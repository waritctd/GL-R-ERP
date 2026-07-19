package th.co.glr.hr.attendance.daily;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import th.co.glr.hr.attendance.schedule.WorkSchedule;

/**
 * Derives one {@code hr.attendance_daily} row from one employee's punches on one business date.
 *
 * <p>Pure: no Spring wiring beyond being a bean, no database, no clock. Every input arrives as an
 * argument, so the whole rule set is unit-testable without a container. It is a {@code @Component}
 * only so callers can inject it; it holds no state.
 *
 * <h2>Direction is decided by chronology, never by {@code punch_state}</h2>
 * {@code V7__attendance_schema.sql} documents {@code punch_state} as not trustworthy for IN/OUT
 * direction, so {@code check_in = MIN(punch_time)} and {@code check_out = MAX(punch_time)}. Besides
 * being the only reliable signal, min/max satisfies the table's
 * {@code chk_attendance_daily_checkout_after_checkin} CHECK by construction.
 *
 * <h2>Grace is a threshold, not an allowance</h2>
 * With a 5-minute grace on an 08:30 start: 08:34 is 0 late minutes; 08:40 is <strong>10</strong>
 * late minutes, measured from 08:30 — not 4. See {@link WorkSchedule#lateMinutes}.
 *
 * <h2>A missing scan is never imputed</h2>
 * If only one punch exists, the other side stays null and the day is flagged. Inventing the missing
 * time would fabricate late/early minutes, which under Thai Labour Protection Act §76 is precisely
 * the failure mode to avoid — those figures are reporting-only and must never become a deduction.
 *
 * <h2>Known non-goals</h2>
 * Mid-day punches (lunch, moving between sites) are counted but not modelled — there is no break or
 * shift concept in the schema to model them against. Night shifts that wrap past midnight are not
 * supported: days are grouped by the stored {@code work_date}, which ingest already normalises to
 * the business zone, and re-deriving it here would create a second, divergent definition.
 */
@Component
public class AttendanceDailyCalculator {

