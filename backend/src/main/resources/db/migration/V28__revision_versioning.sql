-- R6: item snapshot ใน events + quotation versioning

-- เก็บ snapshot ของรายการสินค้าตอน PRICE_PROPOSED เพื่อดู diff
ALTER TABLE sales.ticket_event
    ADD COLUMN item_snapshot JSONB;

-- เวอร์ชันใบเสนอราคา (Rev 1, Rev 2, …)
ALTER TABLE sales.quotation
    ADD COLUMN IF NOT EXISTS quotation_version INT NOT NULL DEFAULT 1;

-- backfill: quotation ที่มีอยู่แล้วให้เป็น v1
UPDATE sales.quotation SET quotation_version = 1 WHERE quotation_version IS NULL OR quotation_version = 0;
