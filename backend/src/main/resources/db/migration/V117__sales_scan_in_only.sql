-- §4 of the governing announcement (ประกาศ วันเวลาทำงาน และการหยุดงาน, effective 1 ต.ค. 2567) requires
-- every employee to scan in AND out ("พนักงานทุกคนมีหน้าที่ต้องนำบัตรพนักงานไปทาบ เพื่อบันทึกเวลา
-- เข้าและออกงาน"), with one parenthetical exemption: "(ฝ่ายขาย อนุญาตให้ทาบบัตรเข้างานอย่างเดียวได้)"
-- -- the sales division may scan in only. V115 had no notion of this, so a sales employee with only
-- a morning punch was classified MISSING_CHECK_OUT every single working day: permanent false noise
-- that buries the genuine missing-scan cases the flag exists to surface.
--
-- This migration reuses V115's tiered work-schedule machinery (hr.work_schedule /
-- hr.work_schedule_day / hr.work_schedule_assignment, TieredWorkScheduleResolver) rather than
-- inventing a parallel one: it adds a per-schedule requires_check_out flag, seeds a SALES_5D
-- schedule that is identical to OFFICE_5D except for that flag, and reassigns the ฝ่ายขาย
-- division/department rows V115 put on OFFICE_5D onto SALES_5D instead.
--
-- Zero payroll effect: late/early-leave minutes stay reporting-only (Thai LPA §76). This migration
-- touches only hr.work_schedule* (application code, same branch, teaches AttendanceDailyCalculator
-- to stop requiring a check-out when requires_check_out = FALSE), never payroll or leave tables.

ALTER TABLE hr.work_schedule
    ADD COLUMN requires_check_out BOOLEAN NOT NULL DEFAULT TRUE;

INSERT INTO hr.work_schedule (code, name_th, work_start, work_end, grace_minutes, requires_check_out)
VALUES ('SALES_5D', 'ห้าวัน จันทร์-ศุกร์ 08:30-17:30 (ฝ่ายขาย ทาบเข้าอย่างเดียวได้)',
        TIME '08:30', TIME '17:30', 5, FALSE);

INSERT INTO hr.work_schedule_day (work_schedule_id, day_of_week)
SELECT s.work_schedule_id, d.day_of_week
  FROM hr.work_schedule s
  JOIN (VALUES (1), (2), (3), (4), (5)) AS d(day_of_week) ON TRUE
 WHERE s.code = 'SALES_5D';

-- ---------------------------------------------------------------------------------------------
-- Reassign ฝ่ายขาย (division source_code 'SA') and ทีมขาย (department source_code 'SATM') from
-- OFFICE_5D to SALES_5D. Both rows were seeded by V115 with effective_from = 2024-10-01, which is
-- also the mandated effective_from for the replacement SALES_5D rows below (the announcement's own
-- effective date, not a date this migration is free to pick) -- so there is no valid effective_to
-- strictly before the new rows' effective_from
-- (chk_work_schedule_assignment_effective_range requires effective_to >= effective_from), and
-- closing the old rows by setting effective_to would therefore leave at least 2024-10-01 itself
-- double-covered by both the old and new row for the same scope. TieredWorkScheduleResolver's
-- last-write-wins tie-break (assignment_id DESC) would still resolve that single-day overlap
-- correctly, but relying on it to paper over a duplicate is exactly what V115's own javadoc warns
-- against -- so this migration deletes the old rows outright (delete-and-reinsert) instead of
-- setting effective_to, which is the only way to leave zero overlapping live rows for these two
-- scopes.
-- ---------------------------------------------------------------------------------------------

DELETE FROM hr.work_schedule_assignment a
 USING hr.division d, hr.work_schedule s
 WHERE a.scope_type = 'DIVISION'
   AND a.scope_id = d.division_id
   AND d.source_code = 'SA'
   AND a.work_schedule_id = s.work_schedule_id
   AND s.code = 'OFFICE_5D'
   AND a.effective_from = DATE '2024-10-01'
   AND a.effective_to IS NULL;

DELETE FROM hr.work_schedule_assignment a
 USING hr.department dep, hr.work_schedule s
 WHERE a.scope_type = 'DEPARTMENT'
   AND a.scope_id = dep.department_id
   AND dep.source_code = 'SATM'
   AND a.work_schedule_id = s.work_schedule_id
   AND s.code = 'OFFICE_5D'
   AND a.effective_from = DATE '2024-10-01'
   AND a.effective_to IS NULL;

INSERT INTO hr.work_schedule_assignment (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
SELECT 'DIVISION', d.division_id, s.work_schedule_id, DATE '2024-10-01', NULL
  FROM hr.division d
  JOIN hr.work_schedule s ON s.code = 'SALES_5D'
 WHERE d.source_code = 'SA';

INSERT INTO hr.work_schedule_assignment (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
SELECT 'DEPARTMENT', dep.department_id, s.work_schedule_id, DATE '2024-10-01', NULL
  FROM hr.department dep
  JOIN hr.work_schedule s ON s.code = 'SALES_5D'
 WHERE dep.source_code = 'SATM';

-- Sales Support 1 and 2 (department source_codes 'SALES', 'SALES2') are DELIBERATELY LEFT on
-- OFFICE_5D -- untouched by this migration, no row added or removed for them. They are back-office
-- staff who work the announcement's five-day office group; the scan-in-only exemption exists
-- because field sales visit customers and do not return to the office to scan out, which does not
-- describe Sales Support. Their DEPARTMENT rows (seeded by V115, source_codes 'SALES'/'SALES2')
-- already outrank the DIVISION-level 'SA' row in TieredWorkScheduleResolver's precedence, so they
-- resolve OFFICE_5D by construction once the DIVISION row above moves to SALES_5D -- nothing further
-- to do here.
--
-- NOTE this is a genuine ambiguity in the announcement, not a settled fact: "ฝ่ายขาย" (the division)
-- could plausibly be read to include Sales Support, since they are organisationally inside the sales
-- division. This migration resolves that ambiguity as "Sales Support is NOT covered" -- i.e. Sales
-- Support must still scan out -- because the announcement's own reasoning for the exemption (field
-- staff who are not at the office to scan out) does not apply to back-office support staff. If HR's
-- reading differs, only the DEPARTMENT rows for 'SALES'/'SALES2' need to move to SALES_5D in a future
-- forward-only migration; nothing about this migration's shape needs to change.
