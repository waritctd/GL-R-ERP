# Agent Handoff

## Task
Phase 4 of the Tailwind style migration, the TABLE-GRID refactor: move each
NON-FROZEN table's desktop `grid-template-columns` from a legacy per-table
CSS class (`employee-table`, `attendance-table`, `overtime-table`,
`leave-table`, `request-table.mine`) to a Tailwind `grid-cols-[...]`
arbitrary-value utility on the `gridClassName` prop/className, and replace
the 6 duplicated ≤720px mobile card-reflow blocks in `styles.css` with ONE
generic `.reflow-cards` marker class, added additively (old per-table
classes and their CSS are NOT deleted in this PR — that's a later
grep-verified cleanup PR).

## Branch
`feat/tw-table-grids`

## Base Commit
`57b75d1` (main, includes merged PR #143 `feat/tw-kill-inline-styles`)

## Current Commit
Committed at the end of this session (see `git log -1` on this branch after
the commit step below) and pushed. No PR opened, per instructions.

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- `frontend/src/styles.css` — added `.reflow-cards` to the existing
  `@media (max-width: 1040px)` `min-width: 900px` selector group, and to all
  6 selector groups inside the existing `@media (max-width: 720px)` HR-core
  card-fallback block (min-width override, `.table-head` hide, `.table-row`
  card layout, `.table-row + .table-row` margin, `> span` block layout,
  `> span[data-label]::before` label content, `> .row-actions` flex row).
  100% additive — no existing rule/selector removed or edited, confirmed via
  `git diff` (only `+` lines, each appending `.reflow-cards`/
  `.reflow-cards.table-head`/`.reflow-cards.table-row` etc. to an existing
  comma-separated selector list).
- `frontend/src/features/employees/EmployeeListPage.jsx` — `DataTable`
  `gridClassName="employee-table"` → Tailwind arbitrary grid + `.reflow-cards`.
- `frontend/src/features/attendance/AttendancePage.jsx` — `DataTable`
  `gridClassName="attendance-table"` → Tailwind arbitrary grid + `.reflow-cards`.
- `frontend/src/features/overtime/OvertimePage.jsx` — hand-rolled
  `<div className="overtime-table table-head/table-row">` (this page does
  NOT use `DataTable`) → a new `OVERTIME_TABLE_GRID` const (same pattern as
  `DASHBOARD_GRID` in `26_tw-convert-dashboards.md`) applied to both the
  head and row divs via template literal.
- `frontend/src/features/leave/LeavePage.jsx` — same pattern, new
  `LEAVE_TABLE_GRID` const, applied to both head/row divs (this page also
  does NOT use `DataTable`).
- `frontend/src/features/profile/MyRequestsPage.jsx` — same pattern, new
  `MY_REQUESTS_TABLE_GRID` const (the `.mine` variant of `request-table`),
  applied to both head/row divs (also NOT `DataTable`).

### Out of Scope / Not Touched
- `frontend/src/features/payroll/PayrollPage.jsx` and the `.payroll-table`
  CSS class — untouched, confirmed via `git diff --stat` (file does not
  appear in the diff). Still shared with frozen `ceoSettings`.
- `frontend/src/features/profileRequests/ProfileRequestsPage.jsx` — uses
  the **non-`.mine`** `request-table` class (6-column HR-review variant,
  with an "Employee" avatar column and approve/reject buttons instead of a
  status badge). This page was **not** in the task's named 5-page list
  (`EmployeeListPage`, `AttendancePage`, `OvertimePage`, `LeavePage`,
  `MyRequestsPage`) and was left on the legacy `.request-table` class.
  Flagging clearly: `.request-table` (the bare, non-`.mine` selector) is
  therefore **still live/used** and must NOT be deleted in the future
  CSS-cleanup PR — only `.request-table.mine` became dead.
- All frozen sales/CRM pages (`tickets`, `deposits`, `commissions`,
  `ceoSettings`, `TicketDashboard.jsx`) — confirmed untouched via
  `git diff --stat` (none appear).
- `frontend/src/components/common/DataTable.jsx` — no change was needed;
  it already applies `gridClassName` as a plain string to both the head and
  row `className`, so it works unchanged with Tailwind utility strings.

## Files Changed
- `frontend/src/styles.css` — 24 insertions, 16 deletions (all
  deletions are lines being replaced by themselves + `,\n  .reflow-cards`,
  i.e. purely additive; see diff in Decisions Made / commands below).
- `frontend/src/features/employees/EmployeeListPage.jsx` — 1 line changed
  (`gridClassName` value).
- `frontend/src/features/attendance/AttendancePage.jsx` — 1 line changed
  (`gridClassName` value).
- `frontend/src/features/overtime/OvertimePage.jsx` — added
  `OVERTIME_TABLE_GRID` const + 2 className call sites updated.
- `frontend/src/features/leave/LeavePage.jsx` — added `LEAVE_TABLE_GRID`
  const + 2 className call sites updated.
- `frontend/src/features/profile/MyRequestsPage.jsx` — added
  `MY_REQUESTS_TABLE_GRID` const + 2 className call sites updated.

## Exact gridClassName Before/After
| Page | Before | After |
|---|---|---|
| EmployeeListPage | `employee-table` | `grid-cols-[minmax(0,2.2fr)_minmax(0,0.9fr)_minmax(0,1.7fr)_minmax(0,1.1fr)_minmax(0,1fr)_minmax(0,0.9fr)] max-[1040px]:min-w-[900px] reflow-cards` |
| AttendancePage | `attendance-table` | `grid-cols-[1.35fr_1.5fr_0.8fr_1.2fr_0.8fr_1.15fr] max-[1040px]:min-w-[900px] reflow-cards` |
| OvertimePage | `overtime-table` | `grid-cols-[minmax(0,1.25fr)_minmax(0,1.45fr)_minmax(0,1.55fr)_minmax(0,1fr)_minmax(0,0.75fr)_minmax(0,0.8fr)] max-[1040px]:min-w-[900px] reflow-cards` |
| LeavePage | `leave-table` | `grid-cols-[minmax(0,1.35fr)_minmax(0,1.1fr)_minmax(0,1.65fr)_minmax(0,0.75fr)_minmax(0,1.35fr)_minmax(0,0.8fr)] max-[1040px]:min-w-[900px] reflow-cards` |
| MyRequestsPage | `request-table mine` | `grid-cols-[minmax(0,1.2fr)_minmax(0,2fr)_minmax(0,2fr)_minmax(0,0.9fr)_minmax(0,1fr)] max-[1040px]:min-w-[900px] reflow-cards` |

Column ratios were copied verbatim from `styles.css` lines ~874-920
(`employee-table`/`request-table.mine`/`attendance-table`/`overtime-table`/
`leave-table`), preserving the exact `minmax(0,Nfr)` wrapper where the
original had it (`employee-table`, `overtime-table`, `leave-table`,
`request-table.mine`) and the bare `Nfr` where it didn't
(`attendance-table` — its original rule has no `minmax()` wrapper at all).
The `max-[1040px]:min-w-[900px]` utility reproduces the ≤1040px
`min-width: 900px` horizontal-scroll-trap rule that applied to all 6
legacy classes (now also applied via `.reflow-cards` in `styles.css` for
belt-and-suspenders parity, so it's present twice — once per-class via
Tailwind, once via the generic marker — which is harmless, both compute
the same value).

## The Generic `.reflow-cards` Rule Added
In `frontend/src/styles.css`, inside the existing
`@media (max-width: 1040px)` block:
```css
.employee-table, .attendance-table, .request-table, .request-table.mine,
.user-table, .commission-table, .commission-payroll-table, .payroll-table,
.overtime-table, .leave-table,
.reflow-cards {          /* <-- added */
  min-width: 900px;
}
```
And inside the existing `@media (max-width: 720px)` "HR-core list card
fallback" block, `.reflow-cards` (and `.reflow-cards.table-head` /
`.reflow-cards.table-row` / `.reflow-cards.table-row + .table-row` /
`.reflow-cards.table-row > span` / `.reflow-cards.table-row >
span[data-label]:not([data-label=""])::before` / `.reflow-cards.table-row >
.row-actions`) were appended to each of the 6 existing selector groups,
verbatim reproducing: min-width reset to 0, hiding the head, turning rows
into single-column bordered/rounded cards, 10px row spacing, block-display
cells, the `content: attr(data-label)` pseudo-label, and the flex-wrap
row-actions layout. No new declarations were invented — every property
value was copied from the existing block.

