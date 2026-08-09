package th.co.glr.hr.overtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.approval.PendingApproverSql;
import th.co.glr.hr.employee.ManagerApproverRepository;

@Repository
public class OvertimeRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public OvertimeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    /**
     * True once payroll has been run for the month. Approved overtime is picked up by
     * {@code PayrollRepository#findApprovedOvertimePayByEmployee}, which keys on
     * {@code payroll_month}, and a processed period is written once — so anything that lands in a
     * processed month would never be paid.
     */
    public boolean payrollMonthProcessed(LocalDate payrollMonth) {
        Boolean processed = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                  FROM hr.payroll_period
                 WHERE payroll_month = :payrollMonth
                   AND status = 'PROCESSED'
            )
            """, Map.of("payrollMonth", payrollMonth), Boolean.class);
        return Boolean.TRUE.equals(processed);
    }

    /**
     * True when {@code payrollMonth} was already paid outside the ERP and is covered by
     * {@code hr.payroll_year_to_date_seed} (V114) -- distinct from {@link
     * #payrollMonthProcessed}, which is true only once THIS system has run payroll for the month.
     * A seed-covered month is never {@code PROCESSED} here (the guard trigger on {@code
     * hr.payroll_period} refuses that, to avoid double-counting year-to-date withholding), so
     * without this check {@code payrollMonthProcessed} alone would report such a month as open and
     * let overtime be filed into it -- money that would then never be paid by anything.
     */
    public boolean payrollMonthSeedCovered(LocalDate payrollMonth) {
        Boolean covered = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                  FROM hr.payroll_seed_coverage c
                 WHERE c.tax_year = EXTRACT(YEAR FROM :payrollMonth)::smallint
                   AND :payrollMonth <= c.covers_through
            )
            """, Map.of("payrollMonth", payrollMonth), Boolean.class);
        return Boolean.TRUE.equals(covered);
    }

    public Optional<OvertimeEmployeeAccess> findEmployeeAccess(long employeeId) {
        return jdbc.query("""
            SELECT employee_id, reports_to_employee_id, division_id, is_active
              FROM hr.employee
             WHERE employee_id = :employeeId
            """, Map.of("employeeId", employeeId), (rs, rowNum) -> new OvertimeEmployeeAccess(
                rs.getLong("employee_id"),
                nullableLong(rs, "reports_to_employee_id"),
                nullableLong(rs, "division_id"),
                rs.getBoolean("is_active")
            ))
            .stream()
            .findFirst();
    }

    public List<OvertimeEmployeeOption> findEmployeeOptions(
            Long managerEmployeeId, Long managerDivisionId, boolean includeAll) {
        StringBuilder sql = new StringBuilder("""
            SELECT e.employee_id,
                   e.employee_code,
                   concat_ws(' ', e.first_name_th, e.last_name_th) AS employee_name,
                   dep.name_th AS department_name,
                   e.reports_to_employee_id,
                   e.division_id
              FROM hr.employee e
              LEFT JOIN hr.department dep ON dep.department_id = e.department_id
             WHERE e.is_active = TRUE
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("managerEmployeeId", managerEmployeeId)
            .addValue("managerDivisionId", managerDivisionId);
        if (!includeAll) {
            // Kept in step with findRequests' scope and OvertimeService.managesEmployee: offering an
            // employee here that submit() would then refuse turns a picker choice into a 403.
            sql.append(" AND (e.employee_id = :managerEmployeeId");
            if (managerDivisionId != null) {
                sql.append(" OR e.division_id = :managerDivisionId");
            }
            sql.append(")");
        }
        sql.append(" ORDER BY e.employee_code");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            long employeeId = rs.getLong("employee_id");
            Long divisionId = nullableLong(rs, "division_id");
            boolean self = managerEmployeeId != null && employeeId == managerEmployeeId;
            boolean directReport =
                managerDivisionId != null && managerDivisionId.equals(divisionId) && !self;
            return new OvertimeEmployeeOption(
                employeeId,
                rs.getString("employee_code"),
                rs.getString("employee_name"),
                rs.getString("department_name"),
                self,
                directReport
            );
        });
    }

    /**
     * @param submitTimeCalculationNote nullable. Written straight into {@code calculation_note} at
     *     INSERT time -- normally {@code null} (nothing to say yet; a real calculation note is only
     *     produced at approval, see {@link #managerApprove}/{@link #ceoDirectApprove}). Non-null
     *     exactly when {@code OvertimeService#resolveDayTypeSubmitNote} found the calendar has zero
     *     rows for the work date's year at all -- the day-type DERIVATION is unverified, so this
     *     fires regardless of what (if anything) {@code SubmitOvertimeRequest.dayType} claimed.
     *     Approval must APPEND to this, never overwrite it wholesale -- see {@code
     *     OvertimeService#preserveDayTypeClaimFlag}.
     */
    public long create(
            long employeeId,
            Long requestedById,
            SubmitOvertimeRequest request,
            int plannedMinutes,
            OvertimeDayType dayType,
            LocalDate payrollMonth,
            String submitTimeCalculationNote) {
        Long id = jdbc.queryForObject("""
            INSERT INTO hr.overtime_request (
                employee_id, work_date, planned_start_at, planned_end_at, planned_minutes,
                day_type, pay_rate_multiplier, calculation_note, reason, payroll_month, requested_by_id
            )
            VALUES (
                :employeeId, :workDate, :plannedStartAt, :plannedEndAt, :plannedMinutes,
                :dayType, :payRateMultiplier, :calculationNote, :reason, :payrollMonth, :requestedById
            )
            RETURNING overtime_request_id
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("workDate", request.workDate())
            .addValue("plannedStartAt", request.plannedStartAt())
            .addValue("plannedEndAt", request.plannedEndAt())
            .addValue("plannedMinutes", plannedMinutes)
            .addValue("dayType", dayType.name())
            .addValue("payRateMultiplier", dayType.multiplier())
            .addValue("calculationNote", submitTimeCalculationNote)
            .addValue("reason", request.reason().trim())
            .addValue("payrollMonth", payrollMonth)
            .addValue("requestedById", requestedById), Long.class);
        return id == null ? 0 : id;
    }

    public Optional<OvertimeRequestDto> findById(long id) {
        return jdbc.query(baseSelect() + " WHERE o.overtime_request_id = :id",
            Map.of("id", id),
            this::mapRequest)
            .stream()
            .findFirst();
    }

    public List<OvertimeRequestDto> findRequests(OvertimeFilter filter) {
        StringBuilder sql = new StringBuilder(baseSelect()).append("""
             WHERE o.work_date BETWEEN :fromDate AND :toDate
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("fromDate", filter.fromDate())
            .addValue("toDate", filter.toDate());

        if (filter.employeeId() != null) {
            sql.append(" AND o.employee_id = :employeeId");
            params.addValue("employeeId", filter.employeeId());
        }
        if (filter.managerEmployeeId() != null) {
            StringBuilder scope = new StringBuilder(
                // Own requests, plus the whole ฝ่าย for a ผู้จัดการ. reports_to is deliberately not
                // a disjunct here: it no longer grants approval rights (see
                // OvertimeService.managesEmployee), and a list that showed rows the viewer cannot
                // act on is how a reviewer ends up staring at a request with no buttons.
                " AND (o.employee_id = :managerEmployeeId");
            params.addValue("managerEmployeeId", filter.managerEmployeeId());
            if (filter.managerDivisionId() != null) {
                scope.append(" OR e.division_id = :managerDivisionId");
                params.addValue("managerDivisionId", filter.managerDivisionId());
            }
            scope.append(")");
            sql.append(scope);
        }
        if (filter.status() != null) {
            sql.append(" AND o.status = :status");
            params.addValue("status", filter.status().name());
        }

        sql.append(" ORDER BY o.work_date DESC, o.planned_start_at DESC, o.overtime_request_id DESC");
        return jdbc.query(sql.toString(), params, this::mapRequest);
    }

    public Optional<OvertimeAttendanceBounds> findAttendanceBounds(
            long employeeId,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd) {
        return jdbc.query("""
            SELECT min(punch_time) AS first_punch_at,
                   max(punch_time) AS last_punch_at
              FROM hr.attendance_punch
             WHERE employee_id = :employeeId
               AND punch_time BETWEEN :windowStart AND :windowEnd
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("windowStart", windowStart)
            .addValue("windowEnd", windowEnd),
            (rs, rowNum) -> {
                OffsetDateTime first = rs.getObject("first_punch_at", OffsetDateTime.class);
                OffsetDateTime last = rs.getObject("last_punch_at", OffsetDateTime.class);
                return first == null || last == null ? null : new OvertimeAttendanceBounds(first, last);
            })
            .stream()
            .filter(bounds -> bounds != null)
            .findFirst();
    }

    /**
     * The employee's division, needed to resolve their {@code WorkSchedule} for {@code
     * OvertimeService#suggestDayType}. Single-row twin of {@link #findDivisionIdsByEmployee} for
     * the submit/approve paths, which act on exactly one employee at a time -- mirrors {@code
     * AttendanceDailyRepository#findDivisionId}, do not reimplement the query differently here.
     */
    public Long findDivisionId(long employeeId) {
        List<Long> found = jdbc.query(
            "SELECT division_id FROM hr.employee WHERE employee_id = :employeeId",
            new MapSqlParameterSource("employeeId", employeeId),
            (rs, rowNum) -> nullableLong(rs, "division_id"));
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * The employee's department, needed alongside division to resolve their {@code WorkSchedule}.
     * Mirrors {@code AttendanceDailyRepository#findDepartmentId} -- see {@link #findDivisionId}'s
     * Javadoc for why this is a separate single-row method rather than folded into one query.
     */
    public Long findDepartmentId(long employeeId) {
        List<Long> found = jdbc.query(
            "SELECT department_id FROM hr.employee WHERE employee_id = :employeeId",
            new MapSqlParameterSource("employeeId", employeeId),
            (rs, rowNum) -> nullableLong(rs, "department_id"));
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Division per employee, in ONE query -- {@code OvertimeService#list}'s suggestion needs it for
     * every row, and a per-row {@link #findDivisionId} call there would be an N+1 (see that
     * method's own comment). Mirrors {@code AttendanceDailyRepository#findDivisionIdsByEmployee}
     * exactly; do not diverge the SQL between the two twins.
     */
    public Map<Long, Long> findDivisionIdsByEmployee() {
        Map<Long, Long> byEmployee = new HashMap<>();
        // Block-statement lambda, not an expression lambda -- Map.put's return value would
        // otherwise make this ambiguous between NamedParameterJdbcTemplate's
        // RowCallbackHandler and ResultSetExtractor<T> overloads (both accept a
        // SqlParameterSource + a lambda; only the void/statement shape picks RowCallbackHandler
        // unambiguously). Same shape AttendanceDailyRepository's twin already uses.
        jdbc.query(
            "SELECT employee_id, division_id FROM hr.employee",
            new MapSqlParameterSource(),
            rs -> { byEmployee.put(rs.getLong("employee_id"), nullableLong(rs, "division_id")); });
        return byEmployee;
    }

    /**
     * Department per employee, in ONE query -- the bulk twin of {@link #findDepartmentId}, same
     * reasoning and same SQL shape as {@link #findDivisionIdsByEmployee}. Mirrors {@code
     * AttendanceDailyRepository#findDepartmentIdsByEmployee}.
     */
    public Map<Long, Long> findDepartmentIdsByEmployee() {
        Map<Long, Long> byEmployee = new HashMap<>();
        // See findDivisionIdsByEmployee's comment on why this must stay a block-statement lambda.
        jdbc.query(
            "SELECT employee_id, department_id FROM hr.employee",
            new MapSqlParameterSource(),
            rs -> { byEmployee.put(rs.getLong("employee_id"), nullableLong(rs, "department_id")); });
        return byEmployee;
    }

    /**
     * The salary an overtime request should be priced from, resolved as of the work date rather
     * than at approval or payroll time. Overtime is paid at the rate in force when the work was
     * done, so this reads {@code hr.salary_history} in three steps, most authoritative first:
     *
     * <ol>
     *   <li>the latest row <em>effective on or before</em> the work date — its {@code new_amount}
     *       is the salary that was in force;</li>
     *   <li>otherwise the earliest row <em>effective after</em> the work date — its
     *       {@code old_amount} (เงินเก่า) is by definition the salary that preceded that change,
     *       i.e. the one in force on the work date. This is the case that matters for a backdated
     *       request filed after a raise: {@code current_salary} is already the new figure, and
     *       looking only backwards would silently pay the new rate for old work;</li>
     *   <li>otherwise {@code hr.employee.current_salary}, because the ETL-loaded history table does
     *       not cover every employee, and finally zero.</li>
     * </ol>
     *
     * <p>Ties on {@code effective_date} are broken by {@code salary_id} so the result is
     * deterministic when two changes share a date.
     */
    public BigDecimal findSalaryBasisAsOf(long employeeId, LocalDate workDate) {
        BigDecimal basis = jdbc.queryForObject("""
            SELECT COALESCE(
                (SELECT sh.new_amount
                   FROM hr.salary_history sh
                  WHERE sh.employee_id = :employeeId
                    AND sh.effective_date IS NOT NULL
                    AND sh.effective_date <= :workDate
                    AND sh.new_amount IS NOT NULL
                  ORDER BY sh.effective_date DESC, sh.salary_id DESC
                  LIMIT 1),
                (SELECT sh.old_amount
                   FROM hr.salary_history sh
                  WHERE sh.employee_id = :employeeId
                    AND sh.effective_date IS NOT NULL
                    AND sh.effective_date > :workDate
                    AND sh.old_amount IS NOT NULL
                  ORDER BY sh.effective_date ASC, sh.salary_id ASC
                  LIMIT 1),
                (SELECT e.current_salary FROM hr.employee e WHERE e.employee_id = :employeeId),
                0
            ) AS salary_basis
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("workDate", workDate), BigDecimal.class);
        return basis == null ? BigDecimal.ZERO : basis;
    }

    public int managerApprove(
            long id, Long reviewedById, OvertimeCalculation calculation, BigDecimal salaryBasis, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.overtime_request
               SET status = 'MANAGER_APPROVED',
                   day_type = :dayType,
                   pay_rate_multiplier = :payRateMultiplier,
                   actual_start_at = :actualStartAt,
                   actual_end_at = :actualEndAt,
                   actual_minutes = :actualMinutes,
                   payable_minutes = :payableMinutes,
                   calculation_note = :calculationNote,
                   salary_basis = :salaryBasis,
                   manager_approved_by = :reviewedById,
                   manager_approved_at = now(),
                   reviewed_by_id = :reviewedById,
                   reviewed_at = now(),
                   reviewer_note = :reviewerNote,
                   updated_at = now()
             WHERE overtime_request_id = :id
               AND status = 'SUBMITTED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            // Re-derived and frozen here, not carried over from whatever submit() stored -- see
            // OvertimeService#calculate's Javadoc. Same source of truth (OvertimeDayType), never
            // caller input.
            .addValue("dayType", calculation.dayType().name())
            .addValue("payRateMultiplier", calculation.dayType().multiplier())
            .addValue("actualStartAt", calculation.actualStartAt())
            .addValue("actualEndAt", calculation.actualEndAt())
            .addValue("actualMinutes", calculation.actualMinutes())
            .addValue("payableMinutes", calculation.payableMinutes())
            .addValue("calculationNote", calculation.calculationNote())
            .addValue("salaryBasis", salaryBasis)
            .addValue("reviewerNote", cleanNote(reviewerNote)));
    }

    /**
     * The manager-less route: SUBMITTED straight to APPROVED in one statement.
     *
     * <p>Writes the calculation and salary basis that {@link #managerApprove} would have written,
     * because payroll reads those off an APPROVED row regardless of which route produced it.
     *
     * <p>{@code manager_approved_by} / {@code manager_approved_at} are deliberately left NULL —
     * no manager approved this, and stamping the CEO into those columns would forge a review stage
     * that never happened. Those NULLs are what tell the two routes apart in the audit trail.
     */
    public int ceoDirectApprove(
            long id, Long reviewedById, OvertimeCalculation calculation, BigDecimal salaryBasis, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.overtime_request
               SET status = 'APPROVED',
                   day_type = :dayType,
                   pay_rate_multiplier = :payRateMultiplier,
                   actual_start_at = :actualStartAt,
                   actual_end_at = :actualEndAt,
                   actual_minutes = :actualMinutes,
                   payable_minutes = :payableMinutes,
                   calculation_note = :calculationNote,
                   salary_basis = :salaryBasis,
                   ceo_approved_by = :reviewedById,
                   ceo_approved_at = now(),
                   reviewed_by_id = :reviewedById,
                   reviewed_at = now(),
                   reviewer_note = :reviewerNote,
                   updated_at = now()
             WHERE overtime_request_id = :id
               AND status = 'SUBMITTED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            // See managerApprove's comment -- same re-derive-and-freeze treatment for the
            // manager-less route, which is this class's one-step equivalent of that same stage.
            .addValue("dayType", calculation.dayType().name())
            .addValue("payRateMultiplier", calculation.dayType().multiplier())
            .addValue("actualStartAt", calculation.actualStartAt())
            .addValue("actualEndAt", calculation.actualEndAt())
            .addValue("actualMinutes", calculation.actualMinutes())
            .addValue("payableMinutes", calculation.payableMinutes())
            .addValue("calculationNote", calculation.calculationNote())
            .addValue("salaryBasis", salaryBasis)
            .addValue("reviewerNote", cleanNote(reviewerNote)));
    }

    public int ceoApprove(long id, Long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.overtime_request
               SET status = 'APPROVED',
                   ceo_approved_by = :reviewedById,
                   ceo_approved_at = now(),
                   reviewed_by_id = :reviewedById,
                   reviewed_at = now(),
                   reviewer_note = COALESCE(CAST(:reviewerNote AS text), reviewer_note),
                   updated_at = now()
             WHERE overtime_request_id = :id
               AND status = 'MANAGER_APPROVED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("reviewerNote", cleanNote(reviewerNote)));
    }

    public int reject(long id, Long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.overtime_request
               SET status = 'REJECTED',
                   reviewed_by_id = :reviewedById,
                   reviewed_at = now(),
                   reviewer_note = :reviewerNote,
                   updated_at = now()
             WHERE overtime_request_id = :id
               AND status = 'SUBMITTED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("reviewerNote", cleanNote(reviewerNote)));
    }

    public int ceoReject(long id, Long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.overtime_request
               SET status = 'REJECTED',
                   reviewed_by_id = :reviewedById,
                   reviewed_at = now(),
                   reviewer_note = :reviewerNote,
                   updated_at = now()
             WHERE overtime_request_id = :id
               AND status = 'MANAGER_APPROVED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("reviewerNote", cleanNote(reviewerNote)));
    }

    public int cancel(long id, Long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.overtime_request
               SET status = 'CANCELLED',
                   reviewed_by_id = COALESCE(CAST(:reviewedById AS bigint), reviewed_by_id),
                   reviewed_at = CASE WHEN CAST(:reviewedById AS bigint) IS NULL THEN reviewed_at ELSE now() END,
                   reviewer_note = COALESCE(CAST(:reviewerNote AS text), reviewer_note),
                   cancelled_at = now(),
                   updated_at = now()
             WHERE overtime_request_id = :id
               AND status IN ('SUBMITTED', 'MANAGER_APPROVED', 'APPROVED')
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("reviewerNote", cleanNote(reviewerNote)));
    }

    private String baseSelect() {
        return """
            SELECT o.overtime_request_id,
                   o.employee_id,
                   e.employee_code,
                   concat_ws(' ', e.first_name_th, e.last_name_th) AS employee_name,
                   o.work_date,
                   o.planned_start_at,
                   o.planned_end_at,
                   o.planned_minutes,
                   o.day_type,
                   o.pay_rate_multiplier,
                   o.reason,
                   o.status,
                   o.actual_start_at,
                   o.actual_end_at,
                   o.actual_minutes,
                   o.payable_minutes,
                   o.calculation_note,
                   o.payroll_month,
                   o.requested_by_id,
                   concat_ws(' ', requested_by.first_name_th, requested_by.last_name_th) AS requested_by_name,
                   o.requested_at,
                   o.manager_approved_by,
                   concat_ws(' ', manager_approver.first_name_th, manager_approver.last_name_th) AS manager_approved_by_name,
                   o.manager_approved_at,
                   o.ceo_approved_by,
                   concat_ws(' ', ceo_approver.first_name_th, ceo_approver.last_name_th) AS ceo_approved_by_name,
                   o.ceo_approved_at,
                   o.reviewed_by_id,
                   concat_ws(' ', reviewed_by.first_name_th, reviewed_by.last_name_th) AS reviewed_by_name,
                   o.reviewed_at,
                   o.reviewer_note,
                   o.cancelled_at,
                   e.reports_to_employee_id,
                   concat_ws(' ', manager.first_name_th, manager.last_name_th) AS manager_name,
                   """
            // Projected per row so the UI can tell a manager-less request apart without a second
            // round trip. Same expression the approve/reject gate uses, so the button the CEO sees
            // and the gate the server enforces cannot disagree.
            + ManagerApproverRepository.hasManagerApproverSql("e") + " AS has_manager_approver,"
            // feat/pending-approver-info: read-only "who this is waiting on" names -- built from the
            // SAME PEER_IS_MANAGER_APPROVER predicate has_manager_approver above already uses (see
            // ManagerApproverRepository#managerApproverSingleNameSql's Javadoc), plus the generic
            // single-active-ceo lookup PendingApproverSql provides. Neither is an authorization
            // decision.
            + ManagerApproverRepository.managerApproverSingleNameSql("e") + " AS division_manager_single_name,"
            + PendingApproverSql.SINGLE_ACTIVE_CEO_NAME_SQL + " AS ceo_single_name,"
            + """

                   o.created_at,
                   o.updated_at
              FROM hr.overtime_request o
              JOIN hr.employee e ON e.employee_id = o.employee_id
              LEFT JOIN hr.employee requested_by ON requested_by.employee_id = o.requested_by_id
              LEFT JOIN hr.employee manager_approver ON manager_approver.employee_id = o.manager_approved_by
              LEFT JOIN hr.employee ceo_approver ON ceo_approver.employee_id = o.ceo_approved_by
              LEFT JOIN hr.employee reviewed_by ON reviewed_by.employee_id = o.reviewed_by_id
              LEFT JOIN hr.employee manager ON manager.employee_id = e.reports_to_employee_id
            """;
    }

    private OvertimeRequestDto mapRequest(ResultSet rs, int rowNum) throws SQLException {
        return new OvertimeRequestDto(
            rs.getLong("overtime_request_id"),
            rs.getLong("employee_id"),
            rs.getString("employee_code"),
            rs.getString("employee_name"),
            rs.getObject("work_date", LocalDate.class),
            rs.getObject("planned_start_at", OffsetDateTime.class),
            rs.getObject("planned_end_at", OffsetDateTime.class),
            rs.getInt("planned_minutes"),
            rs.getString("day_type"),
            rs.getObject("pay_rate_multiplier", BigDecimal.class),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getObject("actual_start_at", OffsetDateTime.class),
            rs.getObject("actual_end_at", OffsetDateTime.class),
            rs.getInt("actual_minutes"),
            rs.getInt("payable_minutes"),
            rs.getString("calculation_note"),
            rs.getObject("payroll_month", LocalDate.class),
            nullableLong(rs, "requested_by_id"),
            blankToNull(rs.getString("requested_by_name")),
            rs.getObject("requested_at", OffsetDateTime.class),
            nullableLong(rs, "manager_approved_by"),
            blankToNull(rs.getString("manager_approved_by_name")),
            rs.getObject("manager_approved_at", OffsetDateTime.class),
            nullableLong(rs, "ceo_approved_by"),
            blankToNull(rs.getString("ceo_approved_by_name")),
            rs.getObject("ceo_approved_at", OffsetDateTime.class),
            nullableLong(rs, "reviewed_by_id"),
            blankToNull(rs.getString("reviewed_by_name")),
            rs.getObject("reviewed_at", OffsetDateTime.class),
            rs.getString("reviewer_note"),
            rs.getObject("cancelled_at", OffsetDateTime.class),
            nullableLong(rs, "reports_to_employee_id"),
            blankToNull(rs.getString("manager_name")),
            rs.getBoolean("has_manager_approver"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            resolvePendingApproverRole(rs),
            resolvePendingApproverName(rs),
            // feat/ot-nonworkday-rate-suggestion: NOT computed here on purpose. This is a plain SQL
            // row mapper with no WorkScheduleResolver, and the suggestion needs one (see
            // OvertimeService#suggestDayType). OvertimeService reconstructs every DTO this
            // repository returns with the real value attached before it reaches a controller --
            // see OvertimeService#withSuggestedDayType / #requireRequest / #attachSuggestions. A
            // caller reading this field directly off a bare OvertimeRepository result (bypassing
            // OvertimeService) would see null, never a wrong answer.
            null
        );
    }

    /**
     * feat/pending-approver-info: mirrors {@code OvertimeService#approve}'s own
     * SUBMITTED/MANAGER_APPROVED branching exactly -- SUBMITTED with a manager stage routes to
     * "manager"; SUBMITTED with none, or MANAGER_APPROVED, routes to "ceo" (the same {@code
     * hasManagerApprover} column the approve endpoint's button visibility already keys off). Any
     * other status (APPROVED/REJECTED/CANCELLED) has nobody left to wait on.
     */
    String resolvePendingApproverRole(ResultSet rs) throws SQLException {
        String status = rs.getString("status");
        if ("SUBMITTED".equals(status)) {
            return rs.getBoolean("has_manager_approver") ? "manager" : "ceo";
        }
        if ("MANAGER_APPROVED".equals(status)) {
            return "ceo";
        }
        return null;
    }

    /**
     * feat/pending-approver-info: the paired display name -- {@code null} whenever the resolved
     * role's candidate set is not exactly one active person (see {@code
     * ManagerApproverRepository#managerApproverSingleNameSql} and {@code
     * PendingApproverSql#SINGLE_ACTIVE_CEO_NAME_SQL}'s Javadoc). Deliberate ambiguity handling, not
     * a bug -- see this feature's PR body.
     */
    String resolvePendingApproverName(ResultSet rs) throws SQLException {
        String role = resolvePendingApproverRole(rs);
        if ("manager".equals(role)) {
            return blankToNull(rs.getString("division_manager_single_name"));
        }
        if ("ceo".equals(role)) {
            return blankToNull(rs.getString("ceo_single_name"));
        }
        return null;
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String cleanNote(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
