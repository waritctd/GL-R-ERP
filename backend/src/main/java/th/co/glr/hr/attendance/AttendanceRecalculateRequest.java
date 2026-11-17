package th.co.glr.hr.attendance;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Range to re-derive. employeeId narrows to one person; omit it for everyone. Recalculation is idempotent and
 * never overwrites a row HR has marked as manually overridden, so re-running a wide range is safe. */
record AttendanceRecalculateRequest(
    @NotNull LocalDate fromDate,
    @NotNull LocalDate toDate,
    Long employeeId
) {
}
