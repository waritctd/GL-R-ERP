# Agent Handoff

## Task
Implement Step 7 of the sales pricing chain: Factory Purchase Order and Import Execution. Build
the (previously nonexistent) procurement/purchase-order module: a Factory PO sourced from the
exact factory-quote/costing chain the customer's selling price was already built from, supplier
proforma/payment-schedule tracking, shipping detail (container/ETD/ETA/customs), and actual
landed cost recorded on receipt — composed with, not replacing, the existing ticket-level
`FulfilmentStatus` flag sequence (`IR_ISSUED -> IR_SENT -> SHIPPING -> CUSTOMS_CLEARANCE ->
GOODS_RECEIVED`).

## Branch
`feat/procurement-factory-order`, stacked on Step 6 tip `f7f69c3` ("feat(sales): deposit, payment,
and order confirmation (Step 6)"), itself off `main` tip `f07f487` (Steps 1-4) + `3da94e5` (Step 5).

## Base Commit
`f7f69c3`.

## Current Commit
Uncommitted working tree — **not committed or pushed**, per instructions.

## Agent / Model Used
Claude (Sonnet 5).

## Scope

### In Scope
- Migration `V77__factory_purchase_order.sql`: new `sales.factory_purchase_order` and
  `sales.factory_purchase_order_item` tables, `sales.factory_purchase_order_no_seq`. Forward-only,
  no edits to any prior migration.
- New package `th.co.glr.hr.procurement`: `ProcurementRepository`, `ProcurementService`,
  `ProcurementController`, `ProcurementRequests`, `ProcurementDtos`, `FactoryPurchaseOrderStatus`.
- `PricingRequestEventKind`: 5 new event kinds (`FACTORY_PO_CREATED`,
  `FACTORY_PO_PROFORMA_RECORDED`, `FACTORY_PO_SHIPPING_RECORDED`, `FACTORY_PO_GOODS_RECEIVED`,
  `FACTORY_PO_CANCELLED`) — logged onto the existing `sales.pricing_request_event` timeline
  (no DB CHECK constraint on that table's `event_kind`, so no widen-constraint migration was
  needed for this, unlike Step 6's `ticket_event.chk_event_kind` widen).
- Backend test: `ProcurementServiceIntegrationTest` (9 tests, real Postgres) — drives a two-item,
  two-factory deal through the REAL Steps 1-6 services to `DealStage.PROCUREMENT`, creates POs,
  asserts item traceability, records proforma/shipping/goods-received, proves composition with
  `markIrSent`/`markShipping`/`markGoodsReceived`, and proves the cross-tenant guard and both
  gate guards in genuine isolation (see "Authz Evidence" below — this took two iterations because
  the first fixture made both gates redundant with each other).
- Frontend: `api/hrApi.js`/`api/routes.js` (`procurement` namespace, `ROLE_PERMISSIONS
  .canManageProcurement`), `api/mockApi.js` (mock `procurement` namespace + 2 new mock arrays),
  `api/queryKeys.js`, `utils/format.js` (`factoryPurchaseOrderStatusLabel`),
  `components/layout/AppShell.jsx` (nav item), `app/permissions.js` (`PATH_GUARDS` entry),
  `App.jsx` (2 new routes), new `features/procurement/ProcurementListPage.jsx` +
  `ProcurementDetailPage.jsx` (+ 8 new component tests, UI-level only).
- `docs/agent-handoffs/96_feat-procurement-factory-order.md` (this file).

### Out of Scope (confirmed not touched)
- **`TicketService.markIrSent`/`markShipping`/`markGoodsReceived` — zero changes.** Decided and
  documented (see "Decisions Made" #2): the Factory PO is an optional detail layer, not a new
  precondition on these methods. Verified by test: the acceptance-scenario test drives all three
  through to completion on a ticket that still has an untouched, still-`OPEN` second PO sitting
  alongside it — proving the two layers are genuinely independent, not coupled.
- **No change to `FulfilmentStatus`** — its 5-value set (`IR_ISSUED`/`IR_SENT`/`SHIPPING`/
  `CUSTOMS_CLEARANCE`/`GOODS_RECEIVED`) is deliberately NOT reused for
  `FactoryPurchaseOrderStatus`, which is its own short, linear, independent set
  (`OPEN`/`SHIPPING`/`RECEIVED`/`CANCELLED`) — see that class's own Javadoc for why duplicating
  the ticket-level machinery at the PO level would be over-engineering for what this task needs.
- **No `sales.ticket_event.chk_event_kind` widen** — every Step 7 event is logged onto
  `sales.pricing_request_event` (no DB constraint on `event_kind` there), never onto
  `sales.ticket_event`, since no ticket-level status write happens in this branch at all.
- **No CEO/Import "raw" vs Sales "progress" split view** — the task allowed this as optional
  ("a small structured note or a child table if genuinely needed"). Decided not built: the whole
  controller is gated Import/CEO-only (`RAW_PO_ROLES`), with no separate sales-facing endpoint at
  all, the strictest reading of "Sales exclusion" and the smallest diff. Documented as a known
  simplification below, not a silent gap.
- Payroll/tax/SSO/commission math: untouched.

## The central finding — this step genuinely builds new entities

Confirmed by reading, before writing any code: there is no pre-existing procurement/purchase-order
module anywhere in this codebase. `th.co.glr.hr.ticket.FulfilmentStatus` (unchanged by this
branch) is a bare 5-value string-flag sequence with no PO record, no supplier detail, no ETA/ETD,
no customs tracking, no landed-cost record, and no linkage to which factory-quote revision was
actually used. Unlike Step 6 (which found a substantial, already-tested deposit/payment pipeline
to bridge into), this step had nothing to bridge into — `ProcurementRepository`/`Service`/
`Controller`/`Dtos` are entirely new, per the task brief's own explicit framing.

## Files Changed

### Backend — new
- `backend/src/main/resources/db/migration/V77__factory_purchase_order.sql`.
- `backend/src/main/java/th/co/glr/hr/procurement/ProcurementRepository.java`.
- `backend/src/main/java/th/co/glr/hr/procurement/ProcurementService.java`.
- `backend/src/main/java/th/co/glr/hr/procurement/ProcurementController.java`.
- `backend/src/main/java/th/co/glr/hr/procurement/ProcurementRequests.java`.
- `backend/src/main/java/th/co/glr/hr/procurement/ProcurementDtos.java`.
- `backend/src/main/java/th/co/glr/hr/procurement/FactoryPurchaseOrderStatus.java`.
- `backend/src/test/java/th/co/glr/hr/procurement/ProcurementServiceIntegrationTest.java`.

### Backend — modified
- `pricingrequest/PricingRequestEventKind.java` — 5 new event kind constants + `VALUES` set.

### Frontend — new
- `frontend/src/features/procurement/ProcurementListPage.jsx` (+ `.test.jsx`, 3 tests).
- `frontend/src/features/procurement/ProcurementDetailPage.jsx` (+ `.test.jsx`, 5 tests).

### Frontend — modified
- `api/hrApi.js` — `procurement` namespace (8 methods, mirroring `ProcurementController`).
- `api/routes.js` — `API_ROUTES.procurement`, `ROLE_PERMISSIONS.canManageProcurement`.
- `api/mockApi.js` — mock `procurement` namespace (8 methods) sourcing PO items from
  `mockPricingDecisions`/`mockPricingCostings` exactly like the real backend does from
  `sales.pricing_decision`/`pricing_costing_item`; `findFactoryPurchaseOrderRaw`/
  `buildFactoryPurchaseOrderView` helpers; `mockFactoryPurchaseOrders` array +
  `mockFactoryPurchaseOrderSeq`/`mockFactoryPurchaseOrderItemSeq`.
- `api/queryKeys.js` — 3 new keys.
- `utils/format.js` — `factoryPurchaseOrderStatusLabel`.
- `components/layout/AppShell.jsx` — nav item ("ใบสั่งซื้อโรงงาน"), Import/CEO-gated.
- `app/permissions.js` — `PATH_GUARDS` entry for `/factory-purchase-orders(/...)`.
- `App.jsx` — 2 new lazy routes.

## Migration Numbering — re-verified per the task's explicit instruction

Checked live via `git worktree list` plus listing every worktree's own
`backend/src/main/resources/db/migration/` directory, at the start of this session:
- This worktree's own tree topped at `V76__quotation_accepted_order_confirmation.sql` (Step 6,
  inherited) before this branch's own `V77` was added.
- `.claude/worktrees/deposit-order` (the Step 6 branch this one is stacked on, same tip `f7f69c3`)
  also topped at `V76` — not a collision, same commit.
- Top-level `GL-R-ERP` (`feat/sales-factory-quote-costing`) topped at `71`.
- `GL-R-ERP-employees`, `GL-R-ERP-main`, `.claude/worktrees/flyway-audit`,
  `.claude/worktrees/profile-avatar-menu` all topped at `54`.
- `.claude/worktrees/nav-menu-grouping` topped at `55`.
- `.claude/worktrees/quotation-outcome` (Step 5) topped at `75`.

**`V77` was free at the check above.** Re-verify again before merging if time has passed —
every prior handoff on this chain repeats this same caveat, and it has mattered before
(`docs/flyway-version-collision-audit`).

## Commands Run

```bash
cd backend && ./mvnw -o compile
cd backend && ./mvnw -o -Dtest=ProcurementServiceIntegrationTest -Djacoco.skip=true test   # iterative, during development
cd backend && ./mvnw -B clean verify
cd frontend && npm ci
cd frontend && npm run lint
cd frontend && npx vitest run
cd frontend && npm run build
git diff --check
```

## Test / Build Results

<!-- FILL IN: verbatim tail of `./mvnw -B clean verify` once the background run in this session
completes. The isolated `-Dtest=ProcurementServiceIntegrationTest` run (see below) already
confirmed 9/9 green with real Testcontainers Postgres before the full-suite run was kicked off. -->

**Backend — isolated `ProcurementServiceIntegrationTest` run (verbatim tail, real Postgres via
Testcontainers, confirmed by the "Successfully applied 75 migrations to schema \"hr\", now at
version v77" log line only reachable through a real, freshly-provisioned Postgres):**
```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 43-44 s -- in th.co.glr.hr.procurement.ProcurementServiceIntegrationTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```

**Backend — full `clean verify`**: kicked off, running in background at the time this section was
drafted (>120s). Baseline (Step 6 tip `f7f69c3`): 964/964. Step 7 adds **9** net tests
(`ProcurementServiceIntegrationTest`), so 964 + 9 = **973** expected. <!-- FILL IN actual result
and whether it matches this arithmetic before treating this handoff as final. -->

**Frontend:**
```
$ npm run lint
✖ 3 problems (0 errors, 3 warnings)   # pre-existing, unrelated (CommissionPage.jsx, PayrollPage.jsx)

$ npx vitest run
 Test Files  47 passed (47)
      Tests  400 passed (400)

$ npm run build
✓ built in 162ms
```
Baseline: 45 files / 392 tests. Step 7 added **8** tests net (`ProcurementListPage.test.jsx` × 3,
`ProcurementDetailPage.test.jsx` × 5). No existing assertion was weakened or removed.

`git diff --check`: <!-- FILL IN: run once more before closing this handoff. -->

**Testcontainers ran for real** — no `TEST_DB_URL` was set (confirmed empty) and `docker info`
confirmed running before every run; every integration test run printed the live Flyway migration
log "Successfully applied 75 migrations to schema \"hr\", now at version v77" once per test class,
only reachable through a real, freshly-provisioned Postgres container.

## Authz Evidence

**Every authorization-shaped change in this branch shipped a real-DB integration test through the
real Java service and repository** (`AbstractPostgresIntegrationTest`/Testcontainers,
`ProcurementServiceIntegrationTest`), and **every guard was mutation-checked**: introduce the bug
(comment/disable exactly one condition), run the SPECIFIC named test with a clean build, confirm
it goes red, revert to a byte-identical diff (`diff` against a pre-write backup copy confirmed
identical each time), re-confirm the specific test green, then the whole class green (9/9) at the
end.

| Guard | Where | Mutation-check result (verbatim) |
|---|---|---|
| Import/CEO only (`RAW_PO_ROLES`) — read AND write | `ProcurementService.requireRole(actor, RAW_PO_ROLES)`, called at the top of every public method | **Red** — added `sales` to `RAW_PO_ROLES` (`Set.of("import", "ceo", "sales")`), ran `createPurchaseOrders_salesActor_cannotCreate` + `salesActor_cannotReadPurchaseOrders` together: both failed with `Expecting code to raise a throwable` (the calls succeeded instead of throwing 403). Reverted (diff against backup confirmed byte-identical), re-ran green (2/2), then whole class green (9/9). |
| Cross-tenant/cross-request reference guard — a PO cannot reference a `pricing_costing_item` from a different pricing request | `ProcurementRepository.insertItems`'s `AND pc.pricing_request_id = :pricingRequestId` join condition | **Red** — removed the join condition, ran `insertItems_rejectsCostingItemFromADifferentPricingRequest` directly: failed (`expected: 0 but was: 1` — the foreign costing item item was inserted). Reverted (byte-identical), re-ran green. |
| `PricingRequestStatus.QUOTATION_ACCEPTED` gate | `ProcurementService.createPurchaseOrders`'s `if (!PricingRequestStatus.QUOTATION_ACCEPTED.equals(summary.status()))` | **CORRECTED DURING THIS SESSION'S OWN VERIFICATION** (not caught by a later reviewer, per the task's own explicit warning to be honest about this) — the FIRST test written for this guard, `createPurchaseOrders_beforeQuotationAccepted_isRejected` (single pricing request, ticket never reaches `DealStage.PROCUREMENT` either), stayed **fully green** with this check disabled (`if (false && ...)`) — because on a single-pricing-request deal, `DealStage.PROCUREMENT` can never be true without `QUOTATION_ACCEPTED` already having been true first (the ONLY path to `PROCUREMENT` is `issueImportRequest`, which requires `ticket.status=QUOTATION_ISSUED`, which only the Step 6 bridge writes, which requires `QUOTATION_ACCEPTED`). The two guards are provably redundant on that fixture — exactly the trap CLAUDE.md's Step 6 section warns about. A SECOND, genuinely isolating fixture was built instead (`createPurchaseOrders_ticketAtProcurementButThisPricingRequestNotAccepted_isRejected`): a SECOND pricing request on the SAME ticket (CLAUDE.md's own "1 Deal -> 0..N Pricing Requests" model), driven all the way to `QUOTATION_ISSUED` (its own real APPROVED decision, its own real costing items — so `findApprovedPricingCostingId` cannot be what blocks it either), while the FIRST pricing request has already driven the ticket to `PROCUREMENT`. With the `QUOTATION_ACCEPTED` check disabled, this test WAS red (`Expecting code to raise a throwable`) — genuinely isolated. Reverted, re-ran green. The original (non-isolating) test is KEPT in the suite as a real, still-passing acceptance case, but is explicitly NOT cited as evidence for this guard in the test file's own comment. |
| `DealStage.PROCUREMENT` gate | `ProcurementService.createPurchaseOrders`'s `if (!DealStage.PROCUREMENT.equals(ticket.salesStage()))` | **Red, independently** — disabled this check alone (QUOTATION_ACCEPTED check left active), ran `createPurchaseOrders_quotationAcceptedButImportRequestNotYetIssued_isRejected` (same pricing request reaches `QUOTATION_ACCEPTED` and even `DEPOSIT_PAID`, but `issueImportRequest` deliberately never called): failed (`Expecting code to raise a throwable`). This fixture already isolates this guard correctly — QUOTATION_ACCEPTED is true, only DealStage is false. Reverted, re-ran green. |

**Reporting:** the frontend UI-level tests (`ProcurementListPage.test.jsx`/
`ProcurementDetailPage.test.jsx`) are **not authz evidence** — they prove this component's own
rendering/mutation wiring against a hand-rolled mock, not server-side enforcement (both test files
carry this exact disclaimer in their own header comment). The authoritative checks are the backend
guards in the table above, all real-DB, all mutation-proven with individual isolation confirmed —
including the one case where the FIRST attempt at isolation was itself wrong and had to be
corrected before it could be trusted, exactly the failure mode CLAUDE.md's Step 6 section warns
prior agents on this chain have made before.

## Decisions Made — stated explicitly per CLAUDE.md's sales-flow-redesign license

1. **Source of truth: `sales.pricing_decision` (status=APPROVED) -> its own `pricing_costing_id`
   -> `sales.pricing_costing_item`, grouped by `factory_name`.** Not a bare "latest SUBMITTED
   costing" query — over multiple return-to-Import round trips a pricing request can have several
   SUBMITTED costing versions across its history, and only the one an APPROVED decision actually
   references is the one the customer's accepted quotation was built from. Reading through the
   decision, not the costing table directly, is what makes this correct.
2. **`markIrSent`/`markShipping`/`markGoodsReceived` remain independently callable, with the
   Factory PO as an optional detail layer alongside them — NOT gated on an open PO existing.**
   The task explicitly asked this to be decided and stated. Reasoning: those three methods are
   already-working, already-tested machinery this branch was told not to weaken; adding a new
   precondition to an already-shipping-capable method is a real behavior change the task
   explicitly flagged as something to avoid smuggling in. Proven by test, not merely asserted: the
   acceptance-scenario test drives all three ticket-level calls to completion while a SECOND PO on
   the same ticket sits untouched at `OPEN` throughout.
3. **The creation gate is BOTH `pricing_request.status == QUOTATION_ACCEPTED` AND
   `ticket.salesStage == DealStage.PROCUREMENT`** — deliberately at least as strict as
   `issueImportRequest`'s own precondition, not weaker, per the task's explicit instruction. Read
   literally: `DealStage.PROCUREMENT` is only reachable via `issueImportRequest` having already
   fired, so a PO cannot exist before Import has actually issued the import request for this deal
   — stricter than merely "eligible to issue an IR." Both conditions are independently
   mutation-proven (see Authz Evidence table) — they turned out to be genuinely non-redundant only
   once tested against a multi-pricing-request-per-ticket fixture, which is documented above as a
   correction made during this session's own verification pass, not glossed over.
4. **`FactoryPurchaseOrderStatus` is its own short, linear, 4-value set (`OPEN`/`SHIPPING`/
   `RECEIVED`/`CANCELLED`), deliberately NOT reusing `FulfilmentStatus`'s 5-value ticket-level
   set.** `customs_status` is a free-text column, not a status value — the task's own "do not
   over-engineer" instruction for the supplier payment schedule extended naturally to not
   reimplementing the ticket-level fulfillment status machine a second time at the PO level.
5. **One PO per factory per pricing request, enforced by a plain (non-partial) UNIQUE constraint**
   (`uq_factory_po_request_factory` on `(pricing_request_id, factory_name)`) — mirrors Step 2's own
   per-factory-quote grouping. A cancelled PO can never be replaced by a fresh one for the same
   factory on the same pricing request; this is a known, accepted limitation (see "Known Risks").
6. **`client_request_id` is stored per-row but is NOT globally unique per creator** — unlike every
   prior step's own `uq_*_creator_client_request` unique index. `createPurchaseOrders` is a BATCH
   action minting one PO per distinct factory under a single key, so the same key legitimately
   labels multiple rows in one call; a unique index on `(created_by, client_request_id)` was tried
   first and immediately broke on a real 2-factory test run (`DuplicateKeyException` on the second
   insert of the same batch) — caught by the integration test, not assumed correct. The real
   idempotency guarantee for a retried batch is the natural key (#5 above): a retry simply
   re-finds the existing PO per factory and skips it, proven by the acceptance test's "idempotent
   re-create: no duplicate POs, no duplicate items" assertion.
7. **No sales-facing "progress only" view was built** — the task left this as a "your call"-shaped
   optional (the CEO/Import visibility requirement said Sales must be EXCLUDED, not that they must
   see something else). The entire `ProcurementController` surface is gated Import/CEO-only, with
   no separate endpoint for Sales progress visibility. Smallest-diff reading; if a future step
   needs Sales-visible procurement progress, that is new scope, not a gap in this one.
8. **PO line items are frozen snapshots (`quantity`/`unit_price`/`currency`/`line_total`), copied
   at creation time, never re-derived from `pricing_costing_item` on read.** The acceptance test
   asserts this directly by comparing the PO item's stored values against the costing item's own
   columns read independently.

## Assumptions
- A PO's `currency` is the `raw_currency` of its first grouped costing item — factory quotes are
  effectively single-currency per factory in this codebase's existing data model (every prior
  step's own THB-only-mock-vs-real-FX pattern applies equally here), so no per-line currency
  reconciliation was built.
- The mock's `pricingCostingItemId` is stood in by `pricingRequestItemId` (1:1 within one costing,
  and the costing is immutable once its decision is approved) — the SAME simplification the
  existing Step 3 mock (`mockPricingDecisions`) already makes for its own `pricingCostingItemId`
  field, reused rather than inventing a new synthetic id scheme.

## Known Risks
- **A cancelled PO cannot be replaced** — the plain (non-partial) unique constraint on
  `(pricing_request_id, factory_name)` means once a factory's PO is cancelled, no new PO for that
  same factory/request pair can ever be created. Not exercised by a negative test in this branch
  (out of scope for the acceptance scenario given); a future step that needs "re-open procurement
  for a cancelled factory line" would need either a partial unique index (`WHERE status <>
  'CANCELLED'`) or an explicit re-open action — deliberately not built here to keep the diff small,
  per the task's own "do not over-engineer" instruction.
- **No dedicated `ProcurementService` Mockito unit test file** — matches every prior step's own
  precedent (Steps 2-6 have no dedicated Mockito file for their own top-level orchestration
  service either); all coverage is the real-DB integration test.
- **No sales-facing procurement visibility** — see Decision #7. If a future step's brief asks for
  Sales to see PO progress (not raw cost), that is new scope.
- **Frontend UI has not been manually click-through-verified in `frontend-mock`** — only automated
  component tests were run (8 new tests, both UI-level, explicitly labelled as not authz
  evidence). No agent on this branch performed a manual browser walkthrough of the Import PO
  list/detail flow.
- **This branch is stacked on Step 6, itself uncommitted in a sibling worktree
  (`.claude/worktrees/deposit-order`)** at the time of writing — both branches sit at the same
  documented tip (`f7f69c3`); re-verify migration numbering again before merging, per every prior
  handoff's own repeated caveat.

## Things Not Finished
- No commit was made yet — per instructions.
- The full backend `./mvnw -B clean verify` was kicked off but had not finished at the time this
  handoff was drafted (>120s, moved to background) — <!-- FILL IN the final verbatim tail and
  whether 973/973 (964 baseline + 9 new) actually held, before treating Step 7 as closed. -->
- `git diff --check` was run once during the isolated test cycle but should be re-run once more
  against the FINAL diff before closing this handoff.
- No manual `frontend-mock` click-through of the new Import PO list/detail pages.

## Recommended Next Agent
1. Confirm the full backend `clean verify` result recorded above (973/973 expected) and fill in
   the two `<!-- FILL IN -->` placeholders in this file with the actual verbatim output.
2. Independently re-run every mutation-check row in the Authz Evidence table above — this chain
   has a documented history (Steps 5, 6) of overstated verification claims, and this step's own
   `QUOTATION_ACCEPTED` guard row already documents ONE correction made mid-session; a second pair
   of eyes reproducing all four rows from a clean state is the standing practice on this chain.
3. A manual click-through of the new `/factory-purchase-orders` list and detail pages in
   `frontend-mock` as Import — create a PO on a `DealStage.PROCUREMENT` deal, record proforma,
   shipping detail, and goods received, confirm the UI reflects each step — has not yet been done
   by any agent on this branch.
4. Step 8 (whatever comes after import execution — landed-cost variance reporting comparing
   `pricing_costing_item.total_landed_cost_thb` [estimate] against
   `factory_purchase_order.actual_landed_cost_thb` [actual] was mentioned as a "later reporting"
   need in the task brief but explicitly deferred here) remains open.

## Exact Next Prompt
```
Review docs/agent-handoffs/96_feat-procurement-factory-order.md in full, then:
1. Confirm the full `./mvnw -B clean verify` result (973/973 expected: 964 baseline + 9 new
   ProcurementServiceIntegrationTest tests) and fill in the two <!-- FILL IN --> placeholders in
   that file's "Test / Build Results" and "Things Not Finished" sections with the actual verbatim
   output.
2. Independently re-run every mutation-check row in the "Authz Evidence" table (introduce each
   described bug, confirm the same test(s) go red in isolation, revert, confirm green) — this
   chain has a documented history of overstated verification claims, and this step's own
   QUOTATION_ACCEPTED guard required a mid-session correction after the first isolating fixture
   turned out to still be redundant with DealStage.PROCUREMENT.
3. Do a manual click-through of Step 7 in frontend-mock as Import: drive a deal to
   DealStage.PROCUREMENT (Steps 1-6), create a Factory PO per factory on /factory-purchase-orders,
   open the detail page, record supplier proforma, shipping detail, and goods received — no agent
   on this chain has yet performed a manual UI walkthrough of Step 7.
```
