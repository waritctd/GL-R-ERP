-- Direct-deal quotation (V165) — a second variant of remark 7 "กำหนดยืนยันราคา" (price validity).
--
-- Owner feedback, verbatim (2026-09-14): "ขอเพิ่มหมายเหตุอีกกรณีนึงคือ กำหนดยืนยันราคา ขอเพิ่มแบบที่
-- กำหนดวันที่ได้ เพราะบางครั้งจำเป็นต้องระบุวันที่ชัดเจน เช่น 'ราคาพิเศษสำหรับการสั่งซื้อและชำระมัดจำ
-- ภายในวันที่..../..../.....'" — i.e. alongside the existing "N วัน นับจากวันที่ในใบเสนอราคา" (a
-- day count from the document's own date), a rep can instead name one specific calendar date.
--
-- NOT the discount-row deadline: sales.quotation_item.adjustment_deadline (an ADJUSTMENT line's own
-- "สั่งซื้อภายใน" date, printed inside DealQuotationLines#adjustmentDescription) is a per-ROW field
-- on a completely different remark and is untouched by this migration.
--
-- Additive and backward-compatible: validity_mode defaults to 'DAYS' for every existing row and for
-- the legacy customer-quotation path (which never sets it and keeps reading/writing validity_days /
-- validity_date exactly as before — see V52/V165's own columns). validity_until is nullable and is
-- only ever populated for a DEAL_DIRECT quotation saved in DATE mode.
--
-- Rollback: revert the application code (DealQuotationRequests/Service/Repository/RenderAdapter and
-- the frontend), then
--     ALTER TABLE sales.quotation DROP CONSTRAINT chk_quotation_validity_mode;
--     ALTER TABLE sales.quotation DROP COLUMN validity_mode;
--     ALTER TABLE sales.quotation DROP COLUMN validity_until;
-- No backfill is needed either direction: DAYS-mode rows never populate validity_until, so dropping
-- it loses nothing that DAYS-mode behaviour depends on.

ALTER TABLE sales.quotation
    ADD COLUMN validity_mode  VARCHAR(10) NOT NULL DEFAULT 'DAYS',
    ADD COLUMN validity_until DATE;

ALTER TABLE sales.quotation
    ADD CONSTRAINT chk_quotation_validity_mode CHECK (validity_mode IN ('DAYS', 'DATE'));

COMMENT ON COLUMN sales.quotation.validity_mode IS
    'Direct-deal quotation (origin = DEAL_DIRECT) only: DAYS (default) counts validity_days from the document date, DATE names an exact validity_until date. The legacy customer-quotation path never sets this and always reads as DAYS.';

COMMENT ON COLUMN sales.quotation.validity_until IS
    'Direct-deal quotation (origin = DEAL_DIRECT) only: the exact "ราคาพิเศษสำหรับการสั่งซื้อและชำระมัดจำภายในวันที่" deadline when validity_mode = DATE. Null in DAYS mode and for every legacy row.';
