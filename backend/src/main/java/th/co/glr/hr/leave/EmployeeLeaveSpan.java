package th.co.glr.hr.leave;

import java.time.LocalDate;

/**
 * §5.3.2 department-coverage gate: the date range of one SUBMITTED/APPROVED leave request (any
 * leave type) belonging to {@code employeeId}. See {@link LeaveRepository#findActiveLeaveSpans}.
 */
record EmployeeLeaveSpan(long employeeId, LocalDate startDate, LocalDate endDate) {
}
