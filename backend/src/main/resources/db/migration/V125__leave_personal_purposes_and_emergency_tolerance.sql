SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- LEAVE: §5.2 ลากิจ ("วันเวลาทำงาน และการหยุดงาน", effective 1 ต.ค. 2567/2024) -- the two pieces of
-- §5.2 that were still missing after V116/V120: a purpose taxonomy (with the wedding-leave 3-day
-- cap), and the emergency-filing monthly tolerance ("โดยไม่หักเงิน" -- without deduction).
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- Piece 1 -- leave purpose.
--
-- §5.2's text lists five illustrative purposes, then ends with "เป็นต้น" ("etc.") -- explicitly
-- NON-EXHAUSTIVE. purpose_code is therefore a small closed set of the five NAMED purposes plus
-- OTHER as the catch-all for anything not on the list; OTHER always being available is what keeps
-- this column from ever refusing a legitimate ลากิจ request for lacking a matching category. The
-- free-text `reason` column (already NOT NULL on this table) carries the actual narrative detail
-- regardless of which bucket is chosen here -- purpose_code is a coarse category on top of it, not
-- a replacement for it.
--
-- Nullable, and left nullable for every leave type including PERSONAL -- see
-- LeaveService#normalizePurposeCode's Javadoc for why purpose is OPTIONAL: every request submitted
-- before this migration has no purpose at all, and §5.2 never states that selecting a purpose
-- category is a precondition for filing ลากิจ, only that the wedding-leave sub-rule caps a request
-- IF that is the stated purpose. Making it required would retroactively invalidate every existing
-- flow that never asked for it.
--
-- Scope decision: this column lives on hr.leave_request generically (any leave type may set it),
-- but only PERSONAL's WEDDING purpose carries an enforced consequence today (see
-- LeaveService#autoRejectNote) -- §5.2, and its wedding sub-rule, is specifically about ลากิจ. A
-- non-PERSONAL row setting a purpose is inert metadata, not an error.
ALTER TABLE hr.leave_request
    ADD COLUMN purpose_code VARCHAR(30);

ALTER TABLE hr.leave_request
    ADD CONSTRAINT chk_leave_request_purpose_code CHECK (
        purpose_code IS NULL OR purpose_code IN (
            'DRIVING_LICENSE_OR_GOVERNMENT',
            'FAMILY_NECESSITY',
            'RELIGIOUS_PRACTICE',
            'WEDDING',
            'FAMILY_FUNERAL',
            'OTHER'
        )
    );

COMMENT ON COLUMN hr.leave_request.purpose_code IS
    '§5.2 ลากิจ purpose category, self-declared by the requester at submission. The five named codes '
    '(DRIVING_LICENSE_OR_GOVERNMENT, FAMILY_NECESSITY, RELIGIOUS_PRACTICE, WEDDING, FAMILY_FUNERAL) '
    'mirror the announcement''s illustrative list; OTHER is the catch-all for the list''s trailing '
    '"เป็นต้น" ("etc.") -- §5.2 is explicitly non-exhaustive, so an unlisted purpose must still be '
    'filable. NULL = no purpose selected (optional -- see LeaveService#normalizePurposeCode). Only '
    'WEDDING carries an enforced consequence (the 3-day cap, hardcoded in '
    'LeaveService#autoRejectNote -- see that method''s comment for why this is NOT a hr.leave_type '
    'column); every other code is informational only. Not scoped to a single leave type at the '
    'schema level, but only ever enforced for PERSONAL.';

