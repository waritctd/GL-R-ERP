# STACKED-PRS.md — the stacked-PR rule for GL-R-ERP

This repo routinely has **many parallel sessions/agents building at once**. Branches cut from a stale
`main`, and unrelated work piled onto one branch, are the two failure modes that have actually cost
this project time: Flyway version collisions, a whole feature built on a base `main` had already
moved past, and reviews too large to review.

**Stacking is the answer to "my next task depends on work that is still in review".** It is *not* a
way to keep piling onto one branch.

> **Each PR is one reviewable unit, based on exactly one thing: `main`, or the single PR it truly
> depends on. Nothing else.**

The mechanics are handled by **Git Town** (`git town`, installed via `brew install git-town`), with
its configuration checked in at [`git-town.toml`](git-town.toml) so every session and worktree
behaves identically. Do not hand-roll `git rebase --onto` chains when `git town sync --stack` does
the same thing without orphaning branches.

---

## 1. Decide: stack, or branch off `main`?

Before creating a branch, ask **one** question: *does this work fail to compile, fail its tests, or
fail review without unmerged work from another branch?*

| Answer | Do this |
|---|---|
| **No** — it stands alone | `git town hack <branch>` — branches off `main`. **Default.** |
| **Yes** — it needs the branch you are on | `git town append <branch>` — stacks a child on the current branch. |
| "It's *related*, same area, same feature" | **Not a dependency.** `git town hack`. |
| "It'd be annoying to resolve conflicts later" | **Not a dependency.** `git town hack`. |

Convenience is never a reason to stack. A stack is a **liability**: every PR in it is blocked by
everything below it, and every sync of the bottom ripples through the whole chain.

**Hard limits:**
- **Max depth 3** (`main → A → B → C`). Deeper than that, stop and get the bottom merged first.
- **One parent per branch.** If you need work from two unmerged branches, that means the bottom one
  should be merged now — not that you need a diamond. Git Town enforces a single parent by design.
- **The bottom of a stack must be the smallest, most mergeable, least controversial piece.** Schema
  and shared plumbing at the bottom; UI and feature surface on top. A stack whose bottom PR is the
  contentious one blocks everything for days. `git town prepend` inserts a new parent *below* the
  current branch if you realise mid-flight that a base layer needs to split out.

---

## 2. Creating a stacked branch

Check you are not on a stale base first — this repo has had a whole feature built on one:

```bash
git fetch origin && git rev-list --left-right --count HEAD...origin/main
```

Then:

```bash
git town hack   feat/parent-work     # independent branch, off main
git town append feat/child-work      # child of the branch you are currently on
```

`git town append` records the parent in the repo's git config, which is what makes every later
`sync`, `propose` and `diff-parent` know the shape of the stack. **A branch created with plain
`git switch -c` has no recorded parent** — Git Town will treat it as a child of `main` and sync it
against the wrong base. If you already made one, fix it rather than working around it:

```bash
git town set-parent          # interactive; or: git town config get-parent
git town branch              # print the whole local branch hierarchy — use this constantly
```

Per repo policy, `share-new-branches = "no"` is set in `git-town.toml`: **new branches are never
auto-pushed and never auto-proposed.** Push and PR-creation happen only when the user asks.

### Worktrees, one per branch

Parallel sessions must not share a checkout. Each branch in a stack gets its own worktree under
`.claude/worktrees/`:

```bash
git worktree add .claude/worktrees/<short-name> -b feat/child-work feat/parent-work
git -C .claude/worktrees/<short-name> town set-parent   # record the lineage
```

Lineage lives in the shared `.git/config`, so every worktree sees the same stack.

**One implementation agent per branch** still holds — and in a stack it is stricter: **do not edit a
lower branch while an upper branch is in flight.** Every commit added to the parent invalidates the
child's base and forces a resync. If the parent needs a fix, tell the owner of the stack, land it,
then resync deliberately (§4).

---

## 3. What the PR body must say

The PR body is the handoff in this repo (see `CLAUDE.md`). A stacked PR must additionally carry a
**Stack** block at the very top, so a reviewer never reviews the parent's diff by mistake:

```markdown
## Stack
- Base: `feat/parent-work` (#471) — **must merge first**
- This PR: 2 of 3
- Above: `feat/grandchild-work` (#474) — blocked on this
- **Review only the diff against `feat/parent-work`** (`git town diff-parent`). Commits from
  #471 appear in the "Commits" tab and are not part of this review.
```

Create the proposal against the recorded parent — Git Town sets the base branch for you:

```bash
git town propose                      # this branch only, based on its parent
git town propose --stack              # a proposal for every branch in the stack
git town propose --title "feat(x): …" --body-file <file> --no-browser
```

Everything the normal PR checklist requires (files changed, commands run, test/build results, authz
evidence, known risks) still applies **per PR** — a stacked PR does not inherit its parent's
evidence. If the child changes authorization, the child ships its own real-DB integration test
through the real Java service.

**Do not run `propose` unless the user asked for a PR.**

---

## 4. Keeping the stack alive

`main` moves. Resync the **whole chain**, not one branch:

```bash
git town sync --stack        # sync every branch in the current stack, bottom-up
git town sync --all          # every local branch (use after a long absence)
git town sync --stack --dry-run   # print the git commands without running them
```

