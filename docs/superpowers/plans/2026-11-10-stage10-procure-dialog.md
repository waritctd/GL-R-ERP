# Stage 10 "จัดซื้อสินค้า" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace stage 10's "ออกใบขอซื้อ (IR)" main button with "จัดซื้อสินค้า", which opens a dialog that confirms each line's source (import or stock): locked to the quotation on new deals, chosen once on old deals.

**Architecture:** A new ticket-level fact, `procurement_source_decided_at`, records that the split is decided. A new endpoint pair, `GET`/`POST /api/tickets/{id}/procurement-source`, reads and decides it. On old deals the POST writes `qty_from_stock` through the same internals as `reserveStock`. The rep's per-line `reserveStock` is withdrawn (CEO only), and IR creation on an old, undecided deal is refused. The frontend adds one dialog and swaps the main-button action on the deal page.

**Tech Stack:** Spring Boot (Java 21, NamedParameterJdbcTemplate, Flyway), React + Vite + TanStack Query, vitest, JUnit integration tests on local Postgres.

**Spec:** `docs/superpowers/specs/2026-11-10-stage10-procure-dialog-design.md`

## Global Constraints

- Work in a worktree branched from `origin/develop` (branch `claude/stage10-procure-dialog`). Rebase onto the latest `develop` before the PR and before merge, then re-run the targeted tests.
- Test first: each task's tests are written, shown to Ploy with what they cover, and run red **for the right reason** before production code.
- Sonnet implements, Opus reviews every task (the reviewer reverts the fix to confirm the tests go red).
- **No commit, push or merge without Ploy's explicit say-so.** No `Co-Authored-By` or Claude footer anywhere.
- Targeted tests only. Do not run the full `verify` locally; CI gates the full build.
- Public repo: no real customer names in fixtures, tests or docs.
- Copy (exact Thai, from the spec): button `จัดซื้อสินค้า`; quick buttons `สั่งนำเข้า` / `จากสต็อก` / `แยกรายการ`; line values `นำเข้า` / `สต็อก`; confirm `ยืนยัน`; new-deal hint `ตามใบเสนอราคา {number}`; header chip `ยังไม่ได้จัดซื้อ`.
- "Old" deal rule: the order was confirmed from a `DEAL_DIRECT` quotation whose `issued_at`, as a Bangkok date, is before `2026-11-07`. Everything else is "new".
- Stock means the whole line: `qty_from_stock = qty`; import means `qty_from_stock = 0`.

## Review Focus

1. **Double confirm** (two tabs, or a double click): the second POST must get 409, not silently re-write the split. Pinned in Task 1 (`secondDecisionIsRefused`) with a conditional `UPDATE … WHERE procurement_source_decided_at IS NULL`.
2. **Old deal where the CEO already marked a line as stock** before the rep opens the dialog: the dialog must start from the current coverage, not all-import. Pinned in Task 1 (`getStartsFromCurrentCoverage`).
3. **Old deal, every line stock:** must leave the import axis (FROM_STOCK, stage DELIVERY_SCHEDULING) exactly as `reserveStock` does, and no IR CTA follows. Pinned in Task 1 (`oldDealAllStockRoutesToDelivery`).
4. **Deal with no lines:** the dialog must not offer a ยืนยัน that writes nothing. Pinned in Task 4 (`disables confirm when the deal has no lines`) and Task 1 (`emptyLinesRejected`, 400).
5. **Deposit not yet paid:** the CTA must stay hidden exactly as the IR CTA is today. Pinned in Task 5 (`no procure action before the deposit is settled`).

---

### Task 1: Backend — decide the procurement source (migration, repository, service, endpoints)

