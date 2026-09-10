SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- LEAVE: a partial-start/partial-end span across multiple days (owner-approved, 2026-09-10)
-- ---------------------------------------------------------------------
--
-- Context: V90 (2026-07-25) added start_time/end_time for a SINGLE-day partial request (a half-day),
-- enforced by chk_leave_time_single_day (start_time IS NULL OR start_date = end_date). That is too
-- narrow for a real case HR flagged: ลาพักร้อน starting 13:00 on day 1 (afternoon only) and running
-- through the FULL of day 2 -- a genuine 1.5-day request that today can only be filed as two separate
-- leave requests. This migration relaxes the schema to permit a timed request to span multiple
-- calendar days; LeaveService/LeaveDayMath (Java side, same PR) compute the day-fraction total as
-- (clock hours in the span MINUS any overlap with the 12:30-13:30 break) / 8, capped at 1.00 per
-- date. Owner ruling, 2026-09-10: the divisor is a flat 8-hour worked day, NOT the schedule's own
-- clock span -- an earlier draft of this header said the opposite and was superseded. 12:30-13:30
-- is company-wide and exists ONLY for this arithmetic; attendance still models no break at all.
--
-- 1) chk_leave_time_single_day is DROPPED outright, not merely relaxed -- there is no longer any
--    schema-level restriction on how many calendar days a timed (start_time IS NOT NULL) request may
--    span. chk_leave_date_order (V13, end_date >= start_date) already bounds the range from below;
--    nothing here needs to re-state it.
--
-- 2) chk_leave_time_order is REPLACED, not merely relaxed. Its old shape
--    (start_time IS NULL OR end_time > start_time) compared two LocalTime-shaped columns as if they
--    always described the same calendar day -- true under the old single-day-only constraint, but
--    wrong once end_date can be a later day than start_date: e.g. start_time=15:00/end_time=09:00
--    across two different days is a perfectly valid span (afternoon day 1 through morning day 2), yet
--    09:00 > 15:00 is false, so the old text would incorrectly reject it. The new definition compares
--    the (date, time) PAIR at each end as one ordered value -- Postgres row-wise comparison
--    ((end_date, end_time) > (start_date, start_time)) is TRUE exactly when end_date is a later
--    calendar day, OR the dates are equal and end_time is later that same day. This subsumes the old
--    same-day ordering check as the special case end_date = start_date, so single-day behaviour is
--    completely unchanged.
--
-- 3) No new "all-day" column. hr.leave_request already treats (start_time IS NULL AND end_time IS
--    NULL) as "whole-day leave" (V90's chk_leave_time_pairing + every existing whole-day code path) --
--    that convention extends unchanged to a whole-day request spanning many days (already supported
--    today, untouched by this migration) and needs no new flag: "all day" is simply "no times given",
--    exactly as it always has been. A frontend all-day tick box maps to "send null start_time/
--    end_time", nothing more.
--
-- No change to total_days/paid_days/unpaid_days column types or their existing CHECK constraints
-- (chk_leave_total_positive, chk_leave_paid_unpaid_sum, chk_leave_paid_unpaid_nonnegative) -- all
-- three are NUMERIC(5,2) and already tolerate a fractional value like 1.50 (proven by the existing
-- single-day sub-day feature); a multi-day timed request's total is computed the same way, just
-- summed across more than one day.

ALTER TABLE hr.leave_request
    DROP CONSTRAINT IF EXISTS chk_leave_time_single_day;

ALTER TABLE hr.leave_request
    DROP CONSTRAINT IF EXISTS chk_leave_time_order;

ALTER TABLE hr.leave_request
    ADD CONSTRAINT chk_leave_time_order CHECK (
        start_time IS NULL OR (end_date, end_time) > (start_date, start_time)
    );
