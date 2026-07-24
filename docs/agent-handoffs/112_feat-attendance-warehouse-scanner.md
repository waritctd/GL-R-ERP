# Agent Handoff

## Task
Register a second physical ZKTeco SC700 scanner (warehouse, ZMM220 board -- same
family as the existing showroom scanner, same `plcommpro.dll` Pull SDK) so its
punches land in `hr.attendance_device`/`hr.attendance_punch` distinguishable as
warehouse punches, fix a latent `ZK_HOST` default bug found while grounding
this task, and write an ops runbook for the human operator to bring the
physical device online. This is a registration + documentation task -- the
ingestion pipeline (`agents/attendance/showroom_agent.py`) is already
env-driven and multi-site capable; no new driver or agent fork was written.

## Branch
`feat/attendance-warehouse-scanner`

## Base Commit
`42de4fd` (origin/main, same as `GL-R-ERP-wfh-present` sibling worktree at task start)

## Current Commit
Not committed -- working tree left dirty for review per task instructions.

## Agent / Model Used
Claude (Sonnet)

## Scope

### In Scope
- New forward-only Flyway migration registering the `WAREHOUSE_SC700` device row.
- Fixing the `ZK_HOST` default in `showroom_agent.py` to match the documented/seeded showroom IP.
- A new ops runbook, `agents/attendance/WAREHOUSE_SCANNER_SETUP.md`.
- This handoff file.

