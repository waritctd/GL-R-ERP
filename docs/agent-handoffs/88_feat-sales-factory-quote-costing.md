# Handoff: Sales Pricing Step 2 — Factory Quotes and Costing

**Branch:** `feat/sales-factory-quote-costing`
**Base before work:** `ba9836f` (`feat/sales-pricing-request-foundation`, stacked on `origin/main` at the time)
**Current tip:** `5ae1c12`, plus six uncommitted commits' worth of changes in the working tree (see
"Files Changed"). **Do not commit or push without explicit instruction.**

This document was rewritten from scratch on 2026-07-21 (COMMIT 6) to replace an appendix-by-appendix
history (one section per review-remediation round) that had drifted from what was actually true and
evidenced. The old structure is preserved in git history if a past round's exact wording is needed;
this version states, once, what is true now.

## 1. Scope Implemented

Step 2 of the sales pricing redesign: Import-owned factory quote requests, factory response
revisions, explicit ready-for-costing marking, costing drafts/recalculation/submission, Sales-level
and factory-quote-level attachments, and the customer-change revision flow — layered on Step 1's
`PricingRequest` aggregate (`docs/agent-handoffs/85_feat-sales-pricing-request-foundation.md`).

- Import generates/edits/sends factory email drafts (per-factory, grouped from catalog-resolved
  factory snapshots), records factory responses/revisions, and marks a quote revision ready for
  costing.
- Costing drafts are created only once every active fixed factory has a current
  `READY_FOR_COSTING` quote; recalculation is explicit and repeatable; submission to CEO is explicit
  and immutable once submitted.
- Sales and Sales Manager cannot read raw factory-quote/costing data (`RAW_QUOTE_ROLES = {import,
  ceo}`); CEO is read-only over that same raw data.
- Sales-level Pricing Request attachments (optional, DRAFT/MORE_INFO_REQUIRED only, owner-scoped)
  with an Import-only "include in factory email" toggle; factory-quote attachments are a separate,
  Import/CEO-only concept with audited-tombstone deletion.
- Sales can create a customer-change revision from an active submitted request; the prior request is
  superseded and open Step 2 children are cancelled.
- Catalog is mandatory at submit (see Known Risks); requested quantities/quoted prices are normalized
  onto a common physical-unit basis before costing math runs, replacing an unsafe unconditional
  per-piece assumption.

## 2. Decision Matrix — honest, evidence-linked

The matrix below replaces the original, which still carried "Pass" on four rows the review process
later found to be wrong or overstated (exactly-once email, unit conversion, optional attachments,
"complete" verification). Every row here reflects what was independently re-verified — see
"Authorization Evidence" (§7) for the mutation-check detail behind each `Pass`.

