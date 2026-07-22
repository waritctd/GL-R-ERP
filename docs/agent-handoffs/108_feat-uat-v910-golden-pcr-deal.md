# Agent Handoff

## Task
Author V910 — a new, forward-only UAT seed migration (`db/migration-uat/V910__uat_golden_pcr_deal.sql`)
that inserts ONE "golden" deal, walked all the way through the pricing-request (PCR) redesign chain
(V59-V84) to `CLOSED_PAID` + an approved commission, on the direct-factory-import sourcing path. Testers
need one pre-baked deal to inspect the back half of the flow (factory quote, costing, CEO pricing
decision, customer quotation, order confirmation, deposit/payment, factory PO, delivery, close,
commission) against the real API without hand-walking every step. V900-V909 seed the older 14-stage
deal-pipeline demo (V50-V54) but never exercise the PCR chain at all.

## Branch
`feat/uat-v910-golden-deal`, worktree `.claude/worktrees/uat-v910-seed`

## Base Commit
`origin/uat` @ `2fe6095` (Merge pull request #275 from waritctd/sync/uat-to-main-v84 — uat was just
synced to frozen main, migrations to V84 + UAT seeds V900-V909)

## Current Commit
Not yet committed at the time of writing this handoff — see the accompanying report for exact commit
instructions; this file is being added in the same commit.

## Agent / Model Used
Claude Sonnet 5 (implementation)

## Scope

### In Scope
- New forward-only migration `db/migration-uat/V910__uat_golden_pcr_deal.sql`.
- New `FlywayMigrationTest.assertUatGoldenPcrDeal()` assertion, called from
  `uatProfileCombinedLocationsApplyToACleanDatabase()`.

### Out of Scope
- No changes to `db/migration` (real schema), no changes to any application/service code.
- No changes to V900-V909 (frozen, checksummed on hosted UAT).
- No push, no merge to `uat`, no deploy.

## Files Changed
- `backend/src/main/resources/db/migration-uat/V910__uat_golden_pcr_deal.sql` (new): the golden deal
  seed — customer/contact/project, ticket `UAT-GOLD-01` (2 items), pricing_request `PCR-UAT-GOLD-01`
  (+2 items, +8 narrative events), factory_quote `FQ-UAT-GOLD-01` (+2 items), pricing_costing
  `PC-UAT-GOLD-01` (+2 items with full landed-cost math), pricing_decision `PD-UAT-GOLD-01` (+2 items,
  35% margin), customer quotation `QN-UAT-GOLD-01` (extends `sales.quotation`/`quotation_item` per
  V74's design, not a separate table) with 2 items, deposit_notice `DN-UAT-GOLD-01`, 2 payment_receipt
  rows (deposit+balance, summing to the full payable), factory_purchase_order `PO-UAT-GOLD-01` (+2
  items, RECEIVED), 1 delivery_record (+2 items, full delivery), `ticket_item.qty_delivered` backfill,
  3 `deal_activity` rows, 1 `invoice_details` row + 1 APPROVED `SALE` `commission_record`.
- `backend/src/test/java/th/co/glr/hr/FlywayMigrationTest.java`: added
  `assertUatGoldenPcrDeal()` (asserts the terminal state of every link in the chain: ticket
  CLOSED_PAID/FULLY_PAID/FULLY_DELIVERED, pricing_request QUOTATION_ACCEPTED, pricing_decision
  APPROVED, quotation ACCEPTED, factory PO RECEIVED, 1 delivery record, full item delivery, payment
  receipts summing to 584,007.07, 1 APPROVED SALE commission) and wired the call into
  `uatProfileCombinedLocationsApplyToACleanDatabase()`, right after the existing
  `assertUatDealPipelineSeed()` call. That existing assertion's counts (`stage_count`,
  `receipt_count=5`, `delivery_count=3`, etc., all filtered by `code LIKE 'UAT-TKT-%'`) are untouched —
  verified unaffected since `UAT-GOLD-01` never matches that LIKE pattern.
- `docs/agent-handoffs/108_feat-uat-v910-golden-pcr-deal.md` (this file, new).

