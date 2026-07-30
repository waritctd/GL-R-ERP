SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- Daily-rate (pay_type = 'D') payroll support (2026-07-30, owner decision).
--
-- hr.employee.pay_type ('M'/'D') has existed since V1 but the payroll engine never read it:
-- PayrollService put employee.current_salary straight into PayrollComponent.SALARY as if it were
-- always a monthly figure. For a pay_type = 'D' employee current_salary is a PER-DAY rate, not a
-- monthly one, so that employee was paid their DAY RATE as their MONTHLY salary.
--
-- Verified in production: exactly one active employee is affected today -- employee_id 203
-- (code 10060, ฿450/day). Run as-is, July would pay them ฿450 instead of the accountant's ฿11,250
-- (25 days x ฿450), with tax and SSO computed on the wrong base too.
--
-- Fixed in PayrollService#calculateLine / PayrollCalculator#calculateClassified: the SALARY
-- component for a daily-rate employee is now current_salary (the rate) multiplied by HR-entered
-- days_worked, and the true per-day rate (not gross/30) feeds unpaidLeaveDeduction/
-- leaveDeductionRefund via PayrollClassifiedCalculationDtos#dailyRateOverride.
--
-- days_worked is HR-typed PER RUN, like bonusPay/otherOneOffPay -- a one-off figure specific to the
-- month worked, not a recurring one, so it is not part of the special-pay carry-forward. Persisted
-- here (rather than only held in memory) so a processed line can be re-read and re-derived exactly,
-- and so the value survives a reload of the payroll page. NULL for every monthly employee and for
-- any pre-existing processed line (all predate this column, and all but employee 203's 32 other
-- 'D' rows are inactive -- see the migration's own comment below on why that stale data is left
-- alone).
--
-- Known risk, recorded rather than silently fixed: 33 employees carry pay_type = 'D' in total.
-- Only employee 203 is ACTIVE and reaches payroll. Of the 32 inactive 'D' rows, current_salary is
-- NOT internally consistent with "day rate": 20 hold sub-฿1,000 values that look like real day
-- rates, 5 hold ฿6,000-9,100 that look like monthly figures mislabelled 'D', and 8 are zero. None
-- of them are payroll-affecting today (findActiveEmployees filters on is_active), so this migration
-- does not attempt to reclassify or backfill that data -- doing so without accountant sign-off per
-- employee would be a guess, and a wrong guess here is exactly the failure mode this feature exists
-- to prevent. Revisit if any of the 32 is ever reactivated.
--
-- Opus review fixes (2026-07-30), applied here because V103 has never been applied anywhere (main
-- and every worktree/UAT are behind it) -- edited in place rather than adding V104, per the owner's
-- explicit instruction:
--
--   Finding 4 (upper bound on days_worked): @PositiveOrZero alone let a calendar-impossible day
--   count (e.g. 45 in a 31-day month) through unblocked, and NUMERIC(6,2)'s own precision limit
--   means a genuine typo (a 5-digit value >= 10000.00) raises a raw PG 22003 (numeric field overflow)
--   that surfaces as a bare 500 for the whole run. PayrollService now refuses > 31 with a clean Thai
--   400 before any INSERT is attempted (mirroring the existing per-diem-basis guard); this CHECK is
--   the DB-level backstop for any path that reaches this table without going through that guard.
--
--   Finding 7 (stale mislabelled 'D' rows, made dangerous by this feature): before this branch, a
--   'D' employee's current_salary was paid AS-IS regardless of days worked, so those 5 inactive
--   rows (current_salary >= ฿6,000, clearly monthly figures mislabelled 'D') would reactivate to a
--   plausible-looking monthly amount. After this fix, current_salary is now multiplied by
--   days_worked -- reactivating one of those 5 rows unmodified would pay e.g. ฿6,000 x 25 =
--   ฿150,000/month. Flip ONLY those 5 rows to 'M' now, while they are still inactive and therefore
--   provably safe to correct (see findActiveEmployees' is_active filter): the 20 sub-฿1,000 rows are
--   genuinely daily and must NOT be touched, and the 8 zero rows are payroll-inert either way.
-- ---------------------------------------------------------------------

ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS days_worked NUMERIC(6,2);

-- Finding 4: bound days_worked to a calendar-plausible range (0..31) as well as non-negative. 31
-- covers every real month length; a fractional day (e.g. 21.5) is still a genuine daily-rate figure
-- (NUMERIC(6,2) keeps the scale), so the bound is on the whole-number range, not on decimals.
ALTER TABLE hr.payroll_line
    DROP CONSTRAINT IF EXISTS chk_payroll_line_days_worked_non_negative,
    ADD CONSTRAINT chk_payroll_line_days_worked_range CHECK (days_worked IS NULL OR (days_worked >= 0 AND days_worked <= 31));

COMMENT ON COLUMN hr.payroll_line.days_worked IS
    'Days worked this period, HR-typed. Only meaningful for a pay_type = ''D'' (daily-rate) employee -- PayrollComponent.SALARY for such an employee is hr.employee.current_salary (the day rate) multiplied by this figure. NULL for a monthly employee, and for any line processed before this column existed. Bounded to 0..31 (chk_payroll_line_days_worked_range) -- see this migration''s own header comment for the Finding 4 rationale.';

-- Finding 3: pay_type was read live from hr.employee at display time (findLines' e.pay_type join),
-- so a later edit to the employee's pay_type retroactively changed how an ALREADY-PROCESSED
-- historical line reports itself -- the days-worked field would vanish from the UI (payType no
-- longer 'D') while days_worked stayed on the row, and a re-process would silently recompute SALARY
-- as the bare rate again, reintroducing the exact bug this migration fixes. Freeze pay_type on the
-- line at processing time instead, the same pattern hr.overtime_request.salary_basis already uses
-- for an identical reason. NULL for every line processed before this column existed (findLines falls
-- back to nothing meaningful for those -- they predate pay_type-aware payroll entirely).
ALTER TABLE hr.payroll_line
    ADD COLUMN IF NOT EXISTS pay_type CHAR(1);

COMMENT ON COLUMN hr.payroll_line.pay_type IS
    'hr.employee.pay_type (''M''/''D''/NULL) AS OF THE RUN THAT PROCESSED THIS LINE -- frozen here (mirroring hr.overtime_request.salary_basis) so a later edit to the employee''s current pay_type cannot retroactively change how an already-processed historical line reports itself. NULL for any line processed before this column existed.';

-- Finding 7 cleanup: exactly 5 inactive pay_type = 'D' rows whose current_salary (>= ฿6,000) is
-- internally inconsistent with a real day rate -- see this migration's header comment. Guarded so it
-- can only ever touch an INACTIVE 'D' row already >= ฿5,000 (a threshold safely below the observed
-- ฿6,000-9,100 band and safely above the 20 genuine sub-฿1,000 day-rate rows, so neither population
-- can cross it); an active employee is categorically excluded regardless of salary.
UPDATE hr.employee
   SET pay_type = 'M'
 WHERE is_active = FALSE
   AND pay_type = 'D'
   AND current_salary >= 5000;
