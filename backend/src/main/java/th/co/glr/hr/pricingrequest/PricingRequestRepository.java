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
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestEventDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.UpdatePricingRequestRequest;

/**
 * Persistence for the PricingRequest aggregate (V58): {@code sales.pricing_request},
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

    public String nextRequestCode() {
        Long seq = jdbc.queryForObject("SELECT nextval('sales.pricing_request_code_seq')", Map.of(), Long.class);
        if (seq == null) {
            throw new IllegalStateException("sales.pricing_request_code_seq returned no value");
        }
        return "PCR-" + Year.now() + "-" + String.format("%04d", seq);
    }

    public long create(long ticketId, String requestCode, CreatePricingRequestRequest request, long actorId) {
        String targetCurrency = normalizeCurrency(request.targetCurrency());
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.pricing_request
                (request_code, ticket_id, recipient_type, recipient_contact_id, recipient_label,
                 status, requested_by, required_date, customer_target_price, target_currency, note,
                 client_request_id)
            VALUES
                (:requestCode, :ticketId, :recipientType, :recipientContactId, :recipientLabel,
                 'DRAFT', :requestedBy, :requiredDate, :customerTargetPrice, :targetCurrency, :note,
                 CAST(:clientRequestId AS uuid))
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
            keyHolder, new String[]{"pricing_request_id"});
        long pricingRequestId = keyHolder.getKey().longValue();
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
                 requested_qty, requested_qty_sqm, requested_unit, quantity_type,
                 target_delivery_date, delivery_location, special_requirement, sort_order)
            VALUES
                (:pricingRequestId, :sourceTicketItemId, :productId, :variantId,
                 :brand, :model, :productDescription, :color, :texture, :size, :factory,
                 :requestedQty, :requestedQtySqm, :requestedUnit, :quantityType,
                 :targetDeliveryDate, :deliveryLocation, :specialRequirement, :sortOrder)
            """, batch);
    }

    /**
     * Compare-and-set status transition. Returns the rowcount (0 or 1); the caller
     * (service) must throw a 409 when it returns 0 rather than following up with a
     * SELECT to build a nicer error message — that would reintroduce the race this
     * WHERE status = :expected clause exists to close.
     */
    public int transition(long id, String expected, String next, Long assignImportId, Long cancelledBy) {
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
                   requested_qty, requested_qty_sqm, requested_unit, quantity_type,
                   target_delivery_date, delivery_location, special_requirement, sort_order
              FROM sales.pricing_request_item
             WHERE pricing_request_id = :id
             ORDER BY sort_order, pricing_request_item_id
            """,
            Map.of("id", pricingRequestId),
            (rs, rowNum) -> mapItem(rs));
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
            rs.getTimestamp("updated_at").toInstant()
        );
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
            rs.getString("quantity_type"),
            rs.getObject("target_delivery_date", LocalDate.class),
            rs.getString("delivery_location"),
            rs.getString("special_requirement"),
            rs.getInt("sort_order")
        );
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
