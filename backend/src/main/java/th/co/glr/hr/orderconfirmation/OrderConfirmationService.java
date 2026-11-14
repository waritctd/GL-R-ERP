package th.co.glr.hr.orderconfirmation;

import java.math.BigDecimal;
import th.co.glr.hr.ticket.DealOwnership;
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
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

/** Step 6 of the sales pricing-flow redesign: Deposit, Payment, and Order Confirmation. A small new
 * orchestration class avoids that cycle entirely while still being free to depend on both aggregates'
 * repositories/services in one direction only. */
@Service
public class OrderConfirmationService {
    private static final Set<String> SALES_ROLES = Set.of("sales");

    private final PricingRequestRepository pricingRequests;
    private final TicketRepository tickets;
    private final TicketService ticketService;
    private final CustomerQuotationRepository quotations;
    private final DepositNoticeService depositNotices;
    private final NotificationRepository notifications;
    // Read-only dependency onto the new engine, used by #reconcileTicketItems to prefer an ACCEPTED
    // PRICING_REQUEST-origin quotation's own line quantities over the PR item's authored ones, and
    // by #createDepositNoticeFromQuotation to find the pricing request's ACCEPTED new-engine
    // quotation. SETTER-injected (not a constructor parameter), matching DealQuotationService's own
    // established device (see its #wirePricingRequestDependencies Javadoc): the hand-wired
    // `new OrderConfirmationService(...)` test call sites keep compiling unchanged, Spring wires
    // it automatically via the @Autowired setter in production, and #reconcileTicketItems
    // null-guards so an unwired test (any that never exercises a PRICING_REQUEST-origin PR) is
    // unaffected either way -- falling back to the PR item's own quantity, which is what those
    // tests' legacy-origin fixtures need regardless.
    private th.co.glr.hr.dealquotation.DealQuotationRepository dealQuotations;

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

