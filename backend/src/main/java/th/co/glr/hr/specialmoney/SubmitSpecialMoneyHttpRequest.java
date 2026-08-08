package th.co.glr.hr.specialmoney;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * HTTP body for {@code POST /api/special-money}. {@link SubmitSpecialMoneyRequest} (slice 1) does
 * not carry the request type -- {@link SpecialMoneyPolicyEvaluator#evaluate} takes {@link
 * SpecialMoneyType} as a separate argument by design, so the type travels alongside the rest of the
 * submission here rather than being added to the slice-1 record.
 *
 * <p><b>{@code eventDate} is deliberately unbounded -- {@code @NotNull} only, no future-date or
 * past-date constraint.</b> This is a considered choice, not a gap: a wedding, ordination, or
 * planned travel may legitimately be filed ahead of the event, and a genuinely backdated medical
 * receipt is normal (the welfare document's own deadline for that type is stated relative to the
 * receipt, in {@link SpecialMoneyPolicyEvaluator#evaluateMedical}, not relative to {@code
 * eventDate}). Do NOT "fix" this by adding a blanket future-date rejection here -- that breaks the
 * legitimate advance-filing flow above. {@code eventDate} is, however, never trusted to key which
 * YEAR an annual welfare cap draws down: see {@code SpecialMoneyService#usageYear}'s Javadoc for
 * why, and for what the cap keys on instead.
 */
public record SubmitSpecialMoneyHttpRequest(
    @NotBlank String requestType,
    Long employeeId,
    @NotNull LocalDate eventDate,
    LocalDate eventEndDate,
    LocalDate receiptDate,
    BigDecimal quantity,
    @NotNull BigDecimal requestedAmount,
    @NotBlank @Size(max = 2000) String reason,
    Map<String, String> detail) {

    public SubmitSpecialMoneyRequest toDomain() {
        return new SubmitSpecialMoneyRequest(
            employeeId,
            eventDate,
            eventEndDate,
            receiptDate,
            quantity == null ? BigDecimal.ONE : quantity,
            requestedAmount,
            reason,
            detail);
    }
}
