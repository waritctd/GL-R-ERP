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

- **`auth.spec.js`** — real credential login for all nine seeded personas, the backend deriving
  each role from its division, session survival across a hard reload, server-side session
  invalidation on logout, and 401 for anonymous callers. Also pins the mock/real divergence:
  `POST /api/auth/login {role}` is refused with 403 by the real `AuthService`.
- **`api-authz.spec.js`** — the authorization matrix for the gates that matter, hit directly
  against the service. Written wrong-way-round: the assertions that count are that a role
  **cannot** reach what it shouldn't. Every row cites the Java class that decides it.
- **`smoke.spec.js`** — real Postgres rows rendering in the DOM, and the route guard and the
  service independently refusing the same thing.

**Breadth — the whole declared surface, derived from the code:**

- **`api-surface.spec.js`** — walks the **backend's own** surface (all **276** operations, from
  `docs/api/api-surface.json`) and asserts, of every one of them: an anonymous `GET`, an anonymous
  `POST`, **and an anonymous request sent with the endpoint's own declared verb** are all refused
  with 401, and no endpoint answers a server error to any authenticated role.

  It used to walk `API_ROUTES` — the table `hrApi.js` calls — on the reasoning that deriving the
  surface from code beats a hand-listed set. The reasoning was right and the source was wrong:
  `API_ROUTES` is a *consumer* of the API, so an endpoint no route called was invisible to it.
  A 2026-08-14 audit measured that at **49 endpoints missing entirely**, 24 more swept under a
  verb they do not implement, and ~22 deal actions collapsed into `/api/tickets/999999/999999` —
  a path matching no controller, which nonetheless answered 401 anonymously and 404 authenticated
  and so passed every assertion while testing nothing.
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
  POST/PUT/PATCH/DELETE) × every role, asserting that **no role ever gets a 2xx from a write
  against a resource that does not exist**. A 2xx there would mean an endpoint mutating or
  creating without an existence check. The role list is `REAL_ROLES`, so the sweep widened from
  588 requests to 882 when V139 took the seeded personas from six to nine.
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
  create something — sweeping them across every role would mean writing rows into a shared
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

- `api-surface.spec.js` walks `docs/api/api-surface.json` — generated by the backend's
  `ApiSurfaceContractTest` from springdoc's `/v3/api-docs`, i.e. Spring's own handler mappings.
  A new endpoint is in scope the moment it exists, and **that test fails if the committed digest
  drifts from the running application**, so the file is never a stale transcription. The sweep
  also asserts it still resolves >250 concrete `/api` paths with no unsubstituted `{param}`, so
  a broken derivation fails loudly instead of silently sweeping nothing.
- `frontend/src/api/serverContract.test.js` (vitest, no backend needed) closes the loop from the
  other side, in three directions. Every endpoint `hrApi.js` calls must exist on the backend with
  that verb (hard fail, no allowlist). Every backend endpoint must have an `hrApi` caller or sit in
  `SERVER_ONLY` **with a written reason**. And every endpoint must be reachable **from a screen** —
  not merely from a declared `hrApi` method — or sit in `UNREACHABLE_FROM_UI`. That third direction
  is what "reachable" actually means: **218 of 276** endpoints pass it, against 251 that a
  declaration-only comparison called reached. It also fails if any module outside `src/api/`
  hand-builds an `/api/` path, since such a call escapes every contract guard.

  All of this is a different axis from `contract.test.js`, which compares two *frontend* modules
  (`mockApi` vs `hrApi`) and can say nothing about the Java service.
- `route-coverage.spec.js` parses `App.jsx` for its `<Route path=…>` set and fails if any route
  is neither swept nor listed in `EXCLUDED_ROUTE_PATTERNS` with a reason — and fails the other
  way too, if a route it sweeps no longer exists.

The first two tests in `auth.spec.js` serve the same honesty goal: they fail if the frontend is
accidentally serving mocks, so that misconfiguration can't silently downgrade the whole suite
into a second mock run.

### Backend behaviour this suite surfaced

Things the sweeps found on the real backend. Two were response-status defects and are now fixed
(deliberate, stated API-contract changes rather than side effects of test work); the rest are
behaviour worth knowing. All stay recorded so they remain visible.

- **`PriceImportController` had no missing-resource handling, on any verb — now fixed.** An
  unknown id yielded 500 rather than 404 for `GET /api/price-import/profile/{factoryId}`, `POST
  /api/price-import/validate/{id}` and `POST /api/price-import/commit/{id}` alike, while a real id
  returned 200 — specifically the missing-row path. Both `KNOWN_SERVER_ERRORS` lists are now
  **empty**, which is the assertion rather than the absence of one: `PriceImportService`'s
  `#getRawProfile` and `#requireDraft` catch `EmptyResultDataAccessException` and raise 404, the
  idiom `#loadProfile` in the same class already used. Only `import` and `ceo` ever reached the
  defect — every other role is refused 403 before the lookup, which is why it survived so long.
