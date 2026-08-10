# GL&R ERP — Pre-UAT Gate Checklist

| | |
|---|---|
| **Document** | 13 — Pre-UAT Gate Checklist |
| **Version** | 1.0 · 10 August 2026 |
| **Audience** | Owner, QA, whoever signs off the go/no-go |
| **Relationship to doc 11** | [`11_UAT_Test_Cases.md`](11_UAT_Test_Cases.md) is what **testers execute during** UAT. This document is what **must be true before** UAT starts. Doc 11 is the exam; this is the eligibility check. |
| **Baseline** | Measured on `main` @ `2ecca72`, 10 August 2026. Re-measure before each UAT round — the status column ages. |

---

## Table of Contents

1. [How to use this](#1-how-to-use-this)
2. [The gate](#2-the-gate)
3. [Baseline measured today](#3-baseline-measured-today)
4. [The checks](#4-the-checks)
5. [What has no coverage — read before signing off](#5-what-has-no-coverage--read-before-signing-off)
6. [Open items before UAT](#6-open-items-before-uat)
7. [Sign-off](#7-sign-off)

---

## 1. How to use this

Every check below carries three things: **what it means**, **how to run it in this repo**, and
**where it stands today**. The status column is evidence, not aspiration — where nothing covers a
check, it says so rather than leaving it blank.

Status legend:

| | Meaning |
|---|---|
| ✅ | Automated, runs today, green |
| ⚠️ | Partial — real coverage exists, named gaps remain |
| ❌ | No automated coverage — nothing catches a regression here |
| 🔲 | Inherently manual — a person has to look |

Two standing rules from [`CLAUDE.md`](../CLAUDE.md) govern how a check may be *reported*, and they
matter more here than anywhere else in the repo, because a UAT sign-off is exactly where an
overstated claim does damage:

- **Green under `VITE_USE_MOCKS=true` is evidence about plumbing, never about behaviour or
  permissions.** `mockApi.js` authorization is known to diverge from the Java services.
- **Any authorization claim needs a real-DB test through the real Java service.** If it ran on
  mocks, the permission aspect is **unverified** — say so.

## 2. The gate

The minimum ordered sequence. Each phase must pass before the next is worth running.

```
Smoke → Integration → E2E → Regression → Role/Permission → UI/Responsive → Bug cleanup → UAT
```

The ordering is not decorative: a failed smoke makes an integration run meaningless, and a
role/permission failure found after UI polish means the polish was applied to a screen that should
not have rendered. The 26 checks in §4 are grouped into these phases.

## 3. Baseline measured today

Run on `main` @ `2ecca72`, 10 August 2026, in a container with no Docker and no Postgres.

| Command | Result |
|---|---|
| `cd frontend && npm run lint` | ✅ clean, exit 0 |
| `cd frontend && npm test` | ✅ **134 files / 1581 tests passed**, 102s |
| `cd frontend && npm run build` | ✅ built in 629ms |
| `cd frontend && npm run test:e2e` | ⏸️ **not run** — needs Postgres + a running backend |
| `cd backend && ./mvnw -B clean verify` | ⏸️ **not run** — no Docker and no `TEST_DB_URL`, so Testcontainers cannot start |

Both backend suites are unrun *for lack of a database in this environment*, not because they fail.
They must be green before UAT; see §6.

**Test inventory** (what exists, whether or not it ran today):

| Layer | Count |
|---|---|
| Frontend unit/component (vitest) | 134 files, 1581 tests |
| Backend test classes | 226 |
| …of which real-Postgres integration tests | 128 |
| Real-stack e2e specs (`frontend/e2e-real/`) | 9 |

**Database state**, read live from `hr.flyway_schema_history` on 10 August 2026:

| Environment | Supabase project | Core schema | Applied | Failed | Last applied |
|---|---|---|---|---|---|
| Repo `main` | — | **V138** | — | — | — |
| **Production** | `GL&R` (`tdyzcqzxmhtxpbouewud`) | **V138** | 136 | **0** | 2026-08-09 14:16 |
| **UAT** | `GL&R's UAT` (`wuypxdznuhhluwzncafh`) | **V138** | 145 | **0** | 2026-08-10 01:27 |

Both environments are **current with `main`** and carry no failed migrations. This supersedes
issue **#439** ("prod is 4 migrations behind main"), which was accurate on 2 August and is now
stale — see §6.

Four notes on that table, each checked row by row against the repo's 133 core migrations rather
than inferred from the max version:

- **There is exactly one `flyway_schema_history` table, in the `hr` schema.** #439's closing
  suggestion to "check the remaining four schemas' history tables" has no work in it — the other
  four Flyway schemas share this history table.
- **UAT has all 133 core migrations, and no demo seed.** Its extra 12 rows are a **`V900`–`V911`
  UAT-seed series that does not exist in this repository** (`uat reference and employees`, `uat
  account persona`, `uat golden pcr deal`, `uat customer master`, …), applied out of band. See §6
  — UAT's fixtures are currently not reproducible from source.
- **The two databases hold different data, and it matters which one you are testing.** UAT carries
  a bespoke seed of 207 employees and **no `Demo@2026` accounts**. The `GL&R` project carries the
  four demo migrations (`V21`, `V32`, `V46`, `V91.1`) and is the public showcase DB that
  `render.yaml` describes — `SPRING_PROFILES_ACTIVE=prod,demo`. Confirm which database the backend
  under test points at before reading anything into a result.
- **The `GL&R` project's history has known, harmless drift; UAT's has none.** It carries a `V11
  employee_password_hash` row where the repo now has `V11.1`/`V11.2`, a leftover from a
  pre-release renumbering. The schema objects those files create — `hr.employee.password_hash`,
  `sales.invoice_details`, `sales.commission_record` — are all **present and verified**, so this
  is a history-only discrepancy, not missing schema. It is also exactly why
  `application-prod.yml` defaults `validate-on-migrate` to `false`; see §6 item 3 before changing
  that.

## 4. The checks

### Phase 1 — Smoke

| # | Check | How to run it here | Status |
|---|---|---|---|
| 1 | **Smoke** — critical flows open and work | `npm run test:e2e` → `smoke.spec.js` (real Postgres rows rendering in the DOM) and `route-coverage.spec.js` (every route in `App.jsx`, as every seeded role, no error boundary and no 5xx) | ⚠️ Covered for *loads without crashing*. `route-coverage.spec.js` never asserts a page shows the **right** thing — only `smoke.spec.js` does that, for two screens. |
| 19 | **Console & network** — no unexpected errors or failed requests | Nothing asserts this. `route-coverage.spec.js` fails on a 5xx behind a page but ignores console output and 4xx noise | ❌ Manual: open DevTools on each critical screen. |

### Phase 2 — Frontend ↔ Backend integration

| # | Check | How to run it here | Status |
|---|---|---|---|
| 3 | **Integration** — all real endpoints tested | `npm run test:e2e` → `api-surface.spec.js` walks `API_ROUTES` (~219 endpoints, derived from `hrApi.js` at runtime, so a new endpoint is in scope the moment it exists) | ⚠️ Asserts *reachability and refusal*: anonymous GET **and** POST are 401, and no endpoint 5xxs for any authenticated role. It does not assert any endpoint returns *correct data*. |
| 20 | **Environment check** — API URL, DB, storage, env vars | `vercel.json` proxies `/api/*` → `https://gl-r-erp.onrender.com`; `render.yaml` declares the backend env; secrets are `sync: false` (dashboard-set, not in git) | ⚠️ Config is declarative and readable. **Not verifiable from a checkout**: which database `SPRING_DATASOURCE_URL` points at, and how `APP_FLYWAY_VALIDATE_ON_MIGRATE` is set, both live in the Render dashboard. See §6 items 3 and 5. |
| 21 | **No mock/test-data logic in the production build** | `cd frontend && npm run build`, then grep `dist/` for mock markers | ✅ **Verified today.** `src/api/index.js` selects the impl on `VITE_USE_MOCKS === 'true'` (so unset ⇒ real API) **and** throws at build time if mocks are on in a PROD build. Vite tree-shakes `mockApi.js` out entirely: `dist/` contains no `MOCK_`, no `mockApi`, no mock-mode strings. |

### Phase 3 — End-to-end

| # | Check | How to run it here | Status |
|---|---|---|---|
| 4 | **E2E** — complete user flows start → finish | `npm run test:e2e` → `write-overtime.spec.js`, `write-overtime-holiday.spec.js` | ❌ **One workflow only.** Overtime is driven end to end (`SUBMITTED → MANAGER_APPROVED → APPROVED`, each refusal asserted separately). Leave, pricing requests, deposit confirmation and deal close are **not driven end to end by anything** since the mock suite was removed. |
| 6 | **Data integrity** — create/update/delete calculations persist | The overtime specs read their setup back through a separate GET rather than trusting the create response — the right pattern, applied in one place | ⚠️ Real for overtime. Everything else: manual. Payroll/tax/commission math is deliberately **not** reimplemented in the mock, so no mock-driven test says anything about it. |
| 15 | **File** — upload / download / export / PDF / Excel | Components exist (`FileUploadField`, `AttachmentList`, `DealAttachmentsPanel`, CSV export in `DataTable`, payroll/commission exports); covered by vitest at component level | ⚠️ Component-level only. **No e2e exercises a real upload or a real download** against the backend. |

### Phase 4 — Regression

| # | Check | How to run it here | Status |
|---|---|---|---|
| 2 | **Regression** — previously working features still work | `npm run lint && npm test && npm run build`, plus `./mvnw -B clean verify` and `npm run test:e2e` | ⚠️ Strong at unit level (1581 frontend tests, 226 backend classes). Weak at **rendered-UI** level — see checks 10 and 11 in Phase 6. |
| 18 | **Performance sanity** — no obviously slow pages/endpoints | Nothing measures this | ❌ Manual. Note the deployment shape when judging: Render `singapore` → Supabase `ap-northeast-1` (prod) and `ap-southeast-2` (UAT), so a cross-region round trip is in every query. |

### Phase 5 — Role & permission

This is the phase with the strictest evidence bar in the repo, and the one where a mock-driven
"I clicked through it as HR" has already produced a wrong report once (issue #199, PR #238).

| # | Check | How to run it here | Status |
|---|---|---|---|
| 5 | **Role & permission** — each role sees/does only what it should | `npm run test:e2e` → `api-authz.spec.js` (authorization matrix, hit against the real service, written wrong-way-round; every row cites the deciding Java class) and `write-authz.spec.js` (98 resource-scoped writes × 6 roles = 588 requests, asserting **0 × 2xx** against a non-existent resource) | ⚠️ Genuinely strong **for the six seeded roles**. Three roles are untested — see below. |
| 22 | **Seed/test accounts ready for every UAT role** | Two different seeds; do not confuse them | ⚠️ **UAT is ready. The automated suite's seed is not.** |

**Role readiness — the two seeds differ, and the difference is the whole point:**

`DivisionAccessPolicy.roleFor` yields nine role strings. The demo seed
(`db/migration-demo/V21`) creates six personas; the UAT database has active, login-ready accounts
for **all nine** (read live, 10 August 2026 — counts only, no personal data):

| Role | Division | Active in UAT | Covered by `e2e-real`? |
|---|---|---|---|
| `ceo` | MD | 4 | ✅ |
| `hr` | HR | 2 | ✅ |
| `import` | PCIM | 3 | ✅ |
| `sales_manager` | SA (manager-titled) | 1 | ✅ |
| `sales` | SA (other) | 15 | ✅ |
| `employee` | no division / SV | 3 | ✅ |
| `account` | AC | 2 | ❌ **no demo persona** |
| `warehouse` | WH | 5 | ❌ **no demo persona** |
| `qc` | QC | 1 | ❌ **no demo persona** |

All 36 active UAT accounts have `must_change_password = false`, so none will hit the forced-change
screen on first login. What that check **cannot** tell you is whether anyone still knows those
passwords — the hashes are BCrypt and unreadable. Confirm a working credential for each of the
nine roles by actually logging in, before testers arrive rather than during the session.

So: **UAT can test all nine roles by hand, but the automated authz suite covers only six.**
`account` is the sharp end of that — it is in `TicketAccessPolicy.VIEWER_ROLES` and is the **only
role permitted to confirm payments**, and no automated test has ever exercised it. Give that role
deliberate manual attention in UAT.

**Also unverified**: HR's leave-review authorization. The demo seed cannot produce a reviewable
leave request (every leave type either auto-approves, auto-rejects for a missing hire date,
requires an attachment, or has zero quota), so the review path is unreachable from it. This is the
interesting counterpart to #199 — `LeaveService.REVIEW_ALL_ROLES` is `{hr}`, so HR *can* review
leave while being refused overtime, and that asymmetry has never been tested.

### Phase 6 — UI / responsive / browser

This phase is where the repo is weakest, and it is weak for a **known, deliberate reason**: 65
tests' worth of visual, layout and form-behaviour coverage was removed on 2026-08-08 (PR #592) and
the pixel gate was retired on 2026-08-10 (PR #637, owner decision — it compared at
`maxDiffPixelRatio 0`, which is correct for a CSS port and wrong for a deliberate redesign).

Neither removal was a mistake. But together they mean **`lint`, `test` and `build` can all be green
while every screen is visually broken** — jsdom has no layout engine, so nothing automated catches
it. Treat every row below as a manual gate.

| # | Check | How to run it here | Status |
|---|---|---|---|
| 10 | **UI/UX review** — spacing, density, hierarchy, duplicate actions | Against [`DESIGN.md`](../DESIGN.md) and the tokens in `frontend/src/index.css` | ❌ Manual. |
| 11 | **Responsive** — mobile / tablet / desktop | Harness kept runnable: `cd frontend && VISUAL_BASELINE=1 npm run test:visual` (mock frontend, pixel diff) | ❌ No CI gate. The harness diffs pixels; it does not judge whether a layout is *right*. |
| 12 | **Browser** — Chrome + other supported browsers | `playwright.real.config.js` declares **`chromium` only**; no `browserslist` is declared anywhere | ❌ Chromium only. Nothing has ever run against Firefox or Safari. Decide the supported set before UAT and say so in the UAT brief. |
| 13 | **Loading / double-submit** | `SafeForm` implements the submitter guard; `SafeForm.test.jsx` covers it at unit level; the `/__e2e/safe-form-submitter-probe` route still exists | ⚠️ Unit-covered. The e2e spec that drove it (`safe-form-submitter-guard`) was removed with the mock suite. |
| 14 | **Refresh / back button** — state stays consistent | `auth.spec.js` asserts session survival across a hard reload | ⚠️ Session only. No coverage of back-button behaviour on multi-step flows. |
| 17 | **Search / filter / sort / pagination** | vitest covers `DataTable` behaviour | ⚠️ **Mirror the real `ORDER BY` and `LIMIT` when checking this.** The contract test compares parameter *counts*, never ordering — and the same limit under a different sort truncates a different set of rows. That mechanism is what opened issue #434. |

### Phase 7 — Correctness, validation, errors

| # | Check | How to run it here | Status |
|---|---|---|---|
| 7 | **Validation** — required fields, invalid input, duplicates, limits | Zod schemas in `frontend/src/api/schemas`, Bean Validation on the backend, DB constraints | ⚠️ Well covered at unit level; no systematic sweep. Note **there is no customer duplicate detection at all** (#401) — duplicates are a product gap, not a validation bug. |
| 8 | **Error handling** — 400 / 401 / 403 / 404 / 409 / 500 | `api-surface.spec.js` + `write-authz.spec.js` sweep the surface for unexpected 5xx | ⚠️ **Two known violations of this exact check are recorded and still open.** See below. |
| 9 | **Empty / null / edge cases** | `EmptyState`, `StatePanel`, `Skeleton` exist and are unit-tested | ⚠️ Components exist. No sweep asserts every list uses them. Long-text and large-number rendering: manual. |
| 16 | **Date / time / currency** — format, timezone, rounding | `Asia/Bangkok` (39 refs), `th-TH` (46), THB (157), `Intl.DateTimeFormat` (28) | ⚠️ Unit-covered per component; no cross-cutting audit. The e2e specs deliberately use **today in `Asia/Bangkok`** rather than a hardcoded date, because approval is gated on the payroll month being open — keep that habit in any new test. |

**The two recorded error-handling defects**, both found by the real-stack sweeps and both
deliberately left unfixed there (a controller's response status is an API-contract change and
belongs in its own branch):

1. **`PriceImportController` returns 500, not 404, for an unknown id** — on `GET
   /api/price-import/profile/{factoryId}`, `POST /api/price-import/validate/{id}` and `POST
   /api/price-import/commit/{id}` alike. A real id returns 200, so it is specifically the
   missing-row path. Only `import` and `ceo` can reach it; every other role is refused 403 before
   the lookup, so the defect is invisible to most of the role matrix.
2. **A wrong HTTP verb returns 500, not 405** — `GET` on a POST-only endpoint produces an
   unhandled `HttpRequestMethodNotSupportedException` reaching the generic error handler.

Both are pinned as **exact** expectations in the sweeps, not skips — so a *new* server error fails
the suite, and so does fixing one of these without deleting its entry.

### Phase 8 — Bug cleanup and environment

| # | Check | How to run it here | Status |
|---|---|---|---|
| 23 | **Known issues documented** with workaround + severity | GitHub issues; #404 is the standing full-stack audit | ✅ 11 open issues, all triaged and labelled. |
| 24 | **Critical/High bugs = 0** | `label:critical` / `label:bug` | ⚠️ **One open critical: #439 — and it is stale.** Its claim (prod 4 migrations behind) was true on 2 August; both environments are at V138 today with 0 failures. Verify and close before sign-off, or the gate is measured against a phantom. |
| 25 | **Test evidence captured** for critical flows | Playwright traces/screenshots from `test:e2e`; manual screenshots otherwise | 🔲 Per-round. |
| 26 | **UAT environment deployed and stable** | Render (`gl-r-erp`, `singapore`, `starter`) + Supabase UAT | ⚠️ DB verified current (V138, 0 failed). **Service health not verifiable from this environment** — outbound HTTPS to `gl-r-erp.onrender.com` is blocked by the agent proxy (403 on CONNECT). Confirm `GET /actuator/health` by hand. |

**`autoDeploy: false`** is set deliberately (owner ruling, 2026-08-03) because this service sits on
a database people rely on. A merge to `main` does **not** reach UAT — someone must trigger the
deploy from the Render dashboard. Before UAT, confirm the running build is the commit you think it
is.

## 5. What has no coverage — read before signing off

Condensed from §4. These are the honest gaps; none is a reason not to run UAT, but each is a reason
not to *claim* something is verified.

1. **Rendered UI, at every viewport.** No automated gate since 2026-08-08/08-10. `lint`, `test` and
   `build` were green for all four CSS-port regressions that motivated the visual harness.
2. **Every business workflow except overtime.** Leave, pricing requests, deposit confirmation and
   deal close are driven end to end by nothing.
3. **Three roles — `account`, `warehouse`, `qc`.** No demo persona, so zero automated authz
   coverage. `account` is the only role that can confirm payments.
4. **HR's leave-review authorization.** Unreachable from the demo seed.
5. **Browsers other than Chromium.** Never run.
6. **Real file upload/download through the stack.** Component-level only.
7. **Console errors, performance, back-button state.** Nothing measures them.
8. **Payroll, tax, commission and pricing math.** Out of scope for the mock by standing rule — a
   mock that mirrors a computation can never validate it. Backend unit/integration tests are the
   only evidence here, and they did not run in this environment.

## 6. Open items before UAT

Ordered by what blocks a sign-off soonest.

| | Item | Why it matters |
|---|---|---|
| 1 | **Run `./mvnw -B clean verify` and `npm run test:e2e` green on a machine with Postgres.** | Both are unrun in this baseline. 128 integration tests and every authz assertion in the repo live behind them — the gate is not measurable without them. |
| 2 | **Verify and close #439.** | It is the only open critical, and it no longer describes reality. A stale critical either blocks the gate for nothing or trains people to ignore the label. |
| 3 | **Decide `APP_FLYWAY_VALIDATE_ON_MIGRATE` per database — do not flip it globally.** | See below; the right answer differs for the two projects, and getting it backwards breaks a deploy. |
| 4 | **Get UAT's `V900`–`V911` seed into the repository.** | UAT's fixtures cannot currently be rebuilt from source. If that database is lost or reset, they go with it — customer master, the golden PCR deal, the `account` persona, and the only accounts that exist for `account`, `warehouse` and `qc`. |
| 5 | **Confirm which backend serves the UAT database.** | `render.yaml` declares **one** service, and its documented configuration (`prod,demo` + demo Flyway locations) matches the `GL&R` showcase project, not UAT. Whatever points at UAT is not described in the repo. |
| 6 | **Decide and publish the supported browser set.** | Testers need to know what to file a bug against. Today only Chromium has ever been run. |
| 7 | **Confirm the deployed UAT build is the intended commit,** and that `GET /actuator/health` answers. | `autoDeploy: false`; not checkable from a checkout. |
| 8 | **Brief testers on the login rate limiter.** | See below — this will otherwise eat a UAT session. |

### Item 3 in full — why `validate-on-migrate` is not a single switch

`application-prod.yml` reads `validate-on-migrate: ${APP_FLYWAY_VALIDATE_ON_MIGRATE:false}`, and
the `prod` profile is activated by **both** the real deploy and the public showcase. That single
default therefore lands on two databases whose histories are in different shape, and the correct
setting differs:

- **On the `GL&R` showcase project, leaving it `false` is deliberate and correct.** That database
  carries the `V11` → `V11.1`/`V11.2` renumbering drift described in §3. Setting
  `APP_FLYWAY_VALIDATE_ON_MIGRATE=true` against it would **fail the deploy on startup** —
  validation compares the applied history to the files on the classpath and finds a resolved
  migration the history does not know about. `application-prod.yml`'s own comment says exactly
  this ("needs forward-only recovery migrations to run despite that history drift").
- **On the UAT project, turning it on is safe and worth doing.** Its history matches the repo's
  133 core migrations exactly, with nothing missing and nothing extra.

The risk #439 raised is real and unaddressed either way: with `out-of-order` unset and validation
off, a migration numbered below the applied max is **silently skipped on a green deploy** — no
error, a missing schema object, and a feature that fails at runtime with its cause several merges
upstream. `V67`'s header records that having already happened here. The fix is per-environment,
not a global flip: turn validation on where the history is clean, and keep the drifted showcase
DB on `false` until its history is repaired.

### Item 8 in full — the login rate limiter

`LoginRateLimitFilter` counts **both 401 and 403** responses to `POST /api/auth/login` as auth
failures: **5 per account** and **20 per client IP** within a 900s window, locking out for 900s
(`APP_LOGIN_MAX_ACCOUNT_FAILURES` / `APP_LOGIN_MAX_IP_FAILURES` / `APP_LOGIN_WINDOW_SECONDS` /
`APP_LOGIN_LOCKOUT_SECONDS`).

Two consequences for a UAT room:

- **A roomful of testers behind one office NAT shares the 20-failure IP budget.** Four people
  fat-fingering a password five times each will lock out *everyone*, including testers who typed
  nothing wrong.
- **A misconfigured CORS origin does not merely fail — it locks the account out**, because every
  rejected login is a 403 and therefore a counted failure.

Recovery is instant: counters are held in an in-memory map, so restarting the backend clears every
lockout. Otherwise wait out the window, or log in successfully once — which resets both counters.

## 7. Sign-off

UAT starts when every phase is either green or has an accepted, written exception.

| Phase | Owner | Date | Result | Accepted exceptions |
|---|---|---|---|---|
| 1 — Smoke | | | | |
| 2 — Integration | | | | |
| 3 — E2E | | | | |
| 4 — Regression | | | | |
| 5 — Role & permission | | | | |
| 6 — UI / responsive / browser | | | | |
| 7 — Correctness / validation / errors | | | | |
| 8 — Bug cleanup & environment | | | | |

**Go / No-Go:** ................................ **Date:** ....................

> An exception is a decision, not an omission. Write down what is not covered and who accepted the
> risk — an empty cell reads as "passed" to whoever finds this document later.
