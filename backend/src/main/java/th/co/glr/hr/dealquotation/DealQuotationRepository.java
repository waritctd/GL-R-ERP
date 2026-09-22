package th.co.glr.hr.dealquotation;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.catalog.CatalogRepository.CatalogSqmBasis;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationCountsDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;

/**
 * Persistence for Quotation v2 (direct deal quotation, V165) — extends the SAME {@code
 * sales.quotation}/{@code sales.quotation_item} aggregate the pricing chain
 * ({@code customerquotation/CustomerQuotationRepository}) already uses, tagged {@code origin =
 * 'DEAL_DIRECT'}. Every read here filters on that column so the two flows' rows never see each
 * other — mirrors {@code CustomerQuotationRepository}'s own {@code pricing_request_id IS NOT
 * NULL} filter exactly.
 *
 * <p>Persistence and mapping only — no permission checks, no arithmetic (see {@link
 * WastageCalculator}), no status-machine rules; see {@link DealQuotationService} for all of that.
 * The one exception is {@link CatalogRepository}: {@link #mapItemColumns} calls its {@link
 * CatalogRepository#findSqmBases} to resolve a stored tile row's catalogue width_mm/height_mm for
 * {@code DealQuotationLines#sizeLine} (owner ruling 2026-09-12) — a batched LOOKUP, not arithmetic
 * or a business rule, and the same "follow the established catalogue-basis path" this file's
 * caller ({@code DealQuotationService#resolveSqmPerPiece}) already uses for the identical field.
 */
