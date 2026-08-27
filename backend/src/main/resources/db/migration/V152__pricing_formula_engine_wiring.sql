-- Wires V109's real selling-price formula into the pricing engine (full engine swap), owner
-- ruling 2026-08-16. Companion Java change in the same commit:
-- th/co/glr/hr/pricingcosting/LandedCostCalculator.java now reads sales.pricing_formula_config
-- (via a new th.co.glr.hr.pricingcosting.PricingFormulaEngine) instead of sales.price_calc_config
-- (V26). th/co/glr/hr/pricingdecision/PricingDecisionService.java#computeSellingPrice now applies
-- selling_buffer and rounds UP to selling_price_round_up_to, instead of a bare cost*(1+margin).
--
-- Until this migration, V109 (2026-08-02) stored the CEO's real formula as config but nothing
-- read it: PricingFormulaConfigController/Repository/Dtos say so in their own header comments
-- ("BRANCH 1 ... config storage only ... No calculation happens here or anywhere in this package
-- as part of this branch"). sales.price_calc_config (V26) is NOT touched or dropped here -- it
-- stays reachable via its own CRUD (PriceCalcConfigController) -- just no longer read by the
-- pricing engine as of this commit. See this branch's commit message for the "does anything still
-- read it" audit.
--
-- MIGRATION NUMBERING: this is V152, not V151. `main` (origin/main, 5de75e24) tops out at V150
-- (V150__ceo_pricing_decision_simplify.sql). V151 looked free at task-authoring time, but
-- re-verifying immediately before writing this file (per this branch's own instructions) found it
-- newly claimed: `.claude/worktrees/catalog-country` (branch feat/catalog-country-normalization)
-- has an UNTRACKED `V151__catalog_country_normalization.sql` on disk -- a genuine concurrent
-- session, not a stale leftover (that branch's own committed tree still tops out at V150; the
-- V151 file is only on disk, uncommitted). Checked via `git ls-tree -r` over every local branch
-- (`refs/heads`) and every remote branch (`refs/remotes`), plus a plain `ls` of every `git
-- worktree list --porcelain` worktree's migration directory on disk -- the same method V72/V150's
-- own headers used. V152 was free everywhere checked at the time of writing; re-verify again
-- before merging if time has passed, per the same precedent.
--
-- Three additions, all on tables the V109/V141/V61 chain already owns:
--
--   1. sales.pricing_request_item.product_type_override -- owner ruling 2026-08-16: duty
--      (T = duty_pct[product_type]) has no source in deal data today (the catalog has `collection`,
--      free text, no type column: grepped, confirmed), so every item defaults to TILE (30%) and
--      the CEO may override this PER ITEM (e.g. โมเสคแก้ว at 10%, not defaulted-TILE's 30%).
--      Lives on pricing_request_item, NOT pricing_decision_item, because LandedCostCalculator
--      reads PricingRequestItemDto and must see the override on the VERY FIRST costing computed
--      at PricingDecisionService#startReview -- before any pricing_decision_item exists yet.
--      Nullable: NULL means "no override, default to TILE" (a LandedCostCalculator constant, not
--      a DB default, so the default lives in the one place the formula itself lives). No CHECK
--      constraint against a fixed value list: sales.pricing_duty_rate.product_type is itself
--      CEO-configurable (POST /api/pricing-formula-config can add a new duty type with no
--      migration), so this column must accept whatever product_type strings the CURRENT config
--      defines -- validated at the application layer
--      (PricingDecisionService#overrideItemProductType) against the live config, not by a DB enum
--      that would go stale the next time the CEO adds a product type.
--
--   2. sales.pricing_costing_item.clearance_fee_thb -- V109's S (customs clearance fee), a
--      genuinely new cost bucket with no honest home among the V26-shaped columns
--      (freight_cost_thb/insurance_cost_thb/import_duty_thb are already spoken for by other V109
--      terms; inland_transport_cost_thb/other_cost_thb are zeroed -- see the Java change, V109 has
--      no inland-transport term). NOT NULL DEFAULT 0 so existing V26-computed rows (frozen,
--      belonging to already-approved decisions -- see this branch's
--      "approved decision price unchanged" test) read back as exactly 0 clearance fee, which is
--      the truthful answer: V26 never charged one.
--
--   3. sales.pricing_costing_item.product_type -- the resolved product_type (override, or TILE
--      default) actually used for THIS row's duty lookup, frozen at compute time for audit/
--      display (mirrors factory_name/raw_unit_price -- a computed input snapshotted per row, not
--      re-derived live on every read). Nullable: existing V26-computed rows have no product_type
--      concept at all.
--
-- calculation_config_id / calculation_config_version (V61) are REUSED, not replaced or
-- superseded by new columns: both are plain BIGINT/INTEGER with no FK constraint (checked: V61's
-- own CREATE TABLE has no REFERENCES clause on either), and were already generically named
-- "calculation config", not "price_calc_config" specifically. From this migration's Java
-- companion change onward, every NEWLY COMPUTED row holds
-- sales.pricing_formula_config.formula_config_id / .version in these two columns instead of
-- sales.price_calc_config.config_id / .version -- a DIFFERENT id space reusing the SAME column
-- pair. This is safe because nothing joins these columns back to either config table in SQL
-- (grepped: zero references anywhere in the codebase), and the one thing that reads them,
-- overrideStale (PricingCostingRepository#mapItem), only ever compares a row's OWN
-- calculation_config_version against ITS OWN override_calc_config_version -- both stamped from
-- the very same row, so a pre-cutover (V26-numbered) row and a post-cutover (V109-numbered) row
-- are never compared against each other. Documented here, in the Java change, and in this
-- branch's commit message, per this task's instruction not to smuggle a meaning change through
-- silently.

ALTER TABLE sales.pricing_request_item
    ADD COLUMN product_type_override VARCHAR(50);

ALTER TABLE sales.pricing_costing_item
    ADD COLUMN clearance_fee_thb NUMERIC(18,4) NOT NULL DEFAULT 0,
    ADD COLUMN product_type VARCHAR(50);

COMMENT ON COLUMN sales.pricing_request_item.product_type_override IS
    'CEO override of the duty product_type used at costing time (sales.pricing_duty_rate.product_type). NULL = no override, LandedCostCalculator defaults to TILE. Set via PricingDecisionService#overrideItemProductType (CEO-only), reachable from the pricing-decision review screen (PricingRequestDetailPage). Validated against the CURRENT pricing_formula_config''s duty rates at write time, not by a DB CHECK constraint, since the duty type list itself is CEO-configurable.';

COMMENT ON COLUMN sales.pricing_costing_item.clearance_fee_thb IS
    'V109''s S (customs clearance fee), looked up ONCE per factory shipment (sales.factory_quote, current revision) from sales.pricing_clearance_fee using the shipment''s total sqm, then allocated across the shipment''s items by each item''s own share of that total sqm. NOT NULL DEFAULT 0 so pre-V109 (V26-computed, frozen/approved) rows read back as the truthful 0 -- V26 never charged a clearance fee.';

COMMENT ON COLUMN sales.pricing_costing_item.product_type IS
    'The resolved product_type (pricing_request_item.product_type_override, or TILE if unset) actually used for THIS row''s duty lookup at compute time. NULL on pre-V109 rows, which have no product_type concept.';

COMMENT ON COLUMN sales.pricing_costing_item.calculation_config_id IS
    'As of V152: sales.pricing_formula_config.formula_config_id (V109 engine), NOT sales.price_calc_config.config_id (V26 engine) -- see V152''s own migration header for why the column is reused rather than replaced. Rows computed before V152 (frozen, on already-approved decisions) still hold a price_calc_config.config_id value in this same column.';

COMMENT ON COLUMN sales.pricing_costing_item.calculation_config_version IS
    'As of V152: sales.pricing_formula_config.version (V109), NOT sales.price_calc_config.version (V26) -- see calculation_config_id''s comment and V152''s own migration header.';
