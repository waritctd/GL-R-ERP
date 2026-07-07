package th.co.glr.hr.overtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

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
            sql.append(" AND (e.employee_id = :managerEmployeeId OR e.reports_to_employee_id = :managerEmployeeId");
            if (managerDivisionId != null) {
                sql.append(" OR e.division_id = :managerDivisionId");
            }
            sql.append(")");
        }
        sql.append(" ORDER BY e.employee_code");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            long employeeId = rs.getLong("employee_id");
            Long reportsTo = nullableLong(rs, "reports_to_employee_id");
            Long divisionId = nullableLong(rs, "division_id");
            boolean self = managerEmployeeId != null && employeeId == managerEmployeeId;
            boolean directReport = (managerEmployeeId != null && managerEmployeeId.equals(reportsTo))
                || (managerDivisionId != null && managerDivisionId.equals(divisionId) && !self);
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

    public long create(
            long employeeId,
            Long requestedById,
            SubmitOvertimeRequest request,
            int plannedMinutes,
            OvertimeDayType dayType,
            LocalDate payrollMonth) {
        Long id = jdbc.queryForObject("""
            INSERT INTO hr.overtime_request (
                employee_id, work_date, planned_start_at, planned_end_at, planned_minutes,
                day_type, pay_rate_multiplier, reason, payroll_month, requested_by_id
            )
            VALUES (
                :employeeId, :workDate, :plannedStartAt, :plannedEndAt, :plannedMinutes,
                :dayType, :payRateMultiplier, :reason, :payrollMonth, :requestedById
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
                " AND (o.employee_id = :managerEmployeeId OR e.reports_to_employee_id = :managerEmployeeId");
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

    public int approve(long id, Long reviewedById, OvertimeCalculation calculation, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.overtime_request
               SET status = 'APPROVED',
                   actual_start_at = :actualStartAt,
                   actual_end_at = :actualEndAt,
                   actual_minutes = :actualMinutes,
                   payable_minutes = :payableMinutes,
                   calculation_note = :calculationNote,
                   reviewed_by_id = :reviewedById,
                   reviewed_at = now(),
                   reviewer_note = :reviewerNote,
                   updated_at = now()
             WHERE overtime_request_id = :id
               AND status = 'SUBMITTED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("actualStartAt", calculation.actualStartAt())
            .addValue("actualEndAt", calculation.actualEndAt())
            .addValue("actualMinutes", calculation.actualMinutes())
            .addValue("payableMinutes", calculation.payableMinutes())
            .addValue("calculationNote", calculation.calculationNote())
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
               AND status IN ('SUBMITTED', 'APPROVED')
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
                   o.reviewed_by_id,
                   concat_ws(' ', reviewed_by.first_name_th, reviewed_by.last_name_th) AS reviewed_by_name,
                   o.reviewed_at,
                   o.reviewer_note,
                   o.cancelled_at,
                   e.reports_to_employee_id,
                   concat_ws(' ', manager.first_name_th, manager.last_name_th) AS manager_name,
                   o.created_at,
                   o.updated_at
              FROM hr.overtime_request o
              JOIN hr.employee e ON e.employee_id = o.employee_id
              LEFT JOIN hr.employee requested_by ON requested_by.employee_id = o.requested_by_id
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
            nullableLong(rs, "reviewed_by_id"),
            blankToNull(rs.getString("reviewed_by_name")),
            rs.getObject("reviewed_at", OffsetDateTime.class),
            rs.getString("reviewer_note"),
            rs.getObject("cancelled_at", OffsetDateTime.class),
            nullableLong(rs, "reports_to_employee_id"),
            blankToNull(rs.getString("manager_name")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
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
