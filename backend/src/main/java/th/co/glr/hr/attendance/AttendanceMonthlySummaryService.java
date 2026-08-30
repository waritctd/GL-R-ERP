package th.co.glr.hr.attendance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import th.co.glr.hr.attendance.daily.AttendanceDailyDto;
import th.co.glr.hr.attendance.daily.AttendanceDailyFilter;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.attendance.daily.AttendanceDayFlag;
import th.co.glr.hr.attendance.daily.AttendanceDayStatus;
import th.co.glr.hr.attendance.daily.EmployeeDay;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.leave.ApprovedLeaveSpanDto;
import th.co.glr.hr.leave.LeaveRepository;

/**
 * Assembles the data behind HR's monthly attendance summary workbook -- see
 * {@link AttendanceMonthlySummaryExporter} for the xlsx this feeds, and
 * {@code AttendanceController#monthlySummary} for the endpoint. The rules below are the spec: they
 * are stated here rather than in a branch document, per CLAUDE.md's "the PR body is now the
 * handoff" — a reader a year from now has this file, not the branch that introduced it.
 *
 * <h2>This is a pure aggregation -- never a second query against {@code attendance_daily}</h2>
 * {@link #buildSummary} calls the SAME {@link AttendanceDailyService#list} the {@code /daily}
 * endpoint calls, through the SAME {@link AttendanceService#resolveScope}, for the SAME
 * [{@code from}, {@code to}] the caller's month resolves to. Every count on the summary sheet folds
 * over exactly the rows an HR user looking at the day view for that month, with the same filters,
 * would see -- so this report can never disagree with that screen. If a figure needs data the daily
 * rows/DTO do not carry, that is a sign the daily view is missing it too, not a reason to add a
 * second read here.
 *
 * <h2>Authorization</h2>
 * This class adds ZERO new authorization semantics. {@link #buildSummary} resolves the caller's
 * {@link AttendanceScope} exactly as {@link AttendanceService#listDaily} does, and the workbook can
 * never contain a row that scope would not also hand back through {@code /daily}. See
 * {@code AttendanceController#monthlySummary}'s javadoc for why the endpoint itself carries no
 * {@code @PreAuthorize}/{@code requireAnyRole}.
 *
 * <h2>Leave-day attribution (decision #1: separate ลา from ขาดงาน)</h2>
 * {@code AttendanceDailyDto} has no LEAVE status -- a day covered by approved ลา arrives as
 * {@code NO_RECORD}, identical to an unexcused absence, because the daily roll-up only ever knows
 * about punches. This class overlays {@link LeaveRepository#findApprovedLeaveOverlapping} on top so
 * ขาดงาน can mean what it says.
 *
 * <p>Kept deliberately simple, and deliberately NOT {@code th.co.glr.hr.leave.LeaveDayMath}, which
 * answers a different question (quota consumption) with a different rule set (paid/unpaid ranking,
 * cross-year splits). This is a REPORTING roll-up, not a re-derivation of quota math.
 *
 * <ul>
 *   <li>A date is "on leave" when an APPROVED request covers it.
 *   <li>Whole-day request: contributes {@code 1.00} to a date, but ONLY when that date is a workday
 *       for that employee -- reusing {@link AttendanceDailyDto#workday()} (which already folds in
 *       schedule + holiday calendar) rather than recomputing the same answer a second way. A leave
 *       date that lands on a weekend/holiday was never a day the employee would otherwise have
 *       worked, so it contributes nothing to either ลา or ขาดงาน.
 *   <li>Sub-day request ({@code startTime}/{@code endTime} non-null, always single-day per
 *       {@code chk_leave_time_single_day}): contributes that request's own {@code total_days}
 *       fraction to that one date, regardless of the workday check above -- an employee who filed a
 *       half-day off has, by definition, already accounted for the working half themselves.
 *   <li>ขาดงาน = workday AND status {@code NO_RECORD} AND this date has NO leave contribution at
 *       all. The ลา columns and the ขาดงาน count both read the SAME per-(employee, date) ledger
 *       (see {@link #attributeLeave}), so the two can never disagree about which dates are covered.
 * </ul>
 *
 * <h2>OT hours: the flag, never the raw minutes, even though today they agree</h2>
 * {@code overtimeMinutes} on a day flagged {@link AttendanceDayFlag#WORKED_LATE_UNAPPROVED} is
 * always {@code 0} today ({@code AttendanceDailyService#toDto} assigns the two flags in a mutually
 * exclusive if/else), so summing raw {@code overtimeMinutes} across every day and summing it across
 * only {@link AttendanceDayFlag#OVERTIME_APPROVED}-flagged days currently produce the identical
 * number. {@link #buildEmployeeRow} still branches on the flag: relying on that incidental
 * arithmetic equivalence changing underneath this report, silently, one day, is exactly the failure
 * shape CLAUDE.md's mock-drift catalogue warns about -- reporting unapproved overtime as OT would
 * promise money payroll never produces.
 */
