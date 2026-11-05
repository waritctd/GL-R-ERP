package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Input/output for {@link PayrollCalculator#calculateClassified}, which uses the per-employee,
 * per-component tax treatment and SSO inclusion data (V95) and the per-limb year-to-date tracking
 * (V96) instead of a hardcoded single-limb income split.
 *
 * <p>Deliberately a NEW pair of records rather than more trailing fields on {@link
 * PayrollCalculationInput}/{@link PayrollCalculation}: those two are frozen by {@code
 * PayrollExcelReconciliationTest} (explicitly "must not be edited" -- it reconciles the ORIGINAL
 * single-limb engine against the accountant's real May 2026 workbook) and by the many legacy
 * constructors that keep pre-V95 call sites compiling. {@link PayrollCalculator#calculate} keeps
 * computing exactly what it always has, for that regression suite; {@link
 * PayrollService#calculateLine} calls only {@code calculateClassified} now -- the classifier engine
 * is the ONLY path production payroll runs through.
 */
public final class PayrollClassifiedCalculationDtos {
    private PayrollClassifiedCalculationDtos() {
    }

    public record PayrollClassifiedCalculationInput(
        long employeeId,
        // Employee code + name, used only to name the employee in the unclassified-component
        // rejection message (names the employee and the component).
        String employeeLabel,
        // This period's amount per component. A component absent from this map is treated as zero --
        // NOT as "unclassified and blocking" (only a component with amount().signum() > 0 and no
        // treatment blocks the run).
        Map<PayrollComponent, BigDecimal> componentAmounts,
        // Per-component tax treatment for this employee/tax-year (PayrollRepository
        // #findComponentTaxTreatmentsByEmployee). A component absent from this map, or present with a
        // null value, both mean "not yet classified" -- both block a non-zero amount identically.
        // SALARY is exempt from this map entirely: it is locked to REGULAR_REPROJECT regardless of
        // what (if anything) is stored, matching the V95 CHECK constraint.
        Map<PayrollComponent, PayrollTaxTreatment> componentTaxTreatments,
        // Per-component SSO wage-base inclusion for this employee/tax-year (PayrollRepository
        // #findComponentSsoInclusionByEmployee). A component absent from THIS map is excluded from the
        // SSO wage base -- there is still no implicit default at this calculator layer, deliberately:
        // #ssoIncluded below stays a pure lookup. The default-TRUE behaviour lives upstream:
        // PayrollService#ssoInclusionFor supplies the default map (PayrollRepository#defaultSsoIncluded)
        // whenever an employee has NO stored rows (e.g. inserted by raw SQL, which would otherwise
        // silently compute ฿0.00 SSO), so this map is never actually empty for a real employee.
        Map<PayrollComponent, Boolean> componentSsoInclusion,
        BigDecimal unpaidLeaveDays,
        BigDecimal leaveRefundDays,
        BigDecimal studentLoanDeduction,
        BigDecimal otherPretaxDeduction,
        BigDecimal otherPostTaxDeductions,
        // หักตามใบเตือน: POST-TAX only -- never reduces taxable gross or 50 ทวิ.
        BigDecimal warningLetterDeduction,
        // หัก 6 ลูกค้าคืนสินค้า. When customerReturnAlreadyEarned is false (the
        // commission was not yet earned), the caller (PayrollService) has ALREADY netted this amount
        // out of componentAmounts.get(COMMISSION_PAY) pre-tax -- the calculator does not apply it
        // again. When true (already earned and paid), the calculator applies it here as a POST-TAX
        // clawback that never touches taxable income.
        BigDecimal customerReturnDeduction,
        boolean customerReturnAlreadyEarned,
        BigDecimal garnishmentRequested,
        // Nullable: null defaults to SALARY, reproducing the pre-existing single 30%-plus-฿20,000-
        // floor rule exactly.
        PayrollGarnishmentType garnishmentType,
        PayrollTaxAllowanceInput taxAllowances,
        PayrollYearToDate yearToDate,
        int payrollMonthValue,
        // retirementAllowance(...) takes a taxYear so the SSF (กองทุนรวมเพื่อการออม) sunset
        // (SSF_FIRST_NON_DEDUCTIBLE_TAX_YEAR = 2025) applies uniformly across both PayrollCalculator
        // entry points. payrollMonthValue is month-only (1-12), hence this separate field, threaded
        // from PayrollService#calculateLine (payrollMonth.getYear()). See PayrollCalculator#allowanceBreakdown.
        int taxYear,
        // Resolved effective withholding-tax override (per-run typed value if present, else the
        // employee's standing override, else null). Same semantics as PayrollCalculationInput's field
        // of the same name -- see that record's javadoc.
        BigDecimal withholdingTaxOverride,
        // Daily-rate support: the employee's actual PER-DAY rate (hr.employee
        // .current_salary for a pay_type = 'D' employee), or null for a monthly employee. When
        // present, PayrollCalculator#calculateClassified uses this DIRECTLY as the dailyRate that
        // feeds unpaidLeaveDeduction/leaveDeductionRefund/hourlyRate, instead of deriving it as
        // amountOf(SALARY)/30 -- amountOf(SALARY) is this period's GROSS pay for a daily-rate employee
        // (rate * daysWorked, computed by PayrollService#calculateLine), and dividing THAT by 30 would
        // silently produce the wrong per-day figure (e.g. 11,250/30 = 375, not the true 450 rate) the
        // moment a month's days-worked figure differs from a 30-day baseline. Null is a complete no-op:
        // the monthly derivation (baseSalary/30) applies.
        BigDecimal dailyRateOverride
    ) {
        /**
         * Legacy 20-arg constructor: the full signature without {@code dailyRateOverride}, i.e.
         * through {@code withholdingTaxOverride}. Keeps every pre-existing positional call site (including
         * PayrollExcelReconciliationTest and PayrollClassifiedLimbClampReviewTest, which must not be
         * edited) compiling unchanged; {@code dailyRateOverride} defaults to {@code null} (monthly
         * derivation).
         */
        public PayrollClassifiedCalculationInput(
            long employeeId,
            String employeeLabel,
            Map<PayrollComponent, BigDecimal> componentAmounts,
            Map<PayrollComponent, PayrollTaxTreatment> componentTaxTreatments,
            Map<PayrollComponent, Boolean> componentSsoInclusion,
            BigDecimal unpaidLeaveDays,
            BigDecimal leaveRefundDays,
            BigDecimal studentLoanDeduction,
            BigDecimal otherPretaxDeduction,
            BigDecimal otherPostTaxDeductions,
            BigDecimal warningLetterDeduction,
            BigDecimal customerReturnDeduction,
            boolean customerReturnAlreadyEarned,
            BigDecimal garnishmentRequested,
            PayrollGarnishmentType garnishmentType,
            PayrollTaxAllowanceInput taxAllowances,
            PayrollYearToDate yearToDate,
            int payrollMonthValue,
            int taxYear,
            BigDecimal withholdingTaxOverride
        ) {
            this(employeeId, employeeLabel, componentAmounts, componentTaxTreatments, componentSsoInclusion,
                unpaidLeaveDays, leaveRefundDays, studentLoanDeduction, otherPretaxDeduction,
                otherPostTaxDeductions, warningLetterDeduction, customerReturnDeduction,
                customerReturnAlreadyEarned, garnishmentRequested, garnishmentType, taxAllowances,
                yearToDate, payrollMonthValue, taxYear, withholdingTaxOverride, null);
        }

        public BigDecimal amountOf(PayrollComponent component) {
            BigDecimal value = componentAmounts == null ? null : componentAmounts.get(component);
            return value == null ? BigDecimal.ZERO : value;
        }

        public PayrollTaxTreatment treatmentOf(PayrollComponent component) {
            if (component == PayrollComponent.SALARY) {
                return PayrollTaxTreatment.REGULAR_REPROJECT;
            }
            return componentTaxTreatments == null ? null : componentTaxTreatments.get(component);
        }

        public boolean ssoIncluded(PayrollComponent component) {
            return componentSsoInclusion != null && Boolean.TRUE.equals(componentSsoInclusion.get(component));
        }
    }

    public record PayrollClassifiedCalculation(
        BigDecimal baseSalary,
        BigDecimal dailyRate,
        BigDecimal hourlyRate,
        List<BigDecimal> specialPays,
        BigDecimal specialPayTotal,
        BigDecimal overtimePay,
        BigDecimal commissionPay,
        BigDecimal bonusPay,
        BigDecimal otherOneOffPay,
        BigDecimal directorRemuneration,
        BigDecimal grossEarnings,
        BigDecimal nonTaxableIncome,
        BigDecimal unpaidLeaveDays,
        BigDecimal unpaidLeaveDeduction,
        BigDecimal grossTaxableIncome,
        BigDecimal ssoWageBase,
        // V123: the UNCAPPED sum this same method computes before clamping it into
        // ssoWageBase above (see PayrollCalculator#calculateClassified's own comment on
        // ssoWageBaseRaw). Persisted to payroll_line.sso_wage_gross so SsoExporter can report the
        // real เงินค่าจ้างทั้งสิ้น instead of the capped figure -- does not change ssoWageBase or
        // socialSecurity, which are computed exactly as before.
        BigDecimal ssoWageBaseRaw,
        BigDecimal socialSecurity,
        BigDecimal projectedAnnualIncome,
        BigDecimal taxExpenseDeduction,
        BigDecimal taxAllowanceTotal,
        BigDecimal taxableAnnualIncome,
        BigDecimal annualTax,
        BigDecimal withholdingTax,
        BigDecimal studentLoanDeduction,
        BigDecimal legalExecutionDeduction,
        BigDecimal otherPostTaxDeductions,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        String calculationNote,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction,
        boolean customerReturnAlreadyEarned,
        BigDecimal leaveRefundDays,
        BigDecimal leaveDeductionRefund,
        BigDecimal withholdingTaxOverride,
        BigDecimal taxableIncomeRegularLimb,
        BigDecimal taxableIncomeKnownLimb,
        BigDecimal taxableIncomeCumulativeLimb,
        BigDecimal withholdingTaxRegularLimb,
        BigDecimal withholdingTaxCumulativeLimb,
        PayrollGarnishmentType garnishmentType,
        // ค่าอาหาร / เบี้ยเลี้ยง (V97): read from the request and folded into componentAmounts for tax
        // (MEAL_ALLOWANCE / PER_DIEM_TAXABLE). These two fields echo the already-classified component
        // amounts so they reach the INSERT column list, like bonusPay/otherOneOffPay above -- not a
        // new calculation.
        BigDecimal mealAllowance,
        BigDecimal perDiemTaxable,
        // The classified engine's analogue of the legacy #calculate()'s excessWithheldToDate --
        // over-withholding for the tax year AS AT THE END OF THIS PERIOD, i.e. the running total actually withheld (yearToDate.withholdingTax() plus this
        // period's own POST-OVERRIDE withholdingTax above) exceeding the TRUE annual liability given
        // everything known so far this year. See #calculateClassified's own computation for why
        // annualTaxWithCumulativeYtd (F1's carried-known-limb base), not the narrower reported
        // taxableAnnualIncome/annualTax fields above, is the correct liability to compare against.
        BigDecimal excessWithheldToDate
    ) {}
}
