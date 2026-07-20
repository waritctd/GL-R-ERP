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

/**
 * Pure unit tests for {@link SpecialMoneyPolicyEvaluator} -- no Mockito, no Postgres, just plain
 * function calls against hand-built inputs. Matches the 2018 welfare-policy seed in {@code
 * V66__special_money_request_schema.sql}.
 */
class SpecialMoneyPolicyEvaluatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 20);
    private static final SpecialMoneyPolicyEvaluator EVALUATOR = new SpecialMoneyPolicyEvaluator();
    private static final Set<String> EXCLUDED_PROVINCES =
        Set.of("สมุทรปราการ", "สมุทรสาคร", "นนทบุรี", "นครปฐม", "ปทุมธานี", "ฉะเชิงเทรา", "ลาดกระบัง");

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

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

    /**
     * The department code is a VARCHAR (hr.department.source_code) carried in
     * special_money_policy.text_value, not in the numeric amount column — it need not be numeric.
     */
    private static PolicyAmounts amountsWithConfiguredSalesSupportCode(String code) {
        return new PolicyAmounts(
            defaultAmountsMap(), Map.of("sales_support_department_code", code), 1);
    }

    private static EmployeeEligibilitySnapshot activeEmployeePastProbation() {
        return new EmployeeEligibilitySnapshot(1L, TODAY.minusYears(5), TODAY.minusYears(5), null, "010", true, TODAY);
    }

    private static UsageSnapshot noUsage() {
        return new UsageSnapshot(Map.of(), Map.of());
    }

    private static BigDecimal bd(int value) {
        return BigDecimal.valueOf(value);
    }

    // ---------------------------------------------------------------------
    // MEDICAL
    // ---------------------------------------------------------------------

    @Test
    void medicalOverYtdCapClampsToRemainingBalance() {
        UsageSnapshot usage =
            new UsageSnapshot(
                new EnumMap<>(Map.of(SpecialMoneyType.MEDICAL, bd(2000))), Map.of());
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

        assertThat(decision.violations()).anyMatch(v -> v.contains("remaining annual balance"));
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

        assertThat(decision.violations()).anyMatch(v -> v.contains("older than one month"));
    }

    @Test
    void probationerRejectedForMedicalButAcceptedForTheKit() {
        EmployeeEligibilitySnapshot probationer =
            new EmployeeEligibilitySnapshot(2L, TODAY.minusDays(30), null, null, "17", true, TODAY);

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
        assertThat(medicalDecision.violations()).anyMatch(v -> v.contains("not completed probation"));

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

    /**
     * hr.department.source_code is a VARCHAR(10) and is not guaranteed to be numeric, so the gate
     * compares strings. An earlier implementation parsed both sides as BigDecimal, which silently
     * refused every employee whose department code contained a letter.
     */
    @Test
    void preprobationKitAcceptsANonNumericDepartmentCode() {
        EmployeeEligibilitySnapshot probationer =
            new EmployeeEligibilitySnapshot(2L, TODAY.minusDays(30), null, null, "SLS-01", true, TODAY);

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.UNIFORM_PREPROBATION_KIT,
                new SubmitSpecialMoneyRequest(
                    2L, TODAY, null, null, BigDecimal.ONE, bd(2660), "pre-probation kit", Map.of()),
                probationer,
                noUsage(),
                amountsWithConfiguredSalesSupportCode("SLS-01"),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).isEmpty();
    }

    /** Leading zeros are part of the code, not decoration: "017" and "17" are different departments. */
    @Test
    void preprobationKitDoesNotTreatLeadingZeroCodesAsEqual() {
        EmployeeEligibilitySnapshot probationer =
            new EmployeeEligibilitySnapshot(2L, TODAY.minusDays(30), null, null, "017", true, TODAY);

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.UNIFORM_PREPROBATION_KIT,
                new SubmitSpecialMoneyRequest(
                    2L, TODAY, null, null, BigDecimal.ONE, bd(2660), "pre-probation kit", Map.of()),
                probationer,
                noUsage(),
                amountsWithConfiguredSalesSupportCode("17"),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations())
            .anySatisfy(v -> assertThat(v).contains("sales-support"));
    }

    // ---------------------------------------------------------------------
    // UNIFORM_ANNUAL
    // ---------------------------------------------------------------------

    @Test
    void uniformAprilReceiptRejected() {
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(
                1L,
                LocalDate.of(2026, 6, 15),
                null,
                LocalDate.of(2026, 4, 10),
                BigDecimal.ONE,
                bd(1000),
                "annual uniform",
                Map.of());

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.UNIFORM_ANNUAL,
                request,
                activeEmployeePastProbation(),
                noUsage(),
                amounts(Map.of()),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).anyMatch(v -> v.contains("dated in May"));
    }

    @Test
    void uniformJulySubmissionRejected() {
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(
                1L, LocalDate.of(2026, 7, 5), null, LocalDate.of(2026, 5, 10), BigDecimal.ONE, bd(1000),
                "annual uniform", Map.of());

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.UNIFORM_ANNUAL,
                request,
                activeEmployeePastProbation(),
                noUsage(),
                amounts(Map.of()),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).anyMatch(v -> v.contains("submitted in June"));
    }

    @Test
    void uniformMayReceiptJuneClaimAccepted() {
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(
                1L, LocalDate.of(2026, 6, 5), null, LocalDate.of(2026, 5, 10), BigDecimal.ONE, bd(1000),
                "annual uniform", Map.of());

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.UNIFORM_ANNUAL,
                request,
                activeEmployeePastProbation(),
                noUsage(),
                amounts(Map.of()),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).isEmpty();
        assertThat(decision.eligibleAmount()).isEqualByComparingTo(bd(1000));
    }

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

        assertThat(decision.violations()).anyMatch(v -> v.contains("maximum of 4 pieces"));
        assertThat(decision.eligibleAmount()).isEqualByComparingTo(bd(1200));
    }

    // ---------------------------------------------------------------------
    // UNIFORM_PREPROBATION_KIT
    // ---------------------------------------------------------------------

    @Test
    void preprobationKitAcceptedForSalesSupportAfterSevenDays() {
        EmployeeEligibilitySnapshot employee =
            new EmployeeEligibilitySnapshot(3L, TODAY.minusDays(10), null, null, "17", true, TODAY);
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(3L, TODAY, null, null, BigDecimal.ONE, bd(2660), "kit", Map.of());

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.UNIFORM_PREPROBATION_KIT,
                request,
                employee,
                noUsage(),
                amountsWithConfiguredSalesSupportCode("17"),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).isEmpty();
        assertThat(decision.eligibleAmount()).isEqualByComparingTo(bd(2660));
    }

    @Test
    void preprobationKitRejectedForNonSalesSupportDepartment() {
        EmployeeEligibilitySnapshot employee =
            new EmployeeEligibilitySnapshot(3L, TODAY.minusDays(10), null, null, "99", true, TODAY);
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(3L, TODAY, null, null, BigDecimal.ONE, bd(2660), "kit", Map.of());

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.UNIFORM_PREPROBATION_KIT,
                request,
                employee,
                noUsage(),
                amountsWithConfiguredSalesSupportCode("17"),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).anyMatch(v -> v.contains("sales-support department"));
    }

    @Test
    void preprobationKitRejectedBeforeDaySeven() {
        EmployeeEligibilitySnapshot employee =
            new EmployeeEligibilitySnapshot(3L, TODAY.minusDays(3), null, null, "17", true, TODAY);
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(3L, TODAY, null, null, BigDecimal.ONE, bd(2660), "kit", Map.of());

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.UNIFORM_PREPROBATION_KIT,
                request,
                employee,
                noUsage(),
                amountsWithConfiguredSalesSupportCode("17"),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).anyMatch(v -> v.contains("minimum 7 days"));
    }

    // ---------------------------------------------------------------------
    // AID_*
    // ---------------------------------------------------------------------

    @Test
    void weddingRejectedWhenAlreadyClaimedOnce() {
        UsageSnapshot usage =
            new UsageSnapshot(Map.of(), new EnumMap<>(Map.of(SpecialMoneyType.AID_WEDDING, 1)));
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

        assertThat(decision.violations()).anyMatch(v -> v.contains("once per lifetime"));
    }

    @Test
    void childbirthAllowedTwice() {
        UsageSnapshot usage =
            new UsageSnapshot(Map.of(), new EnumMap<>(Map.of(SpecialMoneyType.AID_CHILDBIRTH, 2)));
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(
                1L, TODAY.minusDays(10), null, null, BigDecimal.ONE, bd(5000), "childbirth aid", Map.of());

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.AID_CHILDBIRTH,
                request,
                activeEmployeePastProbation(),
                usage,
                amounts(Map.of("cap", bd(5000))),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).isEmpty();
        assertThat(decision.eligibleAmount()).isEqualByComparingTo(bd(5000));
    }

    @Test
    void funeralRejectedForASibling() {
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(
                1L, TODAY.minusDays(5), null, null, BigDecimal.ONE, bd(5000), "funeral aid",
                Map.of("relation", "sibling"));

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.AID_FUNERAL,
                request,
                activeEmployeePastProbation(),
                noUsage(),
                amounts(Map.of("cap", bd(5000))),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).anyMatch(v -> v.contains("not eligible for funeral aid"));
    }

    // ---------------------------------------------------------------------
    // TRAVEL_PER_DIEM
    // ---------------------------------------------------------------------

    @Test
    void perDiemRejectedForSamutPrakan() {
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(
                1L, TODAY, TODAY, null, BigDecimal.ONE, bd(400), "local trip",
                Map.of("destination", "DOMESTIC", "province", "สมุทรปราการ", "role", "driver"));

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.TRAVEL_PER_DIEM,
                request,
                activeEmployeePastProbation(),
                noUsage(),
                amounts(Map.of()),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).anyMatch(v -> v.contains("excluded"));
        assertThat(decision.eligibleAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

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

    @Test
    void perDiemPays800PerDayForNonAsia() {
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(
                1L, TODAY, TODAY, null, BigDecimal.ONE, bd(800), "overseas trip",
                Map.of("destination", "OVERSEAS", "region", "OTHER"));

        PolicyDecision decision =
            EVALUATOR.evaluate(
                SpecialMoneyType.TRAVEL_PER_DIEM,
                request,
                activeEmployeePastProbation(),
                noUsage(),
                amounts(Map.of()),
                EXCLUDED_PROVINCES);

        assertThat(decision.violations()).isEmpty();
        assertThat(decision.eligibleAmount()).isEqualByComparingTo(bd(800));
    }

    // ---------------------------------------------------------------------
    // Universal safety net
    // ---------------------------------------------------------------------

    /**
     * Catches a new {@link SpecialMoneyType} added without wiring an eligibility rule: no matter
     * the cap-rule shape, an inactive employee must never be found eligible.
     */
    @ParameterizedTest
    @EnumSource(SpecialMoneyType.class)
    void inactiveEmployeeAlwaysRejected(SpecialMoneyType type) {
        EmployeeEligibilitySnapshot inactiveEmployee =
            new EmployeeEligibilitySnapshot(9L, null, null, null, null, false, TODAY);
        SubmitSpecialMoneyRequest request =
            new SubmitSpecialMoneyRequest(9L, TODAY, null, TODAY, BigDecimal.ONE, bd(100), "test", Map.of());

        PolicyDecision decision =
            EVALUATOR.evaluate(
                type, request, inactiveEmployee, noUsage(), amountsWithConfiguredSalesSupportCode("17"), EXCLUDED_PROVINCES);

        assertThat(decision.violations()).anyMatch(v -> v.contains("not active"));
    }
}
