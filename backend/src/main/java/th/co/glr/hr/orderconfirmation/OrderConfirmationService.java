package th.co.glr.hr.orderconfirmation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationItemDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.deposit.DepositNoticeDraftRequest;
import th.co.glr.hr.deposit.DepositNoticeDto;
import th.co.glr.hr.deposit.DepositNoticeItemRequest;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.orderconfirmation.OrderConfirmationDtos.OrderConfirmationResultDto;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.ConfirmOrderRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestEventKind;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRepository.OrderConfirmationState;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Step 6 of the sales pricing-flow redesign: Deposit, Payment, and Order Confirmation.
 *
 * <p><strong>This is a targeted bridge, not a rebuild.</strong> A large, working, already-tested
 * deposit/payment/fulfilment pipeline already exists on {@link TicketService}
 * ({@code confirmCustomer}, {@code confirmDepositPaid}, {@code issueImportRequest}) and
 * {@link DepositNoticeService} — but every one of those methods is keyed on the LEGACY
 * {@code sales.ticket.status} state machine, which Step 1 permanently severed from the new
 * PricingRequest chain ({@code TicketService.submit()} now always 409s). A deal driven entirely
 * through Steps 1-5 (PricingRequest -> FactoryQuote -> PricingCosting -> PricingDecision ->
 * CustomerQuotation) therefore has {@code ticket.status} stuck at {@code draft} forever, with no
 * path into any of that existing machinery.
 *
 * <p>This service is that one bridge. Once a pricing request's customer quotation reaches
 * {@link QuotationStatus#ACCEPTED} — which is also exactly when
 * {@link PricingRequestStatus#QUOTATION_ACCEPTED} is reached (Step 5's terminal status) —
 * {@link #confirmOrder} performs the ONE deliberate write outside the legacy state machine
 * ({@link TicketRepository#markQuotationIssuedForOrderConfirmation}, {@code draft ->
 * quotation_issued}) and then calls {@link TicketService#confirmCustomer} UNMODIFIED, whose own
 * gate ({@code status == quotation_issued}) now passes and correctly advances
 * {@code paymentStatus=CUSTOMER_CONFIRMED} + {@code DealStage.ORDER_RECEIVED} using code that
 * already exists and already works. {@link #createDepositNoticeFromQuotation} then builds a
 * deposit-notice draft from the ACCEPTED quotation's own items/total and calls the EXISTING
 * {@link DepositNoticeService#createDraft} unmodified — no changes were needed to that class, see
 * that method's own Javadoc.
 *
 * <p><strong>Dependency direction:</strong> this class depends on both
 * {@link PricingRequestRepository} (Step 1's aggregate) and {@link TicketService}/
 * {@link TicketRepository} (the legacy ticket state machine). It deliberately does NOT live on
 * {@code PricingRequestService} — that class's own Javadoc states it "never writes through"
 * {@link TicketRepository}, and {@code TicketService} already depends on {@code
 * PricingRequestService} for its own dead-deal cascade (see {@code TicketService}'s constructor
 * comment). Adding a {@code TicketService} dependency to {@code PricingRequestService} would
 * create a genuine circular Spring bean reference ({@code TicketService -> PricingRequestService
 * -> TicketService}). A small new orchestration class avoids that cycle entirely while still
 * being free to depend on both aggregates' repositories/services in one direction only.
 */
@Service
public class OrderConfirmationService {
    private static final Set<String> SALES_ROLES = Set.of("sales");

    private final PricingRequestRepository pricingRequests;
    private final TicketRepository tickets;
    private final TicketService ticketService;
    private final CustomerQuotationRepository quotations;
    private final DepositNoticeService depositNotices;
    private final NotificationRepository notifications;

    public OrderConfirmationService(PricingRequestRepository pricingRequests, TicketRepository tickets,
                                    TicketService ticketService, CustomerQuotationRepository quotations,
                                    DepositNoticeService depositNotices, NotificationRepository notifications) {
        this.pricingRequests = pricingRequests;
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.quotations = quotations;
        this.depositNotices = depositNotices;
        this.notifications = notifications;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // 1. The bridge action.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public OrderConfirmationResultDto confirmOrder(long pricingRequestId, ConfirmOrderRequest request, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
        requireOwner(summary, actor);
        String clientRequestId = validateUuid(request == null ? null : request.clientRequestId());

        // Advisory lock, held for the rest of this transaction — serializes every confirmOrder
        // call for this pricing request against itself, mirroring every earlier step's own
        // lockPricingRequest-then-replay-check pattern (e.g. CustomerQuotationService.issue).
        pricingRequests.lockPricingRequest(pricingRequestId);

        OrderConfirmationState state = pricingRequests.findOrderConfirmationState(pricingRequestId);
        if (state.confirmed()) {
            if (clientRequestId != null && clientRequestId.equals(state.clientRequestId())) {
                // Idempotent replay: the bridge already ran under this exact key. Return the
                // current state without re-running confirmCustomer a second time (which would
                // otherwise write a duplicate CUSTOMER_CONFIRMED event — harmless but not what a
                // replay should do).
                return currentResult(pricingRequestId, summary.ticketId(), actor);
            }
            throw new ApiException(HttpStatus.CONFLICT, "คำสั่งซื้อนี้ได้รับการยืนยันไปแล้ว");
        }
        // Re-read under the lock: the earlier `summary` was fetched before the lock was held, so
        // a concurrent status change (there is none possible today — QUOTATION_ACCEPTED is
        // terminal — but this mirrors every prior step's own "trust the locked read" discipline)
        // is not trusted for the gate below.
        PricingRequestSummaryDto locked = requirePricingRequest(pricingRequestId);
        if (!PricingRequestStatus.QUOTATION_ACCEPTED.equals(locked.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ยืนยันคำสั่งซื้อได้เฉพาะใบขอราคาที่ลูกค้ายอมรับใบเสนอราคาแล้วเท่านั้น (ปัจจุบัน: " + locked.status() + ")");
        }

        int confirmedRows = pricingRequests.markOrderConfirmed(pricingRequestId, actor.id(), clientRequestId);
        if (confirmedRows == 0) {
            // Lost a race against another confirmOrder call that committed between the state
            // check above and this compare-and-set — the advisory lock makes this practically
            // unreachable, but the guard is checked, not assumed.
            throw new ApiException(HttpStatus.CONFLICT, "คำสั่งซื้อนี้ได้รับการยืนยันไปแล้ว");
        }

        // The one deliberate bridge write: see this class's own Javadoc. Guarded FROM 'draft'
        // only, so a ticket that already carries a real legacy status is never silently
        // overwritten.
        int ticketRows = tickets.markQuotationIssuedForOrderConfirmation(locked.ticketId());
        if (ticketRows == 1) {
            tickets.addEvent(locked.ticketId(), actor.id(), actor.name(),
                TicketEventKind.ORDER_CONFIRMED_FROM_QUOTATION, TicketStatus.DRAFT, TicketStatus.QUOTATION_ISSUED,
                "ยืนยันคำสั่งซื้อจากใบเสนอราคาลูกค้าที่ยอมรับแล้ว (ใบขอราคา " + locked.requestCode() + ")");
        } else {
            // Defensive-only: markOrderConfirmed's own compare-and-set already makes this
            // unreachable through the real API (a genuine replay short-circuits above, before
            // this write is ever attempted a second time). If it is somehow reached, only a
            // ticket already sitting at exactly quotation_issued is safe to proceed past —
            // anything else means this ticket carries a real, unrelated legacy status and the
            // bridge must refuse rather than silently clobber it.
            TicketSummaryDto currentTicket = requireTicketSummary(locked.ticketId());
            if (!TicketStatus.QUOTATION_ISSUED.equals(currentTicket.status())) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "ไม่สามารถยืนยันคำสั่งซื้อได้ — สถานะดีล (ticket) เดิมขัดแย้งกับขั้นตอนนี้");
            }
        }

        // Step 8: reconcile sales.ticket_item.qty (the delivery machinery's own quantity source
        // — TicketService.reserveStock/completeDelivery) to whatever THIS pricing request
        // actually settled on, before any of that machinery becomes reachable. See
        // reconcileTicketItems's own Javadoc for the full "why".
        reconcileTicketItems(pricingRequestId, locked.ticketId(), actor);

        // The existing, already-tested pipeline takes over from here, unmodified.
        TicketDto ticketDto = ticketService.confirmCustomer(locked.ticketId(), actor);

        pricingRequests.addEvent(pricingRequestId, locked.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.ORDER_CONFIRMED, locked.status(), locked.status(),
            "ยืนยันคำสั่งซื้อแล้ว", null);
        notifications.notifyByRoleForPricingRequest("ceo", pricingRequestId, PricingRequestEventKind.ORDER_CONFIRMED,
            "ใบขอราคา " + locked.requestCode() + " ยืนยันคำสั่งซื้อแล้ว");

        return new OrderConfirmationResultDto(ticketDto, requirePricingRequest(pricingRequestId));
    }

    private OrderConfirmationResultDto currentResult(long pricingRequestId, long ticketId, UserPrincipal actor) {
        TicketDto ticketDto = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"));
        return new OrderConfirmationResultDto(ticketDto, requirePricingRequest(pricingRequestId));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // 1a. Step 8: ticket_item <-> pricing-request-chain quantity reconciliation.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * <p><strong>The central question Step 8 had to answer first, by reading the code and
     * writing a test — not by assuming either answer:</strong> does {@code
     * sales.ticket_item.qty} still match the quantity the customer actually accepted by the time
     * the deal reaches delivery? It does NOT, in general. {@code sales.ticket_item.qty} is
     * written exactly once, at ticket creation ({@link TicketRepository}'s {@code insertItems},
     * called from {@code TicketService.create}), and is never touched again by any of Steps 1-6 —
     * {@code sales.pricing_request_item.requested_qty} is its own, wholly independent field from
     * the moment a pricing request is first drafted (confirmed by reading {@code
     * PricingRequestRequests.PricingRequestItemRequest}: {@code requestedQty} has no default tied
     * to any ticket_item value). Two ways this drifts:
     *
     * <ol>
     *   <li>Even on the FIRST, non-revised pricing request: nothing ever required
     *       {@code requestedQty} to equal the {@code qty} Sales typed into the ticket-creation
     *       form — they are independently entered. (Confirmed live in this codebase's own Step 7
     *       fixture, {@code ProcurementServiceIntegrationTest#ticketItem}: every ticket item is
     *       created with {@code qty=1}, while the pricing-request items in the very same test
     *       request quantities of 10 and 5 — a real, already-existing mismatch, not a
     *       hypothetical.)</li>
     *   <li>A customer-change revision ({@code PricingRequestService.createCustomerChangeRevision})
     *       can change a line's quantity outright, and creates a wholly NEW {@code
     *       pricing_request}/{@code pricing_request_item} row set — it never writes back to the
     *       original {@code ticket_item} row. That method is reachable even from {@code
     *       QUOTATION_ACCEPTED} (its own status guard only excludes {@code DRAFT}/{@code
     *       CANCELLED}/{@code SUPERSEDED} — {@code PricingRequestStatus.ALLOWED} marks {@code
     *       QUOTATION_ACCEPTED} as terminal for FORWARD status transitions, but {@code
     *       createCustomerChangeRevision} does not consult that table for its own gate), so a
     *       customer can revise quantities on an already-accepted deal, and Step 5's own
     *       "terminal" status design does NOT block it.</li>
     * </ol>
     *
     * <p>{@link #confirmOrder} is the one bridge point that ALWAYS runs, exactly once per
     * customer-accepted quotation, strictly before any delivery machinery
     * ({@code TicketService.reserveStock}/{@code completeDelivery}, both keyed on this same
     * {@code qty} column) becomes reachable for the deal — {@code DealStage.PROCUREMENT} (the
     * earliest either method can matter) is only reachable via {@code issueImportRequest}, which
     * requires {@code ticket.status=QUOTATION_ISSUED}, which only this bridge ever writes. So
     * reconciling here, against THIS pricing request's own current {@code pricing_request_item}
     * rows (not the quotation's frozen snapshot — they carry the same figures for an item that
     * traces to a quotation line, see {@code CustomerQuotationItemDto}'s own "immutably
     * snapshotted... from the approved pricing_decision_item" Javadoc, but going straight to
     * {@code pricing_request_item} avoids an unnecessary join through
     * {@code pricing_decision_item}/{@code quotation_item}), guarantees
     * {@code reserveStock}/{@code completeDelivery} always see the quantity this specific
     * customer decision actually committed to.
     *
     * <p>Design decision: extend {@code sales.ticket_item} in place (UPDATE for a line that
     * traces back via {@code source_ticket_item_id}, INSERT for a wholly new line added by a
     * revision) rather than repointing the delivery machinery at
     * {@code pricing_request_item}/{@code quotation_item} directly. This was chosen over the
     * task brief's other suggested option (reading the chain's own quantities directly, mirroring
     * {@code payableAmount}'s COALESCE-chain) because {@code reserveStock}/{@code
     * completeDelivery}/{@code qty_delivered}/{@code qty_from_stock} are ALL keyed off {@code
     * ticket_item.item_id} throughout a large, already-working, already-tested surface (V54) —
     * keeping that surface's one source of truth correct is a smaller, safer diff than teaching
     * it a second quantity source. A line dropped entirely by a revision (no longer present in
     * the confirmed pricing request at all) is deliberately left untouched on {@code ticket_item}
     * — see this branch's own handoff "Known Risks" for why that narrow case is not handled here.
     */
    private void reconcileTicketItems(long pricingRequestId, long ticketId, UserPrincipal actor) {
        List<PricingRequestItemDto> items = pricingRequests.findItems(pricingRequestId);
        boolean anyChange = false;
        for (PricingRequestItemDto item : items) {
            String unitBasis = mapUnitBasisToTicketItem(item.requestedUnitBasis());
            if (item.sourceTicketItemId() != null) {
                boolean changed;
                try {
                    changed = tickets.reconcileItemQty(
                        ticketId, item.sourceTicketItemId(), item.requestedQty(), item.requestedQtySqm());
                } catch (DataIntegrityViolationException e) {
                    throw new ApiException(HttpStatus.CONFLICT,
                        "ไม่สามารถปรับจำนวนสินค้า (item " + item.sourceTicketItemId()
                            + ") ให้ตรงกับใบขอราคาที่ยืนยันคำสั่งซื้อได้ เนื่องจากมีการส่งมอบหรือจองสต็อกไปแล้วเกินจำนวนใหม่");
                }
                anyChange = anyChange || changed;
            } else {
                // A wholly new line added by a customer-change revision — no original ticket_item
                // to reconcile against, so one is created now (once), before it becomes visible
                // to reserveStock/completeDelivery.
                tickets.insertReconciledItem(ticketId, resolveBrand(item), item.model(), item.color(),
                    item.texture(), item.size(), item.factory(), item.requestedQty(), item.requestedQtySqm(),
                    unitBasis);
                anyChange = true;
            }
        }
        if (anyChange) {
            pricingRequests.addEvent(pricingRequestId, ticketId, actor.id(), actor.name(),
                PricingRequestEventKind.TICKET_ITEMS_RECONCILED, null, null,
                "ปรับจำนวนสินค้าในรายการดีลให้ตรงกับใบขอราคาที่ยืนยันคำสั่งซื้อแล้ว", null);
        }
    }

    /** ticket_item.brand is NOT NULL (V8); a pricing_request_item's own brand is optional (it may
     * be identified only by productId/productDescription — see PricingRequestService
     * .isProductIdentified). Falls back to whatever IS present, in the same "some identifying
     * text" order that method already validates was required at submission time. */
    private String resolveBrand(PricingRequestItemDto item) {
        if (item.brand() != null && !item.brand().isBlank()) return item.brand();
        if (item.model() != null && !item.model().isBlank()) return item.model();
        if (item.productDescription() != null && !item.productDescription().isBlank()) return item.productDescription();
        return "รายการใหม่จากใบขอราคา";
    }

    /** ticket_item.unit_basis's own CHECK constraint (V37) only allows PIECE/SQM — a narrower set
     * than UnitBasis's four canonical PER_SQM/PER_PIECE/PER_BOX/PER_LINEAR_M codes. Display-only
     * mapping, duplicated rather than shared, matching this class's own unitLabel precedent. */
    private String mapUnitBasisToTicketItem(String unitBasis) {
        return UnitBasis.PER_SQM.equals(unitBasis) ? "SQM" : "PIECE";
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // 2. Deposit notice from the accepted quotation.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Builds a deposit-notice DRAFT from the pricing request's own ACCEPTED customer quotation —
     * items and amounts trace to {@code sales.quotation}/{@code quotation_item} (Step 4/5's
     * aggregate), never to any {@code sales.ticket_item} row. Calls the EXISTING {@link
     * DepositNoticeService#createDraft} unmodified: because {@code buildItemsFromRequest} inside
     * that method already returns the caller-supplied items verbatim whenever they are non-empty
     * (skipping its own legacy ticket_item auto-population entirely), and because {@link
     * #confirmOrder} already left {@code ticket.status = quotation_issued} — one of {@code
     * requireApprovedTicket}'s three already-accepted values — no change to {@code
     * DepositNoticeService} itself was needed. Verified by the "traces to the quotation, NOT to
     * any sales.ticket_item row" assertions inside {@code OrderConfirmationIntegrationTest
     * .fullChain_quotationAcceptedThroughDepositPaid_composesWithoutShortcuts}.
     */
    @Transactional
    public DepositNoticeDto createDepositNoticeFromQuotation(long pricingRequestId,
            CreateDepositNoticeFromQuotationRequest request, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
        requireOwner(summary, actor);

        CustomerQuotationDto accepted = quotations.findByPricingRequest(pricingRequestId).stream()
            .filter(q -> QuotationStatus.ACCEPTED.equals(q.docStatus()))
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "ยังไม่มีใบเสนอราคาที่ลูกค้ายอมรับสำหรับใบขอราคานี้"));

        List<DepositNoticeItemRequest> items = new ArrayList<>();
        for (CustomerQuotationItemDto item : accepted.items()) {
            String description = item.description() != null && !item.description().isBlank()
                ? item.description() : "รายการสินค้า";
            BigDecimal discount = item.salesDiscount();
            String discountLabel = discount != null && discount.signum() > 0
                ? "ส่วนลด " + discount.stripTrailingZeros().toPlainString() + " ต่อหน่วย" : null;
            items.add(new DepositNoticeItemRequest(
                item.seq(), description, item.requestedQuantity(), unitLabel(item.requestedUnitBasis()),
                item.approvedUnitPrice(), discountLabel, item.finalUnitPrice()));
        }

        DepositNoticeDraftRequest draftRequest = new DepositNoticeDraftRequest(
            null, null, null, null, accepted.number(),
            request != null ? request.depositPercent() : null, null, items);
        DepositNoticeDto draft = depositNotices.createDraft(summary.ticketId(), draftRequest, actor);

        pricingRequests.addEvent(pricingRequestId, summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.DEPOSIT_NOTICE_DRAFTED_FROM_QUOTATION, summary.status(), summary.status(),
            "สร้างร่างใบแจ้งยอดเงินรับมัดจำจากใบเสนอราคา " + accepted.number(), null);
        return draft;
    }

    private String unitLabel(String unitBasis) {
        if (unitBasis == null) return "หน่วย";
        return switch (unitBasis) {
            case UnitBasis.PER_SQM -> "ตร.ม.";
            case UnitBasis.PER_PIECE -> "แผ่น";
            case UnitBasis.PER_BOX -> "กล่อง";
            case UnitBasis.PER_LINEAR_M -> "เมตร";
            default -> unitBasis;
        };
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    /** Owner-only, matching every prior step's "sales" scoping: the deal (ticket) owner, not
     * merely the pricing request's own requested_by (which may differ). */
    private void requireOwner(PricingRequestSummaryDto summary, UserPrincipal actor) {
        if ("sales".equals(actor.role()) && summary.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private PricingRequestSummaryDto requirePricingRequest(long pricingRequestId) {
        return pricingRequests.findSummary(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Pricing request not found"));
    }

    private TicketSummaryDto requireTicketSummary(long ticketId) {
        return tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"))
            .summary();
    }

    private String validateUuid(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(clientRequestId.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientRequestId must be a valid UUID");
        }
    }
}
