# Agent Handoff

> Seeded scaffold — the implementation agent fills the sections marked _(to fill)_ as work proceeds, and completes every section before stopping.

## Task
P1-3 backend observability floor: **(1)** a safely-exposed Spring Actuator **health** endpoint (reachable anonymously under default-deny; no detail leak); **(2)** a **correlation-ID MDC filter** (id on every log line + `X-Correlation-Id` response header); **(3)** enrich `ApiExceptionHandler.handleUnexpected` to log **correlationId + method + path + userId**. Backend-only. Full plan: `/Users/ploy_warit/.claude/plans/atomic-marinating-otter.md` ("Branch 8" section is the exact spec).

## Branch
`backend/observability-floor` (off `main`; ALREADY created and checked out for you)

## Base Commit
`4629264` (main tip — includes P0s + P1-1/2/4)

## Current Commit
`4629264` (implementation is uncommitted on top of this, per instructions — left for review)

## Agent / Model Used
Implementer: Claude Sonnet · Reviewer: Claude Opus

## Scope

### 1. Actuator health (safely exposed)
- `backend/pom.xml`: add `org.springframework.boot:spring-boot-starter-actuator`.
- `application.yml`: `management.endpoints.web.exposure.include: health` (ONLY health); `management.endpoint.health.show-details: never`; `management.endpoint.health.probes.enabled: true`.
- `config/SecurityConfig.java`: permit ONLY `GET /actuator/health` + `/actuator/health/**` (NOT `/actuator/**`). Everything else in the chain unchanged.

### 2. Correlation-ID MDC filter
- New `config/CorrelationIdFilter.java` — `@Component OncePerRequestFilter`, `@Order(Ordered.HIGHEST_PRECEDENCE)`. Read inbound `X-Correlation-Id` (sanitize: trim, cap length, strip control chars); else generate a UUID. Put in `MDC` key `correlationId`, echo on the `X-Correlation-Id` response header, `MDC.remove` in a `finally`.
- `application.yml`: `logging.pattern.level: "%5p [%X{correlationId:-}]"`.

### 3. Enrich unhandled-exception log
- `common/ApiExceptionHandler.java` — `handleUnexpected` only: inject `HttpServletRequest`, log `method= path= userId=` (userId from `request.getSession(false)` → `SessionContext.SESSION_USER_KEY` → `UserPrincipal.id()`, else `"anonymous"`). Response body unchanged (generic "Internal server error"). Other handlers unchanged.

### Out of Scope
- Only `/actuator/health` (+ liveness/readiness) permitted — NOT `/actuator/**`. No other actuator endpoint exposed.
- No change to error response bodies (no stack traces / PII leak). No auth/CSRF/CORS/session/rate-limiter changes. No tracing/metrics backend (Micrometer/Prometheus) — health + correlation id only. Don't log request bodies/headers/PII beyond user id.

## Tests
- New `config/CorrelationIdFilterTest.java` (unit, `MockFilterChain` like `CsrfCookieFilterTest`): generates id when no header + sets response header + MDC populated inside chain and cleared after; reuses inbound id; sanitizes garbage/oversized inbound value.
- New actuator health test (`@SpringBootTest`, Testcontainers-backed like `SecurityAuthorizationIntegrationTest`): unauth `GET /actuator/health` → 200 `UP`; details not exposed.
- Keep `ApiExceptionHandlerTest` green (500 response still generic).