**Files:**
- Create: `backend/src/main/resources/db/migration/V207__ticket_procurement_source_decided.sql`
- Create: `backend/src/main/java/th/co/glr/hr/ticket/ProcurementSourceDto.java`
- Create: `backend/src/main/java/th/co/glr/hr/ticket/ProcurementSourceRequest.java`
- Modify: `backend/src/main/java/th/co/glr/hr/ticket/TicketRepository.java` (new methods next to `coverSourcedFromStockLines`, ~line 1142)
- Modify: `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java` (new public methods next to `reserveStock`, ~line 1467)
- Modify: `backend/src/main/java/th/co/glr/hr/ticket/TicketController.java` (next to `reserve-stock`, ~line 98)
- Modify: `backend/src/main/java/th/co/glr/hr/ticket/TicketEventKind.java` (add `PROCUREMENT_SOURCE_DECIDED`)
- Modify: `docs/api/api-surface.json` (regenerated)
- Test: `backend/src/test/java/th/co/glr/hr/ticket/ProcurementSourceDecisionIntegrationTest.java` (new; reuse the confirm-order fixture helpers from `DealQuotationConfirmOrderIntegrationTest`)

**Interfaces:**
- Produces (HTTP):
  - `GET /api/tickets/{id}/procurement-source` → `ProcurementSourceDto`
  - `POST /api/tickets/{id}/procurement-source` body `ProcurementSourceRequest` → `TicketDetailResponse`
- Produces (Java):
  - `record ProcurementSourceDto(boolean legacy, boolean editable, Instant decidedAt, String quotationNumber, List<Line> lines)` with `record Line(long itemId, boolean fromStock)`. `editable` = legacy && decidedAt == null && the caller may decide. `fromStock` = `qty > 0 && qty_from_stock >= qty`.
  - `record ProcurementSourceRequest(@NotNull @Size(min = 1) List<@Valid Line> lines)` with `record Line(@NotNull Long itemId, boolean fromStock)`.
  - `TicketService.getProcurementSource(long ticketId, UserPrincipal actor): ProcurementSourceDto` (view access as `requireViewAccess`).
  - `TicketService.decideProcurementSource(long ticketId, ProcurementSourceRequest req, UserPrincipal actor): TicketDto`
  - `TicketService.procurementSourceAwaitingRep(long ticketId): boolean` = legacy && not decided (used by Task 3).
  - `TicketRepository.isLegacyStockSourceDeal(long ticketId): boolean`
  - `TicketRepository.markProcurementSourceDecided(long ticketId, long actorId): boolean` (true when this call set it)
  - `TicketRepository.findProcurementSourceDecidedAt(long ticketId): Optional<Instant>`

**Migration V207:**
- `ALTER TABLE sales.ticket ADD COLUMN procurement_source_decided_at timestamptz, ADD COLUMN procurement_source_decided_by bigint` (same FK style as `keyed_by`, V202).
- Backfill: set `procurement_source_decided_at = now()` on every ticket that already has a row in `sales.import_request` or a non-null `fulfillment_status`, so in-flight deals are not pulled back into the dialog.
- Recreate `chk_event_kind` with `PROCUREMENT_SOURCE_DECIDED` added. Copy the latest definition (last redefined in `V204__deal_party.sql`) and append the one value.

**`isLegacyStockSourceDeal` SQL:** EXISTS a `sales.ticket_event` of kind `DEAL_PROMOTED_FROM_QUOTATION`, `related_document_type = 'QUOTATION'`, joined to `sales.quotation q` on `related_document_id`, with `q.origin = 'DEAL_DIRECT'` and `(q.issued_at AT TIME ZONE 'Asia/Bangkok')::date < DATE '2026-11-07'`. (Checked on prod 2026-11-10: all 15 stuck deals match, and all have `issued_at`.)

