# Adversarial re-review, round 2: task 3a lifecycle dialogs (`24a1dce9` on `feat/deal-dialogs`)

Scope: the fix round `3faef768..24a1dce9`, read against the ten rulings (`task-3a-fix1-rulings.md`), with the whole
slice against `b9b0f9bc` / `origin/develop` for context. I worked in an isolated worktree, detached at `24a1dce9`.
I did not push, comment, dispatch agents, or touch any deployed system. Every scratch mutation was reverted, and
`git status --short` is empty at the end.

## Verdict: **ANOTHER ROUND NEEDED** (small)

All ten rulings are met in substance (code). Two of my backend mutations against ruling 5's side guarantees survived (M-2). One blocker comes from OUTSIDE the diff: **V219 is already taken** by a
pushed concurrent branch. A second item needs an owner decision: the uncapped "returning" list has no ceiling.

---

## Rulings

| # | Ruling | Status | Evidence |
|---|---|---|---|
| 1 | Channel required | **IMPLEMENTED** | `CreateTicketRequest.java:26` `@NotBlank String entryChannel`. `git diff b9b0f9bc 24a1dce9` on both validation test files shows additions only (three estimate/designer tests, one estimate 400): the six original channel tests are byte-identical. Create form: chips plus a required error (`TicketCreateModal.jsx:241,400-412`). The UNSPECIFIED row is gone from `DealStagePanel`. Mock refuses absent or blank (`mockApi.js` create). Quotation editor inline create sends `dealForm.entryChannel` (`QuotationEditorPage.jsx:1780`). `startStage` does not touch the channel. Integration test `aDealWithNoChannelIsStillRefused_andNothingIsWritten`. FM11 KILLED. |
| 2 | Items for non-sales creators | **IMPLEMENTED** | `CreateDealItems.jsx` `canEditDealItemsAfterCreate = role === 'sales'`. It mirrors `TicketService.editItems` (`SALES_ROLES = {"sales"}` at `TicketService.java:49`, `&& isOwnerOrKeyer && status ∈ {DRAFT…}`). A creator is always owner or keyer (`DealOwnership.isOwnerOrKeyer` includes `keyed_by`) and a new deal is DRAFT, so the predicates agree for EVERY role: sales gets no section and can edit later; sales_manager, grant holders and everyone else get the section and cannot edit later. I found no disagreeing role. Items are sent through `createItemsPayload`, the old mapping, and persist (`aManagerCreatedDealWithItemsPersistsThem`). FM5 KILLED. |
| 3 | `estimated_value` | **IMPLEMENTED** | Details and the asymmetric-contract analysis are in §3 below. |
| 4 | Returning never capped out | **IMPLEMENTED** | `SalesBoardService.rankFollowUp` (`:210-215`). Counts agree (`aReturningDealIsNeverCrowdedOutByTheCap_andTheRepAndManagerCountsAgree`). ดูทั้งหมด leads to MyWork's งานเงียบ filter, which now prepends the returning rows (`MyWork.jsx:179-183`), NOT the งานทั้งหมด table. Scope classes green 3/3 runs. Mutations in §Mutations. **Open:** no ceiling at all, see M-1. |
| 5 | Change date in place | **IMPLEMENTED** | `TicketService.placeOnHold:3014-3021` and `markDormant` (same shape) branch to `changePauseReturnOn` (`:3907-3914`) AFTER `requireDealOwnership`, before `requireActive`. Only `ON_HOLD`/`DORMANT` reach it; the terminal lifecycles are distinct values. The branch writes `pause_return_on` + `updated_at`. It logs a `POLICY_CHANGED` event, which is NOT in `QuietRule.lastTouchedSql` (STAGE_CHANGED / QUOTATION_ISSUED only). It does not notify, and does not touch `stage_updated_at` or `lifecycle`. The same date twice adds a second POLICY_CHANGED log line and changes nothing else. DORMANT→hold is still 409; ON_HOLD→dormant still notifies. Dialog title, preselection and button are pinned. No client or e2e spec relied on the old 409 (grep of `frontend/src`, `frontend/e2e-real`). |
| 6 | Party names survive guided moves | **IMPLEMENTED** | The only `PUT /tracking` callers are `DealStagePanel.jsx:221` (guided advance), `:262` (S2 readiness) and `DealInfoCard.jsx:64` (edit form, which resends `buyerName` and sends the estimate explicitly). The first two resend designer/owner/buyer/win%/estimate. FM1 KILLED. The win% behaviour change is in §6. |
| 7 | Purge old drafts | **IMPLEMENTED** | `app/purgeLegacyDealDrafts.js`, called once in `main.jsx` before render, inside try/catch. The keys match the removed `dealDrafts.js` exactly (`glr:draft-deal`, `glr:deal-drafts:index`, prefix `glr:draft-deal:`). No other feature uses these prefixes (grep). Marker `glr:deal-drafts:purged`. FM7 and FM8 KILLED. |
| 8 | Surviving-mutation tests | **IMPLEMENTED** | The Bangkok boundary is tested in SQL (`theReturnDayIsTheBangkokDate_atTheHoursWhenUtcIsStillYesterday`, fixed clock bound as `:returnToday`) and in the service (`TicketServiceTest.pauseReturnDate_isJudgedOnTheBangkokDate…`). 403-before-400 for hold and dormant. Dialog past-date guard. S2 note and follow-up guards tested separately. Mock owner scope and mock `isReturning` lifecycle are both tested. BM4 and FM3 KILLED; BM6 is in §Mutations. |
| 9 | Wording | **IMPLEMENTED** | The toast says "สร้างงานเรียบร้อย" in MyWork/AllDeals/TicketList. `DealShortCreateIntegrationTest` is not referenced anywhere (grep). |
| 10 | e2e-real | **IMPLEMENTED** | See §10. |

