package th.co.glr.hr.customerquotation;

import java.math.BigDecimal;
import java.time.Instant;

public final class DiscountApprovalDtos {
    private DiscountApprovalDtos() {}

    /** One row of sales.quotation_item_discount_approval — see that table's own comment (V155) for the append-only,
     * price-bound state machine. rejectionReason is non-null (and non-blank) if and only if status is REJECTED. */
    public record DiscountApprovalDto(
        long id,
        long quotationItemId,
        long quotationId,
        long pricingRequestId,
        String quotationNumber,
        String itemDescription,
        String status,
        BigDecimal requestedFinalUnitPrice,
        long requestedBy,
        String requestedByName,
        Instant requestedAt,
        Long decidedBy,
        String decidedByName,
        Instant decidedAt,
        BigDecimal approvedFinalUnitPrice,
        String rejectionReason
    ) {}
}
