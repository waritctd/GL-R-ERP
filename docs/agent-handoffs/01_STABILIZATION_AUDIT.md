# 01 — Stabilization Audit (Brutal, Honest)

_Senior-engineer audit of GL-R-ERP as of 2026-07-07. Branch `main`, working tree clean, no release tags. Every claim below was verified against the repo, not assumed._

**One-line verdict:** Functional but fragile. The app works because the developers are careful, not because the architecture makes mistakes hard. Every safety property — authorization, error handling, data loading, mobile layout — is opt-in. The HR-core subset is a realistic v0.1.0 target once security is made default-deny, the temp-password path is closed, mobile has a real shell, and the backend gets a testing/observability floor.

---

## 1. Product Identity

- **This is an HR / payroll / internal portal, not an ERP yet.** In-scope, working domains: employees, attendance, overtime, leave, payroll, profile requests, auth, dashboards. The "ERP" ambition lives mostly in a half-built **sales/CRM stack**: tickets/price-requests, quotation, deposit notices, commission, pricing/FX, catalog, customer, factory.
- **Where scope is unclear:** the sales stack is wired into the same nav, same `App.jsx` router, same permissions table, and same global stylesheet as HR — so it reads as "part of the product" even though it is the least tested and highest churn. `CeoSettingsPage` mixes pricing/FX/factory config (sales) with settings surfaced to CEO/admin. README calls the repo a "GL&R HR Portal"; other docs describe quotation/ticket ERP flows. The identity is split.
- **Modules that should be frozen for v0.1.0:** the **entire sales/CRM stack** — `tickets`, `quotation`, `deposit`, `commission`, `pricing`, `catalog`, `customer`, `factory`, and `ceo-settings`. These are money/legal-sensitive, highest-churn (payroll+ticket+deposit dominate recent commits), and least integration-tested. Stabilizing them is a separate, later milestone. Freeze and hide; do not expand.

---

## 2. Frontend

**Stack:** React 18.3, Vite 8, `lucide-react`. **No router. No state/data library.** `package.json` dependencies are literally `react`, `react-dom`, `lucide-react`.

| Area | State | Evidence |
|---|---|---|
| App structure | Feature folders under `src/features/*`, shared `components/common`, one `useHrData` hook | `frontend/src/` |
| Routing | **~30-branch ternary** selecting the screen; string route in `useState`; **zero** `pushState`/`popstate` | `App.jsx:149-181`, `hooks/useHrData.js` |
| State management | Local `useState` only; global-ish state hand-rolled in `useHrData` | `hooks/useHrData.js` |
| Server-state handling | **Manual** `useEffect(fetch)` + `loading`/`error` re-implemented on every page; no cache, no dedup, no retry; silent `.catch(() => [])` | `hooks/useHrData.js:21-28`, every `features/*Page.jsx` |
| Mobile responsiveness | **4 media queries total** (2 are `prefers-reduced-motion`) across a 1891-line sheet; sidebar `display:none` <720px with no replacement | `styles.css` |
| Tables | CSS-grid tables forced to `min-width:900px` <1040px inside `overflow-x:auto` | `styles.css:1805-1828` |
| Forms | Grids do stack at ≤720px; but **no inline validation**, no `aria-invalid`/`aria-describedby`; inputs inherit ≤14px → iOS zoom | `styles.css:218-237,1831-1843` |
| CSS organization | **Single global unscoped `styles.css`** (~1891 lines). Design tokens exist (`:root`) but colors/spacing only partly tokenized | `styles.css:1-90` |
| Component reuse | Good primitives exist and are used: `DataTable`, `ConfirmDialog` (9 uses), `Breadcrumbs` (in all deep detail pages), `Toast`, `Icon` | `components/common/*` |
| Dependencies | Lean and current (recent breaking-upgrade PRs merged: Vite 8, lucide 1.x, hooks-eslint 7) | `package.json` |
| Build/lint/test | Vite build; ESLint + `jsx-a11y`; Vitest (**only 7 test files**) | `package.json`, `frontend-ci.yml` |

