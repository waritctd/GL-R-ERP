CREATE SEQUENCE IF NOT EXISTS hr.employee_code_seq;

SELECT setval(
    'hr.employee_code_seq',
    GREATEST(
        COALESCE((
            SELECT MAX(SUBSTRING(employee_code FROM '^GLR-([0-9]+)$')::BIGINT)
              FROM hr.employee
             WHERE employee_code ~ '^GLR-[0-9]+$'
        ), 1000),
        1000
    )
);