    /**
     * Computes the daily row.
     *
     * @param employeeId              resolved employee; unmapped badges never reach here because
     *                                {@code attendance_daily.employee_id} is NOT NULL
     * @param workDate                the stored business date; not re-derived from punch timestamps
     * @param punches                 that date's punches, any order (sorted internally)
     * @param schedule                the schedule in force for this employee on this date
     * @param approvedOvertimeMinutes minutes from APPROVED overtime requests only — a
     *                                MANAGER_APPROVED request must contribute 0
     * @throws IllegalArgumentException if {@code punches} is empty; punchless days are never
     *                                  materialised (absence is derived at read time, and
     *                                  {@code site_code} is NOT NULL so there would be no site to
     *                                  store)
     */
    public AttendanceDailyRecord calculate(
            long employeeId,
            LocalDate workDate,
            List<PunchRecord> punches,
            WorkSchedule schedule,
            int approvedOvertimeMinutes) {
        if (punches == null || punches.isEmpty()) {
            throw new IllegalArgumentException(
                "A day with no punches is never stored; absence is derived at read time");
        }

        List<PunchRecord> ordered = punches.stream()
            .sorted(Comparator.comparing(PunchRecord::punchTime).thenComparingLong(PunchRecord::punchId))
            .toList();

        boolean workday = schedule.isWorkday(workDate);
        Set<AttendanceDayFlag> flags = EnumSet.noneOf(AttendanceDayFlag.class);

        PunchRecord checkInPunch;
        PunchRecord checkOutPunch;
        if (ordered.size() == 1) {
            PunchRecord lone = ordered.get(0);
            if (isBeforeOrAtMidpoint(lone, schedule)) {
                // Arrived and never scanned out.
                checkInPunch = lone;
                checkOutPunch = null;
                flags.add(AttendanceDayFlag.MISSING_CHECK_OUT);
            } else {
                // Scanned out having never scanned in. Treating this as an arrival instead would
                // report someone as turning up at 17:25 and manufacture ~530 late minutes.
                checkInPunch = null;
                checkOutPunch = lone;
                flags.add(AttendanceDayFlag.MISSING_CHECK_IN);
            }
        } else {
            checkInPunch = ordered.get(0);
            checkOutPunch = ordered.get(ordered.size() - 1);
        }

        OffsetDateTime checkIn = checkInPunch == null ? null : checkInPunch.punchTime();
        OffsetDateTime checkOut = checkOutPunch == null ? null : checkOutPunch.punchTime();

        int lateMinutes = 0;
        int earlyLeaveMinutes = 0;
        if (workday) {
            if (checkIn != null) {
                lateMinutes = schedule.lateMinutes(localTime(checkIn, schedule));
                if (lateMinutes > 0) {
                    flags.add(AttendanceDayFlag.LATE);
                }
            }
            if (checkOut != null) {
                earlyLeaveMinutes = schedule.earlyLeaveMinutes(localTime(checkOut, schedule));
                if (earlyLeaveMinutes > 0) {
                    flags.add(AttendanceDayFlag.EARLY_LEAVE);
                }
            }
        } else {
            // People do come in at weekends. Record the punches; penalise nothing.
            flags.add(AttendanceDayFlag.NON_WORKDAY);
        }

        int overtimeMinutes = Math.max(0, approvedOvertimeMinutes);
        if (overtimeMinutes > 0) {
            flags.add(AttendanceDayFlag.OVERTIME_APPROVED);
        } else if (checkOut != null && workedPastEnd(checkOut, schedule)) {
            flags.add(AttendanceDayFlag.WORKED_LATE_UNAPPROVED);
        }

        Integer totalMinutes = (checkIn == null || checkOut == null)
            ? null
            : (int) Duration.between(checkIn, checkOut).toMinutes();

        return new AttendanceDailyRecord(
            employeeId,
            workDate,
            siteOf(checkInPunch, checkOutPunch),
            checkInPunch == null ? null : checkInPunch.punchId(),
            checkOutPunch == null ? null : checkOutPunch.punchId(),
            checkIn,
            checkOut,
            totalMinutes,
            lateMinutes,
            earlyLeaveMinutes,
            overtimeMinutes,
            ordered.size(),
            // Rows only exist for days that have punches, so a stored row is never an absence.
            // DashboardRepository counts is_absent = FALSE as "present"; keep that true.
            false,
            statusOf(workday, checkIn, checkOut, lateMinutes),
            flags
        );
    }

    /**
     * The day's site is where it started — the check-in punch's, falling back to the check-out
     * punch's when only an afternoon scan exists. A day's punches can span sites; storing the
     * opening one keeps the row consistent with the {@code check_in_punch_id} beside it, and the
     * punch ledger remains the source of truth for per-scan detail.
     */
    private static String siteOf(PunchRecord checkInPunch, PunchRecord checkOutPunch) {
        PunchRecord source = checkInPunch != null ? checkInPunch : checkOutPunch;
        return source == null ? null : source.siteCode();
    }

    private static AttendanceDayStatus statusOf(
            boolean workday, OffsetDateTime checkIn, OffsetDateTime checkOut, int lateMinutes) {
        if (!workday) {
            return AttendanceDayStatus.NON_WORKDAY;
        }
        if (checkIn == null) {
            return AttendanceDayStatus.MISSING_CHECK_IN;
        }
        if (checkOut == null) {
            return AttendanceDayStatus.MISSING_CHECK_OUT;
        }
        return lateMinutes > 0 ? AttendanceDayStatus.LATE : AttendanceDayStatus.PRESENT;
    }

    private static boolean isBeforeOrAtMidpoint(PunchRecord punch, WorkSchedule schedule) {
        return !localTime(punch.punchTime(), schedule).isAfter(schedule.midpoint());
    }

    /** Reuses the grace threshold so "late in" and "stayed late" share one tolerance. */
    private static boolean workedPastEnd(OffsetDateTime checkOut, WorkSchedule schedule) {
        return localTime(checkOut, schedule)
            .isAfter(schedule.workEnd().plusMinutes(schedule.graceMinutes()));
    }

    private static LocalTime localTime(OffsetDateTime instant, WorkSchedule schedule) {
        return instant.atZoneSameInstant(schedule.zone()).toLocalTime();
    }
}
