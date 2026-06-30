-- Auth hardening: replace the predictable "password = employee code" scheme with
-- stored BCrypt password hashes. See security advisory GHSA-2fm4-74wf-99rh.
--
-- Existing rows are backfilled by PasswordBackfillRunner (application side), which
-- BCrypts each employee_code as a TEMPORARY password and leaves must_change_password
-- = TRUE so the user is forced to set a real password on next login. Hashing is done
-- in Java (no pgcrypto), so this migration behaves identically on local Postgres and
-- the managed Supabase instance.
ALTER TABLE hr.employee
    ADD COLUMN IF NOT EXISTS password_hash        VARCHAR(255),
    ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT TRUE;

-- Login looks employees up by normalized email (EmployeeAuthRepository.findByEmail);
-- index that hot path so it does not sequential-scan the employee table.
CREATE INDEX IF NOT EXISTS idx_employee_email_lower
    ON hr.employee (LOWER(btrim(email)));
