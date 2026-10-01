package th.co.glr.hr.pricingrequest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.pricingrequest.LeadTimeChangeDtos.LeadTimeChangeDto;
import th.co.glr.hr.pricingrequest.LeadTimeChangeDtos.LeadTimeChangeLineDto;

/**
 * CR-1 (GLA-167): persistence for lead-time change requests (V195). Persistence only -- who may do
 * what, and every validation, lives in {@link LeadTimeChangeService}. State changes are
 * compare-and-set on {@code status = 'PENDING'} and return a row count, so a lost race is a 409 in
 * the service rather than a silent overwrite.
 */
@Repository
public class LeadTimeChangeRepository {
    /** One line to store: the new range plus the old range snapshotted from the item at request time. */
    public record LineWrite(long pricingRequestItemId, Integer oldMin, Integer oldMax, int newMin, int newMax) {}

    /** Stored as decision_reason when a change is withdrawn by the system because its quote row died. */
    public static final String AUTO_WITHDRAW_REASON = "ใบราคาถูกแก้ไข — คำขอเดิมถูกยกเลิกอัตโนมัติ";

    /**
     * System withdrawal of PENDING changes matching one predicate ({@code %s}, written against alias
     * {@code c}) plus one audit event per change, in a single statement. Used by the repositories
     * that retire a quote row / close a request, so the change can never outlive what it was about.
     * Binds {@code :autoReason}; the caller binds whatever the predicate names.
     */
    public static final String AUTO_WITHDRAW_SQL = """
        WITH w AS (
            UPDATE sales.lead_time_change c
               SET status = 'WITHDRAWN', decision_reason = :autoReason,
                   version = c.version + 1, updated_at = now()
             WHERE %s AND c.status = 'PENDING'
            RETURNING c.lead_time_change_id, c.pricing_request_id
        )
        INSERT INTO sales.pricing_request_event
            (pricing_request_id, ticket_id, event_kind, from_status, to_status, message)
        SELECT w.pricing_request_id, pr.ticket_id, 'LEAD_TIME_CHANGE_WITHDRAWN', pr.status, pr.status,
               'Lead-time change withdrawn automatically: its factory quote was revised or the request closed'
          FROM w JOIN sales.pricing_request pr ON pr.pricing_request_id = w.pricing_request_id
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public LeadTimeChangeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the change and its lines. The partial unique index {@code
     * uq_lead_time_change_one_pending_per_quote} is the authoritative "one PENDING per factory
     * quote" guard; a violation surfaces as {@link DuplicateKeyException} for the caller to map to 409.
     */
    public long create(long factoryQuoteId, long pricingRequestId, String reason, long requestedBy,
                       List<LineWrite> lines) throws DuplicateKeyException {
        Long id = jdbc.queryForObject("""
            INSERT INTO sales.lead_time_change (factory_quote_id, pricing_request_id, reason, requested_by)
            VALUES (:factoryQuoteId, :pricingRequestId, :reason, :requestedBy)
            RETURNING lead_time_change_id
            """,
            new MapSqlParameterSource()
                .addValue("factoryQuoteId", factoryQuoteId)
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("reason", reason)
                .addValue("requestedBy", requestedBy),
            Long.class);
        long changeId = id == null ? 0L : id;
        insertLines(changeId, lines);
        return changeId;
    }

    /** Replaces reason + lines of a still-PENDING change. Returns 0 if it is no longer PENDING. */
    public int updatePending(long changeId, String reason, List<LineWrite> lines) {
        int rows = jdbc.update("""
            UPDATE sales.lead_time_change
               SET reason = :reason, version = version + 1, updated_at = now()
             WHERE lead_time_change_id = :id AND status = 'PENDING'
            """, new MapSqlParameterSource().addValue("id", changeId).addValue("reason", reason));
        if (rows == 1) {
            jdbc.update("DELETE FROM sales.lead_time_change_line WHERE lead_time_change_id = :id",
                Map.of("id", changeId));
            insertLines(changeId, lines);
        }
        return rows;
    }

    /** PENDING -> WITHDRAWN. Not a decision, so decided_* stay null. */
    public int withdrawPending(long changeId) {
        return jdbc.update("""
            UPDATE sales.lead_time_change
               SET status = 'WITHDRAWN', updated_at = now()
             WHERE lead_time_change_id = :id AND status = 'PENDING'
            """, Map.of("id", changeId));
    }

    /** PENDING -> APPROVED/REJECTED (status is a code constant, never user input). */
    public int decidePending(long changeId, String newStatus, long decidedBy, String decisionReason,
                             int expectedVersion) {
        return jdbc.update("""
            UPDATE sales.lead_time_change
               SET status = :status, decided_by = :decidedBy, decided_at = now(),
                   decision_reason = :decisionReason, version = version + 1, updated_at = now()
             WHERE lead_time_change_id = :id AND status = 'PENDING' AND version = :expectedVersion
            """,
            new MapSqlParameterSource()
                .addValue("id", changeId)
                .addValue("status", newStatus)
                .addValue("decidedBy", decidedBy)
                .addValue("decisionReason", decisionReason)
                .addValue("expectedVersion", expectedVersion));
    }

    /** Writes each line's new range onto its pricing_request_item (the approve step). */
    public int applyLinesToItems(long changeId) {
        return jdbc.update("""
            UPDATE sales.pricing_request_item i
               SET lead_time_min_days = l.new_min_days,
                   lead_time_max_days = l.new_max_days
              FROM sales.lead_time_change_line l
             WHERE l.lead_time_change_id = :id
               AND l.pricing_request_item_id = i.pricing_request_item_id
            """, Map.of("id", changeId));
    }

    public Optional<LeadTimeChangeDto> find(long changeId) {
        List<LeadTimeChangeDto> found = query("WHERE c.lead_time_change_id = :id", Map.of("id", changeId));
        return found.stream().findFirst();
    }

    public List<LeadTimeChangeDto> findByPricingRequest(long pricingRequestId) {
        return query("WHERE c.pricing_request_id = :id", Map.of("id", pricingRequestId));
    }

    private List<LeadTimeChangeDto> query(String where, Map<String, Object> params) {
        List<LeadTimeChangeDto> headers = jdbc.query("""
            SELECT c.lead_time_change_id, c.factory_quote_id, c.pricing_request_id, c.status, c.reason,
                   c.requested_by, c.requested_at, c.decided_by, c.decided_at, c.decision_reason, c.version
              FROM sales.lead_time_change c
            """ + where + """

