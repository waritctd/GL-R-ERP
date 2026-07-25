# Phase 4A Implementation

Date: 2026-07-25
Branch: `refactor/ui-phase-4-ticket-worklist`

## Scope Completed

- Implemented the shared interaction foundation for the ticket-list slice:
  visible global focus treatment, Button loading behavior, repaired DataTable
  semantics, explicit record-opening controls, inline table error/retry UI,
  mobile record-list semantics, and touched legacy button replacement.
- Reworked `/tickets` for the existing sales, sales_manager, and CEO route-gated
  roles without changing permissions, API contracts, statuses, or query keys.
- Preserved component-level import/account worklist and all-record behavior in
  `TicketListPage`; route-level `/tickets` access remains unchanged and blocked
  for those roles.
- Implemented a deliberate tablet shell icon rail for 721-1040px.
- Captured rendered evidence and repaired the portrait tablet ticket-list
  clipping failure by applying a tablet compact table treatment that preserves
  required scan fields and the explicit open action.

## Findings Addressed

- F-01 tablet shell: added a 721-1040px icon rail treatment with hidden text
  labels, accessible names, native tooltips, and active route preservation.
- A-01 / F-02 / A-02 / F-07 DataTable structure: replaced row-as-button and fake
  ARIA table structure with native table groups on desktop and list semantics on
  mobile.
- A-03 / F-08 focus: added a global focus-visible contract for interactive
  controls using semantic focus tokens.
- F-13 legacy buttons: replaced legacy button classes in the touched table,
  ticket-list, and procurement-list surfaces with the shared `Button`.
- Phase 3 Button contract: added loading, disabled activation, `aria-busy`, and
  accessible-name enforcement for icon buttons.
- Phase 3 table contract: added explicit row open controls, valid nested action
  handling, decorative skeletons, and inline retryable error state.

## Files Changed

Production source:

- `frontend/src/components/common/Button.jsx`
- `frontend/src/components/common/DataTable.jsx`
- `frontend/src/components/layout/Sidebar.jsx`
- `frontend/src/features/attendance/AttendancePage.jsx`
- `frontend/src/features/employees/EmployeeListPage.jsx`
- `frontend/src/features/procurement/ProcurementListPage.jsx`
- `frontend/src/features/tickets/TicketListPage.jsx`
- `frontend/src/styles.css`

Tests:

- `frontend/src/components/common/Button.test.jsx`
- `frontend/src/components/common/DataTable.test.jsx`
- `frontend/src/components/layout/AppShell.test.jsx`
- `frontend/src/features/employees/EmployeeListPage.test.jsx`
- `frontend/src/features/procurement/ProcurementListPage.test.jsx`
- `frontend/src/features/tickets/TicketListPage.test.jsx`

Docs and evidence:

- `AGENTS.md`
- `docs/ui-repair/04-production-repair/PHASE_4A_PLAN.md`
- `docs/ui-repair/04-production-repair/PHASE_4A_QA_INVENTORY.md`
- `docs/ui-repair/04-production-repair/PHASE_4A_QA_RESULTS.md`
- `docs/ui-repair/04-production-repair/PHASE_4A_VISUAL_QA_RESULTS.md`
- `docs/ui-repair/04-production-repair/PHASE_4A_IMPLEMENTATION.md`
- `docs/ui-repair/04-production-repair/PHASE_4A_QA_MATRIX.md`
- `docs/ui-repair/evidence/proposed/phase-4a-ticket-worklist/`

## Shared Contract Changes

- `Button` now supports `loading`, disables activation while loading, sets
  `aria-busy="true"`, preserves the visible label width by hiding label opacity,
  and renders one spinner only when loading is requested.
- `Button variant="icon"` now requires an accessible name through
  `aria-label`, `aria-labelledby`, or `title`; `title` is copied to
  `aria-label` as a fallback.
- `DataTable` no longer accepts or uses `onRowClick`.
- Desktop `DataTable` now renders a native `<table>` with `<thead>`, `<tbody>`,
  `<th scope="col">`, `<td>`, and `aria-sort` on sortable headers.
- Mobile `DataTable` now renders a `<ul>`/`<li>` record list when `mobileCard`
  is supplied and the viewport matches `<=720px`.
- `DataTable` loading skeleton rows are `aria-hidden`; the table/list panel is
  marked busy.
