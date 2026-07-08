-- =====================================================================
-- UAT BRANCH ONLY: synthetic attendance activity, materialized verbatim
-- from `ERP Documentation/UAT Deliverables/UAT_Test_Data.xlsx` (Attendance
-- sheet, exported as scratch/Attendance.csv for this migration). Applies
-- only under the `uat` Spring profile alongside V900's employee seed.
--
-- Test cases supported: ATT-01 (manual punch entry), ATT-02 (device/
-- biometric punch), ATT-03 (late-arrival minutes surfaced on the daily
-- summary), ATT-04 (valid .dat USB import row lands as a normal punch),
-- and documents (without inserting rows for) the ATT import-failure
-- scenarios ATT-05 (malformed .dat row -> logged import error, not a
-- punch) and ATT-06 (oversized .dat file rejected before processing).
--
-- Deliberately SKIPPED from the CSV (by design, not omission):
--   * GLR-0012 / 2026-07-04 / source=DAT_IMPORT_BAD_ROW: the CSV row has
--     no first_in/last_out — it documents a malformed .dat line that the
--     importer should log to hr.attendance_import_error, not a real
--     punch. Inserting it as an attendance_punch would fabricate data the
--     source system never captured and would violate no real "punch
--     time" existing to store.
--   * emp_code=N/A / 2026-07-04 / source=DAT_IMPORT_OVERSIZED: documents
--     an oversized-file upload rejected before any row-level processing
--     happens — there is no employee, no punch, and emp_code "N/A" does
--     not resolve to any hr.employee row.
-- Both are exercised at the application/service-test layer (upload
-- validation + import-error logging), not via seeded rows here.
--
-- Site/device: reuses the SHOWROOM_SC700 device + SHOWROOM site seeded by
-- the base schema (V7__attendance_schema.sql) — no new device/site rows
-- needed. All 11 real rows are modeled as office/showroom attendance.
--
-- Determinism: no random(). Every row is a literal VALUES list keyed on
-- emp_code + work_date straight from the CSV; punch_time is built from
-- fixed DATE + TIME literals. Re-running this migration is a no-op
-- (Flyway applies each versioned migration exactly once; the WHERE NOT
-- EXISTS / ON CONFLICT guards below additionally make manual re-apply
-- against an already-seeded database safe).
-- =====================================================================

SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- A. RAW PUNCHES (hr.attendance_punch): one check-in + one check-out per
--    CSV row, in the SHOWROOM site, via the existing SHOWROOM_SC700
--    device. badge_code is synthesized from the employee_code (no real
--    badge numbers exist in UAT yet); this mirrors how an unresolved
--    badge would normally be recorded, but here we also set employee_id
--    directly since we know the mapping.
--
--    source mapping (CSV -> punch_source / ingest_method):
--      MANUAL            -> MANUAL     / MANUAL_ENTRY
--      DEVICE            -> BIOMETRIC  / LIVE_CAPTURE
--      DAT_IMPORT_VALID  -> BIOMETRIC  / USB_DAT_IMPORT (real device punch,
--                           delivered via a valid .dat catch-up file)
-- ---------------------------------------------------------------------
INSERT INTO hr.attendance_punch (
    device_id, site_code, employee_id, badge_code, punch_time, work_date,
    punch_state, punch_source, ingest_method
)
SELECT
    (SELECT device_id FROM hr.attendance_device WHERE device_code = 'SHOWROOM_SC700'),
    'SHOWROOM',
    e.employee_id,
    'BADGE-' || SUBSTRING(e.employee_code FROM '[0-9]+$'),
    v.work_date + v.punch_time,
    v.work_date,
    v.punch_state,
    v.punch_source,
    v.ingest_method
