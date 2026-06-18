-- =====================================================================
-- HRIS Employee Master Database — PostgreSQL schema
-- Source: Book1.xlsx (Thai HRIS flat export, 909 rows / 205 employees)
-- Target: PostgreSQL 14+
--
-- Design decisions (confirmed):
--   1. De-explode the Cartesian flat file into independent child tables.
--   2. Surrogate identity PKs; employee_code kept as a UNIQUE business key.
--   3. Status derived in ETL -> employment_status_id + is_active.
--   4. supervisor_code -> self-referencing FK (reports_to_employee_id).
--   5. Forward modules (leave / payroll / RBAC) scaffolded empty.
--   6. PDPA: special-category PII isolated in schema hr_restricted.
--
-- Charset: database must be UTF8 (Thai text). e.g.
--   CREATE DATABASE hris ENCODING 'UTF8' LC_COLLATE 'th_TH.UTF-8' ...;
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS hr;             -- general HR data
CREATE SCHEMA IF NOT EXISTS hr_restricted;  -- special-category PII (PDPA)

SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- MASTER / REFERENCE DATA  (slow-changing, deduplicated lookups)
-- ---------------------------------------------------------------------

CREATE TABLE hr.title (
    title_id     SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name_th      VARCHAR(50) NOT NULL,
    name_en      VARCHAR(50),
    UNIQUE (name_th)
);

