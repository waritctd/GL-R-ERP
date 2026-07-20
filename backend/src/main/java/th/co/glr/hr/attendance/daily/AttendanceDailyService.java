package th.co.glr.hr.attendance.daily;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.attendance.schedule.WorkSchedule;
import th.co.glr.hr.attendance.schedule.WorkScheduleResolver;

/**
 * Derives and serves {@code hr.attendance_daily}.
 *
 * <p>Every write path funnels into {@link #recalculate(EmployeeDay)} so the rules exist once. The
 * override guard that protects HR's manual corrections lives in the repository's SQL, not here —
 * see {@link AttendanceDailyRepository#upsertAll}.
 */
@Service
public class AttendanceDailyService {

    /**
     * Widest span the day view will read at once. This query is employees × days, so an unbounded
     * range is a real memory and latency hazard on a company-wide read.
     */
    public static final int MAX_RANGE_DAYS = 92;

    private final AttendanceDailyRepository repository;
    private final AttendanceDailyCalculator calculator;
    private final WorkScheduleResolver scheduleResolver;

    public AttendanceDailyService(
            AttendanceDailyRepository repository,
            AttendanceDailyCalculator calculator,
            WorkScheduleResolver scheduleResolver) {
        this.repository = repository;
        this.calculator = calculator;
        this.scheduleResolver = scheduleResolver;
    }

    /** Recalculates one employee-day. Silently no-ops when the day has no punches left. */
    @Transactional
    public void recalculate(EmployeeDay pair) {
        recalculateAll(List.of(pair));
    }

    /**
     * Recalculates a batch of employee-days in one round trip.
     *
     * <p>Callers with many punches (the .dat import especially) must collapse to distinct pairs
     * before calling: a 10,000-row import is a few hundred employee-days, and recalculating
     * per-punch instead would do the same work dozens of times over.
     */
    @Transactional
    public int recalculateAll(List<EmployeeDay> pairs) {
        List<AttendanceDailyRecord> records = new ArrayList<>();
        for (EmployeeDay pair : pairs) {
            List<PunchRecord> punches = repository.findPunchesFor(pair.employeeId(), pair.workDate());
            if (punches.isEmpty()) {
                // Nothing to store: absence is the absence of a row, derived at read time.
                continue;
            }
            records.add(calculator.calculate(
                pair.employeeId(),
                pair.workDate(),
                punches,
                scheduleFor(pair),
                repository.findApprovedOvertimeMinutes(pair.employeeId(), pair.workDate())
            ));
        }
        return repository.upsertAll(records);
    }

    /**
     * Recalculates every employee-day with punches in the range. Idempotent; safe to re-run.
     *
     * <p>Loads punches, divisions and approved overtime in <strong>three</strong> queries rather
     * than three per employee-day. A full historical backfill covers thousands of days; the
     * per-day form meant ~14,000 round trips inside one transaction, which on a hosted database
     * exceeds the request timeout and rolls the entire job back — writing nothing, and reporting
     * nothing useful about why.
     */
    @Transactional
    public int recalculateRange(LocalDate fromDate, LocalDate toDate, Long employeeId) {
        Map<EmployeeDay, List<PunchRecord>> punchesByDay =
            repository.findPunchesInRange(fromDate, toDate, employeeId);
        if (punchesByDay.isEmpty()) {
            return 0;
        }
        Map<Long, Long> divisionByEmployee = repository.findDivisionIdsByEmployee();
        Map<EmployeeDay, Integer> overtimeByDay =
            repository.findApprovedOvertimeMinutesInRange(fromDate, toDate);

        List<AttendanceDailyRecord> records = new ArrayList<>(punchesByDay.size());
        punchesByDay.forEach((day, punches) -> records.add(calculator.calculate(
            day.employeeId(),
            day.workDate(),
            punches,
            scheduleResolver.resolve(
                day.employeeId(), divisionByEmployee.get(day.employeeId()), day.workDate()),
            overtimeByDay.getOrDefault(day, 0)
        )));
        return repository.upsertAll(records);
    }

    /** Recalculates the days touched by a specific set of punches. */
    @Transactional
    public int recalculateForPunches(List<Long> punchIds) {
        return recalculateAll(repository.findPairsForPunchIds(punchIds));
    }

