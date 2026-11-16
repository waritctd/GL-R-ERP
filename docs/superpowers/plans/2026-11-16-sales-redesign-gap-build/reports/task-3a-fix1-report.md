# Task 3a, fix round 1: report (deal lifecycle dialogs)

Worktree `C:\Users\user\Documents\GitHub\GL-R-ERP\.claude\worktrees\deal-dialogs`, branch `feat/deal-dialogs`, HEAD `3faef768`.
**Everything is UNCOMMITTED** (my rules forbid committing or staging). Nothing pushed, no PR opened, no deployed system touched.

**V219 is a schema migration. It needs an owner-approved backend image deploy.** It was edited in place this round: no
database outside the integration tests had applied it. The highest migration on `origin/develop` is still V218, re-checked
this session.

## 1. State found, per ruling

The previous fixer had implemented and tested nearly all of the round, and the tree compiled. A baseline targeted run on
real Postgres gave 331/332; the one error was the known LibreOffice environment limit. Read against each ruling:

| # | Ruling | Found | What I did |
|---|---|---|---|
| 1 | Channel required at creation | DONE. `@NotBlank` restored. The six validation tests match `b9b0f9bc` byte for byte (verified with `git diff b9b0f9bc`: only additions below them). Chips are in the form. The UNSPECIFIED row and `EntryChannelFix` usage were removed from DealStagePanel. Mock refuses absent/blank channels. | Mutation-checked |
| 2 | Items for creators who cannot add them later | DONE. `CreateDealItems.jsx` reuses `ticketItemFields.jsx`. `canEditDealItemsAfterCreate` mirrors `TicketService.editItems` (`SALES_ROLES={"sales"}`). Tests per role, plus the integration test `aManagerCreatedDealWithItemsPersistsThem`. | Mutation-checked |
| 3 | Estimate in its own column | DONE. Covers V219 `estimated_value NUMERIC(14,2)` with `chk > 0`, the insert, the summary DTO, the tracking set/keep/clear, the board and dashboard fallback, the reverted `imported_value` comment, the DealInfoCard edit, the mock, and the PipelineImport real-DB tests. | Mutation-checked. Ceiling cited (below) |
| 4 | Returning deals never crowded out | DONE. `rankFollowUp` puts every returning deal first, uncapped, then the top 5 (or top 20) quiet ones. MyWork's งานเงียบ view (where ดูทั้งหมด leads) lists returning deals ahead of quiet ones. Real-DB count-agreement test. | Mutation-checked. Fixed one e2e spec |
| 5 | Change return date in place | DONE. Hold on ON_HOLD, or dormant on DORMANT, changes the date and note only: a POLICY_CHANGED event, no lifecycle change, no notification. Dialog title "เปลี่ยนวันที่จะกลับมาดู" with the state and date preselected. Button beside ดำเนินการต่อ behind the RESUME gate. | Mutation-checked |
| 6 | Party names survive guided moves | PARTIAL. Names were resent, the estimate was not. | Added the estimate to both PUT /tracking resends in DealStagePanel, test-first (red then green) |
| 7 | Purge old drafts | DONE. `purgeLegacyDealDrafts` runs from `main.jsx`, once per browser, inside try/catch. Keys: `glr:draft-deal`, `glr:deal-drafts:index`, prefix `glr:draft-deal:`. Marker: `glr:deal-drafts:purged`. | Mutation-checked |
| 8 | Tests for surviving mutations | DONE: B2 and B6 with fixed clocks; B7 403-not-400; F7, F9, F10; F20 and F22 mock gaps. | All re-run: killed |
| 9 | Wording and stale pointer | DONE. The toast says สร้างงานเรียบร้อย everywhere (MyWork/AllDeals/TicketList), and the `DealShortCreateIntegrationTest` pointer is gone. | Mutation-checked |
| 10 | e2e-real sweep | PARTIAL. Only `write-quote-stage-gates.spec.js` had been updated. | Swept every spec (below). Fixed `sales-board.spec.js` |

Two further changes this round:
- **Reverted:** the previous fixer had made `TicketSummaryDto.withoutMoney()` (the import-role projection) pass `pauseReturnOn`
  through. The reviewed slice nulled it. I restored null, so no role sees a field it did not see in `afe6acbd`.
