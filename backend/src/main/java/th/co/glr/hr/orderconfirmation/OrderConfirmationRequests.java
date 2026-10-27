package th.co.glr.hr.orderconfirmation;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

public final class OrderConfirmationRequests {
    private OrderConfirmationRequests() {}

    /** Mirrors every prior step's {@code clientRequestId}-idempotent action request shape. */
    public record ConfirmOrderRequest(String clientRequestId) {}

    /**
     * {@code depositPercent} is never read (owner ruling 2026-10-23): the draft bills the percent
     * the accepted quotation's PDF printed. Deleting the field would refuse no client, because
     * unknown JSON fields are ignored; while it stays, its bounds still answer 400 to a stale
     * client that sends a value outside 0-1.
     */
    public record CreateDepositNoticeFromQuotationRequest(
        @DecimalMin("0") @DecimalMax("1") BigDecimal depositPercent
    ) {}
}
