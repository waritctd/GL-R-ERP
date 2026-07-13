# Agent Handoff

## Task
Bring the UAT test artifacts (test-case doc, seed migration, automated pytest harness) up to date
with the post-quotation dual-track sales flow that was forward-ported and merged into `uat`
(migrations V37-V39: `ticket_item.unit_basis` PIECE/SQM, `ticket_item.manual_price`/
`manual_override_reason`, `ticket.payment_status`/`fulfillment_status`, `fx_rates.source`/
`fetched_at`, dual-track lifecycle, BOT FX, remaining-invoice renderer — see
`docs/agent-handoffs/40_uat-merge-main-sales-flow.md` and `39_feat-sales-post-quotation-flow.md`).
**Not pushed** — local commits only, per instructions; the orchestrator reviews and pushes.

## Branch
`uat`

## Base Commit
`b78ac53` (uat tip at start of this task — "fix(uat): enable Flyway out-of-order …")

## Current Commit
4 new commits on top of the base (not pushed) — see Commands Run for the exact SHAs.

## Agent / Model Used
Claude Sonnet 5 (implementation agent, per orchestrator prompt).

## Scope

### In Scope
- Update `ERP Documentation/11_UAT_Test_Cases.md`: baseline note (V36 -> V39), extend §7 with
  TKT-10..TKT-18 for the dual-track flow + TKT-19 for BOT FX (manual/config).
- New seed migration `backend/src/main/resources/db/migration-uat/V905__uat_dual_track_sales.sql`
  (entry-point + 2 mid-flow ticket fixtures).
- Extend `tools/uat-tests/test_tickets.py` with pytest cases for the new endpoints.
- Run backend `./mvnw -B clean verify` and the pytest harness, fix anything broken, record results.
- Write this handoff.

### Out of Scope
- Pushing or merging to any remote.
- Any new ERP feature work; no business-logic changes beyond the bug fix described below (which was
  required to make the existing, already-merged dual-track feature actually usable — not new scope).
- Editing the already-applied `V900`-`V904` migrations.

## Bug found and fixed (real backend bug, not a seed issue)

While seeding V905, `mvnw verify`'s `FlywayMigrationTest` and the local docker-compose UAT stack both
crashed applying V905 with `value too long for type character varying(20)`. Root cause: `V6` (initial
sales schema) created `sales.ticket.payment_status VARCHAR(20)`. `V39` ("ticket dual track status")
intended to widen it to `VARCHAR(40)` via `ADD COLUMN IF NOT EXISTS payment_status VARCHAR(40)` — but
`ADD COLUMN IF NOT EXISTS` is a no-op when the column already exists, so it silently left the column
at `VARCHAR(20)`. Two of the real state-machine values are longer than 20 chars
(`DEPOSIT_NOTICE_ISSUED` = 21, `AWAITING_FINAL_PAYMENT` = 22), so **any ticket reaching those states
via the real app** (`TicketService.issueDepositNotice()` / `markGoodsReceived()`) would 500 in
production today, independent of this seed. Confirmed via Flyway startup log:
`WARN ... DB: column "payment_status" of relation "ticket" already exists, skipping`.

**Fix:** new migration `backend/src/main/resources/db/migration/V40__widen_ticket_payment_status.sql`
— `ALTER COLUMN payment_status TYPE VARCHAR(40)` (widening, safe on existing data/rows) + the same
fix for the never-used sibling `delivery_status` column for consistency. This is a shared
`db/migration` file (not UAT-only) since it fixes a real app-wide bug, not a seed-data concern. Landed
as V40 (next free real-migration number after V39); `application-uat.yml`'s existing
`spring.flyway.out-of-order: true` already covers V40 landing below the live UAT DB's V904 the same
way it covers V37-V39.

A second, related bug was caught by the same Flyway run: `sales.ticket_item.qty` is `NOT NULL` at the
DB level (V6) even for `unit_basis='SQM'` items where `qty_sqm` is the "real" quantity — the seed
initially tried `qty=NULL` for an SQM item and violated the constraint. This is **not** a DB bug: the
real frontend (`TicketCreateModal.jsx`) always sends `Number(item.qty) || 0` and never a literal
`null`, so `qty=0` for SQM items is the correct, already-established convention. Fixed in both V905
(the seed) and `test_tkt18_unit_basis_sqm_roundtrip` (the new pytest case) to send `qty=0`/`"0"`
instead of `null` for SQM items — this matches existing behavior, not a new workaround.

