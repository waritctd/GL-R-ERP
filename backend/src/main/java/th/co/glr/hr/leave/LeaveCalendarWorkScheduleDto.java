package th.co.glr.hr.leave;

import java.time.LocalTime;
import java.util.List;

/**
 * The caller's own resolved {@code WorkSchedule} (see {@code LeaveRepository#resolveOwnSchedule}),
 * shaped to mirror {@code WorkScheduleSummaryDto} (attendance/schedule/) field-for-field where a
 * resolved schedule actually has an equivalent: {@code workStart}/{@code workEnd}/
 * {@code graceMinutes}/{@code requiresCheckOut}/{@code workdays} (ISO day-of-week numbers, 1 =
 * Monday .. 7 = Sunday -- same convention as that DTO, so no translation is needed on either side).
 *
 * <p>{@code workScheduleId}/{@code code} are deliberately NOT carried here: those identify a row in
 * the {@code hr.work_schedule} admin catalogue, which {@code WorkScheduleSummaryDto} reads directly.
 * A <em>resolved</em> schedule (this DTO) comes from {@code TieredWorkScheduleResolver#resolve},
 * whose return type ({@code WorkSchedule}) is a plain value object with no catalogue id attached --
 * inventing one here would imply an identity this data does not actually carry.
 */
record LeaveCalendarWorkScheduleDto(
    LocalTime workStart,
    LocalTime workEnd,
    int graceMinutes,
    boolean requiresCheckOut,
    List<Integer> workdays
) {
}
