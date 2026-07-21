package th.co.glr.hr.procurement;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

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

    public record RecordGoodsReceivedRequest(
        @NotNull @DecimalMin("0") BigDecimal actualLandedCostThb
    ) {}

    public record CancelFactoryPurchaseOrderRequest(@NotBlank String reason) {}
}
