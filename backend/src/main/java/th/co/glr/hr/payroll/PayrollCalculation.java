package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.util.List;

public record PayrollCalculation(
    BigDecimal baseSalary,
    BigDecimal dailyRate,
    BigDecimal hourlyRate,
    List<BigDecimal> specialPays,
    BigDecimal specialPayTotal,
    BigDecimal overtimePay,
    BigDecimal commissionPay,
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
    // Reconciliation additions (2026-07-21, C3/C4).
    BigDecimal directorRemuneration,
    BigDecimal warningLetterDeduction,
    BigDecimal customerReturnDeduction,
    BigDecimal otherPretaxDeduction,
    // Cancel-after-close reversal, AUTO-REFUND (2026-07-23): the pre-tax credit reversing a PRIOR
    // month's over-deduction. Kept separate from unpaidLeaveDays/unpaidLeaveDeduction (this month's
    // OWN unpaid leave, if any) throughout -- see PayrollCalculator for how the two combine.
    BigDecimal leaveRefundDays,
    BigDecimal leaveDeductionRefund,
    // Withholding-tax override (2026-07-24, V88): the RESOLVED effective override that was APPLIED to
    // this calculation, or null if none was (the normal computed withholding stands). When non-null,
    // withholdingTax above equals money(withholdingTaxOverride); annualTax / taxableAnnualIncome /
    // projectedAnnualIncome are UNCHANGED and still reflect the true progressive-tax computation, for
    // transparency. Carried onto payroll_line.withholding_tax_override so the per-run typed value can
    // carry forward.
    BigDecimal withholdingTaxOverride,
    // ป.96/2543 compliance (2026-07-28, V92). The two limbs คำชี้แจง ภ.ง.ด.1 requires be kept apart:
    // เงินได้ที่จ่ายตามปกติ (ข้อ 2.1, annualised) and เงินพิเศษที่จ่ายเป็นครั้งคราว (ข้อ 2.5, actual
    // amount, taxed as a difference). regularTaxableIncome + variableTaxableIncome == grossTaxableIncome
    // and regularWithholdingTax + variableWithholdingTax == withholdingTax, always -- both invariants
    // are pinned by tests. The income and withholding pairs are persisted onto payroll_line so next
    // period's projection can carry each limb forward separately.
    BigDecimal regularTaxableIncome,
    BigDecimal variableTaxableIncome,
    BigDecimal regularWithholdingTax,
    BigDecimal variableWithholdingTax,
    // Reported for the payslip and for the ภ.ง.ด.1 working papers: the annualised regular figure
    // (ข้อ 2.1), the cumulative เงินพิเศษ (ข้อ 2.5), the tax on regular pay alone, and the ข้อ 2.5
    // difference attributable to the เงินพิเศษ. projectedAnnualIncome == annualRegularIncome +
    // annualVariableIncome and annualTax == regularAnnualTax + variableAnnualTax.
    BigDecimal annualRegularIncome,
    BigDecimal annualVariableIncome,
    BigDecimal regularAnnualTax,
    BigDecimal variableAnnualTax,
    // จำนวนคราวที่ต้องจ่าย remaining this tax year including this one (ข้อ 2.1). 1 in December, which is
    // what turns the ordinary formula into the ข้อ 2.9 true-up. NOT 1 in a leaver's final period --
    // ข้อ 2.10 is a known gap (see PayrollService#remainingPayPeriods), so nothing in production ever
    // supplies a 1 for that reason.
    int remainingPayPeriods,
    // Withholding already remitted this tax year IN EXCESS of the year's assessed liability, as at
    // this period. Normally zero. Non-zero means an earlier period over-withheld and payroll cannot
    // give it back -- tax remitted to the RD is reclaimed by the employee on their ภ.ง.ด.91, not by a
    // negative withholding. Reported so the condition is visible: withholdingTax on its own reads
    // 0.00, which is indistinguishable from "nothing was due this period".
    BigDecimal excessWithheldToDate,
    // Dedicated one-off pay (2026-07-29, V94), both inside the ข้อ 2.5 variable limb.
    BigDecimal bonusPay,
    BigDecimal otherOneOffPay
) {}
