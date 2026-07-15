# CLAUDE.md — Agent Operating Rules for GL-R-ERP

This repository is in a **stabilization phase** moving a GL&R **HR Portal** toward an ERP platform. It is not a complete ERP yet. Read this before doing anything.

## Start every session by reading context
1. **Always read `docs/agent-handoffs/00_MASTER_CONTEXT.md` before starting.** It holds product identity, current priorities, the non-negotiable rules, and the v0.1.0 Definition of Done.
2. **Always read the latest relevant handoff file** in `docs/agent-handoffs/` before working on a task (the per-branch `NN_<branch>.md`, and `01_STABILIZATION_AUDIT.md` for the plan and branch sequence).
3. **Always run `git status` before making any changes** and confirm which branch you are on.

## During stabilization — non-negotiable
- **Do not add new ERP features.** Stabilize the foundation first.
- **Do not rewrite the whole app.** Make incremental, targeted changes.
- **Do not change business logic** (payroll/tax/commission/pricing math, etc.) unless explicitly requested.
- **Keep changes small and reviewable.** Prefer the smallest diff that satisfies the task.
- The **sales/CRM stack is frozen** (tickets, quotation, deposit, commission, pricing/FX, catalog, customer, factory, ceo-settings). Do not expand it. v0.1.0 is HR-core only.

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

## Repo quick facts (verified 2026-07-07)
- **Frontend:** React 18 + Vite 8. No router, no state/data library yet. Router is a ternary in `frontend/src/App.jsx`; data fetching is manual per page; single global `frontend/src/styles.css`. Tests: Vitest. Lint: ESLint + jsx-a11y.
- **Backend:** Spring Boot 4.1 / Java 21. Session auth. `SecurityConfig` is currently `permitAll` with manual per-endpoint checks. Flyway migrations `V1`–`V32`. No Actuator/OpenAPI yet. Integration tests are gated on `TEST_DB_URL` (skipped on a plain local `mvnw verify`).
- **CI:** `.github/workflows/` — `backend-ci.yml`, `frontend-ci.yml`, `dependency-review.yml`.
- **Deploy:** `render.yaml` (backend), `vercel.json` (frontend), `docker-compose*.yml` (local). The Render demo is a showcase, not real production.

## Commit / PR conventions
- Conventional-commit style prefixes (`feat:`, `fix:`, `chore:`, `refactor:`, `security:`, `docs:`, `test:`).
- One focused branch → one PR → review → merge. Do not commit or push unless asked.
