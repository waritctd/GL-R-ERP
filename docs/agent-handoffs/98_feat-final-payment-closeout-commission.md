# Handoff: Step 9 (final) — Final Payment Closeout & Commission Gate

Date: 2026-07-21
Branch: `feat/final-payment-closeout-commission`, stacked on `origin/main` tip `fada2b5`
(Steps 1-8 merged: PricingRequest → FactoryQuote/Costing → CEO Decision → Customer Quotation →
Customer Outcome/Revisions → Deposit/Order Confirmation → Factory PO → Delivery/Inventory)
Primary migration: `V79__commission_deal_linkage.sql`

This is the final step (9 of 9) of the sales pricing-chain redesign.

## Task

Two things, both scoped to the sales pricing/deal workflow (business-logic changes explicitly
authorized under CLAUDE.md's "Sales flow redesign" exception):

1. Confirm final payment / `CLOSED_PAID` already works correctly for chain deals (it did — no
   bridge needed, see "What was already correct" below) — and prove it with a real integration
   test, since that exact end-to-end path had never been tested before.
2. Add a **gate + cross-check** around commission submission: when a commission names a
   `sourceTicketId`, the linked deal must have reached `DealStage.CLOSED_PAID` before the
   commission can be submitted (hard gate, 422 otherwise), and a hand-typed `grossAmount` that
   diverges from the deal's actual `payableAmount` by more than 5% is flagged for reviewers — never
   blocked. `sourceTicketId` stays optional; unlinked/manual commissions are unaffected.

Commission **rate math itself** (`CommissionCalculator`, tier table) was explicitly out of scope
and was not touched.

## What was already correct (verified, not re-derived)

- `TicketRepository.payableAmount(ticketId)` (backend/src/main/java/th/co/glr/hr/ticket/TicketRepository.java:1037-1075)
  already prefers the chain-derived `sales.quotation.total_amount` over the legacy `ticket_item`
  fallback via a `COALESCE` chain.
- `TicketService.maybeAdvanceClosedPaid` (TicketService.java:1725-1728, called from
  `confirmFinalPayment`/`reconcilePaymentStatus`/`completeDelivery`'s inline recheck) auto-advances
  `DealStage.CLOSED_PAID` once `paymentFullyPaid && FulfilmentStatus.FULLY_DELIVERED` — both gates
  required, either order. Neither of these was modified.

## Decisions Made

- **Cross-check threshold: 5%**, matching the number given in the task. Implemented as
  `|grossAmount - payable| / |payable| > 0.05`, and separately `payable == 0 && grossAmount > 0` →
  mismatch (avoids a division by zero and still flags an obviously-wrong invoice against a
  zero-payable deal). See `CommissionService.isMismatch` (CommissionService.java:~150-158).
- **Gate placement**: the whole gate/cross-check (`resolveDealLinkage`) runs *before* any DB write
  in `submit()` — invoice row, attachment, and commission row are all created only after the gate
  passes. Confirmed by the "ticket not found" and "not yet CLOSED_PAID" tests asserting
  `commissions.createInvoice` is never called (unit level) and the `commission_record` row count is
  unchanged (integration level).
- **Lean `TicketRepository.findSalesStage(long)`** was added rather than reusing `findById`/`get` —
  the task asked to check for a cheap existing accessor first; none existed (only the full
  `TicketSummaryDto` load via `findSummaryById`), so a one-column `SELECT sales_stage ...` was added
  instead of pulling a full ticket detail just to read one field.
- **`createClawback` was left untouched** — it inserts a new `commission_record` row too, but Step 9
  only specified `submit()`; the two new columns default to `NULL`/`FALSE` on that INSERT (no column
  list change needed) since a clawback isn't itself a fresh "submission against a deal."
- **Frontend "Linked Deal" scoping**: no manual role-based filtering was added in
  `CommissionPage.jsx` beyond calling `api.tickets.list({ salesStage: 'CLOSED_PAID' })` — both the
  real `TicketService.listPage` and the mock mirror already scope to `createdByFilter` when
  `role === 'sales'` and show every rep's tickets otherwise, so the existing endpoint scoping does
  the "own tickets only for sales, all reps' for manager/CEO" split without any new frontend logic.

## Files Changed

**Backend — schema**
- `backend/src/main/resources/db/migration/V79__commission_deal_linkage.sql` (new) — adds
  `sales.commission_record.deal_payable_amount_snapshot NUMERIC(14,2)` (nullable) and
  `deal_amount_mismatch BOOLEAN NOT NULL DEFAULT FALSE`. Verified V79 free via `ls` on this branch
  and every other open worktree (`flyway-audit`, `deposit-order`, `inventory-delivery`,
  `nav-menu-grouping`, `procurement-order`, `quotation-outcome`, `profile-avatar-menu` — all top
  out at ≤V78) immediately before writing and again immediately before this handoff.

**Backend — gate + cross-check**
- `backend/src/main/java/th/co/glr/hr/commission/CommissionService.java` — injected
  `TicketRepository`; added `resolveDealLinkage(SubmitCommissionRequest)` (404 if ticket missing,
  422 if not `CLOSED_PAID`, else computes payable snapshot + mismatch flag) and `isMismatch(...)`;
  `submit()` now calls it and threads the two values into `createCommissionRecord`.
- `backend/src/main/java/th/co/glr/hr/commission/CommissionRepository.java` — SELECT now returns
  `deal_payable_amount_snapshot`/`deal_amount_mismatch`; new 8-arg `createCommissionRecord(...)`
  overload persists them (old 6-arg overload delegates with `null, false` — nothing else calling it
  needed updating); `mapRecord` reads both columns.
- `backend/src/main/java/th/co/glr/hr/commission/CommissionRecord.java` — added
  `dealPayableAmountSnapshot`/`dealAmountMismatch` fields (auto-serialized to JSON via
  `CommissionResponses` — no controller DTO change needed since it wraps the record directly).

**Backend — ticket lookup + list filter**
- `backend/src/main/java/th/co/glr/hr/ticket/TicketRepository.java` — new
  `findSalesStage(long ticketId): Optional<String>` (empty ⇔ ticket not found; `sales_stage` is
  `NOT NULL` per V50, so a present row always yields a non-empty stage); `findSummaries`/
  `countSummaries` gained an additive `salesStage` filter parameter (old 2/3-arg overloads kept for
  source compatibility — `TicketRepositoryIntegrationTest`/`TicketServiceTest` call them unchanged).
- `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java` — `listPage` gained an overload
  taking `salesStage`; the existing 3-arg `listPage(status, actor, page)` delegates with `null`.
- `backend/src/main/java/th/co/glr/hr/ticket/TicketController.java` — `GET /api/tickets` gained an
  optional `salesStage` query param, additive to `status`. **API contract addition, stated
  explicitly per CLAUDE.md**: purely additive (new optional param, old callers unaffected), used by
  the commission "Linked Deal" picker to request `GET /api/tickets?salesStage=CLOSED_PAID`.

**Backend — tests**
- `backend/src/test/java/th/co/glr/hr/commission/CommissionServiceTest.java` (Mockito unit test,
  pre-existing) — updated for the new constructor/record shape; added 5 new unit tests covering the
  gate decision itself (ticket not found → 404, not CLOSED_PAID → 422, within/beyond threshold →
  mismatch flag correct, no `sourceTicketId` → never touches `TicketRepository` at all — regression
  guard).
- `backend/src/test/java/th/co/glr/hr/commission/CommissionDealLinkageIntegrationTest.java` (new,
  `AbstractPostgresIntegrationTest`) — real-DB integration coverage; see "Tests" below.

**Frontend**
- `frontend/src/api/mockApi.js` — `commissions.create` now mirrors the gate + cross-check exactly
  (same 404/422 shape, same 5% threshold via new `commissionDealMismatch()`, reusing the existing
  `payableAmount(ticket)` mock helper rather than re-deriving the COALESCE chain); `tickets.list`
  gained the same additive `salesStage` filter; `buildCommissionRecord` defaults the two new fields.
- `frontend/src/features/commissions/CommissionPage.jsx` — new "ดีลที่เกี่ยวข้อง (Linked Deal)"
  `<select>` in the submit form, sourced from `api.tickets.list({ salesStage: 'CLOSED_PAID' })`,
  optional (defaults to unlinked/manual, unchanged submit behavior); shows the linked ticket's
  code/customer once selected; `payloadFromForm()` now sends `sourceTicketId`; a
  `StatusBadge tone="warning"` ("ยอดต่างจากยอดที่เรียกเก็บ") is shown next to the amount in both the
  desktop table and the mobile card when `record.dealAmountMismatch`, and again inside the approve
  confirmation dialog so a reviewer sees it before signing off.

## Commands Run

```bash
# Backend
cd backend
./mvnw -q -B -o compile
./mvnw -q -B -o test-compile
./mvnw -B -o test -Dtest=CommissionServiceTest,CommissionDealLinkageIntegrationTest -DfailIfNoTests=false
./mvnw -B -o clean verify        # full suite, run twice (once before, once after the mutation-check revert)

# Frontend
cd frontend
npm ci                            # node_modules was absent in this worktree
npm run lint
npm test
npm run build
```

## Test / Build Results

- **Backend full suite**: `./mvnw -B -o clean verify` → **BUILD SUCCESS**, **995 tests, 0
  failures, 0 errors, 0 skipped**, jacoco coverage gate passed. Docker was up
  (`docker info` succeeded) and no `TEST_DB_URL` was set, so every `AbstractPostgresIntegrationTest`
  ran against a real Testcontainers Postgres 16 — **integration tests genuinely ran**, none were
  skipped.
- **New tests specifically**: `CommissionDealLinkageIntegrationTest` — 5/5 pass (real Postgres).
  `CommissionServiceTest` — 12/12 pass (Mockito unit, includes the 5 new gate-decision cases).
- **Frontend**: `npm run lint` → 0 errors (2 pre-existing `react-hooks/exhaustive-deps` warnings on
  unrelated files + 1 new one on `CommissionPage.jsx`'s existing `load()` effect, same pattern
  already accepted elsewhere in this codebase, e.g. `PayrollPage.jsx`). `npm test` → **400/400
  pass** (47 files). `npm run build` → succeeds, `CommissionPage-*.js` chunk 21.48 kB.

## Mutation-Check Evidence (CLOSED_PAID gate)

Per CLAUDE.md's "permission changes must ship evidence" pattern, applied here to the business-state
gate since it's the one thing this step newly blocks on:

1. Temporarily changed `CommissionService.resolveDealLinkage`'s guard from
   `if (!DealStage.CLOSED_PAID.equals(salesStage))` to `if (false && !DealStage.CLOSED_PAID.equals(salesStage))`
   (gate effectively disabled).
2. Ran `CommissionServiceTest` + `CommissionDealLinkageIntegrationTest` (17 tests total):
   **exactly 2 went red** —
   `CommissionServiceTest.submitWithLinkedTicketNotClosedPaid_rejectsWithUnprocessableEntity` and
   `CommissionDealLinkageIntegrationTest.submit_rejectsWhenLinkedDealHasNotReachedClosedPaid_andCreatesNoRecord`
   — nothing else. (`ERROR] Tests run: 17, Failures: 2, Errors: 0`.)
3. Reverted to the original guard (verified via `git diff --stat` showing only the intended net
   change, no leftover `MUTATION-CHECK`/`false &&` text). Re-ran the same 17 tests: **all green
   again** (`Tests run: 17, Failures: 0, Errors: 0`). Re-ran the full `clean verify` after the
   revert: still BUILD SUCCESS / 995/995.

This is not an authorization/role-gate change in CLAUDE.md's authz sense (see below), but it is a
money-adjacent business-state gate this session was asked to prove rigorously, so the same
mutation-check discipline was applied to it.

## Authz Evidence

**No authorization/role change in this task.** `CommissionController`'s existing `@PreAuthorize`
role gates (`SALES`/`SALES_MANAGER`/`CEO` for submit) are untouched. The new `TicketController`
`salesStage` query param does not change who may call `GET /api/tickets` or what rows they see —
it only narrows an already-scoped result set further (the existing `createdByFilter` scoping for
`sales` role still applies identically). What *is* new is a **business-state gate** (deal must be
`CLOSED_PAID`) and a **non-blocking data-quality flag** (amount mismatch) — neither is a
permission/scope decision in the CLAUDE.md "who may read or write whose rows" sense, so the
authz-evidence requirement (real-DB integration test through the real service) does not strictly
apply, but the same rigor (mutation-check, wrong-way-round test cases) was applied anyway since it
gates a financial action. See "Mutation-Check Evidence" above.

## Known Risks / Assumptions

- **`AuditService`/`NotificationService` are mocked in `CommissionDealLinkageIntegrationTest`**,
  deliberately — they're side effects of an already-decided business action (per
  `NotificationService`'s own class comment), not the SQL/gate behavior under test. Every
  collaborator that participates in the gate decision or its persistence (`TicketRepository`,
  `CommissionRepository`, `CommissionCalculator`) is real, hitting real Postgres.
- **The 5% threshold is a hand-picked business number** taken directly from the task description,
  not derived from any existing config. If GL&R wants this configurable later (e.g. via CEO
  Settings), it currently lives as a `private static final BigDecimal MISMATCH_THRESHOLD` constant
  in `CommissionService` — an easy follow-up, not done here since it wasn't asked for.
- **`createClawback` does not carry forward the original commission's snapshot/mismatch** onto the
  new clawback row (both default `NULL`/`FALSE`) — a deliberate smallest-reasonable-call per the
  task's own "make the smallest reasonable call" instruction, since Step 9 only specified gating
  `submit()`. If clawback rows should inherit the original's snapshot for audit continuity, that's
  a follow-up.
- **No uniqueness constraint stops the same `CLOSED_PAID` ticket from being linked to more than one
  commission submission.** Not asked for, and plausible legitimate cases exist (e.g. a corrected
  resubmission after a rejected commission) — flagged here in case GL&R wants a hard 1:1 rule later.
- **The "Linked Deal" picker's eligible-tickets list is fetched once per mount** (`useEffect` keyed
  on `canSubmit`), not re-fetched after a successful submission. A newly-`CLOSED_PAID` deal that
  becomes eligible mid-session won't appear until the page reloads. Minor UX gap, not a correctness
  issue (the backend gate is authoritative regardless of what the picker shows).

## Recommended Next Agent

None required for Step 9 itself — implementation, tests, and verification are complete. The
pricing-chain redesign (Steps 1-9) is now feature-complete on this branch stack. Next is **full
pricing-chain UAT and a deploy decision**, which needs the orchestrating/human session, not another
implementation agent.

## Exact Next Prompt

```
Steps 1-9 of the sales pricing-chain redesign are all implemented and individually verified
(backend `./mvnw clean verify` green with real-DB integration tests run, frontend
lint/test/build green, on each step's own branch). Step 9
(feat/final-payment-closeout-commission, this handoff, docs/agent-handoffs/98_*.md) is the last
one — there is no Step 10.

What's needed now is END-TO-END UAT across the full chain on a single merged branch/environment,
not another implementation step:

1. Merge Steps 1-9 in order onto a single integration branch (or verify they're already stacked
   cleanly — check `git log` on feat/final-payment-closeout-commission back to main for the
   Steps 1-8 merge commits already present at fada2b5).
2. Run the full backend suite (`./mvnw clean verify`) and frontend suite
   (`npm run lint && npm test && npm run build`) on that merged branch — confirm nothing broke
   in combination that passed individually.
3. Walk one deal through the ENTIRE chain manually (or via a new end-to-end integration test if
   one doesn't already exist spanning all 9 steps) — PricingRequest creation → factory quote →
   CEO costing decision → customer quotation → customer acceptance → deposit/order confirmation
   → factory PO → delivery → final payment (CLOSED_PAID) → commission submission linked to that
   deal — and confirm every stage transition, document generation, and notification fires
   correctly in sequence.
4. Decide and record (in a new handoff or in 00_MASTER_CONTEXT.md) whether this is ready to
   deploy to UAT/prod, or what's still blocking. Remember Step 1's own note: the chain is "not
   independently deployable" mid-way — confirm the FULL chain (not a partial slice) is what
   actually reaches whatever environment this targets.
5. If UAT surfaces bugs, they get their own scoped branches/handoffs per the normal process —
   don't patch them directly on a Step's already-closed branch.
```
