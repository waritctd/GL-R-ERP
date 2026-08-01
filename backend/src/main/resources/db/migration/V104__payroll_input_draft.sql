-- Payroll input draft (2026-07-30): HR's in-progress, NOT-YET-PROCESSED payroll inputs used to
-- live only in React state on PayrollPage -- a browser reload re-read hr.payroll_line (the
-- engine's persisted OUTPUT) and anything HR had typed but not yet processed vanished, because
-- there was nowhere on the server for it to live. This table is that home.
--
-- Deliberately a SEPARATE table from hr.payroll_line, not a reuse of it: payroll_line holds the
-- calculation's OUTPUT (computed NOT NULL columns, one row per PROCESSED/PREVIEW-snapshotted
-- period); a draft is raw HR-typed INPUT only, with no computation performed on it at all and no
-- bearing whatsoever on PayrollCalculator/payroll math. See PayrollRepository's
-- findInputDrafts/saveInputDrafts/deleteInputDrafts and PayrollService#getInputDraft/
-- #saveInputDraft.
--
-- Column set mirrors PayrollEmployeeInputRequest's per-run fields exactly (the same shape already
-- submitted to /preview and /process) MINUS the standing tax-allowance fields (spouseAllowance..
-- politicalDonation, parentCareCount) -- those are a standing declaration edited via
-- /api/payroll/tax-allowances, never a per-run draft.
CREATE TABLE hr.payroll_input_draft (
    draft_id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id                     BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    payroll_month                   DATE NOT NULL,
    special_pay_1                   NUMERIC(12,2) NOT NULL DEFAULT 0,
    special_pay_2                   NUMERIC(12,2) NOT NULL DEFAULT 0,
    special_pay_3                   NUMERIC(12,2) NOT NULL DEFAULT 0,
    special_pay_4                   NUMERIC(12,2) NOT NULL DEFAULT 0,
    special_pay_5                   NUMERIC(12,2) NOT NULL DEFAULT 0,
    special_pay_6                   NUMERIC(12,2) NOT NULL DEFAULT 0,
    special_pay_7                   NUMERIC(12,2) NOT NULL DEFAULT 0,
    special_pay_8                   NUMERIC(12,2) NOT NULL DEFAULT 0,
    special_pay_9                   NUMERIC(12,2) NOT NULL DEFAULT 0,
    non_taxable_income               NUMERIC(12,2) NOT NULL DEFAULT 0,
    unpaid_leave_days                NUMERIC(6,2)  NOT NULL DEFAULT 0,
    student_loan_deduction           NUMERIC(12,2) NOT NULL DEFAULT 0,
    legal_execution_deduction        NUMERIC(12,2) NOT NULL DEFAULT 0,
    other_post_tax_deductions        NUMERIC(12,2) NOT NULL DEFAULT 0,
    warning_letter_deduction         NUMERIC(12,2) NOT NULL DEFAULT 0,
    customer_return_deduction        NUMERIC(12,2) NOT NULL DEFAULT 0,
    other_pretax_deduction           NUMERIC(12,2) NOT NULL DEFAULT 0,
    -- Nullable/meaningful, same as hr.payroll_line.withholding_tax_override: NULL = "no per-run
    -- override typed", distinct from an explicit 0 (withhold nothing).
    withholding_tax_override         NUMERIC(12,2),
    meal_allowance                   NUMERIC(12,2) NOT NULL DEFAULT 0,
    per_diem_exempt                  NUMERIC(12,2) NOT NULL DEFAULT 0,
    per_diem_taxable                 NUMERIC(12,2) NOT NULL DEFAULT 0,
    -- Nullable enum, mirrors hr.payroll_line.per_diem_basis (PerDiemBasis name, or NULL = not chosen).
    per_diem_basis                   VARCHAR(30),
    bonus_pay                        NUMERIC(12,2) NOT NULL DEFAULT 0,
    other_one_off_pay                NUMERIC(12,2) NOT NULL DEFAULT 0,
    customer_return_already_earned   BOOLEAN NOT NULL DEFAULT FALSE,
    -- Nullable enum, mirrors hr.payroll_line.garnishment_type (PayrollGarnishmentType name, or
    -- NULL = not chosen -- the engine then defaults to SALARY).
    garnishment_type                 VARCHAR(30),
    -- Daily-rate days-worked draft (nullable: no draft typed yet, distinct from an explicit 0).
    days_worked                      NUMERIC(6,2),
    updated_by                       BIGINT REFERENCES hr.employee(employee_id),
    updated_at                       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (employee_id, payroll_month)
);

CREATE INDEX idx_payroll_input_draft_month ON hr.payroll_input_draft(payroll_month);
