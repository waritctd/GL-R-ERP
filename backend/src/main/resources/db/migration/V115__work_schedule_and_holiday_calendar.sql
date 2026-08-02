-- Per-employee/department/division work schedules and a (currently empty) holiday calendar.
--
-- Authority: ประกาศ "วันเวลาทำงาน และการหยุดงาน" effective 1 October 2567 (2024), which SUPERSEDES
-- the 2561/2018 announcement. Owner has confirmed the 2567 version is current practice. Today the
-- whole company runs one hardcoded schedule (Mon-Fri 08:30-17:30, app.attendance.schedule.* / see
-- CompanyWideWorkScheduleResolver); the announcement actually defines several. This migration adds
-- the schema for tiered schedules; TieredWorkScheduleResolver (application code, same branch) reads
-- it and falls back to the existing company-wide schedule for anything unassigned.
--
-- Zero payroll effect: late/early-leave minutes stay reporting-only (Thai LPA §76). This migration
-- touches only hr.work_schedule*/hr.holiday, never payroll or leave tables.

CREATE TABLE hr.work_schedule (
    work_schedule_id  INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code              VARCHAR(30) NOT NULL UNIQUE,
    name_th           VARCHAR(120) NOT NULL,
    work_start        TIME NOT NULL,
    work_end          TIME NOT NULL,
    grace_minutes     SMALLINT NOT NULL DEFAULT 5,
    CONSTRAINT chk_work_schedule_end_after_start CHECK (work_end > work_start),
    CONSTRAINT chk_work_schedule_grace_not_negative CHECK (grace_minutes >= 0)
);

-- Days a schedule applies to. day_of_week is ISO-8601 (1 = Monday .. 7 = Sunday), matching
-- java.time.DayOfWeek#getValue() so the resolver needs no translation table.
CREATE TABLE hr.work_schedule_day (
    work_schedule_id  INTEGER NOT NULL REFERENCES hr.work_schedule(work_schedule_id) ON DELETE CASCADE,
    day_of_week       SMALLINT NOT NULL,
    PRIMARY KEY (work_schedule_id, day_of_week),
    CONSTRAINT chk_work_schedule_day_iso CHECK (day_of_week BETWEEN 1 AND 7)
);

