# Agent Handoff

## Task
Implement Step 5 of the sales pricing chain: Customer Decision and Commercial Revisions. Drive an
ISSUED customer quotation into one of ACCEPTED/REJECTED/REVISION_REQUESTED/EXPIRED, forking
REVISION_REQUESTED into the two already-partly-built mechanisms (commercial-only via
`CustomerQuotationService.createRevision`, cost-affecting via
`PricingRequestService.createCustomerChangeRevision`), fix the Step 3/4 cascade gap so a
cost-affecting revision also supersedes any stale decision/quotation, add `QUOTATION_ACCEPTED` to
`PricingRequestStatus`, and add an automatic EXPIRED sweep.

## Branch
`feat/sales-quotation-outcome`, off `main` tip `f07f487` ("feat(sales): pricing chain Release A —
Steps 1-4 (#251)").

## Base Commit
`f07f487`.

## Current Commit
Uncommitted working tree — **not committed or pushed**, per instructions.

## Agent / Model Used
Claude (Sonnet 5).

## Scope

### In Scope
- Migration `V75__quotation_customer_outcome.sql`: widens `chk_pricing_decision_status` to add
  `SUPERSEDED`, widens `chk_pricing_request_status` to add `QUOTATION_ACCEPTED`, adds
  `sales.quotation.outcome_note/outcome_recorded_by/outcome_recorded_at/outcome_client_request_id`
  + its own unique idempotency index.
- `PricingDecisionStatus.SUPERSEDED` (new terminal-by-supersession value).
- `PricingRequestStatus.QUOTATION_ACCEPTED` + `ALLOWED` map: `QUOTATION_ISSUED ->
  QUOTATION_ACCEPTED` (was terminal), `QUOTATION_ACCEPTED -> {}` (new terminal).
- `PricingRequestEventKind`: `CUSTOMER_QUOTATION_ACCEPTED/REJECTED/REVISION_REQUESTED/EXPIRED`.
- `PricingRequestRepository.supersedeOpenPricingDecisionAndQuotation` (design correction 1's
  cascade), called from `PricingRequestService.createCustomerChangeRevision` alongside the
  existing `cancelOpenStep2Children` call — **not** wired into plain `cancel()` or
  `cancelOpenForTicket`, per the task's literal "call it from the SAME place" instruction.
- `CustomerQuotationService.recordOutcome` (new), `expireOverdueQuotations` (new), widened
  `createRevision` guard (design correction 3) and widened `CustomerQuotationRepository.supersede`
  WHERE clause (a bug I found while implementing the widened guard — see "Decisions Made" #1).
- `CustomerQuotationController`: `POST /api/customer-quotations/{id}/outcome`.
- `QuotationExpiryWorker` (new `@Scheduled` component) + `AppProperties.QuotationExpiry` +
  `app.quotation-expiry.sweep-interval-ms` config.
- Frontend: `hrApi.js`/`mockApi.js`/`routes.js` additions, `pricingRequestMeta.js`
  (`canRecordCustomerQuotationOutcome`, `canCreateCommercialOnlyRevision`, plus two pre-existing
  gaps fixed alongside — see "Decisions Made" #2), a Step 5 section inside
  `PricingRequestDetailPage.jsx`, `format.js` label additions.
- `docs/agent-handoffs/94_feat-sales-quotation-outcome.md` (this file).

### Out of Scope (confirmed not touched)
- No `QUOTATION_REJECTED` pricing-request status — REJECTED lives entirely on
  `quotation.doc_status`, per the task brief's explicit instruction.
- No change to `sales.ticket.status` or `sales_stage` anywhere in this branch — verified in the
  ACCEPTED acceptance test (deal stage identical before/after).
- No change to the legacy ticket-item-driven quotation flow (`TicketService.markQuotationSent/
  Accepted/Rejected`) — Step 5 extends only the Step 4 (`pricing_request_id`-linked) aggregate.
- Payroll/tax/SSO/commission math: untouched.

## Files Changed

### Backend — new
- `backend/src/main/resources/db/migration/V75__quotation_customer_outcome.sql`.
- `backend/src/main/java/th/co/glr/hr/customerquotation/QuotationExpiryWorker.java`.

### Backend — modified
- `pricingdecision/PricingDecisionStatus.java` — `SUPERSEDED` constant.
- `pricingrequest/PricingRequestStatus.java` — `QUOTATION_ACCEPTED` added to `VALUES` and
  `ALLOWED` (`QUOTATION_ISSUED -> {QUOTATION_ACCEPTED}`, was `{}`; new `QUOTATION_ACCEPTED -> {}`).
- `pricingrequest/PricingRequestEventKind.java` — four new `CUSTOMER_QUOTATION_*` kinds.
- `pricingrequest/PricingRequestRepository.java` — new
  `supersedeOpenPricingDecisionAndQuotation(long pricingRequestId)`.
- `pricingrequest/PricingRequestService.java` — `createCustomerChangeRevision` now also calls the
  new cascade method, right after `cancelOpenStep2Children`.
- `notification/NotificationRepository.java` — four new `TICKET_EVENT_TITLES` entries.
- `customerquotation/CustomerQuotationDtos.java` — `CustomerQuotationDto` gains `outcomeNote`,
  `outcomeRecordedAt`.
- `customerquotation/CustomerQuotationRequests.java` — new `RecordQuotationOutcomeRequest`.
- `customerquotation/CustomerQuotationRepository.java` — `findIdByOutcomeClientRequestId`,
  `recordOutcome`, `expireOverdueQuotations` (+ `ExpiredQuotationRow` record); `baseSelect`/
  `mapQuotation` extended with the two new outcome columns; **`supersede`'s WHERE clause widened**
  from `doc_status = 'ISSUED'` to `doc_status IN ('ISSUED', 'REVISION_REQUESTED')` (see "Decisions
  Made" #1 — required for `createRevision`'s widened guard to actually supersede its source).
- `customerquotation/CustomerQuotationService.java` — `createRevision`'s guard widened to accept
  `ISSUED` or `REVISION_REQUESTED`; new `recordOutcome`/`expireOverdueQuotations` methods.
- `customerquotation/CustomerQuotationController.java` — `POST /customer-quotations/{id}/outcome`.
- `config/AppProperties.java` — new `QuotationExpiry` nested class.
- `application.yml` — `app.quotation-expiry.sweep-interval-ms`.
- `test/.../pricingrequest/PricingRequestStatusTest.java` — `quotationIssued_isTerminalForStep4`
  renamed/inverted to `quotationIssued_toQuotationAccepted_isNowAllowed_andNothingElseIs` (same
  rename-not-weaken pattern Steps 3/4 already established) + new `quotationAccepted_isTerminal`.
- `test/.../customerquotation/CustomerQuotationIntegrationTest.java` — 11 new tests (see below).

### Frontend
- `api/hrApi.js`, `api/routes.js` — `recordCustomerQuotationOutcome` endpoint.
- `api/mockApi.js` — mock `recordCustomerQuotationOutcome`; cascade added to
  `createCustomerChangeRevision`'s mock (supersede any DRAFT/APPROVED mock decision + any
  non-terminal mock quotation for the parent pricing request); `createCustomerQuotationRevision`
  mock's guard widened to `['ISSUED', 'REVISION_REQUESTED']`.
- `features/pricingRequests/pricingRequestMeta.js` (+`.test.js`) — new
  `canRecordCustomerQuotationOutcome`, `canCreateCommercialOnlyRevision`; **two pre-existing Step
  4 gaps fixed alongside** (see "Decisions Made" #2): `PRICING_REQUEST_STATUSES` was missing
  `QUOTATION_ISSUED` entirely; `ALLOWED_TRANSITIONS.APPROVED_FOR_QUOTATION` was stale at `[]`
  instead of `['QUOTATION_ISSUED']`.
- `features/pricingRequests/PricingRequestDetailPage.jsx` (+`.test.jsx`) — outcome-recording block
  (accept/reject/revision-requested buttons + customer-note field, Sales/owner/ISSUED-only), the
  explicit commercial-only-vs-cost-affecting choice once REVISION_REQUESTED (widened "สร้าง
  Revision ใหม่" button + a new shortcut button that opens the existing
  `PricingRequestCreateModal mode="revision"` flow — no new modal built), a read-only outcome
  summary line for every non-open status, `QUOTATION_STATUS_TONE` badge-tone map covering the full
  V52+V74 lifecycle.
- `utils/format.js` — `QUOTATION_ISSUED`/`QUOTATION_ACCEPTED` labels added to
  `pricingRequestStatusLabel` (`QUOTATION_ISSUED` was missing — a pre-existing Step 4 gap that made
  the status badge show the raw string; fixed alongside adding `QUOTATION_ACCEPTED`).

## Migration Numbering — re-verified per the task's explicit instruction

Checked live via `git worktree list --porcelain` plus listing every worktree's own
`backend/src/main/resources/db/migration/` directory, both at the start of this session and again
just before writing this handoff:
- This worktree's own tree tops out at `V74__customer_quotation_from_decision.sql` (Steps 1-4,
  inherited from `main` tip `f07f487`).
- Top-level `GL-R-ERP` (`feat/sales-factory-quote-costing`) tops at `V71`.
- `GL-R-ERP-employees`, `GL-R-ERP-main`, `.claude/worktrees/flyway-audit`,
  `.claude/worktrees/profile-avatar-menu` all top at `V54`.
- `.claude/worktrees/nav-menu-grouping` tops at `V55`.
- No other worktree has anything at or above `V72`.

**`V75` was free at both checks.** Re-verify again before merging if time has passed.

## Commands Run

```bash
cd backend && ./mvnw -B -o clean verify
cd frontend && npm ci && npm run lint && npm test -- --run && npm run build
```
(`-o`/offline — Maven repo was warm; `npm ci` was required — this worktree's `node_modules` did
not exist yet.)

## Test / Build Results

**Backend — full `clean verify`, final run (verbatim tail):**
```
[INFO] Tests run: 957, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ glr-hr-backend ---
[INFO] Building jar: .../backend/target/glr-hr-backend-0.1.0.jar
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ glr-hr-backend ---
[INFO] --- jacoco:0.8.13:report (jacoco-report) @ glr-hr-backend ---
[INFO] Analyzed bundle 'GLR HR Backend' with 265 classes
[INFO] --- jacoco:0.8.13:check (jacoco-check) @ glr-hr-backend ---
[INFO] Analyzed bundle 'glr-hr-backend' with 265 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  05:48 min
```
Baseline (Steps 1-4 tip `f07f487`): 945/945. Step 5 adds **12** tests net: `CustomerQuotationIntegrationTest`
gained 11 new tests (9 → 20); `PricingRequestStatusTest` had 1 test removed/renamed and 1 new test
added (net +1: 11 → 12 tests in that file). 945 + 11 + 1 = 957, matching exactly.

**Testcontainers ran for real**: no `TEST_DB_URL` was set (confirmed empty) and Docker was
confirmed running (`docker info`) before every run; the live Flyway migration logs show
"Successfully applied 73 migrations to schema \"hr\", now at version v75" printed once per
integration test class, only reachable through a real, freshly-provisioned Postgres.

**Frontend:**
```
$ npm run lint
✖ 3 problems (0 errors, 3 warnings)   # pre-existing, unrelated (CommissionPage.jsx, PayrollPage.jsx)

$ npm test -- --run
 Test Files  45 passed (45)
      Tests  381 passed (381)

$ npm run build
✓ built in 150ms
```
Baseline: 45 files / 371 tests. Step 5 added **10** tests net: 6 new `pricingRequestMeta.test.js`
cases (`canRecordCustomerQuotationOutcome` × 3, `canCreateCommercialOnlyRevision` × 3) + 4 new
`PricingRequestDetailPage.test.jsx` cases (outcome-recording visible to owner, hidden from
CEO/import, hidden once no longer ISSUED with read-only summary shown, both revision paths offered
once REVISION_REQUESTED). No existing assertion was weakened or removed; the `canTransition` test
gained 5 new assertions for the fixed/new transition-map entries.

## Authz Evidence

**Every authorization-shaped change in this branch shipped a real-DB integration test through the
real Java service and repository** (`AbstractPostgresIntegrationTest`/Testcontainers,
`CustomerQuotationIntegrationTest`), and every guard below was **mutation-checked**: introduce the
bug, confirm exactly the targeted test(s) go red and nothing else, revert to an empty diff
(`git diff --check` clean, re-confirmed), re-confirm the specific test green, then the whole
backend suite green (957/957) at the end.

| Guard | Where | Mutation-check result (verbatim) |
|---|---|---|
| Cascade — customer-change revision supersedes any open decision/quotation | `PricingRequestService.createCustomerChangeRevision` (short-circuited the new cascade call with `if (false)`) | **Red** — exactly 1 test, `recordOutcome_revisionRequested_thenCostAffectingRevision_supersedesOldDecisionAndQuotation`: `expected: "SUPERSEDED" but was: "APPROVED"`. Reverted, re-ran green (20/20). |
| `createRevision`'s widened guard (ISSUED or REVISION_REQUESTED) | `CustomerQuotationService.createRevision` (reverted to the pre-Step-5 `ISSUED`-only check) | **Red** — exactly 2 tests, `recordOutcome_revisionRequested_thenCommercialOnlyRevision_supersedesOldQuotation` and `createRevision_widenedGuard_succeedsFromRevisionRequested`, both erroring at the `createRevision(...)` call with the guard's own 409 message. Reverted, re-ran green. A SECOND mutation (making the guard permissive for every status, `!ISSUED && false`) instead flipped `createRevision_widenedGuard_stillRejectsEveryOtherStatus` red — confirms the negative-space test independently catches an over-widened guard, not just an under-widened one. |
| `CustomerQuotationRepository.supersede`'s WHERE clause (found while implementing the guard above) | `CustomerQuotationRepository.supersede` (reverted to `doc_status = 'ISSUED'` only) | **Red** — exactly 1 test, `recordOutcome_revisionRequested_thenCommercialOnlyRevision_supersedesOldQuotation`, at the "old quotation now SUPERSEDED" assertion. This is a genuinely SEPARATE guard from the one above — `createRevision`'s own guard could be widened correctly while `supersede()`'s WHERE clause silently stayed narrow, leaving a REVISION_REQUESTED source never marked SUPERSEDED after a successful revision. Reverted, re-ran green. |
| `recordOutcome`'s ownership/role guard (`requireEditAccess`) | `CustomerQuotationService.recordOutcome` (short-circuited the `requireEditAccess` call specifically inside this method, not the shared helper globally) | **Red** — exactly 3 tests, `recordOutcome_nonOwningSalesRep_cannotRecordOutcome`, `recordOutcome_ceoAndImport_areReadOnly_cannotRecordOutcome`, `recordOutcome_accountRole_cannotReachAtAll`. Reverted, re-ran green. |
| `PricingRequestStatus.ALLOWED`'s new `QUOTATION_ISSUED -> QUOTATION_ACCEPTED` entry | `PricingRequestStatus.java` (reverted the entry to `Set.<String>of()`) | **Red** — 1 unit test (`PricingRequestStatusTest.quotationIssued_toQuotationAccepted_isNowAllowed_andNothingElseIs`) plus 4 integration tests that exercise the ACCEPTED outcome path (`recordOutcome_accepted_...`, `recordOutcome_nonOwningSalesRep_...`, `createRevision_widenedGuard_stillRejectsEveryOtherStatus`, `expireOverdueQuotations_sweep_...` — all of which build an ACCEPTED fixture as a side scenario), all failing with the `PricingRequestRepository.transition`/`canTransition` `IllegalStateException`. Reverted, re-ran green. |
| Sweep's `validity_date < CURRENT_DATE` filter | `CustomerQuotationRepository.expireOverdueQuotations` (dropped the `validity_date IS NOT NULL AND validity_date < CURRENT_DATE` clause, leaving only `doc_status='ISSUED' AND pricing_request_id IS NOT NULL`) | **Red** — exactly 1 test, `expireOverdueQuotations_sweep_pastValidityFlips_futureValidityAndAcceptedUntouched` (the future-validity quotation was wrongly expired too). Reverted, re-ran green. |
| Explicit `EXPIRED` rejection in `recordOutcome` | `CustomerQuotationService.recordOutcome` (short-circuited the explicit `EXPIRED` check with `false &&`) | **Unverified-but-harmless** — `recordOutcome_expiredOutcome_isRejectedRegardlessOfRole` still passed, because `RECORDABLE_OUTCOMES` (the allowlist gate right below it) independently excludes `EXPIRED` too and throws the same `400`. The explicit check is redundant defense-in-depth over the allowlist, not independently load-bearing for the one test written — documented honestly rather than claimed as mutation-proven. |

**Read access / structural cost-leak guarantees** — unchanged by this branch, re-confirmed still
holding: `recordOutcome_ceoAndImport_areReadOnly_cannotRecordOutcome` proves both roles still reach
`get()` (read-only) while getting `403` on `recordOutcome` specifically. `CustomerQuotationDto`
still has no cost/margin/FX field (Step 5 only added `outcomeNote`/`outcomeRecordedAt`, both
customer-facing).

**Reporting:** the frontend UI-level tests (role-conditional rendering in
`PricingRequestDetailPage.jsx`) are **not authz evidence** — they prove this component's own
conditional rendering against a hand-rolled mock, not server-side enforcement. The authoritative
checks are the backend guards in the table above, all real-DB, all mutation-proven except the one
row honestly marked otherwise.

## Decisions Made — stated explicitly per CLAUDE.md's sales-flow-redesign license

1. **`CustomerQuotationRepository.supersede`'s WHERE clause needed widening alongside
   `createRevision`'s guard** — not called out explicitly in the task brief, discovered while
   implementing design correction 3. `createRevision` calls `quotations.supersede(source.id())`
   unconditionally after inserting the new draft; that method's own WHERE clause was still
   `doc_status = 'ISSUED'` only, so a commercial-only correction created from a REVISION_REQUESTED
   source would silently leave the old row stuck at REVISION_REQUESTED forever (0 rows affected,
   return value never checked by the caller). Widened to `IN ('ISSUED', 'REVISION_REQUESTED')` —
   mutation-proven as a genuinely separate guard from the createRevision entry guard itself (see
   the Authz Evidence table).
2. **Two pre-existing Step 4 frontend gaps fixed alongside this branch's own work**, both in files
   this branch was already touching for Step 5's own additions:
   - `pricingRequestMeta.js`'s `PRICING_REQUEST_STATUSES` array and `ALLOWED_TRANSITIONS` map never
     got `QUOTATION_ISSUED` added when Step 4 shipped — `APPROVED_FOR_QUOTATION` was still mapped
     to `[]` instead of `['QUOTATION_ISSUED']`, and the status wasn't in the enum array at all.
     Fixed alongside adding `QUOTATION_ACCEPTED` to both, since leaving the pre-existing gap next
     to the new addition would have made the new code follow a pattern already known to be stale.
   - `format.js`'s `pricingRequestStatusLabel` map was missing a `QUOTATION_ISSUED` entry entirely
     (fell back to the raw uppercase status string in the badge). Fixed alongside adding
     `QUOTATION_ACCEPTED`'s own label, for the same reason.
   Both are UI-only fixes (no behavior/authz change) and are recorded here explicitly rather than
   silently folded into the diff, per CLAUDE.md's "state each such change explicitly."
3. **`recordOutcome`'s cascade is NOT wired into plain `cancel()` or `cancelOpenForTicket`** — the
   task brief says to call the new cascade "from the SAME place `createCustomerChangeRevision`
   already calls the cascade," read literally as scoping the change to that one call site, not
   every call site of `cancelOpenStep2Children`. A pricing request cancelled outright (not
   superseded by a customer-change revision) leaves its decision/quotation status untouched by this
   branch — documented as a **Known Risk** below, not silently assumed equivalent.
4. **The explicit EXPIRED rejection in `recordOutcome` is intentionally kept** even though the
   mutation-check found it redundant with `RECORDABLE_OUTCOMES`'s own allowlist (see Authz
   Evidence) — it gives a clearer, EXPIRED-specific error message ("ระบบตั้งเป็นอัตโนมัติเท่านั้น")
   than the generic "outcome ไม่ถูกต้อง" the allowlist alone would produce, and documents the
   sweep-only intent inline for the next reader.
5. **The sweep is scoped to `pricing_request_id IS NOT NULL`** (Step 4/5 quotations only) — a
   legacy ticket-item-driven quotation reaching ISSUED with a past `validity_date` is NOT touched
   by this sweep, because emitting a `sales.pricing_request_event` requires that link and this
   step's scope is the Step 4 aggregate, not a retrofit of the legacy flow's own (currently
   nonexistent) expiry handling.
6. **`outcome_note` is a new column, deliberately separate from the pre-existing `customer_notes`**
   (V74) — different author (customer via Sales, vs. Sales itself), different timing (after issue,
   vs. before), same reasoning V74 already used to keep its own new columns distinct from the
   legacy ones.
7. **Idempotency uses a third UUID column** (`outcome_client_request_id`), mirroring
   `client_request_id`/`issue_client_request_id`'s own split — a retry with the SAME key replays
   the existing outcome; a retry with no/different key against an already-non-ISSUED row is a clean
   409.

## Assumptions
- "Customer note" (the task's `customerNote` field) is free text with no structure — stored
  verbatim, displayed verbatim, no parsing/validation beyond the existing `@Size(max = 4000)`.
- The CEO-visibility-notification substitute for "customer notification" established in Step 4
  (no customer user account exists in this system) is reused verbatim for every Step 5 outcome —
  same rationale, not re-litigated.
- "Deal stage unchanged" for the ACCEPTED acceptance scenario means neither `sales.ticket.status`
  nor `sales.ticket.sales_stage` moves — verified for `sales_stage` directly (the field Step 4's
  own acceptance test also checks); `ticket.status` was already established in Step 4's handoff as
  never written by this pricing-request-driven chain at all.

## Known Risks
- **RESOLVED by reviewer (Opus)**: the cascade gap above (Known Risk #1 in the original draft of
  this handoff) was closed for both remaining call sites, not just `createCustomerChangeRevision`.
  - `cancelOpenForTicket` (the dead-deal cascade via `cancelForDeadDeal`, which deliberately
    bypasses `PricingRequestStatus.canTransition` to cancel from ANY open status once a deal goes
    terminal) genuinely CAN reach a pricing request with an APPROVED decision and an ISSUED
    quotation — this was a real, live gap. Fixed by calling
    `supersedeOpenPricingDecisionAndQuotation` alongside `cancelOpenStep2Children` at that call
    site. Proven with a new test,
    `CustomerQuotationIntegrationTest#ticketMarkLost_cascadesToSupersedeAnOpenPricingDecisionAndQuotation`,
    mutation-checked (introduce the bug → this specific test alone goes red, `expected: SUPERSEDED
    but was: APPROVED` → revert → green).
  - `PricingRequestService.cancel` was investigated and found to be **structurally unreachable**
    for this bug: it is gated by `PricingRequestStatus.canTransition(status, CANCELLED)`, and every
    status that can transition to `CANCELLED` (`DRAFT`/`SUBMITTED`/`IMPORT_REVIEWING`/
    `AWAITING_FACTORY_RESPONSE`/`COSTING_IN_PROGRESS`/`MORE_INFO_REQUIRED`) is pre-costing-
    submission — none can co-exist with an open `pricing_decision`, which requires
    `READY_FOR_CEO_REVIEW` or later. The equivalent call was still added there, at zero runtime
    cost (a no-op `UPDATE` today), as a defence against a future widening of that transition map
    silently reintroducing this exact bug — but it has no test, since there is no way to reach it
    through the real API today. This is the same "unverified-but-harmless" shape as the `V70`
    revision-chain unique index and the first-response advisory lock from earlier steps.
- **The explicit EXPIRED rejection in `recordOutcome` is not independently mutation-proven** — see
  Authz Evidence's honest "unverified-but-harmless" row. `RECORDABLE_OUTCOMES`'s allowlist is the
  actually load-bearing guard for this specific rule.
- **No dedicated `CustomerQuotationServiceTest` (Mockito unit tests)** — every Step 5 path is
  exercised only through `CustomerQuotationIntegrationTest`, matching every prior step's own
  precedent (Steps 2-4 have no dedicated Mockito test file either).
- **The mock's FX/currency handling stays THB-only** — the same simplification every prior step's
  mock already makes; Step 5 adds no new FX-adjacent surface, so this is unchanged, not new.
- **This branch is off `main` tip `f07f487`** (Steps 1-4, already merged) — no known migration
  collision at time of writing (re-verified above), but worktree numbers move between checks per
  every prior handoff's own repeated caveat.

## Things Not Finished
- No commit was made yet — the reviewer's cascade fix (see Known Risks) is in the working tree
  pending commit.

## Recommended Next Agent
The reviewer's fix and its mutation-check are complete; the full suite is green
(`958/958` backend, `381/381` frontend). Commit this branch and continue to Step 6.

## Exact Next Prompt
```
Review docs/agent-handoffs/94_feat-sales-quotation-outcome.md in full, then independently re-run
every mutation-check row in its "Authz Evidence" table against feat/sales-quotation-outcome
(introduce each described bug, confirm the same test(s) go red, revert, confirm green) — this
chain has a documented history of overstated verification claims (see 92_feat-sales-
ceo-pricing-decision.md and 93_feat-sales-customer-quotation.md's own "Recommended Next Agent"
sections). Separately, decide whether "Known Risk" #1 (PricingRequestService.cancel/
cancelOpenForTicket do NOT run the new supersede-decision-and-quotation cascade, only
createCustomerChangeRevision does) is a gap worth closing now or genuinely out of this step's
scope, and if the former, implement it as a small follow-up commit on this same branch. Once
satisfied, this closes the full 5-step sales pricing chain (Steps 1-5) — the recommended next
piece of work is a full manual click-through of Steps 1-5 in frontend-mock against a real deal,
which no agent on this chain has yet performed end-to-end.
```
