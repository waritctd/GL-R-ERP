package th.co.glr.hr.customerquotation;

import java.math.BigDecimal;
import java.time.Instant;

public final class DiscountApprovalDtos {
    private DiscountApprovalDtos() {}

    /**
     * One row of {@code sales.quotation_item_discount_approval} — see that table's own comment
     * (V155) for the append-only, price-bound state machine. {@code requestedFinalUnitPrice} is
     * fixed forever once the row exists; {@code approvedFinalUnitPrice} is set ONLY on APPROVED
     * and is always equal to {@code requestedFinalUnitPrice} at the moment of approval — a
     * distinct column (not merely inferred) so {@code CustomerQuotationService}'s issue() gate
     * can ask "was THIS price approved" directly. {@code rejectionReason} is non-null (and
     * non-blank) if and only if {@code status} is REJECTED.
     *
     * <p>{@code quotationNumber}/{@code itemDescription} are display-only conveniences joined in
     * by {@link DiscountApprovalRepository} for the CEO queue and the per-quotation panel, so
     * neither the frontend nor the CEO has to make a second round trip to show "which line, on
     * which quotation" — never written by this feature, only read.
     */
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
