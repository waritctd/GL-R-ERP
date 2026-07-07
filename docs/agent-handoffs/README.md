# Agent Handoff System

This folder is the shared memory between Claude, Codex, and reviewer agents.

Every agent must:
1. Read `00_MASTER_CONTEXT.md`.
2. Read the latest relevant handoff file.
3. Run `git status`.
4. Stay inside the assigned scope.
5. Update the relevant handoff file before stopping.

## Files in this folder
- `00_MASTER_CONTEXT.md` — product identity, priorities, non-negotiable rules, and the v0.1.0 Definition of Done. Read first, every time.
- `01_STABILIZATION_AUDIT.md` — the brutal current-state audit, prioritized fix plan (P0/P1/P2), exact branch sequence, and agent assignments.
- `README.md` — this file: how the handoff process works and the template to use.
- Per-branch handoff files (create as work starts), named `NN_<branch-name>.md` (e.g. `02_fix-mobile-app-shell.md`).

## Process
1. Pick the next branch from the sequence in `01_STABILIZATION_AUDIT.md`.
2. Create a handoff file for it from the template below (or open the existing one).
3. Do the work inside scope, on one focused branch.
4. Run the relevant tests/builds.
5. Fill in every section of the handoff file before you stop.
6. Hand off to the recommended next agent with the exact next prompt.

## Handoff Template

```markdown
# Agent Handoff

## Task
<What this agent was asked to do>

## Branch
<branch name>

## Base Commit
<commit hash before work started>

## Current Commit
<latest commit hash, if committed>

## Agent / Model Used
<Claude Opus / Claude Sonnet / Codex GPT-5.3-Codex / etc.>

## Scope

### In Scope
- ...

### Out of Scope
- ...

## Files Changed
- path/to/file: what changed

## Commands Run
​```bash
<commands>
​```

## Test / Build Results
- Frontend build: pass/fail/not run
- Backend tests: pass/fail/not run
- Lint: pass/fail/not run

## Decisions Made
- ...

## Assumptions
- ...

## Known Risks
- ...

## Things Not Finished
- ...

## Recommended Next Agent
<Claude Opus review / Codex implementation / etc.>

## Exact Next Prompt
​```
<prompt for the next agent>
​```
```