A third issue was caught only by running the pytest cases live (not by `mvnw verify`, since it's an
API precondition ordering issue, not a migration issue): `TicketService.issueImportRequest()` requires
`paymentStatus == "DEPOSIT_NOTICE_ISSUED"` **exactly** — not `DEPOSIT_PAID`. My first draft of
`test_tkt14_fulfillment_walk_and_auto_flip` / `test_tkt15_confirm_final_payment` called `deposit-paid`
before `import-request`, which moved `paymentStatus` past the state the IR endpoint requires and got a
409. Fixed by reordering to `deposit-notice -> import-request -> deposit-paid` (the fulfillment track
can start once the deposit notice is issued, independent of whether the deposit has actually been
paid yet — this is the real, correct precondition per `TicketService.java`, re-confirmed by reading
the source before fixing the test).

## Files Changed
- `ERP Documentation/11_UAT_Test_Cases.md` — baseline note updated to V39 + dual-track/remaining-
  invoice/override/unit-basis summary; §7 extended with TKT-10..TKT-18 (dual-track flow) and TKT-19
  (BOT FX, marked ⚙️ manual/config with the CEO-override/manual-FX on-demand alternative noted);
  added the ⚙️ legend entry to §1.
- `backend/src/main/resources/db/migration-uat/V905__uat_dual_track_sales.sql` (new) — seed fixtures,
  see below.
- `backend/src/main/resources/db/migration/V40__widen_ticket_payment_status.sql` (new) — bug fix, see
  above.
- `tools/uat-tests/test_tickets.py` — 9 new pytest cases (TKT-10..TKT-18) + a `quotation_issued_ticket`
  helper that walks a fresh ticket to `status=quotation_issued` with both dual-track fields still
  `NULL`, reusing the existing `approved_ticket`/`price_proposed_ticket` helpers from `helpers.py`.

## V905 fixtures (ticket codes + states + columns exercised)
- **`UAT-TKT-06`** (entry-point) — `status=quotation_issued`, `payment_status=NULL`,
  `fulfillment_status=NULL`. Customer "บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด" (new). 2 items: one
  `unit_basis=PIECE`, one `unit_basis=SQM` with `qty_sqm=45.5` **and** `manual_price=850` +
  `manual_override_reason` set (exercises the override columns without violating
  `overrideItemPrice()`'s `price_proposed`-only precondition, since this fixture is already past that
  status — see note in the migration header). 1 ISSUED quotation (`QN-2026-00010`). Lets testers walk
  the entire dual-track flow from scratch and download the remaining invoice.
- **`UAT-TKT-07`** (mid-flow A) — `status=quotation_issued`, `payment_status=DEPOSIT_PAID`,
  `fulfillment_status=IR_ISSUED`. Customer "หจก. อีสเทิร์นวิลล่า" (new). 1 `PIECE` item. 1 ISSUED
  quotation (`QN-2026-00011`) + 1 ISSUED deposit notice (`DN-2026-00005`, `deposit_amount=82500`).
- **`UAT-TKT-08`** (mid-flow B) — `status=quotation_issued`, `payment_status=AWAITING_FINAL_PAYMENT`,
  `fulfillment_status=GOODS_RECEIVED`. Customer "บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด" (reused). 2
  `PIECE` items. 1 ISSUED quotation (`QN-2026-00012`). Ready for final-payment/close testing.
- All FKs resolved by business key (customer name / ticket code / employee_code) via subquery, all
  inserts idempotent (`WHERE NOT EXISTS`), following V903's exact conventions.

