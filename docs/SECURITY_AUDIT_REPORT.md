# GL&R ERP — Code & Security Audit Report

| | |
|---|---|
| **Project** | GL&R ERP / HR Portal (`waritctd/GL-R-ERP`) |
| **Repository visibility** | Public |
| **Audit date** | 2026-06-29 |
| **Auditor** | Claude Code (automated code audit) |
| **Stack** | Spring Boot 4.1 / Java 25 backend · React 18 + Vite frontend · PostgreSQL (Supabase) · Flyway · Python attendance agents (ZKTeco SC700) |
| **Deployment** | Backend on Render · Frontend on Vercel · DB on Supabase (ap-northeast-1) |
| **Branch audited** | `main` (and feature branches) |

---

## 1. Executive Summary

The GL&R ERP is a Thai-language HR, attendance, and sales-ticketing system handling sensitive
employee data, including PDPA special-category personal information. The audit covered security,
code quality, performance, reliability, testing, compliance, infrastructure, and documentation.

**Overall posture:** the application's *business-layer authorization* and *data-access layer* are
well engineered — consistent server-side role/ownership checks, fully parameterized SQL (no
injection found), a correct CSRF double-submit implementation, sanitized CORS, and a non-leaky
global error handler. The serious problems were concentrated in **authentication** and **secrets /
data-at-rest handling**.

The single most critical finding: **authentication could be trivially bypassed** because the login
"password" was the user's **employee code**, and employee codes are **sequential and predictable**
(`GLR-1`, `GLR-2`, …). Combined with the absence of rate limiting, this allowed scriptable account
takeover — including HR accounts that expose all employee PII. Root cause was traced to a prior
migration (`V5`) that **deliberately removed** the original hashed-password + RBAC tables.

**Result of the engagement:** 4 private security advisories and 12 public issues were filed, and
**8 remediation pull requests** were opened and verified (all backend PRs pass the full test suite;
frontend PRs pass `vite build`). The Critical auth flaw and the High brute-force exposure are fully
remediated in code. Remaining items are blocked on owner-side actions (secret rotation, encryption
key management, infrastructure provisioning) and are itemized in §8.

### Findings at a glance

| Severity | Count | Remediated | Pending owner action |
|---|---|---|---|
| 🔴 Critical | 2 | 1 (in code) | 1 (secret rotation) |
| 🟠 High | 4 | 3 | 1 (PII encryption) |
| 🟡 Medium | 6 | 3 | 3 |
| 🔵 Low | 4 | 3 | 1 |

---

## 2. Scope & Methodology

### In scope
- **Backend** — `th.co.glr.hr` (auth, employee, attendance, ticket, profile-request, notification, dashboard, config, common)
- **Frontend** — `frontend/src` (React/Vite SPA, API client, feature pages, shared components)
- **Database** — Flyway migrations `V1`–`V11`, schema design, indexing, PII storage
- **Infrastructure / config** — `Dockerfile`, `render.yaml`, `vercel.json`, `docker-compose.yml`, `application.yml`, CI workflow
- **Agents** — Python ZKTeco SC700 attendance scripts under `agents/attendance/`
- **Process** — git history, commit hygiene, dependency/license posture

### Methodology
Static review of the full source tree; targeted searches for injection sinks, secrets, XSS,
deserialization, and authorization gaps; git-history inspection for leaked credentials; dependency
audit (`npm audit`, dependency tree); and verification of fixes by building and running the test
suite. Findings are mapped to the standard audit dimensions (security, code quality, performance,
reliability, testing, compliance, infrastructure, documentation).

### Severity definitions
| Severity | Meaning |
|---|---|
| 🔴 **Critical** | Directly exploitable for full compromise or mass data exposure; fix immediately. |
| 🟠 **High** | Serious weakness, exploitable under common conditions or major compliance gap. |
| 🟡 **Medium** | Meaningful weakness requiring specific conditions or defense-in-depth gap. |
| 🔵 **Low** | Minor hardening, hygiene, or quality issue. |

---

## 3. Findings Summary