**Brutally honest:**
- **Mobile usability is effectively unbuilt.** A phone user has no navigation at all below 720px, and every list is a 900px horizontal-scroll trap. See §5.
- **Manual data fetching is a scaling wall.** Each page owns its fetch/loading/error; switching routes refetches from zero, failures are swallowed into empty states, and two components wanting the same resource issue two requests. This is the single biggest architecture debt for ERP-style workflows.
- **No frontend architecture for growth.** No router means no deep-linking, no working browser back button, and page refresh loses context. No global `ErrorBoundary` (grep: 0) — any component throw white-screens the whole app.
- **`App.jsx` is doing too much:** session restore, login/logout, password-change gating, ticket/deposit selection state, permission-derived routing, and the 30-branch screen switch — all in one 200-line component.
- **Is the UI scalable for ERP workflows? Not yet.** The table + form + fetch patterns are copy-paste per feature; adding modules multiplies boilerplate rather than reusing a data/table/form layer.

**Good, worth keeping:** clean feature-folder separation, real reusable primitives, an `import.meta.env.PROD` guard that hard-fails if the mock (password-less role login) is ever built into prod (`api/index.js`), and a11y linting in CI.

---

## 3. Backend

**Stack:** Spring Boot 4.1 / Spring Framework 7 / Java 21. 19 `@RestController`s. Flyway `V1`–`V32`. Session auth via Spring Session JDBC.

| Area | State | Evidence |
|---|---|---|
| Structure | Clean package-per-domain under `th.co.glr.hr.*` (controller/service/repository/dto) | `backend/src/main/java/...` |
| Auth/security | Session-based; `SecurityConfig` filter chain is `anyRequest().permitAll()`; authz is manual | `config/SecurityConfig.java:23`, `auth/SessionContext.java` |
| Database/Flyway | Migrations V1–V32 + `migration-demo`; `FlywayMigrationTest` applies all end-to-end in CI | `resources/db/migration/*`, `test/.../FlywayMigrationTest.java` |
| Tests | 36 test files; unit (Mockito) + 6 Postgres integration tests **gated on `TEST_DB_URL`** | `src/test/...`, `support/AbstractPostgresIntegrationTest.java` |
| Controllers/services/repos | Consistent layering; some very large repos (`payroll` 387 lines, `deposit` 346) | see §Risky modules |
| API design | REST-ish `/api/*`, hand-written; no generated contract | `api/routes.js` mirrors it by hand on the frontend |
| Observability | **None.** No Actuator, no health endpoint, no metrics, no correlation IDs, no error monitor | `pom.xml`, `application.yml` |
| OpenAPI/docs | **None.** No springdoc/swagger dependency | `pom.xml` (verified absent) |
| Integration-test reliability | Run **only in CI** (Postgres service). Local `mvnw verify` **skips** them (`@EnabledIfEnvironmentVariable`); no Testcontainers | `AbstractPostgresIntegrationTest.java` |

**Brutally honest:**
- **Auth risk is structural (see §4).** `permitAll` + 92 hand-placed `SessionContext.requireUser`/`requireAnyRole` checks + `@PreAuthorize` on only 2 of 19 controllers = no default-deny. One forgotten check = an anonymous-readable endpoint.
- **Employee-code temporary password is a real production risk.** `PasswordBackfillRunner` BCrypts each active employee's `employee_code` as their initial login password (`must_change_password=TRUE`). Employee codes are low-entropy and known internally (badges, attendance exports). `must_change_password` only fires *after* a successful first login — it does **not** stop the first login by anyone who knows the code. With the in-memory rate limiter (5/account, 20/IP) the code space is brute-forceable. `AuthService.changePassword` correctly blocks reusing the code as the new password, but the initial-credential-equals-employee-code window is the DoD blocker. `auth/PasswordBackfillRunner.java`, `auth/EmployeeAuthRepository.java:82-95`.
- **Test gaps on money logic.** Payroll has calculator+service unit tests but **no controller and no integration test**. Commission has only a 32-line calculator test — no service/integration/clawback coverage. Pricing/FX has **zero tests**. Deposit renderer has a thin (~82-line) test for customer-facing legal PDFs.
- **Observability gaps make production blind.** A 500 today is diagnosed by timestamp-grepping: `ApiExceptionHandler` logs `"Unhandled API exception"` with the stack trace but no request path, user, or correlation id. No health endpoint for the load balancer/Render probe.
- **Module boundary risk:** audit logging exists (`AuditService`) but is wired **only into payroll**; mutations in leave/overtime/employee/commission are unaudited.

