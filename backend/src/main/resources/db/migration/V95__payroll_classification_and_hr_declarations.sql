SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- Payroll withholding classification + HR declaration schema.
-- Owner decisions 2026-07-29 -- see docs/agent-handoffs/119_feat-payroll-
-- classification-and-hr-declarations.md for the full specification and
-- the production facts (queried read-only against tdyzcqzxmhtxpbouewud)
-- that back every default and cap below.
--
-- SCOPE: schema + model + repository only. PayrollCalculator's tax math
-- is NOT rewritten here -- that is the next task and depends on this one
-- landing cleanly. Nothing here changes what any already-processed
-- payroll_line row means.
--
-- Reserved-number note: prod's applied Flyway max is V91.1 and this repo's
-- max is V91. V92/V93/V94 are reserved by branch 117 (fix/payroll-wht-
-- po96-compliance, not merged) -- left untouched. This is V95.
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- 1. A ninth special_pay column, added APPEND-ONLY at the time this
-- migration was written.
--
-- CORRECTED IN PLACE (F7/"Also", Opus review 2026-07-30) -- comment only,
-- no DDL below has changed. This originally argued: hr.payroll_line
-- already carries 149 processed rows across five filed months whose
-- special_pay_1..8 values mean exactly what the current labels said,
-- so inserting ค่าเช่าบ้าน anywhere but the end would silently redefine
-- every historical figure and every ภ.ง.ด.1 already filed from them --
-- therefore APPEND ONLY, never renumber.
--
-- That premise is now FALSE, and was overtaken the same day it was
-- written. The handoff's section 9e records that all five of those
-- "processed" 2026 periods were owner-confirmed test runs and were
-- VOIDed on production (no ภ.ง.ด.1 was ever filed, no SSO ever
-- remitted from this system) -- so there was nothing real for a
-- renumber to silently redefine. Section 9d's later "align the system
-- to the accountant's workbook" decision then DID renumber: ค่าเช่าบ้าน
-- moved from this new ninth slot to special_pay_2, and special_pay_9 is
-- now เงินรางวัล/เงินช่วยเหลืออื่นๆ. See PayrollComponent's javadoc for
-- the current, authoritative slot -> label mapping. This section only
-- adds the ninth NUMERIC column; it carries no opinion on which พิเศษ
-- label ends up assigned to which slot -- that lives entirely in Java
-- (PayrollService#specialPayDtos / PayrollComponent) and the frontend,
-- neither of which this migration constrains.
-- ---------------------------------------------------------------------
ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS special_pay_9 NUMERIC(12,2) NOT NULL DEFAULT 0;

ALTER TABLE hr.payroll_line
    DROP CONSTRAINT IF EXISTS chk_payroll_line_special_pay_9_non_negative,
    ADD CONSTRAINT chk_payroll_line_special_pay_9_non_negative CHECK (special_pay_9 >= 0);

-- ---------------------------------------------------------------------
-- 2. Canonical pay-component enumeration, shared by the two per-employee
-- matrices below (tax treatment + SSO inclusion) so both are governed by
-- exactly one list rather than two independently-maintained CHECK IN(...)
-- clauses. One row per payroll_line money column that represents earned/
-- taxable income HR or the engine can put a non-zero amount into for a
-- given employee and run -- NOT deductions or tax allowances, which are
-- governed elsewhere (hr.employee_tax_allowance, the *_deduction columns).
--
-- A lookup table (not a native Postgres ENUM) so a future addition is a
-- single appended INSERT, matching the special_pay_9 append-only
-- precedent above: never renumber or remove a value already in use, since
-- that would retroactively redefine what an already-stored classification
-- or SSO-inclusion row means.
--
-- NOTE (2026-07-29, underspecified in the source handoff): section 10 of
-- the handoff also names bonusPay / otherOneOffPay as future HR-typed
-- fields on the payroll run, but neither has a payroll_line column yet --
-- adding one is out of scope for this migration (schema+model only, per
-- the task brief). They are deliberately NOT included below. Add
-- BONUS / OTHER_ONE_OFF_PAY as new appended rows here when those columns
-- land, never ahead of the columns existing.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS hr.payroll_pay_component (
    component TEXT PRIMARY KEY
);

INSERT INTO hr.payroll_pay_component (component) VALUES
    ('SALARY'),
    ('SPECIAL_PAY_1'), ('SPECIAL_PAY_2'), ('SPECIAL_PAY_3'), ('SPECIAL_PAY_4'), ('SPECIAL_PAY_5'),
    ('SPECIAL_PAY_6'), ('SPECIAL_PAY_7'), ('SPECIAL_PAY_8'), ('SPECIAL_PAY_9'),
    ('OVERTIME_PAY'),
    ('COMMISSION_PAY'),
    ('BONUS_PAY'), ('OTHER_ONE_OFF_PAY'),
    ('DIRECTOR_REMUNERATION'),
    ('NON_TAXABLE_INCOME')
    ON CONFLICT (component) DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. Per-employee, per-component tax treatment (ป.96/2543 ข้อ 1(4)/1(5)/1(6)).
--
-- tax_treatment is nullable = "not yet classified". HR sets every line;
-- an unclassified component with a non-zero amount in a given run blocks
-- that run (enforced in the service layer, next task -- this migration
-- only provides the storage and the one hard rule below).
--
-- เงินเดือน (SALARY) is LOCKED to REGULAR_REPROJECT -- it is paid every
-- คราว by definition and has no second sensible treatment (owner,
-- 2026-07-29). Every other component, including ค่าตอบแทนกรรมการ and all
-- nine พิเศษ, is freely HR-classified per employee. Enforced here via
-- CHECK so a SALARY row can never be stored with any other treatment
-- (including NULL) -- the application is free to not insert a SALARY row
-- at all (its treatment is implied), but if one exists, it must say
-- REGULAR_REPROJECT.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS hr.payroll_component_tax_treatment (
    employee_id     BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    tax_year        SMALLINT NOT NULL,
    component       TEXT NOT NULL REFERENCES hr.payroll_pay_component(component),
    tax_treatment   TEXT NULL,
    updated_by_id   BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (employee_id, tax_year, component),
    CONSTRAINT chk_pctt_tax_treatment_valid CHECK (
        tax_treatment IS NULL OR tax_treatment IN (
            'REGULAR_REPROJECT', 'EXTRA_KNOWN_FREQUENCY', 'EXTRA_CUMULATIVE_ACTUAL'
        )
    ),
    -- The IS NOT NULL is load-bearing, not belt-and-braces. A CHECK rejects a row only when the
    -- expression evaluates to FALSE; NULL passes. Written as `component <> 'SALARY' OR tax_treatment
    -- = 'REGULAR_REPROJECT'`, the pair ('SALARY', NULL) evaluates FALSE OR NULL -> NULL -> ACCEPTED,
    -- so salary could be stored UNCLASSIFIED -- the one state the owner said cannot exist for it.
    -- Reachable through the documented affordance: upsertComponentTaxTreatment sends a null to reset
    -- a component, and ON CONFLICT DO UPDATE would have written it straight onto salary.
    CONSTRAINT chk_pctt_salary_locked_to_regular_reproject CHECK (
        component <> 'SALARY'
        OR (tax_treatment IS NOT NULL AND tax_treatment = 'REGULAR_REPROJECT')
    )
);

CREATE INDEX IF NOT EXISTS idx_pctt_employee_tax_year
    ON hr.payroll_component_tax_treatment(employee_id, tax_year);

-- ---------------------------------------------------------------------
-- 4. Per-employee, per-component SSO wage-base inclusion.
--
-- Owner-decided 2026-07-29, superseding an earlier company-wide-default
-- proposal: employee 10080 draws both a salary AND a director fee, so a
-- single per-person on/off flag gives the wrong answer either way -- the
-- tick must be a matrix (one row per employee, one column per component),
-- not a per-person checkbox.
--
-- Seed defaults (applied by the repository on first insert for an
-- employee/tax-year, not by a DB trigger -- wiring this into employee
-- onboarding is service-layer work for a later task): every component
-- TRUE except DIRECTOR_REMUNERATION and NON_TAXABLE_INCOME, per section 5
-- of the handoff. is_included stays HR-editable per employee thereafter,
-- so the seed is only the starting value, not an enforced constraint.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS hr.payroll_component_sso_inclusion (
    employee_id     BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    tax_year        SMALLINT NOT NULL,
    component       TEXT NOT NULL REFERENCES hr.payroll_pay_component(component),
    is_included     BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by_id   BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (employee_id, tax_year, component)
);

CREATE INDEX IF NOT EXISTS idx_pcsi_employee_tax_year
    ON hr.payroll_component_sso_inclusion(employee_id, tax_year);

-- ---------------------------------------------------------------------
-- 5. Declaration verification + grandfathering (hr.employee_tax_allowance).
--
-- Applies to employee-declared allowances only -- not the automatic
-- ฿60,000 personal allowance, actual SSO, or anything derived from
-- payroll records. Verified against production 2026-07-29: this table has
-- ZERO rows, so there is nothing to backfill/grandfather today; the
-- default below only matters for rows entered from here on.
--
-- State machine (service layer, next task, enforces the transitions):
--   GRANDFATHERED_UNVERIFIED -> applied, HR + employee warned, until
--     verification_deadline.
--   -> VERIFIED on HR confirming supporting documents (verified_by_id /
--     verified_at set).
--   -> EXPIRED_UNVERIFIED if the deadline lapses unverified; from the
--     next payroll the declared amount stops being applied. Already-filed
--     months are never retro-altered for a late verification.
-- ---------------------------------------------------------------------
ALTER TABLE hr.employee_tax_allowance
    ADD COLUMN IF NOT EXISTS verification_status TEXT NOT NULL DEFAULT 'GRANDFATHERED_UNVERIFIED',
    ADD COLUMN IF NOT EXISTS verified_by_id BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS verified_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS verification_deadline DATE NULL;

ALTER TABLE hr.employee_tax_allowance
    DROP CONSTRAINT IF EXISTS chk_eta_verification_status_valid,
    ADD CONSTRAINT chk_eta_verification_status_valid CHECK (
        verification_status IN ('VERIFIED', 'GRANDFATHERED_UNVERIFIED', 'EXPIRED_UNVERIFIED')
    );

-- ---------------------------------------------------------------------
-- 6. Parent allowance per qualifying head, not a flat cap.
--
-- The law is ฿30,000 PER qualifying parent, ฿120,000 = the four-parent
-- maximum -- not a flat ฿120,000 regardless of count. parent_care_count is
-- the head count PayrollCalculator will multiply by ฿30,000 (capped) in
-- the next task; capped at 4 here since that is the statutory maximum
-- number of qualifying parents (two biological + two parents-in-law).
-- ---------------------------------------------------------------------
ALTER TABLE hr.employee_tax_allowance
    ADD COLUMN IF NOT EXISTS parent_care_count SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE hr.employee_tax_allowance
    DROP CONSTRAINT IF EXISTS chk_eta_parent_care_count_valid,
    ADD CONSTRAINT chk_eta_parent_care_count_valid CHECK (parent_care_count BETWEEN 0 AND 4);

-- ---------------------------------------------------------------------
-- 7. PVD (provident fund) -- deliberately NOT added.
--
-- GL&R has no provident fund: hr_restricted.employee_pii has ZERO rows
-- with a provident_fund_no populated (verified 2026-07-29). The
-- pre-existing provident_fund_no column (V1) is untouched here and is
-- NOT this migration's concern -- its removal, if any, belongs to branch
-- 117 (fix/payroll-wht-po96-compliance), which owns that cleanup.
-- ---------------------------------------------------------------------
