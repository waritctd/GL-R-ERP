package th.co.glr.hr.orderconfirmation;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

public final class OrderConfirmationRequests {
    private OrderConfirmationRequests() {}

    /** Mirrors every prior step's {@code clientRequestId}-idempotent action request shape. */
    public record ConfirmOrderRequest(String clientRequestId) {}

    /**
     * {@code depositPercent} is optional — {@code null} lets {@code DepositNoticeService
     * .createDraft}/{@code DepositNoticeRepository.createDraft} apply their own existing 50%
     * default, exactly as the legacy deposit-notice draft flow already does.
     */
    public record CreateDepositNoticeFromQuotationRequest(
        @DecimalMin("0") @DecimalMax("1") BigDecimal depositPercent
    ) {}
}
