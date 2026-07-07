# Agent Handoff

## Task
Docs cleanup for v0.1.0 (DoD #9 / audit P2-5 docs part): add a `docs/README.md` index, archive the
frozen-sales planning docs, and prune merged branches. (`.env.example` already exists for both apps.)

## Branch
`docs/v0.1-cleanup`

## Base Commit
`98db5aa` (clean `main`)

## Current Commit
See PR.

## Agent / Model Used
Claude Opus (docs-only mechanical change — done inline rather than delegated; reviewer is the same).

## Scope

### In Scope
- `docs/README.md` index.
- Move the frozen-sales planning docs to `docs/archive/` (+ an `archive/README.md` explaining why).
- Branch pruning (handled as a separate repo-admin step, not part of this PR diff).

### Out of Scope
- Any code change. Any change to the frozen sales stack itself.
- Recreating `.env.example` (already present: `frontend/.env.example`, `backend/.env.example`).
- Rewriting the dated `01_STABILIZATION_AUDIT.md` snapshot (it is a point-in-time record).

## Files Changed
- `docs/README.md` (new) — documentation index: start-here pointers, current docs, env/setup links,
  and the archive pointer.
- `docs/archive/README.md` (new) — explains the archived docs are frozen-sales, reference-only.
- `docs/M0_SURVEY.md` → `docs/archive/M0_SURVEY.md` (git rename).
- `docs/QUOTATION_AND_REVISION_PLAN.md` → `docs/archive/QUOTATION_AND_REVISION_PLAN.md` (git rename).
- `docs/TICKET_DASHBOARD_PLAN.md` → `docs/archive/TICKET_DASHBOARD_PLAN.md` (git rename).
- `docs/quotation_template_source.xlsx` → `docs/archive/quotation_template_source.xlsx` (git rename).

## Commands Run
```bash
git mv docs/{M0_SURVEY.md,QUOTATION_AND_REVISION_PLAN.md,TICKET_DASHBOARD_PLAN.md,quotation_template_source.xlsx} docs/archive/
# + wrote docs/README.md and docs/archive/README.md
```

## Test / Build Results
- Docs-only change — no build/test impact. `git status` shows 4 clean renames + 2 new index files.
- Verified no stale references to the old paths remain outside the dated audit snapshot.

## Decisions Made
- Kept the moved docs in-repo under `docs/archive/` (reference) rather than deleting them.
- Left `01_STABILIZATION_AUDIT.md` untouched — it is a dated audit snapshot, not a living doc.

## Assumptions
- The sales-CRM stack stays frozen for v0.1.0, so its planning docs belong in archive.

## Known Risks
- None material (docs-only).

## Branch Pruning (done 2026-07-07, repo-admin — not part of this PR diff)
Evidence-based prune using GitHub PR state (merged-PR head = safe; open-PR head = protected):
- Deleted **10 local** merged branches and **27 remote** branches (26 whose PRs were merged + 1
  ancestor of `origin/main` with no PR: `feat/sit-uat-blockers`).
- **Kept** the 2 open-PR branches (`backend/openapi-docs` #127, `docs/v0.1-cleanup` #128).
- **Left for owner review — 4 orphan remotes** (no merged PR, not ancestors of `main`; may be
  unmerged/abandoned WIP): `codex/complete-payroll-feature`, `demo/all-roles-showcase`,
  `docs/security-audit-report`, `yang/ticket`.

## Things Not Finished
- Owner to decide on the 4 orphan remote branches above (keep vs delete).

## Recommended Next Agent
Owner: review/merge this PR. Then the remaining v0.1.0 gates are the **desktop-label** (#2) and
**sales-visibility** decisions, followed by the **v0.1.0 tag** (#10). Non-DoD polish left: P2-4
audit-log coverage (leave/overtime/commission/payroll).

## Exact Next Prompt
```
docs/v0.1-cleanup is merged (DoD #9 done). Remaining for the v0.1.0 tag: settle the desktop-label
decision (#2 — add a small "optimized for desktop" notice to payroll processing + attendance import,
or formally accept the mobile card reflow as satisfying it) and the sales-visibility decision
(flag-hide /tickets, /commissions, /ceo-settings, etc. for v0.1.0, or leave them routable). Then do
a final ./mvnw verify + frontend lint/test/build, update 00_MASTER_CONTEXT.md / 01_STABILIZATION_AUDIT.md
to reflect completion, and tag v0.1.0. Ask the owner before tagging.
```
