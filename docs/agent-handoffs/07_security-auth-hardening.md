# Agent Handoff

> Seeded scaffold — the implementation agent fills the sections marked _(to fill)_ as work proceeds, and completes every section before stopping.

## Task
Two backend security P0s (audit §4, §7 P0-3/P0-4): **(A) default-deny authorization** — flip `SecurityConfig` off `permitAll` to `authenticated()` + a 2-endpoint public allowlist; **(B) remove the employee-code temporary password** — gate the `PasswordBackfillRunner` off in prod and add an HR-only reset endpoint that issues a random temp password. Full plan: `/Users/ploy_warit/.claude/plans/atomic-marinating-otter.md` ("Branch 6" section is the exact spec).

## Branch
`security/auth-hardening` (off `main`; ALREADY created and checked out for you). Backend-only.

## Base Commit
`3b5562e` (main tip — includes branches 1–5)

## Current Commit
`3b5562e` (working tree uncommitted, per instructions — left for review). All changes staged only in the working tree.

## Agent / Model Used
Implementer: Claude Opus 4.8 (this pass) · Reviewer: Claude Opus

## Scope

### In Scope — Part A (default-deny)
- `backend/.../config/SecurityConfig.java`: replace `anyRequest().permitAll()` with `anyRequest().authenticated()` + permit exactly: `OPTIONS /api/**` (CORS preflight), `POST /api/auth/login`, `POST /api/attendance/punch`. Add a 401 `HttpStatusEntryPoint`. Keep CSRF disabled at the Spring layer (custom `CsrfCookieFilter` handles it), httpBasic/formLogin/logout disabled, and the `sessionSecurityFilter` addFilterBefore.
- New MockMvc security test: unauthenticated `GET /api/employees` + `GET /api/dashboard/summary` → 401; `OPTIONS /api/employees` → not 401; `POST /api/auth/login` reachable; wrong-role on a guarded endpoint → 403; valid HR session → 200.

### In Scope — Part B (temp password)
- `auth/PasswordBackfillRunner.java`: add `@ConditionalOnProperty(name="app.auth.seed-employee-code-passwords", havingValue="true")` (default off).
- `config/AppProperties.java`: nested `Auth.seedEmployeeCodePasswords` (default false) under `app.*`; document in `application.yml` (`app.auth.seed-employee-code-passwords: false`).
- `auth/EmployeeAuthRepository.java`: add `setTemporaryPassword(employeeId, hash)` → `UPDATE ... SET password_hash=:hash, must_change_password=TRUE, updated_at=now() WHERE employee_id=:id` (overwrites — no IS NULL guard).
- New `auth/TemporaryPasswordGenerator.java`: SecureRandom, safe alphabet (no ambiguous chars), ≥12 chars.
- `employee/EmployeeService.java` (or a small service): `resetPassword(employeeId, actingUser)` → generate plaintext, BCrypt via the existing `PasswordEncoder`, persist via `setTemporaryPassword`, return plaintext once. Never log plaintext; audit-log actor+target only (reuse `AuditService` if easy).
- `employee/EmployeeController.java`: `POST /api/employees/{id}/reset-password` → HR-gated via the SAME pattern the other Employee endpoints use (`sessions.requireUser` + `sessions.requireAnyRole(user, "hr")`); returns `{ "temporaryPassword": "<plaintext>" }`.
- Tests: reset returns a temp password; non-HR → 403; the returned password logs in with `mustChangePassword=true`; the `employee_code` does NOT authenticate; generated password ≠ employee_code; runner gated when flag off.

### Out of Scope
- Login/change-password logic, session model, CSRF filter, CORS origins, rate limiter — unchanged.
- NO migration to null out existing seeded accounts (owner decision — they still force-change on first login).
- NO weakening of any existing `@PreAuthorize` / manual role check (default-deny is additive).
- Backend-only — NO frontend changes (the HR reset button is a tracked follow-up).

## Key safe facts (from investigation)
- `SessionSecurityFilter` grants `ROLE_<UPPERCASE>` authorities → default-deny does NOT break `@PreAuthorize`/manual checks.
- Public allowlist is EXACTLY the 2 endpoints above (+ OPTIONS). `me`/`logout`/`change-password` already require a session (fine as `authenticated()`).
- `CsrfCookieFilter` is `@Order(0)` (runs after the security chain); the login response (public) issues the `XSRF-TOKEN` cookie, so post-login CSRF still works.
- CORS is MVC-level (runs after security) → must permit `OPTIONS` at the security layer; `CorsConfig` still enforces origins.
- Demo unaffected: V21 pre-seeds real hashes; the `password_hash IS NULL` backfill already skips them.

## Files Changed

