package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LeaveRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public LeaveRepository(NamedParameterJdbcTemplate jdbc) {
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
        once_per_employment
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

    public BigDecimal sumUsedDays(long employeeId, String leaveTypeCode, int quotaYear, Collection<LeaveStatus> statuses) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(sum(total_days), 0)::numeric(5,2)
              FROM hr.leave_request
             WHERE employee_id = :employeeId
               AND leave_type_code = :leaveTypeCode
               AND quota_year = :quotaYear
               AND status IN (:statuses)
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
     */
    public BigDecimal sumPaidDays(long employeeId, String leaveTypeCode, int quotaYear, Collection<LeaveStatus> statuses) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(sum(paid_days), 0)::numeric(5,2)
              FROM hr.leave_request
             WHERE employee_id = :employeeId
               AND leave_type_code = :leaveTypeCode
               AND quota_year = :quotaYear
               AND status IN (:statuses)
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("leaveTypeCode", leaveTypeCode)
            .addValue("quotaYear", quotaYear)
            .addValue("statuses", statuses.stream().map(LeaveStatus::name).toList()),
            BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Leave -&gt; payroll unpaid-day deduction (2026-07-23): per employee, the unpaid WORKING days of
     * APPROVED leave that fall inside payroll month {@code monthStart} (a first-of-month date). Reads
     * every APPROVED, unpaid-day-bearing request whose date range overlaps the month, then attributes
     * days to the month in Java via {@link LeaveDayMath#unpaidWorkingDaysByMonth} so a leave spanning
     * two calendar months splits correctly -- only the unpaid working days that actually fall in this
     * month count here, not the request's full unpaid_days total. Consumed by {@code
     * PayrollService#suggestedInputs} (additive; never touches {@code preview()}/{@code process()}).
     */
    public Map<Long, BigDecimal> findUnpaidLeaveDaysByEmployeeForMonth(LocalDate monthStart) {
        LocalDate monthEndInclusive = monthStart.plusMonths(1).minusDays(1);
        List<UnpaidLeaveSpan> spans = jdbc.query("""
            SELECT employee_id, start_date, end_date, paid_days, total_days
              FROM hr.leave_request
             WHERE status = 'APPROVED'
               AND unpaid_days > 0
               AND start_date <= :monthEndInclusive
               AND end_date >= :monthStart
            """,
            new MapSqlParameterSource()
                .addValue("monthStart", monthStart)
                .addValue("monthEndInclusive", monthEndInclusive),
            (rs, rowNum) -> new UnpaidLeaveSpan(
                rs.getLong("employee_id"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getObject("paid_days", BigDecimal.class),
                rs.getObject("total_days", BigDecimal.class)
            ));

        Map<Long, BigDecimal> byEmployee = new LinkedHashMap<>();
        for (UnpaidLeaveSpan span : spans) {
            // Sub-day leave (2026-07-25): reads the BigDecimal LeaveDayMath computes directly --
            // no more setScale(0, DOWN) floor, which used to discard a sub-day fraction entirely.
            BigDecimal unpaidInMonth = LeaveDayMath
                .unpaidWorkingDaysByMonth(span.startDate(), span.endDate(), span.paidDays(), span.totalDays())
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
        long employeeId, LocalDate startDate, LocalDate endDate, BigDecimal paidDays, BigDecimal totalDays) {
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
                contact_house_no, contact_subdistrict, contact_district, contact_province, contact_phone
            )
            VALUES (
                :employeeId, :leaveTypeCode, :startDate, :endDate, :startTime, :endTime,
                :totalDays, :paidDays, :unpaidDays,
                :quotaYear, :reason, :status, :quotaRemainingBefore,
                :quotaRemainingAfter, :systemNote, :requestedById,
                :contactHouseNo, :contactSubdistrict, :contactDistrict, :contactProvince, :contactPhone
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
            .addValue("contactPhone", contactPhone), Long.class);
        return id == null ? 0 : id;
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
                   lr.contact_phone
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
            rs.getBoolean("once_per_employment")
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
            rs.getString("contact_phone")
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