| Area | Status | Note |
|---|---|---|
| Parent Pricing Request guards | Pass | Real-DB, `PricingRequestRepositoryIntegrationTest`/`PricingRequestFlowIntegrationTest` |
| Cancellation child cascade | Pass | `cancelOpenForTicket` → `cancelForDeadDeal` (COMMIT 5), cascades open Step 2 children |
| Department-wide Import auth | Pass | `requestInformation`/`respondInformation` etc. gate on role only, not `assignedImportId` — see §3.1, a frontend test previously asserted the *opposite* and was fixed |
| Information resume status | Pass | |
| Factory/config strictness | Pass | |
| BOT FX validation | Pass | |
| State model (Costing v2 reopen path) | Pass | `canTransition` now enforced in `PricingRequestRepository.transition()`, not just decorative — mutation-checked, 2 tests red when the assertion was disabled |
| Database uniqueness improvements | Pass, with 2 rows **unverified-but-harmless** | See §7 — the surviving guard (advisory lock or row lock) already serializes every test scenario for those 2 |
| Catalog selection and base price / catalog-mandatory submit | Pass | Intended trade-off (§8); mutation-checked, 4 tests red (2 real-DB + 2 Mockito) |
| Direct manual response flow | Pass | |
| Multi-factory send audit/status | Pass | First factory response no longer wrongly jumps the whole request to `COSTING_IN_PROGRESS` |
| Factory email dispatch delivery | **At-least-once, not exactly-once** | Corrected from the original's "exactly-once" claim — see §8 |
| Costing replay parent validation | Pass | |
| Unit conversion integrity (quantity normalization) | Pass | Mutation-checked, 3 tests red including the review's own worked example (1,000 THB/box × 20 pieces/box × 10 boxes → correct 10,000, not the pre-fix 500) |
| CEO notification matrix | Pass | |
| Factory email editing UI | Pass | Covered by `PricingRequestDetailPage.test.jsx` (new, COMMIT 6) |
| CEO raw quote review UI (read-only) | Pass | Covered by `PricingRequestDetailPage.test.jsx` — no mutating control renders for `ceo` |
| Customer-change revisions | Pass | Race in revision numbering fixed (advisory lock, mutation-proven load-bearing); revision UI now actually edits fields instead of cloning verbatim (COMMIT 5 finding 3) |
| Optional attachments | Pass | Sales-level PR attachments + audited-tombstone factory-quote attachment deletion; 4 of 5 authz guards mutation-proven solely decisive, 1 proven real-but-not-solely-decisive in the one integration scenario chosen (§7) |
| Complete automated verification | **Now true**, was previously only targeted-slice | Full suites green — frontend 43 files/323 tests (0 failures, up from "4 known failures" previously reported as acceptable), backend `clean verify` 807/807 — see §5 |
| Merge readiness | Ready for PR, pending rebase | Branch is 6 commits behind `origin/main` as of this rewrite (fetched 2026-07-21) — unrelated attendance-backfill work, no expected conflict with pricing files, but rebase before opening the PR |
| Step 2 completion | Complete for the revised acceptance scenario | **Still NOT independently deployable** — see §8 |

## 3. COMMIT 6 — closing out review remediation (this pass)

Three items, all frontend/docs. No backend Java or migration changes.

### 3.1 Fixed the 4 pre-existing frontend failures

All four failed identically at branch head `5ae1c12` with an **empty diff** (confirmed via
`git stash` before starting). The original handoff's "verification passed" was an artefact of
running targeted test slices (`npm test -- --run src/api/contract.test.js ...`) that never included
these four; running the full suite (`npm test -- --run`, no path filter) always showed them red. Per
CLAUDE.md, a component is fixed when it's wrong and a test is fixed when it's wrong — never the
assertion weakened to match broken behaviour. Root cause and disposition for each:

