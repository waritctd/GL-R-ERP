# Agent Handoff

## Task
`lint-and-build` in `.github/workflows/frontend-ci.yml` was failing on `main` and on every PR:
the "Audit dependencies" step runs `npm audit --audit-level=moderate` and 10 high-severity
advisories had become reachable in `frontend/package-lock.json`.

Asked to: triage which advisories are genuinely reachable, apply the non-breaking fixes, decide with
the owner how to handle anything unfixable, and get frontend-ci green — while preferring a real fix
over loosening the gate (this repo has been reddened by the audit gate before; `xlsx` was dropped
twice for exactly this reason — see `HANDOFF_LOG.md` and PR #180).

## Branch
`fix/frontend-audit-advisories`

## Base Commit
`c198380` (origin/main — "Merge pull request #318 from waritctd/refactor/ui-foundation-phases-0-3")

## Current Commit
_not committed — see "Things Not Finished"_

## Agent / Model Used
Claude Opus 5 (implementation + verification in one session; the user disabled subagent delegation
for this session, so the usual Sonnet-implements/Opus-reviews split did not apply)

## Scope

### In Scope
- `frontend/package-lock.json` dependency fixes
- The CI audit step in `.github/workflows/frontend-ci.yml`
- Documenting the triage reasoning

### Out of Scope
- Any application code change (none made — `frontend/src/**` is untouched)
- The react-router v7 → v8 migration (scoped as a follow-up branch; see "Things Not Finished")
- Backend, schema, authz, business logic — none touched

---

## Root-cause note: why this went red without a code change

`frontend/package-lock.json` was last modified in `b3cebc6` (the Playwright harness), well before the
last green run. **Nothing in this repo changed.** Two advisories were published against
already-pinned versions:

| Advisory | Published | Effect |
|---|---|---|
| `GHSA-mh99-v99m-4gvg` (brace-expansion) | patched release `5.0.8` on **2026-07-23** | 7 records |
| `GHSA-qwww-vcr4-c8h2` (react-router) | affects `>=7.12.0 <8.3.0` | 2 records |
| `GHSA-r28c-9q8g-f849` (postcss) | fixed forward | 1 record |

So "the advisories became reachable between e97782f0 and baf0ac6" is accurate, but the cause is
registry-side, not a dependency bump in this repo.

---

## Triage — reachability in THIS app

`npm audit` listed 10 records, but they resolve to only **3 root advisories** (verified by walking
the `via` graph; the other 7 records are packages that merely depend on a vulnerable one):

### 1. postcss — `GHSA-r28c-9q8g-f849` — **FIXED, no exception needed**
Path traversal via `sourceMappingURL` auto-loading in *input* CSS. Reached through `vite` and
`vitest`; the CSS it processes is first-party. Fixed forward `8.5.16 → 8.5.23` via `npm audit fix`.
No breaking change; only patch-level bumps to `postcss`, `nanoid`, `eslint`, `@eslint/js`,
`@eslint/eslintrc` landed in the lockfile.

### 2. brace-expansion — `GHSA-mh99-v99m-4gvg` — **dev-only, no fix exists**
DoS: `expand()` bounds result *count* but not result *length*, so `'{a,b}'.repeat(1500)` OOM-crashes
the process. Reached only through `minimatch@3.1.5`, which is pulled in exclusively by the ESLint
toolchain (`eslint`, `@eslint/config-array`, `@eslint/eslintrc`, `eslint-plugin-react`,
`eslint-plugin-jsx-a11y`).

- **Not reachable:** nothing in that chain ships to the browser, and the linter only ever globs our
  own repo paths — there is no untrusted input. Confirmed empirically:
  `npm audit --omit=dev` reports **zero** of these 7 records.
- **No fix exists.** Checked against the registry rather than assumed:
  - Patched only in `brace-expansion@5.0.8`. The `1.x`/`2.x`/`3.x` maintenance lines
    (`1.1.16`, `2.1.2`, `3.0.2`) are all still in the affected range, and `1.1.16` has no length
    guard (`grep -c EXPANSION_MAX_LENGTH` → 0).
  - An `overrides` pin to `^5` **breaks `minimatch@3` at runtime**: `5.0.8`'s CJS entry exports
    `{ expand, EXPANSION_MAX, EXPANSION_MAX_LENGTH }`, not a callable, so minimatch's
    `require('brace-expansion')(...)` throws `be is not a function`. Probed directly, not inferred.
  - Overriding `minimatch` to `^10.2.5` fails the same way (object export, not callable).
  - `eslint-plugin-react@7.37.5` and `eslint-plugin-jsx-a11y@6.10.2` — **both `latest`** — still
    declare `minimatch@^3.1.2`. There is no upstream version to upgrade to.
  - Every fix `npm audit fix --force` proposes is a *downgrade* (`eslint-plugin-react@7.22.0`,
    `eslint-plugin-jsx-a11y@6.4.1` — 2020/2021-era releases).

### 3. react-router — `GHSA-qwww-vcr4-c8h2` — **not reachable, no forward fix in 7.x**
CSRF bypass allowing action execution before a 400 response. The advisory states it *"only affects
your application if you are using the unstable RSC APIs."*

- **Not reachable:** this is a client-side Vite SPA. `src/main.jsx` mounts `<BrowserRouter>`; all
  54 router imports come from `react-router-dom`; the tree contains **zero** RSC entrypoints
  (grepped for `unstable_RSC`, `routeRSCServerRequest`, `matchRSCServerRequest`, `createCallServer`,
  `@vitejs/plugin-rsc`, `react-server` — no hits). There is no server-rendered action pipeline for
  the bypass to act on.
- **No forward fix in 7.x:**
  - Patched only in `react-router@8.3.0`.
  - `react-router-dom` has **no 8.x published at all** (v8 consolidated the package).
  - `react-router-dom@7.18.1` pins `react-router` to **exactly** `7.18.1`, so react-router cannot be
    bumped underneath it.
  - `7.18.1` is the last 7.x release and sits inside the affected range.
  - `npm audit fix --force` would install `react-router-dom@7.11.0` — a downgrade.

---

## Decision (owner-approved)

Presented four options; the owner chose **an allowlist gate with hard-fail on expiry**.

| Option | Verdict |
|---|---|
| **Allowlist script** | ✅ **Chosen.** Keeps `--audit-level=moderate` over **all** deps; subtracts only specific reviewed GHSA ids, each carrying a reachability argument and a `reviewBy` deadline. |
| `--omit=dev` | ✗ Doesn't even fix CI (react-router is a *prod* dep), and would permanently stop gating build tooling — which is what caught the postcss issue fixed here. |
| `--audit-level=critical` | ✗ Silently stops gating **all** future high-severity production advisories. The exact failure mode this repo keeps hitting. |
| react-router v7→v8 migration now | ✗ Real fix, but 54 import sites + v8 breaking changes; turns a CI-repair branch into a router migration. Scoped as a follow-up. |

Owner also chose **hard-fail on expiry**: past `reviewBy`, CI goes red until someone re-reviews. This
is what stops "temporarily unfixable" from quietly becoming permanent.

**The gate is not loosened.** Threshold and scope are unchanged (`moderate`, prod + dev). The only
difference is that two named, argued, dated exceptions are subtracted. Anything new still fails.

## Files Changed
- `frontend/package-lock.json` — `npm audit fix`: postcss `8.5.16 → 8.5.23` (the actual fix), plus
  patch bumps to nanoid, eslint `9.39.4 → 9.39.5`, `@eslint/js`, `@eslint/eslintrc`. No major/minor
  changes, no direct-dependency changes.
- `frontend/audit-allowlist.json` — **new.** The two reviewed exceptions, each with `reason`
  (reachability argument), `noFixAvailable` (why no upgrade exists), `clearWhen`, and
  `reviewBy: 2026-10-23` (90 days).
- `frontend/scripts/audit-gate.mjs` — **new.** The gate. Runs `npm audit --json`, resolves each
  record's **root** advisories through the `via` graph, subtracts allowlisted ones, fails on
  anything unreviewed / any expired entry / a malformed allowlist / an unusable audit report.
- `frontend/scripts/audit-gate.test.js` — **new.** 23 tests, mostly written wrong-way-round
  (asserting the gate *fails*), including a `expectRejected` helper that distinguishes a deliberate
  refusal from a crash.
- `frontend/package.json` — added `"audit": "node scripts/audit-gate.mjs"`.
- `frontend/vitest.config.js` — `include` extended with `scripts/**/*.test.js` so the gate's own
  tests run in `npm test`.
- `.github/workflows/frontend-ci.yml` — "Audit dependencies" step now runs `npm run audit`, with a
  comment explaining that threshold and scope are unchanged.

## Commands Run
```bash
cd frontend
npm ci
npm audit --audit-level=moderate          # 10 high — reproduced the CI failure locally
npm audit --json                          # walked the via graph -> 3 root advisories
npm audit --omit=dev --audit-level=moderate   # proved 7 of 10 are dev-only
npm audit fix                             # postcss 8.5.16 -> 8.5.23
npm run audit                             # the new gate
npm run lint
npm test
npm run build
npm run test:e2e
```

Registry/compat probes (throwaway dirs, not in the repo): `npm view` for
`brace-expansion` / `minimatch` / `react-router` / `react-router-dom` versions, dist-tags and publish
dates; `require()` probes of `brace-expansion@5.0.8`, `brace-expansion@1.1.16` and `minimatch@10.2.5`
to establish the export-shape incompatibility.

## Test / Build Results
- `npm run audit`: **PASS** (exit 0) — 9 records, all cleared by reviewed exceptions, none expired
- `npm run lint`: **PASS** — 0 errors, 1 warning (`PayrollPage.jsx:312` `react-hooks/exhaustive-deps`,
  **pre-existing on main**, untouched)
- `npm test`: **PASS** — 66 files, 597 tests (was 65/574; +1 file, +23 = the new gate tests)
- `npm run build`: **PASS**
- `npm run test:e2e`: **PASS** — 25/25 Playwright specs (mock frontend, port 5250)
- Backend: **not run** — no backend file touched

### Mutation-check of the gate (per CLAUDE.md, "Mutation-check the guard")
A gate that cannot fail is not a gate, so the guard was broken on purpose six ways. Baseline
18/18 green; each mutation reverted to a byte-identical file (`diff -q` clean) before the next:

| Mutation | Tests red |
|---|---|
| M1 allowlist becomes a blanket waiver | 8 |
| M2 `reviewBy` never expires | 2 |
| M3 failure exit code swallowed | 10 |
| M4 severity threshold ignored | 9 |
| M5 partial coverage clears a package | 7 |
| M6 required-field validation removed | 1 |
| M7 unknown severity treated as below-threshold | 2 |
| M8 `reviewBy` date-format validation removed | 1 |
| **reverted** | **0 — 23/23 green** |

⚠️ **A first pass of this check was misleading and was redressed.** Under M1 the "must fail" tests
went green while the guard was defeated: the script hit a `TypeError` (formatting an allowlist entry
that didn't exist), which also exits 1, so `expect(code).toBe(1)` passed *on the crash*. Two fixes:
the script no longer crashes on that path, and every deliberate rejection now emits a `FAIL:` banner
which the tests assert on (`expectRejected`) instead of trusting the exit code alone. The table above
is from the second pass, after that correction. Worth knowing: an initial `grep` over the results was
also wrong — it matched the substring "FAIL" inside test *names*.

### Self-review pass — four fail-OPEN holes found after the gate was already green
The gate was then attacked with the edge cases originally written up for the reviewer. Four inputs
made it **return 0 on something it should have refused** — the worst failure mode for a gate, since
it looks healthy:

| Input | Was | Now |
|---|---|---|
| Record with **no `severity` field** | exit 0 — `indexOf(undefined)` is `-1`, read as below-threshold | exit 1 |
| **Unrecognised severity** (e.g. a future npm label) | exit 0 — same path | exit 1 |
| `reviewBy: "2026-9-01"` (not zero-padded) | exit 0 — sorts *after* `2026-10-23`, so **never expires**; a typo would have granted a permanent waiver | exit 1 |
| `reviewBy: "2026-02-30"` (impossible date) | exit 0 | exit 1 |

Fixes: unknown/absent severity is now treated as in-scope and therefore requires review; `reviewBy`
must be a real zero-padded ISO date (regex + round-trip `Date` check). Confirmed no false positives —
a genuine `low` advisory and a clean report still pass. All four are locked in as regression tests
(M7/M8 above mutation-check them).

**The `2026-9-01` case is the one worth remembering:** it is the difference between a 90-day deferral
and a silent permanent exemption, and nothing in the test suite would have caught it before.

## Authz Evidence
**No authorization change in this task.** No role gate, scope/filter, or read/write rule was touched;
`frontend/src/**` and the entire backend are unmodified. The change is dependency versions plus a CI
step. The e2e run includes `rbac.spec.js`, but that is mock-driven and is **not** offered as authz
evidence.

## Decisions Made
- **D-1** Allowlist gate over `--omit=dev` / raising the threshold — keeps the gate at `moderate`
  across prod + dev and makes each exception explicit, argued and dated. (Owner-approved.)
- **D-2** Exceptions hard-fail past `reviewBy` (90 days → `2026-10-23`). (Owner-approved.)
- **D-3** Allowlist keys on the **root** advisory, not on affected packages — 2 entries clear all 9
  records, and a new advisory sharing a chain still fails (tested).
- **D-4** A package is cleared only when **every** root cause is reviewed, so a reviewed advisory
  can't shelter an unreviewed one on the same dependency (tested, mutation M5).
- **D-5** No `overrides` pin for brace-expansion/minimatch — empirically breaks the ESLint toolchain.
- **D-6** `npm run lint` still runs `eslint src` only; `scripts/` is not linted. Left as-is to keep
  the diff tight — flagged below rather than silently changed.

## Assumptions
- The 90-day review window is a starting point, not a commitment; the owner can shorten it.
- npm's advisory ranges are taken as authoritative for *what is patched*. Reachability was argued
  from this codebase, not from severity scores.

## Known Risks
- **R-1 (accepted, low)** The react-router advisory is a **production** dependency. The exception
  rests on the advisory's own "RSC only" scoping plus a grep showing no RSC usage. If anyone adopts
  react-router's RSC APIs before the v8 migration, this exception becomes wrong. Mitigation: the
  `reason` field states the precondition explicitly, and `reviewBy` forces a re-look by 2026-10-23.
- **R-2 (low)** The gate depends on npm's `--json` shape (`vulnerabilities[].via`). An npm CLI change
  could break parsing — but it fails **closed** (unparseable report → exit 1), tested.
- **R-3 (low)** `reviewBy` will fire in CI on 2026-10-24 for whoever is on the branch that day. That
  is the intended behaviour, but it will look like an unrelated failure if this handoff isn't read.
- **R-4 (informational)** `scripts/` is outside `eslint src`, so the gate script and its tests are
  not linted (see D-6).

## Things Not Finished
- **Not committed.** Working tree only, as required — no commit, push, PR, or merge without the
  owner's say-so. CI has therefore **not** been observed green on a PR yet; every CI step was run
  locally and passes (see above), but the final task item — "verify frontend-ci goes green on the
  PR" — is outstanding pending permission to push.
- **react-router v7 → v8 migration** — the only real fix for `GHSA-qwww-vcr4-c8h2`. Needs its own
  branch: 54 imports move `react-router-dom` → `react-router`, plus v8 breaking changes.
- The `xlsx`-style recurrence risk is reduced but not eliminated: a future *unfixable and reachable*
  production advisory would still redden CI, correctly.

## Recommended Next Agent
Claude Opus review — then, on the owner's say-so, commit + PR.

## Exact Next Prompt
```
Review branch fix/frontend-audit-advisories (uncommitted, worktree
.claude/worktrees/amazing-shamir-8da5b6) against docs/agent-handoffs/114_fix-frontend-audit-advisories.md.

Do not rubber-stamp — try to break it:
1. Re-verify the reachability claims independently. Is react-router's RSC path really unused? Is the
   eslint/minimatch chain really dev-only? Don't take the handoff's word for it.
2. Attack frontend/scripts/audit-gate.mjs — find a way to make an advisory pass without being in
   audit-allowlist.json. Already covered and regression-tested (don't just re-run these): missing
   severity, unrecognised severity, non-zero-padded reviewBy, impossible date, empty `via`, `via`
   object with no url/source, cyclic `via`, partial coverage. Look for what is NOT on that list —
   e.g. duplicate GHSA ids across entries, an allowlist entry whose `id` is an empty-ish value that
   still passes the truthiness check, a `via` chain deeper than the dedupe `seen` set handles, or
   `metadata.vulnerabilities` disagreeing with the `vulnerabilities` map.
3. Confirm the mutation-check holds. Re-run at least M1 and M3 from the handoff table yourself and
   confirm baseline is 23/23 and the revert is byte-identical.
4. Confirm no application code changed: `git diff --stat` should show only package-lock.json,
   package.json, vitest.config.js, the two new scripts/ files, audit-allowlist.json, the workflow,
   and this handoff.
5. Sanity-check the lockfile diff really is patch-level only and introduced no direct-dependency
   change.

Then run: cd frontend && npm run audit && npm run lint && npm test && npm run build && npm run test:e2e
(free port 5250 first if the e2e webServer complains it is in use).

Report findings; do not commit or merge.
```
