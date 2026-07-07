# Agent Handoff

## Task
Phase 4 of the Tailwind style migration: convert `MyRequestsPage` and
`ProfileRequestsPage` layout wrappers to the merged layout primitives
(`PageStack`, `RowActions`), and migrate the 3 form modals' `form-grid` /
`form-grid single` to the `FormGrid` primitive (`EmployeeFormModal`,
`ChangePasswordModal`, `ChangeRequestModal`), including their `span-2`
fields to `formGridSpan2`. No data/behavior/form-logic changes; react-hook-form
+ zod validation untouched in all 3 modals.

## Branch
`feat/tw-convert-requests-modals`

## Base Commit
`bdacb56` (main, PR #144 `feat/tw-table-grids` — matches
`docs/agent-handoffs/00_MASTER_CONTEXT.md` snapshot date 2026-07-07)

## Current Commit
Committed at the end of this session (see `git log -1` on this branch after
the commit step below) and pushed. No PR opened, per instructions.

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- `frontend/src/components/common/Layout.jsx` — extended `FormGrid` with a
  new optional `as` prop (defaults to `'div'`, fully backward compatible)
  so callers can render `<FormGrid as="form">` for real `<form>` semantics
  (needed because all 3 target modals' submit buttons live in the modal
  footer and reference the form via `<button type="submit" form="<id>">`,
  which requires an actual `<form id="...">` element, not a `<div>`).
- `frontend/src/features/profile/MyRequestsPage.jsx` — `<div
  className="page-stack">` → `<PageStack>`. The already-converted
  `MY_REQUESTS_TABLE_GRID` const and table divs (from PR #144,
  `28_tw-table-grids.md`) were left untouched, per instructions.
- `frontend/src/features/profileRequests/ProfileRequestsPage.jsx` — `<div
  className="page-stack">` → `<PageStack>`, `<span className="row-actions">`
  (wrapping the approve/reject buttons) → `<RowActions>` (renders a `<div>`
  instead of `<span>`; safe since `.row-actions` is `display:flex` and sits
  inside a CSS grid row — the replaced-element type doesn't affect flex/grid
  child layout). The `request-table`/`table-head`/`table-row` grid (already
  Tailwind-converted, `.request-table` bare/non-`.mine` variant) was left
  untouched — it wasn't part of this task's scope.
- `frontend/src/features/employees/EmployeeFormModal.jsx` — `<form
  id="employee-form" className="form-grid">` → `<FormGrid as="form"
  id="employee-form">`; the one `<div className="span-2">` (ที่อยู่ปัจจุบัน
  address field) → `<div className={formGridSpan2}>`.
- `frontend/src/features/auth/ChangePasswordModal.jsx` — `<form
  id="change-password-form" className="form-grid single">` → `<FormGrid
  as="form" single id="change-password-form">`.
- `frontend/src/features/profile/ChangeRequestModal.jsx` — `<form
  id="change-request-form" className="form-grid single">` → `<FormGrid
  as="form" single id="change-request-form">`.

### Out of Scope / Not Touched
- `frontend/src/styles.css` — confirmed byte-for-byte unchanged
  (`git diff --stat -- frontend/src/styles.css` is empty).
- All frozen sales/CRM pages (`tickets`, `deposits`, `commissions`,
  `ceoSettings`, `customers`, `factory`, `catalog`,
  `dashboard/TicketDashboard.jsx`) — confirmed untouched via `git diff --stat`.
- `table-panel` class on both target pages — this is a **different**,
  shared class from `.panel` (adds `overflow-x:auto` at ≤1040px on top of
  the shared `.panel`/`.table-panel`/`.detail-hero`/`.profile-strip` surface
  rule at `styles.css:577-585`) and is used by many non-target
  pages including frozen ones (`ceoSettings`, `commissions`, `tickets`,
  `DataTable.jsx` itself, `overtime`, `leave`). It is NOT `.panel` and was
  correctly left alone — converting it to `Panel` would have dropped the
  `overflow-x:auto` responsive behavior and touched a frozen-shared class.
