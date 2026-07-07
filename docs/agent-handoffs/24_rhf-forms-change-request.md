# Agent Handoff

## Task
Phase 4 (one of three parallel modal migrations): migrate `ChangeRequestModal` (profile "request a field change" modal) from manual `useState`/imperative validation to `react-hook-form` + `zod`, following the pattern already merged for `OvertimePage` (Phase 2a) and `LeavePage` (Phase 3). Business logic (validation rule, submit payload) must stay byte-for-byte identical.

## Branch
`feat/rhf-forms-change-request`

## Base Commit
`2ef5f2f347af4dbd550c7aae22cfdd0509fc2080` (`main`, `origin/main`)

## Current Commit
See latest commit on this branch (created and pushed at the end of this session).

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- Replace `ChangeRequestModal`'s manual `[value, setValue]` state and imperative `submit(event)` handler with `useForm` + `zodResolver`.
- Keep using `FormField`/`fieldErrorId` for the "ค่าใหม่" (new value) field's label/error/aria wiring, mirroring `OvertimePage`/`LeavePage`.
- Preserve the exact submit payload: `{ ...requestField, newValue: <value> }`, passed unchanged to the `onSubmit` prop.
- Add `ChangeRequestModal.test.jsx` mirroring `LeavePage.test.jsx`/`OvertimePage.test.jsx`'s two-case pattern (invalid blocks submit; valid submit asserts payload).

### Out of Scope
- `ProfilePage.jsx` (unchanged; still owns `requestField` state, `onClose`, and wires `onSubmit` to `createProfileRequest` via `useHrData.js` — traced but not touched).
- The other two Phase 4 modals (`EmployeeFormModal`, `ChangePasswordModal`) — being migrated in parallel on separate worktrees/branches by other agents (confirmed via `git worktree list`: `feat/rhf-forms-employee-modal`, `feat/rhf-forms-change-password-local`).
- No frozen sales/CRM edits.
- No `styles.css` changes.
- No business-logic changes.