**`decideProcurementSource` rules, in order:**
1. `requireRole(actor, STOCK_DECLARATION_ROLES)` before loading (403).
2. `canDeclareStockCoverage(s, actor)` else 403 `ไม่มีสิทธิ์เข้าถึงรายการนี้`.
3. `requireActive(s)`; `stockCoverageStageReached(s)` else 409; `s.fulfillmentStatus() == null` else 409.
4. The request names every deal item exactly once, else 400 `ต้องระบุที่มาสินค้าให้ครบทุกรายการ`.
5. Not legacy: any line whose `fromStock` differs from the current coverage → 409 `ดีลนี้ใช้ที่มาสินค้าตามใบเสนอราคา แก้ไขไม่ได้`. No item write.
6. Legacy: write `qty_from_stock` (qty or 0) with `tickets.reserveStock(ticketId, lines)`; then `routeAllStockDealToDelivery(...)`; then, when the actor is in `SALES_ROLES` and any line is stock, `notifySalesManagerOfRepDeclaration(...)` (same call `reserveStock` makes).
7. `markProcurementSourceDecided` with `WHERE procurement_source_decided_at IS NULL`; 0 rows → 409 `เลือกที่มาสินค้าของดีลนี้แล้ว แก้ไขไม่ได้` (this also catches the race; the transaction rolls back step 6).
8. Event `PROCUREMENT_SOURCE_DECIDED`, description `เลือกที่มาสินค้า: นำเข้า {n} รายการ · จากสต็อก {m} รายการ`.

- [ ] **Step 1: Write the failing tests** in `ProcurementSourceDecisionIntegrationTest`. Each sets `issued_at` directly to make a deal old (`2026-11-06 10:00+07`) or new (`2026-11-08 10:00+07`):
  - `legacyFlagFollowsQuotationIssueDate`: GET → `legacy == true` for the 11-06 deal and `false` for the 11-08 deal and for a pricing-request deal.
  - `getStartsFromCurrentCoverage`: old deal where the CEO covered item B with `reserveStock` → GET lines `[A:false, B:true]`, and `editable == true` for the owner rep.
  - `oldDealSplitWritesCoverage`: owner POSTs `[A:false, B:true]` → `qty_from_stock` A = 0, B = qty; `decidedAt` is set; the event description equals `เลือกที่มาสินค้า: นำเข้า 1 รายการ · จากสต็อก 1 รายการ`; a sales_manager notification exists.
  - `oldDealAllStockRoutesToDelivery`: POST all stock → `fulfillmentStatus == FROM_STOCK`, `salesStage == DELIVERY_SCHEDULING`.
  - `newDealConfirmUnchangedRecordsOnly`: new deal with B ticked on the quotation; POST `[A:false, B:true]` → 200, `decidedAt` set, `qty_from_stock` unchanged.
  - `newDealChangeRefused`: POST `[A:true, B:true]` → 409 with the exact message; nothing written.
  - `secondDecisionIsRefused`: a second POST → 409 `เลือกที่มาสินค้าของดีลนี้แล้ว แก้ไขไม่ได้`; coverage unchanged; `editable == false` afterwards.
  - `emptyLinesRejected` / `missingItemRejected`: 400.
  - Cannot reach: `nonOwnerSalesForbidden` (403), `salesManagerForbidden` (403), `importForbidden` (403), `beforeOrderReceivedConflict` (409), `ceoMayDecide` (200, no manager notice).
  - `backfillMarksDealsWithImportRequests`: a deal that has an IR row before the migration reads `decidedAt != null`. (If the suite cannot run a migration mid-test, assert the backfill SQL against a seeded row instead.)
- [ ] **Step 2: Run, expect red for the right reason**
  Run: `cd backend && ./mvnw -q -Dtest=ProcurementSourceDecisionIntegrationTest test`
  Expected: the failures are a 404 on `/procurement-source` or assertion failures, not compile errors. Add the DTO/request records and empty endpoints first if needed so the failure is behavioural.
- [ ] **Step 3: Implement** the migration, repository methods, service methods and controller mappings above.
- [ ] **Step 4: Run green**, same command. Then regenerate the API surface: `cd backend && ./mvnw -q -Dtest=ApiSurfaceContractTest -Dapi.surface.update=true test`; the diff is exactly the two new operations plus the count bump.

