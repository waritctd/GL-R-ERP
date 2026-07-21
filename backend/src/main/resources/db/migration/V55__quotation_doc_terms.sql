-- V55: Store the three pre-generate values that appear in the quotation remarks block.
-- offer_date  → B24: "จำนวนที่ได้รับมาเมื่อวันที่ ..."
-- deposit_pct → B26: "ขอรับมัดจำ X%"
-- delivery_days → B28: "ระยะเวลานำเข้าประมาณ X วัน"
ALTER TABLE sales.quotation
    ADD COLUMN IF NOT EXISTS offer_date       DATE,
    ADD COLUMN IF NOT EXISTS deposit_pct      SMALLINT,
    ADD COLUMN IF NOT EXISTS delivery_days    SMALLINT;
