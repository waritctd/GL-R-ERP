# Agent Handoff

## Task
Phase 4, PR1 of the Tailwind style migration: build the missing layout primitives
(`Panel`, `PageStack`, `FormGrid`, `StatGrid`, `FilterBar`, `RowActions`, `FieldList`,
`InfoGrid`, `DetailHero`) in `frontend/src/components/common/`, each reproducing the
exact legacy `styles.css` class values via Tailwind utilities/tokens, and convert one
pilot page (`ProfilePage`) to use them. No business-logic changes; `styles.css` stays
untouched in this PR (CSS deletion is a later PR after all pages are converted).

## Branch
`feat/tw-primitives-profile`

## Base Commit
`fbd3a5b9c6b0614f966e04679608f4ef0efd50ae` (main, `9eaf486` + no further commits — matches
`docs/agent-handoffs/00_MASTER_CONTEXT.md` snapshot date 2026-07-07)

## Current Commit
Not committed — changes left in the working tree for review, per instructions.

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- New primitives in `frontend/src/components/common/`: `Layout.jsx` (`PageStack`,
  `Panel`/`Panel.Header`, `FormGrid`, `formGridSpan2`, `StatGrid`, `FilterBar`,
  `RowActions`) and `FieldList.jsx` (`FieldList`, `InfoGrid`, `DetailHero`).
- Render tests for `Panel`, `PageStack`, and `StatGrid` in `Layout.test.jsx`.
- Convert `frontend/src/features/profile/ProfilePage.jsx` to use `PageStack`,
  `DetailHero`, `InfoGrid`, and `Panel` (with `title`/`actions` props) in place of the
  legacy `page-stack` / `detail-hero.compact` / `info-grid.two` / `panel` /
  `panel-header` classes.

### Out of Scope
- `editable-list` and `request-feed` classes inside `ProfilePage` — not part of the
  primitive list in this PR's task; left as legacy classes (their CSS is untouched, so
  they still render correctly).
- Any frozen pages (`features/tickets`, `features/deposits`, `features/commissions`,
  `features/ceoSettings`, `features/dashboard/TicketDashboard.jsx`) — not touched.
- Any `styles.css` edits or deletions — file confirmed byte-for-byte unchanged
  (`git diff --stat frontend/src/styles.css` is empty).
- Converting any other page (dashboards, attendance, etc.) — next PR per the Phase 4 plan.

## Files Changed
- `frontend/src/components/common/Layout.jsx` — **new**. `PageStack` (`.page-stack`:
  `grid gap-[18px] max-w-[1320px]`), `Panel` + `Panel.Header` (`.panel` +
  `.panel-header`: surface/border/radius/shadow/padding, optional `title`/`actions`
  props render the header row), `FormGrid` + `formGridSpan2` export (`.form-grid` /
  `.form-grid.single` / `.span-2`, incl. ≤720px collapse to 1 column), `StatGrid`
  (`.stat-grid`, 4→2 cols at ≤1040px → 1 col at ≤720px), `FilterBar` (`.filter-bar`),
  `RowActions` (`.row-actions`).
- `frontend/src/components/common/FieldList.jsx` — **new**. `FieldList` (`.field-list`
  / `.field-list.three`: 2/3-col `<dl>` grid, ≤720px collapses to 1 col; `dt`/`dd`
  margin+typography via arbitrary `[&_dt]`/`[&_dd]` selectors matching the still-live
  global `.field-list dt/dd` rules), `InfoGrid` (`.info-grid` / `.info-grid.two`),
  `DetailHero` (`.detail-hero` + `.detail-hero.compact`: flex row → column at ≤720px,
  2nd child gets `flex-1 min-w-0`, `compact` caps `max-width: 980px`).
- `frontend/src/components/common/Layout.test.jsx` — **new**. Render tests for `Panel`
  (children render, surface classes, `title`/`actions` header, className merge),
  `PageStack` (children render, grid classes), `StatGrid` (children render, responsive
  grid-cols classes at each breakpoint).
