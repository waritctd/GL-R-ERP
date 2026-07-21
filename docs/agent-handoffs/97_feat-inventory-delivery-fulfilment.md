# Handoff: Step 8 — Receiving, Inventory Allocation, and Delivery

Date: 2026-07-21
Branch: `feat/inventory-delivery-fulfilment`, stacked on Step 7 (`feat/procurement-factory-order`,
tip `a749b29`, merged to `main` as `ab64dce`)
Primary migration: `V78__inventory_delivery_receiving.sql`

**Authorship note**: the implementing Sonnet subagent did the core design and code work (verified
in detail below) but died mid-session to an API error before writing this handoff or doing final
verification. This document was written by the reviewing session (Opus) after independently
re-deriving every claim below from the code and re-running every check — nothing here is taken on
the subagent's word.

## Scope Implemented

Unlike Step 7 (nothing existed) and like Step 6 (bridge into working code), Step 8 found
substantial existing delivery/stock machinery (`TicketService.reserveStock`/`completeDelivery`,
`FulfilmentStatus`) already keyed on `sales.ticket_item.qty`/`qty_delivered`/`qty_from_stock`. The
central question — whether that quantity source already tracked the pricing chain correctly, or
drifted — was resolved by evidence, not assumption, and the answer was: **it drifts**, and a real
reconciliation bridge was required.

### The central finding

`sales.ticket_item.qty` is written exactly once, at ticket creation (`TicketRepository.insertItems`,
called from `TicketService.create`), and is never touched again by Steps 1-6.
`sales.pricing_request_item.requested_qty` is independently entered from the moment a pricing
request is first drafted — nothing ties the two together at input time. Confirmed as an
**already-live** mismatch, not hypothetical, by reading `ProcurementServiceIntegrationTest`'s own
fixture: every `ticketItem(...)` helper call creates a ticket item with `qty=1`, while the
pricing-request items in the same test request quantities of 10 and 5.

