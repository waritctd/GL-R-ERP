package th.co.glr.hr.specialmoney;

import java.time.LocalDate;

/** Everything SpecialMoneyPolicyEvaluator needs to know about the requesting employee. Deliberately does not
 * carry a repository reference -- the caller assembles this from real data before invoking the pure evaluator. */
public record EmployeeEligibilitySnapshot(
    Long employeeId,
    LocalDate hireDate,
    LocalDate confirmDate,
    Integer probationDays,
    String departmentSourceCode,
    String positionSourceCode,
    boolean isActive,
    LocalDate today) {
}
