SET search_path = hr, public;

ALTER TABLE sales.invoice_details
    ADD COLUMN IF NOT EXISTS invoice_attachment_id BIGINT REFERENCES hr.file_attachment(attachment_id) ON DELETE SET NULL;

ALTER TABLE sales.commission_record
    ADD COLUMN IF NOT EXISTS manager_approved_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS manager_approved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS ceo_approved_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS ceo_approved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS rejected_by_id BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS rejection_reason TEXT;

ALTER TABLE sales.commission_record
    DROP CONSTRAINT IF EXISTS chk_commission_status;

ALTER TABLE sales.commission_record
    ADD CONSTRAINT chk_commission_status CHECK (
        status IN ('SUBMITTED','MANAGER_APPROVED','APPROVED','REJECTED','VOID')
    );

CREATE INDEX IF NOT EXISTS idx_invoice_details_attachment
    ON sales.invoice_details(invoice_attachment_id);
