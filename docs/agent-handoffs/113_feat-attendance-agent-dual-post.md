# 113 — feat/attendance-agent-dual-post

## Goal
Make the on-prem attendance agent able to feed **more than one backend** from the
single physical device, so UAT receives the *same live punches* as prod. This is
the "future punches live in UAT just like prod" half of the request; the history
half was already satisfied (see below).

## Context / what was true before starting (verified against live DBs, 2026-07-24)
Using the `5b502e22…` Supabase MCP against both projects:

- **Prod** (`tdyzcqzxmhtxpbouewud`) and **UAT** (`wuypxdznuhhluwzncafh`) attendance
  are **already byte-identical**: `attendance_punch` 12,696 rows (checksum match,
  max `punch_id`=20255, max `received_at` identical to the microsecond),
  `attendance_daily` 5,102 rows (checksum match). Composition on both:
  10,890 `USB_DAT_IMPORT` SHOWROOM (2020-11-02→2026-07-02), 1,360 `USB_DAT_IMPORT`
  WAREHOUSE, 357 `LIVE_CAPTURE` SHOWROOM (2026-07-03→today), 89 `CATCHUP_PULL`.
- Identical ids + microsecond-identical `received_at` ⇒ UAT attendance is a
  **preserved-id copy of prod taken today**, not an independent feed.
- **No synthetic feed exists on UAT** — `cron.job` has no `uat-attendance-daily`
  and `hr.uat_generate_attendance` does not exist. (An earlier session note about a
  pg_cron synthetic feed was stale/never materialized.)
- Prod **is** live: `LIVE_CAPTURE` punches flow through today, so the agent is
  already running against prod.

**Therefore:** history parity = already done (UAT is a fresh copy of prod). The only
remaining work is keeping UAT live going forward — the agent must post to UAT too,
or UAT drifts stale as prod keeps receiving live punches.

## Change
`agents/attendance/showroom_agent.py` — the agent now supports N delivery targets.

- New `Target` dataclass: `{name, url, token, state_file, queue_file}`. Each target
  has its **own** agent token, delivery watermark (state file) and retry queue.
- New `build_targets(base_dir)`:
  - `ATTENDANCE_API_TARGETS` (JSON array of `{name,url,token?,state_file?,queue_file?}`)
    → multi-target. Per-target files default to `agent_state.<name>.json` /
    `agent_queue.<name>.jsonl` under the data dir.
  - Unset → **backward-compatible** single `default` target from the original
    `ATTENDANCE_API_URL`/`ATTENDANCE_AGENT_TOKEN`, keeping the original
    `showroom_agent_state.json` / `_queue.jsonl` filenames (no watermark reset for
    existing deployments).
- `AgentConfig`: replaced `api_url/api_token/state_file/queue_file` with
  `targets: tuple[Target, ...]`.
- Delivery/state functions (`load_state`, `save_state`, `last_delivered_time`,
  `mark_delivered`, `enqueue_payload`, `flush_queue`, `post_payload`,
  `deliver_payload`, `catchup_cutoff`) are keyed on a `Target`.
- `deliver_payload` fans out to **every** target; a target that fails has the punch
  queued for *its own* retry (isolated), returns True only if all targets accepted.
- `run_catchup` reads the device **once** and replays each target's own window from
  its own watermark (onboarding a new backend backfills it without re-flooding the
  others). `run_live` idle-tick flushes every target's queue.
- `agents/attendance/README.md`: documented `ATTENDANCE_API_TARGETS` (prod+UAT
  example, per-backend tokens, per-target queue/state, dedup note).
- `agents/attendance/test_showroom_agent.py`: new pytest suite (8 tests).

## Files changed
- `agents/attendance/showroom_agent.py` (multi-target refactor)
- `agents/attendance/README.md` (dual-post config section)
- `agents/attendance/test_showroom_agent.py` (new)
- `docs/agent-handoffs/113_feat-attendance-agent-dual-post.md` (this)

## Commands run
- `python3 -m py_compile agents/attendance/showroom_agent.py` → OK
- venv (`requests pytest tzdata`): `pytest agents/attendance/test_showroom_agent.py -q`
  → **8 passed**.
- Mutation check: `for target in config.targets[:1]` in `deliver_payload` → exactly
  the 3 fan-out tests (`test_deliver_posts_to_every_target`,
  `test_failed_target_is_isolated_and_queued`, `test_flush_redelivers_only_failed_target`)
  went red, 5 passed; reverted → 8 passed.

## Tests / build results
- Agent unit tests: **8/8 pass** (config resolution, fan-out delivery, per-target
  queue isolation, queue flush, dry-run no-op). Pure-Python, no device needed.
- No backend/frontend code touched → their suites not relevant to this branch.

## Authz evidence
**No authorization change.** The agent authenticates to each backend with a
per-device `X-GLR-Agent-Token` (existing mechanism); this change only lets it hold
more than one token/URL. No role gate, scope, or SQL `WHERE` clause changed.

## Deploy (to actually make UAT live)
On the on-prem agent host, replace the single `ATTENDANCE_API_URL`/`..._TOKEN` with:
```
ATTENDANCE_API_TARGETS = [
  {"name":"prod","url":"https://gl-r-erp.onrender.com/api/attendance/punch","token":"<PROD device token>"},
  {"name":"uat","url":"https://gl-r-erp-uat.onrender.com/api/attendance/punch","token":"<UAT device token>"}
]
```
Mint the UAT device token via `POST /api/attendance/devices/{deviceCode}/agent-token`
against the UAT backend. Restart the agent. Prod keeps flowing; UAT starts receiving
the same live punches (its first catch-up backfills up to `ATTENDANCE_CATCHUP_MAX_DAYS`,
deduped against the copy already in UAT).

## Known risks
- **Gap window:** UAT's copy was taken today; between now and agent redeploy, prod
  gets live punches UAT misses. The per-target catch-up heals up to
  `ATTENDANCE_CATCHUP_MAX_DAYS` (default 3). If the redeploy is >3 days out, bump
  that on first UAT run or re-copy attendance before switching.
- **UAT identity sequence:** the agent's first UAT insert uses UAT's own
  `attendance_punch` identity. If the prod→UAT copy preserved ids without resetting
  the sequence, the first insert could collide. Verify
  `pg_get_serial_sequence('hr.attendance_punch','punch_id')` is at `max(id)+1` on UAT
  before/at deploy. (The standard `uat-copy-from-prod.sql` resets sequences in step 5.)
- **Merge coordination:** `showroom_agent.py` was recently changed by
  `feat/attendance-pyzk-transport` (ZK_TRANSPORT) and `feat/attendance-warehouse-scanner`,
  both now merged to `origin/main` (this branch is off `main@94a91ed`, so it already
  includes them). No open conflict expected, but re-check at PR time.

## Next prompt for the next agent
"Open the PR for feat/attendance-agent-dual-post (branch off main@94a91ed). Then help
mint a UAT per-device agent token via the UAT backend and set ATTENDANCE_API_TARGETS
on the on-prem agent host, verifying UAT's attendance_punch identity sequence is at
max+1 first. Confirm live punches land in both prod and UAT after restart."