@Service
public class AttendanceMonthlySummaryService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);
    private static final int HOURS_SCALE = 2;

    /**
     * Every status {@link AttendanceDayStatus} assigns on a workday when SOME data exists for the
     * day -- i.e. every status except {@code NO_RECORD} (no data at all) and the two that
     * structurally never co-occur with a workday ({@code NON_WORKDAY}/{@code HOLIDAY} are only ever
     * assigned when {@code !workday}, per {@code AttendanceDailyService#toDto}). Used for มาทำงาน,
     * per the plan's literal definition rather than the (equivalent, but less self-documenting)
     * "status != NO_RECORD".
     */
    private static final Set<AttendanceDayStatus> PRESENT_STATUSES = EnumSet.of(
        AttendanceDayStatus.PRESENT, AttendanceDayStatus.LATE, AttendanceDayStatus.WFH,
        AttendanceDayStatus.MISSING_CHECK_IN, AttendanceDayStatus.MISSING_CHECK_OUT);

    /** Which of the five named leave columns a leave type's days land in -- see {@link #columnFor}. */
    private enum LeaveColumn { SICK, PERSONAL, VACATION, UNPAID, OTHER }

    private final AttendanceService attendanceService;
    private final AttendanceDailyService dailyService;
    private final LeaveRepository leaveRepository;
    private final AttendanceMonthlySummaryExporter exporter;

    public AttendanceMonthlySummaryService(
            AttendanceService attendanceService,
            AttendanceDailyService dailyService,
            LeaveRepository leaveRepository,
            AttendanceMonthlySummaryExporter exporter) {
        this.attendanceService = attendanceService;
        this.dailyService = dailyService;
        this.leaveRepository = leaveRepository;
        this.exporter = exporter;
    }

    /** The finished workbook, scoped to what {@code user} may see -- see this class's javadoc. */
    public byte[] export(UserPrincipal user, YearMonth month, Long requestedEmployeeId, Long requestedDivisionId) {
        return exporter.export(buildSummary(user, month, requestedEmployeeId, requestedDivisionId));
    }

    /**
     * Package-private for direct unit testing, mirroring why {@code AttendanceService#resolveScope}
     * carries the same visibility: {@code AttendanceMonthlySummaryServiceTest} asserts on this
     * method's output directly, with no workbook/POI involved.
     */
    AttendanceMonthlySummaryResult buildSummary(
            UserPrincipal user, YearMonth month, Long requestedEmployeeId, Long requestedDivisionId) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        AttendanceScope scope = attendanceService.resolveScope(user, requestedEmployeeId, requestedDivisionId);
        List<AttendanceDailyDto> days = dailyService.list(
            new AttendanceDailyFilter(scope.employeeId(), scope.divisionId(), from, to));

        // Group by employee, preserving the order list() already returns -- see this class's
        // javadoc ("never a second query"). AttendanceDailyRepository#findRange orders by
        // work_date DESC then employee_code, so the first date block encountered (the month's LAST
        // day) already lists employees by employee_code ascending -- a LinkedHashMap keyed on
        // first-occurrence order reproduces that without a second ORDER BY, and each employee's own
        // sub-list comes out already sorted newest-to-oldest for free, which is exactly the shape
        // Sheet 2 wants (see dailyRowsGroupedByEmployee below).
        Map<Long, List<AttendanceDailyDto>> byEmployee = new LinkedHashMap<>();
        Map<EmployeeDay, AttendanceDailyDto> dayByKey = new HashMap<>();
        for (AttendanceDailyDto day : days) {
            byEmployee.computeIfAbsent(day.employeeId(), unused -> new ArrayList<>()).add(day);
            dayByKey.put(new EmployeeDay(day.employeeId(), day.workDate()), day);
        }

        List<ApprovedLeaveSpanDto> leaveSpans = byEmployee.isEmpty()
            ? List.of()
            : leaveRepository.findApprovedLeaveOverlapping(byEmployee.keySet(), from, to);
        Map<EmployeeDay, List<LeaveContribution>> leaveByDay = attributeLeave(leaveSpans, dayByKey, from, to);

        List<AttendanceMonthlySummaryRow> summaryRows = byEmployee.values().stream()
            .map(employeeDays -> buildEmployeeRow(employeeDays, leaveByDay))
            .toList();
        // Sheet 2 groups by employee (each employee's block together, newest day first within it) --
        // see the comment on byEmployee above for why no separate sort is needed to get this.
        List<AttendanceDailyDto> dailyRowsGroupedByEmployee = byEmployee.values().stream()
            .flatMap(List::stream)
            .toList();

        return new AttendanceMonthlySummaryResult(
            month,
            describeAppliedFilter(scope, days),
            OffsetDateTime.now(AttendanceService.DEFAULT_WORK_DATE_ZONE),
            summaryRows,
            dailyRowsGroupedByEmployee,
            leaveByDay
        );
    }

    /**
     * Folds one employee's day rows into their {@link AttendanceMonthlySummaryRow} in one pass,
     * consulting {@code leaveByDay} once per date via the SAME key ขาดงาน and the ลา columns both
     * use -- see this class's javadoc for why that guarantees the two can never disagree.
     */
    private AttendanceMonthlySummaryRow buildEmployeeRow(
            List<AttendanceDailyDto> employeeDays, Map<EmployeeDay, List<LeaveContribution>> leaveByDay) {
        AttendanceDailyDto identity = employeeDays.get(0);
        int calendarWorkdays = 0;
        int daysPresent = 0;
        int lateCount = 0;
        int lateMinutes = 0;
        int earlyLeaveCount = 0;
        int earlyLeaveMinutes = 0;
        int missingCheckInCount = 0;
        int missingCheckOutCount = 0;
        int absentDays = 0;
        long totalMinutesSum = 0;
        int approvedOtMinutesSum = 0;
        BigDecimal sick = ZERO;
        BigDecimal personal = ZERO;
        BigDecimal vacation = ZERO;
        BigDecimal unpaid = ZERO;
        BigDecimal other = ZERO;

        for (AttendanceDailyDto day : employeeDays) {
            List<LeaveContribution> contributions =
                leaveByDay.getOrDefault(new EmployeeDay(day.employeeId(), day.workDate()), List.of());

            if (day.workday()) {
                calendarWorkdays++;
                if (PRESENT_STATUSES.contains(day.status())) {
                    daysPresent++;
                }
                if (day.status() == AttendanceDayStatus.NO_RECORD && contributions.isEmpty()) {
                    absentDays++;
                }
            }
            // late_minutes/early_leave_minutes are already 0 on a non-workday day
            // (AttendanceDailyCalculator#calculate only computes either inside its `if (workday)`
            // branch), so no extra gate is needed here to keep these two counts workday-scoped.
            if (day.lateMinutes() > 0) {
                lateCount++;
                lateMinutes += day.lateMinutes();
            }
            if (day.earlyLeaveMinutes() > 0) {
                earlyLeaveCount++;
                earlyLeaveMinutes += day.earlyLeaveMinutes();
            }
            if (day.status() == AttendanceDayStatus.MISSING_CHECK_IN) {
                missingCheckInCount++;
            }
            if (day.status() == AttendanceDayStatus.MISSING_CHECK_OUT) {
                missingCheckOutCount++;
            }
            if (day.totalMinutes() != null) {
                totalMinutesSum += day.totalMinutes();
            }
            // The flag, never the raw minutes -- see this class's javadoc ("OT hours") for why this
            // still matters even though the two read the same today.
            if (day.flags().contains(AttendanceDayFlag.OVERTIME_APPROVED)) {
                approvedOtMinutesSum += day.overtimeMinutes();
            }

            for (LeaveContribution contribution : contributions) {
                switch (columnFor(contribution.leaveTypeCode())) {
                    case SICK -> sick = sick.add(contribution.fraction());
                    case PERSONAL -> personal = personal.add(contribution.fraction());
                    case VACATION -> vacation = vacation.add(contribution.fraction());
                    case UNPAID -> unpaid = unpaid.add(contribution.fraction());
                    case OTHER -> other = other.add(contribution.fraction());
                }
            }
        }

        BigDecimal totalLeaveDays = sick.add(personal).add(vacation).add(unpaid).add(other);
        return new AttendanceMonthlySummaryRow(
            identity.employeeId(),
            identity.employeeCode(),
            identity.employeeName(),
            identity.nickName(),
            identity.positionTh(),
            calendarWorkdays,
            daysPresent,
            lateCount,
            lateMinutes,
            earlyLeaveCount,
            earlyLeaveMinutes,
            missingCheckInCount,
            missingCheckOutCount,
            sick,
            personal,
            vacation,
            unpaid,
            other,
            totalLeaveDays,
            absentDays,
            toHours(totalMinutesSum),
            toHours(approvedOtMinutesSum)
        );
    }

    private static BigDecimal toHours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(MINUTES_PER_HOUR, HOURS_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * These five leave-type codes are the entire content of {@code hr.leave_type} today (seeded by
     * V13/V85/V116) -- SICK/PERSONAL/VACATION/LEAVE_WITHOUT_PAY
     * get their own named column per the requested layout, and the remaining three
     * (MATERNITY/MILITARY/ORDINATION) fold into ลาอื่นๆ. This decides which NUMERIC column a code's
     * days land in; it is not the Thai LABEL shown anywhere -- Sheet 2's ลา cell always renders
     * {@code leaveTypeNameTh} from the ledger, never a hardcoded string (see
     * {@link ApprovedLeaveSpanDto}'s own javadoc). An unrecognised future code falls back to ลาอื่นๆ
     * rather than throwing, so a new {@code hr.leave_type} row does not break this report -- it just
     * reports generically until this switch is deliberately extended for it.
     */
    private static LeaveColumn columnFor(String leaveTypeCode) {
        return switch (leaveTypeCode) {
            case "SICK" -> LeaveColumn.SICK;
            case "PERSONAL" -> LeaveColumn.PERSONAL;
            case "VACATION" -> LeaveColumn.VACATION;
            case "LEAVE_WITHOUT_PAY" -> LeaveColumn.UNPAID;
            default -> LeaveColumn.OTHER;
        };
    }

    /**
     * Builds the per-(employee, date) leave ledger every ลา figure and the ขาดงาน exclusion both
     * read -- see this class's javadoc ("Leave-day attribution") for the whole-day/sub-day rule.
     */
    private Map<EmployeeDay, List<LeaveContribution>> attributeLeave(
            List<ApprovedLeaveSpanDto> spans, Map<EmployeeDay, AttendanceDailyDto> dayByKey,
            LocalDate from, LocalDate to) {
        Map<EmployeeDay, List<LeaveContribution>> byDay = new HashMap<>();
        for (ApprovedLeaveSpanDto span : spans) {
            if (span.startTime() != null) {
                // Sub-day: chk_leave_time_single_day guarantees startDate == endDate, and the
                // overlap predicate (start_date <= to AND end_date >= from) guarantees that single
                // date already falls inside [from, to]. Contributes regardless of workday -- see
                // this class's javadoc for why.
                addContribution(
                    byDay, new EmployeeDay(span.employeeId(), span.startDate()), span, nz(span.totalDays()));
                continue;
            }
            // Whole-day: walk only the slice of [startDate, endDate] that falls inside the reported
            // month -- the request itself may extend beyond it (e.g. started last month) -- and
            // count a date only when it is actually a workday for this employee.
            LocalDate rangeStart = span.startDate().isAfter(from) ? span.startDate() : from;
            LocalDate rangeEnd = span.endDate().isBefore(to) ? span.endDate() : to;
            for (LocalDate date = rangeStart; !date.isAfter(rangeEnd); date = date.plusDays(1)) {
                EmployeeDay key = new EmployeeDay(span.employeeId(), date);
                AttendanceDailyDto day = dayByKey.get(key);
                if (day != null && day.workday()) {
                    addContribution(byDay, key, span, BigDecimal.ONE);
                }
            }
        }
        return byDay;
    }

    private static void addContribution(
            Map<EmployeeDay, List<LeaveContribution>> byDay, EmployeeDay key,
            ApprovedLeaveSpanDto span, BigDecimal fraction) {
        byDay.computeIfAbsent(key, unused -> new ArrayList<>())
            .add(new LeaveContribution(span.leaveTypeCode(), span.leaveTypeNameTh(), fraction));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    /**
     * The ฝ่าย/พนักงาน filter actually applied, for the title block -- derived from the RESOLVED
     * {@link AttendanceScope}, never the raw request params, so a manager's ignored
     * {@code divisionId} or a self-view's forced {@code employeeId} reads the same on the workbook
     * as it behaves (see {@code AttendanceService#resolveScope}'s own javadoc for both cases).
     */
    private String describeAppliedFilter(AttendanceScope scope, List<AttendanceDailyDto> days) {
        List<String> parts = new ArrayList<>();
        if (scope.divisionId() != null) {
            String name = dailyService.findDivisionName(scope.divisionId());
            parts.add("ฝ่าย: " + (name != null ? name : ("#" + scope.divisionId())));
        }
        if (scope.employeeId() != null) {
            // The day rows are the cheapest place to find this employee's display name: this scope
            // already narrows to exactly them (or to nobody, e.g. a ฝ่าย manager asking for an
            // out-of-division id), so a name is either sitting right here already or truly
            // unavailable without a query this report would otherwise have no reason to run.
            long targetEmployeeId = scope.employeeId();
            String label = days.stream()
                .filter(day -> day.employeeId() == targetEmployeeId)
                .findFirst()
                .map(day -> day.employeeName() + " (" + day.employeeCode() + ")")
                .orElse("รหัสพนักงาน #" + scope.employeeId());
            parts.add("พนักงาน: " + label);
        }
        return parts.isEmpty() ? "ทุกฝ่าย (พนักงานทั้งหมด)" : String.join(" · ", parts);
    }
}
