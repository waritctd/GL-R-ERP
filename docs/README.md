# GL-R-ERP Documentation Index

This repository is a GL&R **HR Portal** in a **stabilization phase** moving toward an ERP platform.
It is not a complete ERP yet. Start with the master context before doing any work.

## Start here
- [`agent-handoffs/00_MASTER_CONTEXT.md`](agent-handoffs/00_MASTER_CONTEXT.md) — product identity,
  current priorities, non-negotiable rules, and the v0.1.0 Definition of Done. **Read first, every time.**
- [`agent-handoffs/README.md`](agent-handoffs/README.md) — how the agent handoff process works and the
  per-branch handoff template.
- [`agent-handoffs/01_STABILIZATION_AUDIT.md`](agent-handoffs/01_STABILIZATION_AUDIT.md) — current-state
  audit, prioritized fix plan (P0/P1/P2), branch sequence, and agent assignments.

## Current docs
- [`agent-handoffs/`](agent-handoffs/) — shared memory between agents: master context, the stabilization
  audit, and one `NN_<branch>.md` handoff per task branch.
- [`decisions/`](decisions/) — architecture decision records.
  - [`decisions/quotation-deposit-invoice-model.md`](decisions/quotation-deposit-invoice-model.md) —
    Sales document model: a dedicated table per document type.
- [`least-privilege-db-role.md`](least-privilege-db-role.md) — least-privilege database role (`hr_app`)
  rollout runbook.
- [`ux-ui-audit.md`](ux-ui-audit.md) — UX/UI audit and fix roadmap.

## Environment / setup
- Root [`README.md`](../README.md) — project setup, dev commands, frontend/backend split.
- [`frontend/.env.example`](../frontend/.env.example) and [`backend/.env.example`](../backend/.env.example) —
  required environment variables for each app.

## Archive
- [`archive/`](archive/) — historical / superseded planning docs for the **frozen** sales-CRM stack.
  Kept for reference; not part of the v0.1.0 HR-core scope. See [`archive/README.md`](archive/README.md).
