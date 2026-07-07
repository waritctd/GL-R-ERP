# Agent Handoff

## Task
Phase 4 of the Tailwind style migration: convert the LAYOUT markup of the two
remaining hand-rolled (non-`DataTable`) HR-core pages —
`frontend/src/features/overtime/OvertimePage.jsx` and
`frontend/src/features/leave/LeavePage.jsx` — to the layout primitives built in
earlier Phase 4 PRs (`frontend/src/components/common/Layout.jsx`:
`PageStack`/`Panel`/`FormGrid`+`formGridSpan2`/`StatGrid`/`FilterBar`/
`RowActions`). Explicitly OUT of scope: the react-hook-form logic and the
`*_TABLE_GRID` consts / `table-head`/`table-row` grid markup (both pages
already had their form-library migration and their table-grid conversion done
in prior PRs — this PR only touches the surrounding page chrome).

## Branch
`feat/tw-convert-overtime-leave`

## Base Commit
`bdacb56` (main, includes merged PR #144 "move non-frozen table grids to
Tailwind + generic card-reflow")

## Current Commit
Committed at the end of this session (see `git log -1` on this branch after
the commit step below) and pushed. No PR opened, per instructions.

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- `frontend/src/features/overtime/OvertimePage.jsx` — layout markup only:
  `page-stack` → `PageStack`, `stat-grid` → `StatGrid`, `filter-bar` (a
  `<form>`) → kept as `<form>` with a reproduced utility-class const
  (`FILTER_BAR_CLASS`, see Decisions), `panel`/`panel-header` → `Panel`
  (`title` shorthand), `form-grid` (a `<form>`) → kept as `<form>` with a
  reproduced utility-class const (`FORM_GRID_CLASS`, see Decisions), `span-2`
  → `formGridSpan2`, the submit-button `span-2 row-actions` wrapper →
  `RowActions className={formGridSpan2}`.
- `frontend/src/features/leave/LeavePage.jsx` — same primitive swaps, plus:
  `leave-balance-grid` (no primitive fits — see Decisions) reproduced inline
  as a new local const `LEAVE_BALANCE_GRID`; three consecutive `Panel`
  conversions (โควตาวันลา / ยื่นคำขอลา / ปฏิทินวันลา).

### Out of Scope / Not Touched
- `OVERTIME_TABLE_GRID` / `LEAVE_TABLE_GRID` consts and the `table-head`/
  `table-row` grid divs in both files — untouched (confirmed via `git diff`
  showing zero changes to those lines beyond nearby added comments).
- react-hook-form logic (`register`, `useForm`, `zodResolver`, `useWatch`,
  validation schemas, mutations) — untouched, zero behavior/data changes.
- `.leave-calendar-list` / `.leave-calendar-item` / `.leave-balance-card` —
  left as legacy classes (not named in the task's primitive-swap list; their
  CSS is untouched so parity is unaffected).
- `frontend/src/styles.css` — confirmed byte-for-byte unchanged
  (`git diff --stat -- frontend/src/styles.css` empty).
- All frozen sales/CRM pages (`tickets`, `deposits`, `commissions`,
  `ceoSettings`, `dashboard/TicketDashboard.jsx`) — confirmed untouched via
  `git diff --stat` (none appear).
- `Layout.jsx` / `FieldList.jsx` primitives themselves — not modified; no real
  gap was found that required changing a shared primitive (see Decisions for
  why `FilterBar`/`FormGrid` specifically were NOT used as wrapper components
  for the two `<form>` elements).

## Files Changed
- `frontend/src/features/overtime/OvertimePage.jsx` (33 insertions / 26
  deletions): added imports (`formGridSpan2`, `Panel`, `PageStack`,
  `RowActions`, `StatGrid` from `Layout.jsx`), added two local consts
  (`FILTER_BAR_CLASS`, `FORM_GRID_CLASS`), converted `page-stack` → `PageStack`
  wrapper, `stat-grid` → `StatGrid`, `panel`/`panel-header` → `Panel
  title="ยื่นคำขอ OT"`, `span-2` div → `formGridSpan2`, `span-2 row-actions`
  div → `RowActions className={formGridSpan2}`.
- `frontend/src/features/leave/LeavePage.jsx` (52 insertions / 45 deletions):
  same import/const additions plus `LEAVE_BALANCE_GRID`; converted
  `page-stack` → `PageStack`, `stat-grid` → `StatGrid`, all three
  `panel`/`panel-header` blocks (โควตาวันลา / ยื่นคำขอลา / ปฏิทินวันลา) →
  `Panel title="…"`, `leave-balance-grid` div → `LEAVE_BALANCE_GRID` class,
  `span-2` → `formGridSpan2`, `span-2 row-actions` → `RowActions
  className={formGridSpan2}`.

## How `leave-balance-grid` Was Reproduced
`styles.css:922-926` defines `.leave-balance-grid` as:
```css
.leave-balance-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
```
with a `grid-template-columns: 1fr` override only at `≤720px`
(`styles.css:1871`, part of the shared `@media (max-width: 720px)` group —
notably there is **no** `≤1040px` override for this class, unlike
`form-grid`/`stat-grid`). No existing primitive matches a straight 3-equal-
column layout (`StatGrid` is 4→2→1 col at different breakpoints; `FieldList`
with `columns={3}` is a `<dl>` for term/definition pairs, not a plain grid of
`<div>` cards). Reproduced as a local const:
```js
const LEAVE_BALANCE_GRID = 'grid grid-cols-3 gap-3 max-[720px]:grid-cols-1';
```
Verified in-browser via `preview_inspect`: desktop (1440px) computed
`grid-template-columns: 350px 350px 350px`; mobile (390px) computed
`grid-template-columns: 316px` (collapsed to 1 col) — exact match to spec.

## `page-heading` / `page-actions`
Neither class appears in either file — both pages already use the
`PageHeader` component (not raw `.page-heading`/`.page-actions` markup), so
no reproduction was needed for these two classes despite being named in the
task's "read the exact rules" list.

## Commands Run
```bash
git checkout -b feat/tw-convert-overtime-leave   # off origin/main @ bdacb56
cd frontend && npm install                        # worktree had no node_modules
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
git diff --stat -- frontend/src/styles.css                     # confirm empty
git diff --stat -- frontend/src/features/tickets frontend/src/features/deposits \
  frontend/src/features/commissions frontend/src/features/ceoSettings \
  frontend/src/features/dashboard/TicketDashboard.jsx           # confirm empty
git diff --stat                                                 # confirm only 2 files
grep -n 'className="page-stack"\|className="panel"\|className="panel-header"\|
  className="form-grid"\|className="stat-grid"\|className="filter-bar"\|
  className="span-2"' frontend/src/features/overtime/OvertimePage.jsx \
  frontend/src/features/leave/LeavePage.jsx                      # confirm zero matches
```
Plus a manually-started `vite` dev server from **this worktree**
(`VITE_USE_MOCKS=true npx vite --host 127.0.0.1 --port 5202 --strictPort`,
started via the Bash tool's `run_in_background` so it survives across tool
calls) and a `frontend-mock` preview tab redirected to it via
`location.href` (reproducing the same preview-tooling cwd gotcha documented in
`27_tw-kill-inline-styles.md` and `28_tw-table-grids.md`: `preview_start` for
`frontend-mock` launches `vite` against the **outer repo** checkout, not this
worktree — confirmed via `fetch('/src/features/overtime/OvertimePage.jsx')`
not containing the new `FILTER_BAR_CLASS` marker on the auto-started server).

## Test / Build Results
- Lint: **pass** — `eslint src` → 0 errors, 9 pre-existing warnings (all
  `react-hooks/exhaustive-deps` in other/frozen pages — matches the baseline
  from every prior Phase-4 handoff, none introduced by this change).
- Tests: **pass** — `vitest run` → 17 test files, 84 tests, all green,
  including `OvertimePage.test.jsx` and `LeavePage.test.jsx` (both still
  reference form-field `id`s/labels only, unaffected by the layout-only
  change).
- Build: **pass** — `vite build` → built in ~130-150ms, no errors.
  `dist/assets/OvertimePage-*.js` (15.42 kB / gzip 4.79 kB) and
  `dist/assets/LeavePage-*.js` (18.08 kB / gzip 5.30 kB) emitted normally.

## Browser Parity Verification
A significant part of this session was spent working around **severe
preview-tooling instability** beyond what prior handoffs flagged: in this
session, `preview_eval` calls and `preview_resize` calls (even alone,
without any DOM mutation) frequently caused the mock-auth session to reset to
the login screen, and rapid `preview_click` sequences sometimes clicked
through a mobile nav-drawer overlay onto the page underneath. Worked around by
(a) never chaining `preview_eval` between clicks, (b) doing one `preview_click`
per call with a screenshot in between to confirm state, (c) preferring
`preview_inspect` (which did NOT reset the session) over `preview_eval`/
screenshots for confirming computed grid values. Verified, with evidence:

- **OvertimePage, HR role, desktop**: `StatGrid` (4 cards), `<form
  className={FILTER_BAR_CLASS}>` (3-4 fields depending on
  `hasMultipleEmployeeOptions`), `Panel title="ยื่นคำขอ OT"` wrapping a 2-col
  `<form className={FORM_GRID_CLASS}>` — screenshot confirms pixel-identical
  layout to the pre-conversion markup.
- **OvertimePage, Employee role, desktop**: same layout, single-employee
  variant (no employee `<select>`/filter, per existing `hasMultipleSubmitOptions`/
  `hasMultipleEmployeeOptions` logic, unchanged) — screenshot confirms correct
  conditional rendering.
- **OvertimePage, mobile 390px**: `StatGrid` confirmed collapsing to 1 column
  via screenshot.
- **OvertimePage, `RowActions` merge**: `preview_inspect` on the submit-button
  wrapper (desktop 1440px) → `className: "flex justify-end gap-2 col-span-2
  max-[720px]:col-span-1"`, `reactComponent: "RowActions"` — confirms
  `RowActions className={formGridSpan2}` merges both sets of utilities
  correctly via `cn()`/`tailwind-merge` with no class collision.
- **LeavePage, HR role, desktop**: full-page screenshot shows `StatGrid` (4
  cards), `Panel title="โควตาวันลา"` wrapping the 3-col `LEAVE_BALANCE_GRID`
  (ลากิจ/ลาป่วย/ลาพักร้อน cards, pixel-aligned), `<form
  className={FILTER_BAR_CLASS}>`, `Panel title="ยื่นคำขอลา"` wrapping the 2-col
  form — all matching the pre-conversion layout exactly.
- **LeavePage, `LEAVE_BALANCE_GRID` computed styles**: `preview_inspect` at
  desktop 1440px → `grid-template-columns: 350px 350px 350px` (3 equal
  columns); at mobile 390px → `grid-template-columns: 316px` (collapsed to 1
  column) — exact match to the `.leave-balance-grid` CSS spec including the
  "no ≤1040px override" detail.
- **LeavePage, `FormGrid` reproduction computed styles**: `preview_inspect` on
  the "ยื่นคำขอลา" `<form className={FORM_GRID_CLASS}>` → desktop (1440px)
  `grid-template-columns: 530px 530px` (2 equal columns); mobile (390px)
  `grid-template-columns: 316px` (collapsed to 1 column, via
  `max-[720px]:grid-cols-1`) — exact match.
- **LeavePage, `RowActions` merge, mobile**: `preview_inspect` at 390px on the
  submit-button wrapper → `className: "flex justify-end gap-2 col-span-2
  max-[720px]:col-span-1"`, computed `display: flex; justify-content:
  flex-end` — confirms the button stays right-aligned within the collapsed
  single column.
- `preview_console_logs`: clean (no errors) throughout every successful
  navigation captured.

## Decisions Made
- **`FilterBar` and `FormGrid` were NOT used as JSX wrapper components for
  the two `<form>` elements on either page.** Both primitives in
  `Layout.jsx` unconditionally render a `<div>` (there is no `as`/polymorphic-
  element prop anywhere in this codebase — checked `Button.jsx` and the rest
  of `components/common/` for precedent, found none). The original markup on
  both pages is `<form className="filter-bar" onSubmit={...}>` and `<form
  className="form-grid" onSubmit={...} noValidate>` — actual `<form>`
  elements needed for native submit semantics (Enter-to-submit on the search/
  submit button, `noValidate` to defer to react-hook-form's `zodResolver`).
  Wrapping a `<form>` *inside* a `<div>`-rendering `FilterBar`/`FormGrid`
  would add an extra non-form wrapper div and either break Enter-to-submit or
  require duplicating the submit handler onto a wrapping div (not equivalent).
  Instead, each primitive's exact Tailwind utility string was reproduced
  verbatim as a local const (`FILTER_BAR_CLASS`, `FORM_GRID_CLASS`) applied
  directly to the `<form className={...}>` — same technique already
  established for `*_TABLE_GRID` in the prior PR (`28_tw-table-grids.md`).
  This is flagged as a **known primitive gap**: if a third page needs a
  `<form>`-based filter bar or form grid, this same pattern should be reused
  (or, if it recurs a third time, `Layout.jsx`'s `FilterBar`/`FormGrid` could
  gain an `as` prop — not done here since the task said only touch primitives
  if a real gap is found, and two call sites reusing a documented pattern
  isn't enough justification to change a shared primitive's API mid-migration).
- `LEAVE_BALANCE_GRID` follows the same "local const, not a new shared
  primitive" pattern as `DASHBOARD_GRID` (`26_tw-convert-dashboards.md`) and
  `*_TABLE_GRID` (`28_tw-table-grids.md`) — it's currently a single-consumer
  ratio (3-equal-column, no `≤1040px` override), so promoting it to
  `Layout.jsx` would be premature; flagging for a future PR if a second
  consumer appears.
- Used `Panel`'s `title` shorthand for every panel header on both pages — all
  five panel headers here (OT's "ยื่นคำขอ OT"; Leave's "โควตาวันลา"/"ยื่นคำขอลา"/
  "ปฏิทินวันลา") are exactly "one `h2`, zero actions," matching the shorthand
  cleanly (same as the dashboard/profile precedents).
- `RowActions className={formGridSpan2}` (rather than nesting a `formGridSpan2`
  div around a `RowActions`) was used to collapse the original two-class
  `span-2 row-actions` div into one primitive call with a merged className —
  verified via `preview_inspect` (`reactComponent: "RowActions"`, both
  `flex justify-end gap-2` and `col-span-2 max-[720px]:col-span-1` present in
  the resolved className) that `cn()`/`tailwind-merge` combines them without
  any Tailwind class collision (no overlapping CSS properties between the two
  utility sets).

## Assumptions
- The task instruction to read `leave-balance-grid`/`page-heading`/
  `page-actions` rules implied all three might be present; `page-heading`/
  `page-actions` turned out to be unused by either page (both already use the
  `PageHeader` component), so no action was needed for those two classes.
- The `frontend-mock` `.claude/launch.json` config (port 5200,
  `VITE_USE_MOCKS=true`) needed no changes; used a manually-started worktree
  `vite` server on port 5202 for reliable verification, per the gotcha
  documented in `27_tw-kill-inline-styles.md`/`28_tw-table-grids.md`.

## Known Risks
- **`FilterBar`/`FormGrid` still lack a way to render as `<form>`.** Any
  future page conversion with a `<form className="filter-bar">` or `<form
  className="form-grid">` will need to either repeat the
  `FILTER_BAR_CLASS`/`FORM_GRID_CLASS` local-const pattern established here,
  or (if this becomes a 3rd+ occurrence) a future PR should consider adding
  an `as` prop to both primitives in `Layout.jsx` to avoid further
  duplication. Two files now carry an identical copy of both consts
  (`FILTER_BAR_CLASS`, `FORM_GRID_CLASS`) — low risk since the underlying
  `.filter-bar`/`.form-grid` CSS is not expected to change during
  stabilization, but worth a grep check
  (`grep -rn "FILTER_BAR_CLASS\|FORM_GRID_CLASS" frontend/src`) before any
  further page-chrome work.
- **Preview-tooling instability was markedly worse this session** than
  documented in `27_tw-kill-inline-styles.md`/`28_tw-table-grids.md`: beyond
  the known "auto-started server points at the outer repo" gotcha, this
  session also hit (a) `preview_eval` calls resetting the mock-auth session
  to the login screen essentially every time, (b) `preview_resize` calls
  sometimes doing the same, (c) rapid `preview_click` sequences occasionally
  clicking through a mobile nav-drawer overlay onto the page underneath. None
  of this reflects an app bug — worked around by preferring `preview_inspect`
  (which reliably did NOT reset the session) for computed-style verification,
  and by doing single click-then-screenshot steps rather than chaining calls.
  Flagging clearly in case the next agent hits the same thing.
- No CSS was deleted or added (by design — `styles.css` deletion is a later,
  separate PR once all pages have converted off the legacy classes).

## Things Not Finished
- `.leave-calendar-list`/`.leave-calendar-item`/`.leave-balance-card` (the
  balance/calendar *card* styling, as opposed to their grid/list *container*)
  were left as legacy classes — not part of this PR's named primitive list
  (`page-stack`/`panel`/`panel-header`/`form-grid`/`stat-grid`/`filter-bar`/
  `row-actions`), and their CSS is untouched so parity is unaffected.
- The `FilterBar`/`FormGrid` "no `<form>` support" gap noted above is not
  fixed — only worked around locally in these two files.
- No CSS classes were deleted (per the "additive/parity-first" constraint —
  a future cleanup PR, once ALL non-frozen pages are converted, should
  grep-verify and remove now-dead `.page-stack`/`.panel`/`.panel-header`/
  `.form-grid`/`.stat-grid`/`.filter-bar`/`.row-actions`/`.span-2` rules from
  `styles.css`, but `OvertimePage`/`LeavePage` were only two of several
  consumers so these classes are likely still live elsewhere — grep before
  deleting).

## Recommended Next Agent
Claude Sonnet (implementation) — continue Phase 4 into the next non-frozen
page group per the migration order (check `01_STABILIZATION_AUDIT.md` and
recent handoffs for what's left — candidates include `ProfileRequestsPage.jsx`
per the note in `28_tw-table-grids.md`, or the final grep-verified `styles.css`
cleanup pass once all non-frozen pages are confirmed converted).

## Exact Next Prompt
```
You are the single implementation agent for one branch on the GL-R-ERP repo,
in an isolated git worktree. This continues Phase 4 of the Tailwind style
migration. Two candidates are ready, pick ONE (check current branch/PR state
first in case either has already landed):

(a) Convert ProfileRequestsPage.jsx's request-table (bare, non-.mine)
    6-column table AND its surrounding page-chrome (page-stack/panel/
    panel-header/stat-grid/filter-bar/row-actions if present) to the same
    Tailwind primitives used across OvertimePage/LeavePage
    (docs/agent-handoffs/29_tw-convert-overtime-leave.md) and
    MyRequestsPage/EmployeeListPage/AttendancePage
    (docs/agent-handoffs/28_tw-table-grids.md) — this is the last remaining
    non-frozen page still on legacy per-page CSS classes.

(b) CSS cleanup: grep-verify and delete now-dead legacy classes from
    frontend/src/styles.css — .page-stack, .panel, .panel-header, .form-grid,
    .stat-grid, .filter-bar, .row-actions, .span-2, plus the per-table classes
    flagged as dead in docs/agent-handoffs/28_tw-table-grids.md
    (.employee-table, .attendance-table, .overtime-table, .leave-table,
    .request-table.mine) — ONLY if grep confirms zero remaining
    className/gridClassName references across frontend/src. Do NOT remove
    .reflow-cards, .request-table (bare, still used by ProfileRequestsPage
    unless (a) is done first), .payroll-table (frozen-shared with
    ceoSettings), .user-table, .commission-table,
    .commission-payroll-table (sales-stack, frozen).

First read (in order): CLAUDE.md, docs/agent-handoffs/00_MASTER_CONTEXT.md,
docs/agent-handoffs/28_tw-table-grids.md, and
docs/agent-handoffs/29_tw-convert-overtime-leave.md (this file) in full.

Two gotchas to know before touching the browser preview: (1) preview_start's
frontend-mock config may resolve against the OUTER repo checkout instead of
your worktree — verify via fetch() of a just-edited file before trusting any
screenshot; work around by manually starting `VITE_USE_MOCKS=true npx vite
--host 127.0.0.1 --port <free-port> --strictPort` from your worktree via the
Bash tool's run_in_background option (so it survives across tool calls), then
redirect the preview tab to it once via preview_eval's location.href. (2) In
this repo's preview tooling, preview_eval and preview_resize calls have been
observed to intermittently reset the mock-auth session back to the login
screen — prefer preview_inspect (did not reset the session) over
preview_eval for checking computed grid/flex values, and do one
preview_click + preview_screenshot pair at a time rather than chaining
multiple preview_click calls, to avoid clicking through stale overlays.

Hard constraints: parity-first (pixel-identical desktop + mobile ≤720px), do
not touch frozen pages (tickets/deposits/commissions/ceoSettings/
TicketDashboard/payroll-table), do not change data/behavior/form logic, do
not edit/delete any styles.css rule unless doing (b) with grep-verified zero
references, do not commit until verification passes.

Verify: cd frontend && npm run lint && npm test && npm run build (0 lint
errors, 9 pre-existing warnings, tests+build green), then browser parity
check (desktop + mobile 390px) with preview_inspect confirming computed grid
values, console clean. Update/create docs/agent-handoffs/30_<branch-name>.md
before ending, commit, and push (no PR).
```
