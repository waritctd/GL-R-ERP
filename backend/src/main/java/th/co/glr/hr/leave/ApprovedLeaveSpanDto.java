package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One APPROVED leave request's span, for a caller OUTSIDE this package that needs to overlay leave
 * onto attendance data -- see {@code th.co.glr.hr.attendance.AttendanceMonthlySummaryService}'s
 * "Leave-day attribution" javadoc for the whole-day/sub-day rule this feeds, and
 * {@link LeaveRepository#findApprovedLeaveOverlapping} for the query that produces it.
 *
 * <p>Public (unlike {@link EmployeeLeaveSpan}/{@link LeaveRequestSpan}, which never leave this
 * package) specifically so the attendance package can consume it without either package reaching
 * into the other's internals.
 *
 * <p>Carries {@code leaveTypeNameTh} -- joined from {@code hr.leave_type} -- rather than leaving
 * the caller to look it up or hardcode a Thai label per {@code leaveTypeCode}: HR can edit
 * {@code hr.leave_type.name_th} (V116+), so that table is the one source of truth for the label, and
 * a hardcoded copy anywhere else would silently drift from it the next time HR renames a type.
 *
 * <p>{@code startTime}/{@code endTime} are both null for a whole-day request and both non-null for
 * a timed one ({@code chk_leave_time_pairing}, V90). A non-null pair no longer implies
 * {@code startDate.equals(endDate)} -- V166 (2026-09-10) relaxed {@code chk_leave_time_single_day}
 * to let a timed request span multiple calendar days; see {@code AttendanceMonthlySummaryService}'s
 * own "Leave-day attribution" Javadoc for how a multi-day timed span is now attributed.
 */
public record ApprovedLeaveSpanDto(
    long employeeId,
    String leaveTypeCode,
    String leaveTypeNameTh,
    LocalDate startDate,
    LocalDate endDate,
    LocalTime startTime,
    LocalTime endTime,
    BigDecimal totalDays
) {
}