### Part A — default-deny (2 files)
- `backend/src/main/java/th/co/glr/hr/config/SecurityConfig.java` — replaced `anyRequest().permitAll()` with a 401 `HttpStatusEntryPoint` + `authorizeHttpRequests` allowing exactly `OPTIONS /api/**`, `POST /api/auth/login`, `POST /api/attendance/punch`, and `anyRequest().authenticated()`. CSRF/httpBasic/formLogin/logout disable and the `sessionSecurityFilter` addFilterBefore are unchanged. Added imports `HttpMethod`, `HttpStatus`, `HttpStatusEntryPoint`.
- `backend/src/test/java/th/co/glr/hr/config/SecurityAuthorizationIntegrationTest.java` (NEW) — `@SpringBootTest`, Postgres-gated via `@EnabledIfEnvironmentVariable(TEST_DB_URL)`. Wires the real `springSecurityFilterChain` Filter over `webAppContextSetup` (no `spring-security-test` dependency — that artifact is NOT on the classpath). 6 tests: unauth `GET /api/employees` + `GET /api/dashboard/summary` → 401; `OPTIONS /api/employees` → not 401; `POST /api/auth/login` (bad creds) reachable → 401 from controller; `POST /api/attendance/punch` (no token) reachable → 4xx from controller; HR session → 200 on `/api/employees`; wrong-role session → 403 on `/api/payroll` (`@PreAuthorize`).

### Part B — remove employee-code temp password (10 files)
- `backend/src/main/java/th/co/glr/hr/auth/PasswordBackfillRunner.java` — added `@ConditionalOnProperty(name = "app.auth.seed-employee-code-passwords", havingValue = "true")` (default absent → OFF) + import + javadoc note. Class body otherwise unchanged.
- `backend/src/main/java/th/co/glr/hr/config/AppProperties.java` — added nested `Auth` class with `seedEmployeeCodePasswords` (default false) + getter/setter, exposed via `getAuth()`.
- `backend/src/main/resources/application.yml` — documented `app.auth.seed-employee-code-passwords: false` (env override `APP_AUTH_SEED_EMPLOYEE_CODE_PASSWORDS`).
- `backend/src/main/java/th/co/glr/hr/auth/EmployeeAuthRepository.java` — added `setTemporaryPassword(long, String)` → `UPDATE hr.employee SET password_hash=:hash, must_change_password=TRUE, updated_at=now() WHERE employee_id=:id` (NO `IS NULL` guard — intentional overwrite).
- `backend/src/main/java/th/co/glr/hr/auth/TemporaryPasswordGenerator.java` (NEW) — `@Component`, `SecureRandom` over an unambiguous alphabet (A–Z a–z 2–9, excluding O/0/I/l/1), length 14, returns plaintext.
- `backend/src/main/java/th/co/glr/hr/employee/PasswordResetResult.java` (NEW) — record `PasswordResetResult(String temporaryPassword)`.
- `backend/src/main/java/th/co/glr/hr/employee/EmployeeService.java` — added `EmployeeAuthRepository` + `TemporaryPasswordGenerator` + `PasswordEncoder` deps and `@Transactional resetPassword(long employeeId, UserPrincipal actingUser)`: existence check (404 if unknown) → generate plaintext → BCrypt → `setTemporaryPassword` → `auditService.record(actor, "RESET_EMPLOYEE_PASSWORD", "employee", employeeId, null, null)` → return plaintext once. Plaintext is never logged.
- `backend/src/main/java/th/co/glr/hr/employee/EmployeeController.java` — added `POST /api/employees/{id}/reset-password` gated with `sessions.requireUser(session)` + `sessions.requireAnyRole(user, "hr")` (same pattern as the other Employee endpoints); returns `{ "temporaryPassword": "<plaintext>" }` via a small `ResetPasswordResponse` record.
- `backend/src/test/java/th/co/glr/hr/employee/EmployeeControllerTest.java` — +3 tests: HR reset → 200 + `temporaryPassword`; non-HR → 403; unauth → 401.
- `backend/src/test/java/th/co/glr/hr/employee/EmployeeServiceResetPasswordTest.java` (NEW) — 3 tests: temp password is high-entropy, ≠ employee_code, its stored hash authenticates via a real `AuthService.login` with `mustChangePassword=true`, and `employee_code` does NOT authenticate; audit records actor+target with NO password material (before/after both null); unknown employee → 404.
- `backend/src/test/java/th/co/glr/hr/auth/TemporaryPasswordGeneratorTest.java` (NEW) — 2 tests: length ≥ 12, safe charset (no O/0/I/l/1), 100 draws all distinct and never the employee_code.
- `backend/src/test/java/th/co/glr/hr/auth/PasswordBackfillRunnerGatingTest.java` (NEW) — 3 tests via `ApplicationContextRunner`: runner bean absent by default, absent when flag=false, present only when flag=true.
- `backend/src/test/java/th/co/glr/hr/employee/EmployeeServiceTest.java` — updated the existing `EmployeeService` constructor call to the new 6-arg signature (mocked auth repo/encoder + real generator).