1. **`DealStagePanel.test.jsx` ×2** ("shows a newer DRAFT alongside an older still-active
   IMPORT_REVIEWING request", "keeps an older active request visible even when the newest one is
   cancelled") — both asserted `screen.getByText('Import กำลังเสนอราคา')`. COMMIT 3
   (`af1aef4`, financial-integrity review) deliberately relabeled `IMPORT_REVIEWING` from
   `'Import กำลังเสนอราคา'` ("Import is currently quoting") to `'Import ตรวจคำขอราคา'` ("Import is
   reviewing the request") in `utils/format.js`'s `pricingRequestStatusLabel`, once
   `AWAITING_FACTORY_RESPONSE` (`'รอราคาโรงงาน'`) became its own distinct status for the
   factory-quoting phase — confirmed via `git show af1aef4 -- src/utils/format.js`. The two tests
   predate that relabel and were never updated. **The test was wrong** — updated to assert the
   current, deliberately-changed label, with an inline comment pointing at the relabel commit so a
   future reader doesn't "fix" it back.

2. **`PricingRequestPanel.test.jsx`** ("does not offer \"ขอข้อมูลเพิ่มเติม\" to an unassigned import
   user") — asserted the button is hidden from an import user who isn't the request's
   `assignedImportId`. But `canRequestInformation` in `pricingRequestMeta.js` has always read "any
   import user in active Step 2 statuses" (its own comment says so), mirroring the real
   `PricingRequestService.requestInformation`'s `requireRole(actor, IMPORT_ROLES)` with **no**
   `assignedImportId` check (confirmed by reading the Java source directly) — this is department-wide
   by design, stated as a business rule in this file's §1 ("Import department users can request and
   resume Sales information loops ... without relying on a single assigned Import user"). **The test
   was wrong** — inverted to assert an unassigned import user DOES see the button, renamed, and
   labelled UI-level (mock-only) per CLAUDE.md, since the authoritative check is the Java role gate,
   not this test.

3. **`App.test.jsx`** ("redirects a role without canViewPricingRequestQueue (sales) to the dashboard
   instead") — this one was a **real component bug**, not a stale test. `permissions.js`'s
   `PATH_GUARDS` had one combined rule: `{ test: (p) => p === '/pricing-requests' ||
   p.startsWith('/pricing-requests/'), can: (u) => hasPermission(u.role, 'canViewPricingRequestQueue')
   || u.role === 'sales' }`. The `|| u.role === 'sales'` was added (also in COMMIT 3, `af1aef4`) so a
   sales rep could follow a `PICKED_UP`/`MORE_INFO_REQUIRED` notification link to their **own**
   `/pricing-requests/:id` detail page (`NotificationRepository.notifyEmployeeForPricingRequest`
   links there) — but the same predicate also covers the bare `/pricing-requests` queue, which is
   Import's work list and must stay closed to sales. Rendering confirmed the bug live: navigating a
   `sales` user to `/pricing-requests` rendered the `PricingRequestQueuePage` heading
   ("คิวขอราคา"), not a redirect. **Fixed**: split into two rules — the bare path stays
   `canViewPricingRequestQueue`-only, the `/pricing-requests/*` sub-path additionally allows `sales`.
   Two regression tests added to `permissions.test.js`. This is purely a client-side routing/UX gate;
   the backend was never exposed regardless — `PricingRequestRepository.list()` already scopes a
   `sales` actor to `createdByFilter = actor.id()` with real-DB coverage
   (`findSummaries_filtersByStatusDefaultCreatedByAndActiveDealsOnly` in
   `PricingRequestRepositoryIntegrationTest`), so a sales rep who reached the queue page client-side
   would have seen an empty/own-only list, not other reps' data. Still a genuine UX/routing bug worth
   fixing (wrong page reachable at all), just not a data-exposure one.

### 3.2 New test coverage: `PricingRequestDetailPage.test.jsx`

The page had zero prior test coverage. New file,
`frontend/src/features/pricingRequests/PricingRequestDetailPage.test.jsx`, 17 tests against a
hand-rolled `vi.mock('../../api/index.js', ...)` (not the real Java backend, not even `mockApi.js`).
**Per CLAUDE.md's "Mock API contract" / "Authz verify against Java, not the mock", every
role-visibility assertion in this file is UI-level only** — it proves the component's own
conditional rendering, nothing about server-side enforcement. The file's own header states this, and
so does every `describe` block whose name says "(UI-level only — see file header)". The authoritative
checks are the real-DB integration tests already covering these same surfaces server-side
(`PricingFactoryQuoteCostingIntegrationTest`, `PricingRequestFlowIntegrationTest`).

Coverage:
- Sales and `sales_manager` render neither the "Factory Quotes" nor "Costing" section, and the
  underlying raw-data queries (`listFactoryQuotes`/`listCostings`) are never even called (`enabled:
  canSeeRaw(user)`), not just hidden in the DOM.
- CEO sees both sections' raw data but zero mutating controls (send/create-draft/recalculate/submit
  buttons, editable email-draft or response-entry inputs) — read-only is a DOM-level absence, not a
  disabled button.
- Import can edit the factory email draft (To/Subject/Body) before sending and saves it via
  `updateFactoryQuote`.
- The send `clientRequestId` is stable across a click → cancel → re-click cycle (`crypto.randomUUID`
  spied and asserted called only once across both opens, not once per click) — this is what makes a
  retried send idempotent against the backend's outbox worker.
- A factory response/revision entry (`receiveFactoryQuote`) with per-line raw price editing.
- Costing recalculation (`recalculateCosting`) and submit-to-CEO through the confirm dialog
  (`submitCosting`), plus the disabled-while-stale case.
- Customer-change revision editing: opens `PricingRequestCreateModal` in `mode="revision"` seeded
  from the current request (not a blank clone — COMMIT 5 finding 3), edits reach the payload sent to
  `createCustomerChangeRevision`, and the non-owner case (button absent).
- Sales-level pricing-request attachments (COMMIT 4): owner upload while editable, upload/delete
  controls absent once past DRAFT/MORE_INFO_REQUIRED, the Import-only include-in-factory-email
  checkbox vs. the Sales-facing read-only badge, and owner delete.
- A mobile-viewport smoke render (this page has no JS-driven responsive branching — every breakpoint
  is a Tailwind utility class evaluated by CSS media queries jsdom doesn't apply — so the meaningful
  assertion is that the full content tree still renders under a mobile `matchMedia` stub, not a
  different DOM shape).
- No `<button>` nested inside another `<button>` anywhere on the richest rendered scenario
  (eslint-plugin-jsx-a11y is wired into this repo's lint; this is a regression guard, not a lint
  substitute).

## 4. Files Changed

### Backend — migrations (all new, additive)
- `V65__factory_quote_response_idempotency.sql` — `sales.factory_quote_response_receipt` table.
- `V67__factory_quote_dispatch_outbox_worker.sql` — extends `sales.factory_quote_email_dispatch`
  with `attempt_count`/`next_attempt_at`/`claimed_at`/`provider_message_id`/`finalized_at` + claimable
  partial index. **Not V66** — see §8's migration-numbering note.
- `V68__pricing_catalog_gate_and_unit_normalization.sql` — `requested_unit_basis` on
  `pricing_request_item` (+ best-effort backfill), `linear_m_per_unit` on `factory_quote_item`, three
  audit columns on `pricing_costing_item`, and the `pricing_request_item.product_id` FK repoint (see
  §8).
- `V69__pricing_request_and_factory_quote_attachment_hardening.sql` — new
  `sales.pricing_request_attachment` table; `deleted_at`/`deleted_by`/`delete_reason` on
  `hr.file_attachment`.
- `V71__pricing_request_revision_chain_uniqueness.sql` — `uq_pricing_request_chain_revision` unique
  index.

### Backend — main
- `config/AppProperties.java` — `FactoryQuoteDispatch` nested properties (`poll-interval-ms`,
  `reclaim-timeout-seconds`, `max-attempts`, `backoff-base-seconds`, `batch-size`).
- `factory/FactoryEmailService.java` — `send()` overload accepting attachments + returns a locally
  minted message-id `String` instead of `void`.
- `factoryquote/FactoryQuoteController.java`, `FactoryQuoteDtos.java`, `FactoryQuoteRepository.java`,
  `FactoryQuoteRequests.java`, `FactoryQuoteService.java` — send→enqueue-only + outbox worker
  (claim/attemptSend/finalizeDispatch/markDispatchFailed), response-idempotency receipt lookup,
  chain-scoped replay comparison, unit-basis validation via the new `UnitBasis` helper, attachment
  tombstone deletion with the three-guard refusal logic.
- `factoryquote/FactoryQuoteEmailDispatchWorker.java` — new `@Component`, `@Scheduled` tick,
  delegates all logic to `FactoryQuoteService`.
- `pricingcosting/PricingCostingDtos.java`, `PricingCostingRepository.java`,
  `PricingCostingService.java` — `calculate()` rewritten to normalize both the quote's price and the
  request's quantity onto physical pieces independently before multiplying; `resolveSqmPerPiece` no
  longer silently defaults to 1.
- `pricingrequest/PricingRequestController.java`, `PricingRequestDtos.java`,
  `PricingRequestRepository.java`, `PricingRequestRequests.java`, `PricingRequestService.java`,
  `PricingRequestStatus.java` — catalog-mandatory submit gate, `requestedUnitBasis` field,
  `canTransition` enforced in `transition()` + new `cancelForDeadDeal` bypass, advisory-lock-guarded
  `createCustomerChangeRevision`, Sales-level attachment upload/list/delete/include-toggle methods.
- `pricingrequest/UnitBasis.java` — new: the four canonical unit-basis codes + lenient
  `canonicalize()`, extracted from what used to be a private `FactoryQuoteService` method.

### Backend — tests
- `test/.../pricingrequest/PricingFactoryQuoteCostingIntegrationTest.java` — the primary real-DB
  acceptance-scenario test; grew across every commit (outbox dispatch windows, unit-conversion
  matrix, catalog-gate cases, attachment authz, revision-race concurrency) — see §7 for what's
  mutation-proven.
- `test/.../pricingrequest/PricingRequestControllerTest.java`,
  `PricingRequestFlowIntegrationTest.java`, `PricingRequestRepositoryIntegrationTest.java`,
  `PricingRequestServiceTest.java` — updated fixtures for the new required fields + new
  role/status-gate cases.
- `test/.../pricingrequest/PricingRequestStatusTest.java` — new, 6 unit tests for the state-machine
  map itself.
- `test/.../factoryquote/FactoryQuoteServiceAttachmentTest.java` — new, 6 Mockito tests for
  `deleteAttachment`'s refusal decision tree.
- `test/.../support/AbstractPostgresIntegrationTest.java` — `insertCatalogProduct` helper.

### Frontend — commits 1-5
- `api/hrApi.js`, `api/mockApi.js`, `api/queryKeys.js`, `api/routes.js` — endpoint/query-key/mock
  coverage for every backend change above (mock authz is a faithful-shape, non-authoritative mirror
  per CLAUDE.md).
- `api/mockApi.pricingRequests.test.js` — `requestedUnitBasis` added to fixtures.
- `features/pricingRequests/pricingRequestMeta.js` (+`.test.js`) — `UNIT_BASIS_OPTIONS`,
  `ALLOWED_TRANSITIONS.READY_FOR_CEO_REVIEW` gains `COSTING_IN_PROGRESS`.
- `features/pricingRequests/PricingRequestCreateModal.jsx` (+`.test.jsx`) — catalog-picker
  brand-mapping fix, unit input → canonical select, attachment uploader, new `mode="revision"`
  (seeds from current request, `revisionReason` field, `createRevisionFn` prop).
- `features/pricingRequests/PricingRequestDetailPage.jsx` — see §1; outbox-dispatch polling,
  clientRequestId idempotency state for send/receive, attachment sections, revision modal wiring.

### Frontend — COMMIT 6 (this pass)
- `app/permissions.js` — split the `/pricing-requests` path guard into two rules (see §3.1 item 3).
- `app/permissions.test.js` — two new regression tests for that split.
- `features/tickets/DealStagePanel.test.jsx` — updated label assertions (§3.1 item 1).
- `features/pricingRequests/PricingRequestPanel.test.jsx` — inverted the department-wide-Import
  assertion (§3.1 item 2).
- `features/pricingRequests/PricingRequestDetailPage.test.jsx` — **new file**, 17 tests (§3.2).

### Docs
- `docs/agent-handoffs/88_feat-sales-factory-quote-costing.md` — this rewrite.

## 5. Commands Run and Verbatim Tail Output (final consolidated pass, COMMIT 6)

Frontend — full suite, no path filter:
```
$ cd frontend && npm run lint
✖ 3 problems (0 errors, 3 warnings)
# pre-existing, unrelated: react-hooks/exhaustive-deps in CommissionPage.jsx and PayrollPage.jsx

$ cd frontend && npm test -- --run
...
 Test Files  43 passed (43)
      Tests  323 passed (323)
   Start at  05:07:19
   Duration  10.71s (transform 2.15s, setup 6.10s, collect 8.69s, tests 30.46s, environment 34.96s, prepare 2.67s)

$ cd frontend && npm run build
✓ built in 238ms
```
0 failures — previously 300 passed / 4 failed of 304; now 323/323 (4 fixed + 2 new
`permissions.test.js` regression cases + 17 new `PricingRequestDetailPage.test.jsx` cases).

Backend — full build:
```
$ cd backend && ./mvnw -B clean verify
...
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.134 s -- in th.co.glr.hr.customer.CustomerControllerTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 807, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ glr-hr-backend ---
[INFO] Building jar: .../backend/target/glr-hr-backend-0.1.0.jar
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ glr-hr-backend ---
[INFO] --- jacoco:0.8.13:report (jacoco-report) @ glr-hr-backend ---
[INFO] Analyzed bundle 'GLR HR Backend' with 227 classes
[INFO] --- jacoco:0.8.13:check (jacoco-check) @ glr-hr-backend ---
[INFO] Analyzed bundle 'glr-hr-backend' with 227 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  03:09 min
[INFO] Finished at: 2026-07-21T05:11:01+07:00
```
807/807, 0 failures/errors/skipped, coverage gate met, `BUILD SUCCESS`. This is the same 807 total
COMMIT 5 reported — COMMIT 6 made no backend Java/migration changes, so the count is unchanged by
design; this run re-confirms the whole backend suite (not a targeted slice) is still green after the
frontend-only changes above and after fetching `origin/main` (the fetch did not merge/rebase — see
§8 — so this run is still against the pre-rebase tree).

## 6. Testcontainers

**Ran for real**, confirmed three independent ways in the `clean verify` log above:
`org.testcontainers.DockerClientFactory -- Testcontainers version: 2.0.5` and
`org.testcontainers.utility.RyukResourceReaper -- Ryuk started` (both only print when a real
container is actually being provisioned, not on the `@EnabledIf`-skipped short-circuit); live Flyway
migration logs applying every migration through `V71` (`V66` absent, confirming the numbering
decision — see §8 — held); and `Skipped: 0` across all 807 tests, so nothing silently no-opted out.
No `TEST_DB_URL` was set; Docker was confirmed up before the run (`docker info`) and
`PostgresTestSupport` started/reused a throwaway container.

## 7. Authorization Evidence

**No new backend/Java authorization change in COMMIT 6.** The `permissions.js` fix (§3.1 item 3) is
a client-side route-guard convenience only; the authoritative scoping
(`PricingRequestRepository.list()`'s `createdByFilter` for sales) was already correct and unchanged.
Every authz surface below was added/changed in commits 1-5 and is restated here only as a condensed
index — full mutation-check transcripts are in this file's git history if needed verbatim.

**Every authorization-shaped change shipped a real-DB integration test through the real Java service
and repository** (`AbstractPostgresIntegrationTest`/Testcontainers), per CLAUDE.md's "Permission
changes must ship evidence." Guards that could be isolated as the sole cause of a rejection were
mutation-checked (introduce the bug, confirm exactly the targeted test(s) go red, revert to an empty
diff):

| Guard | Where | Mutation-check result |
|---|---|---|
| `canTransition` assertion in `transition()` | `PricingRequestRepository` | **Red** — 2 tests (`transition_rejectsATransitionNotInTheCanonicalMap...`, `cancelForDeadDeal_...`) |
| Advisory lock in `createCustomerChangeRevision` | `PricingRequestRepository` | **Red** — 1 test (`createCustomerChangeRevisionSerializesConcurrentCallers...`), surfaces as a raw `DuplicateKeyException` without it |
| `V71` unique index (`uq_pricing_request_chain_revision`) | migration | **Unverified-but-harmless** — with the advisory lock present, removing the index leaves all tests green; the lock alone fully serializes every reachable test scenario |
| Response-idempotency advisory lock, first-response path | `FactoryQuoteRepository.lockResponseIdempotencyKey` | **Unverified-but-harmless** on that specific path — Postgres's own row lock (an `UPDATE` on a pre-existing row) plus the receipt fallback already serializes it; the lock IS mutation-proven load-bearing on the **revision** path (`INSERT`s a new row, no pre-existing row to block on) — 1 test red there |
| Catalog-completeness gate | `PricingRequestService.submit` | **Red** — 4 tests (2 real-DB + 2 Mockito) |
| Quantity/price normalization | `PricingCostingService.quantityToPieces` | **Red** — 3 tests (correctly unaffected: the 2 `PER_PIECE`-request cases where the bypass and the real switch coincide) |
| Claim-timeout staleness check | `FactoryQuoteRepository.claimDispatch` | **Red** — 2 tests |
| Finalize's `finalized_at IS NULL` guard | `FactoryQuoteService.finalizeDispatch` | **Red** — 1 test |
| Factory-quote attachment deletion guard 1 — `READY_FOR_CEO_REVIEW` excluded | `FactoryQuoteService.ATTACHMENT_DELETE_STATUSES` | **Red** — 1 test |
| Factory-quote attachment deletion guard 2 — quote itself `READY_FOR_COSTING` | `FactoryQuoteService.deleteAttachment` | **Red** — 1 test |
| Factory-quote attachment deletion guard 3 — referenced by `SUBMITTED` costing | `FactoryQuoteService.deleteAttachment` | **Red** — 1 test |
| `setAttachmentIncludeInFactoryEmail`'s `requireRole(IMPORT_ROLES)` | `PricingRequestService` | **Red** — 1 test |
| `uploadAttachment`'s `requireRole(SALES_ROLES)` | `PricingRequestService` | **Red at the Mockito unit level** (`uploadAttachment_rejectsNonSalesRoles`); **not solely decisive** in the one real-DB integration scenario chosen — Import is still correctly rejected via the method's own unconditional ownership re-check even with the role gate disabled. Reported transparently: the guard is real and proven load-bearing by the unit test, it just isn't the only thing standing between Import and someone else's attachment in that specific scenario. |
| Cross-rep attachment read scope | `PricingRequestService.requireViewable` (reused, not new) | **Red** — wrong-way-round test: a second sales rep gets 404 (DRAFT)/403 (non-DRAFT) on upload/list/download/delete of the first rep's attachments |

Every mutation above was reverted to an exact empty diff (`git diff --check` clean) before the
targeted suite was re-confirmed green, and again before the final full-suite run in §5.

## 8. Known Risks

- **Email delivery is at-least-once, not exactly-once.** `attemptSend`'s `provider_message_id` guard
  stops a *reclaimed* dispatch from resending once the provider call is already recorded as
  successful, but a crash between the provider accepting the email and that recording committing can
  still cause a resend on the next reclaim. `provider_message_id` is a locally minted UUID
  (`FactoryEmailService` has no real provider-assigned id to key on, since it's built on
  `JavaMailSender`), so it cannot deduplicate at the provider — this is an irreducible limitation
  without a provider-side idempotency key, not a bug to fix later.
- **Catalog-mandatory submit is an intended trade-off, stated explicitly**: it is now impossible to
  submit a pricing request item that doesn't resolve to an ACTIVE Price Catalog entry. A
  designer/owner/buyer product with no catalog line can no longer be priced at all — Import must add
  it to the catalog, or Sales must pick an existing line, before submission.
- **`requested_unit_basis` backfill defaults to `PER_PIECE`** for any pre-existing free-text
  `requested_unit` value that doesn't match the known synonym list. Best-effort only, and safe only
  because Step 2 has zero production rows (see below) — would need re-examination if this migration
  were ever backported to an environment with real Step 2 data.
- **Pre-existing schema bug fixed as a necessary part of COMMIT 3, stated explicitly**:
  `sales.pricing_request_item.product_id`'s FK pointed at `sales.catalog(catalog_id)` (V59) while the
  catalog picker and `snapshotCatalogSelections` both key off `price_catalog.product_prices.price_id`
  — a completely different id space, with zero prior test coverage. A catalog-picker-sourced
  `productId` could only ever have persisted by coincidence before `V68` repointed the FK. This is a
  schema correction, not itself a pricing-flow business-logic change.
- **The `V71` unique index and the first-response advisory lock are unverified-but-harmless** — see
  §7. Kept as defense-in-depth per the original plan's instruction, not because a failing test
  currently depends on either.
- **The `uploadAttachment` role gate's marginal value is narrow** (§7) — Import can never reach
  another rep's DRAFT (draft privacy) or MORE_INFO_REQUIRED attachment (the ownership re-check blocks
  it independently of the role gate) in the states this branch can currently reach. Not acted on
  further; recorded for the next reviewer's context.
- **`mockApi.js`'s `receiveFactoryQuote` still performs no unit-basis validation/canonicalization** —
  pre-existing gap (confirmed: `canonicalUnit` was never called from the mock even before COMMIT 3),
  out of every commit's declared scope so far.
- **The mock's costing engine omits freight/insurance/import-duty/inland entirely** — pre-existing
  simplification; only the goods/quantity normalization (COMMIT 3's actual subject) was added to
  `recalculateCosting`.
- **`FactoryEmailService.send`'s attachment path has no dedicated unit test** for the actual
  `MimeMessageHelper` multipart construction — coverage is one integration test asserting the mocked
  service was called with the right attachment list, not that the MIME message it would build is
  correct.
- **`IllegalStateException` from `transition()` is unmapped to a specific HTTP status** — falls into
  the generic `Exception.class` handler (500). Intentional: every current call site is audited to
  never trigger this path in production, so it functions as a defensive assertion, not a user-facing
  error path today.
- **Revision-mode's attachment upload window is unreachable in the current UI flow** —
  `createCustomerChangeRevision` is one atomic call, so `onCreated()` navigates away before any
  `createdId`-gated attachment section could be used. Not a regression (the old inline revision UI
  never supported attachments either); attachments for a new revision are added on its own detail
  page after navigation.
- **Migration numbering**: this branch uses `V65`/`V67`/`V68`/`V69`/`V71`, deliberately **skipping
  V66** — claimed by an untracked file in the parallel `feat/special-money-requests` worktree
  (`V66__special_money_request_schema.sql`), checked live against every open worktree at the time.
  Separately and more seriously: the local (unpushed) branch `docs/flyway-version-collision-audit`
  records that **production is at `V55` ("quotation doc terms") from an unmerged branch**, while
  `main` and UAT are at `V54` — and **this branch has its own, different `V55`**
  (`V55__attendance_daily_activation.sql`, confirmed present in
  `backend/src/main/resources/db/migration/`). This means this branch's own `V55` would be **silently
  skipped on production** (Flyway sees `V55` already applied — a different migration under the same
  version number — and moves on) while applying normally on UAT and in this branch's own dev/test
  runs. This will not surface as a migration error anywhere; it will surface as missing
  tables/columns at runtime on production only. This must be resolved (by renumbering the colliding
  migration on whichever branch merges second) before this branch or the colliding one reaches
  production. See that audit branch for the full cross-branch/cross-environment version map.
- **Step 2 remains NOT independently deployable.** Step 1's ticket-level `submit()` still 409s and
  the replacement chain does not yet produce a price on its own — a newly created deal cannot be
  priced, quoted, or advanced past the pre-quote stages until Step 1 and Step 2 ship together.
- **The branch is 6 commits behind `origin/main`** as of this rewrite (fetched 2026-07-21) — all in
  attendance/backfill work (`8b513b8`, `f2e55fc`, `d42256b`, `ba187a5`, `7755377`, `dc65298`), none
  touching `sales.*` or `pricingrequest`/`factoryquote`/`pricingcosting` files by inspection, so a
  clean rebase is expected but has **not** been performed as part of this pass — do this before
  opening the PR, and re-run `./mvnw -B clean verify` + the frontend suite in full afterward.

## 9. Suggested Next Prompt

1. Fetch and rebase this branch onto current `origin/main` (6 commits ahead as of 2026-07-21, all
   attendance-backfill work with no expected file overlap) — resolve any conflicts, then re-run
   `cd frontend && npm run lint && npm test -- --run && npm run build` and
   `cd backend && ./mvnw -B clean verify` in full before opening the PR.
2. Before or immediately after merging, resolve the `V55` production/branch migration-number
   collision recorded in `docs/flyway-version-collision-audit` — this branch's
   `V55__attendance_daily_activation.sql` will be silently skipped on production if the unmerged
   branch that claimed production's `V55` ("quotation doc terms") merges first, or vice versa.
   Whoever merges second renumbers.
3. Once merged, coordinate deploy sequencing with whatever branch completes Step 1's pricing-output
   path — Step 2 alone cannot progress a deal past pre-quote stages (§8).
4. Deferred polish, not blocking: denser per-factory response history, richer attachment previews
   (inline image/PDF preview) for both attachment surfaces, guided CEO review affordances, a
   dedicated `FactoryEmailServiceTest` for the multipart MIME construction path, `mockApi.js`'s
   `receiveFactoryQuote` gaining the same unit-basis validation the real service has always had, and
   the mock's costing engine eventually modeling freight/insurance/duty/inland if Step 2 totals ever
   need end-to-end verification under `VITE_USE_MOCKS=true` alone.
