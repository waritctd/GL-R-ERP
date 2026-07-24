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
    BigDecimal otherPretaxDeduction,
    // Cancel-after-close reversal, AUTO-REFUND (2026-07-23): pending hr.leave_payroll_correction
    // days for this employee, resolved automatically by PayrollService#preview/#process -- never an
    // HR-typed field (contrast unpaidLeaveDays above, which IS HR-typed). Appended last, after the
    // C3/C4 fields, for the same reason those were appended after the original 12: every existing
    // 16-arg call site (this file's own legacy constructor below, PayrollCalculatorTest,
    // PayrollService) keeps compiling via the new 16-arg legacy constructor added below.
    BigDecimal leaveRefundDays,
    // Withholding-tax override (2026-07-24, V88): the RESOLVED effective override for this run --
    // per-run HR-typed value if present, else the employee's standing override, else null (resolved
    // in PayrollService#calculateLine). NULLABLE and meaningful: null = "compute withholding normally"
    // (today's behaviour, unchanged); a non-null value (including 0) SUBSTITUTES the final withheld
    // amount only -- it does NOT touch progressiveTax/annualTax/projections (see PayrollCalculator).
    // Deliberately NOT defaulted to zero: zero is a legitimate override (withhold nothing), so it must
    // stay distinct from "no override". Appended last so every prior positional call site keeps
    // compiling via the legacy constructors below.
    BigDecimal withholdingTaxOverride
) {
    /**
     * Legacy 12-arg constructor, kept so every call site written before the reconciliation fields
     * existed (including {@code PayrollExcelReconciliationTest}, which must not be edited) still
     * compiles. The five new fields default to zero, which is required for the byte-identical
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
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null
        );
    }

    /**
     * Legacy 16-arg constructor: the full signature as it stood right after the C3/C4 reconciliation
     * additions, before {@code leaveRefundDays} existed. Keeps {@link PayrollCalculatorTest} and any
     * other 16-arg call site compiling unchanged; {@code leaveRefundDays} defaults to zero, which
     * reproduces the pre-refund calculation exactly (see {@link PayrollCalculator}'s treatment --
     * zero refund days is a no-op on every downstream figure).
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
        int payrollMonthValue,
        BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction
    ) {
        this(
            baseSalary, specialPays, overtimePay, commissionPay, nonTaxableIncome, unpaidLeaveDays,
            studentLoanDeduction, legalExecutionRequested, otherPostTaxDeductions, taxAllowances,
            yearToDate, payrollMonthValue,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            BigDecimal.ZERO, null
        );
    }

    /**
     * Legacy 17-arg constructor: the full signature as it stood right before {@code
     * withholdingTaxOverride} existed (i.e. through {@code leaveRefundDays}). Keeps every 17-arg
     * positional call site compiling unchanged; {@code withholdingTaxOverride} defaults to {@code
     * null}, which means "no override -- compute withholding normally" and therefore reproduces the
     * pre-override calculation exactly (see {@link PayrollCalculator}).
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
        int payrollMonthValue,
        BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction,
        BigDecimal leaveRefundDays
    ) {
        this(
            baseSalary, specialPays, overtimePay, commissionPay, nonTaxableIncome, unpaidLeaveDays,
            studentLoanDeduction, legalExecutionRequested, otherPostTaxDeductions, taxAllowances,
            yearToDate, payrollMonthValue,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            leaveRefundDays, null
        );
    }
}
