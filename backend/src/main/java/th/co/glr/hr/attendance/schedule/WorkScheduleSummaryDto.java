package th.co.glr.hr.attendance.schedule;

import java.time.LocalTime;
import java.util.List;

/**
 * One {@code hr.work_schedule} catalogue entry, for the picker a future admin UI builds an
 * assignment against. {@code workdays} is ISO day-of-week numbers (1 = Monday .. 7 = Sunday),
 * matching {@code hr.work_schedule_day.day_of_week} directly — no translation needed either side.
 */
record WorkScheduleSummaryDto(
    int workScheduleId,
    String code,
    String nameTh,
    LocalTime workStart,
    LocalTime workEnd,
    int graceMinutes,
    boolean requiresCheckOut,
    List<Integer> workdays
) {
}
