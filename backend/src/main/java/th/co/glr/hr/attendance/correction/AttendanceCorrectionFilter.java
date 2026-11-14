package th.co.glr.hr.attendance.correction;

/** List scope, mirroring OvertimeFilter/LeaveFilter's shape but without a manager dimension -- this feature has
 * no manager approval stage (CEO-only, see AttendanceCorrectionService#requireCeo), so there is no ฝ่าย-scoped
 * reviewer to widen the query for. */
public record AttendanceCorrectionFilter(
    Long employeeId,
    boolean viewAll,
    AttendanceCorrectionStatus status
) {
}
