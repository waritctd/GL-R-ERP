package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public List<LeaveTypeDto> findLeaveTypes() {
        return jdbc.query("""
            SELECT leave_type_code, name_th, name_en, annual_quota_days, requires_attachment
              FROM hr.leave_type
             WHERE is_active = TRUE
             ORDER BY leave_type_code
            """, this::mapLeaveType);
    }

    public Optional<LeaveTypeDto> findLeaveType(String code) {
        return jdbc.query("""
            SELECT leave_type_code, name_th, name_en, annual_quota_days, requires_attachment
              FROM hr.leave_type
             WHERE leave_type_code = :code
               AND is_active = TRUE
            """, Map.of("code", code), this::mapLeaveType)
            .stream()
            .findFirst();
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

    public long create(
            long employeeId,
            Long requestedById,
            SubmitLeaveRequest request,
            BigDecimal totalDays,
            int quotaYear,
            LeaveStatus status,
            BigDecimal quotaRemainingBefore,
            BigDecimal quotaRemainingAfter,
            String systemNote) {
        Long id = jdbc.queryForObject("""
            INSERT INTO hr.leave_request (
                employee_id, leave_type_code, start_date, end_date, total_days, quota_year,
                reason, status, quota_remaining_before,
                quota_remaining_after, system_note, requested_by_id
            )
            VALUES (
                :employeeId, :leaveTypeCode, :startDate, :endDate, :totalDays, :quotaYear,
                :reason, :status, :quotaRemainingBefore,
                :quotaRemainingAfter, :systemNote, :requestedById
            )
            RETURNING leave_request_id
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("leaveTypeCode", request.leaveTypeCode().trim().toUpperCase())
            .addValue("startDate", request.startDate())
            .addValue("endDate", request.endDate())
            .addValue("totalDays", totalDays)
            .addValue("quotaYear", quotaYear)
            .addValue("reason", request.reason().trim())
            .addValue("status", status.name())
            .addValue("quotaRemainingBefore", quotaRemainingBefore)
            .addValue("quotaRemainingAfter", quotaRemainingAfter)
            .addValue("systemNote", systemNote)
            .addValue("requestedById", requestedById), Long.class);
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
                   lr.total_days,
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
                   lr.updated_at
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
            rs.getBoolean("requires_attachment")
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
            rs.getObject("total_days", BigDecimal.class),
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

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
