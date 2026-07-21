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

### Two more bugs found on review — fixed in the same step, not deferred

Per explicit instruction to fix any bug found, not just disclose it, both of the following were
implemented, tested, and mutation-checked by the reviewer before this branch was pushed.

**Bug A — a dropped line's `ticket_item` was never closed out, permanently blocking delivery.**
The implementing pass's own draft disclosed this as a "known risk, not handled": a pricing-request
line dropped entirely by a customer-change revision (present in the original request, absent from
the revision) was left untouched on `ticket_item` — neither removed nor zeroed. On inspection this
is not a narrow edge case but a real, deal-blocking correctness bug: `TicketService.completeDelivery`
iterates `ticket.items()` **unconditionally** — there is no filter for "is this item part of the
currently-accepted revision." A stale row with `qty > qty_delivered` therefore creates a phantom
open balance that can never be satisfied (nobody can deliver units of a product the customer no
longer ordered), so the deal can never reach `FulfilmentStatus.FULLY_DELIVERED` /
`DealStage.DELIVERED`.

Fixed with `TicketRepository.closeOutDroppedChainItems`, called from `reconcileTicketItems`
immediately after the existing per-item reconciliation loop: for every `ticket_item` that was
EVER referenced (via `source_ticket_item_id`) by SOME `pricing_request_item` in this pricing
request's own **root-anchored chain** (`pricing_request_id = :rootId OR root_pricing_request_id =
:rootId` — the same shape as `createCustomerChangeRevision`'s own root lookup) but is absent from
the CURRENTLY-accepted revision's items, close it by setting `qty = qty_delivered` — never below,
so a line already partially delivered before being dropped keeps exactly its true delivered
history, never fabricating a false claim and never violating the `chk_ticket_item_qty_delivered`
CHECK. Deliberately scoped to the chain, not the whole ticket, since a ticket can carry independent
pricing requests for other recipients (designer/owner/buyer) that must never be touched.

Proven with two new tests: `confirmOrder_revisionDropsALineEntirely_closesItsTicketItemSoItStops
BlockingDelivery` drives a two-item deal through a full delivery cycle after a dropped line,
proving the deal genuinely reaches `FULLY_DELIVERED`/`DELIVERED` (not just a data-shape assertion).
`confirmOrder_revisionDropsAPartiallyDeliveredLine_closesToWhatWasActuallyDelivered` proves the
already-delivered-2-of-5 case closes to exactly 2, not 0. **Mutation-checked**: neutralizing the
"still present in current revision" exclusion (kept the SQL syntactically valid — bound
`:currentId` against an impossible id rather than deleting the clause) turned 7 of 8 tests in the
class red, because every confirmOrder call's OWN current items got incorrectly closed to zero too
— confirming the guard protects every call, not just the dropped-line scenario. Reverted, re-ran
green (8/8).

**Bug B — a later revision's reconciliation was silently unreachable once payment had progressed.**
Found while writing Bug A's partial-delivery test, not anticipated in advance.
`OrderConfirmationService.confirmOrder`'s own Javadoc states it is meant to run "exactly once per
customer-accepted quotation" — i.e., once per accepted PRICING REQUEST, including a later revision
of the same deal — because the reconciliation above must happen for every accepted revision, not
just the deal's first one. But the method unconditionally called
`ticketService.confirmCustomer(ticketId, actor)` at the end, which is a ONE-TIME, TICKET-level
action whose own guard (correctly, deliberately — see its Javadoc: "never downgrade the payment
track") throws once `paymentStatus` has progressed past `CUSTOMER_CONFIRMED`. Since the whole
method is `@Transactional`, that exception rolled back the ENTIRE call — including the
reconciliation that had just run moments earlier. Concretely: deposit paid, stock reserved, THEN a
customer-change revision arrives and is accepted — a normal, reachable sequence — and calling
`confirmOrder` on that revision to reconcile its quantities always 409'd, silently leaving EVERY
item on that revision unreconciled, not merely the dropped-line case.

Fixed by reading the ticket's current `paymentStatus` before deciding whether to call
`confirmCustomer`: only invoke it when `paymentStatus == null` (genuinely the first confirmation);
otherwise the business fact "customer confirmed" is already true for the ticket, and this later
revision only needs its own reconciliation (already done) plus its own event/notification.
`confirmCustomer` itself was NOT modified — its refusal is correct and must stay.

Both `confirmOrder_revisionDropsAPartiallyDeliveredLine_closesToWhatWasActuallyDelivered` (Bug A's
own second test, which happens to drive a deal past deposit-paid before creating the revision) and
a dedicated **mutation-check** (reverting the `paymentStatus == null` gate to an unconditional
`confirmCustomer` call) confirm this: exactly that one test goes red with `Payment track already
past CUSTOMER_CONFIRMED`, reverted, re-ran green (8/8).

Mirrored in `frontend/src/api/mockApi.js` for both fixes (the closeout walks the mock's
`parentPricingRequestId` linked list to find the chain root, since the mock never modeled a flat
`rootPricingRequestId` convenience field the way the real schema does; the `confirmCustomer`-skip
mirrors the `paymentStatus == null` check exactly).

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
cd backend && ./mvnw -B -o clean verify           # 985/985, coverage met, BUILD SUCCESS
cd frontend && npm run lint                       # 0 errors, 3 pre-existing warnings
cd frontend && npx vitest run                     # 47 files / 400 tests, all green
cd frontend && npm run build                      # succeeds
git diff --check                                  # clean
```

