SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- LEAVE: §5.3.5 VACATION carry-forward (2026-08-03)
-- ---------------------------------------------------------------------
--
-- Authority: ประกาศ "วันเวลาทำงาน และการหยุดงาน", 1 ตุลาคม 2567, §5.3.5:
--   "กรณีที่ปีใดพนักงานลาพักผ่อนไม่ถึง 6 วัน อนุญาตให้นำวันลาพักผ่อนที่เหลือไปใช้ในปีต่อไปได้
--   แต่ให้ใช้สิทธิที่ยกมานี้ได้ในปีต่อไปเท่านั้น"
--   ("If in any year an employee takes fewer than 6 vacation days, the remaining days may be
--   carried into the following year -- but that carried-forward right may only be used in that
--   following year.")
--
-- V116 seeded VACATION.min_service_months/paid_days_cap/etc. as declarative columns on
-- hr.leave_type and explicitly listed "vacation carry-forward (§5.3.5)" as deliberately NOT yet
-- implemented. This migration implements it. It is the third leave migration to touch VACATION's
-- rules after V116 (base rules) and V120 (first-year pro-ration) -- see LeaveService#employeeAnnualQuota
-- for the pro-ration this migration builds on top of.
--
-- ---------------------------------------------------------------------
-- Design: declarative gate, not a hardcoded VACATION check (V116's own precedent)
-- ---------------------------------------------------------------------
-- §5.3.5 is the ONLY clause in the announcement that grants a carry-forward right -- SICK,
-- PERSONAL, MATERNITY, MILITARY, ORDINATION are not extended this behaviour, and this migration
-- does not generalise beyond the text. Per V116's own stated pattern ("hr.leave_type holds the
-- declarative rules ... rather than adding a third hardcoded-by-code branch"), the ENABLEMENT is a
-- new boolean column, seeded TRUE for VACATION only -- not an "if (code.equals("VACATION"))" branch
-- in Java. The storage/mechanism below is intentionally leave-type-agnostic (leave_type_code is a
-- column, not baked into the table name) so a future leave type that genuinely earns a
-- carry-forward right under some later announcement would not need a parallel table -- but nothing
-- about that is implemented or implied today; only VACATION's row is ever TRUE.
ALTER TABLE hr.leave_type
    ADD COLUMN carries_forward BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN hr.leave_type.carries_forward IS
    '§5.3.5 (V127): TRUE if this type''s unused annual quota may carry into the immediately '
    'following year (see hr.leave_carryover). Seeded TRUE for VACATION only -- the announcement '
    'grants this right to no other leave type; do not seed it elsewhere without a matching clause. '
    'See LeaveService#ensureCarryoverGrant for the computation this gates.';

UPDATE hr.leave_type
   SET carries_forward = TRUE,
       updated_at = now()
 WHERE leave_type_code = 'VACATION';

-- ---------------------------------------------------------------------
-- hr.leave_carryover: one row per (employee, leave type, EARNED year) -- the year the days went
-- unused, NOT the year they may be spent in ("usable_year", stored redundantly as earned_year + 1
-- purely so a lookup by usable_year does not need arithmetic in the WHERE clause).
-- ---------------------------------------------------------------------
--
-- STRUCTURAL EXPIRY (the point of this design): a row granted for earned_year=2025 is only ever
-- looked up by usable_year=2026 (see LeaveService#carryInDays). Once the calendar reaches 2027,
-- nothing in this codebase ever queries earned_year=2025 again -- the grant simply stops being
-- found, because the lookup key changes, not because anything deletes or zeroes it. There is no
-- cleanup job to forget to run, and no mutable "balance" column that must be remembered to reset;
-- the row's meaning is scoped to exactly one usable_year by construction. Old rows are left in
-- place forever as an audit trail (harmless, and small: one row per employee per carrying leave
-- type per year).
--
-- NON-ACCUMULATION (§5.3.5's "...ได้ในปีต่อไปเท่านั้น" -- "...only in the following year"): a
-- carried day that goes unused itself does NOT carry a second time. This is enforced by what
-- own_quota_days/carried_days MEAN, not by an extra rule: a year's carry-OUT
-- (LeaveService#ensureCarryoverGrant) is computed against that year's OWN annual quota
-- (own_quota_days) only, bounded to at most own_quota_days -- a leftover carried-IN day that goes
-- unused is not part of any later year's own_quota_days, so it cannot itself be carried out again.
-- See the Java method's Javadoc for the full worked arithmetic.
--
-- NO HISTORICAL BACKFILL: this migration inserts no rows. Every grant is computed lazily, on first
-- need, by LeaveService#ensureCarryoverGrant, from hr.leave_request_quota_year's real per-year
-- usage (V118) -- exact, not invented, because it is the same data every other quota computation in
-- this codebase already trusts. It is bounded below by LeaveService.CARRY_FORWARD_BASELINE_YEAR
-- (2025): no grant is ever computed for an earned_year before that constant, by design -- see its
-- Javadoc for why 2025 (not 2567/2024, the announcement's own effective year) was chosen as the
-- baseline, and CLAUDE.md's "historical years must not be invented" rule this constant exists to
-- honour. An employee's 2024 vacation usage, whatever it was, grants nothing into 2025.
CREATE TABLE hr.leave_carryover (
    employee_id      BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    leave_type_code  VARCHAR(30) NOT NULL REFERENCES hr.leave_type(leave_type_code),
    earned_year      SMALLINT NOT NULL,
    usable_year      SMALLINT NOT NULL,
    -- Audit trail of the inputs the grant was computed from -- not read back by any query today,
    -- but kept so a wrong-looking carried_days figure can be explained without recomputing it by
    -- hand against whatever hr.leave_request_quota_year looks like NOW (which may have changed
    -- since, e.g. a since-cancelled request).
    own_quota_days   NUMERIC(5,2) NOT NULL,
    used_days        NUMERIC(5,2) NOT NULL,
    carried_in_days  NUMERIC(5,2) NOT NULL DEFAULT 0,
    carried_days     NUMERIC(5,2) NOT NULL,
    computed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (employee_id, leave_type_code, earned_year),
    CONSTRAINT chk_lc_usable_year_is_next CHECK (usable_year = earned_year + 1),
    CONSTRAINT chk_lc_own_quota_nonnegative CHECK (own_quota_days >= 0),
    CONSTRAINT chk_lc_used_days_nonnegative CHECK (used_days >= 0),
    CONSTRAINT chk_lc_carried_in_days_nonnegative CHECK (carried_in_days >= 0),
    CONSTRAINT chk_lc_carried_days_nonnegative CHECK (carried_days >= 0),
    -- Non-accumulation, enforced structurally (not just by the Java arithmetic that computes the
    -- value): a grant can never exceed the EARNED year's own quota, no matter how large
    -- carried_in_days was -- see the non-accumulation note above.
    CONSTRAINT chk_lc_carried_days_bounded_by_own_quota CHECK (carried_days <= own_quota_days)
);

COMMENT ON TABLE hr.leave_carryover IS
    '§5.3.5 VACATION carry-forward (V127): one row per (employee, leave_type_code, earned_year) '
    'once that year has fully elapsed and its grant into earned_year+1 (usable_year) has been '
    'computed. Computed lazily and memoized by LeaveService#ensureCarryoverGrant -- never backfilled '
    'by this migration. See that method''s Javadoc for the consumption-order decision (carried-in '
    'days are treated as consumed BEFORE the year''s own quota) and the full formula.';

-- Primary lookup path: "does employee X have a carry-in available for leave_type_code Y in
-- usable_year Z" (LeaveService#carryInDays). earned_year is already the leading column of the
-- primary key, but usable_year (the actual query key) is not, so a dedicated index avoids a table
-- scan as this grows.
CREATE INDEX idx_leave_carryover_usable_year
    ON hr.leave_carryover(employee_id, leave_type_code, usable_year);