## Files Changed
- `frontend/src/features/profile/ChangeRequestModal.jsx`:
  - Removed `useState` import; added `zodResolver`, `useForm` (react-hook-form), `z` (zod), and `FormField`/`fieldErrorId` imports.
  - Added `changeRequestFormSchema = z.object({ newValue: z.string().min(1, 'กรุณาระบุค่าใหม่') })` — the only field/rule that existed before (the textarea's native `required` attribute, which had no visible message). The Thai message is new text (previously enforced silently by `required`), following the same precedent Phase 3 (`LeavePage`) set for fields that were `required`-only.
  - Replaced `const [value, setValue] = useState('')` with `useForm({ resolver: zodResolver(changeRequestFormSchema), defaultValues: { newValue: '' }, mode: 'onChange', reValidateMode: 'onChange' })`.
  - `submit(event)` (which called `event.preventDefault()` then `onSubmit({ ...requestField, newValue: value })`) became `submit(values)` wired via `handleSubmit(submit)`, calling `onSubmit({ ...requestField, newValue: values.newValue })` — same object shape, same fields, same spread of `requestField` (`fieldKey`, `fieldLabel`, `oldValue`, `icon`).
  - Form gained `noValidate` (matches `OvertimePage`/`LeavePage`).
  - The "ค่าใหม่" `<textarea>` moved from a bare `<label>` wrapper to `FormField` + `register('newValue')` + `aria-invalid`/`aria-describedby` via `fieldErrorId`; native `required` kept as a visual/semantic hint alongside RHF/Zod validation, matching precedent.
  - The "ค่าเดิม" read-only `<input>` is untouched (not a form field, no validation, no RHF wiring — matches the original).
- `frontend/src/features/profile/ChangeRequestModal.test.jsx` (new): renders the modal standalone (no `QueryClientProvider` needed — the component takes `onSubmit`/`onClose` as plain props and does no data fetching/mutation itself). (a) blocks submit and shows `'กรุณาระบุค่าใหม่'` when the new-value textarea is empty; (b) valid submit asserts `onSubmit` is called once with the exact existing payload shape `{ fieldKey, fieldLabel, oldValue, icon, newValue }` (derived from the pre-migration code's `{ ...requestField, newValue: value }` spread, before any edits were made).
- `docs/agent-handoffs/24_rhf-forms-change-request.md`: this handoff.

No `package.json`/`package-lock.json` changes — `react-hook-form`, `zod`, `@hookform/resolvers` were already installed from Phase 2a.

## Commands Run
```bash
git status
git checkout -b feat/rhf-forms-change-request
npm install         # node_modules were not present in this worktree
npm test -- ChangeRequestModal.test.jsx
npm run lint
npm test
npm run build
```

## Test / Build Results
- Targeted test: pass. `npm test -- ChangeRequestModal.test.jsx` → 2 tests passed.
- Full frontend tests: pass. `npm test` → 14 files / 69 tests passed (includes the 2 new ChangeRequestModal tests).
- Lint: pass. `npm run lint` → 0 errors, 9 pre-existing `react-hooks/exhaustive-deps` warnings in untouched files (as expected per task brief).
- Frontend build: pass. `npm run build` → succeeded (`dist/assets/index-BdYnRcte.css`, `dist/assets/index-Pfymx4pu.js`); pre-existing chunk-size warning only, unrelated to this change.
- Backend: not run; out of scope (frontend-only change).
- Browser smoke: attempted via the `frontend-mock` preview launch config, but the sandboxed preview tool's dev server process resolves to the shared main checkout (`/Users/ploy_warit/Desktop/GL-R-ERP/frontend`) rather than this agent's isolated worktree (`.claude/worktrees/agent-a37c01a44cfda1603/frontend`) — confirmed via `lsof`/`ps` (server cwd) and `git worktree list` (main checkout is on `main`, unmodified). Served JS still reflected the pre-migration source even after a full server restart and page reload. Given this environment limitation, verification relied on the unit tests above (which do exercise the real rendered DOM via `@testing-library/react` + `jsdom`, including the FormField error text and RHF-driven submit blocking) plus lint/build. Recommend a human or a same-checkout agent do a quick manual browser smoke pass before merge.

## Decisions Made
- Followed the `LeavePage`/`OvertimePage` Phase 2a/3 pattern precisely, but simplified for this modal's much smaller scope: `ChangeRequestModal` has exactly one form field and no derived/watched state, no query reads, and no mutation of its own (the mutation lives in `useHrData.js`'s `createProfileRequestMutation`, invoked by the `ProfilePage` parent) — so no `useWatch`, `getValues`/`setValue`, or `reset()` were needed. `defaultValues: { newValue: '' }` reproduces the original `useState('')` initial value exactly, and there is no reset-on-success in the original code either (the parent unmounts the modal via `setRequestField(null)` after a successful submit, which naturally discards the form's local state — preserved).
- Added the Thai message `'กรุณาระบุค่าใหม่'` to the previously `required`-only textarea, consistent with the precedent set in Phase 3 (`LeavePage`) for fields that were always effectively required via native HTML but had no explicit message.
- Test file renders `ChangeRequestModal` directly (no `QueryClientProvider`/mocked `api` module) since the component has zero data-layer dependencies — this is a legitimate simplification of the `LeavePage.test.jsx` pattern given the component's much smaller surface, not a deviation from the "mirror the reference test" instruction in spirit (same two-case structure: invalid blocks submit with message, valid submit asserts exact payload).

## Assumptions
- `requestField` (passed by `ProfilePage`) always includes `fieldKey`/`fieldLabel`/`oldValue`/`icon` — untouched, spread as-is, exactly as before.
- No other consumers import `ChangeRequestModal` besides `ProfilePage.jsx` (confirmed via `grep`).
- The two other Phase 4 modal migrations (`EmployeeFormModal`, `ChangePasswordModal`) happening in parallel on sibling worktrees do not touch this file or `FormField.jsx`/`Modal.jsx` in a conflicting way — each is scoped to its own modal file per `01_STABILIZATION_AUDIT.md`/`21_rhf-forms-leave.md`'s branch-per-modal instruction.

## Known Risks
- Browser/UI smoke test could not be completed in this sandboxed session due to the preview tool binding to the shared main checkout instead of this worktree (see Test/Build Results above) — the change is covered by DOM-level unit tests (jsdom + Testing Library) exercising the real component tree, but a manual visual check (modal open/close, focus trap, textarea resize) has not been done for this specific change. Low risk given the diff is small and mirrors an already-verified pattern (Leave/Overtime), but flagging for reviewer awareness.
- Because three modal migrations (`ChangeRequestModal`, `EmployeeFormModal`, `ChangePasswordModal`) are running concurrently on separate branches/worktrees off the same `main` base, merge order matters only if any of them touch shared files beyond their own modal — this branch touches only `ChangeRequestModal.jsx`/`.test.jsx` and this handoff, so conflict risk is low, but the merging human should sanity-check for import-path or `FormField` API drift across all three PRs before merging all three.

## Things Not Finished
- No PR opened (per task instructions — commit + push only).
- The other two Phase 4 modals are out of scope for this branch (in progress elsewhere per `git worktree list`).

## Recommended Next Agent
Claude Opus / human review for this PR (and the sibling `EmployeeFormModal`/`ChangePasswordModal` PRs), then merge all three once reviewed. After Phase 4 is fully merged, the next phase is page-by-page Tailwind style conversion / legacy CSS cleanup.

## Exact Next Prompt
```
The RHF form rollout (Phase 2a-4: OvertimePage, LeavePage, and the three modal migrations —
ChangeRequestModal, EmployeeFormModal, ChangePasswordModal) is complete once all Phase 4
branches are reviewed and merged. Next is Phase 5: page-by-page Tailwind style conversion /
legacy CSS cleanup (migrate pages off the single global frontend/src/styles.css onto the
Tailwind v4 design-system foundation established in Phase 0/1, one page per branch, smallest
diff first). Read CLAUDE.md, docs/agent-handoffs/00_MASTER_CONTEXT.md, and
docs/agent-handoffs/16_tailwind-phase0-setup.md / 17_tailwind-phase1-button.md /
18_tailwind-phase1b-button-sweep.md for the established Tailwind conventions before starting,
then propose a page migration order and pick the smallest/lowest-risk page to start with.
```
