# UI Repair Rules

Rules of engagement for the GL-R ERP UI/UX repair. These sit **on top of**, and
never override, [`CLAUDE.md`](../../../CLAUDE.md) and the frontend design charter at
[`frontend/.claude/rules/frontend-ui.md`](../../../frontend/.claude/rules/frontend-ui.md).
Where this doc and those disagree, those win.

## Scope: Phases 0–3

This effort is **audit, architecture, and foundation work — not a redesign.**

- **Phase 0** — Guardrails & baseline. Governance docs + a verified baseline.
- **Phase 1** — Audit. Evidence-backed findings about the current UI.
- **Phase 2** — Information architecture. Nav / route / role IA proposals.
- **Phase 3** — Design foundation. Token & shared-primitive consolidation notes.

**No production screen may be visually redesigned before Phase 4.** If a task would
change how a real screen looks or behaves for a user, it is out of scope here.

## What must not change (as a side effect of UI work)

The one rule that never relaxes: **do not change business logic** (payroll, tax,
commission, pricing/FX math). Beyond that, UI-repair work must not alter:

- **Routes** — `frontend/src/App.jsx`, `frontend/src/app/permissions.js`,
  `frontend/src/app/roles.js`, `frontend/src/api/routes.js`.
- **API calls / contracts** — `frontend/src/api/**`.
- **Permissions, role gates, or scope/filters** — the `ROLE_PERMISSIONS` matrix,
  `canAccessPath`, `RequireAccess`, and their backend counterparts.
- **Status / stage transitions** — ticket/deal/pricing-request state machines.
- **DB schema / migrations** — `backend/**`, Flyway `Vnnn` files.

The sales/CRM stack is unfrozen at the repo level, but **within this UI-repair
effort** none of the above is a target. If such a change is genuinely required, it
stops being a UI-repair task: split it out, state it plainly, and follow the
repo's normal review + authz-evidence rules — never smuggle it under a UI change.

## Design vocabulary — reuse only, no one-offs

There is exactly one sanctioned design vocabulary: the tokens in
[`DESIGN.md`](../../../DESIGN.md) and the rules in
[`frontend-ui.md`](../../../frontend/.claude/rules/frontend-ui.md).

- **No new one-off colours, shadows, radii, spacing values, buttons, dialogs,
  table styles, or responsive breakpoints.** Use the existing token/primitive; if
  none fits, *propose* a new shared one (see Change Control) rather than inventing
  a local value.
- **No new component framework or UI library.** The stack is fixed: Tailwind 4,
  `class-variance-authority`, `tailwind-merge`/`clsx` (via `src/utils/cn.js`),
  TanStack Table, React Hook Form + Zod.
- **No automatic global CSS→Tailwind conversion.** The ~2.2k-line legacy
  `frontend/src/styles.css` is retired one *verified* component or workflow at a
  time, with screenshots — never as one blind rewrite.
- **No new page-specific CSS files.** If native CSS is genuinely unavoidable,
  document why (Change Control).

## Review & testing before merge

Every UI-repair PR must, before merge:

1. Pass `npm --prefix frontend run lint`.
2. Pass `npm --prefix frontend test` (Vitest).
3. Pass the **relevant** `npm --prefix frontend run test:e2e` (Playwright) specs.
4. Follow the post-change validation checklist in `frontend-ui.md` (render the
   screen, check console + failed network requests, report remaining limitations).
5. Carry a completed [Change Control](CHANGE_CONTROL.md) checklist.
6. Receive human review. Reviewer agents do not implement beyond trivial fixes.

**Authorization is never verified against the mock.** If a change touches a role
gate or scope/filter, it needs a real-DB integration test through the real Java
service (see `CLAUDE.md`), or the permission aspect is reported **unverified**.

## Screenshot evidence

Any change that alters what a screen looks like ships **desktop and mobile**
screenshots (the app's canonical mobile breakpoint is `720px`).

- Store under `docs/ui-repair/evidence/current/` (before) and
  `docs/ui-repair/evidence/proposed/` (after), named for the role + screen.
- Large image binaries follow the repo's existing `.gitattributes`/gitignore/LFS
  conventions (as the prior `docs/ux-ui-audit/` screenshots do) — do not commit
  multi-MB binaries into normal git blobs.

## Secrets & test credentials

- **Never commit real credentials, tokens, API keys, or production data** into this
  tree or anywhere in the repo.
- Verification uses **mock-login personas only** — the mock frontend
  (`VITE_USE_MOCKS=true`, the Playwright config's port-5250 server, or the
  `frontend-mock` launch config). Do not paste real user passwords into docs or
  tests.
- Evidence screenshots must not expose real employee PII, salary figures, or
  customer data — use mock/synthetic data.

## Emergency production UI fix

An **emergency production UI fix** is narrowly defined: a change needed to restore a
**broken or unusable production workflow** — e.g. a screen that crashes, a control
that can't be reached, an action that can't be completed, or a WCAG regression that
blocks a user from doing their job. It is *not* a redesign, a polish pass, or a
"while we're here" improvement.

An emergency fix may bypass the Phase-4 gate, but not the safety rails:

- It stays the **smallest possible diff** that restores function.
- It still passes lint + unit + relevant e2e and ships before/after evidence.
- A CEO/owner (or their delegate) approves the exception.
- It is recorded as an exception in [`CHANGE_CONTROL.md`](CHANGE_CONTROL.md) with a
  link to the PR, so a reviewer can distinguish an emergency from scope creep.

## Documenting exceptions

Any deviation from these rules — an emergency fix, a temporarily necessary one-off
value, a new page-specific CSS file — is logged as an **exception** in the
Exceptions section of [`CHANGE_CONTROL.md`](CHANGE_CONTROL.md), with: what the
exception is, why it was necessary, its **owner**, and its **removal condition**
(what must be true for it to go away). An undocumented deviation is a defect.
