SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- ป.96/2543 withholding compliance (2026-07-28)
--
-- คำสั่งกรมสรรพากร ที่ ป.96/2543, as restated in the คำชี้แจง printed on แบบ ภ.ง.ด.1, splits
-- employment income into two limbs that are withheld on under DIFFERENT rules:
--
--   ข้อ 2.1  เงินได้ที่จ่ายตามปกติ      -- annualised by x จำนวนคราวที่ต้องจ่าย (x12 monthly),
--                                          taxed under มาตรา 48(1), divided back over the periods.
--   ข้อ 2.5  เงินพิเศษที่จ่ายเป็นครั้งคราว -- ค่าล่วงเวลา, เงินโบนัส and the like. Taken at its ACTUAL
--                                          amount (x the number of times THAT payment is made, which
--                                          is 1 for an annual bonus), added to the annualised regular
--                                          income, retaxed, and the DIFFERENCE withheld in the period
--                                          the เงินพิเศษ is paid.
--
-- The engine previously annualised BOTH limbs together, multiplying one-off pay by the months
-- remaining in the year. A June bonus was projected as if it recurred every month to December.
--
-- These columns let the year-to-date carry-forward keep the limbs apart. It has to: next period's
-- ข้อ 2.5 difference is netted against what was already withheld on the VARIABLE limb specifically,
-- and that figure cannot be recovered once the two are summed into one withholding_tax column.
-- ---------------------------------------------------------------------

ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS regular_taxable_income   NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS variable_taxable_income  NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS regular_withholding_tax  NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS variable_withholding_tax NUMERIC(14,2) NOT NULL DEFAULT 0;

-- hr.payroll_year_to_date_seed deliberately gets NO split columns.
--
-- It holds pre-system history back-loaded at go-live from the accountant's records: figures produced
-- BEFORE this engine existed, and therefore before anything was split. HR types one taxable-income
-- total and one withholding total, which is all the source records contain. Adding split columns
-- would create a pair of fields nothing populates, and a seed row entered after this migration would
-- read back as zero year-to-date — silently under-withholding the very employees being back-loaded.
--
-- Instead the reader maps taxable_income -> regular limb and withholding_tax -> regular limb, with a
-- zero variable limb. That is not a fallback, it is the truth: those months were computed by the
-- single-limb method, which annualised every baht as regular pay. See
-- PayrollRepository#findYearToDateByEmployee.

-- Backfill. This is an APPROXIMATION and is deliberately recorded as one.
--
-- History was never split, so there is no way to recover how much of a past month's withholding was
-- attributable to OT or a bonus. Attributing everything to the REGULAR limb is not a guess, though:
-- it is what actually happened. Months processed before this migration annualised every baht as if
-- it were regular pay, so "regular" is a truthful description of the arithmetic that produced them.
--
-- Consequence, which the handoff spells out: for an employee who was paid OT/commission/bonus
-- earlier in 2026, the first post-migration run sees a variable limb of zero year-to-date and will
-- therefore withhold the FULL ข้อ 2.5 difference on their cumulative เงินพิเศษ, without credit for
-- what the old formula already over-withheld on it. The 2026 months already PROCESSED are being
-- recomputed and re-filed (ภ.ง.ด.1 เพิ่มเติม) precisely so that this backfill is replaced by real
-- split figures rather than relied upon.
UPDATE hr.payroll_line
   SET regular_taxable_income  = gross_taxable_income,
       regular_withholding_tax = withholding_tax
 WHERE regular_taxable_income = 0
   AND regular_withholding_tax = 0;

COMMENT ON COLUMN hr.payroll_line.regular_taxable_income IS
  'ป.96/2543 ข้อ 2.1 limb: salary + พิเศษ 6 (คอมมิชชั่น) + commission_pay + director remuneration, net of pre-tax deductions. Annualised by x จำนวนคราวที่ต้องจ่าย. Commission is here because sales earn it every คราว (owner-confirmed) -- only the amount varies.';
COMMENT ON COLUMN hr.payroll_line.variable_taxable_income IS
  'ป.96/2543 ข้อ 2.5 limb: ค่าล่วงเวลา + พิเศษ 1-5, 7, 8 + bonus_pay + other_one_off_pay. All occasional -- owner-confirmed that พิเศษ 1-8 other than 6 are subject to change per employee per month. Never annualised; taxed as the difference between the tax with it and without it.';
COMMENT ON COLUMN hr.payroll_line.regular_withholding_tax IS
  'Portion of withholding_tax attributable to the ข้อ 2.1 regular limb. regular + variable = withholding_tax, always.';
COMMENT ON COLUMN hr.payroll_line.variable_withholding_tax IS
  'Portion of withholding_tax attributable to the ข้อ 2.5 เงินพิเศษ limb. Netted against next period''s ข้อ 2.5 difference.';