---

### Task 2: Backend — withdraw the rep's per-line stock declaration

**Files:**
- Modify: `TicketService.java`: `reserveStock` (~1467) and the `RESERVE_STOCK` advertisement (~3388)
- Test (revise, do not add files): `StockDeclarationAuthzIntegrationTest`, `StockDeclarationNotificationIntegrationTest`, plus every test that calls `reserveStock` as a sales rep: `CommissionAutoCreateIntegrationTest`, `CommissionDealLinkageIntegrationTest`, `StockItemWeightedCommissionIntegrationTest`, `InventoryDeliveryFulfilmentIntegrationTest`, `DeliveryAuthzIntegrationTest`, `SalesRouteWalkIntegrationTest`, `StageFactGateIntegrationTest`, `TicketServiceTest`

**Interfaces:**
- Consumes: `decideProcurementSource` (Task 1) as the rep's replacement in revised fixtures.
- Produces: `reserveStock` is CEO-only; `RESERVE_STOCK` is advertised only to `CEO_ROLES`.

- [ ] **Step 1: Revise the tests.** In `StockDeclarationAuthzIntegrationTest`, change the owner-rep case to expect 403 and keep the CEO case at 200. Add `ownerRepNotAdvertisedReserveStock` (the actions list has no `RESERVE_STOCK` for the owner, and still has it for the CEO). In `StockDeclarationNotificationIntegrationTest`, drive the rep's declaration through `POST /procurement-source` on an old-deal fixture instead of `reserveStock`; the expected notice text is unchanged. In the other files, switch the sales-rep `reserveStock` setup calls to the CEO principal (they set up commission or delivery state; who declared is not under test). Leave a call alone if the test is specifically about the rep.
- [ ] **Step 2: Run red.** `./mvnw -q -Dtest='StockDeclaration*IntegrationTest' test` → the owner-rep 403 and advertisement assertions fail.
- [ ] **Step 3: Implement.** In `reserveStock`, after the role check: if the actor is not in `CEO_ROLES` → 403 `ระบุสินค้าจากสต็อกผ่านปุ่ม "จัดซื้อสินค้า" เท่านั้น`. Change the `RESERVE_STOCK` condition to `CEO_ROLES.contains(actor.role()) && stockCoverageStageReached(s) && s.fulfillmentStatus() == null`. Leave `canDeclareStockCoverage` unchanged; Task 1 uses it.
- [ ] **Step 4: Run green** for every file listed above, by name, in one `-Dtest=` list.

---

### Task 3: Backend — no IR on an old deal before the split is decided

**Files:**
- Modify: `backend/src/main/java/th/co/glr/hr/importrequest/ImportRequestService.java` (`createDrafts`, ~line 275, right after the ORDER_RECEIVED check)
- Test (revise): `backend/src/test/java/th/co/glr/hr/importrequest/ImportRequestPaymentGateIntegrationTest.java`

**Interfaces:**
- Consumes: `TicketService.procurementSourceAwaitingRep(long)` (Task 1).

- [ ] **Step 1: Add tests** to `ImportRequestPaymentGateIntegrationTest` (it already owns IR-create gates):
  - `oldUndecidedDealCannotCreateIr`: 409 with the message `กด "จัดซื้อสินค้า" เพื่อเลือกที่มาสินค้าก่อนออกใบขอซื้อ (IR)`; no IR rows written.
  - `oldDecidedDealCreatesIr`: after POST `/procurement-source` with all import → 201.
  - `newUndecidedDealStillCreatesIr`: a new deal creates an IR without deciding (the gate is old-deal only).
- [ ] **Step 2: Run red.** `./mvnw -q -Dtest=ImportRequestPaymentGateIntegrationTest test`; only the first case fails.
- [ ] **Step 3: Implement** the check in `createDrafts`: `if (ticketService.procurementSourceAwaitingRep(ticketId)) throw new ApiException(CONFLICT, <message above>)`.
- [ ] **Step 4: Run green**, plus `-Dtest='*ImportRequest*IntegrationTest'` to confirm the other IR suites (new-deal fixtures) are unaffected.

