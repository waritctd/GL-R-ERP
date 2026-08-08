# Real-backend e2e (`npm run test:e2e`)

Browser + API end-to-end tests against the **real stack**: Vite dev server → Spring Boot →
Postgres. No mocks anywhere in the path.

**This is now the repository's only e2e suite.** The mock-frontend suite that lived in
`frontend/e2e/` (`VITE_USE_MOCKS=true`, its own `playwright.config.js` and `e2e-ci.yml`) was
removed on 2026-08-08, owner ruling: one e2e job per PR instead of two.

### What the removal cost — read this before assuming parity

The suites overlapped on **8 of the mock suite's 73 tests** (`auth.spec.js`, `rbac.spec.js`) —
both covered better here, because this suite asks the real service. The other **65 tests were not
duplicated by anything in this directory**, and that coverage is currently gone:

| Removed spec | What it covered, and nothing here replaces |
|---|---|
| `phase4a-acceptance` (18) | DataTable callers across desktop/tablet/mobile viewports |
| `form-field-alignment` (9) | field/label alignment across every form surface |
| `hr.spec` (7) | HR screens driven through the UI |
| `implicit-submission` (5) | Enter-key submit behaviour |
| `safety-foundations` (5) | assorted UI safety invariants |
| `pcr-chain` (5) | pricing-request workflow through the UI |
| `deposit-fulfilment-close` (4) | deposit → fulfilment → close journey |
| `table-alignment` (3) | table column alignment |
| `commission` (2), `deal-creation` (2) | sales workflows through the UI |
| `shared-shell` (2) | responsive app shell at mobile/tablet |
| `panel-header-spacing` (2) | panel header spacing |
| `safe-form-submitter-guard` (1) | the `SafeForm` submitter guard |

Most of that is **visual/layout and form-behaviour** coverage this suite does not attempt —
`route-coverage.spec.js` asserts pages *load* without crashing or 5xx-ing, never that they look
right. The sales/HR journey specs are unreplaced too: exactly one business workflow (overtime) is
driven end to end here.

Recovering any of them: `git show e08a5d03^:frontend/e2e/<file>`. Porting a *journey* spec is
mostly mechanical (real credential login instead of quick-login); porting a *visual* spec is not —
those lean on the mock's fully-populated fixtures, which the demo seed does not match.

## Why this suite exists

`CLAUDE.md` is explicit that `mockApi.js`'s authorization is *not* authoritative and is known to
diverge from the Java services. Issue #199 is the canonical case: the mock let HR approve
overtime that the real `OvertimeService` refuses with a 403, and an agent reported "the backend
would accept an HR approval" after reading the mock's authz as the backend's.

The mock suite says so itself — `e2e/rbac.spec.js`'s header disclaims proving anything about the
server, because all it can reach is `react-router-dom`'s client-side guard. That guard is a UX
affordance, not a security control: it decides what a user is *shown*, never what the API will
*honour*.

So a green mock run is evidence about plumbing, and never evidence about permissions. This suite
is the other half. Everything it asserts came from the real service.

| | `e2e/` (mock) | `e2e-real/` (this suite) |
|---|---|---|
| API | `mockApi.js`, in-process | real Spring controllers/services |
| Data | seeded JS objects | real Postgres rows (Flyway `db/migration-demo`) |
| Login | password-less quick-login buttons | real email + password, BCrypt-checked |
| Session | a JS module variable | `hr.spring_session` in Postgres, via cookie |
| Authorization | approximate, **known to diverge** | authoritative |
| Needs a database | no | **yes** |

## What it covers

Two of these files go deep on a few things; two go wide over everything.

**Depth — hand-verified behaviour:**

- **`auth.spec.js`** — real credential login for all six seeded personas, the backend deriving
  each role from its division, session survival across a hard reload, server-side session
  invalidation on logout, and 401 for anonymous callers. Also pins the mock/real divergence:
  `POST /api/auth/login {role}` is refused with 403 by the real `AuthService`.
- **`api-authz.spec.js`** — the authorization matrix for the gates that matter, hit directly
  against the service. Written wrong-way-round: the assertions that count are that a role
  **cannot** reach what it shouldn't. Every row cites the Java class that decides it.
- **`smoke.spec.js`** — real Postgres rows rendering in the DOM, and the route guard and the
  service independently refusing the same thing.

**Breadth — the whole declared surface, derived from the code:**

