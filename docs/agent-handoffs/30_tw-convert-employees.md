# Agent Handoff

## Task
Phase 4 page-markup conversion: convert `EmployeeListPage.jsx` and
`EmployeeDetailPage.jsx` from legacy `styles.css` classes to the Tailwind
layout primitives built in `feat/tw-primitives-profile`
(`frontend/src/components/common/Layout.jsx`:
PageStack/Panel/FormGrid/StatGrid/FilterBar/RowActions, and
`frontend/src/components/common/FieldList.jsx`: FieldList/InfoGrid/DetailHero).
Parity-first, no data/behavior changes, `styles.css` untouched.

## Branch
`feat/tw-convert-employees`

## Base Commit
`bdacb56b81a8680c8b9792208ea80e8707cee0b5` (main, matches
`docs/agent-handoffs/28_tw-table-grids.md` tip — PR #144 merged)

## Current Commit
Committed and pushed (see `git log -1` on the branch after this handoff is
committed) — no PR opened per instructions ("PUSH. No PR/merge").

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- `frontend/src/features/employees/EmployeeListPage.jsx`: `page-stack` →
  `PageStack`, `filter-bar` section → `FilterBar`. `page-heading`/
  `page-actions` were already handled by the existing `PageHeader` component
  (untouched, out of scope) so no inline reproduction was needed there.
- `frontend/src/features/employees/EmployeeDetailPage.jsx`: `page-stack` →
  `PageStack`, `detail-hero` → `DetailHero`, `info-grid two` → `InfoGrid two`
  (all 4 tab render functions), `field-list` / `field-list three` →
  `FieldList` / `FieldList columns={3}` (all 6 usages across
  Personal/Employment/Sensitive tabs), `.tabs` nav reproduced inline with
  Tailwind (no primitive exists for it).

### Out of Scope (left untouched, confirmed)
- `EmployeeListPage`'s `DataTable gridClassName` (already converted to
  Tailwind `grid-cols-[...]` + `reflow-cards` in PR #144 — verified via
  `git diff` shows zero changes to that prop, and via live
  `reactProps.gridClassName` inspection in the browser).
- `EmployeeFormModal.jsx` (separate PR per instructions).
- `frontend/src/styles.css` — confirmed byte-for-byte unchanged
  (`git diff --stat frontend/src/styles.css` empty).
- Frozen sales/CRM pages — not touched.
- `highlight-panel`, `sensitive-panel`, `hero-meta` (partially, see below),
  `timeline-list`, `salary-history`, `address-line` classes — no primitive
  exists for these in the current primitive set, so they were left as
  legacy classes (their CSS rules are untouched and still apply). `hero-meta`
  itself *was* reproduced inline (see Decisions) since it sits directly
  inside the now-primitive `DetailHero`.

## Files Changed
- `frontend/src/features/employees/EmployeeListPage.jsx`:
  - Import `FilterBar`, `PageStack` from `Layout.jsx`.
  - `<div className="page-stack">` → `<PageStack>`.
  - `<section className="filter-bar">` → `<FilterBar>`; the search
    `<label className="search-field">` and its icon/input padding were
    reproduced inline with Tailwind arbitrary selectors
    (`relative flex-1 min-w-[240px] [&_svg]:absolute [&_svg]:left-3
    [&_svg]:top-1/2 [&_svg]:-translate-y-1/2 [&_svg]:text-text-faint
    [&_input]:pl-10`) since `search-field` has no primitive; each `<select>`
    got explicit `w-auto min-w-[142px] max-[720px]:w-full` (reproducing
    `.filter-bar select` + the ≤720px full-width rule) since `FilterBar`
    only owns the container, not its children's sizing.
- `frontend/src/features/employees/EmployeeDetailPage.jsx`:
  - Import `DetailHero`, `FieldList`, `InfoGrid` from `FieldList.jsx`;
    `PageStack` from `Layout.jsx`; `cn` from `utils/cn.js`.
  - `<div className="page-stack">` → `<PageStack>`.
  - `<section className="detail-hero">` → `<DetailHero>` (no `compact` —
    original had no `.compact` modifier here, unlike ProfilePage).
  - `<div className="hero-meta">` reproduced inline as
    `flex flex-wrap gap-x-5 gap-y-[10px] mt-3 text-text-secondary text-sm
    [&_span]:inline-flex [&_span]:gap-[7px] [&_span]:items-center`
    (`.hero-meta` + `.hero-meta span`).
  - `<nav className="tabs">` reproduced inline: container
    `flex gap-[2px] border-b border-border-input overflow-x-auto`; each
    button `inline-flex items-center gap-[7px] min-h-[44px] px-[14px]
    border-0 border-b-2 border-transparent bg-transparent text-text-muted
    font-bold whitespace-nowrap`, with active state (`cn(...)`) adding
    `text-primary border-b-primary`.
  - `PersonalTab`/`EmploymentTab`/`HistoryTab`: `<div className="info-grid
    two">` → `<InfoGrid two>`.
  - All 5 `<dl className="field-list">` → `<FieldList>` (default 2-col);
    the 1 `<dl className="field-list three">` (SensitiveTab) →
    `<FieldList columns={3}>`. The existing per-pair `<div><dt>…<dd>…</div>`
    wrapper markup was preserved unchanged — see Decisions.
  - `address-line` paragraph reproduced inline as
    `leading-[1.7] text-text-secondary` (`.address-line`).

