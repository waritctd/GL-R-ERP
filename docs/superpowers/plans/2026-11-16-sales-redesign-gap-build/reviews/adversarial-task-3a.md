# Adversarial review: task 3a, deal lifecycle dialogs (`afe6acbd` on `feat/deal-dialogs`, base `b9b0f9bc`)

## Verdict: **FIX BEFORE MERGE**

I found nothing that rises to a BLOCK:
- No commission, payroll or tax code reads the entry channel or `imported_value`.
- No permission rule changed.
- The migration is safe on production.
- Every scoping and date mutation I ran against the new SQL was killed.

Three defects must still be fixed (or explicitly ruled on by the owner) before merge:
1. Sales managers and quotation-grant holders silently lose the ability to record items.
2. A returning paused deal can be pushed out of ควรตามต่อ and is then reachable from no list.
3. The rep-typed estimate goes into a column that feeds the CEO forecast, now writable by every deal-entry role and editable by nobody.

The reversal of the 2026-09-30 channel rule needs explicit owner sign-off.

Everything I ran was in worktree `agent-ae38bfdf4a6d2c957`, detached at `afe6acbd`. Postgres 16 was at :54329/glr_it_wn. Every mutation was reverted, and the final `git status --short` is empty.

---

## Findings

### F-1 (HIGH, proven by code reading and existing pinned tests). Sales managers and quotation-grant holders can no longer record items on deals they create
- **Before:** `POST /api/tickets` inserted `request.items()` for every deal-entry role (`TicketRepository.java:431,470`). Deal-entry roles are sales, sales_manager and `can_create_quotation` grant holders (`auth/DealEntryAccess.java:35-40`).
- **Now:** the short form always sends `items: []` (`TicketCreateModal.jsx` submit). The only other place to add items is `editItems`. It allows only `SALES_ROLES = {"sales"}` as owner or keyer (`TicketService.java:3311`, with `SALES_ROLES` at :49). This is already pinned by `TicketServiceTest#editItems_rejectsSalesManagerRole` (:2529), and the mock mirrors it (`mockApi.js:9610`).
- **Concrete case:** an admin grant holder (qc/other with `can_create_quotation`) keys a deal for themselves, or a sales_manager keys one. Before, they entered the item list in the create form. Now nobody can add items to a grant-holder-owned deal; only a quotation's lines would ever fill them.
- **Channel:** a grant holder also cannot state the channel later. `setEntryChannel` → `requireDealOwnership` allows sales owner/keyer, sales_manager or ceo only (`TicketService.java:3205-3210`, 3080-3087). Their deals stay UNSPECIFIED until someone else fixes them.
- **Brief and report:** this breaks the brief's requirement that everything the old form collected "must remain enterable afterwards". The implementer's report claims items move to the deal page's รายการสินค้า, which is false for these roles. The mock agrees with Java, so the mock-mode phone check could not show it.
- **Fix options:** keep an optional item section in the collapsed details, or get an owner ruling that those roles no longer enter items.

### F-2 (MEDIUM, proven by code reading). A returning paused deal can be pushed out of ควรตามต่อ and is then on no list
- **Mechanism:** `SalesBoardService.rankFollowUp` concatenates quiet ACTIVE deals and returning deals, sorts by `QUIET_RANK` (days quiet × THB value, descending), then applies `.limit(5)` (`SalesBoardService.java` rankFollowUp; `FOLLOW_UP_LIMIT=5`).
- **Who loses:** a returning deal with no value or quotation scores 0, so it ranks behind every quiet deal that has a value.
- **No escape route:**
  - The board never carries paused deals (`ACTIVE_WHERE`).
  - "ดูทั้งหมด (n)" opens the board filtered to quiet deals (`MyWork.jsx:181`), so it cannot show returning deals.
  - The established rule, pinned by `MyWork.test.jsx:259`, is "ควรตามต่อ shows at most five; the rest are reachable from the board". It holds for quiet deals and is broken for returning ones.
- **Ruling not met:** the owner ruling says the deal "appears in the rep's ควรตามต่อ list". It does not appear once a rep has five or more valued quiet deals.
- **Counts disagree:** MyWork's `quietTotal` (`MyWork.jsx:175-176`) counts returning deals only from the capped `followUp`. With 7 quiet deals plus 2 returning ones crowded out, the rep sees 7 while the manager's `reps[].quietDeals` (uncapped) shows 9.

