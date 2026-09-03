SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- LEAVE: per-request carry-in/own-quota pool attribution + requester-chosen
-- consumption order (2026-09-03)
-- ---------------------------------------------------------------------
--
-- V127 implemented the §5.3.5 carry-forward RIGHT (unused VACATION may carry into the immediately
-- following year) but never recorded WHICH pool a given request actually drew from -- there was one
-- merged pool at consumption time (LeaveService#remainingDays = annualQuota + carryIn - used), and
-- the carry-OUT figure at year-end (LeaveService#ensureCarryoverGrant) only ever INFERRED a
-- carry-in-first consumption order retroactively, from the AGGREGATE
-- `ownQuota + carriedIn - used` -- never from what individual requests actually consumed. That
-- inference was harmless while carry-in-first was the ONLY possible order. It stops being safe the
-- moment a requester can choose OWN_FIRST instead (this migration's other half) -- see
-- LeaveService#ensureCarryoverGrant's Javadoc for the worked example of why the old formula then
-- lies.
--
-- This migration does two things:
--   1) hr.leave_request_quota_year (V118, one row per calendar year a request's days fall into)
--      gains carried_in_days/own_quota_days -- the REAL, recorded split of that year's
--      quota-consuming amount across the two pools, replacing the old retroactive inference.
--   2) hr.leave_request gains quota_pool_preference -- the requester's OWN choice of which pool to
--      draw from first, defaulting to CARRIED_IN_FIRST (the §5.3.5 use-it-or-lose-it reading that
--      maximises what the employee keeps -- see LeaveQuotaPoolPreference's Javadoc).
--
-- Nothing here touches hr.leave_carryover: no row is deleted, rewritten, or inserted by this
-- migration. That table's earned_year grants (including the 2026-09-01 hand-correction for employee
-- 54/VACATION/2025) are read-only inputs to the backfill below, never outputs of it.

-- ---------------------------------------------------------------------
-- 1) New columns, safe to add with a constant DEFAULT: every EXISTING row is implicitly "0/0" the
--    instant these are added (Postgres backfills the DEFAULT for pre-existing rows), which is why the
--    STRONGER "these two sum to what was actually consumed" invariant (added in step 4, below) cannot
--    be declared here yet -- it would immediately fail for every row that ever consumed a nonzero
--    amount. Only the cheap, unconditionally-true-on-DEFAULT non-negative checks are safe this early.
-- ---------------------------------------------------------------------
ALTER TABLE hr.leave_request_quota_year
    ADD COLUMN carried_in_days NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN own_quota_days  NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_lrqy_carried_in_nonnegative CHECK (carried_in_days >= 0),
    ADD CONSTRAINT chk_lrqy_own_quota_nonnegative CHECK (own_quota_days >= 0);

COMMENT ON COLUMN hr.leave_request_quota_year.carried_in_days IS
    'How many of this year''s quota-consuming days (see chk_lrqy_pool_matches_consumed) were charged '
    'to the carried-in-from-last-year pool, per the requester''s quota_pool_preference (or the '
    'CARRIED_IN_FIRST default) at submission time. Always 0 for a leave type where '
    'hr.leave_type.carries_forward is FALSE -- see LeaveService#computeQuotaSplit.';
COMMENT ON COLUMN hr.leave_request_quota_year.own_quota_days IS
    'How many of this year''s quota-consuming days (see chk_lrqy_pool_matches_consumed) were charged '
    'to this year''s OWN annual quota, as opposed to a carried-in day -- see carried_in_days above and '
    'LeaveService#computeQuotaSplit.';

-- ---------------------------------------------------------------------
-- 2) The requester's chosen consumption order. Safe to CHECK immediately: the DEFAULT
--    ('CARRIED_IN_FIRST') is itself a member of the allowed set, so every pre-existing row already
--    satisfies this the moment the column appears -- unlike step 1's pool columns, there is no
--    "the default violates the constraint" window here.
-- ---------------------------------------------------------------------
ALTER TABLE hr.leave_request
    ADD COLUMN quota_pool_preference VARCHAR(20) NOT NULL DEFAULT 'CARRIED_IN_FIRST',
    ADD CONSTRAINT chk_leave_quota_pool_preference CHECK (
        quota_pool_preference IN ('CARRIED_IN_FIRST', 'OWN_FIRST')
    );