**Good, worth keeping:** clean layering, real Flyway discipline with an end-to-end migration test, session fixation handled (`changeSessionId` on login), strong security-headers filter, and a least-privilege DB-role script already documented (`docs/least-privilege-db-role.md`).

---

## 4. Security

| Control | State | Verdict |
|---|---|---|
| Authentication | Session-based (Spring Session JDBC); role login disabled server-side (`"Role login is disabled"`) | OK |
| Authorization | `permitAll` filter chain + manual `SessionContext` checks + `@PreAuthorize` on 2/19 controllers | 🔴 **Not default-deny** |
| Password handling | BCrypt (strength 10); **but employee-code seeded temp password** | 🔴 Temp-password path blocks prod |
| Session/cookie | HttpOnly + SameSite=Lax + Secure; 30-min timeout | 🟢 Good |
| CSRF | Custom double-submit `CsrfCookieFilter` (timing-safe compare); Spring CSRF disabled | 🟡 Verify it *rejects* mismatches, not just issues cookies |
| CORS | Env-driven allowlist with validation (rejects `*`, requires https in prod) | 🟢 Good (env-dependent) |
| Rate limiting | In-memory sliding window (5/account, 20/IP) | 🟡 Fine single-instance; breaks if scaled horizontally |
| Audit logs | DB-backed `AuditService`, **payroll-only** | 🟡 Extend to other mutations |
| PII/salary/payroll access | `EmployeeService.get(id)` returns any employee by id then field-filters by role, **not by division** | 🔴 Cross-division employee-detail leak |
| Upload handling | Ticket attachments (`AttachmentController`, sales stack) — frozen for v0.1.0 | ⚪ Out of scope; re-review before unfreezing |
| Security headers | `X-Content-Type-Options`, `X-Frame-Options: DENY`, strict CSP, HSTS | 🟢 Good |
| Secrets | Env-var driven (`render.yaml` marks secrets `sync:false`); no hardcoded secrets found | 🟢 Good |

**Blocks real production use:** (1) `permitAll` default-allow authorization model; (2) employee-code temporary password on the production login path; (3) cross-division employee-detail exposure via `EmployeeService.get(id)`. Fix all three before v0.1.0.

**Not blockers but track:** frontend `ROLE_PERMISSIONS` is advisory only (trivially bypassed by calling `/api/*` directly — real enforcement must be server-side); rate limiter is single-instance; CSRF filter enforcement should be re-verified with a test.

---

## 5. Mobile UX

Scope note: only **HR-core** pages need mobile work; the sales stack is frozen.

| Problem | Where (exact) | Severity |
|---|---|---|
| Sidebar blocks mobile | `styles.css:1858` `.sidebar{display:none}` at ≤720px; `AppShell.jsx` always renders `<Sidebar>`, topbar has **no menu button** → zero nav on phones | 🔴 |
| Tables unusable | `styles.css:1805-1816` `min-width:900px` on `.employee-/.attendance-/.request-/.payroll-/.overtime-/.leave-table` inside `overflow-x:auto` | 🔴 |
| iOS input zoom | `input,select,textarea{font:inherit}` (`styles.css:228`) + `label{font-size:13px}` (218), tokens cap at 14px → inputs <16px | 🔴 (app-wide) |
| `height:100vh` | `.app-shell` (313), `body`/`.login-screen` (104,129), `.modal-panel max-height ...100vh` (1446) → iOS address-bar clipping | 🟠 use `100dvh` |
| Tiny touch targets | `.icon-button` 36px (545-546), `.status-badge` min-height 26px (1313) vs 44px guideline | 🟠 |
| Fixed widths | `.search-field min-width:240px` (784), `.filter-bar select min-width:142px` (778) — mitigated by `flex-wrap:wrap` (767) | 🟡 |
| Modal width | `.modal-panel width:min(720px,100%)` (1445) fine; body scrolls (1480); only `max-height` needs `dvh` | 🟢 mostly OK |
| Hover-only | 5 `:hover` rules; only `.info-tip-trigger:hover` (1700) may gate content | 🟡 verify tap access |

