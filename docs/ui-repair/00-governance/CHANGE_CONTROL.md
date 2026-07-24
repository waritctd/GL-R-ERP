# UI Repair Change Control

Every UI change made during the repair (Phases 4+ execution, and any emergency fix)
must satisfy this checklist. It is intentionally short — copy it into the PR
description and fill it in. An unchecked box is a blocker, not a formality.

## Per-change checklist

- [ ] **Role & workflow named.** Which role(s) (`ceo`, `sales`, `sales_manager`,
      `import`, `account`, `hr`, `employee`/division-manager, `warehouse`, `qc`) and
      which workflow this change affects. "All roles" is a valid answer only if it's
      genuinely global.
- [ ] **Primitive reuse.** The change uses an existing shared primitive
      (`frontend/src/components/common/**`) and existing `DESIGN.md` tokens —
      **or** it explicitly proposes a new *shared* primitive (name, API, why no
      existing one fits). No one-off local colours/shadows/radii/buttons/dialogs/
      table styles/breakpoints.
- [ ] **Desktop + mobile evidence.** Before/after screenshots at desktop and at the
      `720px` mobile breakpoint, stored under `evidence/current` and
      `evidence/proposed`. Tested in **both Thai and English**.
- [ ] **All states identified.** The change explicitly handles **loading, empty,
      error, and permission-denied** states for the affected view — not just the
      happy path. Disabled actions explain *why* when the reason isn't obvious.
- [ ] **No forbidden change.** Confirms it does not alter business logic, routes,
      API contracts, permissions/scope, status transitions, or schema (see
      [`UI_REPAIR_RULES.md`](UI_REPAIR_RULES.md)). If it touches authorization, it
      ships a real-DB integration test or reports that aspect **unverified**.
- [ ] **Tests green.** `lint` + unit + relevant Playwright specs pass; results
      pasted into the PR.
- [ ] **New page-specific CSS?** If any native CSS was added, a written explanation
      of why a Tailwind utility / shared component could not do it (logged as an
      exception below).

## Exceptions log

Record every deviation from the rules here. Each row needs an **owner** and a
**removal condition** (what must be true for the exception to disappear). An
exception with no removal condition is technical debt with no exit.

| Date | Exception (what & why) | Type | Owner | Removal condition | PR |
|------|------------------------|------|-------|-------------------|----|
| _(none yet)_ | | | | | |

Exception types: `emergency-fix`, `temporary-one-off-value`, `page-specific-css`,
`other`.
