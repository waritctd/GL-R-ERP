# GL-R-ERP Agent Notes

This repository is a React/Vite frontend with a Spring backend for the GL&R ERP.
It currently spans HR operations and sales/deal workflow surfaces.

## Product And Design Sources

- `PRODUCT.md` explains users, product purpose, operating context, and guardrails.
- `DESIGN.md` defines the approved visual language and design-system rules.
- The `docs/ui-repair/` and `docs/ux-ui-audit/` corpora were retired in 2026-07; `DESIGN.md`
  now carries the design language on its own, and `frontend/src/index.css` is the live source
  of truth for tokens. Older commits and source comments still cite those paths — treat any
  such pointer as a history reference, recoverable with `git show <sha>^:<path>`, not a
  live file.

## Frontend Validation

Run frontend checks from `frontend/`:

```sh
npm run lint
npm run test
npm run build
npm run test:e2e
```

`test:e2e` is the real-backend suite (`frontend/playwright.real.config.js`, driving
`frontend/e2e-real/`); it needs Postgres and a running backend — see
`frontend/e2e-real/README.md`. The mock-frontend suite it used to run was removed on 2026-08-08.
Rendered browser evidence is required for UI repair work, including the affected
roles and viewport sizes named by the phase plan — note that the removal took the viewport/layout
specs with it, so that evidence is now manual until they are ported.

## UI Repair Boundaries

During UI repair, do not change business logic, role permissions, API contracts,
backend status values, status-machine transitions, database schema, or endpoint
behavior unless the task explicitly authorizes that change.

Preserve existing query invalidation, URL query parameters, navigation behavior,
CSV behavior, and role-specific projections unless a documented defect requires a
targeted change.

Use existing shared primitives and approved semantic tokens before adding new UI
structure. Do not duplicate the complete design documentation here.