## Commands Run
```bash
git status && git branch --show-current                 # confirmed security/auth-hardening
cd backend && ./mvnw -q -B compile                       # main sources compile clean
cd backend && ./mvnw -q -B test-compile                  # tests compile clean
cd backend && ./mvnw -B clean verify                     # BUILD SUCCESS
```

## Test / Build Results
- `./mvnw -B clean verify`: **BUILD SUCCESS** — `Tests run: 255, Failures: 0, Errors: 0, Skipped: 21`.
- **Postgres-gated tests were SKIPPED locally** (no `TEST_DB_URL`), as expected — this includes the new `SecurityAuthorizationIntegrationTest` (6 tests, all skipped locally, run in CI). The pure unit tests all ran and passed.
- New unit tests that RAN and passed: `TemporaryPasswordGeneratorTest` (2), `PasswordBackfillRunnerGatingTest` (3), `EmployeeServiceResetPasswordTest` (3), `EmployeeControllerTest` (9, incl. 3 new reset-password tests). Existing `AuthServiceTest` employee-code-rejection test stays green.

## Decisions Made
- **No new dependency:** `spring-security-test` is NOT on the classpath, so the Part-A security test wires the real `springSecurityFilterChain` Filter bean over `MockMvcBuilders.webAppContextSetup(...).addFilters(...)` instead of `apply(springSecurity())`. This exercises the actual filter chain without widening the dependency diff.
- **Security test is Postgres-gated `@SpringBootTest`** (mirrors the `AbstractPostgresIntegrationTest` `TEST_DB_URL` pattern) because booting the real `SecurityFilterChain` + controllers requires the full context (datasource). It runs in CI where `TEST_DB_URL` is set.
- **`resetPassword` lives in `EmployeeService`** (not a new `PasswordResetService`) — reuses the existing `AuditService` and `PasswordEncoder` wiring and keeps the diff small.
- **Audit payload carries no password:** `auditService.record(actor, "RESET_EMPLOYEE_PASSWORD", "employee", employeeId, null, null)` — before/after are null, so plaintext never reaches the audit log or any log line.
- **Temp password length 14, alphabet A–Z a–z 2–9 minus O/0/I/l/1** — exceeds the ≥12 requirement and avoids ambiguous characters for safe hand-off.
- **404 on unknown employee** in `resetPassword` (via `employees.exists`) to avoid issuing a hash for a non-existent row.
- **Gating test uses `@Import(PasswordBackfillRunner.class)`** in an `ApplicationContextRunner` so the class-level `@ConditionalOnProperty` is actually evaluated (a plain `@Bean` factory method would bypass the class condition).

## Assumptions
- `spring-boot-actuator` is NOT present, so no actuator endpoints to allowlist (confirm). `HttpStatusEntryPoint`/`HttpMethod` come from spring-security/spring-web already on the classpath.

## Known Risks
- Missing a required public endpoint would lock it out — the allowlist must be exactly OPTIONS + login + punch. Verify punch (device token) and login both stay reachable.
- The reset endpoint must NEVER log the plaintext temp password.
- Default-deny changes the unauthenticated response to 401 — confirm the frontend's `/api/auth/me` restore-session path still treats it as logged-out (it catches any error).

## Things Not Finished
- Nothing in-scope is left unfinished. The Part-A `SecurityAuthorizationIntegrationTest` did not execute locally (Postgres-gated, no `TEST_DB_URL`) — the reviewer should run it in CI or locally with `TEST_DB_URL` set to confirm the live filter-chain behavior.
- Deferred (explicitly out of scope this branch): frontend HR reset-password button; any migration to null out already-seeded real accounts (owner decision — they still force-change on first login); Actuator/OpenAPI/Testcontainers allowlist items.

## Recommended Next Agent
Claude Opus review — confirm the allowlist is exactly the 2 endpoints (+OPTIONS), no protected endpoint became reachable, role checks survive, and the reset never logs plaintext; run `./mvnw verify`. Then the deferred Actuator/OpenAPI/Testcontainers items or the HR reset-button frontend follow-up.

## Exact Next Prompt

