package th.co.glr.hr.attendance.schedule;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * The expected working day for one employee on one date.
 *
 * <p>Immutable and free of Spring/config types on purpose: {@code AttendanceDailyCalculator} takes
 * one of these as an argument rather than reading configuration itself, so the calculator stays a
 * pure function and a future per-division schedule is a new {@link WorkScheduleResolver} rather
 * than a change to the calculation.
 *
 * <p><strong>§76:</strong> {@code lateMinutes} / {@code earlyLeaveMinutes} derived from this
 * schedule are reporting-only. Thai Labour Protection Act §76 forbids deducting wages as a penalty
 * for lateness or absence.
 *
 * @param zone         zone the punch timestamps are interpreted in (business-day zone)
 * @param workStart    expected clock-in time, e.g. 08:30
 * @param workEnd      expected clock-out time, e.g. 17:30
 * @param graceMinutes minutes after {@code workStart} before a check-in counts late. A
 *                     <strong>threshold, not an allowance</strong> — see {@link #isLate}.
 * @param workdays     days of the week the schedule applies to; other days are non-workdays
 */
public record WorkSchedule(
    ZoneId zone,
    LocalTime workStart,
    LocalTime workEnd,
    int graceMinutes,
    Set<DayOfWeek> workdays
) {
    public WorkSchedule {
        if (zone == null || workStart == null || workEnd == null || workdays == null) {
            throw new IllegalArgumentException("WorkSchedule fields must not be null");
        }
        if (!workEnd.isAfter(workStart)) {
            throw new IllegalArgumentException("workEnd must be after workStart");
        }
        if (graceMinutes < 0) {
            throw new IllegalArgumentException("graceMinutes must not be negative");
        }
        workdays = Set.copyOf(workdays);
    }

    public boolean isWorkday(LocalDate date) {
        return date != null && workdays.contains(date.getDayOfWeek());
    }

    /**
     * The instant that separates "this lone punch was an arrival" from "this lone punch was a
     * departure" — the midpoint of the working window (13:00 for 08:30–17:30).
     *
     * <p>Derived, never hardcoded, so moving the working hours moves the boundary with them.
     */
    public LocalTime midpoint() {
        long halfDay = Duration.between(workStart, workEnd).toMinutes() / 2;
        return workStart.plusMinutes(halfDay);
    }

    /**
     * True when a check-in at {@code checkIn} counts as late.
     *
     * <p>Grace is a threshold: with a 5-minute grace, 08:34 is not late at all, and 08:40 is late
     * by <em>10</em> minutes (measured from 08:30), not 4. Callers must use {@link #lateMinutes}
     * for the magnitude rather than subtracting the grace themselves.
     */
    public boolean isLate(LocalTime checkIn) {
        return checkIn != null && checkIn.isAfter(workStart.plusMinutes(graceMinutes));
    }

    /** Minutes late measured from {@code workStart}, or 0 when within grace. See {@link #isLate}. */
    public int lateMinutes(LocalTime checkIn) {
        if (!isLate(checkIn)) {
            return 0;
        }
        return (int) Duration.between(workStart, checkIn).toMinutes();
    }

    /** Minutes left early measured from {@code workEnd}, or 0 when at/after it. */
    public int earlyLeaveMinutes(LocalTime checkOut) {
        if (checkOut == null || !checkOut.isBefore(workEnd)) {
            return 0;
        }
        return (int) Duration.between(checkOut, workEnd).toMinutes();
    }
}
