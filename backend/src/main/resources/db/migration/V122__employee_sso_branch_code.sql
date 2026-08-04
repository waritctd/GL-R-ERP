-- V122: per-employee SSO branch (ลำดับที่สาขา) so the สปส.1-10 export can file more than one branch.
--
-- WHY. GL&R is registered with the SSO under employer account 10-0050988-5 with TWO branch
-- sequences. Until now the only branch the ERP knew was the single `app.payroll.employer.sso-branch`
-- config value, so `SsoExporter` emitted exactly one type-1 header covering every insured employee.
-- Uploading that file to the e-service while selecting both branches is rejected with
--   "จำนวนสาขาที่เลือกทำธุรกรรมไม่ตรงกับจำนวนสาขาที่พบในไฟล์ จำนวนสาขาที่เลือกคือ 2 จำนวนสาขาที่พบในไฟล์คือ 1"
-- because the branch code appears ONLY in the header record (สปส.1-10 text layout, positions 12-17),
-- so N branches in one file means N header blocks, each followed by its own detail records.
--
-- NULL means "not assigned" and falls back to `app.payroll.employer.sso-branch` (head office,
-- "000000") in SsoExporter. That keeps every existing installation byte-identical to today until an
-- employee is explicitly moved to another branch -- this migration deliberately changes no export.
--
-- NO BACKFILL HERE, ON PURPOSE. Assigning the real employees requires their national ids to match
-- them against the SSO filing, and this repository is public. The owner applies the assignments from
-- a separate, uncommitted SQL script. Until then every employee files under head office, exactly as
-- before.
--
-- Schema change is the task, not a side effect: see the PR body.

ALTER TABLE hr.employee
    ADD COLUMN IF NOT EXISTS sso_branch_code VARCHAR(6);

-- 6 digits, zero-padded, e.g. '000000' (สำนักงานใหญ่) or '110001'. NULL = inherit the employer default.
ALTER TABLE hr.employee
    DROP CONSTRAINT IF EXISTS chk_employee_sso_branch_code_format,
    ADD CONSTRAINT chk_employee_sso_branch_code_format
        CHECK (sso_branch_code IS NULL OR sso_branch_code ~ '^[0-9]{6}$');

COMMENT ON COLUMN hr.employee.sso_branch_code IS
  'ลำดับที่สาขา for the สปส.1-10 contribution file (6 digits). NULL = app.payroll.employer.sso-branch.';
