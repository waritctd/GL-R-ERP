-- Step 4 of the sales pricing-flow redesign: Customer Quotation Generation and Issuance.
--
-- MIGRATION NUMBERING: this is V74. Re-checked live via `git worktree list --porcelain` plus
-- listing every worktree's own backend/src/main/resources/db/migration directory immediately
-- before writing this file:
--   - This branch's own tree tops out at V72__pricing_decision.sql (Step 3, inherited at branch
--     base commit e90cdad, uncommitted in this worktree's working copy).
--   - .claude/worktrees/ceo-pricing (feat/sales-ceo-pricing-decision, same base) also tops out
--     at V72 — the sibling Step 3 worktree, not a collision (same file, same content).
--   - .claude/worktrees/payroll-recon (feat/payroll-reconciliation) has an untracked
--     V73__payroll_reconciliation_inputs.sql.
--   - .claude/worktrees/ot-retroactive (feat/ot-remove-advance-notice) has an untracked
--     V70__overtime_salary_basis_snapshot.sql.
--   - .claude/worktrees/special-money (feat/special-money-requests) has an untracked
--     V66__special_money_request_schema.sql.
--   - No other open worktree (top-level GL-R-ERP, GL-R-ERP-employees, GL-R-ERP-main,
--     flyway-audit, nav-menu-grouping, profile-avatar-menu) has any migration file above V71.
-- V74 is free everywhere checked (V73 is taken by payroll-recon). The production V55 divergence
-- (prod had V55 = "quotation doc terms" from the unmerged feat/doc-gen-real-templates) is now
-- RESOLVED on this line: that migration was adopted byte-identical as V55 and this chain's own
-- close_verification..product_description_idempotency were shifted V55-V59 -> V56-V60, so prod
-- correctly skips the already-applied V55 and applies V56+ forward. See
-- docs/flyway-version-collision-audit / docs/agent-handoffs/88_feat-sales-factory-quote-costing.md §8.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────
-- Owner's decision: EXTEND sales.quotation / sales.quotation_item — do NOT create a parallel
-- sales.customer_quotation. One quotation number space, one renderer (QuotationRenderer is
-- reused as-is, not modified — see CustomerQuotationService's render adapter), one deal-stage
-- transition (TicketService.advanceStageForCustomerQuotationIssue, which calls the exact same
-- private autoAdvanceStage() TicketService.generateQuotation already used).
--
-- The spine: a Step 4 quotation sources its prices from the CURRENT APPROVED
-- sales.pricing_decision (never from sales.ticket_item — Step 3 deliberately never writes
-- ticket_item price columns, see docs/agent-handoffs/92_feat-sales-ceo-pricing-decision.md),
-- snapshotting them into immutable sales.quotation_item rows at creation. The existing
-- rendering columns (brand/qty/raw_unit/unit_price — read by
-- TicketRepository.findQuotationItemsByQuotationId into TicketItemDto) are reused verbatim
-- for PDF/XLSX rendering; the columns below are Step 4's own business-logic columns
-- (unit-basis-explicit, per design corrections 1/2/6 of this same chain) sitting alongside
-- them on the SAME row.

-- New pricing_request status: QUOTATION_ISSUED. Terminal for Step 4's first cut (a cancelled/
-- superseded quotation does not currently roll the pricing request back — see PricingRequestStatus
-- javadoc). Reachable only from APPROVED_FOR_QUOTATION (PricingRequestStatus.ALLOWED is the
-- transition authority, not this CHECK — this CHECK only governs valid values).
ALTER TABLE sales.pricing_request
    DROP CONSTRAINT chk_pricing_request_status;
ALTER TABLE sales.pricing_request
    ADD CONSTRAINT chk_pricing_request_status CHECK (status IN (
        'DRAFT', 'SUBMITTED', 'IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS',
        'READY_FOR_CEO_REVIEW', 'CEO_REVIEWING', 'APPROVED_FOR_QUOTATION', 'COSTING_REVISION_REQUIRED',
        'QUOTATION_ISSUED', 'MORE_INFO_REQUIRED', 'CANCELLED', 'SUPERSEDED'
    ));

