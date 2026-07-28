# Agent Handoff

## Task
Give UAT its own customer master, catalog and factory config, after V91 (merged to `uat` via #329)
left the hosted UAT database with an empty `customers.customer`.

## Branch
`feat/uat-customer-master-v911` (worktree: `.claude/worktrees/uat-sync`)

## Base Commit
`54f2b6ee` — `Merge pull request #329 from waritctd/sync/main-into-uat-1c2cfa5b`, i.e. current
`origin/uat`.

## Current Commit
Not committed. Working tree only.

## Agent / Model Used
Claude Opus 5.

## Scope

### In Scope
- `db/migration-uat/V911` seeding a UAT-owned customer master, contacts, projects, `sales.catalog`
  and `sales.factory_config`.
- An assertion in `FlywayMigrationTest#uatProfileCombinedLocationsApplyToACleanDatabase` that those
  fixtures survive V91.

### Out of Scope
- Back-filling `customer_id` / `project_id` / `contact_id` on the 13 `DEAL-UAT-*` tickets. They
  currently carry a free-text `customer_name` with all three FKs NULL. Rewriting deal rows is a
  separate decision, not a side effect of a fixture seed.
- Anything on `main`. V91 stays exactly as merged; this only adds a UAT-only file.
- Why the hosted UAT database lost the seed's own 11 customers. See Known Risks.

## What actually happened on UAT

The Render deploy of #329 applied V91 at **2026-07-28 12:22:19 UTC, success = true, 1096 ms**. The
service came back healthy (`https://gl-r-erp-uat.onrender.com/` → HTTP 401, the correct
unauthenticated response; a first 60 s timeout was a Render cold start, not a failure).

The data outcome:

| table | before | after |
|---|---|---|
| `customers.customer` | 4 | **0** |
| `customers.contact` | 5 | **0** |
| `customers.project` | 5 | **0** |
| `sales.catalog` | 14 | **0** |
| `sales.factory_config` | 4 | **0** |
| `sales.ticket` | 13 | 13 (untouched) |

Every V91 guard passed because nothing referenced anything: all four sample customers had
`tickets_linked = 0`, and none of the 4 `pricing_request` rows carried a `recipient_contact_id`.
Nothing errors in that state — which is exactly why it needed a test rather than trust.

## Files Changed
- `backend/src/main/resources/db/migration-uat/V911__uat_customer_master.sql` (new): 13 customers
  whose names mirror the `DEAL-UAT-*` tickets' `customer_name` values, 4 contacts, 4 projects, 9
  `factory_config` rows and 6 `catalog` rows. All guarded with `WHERE NOT EXISTS`, so idempotent.
- `backend/src/test/java/th/co/glr/hr/FlywayMigrationTest.java`: adds `assertUatCustomerMaster()`,
  called from the uat combined-locations test.

## Commands Run
```bash
createdb glr_uatsync_test
TEST_DB_URL="jdbc:postgresql://localhost:5432/glr_uatsync_test" TEST_DB_USERNAME="$USER" TEST_DB_PASSWORD="" \
  ./mvnw -B test -Dtest=FlywayMigrationTest -Dtest.fork.count=1
TEST_DB_URL="jdbc:postgresql://localhost:5432/glr_uatsync_test" TEST_DB_USERNAME="$USER" TEST_DB_PASSWORD="" \
  ./mvnw -B clean verify -Dtest.fork.count=1
dropdb glr_uatsync_test
```
(The property is `test.fork.count`, not `fork.count` — see `pom.xml:24`.)

## Test / Build Results
- `./mvnw -B clean verify -Dtest.fork.count=1`: **pass — 1243 tests, 0 failures, 0 errors, 2
  skipped** (counted from the surefire XML), jacoco coverage checks met, BUILD SUCCESS in 8:10.
  Identical totals to the pre-change `uat` baseline, so no regression. Integration tests **ran**
  against real local Postgres.
- `FlywayMigrationTest`: 4/4 pass. The uat combined-locations run reaches v911 across 101 migrations.
- Frontend: **not run** — no frontend file touched.

### Mutation check
Replaced V911's body with `SELECT 1;` → `uatProfileCombinedLocationsApplyToACleanDatabase` failed
(`expected: 13`) and nothing else did: **1 failure, 0 errors**. Reverted; `diff` confirms V911 is
byte-identical.

## Authz Evidence
**No authorization change.** Fixture rows in `customers.*` and `sales.catalog` /
`sales.factory_config` only; no role gate, scope/filter, service or repository touched. UAT-only
file — it cannot reach production, which loads `db/migration` alone.

## Decisions Made
- **Count V911's own rows in the assertion, not whole tables.** On a clean database
  V903/V905/V909/V910 add 11 customers of their own plus the golden deal's contact and project, so
  whole-table counts would assert against two seeds at once and break whenever either moved. First
  attempt did exactly that and failed at `expected: 13` against a table of 24.
- **`@uat.glr` addresses in `factory_config.email`, never a real supplier's.** That column is the
  destination for factory-quote dispatch (V64/V67 outbox worker), so a real address there lets a UAT
  test run email an actual factory. The test asserts no non-`@uat.glr` address can appear. Factory
  *names* do mirror `price_catalog.factories` so the pricing chain lines up with the 9 real price
  lists already loaded on UAT.
- **Obviously-synthetic `0999…` tax IDs**, so no UAT row can ever be mistaken for a real customer.
- **Customer names mirror the existing deals** so a tester can pick the customer matching the deal
  in front of them.

## Known Risks
- **Unexplained: the hosted UAT database never had the seed's own 11 customers.** V900–V910 all
  recorded `success = true` on 2026-07-24, and a clean migrate today demonstrably produces them, yet
  the hosted database held only prod's four V16 samples. The likeliest explanation is the 2026-07-24
  "rebuild UAT to mirror prod" copying prod tables over the seeded rows. Not chased down; it does not
  block V911, but it means **UAT's data is not reproducible from the migrations alone**, and anyone
  reasoning about UAT from the seed files will be wrong.
- After this lands, a **rebuilt** UAT gets 24 customers (11 seed + 13 V911). Harmless duplication for
  a test environment, but it will look odd.
- Merging triggers a second Render UAT deploy.

## Things Not Finished
- Not committed, not pushed, no PR — waiting on explicit say-so.
- The 13 `DEAL-UAT-*` tickets still have NULL customer/project/contact FKs. V911 makes the right rows
  selectable; it does not link them.

## Recommended Next Agent
Whoever picks this up: push, PR into `uat`, confirm the deploy re-seeds, then decide separately
whether the `DEAL-UAT-*` tickets should be linked to the new customer rows.

## Exact Next Prompt
> On branch `feat/uat-customer-master-v911` (worktree `.claude/worktrees/uat-sync`, based on
> `origin/uat` @ 54f2b6ee), read `docs/agent-handoffs/117_feat-uat-customer-master-v911.md`. Push it,
> open a PR into `uat`, and after the Render deploy confirm against the hosted UAT database
> (`wuypxdznuhhluwzncafh`) that `customers.customer` = 13, `sales.factory_config` = 9 with only
> `@uat.glr` emails, and `sales.catalog` = 6. Then report whether the `DEAL-UAT-*` tickets should be
> back-filled with the matching `customer_id` — do not do it without asking.
