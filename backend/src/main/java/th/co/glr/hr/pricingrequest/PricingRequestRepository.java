package th.co.glr.hr.pricingrequest;

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
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteStatus;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestAttachmentDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestEventDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CustomerChangeRevisionRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.UpdatePricingRequestRequest;

/** Persistence for the PricingRequest aggregate (V59): sales.pricing_request, sales.pricing_request_item,
 * sales.pricing_request_event. CRITICAL INVARIANT: this repository must NEVER call
 * th.co.glr.hr.ticket.TicketRepository, nor write to sales.ticket / sales.ticket_event. */
@Repository
public class PricingRequestRepository {
    private static final String SUMMARY_SELECT = """
        SELECT pr.*, t.code AS ticket_code, t.title AS ticket_title, t.created_by AS ticket_created_by, t.keyed_by AS ticket_keyed_by,
               p.name AS project_name, t.customer_name, t.customer_id, t.status AS ticket_status, t.payment_status AS ticket_payment_status,
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
    /** Owns the sales.factory_quote half of #cancelOpenChildrenForDeadRequest. Both are stateless JDBC data-access
     * objects over the one template, so there is nothing to share and nothing to configure; keeping ONE
     * constructor also avoids handing Spring an ambiguous choice. */
    private final FactoryQuoteRepository factoryQuotes;

    public PricingRequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.factoryQuotes = new FactoryQuoteRepository(jdbc);
    }

    /** Serializes every mutating operation order confirmation performs against a given
     * pricing request, against each other and against the other advisory locks on
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
                 client_request_id, payment_term_mode, credit_days, validity_days,
                 printed_by_display_id, sales_rep_display_id, dept_code, unit_code,
                 omit_contact_honorific)
            VALUES
                (:requestCode, :ticketId, :recipientType, :recipientContactId, :recipientLabel,
                 'DRAFT', :requestedBy, :requiredDate, :customerTargetPrice, :targetCurrency, :note,
                 CAST(:clientRequestId AS uuid), :paymentTermMode, :creditDays, :validityDays,
                 :printedByDisplayId, :salesRepDisplayId, :deptCode, :unitCode,
                 :omitContactHonorific)
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
                .addValue("clientRequestId", request.clientRequestId())
                // Header terms — see PricingRequestRequests.CreatePricingRequestRequest's
                // own Javadoc.
                .addValue("paymentTermMode", request.paymentTermMode())
                .addValue("creditDays", request.creditDays())
                .addValue("validityDays", request.validityDays())
                .addValue("printedByDisplayId", request.printedByDisplayId())
                .addValue("salesRepDisplayId", request.salesRepDisplayId())
                .addValue("deptCode", request.deptCode())
                .addValue("unitCode", request.unitCode())
                // Mirrors sales.quotation.omit_contact_honorific's own NOT NULL DEFAULT FALSE —
                // a null input (the compat constructor's default) reads as false.
                .addValue("omitContactHonorific", request.omitContactHonorific() != null && request.omitContactHonorific()),
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
                .addValue("sortOrder", i)
                // V185 (direct-deal-form parity) — see PricingRequestItemRequest's own class-level Javadoc.
                .addValue("productCode", item.productCode())
                .addValue("thicknessMm", item.thicknessMm())
                .addValue("sqmPerPiece", item.sqmPerPiece())
                .addValue("quantityMode", item.quantityMode())
                .addValue("areaSqm", item.areaSqm())
                .addValue("piecesInput", item.piecesInput())
                .addValue("wastageMode", item.wastageMode())
                .addValue("wastageValue", item.wastageValue())
                .addValue("piecesPerBox", item.piecesPerBox())
                .addValue("sqmPerBox", item.sqmPerBox())
                .addValue("piecesBeforeWastage", item.piecesBeforeWastage())
                .addValue("piecesAfterWastage", item.piecesAfterWastage())
                .addValue("boxes", item.boxes())
                // Mirrors sales.quotation_item.round_to_full_box's own NOT NULL DEFAULT TRUE — a null input (never sent by
                // resolveItems, but defensive for any other caller) reads as true, today's only pre-V185 behaviour.
                .addValue("roundToFullBox", item.roundToFullBox() == null || item.roundToFullBox())
                .addValue("originCountry", item.originCountry())
                .addValue("leadTimeMinDays", item.leadTimeMinDays())
                .addValue("leadTimeMaxDays", item.leadTimeMaxDays())
                // Whatever PricingRequestService#resolveItem already normalized this to
                // (null unless originCountry = "อื่นๆ") — this repository does no normalization
                // itself, matching every other derived/validated item field on this same batch.
                .addValue("originCountryOther", item.originCountryOther())
                // Slice 1 stock-line scaffolding (V194): persisted exactly as sent; no validation or routing here (persistence
                // only, per this class's header Javadoc).
                .addValue("stockSource", item.stockSource())
                // Terms Sales fixes on the line; already normalized by the service.
                .addValue("requestedCurrency", item.requestedCurrency())
                .addValue("requestedPriceUnitBasis", item.requestedPriceUnitBasis());
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.pricing_request_item
                (pricing_request_id, source_ticket_item_id, product_id, variant_id,
                 brand, model, product_description, color, texture, size, factory,
                 requested_qty, requested_qty_sqm, requested_unit, requested_unit_basis, quantity_type,
                 target_delivery_date, delivery_location, special_requirement, sort_order,
                 product_code, thickness_mm, sqm_per_piece, quantity_mode, area_sqm, pieces_input,
                 wastage_mode, wastage_value, pieces_per_box, sqm_per_box, pieces_before_wastage,
                 pieces_after_wastage, boxes, round_to_full_box, origin_country,
                 lead_time_min_days, lead_time_max_days, origin_country_other, stock_source,
                 requested_currency, requested_price_unit_basis)
            VALUES
                (:pricingRequestId, :sourceTicketItemId, :productId, :variantId,
                 :brand, :model, :productDescription, :color, :texture, :size, :factory,
                 :requestedQty, :requestedQtySqm, :requestedUnit, :requestedUnitBasis, :quantityType,
                 :targetDeliveryDate, :deliveryLocation, :specialRequirement, :sortOrder,
                 :productCode, :thicknessMm, :sqmPerPiece, :quantityMode, :areaSqm, :piecesInput,
                 :wastageMode, :wastageValue, :piecesPerBox, :sqmPerBox, :piecesBeforeWastage,
                 :piecesAfterWastage, :boxes, :roundToFullBox, :originCountry,
                 :leadTimeMinDays, :leadTimeMaxDays, :originCountryOther, :stockSource,
                 :requestedCurrency, :requestedPriceUnitBasis)
            """, batch);
    }

    /** Compare-and-set status transition. Returns the rowcount (0 or 1); the caller (service) must throw a 409 when
     * it returns 0 rather than following up with a SELECT to build a nicer error message — that would reintroduce
     * the race this WHERE status = :expected clause exists to close. */
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
                   -- COALESCE(picked_up_at, now()), not a bare now(): any later arrival at
                   -- IMPORT_REVIEWING must preserve the ORIGINAL pickup time, or the
                   -- "how long has Import held this" metric resets. The round-trip that
                   -- used to re-enter IMPORT_REVIEWING (MORE_INFO_REQUIRED -> resume) was
                   -- retired by V140, so today only pickup reaches this branch and the
                   -- COALESCE is belt-and-braces — keep it: V140's own data migration
                   -- relies on exactly these semantics when it resumes parked rows.
                   picked_up_at = CASE WHEN :next = 'IMPORT_REVIEWING'
                                       THEN COALESCE(picked_up_at, now()) ELSE picked_up_at END,
                   -- First-writer-wins: a caller passing assignImportId=null leaves the
                   -- existing assignee intact, so a second Import user cannot steal an
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
     * dead-deal cascade — the one place a status transition must
     * bypass {@link PricingRequestStatus#canTransition}, since a deal reaching a terminal
     * lifecycle state must be able to cancel a pricing request from ANY open status, including the
     * two past the cancel cutoff — {@code QUOTATION_ISSUED} and {@code QUOTATION_ACCEPTED} — which
     * {@link PricingRequestStatus#ALLOWED} does not permit a live user to cancel directly, because
     * the customer already holds an offer there. That is precisely the case this method exists for:
     * a deal marked lost after a quotation went out must still close its pricing request. Do not
     * call this from anywhere else; every other caller must use {@link #transition} so the state
     * machine stays authoritative.
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

    /** Full-replacement update of a DRAFT's editable fields (review-remediation plan, Fix 2). Now that
     * PricingRequestCreateModal (edit mode) submits the *entire* editable representation on every PUT, a null
     * means exactly what it says: clear this field. */
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
                   payment_term_mode     = :paymentTermMode,
                   credit_days           = :creditDays,
                   validity_days         = :validityDays,
                   printed_by_display_id = :printedByDisplayId,
                   sales_rep_display_id  = :salesRepDisplayId,
                   dept_code             = :deptCode,
                   unit_code             = :unitCode,
                   omit_contact_honorific = :omitContactHonorific,
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
                .addValue("note", request.note())
                // Header terms — a FULL REPLACEMENT like every other editable field on
                // this UPDATE (this method's own class-level Javadoc: "full replacement... no more
                // COALESCE"), not a sparse patch.
                .addValue("paymentTermMode", request.paymentTermMode())
                .addValue("creditDays", request.creditDays())
                .addValue("validityDays", request.validityDays())
                .addValue("printedByDisplayId", request.printedByDisplayId())
                .addValue("salesRepDisplayId", request.salesRepDisplayId())
                .addValue("deptCode", request.deptCode())
                .addValue("unitCode", request.unitCode())
                .addValue("omitContactHonorific", request.omitContactHonorific() != null && request.omitContactHonorific()));
        if (rows == 1 && request.items() != null) {
            replaceItems(id, request.items());
        }
        return rows == 1;
    }

    /**
     * The next {@code revision_no} is computed under a lock: a bare
     * {@code SELECT COALESCE(MAX(revision_no),0)+1} would let two
     * concurrent customer-change-revision calls racing the same chain (different
     * {@code clientRequestId}s — not a retry of the same one) both read the same MAX before
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
                 client_request_id, payment_term_mode, credit_days, validity_days,
                 printed_by_display_id, sales_rep_display_id, dept_code, unit_code,
                 omit_contact_honorific)
            VALUES
                (:requestCode, :ticketId, :recipientType, :recipientContactId, :recipientLabel,
                 'DRAFT', :requestedBy, :requiredDate, :customerTargetPrice, :targetCurrency, :note,
                 :parentId, :rootId, :revisionNo, :revisionReason, CAST(:clientRequestId AS uuid),
                 :paymentTermMode, :creditDays, :validityDays, :printedByDisplayId,
                 :salesRepDisplayId, :deptCode, :unitCode, :omitContactHonorific)
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
                .addValue("clientRequestId", request.clientRequestId())
                // The revision's header terms come from WHATEVER the caller sent in
                // `request` (PricingRequestCreateModal.jsx's mode="revision" seeds these from the
                // CURRENT parent request, same as every other header field on a revision), never
                // copied server-side from `parent` directly — matching how every other header
                // field on this exact INSERT (recipientLabel, note, etc.) already works.
                .addValue("paymentTermMode", request.paymentTermMode())
                .addValue("creditDays", request.creditDays())
                .addValue("validityDays", request.validityDays())
                .addValue("printedByDisplayId", request.printedByDisplayId())
                .addValue("salesRepDisplayId", request.salesRepDisplayId())
                .addValue("deptCode", request.deptCode())
                .addValue("unitCode", request.unitCode())
                .addValue("omitContactHonorific", request.omitContactHonorific() != null && request.omitContactHonorific()),
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

    /**
     * Supersedes a pricing request in favour of a customer-change revision.
     *
     * <p><strong>This goes through the state machine.</strong> A NEGATIVE guard such as
     * {@code status <> 'SUPERSEDED' AND status <> 'CANCELLED'} would never consult
     * {@link PricingRequestStatus#ALLOWED}, and would supersede a request from any
     * status the map did not declare, including {@code QUOTATION_ACCEPTED} (declared terminal),
     * letting Sales silently rewrite the pricing behind a quotation the customer had already
     * accepted, with no CEO involvement and no transition check.
     *
     * <p>So it takes the caller's {@code expected} status and asserts {@code expected ->
     * SUPERSEDED} against {@link PricingRequestStatus#canTransition} first — the same invariant
     * guard, with the same {@link IllegalStateException} semantics and for the same reason, as
     * {@link #transition} (see that method's Javadoc: an illegal edge is a programming error at
     * the call site, not a user-facing race). The service layer is expected to check
     * {@code canTransition} itself and 409 before ever getting here, so reaching this throw means
     * a caller skipped that check.
     *
     * <p>It is a compare-and-set: {@code WHERE status = :expected}, so a
     * concurrent writer that moved the row between the service's read and this update yields
     * rowcount 0 (which {@code PricingRequestService#createCustomerChangeRevision} turns into a
     * 409) instead of silently superseding from a status the caller never saw.
     *
     * @return rowcount (0 or 1); 0 means the lost-update race.
     */
    public int supersedeForCustomerRevision(long id, String expected, long supersededByPricingRequestId) {
        if (!PricingRequestStatus.canTransition(expected, PricingRequestStatus.SUPERSEDED)) {
            throw new IllegalStateException(
                "Illegal pricing request status transition: " + expected + " -> "
                    + PricingRequestStatus.SUPERSEDED);
        }
        int rows = jdbc.update("""
            UPDATE sales.pricing_request
               SET status = 'SUPERSEDED',
                   superseded_at = now(),
                   superseded_by_pricing_request_id = :supersededBy,
                   updated_at = now()
             WHERE pricing_request_id = :id
               AND status = :expected
            """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expected", expected)
                .addValue("supersededBy", supersededByPricingRequestId));
        if (rows > 0) {
            // CR-1: a pending lead-time change dies with the superseded request.
            jdbc.update(LeadTimeChangeRepository.AUTO_WITHDRAW_SQL.formatted("c.pricing_request_id = :id"),
                new MapSqlParameterSource().addValue("id", id)
                    .addValue("autoReason", LeadTimeChangeRepository.AUTO_WITHDRAW_REASON));
        }
        return rows;
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

    /** True if an event of eventKind tagged with "dispatchId": <dispatchId> in its metadata already exists for this
     * pricing request. Used by FactoryQuoteService.finalizeDispatch as defense-in-depth against duplicating the
     * factory-email-sent event/notification on a re-run — see that method's javadoc. */
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
                   catalog_product_code, catalog_brand, catalog_collection, catalog_model,
                   product_type_override,
                   product_code, thickness_mm, sqm_per_piece, quantity_mode, area_sqm, pieces_input,
                   wastage_mode, wastage_value, pieces_per_box, sqm_per_box, pieces_before_wastage,
                   pieces_after_wastage, boxes, round_to_full_box, origin_country,
                   lead_time_min_days, lead_time_max_days, origin_country_other,
                   stock_source, expected_arrival_date,
                   requested_currency, requested_price_unit_basis
              FROM sales.pricing_request_item
             WHERE pricing_request_id = :id
             ORDER BY sort_order, pricing_request_item_id
            """,
            Map.of("id", pricingRequestId),
            (rs, rowNum) -> mapItem(rs));
    }

    /** V152 (V109 engine wiring): the CEO's per-item duty product_type override. productType == null clears the override
     * (back to LandedCostCalculator's TILE default) — mirrors PricingCostingRepository#applyOverride/clearOverride's
     * own null-means-clear convention for the sibling cost override. */
    public int updateItemProductTypeOverride(long pricingRequestItemId, String productType) {
        return jdbc.update("""
            UPDATE sales.pricing_request_item
               SET product_type_override = :productType
             WHERE pricing_request_item_id = :id
            """,
            new MapSqlParameterSource()
                .addValue("id", pricingRequestItemId)
                .addValue("productType", productType));
    }

    /**
     * Resolves a {@code price_catalog.factories} master row's canonical name by id — the picker
     * sends a factoryId, and this is what {@code PricingRequestService#setItemFactory}
     * uses to validate it and recover the exact name to persist. {@code Optional.empty()} when the
     * id does not exist; the service turns that into a 404 rather than trusting an arbitrary id.
     */
    public Optional<String> findFactoryNameById(long factoryId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT name FROM price_catalog.factories WHERE factory_id = :id",
                Map.of("id", factoryId), String.class));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Every master factory name, in factory_id order (read once per save and matched in memory). */
    public List<String> findMasterFactoryNames() {
        return jdbc.queryForList("SELECT name FROM price_catalog.factories ORDER BY factory_id", Map.of(), String.class);
    }

    public Optional<FactoryQuoteDto> findFactoryQuote(long factoryQuoteId) {
        return factoryQuotes.find(factoryQuoteId);
    }

    /** CEO fix: a CURRENT, non-cancelled quote of this request already named {@code factoryName}. */
    public boolean hasCurrentFactoryQuote(long pricingRequestId, String factoryName) {
        return factoryQuotes.findCurrentByFactory(pricingRequestId, factoryName).isPresent();
    }

    /** CEO fix: another CURRENT, non-cancelled quote of this request already carries {@code factoryId}
     * (V62's unique index would otherwise raise a raw 500). */
    public boolean hasCurrentFactoryQuoteWithFactoryId(long pricingRequestId, long factoryId, long exceptQuoteId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM sales.factory_quote
                            WHERE pricing_request_id = :pr AND factory_id = :f AND factory_quote_id <> :q
                              AND is_current = TRUE AND status <> 'CANCELLED')
            """, Map.of("pr", pricingRequestId, "f", factoryId, "q", exceptQuoteId), Boolean.class));
    }

    /**
     * CEO fix: re-points one factory quote (and every request line on it) from {@code oldName} to
     * the master factory {@code factoryId}/{@code newName}. Returns the number of lines moved. The
     * ticket line (what a later revision copies from) is only rewritten where it still carries the
     * old typo, so a value Sales has since changed is never clobbered. Import's prices are untouched.
     */
    public int repointFactoryQuote(long factoryQuoteId, long factoryId, String oldName, String newName) {
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("quoteId", factoryQuoteId).addValue("factoryId", factoryId)
            .addValue("old", oldName).addValue("new", newName);
        jdbc.update("""
            UPDATE sales.factory_quote
               SET factory_id = :factoryId, factory_name_snapshot = :new, updated_at = now()
             WHERE factory_quote_id = :quoteId
            """, p);
        jdbc.update("""
            UPDATE sales.ticket_item ti SET factory = :new
              FROM sales.pricing_request_item pri
              JOIN sales.factory_quote_item fqi ON fqi.pricing_request_item_id = pri.pricing_request_item_id
             WHERE fqi.factory_quote_id = :quoteId AND ti.item_id = pri.source_ticket_item_id AND ti.factory = :old
            """, p);
        return jdbc.update("""
            UPDATE sales.pricing_request_item pri
               SET factory = :new, resolved_factory_id = :factoryId,
                   resolved_factory_name = CASE WHEN pri.resolved_factory_name IS NULL THEN NULL ELSE :new END
              FROM sales.factory_quote_item fqi
             WHERE fqi.factory_quote_id = :quoteId AND fqi.pricing_request_item_id = pri.pricing_request_item_id
            """, p);
    }

    /** CEO fix: sets the sales-vocabulary origin country on one line (clears the "อื่นๆ" free text). */
    public int updateItemOriginCountry(long pricingRequestId, long itemId, String originCountry) {
        return jdbc.update("""
            UPDATE sales.pricing_request_item SET origin_country = :o, origin_country_other = NULL
             WHERE pricing_request_item_id = :id AND pricing_request_id = :prId
            """, Map.of("o", originCountry, "id", itemId, "prId", pricingRequestId));
    }

    /** True when the line's catalogue link (snapshot id, else live product id) resolves to a factory with a country. */
    public boolean catalogProvidesOriginCountry(long pricingRequestItemId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM sales.pricing_request_item pri
                             JOIN price_catalog.product_prices pp ON pp.price_id = COALESCE(pri.catalog_price_id, pri.product_id)
                             JOIN price_catalog.factories f ON f.factory_id = pp.factory_id
                            WHERE pri.pricing_request_item_id = :id AND f.country IS NOT NULL)
            """, Map.of("id", pricingRequestItemId), Boolean.class));
    }

    /** CEO fix: sets (or clears, null) the manual freight THB on one line. */
    public int updateItemManualFreight(long pricingRequestId, long itemId, java.math.BigDecimal freightThb) {
        return jdbc.update("""
            UPDATE sales.pricing_request_item SET manual_freight_thb = :f
             WHERE pricing_request_item_id = :id AND pricing_request_id = :prId
            """, new MapSqlParameterSource().addValue("f", freightThb).addValue("id", itemId).addValue("prId", pricingRequestId));
    }

    public Map<Long, java.math.BigDecimal> findManualFreights(long pricingRequestId) {
        Map<Long, java.math.BigDecimal> out = new java.util.HashMap<>();
        jdbc.query("SELECT pricing_request_item_id, manual_freight_thb FROM sales.pricing_request_item WHERE pricing_request_id = :id AND manual_freight_thb IS NOT NULL",
            Map.of("id", pricingRequestId), rs -> { out.put(rs.getLong(1), rs.getBigDecimal(2)); });
        return out;
    }

    /** CEO fix: renames one line; the catalogue snapshot names (which the UI prefers) follow when present. */
    public int renameItem(long pricingRequestId, long itemId, String brand, String model) {
        // Source ticket line first (compared against the OLD pair): rewritten only while it still equals it.
        // NULL-safe: ticket_item.brand is NOT NULL (blank for a cleared brand), the item's is NULL.
        jdbc.update("""
            UPDATE sales.ticket_item ti SET brand = COALESCE(:brand, ''), model = :model
              FROM sales.pricing_request_item pri
             WHERE pri.pricing_request_item_id = :id AND pri.pricing_request_id = :prId
               AND ti.item_id = pri.source_ticket_item_id
               AND COALESCE(ti.brand, '') = COALESCE(pri.brand, '') AND COALESCE(ti.model, '') = COALESCE(pri.model, '')
            """, new MapSqlParameterSource().addValue("brand", brand).addValue("model", model)
                .addValue("id", itemId).addValue("prId", pricingRequestId));
        return jdbc.update("""
            UPDATE sales.pricing_request_item
               SET brand = :brand, model = :model,
                   catalog_brand = CASE WHEN catalog_brand IS NULL THEN NULL ELSE :brand END,
                   catalog_model = CASE WHEN catalog_model IS NULL THEN NULL ELSE :model END
             WHERE pricing_request_item_id = :id AND pricing_request_id = :prId
            """, new MapSqlParameterSource().addValue("brand", brand).addValue("model", model)
                .addValue("id", itemId).addValue("prId", pricingRequestId));
    }

    /**
     * Fills in the {@code factory}/{@code resolved_factory_id} on ONE line that has none, so
     * Import can route it to a factory email. Returns the number of rows written — 0 means the
     * WHERE clause rejected it.
     *
     * <p>The guard is IN THE SQL on purpose, not only in the service: the {@code WHERE} requires
     * the row to belong to {@code :pricingRequestId} AND to still have no factory of any kind. That
     * makes this a gap-FILL that can never re-route a line, even under a concurrent write — which
     * matters because {@code sales.factory_quote} rows are grouped BY factory name
     * ({@code FactoryQuoteService#groupByFactory}), so silently moving a line to another factory
     * after its quote existed would strand that quote's item list. The service reads the item first
     * and raises the precise 4xx; a 0 here after those checks passed is a lost race, and its caller
     * turns it into the same 409 {@link #transition} uses.
     *
     * <p>{@code factoryId} is written to {@code resolved_factory_id} —
     * the column downstream readers ({@code FactoryQuoteService#groupByFactory} /
     * {@code factoryConfigs.findByName(factoryName)}) key their factory-email + CEO
     * landed-cost lookup on. Writing only the free-text {@code factory} column would leave
     * {@code resolved_factory_id} NULL for a typed name that does not exactly match a master row
     * and dead-end those lookups. {@code factory} is written too — with the CALLER-RESOLVED
     * canonical master name (see {@code findFactoryNameById} above), not raw user input, so every
     * reader of the free-text column keeps working.
     *
     * <p>{@code resolved_factory_name} stays deliberately NOT written. That column is the
     * price-catalog PRODUCT snapshot ({@link #snapshotCatalogSelections}) — a manually-picked
     * factory (even though it is master-row-resolved) is not a catalog product resolution; every
     * downstream reader already falls back to {@code factory} via
     * {@code firstText(resolvedFactoryName, factory)}.
     */
    public int fillItemFactory(long pricingRequestId, long pricingRequestItemId, long factoryId, String factory) {
        return jdbc.update("""
            UPDATE sales.pricing_request_item
               SET factory = :factory,
                   resolved_factory_id = :factoryId
             WHERE pricing_request_item_id = :id
               AND pricing_request_id = :pricingRequestId
               AND COALESCE(NULLIF(BTRIM(resolved_factory_name), ''), NULLIF(BTRIM(factory), '')) IS NULL
            """,
            new MapSqlParameterSource()
                .addValue("id", pricingRequestItemId)
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("factory", factory)
                .addValue("factoryId", factoryId));
    }

    /**
     * True if {@code factoryName} already has a CURRENT {@code sales.factory_quote} on this
     * pricing request whose status has moved past {@code DRAFT} — i.e. an RFQ that a human has
     * already sent (REQUESTED) or that has progressed further (a factory has answered, etc.).
     * Used by {@code PricingRequestService#setItemFactory} to
     * refuse filling a blank line onto a factory whose current quote can no longer safely absorb
     * it — routed through {@link #factoryQuotes} rather than new SQL here, same one-way-dependency
     * shape {@link #cancelOpenChildrenForDeadRequest} already uses to reach {@code
     * sales.factory_quote} (see this class's field-level Javadoc on {@link #factoryQuotes}).
     *
     * <p>Companion to {@code FactoryQuoteService#generateDrafts}'s own half of the same invariant,
     * which ADDS a newly-resolved item to the existing quote instead of stranding it — but only
     * while that quote is still DRAFT. This method is what stops a NEW line from ever being routed
     * onto a factory once that window has closed, so the two guards protect one invariant from
     * both directions: {@code setItemFactory} refuses the write that would create the
     * unrecoverable case; {@code generateDrafts} handles the case gracefully when it is still
     * safe to.
     */
    public boolean hasFactoryQuotePastDraft(long pricingRequestId, String factoryName) {
        return factoryQuotes.findCurrentByFactory(pricingRequestId, factoryName)
            .filter(quote -> !FactoryQuoteStatus.DRAFT.equals(quote.status()))
            .isPresent();
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
            sql.append(" AND (t.created_by = :createdByFilter OR t.keyed_by = :createdByFilter) ");
            params.addValue("createdByFilter", createdByFilter);
        }
        if (activeDealsOnly) {
            sql.append(" AND t.lifecycle = 'ACTIVE' ");
        }
        // A DRAFT is the owning rep's private scratchpad (see PricingRequestService's class Javadoc) — it must never
        // surface in any list for anyone else, no matter what status/assignedImportId/createdByFilter/activeDealsOnly
        // the caller passed.
        sql.append(" AND (pr.status <> 'DRAFT' OR :draftOversight = TRUE OR t.created_by = :draftOwnerId OR t.keyed_by = :draftOwnerId) ");
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
     * The DEAD-request cascade: the pricing request itself has just reached CANCELLED, so every
     * child that is still alive must die with it. Called by {@code PricingRequestService#cancel}
     * (a user cancelled the request) and {@code PricingRequestService#cancelOpenForTicket} (the
     * DEAL went terminal, so its requests are cancelled from whatever status they held).
     *
     * <p><strong>Why this is not {@link #cancelOpenStep2Children}</strong> — the two cascades look
     * alike and must not be merged. See that method's own Javadoc: the revision cascade has to
     * LEAVE the parent's priced quotes alone, because the carry-forward shortcut copies them onto
     * the child. This one has the opposite job. A single shared method would have to pick one
     * behaviour and would silently break the other.
     *
     * <p>Covers the three child tables a dead request can still own. The fourth
     * ({@code sales.pricing_decision} / {@code sales.quotation}) is handled by
     * {@link #supersedeOpenPricingDecisionAndQuotation}, which the same two callers invoke
     * immediately after — kept separate because those two go to SUPERSEDED rather than CANCELLED
     * and the quotation half is deliberately NOT shared with the revision path.
     *
     * <p>{@code sales.pricing_costing}'s predicate spans all three open statuses on purpose.
     * {@code DRAFT}/{@code CALCULATED} are the historical pair (Import built a costing
     * draft); since V141 costing belongs to the CEO and {@link PricingCostingRepository} inserts
     * rows born directly at {@code SUBMITTED}, which is therefore the ONLY status a newly created
     * costing is found in. Matching only the historical two would make the costing half a complete
     * no-op against current data, invisible precisely because the rows it is meant to catch never
     * appear in the old statuses. The two legacy statuses stay in the list for rows predating V141.
     */
    public void cancelOpenChildrenForDeadRequest(long pricingRequestId, String reason, long actorId) {
        factoryQuotes.cancelOpenForPricingRequest(pricingRequestId, reason, actorId);
        jdbc.update("""
            UPDATE sales.pricing_costing
               SET status = 'CANCELLED',
                   updated_at = now()
             WHERE pricing_request_id = :pricingRequestId
               AND status IN ('DRAFT', 'CALCULATED', 'SUBMITTED')
            """, Map.of("pricingRequestId", pricingRequestId));
    }

    /** The REVISION cascade: a customer-change revision has just superseded this request, so its not-yet-answered
     * factory quotes are tidied away. The narrow ('DRAFT','REQUESTED') predicate is load-bearing — do not widen it
     * to match #cancelOpenChildrenForDeadRequest. */
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
        // V141: stale/stale_reason are retired (dropped from sales.pricing_costing) along with Import's costing-draft
        // step — see PricingRequestStatus / V141's own header. A costing reachable from here is CANCELLED outright,
        // same as before, just without the two columns that no longer exist.
        jdbc.update("""
            UPDATE sales.pricing_costing
               SET status = 'CANCELLED',
                   updated_at = now()
             WHERE pricing_request_id = :pricingRequestId
               AND status IN ('DRAFT', 'CALCULATED')
            """, params);
    }

    /**
     * The pricing-decision/quotation cascade: {@link #cancelOpenStep2Children}
     * has no knowledge of {@code sales.pricing_decision}/{@code sales.quotation}, so without this
     * a DRAFT/APPROVED decision and any non-terminal quotation would be left silently
     * stale when the request dies — still readable via {@code PricingDecisionService.salesView}/{@code get} and
     * {@code CustomerQuotationService.get} as if they were current.
     *
     * <p><strong>Callers: {@code PricingRequestService#cancel} and {@code #cancelOpenForTicket}
     * only</strong> — the two DEAD-request paths, which pair it with
     * {@link #cancelOpenChildrenForDeadRequest}. ({@code createCustomerChangeRevision} calls the
     * decision half, {@link #supersedeOpenPricingDecision}, on its own: the reissue-through-CEO
     * chain deliberately leaves the parent's ISSUED quotation live until the REPLACEMENT is
     * issued.)
     *
     * <p><strong>{@code DRAFT} is in the quotation predicate deliberately, and it is the entry
     * that closes a real hole.</strong> A quotation may be created (as DRAFT) only while the
     * pricing request sits at APPROVED_FOR_QUOTATION, which the cancel cutoff admits — so a
     * cancelled request can own a DRAFT quotation. Leaving one behind would be worse than untidy: {@code CustomerQuotationService#issue}
     * gates on the QUOTATION's {@code doc_status}, and only moves the pricing request when it is
     * still APPROVED_FOR_QUOTATION — an {@code if}, not an assertion. A DRAFT quotation orphaned
     * on a CANCELLED request would therefore still ISSUE cleanly, silently skipping the pricing
     * request transition, and put a live offer in front of a customer whose deal was cancelled.
     * Retiring the draft here is what makes that unreachable.
     *
     * <p>Not routed through {@code PricingDecisionRepository}/{@code CustomerQuotationRepository}
     * (this class's own dependency direction stays one-way, same as {@code cancelOpenStep2Children}
     * already reaching into {@code sales.factory_quote}/{@code sales.pricing_costing} directly) —
     * the class-level "never call TicketRepository" invariant is specifically about ticket
     * tables, not every downstream aggregate.
     */
    public void supersedeOpenPricingDecisionAndQuotation(long pricingRequestId) {
        supersedeOpenPricingDecision(pricingRequestId);
        jdbc.update("""
            UPDATE sales.quotation
               SET doc_status = 'SUPERSEDED'
             WHERE pricing_request_id = :pricingRequestId
               AND doc_status IN ('DRAFT', 'ISSUED', 'READY_TO_ISSUE', 'SENT', 'REVISION_REQUESTED')
            """, Map.of("pricingRequestId", pricingRequestId));
    }

    /**
     * Fallback: the customer-change revision {@code childId} died (cancelled), so its parent's
     * still-ISSUED quotation becomes the live offer again. Compare-and-set: only a parent that is
     * SUPERSEDED by exactly this child and still owns an ISSUED quotation is revived. Returns the
     * parent id, or empty when nothing matched.
     */
    public Optional<Long> reviveParentOfCancelledRevision(long childId) {
        List<Long> ids = jdbc.queryForList("""
            UPDATE sales.pricing_request p
               SET status = 'QUOTATION_ISSUED', superseded_at = NULL,
                   superseded_by_pricing_request_id = NULL, updated_at = now()
             WHERE p.superseded_by_pricing_request_id = :childId
               AND p.status = 'SUPERSEDED'
               AND EXISTS (SELECT 1 FROM sales.quotation q
                            WHERE q.pricing_request_id = p.pricing_request_id AND q.doc_status = 'ISSUED')
            RETURNING p.pricing_request_id
            """, Map.of("childId", childId), Long.class);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        jdbc.update("""
            UPDATE sales.pricing_decision SET status = 'APPROVED', updated_at = now()
             WHERE pricing_decision_id = (SELECT pricing_decision_id FROM sales.pricing_decision
                                           WHERE pricing_request_id = :id AND status = 'SUPERSEDED'
                                           ORDER BY pricing_decision_id DESC LIMIT 1)
            """, Map.of("id", ids.get(0)));
        return Optional.of(ids.get(0));
    }

    /**
     * The pricing-decision half of {@link #supersedeOpenPricingDecisionAndQuotation}, split out
     * so a customer-change revision can supersede the internal decision WITHOUT touching the customer-facing quotation.
     *
     * <p>The two halves genuinely differ in when they should fire.
     * {@code PricingRequestService#cancel} kills the deal outright, so both the decision and the
     * quotation must go. A customer-change revision does not: the already-issued quotation stays
     * ISSUED and valid until the REPLACEMENT quotation is issued, because the customer still holds
     * a live offer and the CEO may refuse the new price. See
     * {@code CustomerQuotationRepository#supersedeSupersededChainQuotations} for the other half of
     * that flow.
     */
    public void supersedeOpenPricingDecision(long pricingRequestId) {
        jdbc.update("""
            UPDATE sales.pricing_decision
               SET status = 'SUPERSEDED',
                   updated_at = now()
             WHERE pricing_request_id = :pricingRequestId
               AND status IN ('DRAFT', 'APPROVED')
            """, Map.of("pricingRequestId", pricingRequestId));
    }

    /**
     * A PRICING_REQUEST-origin ({@code th.co.glr.hr.dealquotation} engine) DRAFT quotation orphaned by a customer-change
     * revision, mirroring {@link #supersedeOpenPricingDecisionAndQuotation}'s own "DRAFT is in the
     * predicate deliberately, and it closes a real hole" reasoning — but scoped MUCH narrower than
     * that method, and NOT the same call: {@code createCustomerChangeRevision} deliberately never
     * calls {@code supersedeOpenPricingDecisionAndQuotation} itself, because the
     * reissue-through-CEO-chain rule keeps an ALREADY-ISSUED legacy (origin IS NULL) quotation
     * live until the replacement is issued (see that method's own Javadoc and this call's own
     * comment in {@code PricingRequestService#createCustomerChangeRevision}). That rule is about
     * an issued, customer-facing document — it has no bearing on a DRAFT of this origin, so only
     * DRAFT is retired here, and there is no "stays live until the
     * replacement issues" concept to preserve for it. Without this, a DRAFT quotation on this
     * engine would be left attached to a now-SUPERSEDED parent forever — nothing else
     * retires it. {@code origin =
     * 'PRICING_REQUEST'} in the predicate is what keeps this from ever touching the legacy
     * quotation the paragraph above is protecting.
     */
    public void supersedeOpenPricingRequestOriginDraft(long pricingRequestId) {
        jdbc.update("""
            UPDATE sales.quotation
               SET doc_status = 'SUPERSEDED'
             WHERE pricing_request_id = :pricingRequestId
               AND origin = 'PRICING_REQUEST'
               AND doc_status = 'DRAFT'
            """, Map.of("pricingRequestId", pricingRequestId));
    }

    /**
     * The order-confirmation bridge's own idempotency/state check — read under the
     * same {@link #lockPricingRequest} hold as the guarded update below, exactly like the other
     * create/issue/outcome replay checks (e.g. {@code CustomerQuotationRepository
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
     * Compare-and-set: only succeeds from {@code QUOTATION_ACCEPTED} (the terminal status —
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

    /** The ticket_item ids that legally belong to this ticket — used by the service layer to validate a
     * pricing-request item's sourceTicketItemId before persisting (commit 3). */
    public List<Long> findItemIdsForTicket(long ticketId) {
        return jdbc.query(
            "SELECT item_id FROM sales.ticket_item WHERE ticket_id = :ticketId",
            Map.of("ticketId", ticketId),
            (rs, rowNum) -> rs.getLong("item_id"));
    }

    // --- Pricing Request attachments (V69) ---
    //
    // Sales-level supporting attachments on the Pricing Request itself — distinct from
    // FactoryQuoteRepository's raw supplier-quote attachments. A dedicated table (not a reuse of
    // hr.file_attachment's generic domain/owner_id shape): this
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
     * Server-internal file handle — deliberately NOT the public {@link PricingRequestAttachmentDto}
     * (which never exposes a local disk path). {@code filePath}/{@code mimeType} are unused by the
     * current caller and kept only because they cost nothing to carry alongside {@code fileName}.
     *
     * <p>Factory RFQ email is manual-only, so there is no send-time resolution to do: the sole
     * caller is {@code FactoryQuoteService.emailBody}, at DRAFT-GENERATION time, which lists these file
     * names in the generated draft text so the human copying it into their own mail client knows
     * what to physically attach — a toggle flipped after a draft already exists is reflected only
     * the next time a draft is (re)generated for that factory, not retroactively.
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
            instant(rs, "order_confirmed_at"),
            // Header terms. SUMMARY_SELECT is `pr.*`, so these columns are always present
            // in the ResultSet -- no SELECT-list change needed here, only the Java read.
            rs.getString("payment_term_mode"),
            nullableInt(rs, "credit_days"),
            nullableInt(rs, "validity_days"),
            nullableLong(rs, "printed_by_display_id"),
            nullableLong(rs, "sales_rep_display_id"),
            rs.getString("dept_code"),
            rs.getString("unit_code"),
            rs.getBoolean("omit_contact_honorific"),
            nullableLong(rs, "customer_id"),
            rs.getString("ticket_status"),
            rs.getString("ticket_payment_status"),
            nullableLong(rs, "ticket_keyed_by")
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
            rs.getString("catalog_model"),
            rs.getString("product_type_override"),
            rs.getString("product_code"),
            rs.getBigDecimal("thickness_mm"),
            rs.getBigDecimal("sqm_per_piece"),
            rs.getString("quantity_mode"),
            rs.getBigDecimal("area_sqm"),
            nullableInt(rs, "pieces_input"),
            rs.getString("wastage_mode"),
            rs.getBigDecimal("wastage_value"),
            nullableInt(rs, "pieces_per_box"),
            rs.getBigDecimal("sqm_per_box"),
            nullableInt(rs, "pieces_before_wastage"),
            nullableInt(rs, "pieces_after_wastage"),
            nullableInt(rs, "boxes"),
            rs.getBoolean("round_to_full_box"),
            rs.getString("origin_country"),
            nullableInt(rs, "lead_time_min_days"),
            nullableInt(rs, "lead_time_max_days"),
            rs.getString("origin_country_other"),
            rs.getString("stock_source"),
            rs.getObject("expected_arrival_date", LocalDate.class),
            rs.getString("requested_currency"),
            rs.getString("requested_price_unit_basis")
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    // V185 (direct-deal-form parity): pieces_input/pieces_per_box/pieces_before_wastage/
    // pieces_after_wastage/boxes/lead_time_min_days/lead_time_max_days are all nullable INTEGER/ SMALLINT columns
    // — mirrors nullableLong's own pattern for the int-width equivalent.
    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
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

    /**
     * Whether {@code employeeId} may be stored as a
     * {@code printedByDisplayId}/{@code salesRepDisplayId} on this pricing request's header terms.
     * Delegates to {@link th.co.glr.hr.commission.QuotationDisplayNameEligibility}, the SAME
     * predicate direct-deal's {@code DealQuotationRepository#isEligibleQuotationDisplayName}
     * already enforces for its own identically-named fields — so the two forms' eligible-employee
     * rule can never drift apart. See that class's Javadoc for why this repository takes no new
     * constructor dependency to reuse it.
     */
    public boolean isEligibleQuotationDisplayName(long employeeId, String salesDivisionCode) {
        return th.co.glr.hr.commission.QuotationDisplayNameEligibility.isEligible(jdbc, employeeId, salesDivisionCode);
    }
}
