package th.co.glr.hr.leave;

import java.time.LocalDate;

/**
 * §5.3.3 contiguous-leave-type gate: the date range of one SUBMITTED/APPROVED leave request, for a
 * single employee/leave-type pair already known to the caller. See
 * {@link LeaveRepository#findActiveRequestsByType} and {@link LeaveService}'s contiguity check.
 */
record LeaveRequestSpan(LocalDate startDate, LocalDate endDate) {
}