### F-3 (MEDIUM, proven by code reading). Estimate stored in `imported_value`: a second writer, editable by no one, feeding the CEO forecast
- **Before:** only the pipeline import wrote the column, and the import is gated to sales_manager/ceo (`PipelineImportService.java:99`). It writes through `COALESCE` into NULL only (`PipelineImportRepository.java:131`).
- **Now:** every deal-entry role (sales, sales_manager, grant holders) writes it at create time. The cap is `@Digits(12,2)`, so up to 999,999,999,999.99 THB.
- **No path to change it afterwards:** I found no UPDATE of `imported_value` in main code except the import's `COALESCE`. A typo (12,000,000 for 1,200,000) stays until a customer-facing quotation exists or someone edits the database.
- **Downstream effects:** see the consumer table under attack B. The estimate inflates:
  - the CEO/cash-role dashboard ยอดคาดการณ์ (`DashboardRepository.forecastValue`, :248-262);
  - the team overview's `openValue`;
  - the quiet ranking.
- **The import cannot correct it:** a later import of a value for the same deal is reported as "คงค่าเดิมในระบบ" (kept) and never overwrites (`PipelineImportService.compareMoney`).
- **Not displayed as an estimate:** the board does not mark the value as an estimate rather than a quoted value. That was already true for imported values.
- **Not money:** no quotation, commission or payment path reads `imported_value` (grepped commission/, dealquotation/, finance/).
- **Assessment:** this is a second meaning given to a column (the migration only updates its comment). It is also a widening of who may write a figure on the CEO's forecast, and it is not stated as a permission change.
- **Fix:** an edit/clear path (owner or manager), plus an owner decision on reuse versus a new column.

### F-4 (MEDIUM, product rule). The 2026-09-30 "a new deal must state a real channel" owner ruling is reversed
- **Origin:** commit `e1f81ab2` ("Owner ruling 2026-09-30 … because since #1088 the channel decides the deal's route. A deal created without one gets no route.").
- **Who authorised the reversal:** the brief allows relaxing required fields to fit the drawing. The drawing (no channel field) is owner-approved, but no ruling names the reversal. Owner memory says to ask on ambiguous rules; get explicit sign-off.
- **Inconsistency:** the quotation editor's inline deal creation still requires a channel (`QuotationEditorPage.jsx:1226,2393`; `quotationMeta.js:2163`). The two create paths now disagree.
- **Consequences:** see table A. Nothing crashes, nothing touches money or commission, and the stage path is a full 15-stage route with route checks off.

### F-5 (MEDIUM, ruling fidelity, proven by code reading). "Re-pause with a new date" is impossible from DORMANT and forces ON_HOLD → DORMANT
- `placeOnHold` requires ACTIVE (`requireActive`). `markDormant` accepts only ACTIVE or ON_HOLD (`TicketService.java:3031-3033`).
- **ON_HOLD deal:** to snooze it, the rep must convert it to DORMANT. That changes its lifecycle and fires a second `DEAL_DORMANT` notification to import/account.
- **DORMANT deal:** it cannot be re-paused at all. It leaves the list only by being resumed, which puts it back on the board as ACTIVE.
- **From the list itself:** the returning row offers only ดำเนินการต่อ; there is no snooze and no โทรแล้ว (`FollowUpList.jsx:76-81`).
- The ruling promised "until the rep resumes it or re-pauses it with a new date". Needs an owner ruling or a re-pause path.

### F-6 (LOW, pre-existing; this slice makes it neither more nor less exposed). Guided advance wipes the party names
- `DealStagePanel.jsx:217-220` (identical at base `:189-192`) PUTs `/tracking` with only `nextFollowUpAt` and `winProbability`. `updateTracking` replaces every column (`TicketRepository.java:2812-2830`, `TrackingUpdateRequest` javadoc), so `designer_name`, `owner_name` and `buyer_name` become NULL.
- **Reachability under this slice:**
  - On S1 deals the primary button is now ย้ายไป S2. The guided opener is hidden (`advanceInHeader`), and the new dialog resends the fields; the F14 mutation was killed.
  - ⋯ เลื่อนไป uses `openAdvance`, which moves directly with no tracking PUT.
  - Net effect: the S1→S2 path is safer than at base.
- **Still reachable:** the guided flow at later readiness-blocked hand moves (e.g. QUOTE_BUYER → NEGOTIATION). That would also erase the `designerName` captured at create, and the quotation fill does not refill it (fill-if-blank happened earlier).
- **Fix:** resend the three fields, one line, same as `moveToS2`.

