package th.co.glr.hr.ticket;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.Instant;
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
               t.deposit_policy_reason, t.entry_channel,
               t.billing_date, t.due_date, t.credit_term_days,
               t.last_follow_up_at, t.next_follow_up_at,
               t.close_confirmed_at,
               NULLIF(TRIM(CONCAT_WS(' ', ecc.first_name_th, ecc.last_name_th)), '') AS close_confirmed_by_name,
               t.cancel_reason, t.cancelled_at
          FROM sales.ticket t
          JOIN hr.employee ec ON ec.employee_id = t.created_by
          LEFT JOIN hr.employee ea ON ea.employee_id = t.assigned_to
          LEFT JOIN hr.employee ecc ON ecc.employee_id = t.close_confirmed_by
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
                      p.name, ct.first_name, ct.last_name,
                      ecc.first_name_th, ecc.last_name_th
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
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> enrichSummary(mapSummary(rs)));
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
            ? request.priority() : Priority.DEFAULT;
        // Every deal begins as a DRAFT at the lead stage, regardless of whether
        // products were attached at creation time. Items attached here are
        // preliminary deal products only — pricing does not start until a
        // PricingRequest is created and submitted against this ticket
        // (see th.co.glr.hr.pricingrequest.PricingRequestService).
        boolean hasItems = request.items() != null && !request.items().isEmpty();
        String status = TicketStatus.DRAFT;
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
        if (hasItems) insertItems(ticketId, request.items());
        addEvent(ticketId, actorId, actorName, TicketEventKind.CREATED, null, TicketStatus.DRAFT, null);
        return ticketId;
    }

    public void addEvent(long ticketId, long actorId, String actorName,
                         String kind, String fromStatus, String toStatus, String message) {
        addEventInternal(ticketId, actorId, actorName, kind, fromStatus, toStatus, message, null,
            null, null);
    }

    public void addEventWithSnapshot(long ticketId, long actorId, String actorName,
                                     String kind, String fromStatus, String toStatus,
                                     String message, String itemSnapshotJson) {
        addEventInternal(ticketId, actorId, actorName, kind, fromStatus, toStatus, message,
            itemSnapshotJson, null, null);
    }

    /**
     * Record an event AND the document it produced (V58), so "which receipt did
     * this payment event write" is a query rather than a timestamp match.
     */
    public void addEventWithDocument(long ticketId, long actorId, String actorName,
                                     String kind, String fromStatus, String toStatus,
                                     String message, String documentType, Long documentId) {
        addEventInternal(ticketId, actorId, actorName, kind, fromStatus, toStatus, message, null,
            documentType, documentId);
    }

    private void addEventInternal(long ticketId, long actorId, String actorName,
                                   String kind, String fromStatus, String toStatus,
                                   String message, String itemSnapshotJson,
                                   String documentType, Long documentId) {
        if (toStatus != null) {
            // toStatus doubles as the event's "to" timeline label AND, for genuine
            // status transitions, the ticket's new status. Deal-pipeline events
            // (STAGE_CHANGED, MARKED_LOST, ON_HOLD, POLICY_CHANGED, …) reuse the same
            // from/to slots to carry a sales_stage / lifecycle value — write it onto
            // sales.ticket.status ONLY when it is a real ticket status, otherwise a
            // stage code (e.g. QUOTE_BUYER) would violate chk_ticket_status. The
            // updated_at / closed_at / pickup side-effects still fire for every event.
            boolean writeStatus = TicketStatus.isValid(toStatus);
            boolean closing = writeStatus
                && (TicketStatus.CLOSED.equals(toStatus) || TicketStatus.CANCELLED.equals(toStatus));
            boolean isPickup = TicketEventKind.PICKED_UP.equals(kind);
            jdbc.update("""
                UPDATE sales.ticket
                   SET status = CASE WHEN :writeStatus THEN :status ELSE status END,
                       updated_at = now(),
                       closed_at = CASE WHEN :closing THEN now() ELSE closed_at END,
                       assigned_to = CASE WHEN :isPickup THEN :actorId ELSE assigned_to END
                 WHERE ticket_id = :id
                """,
                new MapSqlParameterSource()
                    .addValue("status", toStatus)
                    .addValue("writeStatus", writeStatus)
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
            .addValue("message", message)
            .addValue("documentType", documentType)
            .addValue("documentId", documentId);

        if (itemSnapshotJson == null) {
            jdbc.update("""
                INSERT INTO sales.ticket_event
                    (ticket_id, actor_id, actor_name, kind, from_status, to_status, message,
                     related_document_type, related_document_id)
                VALUES (:ticketId, :actorId, :actorName, :kind, :fromStatus, :toStatus, :message,
                        :documentType, :documentId)
                """, params);
            return;
        }

        params.addValue("itemSnapshot", itemSnapshotJson);
        jdbc.update("""
            INSERT INTO sales.ticket_event
                (ticket_id, actor_id, actor_name, kind, from_status, to_status, message, item_snapshot,
                 related_document_type, related_document_id)
            VALUES (:ticketId, :actorId, :actorName, :kind, :fromStatus, :toStatus, :message,
                    :itemSnapshot::jsonb, :documentType, :documentId)
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
                .addValue("manualOverrideReason", item.manualOverrideReason())
                .addValue("qtyDelivered", item.qtyDelivered())
                .addValue("qtyFromStock", item.qtyFromStock())
                .addValue("stockNote", item.stockNote());
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.ticket_item
                (ticket_id, brand, model, color, texture, size, factory,
                 qty, qty_sqm, unit_basis, raw_price, raw_currency, raw_unit,
                 proposed_price, approved_price, currency, sort_order,
                 calced_cost, calced_price, calc_config_version,
                 manual_price, manual_override_reason, qty_delivered, qty_from_stock, stock_note)
            VALUES (:ticketId, :brand, :model, :color, :texture, :size, :factory,
                    :qty, :qtySqm, :unitBasis, :rawPrice, :rawCurrency, :rawUnit,
                    :proposedPrice, :approvedPrice, :currency, :sortOrder,
                    :calcedCost, :calcedPrice, :calcConfigVersion,
                    :manualPrice, :manualOverrideReason, :qtyDelivered, :qtyFromStock, :stockNote)
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
        return createQuotation(ticketId, number, issuedById, totalAmount, QuotationRecipient.UNSPECIFIED,
            null, null, null, null, null);
    }

    @Transactional
    public QuotationDto createQuotation(long ticketId, String number, long issuedById, BigDecimal totalAmount,
                                        String recipientType, String recipientLabel, String paymentTerms,
                                        String leadTime, String deliveryTerms, LocalDate validityDate) {
        List<Long> parentIds = jdbc.query("""
            SELECT quotation_id
              FROM sales.quotation
             WHERE ticket_id = :ticketId AND recipient_type = :recipientType
             ORDER BY quotation_version DESC, quotation_id DESC
             LIMIT 1
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("recipientType", recipientType),
            (rs, rowNum) -> rs.getLong("quotation_id"));
        Long parentQuotationId = parentIds.isEmpty() ? null : parentIds.get(0);

        // Supersede only this recipient chain; accepted/rejected/cancelled rows remain as history.
        jdbc.update("""
            UPDATE sales.quotation
               SET doc_status = 'SUPERSEDED'
             WHERE ticket_id = :ticketId
               AND recipient_type = :recipientType
               AND doc_status NOT IN ('SUPERSEDED','ACCEPTED','REJECTED','CANCELLED')
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("recipientType", recipientType));

        Integer maxVersion = jdbc.queryForObject("""
            SELECT COALESCE(MAX(quotation_version), 0)
              FROM sales.quotation
             WHERE ticket_id = :ticketId AND recipient_type = :recipientType
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("recipientType", recipientType),
            Integer.class);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;

        // generateQuotation() issues and transitions the ticket to QUOTATION_ISSUED in one
        // step (no separate draft phase like deposit notices), so the row starts ISSUED.
        jdbc.update("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, total_amount, currency, quotation_version, doc_status,
                 recipient_type, recipient_label, payment_terms, lead_time, delivery_terms,
                 validity_date, parent_quotation_id)
            VALUES (:ticketId, :number, :issuedBy, :totalAmount, 'THB', :version, 'ISSUED',
                    :recipientType, :recipientLabel, :paymentTerms, :leadTime, :deliveryTerms,
                    :validityDate, :parentQuotationId)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("number", number)
                .addValue("issuedBy", issuedById)
                .addValue("totalAmount", totalAmount)
                .addValue("version", nextVersion)
                .addValue("recipientType", recipientType)
                .addValue("recipientLabel", recipientLabel)
                .addValue("paymentTerms", paymentTerms)
                .addValue("leadTime", leadTime)
                .addValue("deliveryTerms", deliveryTerms)
                .addValue("validityDate", validityDate)
                .addValue("parentQuotationId", parentQuotationId));

        int versionToFind = nextVersion;
        return findQuotationsByTicketId(ticketId).stream()
            .filter(q -> q.quotationVersion() == versionToFind && recipientType.equals(q.recipientType()))
            .findFirst()
            .orElseThrow();
    }

    public boolean markQuotationStatus(long ticketId, long quotationId, String status) {
        String timestampAssignment = switch (status) {
            case QuotationStatus.SENT -> "sent_at = now(),";
            case QuotationStatus.ACCEPTED -> "accepted_at = now(),";
            case QuotationStatus.REJECTED -> "rejected_at = now(),";
            default -> "";
        };
        int rows = jdbc.update("""
            UPDATE sales.quotation
               SET doc_status = :status,
                   %s
                   issued_at = issued_at
             WHERE quotation_id = :quotationId AND ticket_id = :ticketId
            """.formatted(timestampAssignment),
            new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("quotationId", quotationId)
                .addValue("ticketId", ticketId));
        return rows > 0;
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
                          p.name, ct.first_name, ct.last_name,
                          ecc.first_name_th, ecc.last_name_th
                """,
                Map.of("id", id), (rs, rowNum) -> mapSummary(rs));
            return Optional.ofNullable(summary).map(this::enrichSummary);
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
                   manual_price, manual_override_reason,
                   qty_delivered, qty_from_stock, stock_note
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
                    rs.getString("manual_override_reason"),
                    rs.getBigDecimal("qty_delivered"),
                    rs.getBigDecimal("qty_from_stock"),
                    rs.getString("stock_note")
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
                   q.quotation_version, q.doc_status,
                   q.recipient_type, q.recipient_label, q.payment_terms, q.lead_time,
                   q.delivery_terms, q.validity_date, q.sent_at, q.accepted_at,
                   q.rejected_at, q.parent_quotation_id
              FROM sales.quotation q
              JOIN hr.employee e ON e.employee_id = q.issued_by
             WHERE q.ticket_id = :id
             ORDER BY q.issued_at DESC, q.quotation_id DESC
            """,
            Map.of("id", ticketId),
            (rs, rowNum) -> {
                Timestamp sentAt = rs.getTimestamp("sent_at");
                Timestamp acceptedAt = rs.getTimestamp("accepted_at");
                Timestamp rejectedAt = rs.getTimestamp("rejected_at");
                long parentRaw = rs.getLong("parent_quotation_id");
                Long parentQuotationId = rs.wasNull() ? null : parentRaw;
                return new QuotationDto(
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
                    rs.getString("doc_status"),
                    rs.getString("recipient_type"),
                    rs.getString("recipient_label"),
                    rs.getString("payment_terms"),
                    rs.getString("lead_time"),
                    rs.getString("delivery_terms"),
                    rs.getObject("validity_date", LocalDate.class),
                    sentAt != null ? sentAt.toInstant() : null,
                    acceptedAt != null ? acceptedAt.toInstant() : null,
                    rejectedAt != null ? rejectedAt.toInstant() : null,
                    parentQuotationId
                );
            });
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
            rs.getString("entry_channel"),
            rs.getObject("billing_date", LocalDate.class),
            rs.getObject("due_date", LocalDate.class),
            (Integer) rs.getObject("credit_term_days"),
            rs.getObject("last_follow_up_at", LocalDate.class),
            rs.getObject("next_follow_up_at", LocalDate.class),
            PaymentStage.NOT_REQUIRED,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            false,
            rs.getTimestamp("close_confirmed_at") != null
                ? rs.getTimestamp("close_confirmed_at").toInstant() : null,
            rs.getString("close_confirmed_by_name"),
            false,
            rs.getString("cancel_reason"),
            rs.getTimestamp("cancelled_at") != null ? rs.getTimestamp("cancelled_at").toInstant() : null
        );
    }

    private TicketSummaryDto enrichSummary(TicketSummaryDto s) {
        BigDecimal payable = payableAmount(s.id());
        BigDecimal paid = sumPaid(s.id());
        BigDecimal outstanding = payable.subtract(paid);
        if (outstanding.signum() < 0) {
            outstanding = BigDecimal.ZERO;
        }
        boolean balanceReceipt = hasBalanceReceipt(s.id());
        String stage = derivePaymentStage(s, payable, paid, outstanding, balanceReceipt);
        boolean overdue = s.dueDate() != null && s.dueDate().isBefore(LocalDate.now()) && outstanding.signum() > 0;
        return new TicketSummaryDto(
            s.id(), s.code(), s.type(), s.title(), s.status(), s.priority(),
            s.createdById(), s.createdByName(), s.assignedToId(), s.assignedToName(),
            s.customerName(), s.customerId(), s.projectId(), s.projectName(),
            s.contactId(), s.contactName(), s.note(),
            s.createdAt(), s.updatedAt(), s.closedAt(), s.itemCount(), s.hasEdits(),
            s.paymentStatus(), s.fulfillmentStatus(), s.salesStage(), s.lostReason(), s.lostAt(),
            s.stageUpdatedAt(), s.lifecycle(), s.tenderRequirement(), s.depositPolicy(),
            s.depositPolicyReason(), s.entryChannel(), s.billingDate(), s.dueDate(),
            s.creditTermDays(), s.lastFollowUpAt(), s.nextFollowUpAt(),
            stage, payable, paid, outstanding, overdue,
            s.closeConfirmedAt(), s.closeConfirmedByName(), hasInvoiceAttachment(s.id()),
            s.cancelReason(), s.cancelledAt());
    }

    /**
     * An INVOICE document is on file. The invoice is produced by an external
     * system and uploaded here, so its presence — not its contents — is what the
     * close gate can check.
     */
    public boolean hasInvoiceAttachment(long ticketId) {
        Integer n = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.attachment
             WHERE ticket_id = :id AND attach_type = 'INVOICE'
            """, Map.of("id", ticketId), Integer.class);
        return n != null && n > 0;
    }

    /** ฝ่ายบัญชี signs off that the deal is ready to close; CEO verification still required. */
    public void confirmClose(long ticketId, long actorId) {
        jdbc.update("""
            UPDATE sales.ticket
               SET close_confirmed_by = :actor, close_confirmed_at = now(), updated_at = now()
             WHERE ticket_id = :id
            """, Map.of("id", ticketId, "actor", actorId));
    }

    /** Withdraw the confirmation so the deal leaves the CEO's verification queue. */
    public void clearCloseConfirmation(long ticketId) {
        jdbc.update("""
            UPDATE sales.ticket
               SET close_confirmed_by = NULL, close_confirmed_at = NULL, updated_at = now()
             WHERE ticket_id = :id
            """, Map.of("id", ticketId));
    }

    private String derivePaymentStage(TicketSummaryDto s, BigDecimal payable, BigDecimal paid,
                                      BigDecimal outstanding, boolean balanceReceipt) {
        if (payable.signum() <= 0) {
            return PaymentStage.NOT_REQUIRED;
        }
        if (paid.compareTo(payable) >= 0) {
            return PaymentStage.FULLY_PAID;
        }
        if (paid.signum() > 0) {
            return balanceReceipt ? PaymentStage.PARTIALLY_PAID : PaymentStage.DEPOSIT_RECEIVED;
        }
        if (!DepositPolicy.bypassesDepositNotice(s.depositPolicy())
                && ("CUSTOMER_CONFIRMED".equals(s.paymentStatus())
                    || "DEPOSIT_NOTICE_ISSUED".equals(s.paymentStatus()))) {
            return PaymentStage.DEPOSIT_PENDING;
        }
        return outstanding.signum() > 0 ? PaymentStage.BALANCE_PENDING : PaymentStage.NOT_REQUIRED;
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

    /** Record why the opportunity went away. Lifecycle is set separately by the caller. */
    public void cancelDeal(long ticketId, String reason) {
        jdbc.update(
            "UPDATE sales.ticket SET cancel_reason = :r, cancelled_at = now(), updated_at = now() WHERE ticket_id = :id",
            new MapSqlParameterSource()
                .addValue("r", reason)
                .addValue("id", ticketId));
    }

    /**
     * Reopen a lost deal — **without erasing why it was lost**.
     *
     * This used to null lost_reason/lost_at, leaving a row indistinguishable
     * from one that was never lost; the reason survived only inside a Thai
     * free-text event message. The values now stay readable and reopened_at /
     * reopen_count record that the deal came back, so "reopened deals we
     * previously lost on PRICE" is a plain query.
     *
     * lifecycle=ACTIVE is what makes the deal live again; `lost_reason != null`
     * no longer implies "currently lost" — check the lifecycle for that.
     */
    public void clearDealLost(long ticketId) {
        jdbc.update("""
            UPDATE sales.ticket
               SET lifecycle = :lifecycle,
                   reopened_at = now(),
                   reopen_count = reopen_count + 1,
                   stage_updated_at = now()
             WHERE ticket_id = :id
            """,
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

    public long insertPaymentReceipt(long ticketId, String kind, BigDecimal amount, long recordedBy,
                                     Instant receivedAt, String note, Long depositNoticeId, String receiptRef) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.payment_receipt
                (ticket_id, kind, amount, currency, received_at, recorded_by, note, deposit_notice_id, receipt_ref)
            VALUES
                (:ticketId, :kind, :amount, 'THB', COALESCE(CAST(:receivedAt AS timestamptz), now()), :recordedBy, :note,
                 :depositNoticeId, :receiptRef)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("kind", kind)
                .addValue("amount", amount)
                .addValue("receivedAt", receivedAt == null ? null : Timestamp.from(receivedAt))
                .addValue("recordedBy", recordedBy)
                .addValue("note", note)
                .addValue("depositNoticeId", depositNoticeId)
                .addValue("receiptRef", receiptRef),
            keys, new String[]{"receipt_id"});
        return keys.getKey().longValue();
    }

    public List<PaymentReceiptDto> findReceiptsByTicket(long ticketId) {
        return jdbc.query("""
            SELECT r.receipt_id, r.ticket_id, r.kind, r.amount, r.currency,
                   r.received_at, r.recorded_by,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS recorded_by_name,
                   r.note, r.deposit_notice_id, r.receipt_ref, r.created_at
              FROM sales.payment_receipt r
              JOIN hr.employee e ON e.employee_id = r.recorded_by
             WHERE r.ticket_id = :ticketId
             ORDER BY r.received_at ASC, r.receipt_id ASC
            """,
            Map.of("ticketId", ticketId),
            (rs, rowNum) -> {
                long depositNoticeRaw = rs.getLong("deposit_notice_id");
                Long depositNoticeId = rs.wasNull() ? null : depositNoticeRaw;
                return new PaymentReceiptDto(
                    rs.getLong("receipt_id"),
                    rs.getLong("ticket_id"),
                    rs.getString("kind"),
                    rs.getBigDecimal("amount"),
                    rs.getString("currency"),
                    rs.getTimestamp("received_at").toInstant(),
                    rs.getLong("recorded_by"),
                    rs.getString("recorded_by_name"),
                    rs.getString("note"),
                    depositNoticeId,
                    rs.getString("receipt_ref"),
                    rs.getTimestamp("created_at").toInstant()
                );
            });
    }

    public BigDecimal sumPaid(long ticketId) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(SUM(CASE WHEN kind = 'ADJUSTMENT' THEN -amount ELSE amount END), 0)
              FROM sales.payment_receipt
             WHERE ticket_id = :ticketId
            """, Map.of("ticketId", ticketId), BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    public boolean hasBalanceReceipt(long ticketId) {
        Boolean value = jdbc.queryForObject("""
            SELECT EXISTS(
                SELECT 1 FROM sales.payment_receipt
                 WHERE ticket_id = :ticketId AND kind = 'BALANCE'
            )
            """, Map.of("ticketId", ticketId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    public BigDecimal payableAmount(long ticketId) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(
                (SELECT q.total_amount
                   FROM sales.quotation q
                  WHERE q.ticket_id = :ticketId AND q.doc_status = 'ACCEPTED'
                  ORDER BY CASE q.recipient_type
                               WHEN 'BUYER' THEN 0
                               WHEN 'OWNER' THEN 1
                               ELSE 2
                           END,
                           q.accepted_at DESC NULLS LAST,
                           q.issued_at DESC,
                           q.quotation_id DESC
                  LIMIT 1),
                (SELECT q.total_amount
                   FROM sales.quotation q
                  WHERE q.ticket_id = :ticketId AND q.doc_status IN ('ISSUED','SENT')
                  ORDER BY CASE q.recipient_type
                               WHEN 'BUYER' THEN 0
                               WHEN 'OWNER' THEN 1
                               ELSE 2
                           END,
                           q.issued_at DESC,
                           q.quotation_id DESC
                  LIMIT 1),
                (SELECT d.total_payable
                   FROM sales.deposit_notice d
                  WHERE d.ticket_id = :ticketId AND d.status = 'ISSUED'
                  ORDER BY d.version DESC, d.deposit_notice_id DESC
                  LIMIT 1),
                (SELECT COALESCE(SUM(COALESCE(ti.approved_price, 0) * ti.qty), 0)
                   FROM sales.ticket_item ti
                  WHERE ti.ticket_id = :ticketId),
                0
            )
            """, Map.of("ticketId", ticketId), BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    public record DepositNoticePaymentInfo(long id, BigDecimal depositAmount, BigDecimal totalPayable) {}

    public Optional<DepositNoticePaymentInfo> latestIssuedDepositNotice(long ticketId) {
        List<DepositNoticePaymentInfo> rows = jdbc.query("""
            SELECT deposit_notice_id, deposit_amount, total_payable
              FROM sales.deposit_notice
             WHERE ticket_id = :ticketId AND status = 'ISSUED'
             ORDER BY version DESC, deposit_notice_id DESC
             LIMIT 1
            """, Map.of("ticketId", ticketId),
            (rs, rowNum) -> new DepositNoticePaymentInfo(
                rs.getLong("deposit_notice_id"),
                rs.getBigDecimal("deposit_amount"),
                rs.getBigDecimal("total_payable")
            ));
        return rows.stream().findFirst();
    }

    public void updateBilling(long ticketId, LocalDate billingDate, LocalDate dueDate, Integer creditTermDays,
                              LocalDate lastFollowUpAt, LocalDate nextFollowUpAt) {
        jdbc.update("""
            UPDATE sales.ticket
               SET billing_date = :billingDate,
                   due_date = :dueDate,
                   credit_term_days = :creditTermDays,
                   last_follow_up_at = :lastFollowUpAt,
                   next_follow_up_at = :nextFollowUpAt,
                   updated_at = now()
             WHERE ticket_id = :ticketId
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("billingDate", billingDate)
                .addValue("dueDate", dueDate)
                .addValue("creditTermDays", creditTermDays)
                .addValue("lastFollowUpAt", lastFollowUpAt)
                .addValue("nextFollowUpAt", nextFollowUpAt));
    }

    @Transactional
    public void reserveStock(long ticketId, List<StockReservationRequest.Line> lines) {
        MapSqlParameterSource[] batch = new MapSqlParameterSource[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            StockReservationRequest.Line line = lines.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("itemId", line.itemId())
                .addValue("qtyFromStock", line.qtyFromStock())
                .addValue("note", line.note());
        }
        jdbc.batchUpdate("""
            UPDATE sales.ticket_item
               SET qty_from_stock = :qtyFromStock,
                   stock_note = :note
             WHERE ticket_id = :ticketId AND item_id = :itemId
            """, batch);
    }

    @Transactional
    public long insertDeliveryRecord(long ticketId, String source, long deliveredBy, String note,
                                     List<RecordDeliveryRequest.Line> lines) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.delivery_record (ticket_id, source, delivered_by, note)
            VALUES (:ticketId, :source, :deliveredBy, :note)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("source", source)
                .addValue("deliveredBy", deliveredBy)
                .addValue("note", note),
            keyHolder,
            new String[] {"delivery_id"});
        long deliveryId = keyHolder.getKey().longValue();
        for (RecordDeliveryRequest.Line line : lines) {
            jdbc.update("""
                INSERT INTO sales.delivery_record_item (delivery_id, item_id, qty)
                VALUES (:deliveryId, :itemId, :qty)
                """,
                new MapSqlParameterSource()
                    .addValue("deliveryId", deliveryId)
                    .addValue("itemId", line.itemId())
                    .addValue("qty", line.qty()));
            int updated = jdbc.update("""
                UPDATE sales.ticket_item
                   SET qty_delivered = qty_delivered + :qty
                 WHERE ticket_id = :ticketId
                   AND item_id = :itemId
                   AND qty_delivered + :qty <= qty
                """,
                new MapSqlParameterSource()
                    .addValue("ticketId", ticketId)
                    .addValue("itemId", line.itemId())
                    .addValue("qty", line.qty()));
            if (updated != 1) {
                throw new org.springframework.dao.DataIntegrityViolationException("Delivery exceeds ordered quantity");
            }
        }
        return deliveryId;
    }

    public List<DeliveryRecordDto> findDeliveriesByTicket(long ticketId) {
        List<DeliveryRecordDto> records = jdbc.query("""
            SELECT dr.delivery_id, dr.ticket_id, dr.source, dr.delivered_at, dr.delivered_by,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS delivered_by_name,
                   dr.note, dr.created_at
              FROM sales.delivery_record dr
              JOIN hr.employee e ON e.employee_id = dr.delivered_by
             WHERE dr.ticket_id = :ticketId
             ORDER BY dr.delivered_at ASC, dr.delivery_id ASC
            """,
            Map.of("ticketId", ticketId),
            (rs, rowNum) -> new DeliveryRecordDto(
                rs.getLong("delivery_id"),
                rs.getLong("ticket_id"),
                rs.getString("source"),
                rs.getTimestamp("delivered_at").toInstant(),
                rs.getLong("delivered_by"),
                rs.getString("delivered_by_name"),
                rs.getString("note"),
                rs.getTimestamp("created_at").toInstant(),
                findDeliveryItems(rs.getLong("delivery_id"))
            ));
        return records;
    }

    private List<DeliveryRecordItemDto> findDeliveryItems(long deliveryId) {
        return jdbc.query("""
            SELECT delivery_item_id, item_id, qty
              FROM sales.delivery_record_item
             WHERE delivery_id = :deliveryId
             ORDER BY delivery_item_id
            """,
            Map.of("deliveryId", deliveryId),
            (rs, rowNum) -> new DeliveryRecordItemDto(
                rs.getLong("delivery_item_id"),
                rs.getLong("item_id"),
                rs.getBigDecimal("qty")
            ));
    }

    public BigDecimal sumOrdered(long ticketId) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(SUM(qty), 0)
              FROM sales.ticket_item
             WHERE ticket_id = :ticketId
            """, Map.of("ticketId", ticketId), BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    public BigDecimal sumDelivered(long ticketId) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(SUM(qty_delivered), 0)
              FROM sales.ticket_item
             WHERE ticket_id = :ticketId
            """, Map.of("ticketId", ticketId), BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    public boolean allLinesFullyDelivered(long ticketId) {
        Boolean value = jdbc.queryForObject("""
            SELECT COALESCE(bool_and(qty_delivered >= qty), false)
              FROM sales.ticket_item
             WHERE ticket_id = :ticketId
            """, Map.of("ticketId", ticketId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }


    public boolean hasDeliveries(long ticketId) {
        Boolean value = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                  FROM sales.delivery_record
                 WHERE ticket_id = :ticketId
            )
            """, Map.of("ticketId", ticketId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    /**
     * Whether the deal's imported goods ever physically reached OUR warehouse — a
     * permanent fact from the GOODS_RECEIVED event, NOT the current fulfillment_status
     * (which gets overwritten to PARTIALLY_DELIVERED/FULLY_DELIVERED once deliveries
     * start). Used to gate WAREHOUSE-source deliveries so a stock-first partial
     * delivery on an imported deal can't lock out the warehouse remainder (Case 8).
     */
    public boolean hasReceivedGoods(long ticketId) {
        Boolean value = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                  FROM sales.ticket_event
                 WHERE ticket_id = :ticketId AND kind = 'GOODS_RECEIVED'
            )
            """, Map.of("ticketId", ticketId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    /**
     * Step 6 (V76): the ONE deliberate bridge write outside the legacy ticket status state
     * machine — see {@code th.co.glr.hr.orderconfirmation.OrderConfirmationService}'s class
     * Javadoc for the full reasoning. Since {@link th.co.glr.hr.ticket.TicketService#submit}
     * permanently 409s (Step 1), a deal driven entirely through the new PricingRequest chain can
     * never leave {@link TicketStatus#DRAFT} via the legacy flow, so this guarded compare-and-set
     * (FROM {@code draft} only) can never collide with a live legacy transition. A ticket that
     * already carries a real legacy status (created before Step 1, or otherwise mid-flow) is
     * never silently overwritten — 0 rows signals "not eligible", which the caller turns into a
     * 409 unless the current status already happens to be {@code quotation_issued} (an idempotent
     * replay of this same bridge).
     */
    public int markQuotationIssuedForOrderConfirmation(long ticketId) {
        return jdbc.update("""
            UPDATE sales.ticket
               SET status = 'quotation_issued', updated_at = now()
             WHERE ticket_id = :id AND status = 'draft'
            """, Map.of("id", ticketId));
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
