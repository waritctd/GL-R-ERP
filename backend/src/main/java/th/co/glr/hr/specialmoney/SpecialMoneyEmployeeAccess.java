package th.co.glr.hr.specialmoney;

/**
 * Shape of {@code hr.employee} needed to decide who manages whom for special-money review/cancel
 * gates. Deliberately a local record rather than reusing {@code OvertimeEmployeeAccess} -- the two
 * modules stay decoupled even though the shape is identical.
 */
public record SpecialMoneyEmployeeAccess(
    long employeeId,
    Long managerEmployeeId,
    Long divisionId,
    boolean isActive) {
}
