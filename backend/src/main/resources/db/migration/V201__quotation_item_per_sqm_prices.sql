-- Sales feedback #1 (แจ้งปัญหาการใช้งาน เสนอราคา, 2026-10-06) — "แก้เป็น ตร.ม. รวม VAT ทุกแบบ".
--
-- Sales read tile prices per ตร.ม. including VAT. The editor made them state ราคาตั้ง per แผ่น —
-- converting it by hand ("ราคาตั้งไม่แปลงให้ ต้องแก้ไขเป็นราคา/แผ่นเอง") — while it converted ราคาพิเศษ
-- for them. Owner ruling 2026-10-06: every tile price box on a Thai quotation is typed per ตร.ม.
-- including VAT (ราคาตั้ง in all three price modes, and ราคาสุทธิ in DIRECT_NET), and the system
-- works out the per-แผ่น figure the document prints, by the rule ราคาพิเศษ already uses
-- (WastageCalculator#perPieceExVatFromSqmIncVat).
--
-- These two columns hold what the rep TYPED. They are needed because that conversion cannot be
-- undone: on a 10×10 tile (100 แผ่น/ตร.ม.) 1,800 per ตร.ม. is 16.82 a piece, and 16.82 a piece is
-- 1,799.74 per ตร.ม. — an editor that recovered the figure from unit_price would hand the rep back
-- a price they never typed.
--
--   list_price_sqm        ราคาตั้ง, บาท/ตร.ม. including VAT. unit_price is the per-แผ่น figure
--                         derived from it.
--   direct_net_price_sqm  DIRECT_NET's ราคาสุทธิ, บาท/ตร.ม. including VAT. final_unit_price is the
--                         per-แผ่น figure derived from it.
--
-- Both are INPUTS kept for the editor. Nothing prints them and no total is computed from them:
-- unit_price / final_unit_price / amount stay the columns every sum, every CEO-price comparison
-- and every printed cell reads, exactly as before. special_price_sqm (V168) already plays this same
-- role for ราคาพิเศษ, which is why there is no third column here.
--
-- NULLABLE, no default, no backfill: NULL means "this row's price was stated per แผ่น", which is
-- true of every existing row, of every English-document row (USD, no VAT — "รวม VAT" means nothing
-- there), and of every row created from a pricing request. The editor shows such a row's per-แผ่น
-- price converted for display and leaves the stored figures alone until the rep retypes them, so
-- no existing document is repriced by this migration or by opening it afterwards.
-- NUMERIC(12,2) matches special_price_sqm.
--
-- Rollback: revert the application code (DealQuotationRequests.ItemInput, DealQuotationDtos,
-- DealQuotationRepository, DealQuotationService, WastageCalculator, the mock, and the editor's
-- price boxes), then
--     ALTER TABLE sales.quotation_item DROP COLUMN list_price_sqm, DROP COLUMN direct_net_price_sqm;
-- Dropping them loses only the typed per-ตร.ม. figures; every price that was ever charged or
-- printed lives in unit_price / final_unit_price and is untouched.

ALTER TABLE sales.quotation_item
    ADD COLUMN list_price_sqm NUMERIC(12,2),
    ADD COLUMN direct_net_price_sqm NUMERIC(12,2);

ALTER TABLE sales.quotation_item
    ADD CONSTRAINT chk_quotation_item_list_price_sqm_positive
        CHECK (list_price_sqm IS NULL OR list_price_sqm > 0),
    ADD CONSTRAINT chk_quotation_item_direct_net_price_sqm_positive
        CHECK (direct_net_price_sqm IS NULL OR direct_net_price_sqm > 0);

COMMENT ON COLUMN sales.quotation_item.list_price_sqm IS
    'Deal quotation TILE row, Thai document: the list price (ราคาตั้ง) as the rep typed it, baht per sqm INCLUDING VAT. unit_price is the per-piece, ex-VAT figure the server derived from it (WastageCalculator#perPieceExVatFromSqmIncVat) and is what prints. NULL = the list price was stated per piece (every pre-V201 row, English documents, pricing-request rows). Never printed, never summed.';

COMMENT ON COLUMN sales.quotation_item.direct_net_price_sqm IS
    'Deal quotation TILE row in DIRECT_NET price mode, Thai document: the net price (ราคาสุทธิ) as the rep typed it, baht per sqm INCLUDING VAT. final_unit_price is the per-piece, ex-VAT figure derived from it. NULL in every other mode and whenever the net was stated per piece. Never printed, never summed.';
