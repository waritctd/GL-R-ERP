SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- LEAVE: two V116 seeding defects, fixed against the literal text of §5 of the company announcement
-- "วันเวลาทำงาน และการหยุดงาน" (effective 1 ต.ค. 2567/2024). Both changes move money -- see the
-- branch's PR body for the full reasoning; this migration only carries the SQL half.
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- Defect 1 -- VACATION wrongly REFUSED under-one-year employees instead of pro-rating them.
--
-- §5.3's literal text: "พนักงานที่มีอายุงานเกิน 1 ปี สามารถลาพักผ่อนได้ 6 วัน ต่อปี (กรณีอายุงานไม่ถึง
-- หนึ่งปีให้ลาได้เป็นสัดส่วนตามอายุงาน)" -- "...(in case service duration is under one year, leave is
-- granted in proportion to service duration)". V116 seeded VACATION.min_service_months = 12, which
-- LeaveService#autoRejectNote enforces as an outright eligibility floor: an employee with 11 months
-- of service was refused ALL vacation leave, not given a smaller amount. That contradicts the
-- announcement's explicit parenthetical, which grants a pro-rated entitlement, not zero.
--
-- Fix: min_service_months -> 0 (removes the outright refusal) AND a new prorated_first_year flag
-- that tells LeaveService#employeeAnnualQuota (Java, this same branch) to scale the annual quota by
-- the employee's completed months of service instead of granting the flat annual figure from day
-- one -- setting min_service_months alone to 0 without pro-ration would swing the error the other
-- way (a 3-month employee getting the full 6 days), which is equally wrong per the same sentence.
--
-- §5.2 PERSONAL carries the IDENTICAL parenthetical ("กรณีอายุงานไม่ถึงหนึ่งปี ให้ลาเป็นสัดส่วนตาม
-- อายุงาน"), so it gets prorated_first_year = TRUE too. PERSONAL's min_service_months was already 0
-- (V116's review fix -- see that migration's PERSONAL comment): PERSONAL's real eligibility GATE is
-- the separate hire_date+probation_days check in LeaveService#personalProbationRejectionNote, which
-- this migration does not touch. Pro-ration and the probation gate are deliberately independent
-- rules that happen to both apply to PERSONAL -- see LeaveService's Javadoc for how they compose
-- (the probation gate can still refuse the request outright; pro-ration only ever changes the SIZE
-- of an otherwise-eligible quota).
--
-- A column (not a hardcoded type-code list in Java) matches how the rest of §5 was already made
-- declarative data on hr.leave_type by V116, rather than adding a third hardcoded-by-code branch
-- alongside the existing SICK-certificate and PERSONAL-probation ones.
ALTER TABLE hr.leave_type
    ADD COLUMN prorated_first_year BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN hr.leave_type.prorated_first_year IS
    'TRUE if, per §5, an employee under 12 completed months of service gets this type''s annual '
    'quota scaled down instead of either the full amount or an outright refusal (V116''s original '
    'defect: VACATION used min_service_months=12 as a hard eligibility floor, refusing under-1-year '
    'employees entirely, contradicting section 5.3''s stated pro-rated entitlement). Sits alongside '
    'min_service_months (V116) as a DIFFERENT concept: min_service_months is a pass/fail eligibility '
    'floor (still used by ORDINATION''s 12-month requirement); prorated_first_year instead SCALES the '
    'quota by completed service -- see LeaveService#employeeAnnualQuota for the exact formula (an '
    'INTERPRETATION of the announcement''s bare "เป็นสัดส่วนตามอายุงาน" wording, pending owner '
    'confirmation -- flagged in that method''s Javadoc and in this branch''s PR body, not stated as '
    'settled company policy). VACATION and PERSONAL are TRUE (both carry the identical parenthetical '
    'in §5.2/§5.3); every other type is FALSE (unchanged, flat-quota behaviour).';

UPDATE hr.leave_type
   SET min_service_months = 0,
       prorated_first_year = TRUE,
       updated_at = now()
 WHERE leave_type_code = 'VACATION';

UPDATE hr.leave_type
   SET prorated_first_year = TRUE,
       updated_at = now()
 WHERE leave_type_code = 'PERSONAL';

-- ---------------------------------------------------------------------
-- Defect 3 -- PERSONAL's 3-day cap was scoped wrongly: a per-request CONSECUTIVE-day rule applied
-- to EVERYONE (V116, carried over from the superseded 2561 announcement's "ไม่อนุญาตให้ลากิจติดต่อกัน
-- เกิน 3 วัน" -- "consecutive" (ติดต่อกัน) is the key word). The CURRENT (2567) text drops "ติดต่อกัน"
-- and moves the 3-day figure INSIDE the under-one-year parenthesis:
--
--   พนักงานที่ผ่านทดลองงานแล้วสามารถลากิจได้ 7 วันต่อปี (กรณีอายุงานไม่ถึงหนึ่งปี ให้ลาเป็นสัดส่วนตาม
--   อายุงาน และไม่อนุญาตให้ลากิจเกิน 3 วัน)
--
-- Owner-ruled reading (2026-08-02): a TOTAL annual ceiling of 3 days, scoped to employees with UNDER
-- one year of service -- not a per-request consecutive-day rule that binds everyone, including
-- employees with 10 years of tenure. The old blanket rule denied a veteran employee's legitimate
-- 4-consecutive-day PERSONAL request even though the current text no longer restricts them at all.
--
-- Fix: max_consecutive_days -> NULL (removes the blanket per-request rule; the column stays --
-- it correctly describes NOTHING today, not "nothing, forever" -- a future leave type could still
-- use it for an actual consecutive-day rule). A new first_year_max_days column carries the
-- tenure-scoped total-day ceiling as data, matching prorated_first_year's precedent, rather than
-- hardcoding "3" for PERSONAL a second time in Java (LeaveService#autoRejectNote already reads
-- prorated_first_year off this same table for the identical PERSONAL row). NULL = no such ceiling
-- (every type except PERSONAL). See LeaveService#autoRejectNote for how this composes with
-- prorated_first_year: the effective cap for a first-year employee is
-- min(prorated annual quota, first_year_max_days), and the column has NO effect at all once the
-- employee has cleared 12 months of service (not "applies with an unlimited cap" -- does not apply).
ALTER TABLE hr.leave_type
    ADD COLUMN first_year_max_days NUMERIC(5,2);

COMMENT ON COLUMN hr.leave_type.first_year_max_days IS
    'Total days of this type an employee under 12 completed months of service may take in a quota '
    'year, ON TOP OF (not instead of) prorated_first_year''s scaled-down quota -- the effective cap '
    'is min(prorated annual quota, first_year_max_days); see LeaveService#autoRejectNote. NULL = no '
    'such ceiling. Has NO effect once the employee has cleared 12 months of service -- this is not '
    'an "unlimited cap" for a tenured employee, the rule simply does not apply to them at all. Seeded '
    'for PERSONAL only (3.00, §5.2''s "...และไม่อนุญาตให้ลากิจเกิน 3 วัน"), replacing the old '
    'everyone-and-consecutive-only max_consecutive_days=3 rule the pre-2567 announcement stated.';

ALTER TABLE hr.leave_type
    ADD CONSTRAINT chk_leave_type_first_year_max_days_positive CHECK (
        first_year_max_days IS NULL OR first_year_max_days > 0
    );

UPDATE hr.leave_type
   SET max_consecutive_days = NULL,
       first_year_max_days = 3.00,
       updated_at = now()
 WHERE leave_type_code = 'PERSONAL';

-- ---------------------------------------------------------------------
-- Defect 2 -- MILITARY wrongly capped the LEAVE ITSELF at 60 days instead of capping only the PAY.
--
-- §5.5's literal text: "ลาเพื่อรับราชการทหาร ลาได้ตามที่ราชการกำหนด โดยได้รับค่าจ้างไม่เกิน 60 วันต่อปี"
-- -- "...may take leave as ordered by the military authority [uncapped duration], receiving pay for
-- no more than 60 days per year". V116 seeded annual_quota_days = 60.00 with no paid_days_cap at
-- all, which makes 60 days the LEAVE limit (LeaveService#remainingDays refuses anything beyond the
-- statutory quota), not the pay limit -- a call-up longer than 60 days was refused outright past day
-- 60, when the announcement only intends the last 30 of a 90-day call-up to go unpaid, not refused.
--
-- Fix: paid_days_cap = 60.00 (the real, announcement-stated limit -- now on the column V116 built
-- for exactly this shape, see its MATERNITY precedent) and annual_quota_days raised to a sentinel
-- effectively-unlimited value. NOT NULL forbids leaving annual_quota_days unset entirely, and 0/NULL
-- would either forbid every request or break chk_leave_type_quota_positive (annual_quota_days > 0,
-- V13) -- 366.00 is the chosen sentinel because no single leave request can ever exceed a full
-- calendar year's worth of days (366 covers a leap year), so it can never itself bind as a real
-- limit while still satisfying the NOT NULL/positive constraints and chk_leave_type_paid_days_cap's
-- "paid_days_cap <= annual_quota_days" check (60 <= 366). This is a SENTINEL, not a real policy
-- number -- do not "correct" it back toward 60 in a future migration; that would silently
-- reintroduce this exact defect.
UPDATE hr.leave_type
   SET paid_days_cap = 60.00,
       annual_quota_days = 366.00,
       updated_at = now()
 WHERE leave_type_code = 'MILITARY';