## Commands Run
```bash
git checkout -b feat/tw-table-grids   # off origin/main @ 57b75d1
cd frontend && npm install            # worktree had no node_modules
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
git diff --stat -- frontend/src/features/tickets frontend/src/features/deposits \
  frontend/src/features/commissions frontend/src/features/ceoSettings \
  frontend/src/features/payroll frontend/src/features/dashboard/TicketDashboard.jsx
  # confirmed empty
git diff --stat                       # confirm only the 6 intended files touched
grep -rn "employee-table\|attendance-table\|overtime-table\|leave-table\|request-table" \
  frontend/src/features frontend/src/components
  # confirm which legacy classes are now dead vs still live
```
Plus a manually-started `vite` dev server from **this worktree**
(`VITE_USE_MOCKS=true npx vite --host 127.0.0.1 --port 5201 --strictPort`)
and a `frontend-verify` preview tab navigated to it via `preview_eval`'s
`location.href`. **Reproduced the same preview-tooling gotcha documented in
`27_tw-kill-inline-styles.md`**: `mcp__Claude_Preview__preview_start` for
both `frontend-mock` and `frontend-verify` launched a `vite` process whose
cwd was the **outer repo** (`/Users/ploy_warit/Desktop/GL-R-ERP/frontend`,
confirmed via `ps aux` showing the process path), not this worktree —
verified by `fetch('/src/styles.css')` not containing `reflow-cards` on the
auto-started server, then confirming it did after switching to the
manually-started worktree server. Every screenshot/inspect below is from
the manually-started worktree server on port 5201.