             ORDER BY c.requested_at DESC, c.lead_time_change_id DESC
            """, params, (rs, rowNum) -> mapHeader(rs));
        List<LeadTimeChangeDto> result = new ArrayList<>(headers.size());
        for (LeadTimeChangeDto header : headers) {
            result.add(new LeadTimeChangeDto(header.id(), header.factoryQuoteId(), header.pricingRequestId(),
                header.status(), header.reason(), header.requestedBy(), header.requestedAt(), header.decidedBy(),
                header.decidedAt(), header.decisionReason(), findLines(header.id()), header.version()));
        }
        return result;
    }

    private List<LeadTimeChangeLineDto> findLines(long changeId) {
        return jdbc.query("""
            SELECT pricing_request_item_id, old_min_days, old_max_days, new_min_days, new_max_days
              FROM sales.lead_time_change_line
             WHERE lead_time_change_id = :id
             ORDER BY pricing_request_item_id
            """, Map.of("id", changeId), (rs, rowNum) -> new LeadTimeChangeLineDto(
            rs.getLong("pricing_request_item_id"),
            nullableInt(rs, "old_min_days"),
            nullableInt(rs, "old_max_days"),
            rs.getInt("new_min_days"),
            rs.getInt("new_max_days")));
    }

    private void insertLines(long changeId, List<LineWrite> lines) {
        MapSqlParameterSource[] batch = new MapSqlParameterSource[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            LineWrite line = lines.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("changeId", changeId)
                .addValue("itemId", line.pricingRequestItemId())
                .addValue("oldMin", line.oldMin())
                .addValue("oldMax", line.oldMax())
                .addValue("newMin", line.newMin())
                .addValue("newMax", line.newMax());
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.lead_time_change_line
                (lead_time_change_id, pricing_request_item_id, old_min_days, old_max_days, new_min_days, new_max_days)
            VALUES (:changeId, :itemId, :oldMin, :oldMax, :newMin, :newMax)
            """, batch);
    }

    private LeadTimeChangeDto mapHeader(ResultSet rs) throws SQLException {
        long decidedByRaw = rs.getLong("decided_by");
        Long decidedBy = rs.wasNull() ? null : decidedByRaw;
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        Instant requestedAt = rs.getTimestamp("requested_at").toInstant();
        return new LeadTimeChangeDto(
            rs.getLong("lead_time_change_id"), rs.getLong("factory_quote_id"), rs.getLong("pricing_request_id"),
            rs.getString("status"), rs.getString("reason"), rs.getLong("requested_by"), requestedAt,
            decidedBy, decidedAt == null ? null : decidedAt.toInstant(), rs.getString("decision_reason"), List.of(),
            rs.getInt("version"));
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