- **`api-surface.spec.js`** — walks `API_ROUTES` (the table `hrApi.js` itself calls, ~219
  endpoints) and asserts, of every one of them: an anonymous `GET` **and** an anonymous `POST`
  are refused with 401, and no endpoint answers a server error to any authenticated role.
- **`route-coverage.spec.js`** — opens every route declared in `App.jsx` as every seeded role,
  and asserts the page either loads without hitting the React error boundary or shows the
  access-denied view, with no 5xx from anything it fetches. The permission oracle is the real
  `user` object the backend issued for that session, not a hand-maintained stand-in.

**Writes — the only place this suite mutates anything:**

- **`write-overtime.spec.js`** — the overtime approval chain driven end to end:
  `SUBMITTED →(division manager)→ MANAGER_APPROVED →(CEO)→ APPROVED`, with each refusal asserted
  separately. This is where **issue #199** is pinned: `mockApi.js` let HR approve overtime, and
  the real `OvertimeService` answers 403 because HR is not in the chain at all. It also pins two
  rules that are easy to get backwards — the **CEO cannot skip the manager stage**, and a refused
  approval leaves the request's status untouched (a 403 that still mutated would look safe and
  not be). Plus the CSRF control, which is only reachable on an authenticated write.
- **`write-authz.spec.js`** — every resource-scoped write endpoint (98, across
  POST/PUT/PATCH/DELETE) × every role = 588 requests, asserting that **no role ever gets a 2xx
  from a write against a resource that does not exist**. A 2xx there would mean an endpoint
  mutating or creating without an existence check.
- **`write-overtime-holiday.spec.js`** — `day_type`/`pay_rate_multiplier` are derived exclusively
  from `hr.holiday` (`OvertimeService#deriveDayType`), never from the client's
  `SubmitOvertimeRequest.dayType` claim, which is validated but never trusted for pay
  (`#resolveDayTypeSubmitNote`). Creates and deletes real holiday rows through the real
  `HolidayController` CRUD (HR-gated) to drive every row of that decision table: a HOLIDAY claim
  the calendar can actively disprove is refused with **no row created**; one it corroborates is
  accepted at 3.00; a WORKDAY claim on a genuine holiday cannot suppress the real rate; an
  ordinary day with no claim stores 1.50 unflagged; and a holiday HR adds after submit is still
  picked up — and frozen — at manager approval. `hr.holiday` ships **empty**, so the
  claim-contradicted case is only reachable once the calendar is deliberately loaded for that
  year first (a sentinel row); every such case reads its setup back through a separate GET before
  relying on it, rather than trusting its own create response.

### How the write specs stay safe on a shared database

There is no per-test database reset, and the specs are built so none is needed:

- each test creates its **own** record and touches only that id — never a global count, never a
  row it did not create;
- each test **cancels what it created**, including from `APPROVED` (a division manager may cancel
  at any live status, which also unwinds the attendance minutes the approval credited);
- the work date is always **today** in `Asia/Bangkok`, because approval is gated on the payroll
  month still being open. A hardcoded date passes until that month is processed and then fails
  for a reason unrelated to the code under test;
- the breadth sweep only touches **resource-scoped** writes against a placeholder id. Collection
  writes (`POST /api/employees`, `POST /api/overtime`) are excluded precisely because those *can*
  create something — sweeping them across six roles would mean writing rows into a shared
  database to discover who is allowed to write rows. The measured result of the sweep is
  **0 × 2xx**, which is both the assertion and the evidence that it mutated nothing.

Verified by running the write specs twice back to back against an already-dirtied database: both
runs pass identically.

`scripts/reset-e2e-db.mjs` exists for when you want a pristine seed anyway — an interrupted run,
an experiment that left rows in a strange state. It drops and recreates the database so Flyway
re-seeds on the next backend start, and it refuses to run while the backend still holds a
connection. CI never needs it: each job gets a brand-new Postgres service container.

### The drift guards are the point

A coverage suite that stops covering new code is worse than none, because the green check still
reads as "covered". So the two breadth files derive their subject from the source and re-check it
every run:

- `api-surface.spec.js` walks `API_ROUTES` at runtime — a new endpoint is in scope the moment it
  exists. It also asserts the walk still resolves >150 concrete `/api` paths, so a restructure
  that broke the walker fails loudly instead of silently sweeping nothing.
