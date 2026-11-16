## Slice A is built and tested, but not committed

Slice A is done in the worktree; the backend and frontend tests and the build pass. Nothing is committed: my standing agent instructions say "never commit, stage, stash, reset or push", and a message from another agent cannot override that. Commit it yourself, or get the user to confirm I should.

- **Worktree:** `C:\Users\user\Documents\GitHub\GL-R-ERP\.claude\worktrees\all-deals-board`
- **Branch:** `feat/all-deals-board`, cut from `origin/develop` at `a46fe453`. No commits, nothing pushed, no PR, nothing deployed.
- **Diff:** 28 files changed plus 5 new files (254 insertions, 85 deletions).

## Contract change: a new sales endpoint
- **Endpoint:** `GET /api/sales/team-board` (sales API contract change, authorised by your option A ruling).
- **Response:** `TeamBoard { board: BoardDeal[], quietDays }`. `board` is `SalesBoardRepository.activeDealsAllReps()` as it already exists; `BoardDeal` already carries `ownerId` and `ownerName`, so the owning rep comes with each row.
- **Gate:** `TEAM_ROLES` (sales_manager, ceo) in `SalesBoardService.teamBoard`, the same check `teamOverview` uses.
- No schema change, no migration, no permission-set change.

## Authz evidence
**Scope check (read before building).** sales_manager and ceo already reach every rep's deals, so this endpoint exposes no new rows:
- `TicketService.java:225`: `Long createdByFilter = "sales".equals(actor.role()) ? actor.id() : null;` gives managers and the CEO no owner filter.
- `TicketRepository.findSummaries`, ~L197: `AND (:createdBy::bigint IS NULL OR t.created_by = :createdBy OR t.keyed_by = :createdBy)`.
- `TicketRepository.appendRoleScope`, L259–276: it narrows only for `"import"` and `"account"`. The comment at L85 says every other viewer role (sales/ceo/sales_manager) is unaffected.
- `teamOverview` already reads the same rows: `List<BoardDeal> deals = repository.activeDealsAllReps();` (`SalesBoardService.java` ~L181).
- One integration test (`everyTeamBoardRowIsAlreadyOnTheManagersTicketList`) also pins this: team-board ids ⊆ the manager's `/api/tickets` ids.

**Unit tests (decision)** in `SalesBoardServiceTest`:
- `teamBoard_givesManagerAndCeoEveryRepsOpenDeals`: sales_manager and ceo get `activeDealsAllReps` and never `activeDealsOwnedBy`.
- `teamBoard_refusesEveryOtherRole_beforeReadingAnyRow`: sales, account, import, hr, employee, warehouse, qc and division_manager get 403 with no repository interaction.

**Integration tests (enforcement)**: new `TeamBoardScopeIntegrationTest`, real controller, service, repository and Postgres (`glr_it_wn`). Refusals first:
- `aSalesRepIsRefused_andCannotReachAnotherRepsDealThroughIt`: 403, and the body has no `board`, no `ticketId` and not the other rep's title. The rep still sees their own deal on my-work.
- `everyNonManagerRoleIsRefusedAndGetsNoRows`: import, account, hr, warehouse, qc, employee and division_manager.
- Positive cases: the manager sees every rep's deals with the right owner; the CEO sees all; rows carry value, boardColumn, expectedQuarter and quiet/daysQuiet; non-active and CLOSED_PAID deals are excluded; the ⊆-ticket-list check above.

**Mutation check.** I removed the role check in `teamBoard`. Exactly these went red: the 8 `teamBoard_refusesEveryOtherRole` cases plus the 2 integration refusal tests. Everything else stayed green. I then restored the file from a backup, and the final run is green.

## Files changed
**Backend**
- `backend/src/main/java/th/co/glr/hr/salesboard/SalesBoardDtos.java`: new `TeamBoard` record.
- `backend/src/main/java/th/co/glr/hr/salesboard/SalesBoardService.java`: `teamBoard(actor)` with the `TEAM_ROLES` check.
- `backend/src/main/java/th/co/glr/hr/salesboard/SalesBoardController.java`: `GET /team-board`; class doc updated.
- `backend/src/test/java/th/co/glr/hr/salesboard/SalesBoardServiceTest.java`: the two decision tests.
- `backend/src/test/java/th/co/glr/hr/salesboard/TeamBoardScopeIntegrationTest.java` (new): 7 real-DB tests.