## Commands Run
```bash
git fetch origin
git worktree add -b feat/uat-v910-golden-deal .claude/worktrees/uat-v910-seed origin/uat

# throwaway local Postgres DB (NOT Testcontainers — Docker was down; local PG on :5432, role=$USER)
psql -h localhost -U ploy_warit -d postgres -c "CREATE DATABASE glr_erp_v910_test OWNER ploy_warit;"

cd backend
export TEST_DB_URL="jdbc:postgresql://localhost:5432/glr_erp_v910_test"
export TEST_DB_USERNAME="ploy_warit"
export TEST_DB_PASSWORD=""
./mvnw -B -Dtest=FlywayMigrationTest -Dtest.fork.count=1 test        # fast path, ran first
./mvnw -B -Dtest.fork.count=1 clean verify                            # full suite

# idempotency check: re-ran V910's own SQL a second time against an already-V910-migrated DB via
# psql directly (every INSERT reported "INSERT 0 0", one UPDATE reported "UPDATE 0", row counts
# unchanged) — confirms the WHERE NOT EXISTS guards hold on a genuine re-apply, not just Flyway's
# own once-per-version bookkeeping.

psql -h localhost -U ploy_warit -d postgres -c "DROP DATABASE IF EXISTS glr_erp_v910_test;"
psql -h localhost -U ploy_warit -d postgres -c "DROP DATABASE IF EXISTS glr_erp_v910_full;"
```

## Test / Build Results
- Backend `FlywayMigrationTest` alone: **4/4 pass** (all migrations apply clean; demo profile clean;
  UAT profile clean + both seed assertions — old `assertUatDealPipelineSeed` and new
  `assertUatGoldenPcrDeal` — pass; UAT quick-login personas pass).
- Backend full suite (`./mvnw clean verify`, local Postgres via `TEST_DB_URL`, fork.count=1):
  **1123 tests run, 0 failures, 0 errors, 2 skipped** (pre-existing skips, unrelated to this change).
  Jacoco coverage check: all met. `BUILD SUCCESS`, ~7 min.
- Integration tests **ran** (not skipped) — Docker was down, so resolved via local Postgres +
  `TEST_DB_URL`/`TEST_DB_USERNAME`/`TEST_DB_PASSWORD` on a throwaway database, per
  `PostgresTestSupport`. Testcontainers path not exercised this run.
- Frontend: not touched, not run (no frontend files changed).
- Manual idempotency re-apply of V910's raw SQL against an already-migrated DB: 0 new rows, 0 errors.
- Manual DB spot-check (psql) of the golden deal's end state: ticket `closed`/`CLOSED_PAID`/
  `COMPLETED`/`FULLY_PAID`/`FULLY_DELIVERED` with `close_confirmed_by` set; pricing_request
  `QUOTATION_ACCEPTED`; pricing_decision `APPROVED`; quotation `ACCEPTED`; factory PO `RECEIVED`;
  payment_receipt sum = `584007.07`; commission `SALE`/`APPROVED` = `584007.07`.

## Authz Evidence
No authorization change in this task (pure data seed migration + a test assertion; no service, role
gate, scope/filter, or route change).

## Decisions Made
- Ticket code `UAT-GOLD-01` (not `UAT-TKT-%`) so the existing `assertUatDealPipelineSeed` counts are
  provably unaffected, and so it reads unambiguously as the one hand-curated "golden" fixture rather
  than one of the 14 pipeline-demo tickets.
- Every business-key prefix (`PCR-/FQ-/PC-/PD-/PO-/QN-/DN-/UAT-INV-/UAT-RCPT-` all suffixed
  `...-GOLD-01`) is deliberately outside every range V900-V909 already claimed, including V909's own
  `QN-UAT-00xx` quotation-number range, per the standing "env-distinct identifiers" rule from prior
  incidents (see `flyway-checksum-repair-uat` / `uat-seed-v900-ordering-trap` memory notes).
- Every insert is guarded by `WHERE NOT EXISTS` on the table's real unique key (documented inline,
  per section, in the migration file itself) — verified by a manual full re-apply producing zero new
  rows and zero constraint violations.
- Sourced two ticket items to keep the chain provably multi-line (not a degenerate single-row case)
  while keeping the arithmetic traceable by hand: landed cost worked from a 36.50 THB/USD FX rate and
  a flat 10% import duty; selling price at a 35% margin; deposit/balance receipts split 50/50 and
  summed to the exact quotation total (584,007.07) so `payment_status` derives cleanly to
  `FULLY_PAID`.
