package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Input/output for {@link PayrollCalculator#calculateClassified}, the task-2 (2026-07-29) rewrite
 * that replaces the hardcoded single-limb income split with the per-employee, per-component tax
 * treatment and SSO inclusion data introduced by task 1 (V95) and the per-limb year-to-date tracking
 * added by V96. See docs/agent-handoffs/118_feat-payroll-classification-and-hr-declarations.md.
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
        // rejection message (handoff section 1: "reject the payroll run with a message naming the
        // employee and the component").
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
        // #findComponentSsoInclusionByEmployee). A component absent from this map is excluded from the
        // SSO wage base -- there is no implicit default at the calculator layer; the default-TRUE
        // behaviour lives entirely in PayrollRepository#seedSsoInclusionDefaults, upstream of this
        // input.
        Map<PayrollComponent, Boolean> componentSsoInclusion,
        BigDecimal unpaidLeaveDays,
        BigDecimal leaveRefundDays,
        BigDecimal studentLoanDeduction,
        BigDecimal otherPretaxDeduction,
        BigDecimal otherPostTaxDeductions,
        // หักตามใบเตือน (handoff section 6): POST-TAX only now -- never reduces taxable gross or 50
        // ทวิ. Branch 117 had this pre-tax; this task moves it.
        BigDecimal warningLetterDeduction,
        // หัก 6 ลูกค้าคืนสินค้า (handoff section 6). When customerReturnAlreadyEarned is false (the
        // commission was not yet earned), the caller (PayrollService) has ALREADY netted this amount
        // out of componentAmounts.get(COMMISSION_PAY) pre-tax -- the calculator does not apply it
        // again. When true (already earned and paid), the calculator applies it here as a POST-TAX
        // clawback that never touches taxable income.
        BigDecimal customerReturnDeduction,
        boolean customerReturnAlreadyEarned,
        BigDecimal garnishmentRequested,
        // Nullable: null defaults to SALARY, reproducing the pre-existing single 30%-plus-฿20,000-
        // floor rule exactly (handoff section 7).
        PayrollGarnishmentType garnishmentType,
        PayrollTaxAllowanceInput taxAllowances,
        PayrollYearToDate yearToDate,
        int payrollMonthValue,
        // Rebase consequence (2026-07-29): branch 117's retirementAllowance(...) grew a taxYear
        // parameter so the SSF (กองทุนรวมเพื่อการออม) sunset (SSF_FIRST_NON_DEDUCTIBLE_TAX_YEAR = 2025)
        // applies uniformly across both PayrollCalculator entry points. This engine had no year field
        // at all before -- payrollMonthValue is month-only (1-12) -- so it is added here and threaded
        // from PayrollService#calculateLine (which already derives it from payrollMonth.getYear() for
        // the tax-treatment/SSO-inclusion lookups). See PayrollCalculator#allowanceBreakdown.
        int taxYear,
        // Resolved effective withholding-tax override (per-run typed value if present, else the
        // employee's standing override, else null). Same semantics as PayrollCalculationInput's field
        // of the same name -- see that record's javadoc.
        BigDecimal withholdingTaxOverride
    ) {
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
        // Defect fix (Opus review, 2026-07-29): ค่าอาหาร / เบี้ยเลี้ยง (V97) were read from the request,
        // folded into componentAmounts for tax purposes (MEAL_ALLOWANCE / PER_DIEM_TAXABLE), and then
        // discarded -- neither this record nor PayrollLineDto carried them back out, so they never
        // reached the INSERT column list. mealAllowance/perDiemTaxable mirror bonusPay/otherOneOffPay
        // above: an explicit echo of the already-classified component amount, not a new calculation.
        BigDecimal mealAllowance,
        BigDecimal perDiemTaxable
    ) {}
}
