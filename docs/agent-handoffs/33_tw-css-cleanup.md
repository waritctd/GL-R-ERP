# Agent Handoff

## Task
Phase 4 CAPSTONE: grep-verify and delete now-unused non-frozen-only CSS
classes from `frontend/src/styles.css`, left dead by the Tailwind
conversion PRs (`25_tw-primitives-profile.md` through
`32_tw-convert-attendance-payroll.md`). Pure-deletion cleanup — no `.jsx`
changed, no markup changed. Every deletion is grep-proven zero-usage across
`frontend/src` before removal.

## Branch
`feat/tw-css-cleanup`

## Base Commit
`85d8984` (main, includes merged PR #148 "convert Attendance + Payroll
layout to Tailwind primitives")

## Current Commit
Committed and pushed at the end of this session (see `git log -1` on this
branch). No PR opened, per instructions.

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- `frontend/src/styles.css` only. Deleted 9 dead classes (base rules +
  their `@media (max-width: 1040px)` min-width membership +
  `@media (max-width: 720px)` card-reflow membership, where applicable):
  `.detail-hero` (+`.compact`, `h1`/`p` combinators), `.dashboard-grid`,
  `.info-grid` (+`.two`), `.field-list` (+`.three`, `dt`/`dd` rules),
  `.filter-bar` (+`select` sub-rule), `.leave-balance-grid`,
  `.employee-table`, `.attendance-table`, `.overtime-table`,
  `.leave-table`, `.request-table.mine`.

### Out of Scope / Not Touched
- No `.jsx` file touched (verified via `git diff --stat` — only
  `styles.css` appears).
- No frozen page touched (tickets/deposits/commissions/ceoSettings/
  TicketDashboard) — confirmed via `git diff --stat` and via a live browser
  check that every shared/frozen class survives in the served stylesheet
  (see Verify section).
- `.reflow-cards`, `.request-table` (bare), `.payroll-table`,
  `.user-table`, `.commission-table`, `.commission-payroll-table`,
  `.ticket-table`, `.ticket-items-table`, `.page-stack`, `.panel`,
  `.panel-header`, `.table-panel`, `.form-grid`, `.stat-grid`, `.span-2`,
  `.page-heading`, `.page-actions`, `.profile-strip`, `.row-actions` — all
  kept, all grep-confirmed still live (see table below).
- a11y focus-visible/reduced-motion polish and unifying
  `FILTER_BAR_CLASS`/`PANEL_CLASS` local consts onto `as`-prop primitives —
  explicitly deferred, see Recommended Next Agent.

## Class → Verdict Table

| Class | className/gridClassName usage count (frontend/src) | Verdict | Where still used (if KEPT) |
|---|---|---|---|
| `detail-hero` | 0 | DELETE | — (only `DetailHero` component comment references + Tailwind reproduction in `components/common/FieldList.jsx`) |
| `profile-strip` | 1 | KEEP | `features/dashboard/EmployeeDashboard.jsx:116` |
| `page-actions` | 2 | KEEP | `components/common/ErrorBoundary.jsx:42`, `components/common/PageHeader.jsx:8` |
| `dashboard-grid` | 0 | DELETE | — (comment-only references in `HrDashboard.jsx`/`EmployeeDashboard.jsx`, both already on Tailwind) |
| `info-grid` (+`.two`) | 0 | DELETE | — (comment-only reference in `FieldList.jsx`'s `InfoGrid` component, which is pure Tailwind) |
| `field-list` (+`.three`) | 0 | DELETE | — (comment-only references in `FieldList.jsx`'s `FieldList` component, which is pure Tailwind) |
| `filter-bar` | 0 | DELETE | — (`AttendancePage`/`OvertimePage`/`LeavePage` use a `FILTER_BAR_CLASS` Tailwind-string const, not the literal class; `PayrollPage` uses the `<FilterBar>` Tailwind component) |
| `row-actions` | 2 | KEEP | `features/overtime/OvertimePage.jsx:530`, `features/leave/LeavePage.jsx:613` (as a literal `<span className="row-actions">`) |
| `leave-balance-grid` | 0 | DELETE | — (0 usages anywhere; `.leave-balance-card`, a different class, is still live and untouched) |
| `employee-table` | 0 real (9 hits, all `DataTable.test.jsx` string-literal test fixtures) | DELETE | test fixture usage is an arbitrary string, not a CSS dependency — left as-is |
| `attendance-table` | 0 | DELETE | — |
| `overtime-table` | 0 | DELETE | — |
| `leave-table` | 0 | DELETE | — |
| `request-table` (bare) | 2 | KEEP | `features/profileRequests/ProfileRequestsPage.jsx:18,31` |
| `request-table.mine` | 0 | DELETE | — |
| `page-stack` | 7 (non-test) | KEEP | frozen: tickets/commissions/ceoSettings/deposits/TicketDashboard; shared: `components/common/RouteFallback.jsx` |
| `panel` | many | KEEP | frozen pages + `components/common/DataTable.jsx`, `ErrorBoundary.jsx`, `Modal.jsx`, non-frozen `AttendancePage`/`ProfileRequestsPage`/`auth`/`OvertimePage`/`LeavePage`/`EmployeeDetailPage` |
| `panel-header` | 5 | KEEP | frozen: commissions/tickets/ceoSettings/deposits/TicketDashboard |
| `table-panel` | many | KEEP | frozen + `components/common/DataTable.jsx` + non-frozen `ProfileRequestsPage`/`MyRequestsPage`/`OvertimePage`/`LeavePage` |
| `form-grid` | 2 | KEEP | frozen: `commissions/CommissionPage.jsx`, `tickets/TicketCreateModal.jsx` |
| `stat-grid` | 2 | KEEP | frozen: `commissions/CommissionPage.jsx`, `dashboard/TicketDashboard.jsx` |
| `span-2` | many | KEEP | frozen: `commissions/CommissionPage.jsx`, `tickets/TicketCreateModal.jsx` (+ `Layout.jsx`'s `formGridSpan2` doc-comment) |
| `payroll-table` | 1 | KEEP | `features/payroll/PayrollPage.jsx:329` (gridClassName) — confirmed NOT shared with any frozen page (the `payroll-table` hit inside `CommissionPage.jsx` is actually `commission-payroll-table`, a substring trap) |
| `page-heading` | owned by `PageHeader` | KEEP | `components/common/PageHeader.jsx` |

All 9 DELETE classes were removed from: their desktop rule (or, for
`.detail-hero`/`.field-list`, their `h1`/`p`/`dt` combinator entries in
shared selector lists), their `@media (max-width: 1040px)` min-width-900px
group membership (for the 5 table classes), and their
`@media (max-width: 720px)` HR-core card-fallback group membership (for
the 5 table classes, across all 6 selector-list rules:
min-width-reset/table-head-hide/table-row-card/margin/span-block/
data-label-pseudo/row-actions-flex). `.reflow-cards` and every KEPT
selector (`.request-table` bare, `.payroll-table`, `.user-table`,
`.commission-table`, `.commission-payroll-table`) were left fully intact
in every group they belonged to.

## Files Changed
- `frontend/src/styles.css` — 2089 → 1950 lines (139 lines removed net;
  4 insertions / 143 deletions per `git diff --stat`). Brace count verified
  balanced (281 `{` / 281 `}`) both before and after.

## Commands Run
```bash
git checkout -b feat/tw-css-cleanup   # off origin/main @ 85d8984, in the isolated worktree
cd frontend && npm install            # worktree had no node_modules
grep -rn "<each candidate class>" frontend/src        # per-class verification, see table
find frontend/src -name "*.jsx" | xargs grep -n 'className="[^"]*\b<class>\b[^"]*"'  # precise word-boundary check
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
git diff --stat                        # confirm only styles.css touched
wc -l frontend/src/styles.css          # 1950 (was 2089)
python3 -c "content=open('frontend/src/styles.css').read(); print(content.count('{'), content.count('}'))"  # 281/281
```
Plus a manually-started worktree-local `vite` dev server
(`VITE_USE_MOCKS=true npx vite --host 127.0.0.1 --port 5210 --strictPort`)
and the `frontend-mock` preview tab redirected to it via `location.href` —
reproducing the same preview-tooling gotcha documented in every prior
Phase-4 handoff (`preview_start`'s `frontend-mock` config resolves against
the **outer repo** checkout, not this worktree). Verified via
`fetch('/src/styles.css')` containing/excluding expected marker strings
before trusting any screenshot.

**Worktree gotcha hit this session**: the Bash tool's default cwd
persisted an earlier accidental `cd /Users/ploy_warit/Desktop/GL-R-ERP`
(the *outer* repo, a different physical path from the isolated worktree
`/Users/ploy_warit/Desktop/GL-R-ERP/.claude/worktrees/agent-aee0cb460b719d0b7`
despite an identical-looking absolute path string) — a branch was briefly
created in the outer repo by mistake, immediately caught (the `Edit` tool
refused with an isolation error), reverted (`git checkout main && git
branch -d feat/tw-css-cleanup` in the outer repo, zero commits lost), and
redone correctly in the worktree. `pwd -P` was used to confirm the true
physical cwd going forward. No outer-repo file was ever modified.

## Test / Build Results
- Lint: **pass** — `eslint src` → 0 errors, 9 pre-existing warnings (all
  `react-hooks/exhaustive-deps` in other/frozen pages — identical set to
  the baseline in every prior Phase-4 handoff, none introduced by this
  change).
- Tests: **pass** — `vitest run` → 17 test files, 84 tests, all green
  (includes `DataTable.test.jsx`'s 9 `employee-table` string-literal
  fixtures, unaffected since that's just an arbitrary prop value, not a
  CSS dependency).
- Build: **pass** — `vite build` → built in ~126ms, no errors, all chunks
  emitted normally.

## Browser Verification
Manually-started worktree `vite` server (port 5210, mock API), `styles.css`
fetch-verified to be this worktree's copy before trusting anything:
- **HR demo login → Dashboard**: `.profile-strip` renders correctly
  (unaffected by `.detail-hero` deletion, which was split out of that
  shared rule).
- **Employees list** (desktop + 390px mobile): `EmployeeListPage`'s
  Tailwind `grid-cols-[...]` + `.reflow-cards` (from a prior PR) is
  unaffected by deleting the now-dead `.employee-table` CSS — desktop grid
  and mobile card-reflow (head hidden, labeled cards) both confirmed via
  screenshot.
- **Employee detail page** (desktop + 390px mobile): `DetailHero`/
  `InfoGrid`/`FieldList` Tailwind primitives (already converted in prior
  PRs) render pixel-correct with the underlying `.detail-hero`/
  `.info-grid`/`.field-list` CSS now deleted — hero card, tabs, personal-info
  field-list (label/value pairs) all correct at both widths.
- **Leave page** (desktop): stat cards, leave-balance cards (own class,
  untouched), filter row (via `FILTER_BAR_CLASS` const, unaffected by
  `.filter-bar` deletion) all render correctly.
- **Overtime page** (desktop + 390px mobile): form, stat cards, and the
  `OVERTIME_TABLE_GRID` + `.reflow-cards` table (unaffected by
  `.overtime-table` deletion) all render/reflow correctly — table-row card
  with all data-labels and status badge confirmed at mobile width.
- **Payroll page** (desktop): stat cards, filter/actions row, `.payroll-table`
  (kept, untouched) all render correctly.
- **My Requests page** (Employee demo login, desktop + 390px mobile):
  `MY_REQUESTS_TABLE_GRID` + `.reflow-cards` table (unaffected by
  `.request-table.mine` deletion) renders correctly at both widths,
  including the pre-existing no-data-label mobile card layout (documented,
  unrelated quirk from `28_tw-table-grids.md`).
- **Login screen**: `.login-panel`/`.login-brand`/`.login-form` (untouched,
  but shares combinator rules with the now-partially-modified
  `.page-heading`/`.detail-hero` selector groups) renders correctly —
  confirms the `.login-form h1`/`.login-form p` rules survived the
  `.detail-hero h1`/`.detail-hero p` removal from the same selector lists.
- **Programmatic stylesheet check** (most rigorous check, run via
  `preview_eval` + `fetch('/src/styles.css')`): confirmed **every** KEPT
  class-selector substring is present (`panel`, `panel-header`, `stat-grid`,
  `span-2`, `form-grid`, `page-stack`, `table-panel`, `payroll-table`,
  `commission-table`, `commission-payroll-table`, `user-table`,
  `ticket-table`, `request-table {` (bare), `row-actions`, `page-actions`,
  `profile-strip`, `reflow-cards`) and **every** DELETE class-selector
  substring is absent (`detail-hero`, `filter-bar`, `info-grid`,
  `field-list`, `dashboard-grid`, `employee-table`, `attendance-table`,
  `overtime-table`, `leave-table`, `.mine`, `leave-balance-grid`) in the
  live-served stylesheet.
- Frozen sales pages were **not** reachable live in this session
  (`VITE_ENABLE_SALES=false` by default per the v0.1.0 DoD — sales nav is
  intentionally hidden). Verified their shared-class dependency instead via
  the grep table above (every frozen-shared class KEPT and confirmed
  present in the served stylesheet) — this is the documented/expected
  method per the task brief ("at minimum confirm the frozen files' classes
  you KEPT are still present in styles.css").
- Console: clean on the actual worktree server (port 5210) throughout —
  confirmed via `document.body.textContent` not containing "Something went
  wrong" and `location.href` checks after every navigation. The
  `preview_console_logs` tool repeatedly surfaced a **stale, historical**
  error buffer from an unrelated leftover session on port 5203 (the
  documented pre-existing `mockApi.js`-has-no-`attendance`-section bug from
  `32_tw-convert-attendance-payroll.md`) — every one of those log lines'
  stack traces reference `127.0.0.1:5203`, never `5210` (this session's
  server), confirming they are not from this session's navigation and are
  unrelated to this PR's CSS-only change.

## Decisions Made
- Kept `field-list dt` deletion scoped to removing just that one selector
  from the shared `.nav-item small, .employee-cell small, ... .field-list
  dt, .request-feed small, .table-row small { ... }` list, since
  `FieldList.jsx`'s Tailwind reproduction already inlines the equivalent
  `dt` styling directly (`[&_dt]:m-0 [&_dt]:block ...`) rather than relying
  on the descendant selector at runtime — confirmed by reading the
  component before deleting.
- Split `.detail-hero`/`.profile-strip` combined base rules apart rather
  than deleting the whole rule, since `.profile-strip` is still live
  (`EmployeeDashboard.jsx`) while `.detail-hero` is fully dead — same
  approach applied everywhere `.detail-hero`/`.dashboard-grid`/`.field-list`/
  `.info-grid`/`.filter-bar` shared a selector list with a still-live class
  (e.g. `.login-form h1, .page-heading h1, .detail-hero h1` → removed only
  the `.detail-hero h1` line).
- Verified `.payroll-table`'s one match inside `CommissionPage.jsx` was a
  substring trap (`commission-payroll-table`, a different class) before
  concluding `payroll-table` (bare) has exactly one real consumer
  (`PayrollPage.jsx`) — matches the correction already documented in
  `32_tw-convert-attendance-payroll.md`.
- Did not attempt to fix or route around the stale port-5203 log buffer
  noise — cross-verified cleanliness a different way (DOM content check +
  URL-scoped stack traces) since restarting/killing unrelated sessions felt
  out of scope for a CSS-deletion PR.

## Assumptions
- Interpreted "grep-verify each candidate class" as requiring both a broad
  substring grep (to catch any usage form) and a precise
  `className="[^"]*\bclass\b[^"]*"` word-boundary grep (to rule out
  substring traps like `payroll-table` vs `commission-payroll-table`, or
  `panel` vs `panel-header`) — used both for every class before deciding.
- Treated `DataTable.test.jsx`'s `gridClassName="employee-table"` as safe
  to leave alone (per the task's own guidance: "safe to leave as-is or
  rename in the test file, agent's call") since it's an arbitrary test
  fixture string, not a real CSS class dependency — did not rename it,
  since renaming a passing test's fixture string is an unnecessary risk
  for a pure-CSS-deletion PR.
- Assumed the frozen sales stack being unreachable in the default mock
  preview (`VITE_ENABLE_SALES=false`) was expected/by-design (matches
  `00_MASTER_CONTEXT.md`'s v0.1.0 DoD: "frozen sales/CRM stack flag-hidden
  ... v0.1.0 ships cleanly HR-core") rather than something to work around
  by flipping the flag — used the task's own documented fallback
  ("at minimum confirm the frozen files' classes you KEPT are still
  present in styles.css") instead.

## Known Risks
- None identified specific to this change — it is a pure CSS deletion with
  zero JSX touched, verified by (a) exhaustive grep before every deletion,
  (b) 84/84 tests passing, (c) a clean production build, and (d) live
  browser confirmation across every converted non-frozen page at desktop
  and mobile widths, plus a programmatic full-stylesheet content check
  proving every kept class is present and every deleted class is absent.
- The stale port-5203 log-buffer noise (see Browser Verification) could
  confuse a future agent reading raw `preview_console_logs` output without
  checking the URL in each stack trace — flagging clearly so it isn't
  mistaken for a regression introduced by this PR.

## Things Not Finished
- The two optional polish items named in the task brief were intentionally
  left for a future PR (out of scope for this pure-deletion cleanup):
  1. a11y focus-visible / prefers-reduced-motion audit across the
     Tailwind-converted primitives (`Layout.jsx` components).
  2. Unifying the `FILTER_BAR_CLASS`/`PANEL_CLASS` local-const
     Tailwind-string-reproduction pattern (used in `AttendancePage.jsx`,
     `OvertimePage.jsx`, `LeavePage.jsx`, `PayrollPage.jsx`) onto an
     `as`-prop on the `FilterBar`/`Panel` primitives in `Layout.jsx`
     (mirroring `FormGrid`'s existing `as` prop) — flagged as a known gap
     in `32_tw-convert-attendance-payroll.md`'s "Known Risks" section,
     still applicable.
- `ProfileRequestsPage.jsx`'s bare `.request-table` (6-column HR-review
  variant) remains on the legacy CSS-class pattern — intentionally kept
  (still live, not dead), and was flagged in `28_tw-table-grids.md` as a
  natural follow-up Tailwind-conversion target if desired, independent of
  this CSS-cleanup PR.

## Recommended Next Agent
This was the last scheduled step of the Phase 4 Tailwind migration
sequence (the "CAPSTONE" cleanup). No further mandatory Phase 4 work
remains. Optional follow-ups, in priority order:
1. (Low effort) a11y focus-visible / reduced-motion audit across
   `Layout.jsx` primitives.
2. (Low-medium effort) Add an `as` prop to `FilterBar`/`Panel` in
   `Layout.jsx` and migrate `FILTER_BAR_CLASS`/`PANEL_CLASS` local consts
   onto it, eliminating the string-duplication pattern.
3. (Medium effort, new scope) Convert `ProfileRequestsPage.jsx`'s bare
   `.request-table` to the same `grid-cols-[...]` + `.reflow-cards`
   Tailwind pattern used everywhere else, then this cleanup could run
   again to delete `.request-table` (bare) too.

## Exact Next Prompt
```
You are the single implementation agent for one branch on the GL-R-ERP
repo, in an isolated git worktree. The Phase 4 Tailwind CSS migration is
now fully complete (see docs/agent-handoffs/25_tw-primitives-profile.md
through docs/agent-handoffs/33_tw-css-cleanup.md for the full history) —
all non-frozen HR-core pages are converted to Layout.jsx primitives and
all now-dead legacy CSS classes have been deleted from
frontend/src/styles.css (2089 → 1950 lines).

Two small optional polish items remain, flagged in
docs/agent-handoffs/33_tw-css-cleanup.md's "Things Not Finished":
(a) an a11y focus-visible/prefers-reduced-motion audit across the
    Layout.jsx primitives (PageStack/Panel/FormGrid/StatGrid/FilterBar/
    RowActions/FieldList/InfoGrid/DetailHero) and the pages that use them,
(b) adding an `as` prop to Layout.jsx's FilterBar and Panel components
    (mirroring FormGrid's existing `as` prop) and migrating the
    FILTER_BAR_CLASS/PANEL_CLASS local-const string-duplication pattern in
    AttendancePage.jsx/OvertimePage.jsx/LeavePage.jsx/PayrollPage.jsx onto
    it.

Pick ONE (or ask the user which is wanted — neither is urgent, both are
small/contained). First read CLAUDE.md, docs/agent-handoffs/00_MASTER_CONTEXT.md,
and docs/agent-handoffs/33_tw-css-cleanup.md (this file) in full.

Hard constraints: do not touch frozen pages, keep changes small and
reviewable, do not commit until lint+test+build pass and a live browser
check confirms no regression at desktop + mobile 390px, console clean.

Verify: cd frontend && npm run lint && npm test && npm run build (0 lint
errors, 9 pre-existing warnings, tests+build green). Update/create
docs/agent-handoffs/34_<branch-name>.md before ending, commit, and push (no
PR).
```