## Commands Run
```bash
git switch uat && git pull --ff-only origin uat   # already up to date, clean tree
# ... wrote doc + V905 + V40 + pytest cases ...
cd tools/uat-tests
python3 -m venv <scratchpad>/uatvenv && source <scratchpad>/uatvenv/bin/activate
pip install -r requirements.txt
python3 -m pytest --collect-only test_tickets.py          # 19 collected
PYTHON=<venv>/bin/python3 ./run.sh -k "tkt10 or ... or tkt18"   # iterated 5x fixing the 3 bugs above
PYTHON=<venv>/bin/python3 ./run.sh                        # full suite, no -k filter
cd ../../backend
./mvnw -B clean verify
docker ps -a --filter "name=glr-uat-tests" -q | xargs -r docker rm -f   # cleanup
```

## Test / Build Results
- **Pytest collection** (`python3 -m pytest --collect-only test_tickets.py`): **19 items collected**,
  0 errors (10 pre-existing TKT-01..TKT-09 + 9 new TKT-10..TKT-18).
- **Pytest live run, new cases only** (`./run.sh -k "tkt10 or ... or tkt18"` against the local
  docker-compose uat stack, fresh seed incl. V905): **9 passed, 0 failed** (P0 8/8, TKT-18 is P1).
  Docker was available so this ran against the real seeded Postgres + backend, not just collection.
- **Pytest live run, full suite** (`./run.sh`, no filter): **82 passed, 6 skipped, 0 failed**
  (19 deselected = `live_email` marker tests, excluded by default). The 6 skips are all pre-existing
  `manual/UI` markers (ATT-01, AUTH-02, AUTH-05, NOTIF-02, PAY-07, TKT-06 PDF-visual) — none new, none
  related to this change. **P0 64/68** — the 4 non-passing P0s are exactly those 6 skips that carry
  P0 priority (ATT-01, AUTH-02, PAY-07, and TKT-06's PDF-visual skip), all pre-existing manual-only
  cases, not regressions.
- **Backend `./mvnw -B clean verify`:** **BUILD SUCCESS**. Docker was available, Testcontainers ran
  (integration tests not skipped). **326 tests, 0 failures, 0 errors.** `FlywayMigrationTest`'s 3 cases
  all passed, including `uatProfileCombinedLocationsApplyToACleanDatabase` — this is what proves V40 +
  V905 apply cleanly, in order, on a clean DB via the combined `db/migration` + `db/migration-uat`
  locations (V1..V40 then V900..V905). Jacoco coverage check passed.

## Decisions Made
- Fixed the `payment_status VARCHAR(20)` bug as a new shared migration (`V40`) rather than routing
  around it in the seed, because it is a real, already-shipped defect that would 500 on real UAT
  testers reaching `DEPOSIT_NOTICE_ISSUED`/`AWAITING_FINAL_PAYMENT` through the actual UI — not
  something specific to my seed data. This is a minimal, additive, behavior-preserving fix (column
  widen only) consistent with CLAUDE.md's "no business logic changes" rule (it doesn't change any
  logic, only a column's storage width to match what V39's own author clearly intended).
- Put the entry-point fixture's manual-price-override columns on an item that is already past
  `price_proposed` (i.e., the columns are populated directly in the seed, not exercised via a live
  `overrideItemPrice()` call at seed time) since that endpoint's precondition
  (`status == price_proposed`) is incompatible with a `quotation_issued` fixture. TKT-17's pytest case
  covers the *live* override call end-to-end instead, on a freshly-created `price_proposed` ticket.
- Marked BOT FX as TKT-19, explicitly ⚙️ manual/config, with the CEO manual price override / manual FX
  entry noted as the click-testable on-demand alternative — per the task brief's instruction not to
  block sign-off on an external cron dependency.

## Assumptions
- The live, already-seeded UAT Render Postgres has NOT yet had V40/V905 applied (this session had no
  DB access to it) — the safety argument for both rests on: V40 is a pure column-widen (safe on
  existing data, no data loss, no logic change) and V905 is purely additive/idempotent, both covered
  by `application-uat.yml`'s existing `out-of-order: true`. This mirrors exactly the reasoning already
  documented in handoff #40 for V37-V39.
- `ERP Documentation/UAT Deliverables/UAT_Test_Data.xlsx` (gitignored/local) was **not** updated to
  mirror V905 — per Task 4 of the brief, this is noted rather than blocked on.

