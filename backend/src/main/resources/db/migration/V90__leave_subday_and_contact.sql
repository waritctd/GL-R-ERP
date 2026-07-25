SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- LEAVE: sub-day (hourly/half-day) leave + paper-form contact block + PERSONAL quota fix
-- (2026-07-25)
-- ---------------------------------------------------------------------
--
-- Context: the paper leave form (ใบลาหยุด F-HR-020) supports partial-day leave
-- (ตั้งแต่เวลา-ถึงเวลา) and a contact-during-leave block (address + phone). The digital form only
-- supported whole days. This migration adds the columns the sub-day feature and the contact block
-- need, and fixes the PERSONAL quota, which was seeded at 3 days but company policy (§5.2) grants 7.
--
-- 1) Sub-day time range. Both NULL means legacy/whole-day leave (the existing, unaffected case).
--    LeaveService computes a day-fraction from these when present -- see LeaveDayMath/LeaveService.
ALTER TABLE hr.leave_request
    ADD COLUMN start_time TIME,
    ADD COLUMN end_time   TIME;

ALTER TABLE hr.leave_request
    ADD CONSTRAINT chk_leave_time_pairing CHECK (
        (start_time IS NULL) = (end_time IS NULL)
    ),
    ADD CONSTRAINT chk_leave_time_order CHECK (
        start_time IS NULL OR end_time > start_time
    ),
    ADD CONSTRAINT chk_leave_time_single_day CHECK (
        start_time IS NULL OR start_date = end_date
    );

-- 2) Paper-form "contact during leave" block: address (autofilled from the employee's current
--    address, editable) + phone. Nullable -- legacy rows and requests where the employee simply
--    didn't override the autofill still have no value to force here.
ALTER TABLE hr.leave_request
    ADD COLUMN contact_house_no    VARCHAR(60),
    ADD COLUMN contact_subdistrict VARCHAR(120),
    ADD COLUMN contact_district    VARCHAR(120),
    ADD COLUMN contact_province    VARCHAR(120),
    ADD COLUMN contact_phone       VARCHAR(40);

-- 3) PERSONAL quota fix: seeded at 3.00 (V13), company rule (§5.2) grants 7.00 paid personal
--    days/year. Forward-only correction, not a backfill of past usage.
UPDATE hr.leave_type
   SET annual_quota_days = 7.00,
       updated_at = now()
 WHERE leave_type_code = 'PERSONAL';

-- No change to total_days/paid_days/unpaid_days: all three are already NUMERIC(5,2) with
-- chk_leave_total_positive (total_days > 0) and chk_leave_paid_unpaid_sum (paid+unpaid=total for
-- APPROVED/CANCELLED) from V85/V87 -- both already tolerate a fractional value like 0.44, so no
-- redundant constraint is added here.
