package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.util.List;

public record PayrollCalculationInput(
    BigDecimal baseSalary,
    List<BigDecimal> specialPays,
    BigDecimal overtimePay,
    BigDecimal commissionPay,
    BigDecimal unpaidLeaveDays,
    BigDecimal studentLoanDeduction,
    BigDecimal legalExecutionRequested,
    BigDecimal otherPostTaxDeductions,
    PayrollTaxAllowanceInput taxAllowances,
    PayrollYearToDate yearToDate,
    int payrollMonthValue
) {}