- `DataTable` supports `error`, `errorMessage`, `retryLabel`, and `onRetry` for
  a calm inline `role="alert"` retry state while preserving existing rows when
  rows are available.
- `DataTable` export and pagination controls now use the shared `Button`.

## DataTable Migration Notes

- `/tickets`: removed implicit row navigation and added one explicit
  `เปิดดีล` control per desktop row and mobile card.
- `/employees`: added explicit `เปิดข้อมูล` controls for desktop rows and mobile
  cards.
- `/factory-purchase-orders`: added explicit `เปิด` controls for desktop rows
  and mobile cards, and wired inline retry to the purchase-order query.
- `/attendance`: replaced row-click expansion with explicit scan-detail controls
  in desktop rows and mobile cards.
- `/commissions`, `/payroll`, and `/pricing-requests`: left as action/link
  tables because they already avoided `onRowClick`; the repaired DataTable keeps
  their nested buttons and links valid.
- Payroll remains the only inventoried DataTable caller without `mobileCard`.
  That exception is retained because payroll redesign is outside Phase 4A.

## Ticket-List Changes

- Page heading is now Thai-first: `รายการดีล`.
- Refresh and create use the shared `Button`; create remains visible only where
  existing `ROLE_PERMISSIONS[user.role]?.canCreateTicket` allows it.
- Added role scope summary copy for sales, sales_manager, CEO, import, and
  account without changing the existing role predicates.
- Preserved URL-backed `q`, `phase`, `life`, `flag`, and `inbox` behavior.
- Search was moved into the ticket filter bar while remaining URL-backed.
- Phase filtering remains five operational phase controls plus `ทุกเฟส`, with
  `aria-pressed` and compact counts.
- Lifecycle and flag filters moved into Thai-first additional filters:
  `สถานะงาน` and `สัญญาณงาน`.
- Added active-filter count, active-filter chips, clear-all behavior, and a
  filtered-empty state distinct from the true-empty state.
- Manager/CEO pipeline information remains role-specific and compact above the
  list.
- Account mobile cards continue to emphasize money work; import/account
  worklist/all distinctions remain in component tests and code paths.
- No cost, margin, or finance internals were added to unauthorized sales-visible
  rows/cards.

## Responsive Changes

- Added Phase 3 breakpoints in touched styles: mobile up to 720px, tablet
  721-1040px, desktop above 1040px.
- Mobile `/tickets` uses record cards with identity, project, stage/work reason,
  owner/date, freshness, progress, and one clear open action.
- Mobile filters use a bottom sheet when expanded and restore focus when closed
  through the visible close action.
- Tablet shell uses a collapsed icon rail with hidden labels, accessible names,
  native tooltips, and active route styling.
- Desktop ticket list keeps a compact summary/filter hierarchy so the list is
  visible in the initial viewport.

## Accessibility Changes

- Added global keyboard focus styling for buttons, links, summary, and custom
  role controls while suppressing pointer-click rings where `:focus-visible` is
  supported.
- Preserved existing input focus treatment without adding a second local focus
  system.
- Added accessible names to icon-rail links, brand home, refresh, pagination,
  close, and explicit open controls.
- Added `aria-pressed` to scope, phase, lifecycle, and flag filter controls.
- Added `aria-live` to result counts and `aria-busy` to table/list panels.
- Added DataTable inline error `role="alert"` with retry action and no raw
  exception text.
- Added explicit `aria-expanded` for attendance scan-detail controls.

## Legacy CSS Removed

- Removed legacy button classes from the touched `/tickets` page header/actions,
  filter controls, and DataTable export/pagination controls.
- Removed legacy button classes from touched procurement-list refresh/open
  controls.
- Replaced row-as-button table styling with native table/list styling in
  `styles.css`.
- No full `styles.css` deletion or Tailwind migration was performed.

## Legacy CSS Retained

- Legacy `.primary-button`, `.secondary-button`, `.icon-button`, and
  `.danger-button` definitions remain in `styles.css` for out-of-scope surfaces.
- `TicketCreateModal` still uses legacy button classes and remains reachable;
  it was only regression-checked for open/close behavior.
- Ticket detail, pricing request, procurement detail, finance, HR, payroll, and
  commission redesigns retain their existing legacy class usage unless touched
  by the DataTable contract.

## Deferred Work

- Decide in a later authorized phase whether import/account should route to
  `/tickets`; Phase 4A kept the current route guard unchanged.