### 3. `estimated_value` in detail

**V219, read line by line as a production boot migration:**
- `ADD COLUMN IF NOT EXISTS estimated_value NUMERIC(14,2)`: nullable, no default, metadata-only.
- `DROP CONSTRAINT IF EXISTS` / `ADD CONSTRAINT chk_ticket_estimated_value CHECK (estimated_value IS NULL OR estimated_value > 0)`. The scan passes trivially, because every row is NULL.
- `> 0` rejects an explicit 0. No path sends 0:
  - bean `@DecimalMin(exclusive)` on both requests;
  - a service backstop in create and in tracking;
  - frontend `parseEstimatedValue` returns NaN for 0 (FM13 KILLED);
  - `DealStagePanel` resends only a stored value, which is > 0;
  - pipeline import never writes the column.
- `@Digits(12,2)` matches NUMERIC(14,2) exactly. `imported_value` is NUMERIC(14,2) (V215), so precision matches the ruling.
- `chk_ticket_cancel_reason` keeps V57's four codes and adds three. V57 is the only earlier definition (grep of `db/`), so every stored value still passes.
- The `imported_value` comment change was removed and V215's comment stands.
- Idempotent re-run (`IF [NOT] EXISTS`). Rollback stated.

**Consumers** (grep of `backend/src/main`): `imported_value` is read by `SalesBoardRepository.map`, `DashboardRepository.forecastValue` and pipeline import. `estimated_value` is read only by the first two, plus the summary DTO and the insert/update. No commission, payroll, tax, quotation or finance code reads it. Order: quotation → imported → estimated on the board (`SalesBoardRepository.java:131-143`). On the forecast it is `COALESCE(imported, estimated)` unless a value quotation exists (`DashboardRepository.java:257-262`). Real-DB tests pin "both → imported wins" and "estimate alone is the fallback" on both consumers.

