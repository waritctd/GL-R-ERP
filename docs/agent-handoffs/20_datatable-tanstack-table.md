# Agent Handoff

## Task
Phase 2b: re-base `frontend/src/components/common/DataTable.jsx` internals on `@tanstack/react-table`, preserving the existing public API/DOM/behavior, and add default-off `stickyHeader` plus CSV export capabilities.

## Branch
`feat/datatable-tanstack-table`

## Base Commit
`91786f8` (`main`, `origin/main`) — Phase 2a merged via #134

## Current Commit
Not committed.

## Agent / Model Used
Codex GPT-5

## Scope

### In Scope
- Add `@tanstack/react-table`.
- Re-implement `DataTable` row models with TanStack Table headless APIs.
- Preserve the frozen `DataTable` public props and column contract.
- Preserve the existing `div`/grid DOM, `table-head`/`table-row` classes, `data-label` cells, `SortHeader`, skeleton loading, `EmptyState`, and pagination markup/classes.
- Add default-off `stickyHeader`.
- Add default-off CSV export via `exportable` and/or `onExportCsv`.
- Add focused tests for sticky header and CSV export.

### Out of Scope
- No frozen-page edits.
- No business-logic changes.
- No `styles.css` deletion.
- No column resizing.
- No row virtualization.
- No form migrations.

## Files Changed
- `frontend/package.json`: added `@tanstack/react-table`.
- `frontend/package-lock.json`: lockfile updates for `@tanstack/react-table` / `@tanstack/table-core`.
- `frontend/src/components/common/DataTable.jsx`: moved search/sort/pagination internals onto `useReactTable`, preserving rendering; added `stickyHeader`, `exportable`, and `onExportCsv`.
- `frontend/src/components/common/DataTable.test.jsx`: left the existing parity tests intact and added tests for sticky header class and sorted+filtered CSV export.
- `frontend/src/styles.css`: added `.table-head.is-sticky` for the opt-in sticky header.

## Commands Run
```bash
sed -n '1,260p' CLAUDE.md
sed -n '1,320p' docs/agent-handoffs/00_MASTER_CONTEXT.md
sed -n '1,320p' docs/agent-handoffs/README.md
sed -n '1,360p' docs/agent-handoffs/19_rhf-forms-overtime-pilot.md
sed -n '1,280p' frontend/src/components/common/DataTable.jsx
sed -n '1,260p' frontend/src/components/common/DataTable.test.jsx
sed -n '1,320p' frontend/src/features/attendance/AttendancePage.jsx
sed -n '1,320p' frontend/src/features/employees/EmployeeListPage.jsx
sed -n '1,380p' frontend/src/features/payroll/PayrollPage.jsx
sed -n '1,360p' frontend/src/features/tickets/TicketListPage.jsx
sed -n '1,540p' frontend/src/features/commissions/CommissionPage.jsx
git status --short --branch
git log --oneline --decorate -5
git switch -c feat/datatable-tanstack-table
npm install @tanstack/react-table
npm test -- DataTable.test.jsx
npm run lint
npm test
npm run build
VITE_USE_MOCKS=true npm run dev
```

Browser checks were run against `http://127.0.0.1:5174/` with `VITE_USE_MOCKS=true` in the in-app browser.

## Test / Build Results
- Lint: pass. `npm run lint` completed with 0 errors and the expected 9 pre-existing warnings.
- Targeted DataTable tests: pass. `npm test -- DataTable.test.jsx` completed with 9 tests passed.
- Full frontend tests: pass. `npm test` completed with 12 files / 65 tests passed.
- Frontend build: pass. `npm run build` completed successfully.
  - CSS: `dist/assets/index-BdYnRcte.css` — 37.02 kB / 7.81 kB gzip.
  - JS: `dist/assets/index-DLlWC99C.js` — 558.85 kB / 162.58 kB gzip.
  - Phase 2a handoff recorded JS gzip at 148.20 kB, so the TanStack Table delta is about +14.38 kB gzip.
  - The Vite 500 kB advisory persists; route-level code-splitting remains the standing follow-up.
- Backend tests: not run; out of scope.

