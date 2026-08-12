-- V141 — Import records the factory price; the CEO owns all costing and pricing.
--
-- Owner ruling. Import's costing step (createDraft/recalculate/submit — all deleted from
-- PricingCostingService/-Controller/-Repository in this same commit) entered ZERO real data of
-- its own (CreateCostingRequest{note, clientRequestId}, RecalculateCostingRequest{note}); it
-- existed only to trigger the SAME deterministic LandedCostCalculator.calculate() the CEO's
-- review now runs directly. So: Import's last act is marking each factory quote ready
-- (FactoryQuoteService.markReadyForCosting). Once every item's quote is ready, the pricing
-- request auto-advances straight to READY_FOR_CEO_REVIEW — no COSTING_IN_PROGRESS costing draft
-- in between any more — and the CEO's PricingDecisionService.startReview computes + persists the
-- costing itself, in the SAME transaction that creates the pricing_decision.
--
-- Two consequences for this schema:
--
--   1. COSTING_REVISION_REQUIRED is RETIRED. A submitted costing used to be "revised" by sending
--      the pricing request back to Import for a brand-new DRAFT/CALCULATED/SUBMITTED cycle. Now
--      there is no draft cycle to send it back TO — returnToImport instead sends the request to
--      AWAITING_FACTORY_RESPONSE (PricingRequestStatus's new CEO_REVIEWING ->
--      AWAITING_FACTORY_RESPONSE edge), and a NEW back-edge (READY_FOR_CEO_REVIEW ->
--      AWAITING_FACTORY_RESPONSE) lets a factory quote revision that arrives after the request
--      already reached READY_FOR_CEO_REVIEW pull it back, instead of marking a (no-longer-exists-
--      until-review-time) DRAFT/CALCULATED costing "stale". See PricingRequestStatus.java for the
--      full state-machine change.
--
--   2. pricing_costing.stale / stale_reason are RETIRED along with them. A cost computed at
--      CEO-review time, from whatever factory quote is current at that moment, can never itself
--      be "stale" the way a costing sitting in Import's queue for days could. What CAN go stale
--      now is a per-LINE manual cost override the CEO enters over the computed figure (new
--      columns below) — if the FX rate or calc-config version used to compute the line changes
--      AFTER the override was entered, the override becomes stale and blocks approval
--      (PricingDecisionService.approve). That is a fundamentally different, per-item, always-
--      DERIVED (never stored) concept, tracked by the new pricing_costing_item columns below, not
--      by the header-level boolean this migration drops.
--
-- FORWARD-ONLY. Data is migrated before any constraint is narrowed, so the constraint can never
-- reject a pre-existing row. Never edit V140 or any earlier migration.

-- ── 1. Migrate any live row off the retired status ─────────────────────────────────────────
-- Expected to affect 0 rows: no production deal has ever reached COSTING_REVISION_REQUIRED (Step
-- 3 has not been independently deployable — see PricingRequestStatus's own history), and the UAT
-- golden seed sits at QUOTATION_ACCEPTED. Verify before deploy, don't assume:
--   SELECT count(*) FROM sales.pricing_request WHERE status = 'COSTING_REVISION_REQUIRED';
-- A non-zero count still means the correct outcome (AWAITING_FACTORY_RESPONSE is exactly where a
-- CEO-returned request now belongs — see the CEO_REVIEWING -> AWAITING_FACTORY_RESPONSE edge in
-- PricingRequestStatus) — it would only mean the "expected 0" assumption above was wrong for that
-- environment, not that this UPDATE itself is wrong.
UPDATE sales.pricing_request
   SET status = 'AWAITING_FACTORY_RESPONSE',
       updated_at = now()
 WHERE status = 'COSTING_REVISION_REQUIRED';

-- ── 2. Narrow the status constraint (was V59 -> V61 -> V72 -> V74 -> V75 -> V140) ──────────
ALTER TABLE sales.pricing_request
    DROP CONSTRAINT chk_pricing_request_status;
ALTER TABLE sales.pricing_request
    ADD CONSTRAINT chk_pricing_request_status CHECK (status IN (
        'DRAFT', 'SUBMITTED', 'IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE',
        'READY_FOR_CEO_REVIEW', 'CEO_REVIEWING', 'APPROVED_FOR_QUOTATION',
        'QUOTATION_ISSUED', 'QUOTATION_ACCEPTED', 'CANCELLED', 'SUPERSEDED'
    ));

-- ── 3. CEO cost override columns on pricing_costing_item ───────────────────────────────────
-- Sits BESIDE landed_cost_per_unit_thb / total_landed_cost_thb, which keep holding the COMPUTED
-- figures untouched forever — the override never overwrites them. manual_landed_cost_per_unit_thb
-- mirrors landed_cost_per_unit_thb's own PER-PIECE basis (not total_landed_cost_thb's pre-
-- multiplied per-line total) — the deliberately narrow thing a CEO actually edits is the unit
-- rate, not a total. The "effective" cost, its total, and its staleness are all DERIVED at read
-- time (PricingCostingRepository.mapItem), never stored, so none of the three can drift out of
-- sync with the row they describe.
ALTER TABLE sales.pricing_costing_item
    ADD COLUMN manual_landed_cost_per_unit_thb NUMERIC(18,4),
    ADD COLUMN override_reason TEXT,
    ADD COLUMN overridden_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    ADD COLUMN overridden_at TIMESTAMPTZ,
    ADD COLUMN override_fx_rate NUMERIC(12,6),
    ADD COLUMN override_calc_config_version INTEGER;

