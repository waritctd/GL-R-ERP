SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- HR override for withholding tax (ภาษีหัก ณ ที่จ่าย), two layers, "Both"
-- pattern mirroring the V73 tax-allowance design. Used for edge cases such
-- as a director whose withheld tax is a fixed agreed figure rather than the
-- engine's progressive-tax projection.
--
-- GUARDRAIL: this override does NOT change the progressive tax computation.
-- annual_tax / taxable_annual_income / projected_annual_income are still
-- computed and reported exactly as before. The override only SUBSTITUTES the
-- final withheld amount stored in payroll_line.withholding_tax (and therefore
-- deductions / net_amount). See PayrollCalculator for the substitution point.
--
-- Precedence (implemented in PayrollService#calculateLine):
--   per-run override  (payroll_line.withholding_tax_override, HR-typed that run)
--     else standing    (employee.withholding_tax_override)
--       else computed   (the engine's projected withholding_tax)
-- NULL everywhere = compute normally (today's behaviour, unchanged).
-- ---------------------------------------------------------------------

-- Layer 1: standing per-employee default. Set once, applies every month until
-- cleared. NULL = no standing override (compute normally). Deliberately NOT
-- given a NOT NULL / DEFAULT 0 -- unlike director_remuneration, zero is a
-- MEANINGFUL override here (withhold nothing), so NULL must stay distinct from 0.
ALTER TABLE hr.employee
    ADD COLUMN IF NOT EXISTS withholding_tax_override NUMERIC(12,2) NULL;

ALTER TABLE hr.employee
    DROP CONSTRAINT IF EXISTS chk_employee_withholding_tax_override_non_negative,
    ADD CONSTRAINT chk_employee_withholding_tax_override_non_negative
        CHECK (withholding_tax_override IS NULL OR withholding_tax_override >= 0);

-- Layer 2: per-run override HR types on the payroll page for a single run. Stored
-- so it carries forward to the next month like the other recurring inputs. This
-- column holds ONLY the HR-typed per-run value (nullable); the effective withheld
-- amount continues to live in the existing withholding_tax column. NULL = no
-- per-run override this run (fall back to the employee standing value, else compute).
ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS withholding_tax_override NUMERIC(12,2) NULL;

ALTER TABLE hr.payroll_line
    DROP CONSTRAINT IF EXISTS chk_payroll_line_withholding_tax_override_non_negative,
    ADD CONSTRAINT chk_payroll_line_withholding_tax_override_non_negative
        CHECK (withholding_tax_override IS NULL OR withholding_tax_override >= 0);