-- ── sales.quotation: link to the PricingRequest/PricingDecision aggregate + revision chain ──
-- parent_quotation_id already exists (V52) — reused for Step 4's revision chain too (a
-- correction after issue creates a new quotation row with parent_quotation_id pointing at the
-- prior revision; the prior revision's own row is never mutated, only its doc_status moves to
-- SUPERSEDED). quotation_revision_no is a NEW counter, scoped to the pricing_request (not to
-- ticket+recipient like the legacy quotation_version column, which Step 4 still populates
-- correctly to satisfy ux_quotation_ticket_recipient_version, but which is otherwise vestigial
-- for Step 4's own semantics).
ALTER TABLE sales.quotation
    ADD COLUMN pricing_request_id      BIGINT REFERENCES sales.pricing_request(pricing_request_id) ON DELETE RESTRICT,
    ADD COLUMN pricing_decision_id     BIGINT REFERENCES sales.pricing_decision(pricing_decision_id) ON DELETE RESTRICT,
    ADD COLUMN quotation_revision_no   INT NOT NULL DEFAULT 1,
    -- Create-draft idempotency (mirrors sales.pricing_decision.client_request_id).
    ADD COLUMN client_request_id       UUID,
    -- Issue idempotency, a SEPARATE key from client_request_id (mirrors
    -- sales.pricing_decision.approve_client_request_id) — a retry of the create call and a
    -- retry of the issue call must not collide on the same idempotency key.
    ADD COLUMN issue_client_request_id UUID,
    -- Sales-editable, customer-facing free text for the whole document (separate from the
    -- pre-existing, currently-unused `notes TEXT[]` column added in V27 — different shape,
    -- different semantics, left untouched to avoid repurposing a column under a reader who may
    -- still expect its original meaning).
    ADD COLUMN customer_notes          TEXT;

CREATE INDEX idx_quotation_pricing_request ON sales.quotation(pricing_request_id)
    WHERE pricing_request_id IS NOT NULL;

CREATE UNIQUE INDEX uq_quotation_creator_client_request
    ON sales.quotation(issued_by, client_request_id) WHERE client_request_id IS NOT NULL;

CREATE UNIQUE INDEX uq_quotation_issuer_issue_client_request
    ON sales.quotation(issued_by, issue_client_request_id) WHERE issue_client_request_id IS NOT NULL;

-- Full re-declaration to add the Step 4 lifecycle states, following V52's own precedent.
-- READY_TO_ISSUE and REVISION_REQUESTED are new; every pre-existing value (including SENT,
-- which the legacy ticket-item-driven flow still uses and this migration does not touch) is
-- preserved so existing rows/behaviour are unaffected. ACCEPTED/REJECTED/REVISION_REQUESTED are
-- declared now (Step 5 completes the customer-response half of the lifecycle) to avoid a second
-- CHECK-widening migration for what is otherwise a one-line addition.
ALTER TABLE sales.quotation DROP CONSTRAINT IF EXISTS chk_quotation_doc_status;
ALTER TABLE sales.quotation ADD CONSTRAINT chk_quotation_doc_status CHECK (doc_status IN (
    'DRAFT','READY_TO_ISSUE','ISSUED','SENT','SUPERSEDED','CANCELLED','EXPIRED',
    'ACCEPTED','REJECTED','REVISION_REQUESTED'
));

-- ── sales.quotation_item: Step 4's own unit-basis-explicit snapshot columns ──────────────
-- Sit alongside the pre-existing rendering columns (brand/model/color/texture/size/qty/
-- qty_sqm/unit_basis/raw_unit/unit_price/amount) on the SAME row — a Step 4 quotation_item
-- populates BOTH sets (the legacy set so QuotationRenderer keeps working unmodified; the set
-- below for Step 4's own business logic/API responses), never a parallel table.
--
-- requested_unit_basis is VARCHAR(30) (not the legacy unit_basis's VARCHAR(10)) because it
-- stores UnitBasis's canonical codes verbatim ('PER_LINEAR_M' is 12 characters — would not fit
-- the legacy column).
ALTER TABLE sales.quotation_item
    ADD COLUMN pricing_request_item_id  BIGINT REFERENCES sales.pricing_request_item(pricing_request_item_id) ON DELETE RESTRICT,
    ADD COLUMN pricing_decision_item_id BIGINT REFERENCES sales.pricing_decision_item(pricing_decision_item_id) ON DELETE RESTRICT,
    ADD COLUMN requested_unit_basis     VARCHAR(30),
    ADD COLUMN requested_quantity       NUMERIC(18,4),
    -- Frozen at creation from pricing_decision_item.approved_selling_price_per_requested_unit —
    -- never recomputed once the quotation item row exists (design correction 2/7's "no cost
    -- leak, no client-trusted price" precedent, applied here to the sales-facing price too).
    ADD COLUMN approved_unit_price      NUMERIC(18,4),
    -- Sales-entered discount, PER REQUESTED UNIT, in the decision's currency. Zero by default
    -- (no discount). Discount Policy B (controlled): final_unit_price must never fall below
    -- pricing_decision_item.minimum_selling_price_per_requested_unit — enforced in
    -- CustomerQuotationService, not by a cross-table CHECK (Postgres cannot express that here,
    -- same limitation Step 3's V72 already documents for its own currency-consistency rule).
    ADD COLUMN sales_discount          NUMERIC(18,4) NOT NULL DEFAULT 0,
    -- final_unit_price = approved_unit_price - sales_discount, server-recomputed on every
    -- mutation, never trusted from the client.
    ADD COLUMN final_unit_price        NUMERIC(18,4),
    -- line_subtotal = final_unit_price * requested_quantity — BOTH IN THE SAME REQUESTED-UNIT
    -- basis (the highest-financial-risk rule on this task; see the unitBasis_* test).
    ADD COLUMN line_subtotal           NUMERIC(14,2),
    ADD COLUMN vat                     NUMERIC(14,2),
    ADD COLUMN line_total              NUMERIC(14,2),
    -- Sales-editable customer-facing description (defaults from the pricing_decision item's
    -- brand/model/productDescription at creation, then freely editable pre-issue). Deliberately
    -- separate from the legacy brand/model/color/texture/size columns, which stay populated
    -- too (so the existing renderer's buildDesc() has something to read) but are NOT what Sales
    -- edits going forward for a Step 4 quotation.
    ADD COLUMN description             TEXT,
    ADD COLUMN item_notes              TEXT;

CREATE INDEX idx_quotation_item_pricing_decision_item ON sales.quotation_item(pricing_decision_item_id)
    WHERE pricing_decision_item_id IS NOT NULL;

-- No backfill: every pre-existing sales.quotation / sales.quotation_item row (legacy,
-- ticket-item-driven flow) has NULL for every new column above and keeps rendering/behaving
-- exactly as before — this migration is additive only, per "preserve existing rows/behaviour".