> ⚠️ **Two things to know before you run `sync`.**
>
> **1. It pushes.** `git town sync` runs `git push -u origin <branch>` as part of its normal
> operation — it is *not* a read-only "catch up on `main`". Never run it when the user has not asked
> for a push. `git town sync --stack --dry-run` prints the exact git commands first; use it.
>
> **2. It is currently blocked repo-wide.** Sync's first step is `git fetch --prune --tags`, which
> fails because all three annotated release tags point at different commits locally than on origin:
>
> ```
> ! [rejected]  v0.1.0 -> v0.1.0  (would clobber existing tag)   ... same for v0.2.0, v0.3.0
> ```
>
> This is pre-existing and unrelated to any feature work (re-confirmed 2026-08-08). **Do not
> `fetch --tags --force` to unblock a PR** — that silently discards local annotated tag objects for
> published releases as a side effect of unrelated work. Until an owner decides which side is
> authoritative, sync the chain by hand (`git fetch origin main`, then merge parent into child
> bottom-up) or push and `gh pr create --base <parent>` directly; both reach the same end state.

`git-town.toml` sets `feature-strategy = "merge"`: syncing **merges** the parent into the child
rather than rebasing it. That is deliberate for this repo — rebasing rewrites history another
parallel session may already have based work on, and forces a `--force-with-lease` push on every
branch in the chain. The merge strategy needs neither. The child's PR diff against its parent still
shows exactly the child's own changes.

After a sync, sanity-check the shape:

```bash
git town branch                                   # the hierarchy, as Git Town understands it
git town diff-parent                              # what this PR actually contains
git merge-base --is-ancestor feat/parent-work feat/child-work && echo "child still on parent"
```

If a sync stops on conflicts, resolve and then use Git Town's own resume — do not improvise:

```bash
git town continue     # after resolving
git town skip         # skip the current branch, continue the rest
git town status       # what command is suspended
git town undo         # revert the last Git Town command entirely
```

`git town undo` is the escape hatch that makes this safe to run: it restores the pre-command state,
including branch positions. Reach for it before hand-repairing a stack.

---

## 5. Merging a stack

**Bottom-up, one at a time, and never out of order.**

1. Merge the bottom PR **on GitHub**, after review — this repo merges via the PR UI, not the CLI.
2. Use the **merge-commit** button. **Never squash a PR that has children.** Squashing rewrites the
   parent's commits into one new SHA; the child still carries the originals, so its diff balloons
   back to the parent's changes plus its own and the merged commits look unmerged. All three merge
   methods are enabled on this repo, so the wrong one is one click away.
3. GitHub auto-retargets the child PR's base to `main`. Confirm: `gh pr view <child> --json baseRefName`.
4. Resync and re-run the child's checks:
   ```bash
   git town sync --stack
   ```
5. Repeat for the next branch up.

Branch protection here is weaker than the docs imply — **a red suite can merge clean.** Check
`gh pr checks` on *each* PR of the stack, not just the top one.

`git town ship` is **not** part of this workflow (it would merge locally and bypass review).
`git-town.toml` pins `ship-strategy = "always-merge"` purely so that a stray `ship` can never squash
a branch that has children.

Clean up merged branches with `git town sync --all --prune` or `git town delete <branch>`.

**Merge only on the user's explicit say-so.** Never commit, push, or merge unasked.

---

## 6. Flyway migrations in a stack — the collision trap

Migration numbers are the #1 way parallel branches destroy each other here, and stacking makes it
worse because the parent's `Vnnn` is not on `main` yet.

- **Pick the next version from the APPLIED history plus every unmerged branch and worktree**, never
  from what `main` shows:

  ```bash
  git fetch origin
  git branch -a --format='%(refname:short)' | while read b; do
    git ls-tree -r --name-only "$b" -- backend/src/main/resources/db/migration 2>/dev/null
  done | grep -oE 'V[0-9]+' | sort -u -V | tail -5
  ```

- **A child branch takes the number after its parent's**, not after `main`'s.
- **Migrations are forward-only.** Never edit an applied `Vnnn` in place — including one that only
  exists on the parent branch, if that parent has been applied anywhere (UAT counts).
- If the parent's migration number changes during review, the child's must move too. Do that as a
  deliberate resync and re-run the backend suite — a renumber has destroyed a UAT database here
  before.
- Merged ≠ applied. A merged migration may still be unapplied in prod; do not assume a number is
  taken in the DB because it is taken in git, or vice versa.

---

## 7. Quick reference

```bash
brew install git-town                 # one-time, per machine

git town hack   feat/thing            # new branch off main (default)
git town append feat/thing-part-2     # stack a child on the current branch
git town prepend feat/base-layer      # insert a new parent below the current branch
git town set-parent                   # fix lineage for a branch made with `git switch -c`

git town branch                       # show the stack
git town up / down / switch           # move around the stack
git town diff-parent                  # what THIS PR contains, excluding the parent

git town sync --stack                 # resync the whole chain after main moves
git town continue | skip | undo       # after conflicts, or to back out entirely

git town propose [--stack]            # open the PR(s), based on the recorded parent — only when asked
gh pr checks <n>                      # every PR in the stack, not just the top
```

**The three rules that matter most:** stack only on a true dependency; merge bottom-up with a merge
commit, never a squash; and never touch a lower branch while an upper one is open.