**Pages that MUST support mobile:** Login, Employee Dashboard, HR Dashboard, Profile / My Requests / Profile Requests, Attendance (view own punches), Overtime (submit/approve), Leave (submit/approve, balances), Employees list + detail.

**Pages that can remain desktop-first (+ what to show on mobile):**
- **Payroll processing** (`PayrollPage.jsx`) — two-panel bulk entry + irreversible Process + bank export → mobile: read-only per-employee card summary + a clearly labeled "แก้ไขบนเดสก์ท็อป / edit on desktop" notice.
- **Attendance import** (`AttendancePage.jsx` .dat upload + up-to-2000-row table) → mobile: hide import panel, show read-only recent-punches cards.
- **Employee create/edit** big forms → keep usable (grids already stack); no special fallback needed.

Suggested mechanism: a small `useIsMobile()` (matchMedia `max-width:720px`) hook + a reusable `<DesktopOnlyNotice>` for the two heavy workflows — cheaper and safer than making bulk data entry fully responsive.

_Full detail: `docs/ux-ui-audit.md` and the mobile audit already produced this session._

---

## 6. Repo Hygiene

- **Branches:** many stale local + remote feature branches (`feat/*`, `fix/*`, `chore/*`, `claude/*`, `demo/*`). No convention enforced; prune merged ones. Confirm `main` is the only protected branch.
- **Docs:** `docs/` holds a mix of current and stale — `M0_SURVEY.md` and the `QUOTATION_AND_REVISION_PLAN.md` / `TICKET_DASHBOARD_PLAN.md` are pre-implementation planning docs (sales stack, now frozen); `decisions/`, `least-privilege-db-role.md`, and `ux-ui-audit.md` are current. A binary `quotation_template_source.xlsx` lives in `docs/`. No index/README inside `docs/`.
- **README:** root README is accurate for setup (frontend/backend split, dev commands) but calls the product an "HR Portal" while other docs imply ERP — align naming.
- **CI:** three workflows (`backend-ci`, `frontend-ci`, `dependency-review`). Solid basics; **no coverage gate**, no OpenAPI publish, no release automation.
- **Release tags:** **none.** v0.1.0 will be the first.
- **Environment docs:** deployment via `render.yaml` + `vercel.json` + `docker-compose*`; env vars are referenced but there's no single "required env vars" doc.
- **Duplicated/unclear:** frontend has both root `vercel.json` and `frontend/vercel.json`; verify which is authoritative. No `.env.example` for either app.

**Cleanup targets for `docs/v0.1-cleanup`:** add `docs/README.md` index, move frozen sales planning docs under a `docs/archive/` or `docs/sales-later/`, add `.env.example` for frontend+backend, document required env vars, prune merged branches.

---

## 7. Prioritized Fix Plan

### P0 — Stop-ship (blocks v0.1.0)
- **P0-1 Mobile app shell:** hamburger + off-canvas nav drawer to replace `display:none` sidebar; global input `font-size:16px` (kills iOS zoom); `100vh→100dvh`.
- **P0-2 Mobile core list cards:** card fallback for the 6 HR-core `min-width:900px` tables.
- **P0-3 Auth/password hardening:** remove employee-code temporary password from the production login path; close the cross-division `EmployeeService.get(id)` leak; verify `CsrfCookieFilter` rejects mismatches.
- **P0-4 Default-deny authorization** (couple with P0-3): flip `SecurityConfig` to `authenticated()` + explicit public allowlist so a forgotten manual check fails closed.

### P1 — Maintainability blockers
- **P1-1 TanStack Query for core server state** (employees, profile requests, dashboards, leave/overtime lists) — kill manual fetch/loading/error boilerplate; add caching + dedup.
- **P1-2 Real frontend routing** (URL-based) — restore deep-linking + browser back; retire the `App.jsx` ternary for HR-core routes.
- **P1-3 Backend observability floor** — Spring Actuator health (safely exposed), correlation-ID MDC filter, enrich `ApiExceptionHandler` with requestId/user/method/path.
- **P1-4 Backend testing floor** — Testcontainers so integration tests run locally + reliably; add payroll controller/integration tests; add a CI coverage gate.
- **P1-5 Global frontend `ErrorBoundary`** — stop full-app white-screens.