## Known Risks
1. **V40 has not been applied to the live UAT Postgres yet** — same category of risk already logged
   in handoff #40 for V37-V39 (only proven against a fresh Testcontainers DB and the local
   docker-compose stack so far, not the real out-of-order path against an already-V904'd database).
   The fix is a simple, safe `ALTER COLUMN TYPE` widen with no CHECK constraint and no data
   transformation, so the risk is low, but this should be watched on the next real UAT deploy's
   Flyway log (expect `Migrating schema "hr" to version "40 - widen ticket payment status"` with no
   error, then V905 applying cleanly after it).
2. **This surfaced a real, previously-undiscovered production bug in already-merged code** (the V39
   `ADD COLUMN IF NOT EXISTS` no-op). It was not caught by the original `feat/sales-post-quotation-flow`
   or `uat-merge-main-sales-flow` work because their integration test runs happened to apply V39 to a
   **clean** database (where the column doesn't pre-exist, so the `ADD COLUMN` actually executes and
   the width is 40 as intended) — the bug only manifests on the **out-of-order path against an
   already-seeded UAT DB with V6's original VARCHAR(20) already in place**, which is exactly the
   uat-branch's real deployment shape. Worth flagging to whoever owns `main` too, since `main`'s own
   fresh-deploy path is unaffected but any future environment that inherits an old V6-era schema
   snapshot would hit the same issue.
3. Everything else already logged in handoffs #39/#40 (dual-track `close()` business-rule review,
   `TicketDetailPage.jsx` inline styles, `BotFxFetchService`'s per-call `RestClient`) is unchanged and
   not re-litigated here.

## Things Not Finished
- `ERP Documentation/UAT Deliverables/UAT_Test_Data.xlsx` source spreadsheet was not updated to mirror
  V905 (Task 4, noted not blocked — it's gitignored/local, not part of this repo's tracked state).
- Push/merge to remote is intentionally left to the orchestrator.

## Recommended Next Agent
Claude Opus (orchestrator) — review the 4 commits, especially the V40 bug-fix migration (confirm the
column-widen reasoning and that no CHECK constraint or app code assumed `VARCHAR(20)`), then push
`uat` to `origin/uat` to trigger the `gl-r-erp-uat` Render + Vercel redeploy. Watch the Render deploy
log specifically for `Migrating schema "hr" to version "40 - widen ticket payment status"` followed by
`"905 - uat dual track sales"` applying without a `validate()` failure (same out-of-order path already
proven safe for V37-V39). After deploy, spot-check TKT-11/TKT-14 live (issue a deposit notice, walk
goods-received) against the real hosted UAT stack, since those are exactly the two endpoints the V40
bug would have broken.

## Exact Next Prompt
```text
Repo GL-R-ERP, branch uat (4 new commits on top of b78ac53, NOT pushed). Read CLAUDE.md,
docs/agent-handoffs/00_MASTER_CONTEXT.md, and this handoff
(docs/agent-handoffs/41_uat-dual-track-sales-tests.md) first.

Review this task's commits as a reviewer: (1) the V40 migration
(backend/src/main/resources/db/migration/V40__widen_ticket_payment_status.sql) — confirm the
VARCHAR(20)->VARCHAR(40) widen is safe and that the bug diagnosis (V39's ADD COLUMN IF NOT EXISTS
silently no-op'd against V6's pre-existing narrower column) is correct by re-reading V6, V39, and the
Flyway startup log evidence in the handoff; (2) the V905 seed migration for FK-by-business-key
correctness and idempotency, consistent with V903's conventions; (3) the new pytest cases in
tools/uat-tests/test_tickets.py for endpoint/precondition correctness against
backend/src/main/java/th/co/glr/hr/ticket/TicketService.java. If satisfied, push `uat` to origin to
trigger the gl-r-erp-uat Render + Vercel redeploy, watch the Flyway migration step for V40 and V905
applying cleanly (out-of-order, after the live DB's V904), then spot-check TKT-11 (deposit notice) and
TKT-14 (goods-received) live against the hosted UAT stack — these are exactly the two endpoints the
V40 bug would have broken with a real "value too long" 500 before this fix.
```
