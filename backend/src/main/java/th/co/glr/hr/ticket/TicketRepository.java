package th.co.glr.hr.ticket;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TicketRepository {
    private static final String SUMMARY_SELECT = """
        SELECT t.ticket_id,
               t.code, t.type, t.title, t.status, t.priority,
               t.created_by,
               NULLIF(TRIM(CONCAT_WS(' ', ec.first_name_th, ec.last_name_th)), '') AS created_by_name,
               t.assigned_to,
               NULLIF(TRIM(CONCAT_WS(' ', ea.first_name_th, ea.last_name_th)), '') AS assigned_to_name,
               t.customer_name, t.note,
               t.created_at, t.updated_at, t.closed_at,
               COUNT(ti.item_id) AS item_count,
               t.has_edits
          FROM sales.ticket t
          JOIN hr.employee ec ON ec.employee_id = t.created_by
          LEFT JOIN hr.employee ea ON ea.employee_id = t.assigned_to
          LEFT JOIN sales.ticket_item ti ON ti.ticket_id = t.ticket_id
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public TicketRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TicketSummaryDto> findSummaries(String status, Long createdByFilter) {
        return jdbc.query(
            SUMMARY_SELECT + """
             WHERE (:status::varchar IS NULL OR t.status = :status)
               AND (:createdBy::bigint IS NULL OR t.created_by = :createdBy)
             GROUP BY t.ticket_id, ec.first_name_th, ec.last_name_th,
                      ea.first_name_th, ea.last_name_th
             ORDER BY t.created_at DESC
            """,
            new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("createdBy", createdByFilter),
            (rs, rowNum) -> mapSummary(rs));
    }

    public Optional<TicketDto> findById(long id) {
        Optional<TicketSummaryDto> summary = findSummaryById(id);
        if (summary.isEmpty()) return Optional.empty();
        List<TicketItemDto> items = findItemsByTicketId(id);
        List<TicketEventDto> events = findEventsByTicketId(id);
        QuotationDto quotation = findQuotationByTicketId(id).orElse(null);
        return Optional.of(new TicketDto(summary.get(), items, events, quotation));
    }

    public boolean existsById(long ticketId) {
        Boolean result = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM sales.ticket WHERE ticket_id = :id)",
            Map.of("id", ticketId), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    public String nextTicketCode() {
        long seq = jdbc.queryForObject("SELECT nextval('sales.ticket_code_seq')", Map.of(), Long.class);
        return "PR-" + Year.now() + "-" + String.format("%04d", seq);
    }

    public String nextQuotationCode() {
        long seq = jdbc.queryForObject("SELECT nextval('sales.quotation_code_seq')", Map.of(), Long.class);
        return "QT-" + Year.now() + "-" + String.format("%04d", seq);
    }

    @Transactional
    public long create(CreateTicketRequest request, String code, long actorId, String actorName) {
        String priority = (request.priority() != null && !request.priority().isBlank())
            ? request.priority() : "NORMAL";
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.ticket (code, title, status, priority, created_by, customer_name, note)
            VALUES (:code, :title, 'submitted', :priority, :createdBy, :customerName, :note)
            """,
            new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("title", request.title())
                .addValue("priority", priority)
                .addValue("createdBy", actorId)
                .addValue("customerName", request.customerName())
                .addValue("note", request.note()),
            keyHolder, new String[]{"ticket_id"});
        long ticketId = keyHolder.getKey().longValue();
        insertItems(ticketId, request.items());
        addEvent(ticketId, actorId, actorName, TicketEventKind.SUBMITTED, null, TicketStatus.SUBMITTED, null);
        return ticketId;
    }

    public void addEvent(long ticketId, long actorId, String actorName,
                         String kind, String fromStatus, String toStatus, String message) {
        if (toStatus != null) {
            boolean closing = TicketStatus.CLOSED.equals(toStatus) || TicketStatus.CANCELLED.equals(toStatus);
            boolean isPickup = TicketEventKind.PICKED_UP.equals(kind);
            jdbc.update("""
                UPDATE sales.ticket
                   SET status = :status,
                       updated_at = now(),
                       closed_at = CASE WHEN :closing THEN now() ELSE closed_at END,
                       assigned_to = CASE WHEN :isPickup THEN :actorId ELSE assigned_to END
                 WHERE ticket_id = :id
                """,
                new MapSqlParameterSource()
                    .addValue("status", toStatus)
                    .addValue("closing", closing)
                    .addValue("isPickup", isPickup)
                    .addValue("actorId", actorId)
                    .addValue("id", ticketId));
        }
        jdbc.update("""
            INSERT INTO sales.ticket_event
                (ticket_id, actor_id, actor_name, kind, from_status, to_status, message)
            VALUES (:ticketId, :actorId, :actorName, :kind, :fromStatus, :toStatus, :message)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("actorId", actorId)
                .addValue("actorName", actorName)
                .addValue("kind", kind)
                .addValue("fromStatus", fromStatus)
                .addValue("toStatus", toStatus)
                .addValue("message", message));
    }

    public void replaceItems(long ticketId, List<TicketItemRequest> items) {
        jdbc.update("DELETE FROM sales.ticket_item WHERE ticket_id = :id", Map.of("id", ticketId));
        insertItems(ticketId, items);
    }

    public void approveItemPrices(long ticketId) {
        jdbc.update("""
            UPDATE sales.ticket_item
               SET approved_price = proposed_price
             WHERE ticket_id = :id AND proposed_price IS NOT NULL
            """, Map.of("id", ticketId));
    }

    public QuotationDto createQuotation(long ticketId, String number, long issuedById, BigDecimal totalAmount) {
        jdbc.update("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, total_amount, currency)
            VALUES (:ticketId, :number, :issuedBy, :totalAmount, 'THB')
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("number", number)
                .addValue("issuedBy", issuedById)
                .addValue("totalAmount", totalAmount));
        return findQuotationByTicketId(ticketId).orElseThrow();
    }

    // --- private helpers ---

    private Optional<TicketSummaryDto> findSummaryById(long id) {
        try {
            TicketSummaryDto summary = jdbc.queryForObject(
                SUMMARY_SELECT + """
                 WHERE t.ticket_id = :id
                 GROUP BY t.ticket_id, ec.first_name_th, ec.last_name_th,
                          ea.first_name_th, ea.last_name_th
                """,
                Map.of("id", id), (rs, rowNum) -> mapSummary(rs));
            return Optional.ofNullable(summary);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private List<TicketItemDto> findItemsByTicketId(long ticketId) {
        return jdbc.query("""
            SELECT item_id, ticket_id, brand, model, color, texture, size,
                   qty, proposed_price, approved_price, currency, sort_order
              FROM sales.ticket_item
             WHERE ticket_id = :id
             ORDER BY sort_order, item_id
            """,
            Map.of("id", ticketId),
            (rs, rowNum) -> new TicketItemDto(
                rs.getLong("item_id"),
                rs.getLong("ticket_id"),
                rs.getString("brand"),
                rs.getString("model"),
                rs.getString("color"),
                rs.getString("texture"),
                rs.getString("size"),
                rs.getBigDecimal("qty"),
                rs.getBigDecimal("proposed_price"),
                rs.getBigDecimal("approved_price"),
                rs.getString("currency"),
                rs.getInt("sort_order")
            ));
    }

    private List<TicketEventDto> findEventsByTicketId(long ticketId) {
        return jdbc.query("""
            SELECT event_id, ticket_id, actor_id, actor_name, kind,
                   from_status, to_status, message, created_at
              FROM sales.ticket_event
             WHERE ticket_id = :id
             ORDER BY created_at ASC, event_id ASC
            """,
            Map.of("id", ticketId),
            (rs, rowNum) -> new TicketEventDto(
                rs.getLong("event_id"),
                rs.getLong("ticket_id"),
                rs.getLong("actor_id"),
                rs.getString("actor_name"),
                rs.getString("kind"),
                rs.getString("from_status"),
                rs.getString("to_status"),
                rs.getString("message"),
                rs.getTimestamp("created_at").toInstant()
            ));
    }

    private Optional<QuotationDto> findQuotationByTicketId(long ticketId) {
        try {
            QuotationDto q = jdbc.queryForObject("""
                SELECT q.quotation_id, q.ticket_id, q.number, q.issued_by,
                       NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS issued_by_name,
                       q.issued_at, q.pdf_path, q.total_amount, q.currency
                  FROM sales.quotation q
                  JOIN hr.employee e ON e.employee_id = q.issued_by
                 WHERE q.ticket_id = :id
                """,
                Map.of("id", ticketId),
                (rs, rowNum) -> new QuotationDto(
                    rs.getLong("quotation_id"),
                    rs.getLong("ticket_id"),
                    rs.getString("number"),
                    rs.getLong("issued_by"),
                    rs.getString("issued_by_name"),
                    rs.getTimestamp("issued_at").toInstant(),
                    rs.getString("pdf_path"),
                    rs.getBigDecimal("total_amount"),
                    rs.getString("currency")
                ));
            return Optional.ofNullable(q);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private void insertItems(long ticketId, List<TicketItemRequest> items) {
        for (int i = 0; i < items.size(); i++) {
            TicketItemRequest item = items.get(i);
            String currency = (item.currency() != null && !item.currency().isBlank()) ? item.currency() : "THB";
            jdbc.update("""
                INSERT INTO sales.ticket_item
                    (ticket_id, brand, model, color, texture, size,
                     qty, proposed_price, currency, sort_order)
                VALUES (:ticketId, :brand, :model, :color, :texture, :size,
                        :qty, :proposedPrice, :currency, :sortOrder)
                """,
                new MapSqlParameterSource()
                    .addValue("ticketId", ticketId)
                    .addValue("brand", item.brand())
                    .addValue("model", item.model())
                    .addValue("color", item.color())
                    .addValue("texture", item.texture())
                    .addValue("size", item.size())
                    .addValue("qty", item.qty())
                    .addValue("proposedPrice", item.proposedPrice())
                    .addValue("currency", currency)
                    .addValue("sortOrder", i));
        }
    }

    private TicketSummaryDto mapSummary(ResultSet rs) throws SQLException {
        long assignedToRaw = rs.getLong("assigned_to");
        Long assignedToId = rs.wasNull() ? null : assignedToRaw;
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new TicketSummaryDto(
            rs.getLong("ticket_id"),
            rs.getString("code"),
            rs.getString("type"),
            rs.getString("title"),
            rs.getString("status"),
            rs.getString("priority"),
            rs.getLong("created_by"),
            rs.getString("created_by_name"),
            assignedToId,
            rs.getString("assigned_to_name"),
            rs.getString("customer_name"),
            rs.getString("note"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            closedAt != null ? closedAt.toInstant() : null,
            rs.getInt("item_count"),
            rs.getBoolean("has_edits")
        );
    }

    public void setHasEdits(long ticketId, boolean value) {
        jdbc.update(
            "UPDATE sales.ticket SET has_edits = :v, updated_at = now() WHERE ticket_id = :id",
            Map.of("id", ticketId, "v", value));
    }
}