### F-7 (LOW). Test gaps that survived mutation (full list below)
- The S2 dialog's two required-field guards are each untested alone (F9, F10). Mutated, the dialog lets a rep submit; the tracking PUT and activity are written and then the move 400s, a partial write.
- The UI past-date guard is only `min` and untested (F7). The server 400s anyway.
- Bangkok-vs-UTC in both the SQL and the service survives (B2, B6), as the implementer admitted. The tests cannot exercise 00:00–07:00 Bangkok.
- Authz-before-date order in `placeOnHold` is untested (B7). Moving the date check first would tell an unauthorised rep "date in the past" (400) instead of 403. That is information-free, so low.
- Mock-only:
  - mock `myWork` returning deals not scoped to the owner (F22, a mock-more-permissive gap no test sees);
  - mock `isReturning` lifecycle check (F20); in the mock, cancel never clears `pauseReturnOn`.

### F-8 (LOW). Smaller observations
- **Create at S2 skips the readiness gate.** A crafted request can create at S2 with no follow-up date. This is stated; the UI defaults the date to +14.
- **Orphan projects.**
  - **Cause:** a failed create (e.g. a grant holder picks S2 and gets 403) leaves the newly created project behind.
  - **UI gap:** the S2 radio is offered to roles that cannot use it.
- **Saved drafts are orphaned.** Drafts in `localStorage` (`glr:draft-deal*`, holding customer/project names and item lists) are no longer readable. Nothing else reads them, and there is no notice, so reps lose any in-progress draft. They are business data rather than credentials, so I see no purge obligation.
- **Mock divergences** (Java is stricter):
  - the mock has no `@Digits(12,2)` / `@Size(200)` checks;
  - the mock omits the "สร้างดีล" activity (stated).
- **Wording:**
  - `MyWork.jsx:119` toast "สร้างดีลเรียบร้อย" after the new dialog;
  - `EntryChannelFix` default noun ดีล, overridden to งาน in the panel.
  - "งาน" is never used in an identifier: `DUPLICATE_DEAL` etc. are enum constants, and the stored event text "ยกเลิกดีล (CODE)" is unchanged.
- **Stale test pointer:** the comment in `CreateTicketRequestValidationTest` names `DealShortCreateIntegrationTest`, which does not exist (it is `DealLifecycleDialogsIntegrationTest`).
- **m-hold subtitle:** "งานที่พักไว้ไม่นับเป็นงานเงียบ" (from the drawing) now contradicts returning deals counting in the quiet figures.

---

## A. Entry channel: what an UNSPECIFIED deal does at each consumer

UNSPECIFIED was already legal as stored: it is the V144 column default and was creatable before `e1f81ab2`. Production may hold such rows. Pre-V144 rows read DESIGNER_LED.

| Consumer | Behaviour with UNSPECIFIED | Where |
|---|---|---|
| Stage route | Empty off-route set, so all 15 stages are on route; route refusals never fire | `DealRoute.java` `offRouteStages` / `isOnRoute`; `TicketService.java:2430,2475,2987` |
| Quotation recipient gate | Refuses nothing | `TicketService.requireQuotationRecipientOnRoute` :2906; `DealQuotationService.java:235,858` |
| Unaddressed quotation auto-advance | QUOTE_BUYER (on route) | `TicketService.stageForUnspecifiedRecipient` :2826 |
| Pipeline import stage choice | All stages on route | `PipelineStages.java:52-90` |
| Pricing-request default recipient | `recipientForEntryChannel` returns '' → DESIGNER | `PricingRequestCreateModal.jsx:424` |
| Quotation editor default recipient | Falls through to the stage-based recipient | `QuotationEditorPage.jsx:680-686` |
| `dealStageLabel`, route line | Generic labels; route line replaced by "ยังไม่ได้ระบุช่องทางงาน · แก้ช่องทางงาน" when SET_ENTRY_CHANNEL is advertised | `DealStagePanel.jsx` |
| Sales overview off-route parties | None | `SalesOverview.jsx:158` |
| Commission / commission weights | **No reference at all.** The commission package has no channel/designer reference; "commission weight" is the per-item stock weighting (V148), unrelated | grep of `commission/` |
| Sales analytics snapshot | No channel reference | grep of `salesanalytics/` |
| `setEntryChannel` later | Owner (sales) / keyer, sales_manager or ceo; deal must be ACTIVE; no note needed from UNSPECIFIED; allowed at any stage, including after quotations | `TicketService.java:3080-3110` |

## B. Estimate in `imported_value`: consumer table