ALTER TABLE sales.pricing_costing_item
    ADD CONSTRAINT chk_pricing_costing_item_override_reason CHECK (
        manual_landed_cost_per_unit_thb IS NULL
        OR (override_reason IS NOT NULL AND btrim(override_reason) <> '')
    ),
    ADD CONSTRAINT chk_pricing_costing_item_override_provenance CHECK (
        manual_landed_cost_per_unit_thb IS NULL
        OR (override_fx_rate IS NOT NULL AND override_calc_config_version IS NOT NULL)
    ),
    ADD CONSTRAINT chk_pricing_costing_item_override_nonnegative CHECK (
        manual_landed_cost_per_unit_thb IS NULL OR manual_landed_cost_per_unit_thb >= 0
    );

COMMENT ON COLUMN sales.pricing_costing_item.manual_landed_cost_per_unit_thb IS
    'CEO override of landed_cost_per_unit_thb (same PER-PIECE basis). NULL = no override, use the computed figure. Never overwrites landed_cost_per_unit_thb itself. Preserved across a recalculate (PricingCostingRepository#replaceItemsPreservingOverrides) -- cleared only by an explicit CEO action.';
COMMENT ON COLUMN sales.pricing_costing_item.override_fx_rate IS
    'fx_rate snapshotted at override time, from THIS row''s then-current computed value -- compared against the row''s live fx_rate to derive overrideStale at read time. Not itself the rate used for any calculation.';
COMMENT ON COLUMN sales.pricing_costing_item.override_calc_config_version IS
    'calculation_config_version snapshotted at override time, for the same staleness comparison as override_fx_rate.';

-- ── 4. pricing_costing.stale / stale_reason are RETIRED ─────────────────────────────────────
-- Nothing writes them once FactoryQuoteService.receive()'s revision branch stops calling
-- markOpenCostingsStale (deleted in this same commit) -- a permanently-FALSE column left in place
-- would only invite a future reader to trust it. The header-level "is this costing current"
-- question is now answered by the pricing_request's OWN status (READY_FOR_CEO_REVIEW/CEO_REVIEWING
-- means yes, a costing computed at review time is current by construction); the per-LINE "is this
-- override current" question is answered by the new derived overrideStale above.
ALTER TABLE sales.pricing_costing
    DROP COLUMN stale,
    DROP COLUMN stale_reason;

-- ── 5. History is NOT rewritten ──────────────────────────────────────────────────────────────
-- sales.pricing_request_event rows keep their original from_status/to_status values, including
-- 'COSTING_REVISION_REQUIRED'. PRICING_COSTING_STARTED / PRICING_COSTING_CALCULATED /
-- PRICING_COSTING_SUBMITTED stay valid in PricingRequestEventKind.VALUES even though nothing new
-- emits PRICING_COSTING_STARTED/_CALCULATED any more (PRICING_COSTING_SUBMITTED is reused for the
-- new auto-advance transition -- see FactoryQuoteService.markReadyForCosting). There is no CHECK
-- constraint on sales.pricing_request_event's status/kind text columns, so nothing here needs to
-- change for historical rows to keep reading back correctly.
