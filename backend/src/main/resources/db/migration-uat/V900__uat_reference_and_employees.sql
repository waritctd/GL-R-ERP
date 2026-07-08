-- =====================================================================
-- UAT BRANCH ONLY: materializes the repo's AUTHORED UAT dataset.
--
-- Source of truth: `ERP Documentation/UAT Deliverables/UAT_Test_Data.xlsx`
-- (Employees + PayrollMonth sheets) and
-- `ERP Documentation/UAT Deliverables/UAT_Accounts.md` (9 personas).
-- This migration seeds ONLY reference data + the 32 authored employees
-- (GLR-0001..GLR-0032) + 9 persona login accounts + payroll-volume
-- padding (GLR-1001..GLR-1060). Attendance / leave / OT / sales history
-- are handled by LATER V9xx steps, not here.
--
-- Applies ONLY under the `uat` Spring profile (Flyway locations
-- `classpath:db/migration,classpath:db/migration-uat`); never applied to
-- prod/demo or a plain local run, and independent of db/migration-demo.
--
-- Safety / identifiability:
--   * PII-FREE: every name below is a curated synthetic Thai name (no
--     real individuals). The 32 "authored" employees mirror the
--     UAT_Test_Data.xlsx Employees sheet verbatim (names there are
--     themselves already synthetic test fixtures, e.g. "สมชาย บริหารกิจ").
--   * Bank account numbers are OBVIOUSLY FAKE / non-routable:
--     `999` || zero-padded sequence, deliberately unlike any real THB
--     account format, so nobody mistakes a UAT payroll bank-export for
--     a real one.
--   * Shared temporary password for all 9 persona accounts:
--         Uat@2026
--     BCrypt hash below was generated with:
--         htpasswd -nbBC 10 x 'Uat@2026'
--     and independently verified against Spring Security's
--     BCryptPasswordEncoder (matches() == true; the encoder accepts the
--     $2a$/$2b$/$2y$ prefixes interchangeably). All 9 persona accounts
--     have must_change_password = TRUE so UAT-AUTH-01 (forced change on
--     first login) is exercised naturally by every tester.
--   * Determinism: no `random()` anywhere in this file. All rows are
--     literal VALUES lists or generate_series + curated arrays / CASE
--     keyed on row index, so this migration produces byte-identical
--     output on every run (stable Flyway checksum, reviewable diff).
--     (created_at DEFAULT now() from the base schema is unaffected and
--     fine — Flyway checksums the SQL text, not row timestamps.)
--   * Admin-role caveat (see UAT_Accounts.md): DivisionAccessPolicy's
--     roleFor() only derives ceo/hr/import/sales_manager/sales/employee
--     from division source_codes md/hr/pcim/sa (+ กรรมการ position for
--     ceo). There is no `admin` branch. The synthetic ADMIN division
--     seeded below (mirroring db/migration-demo/V21's ADMIN placeholder)
--     therefore resolves admin@uat.glr to plain role `employee` under
--     the CURRENT auth code, not a superuser role. This is documented in
--     UAT_Accounts.md as a known, pre-existing discrepancy — not
--     something this seed step fixes (auth/role logic is out of scope
--     for seed data). Seeded anyway so testers can confirm the behavior
--     directly instead of assuming it.
-- =====================================================================

-- ---------------------------------------------------------------------
-- A. REFERENCE DATA (ON CONFLICT DO NOTHING — no-op if a real dataset's
--    MD/HR/PCIM/SA/WH/PD/AC/GA/IT divisions already exist).
-- ---------------------------------------------------------------------

-- Divisions: the 9 real division codes from Employees.csv, plus a
-- synthetic ADMIN placeholder division (same pattern as db/migration-
-- demo/V21) purely to host the admin@uat.glr persona.
INSERT INTO hr.division (source_code, name_th) VALUES
    ('MD',    'MD-ผู้บริหารระดับสูง'),
    ('HR',    'HR-บุคคล'),
    ('PCIM',  'PCIM-จัดซื้อต่างประเทศ'),
    ('SA',    'SA-ฝ่ายขาย'),
    ('WH',    'WH-คลังสินค้า'),
    ('PD',    'PD-ฝ่ายผลิต'),
    ('AC',    'AC-บัญชีการเงิน'),
    ('GA',    'GA-ธุรการทั่วไป'),
    ('IT',    'IT-เทคโนโลยีสารสนเทศ'),
    ('ADMIN', 'ADMIN-ผู้ดูแลระบบ')
ON CONFLICT (source_code) DO NOTHING;

-- Departments: distinct (division, department) pairs found in
-- Employees.csv, FK'd to the division rows above. source_code invented
-- (UAT-DEP-*) since the CSV carries no department code.
INSERT INTO hr.department (source_code, name_th, division_id)
SELECT v.source_code, v.name_th, d.division_id
FROM (VALUES
    ('UDEP-MD',   'สำนักกรรมการผู้จัดการ', 'MD'),
    ('UDEP-HR',   'แผนกทรัพยากรบุคคล',     'HR'),
    ('UDEP-PCIM', 'แผนกจัดซื้อต่างประเทศ',  'PCIM'),
    ('UDEP-SA',   'แผนกขายในประเทศ',       'SA'),
    ('UDEP-WH',   'แผนกคลังสินค้า',        'WH'),
    ('UDEP-PD1',  'แผนกผลิต',              'PD'),
    ('UDEP-PD2',  'แผนกควบคุมคุณภาพ',      'PD'),
    ('UDEP-AC1',  'แผนกบัญชี',             'AC'),
    ('UDEP-AC2',  'แผนกการเงิน',           'AC'),
    ('UDEP-GA',   'แผนกธุรการ',            'GA'),
    ('UDEP-IT',   'แผนกไอที',              'IT'),
    ('UDEP-ADM','แผนกผู้ดูแลระบบ',       'ADMIN')
) AS v(source_code, name_th, division_code)
JOIN hr.division d ON d.source_code = v.division_code
ON CONFLICT (source_code) DO NOTHING;

-- Positions: the 15 distinct EXACT Thai position strings from
-- Employees.csv (kept verbatim so DivisionAccessPolicy.isManager() /
-- isExecutive() correctly detect "ผู้จัดการ" / "กรรมการ" substrings),
-- plus one ADMIN placeholder position for the admin@uat.glr persona and
-- one generic padding-population staff position.
INSERT INTO hr.position (source_code, name_th, name_en) VALUES
    ('UPOS-MD1',   'กรรมการผู้จัดการ',           'Managing Director'),
    ('UPOS-SA1',   'ผู้จัดการฝ่ายขาย',            'Sales Manager'),
    ('UPOS-WH1',   'ผู้จัดการฝ่ายคลังสินค้า',      'Warehouse Manager'),
    ('UPOS-HR1',   'ผู้จัดการฝ่ายบุคคล',          'HR Manager'),
    ('UPOS-SA2',   'พนักงานขาย',                'Sales Staff'),
    ('UPOS-WH2',   'พนักงานคลังสินค้า',          'Warehouse Staff'),
    ('UPOS-GEN',  'พนักงานทั่วไป',              'General Staff'),
    ('UPOS-PD1',   'พนักงานฝ่ายผลิต',            'Production Staff'),
    ('UPOS-AC1',   'เจ้าหน้าที่การเงิน',          'Finance Officer'),
    ('UPOS-PD2',   'เจ้าหน้าที่ควบคุมคุณภาพ',      'QC Officer'),
    ('UPOS-PCM', 'เจ้าหน้าที่จัดซื้อต่างประเทศ',  'Import Purchasing Officer'),
    ('UPOS-GA1',   'เจ้าหน้าที่ธุรการ',           'Admin Officer'),
    ('UPOS-AC2',   'เจ้าหน้าที่บัญชี',            'Accounting Officer'),
    ('UPOS-HR2',   'เจ้าหน้าที่บุคคล',            'HR Officer'),
    ('UPOS-IT1',   'เจ้าหน้าที่ไอที',             'IT Officer'),
    ('UPOS-ADM',  'เจ้าหน้าที่ดูแลระบบ',         'System Admin Officer'),
    ('UPOS-PAD',  'พนักงานทั่วไป (ปริมาณ)',      'General Staff (Volume Padding)')
ON CONFLICT (source_code) DO NOTHING;

-- Employment status: Active + Resigned (GLR-0024 / GLR-0025 need the
-- latter).
INSERT INTO hr.employment_status (source_code, name_th, name_en) VALUES
    ('UAT-ACT', 'ปฏิบัติงาน', 'Active'),
    ('UAT-RES', 'ลาออก',      'Resigned')
ON CONFLICT (source_code) DO NOTHING;

-- Resignation type (needed for the hr.resignation FK on the 2 resigned
-- authored employees).
INSERT INTO hr.resignation_type (name_th, name_en) VALUES
    ('ลาออกเอง (UAT)', 'Voluntary resignation (UAT)')
ON CONFLICT (name_th) DO NOTHING;

-- Work location: one HQ row, referenced by every employee below.
INSERT INTO hr.work_location (source_code, name_th) VALUES
    ('UAT-HQ', 'สำนักงานใหญ่ (UAT)')
ON CONFLICT (name_th) DO NOTHING;

-- Bank: KBank, the payroll bank used for every fake account number below.
INSERT INTO hr.bank (name_th) VALUES
    ('ธนาคารกสิกรไทย')
ON CONFLICT (name_th) DO NOTHING;

-- ---------------------------------------------------------------------
-- Shared BCrypt hash for the 9 UAT persona accounts.
-- Plaintext: Uat@2026
-- Generated with: htpasswd -nbBC 10 x 'Uat@2026'  (BCrypt, cost 10)
-- Verified with Spring Security's BCryptPasswordEncoder: matches() ==
-- true for plaintext "Uat@2026" against this exact hash.
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- B. THE 32 AUTHORED EMPLOYEES (GLR-0001..GLR-0032), verbatim from
--    UAT_Test_Data.xlsx / Employees.csv. Salary: PayrollMonth.csv
--    base_salary where present (GLR-0001/0002/0005/0006/0008/0010);
--    otherwise a deterministic THB figure banded by position seniority
--    (ordinary staff 18,000-30,000; "เจ้าหน้าที่" senior staff roughly
--    20,000-32,000; ผู้จัดการ managers 45,000-60,000).
--
--    GLR-0009: division_id / department_id left NULL on purpose (the
--    null-division safe-fallback persona, PR #55) — position only.
--    GLR-0024 / GLR-0025: Resigned, is_active = FALSE, paired with an
--    hr.resignation row below; excluded from the payroll predicate
--    (is_active = TRUE AND COALESCE(current_salary,0) > 0).
--    email / password_hash are NULL for all 32 (data-only rows; the 8
--    persona logins below are layered on top of 8 of these 32 via
--    UPDATE, not separate employees).
-- ---------------------------------------------------------------------
INSERT INTO hr.employee (
    employee_code, first_name_th, last_name_th,
    division_id, department_id, position_id, location_id, status_id,
    pay_type, current_salary, hire_date, is_active
)
SELECT v.employee_code, v.first_name_th, v.last_name_th,
       d.division_id, dep.department_id, pos.position_id,
       loc.location_id, st.status_id,
       v.pay_type, v.salary, v.hire_date, v.is_active
FROM (VALUES
    -- code,        first_name_th, last_name_th,   division, department,   position,      pay_type, salary,       hire_date,     is_active
    ('GLR-0001', 'สมชาย',   'บริหารกิจ',     'MD',   'UDEP-MD',   'UPOS-MD1',   'M', 120000::numeric, DATE '2015-03-01', TRUE),
    ('GLR-0002', 'วรรณา',   'ทรัพยากรบุคคล', 'HR',   'UDEP-HR',   'UPOS-HR1',   'M', 55000::numeric,  DATE '2016-06-15', TRUE),
    ('GLR-0003', 'ปิยะดา',  'งานบุคคล',      'HR',   'UDEP-HR',   'UPOS-HR2',   'M', 24000::numeric,  DATE '2019-01-10', TRUE),
    ('GLR-0004', 'อนุชา',   'จัดซื้อนำเข้า',  'PCIM', 'UDEP-PCIM', 'UPOS-PCM', 'M', 30000::numeric,  DATE '2018-04-01', TRUE),
    ('GLR-0005', 'ธนาพร',   'ขายดี',         'SA',   'UDEP-SA',   'UPOS-SA2',   'M', 28000::numeric,  DATE '2020-02-01', TRUE),
    ('GLR-0006', 'กฤษณะ',   'เซลล์แมน',      'SA',   'UDEP-SA',   'UPOS-SA2',   'M', 27000::numeric,  DATE '2021-08-16', TRUE),
    ('GLR-0007', 'มาลี',    'จัดการขาย',     'SA',   'UDEP-SA',   'UPOS-SA1',   'M', 58000::numeric,  DATE '2014-05-20', TRUE),
    ('GLR-0008', 'ประเสริฐ', 'คลังสินค้า',    'WH',   'UDEP-WH',   'UPOS-WH1',   'M', 32000::numeric,  DATE '2013-09-01', TRUE),
    ('GLR-0010', 'สมหญิง',  'แสงทอง',        'WH',   'UDEP-WH',   'UPOS-WH2',   'D', 24000::numeric,  DATE '2017-01-10', TRUE),
    ('GLR-0011', 'วิชัย',   'เจริญสุข',      'PD',   'UDEP-PD1',  'UPOS-PD1',   'M', 20000::numeric,  DATE '2018-02-11', TRUE),
    ('GLR-0012', 'อรุณี',   'พูลผล',         'PD',   'UDEP-PD2',  'UPOS-PD2',   'M', 23000::numeric,  DATE '2019-03-12', TRUE),
    ('GLR-0013', 'ประพันธ์', 'รุ่งเรือง',     'AC',   'UDEP-AC1',  'UPOS-AC2',   'M', 24000::numeric,  DATE '2020-04-13', TRUE),
    ('GLR-0014', 'สุนิสา',  'ใจดี',          'AC',   'UDEP-AC2',  'UPOS-AC1',   'M', 24500::numeric,  DATE '2021-05-14', TRUE),
    ('GLR-0015', 'ชัยวัฒน์', 'มั่นคง',        'GA',   'UDEP-GA',   'UPOS-GA1',   'D', 21000::numeric,  DATE '2022-06-15', TRUE),
    ('GLR-0016', 'กัลยา',   'ทองดี',         'IT',   'UDEP-IT',   'UPOS-IT1',   'M', 26000::numeric,  DATE '2017-07-16', TRUE),
    ('GLR-0017', 'ธีระ',    'ศรีสุข',        'WH',   'UDEP-WH',   'UPOS-WH2',   'M', 19000::numeric,  DATE '2018-08-17', TRUE),
    ('GLR-0018', 'นภา',     'สายบัว',        'PD',   'UDEP-PD1',  'UPOS-PD1',   'M', 19500::numeric,  DATE '2019-09-18', TRUE),
    ('GLR-0019', 'วีระชัย', 'วงศ์ษา',        'PD',   'UDEP-PD2',  'UPOS-PD2',   'M', 22500::numeric,  DATE '2020-01-10', TRUE),
    ('GLR-0020', 'รัตนา',   'ผลบุญ',         'AC',   'UDEP-AC1',  'UPOS-AC2',   'D', 23500::numeric,  DATE '2021-02-11', TRUE),
    ('GLR-0021', 'สมพงษ์',  'เกษมสุข',       'PD',   'UDEP-PD2',  'UPOS-PD2',   'M', 22000::numeric,  DATE '2022-03-01', TRUE),
    ('GLR-0022', 'ดวงใจ',   'ทรัพย์เพิ่ม',    'AC',   'UDEP-AC1',  'UPOS-AC2',   'D', 20000::numeric,  DATE '2022-04-01', TRUE),
    ('GLR-0023', 'อภิชาติ', 'สว่างวงศ์',     'AC',   'UDEP-AC2',  'UPOS-AC1',   'D', 21500::numeric,  DATE '2022-05-01', TRUE),
    ('GLR-0024', 'จิราภรณ์', 'ยิ้มแย้ม',      'AC',   'UDEP-AC2',  'UPOS-AC1',   'M', 24000::numeric,  DATE '2019-07-01', FALSE),
    ('GLR-0025', 'สุรชัย',  'กล้าหาญ',       'GA',   'UDEP-GA',   'UPOS-GA1',   'M', 21000::numeric,  DATE '2019-07-01', FALSE),
    ('GLR-0026', 'พรทิพย์', 'อ่อนละมัย',     'GA',   'UDEP-GA',   'UPOS-GA1',   'D', 20500::numeric,  DATE '2023-02-01', TRUE),
    ('GLR-0027', 'นิพนธ์',  'เพียรทำ',       'SA',   'UDEP-SA',   'UPOS-SA2',   'M', 19000::numeric,  DATE '2021-11-01', TRUE),
    ('GLR-0028', 'วราภรณ์', 'สุขสมบูรณ์',    'SA',   'UDEP-SA',   'UPOS-SA2',   'M', 19000::numeric,  DATE '2021-11-01', TRUE),
    ('GLR-0029', 'ศักดิ์ชัย', 'แก้วมณี',      'GA',   'UDEP-GA',   'UPOS-GA1',   'M', 21000::numeric,  DATE '2020-06-01', TRUE),
    ('GLR-0030', 'อัจฉรา',  'ทวีทรัพย์',     'IT',   'UDEP-IT',   'UPOS-IT1',   'M', 25000::numeric,  DATE '2020-06-01', TRUE),
    ('GLR-0031', 'บุญเลิศ', 'บุญมา',         'WH',   'UDEP-WH',   'UPOS-WH2',   'M', 19000::numeric,  DATE '2020-06-01', TRUE),
    ('GLR-0032', 'สมหญิง',  'แสงทอง',        'PD',   'UDEP-PD1',  'UPOS-PD1',   'M', 19500::numeric,  DATE '2020-06-01', TRUE)
) AS v(employee_code, first_name_th, last_name_th, division_code, dep_code, pos_code, pay_type, salary, hire_date, is_active)
JOIN hr.division d ON d.source_code = v.division_code
JOIN hr.department dep ON dep.source_code = v.dep_code
JOIN hr.position pos ON pos.source_code = v.pos_code
JOIN hr.work_location loc ON loc.source_code = 'UAT-HQ'
JOIN hr.employment_status st ON st.source_code = CASE WHEN v.is_active THEN 'UAT-ACT' ELSE 'UAT-RES' END
ON CONFLICT (employee_code) DO NOTHING;

-- GLR-0009: the null-division persona. division_id / department_id are
-- left NULL on purpose (safe-fallback test, PR #55) — position is still
-- set. pay_type = D per the CSV.
INSERT INTO hr.employee (
    employee_code, first_name_th, last_name_th,
    division_id, department_id, position_id, location_id, status_id,
    pay_type, current_salary, hire_date, is_active
)
SELECT 'GLR-0009', 'ไม่มี', 'ฝ่ายงาน',
       NULL, NULL, pos.position_id, loc.location_id, st.status_id,
       'D', 18000::numeric, DATE '2022-01-05', TRUE
FROM hr.position pos
JOIN hr.work_location loc ON loc.source_code = 'UAT-HQ'
JOIN hr.employment_status st ON st.source_code = 'UAT-ACT'
WHERE pos.source_code = 'UPOS-GEN'
ON CONFLICT (employee_code) DO NOTHING;

-- Resignation rows for the 2 resigned authored employees (dates per the
-- CSV notes column).
INSERT INTO hr.resignation (employee_id, recorded_date, resign_date, resignation_type_id, reason_th)
SELECT e.employee_id, v.resign_date, v.resign_date,
       (SELECT resignation_type_id FROM hr.resignation_type WHERE name_th = 'ลาออกเอง (UAT)'),
       'UAT synthetic resignation fixture'
FROM (VALUES
    ('GLR-0024', DATE '2026-03-31'),
    ('GLR-0025', DATE '2026-05-15')
) AS v(employee_code, resign_date)
JOIN hr.employee e ON e.employee_code = v.employee_code
ON CONFLICT (employee_id) DO NOTHING;

-- ---------------------------------------------------------------------
-- C. BANK ACCOUNTS for every authored employee with has_bank = Y in
--    Employees.csv. Deliberately EXCLUDED: GLR-0009, GLR-0021, GLR-0026
--    (has_bank = N) — these three drive the "bank export warns, not
--    crashes" UAT scenario. account_no is an obviously-fake, non-
--    routable number: '999' || zero-padded numeric suffix of the
--    employee_code.
-- ---------------------------------------------------------------------
INSERT INTO hr.employee_bank_account (employee_id, bank_id, account_no, branch)
SELECT e.employee_id,
       (SELECT bank_id FROM hr.bank WHERE name_th = 'ธนาคารกสิกรไทย'),
       '999' || LPAD(SUBSTRING(e.employee_code FROM '[0-9]+$'), 7, '0'),
       'สาขาทดสอบ UAT'
FROM hr.employee e
WHERE e.employee_code IN (
    'GLR-0001','GLR-0002','GLR-0003','GLR-0004','GLR-0005','GLR-0006','GLR-0007','GLR-0008',
    'GLR-0010','GLR-0011','GLR-0012','GLR-0013','GLR-0014','GLR-0015','GLR-0016','GLR-0017',
    'GLR-0018','GLR-0019','GLR-0020','GLR-0022','GLR-0023','GLR-0024','GLR-0025',
    'GLR-0027','GLR-0028','GLR-0029','GLR-0030','GLR-0031','GLR-0032'
)
ON CONFLICT (employee_id) DO NOTHING;

-- ---------------------------------------------------------------------
-- D. 9 PERSONA LOGIN ACCOUNTS (UAT_Accounts.md). 8 of the 9 personas are
--    layered onto EXISTING authored employees via UPDATE (they do not
--    get new employee rows); only admin@uat.glr needs a brand-new
--    employee (GLR-0033) because no authored row lives in the ADMIN
--    placeholder division. All 9 share the temp password Uat@2026 and
--    must_change_password = TRUE.
--
--    ceo@uat.glr      -> GLR-0001 (MD / กรรมการผู้จัดการ -> role ceo)
--    hr@uat.glr       -> GLR-0002 (HR / ผู้จัดการฝ่ายบุคคล -> role hr)
--    salesmgr@uat.glr -> GLR-0007 (SA / ผู้จัดการฝ่ายขาย -> role sales_manager)
--    sales@uat.glr    -> GLR-0005 (SA / พนักงานขาย -> role sales)
--    import@uat.glr   -> GLR-0004 (PCIM / เจ้าหน้าที่จัดซื้อต่างประเทศ -> role import)
--    divmgr@uat.glr   -> GLR-0008 (WH / ผู้จัดการฝ่ายคลังสินค้า -> role employee, manager=true)
--    employee@uat.glr -> GLR-0029 (GA / เจ้าหน้าที่ธุรการ -> role employee)
--    nulldiv@uat.glr  -> GLR-0009 (division_id NULL -> role employee, safe fallback)
--    admin@uat.glr    -> GLR-0033 (new row, ADMIN placeholder division;
--                        see the admin-role caveat at the top of this file)
-- ---------------------------------------------------------------------
UPDATE hr.employee
SET email = v.email, password_hash = v.password_hash, must_change_password = TRUE
FROM (VALUES
    ('GLR-0001', 'ceo@uat.glr',      '$2y$10$BA.yWRMff5Ppe6w/juKNa.nsGiPkoA7detQ63H8xUfWp5X/dVEkCm'),
    ('GLR-0002', 'hr@uat.glr',       '$2y$10$BA.yWRMff5Ppe6w/juKNa.nsGiPkoA7detQ63H8xUfWp5X/dVEkCm'),
    ('GLR-0007', 'salesmgr@uat.glr', '$2y$10$BA.yWRMff5Ppe6w/juKNa.nsGiPkoA7detQ63H8xUfWp5X/dVEkCm'),
    ('GLR-0005', 'sales@uat.glr',    '$2y$10$BA.yWRMff5Ppe6w/juKNa.nsGiPkoA7detQ63H8xUfWp5X/dVEkCm'),
    ('GLR-0004', 'import@uat.glr',   '$2y$10$BA.yWRMff5Ppe6w/juKNa.nsGiPkoA7detQ63H8xUfWp5X/dVEkCm'),
    ('GLR-0008', 'divmgr@uat.glr',   '$2y$10$BA.yWRMff5Ppe6w/juKNa.nsGiPkoA7detQ63H8xUfWp5X/dVEkCm'),
    ('GLR-0029', 'employee@uat.glr', '$2y$10$BA.yWRMff5Ppe6w/juKNa.nsGiPkoA7detQ63H8xUfWp5X/dVEkCm'),
    ('GLR-0009', 'nulldiv@uat.glr',  '$2y$10$BA.yWRMff5Ppe6w/juKNa.nsGiPkoA7detQ63H8xUfWp5X/dVEkCm')
) AS v(employee_code, email, password_hash)
WHERE hr.employee.employee_code = v.employee_code;

-- admin@uat.glr: brand-new employee in the ADMIN placeholder division.
-- No real role derives 'admin' from DivisionAccessPolicy (see the
-- top-of-file caveat) — resolves to plain role 'employee' today, exactly
-- like the demo branch's DEMO-ADM01 / UAT-ADM01 equivalents. Seeded
-- anyway per UAT_Accounts.md so testers can confirm this directly.
INSERT INTO hr.employee (
    employee_code, first_name_th, last_name_th, email, password_hash, must_change_password,
    division_id, department_id, position_id, location_id, status_id,
    pay_type, current_salary, hire_date, is_active
)
SELECT 'GLR-0033', 'อุบล', 'ดูแลระบบ', 'admin@uat.glr',
       '$2y$10$BA.yWRMff5Ppe6w/juKNa.nsGiPkoA7detQ63H8xUfWp5X/dVEkCm', TRUE,
       d.division_id, dep.department_id, pos.position_id, loc.location_id, st.status_id,
       'M', 40000::numeric, DATE '2023-06-01', TRUE
FROM hr.division d
JOIN hr.department dep ON dep.source_code = 'UDEP-ADM'
JOIN hr.position pos ON pos.source_code = 'UPOS-ADM'
JOIN hr.work_location loc ON loc.source_code = 'UAT-HQ'
JOIN hr.employment_status st ON st.source_code = 'UAT-ACT'
WHERE d.source_code = 'ADMIN'
ON CONFLICT (employee_code) DO NOTHING;

-- Fake bank account for the new admin persona employee too.
INSERT INTO hr.employee_bank_account (employee_id, bank_id, account_no, branch)
SELECT e.employee_id,
       (SELECT bank_id FROM hr.bank WHERE name_th = 'ธนาคารกสิกรไทย'),
       '9990000033',
       'สาขาทดสอบ UAT'
FROM hr.employee e
WHERE e.employee_code = 'GLR-0033'
ON CONFLICT (employee_id) DO NOTHING;

-- =====================================================================
-- E. PAYROLL-VOLUME PADDING: ~60 generated employees (GLR-1001..
--    GLR-1060) distributed across the real divisions/departments/
--    positions seeded above, so payroll/attendance screens have a
--    realistic batch size to page/sort/export, not just the 32
--    hand-authored fixtures. Deterministic Thai names picked by row
--    index from curated arrays (NO random()). All active, pay_type M,
--    salary in the 18,000-45,000 band, email/password_hash NULL (data-
--    only; cannot log in), must_change_password keeps schema default.
--    Each gets a fake bank account. Clearly identifiable via the
--    GLR-1xxx code range (vs. GLR-00xx authored fixtures).
-- =====================================================================
INSERT INTO hr.employee (
    employee_code, first_name_th, last_name_th,
    division_id, department_id, position_id, location_id, status_id,
    pay_type, current_salary, hire_date, is_active
)
SELECT
    'GLR-' || (1000 + n)::text,
    given.name_th,
    family.name_th,
    d.division_id,
    dep.department_id,
    pos.position_id,
    loc.location_id,
    st.status_id,
    'M',
    salary.amount,
    DATE '2022-01-01' + ((n * 7) % 1400),
    TRUE
FROM generate_series(1, 60) AS n
CROSS JOIN LATERAL (
    SELECT (ARRAY[
        'สมชาย','สมหญิง','วิชัย','วิภา','ประเสริฐ','อรุณี','กิตติ','จิราภรณ์','ธนากร','สุนิสา',
        'ณัฐพล','ปิยะดา','วีระ','อัจฉรา','สุรศักดิ์','พรทิพย์','อนุชา','วรรณา','ชัยวัฒน์','ศิริพร'
    ])[((n - 1) % 20) + 1] AS name_th
) AS given
CROSS JOIN LATERAL (
    SELECT (ARRAY[
        'ใจดี','รักเรียน','สุขสันต์','แสงทอง','ศรีสุข','บุญมี','วงศ์สวัสดิ์','เจริญสุข','พูลทรัพย์','มั่นคง',
        'ทองดี','แก้วมณี','ชัยเดช','พงษ์พันธ์','สายทอง','อินทร์แก้ว','เกษมสุข','นาคทอง','เพชรรัตน์','สุวรรณโชติ'
    ])[((n - 1) % 20) + 1] AS name_th
) AS family
-- Distribution: weighted toward operations (SA/WH/PD), a share to
-- AC/HR/GA/IT/PCIM, keyed on fixed row-index bands.
CROSS JOIN LATERAL (
    SELECT CASE
        WHEN n <= 15 THEN 'SA'
        WHEN n <= 27 THEN 'WH'
        WHEN n <= 39 THEN 'PD'
        WHEN n <= 47 THEN 'AC'
        WHEN n <= 51 THEN 'GA'
        WHEN n <= 55 THEN 'IT'
        WHEN n <= 58 THEN 'HR'
        ELSE 'PCIM'
    END AS division_code
) AS band
JOIN hr.division d ON d.source_code = band.division_code
CROSS JOIN LATERAL (
    SELECT CASE
        WHEN band.division_code = 'SA'   THEN 'UDEP-SA'
        WHEN band.division_code = 'WH'   THEN 'UDEP-WH'
        WHEN band.division_code = 'PD' AND n % 2 = 0 THEN 'UDEP-PD1'
        WHEN band.division_code = 'PD'   THEN 'UDEP-PD2'
        WHEN band.division_code = 'AC' AND n % 2 = 0 THEN 'UDEP-AC1'
        WHEN band.division_code = 'AC'   THEN 'UDEP-AC2'
        WHEN band.division_code = 'GA'   THEN 'UDEP-GA'
        WHEN band.division_code = 'IT'   THEN 'UDEP-IT'
        WHEN band.division_code = 'HR'   THEN 'UDEP-HR'
        ELSE 'UDEP-PCIM'
    END AS dep_code
) AS depsel
JOIN hr.department dep ON dep.source_code = depsel.dep_code
CROSS JOIN LATERAL (
    SELECT CASE
        WHEN band.division_code = 'SA'   THEN 'UPOS-SA2'
        WHEN band.division_code = 'WH'   THEN 'UPOS-WH2'
        WHEN band.division_code = 'PD' AND depsel.dep_code = 'UDEP-PD1' THEN 'UPOS-PD1'
        WHEN band.division_code = 'PD'   THEN 'UPOS-PD2'
        WHEN band.division_code = 'AC' AND depsel.dep_code = 'UDEP-AC1' THEN 'UPOS-AC2'
        WHEN band.division_code = 'AC'   THEN 'UPOS-AC1'
        WHEN band.division_code = 'GA'   THEN 'UPOS-GA1'
        WHEN band.division_code = 'IT'   THEN 'UPOS-IT1'
        WHEN band.division_code = 'HR'   THEN 'UPOS-HR2'
        ELSE 'UPOS-PCM'
    END AS pos_code
) AS possel
JOIN hr.position pos ON pos.source_code = possel.pos_code
JOIN hr.work_location loc ON loc.source_code = 'UAT-HQ'
JOIN hr.employment_status st ON st.source_code = 'UAT-ACT'
CROSS JOIN LATERAL (
    SELECT (18000 + ((n % 28) * 964))::numeric(12,2) AS amount
) AS salary
ON CONFLICT (employee_code) DO NOTHING;

-- Fake bank accounts for every padding employee (same non-routable
-- '999' || zero-padded-suffix format as the authored 32).
INSERT INTO hr.employee_bank_account (employee_id, bank_id, account_no, branch)
SELECT e.employee_id,
       (SELECT bank_id FROM hr.bank WHERE name_th = 'ธนาคารกสิกรไทย'),
       '999' || LPAD(SUBSTRING(e.employee_code FROM '[0-9]+$'), 7, '0'),
       'สาขาทดสอบ UAT'
FROM hr.employee e
WHERE e.employee_code ~ '^GLR-1[0-9]{3}$'
ON CONFLICT (employee_id) DO NOTHING;
