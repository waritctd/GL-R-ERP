-- Step 8 of the sales pricing-flow redesign: Receiving, Inventory Allocation, and Delivery.
--
-- MIGRATION NUMBERING: this is V78. Re-checked live via `git worktree list` plus listing every
-- worktree's own backend/src/main/resources/db/migration directory immediately before writing
-- this file:
--   - This branch (feat/inventory-delivery-fulfilment, stacked on Step 7 tip a749b29) tops out
--     at V77__factory_purchase_order.sql.
--   - .claude/worktrees/procurement-order (feat/procurement-factory-order, the SAME Step 7 tip
--     a749b29 this branch is stacked on) also tops out at V77 — not a collision, same commit.
--   - Every other open worktree/checkout (top-level GL-R-ERP [feat/sales-factory-quote-costing,
--     tops at V71], GL-R-ERP-employees, GL-R-ERP-main, .claude/worktrees/flyway-audit,
--     .claude/worktrees/profile-avatar-menu [all top at V54], .claude/worktrees/nav-menu-grouping
--     [tops at V55], .claude/worktrees/deposit-order [tops at V76],
--     .claude/worktrees/quotation-outcome [tops at V75]) has nothing at or above V78.
-- V78 is free everywhere checked. Re-verify again before merging if time has passed.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────
-- Part 1: Receiving — Step 7's factory_purchase_order_item already records the ordered
-- quantity/price (frozen from the approved costing); this adds the ACTUAL quantity Import
-- physically counted on receipt plus a QC/damage note, so a real discrepancy (qty_received -
-- quantity) can be reported per line instead of only a single PO-level "received" flag flip.
-- Nullable until recordGoodsReceived is actually called for this line (see
-- ProcurementService#recordGoodsReceived's own Javadoc for the default-fill-from-ordered-qty
-- backward-compatible behavior when the caller supplies no per-item detail).

ALTER TABLE sales.factory_purchase_order_item
    ADD COLUMN qty_received NUMERIC(18,4)
        CONSTRAINT chk_factory_po_item_qty_received CHECK (qty_received IS NULL OR qty_received >= 0),
    ADD COLUMN qc_note TEXT;

-- ─────────────────────────────────────────────────────────────────────────────────────────
-- Part 2: Delivery note / customer delivery confirmation — sales.delivery_record already
-- carries a free-text `note` (proof-of-delivery narrative) and `delivered_by`/`delivered_at`
-- (our own staff, joined from hr.employee); the one genuinely missing field is who on the
-- CUSTOMER's side received/confirmed the goods. Deliberately just one nullable column, not a
-- new document-rendering pipeline — see TicketService#recordDeliveryInternal's own Javadoc for
-- why no DepositNoticeRenderer-style PDF was built for this ("do not over-build" instruction).

ALTER TABLE sales.delivery_record
    ADD COLUMN recipient_name VARCHAR(255);

-- ─────────────────────────────────────────────────────────────────────────────────────────
-- Part 3: pre-existing bug fix, found by this step's own real-Postgres acceptance test, NOT a
-- Step 8 feature. V54 added STOCK_RESERVED/DELIVERY_RECORDED/DELIVERY_COMPLETED to
-- sales.ticket_event.chk_event_kind so TicketService.reserveStock/recordDeliveryInternal could
-- log events. V56's own "full re-declaration to widen event kinds" (following V39/V48/V50/V51/
-- V52/V53's own precedent, per that migration's comment) accidentally DROPPED those three values
-- instead of carrying them forward — its own CHECK list simply omits them, and V76's later
-- re-declaration (Step 6) inherited that same list, so the omission has silently persisted since
-- V56 first shipped. th.co.glr.hr.ticket.TicketEventKind.java's own comments ("chk_event_kind was
-- extended for this in V54") are misleadingly still describing intent, not the DB's actual
-- current state, which is why this went unnoticed: no prior step's own integration test ever
-- exercised reserveStock/recordDeliveryInternal against a real Postgres database (TicketServiceTest
-- is Mockito-only — see that class's own header) until
-- InventoryDeliveryFulfilmentIntegrationTest's fullChain_reserveStockAndCompleteDelivery... test,
-- which failed on real Postgres with "new row for relation ticket_event violates check constraint
-- chk_event_kind" the first time it ran, confirming the bug was real, not hypothetical.
--
-- Forward-only fix: re-declare chk_event_kind ONE more time, restoring the three missing values
-- and matching TicketEventKind.java's full current constant list exactly. Never edit V54/V56/V76
-- in place.

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
    'ORDER_CONFIRMED_FROM_QUOTATION'
));