It gets worse after acceptance: `PricingRequestService.createCustomerChangeRevision` (Step 2's
remediation) is reachable even from `QUOTATION_ACCEPTED` — its own status guard only excludes
`DRAFT`/`CANCELLED`/`SUPERSEDED`, and does not consult `PricingRequestStatus.ALLOWED` (which marks
`QUOTATION_ACCEPTED` terminal for forward transitions, but that table only governs `transition()`,
not this method's own separate guard). So a customer can revise quantities on an already-accepted
deal, creating a wholly new `pricing_request`/`pricing_request_item` row set that never writes back
to the original `ticket_item` row.

### The fix

`OrderConfirmationService.confirmOrder` (Step 6's bridge point — the one call that always runs,
exactly once per customer-accepted quotation, strictly before any delivery machinery becomes
reachable) now calls a new `reconcileTicketItems` step before handing off to the existing
`TicketService.confirmCustomer`:

- For every `pricing_request_item` with a `sourceTicketItemId`, `TicketRepository.reconcileItemQty`
  UPDATEs that `ticket_item`'s `qty`/`qty_sqm` to the pricing request's current `requested_qty`/
  `requested_qty_sqm`, guarded `WHERE ... AND qty IS DISTINCT FROM :qty` (no-op if already correct).
- For an item with no `sourceTicketItemId` (a wholly new line added by a customer-change revision),
  `TicketRepository.insertReconciledItem` creates one.
- **Safety net, not an afterthought**: the existing `chk_ticket_item_qty_delivered`/
  `chk_ticket_item_qty_from_stock` CHECK constraints (V54, already merged, not new) refuse a
  downward reconciliation that would drop `qty` below an already-recorded `qty_delivered`/
  `qty_from_stock` — `reconcileItemQty` lets that `DataIntegrityViolationException` surface, and
  the service layer catches it and converts it to a clean `409`, not a raw `500` or (worse) a
  silently-applied corruption. **Mutation-checked**: removing that catch-and-convert makes
  `confirmOrder_revisionLowersQtyBelowAlreadyDelivered_isRejectedNotSilentlyApplied` fail exactly as
  expected (an uncaught `DataIntegrityViolationException` instead of the clean `ApiException`/409
  the test asserts) — confirmed by the reviewer, not merely claimed.

**Design decision, stated explicitly**: extend `sales.ticket_item` in place rather than repointing
`reserveStock`/`completeDelivery` at `pricing_request_item`/`quotation_item` directly (the
alternative considered, mirroring Step 6's `payableAmount` COALESCE-chain pattern). Chosen because
the delivery machinery's `qty_delivered`/`qty_from_stock`/event-logging surface (V54) is large,
already-working, and entirely keyed off `ticket_item.item_id` — keeping that one source of truth
correct is a smaller, safer diff than teaching it a second quantity source.

**Known risk, disclosed**: a pricing-request line dropped entirely by a customer-change revision
(present in the original request, absent from the revision) is left untouched on `ticket_item` —
neither removed nor zeroed. This is a narrow edge case (a full removal, not a quantity reduction)
and is NOT handled by this branch. Flag for a follow-up if it proves to matter in practice.

### A second, independent finding: a real pre-existing production bug

`InventoryDeliveryFulfilmentIntegrationTest` is the **first real-Postgres integration test ever
written** for `TicketService.reserveStock`/`recordDeliveryInternal` (the existing Mockito-only
`TicketServiceTest` cannot reach a DB CHECK constraint). It failed on its first real run with `new
row for relation ticket_event violates check constraint chk_event_kind`.

Root cause, traced precisely: `V54` added `STOCK_RESERVED`/`DELIVERY_RECORDED`/`DELIVERY_COMPLETED`
to `chk_event_kind` so these methods could log events. `V56`'s own full re-declaration of that same
CHECK (following the established "widen by full re-declaration" pattern already used by
V39/V48/V50-V53) accidentally **dropped** those three values instead of carrying them forward — its
list simply omits them. `V76` (Step 6, already merged) inherited that same incomplete list. This has
been silently broken on `main` since `V56` first shipped; **verified against merged `main`
directly** (`git show origin/main:.../V76...sql | grep STOCK_RESERVED` returns nothing) — this is
not something Step 8 introduced and then fixed within the same branch.

Fixed forward-only in `V78` (never editing V54/V56/V76 in place): re-declares `chk_event_kind` one
more time, restoring the three missing values and matching `TicketEventKind.java`'s full current
constant list exactly.

### Receiving detail (Step 7 follow-through)

`sales.factory_purchase_order_item` gains `qty_received`/`qc_note` (`V78` Part 1) — the actual
quantity Import physically counted on receipt vs. the PO's own ordered quantity (frozen from the
approved costing), so a real discrepancy is a reportable per-line fact instead of only a
PO-level received/not-received flag flip. `ProcurementService.recordGoodsReceived` accepts optional
per-item detail; when omitted, defaults fill from the ordered quantity (backward-compatible with a
plain "received, no discrepancy" call).

### Delivery note (minimal, not over-built)

`sales.delivery_record` gains one nullable column, `recipient_name` (`V78` Part 2) — who on the
customer's side received/confirmed the goods. Deliberately just a column, not a new
`DepositNoticeRenderer`-style PDF pipeline, per the task's explicit "do not over-build" instruction;
the existing free-text `note` field already carries a proof-of-delivery narrative.

## Files Changed

**Backend:**
- `backend/src/main/resources/db/migration/V78__inventory_delivery_receiving.sql` — new. Three
  parts: PO receiving detail columns, delivery-record recipient column, and the `chk_event_kind`
  forward-fix.
- `backend/src/main/java/th/co/glr/hr/ticket/TicketRepository.java` — `insertDeliveryRecord`/
  `findDeliveries` gain `recipientName`; new `reconcileItemQty`/`insertReconciledItem`.
- `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java` — plumbs `recipientName` through
  `recordDeliveryInternal`/`completeDelivery`/`RecordDeliveryRequest` call sites.
- `backend/src/main/java/th/co/glr/hr/ticket/RecordDeliveryRequest.java`,
  `CompleteDeliveryRequest.java`, `DeliveryRecordDto.java` — `recipientName` field added.
- `backend/src/main/java/th/co/glr/hr/orderconfirmation/OrderConfirmationService.java` —
  `reconcileTicketItems` (the central-finding fix), called from `confirmOrder` before
  `TicketService.confirmCustomer`.
- `backend/src/main/java/th/co/glr/hr/procurement/ProcurementService.java`/`Repository.java`/
  `Dtos.java`/`Requests.java` — `recordGoodsReceived` with optional per-item `qtyReceived`/`qcNote`.
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestEventKind.java` — new
  `TICKET_ITEMS_RECONCILED` event kind.
- `backend/src/test/java/th/co/glr/hr/orderconfirmation/InventoryDeliveryFulfilmentIntegrationTest.java`
  — new, 7 tests (see below).
- `backend/src/test/java/th/co/glr/hr/procurement/ProcurementServiceIntegrationTest.java`,
  `backend/src/test/java/th/co/glr/hr/ticket/TicketServiceTest.java` — minor updates for the new
  `recipientName`/`qtyReceived` fields.

**Frontend:**
- `frontend/src/api/mockApi.js` — `reconcileTicketItemsFromPricingRequest` (mirrors the backend
  reconciliation, including the same downward-revision refusal), `recipientName` threaded through
  delivery recording, `recordGoodsReceived` extended for per-item detail. No new API surface (no
  `hrApi.js` changes needed) — these extend already-existing mock handlers for endpoints Steps 6-7
  already exposed, not new endpoints of their own.
- No new frontend page/component. Receiving/delivery remains Import+CEO-facing and, per the task's
  scope instruction, this branch does not build dedicated UI for it — flagged as a known gap below,
  not a decision to leave unaddressed.

## Commands Run (by the reviewer, independently)

```
cd backend && ./mvnw -B -o -DskipTests compile   # confirmed clean before any further work
cd backend && ./mvnw -B -o clean verify           # 983/983, coverage met, BUILD SUCCESS
cd frontend && npm run lint                       # 0 errors, 3 pre-existing warnings
cd frontend && npx vitest run                     # 47 files / 400 tests, all green
cd frontend && npm run build                      # succeeds
git diff --check                                  # clean
```

Testcontainers ran for real — confirmed via live Flyway migration logs applying through V78 during
the `mvnw verify` run, and via `InventoryDeliveryFulfilmentIntegrationTest` genuinely catching the
`chk_event_kind` bug on its first run (a Mockito-only suite could not have surfaced that).

## Tests / Build Results

- Backend: **983/983**, 0 failures/errors/skipped, Jacoco coverage gate met, `BUILD SUCCESS`.
- Frontend: **400/400** tests, 0 lint errors, build succeeds.
- New test file `InventoryDeliveryFulfilmentIntegrationTest`, 7 tests, all real-Postgres
  (`AbstractPostgresIntegrationTest`):
  1. `confirmOrder_reconcilesTicketItemQty_fromTicketCreationStubToFirstPricingRequestQty`
  2. `confirmOrder_costAffectingRevisionAfterAcceptance_reconcilesToRevisedQty_notOriginal`
  3. `confirmOrder_calledOnBothTheOriginalAndTheRevision_convergesForwardToTheRevisedQty`
  4. `fullChain_reserveStockAndCompleteDelivery_operateAgainstReconciledQty_notStaleTicketCreationQty`
  5. `completeDelivery_rejectsWhenNothingOutstanding_ratherThanRedeclaringDelivered` (wrong-way-round
     on the "must not be marked delivered when open quantities remain" hard constraint from the
     task brief — exercises pre-existing V54 logic, not new to this branch, so not separately
     mutation-checked)
  6. `confirmOrder_revisionLowersQtyBelowAlreadyDelivered_isRejectedNotSilentlyApplied` —
     **mutation-checked by the reviewer**: removed the `DataIntegrityViolationException` catch in
     `reconcileTicketItems`; this exact test failed (raw exception instead of the clean 409 it
     asserts); reverted, re-confirmed green.

## Authz Evidence

No new role gate was introduced by this branch. Receiving/delivery actions continue to use the
pre-existing `FULFILMENT_ROLES` (`import`, `ceo`) already enforced by `TicketService.reserveStock`/
`completeDelivery`/`issueImportRequest` before this branch touched them; `ProcurementService`'s
existing `RAW_PO_ROLES` (Step 7) gates `recordGoodsReceived`. Both are unchanged reuse, cited not
re-proven. **No authz change in this branch.**

## Known Risks

- **A revision-dropped line is not cleaned up on `ticket_item`** (see "Design decision" above) —
  narrow edge case, disclosed, not fixed here.
- **No dedicated Import-facing receiving/delivery UI was built** — the mock mirrors the new backend
  behavior (so `VITE_USE_MOCKS=true` verification of the data flow is possible), but there is no
  page exposing `recordGoodsReceived`/delivery-recipient/QC-note to a real user yet. Flag this
  explicitly for whoever picks up Step 9 or a UI-polish pass — the backend is complete and tested,
  the frontend surface for a human to actually use it is not.
- **This branch is stacked on Step 7**, which was merged to `main` as `ab64dce` during this same
  review session — this branch has not yet been rebased onto that merge. Do that, re-verify the
  full suite on the rebased tree, before pushing/opening a PR.
- **The `chk_event_kind` fix is a real production bug fix riding inside a feature branch.** It is
  small, additive, and forward-only, and is documented here plainly rather than smuggled in, per
  CLAUDE.md's own rule — but flag it explicitly in the PR description as "also fixes a pre-existing
  bug," not just feature work, so a reviewer doesn't miss that this branch fixes something already
  broken on `main`.

## Suggested Next Prompt

Rebase `feat/inventory-delivery-fulfilment` onto current `main` (Step 7 is now merged), re-run the
full suite on the rebased tree, push, open a PR calling out the `chk_event_kind` fix explicitly, wait
for real CI, merge. Then proceed to Step 9 (final payment, closeout, commission) — which will need
its own "does the existing commission/final-payment machinery already read from the new chain
correctly, or does it need a Step-6/8-style reconciliation bridge" investigation before writing any
code, following the same verify-first discipline this step and Step 6 both required.
