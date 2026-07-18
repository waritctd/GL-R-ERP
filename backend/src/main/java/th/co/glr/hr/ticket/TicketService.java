package th.co.glr.hr.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import th.co.glr.hr.pricing.PriceBreakdownItemDto;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.Page;
import th.co.glr.hr.common.PageRequest;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.ticket.TicketResponses.TicketActionDto;
import th.co.glr.hr.ticket.TicketResponses.TicketActionState;
import th.co.glr.hr.ticket.TicketResponses.TicketActionsResponse;

@Service
public class TicketService {
    private static final Set<String> SALES_ROLES  = Set.of("sales");
    private static final Set<String> IMPORT_ROLES = Set.of("import");
    private static final Set<String> CEO_ROLES    = Set.of("ceo");
    private static final Set<String> FULFILMENT_ROLES = Set.of("import", "ceo");
    // Money-receipt confirmations belong to ฝ่ายบัญชี (accounting), with CEO as fallback.
    private static final Set<String> ACCOUNT_ROLES = Set.of("account", "ceo");
    // Who may read tickets at all. Mirrors the frontend's canViewTickets and the mock's
    // list/get gates — hr/employee have no business reading customer pricing.
    // sales_manager is read+comment-only oversight (a project-manager-style follow-up
    // role for the sales team) — it must NEVER be added to SALES_ROLES/IMPORT_ROLES/
    // CEO_ROLES/ACCOUNT_ROLES, only here.
    private static final Set<String> VIEWER_ROLES =
        Set.of("sales", "import", "ceo", "account", "sales_manager");
    private static final Set<String> QUOTATION_ALLOWED_STATUSES =
        Set.of(TicketStatus.APPROVED, TicketStatus.QUOTATION_ISSUED);
    private static final Set<String> PROPOSE_ALLOWED_STATUSES =
        Set.of(TicketStatus.IN_REVIEW, TicketStatus.PRICE_PROPOSED, TicketStatus.APPROVED);
    private static final Set<String> PAYMENT_RECEIPT_KINDS = Set.of("DEPOSIT", "BALANCE", "ADJUSTMENT");
    private static final Set<String> DELIVERY_SOURCES = Set.of("WAREHOUSE", "STOCK");

    private final TicketRepository tickets;
    private final NotificationRepository notifications;
    private final PriceCalcService priceCalcService;
    private final ObjectMapper objectMapper;
    private final CustomerRepository customers;
    private final QuotationRenderer quotationRenderer;

    public TicketService(TicketRepository tickets, NotificationRepository notifications,
                         PriceCalcService priceCalcService, ObjectMapper objectMapper,
                         CustomerRepository customers, QuotationRenderer quotationRenderer) {
        this.tickets           = tickets;
        this.notifications     = notifications;
        this.priceCalcService  = priceCalcService;
        this.objectMapper      = objectMapper;
        this.customers         = customers;
        this.quotationRenderer = quotationRenderer;
    }

    public List<TicketSummaryDto> list(String status, UserPrincipal actor) {
        requireRole(actor, VIEWER_ROLES);
        Long createdByFilter = "sales".equals(actor.role()) ? actor.id() : null;
        return tickets.findSummaries(status, createdByFilter);
    }

    public Page<TicketSummaryDto> listPage(String status, UserPrincipal actor, PageRequest page) {
        requireRole(actor, VIEWER_ROLES);
        Long createdByFilter = "sales".equals(actor.role()) ? actor.id() : null;
        List<TicketSummaryDto> rows = tickets.findSummaries(status, createdByFilter, page);
        // Skip the COUNT round-trip when the whole result set fits on page 0.
        int total = (page.page() == 0 && rows.size() < page.size())
            ? rows.size()
            : tickets.countSummaries(status, createdByFilter);
        return new Page<>(rows, page.page(), page.size(), total);
    }

    public TicketDto get(long id, UserPrincipal actor) {
        return requireViewAccess(id, actor);
    }

    public List<PaymentReceiptDto> listPayments(long ticketId, UserPrincipal actor) {
        requireViewAccess(ticketId, actor);
        return tickets.findReceiptsByTicket(ticketId);
    }

