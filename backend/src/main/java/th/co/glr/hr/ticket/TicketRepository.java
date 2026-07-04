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
import th.co.glr.hr.common.PageRequest;
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
               t.customer_name, t.customer_id, t.project_id, t.contact_id,
               p.name AS project_name,
               NULLIF(TRIM(CONCAT_WS(' ', ct.first_name, ct.last_name)), '') AS contact_name,
               t.note,
               t.created_at, t.updated_at, t.closed_at,
               COUNT(ti.item_id) AS item_count,
               t.has_edits
          FROM sales.ticket t
          JOIN hr.employee ec ON ec.employee_id = t.created_by
          LEFT JOIN hr.employee ea ON ea.employee_id = t.assigned_to
          LEFT JOIN sales.ticket_item ti ON ti.ticket_id = t.ticket_id
          LEFT JOIN sales.project p ON p.project_id = t.project_id
          LEFT JOIN sales.contact ct ON ct.contact_id = t.contact_id
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public TicketRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TicketSummaryDto> findSummaries(String status, Long createdByFilter) {
        return findSummaries(status, createdByFilter, null);
    }

    public List<TicketSummaryDto> findSummaries(String status, Long createdByFilter, PageRequest page) {
        StringBuilder sql = new StringBuilder(SUMMARY_SELECT).append("""
             WHERE (:status::varchar IS NULL OR t.status = :status)
               AND (:createdBy::bigint IS NULL OR t.created_by = :createdBy)
             GROUP BY t.ticket_id, ec.first_name_th, ec.last_name_th,
                      ea.first_name_th, ea.last_name_th,
                      p.name, ct.first_name, ct.last_name
             ORDER BY t.created_at DESC
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("status", status)
            .addValue("createdBy", createdByFilter);
        if (page != null) {
            sql.append(" LIMIT :limit OFFSET :offset");
            params.addValue("limit", page.size());
            params.addValue("offset", page.offset());
        }
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> mapSummary(rs));
    }

    public int countSummaries(String status, Long createdByFilter) {
        Integer total = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket t
             WHERE (:status::varchar IS NULL OR t.status = :status)
               AND (:createdBy::bigint IS NULL OR t.created_by = :createdBy)
            """,
            new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("createdBy", createdByFilter),
            Integer.class);
        return total == null ? 0 : total;
    }

    public Optional<TicketDto> findById(long id) {
        Optional<TicketSummaryDto> summary = findSummaryById(id);
        if (summary.isEmpty()) return Optional.empty();
        List<TicketItemDto> items = findItemsByTicketId(id);
        List<TicketEventDto> events = findEventsByTicketId(id);
        List<QuotationDto> quotations = findQuotationsByTicketId(id);
        QuotationDto quotation = quotations.isEmpty() ? null : quotations.get(0);
        return Optional.of(new TicketDto(summary.get(), items, events, quotation, quotations));
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
            INSERT INTO sales.ticket
                (code, title, status, priority, created_by, customer_name, customer_id, project_id, contact_id, note)
            VALUES
                (:code, :title, 'submitted', :priority, :createdBy, :customerName, :customerId, :projectId, :contactId, :note)
            """,
            new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("title", request.title())
                .addValue("priority", priority)
                .addValue("createdBy", actorId)
                .addValue("customerName", request.customerName())
                .addValue("customerId", request.customerId())
                .addValue("projectId", request.projectId())
                .addValue("contactId", request.contactId())
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

    @Transactional
    public QuotationDto createQuotation(long ticketId, String number, long issuedById, BigDecimal totalAmount) {
        // supersede all current (non-superseded) quotations for this ticket
        jdbc.update("""
            UPDATE sales.quotation
               SET doc_status = 'SUPERSEDED'
             WHERE ticket_id = :ticketId AND doc_status <> 'SUPERSEDED'
            """, Map.of("ticketId", ticketId));

        Integer maxVersion = jdbc.queryForObject("""
            SELECT COALESCE(MAX(quotation_version), 0)
              FROM sales.quotation
             WHERE ticket_id = :ticketId
            """, Map.of("ticketId", ticketId), Integer.class);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;

        // generateQuotation() issues and transitions the ticket to QUOTATION_ISSUED in one
        // step (no separate draft phase like deposit notices), so the row starts ISSUED.
        jdbc.update("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, total_amount, currency, quotation_version, doc_status)
            VALUES (:ticketId, :number, :issuedBy, :totalAmount, 'THB', :version, 'ISSUED')
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("number", number)
                .addValue("issuedBy", issuedById)
                .addValue("totalAmount", totalAmount)
                .addValue("version", nextVersion));

        int versionToFind = nextVersion;
        return findQuotationsByTicketId(ticketId).stream()
            .filter(q -> q.quotationVersion() == versionToFind)
            .findFirst()
            .orElseThrow();
    }

    // --- private helpers ---

    private Optional<TicketSummaryDto> findSummaryById(long id) {
        try {
            TicketSummaryDto summary = jdbc.queryForObject(
                SUMMARY_SELECT + """
                 WHERE t.ticket_id = :id
                 GROUP BY t.ticket_id, ec.first_name_th, ec.last_name_th,
                          ea.first_name_th, ea.last_name_th,
                          p.name, ct.first_name, ct.last_name
                """,
                Map.of("id", id), (rs, rowNum) -> mapSummary(rs));
            return Optional.ofNullable(summary);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private List<TicketItemDto> findItemsByTicketId(long ticketId) {
        return jdbc.query("""
            SELECT item_id, ticket_id, brand, model, color, texture, size, factory,
                   qty, qty_sqm, raw_price, raw_currency, raw_unit,
                   proposed_price, approved_price, currency, sort_order,
                   calced_cost, calced_price, calc_config_version
              FROM sales.ticket_item
             WHERE ticket_id = :id
             ORDER BY sort_order, item_id
            """,
            Map.of("id", ticketId),
            (rs, rowNum) -> {
                int calcConfigVersionRaw = rs.getInt("calc_config_version");
                Integer calcConfigVersion = rs.wasNull() ? null : calcConfigVersionRaw;
                return new TicketItemDto(
                    rs.getLong("item_id"),
                    rs.getLong("ticket_id"),
                    rs.getString("brand"),
                    rs.getString("model"),
                    rs.getString("color"),
                    rs.getString("texture"),
                    rs.getString("size"),
                    rs.getString("factory"),
                    rs.getBigDecimal("qty"),
                    rs.getBigDecimal("qty_sqm"),
                    rs.getBigDecimal("raw_price"),
                    rs.getString("raw_currency"),
                    rs.getString("raw_unit"),
                    rs.getBigDecimal("proposed_price"),
                    rs.getBigDecimal("approved_price"),
                    rs.getString("currency"),
                    rs.getInt("sort_order"),
                    rs.getBigDecimal("calced_cost"),
                    rs.getBigDecimal("calced_price"),
                    calcConfigVersion
                );
            });
    }

    public void updateItemCalcResults(long itemId, BigDecimal calcedCost,
                                      BigDecimal calcedPrice, int configVersion,
                                      BigDecimal proposedPrice) {
        jdbc.update("""
            UPDATE sales.ticket_item
               SET calced_cost         = :calcedCost,
                   calced_price        = :calcedPrice,
                   calc_config_version = :version,
                   proposed_price      = :proposedPrice
             WHERE item_id = :id
            """,
            new MapSqlParameterSource()
                .addValue("calcedCost", calcedCost)
                .addValue("calcedPrice", calcedPrice)
                .addValue("version", configVersion)
                .addValue("proposedPrice", proposedPrice)
                .addValue("id", itemId));
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

    private List<QuotationDto> findQuotationsByTicketId(long ticketId) {
        return jdbc.query("""
            SELECT q.quotation_id, q.ticket_id, q.number, q.issued_by,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS issued_by_name,
                   q.issued_at, q.pdf_path, q.total_amount, q.currency,
                   q.quotation_version, q.doc_status
              FROM sales.quotation q
              JOIN hr.employee e ON e.employee_id = q.issued_by
             WHERE q.ticket_id = :id
             ORDER BY q.quotation_version DESC
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
                rs.getString("currency"),
                rs.getInt("quotation_version"),
                rs.getString("doc_status")
            ));
    }

    private void insertItems(long ticketId, List<TicketItemRequest> items) {
        if (items.isEmpty()) {
            return;
        }
        // Single batched round-trip instead of one INSERT per item; the datasource has
        // reWriteBatchedInserts=true so this collapses into one multi-row statement (issue #29).
        MapSqlParameterSource[] batch = new MapSqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            TicketItemRequest item = items.get(i);
            String currency = (item.currency() != null && !item.currency().isBlank()) ? item.currency() : "THB";
            batch[i] = new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("brand", item.brand())
                .addValue("model", item.model())
                .addValue("color", item.color())
                .addValue("texture", item.texture())
                .addValue("size", item.size())
                .addValue("factory", item.factory())
                .addValue("qty", item.qty())
                .addValue("qtySqm", item.qtySqm())
                .addValue("rawPrice", item.rawPrice())
                .addValue("rawCurrency", item.rawCurrency())
                .addValue("rawUnit", item.rawUnit())
                .addValue("proposedPrice", item.proposedPrice())
                .addValue("currency", currency)
                .addValue("sortOrder", i);
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.ticket_item
                (ticket_id, brand, model, color, texture, size, factory,
                 qty, qty_sqm, raw_price, raw_currency, raw_unit,
                 proposed_price, currency, sort_order)
            VALUES (:ticketId, :brand, :model, :color, :texture, :size, :factory,
                    :qty, :qtySqm, :rawPrice, :rawCurrency, :rawUnit,
                    :proposedPrice, :currency, :sortOrder)
            """, batch);
    }

    private TicketSummaryDto mapSummary(ResultSet rs) throws SQLException {
        long assignedToRaw = rs.getLong("assigned_to");
        Long assignedToId = rs.wasNull() ? null : assignedToRaw;
        long customerIdRaw = rs.getLong("customer_id");
        Long customerId = rs.wasNull() ? null : customerIdRaw;
        long projectIdRaw = rs.getLong("project_id");
        Long projectId = rs.wasNull() ? null : projectIdRaw;
        long contactIdRaw = rs.getLong("contact_id");
        Long contactId = rs.wasNull() ? null : contactIdRaw;
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
            customerId,
            projectId,
            rs.getString("project_name"),
            contactId,
            rs.getString("contact_name"),
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