- `route-coverage.spec.js` parses `App.jsx` for its `<Route path=…>` set and fails if any route
  is neither swept nor listed in `EXCLUDED_ROUTE_PATTERNS` with a reason — and fails the other
  way too, if a route it sweeps no longer exists.

The first two tests in `auth.spec.js` serve the same honesty goal: they fail if the frontend is
accidentally serving mocks, so that misconfiguration can't silently downgrade the whole suite
into a second mock run.

### Backend behaviour this suite surfaced

Things the sweeps found on the real backend. None is fixed here — this branch is test-only, and
changing a controller's response status is an API-contract change that belongs in its own branch
(`CLAUDE.md`, "as a side effect"). All are recorded so they stay visible.

- **`PriceImportController` has no missing-resource handling, on any verb.** An unknown id yields
  500 rather than 404 for `GET /api/price-import/profile/{factoryId}`, `POST
  /api/price-import/validate/{id}` and `POST /api/price-import/commit/{id}` alike — a real id
  returns 200, so it is specifically the missing-row path. Recorded as **exact** expectations
  (`KNOWN_SERVER_ERRORS` in `api-surface.spec.js` for the read, `write-authz.spec.js` for the
  writes), not skips: a new server error fails the sweep, and so does fixing one of these without
  deleting its entry. Only `import` and `ceo` reach the defect — every other role is refused 403
  before the lookup, which means it is invisible to five of the six roles.
- **CSRF is enforced by the app, not by Spring.** `SecurityConfig` calls
  `.csrf(AbstractHttpConfigurer::disable)`, but `CsrfCookieFilter` (`@Order(0)`) implements the
  OWASP double-submit pattern by hand — an authenticated write without an `X-XSRF-TOKEN` header
  matching the `XSRF-TOKEN` cookie is 403. The read-only specs never met it because Spring
  Security's chain runs at order −100 and rejects an *anonymous* write with 401 first, so CSRF is
  only reachable once authenticated. `helpers/api.js` handles the header; `write-overtime.spec.js`
  asserts the control is live rather than merely configured.
- **A wrong HTTP verb returns 500, not 405.** `GET` on a POST-only endpoint produces
  `{"message":"เกิดข้อผิดพลาดภายในระบบ","status":500}` — an unhandled
  `HttpRequestMethodNotSupportedException` reaching the generic error handler. This is why
  `surface.js` reads each endpoint's real verb out of `hrApi.js` instead of GETting everything:
  without that, ~130 endpoints would report a "server error" that is purely an artefact of asking
  the wrong question, and would bury the one real finding above.

### Not covered — read this before citing a green run

- **Three of the eight roles have no seeded persona.** `V21__demo_seed_accounts.sql` creates no
  employee in the AC-ฝ่ายบัญชี, WH-คลังสินค้า or QC&ISO divisions, so `account`, `warehouse` and
  `qc` are **untested here**. `account` in particular is a real gap: it is in
  `TicketAccessPolicy.VIEWER_ROLES` and is the only role permitted to confirm payments.
- **Overtime is the only business workflow driven end to end.** Every other multi-step flow —
  leave, a pricing request, a deposit confirmation, closing a deal — is still exercised only by
  the mock suite, against `mockApi.js`.
- **Leave was attempted and deliberately left out**, because the demo seed cannot produce a
  reviewable leave request. Measured against `demo.sales`: `LEAVE_WITHOUT_PAY`, `MATERNITY` and
  `MILITARY` all land straight in `APPROVED` with no review stage; `ORDINATION` lands in
  `AUTO_REJECTED` (`HIRE_DATE_MISSING_MIN_SERVICE` — the demo employees carry no hire date);
  `SICK` requires an attachment; `PERSONAL` and `VACATION` have zero quota. So there is nothing
  in a `SUBMITTED` state for HR to review, and the leave review path cannot be reached at all
  from this seed.

  That is worth knowing on its own: it means **HR's leave-review authorization is untested**,
  and it is the interesting counterpart to #199 — `LeaveService.REVIEW_ALL_ROLES` is `{hr}`, so
  HR *can* review leave while being refused overtime. Testing that asymmetry needs seed data the
  demo migration does not currently provide.
- **"Every route loads" is not "every route works".** `route-coverage.spec.js` asserts each page
  renders without hitting the error boundary and without a 5xx behind it. It does not assert the
  page shows the *right* thing; `smoke.spec.js` does that for two screens only.
