-- ฝ่ายขาย (sales division) moves from a five-day to a SIX-day week: Saturday becomes a scheduled
-- working day for every employee in division 'SA', effective 2026-09-01.
--
-- Owner request 2026-09-01. The trigger was concrete: a ทีมขาย employee could not file a leave
-- request for Saturday 2026-09-05 at all -- LeaveService rejects a range containing no scheduled
-- working day with "ช่วงวันลาต้องมีวันทำงานอย่างน้อย 1 วัน" (LeaveService#workingDaysBetween), and
-- under SALES_5D a lone Saturday contains none. Sales genuinely works Saturdays; the schedule data
-- said otherwise, so the data is what changes here. No Java changes accompany this migration --
-- V115's tiered-schedule machinery already reads every row this touches.
--
-- ⚠️ THIS IS A DELIBERATE DEPARTURE FROM ประกาศ 1 ตุลาคม 2567 §1, which lists ฝ่ายขาย (and
-- โชว์รูม) in the FIVE-day group and models Saturday sales cover as a rostered two-person
-- เวรวันหยุด duty (the WEEKEND_DUTY schedule V115 seeds and deliberately assigns to nobody). The
-- owner chose the blanket six-day reading over the per-person roster on 2026-09-01 because the
-- roster does not exist as data and the leave surface has to work today. Recorded here rather than
-- smuggled in, so nobody "corrects" this back to the announcement text without knowing it was a
-- ruling. Reverting is one forward-only migration: close these rows and reinstate the five-day ones.
--
-- WHAT THIS CHANGES BESIDES LEAVE, all of it downstream of WorkSchedule#isWorkday and none of it
-- requiring code changes -- listed because each is a real behaviour change someone will notice:
--   * Leave-day counting (LeaveDayMath#countWorkingDays via LeaveRepository#workingDayPredicate):
--     a Mon-Sat span now counts SIX working days against quota where it counted five.
--   * Attendance (AttendanceDailyCalculator#statusOf): a Saturday with no check-in punch is now
--     MISSING_CHECK_IN instead of NON_WORKDAY, for every sales employee not actually in that day.
--   * Overtime (OvertimeService#suggestDayType): Saturday OT is now derived as WORKDAY (1.50x)
--     rather than HOLIDAY (3.00x). Only affects requests submitted from 2026-09-01 -- day_type and
--     pay_rate_multiplier are frozen onto each row at submit, never recomputed, and prod holds no
--     Saturday sales OT row in any status as of this migration.
--
-- NOT retroactive, by construction: effective_from is 2026-09-01 and the superseded five-day rows
-- are CLOSED at 2026-08-31 rather than deleted, so ScheduleAssignment#covers keeps resolving the
-- old five-day schedule for every date before that. A recalculate-all over past attendance still
-- classifies historical Saturdays exactly as it does today. This mirrors the same back-dating
-- prohibition WorkScheduleAssignmentAdminService enforces on the HR/CEO write path.
--
-- IDEMPOTENT ON PURPOSE. These rows were applied to production by hand on 2026-09-01 so the
-- Saturday leave request was not blocked waiting on a backend image build + Render deploy
-- (autoDeploy is off -- see render.yaml). Every statement below is therefore written to be a no-op
-- against a database that already has the change, so Flyway replaying it on the next prod deploy
-- changes nothing, while a fresh/UAT/CI database gets it for the first time.

-- ---------------------------------------------------------------------------------------------
-- Part A -- SALES_6D: the six-day twin of SALES_5D.
--
-- Identical hours, grace and requires_check_out = FALSE (V117's §4 scan-in-only exemption: field
-- sales visit customers and do not come back to scan out). A NEW schedule rather than adding
-- Saturday to SALES_5D, because editing SALES_5D's day set would rewrite what SALES_5D means for
-- every date it has ever governed -- including the closed rows below that still resolve history.
-- ---------------------------------------------------------------------------------------------

INSERT INTO hr.work_schedule (code, name_th, work_start, work_end, grace_minutes, requires_check_out)
VALUES ('SALES_6D', 'หกวัน จันทร์-เสาร์ 08:30-17:30 (ฝ่ายขาย ทาบเข้าอย่างเดียวได้)',
        TIME '08:30', TIME '17:30', 15, FALSE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO hr.work_schedule_day (work_schedule_id, day_of_week)
SELECT s.work_schedule_id, d.day_of_week
  FROM hr.work_schedule s
  JOIN (VALUES (1), (2), (3), (4), (5), (6)) AS d(day_of_week) ON TRUE
 WHERE s.code = 'SALES_6D'
ON CONFLICT (work_schedule_id, day_of_week) DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- Part B -- close the superseded five-day assignments at 2026-08-31.
--
-- Scoped to rows that are actually live across the changeover (effective_from on or before
-- 2026-08-31, and not already closed on or before it). The effective_from guard also protects the
-- CHECK constraint chk_work_schedule_assignment_effective_range: a future-dated row would end up
-- with effective_to < effective_from. No such row exists in production; if a future environment
-- has one, it is left alone here and Part C's NOT EXISTS guard means this migration simply does
-- not create a conflicting successor -- resolve it by hand rather than letting SQL guess.
-- ---------------------------------------------------------------------------------------------

UPDATE hr.work_schedule_assignment a
   SET effective_to = DATE '2026-08-31'
  FROM hr.division d
 WHERE a.scope_type = 'DIVISION'
   AND a.scope_id = d.division_id
   AND d.source_code = 'SA'
   AND a.effective_from <= DATE '2026-08-31'
   AND (a.effective_to IS NULL OR a.effective_to > DATE '2026-08-31');

-- Department scopes. DEPARTMENT outranks DIVISION in TieredWorkScheduleResolver, so closing the
-- division row alone would reach NOBODY: all 16 active ฝ่ายขาย employees (ทีมขาย 8, Sales Support 1
-- 4, Sales Support 2 4, measured against prod 2026-09-01) sit under a department that carries its
-- own row. Every sales department must move or the change is invisible.
--
-- 'SR' here is the DEPARTMENT โชว์รูม (department_id 4 in prod), not the same-coded โชว์รูม
-- DIVISION -- hr.department and hr.division have independent source_code namespaces. Verified
-- against prod 2026-09-01: all 20 employees filed under department 'SR' are in division 'SA', none
-- of them active, so moving it cannot pull a non-sales employee into a six-day week. Included
-- because the ruling is "all of ฝ่ายขาย", and leaving one of its departments behind would silently
-- exempt whoever is filed there next.
UPDATE hr.work_schedule_assignment a
   SET effective_to = DATE '2026-08-31'
  FROM hr.department dep
 WHERE a.scope_type = 'DEPARTMENT'
   AND a.scope_id = dep.department_id
   AND dep.source_code IN ('SATM', 'SALES', 'SALES2', 'SR')
   AND a.effective_from <= DATE '2026-08-31'
   AND (a.effective_to IS NULL OR a.effective_to > DATE '2026-08-31');

-- ---------------------------------------------------------------------------------------------
-- Part C -- the six-day replacements, effective 2026-09-01.
--
-- Scope ids are resolved by source_code INSIDE the migration, never hardcoded surrogate ids --
-- same convention (and same reason) as V115/V117: those ids came from a legacy import and differ
-- per environment, so on a database without these divisions/departments every statement is a no-op.
--
-- requires_check_out is PRESERVED per group rather than unified: ทีมขาย (and the division-level
-- catch-all) keep the scan-in-only exemption via SALES_6D, while Sales Support 1/2 and โชว์รูม --
-- back-office and showroom staff who are at a scanner all day, and whom V117 deliberately left on
-- OFFICE_5D -- move to OPS_6D, which is Mon-Sat with requires_check_out = TRUE. This migration
-- changes WHICH DAYS are worked and nothing else about how each group's attendance is judged.
-- ---------------------------------------------------------------------------------------------

-- DIVISION 'SA' -> SALES_6D. Reaches ฝ่ายขาย departments with no department-scope row of their own
-- (ออกแบบ 'SADS' and ธุรการขาย 'SAAM' in prod, both zero active today) plus any department added
-- to the division later -- the reason the division tier is set at all rather than only the four
-- departments below.
INSERT INTO hr.work_schedule_assignment (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
SELECT 'DIVISION', d.division_id, s.work_schedule_id, DATE '2026-09-01', NULL
  FROM hr.division d
  JOIN hr.work_schedule s ON s.code = 'SALES_6D'
 WHERE d.source_code = 'SA'
   AND NOT EXISTS (
       SELECT 1
         FROM hr.work_schedule_assignment x
        WHERE x.scope_type = 'DIVISION'
          AND x.scope_id = d.division_id
          AND x.work_schedule_id = s.work_schedule_id
          AND x.effective_from = DATE '2026-09-01');

-- DEPARTMENT 'SATM' (ทีมขาย) -> SALES_6D: field sales, keeps scan-in-only.
INSERT INTO hr.work_schedule_assignment (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
SELECT 'DEPARTMENT', dep.department_id, s.work_schedule_id, DATE '2026-09-01', NULL
  FROM hr.department dep
  JOIN hr.work_schedule s ON s.code = 'SALES_6D'
 WHERE dep.source_code = 'SATM'
   AND NOT EXISTS (
       SELECT 1
         FROM hr.work_schedule_assignment x
        WHERE x.scope_type = 'DEPARTMENT'
          AND x.scope_id = dep.department_id
          AND x.work_schedule_id = s.work_schedule_id
          AND x.effective_from = DATE '2026-09-01');

-- DEPARTMENT 'SALES' / 'SALES2' / 'SR' -> OPS_6D: Mon-Sat, check-out still required.
INSERT INTO hr.work_schedule_assignment (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
SELECT 'DEPARTMENT', dep.department_id, s.work_schedule_id, DATE '2026-09-01', NULL
  FROM hr.department dep
  JOIN hr.work_schedule s ON s.code = 'OPS_6D'
 WHERE dep.source_code IN ('SALES', 'SALES2', 'SR')
   AND NOT EXISTS (
       SELECT 1
         FROM hr.work_schedule_assignment x
        WHERE x.scope_type = 'DEPARTMENT'
          AND x.scope_id = dep.department_id
          AND x.work_schedule_id = s.work_schedule_id
          AND x.effective_from = DATE '2026-09-01');
