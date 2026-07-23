# Agent Handoff

## Task
Stage L of the release runbook: add real-DB, wrong-way-round authz integration tests for the
sales-authz gaps identified by Opus's spec (`stage-L-authz-tests-spec.md`) — genuine SQL row-scopes
and role gates with zero prior test coverage. Tests only, no product-code change.

## Branch
`test/stage-L-authz-scope-tests`

## Base Commit
`7dcf72a2dd302ddd29bb472beb6ec2ae742aabc2` (origin/main tip at branch creation)

## Current Commit
See `git log -1` after the commit step below (this handoff is written just before committing).

## Agent / Model Used
Claude Sonnet

## Scope

### In Scope
- New file: `backend/src/test/java/th/co/glr/hr/commission/CommissionListScopeIntegrationTest.java` (L1).
- Extend: `backend/src/test/java/th/co/glr/hr/ticket/TicketScopeIntegrationTest.java` (L2).
- Extend: `backend/src/test/java/th/co/glr/hr/pricingdecision/PricingDecisionIntegrationTest.java` (L3 + L4).
- Mutation-check each new guard by temporarily inverting the product-code guard, confirming the
  targeted test(s) go red, then reverting to an empty product-code diff.

### Out of Scope (per spec §0/§7 — already covered, not rebuilt)
- Procurement, CustomerQuotation, OrderConfirmation owner denials.
- Commission create/submit/approve denials (`CommissionAutoCreateIntegrationTest`,
  `ManualCommissionIntegrationTest`).
- PCR (PricingRequest) owner scoping (`PricingRequestFlowIntegrationTest`,
  `PricingRequestRepositoryIntegrationTest`).
- Ticket import/account `appendRoleScope` slices (already in `TicketScopeIntegrationTest`).
- L5 (optional Mockito-to-real-DB promotions) — not attempted; L1-L4 filled the available time.
- No `mockApi.js` / frontend changes.
- No product-code authz change (see mutation-check evidence below — every inversion was reverted;
  final diff is tests-only).

## Files Changed
- `backend/src/test/java/th/co/glr/hr/commission/CommissionListScopeIntegrationTest.java` — **new**.
  4 tests proving `CommissionService.list`'s `sales_rep_id` row-scope (`CommissionRepository.findRecords`):
  rep A excludes rep B's row, rep B excludes rep A's (symmetric), a non-sales role (account/hr/sales_manager)
  sees both reps (documents the no-role-gate design, does not fix it), and a different payroll month is excluded.
- `backend/src/test/java/th/co/glr/hr/ticket/TicketScopeIntegrationTest.java` — extended. Added a
  second sales rep (`salesRepB`/`salesRepBId`), a `createTicket(stage, repId, repName)` overload, and
  2 new tests proving the sales own-only `created_by` predicate in `TicketRepository.findSummaries`/
  `countSummaries` (via both `TicketService.listPage` and `.list`).
- `backend/src/test/java/th/co/glr/hr/pricingdecision/PricingDecisionIntegrationTest.java` — extended.
  Added 6 new tests:
  - `salesAndSalesManager_cannotReachRawPricingDecision` — sales/sales_manager 403 on
    `PricingDecisionService.get`/`.list` (RAW_DECISION_ROLES={import,ceo}).
  - `salesAndSalesManager_cannotReachRawPricingCosting` — same for `PricingCostingService`
    (RAW_COSTING_ROLES={import,ceo}).
  - `salesAndSalesManager_cannotReachRawFactoryQuote` — same for `FactoryQuoteService`
    (RAW_QUOTE_ROLES={import,ceo}).
  - `salesAndSalesManager_canStillReachSalesViewForApprovedDecision` — positive control: the L3
    redaction is not a blackout; both roles still reach `salesView`.
  - `ceo_cannotMutateFactoryQuoteOrCosting_importOnlyRemainsAbleTo` — ceo is read-only:
    403 on `FactoryQuoteService.send`/`.receive`/`.markReadyForCosting` and
    `PricingCostingService.createDraft`/`.recalculate`/`.submit`, plus a DB re-read proving no row
    changed, plus the positive control that import already succeeded (via the same fixture's
    `twoItemSubmittedCosting()` helper).
  Import added: `SendFactoryQuoteRequest` (the others were already imported).