    /**
     * Re-derives every employee-day that has punches, over all of history.
     *
     * <p>Used by the one-shot backfill and after a badge backfill, where the newly-resolved punches
     * may be years old and there is no cheaper way to find them — the badge only becomes a person
     * at the moment of the fix, so the affected days cannot be known in advance.
     */
    @Transactional
    public int recalculateAllHistory() {
        LocalDate earliest = repository.findEarliestPunchDate();
        if (earliest == null) {
            return 0;
        }
        return recalculateRange(earliest, repository.findLatestPunchDate(), null);
    }

    /**
     * The day view: one row per employee per day across the range, including days with no data so
     * the UI can render "-" rather than dropping them.
     */
    public List<AttendanceDailyDto> list(AttendanceDailyFilter filter) {
        return repository.findRange(filter).stream().map(this::toDto).toList();
    }

    public List<UnmappedBadge> listUnmappedBadges(LocalDate fromDate, LocalDate toDate) {
        return repository.findUnmappedBadges(fromDate, toDate);
    }

    public List<AttendanceEmployeeOption> listEmployeeOptions(
            Long actorEmployeeId, Long managerDivisionId, boolean includeAll) {
        return repository.findEmployeeOptions(actorEmployeeId, managerDivisionId, includeAll);
    }

    private WorkSchedule scheduleFor(EmployeeDay pair) {
        return scheduleResolver.resolve(
            pair.employeeId(), repository.findDivisionId(pair.employeeId()), pair.workDate());
    }

    /**
     * Re-derives status and flags on read rather than storing them.
     *
     * <p>Keeps the labels in one place and keeps the schema unchanged — every persisted column
     * already existed in V7. It also means a corrected schedule reclassifies history on the next
     * read without a migration.
     */
    private AttendanceDailyDto toDto(AttendanceDailyRow row) {
        WorkSchedule schedule = scheduleResolver.resolve(row.employeeId(), null, row.workDate());
        boolean workday = schedule.isWorkday(row.workDate());
        Set<AttendanceDayFlag> flags = EnumSet.noneOf(AttendanceDayFlag.class);
        AttendanceDayStatus status;

        if (!row.hasRecord()) {
            status = workday ? AttendanceDayStatus.NO_RECORD : AttendanceDayStatus.NON_WORKDAY;
            if (!workday) {
                flags.add(AttendanceDayFlag.NON_WORKDAY);
            }
        } else {
            if (!workday) {
                flags.add(AttendanceDayFlag.NON_WORKDAY);
                status = AttendanceDayStatus.NON_WORKDAY;
            } else if (row.checkIn() == null) {
                flags.add(AttendanceDayFlag.MISSING_CHECK_IN);
                status = AttendanceDayStatus.MISSING_CHECK_IN;
            } else if (row.checkOut() == null) {
                flags.add(AttendanceDayFlag.MISSING_CHECK_OUT);
                status = AttendanceDayStatus.MISSING_CHECK_OUT;
            } else {
                status = row.lateMinutes() > 0
                    ? AttendanceDayStatus.LATE
                    : AttendanceDayStatus.PRESENT;
            }
            if (workday && row.lateMinutes() > 0) {
                flags.add(AttendanceDayFlag.LATE);
            }
            if (workday && row.earlyLeaveMinutes() > 0) {
                flags.add(AttendanceDayFlag.EARLY_LEAVE);
            }
            if (row.overtimeMinutes() > 0) {
                flags.add(AttendanceDayFlag.OVERTIME_APPROVED);
            } else if (row.checkOut() != null && workedPastEnd(row, schedule)) {
                flags.add(AttendanceDayFlag.WORKED_LATE_UNAPPROVED);
            }
        }

        return new AttendanceDailyDto(
            row.employeeId(),
            row.employeeCode(),
            row.employeeName(),
            row.nickName(),
            row.positionTh(),
            row.workDate(),
            workday,
            row.checkIn(),
            row.checkOut(),
            row.totalMinutes(),
            row.lateMinutes(),
            row.earlyLeaveMinutes(),
            row.overtimeMinutes(),
            row.punchCount(),
            row.siteCode(),
            status,
            List.copyOf(flags),
            row.manualOverride(),
            row.notes()
        );
    }

    private static boolean workedPastEnd(AttendanceDailyRow row, WorkSchedule schedule) {
        LocalTime checkOut = row.checkOut().atZoneSameInstant(schedule.zone()).toLocalTime();
        return checkOut.isAfter(schedule.workEnd().plusMinutes(schedule.graceMinutes()));
    }
}