-- ---------------------------------------------------------------------
-- Piece 2 -- §5.2 emergency ลากิจ tolerance:
--
--   "...ในกรณีฉุกเฉินที่ไม่สามารถส่งใบลากิจล่วงหน้าได้ บริษัทอนุโลมให้ได้ไม่เกินเดือนละ 3 ครั้ง
--   โดยไม่หักเงิน โดยมีเงื่อนไขต้องแจ้งให้หัวหน้างานทราบก่อนเวลาเริ่มงานในวันนั้น และให้ยื่นใบลาต่อ
--   หัวหน้างานเพื่อขออนุญาตลากิจในวันแรกที่กลับเข้าทำงาน"
--
-- ("...in an emergency where a ลากิจ form cannot be filed in advance, the company allows up to 3
-- times a month, without deduction, provided the supervisor is told before the start of work that
-- day, and the leave form is filed with the supervisor for approval on the first day back at
-- work.")
--
-- emergency_monthly_allowance follows the same per-type-rule-as-data precedent V116/V120 already
-- established (advance_notice_days, paid_days_cap, max_consecutive_days, first_year_max_days): the
-- number of times PER CALENDAR MONTH a request of this type may skip the type's own
-- advance_notice_days gate under this exception. NULL = no such exception (every type except
-- PERSONAL today) -- unlike the purpose/wedding rule above, this one genuinely IS a per-type
-- concept (it modifies how THIS type's own notice gate behaves), so it belongs on hr.leave_type,
-- not hardcoded in Java. See LeaveService#autoRejectNote for the combined notice x emergency
-- decision table.
ALTER TABLE hr.leave_type
    ADD COLUMN emergency_monthly_allowance SMALLINT;

ALTER TABLE hr.leave_type
    ADD CONSTRAINT chk_leave_type_emergency_monthly_allowance_positive CHECK (
        emergency_monthly_allowance IS NULL OR emergency_monthly_allowance > 0
    );

COMMENT ON COLUMN hr.leave_type.emergency_monthly_allowance IS
    'Max number of times PER CALENDAR MONTH a request of this type may bypass advance_notice_days '
    'under the §5.2 emergency-filing exception ("อนุโลมให้ได้ไม่เกินเดือนละ 3 ครั้ง โดยไม่หักเงิน"). '
    'NULL = no such exception -- a late request of this type is refused outright, the pre-existing '
    'behaviour. Seeded for PERSONAL only (3), matching §5.2''s literal text. See '
    'LeaveService#autoRejectNote for the enforcement and LeaveRepository#countEmergencyFilings for '
    'how the rolling monthly count is computed (a plain COUNT over existing rows, never a '
    'separately-maintained counter).';

UPDATE hr.leave_type
   SET emergency_monthly_allowance = 3,
       updated_at = now()
 WHERE leave_type_code = 'PERSONAL';

-- emergency_filing (hr.leave_request): records, per request, whether THIS request was actually
-- approved via the emergency exception above (i.e. it violated its type's advance_notice_days but
-- was allowed under the remaining monthly tolerance) -- a FACT about this specific request decided
-- once at submit time, not a counter. LeaveRepository#countEmergencyFilings sums these rows
-- directly for a given employee/type/month, so the monthly tally can never drift the way a
-- separately-maintained running counter could (see the "vacuous test" / stored-counter-drift class
-- of defect this codebase has been bitten by before). FALSE for every request that did not need or
-- use the exception -- on-time requests, requests of a type with no emergency_monthly_allowance,
-- and a late request that used up its monthly allowance and was refused.
--
-- Scope decision, mirroring ux_leave_once_per_employment's precedent exactly (V116): only
-- SUBMITTED/APPROVED rows count toward the monthly tally (see LeaveRepository#countEmergencyFilings)
-- -- a CANCELLED emergency filing releases its slot for the month, the same "cancelling releases
-- the claim" reasoning V116 already applied to ORDINATION's once-per-employment guard.
ALTER TABLE hr.leave_request
    ADD COLUMN emergency_filing BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN hr.leave_request.emergency_filing IS
    'TRUE if this request was approved via the §5.2 emergency-filing exception (it violated its '
    'type''s advance_notice_days but was allowed under the remaining monthly tolerance -- see '
    'hr.leave_type.emergency_monthly_allowance and LeaveService#autoRejectNote). A recorded FACT '
    'about this request, not a counter -- LeaveRepository#countEmergencyFilings sums these rows '
    'directly per employee/type/calendar-month. FALSE for every other request, including every '
    'request of a type with no emergency exception at all.';