### P2 — Quality improvements
- **P2-1 OpenAPI/springdoc** — generated API contract + published docs.
- **P2-2 Form architecture** — reusable `FormField` with validation/`aria-*`, starting with leave.
- **P2-3 Table architecture** — shared responsive table/card component, starting with employees.
- **P2-4 Audit-log coverage** — extend `AuditService` to leave/overtime/employee/commission mutations.
- **P2-5 Design tokens finished**, **docs cleanup**, **branch pruning**.

---

## 8. Exact Branch Sequence

One branch per task; `main` stays deployable; merge only after review. Recommended order (audit did not find a reason to deviate from the proposed sequence, so it is adopted with scope notes):

1. `fix/mobile-app-shell` — nav drawer + hamburger; input `font-size:16px`; `100dvh`; touch targets. _(P0-1)_
2. `fix/mobile-core-list-cards` — card fallback for HR-core tables (attendance/leave/overtime/employees/payroll/requests). _(P0-2)_
3. `refactor/tanstack-query-core` — add TanStack Query; migrate the shared data layer (`useHrData` seam). _(P1-1)_
4. `refactor/query-employees-requests` — migrate employees + profile-requests reads/writes onto Query. _(P1-1)_
5. `refactor/frontend-routing` — URL routing for HR-core routes; retire the `App.jsx` ternary incrementally. _(P1-2)_
6. `security/password-hardening` — remove employee-code temp-password prod path; default-deny `SecurityConfig`; close division leak; CSRF-enforcement test. _(P0-3/P0-4)_
7. `backend/openapi-docs` — springdoc + published contract. _(P2-1)_
8. `backend/actuator-health` — Actuator health, safely exposed (no sensitive endpoints public). _(P1-3)_
9. `backend/testcontainers-postgres` — Testcontainers so integration tests run locally; payroll controller/integration tests; CI coverage gate. _(P1-4)_
10. `frontend/form-architecture-leave` — reusable validated `FormField`, applied to leave. _(P2-2)_
11. `frontend/table-architecture-employees` — shared responsive table/card component, applied to employees. _(P2-3)_
12. `docs/v0.1-cleanup` — docs index, archive frozen sales plans, `.env.example`, env-var doc, branch pruning. _(P2-5)_
13. `release/v0.1.0-stable-hr-portal` — final verification pass + tag `v0.1.0`.

**Sequencing note:** 1→2 are independent and can run in parallel. 6 (security) is P0 and could move earlier if a production login is imminent — it is placed after the mobile pair only because mobile is the owner's stated first priority; do **not** ship v0.1.0 without 6. 3 must land before 4/5. 8/9 are independent of the frontend track.

---

## 9. Agent Assignment

Model guidance: Opus/Fable = architecture/security/review; Sonnet = medium planning + smaller reviews; Codex GPT-5.3-Codex / GPT-5-Codex = implementation; Codex Mini / Claude Haiku = docs/mechanical only.

