package th.co.glr.hr.specialmoney;

import java.time.LocalDate;

/**
 * Everything {@link SpecialMoneyPolicyEvaluator} needs to know about the requesting employee.
 * Deliberately does not carry a repository reference -- the caller assembles this from real data
 * before invoking the pure evaluator.
 *
 * @param departmentSourceCode {@code hr.department.source_code} for the employee's current
 *     department; used only by {@code UNIFORM_PREPROBATION_KIT}'s sales-support gate.
 * @param today the evaluation "as of" date -- normally the request's submission time, injected
 *     rather than read from a clock so the evaluator stays a pure function.
 */
public record EmployeeEligibilitySnapshot(
    Long employeeId,
    LocalDate hireDate,
    LocalDate confirmDate,
    Integer probationDays,
    String departmentSourceCode,
    boolean isActive,
    LocalDate today) {
}