---

### Task 4: Frontend — API, mock and the dialog

**Files:**
- Modify: `frontend/src/api/routes.js` (`tickets.procurementSource: (id) => \`/api/tickets/${id}/procurement-source\``), `frontend/src/api/hrApi.js` (`tickets.getProcurementSource(id)`, `tickets.decideProcurementSource(id, payload)`), `frontend/src/api/mockApi.js` (same two methods, same rules as Task 1: legacy from a fixture flag, 409 on a second decision or a new-deal change, CEO-only `reserveStock`)
- Create: `frontend/src/features/tickets/ProcurementSourceDialog.jsx`
- Test: `frontend/src/features/tickets/ProcurementSourceDialog.test.jsx` (new), plus the existing mock contract test for `reserveStock`

**Interfaces:**
- Consumes: `ProcurementSourceDto` JSON `{ legacy, editable, decidedAt, quotationNumber, lines: [{ itemId, fromStock }] }` (Task 1).
- Produces: `<ProcurementSourceDialog open ticketId items procurementSource onClose onDecided />`. `items` are the deal page's line list (brand, model, size, qty, unit). `onDecided(ticket, { anyImport })` fires after a successful POST. Built on `components/common/Modal.jsx` (× in the header), with no ยกเลิก button.

- [ ] **Step 1: Write the failing tests:**
  - `shows every line and only ยืนยัน on a new deal`: `editable:false`; tags `นำเข้า`/`สต็อก` per line, the quick buttons are disabled, the text `ตามใบเสนอราคา QT-TEST-1` is shown, no switch, and exactly one button named `ยืนยัน` besides close.
  - `old deal: quick button sets every line`: clicking `จากสต็อก` turns every switch to `สต็อก`; flipping one line back selects `แยกรายการ`.
  - `old deal: starts from current coverage`: `lines:[{fromStock:false},{fromStock:true}]` → `แยกรายการ` is selected.
  - `posts every line once`: ยืนยัน calls `decideProcurementSource(id, { lines: [{itemId, fromStock}, …] })`, then `onDecided(…, { anyImport: true })`.
  - `shows the server's 409 message and stays open`.
  - `disables confirm when the deal has no lines`, showing `ยังไม่มีรายการสินค้า`.
  - `closes on × without posting`.
- [ ] **Step 2: Run red.** `cd frontend && npx vitest run src/features/tickets/ProcurementSourceDialog.test.jsx` → fails on the missing module (once), then on assertions after a stub export.
- [ ] **Step 3: Implement** the API, mock and dialog. Use existing `Button`/`Modal` variants only (the `Button` variant guard throws on unknown variants). The segmented quick buttons and the per-line switch follow the approved mockup v4/v5.
- [ ] **Step 4: Run green**, plus the mock contract test: `npx vitest run src/api`.

---

### Task 5: Frontend — main button, header chip, hints and digests

**Files:**
- Modify: `frontend/src/features/tickets/salesActions.js` (`SALES_ACTION.CHOOSE_PROCUREMENT_SOURCE = 'choose_procurement_source'`, its label and rank next to `CREATE_IMPORT_REQUEST`, and `importRequestAction`)
- Modify: `frontend/src/features/tickets/workState.js` (pass `procurementSource` through `resolveWorkState` → `nextSalesAction`, like `importRequests`)
- Modify: `frontend/src/features/tickets/TicketDetailPage.jsx` (fetch `getProcurementSource` with TanStack Query while the deal is at ORDER_RECEIVED/DEPOSIT_RECEIVED with no fulfilment status; handle `choose_procurement_source` in the sticky-primary chain; mount the dialog; banner text)
- Modify: `frontend/src/features/tickets/DealStateHeader.jsx:171` (chip)
- Modify: `frontend/src/features/tickets/stageMeta.js:55` (`AUTO_STAGE_HINT.PROCUREMENT`)
- Modify: `frontend/src/features/tickets/DealFulfilmentPanel.jsx` (IR button disabled with a hint while `procurementSource.legacy && !decidedAt`)
- Modify: `docs/api/ui-reachable.json` (regenerated)
- Test (revise): `salesActions.test.js`, `workState.test.js`, `TicketDetailPage.test.jsx`, `DealStateHeader` test, `stageMeta` test if present

