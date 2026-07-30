SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- D1 fix (fourth reachability audit, 2026-07-30) -- customer_return_deduction stopped round-tripping
-- what HR actually typed, a REGRESSION against origin/main.
--
-- On origin/main (the pre-this-branch legacy engine) customer_return_deduction was passed straight
-- through verbatim and always echoed back what HR entered. This branch's calculateClassified
-- (V96/task 2) gave the column a SECOND job: when customerReturnAlreadyEarned is FALSE (the unearned
-- path), the amount is netted OUT of commission_pay pre-tax, and PayrollCalculator.java's
-- customerReturnDeduction local is deliberately zeroed for that path so it is not ALSO applied as a
-- post-tax deduction (which would double-count against the pre-tax netting already done). That zeroing
-- is correct arithmetic, but the persisted payroll_line.customer_return_deduction column is now used
-- for two different things -- "what HR entered" and "what was actually deducted post-tax" -- and only
-- the second survives a reload:
--
--   1. HR enters หักลูกค้าคืนสินค้า = ฿3,000, leaves customerReturnAlreadyEarned unticked, processes.
--      Commission is correctly netted pre-tax.
--   2. The stored line's customer_return_deduction is 0 (correct for the post-tax bookkeeping, but it
--      is now the ONLY place the entered amount could have round-tripped from).
--   3. Reload -> PayrollPage.jsx's adjustmentFromLine reads customer_return_deduction: the amount is
--      gone from the form, and the checkbox (rendered only once the field is > 0) disappears with it.
--   4. Reprocess the same month -> the netting is silently NOT re-applied. Commission returns to
--      gross and net pay changes with no HR action.
--
-- Fixed by separating "what HR entered" from "what was actually deducted post-tax" into two columns.
-- customer_return_deduction keeps its existing post-tax-bookkeeping meaning (0 for the unearned path,
-- the full amount for the already-earned path) -- unchanged, so the already-earned path's arithmetic
-- and the payslip are untouched. This new column always echoes the raw request value, regardless of
-- the earned flag, so the form (and the checkbox's `> 0` render gate) survive a reload and a reprocess
-- of the same month is idempotent.
-- ---------------------------------------------------------------------
ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS customer_return_requested NUMERIC(12,2) NOT NULL DEFAULT 0;

ALTER TABLE hr.payroll_line
    DROP CONSTRAINT IF EXISTS chk_payroll_line_customer_return_requested_non_negative,
    ADD CONSTRAINT chk_payroll_line_customer_return_requested_non_negative CHECK (customer_return_requested >= 0);

COMMENT ON COLUMN hr.payroll_line.customer_return_requested IS
  'The หักลูกค้าคืนสินค้า amount HR actually typed this run, verbatim, regardless of customer_return_already_earned. Distinct from customer_return_deduction, which is the POST-TAX bookkeeping figure (0 in the unearned path, where the amount is instead netted pre-tax out of commission_pay). Exists so the form field and its checkbox round-trip after a reload -- see this migration''s own comment for the regression this closes.';

-- Backfill for every already-processed line: the best available reconstruction of "what was entered"
-- is customer_return_deduction itself (correct for the already-earned path, where the two always
-- agreed; a reasonable if imperfect approximation for the unearned path, where the true entered figure
-- was never separately persisted before this migration -- there is nothing more precise to recover it
-- from).
UPDATE hr.payroll_line
   SET customer_return_requested = customer_return_deduction
 WHERE customer_return_requested = 0
   AND customer_return_deduction <> 0;
