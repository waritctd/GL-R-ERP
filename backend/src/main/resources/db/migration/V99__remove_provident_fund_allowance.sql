SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- Remove กองทุนสำรองเลี้ยงชีพ from the payroll tax-allowance layer (owner decision, 2026-07-29).
--
-- V93 added `provident_fund_allowance` reasoning that hr_restricted.employee_pii already carries a
-- `provident_fund_no` column, "so the company has members, and their contributions are deducted at
-- source". The column exists; the members do not. Queried against the real production database on
-- 2026-07-29: ZERO rows have a provident_fund_no populated. GL&R operates no provident fund.
--
-- The inference ran from a schema column to a business fact, and it was wrong. Nothing was ever
-- deducted through it: the column has carried its NOT NULL DEFAULT 0 on every row for its whole
-- life, and no payroll period has been processed since V93 was written.
--
-- NOT TOUCHED, deliberately: hr_restricted.employee_pii.provident_fund_no (V1). That is a genuine
-- employee PII field predating all of this work, unrelated to the tax-allowance layer beyond
-- sharing a name. Dropping it would destroy real HR data. V95 says the same at its line 199.
-- ---------------------------------------------------------------------

-- The constraint must be dropped and rebuilt, NOT left to the column drop.
--
-- `provident_fund_allowance >= 0` is one term inside the MULTI-column chk_eta_counts_non_negative,
-- which also guards child_count, child_count_double and disabled_care_count -- including the
-- invariant `child_count_double <= child_count`. Postgres drops any CHECK constraint that
-- references a dropped column IN ITS ENTIRETY, so a bare DROP COLUMN would silently take all four
-- guards with it and nothing would fail. The child-allowance consequence is concrete: with the
-- invariant gone, HR could declare 5 second-or-later children against a child_count of 2 and
-- childAllowanceCap would hand back ฿90,000 more than the law allows.
ALTER TABLE hr.employee_tax_allowance
    DROP CONSTRAINT IF EXISTS chk_eta_counts_non_negative;

ALTER TABLE hr.employee_tax_allowance
    DROP COLUMN IF EXISTS provident_fund_allowance;

-- Re-added verbatim minus the PVD term.
ALTER TABLE hr.employee_tax_allowance
    ADD CONSTRAINT chk_eta_counts_non_negative CHECK (
        child_count >= 0 AND child_count_double >= 0 AND disabled_care_count >= 0
        AND child_count_double <= child_count
    );

-- Consequence for the ฿500,000 retirement cluster: V93 placed PVD FIRST on the reasoning that a
-- contribution taken at source cannot be stopped by the employee, so the cluster should exhaust
-- against voluntary purchases instead. With no PVD the cluster now begins at RMF. The remaining
-- order (RMF -> SSF -> pension), the ฿500,000 ceiling, มาตรา 42 bis and the RMF/pension sub-caps
-- are all unchanged. See PayrollCalculator#retirementAllowance.