| ID | Title | Severity | Dimension | Status | Tracking |
|---|---|---|---|---|---|
| C-1 | Auth bypass: password = sequential employee code, no hashing | 🔴 Critical | Security / AuthN | ✅ Fixed | GHSA-2fm4 · PR #33, #34 |
| C-2 | Live DB credentials in plaintext on disk | 🔴 Critical | Secrets | ⏳ Owner | GHSA-7gwj |
| H-1 | No login rate limiting / lockout | 🟠 High | Security | ✅ Fixed | GHSA-8m9r · PR #35 |
| H-2 | PDPA special-category PII stored in plaintext | 🟠 High | Compliance / Crypto | ⏳ Owner | GHSA-3fmg |
| H-3 | No dependency scanning (SCA) | 🟠 High | Supply chain | ✅ Fixed | #21 · PR #37 |
| H-4 | No audit logging of PII access | 🟠 High | Observability | ✅ Fixed | #21 · PR #40 |
| M-1 | Static shared attendance agent token | 🟡 Medium | Security | ⏳ Owner | #22 |
| M-2 | In-memory sessions (no persistence/scale) | 🟡 Medium | Reliability | ⏳ Owner | #23 |
| M-3 | Least-privilege DB role not applied (app = superuser) | 🟡 Medium | Infra / Least-privilege | ⏳ Owner | #25 |
| M-4 | DAT import has no size cap (DoS) | 🟡 Medium | Reliability | ✅ Fixed | #26 · PR #36 |
| M-5 | Missing security response headers | 🟡 Medium | Security | ✅ Fixed | #32 · PR #36 |
| M-6 | Frontend dependency vulnerabilities (vite/esbuild) | 🟡 Medium | Supply chain | ⏳ Owner/Dependabot | #31 |
| L-1 | Non-constant-time compare + missing negative auth tests | 🔵 Low | Security / Testing | ✅ Fixed | #24 · PR #33 |
| L-2 | Container runs as root; agent logs leak device password | 🔵 Low | Infra | ✅ Fixed | #27 · PR #36 |
| L-3 | Per-row item inserts (N+1-style writes) + no list pagination | 🔵 Low | Performance | ◑ Partial | #29 · PR #38 |
| L-4 | Test coverage gaps (no FE/integration tests) | 🔵 Low | Testing | ◑ Partial | #28 |

Legend: ✅ fixed in code · ◑ partially fixed · ⏳ pending owner action.

---

## 4. Detailed Findings

