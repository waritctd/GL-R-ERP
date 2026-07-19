-- Activates hr.attendance_daily, which V7 created but nothing has ever written to.
--
-- NUMBERING — why this is V60 and not V55.
--
-- Authored as V55 because the repo tip was V54. But the hosted database was already at V55
-- ("quotation doc terms", applied 2026-07-18 from a branch that had not merged), so Flyway saw
-- version 55 as applied and — with validate-on-migrate off on the prod profile — skipped this file
-- silently. The index and comments below were never created and nothing failed loudly.
--
-- V56–V59 are already claimed by the in-flight sales branches (close_verification, cancel_reason,
-- audit_trail_integrity, pricing_request_foundation), so taking any of those would collide the
-- moment they merge. V60 was the first version free against all three at once: current main, both
-- deployed databases, and every open branch.
--
-- Two rules follow from this, and the gap at V55 in this repo is deliberate evidence of both:
--   1. Pick the number from what is APPLIED (hr.flyway_schema_history per environment), not from
--      the highest file in the repo — branches deploy ahead of main in this project.
--   2. Also check every open branch, not just main, or you collide on merge instead of on deploy.
--
-- No structural change: every column this feature needs already exists on the V7 table, and the
-- constraints there (ux_attendance_daily_employee_date, chk_attendance_daily_checkout_after_checkin,
-- site_code NOT NULL) are all satisfiable by the derivation rules documented below. This migration
-- records those rules next to the data and adds the index the day-range read needs.

COMMENT ON TABLE hr.attendance_daily IS
    'Per-employee, per-day roll-up of hr.attendance_punch, derived by AttendanceDailyCalculator. '
    'Rows exist only for days that have at least one punch — absence is derived at read time by '
    'LEFT JOINing generate_series over the requested range, because a punchless day has no '
    'site_code to store. Never write to this table from SQL: the derivation rules live in Java so '
    'there is exactly one implementation of them.';

-- The constraint that outranks every other consideration here.
COMMENT ON COLUMN hr.attendance_daily.late_minutes IS
    'REPORTING ONLY. Thai Labour Protection Act §76 forbids deducting wages as a penalty for '
    'lateness or absence (up to 6 months imprisonment / THB 100,000 fine). This column must never '
    'feed a payroll deduction. Measured from the scheduled start time, not from the end of the '
    'grace period: with an 08:30 start and 5-minute grace, 08:34 is 0 and 08:40 is 10 — not 4.';

COMMENT ON COLUMN hr.attendance_daily.early_leave_minutes IS
    'REPORTING ONLY — see late_minutes. Thai Labour Protection Act §76 forbids deducting wages as '
    'a penalty for leaving early. Measured from the scheduled end time. Stays 0 when there is no '
    'check-out, because a missing scan is a data-quality problem and must not be imputed.';

COMMENT ON COLUMN hr.attendance_daily.overtime_minutes IS
    'Sourced only from hr.overtime_request rows in status APPROVED. MANAGER_APPROVED does not '
    'count: V34 made CEO approval the second half of a dual-approval gate, and counting the '
    'half-approved state would leak un-finalised overtime into a pay-relevant figure. This is the '
    'only pay-relevant column on this table and it only ever increases pay.';

COMMENT ON COLUMN hr.attendance_daily.check_in IS
    'MIN(punch_time) for the day. attendance_punch.punch_state is documented as unreliable for '
    'IN/OUT direction, so direction is decided by chronology alone. NULL when the only punch fell '
    'after the midpoint of the working window, i.e. the arrival scan is missing.';

COMMENT ON COLUMN hr.attendance_daily.check_out IS
    'MAX(punch_time) for the day; NULL when the only punch fell at or before the midpoint of the '
    'working window. Using MIN/MAX also satisfies chk_attendance_daily_checkout_after_checkin by '
    'construction rather than by luck.';

COMMENT ON COLUMN hr.attendance_daily.site_code IS
    'Where the day started: the check-in punch''s site, or the check-out punch''s when only an '
    'afternoon scan exists. A day''s punches may span sites; per-scan detail stays in '
    'hr.attendance_punch, which is its source of truth.';

COMMENT ON COLUMN hr.attendance_daily.is_manual_override IS
    'Set when HR has corrected the row by hand. Every automated write path guards on this in the '
    'ON CONFLICT ... DO UPDATE ... WHERE clause, so recalculation cannot clobber a human decision.';

COMMENT ON COLUMN hr.attendance_daily.is_absent IS
    'Always FALSE for stored rows — a row only exists when the day had punches. Absence is the '
    'lack of a row. Retained because DashboardRepository filters on is_absent = FALSE.';

-- V7 already indexes (work_date, site_code) and (employee_id, work_date DESC). The day view reads a
-- date range across many employees, so lead on work_date with employee_id as the tiebreaker.
CREATE INDEX IF NOT EXISTS idx_attendance_daily_date_employee
    ON hr.attendance_daily(work_date, employee_id);
