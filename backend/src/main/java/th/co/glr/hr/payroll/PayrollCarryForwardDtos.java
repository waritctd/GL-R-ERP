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
 * <p>The carried fields are special_pay_1..9, meal_allowance, non_taxable_income,
 * student_loan_deduction, legal_execution_deduction. WHICH of the nine special-pay slots (and meal
 * allowance) actually carries for a given employee is governed entirely by {@code
 * hr.payroll_component_carry_forward} (V98, per-employee per-component) — not by a hardcoded slot
 * list here. commission ({@code CommissionService}-fed, separate from any พิเศษ slot) and other
 * event-driven fields (warning-letter / customer-return deductions, other pre/post-tax deductions)
 * are still never carried — those describe THIS month's events, not a standing recurring amount.
 *
 * <p>Supersedes the earlier special_pay_1..5-only design from the ป.96 branch (117), which rested on
 * a classification the owner later contradicted (2026-07-29): พิเศษ 1-9 except 7 (คอมมิชชั่น, per the
 * accountant's-workbook renumbering, handoff section 9d -- F7 correction, Opus review 2026-07-30; this
 * was พิเศษ 6 before that renumbering) are occasional, not standing allowances, so a hardcoded 1..5
 * carry-list could re-propose a one-off bonus as if it recurred. V98's per-employee, per-component
 * carry-forward table (seeded from the accountant's ledger at a 70%-same-value rule) replaces the
 * hardcoded list entirely -- see that migration's comment and {@code
 * PayrollRepository#findCarryForwardSuggestions}.
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
        // Extended to all nine พิเศษ slots (Opus review, 2026-07-29): V98 seeds carry-forward flags
        // for SPECIAL_PAY_6/7/9 too (the accountant's ledger names ค่า GPRS(เพิ่ม) and เงินรางวัล as
        // recurring for several employees), and the DTO used to have no field to carry them into.
        BigDecimal specialPay6,
        BigDecimal specialPay7,
        BigDecimal specialPay8,
        BigDecimal specialPay9,
        // ค่าอาหาร (V97/V98) -- also carry-forward-flagged per employee, same mechanism as the พิเศษ
        // slots.
        BigDecimal mealAllowance,
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
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null
            );
        }
    }

    public record SuggestedInputsResponse(LocalDate payrollMonth, List<SuggestedInputRow> suggestions) {}
}
