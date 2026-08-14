# GL&R HR Portal

This repository is split into two clear applications:

```text
frontend/  React + Vite HR portal
backend/   Spring Boot API for PostgreSQL/Supabase
```

The frontend calls the Spring Boot backend through the existing `/api/*` contract.

## Frontend

```bash
cd frontend
npm install
npm run dev
```

To point the frontend at a local backend:

```bash
cd frontend
VITE_API_BASE_URL=http://127.0.0.1:8080 npm run dev
```

See [frontend/README.md](frontend/README.md).

## Backend

The backend uses Spring Boot, Flyway, and PostgreSQL/Supabase. Local database secrets belong in ignored file `backend/.env.local`; commit only `backend/.env.example`.

```bash
cd backend
set -a
source .env.local
set +a
./mvnw spring-boot:run
```

See [backend/README.md](backend/README.md).

## Tests And Builds

Run the backend unit/controller tests:

```bash
cd backend
./mvnw test
```

15 integration tests are skipped unless `TEST_DB_URL`, `TEST_DB_USERNAME`, and `TEST_DB_PASSWORD` point at a real Postgres. `./mvnw test` passes without them but only runs the Mockito unit tests; set them to also exercise the Flyway/repository integration suite (`AbstractPostgresIntegrationTest`).

Build the backend package:

```bash
cd backend
./mvnw -DskipTests package
```

Build the frontend:

```bash
cd frontend
npm run build
```

Run the frontend lint and test scripts:

```bash
cd frontend
npm run lint
npm test
```

`npm test` runs the Vitest suite.

## Vercel Frontend Deployment

This repo includes a root-level `vercel.json` for Vercel projects whose Root Directory is the repository root. It installs and builds `frontend/`, publishes `frontend/dist`, proxies `/api/*` to the Render backend, and serves `index.html` for React SPA routes.

If the Vercel project Root Directory is set to `frontend`, the matching `frontend/vercel.json` can be used instead.

## Production Shape

For production/on-prem, run the backend with `SPRING_PROFILES_ACTIVE=prod`, the target PostgreSQL datasource, and an absolute persistent `APP_UPLOADS_DIR` path (local disk, SAN/NAS mount, or other backed-up on-prem storage). The `demo` profile is only for seeded showcase data and must not be enabled against a real production database. Authentication uses employee email from `hr.employee`; initial passwords should be issued through the HR reset-password flow, not seeded from employee codes.

### HR reset-password flow (issuing a first or replacement password)

This is the supported way to give a new employee their first password, and the way to recover an
account whose password is lost. It replaced employee-code-derived passwords, which were removed for
security in PR #150.

**Who can do it:** HR only. `EmployeeController#resetPassword` is gated `requireAnyRole(user, "hr")`,
so no other role — CEO included — can issue a password.

**Steps**

1. Sign in as HR and go to **พนักงานทั้งหมด** (`/employees`), then open the employee.
2. In the header, next to **แก้ไข**, open the **⋯** menu and choose **ตั้งรหัสผ่านชั่วคราว**.
3. Confirm. The employee's existing password stops working immediately.
4. The temporary password is displayed. **Copy it before closing the dialog** and pass it to the
   employee through a channel you trust.

**What to know**

- **The password is shown exactly once.** `EmployeeService#resetPassword` returns the plaintext a
  single time and stores only its BCrypt hash, so it cannot be looked up afterwards — not in the
  UI, not in the database, not in the logs. If it is lost, simply reset again; there is no penalty
  beyond re-issuing.
- **The employee is forced to change it at next login.** The reset sets
  `must_change_password = TRUE`, so signing in with the temporary password lands them on the
  change-password screen before they can use the portal.
- **Every reset is audited** as `RESET_EMPLOYEE_PASSWORD` against the acting HR user.
- **A reset alone is not enough if the employee has no email on file.** The password is written to
  `hr.employee`, and login identity is that row's `email` — so an employee with a blank email still
  cannot sign in. Fill the email in first, then reset.
