package th.co.glr.hr.specialmoney;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** HTTP body for POST /api/special-money. eventDate is deliberately unbounded -- @NotNull only, no future-date
 * or past-date constraint. Do NOT "fix" this by adding a blanket future-date rejection here -- that breaks the
 * legitimate advance-filing flow above. */
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
