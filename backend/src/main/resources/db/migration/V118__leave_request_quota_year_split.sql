SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- LEAVE: per-quota-year attribution for a cross-year leave request (2026-08-02)
-- ---------------------------------------------------------------------
--
-- V116 flagged this as a known risk (see its MATERNITY comment): LeaveService#validateDateRange
-- rejected any request whose start/end fell in different calendar years, because
-- hr.leave_request.quota_year is a single scalar and LeaveRepository#sumUsedDays/#sumPaidDays filter
-- on it -- one request could only ever consume one year's quota. That was tolerable while the
-- longest leave type was 30 days (SICK); it stopped being tolerable once MATERNITY (98 days, ~136
-- calendar days) shipped -- any maternity leave starting after roughly mid-September became
-- unfileable outright. This migration is the fix: it moves quota ATTRIBUTION (not consumption
-- eligibility -- nothing here changes whether a request is approved) to a per-calendar-year child
-- table, one row per year the request's days fall into.
--
-- hr.leave_request itself keeps its own total_days/paid_days/unpaid_days as the SUMS across every
-- year touched -- chk_leave_paid_unpaid_sum (V85/V87) is untouched and still holds against those
-- sums. quota_remaining_before/quota_remaining_after on the parent keep their existing meaning for a
-- same-year request; for a request that spans a year boundary they reflect ONLY the request's
-- nominal (start) quota_year -- see the column comments added below and
-- LeaveService#submit/LeaveRequestDto for where the full per-year picture lives.
CREATE TABLE hr.leave_request_quota_year (
    leave_request_id       BIGINT NOT NULL REFERENCES hr.leave_request(leave_request_id) ON DELETE CASCADE,
    quota_year              SMALLINT NOT NULL,
    total_days               NUMERIC(5,2) NOT NULL,
    paid_days                 NUMERIC(5,2) NOT NULL,
    unpaid_days               NUMERIC(5,2) NOT NULL,
    quota_remaining_before   NUMERIC(5,2) NOT NULL,
    quota_remaining_after    NUMERIC(5,2) NOT NULL,
    PRIMARY KEY (leave_request_id, quota_year),
    CONSTRAINT chk_lrqy_quota_year CHECK (quota_year BETWEEN 2000 AND 2100),
    CONSTRAINT chk_lrqy_total_positive CHECK (total_days > 0),
    CONSTRAINT chk_lrqy_paid_unpaid_nonnegative CHECK (paid_days >= 0 AND unpaid_days >= 0),
    -- Mirrors chk_leave_paid_unpaid_sum's V87 relaxation, minus the status gate: this table has no
    -- status column of its own (status lives on the parent and applies uniformly to every year a
    -- request touches), so the (0,0) "not yet split / never granted" state is tolerated
    -- unconditionally instead of being scoped to APPROVED/CANCELLED. That is intentionally at least
    -- as permissive as the parent's own check, never stricter -- a SUBMITTED/REJECTED/AUTO_REJECTED
    -- request's per-year rows are always (0,0) (see LeaveService#submit), the same way the parent's
    -- own paid_days/unpaid_days already are for those statuses.
    CONSTRAINT chk_lrqy_paid_unpaid_sum CHECK (
        paid_days + unpaid_days = total_days OR (paid_days = 0 AND unpaid_days = 0)
    ),
    CONSTRAINT chk_lrqy_quota_remaining_nonnegative CHECK (
        quota_remaining_before >= 0 AND quota_remaining_after >= 0
    )
);

COMMENT ON TABLE hr.leave_request_quota_year IS
    'Per-calendar-year quota attribution for a leave request (2026-08-02 cross-year fix). One row '
    'per year the request''s [start_date, end_date] range falls into -- almost always exactly one '
    '(matching hr.leave_request.quota_year), two only for a request that spans 31 Dec/1 Jan (e.g. a '
    'long MATERNITY request). Each year''s total_days/paid_days/unpaid_days/quota_remaining_before/'
    'quota_remaining_after are computed independently against THAT year''s own quota and paid_days_cap '
    '-- see LeaveService#submit. hr.leave_request''s own total_days/paid_days/unpaid_days are the SUMS '
    'across every row here for that leave_request_id.';

COMMENT ON COLUMN hr.leave_request.quota_remaining_before IS
    'Quota remaining, for hr.leave_request.quota_year (the request''s START year), before this '
    'request. For a request that spans a year boundary this is ONLY the start year''s figure, not the '
    'full multi-year picture -- see hr.leave_request_quota_year for the per-year breakdown. Unchanged '
    'meaning for the common (same-year) case.';
COMMENT ON COLUMN hr.leave_request.quota_remaining_after IS
    'Quota remaining, for hr.leave_request.quota_year (the request''s START year), after this '
    'request. Same start-year-only caveat as quota_remaining_before above.';

CREATE INDEX idx_lrqy_quota_year ON hr.leave_request_quota_year(quota_year);

-- ---------------------------------------------------------------------
-- Backfill: every existing hr.leave_request row gets exactly one child row carrying its own
-- (already single-year) total_days/paid_days/unpaid_days/quota_year/quota_remaining_before/after --
-- a straight 1:1 copy, since every pre-V118 row was, by construction, confined to one calendar year
-- (the cross-year case this migration enables did not exist before it). No historical request
-- changes meaning.
-- ---------------------------------------------------------------------
INSERT INTO hr.leave_request_quota_year (
    leave_request_id, quota_year, total_days, paid_days, unpaid_days,
    quota_remaining_before, quota_remaining_after
)
SELECT leave_request_id, quota_year, total_days, paid_days, unpaid_days,
       quota_remaining_before, quota_remaining_after
  FROM hr.leave_request;

-- Assert the backfill covered every row -- fail the migration loudly rather than silently leaving a
-- pre-existing leave_request without quota attribution (which would make it invisible to the new
-- sumUsedDays/sumPaidDays child-table queries and understate that employee's used quota).
DO $$
DECLARE
    missing_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO missing_count
      FROM hr.leave_request lr
      LEFT JOIN hr.leave_request_quota_year lrqy ON lrqy.leave_request_id = lr.leave_request_id
     WHERE lrqy.leave_request_id IS NULL;

    IF missing_count > 0 THEN
        RAISE EXCEPTION
            'V118 backfill left % hr.leave_request row(s) without a leave_request_quota_year child row',
            missing_count;
    END IF;
END $$;
