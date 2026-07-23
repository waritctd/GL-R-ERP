SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- LEAVE -> PAYROLL: cancel-after-close AUTO-REFUND (2026-07-23)
-- ---------------------------------------------------------------------
--
-- Owner decision (2026-07-23): the V85 record-and-surface design for hr.leave_payroll_correction
-- was not enough -- a correction sitting there forever, only visible as a suggestion HR might net
-- in by hand, is money the employee is owed and might never actually get back. This migration adds
-- the two persisted columns PayrollService#process needs to actually apply the credit; the
-- resolution bookkeeping itself (resolved_at / resolved_payroll_period_id) already exists from V85
-- and is untouched here.
--
-- MECHANISM (see PayrollCalculator/PayrollService/LeaveRepository for the full implementation):
-- refund = base/30 x SUM(unpaid_days_to_refund) for an employee's still-pending corrections, added
-- back as a PRE-TAX credit through the exact same path unpaidLeaveDeduction subtracts through
-- (grossTaxableIncome, ssoWageBase, totalDeductions) -- so tax and SSO recompute on the restored
-- income, not just net pay going up by a flat amount. Applied automatically on PROCESS (HR does not
-- type a number in); surfaced on PREVIEW too so HR sees it before committing. Idempotent: see
-- LeaveRepository#findRefundableUnpaidDaysByEmployee / #resolvePendingCorrections for exactly how
-- re-processing the same month re-includes that period's own prior resolutions without ever
-- double-counting a correction resolved by a DIFFERENT period.
--
-- TAX-TIMING NUANCE (flagged for HR/legal, same as V85's caveat): the refund is taxed/SSO'd as
-- THIS payroll month's income, at this month's marginal rate and against this month's SSO ceiling
-- headroom -- not a retroactive correction to the ORIGINAL month's tax return. If the original
-- over-deduction and the refund land in different tax months (routine here, since the refund can
-- only happen the month AFTER a cancellation), the employee's total annual tax across the two
-- months is not guaranteed to exactly equal what it would have been had the deduction never
-- happened -- e.g. if withholding for the original month was already reported/filed, or if the
-- refund month's marginal bracket differs from the deduction month's. This is standard "correct
-- the current period" treatment (the same convention Thai monthly PND1 withholding generally uses
-- for in-year corrections), not a special-case bug, but it is NOT the same as re-opening and
-- refiling the original month -- confirm this convention is acceptable before this drives a real
-- payroll run.
ALTER TABLE hr.payroll_line
    ADD COLUMN leave_refund_days      NUMERIC(6,2)  NOT NULL DEFAULT 0,
    ADD COLUMN leave_deduction_refund NUMERIC(12,2) NOT NULL DEFAULT 0;
