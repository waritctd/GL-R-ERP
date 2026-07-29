package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.util.List;

public record PayrollLineDto(
    Long id,
    long employeeId,
    String employeeCode,
    String employeeName,
    String departmentName,
    String bankName,
    String bankAccount,
    BigDecimal baseSalary,
    BigDecimal dailyRate,
    BigDecimal hourlyRate,
    List<PayrollSpecialPayDto> specialPays,
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
    // Cancel-after-close reversal, AUTO-REFUND (2026-07-23). See PayrollCalculation.
    BigDecimal leaveRefundDays,
    BigDecimal leaveDeductionRefund,
    // Withholding-tax override (2026-07-24, V88): the PER-RUN HR-typed override value stored for this
    // line (nullable; null = none typed this run). This is the TYPED per-run value only -- NOT the
    // standing employee override and NOT the resolved effective amount (the effective withheld amount
    // lives in withholdingTax above). Persisted so it carries forward to next month's run.
    BigDecimal withholdingTaxOverride,
    // ป.96/2543 compliance (2026-07-28, V92). The two limbs คำชี้แจง ภ.ง.ด.1 keeps apart --
    // เงินได้ที่จ่ายตามปกติ (ข้อ 2.1) and เงินพิเศษที่จ่ายเป็นครั้งคราว (ข้อ 2.5). Persisted on
    // payroll_line because the year-to-date carry-forward needs each limb separately: next period's
    // ข้อ 2.5 difference is netted against what was withheld on the VARIABLE limb specifically, and
    // that is unrecoverable once the two are summed. regularTaxableIncome + variableTaxableIncome ==
    // grossTaxableIncome, and regularWithholdingTax + variableWithholdingTax == withholdingTax.
    BigDecimal regularTaxableIncome,
    BigDecimal variableTaxableIncome,
    BigDecimal regularWithholdingTax,
    BigDecimal variableWithholdingTax,
    // Dedicated one-off pay (2026-07-29, V94), both inside the ข้อ 2.5 variable limb above.
    BigDecimal bonusPay,
    BigDecimal otherOneOffPay,
    // Over-withholding this tax year that payroll cannot hand back (2026-07-29, V94). Computed by
    // PayrollCalculator and surfaced all the way to the payslip: a stranded excess otherwise shows up
    // only as a 0.00 withholding line, which is indistinguishable from "nothing was due".
    BigDecimal excessWithheldToDate
) {
    /**
     * Legacy 40-arg constructor: the full signature as it stood before the ป.96/2543 limb split. Keeps
     * every 40-arg positional call site compiling; the four new fields default to zero. Callers at
     * this arity are pre-split call sites and tests, for which "all regular, nothing variable" is the
     * faithful reading — see {@link PayrollYearToDate}'s legacy constructor for the same argument.
     */
    public PayrollLineDto(
        Long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String bankName,
        String bankAccount,
        BigDecimal baseSalary,
        BigDecimal dailyRate,
        BigDecimal hourlyRate,
        List<PayrollSpecialPayDto> specialPays,
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
        BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction,
        BigDecimal leaveRefundDays,
        BigDecimal leaveDeductionRefund,
        BigDecimal withholdingTaxOverride
    ) {
        this(
            id, employeeId, employeeCode, employeeName, departmentName, bankName, bankAccount,
            baseSalary, dailyRate, hourlyRate, specialPays, specialPayTotal, overtimePay, commissionPay,
            grossEarnings, nonTaxableIncome, unpaidLeaveDays, unpaidLeaveDeduction, grossTaxableIncome,
            ssoWageBase, socialSecurity, projectedAnnualIncome, taxExpenseDeduction, taxAllowanceTotal,
            taxableAnnualIncome, annualTax, withholdingTax, studentLoanDeduction, legalExecutionDeduction,
            otherPostTaxDeductions, totalDeductions, netPay, calculationNote,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            leaveRefundDays, leaveDeductionRefund, withholdingTaxOverride,
            grossTaxableIncome == null ? BigDecimal.ZERO : grossTaxableIncome, BigDecimal.ZERO,
            withholdingTax == null ? BigDecimal.ZERO : withholdingTax, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );
    }

    /**
     * Legacy 37-arg constructor: the full signature as it stood right before {@code
     * leaveRefundDays}/{@code leaveDeductionRefund} existed. Several test files (including {@code
     * PayrollServiceTest} and {@code PayrollRepositoryIntegrationTest}, which must not be edited)
     * construct this record positionally at that arity; both new fields default to zero, which is a
     * no-op on every downstream figure (see {@link PayrollCalculator}).
     */
    public PayrollLineDto(
        Long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String bankName,
        String bankAccount,
        BigDecimal baseSalary,
        BigDecimal dailyRate,
        BigDecimal hourlyRate,
        List<PayrollSpecialPayDto> specialPays,
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
        BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction
    ) {
        this(
            id, employeeId, employeeCode, employeeName, departmentName, bankName, bankAccount,
            baseSalary, dailyRate, hourlyRate, specialPays, specialPayTotal, overtimePay, commissionPay,
            grossEarnings, nonTaxableIncome, unpaidLeaveDays, unpaidLeaveDeduction, grossTaxableIncome,
            ssoWageBase, socialSecurity, projectedAnnualIncome, taxExpenseDeduction, taxAllowanceTotal,
            taxableAnnualIncome, annualTax, withholdingTax, studentLoanDeduction, legalExecutionDeduction,
            otherPostTaxDeductions, totalDeductions, netPay, calculationNote,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            BigDecimal.ZERO, BigDecimal.ZERO, null
        );
    }

    /**
     * Legacy 39-arg constructor: the full signature as it stood right before {@code
     * withholdingTaxOverride} existed (i.e. through {@code leaveDeductionRefund}). Keeps every 39-arg
     * positional call site compiling unchanged; {@code withholdingTaxOverride} defaults to {@code null}
     * ("no per-run override typed"), a no-op on every stored/displayed figure.
     */
    public PayrollLineDto(
        Long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String bankName,
        String bankAccount,
        BigDecimal baseSalary,
        BigDecimal dailyRate,
        BigDecimal hourlyRate,
        List<PayrollSpecialPayDto> specialPays,
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
        BigDecimal directorRemuneration,
        BigDecimal warningLetterDeduction,
        BigDecimal customerReturnDeduction,
        BigDecimal otherPretaxDeduction,
        BigDecimal leaveRefundDays,
        BigDecimal leaveDeductionRefund
    ) {
        this(
            id, employeeId, employeeCode, employeeName, departmentName, bankName, bankAccount,
            baseSalary, dailyRate, hourlyRate, specialPays, specialPayTotal, overtimePay, commissionPay,
            grossEarnings, nonTaxableIncome, unpaidLeaveDays, unpaidLeaveDeduction, grossTaxableIncome,
            ssoWageBase, socialSecurity, projectedAnnualIncome, taxExpenseDeduction, taxAllowanceTotal,
            taxableAnnualIncome, annualTax, withholdingTax, studentLoanDeduction, legalExecutionDeduction,
            otherPostTaxDeductions, totalDeductions, netPay, calculationNote,
            directorRemuneration, warningLetterDeduction, customerReturnDeduction, otherPretaxDeduction,
            leaveRefundDays, leaveDeductionRefund, null
        );
    }
}
