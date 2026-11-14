package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** The submitted request data that SpecialMoneyPolicyEvaluator needs, independent of the (not-yet-built) HTTP
 * layer. */
public record SubmitSpecialMoneyRequest(
    Long employeeId,
    LocalDate eventDate,
    LocalDate eventEndDate,
    LocalDate receiptDate,
    BigDecimal quantity,
    BigDecimal requestedAmount,
    String reason,
    Map<String, String> detail) {

    public String detailValue(String key) {
        return detail == null ? null : detail.get(key);
    }
}
