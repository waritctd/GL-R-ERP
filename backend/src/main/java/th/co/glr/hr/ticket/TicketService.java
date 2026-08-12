package th.co.glr.hr.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import th.co.glr.hr.pricing.PriceBreakdownItemDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.PricingRequestService.CancelOpenForTicketResult;
import th.co.glr.hr.ticket.TicketResponses.TicketActionDto;
import th.co.glr.hr.ticket.TicketResponses.TicketActionState;
import th.co.glr.hr.ticket.TicketResponses.TicketActionsResponse;

@Service
public class TicketService {
    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private static final Set<String> SALES_ROLES  = Set.of("sales");
    private static final Set<String> IMPORT_ROLES = Set.of("import");
    private static final Set<String> CEO_ROLES    = Set.of("ceo");
    private static final Set<String> FULFILMENT_ROLES = Set.of("import", "ceo");
    // Coarse pre-filter for the stock-coverage declaration only (owner ruling 2026-08-13,
    // "Sales declares, Import can correct" — see reserveStock). DERIVED from the two sets
    // canDeclareStockCoverage actually tests, never hand-written, so it cannot silently drift
    // from them if either is ever changed. It is only the first of two gates: passing this set
    // says "your role could declare on SOME deal", not "on THIS one" — a sales rep still has to
    // own the deal. See requireViewAccess for the same two-stage role-then-row shape.
    private static final Set<String> STOCK_DECLARATION_ROLES =
        Stream.concat(FULFILMENT_ROLES.stream(), SALES_ROLES.stream())
            .collect(Collectors.toUnmodifiableSet());
    // Money-receipt confirmations belong to ฝ่ายบัญชี (accounting), with CEO as fallback.
    private static final Set<String> ACCOUNT_ROLES = Set.of("account", "ceo");
    // Step 1 of the three-party close. Deliberately EXCLUDES ceo, unlike ACCOUNT_ROLES:
    // the CEO signs the second half (verifyClose), so letting them sign the first half
    // too would collapse a two-signature gate into one person. Same reasoning as
    // CommissionService's manager→ceo chain. Do not "fix" this by reusing ACCOUNT_ROLES.
    private static final Set<String> CLOSE_CONFIRM_ROLES = Set.of("account");
    // Who may read tickets at all. Mirrors the frontend's canViewTickets and the mock's
    // list/get gates — hr/employee have no business reading customer pricing.
    // sales_manager is read+comment-only oversight (a project-manager-style follow-up
    // role for the sales team) — it must NEVER be added to SALES_ROLES/IMPORT_ROLES/
    // CEO_ROLES/ACCOUNT_ROLES, only here.
    //
    // #389: this is an ALIAS of TicketAccessPolicy.VIEWER_ROLES, not a second literal.
    // AttachmentController used to carry its own hand-written copy of "who may reach a
    // deal" and the two drifted (hr gained every deal's documents, account lost them);
    // both now read the one constant, so that class of bug cannot recur silently.
    private static final Set<String> VIEWER_ROLES = TicketAccessPolicy.VIEWER_ROLES;
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
    // Dead-deal cascade only (see markLost/cancel below) — PricingRequestService
    // injects TicketRepository, not TicketService, so this dependency direction
    // (TicketService -> PricingRequestService -> TicketRepository) is acyclic.
    private final PricingRequestService pricingRequests;

    public TicketService(TicketRepository tickets, NotificationRepository notifications,
                         PriceCalcService priceCalcService, ObjectMapper objectMapper,
                         CustomerRepository customers, QuotationRenderer quotationRenderer,
                         PricingRequestService pricingRequests) {
        this.tickets           = tickets;
        this.notifications     = notifications;
        this.priceCalcService  = priceCalcService;
        this.objectMapper      = objectMapper;
        this.customers         = customers;
        this.quotationRenderer = quotationRenderer;
        this.pricingRequests   = pricingRequests;
    }

    public List<TicketSummaryDto> list(String status, UserPrincipal actor) {
        requireRole(actor, VIEWER_ROLES);
        Long createdByFilter = "sales".equals(actor.role()) ? actor.id() : null;
        return tickets.findSummaries(status, null, createdByFilter, actor.role(), null);
    }

    public Page<TicketSummaryDto> listPage(String status, UserPrincipal actor, PageRequest page) {
        return listPage(status, null, actor, page);
    }

    /**
     * @param salesStage optional {@link DealStage} filter (e.g. {@code CLOSED_PAID}), additive to
     *                    {@code status} — used by the Step 9 commission "Linked Deal" picker.
     */
    public Page<TicketSummaryDto> listPage(String status, String salesStage, UserPrincipal actor, PageRequest page) {
        requireRole(actor, VIEWER_ROLES);
        Long createdByFilter = "sales".equals(actor.role()) ? actor.id() : null;
        // Phase B (role-scoped views): import/account only see the slice of the deal
        // pipeline relevant to their own worklist — see TicketRepository.appendRoleScope.
        // ceo/sales_manager/sales are unaffected (the repository ignores any other role).
        List<TicketSummaryDto> rows = tickets.findSummaries(status, salesStage, createdByFilter, actor.role(), page);
        // Skip the COUNT round-trip when the whole result set fits on page 0.
        int total = (page.page() == 0 && rows.size() < page.size())
            ? rows.size()
            : tickets.countSummaries(status, salesStage, createdByFilter, actor.role());
        return new Page<>(rows, page.page(), page.size(), total);
    }

    public TicketDto get(long id, UserPrincipal actor) {
        return requireViewAccess(id, actor);
    }

