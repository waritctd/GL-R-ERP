-- GLA-136 (owner ruling, Ploy 2026-09-30): direct quotations and pipeline deals STAY SEPARATE.
--
-- A direct quotation (sales.quotation.origin = 'DEAL_DIRECT') is written at /quotations/new and
-- approved by sales_manager/CEO. It is QUOTATION-ONLY — not a pipeline deal. R10 stands: there is
-- no recordOutcome/confirmOrder for DEAL_DIRECT. But sales.quotation.ticket_id is NOT NULL, so
-- the editor's inline "new customer + project" path has always minted a full sales.ticket
-- (status draft, stage LEAD_APPROACH) just to hang the document off — a "ghost" deal that then
-- sat in every rep's deal list, dashboard and worklist forever, never advanced, and whose sticky
-- CTA told the rep to create a pricing request it never needed.
--
-- The ruling: the container ticket is marked quotation_only = TRUE. Pipeline reads (the deal
-- list/count, the dashboard ticket counts) skip it; pipeline writes (manual stage moves, item
-- edits, entry channel/tender) refuse it with a 409. Reading it by id still works — the quotation
-- editor reads the ticket summary. LATER, the rep clicks "สร้างดีลจากใบเสนอราคา" on an APPROVED
-- direct quotation, and DealQuotationService#promoteToDeal PROMOTES the same ticket (never a second
-- one) into the pipeline at S10 ORDER_RECEIVED: quotation_only -> FALSE, status draft ->
-- quotation_issued, ticket_item written from the quotation's lines, payment_status
-- CUSTOMER_CONFIRMED — the exact sequence OrderConfirmationService#confirmOrder already performs
-- for a pricing-request deal. From there the ordinary ticket-keyed steps (deposit notice, IR/stock,
-- delivery, close) work unchanged.
--
-- MIGRATION NUMBERING: V193. Verified free on origin/develop, origin/main and every remote branch
-- (`git ls-tree` over each) immediately before writing this file; develop tops out at V192.
--
-- RE-RUNNABLE ON PURPOSE: every statement below is idempotent (ADD COLUMN IF NOT EXISTS, COMMENT
-- ON, a guarded UPDATE, DROP CONSTRAINT IF EXISTS + ADD), so the backfill integration test
-- (DealQuotationPromoteIntegrationTest#v193Backfill_*) can execute THIS FILE verbatim against an
-- already-migrated schema — the same technique DealQuotationApproverSnapshotIntegrationTest uses
-- for V175 — and a regression in the backfill predicate turns that test red.

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 1. The flag.
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- NOT NULL DEFAULT FALSE: every ticket that exists before this runs is a pipeline deal unless the
-- backfill below proves otherwise, and every ticket created afterwards is a pipeline deal unless
-- its creator says so (CreateTicketRequest.quotationOnly — only the quotation editor's inline
-- create sends TRUE).
--
-- No index. The only predicate that reads this column is `quotation_only = FALSE` on the deal
-- list/count and the dashboard, which matches nearly every row, so a b-tree on it would never be
-- chosen; a partial index on the TRUE side would serve no query that exists.
ALTER TABLE sales.ticket
    ADD COLUMN IF NOT EXISTS quotation_only BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN sales.ticket.quotation_only IS
    'GLA-136 (owner ruling 2026-09-30): TRUE = this ticket exists only as the container of a direct (DEAL_DIRECT) quotation written at /quotations/new; it is NOT a pipeline deal. Excluded from the deal list/count and dashboard ticket counts; manual pipeline writes (stage, items, entry channel, tender) are refused. Readable by id. Set FALSE (never deleted) when the rep promotes an APPROVED direct quotation into the pipeline (DealQuotationService#promoteToDeal), which lands the deal at ORDER_RECEIVED.';

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 2. Backfill — the ghosts that already exist.
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- A ticket is marked quotation-only exactly when ALL of these hold:
--   * it carries at least one DEAL_DIRECT quotation;
--   * it carries NO quotation of any other origin — PRICING_REQUEST, or legacy (origin IS NULL).
--     `IS DISTINCT FROM` covers both in one predicate (a plain `<>` would skip the NULLs);
--   * it has no sales.pricing_request row at all (any status — a cancelled PR still means the rep
--     treated this as a pipeline deal once);
--   * it has never left the pipeline's starting point: status 'draft' AND stage 'LEAD_APPROACH'.
-- Per the GLA-136 investigation against local DB copies, 9/9 direct-only deals there match this
-- shape and no deal has ever mixed origins. A ticket that fails any clause stays a pipeline deal,
-- untouched.
-- `AND NOT t.quotation_only` keeps a re-run from rewriting rows it already marked.
UPDATE sales.ticket t
   SET quotation_only = TRUE
 WHERE NOT t.quotation_only
   AND t.status = 'draft'
   AND t.sales_stage = 'LEAD_APPROACH'
   AND EXISTS (
         SELECT 1 FROM sales.quotation q
          WHERE q.ticket_id = t.ticket_id
            AND q.origin = 'DEAL_DIRECT')
   AND NOT EXISTS (
         SELECT 1 FROM sales.quotation q
          WHERE q.ticket_id = t.ticket_id
            AND q.origin IS DISTINCT FROM 'DEAL_DIRECT')
   AND NOT EXISTS (
         SELECT 1 FROM sales.pricing_request pr
          WHERE pr.ticket_id = t.ticket_id);

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 3. chk_event_kind — add DEAL_PROMOTED_FROM_QUOTATION.
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- The ticket_event kind DealQuotationService#promoteToDeal writes (draft -> quotation_issued,
-- related document = the promoted QUOTATION). Deliberately NOT a reuse of
-- ORDER_CONFIRMED_FROM_QUOTATION: that kind means "the customer accepted a pricing-request
-- quotation and the rep confirmed the order" (OrderConfirmationService), and DealHistoryPanel.jsx
-- would then describe a promotion as something it is not.
--
-- Re-declared in full, following V39/V48/V50/V51/V52/V53/V54/V56/V76/V78/V184/V186's precedent.
-- The list below is V186's list (the latest re-declaration — V188/V189/V191/V192 do not touch this
-- constraint) plus the one new value, so nothing V184/V186 added is dropped. Never edit an earlier
-- re-declaration in place.
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
    'STOCK_RESERVED','DELIVERY_RECORDED','DELIVERY_COMPLETED',
    'CLOSE_CONFIRMED','CLOSE_CONFIRM_REVOKED',
    'ORDER_CONFIRMED_FROM_QUOTATION',
    'DEAL_QUOTATION_REORDERED',
    'DEAL_QUOTATION_SUPERSEDED',
    'IMPORT_STEP_ADVANCED',
    'IMPORT_REQUEST_EMAIL_SENT',
    'DEAL_PROMOTED_FROM_QUOTATION'
));
