-- =====================================================================
-- UAT-only (db/migration-uat): resync the employee-code sequence past the seeded padding.
--
-- V3 initializes hr.employee_code_seq from the MAX GLR-<n> present WHEN V3 RUNS -- long before the
-- V900 UAT padding block inserts GLR-1001..GLR-1060. So on a UAT database the sequence can still point
-- inside that seeded range, and the first HR-created employee (EMP-01) would generate e.g. GLR-1001
-- and collide with a seeded row (unique employee_code violation).
--
-- This lives in its OWN migration (not an edit to the already-applied V900) so the checksum of V900 --
-- which is already applied to the live gl-r-erp-uat Supabase DB -- never changes; the live UAT DB just
-- applies this V904 on its next deploy. Runs after V900-V903, so every GLR-<n> UAT row exists by now.
-- =====================================================================

SELECT setval(
    'hr.employee_code_seq',
    COALESCE((
        SELECT MAX(SUBSTRING(employee_code FROM '^GLR-([0-9]+)$')::BIGINT)
          FROM hr.employee
         WHERE employee_code ~ '^GLR-[0-9]+$'
    ), 0),
    TRUE
);