## Commands Run
```bash
git status                              # confirmed clean, on main-tracking branch
git checkout main && git pull --ff-only
git checkout -b feat/tw-convert-employees   # (renamed the worktree's existing branch)
cd frontend && npm install              # deps weren't installed in this worktree
npm run lint
npm test
npm run build
git diff --stat frontend/src/styles.css     # confirm empty
git diff frontend/.../EmployeeListPage.jsx | grep gridClassName   # confirm empty
```
Plus an extensive `frontend-mock`-equivalent preview session: since the
harness's `preview_start` server kept resolving to the shared main checkout
(`cwd: /Users/ploy_warit/Desktop/GL-R-ERP`) rather than this worktree, a
manual `VITE_USE_MOCKS=true npm run dev -- --port 5210 --strictPort` was run
from inside the worktree, and the preview browser was pointed at it via
`window.location.replace('http://127.0.0.1:5210/')` inside `preview_eval`.
Verified via HR quick-login → `/employees` → click into row → tab clicks,
using `preview_snapshot` (a11y tree) and `preview_eval` computed-style reads
as the source of truth, since `preview_screenshot` was intermittently stale
in this session (see Known Risks).

## Test / Build Results
- Lint: **pass** — `eslint src` → 0 errors, 9 pre-existing warnings (all in
  `AttendancePage`, `CeoSettingsPage`, `CommissionPage`, `TicketDashboard`,
  `DepositNoticePage`, `PayrollPage`, `TicketDetailPage`, `TicketListPage` —
  matches the expected baseline, none introduced by this change).
- Tests: **pass** — `vitest run` → 17 test files, 84 tests, all green.
- Build: **pass** — `vite build` → 246 modules transformed, built in
  ~130-145ms, no errors. `EmployeeListPage-*.js` (5.61 kB) and
  `EmployeeDetailPage-*.js` (9.42 kB) chunks emitted normally.

## Decisions Made
- **`FieldList` dt/dd wrapper divs kept as-is.** `EmployeeDetailPage`'s
  original markup wraps each `<dt>/<dd>` pair in a bare `<div>`
  (`<div><dt>…</dt><dd>…</dd></div>`), unlike ProfilePage which doesn't use
  `field-list` at all. `FieldList` renders `<dl className="grid ...">` and
  relies on `[&_dt]`/`[&_dd]` *descendant* selectors (not direct-child), so
  the dt/dd text styling still applies correctly through the wrapper div.
  Critically, `.field-list`'s CSS is `display:grid` directly on the `<dl>`
  with each *grid item* getting one dt+dd pair — removing the wrapper divs
  would have made each `dt` and `dd` a separate grid cell (doubling the
  effective column count and breaking the label-above-value stacking this
  page relies on). Verified via live computed-style check that
  `dlGridCols` renders as 2 (or 3) equal tracks with each in-flow pair
  occupying one cell, matching the pre-conversion layout exactly. This
  resolves the specific risk flagged in `docs/agent-handoffs/25_tw-primitives-profile.md`
  ("the next page that uses field-list should visually verify it").
- **`.tabs` reproduced inline, not as a new primitive.** The task explicitly
  scoped tabs as "reproduce inline with Tailwind utilities" (not in the
  primitive list), so no new component was added to `Layout.jsx`.
- **`page-heading`/`page-actions` needed no changes.** Both pages already
  use the existing `PageHeader` component (which itself renders
  `className="page-heading"` / `"page-actions"`), so there was nothing to
  convert at the call site — `PageHeader` is out of this task's scope and
  untouched.
- **`hero-meta` reproduced inline rather than left as a legacy class**,
  because it sits as a direct child of the now-primitive `DetailHero`'s
  content block; leaving it as a bare legacy class would have been
  inconsistent with the "kill static inline styles / convert layout
  classes" spirit of the task, and its CSS rule is simple enough to
  reproduce exactly with Tailwind arbitrary selectors.
- **`highlight-panel` / `sensitive-panel` / `timeline-list` / `salary-history`
  / `address-line`(-adjacent container) left as legacy classes** — none are
  in the primitive list, they have no direct Tailwind-primitive equivalent
  in this repo yet, and their CSS is untouched so parity is unaffected.
  `address-line` itself (a `<p>`, not a container) was converted since it's
  a single trivial one-off utility, consistent with "kill static inline
  styles."

