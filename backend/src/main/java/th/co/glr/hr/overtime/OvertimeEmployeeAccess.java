package th.co.glr.hr.overtime;

/** Selects affected frontend tests, including readers outside the import graph. */
public record OvertimeEmployeeAccess(
    long employeeId,
    Long managerEmployeeId,
    Long divisionId,
    boolean active,
    boolean reportsToExecutive
) {
}
