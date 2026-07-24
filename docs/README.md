# GL-R-ERP Documentation Index

This repository is a GL&R **HR + Sales/CRM portal** growing into an ERP platform. It is not a
complete ERP yet. Start with the master context before doing any work.

## Start here
- [`agent-handoffs/00_MASTER_CONTEXT.md`](agent-handoffs/00_MASTER_CONTEXT.md) — product identity,
  current priorities, and the hard guardrails. **Read first, every time.**
- [`agent-handoffs/README.md`](agent-handoffs/README.md) — how the agent handoff process works and the
  per-branch handoff template.
- [`../CLAUDE.md`](../CLAUDE.md) — the operative agent operating rules (scope, mock-API contract,
  permission-evidence requirement, styling direction).

## Agent handoffs (shared memory)
- [`agent-handoffs/`](agent-handoffs/) — one `NN_<branch>.md` handoff per in-flight task branch,
  plus the anchors above.
- [`agent-handoffs/HANDOFF_LOG.md`](agent-handoffs/HANDOFF_LOG.md) — consolidated index of every
  completed/merged branch (the individual handoffs for those were folded in here on 2026-07-25).
- [`agent-handoffs/01_STABILIZATION_AUDIT.md`](agent-handoffs/01_STABILIZATION_AUDIT.md) — the
  original (2026-07-07) pre-stabilization audit and branch sequence. **Historical baseline.**

## Living references
- [`sales-workflow.md`](sales-workflow.md) — canonical current-state sales/deal workflow.
- [`role-scoped-views.md`](role-scoped-views.md) — spec for role-scoped nav + per-role Overview
  landings (updated as each role ships).
- [`decisions/`](decisions/) — architecture decision records.
  - [`decisions/quotation-deposit-invoice-model.md`](decisions/quotation-deposit-invoice-model.md) —
    Sales document model: a dedicated table per document type.
- [`least-privilege-db-role.md`](least-privilege-db-role.md) — least-privilege database role (`hr_app`)
  rollout runbook.
- [`ux-ui-audit/`](ux-ui-audit/) — the evidence-based UX/UI audit deliverable (report + findings).
  See [`ux-ui-audit/README.md`](ux-ui-audit/README.md).
- [`V4(PDF Generator)/`](V4(PDF%20Generator)/) — source-of-truth `.xls` document templates the PDF
  generator loads, plus the "use the real templates, don't regenerate" note.
- [`Catalouge/`](Catalouge/) — how the 9-factory price catalog (~22k rows) was ingested
  (parser logic, engine, factory profiles).

## Environment / setup
- Root [`README.md`](../README.md) — project setup, dev commands, frontend/backend split.
- [`frontend/.env.example`](../frontend/.env.example) and [`backend/.env.example`](../backend/.env.example) —
  required environment variables for each app.

## Archive
- [`archive/`](archive/) — historical / superseded planning docs (M0 survey, early ticket-dashboard
  and quotation-revision plans). Kept for reference. See [`archive/README.md`](archive/README.md).
