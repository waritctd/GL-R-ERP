package th.co.glr.hr.procurement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestEventKind;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.procurement.ProcurementDtos.FactoryPurchaseOrderDto;
import th.co.glr.hr.procurement.ProcurementDtos.FactoryPurchaseOrderItemDto;
import th.co.glr.hr.procurement.ProcurementRepository.CostingItemForPo;
import th.co.glr.hr.procurement.ProcurementRequests.CancelFactoryPurchaseOrderRequest;
import th.co.glr.hr.procurement.ProcurementRequests.CreateFactoryPurchaseOrdersRequest;
import th.co.glr.hr.procurement.ProcurementRequests.ItemReceiptRequest;
import th.co.glr.hr.procurement.ProcurementRequests.RecordGoodsReceivedRequest;
import th.co.glr.hr.procurement.ProcurementRequests.RecordShippingDetailRequest;
import th.co.glr.hr.procurement.ProcurementRequests.RecordSupplierProformaRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Step 7 of the sales pricing-flow redesign: Factory Purchase Order and Import Execution.
 *
 * <p><strong>This step genuinely builds new entities — it is not a bridge into existing
 * machinery.</strong> Unlike Step 6 (which found a substantial, already-tested deposit/payment
 * pipeline to bridge a new-chain deal into), there is no pre-existing procurement/purchase-order
 * module anywhere in this codebase. {@code th.co.glr.hr.ticket.FulfilmentStatus} is a bare
 * sequence of string flags with no PO record, no supplier detail, no ETA/ETD, no customs
 * tracking, no landed-cost record, and no linkage to which factory-quote revision was actually
 * used — see {@code V77__factory_purchase_order.sql}'s own header comment for the full account.
 *
 * <p><strong>Source of truth — do not re-pick a factory/price at PO time.</strong> A PO's items
 * are sourced EXCLUSIVELY from the pricing request's currently-APPROVED {@code
 * sales.pricing_decision}'s own {@code pricing_costing_id} — i.e. exactly the costing version
 * that produced the customer's selling price (Step 3) and that the customer has already accepted
 * a quotation against (Steps 4-5). {@link #createPurchaseOrders} never accepts a caller-supplied
 * factory or price; it groups {@code sales.pricing_costing_item} rows by {@code factory_name} and
 * mints one PO per factory, each carrying a FROZEN snapshot of quantity/unit price/currency at
 * creation time — never recomputed later (the costing itself is immutable post-SUBMITTED, so
 * there is nothing to drift from).
 *
 * <p><strong>Gate — deliberately at least as strict as {@code issueImportRequest}'s own, not
 * weaker.</strong> A PO may only be created once BOTH: (1) {@code pricing_request.status ==
 * QUOTATION_ACCEPTED} (Step 5's terminal status — the only status a {@code pricing_costing_item}
 * chain can ever be trusted as "sold", since {@code PricingRequestStatus.ALLOWED} never moves a
 * request past it), AND (2) the deal has already reached {@code DealStage.PROCUREMENT} — i.e.
 * {@code TicketService.issueImportRequest} has already fired for this ticket. The task explicitly
 * asked for a gate "consistent with {@code issueImportRequest}'s own existing precondition, do
 * not invent a weaker gate" — reusing {@code DealStage.PROCUREMENT} (rather than re-deriving
 * {@code issueImportRequest}'s own quotation/deposit boolean logic a second time in a sibling
 * package) is the strictest available reading of that instruction: a PO cannot exist before
 * Import has actually issued the import request for this deal.
 *
 * <p><strong>Independent of {@code markIrSent}/{@code markShipping}/{@code markGoodsReceived}.
 * </strong> Those three existing {@code TicketService} methods are NOT modified and gain NO new
 * precondition from this branch — they remain reachable purely off {@code
 * sales.ticket.fulfillment_status}, exactly as before. This factory-PO record is an optional
 * detail layer alongside that ticket-level flag sequence, not a replacement or a new gate on it —
 * see this class's own package Javadoc note in the branch handoff for the explicit reasoning
 * ("prefer not weakening any existing gate").
 *
 * <p><strong>Confidentiality.</strong> Raw supplier PO detail (price, proforma reference, payment
 * schedule) is Import/CEO territory, reusing the {@code RAW_QUOTE_ROLES}/{@code
 * RAW_DECISION_ROLES} confidentiality pattern already established by {@code FactoryQuoteService}/
 * {@code PricingDecisionService} — {@code sales}/{@code sales_manager} cannot reach any endpoint
 * on this service at all (a stricter cut than those two classes' own per-field filtering, since
 * this branch does not build a separate sales-facing "progress only" view — see the branch
 * handoff's own scope note for why that was left out).
 */
@Service
public class ProcurementService {
    private static final Set<String> RAW_PO_ROLES = Set.of("import", "ceo");

    private final ProcurementRepository purchaseOrders;
    private final PricingRequestRepository pricingRequests;
    private final TicketRepository tickets;
    private final NotificationRepository notifications;

    public ProcurementService(ProcurementRepository purchaseOrders, PricingRequestRepository pricingRequests,
                              TicketRepository tickets, NotificationRepository notifications) {
        this.purchaseOrders = purchaseOrders;
        this.pricingRequests = pricingRequests;
        this.tickets = tickets;
        this.notifications = notifications;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Create — one PO per distinct factory on the pricing request's APPROVED costing.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public List<FactoryPurchaseOrderDto> createPurchaseOrders(long pricingRequestId,
            CreateFactoryPurchaseOrdersRequest request, UserPrincipal actor) {
        requireRole(actor, RAW_PO_ROLES);
        String clientRequestId = validateUuid(request == null ? null : request.clientRequestId());

        // Serializes every create call for this pricing request — same advisory-lock key every
        // earlier step already uses (PricingRequestRepository.lockPricingRequest), reached into
        // directly rather than duplicated, per the established "an orchestrating layer reaching
        // into a sibling aggregate's repository directly" precedent (handoff 95, decision 3).
        pricingRequests.lockPricingRequest(pricingRequestId);

        if (clientRequestId != null) {
            List<FactoryPurchaseOrderDto> replay = purchaseOrders.findByClientRequestId(actor.id(), clientRequestId);
            if (!replay.isEmpty()) {
                return replay;
            }
        }

        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
        if (!PricingRequestStatus.QUOTATION_ACCEPTED.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "สร้างใบสั่งซื้อโรงงานได้เฉพาะใบขอราคาที่ลูกค้ายอมรับใบเสนอราคาแล้วเท่านั้น (ปัจจุบัน: " + summary.status() + ")");
        }
        TicketSummaryDto ticket = requireTicketSummary(summary.ticketId());
        if (!DealStage.PROCUREMENT.equals(ticket.salesStage())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "สร้างใบสั่งซื้อโรงงานได้หลังจากออกคำขอนำเข้า (Import Request) แล้วเท่านั้น (สถานะดีลปัจจุบัน: " + ticket.salesStage() + ")");
        }

        long pricingCostingId = purchaseOrders.findApprovedPricingCostingId(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "ไม่พบราคาต้นทุนที่ได้รับอนุมัติสำหรับใบขอราคานี้"));
        List<CostingItemForPo> items = purchaseOrders.findCostingItemsForPo(pricingCostingId);
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ไม่พบรายการต้นทุนสำหรับสร้างใบสั่งซื้อโรงงาน");
        }

        // Group by factory — one PO per distinct factory (Step 2's own per-factory quote
        // grouping, mirrored here per this class's own Javadoc).
        Map<String, List<CostingItemForPo>> byFactory = new LinkedHashMap<>();
        for (CostingItemForPo item : items) {
            byFactory.computeIfAbsent(item.factoryName(), k -> new ArrayList<>()).add(item);
        }

        List<FactoryPurchaseOrderDto> result = new ArrayList<>();
        for (Map.Entry<String, List<CostingItemForPo>> entry : byFactory.entrySet()) {
            String factoryName = entry.getKey();
            List<CostingItemForPo> factoryItems = entry.getValue();
            long poId = purchaseOrders.findOpenIdByFactory(pricingRequestId, factoryName).orElseGet(() -> {
                Long factoryId = factoryItems.get(0).factoryId();
                String currency = factoryItems.get(0).rawCurrency();
                long created = purchaseOrders.insertPo(pricingRequestId, summary.ticketId(), factoryId, factoryName,
                    currency, clientRequestId, actor.id());
                List<Long> costingItemIds = factoryItems.stream().map(CostingItemForPo::pricingCostingItemId).toList();
                int inserted = purchaseOrders.insertItems(created, pricingRequestId, costingItemIds);
                if (inserted != costingItemIds.size()) {
                    // Every item in factoryItems was read from THIS pricing request's own
                    // approved costing a moment ago, so this can only mean the cross-tenant
                    // guard inside insertItems (pc.pricing_request_id = :pricingRequestId)
                    // rejected a line it should never have been asked to reject, or a
                    // concurrent writer raced this one — either way, refuse rather than leave a
                    // PO with a silently short item list.
                    throw new ApiException(HttpStatus.CONFLICT,
                        "สร้างรายการใบสั่งซื้อโรงงานไม่ครบถ้วน — กรุณาลองใหม่");
                }
                purchaseOrders.recalcTotal(created);
                pricingRequests.addEvent(pricingRequestId, summary.ticketId(), actor.id(), actor.name(),
                    PricingRequestEventKind.FACTORY_PO_CREATED, summary.status(), summary.status(),
                    "สร้างใบสั่งซื้อโรงงาน " + factoryName, null);
                return created;
            });
            purchaseOrders.findById(poId).ifPresent(result::add);
        }
        notifications.notifyByRoleForPricingRequest("ceo", pricingRequestId, PricingRequestEventKind.FACTORY_PO_CREATED,
            "สร้างใบสั่งซื้อโรงงาน " + result.size() + " ฉบับสำหรับใบขอราคา " + summary.requestCode());
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Progress the PO — independent of the ticket-level fulfillment flags.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public FactoryPurchaseOrderDto recordSupplierProforma(long id, RecordSupplierProformaRequest request, UserPrincipal actor) {
        requireRole(actor, RAW_PO_ROLES);
        FactoryPurchaseOrderDto po = requirePo(id);
        int rows = purchaseOrders.recordSupplierProforma(id, request.supplierProformaRef(), request.supplierPaymentScheduleNote());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบสั่งซื้อนี้ปิดแล้ว ไม่สามารถแก้ไขได้");
        }
        logEvent(po, PricingRequestEventKind.FACTORY_PO_PROFORMA_RECORDED, actor,
            "บันทึกใบแจ้งหนี้ล่วงหน้า (Proforma) " + request.supplierProformaRef());
        return requirePo(id);
    }

    @Transactional
    public FactoryPurchaseOrderDto recordShippingDetail(long id, RecordShippingDetailRequest request, UserPrincipal actor) {
        requireRole(actor, RAW_PO_ROLES);
        FactoryPurchaseOrderDto po = requirePo(id);
        int rows = purchaseOrders.recordShippingDetail(id, request.containerRef(), request.etd(), request.eta(), request.customsStatus());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบสั่งซื้อนี้ปิดแล้ว ไม่สามารถแก้ไขได้");
        }
        logEvent(po, PricingRequestEventKind.FACTORY_PO_SHIPPING_RECORDED, actor, "บันทึกรายละเอียดการขนส่ง");
        return requirePo(id);
    }

    /**
     * Step 8: extends the Step 7 goods-received flag flip with the ACTUAL quantity received per
     * line (vs the ordered {@code quantity} already frozen on the PO item), plus a QC/damage
     * note — {@code discrepancyQty} is computed on read (see
     * {@link ProcurementRepository#withItems}), never stored, mirroring
     * {@code estimatedLandedCostPerUnitThb}'s own "estimate vs actual, never conflated"
     * discipline one field over.
     *
     * <p><strong>Backward-compatible default:</strong> {@code request.items()} is optional — a
     * caller that supplies none (every existing caller, including the unmodified Step 7 frontend)
     * gets the exact prior behavior, with every line's {@code qtyReceived} defaulted to its own
     * ordered {@code quantity} (zero discrepancy, nothing to report). A caller that DOES supply
     * items must cover every line on the PO — see {@link RecordGoodsReceivedRequest}'s own
     * Javadoc for why a partial receiving report is rejected outright rather than silently
     * accepted (this is a real business-logic guard, not a formatting nicety: without it, the PO
     * would move to the terminal RECEIVED status while some lines keep {@code qty_received=NULL}
     * forever, which is a worse state than not reporting a discrepancy at all).
     */
    @Transactional
    public FactoryPurchaseOrderDto recordGoodsReceived(long id, RecordGoodsReceivedRequest request, UserPrincipal actor) {
        requireRole(actor, RAW_PO_ROLES);
        FactoryPurchaseOrderDto po = requirePo(id);
        if (FactoryPurchaseOrderStatus.CLOSED.contains(po.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบสั่งซื้อนี้ปิดแล้ว ไม่สามารถบันทึกรับสินค้าได้อีก");
        }
        List<ItemReceiptRequest> items = request.items();
        if (items == null) {
            // Genuinely absent (JSON omits the field, or sends null) — the backward-compatible
            // default. An explicitly-supplied EMPTY list is different: the caller told us
            // something, and zero items never covers a real PO's item set, so it falls into the
            // strict branch below and is correctly rejected as incomplete, not silently defaulted.
            items = po.items().stream()
                .map(item -> new ItemReceiptRequest(item.id(), item.quantity(), null))
                .toList();
        } else {
            // A single set-equality check both rejects an unknown itemId (requestedItemIds would
            // then contain an element validItemIds structurally cannot) AND rejects a partial/
            // incomplete list (a missing element) — for two finite sets, "contains something the
            // other doesn't" and "not equal" are the same fact. An earlier draft had a separate
            // per-id "unknown item" loop ahead of this check; mutation-testing it (see the branch
            // handoff's own Authz/Guard Evidence) proved it could NEVER independently reject
            // anything this check wouldn't already catch on its own, in any fixture — not merely
            // hard to isolate with the tests at hand, but a set-theory tautology, so it was
            // removed rather than kept as "unverified-but-harmless" dead code.
            Set<Long> validItemIds = po.items().stream().map(FactoryPurchaseOrderItemDto::id).collect(Collectors.toSet());
            Set<Long> requestedItemIds = items.stream().map(ItemReceiptRequest::itemId).collect(Collectors.toSet());
            if (!requestedItemIds.equals(validItemIds)) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ต้องระบุจำนวนรับสินค้าให้ครบทุกรายการในใบสั่งซื้อนี้ และรายการที่ระบุต้องอยู่ในใบสั่งซื้อนี้เท่านั้น");
            }
        }
        for (ItemReceiptRequest item : items) {
            purchaseOrders.recordItemReceipt(id, item.itemId(), item.qtyReceived(), item.qcNote());
        }
        int rows = purchaseOrders.recordGoodsReceived(id, request.actualLandedCostThb());
        if (rows == 0) {
            // Lost a race against another recordGoodsReceived/cancel call between the status
            // check above and this compare-and-set — the per-item writes above are harmless to
            // have run (they only ever touch qty_received/qc_note, never status), the PO simply
            // stays wherever the concurrent call left it.
            throw new ApiException(HttpStatus.CONFLICT, "ใบสั่งซื้อนี้ปิดแล้ว ไม่สามารถบันทึกรับสินค้าได้อีก");
        }
        logEvent(po, PricingRequestEventKind.FACTORY_PO_GOODS_RECEIVED, actor,
            "รับสินค้าแล้ว ต้นทุนนำเข้าจริง " + request.actualLandedCostThb());
        return requirePo(id);
    }

    @Transactional
    public FactoryPurchaseOrderDto cancel(long id, CancelFactoryPurchaseOrderRequest request, UserPrincipal actor) {
        requireRole(actor, RAW_PO_ROLES);
        FactoryPurchaseOrderDto po = requirePo(id);
        int rows = purchaseOrders.cancel(id, request.reason());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบสั่งซื้อนี้ปิดแล้ว ไม่สามารถยกเลิกได้");
        }
        logEvent(po, PricingRequestEventKind.FACTORY_PO_CANCELLED, actor, "ยกเลิกใบสั่งซื้อโรงงาน: " + request.reason());
        return requirePo(id);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Reads.
    // ─────────────────────────────────────────────────────────────────────────────────────

    public FactoryPurchaseOrderDto get(long id, UserPrincipal actor) {
        requireRole(actor, RAW_PO_ROLES);
        return requirePo(id);
    }

    public List<FactoryPurchaseOrderDto> listForPricingRequest(long pricingRequestId, UserPrincipal actor) {
        requireRole(actor, RAW_PO_ROLES);
        return purchaseOrders.findByPricingRequest(pricingRequestId);
    }

    public List<FactoryPurchaseOrderDto> list(String status, UserPrincipal actor) {
        requireRole(actor, RAW_PO_ROLES);
        return purchaseOrders.findAll(status);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private void logEvent(FactoryPurchaseOrderDto po, String eventKind, UserPrincipal actor, String message) {
        pricingRequests.addEvent(po.pricingRequestId(), po.ticketId(), actor.id(), actor.name(),
            eventKind, null, null, message + " (" + po.poNumber() + ")", null);
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
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

    private FactoryPurchaseOrderDto requirePo(long id) {
        return purchaseOrders.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Factory purchase order not found"));
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
