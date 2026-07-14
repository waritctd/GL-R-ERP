# CLAUDE.md — Agent Operating Rules for GL-R-ERP

This repository is a GL&R **HR Portal** moving toward an ERP platform. It is not a complete ERP yet. Stabilization (v0.1.0–v0.3.0) is complete; the repo is now in a **post-stabilization feature phase** closing the remaining HR-core gaps against the product spec. Read this before doing anything.

## Start every session by reading context
1. **Always read `docs/agent-handoffs/00_MASTER_CONTEXT.md` before starting.** It holds product identity, current priorities, and the non-negotiable rules.
2. **Always read the latest relevant handoff file** in `docs/agent-handoffs/` before working on a task (the per-branch `NN_<branch>.md`).
3. **Always run `git status` before making any changes** and confirm which branch you are on.

## Feature-phase rules — non-negotiable
- **New HR-core features may be added**, one gap per branch, per the prioritized gap-closure roadmap (payslip PDF/email, accounting summary, KBank export, attendance→payroll prefill, announcements, audit-log search, user welcome/deactivate, AI policy chatbot).
- The **sales/CRM stack remains frozen** (tickets, quotation, deposit, commission, pricing/FX, catalog, customer, factory, ceo-settings) and flag-hidden (`VITE_ENABLE_SALES=false`). Do not expand it as part of HR-core gap work — un-freezing sales is a separate product decision.
- **Do not rewrite the whole app.** Make incremental, targeted changes.
- **Do not change already-shipped business logic** (payroll/tax/commission/pricing math, etc.) unless explicitly requested. New features may add new calculations but must not alter existing formulas without explicit instruction.
- **Keep changes small and reviewable.** Prefer the smallest diff that satisfies the task.

## Branch & agent discipline
- **One branch per task.** `main` must stay deployable; branch off `main`, open a PR, merge only after review.
- **One implementation agent per branch.** Do not let two agents (e.g. Claude and Codex) edit the same branch at the same time.
- **Reviewer agents do not implement** — except tiny, safe fixes (typos, obvious one-liners). Anything larger goes back to an implementation branch.

## Before you finish an implementation task
- **Always run the relevant tests/builds** and record the results:
  - Frontend: `cd frontend && npm run lint && npm test && npm run build`
  - Backend: `cd backend && ./mvnw -B clean verify` (integration tests need a Postgres + `TEST_DB_URL`; note if they were skipped)
- **Update the relevant handoff file before ending.** Fill in every section, and always list:
  1. **Files changed** (path + what changed)
  2. **Commands run**
  3. **Tests / build results** (pass/fail/not run)
  4. **Known risks**
  5. **The exact next prompt** for the next agent

## Handoff system
`docs/agent-handoffs/` is the shared memory between Claude, Codex, and reviewer agents. See `docs/agent-handoffs/README.md` for the process and the handoff template. Create a new `NN_<branch-name>.md` from the template when you start a branch.

## Repo quick facts (verified 2026-07-14, tag `v0.3.0` on `main`)
- **Frontend:** React 18 + Vite 8. URL routing via react-router v7 (`App.jsx` `<Routes>`); TanStack Query for core server state; single global `frontend/src/styles.css`. Frozen sales pages are flag-hidden. Tests: Vitest. Lint: ESLint + jsx-a11y.
- **Backend:** Spring Boot 4.1 / Java 21. Session auth (Spring Session JDBC). `SecurityConfig` is **default-deny** (`anyRequest().authenticated()` + a small explicit allowlist). Flyway migrations `V1`–`V41` (+ `migration-demo`). Actuator health + OpenAPI/springdoc exist (auth-gated). Integration tests run on Testcontainers in CI; a plain local `mvnw verify` still needs `TEST_DB_URL` for DB-backed tests.
- **CI:** `.github/workflows/` — `backend-ci.yml`, `frontend-ci.yml`, `dependency-review.yml`.
- **Deploy:** `render.yaml` (backend), `vercel.json` (frontend), `docker-compose*.yml` (local). The Render demo is a showcase, not real production.

## Commit / PR conventions
- Conventional-commit style prefixes (`feat:`, `fix:`, `chore:`, `refactor:`, `security:`, `docs:`, `test:`).
- One focused branch → one PR → review → merge. Do not commit or push unless asked.
