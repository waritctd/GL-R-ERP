package th.co.glr.hr.attendance.correction;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AttendanceCorrectionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AttendanceCorrectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean employeeExists(long employeeId) {
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM hr.employee WHERE employee_id = :employeeId AND is_active = TRUE)",
            Map.of("employeeId", employeeId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * True when {@code employeeId} already has an OPEN (SUBMITTED) correction request for
     * {@code workDate} -- a friendlier pre-check ahead of the DB-level
     * {@code ux_attendance_correction_one_open_per_employee_day} race backstop (see
     * {@link AttendanceCorrectionService#submit}'s {@code DuplicateKeyException} handling for the
     * last-resort case where two submissions genuinely race).
     */
    public boolean hasOpenRequest(long employeeId, LocalDate workDate) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS(
                SELECT 1 FROM hr.attendance_correction_request
                 WHERE employee_id = :employeeId AND work_date = :workDate AND status = 'SUBMITTED'
            )
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("workDate", workDate), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** Mirrors {@code OvertimeRepository#findCeoApproverEmployeeIds} — the submit-notification target. */
    public List<Long> findCeoApproverEmployeeIds() {
        return jdbc.query("""
            SELECT e.employee_id
              FROM hr.employee e
              JOIN hr.division d ON d.division_id = e.division_id
             WHERE e.is_active = TRUE
               AND (d.source_code ILIKE 'MD%' OR d.source_code ILIKE 'MN%')
             ORDER BY e.employee_id
            """, Map.of(), (rs, rowNum) -> rs.getLong("employee_id"));
    }

    public long create(
            long employeeId,
            long requestedById,
            SubmitAttendanceCorrectionRequest request) {
        Long id = jdbc.queryForObject("""
            INSERT INTO hr.attendance_correction_request (
                employee_id, work_date, correction_type,
                requested_check_in, requested_check_out, reason, requested_by_id
            )
            VALUES (
                :employeeId, :workDate, :correctionType,
                :requestedCheckIn, :requestedCheckOut, :reason, :requestedById
            )
            RETURNING request_id
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("workDate", request.workDate())
            .addValue("correctionType", request.correctionType().name())
            .addValue("requestedCheckIn", request.requestedCheckIn())
            .addValue("requestedCheckOut", request.requestedCheckOut())
            .addValue("reason", request.reason().trim())
            .addValue("requestedById", requestedById), Long.class);
        return id == null ? 0 : id;
    }

    public Optional<AttendanceCorrectionRequestDto> findById(long id) {
        return jdbc.query(baseSelect() + " WHERE c.request_id = :id",
            Map.of("id", id),
            this::mapRequest)
            .stream()
            .findFirst();
    }

    public List<AttendanceCorrectionRequestDto> findRequests(AttendanceCorrectionFilter filter) {
        StringBuilder sql = new StringBuilder(baseSelect()).append(" WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (!filter.viewAll()) {
            sql.append(" AND c.employee_id = :employeeId");
            params.addValue("employeeId", filter.employeeId());
        } else if (filter.employeeId() != null) {
            sql.append(" AND c.employee_id = :employeeId");
            params.addValue("employeeId", filter.employeeId());
        }
        if (filter.status() != null) {
            sql.append(" AND c.status = :status");
            params.addValue("status", filter.status().name());
        }

        sql.append(" ORDER BY c.work_date DESC, c.request_id DESC");
        return jdbc.query(sql.toString(), params, this::mapRequest);
    }

    public int approve(long id, long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.attendance_correction_request
               SET status = 'APPROVED',
                   reviewed_by_id = :reviewedById,
                   reviewed_at = now(),
                   reviewer_note = :reviewerNote,
                   updated_at = now()
             WHERE request_id = :id
               AND status = 'SUBMITTED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("reviewerNote", cleanNote(reviewerNote)));
    }

    public int reject(long id, long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.attendance_correction_request
               SET status = 'REJECTED',
                   reviewed_by_id = :reviewedById,
                   reviewed_at = now(),
                   reviewer_note = :reviewerNote,
                   updated_at = now()
             WHERE request_id = :id
               AND status = 'SUBMITTED'
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("reviewedById", reviewedById)
            .addValue("reviewerNote", cleanNote(reviewerNote)));
    }

    public int cancel(long id) {
        return jdbc.update("""
            UPDATE hr.attendance_correction_request
               SET status = 'CANCELLED',
                   cancelled_at = now(),
                   updated_at = now()
             WHERE request_id = :id
               AND status = 'SUBMITTED'
            """, Map.of("id", id));
    }

    // --- attendance_punch write (approve-only; see AttendanceCorrectionService#approve) ----------

    /**
     * The badge_code an HR-corrected punch should carry. {@code hr.employee.badge_card_no} is
     * nullable (V1) -- an employee who was never issued a physical badge (or whose card is not yet
     * resolved) still needs a valid, non-blank {@code badge_code} to satisfy
     * {@code chk_attendance_punch_badge_nonblank}, so this falls back to a synthetic,
     * unambiguous-as-manual label. This is a label for audit/debugging only (the punch's
     * {@code employee_id} column, not {@code badge_code}, is what every read path actually joins
     * on) -- see {@code hr.attendance_punch.badge_code}'s V7 column comment.
     */
    public String resolveBadgeCode(long employeeId) {
        String badgeCode = jdbc.query(
            "SELECT badge_card_no FROM hr.employee WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId),
            (rs, rowNum) -> rs.getString("badge_card_no"))
            .stream()
            .findFirst()
            .orElse(null);
        return badgeCode == null || badgeCode.isBlank() ? "MANUAL-" + employeeId : badgeCode;
    }

    /**
     * The site an HR-corrected punch should carry, since {@code hr.attendance_punch.site_code} is
     * {@code NOT NULL} but a correction request never asks the employee to pick one. Preference
     * order: (1) the day's existing {@code attendance_daily.site_code}, if a partial record already
     * exists (the common case -- one side of the punch already scanned, only the other side is
     * missing); (2) the employee's most recent punch anywhere, so a first-ever correction still
     * lands at their usual site; (3) {@code 'SHOWROOM'}, the same seeded default {@code
     * AttendanceScopeIntegrationTest} and every other test fixture in this codebase already uses.
     */
    public String resolveSiteCode(long employeeId, LocalDate workDate) {
        return jdbc.query("""
            SELECT COALESCE(
                (SELECT site_code FROM hr.attendance_daily
                  WHERE employee_id = :employeeId AND work_date = :workDate),
                (SELECT site_code FROM hr.attendance_punch
                  WHERE employee_id = :employeeId
                  ORDER BY punch_time DESC LIMIT 1),
                'SHOWROOM'
            ) AS site_code
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("workDate", workDate),
            (rs, rowNum) -> rs.getString("site_code"))
            .stream()
            .findFirst()
            .orElse("SHOWROOM");
    }

    /**
     * Inserts one HR-corrected punch. Always {@code punch_source = 'MANUAL'},
     * {@code ingest_method = 'MANUAL_ENTRY'} -- the two enum slots V7 reserved for exactly this and
     * that, before this feature, nothing wrote (see V7's {@code chk_attendance_punch_source}/
     * {@code chk_attendance_ingest_method}).
     */
    public long insertManualPunch(
            long employeeId, String siteCode, String badgeCode, OffsetDateTime punchTime, LocalDate workDate) {
        Long punchId = jdbc.queryForObject("""
            INSERT INTO hr.attendance_punch (
                site_code, employee_id, badge_code, punch_time, work_date,
                punch_source, ingest_method
            )
            VALUES (
                :siteCode, :employeeId, :badgeCode, :punchTime, :workDate,
                'MANUAL', 'MANUAL_ENTRY'
            )
            RETURNING punch_id
            """, new MapSqlParameterSource()
            .addValue("siteCode", siteCode)
            .addValue("employeeId", employeeId)
            .addValue("badgeCode", badgeCode)
            .addValue("punchTime", punchTime)
            .addValue("workDate", workDate), Long.class);
        return punchId == null ? 0 : punchId;
    }

    private String baseSelect() {
        return """
            SELECT c.request_id,
                   c.employee_id,
                   e.employee_code,
                   concat_ws(' ', e.first_name_th, e.last_name_th) AS employee_name,
                   c.work_date,
                   c.correction_type,
                   c.requested_check_in,
                   c.requested_check_out,
                   c.reason,
                   c.status,
                   c.requested_by_id,
                   concat_ws(' ', requested_by.first_name_th, requested_by.last_name_th) AS requested_by_name,
                   c.requested_at,
                   c.reviewed_by_id,
                   concat_ws(' ', reviewed_by.first_name_th, reviewed_by.last_name_th) AS reviewed_by_name,
                   c.reviewed_at,
                   c.reviewer_note,
                   c.cancelled_at,
                   c.created_at,
                   c.updated_at
              FROM hr.attendance_correction_request c
              JOIN hr.employee e ON e.employee_id = c.employee_id
              LEFT JOIN hr.employee requested_by ON requested_by.employee_id = c.requested_by_id
              LEFT JOIN hr.employee reviewed_by ON reviewed_by.employee_id = c.reviewed_by_id
            """;
    }

    private AttendanceCorrectionRequestDto mapRequest(ResultSet rs, int rowNum) throws SQLException {
        return new AttendanceCorrectionRequestDto(
            rs.getLong("request_id"),
            rs.getLong("employee_id"),
            rs.getString("employee_code"),
            rs.getString("employee_name"),
            rs.getObject("work_date", LocalDate.class),
            rs.getString("correction_type"),
            rs.getObject("requested_check_in", OffsetDateTime.class),
            rs.getObject("requested_check_out", OffsetDateTime.class),
            rs.getString("reason"),
            rs.getString("status"),
            nullableLong(rs, "requested_by_id"),
            blankToNull(rs.getString("requested_by_name")),
            rs.getObject("requested_at", OffsetDateTime.class),
            nullableLong(rs, "reviewed_by_id"),
            blankToNull(rs.getString("reviewed_by_name")),
            rs.getObject("reviewed_at", OffsetDateTime.class),
            rs.getString("reviewer_note"),
            rs.getObject("cancelled_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            false
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
