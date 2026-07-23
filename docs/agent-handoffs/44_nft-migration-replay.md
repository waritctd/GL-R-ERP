# Agent Handoff

## Task
Round C (final round) of the 3-round pre-UAT NFT hardening pass (Round A: [42_nft-2-live-load-smoke.md](42_nft-2-live-load-smoke.md); Round B: [43_nft-payroll-concurrency.md](43_nft-payroll-concurrency.md)). Replay `main`'s full Flyway chain (`db/migration` V1–V41) against a disposable Postgres, then load a genuinely prod-shaped seed (the unmerged `uat` branch's `db/migration-uat/V900–V905`, ~32 employees + dual-track sales fixtures) on top, to surface schema issues that only appear against realistic, populated data. **This surfaced a real finding** — recorded below, not fixed on this branch.

## Branch
`test/nft-migration-replay`

## Base Commit
`46311eccc1e3353b19e59f444176a367f11b9a39` (main tip; same base as Rounds A/B, cut separately)

## Current Commit
Not committed yet — awaiting user confirmation (same checkpoint pattern as Rounds A/B).

## Agent / Model Used
Claude Opus (micro-plan) → Claude Sonnet 5 (execution, this handoff)

## Scope

### In Scope
- Read-only extraction of `db/migration-uat/V900–V905` from the `uat` branch via `git show` (no checkout, no merge) into the session scratchpad.
- A disposable, uniquely-named Docker Postgres + backend image, isolated from the normal dev `docker-compose` stack (different container names, volume, ports).
- Running the full `db/migration` chain (default profile — no demo/uat profile) against the empty replay DB, then loading the extracted seed on top via `psql`.
- A reusable script (`scripts/nft/migration-replay.sh`) and README section, decoupled from the `uat` branch specifically (takes any `SEED_DIR`).

### Out of Scope
- Merging the `uat` branch, or any of its non-seed changes, into `main`.
- Fixing the schema/constraint conflict this round surfaced (see Known Risks) — flagged for the user, not fixed here, per CLAUDE.md's "no business-logic changes without explicit request" and "small reviewable diffs."
- CI wiring for this script.
- The frozen sales/CRM stack (the finding touches `sales.deposit_notice`, but no sales code/migration was changed).
- Push / PR / merge.