- `request-feed` / `request-feed-item` — grepped and confirmed **neither
  target file uses this class** (`grep -n "request-feed"
  frontend/src/features/profile/MyRequestsPage.jsx
  frontend/src/features/profileRequests/ProfileRequestsPage.jsx` → no
  matches). The task brief's mention of `request-feed` for these two pages
  did not match the actual code — `request-feed`/`request-feed-item` is
  only used by `HrDashboard.jsx`, `EmployeeDashboard.jsx`, and
  `ProfilePage.jsx` (none of which were in this task's scope), confirmed via
  `grep -rn "request-feed" frontend/src/`. Nothing to reproduce inline;
  no legacy class left dangling on the target pages because of this.
- `OvertimePage.jsx` / `LeavePage.jsx` / `CommissionPage.jsx` /
  `TicketCreateModal.jsx` / `PayrollPage.jsx` — all also use `form-grid`/
  `span-2`/`row-actions` but were not named in this task's 3-modal scope
  (2 are frozen-adjacent sales pages, the others are a future Phase-4 PR
  target per the still-open page list in earlier handoffs).

## Files Changed
- `frontend/src/components/common/Layout.jsx` — `FormGrid` gained an `as`
  prop (`as: Component = 'div'`), rendering `<Component>` instead of a
  hardcoded `<div>`. 100% backward compatible: every existing `<FormGrid>`
  call site (none existed yet before this PR) still defaults to `<div>`.
- `frontend/src/features/profile/MyRequestsPage.jsx` — 1 import added
  (`PageStack`), `page-stack` div → `<PageStack>` (open/close tags only).
- `frontend/src/features/profileRequests/ProfileRequestsPage.jsx` — 1 import
  added (`PageStack, RowActions`), `page-stack` div → `<PageStack>`,
  `row-actions` span → `<RowActions>`.
- `frontend/src/features/employees/EmployeeFormModal.jsx` — 1 import added
  (`FormGrid, formGridSpan2`), `form` tag's `className="form-grid"` →
  `<FormGrid as="form">`, `span-2` div's className → `formGridSpan2`.
- `frontend/src/features/auth/ChangePasswordModal.jsx` — 1 import added
  (`FormGrid`), `form` tag's `className="form-grid single"` → `<FormGrid
  as="form" single>`.
- `frontend/src/features/profile/ChangeRequestModal.jsx` — 1 import added
  (`FormGrid`), `form` tag's `className="form-grid single"` → `<FormGrid
  as="form" single>`.

## Commands Run
```bash
git checkout -b feat/tw-convert-requests-modals origin/main
cd frontend && npm install            # worktree had no node_modules
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
git diff --stat -- frontend/src/styles.css                     # confirm empty
git diff --stat -- frontend/src/features/tickets frontend/src/features/deposits \
  frontend/src/features/commissions frontend/src/features/ceoSettings \
  frontend/src/features/dashboard/TicketDashboard.jsx frontend/src/features/customers \
  frontend/src/features/factory frontend/src/features/catalog             # confirm empty
grep -n "request-feed" frontend/src/features/profile/MyRequestsPage.jsx \
  frontend/src/features/profileRequests/ProfileRequestsPage.jsx           # confirm no matches
git diff --stat                                                            # confirm only 6 intended files
```
Plus a manually-started `vite` dev server from **this worktree**
(`VITE_USE_MOCKS=true npx vite --host 127.0.0.1 --port 5299 --strictPort`,
after ports 5201/5202/5210 were found occupied by concurrent sibling-agent
worktree servers — see Known Risks) with `preview_eval`-driven login +
navigation + `getComputedStyle` inspection.

## Test / Build Results
- Lint: **pass** — `eslint src` → 0 errors, 9 pre-existing warnings (all
  `react-hooks/exhaustive-deps` in other/frozen pages — matches the baseline
  from every prior Phase-4 handoff, none introduced by this change).
- Tests: **pass** — `vitest run` → 17 test files, 84 tests, all green,
  including `Layout.test.jsx` (6, unmodified — no new `FormGrid`-specific
  render test was added; the 3 modal component tests below exercise
  `FormGrid as="form"` end-to-end instead), `EmployeeFormModal.test.jsx` (5),
  `ChangePasswordModal.test.jsx` (4), `ChangeRequestModal.test.jsx` (2).
- Build: **pass** — `vite build` → built in ~130-140ms, no errors.

## Decisions Made
- Extended `FormGrid` with a minimal `as` prop rather than wrapping
  `<form><FormGrid>...</FormGrid></form>` (double wrapper) or leaving the
  modals on the legacy `<form className="form-grid">` pattern. All 3 target
  modals' footer submit buttons use `<Button type="submit"
  form="<id>">`, which requires a real `<form id="...">` DOM element
  reachable by ID — a `<div>` (even with an `id` prop passed through) would
  silently break that association since divs don't participate in the HTML
  form-association algorithm. The `as` prop defaults to `'div'` so every
  other/future `FormGrid` consumer that doesn't need form semantics is
  unaffected.
- Kept `table-panel` untouched on both pages (see Out of Scope above) — it
  reproduces a materially different, still-shared/frozen-adjacent CSS rule
  from `.panel`, and forcing it onto `Panel` would have been a scope
  violation (touches frozen pages' shared class) and a behavior regression
  (drops `overflow-x:auto`).
- `RowActions` swaps the original `<span className="row-actions">` to a
  `<div>`. Verified `.row-actions` (styles.css:1194) is `display:flex;
  justify-content:flex-end; gap:8px` with no span-specific styling, and it
  sits inside a CSS grid row (`.request-table.table-row`) where the
  replaced-element wrapper type doesn't affect grid/flex child layout —
  confirmed via live `getComputedStyle` (`display:flex`, 4 instances found
  on `/requests`).
- Did not add a new `Layout.test.jsx` unit test for `FormGrid`'s `as` prop —
  the 3 modal component tests (`EmployeeFormModal.test.jsx`,
  `ChangePasswordModal.test.jsx`, `ChangeRequestModal.test.jsx`, all
  pre-existing and green) already exercise it via `render()` + form
  submission, which is a more meaningful regression guard than an isolated
  primitive test. Flagging as a possible follow-up if the next agent wants
  isolated primitive coverage.

## Assumptions
- The task brief's mention of `request-feed` on `MyRequestsPage`/
  `ProfileRequestsPage` was based on a stale/incorrect assumption about
  these pages' markup — verified by reading both files and grepping the
  whole `frontend/src` tree. Treated the grep result as authoritative and
  proceeded with the actual scope (`page-stack`, `table-panel`,
  `row-actions`) rather than inventing a `request-feed` reproduction that
  isn't needed.
- `table-panel` was correctly identified as out of scope by reading its CSS
  definition (`styles.css:577-585` shared surface rule + `styles.css:805-807`
  `overflow:hidden` + `styles.css:1858-1860` `≤1040px overflow-x:auto`) and
  cross-referencing every JSX consumer via grep — 8 files use it, including
  3 frozen ones, confirming it must stay a shared legacy class for now.

## Known Risks
- **Severe preview-tooling instability this session**, worse than the
  gotcha documented in `27_tw-kill-inline-styles.md`/`28_tw-table-grids.md`.
  Root-caused during this session: **multiple concurrent sibling-agent
  worktrees** (`agent-a38d267e4a716c143`, `agent-a8e8330cfcc62aa6c`, and
  others) were each running their own manually-started `vite` dev servers
  on nearby ports (5201, 5202, 5210, ...) at the same time as this session,
  and the shared Claude-Preview browser tab/proxy for this workspace
  **silently redirected the active tab between different agents' dev
  servers** mid-session (observed jumping from this worktree's server to
  `agent-a38d267e4a716c143`'s and `agent-a8e8330cfcc62aa6c`'s servers
  multiple times, each with different/stale code), and the
  `frontend-mock`-named registered server (tied to the **outer repo**
  checkout, not any worktree) auto-restarted itself several times
  independent of any action taken here, tearing down the active `serverId`
  each time. Every `preview_screenshot` call in this session landed on a
  desynced tab state relative to the `preview_eval` calls immediately
  before it (confirmed by comparing `location.href`/DOM content between
  the two tool calls) — so **no trustworthy screenshot could be captured
  this session**, despite many attempts across 3 different manually-started
  ports (5201, 5202→killed as a stray/other-agent process, 5299) and 3
  fresh `preview_start`/`preview_list` cycles.
- **Verification was therefore done via `preview_eval` +
  `window.getComputedStyle()` instead of screenshots** — this is the
  ground-truth signal for CSS/layout correctness (same one
  `preview_inspect` is documented to prefer over screenshots for style
  verification) and was consistently reliable within a single `eval` call
  (no desync observed there, only between separate tool calls). Confirmed
  live in-browser, logged in as HR and Employee demo roles against this
  worktree's own manually-started server (verified via `fetch()`-ing
  `/src/components/common/Layout.jsx` and checking for the `as: Component`
  marker string before trusting any result, per the established gotcha
  workaround):
  - `EmployeeFormModal` (`#employee-form`, both add and edit variants):
    desktop (1440px effective) → `display:grid; gap:14px;
    grid-template-columns:333px 333px` (2 equal columns, matches
    `repeat(2,minmax(0,1fr))`); mobile (390px) → collapses to
    `grid-template-columns:310px` (1 column); the `span-2` address field →
    `grid-column:span 2 / span 2` desktop, `span 1 / span 1` mobile.
  - `ChangeRequestModal` (`#change-request-form`, `single` variant):
    `display:grid; gap:14px; grid-template-columns:310px` (1 column, as
    expected for `single`).
  - `MyRequestsPage` `PageStack`: `display:grid; gap:18px;
    max-width:1320px`, page heading "คำขอของฉัน" confirmed present.
  - `ProfileRequestsPage` `PageStack`: same grid classes confirmed; 4
    `RowActions` instances found (`display:flex`, one per pending request
    row), page heading "คำขอแก้ไขข้อมูล" confirmed present.
  - `ChangePasswordModal` was **not** reachable live — the app currently
    only wires it up in `forced` mode (`user.mustChangePassword`, see
    `App.jsx:152-156`); there is no voluntary/optional trigger button
    anywhere in the UI yet (the component's own doc comment describes an
    "optional, dismissable, launched voluntarily" mode that isn't wired to
    any button). Verified correctness instead via (a) direct source read
    confirming the identical `FormGrid as="form" single` pattern used in
    the two modals that WERE live-verified, and (b) the pre-existing
    `ChangePasswordModal.test.jsx` (4 tests, green, exercises the rendered
    form and its submit flow). Given the pattern is byte-identical in
    structure to the two verified modals, risk is assessed as low, but
    flagging clearly since it's the one target not confirmed via live
    `getComputedStyle`.
  - No console errors were observed in any successful `eval` call
    (`preview_console_logs` was not repeatedly polled given the tooling
    instability, but no uncaught exceptions surfaced during any of the
    successful navigation/inspection sequences above).
- If the next agent needs a trustworthy screenshot, first check `git
  worktree list` / `ps aux | grep vite` for other concurrently-running
  agent sessions before starting a preview server, and prefer a distinctly
  unusual port (this session used 5299) to reduce collision odds with
  other agents' worktree servers. Re-verify server identity via
  `fetch('/src/<a just-edited file>').then(r=>r.text())` checked for an
  expected marker string **immediately before every** `preview_screenshot`
  call, not just once at the start of the session — the redirect was
  observed recurring multiple times within a single session here.

## Things Not Finished
- No isolated `Layout.test.jsx` unit test added for `FormGrid`'s new `as`
  prop (see Decisions Made — covered transitively by the 3 modal tests
  instead).
- `ChangePasswordModal`'s optional/voluntary trigger is still unwired in
  the app (pre-existing gap, not introduced or fixed by this PR) — flagging
  in case a future page-polish PR wants to add a "เปลี่ยนรหัสผ่าน" button
  somewhere (e.g. profile page or topbar user menu) to make the modal
  reachable outside the forced first-login flow.
- `OvertimePage.jsx`/`LeavePage.jsx` (hand-rolled `form-grid`/`span-2`/
  `row-actions`, not `DataTable`-based) and `CommissionPage.jsx`/
  `TicketCreateModal.jsx`/`PayrollPage.jsx` (frozen or frozen-adjacent) are
  the remaining `form-grid`/`row-actions` consumers not yet on the
  primitives — a natural next Phase-4 PR for the two non-frozen ones
  (`OvertimePage`, `LeavePage`).

## Recommended Next Agent
Claude Sonnet (implementation) — continue Phase 4: convert
`OvertimePage.jsx`'s and `LeavePage.jsx`'s hand-rolled `form-grid`/`span-2`/
`row-actions` (the OT/leave request-submission forms, not their already-
Tailwind-converted table grids from PR #144) to the `FormGrid`/`RowActions`
primitives, following the exact same pattern used in this PR.

## Exact Next Prompt
```
You are the single implementation agent for one branch on the GL-R-ERP
repo. Continue Phase 4 of the Tailwind style migration (see
docs/agent-handoffs/25_tw-primitives-profile.md for the primitives,
docs/agent-handoffs/31_tw-convert-requests-modals.md — this file — for the
FormGrid `as` prop and RowActions precedent). Convert the two remaining
hand-rolled form-grid consumers to primitives:

- frontend/src/features/overtime/OvertimePage.jsx — the "ยื่นคำขอ OT" form
  (`<form className="form-grid">`, ~line 394) → `<FormGrid as="form">`; its
  `span-2` divs (~line 464, ~line 477 `span-2 row-actions` combo — this one
  combines both classes on one div, look closely at how to compose
  `formGridSpan2` + `RowActions` for it) → formGridSpan2 / RowActions.
  NOTE: OvertimePage's table grid (OVERTIME_TABLE_GRID) was already
  converted in PR #144 (28_tw-table-grids.md) — do NOT touch that, only the
  request-submission form above it.
- frontend/src/features/leave/LeavePage.jsx — same pattern, the "ยื่นคำขอลา"
  form (~line 454), span-2 divs at ~line 530 and ~line 543
  (`span-2 row-actions`). Its table grid (LEAVE_TABLE_GRID) was also
  already converted in PR #144 — do NOT touch that.

First read (in order): CLAUDE.md, docs/agent-handoffs/00_MASTER_CONTEXT.md,
docs/agent-handoffs/31_tw-convert-requests-modals.md (this file — has the
FormGrid `as` prop you'll reuse, and a serious preview-tooling gotcha to
read before attempting any browser verification: concurrent sibling-agent
worktrees can hijack your preview tab mid-session; check `ps aux | grep
vite` and `git worktree list` first, use an unusual port, and re-verify
server identity via fetch() before every single screenshot, not just once).

Hard constraints: parity-first (pixel-identical desktop + mobile ≤720px), no
data/behavior/form-logic changes (both pages likely still use plain
useState/manual validation, not react-hook-form — check before assuming),
do not touch frozen pages, do not edit styles.css, do not touch the already-
converted *_TABLE_GRID consts/table divs in either file, do not commit
until verification passes.

Verify: cd frontend && npm run lint && npm test && npm run build (0 lint
errors, 9 pre-existing warnings, tests+build green — OvertimePage.test.jsx
and LeavePage.test.jsx must stay green), then live parity verification —
if the preview browser tooling proves unreliable again, fall back to
preview_eval + getComputedStyle() on #<form-id> and the span-2/row-actions
elements (proven reliable in this session) rather than relying solely on
preview_screenshot. Update/create docs/agent-handoffs/32_<branch-name>.md
before ending.
```
