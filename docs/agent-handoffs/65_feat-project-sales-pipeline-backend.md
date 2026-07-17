# Agent Handoff

## Task
Backend half of the **deal sales pipeline**: one ticket = one deal, and the 14-stage pipeline
(LEAD_APPROACH → CLOSED_PAID, 5 phases) is what it takes to close that deal. This REPLACES an
earlier project-based design (stages on customers.project) after the user redirected mid-review:
a โครงการ is a thin grouping that can hold many deals; the stage belongs to the deal.

Stage model (business cross-reference to the boss's S1–S20 sheet lives in DealStage.java's
javadoc): 14 semantic codes, 5 phases; S4+S5 merged into QUOTE_DESIGN_SIDE; S12–S17 collapsed
into PROCUREMENT whose sub-detail is the ticket's own fulfillment_status. Lost reasons F1–F8 →
8 semantic codes (DealLostReason).

## Branch
`feat/project-sales-pipeline-backend` (branched from `main` at `64391f2`; history REWRITTEN
2026-07-17 — the earlier project-based commits 61574c8/7c372bf were replaced by one deal-based
commit; branch was never pushed)

## Base Commit
`64391f2`

## Agent / Model Used
Claude Opus 4.8 (plan + implementation + verification)

## Files changed
- `backend/src/main/resources/db/migration/V50__deal_sales_pipeline.sql` (NEW) —
  sales_stage/lost_reason/lost_at/stage_updated_at on **sales.ticket** (CHECK-constrained,
  lost-pair invariant), stage backfill derived from status + dual-track (most-advanced first),
  chk_event_kind re-declared (V39/V48 pattern) to allow STAGE_CHANGED/MARKED_LOST/REOPENED —
  stage history reuses sales.ticket_event so each deal has ONE timeline.
- `backend/src/main/java/th/co/glr/hr/ticket/DealStage.java` (NEW) — 14 codes, ordered,
  indexOf for monotonic compare; javadoc carries the S1–S20 cross-reference.
- `.../ticket/DealLostReason.java` (NEW) — 8 codes + VALID set.
- `.../ticket/TicketEventKind.java` — +STAGE_CHANGED/MARKED_LOST/REOPENED.
- `.../ticket/TicketSummaryDto.java` — +salesStage/lostReason/lostAt/stageUpdatedAt.
- `.../ticket/TicketRepository.java` — SUMMARY_SELECT + mapper for the 4 new columns;
  updateSalesStage/markDealLost/clearDealLost; `create` now branches: **no items → DRAFT +
  CREATED event (lightweight lead-stage deal)**, items → SUBMITTED as before.
- `.../ticket/CreateTicketRequest.java` — items now OPTIONAL (lead-stage deals have none yet);
  projectId @NotNull (one deal = one ticket under a โครงการ).
- `.../ticket/TicketService.java` —
  - `create`: 400 without projectId; notifications to import/CEO only when items exist
    (a lead-stage draft is the rep's private deal).
  - `submit`: 400 when itemCount == 0 (price-request flow needs ≥1 product line).
  - `editItems`: DRAFT added to the sales-owner-editable statuses (items arrive later).
  - Deal pipeline section: `updateStage` (gates per TARGET stage: sales stages → deal owner /
    sales_manager / ceo; DEPOSIT_RECEIVED+CLOSED_PAID → account/ceo; PROCUREMENT → import/ceo;
    409 while lost; backward needs a note), `markLost`/`reopenDeal` (owner/sales_manager/ceo;
    stage preserved, reopen resumes in place), private `autoAdvanceStage` (monotonic
    forward-only, no-op while lost) called from confirmCustomer→ORDER_RECEIVED,
    confirmDepositPaid→DEPOSIT_RECEIVED, issueImportRequest→PROCUREMENT,
    confirmFinalPayment→CLOSED_PAID. markIrSent/markShipping/markGoodsReceived deliberately
    write NO stage — they live inside PROCUREMENT.
- `.../ticket/TicketController.java` — POST /{id}/stage, /{id}/lost, /{id}/reopen.
- Tests: `TicketServiceTest` (+16 deal-pipeline/create tests, stubDeal helper, DTO arity),
  `DepositNoticeServiceTest`/`AttachmentControllerTest`/`QuotationRendererTest`/
  `PriceCalcServiceTest` (DTO arity only).

## Important context
- **sales_manager invariant (handoff 58) refined**: still read+comment-only on OPERATIONAL
  ticket actions; the stage/lost/reopen trio is the deliberate user-approved exception (the
  pipeline is exactly this role's follow-up job). Covered by
  `salesManager_stageWriteIsTheOnlyTicketWritePower`.
- Auto stage events are distinguishable in the timeline by message "อัตโนมัติจากขั้นตอนของดีล";
  manual ones carry the user's note (or null).
- Lost is orthogonal to stage; PROJECT_ON_HOLD implies reopen-in-place.
- Backfill maps legacy tickets: FULLY_PAID/closed→CLOSED_PAID, fulfillment→PROCUREMENT,
  deposit-paid→DEPOSIT_RECEIVED, confirmed/notice→ORDER_RECEIVED, quotation/document_issued→
  QUOTE_BUYER, else QUOTE_DESIGN_SIDE.
- V48/V49 were taken by ticket work merged 2026-07-16→17; this branch is **V50**.

## Commands run
- `cd backend && ./mvnw -B clean verify`

## Tests / build results
- **BUILD SUCCESS**. 481 tests, 0 failures, 0 errors, 0 skipped.
- Integration tests RAN via Testcontainers (not skipped): V50 applied cleanly to a real empty
  Postgres including backfill; `TicketRepositoryIntegrationTest` 9/9 against the new columns.

## Known risks
1. `CreateTicketRequest.projectId` is now @NotNull — API consumers creating tickets without a
   project get 400. Mock + create modal mirror this on the UI branch.
2. Lightweight drafts don't notify import/CEO; the notification moment is submit(). If a rep
   never submits, the deal is invisible to import — by design (lead-stage deals are sales work).
3. Existing tickets backfilled to a best-guess stage; reps can correct backward with a note.
4. Demo-seed stages for showcase data: optional follow-up (demo dir shares Flyway's version
   space — check before numbering).

## Next prompt
UI branch `feat/project-sales-pipeline-ui` (handoff 66): one งานขาย page — deal pipeline list +
deal detail with stage-gated doc actions; mock parity for stage fields/endpoints/lightweight
create. Merge this backend branch FIRST.
