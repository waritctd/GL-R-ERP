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
    BigDecimal withholdingTaxOverride,
    // ป.96/2543 compliance (2026-07-28, V92). Both appended last, same reason as every field above:
    // every prior positional call site keeps compiling via the legacy 18-arg constructor below.
    //
    // taxYear: the GREGORIAN tax year this run belongs to (e.g. 2026), from the payroll month. Needed
    // because some ค่าลดหย่อน are year-scoped -- SSF purchases stopped being deductible from ปีภาษี
    // 2568 (Gregorian 2025) -- so the calculator cannot apply the right rules without knowing the
    // year. Zero means "unknown"; the calculator then applies no year-scoped restriction, which is
    // what every legacy call site got before this field existed.
    int taxYear,
    // remainingPayPeriods: จำนวนคราวที่ต้องจ่าย remaining in this tax year INCLUDING this one, per
    // คำชี้แจง ภ.ง.ด.1 ข้อ 2.1. PayrollService resolves it as 13 - month. It is NOT capped at a
    // leaver's final period: ข้อ 2.10 is a known gap because resignations are not recorded in this
    // platform at all -- see PayrollService#remainingPayPeriods.
    // Zero means "not supplied"; the calculator falls back to 13 - payrollMonthValue, which is
    // exactly what it computed internally before this field existed.
    int remainingPayPeriods,
    // taxpayerAge: the employee's age in this tax year, from hr.employee.date_of_birth. Drives the
    // ยกเว้นเงินได้ 190,000 for taxpayers aged 65+ (กฎกระทรวง ฉบับที่ 126); the disability-card route
    // to the same exemption is a ล.ย.01 declaration and travels on PayrollTaxAllowanceInput instead.
    // Zero means "date of birth unknown" -- the exemption is then NOT granted on an assumption.
    int taxpayerAge,
    // Dedicated one-off pay (2026-07-29, V94). เงินโบนัส and อื่นๆ get their own fields so a one-off
    // payment is no longer typed into a พิเศษ slot that also carries a monthly allowance -- the
    // ambiguity that made every slot-based ป.96 classification wrong. Both join the ข้อ 2.5 variable
    // limb; ข้อ 2.5 names เงินโบนัส explicitly.
    BigDecimal bonusPay,
    BigDecimal otherOneOffPay
) {
    /** Legacy 21-arg constructor: the signature before the dedicated one-off pay fields existed. */
    public PayrollCalculationInput(
        BigDecimal baseSalary, List<BigDecimal> specialPays, BigDecimal overtimePay,
        BigDecimal commissionPay, BigDecimal nonTaxableIncome, BigDecimal unpaidLeaveDays,
        BigDecimal studentLoanDeduction, BigDecimal legalExecutionRequested,
        BigDecimal otherPostTaxDeductions, PayrollTaxAllowanceInput taxAllowances,
        PayrollYearToDate yearToDate, int payrollMonthValue, BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction, BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction, BigDecimal leaveRefundDays,
        BigDecimal withholdingTaxOverride, int taxYear, int remainingPayPeriods, int taxpayerAge
    ) {
        this(baseSalary, specialPays, overtimePay, commissionPay, nonTaxableIncome, unpaidLeaveDays,
            studentLoanDeduction, legalExecutionRequested, otherPostTaxDeductions, taxAllowances,
            yearToDate, payrollMonthValue, directorRemuneration, warningLetterDeduction,
            customerReturnDeduction, otherPretaxDeduction, leaveRefundDays, withholdingTaxOverride,
            taxYear, remainingPayPeriods, taxpayerAge, BigDecimal.ZERO, BigDecimal.ZERO);
    }
    /**
     * Legacy 20-arg constructor: the full signature before {@code taxpayerAge} existed. Age defaults
     * to zero, i.e. unknown, so the 65+ exemption is not granted — which is what every call site
     * predating the field got.
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
        BigDecimal leaveRefundDays,
        BigDecimal withholdingTaxOverride,
        int taxYear,
        int remainingPayPeriods
    ) {
        this(
            baseSalary, specialPays, overtimePay, commissionPay, nonTaxableIncome, unpaidLeaveDays,
            studentLoanDeduction, legalExecutionRequested, otherPostTaxDeductions, taxAllowances,
            yearToDate, payrollMonthValue,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            leaveRefundDays, withholdingTaxOverride, taxYear, remainingPayPeriods,
            0
        );
    }

    /**
     * Legacy 18-arg constructor: the full signature as it stood before ป.96/2543 compliance added
     * {@code taxYear} and {@code remainingPayPeriods}. Both default to zero, which the calculator
     * reads as "not supplied" and handles exactly as it did before the fields existed -- no
     * year-scoped allowance restriction, and pay periods derived from the payroll month.
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
        BigDecimal leaveRefundDays,
        BigDecimal withholdingTaxOverride
    ) {
        this(
            baseSalary, specialPays, overtimePay, commissionPay, nonTaxableIncome, unpaidLeaveDays,
            studentLoanDeduction, legalExecutionRequested, otherPostTaxDeductions, taxAllowances,
            yearToDate, payrollMonthValue,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            leaveRefundDays, withholdingTaxOverride,
            0, 0
        );
    }

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
