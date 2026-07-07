# GL-R-ERP Agent Master Context

## Product Identity
This repository is currently a GL&R HR Portal moving toward an ERP platform. Do not call it a complete ERP yet.

## Current Priority
Freeze new feature development. Stabilize the foundation first.

## Stabilization Target
v0.1.0 Stable HR Portal Foundation

## P0 Priorities
1. Mobile usability
2. Auth/password hardening
3. Frontend server-state cleanup
4. Real frontend routing
5. Backend testing and observability

## Non-Negotiable Rules
- Do not add new ERP features.
- Do not rewrite the app.
- Do not change business logic unless explicitly requested.
- Keep changes small and reviewable.
- One branch per task.
- One implementation agent per branch.
- Reviewer agents should not implement except tiny safe fixes.
- Update the relevant handoff file before ending.
- Always run relevant tests/builds before finishing implementation tasks.

## Branch Discipline
- `main` must stay deployable.
- Each task uses one focused branch.
- Do not let Claude and Codex edit the same branch at the same time.
- Merge only after review.

## Definition of Done for v0.1.0
_All items complete as of 2026-07-07 (main `634b19c`). v0.1.0 is ready to tag._
- [x] Core mobile flows are usable. _(#116/#118 mobile shell + card reflow)_
- [x] Desktop-first admin flows are clearly labeled. _(#130 `DesktopOnlyNotice` on payroll/attendance)_
- [x] Employee-code temporary password login is removed from production path. _(#122)_
- [x] TanStack Query is introduced for core server state. _(#119/#120)_
- [x] Real frontend routing is introduced. _(#121 react-router v7)_
- [x] Backend OpenAPI documentation exists. _(#127 springdoc, auth-gated under default-deny)_
- [x] Backend Actuator health endpoints are safely configured. _(#125)_
- [x] Backend integration tests can run reliably, preferably with Testcontainers. _(#124)_
- [x] Documentation is cleaned and organized. _(#128 docs index + archive)_
- [ ] A release tag is created. _(final step — tag `v0.1.0` on `634b19c` or later)_

**Beyond the DoD, also shipped for the release:** default-deny authorization (#122), audit-log
coverage extended to leave/overtime/commission/payroll (#129), and the frozen sales/CRM stack
flag-hidden so v0.1.0 ships cleanly HR-core (`VITE_ENABLE_SALES=false`, #130).

---

## Repository Snapshot (updated 2026-07-07, post-stabilization)
_This section is factual context for agents; the rules above govern behavior. The stabilization work
(P0–P2) is merged — the bullets below reflect the current state, not the pre-stabilization audit
snapshot in `01_STABILIZATION_AUDIT.md`._

- **Default branch:** `main` (must stay deployable). `v0.1.0` is the first release tag (pending).
- **Frontend:** React 18 + Vite 8. **URL routing via react-router v7** (`App.jsx` `<Routes>`), **TanStack Query** for core server state (`useHrData`), global `ErrorBoundary`, `useIsMobile` + mobile card reflow. Single global stylesheet `frontend/src/styles.css`. Mock API guarded from prod by a runtime check in `frontend/src/api/index.js`. Frozen sales pages are flag-hidden (`VITE_ENABLE_SALES`, default false).
- **Backend:** Spring Boot 4.1 / Java 21. Flyway migrations `V1`–`V32` (+ `migration-demo`). Session auth (Spring Session JDBC). `SecurityConfig` is **default-deny** (`anyRequest().authenticated()` + a small explicit allowlist). OpenAPI/springdoc served auth-gated at `/v3/api-docs`. Audit logging (`AuditService`) covers employee/profile/attachment/leave/overtime/commission/payroll mutations.
- **Scope split (see 01_STABILIZATION_AUDIT.md):** HR-core = employees, attendance, leave, overtime, payroll, profile, auth, dashboards. Sales/CRM stack (tickets, quotation, deposit, commission, pricing/FX, catalog, customer, factory) is **out of v0.1.0** — frozen, and flag-hidden from nav/routes.
- **CI:** `.github/workflows/` — `backend-ci.yml` (`mvnw clean verify` on **Testcontainers**), `frontend-ci.yml` (lint + vitest + build + `npm audit`), `dependency-review.yml`. Backend has a Jacoco line-coverage ratchet (floor 0.51).
- **Observability:** Spring Actuator health (`/actuator/health`, safely exposed), correlation-ID MDC filter, enriched `ApiExceptionHandler`.