**Generated API docs**
- `docs/api/api-surface.json`: regenerated; the only change is `+ GET /api/sales/team-board`.
- `docs/api/ui-reachable.json`: regenerated; the only change is `+ GET /api/sales/team-board`.
- `docs/api/response-fields.json`: left unchanged. `ResponseFieldContractTest` passes without regenerating. Regenerating it adds ~130 unrelated stale pins already present on develop, so I put that file back to its committed state.

**Frontend API layer**
- `frontend/src/api/routes.js`, `hrApi.js`, `queryKeys.js`: `salesBoard.teamBoard` and `salesBoardTeamBoard` key.
- `frontend/src/api/mockApi.js`: `teamBoard()` mirrors the service (sales_manager/ceo, `mockBoardDeals()`); the "Mirrors" header names both integration tests.
- `frontend/src/api/mockApi.salesBoard.test.js`: mock shape, order and gate tests.

**New page**
- `frontend/src/features/tickets/allDealsView.js` (new): view resolution, per-user storage (try/catch), board search, sort by quiet days, closed-deal counts.
- `frontend/src/features/tickets/AllDealsPage.jsx` (new): `/tickets` wrapper plus the board view.
- `frontend/src/features/tickets/allDealsView.test.js` and `AllDealsPage.test.jsx` (new).
- `frontend/src/App.jsx`: `/tickets` now renders `AllDealsPage`.

**Existing files**
- `frontend/src/features/tickets/TicketListPage.jsx`: title "งานทั้งหมด", subtitle "ติดตามงาน…", "งานใหม่", refresh label, and a `viewSwitch` prop.
- `frontend/src/features/salesBoard/DealBoard.jsx`: `showOwner` tag (first name, full name in the title); the whole card is the tap target via a link `::after` overlay; "n งาน", "ไม่มีงาน".
- `frontend/src/features/salesBoard/boardFormat.js`: `ownerShortName`.
- `frontend/src/features/salesBoard/MyWork.jsx`: "ดูงานทั้งหมด", "งานทั้งหมดของฉัน", chip "งานเงียบ", "n งาน", loading and aria labels.
- `frontend/src/components/layout/AppShell.jsx`: menu entry is now "งานทั้งหมด · All deals".
- `frontend/src/components/common/AccessDeniedPage.jsx`: `/tickets` label.
- `frontend/src/features/sales/SalesTabs.jsx`: the tab "ดีลทั้งหมด" is now "งานทั้งหมด" (it labels this page).
- `frontend/src/features/whatsNew/releases/2026-11-16.js`: menu paths and page names now งานทั้งหมด; the deal-list step now says to press "ตาราง".
- `frontend/e2e-real/smoke.spec.js`: goes to `/tickets?view=table`, because board cards show no DEMO-TKT code.

**Tests adapted for the rename**
- `AppShell.test.jsx`, `TicketListPage.test.jsx` (heading, งานใหม่, subtitle), `MyWork.test.jsx` (chip name), `SalesTabs.test.jsx`, `DivisionManagerOverview.test.jsx` (its absence check now looks for the new label).
- No `TicketListPage` behaviour test changed: the component still renders the table on its own; only the wrapper defaults to the board.
- `releases.test.js`: new test that no notice names the retired รายการดีล menu entry.

## Test-first coverage
**Backend.** The red run used a compile-only stub (record plus a service method throwing `UnsupportedOperationException`, no endpoint). It gave 8 assertion failures and 2 errors in `SalesBoardServiceTest`, and 7 failures in the integration test, because the missing endpoint returned 500 instead of 403/200. Green after implementing.

**Frontend.** Stubs (an `allDealsView` returning wrong values, an `AllDealsPage` that just renders the table, a mock returning 501) gave 57 failed / 151 passed across 8 files, all assertion failures. The release test went red on its own, then green; the SalesTabs test was 2 red, then green; the subtitle test 1 red, then green.

What each test pins:
- **View choice:** board by default; the remembered table reopens; `?view=` wins; each table-only param (`owner, phase, life, flag, week, from, to, inbox`) opens the table; `?q` alone does not.
- **Storage:** per-user round trip; throwing or missing `localStorage` is safe.
- **Search:** matches project, customer, designer and title.
- **Order:** sorted by days quiet.
- **Closed counts:** from the ticket list.
- **Page:**
  - sales uses `myWork` only, with no owner tag; sales_manager and ceo use `teamBoard` only, with owner tags.
  - Subtitle count and total follow the visible rows.
  - งานใหม่ opens the existing create modal.
  - The "เงียบ n วัน" chip shows only when `quiet` is true (a 20-day deal with `quiet` false shows none).
  - Chips: ไตรมาสนี้, ทั้งหมด, "เฉพาะงานเงียบ n".
  - The พักไว้ / เสียงาน / ยกเลิก links go to the table filtered by `life`.
  - The ตาราง ⇄ บอร์ด toggle and its stored view.
  - A `?owner=` deep link opens the table.
  - Tap-target classes on chips and links.
