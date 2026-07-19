package th.co.glr.hr.factoryquote;

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
import org.springframework.stereotype.Repository;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteItemDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;

@Repository
public class FactoryQuoteRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public FactoryQuoteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextQuoteCode() {
        Long seq = jdbc.queryForObject("SELECT nextval('sales.factory_quote_code_seq')", Map.of(), Long.class);
        return "FQ-" + Year.now() + "-" + String.format("%04d", seq == null ? 0 : seq);
    }

    public long createDraft(long pricingRequestId, Long factoryId, String factoryName, String emailTo,
                            String subject, String body, long actorId) {
        Long id = jdbc.queryForObject("""
            INSERT INTO sales.factory_quote
                (quote_code, pricing_request_id, factory_id, factory_name_snapshot,
                 status, email_to, email_subject, email_body, default_currency, created_by)
            VALUES
                (:quoteCode, :pricingRequestId, :factoryId, :factoryName,
                 'DRAFT', :emailTo, :subject, :body, :currency, :createdBy)
            RETURNING factory_quote_id
            """,
            new MapSqlParameterSource()
                .addValue("quoteCode", nextQuoteCode())
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("factoryId", factoryId)
                .addValue("factoryName", factoryName)
                .addValue("emailTo", emailTo)
                .addValue("subject", subject)
                .addValue("body", body)
                .addValue("currency", "THB")
                .addValue("createdBy", actorId),
            Long.class);
        long quoteId = id == null ? 0L : id;
        jdbc.update("""
            UPDATE sales.factory_quote
               SET root_factory_quote_id = :id
             WHERE factory_quote_id = :id
            """, Map.of("id", quoteId));
        return quoteId;
    }

    public void insertDraftItems(long quoteId, List<Long> pricingRequestItemIds) {
        MapSqlParameterSource[] batch = new MapSqlParameterSource[pricingRequestItemIds.size()];
        for (int i = 0; i < pricingRequestItemIds.size(); i++) {
            batch[i] = new MapSqlParameterSource()
                .addValue("quoteId", quoteId)
                .addValue("pricingRequestItemId", pricingRequestItemIds.get(i))
                .addValue("sortOrder", i);
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.factory_quote_item
                (factory_quote_id, pricing_request_item_id, quoted_quantity, quoted_unit, unit_basis, sort_order)
            SELECT :quoteId, pri.pricing_request_item_id, pri.requested_qty, pri.requested_unit,
                   pri.requested_unit, :sortOrder
              FROM sales.pricing_request_item pri
             WHERE pri.pricing_request_item_id = :pricingRequestItemId
            """, batch);
    }

    public boolean updateDraft(long quoteId, String emailTo, String subject, String body, String note) {
        int rows = jdbc.update("""
            UPDATE sales.factory_quote
               SET email_to = COALESCE(NULLIF(BTRIM(:emailTo), ''), email_to),
                   email_subject = COALESCE(:subject, email_subject),
                   email_body = COALESCE(:body, email_body),
                   note = COALESCE(:note, note),
                   updated_at = now()
             WHERE factory_quote_id = :quoteId
               AND status = 'DRAFT'
            """,
            new MapSqlParameterSource()
                .addValue("quoteId", quoteId)
                .addValue("emailTo", emailTo)
                .addValue("subject", subject)
                .addValue("body", body)
                .addValue("note", note));
        return rows == 1;
    }

    public int markRequested(long quoteId, String emailTo, String subject, String body, long actorId) {
        return jdbc.update("""
            UPDATE sales.factory_quote
               SET status = 'REQUESTED',
                   email_to = COALESCE(NULLIF(BTRIM(:emailTo), ''), email_to),
                   email_subject = COALESCE(:subject, email_subject),
                   email_body = COALESCE(:body, email_body),
                   email_sent_at = now(),
                   requested_at = now(),
                   sent_by = :actorId,
                   updated_at = now()
             WHERE factory_quote_id = :quoteId
               AND status = 'DRAFT'
            """,
            new MapSqlParameterSource()
                .addValue("quoteId", quoteId)
                .addValue("emailTo", emailTo)
                .addValue("subject", subject)
                .addValue("body", body)
                .addValue("actorId", actorId));
    }

    public int cancelOpenForPricingRequest(long pricingRequestId, String reason, long actorId) {
        return jdbc.update("""
            UPDATE sales.factory_quote
               SET status = 'CANCELLED',
                   is_current = FALSE,
                   cancel_reason = :reason,
                   cancelled_by = :actorId,
                   cancelled_at = now(),
                   updated_at = now()
             WHERE pricing_request_id = :pricingRequestId
               AND status IN ('DRAFT', 'REQUESTED')
            """,
            new MapSqlParameterSource()
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("reason", reason)
                .addValue("actorId", actorId));
    }

    public int updateFirstResponse(long quoteId, String supplierQuoteRef, String currency, String paymentTerms,
                                   String leadTimeText, String revisionReason, String negotiationNote) {
        return jdbc.update("""
            UPDATE sales.factory_quote
               SET status = 'RESPONSE_RECEIVED',
                   supplier_quote_ref = :supplierQuoteRef,
                   default_currency = :currency,
                   payment_terms = :paymentTerms,
                   lead_time_text = :leadTimeText,
                   revision_reason = :revisionReason,
                   negotiation_note = :negotiationNote,
                   received_at = now(),
                   updated_at = now()
             WHERE factory_quote_id = :quoteId
               AND status IN ('DRAFT', 'REQUESTED')
            """,
            new MapSqlParameterSource()
                .addValue("quoteId", quoteId)
                .addValue("supplierQuoteRef", supplierQuoteRef)
                .addValue("currency", normalizeCurrency(currency))
                .addValue("paymentTerms", paymentTerms)
                .addValue("leadTimeText", leadTimeText)
                .addValue("revisionReason", revisionReason)
                .addValue("negotiationNote", negotiationNote));
    }

    public long createRevision(FactoryQuoteDto previous, String supplierQuoteRef, String currency, String paymentTerms,
                               String leadTimeText, String revisionReason, String negotiationNote, long actorId) {
        jdbc.query("SELECT pg_advisory_xact_lock(:rootId)",
            Map.of("rootId", previous.rootFactoryQuoteId()), (rs, rowNum) -> 0);
        int nextRevision = jdbc.queryForObject("""
            SELECT COALESCE(MAX(revision_no), 0) + 1
              FROM sales.factory_quote
             WHERE root_factory_quote_id = :rootId
            """, Map.of("rootId", previous.rootFactoryQuoteId()), Integer.class);
        Long id = jdbc.queryForObject("""
            INSERT INTO sales.factory_quote
                (quote_code, pricing_request_id, factory_id, factory_name_snapshot, status,
                 email_to, email_subject, email_body, email_sent_at, sent_by, requested_at,
                 supplier_quote_ref, default_currency, payment_terms, lead_time_text,
                 revision_reason, negotiation_note, received_at, root_factory_quote_id,
                 parent_factory_quote_id, revision_no, is_current, created_by)
            VALUES
                (:quoteCode, :pricingRequestId, :factoryId, :factoryName, 'RESPONSE_RECEIVED',
                 :emailTo, :emailSubject, :emailBody, :emailSentAt, :sentBy, :requestedAt,
                 :supplierQuoteRef, :currency, :paymentTerms, :leadTimeText,
                 :revisionReason, :negotiationNote, now(), :rootId,
                 :parentId, :revisionNo, TRUE, :createdBy)
            RETURNING factory_quote_id
            """,
            new MapSqlParameterSource()
                .addValue("quoteCode", nextQuoteCode())
                .addValue("pricingRequestId", previous.pricingRequestId())
                .addValue("factoryId", previous.factoryId())
                .addValue("factoryName", previous.factoryName())
                .addValue("emailTo", previous.emailTo())
                .addValue("emailSubject", previous.emailSubject())
                .addValue("emailBody", previous.emailBody())
                .addValue("emailSentAt", timestamp(previous.emailSentAt()))
                .addValue("sentBy", previous.sentBy())
                .addValue("requestedAt", timestamp(previous.requestedAt()))
                .addValue("supplierQuoteRef", supplierQuoteRef)
                .addValue("currency", normalizeCurrency(currency))
                .addValue("paymentTerms", paymentTerms)
                .addValue("leadTimeText", leadTimeText)
                .addValue("revisionReason", revisionReason)
                .addValue("negotiationNote", negotiationNote)
                .addValue("rootId", previous.rootFactoryQuoteId())
                .addValue("parentId", previous.id())
                .addValue("revisionNo", nextRevision)
                .addValue("createdBy", actorId),
            Long.class);
        return id == null ? 0L : id;
    }

    public void supersede(long quoteId) {
        jdbc.update("""
            UPDATE sales.factory_quote
               SET status = 'SUPERSEDED',
                   is_current = FALSE,
                   updated_at = now()
             WHERE factory_quote_id = :quoteId
            """, Map.of("quoteId", quoteId));
    }

    public void replaceResponseItems(long quoteId, List<ReceiveFactoryQuoteItemRequest> items) {
        jdbc.update("DELETE FROM sales.factory_quote_item WHERE factory_quote_id = :quoteId", Map.of("quoteId", quoteId));
        MapSqlParameterSource[] batch = new MapSqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            ReceiveFactoryQuoteItemRequest item = items.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("quoteId", quoteId)
                .addValue("pricingRequestItemId", item.pricingRequestItemId())
                .addValue("supplierProductCode", item.supplierProductCode())
                .addValue("supplierProductDescription", item.supplierProductDescription())
                .addValue("quotedQuantity", item.quotedQuantity())
                .addValue("quotedUnit", item.quotedUnit())
                .addValue("unitBasis", item.unitBasis())
                .addValue("rawUnitPrice", item.rawUnitPrice())
                .addValue("currency", normalizeCurrency(item.currency()))
                .addValue("minimumOrderQuantity", item.minimumOrderQuantity())
                .addValue("sqmPerUnit", item.sqmPerUnit())
                .addValue("piecesPerBox", item.piecesPerBox())
                .addValue("leadTimeText", item.leadTimeText())
                .addValue("availabilityNote", item.availabilityNote())
                .addValue("lineNote", item.lineNote())
                .addValue("sortOrder", i);
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.factory_quote_item
                (factory_quote_id, pricing_request_item_id, supplier_product_code,
                 supplier_product_description, quoted_quantity, quoted_unit, unit_basis,
                 raw_unit_price, currency, minimum_order_quantity, sqm_per_unit,
                 pieces_per_box, lead_time_text, availability_note, line_note, sort_order)
            VALUES
                (:quoteId, :pricingRequestItemId, :supplierProductCode,
                 :supplierProductDescription, :quotedQuantity, :quotedUnit, :unitBasis,
                 :rawUnitPrice, :currency, :minimumOrderQuantity, :sqmPerUnit,
                 :piecesPerBox, :leadTimeText, :availabilityNote, :lineNote, :sortOrder)
            """, batch);
    }

    public int startNegotiation(long quoteId, String note) {
        return jdbc.update("""
            UPDATE sales.factory_quote
               SET status = 'NEGOTIATING',
                   negotiation_note = :note,
                   updated_at = now()
             WHERE factory_quote_id = :quoteId
               AND status = 'RESPONSE_RECEIVED'
               AND is_current = TRUE
            """, Map.of("quoteId", quoteId, "note", note));
    }

    public int markReady(long quoteId) {
        return jdbc.update("""
            UPDATE sales.factory_quote
               SET status = 'READY_FOR_COSTING',
                   updated_at = now()
             WHERE factory_quote_id = :quoteId
               AND status IN ('RESPONSE_RECEIVED', 'NEGOTIATING')
               AND is_current = TRUE
               AND NOT EXISTS (
                   SELECT 1 FROM sales.factory_quote_item fqi
                    WHERE fqi.factory_quote_id = sales.factory_quote.factory_quote_id
                      AND (fqi.raw_unit_price IS NULL
                           OR fqi.currency IS NULL
                           OR fqi.quoted_unit IS NULL
                           OR fqi.unit_basis IS NULL)
               )
               AND EXISTS (
                   SELECT 1 FROM sales.factory_quote_item fqi
                    WHERE fqi.factory_quote_id = sales.factory_quote.factory_quote_id
               )
            """, Map.of("quoteId", quoteId));
    }

    public int markNotAvailable(long quoteId, String reason, long actorId) {
        return jdbc.update("""
            UPDATE sales.factory_quote
               SET status = 'NOT_AVAILABLE',
                   note = :reason,
                   cancelled_by = :actorId,
                   updated_at = now()
             WHERE factory_quote_id = :quoteId
               AND status IN ('REQUESTED', 'RESPONSE_RECEIVED', 'NEGOTIATING')
               AND is_current = TRUE
            """, Map.of("quoteId", quoteId, "reason", reason, "actorId", actorId));
    }

    public void markOpenCostingsStale(long pricingRequestId, String reason) {
        jdbc.update("""
            UPDATE sales.pricing_costing
               SET stale = TRUE,
                   stale_reason = :reason,
                   updated_at = now()
             WHERE pricing_request_id = :pricingRequestId
               AND status IN ('DRAFT', 'CALCULATED')
            """, Map.of("pricingRequestId", pricingRequestId, "reason", reason));
    }

    public Optional<FactoryQuoteDto> find(long quoteId) {
        try {
            FactoryQuoteDto quote = jdbc.queryForObject(baseSelect() + " WHERE fq.factory_quote_id = :quoteId",
                Map.of("quoteId", quoteId), (rs, rowNum) -> mapQuote(rs, findItems(quoteId)));
            return Optional.ofNullable(quote);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<FactoryQuoteDto> findByPricingRequest(long pricingRequestId) {
        return jdbc.query(baseSelect() + """
             WHERE fq.pricing_request_id = :pricingRequestId
             ORDER BY fq.factory_name_snapshot, fq.revision_no
            """,
            Map.of("pricingRequestId", pricingRequestId),
            (rs, rowNum) -> {
                long id = rs.getLong("factory_quote_id");
                return mapQuote(rs, findItems(id));
            });
    }

    public Optional<FactoryQuoteDto> findCurrentByFactory(long pricingRequestId, String factoryName) {
        try {
            FactoryQuoteDto quote = jdbc.queryForObject(baseSelect() + """
                 WHERE fq.pricing_request_id = :pricingRequestId
                   AND fq.factory_name_snapshot = :factoryName
                   AND fq.is_current = TRUE
                   AND fq.status <> 'CANCELLED'
                """,
                Map.of("pricingRequestId", pricingRequestId, "factoryName", factoryName),
                (rs, rowNum) -> mapQuote(rs, findItems(rs.getLong("factory_quote_id"))));
            return Optional.ofNullable(quote);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<FactoryQuoteItemDto> findItems(long quoteId) {
        return jdbc.query("""
            SELECT factory_quote_item_id, factory_quote_id, pricing_request_item_id,
                   catalog_product_id_snapshot, supplier_product_code, supplier_product_description,
                   quoted_quantity, quoted_unit, unit_basis, raw_unit_price, currency,
                   minimum_order_quantity, sqm_per_unit, pieces_per_box, lead_time_text,
                   availability_note, line_note, sort_order
              FROM sales.factory_quote_item
             WHERE factory_quote_id = :quoteId
             ORDER BY sort_order, factory_quote_item_id
            """, Map.of("quoteId", quoteId), (rs, rowNum) -> mapItem(rs));
    }

    private String baseSelect() {
        return """
            SELECT factory_quote_id, quote_code, pricing_request_id, factory_id, factory_name_snapshot,
                   status, email_to, email_subject, email_body, email_sent_at, sent_by,
                   supplier_quote_ref, default_currency, payment_terms, lead_time_text, note,
                   negotiation_note, requested_at, received_at, root_factory_quote_id,
                   parent_factory_quote_id, revision_no, revision_reason, is_current,
                   created_at, updated_at
              FROM sales.factory_quote fq
            """;
    }

    private FactoryQuoteDto mapQuote(ResultSet rs, List<FactoryQuoteItemDto> items) throws SQLException {
        return new FactoryQuoteDto(
            rs.getLong("factory_quote_id"),
            rs.getString("quote_code"),
            rs.getLong("pricing_request_id"),
            nullableLong(rs, "factory_id"),
            rs.getString("factory_name_snapshot"),
            rs.getString("status"),
            rs.getString("email_to"),
            rs.getString("email_subject"),
            rs.getString("email_body"),
            instant(rs, "email_sent_at"),
            nullableLong(rs, "sent_by"),
            rs.getString("supplier_quote_ref"),
            rs.getString("default_currency"),
            rs.getString("payment_terms"),
            rs.getString("lead_time_text"),
            rs.getString("note"),
            rs.getString("negotiation_note"),
            instant(rs, "requested_at"),
            instant(rs, "received_at"),
            nullableLong(rs, "root_factory_quote_id"),
            nullableLong(rs, "parent_factory_quote_id"),
            rs.getInt("revision_no"),
            rs.getString("revision_reason"),
            rs.getBoolean("is_current"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            items
        );
    }

    private FactoryQuoteItemDto mapItem(ResultSet rs) throws SQLException {
        return new FactoryQuoteItemDto(
            rs.getLong("factory_quote_item_id"),
            rs.getLong("factory_quote_id"),
            rs.getLong("pricing_request_item_id"),
            nullableLong(rs, "catalog_product_id_snapshot"),
            rs.getString("supplier_product_code"),
            rs.getString("supplier_product_description"),
            rs.getBigDecimal("quoted_quantity"),
            rs.getString("quoted_unit"),
            rs.getString("unit_basis"),
            rs.getBigDecimal("raw_unit_price"),
            rs.getString("currency"),
            rs.getBigDecimal("minimum_order_quantity"),
            rs.getBigDecimal("sqm_per_unit"),
            rs.getBigDecimal("pieces_per_box"),
            rs.getString("lead_time_text"),
            rs.getString("availability_note"),
            rs.getString("line_note"),
            rs.getInt("sort_order")
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private String normalizeCurrency(String currency) {
        return currency == null ? null : currency.trim().toUpperCase();
    }
}
