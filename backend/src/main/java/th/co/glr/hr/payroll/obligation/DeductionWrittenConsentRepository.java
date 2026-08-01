package th.co.glr.hr.payroll.obligation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.payroll.PayrollDeductionKind;
import th.co.glr.hr.payroll.obligation.DeductionWrittenConsentDtos.DeductionWrittenConsentDto;
import th.co.glr.hr.payroll.obligation.DeductionWrittenConsentDtos.DeductionWrittenConsentUpsertRequest;

/**
 * Persistence for {@code hr.deduction_written_consent} (V107, issue #376). One row per (employee,
 * deduction kind) -- an upsert, not an append-only log, since this records a CURRENT fact ("is
 * consent on file right now"), not a history of changes (the audit log already covers who changed
 * it and when, see {@code AuditService}).
 */
@Repository
public class DeductionWrittenConsentRepository {
    private static final String SELECT_COLUMNS = """
        c.consent_id, c.employee_id, e.employee_code,
        COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.email, e.employee_code) AS employee_name,
        c.deduction_kind, c.consent_on_file, c.consent_document_reference, c.consent_date, c.notes,
        c.recorded_by_id, c.recorded_at, c.updated_by_id, c.updated_at
        """;
    private static final String FROM_JOIN = """
        FROM hr.deduction_written_consent c
        JOIN hr.employee e ON e.employee_id = c.employee_id
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public DeductionWrittenConsentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(DeductionWrittenConsentUpsertRequest request, long actorId) {
        jdbc.update("""
            INSERT INTO hr.deduction_written_consent
                (employee_id, deduction_kind, consent_on_file, consent_document_reference,
                 consent_date, notes, recorded_by_id, updated_by_id)
            VALUES
                (:employeeId, :deductionKind, :consentOnFile, :consentDocumentReference,
                 :consentDate, :notes, :actorId, :actorId)
            ON CONFLICT (employee_id, deduction_kind)
            DO UPDATE SET consent_on_file = EXCLUDED.consent_on_file,
                          consent_document_reference = EXCLUDED.consent_document_reference,
                          consent_date = EXCLUDED.consent_date,
                          notes = EXCLUDED.notes,
                          updated_by_id = EXCLUDED.updated_by_id,
                          updated_at = now()
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", request.employeeId())
                .addValue("deductionKind", request.deductionKind().name())
                .addValue("consentOnFile", request.consentOnFile())
                .addValue("consentDocumentReference", request.consentDocumentReference())
                .addValue("consentDate", request.consentDate())
                .addValue("notes", request.notes())
                .addValue("actorId", actorId));
    }

    public List<DeductionWrittenConsentDto> findAll(Long employeeId, PayrollDeductionKind kind) {
        StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLUMNS + FROM_JOIN + " WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (employeeId != null) {
            sql.append(" AND c.employee_id = :employeeId");
            params.addValue("employeeId", employeeId);
        }
        if (kind != null) {
            sql.append(" AND c.deduction_kind = :kind");
            params.addValue("kind", kind.name());
        }
        sql.append(" ORDER BY e.employee_code, c.deduction_kind");
        return jdbc.query(sql.toString(), params, this::mapRow);
    }

    private DeductionWrittenConsentDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        long recordedById = rs.getLong("recorded_by_id");
        boolean recordedByIdNull = rs.wasNull();
        long updatedById = rs.getLong("updated_by_id");
        boolean updatedByIdNull = rs.wasNull();
        return new DeductionWrittenConsentDto(
            rs.getLong("consent_id"),
            rs.getLong("employee_id"),
            rs.getString("employee_code"),
            rs.getString("employee_name"),
            PayrollDeductionKind.valueOf(rs.getString("deduction_kind")),
            rs.getBoolean("consent_on_file"),
            rs.getString("consent_document_reference"),
            rs.getObject("consent_date", LocalDate.class),
            rs.getString("notes"),
            recordedByIdNull ? null : recordedById,
            rs.getObject("recorded_at", OffsetDateTime.class),
            updatedByIdNull ? null : updatedById,
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