-- Which schedule applies to which scope, effective-dated so a schedule change never silently
-- rewrites history when past days are recalculated (see WorkScheduleResolver's javadoc).
-- scope_id is deliberately untyped BIGINT rather than an FK to any one of hr.employee /
-- hr.department / hr.division -- the scope_type column says which table it points into.
CREATE TABLE hr.work_schedule_assignment (
    assignment_id     INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope_type        VARCHAR(10) NOT NULL,
    scope_id          BIGINT NOT NULL,
    work_schedule_id  INTEGER NOT NULL REFERENCES hr.work_schedule(work_schedule_id),
    effective_from    DATE NOT NULL,
    effective_to      DATE NULL,
    CONSTRAINT chk_work_schedule_assignment_scope_type
        CHECK (scope_type IN ('EMPLOYEE', 'DEPARTMENT', 'DIVISION')),
    CONSTRAINT chk_work_schedule_assignment_effective_range
        CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE INDEX idx_work_schedule_assignment_scope
    ON hr.work_schedule_assignment (scope_type, scope_id, effective_from);

-- Company holiday calendar (นักขัตฤกษ์ / bank holidays per §3). Seeded with ZERO rows: the
-- authoritative Bank of Thailand holiday list has not been supplied to this migration, and
-- inventing dates into a schema migration is worse than shipping an empty table -- a wrong date
-- silently misclassifies a real workday as paid holiday (or vice versa) everywhere it is read.
-- HR populates this table once the BOT list (or the company's own §3 determination) is available.
--
-- §3 note: 2567 removed the 2561 "except the bank mid-year holiday" carve-out. Company holidays
-- are simply the commercial bank holiday list; do not reintroduce that exception here or in code
-- that reads this table.
CREATE TABLE hr.holiday (
    holiday_date  DATE NOT NULL PRIMARY KEY,
    name_th       VARCHAR(120) NOT NULL,
    source        VARCHAR(10) NOT NULL,
    CONSTRAINT chk_holiday_source CHECK (source IN ('BANK', 'COMPANY'))
);

-- ---------------------------------------------------------------------------------------------
-- Seed schedules (ประกาศ 2567, effective 2024-10-01).
-- ---------------------------------------------------------------------------------------------

INSERT INTO hr.work_schedule (code, name_th, work_start, work_end, grace_minutes) VALUES
    ('OFFICE_5D',    'ห้าวัน จันทร์-ศุกร์ 08:30-17:30', TIME '08:30', TIME '17:30', 5),
    ('OPS_6D',       'หกวัน จันทร์-เสาร์ 08:30-17:30',   TIME '08:30', TIME '17:30', 5),
    ('WEEKEND_DUTY', 'เวรวันหยุด เสาร์-อาทิตย์ 09:00-17:00', TIME '09:00', TIME '17:00', 5);

INSERT INTO hr.work_schedule_day (work_schedule_id, day_of_week)
SELECT s.work_schedule_id, d.day_of_week
  FROM hr.work_schedule s
  JOIN (VALUES (1), (2), (3), (4), (5)) AS d(day_of_week) ON TRUE
 WHERE s.code = 'OFFICE_5D';

INSERT INTO hr.work_schedule_day (work_schedule_id, day_of_week)
SELECT s.work_schedule_id, d.day_of_week
  FROM hr.work_schedule s
  JOIN (VALUES (1), (2), (3), (4), (5), (6)) AS d(day_of_week) ON TRUE
 WHERE s.code = 'OPS_6D';

-- WEEKEND_DUTY = Saturday (6) + Sunday (7). Nobody is assigned it by this migration -- see below.
INSERT INTO hr.work_schedule_day (work_schedule_id, day_of_week)
SELECT s.work_schedule_id, d.day_of_week
  FROM hr.work_schedule s
  JOIN (VALUES (6), (7)) AS d(day_of_week) ON TRUE
 WHERE s.code = 'WEEKEND_DUTY';

-- ---------------------------------------------------------------------------------------------
-- Seed assignments. Scope ids are resolved by source_code here, INSIDE the migration, never
-- hardcoded surrogate ids -- those ids came from a legacy import and differ per environment (a
-- fresh/CI/UAT database may have none of these divisions/departments at all, in which case every
-- INSERT ... SELECT below is simply a no-op).
-- ---------------------------------------------------------------------------------------------

-- DIVISION -> OFFICE_5D (five-day group): ฝ่ายบุคคล, ฝ่ายบัญชี, ฝ่ายต่างประเทศ,
-- ฝ่ายสนับสนุนการขาย, พนักงานประจำโชว์รูม, ฝ่ายขาย.
--
-- ฝ่ายจัดซื้อ (PC) is DELIBERATELY NOT here or in the six-day group below. The 2561 announcement
-- put เจ้าหน้าที่จัดซื้อ (purchasing) in the six-day group; the current 2567 announcement lists
-- เจ้าหน้าที่จัดส่ง (delivery) there instead -- a different role that this schema has no
-- division/department for yet (see the OPS_6D comment below). Purchasing therefore falls through
-- to the five-day company default (CompanyWideWorkScheduleResolver), which is correct, not an
-- oversight.
INSERT INTO hr.work_schedule_assignment (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
SELECT 'DIVISION', d.division_id, s.work_schedule_id, DATE '2024-10-01', NULL
  FROM hr.division d
  JOIN hr.work_schedule s ON s.code = 'OFFICE_5D'
 WHERE d.source_code IN ('HR', 'AC', 'PCIM', 'AM', 'SADS', 'QC', 'MD', 'MN', 'SA', 'SR');

-- DIVISION -> OPS_6D (six-day group): ฝ่ายคลังสินค้า, ฝ่ายผลิต, ฝ่ายทำความสะอาด.
-- เจ้าหน้าที่จัดส่ง (delivery) is also in this group per the 2567 announcement, but no
-- division/department currently models it, so no row is seeded for it -- there is nothing to
-- point at. เจ้าหน้าที่บริการ (service) is likewise covered only if/when SV maps to it below.
INSERT INTO hr.work_schedule_assignment (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
SELECT 'DIVISION', d.division_id, s.work_schedule_id, DATE '2024-10-01', NULL
  FROM hr.division d
  JOIN hr.work_schedule s ON s.code = 'OPS_6D'
 WHERE d.source_code IN ('WH', 'PD', 'SV');

-- DEPARTMENT -> OFFICE_5D. These are departments within divisions above (or elsewhere) that the
-- five-day rule names explicitly (ฝ่ายสนับสนุนการขาย / โชว์รูม / ฝ่ายขาย sub-departments).
INSERT INTO hr.work_schedule_assignment (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
SELECT 'DEPARTMENT', dep.department_id, s.work_schedule_id, DATE '2024-10-01', NULL
  FROM hr.department dep
  JOIN hr.work_schedule s ON s.code = 'OFFICE_5D'
 WHERE dep.source_code IN ('SALES', 'SALES2', 'SATM', 'SR');

-- EMPLOYEE -> OPS_6D override: employee_code '10051' (จำเนียร วงค์สา), the company's only active
-- cleaning employee (ฝ่ายทำความสะอาด, six-day group). She is filed under department โชว์รูม, which
-- is a five-day department per the DEPARTMENT rows above, so an employee-scope row is required to
-- override it -- EMPLOYEE outranks DEPARTMENT in TieredWorkScheduleResolver's precedence. A no-op
-- when that employee_code does not exist (UAT/demo databases).
INSERT INTO hr.work_schedule_assignment (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
SELECT 'EMPLOYEE', e.employee_id, s.work_schedule_id, DATE '2024-10-01', NULL
  FROM hr.employee e
  JOIN hr.work_schedule s ON s.code = 'OPS_6D'
 WHERE e.employee_code = '10051';

-- No assignments for WEEKEND_DUTY: ฝ่ายขาย's rostered Sat/Sun (09:00-17:00, counted as NORMAL
-- working time, not overtime) covers 2 people at a time on a rotating basis. That roster does not
-- exist as data yet -- HR assigns WEEKEND_DUTY per person once it is supplied.
