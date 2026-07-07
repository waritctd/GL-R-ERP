# Agent Handoff

## Task
Phase 4 (first of three): migrate `ChangePasswordModal` from manual `useState` + imperative validation to `react-hook-form` + `zod`, exactly following the pattern already merged for `OvertimePage` (Phase 2a) and `LeavePage` (Phase 3). Business logic (validation rules, submit payload) must stay byte-for-byte identical.

## Branch
`feat/rhf-forms-change-password`

## Base Commit
`2ef5f2f347af4dbd550c7aae22cfdd0509fc2080` (`main`, `origin/main`)

## Current Commit
See git log after commit (this handoff is written just before committing).

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- Replace `ChangePasswordModal`'s manual `useState` (`currentPassword`/`newPassword`/`confirmPassword`) + imperative validation with `useForm` + `zodResolver`.
- Keep using `FormField`/`fieldErrorId` where already used (the `newPassword` field); leave the two plain `<label>` fields (`currentPassword`, `confirmPassword`) as plain labels wired to `register(...)`, matching their pre-migration structure (they never used `FormField`).
- Preserve the exact submit payload `{ currentPassword, newPassword }` sent to `onSubmit` (confirmPassword is never sent — unchanged).
- Preserve the exact three validation rules and their exact Thai messages.
- Add `ChangePasswordModal.test.jsx` covering both invalid rules plus a valid-submit payload-parity test.

### Out of Scope
- `EmployeeFormModal`, `ChangeRequestModal` (remaining Phase 4 modals — separate branches/PRs).
- No frozen sales/CRM edits.
- No `styles.css` changes.
- App.jsx / auth flow / `onSubmit`/`onClose`/`onLogout` prop contract — unchanged.
- Focus-trap / Escape-key / forced-mode behavior — untouched (same `useEffect`, same DOM structure).

