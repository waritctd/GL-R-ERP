-- V123: persist the UNCAPPED SSO wage base so the สปส.1-10 export can report the real เงินค่าจ้างทั้งสิ้น.
--
-- WHY. PayrollCalculator already computes the uncapped sum over SSO-included components
-- (ssoWageBaseRaw, PayrollCalculator#calculateClassified) and then throws it away -- only the
-- CAPPED value (clamped to [1,650, 17,500]) is persisted to payroll_line.sso_wage_base. GL&R's
-- actual June 2026 filing reports ค่าจ้าง as the wage ACTUALLY PAID, uncapped (one employee shows
-- ค่าจ้าง 124,849 with เงินสมทบ 875): the capped figure understates every employee at the ceiling,
-- which is most of the workforce. See SsoExporter's javadoc for the full defect writeup.
--
-- This migration does NOT touch payroll_line.sso_wage_base or payroll_line.social_security, and
-- does not change how either is computed -- PayrollCalculator's contribution math is explicitly out
-- of scope for this branch (see the PR body). It only adds a place to persist a number the
-- calculator was already computing.
--
-- NULLABLE, ON PURPOSE. NULL means "not recorded" (every row processed before this migration, plus
-- any future row this backfill cannot safely reconstruct) and is distinguishable from a genuine
-- ฿0.00 wage. SsoExporter falls back to sso_wage_base when this is NULL, so historical periods keep
-- exporting exactly as before rather than reporting ฿0.

ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS sso_wage_gross NUMERIC(12,2);

-- NB: COMMENT ON ... IS takes a string LITERAL, not an expression -- a '||'-concatenated comment is
-- a syntax error in Postgres, so this stays one literal however long it gets.
COMMENT ON COLUMN hr.payroll_line.sso_wage_gross IS
  'Uncapped SSO wage base actually paid (PayrollCalculator''s ssoWageBaseRaw, pre-[1650,17500] clamp) -- the real เงินค่าจ้างทั้งสิ้น for the สปส.1-10 file. NULL = not recorded; SsoExporter falls back to sso_wage_base.';

-- ---------------------------------------------------------------------------------------------
-- Backfill, best-effort, conservative.
--
-- Mirrors PayrollCalculator#calculateClassified's own computation: for each payroll_line row, sum
-- the payroll_line columns of every PayrollComponent whose hr.payroll_component_sso_inclusion row
-- (is_included = TRUE) resolves for that employee/period, with the SAME "closest tax_year <= the
-- period's year" resolution PayrollRepository#findComponentSsoInclusionByEmployee uses in
-- production -- the effective tax_year is resolved PER EMPLOYEE (MAX across every component's
-- tax_year for that employee, not per component; that narrower-than-ideal resolution is the
-- existing, documented behaviour, not something this migration changes) -- and the SALARY
-- component gets the same (base_salary - unpaid_leave_deduction + leave_deduction_refund)
-- adjustment the calculator applies. The component -> column mapping below is read from
-- hr.payroll_component_sso_inclusion itself (a CASE over the component TEXT), not a hardcoded
-- "these nine components" list, so it stays correct if the included set ever changes.
--
-- WHAT THIS DOES NOT COVER, ON PURPOSE (left NULL, covered by SsoExporter's fallback):
--   * An employee/period with ZERO hr.payroll_component_sso_inclusion rows at any tax_year <= the
--     period's year. Production falls back to a default map in that case (PayrollService
--     #ssoInclusionFor / PayrollRepository#defaultSsoIncluded, "every component TRUE except
--     DIRECTOR_REMUNERATION and NON_TAXABLE_INCOME"); reproducing that default here would risk
--     silently fabricating a wage figure for an employee whose real inclusion history this
--     migration cannot see. Left NULL instead.
--   * A period recorded before the unpaid_leave_deduction/leave_deduction_refund/special_pay_*
--     columns existed, where those columns are NULL rather than 0 -- COALESCEd to 0 below, which is
--     correct UNLESS a genuinely non-zero historical adjustment was lost when those columns were
--     added; no such case is known, but flagging the assumption here.
-- ---------------------------------------------------------------------------------------------
WITH effective_year AS (
    SELECT pl.line_id,
           pl.employee_id,
           MAX(s.tax_year) AS effective_tax_year
      FROM hr.payroll_line pl
      JOIN hr.payroll_period pp ON pp.period_id = pl.period_id
      JOIN hr.payroll_component_sso_inclusion s
        ON s.employee_id = pl.employee_id
       AND s.tax_year <= EXTRACT(YEAR FROM pp.payroll_month)::smallint
     GROUP BY pl.line_id, pl.employee_id
),
included_sum AS (
    SELECT ey.line_id,
           SUM(
               CASE s.component
                   WHEN 'SALARY' THEN
                       COALESCE(pl.base_salary, 0)
                       - COALESCE(pl.unpaid_leave_deduction, 0)
                       + COALESCE(pl.leave_deduction_refund, 0)
                   WHEN 'SPECIAL_PAY_1' THEN COALESCE(pl.special_pay_1, 0)
                   WHEN 'SPECIAL_PAY_2' THEN COALESCE(pl.special_pay_2, 0)
                   WHEN 'SPECIAL_PAY_3' THEN COALESCE(pl.special_pay_3, 0)
                   WHEN 'SPECIAL_PAY_4' THEN COALESCE(pl.special_pay_4, 0)
                   WHEN 'SPECIAL_PAY_5' THEN COALESCE(pl.special_pay_5, 0)
                   WHEN 'SPECIAL_PAY_6' THEN COALESCE(pl.special_pay_6, 0)
                   WHEN 'SPECIAL_PAY_7' THEN COALESCE(pl.special_pay_7, 0)
                   WHEN 'SPECIAL_PAY_8' THEN COALESCE(pl.special_pay_8, 0)
                   WHEN 'SPECIAL_PAY_9' THEN COALESCE(pl.special_pay_9, 0)
                   WHEN 'OVERTIME_PAY' THEN COALESCE(pl.overtime_pay, 0)
                   WHEN 'COMMISSION_PAY' THEN COALESCE(pl.commission_pay, 0)
                   WHEN 'MEAL_ALLOWANCE' THEN COALESCE(pl.meal_allowance, 0)
                   WHEN 'PER_DIEM_TAXABLE' THEN COALESCE(pl.per_diem_taxable, 0)
                   WHEN 'BONUS_PAY' THEN COALESCE(pl.bonus_pay, 0)
                   WHEN 'OTHER_ONE_OFF_PAY' THEN COALESCE(pl.other_one_off_pay, 0)
                   WHEN 'DIRECTOR_REMUNERATION' THEN COALESCE(pl.director_remuneration, 0)
                   WHEN 'NON_TAXABLE_INCOME' THEN COALESCE(pl.non_taxable_income, 0)
                   ELSE 0
               END
           ) AS wage_gross
      FROM effective_year ey
      JOIN hr.payroll_line pl ON pl.line_id = ey.line_id
      JOIN hr.payroll_component_sso_inclusion s
        ON s.employee_id = ey.employee_id
       AND s.tax_year = ey.effective_tax_year
       AND s.is_included = TRUE
     GROUP BY ey.line_id
)
UPDATE hr.payroll_line pl
   SET sso_wage_gross = included_sum.wage_gross
  FROM included_sum
 WHERE pl.line_id = included_sum.line_id
   AND pl.sso_wage_gross IS NULL;
