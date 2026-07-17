# 73 — fix/ticket-flow-flowchart-alignment

Branch: `fix/ticket-flow-flowchart-alignment` (off `uat`). Not committed/pushed.

## Goal
Align the 14-stage deal pipeline transitions with the authoritative sales
flowchart (S1–S20), fix two transition bugs, and slim the deal-panel lifecycle
UI to "current stage + next only" by default.

## Bugs fixed (transition logic — matched to the flowchart)
1. **`DELIVERY_SCHEDULING` (S18) was orphaned.** The auto path went
   `PROCUREMENT (11) → DELIVERED (13)` on full delivery; nothing ever entered
   stage 12, and `autoAdvanceStage` is monotonic. The flowchart requires
   S17 (goods ready) → S18 (นัดส่งสินค้า/นัดรับเงินส่วนที่เหลือ) → S19. Now the
   "goods ready" signals auto-advance to `DELIVERY_SCHEDULING`.
2. **`CLOSED_PAID` (S20) was reachable on full payment before delivery.** The
   CLOSED_PAID auto-advance is now gated on BOTH payment fully-paid AND
   `FULLY_DELIVERED`, so it fires whichever track finishes last — matching the
   flowchart's S19/S20 convergence on `COMPLETED`.
   - **Review-round refinement:** the first cut reused `deliveryGateComplete`,
     which also treats `GOODS_RECEIVED` + zero delivery records as "complete" (a
     legacy accommodation for the *manual* `close()`). Since fix #1 now parks
     goods-received deals at `DELIVERY_SCHEDULING` with nothing delivered yet, that
     let a fully-paid warehouse deal auto-jump to `CLOSED_PAID` via the import
     path. Fixed by gating the *auto*-advance strictly on `FULLY_DELIVERED`;
     `deliveryGateComplete` (and the manual `close()`/`canClose()` legacy path) is
     left untouched. This is consistent with the UAT seed invariant
     (CLOSED_PAID ⇒ FULLY_PAID + FULLY_DELIVERED).

## Files changed
- `backend/.../ticket/TicketService.java`
  - New `maybeAdvanceClosedPaid(s, paymentFullyPaid, actor)` helper (reuses
    `deliveryGateComplete`); replaces the raw `autoAdvanceStage(..., CLOSED_PAID)`
    at `confirmFinalPayment` and `reconcilePaymentStatus`, and adds the
    delivery-side gate after full delivery in `recordDeliveryInternal`.
  - `markGoodsReceived` and `reserveStock` (full-stock coverage) now
    `autoAdvanceStage(..., DELIVERY_SCHEDULING)`. Full-stock skips PROCUREMENT
    (no import journey).
- `backend/.../ticket/TicketServiceTest.java`
  - Updated `reserveStock_fullCoverage_*` (now expects DELIVERY_SCHEDULING) and
    `midFulfillmentTransitions_*` (markGoodsReceived now advances to S18).
  - Retargeted the stale `confirmFinalPayment_autoAdvancesDealToClosedPaid` →
    `_afterFullDelivery_advancesDealToClosedPaid` (FULLY_DELIVERED fixture), added
    `confirmFinalPayment_goodsInWarehouseNotDelivered_doesNotAdvanceClosedPaid`
    (the exact GOODS_RECEIVED-not-delivered loophole) and
    `deliveryCompletion_whenAlreadyFullyPaid_advancesClosedPaid`.
- `frontend/src/api/mockApi.js` — mirrors the three backend fixes
  (`maybeAdvanceClosedPaid`, reserveStock→DELIVERY_SCHEDULING,
  markGoodsReceived→DELIVERY_SCHEDULING) so the mock stays a faithful stand-in.
- `frontend/src/features/tickets/DealStagePanel.jsx` — compact "ถัดไป: {next}"
  indicator; full 14-stage accordion stays behind the existing toggle.
- `frontend/src/features/tickets/DealStageStepper.jsx` — removed dead ternary
  `isDone ? 1 : 1`.

No schema/migration change. UAT seed (`V909`) + `FlywayMigrationTest` already
encode the target end-states (TKT-08 = DELIVERY_SCHEDULING/PARTIALLY_DELIVERED,
TKT-04 = CLOSED_PAID/COMPLETED) — left untouched and still green.

## Commands run / results
- `cd backend && ./mvnw -B test` → **BUILD SUCCESS, 526 tests, 0 failures**
  (incl. `TicketServiceTest` 156 + new tests, `FlywayMigrationTest` full V1..V54
  + UAT seed assertions on Testcontainers).
- `cd frontend && npm run lint` → 0 errors (4 pre-existing warnings in unrelated
  files) · `npm test` → 126 passed · `npm run build` → OK.
- **Mock e2e (frontend-mock):** drove IR→shipping→goods-received on PR-2026-0012;
  stage advanced PROCUREMENT → **DELIVERY_SCHEDULING (12)** with "ถัดไป: 13" — no
  longer skips S18. Confirmed default panel shows current+next and the 14-stage
  list only via the toggle.

## Known risks
- Behavioral change: pay-in-full-before-delivery deals no longer jump to
  CLOSED_PAID at payment time; they close when delivery completes. Confirm no
  external consumer treats CLOSED_PAID as a pure payment signal.
- Full-stock deals now skip the PROCUREMENT stage display (intentional). Verify
  no report counts "reached PROCUREMENT" for stock-sourced deals.
- Do NOT simplify `maybeAdvanceClosedPaid` to re-read `s.paymentStatus()` — the
  payment call sites hold a pre-write DTO; the explicit boolean is required.

## Next prompt
Review `fix/ticket-flow-flowchart-alignment` (Opus gate): re-verify the two
transition fixes against the flowchart and the mock/Java parity, then advise on
committing + opening the PR into `uat`.
