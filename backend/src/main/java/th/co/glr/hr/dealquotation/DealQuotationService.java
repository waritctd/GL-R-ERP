package th.co.glr.hr.dealquotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationCountsDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.dealquotation.DealQuotationRepository.ContactSnapshot;
import th.co.glr.hr.dealquotation.DealQuotationRepository.CustomerSnapshot;
import th.co.glr.hr.dealquotation.DealQuotationRepository.InsertDraftParams;
import th.co.glr.hr.dealquotation.DealQuotationRepository.NewItem;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.CancelRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.RejectRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.notification.EmailRecipient;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.RelatedDocumentType;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Quotation v2 (direct deal quotation, V165) — see docs/sales/quotation-v2-plan.md for the full spec. Sales
 * creates a deal, adds items straight onto a quotation with typed unit price + discount (the
 * pricing-request/factory-quote/CEO-costing chain is bypassed for this release — owner ruling
 * 2026-09-09), the server computes every number ({@link WastageCalculator}), and sales_manager/
 * ceo approves. Extends the SAME {@code sales.quotation} aggregate {@code customerquotation/*}
 * already uses (tagged {@code origin = 'DEAL_DIRECT'}) — that package stays untouched.
 *
 * <p>Every authz decision lives here, not in {@link DealQuotationController} (that class only
 * calls {@code sessions.requireUser} and delegates) — this codebase's standing convention.
 *
 * <p><strong>Per-employee grant (owner ruling, Ploy 2026-09-09):</strong> ภิญญดา (employee 144,
 * QC&amp;ISO / role {@code qc}) must be able to create quotations without being given the
 * {@code sales} or {@code sales_manager} role. {@code hr.employee.can_create_quotation}
 * (V165) is a CAPABILITY in the exact shape of the existing admin capability
 * ({@code hr.employee.is_admin} → {@link EmployeeAuthRepository#isAdmin} →
 * {@code AuthResponse.admin}): a plain boolean, read LIVE at decision time via
 * {@link EmployeeAuthRepository#canCreateQuotation}, never inferred from the session principal.
 * A grant lets its holder create/edit/submit/cancel/revise and view/list/download on ANY deal —
 * see {@link #requireEditAccess} / {@link #requireViewAccess} — but never approve/reject.
 */
@Service
public class DealQuotationService {
    private static final Logger log = LoggerFactory.getLogger(DealQuotationService.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final BigDecimal VAT_RATE = new BigDecimal("0.07");

    private static final Set<String> EDIT_ROLES = Set.of("sales", "sales_manager");
    private static final Set<String> APPROVE_ROLES = Set.of("sales_manager", "ceo");
    private static final Set<String> VIEW_ROLES = Set.of("sales", "sales_manager", "ceo", "import", "account");

    private final DealQuotationRepository quotations;
    private final TicketRepository tickets;
    private final CustomerRepository customers;
    private final ContactRepository contacts;
    private final NotificationRepository notifications;
    /** The per-employee "can create quotations" capability gate (owner ruling, Ploy 2026-09-09) —
     * read LIVE at decision time via {@link EmployeeAuthRepository#canCreateQuotation}, never from
     * {@code actor} (a {@link UserPrincipal} is captured at login and would otherwise go stale the
     * moment a grant is added or revoked). Same discipline as {@code isAdmin} elsewhere. */
    private final EmployeeAuthRepository employeeAuth;
    /** "Make the mailer injectable so tests use a no-op": {@link NotificationEmailService} is a
     * plain constructor dependency, and it in turn depends on the {@code Mailer} interface — a
     * test wires a real {@code NotificationEmailService} with a no-op (or capturing) {@code
     * Mailer} rather than needing a second mailer abstraction here. */
    private final NotificationEmailService approvalMailer;
    private final th.co.glr.hr.ticket.QuotationRenderer renderer;
    private final EmployeeSignatureRepository signatures;
    private final String appBaseUrl;

    public DealQuotationService(DealQuotationRepository quotations, TicketRepository tickets,
                                CustomerRepository customers, ContactRepository contacts,
                                NotificationRepository notifications,
                                NotificationEmailService approvalMailer,
                                th.co.glr.hr.ticket.QuotationRenderer renderer,
                                EmployeeAuthRepository employeeAuth,
                                EmployeeSignatureRepository signatures,
                                @Value("${app.mail.app-base-url:}") String appBaseUrl) {
        this.quotations = quotations;
        this.tickets = tickets;
        this.customers = customers;
        this.contacts = contacts;
        this.notifications = notifications;
        this.approvalMailer = approvalMailer;
        this.renderer = renderer;
        this.employeeAuth = employeeAuth;
        this.signatures = signatures;
        this.appBaseUrl = appBaseUrl == null ? "" : appBaseUrl.trim();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Create
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public DealQuotationDto create(long ticketId, UpsertDealQuotationRequest request, UserPrincipal actor) {
        TicketSummaryDto ticket = requireTicketSummary(ticketId);
        requireEditAccess(actor, ticket);
        quotations.lockTicket(ticketId);
        ContactSnapshot contact = resolveContact(request.contactId(), null, ticket);
        List<NewItem> items = buildItems(request.items());
        BigDecimal subtotal = WastageCalculator.subtotal(items.stream().map(NewItem::lineAmount).toList());
        CustomerSnapshot customerSnapshot = customerSnapshot(ticket);
        String number = quotations.nextQuotationCode();
        long id = quotations.insertDraft(new InsertDraftParams(
            ticketId, number, actor.id(), ticket.createdById(),
            customerSnapshot.name(), customerSnapshot.address(),
            customerSnapshot.taxId(), customerSnapshot.phone(),
            contact,
            ticket.projectName(), blankToNull(request.deptCode()), blankToNull(request.unitCode()),
            request.offerDate(), request.depositPercent(), blankToNull(request.remainderMode()),
            request.creditDays(), request.validityDays(), blankToNull(request.customerNotes()),
            subtotal, null, 1, items));
        return requireQuotation(id);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Read
    // ─────────────────────────────────────────────────────────────────────────────────────

    public DealQuotationDto get(long id, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireViewAccess(actor, quotation);
        return quotation;
    }

    public List<DealQuotationDto> listForTicket(long ticketId, UserPrincipal actor) {
        TicketSummaryDto ticket = requireTicketSummary(ticketId);
        if (!hasQuotationGrant(actor)) {
            requireRole(actor, VIEW_ROLES);
            if ("sales".equals(actor.role()) && ticket.createdById() != actor.id()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงดีลนี้");
            }
        }
        return quotations.findByTicket(ticketId);
    }

    /** The approver queue (sales_manager/ceo/import/account see everything; sales sees only their
     * own deals' quotations; a {@code canCreateQuotation}-granted employee sees everything, same
     * as sales_manager — the grant is "any deal", not "own deal only"). */
    public List<DealQuotationDto> search(List<String> statuses, boolean needsRework, UserPrincipal actor) {
        return quotations.search(statuses, listOwnerScope(actor), needsRework);
    }

    /** Per-status counts for the list page's tab labels (owner feedback F5, 2026-09-10) — the
     * SAME scope decision as {@link #search}, so a tab's count is always the size of the list
     * that tab would show. */
    public DealQuotationCountsDto counts(UserPrincipal actor) {
        return quotations.counts(listOwnerScope(actor));
    }

    /**
     * The ONE list-scope decision {@link #search} and {@link #counts} share: a
     * {@code canCreateQuotation}-granted employee sees everything (the grant is "any deal");
     * otherwise the caller must hold a {@link #VIEW_ROLES} role, and {@code sales} is scoped to
     * deals they created (returns their own id as the owner filter); every other view role is
     * unrestricted (null).
     */
    private Long listOwnerScope(UserPrincipal actor) {
        boolean grant = hasQuotationGrant(actor);
        if (!grant) {
            requireRole(actor, VIEW_ROLES);
        }
        return (!grant && "sales".equals(actor.role())) ? actor.id() : null;
    }

    /** Rule: server recomputes every number, always — this is the stateless preview the item
     * editor calls on every keystroke (debounced client-side), performing ZERO writes. */
    public DealQuotationItemDto calculateLine(ItemInput input, UserPrincipal actor) {
        if (!EDIT_ROLES.contains(actor.role()) && !hasQuotationGrant(actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        return toItemDto(0L, 0, buildItem(input));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Update (DRAFT-only; FULL replace of items)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public DealQuotationDto update(long id, UpsertDealQuotationRequest request, UserPrincipal actor) {
        DealQuotationDto existing = requireQuotation(id);
        TicketSummaryDto ticket = requireTicketSummary(existing.ticketId());
        requireEditAccess(actor, ticket);
        ContactSnapshot contact = resolveContact(request.contactId(), existing.contactId(), ticket);
        List<NewItem> items = buildItems(request.items());
        BigDecimal subtotal = WastageCalculator.subtotal(items.stream().map(NewItem::lineAmount).toList());
        // Compare-and-set FIRST, before touching a single item row — a header update that finds
        // the row no longer DRAFT (a concurrent submit/approve) must leave the items untouched.
        // F7 (2026-09-10): re-snapshot the ลูกค้า columns from the LIVE customer row on every DRAFT
        // save. The deal card now edits เลขที่ผู้เสียภาษี / โทร. in place, and the promise made
        // there is "the values on screen at save time" -- which only holds if this save rewrites
        // them. updateHeader's own WHERE clause keeps it DRAFT-only, so an issued/approved document
        // stays frozen at what it was approved with.
        int rows = quotations.updateHeader(id, contact, customerSnapshot(ticket),
            blankToNull(request.deptCode()), blankToNull(request.unitCode()),
            request.offerDate(), request.depositPercent(), blankToNull(request.remainderMode()),
            request.creditDays(), request.validityDays(), blankToNull(request.customerNotes()), subtotal);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงแก้ไขไม่ได้");
        }
        quotations.deleteItems(id);
        quotations.insertItems(id, items);
        return requireQuotation(id);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Status machine: DRAFT -> (submit) -> PENDING_APPROVAL -> (approve) -> APPROVED
    //                                                        -> (reject+reason) -> DRAFT
    //                 DRAFT -> (cancel) -> CANCELLED
    //                 APPROVED -> (revise) -> new DRAFT child; parent -> SUPERSEDED once the
    //                             CHILD reaches APPROVED (not before).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public DealQuotationDto submit(long id, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireEditAccessForQuotation(actor, quotation);
        if (quotation.items().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ใบเสนอราคาต้องมีอย่างน้อยหนึ่งรายการก่อนส่งขออนุมัติ");
        }
        // ผู้สั่งซื้อ is mandatory (owner feedback F2): create/update already refuse a draft with no
        // resolvable contact, so this only ever catches a row that predates V167 on a ticket that
        // had no contact -- it stays a draft until the rep saves it with one.
        if (quotation.contactId() == null || isBlank(quotation.contactName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุผู้สั่งซื้อ");
        }
        // Item completeness rule, re-checked over the STORED rows: create/update already enforce
        // this on write (#buildItem), but a row can predate this rule (created before this
        // migration of behaviour) or a stale client could in principle bypass the write-time
        // check -- submit is the last gate before an approver ever sees the document.
        for (DealQuotationItemDto item : quotation.items()) {
            requireStoredItemComplete(item);
        }
        int rows = quotations.submit(id, actor.id());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงส่งขออนุมัติไม่ได้");
        }
        DealQuotationDto submitted = requireQuotation(id);
        tickets.addEvent(submitted.ticketId(), actor.id(), actor.name(), TicketEventKind.SUBMITTED, null, null,
            "ส่งใบเสนอราคา " + submitted.number() + " ขออนุมัติ");
        // M5: notify() used to run the role fan-out (2 inserts + 2 emails via SalesNotificationMailer)
        // INSIDE this transaction. Same reasoning as #approve's own afterCommit -- mail (and the
        // in-app row alongside it here, since notifyByRoleAtLink does both in one call) must not be
        // observable before the submit itself is durable; a rollback after this line would otherwise
        // have already notified sales_manager/ceo about a submission that never actually happened.
        afterCommit(() -> notifySubmitted(submitted));
        return submitted;
    }

    private void notifySubmitted(DealQuotationDto submitted) {
        String message = "ใบเสนอราคา " + submitted.number() + " รอการอนุมัติ";
        String link = "/quotations/" + submitted.id();
        notifications.notifyByRoleAtLink("sales_manager", TicketEventKind.DEAL_QUOTATION_SUBMITTED, message, link);
        notifications.notifyByRoleAtLink("ceo", TicketEventKind.DEAL_QUOTATION_SUBMITTED, message, link);
    }

    @Transactional
    public DealQuotationDto approve(long id, ApproveRequest request, UserPrincipal actor) {
        requireApproveAccess(actor);
        DealQuotationDto quotation = requireQuotation(id);
        LocalDate approvalDate = LocalDate.now(BANGKOK);
        LocalDate validityDate = quotation.validityDays() != null
            ? approvalDate.plusDays(quotation.validityDays()) : null;
        int rows = quotations.approve(id, actor.id(), blankToNull(request.note()), validityDate);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะรออนุมัติ จึงอนุมัติไม่ได้");
        }
        DealQuotationDto approved = requireQuotation(id);
        // The customer's last approved document stays valid until a REVISION actually reaches
        // APPROVED — see the class Javadoc's status-machine note. Only fires for a revision (a
        // first-ever quotation has no parent).
        if (approved.parentQuotationId() != null) {
            quotations.supersede(approved.parentQuotationId());
        }
        tickets.addEventWithDocument(approved.ticketId(), actor.id(), actor.name(), TicketEventKind.QUOTATION_ISSUED,
            null, null, "อนุมัติใบเสนอราคา " + approved.number(), RelatedDocumentType.QUOTATION, id);
        String message = "ใบเสนอราคา " + approved.number() + " ได้รับอนุมัติแล้ว";
        notifyRepAndCreator(approved, TicketEventKind.DEAL_QUOTATION_APPROVED, message);
        afterCommit(() -> sendApprovalEmail(approved));
        return approved;
    }

    @Transactional
    public DealQuotationDto reject(long id, RejectRequest request, UserPrincipal actor) {
        requireApproveAccess(actor);
        // 404 for a genuinely missing id, 409 only once we know the row exists but is in the
        // wrong status -- reject() used to skip straight to the compare-and-set UPDATE, so a
        // missing id and a wrong-status id were indistinguishable (both 409), unlike every other
        // status-machine method here (approve/submit/cancel/update all call requireQuotation
        // first).
        requireQuotation(id);
        int rows = quotations.reject(id, actor.id(), request.reason());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะรออนุมัติ จึงไม่สามารถตีกลับได้");
        }
        DealQuotationDto rejected = requireQuotation(id);
        tickets.addEvent(rejected.ticketId(), actor.id(), actor.name(), TicketEventKind.REJECTED, null, null,
            "ไม่อนุมัติใบเสนอราคา " + rejected.number() + " — " + request.reason());
        notifyRepAndCreator(rejected, TicketEventKind.DEAL_QUOTATION_REJECTED,
            "ใบเสนอราคา " + rejected.number() + " ไม่ได้รับอนุมัติ: " + request.reason());
        return rejected;
    }

    @Transactional
    public DealQuotationDto cancel(long id, CancelRequest request, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireEditAccessForQuotation(actor, quotation);
        int rows = quotations.cancel(id);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงยกเลิกไม่ได้");
        }
        DealQuotationDto cancelled = requireQuotation(id);
        String reasonSuffix = request.reason() != null && !request.reason().isBlank()
            ? " — " + request.reason().trim() : "";
        tickets.addEvent(cancelled.ticketId(), actor.id(), actor.name(), TicketEventKind.CANCELLED, null, null,
            "ยกเลิกร่างใบเสนอราคา " + cancelled.number() + reasonSuffix);
        return cancelled;
    }

    @Transactional
    public DealQuotationDto createRevision(long id, UserPrincipal actor) {
        DealQuotationDto source = requireQuotation(id);
        requireEditAccessForQuotation(actor, source);
        if (!QuotationStatus.APPROVED.equals(source.docStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "สร้างฉบับแก้ไขได้จากใบเสนอราคาที่ได้รับอนุมัติแล้วเท่านั้น (ปัจจุบัน: " + source.docStatus() + ")");
        }
        quotations.lockTicket(source.ticketId());
        // M3: two concurrent "create a revision" calls on the same APPROVED parent both compute
        // the SAME deterministic child number ({base}-{revisionNo+1}, from the SAME source row),
        // and sales.quotation.number is UNIQUE (V6) -- so the second INSERT threw
        // DuplicateKeyException, surfaced as a bare 500 with no Thai message. The advisory lock
        // above already serialises callers for this ticket, so the SECOND caller (once it
        // acquires the lock after the first commits) now observes the first caller's
        // already-inserted child here and 409s with an intelligible reason instead of racing into
        // the unique-index violation.
        if (quotations.hasOpenRevision(source.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "มีฉบับแก้ไขของใบเสนอราคานี้อยู่แล้ว");
        }
        String baseNumber = DealQuotationRepository.baseNumber(source.number(), source.revisionNo());
        // M3 (found while testing the fix above): NOT source.revisionNo() + 1 -- see
        // DealQuotationRepository#nextRevisionNo's own Javadoc for the duplicate-key crash that
        // naive formula still has once a prior revision attempt was cancelled.
        int nextRevisionNo = quotations.nextRevisionNo(source.ticketId(), baseNumber);
        String newNumber = DealQuotationRepository.revisionNumber(baseNumber, nextRevisionNo);
        List<NewItem> items = source.items().stream().map(this::toNewItemFromDto).toList();
        long newId = quotations.insertDraft(new InsertDraftParams(
            source.ticketId(), newNumber, actor.id(), source.salesRepId(),
            source.customerName(), source.customerAddress(), source.customerTaxId(), source.customerPhone(),
            // The parent's frozen snapshot, verbatim -- the rep re-chooses (or the ticket's
            // contact re-defaults) only on the revision's own next save through #update.
            new ContactSnapshot(source.contactId(), source.contactName(), source.contactPhone(), source.contactEmail()),
            source.projectName(), source.deptCode(), source.unitCode(), source.offerDate(),
            source.depositPercent(), source.remainderMode(), source.creditDays(), source.validityDays(),
            source.customerNotes(), source.subtotalAmount(), source.id(), nextRevisionNo, items));
        tickets.addEvent(source.ticketId(), actor.id(), actor.name(), TicketEventKind.REVISION_REQUESTED, null, null,
            "สร้างใบเสนอราคาฉบับแก้ไข " + newNumber + " จาก " + source.number());
        return requireQuotation(newId);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Rendering — builds th.co.glr.hr.ticket.QuotationRenderModel via DealQuotationRenderAdapter
    // and renders it through the SAME QuotationRenderer the legacy/PCR path uses (see that
    // class's Javadoc — one renderer, one rich model, two builders).
    // ─────────────────────────────────────────────────────────────────────────────────────

    public byte[] renderPdf(long id, UserPrincipal actor) {
        return renderer.toPdf(buildRenderModel(id, actor));
    }

    public byte[] renderXlsx(long id, UserPrincipal actor) {
        return renderer.toXlsx(buildRenderModel(id, actor));
    }

    private th.co.glr.hr.ticket.QuotationRenderModel buildRenderModel(long id, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireViewAccess(actor, quotation);
        return toRenderModel(quotation);
    }

    /** Resolves the approver's signature bytes (a live {@code hr.employee_signature} read — never
     * cached) and hands everything to the adapter, which is otherwise a pure function. */
    private th.co.glr.hr.ticket.QuotationRenderModel toRenderModel(DealQuotationDto quotation) {
        byte[] signaturePng = null;
        String signatureMime = null;
        if (quotation.approverHasSignature() && quotation.approvedById() != null) {
            var signature = signatures.find(quotation.approvedById());
            if (signature.isPresent()) {
                signaturePng = signature.get().image();
                signatureMime = signature.get().mimeType();
            }
        }
        return DealQuotationRenderAdapter.toRenderModel(quotation, signaturePng, signatureMime);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Approval email — Thai body, PDF attached, sent to the rep AND the creator (deduped),
    // after the approving transaction actually commits (mail cannot be un-sent).
    // ─────────────────────────────────────────────────────────────────────────────────────

    private void sendApprovalEmail(DealQuotationDto approved) {
        byte[] pdf;
        try {
            pdf = renderer.toPdf(toRenderModel(approved));
        } catch (Exception e) {
            // H1: was logged WITHOUT the exception itself (only e.getMessage()), so a render
            // failure here -- e.g. the underlineCache-across-workbooks bug this PDF render is a
            // second render THROUGH -- left no stack trace anywhere, and the approval email
            // silently never sent with no way to diagnose why. Passing the exception as the last
            // varargs argument (unmatched by a `{}` placeholder) makes SLF4J log the full stack
            // trace. The in-app notification above already fired unconditionally before this
            // deferred call ever runs, so the rep/creator are still told the quotation was
            // approved even when the PDF (and therefore the email) fails.
            log.error("Deal quotation approval PDF render failed: quotationId={}", approved.id(), e);
            return;
        }
        String subject = "ใบเสนอราคา " + approved.number() + " ได้รับอนุมัติแล้ว";
        String link = appBaseUrl + "/quotations/" + approved.id();
        String body = "ใบเสนอราคา " + approved.number() + " ได้รับการอนุมัติแล้วโดย "
            + (approved.approvedByName() != null ? approved.approvedByName() : "ผู้อนุมัติ")
            + "\nยอดรวมทั้งสิ้น " + money(approved.grandTotal()) + " บาท"
            + "\nดูรายละเอียด: " + link;
        String filename = approved.number() + ".pdf";
        Set<String> sentTo = new HashSet<>();
        sendToEmployeeIfPossible(approved.salesRepId(), subject, body, filename, pdf, sentTo);
        sendToEmployeeIfPossible(approved.createdById(), subject, body, filename, pdf, sentTo);
    }

    /** In-app notification to the rep AND the creator, deduped when they are the same employee
     * (a rep who created their own deal). */
    private void notifyRepAndCreator(DealQuotationDto quotation, String kind, String message) {
        String link = "/quotations/" + quotation.id();
        notifications.notifyEmployeeAtLink(quotation.salesRepId(), kind, message, link);
        if (quotation.createdById() != quotation.salesRepId()) {
            notifications.notifyEmployeeAtLink(quotation.createdById(), kind, message, link);
        }
    }

    private void sendToEmployeeIfPossible(long employeeId, String subject, String body, String filename,
                                          byte[] pdf, Set<String> sentTo) {
        java.util.Optional<EmailRecipient> recipient = notifications.findEmployeeRecipient(employeeId);
        if (recipient.isEmpty() || recipient.get().email() == null || recipient.get().email().isBlank()) {
            log.warn("Deal quotation approval email skipped: employee={} has no email on file", employeeId);
            return;
        }
        String email = recipient.get().email();
        if (!sentTo.add(email)) {
            return; // dedupe: rep and creator are the same employee, or share an address
        }
        try {
            approvalMailer.sendWithAttachment(email, subject, body, filename, pdf);
        } catch (Exception e) {
            log.error("Deal quotation approval email failed: to={}", email, e);
        }
    }

    /** Defers a side effect (the approval email) until the surrounding transaction commits — mail
     * cannot be un-sent. Same "no transaction, no deferral" contract as this codebase's existing
     * {@code th.co.glr.hr.notification.AfterCommit} (package-private there; reimplemented here
     * rather than reaching into that package for one static method). */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // ผู้สั่งซื้อ — owner feedback F2 (2026-09-10): mandatory, snapshotted (V167).
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The ลูกค้า snapshot to stamp onto the quotation, read from the LIVE customer row — the one
     * place create and update both build it, so the two can never drift into snapshotting different
     * things. Owner feedback F7 (2026-09-10) made เลขที่ผู้เสียภาษี / โทร. editable on the deal
     * card; this is what makes an edit reach the document, since {@code updateHeader} now rewrites
     * these columns on every DRAFT save.
     *
     * <p>{@code name} deliberately still comes from the ticket (its own join on the customer row,
     * i.e. equally live) rather than from {@code CustomerDto}, so a deal whose customer row has been
     * deleted out from under it still prints the name it was created with instead of a blank.
     */
    private CustomerSnapshot customerSnapshot(TicketSummaryDto ticket) {
        CustomerDto customer = ticket.customerId() != null
            ? customers.findById(ticket.customerId()).orElse(null) : null;
        return new CustomerSnapshot(
            ticket.customerName(),
            customer != null ? customer.address() : null,
            customer != null ? customer.taxId() : null,
            customer != null ? customer.phone() : null);
    }

    /**
     * Resolves the ผู้สั่งซื้อ for a create/update and returns the FROZEN snapshot to store —
     * precedence: the request's own {@code contactId}, else the contact the draft already carries
     * ({@code existingContactId}, update only), else the deal's contact
     * ({@code sales.ticket.contact_id}). None → 400 "กรุณาระบุผู้สั่งซื้อ" (the owner's rule: a
     * quotation cannot be saved without one). The contact must exist and belong to the deal's
     * customer — a contact id from another customer is a client bug or a probe, refused as 400 with
     * the same wording so nothing about other customers' contacts leaks. The name/phone/email are
     * read NOW and written onto the quotation; a later edit of the contact row never changes the
     * document (see V167's header for why that matters for an approved, emailed PDF).
     */
    private ContactSnapshot resolveContact(Long requestedContactId, Long existingContactId, TicketSummaryDto ticket) {
        Long contactId = requestedContactId != null ? requestedContactId
            : existingContactId != null ? existingContactId
            : ticket.contactId();
        if (contactId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุผู้สั่งซื้อ");
        }
        ContactDto contact = contacts.findById(contactId)
            .filter(c -> ticket.customerId() != null && c.customerId() == ticket.customerId())
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุผู้สั่งซื้อ"));
        String name = blankToNull(contact.fullName());
        if (name == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุผู้สั่งซื้อ");
        }
        return new ContactSnapshot(contact.id(), name, blankToNull(contact.phone()), blankToNull(contact.email()));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Item arithmetic — the ONLY place ItemInput -> WastageCalculator.Input is assembled.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private List<NewItem> buildItems(List<ItemInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ใบเสนอราคาต้องมีอย่างน้อยหนึ่งรายการ");
        }
        List<NewItem> items = new ArrayList<>();
        int seq = 0;
        for (ItemInput input : inputs) {
            seq++;
            items.add(buildItem(input, seq));
        }
        return items;
    }

    /** {@code calculate-line}'s stateless preview — see {@link #calculateLine}'s own Javadoc for
     * why this stays lenient (no row number = no completeness check; the editor calls this on
     * every keystroke, often with the row still half-typed). */
    private NewItem buildItem(ItemInput input) {
        return buildItem(input, null);
    }

    /**
     * Item completeness rule (owner ruling 2026-09-10, "Item completeness rule" in
     * inline-deal-spec.md): create/update (and {@link #submit}, over the already-stored rows)
     * require every item complete — model/color/texture/sizeText/a resolved
     * {@code sqmPerPiece}/thicknessMm/piecesPerBox/unitPrice all present and positive, plus a
     * quantity for the item's own {@code quantityMode}. {@code brand}/{@code productCode}/
     * {@code locationLabel}/{@code originCountry}/lead time/notes stay optional.
     *
     * <p>{@code rowNumber == null} means "skip the completeness check" — the ONLY caller that
     * passes null is {@link #calculateLine}'s single-item preview (the editor's live calculation
     * line, called on every keystroke; a half-typed row must still preview, not 400). This is a
     * deliberate divergence from the spec's literal "enforce in ItemInput bean validation" line:
     * {@code ItemInput} is the SAME record {@code calculate-line}'s controller method validates
     * with {@code @Valid} (see {@code DealQuotationController#calculateLine}), so any bean-level
     * {@code @NotBlank}/{@code @NotNull} added for these fields would 400 calculate-line's
     * mid-typing preview too — the one behaviour the spec explicitly says must stay lenient.
     * Enforcing here (service-level, row-number-gated) is what actually lets create/update/submit
     * be strict while calculate-line stays lenient; a bean annotation cannot express that
     * distinction without a validation-groups setup this change does not introduce.
     */
    private NewItem buildItem(ItemInput input, Integer rowNumber) {
        BigDecimal sqmPerPiece = resolveSqmPerPiece(input);
        if (rowNumber != null) {
            requireItemComplete(rowNumber, input, sqmPerPiece);
        }
        WastageCalculator.Result result;
        try {
            result = WastageCalculator.calculate(new WastageCalculator.Input(
                sqmPerPiece, input.quantityMode(), input.areaSqm(), input.piecesInput(),
                input.wastageMode(), input.wastageValue(), input.piecesPerBox(), input.unitPrice(),
                input.discountPct()));
        } catch (IllegalArgumentException | ArithmeticException e) {
            // ArithmeticException alongside IllegalArgumentException: BigDecimal#intValueExact
            // (piecesPerBox/ceiling conversions inside WastageCalculator) throws it for a value
            // that does not fit exactly -- an absurd piecesInput/areaSqm/wastageValue combination
            // otherwise reached the generic 500 handler instead of this 400.
            throw new ApiException(HttpStatus.BAD_REQUEST, "ข้อมูลรายการไม่ถูกต้อง: " + e.getMessage());
        }
        BigDecimal vat = money2(result.lineAmount().multiply(VAT_RATE));
        BigDecimal lineTotal = result.lineAmount().add(vat);
        String descriptionLine = DealQuotationLines.descriptionLine(input.model(), input.color(), input.texture(),
            input.productCode(), input.sizeText(), input.thicknessMm());
        return new NewItem(
            input.locationLabel(), input.catalogPriceId(), input.productCode(),
            input.brand(), input.model(), input.color(), input.texture(), input.sizeText(),
            input.thicknessMm(), sqmPerPiece,
            input.quantityMode(), input.areaSqm(), input.piecesInput(),
            input.wastageMode(), input.wastageValue(), input.piecesPerBox(),
            result.piecesBeforeWastage(), result.piecesAfterWastage(), result.piecesFinal(), result.boxes(),
            input.unitPrice(), input.discountPct(), result.netUnitPrice(), result.lineAmount(),
            vat, lineTotal,
            input.originCountry(), input.leadTimeMinDays(), input.leadTimeMaxDays(), input.itemNotes(),
            descriptionLine);
    }

    private NewItem toNewItemFromDto(DealQuotationItemDto item) {
        BigDecimal vat = money2(item.lineAmount().multiply(VAT_RATE));
        BigDecimal lineTotal = item.lineAmount().add(vat);
        return new NewItem(
            item.locationLabel(), item.catalogPriceId(), item.productCode(),
            item.brand(), item.model(), item.color(), item.texture(), item.sizeText(),
            item.thicknessMm(), item.sqmPerPiece(),
            item.quantityMode(), item.areaSqm(), item.piecesInput(),
            item.wastageMode(), item.wastageValue(), item.piecesPerBox(),
            item.piecesBeforeWastage(), item.piecesAfterWastage(), item.piecesFinal(), item.boxes(),
            item.unitPrice(), item.discountPct(), item.netUnitPrice(), item.lineAmount(),
            vat, lineTotal,
            item.originCountry(), item.leadTimeMinDays(), item.leadTimeMaxDays(), item.itemNotes(),
            item.descriptionLine());
    }

    private BigDecimal resolveSqmPerPiece(ItemInput input) {
        if (input.sqmPerPiece() != null) {
            return input.sqmPerPiece();
        }
        return WastageCalculator.parseSqmPerPieceFromSize(input.sizeText());
    }

    /** Item completeness rule — see {@link #buildItem(ItemInput, Integer)}'s own Javadoc. Collects
     * EVERY missing/invalid field before throwing (not just the first) so one 400 tells the rep
     * everything wrong with the row, not a one-at-a-time guessing game. */
    private void requireItemComplete(int rowNumber, ItemInput input, BigDecimal resolvedSqmPerPiece) {
        List<String> missing = new ArrayList<>();
        if (isBlank(input.model())) missing.add("รุ่น");
        if (isBlank(input.color())) missing.add("สี");
        if (isBlank(input.texture())) missing.add("ผิว");
        if (isBlank(input.sizeText())) missing.add("ขนาด");
        if (input.thicknessMm() == null || input.thicknessMm().signum() <= 0) missing.add("ความหนา");
        if (input.piecesPerBox() == null || input.piecesPerBox() < 1) missing.add("จำนวนแผ่นต่อกล่อง");
        if (resolvedSqmPerPiece == null || resolvedSqmPerPiece.signum() <= 0) missing.add("ตร.ม./แผ่น");
        if (input.unitPrice() == null || input.unitPrice().signum() <= 0) missing.add("ราคาต่อหน่วย");
        if (!hasQuantity(input.quantityMode(), input.areaSqm(), input.piecesInput())) missing.add("จำนวน");
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "รายการที่ " + rowNumber + ": ขาด " + String.join(", ", missing));
        }
    }

    /** Same rule as {@link #requireItemComplete}, over an already-stored (post-derivation) row —
     * used only by {@link #submit}'s defensive re-check. {@code seq} is the item's own printed
     * row number, so the message names the same row the rep sees on screen. */
    private void requireStoredItemComplete(DealQuotationItemDto item) {
        List<String> missing = new ArrayList<>();
        if (isBlank(item.model())) missing.add("รุ่น");
        if (isBlank(item.color())) missing.add("สี");
        if (isBlank(item.texture())) missing.add("ผิว");
        if (isBlank(item.sizeText())) missing.add("ขนาด");
        if (item.thicknessMm() == null || item.thicknessMm().signum() <= 0) missing.add("ความหนา");
        if (item.piecesPerBox() == null || item.piecesPerBox() < 1) missing.add("จำนวนแผ่นต่อกล่อง");
        if (item.sqmPerPiece() == null || item.sqmPerPiece().signum() <= 0) missing.add("ตร.ม./แผ่น");
        if (item.unitPrice() == null || item.unitPrice().signum() <= 0) missing.add("ราคาต่อหน่วย");
        if (!hasQuantity(item.quantityMode(), item.areaSqm(), item.piecesInput())) missing.add("จำนวน");
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "รายการที่ " + item.seq() + ": ขาด " + String.join(", ", missing));
        }
    }

    private boolean hasQuantity(String quantityMode, BigDecimal areaSqm, Integer piecesInput) {
        if (WastageCalculator.QUANTITY_MODE_PIECES.equals(quantityMode)) {
            return piecesInput != null && piecesInput >= 1;
        }
        // AREA is the default reading of any other/blank value -- matches WastageCalculator's own
        // interpretation (see its Input record's Javadoc).
        return areaSqm != null && areaSqm.signum() > 0;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Stateless preview shape (id=0, seq=0) for {@link #calculateLine} — no DB round trip. */
    private DealQuotationItemDto toItemDto(long id, int seq, NewItem item) {
        BigDecimal piecesPerSqm = item.sqmPerPiece() != null && item.sqmPerPiece().signum() > 0
            ? BigDecimal.ONE.divide(item.sqmPerPiece(), 2, RoundingMode.HALF_UP) : null;
        String sizeLine = DealQuotationLines.sizeLine(item.sizeText(), item.thicknessMm());
        String calculationLine = DealQuotationLines.calculationLine(item.quantityMode(), item.areaSqm(), piecesPerSqm,
            item.piecesBeforeWastage(), item.wastageMode(), item.wastageValue(), item.piecesFinal(), item.piecesPerBox());
        return new DealQuotationItemDto(id, seq, item.locationLabel(), item.catalogPriceId(), item.productCode(),
            item.brand(), item.model(), item.color(), item.texture(), item.sizeText(), item.thicknessMm(),
            item.sqmPerPiece(), item.quantityMode(), item.areaSqm(), item.piecesInput(), item.wastageMode(),
            item.wastageValue(), item.piecesPerBox(), item.unitPrice(), item.discountPct(), item.originCountry(),
            item.leadTimeMinDays(), item.leadTimeMaxDays(), item.itemNotes(),
            piecesPerSqm, item.piecesBeforeWastage(), item.piecesAfterWastage(), item.piecesFinal(), item.boxes(),
            item.netUnitPrice(), item.lineAmount(), item.descriptionLine(), sizeLine, calculationLine);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Authz — every decision lives here (see class Javadoc).
    // ─────────────────────────────────────────────────────────────────────────────────────

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    /** create/edit/submit/cancel/revise: {@code sales} on their OWN deals only (open question for
     * Ploy, per docs/sales/quotation-v2-plan.md: whether plain {@code sales} may act on another rep's deal —
     * implemented as NO, the conservative reading, until answered); {@code sales_manager} on any
     * deal; OR a {@code canCreateQuotation}-granted employee (owner ruling, Ploy 2026-09-09 —
     * ภิญญดา, employee 144, QC&amp;ISO / role {@code qc}) on ANY deal, regardless of role — the
     * grant is deliberately not further role-restricted; it is a per-person capability, not a
     * role. Read LIVE via {@link #hasQuotationGrant}, never from {@code actor}. */
    private void requireEditAccess(UserPrincipal actor, TicketSummaryDto ticket) {
        boolean grant = hasQuotationGrant(actor);
        if (!EDIT_ROLES.contains(actor.role()) && !grant) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if ("sales_manager".equals(actor.role()) || grant) {
            return; // any deal
        }
        if ("sales".equals(actor.role()) && ticket.createdById() == actor.id()) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงดีลนี้");
    }

    private void requireEditAccessForQuotation(UserPrincipal actor, DealQuotationDto quotation) {
        requireEditAccess(actor, requireTicketSummary(quotation.ticketId()));
    }

    /** view/list/download: {@link #VIEW_ROLES} (sales limited to own deals) OR a
     * {@code canCreateQuotation}-granted employee, who may view any deal — the grant already lets
     * them act on any deal, so it lets them view any deal too (a strict subset would mean they
     * could create a quotation they then couldn't look at). */
    private void requireViewAccess(UserPrincipal actor, DealQuotationDto quotation) {
        if (hasQuotationGrant(actor)) {
            return;
        }
        requireRole(actor, VIEW_ROLES);
        if ("sales".equals(actor.role())) {
            TicketSummaryDto ticket = requireTicketSummary(quotation.ticketId());
            if (ticket.createdById() != actor.id()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
            }
        }
    }

    /** Live DB read of the per-employee grant — see {@link EmployeeAuthRepository#canCreateQuotation}
     * for why this is never cached on {@code actor}. */
    private boolean hasQuotationGrant(UserPrincipal actor) {
        return employeeAuth.canCreateQuotation(actor.id());
    }

    /**
     * approve/reject: {@code sales_manager} or {@code ceo}. No self-exclusion (matches this
     * repo's existing convention — flagged as an open question in docs/sales/quotation-v2-plan.md).
     *
     * <p>Amount routing (&lt;1M sales_manager, &gt;1M CEO) is an OUT-OF-SYSTEM rule by owner
     * decision 2026-09-09 — do not add amount logic here.
     */
    private void requireApproveAccess(UserPrincipal actor) {
        requireRole(actor, APPROVE_ROLES);
    }

    private TicketSummaryDto requireTicketSummary(long ticketId) {
        return tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"))
            .summary();
    }

    private DealQuotationDto requireQuotation(long id) {
        return quotations.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบเสนอราคานี้"));
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private BigDecimal money2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String money(BigDecimal value) {
        return value == null ? "0" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
