# GL&R ERP (HR + Sales/CRM portal)

```text
frontend/  React 18 + Vite + Tailwind 4 portal (HR self-service, payroll, sales/CRM pipeline)
backend/   Spring Boot 4 / Java 21 API, Flyway migrations, PostgreSQL
docs/      design notes, rulings, API digests
scripts/   backend image push, font check, Thai address import
```

The frontend talks to the backend through the `/api/*` contract. Per-app details live in
[frontend/README.md](frontend/README.md) and [backend/README.md](backend/README.md).

**Prerequisites:** Node `^20.19` or `>=22.12`, Java 21 (the `./mvnw` wrapper is included), and
Docker or a local PostgreSQL 16 for the real-backend path.

## Run a local develop stack

Two ways. Pick **A** to click around in two minutes; pick **B** to test against the real API.

### A. Mock mode (no backend, no database)

The whole API runs in the browser from `frontend/src/api/mockApi.js`, with seeded employees,
leave, overtime, payroll, customers, deals, quotations and a sample price catalogue.

```bash
cd frontend
npm install
VITE_USE_MOCKS=true npm run dev
```

Open <http://localhost:5174>. The login page shows one-click role buttons (ฝ่ายบุคคล, พนักงาน,
ผู้จัดการฝ่าย, ฝ่ายขาย, ผู้จัดการฝ่ายขาย, ฝ่ายนำเข้า, ฝ่ายบัญชี, CEO). No password is needed.
Changes are held in memory and reset on reload.

The mock catalogue is a small sample (14 products, 25 prices across the 9 real factories). It is
enough to exercise the quotation item picker, not to test pricing at full scale. Use path B for that.

### B. Real backend with demo data and quick login

**1. Start PostgreSQL.** The repo's compose file gives you the database the backend expects by
default (`hris`, user `postgres`, password `postgres`, port 5432):

```bash
docker compose up -d db
```

**2. Start the backend with the `demo` profile.** The profile adds `db/migration-demo`, which
seeds one login per role plus sample employees, customers and deals. Flyway creates the whole
schema on first boot.

```bash
cd backend
SPRING_PROFILES_ACTIVE=demo \
SERVER_SESSION_COOKIE_SECURE=false \
APP_MAIL_PROVIDER=log \
./mvnw spring-boot:run
```

`SERVER_SESSION_COOKIE_SECURE=false` is required over plain HTTP, otherwise the browser drops the
session cookie and every request after login is a 401. The API listens on `http://localhost:8080`.

**3. Start the frontend** (a second terminal):

```bash
cd frontend
npm install
npm run dev
```

`frontend/.env.development` already points the dev server at `http://localhost:8080`, turns mocks
off, and unlocks the full app. Open **<http://localhost:5174>** and use `localhost`, not
`127.0.0.1`. The backend cookie is `SameSite=Lax`, and the API is on `localhost`, so a page on
`127.0.0.1` never sends it.

**4. Quick login.** On the dev server the login page shows an **เดโม (เซิร์ฟเวอร์จริง) — เข้าสู่ระบบด่วน** row with one
button per seeded account. The row exists only under `npm run dev` with mocks off. It is removed
from production builds. The accounts are `demo.<key>@demo.invalid`, all with password `Demo@2026`:

| Button | Login | Use it to test |
| --- | --- | --- |
| พนักงาน | `demo.employee@demo.invalid` | self-service, leave, OT, profile requests |
| ฝ่ายบุคคล | `demo.hr@demo.invalid` | employees, approvals, payroll, password reset |
| ฝ่ายขาย | `demo.sales@demo.invalid` | deals, price requests, quotations |
| ผู้จัดการฝ่ายขาย | `demo.salesmanager@demo.invalid` | team deals, commission |
| ฝ่ายนำเข้า | `demo.import@demo.invalid` | import requests, catalogue and price import |
| ฝ่ายบัญชี | `demo.account@demo.invalid` | deposits, invoices, payments |
| คลังสินค้า | `demo.warehouse@demo.invalid` | delivery |
| QC | `demo.qc@demo.invalid` | quality checks |
| CEO | `demo.ceo@demo.invalid` | price approval, pricing settings, dashboards |

