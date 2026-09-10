package th.co.glr.hr.leave;

import java.util.List;

/**
 * GET /api/leave/balances/team (manager team-quota summary, 2026-09): one direct report's leave
 * balances, in the exact per-type shape {@link LeaveService#balanceFor} already produces for the
 * single-employee {@code /api/leave/balances} endpoint -- see {@link LeaveService#teamBalances}'s
 * Javadoc for why the SCOPE (which employees appear here at all) is deliberately computed by the
 * SAME {@link LeaveRepository#findEmployeeOptions} query {@code /api/leave/employees} already uses,
 * not a new predicate.
 */
public record LeaveTeamMemberBalanceDto(
    long employeeId,
    String employeeCode,
    String employeeName,
    String departmentName,
    List<LeaveBalanceDto> balances
) {
}