## Test / Build Results
- Lint: **pass** — `eslint src` → 0 errors, 9 pre-existing warnings (all
  `react-hooks/exhaustive-deps` in other/frozen pages — matches the
  baseline from every prior Phase-4 handoff, none introduced by this change).
- Tests: **pass** — `vitest run` → 17 test files, 84 tests, all green,
  including `OvertimePage.test.jsx` and `LeavePage.test.jsx` which exercise
  the two hand-rolled-table pages touched here.
- Build: **pass** — `vite build` → built in ~130ms, no errors.

## Mobile-Reflow Verification (per table)
All verified in-browser at 390×844 (`preview_resize`) against the
manually-started worktree `vite` server, HR demo login (Employee demo login
for `MyRequestsPage`), with `preview_inspect` confirming computed styles
(not just visual screenshots):

- **EmployeeListPage** (`/employees`): Desktop — `grid-template-columns`
  computed to `242px 99px 187px 121px 110px 99px` (proportional to
  `2.2:0.9:1.7:1.1:1:0.9`, exact match). Mobile — `.table-head` computed
  `display:none`; `.table-row` computed `display:grid;
  grid-template-columns:322px` (single column); each row rendered as a
  bordered/rounded card with `data-label` text before each value
  (พนักงาน/รหัส/ตำแหน่ง.../วันที่เริ่มงาน/เงินเดือน/สถานะ). Screenshots
  captured desktop + mobile.
- **AttendancePage** (`/attendance`): Desktop grid class confirmed present
  via `git diff`/source read (`gridClassName="grid-cols-[1.35fr_1.5fr_..."`).
  **Could not be visually verified live** — see Known Risks: a pre-existing,
  unrelated mock-data bug (`api.attendance` has no mock implementation)
  crashes the page via `ErrorBoundary` before any table renders, on both
  this branch and a clean `origin/main` checkout (confirmed via
  `git stash`). Verified the exact `.reflow-cards` CSS behaves identically
  for the attendance ratio by injecting a synthetic element with the same
  `gridClassName` string directly into the live page's DOM via
  `preview_eval` (bypassing the crashed component): computed
  `.table-head{display:none}`, `.table-row{display:grid;
  grid-template-columns:356px}`, and the `data-label` `::before` pseudo
  content resolved correctly (`"เวลา"`). This proves the CSS itself works
  correctly for this table; only the live-app crash (pre-existing, mock
  data layer) blocked an end-to-end screenshot.
