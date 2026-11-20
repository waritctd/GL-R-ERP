package th.co.glr.hr.specialmoney;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Pure unit tests for SpecialMoneyPolicyEvaluator -- no Mockito, no Postgres, just plain function calls
 * against hand-built inputs. Matches the 2018 welfare-policy seed in V66__special_money_request_schema.sql. */
class SpecialMoneyPolicyEvaluatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 20);
    private static final SpecialMoneyPolicyEvaluator EVALUATOR = new SpecialMoneyPolicyEvaluator();
    private static final Set<String> EXCLUDED_PROVINCES =
        Set.of("สมุทรปราการ", "สมุทรสาคร", "นนทบุรี", "นครปฐม", "ปทุมธานี", "ฉะเชิงเทรา", "ลาดกระบัง");

    // Helpers

    private static Map<String, BigDecimal> defaultAmountsMap() {
        Map<String, BigDecimal> m = new HashMap<>();
        m.put("cap", bd(3000)); // overridden per test as needed; MEDICAL/AID both read "cap"
        m.put("per_piece_shirt", bd(300));
        m.put("per_piece_trouser", bd(350));
        m.put("max_pieces", bd(4));
        m.put("tshirt", bd(220));
        m.put("tshirt_qty", bd(3));
        m.put("trouser", bd(300));
        m.put("trouser_qty", bd(3));
        m.put("shoes", bd(400));
        m.put("shoes_qty", bd(1));
        m.put("belt", bd(700));
        m.put("belt_qty", bd(1));
        m.put("rate_driver", bd(400));
        m.put("rate_loader", bd(200));
        m.put("rate_asia", bd(600));
        m.put("rate_other", bd(800));
        return m;
    }

    private static PolicyAmounts amounts(Map<String, BigDecimal> overrides) {
        Map<String, BigDecimal> m = defaultAmountsMap();
        m.putAll(overrides);
        return new PolicyAmounts(m, 1);
    }

    /** The department code is a VARCHAR (hr.department.source_code) carried in special_money_policy.text_value, not
     * in the numeric amount column — it need not be numeric. */
    /** Configures the kit's eligible DEPARTMENT list (V129 replaced the old singular key). */
    private static PolicyAmounts amountsWithConfiguredSalesSupportCode(String code) {
        return new PolicyAmounts(
            defaultAmountsMap(), Map.of("preprobation_kit_department_codes", code), 1);
    }

    private static EmployeeEligibilitySnapshot activeEmployeePastProbation() {
        return new EmployeeEligibilitySnapshot(1L, TODAY.minusYears(5), TODAY.minusYears(5), null, "010", null, true, TODAY);
    }

    private static UsageSnapshot noUsage() {
        return new UsageSnapshot(Map.of(), Map.of(), Map.of());
    }

    private static BigDecimal bd(int value) {
        return BigDecimal.valueOf(value);
    }

    // MEDICAL

    @Test
    void medicalOverYtdCapClampsToRemainingBalance() {
        UsageSnapshot usage =
            new UsageSnapshot(
                new EnumMap<>(Map.of(SpecialMoneyType.MEDICAL, bd(2000))), Map.of(), Map.of());
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(
                1L, TODAY, null, TODAY.minusDays(5), BigDecimal.ONE, bd(1500), "clinic visit", Map.of());

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.MEDICAL,
                request,
                activeEmployeePastProbation(),
                usage,
                amounts(Map.of()),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).anyMatch(v -> v.contains("วงเงินคงเหลือ"));
        assertThat(decision.eligibleAmount()).isEqualByComparingTo(bd(1000));
    }

    @Test
    void medicalReceiptOlderThanOneMonthRejected() {
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(
                1L, TODAY, null, TODAY.minusMonths(2), BigDecimal.ONE, bd(500), "clinic visit", Map.of());

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.MEDICAL,
                request,
                activeEmployeePastProbation(),
                noUsage(),
                amounts(Map.of()),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).anyMatch(v -> v.contains("เกินหนึ่งเดือน"));
    }

    @Test
    void probationerRejectedForMedicalButAcceptedForTheKit() {
        EmployeeEligibilitySnapshot probationer =
            new EmployeeEligibilitySnapshot(2L, TODAY.minusDays(30), null, null, "17", null, true, TODAY);

        SubmitSpecialMoneyRequest medicalRequest =
            new SubmitSpecialMoneyRequest(
                2L, TODAY, null, TODAY.minusDays(2), BigDecimal.ONE, bd(500), "clinic visit", Map.of());
        PolicyDecision medicalDecision =
            EVALUATOR.evaluate(
                SpecialMoneyType.MEDICAL,
                medicalRequest,
                probationer,
                noUsage(),
                amounts(Map.of()),
                EXCLUDED_PROVINCES);
        assertThat(medicalDecision.violations()).anyMatch(v -> v.contains("ยังไม่พ้นทดลองงาน"));

        SubmitSpecialMoneyRequest kitRequest =
            new SubmitSpecialMoneyRequest(
                2L, TODAY, null, null, BigDecimal.ONE, bd(2660), "pre-probation kit", Map.of());
        PolicyDecision kitDecision =
            EVALUATOR.evaluate(
                SpecialMoneyType.UNIFORM_PREPROBATION_KIT,
                kitRequest,
                probationer,
                noUsage(),
                amountsWithConfiguredSalesSupportCode("17"),
                EXCLUDED_PROVINCES);
        assertThat(kitDecision.violations()).isEmpty();
    }

    // Shared probation resolution (owner ruling, 2026-08-03): confirm_date, when present, is authoritative and the
    // employee is eligible the day AFTER it -- NOT on confirm_date itself.

    @Test
    void confirmDateBoundaryIsNotEligibleOnConfirmDateButEligibleTheDayAfter() {
        LocalDate confirmDate = TODAY.minusDays(10);

        EmployeeEligibilitySnapshot onConfirmDate =
            new EmployeeEligibilitySnapshot(2L, TODAY.minusYears(1), confirmDate, null, "010", null, true, confirmDate);
        EmployeeEligibilitySnapshot dayAfterConfirmDate =
            new EmployeeEligibilitySnapshot(
                2L, TODAY.minusYears(1), confirmDate, null, "010", null, true, confirmDate.plusDays(1));

        PolicyDecision onConfirmDateDecision =
            EVALUATOR.evaluate(
                SpecialMoneyType.MEDICAL,
                new SubmitSpecialMoneyRequest(
                    2L, confirmDate, null, confirmDate, BigDecimal.ONE, bd(500), "clinic visit", Map.of()),
                onConfirmDate,
                noUsage(),
                amounts(Map.of()),
                EXCLUDED_PROVINCES);
        assertThat(onConfirmDateDecision.violations()).anyMatch(v -> v.contains("ยังไม่พ้นทดลองงาน"));

        PolicyDecision dayAfterDecision =
            EVALUATOR.evaluate(
                SpecialMoneyType.MEDICAL,
                new SubmitSpecialMoneyRequest(
                    2L, confirmDate.plusDays(1), null, confirmDate.plusDays(1), BigDecimal.ONE, bd(500),
                    "clinic visit", Map.of()),
                dayAfterConfirmDate,
                noUsage(),
                amounts(Map.of()),
                EXCLUDED_PROVINCES);
        assertThat(dayAfterDecision.violations()).noneMatch(v -> v.contains("ยังไม่พ้นทดลองงาน"));
    }

    @Test
    void hasPassedProbationFailsClosedWhenHireDateAndConfirmDateAreBothNull() {
        assertThat(SpecialMoneyPolicyEvaluator.hasPassedProbation(null, 90, null, TODAY)).isFalse();
    }

    // UNIFORM_ANNUAL

    @Test
    void uniformSelfBuyCappedAt300PerShirtAndFourPieces() {
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(
                1L,
                LocalDate.of(2026, 6, 5),
                null,
                LocalDate.of(2026, 5, 10),
                bd(5),
                bd(1500),
                "self-buy shirts",
                Map.of("uniformMode", "SELF_BUY", "shirtCount", "5", "trouserCount", "0"));

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.UNIFORM_ANNUAL,
                request,
                activeEmployeePastProbation(),
                noUsage(),
                amounts(Map.of()),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).anyMatch(v -> v.contains("ไม่เกิน 4 ชิ้น"));
        assertThat(decision.eligibleAmount()).isEqualByComparingTo(bd(1200));
    }

    // UNIFORM_NEW_STAFF (§2.1.2)

    @Test
    void preprobationKitRejectedBeforeDaySeven() {
        EmployeeEligibilitySnapshot employee =
            new EmployeeEligibilitySnapshot(3L, TODAY.minusDays(3), null, null, "17", null, true, TODAY);
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(3L, TODAY, null, null, BigDecimal.ONE, bd(2660), "kit",
                Map.of("needsBackSupport", "true"));

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.UNIFORM_PREPROBATION_KIT,
                request,
                employee,
                noUsage(),
                amountsWithConfiguredSalesSupportCode("17"),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).anyMatch(v -> v.contains("ยังทำงานไม่ครบ 7 วัน"));
    }

    // AID_*

    @Test
    void weddingRejectedWhenAlreadyClaimedOnce() {
        UsageSnapshot usage =
            new UsageSnapshot(Map.of(), new EnumMap<>(Map.of(SpecialMoneyType.AID_WEDDING, 1)), Map.of());
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(
                1L, TODAY.minusDays(10), null, null, BigDecimal.ONE, bd(5000), "wedding aid", Map.of());

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.AID_WEDDING,
                request,
                activeEmployeePastProbation(),
                usage,
                amounts(Map.of("cap", bd(5000))),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).anyMatch(v -> v.contains("เบิกได้เพียงครั้งเดียว"));
    }

    // TRAVEL_PER_DIEM

    @Test
    void perDiemPays400PerDayForADriver() {
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(
                1L, TODAY, TODAY, null, BigDecimal.ONE, bd(400), "trip",
                Map.of("destination", "DOMESTIC", "province", "เชียงใหม่", "role", "driver"));

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.TRAVEL_PER_DIEM,
                request,
                activeEmployeePastProbation(),
                noUsage(),
                amounts(Map.of()),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).isEmpty();
        assertThat(decision.eligibleAmount()).isEqualByComparingTo(bd(400));
    }

    // Universal safety net

    /** Catches a new SpecialMoneyType added without wiring an eligibility rule: no matter the cap-rule shape, an
     * inactive employee must never be found eligible. */
    @ParameterizedTest
    @EnumSource(SpecialMoneyType.class)
    void inactiveEmployeeAlwaysRejected(SpecialMoneyType type) {
        EmployeeEligibilitySnapshot inactiveEmployee =
            new EmployeeEligibilitySnapshot(9L, null, null, null, null, null, false, TODAY);
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(9L, TODAY, null, TODAY, BigDecimal.ONE, bd(100), "test", Map.of());

        PolicyDecision decision =
            EVALUATOR.evaluate(
                type, request, inactiveEmployee, noUsage(), amountsWithConfiguredSalesSupportCode("17"), EXCLUDED_PROVINCES);

        assertThat(decision.violations()).anyMatch(v -> v.contains("ไม่ได้อยู่ในสถานะทำงาน"));
    }
}
