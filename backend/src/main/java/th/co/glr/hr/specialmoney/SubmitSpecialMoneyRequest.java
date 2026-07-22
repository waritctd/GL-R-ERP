package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * The submitted request data that {@link SpecialMoneyPolicyEvaluator} needs, independent of the
 * (not-yet-built) HTTP layer. {@code detail} carries type-specific fields as plain strings, since
 * it mirrors {@code hr.special_money_request.detail jsonb}:
 *
 * <ul>
 *   <li>{@code TRAVEL_PER_DIEM}: {@code destination} ({@code DOMESTIC}|{@code OVERSEAS}), {@code
 *       province} (domestic only), {@code role} ({@code driver}|{@code loader}, domestic only),
 *       {@code region} ({@code ASIA}|other, overseas only)
 *   <li>{@code UNIFORM_ANNUAL}: {@code uniformMode} ({@code TAILORED}|{@code SELF_BUY}), {@code
 *       shirtCount}, {@code trouserCount} (self-buy only)
 *   <li>{@code AID_FUNERAL}: {@code relation}
 * </ul>
 */
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
