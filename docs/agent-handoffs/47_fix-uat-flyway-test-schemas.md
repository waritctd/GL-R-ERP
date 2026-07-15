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
- **`V903`/`V905` checksums changed.** Any UAT database that already applied them will fail
  `validate()` on the next deploy (the uat profile does **not** set `validate-on-migrate: false`).
  `application-uat.yml` claims "The UAT DB already has db/migration-uat V900-V904 applied", but repo
  memory records the uat branch and its Render/Supabase services as still unpushed/pending, so it is
  unclear whether a seeded UAT DB actually exists. **This needs a human decision before deploying:**
  - If no seeded UAT DB exists → nothing to do; a fresh deploy now works (it previously would have
    crashed at V903).
  - If one does exist → run `flyway repair` against it to recompute checksums. Note also that such a
    DB holds two rows with duplicate `doc_number`, so V45's unique index will fail there until V906's
    UPDATE is applied first; the ordering (V45 < V906) means this likely needs manual intervention.
    This pre-existing hazard is independent of this branch's changes.
  - I could not inspect the hosted UAT DB: the `supabase-uat` MCP server is unauthorized in this
    session.
- The seed now hard-depends on V41/V42/V45 having run. That is guaranteed by the V900+ numbering.

## Things Not Finished
- Not committed or pushed (not requested).
- The hosted-UAT-DB repair question above.
- Consider adding a note to `application-uat.yml` warning that V900+ seeds run after *all* real
  migrations and must track schema moves — this class of bug will recur on the next `sales` table move.

## Recommended Next Agent
Claude Opus review — verify the V903/V905 edits against the intent of `V906`'s immutability comment
and decide the hosted-UAT-DB repair path.

### Exact next prompt
> On branch `fix/uat-flyway-test-schemas` (off `uat`), review the uncommitted diff in
> `backend/src/test/java/th/co/glr/hr/FlywayMigrationTest.java` and
> `backend/src/main/resources/db/migration-uat/V903__uat_sales.sql` + `V905__uat_dual_track_sales.sql`,
> and read `docs/agent-handoffs/47_fix-uat-flyway-test-schemas.md` first.
> The test fix alone was insufficient: it unmasked two pre-existing fresh-DB failures in the UAT seed
> caused by main's V41 (customer moved to the `customers` schema) and V45 (unique index on
> `deposit_notice.doc_number`) running *before* the V900+ seed. The fix edits V903/V905 in place,
> which contradicts V906's "keep V903 immutable for Flyway checksum validation" comment — confirm
> that trade-off is right, given no forward-only migration can prevent V903's INSERT from erroring on
> a fresh DB. Then decide the hosted UAT DB path (`flyway repair` vs. nothing) — check whether a
> seeded UAT DB actually exists; the `supabase-uat` MCP server needs authorizing to inspect it.
> `./mvnw -B clean verify` is green (380 tests, 0 errors). Do not commit without confirming the
> checksum decision.
