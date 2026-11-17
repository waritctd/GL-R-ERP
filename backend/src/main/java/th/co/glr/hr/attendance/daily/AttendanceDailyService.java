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

/** Derives and serves hr.attendance_daily. Every write path funnels into #recalculate(EmployeeDay) so the rules
 * exist once. The override guard that protects HR's manual corrections lives in the repository's SQL, not here
 * — see AttendanceDailyRepository#upsertAll. */
@Service
public class AttendanceDailyService {

    /** Widest span the day view will read at once. This query is employees × days, so an unbounded range is a real
     * memory and latency hazard on a company-wide read. */
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

    /** Recalculates a batch of employee-days in one round trip. Callers with many punches (the .dat import
     * especially) must collapse to distinct pairs before calling: a 10,000-row import is a few hundred
     * employee-days, and recalculating per-punch instead would do the same work dozens of times over. */
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

    /** Recalculates every employee-day with punches in the range. Idempotent; safe to re-run. Loads punches,
     * divisions/departments, approved overtime and holidays in four queries rather than four per employee-day. */
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

    /** Applies an HR/CEO attendance correction (see AttendanceCorrectionService#approve) as an authoritative
     * is_manual_override = TRUE row, after the caller has already inserted the corrected punch(es) for pair. */
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

    /** Re-derives every employee-day that has punches, over all of history. */
    @Transactional
    public int recalculateAllHistory() {
        LocalDate earliest = repository.findEarliestPunchDate();
        if (earliest == null) {
            return 0;
        }
        return recalculateRange(earliest, repository.findLatestPunchDate(), null);
    }

    /** The day view: one row per employee per day across the range, including days with no data so the UI can
     * render "-" rather than dropping them. */
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
     * AttendanceDailyRepository#findDivisionName. */
    public String findDivisionName(long divisionId) {
        return repository.findDivisionName(divisionId);
    }

    public List<AttendanceEmployeeOption> listEmployeeOptions(
            Long actorEmployeeId, Long managerDivisionId, boolean includeAll) {
        return repository.findEmployeeOptions(actorEmployeeId, managerDivisionId, includeAll);
    }

    /** Reconciles the CEO/HR stand-up (WFH) roster for one date: marks everyone in presentEmployeeIds present with
     * no punches, then removes any earlier WFH mark for that date that was not resubmitted. */
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

    /** Re-derives status and flags on read rather than storing them. Keeps the labels in one place and keeps the
     * schema unchanged — every persisted column already existed in V7. It also means a corrected schedule (or a
     * holiday added after the fact) reclassifies history on the next read without a migration. */
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
        // A holiday is not a workday regardless of the schedule — same rule the calculator applies on write; see
        // AttendanceDailyCalculator#calculate and AttendanceDayFlag#HOLIDAY.
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
                // A CEO/HR stand-up or WFH mark: present with no punches, on purpose. Checked before the
                // holiday/workday/checkIn logic below so it reads as WFH even on a non-workday or a holiday, instead of
                // HOLIDAY/NON_WORKDAY/MISSING_CHECK_IN — those describe the absence of data, not this.
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
                // V117: ฝ่ายขาย's SALES_5D sets requiresCheckOut = false, so a lone check-in is a complete, compliant day for
                // them (AttendanceDailyCalculator#statusOf already knows this on write) — mirror that here so a re-read
                // doesn't re-flag a day the write path considered fine.
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
