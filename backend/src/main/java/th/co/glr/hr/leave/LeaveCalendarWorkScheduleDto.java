package th.co.glr.hr.leave;

import java.time.LocalTime;
import java.util.List;

/** The caller's own resolved WorkSchedule, shaped to mirror WorkScheduleSummaryDto field-for-field where a
 * resolved schedule actually has an equivalent: workStart/workEnd/ graceMinutes/requiresCheckOut/workdays (ISO
 * day-of-week numbers, 1 = Monday .. */
record LeaveCalendarWorkScheduleDto(
    LocalTime workStart,
    LocalTime workEnd,
    int graceMinutes,
    boolean requiresCheckOut,
    List<Integer> workdays
) {
}
