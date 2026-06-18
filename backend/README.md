# GL&R HR Spring Boot Backend

Spring Boot API for the existing React HR portal. It targets PostgreSQL and can run against Supabase for the demo or an on-prem PostgreSQL database for production.

## Design

- `JdbcTemplate` services map the normalized `hr` schema into the JSON shape used by the frontend.
- Flyway `V1` contains the attached employee master schema. On a fresh database it creates the HR schemas; on an existing Supabase database, `baseline-on-migrate=true` treats the current schema as version 1.
- Flyway `V2` adds backend-owned tables for profile change requests and emergency contacts.
- Auth uses `hr.app_user`, `hr.role`, and `hr.user_role`, stored in the HTTP session cookie.
- PDPA-sensitive fields are read from `hr_restricted.employee_pii` only for `hr` and `admin` users.

## Run With Supabase Demo DB

Use the same hostname for frontend and backend during local demos so the session cookie is sent consistently.

Your local Supabase values are in `.env.local`, which is ignored by Git. To start with that file:

```bash
cd backend
set -a
source .env.local
set +a
mvn spring-boot:run
```

For another Supabase project, copy `.env.example` to `.env.local` and fill in the host, user, and password.

Then run the frontend with API mode:

```bash
cd ../frontend
VITE_USE_MOCKS=false VITE_API_BASE_URL=http://127.0.0.1:8080 npm run dev
```

The `demo` profile enables:

- seed users for `admin@glr.co.th`, `hr@glr.co.th`, `director@glr.co.th`, `employee@glr.co.th`, and `supervisor@glr.co.th`
- password `demo1234`
- quick role login for the frontend demo buttons

## Production Notes

For production/on-prem, do not enable the `demo` profile.

```bash
APP_DEMO_SEED_ENABLED=false
APP_QUICK_ROLE_LOGIN_ENABLED=false
APP_FLYWAY_ENABLED=true
```

Recommended production hardening:

- Use a dedicated app database role with least-privilege grants.
- Grant `hr_restricted` access only if this API is allowed to serve PDPA-sensitive fields to HR/admin.
- Put TLS and secure cookie settings behind the production reverse proxy.
- Replace the temporary password flow with your organization IAM or a password reset workflow.
