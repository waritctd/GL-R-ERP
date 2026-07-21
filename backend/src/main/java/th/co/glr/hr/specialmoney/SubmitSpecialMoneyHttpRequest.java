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
