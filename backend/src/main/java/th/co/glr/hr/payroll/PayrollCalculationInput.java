package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.util.List;

public record PayrollCalculationInput(
    BigDecimal baseSalary,
    List<BigDecimal> specialPays,
    BigDecimal overtimePay,
    BigDecimal commissionPay,
    BigDecimal nonTaxableIncome,
    BigDecimal unpaidLeaveDays,
    BigDecimal studentLoanDeduction,
    BigDecimal legalExecutionRequested,
    BigDecimal otherPostTaxDeductions,
    PayrollTaxAllowanceInput taxAllowances,
    PayrollYearToDate yearToDate,
    int payrollMonthValue,
    // Director's remuneration (ค่าตอบแทนกรรมการ): excluded from SSO (not wages under the
    // Social Security Act), but still fully taxed exactly like a regular salary.
    boolean exemptFromSocialSecurity
) {}