- **Added (test-first):** a test for the create form's S2 gate, which was untested. It is disabled for any role other than
  sales, sales_manager and ceo, mirroring `requireStageWriteAccess`. Note the server refuses those roles anyway.

## 2. Every contract and schema change of the WHOLE slice (for the PR body)

**Migration `V219__deal_pause_return_on_and_cancel_reasons.sql`.** One file, additive:
- `sales.ticket.pause_return_on DATE`: nullable, no default, no backfill.
- `sales.ticket.estimated_value NUMERIC(14,2)`: nullable, no default, with `chk_ticket_estimated_value CHECK (estimated_value IS NULL OR estimated_value > 0)`.
- `chk_ticket_cancel_reason` re-declared. It keeps V57's four codes (`OWNER_CANCELLED`, `PROJECT_SUSPENDED`,
  `BUDGET_CANCELLED`, `OTHER`) and adds `DUPLICATE_DEAL`, `WRONG_CUSTOMER_OR_PROJECT` and `NO_REAL_PROJECT`. Every value
  stored before (V57 four; V205/V206 wrote only `OTHER`) still passes.
- Column comments on both new columns. The `imported_value` comment is unchanged from V215.

**`POST /api/tickets` (CreateTicketRequest):**
- `entryChannel` is still `@NotBlank`, unchanged from base. UNSPECIFIED and unknown values are 400.
- New optional fields:
  - `designerName` (`@Size(200)`), written to `designer_name`.
  - `estimatedValue` (`@DecimalMin(0, exclusive)`, `@Digits(12,2)`), written to `estimated_value`. The service also
    refuses `<= 0`.
  - `startStage`: `LEAD_APPROACH` (the default) or `SPEC_APPROVED`; anything else is 400. `SPEC_APPROVED` runs the
    unchanged `requireStageWriteAccess(…, SPEC_APPROVED)`. A refusal is 403 and the create rolls back. It writes the
    `STAGE_CHANGED` event "เริ่มที่ S2 · สเปคผ่าน" and skips only the readiness gate.
- `items` behaves as before.

**`PUT /api/tickets/{id}/tracking` (TrackingUpdateRequest):**
- New optional `estimatedValue` (same bounds). Unlike the other tracking fields it is **kept when absent**, as
  `expectedQuarter` already is, so callers outside the tickets feature cannot wipe it.
- New optional `clearEstimatedValue: true` clears it.
- The permission is unchanged: the same `requireDealOwnership` and `requireActive`, with the new code after them.

**`POST /api/tickets/{id}/hold` and `/dormant`:**
- The body is now `PauseRequest {note, pauseReturnOn}`, a wire-compatible superset of `NoteRequest`.
- A past Asia/Bangkok date is 400, checked after the unchanged authorization.
- **Behaviour change (ruling 5):** hold on an ON_HOLD deal, or dormant on a DORMANT deal, used to be 409. It now updates
  only the date and note: a POLICY_CHANGED event "pause_return_on → …", no lifecycle change, no notification, no second
  pause event.
- Dormant on DORMANT → ON_HOLD is still 409. ON_HOLD → DORMANT behaves as before, including its notification.

**`POST /{id}/resume`** now clears `pause_return_on`.

**`TicketSummaryDto`** gains `pauseReturnOn` and `estimatedValue`. Both are null in `withoutMoney()`, the import-role
projection.

**`DealCancelReason`:**
- Gains `DUPLICATE_DEAL`, `WRONG_CUSTOMER_OR_PROJECT` and `NO_REAL_PROJECT`. The retired four stay valid and labelled.
- `GET /api/meta/deal-stages` `cancelReasons` now serves the OFFERED four (the three new codes plus `OTHER`).

**`GET /api/sales/my-work`:**
- `followUp` = every one of the caller's own returning paused deals (ON_HOLD or DORMANT with `pause_return_on <=` today
  in Bangkok) first, uncapped, then the top 5 quiet ACTIVE deals.
- `BoardDeal` gains `returning` and `pauseReturnOn`.
- `board` is unchanged (ACTIVE only).

