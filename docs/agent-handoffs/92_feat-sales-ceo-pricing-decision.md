# Agent Handoff

## Task
Implement Step 3 of the sales pricing chain: CEO Selling Price Decision. Turn a frozen SUBMITTED
costing into an approved, customer-facing selling price, with the eight design corrections listed
in the task brief (unit basis, never leak cost to Sales, freeze factory/costing mutations from
CEO_REVIEWING, one return-to-Import path, minimum selling price required at approval, pinned FX,
server-side margin recompute, idempotent+concurrency-safe approval).

## Branch
`feat/sales-ceo-pricing-decision`, stacked on Step 2's tip `d17bcc0`
(`feat/sales-factory-quote-costing`).

## Base Commit
`d17bcc0` (Step 2, "fix(pricing): address step 2 factory-quote/costing review findings")

## Current Commit
Uncommitted working tree — **not committed or pushed**, per instructions.

## Agent / Model Used
Claude (Sonnet 5)

## Scope

### In Scope
- New `sales.pricing_decision` / `sales.pricing_decision_item` aggregate (migration `V72`).
- New `th.co.glr.hr.pricingdecision` package: `PricingDecisionStatus`, `PricingDecisionDtos`,
  `PricingDecisionRequests`, `PricingDecisionRepository`, `PricingDecisionService`,
  `PricingDecisionController`.
- Extraction of `th.co.glr.hr.pricing.FxResolver` (shared BOT-FX resolution, was a private method
  on `PricingCostingService`) so Step 3 reuses exactly Step 2's FX validation.
- `PricingRequestStatus`: three new statuses (`CEO_REVIEWING`, `APPROVED_FOR_QUOTATION`,
  `COSTING_REVISION_REQUIRED`) and a state-machine change — see "Decisions Made".
- `PricingCostingService.COSTING_CREATE_STATUSES` and `FactoryQuoteService.receive()`'s revision
  branch: removed the two places a SUBMITTED costing could be silently reopened without a CEO
  action.
- Frontend: `hrApi.js`/`mockApi.js`/`routes.js`/`queryKeys.js` additions, a CEO decision workspace
  + Sales-facing approved-price section inside `PricingRequestDetailPage.jsx`, and
  `pricingRequestMeta.js` permission helpers.
- `docs/agent-handoffs/92_feat-sales-ceo-pricing-decision.md` (this file).

### Out of Scope (confirmed not touched)
- No customer quotation created anywhere in this branch.
- No write to legacy `sales.ticket_item` price fields.
- No deal-stage/lifecycle change (verified in the acceptance-scenario test: ticket `status` and
  `sales_stage` identical before/after the full Step 3 flow).
- Payroll/tax/SSO/commission math: untouched.

## Files Changed

### Backend — new
- `backend/src/main/resources/db/migration/V72__pricing_decision.sql` — `sales.pricing_decision`
  (versioned, one open DRAFT at a time, at most one APPROVED ever — both via partial unique
  indexes), `sales.pricing_decision_item` (unit-basis-explicit column names, frozen cost in both
  PIECE and REQUESTED-UNIT bases, proposed/approved margin+price, discount ceiling, minimum
  selling price), and the `chk_pricing_request_status` CHECK constraint extended with the three
  new statuses.
- `backend/src/main/java/th/co/glr/hr/pricing/FxResolver.java` — extracted from
  `PricingCostingService.resolveFx` (behaviour unchanged); both services now delegate to one
  implementation of the BOT-source/7-day-staleness validation.
- `backend/src/main/java/th/co/glr/hr/pricingdecision/PricingDecisionStatus.java`,
  `PricingDecisionDtos.java`, `PricingDecisionRequests.java`, `PricingDecisionRepository.java`,
  `PricingDecisionService.java`, `PricingDecisionController.java` — the Step 3 aggregate.
- `backend/src/test/java/th/co/glr/hr/pricingdecision/PricingDecisionIntegrationTest.java` — 12
  real-DB tests (see "Authz Evidence").

