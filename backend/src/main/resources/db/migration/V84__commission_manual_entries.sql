-- Manual commission entries (owner-authorized, feat/commission-manual-adjustments).
--
-- MIGRATION NUMBERING: V84. V80-83 are taken (wht/overpayment, tier13 rate fix, weight
-- multiplier, deal tracking/activity). Verified free via `ls db/migration` on this branch
-- immediately before writing.
--
-- Business need (owner-confirmed): sales_manager/CEO must be able to add a case-by-case
-- ADJUSTMENT to a rep's monthly commission (e.g. a takeover-credit split, or an interim
-- stock/incentive bonus applied by hand until those rules are automated) or the manager's own
-- team/MANAGER commission -- both entered as a hand-typed MANUAL amount, never computed by
-- CommissionCalculator. This is explicitly NOT an override of a SALE record's final amount
-- (Phase 1 deliberately has none) -- it is a separate commission_record KIND with no invoice
-- behind it.
--
-- invoice_id becomes nullable: manual entries (ADJUSTMENT/MANAGER) have no sales.invoice_details
-- row at all. Every pre-existing row is SALE/CLAWBACK with invoice_id already NOT NULL, so this
-- relaxation changes nothing for existing data.
ALTER TABLE sales.commission_record
    ALTER COLUMN invoice_id DROP NOT NULL;

ALTER TABLE sales.commission_record
    ADD COLUMN manual_amount NUMERIC(14,2),
    ADD COLUMN manual_reason TEXT;

ALTER TABLE sales.commission_record
    DROP CONSTRAINT chk_commission_kind;

ALTER TABLE sales.commission_record
    ADD CONSTRAINT chk_commission_kind CHECK (kind IN ('SALE','CLAWBACK','ADJUSTMENT','MANAGER','STOCK_BONUS','INCENTIVE'));

-- manual_amount/manual_reason are populated exactly for the manual kinds, and exactly those
-- kinds have no invoice; SALE/CLAWBACK are the inverse -- always invoiced, never manual.
ALTER TABLE sales.commission_record
    ADD CONSTRAINT chk_commission_manual_fields CHECK (
        (kind IN ('ADJUSTMENT','MANAGER','STOCK_BONUS','INCENTIVE')
            AND manual_amount IS NOT NULL
            AND manual_reason IS NOT NULL
            AND invoice_id IS NULL)
        OR
        (kind IN ('SALE','CLAWBACK')
            AND manual_amount IS NULL
            AND manual_reason IS NULL
            AND invoice_id IS NOT NULL)
    );
