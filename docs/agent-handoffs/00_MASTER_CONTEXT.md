# GL-R-ERP Agent Master Context

## Product Identity
This repository is currently a GL&R HR Portal moving toward an ERP platform. Do not call it a complete ERP yet.

## Current Priority
The v0.1.0 stabilization freeze is **complete and historical** (see `01_STABILIZATION_AUDIT.md`
for that plan, and `HANDOFF_LOG.md` for every branch that delivered it). The project is now on
its ongoing release line:
- **Sales/CRM is UNFROZEN** (2026-07-16) and part of the current release — repair, refactor, and
  the approved deal/pricing-request redesign are all in scope. See `CLAUDE.md` for the exact
  scope rules and `../sales-workflow.md` for the current-state workflow.
- **Active areas:** payroll depth (statutory exports, withholding-tax override, special-pay
  carry-forward, live refresh), role-scoped views, and on-device attendance hardware (dual ZKTeco
  scanners, WFH "mark present", pyzk transport).
- **Direction:** the final deliverable target is an **on-prem** deployment.

## Hard Guardrails (never relax)
1. **Business logic stays untouchable** — payroll/tax/SSO/commission math — except the explicitly
   approved sales pricing/deal-workflow redesign. See `CLAUDE.md`.
2. **Permission changes ship real-DB integration-test evidence** through the real Java service —
   never inferred from `mockApi.js`.
3. **Schema changes are forward-only `Vnnn`** — never edit an applied migration in place.

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

## v0.1.0 Definition of Done — SHIPPED (historical)
v0.1.0 (the stabilization milestone) is complete: mobile flows, default-deny auth + temp-password
removal, TanStack Query, react-router v7, OpenAPI, Actuator health, Testcontainers ITs, and the
docs cleanup all merged (PRs ~#116–#130). The project has since moved well past it into the
current sales/CRM + payroll + attendance release line — see `HANDOFF_LOG.md`.

---

## Repository Snapshot (updated 2026-07-25)
_Factual context for agents; the guardrails above govern behavior. For per-branch detail see
`HANDOFF_LOG.md`; for the original pre-stabilization baseline see `01_STABILIZATION_AUDIT.md`._

- **Default branch:** `main` (must stay deployable).
- **Frontend:** React 18 + Vite 8. URL routing via react-router v7 (`App.jsx`), TanStack Query for
  server state, RHF + zod forms, TanStack Table, global `ErrorBoundary`. Styling is **Tailwind-first**
  (Tailwind 4 via `@tailwindcss/vite`, tokens in `src/index.css`) with the legacy `src/styles.css`
  (~2.2k lines) being progressively retired — do not add new page CSS. Sales is **enabled by default**:
  `SALES_ENABLED = VITE_ENABLE_SALES !== 'false'` (`frontend/src/app/features.js`) — an off-switch,
  not an opt-in. Mock API (`VITE_USE_MOCKS=true`) is the default dev/QA surface; its authz only
  approximates the Java services (verify permissions against the backend — see `CLAUDE.md`).
- **Backend:** Spring Boot 4.1 / Java 21. Flyway migrations run to **V89** (+ `migration-demo` seed,
  `migration-uat` V900+ seed). Session auth. `SecurityConfig` is `permitAll` with manual per-endpoint
  checks. OpenAPI at `/v3/api-docs`. `AuditService` covers the mutating surfaces.
- **Scope:** HR-core (employees, attendance, leave, overtime, payroll, profile, auth, dashboards)
  **and** the unfrozen sales/CRM stack (tickets, deals, pricing-request, quotation, deposit,
  commission, catalog, customer, factory) are both in the current release line.
- **CI:** `.github/workflows/` — `backend-ci.yml` (`mvnw clean verify`, Postgres via `TEST_DB_URL`
  or Testcontainers), `frontend-ci.yml` (lint + vitest + build + `npm audit`), `dependency-review.yml`.
- **Deploy:** `render.yaml` (backend), `vercel.json` (frontend), `docker-compose*.yml` (local). The
  Render demo is a showcase, not real production. Final target is an on-prem deployment.