    /** See {@link #dealQuotations}'s own Javadoc for why this is setter- rather than
     * constructor-injected. {@code required = false}: an unwired instance falls back to the PR
     * item's own quantity in confirmOrder, and answers 409 on the new-engine branch of
     * createDepositNoticeFromQuotation. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void wireDealQuotationRepository(th.co.glr.hr.dealquotation.DealQuotationRepository dealQuotations) {
        this.dealQuotations = dealQuotations;
    }

    // 1. The bridge action.

    @Transactional
    public OrderConfirmationResultDto confirmOrder(long pricingRequestId, ConfirmOrderRequest request, UserPrincipal actor) {
        requireRole(actor, SALES_ROLES);
        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
        requireOwner(summary, actor);
        String clientRequestId = validateUuid(request == null ? null : request.clientRequestId());

        // Advisory lock, held for the rest of this transaction — serializes every confirmOrder call for this pricing
        // request against itself, mirroring every earlier step's own lockPricingRequest-then-replay-check pattern
        // (e.g. CustomerQuotationService.issue).
        pricingRequests.lockPricingRequest(pricingRequestId);

        OrderConfirmationState state = pricingRequests.findOrderConfirmationState(pricingRequestId);
        if (state.confirmed()) {
            if (clientRequestId != null && clientRequestId.equals(state.clientRequestId())) {
                // Idempotent replay: the bridge already ran under this exact key. Return the current state without re-running
                // confirmCustomer a second time (which would otherwise write a duplicate CUSTOMER_CONFIRMED event — harmless
                // but not what a replay should do).
                return currentResult(pricingRequestId, summary.ticketId(), actor);
            }
            throw new ApiException(HttpStatus.CONFLICT, "คำสั่งซื้อนี้ได้รับการยืนยันไปแล้ว");
        }
        // Re-read under the lock: the earlier `summary` was fetched before the lock was held, so a concurrent status
        // change (there is none possible today — QUOTATION_ACCEPTED is terminal — but this mirrors every prior step's
        // own "trust the locked read" discipline) is not trusted for the gate below.
        PricingRequestSummaryDto locked = requirePricingRequest(pricingRequestId);
        if (!PricingRequestStatus.QUOTATION_ACCEPTED.equals(locked.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ยืนยันคำสั่งซื้อได้เฉพาะคำขอราคาที่ลูกค้ายอมรับใบเสนอราคาแล้วเท่านั้น (ปัจจุบัน: " + locked.status() + ")");
        }

        // Gate BEFORE the first write: the bridge below can only handle a ticket sitting at
        // 'draft' (it advances it) or already at 'quotation_issued' (a later accepted revision).
        // Any other status would be discovered only AFTER markOrderConfirmed had committed,
        // stranding the deal half-confirmed. Refuse up front, naming the status.
        String ticketStatus = requireTicketSummary(locked.ticketId()).status();
        if (!isBridgeableTicketStatus(ticketStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ไม่สามารถยืนยันคำสั่งซื้อได้ — สถานะดีล (ticket) เป็น '" + ticketStatus
                    + "' ซึ่งไม่ตรงกับขั้นตอนนี้ (ต้องเป็น '" + TicketStatus.DRAFT + "' หรือ '"
                    + TicketStatus.QUOTATION_ISSUED + "')");
        }

        int confirmedRows = pricingRequests.markOrderConfirmed(pricingRequestId, actor.id(), clientRequestId);
        if (confirmedRows == 0) {
            // Lost a race against another confirmOrder call that committed between the state check above and this
            // compare-and-set — the advisory lock makes this practically unreachable, but the guard is checked, not
            // assumed.
            throw new ApiException(HttpStatus.CONFLICT, "คำสั่งซื้อนี้ได้รับการยืนยันไปแล้ว");
        }

        // The one deliberate bridge write: see this class's own Javadoc. Guarded FROM 'draft' only, so a ticket that
        // already carries a real legacy status is never silently overwritten.
        int ticketRows = tickets.markQuotationIssuedForOrderConfirmation(locked.ticketId());
        if (ticketRows == 1) {
            tickets.addEvent(locked.ticketId(), actor.id(), actor.name(),
                TicketEventKind.ORDER_CONFIRMED_FROM_QUOTATION, TicketStatus.DRAFT, TicketStatus.QUOTATION_ISSUED,
                "ยืนยันคำสั่งซื้อจากใบเสนอราคาลูกค้าที่ยอมรับแล้ว (คำขอราคา " + locked.requestCode() + ")");
        } else {
            // Defensive-only: markOrderConfirmed's own compare-and-set already makes this unreachable through the real API
            // (a genuine replay short-circuits above, before this write is ever attempted a second time).
            TicketSummaryDto currentTicket = requireTicketSummary(locked.ticketId());
            if (!TicketStatus.QUOTATION_ISSUED.equals(currentTicket.status())) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "ไม่สามารถยืนยันคำสั่งซื้อได้ — สถานะดีล (ticket) เดิมขัดแย้งกับขั้นตอนนี้");
            }
        }

        // Reconcile sales.ticket_item.qty (the delivery machinery's own quantity source
        // — TicketService.reserveStock/completeDelivery) to whatever THIS pricing request
        // actually settled on, before any of that machinery becomes reachable. See
        // reconcileTicketItems's own Javadoc for the full "why".
        reconcileTicketItems(pricingRequestId, locked.ticketId(), actor);

        // Bug found on review, fixed in the same step: confirmOrder is meant to run once PER ACCEPTED PRICING REQUEST,
        // since reconciliation above must happen for every accepted revision, not just the deal's first one.
        TicketSummaryDto currentTicketState = requireTicketSummary(locked.ticketId());
        TicketDto ticketDto;
        if (currentTicketState.paymentStatus() == null) {
            // The accepted new-engine quotation's percent decides the deposit policy (see
            // TicketService#applyCustomerConfirmation; a blank percent means 30). A legacy
            // quotation carries none, so the policy is left alone.
            th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto confirmed = dealQuotations == null
                ? null
                : dealQuotations.findAcceptedForPricingRequest(pricingRequestId)
                    .flatMap(dealQuotations::findById)
                    .orElse(null);
            ticketDto = ticketService.confirmCustomer(locked.ticketId(),
                confirmed == null ? null : confirmed.number(),
                confirmed == null ? null
                    : th.co.glr.hr.dealquotation.DealQuotationRenderAdapter.effectiveDepositPercent(confirmed.depositPercent()),
                actor);
        } else {
            ticketDto = tickets.findById(locked.ticketId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
        }

        pricingRequests.addEvent(pricingRequestId, locked.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.ORDER_CONFIRMED, locked.status(), locked.status(),
            "ยืนยันคำสั่งซื้อแล้ว", null);
        notifications.notifyByRoleForPricingRequest("ceo", pricingRequestId, PricingRequestEventKind.ORDER_CONFIRMED,
            "คำขอราคา " + locked.requestCode() + " ยืนยันคำสั่งซื้อแล้ว");

        return new OrderConfirmationResultDto(ticketDto, requirePricingRequest(pricingRequestId));
    }

    /** The ticket statuses #confirmOrder's bridge can proceed from. Public so the DTO exposed to the frontend
     * (ticketStatus) and the UI gate share one definition. */
    public static boolean isBridgeableTicketStatus(String ticketStatus) {
        return TicketStatus.DRAFT.equals(ticketStatus) || TicketStatus.QUOTATION_ISSUED.equals(ticketStatus);
    }