## Files Changed
- `backend/pom.xml` — added `spring-boot-starter-actuator` dependency (no version; parent-managed).
- `backend/src/main/resources/application.yml` — added `management` section (`endpoints.web.exposure.include: health` only, `endpoint.health.show-details: never`, `endpoint.health.probes.enabled: true`); added `management.health.mail.enabled: false` (see Decisions); added `logging.pattern.level: "%5p [%X{correlationId:-}]"` to the existing `logging` block.
- `backend/src/main/java/th/co/glr/hr/config/SecurityConfig.java` — added one `requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()` line before `anyRequest().authenticated()`. Nothing else in the chain touched.
- `backend/src/main/java/th/co/glr/hr/config/CorrelationIdFilter.java` (new) — `@Component OncePerRequestFilter`, `@Order(Ordered.HIGHEST_PRECEDENCE)`. Reads/sanitizes inbound `X-Correlation-Id` (trim, strip control/non-printable chars, cap at 64 chars), else generates a UUID; puts it in MDC key `correlationId`; echoes it on the response header; `MDC.remove` in `finally`.
- `backend/src/main/java/th/co/glr/hr/common/ApiExceptionHandler.java` — `handleUnexpected` now takes an added `HttpServletRequest request` param and logs `method=... path=... userId=...`; added private `currentUserId(HttpServletRequest)` helper (reads `request.getSession(false)` → `SessionContext.SESSION_USER_KEY` → `UserPrincipal.id()`, else `"anonymous"`). Response body/other handlers unchanged.
- `backend/src/test/java/th/co/glr/hr/common/ApiExceptionHandlerTest.java` — updated the existing `handleUnexpected` call site for the new parameter (passes a `MockHttpServletRequest`); added one extra case exercising the no-session ("anonymous") branch.
- `backend/src/test/java/th/co/glr/hr/config/CorrelationIdFilterTest.java` (new) — unit tests mirroring `CsrfCookieFilterTest`'s `MockFilterChain` style: id generated when no header; MDC populated during the chain and cleared after `doFilter` returns; MDC cleared even when the chain throws; inbound id reused verbatim; oversized/garbage inbound value sanitized (bounded length, no control chars); blank inbound header still generates a fresh id.
- `backend/src/test/java/th/co/glr/hr/config/ActuatorHealthIntegrationTest.java` (new) — `@SpringBootTest` + `PostgresTestSupport`-gated, real `SecurityFilterChain` wired via MockMvc like `SecurityAuthorizationIntegrationTest`: anonymous `GET /actuator/health` → 200, `status` = `UP`, no `components`/`details` keys present; anonymous `GET /api/employees` still → 401 (proves the actuator permit didn't widen default-deny).
- `docs/agent-handoffs/09_backend-observability-floor.md` — this handoff, filled in.

## Commands Run
```bash
git status                       # confirmed branch backend/observability-floor, clean tree except the seeded handoff scaffold
cd backend && ./mvnw -B clean verify   # full build + test + jacoco check (run twice: first run surfaced the mail-health issue below, second run after the fix was green)
```

## Test / Build Results
- `./mvnw -B clean verify` (Docker available, Testcontainers Postgres used): **BUILD SUCCESS**. `Tests run: 282, Failures: 0, Errors: 0, Skipped: 0` (includes the 8 new tests: 6 in `CorrelationIdFilterTest`, 2 in `ActuatorHealthIntegrationTest`, plus 1 added case in `ApiExceptionHandlerTest`). Jacoco `check` passed: line coverage 51% (2,511/4,843 covered — comfortably over the 0.51 ratchet floor; ratchet unchanged).
- Health reachable anonymously (200 UP) + protected endpoint still 401: confirmed by `ActuatorHealthIntegrationTest` — `anonymousHealthCheckIsPermittedAndReportsUp` (200, `status=UP`, no `components`/`details` keys) and `protectedEndpointStillRequiresAuthentication` (`GET /api/employees` unauthenticated → 401), both over the real `springSecurityFilterChain` bean (same MockMvc-over-real-filter-chain pattern as `SecurityAuthorizationIntegrationTest`).
- Correlation id on log line + response header: confirmed by `CorrelationIdFilterTest` — response header is set to a generated UUID when absent, to the inbound value when present (sanitized if garbage/oversized), and MDC key `correlationId` is populated for the duration of `doFilter` and removed afterward (including on the chain throwing). Not separately re-verified via a live curl since the plan's unit test already exercises the exact header/MDC contract end-to-end at the filter level.

## Decisions Made
- **Excluded the mail health indicator** (`management.health.mail.enabled: false`) in `application.yml`. Root cause: Boot auto-configures a `MailHealthIndicator` once `spring-boot-starter-mail` is on the classpath (already a dependency, used for factory Import Request emails), and it opens a live SMTP probe to `spring.mail.host` (defaults to `smtp.gmail.com`) on every health check. In this sandboxed/offline build environment that probe fails, which flipped the aggregate `/actuator/health` status to `DOWN`/503 even though the app and DB were healthy — caught by the new integration test on the first `mvnw verify` run. Excluding it is minimal and in the spirit of the plan (health should reflect this app's own liveness/DB readiness, not a third-party SMTP host's reachability); the DB health indicator (the meaningful signal) still rolls into the aggregate. This is a one-line `application.yml` addition, not present in the original plan text, so flagging it explicitly for reviewer attention — it is a config-only change, not a business-logic or security change.
- Kept `handleUnexpected`'s `currentUserId` as a `private static` helper on `ApiExceptionHandler` (matches the plan's `currentUserId(HttpServletRequest)` shape) rather than delegating to `SessionContext.requireUser`, since that method throws on a missing user — the exception handler needs a non-throwing "anonymous" fallback instead.
- `CorrelationIdFilter` sanitization strips everything outside printable ASCII (0x20–0x7E) rather than a stricter allowlist (e.g. alnum+hyphen only) — simplest correct rule that satisfies "log-safe, no control chars, bounded length" without being more restrictive than the plan asked for; inbound HTTP header values are ASCII-only anyway per RFC 7230, so this doesn't reject any well-formed value a real client would send.
- Added a `mdcIsClearedEvenWhenTheChainThrows` test beyond the plan's minimum ask — cheap to add and directly protects the "critical for pooled threads" risk called out in the plan/handoff scaffold.

