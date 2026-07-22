# Agent Handoff

## Task
Implement Step 4 of the sales pricing chain: Customer Quotation Generation and Issuance. A
quotation must source every price from the current APPROVED `sales.pricing_decision` (never from
legacy `sales.ticket_item` price columns), snapshot it immutably, enforce Discount Policy B
(controlled — Sales may discount down to, but never below, the CEO-approved minimum selling
price), support draft edit / preview / issue / cancel / revision, reuse the existing quotation
number space, renderer, and deal-stage transition, and ship real-DB authz evidence per CLAUDE.md.

## Branch
`feat/sales-customer-quotation`, stacked on Step 3's completed tip `e90cdad`
(`feat/sales-ceo-pricing-decision`).

## Base Commit
`e90cdad` (Step 3, "feat(pricing): CEO selling-price decision (Step 3)").

## Current Commit
Uncommitted working tree — **not committed or pushed**, per instructions.

## Agent / Model Used
Claude (Sonnet 5).

## Scope

### In Scope
- New `th.co.glr.hr.customerquotation` package: `CustomerQuotationDtos`, `CustomerQuotationRequests`,
  `CustomerQuotationRepository`, `CustomerQuotationService`, `CustomerQuotationController`.
- Migration `V74__customer_quotation_from_decision.sql` extending the EXISTING `sales.quotation` /
  `sales.quotation_item` tables (owner's decision: one quotation number space, one renderer, one
  deal-stage transition — no `sales.customer_quotation` parallel table).
- `PricingRequestStatus`: new `QUOTATION_ISSUED` status, one new transition
  `APPROVED_FOR_QUOTATION -> QUOTATION_ISSUED`.
- `PricingRequestEventKind`: five new `CUSTOMER_QUOTATION_*` kinds.
- `QuotationStatus`: two new constants (`READY_TO_ISSUE`, `REVISION_REQUESTED` — declared now,
  Step 5 completes their semantics).
- `TicketService.advanceStageForCustomerQuotationIssue` — a thin public wrapper around the
  existing private `autoAdvanceStage`, so Step 4 reuses the EXACT stage transition
  `generateQuotation` already performs instead of inventing a second path.
- `TicketSummaryDto.withCustomerAndProject` — extracted as a public record method so Step 4 can
  reuse the same frozen-header substitution `TicketService.loadQuotationContext`'s private helper
  already does for the legacy flow, instead of hand-copying the field list a second time.
- `PricingDecisionDtos.PricingDecisionSalesItemDto` / `PricingDecisionRepository.findApprovedSalesView`:
  added `pricingDecisionItemId` (an id, not a cost/margin/FX value) so Step 4 can snapshot the FK
  without touching the "no cost leak" sales-view shape's guarantee.
- Frontend: `hrApi.js`/`mockApi.js`/`routes.js`/`queryKeys.js` additions, a Step 4 section inside
  `PricingRequestDetailPage.jsx` (Sales edit/issue workspace + CEO/Import/sales_manager read-only
  view), `pricingRequestMeta.js` permission helpers.
- `docs/agent-handoffs/93_feat-sales-customer-quotation.md` (this file).

### Out of Scope (confirmed not touched)
- No read or write of `sales.ticket_item` price columns anywhere in the new code path.
- No change to `sales.ticket.status` (the legacy ticket-level state machine) — Step 4 only moves
  `sales_stage` via the reused `autoAdvanceStage`.
