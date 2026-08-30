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
import th.co.glr.hr.attendance.schedule.HolidayCalendar;
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
    private final HolidayCalendar holidayCalendar;

    public AttendanceDailyService(
            AttendanceDailyRepository repository,
            AttendanceDailyCalculator calculator,
            WorkScheduleResolver scheduleResolver,
            HolidayCalendar holidayCalendar) {
        this.repository = repository;
        this.calculator = calculator;
        this.scheduleResolver = scheduleResolver;
        this.holidayCalendar = holidayCalendar;
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
                repository.findApprovedOvertimeMinutes(pair.employeeId(), pair.workDate()),
                holidayCalendar.isHoliday(pair.workDate())
            ));
        }
        return repository.upsertAll(records);
    }

    /**
     * Recalculates every employee-day with punches in the range. Idempotent; safe to re-run.
     *
     * <p>Loads punches, divisions/departments, approved overtime and holidays in <strong>four</strong>
     * queries rather than four per employee-day. A full historical backfill covers thousands of
     * days; the per-day form meant ~14,000 round trips inside one transaction, which on a hosted
     * database exceeds the request timeout and rolls the entire job back — writing nothing, and
     * reporting nothing useful about why. ({@code scheduleResolver.resolve} itself adds no further
     * per-call query — see {@code TieredWorkScheduleResolver}'s own caching.)
     */
    @Transactional
    public int recalculateRange(LocalDate fromDate, LocalDate toDate, Long employeeId) {
        Map<EmployeeDay, List<PunchRecord>> punchesByDay =
            repository.findPunchesInRange(fromDate, toDate, employeeId);
        if (punchesByDay.isEmpty()) {
            return 0;
        }
        Map<Long, Long> divisionByEmployee = repository.findDivisionIdsByEmployee();
        Map<Long, Long> departmentByEmployee = repository.findDepartmentIdsByEmployee();
        Map<EmployeeDay, Integer> overtimeByDay =
            repository.findApprovedOvertimeMinutesInRange(fromDate, toDate);
        Set<LocalDate> holidays = holidayCalendar.holidaysBetween(fromDate, toDate);

        List<AttendanceDailyRecord> records = new ArrayList<>(punchesByDay.size());
        punchesByDay.forEach((day, punches) -> records.add(calculator.calculate(
            day.employeeId(),
            day.workDate(),
            punches,
            scheduleResolver.resolve(
                day.employeeId(),
                divisionByEmployee.get(day.employeeId()),
                departmentByEmployee.get(day.employeeId()),
                day.workDate()),
            overtimeByDay.getOrDefault(day, 0),
            holidays.contains(day.workDate())
        )));
        return repository.upsertAll(records);
    }

    /** Recalculates the days touched by a specific set of punches. */
    @Transactional
    public int recalculateForPunches(List<Long> punchIds) {
        return recalculateAll(repository.findPairsForPunchIds(punchIds));
    }

    /**
     * Applies an HR/CEO attendance correction (see {@code AttendanceCorrectionService#approve}) as
     * an <strong>authoritative</strong> {@code is_manual_override = TRUE} row, after the caller has
     * already inserted the corrected punch(es) for {@code pair}.
     *
     * <p>Deliberately reuses the exact same derivation this class uses everywhere else —
     * {@link AttendanceDailyCalculator#calculate}, fed the day's punches (now including the newly
     * inserted correction), the employee's resolved schedule, approved overtime minutes, and holiday
     * status — so {@code total_minutes}/{@code late_minutes}/{@code early_leave_minutes} come out of
     * the SAME §76 reporting-only computation an ordinary punch would produce, never a
     * correction-specific reimplementation. See {@code AttendanceDailyRepository#upsertOverride}'s
     * javadoc for why persistence (not the calculation) is what differs from {@link #recalculate}.
     *
     * <p>Silently no-ops (writes nothing) when the day ends up with no punches at all — cannot
     * happen for an approved correction in practice (it always inserts at least one punch first),
     * but keeps this method's contract identical to {@link #recalculate}'s for a day with none.
     */
    @Transactional
    public void applyManualCorrection(EmployeeDay pair) {
        List<PunchRecord> punches = repository.findPunchesFor(pair.employeeId(), pair.workDate());
        if (punches.isEmpty()) {
            return;
        }
        AttendanceDailyRecord record = calculator.calculate(
            pair.employeeId(),
            pair.workDate(),
            punches,
            scheduleFor(pair),
            repository.findApprovedOvertimeMinutes(pair.employeeId(), pair.workDate()),
            holidayCalendar.isHoliday(pair.workDate())
        );
        repository.upsertOverride(record);
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
     *
     * <p>Batch-loads the employee-&gt;division/department mapping and the range's holidays once,
     * the same way {@link #recalculateRange} batches its own per-range lookups (see that method's
     * javadoc) — fixes a latent bug where {@link #toDto} used to resolve every row's schedule with
     * {@code divisionId = null}, harmless only while {@code WorkScheduleResolver} ignored every
     * argument; once schedules can differ by division/department, that null silently picked a
     * different schedule on read than the write path used to compute the stored figures.
     */
    public List<AttendanceDailyDto> list(AttendanceDailyFilter filter) {
        List<AttendanceDailyRow> rows = repository.findRange(filter);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> divisionByEmployee = repository.findDivisionIdsByEmployee();
        Map<Long, Long> departmentByEmployee = repository.findDepartmentIdsByEmployee();
        Set<LocalDate> holidays = holidayCalendar.holidaysBetween(filter.fromDate(), filter.toDate());
        return rows.stream()
            .map(row -> toDto(row, divisionByEmployee, departmentByEmployee, holidays))
            .toList();
    }

    public List<UnmappedBadge> listUnmappedBadges(LocalDate fromDate, LocalDate toDate) {
        return repository.findUnmappedBadges(fromDate, toDate);
    }

    /** Pass-through for a report title that names a division-scoped filter -- see
     * {@link AttendanceDailyRepository#findDivisionName}. */
    public String findDivisionName(long divisionId) {
        return repository.findDivisionName(divisionId);
    }

    public List<AttendanceEmployeeOption> listEmployeeOptions(
            Long actorEmployeeId, Long managerDivisionId, boolean includeAll) {
        return repository.findEmployeeOptions(actorEmployeeId, managerDivisionId, includeAll);
    }

    /**
     * Reconciles the CEO/HR stand-up (WFH) roster for one date: marks everyone in
     * {@code presentEmployeeIds} present with no punches, then removes any earlier WFH mark for that
     * date that was not resubmitted. Idempotent — resubmitting the same roster is a no-op past the
     * first call, and leaving someone off a later submission for the same date un-marks them.
     *
     * <p>The mark is authoritative and supersedes an existing scanner-derived row for that day (see
     * {@link AttendanceDailyRepository#upsertWfhPresent}) — the raw punches survive, so un-marking
     * plus a recalc restores them. The clear half is scoped to rows this feature created
     * ({@code is_manual_override = TRUE AND site_code = 'WFH' AND check_in IS NULL}) in
     * {@link AttendanceDailyRepository#clearWfhNotInRoster}, so it never deletes a scanner row.
     */
    @Transactional
    public AttendanceWfhRosterResult setWfhRoster(
            LocalDate workDate, Set<Long> presentEmployeeIds, String notes) {
        Set<Long> ids = presentEmployeeIds == null ? Set.of() : presentEmployeeIds;
        int marked = repository.upsertWfhPresent(workDate, ids, notes);
        int cleared = repository.clearWfhNotInRoster(workDate, ids);
        return new AttendanceWfhRosterResult(marked, cleared);
    }

    private WorkSchedule scheduleFor(EmployeeDay pair) {
        return scheduleResolver.resolve(
            pair.employeeId(),
            repository.findDivisionId(pair.employeeId()),
            repository.findDepartmentId(pair.employeeId()),
            pair.workDate());
    }

    /**
     * Re-derives status and flags on read rather than storing them.
     *
     * <p>Keeps the labels in one place and keeps the schema unchanged — every persisted column
     * already existed in V7. It also means a corrected schedule (or a holiday added after the
     * fact) reclassifies history on the next read without a migration.
     *
     * <p>Because this re-derives rather than reads back what {@link AttendanceDailyCalculator}
     * decided at write time, it must apply exactly the same rules that method does or the two paths
     * disagree about the same row — this happened for real once already (the division/department
     * null-resolution bug V115 fixed, see {@link #list}'s javadoc) and again for V117's
     * {@code requiresCheckOut} exemption (fixed alongside this comment): the MISSING_CHECK_OUT
     * branch below must consult {@code schedule.requiresCheckOut()} the same way
     * {@code AttendanceDailyCalculator#statusOf} does, or a sales employee's compliant lone check-in
     * reads as a compliance problem that the stored row never actually had.
     */
    private AttendanceDailyDto toDto(
            AttendanceDailyRow row,
            Map<Long, Long> divisionByEmployee,
            Map<Long, Long> departmentByEmployee,
            Set<LocalDate> holidays) {
        WorkSchedule schedule = scheduleResolver.resolve(
            row.employeeId(),
            divisionByEmployee.get(row.employeeId()),
            departmentByEmployee.get(row.employeeId()),
            row.workDate());
        boolean holiday = holidays.contains(row.workDate());
        // A holiday is not a workday regardless of the schedule — same rule the calculator applies
        // on write; see AttendanceDailyCalculator#calculate and AttendanceDayFlag#HOLIDAY.
        boolean workday = schedule.isWorkday(row.workDate()) && !holiday;
        Set<AttendanceDayFlag> flags = EnumSet.noneOf(AttendanceDayFlag.class);
        AttendanceDayStatus status;

        if (!row.hasRecord()) {
            if (holiday) {
                status = AttendanceDayStatus.HOLIDAY;
                flags.add(AttendanceDayFlag.HOLIDAY);
            } else if (!workday) {
                status = AttendanceDayStatus.NON_WORKDAY;
                flags.add(AttendanceDayFlag.NON_WORKDAY);
            } else {
                status = AttendanceDayStatus.NO_RECORD;
            }
        } else {
            if (row.manualOverride() && row.punchCount() == 0) {
                // A CEO/HR stand-up or WFH mark: present with no punches, on purpose. Checked before
                // the holiday/workday/checkIn logic below so it reads as WFH even on a non-workday
                // or a holiday, instead of HOLIDAY/NON_WORKDAY/MISSING_CHECK_IN — those describe the
                // absence of data, not this.
                status = AttendanceDayStatus.WFH;
                flags.add(AttendanceDayFlag.WFH);
            } else if (holiday) {
                flags.add(AttendanceDayFlag.HOLIDAY);
                status = AttendanceDayStatus.HOLIDAY;
            } else if (!workday) {
                flags.add(AttendanceDayFlag.NON_WORKDAY);
                status = AttendanceDayStatus.NON_WORKDAY;
            } else if (row.checkIn() == null) {
                flags.add(AttendanceDayFlag.MISSING_CHECK_IN);
                status = AttendanceDayStatus.MISSING_CHECK_IN;
            } else if (row.checkOut() == null && schedule.requiresCheckOut()) {
                // V117: ฝ่ายขาย's SALES_5D sets requiresCheckOut = false, so a lone check-in is a
                // complete, compliant day for them (AttendanceDailyCalculator#statusOf already knows
                // this on write) — mirror that here so a re-read doesn't re-flag a day the write path
                // considered fine. Before this check, every read of a sales employee's normal day
                // showed MISSING_CHECK_OUT regardless of what recalculateRange had stored.
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
