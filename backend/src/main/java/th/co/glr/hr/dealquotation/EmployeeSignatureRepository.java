package th.co.glr.hr.dealquotation;

import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Quotation v2 (direct deal quotation, V165) — persistence for {@code hr.employee_signature}, the
 * approver's signature image anchored into the ผู้อนุมัติ box on an APPROVED PDF/XLSX (a later
 * renderer slice — this repository just stores/serves the bytes).
 */
@Repository
public class EmployeeSignatureRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public EmployeeSignatureRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SignatureImage(String mimeType, byte[] image) {}

    /** Whether {@code hr.employee} has this row at all (active or not) — the 404 gate the service
     * applies before touching a signature, so a write against an id that does not exist is
     * refused rather than "succeeding" against nothing. */
    public boolean employeeExists(long employeeId) {
        Boolean found = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM hr.employee WHERE employee_id = :id)",
            Map.of("id", employeeId), Boolean.class);
        return Boolean.TRUE.equals(found);
    }

    public boolean exists(long employeeId) {
        Boolean found = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM hr.employee_signature WHERE employee_id = :id)",
            Map.of("id", employeeId), Boolean.class);
        return Boolean.TRUE.equals(found);
    }

    public Optional<SignatureImage> find(long employeeId) {
        try {
            SignatureImage image = jdbc.queryForObject(
                "SELECT mime_type, image FROM hr.employee_signature WHERE employee_id = :id",
                Map.of("id", employeeId),
                (rs, rowNum) -> new SignatureImage(rs.getString("mime_type"), rs.getBytes("image")));
            return Optional.ofNullable(image);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void upsert(long employeeId, String mimeType, byte[] image, long uploadedBy) {
        jdbc.update("""
            INSERT INTO hr.employee_signature (employee_id, mime_type, image, uploaded_by, uploaded_at)
            VALUES (:employeeId, :mimeType, :image, :uploadedBy, now())
            ON CONFLICT (employee_id) DO UPDATE
                SET mime_type = EXCLUDED.mime_type, image = EXCLUDED.image,
                    uploaded_by = EXCLUDED.uploaded_by, uploaded_at = now()
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("mimeType", mimeType)
                .addValue("image", image)
                .addValue("uploadedBy", uploadedBy));
    }

    public void delete(long employeeId) {
        jdbc.update("DELETE FROM hr.employee_signature WHERE employee_id = :id", Map.of("id", employeeId));
    }
}