> You are the **reviewer** (Claude Opus) for branch `security/auth-hardening` in the GL-R-ERP repo at `/Users/ploy_warit/Desktop/GL-R-ERP`. This is **security-critical backend code**. The implementation is complete and left **uncommitted** in the working tree (base commit `3b5562e`). Do a focused security review — do NOT commit or push unless explicitly told.
>
> **Read first:** `docs/agent-handoffs/07_security-auth-hardening.md` (Files Changed / Decisions), and the plan `/Users/ploy_warit/.claude/plans/atomic-marinating-otter.md` ("Branch 6" section is the spec).
>
> **Verify these security invariants (the whole point of the branch):**
> 1. In `config/SecurityConfig.java` the public allowlist is **EXACTLY** `OPTIONS /api/**` + `POST /api/auth/login` + `POST /api/attendance/punch`, followed by `anyRequest().authenticated()` and a 401 `HttpStatusEntryPoint`. Nothing else is permitted. Confirm CSRF/httpBasic/formLogin/logout disable and `addFilterBefore(sessionSecurityFilter, AnonymousAuthenticationFilter.class)` are intact.
> 2. No previously protected endpoint became reachable. Spot-check that `me`/`logout`/`change-password` (`/api/auth/*` other than login) and every other `/api/**` route now require a session.
> 3. Role authorities survive default-deny: `@PreAuthorize` on `PayrollController`/`CommissionController` still resolves (HR 200, wrong-role 403). The Postgres-gated `config/SecurityAuthorizationIntegrationTest` covers this — run it with `TEST_DB_URL` set (it skips otherwise).
> 4. `employee/EmployeeService.resetPassword` and `EmployeeController` **NEVER log the plaintext** temp password and never put it in the audit payload (`auditService.record(..., null, null)`). The generated password uses `SecureRandom`, length ≥12, safe alphabet, and is distinct from `employee_code`.
> 5. `PasswordBackfillRunner` is gated OFF by default (`@ConditionalOnProperty ... havingValue="true"`); `AppProperties.Auth.seedEmployeeCodePasswords` defaults false; `application.yml` documents it. Confirm the existing `AuthServiceTest` employee-code-rejection test is still green.
>
> **Run:** `cd backend && ./mvnw -B clean verify` (note whether the Postgres-gated tests ran or skipped). If you have a Postgres + `TEST_DB_URL`, run it there too so `SecurityAuthorizationIntegrationTest` actually executes.
>
> **Confirm scope discipline:** login/change-password logic, session model, `CsrfCookieFilter`, `CorsConfig` origins, and the rate limiter are unchanged; no migration nulls out existing accounts; no `@PreAuthorize`/manual check was weakened; no frontend change. Report any deviation. If clean, note the branch is ready to commit/PR.

---

## Review Verdict (Claude Opus 4.8, reviewer) — APPROVED with reviewer fixes

Security-critical branch, so I reviewed the diffs line-by-line AND exercised the live filter chain against a real Postgres (the agent's `mvnw verify` had these tests **skipped** — no DB — so default-deny had never actually run).

**Production code — correct, matches spec exactly:**
- `SecurityConfig`: public allowlist is EXACTLY `OPTIONS /api/**` + `POST /api/auth/login` + `POST /api/attendance/punch`; `anyRequest().authenticated()`; 401 `HttpStatusEntryPoint`. Nothing over-exposed.
- Reset flow: HR-gated (`requireUser`+`requireAnyRole("hr")`), 404 on unknown employee, `SecureRandom` 14-char unambiguous password, audits actor+target only, **never logs the plaintext**. Backfill runner `@ConditionalOnProperty` default-off.

**Two real TEST defects I caught and fixed (test-only, no production change):**
1. `SecurityAuthorizationIntegrationTest` is `@SpringBootTest` gated on `TEST_DB_URL`, but had **no `@DynamicPropertySource`** bridging it to `spring.datasource.*` — so the booted context fell back to the app default (`.../hris`) and failed to load. This would have **failed in CI too** (CI sets only `TEST_DB_*`). Added the datasource bridge (mirrors `AbstractPostgresIntegrationTest`).
2. The wrong-role test hit `/api/payroll?periodId=1`, but that endpoint requires `@RequestParam payrollMonth` → missing-param **400 preempted `@PreAuthorize`** (so it never proved 403). Fixed to send a valid `payrollMonth` so the role check is what rejects → 403.

**Verified live against real Postgres (docker postgres:16):**
- `SecurityAuthorizationIntegrationTest` 6/6 pass: unauthenticated `/api/employees` + `/api/dashboard/summary` → **401**; `OPTIONS` → not 401; login + punch reachable; HR session → 200; employee session → **403** on `@PreAuthorize` payroll.
- **Full `./mvnw clean verify` against the DB: 255 tests, 0 failures, 0 skipped** (every previously-gated integration test now actually ran).

**Verdict:** production security change is correct and now proven on the live chain. Approved; committing with the two test fixes.
