-- Commission redesign Slice A1.
--
-- The real commission policy Excel has two more columns than sales.invoice_details previously
-- modeled: หัก ณ ที่จ่าย (withholding tax, subtracted from actualReceived) and รับเงินเกิน
-- (overpayment received, added back). This migration only adds storage for them; the calculator
-- change (CommissionCalculator#calculateInvoice) lands alongside it in the same PR. Both columns
-- default to 0 so every existing row, and any submission that omits them, is byte-for-byte
-- unaffected until someone actually fills them in.
--
-- Forward-only: never edit an already-applied migration in place (CLAUDE.md).
ALTER TABLE sales.invoice_details
    ADD COLUMN withholding_tax NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (withholding_tax >= 0),
    ADD COLUMN overpayment     NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (overpayment >= 0);
