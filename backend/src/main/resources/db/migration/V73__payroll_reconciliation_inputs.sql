SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- Payroll reconciliation against the accountant's real 2026 workbook
-- (2026.xlsx, sheet พ.ค.69). Four gaps closed, mirrored arithmetic the
-- sheet already performs -- see docs/agent-handoffs for the reconciliation
-- write-up and PayrollExcelReconciliationTest for the transcribed figures.
-- ---------------------------------------------------------------------

-- C1: per-employee tax allowance declarations (16 fields), persisted per tax year so HR does not
-- retype them every payroll run. The payroll run body may still override a subset in-run; the stored
-- row is the standing declaration used as the base.
CREATE TABLE IF NOT EXISTS hr.employee_tax_allowance (
    employee_id                        BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    tax_year                           SMALLINT NOT NULL,
    spouse_allowance                   NUMERIC(12,2) NOT NULL DEFAULT 0,
    child_allowance                    NUMERIC(12,2) NOT NULL DEFAULT 0,
    parent_care_allowance              NUMERIC(12,2) NOT NULL DEFAULT 0,
    disabled_care_allowance            NUMERIC(12,2) NOT NULL DEFAULT 0,
    maternity_allowance                NUMERIC(12,2) NOT NULL DEFAULT 0,
    life_insurance_allowance           NUMERIC(12,2) NOT NULL DEFAULT 0,
    health_insurance_allowance         NUMERIC(12,2) NOT NULL DEFAULT 0,
    parent_health_insurance_allowance  NUMERIC(12,2) NOT NULL DEFAULT 0,
    rmf_allowance                      NUMERIC(12,2) NOT NULL DEFAULT 0,
    ssf_allowance                      NUMERIC(12,2) NOT NULL DEFAULT 0,
    pension_insurance_allowance        NUMERIC(12,2) NOT NULL DEFAULT 0,
    thai_esg_allowance                 NUMERIC(12,2) NOT NULL DEFAULT 0,
    home_loan_interest_allowance       NUMERIC(12,2) NOT NULL DEFAULT 0,
    education_donation                 NUMERIC(12,2) NOT NULL DEFAULT 0,
    general_donation                   NUMERIC(12,2) NOT NULL DEFAULT 0,
    political_donation                 NUMERIC(12,2) NOT NULL DEFAULT 0,
    updated_by_id                      BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    updated_at                         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (employee_id, tax_year),
    CONSTRAINT chk_eta_non_negative CHECK (
        spouse_allowance >= 0 AND child_allowance >= 0 AND parent_care_allowance >= 0 AND
        disabled_care_allowance >= 0 AND maternity_allowance >= 0 AND life_insurance_allowance >= 0 AND
        health_insurance_allowance >= 0 AND parent_health_insurance_allowance >= 0 AND rmf_allowance >= 0 AND
        ssf_allowance >= 0 AND pension_insurance_allowance >= 0 AND thai_esg_allowance >= 0 AND
        home_loan_interest_allowance >= 0 AND education_donation >= 0 AND general_donation >= 0 AND
        political_donation >= 0
    )
);

-- C2: year-to-date backfill for a mid-year go-live. findYearToDateByEmployee sums payroll_line rows,
-- which is empty on a fresh system, so withholding under-projects and falls to zero from ~August. This
-- table holds the pre-system history (from the accountant's records) loaded once at go-live; the
-- repository merges it with payroll_line so an employee may appear in either or both.
CREATE TABLE IF NOT EXISTS hr.payroll_year_to_date_seed (
    employee_id     BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    tax_year        SMALLINT NOT NULL,
    taxable_income  NUMERIC(14,2) NOT NULL DEFAULT 0,
    social_security NUMERIC(12,2) NOT NULL DEFAULT 0,
    withholding_tax NUMERIC(12,2) NOT NULL DEFAULT 0,
    source_note     TEXT,
    updated_by_id   BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (employee_id, tax_year),
    CONSTRAINT chk_ytd_seed_non_negative CHECK (
        taxable_income >= 0 AND social_security >= 0 AND withholding_tax >= 0
    )
);

-- C3: ค่าตอบแทนกรรมการ (director remuneration). Fixed per person per month, stored on the employee
-- record (not typed per payroll run). It is taxable income but is NOT wages under the Social Security
-- Act, so it must never feed the SSO wage base (which stays base-salary-only).
ALTER TABLE hr.employee
    ADD COLUMN IF NOT EXISTS director_remuneration NUMERIC(12,2) NOT NULL DEFAULT 0;

ALTER TABLE hr.employee
    DROP CONSTRAINT IF EXISTS chk_employee_director_remuneration_non_negative,
    ADD CONSTRAINT chk_employee_director_remuneration_non_negative CHECK (director_remuneration >= 0);

ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS director_remuneration NUMERIC(12,2) NOT NULL DEFAULT 0;

-- C4: the three missing pre-tax deductions (sheet columns Z, AA, AB). The sheet taxes AD = W - AC,
-- where AC already nets out these three alongside unpaid leave; the engine previously only subtracted
-- unpaid leave before tax, sending these three into post-tax deductions and over-taxing the employee.
ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS warning_letter_deduction NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS customer_return_deduction NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS other_pretax_deduction NUMERIC(12,2) NOT NULL DEFAULT 0;

ALTER TABLE hr.payroll_line
    DROP CONSTRAINT IF EXISTS chk_payroll_line_pretax_non_negative,
    ADD CONSTRAINT chk_payroll_line_pretax_non_negative CHECK (
        director_remuneration >= 0 AND warning_letter_deduction >= 0 AND
        customer_return_deduction >= 0 AND other_pretax_deduction >= 0
    );
