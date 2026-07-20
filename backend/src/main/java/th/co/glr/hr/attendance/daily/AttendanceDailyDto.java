package th.co.glr.hr.attendance.daily;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One day of one employee's attendance, as served to the UI.
 *
 * <p>snake_case over the wire to match the rest of the attendance API.
 *
 * <p><strong>§76:</strong> {@code late_minutes} / {@code early_leave_minutes} are for display only.
 * Thai Labour Protection Act §76 forbids deducting wages as a penalty for lateness or absence.
 */
public record AttendanceDailyDto(
    @JsonProperty("employee_id") long employeeId,
    @JsonProperty("employee_code") String employeeCode,
    @JsonProperty("employee_name") String employeeName,
    @JsonProperty("nick_name") String nickName,
    @JsonProperty("position_th") String positionTh,
    @JsonProperty("work_date") LocalDate workDate,
    @JsonProperty("is_workday") boolean workday,
    @JsonProperty("check_in") OffsetDateTime checkIn,
    @JsonProperty("check_out") OffsetDateTime checkOut,
    @JsonProperty("total_minutes") Integer totalMinutes,
    @JsonProperty("late_minutes") int lateMinutes,
    @JsonProperty("early_leave_minutes") int earlyLeaveMinutes,
    @JsonProperty("overtime_minutes") int overtimeMinutes,
    @JsonProperty("punch_count") int punchCount,
    @JsonProperty("site_code") String siteCode,
    @JsonProperty("status") AttendanceDayStatus status,
    @JsonProperty("flags") List<AttendanceDayFlag> flags,
    @JsonProperty("is_manual_override") boolean manualOverride,
    @JsonProperty("notes") String notes
) {
}
