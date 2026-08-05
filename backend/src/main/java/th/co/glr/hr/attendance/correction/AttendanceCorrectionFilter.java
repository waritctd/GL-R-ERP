package th.co.glr.hr.attendance.correction;

/**
 * List scope, mirroring {@code OvertimeFilter}/{@code LeaveFilter}'s shape but without a manager
 * dimension -- this feature has no manager approval stage (CEO-only, see
 * {@link AttendanceCorrectionService#requireCeo}), so there is no ฝ่าย-scoped reviewer to widen the
 * query for.
 *
 * <p>Exactly one of {@code employeeId} (a specific employee's own history, self or CEO-requested)
 * or {@code viewAll} (CEO reading every employee's queue) drives the {@code WHERE} clause -- see
 * {@link AttendanceCorrectionRepository#findRequests}.
 */
public record AttendanceCorrectionFilter(
    Long employeeId,
    boolean viewAll,
    AttendanceCorrectionStatus status
) {
}
