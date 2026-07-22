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
    BigDecimal otherPretaxDeduction
) {}