**Asymmetric tracking contract** (estimate kept when absent; `clearEstimatedValue:true` clears; every other field full-replace):
- **Consistent across all three implementations:**
  - Java `TicketService.updateTracking:2697-2706`;
  - the mock;
  - every caller. `DealInfoCard` sends a value or `clearEstimatedValue:true` (FM4 KILLED). The two DealStagePanel callers resend the held value. Old clients omit it and keep it (integration test `…LeavesItWhenNotSent`; FM10 and BM9 are in §Mutations).
- **A caller that omits it cannot clear it, and a caller that wants to clear can.** This is safe, and arguably safer than full-replace for a field most callers do not know about.
- **Bounds:**
  - negative, 0, more than 12 integer digits and more than 2 decimals are all refused at the boundary: `@Valid` is on the endpoint (`TicketController.java:313`);
  - absurd but in-range values up to 999,999,999,999.99 are accepted. That ceiling is the column's, and the ruling asked only for "a sane ceiling reused from an existing rule".
- **Low divergence:** with `clearEstimatedValue:true` the mock skips validating `estimatedValue`, while Java's bean validation still 400s on an invalid one. The mock is more permissive. No caller sends both. See L-3.

### 6. The "blank win% keeps the override" change
- **Before:** the guided-advance modal's win% field always opens blank (`guidedDraft` initial `winProbability: ''`, `DealStagePanel.jsx:180`). The field is labelled "(ถ้ามี)". Blank sent `null`, which CLEARS `win_probability`, so every guided advance silently erased a rep's override. That is the same erasure class as ruling 6.
- **Now:** blank resends `summary.winProbabilityOverride`.
- **A rep can still clear the override.** The ข้อมูลงาน edit form (`DealInfoCard`) pre-fills the current override, and emptying it sends `null`.
- **Downstream reach** (grep of `win_probability` in `backend/src/main`): it feeds only the board/summary display and pipeline import comparisons (`SalesBoardRepository`, `TicketRepository`, `PipelineImportRepository/Service`, `DealPartyRepository` copy). No dashboard forecast, commission, payroll or analytics figure reads it.
- **Assessment:** this is a correct fix of a latent erasure bug, with no effect on money figures. It is still an unrequested behaviour change, so state it in the PR body. FM2 KILLED.

### 10. e2e-real (read old vs new against `b9b0f9bc`; not run, because the real stack is not available here)
- **`write-quote-stage-gates.spec.js` case 1:**
  - **Old:** two guided "เลื่อนไป: 2./3." steps.
  - **New:** one "ย้ายไป S2 · สเปคผ่าน" dialog. It fills the date only if shown, always fills the note, then asserts ขั้น 3.
  - **Guarantee kept:** the deal reaches ขั้น 3 by hand before the no-shortcut checks.
  - **Coverage lost:** the guided-advance modal is no longer driven by any e2e spec. That is not a loosening of this case's claim.
  - The second hunk scopes the ยกเลิก click to the modal and asserts the auto hint inside `next-step-card`. Same guarantee, more precise.
- **`sales-board.spec.js`:** the fallback target is now the first NON-returning follow-up row. Same guarantee (a real, quiet, owned deal).
- **The rest of the grep sweep is clean.**
  - Both `customer-thai-address` references and `pipeline-import`'s "สร้างดีลใหม่" are other features.
  - No spec posts hold twice or expects the old 409.
  - The helpers create with `entryChannel`.

---

## Findings

### H-1 (HIGH, merge blocker, proven by `git log --all`). V219 collides with a pushed concurrent branch
- **The collision:** `origin/claude/people-insights` (`254429a0`, 2026-11-17 02:21 +0700) adds `V219__people_insights_teams.sql`. This branch adds `V219__deal_pause_return_on_and_cancel_reasons.sql`. The fixer's header claims "verified free on … every local/remote branch". That was true when written (`afe6acbd`, 2026-11-16 22:40) and is no longer true.
- **The danger:** if both reach one image, Flyway refuses to start with "Found more than one migration with version 219". That is a backend boot failure on deploy, and `validate-on-migrate=false` does not suppress duplicate detection.
- **The second danger:** if the other one is applied on prod first, this file must be renumbered. The per-image ordering rule in CLAUDE.md then applies.
- **Fix:** coordinate with that session (CLAUDE.md requires messaging it). Renumber whichever lands second. Re-check the set of versions on `origin/*` immediately before merge.