FROM (VALUES
    -- emp_code,   work_date,          punch_time, punch_state, punch_source, ingest_method
    ('GLR-0002', DATE '2026-06-29', TIME '08:50', 0, 'MANUAL',    'MANUAL_ENTRY'),
    ('GLR-0002', DATE '2026-06-29', TIME '18:00', 1, 'MANUAL',    'MANUAL_ENTRY'),
    ('GLR-0004', DATE '2026-06-30', TIME '08:51', 0, 'BIOMETRIC', 'LIVE_CAPTURE'),
    ('GLR-0004', DATE '2026-06-30', TIME '18:01', 1, 'BIOMETRIC', 'LIVE_CAPTURE'),
    ('GLR-0005', DATE '2026-07-01', TIME '08:52', 0, 'MANUAL',    'MANUAL_ENTRY'),
    ('GLR-0005', DATE '2026-07-01', TIME '18:02', 1, 'MANUAL',    'MANUAL_ENTRY'),
    ('GLR-0006', DATE '2026-07-02', TIME '08:53', 0, 'BIOMETRIC', 'LIVE_CAPTURE'),
    ('GLR-0006', DATE '2026-07-02', TIME '18:03', 1, 'BIOMETRIC', 'LIVE_CAPTURE'),
    ('GLR-0008', DATE '2026-07-03', TIME '08:54', 0, 'MANUAL',    'MANUAL_ENTRY'),
    ('GLR-0008', DATE '2026-07-03', TIME '18:04', 1, 'MANUAL',    'MANUAL_ENTRY'),
    ('GLR-0011', DATE '2026-06-29', TIME '08:55', 0, 'BIOMETRIC', 'LIVE_CAPTURE'),
    ('GLR-0011', DATE '2026-06-29', TIME '18:05', 1, 'BIOMETRIC', 'LIVE_CAPTURE'),
    ('GLR-0012', DATE '2026-06-30', TIME '08:56', 0, 'MANUAL',    'MANUAL_ENTRY'),
    ('GLR-0012', DATE '2026-06-30', TIME '18:06', 1, 'MANUAL',    'MANUAL_ENTRY'),
    ('GLR-0013', DATE '2026-07-01', TIME '08:57', 0, 'BIOMETRIC', 'LIVE_CAPTURE'),
    ('GLR-0013', DATE '2026-07-01', TIME '18:07', 1, 'BIOMETRIC', 'LIVE_CAPTURE'),
    ('GLR-0014', DATE '2026-07-02', TIME '08:58', 0, 'MANUAL',    'MANUAL_ENTRY'),
    ('GLR-0014', DATE '2026-07-02', TIME '18:08', 1, 'MANUAL',    'MANUAL_ENTRY'),
    ('GLR-0015', DATE '2026-07-03', TIME '08:59', 0, 'BIOMETRIC', 'LIVE_CAPTURE'),
    ('GLR-0015', DATE '2026-07-03', TIME '18:09', 1, 'BIOMETRIC', 'LIVE_CAPTURE'),
    ('GLR-0011', DATE '2026-07-04', TIME '08:58', 0, 'BIOMETRIC', 'USB_DAT_IMPORT'),
    ('GLR-0011', DATE '2026-07-04', TIME '18:02', 1, 'BIOMETRIC', 'USB_DAT_IMPORT')
) AS v(employee_code, work_date, punch_time, punch_state, punch_source, ingest_method)
JOIN hr.employee e ON e.employee_code = v.employee_code
WHERE NOT EXISTS (
    SELECT 1 FROM hr.attendance_punch p
    WHERE p.employee_id = e.employee_id
      AND p.work_date = v.work_date
      AND p.punch_state = v.punch_state
);

-- ---------------------------------------------------------------------
-- B. DAILY SUMMARY (hr.attendance_daily): the table the attendance/
--    payroll UI actually reads. One row per (employee, work_date),
--    derived from the check-in/check-out pair above, late_minutes taken
--    straight from the CSV. total_minutes computed from the two punch
--    times (18:0x - 08:5x = ~550 minutes). check_in_punch_id /
--    check_out_punch_id are resolved back from the punches just inserted.
-- ---------------------------------------------------------------------
INSERT INTO hr.attendance_daily (
    employee_id, work_date, site_code,
    check_in_punch_id, check_out_punch_id,
    check_in, check_out, total_minutes, late_minutes, punch_count
)
SELECT
    e.employee_id, v.work_date, 'SHOWROOM',
    pin.punch_id, pout.punch_id,
    pin.punch_time, pout.punch_time,
    EXTRACT(EPOCH FROM (pout.punch_time - pin.punch_time))::integer / 60,
    v.late_minutes,
    2
FROM (VALUES
    -- emp_code,   work_date,          check_in,   check_out,  late_minutes
    ('GLR-0002', DATE '2026-06-29', TIME '08:50', TIME '18:00', 12),
    ('GLR-0004', DATE '2026-06-30', TIME '08:51', TIME '18:01', 0),
    ('GLR-0005', DATE '2026-07-01', TIME '08:52', TIME '18:02', 0),
    ('GLR-0006', DATE '2026-07-02', TIME '08:53', TIME '18:03', 12),
    ('GLR-0008', DATE '2026-07-03', TIME '08:54', TIME '18:04', 0),
    ('GLR-0011', DATE '2026-06-29', TIME '08:55', TIME '18:05', 0),
    ('GLR-0012', DATE '2026-06-30', TIME '08:56', TIME '18:06', 12),
    ('GLR-0013', DATE '2026-07-01', TIME '08:57', TIME '18:07', 0),
    ('GLR-0014', DATE '2026-07-02', TIME '08:58', TIME '18:08', 0),
    ('GLR-0015', DATE '2026-07-03', TIME '08:59', TIME '18:09', 12),
    ('GLR-0011', DATE '2026-07-04', TIME '08:58', TIME '18:02', 0)
) AS v(employee_code, work_date, in_time, out_time, late_minutes)
JOIN hr.employee e ON e.employee_code = v.employee_code
JOIN hr.attendance_punch pin
    ON pin.employee_id = e.employee_id AND pin.work_date = v.work_date AND pin.punch_state = 0
JOIN hr.attendance_punch pout
    ON pout.employee_id = e.employee_id AND pout.work_date = v.work_date AND pout.punch_state = 1
ON CONFLICT (employee_id, work_date) DO NOTHING;
