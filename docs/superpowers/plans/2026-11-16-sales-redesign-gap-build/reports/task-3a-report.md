# Task 3a report — deal lifecycle dialogs (slice C1)

**Status: DONE_WITH_CONCERNS** (concerns listed at the end; none blocks review).

**V219 IS A SCHEMA MIGRATION — it needs an owner-approved backend deploy** (image build + Render deploy; Flyway applies
it at boot). `backend/src/main/resources/db/migration/V219__deal_pause_return_on_and_cancel_reasons.sql`. V219 verified
free on every local/remote branch and worktree before writing (`git ls-tree` loop found nothing above V218).

Worktree `C:\Users\user\Documents\GitHub\GL-R-ERP\.claude\worktrees\deal-dialogs`, branch `feat/deal-dialogs`, cut from
local `feat/deal-page-redesign` @ b9b0f9bc (= origin). **Uncommitted** (my rules forbid committing/staging).

## Contract / schema changes (all sales-scope, stated plainly)

1. **Migration V219** (additive): `sales.ticket.pause_return_on DATE` (nullable, no default, no backfill);
   `chk_ticket_cancel_reason` re-declared with 3 new codes added (4 old kept); `COMMENT ON COLUMN imported_value` updated.
2. **POST /api/tickets** (`CreateTicketRequest`):
   - `entryChannel` **no longer @NotBlank** — absent/blank is stored `UNSPECIFIED`. Reverses the 2026-09-30 "must state a
     real channel" ruling at the boundary, as the brief authorised. Explicit `UNSPECIFIED` and unknown values are still 400.
   - new optional `designerName` (@Size 200) → `sales.ticket.designer_name`.
   - new optional `estimatedValue` (@DecimalMin 0, @Digits 12,2) → `sales.ticket.imported_value` (see judgment call 1).
   - new optional `startStage`: `LEAD_APPROACH` (default) or `SPEC_APPROVED`; anything else 400. Starting at S2 runs the
     SAME `requireStageWriteAccess(…, SPEC_APPROVED, actor)` a hand move runs; a refusal is 403 and the create rolls back
     (relies on `create()`'s existing `@Transactional`). Records a `STAGE_CHANGED` event "เริ่มที่ S2 · สเปคผ่าน".
     The readiness gate is NOT run for a start stage (judgment call 3).
3. **POST /api/tickets/{id}/hold** and **/dormant**: body is now `PauseRequest {note, pauseReturnOn}` (was `NoteRequest
   {note}` — wire-compatible superset). Past date (Asia/Bangkok) → 400, checked AFTER the existing authz checks.
   **POST /{id}/resume** clears `pause_return_on`.
4. **TicketSummaryDto** gains `pauseReturnOn` (all summary reads, detail + list).
5. **DealCancelReason**: new `DUPLICATE_DEAL`, `WRONG_CUSTOMER_OR_PROJECT`, `NO_REAL_PROJECT`; `OFFERED` = those + `OTHER`;
   `RETIRED` = `OWNER_CANCELLED`, `PROJECT_SUSPENDED`, `BUDGET_CANCELLED`; `VALID` = all 7. **GET /api/meta/deal-stages
   `cancelReasons` now serves OFFERED (4 new-list codes) instead of the old 4** — same key, different values.
6. **GET /api/sales/my-work** `followUp` and **/team-overview** `stale` + `reps[].quietDeals` now include PAUSED deals whose
   `pause_return_on <= today (Bangkok)`. `BoardDeal` gains `returning` (bool) and `pauseReturnOn`. `board`, `openDeals`,
   `openValue`, `/team-board`, and the summary `stale` flag are unchanged (ACTIVE only).

Authorization: **no permission rule changed.** placeOnHold / markDormant / resume / cancel / markLost / reopen / create
keep their checks byte-identical and in the same order (new code is added after them). The return date is writable by
exactly whoever may hold/dormant today. `SalesBoardService` role gates untouched; the owner predicate is mirrored on the
new read.

## Authz evidence (real Postgres 16 via TEST_DB_URL, real controller → service → repository)

- `PausedDealFollowUpScopeIntegrationTest` (new, 11 tests, wrong-way-round): rep B's returning deal never in rep A's
  followUp (control: is in B's); date not arrived → out; no date → out; resumed → out; re-paused later → out;
  cancelled with arrived date → out; manager overview counts it as quiet for its own rep only, not as open; team-board
  excludes it; sales rep still 403 on team-overview.
- `DealLifecycleDialogsIntegrationTest` (new, 14 tests): grant holder (qc + can_create_quotation) cannot start at S2 —
  403 AND no row left (call wrapped in a real DataSource transaction, because the harness builds the service with `new`,
  no proxy); still can create at S1; another rep cannot cancel with a new reason, nor hold/dormant with a date (403,
  nothing written).
- **Mutation checks** (each reverted, final run green):
  1. drop `AND t.created_by = :ownerId` from `returningPausedDealsOwnedBy` → exactly
     `anotherRepsReturningPausedDeal_neverAppearsInMyFollowUp` red, nothing else.
  2. drop the `pause_return_on <= Bangkok today` predicate → `aPausedDealWhoseDateHasNotArrived_staysOut` and
     `rePausingWithALaterDate_dropsItOut` red (both genuinely depend on it), nothing else.
  3. drop `requireStageWriteAccess` from the S2 start → exactly `grantHolderWhoCannotMoveADealToS2_cannotStartOneThere` red.
- Frontend permission behaviour verified only under mocks → **unverified as authz**; the mock S2-start gate is an
  approximation and says so.

## Files changed

Backend main: `ticket/{CreateTicketRequest, DealCancelReason, DealStageMetaController, QuietRule, TicketController,
TicketRepository, TicketService, TicketSummaryDto}.java`, `salesboard/{SalesBoardDtos, SalesBoardRepository,
SalesBoardService}.java`, `db/migration/V219__deal_pause_return_on_and_cancel_reasons.sql`.
Backend tests: new `ticket/DealLifecycleDialogsIntegrationTest`, `ticket/DealCancelReasonTest`,
`salesboard/PausedDealFollowUpScopeIntegrationTest`; edited `SalesBoardServiceTest` (+2), `DealStageMetaControllerTest`
(+1), `CreateTicketRequestValidationTest`, `TicketControllerCreateValidationTest`.
Frontend: rewritten `TicketCreateModal.jsx`, `ReasonModal.jsx`, `MarkLostModal.jsx`, `CancelDealModal.jsx`; new
`PauseDealModal.jsx`, `MoveToS2Modal.jsx`, `dealDialogParts.jsx`; edited `DealStagePanel.jsx`, `TicketDetailPage.jsx`,
`DealHistoryPanel.jsx`, `stageMeta.js`, `salesBoard/{FollowUpList, MyWork, TeamOverview}.jsx`, `salesBoard/quietRule.js`,
`api/mockApi.js`, `data/dealStageCatalog.js`; **deleted** `dealDrafts.js` + `dealDrafts.test.js`.
Frontend tests: new `dealDialogs.test.jsx`, `FollowUpList.test.jsx`, `api/mockApi.dealDialogs.test.js`; replaced
`TicketCreateModal.test.jsx`; edited `DealStagePanel.test.jsx`, `TicketDetailPage.test.jsx`,
`TicketDetailPage.redesign.test.jsx`, `DealHistoryPanel.test.jsx`, `stageMeta.test.js`, `MyWork.test.jsx`,
`TeamOverview.test.jsx`, `api/mockApi.ticketsCreate.test.js`.
`hrApi.js` unchanged (no signature change; `hold/dormant(id, payload)` already pass a body). `docs/api/*.json` unchanged —
`ApiSurfaceContractTest` and `ResponseFieldContractTest` pass without regeneration.

## Test-first coverage list (red → green)

Backend red run (`red1.log`): 25 failures + 2 errors, all assertion failures on missing behaviour except the 2 errors =
`column pause_return_on does not exist` (the schema half of the feature, V219 absent). Green after implementation.
- CreateTicketRequestValidationTest: absent/empty/blank channel pass (3, inverted); negative estimate rejected;
  designerName > 200 rejected.
- TicketControllerCreateValidationTest: absent/null/blank channel reach the service (3, inverted); negative estimate 400.
- DealCancelReasonTest: OFFERED exact order; retired still valid; VALID = 7; every code labelled (new use งาน).
- DealStageMetaControllerTest: cancelReasons = the drawn four.
- SalesBoardServiceTest: followUp takes own returning deals, board does not, team read never touched; team overview
  counts returning as quiet not open, rep with only a returning deal gets a row.
- DealLifecycleDialogsIntegrationTest / PausedDealFollowUpScopeIntegrationTest: as listed under authz, plus: short
  form stores UNSPECIFIED/designer/value at S1 with no stage event; S2 start + event; bad start stage 400 and nothing
  written; explicit UNSPECIFIED still 400; new and retired cancel codes stored through the CHECK; hold/dormant store the
  date and GET returns it; no date → null; resume clears; past date 400 and deal stays ACTIVE.
Frontend red runs: TicketCreateModal 11/12 red (12th = "keeps add-new customer sub-form", a preservation test that
passes on both); dealDialogs 14/14 red (stubs); DealStagePanel 7 red; mockApi 11 red; FollowUpList 2 red;
TeamOverview 1 red; DealHistoryPanel 4 red. All green after.
- TicketCreateModal (12): drawn title/subtitle/5 fields, no channel/items/drafts; required project+customer errors;
  new project created under customer, payload has only drawn fields + defaults, no entryChannel; existing project chip /
  same-name reuse creates nothing; S2 + "1,200,000"; invalid value; designer sent by directory name; add-new customer
  sub-form; details keep เจ้าของงาน → ownerEmployeeId; server error shown; no localStorage writes.
- dealDialogs (14): m-lost chips with F1–F8 in order + stored value unchanged; m-cancel exactly the four, warning,
  กลับ/ยกเลิกงาน, sends DUPLICATE_DEAL; m-hold one dialog, chips compute from Bangkok today, ON_HOLD vs DORMANT, no date →
  null, disabled choice the server did not offer, month clamping; m-stage drawn copy, optional note, readiness case asks
  for date + note.
- DealStagePanel: hold/dormant open one พักงาน (4 assertions adapted from old titles); pause posts through the same
  handlers with pauseReturnOn; openMoveToS2 no-op without S2 verdict / past S1 / non-readiness block; S2 straight move;
  readiness case resends tracking fields (updateTracking replaces all) + logs activity then moves; UNSPECIFIED channel row.
- Page tests adapted (old dialog names → drawn ones; ขั้น 2 primary now "ย้ายไป S2 · สเปคผ่าน").
- FollowUpList / MyWork / TeamOverview: returning row label + ดำเนินการต่อ, no ปิดงานนี้/นัดเข้าพบ on it; label
  ปิดดีลนี้ → ปิดงานนี้.
- DealHistoryPanel: CANCELLED/MARKED_LOST event message codes render as labels (new + retired cancel codes, F-coded lost).
- mockApi: channel optional, short-form fields, start stage, return date store/clear/past-400, returning in followUp/stale
  not board, cancel new+retired valid.
Not covered: e2e-real (needs the real stack; not run); visual layout beyond the phone check below; Bangkok-vs-UTC date
boundary at the hours where they differ (the test compares against `LocalDate.now(Asia/Bangkok)` but the run time
decides which side of the boundary is exercised).

## Commands and results

- Backend (portable JDK 21, Postgres 16 at :54329/glr_it_wn, `-Dtest.fork.count=1`, targeted classes):
  - new + changed classes, final run after mutation reverts (final.log): 94/94 green — PausedDealFollowUpScope 11,
    DealLifecycleDialogs 14, SalesBoardServiceTest 39, CreateTicketRequestValidation 7, DealCancelReason 4,
    DealStageMetaController 11, TicketControllerCreateValidation 6, ApiSurfaceContract 1, ResponseFieldContract 1.
  - adjacent classes: DealEntryAccessIntegrationTest 16/16, ApiSurfaceContractTest 1/1, ResponseFieldContractTest 1/1,
    SalesBoardScopeIntegrationTest 24/24, TeamBoardScopeIntegrationTest 7/7, EntryChannelIntegrationTest 14/14,
    QuietRuleIntegrationTest 17/17, TicketStatusMachineIntegrationTest 28/28, TicketSummaryDtoJsonTest 1/1,
    TicketSummaryDtoSerializationTest 5/5, TicketServiceTest 184/185 — the 1 error is
    `getQuotationPdf_rendersFromSnapshotNotLiveEditedItems`: "LibreOffice not found" (environment, untouched code).
  - Full backend suite NOT run (left to CI per brief).
- Frontend: `npm run lint` clean; `npm run build` ✓; `npm test` 307 files / 6620 tests green. One more adapted test
  surfaced by the full run: `ticketItemFields.test.jsx` "shared usage pin" no longer lists TicketCreateModal.jsx (it has no
  item editor now; DealItemsPanel stays pinned). No load flake observed.
- Phone check (mock dev server from this worktree, 360×740): สร้างงานใหม่, พักงาน, ปิดงาน · เสียงาน, ยกเลิกงาน, ย้ายไป S2 —
  no horizontal scroll, panel = viewport width, footer (confirm) at the bottom edge on screen, every button/input ≥ 44px
  (one 19px toggle found and fixed). Drove the new create form end to end in the mock and moved the new deal to S2.
  Screenshots not possible (pane hidden); measured via DOM.

## Drawn elements not built / built differently, and why

- **ลูกค้า / บริษัท is marked required** (drawing shows no asterisk): the server requires a project and a project can only
  be created under a customer (POST /customers/{id}/projects); customer-less projects would need a new API. Project name
  stays first, as drawn; a typed name is matched to the customer's projects at submit.
- **ผู้ออกแบบ** uses the existing directory DesignerPicker (imported from features/quotations, not modified) — a combobox,
  not the drawn token chip.
- **วันที่จะกลับมาดู** has no default selection (drawing shows "2 เดือน" on): optional field, a pause without a date
  behaves as before.
- **"รายละเอียดเพิ่มเติม (ไม่บังคับ)"** — a collapsed extra in m-create holding ชื่องาน, ความสำคัญ, เจ้าของงาน,
  วันติดตาม, หมายเหตุ: nothing on the deal page can set those after creation, and เจ้าของงาน is how admin staff key deals
  for reps. The follow-up date still defaults to +14 days (as before) so the S2 move's readiness gate is satisfiable.
- m-stage shows extra fields only when the server's readiness gate is unmet (date when none, note made required when no
  activity since the last stage change).

## Capability kept behind a secondary control

- LEAD_APPROACH → PRESENTATION and every other hand move: ⋯ "เลื่อนไป: …" and ⋯ "แก้ไขสถานะ…" (UpdateStageModal),
  unchanged gates. The guided follow-up+activity flow still drives non-S1 stages' primary button.
- Moving from ON_HOLD to DORMANT: ⋯ "พัก dormant" opens the one พักงาน dialog preselected ไม่เคลื่อนไหว (พักชั่วคราว
  disabled there — the server refuses hold on a paused deal).
- Dropped create fields now live: items → deal page รายการสินค้า; channel → new "ยังไม่ได้ระบุช่องทางงาน · แก้ช่องทางงาน"
  row in the stage panel (EntryChannelFix, shown only when the server advertises SET_ENTRY_CHANNEL); customer/project
  detail → add-new sub-form + customer pages; title/priority/owner/follow-up/note → the collapsed details.
- Drafts removed cleanly; already-saved browser drafts are left in localStorage (not purged, nothing reads them).

## Judgment calls

1. **มูลค่าโดยประมาณ → `imported_value`** rather than a new column: V215 already defines it as "the rep's stated deal value,
   shown on the board until a quotation gives one"; the create dialog is a second writer of the same fact. Column comment
   updated in V219. The name says "imported". A reviewer may prefer a new column.
2. **Returning paused deals are not "quiet"**: `quiet` stays ACTIVE-only; a separate `returning` flag; they are counted in
   followUp/stale/quietDeals only. A returning deal leaves the list only by resume or a re-pause with a later date (per
   ruling) — logging a call does not remove it. Re-pausing ON_HOLD→ON_HOLD is still impossible (no transition change);
   ON_HOLD→DORMANT with a new date works.
3. **No readiness gate on an S2 start**; the stage WRITE gate is enforced.
4. **canMoveDealToS2 reads only the SPEC_APPROVED stage decision** (allowed, or blocked solely by the readiness message):
   the same reach as the old `advanceAwaitsReadiness` button; the server re-checks.
5. Dialogs use `placement="sheet-on-phone"`.
6. Data-action of the S1 primary stays `advance_stage` (e2e/test anchor), label changes.
7. Cancel/lost event messages keep storing the raw code; the history panel labels it (the only display of a cancel reason
   found: no backend report/export reads `cancel_reason`; notifications use `DealCancelReason.label`, now covering all 7).

## Concerns / what a reviewer should attack first

1. Judgment call 1 (`imported_value` reuse) and the reversal of the 2026-09-30 channel rule — both are product calls.
2. The S2-start rollback depends on `TicketService.create`'s Spring `@Transactional` proxy; the integration test proves
   it with an explicit transaction, not the proxy itself.
3. **Pre-existing bug found, not fixed (out of scope):** `PUT /tickets/{id}/tracking` replaces every tracking column, and
   DealStagePanel's existing guided-advance flow sends only `nextFollowUpAt` + `winProbability` — so it silently nulls
   `designer_name` / `owner_name` / `buyer_name`. My S2 dialog resends them; the old flow does not.
4. Mock `tickets.create` omits the "สร้างดีล" activity the real service inserts, so in mock mode a fresh deal's S2 dialog
   asks for a note that production would not ask for (pre-existing mock gap; not changed).
5. Wording: FollowUpList's empty-state and section copy still say ดีล in places I did not own (only the button I touched).