## Files Changed
- `scripts/nft/migration-replay.sh` (new) — reusable replay harness; builds the backend image, runs it once against a throwaway Postgres to apply Flyway, then loads a `SEED_DIR` of ordered `*.sql` files via `psql`; auto-tears-down on exit via a trap.
- `scripts/nft/README.md` — added a `migration-replay.sh` section (prereqs, usage, env vars, what it does/doesn't prove).
- No `db/migration*` file changed. No merge of `uat`.

## Commands Run
```bash
git checkout main && git checkout -b test/nft-migration-replay

# iCloud-conflict-copy guard (none found this time)
find backend/src -name "* 2.*" -print

# Disposable replay Postgres (uniquely named, never the dev stack's pgdata)
docker run -d --name glr-replay-db -e POSTGRES_DB=replaydb -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres -p 55432:5432 -v glr-replay-pgdata:/var/lib/postgresql/data \
  postgres:16-alpine

docker build -t glr-replay-backend:latest ./backend

# Run once, default profile (no demo/uat) -> applies exactly db/migration V1-V41
docker run -d --name glr-replay-app \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://host.docker.internal:55432/replaydb" \
  -e SPRING_DATASOURCE_USERNAME=postgres -e SPRING_DATASOURCE_PASSWORD=postgres \
  -e APP_FLYWAY_ENABLED=true -p 8091:8080 glr-replay-backend:latest
# -> "Successfully applied 39 migrations to schema hr, now at version v41
#     (execution time 00:00.347s)"; app started.
docker stop glr-replay-app

# Read-only extraction from the uat branch (no checkout/merge)
for f in V900__uat_reference_and_employees V901__uat_attendance V902__uat_leave_and_overtime \
         V903__uat_sales V904__uat_resync_employee_code_seq V905__uat_dual_track_sales; do
  git show "uat:backend/src/main/resources/db/migration-uat/${f}.sql" > "<scratchpad>/${f}.sql"
done

# Load the seed, in version order, via psql
for f in V900... V901... V902... V903... V904... V905...; do
  docker exec -i glr-replay-db psql -v ON_ERROR_STOP=1 -U postgres -d replaydb < "<scratchpad>/${f}.sql"
done
# -> V900, V901, V902 load cleanly. V903 fails partway (see Known Risks). V904, V905 load
#    (their statements don't depend on V903's failed rows).

docker rm -f glr-replay-app glr-replay-db
docker volume rm glr-replay-pgdata
docker image rm glr-replay-backend:latest

# Wrote scripts/nft/migration-replay.sh + README section, then re-ran the actual
# committed script end-to-end to confirm it reproduces the same result and cleans up:
SEED_DIR=<scratchpad>/uat-seed bash scripts/nft/migration-replay.sh
# -> same V903 failure, script exits non-zero as designed, full teardown confirmed
#    (docker ps/volume ls/images all clean afterward).
```

## Test / Build Results
- **Flyway chain (V1–V41) against empty replay DB: PASS.** 39 migrations applied in 0.347s (`flyway_schema_history`: 40 rows — including the V1 baseline row — all `success=true`). No checksum/validation errors.
- **Seed load (V900–V905) against the fully-migrated schema: PARTIAL FAIL.** V900, V901, V902, V904, V905 load with 0 errors. **V903 fails** with `duplicate key value violates unique constraint "ux_deposit_notice_doc_number"` — see Known Risks for root cause.
- **`flyway_schema_history` cleanliness: PASS.** 40 rows, 0 failed, and none of the psql-applied seed files appear in it — confirms the seed load never touched Flyway's tracked state (as intended; this was a one-off `psql` load, not a tracked migration).
- **Row counts after replay:** 93 employees, 8 tickets, 1 deposit_notice (of the 2 the fixture intended — the failing statement's transaction rolled back atomically), 3 quotations, 22 attendance punches, 4 leave requests.
- Backend `./mvnw -B clean verify`: not run — no Java/migration source changed this round (only a new shell script + docs).

## Decisions Made
- **Approach: fresh DB → full V1–V41 → seed on top**, not an interleaved mid-chain replay. The `uat` seed's V900–V905 files are explicitly numbered to run after V41 and resolve foreign keys against tables spanning the whole V1–V41 chain (V6 tickets, V16 customers, V17 documents, V29 deposit-notice rename, V35 dual-approval) — they cannot load earlier. This is the only replay ordering possible against the current migration set.
- **Skipped the planned throwaway `V999` synthetic probe.** The Opus micro-plan suggested authoring a synthetic populated-table migration to demonstrate the harness's forward-looking value. That became unnecessary: **the real seed load itself surfaced a genuine schema/data conflict** (see Known Risks), which is a far more meaningful proof of the harness's value than a synthetic probe would have been.
- **Standalone `docker run` containers, not docker-compose**, with unique names/volume/ports (`glr-replay-*`, ports 55432/8091) — completely isolated from the dev stack's `pgdata`/`backend_uploads`, so a normal `docker compose down -v` during dev work cannot touch replay state and vice versa.
- **`psql -f` for the seed, not Flyway** — these are static, one-off INSERTs; running them through Flyway would pollute `flyway_schema_history` with entries that don't exist on any real environment. Confirmed via the "seed files absent from `flyway_schema_history`" check.
- **Script takes `SEED_DIR`, not a hardcoded `git show uat:...`** — decouples `migration-replay.sh` (committed to `main`) from an unmerged branch that may be renamed/merged/deleted later. The `uat`-specific extraction is documented as a manual prep step in the README instead.

## Assumptions
- Docker available locally with capacity to run two containers + build one image concurrently with (potentially) the normal dev stack also running — verified no port/name collisions since replay uses entirely distinct names/ports.
- The `uat` branch's `db/migration-uat/V900–V905` remain the most realistic available prod-shaped seed as of this session; if `uat` is ever merged or its migration-uat files move, the README's extraction command will need updating (flagged, not fixed, since that's speculative future maintenance).

## Known Risks
- **Real finding, not fixed on this branch:** `V41__deposit_notice_integrity.sql` (already on `main`) adds:
  ```sql
  CREATE UNIQUE INDEX IF NOT EXISTS ux_deposit_notice_doc_number
      ON sales.deposit_notice(doc_number) WHERE doc_number IS NOT NULL;
  ```
  This enforces `doc_number` to be globally unique across `sales.deposit_notice`. But the `uat` branch's `V903__uat_sales.sql` seed models a **document revision** — the same `doc_number` (`QN-2026-00001`) appearing on two rows with `version` 1 and 2 ("Original issued document" → "Revision bumping revision_no; original retained"), which is exactly the kind of assumption `V41`'s own comment says it's guarding ("Guard deposit notice numbering/version assumptions that the services rely on"). The seed's data model and `V41`'s constraint directly disagree: `V41` assumes `doc_number` is unique per row; the seed assumes it can repeat across versions of the same document.
  - This was undetectable against an empty DB — `V41` applies cleanly with 0 data present. It only surfaces when a document-revision scenario is actually inserted, which is exactly what this round's replay was designed to find.
  - **Not fixed here** — could mean either the `V41` constraint should be composite (e.g. `UNIQUE(ticket_id, doc_number, version)` or similar, matching the sibling `ux_deposit_notice_ticket_version` index already in the same migration) or the `uat` seed's revision-modeling assumption is stale relative to `main`'s current schema (the `uat` branch may predate `V41`, or was authored independently). Determining which is correct requires domain knowledge of whether "revision reuses `doc_number`" or "revision gets a new `doc_number`" is the intended product behavior — that's a decision for the user/product owner, not something to guess at in an NFT round.
  - Downstream effect observed: `sales.deposit_notice` ended up with 1 row instead of the seed's intended 2 (the failing INSERT's both rows rolled back atomically — INSERT is all-or-nothing per statement), and any later seed statements depending on the second (revision) row would silently not have it. `V904`/`V905` happened to not depend on it, so they still loaded, but this is fragile — a different seed shape could have cascaded further.
- The `migration-replay.sh` script itself is a housekeeping tool going forward — it currently only validates the CURRENT V1–V41 chain against the CURRENT `uat` seed. It should be re-run whenever either side changes (a new migration lands on `main`, or the `uat` seed / a future replacement prod-shaped dataset is updated) — it is not wired into CI, so this is a manual pre-UAT checklist item, not an automated gate.
- What this round does **not** prove (stated honestly, per the plan): true *future*-migration-ordering safety — i.e., how a brand-new migration (a hypothetical `V42+`) would behave against already-populated tables. That requires an actual future migration to test against; this round could only test today's V1–V41 against today's best-available seed, which is what it did, and that was sufficient to find a real issue.

## Things Not Finished
- The `V41`/`uat`-seed `doc_number` uniqueness conflict above — needs a product/domain decision, not an NFT-round fix.
- No further NFT rounds planned in this pass (this was the 3rd and last of the originally scoped set: live load-smoke, payroll concurrency, migration replay). Broader items from the original backlog (environment parity, backup/restore drill, secrets handling, PDPA pass, mobile matrix, accessibility pass, rollback rehearsal) remain explicitly deferred per the original plan.
- Push / PR / merge of any of the three rounds' branches — deliberately not done, needs explicit user confirmation.

## Recommended Next Agent
User decision point — no further agent work is queued. If the user wants the `doc_number` conflict resolved, that should be its own scoped branch/PR with explicit product input on the correct semantics (composite uniqueness vs. seed-data fix), not bundled into this NFT round.

## Exact Next Prompt
```
All 3 rounds of the pre-UAT NFT hardening pass are complete and committed locally on separate
branches (not pushed):
  - test/nft-2-live-load-smoke      (docs/agent-handoffs/42_nft-2-live-load-smoke.md)
  - test/nft-payroll-concurrency    (docs/agent-handoffs/43_nft-payroll-concurrency.md)
  - test/nft-migration-replay       (docs/agent-handoffs/44_nft-migration-replay.md)

Round C surfaced a real, unfixed finding: V41__deposit_notice_integrity.sql's
ux_deposit_notice_doc_number UNIQUE index conflicts with the uat branch's V903 seed, which
models a document revision by reusing the same doc_number across two deposit_notice rows
(version 1 and 2). This needs a product decision (should doc_number be unique per revision-set
or globally unique?) before any fix — do not guess at the correct semantics.

Next: get the user's decision on (a) whether/how to push and open PRs for the three completed
NFT rounds, and (b) whether to scope a follow-up branch for the doc_number conflict once the
correct semantics are confirmed with a domain owner.
```