### Out of Scope
- Any new driver code, protocol work, or forking the agent script.
- Actually provisioning the Windows mini PC, VPN, or on-site network (physical rollout is the human operator's job, per the runbook).
- Any authorization/permission change (none needed -- the per-device agent-token endpoint already exists, HR-gated, from issue #22 / `V20`).

## Files Changed
- `backend/src/main/resources/db/migration/V89__attendance_warehouse_device.sql` (new) -- inserts one `hr.attendance_device` row for `WAREHOUSE_SC700` / site `WAREHOUSE`, idempotent via `ON CONFLICT (device_code) DO NOTHING`.
- `agents/attendance/showroom_agent.py` -- changed the `ZK_HOST` env default from `192.168.1.202` to `192.168.1.201` (see Decisions Made).
- `agents/attendance/WAREHOUSE_SCANNER_SETUP.md` (new) -- step-by-step ops runbook for the warehouse rollout.
- `docs/agent-handoffs/112_feat-attendance-warehouse-scanner.md` (new) -- this file.

## Commands Run
```bash
git status
git branch --show-current
git log --oneline -5
ls backend/src/main/resources/db/migration | sort -V | tail -10   # confirmed V88 is the highest applied version
git worktree list                                                  # scanned every worktree for a pre-existing V89 -- none found
cd backend && ./mvnw -B clean verify                                # see Test / Build Results
```

## Test / Build Results
- Backend build: **ran in background, result pending at time of writing this section -- see the live update below or ask the agent to confirm before merge.**
- No Docker daemon reachable in this environment (`docker info` hung/unavailable) and no `TEST_DB_URL` set, so Postgres-backed integration tests (including `FlywayMigrationTest`) are expected to **skip**, not run, per `support/PostgresTestSupport#isAvailable`. This means the migration's SQL syntax gets compiled/parsed by Flyway placeholder validation at best, but the full `FlywayMigrationTest` migrate-from-scratch pass was **not confirmed executing** in this environment -- flag this to the reviewer/next agent to re-run with Postgres available (`TEST_DB_URL` or Docker) before merge.
- Frontend: **not run** -- no frontend files were touched.

## Authz Evidence
No authorization change in this task. The device is registered via a plain
`INSERT`; the token-issuance endpoint (`POST
/api/attendance/devices/{deviceCode}/agent-token`, HR-role-gated) and the
`/unmapped` and `/devices` endpoints (HR/CEO-gated) referenced in the runbook
already existed unchanged before this branch.

## Decisions Made
- **V89 confirmed free.** Checked `origin/main`'s migration directory (tops out at `V88__withholding_tax_override.sql`) and scanned every worktree listed by `git worktree list` (including `.claude/worktrees/*`, `GL-R-ERP-wfh-present`, `GL-R-ERP-main`, `GL-R-ERP-employees`) for any existing `V89__*.sql` -- none found. Did not renumber.
- **V7 column set matched:** `hr.attendance_device` has these columns: `device_id` (identity PK), `device_code` (`VARCHAR(40) NOT NULL UNIQUE`), `site_code` (`VARCHAR(20) NOT NULL` FK to `attendance_site`), `device_name` (`VARCHAR(120) NOT NULL`), `model` (`VARCHAR(80) NOT NULL DEFAULT 'ZKTeco SC700'`), `serial_no` (nullable), `ip_address` (`INET`, nullable), `tcp_port` (`INTEGER NOT NULL DEFAULT 4370`), `comm_key_required` (`BOOLEAN NOT NULL DEFAULT FALSE`), `is_active` (`BOOLEAN NOT NULL DEFAULT TRUE`), `installed_at`/`created_at`/`updated_at` (all nullable-or-defaulted), plus `CHECK (tcp_port BETWEEN 1 AND 65535)` and `CHECK (device_code = upper(device_code))`. The showroom seed (`V7`, line 41-43) only supplies `device_code, site_code, device_name, ip_address, tcp_port` and lets everything else default. My `V89` INSERT explicitly supplies `device_code, site_code, device_name, model, ip_address, tcp_port, is_active` -- one column wider than the showroom seed (I added an explicit `model` and `is_active` for clarity even though both match their own defaults) -- and leaves `ip_address = NULL` since the warehouse scanner's real LAN IP is not yet known on-site; `INET` accepts `NULL` (the column has no `NOT NULL`), so this satisfies every constraint. `device_code = 'WAREHOUSE_SC700'` is already uppercase, satisfying `chk_attendance_device_code_upper`. Added `ON CONFLICT (device_code) DO NOTHING` mirroring the showroom seed's idempotency, backed by the existing `UNIQUE` constraint on `device_code`.
- **`ZK_HOST` fix — chose `.201`, not `.202`.** The code default in `showroom_agent.py` (`AgentConfig.from_env`, line 88) was `192.168.1.202`. Every other source of truth disagrees: the `V7` showroom seed row (`INSERT INTO hr.attendance_device ... VALUES ('SHOWROOM_SC700', 'SHOWROOM', 'Showroom ZKTeco SC700', '192.168.1.201', 4370)`), `agents/attendance/README.md` (network-check section and the "Defaults are showroom-safe" config block, both `.201`), and `agents/attendance/SC700_NEW_COMPUTER_SETUP.md` (scanner-details block plus every reachability/test command in the doc, all `.201`) all agree on `.201`. Five independent references at `.201` vs. one code default at `.202` makes `.201` unambiguously correct; `.202` in the code was the latent bug. Fixed the code default only -- did not touch the seed or docs since they were already correct. The warehouse device is unaffected either way since its runbook requires `ZK_HOST` to always be set explicitly (there's no safe warehouse default).
- **Warehouse ops runbook references, does not duplicate, `SC700_NEW_COMPUTER_SETUP.md`** for SDK/Python install steps, per the task spec, to avoid two copies of that content drifting apart.
- **No service-install script exists in the repo** for turning the agent into a Windows service (`resume-agent.ps1`/`pause-for-zkaccess.ps1` assume a service already exists, named `GLRAttendanceAgent` by default). The runbook calls this out explicitly and tells the operator to reuse whatever mechanism (e.g. `nssm`) was used for the showroom machine, under a distinct service name (`GLRAttendanceAgentWarehouse`) since it runs on a separate physical machine. This is a documentation gap in the existing repo, not something introduced by this branch -- flagged as a known risk below rather than silently worked around.

## Assumptions
- The warehouse scanner's on-site LAN IP and the on-prem backend's LAN-over-VPN address are both unknown at the time of this branch (per the task) and are left as fill-in-on-site placeholders in the runbook, not hardcoded guesses.
- Render auto-deploys `main`, per existing repo convention, so once merged the `V89` migration will run the same way every other migration does -- no separate deploy step was invented.

## Known Risks
- **VPN routing is the most likely failure point.** A new site-to-site tunnel to a new subnet is more likely to have a firewall/routing gap than the scanner or agent code -- the runbook calls this out explicitly (step 4) and tells the operator to rule out VPN/networking before assuming an agent bug.
- **This migration seeds an idle device on every environment**, including UAT and any future fresh-DB environment -- the row will sit `is_active = TRUE` with no real scanner behind it anywhere except the physical warehouse rollout. This mirrors exactly how the showroom seed already behaves (V7 seeds `SHOWROOM_SC700` unconditionally too), so it's consistent with existing precedent, not a new pattern.
- **`ip_address` is `NULL` until someone fills it in on-site.** There is currently no HR-facing UI/endpoint to edit an existing device row's `ip_address` after creation (only `POST .../agent-token` exists for devices) -- the row will need either a follow-up forward-only migration or a new PATCH-style endpoint to record the real IP once known. Flagged in the runbook and here rather than fabricated.
- **Backend build/Flyway validation could not be confirmed to completion in this sandboxed environment** (no Docker daemon, no `TEST_DB_URL`) -- see Test / Build Results. The migration was written to mirror the V7 seed's exact style and constraints by inspection, but a real `mvnw -B clean verify` with Postgres available has not been confirmed green by this agent. Do not merge without that confirmation.

## Things Not Finished
- Backend build/verify confirmation (compile + `FlywayMigrationTest` with real Postgres).
- Physical rollout itself (mini PC provisioning, VPN, real scanner IP) -- intentionally out of scope, that's the runbook's job for the human operator.

## Recommended Next Agent
Same agent (to finish the build verification once background output lands) or
a reviewer agent to re-run `./mvnw -B clean verify` with `TEST_DB_URL` set or
Docker available, then confirm the migration applies cleanly against a real
Postgres instance before this branch is merged.

## Exact Next Prompt
```
Continue on branch feat/attendance-warehouse-scanner in
/Users/ploy_warit/Desktop/GL-R-ERP-wh-scanner. The backend build
(./mvnw -B clean verify) was started but its completion was not confirmed in
the previous session (no Docker/TEST_DB_URL available there). Re-run it with
Postgres available (either export TEST_DB_URL to a real Postgres instance, or
ensure Docker is running for Testcontainers) and confirm:
1. The project compiles.
2. FlywayMigrationTest actually runs (not skipped) and passes, proving
   V89__attendance_warehouse_device.sql applies cleanly on top of V1-V88.
3. No other test regressed.
Update docs/agent-handoffs/112_feat-attendance-warehouse-scanner.md's Test /
Build Results and Known Risks sections with the real outcome. Do not commit
or push without asking first.
```