### 🔴 C-1 — Authentication bypass via predictable employee-code password
**Status:** ✅ Remediated (PR #33 backend, PR #34 frontend) · **Advisory:** GHSA-2fm4-74wf-99rh

**Location:** `auth/AuthService.java` (`passwordMatches`), `employee/EmployeeCodeGenerator.java`, `auth/EmployeeAuthRepository.java`

**Description.** Login succeeded when the submitted password equalled the employee code:
`password.equals(employee.employeeCode())`. No password hashing existed anywhere. Employee codes
are generated sequentially as `"GLR-" + nextval(sequence)` (`GLR-1`, `GLR-2`, …), and login is by
email. Therefore an attacker who knows or guesses a user's email could authenticate by trying small
sequential integers.

**Impact.** Full account takeover for any user, including HR-division accounts, which expose all
employee PII (national IDs, tax IDs, social-security numbers, salaries, bank accounts). Critically
amplified by the absence of rate limiting (H-1).

**Root cause.** Migration `V5__remove_app_user_uam.sql` dropped the original `hr.app_user`
(with a `password_hash` column), `hr.role`, `hr.permission`, `hr.user_role`, and
`hr.role_permission` tables — i.e. a proper hashed-credential + RBAC model existed and was removed,
leaving the email-+-employee-code scheme.

**Remediation delivered.**
- Stored **BCrypt** password hashes via `spring-security-crypto` (no full security starter, to
  preserve the existing manual session/CSRF layer).
- `V11` migration adds `password_hash` + `must_change_password` columns and indexes the email
  login lookup.
- The employee-code equality check was removed entirely; verification is now constant-time
  (`BCryptPasswordEncoder.matches`).
- New `POST /api/auth/change-password` (CSRF-protected, self-service) + a forced
  change-password gate in the SPA when `must_change_password` is set.
- A transitional backfill seeds a temporary hash so existing users are not locked out but cannot
  retain the predictable value.

**Residual risk.** During transition the temporary password equals the (now hashed) employee code
until each user changes it — pair with H-1 (rate limiting, shipped) and consider an HR-driven reset.

---

### 🔴 C-2 — Live production database credentials in plaintext on disk
**Status:** ⏳ Pending owner action · **Advisory:** GHSA-7gwj-fq26-rmfq

**Location:** `backend/.env.local`

**Description.** `backend/.env.local` contains the real Supabase host, username, and database
password in cleartext. The file **is** covered by `.gitignore` and was verified **not** present in
git history, so it has not leaked via git — but it sits in plaintext on disk in a public project,
one `git add -f` away from permanent exposure.

**Impact.** Anyone with read access to a developer machine/backup obtains full database access.
Because the secret has lived in cleartext, it should be treated as compromised.

**Remediation (owner).**
1. **Rotate the Supabase database password** (Supabase dashboard → Settings → Database).
2. Update `SPRING_DATASOURCE_PASSWORD` in Render (already `sync:false` in `render.yaml`).
3. Delete `backend/.env.local`; rely on `backend/.env.example` as the template.
4. Enable GitHub **secret scanning + push protection** (Settings → Code security).
5. Restrict Supabase network access where feasible.

---

### 🟠 H-1 — No rate limiting or account lockout on login
**Status:** ✅ Remediated (PR #35) · **Advisory:** GHSA-8m9r-9vhj-mr52

**Location:** `auth/AuthController.java` (`/api/auth/login`)

**Description.** The login endpoint had no throttling, lockout, or backoff. Combined with C-1 this
enabled scriptable mass account takeover.

**Remediation delivered.** A `LoginRateLimitFilter` applies **per-IP and per-account** sliding-window
limits with temporary lockout, returning `429 Too Many Requests` + `Retry-After` before the auth
logic runs; a successful login resets the counters. Configurable via `app.login-rate-limit.*`
(defaults: 5 failures/account, 20/IP, 15-minute window + lockout). **Caveat:** counters are
per-instance (in-memory) — fine for the single-instance Render deploy; a scaled deploy needs a
shared store (see M-2).

---

### 🟠 H-2 — PDPA special-category PII stored in plaintext (no encryption at rest)
**Status:** ⏳ Pending owner action · **Advisory:** GHSA-3fmg-vr2x-hx9j

**Location:** `hr_restricted.employee_pii` (migration `V1__employee_master_schema.sql`); read in `EmployeeRepository.baseSelect`

**Description.** The restricted PII table stores Thailand PDPA special-category data (s.26) and
national-ID data as plaintext columns: `national_id`, `tax_id`, `social_security_no`, `race`,
`religion`, `medical_conditions`, `distinguishing_marks`. The table's own comment states *"Encrypt
at rest (e.g. pgcrypto) and grant only to authorized HR roles"* — neither was implemented.

**Impact.** Any read access to the database (including via the over-privileged app role, M-3)
exposes highly sensitive personal data in cleartext, with PDPA implications.

**Remediation (owner decision required).** Encrypt special-category/national-ID columns at rest —
either pgcrypto column encryption or app-layer envelope encryption with a KMS-held key — plus a
one-time, maintenance-window migration to encrypt existing data. Implementation is ready to proceed
once a key-management approach and key are provided.

---

### 🟠 H-3 — No software-composition analysis (dependency scanning)
**Status:** ✅ Remediated (PR #37) · **Issue:** #21

**Location:** `.github/workflows/`

**Description.** CI ran only `mvnw clean verify` — no dependency scanning, no secret scanning.

**Remediation delivered.** Added `.github/dependabot.yml` (weekly Maven, npm, and GitHub-Actions
updates) and a `dependency-review` workflow that fails PRs introducing high-severity advisories.
**Owner step:** enable secret scanning + push protection in repo settings.

---

### 🟠 H-4 — No audit logging of sensitive PII access
**Status:** ✅ Remediated (PR #40) · **Issue:** #21

**Location:** `employee/EmployeeService.java`

**Description.** There was no record of who viewed or exported restricted PII / salary data.

**Remediation delivered.** HR detail views (the path returning restricted PII + salary history) now
emit a structured event on a dedicated `th.co.glr.hr.audit` logger recording actor id, actor email,
and target employee id. Self-service views are not logged. **Follow-up:** persist events to a
table/SIEM and extend to PII writes.

---

### 🟡 M-1 — Static shared attendance agent token
**Status:** ⏳ Pending owner decision · **Issue:** #22

**Location:** `attendance/AttendanceService.java` (`requireAgentToken`)

`POST /api/attendance/punch` is authenticated by a single static shared token across all devices,
with no rotation or per-device scoping (constant-time comparison is correctly used). Recommend
per-device tokens with rotation; implementation is ready pending a token-provisioning decision.

### 🟡 M-2 — In-memory HTTP sessions
**Status:** ⏳ Pending owner decision · **Issue:** #23

Sessions are stored in-memory; every restart/redeploy logs all users out and the app cannot scale
horizontally without sticky sessions. Recommend externalizing (Spring Session + Redis) or accepting
single-instance explicitly. Also applies to the H-1 rate-limit counters.

### 🟡 M-3 — Least-privilege DB role not applied
**Status:** ⏳ Pending owner action · **Issue:** #25

The application connects as the Supabase `postgres` (owner) role; the `hr` / `hr_restricted` schema
separation that was designed to isolate PII relies on RBAC grants that exist only as commented-out
examples in `V1`. The isolation is therefore cosmetic. Recommend a dedicated least-privilege
application role; the `GRANT` migration can be provided, but creating the role + rotating credentials
is a Supabase-admin action (ties into C-2).

### 🟡 M-4 — DAT import has no size cap (DoS)
**Status:** ✅ Remediated (PR #36) · **Issue:** #26

`POST /api/attendance/imports/dat` accepted an unbounded `content` string loaded fully into memory.
Added `@Size` caps (~5 MB content, 260-char filename) and a 100k-row ceiling in the parser →
oversized payloads are rejected with `413` instead of exhausting heap.

### 🟡 M-5 — Missing security response headers
**Status:** ✅ Remediated (PR #36) · **Issue:** #32

No CSP/HSTS/X-Frame-Options/X-Content-Type-Options on API or SPA responses. Added a backend
`SecurityHeadersFilter` (deny-all CSP for the JSON API, framing/sniffing protections, HSTS over
HTTPS) and a `headers` block in both `vercel.json` files (SPA-appropriate CSP + baseline headers).
**Note:** the SPA CSP should be smoke-tested against the deployed app.

### 🟡 M-6 — Frontend dependency vulnerabilities
**Status:** ⏳ Pending (Dependabot/owner) · **Issue:** #31

`npm audit` reports 1 high + 1 moderate: `esbuild ≤ 0.24.2` (dev-server SSRF, GHSA-67mh-4wv8-2f99)
via `vite ≤ 6.4.2`. Impact is limited to the dev server. The fix is a Vite major bump (5 → 7);
recommend letting Dependabot (now enabled, H-3) propose it so CI validates the upgrade.

---

### 🔵 L-1 — Non-constant-time compare & missing negative auth tests
**Status:** ✅ Remediated (PR #33) · **Issue:** #24

Resolved by the auth rework: verification now uses the constant-time `BCryptPasswordEncoder.matches`,
and `AuthServiceTest` was rewritten to assert the employee code is rejected, wrong passwords fail,
no-hash logins are rejected, and change-password validates current/reused credentials.

### 🔵 L-2 — Container runs as root; agent logs leak device password
**Status:** ✅ Remediated (PR #36) · **Issue:** #27

The Dockerfile runtime stage now runs as a non-root user (uid 10001), and the SC700 diagnostic
scripts no longer print the device communication password.

### 🔵 L-3 — Per-row item inserts & no list pagination
**Status:** ◑ Partially remediated (PR #38) · **Issue:** #29

Ticket-item inserts were converted from one statement per item to a single batched `INSERT`
(`reWriteBatchedInserts=true` is already set). **Pagination** of `GET /api/employees` /
`GET /api/tickets` remains — it is a coordinated backend + frontend/UX change (the employee list
filters client-side over the full set) and is tracked as a follow-up. *(Indexing was found to be
otherwise comprehensive; the one missing index — on `employee.email`, the login hot path — was added
in PR #33.)*

### 🔵 L-4 — Test coverage gaps
**Status:** ◑ Partially remediated · **Issue:** #28

Backend test count grew across the remediation PRs (auth, rate-limit, headers, audit logging). There
are still **no frontend tests** and no integration tests. A frontend test harness (Vitest +
Testing Library) plus controller/integration tests are recommended as a dedicated follow-up.

---

## 5. What's Working Well (Verified Clean)

These were explicitly checked and found sound:

- **SQL injection** — none. All queries use `NamedParameterJdbcTemplate` with bound parameters; dynamic filters append named placeholders only.
- **Authorization (business layer)** — consistent `SessionContext.requireUser` / `requireAnyRole` in controllers plus ownership checks in services (e.g. `TicketService`, `EmployeeService.get` scopes non-HR users to their own record). Profile-request approval is correctly gated to HR.
- **XSS** — no `dangerouslySetInnerHTML`, `innerHTML`, or `eval`; no auth tokens in `localStorage` (session-cookie auth only).
- **CSRF** — correct OWASP double-submit cookie implementation (`CsrfCookieFilter`), constant-time token compare.
- **Insecure deserialization** — none (no polymorphic Jackson typing, `ObjectInputStream`, or `XMLDecoder`).
- **Session cookie flags** — `HttpOnly`, `SameSite=Lax`, `Secure` (default true) correctly set.
- **CORS** — sanitized origins; rejects wildcard-with-credentials and non-absolute URLs.
- **Error handling** — global handler returns generic messages; no stack-trace/internal leakage.
- **License compatibility** — all dependencies permissive (MIT / Apache-2.0 / BSD / ISC); no copyleft conflict.
- **DB indexing** — comprehensive across employee FKs, ticket, attendance, and profile-request tables.
- **`.dockerignore`** — correctly excludes `.env*` and secrets from the image build.

---

## 6. Remediation Pull Requests

| PR | Title | Closes | Verification |
|---|---|---|---|
| #33 | `fix(auth)`: replace employee-code login with BCrypt-hashed credentials | GHSA-2fm4, #24 | `mvnw clean test` ✅ |
| #34 | `feat(auth)`: forced change-password gate + self-service UI | — | `vite build` ✅ |
| #35 | `fix(auth)`: add login rate limiting and lockout | GHSA-8m9r | `mvnw clean test` ✅ |
| #36 | `fix`: security hardening (headers, DAT cap, non-root, log redaction) | #32, #26, #27 | `mvnw clean test` ✅ |
| #37 | `chore(ci)`: Dependabot + dependency-review SCA gate | #21 (part) | config |
| #38 | `perf(tickets)`: batch ticket item inserts | #29 (part) | `mvnw clean test` ✅ |
| #39 | `fix(a11y)`: modal focus mgmt, toast live region, control labels | #30 | `vite build` ✅ |
| #40 | `feat(audit)`: log HR access to sensitive employee PII | #21 (part) | `mvnw clean test` ✅ |

**Suggested merge order:** #33 → #35 → #34 (auth + throttling before the UI that depends on the
endpoint), then #36, #37, #38, #39, #40 in any order.

---

## 7. Audit Dimension Coverage

| Dimension | Status | Notes |
|---|---|---|
| **Security — vulnerabilities** | ✅ Reviewed | Injection/XSS/CSRF/deserialization checked; findings C-1, M-4, M-5 |
| **Security — auth & authz** | ✅ Reviewed | C-1/H-1 fixed; authorization layer sound |
| **Security — secrets** | ✅ Reviewed | C-2 (rotation pending) |
| **Security — dependencies** | ✅ Reviewed | H-3 fixed; M-6 pending |
| **Security — encryption/transport** | ✅ Reviewed | H-2 (PII at rest) pending; TLS `sslmode=require` in place |
| **Code quality & maintainability** | ✅ Reviewed | Consistent, low duplication; minor decomposition opportunities noted |
| **Performance** | ✅ Reviewed | No N+1 reads; L-3 batch fixed, pagination pending; indexing comprehensive |
| **Reliability & error handling** | ✅ Reviewed | Clean error handler; M-2 (sessions), H-4 (audit) addressed/pending |
| **Testing** | ◑ Reviewed | Backend strengthened; FE/integration tests pending (L-4) |
| **Compliance & standards** | ✅ Reviewed | PDPA (H-2) key gap; licenses clean |
| **Infrastructure & config** | ✅ Reviewed | L-2 fixed; M-3 least-privilege pending |
| **Documentation & process** | ◑ Reviewed | This report; deep doc/onboarding review is the remaining follow-up |

---

## 8. Outstanding Items — Required Owner Actions

| # | Item | What's needed from the owner | Severity |
|---|---|---|---|
| 1 | **Rotate Supabase password** (C-2) | Rotate in Supabase, update Render env, delete `.env.local` | 🔴 Critical |
| 2 | **PII encryption at rest** (H-2) | Choose key management (env key vs KMS); schedule a migration window | 🟠 High |
| 3 | **Enable secret scanning + push protection** (H-3) | GitHub → Settings → Code security | 🟠 High |
| 4 | **Least-privilege DB role** (M-3) | Create app role in Supabase, rotate to non-superuser creds | 🟡 Medium |
| 5 | **Externalize sessions / rate-limit state** (M-2) | Provision Redis (or accept single-instance) | 🟡 Medium |
| 6 | **Per-device attendance tokens** (M-1) | Decide token provisioning/distribution model | 🟡 Medium |
| 7 | **Frontend dependency upgrade** (M-6) | Approve Vite major bump (or let Dependabot drive it) | 🟡 Medium |
| 8 | **Pagination & frontend test harness** (L-3/L-4) | Approve scope for the coordinated BE+FE work | 🔵 Low |

Each of items 2, 4, 5, 6, 7, 8 is ready to implement once the prerequisite is provided.

---

## 9. Appendix — Reference Index

**Private security advisories (GitHub repository advisories):**
- `GHSA-2fm4-74wf-99rh` — Authentication bypass (C-1)
- `GHSA-7gwj-fq26-rmfq` — Plaintext DB credentials on disk (C-2)
- `GHSA-8m9r-9vhj-mr52` — No login rate limiting (H-1)
- `GHSA-3fmg-vr2x-hx9j` — PDPA special-category PII in plaintext (H-2)

**Public issues:** #21 (SCA + audit logging), #22 (attendance token), #23 (sessions), #24 (constant-time + tests — resolved by #33), #25 (DB role), #26 (DAT cap), #27 (container/logs), #28 (tests), #29 (perf/pagination), #30 (a11y), #31 (frontend deps), #32 (security headers).

**Pull requests:** #33–#40 (see §6).

**Key files referenced:**
- `backend/src/main/java/th/co/glr/hr/auth/AuthService.java`
- `backend/src/main/java/th/co/glr/hr/employee/EmployeeCodeGenerator.java`
- `backend/src/main/java/th/co/glr/hr/employee/EmployeeRepository.java`
- `backend/src/main/resources/db/migration/V1__employee_master_schema.sql`
- `backend/src/main/resources/db/migration/V5__remove_app_user_uam.sql`
- `backend/.env.local` (secret — to be rotated/removed)

---

*Report generated by Claude Code. Severities and remediation reflect the state of the repository as
of 2026-06-29. Verify all fixes against the merged result before closing the corresponding advisories
and issues.*