    public List<PaymentReceiptDto> listPayments(long ticketId, UserPrincipal actor) {
        requireViewAccess(ticketId, actor);
        // Phase B: the payment ledger is ฝ่ายบัญชี's own document — import has no
        // business reading it (mirrors salesViewScope.js hiding the "payment" section
        // from import's view of TicketDetailPage).
        if (IMPORT_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
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
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        return projectForRole(ticket, actor.role());
    }

    /**
     * Phase B (role-scoped views): import has no business reading the customer-facing
     * quotation chain (mirrors salesViewScope.js hiding the "quotation" section from
     * import's TicketDetailPage) — project it out here rather than trust every one of
     * requireViewAccess's callers (get, comment, listDeliveries, actions, the quotation
     * file download) to re-check. Every other viewer role gets the DTO unchanged.
     *
     * <p>NOTE: mutation responses built via {@code requireTicket} directly (e.g. import's
     * own procurement actions — recordDelivery, markGoodsReceived, and reserveStock when
     * import is the declarer) do NOT go through this projection and so still embed
     * quotations in their return value. That is a narrower, accepted residual gap
     * (transient, tied to import legitimately performing its own action) recorded in the
     * branch handoff rather than silently closed by touching every one of those call sites.
     * The 2026-08-13 widening of {@link #reserveStock} to the deal owner does not enlarge
     * it: this projection only ever strips anything for {@link #IMPORT_ROLES}, and a sales
     * rep may already read their own deal's quotations through {@link #requireViewAccess}.
     */
    private TicketDto projectForRole(TicketDto ticket, String role) {
        if (!IMPORT_ROLES.contains(role)) {
            return ticket;
        }
        return new TicketDto(ticket.summary(), ticket.items(), ticket.events(), null, List.of());
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
                "ไม่รองรับช่องทางรับงาน '" + request.entryChannel() + "'");
        }
        // Guard priority the same way as entryChannel: an unvalidated value hits the
        // chk_ticket_priority CHECK column in the repository and fails closed (500).
        // Null/blank is fine — the repository defaults it to NORMAL.
        if (request.priority() != null && !request.priority().isBlank()
                && !Priority.isValid(request.priority())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ไม่รองรับระดับความสำคัญ '" + request.priority() + "'");
        }
        String code = tickets.nextTicketCode();
        long id = tickets.create(request, code, actor.id(), actor.name());
        // A newly created deal is always the rep's private draft, whether or not
        // products were attached — import/CEO are notified only once a
        // PricingRequest is created and submitted against this ticket, never at
        // deal-creation time.
        return requireTicket(id);
    }

    /**
     * Deprecated: ticket-level price-request submission has been replaced by the
     * PricingRequest aggregate. Create a pricing request via
     * {@code POST /api/tickets/{ticketId}/pricing-requests} and submit it via
     * {@code POST /api/pricing-requests/{id}/submit} instead.
     */
    @Deprecated
    public TicketDto submit(long ticketId, UserPrincipal actor) {
        throw new ApiException(HttpStatus.CONFLICT,
            "การส่งขอราคาย้ายไปอยู่ที่คำขอราคา (PCR) แล้ว — กรุณาสร้างคำขอราคาจากหน้าดีลแทน");
    }

    /**
     * Deprecated: intake pickup for the legacy submit → pickup → propose-price loop that
     * {@link #submit} permanently severed for new deals (always 409s from {@code draft}).
     * Reachable only for the handful of pre-redesign tickets stranded at {@code submitted}
     * status; no {@code @PostMapping} route exposes this anymore. See
     * {@link th.co.glr.hr.pricingrequest.PricingRequestService} for the current intake flow.
     */
    @Deprecated
    @Transactional
    public TicketDto pickup(long ticketId, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        TicketSummaryDto s = loadAndVerifyStatus(ticketId, TicketStatus.SUBMITTED);
        requireActive(s);
        requireStatusAdvanced(
            tickets.transitionStatus(ticketId, TicketStatus.SUBMITTED, TicketStatus.IN_REVIEW));
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.PICKED_UP, TicketStatus.SUBMITTED, TicketStatus.IN_REVIEW, null);
        return requireTicket(ticketId);
    }

    /**
     * Deprecated: superseded by the PricingRequest → FactoryQuote → PricingDecision chain
     * ({@link th.co.glr.hr.pricingrequest.PricingRequestService},
     * {@link th.co.glr.hr.factoryquote.FactoryQuoteService},
     * {@link th.co.glr.hr.pricingdecision.PricingDecisionService}). Reachable only for
     * legacy tickets stuck in {@code in_review}/{@code price_proposed}; no controller route
     * exposes this anymore.
     */
    @Deprecated
    @Transactional
    public TicketDto proposePrice(long ticketId, ProposePriceRequest request, UserPrincipal actor) {
        if (!IMPORT_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        requireActive(s);
        String currentStatus = s.status();
        if (!PROPOSE_ALLOWED_STATUSES.contains(currentStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "เสนอราคาไม่ได้เมื่อดีลอยู่ในสถานะ '" + currentStatus + "'");
        }
        tickets.replaceItems(ticketId, request.items());
        String snapshot = buildItemSnapshot(request.items());
        boolean isRevision = !TicketStatus.IN_REVIEW.equals(currentStatus);
        String eventKind = isRevision ? TicketEventKind.PRICE_REVISED : TicketEventKind.PRICE_PROPOSED;
        // PROPOSE_ALLOWED_STATUSES is exactly {in_review, price_proposed, approved}; the middle
        // one is the declared price_proposed -> price_proposed self-edge (a re-proposal).
        requireStatusAdvanced(
            tickets.transitionStatus(ticketId, currentStatus, TicketStatus.PRICE_PROPOSED));
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

    /**
     * Deprecated: CEO price approval now happens on the PricingDecision aggregate — see
     * {@link th.co.glr.hr.pricingdecision.PricingDecisionService#approve}. Reachable only
     * for legacy tickets stuck at {@code price_proposed}; no controller route exposes this
     * anymore.
     */
    @Deprecated
    @Transactional
    public TicketDto approve(long ticketId, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        TicketSummaryDto s = loadAndVerifyStatus(ticketId, TicketStatus.PRICE_PROPOSED);
        requireActive(s);
        tickets.approveItemPrices(ticketId);
        tickets.setHasEdits(ticketId, false);
        requireStatusAdvanced(
            tickets.transitionStatus(ticketId, TicketStatus.PRICE_PROPOSED, TicketStatus.APPROVED));
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.APPROVED, TicketStatus.PRICE_PROPOSED, TicketStatus.APPROVED, null);
        notifications.notifyEmployee(s.createdById(), ticketId, "APPROVED",
            "Ticket " + s.code() + " ได้รับการอนุมัติราคาแล้ว — กด Generate ใบเสนอราคาได้เลย");
        return requireTicket(ticketId);
    }

    /**
     * Deprecated: CEO price rejection now happens on the PricingDecision aggregate — see
     * {@link th.co.glr.hr.pricingdecision.PricingDecisionService#returnToImport}. Reachable
     * only for legacy tickets stuck at {@code price_proposed}; no controller route exposes
     * this anymore.
     */
    @Deprecated
    @Transactional
    public TicketDto reject(long ticketId, RejectRequest request, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        TicketSummaryDto s = loadAndVerifyStatus(ticketId, TicketStatus.PRICE_PROPOSED);
        requireActive(s);
        requireStatusAdvanced(
            tickets.transitionStatus(ticketId, TicketStatus.PRICE_PROPOSED, TicketStatus.IN_REVIEW));
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.REJECTED, TicketStatus.PRICE_PROPOSED, TicketStatus.IN_REVIEW, request.reason());
        notifications.notifyByRole("import", ticketId, "REJECTED",
            "Ticket " + s.code() + " ถูกตีกลับ — กรุณาแก้ไขราคาเสนอ");
        return requireTicket(ticketId);
    }

    /**
     * Deprecated: ticket-native quotation generation, superseded by
     * {@link th.co.glr.hr.customerquotation.CustomerQuotationService#create} +
     * {@link th.co.glr.hr.customerquotation.CustomerQuotationService#issue} against the
     * PricingRequest/PricingDecision chain. No controller route exposes this anymore.
     * Quotation download/read for both legacy and PCR-issued quotations stays available via
     * {@link #getQuotationXlsx}/{@link #getQuotationPdf} — this deprecation is write-only.
     */
    @Deprecated
    @Transactional
    public TicketDto generateQuotation(long ticketId, GenerateQuotationRequest request, UserPrincipal actor) {
        if (request == null || request.recipientType() == null || request.recipientType().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุผู้รับใบเสนอราคา");
        }
        String recipientType = request.recipientType().trim();
        if (!QuotationRecipient.isValid(recipientType) || QuotationRecipient.UNSPECIFIED.equals(recipientType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับผู้รับใบเสนอราคา '" + recipientType + "'");
        }
        requireRole(actor, SALES_ROLES);
        TicketDto full = requireTicket(ticketId);
        TicketSummaryDto s = full.summary();
        requireActive(s);
        String fromStatus = s.status();
        if (!QUOTATION_ALLOWED_STATUSES.contains(fromStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ออกใบเสนอราคาได้เฉพาะดีลที่อยู่ในสถานะ 'approved' หรือ 'quotation_issued' เท่านั้น (สถานะปัจจุบัน: '" + fromStatus + "')");
        }
        if (s.createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะเจ้าของดีลเท่านั้นที่สามารถออกใบเสนอราคาได้");
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
        // QUOTATION_ALLOWED_STATUSES is exactly {approved, quotation_issued}; the latter is the
        // declared quotation_issued -> quotation_issued self-edge (an amended re-issue).
        requireStatusAdvanced(
            tickets.transitionStatus(ticketId, fromStatus, TicketStatus.QUOTATION_ISSUED));
        tickets.addEventWithDocument(ticketId, actor.id(), actor.name(),
            TicketEventKind.QUOTATION_ISSUED, fromStatus, TicketStatus.QUOTATION_ISSUED, eventMessage,
            RelatedDocumentType.QUOTATION, created.id());
        String stage = stageForQuotationRecipient(recipientType);
        if (stage != null) {
            autoAdvanceStage(s, stage, actor);
        }
        return requireTicket(ticketId);
    }

    /**
     * Deprecated: ticket-native "mark sent" tracking. The redesigned PCR/CustomerQuotation
     * flow does not track a separate sent step between issue and customer decision (see
     * {@link th.co.glr.hr.customerquotation.CustomerQuotationService#issue} /
     * {@link th.co.glr.hr.customerquotation.CustomerQuotationService#recordOutcome}) — there
     * is no direct replacement. No controller route exposes this anymore.
     */
    @Deprecated
    @Transactional
    public TicketDto markQuotationSent(long ticketId, long quotationId, String note, UserPrincipal actor) {
        return markQuotationLifecycle(ticketId, quotationId, QuotationStatus.SENT,
            TicketEventKind.QUOTATION_SENT, note, actor);
    }

    /**
     * Deprecated: superseded by
     * {@link th.co.glr.hr.customerquotation.CustomerQuotationService#recordOutcome} (outcome
     * {@code ACCEPTED}), which also correctly transitions the owning PricingRequest. No
     * controller route exposes this anymore.
     */
    @Deprecated
    @Transactional
    public TicketDto markQuotationAccepted(long ticketId, long quotationId, String note, UserPrincipal actor) {
        return markQuotationLifecycle(ticketId, quotationId, QuotationStatus.ACCEPTED,
            TicketEventKind.QUOTATION_ACCEPTED, note, actor);
    }

    /**
     * Deprecated: superseded by
     * {@link th.co.glr.hr.customerquotation.CustomerQuotationService#recordOutcome} (outcome
     * {@code REJECTED}). No controller route exposes this anymore.
     */
    @Deprecated
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
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบเสนอราคานี้"));
        if (!legalQuotationTransition(quotation.docStatus(), targetStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ไม่สามารถเปลี่ยนใบเสนอราคาเป็น " + targetStatus + " จากสถานะ " + quotation.docStatus() + " ได้");
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
        // Phase B: explicit denial rather than relying on projectForRole's stripped
        // quotations list to fall through to a "quotation not found" 404 — import
        // downloading a quotation file is a permission question, not a lookup miss.
        if (IMPORT_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        QuotationDto quotation = ticket.quotations().stream()
            .filter(q -> q.id() == quotationId)
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบเสนอราคานี้"));

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
            s.amountPaid(), s.amountOutstanding(), s.overdue(),
            s.closeConfirmedAt(), s.closeConfirmedByName(), s.invoiceOnFile(),
            s.cancelReason(), s.cancelledAt(),
            s.winProbabilityOverride(), s.designerName(), s.ownerName(), s.buyerName(), s.stale());
    }

    /**
     * Closing is a three-party sequence, not one person's decision: the goods must
     * be delivered, the balance paid, the invoice on file, ฝ่ายบัญชี must confirm,
     * and the CEO must verify. Sales no longer closes deals.
     *
     * Throws if the deal is not ready; returns quietly if it is.
     */
    private void requireClosePrerequisites(TicketSummaryDto s) {
        String st = s.status();
        // Legacy path: status=DOCUMENT_ISSUED — only for pre-dual-track tickets
        // (paymentStatus never set) or fully-paid ones. A mid-track ticket that
        // reached document_issued must NOT close unpaid (2026-07-16 audit finding #3);
        // recover it via revision or cancel. These predate the delivery and invoice
        // tracks entirely, so requiring either would strand them permanently.
        boolean legacyOk = TicketStatus.DOCUMENT_ISSUED.equals(st)
            && (s.paymentStatus() == null || "FULLY_PAID".equals(s.paymentStatus()));
        boolean dualTrackOk = TicketStatus.QUOTATION_ISSUED.equals(st)
            && "FULLY_PAID".equals(s.paymentStatus())
            && deliveryGateComplete(s);
        if (!legacyOk && !dualTrackOk) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ปิดงานไม่ได้: ต้องรับเงินครบและส่งมอบสินค้าครบก่อน");
        }
        if (s.amountOutstanding() != null && s.amountOutstanding().signum() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ปิดงานไม่ได้: ยังมียอดค้างชำระ");
        }
        // The invoice is produced externally and uploaded here; legacy deals predate it.
        if (dualTrackOk && !s.invoiceOnFile()) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ปิดงานไม่ได้: ยังไม่ได้แนบใบกำกับภาษี (ฝ่ายบัญชีต้องอัปโหลดก่อน)");
        }
    }

    /** ฝ่ายบัญชี confirms the deal is ready to close. Step 1 of 2. */
    @Transactional
    public TicketDto confirmCloseReady(long ticketId, UserPrincipal actor) {
        requireRole(actor, CLOSE_CONFIRM_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireActive(s);
        if (s.closeConfirmedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ยืนยันปิดงานไปแล้ว — รอ CEO ตรวจสอบ");
        }
        requireClosePrerequisites(s);
        tickets.confirmClose(ticketId, actor.id());
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.CLOSE_CONFIRMED, s.status(), s.status(),
            "ฝ่ายบัญชียืนยันพร้อมปิดงาน — รอ CEO ตรวจสอบ");
        return requireTicket(ticketId);
    }

    /** Withdraw the confirmation before the CEO acts (account or CEO). */
    @Transactional
    public TicketDto revokeCloseConfirmation(long ticketId, String note, UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireActive(s);
        if (s.closeConfirmedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ดีลนี้ยังไม่ได้ยืนยันปิดงาน");
        }
        tickets.clearCloseConfirmation(ticketId);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.CLOSE_CONFIRM_REVOKED, s.status(), s.status(), blankToNull(note));
        return requireTicket(ticketId);
    }

    /**
     * CEO verifies and the deal is closed. Step 2 of 2.
     *
     * The CEO verifies, never overrides: every prerequisite is re-checked here, so
     * a deal that regressed between the two signatures (a refund, a returned
     * delivery, a deleted invoice) cannot slip through on a stale confirmation.
     */
    @Transactional
    public TicketDto verifyClose(long ticketId, UserPrincipal actor) {
        requireRole(actor, CEO_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireActive(s);
        if (s.closeConfirmedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ปิดงานไม่ได้: ต้องให้ฝ่ายบัญชียืนยันก่อน");
        }
        requireClosePrerequisites(s);
        // requireClosePrerequisites admits exactly {quotation_issued, document_issued}, which are
        // the only two states TicketStatus.ALLOWED lets reach CLOSED.
        requireStatusAdvanced(tickets.transitionStatus(ticketId, s.status(), TicketStatus.CLOSED));
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.CLOSED, s.status(), TicketStatus.CLOSED, "CEO ตรวจสอบและปิดงาน");
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
                "ขั้นตอนการรับชำระเงินผ่านสถานะ CUSTOMER_CONFIRMED ไปแล้ว");
        }
        // Idempotent no-op when already CUSTOMER_CONFIRMED: PaymentTrack has no
        // CUSTOMER_CONFIRMED -> CUSTOMER_CONFIRMED self-loop (unlike DEPOSIT_NOTICE_ISSUED's one
        // legal revision loop), so re-confirming must skip the write and the event entirely
        // rather than attempt an edge the machine does not recognise. This preserves the prior
        // observable behaviour (a harmless re-click) without adding a new edge to the machine.
        if (!"CUSTOMER_CONFIRMED".equals(s.paymentStatus())) {
            int rows = tickets.advancePaymentStatus(
                ticketId, s.depositPolicy(), s.paymentStatus(), PaymentTrack.CUSTOMER_CONFIRMED);
            requirePaymentAdvanced(rows);
            tickets.addEvent(ticketId, actor.id(), actor.name(),
                TicketEventKind.CUSTOMER_CONFIRMED, s.status(), s.status(), null);
        }
        // Deal pipeline (V50): a confirmed PO advances the deal — guarded no-op inside.
        autoAdvanceStage(s, DealStage.ORDER_RECEIVED, actor);
        return requireTicket(ticketId);
    }

    /**
     * Turns a lost payment-track compare-and-set race into a 409. {@code advancePaymentStatus}'s
     * 0 rowcount means a concurrent writer moved {@code payment_status} out from under this
     * request between the read and the write — per that method's own Javadoc, the correct
     * response is a conflict, never a re-SELECT to build a nicer message.
     */
    private void requirePaymentAdvanced(int rows) {
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ขั้นตอนการรับชำระเงินถูกเปลี่ยนโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
    }

    /**
     * Turns a lost ticket-status compare-and-set race into a 409, mirroring
     * {@link #requirePaymentAdvanced}. {@code transitionStatus}'s 0 rowcount means a concurrent
     * writer moved {@code sales.ticket.status} out from under this request between the read that
     * chose {@code expected} and the write — per that method's Javadoc the correct response is a
     * conflict, never a re-SELECT to build a nicer message. (An UNDECLARED edge is a different
     * thing entirely and throws {@link IllegalStateException} inside the repository before any
     * SQL runs; it never reaches here.)
     */
    private void requireStatusAdvanced(int rows) {
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                "สถานะดีลถูกเปลี่ยนโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
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
            throw new ApiException(HttpStatus.CONFLICT, "ต้องออกใบแจ้งรับมัดจำก่อนจึงจะยืนยันรับชำระมัดจำได้");
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
        //
        // Rule 5 (payment-track state machine): null is no longer treated as "deposit bypassed"
        // here — null may only ever advance to CUSTOMER_CONFIRMED, so a bypass-policy deal must
        // have actually reached CUSTOMER_CONFIRMED (via confirmCustomer) before an IR can issue.
        // This is a deliberate behaviour change; see the branch report.
        boolean depositPolicyBypassesNotice = DepositPolicy.bypassesDepositNotice(s.depositPolicy())
            && "CUSTOMER_CONFIRMED".equals(s.paymentStatus());
        boolean depositReady = "DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus())
            || "DEPOSIT_PAID".equals(s.paymentStatus())
            || depositPolicyBypassesNotice;
        if (!TicketStatus.QUOTATION_ISSUED.equals(s.status()) || !depositReady) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ออกใบขอนำเข้า (IR) ได้เฉพาะเมื่อออกใบเสนอราคาแล้วและรับชำระมัดจำแล้ว (หรือได้รับการยกเว้นมัดจำ) เท่านั้น");
        }
        // Never restart an in-flight fulfillment track: re-issuing the IR would
        // downgrade IR_SENT/SHIPPING/GOODS_RECEIVED back to IR_ISSUED.
        if (s.fulfillmentStatus() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอนำเข้านี้ถูกออกไปแล้ว");
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
        // Deliberately NOT guarded by hasLivePurchaseOrders (unlike markShipping/markGoodsReceived
        // below): IR_SENT is a pre-shipment milestone the POs say nothing about yet —
        // TicketRepository#deriveImportStatus returns null while every live PO is still OPEN — so
        // a ticket-level IR_SENT can never contradict the PO rollup.
        if (!FulfilmentStatus.IR_ISSUED.equals(s.fulfillmentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ต้องออกใบขอนำเข้า (IR) ก่อนจึงจะทำเครื่องหมายว่าส่ง IR แล้วได้");
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
        // Refuse, don't delegate. A ticket-level "start shipping" click cannot say WHICH factory
        // shipped — container ref, ETD/ETA are per-factory PO detail that only the PO aggregate
        // (ProcurementService) legitimately owns, so "delegating" this click would mean fabricating
        // that detail. On a PO-tracked deal the PO rollup (applyPurchaseOrderRollup below) is the
        // source of truth for this axis; refusing is the only option that invents no data.
        // Unconditional on PO presence — not merely "when they disagree" — because a ticket-level
        // write that happens to already agree with the PO rollup would still have bypassed it.
        if (tickets.hasLivePurchaseOrders(ticketId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ดีลนี้ติดตามการนำเข้าด้วยใบสั่งซื้อโรงงาน — ต้องบันทึกการขนส่งที่ใบสั่งซื้อของแต่ละโรงงาน");
        }
        if (!FulfilmentStatus.IR_SENT.equals(s.fulfillmentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ต้องส่งใบขอนำเข้า (IR) ก่อนจึงจะทำเครื่องหมายว่าเริ่มจัดส่งได้");
        }
        applyShipping(s, actor);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto markGoodsReceived(long ticketId, UserPrincipal actor) {
        requireRole(actor, FULFILMENT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireActive(s);
        // Same refuse-not-delegate reasoning as markShipping above: a ticket-level "goods
        // received" click cannot say which factory's shipment arrived, and on a PO-tracked deal
        // the PO rollup is the source of truth for this axis, not this button.
        if (tickets.hasLivePurchaseOrders(ticketId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ดีลนี้ติดตามการนำเข้าด้วยใบสั่งซื้อโรงงาน — ต้องบันทึกรับสินค้าที่ใบสั่งซื้อของแต่ละโรงงาน");
        }
        if (!FulfilmentStatus.SHIPPING.equals(s.fulfillmentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ดีลต้องอยู่ในขั้นตอนจัดส่งก่อนจึงจะทำเครื่องหมายว่าได้รับสินค้าได้");
        }
        applyGoodsReceived(s, actor);
        return requireTicket(ticketId);
    }

    /**
     * The GOODS_RECEIVED side-effects, factored out of {@link #markGoodsReceived} so that method
     * and {@link #applyPurchaseOrderRollup} (the PO-tracked path, called from {@code
     * ProcurementService}) share exactly one implementation and can never drift apart on what
     * "goods received" means. Behaviour is byte-for-byte what {@code markGoodsReceived} always did
     * after its status guard — only the guard itself stayed behind in the caller.
     */
    private void applyGoodsReceived(TicketSummaryDto s, UserPrincipal actor) {
        tickets.updateFulfillmentStatus(s.id(), FulfilmentStatus.GOODS_RECEIVED);
        // Also advance payment track to AWAITING_FINAL_PAYMENT if deposit was paid
        if ("DEPOSIT_PAID".equals(s.paymentStatus())) {
            int rows = tickets.advancePaymentStatus(
                s.id(), s.depositPolicy(), s.paymentStatus(), PaymentTrack.AWAITING_FINAL_PAYMENT);
            requirePaymentAdvanced(rows);
            tickets.addEvent(s.id(), actor.id(), actor.name(),
                TicketEventKind.AWAITING_FINAL_PAYMENT, s.status(), s.status(), null);
        }
        tickets.addEvent(s.id(), actor.id(), actor.name(),
            TicketEventKind.GOODS_RECEIVED, s.status(), s.status(), null);
        // Goods are at the warehouse (S17) — advance to DELIVERY_SCHEDULING (S18)
        // so the "schedule delivery / collect balance" step is reached before
        // DELIVERED, instead of the pipeline jumping PROCUREMENT → DELIVERED.
        autoAdvanceStage(s, DealStage.DELIVERY_SCHEDULING, actor);
    }

    /**
     * The SHIPPING write + event, factored out of {@link #markShipping} for the same reason as
     * {@link #applyGoodsReceived} just above.
     */
    private void applyShipping(TicketSummaryDto s, UserPrincipal actor) {
        tickets.updateFulfillmentStatus(s.id(), FulfilmentStatus.SHIPPING);
        tickets.addEvent(s.id(), actor.id(), actor.name(),
            TicketEventKind.SHIPPING, s.status(), s.status(), null);
    }

    /**
     * Rolls this deal's live factory-PO statuses up into {@code fulfillment_status}. Called by
     * {@code ProcurementService} at the end of every PO mutation ({@code recordShippingDetail},
     * {@code recordGoodsReceived}, {@code cancel}) so a PO-tracked deal's ticket-level flag tracks
     * the PO aggregate automatically — the ticket-level setters above refuse for exactly this
     * class of deal, so nothing else advances this axis for it.
     *
     * <p><strong>Not a controller entry point.</strong> No {@link #requireRole} call here —
     * authorisation already happened inside {@code ProcurementService} ({@code RAW_PO_ROLES =
     * {import, ceo}}), and this method is reachable only from that service, never from any
     * controller. A future reader adding a controller endpoint onto this method directly must add
     * its own authorisation check first — do not mistake the absence of one here for "no check
     * needed".
     */
    @Transactional
    public void applyPurchaseOrderRollup(long ticketId, UserPrincipal actor) {
        TicketSummaryDto s = requireTicket(ticketId).summary();
        String target = tickets.deriveImportStatus(ticketId);
        if (target == null) {
            return;
        }
        // Delivery-axis firewall: once the ticket has left the import axis (a delivery has
        // started, or the deal was always a stock deal) a late PO mutation must never write here
        // again — this is what protects the FULLY_DELIVERED close gate (deliveryGateComplete
        // above) from being clobbered back down to GOODS_RECEIVED by a straggling PO.
        if (!FulfilmentStatus.isImportAxis(s.fulfillmentStatus())) {
            return;
        }
        // Monotonic: only ever advance, never downgrade. importRank is -1 for anything not on the
        // import axis, but that case is already excluded by the firewall above, so both ranks here
        // are real positions on IMPORT_SEQUENCE.
        if (FulfilmentStatus.importRank(target) <= FulfilmentStatus.importRank(s.fulfillmentStatus())) {
            return;
        }
        if (FulfilmentStatus.GOODS_RECEIVED.equals(target)) {
            applyGoodsReceived(s, actor);
        } else {
            applyShipping(s, actor);
        }
    }

    public List<DeliveryRecordDto> listDeliveries(long ticketId, UserPrincipal actor) {
        requireViewAccess(ticketId, actor);
        return tickets.findDeliveriesByTicket(ticketId);
    }

    /**
     * Declares which line quantities on this deal are covered from stock.
     *
     * <p><strong>NOTHING IS RESERVED.</strong> The name, {@link StockReservationRequest}, {@link
     * TicketEventKind#STOCK_RESERVED} and the {@code stock_note} column all read as inventory
     * reservation, and none of them is. This records a <em>sales declaration</em>: the rep (or
     * import, or the CEO) states how many of each line they believe can be supplied from stock.
     * There is no stock ledger, no on-hand quantity and no availability check anywhere in this
     * codebase — nothing is decremented (V54 says the same thing on the column itself) and nothing
     * validates the declared quantity against real availability. The sole constraint is the
     * V-migration CHECK {@code qty_from_stock >= 0 AND qty_from_stock <= qty}, i.e. "no more than
     * was ordered", which is arithmetic on this deal and says nothing about a warehouse.
     *
     * <p><strong>Why it exists at all:</strong> the number is a commission input. {@code
     * CommissionRepository#sumActiveStockActualReceived} computes the owning rep's STOCK_BONUS as
     * {@code SUM(actual_received × SUM(qty_from_stock)/SUM(qty))}, so this field is the {@code
     * stockShare} half of that formula and nothing else reads it as inventory.
     *
     * <p><strong>Inventory tracking is deliberately out of scope</strong> (owner ruling) — a future
     * reader must not "fix" this by building a stock ledger, and must not assume the declaration
     * has been corroborated against one. The dangerous version of the misreading is exactly that
     * assumption; it has already caused two wrong readings of this codebase. Nothing is renamed
     * because the method name is on the API contract ({@link TicketController}) and mirrored in
     * {@code frontend/src/api/mockApi.js} under a contract test.
     *
     * <p><strong>Stage floor (2026-08-13).</strong> Because nothing corroborates the claim, and
     * because full coverage reroutes the deal (below: {@code FROM_STOCK} plus a jump to {@code
     * DELIVERY_SCHEDULING}, which in turn blocks {@link #issueImportRequest}), a declaration is
     * refused below {@link DealStage#ORDER_RECEIVED} — see {@link #stockCoverageStageReached}.
     *
     * <p><strong>Deliberate authorisation change — owner ruling 2026-08-13, "Sales declares,
     * Import can correct."</strong> This was gated to {@link #FULFILMENT_ROLES} alone, so the one
     * person whose money depends on the number could not supply it: {@code
     * CommissionRepository#sumActiveStockActualReceived} computes the rep's STOCK_BONUS input as
     * {@code SUM(actual_received × SUM(qty_from_stock)/SUM(qty))}, i.e. entirely from what another
     * department typed in. The deal owner may now declare what they know about their own deal;
     * import and ceo keep the identical ability and can correct a rep's figure afterwards, since
     * they are the ones who know what is actually on the shelf.
     *
     * <p><strong>Only the gate moved.</strong> The commission formula is untouched — this changes
     * who may supply its input, never how it is computed. Everything below the gate is
     * deliberately identical whoever declares: the same per-line write, the same {@code
     * STOCK_RESERVED} event, the same {@code allCovered → FROM_STOCK → DELIVERY_SCHEDULING}
     * routing, the same {@link #requireActive} guard. One code path and one meaning for the
     * field: routing follows the facts, not the declarer.
     *
     * <p>Two gates, in the order {@link #requireViewAccess} uses. The coarse {@link
     * #STOCK_DECLARATION_ROLES} check runs BEFORE the ticket is loaded so that widening this
     * endpoint cannot turn it into an existence probe — a role that can never declare (hr,
     * employee, account, sales_manager) still gets 403 without a row being read, exactly as
     * before. {@link #canDeclareStockCoverage} then applies the per-row rule.
     */
    @Transactional
    public TicketDto reserveStock(long ticketId, StockReservationRequest request, UserPrincipal actor) {
        requireRole(actor, STOCK_DECLARATION_ROLES);
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        if (!canDeclareStockCoverage(s, actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        requireActive(s);
        if (!stockCoverageStageReached(s)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ประกาศสินค้าจากสต็อกได้หลังจากยืนยันคำสั่งซื้อของลูกค้าแล้วเท่านั้น (ตั้งแต่ขั้นตอน ORDER_RECEIVED)");
        }
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
            remaining,
            request == null ? null : request.recipientName());
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
        long deliveryId = tickets.insertDeliveryRecord(
            s.id(), source, actor.id(), request.note(), request.recipientName(), normalized);
        TicketDto updated = requireTicket(s.id());
        TicketSummaryDto updatedSummary = updated.summary();
        boolean fullyDelivered = updated.items().stream()
            .allMatch(item -> nullToZero(item.qtyDelivered()).compareTo(nullToZero(item.qty())) >= 0);
        String message = deliveryMessage(updated.items(), normalized);
        tickets.addEventWithDocument(s.id(), actor.id(), actor.name(),
            TicketEventKind.DELIVERY_RECORDED, updatedSummary.status(), updatedSummary.status(), message,
            RelatedDocumentType.DELIVERY_RECORD, deliveryId);
        if (fullyDelivered) {
            tickets.updateFulfillmentStatus(s.id(), FulfilmentStatus.FULLY_DELIVERED);
            tickets.addEventWithDocument(s.id(), actor.id(), actor.name(),
                TicketEventKind.DELIVERY_COMPLETED, updatedSummary.status(), updatedSummary.status(),
                completing ? "ส่งมอบครบจากปุ่ม completeDelivery" : message,
                RelatedDocumentType.DELIVERY_RECORD, deliveryId);
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
                "ต้องรับชำระมัดจำแล้วหรือรอชำระเงินงวดสุดท้าย (หรือดีลนี้ได้รับการยกเว้นมัดจำ) ก่อนจึงจะยืนยันรับชำระเงินครบถ้วนได้");
        }
        BigDecimal outstanding = payableAmount(ticket).subtract(nullToZero(tickets.sumPaid(ticketId)));
        if (outstanding.signum() <= 0) {
            if (!"FULLY_PAID".equals(s.paymentStatus())) {
                // Multi-hop walk: DEPOSIT_PAID -> AWAITING_FINAL_PAYMENT -> FULLY_PAID (REQUIRED)
                // or CUSTOMER_CONFIRMED -> AWAITING_FINAL_PAYMENT -> FULLY_PAID (bypass) — both
                // admitted by canConfirmFinalPaymentNow below, both walked in one call.
                int rows = tickets.advancePaymentStatus(
                    ticketId, s.depositPolicy(), s.paymentStatus(), PaymentTrack.FULLY_PAID);
                requirePaymentAdvanced(rows);
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับประเภทการรับชำระเงิน '" + request.kind() + "'");
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
        long receiptId;
        try {
            receiptId = tickets.insertPaymentReceipt(ticketId, kind, amount, actor.id(), request.receivedAt(),
                note, request.depositNoticeId(), receiptRef);
        } catch (DataIntegrityViolationException e) {
            if (receiptRef != null) {
                throw new ApiException(HttpStatus.CONFLICT, "เลขอ้างอิงรับชำระซ้ำ");
            }
            throw e;
        }
        tickets.addEventWithDocument(ticketId, actor.id(), actor.name(), TicketEventKind.PAYMENT_RECORDED,
            s.status(), s.status(),
            "kind=" + kind + ", amount=" + amount + ", paid=" + newPaid + ", payable=" + payable
                + (note != null ? " — " + note : ""),
            RelatedDocumentType.PAYMENT_RECEIPT, receiptId);
        reconcilePaymentStatus(ticketId, actor);
        return requireTicket(ticketId);
    }

    private void reconcilePaymentStatus(long ticketId, UserPrincipal actor) {
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        BigDecimal payable = payableAmount(ticket);
        BigDecimal paid = nullToZero(tickets.sumPaid(ticketId));
        if (payable.signum() > 0 && paid.compareTo(payable) >= 0) {
            // Rule 4/5 tightening: null may only advance to CUSTOMER_CONFIRMED. A payment
            // recorded (by account) before the customer was ever confirmed must not silently
            // promote payment_status straight to FULLY_PAID — it stays null until confirmCustomer
            // eventually runs. Deliberate behaviour change; see the branch report.
            if (s.paymentStatus() != null && !"FULLY_PAID".equals(s.paymentStatus())) {
                // The walk PERSISTS every hop, so a REQUIRED deal jumping from DEPOSIT_PAID goes
                // through AWAITING_FINAL_PAYMENT on the way. The column moves on immediately, so
                // without an event nothing durable records that the state was ever reached —
                // emit one per intermediate hop so the deal's payment history shows the passage
                // rather than appearing to leap. The final hop keeps its own FULLY_PAID event
                // below, so intermediates are every hop except the last.
                List<String> hops = PaymentTrack.stepsBetween(
                    s.depositPolicy(), s.paymentStatus(), PaymentTrack.FULLY_PAID);
                int rows = tickets.advancePaymentStatus(
                    ticketId, s.depositPolicy(), s.paymentStatus(), PaymentTrack.FULLY_PAID);
                requirePaymentAdvanced(rows);
                for (String hop : hops.subList(0, Math.max(0, hops.size() - 1))) {
                    if (PaymentTrack.AWAITING_FINAL_PAYMENT.equals(hop)) {
                        tickets.addEvent(ticketId, actor.id(), actor.name(),
                            TicketEventKind.AWAITING_FINAL_PAYMENT, s.status(), s.status(), null);
                    }
                }
                tickets.addEvent(ticketId, actor.id(), actor.name(),
                    TicketEventKind.FULLY_PAID, s.status(), s.status(), null);
                maybeAdvanceClosedPaid(s, true, actor);
            }
            return;
        }
        if (paid.signum() <= 0 || "FULLY_PAID".equals(s.paymentStatus())) {
            return;
        }
        // Rule 5: null is no longer a valid starting point for anything but CUSTOMER_CONFIRMED.
        // A bare drop of the old "s.paymentStatus() == null ||" leading clause would NOT be
        // enough on its own: the bypass-policy clause below is unconditional on paymentStatus, so
        // it would silently re-admit a null paymentStatus for any bypass-policy deal (an account
        // payment recorded before confirmCustomer ever ran) straight into an illegal
        // null -> AWAITING_FINAL_PAYMENT call below. Guarded explicitly instead.
        boolean eligibleForDepositAdvance =
            s.paymentStatus() != null
                && ("CUSTOMER_CONFIRMED".equals(s.paymentStatus())
                    || "DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus())
                    || DepositPolicy.bypassesDepositNotice(s.depositPolicy()));
        // The idempotency guard MUST compare against the policy-resolved target, not the
        // DEPOSIT_PAID literal. On a bypass policy the target is AWAITING_FINAL_PAYMENT, so a
        // literal comparison never matches and a SECOND partial payment re-enters this block,
        // asking PaymentTrack for an AWAITING_FINAL_PAYMENT -> AWAITING_FINAL_PAYMENT self-loop
        // it correctly refuses -> IllegalStateException -> 500, and because recordPayment is
        // @Transactional the receipt insert rolls back too, so the instalment cannot be recorded
        // at all. Found by adversarial review; the pre-existing test records only ONE partial
        // payment, which is why the suite was green.
        String depositTarget = DepositPolicy.bypassesDepositNotice(s.depositPolicy())
            ? PaymentTrack.AWAITING_FINAL_PAYMENT
            : PaymentTrack.DEPOSIT_PAID;
        if (eligibleForDepositAdvance && !depositTarget.equals(s.paymentStatus())) {
            // ⚠ Semantic change (site 6): the bypass path has no DEPOSIT_PAID state at all — a
            // bypass deal's first payment now targets AWAITING_FINAL_PAYMENT, the corresponding
            // state on that path, instead of the DEPOSIT_PAID literal PaymentTrack now refuses
            // for a bypass policy. This makes a partially-paid bypass deal visible to the
            // account role's list scope (ACCOUNT_PENDING_PAYMENT_STATUSES contains
            // AWAITING_FINAL_PAYMENT, not DEPOSIT_PAID) where it previously was not — see
            // PaymentTrackIntegrationTest for the real-DB proof. Deliberate; see the branch report.
            //
            // Event emission is UNCHANGED from before this branch (per the branch plan: "event
            // emission stays exactly as it is today at all 7 sites") — TicketEventKind.DEPOSIT_PAID
            // fires regardless of which literal the walk actually targets, since the business
            // event is the same ("the deposit-equivalent first payment arrived"); only the
            // persisted payment_status value differs by policy.
            int rows = tickets.advancePaymentStatus(
                ticketId, s.depositPolicy(), s.paymentStatus(), depositTarget);
            requirePaymentAdvanced(rows);
            tickets.addEvent(ticketId, actor.id(), actor.name(),
                TicketEventKind.DEPOSIT_PAID, s.status(), s.status(), null);
            autoAdvanceStage(s, DealStage.DEPOSIT_RECEIVED, actor);
            // Site 7: only reachable when site 6 targeted DEPOSIT_PAID (REQUIRED policy) — a
            // bypass deal already landed on AWAITING_FINAL_PAYMENT above, and PaymentTrack has no
            // AWAITING_FINAL_PAYMENT -> AWAITING_FINAL_PAYMENT self-loop, so this block must not
            // run again for it. "expected" below is depositTarget — the value JUST written above
            // in this same method — never the stale s.paymentStatus() read before it (a stale
            // expected would make the compare-and-set return 0 and wrongly 409 a legitimate flow).
            if (PaymentTrack.DEPOSIT_PAID.equals(depositTarget)
                    && "GOODS_RECEIVED".equals(s.fulfillmentStatus())) {
                int rows2 = tickets.advancePaymentStatus(
                    ticketId, s.depositPolicy(), depositTarget, PaymentTrack.AWAITING_FINAL_PAYMENT);
                requirePaymentAdvanced(rows2);
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
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการนี้ในดีล");
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
        // Rule 4/5 (payment-track state machine): null may only advance to CUSTOMER_CONFIRMED —
        // it no longer qualifies as "deposit bypassed" here, even on a bypass policy. A bypass
        // deal must have confirmCustomer's CUSTOMER_CONFIRMED write behind it before final
        // payment can walk it on to AWAITING_FINAL_PAYMENT -> FULLY_PAID. Deliberate behaviour
        // change; see the branch report.
        boolean depositBypassed = DepositPolicy.bypassesDepositNotice(s.depositPolicy())
            && "CUSTOMER_CONFIRMED".equals(s.paymentStatus());
        return "AWAITING_FINAL_PAYMENT".equals(s.paymentStatus())
            || "DEPOSIT_PAID".equals(s.paymentStatus())
            || depositBypassed;
    }

    // ── Deal pipeline (V50): 14-stage journey on the ticket itself ──────────
    // NOTE on sales_manager: handoff 58 made it read+comment-only on the ticket's
    // OPERATIONAL actions, and that stands. The pipeline stage/lost/reopen fields
    // are the deliberate, user-approved exception — following up the team's deals
    // is exactly this role's job. Never extend it beyond these three methods.

    /**
     * Stages whose manual fallback belongs to the deal owner / sales_manager / ceo.
     *
     * <p>{@link DealStage#QUOTE_OWNER} joins the set (V143) for the same reason
     * {@link DealStage#QUOTE_DESIGN_SIDE} is in it: quoting the owner is a sales action, so when
     * the automatic advance at quotation-issue time did not happen (a quotation raised outside
     * the PCR chain, or a stage corrected after the fact) the fallback must belong to the same
     * three principals — not to account or import, whose money/import stages are separate sets
     * below. This IS a permission surface: {@link #requireStageWriteAccess} keys off exactly
     * these three sets, and membership here is what makes the difference between 403 and a write.
     */
    private static final Set<String> SALES_TARGET_STAGES = Set.of(
        DealStage.LEAD_APPROACH, DealStage.PRESENTATION, DealStage.SPEC_APPROVED,
        DealStage.QUOTE_DESIGN_SIDE, DealStage.QUOTE_OWNER, DealStage.OWNER_SIGNOFF,
        DealStage.AWAITING_BUYER, DealStage.QUOTE_BUYER, DealStage.NEGOTIATION,
        DealStage.ORDER_RECEIVED, DealStage.DELIVERY_SCHEDULING, DealStage.DELIVERED);
    /** Money stages — manual fallback for account/ceo (normally auto from payment track). */
    private static final Set<String> ACCOUNT_TARGET_STAGES = Set.of(
        DealStage.DEPOSIT_RECEIVED, DealStage.CLOSED_PAID);
    /** Import stage — manual fallback for import/ceo (normally auto from the IR). */
    private static final Set<String> IMPORT_TARGET_STAGES = Set.of(
        DealStage.PROCUREMENT);

    @Transactional
    public TicketDto updateStage(long ticketId, String targetStage, String note, UserPrincipal actor) {
        if (!DealStage.isValid(targetStage)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับสถานะขั้นตอนการขาย '" + targetStage + "'");
        }
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireStageWriteAccess(s, targetStage, actor);
        // Keyed on the lifecycle, not on lost_reason: since V58 the reason SURVIVES
        // a reopen, so a live reopened deal still carries one. Checked before
        // requireActive so a lost deal gets this specific message.
        if (DealLifecycle.CLOSED_LOST.equals(s.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ดีลถูกทำเครื่องหมายเสียงานแล้ว — เปิดดีลใหม่ก่อนแก้ไขสถานะ");
        }
        requireActive(s);
        if (targetStage.equals(s.salesStage())) {
            throw new ApiException(HttpStatus.CONFLICT, "ดีลนี้อยู่ในขั้นตอน " + targetStage + " อยู่แล้ว");
        }
        // The fact gate. Deliberately BEFORE the note and tracking-field rules below: those two ask
        // "has the rep done the paperwork?", and a deal whose facts do not support the target stage
        // must be refused whatever the paperwork says. Ordering it after them would have produced
        // the misleading pair "write a note" -> "…now the fact is missing", and would have let a
        // reader mistake the note rule for the thing doing the work.
        requireStageFactsHold(s, targetStage);
        // ONE decision, two messages. This used to be two independent rules — a backward check
        // with an isRoutineBackwardMove exception bolted on, and a raw `indexOf(target) -
        // indexOf(current) > 1` skip check. The second one demanded a written justification for
        // three of the business's four normal routes (an owner buying direct skips S3/S4/S7/S8; a
        // contractor arriving with a BOQ starts at S8; an in-stock deal skips PROCUREMENT), i.e.
        // friction on the default path — the same defect isRoutineBackwardMove had already been
        // patched by hand to fix for exactly one pair. DealStage.requiresJustification now owns
        // both directions, so there is no second mechanism to keep in step.
        boolean backward = DealStage.indexOf(targetStage) < DealStage.indexOf(s.salesStage());
        if (DealStage.requiresJustification(s.salesStage(), targetStage)
                && (note == null || note.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                backward ? "การย้อนสถานะกลับต้องระบุเหตุผล" : "การข้ามขั้นตอนต้องระบุเหตุผล");
        }
        // Slice B1 "kill the weekly report" gate (handoff 103): real forward progress on a MANUAL
        // updateStage — genuinely advancing (target index strictly greater than current) — is
        // blocked unless the rep has kept the deal's tracking fields current. Deliberately keyed
        // off a strictly-greater index and nothing else: `backward` above is now purely a message
        // selector (it no longer carries the isRoutineBackwardMove exception, which moved inside
        // DealStage.requiresJustification), so this gate must not be expressed as "!backward" —
        // that would flip the routine QUOTE_DESIGN_SIDE -> SPEC_APPROVED move into the gate. It is
        // index-wise backward and therefore not forward progress, which is exactly what excludes
        // it here. autoAdvanceStage (the system-driven path) is a separate method entirely and
        // never runs through here, so it is never gated by this block.
        boolean forward = DealStage.indexOf(targetStage) > DealStage.indexOf(s.salesStage());
        if (forward) {
            boolean hasFollowUp = s.nextFollowUpAt() != null;
            boolean hasRecentActivity = tickets.hasActivitySinceLastStageChange(ticketId);
            if (!hasFollowUp || !hasRecentActivity) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "เลื่อนสถานะไม่ได้: ต้องระบุวันติดตามครั้งถัดไป และบันทึกกิจกรรมอย่างน้อย 1 รายการหลังเปลี่ยนสถานะล่าสุด");
            }
        }
        tickets.updateSalesStage(ticketId, targetStage);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.STAGE_CHANGED, s.salesStage(), targetStage, blankToNull(note));
        return requireTicket(ticketId);
    }

    /**
     * The four back-half stages a MANUAL {@link #updateStage} may not claim unless the deal's own
     * recorded fact already holds.
     *
     * <p><strong>Why a fact gate and not a transition table.</strong> {@code updateStage} validated
     * membership only ({@link DealStage#isValid}), so any stage could reach any other — {@code
     * LEAD_APPROACH -> CLOSED_PAID} in one call cost a note, a follow-up date and one logged
     * activity, and nothing about the deal's actual state was consulted. A transition table cannot
     * fix that: the business's real routes branch heavily (the owner buys direct and S3/S4/S7/S8
     * never happen; a contractor arrives with a BOQ and the deal OPENS at S8; everything ships from
     * stock and PROCUREMENT is skipped), so a table faithful to them has to permit almost every
     * forward edge in the front half, including the long jumps. Gating on the fact behind the stage
     * does what the table cannot, and stays correct when the next route is discovered.
     *
     * <p><strong>Exactly four stages, and only these four.</strong> Each already auto-advances FROM
     * its fact, so the fact is recorded state, not a new concept:
     *
     * <ul>
     *   <li>{@link DealStage#ORDER_RECEIVED} — {@link #confirmCustomer} writes {@code
     *       CUSTOMER_CONFIRMED}, see {@link #customerOrderVerified};
     *   <li>{@link DealStage#DEPOSIT_RECEIVED} — {@code reconcilePaymentStatus} advances the
     *       payment track when money first lands, see {@link #depositReceived};
     *   <li>{@link DealStage#DELIVERED} — {@code recordDeliveryInternal} advances on
     *       {@code FULLY_DELIVERED}, i.e. {@link #deliveryGateComplete};
     *   <li>{@link DealStage#CLOSED_PAID} — the payment track reaches {@code FULLY_PAID}, the same
     *       test {@link #requireClosePrerequisites} makes.
     * </ul>
     *
     * <p>{@link DealStage#NEGOTIATION}, {@link DealStage#PROCUREMENT} and {@link
     * DealStage#DELIVERY_SCHEDULING} stay ungated on purpose: they are operational stages where the
     * manual fallback is genuinely useful and a wrong value misreports nothing financial. The four
     * above are the ones where a wrong stage misstates revenue or fulfilment.
     *
     * <p><strong>{@link #autoAdvanceStage} is NOT routed through here</strong>, and must never be:
     * it is the path that fires <em>because</em> the fact just became true, so gating it would be
     * circular and would break every automatic advance. Only the manual path is affected.
     *
     * <p><strong>Keyed on the TARGET only, in both directions.</strong> Landing on {@code DELIVERED}
     * with nothing delivered misstates fulfilment whether the deal arrived from below or is being
     * corrected from above, so this does not consult {@code from}. A deal that needs walking back
     * out of a wrong stage can still be moved to any of the eleven ungated ones.
     *
     * <p><strong>Accepted cost:</strong> the manual fallback for these four stages is gone. A deal
     * whose automation genuinely did not fire can no longer be nudged into them by hand — the
     * underlying fact has to be recorded first. That is the intended trade, and there is
     * deliberately no CEO break-glass override: it was not asked for, and it would be a new
     * capability rather than a repair.
     */
    private void requireStageFactsHold(TicketSummaryDto s, String targetStage) {
        if (DealStage.ORDER_RECEIVED.equals(targetStage) && !customerOrderVerified(s)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "เลื่อนไปขั้นตอน ORDER_RECEIVED ไม่ได้: ยังไม่ได้ยืนยันคำสั่งซื้อของลูกค้า");
        }
        if (DealStage.DEPOSIT_RECEIVED.equals(targetStage) && !depositReceived(s)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "เลื่อนไปขั้นตอน DEPOSIT_RECEIVED ไม่ได้: ยังไม่ได้รับชำระมัดจำ");
        }
        if (DealStage.DELIVERED.equals(targetStage) && !deliveryGateComplete(s)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "เลื่อนไปขั้นตอน DELIVERED ไม่ได้: ยังส่งมอบสินค้าไม่ครบ");
        }
        if (DealStage.CLOSED_PAID.equals(targetStage)
                && !PaymentTrack.FULLY_PAID.equals(s.paymentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "เลื่อนไปขั้นตอน CLOSED_PAID ไม่ได้: ยังรับชำระเงินไม่ครบ");
        }
    }

    /**
     * The fact behind {@link DealStage#ORDER_RECEIVED} (S10): the customer's order is verified.
     *
     * <p>There was no existing predicate for this — {@link #canConfirmCustomer} asks the opposite
     * question ("may this deal still BE confirmed?") — so it is derived from the write the
     * auto-advance itself keys off: {@link #confirmCustomer} is the one method that both advances
     * the deal to ORDER_RECEIVED and moves {@code payment_status} to {@code CUSTOMER_CONFIRMED}.
     *
     * <p>A non-null payment track is therefore the fact, not merely a correlate of it:
     * {@code payment_status} starts NULL (V6/V39/V44) and {@link PaymentTrack#canTransition}
     * admits exactly one edge out of null — to {@code CUSTOMER_CONFIRMED} — on either policy path,
     * a rule {@code reconcilePaymentStatus} re-states explicitly so a payment recorded before the
     * customer was confirmed cannot promote the column either. Deliberately expressed through
     * {@link PaymentTrack#isValid} rather than a bare {@code != null} so that only the five real
     * states count.
     */
    private static boolean customerOrderVerified(TicketSummaryDto s) {
        return PaymentTrack.isValid(s.paymentStatus());
    }

    /**
     * The payment-track states that mean the deposit (or, on a bypass policy, the first money
     * against the deal) has actually been received — the fact behind {@link
     * DealStage#DEPOSIT_RECEIVED} (S11).
     *
     * <p>Read off the auto-advance site rather than invented: {@code reconcilePaymentStatus}
     * advances the stage immediately after writing {@code depositTarget}, which is {@code
     * DEPOSIT_PAID} on the REQUIRED path and {@code AWAITING_FINAL_PAYMENT} on a bypass policy
     * (that path has no DEPOSIT_PAID state at all). {@code FULLY_PAID} is included because it is
     * strictly later on both paths — a deal paid in full has necessarily passed this point.
     *
     * <p>{@code DEPOSIT_NOTICE_ISSUED} is deliberately absent: issuing the notice is a document,
     * not a receipt, and {@code confirmDepositPaid} exists precisely because the money is a
     * separate event.
     */
    private static final Set<String> DEPOSIT_RECEIVED_STATES = Set.of(
        PaymentTrack.DEPOSIT_PAID, PaymentTrack.AWAITING_FINAL_PAYMENT, PaymentTrack.FULLY_PAID);

    /**
     * See {@link #DEPOSIT_RECEIVED_STATES}. Null-checked before the lookup: {@code Set.of(...)}
     * is an immutable set whose {@code contains(null)} throws NPE rather than returning false —
     * the same trap {@link DealStage#requiresJustification} documents for {@code List.of().indexOf}.
     */
    private static boolean depositReceived(TicketSummaryDto s) {
        return s.paymentStatus() != null && DEPOSIT_RECEIVED_STATES.contains(s.paymentStatus());
    }

    // ── Deal tracking + activity (V83, Slice B1 "kill the weekly report" — handoff 103) ──────

    /**
     * Log a follow-up. Deliberately NOT gated on {@link #requireActive} — a rep can still record
     * why a deal went quiet (or what happened before it was lost) on a non-ACTIVE deal, and the
     * one place this matters for enforcement ({@link #updateStage}'s gate) only ever runs on an
     * ACTIVE deal anyway (see {@link #requireActive} there).
     */
    @Transactional
    public DealActivityDto addActivity(long ticketId, DealActivityRequest request, UserPrincipal actor) {
        if (!DealActivityKind.isValid(request.kind())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับประเภทกิจกรรม '" + request.kind() + "'");
        }
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireDealOwnership(s, actor);
        long id = tickets.insertDealActivity(ticketId, request.activityDate(), request.kind(),
            blankToNull(request.note()), actor.id());
        return tickets.findActivitiesByTicket(ticketId).stream()
            .filter(a -> a.id() == id)
            .findFirst()
            .orElseThrow();
    }

    public List<DealActivityDto> listActivities(long ticketId, UserPrincipal actor) {
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireDealOwnership(s, actor);
        return tickets.findActivitiesByTicket(ticketId);
    }

    /** Sets the rep-facing tracking fields (win% override, counterparty names, next follow-up). */
    @Transactional
    public TicketDto updateTracking(long ticketId, TrackingUpdateRequest request, UserPrincipal actor) {
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireDealOwnership(s, actor);
        requireActive(s);
        tickets.updateTracking(ticketId, request.winProbability(), blankToNull(request.designerName()),
            blankToNull(request.ownerName()), blankToNull(request.buyerName()), request.nextFollowUpAt());
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.POLICY_CHANGED, s.salesStage(), s.salesStage(), "tracking fields updated");
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto markLost(long ticketId, String reason, String note, UserPrincipal actor) {
        if (!DealLostReason.isValid(reason)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับเหตุผลการเสียงาน '" + reason + "'");
        }
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireDealOwnership(s, actor);
        requireActive(s);
        // Lifecycle, not lost_reason — a reopened deal is ACTIVE and still carries
        // the reason it was lost for last time; it must be losable again.
        if (DealLifecycle.CLOSED_LOST.equals(s.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "ดีลนี้ถูกทำเครื่องหมายเสียงานไปแล้ว");
        }
        tickets.markDealLost(ticketId, reason);
        // Stage untouched by design: reopening resumes exactly where the deal was.
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.MARKED_LOST, s.salesStage(), s.salesStage(),
            "เสียงาน (" + reason + ")" + (note != null && !note.isBlank() ? " — " + note.trim() : ""));
        // Terminal deal state: a lost deal can never receive Import's pricing, so
        // any pricing request still open on it (DRAFT/SUBMITTED/IMPORT_REVIEWING/
        // MORE_INFO_REQUIRED) is cancelled here too rather than stranded in a queue
        // forever. See PricingRequestService.cancelOpenForTicket's Javadoc for why
        // this has no role check of its own. placeOnHold/markDormant deliberately do
        // NOT do this — see those methods.
        CancelOpenForTicketResult cancelResult = pricingRequests.cancelOpenForTicket(ticketId, reason, actor);
        if (cancelResult.hasAbandoned()) {
            // cancelOpenForTicket already logged the per-row detail; this ties the
            // abandonment back to the deal action that triggered it, for whoever is
            // grepping logs after the fact. The deal's own lost-marking above still
            // committed — an abandoned pricing request must never roll that back.
            log.warn("markLost: ticket {} left {} pricing request(s) still open after the cascade: {}",
                ticketId, cancelResult.abandonedCount(), cancelResult.abandonedIds());
        }
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto reopenDeal(long ticketId, String note, UserPrincipal actor) {
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireDealOwnership(s, actor);
        if (!DealLifecycle.CLOSED_LOST.equals(s.lifecycle()) || s.lostReason() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ดีลนี้ยังไม่ได้ถูกทำเครื่องหมายเสียงาน");
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
    /**
     * Step 4 (Customer Quotation Generation and Issuance): the EXACT stage-advance
     * {@link #generateQuotation} already performs for a recipient-scoped quotation, exposed as a
     * public entry point so {@code th.co.glr.hr.customerquotation.CustomerQuotationService} can
     * reuse it at issue time instead of inventing a second stage-transition path. Delegates to
     * the same private {@link #autoAdvanceStage}, so both flows share one implementation and can
     * never drift apart on which recipient maps to which stage.
     */
    @Transactional
    public void advanceStageForCustomerQuotationIssue(long ticketId, String recipientType, UserPrincipal actor) {
        TicketSummaryDto s = requireTicket(ticketId).summary();
        String stage = stageForQuotationRecipient(recipientType);
        if (stage != null) {
            autoAdvanceStage(s, stage, actor);
        }
    }

    /**
     * The one recipient → {@link DealStage} mapping, shared by {@link #generateQuotation} and
     * {@link #advanceStageForCustomerQuotationIssue}. Those two used to carry a hand-copied
     * {@code if/else if} each — the second one's Javadoc even claimed they "share one
     * implementation and can never drift apart", which was not true of duplicated literals.
     *
     * <p>V143 splits the recipients that used to collapse together: {@code DESIGNER -> S4},
     * {@code OWNER -> S5}, {@code BUYER -> S8}. Returns {@code null} for {@code UNSPECIFIED} and
     * for anything unrecognised — the same "advance nothing" behaviour the old {@code else if}
     * chain produced by falling off the end.
     */
    private static String stageForQuotationRecipient(String recipientType) {
        if (QuotationRecipient.DESIGNER.equals(recipientType)) {
            return DealStage.QUOTE_DESIGN_SIDE;
        }
        if (QuotationRecipient.OWNER.equals(recipientType)) {
            return DealStage.QUOTE_OWNER;
        }
        if (QuotationRecipient.BUYER.equals(recipientType)) {
            return DealStage.QUOTE_BUYER;
        }
        return null;
    }

    private void autoAdvanceStage(TicketSummaryDto s, String targetStage, UserPrincipal actor) {
        // ACTIVE is the whole test. The old `lostReason != null` clause would now
        // silently disable auto-advance on every reopened deal, since V58 keeps the
        // reason after a reopen.
        if (!DealLifecycle.ACTIVE.equals(s.lifecycle())) {
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
        // Deliberately does NOT call pricingRequests.cancelOpenForTicket, unlike
        // markLost/cancel. ON_HOLD is temporary — resume() brings the deal straight
        // back to ACTIVE — so cancelling in-progress pricing work here would destroy
        // it for no reason. The default queue already hides these requests while the
        // deal is not ACTIVE (PricingRequestRepository.findSummaries'
        // activeDealsOnly); resume() un-hides them with their status intact. Do not
        // "fix" this into symmetry with the terminal-state methods.
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
        // Same deliberate asymmetry as placeOnHold: DORMANT is temporary (resume()
        // returns it to ACTIVE), so no pricingRequests.cancelOpenForTicket call here
        // either. Do not "fix" this into symmetry with markLost/cancel.
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับเงื่อนไขการประมูล '" + value + "'");
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
        // UNSPECIFIED is valid as STORED but never as INPUT: once a channel has been stated it must
        // not be possible to un-state it. Same guard shape as generateQuotation's
        // QuotationRecipient.UNSPECIFIED check above — see EntryChannel's Javadoc and V144.
        if (!EntryChannel.isValid(value) || EntryChannel.UNSPECIFIED.equals(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับช่องทางรับงาน '" + value + "'");
        }
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireDealOwnership(s, actor);
        requireActive(s);
        // "Changing a STATED channel needs a reason." Both DESIGNER_LED and UNSPECIFIED count as
        // unstated here and neither requires a note: UNSPECIFIED is the V144 default, and
        // DESIGNER_LED is the pre-V144 default that was never backfilled (V144's data cutoff), so
        // an untouched deal reads one or the other purely by age. Dropping UNSPECIFIED from this
        // list would make the FIRST statement of a channel on every new deal demand a reason.
        boolean changingExistingNonDefault = s.entryChannel() != null
            && !EntryChannel.DESIGNER_LED.equals(s.entryChannel())
            && !EntryChannel.UNSPECIFIED.equals(s.entryChannel())
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับนโยบายยกเว้นมัดจำ '" + policy + "'");
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุเหตุผลนโยบายมัดจำ");
        }
        requireRole(actor, ACCOUNT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        requireActive(s);
        // Rule 4 (payment-track state machine): once a deposit invoice has actually been issued
        // (or paid, or further), waiving the deposit policy after the fact is no longer a policy
        // change a rep/account can make retroactively — the customer already has a real document
        // in hand quoting a deposit; undoing that is a credit note, not a policy flip. Only a
        // not-yet-started payment track (null) or one still at CUSTOMER_CONFIRMED may waive.
        if (s.paymentStatus() != null && !PaymentTrack.CUSTOMER_CONFIRMED.equals(s.paymentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ยกเลิกนโยบายมัดจำไม่ได้: มีการออกใบแจ้งรับมัดจำแล้ว — การแก้ไขต้องออกเป็นใบลดหนี้ ไม่ใช่การเปลี่ยนนโยบาย");
        }
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
        throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
    }

    /** Lost/reopen belong to the sales side: deal owner, sales_manager, or ceo. */
    private void requireDealOwnership(TicketSummaryDto s, UserPrincipal actor) {
        String role = actor.role();
        boolean isOwner = SALES_ROLES.contains(role) && s.createdById() == actor.id();
        if (!isOwner && !"sales_manager".equals(role) && !"ceo".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private void requireQuotationWriteAccess(TicketSummaryDto s, UserPrincipal actor) {
        if (!canManageQuotation(s, actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * Cancel a deal, recording why the opportunity went away.
     *
     * The reason is mandatory, matching {@link #markLost}: an optional one is
     * skipped in practice and the gap it was added to close stays open.
     *
     * Ownership remains the only gate — cancel deliberately has no requireRole,
     * as before. Tickets are created by sales, so the owner is a sales rep; this
     * is noted rather than changed because tightening it is an authz decision,
     * not a side effect of adding a reason column.
     */
    @Transactional
    public TicketDto cancel(long ticketId, String reason, String note, UserPrincipal actor) {
        if (!DealCancelReason.isValid(reason)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับเหตุผลการยกเลิก '" + reason + "'");
        }
        TicketDto ticket = requireTicket(ticketId);
        String currentStatus = ticket.summary().status();
        if (TicketStatus.CLOSED.equals(currentStatus) || TicketStatus.CANCELLED.equals(currentStatus)) {
            throw new ApiException(HttpStatus.CONFLICT, "ไม่สามารถยกเลิกดีลที่ปิดหรือถูกยกเลิกไปแล้วได้");
        }
        if (ticket.summary().createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        tickets.cancelDeal(ticketId, reason);
        String message = blankToNull(note) == null
            ? "ยกเลิกดีล (" + reason + ")"
            : "ยกเลิกดีล (" + reason + ") — " + note.trim();
        // The guard above already excluded the two terminal statuses, which are the only two
        // TicketStatus.ALLOWED does not let reach CANCELLED.
        requireStatusAdvanced(
            tickets.transitionStatus(ticketId, currentStatus, TicketStatus.CANCELLED));
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.CANCELLED, currentStatus, TicketStatus.CANCELLED, message);
        tickets.updateLifecycle(ticketId, DealLifecycle.CANCELLED);
        // Terminal deal state: same cascade as markLost above — a cancelled deal
        // can never receive Import's pricing, so any pricing request still open on
        // it is cancelled too.
        CancelOpenForTicketResult cancelResult = pricingRequests.cancelOpenForTicket(ticketId, reason, actor);
        if (cancelResult.hasAbandoned()) {
            log.warn("cancel: ticket {} left {} pricing request(s) still open after the cascade: {}",
                ticketId, cancelResult.abandonedCount(), cancelResult.abandonedIds());
        }
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
                prior != null ? prior.manualOverrideReason() : null,
                // Pricing fields above are guarded (request can never overwrite them — see this
                // method's own comment above). The catalog link is a descriptive field, not a
                // pricing one, so — same as brand/model/etc. — the request wins outright: the
                // frontend already carries the prior link forward (or clears it when the user
                // hand-edits a descriptive field, see TicketCreateModal.jsx's updateItem), so
                // this stays a pure passthrough with no merge logic of its own.
                BigDecimal.ZERO, BigDecimal.ZERO, null,
                r.catalogPriceId(), r.catalogProductCode()
            ));
        }
        return merged;
    }

    @Transactional
    public TicketDto comment(long ticketId, CommentRequest request, UserPrincipal actor) {
        // Same access rule as GET /tickets/{id} — commenting returns the full ticket,
        // so it must not be a side door around the read scoping (nor, per Phase B,
        // around the import quotation projection — the ticket is re-fetched below to
        // pick up the new event, so it must be re-projected too).
        requireViewAccess(ticketId, actor);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.COMMENTED, null, null, request.message());
        return projectForRole(requireTicket(ticketId), actor.role());
    }

    /**
     * Deprecated: recalculated legacy {@code ticket_item} prices for the submit → pickup →
     * propose-price → approve loop. CEO price computation now happens via
     * {@link th.co.glr.hr.pricingcosting.PricingCostingService} /
     * {@link th.co.glr.hr.pricingdecision.PricingDecisionService}.
     * Reachable only for legacy tickets stuck at {@code price_proposed}; no controller route
     * exposes this anymore.
     */
    @Deprecated
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

    /**
     * Deprecated: manual price override for a legacy {@code ticket_item} row. Superseded by
     * editing sale price on the PricingDecision aggregate — see
     * {@link th.co.glr.hr.pricingdecision.PricingDecisionService#update}. Reachable only for
     * legacy tickets stuck at {@code price_proposed}; no controller route exposes this
     * anymore.
     */
    @Deprecated
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
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการนี้ในดีล");
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
            // Slice S1 "engine collapse": addQuotationActions (MARK_QUOTATION_SENT/
            // ACCEPTED/REJECTED) was removed — those verbs pointed at retired
            // /{id}/quotations/{quotationId}/{sent,accepted,rejected} routes; see
            // markQuotationSent/Accepted/Rejected's own @Deprecated Javadoc for the
            // CustomerQuotationService replacement.
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
        // Slice S1 "engine collapse": PICKUP/PROPOSE_PRICE/APPROVE/REJECT/CALCULATE_PRICES/
        // OVERRIDE_ITEM_PRICE/GENERATE_QUOTATION were removed from here — every one of them
        // pointed at a route TicketController no longer exposes (see the corresponding
        // TicketService method's own @Deprecated Javadoc for the PCR/PricingDecision/
        // CustomerQuotationService replacement). Advertising a dead action to a client that
        // would immediately 404 on click is worse than not offering it — same reasoning as
        // the pre-existing "actions_neverOffersSubmit" guarantee for SUBMIT. Only the three
        // stranded pre-redesign tickets (submitted/in_review/price_proposed) could ever have
        // surfaced these anyway.
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
        if (canConfirmClose(s, actor)) {
            actions.add(new TicketActionDto("CONFIRM_CLOSE", "operational", "ยืนยันพร้อมปิดงาน"));
        }
        if (canRevokeCloseConfirmation(s, actor)) {
            actions.add(new TicketActionDto("REVOKE_CLOSE_CONFIRM", "operational", "ยกเลิกการยืนยันปิดงาน"));
        }
        if (canVerifyClose(s, actor)) {
            actions.add(new TicketActionDto("VERIFY_CLOSE", "operational", "ตรวจสอบและปิดงาน"));
        }
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

    /**
     * Mirrors {@link #issueImportRequest}'s own deposit-readiness check EXACTLY (down to the
     * rule-5 null tightening) — this predicate exists only to decide whether to advertise
     * ISSUE_IMPORT_REQUEST in {@link #actions}. Letting the two drift would advertise an action
     * that immediately 409s on click, which is worse than not offering it at all (same "never
     * offer a dead action" discipline {@link #addOperationalActions} already documents for the
     * removed legacy actions).
     */
    private boolean canIssueImportRequest(TicketSummaryDto s) {
        boolean depositPolicyBypassesNotice = DepositPolicy.bypassesDepositNotice(s.depositPolicy())
            && "CUSTOMER_CONFIRMED".equals(s.paymentStatus());
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

    /**
     * Who may declare stock coverage on THIS deal — the single source of truth for {@link
     * #reserveStock}'s gate and for whether {@link #actions} advertises RESERVE_STOCK, so the two
     * cannot drift into offering an action that immediately 403s (the same discipline {@link
     * #canIssueImportRequest} documents for 409s).
     *
     * <p>Ownership is expressed exactly as {@link #requireDealOwnership}, {@link
     * #canManageQuotation} and {@link #canConfirmCustomer} express it: a {@code sales} role that
     * created this deal. {@code sales_manager} is deliberately NOT included even though {@link
     * #requireDealOwnership} grants it — the ruling is "Sales declares", and the declaration feeds
     * the owning rep's own STOCK_BONUS input, so letting oversight write another rep's commission
     * input is a wider grant than was asked for. {@code ceo} keeps access through {@link
     * #FULFILMENT_ROLES}, not through ownership.
     */
    private boolean canDeclareStockCoverage(TicketSummaryDto s, UserPrincipal actor) {
        return FULFILMENT_ROLES.contains(actor.role())
            || (SALES_ROLES.contains(actor.role()) && s.createdById() == actor.id());
    }

    /**
     * The stage floor a stock-coverage declaration must clear, and the single source of truth for
     * it — {@link #reserveStock}'s 409 and {@link #canReserveStock} (which decides whether {@link
     * #actions} advertises RESERVE_STOCK) both read this one predicate, exactly as they both read
     * {@link #canDeclareStockCoverage} for the authorisation half. Applying the floor to only one
     * of them would put the capability back in the state the widening was careful to avoid: live
     * but invisible, or advertised and then refused on click.
     *
     * <p><strong>Why ORDER_RECEIVED (S10), and why a floor is needed at all.</strong> The
     * declaration is uncorroborated (see {@link #reserveStock}), yet a full-coverage one reroutes
     * the deal: {@code fulfillment_status} becomes {@code FROM_STOCK} and {@link #autoAdvanceStage}
     * — which checks only lifecycle and stage index — jumps it to {@code DELIVERY_SCHEDULING} from
     * wherever it was, {@code LEAD_APPROACH} included. That also blocks {@link
     * #issueImportRequest}, whose own guard requires {@code fulfillmentStatus == null}. So on one
     * rep's word an untouched lead could skip the entire import journey.
     *
     * <p>S10 is not an invented threshold: it is where the owner's own flows put the declaration.
     * The all-from-stock route runs {@code S8 -> S9 -> S10 PO verified -> (deposit?) -> declare
     * stock -> S18}, and the mixed route runs {@code S10 PO verified -> stock check -> declare or
     * IR}. Both declare only after the order is verified, which is the substantive reason as well
     * as the procedural one — before S10 the quantities are not final, so a coverage declaration
     * is meaningless.
     *
     * <p>Nothing above the floor changes: a full-coverage declaration still sets {@code FROM_STOCK}
     * and still advances to {@code DELIVERY_SCHEDULING}, whoever declares.
     *
     * <p>Fails closed on an unknown or null stage ({@link DealStage#indexOf} returns -1), which
     * {@code sales_stage NOT NULL} since V50 should already make unreachable.
     */
    private static boolean stockCoverageStageReached(TicketSummaryDto s) {
        return DealStage.indexOf(s.salesStage()) >= DealStage.indexOf(DealStage.ORDER_RECEIVED);
    }

    private boolean canReserveStock(TicketDto ticket, UserPrincipal actor) {
        return canDeclareStockCoverage(ticket.summary(), actor)
            && stockCoverageStageReached(ticket.summary())
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

    /** Prerequisites only — the role checks live on each action below. */
    private boolean closeReady(TicketSummaryDto s) {
        try {
            requireClosePrerequisites(s);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    private boolean canConfirmClose(TicketSummaryDto s, UserPrincipal actor) {
        return CLOSE_CONFIRM_ROLES.contains(actor.role())
            && DealLifecycle.ACTIVE.equals(s.lifecycle())
            && s.closeConfirmedAt() == null
            && closeReady(s);
    }

    private boolean canRevokeCloseConfirmation(TicketSummaryDto s, UserPrincipal actor) {
        return ACCOUNT_ROLES.contains(actor.role())
            && DealLifecycle.ACTIVE.equals(s.lifecycle())
            && s.closeConfirmedAt() != null;
    }

    private boolean canVerifyClose(TicketSummaryDto s, UserPrincipal actor) {
        return CEO_ROLES.contains(actor.role())
            && DealLifecycle.ACTIVE.equals(s.lifecycle())
            && s.closeConfirmedAt() != null
            && closeReady(s);
    }

    /**
     * The delivery half of the manual {@link #close} gate: the customer must actually
     * have the goods.
     *
     * This previously also accepted GOODS_RECEIVED with no delivery records, justified
     * as a concession to "legacy coarse deals". That justification did not hold:
     * legacy tickets close through the {@code legacyOk} branch (status=DOCUMENT_ISSUED),
     * which never consults this predicate at all. The only deals the concession ever
     * reached were modern dual-track ones (status=QUOTATION_ISSUED) — and for those it
     * was simply wrong. GOODS_RECEIVED means the goods reached GLR's own warehouse
     * (S17); the customer has received nothing. A fully-paid deal in that state was
     * closeable to COMPLETED with zero delivered units.
     *
     * Now aligned with {@link #maybeAdvanceClosedPaid}, so the manual and automatic
     * paths agree on what "delivered" means.
     */
    private boolean deliveryGateComplete(TicketSummaryDto s) {
        return FulfilmentStatus.FULLY_DELIVERED.equals(s.fulfillmentStatus());
    }

    /**
     * Advance to CLOSED_PAID only when BOTH gates are satisfied — payment is fully
     * paid AND the goods have actually been delivered to the customer
     * (FULLY_DELIVERED). CLOSED_PAID (S20) must not be reachable on payment alone
     * while goods are still undelivered.
     *
     * This now matches {@link #deliveryGateComplete} (used by the manual
     * {@link #close}); that predicate used to be looser, accepting GOODS_RECEIVED
     * with no delivery records, so the manual path could complete a deal this gate
     * would have refused. Both now require FULLY_DELIVERED.
     * {@code paymentFullyPaid} is passed explicitly because
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
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
    }

    private TicketSummaryDto loadAndVerifyStatus(long ticketId, String expectedStatus) {
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!expectedStatus.equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ต้องการสถานะ '" + expectedStatus + "' แต่ดีลนี้อยู่ในสถานะ '" + s.status() + "'");
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
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private boolean canDealOwnership(TicketSummaryDto s, UserPrincipal actor) {
        String role = actor.role();
        boolean isOwner = SALES_ROLES.contains(role) && s.createdById() == actor.id();
        return isOwner || "sales_manager".equals(role) || "ceo".equals(role);
    }
}
