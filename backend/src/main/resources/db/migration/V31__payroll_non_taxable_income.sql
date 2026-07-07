-- Non-taxable income (sheet column D / "รายได้อื่นๆที่ไม่คำนวนภาษี").
-- Excluded from tax and SSO; added back to net pay after all deductions.
ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS non_taxable_income NUMERIC(12,2) NOT NULL DEFAULT 0;
