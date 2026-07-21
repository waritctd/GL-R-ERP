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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteAttachmentDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteItemDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;

@Repository
public class FactoryQuoteRepository {
    private static final String DISPATCH_SELECT = """
        SELECT factory_quote_email_dispatch_id, factory_quote_id, client_request_id::text AS client_request_id,
               status, email_to, email_subject, email_body, created_by, created_at,
               sending_at, sent_at, failed_at, failure_message,
               attempt_count, next_attempt_at, claimed_at, provider_message_id, finalized_at
          FROM sales.factory_quote_email_dispatch
        """;

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
                   CASE
                       WHEN LOWER(pri.requested_unit) IN ('sqm', 'sq.m', 'm2', 'm²', 'ตร.ม.') THEN 'PER_SQM'
                       ELSE 'PER_PIECE'
                   END,
                   :sortOrder
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

    public Optional<FactoryQuoteEmailDispatchDto> findDispatchByClientRequest(long actorId, String clientRequestId) {
        try {
            FactoryQuoteEmailDispatchDto dto = jdbc.queryForObject(DISPATCH_SELECT + """
                 WHERE created_by = :actorId
                   AND client_request_id = CAST(:clientRequestId AS uuid)
                """,
                new MapSqlParameterSource()
                    .addValue("actorId", actorId)
                    .addValue("clientRequestId", clientRequestId),
                (rs, rowNum) -> mapDispatch(rs));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public FactoryQuoteEmailDispatchDto createDispatch(long quoteId, String clientRequestId, String emailTo,
                                                       String subject, String body, long actorId) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.factory_quote_email_dispatch
                (factory_quote_id, client_request_id, email_to, email_subject, email_body, created_by)
            VALUES
                (:quoteId, CAST(:clientRequestId AS uuid), :emailTo, :subject, :body, :actorId)
            """,
            new MapSqlParameterSource()
                .addValue("quoteId", quoteId)
                .addValue("clientRequestId", clientRequestId)
                .addValue("emailTo", emailTo)
                .addValue("subject", subject)
                .addValue("body", body)
                .addValue("actorId", actorId),
            key, new String[]{"factory_quote_email_dispatch_id"});
        return findDispatch(key.getKey().longValue()).orElseThrow();
    }

    public Optional<FactoryQuoteEmailDispatchDto> findActiveDispatch(long quoteId) {
        try {
            FactoryQuoteEmailDispatchDto dto = jdbc.queryForObject(DISPATCH_SELECT + """
                 WHERE factory_quote_id = :quoteId
                   AND status IN ('PENDING', 'SENDING', 'SENT')
                 ORDER BY factory_quote_email_dispatch_id DESC
                 LIMIT 1
                """, Map.of("quoteId", quoteId), (rs, rowNum) -> mapDispatch(rs));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<FactoryQuoteEmailDispatchDto> findDispatch(long dispatchId) {
        try {
            FactoryQuoteEmailDispatchDto dto = jdbc.queryForObject(DISPATCH_SELECT + """
                 WHERE factory_quote_email_dispatch_id = :dispatchId
                """, Map.of("dispatchId", dispatchId), (rs, rowNum) -> mapDispatch(rs));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Most recent dispatch row for a quote, regardless of status — used to surface
     * pending/sending/sent/failed on {@code FactoryQuoteDto} for the frontend. Unlike
     * {@link #findActiveDispatch}, this also returns a terminal FAILED row so Import can see why
     * a send did not go out.
     */
    public Optional<FactoryQuoteEmailDispatchDto> findLatestDispatch(long quoteId) {
        try {
            FactoryQuoteEmailDispatchDto dto = jdbc.queryForObject(DISPATCH_SELECT + """
                 WHERE factory_quote_id = :quoteId
                 ORDER BY factory_quote_email_dispatch_id DESC
                 LIMIT 1
                """, Map.of("quoteId", quoteId), (rs, rowNum) -> mapDispatch(rs));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * The outbox worker's atomic claim. A single UPDATE keyed on the row id, rechecking the same
     * eligibility predicate the scan query used: PENDING (never attempted), FAILED (backed off,
     * due for retry), or SENDING-but-stale (claimed by a worker that crashed before finishing,
     * past {@code reclaimTimeoutSeconds}) — AND under the attempt cap — AND due (next_attempt_at
     * elapsed or unset). Two workers racing the same row: Postgres row-level locking on the UPDATE
     * serializes them, and the loser's WHERE no longer matches once the winner's UPDATE has
     * committed (status is now SENDING with a fresh claimed_at), so it affects zero rows.
     */
    public int claimDispatch(long dispatchId, int reclaimTimeoutSeconds, int maxAttempts) {
        return jdbc.update("""
            UPDATE sales.factory_quote_email_dispatch
               SET status = 'SENDING',
                   claimed_at = now(),
                   attempt_count = attempt_count + 1
             WHERE factory_quote_email_dispatch_id = :dispatchId
               AND attempt_count < :maxAttempts
               AND (
                    status = 'PENDING'
                    OR status = 'FAILED'
                    OR (status = 'SENDING' AND claimed_at < now() - (:reclaimTimeoutSeconds || ' seconds')::interval)
               )
               AND (next_attempt_at IS NULL OR next_attempt_at <= now())
            """,
            new MapSqlParameterSource()
                .addValue("dispatchId", dispatchId)
                .addValue("maxAttempts", maxAttempts)
                .addValue("reclaimTimeoutSeconds", reclaimTimeoutSeconds));
    }

    /**
     * Candidate ids for a worker tick to attempt {@link #claimDispatch}. A plain SELECT — the
     * actual atomicity/race-safety comes from the per-id claim UPDATE above, not from this scan.
     */
    public List<Long> findClaimableDispatchIds(int batchSize, int reclaimTimeoutSeconds, int maxAttempts) {
        return jdbc.queryForList("""
            SELECT factory_quote_email_dispatch_id
              FROM sales.factory_quote_email_dispatch
             WHERE attempt_count < :maxAttempts
               AND (
                    status = 'PENDING'
                    OR status = 'FAILED'
                    OR (status = 'SENDING' AND claimed_at < now() - (:reclaimTimeoutSeconds || ' seconds')::interval)
               )
               AND (next_attempt_at IS NULL OR next_attempt_at <= now())
             ORDER BY created_at
             LIMIT :batchSize
            """,
            new MapSqlParameterSource()
                .addValue("maxAttempts", maxAttempts)
                .addValue("reclaimTimeoutSeconds", reclaimTimeoutSeconds)
                .addValue("batchSize", batchSize),
            Long.class);
    }

    /** Best-effort marker that the provider call succeeded, so a reclaim after a post-send crash skips resending. */
    public void recordProviderMessageId(long dispatchId, String providerMessageId) {
        jdbc.update("""
            UPDATE sales.factory_quote_email_dispatch
               SET provider_message_id = :providerMessageId
             WHERE factory_quote_email_dispatch_id = :dispatchId
            """,
            new MapSqlParameterSource()
                .addValue("dispatchId", dispatchId)
                .addValue("providerMessageId", providerMessageId));
    }

    /** Terminal-per-attempt failure with backoff; stays claimable (via claimDispatch's WHERE) until attempt_count hits the cap. */
    public void markDispatchFailedWithBackoff(long dispatchId, String message, int backoffSeconds) {
        jdbc.update("""
            UPDATE sales.factory_quote_email_dispatch
               SET status = 'FAILED',
                   failed_at = now(),
                   failure_message = :message,
                   next_attempt_at = now() + (:backoffSeconds || ' seconds')::interval
             WHERE factory_quote_email_dispatch_id = :dispatchId
               AND status = 'SENDING'
            """,
            new MapSqlParameterSource()
                .addValue("dispatchId", dispatchId)
                .addValue("message", message)
                .addValue("backoffSeconds", backoffSeconds));
    }

    /** Last step of an idempotent finalize: dispatch reaches its terminal SENT state. */
    public void markDispatchFinalized(long dispatchId) {
        jdbc.update("""
            UPDATE sales.factory_quote_email_dispatch
               SET status = 'SENT',
                   sent_at = COALESCE(sent_at, now()),
                   finalized_at = now(),
                   failed_at = NULL,
                   failure_message = NULL
             WHERE factory_quote_email_dispatch_id = :dispatchId
            """, Map.of("dispatchId", dispatchId));
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
                .addValue("linearMPerUnit", item.linearMPerUnit())
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
                 pieces_per_box, linear_m_per_unit, lead_time_text, availability_note, line_note, sort_order)
            VALUES
                (:quoteId, :pricingRequestItemId, :supplierProductCode,
                 :supplierProductDescription, :quotedQuantity, :quotedUnit, :unitBasis,
                 :rawUnitPrice, :currency, :minimumOrderQuantity, :sqmPerUnit,
                 :piecesPerBox, :linearMPerUnit, :leadTimeText, :availabilityNote, :lineNote, :sortOrder)
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

    public void lockResponseIdempotencyKey(long actorId, String clientRequestId) {
        // Single 64-bit combined key via the pg_advisory_xact_lock(bigint) overload. The
        // two-int32 overload (pg_advisory_xact_lock(int, int)) would require casting actorId
        // down to int4, silently truncating employee ids above 2^31; hashtextextended hashes
        // the combined "actorId:clientRequestId" string into a bigint instead.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource()
                .addValue("lockKey", actorId + ":" + clientRequestId),
            (rs, rowNum) -> 0);
    }

    public Optional<FactoryQuoteResponseReceiptDto> findResponseReceipt(long actorId, String clientRequestId) {
        try {
            FactoryQuoteResponseReceiptDto dto = jdbc.queryForObject("""
                SELECT factory_quote_response_receipt_id, factory_quote_id, created_by,
                       client_request_id::text AS client_request_id, created_at
                  FROM sales.factory_quote_response_receipt
                 WHERE created_by = :actorId
                   AND client_request_id = CAST(:clientRequestId AS uuid)
                """,
                new MapSqlParameterSource()
                    .addValue("actorId", actorId)
                    .addValue("clientRequestId", clientRequestId),
                (rs, rowNum) -> mapResponseReceipt(rs));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Inserts the receipt unless one already exists for (created_by, client_request_id); returns
     * empty in that case instead of letting the unique index throw. {@code ON CONFLICT DO NOTHING}
     * never aborts the surrounding transaction (unlike a caught unique-violation, which under
     * Postgres leaves the transaction in an aborted state — 25P02 — so a subsequent statement in
     * the same transaction, such as a fallback lookup, would itself fail).
     */
    public Optional<FactoryQuoteResponseReceiptDto> createResponseReceiptIfAbsent(
        long factoryQuoteId, long actorId, String clientRequestId
    ) {
        List<FactoryQuoteResponseReceiptDto> inserted = jdbc.query("""
            INSERT INTO sales.factory_quote_response_receipt
                (factory_quote_id, created_by, client_request_id)
            VALUES
                (:factoryQuoteId, :actorId, CAST(:clientRequestId AS uuid))
            ON CONFLICT (created_by, client_request_id) DO NOTHING
            RETURNING factory_quote_response_receipt_id, factory_quote_id, created_by,
                      client_request_id::text AS client_request_id, created_at
            """,
            new MapSqlParameterSource()
                .addValue("factoryQuoteId", factoryQuoteId)
                .addValue("actorId", actorId)
                .addValue("clientRequestId", clientRequestId),
            (rs, rowNum) -> mapResponseReceipt(rs));
        return inserted.stream().findFirst();
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
                Map.of("quoteId", quoteId), (rs, rowNum) -> mapQuote(rs, findItems(quoteId), findAttachments(quoteId),
                    findLatestDispatch(quoteId).orElse(null)));
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
                return mapQuote(rs, findItems(id), findAttachments(id), findLatestDispatch(id).orElse(null));
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
                (rs, rowNum) -> {
                    long id = rs.getLong("factory_quote_id");
                    return mapQuote(rs, findItems(id), findAttachments(id), findLatestDispatch(id).orElse(null));
                });
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
                   minimum_order_quantity, sqm_per_unit, pieces_per_box, linear_m_per_unit,
                   lead_time_text, availability_note, line_note, sort_order
              FROM sales.factory_quote_item
             WHERE factory_quote_id = :quoteId
             ORDER BY sort_order, factory_quote_item_id
            """, Map.of("quoteId", quoteId), (rs, rowNum) -> mapItem(rs));
    }

