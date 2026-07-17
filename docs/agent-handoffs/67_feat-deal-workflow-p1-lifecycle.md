# Agent Handoff — Phase 1 of the branching-workflow program (FOR CODEX)

> **Program context.** The deal pipeline (one ticket = one deal, 14 stages) lives on two
> DRAFT PRs: #226 (backend, V50) and #227 (UI). Nothing is merged; there is NO production
> data. This is Phase 1 of 5 converting the pipeline into a safe branching workflow
> (lifecycle, policies, structured skips, actions API). Phases 2–5 (quotation records,
> payment ledger, partial delivery, reports/docs) come as separate handoffs AFTER this
> phase passes Opus review. Implement ONLY what this document specifies.

## Task
On a new branch `feat/deal-workflow-p1-lifecycle` **branched from `feat/deal-pipeline-base`**
(pushed; = PR #226 + PR #227 merged; backend 481 tests + frontend 118 tests green on it):

1. Deal lifecycle (ACTIVE / ON_HOLD / DORMANT / CLOSED_LOST / CANCELLED / COMPLETED) with
   on-hold/dormant/resume actions and lifecycle gating of all mutations.
2. Structured policies: tender_requirement, deposit_policy (+ reason/actor), entry_channel.
3. Case-6 path: deposit-waived deals may issue the IR without a deposit notice.
4. Skip-with-reason: multi-step forward stage jumps require a note.
5. Available-actions API: `GET /api/tickets/{id}/actions` — backend is the source of truth
   for what each viewer can do; the cockpit consumes it.

## Non-negotiable invariants (violating any of these fails review)

1. **Owner scoping.** A plain `sales` user may view/open/update ONLY deals they created
   (`ticket.created_by = user.id`). Every new endpoint must route through
   `TicketService.requireViewAccess` (reads) or the existing owner gates (writes).
   `sales_manager`/`import`/`account`/`ceo` see all tickets, as today.
2. **CEO price gate.** The internal price flow is untouchable: sales submits → Import
   proposes (from โรงงาน) → **CEO approves and returns the final price** → only then can
   quotations be issued (`QUOTATION_ALLOWED_STATUSES`). Phase 1 must not touch pricing.
3. **sales_manager** has exactly THREE ticket write powers (stage/lost/reopen — handoff 58
   exception, extended in this phase to on-hold/dormant/resume which are the same follow-up
   job). It must NEVER gain operational powers (submit/pickup/propose/approve/confirm-*).
4. **Do not change business logic outside this spec** (CLAUDE.md). No payroll/commission/
   pricing math. No API/DB changes beyond the ones listed.
5. **Mock parity.** Every hrApi method must exist in mockApi and vice versa
   (`frontend/src/api/contract.test.js` enforces both directions). Mock authz approximates
   the Java service and is NOT authoritative — permission truth lives in backend tests.
6. No skipped/disabled tests, no lint suppressions, no commented-out production logic.
7. Money is NUMERIC (not used in this phase, but never introduce floats).
8. Keep each namespace's `// Mirrors <JavaClass>` header in mockApi.js accurate.

## Repo orientation (verified facts — do not rediscover, do not assume otherwise)

- Backend: Spring Boot 4.1 / Java 21, **plain NamedParameterJdbcTemplate** (no JPA/ORM, no
  @Version). Status "enums" are String-constant classes + DB CHECK constraints.
- Key files (backend, `backend/src/main/java/th/co/glr/hr/ticket/`):
  - `TicketService.java` — all gates/transitions; deal-pipeline section near the bottom
    (updateStage/markLost/reopenDeal/autoAdvanceStage, SALES_TARGET_STAGES etc.).
  - `DealStage.java` (14 codes, ORDER, indexOf), `DealLostReason.java`,
    `TicketEventKind.java`, `TicketStatus.java`, `TicketRepository.java`,
    `TicketController.java`, `TicketSummaryDto.java`.
  - Tests: `TicketServiceTest.java` (Mockito style; `stubDeal(...)` helper builds a
    TicketSummaryDto with stage/lost fields; `actor(id, role)` builds principals).
- Migrations: `backend/src/main/resources/db/migration/`, **next free = V51**
  (V50__deal_sales_pipeline.sql is the deal pipeline; follow its comment style).
  Widening `chk_event_kind` = full DROP + re-ADD listing ALL kinds (see V39/V48/V50).
  Flyway runs in CI via Testcontainers (`FlywayMigrationTest`), schemas incl. `sales`.
- Frontend: React 18 + Vite, plain JS, TanStack Query, Tailwind-first (no new CSS files).
  - `frontend/src/features/tickets/` — `TicketDetailPage.jsx` (cockpit wiring: `can` flags,
    `primaryAction`, `doAction(fn, msg)` helper, `applyTicketUpdate` cache fast-path),
    `DealStagePanel.jsx` (hero + chips + guidance + docs), `stageMeta.js` (stage order,
    gates, PRICING/PAYMENT/PROCUREMENT_SUBSTEPS), `TicketListPage.jsx` (phase cards).
  - API plumbing: `frontend/src/api/routes.js` (`API_ROUTES.tickets.action(id, name)`),
    `hrApi.js`, `mockApi.js` (tickets namespace mirrors TicketController; the mock imports
    gates from `stageMeta.js` so mock/UI can't drift), `queryKeys.js`.
  - Thai labels: `frontend/src/utils/format.js` (`dealStageLabel`, `dealLostReasonLabel` —
    add new label fns here, map → `{label, tone}`).
- Verification commands: `cd backend && ./mvnw -B clean verify` (Testcontainers needs
  Docker; note if skipped) · `cd frontend && npm run lint && npm test && npm run build`.
  Manual verification uses the `frontend-mock` launch config (VITE_USE_MOCKS=true, port
  5200) with the login page's quick-login persona buttons.

## Backend spec

### V51__deal_lifecycle_and_policies.sql
```sql
ALTER TABLE sales.ticket
    ADD COLUMN lifecycle            VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN tender_requirement   VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN deposit_policy       VARCHAR(20) NOT NULL DEFAULT 'REQUIRED',
    ADD COLUMN deposit_policy_reason TEXT,
    ADD COLUMN deposit_policy_set_by BIGINT REFERENCES hr.employee(employee_id),
    ADD COLUMN entry_channel        VARCHAR(20) NOT NULL DEFAULT 'DESIGNER_LED';
-- CHECKs:
--   lifecycle IN ('ACTIVE','ON_HOLD','DORMANT','CLOSED_LOST','CANCELLED','COMPLETED')
--   tender_requirement IN ('REQUIRED','NOT_REQUIRED','UNKNOWN')
--   deposit_policy IN ('REQUIRED','NOT_REQUIRED','WAIVED','CREDIT_CUSTOMER')
--   entry_channel IN ('DESIGNER_LED','OWNER_DIRECT','BUYER_DIRECT')
-- Backfill (simple — no production data exists):
--   lost_reason IS NOT NULL        -> 'CLOSED_LOST'
--   status = 'cancelled'           -> 'CANCELLED'
--   status = 'closed'              -> 'COMPLETED'
--   else                           -> 'ACTIVE'
-- Index: idx_ticket_lifecycle ON sales.ticket(lifecycle)
-- chk_event_kind: re-declare (full list from V50) + 'ON_HOLD','DORMANT','RESUMED'
```
`lost_reason`/`lost_at` STAY as the CLOSED_LOST detail (do not fold into lifecycle).

### Java
- `DealLifecycle.java` (ticket pkg): 6 constants + `VALID` set (+ short javadoc: ON_HOLD/
  DORMANT are pauses — reopenable; CLOSED_LOST carries lost_reason; COMPLETED only via
  close()). New constants in `TicketEventKind`: `ON_HOLD`, `DORMANT`, `RESUMED`.
- `TicketPolicy.java` or constants where natural: TenderRequirement, DepositPolicy,
  EntryChannel value sets (follow DealLostReason.java style).
- `TicketSummaryDto` += `lifecycle`, `tenderRequirement`, `depositPolicy`,
  `depositPolicyReason`, `entryChannel` (+ SUMMARY_SELECT/mapper in TicketRepository; also
  update the `withCustomerAndProject` copier in TicketService and ALL test constructors —
  grep `new TicketSummaryDto(`).
- `TicketRepository`: `updateLifecycle(id, value)`, `updateTenderRequirement(id, value)`,
  `updateDepositPolicy(id, policy, reason, setBy)`, `updateEntryChannel(id, value)`.
- `TicketService`:
  - **Lifecycle actions** (gate = deal owner / sales_manager / ceo, same as markLost):
    `placeOnHold(id, note, actor)` (ACTIVE→ON_HOLD; note optional),
    `markDormant(id, note, actor)` (ACTIVE|ON_HOLD→DORMANT),
    `resume(id, note, actor)` (ON_HOLD|DORMANT→ACTIVE). Each writes its event kind with
    from/to = current salesStage (stage preserved) and the note as message.
    Rewire existing methods: `markLost` also sets lifecycle=CLOSED_LOST; `reopenDeal` sets
    ACTIVE (works from CLOSED_LOST only, as today via lost_reason); `cancel` sets
    CANCELLED; `close` sets COMPLETED.
  - **Lifecycle gating**: introduce one private `requireActive(summary)` (409 with a clear
    Thai message naming the lifecycle) called by: submit, pickup, proposePrice, approve,
    reject, generateQuotation, editItems, calculatePrices, overrideItemPrice, updateStage,
    the four dual-track confirms + IR + mid-fulfillment marks, comment stays allowed.
    (`markLost`/`placeOnHold`/`markDormant` require ACTIVE; `resume`/`reopenDeal` require
    their source states; `cancel` keeps its current terminal-status rule + not-CANCELLED.)
    `autoAdvanceStage` already no-ops on lost — extend to no-op unless lifecycle ACTIVE.
  - **Policy actions**: `setTenderRequirement(id, value, actor)` (owner/sales_manager/ceo);
    `setEntryChannel(id, value, note, actor)` (same gate; note required when changing an
    existing non-default value); `waiveDeposit(id, policy, reason, actor)` where policy ∈
    {NOT_REQUIRED, WAIVED, CREDIT_CUSTOMER} (gate = ACCOUNT_ROLES — money decision; reason
    REQUIRED; records deposit_policy_set_by; event kind COMMENTED? No — add message-bearing
    STAGE-neutral audit via a `COMMENTED`-style event is wrong; use kind `EDITED`? Also
    wrong. **Add event kinds** `POLICY_CHANGED` to chk_event_kind + TicketEventKind and use
    it for all three policy actions with a message like `deposit_policy → WAIVED — <reason>`).
  - **Case 6**: in `issueImportRequest`, `depositReady` also true when
    `deposit_policy ∈ (NOT_REQUIRED, WAIVED, CREDIT_CUSTOMER)` and paymentStatus is null or
    CUSTOMER_CONFIRMED. (Keep the fulfillment-already-started guard.)
  - **Skip-with-reason**: in `updateStage`, a forward move where
    `indexOf(target) - indexOf(current) > 1` requires a non-blank note (400, Thai message
    "การข้ามขั้นตอนต้องระบุเหตุผล"). Single-step stays note-optional; backward rule unchanged.
  - **Create**: `CreateTicketRequest` += optional `entryChannel` (validated; default
    DESIGNER_LED at the repo layer).
- **Actions API**: `GET /api/tickets/{id}/actions` on TicketController →
  `requireViewAccess`, then build from the same gate logic:
  ```json
  { "currentState": { "lifecycle": "...", "salesStage": "...", "paymentStatus": null,
                      "fulfillmentStatus": null, "status": "..." },
    "availableActions": [
      { "action": "SUBMIT",            "kind": "operational", "label": "ส่งขอราคา" },
      { "action": "ADVANCE_STAGE",     "kind": "stage", "targetStage": "SPEC_APPROVED" },
      { "action": "UPDATE_STAGE",      "kind": "stage", "requiredFields": ["stage"] },
      { "action": "GENERATE_QUOTATION","kind": "doc" },
      { "action": "WAIVE_DEPOSIT",     "kind": "policy", "requiredFields": ["policy","reason"] },
      { "action": "PLACE_ON_HOLD",     "kind": "lifecycle" }, ...
    ] }
  ```
  Implementation: extract the eligibility checks into small pure helpers (or a
  `TicketActionCatalog` class in the ticket pkg) reused by BOTH the transition methods and
  the endpoint, so the endpoint can never drift from enforcement. Cover: submit, pickup,
  propose, approve, reject, generate quotation, deposit-notice-eligible (status
  quotation_issued + ps CUSTOMER_CONFIRMED + owner), confirm-customer, deposit-paid, IR,
  ir-sent, shipping, goods-received, final-payment, close, cancel, edit-items, advance/
  update stage, mark-lost, reopen, on-hold, dormant, resume, set-tender, set-entry-channel,
  waive-deposit. `requiredFields` for anything needing reason/stage.

### New/changed endpoints (TicketController)
`GET /{id}/actions` · `POST /{id}/hold` `{note?}` · `POST /{id}/dormant` `{note?}` ·
`POST /{id}/resume` `{note?}` · `POST /{id}/tender-requirement` `{value}` ·
`POST /{id}/entry-channel` `{value, note?}` · `POST /{id}/deposit-policy`
`{policy, reason}`. Request records with jakarta validation, following the existing
UpdateStageRequest pattern at the bottom of TicketController.

## Frontend spec

- `routes.js`/`hrApi.js`: `tickets.actions(id)` (GET), `hold`, `dormant`, `resume`,
  `setTenderRequirement`, `setEntryChannel`, `setDepositPolicy` — via
  `API_ROUTES.tickets.action(id, '...')`. Contract test must pass with the mock.
- `mockApi.js` (tickets namespace): mirror ALL of the above incl. the actions endpoint
  (compute from the same stageMeta gates + new lifecycle/policy fields on db.tickets; add
  `lifecycle: 'ACTIVE'`, `tenderRequirement: 'UNKNOWN'`, `depositPolicy: 'REQUIRED'`,
  `entryChannel: 'DESIGNER_LED'` to the load-time backfill loop at the top of mockApi.js).
  Mock lifecycle gating mirrors requireActive.
- `format.js`: `dealLifecycleLabel(value)` → {label, tone} (ACTIVE ไม่แสดง badge ก็ได้;
  ON_HOLD 'พักไว้ชั่วคราว' warning; DORMANT 'พักยาว (dormant)' neutral; CLOSED_LOST
  'เสียงาน' danger; CANCELLED 'ยกเลิก' danger; COMPLETED 'เสร็จสมบูรณ์' success), plus
  labels for policies (tender/deposit/entry-channel Thai labels).
- `DealStagePanel.jsx` + `TicketDetailPage.jsx`:
  - **Consume `GET /actions`** (new query key `ticketActions(id)`; invalidate alongside
    ticketDetail in `applyTicketUpdate`). The cockpit's `primaryAction`/doc buttons/stage
    buttons render only when the corresponding action is present in `availableActions` —
    the local `can` flags remain ONLY as render-shortcuts, actions list wins. (Keep the
    existing button JSX/handlers; gate visibility on the endpoint response.)
  - Lifecycle banner in the panel: ON_HOLD → amber banner + "ดำเนินการต่อ (Resume)";
    DORMANT → neutral banner + resume; CANCELLED/COMPLETED → terminal banner, no actions.
    New buttons "พักดีลไว้" / "พัก dormant" next to เสียงาน (same gate), with note dialog.
  - Deposit policy: in the การชำระเงิน chips row area, show the policy when not REQUIRED
    ("ไม่เก็บมัดจำ — <reason>") and HIDE the deposit chips (ออกใบแจ้งมัดจำ/รับมัดจำ) when
    policy ∈ NOT_REQUIRED/WAIVED (CREDIT_CUSTOMER shows a credit note instead). Account/CEO
    get a "นโยบายมัดจำ…" action opening a small modal (policy select + required reason).
  - Tender: at AWAITING_BUYER stage show tender_requirement control (REQUIRED/NOT_REQUIRED/
    UNKNOWN select, owner/manager/ceo).
  - Skip dialog: UpdateStageModal already requires a note for backward moves — ALSO require
    it when the selected target skips ≥1 stage forward (mirror backend), with helper text.
- `TicketListPage.jsx`: lost bucket becomes lifecycle-aware — the เสียงาน card counts
  CLOSED_LOST; ON_HOLD/DORMANT deals show a small badge on the row (stage cell) but keep
  their phase; CANCELLED/COMPLETED behave as today (status chip labels already exist).
  Keep this minimal — the full report/filter work is Phase 5.

## Out of scope (do NOT build now)
Quotation recipient_type/terms (Phase 2) · payment ledger/amounts/overdue (Phase 3) ·
partial delivery/stock declaration/fulfilment enum extension (Phase 4) · dashboards,
filters, workflow doc, 22-scenario matrix (Phase 5). No renaming of stages. No UI redesign.

## Tests (minimum)
Backend (`TicketServiceTest` style, extend `stubDeal` for lifecycle/policy fields):
- lifecycle transitions: hold/dormant/resume happy + wrong-source 409 + gate 403s
  (other-sales, import, account denied; owner/sales_manager/ceo allowed);
- requireActive: submit/updateStage/confirmCustomer/etc. 409 on ON_HOLD deal; comment OK;
- markLost sets CLOSED_LOST; reopen restores ACTIVE; cancel→CANCELLED; close→COMPLETED;
- waiveDeposit: account/ceo only, reason required, records set_by; issueImportRequest passes
  with WAIVED policy + null paymentStatus and still 409s with REQUIRED policy;
- skip-with-reason: 2-step jump w/o note 400, with note OK; 1-step w/o note OK;
- actions endpoint: sales owner vs other sales (403 via requireViewAccess) vs sales_manager
  vs import vs account vs ceo — assert action lists match the can-rules for 2–3 fixture
  states (draft deal, quotation_issued + CUSTOMER_CONFIRMED, ON_HOLD deal).
Frontend: contract.test.js green; stageMeta/modal tests updated if gates surface there; one
component test asserting the cockpit hides actions absent from a mocked /actions response.

## Definition of done (Codex fills before passing back)
1. All listed backend + frontend changes implemented on `feat/deal-workflow-p1-lifecycle`.
2. `cd backend && ./mvnw -B clean verify` → BUILD SUCCESS (report test count; note if
   integration tests skipped for missing Docker).
3. `cd frontend && npm run lint && npm test && npm run build` → 0 lint errors, all green.
4. frontend-mock manual pass (describe what you drove): hold→gated actions→resume; waive
   deposit→IR without deposit notice; multi-step skip requires reason; actions endpoint
   drives the cockpit.
5. Update THIS file's sections below (Files changed / Commands run / Results / Known risks /
   Questions for review). Commit everything on the phase branch. Do NOT merge anything.

## Files changed
- Backend lifecycle/policy model and migration:
  - `backend/src/main/resources/db/migration/V51__deal_lifecycle_and_policies.sql`
  - `backend/src/main/java/th/co/glr/hr/ticket/DealLifecycle.java`
  - `backend/src/main/java/th/co/glr/hr/ticket/TenderRequirement.java`
  - `backend/src/main/java/th/co/glr/hr/ticket/DepositPolicy.java`
  - `backend/src/main/java/th/co/glr/hr/ticket/EntryChannel.java`
  - `backend/src/main/java/th/co/glr/hr/ticket/TicketEventKind.java`
  - `backend/src/main/java/th/co/glr/hr/ticket/TicketSummaryDto.java`
  - `backend/src/main/java/th/co/glr/hr/ticket/CreateTicketRequest.java`
- Backend persistence/API/service:
  - `backend/src/main/java/th/co/glr/hr/ticket/TicketRepository.java`
  - `backend/src/main/java/th/co/glr/hr/ticket/TicketResponses.java`
  - `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java`
  - `backend/src/main/java/th/co/glr/hr/ticket/TicketController.java`
- Backend tests/constructor updates:
  - `backend/src/test/java/th/co/glr/hr/ticket/TicketServiceTest.java`
  - `backend/src/test/java/th/co/glr/hr/ticket/TicketRepositoryIntegrationTest.java`
  - `backend/src/test/java/th/co/glr/hr/ticket/QuotationRendererTest.java`
  - `backend/src/test/java/th/co/glr/hr/pricing/PriceCalcServiceTest.java`
  - `backend/src/test/java/th/co/glr/hr/deposit/DepositNoticeServiceTest.java`
  - `backend/src/test/java/th/co/glr/hr/attachment/AttachmentControllerTest.java`
- Frontend API/mock/query/format wiring:
  - `frontend/src/api/hrApi.js`
  - `frontend/src/api/mockApi.js`
  - `frontend/src/api/queryKeys.js`
  - `frontend/src/utils/format.js`
- Frontend cockpit/list/stage UI:
  - `frontend/src/features/tickets/TicketDetailPage.jsx`
  - `frontend/src/features/tickets/DealStagePanel.jsx`
  - `frontend/src/features/tickets/UpdateStageModal.jsx`
  - `frontend/src/features/tickets/TicketListPage.jsx`
  - `frontend/src/features/tickets/TicketDetailPage.test.jsx`

## Commands run
- `git switch feat/deal-pipeline-base`
- `git switch -c feat/deal-workflow-p1-lifecycle`
- `cd backend && ./mvnw -q -Dtest=TicketServiceTest test`
- `cd backend && ./mvnw -B clean verify`
  - First sandboxed run failed because Testcontainers could not access Docker.
  - Reran with approved escalation for Docker/Testcontainers access.
- `cd frontend && npm run lint`
- `cd frontend && npm test -- --run`
- `cd frontend && npm run build`
- `cd frontend && npm run dev -- --host 127.0.0.1 --port 5200`
  - Started for manual frontend-mock verification, then stopped.

## Tests / build results
- Backend focused test: `TicketServiceTest` passed.
- Backend full verify: `BUILD SUCCESS`; Testcontainers/Flyway ran successfully after Docker escalation.
  - Reported summary: 487 tests run, 0 failures, 0 errors, 0 skipped.
- Frontend lint: passed with 0 errors and 4 pre-existing hook dependency warnings outside this work:
  - `AttendancePage.jsx` missing `loadPunches`
  - `CommissionPage.jsx` missing `load`, `canCeoReview`, `canReviewRecord`
  - `PayrollPage.jsx` missing `load`
- Frontend tests: 27 files passed, 119 tests passed.
  - Added component coverage that `TicketDetailPage` hides an approve cockpit action when mocked `/actions` omits `APPROVE`.
- Frontend build: passed.
- Manual frontend-mock pass: attempted but not completed because the in-app browser runtime failed during setup with `Cannot redefine property: process`, even after `js_reset`; direct Playwright import was also unavailable in this repo/runtime (`Cannot find package 'playwright'`). The dev server itself started on `http://127.0.0.1:5200/` and was stopped afterward.

## Known risks / questions for Opus review
- Manual UI verification is the main gap: hold/resume, deposit-waived IR, skip reason, and action-driven cockpit behavior are covered by service/mock/component tests, but were not browser-driven because the browser tooling failed locally.
- The actions response is computed inside `TicketService` and reuses the same guard helpers where practical, but review should pay close attention to drift between each transition guard and its advertised action, especially around policy/lifecycle roles.
- `sales_manager` now gets lifecycle pause/resume plus the specified stage/lost/reopen powers; it should still have no operational powers. Backend tests assert representative action visibility, but this remains a high-value review area.
- `deposit_policy` bypass currently allows IR when policy is `NOT_REQUIRED`, `WAIVED`, or `CREDIT_CUSTOMER` and payment status is null or `CUSTOMER_CONFIRMED`, matching the handoff. Confirm with business reviewers whether `CREDIT_CUSTOMER` should hide deposit chips or show a credit note only in all later UI/reporting phases.