## Commands Run
```bash
git fetch origin
git checkout -b test/stage-L-authz-scope-tests origin/main

# Local Postgres 15 (Homebrew) — Docker was not verified/used.
pg_isready -h localhost -p 5432
psql -h localhost -p 5432 -U "$USER" -d postgres -c 'CREATE DATABASE "glr_stage_l_authz_<ts>";'
export TEST_DB_URL="jdbc:postgresql://localhost:5432/glr_stage_l_authz_<ts>"
export TEST_DB_USERNAME="$USER"
export TEST_DB_PASSWORD=""

cd backend
./mvnw -q -B test-compile

# Targeted new-test run
./mvnw -B -Dtest.fork.count=1 \
  -Dtest='CommissionListScopeIntegrationTest,TicketScopeIntegrationTest,PricingDecisionIntegrationTest' \
  -DfailIfNoTests=true test

# Full existing-coverage + new-test verify (Stage L gate, spec §5)
./mvnw -B -Dtest.fork.count=1 \
  -Dtest='*ScopeIntegrationTest,*AuthorizationIntegrationTest,Commission*IntegrationTest,PricingDecisionIntegrationTest,PricingRequestFlowIntegrationTest,CustomerQuotationIntegrationTest,OrderConfirmationIntegrationTest,ProcurementServiceIntegrationTest' \
  -DfailIfNoTests=true test
# → ran TWICE: once before the mutation-check pass, once after all four reverts (final gate).

# Per-guard mutation-checks (product code edited, single targeted test class run, then reverted —
# see "Authz Evidence" below for each). Verified `git diff --stat -- backend/src/main` was empty
# after every single one, and again once at the very end, before committing.
```

## Test / Build Results
- Frontend: not run — backend-only branch.
- Backend compile (`test-compile`): pass.
- Backend targeted new tests (Commission/Ticket/PricingDecision): **41/41 pass** (4 + 21 + 17 — 19
  pre-existing Ticket tests + 2 new; 17 PricingDecision tests includes 11 pre-existing + 6 new).
  Ran against real Postgres — **integration tests RAN, not skipped**.
- Backend full Stage-L gate run (existing coverage + all new tests, the file list from spec §5):
  **171/171 pass, 0 failures, 0 errors, 0 skipped**, ~4m07s. Ran twice (pre- and post-mutation-check);
  both green. Confirms `AttendanceScopeIntegrationTest`/`OvertimeRetroactiveScopeIntegrationTest`/etc.
  in that glob are also green, alongside every file named in spec §0's "already strong" list that the
  glob matches.
- Lint: not run — no frontend/JS files touched.

## Authz Evidence
Verified against the real Java service — **four new guards, each with a real mutation-check actually
executed** (product code edited, targeted test run, result observed, then reverted — not simulated):