    public List<FactoryQuoteAttachmentDto> findAttachments(long quoteId) {
        return jdbc.query("""
            SELECT attachment_id, owner_id AS factory_quote_id, file_name, mime_type,
                   file_size, uploaded_by, uploaded_at, deleted_at, deleted_by, delete_reason
              FROM hr.file_attachment
             WHERE domain = 'factory_quote'
               AND owner_id = :quoteId
             ORDER BY uploaded_at DESC, attachment_id DESC
            """, Map.of("quoteId", quoteId), (rs, rowNum) -> mapAttachment(rs));
    }

    /**
     * True if any {@code SUBMITTED} costing was calculated against this quote (i.e. any {@code
     * sales.pricing_costing_item} row it produced points at this {@code factory_quote_id}). Used
     * by {@link th.co.glr.hr.factoryquote.FactoryQuoteService#deleteAttachment} — supplier
     * evidence backing a costing the CEO has already reviewed must never be deletable, even if
     * the quote itself never individually reached {@code READY_FOR_COSTING} again after a later
     * supersede (the costing line still references this exact revision).
     */
    public boolean existsSubmittedCostingReferencingQuote(long factoryQuoteId) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                  FROM sales.pricing_costing_item pci
                  JOIN sales.pricing_costing pc ON pc.pricing_costing_id = pci.pricing_costing_id
                 WHERE pci.factory_quote_id = :factoryQuoteId
                   AND pc.status = 'SUBMITTED'
            )
            """, Map.of("factoryQuoteId", factoryQuoteId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public FactoryQuoteAttachmentDto saveAttachment(long quoteId, String fileName, String filePath,
                                                    String mimeType, Long fileSize, long uploadedBy) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO hr.file_attachment
                (domain, owner_id, file_name, file_path, mime_type, file_size, uploaded_by)
            VALUES
                ('factory_quote', :quoteId, :fileName, :filePath, :mimeType, :fileSize, :uploadedBy)
            """,
            new MapSqlParameterSource()
                .addValue("quoteId", quoteId)
                .addValue("fileName", fileName)
                .addValue("filePath", filePath)
                .addValue("mimeType", mimeType)
                .addValue("fileSize", fileSize)
                .addValue("uploadedBy", uploadedBy),
            key, new String[]{"attachment_id"});
        return findAttachment(key.getKey().longValue()).orElseThrow();
    }

    public Optional<FactoryQuoteAttachmentDto> findAttachment(long attachmentId) {
        try {
            FactoryQuoteAttachmentDto dto = jdbc.queryForObject("""
                SELECT attachment_id, owner_id AS factory_quote_id, file_name, mime_type,
                       file_size, uploaded_by, uploaded_at, deleted_at, deleted_by, delete_reason
                  FROM hr.file_attachment
                 WHERE attachment_id = :attachmentId
                   AND domain = 'factory_quote'
                """, Map.of("attachmentId", attachmentId), (rs, rowNum) -> mapAttachment(rs));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public String findAttachmentFilePath(long attachmentId) {
        try {
            return jdbc.queryForObject("""
                SELECT file_path
                  FROM hr.file_attachment
                 WHERE attachment_id = :attachmentId
                   AND domain = 'factory_quote'
                """, Map.of("attachmentId", attachmentId), String.class);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * Audited tombstone (V69, review remediation COMMIT 4): keeps the row and the stored file,
     * only records who deleted it, when, and why. Replaces the old hard {@code DELETE} +
     * {@code Files.deleteIfExists} — supplier evidence is append-only once recorded; only
     * {@code FactoryQuoteService.deleteAttachment}'s guards (not {@code READY_FOR_COSTING}, not
     * referenced by a {@code SUBMITTED} costing — see {@link #existsSubmittedCostingReferencingQuote})
     * decide whether tombstoning is even permitted. Guarded by {@code deleted_at IS NULL} so a
     * second call cannot overwrite an existing tombstone's {@code deleted_by}/{@code delete_reason}.
     */
    public int tombstoneAttachment(long attachmentId, long deletedBy, String reason) {
        return jdbc.update("""
            UPDATE hr.file_attachment
               SET deleted_at = now(),
                   deleted_by = :deletedBy,
                   delete_reason = :reason
             WHERE attachment_id = :attachmentId
               AND domain = 'factory_quote'
               AND deleted_at IS NULL
            """,
            new MapSqlParameterSource()
                .addValue("attachmentId", attachmentId)
                .addValue("deletedBy", deletedBy)
                .addValue("reason", reason));
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

    private FactoryQuoteDto mapQuote(ResultSet rs, List<FactoryQuoteItemDto> items,
                                     List<FactoryQuoteAttachmentDto> attachments,
                                     FactoryQuoteEmailDispatchDto dispatch) throws SQLException {
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
            items,
            attachments,
            dispatch == null ? null : dispatch.status(),
            dispatch == null ? 0 : dispatch.attemptCount(),
            dispatch == null ? null : dispatch.failureMessage(),
            dispatch == null ? null : dispatch.nextAttemptAt()
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
            rs.getBigDecimal("linear_m_per_unit"),
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

    private FactoryQuoteAttachmentDto mapAttachment(ResultSet rs) throws SQLException {
        return new FactoryQuoteAttachmentDto(
            rs.getLong("attachment_id"),
            rs.getLong("factory_quote_id"),
            rs.getString("file_name"),
            rs.getString("mime_type"),
            nullableLong(rs, "file_size"),
            rs.getLong("uploaded_by"),
            rs.getTimestamp("uploaded_at").toInstant(),
            instant(rs, "deleted_at"),
            nullableLong(rs, "deleted_by"),
            rs.getString("delete_reason")
        );
    }

    private FactoryQuoteEmailDispatchDto mapDispatch(ResultSet rs) throws SQLException {
        return new FactoryQuoteEmailDispatchDto(
            rs.getLong("factory_quote_email_dispatch_id"),
            rs.getLong("factory_quote_id"),
            rs.getString("client_request_id"),
            rs.getString("status"),
            rs.getString("email_to"),
            rs.getString("email_subject"),
            rs.getString("email_body"),
            nullableLong(rs, "created_by"),
            instant(rs, "created_at"),
            instant(rs, "sending_at"),
            instant(rs, "sent_at"),
            instant(rs, "failed_at"),
            rs.getString("failure_message"),
            rs.getInt("attempt_count"),
            instant(rs, "next_attempt_at"),
            instant(rs, "claimed_at"),
            rs.getString("provider_message_id"),
            instant(rs, "finalized_at")
        );
    }

    private FactoryQuoteResponseReceiptDto mapResponseReceipt(ResultSet rs) throws SQLException {
        return new FactoryQuoteResponseReceiptDto(
            rs.getLong("factory_quote_response_receipt_id"),
            rs.getLong("factory_quote_id"),
            rs.getLong("created_by"),
            rs.getString("client_request_id"),
            rs.getTimestamp("created_at").toInstant()
        );
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

    public record FactoryQuoteEmailDispatchDto(
        long id,
        long factoryQuoteId,
        String clientRequestId,
        String status,
        String emailTo,
        String emailSubject,
        String emailBody,
        Long createdBy,
        java.time.Instant createdAt,
        java.time.Instant sendingAt,
        java.time.Instant sentAt,
        java.time.Instant failedAt,
        String failureMessage,
        int attemptCount,
        java.time.Instant nextAttemptAt,
        java.time.Instant claimedAt,
        String providerMessageId,
        java.time.Instant finalizedAt
    ) {}

    public record FactoryQuoteResponseReceiptDto(
        long id,
        long factoryQuoteId,
        long createdBy,
        String clientRequestId,
        java.time.Instant createdAt
    ) {}
}
