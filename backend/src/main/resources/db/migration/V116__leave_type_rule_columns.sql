SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- LEAVE: per-leave-type rule columns from the §5 company announcement
-- ("วันเวลาทำงาน และการหยุดงาน", effective 1 Oct 2567/2024, supersedes 2561/2018)
-- ---------------------------------------------------------------------
--
-- Slice 1 of the leave-rules-as-data rework: turns the declarative, per-type parts of §5 into
-- columns on hr.leave_type instead of a mix of hardcoded LeaveService constants (the old
-- SICK/PERSONAL/VACATION-only autoRejectNote branches) and the single global
-- app.leave.advance-notice-days=7 property (wrong for every type except VACATION's neighbourhood).
-- LeaveService (Slice 2, same branch) reads these columns; this migration only adds the columns
-- and seeds them. Deliberately NOT here: pro-ration by tenure (§5.2/§5.3), vacation carry-forward
-- (§5.3.5), department-coverage/adjacency/post-resignation rules (§5.3.2-4), and the "<=3
-- times/month" tolerance counters (§5.1/§5.2) -- those are later branches.
--
-- All five columns are nullable-or-defaulted so every pre-existing row keeps today's behaviour
-- unless a later statement in this same migration explicitly sets it.
ALTER TABLE hr.leave_type
    ADD COLUMN paid_days_cap        NUMERIC(5,2),
    ADD COLUMN advance_notice_days  SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN min_service_months   SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN max_consecutive_days NUMERIC(5,2),
    ADD COLUMN once_per_employment  BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN hr.leave_type.paid_days_cap IS
    'Max days of this type''s quota that are ever PAID in a year; NULL = every quota day is paid '
    '(today''s behaviour). E.g. MATERNITY: 98-day quota, 45-day paid cap (§5.4) -- a 98-day request '
    'splits 45 paid / 53 unpaid regardless of how much of the 98-day quota remains.';
COMMENT ON COLUMN hr.leave_type.advance_notice_days IS
    'Working days'' notice required for a request of this type to be paid. Replaces the single '
    'global app.leave.advance-notice-days (=7), which was wrong for every type except roughly '
    'VACATION''s neighbourhood -- SICK and most new types need none, PERSONAL needs 1, VACATION '
    'needs 3 (§5.1-5.3).';
COMMENT ON COLUMN hr.leave_type.min_service_months IS
    'Minimum completed months of service (from hr.employee.hire_date) before an employee is '
    'eligible for this leave type at all. 0 = no minimum.';
COMMENT ON COLUMN hr.leave_type.max_consecutive_days IS
    'Max days a single request of this type may span; NULL = unlimited. E.g. PERSONAL: 3 '
    'consecutive days (§5.2, "ไม่เกิน 3 วันติดต่อกัน").';
COMMENT ON COLUMN hr.leave_type.once_per_employment IS
    'TRUE if this leave type may be granted at most once over the whole employment, not once per '
    'year (§5.6 ORDINATION, "ใช้ได้เพียงครั้งเดียวตลอดระยะเวลาที่เป็นพนักงาน"). Enforced in Java '
    '(LeaveService) AND in the DB (see ux_leave_once_per_employment below) -- a Java-only check is '
    'not race-proof under concurrent submissions, matching the precedent in '
    'V66__special_money_request_schema.sql''s ux_smr_once_per_lifetime for the identical wedding/'
    'ordination-aid rule.';

-- ---------------------------------------------------------------------
-- Existing rows: explicit UPDATEs (not relying on the ADD COLUMN defaults reading right by
-- accident) so every value below is a legible, intentional statement of company policy.
-- ---------------------------------------------------------------------

-- 5.1 SICK: <=30 days/year paid in full (no cap below the quota itself), beyond 30 is unpaid --
-- LeaveService's existing paid/unpaid split off annual_quota_days already gives this with
-- paid_days_cap NULL. No advance notice required (illness is not plannable); no minimum service;
-- no consecutive-day cap; not once-per-employment.
UPDATE hr.leave_type
   SET paid_days_cap = NULL,
       advance_notice_days = 0,
       min_service_months = 0,
       max_consecutive_days = NULL,
       once_per_employment = FALSE,
       updated_at = now()
 WHERE leave_type_code = 'SICK';

-- 5.2 PERSONAL: 7 days/year (already corrected to 7.00 by V90), fully paid within quota, must be
-- filed 1 working day ahead, "not more than 3 consecutive days".
--
-- ASSUMPTION (owner confirmation pending): the announcement grants this right to employees "who
-- have passed probation" (พนักงานที่ผ่านทดลองงานแล้ว) but never states the probation length, and
-- hr.employee has no probation/confirmation-date column to derive it from. min_service_months = 4
-- stands in for the common Thai 119-day probation period (see
-- SpecialMoneyPolicyEvaluator.DEFAULT_PROBATION_DAYS, the same company convention already used for
-- special-money eligibility), rounded up to whole months (119 days ~= 3.9 months -> 4). This is a
-- best-effort placeholder, NOT a stated company rule -- flag for HR/legal sign-off before it gates a
-- real employee's PERSONAL leave. Do not add a probation-length column to satisfy this; that is out
-- of scope for this migration.
UPDATE hr.leave_type
   SET paid_days_cap = NULL,
       advance_notice_days = 1,
       min_service_months = 4,
       max_consecutive_days = 3.00,
       once_per_employment = FALSE,
       updated_at = now()
 WHERE leave_type_code = 'PERSONAL';

-- 5.3 VACATION: 6 days/year for >1 year of service (pro-ration below that is a later branch -- this
-- slice only encodes the >=12-month eligibility floor, not the pro-rated quota itself), 3 working
-- days' advance notice, fully paid within quota, no consecutive-day cap stated.
UPDATE hr.leave_type
   SET paid_days_cap = NULL,
       advance_notice_days = 3,
       min_service_months = 12,
       max_consecutive_days = NULL,
       once_per_employment = FALSE,
       updated_at = now()
 WHERE leave_type_code = 'VACATION';

-- LEAVE_WITHOUT_PAY: 0-day quota, always unpaid (paid_days_cap = 0 is consistent with, though
-- redundant given, the 0-day quota itself). No notice/service/consecutive/lifetime restriction.
UPDATE hr.leave_type
   SET paid_days_cap = 0.00,
       advance_notice_days = 0,
       min_service_months = 0,
       max_consecutive_days = NULL,
       once_per_employment = FALSE,
       updated_at = now()
 WHERE leave_type_code = 'LEAVE_WITHOUT_PAY';

-- ---------------------------------------------------------------------
-- New leave types the announcement defines that hr.leave_type never had rows for.
-- ---------------------------------------------------------------------

-- 5.4 MATERNITY (ลาคลอดบุตร): <=98 days/year, of which <=45 are paid (includes antenatal-check
-- leave and any weekly/traditional holidays falling inside the period -- counted here as calendar
-- days via LeaveDayMath's existing whole-day path, same as every other type). requires_attachment
-- mirrors SICK (medical documentation for the leave).
INSERT INTO hr.leave_type (
    leave_type_code, name_th, name_en, annual_quota_days, requires_attachment,
    paid_days_cap, advance_notice_days, min_service_months, max_consecutive_days, once_per_employment
) VALUES (
    'MATERNITY', 'ลาคลอดบุตร', 'Maternity leave', 98.00, TRUE,
    45.00, 0, 0, NULL, FALSE
)
ON CONFLICT (leave_type_code) DO NOTHING;

-- 5.5 MILITARY (ลารับราชการทหาร): <=60 days/year, fully paid, per an actual call-up document
-- (requires_attachment TRUE -- the call-up letter). Scoped to inspection/training/readiness
-- call-ups, not voluntary enlistment; that distinction is not representable as a leave_type column
-- and is left to the reviewing HR approver, same as it is for every other manually-reviewed leave.
INSERT INTO hr.leave_type (
    leave_type_code, name_th, name_en, annual_quota_days, requires_attachment,
    paid_days_cap, advance_notice_days, min_service_months, max_consecutive_days, once_per_employment
) VALUES (
    'MILITARY', 'ลารับราชการทหาร', 'Military service leave', 60.00, TRUE,
    NULL, 0, 0, NULL, FALSE
)
ON CONFLICT (leave_type_code) DO NOTHING;

-- 5.6 ORDINATION (ลาอุปสมบท): employees with >=1 year of service; 15 days paid at normal rate but
-- up to 60 days total absence is permitted (the extra 45 days are unpaid, hence paid_days_cap=15 on
-- a 60-day quota); usable once per working lifetime -- see ux_leave_once_per_employment below.
INSERT INTO hr.leave_type (
    leave_type_code, name_th, name_en, annual_quota_days, requires_attachment,
    paid_days_cap, advance_notice_days, min_service_months, max_consecutive_days, once_per_employment
) VALUES (
    'ORDINATION', 'ลาอุปสมบท', 'Ordination leave', 60.00, FALSE,
    15.00, 0, 12, NULL, TRUE
)
ON CONFLICT (leave_type_code) DO NOTHING;

-- ---------------------------------------------------------------------
-- Constraints
-- ---------------------------------------------------------------------
ALTER TABLE hr.leave_type
    ADD CONSTRAINT chk_leave_type_paid_days_cap CHECK (
        paid_days_cap IS NULL
        OR (paid_days_cap >= 0 AND paid_days_cap <= annual_quota_days)
    ),
    ADD CONSTRAINT chk_leave_type_advance_notice_nonnegative CHECK (advance_notice_days >= 0),
    ADD CONSTRAINT chk_leave_type_min_service_nonnegative CHECK (min_service_months >= 0),
    ADD CONSTRAINT chk_leave_type_max_consecutive_positive CHECK (
        max_consecutive_days IS NULL OR max_consecutive_days > 0
    );

-- Race-proof "once per employment" guard, same precedent as V66's ux_smr_once_per_lifetime for
-- wedding/ordination special-money aid: a Java-level pre-check (LeaveService#submit) is not enough
-- under concurrent submissions, so the DB itself refuses a second live claim.
--
-- Scope decision: an APPROVED-then-CANCELLED request does NOT count as having used the
-- once-per-employment right -- cancelling releases the claim, on the reasoning that the employee
-- never actually took the leave. This mirrors ux_smr_once_per_lifetime's own status list exactly
-- (SUBMITTED, MANAGER_APPROVED/APPROVED all block a second claim; a cancelled/rejected one does
-- not) for the same underlying benefit (ordination). SUBMITTED is included so a still-pending
-- request already blocks a concurrent second submission, not just an already-APPROVED one.
--
-- The WHERE list of leave_type_codes must be kept in sync BY HAND with which hr.leave_type rows
-- have once_per_employment = TRUE (a partial index predicate cannot subquery another table). There
-- is currently exactly one such row: ORDINATION.
CREATE UNIQUE INDEX ux_leave_once_per_employment
    ON hr.leave_request(employee_id, leave_type_code)
    WHERE leave_type_code = 'ORDINATION'
      AND status IN ('SUBMITTED', 'APPROVED');
