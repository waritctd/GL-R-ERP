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
- Core mobile flows are usable.
- Desktop-first admin flows are clearly labeled.
- Employee-code temporary password login is removed from production path.
- TanStack Query is introduced for core server state.
- Real frontend routing is introduced.
- Backend OpenAPI documentation exists.
- Backend Actuator health endpoints are safely configured.
- Backend integration tests can run reliably, preferably with Testcontainers.
- Documentation is cleaned and organized.
- A release tag is created.

---

## Repository Snapshot (verified 2026-07-07)
_This section is factual context for agents; the rules above govern behavior._

- **Default branch:** `main` (must stay deployable). No release tags exist yet.
- **Frontend:** React 18 + Vite 8. **No router library**, **no state/server-state library**. Routing is a ~30-branch ternary in `frontend/src/App.jsx`. Data fetching is manual per page. Single global stylesheet `frontend/src/styles.css` (~1891 lines, only 4 `@media` queries). Mock API (`frontend/src/api/mockApi.js`, ~1727 lines) is guarded from prod by a runtime check in `frontend/src/api/index.js`.
- **Backend:** Spring Boot 4.1 / Java 21. 19 REST controllers. Flyway migrations `V1`–`V32` (+ `migration-demo`). Session auth (Spring Session JDBC). `SecurityConfig` is `permitAll` at the filter layer; authorization is ~92 manual `SessionContext` checks + `@PreAuthorize` on only 2 controllers (payroll, commission).
- **Scope split (see 01_STABILIZATION_AUDIT.md):** HR-core = employees, attendance, leave, overtime, payroll, profile, auth, dashboards. Sales/CRM stack (tickets, quotation, deposit, commission, pricing/FX, catalog, customer, factory) is **out of v0.1.0** — freeze and do not expand.
- **CI:** `.github/workflows/` — `backend-ci.yml` (`mvnw clean verify` + Postgres service), `frontend-ci.yml` (lint + vitest + build + `npm audit`), `dependency-review.yml`. No coverage gate.
- **Not present yet:** OpenAPI/springdoc, Spring Actuator, Testcontainers (integration tests only run in CI via `TEST_DB_URL`; a plain local `mvnw verify` skips them), correlation-ID logging, global frontend ErrorBoundary, URL routing.
