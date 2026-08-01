SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- Task 2 of the payroll withholding rework -- schema support for the
-- per-employee, per-component PayrollCalculator rewrite. See
-- docs/agent-handoffs/119_feat-payroll-classification-and-hr-declarations.md
-- (owner decisions 2026-07-29) and the "task 2" progress section appended
-- to that file for the full design.
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- 1. Known risk 3 from task 1: nothing seeded hr.payroll_component_sso_
-- inclusion for the ~30 employees that existed before V95, so the SSO
-- wage base would read as EMPTY for everyone and every contribution would
-- silently compute to zero. Backfill the same defaults
-- PayrollRepository#seedSsoInclusionDefaults applies to a new hire: TRUE
-- for every component except DIRECTOR_REMUNERATION and NON_TAXABLE_INCOME.
--
-- tax_year is hardcoded to 2026: verified in task 1 (production facts,
-- 2026-07-29) that 2026-03 is the earliest payroll month on record and
-- only one tax year exists yet, so this is a one-time backfill, not a
-- rolling migration concern. ON CONFLICT DO NOTHING so this is safe to
-- run even if some rows already exist (e.g. an employee created between
-- V95 landing and this migration, once the create-path wiring below took
-- effect).
-- ---------------------------------------------------------------------
INSERT INTO hr.payroll_component_sso_inclusion (employee_id, tax_year, component, is_included, updated_by_id, updated_at)
SELECT e.employee_id,
       2026,
       c.component,
       c.component NOT IN ('DIRECTOR_REMUNERATION', 'NON_TAXABLE_INCOME'),
       NULL,
       now()
  FROM hr.employee e
 CROSS JOIN hr.payroll_pay_component c
    ON CONFLICT (employee_id, tax_year, component) DO NOTHING;

-- ---------------------------------------------------------------------
-- 2. เงินโบนัส / อื่นๆ payroll_line columns. Branch 117 (fix/payroll-wht-
-- po96-compliance) was expected to supply these via its V94 before this
-- branch, per PayrollComponent's javadoc -- but 117 is not merged into
-- this worktree, so BONUS_PAY / OTHER_ONE_OFF_PAY (already in the V95
-- enum) have no column to read from. The spec calls bonus the archetypal
-- EXTRA_KNOWN_FREQUENCY example and names โบนัส explicitly in the SSO wage
-- base (handoff section 5) -- the calculator rewrite cannot express either
-- without these columns, so they are added here rather than waiting on
-- 117.
-- ---------------------------------------------------------------------
ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS bonus_pay NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS other_one_off_pay NUMERIC(12,2) NOT NULL DEFAULT 0;

ALTER TABLE hr.payroll_line
    DROP CONSTRAINT IF EXISTS chk_payroll_line_bonus_pay_non_negative,
    ADD CONSTRAINT chk_payroll_line_bonus_pay_non_negative CHECK (bonus_pay >= 0),
    DROP CONSTRAINT IF EXISTS chk_payroll_line_other_one_off_pay_non_negative,
    ADD CONSTRAINT chk_payroll_line_other_one_off_pay_non_negative CHECK (other_one_off_pay >= 0);