## Browser Results
- `EmployeeListPage` desktop: rendered rows/pagination, salary sort worked numerically, next-page pagination moved from page 1 to page 2, and no console errors were observed.
- `EmployeeListPage` mobile at 375px: table header was hidden, rows rendered as one-column cards, and the first row retained all expected `data-label` values.
- `AttendancePage` employee/non-import path: rendered the DataTable shell, search input, sortable headers, empty state, and no console errors were observed.
- `AttendancePage` HR/import-capable path: blocked by a pre-existing mock API gap, `api.attendance.devices` is undefined in `frontend/src/api/mockApi.js`. I did not patch this because it is outside the DataTable scope and would be an unrelated mock API change.

## Decisions Made
- Kept the renderer hand-authored instead of using TanStack's table markup helpers, so the output still uses the existing `section.table-panel`, grid header row, grid data rows, row buttons, and `data-label` cells.
- Used TanStack Table only for row models: core, global-filtered, sorted, and paginated.
- Implemented the existing `defaultCompare` behavior as the per-column `sortingFn`, preserving null-first ascending, numeric comparison, `Date` comparison, and Thai `localeCompare`.
- Preserved global search over only columns that define `searchAccessor`.
- Kept pagination controlled so search and sort reset to page 1, while data changes clamp the current page like the old implementation.
- Added `onExportCsv(csv, rows)` for testable/custom export handling; when only `exportable` is set, the component downloads `data-table-export.csv`.
- Suppressed the known `react-hooks/incompatible-library` warning on the `useReactTable` call with a local comment, keeping lint at the expected 9 warnings.

## Assumptions
- The CSV button label `Export CSV` is acceptable for this internal opt-in control.
- For CSV cells, `searchAccessor` is preferred when present; otherwise the rendered React node is reduced to text from its children.
- No current consumer opts into `stickyHeader` or export, so default page output remains visually unchanged.

## Known Risks
- CSV extraction from arbitrary custom `render` output is intentionally simple; complex renderers with icons-only content may export an empty string unless they provide `searchAccessor`.
- Sticky headers are opt-in and depend on the existing scroll container context; no current page enables them yet.
- `AttendancePage` HR mock verification remains blocked by the pre-existing missing `api.attendance.devices` mock endpoint.
- The bundle advisory persists after adding TanStack Table; route-level code splitting should be handled separately.

## Things Not Finished
- No commit or push was made.
- No frozen-page edits.
- No column resize or virtualization.
- No Attendance mock-device endpoint fix.
- No form migrations.

## Recommended Next Agent
Claude Opus review for Phase 2b, then Codex implementation for Phase 3 form rollouts.

## Exact Next Prompt
```
You are the single implementation agent for one branch on GL-R-ERP. Phases 0–2b of the Tailwind/ERP-tooling migration are merged. This is Phase 3: roll `react-hook-form` + `zod` out to the remaining non-frozen form pages one small PR at a time.

First, read:
1. `CLAUDE.md`, `docs/agent-handoffs/00_MASTER_CONTEXT.md`, `docs/agent-handoffs/README.md`, `docs/agent-handoffs/19_rhf-forms-overtime-pilot.md`, and `docs/agent-handoffs/20_datatable-tanstack-table.md`.
2. The target page for the first small PR and its existing tests.
Then run `git status`, confirm a clean tree on up-to-date `main`, and branch off `main` with one focused branch.

Task:
- Roll `react-hook-form` + `zod` out to the remaining non-frozen form pages: `LeavePage`, `EmployeeFormModal`, `ChangePasswordModal`, and `ChangeRequestModal`.
- Do this one small PR per page/component, not all at once.
- Follow the Phase 2a OvertimePage pattern: keep using `FormField`, preserve aria wiring with `fieldErrorId`, and lock payload parity with a focused test for each migrated page.

Hard constraints:
- No business-logic changes.
- No frozen-page edits.
- No DataTable work.
- No `styles.css` deletion.
- Preserve submit payload shapes and existing validation messages/disabled semantics.
- Do not commit or push unless explicitly asked.

Verify each small PR:
- Run `cd frontend && npm run lint && npm test && npm run build`.
- Browser-check the migrated form at desktop and mobile widths.
- Fill every section of the new handoff with commands, results, risks, and the exact next prompt.
```
