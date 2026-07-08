-- =====================================================================
-- UAT BRANCH ONLY: synthetic, PII-FREE reference data + a realistic
-- employee population (~120) + 7 role login accounts for User
-- Acceptance Testing. This is the FK foundation for later UAT seed
-- steps (attendance / leave / OT synthetic history).
--
-- Applies ONLY under the `uat` Spring profile: Flyway locations for that
-- profile are `classpath:db/migration,classpath:db/migration-uat` (see
-- application-uat.yml). It is never applied to prod/demo or a plain
-- local run, and is mutually exclusive with db/migration-demo/V21.
--
-- Safety / identifiability:
--   * Every row this migration creates uses the `UAT-` employee_code /
--     source_code prefix so it is trivially identifiable and can be
--     bulk-removed (`DELETE FROM hr.employee WHERE employee_code LIKE
--     'UAT-%'` cascades to child rows via ON DELETE CASCADE).
--   * All reference-data inserts are `ON CONFLICT ... DO NOTHING`, so
--     this migration is a safe no-op against any database that already
--     has the real MD/HR/PCIM/SA divisions (e.g. if ever pointed at a
--     real dataset by mistake) and is idempotent on re-run.
--   * Names are drawn from curated, deterministic Thai given/family-name
--     arrays (no real individuals). Emails are `uat.NNNNN@uat.invalid`
--     (RFC 2606 .invalid TLD — cannot be delivered externally).
--   * Bank account numbers are OBVIOUSLY FAKE and non-routable:
--     `9999-0000-<5-digit-sequence>`, deliberately unlike any real THB
--     account format, so nobody mistakes a UAT payroll bank-export for
--     a real one.
--   * Shared password for every UAT account (employees + role logins):
--         Uat@2026
--     BCrypt hash below was generated with:
--         htpasswd -nbBC 10 x 'Uat@2026'
--     and verified to validate against Spring's BCryptPasswordEncoder
--     (accepts $2a$/$2b$/$2y$ prefixes). Role-login accounts have
--     must_change_password = FALSE (testers log straight in); the bulk
--     120 employees have must_change_password = TRUE (exercises the
--     forced-change-on-first-login flow, UAT-AUTH-01).
--   * No `random()` / no `now()`-based literals anywhere below — every
--     value is derived from `generate_series` row index + fixed arrays
--     or fixed literal dates, so the migration produces byte-identical
--     output on every run (stable Flyway checksum, reviewable diff).
--
-- Role derivation note (read before relying on `UAT-ADM01`):
--   `DivisionAccessPolicy.roleFor()` derives role from division
--   source_code md/hr/pcim/sa only; there is no `admin` branch, and
--   `ApplicationRoles` does not list `admin` as an allowed role either.
--   The synthetic `ADMIN` division below (mirroring db/migration-demo's
--   V21 pattern) therefore resolves to plain role `employee` under the
--   CURRENT auth code, not a superuser role -- this is a pre-existing
--   discrepancy in the demo seed's own comments, not something fixed by
--   this migration (auth/role logic is out of scope for a seed-data
--   step). `UAT-ADM01` is still seeded, clearly labelled, so testers can
--   confirm this behavior directly rather than assume it.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Reference data: divisions (ON CONFLICT DO NOTHING => no-op if the real
-- org's MD/HR/PCIM/SA already exist).
-- ---------------------------------------------------------------------
INSERT INTO hr.division (source_code, name_th) VALUES
    ('MD',    'MD-ผู้บริหารระดับสูง'),
    ('HR',    'HR-บุคคล'),
    ('PCIM',  'PCIM-จัดซื้อต่างประเทศ'),
    ('SA',    'SA-ฝ่ายขาย'),
    ('ADMIN', 'ADMIN-ผู้ดูแลระบบ')
ON CONFLICT (source_code) DO NOTHING;

-- Departments per division (a handful each), namespaced UAT-DEPnn so they
-- never collide with a real imported department's source_code.
INSERT INTO hr.department (source_code, name_th, division_id)
SELECT v.source_code, v.name_th, d.division_id
FROM (VALUES
    ('UAT-DEP01', 'สำนักผู้บริหาร',        'MD'),
    ('UAT-DEP02', 'บุคคลและธุรการ',        'HR'),
    ('UAT-DEP03', 'เงินเดือนและสวัสดิการ',  'HR'),
    ('UAT-DEP04', 'จัดซื้อต่างประเทศ',      'PCIM'),
    ('UAT-DEP05', 'ขายโครงการ',           'SA'),
    ('UAT-DEP06', 'ขายหน้าร้าน',           'SA'),
    ('UAT-DEP07', 'สนับสนุนงานขาย',        'SA')
) AS v(source_code, name_th, division_code)
JOIN hr.division d ON d.source_code = v.division_code
ON CONFLICT (source_code) DO NOTHING;

-- Positions spanning staff -> lead -> manager -> exec. Reuse the real
-- canonical manager titles ('ผู้จัดการ' / 'ผู้ช่วยผู้จัดการ', see V30) so
-- DivisionAccessPolicy.isManager() recognizes them the same way it does
-- for real employees; everything else is UAT-namespaced.
INSERT INTO hr.position (source_code, name_th, name_en) VALUES
    ('UAT-STAFF', 'พนักงาน',              'Staff'),
    ('UAT-SRSTAF','พนักงานอาวุโส',         'Senior Staff'),
    ('UAT-LEAD',  'หัวหน้างาน',            'Team Lead'),
    ('UAT-MGR',   'ผู้จัดการ',             'Manager'),
    ('UAT-AMGR',  'ผู้ช่วยผู้จัดการ',       'Assistant Manager'),
    ('UAT-EXEC',  'กรรมการผู้จัดการ',      'Managing Director')
ON CONFLICT (source_code) DO NOTHING;

INSERT INTO hr.employee_level (source_code, name_th, name_en) VALUES
    ('UAT-L1', 'ระดับปฏิบัติการ', 'Operational'),
    ('UAT-L2', 'ระดับหัวหน้างาน', 'Supervisory'),
    ('UAT-L3', 'ระดับบริหาร',    'Managerial')
ON CONFLICT (source_code) DO NOTHING;

INSERT INTO hr.work_location (source_code, name_th) VALUES
    ('UAT-HQ', 'สำนักงานใหญ่ (UAT)')
ON CONFLICT (name_th) DO NOTHING;

INSERT INTO hr.employment_status (source_code, name_th, name_en) VALUES
    ('UAT-ACT', 'ปฏิบัติงาน', 'Active')
ON CONFLICT (source_code) DO NOTHING;

-- Bank reference row for the fake bank-account export (KBank, matching
-- the real org's dominant payroll bank). ON CONFLICT DO NOTHING means
-- this is a no-op if a real 'ธนาคารกสิกรไทย' row already exists.
INSERT INTO hr.bank (name_th) VALUES
    ('ธนาคารกสิกรไทย')
ON CONFLICT (name_th) DO NOTHING;

-- ---------------------------------------------------------------------
-- Shared password hash for ALL UAT accounts below.
-- Plaintext: Uat@2026
-- Generated with: htpasswd -nbBC 10 x 'Uat@2026'  (BCrypt, cost 10)
-- Verified to validate via BCryptPasswordEncoder (matches() = true).
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- Bulk employee population: UAT-00001 .. UAT-00120.
--
-- Deterministic assignment (no random()/now()):
--   - Thai given/family names picked by (row_number - 1) mod array-length
--     from two 30-element curated arrays, combined so every row gets a
--     distinct-looking but fully repeatable name pair.
--   - Distribution across division/department/position/level is driven
--     by fixed row-index bands (via CASE on n), weighted toward SA
--     (sales/operations) per the task's "most in SA/operations" guidance:
--       n 1..70   -> SA department (35 UAT-DEP05 project sales, 35
--                    UAT-DEP06 storefront sales), mostly staff/senior
--                    staff, a few leads/managers
--       n 71..90  -> HR (UAT-DEP02/UAT-DEP03)
--       n 91..110 -> PCIM import (UAT-DEP04)
--       n 111..118-> MD exec office (UAT-DEP01)
--       n 119..120-> MD executives
--   - Salary bands by seniority (THB/month), deterministic by position:
--       staff        18,000 - 35,000 (varies by n mod 18 within band)
--       senior/lead   35,000 - 55,000
--       manager       55,000 - 90,000
--       exec          higher (120,000+)
-- ---------------------------------------------------------------------
INSERT INTO hr.employee (
    employee_code, first_name_th, last_name_th, email, gender,
    division_id, department_id, position_id, level_id, location_id, status_id,
    pay_type, current_salary, hire_date,
    is_active, password_hash, must_change_password
)
SELECT
    'UAT-' || LPAD(n::text, 5, '0'),
    given.name_th,
    family.name_th,
    'uat.' || LPAD(n::text, 5, '0') || '@uat.invalid',
    CASE WHEN n % 2 = 0 THEN 'F' ELSE 'M' END,
    d.division_id,
    dep.department_id,
    pos.position_id,
    lvl.level_id,
    loc.location_id,
    st.status_id,
    'M',
    salary.amount,
    DATE '2022-01-01' + ((n * 11) % 1400),
    TRUE,
    '$2y$10$tYsrdjIjDS/B49Zm.7RPeeP7E1tvvRUWMXO8uaNA0MEIAaKQw7Yky',
    TRUE
FROM generate_series(1, 120) AS n
CROSS JOIN LATERAL (
    SELECT (ARRAY[
        'สมชาย','สมหญิง','วิชัย','วิภา','ประเสริฐ','อรุณี','กิตติ','จิราภรณ์','ธนากร','สุนิสา',
        'ณัฐพล','ปิยะดา','วีระ','อัจฉรา','สุรศักดิ์','พรทิพย์','อนุชา','วรรณา','ชัยวัฒน์','ศิริพร',
        'ธีรพงษ์','กมลวรรณ','พีระพล','สุภาพร','ณรงค์ศักดิ์','เบญจวรรณ','สมศักดิ์','ลัดดาวัลย์','อภิชาติ','รัตนาภรณ์'
    ])[((n - 1) % 30) + 1] AS name_th
) AS given
CROSS JOIN LATERAL (
    SELECT (ARRAY[
        'ใจดี','รักเรียน','สุขสันต์','แสงทอง','ศรีสุข','บุญมี','วงศ์สวัสดิ์','เจริญสุข','พูลทรัพย์','มั่นคง',
        'ทองดี','แก้วมณี','ชัยเดช','พงษ์พันธ์','สายทอง','อินทร์แก้ว','เกษมสุข','นาคทอง','เพชรรัตน์','สุวรรณโชติ',
        'ธนโชติ','ศรีวิไล','บุญเลิศ','วัฒนกุล','ทิพย์มณี','กาญจนพงศ์','สมบูรณ์ดี','พิพัฒน์กุล','จันทร์เพ็ญ','อ่อนละมัย'
    ])[((n - 1) % 30) + 1] AS name_th
) AS family
CROSS JOIN LATERAL (
    SELECT CASE
        WHEN n <= 70 THEN 'SA'
        WHEN n <= 90 THEN 'HR'
        WHEN n <= 110 THEN 'PCIM'
        ELSE 'MD'
    END AS division_code
) AS band
JOIN hr.division d ON d.source_code = band.division_code
CROSS JOIN LATERAL (
    SELECT CASE
        WHEN band.division_code = 'SA' AND n <= 35 THEN 'UAT-DEP05'
        WHEN band.division_code = 'SA'              THEN 'UAT-DEP06'
        WHEN band.division_code = 'HR' AND n <= 80  THEN 'UAT-DEP02'
        WHEN band.division_code = 'HR'               THEN 'UAT-DEP03'
        WHEN band.division_code = 'PCIM'             THEN 'UAT-DEP04'
        ELSE 'UAT-DEP01'
    END AS dep_code
) AS depsel
JOIN hr.department dep ON dep.source_code = depsel.dep_code
CROSS JOIN LATERAL (
    SELECT CASE
        -- Executives: last 2 rows of the MD band.
        WHEN n > 118 THEN 'UAT-EXEC'
        -- Managers: one per department band (first row of each band).
        WHEN n = 1 OR n = 36 OR n = 71 OR n = 91 OR n = 111 THEN 'UAT-MGR'
        -- Assistant managers: second row of the two largest bands.
        WHEN n = 2 OR n = 37 THEN 'UAT-AMGR'
        -- Leads: third row of each band.
        WHEN n = 3 OR n = 38 OR n = 72 OR n = 92 OR n = 112 THEN 'UAT-LEAD'
        -- Senior staff: every 4th row thereafter.
        WHEN n % 4 = 0 THEN 'UAT-SRSTAF'
        ELSE 'UAT-STAFF'
    END AS pos_code
) AS possel
JOIN hr.position pos ON pos.source_code = possel.pos_code
JOIN hr.employee_level lvl ON lvl.source_code = CASE
    WHEN possel.pos_code IN ('UAT-MGR', 'UAT-AMGR', 'UAT-EXEC') THEN 'UAT-L3'
    WHEN possel.pos_code IN ('UAT-LEAD', 'UAT-SRSTAF') THEN 'UAT-L2'
    ELSE 'UAT-L1'
END
JOIN hr.work_location loc ON loc.source_code = 'UAT-HQ'
JOIN hr.employment_status st ON st.source_code = 'UAT-ACT'
CROSS JOIN LATERAL (
    SELECT CASE
        WHEN possel.pos_code = 'UAT-EXEC' THEN 120000 + ((n % 5) * 8000)
        WHEN possel.pos_code IN ('UAT-MGR', 'UAT-AMGR') THEN 55000 + ((n % 8) * 4375)
        WHEN possel.pos_code IN ('UAT-LEAD', 'UAT-SRSTAF') THEN 35000 + ((n % 9) * 2222)
        ELSE 18000 + ((n % 18) * 944)
    END::numeric(12,2) AS amount
) AS salary
ON CONFLICT (employee_code) DO NOTHING;

-- ---------------------------------------------------------------------
-- Fake, non-routable bank accounts for every UAT employee (KBank),
-- so the payroll bank-export screen has something realistic to show.
-- account_no format 9999-0000-<5-digit-seq> is deliberately unlike any
-- real Thai bank account number.
-- ---------------------------------------------------------------------
INSERT INTO hr.employee_bank_account (employee_id, bank_id, account_no, branch)
SELECT e.employee_id,
       (SELECT bank_id FROM hr.bank WHERE name_th = 'ธนาคารกสิกรไทย'),
       '9999-0000-' || LPAD(SUBSTRING(e.employee_code FROM '[0-9]+$'), 5, '0'),
       'สาขาทดสอบ UAT'
FROM hr.employee e
WHERE e.employee_code LIKE 'UAT-%'
  AND e.employee_code ~ '^UAT-[0-9]{5}$'
ON CONFLICT (employee_id) DO NOTHING;

-- =====================================================================
-- Role login accounts: one per role (employee / hr / sales /
-- sales_manager / import / ceo / admin), distinct from the 120 bulk
-- employees above so testers can always find "the" account for a role.
-- must_change_password = FALSE so testers can log straight in with the
-- shared UAT password. current_salary is populated (> 0) so these
-- accounts also show up in a payroll run, per the task's HR-tester
-- requirement.
-- =====================================================================
INSERT INTO hr.employee (
    employee_code, first_name_th, last_name_th, email, gender,
    division_id, department_id, position_id, level_id, location_id, status_id,
    pay_type, current_salary, hire_date,
    is_active, password_hash, must_change_password
)
SELECT v.employee_code, v.first_name_th, v.last_name_th, v.email, v.gender,
       d.division_id, dep.department_id, pos.position_id, lvl.level_id,
       loc.location_id, st.status_id,
       'M', v.salary, DATE '2023-06-01',
       TRUE,
       '$2y$10$tYsrdjIjDS/B49Zm.7RPeeP7E1tvvRUWMXO8uaNA0MEIAaKQw7Yky',
       FALSE
FROM (VALUES
    ('UAT-EMP01', 'อุเทน',   'ทดสอบระบบ', 'uat.emp01@uat.invalid', 'M', 'SA',    'UAT-DEP06', 'UAT-STAFF', 25000::numeric),
    ('UAT-HR01',  'อุไรวรรณ','ทดสอบระบบ', 'uat.hr01@uat.invalid',  'F', 'HR',    'UAT-DEP02', 'UAT-STAFF', 30000::numeric),
    ('UAT-SLS01', 'อุดม',    'ทดสอบระบบ', 'uat.sls01@uat.invalid', 'M', 'SA',    'UAT-DEP05', 'UAT-STAFF', 28000::numeric),
    ('UAT-MGR01', 'อุมาพร',  'ทดสอบระบบ', 'uat.mgr01@uat.invalid', 'F', 'SA',    'UAT-DEP05', 'UAT-MGR',   65000::numeric),
    ('UAT-IMP01', 'อุกฤษฏ์', 'ทดสอบระบบ', 'uat.imp01@uat.invalid', 'M', 'PCIM',  'UAT-DEP04', 'UAT-STAFF', 32000::numeric),
    ('UAT-CEO01', 'อุ่นเรือน','ทดสอบระบบ', 'uat.ceo01@uat.invalid', 'F', 'MD',    'UAT-DEP01', 'UAT-EXEC',  150000::numeric),
    -- No real division derives an 'admin' role today (see top-of-file
    -- note): this account resolves to plain 'employee' under the
    -- current DivisionAccessPolicy, exactly like the demo branch's
    -- equivalent DEMO-ADM01. Seeded anyway so testers can verify that
    -- behavior directly instead of assuming it.
    ('UAT-ADM01', 'อุบล',    'ทดสอบระบบ', 'uat.adm01@uat.invalid', 'F', 'ADMIN', 'UAT-DEP01', 'UAT-STAFF', 40000::numeric)
) AS v(employee_code, first_name_th, last_name_th, email, gender, division_code, dep_code, pos_code, salary)
JOIN hr.division d ON d.source_code = v.division_code
JOIN hr.department dep ON dep.source_code = v.dep_code
JOIN hr.position pos ON pos.source_code = v.pos_code
JOIN hr.employee_level lvl ON lvl.source_code = CASE
    WHEN v.pos_code IN ('UAT-MGR', 'UAT-AMGR', 'UAT-EXEC') THEN 'UAT-L3'
    ELSE 'UAT-L1'
END
JOIN hr.work_location loc ON loc.source_code = 'UAT-HQ'
JOIN hr.employment_status st ON st.source_code = 'UAT-ACT'
ON CONFLICT (employee_code) DO NOTHING;

-- Fake bank accounts for the 7 role-login accounts too (reuse the same
-- non-routable format, seeded off the alpha suffix rather than a numeric
-- employee_code since these codes are not purely numeric).
INSERT INTO hr.employee_bank_account (employee_id, bank_id, account_no, branch)
SELECT e.employee_id,
       (SELECT bank_id FROM hr.bank WHERE name_th = 'ธนาคารกสิกรไทย'),
       '9999-0001-' || LPAD(ROW_NUMBER() OVER (ORDER BY e.employee_code)::text, 5, '0'),
       'สาขาทดสอบ UAT'
FROM hr.employee e
WHERE e.employee_code IN (
    'UAT-EMP01', 'UAT-HR01', 'UAT-SLS01', 'UAT-MGR01', 'UAT-IMP01', 'UAT-CEO01', 'UAT-ADM01'
)
ON CONFLICT (employee_id) DO NOTHING;