- **OvertimePage** (`/overtime`): Desktop — `grid-template-columns`
  computed to `157.7px 183px 195.6px 126.2px 94.6px 100.9px`
  (proportional to `1.25:1.45:1.55:1:0.75:0.8`, exact match). Mobile —
  card reflow confirmed via screenshot: head hidden, one seeded OT request
  rendered as a card with all 4 `data-label`s
  (วันที่/พนักงาน · แผน OT · เหตุผล · เวลาจริง/จ่ายได้) plus status badge;
  `.row-actions` computed `display:flex; flex-wrap:wrap` (empty for this
  particular row/user — a permission condition, not a CSS issue).
- **LeavePage** (`/leave`): Desktop — `grid-template-columns` computed to
  `165.5px 134.8px 202.2px 91.9px 165.5px 98.1px` (proportional to
  `1.35:1.1:1.65:0.75:1.35:0.8`, exact match); screenshot shows both seeded
  leave requests aligned correctly with status badges and row-action icon
  buttons on the right. Mobile — screenshot confirms both requests reflow
  to labelled cards with all 5 `data-label`s, status badges, and the
  approve/reject/cancel icon-button row correctly laid out under each card
  (2-request row-actions verified: one pending row shows 3 buttons
  ✓/✗/✗-cancel, one approved row shows 1 cancel button — matches the page's
  conditional logic, unaffected by this CSS-only change).
- **MyRequestsPage** (`/my-requests`, Employee demo login, this page's
  default landing route for that role): Desktop — `grid-template-columns`
  computed to `147px 245.1px 245.1px 110.3px 122.5px` (proportional to
  `1.2:2:2:0.9:1`, exact match). Mobile — screenshot + computed styles
  confirm `.table-head{display:none}`, `.table-row{display:grid;
  grid-template-columns:322px}`; the single seeded profile-edit request
  reflows to a card. **Important parity note**: this page's original
  markup has **no `data-label` attributes on any `<span>`** (verified by
  reading the pre-change source) — so unlike the other 4 tables, its
  mobile card shows bare values with no small uppercase label above them.
  This was preserved exactly as-is (not "fixed"/improved) since the task
  is parity-first; flagging in case a future page-polish PR wants to add
  `data-label`s here for consistency with the other reflowed tables.
- **PayrollPage** (unchanged, `.payroll-table`) used as the mobile-reflow
  baseline throughout — same head-hide/card/label/row-actions pattern
  visually confirmed consistent with all 4 successfully-verified tables
  above.
- `preview_console_logs`: clean for all 4 successfully-verified pages; the
  only console errors seen anywhere in this session are the pre-existing
  `AttendancePage`/mock-data crash (see Known Risks), which is unrelated
  to any CSS/className change made in this PR.

## Decisions Made
- Followed the `DASHBOARD_GRID` precedent from `26_tw-convert-dashboards.md`
  for the 3 hand-rolled (non-`DataTable`) pages (`OvertimePage`,
  `LeavePage`, `MyRequestsPage`): a local `const <NAME>_TABLE_GRID` string
  applied via template literal to both the head and row `className`, rather
  than repeating the long Tailwind string twice inline. `EmployeeListPage`
  and `AttendancePage` use `DataTable`, which only needs the string once
  (as the `gridClassName` prop), so no local const was needed there.
- Did not touch `DataTable.jsx` — it already treats `gridClassName` as an
  opaque string appended to both head/row `className`s, so Tailwind
  utility strings work through it unmodified. Confirmed by reading the
  component (lines 238, 259, 283 in `DataTable.jsx`) before making any
  page-level change.
- Added `.reflow-cards` to the ≤1040px `min-width: 900px` selector group in
  `styles.css`, even though every converted table's Tailwind className also
  carries its own `max-[1040px]:min-w-[900px]` utility. This was
  intentional belt-and-suspenders: the Tailwind utility is layered
  (`@layer utilities`) and could theoretically be beaten by unlayered CSS
  in edge cases, so having the same guarantee expressed both ways (a
  layered Tailwind utility AND an unlayered generic-marker CSS rule) means
  the ≤1040px horizontal-scroll-trap behavior is protected two ways. No
  downside — both resolve to the identical `min-width: 900px` value.
- Left `.request-table` (non-`.mine`) fully alone — it's still used by
  `ProfileRequestsPage.jsx`, which was not in the task's named 5-page
  scope. Did not convert it opportunistically, to keep the diff scoped
  exactly to the named pages.