### M-1 (MEDIUM, design gap, read code). Returning deals are uncapped with no ceiling and no expiry
- **The rule:** a paused deal "returns" forever once its date passes, until someone resumes it or re-dates it (`QuietRule.returningSql`). Ruling 4 makes these rows uncapped in the rep's ควรตามต่อ and in the manager's `stale` list. The fixer's justification is "they are few".
- **Why that does not hold:** nothing keeps them few. Every dated pause a rep ignores stays in both lists permanently. Unlike quiet ACTIVE deals, which are cut to 5 or 20, the list grows without bound. `FollowUpList` and `TeamOverview` render the whole list (`TeamOverview.jsx:202`, no slice). No email or other consumer relies on ≤ 20 (grep).
- **The ruling itself is met.** This needs an owner call: accept unbounded, cap it with a visible "+N more", or age them out.
- **Also:** the team panel subtitle "เรียงตามมูลค่า × วันที่เงียบ" is no longer the order, because returning rows come first.

### L-1 (LOW, pre-existing, PROVEN). The "intermittent" `followUpIsCappedAtFiveAndOrderedByValueTimesDays` is a real clock-mixing defect, not test noise
- **The ORDER BY is fully deterministic.** `QUIET_RANK` = days×THB value desc, days desc, `ticketId` asc: a total order. `rankFollowUp` sorts each half with it. The SQL `ORDER BY last_touched_at, t.ticket_id` is total too. No result depends on physical row order, and the new "returning first" split does not change this.
- **The cause:**
  - `daysQuiet` is `floor((System.currentTimeMillis() − last_touched_at) / 1 day)` (`SalesBoardRepository.map`);
  - `last_touched_at` comes from Postgres `now()`;
  - on this Windows box the JVM clock is coarser than, and lags, Postgres's precise clock by up to ~16 ms.
- **Constructed:** a scratch test backdated one deal by exactly 19 days and read it at once, 300 times. **131/300 reads gave `daysQuiet = 18`.** The minimum of javaNow − dbClock was −16 ms.
- **In the failing run:** the 19-day deal therefore tied the 18-day deal, and the id tie-break put 18 first. That is exactly the logged `[2,1,6,7,5]`.
- **Scope:** the code and the test are both on `origin/develop` unchanged, so this slice did not introduce it. The class passed 3/3 for me.
- **Production effect:** "เงียบ N วัน" and the rank can be off by one for a deal within the app↔DB clock skew of a whole-day boundary. That is cosmetic, but it also applies to the rep list.
- **Fix (separate PR):** compute `daysQuiet` in SQL from the same `now()`, or make the test backdate by N days plus 1 hour.

### L-2 (LOW, read code). Javadoc for `withoutMoney()` is now false; withholding `pauseReturnOn` from import is legacy, not principle
- **The contradiction:** `TicketSummaryDto.withoutMoney()` (`:488-508`) says "customer money totals nulled — every other field unchanged", yet it now nulls `pauseReturnOn` and `estimatedValue`.
- **`estimatedValue`:** withholding is right (a selling figure).
- **`pauseReturnOn`:** not money. The import role sees `lifecycle = ON_HOLD/DORMANT` and receives the IMPORT_PAUSE notice, but not when the deal comes back. The fixer restored the reviewed slice's state, which is safe (it widens nothing). It is merely the earlier state: no rule says import must not see it, and it would help import plan.
- **Fix:** correct the Javadoc either way, and ask the owner whether import should see the date.

### L-3 (LOW, read code). Mock more permissive on `{clearEstimatedValue:true, estimatedValue:<invalid>}`
- **Java:** the request is still 400 (`@DecimalMin` / `@Digits` on the record).
- **Mock:** it skips validation when the clear flag is set (`mockApi.js` updateTracking).
- No caller sends both. Fix with a one-line reorder.