- Step 5 (customer response — SENT/ACCEPTED/REJECTED lifecycle on a Step 4 quotation) — the
  statuses are declared in the V74 CHECK constraint per the task brief ("avoids a second
  migration"), but no service logic reaches them yet.
- Payroll/tax/SSO/commission math: untouched.

## Files Changed

### Backend — new
- `backend/src/main/resources/db/migration/V74__customer_quotation_from_decision.sql` — extends
  `sales.pricing_request` (chk status +`QUOTATION_ISSUED`), `sales.quotation` (pricing_request_id,
  pricing_decision_id, quotation_revision_no, client_request_id, issue_client_request_id,
  customer_notes, chk doc_status widened to add READY_TO_ISSUE/ACCEPTED/REJECTED/
  REVISION_REQUESTED alongside every pre-existing value), `sales.quotation_item`
  (pricing_request_item_id, pricing_decision_item_id, requested_unit_basis, requested_quantity,
  approved_unit_price, sales_discount, final_unit_price, line_subtotal, vat, line_total,
  description, item_notes). Additive only — every pre-existing row/column/behaviour preserved.
- `backend/src/main/java/th/co/glr/hr/customerquotation/CustomerQuotationDtos.java`,
  `CustomerQuotationRequests.java`, `CustomerQuotationRepository.java`,
  `CustomerQuotationService.java`, `CustomerQuotationController.java` — the Step 4 aggregate.
- `backend/src/test/java/th/co/glr/hr/customerquotation/CustomerQuotationIntegrationTest.java` —
  9 real-DB tests (see "Authz Evidence").

### Backend — modified
- `pricingrequest/PricingRequestStatus.java` — `QUOTATION_ISSUED` added to `VALUES`; ALLOWED map
  gains `APPROVED_FOR_QUOTATION -> {QUOTATION_ISSUED}` (was `{}` / terminal) and
  `QUOTATION_ISSUED -> {}` (new terminal state).
- `pricingrequest/PricingRequestEventKind.java` — `CUSTOMER_QUOTATION_{CREATED,UPDATED,ISSUED,
  CANCELLED,REVISED}`.
- `ticket/QuotationStatus.java` — `READY_TO_ISSUE`, `REVISION_REQUESTED`.
- `ticket/TicketService.java` — new public `advanceStageForCustomerQuotationIssue(ticketId,
  recipientType, actor)`, delegates to the existing private `autoAdvanceStage`.
- `ticket/TicketSummaryDto.java` — new public `withCustomerAndProject(customerName, projectName)`
  record method (copy-with-override, full field list), extracted so a second package can reuse the
  exact frozen-header substitution the legacy renderer path already performs.
- `pricingdecision/PricingDecisionDtos.java`, `PricingDecisionRepository.java` — added
  `pricingDecisionItemId` to `PricingDecisionSalesItemDto` + its query.
- `notification/NotificationRepository.java` — two new `TICKET_EVENT_TITLES` entries
  (`CUSTOMER_QUOTATION_ISSUED`, `CUSTOMER_QUOTATION_CANCELLED`).
- `test/.../pricingrequest/PricingRequestStatusTest.java` — `approvedForQuotation_isTerminalForStep3`
  renamed/inverted to `approvedForQuotation_toQuotationIssued_isNowAllowed_andNothingElseIs` (the
  map genuinely changed, same rename-not-weaken pattern Step 3's own handoff records for its
  predecessor test) + new `quotationIssued_isTerminalForStep4`.

### Frontend
- `api/hrApi.js`, `api/mockApi.js`, `api/routes.js`, `api/queryKeys.js` — Step 4 endpoints
  (`createCustomerQuotation`, `listCustomerQuotations`, `getCustomerQuotation`,
  `updateCustomerQuotation`, `previewCustomerQuotation`, `issueCustomerQuotation`,
  `cancelCustomerQuotation`, `createCustomerQuotationRevision`,
  `downloadCustomerQuotationPdf/Xlsx`) and a full mock implementation
  (`mockCustomerQuotations` flat array, mirroring `mockPricingDecisions`'s own pattern; discount
  policy B enforced with the same 422; reuses the existing `autoAdvanceStage` mock helper at
  issue time).
- `features/pricingRequests/pricingRequestMeta.js` (+`.test.js`) —
  `canCreateCustomerQuotation`, `canManageCustomerQuotation`, `canViewCustomerQuotation`,
  `isCustomerQuotationEditable`, plus 10 new unit tests.
- `features/pricingRequests/PricingRequestDetailPage.jsx` (+`.test.jsx`) — new "ใบเสนอราคาลูกค้า"
  section: create-draft button (owner + APPROVED_FOR_QUOTATION only), per-item editable
  description/notes/discount with an inline below-minimum warning, server-calculated
  subtotal/VAT/total display, payment/delivery-terms/valid-until/notes editors, Preview PDF/XLSX
  download buttons, Issue via the shared `ConfirmDialog`, Cancel-draft, Create-revision (once
  ISSUED), and a read-only render path for CEO/Import/sales_manager (same data, zero
  editable/mutating controls) — plus 5 new UI-level tests.

## Migration Numbering — re-verified per the task's explicit instruction

Checked live via `git worktree list --porcelain` + listing every worktree's own
`backend/src/main/resources/db/migration/` directory, both at the start of this session and again
just before finishing:

- This worktree's own tree topped out at `V72__pricing_decision.sql` (Step 3, inherited from base
  commit `e90cdad`).