COMMENT ON COLUMN hr.leave_request.quota_pool_preference IS
    '§5.3.5 pool choice (V161): which pool this request drew from FIRST when it was submitted -- '
    'CARRIED_IN_FIRST (default; the about-to-expire carry-in pool is spent down before the renewable '
    'annual quota -- see LeaveQuotaPoolPreference''s Javadoc) or OWN_FIRST. Every pre-V161 request is '
    'backfilled to CARRIED_IN_FIRST, matching the assumption '
    'LeaveService#ensureCarryoverGrant''s old carry-out INFERENCE already made for every request ever '
    'submitted before this column existed -- this backfill therefore preserves history rather than '
    'rewriting it. Spillover into the OTHER pool when the chosen one runs out mid-request is normal -- '
    'see hr.leave_request_quota_year.carried_in_days/own_quota_days for what was actually charged '
    'where.';

-- ---------------------------------------------------------------------
-- 3) Backfill: attribute every EXISTING hr.leave_request_quota_year row's already-recorded
--    consumption across the two pools, under the historical carry-in-first assumption.
-- ---------------------------------------------------------------------
--
-- The amount a row actually consumed from the quota is `quota_remaining_before - quota_remaining_after`
-- -- deliberately NEITHER total_days NOR paid_days:
--   * total_days overstates it -- a day requested beyond remaining quota goes UNPAID and consumes no
--     quota at all (LeaveService#computeQuotaSplit: quotaBoundedPaidDaysYear = remainingBeforeYear
--     .min(daysInYear), strictly less than daysInYear/total_days whenever a request runs past what
--     remained).
--   * paid_days can UNDERSTATE it -- paid_days is quotaBoundedPaidDaysYear narrowed a SECOND time by
--     boundByPaidCap (a type''s own separate paid-days-cap, e.g. MATERNITY''s 45-day cap against a
--     98-day quota); the review-fix comment on remainingAfterYear is explicit that quota consumption
--     tracks quotaBoundedPaidDaysYear, "NOT from the paid-cap-narrowed paidDaysYear". A capped type''s
--     paid_days can therefore be smaller than what it actually used of the quota.
--   * `quota_remaining_before - quota_remaining_after` is exactly quotaBoundedPaidDaysYear by
--     construction (LeaveService#computeQuotaSplit's approved branch subtracts exactly that from
--     remainingBeforeYear to get remainingAfterYear; the AUTO_REJECTED branch leaves them equal,
--     giving zero either way) -- it is the one figure on this row that already IS "what this row
--     consumed from the quota", for every status, without re-deriving anything.
--
-- Per (employee_id, leave_type_code, quota_year), ordered chronologically by (start_date,
-- leave_request_id) -- start_date first (the natural "which day happened first" reading a
-- use-it-or-lose-it pool should be spent against), leave_request_id only as a tie-breaker for two
-- requests starting the same day. NOTE, stated plainly: this is LEAVE-DATE order, not the SUBMISSION
-- order the original quota_remaining_before/after snapshots were actually computed in (two requests
-- can be submitted in one order but start in another) -- an exact bit-for-bit replay of history is not
-- reconstructable post hoc, and was not asked for; this is a deterministic, defensible or as-good
-- ordering whose per-row split sums EXACTLY to the already-recorded (before-after) figure either way,
-- which is the property that actually matters (chk_lrqy_pool_matches_consumed below).
--
-- For each row, in that order: charge carried_in_days from that (employee, type, quota_year)'s
-- available grant -- hr.leave_carryover.carried_days where usable_year = quota_year (i.e.
-- earned_year = quota_year - 1), READ-ONLY, defaulting to 0 when no grant row exists (not yet
-- computed/memoized, or this type never carries forward) -- MINUS whatever earlier rows in the same
-- group already charged against it (a running total via a window SUM over the preceding rows only),
-- floored at 0; then whatever remains of this row's consumption falls to own_quota_days. A leave type
-- with carries_forward = FALSE always gets carried_in_days = 0 and the full consumed amount in
-- own_quota_days, regardless of any grant_days lookup (defensive -- no such type should ever have a
-- hr.leave_carryover row in the first place, since V127 only ever computes one for a carrying type).
WITH grant_lookup AS (
    SELECT employee_id, leave_type_code, usable_year, carried_days
      FROM hr.leave_carryover
),
attributed AS (
    SELECT
        lrqy.leave_request_id,
        lrqy.quota_year,
        lt.carries_forward,
        COALESCE(gl.carried_days, 0) AS grant_days,
        -- Only ACTIVE-status rows draw the pool down. This MUST match
        -- LeaveService.ACTIVE_QUOTA_STATUSES = {SUBMITTED, APPROVED}, because every read of these
        -- columns afterwards (LeaveRepository#sumCarriedInDaysUsed/#sumOwnQuotaDaysUsed, and
        -- #sumUsedDays before them) filters to exactly that set -- a running total computed over a
        -- WIDER set than the service will ever read back would reconstruct an attribution the
        -- service itself would never produce. CANCELLED is the case that actually differs: it keeps
        -- the nonzero quota_remaining_before/after snapshot it had while it was live (neither
        -- #cancel nor #approve/#reject ever recomputes a child row), so counting it here would eat
        -- carry-in that is no longer spoken for, push later rows onto own_quota_days, and thereby
        -- UNDERSTATE carryOut = ownQuota - ownUsed -- i.e. silently cost the employee carry-forward
        -- days. REJECTED/AUTO_REJECTED are already inert either way (before = after, contributing
        -- zero), so this filter changes nothing for them; it is CANCELLED it exists for.
        -- NOTE this filters only the RUNNING TOTAL. Every row, whatever its status, still receives
        -- its own split below and must satisfy chk_lrqy_pool_matches_consumed.
        COALESCE(
            SUM(CASE WHEN lr.status IN ('SUBMITTED', 'APPROVED')
                     THEN lrqy.quota_remaining_before - lrqy.quota_remaining_after
                     ELSE 0
                END) OVER (
                PARTITION BY lr.employee_id, lr.leave_type_code, lrqy.quota_year
                ORDER BY lr.start_date, lrqy.leave_request_id
                ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
            ),
            0
        ) AS consumed_before_this_row
      FROM hr.leave_request_quota_year lrqy
      JOIN hr.leave_request lr ON lr.leave_request_id = lrqy.leave_request_id
      JOIN hr.leave_type lt ON lt.leave_type_code = lr.leave_type_code
      LEFT JOIN grant_lookup gl
             ON gl.employee_id = lr.employee_id
            AND gl.leave_type_code = lr.leave_type_code
            AND gl.usable_year = lrqy.quota_year
),
split AS (
    SELECT
        leave_request_id,
        quota_year,
        -- "How much of the year's carry-in grant is left BEFORE this row" -- grant_days is already
        -- non-negative by construction (hr.leave_carryover's own chk_lc_carried_days_nonnegative), so
        -- the only clamp needed here is against consumed_before_this_row exceeding it. Capping THIS
        -- row's own share to what it actually consumed happens below, in the UPDATE itself, alongside
        -- own_quota_days so both stay visibly derived from the same `consumed` expression.
        CASE WHEN carries_forward
             THEN GREATEST(grant_days - consumed_before_this_row, 0)
             ELSE 0
        END AS remaining_grant_before_row
      FROM attributed
)
UPDATE hr.leave_request_quota_year lrqy
   SET carried_in_days = LEAST(
           split.remaining_grant_before_row,
           GREATEST(lrqy.quota_remaining_before - lrqy.quota_remaining_after, 0)
       ),
       own_quota_days = GREATEST(lrqy.quota_remaining_before - lrqy.quota_remaining_after, 0)
           - LEAST(
               split.remaining_grant_before_row,
               GREATEST(lrqy.quota_remaining_before - lrqy.quota_remaining_after, 0)
             )
  FROM split
 WHERE split.leave_request_id = lrqy.leave_request_id
   AND split.quota_year = lrqy.quota_year;

-- ---------------------------------------------------------------------
-- 4) Assert the backfill actually produced the invariant it was meant to, with a specific error
--    message -- same house style as V118's post-backfill DO $$ block -- BEFORE declaring it as a real
--    constraint below, so a defect here fails loudly with an explanation rather than a bare
--    "constraint violated" from the ALTER TABLE that follows.
--
-- INVARIANT CHOSEN: carried_in_days + own_quota_days = quota_remaining_before - quota_remaining_after
-- (the row's own already-recorded consumption -- see the backfill comment above for why this, not
-- total_days or paid_days, is "the quota-consuming amount"). This is provable in EVERY branch of
-- LeaveService#computeQuotaSplit, not just the common case:
--   * approved branch: remainingAfterYear is DEFINED as remainingBeforeYear.subtract(
--     quotaBoundedPaidDaysYear), and the pool split (LeaveService#computeQuotaSplit, this same
--     change) is DEFINED to divide exactly quotaBoundedPaidDaysYear between the two pools (each
--     bounded by its own remaining, with the unchosen pool absorbing any spillover) -- so
--     carried_in_days + own_quota_days = quotaBoundedPaidDaysYear = quota_remaining_before -
--     quota_remaining_after holds BY CONSTRUCTION, not by coincidence.
--   * AUTO_REJECTED (approved = false) branch: remainingAfterYear = remainingBeforeYear (nothing was
--     consumed) and both pools are pinned to 0 -- 0 + 0 = 0, still holds.
--   * every pre-V118 backfilled row and every V118 cross-year row behaves as one of the two branches
--     above, since both predate this change and were computed by the same computeQuotaSplit shape.
--
-- INVARIANT REJECTED: tying the split to total_days (`carried_in_days + own_quota_days = total_days`)
-- or to paid_days (`... = paid_days`). Both are FALSE in real, reachable rows: total_days overstates
-- quota consumption whenever a request ran past what remained (the excess is unpaid_days, which
-- consumes no quota); paid_days can UNDERSTATE it for a paid-days-capped type (MATERNITY) whose
-- quota-bounded days were further narrowed by boundByPaidCap. Neither was written into a CHECK
-- constraint -- doing so would fire in production the first time either case occurs. See this
-- migration's header and LeaveService#computeQuotaSplit's Javadoc for the same reasoning stated on
-- the Java side.
DO $$
DECLARE
    mismatched_count INTEGER;
    wrong_type_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO mismatched_count
      FROM hr.leave_request_quota_year
     WHERE carried_in_days + own_quota_days <> quota_remaining_before - quota_remaining_after;

    IF mismatched_count > 0 THEN
        RAISE EXCEPTION
            'V161 backfill left % hr.leave_request_quota_year row(s) whose carried_in_days + '
            'own_quota_days does not equal quota_remaining_before - quota_remaining_after',
            mismatched_count;
    END IF;

    -- Belt-and-suspenders on the "carries_forward = FALSE types never get a carried-in figure" rule
    -- stated in this migration's header and in LeaveQuotaPoolPreference's Javadoc.
    SELECT COUNT(*) INTO wrong_type_count
      FROM hr.leave_request_quota_year lrqy
      JOIN hr.leave_request lr ON lr.leave_request_id = lrqy.leave_request_id
      JOIN hr.leave_type lt ON lt.leave_type_code = lr.leave_type_code
     WHERE lt.carries_forward = FALSE
       AND lrqy.carried_in_days <> 0;

    IF wrong_type_count > 0 THEN
        RAISE EXCEPTION
            'V161 backfill assigned a nonzero carried_in_days to % hr.leave_request_quota_year row(s) '
            'whose leave type does not carry forward',
            wrong_type_count;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 5) Now that every row is verified to already satisfy it, declare the invariant as a real CHECK --
--    structural enforcement going forward (LeaveService#computeQuotaSplit must keep producing a split
--    that sums to exactly the consumed amount, or every future INSERT fails loudly, the same
--    defense-in-depth V127's chk_lc_carried_days_bounded_by_own_quota already applies one table over).
-- ---------------------------------------------------------------------
ALTER TABLE hr.leave_request_quota_year
    ADD CONSTRAINT chk_lrqy_pool_matches_consumed CHECK (
        carried_in_days + own_quota_days = quota_remaining_before - quota_remaining_after
    );
