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
    BigDecimal withholdingTaxOverride
) {}
