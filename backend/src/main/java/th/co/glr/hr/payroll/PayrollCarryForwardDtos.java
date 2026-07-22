package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Special-pay carry-forward (2026-07-23): read-only suggestions for {@code GET
 * /api/payroll/suggested-inputs}, used by the frontend to pre-fill a brand-new monthly payroll run
 * from each employee's most recent PRIOR processed {@code payroll_line}. HR still edits/overrides
 * every value before preview/process, and the explicit value HR submits is always what is stored —
 * see {@link PayrollService#suggestedInputs}. This does not change {@code preview()}/{@code
 * process()} in any way; it is a separate, additive read path.
 *
 * <p>Only the recurring fields are carried: special_pay_1..5 (company allowances), non_taxable_income,
 * student_loan_deduction, legal_execution_deduction. Deliberately excluded: special_pay_6
 * (commission — {@code CommissionService} already feeds this; carrying it would double-count),
 * special_pay_7/8 (KPI / one-off bonus), and every event-driven field (unpaid leave days,
 * warning-letter / customer-return deductions, other pre/post-tax deductions) — those describe THIS
 * month's events, not a standing recurring amount.
 */
public final class PayrollCarryForwardDtos {
    private PayrollCarryForwardDtos() {
    }

    public record SuggestedInputRow(
        Long employeeId,
        BigDecimal specialPay1,
        BigDecimal specialPay2,
        BigDecimal specialPay3,
        BigDecimal specialPay4,
        BigDecimal specialPay5,
        BigDecimal nonTaxableIncome,
        BigDecimal studentLoanDeduction,
        BigDecimal legalExecutionDeduction
    ) {}

    public record SuggestedInputsResponse(LocalDate payrollMonth, List<SuggestedInputRow> suggestions) {}
}
