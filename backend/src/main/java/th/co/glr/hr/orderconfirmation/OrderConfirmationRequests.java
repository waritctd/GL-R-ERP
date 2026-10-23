package th.co.glr.hr.orderconfirmation;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

public final class OrderConfirmationRequests {
    private OrderConfirmationRequests() {}

    /** Mirrors every prior step's {@code clientRequestId}-idempotent action request shape. */
    public record ConfirmOrderRequest(String clientRequestId) {}

    /**
     * {@code depositPercent} is accepted and never read (owner ruling 2026-10-23): the draft bills
     * the percent the accepted quotation's PDF printed. Kept so an older frontend build that still
     * sends it is not refused.
     */
    public record CreateDepositNoticeFromQuotationRequest(
        @DecimalMin("0") @DecimalMax("1") BigDecimal depositPercent
    ) {}
}
