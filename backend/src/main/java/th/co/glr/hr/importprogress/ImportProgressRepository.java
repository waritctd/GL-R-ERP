package th.co.glr.hr.importprogress;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.importprogress.ImportProgressDtos.FactoryImportProgressDto;

/**
 * Persistence for {@code sales.factory_import_progress} (V184). Persistence only — no permission
 * checks, no workflow validation; see {@link ImportProgressService} for those, mirroring every
 * other feature's repository/service split.
 */
@Repository
public class ImportProgressRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ImportProgressRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_ROW = """
        SELECT fip.factory_import_progress_id, fip.pricing_request_id, fip.ticket_id,
               fip.factory_name, fip.import_step, fip.import_step_at, fip.eta, fip.note,
               fip.updated_by, fip.updated_at,
               pr.request_code AS pricing_request_code, t.code AS ticket_code,
               e.first_name_th AS updated_by_first_name_th, e.last_name_th AS updated_by_last_name_th
          FROM sales.factory_import_progress fip
          JOIN sales.pricing_request pr ON pr.pricing_request_id = fip.pricing_request_id
          JOIN sales.ticket t ON t.ticket_id = fip.ticket_id
          LEFT JOIN hr.employee e ON e.employee_id = fip.updated_by
        """;

    /**
     * Seeds one row per distinct factory of this pricing request — its {@code
     * factory_name_snapshot}s on {@code sales.factory_quote}, the same per-factory entity Import
     * already emails. Idempotent: {@code ON CONFLICT DO NOTHING} against
     * {@code uq_factory_import_progress}, so re-opening the tracker never duplicates or resets a
     * row. Returns how many were newly inserted.
     */
    public int ensureRowsForPricingRequest(long pricingRequestId, long ticketId) {
        return jdbc.update("""
            INSERT INTO sales.factory_import_progress (pricing_request_id, ticket_id, factory_name)
            SELECT DISTINCT :pricingRequestId, :ticketId, fq.factory_name_snapshot
              FROM sales.factory_quote fq
             WHERE fq.pricing_request_id = :pricingRequestId
               AND fq.factory_name_snapshot IS NOT NULL
            ON CONFLICT ON CONSTRAINT uq_factory_import_progress DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("ticketId", ticketId));
    }

    public List<FactoryImportProgressDto> findByPricingRequest(long pricingRequestId) {
        return jdbc.query(SELECT_ROW + " WHERE fip.pricing_request_id = :pricingRequestId ORDER BY fip.factory_name",
            Map.of("pricingRequestId", pricingRequestId), this::map);
    }

    public List<FactoryImportProgressDto> findAll() {
        return jdbc.query(SELECT_ROW + " ORDER BY t.code, fip.factory_name", Map.of(), this::map);
    }

    public List<FactoryImportProgressDto> findByTicket(long ticketId) {
        return jdbc.query(SELECT_ROW + " WHERE fip.ticket_id = :ticketId ORDER BY fip.factory_name",
            Map.of("ticketId", ticketId), this::map);
    }

    public Optional<FactoryImportProgressDto> findById(long id) {
        try {
            return Optional.of(jdbc.queryForObject(
                SELECT_ROW + " WHERE fip.factory_import_progress_id = :id", Map.of("id", id), this::map));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Sets the step and stamps who/when. The monotonic/valid-target check lives in
     * {@link ImportProgressService#advanceStep} (which holds the current step); this only writes.
     */
    public int advanceStep(long id, String importStep, long actorId) {
        return jdbc.update("""
            UPDATE sales.factory_import_progress
               SET import_step = :step, import_step_at = now(), updated_by = :actorId, updated_at = now()
             WHERE factory_import_progress_id = :id
            """,
            new MapSqlParameterSource().addValue("id", id).addValue("step", importStep).addValue("actorId", actorId));
    }

    public int updateEtaNote(long id, LocalDate eta, String note, long actorId) {
        return jdbc.update("""
            UPDATE sales.factory_import_progress
               SET eta = :eta, note = :note, updated_by = :actorId, updated_at = now()
             WHERE factory_import_progress_id = :id
            """,
            new MapSqlParameterSource().addValue("id", id)
                .addValue("eta", eta).addValue("note", note).addValue("actorId", actorId));
    }

    private FactoryImportProgressDto map(ResultSet rs, int rowNum) throws SQLException {
        long updatedByRaw = rs.getLong("updated_by");
        Long updatedBy = rs.wasNull() ? null : updatedByRaw;
        String updatedByName = joinName(rs.getString("updated_by_first_name_th"), rs.getString("updated_by_last_name_th"));
        return new FactoryImportProgressDto(
            rs.getLong("factory_import_progress_id"),
            rs.getLong("pricing_request_id"), rs.getString("pricing_request_code"),
            rs.getLong("ticket_id"), rs.getString("ticket_code"),
            rs.getString("factory_name"), rs.getString("import_step"),
            rs.getTimestamp("import_step_at") != null ? rs.getTimestamp("import_step_at").toInstant() : null,
            rs.getObject("eta", LocalDate.class), rs.getString("note"),
            updatedBy, updatedByName,
            rs.getTimestamp("updated_at").toInstant());
    }

    private String joinName(String firstNameTh, String lastNameTh) {
        String joined = ((firstNameTh == null ? "" : firstNameTh) + " " + (lastNameTh == null ? "" : lastNameTh)).trim();
        return joined.isEmpty() ? null : joined;
    }
}
