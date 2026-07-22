-- Commit 3 of the sales pricing-flow financial-integrity remediation plan.
-- Scope: Finding A (catalog is now mandatory to submit a pricing request) and
-- Finding B (requested-quantity unit basis must be machine-readable so costing
-- can normalize price and quantity onto the same basis before multiplying).
--
-- MIGRATION NUMBERING: this is V68, not V66. V66 is claimed by an untracked
-- migration file on a parallel worktree (feat/special-money-requests,
-- .claude/worktrees/special-money/backend/src/main/resources/db/migration/
-- V66__special_money_request_schema.sql) which does not show up in this
-- branch's own `git log`/`git diff` but is real in-progress work on a
-- different branch that would collide with this branch's V66 on merge. V65
-- and V67 are already taken in this working tree (commits 1-2 of this same
-- remediation plan). Checked live on 2026-07-20 against: this branch's own
-- V65/V67, origin/main (V54), hosted prod/UAT, and every other open
-- worktree's migration directory — same reasoning V67's own header already
-- recorded when V66 was first skipped.

-- ─────────────────────────────────────────────────────────────────────────
-- Finding A follow-up: sales.pricing_request_item.product_id has always had
-- an FK to sales.catalog(catalog_id) (V59) — but the catalog picker
-- (CatalogRepository.searchProductPrices, GET /catalog/prices, what
-- PricingRequestCreateModal actually calls) and PricingRequestRepository.
-- snapshotCatalogSelections both key off price_catalog.product_prices.price_id,
-- a completely different id space. This is a genuine pre-existing bug,
-- discovered while writing this commit's integration tests: persisting an
-- item with a catalog-picker-sourced product_id 23503s against the wrong FK
-- unless sales.catalog coincidentally has a row with the same id. It has no
-- prior test coverage (grep confirms zero existing tests reference
-- price_catalog.*, catalog_price_id, or snapshotCatalogSelections), so it was
-- never caught. Finding A's new submit()-time catalog-completeness gate is
-- meaningless if the column it depends on can never actually be persisted in
-- a real deployment, so the FK is corrected here as a required part of this
-- fix, not a side effect — repointed to the table the picker and the
-- snapshot join actually use.
ALTER TABLE sales.pricing_request_item
    DROP CONSTRAINT pricing_request_item_product_id_fkey;

ALTER TABLE sales.pricing_request_item
    ADD CONSTRAINT fk_pricing_request_item_product
        FOREIGN KEY (product_id) REFERENCES price_catalog.product_prices(price_id) ON DELETE SET NULL;

-- ─────────────────────────────────────────────────────────────────────────
-- Finding B: sales.pricing_request_item.requested_unit (V59, VARCHAR(30)) is
-- free text typed by Sales (แผ่น / ตร.ม. / ...), with no machine-readable
-- basis. PricingCostingService.calculate() multiplied a per-piece landed cost
-- by requested_qty without ever checking requested_qty was actually expressed
-- in pieces — for a PER_BOX factory quote, requested_qty could be a box count
-- that should have been converted to pieces first (review worked example:
-- 1,000 THB/box, 20 pieces/box, 10 boxes requested -> the pre-fix code could
-- compute 1000/20*10 = 500 instead of 1000*10 = 10,000). requested_unit_basis
-- adds the missing machine-readable basis alongside the existing free-text
-- label (requested_unit is kept as-is — it still drives the factory email
-- body / display).
ALTER TABLE sales.pricing_request_item
    ADD COLUMN requested_unit_basis VARCHAR(30);

-- Backfill: reuses the exact free-text -> canonical mapping V63 already
-- established for factory quote / costing item units, defaulting anything
-- unmapped to PER_PIECE. Best-effort only, and safe to be best-effort: Step 2
-- (factory quote / costing — see docs/agent-handoffs/
-- 88_feat-sales-factory-quote-costing.md, "Step 1 is not independently
-- deployable") has never been deployed to any real environment, so there are
-- no production sales.pricing_request_item rows for this backfill to affect.
-- It exists purely so a migration replay of this branch's own dev/test data
-- lands on a sensible default instead of leaving the new NOT NULL column
-- unsatisfiable.
UPDATE sales.pricing_request_item
   SET requested_unit_basis = CASE
       WHEN LOWER(requested_unit) IN ('sqm', 'sq.m', 'm2', 'm²', 'per_sqm', 'ตร.ม.') THEN 'PER_SQM'
       WHEN LOWER(requested_unit) IN ('box', 'per_box', 'กล่อง') THEN 'PER_BOX'
       WHEN LOWER(requested_unit) IN ('linear_m', 'linear_meter', 'meter', 'metre', 'm', 'per_linear_m', 'เมตร') THEN 'PER_LINEAR_M'
       ELSE 'PER_PIECE'
   END
 WHERE requested_unit_basis IS NULL;

ALTER TABLE sales.pricing_request_item
    ALTER COLUMN requested_unit_basis SET NOT NULL,
    ADD CONSTRAINT chk_pricing_request_item_unit_basis CHECK (
        requested_unit_basis IN ('PER_SQM', 'PER_PIECE', 'PER_BOX', 'PER_LINEAR_M')
    );

-- Finding B continued: factory_quote_item already has sqm_per_unit and
-- pieces_per_box conversion factors (V61) but nothing for PER_LINEAR_M —
-- which is exactly why PricingCostingService's PER_LINEAR_M branch has always
-- unconditionally thrown 422 rather than actually converting. Add the missing
-- factor (linear metres per physical piece) so PER_LINEAR_M can be priced /
-- normalized the same way PER_SQM and PER_BOX already are, instead of being a
-- blanket "not implemented" throw.
ALTER TABLE sales.factory_quote_item
    ADD COLUMN linear_m_per_unit NUMERIC(10,6);

-- Auditability: persist the normalization inputs/outputs on each costing line
-- so a reviewer can see which basis the requested quantity was normalized
-- from, how many pieces it normalized to, and which linear-metre factor (if
-- any) was applied — mirrors the existing sqm_per_unit/pieces_per_box audit
-- columns already on this table (V61). Nullable like those existing columns:
-- this is audit data describing a calculation that already happened, not a
-- new invariant to enforce at the database layer.
ALTER TABLE sales.pricing_costing_item
    ADD COLUMN requested_unit_basis VARCHAR(30),
    ADD COLUMN normalized_quantity_pieces NUMERIC(18,6),
    ADD COLUMN linear_m_per_unit NUMERIC(10,6);