**`GET /api/sales/team-overview`:**
- `stale` = all returning deals first, then the top 20 quiet deals.
- `reps[].quietDeals` counts returning deals.
- `openDeals` and `openValue` exclude them.

**Value order** for the board, follow-up and team value, and for the dashboard ยอดคาดการณ์: live quotation →
`imported_value` → `estimated_value`. These are the same two consumers as before (`SalesBoardRepository.map` and
`DashboardRepository.forecastValue`); no new consumer.

**Estimate ceiling:** `@Digits(12,2)` equals the column's NUMERIC(14,2), which is the precision of `imported_value`
(V215). This follows the repo's existing rule that "`@Digits` matches the column's NUMERIC precision exactly"
(`dealquotation/DealQuotationRequests.java:90`). I found no existing deal-value cap stricter than that. 999,999,999,999.99
is generous; the typo risk is handled by the estimate now being editable.

**Frontend:**
- `localStorage` keys `glr:draft-deal`, `glr:deal-drafts:index` and `glr:draft-deal:*` are removed once per browser.
- New key: `glr:deal-drafts:purged`.

**Unchanged:** `hrApi.js` signatures, `docs/api/*.json` (the contract tests pass without regeneration), and all role
gates.

## 3. Authz evidence (real Postgres 16 at :54329/glr_it_wn, through the real controller, service and repository)

**No permission rule changed.** `git diff b9b0f9bc` on `TicketService`:
- `placeOnHold` and `markDormant` still start with `requireTicket` then `requireDealOwnership`, byte-identical. The
  date-change branch comes after them.
- `resume`, `cancel`, `markLost` and `reopen`: additions only.
- `updateTracking`: additions only, after `requireDealOwnership` and `requireActive`.
- `create`: two input validations (start stage, estimate) were added before the owner-eligibility check. The S2 gate
  reuses the unchanged `requireStageWriteAccess`.

`TicketController` changed only the hold/dormant body type. `SalesBoardService` role gates are untouched; the only
removed lines are the old ranking. The frontend's new gates (change-date behind RESUME, S2 option, item section) are
presentation only. Java lists RESUME exactly when `canDealOwnership`, the same predicate `requireDealOwnership` enforces
for hold and dormant.

**Wrong-way-round tests:**
- `PausedDealFollowUpScopeIntegrationTest`: 13 tests, including:
  - rep B's returning deal is never in rep A's list;
  - a deal whose date has not arrived, or that has no date, stays out;
  - resumed, cancelled and lost deals stay out;
  - the manager counts a returning deal for its own rep only;
  - the count agreement holds, and the Bangkok-vs-UTC boundary is tested with a fixed clock.
- `DealLifecycleDialogsIntegrationTest`: 25 tests, including:
  - another rep cannot change the return date (403, date unchanged);
  - another rep with a past date gets 403, not 400;
  - another rep cannot edit the estimate;
  - a grant holder cannot start at S2 (403, nothing left behind).

**Backend mutations** (real Postgres; each one reverted, `git diff` restored). The reviewer's survivors (B2, B6, B7) are
all now killed.

| ID | Mutation | Result |
|---|---|---|
| B1 | `<=` → `<` in returning | KILLED |
| B2 | SQL today in UTC | KILLED |
| B3 | Lifecycle predicate dropped | KILLED (`lostAndCancelled…` only) |
| B5 | Service reads all reps | KILLED (`anotherRepsReturningPausedDeal_neverAppearsInMyFollowUp` only) |
| B5s | Owner predicate dropped from the SQL | KILLED (the same single test only) |
| B8 | Returning not counted | KILLED |
| B6 | Service date in UTC | KILLED |
| B7 | Date before authz in hold | KILLED (`anotherRepWithAPastDate…` [hold]) |
| B7d | Same, in dormant | KILLED ([dormant]) |
| R1 | `@NotBlank` removed | KILLED (3 validation tests and the integration test) |
| R3a | Estimate written to `imported_value` | KILLED |
| R3b | Board ignores the estimate | KILLED |
| R3c | Forecast prefers the estimate | KILLED |
| R3d | Clear ignored | KILLED |
| R3e | Service accepts 0 in tracking | **SURVIVED** (equivalent at HTTP: `@DecimalMin` on the request refuses first; the service check is a backstop for service-level callers) |
| R4 | Returning deals capped with the quiet ones | KILLED |
| R5a | Hold on ON_HOLD returns 409 | KILLED |
| R5b | Second pause event | KILLED |
| R5c | Notification sent | KILLED |
| R5d | Date change before the permission check | KILLED (`anotherRepCannotChangeTheReturnDate` only) |
| R5e | Date not written | KILLED |

