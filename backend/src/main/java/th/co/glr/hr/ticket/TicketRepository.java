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
               t.has_edits,
               t.payment_status, t.fulfillment_status,
               t.sales_stage, t.lost_reason, t.lost_at, t.stage_updated_at,
               t.lifecycle, t.tender_requirement, t.deposit_policy,
               t.deposit_policy_reason, t.entry_channel
          FROM sales.ticket t
          JOIN hr.employee ec ON ec.employee_id = t.created_by
          LEFT JOIN hr.employee ea ON ea.employee_id = t.assigned_to
          LEFT JOIN sales.ticket_item ti ON ti.ticket_id = t.ticket_id
          LEFT JOIN customers.project p ON p.project_id = t.project_id
          LEFT JOIN customers.contact ct ON ct.contact_id = t.contact_id
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
        // V50 lightweight deal start: a deal created without items begins as a DRAFT
        // at the lead stage — product items and the price-request flow come later
        // (editItems then submit). A deal created WITH items enters the price-request
        // flow immediately, exactly as before.
        boolean hasItems = request.items() != null && !request.items().isEmpty();
        String status = hasItems ? TicketStatus.SUBMITTED : TicketStatus.DRAFT;
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.ticket
                (code, title, status, priority, created_by, customer_name, customer_id, project_id, contact_id, note, entry_channel)
            VALUES
                (:code, :title, :status, :priority, :createdBy, :customerName, :customerId, :projectId, :contactId, :note, :entryChannel)
            """,
            new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("title", request.title())
                .addValue("status", status)
                .addValue("priority", priority)
                .addValue("createdBy", actorId)
                .addValue("customerName", request.customerName())
                .addValue("customerId", request.customerId())
                .addValue("projectId", request.projectId())
                .addValue("contactId", request.contactId())
                .addValue("note", request.note())
                .addValue("entryChannel", request.entryChannel() != null && !request.entryChannel().isBlank()
                    ? request.entryChannel() : EntryChannel.DESIGNER_LED),
            keyHolder, new String[]{"ticket_id"});
        long ticketId = keyHolder.getKey().longValue();
        if (hasItems) {
            insertItems(ticketId, request.items());
            addEvent(ticketId, actorId, actorName, TicketEventKind.SUBMITTED, null, TicketStatus.SUBMITTED, null);
        } else {
            addEvent(ticketId, actorId, actorName, TicketEventKind.CREATED, null, TicketStatus.DRAFT, null);
        }
        return ticketId;
    }

    public void addEvent(long ticketId, long actorId, String actorName,
                         String kind, String fromStatus, String toStatus, String message) {
        addEventInternal(ticketId, actorId, actorName, kind, fromStatus, toStatus, message, null);
    }

    public void addEventWithSnapshot(long ticketId, long actorId, String actorName,
                                     String kind, String fromStatus, String toStatus,
                                     String message, String itemSnapshotJson) {
        addEventInternal(ticketId, actorId, actorName, kind, fromStatus, toStatus, message, itemSnapshotJson);
    }

    private void addEventInternal(long ticketId, long actorId, String actorName,
                                   String kind, String fromStatus, String toStatus,
                                   String message, String itemSnapshotJson) {
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

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("ticketId", ticketId)
            .addValue("actorId", actorId)
            .addValue("actorName", actorName)
            .addValue("kind", kind)
            .addValue("fromStatus", fromStatus)
            .addValue("toStatus", toStatus)
            .addValue("message", message);

        if (itemSnapshotJson == null) {
            jdbc.update("""
                INSERT INTO sales.ticket_event
                    (ticket_id, actor_id, actor_name, kind, from_status, to_status, message)
                VALUES (:ticketId, :actorId, :actorName, :kind, :fromStatus, :toStatus, :message)
                """, params);
            return;
        }

        params.addValue("itemSnapshot", itemSnapshotJson);
        jdbc.update("""
            INSERT INTO sales.ticket_event
                (ticket_id, actor_id, actor_name, kind, from_status, to_status, message, item_snapshot)
            VALUES (:ticketId, :actorId, :actorName, :kind, :fromStatus, :toStatus, :message, :itemSnapshot::jsonb)
            """, params);
    }

    public void replaceItems(long ticketId, List<TicketItemRequest> items) {
        jdbc.update("DELETE FROM sales.ticket_item WHERE ticket_id = :id", Map.of("id", ticketId));
        insertItems(ticketId, items);
    }

    // Used by TicketService.editItems: unlike replaceItems (which is for proposePrice's
    // intentional wholesale price replacement), this preserves each item's pricing fields
    // (proposedPrice/approvedPrice/calcedCost/calcedPrice/calcConfigVersion/manualPrice/
    // manualOverrideReason/currency) exactly as TicketService merged them — sales editing
    // descriptive fields must never silently discard import's proposed price or CEO's
    // approved/manual price (2026-07-16 pricing-integrity audit, finding #4).
    @Transactional
    public void replaceItemsPreservingPricing(long ticketId, List<TicketItemDto> items) {
        jdbc.update("DELETE FROM sales.ticket_item WHERE ticket_id = :id", Map.of("id", ticketId));
        if (items.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            TicketItemDto item = items.get(i);
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
                .addValue("unitBasis", item.unitBasis())
                .addValue("rawPrice", item.rawPrice())
                .addValue("rawCurrency", item.rawCurrency())
                .addValue("rawUnit", item.rawUnit())
                .addValue("proposedPrice", item.proposedPrice())
                .addValue("approvedPrice", item.approvedPrice())
                .addValue("currency", item.currency())
                .addValue("sortOrder", i)
                .addValue("calcedCost", item.calcedCost())
                .addValue("calcedPrice", item.calcedPrice())
                .addValue("calcConfigVersion", item.calcConfigVersion())
                .addValue("manualPrice", item.manualPrice())
                .addValue("manualOverrideReason", item.manualOverrideReason());
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.ticket_item
                (ticket_id, brand, model, color, texture, size, factory,
                 qty, qty_sqm, unit_basis, raw_price, raw_currency, raw_unit,
                 proposed_price, approved_price, currency, sort_order,
                 calced_cost, calced_price, calc_config_version,
                 manual_price, manual_override_reason)
            VALUES (:ticketId, :brand, :model, :color, :texture, :size, :factory,
                    :qty, :qtySqm, :unitBasis, :rawPrice, :rawCurrency, :rawUnit,
                    :proposedPrice, :approvedPrice, :currency, :sortOrder,
                    :calcedCost, :calcedPrice, :calcConfigVersion,
                    :manualPrice, :manualOverrideReason)
            """, batch);
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

    // --- quotation snapshot (V49: freeze issued quotations at issue time) ---

    // Frozen customer/project header for an issued quotation. All fields nullable —
    // pre-V49 quotations have no header snapshot and the caller must fall back to a
    // live customer/ticket lookup.
    public record QuotationHeaderSnapshot(String customerName, String customerAddress,
                                          String customerTaxId, String customerPhone,
                                          String projectName) {}

    // Called once, in the same transaction as createQuotation, from
    // TicketService.generateQuotation. Only priced items (approvedPrice != null) are
    // snapshotted — unpriced items never contribute to the rendered quotation, mirroring
    // the total-amount calculation in TicketService.generateQuotation itself.
    public void insertQuotationItems(long quotationId, List<TicketItemDto> items) {
        List<TicketItemDto> priced = items.stream().filter(it -> it.approvedPrice() != null).toList();
        if (priced.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[priced.size()];
        for (int i = 0; i < priced.size(); i++) {
            TicketItemDto item = priced.get(i);
            BigDecimal qty = item.qty() != null ? item.qty() : BigDecimal.ONE;
            BigDecimal amount = item.approvedPrice().multiply(qty);
            batch[i] = new MapSqlParameterSource()
                .addValue("quotationId", quotationId)
                .addValue("seq", i + 1)
                .addValue("brand", item.brand())
                .addValue("model", item.model())
                .addValue("color", item.color())
                .addValue("texture", item.texture())
                .addValue("size", item.size())
                .addValue("qty", item.qty())
                .addValue("qtySqm", item.qtySqm())
                .addValue("unitBasis", item.unitBasis())
                .addValue("rawUnit", item.rawUnit())
                .addValue("unitPrice", item.approvedPrice())
                .addValue("amount", amount);
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.quotation_item
                (quotation_id, seq, brand, model, color, texture, size,
                 qty, qty_sqm, unit_basis, raw_unit, unit_price, amount)
            VALUES (:quotationId, :seq, :brand, :model, :color, :texture, :size,
                    :qty, :qtySqm, :unitBasis, :rawUnit, :unitPrice, :amount)
            """, batch);
    }

    public void updateQuotationHeader(long quotationId, String customerName, String customerAddress,
                                      String customerTaxId, String customerPhone, String projectName) {
        jdbc.update("""
            UPDATE sales.quotation
               SET customer_name    = :customerName,
                   customer_address = :customerAddress,
                   customer_tax_id  = :customerTaxId,
                   customer_phone   = :customerPhone,
                   project_name     = :projectName
             WHERE quotation_id = :id
            """,
            new MapSqlParameterSource()
                .addValue("customerName", customerName)
                .addValue("customerAddress", customerAddress)
                .addValue("customerTaxId", customerTaxId)
                .addValue("customerPhone", customerPhone)
                .addValue("projectName", projectName)
                .addValue("id", quotationId));
    }

    // Empty list = no snapshot exists for this quotation (either it predates V49, or
    // generateQuotation ran with zero priced items) — the caller falls back to live data.
    public List<TicketItemDto> findQuotationItemsByQuotationId(long quotationId, long ticketId) {
        return jdbc.query("""
            SELECT quotation_item_id, seq, brand, model, color, texture, size,
                   qty, qty_sqm, unit_basis, raw_unit, unit_price
              FROM sales.quotation_item
             WHERE quotation_id = :id
             ORDER BY seq
            """,
            Map.of("id", quotationId),
            (rs, rowNum) -> new TicketItemDto(
                rs.getLong("quotation_item_id"),
                ticketId,
                rs.getString("brand"),
                rs.getString("model"),
                rs.getString("color"),
                rs.getString("texture"),
                rs.getString("size"),
                null, // factory — not part of the render, not snapshotted
                rs.getBigDecimal("qty"),
                rs.getBigDecimal("qty_sqm"),
                null, null, // rawPrice, rawCurrency — not used by QuotationRenderer
                rs.getString("raw_unit"),
                null, // proposedPrice — meaningless post-issue
                rs.getBigDecimal("unit_price"), // approvedPrice = the frozen unit price
                null, // currency — not snapshotted (unused by QuotationRenderer)
                rs.getInt("seq"),
                null, null, null, // calcedCost, calcedPrice, calcConfigVersion
                rs.getString("unit_basis"),
                null, null // manualPrice, manualOverrideReason
            ));
    }

    public Optional<QuotationHeaderSnapshot> findQuotationHeaderSnapshot(long quotationId) {
        try {
            QuotationHeaderSnapshot snap = jdbc.queryForObject("""
                SELECT customer_name, customer_address, customer_tax_id, customer_phone, project_name
                  FROM sales.quotation
                 WHERE quotation_id = :id
                """,
                Map.of("id", quotationId),
                (rs, rowNum) -> new QuotationHeaderSnapshot(
                    rs.getString("customer_name"),
                    rs.getString("customer_address"),
                    rs.getString("customer_tax_id"),
                    rs.getString("customer_phone"),
                    rs.getString("project_name")
                ));
            return Optional.ofNullable(snap);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
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
                   qty, qty_sqm, unit_basis, raw_price, raw_currency, raw_unit,
                   proposed_price, approved_price, currency, sort_order,
                   calced_cost, calced_price, calc_config_version,
                   manual_price, manual_override_reason
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
                    calcConfigVersion,
                    rs.getString("unit_basis"),
                    rs.getBigDecimal("manual_price"),
                    rs.getString("manual_override_reason")
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
                   from_status, to_status, message, created_at,
                   item_snapshot::text AS item_snapshot
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
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("item_snapshot")
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
            String unitBasis = (item.unitBasis() != null && !item.unitBasis().isBlank())
                ? item.unitBasis() : "PIECE";
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
                .addValue("unitBasis", unitBasis)
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
                 qty, qty_sqm, unit_basis, raw_price, raw_currency, raw_unit,
                 proposed_price, currency, sort_order)
            VALUES (:ticketId, :brand, :model, :color, :texture, :size, :factory,
                    :qty, :qtySqm, :unitBasis, :rawPrice, :rawCurrency, :rawUnit,
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
            rs.getBoolean("has_edits"),
            rs.getString("payment_status"),
            rs.getString("fulfillment_status"),
            rs.getString("sales_stage"),
            rs.getString("lost_reason"),
            rs.getTimestamp("lost_at") != null ? rs.getTimestamp("lost_at").toInstant() : null,
            rs.getTimestamp("stage_updated_at").toInstant(),
            rs.getString("lifecycle"),
            rs.getString("tender_requirement"),
            rs.getString("deposit_policy"),
            rs.getString("deposit_policy_reason"),
            rs.getString("entry_channel")
        );
    }

    public void updateSalesStage(long ticketId, String stage) {
        jdbc.update(
            "UPDATE sales.ticket SET sales_stage = :s, stage_updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("s", stage).addValue("id", ticketId));
    }

    public void markDealLost(long ticketId, String reason) {
        jdbc.update(
            "UPDATE sales.ticket SET lost_reason = :r, lost_at = now(), lifecycle = :lifecycle, stage_updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource()
                .addValue("r", reason)
                .addValue("lifecycle", DealLifecycle.CLOSED_LOST)
                .addValue("id", ticketId));
    }

    public void clearDealLost(long ticketId) {
        jdbc.update(
            "UPDATE sales.ticket SET lost_reason = NULL, lost_at = NULL, lifecycle = :lifecycle, stage_updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource()
                .addValue("lifecycle", DealLifecycle.ACTIVE)
                .addValue("id", ticketId));
    }

    public void updateLifecycle(long ticketId, String lifecycle) {
        jdbc.update(
            "UPDATE sales.ticket SET lifecycle = :lifecycle, updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("lifecycle", lifecycle).addValue("id", ticketId));
    }

    public void updateTenderRequirement(long ticketId, String value) {
        jdbc.update(
            "UPDATE sales.ticket SET tender_requirement = :value, updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("value", value).addValue("id", ticketId));
    }

    public void updateDepositPolicy(long ticketId, String policy, String reason, long setBy) {
        jdbc.update("""
            UPDATE sales.ticket
               SET deposit_policy = :policy,
                   deposit_policy_reason = :reason,
                   deposit_policy_set_by = :setBy,
                   updated_at = now()
             WHERE ticket_id = :id
            """,
            new MapSqlParameterSource()
                .addValue("policy", policy)
                .addValue("reason", reason)
                .addValue("setBy", setBy)
                .addValue("id", ticketId));
    }

    public void updateEntryChannel(long ticketId, String value) {
        jdbc.update(
            "UPDATE sales.ticket SET entry_channel = :value, updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("value", value).addValue("id", ticketId));
    }

    public void updatePaymentStatus(long ticketId, String paymentStatus) {
        jdbc.update(
            "UPDATE sales.ticket SET payment_status = :s WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("s", paymentStatus).addValue("id", ticketId));
    }

    public void updateFulfillmentStatus(long ticketId, String fulfillmentStatus) {
        jdbc.update(
            "UPDATE sales.ticket SET fulfillment_status = :s WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("s", fulfillmentStatus).addValue("id", ticketId));
    }

    public void updateItemManualPrice(long itemId, BigDecimal manualPrice, String reason) {
        jdbc.update("""
            UPDATE sales.ticket_item
               SET manual_price           = :manualPrice,
                   manual_override_reason = :reason,
                   proposed_price         = :manualPrice
             WHERE item_id = :id
            """,
            new MapSqlParameterSource()
                .addValue("manualPrice", manualPrice)
                .addValue("reason", reason)
                .addValue("id", itemId));
    }

    public void setHasEdits(long ticketId, boolean value) {
        jdbc.update(
            "UPDATE sales.ticket SET has_edits = :v, updated_at = now() WHERE ticket_id = :id",
            Map.of("id", ticketId, "v", value));
    }
}
