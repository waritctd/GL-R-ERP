-- V176 — sales.quotation_item.sqm_per_box: the supplier-stated square metres in one box.
--
-- Owner decision 2026-09-13 ("Option A"): on an ENGLISH quotation priced per square metre
-- (price_mode SPECIAL_SQM on a document_language 'EN' quotation), the printed quantity is
--     qty (sqm) = boxes × sqm_per_box, rounded to 2 decimal places
-- exactly as her QN6900933 prints it: "(1 box = 28 pcs = 0.6 sqm)", 120 boxes → 72.00, and
-- "(1 box = 66 pcs = 0.495 sqm)", 114 boxes → 56.43. The figure is the SUPPLIER'S stated box
-- area, not width × height × pieces: 28 × 6.5 cm × 32.8 cm is 0.59696, which reproduces neither
-- sample at any single rounding. It is copied from price_catalog.product_prices.sqm_per_box when
-- the rep picks a catalogue row (never for a per_linear_m row, whose sqm_per_box holds linear
-- metres — see V153) and stays editable by the rep.
--
-- NUMERIC(10,6): the same precision this table already uses for sqm_per_piece (V165). Nullable,
-- no default and no backfill: every existing row reads NULL, which is correct — no quotation
-- could be priced per sqm in English before this change (DealQuotationService refused it), and
-- every other mode never reads this column. The service, not a CHECK, refuses a per-sqm English
-- row without a positive value, with a rep-facing message.
--
-- Forward-only and purely additive. V175 is reserved by feat/quotation-approval-snapshot.

ALTER TABLE sales.quotation_item ADD COLUMN sqm_per_box NUMERIC(10,6);

COMMENT ON COLUMN sales.quotation_item.sqm_per_box IS
    'Supplier-stated square metres per box (e.g. 0.6, 0.495). English per-sqm quotations print qty = boxes x sqm_per_box (2dp). Copied from price_catalog.product_prices.sqm_per_box on a catalogue pick (never for per_linear_m rows), rep-editable. NULL on every row before V176.';
