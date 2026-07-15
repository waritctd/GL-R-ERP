# Agent Handoff

## Task
Fix the failing `FlywayMigrationTest.uatProfileCombinedLocationsApplyToACleanDatabase` test on
`uat` (`./mvnw -B clean verify` → 380 tests, 1 error), and get the full backend verify green.

## Branch
`fix/uat-flyway-test-schemas` (off `uat`)

## Base Commit
`48a412a` (uat tip — "Merge remote-tracking branch 'origin/main' into uat")

## Current Commit
_Not committed — working tree only (no commit/push was requested)._

## Agent / Model Used
Claude Opus 4.8

## Scope

### In Scope
- The `.schemas(...)` drift in the uat test in `FlywayMigrationTest`.
- The fresh-database breakage in the `db/migration-uat` seed that the test fix exposed.

### Out of Scope
- The hosted UAT DB's Flyway history (see **Known Risks** — needs a decision + `flyway repair`).
- Any change to `db/migration` (main's real migrations) or to business logic.
- The demo seed (`db/migration-demo`) — verified unaffected, see **Decisions Made**.

## Files Changed
- `backend/src/test/java/th/co/glr/hr/FlywayMigrationTest.java`: added `"customers", "price_catalog"`
  to the `.schemas(...)` list in `uatProfileCombinedLocationsApplyToACleanDatabase` (line 95), so it
  matches the other two tests **and** `application.yml`'s real `spring.flyway.schemas`.
- `backend/src/main/resources/db/migration-uat/V903__uat_sales.sql`: `sales.customer` →
  `customers.customer` (9 refs, SQL + comments); the version-2 deposit-notice row's `doc_number`
  changed `QN-2026-00001` → `QN-2026-00003`; stale comments about `sales.contact` / the shared
  doc_number corrected.
- `backend/src/main/resources/db/migration-uat/V905__uat_dual_track_sales.sql`: `sales.customer` →
  `customers.customer` (4 refs, SQL + comments); stale `sales.project`/`sales.contact` comment fixed.

## Commands Run
```bash
git checkout -b fix/uat-flyway-test-schemas uat
cd backend && ./mvnw -B test -Dtest=FlywayMigrationTest   # iterated; final: 3/3 pass
cd backend && ./mvnw -B clean verify                      # 380 tests, 0 failures, 0 errors
```

## Test / Build Results
- Backend `./mvnw -B clean verify`: **pass** — 380 tests, 0 failures, 0 errors, Jacoco floor met.
  (Was 380 tests / 1 error on `uat`.) Testcontainers ran against local Docker; `TEST_DB_URL` unset.
- `FlywayMigrationTest` alone: **pass** — 3/3 (all three profiles apply to a clean DB).
- Frontend: **not run** — no frontend files touched.

## Decisions Made
- **The reported one-line fix was necessary but not sufficient.** Adding the two schemas fixed the
  reported `relation "customer" already exists` error, but that error was *masking* two real,
  fresh-database seed bugs, which then surfaced in turn. All three are pre-existing on `uat` and are
  not caused by the main→uat merge (`V41` is already present at the pre-merge commit `f5a264b`).
- **Root cause of the seed bugs — migration ordering.** The UAT seed uses the V900+ range, so it runs
  *after* every real `db/migration`. Main's `V41` (customer → `customers` schema) and `V45` (unique
  index on `deposit_notice.doc_number`) therefore land *before* the seed, and the seed was authored
  against the pre-V41/V45 schema:
  1. `V903`/`V905` insert into `sales.customer`, which no longer exists after V41 → `42P01`.
  2. `V903` inserts two `deposit_notice` rows sharing `doc_number = QN-2026-00001`, which V45's
     `ux_deposit_notice_doc_number` forbids → duplicate-key error.
  The demo seed does **not** have this problem and needed no change: its files are numbered V21/V32/V46,
  so they run *before* V41 while `customer` is still in `sales`. That asymmetry is why only the uat
  test failed.
- **Edited `V903`/`V905` in place rather than adding a forward-only repair.** `V906`'s header says
  "Keep V903 immutable for Flyway checksum validation" and repairs the doc_number forward. That
  pattern cannot fix a *fresh* database: V45's unique index is created before V903 runs, so V903's
  INSERT fails outright and V906 never executes. No forward-only migration can rescue a statement
  that errors at apply time — the seed itself has to be correct. `V906` is left in place (it is now
  a no-op on a fresh DB, and is still needed by any DB seeded before V45).
- Applied V906's existing intent (`QN-2026-00003`) at the source, so fresh and repaired DBs converge
  on the same data.
- Verified no `db/migration-uat` file touches any table moved by `V42` (`factories`,
  `import_profiles`, `price_list_versions`, `product_prices`, `product_price_staging`), and that
  `sales.catalog` was **not** moved by V42, so it correctly stays `sales`-qualified.
- Verified no Java, docs, or frontend code references `QN-2026-00001`/`QN-2026-00003`.

## Assumptions
- The uat profile is intended to run all five schemas: `application-uat.yml` does not override
  `spring.flyway.schemas`, so it inherits `hr,hr_restricted,sales,customers,price_catalog` from
  `application.yml`. The test's three-schema list was drift, not intent.

