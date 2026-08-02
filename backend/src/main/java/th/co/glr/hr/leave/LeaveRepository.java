package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.attendance.schedule.HolidayCalendar;
import th.co.glr.hr.attendance.schedule.WorkSchedule;
import th.co.glr.hr.attendance.schedule.WorkScheduleResolver;

@Repository
public class LeaveRepository {
    // Legacy fallback (2026-08-03): the hardcoded Mon-Fri/no-holiday assumption LeaveDayMath used
    // to carry everywhere, now scoped to ONLY the 1-arg constructor below -- see that constructor's
    // javadoc. Built through the SAME WorkScheduleResolver/HolidayCalendar interfaces the real
    // schedule/holiday-aware path uses, so #workingDayPredicate needs no legacy-vs-real branch.
    private static final WorkSchedule LEGACY_MON_FRI_SCHEDULE = new WorkSchedule(
        ZoneId.of("Asia/Bangkok"), LocalTime.of(8, 30), LocalTime.of(17, 30), 5,
        EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));
    private static final WorkScheduleResolver LEGACY_SCHEDULE_RESOLVER =
        (employeeId, divisionId, departmentId, workDate) -> LEGACY_MON_FRI_SCHEDULE;
    private static final HolidayCalendar LEGACY_NO_HOLIDAYS = new HolidayCalendar() {
        @Override
        public boolean isHoliday(LocalDate date) {
            return false;
        }

        @Override
        public Set<LocalDate> holidaysBetween(LocalDate fromDate, LocalDate toDate) {
            return Set.of();
        }
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final WorkScheduleResolver scheduleResolver;
    private final HolidayCalendar holidayCalendar;

    /**
     * Test-convenience overload (2026-08-03): defaults to {@link #LEGACY_SCHEDULE_RESOLVER}/
     * {@link #LEGACY_NO_HOLIDAYS} -- Mon-Fri counts as working, no date is ever a holiday, exactly
     * {@code LeaveDayMath}'s pre-schedule-aware behaviour. This exists ONLY so the ~30 payroll/leave
     * integration tests that construct {@code new LeaveRepository(jdbc)} directly (they need SOME
     * working repository to satisfy PayrollService/LeaveService's constructor, but do not exercise
     * six-day-schedule or holiday day-counting) are not forced into an unrelated diff. Production
     * wiring always goes through the schedule-aware constructor below via Spring DI (note the single
     * {@code @Autowired} there) -- see CLAUDE.md's note on Spring only auto-selecting a constructor
     * when exactly one candidate is annotated.
     */
    public LeaveRepository(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, LEGACY_SCHEDULE_RESOLVER, LEGACY_NO_HOLIDAYS);
    }

    @Autowired
    public LeaveRepository(NamedParameterJdbcTemplate jdbc, WorkScheduleResolver scheduleResolver,
            HolidayCalendar holidayCalendar) {
        this.jdbc = jdbc;
        this.scheduleResolver = scheduleResolver;
        this.holidayCalendar = holidayCalendar;
    }

    /**
     * Schedule/holiday-aware "is this date a working day for this employee" predicate (2026-08-03)
     * -- see {@code LeaveDayMath}'s javadoc for the six-day-department and holiday defects this
     * closes. {@code LeaveService#submit}/{@code #cancel} call this ONCE per request (not per day)
     * to build the predicate every {@code LeaveDayMath} day-counting call for that request then
     * shares. Costs exactly two queries regardless of the range's length: the employee's
     * division/department (for schedule resolution) and the range's holidays, both batch reads --
     * see {@link LeaveDayMath#workingDayPredicate} for why per-date schedule resolution beyond that
     * is free (no further query).
     */
    public Predicate<LocalDate> workingDayPredicate(long employeeId, LocalDate fromDate, LocalDate toDate) {
        EmployeeScheduleContext context = findScheduleContext(employeeId);
        Set<LocalDate> holidays = holidayCalendar.holidaysBetween(fromDate, toDate);
        return LeaveDayMath.workingDayPredicate(
            scheduleResolver, employeeId, context.divisionId(), context.departmentId(), holidays);
    }

    private EmployeeScheduleContext findScheduleContext(long employeeId) {
        List<EmployeeScheduleContext> rows = jdbc.query("""
            SELECT division_id, department_id
              FROM hr.employee
             WHERE employee_id = :employeeId
            """, Map.of("employeeId", employeeId),
            (rs, rowNum) -> new EmployeeScheduleContext(
                nullableLong(rs, "division_id"), nullableLong(rs, "department_id")));
        return rows.isEmpty() ? new EmployeeScheduleContext(null, null) : rows.get(0);
    }

    private record EmployeeScheduleContext(Long divisionId, Long departmentId) {
    }

    public boolean employeeExists(long employeeId) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                  FROM hr.employee
                 WHERE employee_id = :employeeId
                   AND is_active = TRUE
            )
            """, Map.of("employeeId", employeeId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<LeaveEmployeeAccess> findEmployeeAccess(long employeeId) {
        return jdbc.query("""
            SELECT employee_id, reports_to_employee_id, is_active
              FROM hr.employee
             WHERE employee_id = :employeeId
            """, Map.of("employeeId", employeeId), (rs, rowNum) -> new LeaveEmployeeAccess(
                rs.getLong("employee_id"),
                nullableLong(rs, "reports_to_employee_id"),
                rs.getBoolean("is_active")
            ))
            .stream()
            .findFirst();
    }

    /**
     * Paper-form (ใบลาหยุด F-HR-020) autofill for the contact-during-leave block, plus read-only
     * position/department/division -- phone from {@code hr.employee.phone}, the rest from the
     * employee's CURRENT {@code hr.employee_address} row (LEFT JOIN: a missing address just leaves
     * those fields null, it doesn't fail the lookup). See LeaveService#contactDefaults.
     */
    public Optional<LeaveContactDefaultsDto> findContactDefaults(long employeeId) {
        return jdbc.query("""
            SELECT e.employee_id,
                   pos.name_th AS position_name_th,
                   dep.name_th AS department_name_th,
                   div.name_th AS division_name_th,
                   addr.house_no AS contact_house_no,
                   addr.subdistrict AS contact_subdistrict,
                   addr.district AS contact_district,
                   addr.province AS contact_province,
                   e.phone AS contact_phone
              FROM hr.employee e
              LEFT JOIN hr.position pos ON pos.position_id = e.position_id
              LEFT JOIN hr.department dep ON dep.department_id = e.department_id
              LEFT JOIN hr.division div ON div.division_id = e.division_id
              LEFT JOIN hr.employee_address addr
                     ON addr.employee_id = e.employee_id AND addr.address_type = 'CURRENT'
             WHERE e.employee_id = :employeeId
            """, Map.of("employeeId", employeeId), (rs, rowNum) -> new LeaveContactDefaultsDto(
                rs.getLong("employee_id"),
                rs.getString("position_name_th"),
                rs.getString("department_name_th"),
                rs.getString("division_name_th"),
                rs.getString("contact_house_no"),
                rs.getString("contact_subdistrict"),
                rs.getString("contact_district"),
                rs.getString("contact_province"),
                rs.getString("contact_phone")
            ))
            .stream()
            .findFirst();
    }

    public List<LeaveEmployeeOption> findEmployeeOptions(Long managerEmployeeId, boolean includeAll) {
        StringBuilder sql = new StringBuilder("""
            SELECT e.employee_id,
                   e.employee_code,
                   concat_ws(' ', e.first_name_th, e.last_name_th) AS employee_name,
                   dep.name_th AS department_name,
                   e.reports_to_employee_id
              FROM hr.employee e
              LEFT JOIN hr.department dep ON dep.department_id = e.department_id
             WHERE e.is_active = TRUE
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("managerEmployeeId", managerEmployeeId);
        if (!includeAll) {
            sql.append(" AND (e.employee_id = :managerEmployeeId OR e.reports_to_employee_id = :managerEmployeeId)");
        }
        sql.append(" ORDER BY e.employee_code");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            long employeeId = rs.getLong("employee_id");
            Long reportsTo = nullableLong(rs, "reports_to_employee_id");
            boolean self = managerEmployeeId != null && employeeId == managerEmployeeId;
            boolean directReport = managerEmployeeId != null && managerEmployeeId.equals(reportsTo);
            return new LeaveEmployeeOption(
                employeeId,
                rs.getString("employee_code"),
                rs.getString("employee_name"),
                rs.getString("department_name"),
                self,
                directReport
            );
        });
    }

    private static final String LEAVE_TYPE_COLUMNS = """
        leave_type_code, name_th, name_en, annual_quota_days, requires_attachment,
        paid_days_cap, advance_notice_days, min_service_months, max_consecutive_days,
        once_per_employment, day_count_basis, prorated_first_year, first_year_max_days,
        certificate_filing_window_days, no_certificate_monthly_tolerance,
        emergency_monthly_allowance
        """;

    public List<LeaveTypeDto> findLeaveTypes() {
        return jdbc.query("""
            SELECT %s
              FROM hr.leave_type
             WHERE is_active = TRUE
             ORDER BY leave_type_code
            """.formatted(LEAVE_TYPE_COLUMNS), this::mapLeaveType);
    }

    public Optional<LeaveTypeDto> findLeaveType(String code) {
        return jdbc.query("""
            SELECT %s
              FROM hr.leave_type
             WHERE leave_type_code = :code
               AND is_active = TRUE
            """.formatted(LEAVE_TYPE_COLUMNS), Map.of("code", code), this::mapLeaveType)
            .stream()
            .findFirst();
    }

    /**
     * §5 leave-rules-as-data (V116), min-service-months gate: {@code hr.employee.hire_date} is
     * nullable, so this is {@code Optional.empty()} exactly when the column is NULL -- see
     * LeaveService#submit for how a missing hire date is handled (deliberately NOT a silent pass).
     */
    public Optional<LocalDate> findHireDate(long employeeId) {
        // NOTE: deliberately NOT .stream().findFirst() -- Stream#findFirst() boxes its result via
        // Optional.of(element) internally, which throws NPE the moment the single element IS null
        // (exactly the hire_date IS NULL case this method exists to represent as Optional.empty()).
        // List#get(0) has no such trap.
        List<LocalDate> rows = jdbc.query("""
            SELECT hire_date
              FROM hr.employee
             WHERE employee_id = :employeeId
            """, Map.of("employeeId", employeeId),
            (rs, rowNum) -> rs.getObject("hire_date", LocalDate.class));
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /**
     * §5.2 PERSONAL "passed probation" gate (review fix, V116): {@code hr.employee.probation_days}
     * is nullable -- callers fall back to {@code SpecialMoneyPolicyEvaluator.DEFAULT_PROBATION_DAYS},
     * the same convention {@code SpecialMoneyPolicyEvaluator#evaluateStandardProbationEligibility}
     * already applies for special-money aid eligibility. See LeaveService#autoRejectNote.
     */
    public Optional<Integer> findProbationDays(long employeeId) {
        // Same List#get(0) pattern as findHireDate -- .stream().findFirst() NPEs the moment the
        // single row's probation_days IS null (Optional.of(null) inside Stream#findFirst()).
        List<Integer> rows = jdbc.query("""
            SELECT probation_days
              FROM hr.employee
             WHERE employee_id = :employeeId
            """, Map.of("employeeId", employeeId),
            (rs, rowNum) -> rs.getObject("probation_days", Integer.class));
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /**
     * §5.6 ORDINATION once-per-employment gate (V116), Java-level pre-check paired with the
     * race-proof {@code ux_leave_once_per_employment} partial unique index (see V116's migration
     * comment). Matches the index's own status scope exactly: SUBMITTED/APPROVED count as an
     * outstanding or consumed claim, CANCELLED/REJECTED/AUTO_REJECTED do not.
     */
    public boolean hasOutstandingOrGrantedRequest(long employeeId, String leaveTypeCode) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                  FROM hr.leave_request
                 WHERE employee_id = :employeeId
                   AND leave_type_code = :leaveTypeCode
                   AND status IN ('SUBMITTED', 'APPROVED')
            )
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("leaveTypeCode", leaveTypeCode),
            Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * §5.3.4 (ประกาศ "วันเวลาทำงาน และการหยุดงาน" eff. 1 ต.ค. 2567): does {@code hr.resignation} (V1)
     * carry a row for this employee at all -- see {@link LeaveService}'s resignation-gate Javadoc for
     * why mere presence, not {@code resign_date}, is what this gate reads. {@code employee_id} is
     * that table's PRIMARY KEY (1:1), so this is a plain existence check.
     */
    public boolean hasSubmittedResignation(long employeeId) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                  FROM hr.resignation
                 WHERE employee_id = :employeeId
            )
            """, Map.of("employeeId", employeeId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * §5.3.3 contiguous-leave-type gate: SUBMITTED/APPROVED {@code leaveTypeCode} requests for
     * {@code employeeId} whose range overlaps [{@code fromDate}, {@code toDate}] -- a small padding
     * window around the new request's own dates (see {@code LeaveService#CONTIGUOUS_CHECK_WINDOW_DAYS}),
     * not the employee's whole history. Same SUBMITTED/APPROVED scope as {@link
     * #hasOutstandingOrGrantedRequest} -- a still-pending request counts as taken for this gate too.
     */
    public List<LeaveRequestSpan> findActiveRequestsByType(
            long employeeId, String leaveTypeCode, LocalDate fromDate, LocalDate toDate) {
        return jdbc.query("""
            SELECT start_date, end_date
              FROM hr.leave_request
             WHERE employee_id = :employeeId
               AND leave_type_code = :leaveTypeCode
               AND status IN ('SUBMITTED', 'APPROVED')
               AND start_date <= :toDate
               AND end_date >= :fromDate
             ORDER BY start_date
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("leaveTypeCode", leaveTypeCode)
            .addValue("fromDate", fromDate)
            .addValue("toDate", toDate),
            (rs, rowNum) -> new LeaveRequestSpan(
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class)));
    }

    /**
     * §5.3.2 department-coverage gate: the employee_id of every OTHER active employee in {@code
     * employeeId}'s own department (excluding {@code employeeId} itself) -- see {@code
     * LeaveService#departmentCoverageRejectionNote}, which resolves each one's own working-day
     * predicate via {@link #workingDayPredicate} rather than this method carrying schedule columns.
     *
     * <p>Returns an EMPTY list -- not an error -- when {@code employeeId} has no {@code
     * department_id} on file, or has no active colleagues at all. {@code LeaveService} derives the
     * department's total active size directly from this list's length ({@code colleagueIds.size() +
     * 1} for the requester themself, see {@code LeaveService#MIN_DEPARTMENT_SIZE_FOR_COVERAGE_GATE})
     * rather than issuing a separate headcount query -- so this one list answers both "who else is
     * there" and "how many active people does this department have".
     */
    public List<Long> findActiveDepartmentColleagues(long employeeId) {
        return jdbc.query("""
            SELECT e.employee_id
              FROM hr.employee e
             WHERE e.is_active = TRUE
               AND e.employee_id <> :employeeId
               AND e.department_id IS NOT NULL
               AND e.department_id = (SELECT department_id FROM hr.employee WHERE employee_id = :employeeId)
            """, Map.of("employeeId", employeeId), (rs, rowNum) -> rs.getLong("employee_id"));
    }

    /**
     * §5.3.2 department-coverage gate: SUBMITTED/APPROVED requests of ANY leave type, for the given
     * {@code employeeIds} (a department's OTHER active employees), overlapping [{@code fromDate},
     * {@code toDate}]. {@code employeeIds} empty short-circuits to an empty list without querying --
     * an empty SQL {@code IN (...)} list is invalid, and {@link #findActiveDepartmentColleagues}
     * already guarantees this is only ever called with a non-empty set in practice.
     */
    public List<EmployeeLeaveSpan> findActiveLeaveSpans(
            Collection<Long> employeeIds, LocalDate fromDate, LocalDate toDate) {
        if (employeeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
            SELECT employee_id, start_date, end_date
              FROM hr.leave_request
             WHERE employee_id IN (:employeeIds)
               AND status IN ('SUBMITTED', 'APPROVED')
               AND start_date <= :toDate
               AND end_date >= :fromDate
            """, new MapSqlParameterSource()
            .addValue("employeeIds", employeeIds)
            .addValue("fromDate", fromDate)
            .addValue("toDate", toDate),
            (rs, rowNum) -> new EmployeeLeaveSpan(
                rs.getLong("employee_id"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class)));
    }

    /**
     * V118 cross-year quota fix (2026-08-02): reads from {@code hr.leave_request_quota_year}, NOT the
     * parent's own {@code total_days}/{@code quota_year} -- a request's days may now be attributed to
     * more than one calendar year (§5.4 MATERNITY), so quota consumption for a given year must sum
     * only the days that year's own child row records, not a whole multi-year request's total just
     * because it happens to touch this year at all. For every pre-V118 (necessarily single-year)
     * request this is byte-identical to the old parent-column query, since V118's backfill gave each
     * one exactly one child row carrying its own total_days.
     */
    public BigDecimal sumUsedDays(long employeeId, String leaveTypeCode, int quotaYear, Collection<LeaveStatus> statuses) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(sum(lrqy.total_days), 0)::numeric(5,2)
              FROM hr.leave_request_quota_year lrqy
              JOIN hr.leave_request lr ON lr.leave_request_id = lrqy.leave_request_id
             WHERE lr.employee_id = :employeeId
               AND lr.leave_type_code = :leaveTypeCode
               AND lrqy.quota_year = :quotaYear
               AND lr.status IN (:statuses)
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("leaveTypeCode", leaveTypeCode)
            .addValue("quotaYear", quotaYear)
            .addValue("statuses", statuses.stream().map(LeaveStatus::name).toList()),
            BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * §5 leave-rules-as-data (V116), paid_days_cap gate: sum of the {@code paid_days} (not
     * {@code total_days} -- see {@link #sumUsedDays}) already granted/pending this quota year, for
     * types whose paid allowance is capped independently of the quota itself (e.g. MATERNITY: 98-day
     * quota, 45-day paid cap). See LeaveService#submit.
     *
     * <p>V118 cross-year quota fix: same per-year child-table read as {@link #sumUsedDays} above, and
     * for the same reason -- a capped type's paid allowance is also a per-calendar-year concept (see
     * {@link LeaveQuotaYearSplit}'s Javadoc for the "per-year-independent cap" design decision).
     */
    public BigDecimal sumPaidDays(long employeeId, String leaveTypeCode, int quotaYear, Collection<LeaveStatus> statuses) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(sum(lrqy.paid_days), 0)::numeric(5,2)
              FROM hr.leave_request_quota_year lrqy
              JOIN hr.leave_request lr ON lr.leave_request_id = lrqy.leave_request_id
             WHERE lr.employee_id = :employeeId
               AND lr.leave_type_code = :leaveTypeCode
               AND lrqy.quota_year = :quotaYear
               AND lr.status IN (:statuses)
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("leaveTypeCode", leaveTypeCode)
            .addValue("quotaYear", quotaYear)
            .addValue("statuses", statuses.stream().map(LeaveStatus::name).toList()),
            BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * §5.1 SICK no-certificate monthly tolerance (V124): counts EXISTING certificate-less
     * ({@code attachment_id IS NULL}) requests of this type, in the given status set, whose {@code
     * start_date} falls within {@code [monthStart, monthEndInclusive]} -- the SAME start_date-only
     * month attribution {@code quota_year} already uses (see {@link LeaveService#submit}). Counts
     * REQUESTS (occasions), not days -- see {@link LeaveService#sickCertificateNote}'s Javadoc for
     * why. Does NOT include the request currently being submitted (it does not exist yet at the time
     * this is called, since {@link LeaveService#submit} evaluates {@code autoRejectNote} before
     * {@link #create}).
     */
    public int countNoCertificateRequestsInMonth(long employeeId, String leaveTypeCode,
            LocalDate monthStart, LocalDate monthEndInclusive, Collection<LeaveStatus> statuses) {
        Integer value = jdbc.queryForObject("""
            SELECT count(*)
              FROM hr.leave_request
             WHERE employee_id = :employeeId
               AND leave_type_code = :leaveTypeCode
               AND attachment_id IS NULL
               AND start_date BETWEEN :monthStart AND :monthEndInclusive
               AND status IN (:statuses)
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("leaveTypeCode", leaveTypeCode)
            .addValue("monthStart", monthStart)
            .addValue("monthEndInclusive", monthEndInclusive)
            .addValue("statuses", statuses.stream().map(LeaveStatus::name).toList()),
            Integer.class);
        return value == null ? 0 : value;
    }

    /**
     * V118 cross-year quota fix: persists the per-calendar-year attribution {@code LeaveService#submit}
     * computed for a just-created leave request -- one row per year in {@code splits}, always at
     * least one (every request touches at least its own start year). Called in the same transaction
     * as {@link #create}, right after it returns the new id.
     */
    public void insertQuotaYearSplits(long leaveRequestId, List<LeaveQuotaYearSplit> splits) {
        for (LeaveQuotaYearSplit split : splits) {
            jdbc.update("""
                INSERT INTO hr.leave_request_quota_year (
                    leave_request_id, quota_year, total_days, paid_days, unpaid_days,
                    quota_remaining_before, quota_remaining_after
                )
                VALUES (
                    :leaveRequestId, :quotaYear, :totalDays, :paidDays, :unpaidDays,
                    :quotaRemainingBefore, :quotaRemainingAfter
                )
                """, new MapSqlParameterSource()
                .addValue("leaveRequestId", leaveRequestId)
                .addValue("quotaYear", split.quotaYear())
                .addValue("totalDays", split.totalDays())
                .addValue("paidDays", split.paidDays())
                .addValue("unpaidDays", split.unpaidDays())
                .addValue("quotaRemainingBefore", split.quotaRemainingBefore())
                .addValue("quotaRemainingAfter", split.quotaRemainingAfter()));
        }
    }

    /**
     * V118 cross-year quota fix: reads back a leave request's per-year attribution -- used by {@code
     * LeaveService#cancel}'s cancel-after-close reversal ({@code recordPayrollCorrectionIfNeeded}),
     * which needs each year's own (paidDays, totalDays) to correctly re-derive which calendar months
     * were unpaid via {@link LeaveDayMath#unpaidWorkingDaysByMonthAcrossYears}. Ordered by quota_year
     * so the earliest (start) year is always first.
     */
    public List<LeaveQuotaYearSplit> findQuotaYearSplits(long leaveRequestId) {
        return jdbc.query("""
            SELECT quota_year, total_days, paid_days, unpaid_days,
                   quota_remaining_before, quota_remaining_after
              FROM hr.leave_request_quota_year
             WHERE leave_request_id = :leaveRequestId
             ORDER BY quota_year
            """, Map.of("leaveRequestId", leaveRequestId),
            (rs, rowNum) -> new LeaveQuotaYearSplit(
                rs.getInt("quota_year"),
                rs.getObject("total_days", BigDecimal.class),
                rs.getObject("paid_days", BigDecimal.class),
                rs.getObject("unpaid_days", BigDecimal.class),
                rs.getObject("quota_remaining_before", BigDecimal.class),
                rs.getObject("quota_remaining_after", BigDecimal.class)
            ));
    }

    /**
     * Leave -&gt; payroll unpaid-day deduction (2026-07-23): per employee, the unpaid WORKING days of
     * APPROVED leave that fall inside payroll month {@code monthStart} (a first-of-month date). Reads
     * every APPROVED, unpaid-day-bearing request whose date range overlaps the month, then attributes
     * days to the month in Java via {@link LeaveDayMath#unpaidWorkingDaysByMonth} so a leave spanning
     * two calendar months splits correctly -- only the unpaid working days that actually fall in this
     * month count here, not the request's full unpaid_days total. Consumed by {@code
     * PayrollService#suggestedInputs} (additive; never touches {@code preview()}/{@code process()}).
     *
     * <p>V118 cross-year quota fix: reads {@code paid_days}/{@code total_days} from the
     * {@code hr.leave_request_quota_year} row for {@code year(monthStart)} specifically -- NOT the
     * parent's aggregate columns -- and clips the date range to that same calendar year before
     * handing it to {@link LeaveDayMath#unpaidWorkingDaysByMonth}. A payroll month always belongs to
     * exactly one calendar year, so this year's own child row is always sufficient (an unpaid day in
     * month M can only ever come from year(M)'s own attribution -- see {@link
     * LeaveQuotaYearSplit}'s Javadoc on why paid-day consumption resets at a year boundary, which is
     * exactly why the PARENT's aggregate paid_days/total_days can no longer be used here). For every
     * pre-V118 (necessarily single-year) request this is byte-identical to the old behaviour, since
     * the year-clip is a no-op when the whole range already sits inside one year.
     *
     * <p>§5.4 MATERNITY calendar-day counting (V119): joins {@code hr.leave_type} for {@code
     * day_count_basis} and passes it through to {@link LeaveDayMath#unpaidWorkingDaysByMonth}.
     * DECISION (see LeaveService's submit()/recordPayrollCorrectionIfNeeded() Javadoc for the full
     * reasoning): the payroll base/30 deduction uses the SAME basis as the leave type's own
     * quota/paid-cap counting, not always WORKING_DAYS. For MATERNITY this means an unpaid day that
     * falls on a Saturday/Sunday inside the leave IS deducted -- because {@code paid_days}/{@code
     * total_days} were themselves computed on a calendar-day basis (§5.4), so a working-days-only
     * reading here would silently drop weekend/holiday days from the deduction even though they were
     * counted as consumed leave, breaking the {@code paidDays + unpaidDays == totalDays} invariant
     * this method's ranking logic depends on. For every WORKING_DAYS type (everything except
     * MATERNITY) this is byte-identical to the pre-V119 behaviour.
     *
     * <p>Schedule/holiday-aware working-day counting (2026-08-03): also joins {@code hr.employee}
     * for {@code division_id}/{@code department_id} (schedule resolution) and batch-loads the
     * MONTH's holidays ONCE via {@code holidayCalendar.holidaysBetween} -- not once per span, and
     * definitely not once per day -- then builds one {@link LeaveDayMath#workingDayPredicate} per
     * span from that employee's own division/department. A WORKING_DAYS-basis span for a
     * {@code OPS_6D} (six-day) employee now correctly counts an unpaid Saturday inside it; a span
     * overlapping a seeded {@code hr.holiday} row no longer counts that date at all, for any
     * employee. MATERNITY (CALENDAR_DAYS) is unaffected -- see {@link LeaveDayCountBasis}.
     */
    public Map<Long, BigDecimal> findUnpaidLeaveDaysByEmployeeForMonth(LocalDate monthStart) {
        LocalDate monthEndInclusive = monthStart.plusMonths(1).minusDays(1);
        int year = monthStart.getYear();
        List<UnpaidLeaveSpan> spans = jdbc.query("""
            SELECT lr.employee_id, lr.start_date, lr.end_date, lrqy.paid_days, lrqy.total_days,
                   lt.day_count_basis, e.division_id, e.department_id
              FROM hr.leave_request lr
              JOIN hr.leave_request_quota_year lrqy
                ON lrqy.leave_request_id = lr.leave_request_id
               AND lrqy.quota_year = :year
              JOIN hr.leave_type lt ON lt.leave_type_code = lr.leave_type_code
              JOIN hr.employee e ON e.employee_id = lr.employee_id
             WHERE lr.status = 'APPROVED'
               AND lrqy.unpaid_days > 0
               AND lr.start_date <= :monthEndInclusive
               AND lr.end_date >= :monthStart
            """,
            new MapSqlParameterSource()
                .addValue("monthStart", monthStart)
                .addValue("monthEndInclusive", monthEndInclusive)
                .addValue("year", year),
            (rs, rowNum) -> new UnpaidLeaveSpan(
                rs.getLong("employee_id"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getObject("paid_days", BigDecimal.class),
                rs.getObject("total_days", BigDecimal.class),
                LeaveDayCountBasis.valueOf(rs.getString("day_count_basis")),
                nullableLong(rs, "division_id"),
                nullableLong(rs, "department_id")
            ));

        // One holiday read for the WHOLE month, shared by every span/employee below -- see this
        // method's javadoc. A payroll month is at most 31 days, so this is one bounded query no
        // matter how many employees have an unpaid span inside it.
        Set<LocalDate> holidaysThisMonth = holidayCalendar.holidaysBetween(monthStart, monthEndInclusive);

        Map<Long, BigDecimal> byEmployee = new LinkedHashMap<>();
        for (UnpaidLeaveSpan span : spans) {
            LocalDate[] clipped = LeaveDayMath.clipToYear(span.startDate(), span.endDate(), year);
            if (clipped == null) {
                continue;
            }
            Predicate<LocalDate> isWorkingDay = LeaveDayMath.workingDayPredicate(
                scheduleResolver, span.employeeId(), span.divisionId(), span.departmentId(), holidaysThisMonth);
            // Sub-day leave (2026-07-25): reads the BigDecimal LeaveDayMath computes directly --
            // no more setScale(0, DOWN) floor, which used to discard a sub-day fraction entirely.
            BigDecimal unpaidInMonth = LeaveDayMath
                .unpaidWorkingDaysByMonth(
                    clipped[0], clipped[1], span.paidDays(), span.totalDays(), span.basis(), isWorkingDay)
                .get(monthStart);
            if (unpaidInMonth != null && unpaidInMonth.signum() > 0) {
                byEmployee.merge(span.employeeId(), unpaidInMonth, BigDecimal::add);
            }
        }
        return byEmployee;
    }

    /**
     * Cancel-after-close reversal (2026-07-23): of the given candidate months, which already have a
     * PROCESSED payroll_period -- i.e. which of the cancelled leave's months are "closed" and can no
     * longer simply un-happen. Called from {@code LeaveService#cancel}.
     */
    public Set<LocalDate> findProcessedPayrollMonths(Collection<LocalDate> candidateMonths) {
        if (candidateMonths.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.query("""
            SELECT payroll_month
              FROM hr.payroll_period
             WHERE status = 'PROCESSED'
               AND payroll_month IN (:months)
            """,
            new MapSqlParameterSource().addValue("months", candidateMonths),
            (rs, rowNum) -> rs.getObject("payroll_month", LocalDate.class)));
    }

    /**
     * Cancel-after-close reversal (2026-07-23): records that {@code unpaidDaysToRefund} unpaid days
     * already deducted for {@code payrollMonth} belong to a leave request that has since been
     * cancelled, so the credit is still owed to the employee. Never auto-resolved by this codebase yet
     * -- see the V85 migration comment and the branch handoff for why.
     */
    public void recordPayrollCorrection(long leaveRequestId, long employeeId, LocalDate payrollMonth, BigDecimal unpaidDaysToRefund) {
        jdbc.update("""
            INSERT INTO hr.leave_payroll_correction (
                leave_request_id, employee_id, payroll_month, unpaid_days_to_refund
            )
            VALUES (:leaveRequestId, :employeeId, :payrollMonth, :unpaidDaysToRefund)
            """,
            new MapSqlParameterSource()
                .addValue("leaveRequestId", leaveRequestId)
                .addValue("employeeId", employeeId)
                .addValue("payrollMonth", payrollMonth)
                .addValue("unpaidDaysToRefund", unpaidDaysToRefund));
    }

    /**
     * Cancel-after-close reversal (2026-07-23): unresolved (not yet {@code resolved_at}) correction
     * totals per employee, across all months -- surfaced by {@code PayrollService#suggestedInputs}
     * as an early "heads up, something is outstanding" figure for HR, independent of any specific
     * payroll run. This is deliberately UNSCOPED (no give-back-on-re-run logic) and never mutates
     * anything -- the actual auto-refund + resolve now lives in {@link
     * #findRefundableUnpaidDaysByEmployee} / {@link #resolvePendingCorrections}, used by {@code
     * PayrollService#preview}/{@code #process} directly. Keep this one as-is; it is a separate,
     * lighter-weight signal, not the source of truth for what a given run will actually refund.
     */
    public Map<Long, BigDecimal> findPendingPayrollCorrectionsByEmployee() {
        return jdbc.query("""
            SELECT employee_id, SUM(unpaid_days_to_refund)::numeric(5,2) AS total_days
              FROM hr.leave_payroll_correction
             WHERE resolved_at IS NULL
             GROUP BY employee_id
            """, Map.of(),
            (rs, rowNum) -> Map.entry(rs.getLong("employee_id"), rs.getBigDecimal("total_days")))
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Cancel-after-close reversal, AUTO-REFUND (2026-07-23): total unpaid days to refund per
     * employee that THIS payroll calculation should credit back -- pending corrections
     * ({@code resolved_at IS NULL}) PLUS, if {@code givingBackPeriodId} is non-null, any correction
     * already resolved BY THAT SAME period. The give-back half is what makes re-processing a month
     * idempotent without double-refunding or losing the credit: a re-run recomputes the whole
     * period from scratch, so corrections that period previously resolved must come back into the
     * pool for this recomputation, while corrections resolved by any OTHER (earlier or later)
     * period stay excluded. Pass {@code null} when the period does not exist yet (nothing to give
     * back -- equivalent to a plain "pending only" read). Called once per {@code
     * PayrollService#preview}/{@code #process} invocation, in the same transaction as the paired
     * {@link #resolvePendingCorrections} call on process -- see that method for how the two stay
     * consistent with each other.
     */
    public Map<Long, BigDecimal> findRefundableUnpaidDaysByEmployee(Long givingBackPeriodId) {
        return jdbc.query("""
            SELECT employee_id, SUM(unpaid_days_to_refund)::numeric(5,2) AS total_days
              FROM hr.leave_payroll_correction
             WHERE resolved_at IS NULL
                OR resolved_payroll_period_id = :givingBackPeriodId::bigint
             GROUP BY employee_id
            """,
            new MapSqlParameterSource("givingBackPeriodId", givingBackPeriodId),
            (rs, rowNum) -> Map.entry(rs.getLong("employee_id"), rs.getBigDecimal("total_days")))
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Cancel-after-close reversal, AUTO-REFUND (2026-07-23): marks every correction consumed by
     * {@code periodId}'s just-computed calculation as resolved. The WHERE clause is deliberately
     * the exact same shape as {@link #findRefundableUnpaidDaysByEmployee}'s read for this same
     * {@code periodId} -- run in the same DB transaction (see {@code PayrollService#process},
     * {@code @Transactional}), so this UPDATE only ever touches rows the preceding read already
     * included. Idempotent by construction: a correction with {@code resolved_payroll_period_id =
     * periodId} already (from a prior run of this same month) just gets {@code resolved_at}
     * refreshed -- no double-refund, because {@code findRefundableUnpaidDaysByEmployee} would have
     * included that exact row in the SUM whether or not this method had ever run before. A
     * correction resolved by a DIFFERENT period is excluded by both the read and this write, so it
     * is never touched, never re-summed, and never double-counted.
     */
    public void resolvePendingCorrections(long periodId) {
        jdbc.update("""
            UPDATE hr.leave_payroll_correction
               SET resolved_at = now(),
                   resolved_payroll_period_id = :periodId
             WHERE resolved_at IS NULL
                OR resolved_payroll_period_id = :periodId
            """, new MapSqlParameterSource("periodId", periodId));
    }

    private record UnpaidLeaveSpan(
        long employeeId, LocalDate startDate, LocalDate endDate, BigDecimal paidDays, BigDecimal totalDays,
        LeaveDayCountBasis basis, Long divisionId, Long departmentId) {
    }

    public long create(
            long employeeId,
            Long requestedById,
            SubmitLeaveRequest request,
            BigDecimal totalDays,
            BigDecimal paidDays,
            BigDecimal unpaidDays,
            int quotaYear,
            LeaveStatus status,
            BigDecimal quotaRemainingBefore,
            BigDecimal quotaRemainingAfter,
            String systemNote,
            String contactHouseNo,
            String contactSubdistrict,
            String contactDistrict,
            String contactProvince,
            String contactPhone) {
        Long id = jdbc.queryForObject("""
            INSERT INTO hr.leave_request (
                employee_id, leave_type_code, start_date, end_date, start_time, end_time,
                total_days, paid_days, unpaid_days,
                quota_year, reason, status, quota_remaining_before,
                quota_remaining_after, system_note, requested_by_id,
                contact_house_no, contact_subdistrict, contact_district, contact_province, contact_phone,
                purpose_code
            )
            VALUES (
                :employeeId, :leaveTypeCode, :startDate, :endDate, :startTime, :endTime,
                :totalDays, :paidDays, :unpaidDays,
                :quotaYear, :reason, :status, :quotaRemainingBefore,
                :quotaRemainingAfter, :systemNote, :requestedById,
                :contactHouseNo, :contactSubdistrict, :contactDistrict, :contactProvince, :contactPhone,
                :purposeCode
            )
            RETURNING leave_request_id
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("leaveTypeCode", request.leaveTypeCode().trim().toUpperCase())
            .addValue("startDate", request.startDate())
            .addValue("endDate", request.endDate())
            .addValue("startTime", request.startTime())
            .addValue("endTime", request.endTime())
            .addValue("totalDays", totalDays)
            .addValue("paidDays", paidDays)
            .addValue("unpaidDays", unpaidDays)
            .addValue("quotaYear", quotaYear)
            .addValue("reason", request.reason().trim())
            .addValue("status", status.name())
            .addValue("quotaRemainingBefore", quotaRemainingBefore)
            .addValue("quotaRemainingAfter", quotaRemainingAfter)
            .addValue("systemNote", systemNote)
            .addValue("requestedById", requestedById)
            .addValue("contactHouseNo", contactHouseNo)
            .addValue("contactSubdistrict", contactSubdistrict)
            .addValue("contactDistrict", contactDistrict)
            .addValue("contactProvince", contactProvince)
            .addValue("contactPhone", contactPhone)
            // §5.2 leave purpose (V125): same trim/uppercase normalization as leaveTypeCode above --
            // the whitelist check itself already ran in LeaveService#normalizePurposeCode before this
            // was ever called, so no re-validation happens here (matches leaveTypeCode's own split of
            // concerns: Java validates, the repository just persists the normalized form).
            .addValue("purposeCode", request.purposeCode() == null || request.purposeCode().isBlank()
                ? null : request.purposeCode().trim().toUpperCase()),
            Long.class);
        return id == null ? 0 : id;
    }

    /**
     * §5.2 emergency-filing exception (V125): marks a just-created request as having been approved
     * via the emergency tolerance rather than by meeting its type's ordinary advance notice. Called
     * conditionally after {@link #create}, in the same transaction -- mirrors {@link #attachFile}'s
     * shape (a small follow-up UPDATE by id, kept OUT of {@link #create}'s own parameter list so
     * every existing caller/test of that method is unaffected by this addition).
     */
    public int markEmergencyFiling(long leaveRequestId) {
        return jdbc.update("""
            UPDATE hr.leave_request
               SET emergency_filing = TRUE,
                   updated_at = now()
             WHERE leave_request_id = :leaveRequestId
            """, new MapSqlParameterSource().addValue("leaveRequestId", leaveRequestId));
    }

    /**
     * §5.2 emergency-filing exception (V125): rolling monthly count of this employee's PRIOR
     * requests of {@code leaveTypeCode} that were themselves approved via the emergency exception
     * ({@code emergency_filing = TRUE}), for the calendar month containing {@code requestStartDate}
     * -- see LeaveService#autoRejectNote for how a NEW request's own eligibility for the exception is
     * bounded by this count. A plain COUNT over existing rows, never a separately-maintained counter
     * -- see V125's migration comment for why.
     *
     * <p>Scoped to {@code statuses} the same way {@link #sumUsedDays} is scoped to
     * ACTIVE_QUOTA_STATUSES (SUBMITTED/APPROVED): a CANCELLED emergency filing releases its slot for
     * the month, mirroring {@code ux_leave_once_per_employment}'s identical "cancelling releases the
     * claim" precedent (V116).
     */
    public int countEmergencyFilings(long employeeId, String leaveTypeCode, LocalDate requestStartDate, Collection<LeaveStatus> statuses) {
        LocalDate monthStart = requestStartDate.withDayOfMonth(1);
        LocalDate monthStartNext = monthStart.plusMonths(1);
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM hr.leave_request
             WHERE employee_id = :employeeId
               AND leave_type_code = :leaveTypeCode
               AND emergency_filing = TRUE
               AND status IN (:statuses)
               AND start_date >= :monthStart
               AND start_date < :monthStartNext
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("leaveTypeCode", leaveTypeCode)
            .addValue("statuses", statuses.stream().map(LeaveStatus::name).toList())
            .addValue("monthStart", monthStart)
            .addValue("monthStartNext", monthStartNext),
            Integer.class);
        return count == null ? 0 : count;
    }

    public int attachFile(long leaveRequestId, long attachmentId) {
        return jdbc.update("""
            UPDATE hr.leave_request
               SET attachment_id = :attachmentId,
                   updated_at = now()
             WHERE leave_request_id = :leaveRequestId
            """, new MapSqlParameterSource()
            .addValue("leaveRequestId", leaveRequestId)
            .addValue("attachmentId", attachmentId));
    }

    public Optional<LeaveRequestDto> findById(long id) {
        return jdbc.query(baseSelect() + " WHERE lr.leave_request_id = :id",
            Map.of("id", id),
            this::mapRequest)
            .stream()
            .findFirst();
    }

    public List<LeaveRequestDto> findRequests(LeaveFilter filter) {
        StringBuilder sql = new StringBuilder(baseSelect()).append("""
             WHERE lr.start_date <= :toDate
               AND lr.end_date >= :fromDate
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("fromDate", filter.fromDate())
            .addValue("toDate", filter.toDate());

        if (filter.employeeId() != null) {
            sql.append(" AND lr.employee_id = :employeeId");
            params.addValue("employeeId", filter.employeeId());
        }
        if (filter.managerEmployeeId() != null) {
            sql.append(" AND (lr.employee_id = :managerEmployeeId OR e.reports_to_employee_id = :managerEmployeeId)");
            params.addValue("managerEmployeeId", filter.managerEmployeeId());
        }
        if (filter.status() != null) {
            sql.append(" AND lr.status = :status");
            params.addValue("status", filter.status().name());
        }

        sql.append(" ORDER BY lr.start_date DESC, lr.leave_request_id DESC");
        return jdbc.query(sql.toString(), params, this::mapRequest);
    }

    public int approve(long id, Long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.leave_request
               SET status = 'APPROVED',
                   reviewed_by_id = :reviewedById,
                   reviewed_at = now(),
                   reviewer_note = :reviewerNote,
                   updated_at = now()
             WHERE leave_request_id = :id
               AND status = 'SUBMITTED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("reviewerNote", clean(reviewerNote)));
    }

    public int reject(long id, Long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.leave_request
               SET status = 'REJECTED',
                   reviewed_by_id = :reviewedById,
                   reviewed_at = now(),
                   reviewer_note = :reviewerNote,
                   updated_at = now()
             WHERE leave_request_id = :id
               AND status = 'SUBMITTED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("reviewerNote", clean(reviewerNote)));
    }

    public int cancel(long id, Long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.leave_request
               SET status = 'CANCELLED',
                   reviewed_by_id = COALESCE(:reviewedById, reviewed_by_id),
                   reviewed_at = CASE WHEN :reviewedById IS NULL THEN reviewed_at ELSE now() END,
                   reviewer_note = COALESCE(:reviewerNote, reviewer_note),
                   cancelled_at = now(),
                   updated_at = now()
             WHERE leave_request_id = :id
               AND status IN ('SUBMITTED', 'APPROVED')
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("reviewerNote", clean(reviewerNote)));
    }

    private String baseSelect() {
        return """
            SELECT lr.leave_request_id,
                   lr.employee_id,
                   e.employee_code,
                   concat_ws(' ', e.first_name_th, e.last_name_th) AS employee_name,
                   lr.leave_type_code,
                   lt.name_th AS leave_type_name_th,
                   lt.name_en AS leave_type_name_en,
                   lr.start_date,
                   lr.end_date,
                   lr.start_time,
                   lr.end_time,
                   lr.total_days,
                   lr.paid_days,
                   lr.unpaid_days,
                   lr.quota_year,
                   lr.reason,
                   lr.attachment_id,
                   fa.file_name AS attachment_file_name,
                   lr.status,
                   lr.quota_remaining_before,
                   lr.quota_remaining_after,
                   lr.system_note,
                   lr.requested_by_id,
                   concat_ws(' ', requested_by.first_name_th, requested_by.last_name_th) AS requested_by_name,
                   lr.requested_at,
                   lr.reviewed_by_id,
                   concat_ws(' ', reviewed_by.first_name_th, reviewed_by.last_name_th) AS reviewed_by_name,
                   lr.reviewed_at,
                   lr.reviewer_note,
                   lr.cancelled_at,
                   e.reports_to_employee_id,
                   concat_ws(' ', manager.first_name_th, manager.last_name_th) AS manager_name,
                   lr.created_at,
                   lr.updated_at,
                   lr.contact_house_no,
                   lr.contact_subdistrict,
                   lr.contact_district,
                   lr.contact_province,
                   lr.contact_phone,
                   lr.purpose_code,
                   lr.emergency_filing
              FROM hr.leave_request lr
              JOIN hr.employee e ON e.employee_id = lr.employee_id
              JOIN hr.leave_type lt ON lt.leave_type_code = lr.leave_type_code
              LEFT JOIN hr.employee requested_by ON requested_by.employee_id = lr.requested_by_id
              LEFT JOIN hr.employee reviewed_by ON reviewed_by.employee_id = lr.reviewed_by_id
              LEFT JOIN hr.employee manager ON manager.employee_id = e.reports_to_employee_id
              LEFT JOIN hr.file_attachment fa ON fa.attachment_id = lr.attachment_id
            """;
    }

    private LeaveTypeDto mapLeaveType(ResultSet rs, int rowNum) throws SQLException {
        return new LeaveTypeDto(
            rs.getString("leave_type_code"),
            rs.getString("name_th"),
            rs.getString("name_en"),
            rs.getObject("annual_quota_days", BigDecimal.class),
            rs.getBoolean("requires_attachment"),
            rs.getObject("paid_days_cap", BigDecimal.class),
            rs.getInt("advance_notice_days"),
            rs.getInt("min_service_months"),
            rs.getObject("max_consecutive_days", BigDecimal.class),
            rs.getBoolean("once_per_employment"),
            LeaveDayCountBasis.valueOf(rs.getString("day_count_basis")),
            rs.getBoolean("prorated_first_year"),
            rs.getObject("first_year_max_days", BigDecimal.class),
            rs.getObject("certificate_filing_window_days", Integer.class),
            rs.getInt("no_certificate_monthly_tolerance"),
            rs.getObject("emergency_monthly_allowance", Integer.class)
        );
    }

    private LeaveRequestDto mapRequest(ResultSet rs, int rowNum) throws SQLException {
        return new LeaveRequestDto(
            rs.getLong("leave_request_id"),
            rs.getLong("employee_id"),
            rs.getString("employee_code"),
            rs.getString("employee_name"),
            rs.getString("leave_type_code"),
            rs.getString("leave_type_name_th"),
            rs.getString("leave_type_name_en"),
            rs.getObject("start_date", LocalDate.class),
            rs.getObject("end_date", LocalDate.class),
            rs.getObject("start_time", LocalTime.class),
            rs.getObject("end_time", LocalTime.class),
            rs.getObject("total_days", BigDecimal.class),
            rs.getObject("paid_days", BigDecimal.class),
            rs.getObject("unpaid_days", BigDecimal.class),
            rs.getInt("quota_year"),
            rs.getString("reason"),
            nullableLong(rs, "attachment_id"),
            rs.getString("attachment_file_name"),
            rs.getString("status"),
            rs.getObject("quota_remaining_before", BigDecimal.class),
            rs.getObject("quota_remaining_after", BigDecimal.class),
            rs.getString("system_note"),
            nullableLong(rs, "requested_by_id"),
            blankToNull(rs.getString("requested_by_name")),
            rs.getObject("requested_at", OffsetDateTime.class),
            nullableLong(rs, "reviewed_by_id"),
            blankToNull(rs.getString("reviewed_by_name")),
            rs.getObject("reviewed_at", OffsetDateTime.class),
            rs.getString("reviewer_note"),
            rs.getObject("cancelled_at", OffsetDateTime.class),
            nullableLong(rs, "reports_to_employee_id"),
            blankToNull(rs.getString("manager_name")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            rs.getString("contact_house_no"),
            rs.getString("contact_subdistrict"),
            rs.getString("contact_district"),
            rs.getString("contact_province"),
            rs.getString("contact_phone"),
            rs.getString("purpose_code"),
            rs.getBoolean("emergency_filing")
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
