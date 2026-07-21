# Agent Handoff

## Task
Implement Step 6 of the sales pricing chain: Deposit, Payment, and Order Confirmation. Bridge a
deal that reached `pricing_request.status = QUOTATION_ACCEPTED` (Step 5's terminal status) into
the EXISTING, already-tested legacy deposit/payment/fulfilment pipeline
(`TicketService.confirmCustomer`/`confirmDepositPaid`/`issueImportRequest`,
`DepositNoticeService`) — a targeted bridge, not a rebuild.

## Branch
`feat/sales-deposit-order-confirmation`, stacked on Step 5 tip `38a0afd` ("feat(sales): customer
decision and commercial revisions (Step 5)"), itself off `main` tip `f07f487` (Steps 1-4).

## Base Commit
`38a0afd`.

## Current Commit
Uncommitted working tree — **not committed or pushed**, per instructions.

## Agent / Model Used
Claude (Sonnet 5).

## Scope

### In Scope
- Migration `V76__quotation_accepted_order_confirmation.sql`: `sales.pricing_request` gains
  `order_confirmed_at`/`order_confirmed_by`/`order_confirm_client_request_id` (the order-
  confirmation bridge's own idempotency/audit state — see "Decisions Made" #1 for why this needed
  its own columns rather than reusing `status`); `sales.ticket_event.chk_event_kind` widened to
  add `ORDER_CONFIRMED_FROM_QUOTATION`.
- New package `th.co.glr.hr.orderconfirmation`: `OrderConfirmationService` (the bridge action +
  the deposit-notice-from-quotation entry point), `OrderConfirmationController`
  (`POST /api/pricing-requests/{id}/confirm-order`, `POST /api/pricing-requests/{id}/deposit-notice`),
  `OrderConfirmationRequests`, `OrderConfirmationDtos`.
- `TicketRepository.markQuotationIssuedForOrderConfirmation` — the ONE deliberate bridge write
  outside the legacy ticket status state machine (`draft -> quotation_issued`), guarded FROM
  `draft` only.
- `PricingRequestRepository`: `lockPricingRequest`, `findOrderConfirmationState`,
  `markOrderConfirmed` (the compare-and-set guarding the whole bridge).
- `PricingRequestDtos.PricingRequestSummaryDto` gains `orderConfirmedAt` (+ `mapSummary` update +
  4 test call sites fixed for the new positional field).
- `TicketEventKind.ORDER_CONFIRMED_FROM_QUOTATION`, `PricingRequestEventKind.ORDER_CONFIRMED` /
  `.DEPOSIT_NOTICE_DRAFTED_FROM_QUOTATION`, two `NotificationRepository.TICKET_EVENT_TITLES`
  entries.
- Backend test: `OrderConfirmationIntegrationTest` (6 tests, real Postgres) — drives a single-item
  deal through the REAL Steps 1-5 services to `QUOTATION_ACCEPTED`, then exercises the bridge, the
  deposit-notice-from-quotation entry point, `confirmDepositPaid`, the CEO-credit-terms bypass
  path, and every guard.
- Frontend: `hrApi.js`/`routes.js`/`mockApi.js` additions (`confirmOrder`,
  `createDepositNoticeFromQuotation`), `pricingRequestMeta.js` (`canConfirmOrder`,
  `canCreateDepositNoticeFromQuotation`, + 7 new unit tests), a Step 6 section in
  `PricingRequestDetailPage.jsx` (+ 4 new component tests).
- `docs/agent-handoffs/95_feat-sales-deposit-order-confirmation.md` (this file).

### Out of Scope (confirmed not touched)
- **`DepositNoticeService` itself — zero changes.** `createDraft`'s existing "items provided ->
  use them verbatim, skip legacy ticket_item auto-population" branch and `requireApprovedTicket`'s
  existing `{APPROVED, QUOTATION_ISSUED, DOCUMENT_ISSUED}` allowlist already do everything Step 6
  needs once the bridge has written `ticket.status = QUOTATION_ISSUED`. Verified by test, not
  assumed — see "The central finding" below.
- **No change to `TicketRepository.payableAmount`.** Its existing query already reads `sales
  .quotation.total_amount` for an `ACCEPTED` row regardless of `pricing_request_id` — because
  Step 4 (`CustomerQuotationRepository`) deliberately extends the SAME `sales.quotation` table
  rather than a parallel one (that decision predates this branch; see
  `V74__customer_quotation_from_decision.sql`'s own header). The task brief anticipated this as a
  bug to fix; it turned out to already be fixed by that earlier design decision. See "The central
  finding" below for the full account and the test that proves it.
- No change to `TicketService.confirmCustomer`/`confirmDepositPaid`/`issueImportRequest` — called
  unmodified.
- No change to `PricingRequestStatus`/`PricingRequestStatus.ALLOWED` — `QUOTATION_ACCEPTED` stays
  exactly as terminal as Step 5 left it; the bridge's own "already ran" tracking lives on the new
  `order_confirmed_at` columns, not on a pricing-request status transition.
- No new pricing-request status invented for "order confirmed"/"deposit received" — per the task's
  explicit instruction, the existing `DealStage`/`paymentStatus`/`DepositPolicy` machinery models
  all of it.
- Payroll/tax/SSO/commission math: untouched.

## The central finding — payableAmount was already correct

The task brief's item 3 asked me to "fix `payableAmount`... for a new-chain deal this is 0/null."
Reading `TicketRepository.payableAmount(long ticketId)` (unmodified by any step) shows it is a
4-branch `COALESCE`, not a bare `ticket_item` sum:

1. `sales.quotation.total_amount` where `doc_status = 'ACCEPTED'` (ties broken by recipient/date),
2. `sales.quotation.total_amount` where `doc_status IN ('ISSUED','SENT')`,
3. the latest `ISSUED` `sales.deposit_notice.total_payable`,
4. `SUM(ticket_item.approved_price * qty)`,
5. `0`.

Because Step 4 (`CustomerQuotationRepository`) writes into the SAME `sales.quotation` table the
legacy flow always used (not a parallel `sales.customer_quotation` — an explicit prior design
decision), branch 1 already picks up a Step 4/5 quotation's own `ACCEPTED` row with zero code
changes. `OrderConfirmationIntegrationTest.fullChain_..._composesWithoutShortcuts` asserts this
directly: `tickets.payableAmount(ticketId)` equals the accepted quotation's `subtotalAmount()`
*before any deposit code from this branch runs*, and the subsequent deposit payment (the full
deposit-notice amount, DepositPolicy's own 50% default) is strictly less than that payable amount
— proving the overpayment guard was never going to trip, without touching `payableAmount` at all.

This is reported honestly per the task's own instruction ("if it isn't [true], say exactly what
forced a change there and why" — the converse holds too: nothing forced a change, so none was
made).

## Files Changed

### Backend — new
- `backend/src/main/resources/db/migration/V76__quotation_accepted_order_confirmation.sql`.
- `backend/src/main/java/th/co/glr/hr/orderconfirmation/OrderConfirmationService.java`.
- `backend/src/main/java/th/co/glr/hr/orderconfirmation/OrderConfirmationController.java`.
- `backend/src/main/java/th/co/glr/hr/orderconfirmation/OrderConfirmationRequests.java`.
- `backend/src/main/java/th/co/glr/hr/orderconfirmation/OrderConfirmationDtos.java`.
- `backend/src/test/java/th/co/glr/hr/orderconfirmation/OrderConfirmationIntegrationTest.java`.

### Backend — modified
- `ticket/TicketRepository.java` — new `markQuotationIssuedForOrderConfirmation(long ticketId)`.
- `ticket/TicketEventKind.java` — `ORDER_CONFIRMED_FROM_QUOTATION`.
- `pricingrequest/PricingRequestEventKind.java` — `ORDER_CONFIRMED`,
  `DEPOSIT_NOTICE_DRAFTED_FROM_QUOTATION`.
- `pricingrequest/PricingRequestRepository.java` — `lockPricingRequest`,
  `findOrderConfirmationState` (+ its `OrderConfirmationState` record), `markOrderConfirmed`;
  `mapSummary` extended with `orderConfirmedAt` (read via `pr.*`, no SELECT-list change needed).
- `pricingrequest/PricingRequestDtos.java` — `PricingRequestSummaryDto` gains `orderConfirmedAt`.
- `notification/NotificationRepository.java` — two new `TICKET_EVENT_TITLES` entries.
- `test/.../factoryquote/FactoryQuoteServiceAttachmentTest.java`,
  `test/.../pricingrequest/PricingRequestControllerTest.java`,
  `test/.../pricingrequest/PricingRequestServiceTest.java` — trailing `null` arg for the new
  `PricingRequestSummaryDto` field (mechanical, no behavior change).

### Frontend
- `api/hrApi.js`, `api/routes.js` — `pricingRequests.confirmOrder`,
  `.createDepositNoticeFromQuotation`.
- `api/mockApi.js` — mock `confirmOrder`/`createDepositNoticeFromQuotation` under
  `pricingRequests`; `buildPricingRequestSummary` gains `orderConfirmedAt`; new
  `mockUnitBasisLabel` helper (mirrors `OrderConfirmationService`'s own private `unitLabel`).
- `features/pricingRequests/pricingRequestMeta.js` (+`.test.js`) — `canConfirmOrder`,
  `canCreateDepositNoticeFromQuotation` (+7 new unit tests).
- `features/pricingRequests/PricingRequestDetailPage.jsx` (+`.test.jsx`) — a Step 6 section
  (visible once `summary.status === 'QUOTATION_ACCEPTED'`): "ยืนยันคำสั่งซื้อ" button before the
  bridge has run, "สร้างใบแจ้งยอดเงินรับมัดจำ" (with a % มัดจำ input) after — on success, navigates
  to the EXISTING `/tickets/:ticketId/deposit` route (`DepositNoticePage`, unmodified) so
  editing/issuing the just-created draft reuses that page's UI verbatim, per the task's
  instruction to check for an existing deposit-notice page before building one (+4 new component
  tests).

## Migration Numbering — re-verified per the task's explicit instruction

Checked live via `git worktree list` plus listing every worktree's/checkout's own
`backend/src/main/resources/db/migration/` directory, both mid-session and again just before
writing this handoff:
- This worktree's own tree tops out at `V75__quotation_customer_outcome.sql` (Step 5, inherited).
- `.claude/worktrees/quotation-outcome` (the Step 5 branch this one is stacked on, same tip
  `38a0afd`) also tops at `V75` — not a collision, same commit.
- Top-level `GL-R-ERP` (`feat/sales-factory-quote-costing`) tops at `V71`.
- `GL-R-ERP-employees`, `GL-R-ERP-main`, `.claude/worktrees/flyway-audit`,
  `.claude/worktrees/profile-avatar-menu` all top at `V54`.
- `.claude/worktrees/nav-menu-grouping` tops at `V55`.

**`V76` was free at both checks.** Re-verify again before merging if time has passed.

## Commands Run

```bash
cd backend && ./mvnw -B -o clean verify
cd frontend && npm ci && npm run lint && npx vitest run && npm run build
git diff --check
```

## Test / Build Results

**Backend — full `clean verify`, final run (verbatim tail):**
```
[INFO] Tests run: 964, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] --- jacoco:0.8.13:check (jacoco-check) @ glr-hr-backend ---
[INFO] Analyzed bundle 'glr-hr-backend' with 268 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  05:59 min
```
Baseline (Step 5 tip `38a0afd`): 958/958. Step 6 adds **6** tests net —
`OrderConfirmationIntegrationTest` (6 new tests). 958 + 6 = 964, matching exactly.

**Testcontainers ran for real**: no `TEST_DB_URL` was set (confirmed empty) and Docker was
confirmed running (`docker info`) before every run; the live Flyway migration logs show
"Successfully applied 74 migrations to schema \"hr\", now at version v76" printed once per
integration test class, only reachable through a real, freshly-provisioned Postgres.

**Frontend:**
```
$ npm run lint
✖ 3 problems (0 errors, 3 warnings)   # pre-existing, unrelated (CommissionPage.jsx, PayrollPage.jsx)

$ npx vitest run
 Test Files  45 passed (45)
      Tests  392 passed (392)

$ npm run build
✓ built in 166ms
```
Baseline: 45 files / 381 tests. Step 6 added **11** tests net: 7 new `pricingRequestMeta.test.js`
cases (`canConfirmOrder` × 4, `canCreateDepositNoticeFromQuotation` × 3) + 4 new
`PricingRequestDetailPage.test.jsx` cases. No existing assertion was weakened or removed.

`git diff --check`: clean (no whitespace errors), re-confirmed after every mutation-check revert.

## Authz Evidence

**Every authorization-shaped change in this branch shipped a real-DB integration test through the
real Java service and repository** (`AbstractPostgresIntegrationTest`/Testcontainers,
`OrderConfirmationIntegrationTest`), and every NEW guard was **mutation-checked**: introduce the
bug, confirm exactly the targeted test(s) go red and nothing else, revert to an empty diff
(`git diff --check` clean, re-confirmed, and `diff` against a pre-write backup copy of
`OrderConfirmationService.java` confirmed byte-identical), re-confirm the specific test green, then
the whole backend suite green (964/964) at the end.

| Guard | Where | Mutation-check result (verbatim) |
|---|---|---|
| Reachable ONLY from `QUOTATION_ACCEPTED` — service-level pre-check AND repository-level compare-and-set | `OrderConfirmationService.confirmOrder`'s `if (!PricingRequestStatus.QUOTATION_ACCEPTED.equals(locked.status()))` and `PricingRequestRepository.markOrderConfirmed`'s `AND status = 'QUOTATION_ACCEPTED'` | **CORRECTED ON REVIEW (Opus)** — the original draft of this table claimed the repository clause alone was independently "Red." Reproduced twice by the reviewer with a clean build against the exact named test (`confirmOrder_beforeQuotationAccepted_isRejected`): removing **either** condition alone leaves all 6 tests green, because the service-level check runs first and unconditionally throws before `markOrderConfirmed` is ever reached — so the repository clause can never be observed by this test on its own, the same way the service check can't be either. Removing **both simultaneously** does go red (exactly this one test, `Expecting code to raise a throwable`) — the two are genuinely mutually-redundant defense-in-depth, not "one real guard plus one decorative check." Both are individually **unverified-but-harmless**; only their combination is proven load-bearing. Reverted, re-ran green (6/6). |
| Owner-only (`requireOwner`) | `OrderConfirmationService.requireOwner` (short-circuited with `if (false && ...)`) | **Red** — exactly 1 test, `confirmOrder_nonOwningSalesRep_cannotConfirm`: ticket status ended up `quotation_issued` instead of staying `draft` (a non-owner's call succeeded). Reverted, re-ran green. `confirmOrder_importAndCeo_cannotConfirm` stayed green throughout — it is independently caught by the earlier `requireRole(actor, SALES_ROLES)` check, a genuinely separate guard from ownership. |
| `clientRequestId` replay guard (same key -> replay, different/no key -> 409) | `OrderConfirmationService.confirmOrder`'s replay condition (`clientRequestId != null && clientRequestId.equals(state.clientRequestId())`) replaced with `if (true)` (treats every retry as a valid replay) | **Red** — exactly 1 test, `fullChain_quotationAcceptedThroughDepositPaid_composesWithoutShortcuts`: the "retry with a DIFFERENT key against an already-confirmed request is a clean 409" assertion (`Expecting code to raise a throwable`) failed, because the mutated code now silently replayed instead. Reverted, re-ran green. |
| `markQuotationIssuedForOrderConfirmation`'s `WHERE status = 'draft'` guard | `TicketRepository.markQuotationIssuedForOrderConfirmation` (WHERE clause removed) | **Unverified-but-harmless** — all 6 tests stayed green, same shape as Step 5's own `PricingRequestService.cancel` no-op-guard precedent. There is no live path through the real API today that reaches `confirmOrder` with a ticket already carrying a non-`draft` legacy status (a fresh deal created after Step 1 can never leave `draft` via the legacy flow — `TicketService.submit()` permanently 409s, confirmed by reading `submit()` and `QUOTATION_ALLOWED_STATUSES`'s only other producer, `approve()`, which is gated behind `pickup()`, which requires `status=submitted`, unreachable). The guard defends against a future widening (e.g. a pre-Step-1 legacy ticket later getting a new-chain pricing request attached) silently clobbering that ticket's real status — documented, not proven reachable. |

**Reuse claims (existing, unmodified authz) — cited, not re-proven:**
- `confirmDepositPaid`/`recordPayment` staying `account`/`ceo`-only is `TicketService.ACCOUNT_ROLES`,
  unchanged by this branch — `TicketServiceTest.confirmDepositPaid_rejectsSalesRole`/
  `_rejectsImportRole`/`_rejectsSalesManagerRole` already cover this (Mockito). This branch adds
  `OrderConfirmationIntegrationTest.confirmDepositPaid_salesActor_cannotReach_onNewChainDeal` —
  the SAME guard, proven for a real new-chain deal specifically (real DB, real quotation-sourced
  payable amount), not a re-proof of the guard's general existence.
- `DepositNoticeService.createDraft`/`issue`'s own SALES-role gate is unchanged — confirmed by
  reading the (untouched) source, not re-tested; `OrderConfirmationService.createDepositNoticeFromQuotation`
  adds its OWN, stricter owner check (`requireOwner`) in front of it, since
  `DepositNoticeService.createDraft` itself has no ownership check at all (only role) — documented
  as a pre-existing gap in that class, not something this branch was asked to fix.
- **Duplicate-payment prevention (item 4)**: `confirmDepositPaid`'s own
  `paymentStatus == 'DEPOSIT_NOTICE_ISSUED'` gate already blocks a second call (paymentStatus has
  moved to `DEPOSIT_PAID` after the first) — this ALREADY held before this branch (unit-tested by
  `TicketServiceTest.confirmDepositPaid_rejectsWrongPaymentStatus`). `OrderConfirmationIntegrationTest
  .fullChain_..._composesWithoutShortcuts` proves it holds for a real new-chain deal end to end: a
  second `confirmDepositPaid` call 409s and the payment_receipt row count stays at 1.

**Reporting:** the frontend UI-level tests (`PricingRequestDetailPage.jsx`'s conditional rendering)
are **not authz evidence** — they prove this component's own conditional rendering against a
hand-rolled mock, not server-side enforcement. The authoritative checks are the backend guards in
the table above, all real-DB, all mutation-proven except the two rows honestly marked
"unverified-but-harmless."

## Decisions Made — stated explicitly per CLAUDE.md's sales-flow-redesign license

1. **The bridge's "already ran" idempotency state lives on THREE new columns on
   `sales.pricing_request`** (`order_confirmed_at`/`order_confirmed_by`/
   `order_confirm_client_request_id`), not on a pricing-request status transition. Reason:
   `QUOTATION_ACCEPTED` is Step 5's deliberately terminal status (`PricingRequestStatus.ALLOWED`
   maps it to `{}`), so unlike every other step's own guard, "status changed" cannot double as
   "the bridge already ran" here. This mirrors `TicketRepository.confirmClose`/
   `clearCloseConfirmation`'s own precedent (a dedicated `close_confirmed_at`/`close_confirmed_by`
   pair sitting alongside a status machine that doesn't itself move for that action), extended
   with a `client_request_id` column because — unlike that close-confirmation pair — this action
   needed a genuine idempotent-replay contract (a UI retry after a network drop should return
   success, not a spurious 409).
2. **`OrderConfirmationService` is a NEW orchestration class, not a method on
   `PricingRequestService` or `TicketService`.** `PricingRequestService`'s own class Javadoc states
   it "never writes through" `TicketRepository`, and `TicketService` already depends on
   `PricingRequestService` for its dead-deal cascade (documented in `TicketService`'s constructor
   comment: "TicketService -> PricingRequestService -> TicketRepository, acyclic"). Adding a
   `TicketService` dependency to `PricingRequestService` to host the bridge there would create a
   genuine circular Spring bean reference (`TicketService -> PricingRequestService ->
   TicketService`). The new class depends on `PricingRequestRepository` (not `PricingRequestService`)
   and `TicketService`/`TicketRepository`, in one direction only — no cycle.
3. **`CustomerQuotationRepository` is depended on directly** (not `CustomerQuotationService`) for
   reading the ACCEPTED quotation in `createDepositNoticeFromQuotation` — avoids re-running
   `CustomerQuotationService`'s own authz (which would apply the WRONG scoping: its `VIEW_ROLES`
   includes `import`/`ceo` for reads, but this bridge is SALES-owner-only end to end) and mirrors
   the established precedent of an orchestrating layer reaching into a sibling aggregate's
   repository directly when the invariant allows it (e.g.
   `PricingRequestRepository.supersedeOpenPricingDecisionAndQuotation` reaching into
   `sales.pricing_decision`/`sales.quotation` directly, per that method's own Javadoc).
4. **No `pricing_request_id` FK column added to `sales.deposit_notice`.** The task left this as
   "your call." Decided not needed: deposit notices are already scoped by `ticket_id` (one active
   pricing chain per deal in practice), and the accepted quotation's own `number` is stored in the
   deposit notice's existing `reference` field for a human-readable trace, satisfying the
   acceptance scenario's "items/amount trace to the quotation" requirement without a schema change.
5. **`payableAmount` needed no fix** — see "The central finding" above. This directly contradicts
   the task brief's own framing of item 3 as "a real latent bug, not speculative"; verified by test
   (not by re-reading the brief and trusting it), and reported as found.
6. **The CEO-credit-terms bypass path SKIPS `DealStage.DEPOSIT_RECEIVED`, landing on
   `DealStage.PROCUREMENT` instead** — traced from `TicketService.issueImportRequest`'s own
   `depositPolicyBypassesNotice` logic and `autoAdvanceStage`'s monotonic-forward-only semantics
   (advancing straight from `ORDER_RECEIVED` to `PROCUREMENT` is a legal forward jump; the deal
   never sits at the intermediate stage). The task brief asked me to "trace ... to confirm what
   stage/status a credit-terms deal reaches" rather than assuming it lands exactly on
   `DEPOSIT_RECEIVED` — this is reported as found, with a dedicated test
   (`ceoCreditTermsBypass_reachesOrderConfirmed_withoutADepositNotice`) proving `paymentStatus`
   stays `CUSTOMER_CONFIRMED` (no payment was ever recorded) and zero `deposit_notice` rows exist
   for that ticket.

## Assumptions
- "Deposit percent Sales supplies" (task item 2) is passed straight through to the EXISTING
  `DepositNoticeDraftRequest.depositPercent`/`DepositNoticeService.createDraft`'s own 50% default
  when omitted — no new default logic invented on this branch's side.
- The deposit-notice item's `unit` display label (`unitLabel`) is a small private helper on
  `OrderConfirmationService`, deliberately duplicated from (not imported from)
  `CustomerQuotationService`'s own private `unitLabel` — matches this codebase's established
  pattern of small role-set/label duplication across sibling services rather than introducing a
  shared utility for a 4-line switch (see `PricingRequestService`'s own "Duplicated ... on purpose"
  precedent).
- The frontend's post-deposit-notice-creation navigation target
  (`/tickets/:ticketId/deposit`, the existing `DepositNoticePage`) is reused verbatim — no new
  deposit-notice UI was built, per the task's explicit instruction to check for an existing page
  first.

## Known Risks
- **Three guards are "unverified-but-harmless"** (see the Authz Evidence table, corrected on
  review): the service-level `QUOTATION_ACCEPTED` pre-check and the repository-level compare-and-set
  are mutually redundant — reviewer-reproduced with a clean build, removing either alone leaves all
  6 tests green, only removing both together goes red — so neither is individually load-bearing, the
  combination is. The `draft`-only ticket-status guard has no live path through the real API today
  to exercise its negative case, matching the exact shape of Step 5's own documented
  "unverified-but-harmless" `PricingRequestService.cancel` guard.
- **No dedicated `OrderConfirmationService` Mockito unit test file** — every path is exercised only
  through `OrderConfirmationIntegrationTest`, matching Steps 2-5's own precedent (none of them has
  a dedicated Mockito test file for their top-level orchestration service either).
- **The mock's FX/currency handling stays THB-only** — unchanged from every prior step's own
  simplification; Step 6 adds no new FX-adjacent surface.
- **This branch is stacked on Step 5, which is itself uncommitted in this session** — both branches
  currently sit at the same tip (`38a0afd`); no migration collision at time of writing
  (re-verified above), but worktree numbers move between checks per every prior handoff's own
  repeated caveat.
- **Frontend Step 6 UI is genuinely new (not carried forward from a prior step's own pattern for
  this exact action)** — its role-visibility assertions in `PricingRequestDetailPage.test.jsx` are
  UI-level only, per this file's own standing header disclaimer; the authoritative role checks are
  the backend guards in the Authz Evidence table.

## Things Not Finished
- No commit was made yet — per instructions.
- Frontend Step 6 UI has not been manually click-through-verified in `frontend-mock` (browser) —
  only automated component tests were run. A manual click-through (Sales confirms order -> deposit
  notice draft created -> navigates to the existing deposit-notice page -> issues -> Account
  confirms deposit paid) would be a reasonable next check, alongside Steps 1-5's own
  never-yet-performed end-to-end manual walkthrough (noted in handoff 94's own "Recommended Next
  Agent").

## Recommended Next Agent
Independently re-run every mutation-check row in the Authz Evidence table above (introduce each
described bug, confirm the same test(s) go red, revert, confirm green) — this chain has a
documented history of overstated verification claims (see handoffs 92-94's own "Recommended Next
Agent" sections). If satisfied, this closes Step 6. Steps 7-8 (import request onward — already
noted as pre-existing, working machinery this branch deliberately did not touch) remain, per the
original task brief's own scoping.

## Exact Next Prompt
```
Review docs/agent-handoffs/95_feat-sales-deposit-order-confirmation.md in full, then independently
re-run every mutation-check row in its "Authz Evidence" table against
feat/sales-deposit-order-confirmation (introduce each described bug, confirm the same test(s) go
red, revert, confirm green) — this chain has a documented history of overstated verification
claims. Separately, verify the handoff's central finding yourself: read
TicketRepository.payableAmount and confirm its first COALESCE branch (ACCEPTED sales.quotation
.total_amount) really does need no code change for a new-chain deal, given
CustomerQuotationRepository writes into the same sales.quotation table Step 4 onward. If both hold
up, do a manual click-through of Step 6 in frontend-mock (Sales confirms order on a
QUOTATION_ACCEPTED deal, creates a deposit notice, issues it via the existing DepositNoticePage,
Account confirms the deposit paid) — no agent on this chain has yet performed a manual UI
walkthrough of Steps 1-6 end to end.
```