## Assumptions
- `spring-boot-starter-actuator` version is managed by the Boot 4.1 parent. `logging.pattern.level` is the documented way to inject MDC without a `logback-spring.xml`.
- Excluding the mail health indicator (see Decisions) is an acceptable, in-scope config tweak rather than a deviation — it doesn't touch auth/CSRF/CORS/session/business logic, and it's needed for `/actuator/health` to be a truthful, deployment-safe liveness signal instead of one hostage to an unrelated third-party SMTP host.
- The correlation id / MDC contract is considered proven by the new unit test (header + MDC populated/cleared) without an additional manual curl or a live log-line grep, since `CorrelationIdFilterTest` exercises the exact filter contract in isolation and `application.yml`'s `logging.pattern.level` is a one-line, low-risk Boot-documented knob.

## Known Risks
- Security-adjacent: permitting `/actuator/health` must NOT widen to `/actuator/**`. Verified via `ActuatorHealthIntegrationTest` (health 200) + `SecurityAuthorizationIntegrationTest`'s existing 401 coverage; reviewer should still spot-check `/actuator/beans`, `/actuator/env`, `/actuator/mappings` → expect 401 (not permitted by `SecurityConfig`, and not web-exposed per `management.endpoints.web.exposure.include: health` either way — defense in depth).
- MDC must be cleared in `finally` (pooled threads would otherwise leak the id across requests) — covered by a dedicated test, including the chain-throws case.
- Coverage ratchet (0.51) must still pass — confirmed at 51% (unchanged floor, not raised).
- The `management.health.mail.enabled: false` addition (see Decisions) was not in the original plan text; it's config-only and additive-safe, but the reviewer should confirm it's an acceptable scope addition rather than something that should have been raised back to the planner first.

## Things Not Finished
None — all three scope items (actuator health, correlation-ID filter, enriched exception log) are implemented and tested; `./mvnw -B clean verify` is green with the coverage ratchet met. Nothing was deferred.

## Recommended Next Agent
Claude Opus review — confirm only `/actuator/health` is permitted (spot-check `/actuator/beans`/`/actuator/env` → 401), no PII in logs, MDC cleared, `./mvnw verify` green + health reachable anonymously while protected endpoints stay 401. Then P1-5 (frontend ErrorBoundary) — the last P1.

## Exact Next Prompt

