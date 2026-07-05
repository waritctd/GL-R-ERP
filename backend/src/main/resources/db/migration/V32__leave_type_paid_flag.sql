-- Distinguish paid statutory leave from unpaid leave so payroll only deducts the unpaid kind.
-- Existing types (SICK, VACATION, PERSONAL) are paid leave under the Labour Protection Act and
-- must NOT reduce pay, so the column defaults to TRUE.
ALTER TABLE hr.leave_type
    ADD COLUMN IF NOT EXISTS is_paid BOOLEAN NOT NULL DEFAULT TRUE;

-- A dedicated "leave without pay" type (ลาไม่รับค่าจ้าง). Days approved under this type feed the
-- payroll unpaid-leave deduction (no-work-no-pay). Nominal quota satisfies the positive-quota check.
INSERT INTO hr.leave_type (leave_type_code, name_th, name_en, annual_quota_days, requires_attachment, is_paid)
VALUES ('UNPAID', 'ลาไม่รับค่าจ้าง', 'Leave without pay', 365.00, FALSE, FALSE)
ON CONFLICT (leave_type_code) DO NOTHING;
