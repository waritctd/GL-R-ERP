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
    BigDecimal leaveDeductionRefund
) {
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
            BigDecimal.ZERO, BigDecimal.ZERO
        );
    }
}