**Interfaces:**
- Consumes: the dialog and API from Task 4.
- Produces: `nextSalesAction(…, { procurementSource })`. `importRequestAction` returns `{ key: 'choose_procurement_source', label: 'จัดซื้อสินค้า' }` whenever its existing gate passes and `procurementSource?.decidedAt` is not set; it returns the existing IR action once it is set. On the dashboard (`procurementSource` undefined) it therefore reads `จัดซื้อสินค้า`.

- [ ] **Step 1: Write the failing tests:**
  - salesActions: `procure action replaces IR before the source is decided` (label `จัดซื้อสินค้า`); `IR action returns once decided`; `no procure action before the deposit is settled`; `no procure action when every line is from stock`.
  - TicketDetailPage: the primary slot has `data-action="choose_procurement_source"`; clicking it opens the dialog; after `onDecided` with `anyImport:true`, the page switches to the จัดซื้อ-ส่งมอบ tab and the primary slot reads `ออกใบขอซื้อ (IR)`. Banner text is `เลือกว่าสินค้าสั่งนำเข้าหรือมาจากสต็อก` (old) and `ตรวจที่มาสินค้าตามใบเสนอราคา` (new).
  - DealStateHeader: the chip reads `ยังไม่ได้จัดซื้อ` when undecided and keeps `ยังไม่ออกใบขอซื้อ` after deciding.
  - stageMeta: `AUTO_STAGE_HINT.PROCUREMENT === 'อัปเดตอัตโนมัติเมื่อฝ่ายขายออกเลขใบขอซื้อ (IR) ของดีลนี้ · ถ้าสินค้ามาจากสต็อกทั้งหมด จะข้ามไปขั้นนัดส่งสินค้า'`.
- [ ] **Step 2: Run red.** `cd frontend && npx vitest run src/features/tickets/salesActions.test.js src/features/tickets/workState.test.js src/features/tickets/TicketDetailPage.test.jsx src/features/tickets/DealStateHeader.test.jsx`
- [ ] **Step 3: Implement.** After a decision that leaves import lines, the page does not open the IR confirmation itself: it switches to the จัดซื้อ-ส่งมอบ tab, and the main button becomes the existing IR action.
- [ ] **Step 4: Run green**, then regenerate the reachable set: `UI_REACHABLE_UPDATE=1 npx vitest run src/api/serverContract.test.js`, and re-run backend `ResponseFieldContractTest`. Both digest diffs are exactly the new operations.

---

### Task 6: Whole-branch check and handoff

- [ ] Opus reviewer: read the whole diff against the spec, revert each fix to confirm its tests go red, and confirm the coverage lists match the assertions.
- [ ] Browser check on the local stack (with `VITE_SELF_SERVICE_ONLY=true`, as on prod): an old deal and a new deal at stage 10, desktop and 375px. Confirm the dialog matches mockup v5.
- [ ] Rebase onto the latest `develop` and re-run Tasks 1–5's test commands.
- [ ] Stop and report to Ploy: the coverage list, the not-covered list, and the reminder that the backend needs a new image to reach prod. Commit, PR and merge only on Ploy's go.

**Deliberately not covered:** the follow-up repricing flow (spec "Follow-up"); partial-quantity stock on old deals (the dialog is whole-line, as the quotation tick is); deals priced in the retired pre-PCR engine (none at stage 10 on prod on 2026-11-10). They are classed "new" and locked to their current coverage.