- Fix mobile ticket filter sheet Escape behavior; the close button restores
  focus, but Escape did not close the sheet in the Playwright run.
- Consider migrating remaining legacy buttons only in the future slices that
  own those surfaces.

## Known Limitations

- The previous `shared/tablet-768x1024.png` visual blocker is resolved in the
  recaptured evidence. The tablet ticket table now keeps the explicit open
  action visible and lets long stage labels wrap.
- Import/account `/tickets` validation is blocked by current route permissions;
  related-record checks from their actual role landing pages passed.
- Step 12 E2E logged RBAC drift between
  `docs/ux-ui-audit/data/shoot-manifest.json` and the live route oracle,
  including import/account `/tickets` entries where the stale manifest allows
  the route and the live app redirects. This was logged by the existing E2E
  suite and not fixed silently.
- Live ticket-list error/retry could not be safely reproduced through network
  routing because the mock ticket list is served by the in-memory mock module;
  visual evidence uses a component-state harness and unit tests cover retry.
- The mock Vite app logs known `/api/auth/login` proxy `502` /
  `net::ERR_ABORTED` noise during quick-login fallback.
- Pre-existing full-suite baseline noise remains documented in
  `PHASE_4A_PLAN.md`; unrelated baseline issues were not fixed silently.

## Rollback Notes

- Roll back `Button.jsx`, `Button.test.jsx`, and the focus/spinner CSS together
  if the loading/accessibility contract causes a wider regression.
- Roll back `DataTable.jsx`, `DataTable.test.jsx`, and the caller migrations
  together because the `onRowClick` removal requires explicit caller controls.
- `/tickets` visual/layout changes are isolated to `TicketListPage.jsx` and
  ticket-prefixed CSS blocks in `styles.css`.
- Tablet shell rollback is isolated to `Sidebar.jsx`, `AppShell.test.jsx`, and
  the 721-1040px sidebar CSS block.
- No backend, API, database, permission, status-machine, or route migration was
  made, so rollback should not require server or migration changes.

## Validation Recorded

- Focused unit command executed on 2026-07-25:
  `npm run test -- Button.test.jsx DataTable.test.jsx TicketListPage.test.jsx AppShell.test.jsx EmployeeListPage.test.jsx ProcurementListPage.test.jsx`.
- Result: 6 files passed, 75 tests passed.
- `npm run lint`: passed with 0 errors and 1 existing warning at
  `frontend/src/features/payroll/PayrollPage.jsx:312:6`
  (`react-hooks/exhaustive-deps`).
- `npm test`: passed, 64 files / 585 tests. Non-failing stderr included the
  known jsdom navigation warning and unmatched `/tickets/701/deposit` route
  warning.
- `npm run build`: passed, Vite built 315 modules.
- `npm run test:e2e`: sandboxed run failed before tests with
  `listen EPERM 127.0.0.1:5250`; escalated rerun passed, 25/25 tests in 58.4s.
  Non-failing logs included mock `/api/auth/login` proxy `ECONNREFUSED`,
  `PricingRequestDetailPage` unique-key warnings, and the existing RBAC drift
  log.
- `git diff --check`: passed.
- Static searches requested in Step 12 were run. Diff-added `max-width: 720px`
  occurrences are test stubs for the existing mobile hook; no diff-added
  hardcoded hex/rgba values were introduced under the requested
  common/tickets/layout paths. Full-file searches still find retained legacy
  buttons and hardcoded values in out-of-scope ticket create/detail/common
  surfaces.
- Tablet repair rerun:
  `npm run test -- DataTable.test.jsx TicketListPage.test.jsx AppShell.test.jsx`
  passed, 3 files / 43 tests.
- Tablet repair browser QA rerun used `/private/tmp/glr-phase4a-qa/phase4a-qa.mjs`.
  Result: 25 role/viewport runs, 143 screenshots, 0 failures, 10 expected
  import/account route blockers, 3 mobile filter Escape warnings, 25 known mock
  login console/network errors.
- Step 10 visual evidence was recaptured under
  `docs/ui-repair/evidence/proposed/phase-4a-ticket-worklist/`; 21 PNG files are
  present, including repaired `shared/tablet-768x1024.png`.
- Playwright functional QA and visual QA are recorded separately in
  `PHASE_4A_QA_RESULTS.md`, `PHASE_4A_VISUAL_QA_RESULTS.md`, and
  `PHASE_4A_QA_MATRIX.md`.
