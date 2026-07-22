package th.co.glr.hr.pricingrequest;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestAttachmentDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestEventDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CustomerChangeRevisionRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.UpdatePricingRequestRequest;

/**
 * Persistence for the PricingRequest aggregate (V59): {@code sales.pricing_request},
 * {@code sales.pricing_request_item}, {@code sales.pricing_request_event}.
 *
 * <p><strong>CRITICAL INVARIANT:</strong> this repository must NEVER call
 * {@code th.co.glr.hr.ticket.TicketRepository}, nor write to {@code sales.ticket} /
 * {@code sales.ticket_event}. Reason: {@code TicketRepository.addEventInternal} writes
 * {@code assigned_to = CASE WHEN :isPickup THEN :actorId ELSE assigned_to END} as a
 * side-effect of any PICKED_UP event. Pricing-request pickup must assign the Import
 * employee to the *pricing request only*, never to the whole deal. Routing
 * pricing-request events through TicketRepository would silently reassign the ticket.
 *
 * <p>Persistence only — no permission checks, no workflow validation, no Jackson.
 * Those belong in the service layer.
 */
@Repository
public class PricingRequestRepository {
    private static final String SUMMARY_SELECT = """
        SELECT pr.*, t.code AS ticket_code, t.title AS ticket_title, t.created_by AS ticket_created_by,
               p.name AS project_name, t.customer_name,
               er.first_name_th AS requested_by_first_name_th, er.last_name_th AS requested_by_last_name_th,
               ei.first_name_th AS assigned_import_first_name_th, ei.last_name_th AS assigned_import_last_name_th,
               (SELECT COUNT(*) FROM sales.pricing_request_item i
                 WHERE i.pricing_request_id = pr.pricing_request_id) AS item_count
          FROM sales.pricing_request pr
          JOIN sales.ticket t ON t.ticket_id = pr.ticket_id
          LEFT JOIN customers.project p ON p.project_id = t.project_id
          JOIN hr.employee er ON er.employee_id = pr.requested_by
          LEFT JOIN hr.employee ei ON ei.employee_id = pr.assigned_import_id
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public PricingRequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Serializes every mutating operation Step 6 (order confirmation) performs against a given
     * pricing request, against each other and against every earlier step's own advisory locks on
     * the same key. Mirrors {@code CustomerQuotationRepository.lockPricingRequest} exactly. */
    public void lockPricingRequest(long pricingRequestId) {
        jdbc.query("SELECT pg_advisory_xact_lock(:id)", Map.of("id", pricingRequestId), (rs, rowNum) -> 0);
    }

    public String nextRequestCode() {
        Long seq = jdbc.queryForObject("SELECT nextval('sales.pricing_request_code_seq')", Map.of(), Long.class);
        if (seq == null) {
            throw new IllegalStateException("sales.pricing_request_code_seq returned no value");
        }
        return "PCR-" + Year.now() + "-" + String.format("%04d", seq);
    }

    public long create(long ticketId, String requestCode, CreatePricingRequestRequest request, long actorId) {
        String targetCurrency = normalizeCurrency(request.targetCurrency());
        List<Long> ids = jdbc.query("""
            INSERT INTO sales.pricing_request
                (request_code, ticket_id, recipient_type, recipient_contact_id, recipient_label,
                 status, requested_by, required_date, customer_target_price, target_currency, note,
                 client_request_id)
            VALUES
                (:requestCode, :ticketId, :recipientType, :recipientContactId, :recipientLabel,
                 'DRAFT', :requestedBy, :requiredDate, :customerTargetPrice, :targetCurrency, :note,
                 CAST(:clientRequestId AS uuid))
            ON CONFLICT (requested_by, client_request_id)
            WHERE client_request_id IS NOT NULL
            DO NOTHING
            RETURNING pricing_request_id
            """,
            new MapSqlParameterSource()
                .addValue("requestCode", requestCode)
                .addValue("ticketId", ticketId)
                .addValue("recipientType", request.recipientType())
                .addValue("recipientContactId", request.recipientContactId())
                .addValue("recipientLabel", request.recipientLabel())
                .addValue("requestedBy", actorId)
                .addValue("requiredDate", request.requiredDate())
                .addValue("customerTargetPrice", request.customerTargetPrice())
                .addValue("targetCurrency", targetCurrency)
                .addValue("note", request.note())
                .addValue("clientRequestId", request.clientRequestId()),
            (rs, rowNum) -> rs.getLong("pricing_request_id"));
        if (ids.isEmpty()) {
            return 0L;
        }
        long pricingRequestId = ids.get(0);
        replaceItems(pricingRequestId, request.items());
        return pricingRequestId;
    }

    public void replaceItems(long pricingRequestId, List<PricingRequestItemRequest> items) {
        jdbc.update("DELETE FROM sales.pricing_request_item WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId));
        if (items.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            PricingRequestItemRequest item = items.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("sourceTicketItemId", item.sourceTicketItemId())
                .addValue("productId", item.productId())
                .addValue("variantId", item.variantId())
                .addValue("brand", item.brand())
                .addValue("model", item.model())
                .addValue("productDescription", item.productDescription())
                .addValue("color", item.color())
                .addValue("texture", item.texture())
                .addValue("size", item.size())
                .addValue("factory", item.factory())
                .addValue("requestedQty", item.requestedQty())
                .addValue("requestedQtySqm", item.requestedQtySqm())
                .addValue("requestedUnit", item.requestedUnit())
                .addValue("requestedUnitBasis", item.requestedUnitBasis())
                .addValue("quantityType", item.quantityType())
                .addValue("targetDeliveryDate", item.targetDeliveryDate())
                .addValue("deliveryLocation", item.deliveryLocation())
                .addValue("specialRequirement", item.specialRequirement())
                .addValue("sortOrder", i);
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.pricing_request_item
                (pricing_request_id, source_ticket_item_id, product_id, variant_id,
                 brand, model, product_description, color, texture, size, factory,
                 requested_qty, requested_qty_sqm, requested_unit, requested_unit_basis, quantity_type,
                 target_delivery_date, delivery_location, special_requirement, sort_order)
            VALUES
                (:pricingRequestId, :sourceTicketItemId, :productId, :variantId,
                 :brand, :model, :productDescription, :color, :texture, :size, :factory,
                 :requestedQty, :requestedQtySqm, :requestedUnit, :requestedUnitBasis, :quantityType,
                 :targetDeliveryDate, :deliveryLocation, :specialRequirement, :sortOrder)
            """, batch);
    }

    /**
     * Compare-and-set status transition. Returns the rowcount (0 or 1); the caller
     * (service) must throw a 409 when it returns 0 rather than following up with a
     * SELECT to build a nicer error message — that would reintroduce the race this
     * WHERE status = :expected clause exists to close.
     *
     * <p><strong>Review remediation (COMMIT 5):</strong> {@code expected -> next} is now asserted
     * against {@link PricingRequestStatus#canTransition} before the UPDATE runs — previously
     * {@link PricingRequestStatus#ALLOWED} was decorative (nothing consulted it outside {@code
     * cancel()}'s own pre-check), so {@code PricingCostingService.createDraft()} could freely move
     * a {@code READY_FOR_CEO_REVIEW} request back to {@code COSTING_IN_PROGRESS} even though the
     * canonical map only listed {@code SUPERSEDED} as reachable from there. This throws {@link
     * IllegalStateException}, not an {@code ApiException} 409: an illegal {@code expected -> next}
     * pair means the CALLING CODE asked for a transition the state machine does not recognise —
     * a programming error to catch at the source, not a normal concurrent-user race (that case is
     * what the {@code WHERE status = :expected} compare-and-set above already handles via a 0
     * rowcount, which the service layer turns into a 409). This class's own Javadoc says
     * persistence code should not carry workflow validation; this is the one deliberate exception,
     * treated as a repository-level invariant guard (the state machine must be authoritative
     * everywhere `transition` is called), not as business-rule validation belonging in the service.
     *
     * <p>The one intentional bypass of this assertion is {@link #cancelForDeadDeal} — used
     * exclusively by {@code PricingRequestService#cancelOpenForTicket}'s dead-deal cascade, which
     * must be able to cancel a request from ANY open status (including one, like {@code
     * READY_FOR_CEO_REVIEW}, that is not normally cancellable by a live user action) once the deal
     * itself has gone terminal. Use that method, never this one, for that cascade.
     */
    public int transition(long id, String expected, String next, Long assignImportId, Long cancelledBy) {
        if (!PricingRequestStatus.canTransition(expected, next)) {
            throw new IllegalStateException(
                "Illegal pricing request status transition: " + expected + " -> " + next);
        }
        return jdbc.update("""
            UPDATE sales.pricing_request
               SET status       = :next,
                   submitted_at = CASE WHEN :next = 'SUBMITTED'
                                       THEN COALESCE(submitted_at, now()) ELSE submitted_at END,
                   -- COALESCE(picked_up_at, now()) is what makes MORE_INFO_REQUIRED ->
                   -- IMPORT_REVIEWING preserve the ORIGINAL pickup time. A bare now()
                   -- would reset it on every info round-trip and destroy the
                   -- "how long has Import held this" metric.
                   picked_up_at = CASE WHEN :next = 'IMPORT_REVIEWING'
                                       THEN COALESCE(picked_up_at, now()) ELSE picked_up_at END,
                   -- First-writer-wins: respond-information passes assignImportId=null and
                   -- the existing assignee survives; a second Import user cannot steal an
                   -- already-assigned request by picking it up again.
                   assigned_import_id = COALESCE(assigned_import_id, :assignImportId),
                   cancelled_at = CASE WHEN :next = 'CANCELLED' THEN now() ELSE cancelled_at END,
                   cancelled_by = CASE WHEN :next = 'CANCELLED' THEN :cancelledBy ELSE cancelled_by END,
                   updated_at   = now()
             WHERE pricing_request_id = :id AND status = :expected
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expected", expected)
                .addValue("next", next)
                .addValue("assignImportId", assignImportId)
                .addValue("cancelledBy", cancelledBy));
    }

    /**
     * Cancels a pricing request as part of {@code PricingRequestService#cancelOpenForTicket}'s
     * dead-deal cascade (review remediation COMMIT 5) — the one place a status transition must
     * bypass {@link PricingRequestStatus#canTransition}, since a deal reaching a terminal
     * lifecycle state must be able to cancel a pricing request from ANY open status, including
     * {@code READY_FOR_CEO_REVIEW} (which {@link PricingRequestStatus#ALLOWED} does not permit a
     * live user to cancel directly — only {@code SUPERSEDED}/{@code COSTING_IN_PROGRESS} are
     * reachable from there for a normal in-flight workflow). Do not call this from anywhere else;
     * every other caller must use {@link #transition} so the state machine stays authoritative.
     *
     * <p>Same compare-and-set shape as {@link #transition}, deliberately narrowed to only the
     * columns a cancellation actually touches (no {@code assignImportId}/{@code submitted_at}/
     * {@code picked_up_at} handling — a dead-deal cancel never needs any of those).
     */
    public int cancelForDeadDeal(long id, String expected, long cancelledBy) {
        return jdbc.update("""
            UPDATE sales.pricing_request
               SET status       = 'CANCELLED',
                   cancelled_at = now(),
                   cancelled_by = :cancelledBy,
                   updated_at   = now()
             WHERE pricing_request_id = :id AND status = :expected
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expected", expected)
                .addValue("cancelledBy", cancelledBy));
    }

    public int requestMoreInformation(long id, String expectedResumeStatus) {
        return jdbc.update("""
            UPDATE sales.pricing_request
               SET status = 'MORE_INFO_REQUIRED',
                   resume_status = :resumeStatus,
                   updated_at = now()
             WHERE pricing_request_id = :id
               AND status = :resumeStatus
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("resumeStatus", expectedResumeStatus));
    }

    public Optional<String> findResumeStatus(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT resume_status
                  FROM sales.pricing_request
                 WHERE pricing_request_id = :id
                """, Map.of("id", id), String.class));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public int resumeFromMoreInformation(long id, String nextStatus) {
        return jdbc.update("""
            UPDATE sales.pricing_request
               SET status = :nextStatus,
                   resume_status = NULL,
                   picked_up_at = CASE WHEN :nextStatus = 'IMPORT_REVIEWING'
                                       THEN COALESCE(picked_up_at, now()) ELSE picked_up_at END,
                   updated_at = now()
             WHERE pricing_request_id = :id
               AND status = 'MORE_INFO_REQUIRED'
               AND COALESCE(resume_status, 'IMPORT_REVIEWING') = :nextStatus
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("nextStatus", nextStatus));
    }

    /**
     * Full-replacement update of a DRAFT's editable fields (review-remediation
     * plan, Fix 2). Used to COALESCE(:x, col) every scalar column, which meant
     * a {@code null} in the request could never clear a field — there was no
     * way for a client to express "remove the target price / required date /
     * contact / note", only "leave it" or "overwrite it with a non-null
     * value". Now that {@code PricingRequestCreateModal} (edit mode) submits
     * the *entire* editable representation on every PUT, a {@code null} means
     * exactly what it says: clear this field. {@code recipient_type} is
     * NOT NULL in the DB, so {@link PricingRequestService#updateDraft} must
     * validate it unconditionally before this method is ever called — a null
     * here would otherwise surface as a raw constraint violation (500)
     * instead of a 400. {@code items} keeps its own "provide to replace,
     * omit to leave untouched" behavior (see the {@code rows == 1 &&
     * request.items() != null} guard below) — unrelated to this change,
     * since it was already a full delete+reinsert via {@link #replaceItems}
     * whenever a list was supplied.
     */
    public boolean updateDraft(long id, UpdatePricingRequestRequest request) {
        int rows = jdbc.update("""
            UPDATE sales.pricing_request
               SET recipient_type        = :recipientType,
                   recipient_contact_id  = :recipientContactId,
                   recipient_label       = :recipientLabel,
                   required_date         = :requiredDate,
                   customer_target_price = :customerTargetPrice,
                   target_currency       = :targetCurrency,
                   note                  = :note,
                   updated_at            = now()
             WHERE pricing_request_id = :id AND status = 'DRAFT'
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("recipientType", request.recipientType())
                .addValue("recipientContactId", request.recipientContactId())
                .addValue("recipientLabel", request.recipientLabel())
                .addValue("requiredDate", request.requiredDate())
                .addValue("customerTargetPrice", request.customerTargetPrice())
                .addValue("targetCurrency", normalizeCurrency(request.targetCurrency()))
                .addValue("note", request.note()));
        if (rows == 1 && request.items() != null) {
            replaceItems(id, request.items());
        }
        return rows == 1;
    }

    /**
     * Review remediation (COMMIT 5, P2 finding 2): the next {@code revision_no} used to be
     * computed via a bare {@code SELECT COALESCE(MAX(revision_no),0)+1} with no lock, so two
     * concurrent customer-change-revision calls racing the same chain (different
     * {@code clientRequestId}s — not a retry of the same one) could both read the same MAX before
     * either INSERT committed. Mirrors {@code FactoryQuoteRepository.createRevision}'s
     * {@code pg_advisory_xact_lock(rootId)} pattern exactly: held for the remainder of this
     * (transactional) call, so a second racing caller blocks here until the first commits, then
     * recomputes {@code revision_no} against the now-visible row instead of colliding with it.
     */
    public long createCustomerChangeRevision(PricingRequestSummaryDto parent, CustomerChangeRevisionRequest request,
                                             long actorId) {
        Long rootId = findRootPricingRequestId(parent.id()).orElse(parent.id());
        jdbc.query("SELECT pg_advisory_xact_lock(:rootId)", Map.of("rootId", rootId), (rs, rowNum) -> 0);
        Integer nextRevision = jdbc.queryForObject("""
            SELECT COALESCE(MAX(revision_no), 0) + 1
              FROM sales.pricing_request
             WHERE pricing_request_id = :rootId
                OR root_pricing_request_id = :rootId
            """, Map.of("rootId", rootId), Integer.class);
        String targetCurrency = normalizeCurrency(request.targetCurrency());
        List<Long> ids = jdbc.query("""
            INSERT INTO sales.pricing_request
                (request_code, ticket_id, recipient_type, recipient_contact_id, recipient_label,
                 status, requested_by, required_date, customer_target_price, target_currency, note,
                 parent_pricing_request_id, root_pricing_request_id, revision_no, revision_reason,
                 client_request_id)
            VALUES
                (:requestCode, :ticketId, :recipientType, :recipientContactId, :recipientLabel,
                 'DRAFT', :requestedBy, :requiredDate, :customerTargetPrice, :targetCurrency, :note,
                 :parentId, :rootId, :revisionNo, :revisionReason, CAST(:clientRequestId AS uuid))
            ON CONFLICT (requested_by, client_request_id)
            WHERE client_request_id IS NOT NULL
            DO NOTHING
            RETURNING pricing_request_id
            """,
            new MapSqlParameterSource()
                .addValue("requestCode", nextRequestCode())
                .addValue("ticketId", parent.ticketId())
                .addValue("recipientType", request.recipientType())
                .addValue("recipientContactId", request.recipientContactId())
                .addValue("recipientLabel", request.recipientLabel())
                .addValue("requestedBy", actorId)
                .addValue("requiredDate", request.requiredDate())
                .addValue("customerTargetPrice", request.customerTargetPrice())
                .addValue("targetCurrency", targetCurrency)
                .addValue("note", request.note())
                .addValue("parentId", parent.id())
                .addValue("rootId", rootId)
                .addValue("revisionNo", nextRevision)
                .addValue("revisionReason", request.revisionReason())
                .addValue("clientRequestId", request.clientRequestId()),
            (rs, rowNum) -> rs.getLong("pricing_request_id"));
        if (ids.isEmpty()) {
            return 0L;
        }
        long pricingRequestId = ids.get(0);
        replaceItems(pricingRequestId, request.items());
        return pricingRequestId;
    }

    public Optional<Long> findRootPricingRequestId(long pricingRequestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT root_pricing_request_id
                  FROM sales.pricing_request
                 WHERE pricing_request_id = :id
                """, Map.of("id", pricingRequestId), Long.class));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public int supersedeForCustomerRevision(long id, long supersededByPricingRequestId) {
        return jdbc.update("""
            UPDATE sales.pricing_request
               SET status = 'SUPERSEDED',
                   superseded_at = now(),
                   superseded_by_pricing_request_id = :supersededBy,
                   updated_at = now()
             WHERE pricing_request_id = :id
               AND status <> 'SUPERSEDED'
               AND status <> 'CANCELLED'
            """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("supersededBy", supersededByPricingRequestId));
    }

    public void addEvent(long pricingRequestId, long ticketId, Long actorId, String actorName,
                         String eventKind, String fromStatus, String toStatus, String message,
                         String metadataJson) {
        jdbc.update("""
            INSERT INTO sales.pricing_request_event
                (pricing_request_id, ticket_id, actor_id, actor_name, event_kind,
                 from_status, to_status, message, metadata)
            VALUES
                (:pricingRequestId, :ticketId, :actorId, :actorName, :eventKind,
                 :fromStatus, :toStatus, :message,
                 -- COALESCE(CAST(:metadata AS jsonb), '{}'::jsonb), NOT :metadata::jsonb:
                 -- with a null bind, COALESCE(:x::jsonb, ...) leaves Postgres unable to
                 -- infer the parameter type and you get "could not determine data type of
                 -- parameter $N". metadataJson is a raw JSON String — the service owns
                 -- Jackson serialisation, keeping this class Jackson-free.
                 COALESCE(CAST(:metadata AS jsonb), '{}'::jsonb))
            """,
            new MapSqlParameterSource()
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("ticketId", ticketId)
                .addValue("actorId", actorId)
                .addValue("actorName", actorName)
                .addValue("eventKind", eventKind)
                .addValue("fromStatus", fromStatus)
                .addValue("toStatus", toStatus)
                .addValue("message", message)
                .addValue("metadata", metadataJson));
    }

    /**
     * True if an event of {@code eventKind} tagged with {@code "dispatchId": <dispatchId>} in its
     * {@code metadata} already exists for this pricing request. Used by {@code
     * FactoryQuoteService.finalizeDispatch} as defense-in-depth against duplicating the
     * factory-email-sent event/notification on a re-run — see that method's javadoc.
     */
    public boolean existsEventForDispatch(long pricingRequestId, String eventKind, long dispatchId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request_event
             WHERE pricing_request_id = :pricingRequestId
               AND event_kind = :eventKind
               AND metadata->>'dispatchId' = :dispatchId
            """,
            new MapSqlParameterSource()
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("eventKind", eventKind)
                .addValue("dispatchId", String.valueOf(dispatchId)),
            Integer.class);
        return count != null && count > 0;
    }

    public Optional<PricingRequestSummaryDto> findSummary(long id) {
        try {
            PricingRequestSummaryDto summary = jdbc.queryForObject(
                SUMMARY_SELECT + " WHERE pr.pricing_request_id = :id",
                Map.of("id", id), (rs, rowNum) -> mapSummary(rs));
            return Optional.ofNullable(summary);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<PricingRequestSummaryDto> findByClientRequestId(long requestedBy, String clientRequestId) {
        try {
            PricingRequestSummaryDto summary = jdbc.queryForObject(
                SUMMARY_SELECT + """
                 WHERE pr.requested_by = :requestedBy
                   AND pr.client_request_id = CAST(:clientRequestId AS uuid)
                """,
                new MapSqlParameterSource()
                    .addValue("requestedBy", requestedBy)
                    .addValue("clientRequestId", clientRequestId),
                (rs, rowNum) -> mapSummary(rs));
            return Optional.ofNullable(summary);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<PricingRequestItemDto> findItems(long pricingRequestId) {
        return jdbc.query("""
            SELECT pricing_request_item_id, pricing_request_id, source_ticket_item_id, product_id, variant_id,
                   brand, model, product_description, color, texture, size, factory,
                   requested_qty, requested_qty_sqm, requested_unit, requested_unit_basis, quantity_type,
                   target_delivery_date, delivery_location, special_requirement, sort_order,
                   price_list_version_id, catalog_price_id, catalog_base_price, catalog_currency,
                   catalog_effective_date, resolved_factory_id, resolved_factory_name,
                   catalog_product_code, catalog_brand, catalog_collection, catalog_model
              FROM sales.pricing_request_item
             WHERE pricing_request_id = :id
             ORDER BY sort_order, pricing_request_item_id
            """,
            Map.of("id", pricingRequestId),
            (rs, rowNum) -> mapItem(rs));
    }

    public List<Long> findUnresolvableCatalogItemIds(long pricingRequestId) {
        return jdbc.query("""
            SELECT pri.pricing_request_item_id
              FROM sales.pricing_request_item pri
              LEFT JOIN price_catalog.product_prices pp ON pp.price_id = pri.product_id
              LEFT JOIN price_catalog.price_list_versions plv ON plv.version_id = pp.version_id
             WHERE pri.pricing_request_id = :pricingRequestId
               AND pri.product_id IS NOT NULL
               AND (pp.price_id IS NULL OR plv.status <> 'ACTIVE')
             ORDER BY pri.sort_order, pri.pricing_request_item_id
            """, Map.of("pricingRequestId", pricingRequestId), (rs, rowNum) -> rs.getLong("pricing_request_item_id"));
    }

    public int snapshotCatalogSelections(long pricingRequestId) {
        return jdbc.update("""
            UPDATE sales.pricing_request_item pri
               SET price_list_version_id = plv.version_id,
                   catalog_price_id = pp.price_id,
                   catalog_base_price = pp.price,
                   catalog_currency = pp.currency,
                   catalog_effective_date = plv.effective_from,
                   resolved_factory_id = f.factory_id,
                   resolved_factory_name = f.name,
                   catalog_product_code = pp.product_code,
                   catalog_brand = COALESCE(pri.brand, pp.grade),
                   catalog_collection = pp.collection,
                   catalog_model = pp.product_name,
                   factory = COALESCE(NULLIF(BTRIM(pri.factory), ''), f.name)
              FROM price_catalog.product_prices pp
              JOIN price_catalog.price_list_versions plv ON plv.version_id = pp.version_id
              JOIN price_catalog.factories f ON f.factory_id = pp.factory_id
             WHERE pri.pricing_request_id = :pricingRequestId
               AND pri.product_id = pp.price_id
               AND plv.status = 'ACTIVE'
            """, Map.of("pricingRequestId", pricingRequestId));
    }

    public List<PricingRequestEventDto> findEvents(long pricingRequestId) {
        return jdbc.query("""
            SELECT pricing_request_event_id, pricing_request_id, ticket_id, actor_id, actor_name,
                   event_kind, from_status, to_status, message, metadata::text AS metadata, created_at
              FROM sales.pricing_request_event
             WHERE pricing_request_id = :id
             ORDER BY created_at ASC, pricing_request_event_id ASC
            """,
            Map.of("id", pricingRequestId),
            (rs, rowNum) -> mapEvent(rs));
    }

    public List<PricingRequestSummaryDto> findSummaries(String status, Long assignedImportId,
                                                         Long createdByFilter, boolean activeDealsOnly,
                                                         boolean draftOversight, Long draftOwnerId) {
        StringBuilder sql = new StringBuilder(SUMMARY_SELECT).append(" WHERE 1=1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (status != null) {
            sql.append(" AND pr.status = :status ");
            params.addValue("status", status);
        } else {
            // Default queue: exclude dead rows so CANCELLED requests do not pollute it.
            sql.append(" AND pr.status <> 'CANCELLED' ");
        }
        if (assignedImportId != null) {
            sql.append(" AND pr.assigned_import_id = :assignedImportId ");
            params.addValue("assignedImportId", assignedImportId);
        }
        if (createdByFilter != null) {
            sql.append(" AND t.created_by = :createdByFilter ");
            params.addValue("createdByFilter", createdByFilter);
        }
        if (activeDealsOnly) {
            sql.append(" AND t.lifecycle = 'ACTIVE' ");
        }
        // A DRAFT is the owning rep's private scratchpad (see PricingRequestService's
        // class Javadoc) — it must never surface in any list for anyone else, no
        // matter what status/assignedImportId/createdByFilter/activeDealsOnly the
        // caller passed. draftOversight is true only for ceo/sales_manager (managerial
        // oversight); everyone else only sees a DRAFT row when they are its owner
        // (t.created_by = draftOwnerId, which the service always sets to the caller's
        // own id). This is independent of createdByFilter above, which only applies to
        // the "sales" role and is a distinct concern (own-deals-only scoping).
        sql.append(" AND (pr.status <> 'DRAFT' OR :draftOversight = TRUE OR t.created_by = :draftOwnerId) ");
        params.addValue("draftOversight", draftOversight);
        params.addValue("draftOwnerId", draftOwnerId);
        sql.append(" ORDER BY pr.created_at DESC, pr.pricing_request_id DESC ");
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> mapSummary(rs));
    }

    public List<PricingRequestSummaryDto> findByTicket(long ticketId) {
        return jdbc.query(
            SUMMARY_SELECT + " WHERE pr.ticket_id = :ticketId ORDER BY pr.created_at DESC, pr.pricing_request_id DESC",
            Map.of("ticketId", ticketId),
            (rs, rowNum) -> mapSummary(rs));
    }

    public List<Long> findOpenIdsForTicket(long ticketId) {
        return jdbc.query("""
            SELECT pricing_request_id
              FROM sales.pricing_request
             WHERE ticket_id = :ticketId AND status <> 'CANCELLED'
            """,
            Map.of("ticketId", ticketId),
            (rs, rowNum) -> rs.getLong("pricing_request_id"));
    }

    public void cancelOpenStep2Children(long pricingRequestId, String reason, long actorId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("pricingRequestId", pricingRequestId)
            .addValue("reason", reason)
            .addValue("actorId", actorId);
        jdbc.update("""
            UPDATE sales.factory_quote
               SET status = 'CANCELLED',
                   is_current = FALSE,
                   cancel_reason = :reason,
                   cancelled_by = :actorId,
                   cancelled_at = now(),
                   updated_at = now()
             WHERE pricing_request_id = :pricingRequestId
               AND status IN ('DRAFT', 'REQUESTED')
            """, params);
        jdbc.update("""
            UPDATE sales.pricing_costing
               SET status = 'CANCELLED',
                   stale = FALSE,
                   stale_reason = :reason,
                   updated_at = now()
             WHERE pricing_request_id = :pricingRequestId
               AND status IN ('DRAFT', 'CALCULATED')
            """, params);
    }

    /**
     * Step 5 (V75, design correction 1 — "the cascade gap"): {@link #cancelOpenStep2Children}
     * predates {@code sales.pricing_decision}/{@code sales.quotation} (Steps 3/4) and has zero
     * knowledge of either, so a customer-change (cost-affecting) revision superseding its parent
     * pricing request left a DRAFT/APPROVED decision and any non-terminal quotation silently
     * stale — still readable via {@code PricingDecisionService.salesView}/{@code get} and
     * {@code CustomerQuotationService.get} as if they were current. Called alongside
     * {@code cancelOpenStep2Children} from {@code PricingRequestService.createCustomerChangeRevision},
     * never from plain {@code cancel()}/{@code cancelOpenForTicket} — those cascades are
     * unchanged by this method existing.
     *
     * <p>Not routed through {@code PricingDecisionRepository}/{@code CustomerQuotationRepository}
     * (this class's own dependency direction stays one-way, same as {@code cancelOpenStep2Children}
     * already reaching into {@code sales.factory_quote}/{@code sales.pricing_costing} directly) —
     * the class-level "never call TicketRepository" invariant is specifically about ticket
     * tables, not every downstream aggregate.
     */
    public void supersedeOpenPricingDecisionAndQuotation(long pricingRequestId) {
        jdbc.update("""
            UPDATE sales.pricing_decision
               SET status = 'SUPERSEDED',
                   updated_at = now()
             WHERE pricing_request_id = :pricingRequestId
               AND status IN ('DRAFT', 'APPROVED')
            """, Map.of("pricingRequestId", pricingRequestId));
        jdbc.update("""
            UPDATE sales.quotation
               SET doc_status = 'SUPERSEDED'
             WHERE pricing_request_id = :pricingRequestId
               AND doc_status IN ('ISSUED', 'READY_TO_ISSUE', 'SENT', 'REVISION_REQUESTED')
            """, Map.of("pricingRequestId", pricingRequestId));
    }

    /**
     * Step 6 (V76): the order-confirmation bridge's own idempotency/state check — read under the
     * same {@link #lockPricingRequest} hold as the guarded update below, exactly like every prior
     * step's create/issue/outcome replay check (e.g. {@code CustomerQuotationRepository
     * .findIdByIssueClientRequestId}).
     */
    public record OrderConfirmationState(boolean confirmed, String clientRequestId) {}

    public OrderConfirmationState findOrderConfirmationState(long pricingRequestId) {
        return jdbc.queryForObject("""
            SELECT order_confirmed_at IS NOT NULL AS confirmed,
                   order_confirm_client_request_id::text AS client_request_id
              FROM sales.pricing_request
             WHERE pricing_request_id = :id
            """, Map.of("id", pricingRequestId),
            (rs, rowNum) -> new OrderConfirmationState(
                rs.getBoolean("confirmed"), rs.getString("client_request_id")));
    }

    /**
     * Compare-and-set: only succeeds from {@code QUOTATION_ACCEPTED} (Step 5's terminal status —
     * intentionally NOT routed through {@link #transition}/{@link PricingRequestStatus#ALLOWED},
     * since {@code status} itself does not change here; only the order-confirmation columns do)
     * and only once ({@code order_confirmed_at IS NULL}). Rowcount 0 means either "not
     * QUOTATION_ACCEPTED yet" or "already confirmed" — {@code OrderConfirmationService}
     * distinguishes the two via {@link #findOrderConfirmationState}, called first under the same
     * lock hold, exactly like {@code CustomerQuotationService.issue}'s own replay-vs-conflict split.
     */
    public int markOrderConfirmed(long pricingRequestId, long actorId, String clientRequestId) {
        return jdbc.update("""
            UPDATE sales.pricing_request
               SET order_confirmed_at = now(),
                   order_confirmed_by = :actorId,
                   order_confirm_client_request_id = CAST(:clientRequestId AS uuid),
                   updated_at = now()
             WHERE pricing_request_id = :id
               AND status = 'QUOTATION_ACCEPTED'
               AND order_confirmed_at IS NULL
            """,
            new MapSqlParameterSource()
                .addValue("id", pricingRequestId)
                .addValue("actorId", actorId)
                .addValue("clientRequestId", clientRequestId));
    }

    /**
     * The ticket_item ids that legally belong to this ticket — used by the
     * service layer to validate a pricing-request item's sourceTicketItemId
     * before persisting (commit 3). Lives here rather than on TicketRepository
     * because this repository's only consumer for the query is PricingRequest
     * validation; no ticket workflow needs it.
     */
    public List<Long> findItemIdsForTicket(long ticketId) {
        return jdbc.query(
            "SELECT item_id FROM sales.ticket_item WHERE ticket_id = :ticketId",
            Map.of("ticketId", ticketId),
            (rs, rowNum) -> rs.getLong("item_id"));
    }

    // --- Pricing Request attachments (V69, review remediation COMMIT 4) ---
    //
    // Sales-level supporting attachments on the Pricing Request itself — distinct from
    // FactoryQuoteRepository's raw supplier-quote attachments. A dedicated table (not a reuse of
    // hr.file_attachment's generic domain/owner_id shape) per the approved remediation plan: this
    // is fully owned by sales.pricing_request and needs its own include_in_factory_email column,
    // which would otherwise leak a pricing-request-specific concern onto a table shared with
    // unrelated domains (e.g. hr.leave_request attachments).

    public PricingRequestAttachmentDto saveAttachment(long pricingRequestId, String fileName, String filePath,
                                                       String mimeType, Long fileSize, long uploadedBy) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.pricing_request_attachment
                (pricing_request_id, file_name, file_path, mime_type, file_size, uploaded_by)
            VALUES
                (:pricingRequestId, :fileName, :filePath, :mimeType, :fileSize, :uploadedBy)
            """,
            new MapSqlParameterSource()
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("fileName", fileName)
                .addValue("filePath", filePath)
                .addValue("mimeType", mimeType)
                .addValue("fileSize", fileSize)
                .addValue("uploadedBy", uploadedBy),
            key, new String[]{"pricing_request_attachment_id"});
        return findAttachment(key.getKey().longValue()).orElseThrow();
    }

    public List<PricingRequestAttachmentDto> findAttachments(long pricingRequestId) {
        return jdbc.query("""
            SELECT pricing_request_attachment_id, pricing_request_id, file_name, mime_type,
                   file_size, include_in_factory_email, uploaded_by, uploaded_at
              FROM sales.pricing_request_attachment
             WHERE pricing_request_id = :pricingRequestId
             ORDER BY uploaded_at DESC, pricing_request_attachment_id DESC
            """, Map.of("pricingRequestId", pricingRequestId), (rs, rowNum) -> mapAttachment(rs));
    }

    public Optional<PricingRequestAttachmentDto> findAttachment(long attachmentId) {
        try {
            PricingRequestAttachmentDto dto = jdbc.queryForObject("""
                SELECT pricing_request_attachment_id, pricing_request_id, file_name, mime_type,
                       file_size, include_in_factory_email, uploaded_by, uploaded_at
                  FROM sales.pricing_request_attachment
                 WHERE pricing_request_attachment_id = :attachmentId
                """, Map.of("attachmentId", attachmentId), (rs, rowNum) -> mapAttachment(rs));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public String findAttachmentFilePath(long attachmentId) {
        try {
            return jdbc.queryForObject("""
                SELECT file_path FROM sales.pricing_request_attachment
                 WHERE pricing_request_attachment_id = :attachmentId
                """, Map.of("attachmentId", attachmentId), String.class);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public void deleteAttachment(long attachmentId) {
        jdbc.update("""
            DELETE FROM sales.pricing_request_attachment WHERE pricing_request_attachment_id = :attachmentId
            """, Map.of("attachmentId", attachmentId));
    }

    public int setIncludeInFactoryEmail(long attachmentId, boolean include) {
        return jdbc.update("""
            UPDATE sales.pricing_request_attachment
               SET include_in_factory_email = :include
             WHERE pricing_request_attachment_id = :attachmentId
            """, Map.of("attachmentId", attachmentId, "include", include));
    }

    /**
     * Server-internal file handle for outbound email use only — deliberately NOT the public
     * {@link PricingRequestAttachmentDto} (which never exposes a local disk path). Used solely by
     * {@code FactoryQuoteService.attemptSend}, resolved fresh at actual-send time so a late
     * {@code include_in_factory_email} toggle is honoured up to the moment the worker really
     * calls the mail provider.
     */
    public record PricingRequestEmailAttachmentFile(String fileName, String filePath, String mimeType) {}

    public List<PricingRequestEmailAttachmentFile> findIncludedInFactoryEmailAttachmentFiles(long pricingRequestId) {
        return jdbc.query("""
            SELECT file_name, file_path, mime_type
              FROM sales.pricing_request_attachment
             WHERE pricing_request_id = :pricingRequestId
               AND include_in_factory_email = TRUE
             ORDER BY uploaded_at
            """, Map.of("pricingRequestId", pricingRequestId),
            (rs, rowNum) -> new PricingRequestEmailAttachmentFile(
                rs.getString("file_name"), rs.getString("file_path"), rs.getString("mime_type")));
    }

    private PricingRequestAttachmentDto mapAttachment(ResultSet rs) throws SQLException {
        return new PricingRequestAttachmentDto(
            rs.getLong("pricing_request_attachment_id"),
            rs.getLong("pricing_request_id"),
            rs.getString("file_name"),
            rs.getString("mime_type"),
            nullableLong(rs, "file_size"),
            rs.getBoolean("include_in_factory_email"),
            rs.getLong("uploaded_by"),
            rs.getTimestamp("uploaded_at").toInstant()
        );
    }

    // --- private helpers ---

    private String normalizeCurrency(String targetCurrency) {
        if (targetCurrency == null || targetCurrency.isBlank()) {
            return null;
        }
        return targetCurrency.trim().toUpperCase();
    }

    private PricingRequestSummaryDto mapSummary(ResultSet rs) throws SQLException {
        long recipientContactIdRaw = rs.getLong("recipient_contact_id");
        Long recipientContactId = rs.wasNull() ? null : recipientContactIdRaw;
        long assignedImportIdRaw = rs.getLong("assigned_import_id");
        Long assignedImportId = rs.wasNull() ? null : assignedImportIdRaw;
        long parentIdRaw = rs.getLong("parent_pricing_request_id");
        Long parentPricingRequestId = rs.wasNull() ? null : parentIdRaw;
        Timestamp submittedAt = rs.getTimestamp("submitted_at");
        Timestamp pickedUpAt = rs.getTimestamp("picked_up_at");
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        String requestedByName = joinName(
            rs.getString("requested_by_first_name_th"), rs.getString("requested_by_last_name_th"));
        String assignedImportName = joinName(
            rs.getString("assigned_import_first_name_th"), rs.getString("assigned_import_last_name_th"));
        return new PricingRequestSummaryDto(
            rs.getLong("pricing_request_id"),
            rs.getString("request_code"),
            rs.getLong("ticket_id"),
            rs.getString("ticket_code"),
            rs.getString("project_name"),
            rs.getString("customer_name"),
            rs.getLong("ticket_created_by"),
            rs.getString("recipient_type"),
            recipientContactId,
            rs.getString("recipient_label"),
            rs.getString("status"),
            rs.getLong("requested_by"),
            requestedByName,
            assignedImportId,
            assignedImportName,
            rs.getObject("required_date", LocalDate.class),
            rs.getBigDecimal("customer_target_price"),
            rs.getString("target_currency"),
            rs.getString("note"),
            rs.getInt("item_count"),
            rs.getInt("revision_no"),
            parentPricingRequestId,
            submittedAt != null ? submittedAt.toInstant() : null,
            pickedUpAt != null ? pickedUpAt.toInstant() : null,
            cancelledAt != null ? cancelledAt.toInstant() : null,
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            instant(rs, "order_confirmed_at")
        );
    }

    private java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private PricingRequestItemDto mapItem(ResultSet rs) throws SQLException {
        long sourceTicketItemIdRaw = rs.getLong("source_ticket_item_id");
        Long sourceTicketItemId = rs.wasNull() ? null : sourceTicketItemIdRaw;
        long productIdRaw = rs.getLong("product_id");
        Long productId = rs.wasNull() ? null : productIdRaw;
        long variantIdRaw = rs.getLong("variant_id");
        Long variantId = rs.wasNull() ? null : variantIdRaw;
        return new PricingRequestItemDto(
            rs.getLong("pricing_request_item_id"),
            rs.getLong("pricing_request_id"),
            sourceTicketItemId,
            productId,
            variantId,
            rs.getString("brand"),
            rs.getString("model"),
            rs.getString("product_description"),
            rs.getString("color"),
            rs.getString("texture"),
            rs.getString("size"),
            rs.getString("factory"),
            rs.getBigDecimal("requested_qty"),
            rs.getBigDecimal("requested_qty_sqm"),
            rs.getString("requested_unit"),
            rs.getString("requested_unit_basis"),
            rs.getString("quantity_type"),
            rs.getObject("target_delivery_date", LocalDate.class),
            rs.getString("delivery_location"),
            rs.getString("special_requirement"),
            rs.getInt("sort_order"),
            nullableLong(rs, "price_list_version_id"),
            nullableLong(rs, "catalog_price_id"),
            rs.getBigDecimal("catalog_base_price"),
            rs.getString("catalog_currency"),
            rs.getObject("catalog_effective_date", LocalDate.class),
            nullableLong(rs, "resolved_factory_id"),
            rs.getString("resolved_factory_name"),
            rs.getString("catalog_product_code"),
            rs.getString("catalog_brand"),
            rs.getString("catalog_collection"),
            rs.getString("catalog_model")
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private PricingRequestEventDto mapEvent(ResultSet rs) throws SQLException {
        long actorIdRaw = rs.getLong("actor_id");
        Long actorId = rs.wasNull() ? null : actorIdRaw;
        return new PricingRequestEventDto(
            rs.getLong("pricing_request_event_id"),
            rs.getLong("pricing_request_id"),
            rs.getLong("ticket_id"),
            actorId,
            rs.getString("actor_name"),
            rs.getString("event_kind"),
            rs.getString("from_status"),
            rs.getString("to_status"),
            rs.getString("message"),
            rs.getString("metadata"),
            rs.getTimestamp("created_at").toInstant()
        );
    }

    private String joinName(String firstNameTh, String lastNameTh) {
        String joined = ((firstNameTh == null ? "" : firstNameTh) + " " + (lastNameTh == null ? "" : lastNameTh)).trim();
        return joined.isEmpty() ? null : joined;
    }
}