To switch persona, log out and click another button. Restarting the backend also logs you out.

**5. Load the price catalogue.** This is the one thing the demo seed does **not** include.
The nine factories exist (Panaria, LEA, Padana, CDE, CITY, REFIN, Equipe, Vives, Bode), but
`price_catalog.product_prices` is empty, so the product picker in a quotation shows no matches
and no error. The real supplier price lists are not in this repository. Load them one of two ways:

- **Upload through the app.** Log in as **ฝ่ายนำเข้า** or **CEO**, open **นำเข้าราคา**
  (`/price-import`), and upload each factory's Excel price list. Import rules per factory are in
  [docs/Catalouge/catalog-import-logic.md](docs/Catalouge/catalog-import-logic.md). Ask a teammate
  for the files.
- **Copy it from another database.** If a teammate has a populated database, dump only the
  catalogue schema and load it into yours. `size_norm` and `price_per_sqm` are generated columns,
  so use `pg_dump --data-only --column-inserts` and run it after step 2 has created the schema:

  ```bash
  pg_dump --data-only --column-inserts --schema=price_catalog -h <source-host> -U <user> <source-db> \
    | psql -h localhost -U postgres hris
  ```

Only rows whose price list version is `ACTIVE` show up in the sales picker.

**Reset to a fresh demo.** Stop the backend, then recreate the database and start again:

```bash
docker compose down -v        # drops the pgdata volume
docker compose up -d db
```

### Pointing the frontend somewhere else

| Variable | Effect |
| --- | --- |
| `VITE_USE_MOCKS=true` | use the in-browser mock API |
| `VITE_API_BASE_URL` | API origin the browser calls (`.env.development` sets `http://localhost:8080`) |
| `VITE_API_PROXY_TARGET` | backend the Vite `/api` proxy forwards to, when `VITE_API_BASE_URL` is empty |
| `VITE_SELF_SERVICE_ONLY=true` | show the locked-down production surface (self-service only) to non-HR, non-CEO roles |
| `VITE_ENABLE_SALES=false` | hide the sales/CRM stack |

If you run the backend on another port or host, add the frontend origin to
`APP_CORS_ALLOWED_ORIGINS`. The default allows `localhost` and `127.0.0.1` on ports 5173 and 5174.

## Tests And Builds

Backend (from `backend/`):

```bash
./mvnw test                       # unit, controller and, when a database is reachable, integration tests
./mvnw -DskipTests package        # build the jar
```

The integration suite resolves its database in this order: `TEST_DB_URL`, `TEST_DB_USERNAME` and
`TEST_DB_PASSWORD` if set; otherwise a Testcontainers `postgres:16-alpine` when Docker is running;
otherwise those tests skip. With Docker running, a plain `./mvnw test` exercises Flyway and the
repositories.

Frontend (from `frontend/`):

```bash
npm run lint        # ESLint, including jsx-a11y
npm test            # Vitest; run it from frontend/ or source-guard tests fail
npm run build
```

Real-backend end-to-end tests (Playwright, no mocks) are described in
[frontend/e2e-real/README.md](frontend/e2e-real/README.md). Start the database and backend as above,
then run `npm run test:e2e` from `frontend/`.

CI runs backend tests, frontend lint/test/build, and the real-backend e2e suite on every pull
request (`.github/workflows/`).

## Vercel Frontend Deployment

This repo includes a root-level `vercel.json` for Vercel projects whose Root Directory is the repository root. It installs and builds `frontend/`, publishes `frontend/dist`, proxies `/api/*` to the Render backend, and serves `index.html` for React SPA routes.

Builds are locked to the self-service surface by default (`VITE_SELF_SERVICE_ONLY` is on unless set to `false`). The `develop` branch build sets it to `false`, so the develop preview shows the full app; every other branch, including `main`, ships the locked surface.

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
