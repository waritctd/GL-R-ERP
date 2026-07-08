-- =====================================================================
-- UAT BRANCH ONLY: synthetic leave + overtime activity, materialized
-- from `ERP Documentation/UAT Deliverables/UAT_Test_Data.xlsx`
-- (LeaveBalances + Employees sheets, exported as scratch/LeaveBalances.csv
-- and scratch/Employees.csv for this migration). Applies only under the
-- `uat` Spring profile alongside V900/V901.
--
-- Test cases supported: LV-01 (submit + approve a leave request against
-- quota), LV-02 (near-exhausted / exceed-quota warning), LV-03 (sick
-- leave pending an attachment before approval), OT-01 (submit OT),
-- OT-02 (manager approval step), OT-03 (CEO approval step, V34 chain),
-- OT-04 (fully-approved OT minutes feeding payroll), PAY-02 (payroll
-- reads approved OT + approved leave for the period).
--
-- LEAVE (hr.leave_request): there is no hr.leave_balance table after
-- V13 (balances are computed on the fly from approved requests), so
-- LeaveBalances.csv's used_days is reproduced here as concrete APPROVED
-- (or SUBMITTED, per the CSV notes) leave_request rows whose total_days
-- sum to exactly the CSV's used_days per (employee, leave_type):
--   * GLR-0003 SICK used=3: CSV note says "pending request example,
--     needs attachment" -> ONE SUBMITTED (not yet approved) 3-day sick
--     request, attachment_name left NULL (LV-03: blocks approval until
--     an attachment is supplied).
--   * GLR-0005 VACATION used=5 of 6 quota: CSV note flags this as the
--     LV-02 exceed-quota fixture (only 1 day left) -> APPROVED requests
--     totaling 5 days (a 3-day trip + a 2-day trip), quota_remaining_*
--     tracked accordingly (6 -> 3 -> 1).
--   * GLR-0006 PERSONAL used=1: ONE APPROVED 1-day request (6 -> 5 is
--     N/A here; quota is 3, so 3 -> 2).
--   * All other (employee, leave_type) rows in the CSV have used_days=0
--     -> no leave_request row needed (zero is the correct, already-true
--     baseline with no rows).
-- Approver: HR manager persona GLR-0002 (HR-บุคคล / ผู้จัดการฝ่ายบุคคล)
-- reviews all three, since none of GLR-0003/0005/0006 sit in a division
-- with its own division-manager persona seeded in V900 for leave review.
--
-- OVERTIME (hr.overtime_request): Employees.csv has_OT=Y flags exactly
-- GLR-0005, GLR-0010, GLR-0011, GLR-0012 (verified against the CSV, not
-- the illustrative list in the task prompt). Seeded a MIX of workflow
-- states across the V34 manager->CEO approval chain so OT-01..OT-04 and
-- PAY-02 all have fixtures to exercise:
--   * GLR-0005 (sales rep): status APPROVED, full chain (manager_approved_by
--     = sales manager GLR-0007, ceo_approved_by = GLR-0001) -> feeds payroll.
--   * GLR-0010 (warehouse staff): status SUBMITTED (pending, no approvals
--     yet) -> OT-01.
--   * GLR-0011 (production staff): status MANAGER_APPROVED (manager step
--     done via HR manager GLR-0002, standing in for PD which has no
--     division-manager persona; CEO step still pending) -> OT-02/OT-03.
--   * GLR-0012 (QC staff): status APPROVED, full chain (manager_approved_by
--     = GLR-0002, ceo_approved_by = GLR-0001) -> feeds payroll, second
--     PAY-02 fixture.
-- payroll_month is the first-of-month for each work_date (matches the
-- chk_overtime_payroll_month_first CHECK).
--
-- Determinism: no random(). Every row is a literal VALUES list; dates are
-- fixed 2026-06/07 literals from the CSV notes' target month. Re-running
-- this migration is a no-op (Flyway applies each versioned migration
-- exactly once; the WHERE NOT EXISTS guards below additionally make
-- manual re-apply against an already-seeded database safe).
-- =====================================================================

SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- A. LEAVE REQUESTS
-- ---------------------------------------------------------------------

