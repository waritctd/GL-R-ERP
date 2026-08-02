SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- §5.1 SICK: the two rules V116 explicitly deferred ("the '<=3 times/month' tolerance counters
-- (§5.1/§5.2) -- those are later branches"), from "วันเวลาทำงาน และการหยุดงาน" (effective
-- 1 ต.ค. 2567), verbatim:
--
--   "(ต้องมีใบรับรองแพทย์ และยื่นใบลาภายใน 3 วันทำการ) ได้ไม่เกิน 30 วันต่อปี โดยได้รับค่าจ้างตามปกติ
--   หากลาป่วยเกิน 30 วันจะไม่มีสิทธิได้รับค่าจ้าง สำหรับกรณีป่วยเพียงเล็กน้อยไม่ได้ไปพบแพทย์
--   บริษัทอนุโลมให้ลาได้ไม่เกินเดือนละ 3 ครั้ง และต้องแจ้งให้หัวหน้างานทราบก่อนเวลาเริ่มงานในวันนั้น"
--
-- V116/V120 already gave SICK: 30-day quota, requires_attachment=TRUE (V13; today only ever
-- ENFORCED for SICK -- see LeaveService, unchanged by this migration), paid_days_cap=NULL (every
-- quota day paid up to 30; beyond 30 is unpaid via the ordinary quota-exceeded split).
--
-- MONEY NOTE (not validation-only): today LeaveService#autoRejectNote auto-rejects EVERY
-- certificate-less SICK request outright -- no leave granted, no pay. After this migration + its
-- paired LeaveService change, a certificate-less request may be APPROVED AND PAID, up to
-- no_certificate_monthly_tolerance times per calendar month. See
-- LeaveService#sickCertificateNote's Javadoc for the full combined decision table (certificate
-- presence x filing window x monthly tolerance) and the branch's PR body for the plain statement of
-- this effect.
--
-- Both columns are nullable-or-defaulted so every pre-existing row keeps TODAY'S behaviour (no
-- filing-window rule; zero tolerance, i.e. a missing attachment is always rejected) unless this same
-- migration explicitly sets it for SICK. No other leave type's seeded rule values change.
-- ---------------------------------------------------------------------
ALTER TABLE hr.leave_type
    ADD COLUMN certificate_filing_window_days   SMALLINT,
    ADD COLUMN no_certificate_monthly_tolerance  SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN hr.leave_type.certificate_filing_window_days IS
    'Working days (LeaveDayMath/LeaveDayMath#addWorkingDays -- Mon-Fri only, no holiday calendar; a '
    'known, PRE-EXISTING, separately-owned limitation, see LeaveDayMath''s class Javadoc and '
    'CLAUDE.md -- NOT fixed by this migration) after this request''s own start_date by which a '
    'submitted medical certificate must be filed for the request to be approved on that basis; NULL '
    '= no such window (today''s behaviour for every type). Read ONLY when an attachment IS present '
    '-- see LeaveService#sickCertificateNote''s Javadoc: the no-certificate tolerance path below is '
    'judged on occasion count alone and is NEVER checked against this window (the filing-window '
    'clause is grammatically part of the certificate sentence in §5.1, not the tolerance sentence). '
    'Seeded 3 for SICK only (§5.1, "ยื่นใบลาภายใน 3 วันทำการ"). INTERPRETATION (pending owner '
    'confirmation, same caveat as every other §5 interpretive reading in this codebase): the '
    'announcement names no explicit reference event; this reads "within 3 working days" as 3 working '
    'days from the leave''s own start_date -- hr.leave_request has no separate "date of illness" '
    'field, so start_date is the only available proxy for "the sick event".';
COMMENT ON COLUMN hr.leave_type.no_certificate_monthly_tolerance IS
    'Occasions (REQUESTS, not days -- §5.1 says "ครั้ง" [times], unlike every day-count figure '
    'elsewhere in §5 which says "วัน" [days]) per CALENDAR MONTH a request of this type is tolerated '
    'WITHOUT a medical certificate attachment; 0 = no tolerance (a missing attachment is always '
    'rejected -- today''s behaviour, unchanged for every type except SICK). A request''s occasion '
    'month is its own start_date''s calendar month -- the same start_date-only attribution '
    'quota_year already uses (see LeaveService#submit), for the same reason (one row, one '
    'unambiguous month, no cross-boundary splitting). Seeded 3 for SICK only (§5.1, "กรณีป่วยเพียง '
    'เล็กน้อยไม่ได้ไปพบแพทย์ ... ไม่เกินเดือนละ 3 ครั้ง"). This is a ROLLING count computed from '
    'hr.leave_request at request time (see LeaveRepository#countNoCertificateRequestsInMonth), NEVER '
    'a stored counter that could drift from the underlying rows. KNOWN GAP: the announcement''s '
    'same-day supervisor-notification clause ("ต้องแจ้งให้หัวหน้างานทราบก่อนเวลาเริ่มงานในวันนั้น") '
    'has no representation in this schema and is NOT enforced anywhere -- flagged here as a known '
    'gap, not silently dropped.';

ALTER TABLE hr.leave_type
    ADD CONSTRAINT chk_leave_type_certificate_filing_window_positive CHECK (
        certificate_filing_window_days IS NULL OR certificate_filing_window_days > 0
    ),
    ADD CONSTRAINT chk_leave_type_no_certificate_tolerance_nonnegative CHECK (
        no_certificate_monthly_tolerance >= 0
    );

UPDATE hr.leave_type
   SET certificate_filing_window_days = 3,
       no_certificate_monthly_tolerance = 3,
       updated_at = now()
 WHERE leave_type_code = 'SICK';
