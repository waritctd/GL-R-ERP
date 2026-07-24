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
 * special_pay_7/8 (KPI / one-off bonus), and other event-driven fields (warning-letter /
 * customer-return deductions, other pre/post-tax deductions) — those describe THIS month's events,
 * not a standing recurring amount.
 *
 * <p>Leave -&gt; payroll unpaid-day deduction (2026-07-23): {@code unpaidLeaveDays} IS event-driven
 * (this month's approved-beyond-quota leave, from {@code LeaveRepository
 * #findUnpaidLeaveDaysByEmployeeForMonth}) but is included here anyway because, unlike the other
 * event fields, it has a real system of record ({@code hr.leave_request}) rather than being typed
 * fresh by HR every run. {@code pendingUnpaidLeaveCorrectionDays} is the unresolved
 * cancel-after-close credit total from {@code LeaveRepository#findPendingPayrollCorrectionsByEmployee}
 * — surfaced so HR can see it, NOT auto-netted into {@code unpaidLeaveDays}, and NOT auto-resolved
 * once shown (see the V85 migration comment and {@code LeaveService#cancel} for why). HR must
 * manually factor it into the submitted {@code unpaidLeaveDays} value.
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
        BigDecimal legalExecutionDeduction,
        BigDecimal unpaidLeaveDays,
        BigDecimal pendingUnpaidLeaveCorrectionDays,
        // Withholding-tax override (2026-07-24, V88): the PER-RUN value HR typed on the prior processed
        // line, surfaced so the payroll page pre-fills it again (like studentLoanDeduction). NULLABLE:
        // null = none typed last run -> the field starts blank and the employee's STANDING override (if
        // any) re-applies on its own -- the standing value is deliberately NOT carried here.
        BigDecimal withholdingTaxOverride
    ) {
        /** A row with only the identity set — used when an employee has leave-derived figures to
         *  surface but no special-pay carry-forward row (e.g. their first-ever processed month). */
        public static SuggestedInputRow empty(Long employeeId) {
            return new SuggestedInputRow(
                employeeId,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null
            );
        }
    }

    public record SuggestedInputsResponse(LocalDate payrollMonth, List<SuggestedInputRow> suggestions) {}
}
