-- Some employees (typically owners/board members) are paid a director's remuneration
-- (ค่าตอบแทนกรรมการ) instead of a regular wage. Director compensation is not "wages" under
-- the Social Security Act (excluded from SSO) but remains fully subject to normal progressive
-- income tax, withheld through payroll exactly like a salary.
ALTER TABLE hr.employee
    ADD COLUMN IF NOT EXISTS compensation_type VARCHAR(10) NOT NULL DEFAULT 'SALARY',
    ADD COLUMN IF NOT EXISTS director_compensation NUMERIC(12,2) NOT NULL DEFAULT 0;

ALTER TABLE hr.employee
    DROP CONSTRAINT IF EXISTS chk_employee_compensation_type,
    ADD CONSTRAINT chk_employee_compensation_type CHECK (compensation_type IN ('SALARY', 'DIRECTOR'));
