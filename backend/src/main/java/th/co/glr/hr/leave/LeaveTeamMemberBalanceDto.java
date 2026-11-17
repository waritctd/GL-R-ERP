package th.co.glr.hr.leave;

import java.util.List;

/** GET /api/leave/balances/team: one direct report's leave balances, in the exact per-type shape
 * LeaveService#balanceFor already produces for the single-employee /api/leave/balances endpoint. */
public record LeaveTeamMemberBalanceDto(
    long employeeId,
    String employeeCode,
    String employeeName,
    String departmentName,
    List<LeaveBalanceDto> balances
) {
}
