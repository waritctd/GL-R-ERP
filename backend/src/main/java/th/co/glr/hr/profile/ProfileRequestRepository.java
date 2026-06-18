package th.co.glr.hr.profile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.auth.UserPrincipal;

@Repository
public class ProfileRequestRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ProfileRequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProfileRequestRecord> findAll() {
        return jdbc.query(baseSelect() + " ORDER BY r.requested_at DESC, r.request_id DESC",
            Map.of(), (rs, rowNum) -> new ProfileRequestRecord(
                rs.getLong("request_id"),
                rs.getLong("employee_id"),
                rs.getString("field_key"),
                rs.getString("field_label"),
                rs.getString("old_value"),
                rs.getString("new_value"),
                rs.getString("requested_by_name"),
                rs.getObject("requested_at", LocalDate.class),
                rs.getString("status"),
                rs.getObject("reviewed_at", LocalDate.class)
            ));
    }

    public List<ProfileRequestRecord> findByEmployee(long employeeId) {
        return jdbc.query(baseSelect() + " WHERE r.employee_id = :employeeId ORDER BY r.requested_at DESC, r.request_id DESC",
            Map.of("employeeId", employeeId), (rs, rowNum) -> new ProfileRequestRecord(
                rs.getLong("request_id"),
                rs.getLong("employee_id"),
                rs.getString("field_key"),
                rs.getString("field_label"),
                rs.getString("old_value"),
                rs.getString("new_value"),
                rs.getString("requested_by_name"),
                rs.getObject("requested_at", LocalDate.class),
                rs.getString("status"),
                rs.getObject("reviewed_at", LocalDate.class)
            ));
    }

    public Optional<ProfileRequestRecord> findById(long id) {
        try {
            ProfileRequestRecord record = jdbc.queryForObject(baseSelect() + " WHERE r.request_id = :id",
                Map.of("id", id), (rs, rowNum) -> new ProfileRequestRecord(
                    rs.getLong("request_id"),
                    rs.getLong("employee_id"),
                    rs.getString("field_key"),
                    rs.getString("field_label"),
                    rs.getString("old_value"),
                    rs.getString("new_value"),
                    rs.getString("requested_by_name"),
                    rs.getObject("requested_at", LocalDate.class),
                    rs.getString("status"),
                    rs.getObject("reviewed_at", LocalDate.class)
                ));
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public long create(long employeeId, CreateProfileRequestRequest request, UserPrincipal requestedBy) {
        Long id = jdbc.queryForObject("""
            INSERT INTO hr.profile_change_request(
                employee_id, field_key, field_label, old_value, new_value,
                requested_by_user_id, requested_by_name
            )
            VALUES (
                :employeeId, :fieldKey, :fieldLabel, :oldValue, :newValue,
                :requestedByUserId, :requestedByName
            )
            RETURNING request_id
            """, new MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("fieldKey", request.fieldKey())
            .addValue("fieldLabel", request.fieldLabel())
            .addValue("oldValue", request.oldValue())
            .addValue("newValue", request.newValue())
            .addValue("requestedByUserId", requestedBy.id())
            .addValue("requestedByName", requestedBy.name()), Long.class);
        return id == null ? 0 : id;
    }

    public void updateStatus(long id, String status, long reviewerUserId, String reviewerNote) {
        jdbc.update("""
            UPDATE hr.profile_change_request
               SET status = :status,
                   reviewed_by_user_id = :reviewerUserId,
                   reviewed_at = now(),
                   reviewer_note = :reviewerNote
             WHERE request_id = :id
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("status", status)
            .addValue("reviewerUserId", reviewerUserId)
            .addValue("reviewerNote", reviewerNote));
    }

    public Map<Long, Integer> pendingCountsByEmployee() {
        return jdbc.query("""
            SELECT employee_id, COUNT(*)::int AS pending_count
              FROM hr.profile_change_request
             WHERE status = 'pending'
             GROUP BY employee_id
            """, Map.of(), (rs, rowNum) -> Map.entry(rs.getLong("employee_id"), rs.getInt("pending_count")))
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public int pendingCountByEmployee(long employeeId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)::int
              FROM hr.profile_change_request
             WHERE employee_id = :employeeId
               AND status = 'pending'
            """, Map.of("employeeId", employeeId), Integer.class);
        return count == null ? 0 : count;
    }

    private String baseSelect() {
        return """
            SELECT r.request_id,
                   r.employee_id,
                   r.field_key,
                   r.field_label,
                   r.old_value,
                   r.new_value,
                   r.requested_by_name,
                   r.requested_at::date AS requested_at,
                   r.status,
                   r.reviewed_at::date AS reviewed_at
              FROM hr.profile_change_request r
            """;
    }
}
