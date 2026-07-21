-- Step 6 of the sales pricing-flow redesign: Deposit, Payment, and Order Confirmation.
--
-- MIGRATION NUMBERING: this is V76. Re-checked live via `git worktree list --porcelain` plus
-- listing every worktree's own backend/src/main/resources/db/migration directory immediately
-- before writing this file:
--   - This branch (feat/sales-deposit-order-confirmation, stacked on Step 5 tip 38a0afd) tops
--     out at V75__quotation_customer_outcome.sql.
--   - .claude/worktrees/quotation-outcome (feat/sales-quotation-outcome, the SAME Step 5 tip
--     38a0afd this branch is stacked on) also tops out at V75 — not a collision, same commit.
--   - Every other open worktree/checkout (top-level GL-R-ERP [feat/sales-factory-quote-costing,
--     tops at V71], GL-R-ERP-employees, GL-R-ERP-main, .claude/worktrees/flyway-audit,
--     .claude/worktrees/profile-avatar-menu [all top at V54], .claude/worktrees/nav-menu-grouping
--     [tops at V55]) has nothing at or above V72.
-- V76 is free everywhere checked. Re-verify again before merging if time has passed — prior
-- handoffs on this chain repeatedly note worktree numbers move between checks.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────
-- Purpose: this is the ONE deliberate bridge write in the whole 6-step chain — see
-- OrderConfirmationService's own class Javadoc for the full reasoning. In short: Step 1 made
-- TicketService.submit() permanently 409, so a deal driven entirely through the new
-- PricingRequest -> PricingDecision -> CustomerQuotation chain can never move
-- sales.ticket.status off 'draft' via the legacy state machine. But a large, working,
-- already-tested payment/deposit/fulfilment pipeline (TicketService.confirmCustomer/
-- confirmDepositPaid/issueImportRequest, DepositNoticeService) is keyed on exactly that legacy
-- status reaching 'quotation_issued'. Rather than rebuild that pipeline for the new chain, this
-- migration adds the columns OrderConfirmationService needs to bridge into it exactly once, per
-- pricing request, once that pricing request's customer quotation reaches QUOTATION_ACCEPTED
-- (Step 5's terminal status).
--
-- No backfill anywhere in this migration — every pre-existing row keeps its current status/NULL
-- new columns and behaves exactly as before (additive only).

-- ── sales.pricing_request: order-confirmation idempotency + audit ───────────────────────
-- order_confirmed_at doubles as the "has OrderConfirmationService.confirmOrder already run for
-- this pricing request" flag: QUOTATION_ACCEPTED is terminal (PricingRequestStatus.ALLOWED maps
-- it to {}), so status itself can never signal "already bridged" the way every other step's own
-- status transition does. A guarded UPDATE (SET ... WHERE status = 'QUOTATION_ACCEPTED' AND
-- order_confirmed_at IS NULL) is the compare-and-set; order_confirm_client_request_id is the
-- replay key, mirroring sales.quotation's own client_request_id/issue_client_request_id/
-- outcome_client_request_id split (V74/V75) — a retry with the SAME key replays (returns the
-- current state without re-running the bridge write); a retry with a different/no key against an
-- already-confirmed row is a clean 409.
ALTER TABLE sales.pricing_request
    ADD COLUMN order_confirmed_at               TIMESTAMPTZ,
    ADD COLUMN order_confirmed_by                BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    ADD COLUMN order_confirm_client_request_id   UUID;

-- ── sales.ticket_event: the one new event kind for the bridge's own ticket.status write ──
-- Full re-declaration, following V50/V52/V53/V54/V56's own precedent for widening this CHECK
-- (every pre-existing value preserved).
ALTER TABLE sales.ticket_event DROP CONSTRAINT IF EXISTS chk_event_kind;
ALTER TABLE sales.ticket_event ADD CONSTRAINT chk_event_kind CHECK (kind IN (
    'CREATED','SUBMITTED','PICKED_UP','PRICE_PROPOSED','APPROVED','REJECTED',
    'QUOTATION_ISSUED','COMMENTED','CLOSED','CANCELLED','EDITED',
    'DOCUMENT_ISSUED','REVISION_REQUESTED','PRICE_REVISED',
    'CUSTOMER_CONFIRMED','DEPOSIT_NOTICE_ISSUED','DEPOSIT_PAID',
    'IR_ISSUED','IR_SENT','SHIPPING','GOODS_RECEIVED',
    'AWAITING_FINAL_PAYMENT','FULLY_PAID','PRICE_OVERRIDDEN',
    'STAGE_CHANGED','MARKED_LOST','REOPENED',
    'ON_HOLD','DORMANT','RESUMED','POLICY_CHANGED',
    'QUOTATION_SENT','QUOTATION_ACCEPTED','QUOTATION_REJECTED',
    'PAYMENT_RECORDED','BILLING_UPDATED',
    'CLOSE_CONFIRMED','CLOSE_CONFIRM_REVOKED',
    'ORDER_CONFIRMED_FROM_QUOTATION'
));