@Repository
public class DealQuotationRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogRepository catalog;

    public DealQuotationRepository(NamedParameterJdbcTemplate jdbc, CatalogRepository catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    /** Same sequence, same format as {@code CustomerQuotationRepository.nextQuotationCode()} —
     * one quotation number space across both flows (the {@code number} column is UNIQUE). */
    public String nextQuotationCode() {
        long seq = jdbc.queryForObject("SELECT nextval('sales.quotation_code_seq')", Map.of(), Long.class);
        return "QT-" + Year.now() + "-" + String.format("%04d", seq);
    }

    /**
     * A revision child's number is {@code {base}-{revisionNo}} — INCLUDING revision 1 (owner
     * feedback 2026-09-11: "มีรันเลข -1 -2 ต่อท้ายตี้วแต่แรก" / "ใบแรกเป็น QT-2026-0014-1").
     *
     * <p>Delegates to {@link th.co.glr.hr.ticket.QuotationNumbering#baseNumber} (extracted
     * 2026-09-18 so the customerquotation/PricingRequest-chain quotation flow can share this exact
     * logic rather than duplicate it — both flows write the same {@code sales.quotation.number}
     * column under the same UNIQUE constraint). Kept here, at this signature, so every existing
     * call site in {@code DealQuotationService} needs no change. See that class's Javadoc for the
     * full reasoning (legacy bare-number handling, the base-recovery invariant, etc.) — this
     * wrapper does not repeat it.
     */
    static String baseNumber(String sourceNumber, int sourceRevisionNo) {
        return th.co.glr.hr.ticket.QuotationNumbering.baseNumber(sourceNumber, sourceRevisionNo);
    }

    /** Delegates to {@link th.co.glr.hr.ticket.QuotationNumbering#revisionNumber} — see
     * {@link #baseNumber}'s Javadoc for why this class keeps a same-signature wrapper. */
    static String revisionNumber(String baseNumber, int revisionNo) {
        return th.co.glr.hr.ticket.QuotationNumbering.revisionNumber(baseNumber, revisionNo);
    }

    /** Serializes every mutating operation this feature performs against a given deal, against
     * each other. Mirrors {@code CustomerQuotationRepository.lockPricingRequest}'s idiom, keyed on
     * the ticket id instead — this feature has no pricing-request row to lock on. */
    public void lockTicket(long ticketId) {
        jdbc.query("SELECT pg_advisory_xact_lock(:id)", Map.of("id", ticketId), (rs, rowNum) -> 0);
    }

    /** M3: whether {@code parentQuotationId} already has a child revision sitting open
     * (DRAFT/PENDING_APPROVAL) — {@link DealQuotationService#createRevision} calls this, under
     * {@link #lockTicket}, to refuse a second revision with a 409 rather than racing a second
     * caller into the {@code sales.quotation.number} UNIQUE constraint (V6). */
    public boolean hasOpenRevision(long parentQuotationId) {
        Boolean found = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM sales.quotation
                 WHERE parent_quotation_id = :parentId
                   AND origin = 'DEAL_DIRECT'
                   AND doc_status IN ('DRAFT', 'PENDING_APPROVAL')
            )
            """, Map.of("parentId", parentQuotationId), Boolean.class);
        return Boolean.TRUE.equals(found);
    }

    /** GLA-123 slice S1 — the pricing-request/decision-item link for every LINKED row on a
     * quotation, keyed by {@code quotation_item_id}. Internal only (never exposed on
     * {@code DealQuotationItemDto} — the API carries the derived {@code priceChangedFromCeo} flag
     * instead, see that field's Javadoc); {@link DealQuotationService#update} uses this to carry a
     * row's link forward across a save, since {@code ItemInput} (the client payload) has no field
     * for it — a link can only ever be ESTABLISHED by {@link DealQuotationService
     * #createFromPricingRequest}, never by an update. */
    public record ItemLink(long itemId, long pricingRequestItemId, long pricingDecisionItemId) {}

    public List<ItemLink> findItemLinks(long quotationId) {
        return jdbc.query("""
            SELECT quotation_item_id, pricing_request_item_id, pricing_decision_item_id
              FROM sales.quotation_item
             WHERE quotation_id = :id AND pricing_decision_item_id IS NOT NULL
            """, Map.of("id", quotationId), (rs, rowNum) -> new ItemLink(
                rs.getLong("quotation_item_id"), rs.getLong("pricing_request_item_id"),
                rs.getLong("pricing_decision_item_id")));
    }

    /** M4(d) fix (Opus review, 2026-09-20) — every item of THIS quotation's own {@code
     * pricing_decision_id} (frozen at create time, the exact same decision {@link
     * DealQuotationService#restoreRemovedItem} reads from) that is NOT currently linked to a live
     * row on this quotation, i.e. what {@code #restoreRemovedItem} can bring back. Gives the
     * editor enough (id + a short product label) to render a "คืนรายการ" action per dropped line
     * without a second round trip to the pricing request/decision endpoints. Empty for a
     * DEAL_DIRECT row (no {@code pricing_decision_id}) or a PRICING_REQUEST row with nothing
     * dropped. */
    public record RemovedLinkedItemDto(long pricingDecisionItemId, String brand, String model,
                                        String color, String texture, String sizeText) {}

    public List<RemovedLinkedItemDto> findRemovedLinkedItems(long quotationId) {
        return jdbc.query("""
            SELECT pdi.pricing_decision_item_id, pri.brand, pri.model, pri.color, pri.texture, pri.size
              FROM sales.quotation q
              JOIN sales.pricing_decision_item pdi ON pdi.pricing_decision_id = q.pricing_decision_id
              JOIN sales.pricing_request_item pri ON pri.pricing_request_item_id = pdi.pricing_request_item_id
             WHERE q.quotation_id = :quotationId
               AND pdi.pricing_decision_item_id NOT IN (
                   SELECT pricing_decision_item_id FROM sales.quotation_item
                    WHERE quotation_id = :quotationId AND pricing_decision_item_id IS NOT NULL
               )
             ORDER BY pdi.pricing_decision_item_id
            """, Map.of("quotationId", quotationId),
            (rs, rowNum) -> new RemovedLinkedItemDto(
                rs.getLong("pricing_decision_item_id"), rs.getString("brand"), rs.getString("model"),
                rs.getString("color"), rs.getString("texture"), rs.getString("size")));
    }

    /** NIT fix (Opus re-review, 2026-09-20) — lets {@link DealQuotationService#restoreRemovedItem}
     * match the decision item through the quotation's OWN frozen {@code pricing_decision_id},
     * rather than searching "any APPROVED decision on this pricing request" (which could match a
     * LATER decision — e.g. after a customer-change revision cycles the PR through CEO review
     * again — whose item ids happen not to collide today, but is the wrong source of truth on
     * principle: this quotation was created from ONE specific decision, and that is the only one
     * its restore should ever read from). Empty for a DEAL_DIRECT row (no pricing_decision_id). */
    public java.util.Optional<Long> findPricingDecisionId(long quotationId) {
        Long id = jdbc.queryForObject(
            "SELECT pricing_decision_id FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId), Long.class);
        return java.util.Optional.ofNullable(id);
    }

    /** GLA-123 slice S1 fix (Opus review M2, 2026-09-20) — mutual exclusivity, the mirror image
     * of {@code CustomerQuotationRepository#hasLiveQuotation}: whether THIS pricing request
     * already has a live (not CANCELLED/SUPERSEDED/EXPIRED) PRICING_REQUEST-origin quotation.
     * Used by {@code CustomerQuotationService#create} to refuse (409) starting the OLD chain when
     * the new one is already in play, and — since S2 — by {@code DealQuotationService
     * #createFromPricingRequest} itself, to refuse a SECOND live quotation of this SAME engine
     * (MAJOR-3 fix, Opus re-review 2026-09-20: closes the gap where a rep could otherwise mint a
     * second live quotation while their first sat PENDING_APPROVAL, since PR status alone does
     * not move until actual issue). */
    public boolean hasLivePricingRequestQuotation(long pricingRequestId) {
        // MINOR-4 fix (owner ruling, confirmed 2026-09-20, second re-review) — REJECTED added to
        // the non-live set, for CONSISTENCY with CustomerQuotationRepository#hasLiveQuotation's
        // own identical addition (a rejected quotation has no live offer, so it must not block the
        // OTHER engine). STILL never actually written by this origin's own #reject (R9 returns to
        // DRAFT, not a REJECTED terminal status — see that method's own comment) — kept in the
        // non-live set anyway for the same forward-looking consistency reasoning MINOR-4 gave.
        Boolean found = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM sales.quotation
                 WHERE pricing_request_id = :id AND origin = 'PRICING_REQUEST'
                   AND doc_status NOT IN ('CANCELLED', 'SUPERSEDED', 'EXPIRED', 'REJECTED')
            )
            """, Map.of("id", pricingRequestId), Boolean.class);
        return Boolean.TRUE.equals(found);
    }

    /** GLA-123 slice S1 M4(c)/(d) fix (Opus review, 2026-09-20) — moves {@code
     * items_removed_from_ceo_count} (V191) by {@code delta}: {@code +1} per CEO-linked line
     * dropped in {@link DealQuotationService#update}, {@code -1} per line restored by {@link
     * DealQuotationService#restoreRemovedItem}. {@code GREATEST(0, ...)} floors it at 0 so it can
     * never go negative even under a race between two saves, matching V191's own CHECK
     * constraint (a negative value here would otherwise 500 instead of just clamping). */
    public void incrementItemsRemovedFromCeo(long quotationId, int delta) {
        jdbc.update("""
            UPDATE sales.quotation
               SET items_removed_from_ceo_count = GREATEST(0, items_removed_from_ceo_count + :delta)
             WHERE quotation_id = :id
            """, Map.of("id", quotationId, "delta", delta));
    }

    /** MAJOR-1 fix (Opus re-review, 2026-09-20) — {@link DealQuotationService#restoreRemovedItem}
     * inserts a new item row but, unlike {@link DealQuotationService#update}, never rewrote {@code
     * total_amount} — the column {@link #mapQuotation} reads as the subtotal and from which VAT
     * and the grand total are derived. Left uncorrected, a restore leaves the document's total
     * understated by exactly the restored line's amount until the NEXT full save recomputes it —
     * silently wrong on any render/download in between. Same DRAFT-only, both-origins predicate as
     * {@link #updateHeader}, but touches ONLY {@code total_amount} — a restore has no other header
     * field to change, so reusing the full {@code updateHeader} (which would also re-snapshot
     * contact/customer from a live read) would risk an unrelated, unintended side effect. */
    public void updateSubtotal(long quotationId, BigDecimal subtotal) {
        jdbc.update("""
            UPDATE sales.quotation
               SET total_amount = :subtotal, updated_at = now()
             WHERE quotation_id = :id AND origin IN ('DEAL_DIRECT', 'PRICING_REQUEST') AND doc_status = 'DRAFT'
            """, Map.of("id", quotationId, "subtotal", subtotal));
    }

    /** GLA-123 slice S1 — idempotent-create guard for {@link
     * DealQuotationService#createFromPricingRequest}: a still-open (DRAFT) PRICING_REQUEST-origin
     * quotation for this pricing request, if one exists. Scoped to DRAFT only — once submitted
     * (S2), a second create attempt is a separate question S2 owns, not this method's. */
    public Optional<Long> findOpenDraftForPricingRequest(long pricingRequestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT quotation_id FROM sales.quotation
                 WHERE pricing_request_id = :pricingRequestId AND origin = 'PRICING_REQUEST'
                   AND doc_status = 'DRAFT'
                 ORDER BY quotation_id DESC
                 LIMIT 1
                """, Map.of("pricingRequestId", pricingRequestId), Long.class));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * MAJOR-3 fix (owner ruling via coordinator, 2026-09-20 — "the expiry escape hatch"): the
     * most recent PRICING_REQUEST-origin quotation for this pricing request, REGARDLESS of
     * status — used by {@code DealQuotationService#createFromPricingRequest} to continue the SAME
     * {@code {base}-{n}} number family when re-creating after an EXPIRED quotation, rather than
     * minting an unrelated fresh base number. There is at most one LIVE row at a time (M2/the
     * one-quotation-per-request rule) and this origin has no revision chain (D12), so "most
     * recent by id" and "the only row" coincide in every reachable case today; ORDER BY id DESC
     * is still the honest query to write in case that ever stops being true.
     */
    public Optional<Long> findLatestForPricingRequest(long pricingRequestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT quotation_id FROM sales.quotation
                 WHERE pricing_request_id = :pricingRequestId AND origin = 'PRICING_REQUEST'
                 ORDER BY quotation_id DESC
                 LIMIT 1
                """, Map.of("pricingRequestId", pricingRequestId), Long.class));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * GLA-123 slice S3 BLOCKER 2 fix (Opus review against real Postgres, 2026-09-23):
     * {@code OrderConfirmationService#reconcileTicketItems} used to write {@code
     * pricing_request_item.requested_qty} onto {@code sales.ticket_item.qty} unconditionally — the
     * PR-AUTHORED quantity, never what the customer actually ACCEPTED. S1 explicitly lets sales
     * edit a PRICING_REQUEST-origin quotation line's quantity/เผื่อ (excluded from {@code
     * requireCeoApprovalIfChanged}, so it needs no CEO re-approval either), so a PR authored at 10
     * pieces whose quotation line was edited to 25 before issue reconciled back down to 10 —
     * proven live: stock reservation, delivery and "no open quantities" all ran on the wrong
     * number.
     *
     * <p>This is the fix's read side: one row per quotation line on the pricing request's own
     * ACCEPTED PRICING_REQUEST-origin quotation (there is at most one — R8), keyed by the {@code
     * pricing_request_item_id} each TILE line is still linked to (S1 never lets a line lose that
     * link — see {@link DealQuotationDtos.DealQuotationItemDto#priceChangedFromCeo}'s own family
     * of fields, all keyed the same way), value = {@code quotation_item.qty} — the SAME column
     * {@link #itemParams} writes from {@code NewItem#quantity()}, which is {@code piecesFinal} for
     * a TILE row (the printed, wastage-and-edit-final piece count — see that field's own Javadoc).
     * {@code OrderConfirmationService} prefers this map's value over the PR item's own {@code
     * requestedQty} when a row exists for it, and falls back to the PR item's value otherwise
     * (no accepted new-engine quotation at all — the legacy engine, which has NO quantity-edit
     * capability on its own quotation items, so the PR item's value was always already correct
     * there; or a PR item added after acceptance with no matching quotation line).
     */
    public Map<Long, java.math.BigDecimal> findAcceptedItemQuantitiesByPricingRequest(long pricingRequestId) {
        List<Object[]> rows = jdbc.query("""
            SELECT qi.pricing_request_item_id, qi.qty
              FROM sales.quotation_item qi
              JOIN sales.quotation q ON q.quotation_id = qi.quotation_id
             WHERE q.pricing_request_id = :pricingRequestId
               AND q.origin = 'PRICING_REQUEST'
               AND q.doc_status = 'ACCEPTED'
               AND qi.pricing_request_item_id IS NOT NULL
            """, Map.of("pricingRequestId", pricingRequestId),
            (rs, rowNum) -> new Object[]{rs.getLong("pricing_request_item_id"), rs.getBigDecimal("qty")});
        Map<Long, java.math.BigDecimal> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.put((Long) row[0], (java.math.BigDecimal) row[1]);
        }
        return result;
    }

    /**
     * M3 (found while testing the fix above): the next unused revision number for the chain
     * {@code baseNumber} belongs to — MAX(quotation_revision_no) across every row that has EVER
     * existed under this base (found by exact match on {@code baseNumber} itself, or the {@code
     * {base}-N} suffix pattern {@link #revisionNumber} produces) plus one, rather than the naive
     * {@code source.revisionNo() + 1}.
     *
     * <p>The naive version has its own latent duplicate-key crash: {@code source.revisionNo()}
     * belongs to whatever row {@code createRevision} was CALLED ON, and that row's own
     * {@code revisionNo} never changes just because a PRIOR attempt at revising it was cancelled.
     * Cancel a freshly created revision and call {@code createRevision} on the same (still
     * APPROVED) parent again — a legitimate, expected flow, not a race — and the naive formula
     * recomputes the SAME {@code {base}-N} number the cancelled row still holds (cancelling never
     * frees the UNIQUE {@code number} slot), hitting the identical DuplicateKeyException M3 was
     * about. {@code DealQuotationService#createRevision}/{@code #createReorder} call this under
     * the SAME {@link #lockTicket} advisory lock {@link #hasOpenRevision} already relies on, so
     * those two callers are race-safe against concurrent callers too.
     *
     * <p>GLA-123 slice S2 review fix (2026-09-20) — widened from {@code origin = 'DEAL_DIRECT'}
     * to include {@code 'PRICING_REQUEST'}: {@code DealQuotationService#createFromPricingRequest}
     * reuses this SAME method (not a hand-rolled {@code latest.revisionNo() + 1}) to number a
     * fresh quotation written after the previous one EXPIRED (D9 — "the version never restarts"),
     * for the identical reason M3 gives above — a cancelled/expired row never frees its own
     * {@code number}, so the naive "one more than the latest row" formula has the same latent
     * collision risk this method was written to close. NIT (Opus re-review, 2026-09-20): that
     * caller does NOT hold {@link #lockTicket} — it serialises on {@code
     * CustomerQuotationRepository#lockPricingRequest} instead, since the escape hatch's own
     * invariant ("one live quotation per pricing request") is scoped to the pricing request, not
     * the ticket. Still race-safe (two concurrent escape-hatch calls for the SAME pricing request
     * fully serialise on that lock), just not via the ticket lock this Javadoc used to imply
     * unconditionally. The two origins can never collide on a shared {@code ticket_id} either way:
     * each mints its own base number from its own {@link #nextQuotationCode()} call at first
     * creation, so widening this predicate only ever WIDENS the MAX() scan, never merges two
     * unrelated numbering families.
     */
    public int nextRevisionNo(long ticketId, String baseNumber) {
        Integer max = jdbc.queryForObject("""
            SELECT COALESCE(MAX(quotation_revision_no), 0) FROM sales.quotation
             WHERE ticket_id = :ticketId AND origin IN ('DEAL_DIRECT', 'PRICING_REQUEST')
               AND (number = :baseNumber OR number LIKE :basePrefix)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("baseNumber", baseNumber)
                .addValue("basePrefix", baseNumber + "-%"),
            Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    /**
     * One row about to be written to {@code sales.quotation_item}, with every derived number
     * already computed by {@code DealQuotationService}.
     *
     * <p>Quotation v3 (2026-09-11) added {@code lineType} and the fields the two non-tile row
     * types need. Note what is NOT here: {@code piecesBeforeWastage}/{@code piecesAfterWastage}/
     * {@code piecesFinal}/{@code boxes} stay {@code int}/{@code Integer} because they are TILE
     * concepts — a PLAIN or ADJUSTMENT row carries 0/null in them and prints its {@code quantity}
     * instead. {@code quantity} is the ONE printed จำนวน for every row type (a TILE row sets it to
     * its own {@code piecesFinal}, so the two can never disagree).
     */
    public record NewItem(
        String locationLabel, Long catalogPriceId, String productCode,
        String brand, String model, String color, String texture, String sizeText,
        BigDecimal thicknessMm, BigDecimal sqmPerPiece,
        String quantityMode, BigDecimal areaSqm, Integer piecesInput,
        String wastageMode, BigDecimal wastageValue, Integer piecesPerBox,
        int piecesBeforeWastage, int piecesAfterWastage, int piecesFinal, Integer boxes,
        BigDecimal unitPrice, BigDecimal discountPct, BigDecimal netUnitPrice, BigDecimal lineAmount,
        BigDecimal vat, BigDecimal lineTotal,
        String originCountry, Integer leadTimeMinDays, Integer leadTimeMaxDays, String itemNotes,
        String descriptionLine,
        // ── quotation v3 ──────────────────────────────────────────────────────────────────────
        String lineType, BigDecimal quantity, String unit,
        BigDecimal specialPriceSqm, BigDecimal adjustmentPct, java.time.LocalDate adjustmentDeadline,
        // ── owner ruling 2026-09-12 ("normalize it in the database so its the same in unit. make
        // the size cm and the thickness mm") ───────────────────────────────────────────────────
        // The catalogue's OWN width_mm/height_mm for this row's catalogPriceId -- null for a
        // PLAIN/ADJUSTMENT row, or a TILE row with no catalog link/basis. Carried through from the
        // SAME CatalogSqmBasis lookup DealQuotationService#resolveSqmPerPiece already performs
        // (see DealQuotationService#buildTileItem), so DealQuotationLines#sizeLine can print the
        // face size in CENTIMETRES from unambiguous millimetres instead of guessing the unit of
        // the rep's free-text sizeText. NOT a persisted column -- sales.quotation_item has no
        // width_mm/height_mm of its own, and neither #insertDraft nor #updateItemsInPlace/
        // #insertItemsAtSeq ever reference these two fields -- they exist purely to reach
        // DealQuotationService#toItemDto's stateless preview without a second catalog round trip
        // for the same catalogPriceId. A STORED item's sizeLine is instead recomputed at READ time
        // by this class's own #mapItemColumns, which looks the same basis up fresh (batched, see
        // CatalogRepository#findSqmBases) since these two fields never reach the database.
        BigDecimal catalogWidthMm, BigDecimal catalogHeightMm,
        // V176 (owner decision 2026-09-13) — the supplier-stated sqm per box. PERSISTED
        // (sales.quotation_item.sqm_per_box); an English per-sqm row's quantity is boxes × this.
        BigDecimal sqmPerBox,
        // V182 (owner-approved "sell loose pieces", 2026-09-16) — PERSISTED
        // (sales.quotation_item.round_to_full_box). true on every PLAIN/ADJUSTMENT row (the flag
        // is meaningless there — neither has a piecesPerBox) and on every TILE row unless the rep
        // opted out; see WastageCalculator.Input's own Javadoc for what it changes.
        boolean roundToFullBox,
        // ── GLA-123 slice S1 (2026-09-19) ───────────────────────────────────────────────────────
        // Non-null exactly on a PRICING_REQUEST-origin TILE row created from an approved
        // pricing-decision item (DealQuotationService#createFromPricingRequest) — PERSISTED
        // (sales.quotation_item.pricing_request_item_id/pricing_decision_item_id, columns that
        // have existed since V74's customerquotation/ path; this is simply the first time the
        // DEAL_DIRECT-shaped engine writes them). Null on every DEAL_DIRECT row and on any
        // PRICING_REQUEST row the rep added themselves (D7 extra row, or a TILE row whose link was
        // dropped by deleting-and-re-adding it — see DealQuotationService#update's own comment on
        // why an ItemInput can never carry a link the CLIENT supplied). Read back as
        // DealQuotationItemDto#ceoNetUnitPrice != null (a plain non-null check — see that field's
        // own Javadoc; there is no separate boolean field for this) and enforced, as of the M4(a)/
        // (b) fix (Opus review, 2026-09-20), by DealQuotationService#requireLinkedLineIdentityUnchanged
        // — NOT DealQuotationService#requireLockedPriceUnchanged, a method that never shipped: the
        // owner ruling revised 2026-09-19 replaced a price LOCK with sales editing price freely,
        // flagged for CEO re-approval, before this whole field ever reached a released build.
        Long pricingRequestItemId, Long pricingDecisionItemId
    ) {
        /** The pre-GLA-123 shape (no pricing-request/decision item link) — kept so every existing
         * construction site (tests, mostly, plus every DEAL_DIRECT call site in this class and
         * DealQuotationService) compiles unchanged. Defaults both to null, correct for every
         * DEAL_DIRECT row. */
        public NewItem(
            String locationLabel, Long catalogPriceId, String productCode,
            String brand, String model, String color, String texture, String sizeText,
            BigDecimal thicknessMm, BigDecimal sqmPerPiece,
            String quantityMode, BigDecimal areaSqm, Integer piecesInput,
            String wastageMode, BigDecimal wastageValue, Integer piecesPerBox,
            int piecesBeforeWastage, int piecesAfterWastage, int piecesFinal, Integer boxes,
            BigDecimal unitPrice, BigDecimal discountPct, BigDecimal netUnitPrice, BigDecimal lineAmount,
            BigDecimal vat, BigDecimal lineTotal,
            String originCountry, Integer leadTimeMinDays, Integer leadTimeMaxDays, String itemNotes,
            String descriptionLine,
            String lineType, BigDecimal quantity, String unit,
            BigDecimal specialPriceSqm, BigDecimal adjustmentPct, java.time.LocalDate adjustmentDeadline,
            BigDecimal catalogWidthMm, BigDecimal catalogHeightMm, BigDecimal sqmPerBox, boolean roundToFullBox) {
            this(locationLabel, catalogPriceId, productCode, brand, model, color, texture, sizeText,
                thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput, wastageMode, wastageValue,
                piecesPerBox, piecesBeforeWastage, piecesAfterWastage, piecesFinal, boxes, unitPrice,
                discountPct, netUnitPrice, lineAmount, vat, lineTotal, originCountry, leadTimeMinDays,
                leadTimeMaxDays, itemNotes, descriptionLine, lineType, quantity, unit, specialPriceSqm,
                adjustmentPct, adjustmentDeadline, catalogWidthMm, catalogHeightMm, sqmPerBox, roundToFullBox,
                null, null);
        }

        /** The pre-V176 shape (no sqmPerBox/roundToFullBox) — PLAIN/ADJUSTMENT rows and existing
         * call sites, for which the flag is moot; hardcodes {@code roundToFullBox = true}. */
        public NewItem(
            String locationLabel, Long catalogPriceId, String productCode,
            String brand, String model, String color, String texture, String sizeText,
            BigDecimal thicknessMm, BigDecimal sqmPerPiece,
            String quantityMode, BigDecimal areaSqm, Integer piecesInput,
            String wastageMode, BigDecimal wastageValue, Integer piecesPerBox,
            int piecesBeforeWastage, int piecesAfterWastage, int piecesFinal, Integer boxes,
            BigDecimal unitPrice, BigDecimal discountPct, BigDecimal netUnitPrice, BigDecimal lineAmount,
            BigDecimal vat, BigDecimal lineTotal,
            String originCountry, Integer leadTimeMinDays, Integer leadTimeMaxDays, String itemNotes,
            String descriptionLine,
            String lineType, BigDecimal quantity, String unit,
            BigDecimal specialPriceSqm, BigDecimal adjustmentPct, java.time.LocalDate adjustmentDeadline,
            BigDecimal catalogWidthMm, BigDecimal catalogHeightMm) {
            this(locationLabel, catalogPriceId, productCode, brand, model, color, texture, sizeText,
                thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput, wastageMode, wastageValue,
                piecesPerBox, piecesBeforeWastage, piecesAfterWastage, piecesFinal, boxes, unitPrice,
                discountPct, netUnitPrice, lineAmount, vat, lineTotal, originCountry, leadTimeMinDays,
                leadTimeMaxDays, itemNotes, descriptionLine, lineType, quantity, unit, specialPriceSqm,
                adjustmentPct, adjustmentDeadline, catalogWidthMm, catalogHeightMm, null, true);
        }
    }

    /** The ผู้สั่งซื้อ snapshot written onto {@code sales.quotation} (V167) — see {@link
     * DealQuotationService#resolveContact}; a revision copies its parent's verbatim. */
    public record ContactSnapshot(Long contactId, String name, String phone, String email) {}

    /** The ลูกค้า snapshot on {@code sales.quotation} — written at insert AND re-written on every
     * DRAFT save (see {@link #updateHeader}), so a tax id / phone the rep corrects on the deal card
     * reaches the document instead of being frozen at the value the deal happened to carry when the
     * draft was first created. Built in ONE place, {@code DealQuotationService#customerSnapshot}. */
    public record CustomerSnapshot(String name, String address, String taxId, String phone) {}

    public record InsertDraftParams(
        long ticketId, String number, long createdById, long salesRepId,
        String customerName, String customerAddress, String customerTaxId, String customerPhone,
        ContactSnapshot contact,
        String projectName, String deptCode, String unitCode, LocalDate offerDate,
        Integer depositPercent, String remainderMode, Integer creditDays, Integer validityDays,
        // V178 — "DAYS" | "DATE" and the exact deadline for DATE mode; see WastageCalculator's
        // VALIDITY_MODE_* constants and DealQuotationService#requireValidityUntilForMode.
        String validityMode, LocalDate validityUntil,
        String customerNotes, String priceMode, String documentLanguage, String currency,
        // V179 — print-only ผู้พิมพ์/พนักงานขาย name override; see DealQuotationDtos'
        // printedByDisplayId/salesRepDisplayId Javadoc. Null on every path that does not set one.
        Long printedByDisplayId, Long salesRepDisplayId,
        // V180/V181 (items 2/4, 2026-09-16) — see DealQuotationDtos' own Javadoc on each field.
        boolean omitContactHonorific, String fullPaymentTerm,
        BigDecimal subtotal, Long parentQuotationId, int revisionNo,
        // GLA-74 part 1 (V186) — see DealQuotationDtos#derivedFromQuotationId's own Javadoc.
        // Mutually exclusive with parentQuotationId in practice (a row is either an ordinary
        // create/revision, which sets parentQuotationId, or a reorder clone, which sets THIS
        // instead) — DealQuotationService#insertCopyOf is the one place that decides which.
        Long derivedFromQuotationId,
        List<NewItem> items,
        // ── GLA-123 slice S1 (2026-09-19) ───────────────────────────────────────────────────────
        // origin: 'DEAL_DIRECT' (every call site before this feature) or 'PRICING_REQUEST'
        // (DealQuotationService#createFromPricingRequest). recipientType/recipientLabel: always
        // 'UNSPECIFIED'/null for DEAL_DIRECT (unchanged); for PRICING_REQUEST, the SOURCE pricing
        // request's own recipientType/recipientLabel (ผู้ออกแบบ/เจ้าของ/ผู้ซื้อ), carried over so
        // a later slice's issue() can reuse TicketService#advanceStageForCustomerQuotationIssue's
        // existing recipient->stage mapping unchanged. pricingRequestId/pricingDecisionId: the
        // source request/decision, null for DEAL_DIRECT.
        String origin, String recipientType, String recipientLabel,
        Long pricingRequestId, Long pricingDecisionId) {

        /** The pre-GLA-123 shape — every DEAL_DIRECT call site. Defaults origin to
         * {@code DEAL_DIRECT}, recipientType to {@code UNSPECIFIED} (this class's own historic
         * hardcoded literal), and everything else to null. */
        public InsertDraftParams(
            long ticketId, String number, long createdById, long salesRepId,
            String customerName, String customerAddress, String customerTaxId, String customerPhone,
            ContactSnapshot contact,
            String projectName, String deptCode, String unitCode, LocalDate offerDate,
            Integer depositPercent, String remainderMode, Integer creditDays, Integer validityDays,
            String validityMode, LocalDate validityUntil,
            String customerNotes, String priceMode, String documentLanguage, String currency,
            Long printedByDisplayId, Long salesRepDisplayId,
            boolean omitContactHonorific, String fullPaymentTerm,
            BigDecimal subtotal, Long parentQuotationId, int revisionNo,
            Long derivedFromQuotationId,
            List<NewItem> items) {
            this(ticketId, number, createdById, salesRepId, customerName, customerAddress, customerTaxId,
                customerPhone, contact, projectName, deptCode, unitCode, offerDate, depositPercent,
                remainderMode, creditDays, validityDays, validityMode, validityUntil, customerNotes,
                priceMode, documentLanguage, currency, printedByDisplayId, salesRepDisplayId,
                omitContactHonorific, fullPaymentTerm, subtotal, parentQuotationId, revisionNo,
                derivedFromQuotationId, items, "DEAL_DIRECT", "UNSPECIFIED", null, null, null);
        }
    }

    @Transactional
    public long insertDraft(InsertDraftParams p) {
        Integer maxVersion = jdbc.queryForObject("""
            SELECT COALESCE(MAX(quotation_version), 0) FROM sales.quotation WHERE ticket_id = :ticketId
            """, Map.of("ticketId", p.ticketId()), Integer.class);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, issued_at, total_amount, currency, quotation_version,
                 doc_status, recipient_type, recipient_label, quotation_revision_no, origin, created_by, sales_rep_id,
                 customer_name, customer_address, customer_tax_id, customer_phone,
                 contact_id, contact_name, contact_phone, contact_email, project_name,
                 dept_code, unit_code, offer_date, deposit_percent, remainder_mode, credit_days,
                 validity_days, validity_mode, validity_until, customer_notes, price_mode, document_language,
                 printed_by_display_id, sales_rep_display_id,
                 omit_contact_honorific, full_payment_term,
                 parent_quotation_id, derived_from_quotation_id, updated_at,
                 pricing_request_id, pricing_decision_id)
            VALUES
                (:ticketId, :number, :salesRepId, now(), :totalAmount, :currency, :version,
                 'DRAFT', :recipientType, :recipientLabel, :revisionNo, :origin, :createdById, :salesRepId,
                 :customerName, :customerAddress, :customerTaxId, :customerPhone,
                 :contactId, :contactName, :contactPhone, :contactEmail, :projectName,
                 :deptCode, :unitCode, :offerDate, :depositPercent, :remainderMode, :creditDays,
                 :validityDays, :validityMode, :validityUntil, :customerNotes, :priceMode, :documentLanguage,
                 :printedByDisplayId, :salesRepDisplayId,
                 :omitContactHonorific, :fullPaymentTerm,
                 :parentQuotationId, :derivedFromQuotationId, now(),
                 :pricingRequestId, :pricingDecisionId)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", p.ticketId())
                .addValue("number", p.number())
                .addValue("salesRepId", p.salesRepId())
                .addValue("totalAmount", p.subtotal())
                .addValue("version", nextVersion)
                .addValue("revisionNo", p.revisionNo())
                .addValue("createdById", p.createdById())
                .addValue("customerName", p.customerName())
                .addValue("customerAddress", p.customerAddress())
                .addValue("customerTaxId", p.customerTaxId())
                .addValue("customerPhone", p.customerPhone())
                .addValue("contactId", p.contact() != null ? p.contact().contactId() : null)
                .addValue("contactName", p.contact() != null ? p.contact().name() : null)
                .addValue("contactPhone", p.contact() != null ? p.contact().phone() : null)
                .addValue("contactEmail", p.contact() != null ? p.contact().email() : null)
                .addValue("origin", p.origin())
                .addValue("recipientType", p.recipientType())
                // GLA-123 slice S1 (coordinator follow-up) — was silently DROPPED: passed on
                // InsertDraftParams by #createFromPricingRequest but never bound/columned here,
                // so every row lost the PR's ผู้ออกแบบ/เจ้าของ/ผู้ซื้อ free-text label. Mirrors
                // CustomerQuotationRepository#insertDraft, which has always carried it.
                .addValue("recipientLabel", p.recipientLabel())
                .addValue("pricingRequestId", p.pricingRequestId())
                .addValue("pricingDecisionId", p.pricingDecisionId())
                .addValue("projectName", p.projectName())
                .addValue("deptCode", p.deptCode())
                .addValue("unitCode", p.unitCode())
                .addValue("offerDate", p.offerDate())
                .addValue("depositPercent", p.depositPercent())
                .addValue("remainderMode", p.remainderMode())
                .addValue("creditDays", p.creditDays())
                .addValue("validityDays", p.validityDays())
                .addValue("validityMode", p.validityMode())
                .addValue("validityUntil", p.validityUntil())
                .addValue("customerNotes", p.customerNotes())
                .addValue("priceMode", p.priceMode())
                // v3b: currency was a hardcoded 'THB' LITERAL in the VALUES list until V169 — it
                // is a real parameter now, defaulted from the document language by
                // DealQuotationService#resolveCurrency (TH->THB, EN->USD).
                .addValue("documentLanguage", p.documentLanguage())
                .addValue("currency", p.currency())
                .addValue("printedByDisplayId", p.printedByDisplayId())
                .addValue("salesRepDisplayId", p.salesRepDisplayId())
                .addValue("omitContactHonorific", p.omitContactHonorific())
                .addValue("fullPaymentTerm", p.fullPaymentTerm())
                .addValue("parentQuotationId", p.parentQuotationId())
                .addValue("derivedFromQuotationId", p.derivedFromQuotationId()),
            keyHolder, new String[]{"quotation_id"});
        long quotationId = keyHolder.getKey().longValue();
        insertItems(quotationId, p.items());
        return quotationId;
    }

    /**
     * The ONE place a {@link NewItem}'s columns become a {@code sales.quotation_item} parameter
     * set — shared by {@link #insertItems}, {@link #insertItemsAtSeq} and
     * {@link #updateItemsInPlace} so an insert and an update of the same row can never drift on a
     * column. Deliberately does NOT set {@code picture_id}/{@code picture_placement} — those are
     * owned exclusively by the GLA-75 picture endpoints (see {@link #setItemPicture} etc.), which
     * is what lets {@link #updateItemsInPlace} leave a row's picture alone.
     */
    private MapSqlParameterSource itemParams(long quotationId, int seq, NewItem item) {
        return new MapSqlParameterSource()
            .addValue("quotationId", quotationId)
            .addValue("seq", seq)
            .addValue("brand", item.brand())
            .addValue("model", item.model())
            .addValue("color", item.color())
            .addValue("texture", item.texture())
            .addValue("size", item.sizeText())
            // v3: the printed หน่วย is now per-row, not the hardcoded "แผ่น" it used to be —
            // a PLAIN row carries JOB/Bags/Barrels/ชุด and an ADJUSTMENT row carries none at
            // all (NULL, which the renderer prints as an EMPTY cell rather than falling back).
            .addValue("rawUnit", item.unit())
            // v3: quantity, not piecesFinal. For a TILE row the service sets quantity =
            // piecesFinal so this is byte-identical to the previous expression; for PLAIN it
            // is the rep's own จำนวน, and for ADJUSTMENT it is −1 (which is precisely what
            // makes amount = quantity × netPrice come out negative).
            .addValue("qty", item.quantity())
            .addValue("unitPrice", item.unitPrice())
            .addValue("amount", item.lineAmount())
            .addValue("salesDiscount", item.unitPrice().subtract(item.netUnitPrice()))
            .addValue("finalUnitPrice", item.netUnitPrice())
            .addValue("lineSubtotal", item.lineAmount())
            .addValue("vat", item.vat())
            .addValue("lineTotal", item.lineTotal())
            .addValue("description", item.descriptionLine())
            .addValue("locationLabel", item.locationLabel())
            .addValue("catalogPriceId", item.catalogPriceId())
            .addValue("productCode", item.productCode())
            .addValue("thicknessMm", item.thicknessMm())
            .addValue("sqmPerPiece", item.sqmPerPiece())
            .addValue("quantityMode", item.quantityMode())
            .addValue("areaSqm", item.areaSqm())
            .addValue("piecesInput", item.piecesInput())
            .addValue("wastageMode", item.wastageMode())
            .addValue("wastageValue", item.wastageValue())
            .addValue("piecesPerBox", item.piecesPerBox())
            .addValue("piecesBeforeWastage", item.piecesBeforeWastage())
            .addValue("piecesAfterWastage", item.piecesAfterWastage())
            .addValue("boxes", item.boxes())
            .addValue("discountPct", item.discountPct())
            .addValue("originCountry", item.originCountry())
            .addValue("leadTimeMinDays", item.leadTimeMinDays())
            .addValue("leadTimeMaxDays", item.leadTimeMaxDays())
            .addValue("itemNotes", item.itemNotes())
            // ── quotation v3 (V168) ──────────────────────────────────────────────────────────
            .addValue("lineType", item.lineType())
            .addValue("specialPriceSqm", item.specialPriceSqm())
            .addValue("adjustmentPct", item.adjustmentPct())
            .addValue("adjustmentDeadline", item.adjustmentDeadline())
            // V176
            .addValue("sqmPerBox", item.sqmPerBox())
            // V182
            .addValue("roundToFullBox", item.roundToFullBox())
            // GLA-123 slice S1
            .addValue("pricingRequestItemId", item.pricingRequestItemId())
            .addValue("pricingDecisionItemId", item.pricingDecisionItemId());
    }

    private static final String INSERT_ITEM_SQL = """
        INSERT INTO sales.quotation_item
            (quotation_id, seq, brand, model, color, texture, size, raw_unit, qty, unit_price, amount,
             sales_discount, final_unit_price, line_subtotal, vat, line_total, description,
             location_label, catalog_price_id, product_code, thickness_mm, sqm_per_piece,
             quantity_mode, area_sqm, pieces_input, wastage_mode, wastage_value, pieces_per_box,
             pieces_before_wastage, pieces_after_wastage, boxes, discount_pct, origin_country,
             lead_time_min_days, lead_time_max_days, item_notes,
             line_type, special_price_sqm, adjustment_pct, adjustment_deadline, sqm_per_box,
             round_to_full_box, pricing_request_item_id, pricing_decision_item_id)
        VALUES
            (:quotationId, :seq, :brand, :model, :color, :texture, :size, :rawUnit, :qty, :unitPrice, :amount,
             :salesDiscount, :finalUnitPrice, :lineSubtotal, :vat, :lineTotal, :description,
             :locationLabel, :catalogPriceId, :productCode, :thicknessMm, :sqmPerPiece,
             :quantityMode, :areaSqm, :piecesInput, :wastageMode, :wastageValue, :piecesPerBox,
             :piecesBeforeWastage, :piecesAfterWastage, :boxes, :discountPct, :originCountry,
             :leadTimeMinDays, :leadTimeMaxDays, :itemNotes,
             :lineType, :specialPriceSqm, :adjustmentPct, :adjustmentDeadline, :sqmPerBox,
             :roundToFullBox, :pricingRequestItemId, :pricingDecisionItemId)
        """;

    /** One row about to be inserted at an explicit {@code seq} — {@link #insertItemsAtSeq}, the
     * variant {@code update()} needs because its inserted rows fill the gaps left by whichever
     * seqs an in-place UPDATE claimed, so they cannot simply be numbered {@code i + 1}. */
    public record SeqItem(int seq, NewItem item) {}

    /** One row about to be UPDATED in place — {@code itemId} identifies the existing
     * {@code quotation_item_id}; {@code seq} and {@code item} are its new stored values. */
    public record ExistingItem(long itemId, int seq, NewItem item) {}

    public void insertItems(long quotationId, List<NewItem> items) {
        if (items.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            batch[i] = itemParams(quotationId, i + 1, items.get(i));
        }
        jdbc.batchUpdate(INSERT_ITEM_SQL, batch);
    }

    /** Inserts new rows at the {@code seq} each {@link SeqItem} carries — used by
     * {@code DealQuotationService#update} for every input that did not reuse an existing item id
     * (a brand-new row, or one whose id was null/foreign/stale/a duplicate within the payload). */
    public void insertItemsAtSeq(long quotationId, List<SeqItem> items) {
        if (items.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = items.stream()
            .map(si -> itemParams(quotationId, si.seq(), si.item()))
            .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT_ITEM_SQL, batch);
    }

    /** Updates each existing row IN PLACE — every column {@link #insertItems} would set, keyed by
     * {@code quotation_id AND quotation_item_id} so a foreign {@code itemId} (one belonging to
     * another quotation) can never modify anything: this WHERE clause is the enforcement, on top
     * of the caller's own membership check (the decision). Deliberately never touches
     * {@code picture_id}/{@code picture_placement} — see {@link #itemParams}'s Javadoc — which is
     * how a picture survives a draft save that reuses the item's id. */
    public void updateItemsInPlace(long quotationId, List<ExistingItem> items) {
        if (items.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = items.stream()
            .map(ei -> itemParams(quotationId, ei.seq(), ei.item()).addValue("itemId", ei.itemId()))
            .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
            UPDATE sales.quotation_item
               SET seq = :seq, brand = :brand, model = :model, color = :color, texture = :texture,
                   size = :size, raw_unit = :rawUnit, qty = :qty, unit_price = :unitPrice, amount = :amount,
                   sales_discount = :salesDiscount, final_unit_price = :finalUnitPrice,
                   line_subtotal = :lineSubtotal, vat = :vat, line_total = :lineTotal,
                   description = :description, location_label = :locationLabel,
                   catalog_price_id = :catalogPriceId, product_code = :productCode,
                   thickness_mm = :thicknessMm, sqm_per_piece = :sqmPerPiece,
                   quantity_mode = :quantityMode, area_sqm = :areaSqm, pieces_input = :piecesInput,
                   wastage_mode = :wastageMode, wastage_value = :wastageValue, pieces_per_box = :piecesPerBox,
                   pieces_before_wastage = :piecesBeforeWastage, pieces_after_wastage = :piecesAfterWastage,
                   boxes = :boxes, discount_pct = :discountPct, origin_country = :originCountry,
                   lead_time_min_days = :leadTimeMinDays, lead_time_max_days = :leadTimeMaxDays,
                   item_notes = :itemNotes,
                   line_type = :lineType, special_price_sqm = :specialPriceSqm,
                   adjustment_pct = :adjustmentPct, adjustment_deadline = :adjustmentDeadline,
                   sqm_per_box = :sqmPerBox, round_to_full_box = :roundToFullBox
             WHERE quotation_id = :quotationId AND quotation_item_id = :itemId
            """, batch);
    }

    /** This quotation's current item ids — {@code DealQuotationService#update} reads these BEFORE
     * writing anything, to decide which of the request's item ids may be reused (see the class's
     * stable-item-id contract, GLA-75-picture / #931 follow-up). */
    public List<Long> findItemIds(long quotationId) {
        return jdbc.query("SELECT quotation_item_id FROM sales.quotation_item WHERE quotation_id = :id",
            Map.of("id", quotationId), (rs, rowNum) -> rs.getLong("quotation_item_id"));
    }

    /**
     * Deletes this quotation's item rows whose id is NOT in {@code claimedIds} — an empty
     * {@code claimedIds} deletes every current row (the payload reused none of them), exactly
     * what the pre-GLA-75-picture full replace always did. Returns the picture ids those deleted
     * rows carried, so the caller can {@link #deletePicturesIfUnreferenced} them; a picture still
     * referenced by another row (a parent revision sharing it) is left alone by that call, not by
     * this one.
     */
    public List<Long> deleteUnclaimedItems(long quotationId, Set<Long> claimedIds) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("quotationId", quotationId);
        String scope;
        if (claimedIds.isEmpty()) {
            scope = "quotation_id = :quotationId";
        } else {
            scope = "quotation_id = :quotationId AND quotation_item_id NOT IN (:claimedIds)";
            params.addValue("claimedIds", claimedIds);
        }
        List<Long> pictureIds = jdbc.query(
            "SELECT picture_id FROM sales.quotation_item WHERE " + scope + " AND picture_id IS NOT NULL",
            params, (rs, rowNum) -> rs.getLong("picture_id"));
        jdbc.update("DELETE FROM sales.quotation_item WHERE " + scope, params);
        return pictureIds;
    }

    /** DRAFT-only via the WHERE clause — the enforcement, not a service-layer check the caller
     * could forget (mirrors {@code CustomerQuotationRepository.updateHeader}'s contract).
     *
     * <p>The four {@code customer_*} columns are RE-SNAPSHOTTED here, not only at insert. Owner
     * feedback F7 (2026-09-10) puts เลขที่ผู้เสียภาษี / โทร. on screen as editable fields on the
     * SELECTED customer, and promised "the values on screen at save time" — which was false while
     * this statement left the columns alone: correcting a wrong tax id and re-saving the draft
     * still printed the old one forever. Re-snapshotting on every DRAFT save is safe precisely
     * because of the {@code doc_status = 'DRAFT'} predicate below: an issued/approved/superseded
     * document is not matched by this UPDATE at all, so the freeze that makes a sent document
     * trustworthy still holds. A revision is its own DRAFT row, so it picks up the correction the
     * first time the rep saves it — which is the point of taking a revision. */
    public int updateHeader(long quotationId, ContactSnapshot contact, CustomerSnapshot customer,
                            String deptCode, String unitCode,
                            LocalDate offerDate, Integer depositPercent, String remainderMode, Integer creditDays,
                            Integer validityDays, String validityMode, LocalDate validityUntil,
                            String customerNotes, String priceMode,
                            String documentLanguage, String currency, BigDecimal subtotal,
                            // V179 — print-only override; see DealQuotationDtos' Javadoc.
                            Long printedByDisplayId, Long salesRepDisplayId,
                            // Owner feedback 2026-09-14 — project_name was write-once at INSERT
                            // only until now; genuinely editable on every DRAFT save, same as
                            // customer_notes just below it.
                            String projectName,
                            // V180/V181 (items 2/4, 2026-09-16) — see DealQuotationDtos' own Javadoc
                            // on each field. Both always sent on every DRAFT save, same "no
                            // missing-keeps-stored" discipline as printedByDisplayId/projectName
                            // above: DealQuotationService resolves the WHOLE value (including
                            // forcing fullPaymentTerm/remainderMode/creditDays null where they don't
                            // apply) before calling this method, so there is nothing left to default
                            // here.
                            boolean omitContactHonorific, String fullPaymentTerm) {
        return jdbc.update("""
            UPDATE sales.quotation
               SET contact_id = :contactId, contact_name = :contactName,
                   contact_phone = :contactPhone, contact_email = :contactEmail,
                   customer_name = :customerName, customer_address = :customerAddress,
                   customer_tax_id = :customerTaxId, customer_phone = :customerPhone,
                   project_name = :projectName,
                   dept_code = :deptCode, unit_code = :unitCode, offer_date = :offerDate,
                   deposit_percent = :depositPercent, remainder_mode = :remainderMode,
                   credit_days = :creditDays, validity_days = :validityDays,
                   validity_mode = :validityMode, validity_until = :validityUntil,
                   customer_notes = :customerNotes, price_mode = :priceMode,
                   document_language = :documentLanguage, currency = :currency,
                   printed_by_display_id = :printedByDisplayId, sales_rep_display_id = :salesRepDisplayId,
                   omit_contact_honorific = :omitContactHonorific, full_payment_term = :fullPaymentTerm,
                   total_amount = :subtotal, updated_at = now()
             -- GLA-123 slice S1: widened from origin = 'DEAL_DIRECT' — this is the ONE shared
             -- draft-header save both origins use (DealQuotationService#update). Every OTHER
             -- DEAL_DIRECT-literal WHERE clause in this class (submit/approve/reject/revision/
             -- reorder SQL) is intentionally left AS 'DEAL_DIRECT' ONLY: DealQuotationService
             -- #submit refuses a PRICING_REQUEST-origin quotation in Java before any of those are
             -- ever reached in S1, so leaving them narrow is inert now and a deliberate guard
             -- later (S2 replaces this whole status machine for the new origin, on purpose).
             WHERE quotation_id = :id AND origin IN ('DEAL_DIRECT', 'PRICING_REQUEST') AND doc_status = 'DRAFT'
            """,
            new MapSqlParameterSource()
                .addValue("id", quotationId)
                .addValue("contactId", contact.contactId())
                .addValue("contactName", contact.name())
                .addValue("contactPhone", contact.phone())
                .addValue("contactEmail", contact.email())
                .addValue("customerName", customer.name())
                .addValue("customerAddress", customer.address())
                .addValue("customerTaxId", customer.taxId())
                .addValue("customerPhone", customer.phone())
                .addValue("projectName", projectName)
                .addValue("deptCode", deptCode)
                .addValue("unitCode", unitCode)
                .addValue("offerDate", offerDate)
                .addValue("depositPercent", depositPercent)
                .addValue("remainderMode", remainderMode)
                .addValue("creditDays", creditDays)
                .addValue("validityDays", validityDays)
                .addValue("validityMode", validityMode)
                .addValue("validityUntil", validityUntil)
                .addValue("customerNotes", customerNotes)
                .addValue("priceMode", priceMode)
                .addValue("documentLanguage", documentLanguage)
                .addValue("currency", currency)
                .addValue("printedByDisplayId", printedByDisplayId)
                .addValue("salesRepDisplayId", salesRepDisplayId)
                .addValue("omitContactHonorific", omitContactHonorific)
                .addValue("fullPaymentTerm", fullPaymentTerm)
                .addValue("subtotal", subtotal));
    }

    /**
     * Quotation-editor bug fix (owner re-report 2026-09-16, "แก้หรือเพิ่ม Email ผู้สั่งซื้อภายหลัง
     * ไม่ได้"): {@link #updateHeader}'s own re-snapshot only fires on the DRAFT's OWN next save —
     * so correcting a typo'd email/phone on the CONTACT record itself never reached a draft the
     * rep was not actively re-saving, and the printed PDF (rendered fresh from the DB on every
     * download, see {@code DealQuotationService}'s render path) kept the stale value indefinitely.
     *
     * <p>Called from {@code CustomerService#updateContact} in the same request as the contact
     * write itself (that method wraps both in one {@code @Transactional} boundary) — every DRAFT
     * quotation row currently pointing at this contact id picks up its LIVE phone/email the moment
     * the contact is corrected, not on its own next save. Reads {@code customers.contact} directly
     * (a {@code FROM} join, not parameters) so this can never drift from whatever
     * {@code ContactRepository#update} just committed.
     *
     * <p>{@code refreshName} is {@code false} for a phone/email-only edit (the common case): the
     * printed ผู้สั่งซื้อ NAME is a bigger, more visible change than a phone/email typo fix, so it is
     * only re-snapshotted when the caller confirms the edit actually touched {@code first_name}/
     * {@code last_name} — never recomputed as an incidental side effect of some other field's PATCH.
     *
     * <p>Mirrors {@link #updateHeader}'s own {@code doc_status = 'DRAFT'} predicate exactly, so a
     * PENDING_APPROVAL/APPROVED/REJECTED/CANCELLED row's frozen snapshot is never touched — only a
     * quotation still open for editing moves. Same soft-reference discipline as V167's own contact
     * columns: this UPDATE only ever runs because the contact row still exists (it was just
     * written), so there is nothing to guard against here that {@code contact_id} being a
     * non-FK reference would otherwise risk.
     *
     * <p>D6 fix (Opus review 2026-09-16): {@code contact_phone}/{@code contact_email} are wrapped
     * in {@code NULLIF(TRIM(...), '')}, matching the {@code contact_name} case just below AND
     * {@code DealQuotationService#resolveContact}'s own {@code blankToNull(contact.phone())}/
     * {@code blankToNull(contact.email())} — every OTHER writer of these two columns normalises a
     * blank to {@code NULL}. {@code ContactRepository#update}'s own {@code COALESCE(:email, email)}
     * treats an explicit {@code ""} (as opposed to a literal {@code null} parameter, which means
     * "leave unchanged") as "clear this field", so {@code customers.contact.email/phone} can
     * genuinely hold {@code ''} for a cleared field — copying it here raw diverged from the {@code
     * NULL}-never-{@code ''} invariant every other writer of {@code sales.quotation.contact_phone}/
     * {@code contact_email} maintains.
     */
    public int refreshDraftContactSnapshot(long contactId, boolean refreshName) {
        return jdbc.update("""
            UPDATE sales.quotation q
               SET contact_phone = NULLIF(TRIM(c.phone), ''),
                   contact_email = NULLIF(TRIM(c.email), ''),
                   contact_name  = CASE WHEN :refreshName
                                        THEN NULLIF(TRIM(CONCAT_WS(' ', c.first_name, c.last_name)), '')
                                        ELSE q.contact_name END,
                   updated_at = now()
              FROM customers.contact c
             WHERE c.contact_id = :contactId
               AND q.contact_id = :contactId
               -- NIT fix (Opus review, 2026-09-20): widened from a bare 'DEAL_DIRECT' equality to
               -- match #findById/#updateHeader/#cancel's own widening — a live PRICING_REQUEST
               -- DRAFT's frozen ผู้สั่งซื้อ snapshot must refresh when the underlying customers.
               -- contact row changes exactly like a DEAL_DIRECT one does; there is no ruling that
               -- singles this origin out from that behaviour.
               AND q.origin IN ('DEAL_DIRECT', 'PRICING_REQUEST')
               AND q.doc_status = 'DRAFT'
            """,
            new MapSqlParameterSource()
                .addValue("contactId", contactId)
                .addValue("refreshName", refreshName));
    }

    /** Compare-and-set DRAFT -> PENDING_APPROVAL. Rowcount 0 means not open for submit. Clears any
     * stale rejection reason from a prior cycle (V165's own header comment: "cleared on the next
     * submit"). GLA-123 slice S2: widened from {@code origin = 'DEAL_DIRECT'} to include {@code
     * 'PRICING_REQUEST'} — submit's own validations (item completeness, ผู้สั่งซื้อ, payment term,
     * validity date) are origin-agnostic and apply unchanged to both. */
    public int submit(long quotationId, long actorId) {
        return jdbc.update("""
            UPDATE sales.quotation
               SET doc_status = 'PENDING_APPROVAL', submitted_at = now(), submitted_by = :actorId,
                   approval_note = NULL, updated_at = now()
             WHERE quotation_id = :id AND origin IN ('DEAL_DIRECT', 'PRICING_REQUEST') AND doc_status = 'DRAFT'
            """, new MapSqlParameterSource().addValue("id", quotationId).addValue("actorId", actorId));
    }

    /**
     * Compare-and-set PENDING_APPROVAL -> APPROVED (DEAL_DIRECT) or -> ISSUED (PRICING_REQUEST),
     * and — in the SAME statement — the V175 approver snapshot (owner ruling 2026-09-13,
     * "already-sent quotations never change afterwards"): the approver's Thai/English names and
     * signature bytes as they are at this instant. A data-modifying CTE rather than two
     * statements, so the pair is atomic even with no surrounding transaction: the INSERT only
     * sees the row the UPDATE actually won, and a lost compare-and-set inserts nothing. No
     * signature on file stores NULL image + mime with the names still frozen. Returns the
     * snapshot rowcount, which equals the UPDATE's (both LEFT JOINs keep the row).
     *
     * <p>GLA-123 slice S2 REWORK (owner ruling reversed the dual-approval design, 2026-09-20):
     * this is now the WHOLE of "approve" for BOTH origins — one approval issues a
     * PRICING_REQUEST-origin quotation exactly the way one approval already terminates a
     * DEAL_DIRECT one (that origin's own "terminal" state is spelled {@code APPROVED} rather than
     * {@code ISSUED}, a pre-existing naming difference this rework does not touch — see
     * {@code DealQuotationService#approve}'s own origin branch for where that name is chosen).
     * Reusing this ONE method (rather than a parallel PRICING_REQUEST-only compare-and-set, as an
     * earlier draft of this feature had) is what makes the printed approver, the frozen V175
     * signature, and the {@code approval_note} column all come from the SAME, already-tested path
     * DEAL_DIRECT uses — there is no second signature/snapshot mechanism to keep in sync.
     * {@code CASE WHEN origin = 'DEAL_DIRECT' THEN 'APPROVED' ELSE 'ISSUED' END} is the only
     * origin-aware fragment in the whole statement.
     */
    public int approve(long quotationId, long approverId, String note, LocalDate validityDate) {
        return jdbc.update("""
            WITH approved AS (
                UPDATE sales.quotation
                   SET doc_status = CASE WHEN origin = 'DEAL_DIRECT' THEN 'APPROVED' ELSE 'ISSUED' END,
                       approved_at = now(), approved_by = :approverId,
                       approval_decided_at = now(), approval_decided_by = :approverId,
                       approval_note = :note, validity_date = :validityDate, updated_at = now()
                 WHERE quotation_id = :id AND origin IN ('DEAL_DIRECT', 'PRICING_REQUEST')
                   AND doc_status = 'PENDING_APPROVAL'
                RETURNING quotation_id, approved_by
            )
            INSERT INTO sales.quotation_approver_snapshot
                (quotation_id, approver_id, approver_name_th, approver_name_en,
                 signature_mime_type, signature_image, snapshotted_at)
            SELECT a.quotation_id, a.approved_by,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''),
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_en, e.last_name_en)), ''),
                   es.mime_type, es.image, now()
              FROM approved a
              LEFT JOIN hr.employee e            ON e.employee_id  = a.approved_by
              LEFT JOIN hr.employee_signature es ON es.employee_id = a.approved_by
            ON CONFLICT (quotation_id) DO UPDATE
                SET approver_id = EXCLUDED.approver_id, approver_name_th = EXCLUDED.approver_name_th,
                    approver_name_en = EXCLUDED.approver_name_en,
                    signature_mime_type = EXCLUDED.signature_mime_type,
                    signature_image = EXCLUDED.signature_image, snapshotted_at = EXCLUDED.snapshotted_at
            """,
            new MapSqlParameterSource().addValue("id", quotationId).addValue("approverId", approverId)
                .addValue("note", note).addValue("validityDate", validityDate));
    }

    /** Compare-and-set PENDING_APPROVAL -> DRAFT, with the reason recorded for the rep to see.
     * GLA-123 slice S2 rework: widened to both origins for the identical reason {@link #approve}
     * was — a reject is a reject, one approver, one decision, regardless of origin. */
    public int reject(long quotationId, long approverId, String reason) {
        return jdbc.update("""
            UPDATE sales.quotation
               SET doc_status = 'DRAFT', approval_decided_at = now(), approval_decided_by = :approverId,
                   approval_note = :reason, updated_at = now()
             WHERE quotation_id = :id AND origin IN ('DEAL_DIRECT', 'PRICING_REQUEST')
               AND doc_status = 'PENDING_APPROVAL'
            """, new MapSqlParameterSource().addValue("id", quotationId).addValue("approverId", approverId)
                .addValue("reason", reason));
    }

    /**
     * GLA-123 slice S3 (R9 — customer outcome): mirrors {@code
     * CustomerQuotationRepository#recordOutcome} column-for-column (same compare-and-set FROM
     * {@code ISSUED}, same outcome/note/actor/timestamp columns — {@code sales.quotation} is one
     * shared table for both origins, V75 never scoped these columns to the legacy chain). Scoped
     * to {@code origin = 'PRICING_REQUEST'} here purely as defense in depth: the service layer
     * ({@code DealQuotationService#recordOutcome}) already refuses a {@code DEAL_DIRECT} row
     * before this is ever reached (R10 — the direct quotation never joins the pipeline), so this
     * WHERE clause should never be the thing that actually excludes a row in practice.
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
                   rejected_at = CASE WHEN :outcome = 'REJECTED' THEN now() ELSE rejected_at END,
                   updated_at = now()
             WHERE quotation_id = :id AND origin = 'PRICING_REQUEST' AND doc_status = 'ISSUED'
            """,
            new MapSqlParameterSource()
                .addValue("id", quotationId)
                .addValue("outcome", outcome)
                .addValue("outcomeNote", outcomeNote)
                .addValue("actorId", actorId)
                .addValue("outcomeClientRequestId", outcomeClientRequestId));
    }

    /** Step 5's own idempotency lookup for this origin, mirrors {@code
     * CustomerQuotationRepository#findIdByOutcomeClientRequestId} exactly — same
     * {@code (issued_by, outcome_client_request_id)} partial-unique index from V75, which is
     * table-wide (not origin-scoped), so this stays a safe replay check even though the WHERE
     * clause below additionally narrows to this origin's own rows. */
    public Optional<Long> findIdByOutcomeClientRequestId(long issuedBy, String outcomeClientRequestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT quotation_id FROM sales.quotation
                 WHERE origin = 'PRICING_REQUEST' AND issued_by = :issuedBy
                   AND outcome_client_request_id = CAST(:outcomeClientRequestId AS uuid)
                """, new MapSqlParameterSource().addValue("issuedBy", issuedBy)
                    .addValue("outcomeClientRequestId", outcomeClientRequestId),
                Long.class));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** One quotation actually flipped ISSUED -> EXPIRED — same shape as
     * {@code CustomerQuotationRepository.ExpiredQuotationRow}. */
    public record ExpiredQuotationRow(long quotationId, long pricingRequestId, long ticketId, String number) {}

    /**
     * D5 — the PRICING_REQUEST-origin counterpart of
     * {@code CustomerQuotationRepository#expireOverdueQuotations}; see that method's own comment
     * for why this predicate EXCLUDES it (that query is {@code origin IS NULL}-scoped precisely
     * to leave this origin to its own S2 flow, which this method now is). Never touches a
     * {@code DEAL_DIRECT} row (D5 — that origin never expires) because {@code origin =
     * 'PRICING_REQUEST'} excludes it categorically, not merely by never reaching {@code ISSUED}.
     */
    public List<ExpiredQuotationRow> expireOverdueQuotations() {
        // NIT fix (Opus re-review, 2026-09-20): validity_date is computed and compared in Bangkok
        // local time everywhere else this feature touches it (DealQuotationService
        // #approveAndIssuePricingRequestOrigin's own LocalDate.now(BANGKOK)) — CURRENT_DATE reads
        // the DB SESSION's timezone instead, which is
        // not guaranteed to be Bangkok. Inherited from the legacy CustomerQuotationRepository
        // query this one was modelled on, but that one is untouched here (out of this fix's
        // scope) and this one is NEW, so it is bound to an explicit Bangkok "today" parameter
        // instead of trusting the session TZ to agree.
        LocalDate bangkokToday = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok"));
        return jdbc.query("""
            UPDATE sales.quotation
               SET doc_status = 'EXPIRED', updated_at = now()
             WHERE doc_status = 'ISSUED'
               AND origin = 'PRICING_REQUEST'
               AND validity_date IS NOT NULL
               AND validity_date < :bangkokToday
            RETURNING quotation_id, pricing_request_id, ticket_id, number
            """, new MapSqlParameterSource().addValue("bangkokToday", bangkokToday),
            (rs, rowNum) -> new ExpiredQuotationRow(
                rs.getLong("quotation_id"), rs.getLong("pricing_request_id"),
                rs.getLong("ticket_id"), rs.getString("number")));
    }

    /** Compare-and-set DRAFT -> CANCELLED. */
    public int cancel(long quotationId) {
        return jdbc.update("""
            UPDATE sales.quotation SET doc_status = 'CANCELLED', updated_at = now()
             -- GLA-123 slice S1 (owner ruling): a PRICING_REQUEST-origin DRAFT may be cancelled by
             -- its owning rep exactly like a DEAL_DIRECT one — cancelling a DRAFT can never leave
             -- the source pricing request/decision inconsistent (nothing downstream exists yet;
             -- submit() is refused for this origin in S1). No PR-side bookkeeping is added here —
             -- mirrors DEAL_DIRECT's cancel exactly (ticket event only, see DealQuotationService
             -- #cancel).
             WHERE quotation_id = :id AND origin IN ('DEAL_DIRECT', 'PRICING_REQUEST') AND doc_status = 'DRAFT'
            """, Map.of("id", quotationId));
    }

    /** Compare-and-set the parent -> SUPERSEDED — only called once the CHILD revision itself
     * reaches APPROVED (see {@code DealQuotationService#approve}'s
     * {@code parentQuotationId() != null} call). Two starting statuses, matching this repository's
     * two ways a revision gets minted:
     * <ul>
     *   <li>{@code APPROVED} — an ordinary revision of an already-approved document
     *       ({@code DealQuotationService#createRevision}); the parent stays a valid, live APPROVED
     *       document until the child actually replaces it, not before.</li>
     *   <li>{@code DRAFT} — owner clarification (2026-09-15): a revision minted by resubmitting a
     *       ตีกลับ'd draft ({@code DealQuotationService#submitAsRevisionOfRejected}). That parent
     *       was never a live, sent document (it was rejected, never approved), so there is no
     *       "still valid until replaced" concern to preserve — but it still only becomes
     *       SUPERSEDED once its own child is actually approved, the SAME timing as the APPROVED
     *       case, for the same reason: a child that itself gets rejected must not have already
     *       retired the row it was trying to replace.</li>
     * </ul>
     * A parent in any OTHER status (CANCELLED, SUPERSEDED already, itself PENDING_APPROVAL — none
     * reachable while it has an open child, per {@code #hasOpenRevision}) matches neither branch
     * and this simply no-ops (0 rows), which is the safe outcome either way. */
    public int supersede(long quotationId) {
        return jdbc.update("""
            UPDATE sales.quotation SET doc_status = 'SUPERSEDED', updated_at = now()
             WHERE quotation_id = :id AND origin = 'DEAL_DIRECT' AND doc_status IN ('APPROVED', 'DRAFT')
            """, Map.of("id", quotationId));
    }

    /** One sibling {@link #supersedeOtherApprovedOnTicket} actually flipped — {@code number} is
     * read back in the SAME statement (a data-modifying CTE, same device as {@link #approve}'s
     * own approver-snapshot insert) so the caller never needs a second round trip just to name it
     * in a ticket event. */
    public record SupersededSibling(long id, String number) {}

    /**
     * Owner ruling (2026-09-19): a deal may hold only ONE APPROVED {@code DEAL_DIRECT} quotation
     * at a time — approving {@code approvedId} supersedes every OTHER {@code DEAL_DIRECT}
     * quotation on the SAME {@code ticketId} that is currently {@code APPROVED}. Called from
     * {@code DealQuotationService#approve} in the SAME transaction as the compare-and-set that put
     * {@code approvedId} into {@code APPROVED}, under the SAME ticket advisory lock
     * {@link #lockTicket} already serialises {@link #hasOpenRevision}/{@code createReorder}
     * against — see that method's own Javadoc for why two concurrent approvals on the same ticket
     * cannot both end APPROVED.
     *
     * <p>Deliberately a SEPARATE rule from {@link #supersede}'s ancestor-chain walk, not a
     * replacement for it: this sweeps every APPROVED sibling regardless of lineage (covers "two
     * independent first-issue quotations" and "revise an approved parent" alike), while the
     * ancestor walk still climbs a REJECTED/DRAFT lineage {@code parentQuotationId} chain that
     * this sweep's {@code doc_status = 'APPROVED'} predicate does not reach (those ancestors are
     * DRAFT, not APPROVED). Calling both is safe and not double-counted: a row this sweep already
     * flipped simply no-ops under {@link #supersede}'s own compare-and-set when the ancestor walk
     * reaches it too.
     *
     * <p>Existing data is NOT rewritten: this only ever runs going forward, from the next
     * {@code approve()} call — any ticket that already holds two or more APPROVED {@code
     * DEAL_DIRECT} rows from before this rule shipped stays exactly as it is until its next
     * approval.
     */
    public List<SupersededSibling> supersedeOtherApprovedOnTicket(long ticketId, long approvedId) {
        return jdbc.query("""
            UPDATE sales.quotation
               SET doc_status = 'SUPERSEDED', updated_at = now()
             WHERE ticket_id = :ticketId AND origin = 'DEAL_DIRECT' AND doc_status = 'APPROVED'
               AND quotation_id <> :approvedId
            RETURNING quotation_id, number
            """,
            new MapSqlParameterSource().addValue("ticketId", ticketId).addValue("approvedId", approvedId),
            (rs, rowNum) -> new SupersededSibling(rs.getLong("quotation_id"), rs.getString("number")));
    }

    /** The V175 approver signature snapshot. {@code mimeType}/{@code image} are both null when the
     * approver had no signature on file at approval — which is still a TAKEN snapshot. */
    public record ApproverSignatureSnapshot(String mimeType, byte[] image) {}

    /** Present iff the V175 snapshot row exists for this quotation (i.e. it was approved on or
     * after V175, or backfilled); empty means "no snapshot taken", NOT "no signature". GLA-123
     * slice S2 rework: this is the ONLY snapshot read for BOTH origins now — whoever approves a
     * PRICING_REQUEST-origin quotation freezes into this SAME row {@link #approve} already writes
     * for DEAL_DIRECT, one approver, one snapshot, no slot concept. */
    public Optional<ApproverSignatureSnapshot> findApproverSignatureSnapshot(long quotationId) {
        List<ApproverSignatureSnapshot> rows = jdbc.query("""
            SELECT signature_mime_type, signature_image
              FROM sales.quotation_approver_snapshot WHERE quotation_id = :id
            """, Map.of("id", quotationId),
            (rs, rowNum) -> new ApproverSignatureSnapshot(
                rs.getString("signature_mime_type"), rs.getBytes("signature_image")));
        return rows.stream().findFirst();
    }

    public record RepInfo(String name, String phone) {}

    /** Nothing existing on the employee-lookup surface returns phone — added here rather than
     * widening a shared DTO for one caller. */
    public Optional<RepInfo> findEmployeeNameAndPhone(long employeeId) {
        try {
            RepInfo info = jdbc.queryForObject("""
                SELECT NULLIF(TRIM(CONCAT_WS(' ', first_name_th, last_name_th)), '') AS name, phone
                  FROM hr.employee WHERE employee_id = :id
                """, Map.of("id", employeeId), (rs, rowNum) -> new RepInfo(rs.getString("name"), rs.getString("phone")));
            return Optional.ofNullable(info);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * V179 — who may appear as a ผู้พิมพ์/พนักงานขาย print-name OPTION: the union of (a) active
     * employees in the sales division ({@code DivisionAccessPolicy.SALES_DIVISION_CODE}, the SAME
     * population {@code CommissionRepository#findActiveSalesRepOptions} already queries) and (b)
     * any active employee holding the {@code hr.employee.can_create_quotation} grant (e.g.
     * ภิญญดา, who is {@code qc} role, not sales division).
     *
     * <p>Second review pass, finding N5 (2026-09-19): the predicate itself now lives in
     * {@link th.co.glr.hr.commission.QuotationDisplayNameEligibility}, shared with
     * {@code PricingRequestRepository}'s own {@code printedByDisplayId}/{@code salesRepDisplayId}
     * validation, so the two forms' eligible-employee rule can never drift apart. This method and
     * {@link #isEligibleQuotationDisplayName} keep their own names/signatures — every existing
     * caller here is unaffected — and simply delegate.
     */
    public List<th.co.glr.hr.commission.CommissionRepOptionDto> findEligibleQuotationDisplayNameOptions(
            String salesDivisionCode) {
        return th.co.glr.hr.commission.QuotationDisplayNameEligibility.options(jdbc, salesDivisionCode);
    }

    /** Whether {@code employeeId} is in the SAME eligible union {@link
     * #findEligibleQuotationDisplayNameOptions} lists — the validation
     * {@code DealQuotationService#create}/{@code #update} run before accepting a
     * {@code printedByDisplayId}/{@code salesRepDisplayId}. See that method's Javadoc for why the
     * predicate itself now lives in {@code QuotationDisplayNameEligibility}. */
    public boolean isEligibleQuotationDisplayName(long employeeId, String salesDivisionCode) {
        return th.co.glr.hr.commission.QuotationDisplayNameEligibility.isEligible(jdbc, employeeId, salesDivisionCode);
    }

    /** GLA-123 slice S1 — the ONE single-quotation-by-id read both origins share (widened from
     * {@code origin = 'DEAL_DIRECT'}): {@code DealQuotationService#requireQuotation} calls this
     * for get/update/submit/etc. on EITHER origin, so a PRICING_REQUEST row must be readable back
     * the moment {@link #insertDraft} creates it. {@link #search}/{@link #counts} (the DEAL_DIRECT
     * APPROVER QUEUE, unrelated to what a deal's document register shows) still stay {@code origin
     * = 'DEAL_DIRECT'} only, deliberately — that queue is about DEAL_DIRECT approvals
     * specifically, not "what quotations exist for this ticket". {@link #findByTicket} used to be
     * grouped with those two under the same "DIRECT-deal LIST surfaces, deliberately DEAL_DIRECT
     * only" reasoning; see that method's own Javadoc for why that turned out to be wrong for IT
     * specifically (GLA-123 item 8, S3 MAJOR 4 fix). */
    public Optional<DealQuotationDto> findById(long quotationId) {
        try {
            DealQuotationDto dto = jdbc.queryForObject(
                baseSelect() + " WHERE q.quotation_id = :id AND q.origin IN ('DEAL_DIRECT', 'PRICING_REQUEST')",
                Map.of("id", quotationId), (rs, rowNum) -> mapQuotation(rs, findItems(quotationId)));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Every quotation this DEAL carries on this engine, newest first — feeds {@code
     * DealQuotationService#listForTicket}, which is what {@code GET /tickets/{id}/deal-quotations}
     * serves, which is what the frontend's {@code DealDocumentRegister.jsx} (its
     * {@code directQuotationsQuery}) and {@code DealDirectQuotationPanel.jsx} both call to decide
     * whether a deal has ANY quotation to show at all.
     *
     * <p><b>GLA-123 slice S3 MAJOR 4 fix (Opus review against real Postgres, 2026-09-23):</b> this
     * used to read {@code origin = 'DEAL_DIRECT'} only, grouped with {@link #search}/{@link
     * #counts} under a single "DIRECT-deal LIST surfaces, deliberately DEAL_DIRECT only, so those
     * pages never start showing pipeline quotations by accident" rationale. That reasoning does
     * not hold for THIS method specifically: unlike the approver queue those two serve,
     * {@code findByTicket} is the sole source for "does this deal have a quotation at all", and
     * GLA-123 item 8 explicitly requires {@code DealDocumentRegister} to recognise the new origin.
     * With the old scope, a deal whose only quotation was PRICING_REQUEST-origin showed
     * "ยังไม่มีใบเสนอราคาสำหรับดีลนี้" even with one ISSUED — reproduced against real Postgres.
     * Widened to both origins, matching {@link #findById}'s own reasoning. {@link #search}/{@link
     * #counts} are UNCHANGED (still DEAL_DIRECT-only) — they are the DEAL_DIRECT approver queue,
     * not a "what exists for this ticket" read, and widening them was not asked for and is not
     * needed to close this gap.
     */
    public List<DealQuotationDto> findByTicket(long ticketId) {
        List<Long> ids = jdbc.query("""
            SELECT quotation_id FROM sales.quotation
             WHERE ticket_id = :id AND origin IN ('DEAL_DIRECT', 'PRICING_REQUEST')
             ORDER BY quotation_id DESC
            """, Map.of("id", ticketId), (rs, rowNum) -> rs.getLong("quotation_id"));
        return hydrate(ids);
    }

    /**
     * The "ฉบับแก้" bucket (owner feedback F5, 2026-09-10): a DRAFT that was sent back with a
     * reason ({@code approval_note}) OR a revision still in progress ({@code parent_quotation_id}).
     * ONE definition, shared by {@link #search}'s {@code needsRework} filter and {@link #counts}
     * so the tab's count can never disagree with the rows the tab lists.
     *
     * <p>Opus review (2026-09-15): this used to say {@code approval_note} is "cleared again on
     * the next submit" — true before that same day's owner clarification, false after it.
     * {@code DealQuotationService#submit}'s resubmit-after-rejection path now mints a REVISION of
     * a rejected row instead of resubmitting it, and deliberately leaves the rejected row's own
     * {@code approval_note} untouched — a permanent record of why that number was retired — so a
     * rejected row stays in this bucket until {@code approve}'s ancestor walk finally supersedes
     * it (see that method's own Javadoc for why a single-hop supersede left multi-cycle chains
     * stranded here forever).
     */
    private static final String NEEDS_REWORK_PREDICATE =
        "(q.doc_status = 'DRAFT' AND (q.approval_note IS NOT NULL OR q.parent_quotation_id IS NOT NULL))";

    /**
     * Approver-queue / own-list search. {@code statuses} null or empty means "any status".
     * {@code ownerTicketCreatedById} non-null scopes to deals created by that employee (the "own
     * deals only" rule for the {@code sales} role — see {@code DealQuotationService}); null means
     * no owner restriction (the approver queue). {@code needsRework} narrows to
     * {@link #NEEDS_REWORK_PREDICATE} — server-side, so the list page's "แก้" tab is never a
     * client-side post-filter over a truncated list; it composes with {@code statuses} (AND).
     */
    public List<DealQuotationDto> search(List<String> statuses, Long ownerTicketCreatedById, boolean needsRework) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder("q.origin = 'DEAL_DIRECT'");
        if (statuses != null && !statuses.isEmpty()) {
            where.append(" AND q.doc_status IN (:statuses)");
            params.addValue("statuses", statuses);
        }
        if (needsRework) {
            where.append(" AND ").append(NEEDS_REWORK_PREDICATE);
        }
        if (ownerTicketCreatedById != null) {
            where.append(" AND t.created_by = :ownerId");
            params.addValue("ownerId", ownerTicketCreatedById);
        }
        List<Long> ids = jdbc.query("""
            SELECT q.quotation_id FROM sales.quotation q
              JOIN sales.ticket t ON t.ticket_id = q.ticket_id
             WHERE %s
             ORDER BY q.quotation_id DESC
            """.formatted(where), params, (rs, rowNum) -> rs.getLong("quotation_id"));
        return hydrate(ids);
    }

    /**
     * Per-status counts over the SAME scope as {@link #search} with no status filter — the same
     * {@code t.created_by} owner clause (null = unrestricted), the same origin filter, the same
     * {@link #NEEDS_REWORK_PREDICATE} — in one statement with conditional aggregates. {@code all}
     * counts every row in scope regardless of status (SUPERSEDED etc. included), matching what
     * {@code search(null, owner, false)} returns.
     */
    public DealQuotationCountsDto counts(Long ownerTicketCreatedById) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String ownerClause = "";
        if (ownerTicketCreatedById != null) {
            ownerClause = " AND t.created_by = :ownerId";
            params.addValue("ownerId", ownerTicketCreatedById);
        }
        return jdbc.queryForObject("""
            SELECT COUNT(*) AS all_count,
                   COUNT(*) FILTER (WHERE q.doc_status = 'PENDING_APPROVAL') AS pending_count,
                   COUNT(*) FILTER (WHERE %s) AS rework_count,
                   COUNT(*) FILTER (WHERE q.doc_status = 'CANCELLED') AS cancelled_count,
                   COUNT(*) FILTER (WHERE q.doc_status = 'APPROVED') AS approved_count
              FROM sales.quotation q
              JOIN sales.ticket t ON t.ticket_id = q.ticket_id
             WHERE q.origin = 'DEAL_DIRECT'%s
            """.formatted(NEEDS_REWORK_PREDICATE, ownerClause), params,
            (rs, rowNum) -> new DealQuotationCountsDto(rs.getLong("all_count"), rs.getLong("pending_count"),
                rs.getLong("rework_count"), rs.getLong("cancelled_count"), rs.getLong("approved_count")));
    }

    /**
     * Batches {@link #findByTicket}/{@link #search}'s per-row hydration into a small, FIXED number
     * of queries regardless of how many ids are requested — never one per quotation. The previous
     * version called {@link #findById} per id, and that method itself issues two queries (header +
     * items), so a list of N quotations cost 2N round trips (N+1 twice over).
     *
     * <p>Was "exactly TWO queries total" before owner ruling 2026-09-12 added a batched catalogue
     * lookup for {@code DealQuotationLines#sizeLine}'s width_mm/height_mm (see {@link
     * #findItemsForQuotations}'s own comment) — that adds at most two more (a distinct-{@code
     * catalog_price_id} query, then {@link CatalogRepository#findSqmBases}'s own chunked queries,
     * negligible at realistic item counts), so FOUR total now, still independent of {@code
     * ids.size()}. Order is preserved to match the callers' own {@code ORDER BY quotation_id DESC}.
     */
    private List<DealQuotationDto> hydrate(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        // GLA-123 slice S3 MAJOR 4 fix, follow-up (Opus review against real Postgres, 2026-09-23):
        // this filter used to be `origin = 'DEAL_DIRECT'` only — a SECOND, independent scope on
        // top of the id list each caller already selected, and it silently re-dropped every
        // PRICING_REQUEST id #findByTicket's own (now-widened) query passed in, so the "widen
        // findByTicket" fix above did not actually work until this line was found and fixed too
        // (caught by this slice's own #listForTicket_includesAPricingRequestOriginQuotation_...
        // test going red against the real DB). Safe to widen for {@link #search}, the ONLY other
        // caller: that method's own `ids` query already hard-scopes `q.origin = 'DEAL_DIRECT'`
        // BEFORE calling this method (line ~1361), so its own ids list can never contain a
        // PRICING_REQUEST id in the first place — widening this filter is a genuine no-op for it.
        Map<Long, List<DealQuotationItemDto>> itemsByQuotation = findItemsForQuotations(ids);
        List<DealQuotationDto> rows = jdbc.query(
            baseSelect() + " WHERE q.quotation_id IN (:ids) AND q.origin IN ('DEAL_DIRECT', 'PRICING_REQUEST')",
            Map.of("ids", ids),
            (rs, rowNum) -> mapQuotation(rs,
                itemsByQuotation.getOrDefault(rs.getLong("quotation_id"), List.of())));
        Map<Long, DealQuotationDto> byId = new HashMap<>();
        for (DealQuotationDto dto : rows) {
            byId.put(dto.id(), dto);
        }
        List<DealQuotationDto> result = new ArrayList<>();
        for (Long id : ids) {
            DealQuotationDto dto = byId.get(id);
            if (dto != null) {
                result.add(dto);
            }
        }
        return result;
    }

    /** The distinct {@code catalog_price_id}s a set of quotation items reference, batch-resolved
     * to their catalogue basis in ONE round trip via {@link CatalogRepository#findSqmBases} —
     * fetched BEFORE the main row query below so {@link #mapItemColumns} can look each row's up by
     * id instead of querying per row (see that method's caller for why: the same N+1 shape {@link
     * CatalogRepository#findPricingKeys}'s own Javadoc already documents avoiding elsewhere). */
    private Map<Long, CatalogSqmBasis> findCatalogBasesFor(String whereClause, Map<String, ?> params) {
        List<Long> priceIds = jdbc.query(
            "SELECT DISTINCT catalog_price_id FROM sales.quotation_item WHERE " + whereClause
                + " AND catalog_price_id IS NOT NULL",
            params, (rs, rowNum) -> rs.getLong("catalog_price_id"));
        return catalog.findSqmBases(priceIds);
    }

    private List<DealQuotationItemDto> findItems(long quotationId) {
        Map<Long, CatalogSqmBasis> basisByPriceId =
            findCatalogBasesFor("quotation_id = :id", Map.of("id", quotationId));
        return jdbc.query("""
            SELECT quotation_id, quotation_item_id, seq, location_label, catalog_price_id, product_code,
                   brand, model, color, texture, size, thickness_mm, sqm_per_piece,
                   quantity_mode, area_sqm, pieces_input, wastage_mode, wastage_value, pieces_per_box,
                   qi.unit_price, qi.discount_pct, origin_country, lead_time_min_days, lead_time_max_days,
                   item_notes, pieces_before_wastage, pieces_after_wastage, qty AS pieces_final, boxes,
                   final_unit_price, amount,
                   raw_unit, description, line_type, qi.special_price_sqm, adjustment_pct, adjustment_deadline,
                   picture_placement, sqm_per_box, round_to_full_box,
                   -- GLA-123 slice S1 (owner ruling revised 2026-09-19: editable, not locked) —
                   -- the CEO's ORIGINAL decision-item values, joined by the link this row was
                   -- created with, so #mapItemColumns can detect a deviation on every read without
                   -- ever storing one. Never null for a linked row (pricing_decision_item rows are
                   -- never mutated once their decision is APPROVED — see
                   -- DealQuotationItemDto#priceChangedFromCeo's own Javadoc for that assumption).
                   qi.pricing_decision_item_id,
                   -- B1 fix (Opus review, 2026-09-20): the CEO's EFFECTIVE list price under NET is
                   -- COALESCE(manual override, auto list) -- mirrors PricingDecisionService's own
                   -- effectiveListPrice exactly (an active "ปรับราคาเอง" REPLACES the auto-formula
                   -- price, per V187's own column comment). Using bare list_unit_price here made
                   -- every overridden NET line read as "changed" the instant it was created, since
                   -- the quotation's own unit_price is the (correct) override value while
                   -- list_unit_price is the (superseded) auto formula price.
                   COALESCE(pdi.manual_selling_price_per_requested_unit, pdi.list_unit_price) AS ceo_list_unit_price,
                   pdi.discount_pct AS ceo_discount_pct,
                   pdi.special_price_sqm AS ceo_special_price_sqm, pdi.direct_net_price AS ceo_direct_net_price,
                   pdi.net_unit_price AS ceo_net_unit_price,
                   -- Owner ruling 2026-09-13: the printed item lines follow the DOCUMENT's language
                   -- (and, for the English per-sqm quantity, its price mode), resolved at read time
                   -- so every existing English quotation picks them up.
                   (SELECT q.document_language FROM sales.quotation q
                     WHERE q.quotation_id = qi.quotation_id) AS document_language,
                   (SELECT q.price_mode FROM sales.quotation q
                     WHERE q.quotation_id = qi.quotation_id) AS price_mode
              FROM sales.quotation_item qi
              LEFT JOIN sales.pricing_decision_item pdi ON pdi.pricing_decision_item_id = qi.pricing_decision_item_id
             WHERE quotation_id = :id
             ORDER BY seq
            """, Map.of("id", quotationId), (rs, rowNum) -> mapItem(rs, basisByPriceId));
    }

    /** Same row shape as {@link #findItems}, batched across every id in one query — see
     * {@link #hydrate}. */
    private Map<Long, List<DealQuotationItemDto>> findItemsForQuotations(List<Long> ids) {
        Map<Long, CatalogSqmBasis> basisByPriceId =
            findCatalogBasesFor("quotation_id IN (:ids)", Map.of("ids", ids));
        Map<Long, List<DealQuotationItemDto>> byQuotation = new LinkedHashMap<>();
        jdbc.query("""
            SELECT quotation_id, quotation_item_id, seq, location_label, catalog_price_id, product_code,
                   brand, model, color, texture, size, thickness_mm, sqm_per_piece,
                   quantity_mode, area_sqm, pieces_input, wastage_mode, wastage_value, pieces_per_box,
                   qi.unit_price, qi.discount_pct, origin_country, lead_time_min_days, lead_time_max_days,
                   item_notes, pieces_before_wastage, pieces_after_wastage, qty AS pieces_final, boxes,
                   final_unit_price, amount,
                   raw_unit, description, line_type, qi.special_price_sqm, adjustment_pct, adjustment_deadline,
                   picture_placement, sqm_per_box, round_to_full_box,
                   -- GLA-123 slice S1 — see #findItems's identical fragment for the full comment.
                   qi.pricing_decision_item_id,
                   -- B1 fix (Opus review, 2026-09-20): the CEO's EFFECTIVE list price under NET is
                   -- COALESCE(manual override, auto list) -- mirrors PricingDecisionService's own
                   -- effectiveListPrice exactly (an active "ปรับราคาเอง" REPLACES the auto-formula
                   -- price, per V187's own column comment). Using bare list_unit_price here made
                   -- every overridden NET line read as "changed" the instant it was created, since
                   -- the quotation's own unit_price is the (correct) override value while
                   -- list_unit_price is the (superseded) auto formula price.
                   COALESCE(pdi.manual_selling_price_per_requested_unit, pdi.list_unit_price) AS ceo_list_unit_price,
                   pdi.discount_pct AS ceo_discount_pct,
                   pdi.special_price_sqm AS ceo_special_price_sqm, pdi.direct_net_price AS ceo_direct_net_price,
                   pdi.net_unit_price AS ceo_net_unit_price,
                   -- Owner ruling 2026-09-13: the printed item lines follow the DOCUMENT's language
                   -- (and, for the English per-sqm quantity, its price mode), resolved at read time
                   -- so every existing English quotation picks them up.
                   (SELECT q.document_language FROM sales.quotation q
                     WHERE q.quotation_id = qi.quotation_id) AS document_language,
                   (SELECT q.price_mode FROM sales.quotation q
                     WHERE q.quotation_id = qi.quotation_id) AS price_mode
              FROM sales.quotation_item qi
              LEFT JOIN sales.pricing_decision_item pdi ON pdi.pricing_decision_item_id = qi.pricing_decision_item_id
             WHERE quotation_id IN (:ids)
             ORDER BY quotation_id, seq
            """, Map.of("ids", ids), (ResultSet rs) -> {
                // A RowCallbackHandler: Spring has ALREADY advanced to this row before calling us.
                // An inner `while (rs.next())` here used to re-advance past it and consume the
                // rest of the result set in this one call, so Spring's own outer loop found
                // nothing left and stopped -- silently dropping the very FIRST row of the combined
                // (quotation_id, seq) ordering, i.e. the seq-1 item of whichever quotation sorts
                // first (see #search_returnsEveryItemAcrossMultipleQuotations_... in
                // DealQuotationIntegrationTest). One call per row, no loop.
                long quotationId = rs.getLong("quotation_id");
                byQuotation.computeIfAbsent(quotationId, k -> new ArrayList<>()).add(mapItem(rs, basisByPriceId));
            });
        return byQuotation;
    }

    private String baseSelect() {
        return """
            SELECT q.quotation_id, q.number, q.ticket_id, q.doc_status, q.quotation_revision_no,
                   q.parent_quotation_id, q.created_by,
                   NULLIF(TRIM(CONCAT_WS(' ', cb.first_name_th, cb.last_name_th)), '') AS created_by_name,
                   -- v3b: the ENGLISH document's signature names. NULLIF(TRIM(...)) so an employee
                   -- with blank English names reads as SQL NULL here rather than an empty string,
                   -- which is what lets DealQuotationRenderAdapter fall back to the Thai name with
                   -- a plain null check instead of a blank test at every call site.
                   NULLIF(TRIM(CONCAT_WS(' ', cb.first_name_en, cb.last_name_en)), '') AS created_by_name_en,
                   q.sales_rep_id,
                   NULLIF(TRIM(CONCAT_WS(' ', rep.first_name_th, rep.last_name_th)), '') AS sales_rep_name,
                   NULLIF(TRIM(CONCAT_WS(' ', rep.first_name_en, rep.last_name_en)), '') AS sales_rep_name_en,
                   rep.phone AS sales_rep_phone,
                   q.submitted_at, q.approved_by,
                   -- V175: an approved quotation prints the approver AS THEY WERE AT APPROVAL. The
                   -- live hr.employee / hr.employee_signature values are only a defensive fallback
                   -- for a row with no snapshot (V175's backfill gives every pre-V175 approval
                   -- one); DRAFT/PENDING rows have approved_by NULL, so both are NULL.
                   CASE WHEN aps.quotation_id IS NOT NULL THEN aps.approver_name_th
                        ELSE NULLIF(TRIM(CONCAT_WS(' ', ap.first_name_th, ap.last_name_th)), '')
                   END AS approved_by_name,
                   CASE WHEN aps.quotation_id IS NOT NULL THEN aps.approver_name_en
                        ELSE NULLIF(TRIM(CONCAT_WS(' ', ap.first_name_en, ap.last_name_en)), '')
                   END AS approved_by_name_en,
                   q.approved_at, q.approval_note,
                   q.customer_name, q.customer_address, q.customer_tax_id, q.customer_phone, q.project_name,
                   q.contact_id, q.contact_name, q.contact_phone, q.contact_email,
                   q.dept_code, q.unit_code, q.offer_date, q.deposit_percent, q.remainder_mode,
                   q.credit_days, q.validity_days, q.validity_date, q.validity_mode, q.validity_until,
                   q.customer_notes, q.price_mode,
                   q.document_language,
                   -- V180/V181 (items 2/4, 2026-09-16): "ไม่เติม “คุณ”" flag and the zero-deposit
                   -- full-payment-term code — see each column's own COMMENT ON COLUMN.
                   q.omit_contact_honorific, q.full_payment_term,
                   q.total_amount, q.currency, q.issued_at AS created_at, q.updated_at,
                   CASE WHEN aps.quotation_id IS NOT NULL THEN aps.signature_image IS NOT NULL
                        ELSE EXISTS (SELECT 1 FROM hr.employee_signature es WHERE es.employee_id = q.approved_by)
                   END AS approver_has_signature,
                   -- V179: print-only ผู้พิมพ์/พนักงานขาย name override — see DealQuotationDtos'
                   -- printedByDisplayId/salesRepDisplayId Javadoc. Both LEFT JOINs are null when the
                   -- column itself is null, which is what lets #mapQuotation fall back to the real
                   -- createdByName/salesRepName with a plain null check.
                   q.printed_by_display_id,
                   NULLIF(TRIM(CONCAT_WS(' ', pbd.first_name_th, pbd.last_name_th)), '') AS printed_by_display_name,
                   NULLIF(TRIM(CONCAT_WS(' ', pbd.first_name_en, pbd.last_name_en)), '') AS printed_by_display_name_en,
                   q.sales_rep_display_id,
                   NULLIF(TRIM(CONCAT_WS(' ', srd.first_name_th, srd.last_name_th)), '') AS sales_rep_display_name,
                   NULLIF(TRIM(CONCAT_WS(' ', srd.first_name_en, srd.last_name_en)), '') AS sales_rep_display_name_en,
                   srd.phone AS sales_rep_display_phone,
                   -- GLA-74 part 1 (V186) — the reorder-clone provenance link. A plain join on the
                   -- SOURCE's live number AND status (never frozen), purely for the UI's
                   -- "สั่งเหมือนเดิมจาก..." note; see DealQuotationDtos#derivedFromQuotationNumber/
                   -- #derivedFromQuotationStatus's own Javadoc for why the status is read live too
                   -- (owner ruling 2026-09-19 — the source is no longer immune from supersession).
                   q.derived_from_quotation_id,
                   src.number AS derived_from_quotation_number,
                   src.doc_status AS derived_from_quotation_status,
                   -- GLA-123 slice S1: origin/pricing_request_id drive the editor's create-from-PR
                   -- affordance and CEO-comparison UI; ceo_price_mode is the ORIGINAL decision's
                   -- price_mode (never mutated by this quotation's own edits) so #mapQuotation can
                   -- flag a header-level deviation the same way #mapItemColumns flags a line one.
                   q.origin, q.pricing_request_id, pd.price_mode AS ceo_price_mode,
                   -- Coordinator follow-up (2026-09-20): the editor's "สร้างจากคำขอราคา {code}"
                   -- link needs the PR's human-readable code, not just its id.
                   pr.request_code AS pricing_request_code,
                   -- GLA-123 slice S1 M4(c) (V191): count of CEO-linked lines dropped since
                   -- creation — see DealQuotationDtos#itemsRemovedFromCeoCount's own Javadoc.
                   q.items_removed_from_ceo_count
              FROM sales.quotation q
              LEFT JOIN hr.employee cb  ON cb.employee_id = q.created_by
              LEFT JOIN hr.employee rep ON rep.employee_id = q.sales_rep_id
              LEFT JOIN hr.employee ap  ON ap.employee_id = q.approved_by
              -- GLA-123 slice S2 REWORK (owner reversed the dual-approval design, 2026-09-20): one
              -- approval, one snapshot row, no slot concept — this join (and the whole
              -- approved_by/approver_has_signature pair it feeds) now serves BOTH origins exactly
              -- alike, since #approve writes the SAME row for either. An earlier, never-merged
              -- draft of this feature widened this table's key to (quotation_id, slot) for a
              -- two-approver design (its own migration was deleted before ever landing, so there
              -- is nothing to find in history for it) — this join is back to the plain V175 shape.
              LEFT JOIN sales.quotation_approver_snapshot aps ON aps.quotation_id = q.quotation_id
              LEFT JOIN hr.employee pbd ON pbd.employee_id = q.printed_by_display_id
              LEFT JOIN hr.employee srd ON srd.employee_id = q.sales_rep_display_id
              LEFT JOIN sales.quotation src ON src.quotation_id = q.derived_from_quotation_id
              LEFT JOIN sales.pricing_decision pd ON pd.pricing_decision_id = q.pricing_decision_id
              LEFT JOIN sales.pricing_request pr ON pr.pricing_request_id = q.pricing_request_id
            """;
    }

    private DealQuotationDto mapQuotation(ResultSet rs, List<DealQuotationItemDto> items) throws SQLException {
        BigDecimal subtotal = rs.getBigDecimal("total_amount") != null ? rs.getBigDecimal("total_amount") : BigDecimal.ZERO;
        // v3b: NULL document_language (every pre-V169 row) reads as TH — see V169's own comment.
        String documentLanguage = rs.getString("document_language") == null
            ? WastageCalculator.DOCUMENT_LANGUAGE_TH : rs.getString("document_language");
        // ⚠️ The VAT rate is a function of the LANGUAGE, not a constant: an EN/F-SM-008 document
        // has no VAT row at all, so vatTotal is ZERO and grandTotal == subtotal there. Routed
        // through WastageCalculator#vat(subtotal, language) so the DTO, the stored per-item vat
        // column and the printed footer can never disagree about it.
        BigDecimal vatTotal = WastageCalculator.vat(subtotal, documentLanguage);
        // total_amount is the persisted subtotal (server-recomputed on every write — see
        // DealQuotationService); vat/grand are always DERIVED from it here, never stored
        // separately, so they can never drift from the subtotal they belong to.
        BigDecimal grandTotal = WastageCalculator.grandTotal(subtotal, vatTotal);
        Instant approvedAt = instant(rs, "approved_at");
        Instant createdAt = instant(rs, "created_at");
        // Owner feedback F8 (2026-09-10): the header date is the date the sales rep CREATED the
        // quotation, for EVERY status -- it used to be the approved date once approved, else
        // today. DealQuotationRenderAdapter#toRenderModel derives the printed B4 cell the same
        // way, so the list/detail UI and the printed document can never disagree. offerDate
        // (remark 1) is a separate, rep-editable date and is unaffected.
        LocalDate quotationDate = createdAt != null
            ? createdAt.atZone(java.time.ZoneId.of("Asia/Bangkok")).toLocalDate()
            : LocalDate.now(java.time.ZoneId.of("Asia/Bangkok"));
        long approvedByRaw = rs.getLong("approved_by");
        Long approvedById = rs.wasNull() ? null : approvedByRaw;
        return new DealQuotationDto(
            rs.getLong("quotation_id"),
            rs.getString("number"),
            rs.getLong("ticket_id"),
            rs.getString("doc_status"),
            rs.getInt("quotation_revision_no"),
            nullableLong(rs, "parent_quotation_id"),
            rs.getLong("created_by"),
            rs.getString("created_by_name"),
            rs.getString("created_by_name_en"),
            rs.getLong("sales_rep_id"),
            rs.getString("sales_rep_name"),
            rs.getString("sales_rep_name_en"),
            rs.getString("sales_rep_phone"),
            instant(rs, "submitted_at"),
            approvedById,
            rs.getString("approved_by_name"),
            rs.getString("approved_by_name_en"),
            approvedAt,
            rs.getString("approval_note"),
            quotationDate,
            rs.getString("customer_name"),
            rs.getString("customer_address"),
            rs.getString("customer_tax_id"),
            rs.getString("customer_phone"),
            nullableLong(rs, "contact_id"),
            rs.getString("contact_name"),
            rs.getString("contact_phone"),
            rs.getString("contact_email"),
            rs.getString("project_name"),
            rs.getString("dept_code"),
            rs.getString("unit_code"),
            rs.getObject("offer_date", LocalDate.class),
            nullableInt(rs, "deposit_percent"),
            rs.getString("remainder_mode"),
            nullableInt(rs, "credit_days"),
            nullableInt(rs, "validity_days"),
            rs.getObject("validity_date", LocalDate.class),
            // V178: NULL validity_mode (every pre-V178 row, and the whole legacy customer-quotation
            // path) reads as DAYS — see the migration's own comment.
            rs.getString("validity_mode") == null
                ? WastageCalculator.VALIDITY_MODE_DAYS : rs.getString("validity_mode"),
            rs.getObject("validity_until", LocalDate.class),
            rs.getString("customer_notes"),
            // NULL price_mode (every pre-V168 row) reads as NET — see V168's own comment.
            rs.getString("price_mode") == null
                ? WastageCalculator.PRICE_MODE_NET : rs.getString("price_mode"),
            documentLanguage,
            subtotal,
            vatTotal,
            grandTotal,
            rs.getString("currency"),
            rs.getBoolean("approver_has_signature"),
            // V179 — print-only ผู้พิมพ์/พนักงานขาย name override; both null unless the
            // corresponding column is set (see #baseSelect's LEFT JOINs).
            nullableLong(rs, "printed_by_display_id"),
            rs.getString("printed_by_display_name"),
            rs.getString("printed_by_display_name_en"),
            nullableLong(rs, "sales_rep_display_id"),
            rs.getString("sales_rep_display_name"),
            rs.getString("sales_rep_display_name_en"),
            rs.getString("sales_rep_display_phone"),
            // V180/V181 (items 2/4, 2026-09-16) — see DealQuotationDtos' own Javadoc on each field.
            rs.getBoolean("omit_contact_honorific"),
            rs.getString("full_payment_term"),
            // GLA-74 part 1 (V186) — see DealQuotationDtos#derivedFromQuotationId's own Javadoc.
            nullableLong(rs, "derived_from_quotation_id"),
            rs.getString("derived_from_quotation_number"),
            rs.getString("derived_from_quotation_status"),
            items,
            createdAt,
            instant(rs, "updated_at"),
            // GLA-123 slice S1 — stored NULL origin (every DEAL_DIRECT row, and every row from
            // before this feature) reads as DEAL_DIRECT, mirroring #priceMode/#documentLanguage's
            // own null-default convention immediately above.
            rs.getString("origin") == null ? "DEAL_DIRECT" : rs.getString("origin"),
            nullableLong(rs, "pricing_request_id"),
            ceoPriceModeChanged(rs),
            rs.getString("ceo_price_mode"),
            rs.getString("pricing_request_code"),
            rs.getInt("items_removed_from_ceo_count"),
            // M4(d) — populated by DealQuotationService#requireQuotation via #withRemovedCeoItems
            // for a single-row PRICING_REQUEST-origin lookup only; every OTHER read path (list/
            // search, both DEAL_DIRECT-only) never needs it, so it stays empty straight out of
            // this shared mapper rather than paying a second query per row in a list loop.
            List.of()
        );
    }

    /** {@code true} exactly when this is a PRICING_REQUEST-origin row whose CURRENT price_mode no
     * longer matches the CEO's ORIGINAL decision — see DealQuotationDto#priceModeChangedFromCeo's
     * own Javadoc. A DEAL_DIRECT row (ceo_price_mode always null, no pricing_decision_id) never
     * flags. */
    private boolean ceoPriceModeChanged(ResultSet rs) throws SQLException {
        String ceoPriceMode = rs.getString("ceo_price_mode");
        if (ceoPriceMode == null) {
            return false;
        }
        String currentPriceMode = rs.getString("price_mode") == null
            ? WastageCalculator.PRICE_MODE_NET : rs.getString("price_mode");
        return !ceoPriceMode.equals(currentPriceMode);
    }

    /** GLA-75: the stored row, plus its picture link (V170) — only the placement is read here;
     * the bytes are served separately by {@link #findItemPicture}. An item without a picture
     * keeps the legacy constructor's {@code hasPicture=false} shape exactly. */
    private DealQuotationItemDto mapItem(ResultSet rs, Map<Long, CatalogSqmBasis> basisByPriceId) throws SQLException {
        DealQuotationItemDto item = mapItemColumns(rs, basisByPriceId);
        String placement = rs.getString("picture_placement");
        return placement == null ? item : item.withPicture(rs.getLong("quotation_id"), placement);
    }

    /**
     * {@code true} when this row is linked to a decision item (a non-null CEO value came back
     * over the join) AND, under whichever price mode is CURRENTLY saved on the document, the
     * relevant stored field(s) no longer equal the CEO's ORIGINAL ones — see
     * {@link DealQuotationDtos.DealQuotationItemDto#priceChangedFromCeo}'s own Javadoc. A row
     * whose document price_mode itself changed (so the "relevant field" is no longer even the
     * same column the CEO priced) always reads changed, mirroring
     * {@link #ceoPriceModeChanged}'s own header-level rule — comparing across modes is
     * meaningless, and treating a mode switch as "unchanged" would hide the biggest possible
     * deviation from the CEO's decision.
     */
    private boolean priceChangedFromCeo(ResultSet rs, String currentPriceMode, BigDecimal ceoListUnitPrice,
            BigDecimal ceoDiscountPct, BigDecimal ceoSpecialPriceSqm, BigDecimal ceoDirectNetPrice)
            throws SQLException {
        if (ceoListUnitPrice == null && ceoDiscountPct == null && ceoSpecialPriceSqm == null
                && ceoDirectNetPrice == null) {
            // Unlinked row (no decision item) — every ceo_* column came back null over the LEFT
            // JOIN. Never flagged; nothing to compare against.
            return false;
        }
        return switch (currentPriceMode) {
            case WastageCalculator.PRICE_MODE_NET ->
                !moneyEquals(rs.getBigDecimal("unit_price"), ceoListUnitPrice)
                    // zeroIfNull on BOTH sides: "the CEO never typed a discount" (pdi.discount_pct
                    // NULL) and "this line carries 0%" are the SAME value, not a mismatch — every
                    // NET-mode writer in this codebase (buildItemInputFromDecisionItem here,
                    // PricingDecisionService's own approve()) already treats a null discount as
                    // zero; the comparison must use the identical convention or a never-discounted
                    // line reads as "changed" the moment it is created.
                    || !moneyEquals(zeroIfNull(rs.getBigDecimal("discount_pct")), zeroIfNull(ceoDiscountPct));
            case WastageCalculator.PRICE_MODE_SPECIAL_SQM ->
                !moneyEquals(rs.getBigDecimal("special_price_sqm"), ceoSpecialPriceSqm);
            case WastageCalculator.PRICE_MODE_DIRECT_NET ->
                // DIRECT_NET has no dedicated stored column on quotation_item (V187's own comment
                // on pricing_decision_item.direct_net_price) — the typed net lands straight in
                // final_unit_price, exactly like the CEO's direct_net_price is his own typed net.
                !moneyEquals(rs.getBigDecimal("final_unit_price"), ceoDirectNetPrice);
            default -> true;
        };
    }

    /** Null-safe, scale-insensitive equality for two money columns of potentially different
     * NUMERIC scales (e.g. {@code quotation_item.discount_pct} NUMERIC(5,2) vs
     * {@code pricing_decision_item.discount_pct}, same scale but read back through JDBC as
     * BigDecimals that {@link BigDecimal#equals} would otherwise treat as unequal on trailing
     * zeros alone — {@code compareTo} is the correct comparison for a decimal VALUE). */
    private boolean moneyEquals(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private DealQuotationItemDto mapItemColumns(ResultSet rs, Map<Long, CatalogSqmBasis> basisByPriceId)
            throws SQLException {
        // NULL document_language (every pre-V169 row) reads as TH, exactly as #mapQuotation does.
        String documentLanguage = rs.getString("document_language") == null
            ? WastageCalculator.DOCUMENT_LANGUAGE_TH : rs.getString("document_language");
        // NULL price_mode (every pre-V168 row) reads as NET, exactly as #mapQuotation does.
        String priceMode = rs.getString("price_mode") == null
            ? WastageCalculator.PRICE_MODE_NET : rs.getString("price_mode");
        // GLA-123 slice S1 (owner ruling revised 2026-09-19: editable, not locked) — see
        // DealQuotationItemDto#priceChangedFromCeo's own Javadoc. Applied via #withCeoComparison
        // below (mirroring #withSqmPerBox/#withRoundToFullBox's own device) so the two constructor
        // call sites below (tile vs. non-tile) never have to agree on yet another trailing
        // positional argument.
        BigDecimal ceoListUnitPrice = rs.getBigDecimal("ceo_list_unit_price");
        BigDecimal ceoDiscountPct = rs.getBigDecimal("ceo_discount_pct");
        BigDecimal ceoSpecialPriceSqm = rs.getBigDecimal("ceo_special_price_sqm");
        BigDecimal ceoDirectNetPrice = rs.getBigDecimal("ceo_direct_net_price");
        BigDecimal ceoNetUnitPrice = rs.getBigDecimal("ceo_net_unit_price");
        boolean priceChangedFromCeo = priceChangedFromCeo(rs, priceMode, ceoListUnitPrice, ceoDiscountPct,
            ceoSpecialPriceSqm, ceoDirectNetPrice);
        BigDecimal sqmPerBox = rs.getBigDecimal("sqm_per_box");
        String quantityMode = rs.getString("quantity_mode");
        BigDecimal areaSqm = rs.getBigDecimal("area_sqm");
        Integer piecesPerBox = nullableInt(rs, "pieces_per_box");
        // V182: NOT NULL DEFAULT TRUE (every pre-V182 row backfills to true at the ALTER TABLE
        // itself), so this is never NULL — no fallback needed, unlike the nullable columns above.
        boolean roundToFullBox = rs.getBoolean("round_to_full_box");
        int piecesBeforeWastage = rs.getInt("pieces_before_wastage");
        int piecesAfterWastage = rs.getInt("pieces_after_wastage");
        BigDecimal sqmPerPiece = rs.getBigDecimal("sqm_per_piece");
        String wastageMode = rs.getString("wastage_mode");
        BigDecimal wastageValue = rs.getBigDecimal("wastage_value");
        // v3: piecesPerSqm now comes from WastageCalculator rather than an inline reciprocal here.
        // It is a DIVISOR in the SPECIAL_SQM money math, so the printed ตร.ม./แผ่น and the price
        // computed from it must be the same number — see WastageCalculator#piecesPerSqm's Javadoc.
        BigDecimal piecesPerSqm = sqmPerPiece != null && sqmPerPiece.signum() > 0
            ? WastageCalculator.piecesPerSqm(sqmPerPiece) : null;
        String model = rs.getString("model");
        String color = rs.getString("color");
        String texture = rs.getString("texture");
        String productCode = rs.getString("product_code");
        String sizeText = rs.getString("size");
        BigDecimal thicknessMm = rs.getBigDecimal("thickness_mm");
        Long catalogPriceId = nullableLong(rs, "catalog_price_id");
        // The catalogue's own width_mm/height_mm for this row's catalogPriceId, batch-resolved by
        // the caller (see #findItems/#findItemsForQuotations) — used ONLY to print DealQuotation
        // Lines#sizeLine in centimetres below; never persisted, never used for any arithmetic.
        CatalogSqmBasis catalogBasis = catalogPriceId != null ? basisByPriceId.get(catalogPriceId) : null;
        BigDecimal catalogWidthMm = catalogBasis != null ? catalogBasis.widthMm() : null;
        BigDecimal catalogHeightMm = catalogBasis != null ? catalogBasis.heightMm() : null;

        // ── quotation v3 (V168) ──────────────────────────────────────────────────────────────
        // NULL line_type reads as TILE: every pre-V168 row, and every row written by a build that
        // predates this feature, keeps behaving exactly as it does today with no data rewrite.
        String lineType = rs.getString("line_type");
        if (lineType == null) {
            lineType = WastageCalculator.LINE_TYPE_TILE;
        }
        boolean tile = WastageCalculator.LINE_TYPE_TILE.equals(lineType);
        BigDecimal quantity = rs.getBigDecimal("pieces_final"); // the `qty` column, aliased
        // piecesFinal stays an int for TILE rows (it is a piece COUNT and always whole). For a
        // PLAIN row the quantity may legitimately be fractional and for an ADJUSTMENT it is −1, so
        // intValueExact() — which THROWS on a non-integral value — must not be reached for those.
        // The printed จำนวน for every row type is `quantity`; piecesFinal is TILE-only.
        int piecesFinal = tile && quantity != null ? quantity.intValueExact() : 0;
        String storedDescription = rs.getString("description");
        BigDecimal specialPriceSqm = rs.getBigDecimal("special_price_sqm");
        BigDecimal adjustmentPct = rs.getBigDecimal("adjustment_pct");
        LocalDate adjustmentDeadline = rs.getObject("adjustment_deadline", LocalDate.class);

        if (!tile) {
            // A non-tile row has no รุ่น/สี/ผิว/ขนาด and no wastage arithmetic: its description was
            // composed once at write time (rep-typed for PLAIN, derived for ADJUSTMENT) and stored,
            // and there is no size line or calculation line to print at all.
            return new DealQuotationItemDto(
                rs.getLong("quotation_item_id"), rs.getInt("seq"), rs.getString("location_label"),
                catalogPriceId, productCode, rs.getString("brand"), model, color,
                texture, sizeText, thicknessMm, sqmPerPiece, quantityMode, areaSqm,
                nullableInt(rs, "pieces_input"), wastageMode, wastageValue, piecesPerBox,
                rs.getBigDecimal("unit_price"), rs.getBigDecimal("discount_pct"),
                rs.getString("origin_country"), nullableInt(rs, "lead_time_min_days"),
                nullableInt(rs, "lead_time_max_days"), rs.getString("item_notes"),
                piecesPerSqm, piecesBeforeWastage, piecesAfterWastage, piecesFinal,
                nullableInt(rs, "boxes"), rs.getBigDecimal("final_unit_price"), rs.getBigDecimal("amount"),
                // An ADJUSTMENT's description was composed in Thai at write time; an English document
                // re-derives it here (DealQuotationLines#printedAdjustmentDescription). A PLAIN row's
                // text is the rep's own and is printed as typed.
                WastageCalculator.LINE_TYPE_ADJUSTMENT.equals(lineType)
                    ? DealQuotationLines.printedAdjustmentDescription(documentLanguage, storedDescription,
                        adjustmentPct, adjustmentDeadline)
                    : storedDescription,
                null, null,
                lineType, quantity, rs.getString("raw_unit"), specialPriceSqm, adjustmentPct,
                adjustmentDeadline, null,
                // Review fix F2: a FLAT adjustment's amount has no column of its own, so echo it
                // back off unit_price or a GET→PUT round-trip of the row cannot be saved.
                DealQuotationLines.flatAdjustmentAmount(lineType, adjustmentPct,
                    rs.getBigDecimal("unit_price"))).withCeoComparison(priceChangedFromCeo, ceoListUnitPrice,
                    ceoDiscountPct, ceoSpecialPriceSqm, ceoDirectNetPrice, ceoNetUnitPrice);
        }
        // English per-sqm (owner decision 2026-09-13) or the ordinary pieces print — decided in
        // DealQuotationLines#tilePrint, the SAME call DealQuotationService#toItemDto makes.
        DealQuotationLines.TilePrint print = DealQuotationLines.tilePrint(documentLanguage, priceMode, quantityMode,
            areaSqm, sqmPerPiece, piecesPerSqm, piecesBeforeWastage, wastageMode, wastageValue, piecesFinal,
            piecesPerBox, nullableInt(rs, "boxes"), sqmPerBox, quantity, rs.getString("raw_unit"), specialPriceSqm,
            roundToFullBox);
        return new DealQuotationItemDto(
            rs.getLong("quotation_item_id"),
            rs.getInt("seq"),
            rs.getString("location_label"),
            catalogPriceId,
            productCode,
            rs.getString("brand"),
            model,
            color,
            texture,
            sizeText,
            thicknessMm,
            sqmPerPiece,
            quantityMode,
            areaSqm,
            nullableInt(rs, "pieces_input"),
            wastageMode,
            wastageValue,
            piecesPerBox,
            rs.getBigDecimal("unit_price"),
            rs.getBigDecimal("discount_pct"),
            rs.getString("origin_country"),
            nullableInt(rs, "lead_time_min_days"),
            nullableInt(rs, "lead_time_max_days"),
            rs.getString("item_notes"),
            piecesPerSqm,
            piecesBeforeWastage,
            piecesAfterWastage,
            piecesFinal,
            nullableInt(rs, "boxes"),
            rs.getBigDecimal("final_unit_price"),
            rs.getBigDecimal("amount"),
            DealQuotationLines.descriptionLine(documentLanguage, model, color, texture, productCode, sizeText,
                thicknessMm),
            DealQuotationLines.sizeLine(documentLanguage, sizeText, thicknessMm, catalogWidthMm, catalogHeightMm),
            print.calculationLine(),
            lineType, print.quantity(), print.unit(),
            specialPriceSqm, adjustmentPct,
            adjustmentDeadline,
            print.subLine(),
            // Always null on this branch — it is the TILE branch, and only an ADJUSTMENT row can
            // carry a flat amount. Routed through the same helper anyway so the two branches can
            // never disagree about the rule.
            DealQuotationLines.flatAdjustmentAmount(lineType, adjustmentPct, null)
        ).withSqmPerBox(sqmPerBox).withRoundToFullBox(roundToFullBox).withCeoComparison(priceChangedFromCeo,
            ceoListUnitPrice, ceoDiscountPct, ceoSpecialPriceSqm, ceoDirectNetPrice, ceoNetUnitPrice);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // GLA-75 item pictures (V170). Bytes live once, immutable, in sales.quotation_item_picture;
    // an item references one by picture_id + picture_placement. See V170's header for why.
    // Every item-scoped statement below is keyed by BOTH quotation_id and quotation_item_id, so an
    // item id from another quotation can never be reached through this quotation's URL.
    // ─────────────────────────────────────────────────────────────────────────────────────

    public record PictureImage(String mimeType, byte[] image) {}

    /** A revision copies its parent's items verbatim and in the same order, so {@code seq} lines
     * the two sets up exactly; the child SHARES the parent's picture rows (immutable). */
    public int copyPictureLinks(long fromQuotationId, long toQuotationId) {
        return jdbc.update("""
            UPDATE sales.quotation_item child
               SET picture_id = parent.picture_id, picture_placement = parent.picture_placement
              FROM sales.quotation_item parent
             WHERE parent.quotation_id = :fromId
               AND parent.picture_id IS NOT NULL
               AND child.quotation_id = :toId
               AND child.seq = parent.seq
            """, Map.of("fromId", fromQuotationId, "toId", toQuotationId));
    }

    public long insertPicture(String mimeType, byte[] image, long uploadedBy) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.quotation_item_picture (mime_type, image, uploaded_by, uploaded_at)
            VALUES (:mimeType, :image, :uploadedBy, now())
            """,
            new MapSqlParameterSource()
                .addValue("mimeType", mimeType)
                .addValue("image", image)
                .addValue("uploadedBy", uploadedBy),
            keyHolder, new String[]{"picture_id"});
        return keyHolder.getKey().longValue();
    }

    /** The item's current picture id (null when none, or when the item is not this quotation's). */
    public Long findItemPictureId(long quotationId, long itemId) {
        List<Long> ids = jdbc.query("""
            SELECT picture_id FROM sales.quotation_item
             WHERE quotation_id = :quotationId AND quotation_item_id = :itemId AND picture_id IS NOT NULL
            """, Map.of("quotationId", quotationId, "itemId", itemId), (rs, rowNum) -> rs.getLong("picture_id"));
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** The DRAFT predicate every picture write shares — the ENFORCEMENT, in the statement itself,
     * exactly like {@link #updateHeader}'s; the service's own status check only chooses the
     * error message. */
    private static final String EDITABLE_DRAFT_ITEM = """
             WHERE quotation_id = :quotationId AND quotation_item_id = :itemId
               AND EXISTS (SELECT 1 FROM sales.quotation q
                            WHERE q.quotation_id = :quotationId
                              -- GLA-123 slice S1: widened from origin = 'DEAL_DIRECT' — pictures
                              -- are one of the editable-on-a-DRAFT fields this origin keeps (S1's
                              -- brief: "Rendering and preview of a DRAFT must work"), same
                              -- reasoning as #updateHeader's identical widening.
                              AND q.origin IN ('DEAL_DIRECT', 'PRICING_REQUEST') AND q.doc_status = 'DRAFT')
            """;

    public int setItemPicture(long quotationId, long itemId, long pictureId, String placement) {
        return jdbc.update("UPDATE sales.quotation_item SET picture_id = :pictureId, picture_placement = :placement"
                + EDITABLE_DRAFT_ITEM,
            new MapSqlParameterSource()
                .addValue("quotationId", quotationId)
                .addValue("itemId", itemId)
                .addValue("pictureId", pictureId)
                .addValue("placement", placement));
    }

    public int clearItemPicture(long quotationId, long itemId) {
        return jdbc.update("UPDATE sales.quotation_item SET picture_id = NULL, picture_placement = NULL"
                + EDITABLE_DRAFT_ITEM,
            Map.of("quotationId", quotationId, "itemId", itemId));
    }

    public int setItemPicturePlacement(long quotationId, long itemId, String placement) {
        return jdbc.update("UPDATE sales.quotation_item SET picture_placement = :placement"
                + EDITABLE_DRAFT_ITEM + " AND picture_id IS NOT NULL",
            new MapSqlParameterSource()
                .addValue("quotationId", quotationId)
                .addValue("itemId", itemId)
                .addValue("placement", placement));
    }

    /** One item's picture bytes — scoped to the quotation, so the caller's view access on THAT
     * quotation is what governs the read. */
    public Optional<PictureImage> findItemPicture(long quotationId, long itemId) {
        List<PictureImage> found = jdbc.query("""
            SELECT p.mime_type, p.image
              FROM sales.quotation_item i
              JOIN sales.quotation_item_picture p ON p.picture_id = i.picture_id
             WHERE i.quotation_id = :quotationId AND i.quotation_item_id = :itemId
            """, Map.of("quotationId", quotationId, "itemId", itemId),
            (rs, rowNum) -> new PictureImage(rs.getString("mime_type"), rs.getBytes("image")));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** item id → picture bytes, for rendering this quotation. */
    public Map<Long, PictureImage> findPictureImagesForQuotation(long quotationId) {
        Map<Long, PictureImage> images = new HashMap<>();
        jdbc.query("""
            SELECT i.quotation_item_id, p.mime_type, p.image
              FROM sales.quotation_item i
              JOIN sales.quotation_item_picture p ON p.picture_id = i.picture_id
             WHERE i.quotation_id = :id
            """, Map.of("id", quotationId), (ResultSet rs) -> {
                // A RowCallbackHandler: Spring has ALREADY advanced to this row. An inner
                // `while (rs.next())` here would silently drop the first row.
                images.put(rs.getLong("quotation_item_id"),
                    new PictureImage(rs.getString("mime_type"), rs.getBytes("image")));
            });
        return images;
    }

    /** Deletes each picture row NO item references any more (on any quotation — a revision shares
     * its parent's). A still-referenced row is left alone; the FK would refuse it anyway. */
    public void deletePicturesIfUnreferenced(java.util.Collection<Long> pictureIds) {
        if (pictureIds == null || pictureIds.isEmpty()) {
            return;
        }
        jdbc.update("""
            DELETE FROM sales.quotation_item_picture p
             WHERE p.picture_id IN (:ids)
               AND NOT EXISTS (SELECT 1 FROM sales.quotation_item i WHERE i.picture_id = p.picture_id)
            """, Map.of("ids", List.copyOf(new java.util.HashSet<>(pictureIds))));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
