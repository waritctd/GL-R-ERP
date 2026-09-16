-- Owner-approved "sell loose pieces" (2026-09-16): a TILE line can be sold as fewer pieces than a
-- full box instead of always rounding up ("3 กล่อง + 2 แผ่น"). Sales still enters แผ่น/กล่อง as
-- they do today (rep-editable, catalogue-prefilled where available, required on a TILE row) --
-- this migration only adds the OPT-OUT switch for the box-rounding step that comes after it.
--
-- MIGRATION NUMBERING: a sibling worktree (feat/quotation-header-terms-rulings) owns V180/V181 for
-- the header/terms rework landing concurrently; this branch's own next free number is V182, one
-- above that reservation, so the two lanes can never collide when both merge.
--
-- Default TRUE preserves today's ONLY behaviour (round piece count UP to the next full box) for
-- every existing row and every new row that does not opt out -- see
-- th.co.glr.hr.dealquotation.WastageCalculator.Input#roundToFullBox for the arithmetic this
-- switches, and DealQuotationLines#calculationLine for the printed line it changes.
ALTER TABLE sales.quotation_item
    ADD COLUMN round_to_full_box BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN sales.quotation_item.round_to_full_box IS
    'true (default) rounds a TILE line''s piece count UP to the next pieces_per_box multiple, exactly as before V182; false sells the wastage-adjusted piece count unrounded, split into full boxes plus a loose-piece remainder. Meaningless on a PLAIN/ADJUSTMENT row (pieces_per_box is null there) and never false together with an English per-sqm price mode (see DealQuotationService#requireBoxDataForPerSqm).';
