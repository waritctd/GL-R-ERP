# CLAUDE.md — Agent Operating Rules for GL-R-ERP

This repository is a GL&R **HR + Sales/CRM portal** growing into an ERP platform. It is not a complete ERP yet. Read this before doing anything.

## Start every session by reading context
1. **Read this file and `AGENTS.md` before starting.** They hold the product identity, the current priorities, and the non-negotiable rules. There is no longer a separate handoff corpus to read — it was retired in 2026-07 (see "Where the old docs went" below).
2. **Always run `git status` before making any changes** and confirm which branch you are on.
3. **Keep local `main` identical to `origin/main` — always.** `main` is never a place to hold work, so it should only ever fast-forward. Sync it at session start, after every merge, and before cutting any branch:
   ```
   git fetch origin main && git merge --ff-only origin/main
   ```
   `--ff-only` is the point: it refuses to create a merge commit on `main`, so a failure means something was committed locally that should not have been — investigate rather than force it. Verify with `git rev-list --left-right --count HEAD...origin/main`, which must read `0  0`. **Branch from `origin/main`, not from a stale local `main`**: with several worktrees in play this repo has had a full feature built on a base `main` had already moved past, and a branch cut from a stale base is the thing that turns into repeated mid-flight syncs later.
4. **Check for concurrent activity before starting substantial work.** `git branch -a` / `git log --all --oneline -10` to see what other worktrees or agents have pushed recently, and list open PRs to see if another session is already on the same files or surface. Two agents silently touching the same area is how conflicting changes and repeated merge-fixups happen — see the branch-lifetime note below.

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
- **Every such change is stated explicitly in the PR body, with its reasoning**, so a reviewer can tell an intended contract change from an accident.

Known deliberate divergences worth knowing about: the CEO may cancel a pricing request although `TicketService.cancel` is owner-only, and a `DRAFT` pricing request is visible only to its owning rep plus CEO/sales_manager.

⚠️ Step 1 is **not independently deployable**: ticket-level `submit()` now 409s and the replacement chain does not yet produce a price, so a newly created deal cannot be priced, quoted, or advanced past the pre-quote stages until the later steps land. Do not deploy it alone.

## Mock API contract — shapes are faithful, authz and behaviour are not
`frontend/src/api/mockApi.js` (`VITE_USE_MOCKS=true`, the `frontend-mock` launch config) is the
**default verification surface** — it is what devs, QA and coding agents drive. Its contract is now
explicit:

- **Endpoints and DTO shapes are a faithful stand-in for the Spring backend.** This is enforced:
  `frontend/src/api/contract.test.js` asserts mockApi's method surface matches `hrApi.js`'s in both
  directions, **and that each method declares the same number of parameters** (#434). If you add a
  method or a parameter to `hrApi.js`, mirror it in `mockApi.js` or the test fails. Genuine
  exceptions go in that file's `KNOWN_GAPS` / `ARITY_EXEMPTIONS` with a written reason, not a
  silent skip.
- **Authorization is NOT authoritative.** The mock's permission gates approximate the Java services
  and are known to diverge. **Verify permission behaviour against the Java service, never the mock.**
  `VITE_USE_MOCKS=true` verification is therefore *incomplete for anything permission-shaped* — say
  so when reporting, rather than claiming a permission rule was verified.
- **Argument handling — limits, ordering, truncation — is NOT guaranteed by the contract test.**
  The arity check compares *parameter counts only*. It does not see arguments bundled into a
  `params` bag (`list({ limit, page, sort })` matches at arity 1 either way), does not check that a
  declared parameter is actually *used*, and **does not compare ordering at all**. Ordering is not a
  detail: the same limit under a different sort truncates a *different set of rows*, which is the
  mechanism of the bug that opened #434. When you mirror a paginated endpoint, mirror its real
  `ORDER BY` **and** its real `LIMIT` — including whether the cap is caller-supplied or hardcoded in
  the repository — and cite the Java repository in a comment.
- **Where the mock MIRRORS a backend computation, mock-driven tests are not independent evidence
  about that computation.** `computeDraftEtag` deliberately mirrors `PayrollDraftETag.compute`; if
  the algorithm is wrong the same way on both sides, every mock-driven test passes. A mock can
  validate plumbing; it can never validate an algorithm it mirrors. Prefer *not* reimplementing
  payroll/tax/commission math in the mock at all — a "not supported in mock mode" stub is the
  honest option, and most of this file already takes it.

**The three failure shapes on record**, all of which produced a green suite that was evidence of
nothing about the behaviour under test:

| Shape | What passes anyway |
|---|---|
| Mock **drops** an argument the real API honours (`limit`) | truncation-dependent logic never sees truncation |
| Mock **mirrors** a backend computation (`computeDraftEtag`) | a shared algorithmic error is invisible on both sides |
| Mock **omits** a field the feature keys on (`etag`) | the feature's actual code path is never entered |

The common error is treating "green under `VITE_USE_MOCKS=true`" as evidence about *behaviour* when
it is only ever evidence about *the plumbing the fixture happens to drive*. A mock more permissive
than production, or a fixture more populated than production, is the same class of lie — and both
only surface in prod.

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

**Clicking through a browser CAN be authz evidence — but only against the real stack.** The
real-backend e2e suite (`cd frontend && npm run test:e2e`, see `frontend/e2e-real/README.md`)
drives a real browser against real Spring services and a real Postgres, with no mock in the path.
`e2e-real/api-authz.spec.js` is where a role gate's *observed* behaviour is pinned. It does not
replace requirement 2 above — a real-DB integration test through the Java service is still what
proves a scope filter reaches the `WHERE` clause — but a green run there is real evidence. Three
roles have no seeded persona (`account`, `warehouse`, `qc`), so that suite says nothing about them;
the README lists the gaps.

**There is only one e2e suite now.** The mock-frontend suite (`frontend/e2e/`, its own
`playwright.config.js`, and `e2e-ci.yml`) was removed on 2026-08-08 — owner ruling, one e2e job per
PR instead of two. Only 8 of its 73 tests were duplicated by `e2e-real/`; the other 65 were mostly
**visual / layout / form-behaviour** coverage that nothing currently replaces, plus the sales and HR
journey specs. `frontend/e2e-real/README.md` lists exactly what went, and
`git show e08a5d03^:frontend/e2e/<file>` recovers any of it. Do not read a green
`npm run test:e2e` as covering layout or a UI journey — it covers neither.

## Styling direction — Tailwind-first
The frontend is migrating from the single global `frontend/src/styles.css` to a **Tailwind-first** system. Tailwind 4 is already wired up via `@tailwindcss/vite`, with design tokens in `frontend/src/index.css` (`@theme static`).

- **Prefer Tailwind utilities and shared Tailwind-based components** for layout, spacing, typography, color, borders, radius, state, and responsive behavior.
- **Do not add new page-specific CSS files.** If native CSS is genuinely unavoidable, document why.
- **Keep global CSS only for**: Tailwind imports/layers, design tokens/CSS variables, font setup, base reset, third-party overrides, and rare keyframes.
- **Use `@apply` only inside shared semantic component classes** — never build a second hidden design system.
- **Tailwind breakpoints drive responsive behavior.** Remove dead CSS carefully, and never at the cost of a visual regression.
- Migrating existing CSS is expected and allowed, but do it in reviewable slices with screenshots — not as one blind rewrite.

## Frontend design skills — use before/during UI work
- **Use the `information-architecture` skill** whenever frontend work involves navigation, page/content structure, URL patterns, or user flows — plan the structural layer before touching visual design. This applies to new pages/sections and to any restructuring of existing ones, not just net-new features.
- **Use the `frontend-design` skill/plugin** when implementing the actual UI. Hold implementation to an impeccable bar: no generic/placeholder-looking output, consistent with the Tailwind-first direction above and the existing design tokens in `frontend/src/index.css`.
- Both live in `.agents/skills/` (committed). Claude Code loads skills from `.claude/skills/`, which is gitignored, so each machine needs symlinks into `.agents/skills/` — `.claude/hooks/session-start.sh` creates them, **but only when `CLAUDE_CODE_REMOTE=true`**. On a local checkout you must create them yourself, or these skills silently will not exist:
  ```
  mkdir -p .claude/skills && for d in .agents/skills/*/; do ln -sfn "../../$d" ".claude/skills/$(basename "$d")"; done
  ```
  **Re-run that loop in two situations, not one.** It links only what exists *at the moment it runs*, and it is not idempotent against a changing `.agents/skills/`:
  1. **Every new worktree** — the symlinks are gitignored, so a fresh `git worktree add` starts with none.
  2. **After any merge that adds a skill** — an already-linked worktree that pulls in a new one gets the committed directory with no symlink.

  Both failures are **silent**: nothing errors, the skill is simply absent from the listing and the session freehands instead. Confirmed twice on 2026-08-08 — a new worktree started bare, and an already-linked worktree merged `main`, gained `retired-docs`, and did not load it. **`ls .claude/skills` and compare against `ls .agents/skills` before relying on any of them**; the counts must match. Invoke these skills rather than freehanding IA or visual design decisions.

  Note `.agents/skills/` is no longer only design skills — it also carries `retired-docs` and `playwright-best-practices`. The loop covers all of them; this section just happens to be where it is documented.

## Branch & agent discipline
- **One branch per task.** `main` must stay deployable; branch off `main`, open a PR, merge only after review.
- **One implementation agent per branch.** Do not let two agents (e.g. Claude and Codex) edit the same branch at the same time.
- **Reviewer agents do not implement** — except tiny, safe fixes (typos, obvious one-liners). Anything larger goes back to an implementation branch.
- **If your work overlaps another active session's, tell that session — don't just note it and continue.**
  If you find a branch, PR, or file another agent session is actively working (per the concurrent-activity
  check above), and your task touches the same surface, message that session directly (the CCR
  `SendMessage` tool if it's a sibling session, otherwise a comment on its PR) before you proceed, so the
  two lines of work coordinate instead of silently diverging or colliding on merge. Silence here is what
  turns into the review-fix and repeated-main-sync patterns above.
- **Cap branch lifetime — sync with `main` at most once before opening the PR.** A branch that needs
  a second or third `git fetch origin main && git merge/rebase` mid-flight has grown too large or sat
  open too long; both cost real agent time re-reading diffs and re-resolving conflicts. `feat/leave-rules-tab`
  synced `main` four times before merging — that pattern is the thing to avoid, not repeat.
- **Stacked PRs run on Git Town — read [`STACKED-PRS.md`](STACKED-PRS.md) before creating any branch.**
  It has the mechanics (depth cap, recorded parents, the required PR **Stack** block, resync and
  recovery); config is checked in at [`git-town.toml`](git-town.toml). Three things belong here
  because getting them wrong is expensive and you will not have read that file yet:
  - **Default to `git town hack` off `main`. Stack only for a genuine dependency** — the work cannot
    compile, pass tests, or be reviewed without another branch's unmerged commits. "Related" and
    "would conflict later" are **not** dependencies, and a needless stack is pure cost.
  - ⚠️ **`git town sync` pushes** — branches *and* tags — and stashes your working tree. It is not a
    read-only catch-up, so never run it when a push has not been asked for. `--dry-run` first.
  - ⚠️ **Merge bottom-up with a merge commit. Squashing a PR that has children orphans every branch
    above it.**
- **Rework costs a full second pass — verify before handing off, not after review flags it.** Commits
  like `review fixes for V116` (a second pass on quota bookkeeping and probation resolution after
  review) are exactly the pattern to prevent: for business-logic-sensitive surfaces (leave/payroll
  math, permission gates), re-check the diff against the spec/migration yourself before opening the
  PR, rather than relying on review to catch it.

## Before you finish an implementation task
- **Always run the relevant tests/builds** and record the results:
  - Frontend: `cd frontend && npm run lint && npm test && npm run build` (there is no `typecheck` script)
  - Backend: `cd backend && ./mvnw -B clean verify` (integration tests need Postgres — either `TEST_DB_URL` **or** a running Docker for Testcontainers; note if they were skipped)
- **Don't pay full-suite cost on every edit.** While iterating, run only the targeted test
  (`npx vitest run <file>`, a single Maven `-Dtest=ClassName`) against the file you're changing.
  Reserve the full commands above — and a full `mvnw verify` — for the pre-PR check, run once, after
  the change has settled.
- **If neither `TEST_DB_URL` nor Docker is available, don't retry Testcontainers.** Note once that
  integration tests were skipped for lack of a DB and move on — repeated attempts just burn time on a
  precondition that isn't going to change mid-session.
- **Did the change touch authorization?** (a role gate, a scope/filter, who may read or write whose
  rows.) If yes, a real-DB integration test through the real Java service is **required** — see
  "Permission changes must ship evidence" above. If it ran on mocks only, report the permission
  aspect as **unverified**; do not describe it as tested.
- **Write the PR body as the handoff.** It is now the only durable record of a change, so it must
  always list:
  1. **Files changed** (path + what changed)
  2. **Commands run**
  3. **Tests / build results** (pass/fail/not run, and whether integration tests *ran* or were skipped)
  4. **Authz evidence** (real-service test, or "no authz change", or "unverified — mock only")
  5. **Known risks**

## Where the old docs went
`docs/agent-handoffs/`, `docs/ui-repair/` and `docs/ux-ui-audit/` were retired in 2026-07 — **the
PR body is now the handoff**, and the code's own comments carry the reasoning. Source comments
still cite those paths; treat such a pointer as a history reference, not a live file. Nothing is
lost — use the `retired-docs` skill to read any of them back out of git history.

## Repo quick facts
- **Frontend styling is mid-migration:** Tailwind 4 (`@tailwindcss/vite`) with tokens in `src/index.css`, alongside a legacy global `src/styles.css` being progressively retired. `styles.css` is imported as `@import "./styles.css" layer(legacy)` — so a Tailwind utility **always** beats a `styles.css` rule regardless of selector specificity. Measure computed styles before assuming a legacy rule still applies.
- **There is no `typecheck` script** — this is a plain JS project with no TypeScript. Validation is `npm run lint && npm test && npm run build`. Do not claim a typecheck ran.
- **npm scripts live in `frontend/`**, not the repo root (there is no root `package.json`).
- **Backend:** session auth via `SessionSecurityFilter`. `SecurityConfig` is **default-deny** — `anyRequest().authenticated()`, with only four anonymous exceptions (OPTIONS preflight, `POST /api/auth/login`, `POST /api/attendance/punch`, `GET /actuator/health`). Read the file (39 lines) rather than assuming — this bullet claimed `permitAll` and "no Actuator/OpenAPI" until 2026-08-08, and both were wrong. **Role checks live in the controllers, not the filter chain**, so "authenticated" is the only guarantee `SecurityConfig` gives you.
- **CSRF is enforced, but not by Spring Security.** `SecurityConfig` calls `.csrf(disable)` because the app rolls its own: `CsrfCookieFilter` (`@Order(0)`) issues a non-HttpOnly `XSRF-TOKEN` cookie and rejects unsafe `/api/` methods with 403 unless `X-XSRF-TOKEN` matches it. Do **not** "fix" the disabled Spring CSRF — you would double up on an already-working guard.
- Integration tests resolve Postgres via `TEST_DB_URL` **or** Testcontainers when Docker is available (`support/PostgresTestSupport#isAvailable`) — so they usually *do* run on a local `mvnw verify`. **With neither, they ERROR rather than skip.** This line previously claimed they skip; that was false and is worth knowing why, because the mistake is easy to repeat: JUnit's `@EnabledIf` is **not `@Inherited`**, so the annotation on `AbstractPostgresIntegrationTest` disables nothing in its ~130 subclasses. Verified against junit-jupiter-api 5.12.2's own `RuntimeVisibleAnnotations`, and observed by forcing `isAvailable()` false and watching a subclass error ten times rather than skip (PR #770). **Do not annotate a base class and assume subclasses inherit the gate** — annotate each class, or gate in code.
- ⚠️ **`main` IS production — merging deploys.** Owner ruling 2026-08-17. Real GL&R production is the
  **Render backend + Vercel frontend** pipeline: `vercel.json` rewrites `/api/*` to
  `https://gl-r-erp.onrender.com`, and both platforms deploy from `main`. There is **no deploy job** in
  `.github/workflows/` — nothing gates the deploy, so a merge is the release.
  - **This bullet used to read "The Render demo is a showcase, not real production", and that was
    wrong.** It is corrected rather than deleted because the mistake has a consequence worth naming:
    anyone who believes it will treat a merge to `main` as safe. It is not. In particular **Flyway runs
    at boot**, so a migration on a merged branch applies itself to the production Supabase database
    with no further step — see `application-prod.yml`, which documents six checksum mismatches and an
    unresolved `V11`-vs-`V11.1`/`V11.2` split in real prod's own history, and is why
    `validate-on-migrate` is pinned false. Adding a migration is an owner-approved operation with a
    rollback plan, never a routine merge.
  - **One thing still unreconciled:** `application-prod.yml`'s own comments describe "the real GL&R
    production deploy" and "the public gl-r-erp.onrender.com showcase" as two deployments sharing the
    `prod` profile, which does not sit cleanly with the ruling above. Do not assume either reading is
    complete — ask the owner before acting on the distinction.

## Commit / PR conventions
- Conventional-commit style prefixes (`feat:`, `fix:`, `chore:`, `refactor:`, `security:`, `docs:`, `test:`).
- One focused branch → one PR → review → merge. Do not commit or push unless asked.