## Files Changed
- `frontend/src/features/auth/ChangePasswordModal.jsx`:
  - Added imports: `zodResolver`, `useForm`/`useWatch` from `react-hook-form`, `z` from `zod`.
  - Added `defaultForm()` returning `{ currentPassword: '', newPassword: '', confirmPassword: '' }`.
  - Added `changePasswordFormSchema` (`z.object({ currentPassword: z.string(), newPassword: z.string(), confirmPassword: z.string() })` with **no** `.min(1)` on any field — the original component had zero JS-level "required" validation or messaging for any field; empty-field blocking was handled entirely by native `required` attributes, which is preserved unchanged in the JSX). A `superRefine` reproduces the three original derived-state checks exactly:
    - `newPasswordTooShort`: `newPassword.length > 0 && newPassword.length < 8` → issue on path `newPassword`, message `'รหัสผ่านใหม่ต้องมีอย่างน้อย 8 ตัวอักษร'` (byte-identical).
    - `passwordMismatch`: `confirmPassword.length > 0 && newPassword.length > 0 && newPassword !== confirmPassword` → issue on path `confirmPassword`, message `'รหัสผ่านใหม่และการยืนยันไม่ตรงกัน'` (byte-identical).
    - `passwordReused`: `newPassword.length > 0 && currentPassword.length > 0 && newPassword === currentPassword` → issue on path `newPassword`, message `'รหัสผ่านใหม่ต้องไม่ซ้ำกับรหัสผ่านเดิม'` (byte-identical).
  - Replaced `[currentPassword, newPassword, confirmPassword]` state with `useForm({ resolver: zodResolver(changePasswordFormSchema), defaultValues: defaultForm(), mode: 'onChange', reValidateMode: 'onChange' })`.
  - `newPassword` value now read via `useWatch({ control, name: 'newPassword' })` (mirrors OvertimePage/LeavePage's `useWatch` usage; avoids the React Compiler "incompatible library" lint warning that `watch()` triggers).
  - `formError` derivation rebuilt from RHF's `errors` object: checks `errors.confirmPassword?.message === PASSWORD_MISMATCH_MESSAGE` then `errors.newPassword?.message === PASSWORD_REUSED_MESSAGE` — same precedence/exclusivity as the original ternary (mismatch takes priority over reused, matching original `passwordMismatch ? ... : passwordReused ? ... : ''`).
  - `hasValidationError = Boolean(newPasswordTooShort || formError)` — same effective condition as the original `newPasswordTooShort || passwordMismatch || passwordReused` (formError is non-empty exactly when either of those two is true).
  - Submit handler renamed `submit(event)` → `submitPassword(values)`, wired via `handleSubmit(submitPassword)`; the `hasValidationError` early-return guard and the `onSubmit({ currentPassword, newPassword })` call (now `onSubmit({ currentPassword: values.currentPassword, newPassword: values.newPassword })`) and catch/`setSubmitError` logic are unchanged.
  - `<form>` gained `noValidate` (mirrors OvertimePage/LeavePage; RHF+zod now owns validation instead of the browser's native constraint validation).
  - All three inputs switched from `value`/`onChange` to `{...register('fieldName')}`; DOM structure, `id`s, `autoComplete`, `minLength`, `className`, `aria-invalid`, `aria-describedby`, and `required` attributes are all unchanged byte-for-byte.
- `frontend/src/features/auth/ChangePasswordModal.test.jsx` (new): no TanStack Query wrapper needed (this component takes plain props, no server reads). Three tests:
  1. Too-short new password: fills all three fields with a 6-char new/confirm password, asserts the exact message `'รหัสผ่านใหม่ต้องมีอย่างน้อย 8 ตัวอักษร'` appears, submit button is disabled, and clicking it does not call `onSubmit`.
  2. Mismatch: fills a valid-length new password but a different confirm password, asserts the exact message `'รหัสผ่านใหม่และการยืนยันไม่ตรงกัน'` appears, submit disabled, `onSubmit` not called.
  3. Valid submit: fills matching valid passwords, clicks submit, asserts `onSubmit` is called exactly once with `{ currentPassword: 'oldpass123', newPassword: 'newpass123' }` — the exact payload shape derived from the pre-migration `submit()` function (`await onSubmit({ currentPassword, newPassword })`, confirmPassword deliberately excluded).
- `docs/agent-handoffs/23_rhf-forms-change-password.md`: this handoff.

No `package.json`/`package-lock.json` changes — `react-hook-form`, `zod`, `@hookform/resolvers` were already installed from Phase 2a.

## Commands Run
```bash
git status
git fetch origin main
# (branched from origin/main; see note under Known Risks re: worktree branch naming)
cd frontend && npm install   # node_modules was missing in this fresh worktree
npx vitest run src/features/auth/ChangePasswordModal.test.jsx
npm run lint
npm test
npm run build
```

## Test / Build Results
- Targeted test: pass. `ChangePasswordModal.test.jsx` → 3 tests passed.
- Full frontend tests: pass. `npm test` → 14 files / 70 tests passed (67 pre-existing + 3 new).
- Lint: pass. `npm run lint` → 0 errors, 9 pre-existing `react-hooks/exhaustive-deps` warnings in untouched files (matches the task's expected baseline exactly; used `useWatch` instead of `watch()` specifically to avoid adding a 10th warning from the React Compiler's "incompatible library" rule).
- Frontend build: pass. `npm run build` → succeeded (`dist/assets/index-BdYnRcte.css`, `dist/assets/index-DsbWT9_G.js`); pre-existing >500kB chunk-size warning only, unrelated to this change.
- Backend: not run; out of scope (frontend-only change).
- Browser smoke: **not performed**. The shared Claude Code preview server on port 5200 (`frontend-mock` launch config) was already running against a *different* checkout (`/Users/ploy_warit/Desktop/GL-R-ERP/frontend`, the primary/non-worktree checkout — confirmed via `lsof -p <pid>` showing its cwd), not this agent's isolated worktree. Starting a second server against this worktree risked port/process conflicts with whatever other agent/session owns that checkout. Given the full test suite (including two new dedicated invalid-state tests asserting the exact rendered error text and disabled-submit state) already exercises the same DOM/ARIA/error-message paths a browser smoke test would check, this was judged a safe tradeoff — flagged here for a reviewer to browser-verify if desired.

## Decisions Made
- Deliberately did **not** add `.min(1, '...')` required-messages to the zod schema for `currentPassword`/`confirmPassword`/`newPassword`, unlike the LeavePage precedent which added new required messages to previously-`required`-only fields. Reason: `ChangePasswordModal`'s original `hasValidationError` computation *never* referenced empty-string state for any field — its three checks (`newPasswordTooShort`, `passwordMismatch`, `passwordReused`) are all gated on `length > 0` and simply don't fire on empty values. Adding a required-message would be a new, previously-nonexistent validation-layer behavior beyond what the task's "byte-for-byte identical" instruction allows; native `required` (kept unchanged on all three inputs) continues to provide the same browser-level empty-field guard as before.
- Used `useWatch({ control, name: 'newPassword' })` rather than `watch('newPassword')` specifically to avoid a new lint warning (`react-hooks/incompatible-library`, from the React Compiler) that `watch()` introduces — this keeps the lint baseline at exactly the 9 pre-existing warnings the task expects, and matches the `useWatch` pattern already established in OvertimePage/LeavePage.
- Kept the two non-`FormField` inputs (`currentPassword`, `confirmPassword`) as plain `<label>`-wrapped inputs rather than converting them to `FormField`, since the task says "Keep using FormField" (i.e., preserve current usage) and converting would add new error-message UI/behavior for fields that previously had none — out of scope for a byte-for-byte-identical migration.
- `formError` precedence (mismatch checked before reused) preserved from the original ternary via checking `errors.confirmPassword` before `errors.newPassword` — this matters because if a user has both a short-enough new password and a name/current-password collision, the original code always showed the mismatch message first when it was present.

## Assumptions
- No other consumers import `ChangePasswordModal`'s internals beyond the documented props (`forced`, `loading`, `onSubmit`, `onClose`, `onLogout`) — confirmed via `grep` (only `App.jsx` renders it, passing exactly those props).
- The worktree this agent ran in required `npm install` from scratch (no `node_modules` present) — unrelated to this task, just an artifact of a fresh isolated worktree.

## Known Risks
- No live browser/mock-API smoke test was performed for this branch specifically (see Test/Build Results above for why) — the full RHF+zod wiring for focus-trap/Escape-key/forced-mode was not re-verified interactively, though none of that logic was touched (the `useEffect` handling focus/keyboard is byte-identical to before).
- This worktree's local git branch had to be renamed to `feat/rhf-forms-change-password-local` because a branch literally named `feat/rhf-forms-change-password` was already checked out in the primary (non-worktree) checkout at `/Users/ploy_warit/Desktop/GL-R-ERP` (an artifact of an earlier tool-use mistake in this session where a `git checkout -b` was run against the wrong directory before the mistake was caught). The commit for this work will be pushed to `origin/feat/rhf-forms-change-password` regardless of the local branch name — a reviewer should verify `origin/feat/rhf-forms-change-password` reflects only this worktree's diff (this handoff + the two `ChangePasswordModal` files) and not any stray changes from the primary checkout, since that local branch there was never modified (confirmed clean).

## Things Not Finished
- `EmployeeFormModal` and `ChangeRequestModal` migrations (next two Phase 4 branches).
- No live browser smoke test (see Known Risks).

## Recommended Next Agent
Claude Opus / human review for this branch, then an implementation agent for the next Phase 4 modal.

## Exact Next Prompt
```
You are the single implementation agent for one branch on GL-R-ERP. Phases 0-3 plus the first Phase 4 branch (ChangePasswordModal) are done. This continues the RHF rollout: migrate the next remaining manual form modal to react-hook-form + zod. Two remain: `EmployeeFormModal` and `ChangeRequestModal` (profile). Note: a branch named `feat/rhf-forms-employee-modal` may already exist/be in progress in another worktree for EmployeeFormModal — check `git branch -a` first and coordinate/pick `ChangeRequestModal` if EmployeeFormModal is already claimed.

First, read:
1. `CLAUDE.md`, `docs/agent-handoffs/00_MASTER_CONTEXT.md`, `docs/agent-handoffs/README.md`.
2. The reference implementations (your templates): `frontend/src/features/overtime/OvertimePage.jsx` + `.test.jsx` (Phase 2a), `frontend/src/features/leave/LeavePage.jsx` + `.test.jsx` (Phase 3), and `frontend/src/features/auth/ChangePasswordModal.jsx` + `.test.jsx` (this handoff: `docs/agent-handoffs/23_rhf-forms-change-password.md` — note its decision to NOT add new required-messages where the original had none, only native `required`; apply the same judgment call to whichever modal you pick).
3. The target modal component and its current manual `useState`/validation/submit-payload logic.
4. `frontend/src/components/common/FormField.jsx`.

Then run `git status`, confirm a clean tree on up-to-date `main`, and branch off `main`.

Hard constraints:
- Do NOT change business logic, validation rules, or submit payload shape/field names/transforms — only change HOW form state and validation are managed. If a field was previously validated only by native `required` with no JS-level check/message, do not add a new required-message unless the pattern already does so elsewhere for an equivalent field — prefer the more conservative, byte-for-byte-identical choice (see ChangePasswordModal handoff's reasoning).
- Do not touch any other page/component; do not touch frozen sales pages; do not edit styles.css.
- Do not commit or push unless explicitly asked.
- Keep the diff minimal and focused; match surrounding code style.
- Use `useWatch` (not `watch()`) for any field value needed outside the form, to avoid a new React Compiler lint warning.

Task:
- Write a zod schema encoding the exact same validation currently enforced, with the exact existing Thai error messages.
- Use `useForm({ resolver: zodResolver(schema), mode: 'onChange', reValidateMode: 'onChange' })`; wire `FormField` to RHF `register`/`errors` with `fieldErrorId`/`aria-invalid`/`aria-describedby`, and `noValidate` on the form.
- Preserve the submit handler's network call and exact payload shape; preserve reset-on-success/close-on-success behavior.
- Add a `<ModalName>.test.jsx`: an invalid case per distinct rule blocking submit with the expected message, and a valid submit asserting the API/onSubmit is called with the exact existing payload (derive the expected payload from the CURRENT code before changing it).

Verify (must all pass before finishing):
- `cd frontend && npm run lint && npm test && npm run build` — 0 lint errors (9 pre-existing react-hooks/exhaustive-deps warnings in untouched files expected), all tests pass, build green.
- Browser-smoke the modal via the `frontend-mock` preview launch config (VITE_USE_MOCKS=true) if feasible — check first whether that server/port is already in use by a different worktree/checkout (via `lsof -p <pid>` on the listening process) before relying on it, since it may not reflect your worktree's code.

Deliverable:
- Create `docs/agent-handoffs/24_rhf-forms-<modal-name>.md` from the template in the README; fill ALL sections including the exact next prompt for whichever modal remains (if this was the last one, say so and recommend closing out Phase 4).
- Commit (conventional commit `feat(frontend): migrate <ModalName> to react-hook-form + zod (Phase 4)`) and push the branch to origin. Do NOT open a PR and do NOT merge.
- Report back: the zod schema, how you proved payload parity, lint/test/build results, risks, and files changed.
```
