SET search_path = hr, public;

ALTER TABLE hr.overtime_request
    ADD COLUMN IF NOT EXISTS manager_approved_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS manager_approved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS ceo_approved_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS ceo_approved_at TIMESTAMPTZ;

ALTER TABLE hr.overtime_request
    DROP CONSTRAINT IF EXISTS chk_overtime_status;

ALTER TABLE hr.overtime_request
    ADD CONSTRAINT chk_overtime_status CHECK (
        status IN ('SUBMITTED', 'MANAGER_APPROVED', 'APPROVED', 'REJECTED', 'CANCELLED')
    );
