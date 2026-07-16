package th.co.glr.hr.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import th.co.glr.hr.pricing.PriceBreakdownItemDto;
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

@Service
public class TicketService {
    private static final Set<String> SALES_ROLES  = Set.of("sales");
    private static final Set<String> IMPORT_ROLES = Set.of("import");
    private static final Set<String> CEO_ROLES    = Set.of("ceo");
    // Money-receipt confirmations belong to ฝ่ายบัญชี (accounting), with CEO as fallback.
    private static final Set<String> ACCOUNT_ROLES = Set.of("account", "ceo");
    // Who may read tickets at all. Mirrors the frontend's canViewTickets and the mock's
    // list/get gates — hr/employee have no business reading customer pricing.
    private static final Set<String> VIEWER_ROLES = Set.of("sales", "import", "ceo", "account");
    private static final Set<String> QUOTATION_ALLOWED_STATUSES =
        Set.of(TicketStatus.APPROVED, TicketStatus.QUOTATION_ISSUED);
    private static final Set<String> PROPOSE_ALLOWED_STATUSES =
        Set.of(TicketStatus.IN_REVIEW, TicketStatus.PRICE_PROPOSED, TicketStatus.APPROVED);

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
        String code = tickets.nextTicketCode();
        long id = tickets.create(request, code, actor.id(), actor.name());
        notifications.notifyByRole("import", id, "SUBMITTED",
            "Ticket " + code + " รอการรับเรื่อง");
        notifications.notifyByRole("ceo", id, "SUBMITTED",
            "Ticket " + code + " ส่งเข้าระบบแล้ว");
        return requireTicket(id);
    }

    @Transactional
    public TicketDto submit(long ticketId, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto s = loadAndVerifyStatus(ticketId, TicketStatus.DRAFT);
        if (s.createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the ticket owner can submit");
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
        loadAndVerifyStatus(ticketId, TicketStatus.SUBMITTED);
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
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.REJECTED, TicketStatus.PRICE_PROPOSED, TicketStatus.IN_REVIEW, request.reason());
        notifications.notifyByRole("import", ticketId, "REJECTED",
            "Ticket " + s.code() + " ถูกตีกลับ — กรุณาแก้ไขราคาเสนอ");
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto generateQuotation(long ticketId, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        String fromStatus = s.status();
        if (!QUOTATION_ALLOWED_STATUSES.contains(fromStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Expected status 'approved' or 'quotation_issued' but ticket is '" + fromStatus + "'");
        }
        if (s.createdById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the ticket owner can generate a quotation");
        }
        TicketDto full = requireTicket(ticketId);
        BigDecimal total = full.items().stream()
            .map(item -> {
                BigDecimal price = item.approvedPrice() != null ? item.approvedPrice() : BigDecimal.ZERO;
                return price.multiply(item.qty());
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        String number = tickets.nextQuotationCode();
        tickets.createQuotation(ticketId, number, actor.id(), total);
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.QUOTATION_ISSUED, fromStatus, TicketStatus.QUOTATION_ISSUED, null);
        return requireTicket(ticketId);
    }

    // Renders the quotation straight from the current ticket + quotation record — there is no
    // separate draft/edit phase, so "regenerating" just means re-rendering from live data.
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
        CustomerDto customer = ticket.summary().customerId() != null
            ? customers.findById(ticket.summary().customerId()).orElse(null)
            : null;
        return new QuotationRenderContext(ticket, quotation, customer);
    }

    @Transactional
    public TicketDto close(long ticketId, UserPrincipal actor) {
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
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
            && "GOODS_RECEIVED".equals(s.fulfillmentStatus());
        if (!legacyOk && !dualTrackOk) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Cannot close: require paymentStatus=FULLY_PAID and fulfillmentStatus=GOODS_RECEIVED");
        }
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.CLOSED, st, TicketStatus.CLOSED, null);
        return requireTicket(ticketId);
    }

    // ── Dual-track transitions (ข้อ 13) ─────────────────────────────────────

    @Transactional
    public TicketDto confirmCustomer(long ticketId, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        TicketSummaryDto s = loadAndVerifyStatus(ticketId, TicketStatus.QUOTATION_ISSUED);
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
        return requireTicket(ticketId);
    }

    // NOTE: the former issueDepositNotice endpoint (advance payment track with no
    // document) was removed — issuing the real deposit-notice document
    // (DepositNoticeService.issue) is now the single action that sets
    // paymentStatus=DEPOSIT_NOTICE_ISSUED.

    @Transactional
    public TicketDto confirmDepositPaid(long ticketId, UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!"DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Expected paymentStatus=DEPOSIT_NOTICE_ISSUED");
        }
        tickets.updatePaymentStatus(ticketId, "DEPOSIT_PAID");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.DEPOSIT_PAID, s.status(), s.status(), null);
        // Mirror of markGoodsReceived: if goods already arrived while the deposit was
        // unconfirmed, advance the payment track now — otherwise AWAITING_FINAL_PAYMENT
        // is unreachable and the ticket can never be closed.
        if ("GOODS_RECEIVED".equals(s.fulfillmentStatus())) {
            tickets.updatePaymentStatus(ticketId, "AWAITING_FINAL_PAYMENT");
            tickets.addEvent(ticketId, actor.id(), actor.name(),
                TicketEventKind.AWAITING_FINAL_PAYMENT, s.status(), s.status(), null);
        }
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto issueImportRequest(long ticketId, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        // DEPOSIT_PAID is also acceptable: the customer often pays (and accounting
        // confirms) before import gets to the IR — requiring DEPOSIT_NOTICE_ISSUED
        // exactly deadlocked the fulfillment track in that ordering.
        boolean depositReady = "DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus())
            || "DEPOSIT_PAID".equals(s.paymentStatus());
        if (!TicketStatus.QUOTATION_ISSUED.equals(s.status()) || !depositReady) {
            throw new ApiException(HttpStatus.CONFLICT,
                "IR requires quotation_issued + paymentStatus=DEPOSIT_NOTICE_ISSUED or DEPOSIT_PAID");
        }
        // Never restart an in-flight fulfillment track: re-issuing the IR would
        // downgrade IR_SENT/SHIPPING/GOODS_RECEIVED back to IR_ISSUED.
        if (s.fulfillmentStatus() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Import request already issued");
        }
        tickets.updateFulfillmentStatus(ticketId, "IR_ISSUED");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.IR_ISSUED, s.status(), s.status(), null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto markIrSent(long ticketId, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!"IR_ISSUED".equals(s.fulfillmentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Expected fulfillmentStatus=IR_ISSUED");
        }
        tickets.updateFulfillmentStatus(ticketId, "IR_SENT");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.IR_SENT, s.status(), s.status(), null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto markShipping(long ticketId, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!"IR_SENT".equals(s.fulfillmentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Expected fulfillmentStatus=IR_SENT");
        }
        tickets.updateFulfillmentStatus(ticketId, "SHIPPING");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.SHIPPING, s.status(), s.status(), null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto markGoodsReceived(long ticketId, UserPrincipal actor) {
        if (!IMPORT_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!"SHIPPING".equals(s.fulfillmentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Expected fulfillmentStatus=SHIPPING");
        }
        tickets.updateFulfillmentStatus(ticketId, "GOODS_RECEIVED");
        // Also advance payment track to AWAITING_FINAL_PAYMENT if deposit was paid
        if ("DEPOSIT_PAID".equals(s.paymentStatus())) {
            tickets.updatePaymentStatus(ticketId, "AWAITING_FINAL_PAYMENT");
            tickets.addEvent(ticketId, actor.id(), actor.name(),
                TicketEventKind.AWAITING_FINAL_PAYMENT, s.status(), s.status(), null);
        }
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.GOODS_RECEIVED, s.status(), s.status(), null);
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto confirmFinalPayment(long ticketId, UserPrincipal actor) {
        requireRole(actor, ACCOUNT_ROLES);
        TicketSummaryDto s = requireTicket(ticketId).summary();
        if (!"AWAITING_FINAL_PAYMENT".equals(s.paymentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Expected paymentStatus=AWAITING_FINAL_PAYMENT");
        }
        tickets.updatePaymentStatus(ticketId, "FULLY_PAID");
        tickets.addEvent(ticketId, actor.id(), actor.name(),
            TicketEventKind.FULLY_PAID, s.status(), s.status(), null);
        return requireTicket(ticketId);
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
        return requireTicket(ticketId);
    }

    @Transactional
    public TicketDto editItems(long ticketId, EditItemsRequest request, UserPrincipal actor) {
        TicketDto ticket = requireTicket(ticketId);
        TicketSummaryDto s = ticket.summary();
        String st = s.status();
        boolean isOwner = actor.id() == s.createdById();

        boolean salesCanEdit = SALES_ROLES.contains(actor.role()) && isOwner
            && Set.of(TicketStatus.SUBMITTED, TicketStatus.IN_REVIEW, TicketStatus.PRICE_PROPOSED).contains(st);

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
            String unitBasis = (r.unitBasis() != null && !r.unitBasis().isBlank())
                ? r.unitBasis() : "PIECE";
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
}