### L-4 (LOW, read code). Guided moves resend a possibly-stale estimate although absence would keep it
- `DealStagePanel` sends `estimatedValue: summary.estimatedValue`. Because the server keeps an absent value, resending can only REVERT a concurrent edit made since the page loaded. Omitting it never can.
- This is the same lost-update exposure the names already have, and is negligible with one rep per deal. The ruling asked for the resend, so leave it, but it is not a safety gain.

### L-5 (LOW, UX, read code / existing tests)
- **Wrong kind under a change-date title.** On an ON_HOLD deal, the "เปลี่ยนวันที่จะกลับมาดู" dialog still offers ไม่เคลื่อนไหว (if MARK_DORMANT is served). Picking it performs a real state switch WITH a notification, under a "บันทึกวันที่" button.
- **A refused date pauses with no date.** In the pause dialog, a typed past date is refused by the field ("วันที่อยู่นอกช่วงที่เลือกได้"), but พักงาน still submits with no date (pinned by `dealDialogs.test.jsx`). The rep sees the error, but may still pause without the date they meant.
- **No focus on a missing channel.** The create form's channel error is not focused: `entryChannel` is missing from the focus id map (`TicketCreateModal.jsx:250-252`).

### Not verified
- **Layout at 320–375px:** not checked in a browser. The `frontend-mock` launch config's port may belong to another session's server. By code reading:
  - the chips are `flex-wrap`, `min-h-11`;
  - the item rows are `grid-cols-1 sm:grid-cols-2`;
  - the change-date button sits in a `flex-wrap` span.
  Nothing obviously overflows.
- **e2e-real:** not run.
- **Authorization:** only the targeted real-DB classes below ran.

## Authorization byte-identity
- **`TicketService`, `TicketController` and `salesboard/*`:** `git diff b9b0f9bc 24a1dce9 … | grep '^-'` shows no removed authorization line.
  - `placeOnHold`/`markDormant` still start with `requireTicket` → `requireDealOwnership`, and the new branch follows.
  - `updateTracking` keeps `requireDealOwnership` → `requireActive` first.
  - The `SalesBoardService` role gates are untouched (the removed lines are the old ranking only).
  - The controller changed only the hold/dormant body type.
- **Create:** the start-stage and estimate input checks run after `DealEntryAccess.requireCanEnterDeal` (`create:365` → `createDeal`).
- **Mock vs Java for this round's endpoints:**
  - hold/dormant same-state: same order (permission, then date);
  - tracking: permission, active, then validation (except L-3);
  - create: channel/estimate/startStage. The S2 gate in the mock is labelled APPROXIMATION and has its real-DB test.

## Tests changed in the round
- **Removed lines** are all inverted tests restored to the original (channel), the deleted UNSPECIFIED-row test, and the `imported_value` assertions now pointing at `estimated_value`.
- **Weakened tests:** none found.
- **Rewritten tests:** the `ticketItemFields` shared-usage pin now pins `CreateDealItems.jsx`, which restores rather than weakens it.

## Runs
- **`SalesBoardScopeIntegrationTest` + `PausedDealFollowUpScopeIntegrationTest`:** three runs, 37/37 each (24 + 13), real Postgres 16. One run also carried the scratch test.
- **Frontend:** targeted vitest gave 58 files, 1810 tests, all green. eslint on the touched files is clean.

## Mutations (all reverted)

Frontend (vitest):

| ID | Mutation | Result |
|---|---|---|
| FM1 | guided advance drops designerName resend | KILLED |
| FM2 | blank win% clears override again | KILLED |
| FM3 | pause past-date guard removed | KILLED |
| FM4 | info card: emptied estimate not cleared | KILLED |
| FM5 | items helper excludes sales_manager | KILLED |
| FM6 | งานเงียบ view omits returning deals | KILLED |
| FM7 | purge ignores once-per-browser marker | KILLED |
| FM8 | purge removes every `glr:` key | KILLED |
| FM9 | mock caps returning with quiet | KILLED |
| FM10 | mock tracking: absent estimate clears | KILLED |
| FM11 | create form channel not required | KILLED |
| FM12 | change-date not behind RESUME | KILLED |
| FM13 | estimate 0 accepted | KILLED |

