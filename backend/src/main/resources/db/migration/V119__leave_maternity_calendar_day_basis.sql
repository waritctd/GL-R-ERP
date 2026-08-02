SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- LEAVE: §5.4 MATERNITY calendar-day counting basis (2026-08-02)
-- ---------------------------------------------------------------------
--
-- Defect this fixes: the governing announcement ("วันเวลาทำงาน และการหยุดงาน", effective 1 ตุลาคม
-- 2567) §5.4 states, literally:
--
--   "การลาคลอดบุตร ลาหยุดได้ไม่เกิน 98 วันต่อปี โดยได้รับค่าจ้างไม่เกิน 45 วัน (รวมถึงลาเพื่อไปตรวจ
--   ครรภ์ก่อนคลอดบุตรด้วย การนับวันลาเพื่อคลอดบุตรให้นับรวมวันหยุดประจำสัปดาห์ วันหยุดตามประเพณี
--   ที่มีระหว่างวันลาการคลอดบุตรด้วย)"
--
-- ("Maternity leave: no more than 98 days per year, of which no more than 45 days are paid
-- (including antenatal-check leave; the counting of maternity leave days shall include weekly
-- holidays and traditional holidays that fall during the maternity leave period).")
--
-- I.e. maternity days are CALENDAR days -- weekly holidays (Sat/Sun) and traditional/public
-- holidays inside the leave period count toward both the 98-day quota and the 45-day paid cap.
-- LeaveDayMath (V85 onward) counts Mon-Fri WORKING days only for every leave type, so a
-- 98-CALENDAR-day maternity leave was being counted as roughly 70 days (98 * 5/7) -- understating
-- both quota consumption and the paid-day cap. This column, and the LeaveDayMath/LeaveService
-- changes in the same branch, fix that for MATERNITY specifically.
--
-- Every other leave type keeps today's working-day counting -- this is a MATERNITY-specific fix,
-- not a general switch to calendar-day counting. See LeaveDayCountBasis's Javadoc for where this
-- is consumed, and LeaveDayMath's basis-aware overloads for the day-counting logic itself: the
-- existing Mon-Fri working-day behaviour is UNTOUCHED (owned by a later six-day-department branch),
-- this adds a second, parallel counting basis alongside it.
ALTER TABLE hr.leave_type
    ADD COLUMN day_count_basis VARCHAR(16) NOT NULL DEFAULT 'WORKING_DAYS'
        CONSTRAINT chk_leave_type_day_count_basis CHECK (day_count_basis IN ('WORKING_DAYS', 'CALENDAR_DAYS'));

COMMENT ON COLUMN hr.leave_type.day_count_basis IS
    'How this leave type''s days are counted for quota/paid-cap/unpaid attribution. WORKING_DAYS '
    '(default, today''s behaviour for every type except MATERNITY): Mon-Fri only, per '
    'LeaveDayMath''s existing Mon-Fri assumption (untouched by this migration -- wrong for six-day '
    'departments, owned by a later branch). CALENDAR_DAYS: every day in the range counts, including '
    'weekly holidays (Sat/Sun) and any traditional/public holiday that falls on a weekday -- per '
    'announcement §5.4: "การนับวันลาเพื่อคลอดบุตรให้นับรวมวันหยุดประจำสัปดาห์ วันหยุดตามประเพณี '
    'ที่มีระหว่างวันลาการคลอดบุตรด้วย" (the counting of maternity leave days shall include weekly '
    'holidays and traditional holidays that fall during the leave period). Only MATERNITY is '
    'CALENDAR_DAYS -- see the UPDATE below for why MILITARY/ORDINATION are deliberately NOT.';

-- MATERNITY: the one leave type §5.4 states this rule for.
UPDATE hr.leave_type
   SET day_count_basis = 'CALENDAR_DAYS',
       updated_at = now()
 WHERE leave_type_code = 'MATERNITY';

-- DECISION (deliberate, not an oversight): MILITARY (§5.5) and ORDINATION (§5.6) stay
-- WORKING_DAYS (the column default already gives them this; no UPDATE needed for either). The
-- announcement's calendar-day-inclusive counting sentence appears ONLY in §5.4, for MATERNITY --
-- §5.5 and §5.6 are silent on how their own day counts include or exclude weekly/traditional
-- holidays. Extending calendar-day counting to MILITARY or ORDINATION would be inventing a rule
-- the text does not state, in either direction (helpful or harmful to the employee), so both keep
-- today's working-day counting until/unless a future announcement or legal reading says otherwise.
-- Every other leave type (SICK, PERSONAL, VACATION, LEAVE_WITHOUT_PAY) is likewise unaffected: none
-- of them carry a §5 sentence like §5.4's, and the column default already preserves their existing
-- behaviour with no UPDATE required.