-- ---------------------------------------------------------------------
-- 3. Per-limb taxable-income and withholding-tax breakdown, one column
-- pair per ป.96/2543 limb, so a later period's year-to-date projection can
-- be reconstructed per limb instead of as one blended total (see
-- PayrollRepository#findYearToDateByEmployee, extended by this task).
--
-- Why this cannot just be arithmetic on the existing gross_taxable_income
-- and withholding_tax columns: those two remain the TOTAL across all three
-- limbs (PND1/SSO export and the payslip need the total, unchanged), but
-- ข้อ 1(4)'s reprojection needs the REGULAR limb's own prior contribution,
-- and ข้อ 1(6)'s "less tax already withheld" needs the CUMULATIVE limb's
-- own prior withholding -- neither is separable from the total after the
-- fact without a second, independent source of drift (allowance changes,
-- SSO cap changes) corrupting the split. The known-limb (ข้อ 1(5)) taxable
-- amount is stored for audit/transparency; unlike the other two it is not
-- read back into next period's projection (each known-frequency payment is
-- taxed as its own period's marginal difference, not carried forward -- see
-- PayrollCalculator#calculateClassified).
-- ---------------------------------------------------------------------
ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS taxable_income_regular_limb NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS taxable_income_known_limb NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS taxable_income_cumulative_limb NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS withholding_tax_regular_limb NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS withholding_tax_cumulative_limb NUMERIC(12,2) NOT NULL DEFAULT 0;

-- Backfill: every payroll_line row processed before this migration was
-- computed by the pre-V95 engine, which had exactly one limb (everything
-- annualised together, ป.96 ข้อ 1(4) style). So historically
-- gross_taxable_income and withholding_tax ARE what the regular limb
-- means; known/cumulative stay at their 0 default (correctly -- those
-- limbs did not exist in the old engine's model).
UPDATE hr.payroll_line
   SET taxable_income_regular_limb = gross_taxable_income,
       withholding_tax_regular_limb = withholding_tax
 WHERE taxable_income_regular_limb = 0
   AND withholding_tax_regular_limb = 0;

-- ---------------------------------------------------------------------
-- 4. ลูกค้าคืนสินค้า earned/unearned flag (handoff section 6). Commission
-- not yet earned reduces the commission earning itself (netted into
-- COMMISSION_PAY pre-tax by the service layer, so it never reaches this
-- column as a separate deduction); already earned and paid is a separate
-- post-tax clawback that must not reduce current taxable income. This
-- column records which treatment applied to whatever is stored in the
-- pre-existing customer_return_deduction column, for audit.
-- ---------------------------------------------------------------------
ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS customer_return_already_earned BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------
-- 5. Garnishment payment type (handoff section 7). The pre-existing
-- legal_execution_deduction column stays the HR-requested amount; this
-- column records which statutory cap applied to it. Defaults to SALARY,
-- which reproduces the pre-existing single 30%-plus-฿20,000-floor rule
-- exactly (see PayrollCalculator's legacy legalExecutionDeduction()
-- method, unchanged) for every row written before HR had a reason to pick
-- anything else.
-- ---------------------------------------------------------------------
ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS garnishment_type TEXT NOT NULL DEFAULT 'SALARY';

ALTER TABLE hr.payroll_line
    DROP CONSTRAINT IF EXISTS chk_payroll_line_garnishment_type_valid,
    ADD CONSTRAINT chk_payroll_line_garnishment_type_valid CHECK (
        garnishment_type IN ('SALARY', 'BONUS', 'OVERTIME_OR_DILIGENCE', 'SEVERANCE')
    );

-- ---------------------------------------------------------------------
-- 6. payroll_year_to_date_seed: the mid-year go-live back-load needs the
-- same per-limb split as payroll_line so a tax year that starts with a
-- seeded balance (rather than accumulating from processed periods only)
-- projects correctly. taxable_income / withholding_tax (existing columns)
-- keep meaning "the regular limb", matching the payroll_line backfill
-- above -- seeded balances predate the three-limb model exactly like the
-- processed rows do.
-- ---------------------------------------------------------------------
ALTER TABLE hr.payroll_year_to_date_seed
    ADD COLUMN IF NOT EXISTS cumulative_limb_taxable_income NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cumulative_limb_withholding_tax NUMERIC(12,2) NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------
-- 7. Declaration verification gating: an EXPIRED_UNVERIFIED declaration
-- must stop applying to withholding from the next payroll onward (handoff
-- section 3). PayrollRepository#findTaxAllowancesByEmployee (extended by
-- this task) now excludes EXPIRED_UNVERIFIED rows outright, so no schema
-- change is required for that gate -- noted here only so the reader of
-- this migration knows the behaviour is enforced in Java, not SQL.
-- ---------------------------------------------------------------------
