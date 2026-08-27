-- CEO selling-price decision UI simplification (Phase 1 of 2), owner ruling 2026-08-16.
--
-- MIGRATION NUMBERING: this is V150. Verified free by checking every local branch, every remote
-- branch, and every on-disk worktree (tracked AND untracked files) for a migration numbered V150
-- or higher via `git ls-tree -r --name-only <ref> -- backend/src/main/resources/db/migration` over
-- `git for-each-ref refs/heads refs/remotes`, plus a plain `ls` of each worktree's migration
-- directory on disk. `main` itself tops out at V149 (V149__drop_dead_duplicate_division_rows.sql).
-- Nothing anywhere goes above V149, so V150 is free everywhere checked at the time of writing.
--
-- Two independent changes, both scoped to sales.pricing_decision_item:
--
--   1. ส่วนลดสูงสุด (discount ceiling) is removed from the whole system — owner's words. The
--      column (discount_ceiling_pct, added by V72) is dropped. Nothing else in the schema
--      references it (grepped: only V72 defines it; no view, index, trigger, or other migration
--      touches it), so this is a plain, safe DROP COLUMN. Forward-only: no earlier migration is
--      edited.
--
--   2. ปรับราคาเอง (CEO manual selling-price override) — a REAL behaviour change, not a relabel.
--      Mirrors the V141 "CEO owns costing" cost-override design on sales.pricing_costing_item
--      (manual_landed_cost_per_unit_thb / override_reason there) as closely as the existing
--      table allows, reusing decision_note (already on this row, already free-text, already
--      CEO-only) as the override's mandatory reason column instead of adding a second one —
--      owner/coordinator steer: prefer reusing what exists over inventing new schema. Sits BESIDE
--      proposed_selling_price_per_requested_unit, which keeps holding the FORMULA's own computed
--      output forever; an override never overwrites it. NULL = no override, use the formula.
--      Non-null = the CEO's fixed final price for this line; the formula stops driving it (see
--      PricingDecisionService#approve, PricingDecisionService#applyItemUpdates).
--
--      Unlike the cost override, this carries no staleness columns (override_fx_rate /
--      override_calc_config_version) — a manually-fixed SELLING price has nothing to go stale
--      against (it is not derived from FX or a calc-config version the way landed cost is); a
--      cost recalculation simply leaves an overridden item's effective price untouched, by
--      construction, because the "effective" price for an overridden item is always the manual
--      value regardless of what the formula would now compute.

ALTER TABLE sales.pricing_decision_item
    DROP COLUMN discount_ceiling_pct;

ALTER TABLE sales.pricing_decision_item
    ADD COLUMN manual_selling_price_per_requested_unit NUMERIC(18,4);

ALTER TABLE sales.pricing_decision_item
    ADD CONSTRAINT chk_pricing_decision_item_price_override_reason CHECK (
        manual_selling_price_per_requested_unit IS NULL
        OR (decision_note IS NOT NULL AND btrim(decision_note) <> '')
    ),
    ADD CONSTRAINT chk_pricing_decision_item_price_override_nonnegative CHECK (
        manual_selling_price_per_requested_unit IS NULL OR manual_selling_price_per_requested_unit >= 0
    );

COMMENT ON COLUMN sales.pricing_decision_item.manual_selling_price_per_requested_unit IS
    'CEO override of proposed_selling_price_per_requested_unit (same PER-REQUESTED-UNIT basis, same currency as the parent decision). NULL = no override, the formula (frozen cost x margin, via computeSellingPrice) drives this line. Non-null freezes this exact value in at approve() time (PricingDecisionService#approve), ignoring margin entirely for this line. reason is mandatory in BOTH directions (set and clear) and is recorded in this row''s own decision_note, exactly like PricingDecisionRequests.CostOverrideRequest requires for the sibling cost override.';
