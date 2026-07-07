# Agent Handoff

## Task
Phase 4, PR2 of the Tailwind style migration: convert the two dashboards
(`EmployeeDashboard.jsx`, `HrDashboard.jsx`) to the layout primitives built in PR1
(`frontend/src/components/common/Layout.jsx`: `PageStack`/`Panel`/`StatGrid`;
`FieldList.jsx`: `FieldList`/`InfoGrid`/`DetailHero`), reproduce `.dashboard-grid`
(no primitive exists yet) with Tailwind utilities, and remove the one inline style
in `HrDashboard.jsx`. No business-logic/data/prop changes; `styles.css` untouched.

## Branch
`feat/tw-convert-dashboards`

## Base Commit
`07da4dc` (main, includes merged PR1 `#141` "add Tailwind layout primitives + convert
ProfilePage")

## Current Commit
Not committed yet — changes left in the working tree for review at the time of
writing this handoff (will be committed and pushed per the task instructions after
this file is written).

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- `frontend/src/features/dashboard/EmployeeDashboard.jsx` — replaced
  `<div className="page-stack">` → `<PageStack>`, `<div className="stat-grid">` →
  `<StatGrid>`, both `<section className="panel"><div className="panel-header">…`
  → `<Panel title=… actions=…>`, and `<div className="dashboard-grid">` → a local
  `DASHBOARD_GRID` Tailwind class string (no primitive for this exact 1.15fr/0.85fr
  column ratio yet).
- `frontend/src/features/dashboard/HrDashboard.jsx` — same `PageStack`/`StatGrid`
  (×2, matching the page's two separate `.stat-grid` blocks)/`Panel`/`DASHBOARD_GRID`
  conversion, plus removed the one inline style (`style={{ width: ... }}` on the
  division headcount bar's `<i>`) by moving the computed percentage into a CSS
  custom property (`--bar-width`) set via `style` and consumed by a Tailwind
  arbitrary-value class `w-[var(--bar-width)]`.

### Out of Scope
- `features/dashboard/TicketDashboard.jsx` — frozen, not touched.
- Any other frozen page (`features/tickets`, `features/deposits`,
  `features/commissions`, `features/ceoSettings`) — not touched.
- `styles.css` — confirmed byte-for-byte unchanged (`git diff --stat` empty).
- `StatCard.jsx`, `Button.jsx`, `StatusBadge.jsx`, `Avatar.jsx`, `PageHeader.jsx` —
  reused as-is, not modified.
- `.profile-strip`, `.action-list`, `.request-feed`, `.bar-list`, `.bar-row`,
  `.bar-track` classes — left as legacy classes (not in the primitive list for this
  PR; their CSS is untouched so parity is unaffected).
- Building a shared `DashboardGrid` primitive in `Layout.jsx` — the task said to
  reproduce `.dashboard-grid` "with Tailwind utilities inline rather than adding CSS
  / a primitive," so it's a local `const DASHBOARD_GRID` string in each file
  (duplicated, ~3 lines) rather than a new export. Flagging in case a later PR wants
  to promote it to `Layout.jsx` once a 3rd consumer appears.

## Files Changed
- `frontend/src/features/dashboard/EmployeeDashboard.jsx`:
  - Added imports for `PageStack`, `Panel`, `StatGrid` from `../../components/common/Layout.jsx`.
  - Added a local `DASHBOARD_GRID` Tailwind class constant reproducing
    `.dashboard-grid` (`grid-template-columns: 1.15fr 0.85fr; gap: 18px;
    align-items: start;` → 2-col at ≤1040px → 1-col at ≤720px, matching the
    `styles.css` media queries at lines ~1830 and ~1862).
  - `<div className="page-stack">` → `<PageStack>`.
  - `<div className="stat-grid">` → `<StatGrid>` (unchanged children/logic).
  - `<div className="dashboard-grid">` → `<div className={DASHBOARD_GRID}>`.
  - Both `<section className="panel"><div className="panel-header"><h2>…</h2>[<Button/>]</div>…</section>`
    blocks → `<Panel title="…" actions={…}>…</Panel>` (second panel's `actions` is
    the existing "ดูทั้งหมด" `Button`; first panel has no actions).
  - `.profile-strip`, `.action-list`, `.request-feed`, `.empty-state` classes left
    as-is (not in scope).
- `frontend/src/features/dashboard/HrDashboard.jsx`:
  - Added imports for `PageStack`, `Panel`, `StatGrid`.
  - Added the same local `DASHBOARD_GRID` constant.
  - `<div className="page-stack">` → `<PageStack>`.
  - Both `<div className="stat-grid">` blocks → `<StatGrid>` (page has two
    consecutive stat-grid rows — both converted independently, unchanged children).
  - `<div className="dashboard-grid">` → `<div className={DASHBOARD_GRID}>`.
  - Both panels → `<Panel title="…" actions={<Button variant="text">…</Button>}>`.
  - Removed the inline style: `<i style={{ width: `${...}%` }} />` →
    `<i className="w-[var(--bar-width)]" style={{ '--bar-width': `${widthPct}%` }} />`,
    with `widthPct` extracted as a local `const` inside the `.map()` callback for
    readability. The percentage is still computed per-row at render time (data-driven
    bar chart, not a static token), so a literal Tailwind width class isn't possible —
    the CSS custom property + arbitrary-value class is the standard Tailwind idiom
    for dynamic values and removes the raw pixel/percent literal from `style`. The
    `.bar-track i` CSS rule (`display: block; height: 100%; border-radius: inherit;
    background: var(--color-accent);`, `styles.css:700`) is untouched and still
    supplies everything except width.

## Commands Run
```bash
git status                      # confirmed clean, on main @ 07da4dc
git checkout -b feat/tw-convert-dashboards
cd frontend && npm install      # worktree had no node_modules
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
git diff --stat frontend/src/styles.css   # confirm empty
git diff --stat                            # confirm only the 2 dashboard files touched
```
Plus a `frontend-mock` preview session (`preview_start` on port 5200 → login as HR
demo role → screenshot `/` (EmployeeDashboard, company mode) and `/hr`
(HrDashboard) at desktop width → `preview_resize` to 1000px (≤1040px breakpoint) and
390px (≤720px breakpoint) → screenshots at each → logged out, logged in as Employee
demo role → screenshot `/` (EmployeeDashboard, employee mode, the 8-card layout) at
desktop/1000px/390px → `preview_console_logs` checked clean throughout →
`preview_stop`.

## Test / Build Results
- Lint: **pass** — `eslint src` → 0 errors, 9 pre-existing warnings (all
  `react-hooks/exhaustive-deps` in other/frozen pages: `AttendancePage`,
  `CeoSettingsPage`, `CommissionPage`, `TicketDashboard`, `DepositNoticePage`,
  `PayrollPage`, `TicketDetailPage`, `TicketListPage` — matches the PR1 baseline,
  none introduced by this change).
- Tests: **pass** — `vitest run` → 17 test files, 84 tests, all green (no test
  touches dashboards directly; nothing broken).
- Build: **pass** — `vite build` → built in 132ms, no errors.
  `dist/assets/EmployeeDashboard-*.js` (6.31 kB / gzip 2.09 kB) and
  `dist/assets/HrDashboard-*.js` (4.41 kB / gzip 1.81 kB) emitted normally.

## Decisions Made
- Kept `DASHBOARD_GRID` as a plain exported-nowhere `const` string duplicated in
  both files rather than adding it to `Layout.jsx`, per the task's explicit
  instruction ("reproduce it with Tailwind utilities inline rather than adding CSS
  / a small local wrapper"). Both copies are byte-identical; a future PR could
  promote it to a shared `DashboardGrid` primitive once a 3rd page needs the same
  1.15fr/0.85fr ratio.
- Used `Panel`'s `title`/`actions` shorthand for every panel header in both files —
  all four panel headers here are exactly "one `h2` + zero-or-one action button,"
  which fits the shorthand cleanly (no need for `Panel.Header` directly, unlike the
  risk flagged in the PR1 handoff for more complex headers).
- For the one inline style (dynamic bar width in `HrDashboard`), used a CSS custom
  property (`style={{ '--bar-width': ... }}`) consumed by a Tailwind arbitrary-value
  class (`w-[var(--bar-width)]`) instead of leaving a raw `style={{ width: ... }}`.
  This is the standard Tailwind pattern for computed/data-driven values and there's
  no static "token" that could express a per-row percentage, so a literal utility
  class was not possible — flagging this explicitly since the task phrased it as
  "token utility."

## Assumptions
- The `frontend-mock` `.claude/launch.json` config (port 5200,
  `VITE_USE_MOCKS=true`) needed no changes (confirmed present, matches PR1).
- Login as "HR" demo role reaches `/` (EmployeeDashboard in "company" mode, 9 stat
  cards) and `/hr` (HrDashboard, two 4-card `StatGrid` rows + division bar chart);
  login as "Employee" demo role reaches `/` in "employee" mode (5 base cards + 3
  extra employee-only cards = 8 total) — both were exercised for parity coverage
  ("manager" mode was not separately exercised since no demo login maps to it, but
  its stat-card list follows the identical `StatGrid`/`Panel` markup as the other
  two modes, just a different `cards` array).

## Known Risks
- `DASHBOARD_GRID` is now duplicated verbatim in two files. If the exact ratio
  (`1.15fr 0.85fr`) or breakpoints ever change, both copies need to be updated
  together — low risk since `dashboard-grid` in `styles.css` isn't expected to
  change during stabilization, but worth a lint/grep check (`grep -rn
  "grid-cols-\[1.15fr_0.85fr\]" frontend/src`) before any further dashboard work.
- The bar-width CSS-custom-property approach is a new pattern in this codebase (no
  prior `style={{'--x': ...}}` + `w-[var(--x)]` usage elsewhere) — verified visually
  in the preview (bars render at correct proportional widths, computed style
  showed `width: 209.508px` on an 8-of-8-max-headcount row scaling correctly) but
  there's no automated test asserting the bar width; a future test could snapshot
  `getComputedStyle` if this becomes a regression concern.
- A stray click during manual preview testing (dismissing a notification dropdown)
  triggered a logout once — this is pre-existing app behavior (the notification
  bell/logout controls are adjacent in the topbar), unrelated to this PR's changes,
  and not a regression; noting it in case the next agent hits the same thing while
  testing.

## Things Not Finished
- `.profile-strip` (used only in `EmployeeDashboard`'s optional avatar header block)
  was not converted to `DetailHero` — it's structurally similar but wasn't in this
  PR's explicit primitive list (`page-stack`/`panel`/`panel-header`/`stat-grid`/
  `dashboard-grid`) and doesn't appear in `HrDashboard` at all, so converting it here
  would have been scope creep. Flagging for whichever future page/cleanup pass
  covers `.profile-strip`.
- `.action-list`, `.request-feed`, `.bar-list`/`.bar-row`/`.bar-track` were left as
  legacy classes inside the converted panels (not part of the primitive list; their
  CSS is untouched, so parity is unaffected).
- No CSS was deleted (by design — deletion is the final PR after all pages convert).

## Recommended Next Agent
Claude Sonnet (implementation) — continue Phase 4, PR3.

## Exact Next Prompt
```
You are the single implementation agent for one branch on the GL-R-ERP repo. This
is Phase 4, PR3 of the Tailwind style migration: convert the next non-frozen page
group per the Phase 4 order — profile requests
(frontend/src/features/profile/MyRequestsPage.jsx and
frontend/src/features/profile/ProfileRequestsPage.jsx, or whichever of the two
exists/is next per the plan) — to the layout primitives already built:
frontend/src/components/common/Layout.jsx (PageStack/Panel/FormGrid/StatGrid/
FilterBar/RowActions) and frontend/src/components/common/FieldList.jsx
(FieldList/InfoGrid/DetailHero).

First, read (in order): CLAUDE.md, docs/agent-handoffs/00_MASTER_CONTEXT.md,
docs/agent-handoffs/25_tw-primitives-profile.md (PR1 — the primitives and their
known risks) and docs/agent-handoffs/26_tw-convert-dashboards.md (this file — PR2,
the dashboard conversion and the DASHBOARD_GRID/bar-width decisions). Then read the
target page file(s) plus their current legacy classes/styles.css rules before
changing anything. git status, confirm clean on up-to-date main, branch off as
feat/tw-convert-profile-requests (or similar).

Hard constraints (same as PR1/PR2): parity-first (pixel-identical desktop + mobile
≤720px), no business-logic/data/prop changes, do not touch frozen pages
(TicketDashboard, tickets/deposits/commissions/ceoSettings), do not edit/delete any
styles.css rule in this PR, do not commit/push until final verification passes —
then commit and push (do not open a PR) per the task instructions.

This may be the first real consumer of FieldList (2/3-column mode) if the profile
requests page uses a field-list-style layout — validate its ≤720px collapse
in-browser since PR1 flagged it as built-but-unverified-in-browser. If a page's
markup doesn't map cleanly onto Panel's title/actions shorthand or another
primitive's assumptions (see "Known Risks" in 25_tw-primitives-profile.md and
26_tw-convert-dashboards.md), adjust the call site first — only touch the
primitives themselves if a real gap is found, and note any such change clearly.

Verify: cd frontend && npm run lint && npm test && npm run build (0 lint errors, 9
pre-existing warnings, tests+build green), then frontend-mock preview parity check
(desktop + mobile, both HR and Employee demo-login roles since profile requests are
visible to both) with screenshots, console clean. Update/create the handoff file
docs/agent-handoffs/27_<branch-name>.md before ending, exact-next-prompt pointing at
the next page group per the Phase 4 order.
```
