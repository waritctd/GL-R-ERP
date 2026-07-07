# Agent Handoff

## Task
Phase 4, final page conversion: convert the LAYOUT markup of the last two
remaining hand-rolled non-frozen HR-core pages —
`frontend/src/features/attendance/AttendancePage.jsx` and
`frontend/src/features/payroll/PayrollPage.jsx` — to the Tailwind layout
primitives in `frontend/src/components/common/Layout.jsx`
(`PageStack`/`Panel`/`FormGrid`/`StatGrid`/`FilterBar`/`RowActions`). Explicitly
OUT of scope: react-hook-form/state logic, `DataTable`, and the
`payroll-table` gridClassName (shared table-grid styling) — both pages already
had their table-grid/form-library work done in prior PRs; this PR only
touches the surrounding page chrome.

## Branch
`feat/tw-convert-attendance-payroll`

## Base Commit
`ce73b7d` (main, includes merged PR #147 "convert OvertimePage + LeavePage
layout to Tailwind primitives")

## Current Commit
Committed and pushed at the end of this session (see `git log -1` on this
branch). No PR opened, per instructions.

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- `frontend/src/features/attendance/AttendancePage.jsx` — layout markup only:
  `page-stack` → `PageStack`, `stat-grid` → `StatGrid`, `filter-bar` (a native
  `<form onSubmit>`) → kept as `<form>` with a reproduced utility-class const
  (`FILTER_BAR_CLASS`, following the exact `OvertimePage`/`LeavePage`
  precedent from `29_tw-convert-overtime-leave.md`).
- `frontend/src/features/payroll/PayrollPage.jsx` — `page-stack` → `PageStack`,
  `stat-grid` → `StatGrid`, `filter-bar payroll-actions` (a `<section>`, no
  native-form constraint) → `<FilterBar className="payroll-actions">`, the
  `panel payroll-detail-panel` sidebar (a real `<aside>`, semantic landmark) →
  kept as `<aside>` with a reproduced utility-class const (`PANEL_CLASS`) plus
  `Panel.Header` for the header row, two `form-grid` `<div>`s inside
  `CollapsibleSection` bodies → `<FormGrid>` (default `<div>` render, no `as`
  needed here since these aren't native forms).

### Out of Scope / Not Touched
- `DataTable`, `attendanceColumns`/`payrollColumns`, `gridClassName="payroll-table"`
  and `gridClassName="grid-cols-[1.35fr_1.5fr_0.8fr_1.2fr_0.8fr_1.15fr] ... reflow-cards"`
  — untouched (confirmed via `git diff` showing zero changes to the `DataTable`
  blocks in either file).
- All react state/handlers (`loadPunches`, `importFile`, `preview`, `process`,
  `confirmProcessPayroll`, `exportBankFile`, `updateAdjustment`, etc.) —
  untouched, zero behavior/data changes.
- `.toolbar-actions` (PageHeader's `actions` slot, both pages) — left as a
  legacy class; not in the primitive-swap list for this PR (no `Toolbar`
  primitive exists), and it's still used by `CommissionPage`/`DepositNoticePage`
  (frozen) — see grep evidence below.
- `.payroll-workspace` (the table+detail-panel 2-col grid wrapper) — left as a
  legacy `<section>`; not in the primitive list, single-consumer, and its ratio
  (`minmax(0,1.35fr) minmax(340px,0.65fr)`) doesn't match any existing
  primitive. Flagging as a `DASHBOARD_GRID`-style local-const candidate for a
  future PR if a second consumer appears.
- `.payroll-detail-grid`, `.payroll-special-grid`, `.payroll-breakdown`,
  `.mini-metric`, `.currency-input`(+`-symbol`), `.collapsible-total`,
  `.confirm-dialog-message` — left as legacy classes; not named in the task's
  primitive list, their CSS is untouched so parity is unaffected.
- `.attendance-import-panel` (+`-device`/`-result`) — left as legacy; not in
  the primitive list (no primitive fits its 4-column
  `minmax(180px,1fr) minmax(200px,1fr) minmax(220px,1.2fr) auto` layout), and
  it's a single-consumer class.
- All frozen sales/CRM pages (`tickets`, `deposits`, `commissions`,
  `ceoSettings`, `dashboard/TicketDashboard.jsx`) — confirmed untouched via
  `git diff --stat` (none appear).
- `frontend/src/styles.css` — confirmed byte-for-byte unchanged
  (`git diff --stat -- frontend/src/styles.css` empty).
- `Layout.jsx` primitives themselves — not modified. `Panel` still has no `as`
  prop (only `FormGrid` does); see Decisions for why that mattered here.

## Files Changed
- `frontend/src/features/attendance/AttendancePage.jsx` (17 insertions / 10
  deletions): added `PageStack`/`StatGrid` import from `Layout.jsx`, added a
  local `FILTER_BAR_CLASS` const (byte-identical to `FilterBar`'s internal
  Tailwind string), converted `<div className="page-stack">` → `<PageStack>`,
  `<section className="stat-grid">` → `<StatGrid>`, `<form className="filter-bar"
  onSubmit={submitFilters}>` → `<form className={FILTER_BAR_CLASS}
  onSubmit={submitFilters}>`, closing `</div>` → `</PageStack>`.
- `frontend/src/features/payroll/PayrollPage.jsx` (36 insertions / 19
  deletions): added `FilterBar`/`FormGrid`/`PageStack`/`Panel`/`StatGrid`
  import from `Layout.jsx`, added `cn` import (`../../utils/cn.js`), added a
  local `PANEL_CLASS` const (byte-identical to `Panel`'s internal Tailwind
  string), converted `page-stack` → `PageStack`, `stat-grid` → `StatGrid`,
  `<section className="filter-bar payroll-actions">` →
  `<FilterBar className="payroll-actions">`, `<aside className="panel
  payroll-detail-panel">` → `<aside className={cn(PANEL_CLASS,
  'payroll-detail-panel')}>` with its inner `<div className="panel-header">`
  → `<Panel.Header>` (imported from `Layout.jsx`, used standalone since
  `Panel` itself couldn't wrap the `<aside>`), both `<div className="form-grid">`
  blocks inside `CollapsibleSection` → `<FormGrid>`, closing `</div>` →
  `</PageStack>`.

## Why `Panel` Wasn't Used Directly for the Payroll Detail Sidebar
The original markup is `<aside className="panel payroll-detail-panel">` — a
real `<aside>` (semantic landmark, distinct from `.panel`'s default
`<section>`). `Panel` in `Layout.jsx` unconditionally renders a `<section>`
and has **no `as` prop** (unlike `FormGrid`, which gained one in a prior PR).
Two options were considered:
1. Wrap a `Panel` (`<section>`) inside the `<aside>` — adds an extra DOM
   element/landmark nesting not present in the original markup.
2. Reproduce `Panel`'s exact Tailwind string as a local `PANEL_CLASS` const
   applied directly to the `<aside>`, and reuse `Panel.Header` (exported
   separately, renders a plain `<div>`) for the header row — chosen, since it
   keeps the exact original DOM shape (one `<aside>`, one header `<div>`) while
   still reusing `Layout.jsx`'s `Panel.Header` sub-component for the header
   markup itself.
This is the same "local-const reproduction" pattern established for
`FILTER_BAR_CLASS`/`FORM_GRID_CLASS` in `29_tw-convert-overtime-leave.md`,
now needed for `Panel` specifically. **Flagging as a known primitive gap**: if
a third page needs a non-`<section>` panel (e.g. `<aside>`, `<article>`),
`Layout.jsx`'s `Panel` should probably gain an `as` prop like `FormGrid`
already has — not done here since this is the first occurrence for `Panel`
and the task said only touch primitives if a real recurring gap is found.

## Legacy Classes Confirmed Still Shared With Frozen Pages (grep evidence)
```
$ grep -rln 'className="page-stack"' frontend/src
features/tickets/TicketListPage.jsx
features/tickets/TicketDetailPage.jsx
features/commissions/CommissionPage.jsx
features/dashboard/TicketDashboard.jsx
features/deposits/DepositNoticePage.jsx
features/ceoSettings/CeoSettingsPage.jsx
components/common/RouteFallback.jsx        # shared Suspense fallback, not a page

$ grep -rln 'className="panel"' frontend/src
features/tickets/TicketDetailPage.jsx
features/deposits/DepositNoticePage.jsx
features/commissions/CommissionPage.jsx
features/dashboard/TicketDashboard.jsx

$ grep -rln 'className="panel-header"' frontend/src
features/tickets/TicketDetailPage.jsx
features/commissions/CommissionPage.jsx
features/ceoSettings/CeoSettingsPage.jsx
features/deposits/DepositNoticePage.jsx
features/dashboard/TicketDashboard.jsx

$ grep -rln 'className="stat-grid"' frontend/src
features/commissions/CommissionPage.jsx
features/dashboard/TicketDashboard.jsx

$ grep -rln '"form-grid"' frontend/src
features/commissions/CommissionPage.jsx
features/tickets/TicketCreateModal.jsx

$ grep -rn 'filter-bar' frontend/src/features
(no matches — the exact `.filter-bar` class string is now used by ZERO pages;
only AttendancePage's/OvertimePage's/LeavePage's FILTER_BAR_CLASS *string
literal* remains, and PayrollPage's <FilterBar> component call)
```
**Correction to the task brief**: the brief said "PayrollPage keeps
`payroll-table` (shared with frozen ceoSettings)" — this was verified false by
grep. `gridClassName="payroll-table"` is used **only** by `PayrollPage.jsx`;
`ceoSettings`/`CommissionPage` use `commission-table`/`commission-payroll-table`
(different classes, similar name). `payroll-table` was left untouched anyway
per the explicit "do NOT convert that table" instruction, so this doesn't
change any action taken — just documenting the grep result since the task
asked to verify sharing before leaving classes as legacy.

## Classes Now Candidates for a Future CSS Cleanup Pass
Not deleted in this PR (deletion is a separate, later pass), but flagging for
that future pass:
- `.filter-bar` (the CSS rule itself, `styles.css:771`) — **zero remaining
  `className="filter-bar"` references** anywhere in `frontend/src` after this
  PR (AttendancePage/OvertimePage/LeavePage all now use a
  `FILTER_BAR_CLASS` *string copy* of the same utilities, not the class name;
  PayrollPage uses the `<FilterBar>` component). If no other page picks up a
  literal `.filter-bar` className before the cleanup pass, this CSS rule is
  dead and removable then — but double-check with a fresh grep at cleanup
  time, since `FILTER_BAR_CLASS`'s *string content* still depends on
  `.filter-bar`'s current visual spec staying in sync (the Tailwind utilities
  were copied from the CSS, not the other way around).
- `payroll-table` is NOT shared with any frozen page (see grep evidence
  above) — worth confirming in the cleanup PR whether it should eventually
  move to Tailwind too (out of scope here; the task explicitly said leave it).

## Commands Run
```bash
git status                                        # confirmed clean, on main-tracking branch
git checkout -b feat/tw-convert-attendance-payroll
cd frontend && npm install                        # worktree had no node_modules
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
git diff --stat -- frontend/src/styles.css                     # confirm empty
git diff --stat -- frontend/src/features/tickets frontend/src/features/deposits \
  frontend/src/features/commissions frontend/src/features/ceoSettings \
  frontend/src/features/dashboard/TicketDashboard.jsx           # confirm empty
git diff --stat                                                 # confirm only 2 files
grep -rln 'className="page-stack"\|className="panel"\|className="panel-header"\|
  className="stat-grid"\|"form-grid"\|filter-bar' frontend/src  # legacy-class sharing check
```
Plus a manually-started worktree-local `vite` dev server
(`VITE_USE_MOCKS=true npx vite --host 127.0.0.1 --port 5203 --strictPort`,
via the Bash tool's `run_in_background`) and the `frontend-mock` preview tab
redirected to it via `location.href` — reproducing the same preview-tooling
gotcha documented in every prior Phase-4 handoff (`preview_start`'s
`frontend-mock` config resolves against the **outer repo** checkout, not this
worktree; confirmed via `fetch()` of a just-edited file before trusting any
screenshot).

## Test / Build Results
- Lint: **pass** — `eslint src` → 0 errors, 9 pre-existing warnings (all
  `react-hooks/exhaustive-deps` in other/frozen pages — identical set to the
  baseline in every prior Phase-4 handoff, none introduced by this change).
- Tests: **pass** — `vitest run` → 17 test files, 84 tests, all green,
  including `PayrollPage.test.jsx` (2 tests, both still reference form
  field/label markup only, unaffected by the layout-only change).
- Build: **pass** — `vite build` → built in ~130ms, no errors.
  `dist/assets/AttendancePage-*.js` and `dist/assets/PayrollPage-*.js`
  emitted normally (14.15 kB / gzip 4.50 kB for Payroll).

## Browser Parity Verification

### PayrollPage — verified live in-browser
Logged in as HR demo role on the worktree-local vite server (port 5203, mock
API), navigated to `/payroll`:
- **Desktop**: screenshot confirms `PageStack`/`StatGrid` (4 cards)/`FilterBar`
  (period status row: "0 employees" + status badge + Preview/Process
  Payroll/Bank file buttons)/`DataTable`(unchanged, `payroll-table`
  gridClassName intact)/detail `<aside>` panel — pixel-identical to the
  pre-conversion markup. `preview_inspect` on the `StatGrid` confirmed
  `grid-template-columns: 268.5px 268.5px 268.5px 268.5px` (4 equal columns)
  with `reactComponent: "StatGrid"`. `preview_inspect` on the period-status
  row confirmed `className: "flex flex-wrap gap-[10px] items-center bg-surface
  border border-border rounded-md p-[14px] payroll-actions"`,
  `justify-content: flex-end` (from the `.payroll-actions` CSS rule still
  applying on top via plain className passthrough), `reactComponent:
  "FilterBar"` — confirms `<FilterBar className="payroll-actions">` correctly
  layers `.payroll-actions`'s CSS override on top of the primitive's base
  utilities. `preview_inspect` on the detail `<aside>` confirmed
  `className: "bg-surface border border-border rounded-md shadow-sm p-5
  payroll-detail-panel"`, computed `box-shadow` matching `.panel`'s shadow
  spec, `position: sticky; top: 18px` (from `.payroll-detail-panel`'s own
  CSS layering on top of the `PANEL_CLASS` reproduction) — confirms the
  local-const approach preserves both the base panel look and the
  page-specific sticky behavior.
- **Mobile 390px**: `DesktopOnlyNotice` renders (expected, `PayrollPage` is
  desktop-only per the v0.1.0 DoD). `preview_inspect` confirmed `StatGrid`
  collapses to `grid-template-columns: 358px` (1 column) and `.payroll-actions`
  keeps `flex-wrap: wrap; justify-content: flex-end` at this width.
- Mock data gap: the mock API returns zero payroll lines for the current
  month regardless of Preview/Refresh (pre-existing mock-data limitation, not
  a regression — matches the "mock-has-no-attendance/payroll-lines gotcha"
  noted in prior session memory), so the populated-detail-panel state
  (`selectedLine && selectedAdjustment` branch, including the two converted
  `FormGrid`s inside `CollapsibleSection`) could not be exercised with real
  data in this session. That branch was verified by source-level review
  instead: the JSX structure, `FormGrid`'s default `<div>` render (no `as`
  needed, these aren't native forms), and `Panel.Header`/`PANEL_CLASS` are
  unchanged from the verified empty-state render path, so the same styling
  applies once data is present.
- `preview_console_logs`: clean (only vite/React-DevTools debug/info noise,
  no errors or warnings) throughout.

### AttendancePage — could NOT verify live; verified via source parity instead
Confirmed the task brief's warning is accurate: navigating to `/attendance`
under mocks throws `Cannot read properties of undefined (reading 'devices')`
and trips the app's `ErrorBoundary` (full-page "Something went wrong" screen).
Root cause confirmed via `grep -n attendance frontend/src/api/mockApi.js` —
`mockApi.js` has no `attendance` section at all (only a `dashboard.attendance`
sub-key used by the dashboards, unrelated). Confirmed this is **pre-existing
and unrelated to this PR's changes** by `git stash`-ing my edits and
reproducing the identical crash against the unmodified `main`-tracking
`AttendancePage.jsx` (same file, same crash, same stack).

Verified instead via source-level parity, cross-checked against PayrollPage's
now browser-confirmed behavior (both files use the identical `Layout.jsx`
primitives with identical props):
- `<PageStack>` wraps the same children in the same order as the original
  `<div className="page-stack">`.
- `<StatGrid>` wraps the same 4 `StatCard`s in the same order as the original
  `<section className="stat-grid">` — `StatGrid`'s definition
  (`grid grid-cols-4 gap-[14px] max-[1040px]:grid-cols-2
  max-[720px]:grid-cols-1`) was already browser-confirmed correct via
  PayrollPage's identical `StatGrid` usage above.
- `FILTER_BAR_CLASS` is a byte-for-byte copy of `FilterBar`'s internal
  Tailwind string in `Layout.jsx` (`flex flex-wrap gap-[10px] items-center
  bg-surface border border-border rounded-md p-[14px]`), applied to the
  `<form onSubmit={submitFilters}>` exactly as done in `OvertimePage.jsx`/
  `LeavePage.jsx` (browser-verified correct in
  `29_tw-convert-overtime-leave.md`).
- `attendance-import-panel`, `DataTable`, and all columns/handlers are
  byte-identical to before (confirmed via `git diff`, zero changes to those
  lines).
No screenshot/computed-style evidence could be captured for this specific
page in this session due to the mock-data crash — flagging clearly for the
next agent in case a mock-API fix for `api.attendance` lands and this page
should be re-verified live at that point.

## Decisions Made
- Used the established "local-const reproduction" pattern
  (`FILTER_BAR_CLASS`, `PANEL_CLASS`) for the two places where a primitive
  couldn't be used directly (native `<form>` needing real submit semantics;
  `<aside>` needing to stay a semantic landmark) — consistent with
  `FILTER_BAR_CLASS`/`FORM_GRID_CLASS` in `29_tw-convert-overtime-leave.md`.
- Used `<FormGrid>` (default `<div>` render) for PayrollPage's two
  `form-grid` divs since neither needs native `<form>` semantics — they're
  plain layout containers holding `<label>`+input pairs inside a
  `CollapsibleSection`'s body, with the actual save action being the page's
  Preview/Process buttons elsewhere, not a submit on these divs.
- Used `<FilterBar className="payroll-actions">` (not a local-const
  reproduction) for PayrollPage's period-status row since it's a plain
  `<section>` in the original (no native-form constraint), same as
  `PayrollPage`'s own precedent-setting choice pattern in
  `26_tw-convert-dashboards.md`/`29_tw-convert-overtime-leave.md` for
  `<div>`/`<section>`-based bars.

## Assumptions
- The `frontend-mock` `.claude/launch.json` config (port 5200,
  `VITE_USE_MOCKS=true`) needed no changes; used a manually-started worktree
  `vite` server on port 5203 for reliable verification, per the gotcha
  documented in every prior Phase-4 handoff.
- Assumed the task brief's claim that `payroll-table` is "shared with frozen
  ceoSettings" was directional guidance to be cautious, not a hard fact to
  preserve verbatim — grep disproved it (see "Correction to the task brief"
  above), but the outcome (leave `payroll-table` untouched) is unchanged
  either way since the task explicitly forbade converting that table
  regardless of sharing status.

## Known Risks
- **AttendancePage could not be verified live in-browser this session** due
  to the pre-existing `mockApi.js` gap (no `attendance` section) crashing the
  page via `ErrorBoundary`. Verification relied on source-level parity
  against the browser-confirmed `PayrollPage` (same primitives, same
  patterns). If this mock gap is ever fixed, a follow-up live verification of
  `AttendancePage` (both the filter-bar/stat-grid layout AND the
  `attendance-import-panel`/`DataTable` interaction) would be worth doing,
  though risk is low given the mechanical nature of the swap and the
  byte-identical `FILTER_BAR_CLASS` string.
- **`Panel` still has no `as` prop.** This PR is the first to need a
  non-`<section>` panel (`<aside>`); worked around via the `PANEL_CLASS`
  local-const pattern (see "Why `Panel` Wasn't Used Directly" above). If a
  third page needs this, consider adding an `as` prop to `Panel` in
  `Layout.jsx` (mirroring `FormGrid`'s existing `as` prop) rather than a third
  local-const copy.
- PayrollPage's populated-detail-panel state (with real payroll lines
  selected) was not exercised live due to the mock API returning zero lines
  for any month tried — the empty-state (`EmptyState` component) render path
  was the only one verified live. Source review confirms the populated branch
  uses the same primitives/props as the verified empty-state wrapper, so risk
  is low, but flagging since it's not screenshot-confirmed.
- `payroll-table` gridClassName is confirmed NOT shared with any frozen page
  (contradicts the task brief's assumption) — noted above; does not change
  any action taken, but worth knowing for the next agent/cleanup pass.

## Things Not Finished
- This was the LAST scheduled page-chrome conversion per the Phase 4 order
  (per the task framing: "the LAST two page conversions"). No further
  per-page primitive-swap work is expected — the next Phase 4 step should be
  the CSS cleanup pass (grep-verify and delete now-dead legacy classes from
  `styles.css`), which was already flagged as option (b) in
  `29_tw-convert-overtime-leave.md`'s exact-next-prompt and remains
  unstarted.
- `.toolbar-actions`, `.payroll-workspace`, `.payroll-detail-grid`,
  `.payroll-special-grid`, `.payroll-breakdown`, `.mini-metric`,
  `.currency-input`(+`-symbol`), `.attendance-import-panel`(+`-device`/
  `-result`), `.collapsible-total`, `.confirm-dialog-message` — all left as
  legacy classes on both pages (not in this PR's named primitive list; their
  CSS is untouched so parity is unaffected).
- No CSS classes were deleted (per the "additive/parity-first" constraint —
  deletion is the final cleanup pass once all non-frozen pages are converted,
  which is now the case as of this PR).

## Recommended Next Agent
Claude Sonnet (implementation) — the CSS cleanup pass (Phase 4 wrap-up).

## Exact Next Prompt
```
You are the single implementation agent for one branch on the GL-R-ERP repo,
in an isolated git worktree. This is the CSS cleanup pass that wraps up
Phase 4 of the Tailwind style migration — ALL non-frozen HR-core pages have
now been converted to the Layout.jsx primitives (see
docs/agent-handoffs/25_tw-primitives-profile.md through
docs/agent-handoffs/32_tw-convert-attendance-payroll.md for the full history).

Task: grep-verify and delete now-dead legacy CSS classes from
frontend/src/styles.css. Candidates (verify each with a fresh grep across
frontend/src before deleting — do not trust this list blindly, some classes
may still be used as *string literals* copied into local consts like
FILTER_BAR_CLASS/PANEL_CLASS/FORM_GRID_CLASS, which is fine to leave as-is
since those are just Tailwind utility strings, not CSS class references):
- .page-stack, .panel, .panel-header, .form-grid, .stat-grid, .filter-bar,
  .row-actions, .span-2 — ONLY delete if zero remaining
  className="<exact-name>" (or className="<exact-name> <other>") references
  exist outside frozen pages (tickets/deposits/commissions/ceoSettings/
  TicketDashboard) and outside components/common/RouteFallback.jsx (uses
  .page-stack directly, is a shared Suspense fallback, not a page — check
  whether to convert it to <PageStack> too, low risk, in scope for this
  cleanup pass since it's non-frozen).
- Per-table classes flagged as dead in docs/agent-handoffs/28_tw-table-grids.md
  (.employee-table, .attendance-table, .overtime-table, .leave-table,
  .request-table.mine) — ONLY if grep confirms zero remaining
  className/gridClassName references.
- Do NOT remove: .reflow-cards, .request-table (bare), .payroll-table
  (confirmed NOT shared with any frozen page per
  docs/agent-handoffs/32_tw-convert-attendance-payroll.md, but explicitly
  out of scope for conversion per the original task — leave its CSS alone
  unless a future task converts that table), .user-table, .commission-table,
  .commission-payroll-table (sales-stack, frozen), .toolbar-actions,
  .payroll-actions, .payroll-workspace, .payroll-detail-panel,
  .payroll-detail-grid, .payroll-special-grid, .payroll-breakdown,
  .mini-metric, .currency-input(-symbol), .attendance-import-panel(-device/
  -result), .collapsible-total, .confirm-dialog-message (none of these were
  in the primitive-swap scope of any Phase 4 PR — still live, not dead).

First read (in order): CLAUDE.md, docs/agent-handoffs/00_MASTER_CONTEXT.md,
docs/agent-handoffs/29_tw-convert-overtime-leave.md, and
docs/agent-handoffs/32_tw-convert-attendance-payroll.md (this file) in full.

Hard constraints: do not touch frozen pages, do not change any non-CSS file
except RouteFallback.jsx if you choose to convert it, do not delete a class
until grep confirms zero remaining references (className or gridClassName)
anywhere in frontend/src, do not commit until verification passes.

Verify: cd frontend && npm run lint && npm test && npm run build (0 lint
errors, 9 pre-existing warnings, tests+build green), then a full visual
sweep of every non-frozen page (dashboards, employees, attendance, overtime,
leave, payroll, profile/requests) at desktop + mobile 390px to confirm
nothing visually broke from the CSS deletion, console clean. Update/create
docs/agent-handoffs/33_<branch-name>.md before ending, commit, and push (no
PR).
```