- `frontend/src/features/profile/ProfilePage.jsx` — replaced `<div className="page-stack">`
  → `<PageStack>`, `<section className="detail-hero compact">` → `<DetailHero compact>`,
  `<div className="info-grid two">` → `<InfoGrid two>`, both `<section className="panel">`
  (+ manual `panel-header` on the second) → `<Panel title=… actions=…>`. No prop/data/
  behavior changes — same children, same conditional logic.

## Commands Run
```bash
git checkout -b feat/tw-primitives-profile
cd frontend && npm run lint
cd frontend && npm test
cd frontend && npm run build
git diff --stat frontend/src/styles.css   # confirm empty
```
Plus a `frontend-mock` preview session (`preview_start` → login as Employee demo role →
navigate to `/profile` → desktop screenshot + computed-style inspection → `preview_resize`
to mobile (375×812) → screenshot + a11y snapshot → back to desktop → `preview_stop`).

## Test / Build Results
- Lint: **pass** — `eslint src` → 0 errors, 9 pre-existing warnings (all in frozen/other
  pages: `AttendancePage`, `CeoSettingsPage`, `CommissionPage`, `TicketDashboard`,
  `DepositNoticePage`, `PayrollPage`, `TicketDetailPage`, `TicketListPage` — matches the
  expected baseline, none introduced by this change).
- Tests: **pass** — `vitest run` → 17 test files, 84 tests, all green (new
  `Layout.test.jsx` adds 6 passing tests; no existing test broken).
- Build: **pass** — `vite build` → 246 modules transformed, built in 130ms, no errors.
  `dist/assets/ProfilePage-*.js` emitted normally (5.10 kB / gzip 2.05 kB).

## Decisions Made
- Grouped the 6 small structural primitives (`PageStack`, `Panel`, `FormGrid`,
  `StatGrid`, `FilterBar`, `RowActions`) into one `Layout.jsx` file, and the 3
  hero/field-list primitives into `FieldList.jsx`, matching the plan's suggested
  grouping and the repo's mix of one-per-file / small-grouped-file conventions.
- `Panel` takes optional `title`/`actions` props that render a `Panel.Header`
  internally (matching `.panel h2` + `.panel-header` exactly) — this covers both
  ProfilePage panels (one with just a title, one with title + a text-button action)
  without callers having to hand-roll the header markup. `Panel.Header` is also
  exported standalone for pages that need custom header content later.
- `DetailHero` auto-wraps its **second** child in a `flex-1 min-w-0` div to reproduce
  the legacy `.detail-hero > div:nth-child(2)` rule, since Tailwind has no direct
  nth-child-of-parent utility usable from the child's own className. This keeps the
  call site (`<DetailHero><Avatar/><div>…</div><StatusBadge/></DetailHero>`) visually
  identical to before with no extra wrapper divs added at the call site itself.
- `formGridSpan2` is exported as a plain class-name string (not a component) so
  `FormGrid` children can opt in via `className={formGridSpan2}` — kept it a primitive
  string rather than a `<Span2>` wrapper component since `.span-2` is applied directly
  to real form-field elements, not a container.
- Left `editable-list`/`request-feed` as legacy classes inside the converted
  `ProfilePage` — they weren't in the PR1 primitive list and their CSS is untouched, so
  parity is unaffected; a later page/primitive PR can pick them up if needed.

## Assumptions
- The `frontend-mock` `.claude/launch.json` config (port 5200, `VITE_USE_MOCKS=true`)
  already existed and needed no changes.
- "Employee" demo-login role is sufficient to reach `/profile` with representative
  data (contact fields + a pending change request) for parity verification.

