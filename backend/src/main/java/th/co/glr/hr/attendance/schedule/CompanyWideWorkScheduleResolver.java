package th.co.glr.hr.attendance.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import th.co.glr.hr.config.AppProperties;

/**
 * The only {@link WorkScheduleResolver} today: every employee shares one configured schedule, so
 * all three arguments are ignored.
 *
 * <p>The schedule is parsed once at construction, not per call — a malformed {@code work-start} or
 * {@code workdays} entry then fails fast at startup instead of throwing on the first punch of the
 * day, which is the difference between a deploy that won't boot and one that silently stops
 * recording attendance.
 */
@Component
public class CompanyWideWorkScheduleResolver implements WorkScheduleResolver {
    private final WorkSchedule schedule;

    public CompanyWideWorkScheduleResolver(AppProperties properties) {
        this.schedule = build(properties.getAttendance().getSchedule());
    }

    @Override
    public WorkSchedule resolve(long employeeId, Long divisionId, LocalDate workDate) {
        return schedule;
    }

    private static WorkSchedule build(AppProperties.Schedule config) {
        return new WorkSchedule(
            parseZone(config.getZone()),
            parseTime(config.getWorkStart(), "app.attendance.schedule.work-start"),
            parseTime(config.getWorkEnd(), "app.attendance.schedule.work-end"),
            config.getGraceMinutes(),
            parseWorkdays(config.getWorkdays())
        );
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value.trim());
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                "app.attendance.schedule.zone is not a valid zone id: " + value, ex);
        }
    }

    private static LocalTime parseTime(String value, String property) {
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalStateException(
                property + " must be HH:mm (24-hour), got: " + value, ex);
        }
    }

    private static Set<DayOfWeek> parseWorkdays(Iterable<String> values) {
        Set<DayOfWeek> days = new LinkedHashSet<>();
        for (String value : values) {
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                days.add(DayOfWeek.valueOf(trimmed.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException(
                    "app.attendance.schedule.workdays contains an unknown day: " + trimmed, ex);
            }
        }
        if (days.isEmpty()) {
            throw new IllegalStateException("app.attendance.schedule.workdays must not be empty");
        }
        return days;
    }
}
