package th.co.glr.hr.specialmoney;

import java.time.LocalDate;

/**
 * Everything {@link SpecialMoneyPolicyEvaluator} needs to know about the requesting employee.
 * Deliberately does not carry a repository reference -- the caller assembles this from real data
 * before invoking the pure evaluator.
 *
 * @param departmentSourceCode {@code hr.department.source_code} for the employee's current
 *     department; used only by {@code UNIFORM_PREPROBATION_KIT}'s job-function gate.
 * @param positionSourceCode {@code hr.position.source_code} for the employee's current position.
 *     Also only used by that gate — §2.1.3 names FOUR job functions and they do not all live at the
 *     same level of the org data: สนับสนุนฝ่ายขาย is a department, ขับรถ is a position.
 * @param today the evaluation "as of" date -- normally the request's submission time, injected
 *     rather than read from a clock so the evaluator stays a pure function.
 */
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