Testcontainers ran for real — confirmed via live Flyway migration logs applying through V78 during
the `mvnw verify` run, and via `InventoryDeliveryFulfilmentIntegrationTest` genuinely catching the
`chk_event_kind` bug on its first run (a Mockito-only suite could not have surfaced that).

## Tests / Build Results

- Backend: **985/985**, 0 failures/errors/skipped, Jacoco coverage gate met, `BUILD SUCCESS`.
- Frontend: **400/400** tests, 0 lint errors, build succeeds.
- Test file `InventoryDeliveryFulfilmentIntegrationTest`, **8 tests** (6 from the implementing
  pass + 2 added by the reviewer for Bug A/B above), all real-Postgres
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
  7. `confirmOrder_revisionDropsALineEntirely_closesItsTicketItemSoItStopsBlockingDelivery` —
     **new, reviewer-added, proves Bug A's fix**; see the "Two more bugs found on review" section
     above for the mutation-check (7/8 tests in the class red when neutralized, reverted, green).
  8. `confirmOrder_revisionDropsAPartiallyDeliveredLine_closesToWhatWasActuallyDelivered` — **new,
     reviewer-added, proves both Bug A's history-preservation AND Bug B's fix** (this exact
     scenario — deposit paid, stock reserved, THEN a revision arrives — is what surfaced Bug B).

## Authz Evidence

No new role gate was introduced by this branch. Receiving/delivery actions continue to use the
pre-existing `FULFILMENT_ROLES` (`import`, `ceo`) already enforced by `TicketService.reserveStock`/
`completeDelivery`/`issueImportRequest` before this branch touched them; `ProcurementService`'s
existing `RAW_PO_ROLES` (Step 7) gates `recordGoodsReceived`. Both are unchanged reuse, cited not
re-proven. **No authz change in this branch.**

## Known Risks

- **No dedicated Import-facing receiving/delivery UI was built** — the mock mirrors the backend
  behavior (so `VITE_USE_MOCKS=true` verification of the data flow is possible), but there is no
  page exposing `recordGoodsReceived`/delivery-recipient/QC-note to a real user yet. Flag this
  explicitly for whoever picks up Step 9 or a UI-polish pass — the backend is complete and tested,
  the frontend surface for a human to actually use it is not.
- **Three real bugs found on review are fixed in this branch, not deferred** (the `chk_event_kind`
  fix from the implementing pass, plus Bug A and Bug B above) — small, additive/forward-only, and
  documented here plainly rather than smuggled in, per CLAUDE.md's own rule. Call out all three
  explicitly in the PR description so a reviewer doesn't mistake this for feature-only work.

## Suggested Next Prompt

This branch was already rebased onto `main` (post Step 7 merge, `ab64dce`) and its PR (#255)
opened before Bugs A and B above were found on review — those two fixes are a follow-up commit on
top, re-verified on the full suite (985/985 backend, 400/400 frontend) but NOT yet re-verified by a
fresh CI run at the time of writing. Push the follow-up commit, wait for CI to go green on it
specifically (a stale pass from before these fixes existed does not count as evidence), then merge.
Then proceed to Step 9 (final payment, closeout, commission) — which will need its own "does the
existing commission/final-payment machinery already read from the new chain correctly, or does it
need a Step-6/8-style reconciliation bridge" investigation before writing any
code, following the same verify-first discipline this step and Step 6 both required.
