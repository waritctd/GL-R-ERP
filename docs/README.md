# GL-R-ERP Documentation Index

This repository is a GL&R **HR + Sales/CRM portal** growing into an ERP platform. It is not a
complete ERP yet.

## Start here
- [`../AGENTS.md`](../AGENTS.md) — **read first, every time.** Stack, commands, the rules that apply
  everywhere, and the branch and release workflow. `CLAUDE.md` only imports it.
- [`../backend/AGENTS.md`](../backend/AGENTS.md) and [`../frontend/AGENTS.md`](../frontend/AGENTS.md) —
  conventions and gotchas for each side, including the mock-API contract.
- `frontend/src/index.css` — the source of truth for design tokens.

## Where the per-branch handoffs went
`docs/agent-handoffs/` was retired in 2026-07 (along with `docs/ui-repair/` and `docs/ux-ui-audit/`).
The corpus had grown to ~260 files nobody read end-to-end, and a stale plan is worse than none.

**The commit message and the handoff note carry it now** — see the Workflow section of
[`../AGENTS.md`](../AGENTS.md) for what a session hands over. The code's own comments carry the
reasoning.

Nothing is lost; the files are in git history:

```bash
git log --diff-filter=D --oneline -- docs/agent-handoffs   # find the removal commit
git show <sha>^:docs/agent-handoffs/<path>                 # read any retired file
```

Source comments still cite these paths — treat such a pointer as a history reference, not a live
file.

## Living references
- [`sales-workflow.md`](sales-workflow.md) — canonical current-state sales/deal workflow.
- [`role-scoped-views.md`](role-scoped-views.md) — spec for role-scoped nav + per-role Overview
  landings (updated as each role ships).
- [`versioning.md`](versioning.md) — how a release gets its version number, and where it is set.
- [`decisions/`](decisions/) — architecture decision records.
  - [`decisions/quotation-deposit-invoice-model.md`](decisions/quotation-deposit-invoice-model.md) —
    Sales document model: a dedicated table per document type.
- [`least-privilege-db-role.md`](least-privilege-db-role.md) — least-privilege database role (`hr_app`)
  rollout runbook.
- [`V4(PDF Generator)/`](V4(PDF%20Generator)/) — source-of-truth `.xls` document templates the PDF
  generator loads, plus the "use the real templates, don't regenerate" note.
- [`Catalouge/`](Catalouge/) — how the 9-factory price catalog (~22k rows) was ingested
  (parser logic, engine, factory profiles).

## Environment / setup
- Root [`README.md`](../README.md) — project setup, dev commands, frontend/backend split.
- [Production maintenance mode](../README.md#maintenance-mode-during-cutover) — enable/disable the
  temporary production notice, rebuild requirements, and the Vercel/on-prem API routing.
- [`frontend/.env.example`](../frontend/.env.example) and [`backend/.env.example`](../backend/.env.example) —
  required environment variables for each app.

## Archive
- [`archive/`](archive/) — historical / superseded planning docs (M0 survey, early ticket-dashboard
  and quotation-revision plans). Kept for reference. See [`archive/README.md`](archive/README.md).
