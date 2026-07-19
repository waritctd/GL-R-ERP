# CLAUDE.md — Agent Operating Rules for GL-R-ERP

This repository is a GL&R **HR + Sales/CRM portal** growing into an ERP platform. It is not a complete ERP yet. Read this before doing anything.

## Start every session by reading context
1. **Always read `docs/agent-handoffs/00_MASTER_CONTEXT.md` before starting.** It holds product identity, current priorities, and the non-negotiable rules.
2. **Always read the latest relevant handoff file** in `docs/agent-handoffs/` before working on a task (the per-branch `NN_<branch>.md`, and `01_STABILIZATION_AUDIT.md` for the plan and branch sequence).
3. **Always run `git status` before making any changes** and confirm which branch you are on.

## Scope rules — non-negotiable
- **Do not change business logic** (payroll/tax/commission/pricing math, etc.) unless explicitly requested. This is the one rule that never relaxes. **Exception, currently live:** the sales deal/pricing workflow is under an approved redesign — see below.
- **Do not change API contracts, auth, permissions, routes, or DB schema** as a side effect of UI work. The operative words are *as a side effect*: when such a change **is** the task, state it plainly and record it.
- **Keep changes reviewable.** Prefer the smallest diff that satisfies the task, and split unrelated concerns into separate branches.
- **Do not add new ERP features** without an explicit request. Repairing or hardening what exists is always in scope.

### Sales/CRM stack — UNFROZEN (2026-07-16)
The sales/CRM stack (tickets, quotation, deposit, commission, pricing/FX, catalog, customer, factory, ceo-settings) **is no longer frozen**. v0.1.0 was the HR-core-only release and is now historical; sales/CRM is part of the current release line and may be repaired, refactored, and improved like any other surface.

`VITE_ENABLE_SALES` still gates sales nav + routes at runtime, but it is now an **off-switch**: sales is enabled unless the var is explicitly `false`. The direction matters — the production build sets no `VITE_` vars (there is no `env` block in `vercel.json` and `.env*` is gitignored), so the previous `=== 'true'` check left sales disabled in production regardless of intent.

### Sales flow redesign — business logic IS changing (2026-07-20)
The sales deal/pricing workflow is under an **approved multi-step redesign**. Step 1 separates the `PricingRequest` aggregate from the Deal Ticket, so the model is now:

```
1 Ticket = 1 Deal
1 Deal   → 0..N Pricing Requests   (designer / owner / buyer, and revisions)
```

Later steps add factory quotes, landed cost, CEO costing, and quotation generation on top of that aggregate.

**Within sales/CRM only**, changes to workflow logic, sales permissions, sales API contracts, and sales DB schema are **expected and authorised** — they are the point of the work, not a side effect. Do not block on the rules above for sales-flow tasks. Do state each such change explicitly; never smuggle one in under a UI ticket.

Guardrails that do **not** relax:
- **Payroll, tax, SSO and commission math stay untouchable.** This relaxation is scoped to the sales pricing/deal workflow and nothing else.
- **Permission changes must be enforced and verified against the Java service**, never inferred from `mockApi.js`. A mock more permissive than production is the dangerous direction — see the section below.
- **Schema changes are forward-only `Vnnn`.** Never edit an already-applied migration in place.
- **Every such change is recorded in the branch's handoff, with its reasoning**, so a reviewer can tell an intended contract change from an accident.

Worked example: `docs/agent-handoffs/85_feat-sales-pricing-request-foundation.md`, including the deliberate divergences it records — the CEO may cancel a pricing request although `TicketService.cancel` is owner-only, and a `DRAFT` pricing request is visible only to its owning rep plus CEO/sales_manager.

⚠️ Step 1 is **not independently deployable**: ticket-level `submit()` now 409s and the replacement chain does not yet produce a price, so a newly created deal cannot be priced, quoted, or advanced past the pre-quote stages until the later steps land. Do not deploy it alone.

## Mock API contract — shapes are faithful, authz is not
`frontend/src/api/mockApi.js` (`VITE_USE_MOCKS=true`, the `frontend-mock` launch config) is the
**default verification surface** — it is what devs, QA and coding agents drive. Its contract is now
explicit:

- **Endpoints and DTO shapes are a faithful stand-in for the Spring backend.** This is enforced:
  `frontend/src/api/contract.test.js` asserts mockApi's method surface matches `hrApi.js`'s in both
  directions. If you add a method to `hrApi.js`, add it to `mockApi.js` or the test fails. Genuine
  exceptions go in that file's `KNOWN_GAPS` with a written reason, not a silent skip.
- **Authorization is NOT authoritative.** The mock's permission gates approximate the Java services
  and are known to diverge. **Verify permission behaviour against the Java service, never the mock.**
  `VITE_USE_MOCKS=true` verification is therefore *incomplete for anything permission-shaped* — say
  so when reporting, rather than claiming a permission rule was verified.

A mock that is *more permissive* than production is the dangerous direction: you only find out in
prod. This is not hypothetical — issue #199 was exactly this (mock let HR approve OT; the real
`OvertimeService` returns 403), and an agent reported "the backend would accept an HR approval"
because it read the mock's authz as the backend's.

When editing `mockApi.js`, keep each namespace's `// Mirrors <JavaClass>` header accurate — that
pointer is how the next reader finds the source of truth.

### Permission changes must ship evidence — not a claim

