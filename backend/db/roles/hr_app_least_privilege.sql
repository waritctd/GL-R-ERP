-- hr_app_least_privilege.sql
-- Issue #25: create a least-privilege application role so the app no longer connects as the
-- Supabase `postgres` superuser/owner.
--
-- WHAT THIS GRANTS hr_app (and nothing more):
--   * hr, sales        -> SELECT / INSERT / UPDATE / DELETE  (+ sequence usage)
--   * hr_restricted    -> SELECT ONLY   (the app only reads PII; it never writes it)
--   * DEFAULT PRIVILEGES so tables/sequences created by FUTURE migrations are usable, provided the
--     migrations run as the SAME admin role you run this script as.
--   * NO superuser, NO CREATE/DDL, NO ownership, nothing on any other schema.
--
-- IMPORTANT: the app runs Flyway at startup, which needs DDL this role deliberately lacks.
-- Run migrations with an admin role and run the app runtime as hr_app with APP_FLYWAY_ENABLED=false.
-- See docs/least-privilege-db-role.md for the full rollout.
--
-- HOW TO RUN (connected to the TARGET database as an admin/owner role, e.g. `postgres`):
--   psql "$ADMIN_DATABASE_URL" \
--     -v ON_ERROR_STOP=1 \
--     -v app_password="'REPLACE_WITH_A_STRONG_SECRET'" \
--     -f backend/db/roles/hr_app_least_privilege.sql
--
-- Re-running is safe (idempotent).

\set ON_ERROR_STOP on

-- 1) Ensure the login role exists (password is set in step 2, so it is never hard-coded here).
SELECT 'CREATE ROLE hr_app LOGIN'
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'hr_app')
\gexec

-- 2) Set / rotate the password to the value passed via -v app_password.
ALTER ROLE hr_app WITH LOGIN PASSWORD :'app_password';

-- 3) Pin down the role's ceiling (these are the defaults; stated explicitly as a guard).
ALTER ROLE hr_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

-- 4) Connect + schema usage.
SELECT format('GRANT CONNECT ON DATABASE %I TO hr_app', current_database())
\gexec
GRANT USAGE ON SCHEMA hr, sales, hr_restricted TO hr_app;

-- 5) DML on the read/write schemas.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA hr    TO hr_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA sales TO hr_app;

-- 6) hr_restricted is read-only for the app (special-category PII is only ever SELECTed).
GRANT SELECT ON ALL TABLES IN SCHEMA hr_restricted TO hr_app;

-- 7) Explicit sequences the app advances directly
--    (hr.employee_code_seq, sales.ticket_code_seq, sales.quotation_code_seq).
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA hr    TO hr_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA sales TO hr_app;

-- 8) Future objects created by later migrations. ALTER DEFAULT PRIVILEGES applies to objects created
--    by the role running this script, so run it as the same admin role Flyway uses for migrations.
ALTER DEFAULT PRIVILEGES IN SCHEMA hr    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO hr_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA sales GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO hr_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA hr_restricted GRANT SELECT ON TABLES TO hr_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA hr    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO hr_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA sales GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO hr_app;

-- 9) Sanity check (optional): list privileges the role now holds on hr tables.
--   \dp hr.*