## Assumptions
- The task's phrase "5 non-frozen table pages" and the explicit file list
  (`EmployeeListPage`, `AttendancePage`, `OvertimePage`, `LeavePage`,
  `MyRequestsPage`) is authoritative scope, even though 3 of those 5 don't
  actually use the `DataTable` component (they hand-roll
  `<div className="X table-head/table-row">` directly) — the task's
  "Background" section assumed universal `DataTable` usage, but the actual
  code doesn't match that assumption for `OvertimePage`/`LeavePage`/
  `MyRequestsPage`. Handled by applying the same `gridClassName`-equivalent
  string directly to those pages' hand-rolled divs, which achieves the
  identical visual/CSS result the task describes.
- `ProfileRequestsPage.jsx` (the `request-table` non-`.mine` 6th consumer)
  was intentionally excluded since it wasn't named in the task's 5-page
  list — flagged clearly above so a future PR doesn't assume
  `.request-table` (bare) is dead.

## Known Risks
- **Pre-existing, unrelated bug blocks live AttendancePage verification**:
  `frontend/src/api/mockApi.js`'s exported `api` object has no `attendance`
  key at all (grep confirms sections for auth/employees/profileRequests/
  users/tickets/leave/overtime/commissions/dashboard/notifications/catalog/
  factoryConfigs/fxRates/priceCalcConfigs/attachments/customers/
  depositNotices, but not attendance). `AttendancePage.jsx`'s
  `api.attendance.list(...)` (line 61) and `api.attendance.devices()`
  (line 78) both throw `TypeError: Cannot read properties of undefined
  (reading 'devices')`, crashing the whole page via `ErrorBoundary`, in
  **mock mode only** (`VITE_USE_MOCKS=true`). Confirmed via `git stash` that
  this reproduces identically on a clean `origin/main` checkout — 100%
  unrelated to this PR's changes (CSS/className only, no `mockApi.js`
  touch). **Flagged as a spawned background task** (not fixed here, since
  it's a mock-data-layer change outside this PR's CSS-only scope) — see
  the task chip in this session, or search for "mockApi.js attendance
  section" if picking this up later. This bug does NOT affect the real
  backend-connected app (`VITE_USE_MOCKS` unset/false uses `hrApi.js`,
  which has a real `attendance.devices` route wired), only the
  `frontend-mock` demo-preview environment used for parity testing.
- **Preview-tooling cwd gotcha recurred** (documented in
  `27_tw-kill-inline-styles.md`): `mcp__Claude_Preview__preview_start`
  launched `vite` from the outer repo checkout, not this worktree, for
  both `frontend-mock` and `frontend-verify` configs. Worked around with a
  manually-started `vite` process from this worktree + `location.href`
  navigation of the existing preview tab, same as the prior PR. If this
  recurs for the next agent, verify via `fetch('/src/styles.css')` (or any
  just-edited file) containing an expected marker string before trusting
  any screenshot.
- `MyRequestsPage`'s cards have no `data-label`s (pre-existing, preserved
  exactly) — see the note in the verification section above. Not a
  regression, but visually inconsistent with the other 4 tables' mobile
  cards; flagged for a future polish pass if desired (out of scope here).

## Things Not Finished
- Old per-table CSS classes (`.employee-table`, `.attendance-table`,
  `.overtime-table`, `.leave-table`, `.request-table.mine`) and their
  ≤1040px/≤720px rule memberships are now **dead** (no longer referenced by
  any JSX `className`) but were **not deleted** — per the task's explicit
  "additive only" constraint. The final cleanup PR should:
  1. Grep-verify each of `.employee-table`, `.attendance-table`,
     `.overtime-table`, `.leave-table`, `.request-table.mine` has zero
     remaining `className`/`gridClassName` references (should just be the
     `DataTable.test.jsx` string-literal example, which uses
     `employee-table` as an arbitrary test fixture name, not real CSS
     dependency — safe to leave as-is or rename in the test file, agent's
     call).
  2. Remove those 5 classes' membership from the desktop
     `grid-template-columns` rules (`styles.css` ~874-920), the ≤1040px
     `min-width: 900px` group, and all 6 selector groups in the ≤720px
     block — leaving `.request-table` (bare), `.payroll-table`,
     `.user-table`, `.commission-table`, `.commission-payroll-table`
     untouched (still live).
  3. Do NOT remove `.reflow-cards` (still needed by the 5 converted pages)
     or `.request-table`/`.payroll-table` (still live, see above).
