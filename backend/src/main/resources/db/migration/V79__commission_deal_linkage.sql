-- Step 9 (final payment / closeout / commission gate) of the sales pricing-flow redesign.
--
-- MIGRATION NUMBERING: this is V79. Verified via `ls backend/src/main/resources/db/migration`
-- on this branch (feat/final-payment-closeout-commission, stacked on origin/main tip fada2b5,
-- which includes Steps 1-8 and tops out at V78__inventory_delivery_receiving.sql) plus every
-- other open worktree under .claude/worktrees/ (flyway-audit, deposit-order, inventory-delivery,
-- nav-menu-grouping, procurement-order, quotation-outcome, profile-avatar-menu) — none has
-- anything at or above V79. V79 is free everywhere checked. Re-verify before merging if time
-- has passed.
--
-- Commission submission (sales.commission_record, CommissionService#submit) has always accepted
-- a fully manual, hand-typed invoice with an optional sourceTicketId FK that was never validated
-- or cross-checked against the deal it claims to be earned on. Step 9 adds a gate ("the linked
-- deal must have actually reached final payment, DealStage.CLOSED_PAID, before a commission can
-- be submitted against it") plus a non-blocking cross-check flag ("does the hand-typed
-- grossAmount roughly match what the deal was actually billed for"). Both new columns are
-- nullable/defaulted so every existing row, and any future commission submitted with
-- sourceTicketId = NULL (unlinked/manual — still fully supported), is unaffected.
ALTER TABLE sales.commission_record
    ADD COLUMN deal_payable_amount_snapshot NUMERIC(14,2),
    ADD COLUMN deal_amount_mismatch BOOLEAN NOT NULL DEFAULT FALSE;
