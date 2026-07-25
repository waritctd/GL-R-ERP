# Phase 4A Plan - Ticket List Repair

## Preflight Record

- Branch: `refactor/ui-phase-4-ticket-worklist`.
- Working tree: not clean before this plan. Existing dirty files include HR leave/payroll backend and frontend files, `docs/agent-handoffs/july-2026-leave-import.md`, and untracked `docs/ui-repair/`.
- `origin/main` comparison: `git rev-list --left-right --count origin/main...HEAD` returned `0 0`; the branch is not behind local `origin/main`.
- Phase documents: `PRODUCT.md`, `DESIGN.md`, and `docs/ui-repair/03-design-foundation/*` are present in the working tree. Strict git check found no tracked files under `docs/ui-repair`, so the branch does not yet contain the Phase 0-3 repair docs as tracked branch content.
- AGENTS: no root or nested `AGENTS.md` existed. Root `AGENTS.md` was added with repo-specific UI repair instructions.
- Package scripts: `frontend/package.json` defines `lint`, `test`, `build`, and `test:e2e`.
- Playwright: `frontend/playwright.config.js` runs `./e2e`, Chromium only, one worker, mock Vite server at `127.0.0.1:5250` with `VITE_USE_MOCKS=true`.
- Baseline `npm run lint`: pass with 1 warning, `frontend/src/features/payroll/PayrollPage.jsx:312:6 react-hooks/exhaustive-deps`.
- Baseline `npm run test`: pass, 64 files / 560 tests. Non-failing stderr included jsdom navigation warnings and one unmatched route warning for `/tickets/701/deposit`.
- Baseline `npm run build`: pass, Vite built 315 modules.
- Baseline `npm run test:e2e`: first sandboxed run failed to start webServer with `listen EPERM 127.0.0.1:5250`; escalated rerun passed, 25/25 tests in 56.9s. Existing logged noise included Vite `/api/auth/login` proxy ECONNREFUSED, repeated `PricingRequestDetailPage` unique-key warnings, and non-asserted RBAC drift log.

## Current Problems Being Repaired

- Focus visibility is incomplete because Phase 3 cites `outline:none` stripping focus in `styles.css` (A-03 / F-08; `COMPONENT_CONTRACTS.md` cross-cutting focus and Button contract).
- The ticket list and affected shared surfaces still use legacy `primary-button`, `secondary-button`, and `icon-button` classes where the Phase 3 Button contract requires the cva `<Button>` as the single button system (F-13; `LEGACY_STYLE_MIGRATION.md`).
- `DataTable` currently renders clickable rows as `<button role="row">` and may contain interactive descendants, violating the Phase 3 table contract for no button-in-button and correct table/grid structure (A-01 / F-02 / A-02 / F-07; `COMPONENT_CONTRACTS.md` Data table).
- Record opening is implicit through a clickable row. Phase 3 requires one explicit open affordance with sibling row actions, not nested controls (Data table contract).
- Table load errors are caller-owned toast behavior today on `/tickets`; Phase 3 requires a standard inline retry state for table errors (Data table partial error state + Inline alert contract).
- Ticket filters are visually fragmented: phase cards, inbox toggle, lifecycle/flag expander, and `DataTable` search all work but do not yet follow the Phase 3 Filter bar regions. Preserve URL-backed `q`, `phase`, `life`, `flag`, and `inbox`.
- Mobile record cards already exist for `/tickets`, but the shared DataTable must make desktop table to mobile record-list semantics deliberate and not a squeezed grid (Responsive appendix "deal list usable with friction"; Mobile record card contract).
- Tablet shell behavior is the weakest band: Phase 3 identifies 721-1040 as the token gap and F-01 root cause. This slice only applies the deliberate tablet treatment needed to keep `/tickets` usable inside the app shell.
- Touched styles must adopt Phase 3 semantic tokens and avoid new one-off colors, radii, spacing, buttons, or breakpoints (`TOKENS.md`, D-T1/D-T4, `LEGACY_STYLE_MIGRATION.md`).

## Files Likely To Change