    private OrderConfirmationResultDto currentResult(long pricingRequestId, long ticketId, UserPrincipal actor) {
        TicketDto ticketDto = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
        return new OrderConfirmationResultDto(ticketDto, requirePricingRequest(pricingRequestId));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // ticket_item <-> pricing-request-chain quantity reconciliation.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** The central question Step 8 had to answer first, by reading the code and writing a test — not by assuming
     * either answer: does sales.ticket_item.qty still match the quantity the customer actually accepted by the
     * time the deal reaches delivery? */
    private void reconcileTicketItems(long pricingRequestId, long ticketId, UserPrincipal actor) {
        List<PricingRequestItemDto> items = pricingRequests.findItems(pricingRequestId);
        // See DealQuotationRepository#findAcceptedItemQuantitiesByPricingRequest's Javadoc. Empty
        // (not merely unwired) whenever this PR's accepted quotation is on the LEGACY engine or
        // there is none yet -- the legacy engine has no per-line quantity edit at all, so falling
        // back to the PR item's own requestedQty()/requestedQtySqm() below is correct there, not a
        // compromise.
        java.util.Map<Long, th.co.glr.hr.dealquotation.DealQuotationRepository.AcceptedItemQty> acceptedByPrItemId =
            dealQuotations != null
                ? dealQuotations.findAcceptedItemQuantitiesByPricingRequest(pricingRequestId)
                : java.util.Map.of();
        boolean anyChange = false;
        for (PricingRequestItemDto item : items) {
            String unitBasis = mapUnitBasisToTicketItem(item.requestedUnitBasis());
            // The customer ACCEPTED this quantity on the quotation, not necessarily what the PR
            // was authored with (sales may edit qty/เผื่อ on a PRICING_REQUEST-origin quotation
            // without CEO re-approval — see requireCeoApprovalIfChanged's own exclusion).
            th.co.glr.hr.dealquotation.DealQuotationRepository.AcceptedItemQty accepted =
                acceptedByPrItemId.get(item.id());
            BigDecimal reconciledQty = accepted != null ? accepted.qty() : item.requestedQty();
            // qty_sqm must move with qty: if it kept the PR's ORIGINAL, pre-edit area, qty=25.00
            // would sit beside qty_sqm=3.6000 (the stale 10-piece area) on the SAME row. So derive
            // qty_sqm from the SAME accepted qty via the shared {@code
            // WastageCalculator#sqmQuantityFromPieces} (never reimplemented here) whenever a
            // sqm/piece basis is available on the accepted line; fall back to the PR item's own
            // requestedQtySqm() when there is no accepted new-engine quotation (legacy engine --
            // correct already, see comment above) or the accepted line has no sqm/piece basis
            // (defensive only -- every TILE row on this origin has one).
            BigDecimal reconciledQtySqm = (accepted != null && accepted.sqmPerPiece() != null)
                ? th.co.glr.hr.dealquotation.WastageCalculator.sqmQuantityFromPieces(
                    reconciledQty.intValueExact(), accepted.sqmPerPiece())
                : item.requestedQtySqm();
            // A stock line is covered in full and never ordered from a factory. Any other line is left as it is
            // (null): a legacy reserve on an import line survives, and a new row defaults to 0.
            BigDecimal qtyFromStock = PricingRequestService.STOCK_IN_THAILAND.equals(item.stockSource())
                ? reconciledQty : null;
            if (item.sourceTicketItemId() != null) {
                boolean changed;
                try {
                    changed = tickets.reconcileItemQty(
                        ticketId, item.sourceTicketItemId(), reconciledQty, reconciledQtySqm, qtyFromStock);
                } catch (DataIntegrityViolationException e) {
                    throw new ApiException(HttpStatus.CONFLICT,
                        "ไม่สามารถปรับจำนวนสินค้า (item " + item.sourceTicketItemId()
                            + ") ให้ตรงกับคำขอราคาที่ยืนยันคำสั่งซื้อได้ เนื่องจากมีการส่งมอบหรือจองสต็อกไปแล้วเกินจำนวนใหม่");
                }
                anyChange = anyChange || changed;
            } else {
                // A wholly new line added by a customer-change revision — no original ticket_item to reconcile against, so one
                // is created now (once), before it becomes visible to reserveStock/completeDelivery.
                tickets.insertReconciledItem(ticketId, resolveBrand(item), item.model(), item.color(),
                    item.texture(), item.size(), item.factory(), reconciledQty, reconciledQtySqm,
                    unitBasis, qtyFromStock);
                anyChange = true;
            }
        }
        // Bug fix: close out any ticket_item this chain USED to reference but the currently-accepted revision dropped
        // entirely — see TicketRepository.closeOutDroppedChainItems's own Javadoc for why an unclosed stale line
        // permanently blocks completeDelivery's "no open quantities" check.
        long rootPricingRequestId = pricingRequests.findRootPricingRequestId(pricingRequestId).orElse(pricingRequestId);
        int closed = tickets.closeOutDroppedChainItems(ticketId, rootPricingRequestId, pricingRequestId);
        anyChange = anyChange || closed > 0;
        if (anyChange) {
            pricingRequests.addEvent(pricingRequestId, ticketId, actor.id(), actor.name(),
                PricingRequestEventKind.TICKET_ITEMS_RECONCILED, null, null,
                "ปรับจำนวนสินค้าในรายการดีลให้ตรงกับคำขอราคาที่ยืนยันคำสั่งซื้อแล้ว", null);
        }
    }

    /** ticket_item.brand is NOT NULL (V8); a pricing_request_item's own brand is optional (it may be identified
     * only by productId/productDescription — see PricingRequestService .isProductIdentified). */
    private String resolveBrand(PricingRequestItemDto item) {
        if (item.brand() != null && !item.brand().isBlank()) return item.brand();
        if (item.model() != null && !item.model().isBlank()) return item.model();
        if (item.productDescription() != null && !item.productDescription().isBlank()) return item.productDescription();
        return "รายการใหม่จากคำขอราคา";
    }

    /** ticket_item.unit_basis's own CHECK constraint (V37) only allows PIECE/SQM — a narrower set than UnitBasis's
     * four canonical PER_SQM/PER_PIECE/PER_BOX/PER_LINEAR_M codes. */
    private String mapUnitBasisToTicketItem(String unitBasis) {
        return UnitBasis.PER_SQM.equals(unitBasis) ? "SQM" : "PIECE";
    }

    // 2. Deposit notice from the accepted quotation.

    /**
     * Builds a deposit-notice DRAFT from the pricing request's own ACCEPTED customer quotation —
     * items and amounts trace to {@code sales.quotation}/{@code quotation_item} (Step 4/5's
     * aggregate), never to any {@code sales.ticket_item} row. Calls the EXISTING {@link
     * DepositNoticeService#createDraft}: {@code buildItemsFromRequest} inside that method already
     * returns the caller-supplied items verbatim whenever they are non-empty (skipping its own
     * ticket-chain auto-population entirely), and {@link #confirmOrder} already left {@code
     * ticket.status = quotation_issued} — one of {@code requireApprovedTicket}'s three
     * already-accepted values. Verified by the "traces to the quotation, NOT to any
     * sales.ticket_item row" assertions inside {@code OrderConfirmationIntegrationTest
     * .fullChain_quotationAcceptedThroughDepositPaid_composesWithoutShortcuts}.
     *
     * <p>{@code DepositNoticeService.createDraft} has to cope with this chain: every deal created
     * purely through it reaches this method with {@code ticket_item.approved_price} still NULL on
     * every line (that column is written only by the {@code @Deprecated}, routeless {@code
     * TicketService.approve}), and the header fields ({@code customerTaxId}/{@code
     * customerAddress}/{@code projectName}) are not supplied. This call site always supplies
     * non-empty {@code items} itself, and leaves the header fields {@code null} for {@code
     * createDraft} to autofill from the ticket's own customer/project, same as the ticket-level
     * route.
     *
     * <p><strong>Deposit percent:</strong> {@code
     * request.depositPercent()} is never read: the percent is the one the chosen quotation's PDF
     * printed, passed as {@code sourceDepositPercent}. For the legacy branch that is always {@link
     * QuotationRenderer#LEGACY_DEPOSIT_PERCENT}, because the legacy PDF never reads the stored
     * column. Deleting the field from {@link CreateDepositNoticeFromQuotationRequest} would refuse
     * no client (unknown JSON fields are ignored); its bounds still 400 an out-of-range value.
     *
     * <p><strong>New-engine quotation:</strong> when the pricing request has no ACCEPTED legacy
     * quotation, this falls back to its ACCEPTED new-engine {@code PRICING_REQUEST}-origin
     * quotation ({@code DealQuotationService#createFromPricingRequest}), found by {@code
     * DealQuotationRepository#findAcceptedForPricingRequest} (its Javadoc says why "latest" is
     * the wrong pick). The items come from that quotation,
     * the accepted quotation of THIS pricing request ({@link
     * DepositNoticeService#itemsFromDealQuotation}), so a sibling pricing request's quotation on
     * the same ticket can never supply them. The percent also comes from that quotation: its own
     * {@code deposit_percent}, a null one as the PDF's default 30%, goes to {@link
     * DepositNoticeService#createDraftFromQuotation}. Neither branch consults the
     * ticket's governing quotation ({@code DepositNoticeRepository#findGoverningQuotation}),
     * which only the ticket-level route uses. The customer must have accepted: an ISSUED-only
     * quotation answers 409.
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
            .orElse(null);

        String reference;
        List<DepositNoticeItemRequest> items;
        Integer sourceDepositPercent;
        if (accepted != null) {
            reference = accepted.number();
            items = DepositNoticeService.itemsFromQuotation(accepted.items());
            sourceDepositPercent = QuotationRenderer.LEGACY_DEPOSIT_PERCENT;
        } else {
            th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto newEngine = dealQuotations == null
                ? null
                : dealQuotations.findAcceptedForPricingRequest(pricingRequestId)
                    .flatMap(dealQuotations::findById)
                    .orElse(null);
            if (newEngine == null) {
                throw new ApiException(HttpStatus.CONFLICT, "ยังไม่มีใบเสนอราคาที่ลูกค้ายอมรับสำหรับคำขอราคานี้");
            }
            // A zero-deposit quotation (DealQuotationService#isZeroDeposit: "ไม่รับมัดจำ", paid in
            // full under fullPaymentTerm) has nothing to bill. The confirmation above moves such a
            // deal to NOT_REQUIRED and createDraftFromQuotation refuses a bypass policy; this check
            // stays for deals confirmed before that sync existed (still REQUIRED).
            if (Integer.valueOf(0).equals(newEngine.depositPercent())) {
                throw DepositNoticeService.zeroDepositRefusal(newEngine.number());
            }
            reference = newEngine.number();
            items = depositNotices.itemsFromDealQuotation(newEngine.id());
            sourceDepositPercent = newEngine.depositPercent() != null ? newEngine.depositPercent()
                : th.co.glr.hr.dealquotation.DealQuotationRenderAdapter.DEFAULT_DEPOSIT_PERCENT;
        }

        DepositNoticeDraftRequest draftRequest = new DepositNoticeDraftRequest(
            null, null, null, null, reference, null, null, items);
        DepositNoticeDto draft = depositNotices.createDraftFromQuotation(summary.ticketId(), draftRequest,
            sourceDepositPercent, actor);

        pricingRequests.addEvent(pricingRequestId, summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.DEPOSIT_NOTICE_DRAFTED_FROM_QUOTATION, summary.status(), summary.status(),
            "สร้างร่างใบแจ้งยอดเงินรับมัดจำจากใบเสนอราคา " + reference, null);
        return draft;
    }

    // Helpers

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    /** Owner-only, matching every prior step's "sales" scoping: the deal (ticket) owner, not merely the pricing
     * request's own requested_by (which may differ). */
    private void requireOwner(PricingRequestSummaryDto summary, UserPrincipal actor) {
        if ("sales".equals(actor.role()) && !DealOwnership.isOwnerOrKeyer(summary.ticketCreatedById(), summary.ticketKeyedById(), actor.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private PricingRequestSummaryDto requirePricingRequest(long pricingRequestId) {
        return pricingRequests.findSummary(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
    }

    private TicketSummaryDto requireTicketSummary(long ticketId) {
        return tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"))
            .summary();
    }

    private String validateUuid(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(clientRequestId.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientRequestId ต้องเป็น UUID ที่ถูกต้อง");
        }
    }
}