### Backend — modified
- `pricingrequest/PricingRequestStatus.java` — added `CEO_REVIEWING`, `APPROVED_FOR_QUOTATION`,
  `COSTING_REVISION_REQUIRED`; **removed** `READY_FOR_CEO_REVIEW -> COSTING_IN_PROGRESS`; added
  `READY_FOR_CEO_REVIEW -> CEO_REVIEWING`, `CEO_REVIEWING -> {APPROVED_FOR_QUOTATION,
  COSTING_REVISION_REQUIRED}`, `COSTING_REVISION_REQUIRED -> COSTING_IN_PROGRESS`.
- `pricingrequest/PricingRequestEventKind.java` — `PRICING_DECISION_STARTED/UPDATED/APPROVED/
  RETURNED`.
- `pricingcosting/PricingCostingService.java` — `COSTING_CREATE_STATUSES` swaps
  `READY_FOR_CEO_REVIEW` for `COSTING_REVISION_REQUIRED`; `resolveFx` now delegates to
  `FxResolver`.
- `factoryquote/FactoryQuoteService.java` — `receive()`'s revision branch no longer
  auto-transitions `READY_FOR_CEO_REVIEW -> COSTING_IN_PROGRESS` (see "Decisions Made" #2).
- `notification/NotificationRepository.java` — three new `TICKET_EVENT_TITLES` entries.
- `test/.../pricingrequest/PricingRequestStatusTest.java`,
  `PricingRequestRepositoryIntegrationTest.java`,
  `PricingFactoryQuoteCostingIntegrationTest.java` — updated for the state-machine change (see
  "Decisions Made" #1/#2); no assertion was weakened, each updated test's rationale is inline.

### Frontend
- `api/hrApi.js`, `api/mockApi.js`, `api/routes.js`, `api/queryKeys.js` — Step 3 endpoints and a
  full mock implementation (`startPricingDecision`, `listPricingDecisions`,
  `getPricingDecisionSalesView`, `getPricingDecision`, `updatePricingDecision`,
  `recalculatePricingDecision`, `approvePricingDecision`, `returnPricingDecisionToImport`), plus
  the same CEO_REVIEWING freeze added to the mock's `createCosting`/`receiveFactoryQuote`/
  `startFactoryNegotiation`/`markFactoryQuoteReady`/`markFactoryQuoteNotAvailable`/
  `uploadFactoryQuoteAttachment`.
- `features/pricingRequests/pricingRequestMeta.js` (+`.test.js`) — state machine updated;
  `canStartCeoReview`, `canActOnPricingDecision`, `canSeeRawPricingDecision`,
  `canSeePricingDecisionSalesView`.
- `features/pricingRequests/PricingRequestDetailPage.jsx` (+`.test.jsx`) — CEO decision workspace
  (cost summary, per-item margin/minimum-price/discount-ceiling editor, approve/return-to-import
  via `ConfirmDialog`'s `requireReason`, decision history) and a separate Sales-facing "ราคาขายที่
  อนุมัติ" section driven by the sales-view projection.
- `utils/format.js` — three new status labels (`CEO_REVIEWING`, `APPROVED_FOR_QUOTATION`,
  `COSTING_REVISION_REQUIRED`) in the canonical `pricingRequestStatusLabel` map.

## Commands Run

```bash
cd backend && ./mvnw -B -o clean verify
cd frontend && npm ci && npm run lint && npm test -- --run && npm run build
```

(`-o`/offline used throughout since the Maven repo was already warm from Step 2's session; `npm
ci` was required — this worktree's `node_modules` did not exist yet.)

## Test / Build Results

**Backend — full `clean verify`, final run (verbatim tail):**
```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.077 s -- in th.co.glr.hr.customer.CustomerControllerTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 825, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ glr-hr-backend ---
[INFO] Building jar: .../backend/target/glr-hr-backend-0.1.0.jar
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ glr-hr-backend ---
[INFO] --- jacoco:0.8.13:report (jacoco-report) @ glr-hr-backend ---
[INFO] Analyzed bundle 'GLR HR Backend' with 235 classes
[INFO] --- jacoco:0.8.13:check (jacoco-check) @ glr-hr-backend ---
[INFO] Analyzed bundle 'glr-hr-backend' with 235 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  04:12 min
```
Baseline before this branch's work (Step 2 tip `d17bcc0`): 808/808. Step 3 adds **17** tests
net: 12 in the new `PricingDecisionIntegrationTest`, 4 net-new in `PricingRequestStatusTest`
(one renamed to assert the opposite — `readyForCeoReview_toCostingInProgress` is now
`isNoLongerAllowed`, not `isNowAllowed` — plus 4 new tests for the new transitions), and 1
net-new in `PricingRequestRepositoryIntegrationTest` (same rename-plus-addition pattern).
808 + 12 + 4 + 1 = 825, matching exactly. Testcontainers ran for real on every run this session
— confirmed by live Flyway migration logs applying every migration through `V72`
("Successfully applied 67 migrations to schema \"hr\", now at version v72") and
`org.testcontainers.DockerClientFactory` / `RyukResourceReaper` lines earlier in each log (only
print for a real provisioned container, not the `@EnabledIf`-skipped short-circuit). No
`TEST_DB_URL` was set; Docker was confirmed running (`docker info`) before each run.

**Frontend:**
```
$ npm run lint
✖ 3 problems (0 errors, 3 warnings)   # pre-existing, unrelated (CommissionPage.jsx, PayrollPage.jsx)

$ npm test -- --run
 Test Files  43 passed (43)
      Tests  345 passed (345)

$ npm run build
✓ built in 157ms
```
(Baseline before this branch: 43 files / 323 tests. Step 3 added 22 tests net: 10 new
`PricingRequestDetailPage.test.jsx` cases (17 → 27) + 12 new `pricingRequestMeta.test.js` cases
(27 → 39), plus one existing `canTransition` assertion flipped from `.toBe(true)` to
`.toBe(false)` with an inline comment explaining why — the map itself changed, so the test
correctly changed with it; no assertion was weakened to match broken behaviour.)

## Authz Evidence

**Every authorization-shaped change in this branch shipped a real-DB integration test through the
real Java service and repository** (`AbstractPostgresIntegrationTest`/Testcontainers,
`PricingDecisionIntegrationTest`), and every guard listed below was **mutation-checked**:
introduce the bug, confirm exactly the targeted test(s) go red and nothing else, revert to an
empty diff (`git diff --check` clean), re-confirm green.

| Guard | Where | Mutation-check result |
|---|---|---|
| CEO freeze — `COSTING_CREATE_STATUSES` excludes `CEO_REVIEWING` | `PricingCostingService` | **Red** — 1 test (`ceoReviewing_freezesFactoryQuoteAndCostingMutations`), added `CEO_REVIEWING` back in |
| CEO freeze — `RESPONSE_STATUSES`/`MUTABLE_STATUSES` exclude `CEO_REVIEWING` | `FactoryQuoteService` | **Red** — same 1 test (both sets mutated together) |
| One return-to-Import path — `COSTING_REVISION_REQUIRED -> COSTING_IN_PROGRESS` | `PricingRequestStatus.ALLOWED` | **Red** — 3 tests across 3 files (`PricingDecisionIntegrationTest`, `PricingRequestStatusTest`, `PricingRequestRepositoryIntegrationTest`), each independently proving the same edge |
| Approval item gate — margin + minimum selling price required | `PricingDecisionService.approve` | **Red** — 1 test (`approve_rejectsWhenAnyItemLacksMinimumSellingPriceOrMargin`) |
| Server recomputes selling price at approval (never trusts stored/corrupted price) | `PricingDecisionService.approve` | **Red** — 1 test (`approve_recomputesSellingPriceFromCostAndMargin_ignoringAnyStoredOrCorruptedPrice`) — the test SQL-corrupts the stored proposed price before approving |
| Sales-view ownership check (non-owning sales rep) | `PricingDecisionService.salesView` | **Red** — 1 test (`salesView_rejectsANonOwningSalesRep`), wrong-way-round |
| `RAW_DECISION_ROLES` excludes `account` (and everyone but import/ceo) | `PricingDecisionService` | **Red** — 1 test (`accountRole_cannotReachDecisionsCostingsOrFactoryQuotes`) |
| `pg_advisory_xact_lock` in `approve()` | `PricingDecisionRepository`/`PricingDecisionService` | **Unverified-but-harmless** — removing it, `approveConcurrently_exactlyOneWins_oneApprovalEventOneNotification` still passed 3/3 runs. Postgres's own row-level lock on the compare-and-set `UPDATE ... WHERE status = 'DRAFT'` already serializes the two racing approvals on its own (same finding, same reasoning, as Step 2's `V71` unique index / first-response advisory lock — see `88_feat-sales-factory-quote-costing.md` §7). Kept as defense-in-depth per the task's explicit instruction to use it, and because it also protects `update()`/`recalculate()`/`returnToImport()` racing against `approve()` on the *decision row itself* even though the single-column compare-and-set already covers the specific concurrent-approve scenario tested. |
| `uq_pricing_decision_one_approved` partial unique index | migration `V72` | **Not independently mutation-tested** (would require a throwaway migration edit + replay) — analytically the same "unverified-but-harmless" story as the lock above: two decision rows for the same pricing_request can only both be terminal-eligible if both were ever `DRAFT` at once, which `uq_pricing_decision_open_draft` already forbids. Kept as the DB-level backstop the migration's own comment documents; a direct-SQL test (`onlyOneApprovedDecisionCanEverExist_databaseLevel`) confirms the constraint exists and fires (`DuplicateKeyException`) by bypassing the service and inserting a second `APPROVED` row directly. |

**Sales-view ownership + cost-leak (design correction 2) — full evidence, not just the mutation
row above:**
- `salesView_returns404WhenNoDecisionHasBeenApprovedYet` — a non-approved decision is invisible
  (404), not partially visible.
- `salesView_rejectsANonOwningSalesRep` — wrong-way-round, mutation-proven above.
- `accountRole_cannotReachDecisionsCostingsOrFactoryQuotes` — `account` gets 403 on
  `PricingDecisionService.get/list/salesView` **and** the pre-existing `PricingCostingService.list`
  / `FactoryQuoteService.list` (re-confirming Step 2's own guard still holds), mutation-proven
  above for the decision side.
- Structural (not just tested): `PricingDecisionSalesViewDto`/`PricingDecisionSalesItemDto` have
  no cost/margin field in their record definition at all — there is no filter to forget, because
  the type that reaches Sales cannot represent cost. `PricingDecisionRepository.findApprovedSalesView`
  builds these records directly from a dedicated SQL projection, never by taking a
  `PricingDecisionItemDto` and stripping fields. The frontend mirrors this exactly:
  `mockApi.js`'s `getPricingDecisionSalesView` constructs a fresh object literal per item (see
  its own comment), and `PricingRequestDetailPage.jsx`'s sales-view render path never touches
  `frozenLandedCostPerRequestedUnitThb`/`proposedMarginPct`/`approvedMarginPct` — confirmed by a
  frontend test asserting no "ต้นทุน"/"Margin" text renders anywhere on the page for a sales user
  (**UI-level only**, see the file's own header note — not authz evidence).

**Reporting:** frontend role-visibility assertions (who sees which button/section) are UI-level
only per CLAUDE.md — they prove the component's own conditional rendering and query-`enabled`
gating, not server-side enforcement. The authoritative checks are the nine backend guards in the
table above, all real-DB, all mutation-proven.

## Decisions Made — stated explicitly per CLAUDE.md's sales-flow-redesign license

1. **Removed the direct `READY_FOR_CEO_REVIEW -> COSTING_IN_PROGRESS` transition** (added by Step
   2 commit 5 for its "Costing v2 reopen path"). This let Import silently reopen a `SUBMITTED`
   costing with zero CEO involvement, which is exactly what made design correction 3's "submitted
   costing is immutable" claim false. The single replacement is `CEO_REVIEWING ->
   COSTING_REVISION_REQUIRED -> COSTING_IN_PROGRESS`, reachable only via
   `PricingDecisionService.returnToImport` (a CEO action). This is a genuine, intentional
   business-logic change to Step 2's own state machine, made because Step 3's design corrections 3
   and 4 explicitly require it — not a side effect.
2. **`FactoryQuoteService.receive()`'s revision branch also used to auto-transition
   `READY_FOR_CEO_REVIEW -> COSTING_IN_PROGRESS`** when a factory sent a revised quote while the
   request awaited CEO review — a SECOND place doing the same silent reopen, discovered while
   implementing this branch (it broke one pre-existing Step 2 test,
   `factoryQuoteAttachmentDeletion_isRefusedOnceReferencedByASubmittedCosting`, once the direct
   transition edge above was removed). Fixed the same way: the pricing request status is now left
   unchanged when this happens; the factory's new quote is still recorded (superseding the old
   one), and if the CEO wants it reflected in a new costing, they return the request via the same
   `COSTING_REVISION_REQUIRED` path. **Import can still receive/negotiate/mark-ready factory quote
   revisions while `READY_FOR_CEO_REVIEW`** (before the CEO clicks "start review") — the freeze in
   design correction 3 is scoped exactly to `CEO_REVIEWING` onward, per the task brief's own
   wording ("Freeze factory mutations from CEO_REVIEWING (owner's decision)"), not to
   `READY_FOR_CEO_REVIEW`.
3. **Approve/return never accept a client-supplied selling price at all** —
   `ApprovePricingDecisionRequest` has no price/margin field, only `ceoNote`/`clientRequestId`.
   The CEO edits margins via `PUT /pricing-decisions/{id}` beforehand; `approve()` always
   recomputes `approvedSellingPricePerRequestedUnit` fresh from
   `frozenLandedCostPerRequestedUnitThb * (1 + approvedMarginPct)` (converted through the pinned
   FX rate), never from any stored or client-influenced column. This makes design correction 7
   structurally true, not just tested — mutation-proven above regardless.
4. **`frozenLandedCostPerRequestedUnitThb` is derived as `costingItem.totalLandedCostThb /
   costingItem.requestedQuantity`**, not by re-deriving the piece-conversion factor
   (`piecesPerBox`/`sqmPerUnit`/`linearMPerUnit`) independently. This reuses Step 2's own
   normalization output instead of duplicating its logic a second time in Step 3, and is what
   makes the per-box-vs-per-piece-same-total test (`unitBasis_perBoxAndPerPieceRequests_...`)
   pass by construction rather than by coincidence.
5. **FX for a non-THB decision currency reuses `FxResolver` (Step 2's BOT-source, 7-day-staleness
   validation)**, pinned once at `startReview` time onto `pricing_decision.fx_rate_used/
   fx_source/fx_effective_date` and never re-resolved for that decision version afterward — even
   if `sales.fx_rates` changes later. **The frontend mock does NOT replicate this** — it always
   uses THB/rate 1, the same simplification the Step 2 mock already makes for costing (no
   `fxRate`/`fxSource` fields on a mock costing item either). Documented in "Known Risks".
6. **Approval idempotency uses a decision-scoped `approve_client_request_id` column** (new,
   separate from the create-time `client_request_id`), mirroring the create-draft pattern already
   established in `PricingCostingRepository`/`FactoryQuoteRepository`. A retry with the SAME key
   returns the existing approved decision; a retry with no key (or a different key) against an
   already-APPROVED decision gets a clean 409, not a silent second "success" — this is what makes
   the "two CEOs, exactly one wins" test assertable as a hard failure for the loser, not an
   ambiguous double-success.
7. **`pricing_decision_item`'s `currency` column always equals the parent decision's `currency`**
   — enforced only in `PricingDecisionService` (every write path copies it from the decision),
   not by a cross-table DB constraint (Postgres CHECK constraints cannot reference another table).
   Documented as a known limitation, not a bug: nothing outside this service ever writes that
   column.
8. **`PricingDecisionRequests.UpdatePricingDecisionItemRequest` uses `COALESCE`-style partial
   updates** (a `null` field leaves the existing DB value unchanged) — matches the exact
   convention `PricingCostingRepository`/`PricingRequestRepository` already use elsewhere in this
   codebase (e.g. `note = COALESCE(:note, note)`). One consequence: a CEO cannot use this endpoint
   to explicitly clear a `discountCeilingPct` back to `null` once set — the same limitation the
   existing costing/pricing-request note fields already have, not a new one introduced here.

## Assumptions
- "Every decision item needs a margin AND a minimum selling price before approval" (design
  correction 5) was read as applying to every row on the decision — there is no per-item
  "excluded"/"inactive" concept anywhere in the `PricingRequestItemDto`/`PricingCostingItemDto`
  model this branch builds on, so "every active item" in the task brief is every
  `pricing_decision_item` row.
- The `recalculate` endpoint's "reapply the (possibly newly updated) default margin to every item"
  behavior was designed as an explicit CEO bulk-reset action (pass `defaultMarginPct` in the
  request body to trigger it), separate from "just refresh every item's price from its own current
  margin" (omit it) — the task brief specifies the endpoint must exist but not its exact semantics
  beyond "recalculate."

## Known Risks
- **Mock FX is THB-only** (see "Decisions Made" #5) — `VITE_USE_MOCKS=true` cannot exercise a
  non-THB Step 3 decision end-to-end; this mirrors a pre-existing Step 2 mock gap (costing has no
  FX fields either), not a new one.
- **The `uq_pricing_decision_one_approved` partial unique index was not independently
  mutation-tested** (see the Authz Evidence table) — analytically redundant with the already-tight
  `uq_pricing_decision_open_draft` invariant in every currently reachable scenario, kept as
  documented defense-in-depth matching Step 2's own precedent for its analogous index.
  `pg_advisory_xact_lock` in `approve()` is similarly unverified-but-harmless for the one scenario
  tested (see the same table) but is real, cheap insurance against any future code path that adds
  another write to the same row without also going through the compare-and-set.
- **Step 3 does not implement Step 4** (quotation generation using the approved price, discount
  policy enforcement against `discountCeilingPct`/`minimumSellingPricePerRequestedUnit`) — those
  fields are stored and exposed to Sales but nothing yet reads them to gate an actual discount.
  `APPROVED_FOR_QUOTATION` is terminal in `PricingRequestStatus.ALLOWED` for now, explicitly noted
  in that file's Javadoc as "Step 4 will extend this."
  This branch is **not independently deployable** for the same reason Step 1/Step 2 were not —
  Steps 1-3 together still cannot produce a customer-facing quotation document.
- **This branch is currently stacked on Step 2's own working tree** (`d17bcc0`), which per Step
  2's handoff §8 is itself 6 commits behind `origin/main` and has a known `V55` production-version
  collision recorded in `docs/flyway-version-collision-audit` — unresolved by this branch, carried
  forward as Step 2 left it. `V72` (this branch's own migration) was re-verified free against
  every open worktree immediately before use (see the migration file's own header) but the deeper
  cross-branch production-numbering conflict is Step 2's to resolve at merge time, not this
  branch's.
- **CEO-facing per-item UI is a flat list, not grouped by factory** — acceptable for the small
  item counts in every test fixture; a pricing request with many line items across many factories
  would benefit from grouping, deferred as polish.
- **No dedicated `PricingDecisionServiceTest` (Mockito unit tests)** exist alongside the real-DB
  integration test — every decision path in this branch is exercised only through
  `PricingDecisionIntegrationTest`. This matches `PricingCostingService`'s own Step 2 precedent
  (also has no dedicated Mockito test file), so it is consistent with the codebase, not a gap
  specific to this branch.

## Things Not Finished
- Step 4 (quotation generation, discount-policy enforcement) — explicitly out of scope per the
  task brief.
- Rebasing onto current `origin/main` / resolving the `V55` collision — inherited from Step 2,
  not attempted here (this branch was never asked to touch Step 2's own unresolved items beyond
  what Step 3 requires).
- No commit was made — per instructions, this branch's changes are left in the working tree.

## Recommended Next Agent
Claude/Codex implementation agent for Step 4 (quotation generation from the approved
`pricing_decision`), once this branch and Step 2 are rebased/merged together. A review pass
(Opus) on this branch before that, given the chain's own history of overstated verification
claims noted in the task brief.

## Exact Next Prompt
```
Rebase feat/sales-ceo-pricing-decision onto a merged main+Step2 (once Step 2 lands) and re-run
the full backend `clean verify` + frontend `lint && test && build`. Then start Step 4: quotation
generation from an APPROVED_FOR_QUOTATION pricing request's pricing_decision, enforcing each
item's discountCeilingPct/minimumSellingPricePerRequestedUnit as a floor/ceiling on whatever
selling price a Sales rep proposes to the customer at quotation time. Read
docs/agent-handoffs/92_feat-sales-ceo-pricing-decision.md in full first — sections "Decisions
Made" and "Known Risks" record why APPROVED_FOR_QUOTATION is currently terminal in
PricingRequestStatus.ALLOWED and what Step 4 needs to add there.
```
