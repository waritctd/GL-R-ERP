# Agent Handoff

## Task
v0.1.0 release-prep frontend work — the two remaining Definition-of-Done decisions:
- **#2 (desktop-first admin flows "clearly labeled"):** add a mobile "optimized for desktop" notice to
  payroll processing + attendance import.
- **Sales-page visibility:** flag-hide the frozen sales/CRM pages so v0.1.0 ships cleanly HR-core.

## Branch
`frontend/v0.1.0-release-prep`

## Base Commit
`b363977` (main tip — includes P2-1 openapi, P2-4 audit coverage, P2-5 docs cleanup)

## Current Commit
See PR.

## Agent / Model Used
Planned + reviewed/verified: Claude Opus. Implemented: Claude Sonnet.

## Scope

### In Scope
- Feature flag `SALES_ENABLED` (env `VITE_ENABLE_SALES`, default **false**) gating sales nav + routes.
- `useIsMobile()` hook + `DesktopOnlyNotice` banner on payroll + attendance.

### Out of Scope
- No change to the frozen sales page components themselves (only their routing/nav registration).
- No business-logic change; the mobile card reflow (from branch 2) is unchanged.

## Files Changed
- `frontend/src/app/features.js` (new) — `SALES_ENABLED = import.meta.env.VITE_ENABLE_SALES === 'true'`.
- `frontend/src/components/layout/AppShell.jsx` — ANDed `SALES_ENABLED` into the `show` of the 4 sales
  nav items (`/ticket-overview`, `/tickets`, `/ceo-settings`, `/commissions`). No other nav touched.
- `frontend/src/App.jsx` — wrapped the 5 in-`RequireAccess` sales routes and the standalone
  `/ceo-settings` route in `{SALES_ENABLED && (...)}`; with the flag off they fall through to the
  existing `path="*"` → `Navigate to="/"`. All imports retained (still referenced in the conditional).
- `frontend/.env.example` — added `VITE_ENABLE_SALES=false` (commented).
- `frontend/src/hooks/useIsMobile.js` (new) — `matchMedia('(max-width: 720px)')`, SSR/jsdom-safe.
- `frontend/src/components/common/DesktopOnlyNotice.jsx` (new) — `role="note"` bilingual banner,
  `message`/`children` override; no icon (Icon registry has no info/monitor glyph — matches InfoTip).
- `frontend/src/styles.css` — additive `.desktop-only-notice` block using existing tokens.
- `frontend/src/features/payroll/PayrollPage.jsx` + `.../attendance/AttendancePage.jsx` — render
  `{isMobile && <DesktopOnlyNotice />}` as the first child of `page-stack`.
- `frontend/src/hooks/useIsMobile.test.js` (new) + `.../common/DesktopOnlyNotice.test.jsx` (new).

## Commands Run
```bash
cd frontend && npm run lint && npm test && npm run build   # run by implementer AND reviewer
```

## Test / Build Results
- Lint: **0 errors** (9 pre-existing exhaustive-deps warnings in untouched files).
- Tests: **55/55 passed** (10 files; +6 new cases across the 2 new test files).
- Build: **success** (386 KB with flag off; ~492 KB with flag on — confirms sales code is
  tree-shaken out of the default build).
- Note: verification was via lint/test/build + diff review. A live browser preview of the gated
  payroll/attendance pages needs the backend running + a login session, so it was not run; the
  behavior is covered by the unit tests, the bundle-size delta, and the route/nav diff.

## Decisions Made
- Flag defaults OFF, so the default v0.1.0 build is HR-core with sales hidden. Re-enabling is a single
  env var — the sales code is frozen, not deleted.
- Desktop notice is informational only (non-blocking); the heavy flows still fully work on mobile.

## Assumptions
- jsdom has no `matchMedia`, so `useIsMobile()` returns false in existing tests (payroll test unaffected).

## Known Risks
- `NotificationBell` (in AppShell) can still deep-link to `/tickets/:id`; with sales off those redirect
  home. Minor and acceptable (ticket notifications are a sales concern). Left as-is intentionally.

## Things Not Finished
- Nothing in scope. This is the last DoD gap before the tag.

## Recommended Next Agent
Owner: review/merge this PR. Then the FINAL step: update `00_MASTER_CONTEXT.md` / `01_STABILIZATION_AUDIT.md`
to reflect completion and **tag `v0.1.0`** (confirm with owner before tagging).

## Exact Next Prompt
```
All P0/P1/P2 + both v0.1.0 decisions are merged. Do the final release step: on a branch off clean main,
update docs/agent-handoffs/00_MASTER_CONTEXT.md (DoD all checked) and note completion, do a final
backend ./mvnw -B clean verify + frontend lint/test/build, then create the annotated tag v0.1.0 on main.
Ask the owner before pushing the tag.
```
