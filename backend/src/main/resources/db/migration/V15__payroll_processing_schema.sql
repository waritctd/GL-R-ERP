SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- PAYROLL: enrich the original payroll scaffold with Thai payroll inputs
-- and calculation outputs used by the payroll processing API.
-- ---------------------------------------------------------------------

ALTER TABLE hr.payroll_period
    ADD COLUMN IF NOT EXISTS payroll_month DATE,
    ADD COLUMN IF NOT EXISTS processed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS processed_by_id BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL;

UPDATE hr.payroll_period
   SET payroll_month = date_trunc('month', period_start)::date
 WHERE payroll_month IS NULL;

ALTER TABLE hr.payroll_period
    ALTER COLUMN payroll_month SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_payroll_period_month
    ON hr.payroll_period(payroll_month);

ALTER TABLE hr.payroll_period
    DROP CONSTRAINT IF EXISTS chk_payroll_period_month_first,
    ADD CONSTRAINT chk_payroll_period_month_first CHECK (
        payroll_month = date_trunc('month', payroll_month)::date
    );

ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS base_salary NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS daily_rate NUMERIC(12,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS hourly_rate NUMERIC(12,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS special_pay_1 NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS special_pay_2 NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS special_pay_3 NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS special_pay_4 NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS special_pay_5 NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS special_pay_6 NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS special_pay_7 NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS special_pay_8 NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS special_pay_total NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS overtime_pay NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS commission_pay NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS unpaid_leave_days NUMERIC(6,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS unpaid_leave_deduction NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS gross_taxable_income NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sso_wage_base NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS social_security NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS projected_annual_income NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tax_expense_deduction NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tax_allowance_total NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS taxable_annual_income NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS annual_tax NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS withholding_tax NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS student_loan_deduction NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS legal_execution_deduction NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS other_post_tax_deductions NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS calculation_note TEXT;

CREATE INDEX IF NOT EXISTS idx_payroll_period_status
    ON hr.payroll_period(status, payroll_month DESC);

CREATE INDEX IF NOT EXISTS idx_payroll_line_period
    ON hr.payroll_line(period_id, employee_id);
