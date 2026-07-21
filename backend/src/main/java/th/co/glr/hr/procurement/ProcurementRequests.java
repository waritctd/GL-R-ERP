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

    /**
     * Every field optional and independently settable — Import may record container/shipment
     * detail, ETD/ETA, and customs status in whatever order and combination the real shipment
     * gives them, rather than a single all-or-nothing call.
     */
    public record RecordShippingDetailRequest(
        String containerRef,
        LocalDate etd,
        LocalDate eta,
        String customsStatus
    ) {}

    /**
     * Step 8: {@code items} is OPTIONAL and backward-compatible — {@code null} (the field
     * omitted from the JSON body entirely, or sent as JSON {@code null}), which is exactly what
     * every existing caller sends (including the Step 7 frontend, unchanged by this branch),
     * keeps getting the exact prior behavior: the PO simply moves to RECEIVED with no per-line
     * discrepancy recorded (see {@code ProcurementService#recordGoodsReceived}'s own Javadoc for
     * the default-fill). An explicitly-supplied list — even an EMPTY one — is treated as real
     * caller intent and must cover EVERY line on the PO, or the call is rejected outright: a
     * receiving event that only reports some lines while flipping the whole PO to the terminal
     * RECEIVED status would leave the rest with no {@code qty_received} at all, which is worse
     * than not reporting a discrepancy.
     */
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