**Frontend mutations** (vitest on the touched files; each one reverted). 27 of 28 killed. Killed: R1a, R1b, R2a–c,
R3a–d, R4a–b, R5a–e, R6a–b, R7a–b, F7, F9, F10, F20, F22, R9, and the S2-option gate. R1c (mock accepts an absent
channel) **survived as equivalent**: the mock's next check refuses any non-real channel, so a missing one is still 400.
The full list with failing test names is in
`C:\Users\user\AppData\Local\Temp\claude\44a3f738c4644781\21665dad-d5d9-4895-9692-9b2bd2afbd34\scratchpad\f1-mutations-frontend.txt`
and `...\f1-mutations-backend.txt`.

## 4. Test-first record for what I added

**DealStagePanel tests (ruling 6):**
- The guided advance now asserts `winProbability: 40` (an override left blank is kept, not cleared) and
  `estimatedValue: 750000`.
- The S2 readiness resend asserts `estimatedValue: 1200000`.
- **Red run:** 2 failed, with `estimatedValue` missing from the call. Assertion failures, not errors.
- Then implemented: two lines in `DealStagePanel.jsx`. **Green:** 125/125.

**TicketCreateModal (S2 gate):**
- `it.each` over qc, account (disabled) and sales, sales_manager, ceo (enabled).
- The implementation already existed. Mutation (gate removed) → qc and account cases red. Restored.

**Rulings already implemented by the previous fixer:** I did not see their red runs. I proved each test can fail by
mutation instead (tables above).

## 5. e2e-real (cannot run here: it needs the real stack; NOT run)

I grepped every spec and helper for: create-modal fields and sections, สร้างดีลใหม่/สร้างงานใหม่/งานใหม่, เสนอแก่ and
channel labels, stage/hold/dormant/lost/cancel titles and buttons, reason codes, drafts, ยกเลิกดีล/ยกเลิกงาน,
follow-up/ควรตามต่อ, `estimatedValue`/`imported_value`, `/hold` `/dormant` `/cancel`, and UNSPECIFIED.

**`write-quote-stage-gates.spec.js` case 1** (changed by the previous fixer; reviewed, kept):
- **Old:** click "เลื่อนไป: 2." then "เลื่อนไป: 3.", filling the guided form if shown; assert ขั้น 3.
- **New:** click "ย้ายไป S2 · สเปคผ่าน", fill `move-s2-modal` (date only if shown, then the note), confirm "ย้ายไป S2";
  assert ขั้น 3.
- Same guarantee: the deal reaches ขั้น 3 by hand before case 1's no-shortcut checks.

**`sales-board.spec.js` "โทรแล้ว…"** (changed by me):
- **Old:** fallback `mine.followUp[0]`.
- **New:** fallback `mine.followUp.find((d) => !d.returning)`.
- Reason: followUp now leads with returning paused deals, which offer no โทรแล้ว. The same guarantee is asserted
  against a quiet row.

**Checked and unaffected:**
- `customer-thai-address.spec.js` and case 8 of the stage-gates spec use the quotation editor's inline สร้างดีลใหม่
  (quotations feature, untouched).
- "สร้างดีลใหม่ 1" in `pipeline-import.spec.js` is the import dialog.
- API creates in `helpers/sales.js`, `write-*.spec.js` and the stage-gates `newDeal` all pass `entryChannel`, as required
  before and now.
- The cancel helper uses `OTHER` (still valid).

## 6. Commands and results

**Backend** (JDK 21 portable, `-Dtest.fork.count=1`, `TEST_DB_URL` Postgres 16, targeted classes; the full suite is left
to CI):