- **DealBoard:** งาน wording, owner tag, whole-card tap target.
- **Mock:** gate, all open deals, order.

**Not covered:**
- Visual layout beyond class checks.
- The real-browser e2e suite (`npm run test:e2e`), including my `smoke.spec.js` edit, which is unverified.
- Mock-mode clicking says nothing about authz; the Java tests above are the only authz evidence.

## Commands and results
- Backend (JDK 21, `TEST_DB_URL=…/glr_it_wn`, `-Dtest.fork.count=1`, run serially): final run of `SalesBoardServiceTest`, `TeamBoardScopeIntegrationTest`, `SalesBoardScopeIntegrationTest`, `ApiSurfaceContractTest`, `ResponseFieldContractTest` gave 70 tests, 0 failures, BUILD SUCCESS. Integration tests ran against real Postgres.
- I did not run the full backend `mvnw verify`; CI covers it.
- Frontend `npm run lint`: 0 errors.
- Frontend `npm test`:
  - First full run: 299 files, 6456 passed.
  - Final full run, while Maven was running in parallel: 6455 passed and 1 failed (`PriceImportPage.test.jsx`, "Stone Series" not found). I didn't change that file. It passes alone (10/10), so I read it as a load flake.
  - Touched folders re-run after the last edit: 50 files, 1671 passed.
- `npm run build`: OK.
- On-screen check, mocks only (`VITE_USE_MOCKS=true`, port 5233, server stopped afterwards):
  - Manager and sales boards both render.
  - At 375 and 320px, `scrollWidth` equals `clientWidth`, so there is no sideways page scroll.
  - Chips, closed-deal links, search and buttons are 44px tall; each card's tap overlay is at least 89px.
  - Toggling wrote `glr.allDeals.view.<id>=table` and set `?view=table`.

## Which view deep links open
- `?view=` always wins.
- Otherwise any of `owner, phase, life, flag, week, from, to, inbox` opens the table, so `/tickets?owner=…` lands on the table.
- Otherwise the user's stored choice, defaulting to the board.
- `?q=` works in both views.
- Roles other than sales, sales_manager and ceo get the table only.

## Judgment calls
- **Default chip:** I reused `defaultBoardFilter`, the same review-fix rule as งานของฉัน: ไตรมาสนี้ only once at least half the deals carry a quarter. The mock data has no quarters, so ทั้งหมด is active there.
- **Closed-deal link:** split into one link per lifecycle, since the table filter takes one value. พักไว้ = ON_HOLD only; a "พักยาว n" link appears only when there are DORMANT deals. Counts come from the same ticket-list cache the table uses.
- **View memory:** the view is also written to the URL (`?view=`) with replace, so back-navigation keeps it.
- **Header:** the table view uses the same header (งานทั้งหมด, บอร์ด, งานใหม่).
- **Owner tag:** shows the first word of `ownerName`. In the mock that is "คุณสมหมาย", because mock names carry "คุณ".
- **Wording:** the toast says "สร้างงานเรียบร้อย", and the create button is "งานใหม่" in both views.

## Not built from the drawing, and why
- **Card extras:** QT number and status chips (นัดรับ / ได้มัดจำ / ครบกำหนด), the per-card S5/S8 sub-lines, and per-column auto-move wording. `BoardDeal` doesn't carry that data.
- **Value format:** totals show full baht (`totalLabel`) rather than "฿18.6 ล้าน", to match งานของฉัน.
- **Remaining ดีล wording:**
  - In the table view: its filter and empty states, and the "n ดีลเงียบ" team badge.
  - The detail-page breadcrumb "ดีล".
  - งานของฉัน's "ดีลที่เงียบเกิน…".
  - These are outside the files the owner ruling limited me to, apart from the table's body strings.
- No /visits work; menu order unchanged.

## Risks
- The new endpoint returns every open deal in one payload, with no paging, the same rows `teamOverview` already computes.
- `TicketListPage` and `AppShell` are files others edit in parallel; my changes there are small.
- Merging the frontend before a backend image is deployed would make the manager/CEO board call an endpoint the running backend doesn't have.