The paragraph above was already in this file and an agent still reported mock-driven browser
clicking as verified role scoping (PR #238). Prose was not enough, so this is now a **requirement**:

**Any change that touches authorization — a role gate, a scope/filter, who may read or write whose
rows — must ship a real-DB integration test through the real Java service, or it is not done.**

1. **Unit-test the decision** (`resolveScope`-style): proves the right branch was chosen.
2. **Integration-test the enforcement** against real Postgres, through the real service *and*
   repository (`AbstractPostgresIntegrationTest`): proves the decision survives into the `WHERE`
   clause and actually filters rows. **Mockito cannot reach this** — a mocked repository happily
   "passes" while the SQL does something else. This repo has been bitten by exactly that before.
3. **Write the cases wrong-way-round.** Assert the caller *cannot* reach what they shouldn't, not
   that they can reach their own. "Manager asks for an out-of-division employee → zero rows" is the
   test that matters; "manager sees their own division" is not.
4. **Mutation-check the guard.** Introduce the vulnerability, confirm that specific test goes red
   and nothing else, then revert to an empty diff. A green test that cannot fail is not evidence.

Reference implementation:
`backend/src/test/java/th/co/glr/hr/attendance/AttendanceScopeIntegrationTest.java`.

**Reporting:** if verification ran only under `VITE_USE_MOCKS=true`, say so and call the permission
aspect **unverified**. Never let "I clicked through it as HR" stand in for an authz claim.

## Styling direction — Tailwind-first
The frontend is migrating from the single global `frontend/src/styles.css` to a **Tailwind-first** system. Tailwind 4 is already wired up via `@tailwindcss/vite`, with design tokens in `frontend/src/index.css` (`@theme static`).

- **Prefer Tailwind utilities and shared Tailwind-based components** for layout, spacing, typography, color, borders, radius, state, and responsive behavior.
- **Do not add new page-specific CSS files.** If native CSS is genuinely unavoidable, document why.
- **Keep global CSS only for**: Tailwind imports/layers, design tokens/CSS variables, font setup, base reset, third-party overrides, and rare keyframes.
- **Use `@apply` only inside shared semantic component classes** — never build a second hidden design system.
- **Tailwind breakpoints drive responsive behavior.** Remove dead CSS carefully, and never at the cost of a visual regression.
- Migrating existing CSS is expected and allowed, but do it in reviewable slices with screenshots — not as one blind rewrite.

## Branch & agent discipline
- **One branch per task.** `main` must stay deployable; branch off `main`, open a PR, merge only after review.
- **One implementation agent per branch.** Do not let two agents (e.g. Claude and Codex) edit the same branch at the same time.
- **Reviewer agents do not implement** — except tiny, safe fixes (typos, obvious one-liners). Anything larger goes back to an implementation branch.

## Before you finish an implementation task
- **Always run the relevant tests/builds** and record the results:
  - Frontend: `cd frontend && npm run lint && npm test && npm run build` (there is no `typecheck` script)
  - Backend: `cd backend && ./mvnw -B clean verify` (integration tests need Postgres — either `TEST_DB_URL` **or** a running Docker for Testcontainers; note if they were skipped)
- **Did the change touch authorization?** (a role gate, a scope/filter, who may read or write whose
  rows.) If yes, a real-DB integration test through the real Java service is **required** — see
  "Permission changes must ship evidence" above. If it ran on mocks only, report the permission
  aspect as **unverified**; do not describe it as tested.
- **Update the relevant handoff file before ending.** Fill in every section, and always list:
  1. **Files changed** (path + what changed)
  2. **Commands run**
  3. **Tests / build results** (pass/fail/not run, and whether integration tests *ran* or were skipped)
  4. **Authz evidence** (real-service test, or "no authz change", or "unverified — mock only")
  5. **Known risks**
  6. **The exact next prompt** for the next agent

## Handoff system
`docs/agent-handoffs/` is the shared memory between Claude, Codex, and reviewer agents. See `docs/agent-handoffs/README.md` for the process and the handoff template. Create a new `NN_<branch-name>.md` from the template when you start a branch.

## Repo quick facts (frontend verified 2026-07-16)
- **Frontend:** React 18 + Vite 8. Routing is `react-router-dom` 7 (`frontend/src/App.jsx`); server state via `@tanstack/react-query` 5; tables via `@tanstack/react-table` 8; forms via `react-hook-form` + `zod`. Styling is mid-migration: Tailwind 4 (`@tailwindcss/vite`) with tokens in `src/index.css`, alongside a legacy global `src/styles.css` (~2k lines) being progressively retired. Tests: Vitest. Lint: ESLint + jsx-a11y.
- **There is no `typecheck` script** — this is a plain JS project with no TypeScript. Validation is `npm run lint && npm test && npm run build`. Do not claim a typecheck ran.
- **npm scripts live in `frontend/`**, not the repo root (there is no root `package.json`).
- **Backend:** Spring Boot 4.1 / Java 21. Session auth. `SecurityConfig` is currently `permitAll` with manual per-endpoint checks. Flyway migrations run to `V58` (plus a `db/migration-demo` seed). No Actuator/OpenAPI yet. Integration tests resolve Postgres via `TEST_DB_URL` **or** Testcontainers when Docker is available (`support/PostgresTestSupport#isAvailable`), and skip only when neither exists — so they usually *do* run on a local `mvnw verify`.
- **CI:** `.github/workflows/` — `backend-ci.yml`, `frontend-ci.yml`, `dependency-review.yml`.
- **Deploy:** `render.yaml` (backend), `vercel.json` (frontend), `docker-compose*.yml` (local). The Render demo is a showcase, not real production.

## Commit / PR conventions
- Conventional-commit style prefixes (`feat:`, `fix:`, `chore:`, `refactor:`, `security:`, `docs:`, `test:`).
- One focused branch → one PR → review → merge. Do not commit or push unless asked.
