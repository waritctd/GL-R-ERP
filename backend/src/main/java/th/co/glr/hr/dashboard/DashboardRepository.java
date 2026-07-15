package th.co.glr.hr.dashboard;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public DashboardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public HeadcountSummaryDto headcount(DashboardQueryScope scope) {
        if (scope.isNone() || scope.isSelf()) {
            return HeadcountSummaryDto.empty(scope.label());
        }

        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = " WHERE 1 = 1" + whereEmployeeScope("e", scope, params);
        HeadcountTotals totals = jdbc.queryForObject("""
            SELECT COUNT(*) FILTER (WHERE e.is_active = TRUE) AS active,
                   COUNT(*) FILTER (WHERE e.is_active = FALSE) AS inactive,
                   COUNT(*) AS total
              FROM hr.employee e
            """ + where,
            params,
            (rs, rowNum) -> new HeadcountTotals(
                rs.getLong("active"),
                rs.getLong("inactive"),
                rs.getLong("total")
            ));

        List<DivisionHeadcountDto> byDivision = jdbc.query("""
            SELECT e.division_id,
                   d.source_code AS division_code,
                   COALESCE(d.name_th, 'Unassigned') AS division_name,
                   COUNT(*) FILTER (WHERE e.is_active = TRUE) AS active,
                   COUNT(*) FILTER (WHERE e.is_active = FALSE) AS inactive,
                   COUNT(*) AS total
              FROM hr.employee e
              LEFT JOIN hr.division d ON d.division_id = e.division_id
            """ + where + """
             GROUP BY e.division_id, d.source_code, d.name_th
             ORDER BY COALESCE(d.name_th, 'Unassigned')
            """,
            params,
            (rs, rowNum) -> new DivisionHeadcountDto(
                nullableLong(rs.getObject("division_id")),
                rs.getString("division_code"),
                rs.getString("division_name"),
                rs.getLong("active"),
                rs.getLong("inactive"),
                rs.getLong("total")
            ));

        return new HeadcountSummaryDto(
            scope.label(),
            totals == null ? 0L : totals.active(),
            totals == null ? 0L : totals.inactive(),
            totals == null ? 0L : totals.total(),
            byDivision
        );
    }

    public PendingApprovalsSummaryDto pendingApprovals(
            DashboardQueryScope employeeScope,
            DashboardPendingVisibility visibility,
            DashboardQueryScope commissionScope,
            DashboardQueryScope ticketScope) {
        long profileRequests = visibility.profileRequests()
            ? countProfileRequests(employeeScope)
            : 0;
        long overtime = visibility.overtime()
            ? countOvertime(employeeScope)
            : 0;
        long leave = visibility.leave()
            ? countLeave(employeeScope)
            : 0;
        long commissions = visibility.commissions()
            ? countCommissions(commissionScope)
            : 0;
        long tickets = visibility.tickets()
            ? countPendingTickets(ticketScope)
            : 0;
        return PendingApprovalsSummaryDto.of(
            employeeScope.label(),
            profileRequests,
            overtime,
            leave,
            commissions,
            tickets
        );
    }

    public AttendanceSummaryDto attendance(DashboardQueryScope scope, LocalDate today, LocalDate monthStart) {
        if (scope.isNone()) {
            return AttendanceSummaryDto.empty(scope.label());
        }
        if (scope.isSelf()) {
            return selfAttendance(scope, today, monthStart);
        }
        return broadAttendance(scope, today, monthStart);
    }

    public TicketSummaryDto tickets(
            DashboardQueryScope scope,
            LocalDate monthStart,
            OffsetDateTime overdueBefore) {
        if (scope.isNone()) {
            return TicketSummaryDto.empty(scope.label());
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("monthStart", monthStart)
            .addValue("overdueBefore", overdueBefore);
        String from = " FROM sales.ticket t";
        String where = whereTicketScope(scope, params);
        if (scope.isDivision()) {
            from += " JOIN hr.employee e ON e.employee_id = t.created_by";
        }

        TicketSummaryDto summary = jdbc.queryForObject("""
            SELECT COUNT(*) FILTER (WHERE t.status = 'draft') AS draft,
                   COUNT(*) FILTER (WHERE t.status = 'submitted') AS submitted,
                   COUNT(*) FILTER (WHERE t.status = 'in_review') AS in_review,
                   COUNT(*) FILTER (WHERE t.status = 'price_proposed') AS price_proposed,
                   COUNT(*) FILTER (WHERE t.status = 'approved') AS approved,
                   COUNT(*) FILTER (WHERE t.status = 'quotation_issued') AS quotation_issued,
                   COUNT(*) FILTER (WHERE t.status = 'document_issued') AS document_issued,
                   COUNT(*) FILTER (WHERE t.status = 'closed') AS closed,
                   COUNT(*) FILTER (WHERE t.status = 'cancelled') AS cancelled,
                   COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE t.status NOT IN ('closed','cancelled')) AS total_open,
                   COUNT(*) FILTER (WHERE t.status = 'closed' AND t.closed_at >= :monthStart) AS closed_this_month,
                   COUNT(*) FILTER (WHERE t.status = 'cancelled' AND t.updated_at >= :monthStart) AS cancelled_this_month,
                   COUNT(*) FILTER (
                       WHERE t.status NOT IN ('closed','cancelled','draft')
                         AND t.created_at < :overdueBefore
                   ) AS overdue_over_3days
            """ + from + where,
            params,
            (rs, rowNum) -> new TicketSummaryDto(
                scope.label(),
                rs.getLong("draft"),
                rs.getLong("submitted"),
                rs.getLong("in_review"),
                rs.getLong("price_proposed"),
                rs.getLong("approved"),
                rs.getLong("quotation_issued"),
                rs.getLong("document_issued"),
                rs.getLong("closed"),
                rs.getLong("cancelled"),
                rs.getLong("total"),
                rs.getLong("total_open"),
                rs.getLong("closed_this_month"),
                rs.getLong("cancelled_this_month"),
                rs.getLong("overdue_over_3days")
            ));
        return summary == null ? TicketSummaryDto.empty(scope.label()) : summary;
    }

    public NotificationSummaryDto notifications(long employeeId) {
        NotificationSummaryDto summary = jdbc.queryForObject("""
            SELECT COUNT(*) FILTER (WHERE is_read = FALSE) AS unread,
                   COUNT(*) FILTER (WHERE is_read = TRUE) AS read,
                   COUNT(*) AS total
              FROM hr.notification
             WHERE employee_id = :employeeId
            """,
            Map.of("employeeId", employeeId),
            (rs, rowNum) -> new NotificationSummaryDto(
                rs.getLong("unread"),
                rs.getLong("read"),
                rs.getLong("total")
            ));
        return summary == null ? NotificationSummaryDto.empty() : summary;
    }

    public Optional<Long> latestPayrollPeriodId(long employeeId) {
        try {
            Long periodId = jdbc.queryForObject("""
                SELECT pp.period_id
                  FROM hr.payroll_period pp
                  JOIN hr.payroll_line pl ON pl.period_id = pp.period_id
                 WHERE pl.employee_id = :employeeId
                   AND pp.status <> 'VOID'
                 ORDER BY pp.payroll_month DESC, pp.processed_at DESC NULLS LAST, pp.period_id DESC
                 LIMIT 1
                """,
                Map.of("employeeId", employeeId),
                Long.class);
            return Optional.ofNullable(periodId);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private AttendanceSummaryDto broadAttendance(DashboardQueryScope scope, LocalDate today, LocalDate monthStart) {
        MapSqlParameterSource todayParams = new MapSqlParameterSource()
            .addValue("today", today);
        String todayWhere = whereAttendanceScope("e", scope, todayParams);
        AttendanceDailyTotals todayTotals = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT ad.employee_id) FILTER (
                       WHERE ad.is_absent = FALSE
                         AND (ad.check_in IS NOT NULL OR ad.punch_count > 0)
                   ) AS today_present,
                   COUNT(*) FILTER (WHERE ad.late_minutes > 0) AS late_today,
                   COUNT(*) FILTER (
                       WHERE ad.check_in IS NOT NULL
                         AND ad.check_out IS NULL
                         AND ad.is_absent = FALSE
                   ) AS missing_checkout,
                   COALESCE(SUM(ad.punch_count), 0) AS punch_count_today
              FROM hr.attendance_daily ad
              JOIN hr.employee e ON e.employee_id = ad.employee_id
             WHERE ad.work_date = :today
            """ + todayWhere,
            todayParams,
            (rs, rowNum) -> new AttendanceDailyTotals(
                rs.getLong("today_present"),
                rs.getLong("late_today"),
                rs.getLong("missing_checkout"),
                rs.getLong("punch_count_today")
            ));

        long monthlyAttendanceDays = countMonthlyAttendanceDays(scope, monthStart, today);
        AttendanceDailyTotals safeTotals = todayTotals == null
            ? new AttendanceDailyTotals(0, 0, 0, 0)
            : todayTotals;
        return new AttendanceSummaryDto(
            scope.label(),
            safeTotals.todayPresent(),
            safeTotals.lateToday(),
            safeTotals.missingCheckout(),
            safeTotals.punchCountToday(),
            monthlyAttendanceDays,
            null,
            null,
            null,
            null
        );
    }

    private AttendanceSummaryDto selfAttendance(DashboardQueryScope scope, LocalDate today, LocalDate monthStart) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("employeeId", scope.employeeId())
            .addValue("today", today);
        List<AttendanceDailyRow> rows = jdbc.query("""
            SELECT check_in,
                   check_out,
                   late_minutes,
                   punch_count,
                   is_absent
              FROM hr.attendance_daily
             WHERE employee_id = :employeeId
               AND work_date = :today
            """,
            params,
            (rs, rowNum) -> new AttendanceDailyRow(
                rs.getObject("check_in", OffsetDateTime.class),
                rs.getObject("check_out", OffsetDateTime.class),
                rs.getInt("late_minutes"),
                rs.getInt("punch_count"),
                rs.getBoolean("is_absent")
            ));

        long monthlyAttendanceDays = countMonthlyAttendanceDays(scope, monthStart, today);
        AttendanceDailyRow row = rows.stream().findFirst().orElse(null);
        if (row == null) {
            return new AttendanceSummaryDto(
                scope.label(), null, null, null, null, monthlyAttendanceDays,
                "NO_RECORD", null, null, null);
        }
        String status = row.isAbsent()
            ? "ABSENT"
            : row.checkIn() != null || row.punchCount() > 0 ? "PRESENT" : "NO_RECORD";
        return new AttendanceSummaryDto(
            scope.label(),
            null,
            null,
            null,
            null,
            monthlyAttendanceDays,
            status,
            row.checkIn(),
            row.checkOut(),
            row.lateMinutes()
        );
    }

    private long countMonthlyAttendanceDays(DashboardQueryScope scope, LocalDate monthStart, LocalDate today) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("monthStart", monthStart)
            .addValue("today", today);
        String where = whereAttendanceScope("e", scope, params);
        Long count = jdbc.queryForObject("""
            SELECT COUNT(*) AS monthly_attendance_days
              FROM hr.attendance_daily ad
              JOIN hr.employee e ON e.employee_id = ad.employee_id
             WHERE ad.work_date BETWEEN :monthStart AND :today
               AND ad.is_absent = FALSE
               AND (ad.check_in IS NOT NULL OR ad.punch_count > 0)
            """ + where,
            params,
            Long.class);
        return count == null ? 0 : count;
    }

    private long countProfileRequests(DashboardQueryScope scope) {
        return countEmployeeScoped("""
            SELECT COUNT(*)
              FROM hr.profile_change_request r
              JOIN hr.employee e ON e.employee_id = r.employee_id
             WHERE r.status = 'pending'
            """, scope);
    }

    private long countOvertime(DashboardQueryScope scope) {
        return countEmployeeScoped("""
            SELECT COUNT(*)
              FROM hr.overtime_request r
              JOIN hr.employee e ON e.employee_id = r.employee_id
             WHERE r.status = 'SUBMITTED'
            """, scope);
    }

    private long countLeave(DashboardQueryScope scope) {
        return countEmployeeScoped("""
            SELECT COUNT(*)
              FROM hr.leave_request r
              JOIN hr.employee e ON e.employee_id = r.employee_id
             WHERE r.status = 'SUBMITTED'
            """, scope);
    }

    private long countCommissions(DashboardQueryScope scope) {
        return countEmployeeScoped("""
            SELECT COUNT(*)
              FROM sales.commission_record r
              JOIN hr.employee e ON e.employee_id = r.sales_rep_id
             WHERE r.status IN ('SUBMITTED', 'MANAGER_APPROVED')
            """, scope);
    }

    private long countEmployeeScoped(String sql, DashboardQueryScope scope) {
        if (scope.isNone()) {
            return 0;
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long count = jdbc.queryForObject(sql + whereEmployeeScope("e", scope, params), params, Long.class);
        return count == null ? 0 : count;
    }

    private long countPendingTickets(DashboardQueryScope scope) {
        if (scope.isNone()) {
            return 0;
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        String from = " FROM sales.ticket t";
        String where = whereTicketScope(scope, params);
        if (scope.isDivision()) {
            from += " JOIN hr.employee e ON e.employee_id = t.created_by";
        }
        Long count = jdbc.queryForObject("""
            SELECT COUNT(*)
            """ + from + where + """
              AND t.status IN ('submitted', 'in_review', 'price_proposed')
            """, params, Long.class);
        return count == null ? 0 : count;
    }

    private String whereEmployeeScope(String employeeAlias, DashboardQueryScope scope, MapSqlParameterSource params) {
        if (scope.isAll()) {
            return "";
        }
        if (scope.isDivision()) {
            params.addValue("divisionId", scope.divisionId());
            return " AND " + employeeAlias + ".division_id = :divisionId";
        }
        if (scope.isSelf()) {
            params.addValue("employeeId", scope.employeeId());
            return " AND " + employeeAlias + ".employee_id = :employeeId";
        }
        return " AND 1 = 0";
    }

    private String whereAttendanceScope(String employeeAlias, DashboardQueryScope scope, MapSqlParameterSource params) {
        return whereEmployeeScope(employeeAlias, scope, params);
    }

    private String whereTicketScope(DashboardQueryScope scope, MapSqlParameterSource params) {
        if (scope.isAll()) {
            return " WHERE 1 = 1";
        }
        if (scope.isDivision()) {
            params.addValue("divisionId", scope.divisionId());
            return " WHERE e.division_id = :divisionId";
        }
        if (scope.isSelf()) {
            params.addValue("employeeId", scope.employeeId());
            return " WHERE t.created_by = :employeeId";
        }
        return " WHERE 1 = 0";
    }

    private Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }

    private record HeadcountTotals(long active, long inactive, long total) {
    }

    private record AttendanceDailyTotals(
        long todayPresent,
        long lateToday,
        long missingCheckout,
        long punchCountToday
    ) {
    }

    private record AttendanceDailyRow(
        OffsetDateTime checkIn,
        OffsetDateTime checkOut,
        int lateMinutes,
        int punchCount,
        boolean isAbsent
    ) {
    }
}
