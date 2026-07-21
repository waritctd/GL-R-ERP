package th.co.glr.hr.customerquotation;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationItemDto;

/**
 * Persistence for Step 4 (V74): extends {@code sales.quotation}/{@code sales.quotation_item} —
 * NOT a parallel table (owner's decision, see the branch handoff). Persistence only — no
 * permission checks, no discount-policy validation, no arithmetic; see
 * {@link CustomerQuotationService} for all of that. Mirrors {@code PricingDecisionRepository}'s
 * own shape/idioms exactly.
 */
@Repository
public class CustomerQuotationRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public CustomerQuotationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextQuotationCode() {
        long seq = jdbc.queryForObject("SELECT nextval('sales.quotation_code_seq')", Map.of(), Long.class);
        return "QT-" + Year.now() + "-" + String.format("%04d", seq);
    }

    /** Serializes every mutating operation Step 4 performs against a given pricing request,
     * against each other and against Step 3's own advisory locks on the same key. */
    public void lockPricingRequest(long pricingRequestId) {
        jdbc.query("SELECT pg_advisory_xact_lock(:id)", Map.of("id", pricingRequestId), (rs, rowNum) -> 0);
    }

    public Optional<Long> findIdByClientRequestId(long issuedBy, String clientRequestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT quotation_id FROM sales.quotation
                 WHERE issued_by = :issuedBy AND client_request_id = CAST(:clientRequestId AS uuid)
                """, new MapSqlParameterSource().addValue("issuedBy", issuedBy).addValue("clientRequestId", clientRequestId),
                Long.class));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Long> findIdByIssueClientRequestId(long issuedBy, String issueClientRequestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT quotation_id FROM sales.quotation
                 WHERE issued_by = :issuedBy AND issue_client_request_id = CAST(:issueClientRequestId AS uuid)
                """, new MapSqlParameterSource().addValue("issuedBy", issuedBy)
                    .addValue("issueClientRequestId", issueClientRequestId),
                Long.class));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Step 5: recordOutcome's own idempotency lookup, mirrors {@link #findIdByIssueClientRequestId}. */
    public Optional<Long> findIdByOutcomeClientRequestId(long issuedBy, String outcomeClientRequestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT quotation_id FROM sales.quotation
                 WHERE issued_by = :issuedBy AND outcome_client_request_id = CAST(:outcomeClientRequestId AS uuid)
                """, new MapSqlParameterSource().addValue("issuedBy", issuedBy)
                    .addValue("outcomeClientRequestId", outcomeClientRequestId),
                Long.class));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public record NewItem(
        long pricingRequestItemId, long pricingDecisionItemId, String description,
        String requestedUnitBasis, BigDecimal requestedQuantity, BigDecimal approvedUnitPrice,
        BigDecimal salesDiscount, BigDecimal finalUnitPrice, BigDecimal lineSubtotal,
        BigDecimal vat, BigDecimal lineTotal,
        // Legacy rendering columns (kept in sync so QuotationRenderer/
        // TicketRepository.findQuotationItemsByQuotationId — reused as-is, unmodified — keep
        // working for a Step 4 quotation exactly as they do for a legacy one).
        String brand, String rawUnit) {}

    public record InsertDraftParams(
        long ticketId, long pricingRequestId, long pricingDecisionId, String recipientType,
        String recipientLabel, long actorId, String clientRequestId, String paymentTerms,
        String leadTime, String deliveryTerms, LocalDate validityDate, String customerNotes,
        Long parentQuotationId, int quotationRevisionNo, BigDecimal subtotal, String currency,
        String customerName, String customerAddress, String customerTaxId, String customerPhone,
        String projectName, List<NewItem> items) {}

    /**
     * Inserts a new DRAFT quotation row + its item snapshot in one transaction. Does not check
     * idempotency itself (the caller checks {@link #findIdByClientRequestId} first, under the
     * same {@link #lockPricingRequest} hold, mirroring {@code PricingDecisionRepository.createDraft}'s
     * contract) and does not supersede any prior row (the caller does that explicitly when
     * creating a revision, so a first-ever create never accidentally supersedes anything).
     */
    @Transactional
    public long insertDraft(InsertDraftParams p) {
        Integer maxVersion = jdbc.queryForObject("""
            SELECT COALESCE(MAX(quotation_version), 0) FROM sales.quotation
             WHERE ticket_id = :ticketId AND recipient_type = :recipientType
            """, new MapSqlParameterSource().addValue("ticketId", p.ticketId())
                .addValue("recipientType", p.recipientType()), Integer.class);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;
        String number = nextQuotationCode();

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, total_amount, currency, quotation_version, doc_status,
                 recipient_type, recipient_label, payment_terms, lead_time, delivery_terms, validity_date,
                 parent_quotation_id, pricing_request_id, pricing_decision_id, quotation_revision_no,
                 client_request_id, customer_notes, customer_name, customer_address, customer_tax_id,
                 customer_phone, project_name)
            VALUES
                (:ticketId, :number, :issuedBy, :totalAmount, :currency, :version, 'DRAFT',
                 :recipientType, :recipientLabel, :paymentTerms, :leadTime, :deliveryTerms, :validityDate,
                 :parentQuotationId, :pricingRequestId, :pricingDecisionId, :quotationRevisionNo,
                 CAST(:clientRequestId AS uuid), :customerNotes, :customerName, :customerAddress,
                 :customerTaxId, :customerPhone, :projectName)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", p.ticketId())
                .addValue("number", number)
                .addValue("issuedBy", p.actorId())
                .addValue("totalAmount", p.subtotal())
                .addValue("currency", p.currency())
                .addValue("version", nextVersion)
                .addValue("recipientType", p.recipientType())
                .addValue("recipientLabel", p.recipientLabel())
                .addValue("paymentTerms", p.paymentTerms())
                .addValue("leadTime", p.leadTime())
                .addValue("deliveryTerms", p.deliveryTerms())
                .addValue("validityDate", p.validityDate())
                .addValue("parentQuotationId", p.parentQuotationId())
                .addValue("pricingRequestId", p.pricingRequestId())
                .addValue("pricingDecisionId", p.pricingDecisionId())
                .addValue("quotationRevisionNo", p.quotationRevisionNo())
                .addValue("clientRequestId", p.clientRequestId())
                .addValue("customerNotes", p.customerNotes())
                .addValue("customerName", p.customerName())
                .addValue("customerAddress", p.customerAddress())
                .addValue("customerTaxId", p.customerTaxId())
                .addValue("customerPhone", p.customerPhone())
                .addValue("projectName", p.projectName()),
            keyHolder, new String[]{"quotation_id"});
        long quotationId = keyHolder.getKey().longValue();
        insertItems(quotationId, p.items());
        return quotationId;
    }

    public void insertItems(long quotationId, List<NewItem> items) {
        if (items.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            NewItem item = items.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("quotationId", quotationId)
                .addValue("seq", i + 1)
                .addValue("brand", item.brand())
                .addValue("rawUnit", item.rawUnit())
                .addValue("qty", item.requestedQuantity())
                .addValue("unitPrice", item.finalUnitPrice())
                .addValue("amount", item.lineSubtotal())
                .addValue("pricingRequestItemId", item.pricingRequestItemId())
                .addValue("pricingDecisionItemId", item.pricingDecisionItemId())
                .addValue("requestedUnitBasis", item.requestedUnitBasis())
                .addValue("requestedQuantity", item.requestedQuantity())
                .addValue("approvedUnitPrice", item.approvedUnitPrice())
                .addValue("salesDiscount", item.salesDiscount())
                .addValue("finalUnitPrice", item.finalUnitPrice())
                .addValue("lineSubtotal", item.lineSubtotal())
                .addValue("vat", item.vat())
                .addValue("lineTotal", item.lineTotal())
                .addValue("description", item.description());
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.quotation_item
                (quotation_id, seq, brand, raw_unit, qty, unit_price, amount,
                 pricing_request_item_id, pricing_decision_item_id, requested_unit_basis,
                 requested_quantity, approved_unit_price, sales_discount, final_unit_price,
                 line_subtotal, vat, line_total, description)
            VALUES
                (:quotationId, :seq, :brand, :rawUnit, :qty, :unitPrice, :amount,
                 :pricingRequestItemId, :pricingDecisionItemId, :requestedUnitBasis,
                 :requestedQuantity, :approvedUnitPrice, :salesDiscount, :finalUnitPrice,
                 :lineSubtotal, :vat, :lineTotal, :description)
            """, batch);
    }

    /** Draft-only mutation (rule 8: issued quotations are immutable) — the WHERE clause is the
     * enforcement, not a service-layer check the caller could forget. */
    public int updateHeader(long quotationId, String paymentTerms, String leadTime, String deliveryTerms,
                            LocalDate validityDate, String customerNotes) {
        return jdbc.update("""
            UPDATE sales.quotation
               SET payment_terms  = COALESCE(:paymentTerms, payment_terms),
                   lead_time      = COALESCE(:leadTime, lead_time),
                   delivery_terms = COALESCE(:deliveryTerms, delivery_terms),
                   validity_date  = COALESCE(:validityDate, validity_date),
                   customer_notes = COALESCE(:customerNotes, customer_notes)
             WHERE quotation_id = :id AND doc_status IN ('DRAFT', 'READY_TO_ISSUE')
            """,
            new MapSqlParameterSource()
                .addValue("id", quotationId)
                .addValue("paymentTerms", paymentTerms)
                .addValue("leadTime", leadTime)
                .addValue("deliveryTerms", deliveryTerms)
                .addValue("validityDate", validityDate)
                .addValue("customerNotes", customerNotes));
    }

    public record ItemUpdate(long itemId, String description, String itemNotes, BigDecimal salesDiscount,
                             BigDecimal finalUnitPrice, BigDecimal lineSubtotal, BigDecimal vat, BigDecimal lineTotal) {}

    /** Returns rows touched — the caller compares against the requested count to detect an
     * itemId that does not belong to this quotation, exactly like
     * {@code PricingDecisionRepository.updateItems}. Draft-only via the WHERE clause. */
    public int updateItems(long quotationId, List<ItemUpdate> updates) {
        if (updates.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[updates.size()];
        for (int i = 0; i < updates.size(); i++) {
            ItemUpdate u = updates.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("quotationId", quotationId)
                .addValue("itemId", u.itemId())
                .addValue("description", u.description())
                .addValue("itemNotes", u.itemNotes())
                .addValue("salesDiscount", u.salesDiscount())
                .addValue("finalUnitPrice", u.finalUnitPrice())
                .addValue("lineSubtotal", u.lineSubtotal())
                .addValue("vat", u.vat())
                .addValue("lineTotal", u.lineTotal());
        }
        int[] counts = jdbc.batchUpdate("""
            UPDATE sales.quotation_item
               SET description      = COALESCE(:description, description),
                   item_notes       = COALESCE(:itemNotes, item_notes),
                   sales_discount   = :salesDiscount,
                   final_unit_price = :finalUnitPrice,
                   line_subtotal    = :lineSubtotal,
                   unit_price       = :finalUnitPrice,
                   amount           = :lineSubtotal,
                   vat              = :vat,
                   line_total       = :lineTotal
             WHERE quotation_item_id = :itemId
               AND quotation_id = :quotationId
               AND EXISTS (
                   SELECT 1 FROM sales.quotation q
                    WHERE q.quotation_id = :quotationId AND q.doc_status IN ('DRAFT', 'READY_TO_ISSUE'))
            """, batch);
        int total = 0;
        for (int c : counts) total += c;
        return total;
    }

    /** Recomputes the quotation-level subtotal from its current item rows — always called after
     * {@link #updateItems} in the same transaction so total_amount never drifts from the sum of
     * its own lines (rule 3: server calculates subtotal/VAT/total, never trusts the client). */
    public void recalculateTotal(long quotationId) {
        jdbc.update("""
            UPDATE sales.quotation
               SET total_amount = COALESCE(
                       (SELECT SUM(line_subtotal) FROM sales.quotation_item WHERE quotation_id = :id), 0)
             WHERE quotation_id = :id
            """, Map.of("id", quotationId));
    }

    /** Compare-and-set DRAFT/READY_TO_ISSUE -> ISSUED. Rowcount 0 means not open for issuing —
     * the service distinguishes "already issued by this same idempotency key" (replay) from a
     * genuine conflict, exactly like {@code PricingDecisionRepository.approve}. */
    public int issue(long quotationId, String issueClientRequestId) {
        return jdbc.update("""
            UPDATE sales.quotation
               SET doc_status = 'ISSUED',
                   issued_at = now(),
                   issue_client_request_id = CAST(:issueClientRequestId AS uuid)
             WHERE quotation_id = :id AND doc_status IN ('DRAFT', 'READY_TO_ISSUE')
            """,
            new MapSqlParameterSource().addValue("id", quotationId)
                .addValue("issueClientRequestId", issueClientRequestId));
    }

    public int cancel(long quotationId) {
        return jdbc.update("""
            UPDATE sales.quotation SET doc_status = 'CANCELLED'
             WHERE quotation_id = :id AND doc_status IN ('DRAFT', 'READY_TO_ISSUE')
            """, Map.of("id", quotationId));
    }

    /** Only this recipient chain's currently-ISSUED (or, Step 5: REVISION_REQUESTED — a
     * commercial-only correction created after recordOutcome already moved the source off
     * ISSUED) row supersedes when a revision is created — mirrors
     * {@code TicketRepository.createQuotation}'s own supersede scope, widened the same way
     * {@code CustomerQuotationService.createRevision}'s own predecessor guard was (design
     * correction 3): exactly these two statuses, nothing else. */
    public int supersede(long quotationId) {
        return jdbc.update("""
            UPDATE sales.quotation SET doc_status = 'SUPERSEDED'
             WHERE quotation_id = :id AND doc_status IN ('ISSUED', 'REVISION_REQUESTED')
            """, Map.of("id", quotationId));
    }

    /**
     * Step 5: compare-and-set ISSUED -> {@code outcome} (ACCEPTED/REJECTED/REVISION_REQUESTED —
     * the service validates the value before calling this). Rowcount 0 means not open for
     * recording (already terminal/not ISSUED) — the service distinguishes an idempotent replay
     * (checked separately via {@link #findIdByOutcomeClientRequestId}, under the same lock hold as
     * every other Step 4/5 mutation) from a genuine conflict, exactly like {@link #issue}.
     */
    public int recordOutcome(long quotationId, String outcome, String outcomeNote, long actorId,
                             String outcomeClientRequestId) {
        return jdbc.update("""
            UPDATE sales.quotation
               SET doc_status = :outcome,
                   outcome_note = :outcomeNote,
                   outcome_recorded_by = :actorId,
                   outcome_recorded_at = now(),
                   outcome_client_request_id = CAST(:outcomeClientRequestId AS uuid),
                   accepted_at = CASE WHEN :outcome = 'ACCEPTED' THEN now() ELSE accepted_at END,
                   rejected_at = CASE WHEN :outcome = 'REJECTED' THEN now() ELSE rejected_at END
             WHERE quotation_id = :id AND doc_status = 'ISSUED'
            """,
            new MapSqlParameterSource()
                .addValue("id", quotationId)
                .addValue("outcome", outcome)
                .addValue("outcomeNote", outcomeNote)
                .addValue("actorId", actorId)
                .addValue("outcomeClientRequestId", outcomeClientRequestId));
    }

    /** One row per quotation the sweep just flipped ISSUED -> EXPIRED, for the service to emit a
     * pricing_request_event/notification per row. */
    public record ExpiredQuotationRow(long quotationId, long pricingRequestId, long ticketId, String number) {}

    /**
     * Step 5 ("EXPIRED — automatic, following the established worker pattern"): a single guarded
     * UPDATE, not the outbox/claim/retry machinery {@code FactoryQuoteEmailDispatchWorker} needs
     * — this isn't calling an external system. Scoped to {@code pricing_request_id IS NOT NULL}
     * (Step 4/5 quotations only) because emitting a {@code sales.pricing_request_event} requires
     * that link; a legacy ticket-item-driven quotation's own EXPIRED handling (if any is ever
     * added) is out of this step's scope. Never touches an ACCEPTED/REJECTED/CANCELLED/SUPERSEDED
     * row, and never rolls the pricing request back (design correction 2's own "no pricing-request
     * status change on expiry").
     */
    public List<ExpiredQuotationRow> expireOverdueQuotations() {
        return jdbc.query("""
            UPDATE sales.quotation
               SET doc_status = 'EXPIRED'
             WHERE doc_status = 'ISSUED'
               AND pricing_request_id IS NOT NULL
               AND validity_date IS NOT NULL
               AND validity_date < CURRENT_DATE
            RETURNING quotation_id, pricing_request_id, ticket_id, number
            """, Map.of(), (rs, rowNum) -> new ExpiredQuotationRow(
                rs.getLong("quotation_id"), rs.getLong("pricing_request_id"),
                rs.getLong("ticket_id"), rs.getString("number")));
    }

    public Optional<CustomerQuotationDto> findById(long quotationId) {
        try {
            CustomerQuotationDto dto = jdbc.queryForObject(baseSelect() + " WHERE q.quotation_id = :id AND q.pricing_request_id IS NOT NULL",
                Map.of("id", quotationId), (rs, rowNum) -> mapQuotation(rs, findItems(quotationId)));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<CustomerQuotationDto> findByPricingRequest(long pricingRequestId) {
        List<Long> ids = jdbc.query("""
            SELECT quotation_id FROM sales.quotation
             WHERE pricing_request_id = :id
             ORDER BY quotation_revision_no, quotation_id
            """, Map.of("id", pricingRequestId), (rs, rowNum) -> rs.getLong("quotation_id"));
        List<CustomerQuotationDto> result = new ArrayList<>();
        for (Long id : ids) {
            findById(id).ifPresent(result::add);
        }
        return result;
    }

    private List<CustomerQuotationItemDto> findItems(long quotationId) {
        return jdbc.query("""
            SELECT qi.quotation_item_id, qi.seq, qi.pricing_request_item_id, qi.pricing_decision_item_id,
                   qi.description, qi.item_notes, qi.requested_unit_basis, qi.requested_quantity,
                   qi.approved_unit_price, qi.sales_discount, qi.final_unit_price, qi.line_subtotal,
                   qi.vat, qi.line_total, pdi.minimum_selling_price_per_requested_unit
              FROM sales.quotation_item qi
              LEFT JOIN sales.pricing_decision_item pdi ON pdi.pricing_decision_item_id = qi.pricing_decision_item_id
             WHERE qi.quotation_id = :id
             ORDER BY qi.seq
            """, Map.of("id", quotationId), (rs, rowNum) -> mapItem(rs));
    }

    private String baseSelect() {
        return """
            SELECT q.quotation_id, q.number, q.ticket_id, q.pricing_request_id, q.pricing_decision_id,
                   q.recipient_type, q.recipient_label, q.doc_status, q.quotation_version,
                   q.quotation_revision_no, q.parent_quotation_id, q.issued_by,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS issued_by_name,
                   q.issued_at, q.total_amount, q.currency, q.payment_terms, q.lead_time, q.delivery_terms,
                   q.validity_date, q.customer_notes, q.sent_at, q.accepted_at, q.rejected_at,
                   q.issued_at AS created_at, q.outcome_note, q.outcome_recorded_at
              FROM sales.quotation q
              LEFT JOIN hr.employee e ON e.employee_id = q.issued_by
            """;
    }

    private CustomerQuotationDto mapQuotation(ResultSet rs, List<CustomerQuotationItemDto> items) throws SQLException {
        BigDecimal subtotal = rs.getBigDecimal("total_amount") != null ? rs.getBigDecimal("total_amount") : BigDecimal.ZERO;
        BigDecimal vatTotal = items.stream().map(CustomerQuotationItemDto::vat).filter(v -> v != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grandTotal = items.stream().map(CustomerQuotationItemDto::lineTotal).filter(v -> v != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CustomerQuotationDto(
            rs.getLong("quotation_id"),
            rs.getString("number"),
            rs.getLong("ticket_id"),
            rs.getLong("pricing_request_id"),
            rs.getLong("pricing_decision_id"),
            rs.getString("recipient_type"),
            rs.getString("recipient_label"),
            rs.getString("doc_status"),
            rs.getInt("quotation_version"),
            rs.getInt("quotation_revision_no"),
            nullableLong(rs, "parent_quotation_id"),
            rs.getLong("issued_by"),
            rs.getString("issued_by_name"),
            instant(rs, "issued_at"),
            subtotal,
            vatTotal,
            grandTotal,
            rs.getString("currency"),
            rs.getString("payment_terms"),
            rs.getString("lead_time"),
            rs.getString("delivery_terms"),
            rs.getDate("validity_date") != null ? rs.getDate("validity_date").toLocalDate() : null,
            rs.getString("customer_notes"),
            instant(rs, "sent_at"),
            instant(rs, "accepted_at"),
            instant(rs, "rejected_at"),
            instant(rs, "created_at"),
            rs.getString("outcome_note"),
            instant(rs, "outcome_recorded_at"),
            items
        );
    }

    private CustomerQuotationItemDto mapItem(ResultSet rs) throws SQLException {
        return new CustomerQuotationItemDto(
            rs.getLong("quotation_item_id"),
            rs.getInt("seq"),
            rs.getLong("pricing_request_item_id"),
            rs.getLong("pricing_decision_item_id"),
            rs.getString("description"),
            rs.getString("item_notes"),
            rs.getString("requested_unit_basis"),
            rs.getBigDecimal("requested_quantity"),
            rs.getBigDecimal("approved_unit_price"),
            rs.getBigDecimal("sales_discount"),
            rs.getBigDecimal("final_unit_price"),
            rs.getBigDecimal("minimum_selling_price_per_requested_unit"),
            rs.getBigDecimal("line_subtotal"),
            rs.getBigDecimal("vat"),
            rs.getBigDecimal("line_total")
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
