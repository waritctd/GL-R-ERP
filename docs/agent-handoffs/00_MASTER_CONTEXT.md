# GL-R-ERP Agent Master Context

## Product Identity
This repository is currently a GL&R HR Portal moving toward an ERP platform. Do not call it a complete ERP yet.

## Current Priority
Stabilization is complete (`v0.1.0` → `v0.3.0` tagged on `main`). Current priority is closing the
remaining HR-core feature gaps against the product spec, one branch per gap, in the priority order
below. The sales/CRM stack stays frozen and flag-hidden until a separate product decision un-freezes it.

## HR-Core Gap-Closure Roadmap (priority order)
1. `feat/payslip-pdf` — per-employee payslip PDF (reuse `PdfDocumentWriter` + Sarabun, mirror `DepositNoticeRenderer`)
2. `feat/payslip-email` — email the payslip PDF to each employee (depends on 1)
3. `feat/payroll-accounting-summary` — auto-email a payroll period summary to accounting (depends on 1)
4. `feat/payroll-kbank-export` — replace the generic bank-export text format with KBank's real layout (blocked: need KBank's file spec from GL&R finance)
5. `feat/payroll-unpaid-prefill` — derive `unpaidLeaveDays` from `attendance_daily` instead of manual entry only
6. `feat/announcements` — company notices/announcements feature (table + admin CRUD + dashboard surface)
7. `feat/audit-log-search` — searchable `GET /api/audit-log` + HR page over the existing immutable audit table
8. `feat/user-welcome-deactivate` — welcome email on account creation + deactivate/reactivate endpoint
9. `feat/ai-policy-chatbot` — Gemini-backed policy Q&A chatbot (blocked: need Gemini API key + curated policy corpus from HR)

Sales/ERP completion (catalog PDF import, invoice/import-request PDFs, deal-close→commission trigger,
NAS archival, ticket kanban) is a separate initiative after the HR-core list above, gated on a decision
to un-freeze the sales/CRM stack.

## Stabilization Target (historical, complete)
v0.1.0 Stable HR Portal Foundation — achieved; see Definition of Done below.

## P0 Priorities
1. Mobile usability
2. Auth/password hardening
3. Frontend server-state cleanup
4. Real frontend routing
5. Backend testing and observability

## Non-Negotiable Rules
- New HR-core features are allowed only per the Gap-Closure Roadmap above, one gap per branch. The sales/CRM stack stays frozen — do not expand it.
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

## Definition of Done for v0.1.0 (achieved)
_All items complete as of 2026-07-07 (main `634b19c`)._
- [x] Core mobile flows are usable. _(#116/#118 mobile shell + card reflow)_
- [x] Desktop-first admin flows are clearly labeled. _(#130 `DesktopOnlyNotice` on payroll/attendance)_
- [x] Employee-code temporary password login is removed from production path. _(#122)_
- [x] TanStack Query is introduced for core server state. _(#119/#120)_
- [x] Real frontend routing is introduced. _(#121 react-router v7)_
- [x] Backend OpenAPI documentation exists. _(#127 springdoc, auth-gated under default-deny)_
- [x] Backend Actuator health endpoints are safely configured. _(#125)_
- [x] Backend integration tests can run reliably, preferably with Testcontainers. _(#124)_
- [x] Documentation is cleaned and organized. _(#128 docs index + archive)_
- [x] A release tag is created. _(`v0.1.0` on `9eaf486`, 2026-07-07)_

**Beyond the DoD, also shipped for the release:** default-deny authorization (#122), audit-log
coverage extended to leave/overtime/commission/payroll (#129), and the frozen sales/CRM stack
flag-hidden so v0.1.0 shipped cleanly HR-core (`VITE_ENABLE_SALES=false`, #130).

## Releases since v0.1.0
- `v0.2.0` (`f655b77`, 2026-07-09) — theming/frozen-stack detoken pass.
- `v0.3.0` (`46311ec`, 2026-07-14) — sales post-quotation dual-track flow (#163, still flag-hidden),
  production-readiness hardening, demo Flyway pinning fixes, post-SIT NFT round 1.

---

## Repository Snapshot (updated 2026-07-14, tag `v0.3.0`)
_This section is factual context for agents; the rules above govern behavior._

- **Default branch:** `main` (must stay deployable). Current release tag: `v0.3.0`.
- **Frontend:** React 18 + Vite 8. **URL routing via react-router v7** (`App.jsx` `<Routes>`), **TanStack Query** for core server state (`useHrData`), global `ErrorBoundary`, `useIsMobile` + mobile card reflow. Single global stylesheet `frontend/src/styles.css`. Mock API guarded from prod by a runtime check in `frontend/src/api/index.js`. Frozen sales pages are flag-hidden (`VITE_ENABLE_SALES`, default false).
- **Backend:** Spring Boot 4.1 / Java 21. Flyway migrations `V1`–`V41` (+ `migration-demo`). Session auth (Spring Session JDBC). `SecurityConfig` is **default-deny** (`anyRequest().authenticated()` + a small explicit allowlist). OpenAPI/springdoc served auth-gated at `/v3/api-docs`. Audit logging (`AuditService`) covers employee/profile/attachment/leave/overtime/commission/payroll mutations.
- **Scope split:** HR-core = employees, attendance, leave, overtime, payroll, profile, auth, dashboards — now open for the gap-closure roadmap above. Sales/CRM stack (tickets, quotation, deposit, commission, pricing/FX, catalog, customer, factory) stays frozen and flag-hidden from nav/routes.
- **CI:** `.github/workflows/` — `backend-ci.yml` (`mvnw clean verify` on **Testcontainers**), `frontend-ci.yml` (lint + vitest + build + `npm audit`), `dependency-review.yml`. Backend has a Jacoco line-coverage ratchet (floor 0.51).
- **Observability:** Spring Actuator health (`/actuator/health`, safely exposed), correlation-ID MDC filter, enriched `ApiExceptionHandler`.