## Known Risks

### The hosted UAT DB exists and needs a checksum repair (verified 2026-07-15)
Inspected the live `GL&R's UAT` Supabase project (`wuypxdznuhhluwzncafh`, ap-southeast-2) via the
Supabase MCP. Findings from `hr.flyway_schema_history`:

- **Applied:** V1–V20, V22–V31, V33–V40, plus V900–V906. `V906` ran 2026-07-14.
- **NOT yet applied:** **V41–V47** — the real migrations from the main→uat merge are still pending.
  The DB's newest real migration is V40.
- `sales.customer` / `sales.contact` / `sales.project` are **still in the `sales` schema** (V41 pending).
- `UAT-TKT-04` deposit notices are already `v1 = QN-2026-00001`, `v2 = QN-2026-00003` — **V906 has
  already done its repair, so no duplicate `doc_number` exists.**

**Correction to an earlier draft of this handoff / PR #184's body:** the claim that "V45's unique
index will fail on the seeded DB because of duplicate doc_number" is **wrong**. V906 ran on 2026-07-14,
*before* V41–V47 arrived on the branch, so the duplicate was cleared while V45 was still unmerged.
When V45 finally applies it will succeed. This also explains why the prior agent's forward-only V906
design was reasonable *for the hosted DB* — the UAT DB receives migrations incrementally as branches
merge, so V906 legitimately ran ahead of V45. It just cannot work for a fresh DB, where numeric order
puts V45 before V903.

**The only real problem is the two changed checksums.** The uat profile does not set
`validate-on-migrate: false`, so the next deploy fails `validate()` unless they are repaired:

| Migration | Stored in UAT DB | New (this branch) |
|---|---|---|
| `V903__uat_sales.sql` | `-733748901` | `547934313` |
| `V905__uat_dual_track_sales.sql` | `-1760552940` | `1498275195` |
| `V906__...doc_number.sql` | `-1911239188` | `-1911239188` (untouched) |

Checksums were computed with a local reimplementation of Flyway's `ChecksumCalculator` (CRC32 over
each line's UTF-8 bytes, terminators excluded), **validated by reproducing all three of the DB's
existing stored values exactly** from the pre-change files.

**Data converges — no data fix is needed.** The end state after V41–V47 apply is identical to what the
corrected V903/V905 produce on a fresh DB: V41 moves the customer rows into `customers`, and V906 has
already assigned `QN-2026-00003`.

**⚠️ Sequencing matters.** The repair must land **with or after** PR #184's merge, never before: if the
stored checksums are updated while `origin/uat` still has the old V903/V905, a deploy from that state
fails `validate()` in the opposite direction.

Repair options (not yet applied — awaiting a decision):
1. `flyway repair` against the UAT DB (no `flyway-maven-plugin` in `backend/pom.xml`, and the UAT
   datasource credentials are not available in this worktree, so this needs the Flyway CLI + the
   Render env vars).
2. Equivalent direct SQL via the Supabase MCP (this is exactly what repair does for checksum drift):
   ```sql
   UPDATE hr.flyway_schema_history SET checksum = 547934313  WHERE version = '903';
   UPDATE hr.flyway_schema_history SET checksum = 1498275195 WHERE version = '905';
   ```

### Other
- The seed now hard-depends on V41/V42/V45 having run. That is guaranteed by the V900+ numbering.

## Things Not Finished
- **The UAT DB checksum repair is NOT applied** — see Known Risks for the exact SQL and the
  sequencing constraint (must land with/after PR #184, not before).
- Consider adding a note to `application-uat.yml` warning that V900+ seeds run after *all* real
  migrations and must track schema moves — this class of bug will recur on the next `sales` table move.

## Recommended Next Agent
Claude Opus review of PR #184, then whoever owns the UAT deploy runs the checksum repair.

### Exact next prompt
> Review PR #184 (`fix/uat-flyway-test-schemas` → `uat`) and read
> `docs/agent-handoffs/47_fix-uat-flyway-test-schemas.md` first.
> The reported one-line test fix was necessary but not sufficient: it unmasked two pre-existing
> fresh-DB failures in the UAT seed, caused by main's V41 (customer moved to the `customers` schema)
> and V45 (unique index on `deposit_notice.doc_number`) running *before* the V900+ seed. The fix
> edits V903/V905 in place, which contradicts V906's "keep V903 immutable for Flyway checksum
> validation" comment — confirm that trade-off is right, given no forward-only migration can prevent
> V903's INSERT from erroring on a fresh DB. `./mvnw -B clean verify` is green (380 tests, 0 errors).
>
> Then, **with or after the merge (never before)**, repair the live UAT DB's checksums — Supabase
> project `wuypxdznuhhluwzncafh` (`GL&R's UAT`):
> ```sql
> UPDATE hr.flyway_schema_history SET checksum = 547934313  WHERE version = '903';
> UPDATE hr.flyway_schema_history SET checksum = 1498275195 WHERE version = '905';
> ```
> No data fix is needed: V906 already assigned `QN-2026-00003`, and the still-pending V41 will move
> the customer rows into `customers` on the next deploy. Verify afterwards that a uat deploy passes
> `validate()` and applies V41–V47.
