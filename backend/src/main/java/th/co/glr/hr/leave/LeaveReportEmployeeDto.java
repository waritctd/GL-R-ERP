package th.co.glr.hr.leave;

import java.time.LocalDate;
import java.util.List;

/**
 * One employee's section of the printable leave-records report (รายงานสรุปใบลางาน, 2026-09) --
 * either the sole section of an employee's own "me" report, or one of several sections in a
 * manager's grouped "team" report (owner ruling: ONE PDF, grouped per employee, not one PDF per
 * person -- see {@link LeaveService#teamLeaveReport}).
 *
 * <p>{@code balances} is the SAME {@link LeaveBalanceDto} list {@link LeaveService#balanceFor}
 * already produces for the on-screen quota panels ({@code /api/leave/balances} and the #914
 * {@code /api/leave/balances/team}) -- the printed quota summary block must read these numbers
 * verbatim, never a re-query, so the PDF and the portal can never disagree (see
 * {@link LeaveReportRenderer}).
 *
 * <p>{@code divisionNameTh}/{@code hireDate} are the two group-header fields
 * {@link LeaveRequestDto} does not carry -- sourced from {@link LeaveRepository#findContactDefaults}
 * and {@link LeaveRepository#findHireDate} respectively (both pre-existing reads, no new SQL). Either
 * can be {@code null} (a missing division assignment, or a {@code hire_date IS NULL} row) -- the
 * renderer prints a dash for either.
 *
 * <p>{@code requests} is the employee's own {@link LeaveRequestDto} rows for the report's selected
 * period, exactly what {@link LeaveService#list} already returns for that employee/date-range --
 * see that method's Javadoc for the overlap semantics ({@code start_date <= to AND end_date >=
 * from}, not a strict containment).
 */
public record LeaveReportEmployeeDto(
    long employeeId,
    String employeeCode,
    String employeeName,
    String divisionNameTh,
    LocalDate hireDate,
    List<LeaveBalanceDto> balances,
    List<LeaveRequestDto> requests
) {
}