Backend (real Postgres, targeted classes):

| ID | Mutation | Classes | Result |
|---|---|---|---|
| BM1 | owner predicate dropped from returning SQL | PausedScope | KILLED (`anotherRepsReturningPausedDeal_neverAppearsInMyFollowUp` only) |
| BM2 | lifecycle condition dropped from `returningSql` | PausedScope | KILLED (`lostAndCancelledDealsNeverReturn…` only) |
| BM3 | `<=` → `<` | PausedScope | KILLED (5 tests) |
| BM4 | `bangkokToday` in UTC | PausedScope | KILLED (incl. the fixed-clock boundary test; the others also failed because I ran it at 03:xx Bangkok) |
| BM5 | resume does not clear the date | PausedScope, then Dialogs+TicketServiceTest | survived PausedScope (equivalent there: lifecycle gates it); KILLED by `DealLifecycleDialogsIntegrationTest.resume_clearsTheReturnDate` |
| BM6 | date-change branch before `requireDealOwnership` (hold) | Dialogs | KILLED (`anotherRepCannotChangeTheReturnDate`) |
| BM7 | date change logged as `STAGE_CHANGED` (feeds `lastTouchedSql` and the movement/stage reports) | Dialogs+PausedScope | **SURVIVED** |
| BM8 | date change accepts a PAST date (drop `requireReturnDateNotPast` in `changePauseReturnOn`) | Dialogs | **SURVIVED** |
| BM9 | tracking: an absent estimate clears it | Dialogs | KILLED |
| BM10 | returning deals capped together with quiet ones | PausedScope | KILLED |

### M-2 (MEDIUM, test gap, PROVEN by BM7/BM8). The date-in-place guarantees beyond "no notification / no pause event" are unpinned
- **BM7.** Logging the date change as a `STAGE_CHANGED` event instead of `POLICY_CHANGED` passes every test. That one-word slip would:
  - reset `QuietRule.lastTouchedSql`, so the deal looks freshly touched in the quiet ranking and after resume;
  - count as a stage movement in range-filtered reports.
  The code is right today (`TicketService.java:3911`, POLICY_CHANGED), but nothing pins "no change to anything the quiet rule or reports read". `stage_updated_at` is also not asserted unchanged.
- **BM8.** A same-state hold/dormant with a past date is accepted once `requireReturnDateNotPast` is removed from `changePauseReturnOn`. No test sends a past date on an ALREADY-paused deal by the owner. The past-date tests run on ACTIVE deals, or as another rep (403 first).
- **Fix:** two short real-DB cases in `DealLifecycleDialogsIntegrationTest`:
  - after a date change, the `STAGE_CHANGED` event count, `stage_updated_at` and the board's `lastTouchedAt` are unchanged;
  - the owner re-dating to yesterday gets 400 and the date is unchanged.

## Is V219 safe to apply on a live production database as written?
**The SQL itself: yes.**
- It is additive and nullable.
- The two CHECKs validate trivially (a new all-NULL column; a strict superset of the only previous cancel-reason list).
- It is idempotent and needs only brief locks on a small table.

**As numbered: no.** `V219` duplicates `origin/claude/people-insights`'s `V219__people_insights_teams.sql` (H-1). Renumber or sequence it before any image carries both.

## Verdict: **ANOTHER ROUND NEEDED**
- **Required:** H-1 (renumber or coordinate V219) and M-2 (two real-DB tests).
- **Owner decision:** M-1 (no ceiling on returning deals).
- **Also fix:** L-2 Javadoc, L-3 mock.
- Everything else in the round is implemented and mutation-proven.