- **CSRF is enforced by the app, not by Spring.** `SecurityConfig` calls
  `.csrf(AbstractHttpConfigurer::disable)`, but `CsrfCookieFilter` (`@Order(0)`) implements the
  OWASP double-submit pattern by hand — an authenticated write without an `X-XSRF-TOKEN` header
  matching the `XSRF-TOKEN` cookie is 403. The read-only specs never met it because Spring
  Security's chain runs at order −100 and rejects an *anonymous* write with 401 first, so CSRF is
  only reachable once authenticated. `helpers/api.js` handles the header; `write-overtime.spec.js`
  asserts the control is live rather than merely configured.
- **A wrong HTTP verb returned 500, not 405 — now fixed.** `GET` on a POST-only endpoint produced
  `{"message":"เกิดข้อผิดพลาดภายในระบบ","status":500}`, an unhandled
  `HttpRequestMethodNotSupportedException` reaching the generic handler.
  `ApiExceptionHandler#handleMethodNotSupported` now answers 405 with an `Allow` header
  (RFC 9110 §15.5.6). `surface.js` still reads each endpoint's real verb out of `hrApi.js`, and
  should keep doing so — asking the right question is what keeps the sweep's output meaningful,
  and ~130 endpoints reporting a well-formed 405 would be just as much noise as 500s were.

### Not covered — read this before citing a green run

- **Overtime and the sales pipeline are the two business workflows driven end to end — by two
  DIFFERENT mechanisms, and only one of them runs in `npm run test:e2e`.** Overtime's coverage
  (`write-overtime.spec.js`) runs locally, against the local stack this file's "Running it" section
  starts, on every invocation. The sales pipeline's coverage (`uat-sales-*.spec.js` — the
  pricing-request chain from lead to `ORDER_RECEIVED`, the stage/note/readiness/role refusal
  matrix, and the four real routes named in `DealStage.java`'s own Javadoc: Case A designer-led,
  Case B owner-direct, Case C buyer-direct/starts-at-S8, Case D an all-from-stock deal that skips
  PROCUREMENT) runs ONLY against a **deployed UAT environment**, via `npm run test:e2e:uat` — see
  "Running against deployed UAT" below. `playwright.real.config.js` excludes every `uat-*.spec.js`
  file from a local run entirely (`testIgnore`), so a green local `npm run test:e2e` asserts
  nothing about the sales pipeline at all — it is neither run nor skipped-and-reported, just absent
  from that invocation's test count. Every OTHER multi-step flow — leave, a deposit confirmation —
  still has no end-to-end coverage anywhere since the mock suite was removed.
- **Leave's successful approve transition is still not driven, and the reason is the service, not
  the seed.** This entry used to blame the demo seed alone. Two things were in the way and only one
  of them was seed-shaped:

  1. *The seed, now fixed.* Every demo employee had `hire_date IS NULL`, and
     `LeaveService#employeeAnnualQuota` returns ZERO when `findHireDate` is empty, so `VACATION`
     (6.00) and `PERSONAL` (3.00) prorated to nothing and failed closed on quota, while
     `#autoRejectNote` failed `ORDINATION` with `HIRE_DATE_MISSING_MIN_SERVICE`.
     `V139__demo_missing_role_personas_and_hire_dates.sql` backfills a hire date three years back,
     past `FULL_SERVICE_MONTHS`, and `write-leave-review.spec.js` guards that.
  2. *The service, which no seed can work around.* **`LeaveService#submit` never produces a
     `SUBMITTED` request.** It reads
     `LeaveStatus status = systemNote == null ? APPROVED : AUTO_REJECTED` — a submission is decided
     on the spot, so there is no pending state to review. Fixing the hire date moved `VACATION`
     from `AUTO_REJECTED` to `APPROVED`; it did not, and could not, create anything reviewable.

  The only `SUBMITTED` row in the database is the single one `V21` seeds for `DEMO-EMP01`, and it
  is consumable — approving it once leaves every later run with nothing. So `write-leave-review.spec.js`
  asserts the review gate in the two directions that mutate nothing: the **refusals** (`ceo`,
  `import`, `sales` are each 403 on approve *and* reject, with the row re-read afterwards to prove
  it was untouched), and HR's **capability** via the `canReview` flag — which
  `#withCanReviewFlag`'s Javadoc states is computed from the same decision `#approve`/`#reject`
  gate on, not a role check. That pins the counterpart to #199: HR reviews leave while
  `OvertimeService` refuses HR outright.

  **What is still missing is the successful `SUBMITTED → APPROVED` transition.** Driving it needs
  either a reviewable row the API cannot create or a per-test database reset this suite does not
  have.
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

