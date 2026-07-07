# Agent Handoff

> Seeded scaffold — the implementation agent fills the sections marked _(to fill)_ as work proceeds, and completes every section before stopping.

## Task
Make the six HR-core data tables usable on phones. They are currently forced to `min-width: 900px` inside a horizontally-scrolling panel (a scroll trap on ~375px screens). Replace that with a **card fallback** below 720px: each row renders as a stacked label→value card instead of a wide grid row. Branch 2 of the stabilization roadmap (`01_STABILIZATION_AUDIT.md` §8, P1-2).

## Branch
`fix/mobile-core-list-cards` (stacked on `fix/mobile-app-shell` — that branch's PR #116 is not merged yet, and this branch shares `styles.css`. Rebase onto `main` once #116 merges.)

## Base Commit
`1330315` (tip of `fix/mobile-app-shell`)

## Current Commit
Uncommitted working tree on `fix/mobile-core-list-cards`; tip commit is still `1330315` (tip of `fix/mobile-app-shell`). Left uncommitted for review per task rules.

## Agent / Model Used
Implementer: Claude Opus 4.8 (claude-opus-4-8) · Reviewer: Claude Sonnet _(per audit §9)_

## Scope

### In Scope — the six HR-core tables
- **Via the shared `DataTable` primitive** (`frontend/src/components/common/DataTable.jsx`): employees, attendance, payroll. `DataTable` emits `{gridClassName} table-row` with ordered `<span role="cell">{column.render(row)}</span>` children. Add `data-label={column.header}` (string headers) to each cell span so a CSS card layout can show the column name. One change here covers all three.
- **Custom `table-row` markup** (each renders its own `.table-head`/`.table-row` spans): `frontend/src/features/leave/LeavePage.jsx`, `frontend/src/features/overtime/OvertimePage.jsx`, `frontend/src/features/profileRequests/ProfileRequestsPage.jsx`. Add matching `data-label` attributes to each body cell span.
- **CSS:** a new rule set inside the existing `@media (max-width: 720px)` block in `frontend/src/styles.css` that, for the HR-core grid classes (`.employee-table`, `.attendance-table`, `.request-table`, `.payroll-table`, `.overtime-table`, `.leave-table`), turns each `.table-row` into a card (block layout, each cell on its own line showing `data-label` via `::before`), and drops the `min-width: 900px` for those classes at ≤720px. The `.table-head` (column header row) is hidden in card mode since each cell now carries its own label.

### Out of Scope
- The **sales-stack** grid classes in the same 900px rule (`.user-table`, `.commission-table`, `.commission-payroll-table`) — leave them as horizontal-scroll; those pages are frozen.
- The mobile drawer / app shell (branch 1, already done).
- Routing, data fetching, business logic, any column/data changes.
- Desktop/tablet (>720px) table appearance must be **unchanged**.

## Files Changed
Exactly the five expected files (`git diff --stat`: 5 files, +112 / -13):
- `frontend/src/components/common/DataTable.jsx` — added `data-label={typeof column.header === 'string' ? column.header : undefined}` to the body cell `<span role="cell">` (lines ~194-201). Covers employees + attendance + payroll (the three DataTable callers). Head cells untouched; grid/desktop behavior unchanged.
- `frontend/src/features/leave/LeavePage.jsx` — added `data-label` to the four `<span>` body cells (ช่วงลา / พนักงาน, ประเภท / จำนวนวัน, เหตุผล / เอกสาร, อนุมัติ / หมายเหตุ). The status column is a `<StatusBadge>` (self-descriptive) and the actions column is `.row-actions` — both intentionally left label-less.
- `frontend/src/features/overtime/OvertimePage.jsx` — added `data-label` to the four `<span>` body cells (วันที่ / พนักงาน, แผน OT, เหตุผล, เวลาจริง / จ่ายได้). Status badge + actions left label-less.
- `frontend/src/features/profileRequests/ProfileRequestsPage.jsx` — added `data-label` to พนักงาน (`.employee-cell`), ค่าเดิม (`.old-value`), and วันที่ spans. Wrapped the bare `<strong>{newValue}</strong>` cell in a `<span data-label="ค่าใหม่">` so its label renders (it was not a span before). The `<StatusBadge>` "ข้อมูลที่ขอแก้" column and `.row-actions` left label-less.
- `frontend/src/styles.css` — appended a card-fallback rule block INSIDE the existing (branch-1) `@media (max-width: 720px)` block, after the drawer rules and before the block's closing brace. No second media block created; no base or 1040px rules edited.

## Commands Run
```bash
cd frontend && npm run lint    # 0 errors, 13 pre-existing exhaustive-deps warnings
cd frontend && npm test        # vitest run
cd frontend && npm run build   # vite build
# Preview: preview_start frontend-mock (port 5200), preview_resize mobile/1440,
# HR quick-login, navigate Employees/Leave/Overtime/Profile-requests, preview_eval + screenshots
```

## Test / Build Results
- Frontend build (`npm run build`): **PASS** — 113 modules, built in ~158ms, CSS 30.50 kB.
- Frontend tests (`npm test`): **PASS** — 7 test files, 37 tests passed (incl. `DataTable.test.jsx`).
- Lint (`npm run lint`): **PASS** — 0 errors; 13 warnings, all pre-existing `react-hooks/exhaustive-deps` in unrelated files (none introduced by this change).
- Manual check via preview (mock, port 5200):
  - **375px** — Employees (DataTable path): rows render as full-width `display:grid` cards, `::before` shows column labels (พนักงาน / รหัส / ตำแหน่ง / วันที่เริ่มงาน), header row hidden, `document.scrollWidth === 375` (no horizontal scroll). Profile-requests (custom-markup path): 5 rows as cards, all four data-labels render via `::before`, actions in a wrapped row, no scroll. Leave & Overtime: header hidden + no scroll confirmed, but **0 rows in mock data** for the default date range so card rows themselves were not rendered there (behavior proven by the structurally identical Profile-requests page).
  - **1440px** — Profile-requests: header row `display:grid` visible, rows use the original 6-column `grid-template-columns`, `::before` label content is `none`. Full sidebar present. Pixel-identical to before; card fallback does not leak above 720px.

## Decisions Made
- **`data-label` only on `<span>` cells** (per DataTable's `role="cell"` spans and the `.table-row > span[data-label]::before` CSS selector). Non-span direct grid children — `<StatusBadge>` (status columns) and `.row-actions` — deliberately carry no label: status badges are self-descriptive, and the action cell is icon buttons. This keeps the diff minimal and avoids threading a `data-label` prop through `StatusBadge` (which does not forward arbitrary props).
- **ProfileRequests bare `<strong>` wrapped in a span** — the "ค่าใหม่" cell was a bare `<strong>` (not a span), so it could not receive a `::before` label. Wrapped it in `<span data-label="ค่าใหม่">` to give it a labeled card line; the `<strong>` styling is preserved inside.
- **CSS scoping via `.<grid>.table-row` compound selectors** — targets only the six HR-core grid classes; the sales-stack classes (`.user-table`, `.commission-table`, `.commission-payroll-table`) are omitted so they keep horizontal-scrolling. `.request-table.mine` is covered because its rows are `request-table mine table-row` and `.request-table.table-row` matches them.
- **`:not([data-label=""])`** guard on the `::before` rule so a cell with an empty/absent label never shows a stray label bar.
- **Appended to the existing 720px block** (branch 1's, at the end of the file) rather than the first 720px block, matching the task's "APPEND to it" instruction. Roles (`role="table"/row/cell`) are preserved — cards use `display:block/grid`, never `display:none` on cells.

## Assumptions
- `column.header` values in the three DataTable callers are plain strings suitable as `data-label` text. **Confirmed:** the `data-label` guard is `typeof column.header === 'string' ? column.header : undefined`, so any column using non-string `headerNode` markup simply omits the label (the `:not([data-label=""])` CSS also suppresses empty ones). Verified live on the Employees list — every column produced a correct Thai label.
- Mock mode has no leave/overtime rows in the default date window, so the Leave and Overtime card *rows* could not be rendered in the preview; their CSS coverage (min-width reset, hidden header, no horizontal scroll) was verified, and the row-card layout is proven by Profile-requests + Employees, which use the identical `.table-row > span[data-label]` mechanism.

## Known Risks
- `styles.css` is a single global sheet — card rules were added inside the existing `@media (max-width: 720px)` block; no desktop/tablet base rules and no `@media (max-width: 1040px)` rules were changed. Verified at 1440px that desktop is pixel-unchanged.
- The 720px block already contained branch 1's drawer rules — appended, did not rewrite.
- `.table-row` keeps `role="row"` with `role="cell"` spans; card mode uses `display:block/grid` (never `display:none` on cells), so a11y table semantics stay intact.
- When a DataTable has `onRowClick`, the row element is a `<button>` (not a div) with `display:grid` and block children. This is pre-existing (desktop grid also applies to that button); card mode does not change the element type. Low risk but worth a glance if any HR-core DataTable ever renders visually broken cards.
- **Attendance not renderable in mock mode:** the Attendance list white-screens in mock due to a pre-existing missing `api.attendance` mock (documented in the scaffold). Its `.attendance-table` class IS covered by the card rule set (head-hide + row-card + min-width reset), verified by CSS inspection, but could not be rendered live in mock mode. Verify against the real backend before release.

## Things Not Finished
- Nothing outstanding in scope. All five files changed, all commands pass, 375px/1440px verified.
- Not done (out of scope, intentionally): Attendance live-render in mock (blocked by missing mock — noted above); any sales-stack table (frozen); any routing/data/business-logic change.

## Review Verdict (Claude Opus 4.8, acting reviewer)

**APPROVED — no changes needed beyond a one-line handoff cleanup.** Committed and opened as a stacked PR on top of PR #116.

- **Scope:** clean. Exactly the 5 expected files; only the six HR-core grid classes get card rules; sales-stack classes (`.user-table`, `.commission-table`, `.commission-payroll-table`) are correctly omitted and still scroll. Card rules are appended inside the existing `@media (max-width: 720px)` block (branch 1's), no duplicate media block, no base/1040px rule touched.
- **a11y:** `role="table"/row/cell` preserved; card mode uses `display:block/grid`, never `display:none` on cells. Header row hidden (each cell now self-labels) is correct.
- **Live verification (Claude Opus, beyond the implementer's pass):** re-ran lint (0 errors)/test (37/37)/build (ok). At 375px, injected a real-markup `.leave-table.table-row` (mock had 0 leave rows in range, so this was the only way to exercise it) and confirmed via computed styles: `display:grid` single-column card, cells `display:block`, `::before` shows the `data-label` ("ช่วงลา / พนักงาน"), row 341px fits 375px viewport, `document.scrollWidth === 375` (no scroll). At 1440px the same class showed the original 6-column grid with `::before` content `none` and header `display:grid` — desktop pixel-unchanged, no leak above 720px. This closes the implementer's "Leave/Overtime not rendered live" gap (both use the identical `.table-row > span[data-label]` mechanism, one path now directly exercised).
- **Remaining caveat (unchanged):** Attendance can't render in mock mode (pre-existing missing `api.attendance` mock); its `.attendance-table` class is covered by the rule set and verified via CSS inspection, but needs a real-backend check before release.

## Recommended Next Agent
Human: merge PR #116 (branch 1) first, then this branch's PR (stacked on it) — or merge #116, rebase this branch onto `main`, and merge. After both land, the next roadmap step is branch 3, `refactor/tanstack-query-core` (`01_STABILIZATION_AUDIT.md` §8) — a larger architectural change that should get its own explicit go-ahead and Opus planning before an implementation agent starts.

## Exact Next Prompt
```
You are the REVIEW agent for branch `fix/mobile-core-list-cards` on GL-R-ERP (branch 2 of the stabilization roadmap). The implementation is complete and UNCOMMITTED in the working tree — do not commit or push; review only, and apply only tiny safe fixes if strictly necessary.

Read first: docs/agent-handoffs/00_MASTER_CONTEXT.md, docs/agent-handoffs/01_STABILIZATION_AUDIT.md (§5, §8), and docs/agent-handoffs/03_fix-mobile-core-list-cards.md in full. Then `git diff` (5 files: DataTable.jsx, LeavePage.jsx, OvertimePage.jsx, ProfileRequestsPage.jsx, styles.css).

Verify scope and correctness:
1. Only the six HR-core grid classes get the card fallback (.employee-table, .attendance-table, .request-table incl .request-table.mine, .payroll-table, .overtime-table, .leave-table). Confirm the sales-stack classes (.user-table, .commission-table, .commission-payroll-table) are NOT in the card rules and still horizontal-scroll.
2. The card rules live INSIDE the existing @media (max-width: 720px) block (the one at the end of styles.css with branch 1's drawer rules) — not a new/duplicate media block, and no base or @media (max-width: 1040px) rule was edited.
3. a11y: role="table"/row/cell preserved; no cell is display:none (header row hidden is fine).
4. data-label values match the Thai column headers each page's .table-head shows; head cells did NOT get data-label.
5. DataTable guard is `typeof column.header === 'string'` so non-string headerNode columns omit the label gracefully.

Then run in frontend/: `npm run lint && npm test && npm run build` (expect: 0 lint errors + only pre-existing exhaustive-deps warnings; 37 tests pass; build ok).

Then preview-verify (preview_start frontend-mock on 5200; HR quick-login = the button whose text contains "HR" and "พนักงานทั้งหมด"):
- At 375px: Employees + Profile-requests render as labeled cards, header row hidden, NO horizontal scroll (document.scrollWidth === 375). (Leave/Overtime have 0 mock rows in range; Attendance white-screens in mock — both are known/documented, verify CSS coverage not live render.)
- At 1440px: the same tables are unchanged wide grid rows, ::before label content is `none`, full sidebar present.

If everything checks out, approve for merge (note it must rebase onto main after branch 1 / PR #116 merges, since both touch styles.css). If you find issues, list them precisely; do not implement large fixes yourself. Then advance to branch 3 (`refactor/tanstack-query-core`) per audit §8.
```