- `frontend/src/components/common/Button.jsx`: add `loading` behavior and icon-button accessible-name guard where feasible.
- `frontend/src/components/common/Button.test.jsx`: cover loading disabled/`aria-busy` behavior.
- `frontend/src/components/common/DataTable.jsx`: correct table semantics, separate row opening from row actions, standard inline error/retry state, Button usage for toolbar/pagination.
- `frontend/src/components/common/DataTable.test.jsx`: update semantic queries and add nested-action/open-affordance/error/mobile-card coverage.
- `frontend/src/features/tickets/TicketListPage.jsx`: move refresh/create/filter/search/list composition onto the repaired shared structure, add explicit open affordance, keep role-specific cards and URL params.
- `frontend/src/features/tickets/TicketListPage.test.jsx`: preserve create, filtering, URL search/filter, role projection, and navigation behavior.
- `frontend/src/styles.css` and/or `frontend/src/index.css`: global focus ring, tablet shell rules, legacy class replacements only where touched.
- Possibly `frontend/src/components/layout/AppShell.jsx` / `Sidebar.jsx`: only if the tablet shell treatment cannot be completed in CSS without markup support.

Out of scope even if nearby: `TicketCreateModal.jsx`, ticket detail panels, pricing request flows, finance/procurement/HR/payroll redesigns, route migration, backend, API, permissions, status-machine logic.

## Shared Component Impact

- Button: implement the Phase 3 loading prop as spinner + disabled + `aria-busy`, preserving default `type="button"` and mobile 44px floor. Use it on `/tickets` refresh/create and DataTable retry/export/pagination if loading is surfaced.
- DataTable: keep sorting/search/pagination/CSV behavior stable, but replace the clickable-row contract with semantic table/list markup and a caller-supplied explicit open control.
- DataTable error: add an inline alert/retry surface so `/tickets` can show query errors in context instead of relying only on a toast.
- Filter bar: do not create a broad new framework. Use a reusable structure or local helper only for the ticket list regions that Phase 3 names: search, filters, result count/active filters, clear/retry.
- App shell/tablet: use Phase 3 breakpoint intent (`<=720`, `721-1040`, desktop) without inventing arbitrary new breakpoints.

## DataTable Caller Inventory

Every production caller found by `rg "<DataTable" frontend/src`:

| Caller | Page | Role(s) | `onRowClick` | Cell contents | Mobile card | Row intent | Shared-contract impact | Existing tests |
|---|---|---|---|---|---|---|---|---|
| `frontend/src/features/employees/EmployeeListPage.jsx` | `/employees` employee directory | HR only (`canViewEmployees`) | Yes: navigate to `/employees/:id` | Avatar, `code`, `StatusBadge`; no buttons/links/inputs in cells | Yes: `EmployeeCard` | Navigate to employee detail | A row-button removal needs an explicit open affordance or semantic row action while preserving whole-row mouse convenience if kept. Low nested-control risk today because cells are static. | `EmployeeListPage.test.jsx` covers URL search/filter/sort behavior and rendered rows; `App.test.jsx` / `permissions.test.js` cover route access. No direct DataTable semantic test for employee row navigation beyond rendered rows. |
| `frontend/src/features/commissions/CommissionPage.jsx` | `/commissions` commission list/review | Sales, sales_manager, CEO, HR; account route exists for create-from-deal but list access is narrower | No | Expand icon button, approve/reject/edit/clawback icon buttons for reviewers, `StatusBadge`, manual/weight/mismatch badges; no row links; forms outside table contain inputs/selects | Yes: `CommissionCard` | Expand calculation detail and act on records; read-only for sales | High impact. This page already avoids `onRowClick` because action buttons would nest inside a row button. DataTable must support action cells and expanded rows without assuming row-as-button. | No direct `CommissionPage.test.jsx` found. `frontend/e2e/commission.spec.js` covers sales_manager manual commission creation and CEO approval. Dashboard tests cover commission summaries, not DataTable behavior. |
| `frontend/src/features/attendance/AttendancePage.jsx` | `/attendance` daily attendance list | All authenticated users; self view for employees, all/team view for HR/CEO/managers where allowed | Yes: `toggleExpanded` | `StatusBadge` via `StatusCell`, midday punch chip, text/time fields; no per-cell buttons/links/inputs in table cells | Yes: `AttendanceDayCard` | Expand/collapse punch detail, not navigation | Medium impact. Contract must preserve expansion state and `aria-expanded` without forcing a navigation affordance. Mobile card must remain the list form below 720px. | `AttendancePage.test.jsx` covers page rendering; `frontend/e2e/hr.spec.js` covers attendance route rendering for HR and employee. |
| `frontend/src/features/payroll/PayrollPage.jsx` | `/payroll` payroll workspace | HR only (`canManagePayroll`) | No | Action cell contains shared `<Button>` controls for detail selection and payslip download; `StatusBadge` elsewhere; payroll adjustment inputs live in side panel, not table cells | No | Select a payroll line via cell button; download payslip; side panel edits selected line | High shared-contract impact despite outside visual scope. DataTable must keep buttons inside cells valid. Missing mobile card is a known exception because payroll is desktop-oriented; do not force a mobile repair in Phase 4A. | `PayrollPage.test.jsx` covers preview/process/download/export/distribute and unpaid leave fields; `PayrollPage.carryForward.test.jsx` covers suggestion carry-forward. Existing lint warning at `PayrollPage.jsx:312`. |
| `frontend/src/features/tickets/TicketListPage.jsx` | `/tickets` deal list | Sales, sales_manager, CEO by current route gate; component also contains import/account projections and worklist distinction | Yes: navigate to `/tickets/:id` | `StatusBadge`, `StageProgressBar`, `DaysBadge`, manager `TrackingBadges`; no buttons/links/inputs inside DataTable cells today | Yes: `DealCard`; account uses `MoneyWorklistCard` | Navigate to ticket detail; preserve URL filters and role-specific projection | Primary Phase 4A target. Must replace implicit row-open with explicit open affordance, keep browser-back filter persistence, manager tracking column, account money card, import/account worklist distinction in component/harness without changing route permissions. | `TicketListPage.test.jsx` covers list rendering, create invalidation/navigation, lifecycle/flag filters, extra-filter expander, draft-status suppression. `salesViewScope.test.js`, `accountActions.test.js`, `importActions.test.js`, `permissions.test.js`, `deal-creation.spec.js`, `pcr-chain.spec.js`, and `deposit-fulfilment-close.spec.js` touch surrounding behavior. |
| `frontend/src/features/procurement/ProcurementListPage.jsx` | `/factory-purchase-orders` list, reused by `/procurement` fulfilment section | Import and CEO (`canManageProcurement`) | Yes: navigate to `/factory-purchase-orders/:id` | `StatusBadge`, text/code/money/date; no buttons/links/inputs inside DataTable cells | Yes: `PoCard` | Navigate to factory PO detail | Medium impact. Needs explicit open affordance if shared row navigation changes. No nested action risk in current cells. | `ProcurementListPage.test.jsx` covers rendered rows, row-click navigation, and status filter query; `ProcurementFulfilmentPage.test.jsx` verifies reuse; `ProcurementDetailPage.test.jsx` covers detail. |
| `frontend/src/features/pricingRequests/PricingRequestQueuePage.jsx` | `/pricing-requests` queue | Import, CEO, sales_manager for queue; sales may reach detail only, not bare queue | No | Request-code `Link`, `StatusBadge`, pickup `button` action when allowed | Yes: `QueueCard` with `Link`, `StatusBadge`, and pickup `button` | Navigate via link and act by pickup; row itself is not the action | High impact. This is the model for non-row-click action tables: DataTable must allow links/buttons in cells/cards and must not wrap a mobile card in a row button. Existing comments explicitly call out avoiding nested interactives. | `PricingRequestQueuePage.test.jsx` covers default filter, status refetch, pickup action, and hidden pickup for sales; `App.test.jsx` and `permissions.test.js` cover route guards; `pcr-chain.spec.js` and `deposit-fulfilment-close.spec.js` exercise queue flow. |

Contract conclusions:

- DataTable must not render any row or mobile card as a native `<button>` that can contain caller-supplied buttons/links. Commission, payroll, and pricing requests already require valid nested-interactive handling.
- Navigation and expansion are distinct row intents. Employee, ticket, and procurement rows navigate; attendance and commissions expand; payroll selects/acts; pricing requests link/act.
- A safe shared contract should be additive: support explicit per-row open/navigation affordance, support expanded rows, preserve action cells, and avoid forcing every caller to supply a mobile card immediately.
- The only caller without `mobileCard` is payroll; treat that as an out-of-scope desktop payroll exception rather than broadening Phase 4A.

## Ticket-List Role Matrix