| # | Branch | Planner | Implementer | Reviewer |
|---|---|---|---|---|
| 1 | `fix/mobile-app-shell` | Claude Sonnet | Codex GPT-5.3-Codex | Claude Opus |
| 2 | `fix/mobile-core-list-cards` | Claude Sonnet | Codex GPT-5.3-Codex | Claude Sonnet |
| 3 | `refactor/tanstack-query-core` | **Claude Opus** | Codex GPT-5.3-Codex | Claude Opus |
| 4 | `refactor/query-employees-requests` | Claude Sonnet | Codex GPT-5.3-Codex | Claude Sonnet |
| 5 | `refactor/frontend-routing` | **Claude Opus** | Codex GPT-5.3-Codex | Claude Opus |
| 6 | `security/password-hardening` | **Claude Opus** | Codex GPT-5.3-Codex | **Claude Opus** |
| 7 | `backend/openapi-docs` | Claude Sonnet | Codex GPT-5.3-Codex | Claude Sonnet |
| 8 | `backend/actuator-health` | Claude Sonnet | Codex GPT-5.3-Codex | **Claude Opus** (exposure review) |
| 9 | `backend/testcontainers-postgres` | Claude Opus | Codex GPT-5.3-Codex | Claude Opus |
| 10 | `frontend/form-architecture-leave` | Claude Sonnet | Codex GPT-5.3-Codex | Claude Sonnet |
| 11 | `frontend/table-architecture-employees` | Claude Sonnet | Codex GPT-5.3-Codex | Claude Sonnet |
| 12 | `docs/v0.1-cleanup` | Claude Sonnet | Codex Mini / Claude Haiku | Claude Sonnet |
| 13 | `release/v0.1.0-stable-hr-portal` | **Claude Opus** | Claude Opus | Human owner |

Rule reminder: **reviewer agents do not implement** except tiny safe fixes. Architecture/security branches (3, 5, 6, 8, 9, 13) get Opus planning and/or review.

---

## 10. Final Recommendation

- **Keep feature development frozen.** Do not add ERP features or expand the sales stack until v0.1.0 is tagged. The money/legal modules (payroll, commission, pricing/FX, deposit) stay frozen and untouched beyond the security/observability work above.
- **Next agent should do branch 1, `fix/mobile-app-shell`** — it is the owner's stated first priority, unblocks all mobile navigation, and its riskiest change (a nav drawer) is isolated to `AppShell.jsx` + `Sidebar.jsx` + additive `@media` CSS. The three global fixes it carries (input `font-size:16px`, `100dvh`, touch targets) are near-zero-risk.
- **Do not run branch 1 and any backend branch on the same working copy at the same time** if the same person is driving both; keep one implementation agent per branch.

### Exact Next Prompt (for the next agent)

```
You are the implementation agent for branch `fix/mobile-app-shell` on GL-R-ERP.

Before doing anything:
1. Read docs/agent-handoffs/00_MASTER_CONTEXT.md and docs/agent-handoffs/01_STABILIZATION_AUDIT.md.
2. Run `git status` and confirm a clean tree on `main`.
3. Create and switch to branch `fix/mobile-app-shell`.
4. Create docs/agent-handoffs/02_fix-mobile-app-shell.md from the template in README.md and fill Base Commit.

Scope (do ONLY this, HR-core pages only; sales stack stays frozen):
- Add a mobile navigation drawer: a hamburger button in the topbar (`frontend/src/components/layout/AppShell.jsx`) that opens the existing `Sidebar` as an off-canvas drawer with a backdrop below 720px; close on route change and on backdrop tap. Add the drawer + backdrop rules inside a NEW `@media (max-width: 720px)` block in `frontend/src/styles.css` — do not edit shared base table/grid selectors.
- Global iOS-zoom fix: set `font-size: 16px` on the `input, select, textarea` rule (`frontend/src/styles.css:226-237`).
- Replace `100vh` with `100dvh` at `styles.css` lines ~104, ~129, ~313, and the modal `max-height` ~1446.
- Bump touch targets: `.icon-button` to 44x44 (`styles.css:545-546`); give `.status-badge` a larger tap area where it is interactive.

Out of scope: routing changes, data fetching, table card layout (that is branch 2), any sales/CRM page, any business logic.

Constraints:
- Keep changes small and reviewable. Do NOT set user-scalable=no / maximum-scale in index.html.
- Do not rewrite styles.css; add mobile rules in @media blocks. The only safe base-rule edit is the input font-size.

Before finishing:
- Run `npm run lint`, `npm test`, and `npm run build` in `frontend/`.
- Verify at 375px and 768px that the drawer opens/closes, all HR-core routes are reachable, inputs don't trigger zoom, and nothing regresses on desktop.
- Fill in every section of docs/agent-handoffs/02_fix-mobile-app-shell.md (files changed, commands run, test/build results, risks, things not finished) and set "Recommended Next Agent" to a Claude Opus review, with the exact next prompt.

Do not merge. Hand off for review.
```
