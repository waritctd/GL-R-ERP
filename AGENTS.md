# GL-R-ERP Agent Notes

This repository is a React/Vite frontend with a Spring backend for the GL&R ERP.
It currently spans HR operations and sales/deal workflow surfaces.

## Product And Design Sources

- `PRODUCT.md` explains users, product purpose, operating context, and guardrails.
- `DESIGN.md` defines the approved visual language and design-system rules.
- `docs/ui-repair/` contains the UI repair audit, component contracts, token notes,
  and phase plans. Read the relevant phase docs before changing UI surfaces.

## Frontend Validation

Run frontend checks from `frontend/`:

```sh
npm run lint
npm run test
npm run build
npm run test:e2e
```

Playwright starts the mock Vite app through `frontend/playwright.config.js`.
Rendered browser evidence is required for UI repair work, including the affected
roles and viewport sizes named by the phase plan.

## UI Repair Boundaries

During UI repair, do not change business logic, role permissions, API contracts,
backend status values, status-machine transitions, database schema, or endpoint
behavior unless the task explicitly authorizes that change.

Preserve existing query invalidation, URL query parameters, navigation behavior,
CSV behavior, and role-specific projections unless a documented defect requires a
targeted change.

Use existing shared primitives and approved semantic tokens before adding new UI
structure. Do not duplicate the complete design documentation here.
