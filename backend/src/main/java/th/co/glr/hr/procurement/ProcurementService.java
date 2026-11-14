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
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketSummaryDto;

/** Step 7 of the sales pricing-flow redesign: Factory Purchase Order and Import Execution. Source of truth — do
 * not re-pick a factory/price at PO time. This step genuinely builds new entities — it is not a bridge into
 * existing machinery. */
@Service
public class ProcurementService {
    private static final Set<String> RAW_PO_ROLES = Set.of("import", "ceo");

    private final ProcurementRepository purchaseOrders;
    private final PricingRequestRepository pricingRequests;
    private final TicketRepository tickets;
    private final NotificationRepository notifications;
    private final TicketService ticketService;

    public ProcurementService(ProcurementRepository purchaseOrders, PricingRequestRepository pricingRequests,
                              TicketRepository tickets, NotificationRepository notifications,
                              TicketService ticketService) {
        this.purchaseOrders = purchaseOrders;
        this.pricingRequests = pricingRequests;
        this.tickets = tickets;
        this.notifications = notifications;
        this.ticketService = ticketService;
    }

    // Create — one PO per distinct factory on the pricing request's APPROVED costing.

    @Transactional
    public List<FactoryPurchaseOrderDto> createPurchaseOrders(long pricingRequestId,
            CreateFactoryPurchaseOrdersRequest request, UserPrincipal actor) {
        requireRole(actor, RAW_PO_ROLES);
        String clientRequestId = validateUuid(request == null ? null : request.clientRequestId());

        // Serializes every create call for this pricing request — same advisory-lock key every earlier step already
        // uses, reached into directly rather than duplicated, per the established "an orchestrating layer reaching
        // into a sibling aggregate's repository directly" precedent.
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
                "สร้างใบสั่งซื้อโรงงานได้เฉพาะคำขอราคาที่ลูกค้ายอมรับใบเสนอราคาแล้วเท่านั้น (ปัจจุบัน: " + summary.status() + ")");
        }
        TicketSummaryDto ticket = requireTicketSummary(summary.ticketId());
        if (!DealStage.PROCUREMENT.equals(ticket.salesStage())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "สร้างใบสั่งซื้อโรงงานได้หลังจากออกคำขอนำเข้า (Import Request) แล้วเท่านั้น (สถานะดีลปัจจุบัน: " + ticket.salesStage() + ")");
        }

        long pricingCostingId = purchaseOrders.findApprovedPricingCostingId(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "ไม่พบราคาต้นทุนที่ได้รับอนุมัติสำหรับคำขอราคานี้"));
        List<CostingItemForPo> items = purchaseOrders.findCostingItemsForPo(pricingCostingId);
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ไม่พบรายการต้นทุนสำหรับสร้างใบสั่งซื้อโรงงาน");
        }

        // Group by factory — one PO per distinct factory (Step 2's own per-factory quote grouping, mirrored here per
        // this class's own Javadoc).
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
                    // Every item in factoryItems was read from THIS pricing request's own approved costing a moment ago, so this
                    // can only mean the cross-tenant guard inside insertItems rejected a line it should never have been asked to
                    // reject, or a concurrent writer raced this one.
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
            "สร้างใบสั่งซื้อโรงงาน " + result.size() + " ฉบับสำหรับคำขอราคา " + summary.requestCode());
        return result;
    }

    // Progress the PO — independent of the ticket-level fulfillment flags.

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
        ticketService.applyPurchaseOrderRollup(po.ticketId(), actor);
        return requirePo(id);
    }

    /** Step 8: extends the Step 7 goods-received flag flip with the ACTUAL quantity received per line, plus a
     * QC/damage note — discrepancyQty is computed on read, never stored, mirroring estimatedLandedCostPerUnitThb's
     * own "estimate vs actual, never conflated" discipline one field over. */
    @Transactional
    public FactoryPurchaseOrderDto recordGoodsReceived(long id, RecordGoodsReceivedRequest request, UserPrincipal actor) {
        requireRole(actor, RAW_PO_ROLES);
        FactoryPurchaseOrderDto po = requirePo(id);
        if (FactoryPurchaseOrderStatus.CLOSED.contains(po.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบสั่งซื้อนี้ปิดแล้ว ไม่สามารถบันทึกรับสินค้าได้อีก");
        }
        List<ItemReceiptRequest> items = request.items();
        if (items == null) {
            // Genuinely absent (JSON omits the field, or sends null) — the backward-compatible default.
            items = po.items().stream()
                .map(item -> new ItemReceiptRequest(item.id(), item.quantity(), null))
                .toList();
        } else {
            // A single set-equality check both rejects an unknown itemId (requestedItemIds would then contain an element
            // validItemIds structurally cannot) AND rejects a partial/ incomplete list (a missing element) — for two
            // finite sets, "contains something the other doesn't" and "not equal" are the same fact.
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
            // Lost a race against another recordGoodsReceived/cancel call between the status check above and this
            // compare-and-set — the per-item writes above are harmless to have run (they only ever touch
            // qty_received/qc_note, never status), the PO simply stays wherever the concurrent call left it.
            throw new ApiException(HttpStatus.CONFLICT, "ใบสั่งซื้อนี้ปิดแล้ว ไม่สามารถบันทึกรับสินค้าได้อีก");
        }
        logEvent(po, PricingRequestEventKind.FACTORY_PO_GOODS_RECEIVED, actor,
            "รับสินค้าแล้ว ต้นทุนนำเข้าจริง " + request.actualLandedCostThb());
        ticketService.applyPurchaseOrderRollup(po.ticketId(), actor);
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
        // Cancelling the last not-yet-received PO can itself COMPLETE the rollup — e.g. PO A RECEIVED + PO B just
        // CANCELLED now means every remaining LIVE PO is RECEIVED. Do not skip this call on the cancel path just
        // because "cancel" sounds like it only ever subtracts.
        ticketService.applyPurchaseOrderRollup(po.ticketId(), actor);
        return requirePo(id);
    }

    // Reads.

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

    // Helpers.

    private void logEvent(FactoryPurchaseOrderDto po, String eventKind, UserPrincipal actor, String message) {
        pricingRequests.addEvent(po.pricingRequestId(), po.ticketId(), actor.id(), actor.name(),
            eventKind, null, null, message + " (" + po.poNumber() + ")", null);
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
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

    private FactoryPurchaseOrderDto requirePo(long id) {
        return purchaseOrders.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบสั่งซื้อโรงงานนี้"));
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