-- GLR-0003 / SICK: 3 days, SUBMITTED (pending attachment), quota 30 -> 27
-- once approved (kept as quota_remaining_before/after = 30/27 to mirror
-- the CSV's target end-state, even while still pending review).
INSERT INTO hr.leave_request (
    employee_id, leave_type_code, start_date, end_date, total_days, quota_year,
    reason, status, quota_remaining_before, quota_remaining_after, requested_by_id
)
SELECT e.employee_id, 'SICK', DATE '2026-06-15', DATE '2026-06-17', 3, 2026,
       'ไข้หวัดใหญ่ ต้องพักรักษาตัว (UAT)', 'SUBMITTED', 30, 27, e.employee_id
FROM hr.employee e
WHERE e.employee_code = 'GLR-0003'
  AND NOT EXISTS (
    SELECT 1 FROM hr.leave_request lr
    WHERE lr.employee_id = e.employee_id AND lr.reason = 'ไข้หวัดใหญ่ ต้องพักรักษาตัว (UAT)'
  );

-- GLR-0005 / VACATION: two APPROVED requests totaling 5 of 6 days
-- (3 + 2), leaving exactly 1 day remaining per the CSV (LV-02 fixture).
INSERT INTO hr.leave_request (
    employee_id, leave_type_code, start_date, end_date, total_days, quota_year,
    reason, status, quota_remaining_before, quota_remaining_after,
    requested_by_id, reviewed_by_id, reviewed_at
)
SELECT e.employee_id, 'VACATION', v.start_date, v.end_date, v.total_days, 2026,
       v.reason, 'APPROVED', v.remaining_before, v.remaining_after,
       e.employee_id,
       (SELECT employee_id FROM hr.employee WHERE employee_code = 'GLR-0002'),
       v.reviewed_at
FROM (VALUES
    (DATE '2026-06-08', DATE '2026-06-10', 3::numeric, 6::numeric, 3::numeric,
     'พักร้อนพักผ่อนครอบครัว (UAT)', TIMESTAMPTZ '2026-06-05 10:00:00+07'),
    (DATE '2026-06-22', DATE '2026-06-23', 2::numeric, 3::numeric, 1::numeric,
     'พักร้อนธุระส่วนตัว (UAT)', TIMESTAMPTZ '2026-06-19 10:00:00+07')
) AS v(start_date, end_date, total_days, remaining_before, remaining_after, reason, reviewed_at)
CROSS JOIN LATERAL (SELECT employee_id FROM hr.employee WHERE employee_code = 'GLR-0005') AS e
WHERE NOT EXISTS (
    SELECT 1 FROM hr.leave_request lr
    WHERE lr.employee_id = e.employee_id AND lr.reason = v.reason
);

-- GLR-0006 / PERSONAL: one APPROVED 1-day request, quota 3 -> 2.
INSERT INTO hr.leave_request (
    employee_id, leave_type_code, start_date, end_date, total_days, quota_year,
    reason, status, quota_remaining_before, quota_remaining_after,
    requested_by_id, reviewed_by_id, reviewed_at
)
SELECT e.employee_id, 'PERSONAL', DATE '2026-06-12', DATE '2026-06-12', 1, 2026,
       'ลากิจธุระส่วนตัว (UAT)', 'APPROVED', 3, 2,
       e.employee_id,
       (SELECT employee_id FROM hr.employee WHERE employee_code = 'GLR-0002'),
       TIMESTAMPTZ '2026-06-10 09:30:00+07'
FROM hr.employee e
WHERE e.employee_code = 'GLR-0006'
  AND NOT EXISTS (
    SELECT 1 FROM hr.leave_request lr
    WHERE lr.employee_id = e.employee_id AND lr.reason = 'ลากิจธุระส่วนตัว (UAT)'
  );

-- ---------------------------------------------------------------------
-- B. OVERTIME REQUESTS (has_OT=Y in Employees.csv: GLR-0005, GLR-0010,
--    GLR-0011, GLR-0012)
-- ---------------------------------------------------------------------

-- GLR-0005: fully APPROVED, manager -> CEO chain complete. Feeds payroll.
INSERT INTO hr.overtime_request (
    employee_id, work_date, planned_start_at, planned_end_at, planned_minutes,
    reason, status, actual_start_at, actual_end_at, actual_minutes, payable_minutes,
    payroll_month, requested_by_id,
    manager_approved_by, manager_approved_at, ceo_approved_by, ceo_approved_at,
    reviewed_by_id, reviewed_at
)
SELECT e.employee_id, DATE '2026-06-25',
       (DATE '2026-06-25' + TIME '18:00'), (DATE '2026-06-25' + TIME '20:00'), 120,
       'ปิดยอดขายเดือนมิถุนายน (UAT)', 'APPROVED',
       (DATE '2026-06-25' + TIME '18:00'), (DATE '2026-06-25' + TIME '20:00'), 120, 120,
       DATE '2026-06-01', e.employee_id,
       mgr.employee_id, TIMESTAMPTZ '2026-06-26 09:00:00+07',
       ceo.employee_id, TIMESTAMPTZ '2026-06-26 15:00:00+07',
       ceo.employee_id, TIMESTAMPTZ '2026-06-26 15:00:00+07'
FROM hr.employee e
CROSS JOIN LATERAL (SELECT employee_id FROM hr.employee WHERE employee_code = 'GLR-0007') AS mgr
CROSS JOIN LATERAL (SELECT employee_id FROM hr.employee WHERE employee_code = 'GLR-0001') AS ceo
WHERE e.employee_code = 'GLR-0005'
  AND NOT EXISTS (
    SELECT 1 FROM hr.overtime_request ot
    WHERE ot.employee_id = e.employee_id AND ot.reason = 'ปิดยอดขายเดือนมิถุนายน (UAT)'
  );

-- GLR-0010: SUBMITTED, pending — no approvals yet (OT-01).
INSERT INTO hr.overtime_request (
    employee_id, work_date, planned_start_at, planned_end_at, planned_minutes,
    reason, status, payroll_month, requested_by_id
)
SELECT e.employee_id, DATE '2026-07-01',
       (DATE '2026-07-01' + TIME '18:00'), (DATE '2026-07-01' + TIME '19:30'), 90,
       'เร่งตรวจนับสต็อกคลังสินค้า (UAT)', 'SUBMITTED',
       DATE '2026-07-01', e.employee_id
FROM hr.employee e
WHERE e.employee_code = 'GLR-0010'
  AND NOT EXISTS (
    SELECT 1 FROM hr.overtime_request ot
    WHERE ot.employee_id = e.employee_id AND ot.reason = 'เร่งตรวจนับสต็อกคลังสินค้า (UAT)'
  );

-- GLR-0011: MANAGER_APPROVED — manager step done (HR manager GLR-0002,
-- standing in for PD which has no division-manager persona), CEO step
-- still pending (OT-02/OT-03 fixture).
INSERT INTO hr.overtime_request (
    employee_id, work_date, planned_start_at, planned_end_at, planned_minutes,
    reason, status, payroll_month, requested_by_id,
    manager_approved_by, manager_approved_at
)
SELECT e.employee_id, DATE '2026-06-30',
       (DATE '2026-06-30' + TIME '17:30'), (DATE '2026-06-30' + TIME '19:30'), 120,
       'เร่งผลิตงานด่วนปลายเดือน (UAT)', 'MANAGER_APPROVED',
       DATE '2026-06-01', e.employee_id,
       mgr.employee_id, TIMESTAMPTZ '2026-07-01 09:00:00+07'
FROM hr.employee e
CROSS JOIN LATERAL (SELECT employee_id FROM hr.employee WHERE employee_code = 'GLR-0002') AS mgr
WHERE e.employee_code = 'GLR-0011'
  AND NOT EXISTS (
    SELECT 1 FROM hr.overtime_request ot
    WHERE ot.employee_id = e.employee_id AND ot.reason = 'เร่งผลิตงานด่วนปลายเดือน (UAT)'
  );

-- GLR-0012: fully APPROVED, manager -> CEO chain complete. Second
-- feeds-payroll fixture (PAY-02).
INSERT INTO hr.overtime_request (
    employee_id, work_date, planned_start_at, planned_end_at, planned_minutes,
    reason, status, actual_start_at, actual_end_at, actual_minutes, payable_minutes,
    payroll_month, requested_by_id,
    manager_approved_by, manager_approved_at, ceo_approved_by, ceo_approved_at,
    reviewed_by_id, reviewed_at
)
SELECT e.employee_id, DATE '2026-06-18',
       (DATE '2026-06-18' + TIME '17:30'), (DATE '2026-06-18' + TIME '19:00'), 90,
       'ตรวจสอบคุณภาพเร่งด่วนก่อนส่งมอบ (UAT)', 'APPROVED',
       (DATE '2026-06-18' + TIME '17:30'), (DATE '2026-06-18' + TIME '19:00'), 90, 90,
       DATE '2026-06-01', e.employee_id,
       mgr.employee_id, TIMESTAMPTZ '2026-06-19 09:00:00+07',
       ceo.employee_id, TIMESTAMPTZ '2026-06-19 14:00:00+07',
       ceo.employee_id, TIMESTAMPTZ '2026-06-19 14:00:00+07'
FROM hr.employee e
CROSS JOIN LATERAL (SELECT employee_id FROM hr.employee WHERE employee_code = 'GLR-0002') AS mgr
CROSS JOIN LATERAL (SELECT employee_id FROM hr.employee WHERE employee_code = 'GLR-0001') AS ceo
WHERE e.employee_code = 'GLR-0012'
  AND NOT EXISTS (
    SELECT 1 FROM hr.overtime_request ot
    WHERE ot.employee_id = e.employee_id AND ot.reason = 'ตรวจสอบคุณภาพเร่งด่วนก่อนส่งมอบ (UAT)'
  );
