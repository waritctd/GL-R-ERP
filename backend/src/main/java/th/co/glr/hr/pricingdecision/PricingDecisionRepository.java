package th.co.glr.hr.pricingdecision;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionSalesItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionSalesViewDto;

/**
 * Persistence for the Step 3 aggregate (V72): {@code sales.pricing_decision},
 * {@code sales.pricing_decision_item}. Persistence only — no permission checks, no workflow
 * validation, no margin/price arithmetic; see {@link PricingDecisionService} for all of that.
 */
@Repository
public class PricingDecisionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PricingDecisionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextDecisionCode() {
        Long seq = jdbc.queryForObject("SELECT nextval('sales.pricing_decision_code_seq')", Map.of(), Long.class);
        return "PCD-" + Year.now() + "-" + String.format("%04d", seq == null ? 0 : seq);
    }

    /** Serializes every mutating operation on a given pricing request against every other one
     * (create-review, approve, return-to-import, and — via the same key — Step 2's own
     * costing/customer-revision advisory locks) for the lifetime of the caller's transaction. */
    public void lockPricingRequest(long pricingRequestId) {
        jdbc.query("SELECT pg_advisory_xact_lock(:pricingRequestId)",
            Map.of("pricingRequestId", pricingRequestId), (rs, rowNum) -> 0);
    }

    public record CreateDecisionResult(long decisionId, boolean created) {}

    /** Caller must hold {@link #lockPricingRequest} for the current transaction before calling
     * this — mirrors {@code PricingCostingRepository.createDraft}'s own contract. */
    public CreateDecisionResult createDraft(long pricingRequestId, long pricingCostingId, BigDecimal defaultMarginPct,
                                            String currency, BigDecimal fxRateUsed, String fxSource,
                                            LocalDate fxEffectiveDate, String ceoNote, String clientRequestId,
                                            long actorId) {
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            Optional<PricingDecisionDto> replay = findByClientRequestId(actorId, clientRequestId);
            if (replay.isPresent()) {
                return new CreateDecisionResult(replay.get().id(), false);
            }
        }
        Optional<PricingDecisionDto> open = findOpenDraft(pricingRequestId);
        if (open.isPresent()) {
            return new CreateDecisionResult(open.get().id(), false);
        }
        Integer nextVersion = jdbc.queryForObject("""
            SELECT COALESCE(MAX(decision_version_no), 0) + 1
              FROM sales.pricing_decision
             WHERE pricing_request_id = :pricingRequestId
            """, Map.of("pricingRequestId", pricingRequestId), Integer.class);
        Long id = jdbc.queryForObject("""
            INSERT INTO sales.pricing_decision
                (decision_code, pricing_request_id, pricing_costing_id, decision_version_no, status,
                 default_margin_pct, currency, fx_rate_used, fx_source, fx_effective_date, ceo_note,
                 client_request_id, created_by)
            VALUES
                (:code, :pricingRequestId, :pricingCostingId, :versionNo, 'DRAFT',
                 :defaultMarginPct, :currency, :fxRateUsed, :fxSource, :fxEffectiveDate, :ceoNote,
                 CAST(:clientRequestId AS uuid), :createdBy)
            RETURNING pricing_decision_id
            """,
            new MapSqlParameterSource()
                .addValue("code", nextDecisionCode())
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("pricingCostingId", pricingCostingId)
                .addValue("versionNo", nextVersion == null ? 1 : nextVersion)
                .addValue("defaultMarginPct", defaultMarginPct)
                .addValue("currency", currency)
                .addValue("fxRateUsed", fxRateUsed)
                .addValue("fxSource", fxSource)
                .addValue("fxEffectiveDate", fxEffectiveDate)
                .addValue("ceoNote", ceoNote)
                .addValue("clientRequestId", clientRequestId)
                .addValue("createdBy", actorId),
            Long.class);
        return new CreateDecisionResult(id == null ? 0L : id, true);
    }

    public record WriteItem(
        long pricingRequestItemId, long pricingCostingItemId, String requestedUnitBasis,
        BigDecimal requestedQuantity, BigDecimal normalizedQuantityPieces,
        BigDecimal frozenLandedCostPerPieceThb, BigDecimal frozenLandedCostPerRequestedUnitThb,
        String currency, BigDecimal proposedMarginPct, BigDecimal proposedSellingPrice,
        // Phase 2 (owner ruling A, 2026-09-19, V187): the auto-calculated formula price is a REAL
        // stored value from the moment the item is created, not a UI placeholder — it becomes
        // list_unit_price's starting value (the NET mode's ราคาตั้ง) so a CEO who picks NET and
        // never types anything still has a real, storable price to approve. Same figure as
        // proposedSellingPrice (2dp, matching this column's scale) whenever a formula price could
        // be computed; null when it could not (e.g. uncostable line) — same as proposedSellingPrice.
        BigDecimal listUnitPrice) {}

    public void insertItems(long decisionId, List<WriteItem> items) {
        if (items.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            WriteItem item = items.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("decisionId", decisionId)
                .addValue("pricingRequestItemId", item.pricingRequestItemId())
                .addValue("pricingCostingItemId", item.pricingCostingItemId())
                .addValue("requestedUnitBasis", item.requestedUnitBasis())
                .addValue("requestedQuantity", item.requestedQuantity())
                .addValue("normalizedQuantityPieces", item.normalizedQuantityPieces())
                .addValue("frozenLandedCostPerPieceThb", item.frozenLandedCostPerPieceThb())
                .addValue("frozenLandedCostPerRequestedUnitThb", item.frozenLandedCostPerRequestedUnitThb())
                .addValue("currency", item.currency())
                .addValue("proposedMarginPct", item.proposedMarginPct())
                .addValue("proposedSellingPrice", item.proposedSellingPrice())
                .addValue("listUnitPrice", item.listUnitPrice());
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.pricing_decision_item
                (pricing_decision_id, pricing_request_item_id, pricing_costing_item_id, requested_unit_basis,
                 requested_quantity, normalized_quantity_pieces, frozen_landed_cost_per_piece_thb,
                 frozen_landed_cost_per_requested_unit_thb, currency, proposed_margin_pct,
                 proposed_selling_price_per_requested_unit, list_unit_price)
            VALUES
                (:decisionId, :pricingRequestItemId, :pricingCostingItemId, :requestedUnitBasis,
                 :requestedQuantity, :normalizedQuantityPieces, :frozenLandedCostPerPieceThb,
                 :frozenLandedCostPerRequestedUnitThb, :currency, :proposedMarginPct,
                 :proposedSellingPrice, :listUnitPrice)
            """, batch);
    }

    public int updateDecisionNote(long id, String ceoNote) {
        return updateDecisionHeader(id, ceoNote, null);
    }

    /** Phase 2 (V187): {@code priceMode} joins {@code ceoNote} under the same COALESCE
     * ("omit/null = unchanged") semantics — chosen once, then left alone on every later call that
     * does not pass it again. DRAFT-only, same guard as every other mutation here. */
    public int updateDecisionHeader(long id, String ceoNote, String priceMode) {
        return jdbc.update("""
            UPDATE sales.pricing_decision
               SET ceo_note = COALESCE(:ceoNote, ceo_note),
                   price_mode = COALESCE(:priceMode, price_mode),
                   updated_at = now()
             WHERE pricing_decision_id = :id AND status = 'DRAFT'
            """, new MapSqlParameterSource().addValue("id", id).addValue("ceoNote", ceoNote)
                .addValue("priceMode", priceMode));
    }

    /**
     * Phase 2 (V187) price-mode fields -- {@code listUnitPrice}/{@code discountPct} (NET),
     * {@code specialPriceSqm} (SPECIAL_SQM), {@code directNetPrice} (DIRECT_NET). Review finding
     * #6 (2026-09-19): each needs the SAME tri-state (set / clear / leave untouched) the legacy
     * {@code sellingPriceOverride} already has, not a plain COALESCE -- a blanked input (the CEO
     * clears a field back to empty) must actually clear the stored value, not silently leave the
     * old one in place because a null parameter is indistinguishable from "omitted". The paired
     * {@code clearXxx} boolean is that explicit "yes, really clear this" signal.
     *
     * <p>{@code netUnitPrice}/{@code netUnitPriceComputed} is a different shape again: the SERVER
     * always decides this column's value (never client input), so the tri-state here is "the
     * caller computed a definitive value (write it, even NULL)" vs. "this call did not touch net
     * at all (leave it alone)" -- {@code netUnitPriceComputed} is that second signal. Reused by
     * {@code PricingDecisionService}'s mode-switch recompute (review finding #2): a pure
     * net-only write, every other field left null/false/untouched.
     */
    public record ItemUpdate(
        long itemId, BigDecimal marginPct, BigDecimal sellingPrice,
        BigDecimal minimumSellingPrice, String decisionNote,
        BigDecimal sellingPriceOverride, boolean clearSellingPriceOverride,
        BigDecimal listUnitPrice, boolean clearListUnitPrice,
        BigDecimal discountPct, boolean clearDiscountPct,
        BigDecimal specialPriceSqm, boolean clearSpecialPriceSqm,
        BigDecimal directNetPrice, boolean clearDirectNetPrice,
        BigDecimal netUnitPrice, boolean netUnitPriceComputed) {
        /** The pre-finding-#6/#2 shape (plain COALESCE, no clear/compute signals) -- kept so the
         * few call sites that only ever SET (never clear) these fields, and never touch net
         * unilaterally, stay simple. */
        public ItemUpdate(long itemId, BigDecimal marginPct, BigDecimal sellingPrice,
                          BigDecimal minimumSellingPrice, String decisionNote,
                          BigDecimal sellingPriceOverride, boolean clearSellingPriceOverride) {
            this(itemId, marginPct, sellingPrice, minimumSellingPrice, decisionNote,
                sellingPriceOverride, clearSellingPriceOverride,
                null, false, null, false, null, false, null, false, null, false);
        }
    }

    /** Returns the number of rows actually touched — the caller compares this against the
     * requested item count to detect an itemId that does not belong to this decision.
     *
     * <p>{@code manual_selling_price_per_requested_unit} cannot use the same plain
     * {@code COALESCE(:param, column)} shape every other column here uses, because "omit" and
     * "explicitly clear to NULL" both look like a null parameter to COALESCE — the CASE
     * expression is what gives {@link ItemUpdate#clearSellingPriceOverride()} a real clear path
     * distinct from "this call did not touch the override at all" (see
     * {@link PricingDecisionRequests.UpdatePricingDecisionItemRequest}'s own Javadoc for why). */
    public int updateItems(long decisionId, List<ItemUpdate> updates) {
        if (updates.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[updates.size()];
        for (int i = 0; i < updates.size(); i++) {
            ItemUpdate u = updates.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("decisionId", decisionId)
                .addValue("itemId", u.itemId())
                .addValue("marginPct", u.marginPct())
                .addValue("sellingPrice", u.sellingPrice())
                .addValue("minimumSellingPrice", u.minimumSellingPrice())
                .addValue("decisionNote", u.decisionNote())
                .addValue("sellingPriceOverride", u.sellingPriceOverride())
                .addValue("clearOverride", u.clearSellingPriceOverride())
                .addValue("listUnitPrice", u.listUnitPrice())
                .addValue("clearListUnitPrice", u.clearListUnitPrice())
                .addValue("discountPct", u.discountPct())
                .addValue("clearDiscountPct", u.clearDiscountPct())
                .addValue("specialPriceSqm", u.specialPriceSqm())
                .addValue("clearSpecialPriceSqm", u.clearSpecialPriceSqm())
                .addValue("directNetPrice", u.directNetPrice())
                .addValue("clearDirectNetPrice", u.clearDirectNetPrice())
                .addValue("netUnitPrice", u.netUnitPrice())
                .addValue("netUnitPriceComputed", u.netUnitPriceComputed());
        }
        int[] counts = jdbc.batchUpdate("""
            UPDATE sales.pricing_decision_item
               SET proposed_margin_pct = COALESCE(:marginPct, proposed_margin_pct),
                   proposed_selling_price_per_requested_unit =
                       COALESCE(:sellingPrice, proposed_selling_price_per_requested_unit),
                   minimum_selling_price_per_requested_unit =
                       COALESCE(:minimumSellingPrice, minimum_selling_price_per_requested_unit),
                   decision_note = COALESCE(:decisionNote, decision_note),
                   manual_selling_price_per_requested_unit = CASE
                       WHEN :clearOverride THEN NULL
                       ELSE COALESCE(:sellingPriceOverride, manual_selling_price_per_requested_unit)
                   END,
                   -- Phase 2 (V187), review finding #6: each price-mode input gets the SAME
                   -- tri-state (set / clear / leave untouched) as manual_selling_price above --
                   -- see ItemUpdate's own Javadoc for why a plain COALESCE cannot express "clear".
                   list_unit_price = CASE
                       WHEN :clearListUnitPrice THEN NULL
                       ELSE COALESCE(:listUnitPrice, list_unit_price)
                   END,
                   discount_pct = CASE
                       WHEN :clearDiscountPct THEN NULL
                       ELSE COALESCE(:discountPct, discount_pct)
                   END,
                   special_price_sqm = CASE
                       WHEN :clearSpecialPriceSqm THEN NULL
                       ELSE COALESCE(:specialPriceSqm, special_price_sqm)
                   END,
                   direct_net_price = CASE
                       WHEN :clearDirectNetPrice THEN NULL
                       ELSE COALESCE(:directNetPrice, direct_net_price)
                   END,
                   -- net_unit_price is NEVER client input -- netUnitPriceComputed is the caller
                   -- (PricingDecisionService) saying "I derived a definitive value this call,
                   -- write it verbatim, even if NULL"; false means this call did not touch net
                   -- at all (reused as-is by the mode-switch recompute, review finding #2).
                   net_unit_price = CASE
                       WHEN :netUnitPriceComputed THEN :netUnitPrice
                       ELSE net_unit_price
                   END,
                   updated_at = now()
             WHERE pricing_decision_item_id = :itemId
               AND pricing_decision_id = :decisionId
               AND EXISTS (
                   SELECT 1 FROM sales.pricing_decision pd
                    WHERE pd.pricing_decision_id = :decisionId AND pd.status = 'DRAFT')
            """, batch);
        int total = 0;
        for (int c : counts) {
            total += c;
        }
        return total;
    }

    /**
     * V141 ("CEO owns costing"): re-derives a DRAFT decision item's FROZEN cost columns —
     * previously write-once at {@code insertItems} time, never touched again by any repository
     * method. Now needed twice: {@code PricingDecisionService#recalculateCost} (the bound costing
     * was just recomputed) and {@code #overrideItemCost} (one line's effective cost just changed).
     * Deliberately does NOT touch {@code proposed_margin_pct}/{@code approved_*} — a cost-driven
     * recompute never overwrites a margin the CEO already set, or anything {@code approve()} froze.
     * Same DRAFT-only guard shape as {@link #updateItems}.
     *
     * <p>{@code listUnitPrice}/{@code netUnitPrice} (owner correction, 2026-09-19): whenever
     * {@code proposedSellingPrice} (the formula reference) changes here, Phase 2's NET-mode list
     * price must move WITH it — {@code list_unit_price} is never a client input (ruling A: the CEO
     * never types it), so the ONLY way it can stay correct after a cost-driven recompute is for
     * this exact write path to carry it. Freezing it at {@code insertItems} time only (the
     * original, wrong design) left an item that starts uncostable permanently unable to reach a
     * NET approval even after {@code overrideItemCost} made it costable — reintroducing review
     * finding #1's deadlock through a second door. Both fields are UNCONDITIONAL overwrites here
     * (always {@code money2(proposedSellingPrice)}/the freshly recomputed net, including to NULL
     * when the item just became uncostable again) — never tri-state, because neither is ever
     * client-supplied on this path; see {@code PricingDecisionService#deriveListAndNet}. */
    public record FrozenCostUpdate(
        long itemId, BigDecimal frozenLandedCostPerPieceThb, BigDecimal frozenLandedCostPerRequestedUnitThb,
        BigDecimal proposedSellingPrice, BigDecimal listUnitPrice, BigDecimal netUnitPrice) {}

    public int updateFrozenCosts(long decisionId, List<FrozenCostUpdate> updates) {
        if (updates.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[updates.size()];
        for (int i = 0; i < updates.size(); i++) {
            FrozenCostUpdate u = updates.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("decisionId", decisionId)
                .addValue("itemId", u.itemId())
                .addValue("frozenPerPiece", u.frozenLandedCostPerPieceThb())
                .addValue("frozenPerRequestedUnit", u.frozenLandedCostPerRequestedUnitThb())
                .addValue("sellingPrice", u.proposedSellingPrice())
                .addValue("listUnitPrice", u.listUnitPrice())
                .addValue("netUnitPrice", u.netUnitPrice());
        }
        int[] counts = jdbc.batchUpdate("""
            UPDATE sales.pricing_decision_item
               SET frozen_landed_cost_per_piece_thb = :frozenPerPiece,
                   frozen_landed_cost_per_requested_unit_thb = :frozenPerRequestedUnit,
                   proposed_selling_price_per_requested_unit = :sellingPrice,
                   list_unit_price = :listUnitPrice,
                   net_unit_price = :netUnitPrice,
                   updated_at = now()
             WHERE pricing_decision_item_id = :itemId
               AND pricing_decision_id = :decisionId
               AND EXISTS (
                   SELECT 1 FROM sales.pricing_decision pd
                    WHERE pd.pricing_decision_id = :decisionId AND pd.status = 'DRAFT')
            """, batch);
        int total = 0;
        for (int c : counts) {
            total += c;
        }
        return total;
    }

    /**
     * {@code minimumSellingPrice} is written here unconditionally (never COALESCE'd against the
     * existing value) — the caller ({@link PricingDecisionService#approve}) has already resolved
     * it to either the CEO's explicitly-set floor (still settable via {@link #updateItems}, kept
     * for compatibility) or a fallback equal to {@code approvedSellingPrice} itself, so by the
     * time this method runs there is always exactly one correct value to write, not two to merge.
     * Phase 1 UI simplification: this is what keeps
     * {@code CustomerQuotationService}'s three null-guarded minimum-price checks load-bearing now
     * that ราคาขั้นต่ำ is no longer a CEO input — see {@code PricingDecisionService#approve}'s own
     * doc comment for the full reasoning.
     */
    public record ApprovedItem(
        long itemId, BigDecimal approvedMarginPct, BigDecimal approvedSellingPrice,
        BigDecimal minimumSellingPrice) {}

    public void approveItems(long decisionId, List<ApprovedItem> items) {
        if (items.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            ApprovedItem item = items.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("decisionId", decisionId)
                .addValue("itemId", item.itemId())
                .addValue("approvedMarginPct", item.approvedMarginPct())
                .addValue("approvedSellingPrice", item.approvedSellingPrice())
                .addValue("minimumSellingPrice", item.minimumSellingPrice());
        }
        jdbc.batchUpdate("""
            UPDATE sales.pricing_decision_item
               SET approved_margin_pct = :approvedMarginPct,
                   approved_selling_price_per_requested_unit = :approvedSellingPrice,
                   minimum_selling_price_per_requested_unit = :minimumSellingPrice,
                   updated_at = now()
             WHERE pricing_decision_item_id = :itemId AND pricing_decision_id = :decisionId
            """, batch);
    }

    /**
     * Compare-and-set DRAFT -&gt; APPROVED. Rowcount 0 means either the decision was not DRAFT
     * (already approved/returned by a concurrent caller, or this is a stale id) — the caller
     * (service), inside the same advisory-lock-held transaction, is responsible for
     * distinguishing "already approved by me" (idempotent replay via
     * {@code approveClientRequestId}) from a genuine conflict.
     */
    public int approve(long id, long actorId, String ceoNote, String approveClientRequestId) {
        return jdbc.update("""
            UPDATE sales.pricing_decision
               SET status = 'APPROVED',
                   approved_by = :actorId,
                   approved_at = now(),
                   ceo_note = COALESCE(:ceoNote, ceo_note),
                   approve_client_request_id = CAST(:approveClientRequestId AS uuid),
                   updated_at = now()
             WHERE pricing_decision_id = :id AND status = 'DRAFT'
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("actorId", actorId)
                .addValue("ceoNote", ceoNote)
                .addValue("approveClientRequestId", approveClientRequestId));
    }

    public int returnToImport(long id, String returnReason) {
        return jdbc.update("""
            UPDATE sales.pricing_decision
               SET status = 'RETURNED',
                   return_reason = :reason,
                   returned_at = now(),
                   updated_at = now()
             WHERE pricing_decision_id = :id AND status = 'DRAFT'
            """, new MapSqlParameterSource().addValue("id", id).addValue("reason", returnReason));
    }

    public Optional<PricingDecisionDto> find(long id) {
        try {
            PricingDecisionDto dto = jdbc.queryForObject(baseSelect() + " WHERE pd.pricing_decision_id = :id",
                Map.of("id", id), (rs, rowNum) -> mapDecision(rs, findItems(id)));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<PricingDecisionDto> findOpenDraft(long pricingRequestId) {
        try {
            PricingDecisionDto dto = jdbc.queryForObject(baseSelect() + """
                 WHERE pd.pricing_request_id = :pricingRequestId AND pd.status = 'DRAFT'
                """,
                Map.of("pricingRequestId", pricingRequestId),
                (rs, rowNum) -> mapDecision(rs, findItems(rs.getLong("pricing_decision_id"))));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<PricingDecisionDto> findByClientRequestId(long createdBy, String clientRequestId) {
        try {
            PricingDecisionDto dto = jdbc.queryForObject(baseSelect() + """
                 WHERE pd.created_by = :createdBy AND pd.client_request_id = CAST(:clientRequestId AS uuid)
                """,
                new MapSqlParameterSource().addValue("createdBy", createdBy).addValue("clientRequestId", clientRequestId),
                (rs, rowNum) -> mapDecision(rs, findItems(rs.getLong("pricing_decision_id"))));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<PricingDecisionDto> findByApproveClientRequestId(long approvedBy, String approveClientRequestId) {
        try {
            PricingDecisionDto dto = jdbc.queryForObject(baseSelect() + """
                 WHERE pd.approved_by = :approvedBy
                   AND pd.approve_client_request_id = CAST(:clientRequestId AS uuid)
                """,
                new MapSqlParameterSource().addValue("approvedBy", approvedBy).addValue("clientRequestId", approveClientRequestId),
                (rs, rowNum) -> mapDecision(rs, findItems(rs.getLong("pricing_decision_id"))));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<PricingDecisionDto> findByPricingRequest(long pricingRequestId) {
        return jdbc.query(baseSelect() + """
             WHERE pd.pricing_request_id = :pricingRequestId
             ORDER BY pd.decision_version_no, pd.pricing_decision_id
            """, Map.of("pricingRequestId", pricingRequestId),
            (rs, rowNum) -> mapDecision(rs, findItems(rs.getLong("pricing_decision_id"))));
    }

    public List<PricingDecisionItemDto> findItems(long decisionId) {
        return jdbc.query("""
            SELECT pdi.pricing_decision_item_id, pdi.pricing_decision_id, pdi.pricing_request_item_id,
                   pdi.pricing_costing_item_id, pri.brand, pri.model, pri.product_description,
                   pci.factory_name, pdi.requested_unit_basis, pdi.requested_quantity,
                   pdi.normalized_quantity_pieces, pdi.frozen_landed_cost_per_piece_thb,
                   pdi.frozen_landed_cost_per_requested_unit_thb, pdi.currency, pdi.proposed_margin_pct,
                   pdi.approved_margin_pct, pdi.proposed_selling_price_per_requested_unit,
                   pdi.approved_selling_price_per_requested_unit,
                   pdi.minimum_selling_price_per_requested_unit, pdi.decision_note,
                   pdi.created_at, pdi.updated_at, pdi.manual_selling_price_per_requested_unit,
                   pri.sqm_per_piece, pdi.list_unit_price, pdi.discount_pct, pdi.special_price_sqm,
                   pdi.direct_net_price, pdi.net_unit_price
              FROM sales.pricing_decision_item pdi
              JOIN sales.pricing_request_item pri ON pri.pricing_request_item_id = pdi.pricing_request_item_id
              JOIN sales.pricing_costing_item pci ON pci.pricing_costing_item_id = pdi.pricing_costing_item_id
             WHERE pdi.pricing_decision_id = :decisionId
             ORDER BY pdi.pricing_decision_item_id
            """, Map.of("decisionId", decisionId), (rs, rowNum) -> mapItem(rs));
    }

    /** Design correction 2's sales-facing projection — see {@link PricingDecisionDtos.PricingDecisionSalesViewDto}. */
    public Optional<PricingDecisionSalesViewDto> findApprovedSalesView(long pricingRequestId) {
        try {
            PricingDecisionSalesViewDto header = jdbc.queryForObject("""
                SELECT pricing_decision_id, currency, approved_at
                  FROM sales.pricing_decision
                 WHERE pricing_request_id = :pricingRequestId AND status = 'APPROVED'
                """, Map.of("pricingRequestId", pricingRequestId),
                (rs, rowNum) -> new PricingDecisionSalesViewDto(pricingRequestId, rs.getLong("pricing_decision_id"),
                    rs.getString("currency"), rs.getTimestamp("approved_at").toInstant(), List.of()));
            // color/texture/size added here (sales-view only — findItems() above is the OTHER
            // query, feeding the cost-bearing PricingDecisionItemDto, and is deliberately left
            // untouched) so Step 4's CustomerQuotationService#buildItem has them to snapshot
            // onto sales.quotation_item's legacy render columns — see
            // PricingDecisionSalesItemDto's own field comment for the full chain.
            List<PricingDecisionSalesItemDto> items = jdbc.query("""
                SELECT pdi.pricing_decision_item_id, pdi.pricing_request_item_id, pri.brand, pri.model,
                       pri.color, pri.texture, pri.size,
                       pri.product_description, pdi.requested_unit_basis, pdi.requested_quantity,
                       pdi.approved_selling_price_per_requested_unit,
                       pdi.minimum_selling_price_per_requested_unit
                  FROM sales.pricing_decision_item pdi
                  JOIN sales.pricing_request_item pri ON pri.pricing_request_item_id = pdi.pricing_request_item_id
                 WHERE pdi.pricing_decision_id = :decisionId
                 ORDER BY pdi.pricing_decision_item_id
                """, Map.of("decisionId", header.pricingDecisionId()),
                (rs, rowNum) -> new PricingDecisionSalesItemDto(
                    rs.getLong("pricing_request_item_id"), rs.getLong("pricing_decision_item_id"),
                    rs.getString("brand"), rs.getString("model"),
                    rs.getString("color"), rs.getString("texture"), rs.getString("size"),
                    rs.getString("product_description"), rs.getString("requested_unit_basis"),
                    rs.getBigDecimal("requested_quantity"), rs.getBigDecimal("approved_selling_price_per_requested_unit"),
                    rs.getBigDecimal("minimum_selling_price_per_requested_unit")));
            return Optional.of(new PricingDecisionSalesViewDto(
                header.pricingRequestId(), header.pricingDecisionId(), header.currency(), header.approvedAt(), items));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private String baseSelect() {
        return """
            SELECT pricing_decision_id, decision_code, pricing_request_id, pricing_costing_id,
                   decision_version_no, status, default_margin_pct, currency, fx_rate_used, fx_source,
                   fx_effective_date, ceo_note, return_reason, created_by, created_at, updated_at,
                   approved_by, approved_at, returned_at, price_mode
              FROM sales.pricing_decision pd
            """;
    }

    private PricingDecisionDto mapDecision(ResultSet rs, List<PricingDecisionItemDto> items) throws SQLException {
        return new PricingDecisionDto(
            rs.getLong("pricing_decision_id"),
            rs.getString("decision_code"),
            rs.getLong("pricing_request_id"),
            rs.getLong("pricing_costing_id"),
            rs.getInt("decision_version_no"),
            rs.getString("status"),
            rs.getBigDecimal("default_margin_pct"),
            rs.getString("currency"),
            rs.getBigDecimal("fx_rate_used"),
            rs.getString("fx_source"),
            rs.getDate("fx_effective_date").toLocalDate(),
            rs.getString("ceo_note"),
            rs.getString("return_reason"),
            nullableLong(rs, "created_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            nullableLong(rs, "approved_by"),
            instant(rs, "approved_at"),
            instant(rs, "returned_at"),
            items,
            rs.getString("price_mode")
        );
    }

    private PricingDecisionItemDto mapItem(ResultSet rs) throws SQLException {
        BigDecimal proposedSellingPrice = rs.getBigDecimal("proposed_selling_price_per_requested_unit");
        BigDecimal manualSellingPrice = rs.getBigDecimal("manual_selling_price_per_requested_unit");
        // Mirrors PricingCostingRepository#mapItem's effectiveLandedCostPerUnitThb exactly: a
        // straight passthrough of whichever of the two is active, recomputed on every read so it
        // can never drift out of sync with the row it describes.
        BigDecimal effectiveSellingPrice = manualSellingPrice != null ? manualSellingPrice : proposedSellingPrice;
        return new PricingDecisionItemDto(
            rs.getLong("pricing_decision_item_id"),
            rs.getLong("pricing_decision_id"),
            rs.getLong("pricing_request_item_id"),
            rs.getLong("pricing_costing_item_id"),
            rs.getString("brand"),
            rs.getString("model"),
            rs.getString("product_description"),
            rs.getString("factory_name"),
            rs.getString("requested_unit_basis"),
            rs.getBigDecimal("requested_quantity"),
            rs.getBigDecimal("normalized_quantity_pieces"),
            rs.getBigDecimal("frozen_landed_cost_per_piece_thb"),
            rs.getBigDecimal("frozen_landed_cost_per_requested_unit_thb"),
            rs.getString("currency"),
            rs.getBigDecimal("proposed_margin_pct"),
            rs.getBigDecimal("approved_margin_pct"),
            proposedSellingPrice,
            rs.getBigDecimal("approved_selling_price_per_requested_unit"),
            rs.getBigDecimal("minimum_selling_price_per_requested_unit"),
            rs.getString("decision_note"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            manualSellingPrice,
            effectiveSellingPrice,
            rs.getBigDecimal("sqm_per_piece"),
            rs.getBigDecimal("list_unit_price"),
            rs.getBigDecimal("discount_pct"),
            rs.getBigDecimal("special_price_sqm"),
            rs.getBigDecimal("direct_net_price"),
            rs.getBigDecimal("net_unit_price")
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
