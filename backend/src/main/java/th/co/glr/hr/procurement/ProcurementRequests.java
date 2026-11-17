package th.co.glr.hr.procurement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ProcurementRequests {
    private ProcurementRequests() {}

    /** Mirrors every prior step's {@code clientRequestId}-idempotent action request shape. */
    public record CreateFactoryPurchaseOrdersRequest(String clientRequestId) {}

    public record RecordSupplierProformaRequest(
        @NotBlank String supplierProformaRef,
        String supplierPaymentScheduleNote
    ) {}

    /** Every field optional and independently settable — Import may record container/shipment detail, ETD/ETA, and
     * customs status in whatever order and combination the real shipment gives them, rather than a single
     * all-or-nothing call. */
    public record RecordShippingDetailRequest(
        String containerRef,
        LocalDate etd,
        LocalDate eta,
        String customsStatus
    ) {}

    /** Step 8: items is OPTIONAL and backward-compatible — null, which is exactly what every existing caller sends,
     * keeps getting the exact prior behavior: the PO simply moves to RECEIVED with no per-line discrepancy
     * recorded. */
    public record RecordGoodsReceivedRequest(
        @NotNull @DecimalMin("0") BigDecimal actualLandedCostThb,
        @Valid List<ItemReceiptRequest> items
    ) {}

    public record ItemReceiptRequest(
        @NotNull Long itemId,
        @NotNull @DecimalMin("0") BigDecimal qtyReceived,
        String qcNote
    ) {}

    public record CancelFactoryPurchaseOrderRequest(@NotBlank String reason) {}
}