## Assumptions
- The existing `frontend-mock` `.claude/launch.json` entry (port 5200,
  `VITE_USE_MOCKS=true`) was assumed sufficient, but in this worktree the
  harness's `preview_start` kept starting/reusing a server rooted at the
  shared main checkout instead of this worktree's file tree. Worked around
  by running Vite manually from inside the worktree on a separate port
  (5210) and pointing the preview browser's `window.location` at it.
- HR demo-login role is sufficient to reach `/employees` and
  `/employees/:id` with representative data for parity verification.

## Known Risks
- **Preview-tooling instability in this session**, not an app defect:
  `preview_screenshot` repeatedly returned stale content (a previous route,
  or the login screen) while `preview_eval`/`preview_snapshot` against the
  same `serverId` simultaneously reported the correct, current route and
  DOM — strongly suggesting the screenshot tool was reading from a
  different/cached browser tab. Login session state and route also reset
  unpredictably between some tool calls. Because of this, verification in
  this handoff relies on `preview_snapshot` (accessibility tree) and
  `preview_eval` computed-style reads (both consistently live and
  cross-checked against the CSS source in `styles.css`), not on visual
  screenshots. A future agent with a stable preview session should still
  do a quick visual screenshot pass as a sanity check, though the
  structural/computed-style verification here is thorough (tabs active
  color/border, FieldList grid-template-columns at 2/3 columns and at
  ≤720px, DetailHero flex-direction at ≤720px, FilterBar select/button
  full-width at ≤720px all directly measured and matched against the
  `styles.css` source rules).
- `FieldList`'s reliance on descendant selectors (`[&_dt]`/`[&_dd]`) rather
  than direct-child means it will keep working even if a future page nests
  dt/dd differently — but this also means it does *not* enforce the
  "one grid cell per pair" structure; a future page that puts `dt`/`dd` as
  direct `<dl>` children (no wrapper div, like the primitive's own doc
  comment implies) will get a different visual grid than one that wraps
  pairs in divs (like this page). Worth normalizing in a later cleanup pass
  once all pages are converted, but out of scope now (parity-first, no
  behavior/layout changes).

## Things Not Finished
- CSS deletion — none done here, matches repo-wide convention (deletion is
  the final PR after all pages convert, per `25_tw-primitives-profile.md`).
- `EmployeeFormModal.jsx` — explicitly out of scope (separate PR).

## Recommended Next Agent
Claude Sonnet (implementation) — continue Phase 4 with the next
non-frozen, not-yet-converted page group.

## Exact Next Prompt
```
You are the single implementation agent for one branch on the GL-R-ERP repo.
This is Phase 4 of the Tailwind style migration, continuing after
docs/agent-handoffs/30_tw-convert-employees.md (EmployeeListPage +
EmployeeDetailPage converted to the Layout.jsx / FieldList.jsx primitives).

First read (in order): CLAUDE.md, docs/agent-handoffs/00_MASTER_CONTEXT.md,
docs/agent-handoffs/30_tw-convert-employees.md (this file — read the "Known
Risks" section on preview-tooling instability before trying browser
verification, and the FieldList dt/dd-wrapper-div decision before touching
any other page that uses field-list). Then check
docs/agent-handoffs/README.md and 01_STABILIZATION_AUDIT.md for the next
page in the Phase 4 sequence (candidates: AttendancePage, OvertimePage,
LeavePage, ProfileRequestsPage, MyRequestsPage — confirm which are still on
legacy page-stack/panel/info-grid/field-list classes with a grep across
frontend/src/features before picking one; skip any already converted).

git status, confirm clean on up-to-date main, branch off as
feat/tw-convert-<page-name>.

Hard constraints (same as prior PRs): parity-first (desktop + ≤720px), no
business-logic/data/prop changes, do not touch frozen pages
(tickets/quotation/deposit/commission/pricing-FX/catalog/customer/factory/
ceo-settings), do not edit/delete any styles.css rule, do not touch
EmployeeFormModal.jsx or any already-converted DataTable gridClassName.

Verify: cd frontend && npm run lint && npm test && npm run build (0 lint
errors, 9 pre-existing warnings, tests+build green). For the browser
preview: try the normal frontend-mock preview_start flow first: if
preview_start resolves to the wrong working directory (serving a different
checkout's code instead of your worktree's — check the reported `cwd` in
preview_list output), fall back to manually running
`VITE_USE_MOCKS=true npm run dev -- --port <unused-port> --strictPort` from
inside your actual worktree via Bash, then point the preview browser at it
with `window.location.replace('http://127.0.0.1:<port>/')` inside
preview_eval. Prefer preview_snapshot (accessibility tree) and preview_eval
computed-style checks over preview_screenshot if screenshots seem stale
compared to snapshot/eval state (cross-check window.location.pathname
between calls to detect this). Update/create the handoff file
docs/agent-handoffs/31_<branch-name>.md before ending, with an exact-next-
prompt pointing at the following page group in the Phase 4 order.
```