- `sales`: `/tickets` pipeline browser, own-record behavior comes from the list API; can create tickets; sees standard deal card; no manager tracking column.
- `sales_manager`: oversight pipeline browser; cannot create; sees manager/CEO pipeline information and tracking column/card metadata.
- `ceo`: oversight pipeline browser; cannot create; sees manager/CEO pipeline information and tracking column/card metadata.
- `import`: code has worklist/all distinction via `?inbox=0`, import-focused card reason, and `dealInScope`; current route permissions exclude `/tickets` from `canViewDealPipeline`.
- `account`: code has worklist/all distinction via `?inbox=0`, account money-worklist card, and `dealInScope`; current route permissions exclude `/tickets` from `canViewDealPipeline`.

No permission or route-guard change is planned in this slice. Import/account ticket-list projections will be validated at component/harness level unless the route decision is explicitly reopened.

## Test Plan

- Re-run full baseline after implementation: `npm run lint`, `npm run test`, `npm run build`, `npm run test:e2e`.
- Focused unit tests:
  - `Button.test.jsx`: loading, disabled click prevention, `aria-busy`.
  - `DataTable.test.jsx`: semantic row/cell/header structure, no row button wrapping actions, explicit open affordance, inline error/retry, CSV unchanged.
  - `TicketListPage.test.jsx`: `q`, `phase`, `life`, `flag`, `inbox` URL persistence; list to detail navigation; create permission; role-specific card content.
  - `salesViewScope.test.js`: keep worklist/all scope rules unchanged.
- Focused Playwright or browser validation:
  - Login as sales, sales_manager, import, account, ceo.
  - Validate `/tickets` where route access exists; for import/account, record current route-gate behavior separately from component projection unless permission scope changes are authorized.

## Screenshot Plan

- Viewports: `390x844`, `768x1024`, `1024x768`, `1366x768`, `1440x900`.
- Roles: sales, sales_manager, import, account, ceo.
- Evidence per role/viewport:
  - Default list.
  - Search `q` applied.
  - Phase filter applied.
  - Lifecycle/flag filter visible and applied.
  - Import/account `inbox` default and `?inbox=0` all-record option where accessible or in harness.
  - Keyboard focus on refresh, create/open, filters, search, pagination.
  - Error/retry state by forcing ticket-list query failure in test harness.

## Rollback Boundaries

- Shared primitive rollback: `Button.jsx` + tests can revert independently if loading/focus breaks unrelated surfaces.
- DataTable rollback: keep API additive where possible (`error`, `onRetry`, explicit open affordance) so non-ticket callers can remain unchanged during rollback.
- Ticket page rollback: isolate `/tickets` layout/filter structure from route permissions and data APIs.
- CSS rollback: keep focus/tablet/touched legacy-button edits in labeled blocks; do not delete broad `styles.css` sections in this slice.

## Explicit Exclusions

- No `/tickets/new`, draft persistence, create-route selection, or TicketCreateModal redesign.
- No ticket detail tab architecture, pricing request redesign, CEO approval redesign, procurement, finance, HR, or payroll redesign.
- No backend, database migration, API endpoint, permission, status-machine, or navigation-route migration.
- No full `styles.css` deletion, whole-app Tailwind migration, dark mode, new component framework, animation library, font, or icon library.
- No unrelated baseline failure fixes.

## Risks

- Dirty worktree and untracked Phase docs make branch containment ambiguous; avoid touching existing dirty HR files.
- DataTable semantics can regress seven caller surfaces if the API is not additive.
- The import/account `/tickets` requirement conflicts with current route-gate comments; do not resolve by changing permissions in this phase.
- Tablet shell changes can affect every route; keep the CSS minimal and prove with screenshots.
- CSV behavior depends on rendered/text extraction; preserve output order and escaping unless a safe DataTable-contract fix requires adjustment.
- Existing E2E suite logs non-failing warnings; do not conflate them with Phase 4A regressions.

## Implementation Checkpoints

1. Add shared focus ring and Button loading behavior; update Button tests.
2. Repair DataTable semantics and inline error/retry without changing caller data contracts; update DataTable tests.
3. Convert only `/tickets` and DataTable-touched legacy button classes to `<Button>`.
4. Add explicit record-open affordance on `/tickets`; keep browser back preserving `q`, `phase`, `life`, `flag`, and `inbox`.
5. Rework the ticket-list filter structure around the Phase 3 regions while preserving every existing URL parameter and count convention.
6. Verify role-specific ticket projections for sales, sales_manager, import, account, ceo.
7. Apply the minimum tablet shell treatment required for 768/1024 ticket-list usability.
8. Run focused tests, then full baseline.
9. Capture rendered screenshots for all required viewport/role combinations.
10. Stop and report instead of silently fixing any unrelated failure.