- `sales.quotation`/`sales.quotation_item` are extended in place (not a new `customer_quotation`
  table) — confirmed by reading V74's own header, which records the owner's explicit decision to do
  this; both the legacy rendering columns and Step 4's business columns are populated on the same row.
- Commission modeled as a plain automated `SALE` kind (not a V84 manual kind) tied to the deal via
  `source_ticket_id`, with full V35 dual-approval chain and V79's `deal_payable_amount_snapshot`
  matching `actual_received` exactly (`deal_amount_mismatch = FALSE`), mirroring V903's existing
  pattern for a "no problems" example row.
- Added a `pricing_request_event` narrative trail (8 rows) for tester readability — safe because
  `event_kind` is free text with no CHECK constraint (confirmed in V59), unlike `ticket_event.kind`.

## Assumptions
- `order_confirmed_by` (Step 6 bridge) and the quotation's `issued_by` are both the sales rep
  (`GLR-0005`), and the factory-quote/costing/PO chain is driven by import (`GLR-0004`) — a reasonable
  division of labor inferred from each step's own migration header comments (Step 2 = "Import-
  controlled costing submission", Step 3 = "CEO Selling Price Decision", Step 6 = the sales-facing
  bridge), not verified against actual `OrderConfirmationService`/`CustomerQuotationService` code paths
  (out of scope for a pure-SQL seed task).
- `calculation_config_id`/`calculation_config_version` on `pricing_costing_item` are BIGINT/INTEGER
  NOT NULL with **no FK** (confirmed by reading V61 directly) — used literal `1`/`1` as placeholders,
  since no real calc-config table exists in this schema to reference.
- Actual landed cost on the factory PO (`404,178.50` THB) was set to exactly match the costing
  estimate (no variance) to keep `deal_amount_mismatch = FALSE` simple and unambiguous for testers;
  a reviewer wanting a variance-reporting example would need a second fixture.

## Known Risks
- This seed was only verified against a local throwaway Postgres, not Testcontainers or the actual
  hosted UAT database — the migration's own idempotency guards (`WHERE NOT EXISTS` on real unique
  keys, checked per-section in the file) are the defense against any hosted-data collision, per the
  standing lesson from `V909`'s own history (recorded in this repo's memory as
  `uat-seed-v900-ordering-trap` / `flyway-checksum-repair-uat`), but a live hosted-UAT deploy is the
  only way to be fully certain no tester-made row already collides on one of the new business keys.
- No frontend verification was performed (not requested, and no frontend code was touched) — a tester
  driving this fixture through the actual UI has not been exercised by this agent.

## Things Not Finished
- Nothing outstanding for the stated task. The migration + test assertion + full backend build are
  all green and idempotency is verified.

## Recommended Next Agent
Claude Opus review (per the standing "Sonnet implements / Opus reviews" loop), focused on: (1) the
schema/constraint mapping in the report is accurate against the actual migration files, (2) every FK
join in V910 resolves through the correct natural key with no silent cross-join/duplication risk, (3)
the numeric chain (landed cost -> selling price -> quotation totals -> deposit/balance -> commission)
is internally consistent, which was spot-checked in psql but should be independently re-derived by the
reviewer.

## Exact Next Prompt
```
Review docs/agent-handoffs/108_feat-uat-v910-golden-pcr-deal.md and the diff on
feat/uat-v910-golden-deal (worktree .claude/worktrees/uat-v910-seed) against origin/uat @ 2fe6095:
backend/src/main/resources/db/migration-uat/V910__uat_golden_pcr_deal.sql and the
assertUatGoldenPcrDeal() addition in backend/src/test/java/th/co/glr/hr/FlywayMigrationTest.java.
Re-derive the landed-cost/selling-price/quotation/deposit/commission numeric chain independently and
confirm it's internally consistent; verify every JOIN key against the actual current-state schema
(V50-V84); confirm no unique-key collision risk against hosted UAT's real V900-V909 data. Do NOT push
or merge — this is a review-only pass. Report back with a verdict (ship / fix-then-ship / rework) and,
if issues are found, the exact lines to change.
```
