package th.co.glr.hr.overtime;

/**
 * @param reportsToExecutive CEO-approval-reach follow-on (2026-09-01): true when this employee's
 *     {@code reports_to_employee_id} points at an active executive (division {@code md}, or a
 *     position containing "กรรมการ"). Computed by the same SQL fragment {@code
 *     ManagerApproverRepository} uses for its own routing decision ({@code
 *     ManagerApproverRepository#reportsToExecutiveSql}, spliced in by {@code
 *     OvertimeRepository#findEmployeeAccess}) so this and {@code hasManagerApprover} can never
 *     drift. Read by {@code OvertimeService#managesEmployee} — see its Javadoc.
 */
public record OvertimeEmployeeAccess(
    long employeeId,
    Long managerEmployeeId,
    Long divisionId,
    boolean active,
    boolean reportsToExecutive
) {
}