```
You are the reviewer agent (Claude Opus) for branch `backend/observability-floor` in
GL-R-ERP at /Users/ploy_warit/Desktop/GL-R-ERP. Do NOT implement beyond tiny, safe
fixes (typos/obvious one-liners) — anything larger goes back to an implementation
agent. Backend-only, uncommitted diff on this branch.

Read first: docs/agent-handoffs/00_MASTER_CONTEXT.md, then this file
(docs/agent-handoffs/09_backend-observability-floor.md) in full, then the plan at
/Users/ploy_warit/.claude/plans/atomic-marinating-otter.md ("Branch 8" section).

Review focus (P1-3 backend observability floor):
1. Security: confirm `backend/src/main/java/th/co/glr/hr/config/SecurityConfig.java`
   permits ONLY `GET /actuator/health` + `/actuator/health/**`, and NOT `/actuator/**`
   generally. Spot-check that `/actuator/beans`, `/actuator/env`, `/actuator/mappings`
   would be unreachable (401, and/or 404 since only `health` is web-exposed per
   `application.yml`'s `management.endpoints.web.exposure.include: health`). Confirm no
   other line in the security filter chain changed.
2. Confirm `CorrelationIdFilter`
   (backend/src/main/java/th/co/glr/hr/config/CorrelationIdFilter.java) clears MDC in a
   `finally`, sanitizes inbound header values (bounded length, no control chars), and
   is `@Order(Ordered.HIGHEST_PRECEDENCE)`.
3. Confirm `ApiExceptionHandler.handleUnexpected`
   (backend/src/main/java/th/co/glr/hr/common/ApiExceptionHandler.java) logs
   method/path/userId only (no PII beyond a numeric user id, no stack trace or
   internal detail leaking into the HTTP response body — response body must stay the
   generic "Internal server error").
4. Review the "Decisions Made" section of this handoff, in particular the
   `management.health.mail.enabled: false` addition in
   backend/src/main/resources/application.yml — it was NOT in the original plan text.
   It was added because the mail health indicator (auto-configured once
   spring-boot-starter-mail is on the classpath) opens a live SMTP probe to
   spring.mail.host on every check, which flipped /actuator/health to DOWN/503 in the
   sandboxed build environment even though the app and DB were healthy. Confirm this
   reasoning holds and the fix is proportionate (config-only, no business-logic or
   security change), or flag if you think a different approach is warranted.
5. Run `cd backend && ./mvnw -B clean verify` yourself (Docker required) and confirm:
   BUILD SUCCESS, all tests green (282 total including the new
   CorrelationIdFilterTest, ActuatorHealthIntegrationTest, and the updated
   ApiExceptionHandlerTest), and the Jacoco coverage check still passes at >= 0.51.
6. Confirm via ActuatorHealthIntegrationTest (or your own MockMvc/curl check) that
   anonymous GET /actuator/health -> 200 UP with no component/detail leak, while
   GET /api/employees unauthenticated still -> 401.

If everything checks out, update this handoff's "Recommended Next Agent" /
"Exact Next Prompt" to hand this off toward a merge decision (or write the next
prompt for P1-5, frontend ErrorBoundary — the last P1 item per
00_MASTER_CONTEXT.md). Do not commit or push unless the user asks you to.
```

---

## Review Verdict (Claude Opus 4.8, reviewer) — APPROVED

Reviewed the diff and independently re-ran `./mvnw clean verify`. Approved; committed + PR'd.

- **Security (the key lens):** `SecurityConfig` adds exactly ONE matcher — `GET /actuator/health` + `/actuator/health/**`, permitAll — **not** `/actuator/**`. Combined with `exposure.include: health` (no other endpoint web-exposed) and default-deny, any other actuator path (`/actuator/beans`, `/actuator/env`) is both unexposed AND 401 for anonymous callers. `ActuatorHealthIntegrationTest` proves, over the real `springSecurityFilterChain`, that anonymous `GET /actuator/health` → 200 `UP` (no `components`/`details`) while anonymous `GET /api/employees` → 401 — the permit did not widen default-deny.
- **Correlation filter:** HIGHEST_PRECEDENCE `OncePerRequestFilter`; sanitizes the inbound `X-Correlation-Id` (trims, strips control chars incl. newlines → log-injection defense, caps at 64) else UUID; `MDC.remove` in `finally` (verified it clears even when the chain throws) — correct thread-pool hygiene.
- **Exception log:** `handleUnexpected` now logs `method/path/userId`; `currentUserId` is defensive (null/absent session → `"anonymous"`, never throws, only the numeric id — no PII). Response body unchanged (generic, no stack trace leak).
- **Mail-health deviation — APPROVED (a genuine improvement, not just a sandbox fix):** the auto-configured `MailHealthIndicator` opens a live SMTP connection to `smtp.gmail.com` on every health check, which would flap `/actuator/health` to DOWN whenever Gmail SMTP hiccups — coupling this app's liveness to a third-party host it only uses for async notifications. `management.health.mail.enabled: false` makes health reflect the app's own readiness (DB). Config-only, well-commented.
- **Gates (re-run by me):** `./mvnw -B clean verify` (Docker, no `TEST_DB_URL`) → **282 run / 0 skipped**, "All coverage checks have been met" (0.51 ratchet), BUILD SUCCESS.

**Next:** P1-5 (frontend `ErrorBoundary`) — the last P1.