1. **L1 — `CommissionService.list` sales_rep_id row-scope.** Mutated
   `CommissionRepository.findRecords`'s `WHERE (:salesRepId::bigint IS NULL OR cr.sales_rep_id =
   :salesRepId) AND ...` to `WHERE (TRUE) AND ...`. Ran `CommissionListScopeIntegrationTest` (4
   tests): exactly `listAsRepA_excludesRepBsRow` and `listAsRepB_excludesRepAsRow_symmetric` went
   red (rep A now saw rep B's row and vice versa); the non-sales-role and payroll-month tests
   stayed green. Reverted; `git diff` on `CommissionRepository.java` was empty.
2. **L2 — Ticket list sales own-only `created_by` predicate.** Mutated both occurrences of `AND
   (:createdBy::bigint IS NULL OR t.created_by = :createdBy)` in `TicketRepository.findSummaries`/
   `countSummaries` to `AND (TRUE)`. Ran `TicketScopeIntegrationTest` (20 tests): exactly
   `salesListPage_ownOnly_excludesOtherRepsTicket` and `salesList_ownOnly_excludesOtherRepsTicket`
   went red; all 18 import/account/ceo `appendRoleScope` tests stayed green (different predicate,
   untouched). Reverted; `git diff` on `TicketRepository.java` was empty.
3. **L3 — sales/sales_manager cannot reach raw pricing-decision cost/margin.** Widened
   `PricingDecisionService.RAW_DECISION_ROLES` from `Set.of("import", "ceo")` to `Set.of("import",
   "ceo", "sales")`. Ran `PricingDecisionIntegrationTest` (17 tests): **two** went red — the new
   `salesAndSalesManager_cannotReachRawPricingDecision`, and the pre-existing
   `acceptanceScenario_submitApproveAndSalesVisibility` (which independently asserts the same
   `decisionService.get`/`.list` sales-denial near its end). Both failures are the same guard;
   nothing unrelated flipped. Reverted; `git diff` on `PricingDecisionService.java` was empty.
4. **L4 — ceo cannot mutate factory quote / costing (read-only for ceo).** Widened
   `FactoryQuoteService.IMPORT_ROLES` from `Set.of("import")` to `Set.of("import", "ceo")`. Ran
   `PricingDecisionIntegrationTest`: `ceo_cannotMutateFactoryQuoteOrCosting_importOnlyRemainsAbleTo`
   went red at its first assertion (`send`) — with `ceo` added to `IMPORT_ROLES`, `requireRole` no
   longer blocks, so the call falls through to the next guard (`requireMutablePricingRequest`,
   DRAFT_STATUSES) and throws `409 CONFLICT` instead of the expected `403 FORBIDDEN`. AssertJ halts
   at the first failed assertion, so `receive`/`markReadyForCosting`/the costing calls never ran
   that pass — but the role gate's removal is unambiguously what the red test reports (a 409
   instead of a clean 403 is still proof `requireRole` stopped blocking `ceoActor` first). No other
   test flipped. Reverted; `git diff` on `FactoryQuoteService.java` was empty.

Final `git status`/`git diff --stat -- backend/src/main` after all four reverts: **empty** — no
product-code change survives in the branch diff.

## Decisions Made
- Seeded L1's commission rows via the real `CommissionService.createManualCommission` path (a
  `sales_manager` actor, `CommissionKind.ADJUSTMENT`) rather than building a full ticket/deal/
  invoice fixture — `list()` makes no SALE-vs-manual distinction, and this keeps the fixture to
  ~5 lines while still going through the real service+repository.
- L2's "injection guard" case from the spec (a caller passing another rep's id as a filter param)
  was explicitly skipped with a comment: `TicketService.list`/`.listPage` never accept a
  `createdBy` parameter from the caller — the filter is derived solely from
  `actor.role()`/`actor.id()` server-side, so there is no parameter surface to inject through.
- L4's DB re-read asserts `quoteAfter.status()`/`.updatedAt()` unchanged and
  `costingService.list(...).size()` unchanged, rather than re-fetching a costing draft that was
  never created (there is nothing to re-read for a create that never happened; the count check
  covers that).

## Assumptions
- None beyond what's in the spec — this branch did not need to interpret any ambiguous business
  rule; every guard tested is exactly the one the spec pointed at, with role sets/predicates read
  directly from the current source.

## Design Flag — RESOLVED (owner decision 2026-07-24): commission-list role gate ADDED
- **`CommissionService.list` had NO role gate at all** — the `sales_rep_id` filter was bound only
  for `actor.role().equals("sales")`, so ANY authenticated user (incl. a plain `employee`,
  `warehouse`, `qc`, `import`) could read **every** rep's commission amounts via `GET /commissions`.
  L1 surfaced this. **Owner decision: gate it.**
- **FIX (this PR, product-code authz change — stated per CLAUDE.md):** added
  `LIST_VIEWER_ROLES = {sales, sales_manager, ceo, hr, account}` (matches the frontend's
  `canViewCommissions`) + `requireRole`-style gate at the top of `CommissionService.list`. Sales stays
  row-scoped to its own rows; the other four roles keep the full feed (they review/approve/pay it);
  `import`/`employee`/`warehouse`/`qc` now get **403**.
- **Evidence:** `CommissionListScopeIntegrationTest` gained `listAsUnprivilegedRole_isForbidden`
  (import/employee/warehouse/qc → FORBIDDEN); the class now proves BOTH layers (role gate + row-scope),
  5 tests green. **Mutation-check (run + verified by Opus):** neutralizing the gate (`if (false)`) →
  exactly `listAsUnprivilegedRole_isForbidden` reddens, the other 4 stay green; reverted.
- No other authz bug was found — every mutation-check (L1 row-scope, L2 ticket, L3/L4 role gates, and
  this new list gate) produced exactly the expected red test(s) and nothing else.

## Things Not Finished
- L5 (optional Mockito-to-real-DB promotions: `TicketService.setBilling`/`waiveDeposit`/
  `confirmFinalPayment` denials, deposit-notice viewer scope, FX/price-calc config CEO gate,
  catalog price import authz) — spec marked this "only if time remains"; L1-L4 (the must-haves)
  took priority and filled the available session. Left for a follow-up branch if the owner wants
  the extra coverage.
- Docker was not verified in this environment; local Homebrew Postgres 15 was used instead
  (`TEST_DB_URL` + `-Dtest.fork.count=1`), per the project's established method. The throwaway
  database was NOT dropped after this session — clean it up with
  `psql -h localhost -p 5432 -U "$USER" -d postgres -c 'DROP DATABASE "glr_stage_l_authz_<ts>";'`
  if desired (see the exact name in this session's shell history / scratchpad
  `stage-l-dbname.txt`).

## Recommended Next Agent
Claude Opus review (done — Opus independently re-ran the L1 row-scope AND the new commission-list
gate mutation-checks), then owner say-so to merge. The commission-list no-role-gate flag is
**RESOLVED**: the owner chose to add the gate, so this PR now carries one intended product-code
authz change (`CommissionService.list` role gate) alongside the tests — stated here per CLAUDE.md,
with a real-DB wrong-way-round test + mutation-check as its evidence.

## Exact Next Prompt
```
Review PR <PR_URL> (branch test/stage-L-authz-scope-tests) — Stage L sales-authz scope integration
tests. Confirm: (1) the diff is tests-only (backend/src/test only, zero backend/src/main changes),
(2) each of the 4 mutation-check records in PricingDecisionIntegrationTest.java (L3, L4) and the
new inline records in CommissionListScopeIntegrationTest.java (L1) and TicketScopeIntegrationTest.java
(L2) are consistent with what a re-run would show, (3) the design flag on CommissionService.list
having no role gate is worth a decision — either confirm it's intended (nothing to do) or spin up a
follow-up branch to add one. If approved, merge to main; do not merge without explicit owner/Opus
say-so per CLAUDE.md branch discipline.
```
