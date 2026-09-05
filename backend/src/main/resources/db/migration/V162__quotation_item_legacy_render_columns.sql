-- Fix: "สร้างใบเสนอราคาแล้ว รายละเอียดสินค้าไม่ขึ้น ทั้ง excel และ pdf" — a Step 4
-- (Customer Quotation Generation and Issuance) quotation's item description printed as the bare
-- word "กระเบื้อง" with no รุ่น/สี/ขนาด/พื้นผิว, in both XLSX and PDF (QuotationRenderer.toPdf is
-- LibreOfficePdfConverter.convert(toXls(...)), so one root cause broke both).
--
-- Root cause was entirely in the Java write path, fixed alongside this migration:
--   - PricingDecisionRepository#findApprovedSalesView's SELECT dropped pri.color/pri.texture/
--     pri.size (kept only brand/model).
--   - CustomerQuotationService#buildItem and CustomerQuotationRepository's NewItem/insertItems
--     never carried model/color/texture/size through to the INSERT at all (model wasn't even a
--     field), so every Step 4 row got these four columns NULL from day one.
-- QuotationRenderer.buildDesc(TicketItemDto) (unmodified — it was never the bug) appends " รุ่น
-- "/" สี "/" ขนาด "/texture only when non-blank, so NULL columns silently rendered nothing
-- rather than erroring. V74__customer_quotation_from_decision.sql already documents the
-- INTENDED contract in its own comment on this table ("the legacy brand/model/color/texture/
-- size columns, which stay populated too, so the existing renderer's buildDesc() has something
-- to read") — this migration (plus the Java fix) restores that documented design; it is not a
-- redesign.

-- 1. Widen the legacy model/color/texture/size columns from VARCHAR(80) (V49) to VARCHAR(255),
-- matching sales.pricing_request_item's widths (V59) — brand is already VARCHAR(255) on both
-- tables and is left untouched.
--
-- REQUIRED, not cosmetic: the Java fix now writes pricing_request_item's own
-- model/color/texture/size straight onto this row. Without this widening, any pricing request
-- item whose model/color/size is already legal on pricing_request_item (up to 255 chars) but
-- longer than 80 chars would make the new INSERT throw a data-too-long error and fail
-- quotation creation outright — i.e. this fix would turn today's cosmetic blank-description bug
-- into a hard 500 for anyone with a long model/color/size. Widening VARCHAR(n) to VARCHAR(m)
-- with m > n is a metadata-only change in Postgres (no table rewrite, no row scan).
ALTER TABLE sales.quotation_item
    ALTER COLUMN model   TYPE VARCHAR(255),
    ALTER COLUMN color   TYPE VARCHAR(255),
    ALTER COLUMN texture TYPE VARCHAR(255),
    ALTER COLUMN size    TYPE VARCHAR(255);

-- 2. Backfill existing Step-4 quotation_item rows (created by the pre-fix code, so
-- model/color/texture/size are NULL) from their linked pricing_request_item.
--
-- The four-column NULL guard is load-bearing, not defensive filler:
--   - It makes this UPDATE inert on replay — this file runs exactly once under Flyway, but the
--     guard also means a manual re-run (e.g. while testing the migration) is a no-op the second
--     time, never a second overwrite.
--   - It guarantees this can never clobber a legacy (pre-Step-4) quotation_item row that
--     legitimately holds its own brand/model/color/texture/size values from the original
--     ticket-item-driven flow (V49, pre-dating pricing_request_item_id existing at all — V74).
--     Requiring ALL FOUR of model/color/texture/size to be NULL before touching any of them is
--     the conservative direction: a row that already has so much as one of these four populated
--     is left completely alone rather than partially overwritten or guessed at.
-- pricing_request_item_id IS NOT NULL further scopes this to Step-4 rows only — a legacy row
-- never has this FK set, so it can never match the join in the first place; the condition is
-- kept explicit anyway since it is the whole reason this backfill is safe to run at all.
UPDATE sales.quotation_item qi
   SET model   = pri.model,
       color   = pri.color,
       texture = pri.texture,
       size    = pri.size
  FROM sales.pricing_request_item pri
 WHERE pri.pricing_request_item_id = qi.pricing_request_item_id
   AND qi.pricing_request_item_id IS NOT NULL
   AND (qi.model IS NULL AND qi.color IS NULL AND qi.texture IS NULL AND qi.size IS NULL);
