-- R5: ใบเสนอราคา fields + ตารางแนบไฟล์ (PO / ใบเซ็นกลับ)

-- เพิ่ม fields ให้ quotation สำหรับออกใบเสนอราคา
ALTER TABLE sales.quotation
    ADD COLUMN IF NOT EXISTS validity_period  VARCHAR(100),   -- เช่น "30 วัน"
    ADD COLUMN IF NOT EXISTS deposit_pct      NUMERIC(5,4),   -- เช่น 0.3000 = 30%
    ADD COLUMN IF NOT EXISTS revision_date    DATE,
    ADD COLUMN IF NOT EXISTS notes            TEXT[],         -- หมายเหตุท้ายใบ
    ADD COLUMN IF NOT EXISTS doc_status       VARCHAR(20)  NOT NULL DEFAULT 'DRAFT';  -- DRAFT / ISSUED / SUPERSEDED

-- ตาราง attachments: แนบไฟล์กลับจากลูกค้า (PO / ใบเซ็น)
CREATE TABLE sales.attachment (
    attachment_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id     BIGINT        NOT NULL REFERENCES sales.ticket(ticket_id) ON DELETE CASCADE,
    quotation_id  BIGINT        REFERENCES sales.quotation(quotation_id),
    file_name     VARCHAR(500)  NOT NULL,
    file_path     VARCHAR(1000) NOT NULL,
    file_size     BIGINT,
    mime_type     VARCHAR(200),
    attach_type   VARCHAR(30)   NOT NULL DEFAULT 'OTHER',  -- PO / SIGNED_QUOTATION / OTHER
    uploaded_by   BIGINT        REFERENCES hr.employee(employee_id),
    uploaded_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_attachment_ticket ON sales.attachment(ticket_id);
