package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Read-only preview of what {@code SpecialMoneyService#ceoApproveFrom} would enforce for a request
 * evaluated RIGHT NOW, computed via {@code SpecialMoneyService#computeApprovalCeiling} -- the same
 * calculation the approval itself uses, so a preview fetched and an approval submitted at the SAME
 * instant agree exactly. They are not guaranteed to agree across TWO instants, though: usage,
 * the payroll cutoff/roll-forward, and policy amounts can all change in the gap between a CEO
 * opening the preview and actually submitting the approval (most commonly, another request for the
 * same employee gets decided in between). The server re-evaluates the ceiling fresh at approval
 * time regardless of what this DTO returned, and remains the sole authority -- this is a
 * best-effort display, not a contract the caller may skip its own 400 handling for.
 *
 * @param requestedAmount what the employee originally asked for -- echoed back so the caller does
 *     not need a second lookup to compare against {@code eligibleAmount}.
 * @param eligibleAmount the policy ceiling {@code ceoApproveFrom} would compare an approved amount
 *     against right now (see {@code PolicyDecision#eligibleAmount}'s Javadoc for what this reflects
 *     -- the computed/capped amount, zeroed only on a hard block).
 * @param payrollMonth the payroll month a fresh approval would be stamped with right now (the
 *     25th-cutoff, rolled past any already-PROCESSED month).
 */
public record SpecialMoneyApprovalPreviewDto(
    BigDecimal requestedAmount, BigDecimal eligibleAmount, LocalDate payrollMonth) {
}
