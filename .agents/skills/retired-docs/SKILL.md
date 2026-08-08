---
name: retired-docs
description: Read a deleted docs/agent-handoffs, docs/ui-repair, or docs/ux-ui-audit file back out of git history.
---

# Recovering retired docs

`docs/agent-handoffs/`, `docs/ui-repair/` and `docs/ux-ui-audit/` were retired in 2026-07. The
per-branch handoff corpus had grown to ~260 files that no one read end-to-end, and a stale copy
is worse than none — an agent following a superseded plan is the failure mode this repo actually
hit. **The PR body is now the handoff**, and the code's own comments carry the reasoning.

Nothing is lost: the files are in git history.

## Find the removal commit

```bash
git log --diff-filter=D --oneline -- docs/ui-repair
```

Swap in `docs/agent-handoffs` or `docs/ux-ui-audit` for the other two directories.

## Read a specific file

```bash
git show <sha>^:docs/ui-repair/<path>
```

The `^` matters — it reads the parent of the removal commit, i.e. the last revision where the file
still existed.

## List everything that was in a directory

```bash
git ls-tree -r --name-only <sha>^ -- docs/ui-repair
```

## Before you rely on one

These docs were retired *because* they went stale. Treat a recovered file as a historical record of
what someone believed at the time, not as a current plan — verify any claim it makes against the
code before acting on it.