| Consumer | Effect of a rep-typed estimate | Where |
|---|---|---|
| Board / follow-up / team `BoardDeal.value` | Shown as the deal value (THB) until a customer-facing quotation exists | `SalesBoardRepository.java:126-133` |
| Team overview `openValue`, quiet ranking | Summed and ranked | `SalesBoardService` `repRows` / `QUIET_RANK` |
| Dashboard ยอดคาดการณ์ (cash roles) | Counted for ACTIVE pre-ORDER_RECEIVED deals with no value quotation | `DashboardRepository.java:248-262`; `DashboardService.java:79` |
| Pipeline import `fillBlanks` | Never overwrites the rep's value; a differing file value is "kept" with a warning | `PipelineImportRepository.java:131`; `PipelineImportService.compareMoney` |
| Pipeline import matching | Rep-created deals have no `imported_quotation_no`, so the import does not match them (it would create a duplicate; this was already so) | V215 |
| Quotation, commission, finance | Not read | grep |
| Edit path | **None** | grep |

## C. Migration V219: read as if run at boot on production
- **Version is free.** I scanned all 478 refs (local + remote, after `git fetch`) and every worktree directory. Only `feat/deal-dialogs` and the implementer's worktree carry V219. `origin/develop` and `origin/main` max out at V218. There is no out-of-order skip risk, provided the image also carries any lower pending versions (for example the memory's V192.n hotfixes, if any are pending).
- **`ADD COLUMN IF NOT EXISTS … DATE`** is nullable with no default: metadata only, no rewrite.
- **CHECK constraint.** DROP+ADD of `chk_ticket_cancel_reason` takes ACCESS EXCLUSIVE and does a validating scan of `sales.ticket`. The table is small and the migration is one transaction, so the lock is brief. The new set is a superset of V57's four values plus NULL. The only other writers ever (V205/V206) wrote 'OTHER', and the CHECK has existed since V57, so no stored row can violate it.
- **Comments** are harmless.
- **Scope:** nothing outside the `sales` schema is touched.
- **Verified:** it ran green inside the integration tests.
- **Requires an owner-approved image deploy.**

## D. Scoped reads
- **No OR-branch exists.** The returning rows come from a separate query: `ROW_SELECT + " WHERE (returningSql) AND COALESCE(stage) <> 'CLOSED_PAID'"`, plus `" AND t.created_by = :ownerId"` for the per-rep read. Every connective is AND, so operator precedence cannot leak rows across owners.
- **No double counting.** A deal is ACTIVE or paused, never both, so it cannot appear both as quiet and as returning; `repRows` adds returning deals only to the quiet count.
- **Board unchanged:** `ACTIVE_WHERE` is unchanged.
- **Inactive reps** are treated identically to `activeDealsAllReps`.
- **No stale dates on ACTIVE deals.** Resume, cancel and lost were each checked:
  - resume clears the date;
  - lost requires ACTIVE, so a paused deal cannot be lost;
  - cancelled deals are excluded by the lifecycle check;
  - reopen only follows lost.
- Ran `PausedDealFollowUpScopeIntegrationTest` (11) and `DealLifecycleDialogsIntegrationTest` (14), plus `SalesBoardServiceTest`, `DealCancelReasonTest` and `CreateTicketRequestValidationTest`: **75/75 green**.

## E. Create
- **Validations dropped:** only `@NotBlank entryChannel`.
- **Validations added:** `designerName` `@Size(200)`; `estimatedValue` `@DecimalMin(0)` / `@Digits(12,2)` (non-numeric gives Jackson 400); `startStage` ∈ {LEAD_APPROACH, SPEC_APPROVED} (B10 killed).
- **Unchanged:** title `@NotBlank`, `projectId` `@NotNull`, priority, explicit UNSPECIFIED → 400, owner eligibility.
- **S2 start runs:**
  - `requireStageWriteAccess` (mutation killed by the implementer);
  - SPEC_APPROVED has no fact gate;
  - S1→S2 skips no MANDATORY stage, so no justification is needed;
  - only the readiness gate is skipped (stated).
- **Rollback in production is real.** `create` is public `@Transactional` and is called from `TicketController` through the bean proxy. `createDeal` is a private callee in the same transaction. `ApiException` (runtime) rolls back. No half-created deal can survive, except the separately created project (F-8).
- **Old-form fields:** see F-1. Customer details are still in the add-new sub-form. The old form also captured only the project name. Title, priority, owner, follow-up and note are in the collapsed details.