- `.claude/worktrees/ceo-pricing` (`feat/sales-ceo-pricing-decision`, the sibling Step 3 worktree,
  same base commit) also tops out at `V72` — same file, not a collision.
- `.claude/worktrees/payroll-recon` (`feat/payroll-reconciliation`) has an untracked
  `V73__payroll_reconciliation_inputs.sql`.
- `.claude/worktrees/ot-retroactive` (`feat/ot-remove-advance-notice`) has an untracked
  `V70__overtime_salary_basis_snapshot.sql`.
- `.claude/worktrees/special-money` (`feat/special-money-requests`) has an untracked
  `V66__special_money_request_schema.sql`.
- No other open worktree (top-level `GL-R-ERP`, `GL-R-ERP-employees`, `GL-R-ERP-main`,
  `flyway-audit`, `nav-menu-grouping`, `profile-avatar-menu`) has any migration above `V71`.

**`V74` was free at both checks** (V73 is payroll-recon's). The deeper cross-branch/production
`V55` collision this whole chain has carried since Step 2 (`docs/flyway-version-collision-audit`)
is unresolved by this branch, same as Steps 2/3 left it — it is Step 2's/whoever-merges-second's to
resolve, not renumbered here. **Re-verify again before merging** if time has passed — the prior
handoffs' own notes confirm worktree numbers move between checks.

## THE SPINE — verified

`CustomerQuotationService.create`/`createRevision` source every price from
`PricingDecisionRepository.findApprovedSalesView` (the same structurally-cost-free projection
`PricingDecisionService.salesView` uses) — never from `sales.ticket_item`, never from the raw
cost-bearing `PricingDecisionItemDto`. Grep confirms: no file under `customerquotation/` imports
`TicketItemRequest`/`TicketItemDto`'s price fields or `pricing_costing_item`. The item snapshot is
written once at create/revision time into `sales.quotation_item`'s new columns and never
recomputed from a live decision afterward (immutability of an issued document, rule 8).

## Unit Basis — verified (highest financial risk item)

`CustomerQuotationService.buildItem` computes `lineSubtotal = money2(finalUnitPrice.multiply(
item.requestedQuantity()))` where `finalUnitPrice` is per-requested-unit
(`approved_selling_price_per_requested_unit - salesDiscount`, both already in the requested-unit
basis from Step 3) and `requestedQuantity` is the SAME requested-unit basis — never a physical
piece count. `requestedUnitBasis` and `requestedQuantity` are carried onto the quotation item
snapshot verbatim (new columns). Test
`unitBasis_perBoxAndPerPieceRequests_atSamePhysicalQuantity_produceTheSameLineTotal` proves a
per-box request and a per-piece request at the same physical quantity and same per-piece
economics produce the same `lineSubtotal`/quotation `subtotalAmount`, mirroring the Step 2/3
`unitBasis_*` tests' own worked-example pattern.

## Commands Run

```bash
cd backend && ./mvnw -B -o clean verify
cd frontend && npm ci && npm run lint && npm test -- --run && npm run build
```
(`-o`/offline throughout — Maven repo was warm from Steps 2/3's sessions; `npm ci` was required —
this worktree's `node_modules` did not exist yet.)

## Test / Build Results

**Backend — full `clean verify`, final run (verbatim tail):**
```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.057 s -- in th.co.glr.hr.customer.CustomerControllerTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 835, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ glr-hr-backend ---
[INFO] Building jar: .../backend/target/glr-hr-backend-0.1.0.jar
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ glr-hr-backend ---
[INFO] --- jacoco:0.8.13:report (jacoco-report) @ glr-hr-backend ---
[INFO] --- jacoco:0.8.13:check (jacoco-check) @ glr-hr-backend ---
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  04:10 min
```
Baseline before this branch (Step 3 tip `e90cdad`): 825/825. Step 4 adds **10** tests net: 9 in the
new `CustomerQuotationIntegrationTest`, and a net +1 in `PricingRequestStatusTest` (1 removed —
the Step 3 test asserting `APPROVED_FOR_QUOTATION` was terminal, which Step 4 deliberately makes
false — replaced by 2 new tests: the rename-and-invert plus a new "QUOTATION_ISSUED is terminal"
test). 825 + 9 + 1 = 835, matching exactly.

**Testcontainers ran for real**: no `TEST_DB_URL` was set (confirmed `echo $TEST_DB_URL` empty)
and no long-lived Postgres container existed before the run, so the live Flyway migration logs
(`Successfully applied 68 migrations to schema "hr", now at version v74`, printed once per
integration test class, each with realistic per-class timing) are only reachable through a real,
freshly-provisioned Postgres — i.e. Testcontainers actually ran, not the `@EnabledIf`-skipped
short-circuit. Docker was confirmed running (`docker info`) before the run.

**Frontend:**
```
$ npm run lint
✖ 3 problems (0 errors, 3 warnings)   # pre-existing, unrelated (CommissionPage.jsx, PayrollPage.jsx)

$ npm test -- --run
 Test Files  43 passed (43)
      Tests  360 passed (360)

$ npm run build
✓ built in 163ms
```
Baseline before this branch: 43 files / 345 tests. Step 4 added **15** tests net: 5 new
`PricingRequestDetailPage.test.jsx` cases (create gate, gated absence, editable discount +
below-minimum warning + issue confirm, CEO/Import read-only + Preview download, revision button)
+ 10 new `pricingRequestMeta.test.js` cases for the four new permission helpers. No existing
assertion was weakened or removed.

**Browser smoke check (mock, `frontend-mock` on :5200)**: logged in as `sales`, navigated the
deal/ticket list and a ticket detail page — app loads, zero console errors. **Did not** construct
a full click-through APPROVED_FOR_QUOTATION fixture through the UI (that precondition chain is the
same many-step Sales→Import→CEO flow the backend integration test already drives programmatically
and the 5 new component tests already exercise against a hand-built fixture) — recorded as a
**Known Risk** below, not claimed as full manual UI verification.

## Authz Evidence

**Every authorization-shaped change in this branch shipped a real-DB integration test through the
real Java service and repository** (`AbstractPostgresIntegrationTest`/Testcontainers,
`CustomerQuotationIntegrationTest`), and every guard below was **mutation-checked**: introduce the
bug, confirm exactly the targeted test(s) go red and nothing else, revert to an empty diff
(`git diff --check` clean, re-confirmed), re-confirm the specific test green again, then the whole
suite green.

| Guard | Where | Mutation-check result (verbatim) |
|---|---|---|
| Ownership — non-owning sales rep cannot edit/issue | `CustomerQuotationService.requireEditAccess` (disabled `requireOwner` call) | **Red** — `nonOwningSalesRepCannotReadEditOrIssueAnotherRepsQuotation`: `Expecting code to raise a throwable.` at the `update(...)` assertion. Reverted, re-ran green. |
| Discount Policy B floor (never below CEO minimum) | `CustomerQuotationService.applyItemUpdates`'s `if (... < minimum)` check (short-circuited with `false &&`) | **Red** — `discountBelowMinimum_isRejectedWith422_noAutoEscalation`: `Expecting code to raise a throwable.` Reverted, re-ran green. |
| Immutability — `requireDraft` blocks editing an ISSUED quotation | `CustomerQuotationService.requireDraft` (short-circuited) | **Red** — `issuedQuotation_cannotBeEdited`: fails at the `update(...)` assertion line. Reverted. |
| Immutability — repository WHERE clause blocks cancelling an ISSUED quotation | `CustomerQuotationRepository.cancel`'s `WHERE ... AND doc_status IN (...)` (dropped the status filter) | **Red**, independently — with `requireDraft` restored, the SAME test now fails at the `cancel(...)` assertion instead, proving this is a second, independently load-bearing guard, not redundant with `requireDraft`. Reverted. |
| `account` role excluded from every read/write | `CustomerQuotationService.VIEW_ROLES` (added `"account"`) | **Red** — `accountRole_cannotReachCustomerQuotationsAtAll`: fails at the `get(...)` assertion. Reverted. |

Every mutation above was reverted to an exact empty diff (verified via `grep -n "false &&"` and
`grep -n '"account")'` returning nothing, plus a final full `clean verify` at 835/835) before the
final full-suite run was taken as evidence.

**Structural, not just tested — "the quotation carries no cost/margin/FX fields at all":**
`CustomerQuotationDto`/`CustomerQuotationItemDto` (`CustomerQuotationDtos.java`) have no cost,
margin, or FX-rate field in their record definitions — there is no filter to forget, the same
"structurally cannot leak cost" pattern `PricingDecisionSalesViewDto` already established in Step
3. `CustomerQuotationService.buildItem` is built from `PricingDecisionSalesItemDto` (Step 3's
cost-free sales projection), never from `PricingDecisionItemDto` (the raw, cost-bearing DTO) or
`pricing_costing_item`. Re-confirmed inline in `acceptanceScenario_draftDiscountPreviewIssue` (every
item asserted to have `approvedUnitPrice`/`finalUnitPrice` and nothing else).

**Read access** (`ceo`/`import` read-only, `account` fully forbidden, `sales_manager` unscoped
read): `ceoAndImport_canReadButNeverEditOrIssue` proves both roles reach `get()` successfully but
403 on `update`/`issue`. `accountRole_cannotReachCustomerQuotationsAtAll` proves 403 on
`get`/`listForPricingRequest`/`create`.

**Reporting:** the frontend UI-level tests (role-conditional rendering in
`PricingRequestDetailPage.jsx`) are **not authz evidence** — they prove this component's own
conditional rendering against a hand-rolled mock, not server-side enforcement, per this file's own
existing header note and CLAUDE.md's "Authz verify against Java, not the mock". The authoritative
checks are the five backend guards in the table above, all real-DB, all mutation-proven.

## Decisions Made — stated explicitly per CLAUDE.md's sales-flow-redesign license

1. **Did not modify `QuotationRenderer.java`.** Rule 11 ("PDF and XLSX derive from the same stored
   snapshot") is satisfied by reusing the EXISTING legacy rendering columns
   (`brand`/`qty`/`raw_unit`/`unit_price`) on the SAME `sales.quotation_item` row a Step 4
   quotation writes — `description`→`brand`, `requestedQuantity`→`qty`,
   a Thai unit label→`raw_unit`, `finalUnitPrice`→`unit_price`. `CustomerQuotationService.
   loadRenderContext` builds a synthetic `TicketDto`/`QuotationDto`/`CustomerDto` from the Step 4
   DTOs + `TicketRepository.findQuotationItemsByQuotationId`/`findQuotationHeaderSnapshot`
   (both pre-existing, unmodified) and calls the EXISTING `quotationRenderer.toXlsx`/`toPdf`
   verbatim. Zero new PDF/XLSX drawing code exists anywhere in this branch — genuinely "the same
   renderer," not a parallel implementation that happens to look similar.
2. **`ticket.status` (the legacy 9-value ticket-level state machine) is never written by Step 4** —
   only `sales_stage` moves, via the reused `autoAdvanceStage`. The legacy `generateQuotation`
   flow moves BOTH `status` and `sales_stage`; Step 4 deliberately only moves the latter, because
   `sales.ticket.status` is effectively vestigial for a pricing-request-driven deal (many such
   deals never leave `draft` at the ticket-status level while `sales_stage`/`pricing_request.status`
   carry the real state) and forcing it to `quotation_issued` would be a legacy-flow-specific
   status value applied to a deal that never went through that flow.
3. **`quotation_revision_no` is a NEW counter scoped to the pricing_request**, independent of the
   legacy `quotation_version` column (still populated correctly, scoped to `ticket_id +
   recipient_type`, to satisfy the pre-existing `ux_quotation_ticket_recipient_version` unique
   index — "one quotation number space" per the owner's decision). In every reachable Step 4
   scenario the two numbers are identical (a Step 4 pricing request's recipient never has a
   legacy quotation on the same ticket+recipient), but they are computed independently and kept
   as separate columns/DTO fields for clarity of intent, not collapsed into one.
4. **"Customer notification" (acceptance scenario: "exactly one issue event and one customer
   notification") has no literal target** — this system has no customer user account to notify
   in-app. The closest equivalent implemented is a CEO-visibility notification
   (`notifyByRoleForPricingRequest("ceo", ...)`), matching the task brief's own "CEO read-only on
   the final document." Documented here explicitly rather than silently substituted; test asserts
   exactly one `hr.notification` row of type `CUSTOMER_QUOTATION_ISSUED`.
5. **Discount Policy B is the ONLY discount gate implemented** — `discountCeilingPct` (set by the
   CEO in Step 3, carried through to the quotation item for display) is NOT enforced as a second,
   separate cap in this step. The task brief names Policy B ("down to, but never below, the
   CEO-approved minimum_selling_price... no auto-escalation") as THE rule; `discountCeilingPct`'s
   exact relationship to it (a softer warning threshold? redundant with minimum?) is not specified
   by the brief and is left as a **Known Risk**/assumption below rather than guessed at.
6. **`READY_TO_ISSUE` is declared (CHECK constraint, `QuotationStatus` constant) but never actually
   reached by any code path in this cut** — every quotation goes straight from `DRAFT` to `ISSUED`.
   `isOpenForIssue`/`requireDraft`/`isCustomerQuotationEditable` all already treat `DRAFT` and
   `READY_TO_ISSUE` identically, so introducing an explicit "ready to issue" intermediate step
   later (e.g. a "lock for review" action) needs no further schema change — a simplification of
   scope, not a bug, and consistent with the task brief's own framing of a 14-rule generation
   process without mandating a distinct locking step.
7. **`preview()` is a pure re-read of the already-persisted, already-server-computed state** — every
   mutation (`create`/`update`/`createRevision`) already recomputes and persists totals
   server-side (rule 3), so there is no separate "preview computation" to perform; the endpoint
   exists as its own explicit, unambiguous "no side effect" call for the frontend to make before
   offering a PDF/XLSX download, satisfying rule 12 by construction (nothing in the call path ever
   writes).
8. **Idempotency uses two separate UUID columns** (`client_request_id` for create,
   `issue_client_request_id` for issue), mirroring Step 3's own
   `client_request_id`/`approve_client_request_id` split — a retry with the SAME key replays the
   existing result; a retry with a different/no key against an already-terminal row is a clean
   409, not a silent second "success."

## Assumptions
- "Sales-facing customer-facing description" (rules 4/5) defaults from
  `pricing_decision_item`'s joined `productDescription` (falling back to `brand + model`) at
  creation/revision time, then is freely editable — there is no separate "reset to default"
  action; editing is a plain overwrite, same convention as every other free-text field on this
  aggregate.
- `discountCeilingPct` is surfaced in the read DTO chain up through `PricingDecisionSalesItemDto`
  but Step 4 does not currently read or display it anywhere new — it was already part of Step 3's
  sales-view shape and is untouched here.
- The pricing-request's own `recipientType`/`recipientLabel` (set at Step 1) is reused verbatim
  for the quotation row — there is no separate "who is this quotation addressed to" input on the
  Step 4 create endpoint, since the brief's create/issue lifecycle is keyed off the pricing
  request, which already carries this.

## Known Risks
- **Full click-through UI verification against a real APPROVED_FOR_QUOTATION mock fixture was not
  performed** — reaching that state requires the full Sales→Import→CEO Step 1-3 chain (create
  pricing request → submit → pickup → factory quotes → costing → CEO decision → approve), which
  the backend integration test already drives programmatically and the 5 new
  `PricingRequestDetailPage.test.jsx` cases already exercise against a hand-built fixture. The
  browser smoke check confirmed the app loads and runs with zero console errors under the mock
  with this branch's changes loaded, but did not click through the full precondition chain. A
  future agent (or a manual QA pass) should walk this end-to-end in `frontend-mock` before this
  branch is considered demo-ready.
- **`discountCeilingPct` vs. `minimumSellingPricePerRequestedUnit`** — only the latter is enforced
  as a hard gate (Decision 5 above). If the intended design was for `discountCeilingPct` to be a
  SEPARATE, tighter cap Sales must also respect, this branch does not implement that; it is
  surfaced read-only in the raw decision data but not re-checked at the quotation layer.
- **`READY_TO_ISSUE` is unreachable** (Decision 6) — declared for forward-compatibility only.
- **The mock's FX/currency handling is THB-only** (rate 1), the same simplification Steps 2/3's
  mocks already make — `VITE_USE_MOCKS=true` cannot exercise a non-THB Step 4 quotation
  end-to-end.
- **No dedicated `CustomerQuotationServiceTest` (Mockito unit tests)** — every path is exercised
  only through `CustomerQuotationIntegrationTest`. Matches `PricingCostingService`/
  `PricingDecisionService`'s own precedent (neither has a dedicated Mockito test file either), so
  this is consistent with the codebase, not a gap specific to this branch.
- **Step 4 does not implement Step 5** (customer response lifecycle — SENT/ACCEPTED/REJECTED on a
  Step 4 quotation, or reconciling it with the LEGACY `markQuotationSent/Accepted/Rejected`
  endpoints, which still only operate on ticket-item-driven quotations). `ACCEPTED`/`REJECTED`/
  `REVISION_REQUESTED` are declared in the V74 CHECK constraint (per the task brief) but no Step 4
  service method reaches them.
- **This branch is stacked on Step 3's own working tree** (`e90cdad`), itself built on Step 2
  (`d17bcc0`), which per Step 2's handoff §8 carries the unresolved `V55` production-numbering
  collision — not addressed by this branch, carried forward as Steps 2/3 left it.

## Things Not Finished
- Step 5 (customer-response lifecycle on a Step 4 quotation) — explicitly out of scope per the
  task brief's own framing (this step covers generation/issuance/revision, not the customer's
  reply).
- Full manual browser click-through of the Step 4 workspace against a live, click-constructed
  APPROVED_FOR_QUOTATION fixture (see "Known Risks").
- Rebasing onto current `origin/main` / resolving the `V55` collision — inherited from Step 2, not
  attempted here.
- No commit was made — per instructions, this branch's changes are left in the working tree.

## Recommended Next Agent
Claude/Codex implementation agent for Step 5 (customer response lifecycle: SENT/ACCEPTED/REJECTED
on a Step 4 quotation), once this branch, Step 3, and Step 2 are rebased/merged together. A review
pass (Opus) on this branch before that, given this chain's own documented history of overstated
verification claims — re-run every mutation-check row in "Authz Evidence" independently rather
than trusting this file's transcript.

## Exact Next Prompt
```
Rebase feat/sales-customer-quotation onto a merged main+Step2+Step3 (once those land) and re-run
the full backend `clean verify` + frontend `lint && test && build`. Then either (a) do a full
manual click-through of the Step 4 quotation workspace in frontend-mock against a real
APPROVED_FOR_QUOTATION fixture built by walking Steps 1-3 through the UI (the one verification gap
this branch's handoff records as not done), or (b) start Step 5: customer response lifecycle
(SENT/ACCEPTED/REJECTED/REVISION_REQUESTED) on a Step 4 quotation, deciding explicitly whether it
reuses or replaces the legacy TicketService.markQuotationSent/Accepted/Rejected endpoints (which
today only operate on ticket-item-driven quotations, not Step 4's pricing-decision-sourced ones).
Read docs/agent-handoffs/93_feat-sales-customer-quotation.md in full first — "Decisions Made" and
"Known Risks" record the open questions (discountCeilingPct's exact role, READY_TO_ISSUE's
unreached status) Step 5 will need to resolve or inherit.
```
