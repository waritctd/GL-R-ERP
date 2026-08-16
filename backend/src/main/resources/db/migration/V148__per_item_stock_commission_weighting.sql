-- Per-item stock-commission weighting.
--
-- MIGRATION NUMBERING: this is V148. Verified free by listing this worktree's own
-- backend/src/main/resources/db/migration (tops out at V147__restore_employee_reference_foreign_keys.sql)
-- and re-confirmed by grepping every V-prefixed filename in this directory for a duplicate/gap
-- immediately before writing this file -- V148 is unused. As with every prior migration on this
-- repo, the true production-numbering conflict is tracked separately (docs/flyway-version-collision-audit)
-- and must be re-checked again before merge if time has passed or other worktrees have advanced.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Context / owner ruling this migration acts on
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- V82 (sales.commission_record.weight_multiplier) put the 2x/3x tier-base weighting at the
-- RECORD level -- one multiplier for a whole invoice's actual_received. The owner has asked for
-- PER-ITEM granularity: a single deal can mix stock-sourced lines (which may deserve 2x/3x) with
-- import-sourced lines (which must NEVER be weighted), and a record-level number cannot express
-- that split. V82's own comment additionally flagged that 3x was allowed by the CHECK constraint
-- but "has NOT been confirmed by the owner ... do not treat a 3x row as verified business policy
-- without owner sign-off". THE OWNER HAS NOW CONFIRMED 3x AS REAL POLICY (this task's brief). V82
-- is an already-applied migration and is never edited in place (CLAUDE.md) -- this comment block
-- is the forward-only correction of record; UpdateCommissionDeductionsRequest's Javadoc and
-- CommissionPage.jsx's "(ยังไม่ยืนยันนโยบาย)" qualifier are updated in this same branch's Java/JS
-- changes for the same reason.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 1. sales.ticket_item.weight_multiplier -- the manager-set INPUT, per item
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Same shape as V82's record-level column (SMALLINT NOT NULL DEFAULT 1, CHECK IN (1,2,3)) so a
-- brand-new ticket_item row (every row that existed before this migration included) defaults to
-- the same "no weighting" behaviour V82 shipped with. Set by the sales_manager (ceo may also set
-- it, mirroring the existing record-level setter's requireManagerOrCeo gate) through a new
-- POST /api/tickets/{id}/item-weight-multipliers endpoint -- see TicketService#setItemWeightMultipliers
-- and its own authorization Javadoc for why sales_manager is a NEW role set here rather than a
-- reuse of STOCK_DECLARATION_ROLES (qty_from_stock is declared by {sales-owner, import, ceo};
-- this multiplier is approved by {sales_manager, ceo} -- two different decisions, two gates).
ALTER TABLE sales.ticket_item
    ADD COLUMN weight_multiplier SMALLINT NOT NULL DEFAULT 1
        CHECK (weight_multiplier IN (1, 2, 3));

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 2. sales.commission_record.effective_weight_multiplier -- the FROZEN, blended OUTPUT
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Nullable NUMERIC (not SMALLINT like weight_multiplier -- a blended weight can be fractional,
-- e.g. 1.6, when only part of an item's quantity is stock-sourced). Computed ONCE, at commission
-- record creation time (CommissionService#submit / #createFromDeal), from that deal's ticket_item
-- rows as they stand at that moment -- exactly the same "snapshot, not a live join" discipline
-- deal_payable_amount_snapshot already uses two columns to the left of this one, and the same
-- precedent sales.pricing_decision_item (V72) set for freezing a computed value at a defined
-- workflow point instead of recomputing it live. NEVER recomputed afterwards by anything --
-- editing ticket_item months later (a different weight_multiplier, a corrected qty_from_stock)
-- must not move an already-created commission record's money.
--
-- NULL is the load-bearing default for BACKWARD COMPATIBILITY: every commission_record that
-- exists before this migration runs (including every already-APPROVED, already-paid one) gets
-- NULL here, and payroll reads COALESCE(effective_weight_multiplier, weight_multiplier) --
-- see CommissionRepository#sumActiveWeightedActualReceived and
-- CommissionService#computeRepPayrollCommissions. A NULL therefore falls straight back to the
-- existing plain weight_multiplier column, so no historical record's contribution to payroll can
-- change value because this migration ran. NULL also covers every future record this feature does
-- not apply to: an unlinked/manual commission (source_ticket_id IS NULL -- every MANUAL_KIND), a
-- ticket with no items, or a ticket whose items sum to zero item value (nothing to derive a
-- signal from) -- see CommissionCalculator#itemDerivedWeight's own Javadoc for the full list.
--
-- Bounds CHECK mirrors the fact that a weighted AVERAGE of per-item effective weights (each
-- itself bounded to [1, item's own 1/2/3]) can never legitimately fall outside [1, 3] -- the same
-- range weight_multiplier's own CHECK enforces. CommissionCalculator#itemDerivedWeight clamps its
-- result into this range defensively before ever returning it, so this CHECK should never actually
-- fire in application use; it exists as a backstop against a direct/malformed write, the same
-- belt-and-suspenders posture V82's own weight_multiplier CHECK already sets for this table.
ALTER TABLE sales.commission_record
    ADD COLUMN effective_weight_multiplier NUMERIC(9,6)
        CHECK (effective_weight_multiplier IS NULL
               OR (effective_weight_multiplier >= 1 AND effective_weight_multiplier <= 3));
