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
    // Reconciliation additions (2026-07-21, C3/C4). Appended after the original 12 fields so the
    // legacy 12-arg constructor below keeps every existing positional call site compiling unchanged.
    BigDecimal directorRemuneration,
    BigDecimal warningLetterDeduction,
    BigDecimal customerReturnDeduction,
    BigDecimal otherPretaxDeduction
) {
    /**
     * Legacy 12-arg constructor, kept so every call site written before the reconciliation fields
     * existed (including {@code PayrollExcelReconciliationTest}, which must not be edited) still
     * compiles. The four new fields default to zero, which is required for the byte-identical
     * regression guarantee: with all-zero new inputs, {@link PayrollCalculator} must reproduce
     * exactly what it produced before this change.
     */
    public PayrollCalculationInput(
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
        int payrollMonthValue
    ) {
        this(
            baseSalary, specialPays, overtimePay, commissionPay, nonTaxableIncome, unpaidLeaveDays,
            studentLoanDeduction, legalExecutionRequested, otherPostTaxDeductions, taxAllowances,
            yearToDate, payrollMonthValue,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );
    }
}
