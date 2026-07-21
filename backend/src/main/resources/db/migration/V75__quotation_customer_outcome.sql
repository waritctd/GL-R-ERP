-- Step 5 of the sales pricing-flow redesign: Customer Decision and Commercial Revisions.
--
-- MIGRATION NUMBERING: this is V75. Re-checked live via `git worktree list --porcelain` plus
-- listing every worktree's own backend/src/main/resources/db/migration directory immediately
-- before writing this file:
--   - This branch's own tree (feat/sales-quotation-outcome, branched from main tip f07f487,
--     "Steps 1-4" already merged) tops out at V74__customer_quotation_from_decision.sql.
--   - Every other open worktree (top-level GL-R-ERP [feat/sales-factory-quote-costing, tops at
--     V71], GL-R-ERP-employees, GL-R-ERP-main, .claude/worktrees/flyway-audit,
--     .claude/worktrees/profile-avatar-menu [all three top at V54],
--     .claude/worktrees/nav-menu-grouping [tops at V55]) has nothing at or above V75.
-- V75 is free everywhere checked. Re-verify again before merging if time has passed — prior
-- handoffs on this chain repeatedly note worktree numbers move between checks.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────
-- Purpose: sales.quotation.doc_status already declares the full customer-outcome lifecycle
-- (ISSUED, SENT, SUPERSEDED, CANCELLED, EXPIRED, ACCEPTED, REJECTED, REVISION_REQUESTED — V52 +
-- V74's own CHECK). This migration adds the two schema-level gaps Step 5's service layer needs
-- to actually drive that lifecycle, per the task brief's "design corrections":
--
-- 1. Design correction 1 (the cascade gap): sales.pricing_decision.status has no
--    terminal-by-supersession value (DRAFT/APPROVED/RETURNED only, V72's CHECK). Widened here to
--    add SUPERSEDED, following V74's own precedent for widening a CHECK additively (DROP + ADD,
--    every pre-existing value preserved). CustomerQuotationRepository/PricingRequestRepository's
--    new supersede-cascade (called from PricingRequestService.createCustomerChangeRevision,
--    right alongside the existing cancelOpenStep2Children cascade) sets an APPROVED/DRAFT
--    decision and any non-terminal quotation to SUPERSEDED so neither reads as current once a
--    customer-change (cost-affecting) revision supersedes their parent pricing request.
--
-- 2. Design correction 2 (QUOTATION_ACCEPTED): sales.pricing_request.status stops at
--    QUOTATION_ISSUED today (V74's own comment: "a cancelled/superseded quotation does not
--    currently roll the pricing request back"). Widened here to add QUOTATION_ACCEPTED so
--    recordOutcome's ACCEPTED path can transition QUOTATION_ISSUED -> QUOTATION_ACCEPTED via
--    PricingRequestRepository.transition (PricingRequestStatus.canTransition stays the
--    transition authority — this CHECK only governs valid values). Deliberately NOT adding a
--    QUOTATION_REJECTED pricing-request status — REJECTED lives entirely on
--    quotation.doc_status; Sales decides what happens next (a new revision, or a separate
--    ticket-level lost-deal action outside this step's scope). Same for EXPIRED.
--
-- 3. sales.quotation gains its own outcome-recording columns (customer note + who/when + a
--    THIRD idempotency key, separate from client_request_id/issue_client_request_id, mirroring
--    that same create/issue idempotency-key-per-action pattern V74 already established).
--
-- No backfill anywhere in this migration — every pre-existing row keeps its current
-- status/doc_status/NULL outcome columns and behaves exactly as before (additive only).

-- ── 1. sales.pricing_decision: SUPERSEDED (design correction 1) ─────────────────────────
ALTER TABLE sales.pricing_decision
    DROP CONSTRAINT chk_pricing_decision_status;
ALTER TABLE sales.pricing_decision
    ADD CONSTRAINT chk_pricing_decision_status CHECK (status IN ('DRAFT', 'APPROVED', 'RETURNED', 'SUPERSEDED'));

-- ── 2. sales.pricing_request: QUOTATION_ACCEPTED (design correction 2) ──────────────────
ALTER TABLE sales.pricing_request
    DROP CONSTRAINT chk_pricing_request_status;
ALTER TABLE sales.pricing_request
    ADD CONSTRAINT chk_pricing_request_status CHECK (status IN (
        'DRAFT', 'SUBMITTED', 'IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS',
        'READY_FOR_CEO_REVIEW', 'CEO_REVIEWING', 'APPROVED_FOR_QUOTATION', 'COSTING_REVISION_REQUIRED',
        'QUOTATION_ISSUED', 'QUOTATION_ACCEPTED', 'MORE_INFO_REQUIRED', 'CANCELLED', 'SUPERSEDED'
    ));

-- ── 3. sales.quotation: outcome-recording columns ────────────────────────────────────────
-- outcome_note is deliberately separate from the pre-existing customer_notes column (V74):
-- customer_notes is Sales-editable, customer-facing free text written INTO the document before
-- issue; outcome_note is what the customer said back, recorded by Sales AFTER issue via
-- recordOutcome — different author, different timing, different lifecycle, so a fresh column
-- rather than repurposing one a prior reader may still expect to mean "terms written by Sales".
ALTER TABLE sales.quotation
    ADD COLUMN outcome_note              TEXT,
    ADD COLUMN outcome_recorded_by       BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    ADD COLUMN outcome_recorded_at       TIMESTAMPTZ,
    -- recordOutcome's own idempotency key, a THIRD key on this row alongside client_request_id
    -- (create) and issue_client_request_id (issue) — mirrors PricingDecisionRepository's
    -- client_request_id/approve_client_request_id split (Step 3) and this same table's own
    -- client_request_id/issue_client_request_id split (Step 4, V74). A retry with the SAME key
    -- replays the existing outcome; a retry with a different/no key against an already-recorded
    -- outcome is a clean 409, not a silent second "success".
    ADD COLUMN outcome_client_request_id UUID;

-- Scoped by (issued_by, outcome_client_request_id) — mirrors uq_quotation_issuer_issue_client_request's
-- own scoping exactly. Only the owning sales rep may ever call recordOutcome (owner-scoped per
-- CustomerQuotationService.requireEditAccess), so issued_by is always that same actor's id.
CREATE UNIQUE INDEX uq_quotation_issuer_outcome_client_request
    ON sales.quotation(issued_by, outcome_client_request_id) WHERE outcome_client_request_id IS NOT NULL;
