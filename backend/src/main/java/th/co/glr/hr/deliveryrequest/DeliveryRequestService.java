package th.co.glr.hr.deliveryrequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.auth.WarehouseStaff;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.ApprovedQuotationDto;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.ConfirmInvoiceRequest;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.InvoiceDto;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.InvoiceRead;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.LineDto;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.OrderRef;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.RoundDto;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.RoundsResponse;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.SystemDocRef;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.TaxInvoiceUpload;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.UpdateRequest;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.ViewerDto;
import th.co.glr.hr.dealquotation.DealQuotationService;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.deposit.RemainingInvoiceService;
import th.co.glr.hr.importrequest.ImportRequestService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.ticket.AttachType;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.RoundDeliveryRequest;
import th.co.glr.hr.ticket.TicketRepository;
import java.time.Instant;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Delivery rounds of one order (V236): sales drafts a request, attaches files and sends it to the warehouse. Read is the
 * deal-read rule ({@link TicketService#requireDealReadAccess}); sales writes are {@link TicketService#canWriteDeliveryRounds}
 * (owner, keyer, grant holder or CEO), else 403 before any 409. The warehouse, invoice and delivery steps are later slices.
 */
@Service
public class DeliveryRequestService {
    private static final String NOT_THIS_DEAL = "เอกสารนี้ไม่ใช่ของงานนี้";
    private static final String FORBIDDEN = "ไม่มีสิทธิ์เข้าถึงรายการนี้";
    /** The Thai labels the deal history shows for the two statuses a void steps back to. */
    private static final Map<String, String> PAYMENT_LABEL = Map.of("CUSTOMER_CONFIRMED", "ลูกค้ายืนยันคำสั่งซื้อ",
        "DEPOSIT_NOTICE_ISSUED", "ออกใบแจ้งมัดจำ");
    private static final BigDecimal INVOICE_ROUNDING_TOLERANCE = BigDecimal.ONE;
    private static final Set<String> OPEN = Set.of(DeliveryRequestDtos.DRAFT, DeliveryRequestDtos.SENT,
        DeliveryRequestDtos.RETURNED, DeliveryRequestDtos.FORWARDED);
    private static final Set<String> EDITABLE = Set.of(DeliveryRequestDtos.DRAFT, DeliveryRequestDtos.RETURNED);

    /** Server-side upload allow-list for round files: PDF, images, Excel. Size is FileStorageService's app.uploads-max-bytes. */
    static final Map<String, String> MIME_BY_EXT = Map.ofEntries(
        Map.entry("pdf", "application/pdf"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
        Map.entry("png", "image/png"), Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
        Map.entry("heic", "image/heic"), Map.entry("heif", "image/heif"), Map.entry("xls", "application/vnd.ms-excel"),
        Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    static final Set<String> ALLOWED_MIME = Set.copyOf(MIME_BY_EXT.values());
    /** Files one round may hold. */
    static final int MAX_FILES_PER_ROUND = 30;
    private static final String TYPES_MESSAGE = "รองรับเฉพาะไฟล์ PDF, รูปภาพ (JPG, PNG, GIF, WEBP, HEIC) หรือ Excel (XLS, XLSX)";

    private final DeliveryRequestRepository rounds;
    private final TicketRepository tickets;
    private final TicketService ticketService;
    private final PrePortal prePortal;
    private final NotificationRepository notifications;
    private final AttachmentRepository attachments;
    private final FileStorageService fileStorage;
    private final EmployeeAuthRepository employees;
    private final DealQuotationService quotations;
    private final DepositNoticeService depositNotices;
    private final RemainingInvoiceService remainingInvoices;
    private final ImportRequestService importRequests;

    public DeliveryRequestService(DeliveryRequestRepository rounds, TicketRepository tickets, TicketService ticketService,
                                  NotificationRepository notifications, AttachmentRepository attachments,
                                  FileStorageService fileStorage, EmployeeAuthRepository employees,
                                  DealQuotationService quotations, DepositNoticeService depositNotices,
                                  RemainingInvoiceService remainingInvoices, ImportRequestService importRequests,
                                  PrePortal prePortal) {
        this.rounds = rounds;
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.notifications = notifications;
        this.attachments = attachments;
        this.fileStorage = fileStorage;
        this.employees = employees;
        this.quotations = quotations;
        this.depositNotices = depositNotices;
        this.remainingInvoices = remainingInvoices;
        this.importRequests = importRequests;
        this.prePortal = prePortal;
    }

    private th.co.glr.hr.customerquotation.CustomerQuotationService customerQuotations;

    /** Setter-injected so the hand-wired test call sites keep compiling (the PDF of an older pricing-request quotation). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void wireCustomerQuotations(th.co.glr.hr.customerquotation.CustomerQuotationService customerQuotations) {
        this.customerQuotations = customerQuotations;
    }

    public RoundsResponse list(long ticketId, UserPrincipal actor) {
        RoundsResponse r = listFor(ticketId, actor);
        // Import stays price-free: no round file (they carry prices, the tax invoice included) and no invoice money.
        return "import".equals(actor.role()) ? priceFree(r) : r;
    }

    private RoundsResponse listFor(long ticketId, UserPrincipal actor) {
        if (WarehouseStaff.is(actor)) {
            return listForWarehouse(ticketId, holdsGrant(actor));
        }
        boolean holder = holdsGrant(actor);
        // Account reads the finance view of a deal, never the ticket routes: inside its list scope it may READ the rounds
        // sales has sent (a draft is sales-private), and acts on none of them (only the issuer grant adds actions).
        if ("account".equals(actor.role())) {
            if (tickets.isInAccountScope(ticketId)) {
                return listForAccount(ticketId, actor, holder);
            }
            if (holder && rounds.hasForwardedRound(ticketId)) {
                return listForIssuer(ticketId);
            }
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        try {
            ticketService.requireDealReadAccess(ticketId, actor);
        } catch (ApiException refused) {
            // The invoice issuer is a person, not a role: refused the deal, it still reads the rounds forwarded to it.
            if (refused.getStatus() == HttpStatus.FORBIDDEN && holder && rounds.hasForwardedRound(ticketId)) {
                return listForIssuer(ticketId);
            }
            throw refused;
        }
        TicketSummaryDto s = summary(ticketId);
        boolean canWrite = ticketService.canWriteDeliveryRounds(s, actor);
        List<ApprovedQuotationDto> approved = withPreDepositMax(s, rounds.approvedQuotations(ticketId));
        VoidCtx vctx = voidCtx(ticketId, holder);
        Memo memo = new Memo();
        List<RoundDto> dtos = rounds.findByTicket(ticketId).stream().map(r -> toDto(r, s, canWrite, false, holder, vctx, memo)).toList();
        if (ticketService.readsOnlyByGrant(s, actor)) {
            // The read-all grant reads rounds sales has sent, never sales' private drafts (and their files), nor
            // that one is open: open/canStart are worked out after this filter.
            dtos = dtos.stream().filter(r -> !DeliveryRequestDtos.DRAFT.equals(r.status())).toList();
        }
        boolean open = dtos.stream().anyMatch(r -> OPEN.contains(r.status()));
        String blocked = open ? null : startBlockedReason(s, canWrite, approved);
        return new RoundsResponse(orderRef(s), dtos, approved, !open && blocked == null, blocked, invoiceable(ticketId));
    }

    private RoundsResponse listForAccount(long ticketId, UserPrincipal actor, boolean holder) {
        TicketSummaryDto s = summary(ticketId);
        VoidCtx vctx = voidCtx(ticketId, true);
        Memo memo = new Memo();
        List<RoundDto> dtos = rounds.findByTicket(ticketId).stream()
            .filter(r -> !DeliveryRequestDtos.DRAFT.equals(r.status()))
            .map(r -> toDto(r, s, false, false, holder, vctx, memo)).toList();
        return new RoundsResponse(orderRef(s), dtos, List.of(), false, "ฝ่ายขายเจ้าของงานเป็นผู้ขอออกใบกำกับ", invoiceable(ticketId));
    }

    /** While a send asks for the pre-portal deposit, each quotation carries the most that deposit may be: its total (VAT
     * included, as the confirm's payable) less the money already on the deal. */
    private List<ApprovedQuotationDto> withPreDepositMax(TicketSummaryDto s, List<ApprovedQuotationDto> approved) {
        if (!asksPreDeposit(s)) {
            return approved;
        }
        BigDecimal paid = tickets.sumPaid(s.id());
        return approved.stream().map(q -> new ApprovedQuotationDto(q.id(), q.number(), q.total(), q.docStatus(),
            q.depositBlockedReason(), q.total().subtract(paid).max(BigDecimal.ZERO), q.legacy())).toList();
    }

    /** What the order may still be invoiced for: payable minus the confirmed invoices, never below zero. */
    private BigDecimal invoiceable(long ticketId) {
        return tickets.payableAmount(ticketId).subtract(rounds.invoicedTotal(ticketId)).max(BigDecimal.ZERO);
    }

    private static RoundsResponse priceFree(RoundsResponse r) {
        List<RoundDto> dtos = r.rounds().stream().map(d -> new RoundDto(d.id(), d.roundNo(), d.status(), d.quotationId(),
            d.quotationNumber(), d.note(), d.returnReasonCodes(), d.returnReasonText(), d.requestedByName(), d.sentAt(),
            d.forwardedByName(), d.forwardedAt(), priceFree(d.invoice()), d.lines(), List.of(), d.untickedDocs(), d.viewer(),
            d.customerName(), d.taxId(), d.branch(), d.customerFromDeal())).toList();
        return new RoundsResponse(r.order(), dtos, r.approvedQuotations(), r.canStart(), r.startBlockedReason(), null);
    }

    private static InvoiceDto priceFree(InvoiceDto i) {
        return i == null ? null : new InvoiceDto(i.number(), i.date(), null, null, null, null, i.invoicedByName(), i.invoicedAt());
    }

    /** The invoice issuer works through the round endpoints only: the forwarded (and invoiced) rounds of an order, no
     * quotation money, nothing of a round that is still with sales or the warehouse. */
    private RoundsResponse listForIssuer(long ticketId) {
        TicketSummaryDto s = summary(ticketId);
        VoidCtx vctx = voidCtx(ticketId, true);
        Memo memo = new Memo();
        List<RoundDto> dtos = rounds.findByTicket(ticketId).stream()
            .filter(r -> Set.of(DeliveryRequestDtos.FORWARDED, DeliveryRequestDtos.INVOICED).contains(r.status()))
            .map(r -> toDto(r, s, false, false, true, vctx, memo)).toList();
        return new RoundsResponse(orderRef(s), dtos, List.of(), false, "ฝ่ายขายเจ้าของงานเป็นผู้ขอออกใบกำกับ", invoiceable(ticketId));
    }

    /** The warehouse works through the round endpoints only (never the deal): once sales has sent a request it sees that
     * order's sent rounds, with no quotation money (the order block is names and codes, the quotation list is empty). */
    private RoundsResponse listForWarehouse(long ticketId, boolean issuerFlag) {
        if (!rounds.hasSentRound(ticketId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        TicketSummaryDto s = summary(ticketId);
        VoidCtx vctx = voidCtx(ticketId, issuerFlag);
        Memo memo = new Memo();
        List<RoundDto> dtos = rounds.findByTicket(ticketId).stream()
            .filter(r -> !DeliveryRequestDtos.DRAFT.equals(r.status())).map(r -> toDto(r, s, false, true, issuerFlag, vctx, memo)).toList();
        return new RoundsResponse(orderRef(s), dtos, List.of(), false, "ฝ่ายขายเจ้าของงานเป็นผู้ขอออกใบกำกับ", null);
    }

    /**
     * Record goods delivered against this INVOICED round: sales (the delivery-write rule) or any warehouse staff. The
     * quantity rules, the tax-invoice rule and every stage effect are TicketService's (one flow, not a copy). Answers
     * the round as the caller reads it, so the screen refreshes without reading the deal.
     */
    @Transactional
    public RoundDto recordDelivery(long rid, RoundDeliveryRequest request, UserPrincipal actor) {
        var round = rounds.findById(rid).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรอบส่งที่ขอ"));
        // The warehouse acts only on a round it can read: never a draft, never a deal that was not sent to it. Checked
        // before anything is written; the whole call is one transaction, so a refused answer leaves no delivery behind.
        if (WarehouseStaff.is(actor)) {
            requireWarehouseRead(round);
            if (!rounds.hasSentRound(round.ticketId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
            }
        }
        ticketService.recordRoundDelivery(round.ticketId(), rid, request, actor);
        if (WarehouseStaff.is(actor)) {
            boolean grant = holdsGrant(actor);
            return toDto(rounds.findById(rid).orElseThrow(), summary(round.ticketId()), false, true, grant, voidCtx(round.ticketId(), grant));
        }
        return listFor(round.ticketId(), actor).rounds().stream().filter(d -> d.id() == rid).findFirst().orElseThrow();
    }

    @Transactional
    public RoundDto createDraft(long ticketId, Long quotationId, UserPrincipal actor) {
        TicketSummaryDto s = summary(ticketId);
        requireWrite(s, actor);
        tickets.lockTicketForUpdate(ticketId);
        var existing = rounds.findOpen(ticketId);
        if (existing.isPresent()) {
            return toDto(existing.get(), s, true, false, false, VoidCtx.NONE);
        }
        String blocked = startBlockedReason(s, true, rounds.approvedQuotations(ticketId));
        if (blocked != null) {
            throw new ApiException(HttpStatus.CONFLICT, blocked);
        }
        Long chosen = resolveQuotation(ticketId, quotationId);
        long id = rounds.insertDraft(ticketId, rounds.nextRoundNo(ticketId), chosen, actor.id());
        return toDto(rounds.findById(id).orElseThrow(), s, true, false, false, VoidCtx.NONE);
    }

    @Transactional
    public RoundDto update(long rid, UpdateRequest request, UserPrincipal actor) {
        var round = rounds.findById(rid).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรอบส่งที่ขอ"));
        TicketSummaryDto s = summary(round.ticketId());
        requireWrite(s, actor);
        tickets.lockTicketForUpdate(round.ticketId());
        round = rounds.findById(rid).orElseThrow();
        requireEditable(round);
        Long quotationId = request.quotationId() != null ? validQuotation(round.ticketId(), request.quotationId())
            : round.quotationId();
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        rounds.updateDraft(rid, quotationId, note, validUnticked(round.ticketId(), request.untickedDocs()));
        return toDto(rounds.findById(rid).orElseThrow(), s, true, false, false, VoidCtx.NONE);
    }

    /** One more file on the round: sales may attach while the round is open (draft, sent, returned, forwarded); the
     * warehouse may attach once it is sent, until it is returned or closed (SENT or FORWARDED). Once INVOICED, either
     * may add proof of delivery (kind DELIVERY_PROOF); the delete rule keeps it (a past-SENT round's files are frozen). */
    @Transactional
    public RoundDto addFile(long rid, MultipartFile file, UserPrincipal actor) {
        var round = rounds.findById(rid).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรอบส่งที่ขอ"));
        TicketSummaryDto s = summary(round.ticketId());
        boolean warehouse = WarehouseStaff.is(actor);
        if (warehouse) {
            requireWarehouseRead(round);
        } else {
            requireWrite(s, actor);
        }
        tickets.lockTicketForUpdate(round.ticketId());
        round = rounds.findById(rid).orElseThrow();
        // An INVOICED round takes proof of delivery (a photo of the signed note) from sales or the warehouse.
        boolean proof = DeliveryRequestDtos.INVOICED.equals(round.status());
        boolean attachable = proof || (warehouse
            ? Set.of(DeliveryRequestDtos.SENT, DeliveryRequestDtos.FORWARDED).contains(round.status())
            : OPEN.contains(round.status()));
        if (!attachable) {
            throw new ApiException(HttpStatus.CONFLICT, "รอบนี้ปิดแล้ว แนบไฟล์เพิ่มไม่ได้");
        }
        String mime = allowedMime(file);
        if (rounds.files(rid).stream().filter(f -> !AttachType.SYSTEM_DOC.equals(f.kind())).count() >= MAX_FILES_PER_ROUND) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "แนบไฟล์ได้ไม่เกิน " + MAX_FILES_PER_ROUND + " ไฟล์ต่อรอบ");
        }
        FileStorageService.StoredFile stored = fileStorage.store("tickets", round.ticketId(), file, Set.of());
        fileStorage.deleteOnRollback(stored);
        attachments.save(round.ticketId(), null, stored.fileName(), stored.filePath(), mime,
            stored.fileSize(), proof ? AttachType.DELIVERY_PROOF : AttachType.OTHER, actor.id(), rid, warehouse ? "WAREHOUSE" : "SALES");
        return toDto(rounds.findById(rid).orElseThrow(), s, !warehouse, warehouse, false, VoidCtx.NONE);
    }

    /** The warehouse passes the round to whoever issues the tax invoice. Only from SENT: 409 once anyone has acted. */
    @Transactional
    public RoundDto forward(long rid, UserPrincipal actor) {
        var round = lockedWarehouseRound(rid, actor);
        TicketSummaryDto s = summary(round.ticketId());
        // Forwarding hands the round to someone: with nobody to hand it to it would sit forwarded for good.
        // The refusal counts every holder (a warehouse person who is the only one still forwards to themself); only the
        // bell leaves the actor out.
        List<Long> holders = notifications.taxInvoiceIssuerIds();
        if (holders.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ยังไม่มีผู้ออกใบกำกับภาษีในระบบ กรุณาแจ้งผู้ดูแล");
        }
        List<Long> issuers = holders.stream().filter(id -> id != actor.id()).toList();
        rounds.markForwarded(rid, actor.id());
        tickets.addEvent(s.id(), actor.id(), actor.name(), TicketEventKind.DELIVERY_REQUEST_FORWARDED, null, null,
            "ส่งต่อให้ออกใบกำกับ รอบส่งที่ " + round.roundNo());
        notifications.notifyTaxInvoiceIssuers(issuers, TicketEventKind.DELIVERY_REQUEST_FORWARDED,
            displayCode(s) + " รอบส่งที่ " + round.roundNo() + " · " + buyerName(s, round.quotationNumber())
                + " · คลังตรวจเอกสารแล้ว รอออกใบกำกับภาษี",
            "/tickets/" + s.id() + "?tab=ship");
        return toDto(rounds.findById(rid).orElseThrow(), s, false, true, false, VoidCtx.NONE);
    }

    private static final Map<String, String> RETURN_REASONS = Map.of(
        "ADDRESS_TAX_ID", "ที่อยู่ออกใบกำกับ / เลขผู้เสียภาษี",
        "PAYMENT_PROOF", "หลักฐานการชำระเงิน",
        "DELIVERY_DATE_PLACE", "วันและสถานที่ส่ง");

    /** The warehouse sends a round back to sales with a reason (a known code, free text, or both); sales may edit it and
     * send it again. The owner, the keyer and the sender (not the warehouse person) are told, with the reason. */
    @Transactional
    public RoundDto sendBack(long rid, DeliveryRequestDtos.ReturnRequest request, UserPrincipal actor) {
        var round = lockedWarehouseRound(rid, actor);
        List<String> codes = request == null || request.reasonCodes() == null ? List.of()
            : request.reasonCodes().stream().distinct().toList();
        String detail = request == null || request.detail() == null || request.detail().isBlank() ? null
            : request.detail().trim();
        if (codes.contains(null) || !RETURN_REASONS.keySet().containsAll(codes) || (codes.isEmpty() && detail == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ระบุเหตุผลที่ส่งกลับ");
        }
        TicketSummaryDto s = summary(round.ticketId());
        rounds.markReturned(rid, actor.id(), codes, detail);
        String reason = String.join(", ", java.util.stream.Stream.concat(
            codes.stream().map(RETURN_REASONS::get), java.util.stream.Stream.ofNullable(detail)).toList());
        tickets.addEvent(s.id(), actor.id(), actor.name(), TicketEventKind.DELIVERY_REQUEST_RETURNED, null, null,
            "ส่งกลับฝ่ายขาย รอบส่งที่ " + round.roundNo() + " · " + reason);
        String message = displayCode(s) + " รอบส่งที่ " + round.roundNo() + " · คลังส่งกลับ: " + reason;
        java.util.Set<Long> told = new java.util.LinkedHashSet<>();
        told.add(s.createdById());
        if (s.keyedById() != null) {
            told.add(s.keyedById());
        }
        if (round.requestedById() != null) {
            told.add(round.requestedById());
        }
        told.remove(actor.id());
        told.forEach(id -> notifications.notifyEmployeeAtLink(id, TicketEventKind.DELIVERY_REQUEST_RETURNED, message,
            "/tickets/" + s.id() + "?tab=ship"));
        return toDto(rounds.findById(rid).orElseThrow(), s, false, true, false, VoidCtx.NONE);
    }

    /** What the upload's checks and the PDF read produced, computed BEFORE any transaction or row lock. */
    public record PreparedInvoice(TaxInvoicePdfReader.Read read) {}

    /** The grant check, the file checks and the bounded PDF read: no database transaction, no ticket lock held while an
     * untrusted file is parsed. A read that fails in any way is "nothing read", never an error. */
    public PreparedInvoice prepareTaxInvoice(MultipartFile file, UserPrincipal actor) {
        requireIssuer(actor);
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์ว่างเปล่า");
        }
        if (file.getSize() > fileStorage.maxUploadBytes()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์ใหญ่เกินไป (ขนาดสูงสุดที่รองรับคือ "
                + (fileStorage.maxUploadBytes() / (1024 * 1024)) + " MB)");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "อ่านไฟล์ไม่ได้");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".pdf") || bytes.length < 5 || !"%PDF-".equals(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ใบกำกับภาษีต้องเป็นไฟล์ PDF");
        }
        return new PreparedInvoice(TaxInvoicePdfReader.read(bytes));
    }

    /** Convenience for callers with no controller in front: prepares, then attaches. */
    @Transactional
    public TaxInvoiceUpload attachTaxInvoice(long rid, MultipartFile file, UserPrincipal actor) {
        return attachTaxInvoice(rid, file, actor, prepareTaxInvoice(file, actor));
    }

    /** The invoice issuer attaches the issued tax invoice (an Express PDF) to a FORWARDED round and gets what the system
     * read from it to check. A re-upload replaces an earlier unconfirmed file (an event names who replaced whose file when
     * they differ). Nothing is recorded until {@link #confirmInvoice}. */
    @Transactional
    public TaxInvoiceUpload attachTaxInvoice(long rid, MultipartFile file, UserPrincipal actor, PreparedInvoice prepared) {
        requireIssuer(actor);
        var round = lockedRound(rid);
        requireForwarded(round);
        TicketSummaryDto s = summary(round.ticketId());
        for (var old : rounds.invoiceFileOwners(rid)) {
            Number by = (Number) old.get("uploaded_by");
            if (by != null && by.longValue() != actor.id()) {
                tickets.addEvent(s.id(), actor.id(), actor.name(), TicketEventKind.TAX_INVOICE_FILE_REPLACED, null, null,
                    "รอบส่งที่ " + round.roundNo() + " · เปลี่ยนไฟล์ใบกำกับ " + old.get("file_name") + " ที่ "
                    + (old.get("uploader_name") == null ? "ผู้อื่น" : old.get("uploader_name")) + " แนบไว้");
            }
        }
        rounds.deleteInvoiceFiles(rid).forEach(fileStorage::deleteOnCommit);
        FileStorageService.StoredFile stored = fileStorage.store("tickets", round.ticketId(), file, Set.of());
        fileStorage.deleteOnRollback(stored);
        var saved = attachments.save(round.ticketId(), null, stored.fileName(), stored.filePath(), "application/pdf",
            stored.fileSize(), AttachType.INVOICE, actor.id(), rid, "ISSUER");
        TaxInvoicePdfReader.Read read = prepared.read();
        return new TaxInvoiceUpload(saved.id(), new InvoiceRead(read.invoiceNumber(), read.invoiceDate(), read.total()),
            read.readOk(), read.totalWeak());
    }

    /**
     * The issuer confirms the three values (read, or typed). The round becomes INVOICED and the invoice counts as money
     * received, but only the part not already on the order: {@code toRecord = min(payable - paid, max(0, invoiced so far -
     * paid))}, where invoiced so far is every INVOICED round of the order plus this one. A deposit account already
     * confirmed, earlier rounds and Express uploads are all inside {@code paid}, so nothing counts twice. The invoices of
     * an order together may not exceed what the order is worth (400 naming what is left).
     */
    @Transactional
    public RoundDto confirmInvoice(long rid, ConfirmInvoiceRequest request, UserPrincipal actor) {
        requireIssuer(actor);
        var round = lockedRound(rid);
        requireForwarded(round);
        TicketSummaryDto s = summary(round.ticketId());
        String number = request == null || request.invoiceNumber() == null ? "" : request.invoiceNumber().trim();
        if (number.isEmpty() || number.length() > 60) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ระบุเลขที่ใบกำกับ (ไม่เกิน 60 ตัวอักษร)");
        }
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok"));
        if (request.invoiceDate() == null || request.invoiceDate().isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่ใบกำกับต้องไม่เป็นวันในอนาคต");
        }
        BigDecimal total = request.total();
        if (total == null || total.signum() <= 0 || total.scale() > 2 && total.stripTrailingZeros().scale() > 2
            || total.compareTo(new BigDecimal("999999999999")) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ยอดรวมใบกำกับต้องมากกว่า 0 และไม่เกิน 2 ตำแหน่งทศนิยม");
        }
        total = total.setScale(2, java.math.RoundingMode.UNNECESSARY);
        Long fileId = rounds.invoiceFileId(rid)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "แนบไฟล์ใบกำกับภาษีก่อนยืนยัน"));
        if (!DealLifecycle.ACTIVE.equals(s.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "งานนี้ไม่ได้เปิดอยู่ จึงบันทึกรับเงินจากใบกำกับไม่ได้");
        }
        if (rounds.invoiceNumberUsed(number)) {
            throw new ApiException(HttpStatus.CONFLICT, "เลขที่ใบกำกับนี้ถูกยืนยันไว้ในรอบอื่นแล้ว");
        }
        BigDecimal paid = tickets.sumPaid(s.id());
        BigDecimal payable = tickets.payableAmount(s.id());
        BigDecimal invoicedSoFar = rounds.invoicedTotal(s.id()).add(total);
        // ฿1.00 over is allowed: Express rounds VAT per line, so an invoice can sit a satang or two above the quotation.
        // What is recorded as received is still capped at payable below.
        if (payable.signum() > 0 && invoicedSoFar.compareTo(payable.add(INVOICE_ROUNDING_TOLERANCE)) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ยอดใบกำกับรวมของออเดอร์นี้เกินมูลค่างาน ออกใบกำกับได้อีกไม่เกิน ฿"
                + baht(invoiceable(s.id())));
        }
        BigDecimal toRecord = payable.subtract(paid).min(invoicedSoFar.subtract(paid).max(BigDecimal.ZERO)).max(BigDecimal.ZERO);
        // What the receipt does to the expected payments is read before and after, so a void can undo exactly that.
        var expectedBefore = tickets.expectedSnapshot(s.id());
        Long receiptId = toRecord.signum() > 0
            ? ticketService.recordTaxInvoiceReceipt(s.id(), toRecord, request.invoiceDate(), number, actor) : null;
        String deltas = receiptId == null ? null : TicketRepository.expectedDeltasJson(expectedBefore, tickets.expectedSnapshot(s.id()));
        boolean marked;
        try {
            marked = rounds.markInvoiced(rid, actor.id(), number, request.invoiceDate(), total, toRecord, receiptId, fileId, deltas);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "เลขที่ใบกำกับนี้ถูกยืนยันไว้ในรอบอื่นแล้ว");
        }
        if (!marked) {
            throw new ApiException(HttpStatus.CONFLICT, "เรื่องนี้ถูกดำเนินการแล้ว");
        }
        String money = "ใบกำกับ " + number + " ฿" + baht(total) + " · บันทึกรับเงิน ฿" + baht(toRecord);
        tickets.addEvent(s.id(), actor.id(), actor.name(), TicketEventKind.TAX_INVOICE_CONFIRMED, null, null, money);
        notifyInvoiced(s, round, TicketEventKind.TAX_INVOICE_CONFIRMED,
            displayCode(s) + " รอบส่งที่ " + round.roundNo() + " · ออกใบกำกับ " + number + " แล้ว ส่งของได้", actor);
        return toDto(rounds.findById(rid).orElseThrow(), s, false, false, true, voidCtx(s.id(), true));
    }

    /**
     * Cancels a confirmed tax invoice before any delivery rests on it: one transaction under the ticket lock removes the
     * TAX_INVOICE receipt this confirm created (and only that row), clears the round's invoice data and returns it to
     * FORWARDED so the file can be replaced. For an issuer-grant holder or the account role (inside its scope); a reason is
     * required; the people told at confirm are told again.
     */
    @Transactional
    public RoundDto voidInvoice(long rid, DeliveryRequestDtos.VoidInvoiceRequest request, UserPrincipal actor) {
        requireNotImport(actor);
        var peek = rounds.findById(rid).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรอบส่งที่ขอ"));
        boolean holder = holdsGrant(actor);
        boolean account = "account".equals(actor.role()) && tickets.isInAccountScope(peek.ticketId());
        if (!holder && !account) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        var round = lockedRound(rid);
        if (!DeliveryRequestDtos.INVOICED.equals(round.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "รอบนี้ไม่ได้อยู่ในสถานะออกใบกำกับแล้ว");
        }
        String reason = request == null || request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty() || reason.length() > 300) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ระบุเหตุผลที่ยกเลิกใบกำกับ (ไม่เกิน 300 ตัวอักษร)");
        }
        TicketSummaryDto s = summary(round.ticketId());
        if (!DealLifecycle.ACTIVE.equals(s.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "งานนี้ไม่ได้เปิดอยู่ จึงยกเลิกใบกำกับไม่ได้");
        }
        String blocked = voidCtx(s.id(), true).blocked(round);
        if (blocked != null) {
            throw new ApiException(HttpStatus.CONFLICT, blocked);
        }
        Long receiptId = round.receiptId();
        String expectedDeltas = rounds.expectedDeltasJson(rid);
        if (!rounds.clearInvoice(rid)) {
            throw new ApiException(HttpStatus.CONFLICT, "เรื่องนี้ถูกดำเนินการแล้ว");
        }
        String steppedBack = null;
        if (receiptId != null) {
            steppedBack = ticketService.removeTaxInvoiceReceipt(s.id(), receiptId, expectedDeltas);
        }
        String what = "ใบกำกับ " + round.invoiceNumber() + " ฿" + baht(round.invoiceTotal())
            + " · ลบรายการรับเงิน ฿" + baht(round.recordedAmount() == null ? BigDecimal.ZERO : round.recordedAmount());
        tickets.addEvent(s.id(), actor.id(), actor.name(), TicketEventKind.TAX_INVOICE_VOIDED, null, null,
            "รอบส่งที่ " + round.roundNo() + " · ยกเลิก" + what + " · เหตุผล: " + reason
            + (steppedBack == null ? "" : " · สถานะการชำระเงินย้อนกลับเป็น " + PAYMENT_LABEL.getOrDefault(steppedBack, steppedBack))
            + " · ขั้นของงานคงไว้ตามเดิม ไม่ย้อนกลับ");
        notifyInvoiced(s, round, TicketEventKind.TAX_INVOICE_VOIDED, displayCode(s) + " รอบส่งที่ " + round.roundNo()
            + " · ยกเลิกใบกำกับ " + round.invoiceNumber() + " แล้ว รอออกใบกำกับใหม่ · " + reason, actor);
        return toDto(rounds.findById(rid).orElseThrow(), s, false, false, holder, voidCtx(s.id(), true));
    }

    private static String baht(BigDecimal v) {
        return String.format(Locale.US, "%,.2f", v);
    }

    /** Warehouse (can deliver now), the people who asked (owner, keyer, sender), then account and sales manager (view only). */
    private void notifyInvoiced(TicketSummaryDto s, DeliveryRequestRepository.Row round, String type, String message,
                                UserPrincipal actor) {
        String link = "/tickets/" + s.id() + "?tab=ship";
        java.util.Set<Long> direct = new java.util.LinkedHashSet<>(WarehouseStaff.recipientIds(employees));
        direct.add(s.createdById());
        if (s.keyedById() != null) {
            direct.add(s.keyedById());
        }
        if (round.requestedById() != null) {
            direct.add(round.requestedById());
        }
        direct.remove(actor.id());
        direct.forEach(id -> notifications.notifyEmployeeAtLink(id, type, message, link));
        long[] skip = java.util.stream.LongStream.concat(java.util.stream.LongStream.of(actor.id()),
            direct.stream().mapToLong(Long::longValue)).toArray();
        for (String role : List.of("account", "sales_manager")) {
            notifications.notifyByRoleAtLink(role, type, message, link, skip);
        }
    }

    private void requireIssuer(UserPrincipal actor) {
        requireNotImport(actor);
        if (!employees.canIssueTaxInvoice(actor.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
    }

    /** The grant counts for everyone but the import role (price-free, whatever the grant says). */
    private boolean holdsGrant(UserPrincipal actor) {
        return !"import".equals(actor.role()) && employees.canIssueTaxInvoice(actor.id());
    }

    /** Import stays price-free: even holding the grant it cannot issue (or confirm, or void) a tax invoice. */
    private static void requireNotImport(UserPrincipal actor) {
        if ("import".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "บทบาทนำเข้าไม่สามารถออกใบกำกับได้");
        }
    }

    private DeliveryRequestRepository.Row lockedRound(long rid) {
        var round = rounds.findById(rid).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรอบส่งที่ขอ"));
        tickets.lockTicketForUpdate(round.ticketId());
        return rounds.findById(rid).orElseThrow();
    }

    private static void requireForwarded(DeliveryRequestRepository.Row round) {
        if (!DeliveryRequestDtos.FORWARDED.equals(round.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "เรื่องนี้ถูกดำเนินการแล้ว");
        }
    }

    /** Warehouse staff only (403), the round locked and re-read, still SENT (else 409). */
    private DeliveryRequestRepository.Row lockedWarehouseRound(long rid, UserPrincipal actor) {
        if (!WarehouseStaff.is(actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        var round = rounds.findById(rid).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรอบส่งที่ขอ"));
        tickets.lockTicketForUpdate(round.ticketId());
        round = rounds.findById(rid).orElseThrow();
        if (!DeliveryRequestDtos.SENT.equals(round.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "เรื่องนี้ถูกดำเนินการแล้ว");
        }
        return round;
    }

    /** A DRAFT is the sales side's private work: the warehouse cannot touch it. */
    private void requireWarehouseRead(DeliveryRequestRepository.Row round) {
        if (DeliveryRequestDtos.DRAFT.equals(round.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
    }

    public RoundDto send(long rid, List<SystemDocRef> systemDocs, UserPrincipal actor) {
        return send(rid, systemDocs, null, null, actor);
    }

    /** {@code preDeposit}: required (0 or more) only when this send makes the deal pre-portal; see {@link PrePortal}. */
    @Transactional
    public RoundDto send(long rid, List<SystemDocRef> systemDocs, BigDecimal preDeposit, java.time.LocalDate preDepositDate,
                         UserPrincipal actor) {
        var round = rounds.findById(rid).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรอบส่งที่ขอ"));
        long ticketId = round.ticketId();
        TicketSummaryDto s = summary(ticketId);
        requireWrite(s, actor);
        // Before the row lock: the confirm takes the advisory lock, then the row (and so does PrePortal's flag write).
        requireEditable(round);
        // A deal not confirmed in the portal (stage before ORDER_RECEIVED) is an old one: confirm its order from the chosen
        // quotation, mark it pre-portal with the deposit already paid, and move it to delivery. A pre_portal deal made at the
        // import stage only gets the confirm.
        boolean notConfirmed = DealStage.indexOf(s.salesStage()) < DealStage.indexOf(DealStage.ORDER_RECEIVED);
        Long chosen = round.quotationId() != null ? validQuotation(ticketId, round.quotationId())
            : resolveQuotation(ticketId, null);
        if (notConfirmed && DealLifecycle.ACTIVE.equals(s.lifecycle())) {
            if (chosen == null) {
                throw new ApiException(HttpStatus.CONFLICT, "เลือกใบเสนอราคาของรอบนี้ก่อนส่ง");
            }
            if (asksPreDeposit(s)) {
                prePortal.confirmAndMark(ticketId, chosen, preDeposit, preDepositDate, actor);
            } else {
                prePortal.ensureOrderConfirmed(ticketId, chosen, actor);
            }
            tickets.updateSalesStage(ticketId, DealStage.DELIVERY_SCHEDULING);
            tickets.addEvent(ticketId, actor.id(), actor.name(), TicketEventKind.STAGE_CHANGED, s.salesStage(),
                DealStage.DELIVERY_SCHEDULING, TicketRepository.PRE_PORTAL_SEND_STAGE_NOTE);
            s = summary(ticketId);
        } else {
            prePortal.ensureOrderConfirmed(ticketId, round.quotationId(), actor);
        }
        // The PDFs are rendered BEFORE the row lock (a render can be slow); the lock only stores them, after re-checking
        // that the document set is still the one rendered.
        Long preQuotationId = round.quotationId() != null ? validQuotation(ticketId, round.quotationId()) : resolveQuotation(ticketId, null);
        Map<SystemDocRef, RenderedDoc> rendered = preQuotationId == null ? Map.of()
            : renderSystemDocs(ticketId, validDocs(ticketId, systemDocs, preQuotationId), actor);
        tickets.lockTicketForUpdate(ticketId);
        round = rounds.findById(rid).orElseThrow();
        requireEditable(round);
        // 1. the order must be confirmed (it has lines) and live; 2. a quotation; 3. coverage.
        if (!DealLifecycle.ACTIVE.equals(s.lifecycle()) || !rounds.hasItems(ticketId)) {
            throw new ApiException(HttpStatus.CONFLICT, "ต้องยืนยันออเดอร์ก่อนจึงจะขอออกใบกำกับได้");
        }
        Long quotationId = round.quotationId() != null ? validQuotation(ticketId, round.quotationId())
            : resolveQuotation(ticketId, null);
        if (quotationId == null) {
            throw new ApiException(HttpStatus.CONFLICT, "เลือกใบเสนอราคาของรอบนี้ก่อนส่ง");
        }
        List<DeliveryRequestRepository.Coverage> lines = rounds.coverage(ticketId, rid).stream()
            .filter(c -> c.remaining().signum() > 0).toList();
        if (lines.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ทุกรายการมีรอบส่งครอบคลุมแล้ว");
        }
        Map<SystemDocRef, String> docNumbers = validDocs(ticketId, systemDocs, quotationId);
        boolean same = docNumbers.size() == rendered.size() && docNumbers.entrySet().stream()
            .allMatch(e -> rendered.containsKey(e.getKey()) && rendered.get(e.getKey()).number().equals(e.getValue()));
        if (!same) {
            throw new ApiException(HttpStatus.CONFLICT, "เอกสารของรอบนี้เปลี่ยนไประหว่างส่ง ลองอีกครั้ง");
        }
        rounds.updateDraft(rid, quotationId, round.note(), null);
        rounds.replaceLines(rid, lines);
        storeSystemDocs(ticketId, rid, rendered, actor);
        rounds.markSent(rid, actor.id());
        String message = displayCode(s) + " รอบส่งที่ " + round.roundNo() + " · " + buyerName(s, rounds.findById(rid).orElseThrow().quotationNumber())
            + " · ฝ่ายขายขอออกใบกำกับ รอคลังตรวจเอกสาร";
        tickets.addEvent(ticketId, actor.id(), actor.name(), TicketEventKind.DELIVERY_REQUEST_SENT, null, null,
            "ขอออกใบกำกับ รอบส่งที่ " + round.roundNo());
        String link = "/tickets/" + ticketId + "?tab=ship";
        for (String role : List.of("account", "sales_manager")) {
            notifications.notifyByRoleAtLink(role, TicketEventKind.DELIVERY_REQUEST_SENT, message, link, actor.id());
        }
        // Warehouse staff by the shared effective-role predicate, not by division (a wh person acting as sales is not staff).
        for (long id : WarehouseStaff.recipientIds(employees)) {
            if (id != actor.id()) {
                notifications.notifyEmployeeAtLink(id, TicketEventKind.DELIVERY_REQUEST_SENT, message, link);
            }
        }
        return toDto(rounds.findById(rid).orElseThrow(), s, true, false, false, VoidCtx.NONE);
    }

    /** Each requested document must be an issued or approved document of this order, and a deposit notice or remaining
     * invoice must belong to the round's quotation: else 400, nothing stored. One that names no quotation of this order
     * (blank, a PO number) is attachable only when the order has a single quotation to belong to. */
    private Map<SystemDocRef, String> validDocs(long ticketId, List<SystemDocRef> docs, Long quotationId) {
        Map<SystemDocRef, String> out = new java.util.LinkedHashMap<>();
        for (SystemDocRef d : docs == null ? List.<SystemDocRef>of() : docs) {
            if (d == null || d.kind() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, NOT_THIS_DEAL);
            }
            String number = rounds.systemDocNumber(ticketId, d.kind(), d.id())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, NOT_THIS_DEAL));
            if ("DEPOSIT_NOTICE".equals(d.kind()) || "REMAINING_INVOICE".equals(d.kind())) {
                requireOfQuotation(ticketId, d, quotationId);
            }
            out.putIfAbsent(d, number);
        }
        return out;
    }

    private void requireOfQuotation(long ticketId, SystemDocRef d, Long quotationId) {
        String ref = rounds.docQuotationNumber(ticketId, d.kind(), d.id());
        List<ApprovedQuotationDto> approved = rounds.approvedQuotations(ticketId);
        var named = ref == null ? null : approved.stream().filter(q -> ref.equals(q.number())).findFirst().orElse(null);
        boolean ok = named != null ? quotationId != null && named.id() == quotationId : approved.size() == 1;
        if (!ok) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เอกสารนี้ไม่ใช่ของใบเสนอราคาที่เลือกสำหรับรอบนี้");
        }
    }

    private static final java.util.regex.Pattern DOC_KEY = java.util.regex.Pattern.compile("(QUOTATION|DEPOSIT_NOTICE|REMAINING_INVOICE|IMPORT_REQUEST):\\d{1,18}");

    /** The unticked keys: a malformed key (or a comma) is a 400; a well-formed key that is no longer one of this order's
     * candidate documents (superseded since it was saved) is dropped; the kept list is no longer than the candidates. */
    private List<String> validUnticked(long ticketId, List<String> keys) {
        if (keys == null) {
            return null;
        }
        for (String k : keys) {
            if (k == null || !DOC_KEY.matcher(k).matches()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, NOT_THIS_DEAL);
            }
        }
        java.util.Set<String> candidates = rounds.candidateDocKeys(ticketId);
        List<String> kept = keys.stream().filter(candidates::contains).toList();
        if (kept.size() > Math.min(candidates.size(), 100)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, NOT_THIS_DEAL);
        }
        return kept;
    }

    private record RenderedDoc(String number, byte[] pdf) {}

    /** Renders each document with the SENDER's own access (no lock held), so the warehouse sees the document as it was
     * when sent whatever is revised afterwards. */
    private Map<SystemDocRef, RenderedDoc> renderSystemDocs(long ticketId, Map<SystemDocRef, String> docs, UserPrincipal actor) {
        Map<SystemDocRef, RenderedDoc> out = new java.util.LinkedHashMap<>();
        for (var e : docs.entrySet()) {
            SystemDocRef d = e.getKey();
            byte[] pdf;
            try {
                pdf = switch (d.kind()) {
                    case "QUOTATION" -> rounds.isLegacyPricingQuotation(d.id())
                        ? customerQuotations.renderPdf(d.id(), actor)
                        : quotations.renderPdfForDealDocument(d.id(), ticketId, actor);
                    case "DEPOSIT_NOTICE" -> depositNotices.getPdf(d.id(), actor);
                    case "REMAINING_INVOICE" -> remainingInvoices.getPdf(d.id(), actor);
                    default -> importRequests.renderStored(d.id(), actor);
                };
            } catch (ApiException ex) {
                if (ex.getStatus().is4xxClientError()) {
                    throw ex;
                }
                throw renderFailed(e.getValue());
            } catch (RuntimeException ex) {
                throw renderFailed(e.getValue());
            }
            if (pdf.length > fileStorage.maxUploadBytes()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "เอกสาร " + e.getValue() + " มีขนาดใหญ่เกินไป (ขนาดสูงสุดที่รองรับคือ "
                    + (fileStorage.maxUploadBytes() / (1024 * 1024)) + " MB)");
            }
            out.put(d, new RenderedDoc(e.getValue(), pdf));
        }
        return out;
    }

    /** Stores the rendered copies on the round (under the row lock); a re-send replaces this round's earlier copies. */
    private void storeSystemDocs(long ticketId, long rid, Map<SystemDocRef, RenderedDoc> docs, UserPrincipal actor) {
        rounds.deleteSystemDocs(rid).forEach(fileStorage::deleteOnCommit);
        for (var doc : docs.values()) {
            String name = doc.number().replaceAll("[\\\\/]", "-") + ".pdf";
            FileStorageService.StoredFile stored = fileStorage.storeBytes("tickets", ticketId, name, "application/pdf", doc.pdf());
            fileStorage.deleteOnRollback(stored);
            attachments.save(ticketId, null, stored.fileName(), stored.filePath(), stored.mimeType(), stored.fileSize(),
                AttachType.SYSTEM_DOC, actor.id(), rid, "SYSTEM");
        }
    }

    private static ApiException renderFailed(String docNumber) {
        return new ApiException(HttpStatus.CONFLICT, "สร้างไฟล์ " + docNumber + " ไม่สำเร็จ ลองอีกครั้ง");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private TicketSummaryDto summary(long ticketId) {
        return tickets.findSummaryById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงานนี้"));
    }

    private void requireWrite(TicketSummaryDto s, UserPrincipal actor) {
        if (!ticketService.canWriteDeliveryRounds(s, actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
    }

    private void requireEditable(DeliveryRequestRepository.Row round) {
        if (!EDITABLE.contains(round.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "เรื่องนี้ส่งไปแล้ว");
        }
    }

    private String startBlockedReason(TicketSummaryDto s, boolean canWrite, List<ApprovedQuotationDto> approved) {
        if (!canWrite) {
            return "ฝ่ายขายเจ้าของงานเป็นผู้ขอออกใบกำกับ";
        }
        if (!DealLifecycle.ACTIVE.equals(s.lifecycle())) {
            return "งานนี้ไม่ได้เปิดอยู่ ขอออกใบกำกับไม่ได้";
        }
        if (approved.isEmpty()) {
            if (rounds.hasAcceptedPricingQuotation(s.id())) {
                return "ลูกค้าตอบรับใบเสนอราคาแล้ว ต้องยืนยันออเดอร์ก่อนจึงจะขอออกใบกำกับได้";
            }
            return "ต้องมีใบเสนอราคาที่อนุมัติแล้ว";
        }
        // An old deal is confirmed by its first send, so it has no lines to cover yet.
        if (unconfirmed(s)) {
            return null;
        }
        if (!rounds.hasItems(s.id())) {
            return "ต้องยืนยันออเดอร์ก่อนจึงจะขอออกใบกำกับได้";
        }
        if (rounds.coverage(s.id(), 0).stream().noneMatch(c -> c.remaining().signum() > 0)) {
            return "ทุกรายการมีรอบส่งครอบคลุมแล้ว";
        }
        return null;
    }

    /** The given quotation, or the only approved one; null when several are approved and none was chosen. */
    private Long resolveQuotation(long ticketId, Long wanted) {
        if (wanted != null) {
            return validQuotation(ticketId, wanted);
        }
        List<ApprovedQuotationDto> approved = rounds.approvedQuotations(ticketId);
        return approved.size() == 1 ? approved.get(0).id() : null;
    }

    private Long validQuotation(long ticketId, long quotationId) {
        if (rounds.approvedQuotations(ticketId).stream().noneMatch(q -> q.id() == quotationId)) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคานี้ไม่ใช่ของออเดอร์นี้หรือยังไม่ได้อนุมัติ");
        }
        return quotationId;
    }

    /** The canonical MIME to store. The extension must be allowed; a blank or octet-stream declared type is decided by
     * the extension, any other declared type must itself be allowed. */
    private String allowedMime(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String byExt = MIME_BY_EXT.get(ext);
        if (byExt == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, TYPES_MESSAGE);
        }
        String declared = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT).trim();
        if (declared.isEmpty() || "application/octet-stream".equals(declared)) {
            return byExt;
        }
        if (!ALLOWED_MIME.contains(declared)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, TYPES_MESSAGE);
        }
        return declared;
    }

    /** Per-list answers that do not change between the rounds of one ticket (the buyer by quotation number, the
     * record-block reason), so a list costs one lookup each, not one per round. */
    private static final class Memo {
        final Map<String, DepositNoticeService.QuotationBuyer> buyers = new java.util.HashMap<>();
        boolean blockRead;
        String block;
    }

    private RoundDto toDto(DeliveryRequestRepository.Row r, TicketSummaryDto s, boolean canWrite, boolean warehouse,
                           boolean issuerFlag, VoidCtx vctx) {
        return toDto(r, s, canWrite, warehouse, issuerFlag, vctx, new Memo());
    }

    private RoundDto toDto(DeliveryRequestRepository.Row r, TicketSummaryDto s, boolean canWrite, boolean warehouse,
                           boolean issuerFlag, VoidCtx vctx, Memo memo) {
        boolean editable = canWrite && EDITABLE.contains(r.status());
        List<LineDto> lines = DeliveryRequestDtos.DRAFT.equals(r.status())
            ? rounds.coverage(r.ticketId(), r.id()).stream().filter(c -> c.remaining().signum() > 0)
                .map(c -> new LineDto(c.itemId(), c.description(), c.unit(), c.remaining(), BigDecimal.ZERO)).toList()
            : rounds.lines(r.id());
        InvoiceDto invoice = r.invoiceNumber() == null ? null : new InvoiceDto(r.invoiceNumber(), r.invoiceDate(),
            warehouse ? null : r.invoiceTotal(), warehouse ? null : r.recordedAmount(), warehouse ? null : r.receiptId(),
            r.invoiceAttachmentId(), r.invoicedByName(), r.invoicedAt());
        boolean canIssue = issuerFlag && DeliveryRequestDtos.FORWARDED.equals(r.status());
        boolean invoiced = DeliveryRequestDtos.INVOICED.equals(r.status());
        String voidBlock = invoiced ? vctx.blocked(r) : null;
        // May record only when the goods can leave (the record path's own source rules): never a button that always 409s.
        boolean recordable = invoiced && (canWrite || warehouse) && DealLifecycle.ACTIVE.equals(s.lifecycle())
            && lines.stream().anyMatch(l -> l.coveredQty().compareTo(l.deliveredQty()) > 0);
        if (recordable && !memo.blockRead) {
            memo.block = ticketService.deliveryBlockedReason(r.ticketId(), warehouse);
            memo.blockRead = true;
        }
        String recordBlock = recordable ? memo.block : null;
        boolean canRecord = recordable && recordBlock == null;
        var buyer = memo.buyers.computeIfAbsent(String.valueOf(r.quotationNumber()), k -> depositNotices.quotationBuyer(s, r.quotationNumber()));
        return new RoundDto(r.id(), r.roundNo(), r.status(), r.quotationId(), r.quotationNumber(), r.note(),
            r.returnReasonCodes(), r.returnReasonText(), r.requestedByName(), r.sentAt(), r.forwardedByName(),
            r.forwardedAt(), invoice, lines, rounds.files(r.id()), r.untickedDocs(),
            new ViewerDto(editable, editable, warehouse && DeliveryRequestDtos.SENT.equals(r.status()), canIssue,
                canRecord, invoiced && vctx.scope() && voidBlock == null, invoiced && vctx.scope() ? voidBlock : null,
                recordBlock),
            buyer.name(), warehouse ? null : buyer.taxId(), warehouse ? null : buyer.branch(), buyer.fromDeal());
    }

    /**
     * What a list needs to say whether an INVOICED round may be voided, computed once per list (no per-round query). All
     * must hold: no delivery recorded against the round; it is the order's most recently CONFIRMED invoice (invoiced_at,
     * then id); no receipt of any source was recorded after it (its own receipt, if any, is the ticket's newest; with no
     * receipt of its own, none is newer than the confirmation); no Express import row is settled against its receipt.
     * Other rounds being open does not matter.
     */
    private record VoidCtx(boolean scope, DeliveryRequestRepository.Row latest, Set<Long> delivered, Long newestReceiptId,
                           Instant newestReceiptAt, Set<Long> settled, boolean paidInFull) {
        static final VoidCtx NONE = new VoidCtx(false, null, Set.of(), null, null, Set.of(), false);

        /** null when the round may be voided, else the Thai reason it cannot. */
        String blocked(DeliveryRequestRepository.Row r) {
            if (!DeliveryRequestDtos.INVOICED.equals(r.status())) {
                return "รอบนี้ไม่ได้อยู่ในสถานะออกใบกำกับแล้ว";
            }
            if (delivered.contains(r.id())) {
                return "บันทึกการส่งของของรอบนี้แล้ว ยกเลิกใบกำกับไม่ได้";
            }
            if (latest != null && latest.id() != r.id()) {
                return "ยกเลิกได้เฉพาะใบกำกับที่ยืนยันล่าสุดของออเดอร์ ให้ยกเลิกใบกำกับของรอบส่งที่ " + latest.roundNo() + " ก่อน";
            }
            // confirmFinalPayment with nothing outstanding writes no receipt, so the receipt test below cannot see it.
            if (paidInFull) {
                return "งานนี้ยืนยันรับชำระครบแล้ว ให้ฝ่ายบัญชีปรับยอดแทน";
            }
            boolean later = r.receiptId() != null ? !r.receiptId().equals(newestReceiptId)
                : newestReceiptAt != null && r.invoicedAt() != null && newestReceiptAt.isAfter(r.invoicedAt());
            if (later) {
                return "มีการบันทึกรับเงินหลังใบกำกับนี้แล้ว ให้ฝ่ายบัญชีปรับยอด";
            }
            if (r.receiptId() != null && settled.contains(r.receiptId())) {
                return "ยกเลิกใบกำกับนี้ไม่ได้ เพราะมีการจับคู่รายการชำระเงินจาก Express กับเงินก้อนนี้แล้ว ให้ฝ่ายบัญชีปรับยอดแทน";
            }
            return null;
        }
    }

    private VoidCtx voidCtx(long ticketId, boolean scope) {
        if (!scope) {
            return VoidCtx.NONE;
        }
        var ts = summary(ticketId);
        boolean paidInFull = "FULLY_PAID".equals(ts.paymentStatus()) || "CLOSED_PAID".equals(ts.salesStage());
        var newest = rounds.newestReceipt(ticketId);
        Long newestId = newest == null ? null : ((Number) newest.get("receipt_id")).longValue();
        Instant newestAt = newest == null ? null : ((java.sql.Timestamp) newest.get("created_at")).toInstant();
        return new VoidCtx(true, rounds.latestConfirmed(ticketId).orElse(null), rounds.deliveredRoundIds(ticketId),
            newestId, newestAt, rounds.receiptsSettledByImportRows(ticketId), paidInFull);
    }

    /** The buyer's name for a bell: the quotation's party customer, else the deal's (owner rule #65). */
    private String buyerName(TicketSummaryDto s, String quotationNumber) {
        return depositNotices.quotationBuyer(s, quotationNumber).name();
    }

    private OrderRef orderRef(TicketSummaryDto s) {
        boolean addOn = "ADD_ON".equals(s.childKind());
        int orderNo = 1;
        if (addOn && s.code().contains("-")) {
            try {
                orderNo = Integer.parseInt(s.code().substring(s.code().lastIndexOf('-') + 1));
            } catch (NumberFormatException ignored) {
                // an unexpected code shape keeps order 1
            }
        }
        return new OrderRef(s.id(), s.code(), displayCode(s), orderNo, s.customerName(), s.projectName(),
            addOn ? s.parentCode() : null, s.createdByName(), tickets.isPrePortal(s.id()), unconfirmed(s), asksPreDeposit(s));
    }

    /**
     * An old or pre-portal deal whose order the first send will confirm. Any lines it has are preliminary (the confirm
     * replaces them), so the sheet asks for the deposit exactly when {@code send} requires it.
     */
    /** The ONE rule for "this send asks for the deposit paid before the portal": a live deal not yet confirmed in the
     * portal (before ORDER_RECEIVED) that is not already pre-portal. {@code send} and the order block both read it. */
    private boolean asksPreDeposit(TicketSummaryDto s) {
        return DealLifecycle.ACTIVE.equals(s.lifecycle())
            && DealStage.indexOf(s.salesStage()) < DealStage.indexOf(DealStage.ORDER_RECEIVED) && !tickets.isPrePortal(s.id());
    }

    private boolean unconfirmed(TicketSummaryDto s) {
        boolean old = tickets.isPrePortal(s.id()) || DealStage.indexOf(s.salesStage()) < DealStage.indexOf(DealStage.ORDER_RECEIVED);
        return old && DealLifecycle.ACTIVE.equals(s.lifecycle()) && !tickets.hasPromotionFromQuotationEvent(s.id())
            && !rounds.isConfirmedPricingOrder(s.id());
    }

    private String displayCode(TicketSummaryDto s) {
        return "ADD_ON".equals(s.childKind()) ? s.code() : s.code() + "-1";
    }
}
