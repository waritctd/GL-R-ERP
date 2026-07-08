# Agent Handoff

## Task
Fix a production incident: the Render demo deploy was crash-looping on every boot with
`FlywayException: Found more than one migration with version 32`.

## Branch
fix/v32-migration-collision

## Base Commit
08ff771 (main, tip after PR #153 merged)

## Agent / Model Used
Claude Opus 4.8 (diagnosis, fix, verification, guard test)

## What happened

`db/migration/V32__hr_notification_schema.sql` (added in `feat/notification-email-backbone`, PR
#150, merged 2026-07-08) reused version 32, which was already claimed by
`db/migration-demo/V32__link_demo_ticket_customers.sql` (renamed to V32 by an *earlier* fix, PR
#115, for the exact same reason — see below). `application-prod.yml` configures
`spring.flyway.locations: classpath:db/migration,classpath:db/migration-demo` for the Render demo
deploy, and Flyway validates version uniqueness across **all** configured locations combined, not
per-folder. The collision made every deploy since PR #150 merged fail at Flyway's `validate()` step,
before the app ever bound to a port — a full outage of the demo environment (`gl-r-erp.onrender.com`)
for however long elapsed between that merge and this fix.

This is the **second** time this exact class of bug has hit production. PR #115 (2026-07-07) fixed
an identical V31 collision and added a `NOTE` comment to `application-prod.yml` spelling out the
constraint ("check both directories before picking the next number"). The comment did not prevent
recurrence — nobody implementing PR #150-#153 read it, because none of those branches' authors had
reason to open `application-prod.yml`, and no automated check enforced it. Documentation alone was an
insufficient guard the first time it was tried; this fix adds an automated one instead.

## Root cause (why review/CI never caught it)

`FlywayMigrationTest.allMigrationsApplyToACleanDatabase` (added specifically to catch cross-migration
errors "the Mockito-based unit tests cannot") only exercises Flyway's **default** location
(`classpath:db/migration`) — it never scans `db/migration-demo`. Every `./mvnw clean verify` run
across all four round-1 branches (and my review of each) passed cleanly because the test suite
literally never assembles the same location list Render uses. The collision was invisible until a
real `prod`-profile boot.

## Fix

1. Renamed `db/migration/V32__hr_notification_schema.sql` → `V36__hr_notification_schema.sql` (the
   next version free in **both** folders: `db/migration` topped out at V35, `db/migration-demo` had
   claimed V21 and V32). No SQL content changed — the file has no internal version self-references.
   Confirmed safe to renumber: grepped the whole backend for hardcoded `"V32"` references (none), and
   confirmed via the failing deploy logs that no environment had successfully applied this specific
   migration under version 32 (Flyway's `validate()` fails *before* any migration executes, so a
   crash-looping deploy never got far enough to record it in `flyway_schema_history`).
2. **Added a permanent regression test**, not just a comment this time:
   `FlywayMigrationTest.demoProfileCombinedLocationsApplyToACleanDatabase` — a second test in the
   same file that configures Flyway with `.locations("classpath:db/migration",
   "classpath:db/migration-demo")`, exactly mirroring `application-prod.yml`, and asserts a clean
   migrate succeeds. This runs on every `./mvnw clean verify` (same Testcontainers/`TEST_DB_URL`
   gating as the existing test), so any future version collision between the two folders now fails
   CI on the PR that introduces it, instead of surfacing only in a live Render deploy after merge.
3. Added a pointer note in `docs/agent-handoffs/34_feat-notification-email-backbone.md` (the original
   PR that introduced the collision) so the historical "V32" references there are understood as
   accurate-at-merge-time, not the current filename.

## Verification

- **Simulated the exact Render failure and fix locally**, matching PR #115's own verification method:
  built the jar (`mvnw -DskipTests package`), started a throwaway isolated `postgres:16-alpine`
  Docker container (port 55432, torn down after — did **not** touch the shared local dev DB on 5432),
  and booted the jar with `SPRING_PROFILES_ACTIVE=prod` pointed at it.
  - **Before the fix** (implicitly, by the reported Render log): crash with `Found more than one
    migration with version 32`.
  - **After the fix**: `Successfully applied 36 migrations to schema "hr", now at version v36` and
    `Started HrBackendApplication in 2.374 seconds` — clean boot.
- `cd backend && ./mvnw -B clean verify`: **PASS**, 304 tests (303 + 1 new guard test), 0 failures,
  Testcontainers ran both `FlywayMigrationTest` methods successfully, Jacoco passed.
- Frontend: not touched by this change; not re-run.

## Files Changed
- `backend/src/main/resources/db/migration/V32__hr_notification_schema.sql` → renamed to
  `V36__hr_notification_schema.sql` (content unchanged).
- `backend/src/test/java/th/co/glr/hr/FlywayMigrationTest.java`: added
  `demoProfileCombinedLocationsApplyToACleanDatabase()`.
- `docs/agent-handoffs/34_feat-notification-email-backbone.md`: added a pointer note.

## Decisions Made
- Renumbered the *newer* migration (V32→V36) rather than touching the demo one a second time,
  since the demo migration was already live in production history from PR #115's fix; the
  hr_notification migration was never successfully applied anywhere (every deploy attempt failed at
  validation), so it was the only one safe to move.
- Chose an automated test over strengthening the existing comment again — the comment already failed
  once. A CI-enforced check is the correct level of guard for a two-strikes class of bug.

## Known Risks
- None outstanding. The Render demo deploy should succeed on the next auto-deploy once this merges to
  `main`. If Render doesn't auto-redeploy on merge, a manual redeploy trigger will be needed —
  recommend checking `gl-r-erp.onrender.com` after merge to confirm it comes back up.
- This is the second recurrence of "migration numbered against only one folder's history." If a
  third `db/migration-demo` file or a third real migration ever collides again, treat it as a signal
  the two-folder scheme itself is fragile and consider a structural fix (e.g., reserving a disjoint
  numeric range for `db/migration-demo`, such as V900+, so the two folders can never collide by
  construction) rather than a fourth one-off renumber.

## Things Not Finished
- Branch not yet committed/pushed/PR'd — doing that next as this doc is written.
- Have not confirmed the Render deploy actually recovers post-merge (no access to trigger/observe
  Render deploys from this session) — worth a manual check by whoever has dashboard access.

## Recommended Next Agent
None required — this is a standalone hotfix. Resume the round-1-adjacent backlog (CEO Clarification
Backlog items) whenever ready; see `~/.claude/plans/1-quirky-stroustrup.md`.