## Running against deployed UAT

A second mode of this SAME suite (same `playwright.real.config.js`, same `helpers/`) drives the
sales pipeline end to end against a deployed UAT environment instead of the local stack above —
real Vercel frontend, real Render backend, real Postgres, no mocks anywhere, no local server
started. This is what actually proves the pricing-request chain, the refusal matrix, and the four
sales-pipeline routes (Cases A-D) named in this file's "Not covered" section above.

```bash
E2E_BASE_URL=https://<uat-frontend-host> \
E2E_UAT_PASSWORD=<ask whoever runs UAT> \
npm run test:e2e:uat
```

Two environment variables, both required:

| variable | purpose |
|---|---|
| `E2E_BASE_URL` | the deployed frontend's origin — must be `https://` and non-loopback, or `playwright.real.config.js` refuses to start (its anti-mock protection depends on the target being a real `vite build` output, which only a genuine deployment is). `/api/*` is rewritten to the backend from this same origin, so the browser and this suite's direct-to-Spring API calls share one session cookie jar. |
| `E2E_UAT_PASSWORD` | the shared password on the `E2E-*` personas `db/migration-uat/V912__uat_e2e_test_personas.sql` seeds. **Not in this repository, and never will be** — see `helpers/uat-accounts.js`'s `uatPassword()` for the full reasoning. Ask whoever runs UAT; do not try any password published for the old, now-gone `@uat.glr` personas. |

`npm run test:e2e:uat` runs `playwright test --config playwright.real.config.js --grep @uat-sales`.
Every spec meant for UAT tags its `describe` block `@uat-sales`, and `playwright.real.config.js`
separately `testIgnore`s every `uat-*.spec.js` file in LOCAL mode — two independent mechanisms, so
neither a `--grep` typo nor a missing tag can point a local run at a shared deployment, or a remote
run at specs that assume a local one (`route-coverage.spec.js`, `api-surface.spec.js` and
`write-authz.spec.js` are excluded the other way for exactly that reason — see
`playwright.real.config.js`'s own comment).

### The never-do list

This is a SHARED, long-lived database that human testers are actively using, not a disposable
fixture a run can leave dirty:

- **Own deal per test, registered for teardown.** Every UAT spec creates its own deal(s), tags the
  title with the run id `global-setup.js` stamps (`E2E-<timestamp>-<random>`), and cancels every
  deal it created in `afterAll` — best-effort and outside any `expect()`, so a teardown failure can
  never turn a passing assertion red or mask a real one.
- **Never touch `UAT-TKT-01`..`14` or `UAT-GOLD-01`.** Those are human testers' own fixtures; a
  spec that advanced one would silently rewrite somebody's acceptance script out from under them.
- **No failing logins.** `LoginRateLimitFilter` counts both 401 and 403 against `POST
  /api/auth/login` — 5 failures per account, 20 per client IP, inside a 900s window — and a
  lockout on the shared Render service cannot be cleared by restarting anything you control.
  `global-setup.js` captures one session per persona up front and aborts on the FIRST login
  failure rather than spending the budget trying the rest.
- **Every test is `@uat-sales` tagged.** It is the only thing `--grep` can select against, and (see
  above) the local run's `testIgnore` is the second, independent guard against a stray `--grep`
  sending the wrong specs at the wrong target.

### Personas

The five roles this suite needs (`sales`, `sales_manager`, `import`, `ceo`, `account` —
`UAT_SALES_ROLES` in `helpers/uat-accounts.js`) come from
`db/migration-uat/V912__uat_e2e_test_personas.sql`, which lives on the `uat` branch **only** — a
branch cut from `main` cannot see it, diff against it, or verify it. `helpers/uat-accounts.js` is
therefore necessarily a HAND-COPY of that migration's persona table, kept next to a long comment on
exactly how it drifts (an employee's division moves, an email is renamed) and how each failure mode
surfaces differently. **No credential is committed anywhere in this repository**: V912 seeds every
persona with `password_hash NULL`, and whoever runs UAT sets the shared password once by hand —
`helpers/uat-accounts.js`'s own header has the full reasoning, including why the old `@uat.glr`
password (published in plaintext in earlier commits) is not a fallback: those accounts no longer
exist in the rebuilt UAT database at all, so every login attempt with it is a guaranteed 401 that
only spends the rate-limit budget.

## When a test here goes red

The answer is in the Java class the assertion cites — not in `mockApi.js`, and not in
`ROLE_PERMISSIONS`. A red `api-authz.spec.js` means a real gate moved.