    /**
     * The one read-access rule for a single ticket: viewer role required, and sales
     * reps only see their own tickets. Every endpoint that returns or renders ticket
     * data must go through this.
     */
    private TicketDto requireViewAccess(long ticketId, UserPrincipal actor) {
        requireRole(actor, VIEWER_ROLES);
        TicketDto ticket = requireTicket(ticketId);
        if ("sales".equals(actor.role()) && ticket.summary().createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        return ticket;
    }

    @Transactional
    public TicketDto create(CreateTicketRequest request, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        // V50: every new deal belongs to a โครงการ (one deal = one ticket; a project
        // can hold many deals over time). Pre-existing project-less tickets stay valid.
        if (request.projectId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องเลือกโครงการก่อนสร้างดีล");
        }
        if (request.entryChannel() != null && !request.entryChannel().isBlank()
                && !EntryChannel.isValid(request.entryChannel())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Unknown entry channel '" + request.entryChannel() + "'");
        }
        // Guard priority the same way as entryChannel: an unvalidated value hits the
        // chk_ticket_priority CHECK column in the repository and fails closed (500).
        // Null/blank is fine — the repository defaults it to NORMAL.
        if (request.priority() != null && !request.priority().isBlank()
                && !Priority.isValid(request.priority())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Unknown priority '" + request.priority() + "'");
        }
        String code = tickets.nextTicketCode();
        long id = tickets.create(request, code, actor.id(), actor.name());
        // A lightweight lead-stage deal (no items yet) is the rep's private draft —
        // import/CEO are only notified when it actually enters the price-request
        // flow (created with items, or submitted later).
        if (request.items() != null && !request.items().isEmpty()) {
            notifications.notifyByRole("import", id, "SUBMITTED",
                "Ticket " + code + " รอการรับเรื่อง");
            notifications.notifyByRole("ceo", id, "SUBMITTED",
                "Ticket " + code + " ส่งเข้าระบบแล้ว");
        }
        return requireTicket(id);
    }

    @Transactional
    public TicketDto submit(long ticketId, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto s = loadAndVerifyStatus(ticketId, TicketStatus.DRAFT);
        requireActive(s);
        if (s.createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the ticket owner can submit");
        }
        // A lightweight lead-stage deal has no items yet — the price-request flow
        // needs at least one product line before import can price it.
        if (s.itemCount() == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ต้องเพิ่มรายการสินค้าอย่างน้อย 1 รายการก่อนส่งขอราคา");
        }
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.SUBMITTED, TicketStatus.DRAFT, TicketStatus.SUBMITTED, null);
        notifications.notifyByRole("import", ticketId, "SUBMITTED",
            "Ticket " + s.code() + " รอการรับเรื่อง");
        notifications.notifyByRole("ceo", ticketId, "SUBMITTED",
            "Ticket " + s.code() + " ส่งเข้าระบบแล้ว");
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto pickup(long ticketId, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        TicketSummaryDto s = loadAndVerifyStatus(ticketId, TicketStatus.SUBMITTED);
        requireActive(s);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.PICKED_UP, TicketStatus.SUBMITTED, TicketStatus.IN_REVIEW, null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto proposePrice(long ticketId, ProposePriceRequest request, UserPrincipal actor) {
        if (!IMPORT_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        requireActive(s);
        String currentStatus = s.status();
        if (!PROPOSE_ALLOWED_STATUSES.contains(currentStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Cannot propose price when ticket is '" + currentStatus + "'");
        }
        tickets.replaceItems(ticketId, request.items());
        String snapshot = buildItemSnapshot(request.items());
        boolean isRevision = !TicketStatus.IN_REVIEW.equals(currentStatus);
        String eventKind = isRevision ? TicketEventKind.PRICE_REVISED : TicketEventKind.PRICE_PROPOSED;
        tickets.addEventWithSnapshot(ticketId, actor.id(), actor.name(),
            eventKind, currentStatus, TicketStatus.PRICE_PROPOSED, request.note(), snapshot);
        notifications.notifyByRole("ceo", ticketId, "PRICE_PROPOSED",
            "Ticket " + s.code() + (isRevision ? " มีการแก้ไขราคาเสนอ — กรุณาตรวจสอบใหม่" : " มีราคาเสนอรอการอนุมัติ"));
        return requireTicket(ticketId);
    }

    private String buildItemSnapshot(List<TicketItemRequest> items) {
        try {
            record ItemSnap(String brand, String model, BigDecimal qty,
                            BigDecimal rawPrice, String rawCurrency, String rawUnit) {}
            var snaps = items.stream()
                .map(it -> new ItemSnap(it.brand(), it.model(), it.qty(),
                                        it.rawPrice(), it.rawCurrency(), it.rawUnit()))
                .toList();
            return objectMapper.writeValueAsString(snaps);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public TicketDto approve(long ticketId, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        TicketSummaryDto s = loadAndVerifyStatus(ticketId, TicketStatus.PRICE_PROPOSED);
        requireActive(s);
        tickets.approveItemPrices(ticketId);
        tickets.setHasEdits(ticketId, false);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.APPROVED, TicketStatus.PRICE_PROPOSED, TicketStatus.APPROVED, null);
        notifications.notifyEmployee(s.createdById(), ticketId, "APPROVED",
            "Ticket " + s.code() + " ได้รับการอนุมัติราคาแล้ว — กด Generate ใบเสนอราคาได้เลย");
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto reject(long ticketId, RejectRequest request, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        TicketSummaryDto s = loadAndVerifyStatus(ticketId, TicketStatus.PRICE_PROPOSED);
        requireActive(s);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.REJECTED, TicketStatus.PRICE_PROPOSED, TicketStatus.IN_REVIEW, request.reason());
        notifications.notifyByRole("import", ticketId, "REJECTED",
            "Ticket " + s.code() + " ถูกตีกลับ — กรุณาแก้ไขราคาเสนอ");
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto generateQuotation(long ticketId, GenerateQuotationRequest request, UserPrincipal actor) {
        if (request == null || request.recipientType() == null || request.recipientType().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุผู้รับใบเสนอราคา");
        }
        String recipientType = request.recipientType().trim();
        if (!QuotationRecipient.isValid(recipientType) || QuotationRecipient.UNSPECIFIED.equals(recipientType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown quotation recipient '" + recipientType + "'");
        }
        requireRole(actor, SALES_ROLES);
        TicketDto full = requireTicket(ticketId);
        TicketSummaryDto s = full.summary();
        requireActive(s);
        String fromStatus = s.status();
        if (!QUOTATION_ALLOWED_STATUSES.contains(fromStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Expected status 'approved' or 'quotation_issued' but ticket is '" + fromStatus + "'");
        }
        if (s.createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the ticket owner can generate a quotation");
        }
        boolean acceptedInChain = full.quotations().stream()
            .anyMatch(q -> recipientType.equals(q.recipientType()) && QuotationStatus.ACCEPTED.equals(q.docStatus()));
        boolean amendmentReasonRequired = acceptedInChain || s.paymentStatus() != null;
        if (amendmentReasonRequired && (request.amendmentReason() == null || request.amendmentReason().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ต้องระบุเหตุผลการแก้ไขใบเสนอราคาหลังลูกค้ายืนยันหรือมีใบที่ accepted แล้ว");
        }
        BigDecimal total = full.items().stream()
            .map(item -> {
                BigDecimal price = item.approvedPrice() != null ? item.approvedPrice() : BigDecimal.ZERO;
                return price.multiply(item.qty());
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        String number = tickets.nextQuotationCode();
        QuotationDto created = tickets.createQuotation(ticketId, number, actor.id(), total, recipientType,
            blankToNull(request.recipientLabel()), blankToNull(request.paymentTerms()),
            blankToNull(request.leadTime()), blankToNull(request.deliveryTerms()), request.validityDate(),
            request.offerDate(), request.depositPercent(), request.deliveryLeadDays());

        // Freeze this quotation at issue time (V49): item data + customer/project header,
        // in the same transaction as createQuotation, so a later ticket edit or customer-
        // record change can never alter an already-issued quotation's downloaded content
        // (legal-compliance requirement — quotation v1 re-downloaded after a revision must
        // still show v1's items/prices, not today's).
        tickets.insertQuotationItems(created.id(), full.items());
        CustomerDto customer = s.customerId() != null ? customers.findById(s.customerId()).orElse(null) : null;
        // Freeze what the renderer would have PRINTED at issue time: the header name is
        // the TICKET's customer display name (toXlsx/toPdf have always rendered
        // s.customerName()), with the master record's name only as a fallback;
        // address/taxId/phone come from the master record because that's what the live
        // render pulls from CustomerDto.
        String issuedCustomerName = s.customerName() != null && !s.customerName().isBlank()
            ? s.customerName()
            : (customer != null ? customer.name() : null);
        tickets.updateQuotationHeader(created.id(),
            issuedCustomerName,
            customer != null ? customer.address() : null,
            customer != null ? customer.taxId() : null,
            customer != null ? customer.phone() : null,
            s.projectName());

        String eventMessage = "recipient_type=" + recipientType + ", version=" + created.quotationVersion()
            + (request.amendmentReason() != null && !request.amendmentReason().isBlank()
                ? " — amendment: " + request.amendmentReason().trim()
                : "");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.QUOTATION_ISSUED, fromStatus, TicketStatus.QUOTATION_ISSUED, eventMessage);
        if (QuotationRecipient.DESIGNER.equals(recipientType) || QuotationRecipient.OWNER.equals(recipientType)) {
            autoAdvanceStage(s, DealStage.QUOTE_DESIGN_SIDE, actor);
        } else if (QuotationRecipient.BUYER.equals(recipientType)) {
            autoAdvanceStage(s, DealStage.QUOTE_BUYER, actor);
        }
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto markQuotationSent(long ticketId, long quotationId, String note, UserPrincipal actor) {
        return markQuotationLifecycle(ticketId, quotationId, QuotationStatus.SENT,
            TicketEventKind.QUOTATION_SENT, note, actor);
    }

    @Transactional
    public TicketDto markQuotationAccepted(long ticketId, long quotationId, String note, UserPrincipal actor) {
        return markQuotationLifecycle(ticketId, quotationId, QuotationStatus.ACCEPTED,
            TicketEventKind.QUOTATION_ACCEPTED, note, actor);
    }

    @Transactional
    public TicketDto markQuotationRejected(long ticketId, long quotationId, String note, UserPrincipal actor) {
        return markQuotationLifecycle(ticketId, quotationId, QuotationStatus.REJECTED,
            TicketEventKind.QUOTATION_REJECTED, note, actor);
    }

    private TicketDto markQuotationLifecycle(long ticketId, long quotationId, String targetStatus,
                                             String eventKind, String note, UserPrincipal actor) {
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        requireQuotationWriteAccess(s, actor);
        requireActive(s);
        QuotationDto quotation = ticket.quotations().stream()
            .filter(q -> q.id() == quotationId)
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Quotation not found"));
        if (!legalQuotationTransition(quotation.docStatus(), targetStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Cannot mark quotation " + targetStatus + " from " + quotation.docStatus());
        }
        tickets.markQuotationStatus(ticketId, quotationId, targetStatus);
        String message = quotation.number() + " (" + quotation.recipientType() + ")"
            + (note != null && !note.isBlank() ? " — " + note.trim() : "");
        tickets.addEvent(ticketId, actor.id(), actor.name(), eventKind, s.status(), s.status(), message);
        return requireTicket(ticketId);
    }

    private boolean legalQuotationTransition(String currentStatus, String targetStatus) {
        if (QuotationStatus.SENT.equals(targetStatus)) {
            return QuotationStatus.ISSUED.equals(currentStatus) || QuotationStatus.SENT.equals(currentStatus);
        }
        if (QuotationStatus.ACCEPTED.equals(targetStatus) || QuotationStatus.REJECTED.equals(targetStatus)) {
            return QuotationStatus.ISSUED.equals(currentStatus) || QuotationStatus.SENT.equals(currentStatus);
        }
        return false;
    }

    // Renders the quotation from its issue-time snapshot when one exists (V49); falls back
    // to live ticket data only for pre-V49 quotations that predate the snapshot.
    public byte[] getQuotationXlsx(long ticketId, long quotationId, UserPrincipal actor) {
        var ctx = loadQuotationContext(ticketId, quotationId, actor);
        return quotationRenderer.toXlsx(ctx.ticket(), ctx.quotation(), ctx.customer());
    }

    public byte[] getQuotationPdf(long ticketId, long quotationId, UserPrincipal actor) {
        var ctx = loadQuotationContext(ticketId, quotationId, actor);
        return quotationRenderer.toPdf(ctx.ticket(), ctx.quotation(), ctx.customer());
    }

    private record QuotationRenderContext(TicketDto ticket, QuotationDto quotation, CustomerDto customer) {}

    private QuotationRenderContext loadQuotationContext(long ticketId, long quotationId, UserPrincipal actor) {
        TicketDto ticket = requireViewAccess(ticketId, actor);
        QuotationDto quotation = ticket.quotations().stream()
            .filter(q -> q.id() == quotationId)
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Quotation not found"));

        List<TicketItemDto> snapshotItems = tickets.findQuotationItemsByQuotationId(quotationId, ticketId);
        if (!snapshotItems.isEmpty()) {
            TicketRepository.QuotationHeaderSnapshot header = tickets.findQuotationHeaderSnapshot(quotationId)
                .orElse(new TicketRepository.QuotationHeaderSnapshot(null, null, null, null, null));
            String frozenCustomerName = header.customerName() != null
                ? header.customerName() : ticket.summary().customerName();
            String frozenProjectName = header.projectName() != null
                ? header.projectName() : ticket.summary().projectName();
            TicketSummaryDto frozenSummary =
                withCustomerAndProject(ticket.summary(), frozenCustomerName, frozenProjectName);
            TicketDto frozenTicket = new TicketDto(frozenSummary, snapshotItems, ticket.events(),
                ticket.quotation(), ticket.quotations());
            CustomerDto frozenCustomer = new CustomerDto(
                ticket.summary().customerId() != null ? ticket.summary().customerId() : 0L,
                frozenCustomerName, header.customerTaxId(), header.customerAddress(),
                null, header.customerPhone());
            return new QuotationRenderContext(frozenTicket, quotation, frozenCustomer);
        }

        // Legacy fallback: no snapshot rows (quotation issued before V49) — render from
        // live data exactly as before this change.
        CustomerDto customer = ticket.summary().customerId() != null
            ? customers.findById(ticket.summary().customerId()).orElse(null)
            : null;
        return new QuotationRenderContext(ticket, quotation, customer);
    }

    private TicketSummaryDto withCustomerAndProject(TicketSummaryDto s, String customerName, String projectName) {
        return new TicketSummaryDto(
            s.id(), s.code(), s.type(), s.title(), s.status(), s.priority(),
            s.createdById(), s.createdByName(), s.assignedToId(), s.assignedToName(),
            customerName, s.customerId(), s.projectId(), projectName,
            s.contactId(), s.contactName(), s.note(),
            s.createdAt(), s.updatedAt(), s.closedAt(), s.itemCount(), s.hasEdits(),
            s.paymentStatus(), s.fulfillmentStatus(),
            s.salesStage(), s.lostReason(), s.lostAt(), s.stageUpdatedAt(),
            s.lifecycle(), s.tenderRequirement(), s.depositPolicy(), s.depositPolicyReason(),
            s.entryChannel(), s.billingDate(), s.dueDate(), s.creditTermDays(),
            s.lastFollowUpAt(), s.nextFollowUpAt(), s.paymentStage(), s.amountPayable(),
            s.amountPaid(), s.amountOutstanding(), s.overdue());
    }

    @Transactional
    public TicketDto close(long ticketId, UserPrincipal actor) {
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        requireActive(s);
        if (s.createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        String st = s.status();
        // Legacy path: status=DOCUMENT_ISSUED — only for pre-dual-track tickets
        // (paymentStatus never set) or fully-paid ones. A mid-track ticket that
        // reached document_issued must NOT close unpaid (2026-07-16 audit finding #3);
        // recover it via revision or cancel.
        // Dual-track path: both tracks complete.
        boolean legacyOk = TicketStatus.DOCUMENT_ISSUED.equals(st)
            && (s.paymentStatus() == null || "FULLY_PAID".equals(s.paymentStatus()));
        boolean dualTrackOk = TicketStatus.QUOTATION_ISSUED.equals(st)
            && "FULLY_PAID".equals(s.paymentStatus())
            && deliveryGateComplete(s);
        if (!legacyOk && !dualTrackOk) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Cannot close: require paymentStatus=FULLY_PAID and delivery complete");
        }
        if (s.amountOutstanding() != null && s.amountOutstanding().signum() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot close: ยังมียอดค้างชำระ");
        }
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.CLOSED, st, TicketStatus.CLOSED, null);
        tickets.updateLifecycle(ticketId, DealLifecycle.COMPLETED);
        return requireTicket(ticketId);
    }

    // ── Dual-track transitions (ข้อ 13) ─────────────────────────────────────

    @Transactional
    public TicketDto confirmCustomer(long ticketId, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto s = loadAndVerifyStatus(ticketId, TicketStatus.QUOTATION_ISSUED);
        requireActive(s);
        requireOwner(s, actor);
        // Never downgrade the payment track: once past CUSTOMER_CONFIRMED, re-confirming
        // would reset paymentStatus and deadlock the later transitions.
        if (s.paymentStatus() != null && !"CUSTOMER_CONFIRMED".equals(s.paymentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Payment track already past CUSTOMER_CONFIRMED");
        }
        tickets.updatePaymentStatus(ticketId, "CUSTOMER_CONFIRMED");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.CUSTOMER_CONFIRMED, s.status(), s.status(), null);
        // Deal pipeline (V50): a confirmed PO advances the deal — guarded no-op inside.
        autoAdvanceStage(s, DealStage.ORDER_RECEIVED, actor);
        return requireTicket(ticketId);
    }

    // NOTE: the former issueDepositNotice endpoint (advance payment track with no
    // document) was removed — issuing the real deposit-notice document
    // (DepositNoticeService.issue) is now the single action that sets
    // paymentStatus=DEPOSIT_NOTICE_ISSUED.

    @Transactional
    public TicketDto confirmDepositPaid(long ticketId, UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        requireActive(s);
        if (!"DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Expected paymentStatus=DEPOSIT_NOTICE_ISSUED");
        }
        var notice = tickets.latestIssuedDepositNotice(ticketId).orElse(null);
        BigDecimal amount = notice != null && notice.depositAmount() != null
            ? notice.depositAmount()
            : payableAmount(ticket).multiply(new BigDecimal("0.50"));
        if (amount.signum() <= 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ไม่พบยอดมัดจำสำหรับบันทึกรับชำระ");
        }
        RecordPaymentRequest request = new RecordPaymentRequest(
            "DEPOSIT", amount, null, "ยืนยันรับมัดจำ", notice != null ? notice.id() : null, null, false);
        return recordPaymentInternal(ticketId, request, actor);
    }

    @Transactional
    public TicketDto issueImportRequest(long ticketId, UserPrincipal actor) {
        requireRole(actor, FULFILMENT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireActive(s);
        // DEPOSIT_PAID is also acceptable: the customer often pays (and accounting
        // confirms) before import gets to the IR — requiring DEPOSIT_NOTICE_ISSUED
        // exactly deadlocked the fulfillment track in that ordering.
        boolean depositPolicyBypassesNotice = DepositPolicy.bypassesDepositNotice(s.depositPolicy())
            && (s.paymentStatus() == null || "CUSTOMER_CONFIRMED".equals(s.paymentStatus()));
        boolean depositReady = "DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus())
            || "DEPOSIT_PAID".equals(s.paymentStatus())
            || depositPolicyBypassesNotice;
        if (!TicketStatus.QUOTATION_ISSUED.equals(s.status()) || !depositReady) {
            throw new ApiException(HttpStatus.CONFLICT,
                "IR requires quotation_issued + paymentStatus=DEPOSIT_NOTICE_ISSUED/DEPOSIT_PAID or a waived deposit policy");
        }
        // Never restart an in-flight fulfillment track: re-issuing the IR would
        // downgrade IR_SENT/SHIPPING/GOODS_RECEIVED back to IR_ISSUED.
        if (s.fulfillmentStatus() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Import request already issued");
        }
        tickets.updateFulfillmentStatus(ticketId, FulfilmentStatus.IR_ISSUED);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.IR_ISSUED, s.status(), s.status(), null);
        // Deal pipeline (V50): the whole import journey (IR→warehouse) lives inside
        // PROCUREMENT — later fulfillment transitions render from fulfillment_status
        // and need no further stage writes. Guarded no-op inside.
        autoAdvanceStage(s, DealStage.PROCUREMENT, actor);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto markIrSent(long ticketId, UserPrincipal actor) {
        requireRole(actor, FULFILMENT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireActive(s);
        if (!FulfilmentStatus.IR_ISSUED.equals(s.fulfillmentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Expected fulfillmentStatus=IR_ISSUED");
        }
        tickets.updateFulfillmentStatus(ticketId, FulfilmentStatus.IR_SENT);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.IR_SENT, s.status(), s.status(), null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto markShipping(long ticketId, UserPrincipal actor) {
        requireRole(actor, FULFILMENT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireActive(s);
        if (!FulfilmentStatus.IR_SENT.equals(s.fulfillmentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Expected fulfillmentStatus=IR_SENT");
        }
        tickets.updateFulfillmentStatus(ticketId, FulfilmentStatus.SHIPPING);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.SHIPPING, s.status(), s.status(), null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto markGoodsReceived(long ticketId, UserPrincipal actor) {
        requireRole(actor, FULFILMENT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireActive(s);
        if (!FulfilmentStatus.SHIPPING.equals(s.fulfillmentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Expected fulfillmentStatus=SHIPPING");
        }
        tickets.updateFulfillmentStatus(ticketId, FulfilmentStatus.GOODS_RECEIVED);
        // Also advance payment track to AWAITING_FINAL_PAYMENT if deposit was paid
        if ("DEPOSIT_PAID".equals(s.paymentStatus())) {
            tickets.updatePaymentStatus(ticketId, "AWAITING_FINAL_PAYMENT");
            tickets.addEvent(ticketId, actor.id(), actor.name(),
                TicketEventKind.AWAITING_FINAL_PAYMENT, s.status(), s.status(), null);
        }
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.GOODS_RECEIVED, s.status(), s.status(), null);
        // Goods are at the warehouse (S17) — advance to DELIVERY_SCHEDULING (S18)
        // so the "schedule delivery / collect balance" step is reached before
        // DELIVERED, instead of the pipeline jumping PROCUREMENT → DELIVERED.
        autoAdvanceStage(s, DealStage.DELIVERY_SCHEDULING, actor);
        return requireTicket(ticketId);
    }

    public List<DeliveryRecordDto> listDeliveries(long ticketId, UserPrincipal actor) {
        requireViewAccess(ticketId, actor);
        return tickets.findDeliveriesByTicket(ticketId);
    }

    @Transactional
    public TicketDto reserveStock(long ticketId, StockReservationRequest request, UserPrincipal actor) {
        requireRole(actor, FULFILMENT_ROLES);
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        requireActive(s);
        List<StockReservationRequest.Line> lines = request == null ? List.of() : request.lines();
        if (lines == null || lines.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุรายการสินค้า");
        }
        Map<Long, TicketItemDto> itemsById = itemMap(ticket.items());
        Map<Long, BigDecimal> mergedStock = new LinkedHashMap<>();
        for (TicketItemDto item : ticket.items()) {
            mergedStock.put(item.id(), nullToZero(item.qtyFromStock()));
        }
        BigDecimal totalDeclared = BigDecimal.ZERO;
        for (StockReservationRequest.Line line : lines) {
            TicketItemDto item = requireLineItem(itemsById, line.itemId());
            BigDecimal qty = nullToZero(line.qtyFromStock());
            if (qty.signum() < 0 || qty.compareTo(nullToZero(item.qty())) > 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "จำนวนสินค้าจากสต็อกต้องไม่เกินจำนวนที่สั่ง");
            }
            mergedStock.put(item.id(), qty);
            totalDeclared = totalDeclared.add(qty);
        }
        tickets.reserveStock(ticketId, lines);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.STOCK_RESERVED, s.status(), s.status(),
            "qty_from_stock=" + totalDeclared.stripTrailingZeros().toPlainString());
        boolean allCovered = !ticket.items().isEmpty()
            && ticket.items().stream()
                .allMatch(item -> nullToZero(mergedStock.get(item.id())).compareTo(nullToZero(item.qty())) >= 0);
        if (allCovered && (s.fulfillmentStatus() == null || FulfilmentStatus.FROM_STOCK.equals(s.fulfillmentStatus()))) {
            tickets.updateFulfillmentStatus(ticketId, FulfilmentStatus.FROM_STOCK);
            // Fully covered from stock — no import journey, so the goods are ready
            // now. Advance straight to DELIVERY_SCHEDULING (S18) rather than
            // PROCUREMENT (an import step this deal never performs).
            autoAdvanceStage(s, DealStage.DELIVERY_SCHEDULING, actor);
        }
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto recordPartialDelivery(long ticketId, RecordDeliveryRequest request, UserPrincipal actor) {
        requireRole(actor, FULFILMENT_ROLES);
        TicketDto ticket = requireTicket(ticketId);
        return recordDeliveryInternal(ticket, request, actor, false);
    }

    @Transactional
    public TicketDto completeDelivery(long ticketId, CompleteDeliveryRequest request, UserPrincipal actor) {
        requireRole(actor, FULFILMENT_ROLES);
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        requireActive(s);
        List<RecordDeliveryRequest.Line> remaining = ticket.items().stream()
            .map(item -> new RecordDeliveryRequest.Line(item.id(),
                nullToZero(item.qty()).subtract(nullToZero(item.qtyDelivered()))))
            .filter(line -> line.qty().signum() > 0)
            .toList();
        if (remaining.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ไม่มีจำนวนค้างส่ง");
        }
        boolean allRemainingCoveredByStock = ticket.items().stream().allMatch(item -> {
            BigDecimal remainingQty = nullToZero(item.qty()).subtract(nullToZero(item.qtyDelivered()));
            if (remainingQty.signum() <= 0) return true;
            return nullToZero(item.qtyDelivered()).add(remainingQty).compareTo(nullToZero(item.qtyFromStock())) <= 0;
        });
        String source = allRemainingCoveredByStock ? "STOCK" : "WAREHOUSE";
        RecordDeliveryRequest delivery = new RecordDeliveryRequest(
            source,
            request == null ? null : request.note(),
            remaining);
        return recordDeliveryInternal(ticket, delivery, actor, true);
    }

    private TicketDto recordDeliveryInternal(TicketDto ticket, RecordDeliveryRequest request,
                                             UserPrincipal actor, boolean completing) {
        TicketSummaryDto s = ticket.summary();
        requireActive(s);
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุรายการส่งสินค้า");
        }
        String source = request.source() == null ? "" : request.source().trim().toUpperCase();
        if (!DELIVERY_SOURCES.contains(source)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "source ต้องเป็น WAREHOUSE หรือ STOCK");
        }
        Map<Long, TicketItemDto> itemsById = itemMap(ticket.items());
        Map<Long, BigDecimal> combined = new LinkedHashMap<>();
        for (RecordDeliveryRequest.Line line : request.lines()) {
            TicketItemDto item = requireLineItem(itemsById, line.itemId());
            BigDecimal qty = nullToZero(line.qty());
            if (qty.signum() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "จำนวนส่งมอบต้องมากกว่า 0");
            }
            combined.merge(item.id(), qty, BigDecimal::add);
        }
        List<RecordDeliveryRequest.Line> normalized = combined.entrySet().stream()
            .map(entry -> new RecordDeliveryRequest.Line(entry.getKey(), entry.getValue()))
            .toList();
        for (RecordDeliveryRequest.Line line : normalized) {
            TicketItemDto item = itemsById.get(line.itemId());
            BigDecimal newDelivered = nullToZero(item.qtyDelivered()).add(line.qty());
            if (newDelivered.compareTo(nullToZero(item.qty())) > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "จำนวนส่งมอบเกินจำนวนที่สั่ง");
            }
            if ("STOCK".equals(source)
                && newDelivered.compareTo(nullToZero(item.qtyFromStock())) > 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "ส่งจากสต็อกได้ไม่เกินจำนวนที่ประกาศว่าพร้อมจากสต็อก");
            }
        }
        if ("WAREHOUSE".equals(source) && !warehouseDeliveryAvailable(s, ticket.summary().id())) {
            throw new ApiException(HttpStatus.CONFLICT, "ต้องรับสินค้าเข้าโกดังก่อนส่งจาก WAREHOUSE");
        }
        tickets.insertDeliveryRecord(s.id(), source, actor.id(), request.note(), normalized);
        TicketDto updated = requireTicket(s.id());
        TicketSummaryDto updatedSummary = updated.summary();
        boolean fullyDelivered = updated.items().stream()
            .allMatch(item -> nullToZero(item.qtyDelivered()).compareTo(nullToZero(item.qty())) >= 0);
        String message = deliveryMessage(updated.items(), normalized);
        tickets.addEvent(s.id(), actor.id(), actor.name(),
            TicketEventKind.DELIVERY_RECORDED, updatedSummary.status(), updatedSummary.status(), message);
        if (fullyDelivered) {
            tickets.updateFulfillmentStatus(s.id(), FulfilmentStatus.FULLY_DELIVERED);
            tickets.addEvent(s.id(), actor.id(), actor.name(),
                TicketEventKind.DELIVERY_COMPLETED, updatedSummary.status(), updatedSummary.status(),
                completing ? "ส่งมอบครบจากปุ่ม completeDelivery" : message);
            autoAdvanceStage(updatedSummary, DealStage.DELIVERED, actor);
            // Second CLOSED_PAID gate: a deal paid in full before delivery closes
            // exactly when delivery completes (reload so fulfilment reflects the
            // just-written FULLY_DELIVERED).
            TicketSummaryDto afterDelivery = requireTicket(s.id()).summary();
            maybeAdvanceClosedPaid(afterDelivery, "FULLY_PAID".equals(afterDelivery.paymentStatus()), actor);
        } else {
            tickets.updateFulfillmentStatus(s.id(), FulfilmentStatus.PARTIALLY_DELIVERED);
        }
        return requireTicket(s.id());
    }

    @Transactional
    public TicketDto confirmFinalPayment(long ticketId, UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        requireActive(s);
        if (!canConfirmFinalPaymentNow(s)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Expected paymentStatus=DEPOSIT_PAID/AWAITING_FINAL_PAYMENT or a waived deposit policy");
        }
        BigDecimal outstanding = payableAmount(ticket).subtract(nullToZero(tickets.sumPaid(ticketId)));
        if (outstanding.signum() <= 0) {
            if (!"FULLY_PAID".equals(s.paymentStatus())) {
                tickets.updatePaymentStatus(ticketId, "FULLY_PAID");
                tickets.addEvent(ticketId, actor.id(), actor.name(),
                    TicketEventKind.FULLY_PAID, s.status(), s.status(), null);
                maybeAdvanceClosedPaid(s, true, actor);
            }
            return requireTicket(ticketId);
        }
        RecordPaymentRequest request = new RecordPaymentRequest(
            "BALANCE", outstanding, null, "ยืนยันชำระส่วนที่เหลือ", null, null, false);
        return recordPaymentInternal(ticketId, request, actor);
    }

    @Transactional
    public TicketDto recordPayment(long ticketId, RecordPaymentRequest request, UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        return recordPaymentInternal(ticketId, request, actor);
    }

    @Transactional
    public TicketDto setBilling(long ticketId, BillingRequest request, UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireActive(s);
        tickets.updateBilling(ticketId, request.billingDate(), request.dueDate(), request.creditTermDays(),
            request.lastFollowUpAt(), request.nextFollowUpAt());
        tickets.addEvent(ticketId, actor.id(), actor.name(), TicketEventKind.BILLING_UPDATED,
            s.status(), s.status(), "billing_date=" + request.billingDate() + ", due_date=" + request.dueDate());
        return requireTicket(ticketId);
    }

    private TicketDto recordPaymentInternal(long ticketId, RecordPaymentRequest request, UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุข้อมูลรับชำระเงิน");
        }
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        requireActive(s);
        String kind = request.kind() == null ? null : request.kind().trim().toUpperCase();
        if (!PAYMENT_RECEIPT_KINDS.contains(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown payment kind '" + request.kind() + "'");
        }
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ยอดรับชำระต้องมากกว่า 0");
        }
        BigDecimal payable = payableAmount(ticket);
        BigDecimal paid = nullToZero(tickets.sumPaid(ticketId));
        BigDecimal newPaid = paid.add(signedPaymentAmount(kind, amount));
        if (newPaid.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ยอดรับชำระสุทธิห้ามติดลบ");
        }
        boolean overpaid = newPaid.compareTo(payable) > 0;
        boolean allowOverpayment = Boolean.TRUE.equals(request.allowOverpayment());
        String note = blankToNull(request.note());
        if (overpaid && !allowOverpayment) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ยอดรับชำระเกินยอดที่ต้องชำระ กรุณายืนยัน overpayment พร้อมเหตุผล");
        }
        if (overpaid && (note == null || note.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "การรับชำระเกินยอดต้องระบุเหตุผล");
        }
        String receiptRef = blankToNull(request.receiptRef());
        try {
            tickets.insertPaymentReceipt(ticketId, kind, amount, actor.id(), request.receivedAt(),
                note, request.depositNoticeId(), receiptRef);
        } catch (DataIntegrityViolationException e) {
            if (receiptRef != null) {
                throw new ApiException(HttpStatus.CONFLICT, "เลขอ้างอิงรับชำระซ้ำ");
            }
            throw e;
        }
        tickets.addEvent(ticketId, actor.id(), actor.name(), TicketEventKind.PAYMENT_RECORDED,
            s.status(), s.status(),
            "kind=" + kind + ", amount=" + amount + ", paid=" + newPaid + ", payable=" + payable
                + (note != null ? " — " + note : ""));
        reconcilePaymentStatus(ticketId, actor);
        return requireTicket(ticketId);
    }

    private void reconcilePaymentStatus(long ticketId, UserPrincipal actor) {
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        BigDecimal payable = payableAmount(ticket);
        BigDecimal paid = nullToZero(tickets.sumPaid(ticketId));
        if (payable.signum() > 0 && paid.compareTo(payable) >= 0) {
            if (!"FULLY_PAID".equals(s.paymentStatus())) {
                tickets.updatePaymentStatus(ticketId, "FULLY_PAID");
                tickets.addEvent(ticketId, actor.id(), actor.name(),
                    TicketEventKind.FULLY_PAID, s.status(), s.status(), null);
                maybeAdvanceClosedPaid(s, true, actor);
            }
            return;
        }
        if (paid.signum() <= 0 || "FULLY_PAID".equals(s.paymentStatus())) {
            return;
        }
        boolean eligibleForDepositAdvance =
            s.paymentStatus() == null
                || "CUSTOMER_CONFIRMED".equals(s.paymentStatus())
                || "DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus())
                || DepositPolicy.bypassesDepositNotice(s.depositPolicy());
        if (eligibleForDepositAdvance && !"DEPOSIT_PAID".equals(s.paymentStatus())) {
            tickets.updatePaymentStatus(ticketId, "DEPOSIT_PAID");
            tickets.addEvent(ticketId, actor.id(), actor.name(),
                TicketEventKind.DEPOSIT_PAID, s.status(), s.status(), null);
            autoAdvanceStage(s, DealStage.DEPOSIT_RECEIVED, actor);
            if ("GOODS_RECEIVED".equals(s.fulfillmentStatus())) {
                tickets.updatePaymentStatus(ticketId, "AWAITING_FINAL_PAYMENT");
                tickets.addEvent(ticketId, actor.id(), actor.name(),
                    TicketEventKind.AWAITING_FINAL_PAYMENT, s.status(), s.status(), null);
            }
        }
    }

    private BigDecimal signedPaymentAmount(String kind, BigDecimal amount) {
        return "ADJUSTMENT".equals(kind) ? amount.negate() : amount;
    }

    /**
     * Payment payable precedence for Phase 3: latest ACCEPTED quotation (BUYER, then
     * OWNER, then any), latest ISSUED/SENT quotation with the same recipient preference,
     * latest issued deposit notice total, then approved line totals.
     */
    private BigDecimal payableAmount(TicketDto ticket) {
        return nullToZero(tickets.payableAmount(ticket.summary().id()));
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Map<Long, TicketItemDto> itemMap(List<TicketItemDto> items) {
        Map<Long, TicketItemDto> map = new LinkedHashMap<>();
        for (TicketItemDto item : items) {
            map.put(item.id(), item);
        }
        return map;
    }

    private TicketItemDto requireLineItem(Map<Long, TicketItemDto> itemsById, Long itemId) {
        if (itemId == null || !itemsById.containsKey(itemId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Item not found in this ticket");
        }
        return itemsById.get(itemId);
    }

    private boolean warehouseDeliveryAvailable(TicketSummaryDto s, long ticketId) {
        // Goods reaching the warehouse is a permanent fact — the current status is enough
        // when it's still GOODS_RECEIVED, but once a delivery flips it to
        // PARTIALLY_DELIVERED we fall back to the GOODS_RECEIVED event so a stock-first
        // partial delivery can't wrongly block the warehouse remainder (Case 8: 40 from
        // stock delivered first, 60 imported still to go).
        return FulfilmentStatus.GOODS_RECEIVED.equals(s.fulfillmentStatus())
            || tickets.hasReceivedGoods(ticketId);
    }

    private static boolean hasRemainingDelivery(TicketDto ticket) {
        return ticket.items().stream()
            .anyMatch(item -> nullToZero(item.qty()).compareTo(nullToZero(item.qtyDelivered())) > 0);
    }

    private static String deliveryMessage(List<TicketItemDto> updatedItems, List<RecordDeliveryRequest.Line> lines) {
        Map<Long, TicketItemDto> itemsById = itemMap(updatedItems);
        return lines.stream()
            .map(line -> {
                TicketItemDto item = itemsById.get(line.itemId());
                if (item == null) {
                    return line.itemId() + ": +" + line.qty().stripTrailingZeros().toPlainString();
                }
                return line.itemId() + ": "
                    + nullToZero(item.qtyDelivered()).stripTrailingZeros().toPlainString()
                    + "/" + nullToZero(item.qty()).stripTrailingZeros().toPlainString();
            })
            .toList()
            .toString();
    }

    private boolean canConfirmFinalPaymentNow(TicketSummaryDto s) {
        boolean depositBypassed = DepositPolicy.bypassesDepositNotice(s.depositPolicy())
            && (s.paymentStatus() == null || "CUSTOMER_CONFIRMED".equals(s.paymentStatus()));
        return "AWAITING_FINAL_PAYMENT".equals(s.paymentStatus())
            || "DEPOSIT_PAID".equals(s.paymentStatus())
            || depositBypassed;
    }

    // ── Deal pipeline (V50): 14-stage journey on the ticket itself ──────────
    // NOTE on sales_manager: handoff 58 made it read+comment-only on the ticket's
    // OPERATIONAL actions, and that stands. The pipeline stage/lost/reopen fields
    // are the deliberate, user-approved exception — following up the team's deals
    // is exactly this role's job. Never extend it beyond these three methods.

    /** Stages whose manual fallback belongs to the deal owner / sales_manager / ceo. */
    private static final Set<String> SALES_TARGET_STAGES = Set.of(
        DealStage.LEAD_APPROACH, DealStage.PRESENTATION, DealStage.SPEC_APPROVED,
        DealStage.QUOTE_DESIGN_SIDE, DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER,
        DealStage.QUOTE_BUYER, DealStage.NEGOTIATION, DealStage.ORDER_RECEIVED,
        DealStage.DELIVERY_SCHEDULING, DealStage.DELIVERED);
    /** Money stages — manual fallback for account/ceo (normally auto from payment track). */
    private static final Set<String> ACCOUNT_TARGET_STAGES = Set.of(
        DealStage.DEPOSIT_RECEIVED, DealStage.CLOSED_PAID);
    /** Import stage — manual fallback for import/ceo (normally auto from the IR). */
    private static final Set<String> IMPORT_TARGET_STAGES = Set.of(
        DealStage.PROCUREMENT);

    @Transactional
    public TicketDto updateStage(long ticketId, String targetStage, String note, UserPrincipal actor) {
        if (!DealStage.isValid(targetStage)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown stage '" + targetStage + "'");
        }
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireStageWriteAccess(s, targetStage, actor);
        requireActive(s);
        if (s.lostReason() != null) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ดีลถูกทำเครื่องหมายเสียงานแล้ว — เปิดดีลใหม่ก่อนแก้ไขสถานะ");
        }
        if (targetStage.equals(s.salesStage())) {
            throw new ApiException(HttpStatus.CONFLICT, "Deal is already in stage " + targetStage);
        }
        boolean backward = DealStage.indexOf(targetStage) < DealStage.indexOf(s.salesStage());
        boolean skipForward = DealStage.indexOf(targetStage) - DealStage.indexOf(s.salesStage()) > 1;
        if (backward && (note == null || note.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "การย้อนสถานะกลับต้องระบุเหตุผล");
        }
        if (skipForward && (note == null || note.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "การข้ามขั้นตอนต้องระบุเหตุผล");
        }
        tickets.updateSalesStage(ticketId, targetStage);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.STAGE_CHANGED, s.salesStage(), targetStage, blankToNull(note));
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto markLost(long ticketId, String reason, String note, UserPrincipal actor) {
        if (!DealLostReason.isValid(reason)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown lost reason '" + reason + "'");
        }
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireDealOwnership(s, actor);
        requireActive(s);
        if (s.lostReason() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Deal is already marked lost");
        }
        tickets.markDealLost(ticketId, reason);
        // Stage untouched by design: reopening resumes exactly where the deal was.
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.MARKED_LOST, s.salesStage(), s.salesStage(),
            "เสียงาน (" + reason + ")" + (note != null && !note.isBlank() ? " — " + note.trim() : ""));
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto reopenDeal(long ticketId, String note, UserPrincipal actor) {
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireDealOwnership(s, actor);
        if (!DealLifecycle.CLOSED_LOST.equals(s.lifecycle()) || s.lostReason() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Deal is not marked lost");
        }
        tickets.clearDealLost(ticketId);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.REOPENED, s.salesStage(), s.salesStage(), blankToNull(note));
        return requireTicket(ticketId);
    }

    /**
     * Same-transaction stage advance from the deal's own operational transitions.
     * No-throw by construction: no-op when the deal is lost or the target is not
     * strictly forward (monotonic — re-running a transition can never regress).
     */
    private void autoAdvanceStage(TicketSummaryDto s, String targetStage, UserPrincipal actor) {
        if (!DealLifecycle.ACTIVE.equals(s.lifecycle()) || s.lostReason() != null) {
            return;
        }
        if (DealStage.indexOf(targetStage) <= DealStage.indexOf(s.salesStage())) {
            return;
        }
        tickets.updateSalesStage(s.id(), targetStage);
        tickets.addEvent(s.id(), actor.id(), actor.name(),
            TicketEventKind.STAGE_CHANGED, s.salesStage(), targetStage, "อัตโนมัติจากขั้นตอนของดีล");
    }

    @Transactional
    public TicketDto placeOnHold(long ticketId, String note, UserPrincipal actor) {
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireDealOwnership(s, actor);
        requireActive(s);
        tickets.updateLifecycle(ticketId, DealLifecycle.ON_HOLD);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.ON_HOLD, s.salesStage(), s.salesStage(), blankToNull(note));
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto markDormant(long ticketId, String note, UserPrincipal actor) {
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireDealOwnership(s, actor);
        if (!DealLifecycle.ACTIVE.equals(s.lifecycle()) && !DealLifecycle.ON_HOLD.equals(s.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "พัก dormant ได้เฉพาะดีลที่ active หรือ on hold");
        }
        tickets.updateLifecycle(ticketId, DealLifecycle.DORMANT);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.DORMANT, s.salesStage(), s.salesStage(), blankToNull(note));
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto resume(long ticketId, String note, UserPrincipal actor) {
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireDealOwnership(s, actor);
        if (!DealLifecycle.ON_HOLD.equals(s.lifecycle()) && !DealLifecycle.DORMANT.equals(s.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "ดำเนินการต่อได้เฉพาะดีลที่พักไว้");
        }
        tickets.updateLifecycle(ticketId, DealLifecycle.ACTIVE);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.RESUMED, s.salesStage(), s.salesStage(), blankToNull(note));
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto setTenderRequirement(long ticketId, String value, UserPrincipal actor) {
        if (!TenderRequirement.isValid(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown tender requirement '" + value + "'");
        }
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireDealOwnership(s, actor);
        requireActive(s);
        tickets.updateTenderRequirement(ticketId, value);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.POLICY_CHANGED, s.salesStage(), s.salesStage(),
            "tender_requirement → " + value);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto setEntryChannel(long ticketId, String value, String note, UserPrincipal actor) {
        if (!EntryChannel.isValid(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown entry channel '" + value + "'");
        }
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireDealOwnership(s, actor);
        requireActive(s);
        boolean changingExistingNonDefault = s.entryChannel() != null
            && !EntryChannel.DESIGNER_LED.equals(s.entryChannel())
            && !s.entryChannel().equals(value);
        if (changingExistingNonDefault && (note == null || note.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "การเปลี่ยน entry channel ต้องระบุเหตุผล");
        }
        tickets.updateEntryChannel(ticketId, value);
        String message = "entry_channel → " + value
            + (note != null && !note.isBlank() ? " — " + note.trim() : "");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.POLICY_CHANGED, s.salesStage(), s.salesStage(), message);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto waiveDeposit(long ticketId, String policy, String reason, UserPrincipal actor) {
        if (!DepositPolicy.NON_REQUIRED.contains(policy)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown deposit waiver policy '" + policy + "'");
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุเหตุผลนโยบายมัดจำ");
        }
        requireRole(actor, ACCOUNT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireActive(s);
        tickets.updateDepositPolicy(ticketId, policy, reason.trim(), actor.id());
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.POLICY_CHANGED, s.salesStage(), s.salesStage(),
            "deposit_policy → " + policy + " — " + reason.trim());
        return requireTicket(ticketId);
    }

    private void requireStageWriteAccess(TicketSummaryDto s, String targetStage, UserPrincipal actor) {
        String role = actor.role();
        if ("ceo".equals(role)) {
            return;
        }
        if (SALES_TARGET_STAGES.contains(targetStage)) {
            if ("sales_manager".equals(role)) {
                return;
            }
            if (SALES_ROLES.contains(role) && s.createdById() == actor.id()) {
                return;
            }
        } else if (ACCOUNT_TARGET_STAGES.contains(targetStage)) {
            if ("account".equals(role)) {
                return;
            }
        } else if (IMPORT_TARGET_STAGES.contains(targetStage)) {
            if (IMPORT_ROLES.contains(role)) {
                return;
            }
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
    }

    /** Lost/reopen belong to the sales side: deal owner, sales_manager, or ceo. */
    private void requireDealOwnership(TicketSummaryDto s, UserPrincipal actor) {
        String role = actor.role();
        boolean isOwner = SALES_ROLES.contains(role) && s.createdById() == actor.id();
        if (!isOwner && !"sales_manager".equals(role) && !"ceo".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private void requireQuotationWriteAccess(TicketSummaryDto s, UserPrincipal actor) {
        if (!canManageQuotation(s, actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @Transactional
    public TicketDto cancel(long ticketId, UserPrincipal actor) {
        TicketDto ticket = requireTicket(ticketId);
        String currentStatus = ticket.summary().status();
        if (TicketStatus.CLOSED.equals(currentStatus) || TicketStatus.CANCELLED.equals(currentStatus)) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot cancel a closed or already cancelled ticket");
        }
        if (ticket.summary().createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.CANCELLED, currentStatus, TicketStatus.CANCELLED, null);
        tickets.updateLifecycle(ticketId, DealLifecycle.CANCELLED);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto editItems(long ticketId, EditItemsRequest request, UserPrincipal actor) {
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        requireActive(s);
        String st = s.status();
        boolean isOwner = actor.id() == s.createdById();

        // DRAFT included since V50: a lightweight lead-stage deal gets its product
        // items here before submit().
        boolean salesCanEdit = SALES_ROLES.contains(actor.role()) && isOwner
            && Set.of(TicketStatus.DRAFT, TicketStatus.SUBMITTED, TicketStatus.IN_REVIEW,
                      TicketStatus.PRICE_PROPOSED).contains(st);

        if (!salesCanEdit) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์แก้ไขรายการสินค้าในสถานะนี้");
        }
        // Sales editing items (brand/model/qty/etc.) must NOT be able to clobber import's
        // proposed price or CEO's approved/manual price — only proposePrice (import) is
        // allowed to replace pricing wholesale. Merge request items onto the ticket's
        // existing items by position (request order = display order); pricing fields
        // always come from the existing item at that position, never the request.
        List<TicketItemDto> merged = mergeEditedItemsPreservingPricing(ticketId, ticket.items(), request.items());
        tickets.replaceItemsPreservingPricing(ticketId, merged);
        tickets.setHasEdits(ticketId, true);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.EDITED, st, st, request.note());
        return requireTicket(ticketId);
    }

    private List<TicketItemDto> mergeEditedItemsPreservingPricing(
            long ticketId, List<TicketItemDto> existingItems, List<TicketItemRequest> requestItems) {
        List<TicketItemDto> merged = new ArrayList<>(requestItems.size());
        for (int i = 0; i < requestItems.size(); i++) {
            TicketItemRequest r = requestItems.get(i);
            TicketItemDto prior = i < existingItems.size() ? existingItems.get(i) : null;
            // Request wins; a request that omits unitBasis inherits the prior item's
            // basis (an API edit must not silently flip an SQM item back to PIECE).
            String unitBasis = (r.unitBasis() != null && !r.unitBasis().isBlank())
                ? r.unitBasis()
                : (prior != null && prior.unitBasis() != null ? prior.unitBasis() : "PIECE");
            // "currency" (display currency, distinct from rawCurrency) is pricing-adjacent
            // metadata, not a descriptive field the request is meant to drive — carry it
            // over like the other pricing fields, falling back to the request/THB only
            // for brand-new rows that have no prior item to inherit from.
            String currency = prior != null
                ? prior.currency()
                : ((r.currency() != null && !r.currency().isBlank()) ? r.currency() : "THB");
            merged.add(new TicketItemDto(
                prior != null ? prior.id() : 0L,
                ticketId,
                r.brand(), r.model(), r.color(), r.texture(), r.size(), r.factory(),
                r.qty(), r.qtySqm(),
                r.rawPrice(), r.rawCurrency(), r.rawUnit(),
                prior != null ? prior.proposedPrice() : null,
                prior != null ? prior.approvedPrice() : null,
                currency,
                i,
                prior != null ? prior.calcedCost() : null,
                prior != null ? prior.calcedPrice() : null,
                prior != null ? prior.calcConfigVersion() : null,
                unitBasis,
                prior != null ? prior.manualPrice() : null,
                prior != null ? prior.manualOverrideReason() : null
            ));
        }
        return merged;
    }

    @Transactional
    public TicketDto comment(long ticketId, CommentRequest request, UserPrincipal actor) {
        // Same access rule as GET /tickets/{id} — commenting returns the full ticket,
        // so it must not be a side door around the read scoping.
        requireViewAccess(ticketId, actor);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.COMMENTED, null, null, request.message());
        return requireTicket(ticketId);
    }

    @Transactional
    public CalculatePricesResult calculatePrices(long ticketId, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireActive(s);
        if (!TicketStatus.PRICE_PROPOSED.equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "คำนวณราคาได้เฉพาะ ticket ที่มีสถานะ price_proposed");
        }
        TicketDto ticket = priceCalcService.calculateForTicket(ticketId);
        List<PriceBreakdownItemDto> breakdown = priceCalcService.calculateBreakdown(ticketId);
        return new CalculatePricesResult(ticket, breakdown);
    }

    public record CalculatePricesResult(TicketDto ticket, List<PriceBreakdownItemDto> breakdown) {}

    @Transactional
    public TicketDto overrideItemPrice(long ticketId, long itemId, OverridePriceRequest request, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireActive(s);
        if (!TicketStatus.PRICE_PROPOSED.equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "override ราคาได้เฉพาะ ticket ที่มีสถานะ price_proposed");
        }
        boolean itemExists = requireTicket(ticketId).items().stream()
            .anyMatch(it -> it.id() == itemId);
        if (!itemExists) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Item not found in this ticket");
        }
        tickets.updateItemManualPrice(itemId, request.manualPrice(), request.reason());
        // Audit trail: an override silently changing an item's price with no ticket_event
        // was a gap found in the 2026-07-16 pricing-integrity audit (finding #3).
        String note = "Item #" + itemId + ": ราคา manual override = " + request.manualPrice()
            + (request.reason() != null && !request.reason().isBlank() ? " — เหตุผล: " + request.reason() : "");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.PRICE_OVERRIDDEN, s.status(), s.status(), note);
        return requireTicket(ticketId);
    }

    // --- helpers ---

    /**
     * Gate for POST /tickets/{id}/factory-emails/send. Factory outreach is part of the
     * import price-proposal flow: import role only, and the ticket must exist — the
     * endpoint previously required only a session, making it an open mail relay.
     */
    public void assertFactoryEmailAllowed(long ticketId, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        requireTicket(ticketId);
    }

    public TicketActionsResponse actions(long ticketId, UserPrincipal actor) {
        TicketDto ticket = requireViewAccess(ticketId, actor);
        TicketSummaryDto s = ticket.summary();
        List<TicketActionDto> actions = new ArrayList<>();
        boolean active = DealLifecycle.ACTIVE.equals(s.lifecycle());
        if (active) {
            addOperationalActions(actions, ticket, actor);
            addStageActions(actions, s, actor);
            addPolicyActions(actions, s, actor);
            addQuotationActions(actions, ticket, actor);
            if (canDealOwnership(s, actor)) {
                actions.add(new TicketActionDto("MARK_LOST", "lifecycle", "เสียงาน", List.of("reason")));
                actions.add(new TicketActionDto("PLACE_ON_HOLD", "lifecycle", "พักดีลไว้"));
                actions.add(new TicketActionDto("MARK_DORMANT", "lifecycle", "พัก dormant"));
            }
        } else if ((DealLifecycle.ON_HOLD.equals(s.lifecycle()) || DealLifecycle.DORMANT.equals(s.lifecycle()))
                && canDealOwnership(s, actor)) {
            actions.add(new TicketActionDto("RESUME", "lifecycle", "ดำเนินการต่อ"));
            if (DealLifecycle.ON_HOLD.equals(s.lifecycle())) {
                actions.add(new TicketActionDto("MARK_DORMANT", "lifecycle", "พัก dormant"));
            }
        } else if (DealLifecycle.CLOSED_LOST.equals(s.lifecycle()) && canDealOwnership(s, actor)) {
            actions.add(new TicketActionDto("REOPEN", "lifecycle", "เปิดดีลใหม่"));
        }
        TicketActionState state = new TicketActionState(s.lifecycle(), s.salesStage(), s.paymentStatus(),
            s.fulfillmentStatus(), s.status());
        return new TicketActionsResponse(state, actions);
    }

    private void addOperationalActions(List<TicketActionDto> actions, TicketDto ticket, UserPrincipal actor) {
        TicketSummaryDto s = ticket.summary();
        if (canSubmit(s, actor)) actions.add(new TicketActionDto("SUBMIT", "operational", "ส่งขอราคา"));
        if (IMPORT_ROLES.contains(actor.role()) && TicketStatus.SUBMITTED.equals(s.status())) {
            actions.add(new TicketActionDto("PICKUP", "operational", "รับเรื่อง"));
        }
        if (IMPORT_ROLES.contains(actor.role()) && PROPOSE_ALLOWED_STATUSES.contains(s.status())) {
            actions.add(new TicketActionDto("PROPOSE_PRICE", "operational", "เสนอราคา", List.of("items")));
        }
        if (CEO_ROLES.contains(actor.role()) && TicketStatus.PRICE_PROPOSED.equals(s.status())) {
            actions.add(new TicketActionDto("APPROVE", "operational", "อนุมัติราคา"));
            actions.add(new TicketActionDto("REJECT", "operational", "ตีกลับราคา", List.of("reason")));
            actions.add(new TicketActionDto("CALCULATE_PRICES", "operational", "คำนวณราคา"));
            actions.add(new TicketActionDto("OVERRIDE_ITEM_PRICE", "operational", "แก้ไขราคาด้วยตนเอง", List.of("itemId", "manualPrice")));
        }
        if (canGenerateQuotation(s, actor)) {
            actions.add(new TicketActionDto("GENERATE_QUOTATION", "doc", "ออกใบเสนอราคา",
                List.of("recipientType")));
        }
        if (canConfirmCustomer(s, actor)) actions.add(new TicketActionDto("CONFIRM_CUSTOMER", "payment", "ลูกค้ายืนยัน"));
        if (canCreateDepositNotice(s, actor)) actions.add(new TicketActionDto("ISSUE_DEPOSIT_NOTICE", "doc", "ออกใบแจ้งมัดจำ"));
        if (ACCOUNT_ROLES.contains(actor.role()) && "DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus())) {
            actions.add(new TicketActionDto("DEPOSIT_PAID", "payment", "รับมัดจำ"));
        }
        if (canRecordPayment(s, actor)) {
            actions.add(new TicketActionDto("RECORD_PAYMENT", "payment", "บันทึกรับชำระเงิน",
                List.of("kind", "amount")));
        }
        if (ACCOUNT_ROLES.contains(actor.role())) {
            actions.add(new TicketActionDto("SET_BILLING", "payment", "ตั้งค่าการวางบิล",
                List.of("dueDate")));
        }
        if (FULFILMENT_ROLES.contains(actor.role()) && canIssueImportRequest(s)) {
            actions.add(new TicketActionDto("ISSUE_IMPORT_REQUEST", "fulfillment", "ออก IR"));
        }
        if (FULFILMENT_ROLES.contains(actor.role()) && FulfilmentStatus.IR_ISSUED.equals(s.fulfillmentStatus())) {
            actions.add(new TicketActionDto("IR_SENT", "fulfillment", "ส่ง IR"));
        }
        if (FULFILMENT_ROLES.contains(actor.role()) && FulfilmentStatus.IR_SENT.equals(s.fulfillmentStatus())) {
            actions.add(new TicketActionDto("SHIPPING", "fulfillment", "สินค้าเดินทาง"));
        }
        if (FULFILMENT_ROLES.contains(actor.role()) && FulfilmentStatus.SHIPPING.equals(s.fulfillmentStatus())) {
            actions.add(new TicketActionDto("GOODS_RECEIVED", "fulfillment", "รับสินค้า"));
        }
        if (canReserveStock(ticket, actor)) {
            actions.add(new TicketActionDto("RESERVE_STOCK", "fulfillment", "จองสินค้าจากสต็อก",
                List.of("lines")));
        }
        if (canRecordDelivery(ticket, actor)) {
            actions.add(new TicketActionDto("RECORD_PARTIAL_DELIVERY", "fulfillment", "บันทึกการส่งสินค้า",
                List.of("source", "lines")));
            actions.add(new TicketActionDto("COMPLETE_DELIVERY", "fulfillment", "ส่งมอบครบ"));
        }
        if (ACCOUNT_ROLES.contains(actor.role()) && canConfirmFinalPaymentNow(s)) {
            actions.add(new TicketActionDto("FINAL_PAYMENT", "payment", "รับเงินครบ"));
        }
        if (canClose(s, actor)) actions.add(new TicketActionDto("CLOSE", "operational", "ปิดงาน"));
        if (canCancel(s, actor)) actions.add(new TicketActionDto("CANCEL", "operational", "ยกเลิก"));
        if (canEditItems(s, actor)) actions.add(new TicketActionDto("EDIT_ITEMS", "operational", "แก้ไขรายการ"));
    }

    private void addStageActions(List<TicketActionDto> actions, TicketSummaryDto s, UserPrincipal actor) {
        for (String target : DealStage.ORDER) {
            if (target.equals(s.salesStage())) continue;
            if (canSetStage(s, target, actor)) {
                actions.add(new TicketActionDto("ADVANCE_STAGE", "stage", "เลื่อนสถานะ", target));
            }
        }
        if (DealStage.ORDER.stream().anyMatch(target -> !target.equals(s.salesStage()) && canSetStage(s, target, actor))) {
            actions.add(new TicketActionDto("UPDATE_STAGE", "stage", "แก้ไขสถานะ", List.of("stage")));
        }
    }

    private void addPolicyActions(List<TicketActionDto> actions, TicketSummaryDto s, UserPrincipal actor) {
        if (canDealOwnership(s, actor)) {
            actions.add(new TicketActionDto("SET_TENDER_REQUIREMENT", "policy", "ตั้งค่าสถานะประมูล", List.of("value")));
            actions.add(new TicketActionDto("SET_ENTRY_CHANNEL", "policy", "ตั้งค่า entry channel", List.of("value")));
        }
        if (ACCOUNT_ROLES.contains(actor.role())) {
            actions.add(new TicketActionDto("WAIVE_DEPOSIT", "policy", "นโยบายมัดจำ", List.of("policy", "reason")));
        }
    }

    private void addQuotationActions(List<TicketActionDto> actions, TicketDto ticket, UserPrincipal actor) {
        if (!canManageQuotation(ticket.summary(), actor)) {
            return;
        }
        boolean canMarkSent = ticket.quotations().stream()
            .anyMatch(q -> legalQuotationTransition(q.docStatus(), QuotationStatus.SENT));
        boolean canMarkAccepted = ticket.quotations().stream()
            .anyMatch(q -> legalQuotationTransition(q.docStatus(), QuotationStatus.ACCEPTED));
        boolean canMarkRejected = ticket.quotations().stream()
            .anyMatch(q -> legalQuotationTransition(q.docStatus(), QuotationStatus.REJECTED));
        if (canMarkSent) {
            actions.add(new TicketActionDto("MARK_QUOTATION_SENT", "doc", "บันทึกว่าส่งใบเสนอราคาแล้ว",
                List.of("quotationId")));
        }
        if (canMarkAccepted) {
            actions.add(new TicketActionDto("MARK_QUOTATION_ACCEPTED", "doc", "บันทึกลูกค้ารับใบเสนอราคา",
                List.of("quotationId")));
        }
        if (canMarkRejected) {
            actions.add(new TicketActionDto("MARK_QUOTATION_REJECTED", "doc", "บันทึกลูกค้าปฏิเสธใบเสนอราคา",
                List.of("quotationId")));
        }
    }

    private boolean canSubmit(TicketSummaryDto s, UserPrincipal actor) {
        return SALES_ROLES.contains(actor.role()) && s.createdById() == actor.id()
            && TicketStatus.DRAFT.equals(s.status()) && s.itemCount() > 0;
    }

    private boolean canGenerateQuotation(TicketSummaryDto s, UserPrincipal actor) {
        return SALES_ROLES.contains(actor.role()) && s.createdById() == actor.id()
            && QUOTATION_ALLOWED_STATUSES.contains(s.status());
    }

    private boolean canManageQuotation(TicketSummaryDto s, UserPrincipal actor) {
        return CEO_ROLES.contains(actor.role())
            || (SALES_ROLES.contains(actor.role()) && s.createdById() == actor.id());
    }

    private boolean canConfirmCustomer(TicketSummaryDto s, UserPrincipal actor) {
        return SALES_ROLES.contains(actor.role()) && s.createdById() == actor.id()
            && TicketStatus.QUOTATION_ISSUED.equals(s.status())
            && (s.paymentStatus() == null || "CUSTOMER_CONFIRMED".equals(s.paymentStatus()));
    }

    private boolean canCreateDepositNotice(TicketSummaryDto s, UserPrincipal actor) {
        return SALES_ROLES.contains(actor.role()) && s.createdById() == actor.id()
            && TicketStatus.QUOTATION_ISSUED.equals(s.status())
            && "CUSTOMER_CONFIRMED".equals(s.paymentStatus())
            && !DepositPolicy.bypassesDepositNotice(s.depositPolicy());
    }

    private boolean canIssueImportRequest(TicketSummaryDto s) {
        boolean depositPolicyBypassesNotice = DepositPolicy.bypassesDepositNotice(s.depositPolicy())
            && (s.paymentStatus() == null || "CUSTOMER_CONFIRMED".equals(s.paymentStatus()));
        boolean depositReady = "DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus())
            || "DEPOSIT_PAID".equals(s.paymentStatus())
            || depositPolicyBypassesNotice;
        return TicketStatus.QUOTATION_ISSUED.equals(s.status()) && depositReady && s.fulfillmentStatus() == null;
    }

    private boolean canRecordPayment(TicketSummaryDto s, UserPrincipal actor) {
        return ACCOUNT_ROLES.contains(actor.role())
            && s.amountPayable() != null
            && s.amountPayable().signum() > 0
            && !PaymentStage.FULLY_PAID.equals(s.paymentStage());
    }

    private boolean canReserveStock(TicketDto ticket, UserPrincipal actor) {
        return FULFILMENT_ROLES.contains(actor.role())
            && !ticket.items().isEmpty()
            && hasRemainingDelivery(ticket)
            && !FulfilmentStatus.FULLY_DELIVERED.equals(ticket.summary().fulfillmentStatus());
    }

    private boolean canRecordDelivery(TicketDto ticket, UserPrincipal actor) {
        if (!FULFILMENT_ROLES.contains(actor.role()) || !hasRemainingDelivery(ticket)) {
            return false;
        }
        TicketSummaryDto s = ticket.summary();
        boolean stockAvailable = ticket.items().stream()
            .anyMatch(item -> nullToZero(item.qtyFromStock()).compareTo(nullToZero(item.qtyDelivered())) > 0);
        boolean warehouseAvailable = warehouseDeliveryAvailable(s, s.id());
        return FulfilmentStatus.FROM_STOCK.equals(s.fulfillmentStatus()) || stockAvailable || warehouseAvailable;
    }

    private boolean canClose(TicketSummaryDto s, UserPrincipal actor) {
        boolean legacyOk = TicketStatus.DOCUMENT_ISSUED.equals(s.status())
            && (s.paymentStatus() == null || "FULLY_PAID".equals(s.paymentStatus()));
        boolean dualTrackOk = TicketStatus.QUOTATION_ISSUED.equals(s.status())
            && "FULLY_PAID".equals(s.paymentStatus())
            && deliveryGateComplete(s);
        return s.createdById() == actor.id() && (legacyOk || dualTrackOk);
    }

    private boolean deliveryGateComplete(TicketSummaryDto s) {
        if (FulfilmentStatus.FULLY_DELIVERED.equals(s.fulfillmentStatus())) {
            return true;
        }
        return FulfilmentStatus.GOODS_RECEIVED.equals(s.fulfillmentStatus())
            && !tickets.hasDeliveries(s.id());
    }

    /**
     * Advance to CLOSED_PAID only when BOTH gates are satisfied — payment is fully
     * paid AND the goods have actually been delivered to the customer
     * (FULLY_DELIVERED). CLOSED_PAID (S20) must not be reachable on payment alone
     * while goods are still undelivered.
     *
     * This is deliberately STRICTER than {@link #deliveryGateComplete} (used by the
     * manual {@link #close}): that predicate also accepts a legacy coarse deal at
     * GOODS_RECEIVED with no delivery records, but GOODS_RECEIVED only means the
     * goods reached GLR's warehouse (S17) — nothing has been handed to the
     * customer. Auto-advancing on that would skip DELIVERED (S19) for a fully-paid
     * deal whose stock is sitting in our warehouse, which is exactly the bug this
     * gate exists to prevent. {@code paymentFullyPaid} is passed explicitly because
     * the in-hand summary at the payment call sites was loaded before the FULLY_PAID
     * write in the same transaction; fulfilment status is read live from s.
     */
    private void maybeAdvanceClosedPaid(TicketSummaryDto s, boolean paymentFullyPaid, UserPrincipal actor) {
        if (paymentFullyPaid && FulfilmentStatus.FULLY_DELIVERED.equals(s.fulfillmentStatus())) {
            autoAdvanceStage(s, DealStage.CLOSED_PAID, actor);
        }
    }

    private boolean canCancel(TicketSummaryDto s, UserPrincipal actor) {
        return s.createdById() == actor.id()
            && !TicketStatus.CLOSED.equals(s.status())
            && !TicketStatus.CANCELLED.equals(s.status());
    }

    private boolean canEditItems(TicketSummaryDto s, UserPrincipal actor) {
        return SALES_ROLES.contains(actor.role()) && s.createdById() == actor.id()
            && Set.of(TicketStatus.DRAFT, TicketStatus.SUBMITTED, TicketStatus.IN_REVIEW,
                      TicketStatus.PRICE_PROPOSED).contains(s.status());
    }

    private boolean canSetStage(TicketSummaryDto s, String targetStage, UserPrincipal actor) {
        try {
            requireStageWriteAccess(s, targetStage, actor);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    private TicketDto requireTicket(long id) {
        return tickets.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"));
    }

    private TicketSummaryDto loadAndVerifyStatus(long ticketId, String expectedStatus) {
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!expectedStatus.equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Expected status '" + expectedStatus + "' but ticket is '" + s.status() + "'");
        }
        return s;
    }

    private void requireActive(TicketSummaryDto summary) {
        if (!DealLifecycle.ACTIVE.equals(summary.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ดีลไม่ได้อยู่ในสถานะ ACTIVE (" + summary.lifecycle() + ") จึงแก้ไขขั้นตอนนี้ไม่ได้");
        }
    }

    private void requireOwner(TicketSummaryDto summary, UserPrincipal actor) {
        if (summary.createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private boolean canDealOwnership(TicketSummaryDto s, UserPrincipal actor) {
        String role = actor.role();
        boolean isOwner = SALES_ROLES.contains(role) && s.createdById() == actor.id();
        return isOwner || "sales_manager".equals(role) || "ceo".equals(role);
    }
}
