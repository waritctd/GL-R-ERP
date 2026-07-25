# Phase 4A Playwright QA Inventory

## Run Context

- Target: `/tickets`.
- Roles: `sales`, `sales_manager`, `import`, `account`, `ceo`.
- Viewports: `390x844`, `768x1024`, `1024x768`, `1366x768`, `1440x900`.
- Browser: Chromium through Playwright against Vite mock app.
- Evidence folder: `/private/tmp/glr-phase4a-qa/`.
- Interaction rule: use normal user actions (`click`, `fill`, keyboard keys, browser back). Do not rely on `page.evaluate()` as signoff interaction.

## Role Workflow Inventory

| Role | Required workflow | State to preserve | Permission/data checks |
|---|---|---|---|
| Sales | Open `/tickets`; search a deal; select a phase; open a deal; browser back; confirm search and phase remain; open and close create modal. | `q` and `phase` remain in URL and UI after browser back. | Create button visible; modal only regression-checked; no cost/margin internals. |
| Sales Manager | Open `/tickets`; inspect pipeline information; filter deals; open a deal; browser back. | Filter state remains after browser back. | Manager/CEO tracking information visible in list/card only where intended. |
| Import | Open `/tickets`; default scope is `ต้องดำเนินการ`; switch to `ทั้งหมด`; confirm work reason; open a deal or related record; browser back. | `inbox=0` all-record scope remains after browser back. | Import work reason visible; no create action. |
| Account | Open `/tickets`; default money-relevant work; confirm amount visibility; switch scope; open a deal; browser back. | `inbox=0` all-record scope remains after browser back. | Existing amount visibility preserved; no cost/margin internals introduced. |
| CEO | Open `/tickets`; view all deals; inspect manager pipeline information; filter deals; open a deal; browser back. | Filter state remains after browser back. | List does not imply false single-owner state for multi-role stages. |

## Viewport Assertions

For each role and viewport:

- No page-level horizontal overflow.
- No clipped controls in header, phase strip, filter bar, table/list, pagination, or tablet shell.
- Desktop/tablet use the repaired table structure where visible.
- Mobile uses record-list cards with one clear open action.
- Long Thai customer/project/owner/stage text remains readable without tiny text.
- Active route and role navigation remain intact.

## Instrumentation Checks

- Console errors.
- React warnings, especially nested interactive-control and key warnings.
- Failed network requests.
- `validateDOMNesting` warnings.
- Table/list `aria-busy` during loading.
- Filtered-to-empty copy after a search with no matches.
- Empty state when safely reproducible without changing app data.
- Error/retry state only when safely reproducible in a test harness; do not break the live mock session to manufacture an error.

## Keyboard Checks

For representative sales, import/account, and CEO runs:

- Tab reaches refresh, create when permitted, search, phase chips, scope controls, more-filters trigger, explicit open action, and pagination.
- Focus ring is visible against light content and the dark/tablet sidebar.
- Enter/Space activates buttons and the explicit open action.
- Escape closes the create modal and mobile filter sheet.
- Closing overlays restores focus to the opening control.

## Evidence Targets

- Screenshot after default load for every role/viewport.
- Screenshot after filter/search for every role at one desktop and one mobile viewport.
- Screenshot after browser-back state preservation for sales, import, account, and CEO.
- Screenshot of create modal open/closed regression for sales only.
- Screenshot of filtered-empty state at `390x844` and `1366x768`.
- Save machine-readable QA summary as `/private/tmp/glr-phase4a-qa/summary.json`.
