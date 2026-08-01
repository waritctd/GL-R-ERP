-- Issue #405: auto-compute the INCENTIVE ladder (ข้อ 12) and add the (initially disabled)
-- STOCK_BONUS (พิเศษขายของในสต๊อค) rule, replacing the "manual across the UI for now" placeholder
-- CommissionKind's comment described (feat/commission-manual-adjustments, V84).
--
-- Both tables are config-driven, following sales.tier_config's own pattern (V11.2/V81): the CEO
-- revises a ladder/config row without a deploy. Every row carries an effective_from generation —
-- CommissionRepository#findIncentiveTiers / #findStockBonusConfig always pick the latest
-- generation whose effective_from <= the payroll month being computed. That single
-- generation-selection rule is the fix-forward mechanism: a payroll month before 2026-08-01 has
-- no matching generation in either table, so it resolves to an empty ladder / a "disabled"
-- config and computes EXACTLY as it did before this migration (zero incentive, zero stock
-- bonus) -- ภ.ง.ด.1 has already been filed for those months and must not be touched.
--
-- Owner decisions (already made, see the issue and CLAUDE.md's sales-flow-redesign section):
--   1. INCENTIVE ladder: fully reconciled against the June 2026 workbook -- ship computing.
--      There is deliberately NO 80,000 row here -- the workbook's second "8.00 ล้าน -> 80,000"
--      row is superseded and must not exist (see CommissionCalculatorTest / the reconciliation
--      integration test for the four-rep proof).
--   2. STOCK_BONUS: the stepped rule is fully built and tested, but ships seeded
--      enabled = FALSE. The CEO flips this single row to TRUE (or inserts a new later
--      generation) to turn it on -- no deploy required either way.
--   3. Effective 2026-08-01. Forward-only, no backfill: nothing before this date is recomputed.
--
-- Review fix (2026-08-02): both tables also CHECK effective_from >= 2026-08-01. Fix-forward was
-- previously only a DATA promise (nothing currently inserts an earlier generation) -- this makes
-- it a SCHEMA guarantee instead: without the CHECK, a later `INSERT ... effective_from =
-- '2026-05-01'` would silently re-price a month whose ภ.ง.ด.1 has already been filed, and nothing
-- in the application layer would catch it. Back-dating a generation is now structurally
-- impossible, not just undocumented.

CREATE TABLE sales.commission_incentive_tier (
    incentive_tier_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tier_number       INT           NOT NULL,
    threshold_base    NUMERIC(14,2) NOT NULL,
    incentive_amount  NUMERIC(14,2) NOT NULL,
    effective_from    DATE          NOT NULL,
    CONSTRAINT uq_commission_incentive_tier UNIQUE (effective_from, tier_number),
    CONSTRAINT chk_commission_incentive_tier_nonnegative CHECK (
        threshold_base >= 0 AND incentive_amount >= 0
    ),
    CONSTRAINT chk_commission_incentive_tier_effective_not_before_launch CHECK (
        effective_from >= DATE '2026-08-01'
    )
);

CREATE INDEX idx_commission_incentive_tier_effective ON sales.commission_incentive_tier(effective_from);

-- Reconciled against the June 2026 workbook (see the issue's four-rep table): highest threshold
-- reached wins, not cumulative, not pro-rated. No 80,000 row.
INSERT INTO sales.commission_incentive_tier (tier_number, threshold_base, incentive_amount, effective_from)
VALUES
    (1, 3000000.00, 15000.00, '2026-08-01'),
    (2, 4000000.00, 25000.00, '2026-08-01'),
    (3, 6000000.00, 50000.00, '2026-08-01'),
    (4, 8000000.00, 65000.00, '2026-08-01');

CREATE TABLE sales.stock_bonus_config (
    stock_bonus_config_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    enabled               BOOLEAN       NOT NULL DEFAULT FALSE,
    effective_from        DATE          NOT NULL,
    block_amount          NUMERIC(14,2) NOT NULL CHECK (block_amount > 0),
    bonus_per_block       NUMERIC(14,2) NOT NULL,
    CONSTRAINT uq_stock_bonus_config_effective UNIQUE (effective_from),
    CONSTRAINT chk_stock_bonus_config_nonnegative CHECK (bonus_per_block >= 0),
    CONSTRAINT chk_stock_bonus_config_effective_not_before_launch CHECK (
        effective_from >= DATE '2026-08-01'
    )
);

-- enabled = FALSE is the owner's decision, not an oversight -- the CEO enables this generation
-- (or seeds a later one already enabled) when ready.
INSERT INTO sales.stock_bonus_config (enabled, effective_from, block_amount, bonus_per_block)
VALUES (FALSE, '2026-08-01', 100000.00, 1000.00);