## F. Pause / resume / cancel / lost
- **`pauseReturnOn`:** past dates by Bangkok date → 400; today is allowed. It is checked after the authz checks.
- **Who may set it:** exactly the hold/dormant callers.
- **Clearing:** resume clears it, and resume is the only path back to ACTIVE from a pause.
- **Service methods byte-identical:** `cancel`, `markLost`, `reopen` and `resume`'s checks (diff shows only additions).
- **Reason values:**
  - lost reasons and their stored values are unchanged;
  - retired cancel codes are still accepted by the API and labelled in Java (`DealCancelReason.label`) and the frontend (`CANCEL_REASON_LABEL`);
  - the only frontend display is `DealHistoryPanel`;
  - no report or export reads `cancel_reason`.

## G. Lost capability
See F-1 and F-8.
- **`ticketItemFields.test.jsx` pin:** it guarded against re-inlining the item-editor primitives. With no item editor in the create modal it is vacuous, so dropping it is correct.

## J. Adapted tests

| Test | Old assertion | New assertion | Change |
|---|---|---|---|
| `CreateTicketRequestValidationTest` (×3) | absent / empty / blank channel is a violation | no violation | Inverted. Follows the brief's relaxation; not an independent owner ruling (F-4) |
| `TicketControllerCreateValidationTest` (×3) | 400 and the service is never called | 200 and the service is called | Same as above |
| `mockApi.ticketsCreate` (×3) | no channel → 400 | no channel → UNSPECIFIED | Same as above |
| Page / MyWork / DealStagePanel tests | old dialog titles | new dialog titles | Renames only; gates unchanged |
| `TicketCreateModal.test` | 1,800 lines pinning drafts, items, required channel | replaced | Capability intentionally removed |

None of these weakened an assertion beyond the stated rule change.

## Mutations

### Backend: real Postgres, targeted classes

| ID | Mutation | Result |
|---|---|---|
| B1 | `<=` → `<` | KILLED |
| B2 | SQL date computed in UTC | **SURVIVED** |
| B3 | Lifecycle predicate dropped | KILLED |
| B4 | Resume does not clear the date | KILLED |
| B5 | my-work reads every rep's returning deals | KILLED |
| B6 | Service past-date check in UTC | **SURVIVED** |
| B7 | Date check before authz in hold | **SURVIVED** |
| B8 | Returning deals not counted in `quietDeals` | KILLED |
| B9 | Dormant keeps the old date | KILLED |
| B10 | Any start stage accepted | KILLED |

### Frontend: vitest on the touched files

| ID | Mutation | Result |
|---|---|---|
| F1 | Reason not required | KILLED |
| F2 | F-code PRICE F2 → F3 | KILLED |
| F3 | Cancel label wording | KILLED |
| F4 | Retired cancel label dropped | KILLED |
| F5 | Catalog offers a retired code | KILLED |
| F6 | Chip 2 months = 1 month | KILLED |
| F7 | Pause date `min` removed | **SURVIVED** |
| F8 | Pause always ON_HOLD | KILLED |
| F9 | S2 note not required | **SURVIVED** |
| F10 | S2 follow-up not required | **SURVIVED** |
| F11 | `canMoveDealToS2` ignores the block reason | KILLED |
| F12 | `canMoveDealToS2` ignores off-route | SURVIVED (equivalent: SPEC_APPROVED is never off-route) |
| F13 | `canMoveDealToS2` ignores lifecycle | SURVIVED (largely redundant: the server blocks paused deals with a different reason) |
| F14 | S2 tracking resend drops `designerName` | KILLED |
| F15 | Customer not required | KILLED |
| F16 | Negative estimate accepted | KILLED |
| F17 | Create sends a channel | KILLED |
| F18 | Create drops `startStage` | KILLED |
| F19 | `isReturning` `<` | KILLED |
| F20 | Mock `isReturning` ignores lifecycle | **SURVIVED** |
| F21 | Mock resume keeps the date | KILLED |
| F22 | Mock my-work not owner-scoped | **SURVIVED** |
| F23 | Returning row offers ปิดงานนี้ | KILLED |
| F24 | Page primary ignores the S2 dialog | KILLED |
| F25 | Mock past-date guard removed | KILLED |

## Proven vs suspected
- **Proven:**
  - by reading the code, with existing pinned tests: F-1 to F-6;
  - by mutation runs: F-7;
  - by targeted runs: the 75/75 baseline.
- **Not verified:**
  - production data contents (no production access);
  - e2e-real;
  - the full frontend and backend suites;
  - visual and phone behaviour.
