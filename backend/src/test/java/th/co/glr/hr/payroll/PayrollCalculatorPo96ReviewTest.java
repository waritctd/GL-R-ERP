package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** REVIEWER-authored adversarial coverage for the ป.96/2543 withholding split (branch
 * fix/payroll-wht-po96-compliance). These are deliberately NOT the author's scenarios. */
class PayrollCalculatorPo96ReviewTest {
    private final PayrollCalculator calculator = new PayrollCalculator();

    private static final List<BigDecimal> NO_SPECIAL = List.of(
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    private PayrollCalculation run(
        BigDecimal baseSalary,
        List<BigDecimal> specialPays,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        PayrollTaxAllowanceInput allowances,
        PayrollYearToDate ytd,
        int month,
        int remainingPayPeriods
    ) {
        return calculator.calculate(new PayrollCalculationInput(
            baseSalary, specialPays, overtimePay, commissionPay,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            allowances, ytd, month,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null, 2026, remainingPayPeriods, 0));
    }

    /** Carries both limbs forward across a run of months, returning the total actually withheld. */
    private record YearRun(BigDecimal totalWithheld, PayrollCalculation lastMonth) {}

    private YearRun simulate(
        int firstMonth, int lastMonth, int lastPayPeriodOfYear,
        java.util.function.IntFunction<BigDecimal> salaryFor,
        java.util.function.IntFunction<BigDecimal> bonusFor,
        java.util.function.IntFunction<BigDecimal> commissionFor,
        PayrollTaxAllowanceInput allowances
    ) {
        BigDecimal regularIncome = BigDecimal.ZERO;
        BigDecimal variableIncome = BigDecimal.ZERO;
        BigDecimal sso = BigDecimal.ZERO;
        BigDecimal regularWh = BigDecimal.ZERO;
        BigDecimal variableWh = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        PayrollCalculation last = null;
        for (int month = firstMonth; month <= lastMonth; month += 1) {
            List<BigDecimal> specials = List.of(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, bonusFor.apply(month));
            int remaining = Math.max(1, lastPayPeriodOfYear - month + 1);
            PayrollCalculation result = run(
                salaryFor.apply(month), specials, BigDecimal.ZERO, commissionFor.apply(month),
                allowances,
                new PayrollYearToDate(regularIncome, variableIncome, sso, regularWh, variableWh),
                month, remaining);

            // The invariant the whole design rests on.
            assertThat(result.regularTaxableIncome().add(result.variableTaxableIncome()))
                .as("month %s income limbs must sum to grossTaxableIncome", month)
                .isEqualByComparingTo(result.grossTaxableIncome());
            assertThat(result.regularWithholdingTax().add(result.variableWithholdingTax()))
                .as("month %s withholding limbs must sum to withholdingTax", month)
                .isEqualByComparingTo(result.withholdingTax());

            regularIncome = regularIncome.add(result.regularTaxableIncome());
            variableIncome = variableIncome.add(result.variableTaxableIncome());
            sso = sso.add(result.socialSecurity());
            regularWh = regularWh.add(result.regularWithholdingTax());
            variableWh = variableWh.add(result.variableWithholdingTax());
            total = total.add(result.withholdingTax());
            last = result;
        }
        return new YearRun(total, last);
    }

    // Checks commission clawbacks do not reduce the month's taxable income.
    @Test
    @DisplayName("REVIEW: a commission clawback no longer reduces the month's taxable income")
    void aNegativeCommissionIsSwallowedByTheVariableLimbFloor() {
        PayrollCalculation clawback = run(
            new BigDecimal("50000.00"), NO_SPECIAL, BigDecimal.ZERO, new BigDecimal("-10000.00"),
            PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 6, 7);

        // The pre-split engine computed max(0, 50000 + (-10000)) = 40,000.00.
        assertThat(clawback.grossTaxableIncome())
            .as("a -10,000 clawback must still reduce assessable income to 40,000")
            .isEqualByComparingTo(new BigDecimal("40000.00"));
    }

    // Checks full-year reconciliation when percentage-capped RMF relief differs between regular and variable
    // income passes.
    @Test
    @DisplayName("REVIEW: a large RMF plus a large mid-year bonus still lands on the year's actual liability")
    void aLargeRmfAndALargeBonusStillReconcileOverTheYear() {
        PayrollTaxAllowanceInput bigRmf = new PayrollTaxAllowanceInput(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("400000.00"), // RMF, above 30% of regular-only income
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        YearRun year = simulate(1, 12, 12,
            month -> new BigDecimal("100000.00"),
            month -> month == 6 ? new BigDecimal("600000.00") : BigDecimal.ZERO,
            month -> BigDecimal.ZERO,
            bigRmf);

        assertThat(year.totalWithheld())
            .as("year total withheld must equal December's assessed annual liability")
            .isEqualByComparingTo(year.lastMonth().annualTax());
        assertThat(year.lastMonth().regularAnnualTax().add(year.lastMonth().variableAnnualTax()))
            .as("the two limbs' annual tax must sum to the annual tax")
            .isEqualByComparingTo(year.lastMonth().annualTax());
    }

    // 3. AN EMPLOYEE WITH NO REGULAR PAY AT ALL (pure-commission rep). Every baht is ข้อ 2.5, so the regular
    // limb's annual tax is zero and the whole liability lives in the variable limb -- which is NOT divided by
    // จำนวนคราว. Does the year still reconcile?
    @Test
    @DisplayName("REVIEW: a pure-commission rep with zero base salary still reconciles over the year")
    void aPureCommissionRepReconcilesOverTheYear() {
        YearRun year = simulate(1, 12, 12,
            month -> BigDecimal.ZERO,
            month -> BigDecimal.ZERO,
            month -> new BigDecimal("80000.00"),
            PayrollTaxAllowanceInput.empty());

        assertThat(year.totalWithheld())
            .as("year total withheld must equal December's assessed annual liability")
            .isEqualByComparingTo(year.lastMonth().annualTax());
    }

    // 4. JOINS IN APRIL, LEAVES IN SEPTEMBER. remainingPayPeriods is hand-passed here; production does NOT cap it
    // at a resignation (ข้อ 2.10 known gap), so September resolves to 1 and must true up the whole year.
    @Test
    @DisplayName("REVIEW: an employee who joins in April and leaves in September trues up in September")
    void aJoinerWhoAlsoLeavesInTheSameYearTruesUpInTheirFinalPeriod() {
        YearRun year = simulate(4, 9, 9,
            month -> new BigDecimal("120000.00"),
            month -> month == 8 ? new BigDecimal("200000.00") : BigDecimal.ZERO,
            month -> BigDecimal.ZERO,
            PayrollTaxAllowanceInput.empty());

        assertThat(year.lastMonth().remainingPayPeriods())
            .as("the final period must resolve to one pay period")
            .isEqualTo(1);
        assertThat(year.totalWithheld())
            .as("the leaver's total withheld must equal their actual liability (ข้อ 2.10)")
            .isEqualByComparingTo(year.lastMonth().annualTax());
    }

    // Checks no additional tax is withheld once year-to-date withholding exceeds full-year liability.
    @Test
    @DisplayName("REVIEW: no period may withhold once the year is already over-withheld")
    void anAlreadyOverWithheldYearMustNotWithholdMoreOnAMidYearBonus() {
        BigDecimal regularIncome = BigDecimal.ZERO;
        BigDecimal variableIncome = BigDecimal.ZERO;
        BigDecimal sso = BigDecimal.ZERO;
        BigDecimal regularWh = BigDecimal.ZERO;
        BigDecimal variableWh = BigDecimal.ZERO;
        BigDecimal cumulative = BigDecimal.ZERO;
        for (int month = 1; month <= 12; month += 1) {
            List<BigDecimal> specials = List.of(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                month == 9 ? new BigDecimal("150000.00") : BigDecimal.ZERO);
            PayrollCalculation result = run(
                month <= 6 ? new BigDecimal("300000.00") : new BigDecimal("20000.00"),
                specials, BigDecimal.ZERO, BigDecimal.ZERO, PayrollTaxAllowanceInput.empty(),
                new PayrollYearToDate(regularIncome, variableIncome, sso, regularWh, variableWh),
                month, 13 - month);

            if (cumulative.compareTo(result.annualTax()) >= 0) {
                assertThat(result.withholdingTax())
                    .as("month %s: %s already withheld against an assessed liability of %s",
                        month, cumulative, result.annualTax())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            }

            regularIncome = regularIncome.add(result.regularTaxableIncome());
            variableIncome = variableIncome.add(result.variableTaxableIncome());
            sso = sso.add(result.socialSecurity());
            regularWh = regularWh.add(result.regularWithholdingTax());
            variableWh = variableWh.add(result.variableWithholdingTax());
            cumulative = cumulative.add(result.withholdingTax());
        }
    }

    // Checks an allowance contradicted by a zero head count is capped with a visible warning.
    @Test
    @DisplayName("a child allowance contradicted by a zero head count is clamped, and says so")
    void aChildAllowanceWithAZeroHeadCountIsWipedRatherThanCapped() {
        PayrollTaxAllowanceInput declaredButUncounted = new PayrollTaxAllowanceInput(
            BigDecimal.ZERO, new BigDecimal("60000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            0, 0, 0, false);
        PayrollTaxAllowanceInput noAllowanceAtAll = PayrollTaxAllowanceInput.empty();

        BigDecimal withDeclaration = run(new BigDecimal("100000.00"), NO_SPECIAL, BigDecimal.ZERO,
            BigDecimal.ZERO, declaredButUncounted, PayrollYearToDate.empty(), 1, 12)
            .taxAllowanceTotal();
        BigDecimal without = run(new BigDecimal("100000.00"), NO_SPECIAL, BigDecimal.ZERO,
            BigDecimal.ZERO, noAllowanceAtAll, PayrollYearToDate.empty(), 1, 12)
            .taxAllowanceTotal();

        assertThat(withDeclaration)
            .as("the per-head cap is the law: no declared children means no ค่าลดหย่อนบุตร")
            .isEqualByComparingTo(without);

        PayrollCalculation clamped = run(new BigDecimal("100000.00"), NO_SPECIAL, BigDecimal.ZERO,
            BigDecimal.ZERO, declaredButUncounted, PayrollYearToDate.empty(), 1, 12);
        assertThat(clamped.calculationNote())
            .as("the clamp must be visible on the payslip, not silent")
            .contains("ค่าลดหย่อนบุตร")
            .contains("cap");
    }

    // Checks calculation warnings fit within the payslip's printable width.
    @Test
    @DisplayName("REVIEW: the calculation note must fit the payslip line it is printed on")
    void theClampWarningMustActuallyFitOnThePayslip() throws Exception {
        PayrollTaxAllowanceInput contradictory = new PayrollTaxAllowanceInput(
            BigDecimal.ZERO, new BigDecimal("60000.00"), BigDecimal.ZERO, new BigDecimal("120000.00"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            0, 0, 0, false);

        String plainNote = run(new BigDecimal("100000.00"), NO_SPECIAL, BigDecimal.ZERO,
            BigDecimal.ZERO, PayrollTaxAllowanceInput.empty(), PayrollYearToDate.empty(), 1, 12)
            .calculationNote();
        String warningNote = run(new BigDecimal("100000.00"), NO_SPECIAL, BigDecimal.ZERO,
            BigDecimal.ZERO, contradictory, PayrollYearToDate.empty(), 1, 12)
            .calculationNote();

        try (th.co.glr.hr.common.PdfDocumentWriter pdf = new th.co.glr.hr.common.PdfDocumentWriter()) {
            org.apache.pdfbox.pdmodel.font.PDFont regular =
                pdf.loadFont(th.co.glr.hr.common.PdfDocumentWriter.FONT_REGULAR);
            float printable = pdf.right() - pdf.left();

            // RESOLVED 2026-07-29 by WRAPPING the note in PayslipRenderer rather than shortening it. The original
            // assertion -- that the whole note fit one line -- encoded the unwrapped renderer, and could never hold for a
            // note carrying a withholding-override clause plus two clamp warnings.
            PayslipRenderer renderer = new PayslipRenderer();
            for (String note : new String[] {plainNote, warningNote}) {
                List<String> wrapped = renderer.wrapToWidth(pdf, regular, 9f, printable, "หมายเหตุ: " + note);
                for (String wrappedLine : wrapped) {
                    assertThat(pdf.width(regular, 9f, wrappedLine))
                        .as("every rendered note line must fit the printable width (%s pt)", printable)
                        .isLessThanOrEqualTo(printable);
                }
                assertThat(String.join(" ", wrapped))
                    .as("wrapping must not drop any of the note")
                    .isEqualTo("หมายเหตุ: " + note);
            }
            // The plain note still fits a single line -- a payslip with nothing unusual reads as before.
            assertThat(pdf.width(regular, 9f, "หมายเหตุ: " + plainNote))
                .as("an ordinary payslip's note should not need wrapping at all")
                .isLessThanOrEqualTo(printable);
        }
    }

    // Checks allowance caps also warn when a nonzero stored head count is stale.
    @Test
    @DisplayName("REVIEW: a child allowance clamped against a STALE head count must also say so")
    void aClampAgainstANonZeroButStaleHeadCountIsStillSilent() {
        PayrollTaxAllowanceInput threeChildrenOneCounted = new PayrollTaxAllowanceInput(
            BigDecimal.ZERO, new BigDecimal("90000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            1, 0, 0, false);
        PayrollTaxAllowanceInput oneChildDeclaredExactly = new PayrollTaxAllowanceInput(
            BigDecimal.ZERO, new BigDecimal("30000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            1, 0, 0, false);

        PayrollCalculation clamped = run(new BigDecimal("100000.00"), NO_SPECIAL, BigDecimal.ZERO,
            BigDecimal.ZERO, threeChildrenOneCounted, PayrollYearToDate.empty(), 1, 12);
        PayrollCalculation exact = run(new BigDecimal("100000.00"), NO_SPECIAL, BigDecimal.ZERO,
            BigDecimal.ZERO, oneChildDeclaredExactly, PayrollYearToDate.empty(), 1, 12);

        // 60,000 of the declaration is discarded -- this part is the CURRENT behaviour, pinned so the scenario is
        // unambiguous.
        assertThat(clamped.taxAllowanceTotal())
            .as("90,000 against a stored count of 1 is clamped to 30,000")
            .isEqualByComparingTo(exact.taxAllowanceTotal());

        assertThat(clamped.calculationNote())
            .as("a clamp that discards 60,000 must name itself, exactly as the zero-count clamp does")
            .contains("ค่าลดหย่อนบุตร");
    }
}