## Known Risks
- `FieldList` is built (per the task's primitive list) but **ProfilePage doesn't
  actually consume it** — the page uses `editable-list`/`request-feed`, not
  `field-list`, so `FieldList`'s reproduction of `.field-list`/`.field-list.three` is
  verified only by the new render test's class assertions, not by an in-browser parity
  check yet. The next page that uses `field-list` (e.g. `EmployeeDetailPage`, per the
  Phase 4 page order) should visually verify it before relying on it further.
- `Panel`'s auto-header (`title`/`actions` props) assumes every `.panel` header maps
  cleanly to "one `h2` + one optional action node." Pages with more complex panel
  headers (icons, multiple actions, badges next to the title) may need `Panel.Header`
  used directly instead of the `title`/`actions` shorthand — flagged here so the next
  converting agent doesn't force-fit those into the two simple props.
- `DetailHero`'s nth-child-2 special-casing depends on children being passed as a flat
  array with the "main content" block always in position 2 (0-indexed 1) — matches
  every current legacy `.detail-hero` usage (avatar, main block, badge) but should be
  re-checked against each additional page's markup as it's converted.

## Things Not Finished
- Only `ProfilePage` was converted (as scoped). `FormGrid`, `StatGrid`, `FilterBar`,
  `RowActions`, and `FieldList`'s 2/3-column mode are built but not yet exercised by
  any converted page — the next PR (dashboards) should be the first real consumer of
  `StatGrid` and will validate the 4→2→1 responsive breakpoints in-browser.
- No CSS was deleted (by design — deletion is the final PR after all pages convert).

## Recommended Next Agent
Claude Sonnet (implementation) — continue Phase 4, PR2.

## Exact Next Prompt
```
You are the single implementation agent for one branch on the GL-R-ERP repo. This is
Phase 4, PR2 of the Tailwind style migration: convert the next non-frozen pages —
the dashboards (`frontend/src/features/dashboard/EmployeeDashboard.jsx` and
`frontend/src/features/dashboard/HrDashboard.jsx`; do NOT touch
`features/dashboard/TicketDashboard.jsx`, it's frozen) — to the layout primitives
built in PR1 (`frontend/src/components/common/Layout.jsx`:
PageStack/Panel/FormGrid/StatGrid/FilterBar/RowActions, and
`frontend/src/components/common/FieldList.jsx`: FieldList/InfoGrid/DetailHero).

First, read (in order): CLAUDE.md, docs/agent-handoffs/00_MASTER_CONTEXT.md,
docs/agent-handoffs/25_tw-primitives-profile.md (this file — the primitives you'll
reuse and their known risks), and the approved plan
/Users/ploy_warit/.claude/plans/i-want-you-to-magical-manatee.md (Phase 4, PR2…N
section) if still readable. Then read the two dashboard page files plus
`StatCard.jsx` (already Tailwind, keep using it inside StatGrid) before changing
anything. git status, confirm clean on up-to-date main, branch off as
feat/tw-primitives-dashboards (or similar).

Hard constraints (same as PR1): parity-first (pixel-identical desktop + mobile
≤720px), no business-logic/data/prop changes, do not touch frozen pages, do not
edit/delete any styles.css rule in this PR, do not commit/push — leave changes in
the working tree for review.

This is the first real consumer of StatGrid (4-col → 2-col ≤1040px → 1-col ≤720px)
and of Panel/PageStack at a second call site — validate those breakpoints carefully
in the frontend-mock preview (both dashboards render stat cards). If either
dashboard's markup doesn't map cleanly onto the existing primitive props (see
"Known Risks" in 25_tw-primitives-profile.md re: Panel's title/actions shorthand and
DetailHero's nth-child-2 assumption), adjust the call site first — only touch the
primitives themselves if a real gap is found, and note any such change clearly.

Verify: cd frontend && npm run lint && npm test && npm run build (0 lint errors, 9
pre-existing warnings, tests+build green), then frontend-mock preview parity check
(desktop + mobile, both dashboards, both HR and Employee demo-login roles since they
render different dashboards) with screenshots, console clean. Update/create the
handoff file docs/agent-handoffs/26_<branch-name>.md before ending, exact-next-prompt
pointing at the next page group (profileRequests/MyRequestsPage) per the Phase 4 order.
```
