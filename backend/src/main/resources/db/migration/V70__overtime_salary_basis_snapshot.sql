-- Freeze the salary an overtime request is priced from at the time the work was done, instead of
-- re-reading hr.employee.current_salary at payroll time (which silently re-prices already-approved
-- work when the employee's salary changes before payroll runs).

ALTER TABLE hr.overtime_request
    ADD COLUMN IF NOT EXISTS salary_basis NUMERIC(12,2);

COMMENT ON COLUMN hr.overtime_request.salary_basis IS
    'Monthly salary the overtime was priced from, frozen at manager approval and resolved as of '
    'the work date (see OvertimeRepository#findSalaryBasisAsOf). Payroll reads this column instead '
    'of hr.employee.current_salary; the employee join in PayrollRepository stays only as a fallback '
    'for rows approved before this column existed.';

-- Backfill so historical figures do not move: reproduce exactly what the old query would have
-- computed today, by pinning existing rows to the employee's current salary as of this migration.
UPDATE hr.overtime_request ot
   SET salary_basis = COALESCE(e.current_salary, 0)
  FROM hr.employee e
 WHERE e.employee_id = ot.employee_id
   AND ot.salary_basis IS NULL;

ALTER TABLE hr.overtime_request
    ADD CONSTRAINT chk_overtime_request_salary_basis_non_negative
    CHECK (salary_basis IS NULL OR salary_basis >= 0);
