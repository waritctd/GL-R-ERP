package th.co.glr.hr.procurement;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.procurement.ProcurementDtos.FactoryPurchaseOrderDto;
import th.co.glr.hr.procurement.ProcurementRequests.CancelFactoryPurchaseOrderRequest;
import th.co.glr.hr.procurement.ProcurementRequests.CreateFactoryPurchaseOrdersRequest;
import th.co.glr.hr.procurement.ProcurementRequests.RecordGoodsReceivedRequest;
import th.co.glr.hr.procurement.ProcurementRequests.RecordShippingDetailRequest;
import th.co.glr.hr.procurement.ProcurementRequests.RecordSupplierProformaRequest;

/**
 * Step 7 endpoints. The create route is pricing-request-scoped (mirrors {@code
 * OrderConfirmationController}/{@code CustomerQuotationController}'s own {@code
 * /pricing-requests/{id}/...} create routes); every other route acts on the PO's own id, mirroring
 * {@code /api/factory-quotes/{id}/...}.
 */
@RestController
@RequestMapping("/api")
public class ProcurementController {
    private final ProcurementService procurement;
    private final SessionContext sessions;

    public ProcurementController(ProcurementService procurement, SessionContext sessions) {
        this.procurement = procurement;
        this.sessions = sessions;
    }

    @PostMapping("/pricing-requests/{id}/factory-purchase-orders")
    Map<String, List<FactoryPurchaseOrderDto>> create(@PathVariable long id,
            @RequestBody(required = false) CreateFactoryPurchaseOrdersRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        List<FactoryPurchaseOrderDto> created = procurement.createPurchaseOrders(
            id, request != null ? request : new CreateFactoryPurchaseOrdersRequest(null), user);
        return Map.of("factoryPurchaseOrders", created);
    }

    @GetMapping("/pricing-requests/{id}/factory-purchase-orders")
    Map<String, List<FactoryPurchaseOrderDto>> listForPricingRequest(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("factoryPurchaseOrders", procurement.listForPricingRequest(id, user));
    }

    @GetMapping("/factory-purchase-orders")
    Map<String, List<FactoryPurchaseOrderDto>> list(@RequestParam(required = false) String status, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("factoryPurchaseOrders", procurement.list(status, user));
    }

    @GetMapping("/factory-purchase-orders/{id}")
    Map<String, FactoryPurchaseOrderDto> get(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("factoryPurchaseOrder", procurement.get(id, user));
    }

    @PostMapping("/factory-purchase-orders/{id}/proforma")
    Map<String, FactoryPurchaseOrderDto> recordSupplierProforma(@PathVariable long id,
            @Valid @RequestBody RecordSupplierProformaRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("factoryPurchaseOrder", procurement.recordSupplierProforma(id, request, user));
    }

    @PostMapping("/factory-purchase-orders/{id}/shipping")
    Map<String, FactoryPurchaseOrderDto> recordShippingDetail(@PathVariable long id,
            @RequestBody(required = false) RecordShippingDetailRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("factoryPurchaseOrder", procurement.recordShippingDetail(
            id, request != null ? request : new RecordShippingDetailRequest(null, null, null, null), user));
    }

    @PostMapping("/factory-purchase-orders/{id}/goods-received")
    Map<String, FactoryPurchaseOrderDto> recordGoodsReceived(@PathVariable long id,
            @Valid @RequestBody RecordGoodsReceivedRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("factoryPurchaseOrder", procurement.recordGoodsReceived(id, request, user));
    }

    @PostMapping("/factory-purchase-orders/{id}/cancel")
    Map<String, FactoryPurchaseOrderDto> cancel(@PathVariable long id,
            @Valid @RequestBody CancelFactoryPurchaseOrderRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("factoryPurchaseOrder", procurement.cancel(id, request, user));
    }
}