- `PayrollPage`/`.payroll-table` intentionally not converted (frozen-shared
  with `ceoSettings`) — out of scope for the entire Tailwind migration
  until `ceoSettings` itself is unfrozen or a separate decision is made.
- `ProfileRequestsPage.jsx`/`.request-table` (bare) intentionally not
  converted — not in this task's named scope; a natural follow-up once the
  cleanup PR above lands, since it's now the only page still on the legacy
  6-class group pattern for the request-table family.

## Recommended Next Agent
Claude Sonnet (implementation) — either (a) the grep-verified CSS cleanup
PR removing the now-dead classes listed above, or (b) continue the
Tailwind migration Phase 4 sequence into `ProfileRequestsPage.jsx` if that
page is still desired as a table-grid conversion target, or (c) pick up
the spawned "add mockApi.js attendance section" background task first
since it currently blocks any live browser verification of
`AttendancePage` in the mock-preview environment.

## Exact Next Prompt
```
You are the single implementation agent for one branch on the GL-R-ERP
repo. Two follow-ups are ready from docs/agent-handoffs/28_tw-table-grids.md
(the previous PR, which converted employee/attendance/overtime/leave/
my-requests tables to Tailwind grid-cols-[...] + a generic .reflow-cards
mobile card-reflow marker, additively, without deleting the old per-table
CSS classes). Pick ONE:

(a) CSS cleanup: grep-verify and delete the now-dead per-table CSS classes
    in frontend/src/styles.css: .employee-table, .attendance-table,
    .overtime-table, .leave-table, .request-table.mine (desktop
    grid-template-columns rules ~874-920, plus their membership in the
    ≤1040px min-width:900px group and all 6 selector groups in the ≤720px
    HR-core card-fallback block). Do NOT remove .reflow-cards (still used),
    .request-table (bare — still used by ProfileRequestsPage.jsx),
    .payroll-table (frozen-shared with ceoSettings), .user-table,
    .commission-table, .commission-payroll-table (sales-stack, frozen).
    Grep every class name across frontend/src before deleting to confirm
    zero remaining className/gridClassName references.

(b) Convert ProfileRequestsPage.jsx's request-table (bare, non-.mine)
    6-column table to the same grid-cols-[...] + reflow-cards pattern used
    for MyRequestsPage's .mine variant in the prior PR — this is the last
    remaining non-frozen table still on a legacy per-table CSS class.

First read (in order): CLAUDE.md, docs/agent-handoffs/00_MASTER_CONTEXT.md,
and docs/agent-handoffs/28_tw-table-grids.md (this file) in full — it has
the exact column ratios, the .reflow-cards rule definition, the list of
now-dead vs still-live classes, and two gotchas to know before touching
anything: (1) the frontend-mock/frontend-verify preview_start launch
configs may resolve against the OUTER repo checkout instead of your
worktree — verify via fetch() of a just-edited file before trusting any
screenshot, work around with a manually-started `vite` process + location.href
navigation if it recurs (same fix documented in 27_tw-kill-inline-styles.md
and 28_tw-table-grids.md); (2) AttendancePage crashes in mock-preview mode
due to a PRE-EXISTING, unrelated bug (mockApi.js has no `attendance` mock
section) — this is a known issue with a spawned background task already
flagged, do not treat it as something you broke, and don't be blocked by
it for other pages' verification.

Hard constraints: parity-first (pixel-identical desktop + mobile ≤720px),
do not touch frozen pages/payroll-table, do not change data/behavior, do
not commit until verification passes.

Verify: cd frontend && npm run lint && npm test && npm run build (0 lint
errors, 9 pre-existing warnings, tests+build green), then a frontend-mock
browser preview parity check with computed-style inspection (not just
screenshots) at desktop and 390px mobile widths, console clean (aside from
the known pre-existing AttendancePage issue if (b) touches an adjacent
page). Update/create docs/agent-handoffs/29_<branch-name>.md before ending.
```