CREATE TABLE hr.division (             -- ฝ่าย
    division_id  SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_code  VARCHAR(10) UNIQUE,   -- original (รหัส) ฝ่าย, e.g. '0017'
    name_th      VARCHAR(120) NOT NULL,
    name_en      VARCHAR(120),
    is_active    BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE hr.department (           -- แผนก
    department_id SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_code   VARCHAR(10) UNIQUE,
    name_th       VARCHAR(120) NOT NULL,
    name_en       VARCHAR(120),
    division_id   SMALLINT REFERENCES hr.division(division_id),
    is_active     BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE hr.employee_level (       -- ระดับพนักงาน (band: L / M ...)
    level_id     SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_code  VARCHAR(10) UNIQUE,
    name_th      VARCHAR(60) NOT NULL,
    name_en      VARCHAR(60)
);

CREATE TABLE hr.position (             -- ตำแหน่ง
    position_id  SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_code  VARCHAR(10) UNIQUE,
    name_th      VARCHAR(120) NOT NULL,
    name_en      VARCHAR(120),
    is_active    BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE hr.work_location (        -- สถานที่ทำงาน
    location_id  SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_code  VARCHAR(10),
    name_th      VARCHAR(255) NOT NULL,
    name_en      VARCHAR(255),
    UNIQUE (name_th)
);

CREATE TABLE hr.employment_status (    -- สถานะการทำงาน
    status_id    SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_code  VARCHAR(10) UNIQUE,
    name_th      VARCHAR(60) NOT NULL,
    name_en      VARCHAR(60)
);

CREATE TABLE hr.resignation_type (     -- ประเภทลาออก
    resignation_type_id SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name_th      VARCHAR(120) NOT NULL,
    name_en      VARCHAR(120),
    UNIQUE (name_th)
);

CREATE TABLE hr.bank (                  -- ธนาคาร
    bank_id      SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name_th      VARCHAR(120) NOT NULL,
    UNIQUE (name_th)
);

CREATE TABLE hr.country (
    country_id   SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name_th      VARCHAR(120),
    name_en      VARCHAR(120),
    UNIQUE (name_th)
);

-- ---------------------------------------------------------------------
-- CORE: EMPLOYEE MASTER  (one row per real person)
-- ---------------------------------------------------------------------

CREATE TABLE hr.employee (
    employee_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_code      VARCHAR(20) NOT NULL UNIQUE,   -- รหัสพนักงาน (business key)
    badge_card_no      VARCHAR(20),                   -- รหัสบัตรรูด
    title_id           SMALLINT REFERENCES hr.title(title_id),
    first_name_th      VARCHAR(100),
    last_name_th       VARCHAR(100),
    first_name_en      VARCHAR(100),
    last_name_en       VARCHAR(100),
    nickname           VARCHAR(60),
    gender             CHAR(1) CHECK (gender IN ('M','F','U')),  -- ชาย/หญิง
    date_of_birth      DATE,
    birth_place        VARCHAR(120),
    blood_type         VARCHAR(5),
    height_cm          NUMERIC(5,1),
    weight_kg          NUMERIC(5,1),
    nationality        VARCHAR(60),
    marital_status     VARCHAR(30),
    military_status    VARCHAR(60),
    email              VARCHAR(255),
    phone              VARCHAR(40),
    is_foreigner       BOOLEAN NOT NULL DEFAULT FALSE,

    -- current employment snapshot (history lives in employee_assignment)
    division_id        SMALLINT REFERENCES hr.division(division_id),
    department_id      SMALLINT REFERENCES hr.department(department_id),
    position_id        SMALLINT REFERENCES hr.position(position_id),
    level_id           SMALLINT REFERENCES hr.employee_level(level_id),
    location_id        SMALLINT REFERENCES hr.work_location(location_id),
    status_id          SMALLINT REFERENCES hr.employment_status(status_id),
    reports_to_employee_id BIGINT REFERENCES hr.employee(employee_id),  -- org hierarchy

    pay_type           CHAR(1) CHECK (pay_type IN ('M','D')),  -- รายเดือน/รายวัน
    current_salary     NUMERIC(12,2),
    job_grade          VARCHAR(20),                            -- ระดับ JG
    contract_no        VARCHAR(50),

    hire_date          DATE,            -- วันเริ่มงาน
    confirm_date       DATE,            -- วันบรรจุ
    probation_days     SMALLINT,
    contract_start     DATE,
    contract_end       DATE,
    retirement_date    DATE,            -- วันครบเกษียณ

    is_active          BOOLEAN NOT NULL DEFAULT TRUE,  -- derived in ETL
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_no_self_manager CHECK (reports_to_employee_id <> employee_id)
);

COMMENT ON COLUMN hr.employee.is_active IS
  'Derived: FALSE if resignation date present OR source still-employed flag is false.';

-- Banking (1:1, but its own table to keep account numbers out of base scans)
CREATE TABLE hr.employee_bank_account (
    employee_id   BIGINT PRIMARY KEY REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    bank_id       SMALLINT REFERENCES hr.bank(bank_id),
    account_no    VARCHAR(40),     -- เลขที่บัญชีธนาคาร (normalized digits)
    branch        VARCHAR(120)
);

-- Addresses (typed: current vs household-registration)
CREATE TABLE hr.employee_address (
    address_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id   BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    address_type  VARCHAR(20) NOT NULL CHECK (address_type IN ('CURRENT','REGISTERED')),
    house_no      VARCHAR(60),
    building      VARCHAR(120),
    soi           VARCHAR(120),
    road          VARCHAR(120),
    subdistrict   VARCHAR(120),   -- ตำบล
    district      VARCHAR(120),   -- อำเภอ
    province      VARCHAR(120),   -- จังหวัด
    postal_code   VARCHAR(10),
    phone         VARCHAR(40),
    country       VARCHAR(60),
    region        VARCHAR(60),
    UNIQUE (employee_id, address_type)
);

-- Foreigner documents (only when is_foreigner)
CREATE TABLE hr.employee_foreign_doc (
    employee_id        BIGINT PRIMARY KEY REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    passport_no        VARCHAR(40),
    passport_issue_place VARCHAR(120),
    passport_issue_date  DATE,
    passport_expiry      DATE,
    visa_no            VARCHAR(40),
    visa_issue_place   VARCHAR(120),
    visa_issue_date    DATE,
    visa_expiry        DATE,
    work_permit_no     VARCHAR(40),
    work_permit_issue_date  DATE,
    work_permit_expiry      DATE,
    work_permit_issue_place VARCHAR(120)
);

-- Family summary + spouse/parents (1:1)
CREATE TABLE hr.employee_family (
    employee_id        BIGINT PRIMARY KEY REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    spouse_first_name  VARCHAR(100),
    spouse_last_name   VARCHAR(100),
    spouse_dob         DATE,
    spouse_deceased    BOOLEAN,
    children_total     SMALLINT,
    children_sons      SMALLINT,
    children_daughters SMALLINT,
    father_name        VARCHAR(120),
    father_deceased    BOOLEAN,
    mother_name        VARCHAR(120),
    mother_deceased    BOOLEAN,
    parents_separated  BOOLEAN
);

-- ---------------------------------------------------------------------
-- HISTORICAL / TRANSACTIONAL  (the de-exploded 1:M child blocks)
-- ---------------------------------------------------------------------

-- Employment history / assignment timeline + org hierarchy anchor
CREATE TABLE hr.employee_assignment (
    assignment_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id    BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    division_id    SMALLINT REFERENCES hr.division(division_id),
    department_id  SMALLINT REFERENCES hr.department(department_id),
    position_id    SMALLINT REFERENCES hr.position(position_id),
    level_id       SMALLINT REFERENCES hr.employee_level(level_id),
    location_id    SMALLINT REFERENCES hr.work_location(location_id),
    status_id      SMALLINT REFERENCES hr.employment_status(status_id),
    effective_from DATE NOT NULL,
    effective_to   DATE,
    is_current     BOOLEAN NOT NULL DEFAULT TRUE,
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE TABLE hr.education_history (
    education_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id    BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    seq            SMALLINT,                  -- ลำดับการศึกษา
    level          VARCHAR(120),              -- ระดับการศึกษา
    degree         VARCHAR(120),              -- วุฒิการศึกษา
    major          VARCHAR(120),              -- สาขา
    institution    VARCHAR(255),              -- สถาบัน
    country        VARCHAR(60),
    year_start     SMALLINT,
    year_end       SMALLINT,
    grade          VARCHAR(20),
    note           TEXT,
    CHECK (year_end IS NULL OR year_start IS NULL OR year_end >= year_start)
);

CREATE TABLE hr.prior_employment (
    prior_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id    BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    seq            SMALLINT,
    company        VARCHAR(255),
    position       VARCHAR(255),
    job_detail     TEXT,
    company_address VARCHAR(255),
    company_phone  VARCHAR(40),
    date_from      DATE,
    date_to        DATE,
    leave_reason   TEXT,
    total_duration VARCHAR(40),               -- จำนวนรวมเดือน/ปี (kept as-is)
    CHECK (date_to IS NULL OR date_from IS NULL OR date_to >= date_from)
);

CREATE TABLE hr.salary_history (
    salary_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id    BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    effective_date DATE,                      -- วันที่ปรับเงิน
    recorded_date  DATE,                      -- วันที่บันทึก
    old_amount     NUMERIC(12,2),             -- เงินเก่า
    new_amount     NUMERIC(12,2),             -- เงินใหม่
    grade          VARCHAR(20),
    note           TEXT
);

CREATE TABLE hr.employee_child (
    child_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id    BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    seq            SMALLINT,
    full_name      VARCHAR(200),
    date_of_birth  DATE,
    gender         CHAR(1) CHECK (gender IN ('M','F','U')),
    note           TEXT
);

CREATE TABLE hr.resignation (
    employee_id        BIGINT PRIMARY KEY REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    recorded_date      DATE,                  -- วันที่บันทึกลาออก
    resign_date        DATE,                  -- วันที่ลาออก
    resignation_type_id SMALLINT REFERENCES hr.resignation_type(resignation_type_id),
    reason_th          TEXT,
    reason_en          TEXT,
    detail             TEXT
);

-- ---------------------------------------------------------------------
-- SPECIAL-CATEGORY PII  (PDPA-restricted; separate schema + role grants)
-- ---------------------------------------------------------------------
CREATE TABLE hr_restricted.employee_pii (
    employee_id        BIGINT PRIMARY KEY REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    national_id        VARCHAR(20),   -- เลขที่บัตรประชาชน (13 digits)
    national_id_expiry DATE,
    tax_id             VARCHAR(20),   -- เลขที่ผู้เสียภาษี
    social_security_no VARCHAR(20),   -- เลขที่บัตรประกันสังคม
    ss_hospital        VARCHAR(120),  -- โรงพยาบาล ปกส.
    provident_fund_no  VARCHAR(40),   -- เลขที่สมาชิกกองทุน
    -- special-category under PDPA s.26:
    race               VARCHAR(60),   -- เชื้อชาติ
    religion           VARCHAR(60),   -- ศาสนา
    medical_conditions TEXT,          -- โรคประจำตัว
    distinguishing_marks TEXT         -- ตำหนิ/รูปพรรณ
);
COMMENT ON TABLE hr_restricted.employee_pii IS
  'PDPA special-category & national-ID data. Encrypt at rest (e.g. pgcrypto) and grant only to authorized HR roles.';

-- ---------------------------------------------------------------------
-- FORWARD-LOOKING MODULES  (scaffolding; not populated by this migration)
-- ---------------------------------------------------------------------

CREATE TABLE hr.leave_type (
    leave_type_id  SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name_th        VARCHAR(80) NOT NULL,
    name_en        VARCHAR(80),
    days_per_year  NUMERIC(5,1)
);

CREATE TABLE hr.leave_balance (
    balance_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id    BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    leave_type_id  SMALLINT NOT NULL REFERENCES hr.leave_type(leave_type_id),
    year           SMALLINT NOT NULL,
    entitled_days  NUMERIC(6,1) NOT NULL DEFAULT 0,
    used_days      NUMERIC(6,1) NOT NULL DEFAULT 0,
    UNIQUE (employee_id, leave_type_id, year)
);

CREATE TABLE hr.leave_request (
    request_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id    BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    leave_type_id  SMALLINT NOT NULL REFERENCES hr.leave_type(leave_type_id),
    start_date     DATE NOT NULL,
    end_date       DATE NOT NULL,
    days           NUMERIC(5,1) NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    approved_by    BIGINT REFERENCES hr.employee(employee_id),
    CHECK (end_date >= start_date)
);

CREATE TABLE hr.payroll_period (
    period_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    period_start   DATE NOT NULL,
    period_end     DATE NOT NULL,
    pay_date       DATE,
    status         VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    UNIQUE (period_start, period_end)
);

CREATE TABLE hr.payroll_line (
    line_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    period_id      BIGINT NOT NULL REFERENCES hr.payroll_period(period_id) ON DELETE CASCADE,
    employee_id    BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    gross_amount   NUMERIC(12,2) NOT NULL DEFAULT 0,
    deductions     NUMERIC(12,2) NOT NULL DEFAULT 0,
    net_amount     NUMERIC(12,2) NOT NULL DEFAULT 0,
    UNIQUE (period_id, employee_id)
);

-- RBAC: app accounts linked to employees
CREATE TABLE hr.app_user (
    user_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id    BIGINT UNIQUE REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    username       VARCHAR(80) NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL,
    is_enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE hr.role (
    role_id        SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name           VARCHAR(60) NOT NULL UNIQUE,
    description    TEXT
);

CREATE TABLE hr.permission (
    permission_id  SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code           VARCHAR(80) NOT NULL UNIQUE,
    description    TEXT
);

CREATE TABLE hr.role_permission (
    role_id        SMALLINT NOT NULL REFERENCES hr.role(role_id) ON DELETE CASCADE,
    permission_id  SMALLINT NOT NULL REFERENCES hr.permission(permission_id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE hr.user_role (
    user_id        BIGINT NOT NULL REFERENCES hr.app_user(user_id) ON DELETE CASCADE,
    role_id        SMALLINT NOT NULL REFERENCES hr.role(role_id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- ---------------------------------------------------------------------
-- INDEXES
-- ---------------------------------------------------------------------
CREATE INDEX idx_emp_department   ON hr.employee(department_id);
CREATE INDEX idx_emp_division     ON hr.employee(division_id);
CREATE INDEX idx_emp_position     ON hr.employee(position_id);
CREATE INDEX idx_emp_manager      ON hr.employee(reports_to_employee_id);
CREATE INDEX idx_emp_active       ON hr.employee(is_active);
CREATE INDEX idx_emp_name_th      ON hr.employee(last_name_th, first_name_th);
CREATE INDEX idx_assign_emp       ON hr.employee_assignment(employee_id);
CREATE INDEX idx_assign_current   ON hr.employee_assignment(employee_id) WHERE is_current;
CREATE INDEX idx_edu_emp          ON hr.education_history(employee_id);
CREATE INDEX idx_prior_emp        ON hr.prior_employment(employee_id);
CREATE INDEX idx_salary_emp       ON hr.salary_history(employee_id, effective_date);
CREATE INDEX idx_child_emp        ON hr.employee_child(employee_id);
CREATE INDEX idx_addr_emp         ON hr.employee_address(employee_id);
CREATE INDEX idx_leavebal_emp     ON hr.leave_balance(employee_id);
CREATE INDEX idx_payline_emp      ON hr.payroll_line(employee_id);

-- ---------------------------------------------------------------------
-- ETL STAGING + ERROR QUARANTINE
-- ---------------------------------------------------------------------
CREATE TABLE hr.etl_error_log (
    error_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    loaded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    source_row   INTEGER,
    employee_code VARCHAR(20),
    severity     VARCHAR(10),     -- WARN / ERROR
    rule         VARCHAR(80),
    detail       TEXT
);

-- ---------------------------------------------------------------------
-- EXAMPLE RBAC GRANTS (adjust role names to your environment)
-- ---------------------------------------------------------------------
-- CREATE ROLE hr_app LOGIN;          -- application
-- CREATE ROLE hr_officer NOLOGIN;    -- can see PII
-- GRANT USAGE ON SCHEMA hr TO hr_app;
-- GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA hr TO hr_app;
-- GRANT USAGE ON SCHEMA hr_restricted TO hr_officer;
-- GRANT SELECT ON ALL TABLES IN SCHEMA hr_restricted TO hr_officer;
-- (hr_app intentionally has NO grant on hr_restricted.)