Baseline (before my backend edit), 11 classes: 331/332. The only error is
`TicketServiceTest.getQuotationPdf_rendersFromSnapshotNotLiveEditedItems` (LibreOffice not found, environment).

Final run, 19 classes, 421 tests (`f1-final.log`):
- All green except the LibreOffice error above and **one failure in
  `SalesBoardScopeIntegrationTest.followUpIsCappedAtFiveAndOrderedByValueTimesDays`**.
- The failure was two zero-value quiet deals (backdated 18 and 19 days) ranked by ticket id instead of days. That means
  their `daysQuiet` tied.
- It **passed when re-run alone**, and the **whole class then passed 24/24 on a re-run** (`f1-rerun-scope-class.log`).
  So it is intermittent.
- The code it exercises (`lastTouchedSql`, `quietMillis`, `QUIET_RANK`, and the ordering of quiet ACTIVE deals within
  `rankFollowUp`) is identical in behaviour to the slice for deals that are not returning. I believe it is a
  timing-sensitive test, not a regression, but did not prove why the days tied.

Per-class counts:
- PausedDealFollowUpScope 13
- DealLifecycleDialogs 25
- PipelineImport 37
- SalesBoardServiceTest 40
- SalesBoardScope 23/24
- QuietRule 17
- EntryChannel 14
- DealEntryAccess 16
- TeamBoardScope 7
- TicketDepositDue 5
- CreateTicketRequestValidation 8
- TicketControllerCreateValidation 6
- DealCancelReason 4
- DealStageMetaController 11
- TicketSummaryDtoJson 1
- TicketSummaryDtoSerialization 5
- ApiSurfaceContract 1
- ResponseFieldContract 1
- TicketServiceTest 185/186 (LibreOffice)

**Frontend** (`cd frontend`):
- `npm run lint`: exit 0, clean.
- `npm test`: exit 0, 311 files / 6715 tests passed.
- `npm run build`: exit 0.

## 7. Not done, and decisions where the rulings were silent

- **Estimate semantics on PUT /tracking:** kept when absent, plus `clearEstimatedValue`. Every other tracking field is
  full-replace. I chose this because callers outside the tickets feature, and any older client, would otherwise silently
  wipe it. Ruling 6's "resend the estimate" is honoured anyway. **This is an additive change to the tracking contract,
  stated above.** Ruling 6's "the endpoint's contract does not change" cannot hold literally alongside ruling 3's "edit
  through the existing tracking update".
- **Guided advance:** a blank win% now keeps the rep's override. Before, it cleared it: the same erasure class as ruling 6.
- **"ดูทั้งหมด" destination:** the MyWork board's งานเงียบ filter. Returning deals are listed ahead of the quiet ones
  there, and the count includes them. It is not the งานทั้งหมด table.
- **Team overview `stale`:** returning deals are uncapped too, so the list can exceed 20.
- **Pause dialog subtitle:** now "งานที่พักไว้ไม่นับเป็นงานเงียบ จนถึงวันที่จะกลับมาดู". The drawn text contradicted
  ruling 4.
- **`withoutMoney()`:** `pauseReturnOn` is still withheld from the import roles, as in `afe6acbd`. Ask the owner if
  import should see it.
- **R3e:** no service-level unit test for the tracking `<= 0` backstop.
- **Not verified:** e2e-real was not run, and visual or phone behaviour of the new item section and the date-change
  button was not checked in a browser.

## 8. What a reviewer should attack first

1. The intermittent `SalesBoardScopeIntegrationTest.followUpIsCappedAtFiveAndOrderedByValueTimesDays` (1 failure in 3
   runs: the 18-day and 19-day deals tied on `daysQuiet`). Code that is behaviourally unchanged; cause not proven.
2. The keep-when-absent estimate on PUT /tracking versus full-replace for the rest. Also the import-role `withoutMoney`
   withholding of `pauseReturnOn`.
3. The date-change branch sits before `requireActive` in `placeOnHold`/`markDormant`. Check that nothing besides
   ON_HOLD/DORMANT can reach `changePauseReturnOn`: CANCELLED, COMPLETED and CLOSED_LOST each have their own lifecycle
   value, so they cannot.
4. Uncapped returning deals in the team `stale` list.
