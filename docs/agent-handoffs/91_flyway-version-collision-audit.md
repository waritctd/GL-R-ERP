# Handoff: Flyway version-number collision audit

Date: 2026-07-20
Branch: `docs/flyway-version-collision-audit` (branched from `origin/main` @ `d42256b`)
Type: **Audit only — no migration files are added, renamed or edited by this branch.**

## Why this exists

While reviewing Step 2 (`feat/sales-factory-quote-costing`) the new `V65` was checked against
`origin/main` and came back free. Checking it against the *deployed databases* and the *other open
branches* told a different story: several version numbers are claimed more than once across
in-flight work, and one of them is already applied to the real production database from a branch
that never merged.

This is the same class of failure that forced the UAT database rebuild on 2026-07-15. Raising it
separately rather than folding a renumber into a review-remediation branch, because merge order —
not this branch — decides who has to move.

## Evidence

### Applied state (queried live 2026-07-20 via Supabase MCP, read-only `SELECT`)

| Environment | Project | Highest applied real migration |
|---|---|---|
| Real / production | `tdyzcqzxmhtxpbouewud` | **`V55` = "quotation doc terms"**, applied 2026-07-18 15:37 |
| UAT | `wuypxdznuhhluwzncafh` | `V54` = "fulfilment and delivery" (+ `V900`–`V909` UAT seeds) |
| `origin/main` (repo) | — | `V54` |

`V55` on production came from `feat/doc-gen-real-templates`, which has **not** merged to `main`.
Production is therefore one migration *ahead* of `main`, on content that exists only on a branch.

### Version numbers claimed more than once

| Migration | `feat/sales-factory-quote-costing` | Other claimant |
|---|---|---|
| `attendance_daily_activation` | `V55` | `V60` on `chore/attendance-daily-migration-hold`; `V55` on `docs/authz-verification-rule` and `feat/attendance-day-view` |
| `quotation_doc_terms` | — | `V55` on `feat/doc-gen-real-templates` — **already applied to production** |
| `close_verification` | `V56` | `V55` on `feat/sales-flow-remediation-part1` (byte-identical content) |
| `cancel_reason` | `V57` | `V56` on `feat/sales-flow-remediation-part1` |
| `audit_trail_integrity` | `V58` | `V57` on `feat/sales-flow-remediation-part1` |
| `pricing_request_product_description_idempotency` | `V60` | collides with `attendance_daily_activation` at `V60` on the hold branch |

`chore/attendance-daily-migration-hold` already diagnosed part of this and renumbered its own copy to
`V60`, recording the reasoning in the migration file itself. That fix does not compose with
`feat/sales-factory-quote-costing`, which independently took `V60`.

### Uncommitted claims in parallel worktrees

Scanning committed branches is not sufficient — two version numbers are currently claimed by files
that exist only in working trees:

| Version | Where | State |
|---|---|---|
| `V65` | `feat/sales-factory-quote-costing` | uncommitted (Step 2 remediation, in progress) |
| `V66` | `feat/special-money-requests` worktree | untracked `V66__special_money_request_schema.sql` |

**The first version free against main, both databases, every open branch and every working tree is
`V67`.**

## The concrete failure this produces

`feat/sales-factory-quote-costing` currently carries `V55__attendance_daily_activation.sql`.
On merge and deploy:

- **Production** already has version `55` recorded (as "quotation doc terms"). The prod profile runs
  with `validate-on-migrate` off, so Flyway skips the file **silently**. The `attendance_daily`
  index and comments are never created, and nothing fails loudly.
- **UAT** is at `V54`, so the same file **does** apply there.

Result: both environments hold version `55`, with different content, permanently. No checksum repair
fixes that — the histories have genuinely diverged. If `validate-on-migrate` is ever enabled on
production, the deploy fails hard and the only remedies are a manual history edit or a rebuild.

## Recommendation

1. **Do not renumber inside a feature branch under review.** Each branch renumbering independently is
   what produced the `V60`-vs-`V60` collision between the hold branch and Step 2.
2. **Agree one merge order**, then renumber from the bottom up, once, against `V67`+.
3. **Adopt the hold branch's rule as repo policy** (it is already written into that migration file):
   pick the next version from what is *applied* per environment — `hr.flyway_schema_history` on both
   databases — not from the highest file in the repo, and check every open branch *and working tree*,
   not just `main`.
4. Consider adding a CI check that fails when two files in the repo, or a file and a deployed
   history row, claim the same version. This audit was manual and would not have been run if the
   Step 2 review had not happened to touch a migration.

## Files changed

- `docs/agent-handoffs/89_flyway-version-collision-audit.md` (this file). Nothing else.

## Commands run

- `git ls-tree` over every remote branch for `backend/src/main/resources/db/migration/`
- `git status --porcelain` over every registered worktree for `backend/src/main/resources/db/`
- `git diff` between the colliding copies of `attendance_daily_activation` and `close_verification`
- Supabase MCP `execute_sql`, read-only `SELECT` on `hr.flyway_schema_history`, both projects

## Tests / build results

Not run — documentation-only branch, no code or migration changes.

## Authz evidence

No authorization change.

## Known risks

- The audit is a snapshot. `V65` and `V66` were uncommitted at the time of writing and may have
  landed, moved, or been abandoned since.
- Production being ahead of `main` on unmerged content is itself unresolved and outside this audit;
  it is recorded here because it is what makes the `V55` collision concrete rather than theoretical.

## Suggested next prompt

Decide the merge order for `feat/doc-gen-real-templates`, `feat/sales-flow-remediation-part1`,
`chore/attendance-daily-migration-hold`, `feat/sales-factory-quote-costing` and
`feat/special-money-requests`; then renumber the losers from `V67` upward in one pass, and add the
CI duplicate-version check.