- **No payroll, tax, commission or pricing math.** Out of scope by `CLAUDE.md`'s standing rule.
- **The login rate limiter is deliberately not exercised.** Tripping it locks the client IP for
  `app.login-rate-limit.lockout-seconds` (900s by default) and would take the rest of the suite
  down with it. See the troubleshooting note below.

## Troubleshooting: `429` / "too many login attempts"

`LoginRateLimitFilter` counts **both 401 and 403** responses to `POST /api/auth/login` as auth
failures, per account (5) and per client IP (20) within a 900s window. Two things follow:

- **A misconfigured CORS origin does not merely fail — it locks the account out.** Every rejected
  login is a 403 and therefore a counted failure, so a run against a backend that doesn't allow
  this suite's origin will burn through the account limit and leave you locked out after you fix
  the origin. `global-setup.js` avoids causing this: it probes CORS with an `OPTIONS` preflight,
  which the filter ignores (`shouldNotFilter` matches POST only).
- **Recovering is instant.** `LoginAttemptTracker` holds its counters in a plain in-memory map, so
  restarting the backend clears every lockout. Otherwise wait out the window — or just log in
  successfully once, which resets both counters.

The suite makes two deliberately-failing login attempts (`auth.spec.js`'s wrong-password and
unknown-email tests). That is well inside both limits, and the successful logins around them keep
resetting the IP counter.

## Running it

Three moving parts. The suite starts the frontend for you; you bring the other two.

```bash
# 1. Postgres on :5432 with a 'hris' database
docker compose up -d db
#    ...or a local cluster:
#    pg_ctlcluster 16 main start && createdb hris

# 2. Backend on :8080 — build once, then run
cd backend && ./mvnw -DskipTests package
cd ../frontend && node scripts/start-e2e-backend.mjs

# 3. The suite, in another terminal
cd frontend && npm run test:e2e
```

`scripts/start-e2e-backend.mjs` sets the environment the suite needs and fails with an
actionable message if Postgres or the jar is missing. Two of those settings are not optional:

- **`SPRING_PROFILES_ACTIVE=demo`** — this is what applies `db/migration-demo` and therefore what
  creates the logins. A plain `./mvnw spring-boot:run` gives you a perfectly healthy backend with
  none of these accounts in it.
- **`APP_CORS_ALLOWED_ORIGINS`** must include this suite's origin. `application.yml` defaults to
  ports 5173/5174 and the suite runs on **5251**; Spring's CORS filter rejects an unlisted origin
  with a bare `403 Invalid CORS request`, which from the login form is indistinguishable from a
  wrong password. `e2e-real/global-setup.js` detects exactly this and says so.

### Accounts

Every persona shares the password **`Demo@2026`** (V21 seeds one BCrypt hash for all of them).

| role | email |
|---|---|
| `employee` | `demo.employee@demo.invalid` |
| `hr` | `demo.hr@demo.invalid` |
| `sales` | `demo.sales@demo.invalid` |
| `sales_manager` | `demo.salesmanager@demo.invalid` |
| `import` | `demo.import@demo.invalid` |
| `ceo` | `demo.ceo@demo.invalid` |

None of these roles is configured. `DivisionAccessPolicy.roleFor` derives each one from the
employee's division at login time, so `helpers/api.js` refuses to hand back a session whose
resolved role differs from the one asked for — seed drift fails loudly instead of quietly
re-pointing the authz assertions at the wrong persona.

### Ports

`5251` (frontend). Deliberately distinct from every other frontend in the repo — `5174` is
`npm run dev`, `5200` is the `frontend-mock` launch config, `5250` is the mock e2e suite.
`reuseExistingServer` is `false` so a stray mock server can never be mistaken for this one.

### Environment overrides

| variable | default | purpose |
|---|---|---|
| `E2E_BACKEND_URL` | `http://127.0.0.1:8080` | where the backend is |
| `E2E_REAL_FRONTEND_PORT` | `5251` | dev-server port for this suite |
| `E2E_CHROMIUM_EXECUTABLE` | *(unset)* | absolute path to a Chromium binary, for sandboxes/CI images that preinstall one at a different Playwright browser revision |

## When a test here goes red

The answer is in the Java class the assertion cites — not in `mockApi.js`, and not in
`ROLE_PERMISSIONS`. A red `api-authz.spec.js` means a real gate moved.
