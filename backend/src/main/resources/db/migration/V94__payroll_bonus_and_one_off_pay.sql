SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- Dedicated one-off pay fields (2026-07-29)
--
-- Owner, asked where an annual bonus is entered today: "one of พิเศษ 1-5", and separately
-- "bonus, อื่นๆ should be a separate field".
--
-- Why it matters beyond tidiness. ป.96/2543 taxes เงินพิเศษที่จ่ายเป็นครั้งคราว (ข้อ 2.5) differently
-- from เงินได้ที่จ่ายตามปกติ (ข้อ 2.1), so the engine has to know which a payment is. It was reading
-- that off the SLOT NUMBER, and no slot rule can be right when one slot carries a monthly allowance in
-- most months and a bonus in others. Giving one-off money its own field ends the ambiguity at the
-- point of entry instead of guessing at it afterwards.
--
-- The tax treatment of these two columns is the same as พิเศษ 1-5/7/8 today (all ข้อ 2.5), so this
-- migration changes no employee's tax on its own. What it changes is that a bonus is now identifiable
-- as a bonus -- on the payslip, in the ภ.ง.ด.1 working papers, and to the next person who has to
-- classify it.
-- ---------------------------------------------------------------------

ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS bonus_pay              NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS other_one_off_pay      NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS excess_withheld_to_date NUMERIC(14,2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN hr.payroll_line.excess_withheld_to_date IS
  'Withholding already remitted this tax year IN EXCESS of the year''s assessed liability, as at this period. Normally zero. Payroll cannot return it -- tax remitted to the RD is reclaimed by the employee on their ภ.ง.ด.91 -- so it is recorded and printed on the payslip rather than left as a silent 0.00 withholding line.';

COMMENT ON COLUMN hr.payroll_line.bonus_pay IS
  'เงินโบนัส. ป.96/2543 ข้อ 2.5 names เงินโบนัส explicitly as เงินพิเศษที่จ่ายเป็นครั้งคราว: taken at its actual amount (x the number of times it is paid, 1 for an annual bonus) and taxed as the DIFFERENCE it makes to the annual tax, never multiplied across the remaining periods.';
COMMENT ON COLUMN hr.payroll_line.other_one_off_pay IS
  'อื่นๆ -- one-off pay that is neither a bonus nor one of the พิเศษ slots. Same ข้อ 2.5 treatment as bonus_pay.';

-- No backfill. Historic bonuses are indistinguishable from allowances inside special_pay_1..5 -- that
-- is the very problem this column exists to end -- and inventing a split would be a guess written into
-- the ledger. Both columns start at zero and are populated from the first run after this migration.
