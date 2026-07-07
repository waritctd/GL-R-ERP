# Agent Handoff

## Task
Phase 3: migrate the `LeavePage` create-request form from manual `useState`/`updateForm` state to `react-hook-form` + `zod`, exactly following the pattern already merged for `OvertimePage` in Phase 2a (`docs/agent-handoffs/19_rhf-forms-overtime-pilot.md`). Business logic (validation rules, submit payload) must stay byte-for-byte identical.

## Branch
`feat/rhf-forms-leave`

## Base Commit
`17e84520e05e71ada34049c59a4a619dab54032e` (`main`, `origin/main`)

## Current Commit
Not committed.

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- Replace the Leave create-request form's manual `form` state / `updateForm` flow with `useForm` + `zodResolver`.
- Keep using `FormField` and `fieldErrorId` for labels, errors, and aria wiring (mirrors `OvertimePage`).
- Preserve the existing Leave submit payload shape and value conversions.
- Preserve the existing `startDateInPast` rule and disabled-submit behavior.
- Preserve the start-date → end-date auto-bump behavior (`endDate` only moves forward to match a later `startDate`; never moves backward).
- Add a focused Leave form validation/payload test (`LeavePage.test.jsx`), mirroring `OvertimePage.test.jsx`.

### Out of Scope
- No other form/page conversions (`EmployeeFormModal`, `ChangePasswordModal`, `ChangeRequestModal` are next).
- No frozen sales/CRM edits.
- No `styles.css` changes.
- No data-grid work.
- No business-logic changes (leave-type/date/quota rules and the API payload are unchanged).
- Read side (TanStack Query for requests/balances/employees/types), filters, approval/rejection/cancel dialogs, leave-balance grid, and calendar list are unchanged.

## Files Changed
- `frontend/src/features/leave/LeavePage.jsx`:
  - Added imports: `zodResolver`, `useForm`/`useWatch` from `react-hook-form`, `z` from `zod`.
  - `defaultForm()` now stringifies `employeeId` (DOM/RHF compatibility), matching `OvertimePage`.
  - Added `createLeaveFormSchema({ requireEmployeeId, minStartDate })` — a `zod` schema encoding the same rules as before: `leaveTypeCode`/`startDate`/`endDate`/`reason` required (Thai messages, previously enforced only by the native `required` attribute with no message), `employeeId` conditionally required via `superRefine` when there's more than one submit-eligible employee (same condition as the old `hasMultipleSubmitOptions` native `required`), and the `startDateInPast` rule reproduced via `superRefine` comparing `startDate` to `minStartDate` (today, Bangkok TZ) with the exact original message `'วันที่เริ่มลาต้องไม่ก่อนวันนี้'`.
  - Replaced the local `[form, setForm]` state with `useForm({ resolver: zodResolver(leaveFormSchema), defaultValues: defaultForm(...), mode: 'onChange', reValidateMode: 'onChange' })`.
  - Used `useWatch` for `employeeId`/`startDate`/`leaveTypeCode` so the balances query, `balancesYear`, `startDateInPast`, `invalidateLeave()`, and the success-reset logic all read live form values (previously read from `form.*` state).
  - Replaced the `updateForm('startDate', ...)` auto-bump-endDate logic with `handleStartDateChange` wired to `register('startDate', { onChange: handleStartDateChange })`, using `getValues`/`setValue` — identical behavior: if `endDate < newStartDate`, bump `endDate` to the new `startDate`; otherwise leave `endDate` untouched.
  - Removed the old `updateForm` helper (fields not needing derived behavior now use plain `register(...)`).
  - Submit handler renamed to `submitLeave(values)` and wired via `handleSubmit(submitLeave)`; the payload construction (`Number(employeeId) : null`, `leaveTypeCode`, `startDate`, `endDate`, `reason.trim()`, `attachmentName.trim() || null`, `attachmentUrl.trim() || null`) is unchanged field-for-field.
  - `createMutation.onSuccess` now calls `reset(defaultForm(nextEmployeeId, formLeaveTypeCode))` instead of `setForm(...)`, same semantics.
  - Seed-defaults `useEffect` (default acting employee + leave type once employees/leaveTypes queries land) now reads/writes via `getValues`/`setValue` instead of `setForm`, same triggering conditions (`employeesQuery.data`/`leaveTypesQuery.data`).
  - Submit-form JSX: every field (`พนักงาน`/employee select, `ประเภทการลา`/leave type select, `วันที่เริ่ม`/start date, `วันที่สิ้นสุด`/end date, `ชื่อเอกสาร`, `ลิงก์เอกสาร`, `เหตุผลการลา`/reason) now uses `FormField` + `register(...)` + `aria-invalid`/`aria-describedby` wired to `fieldErrorId`, mirroring `OvertimePage`'s pattern exactly (`noValidate` on the form, native `required` kept as a visual/semantic hint alongside RHF/Zod validation). `endDate`'s `min={formStartDate}` attribute preserved.
  - Submit `<Button type="submit" disabled={saving || startDateInPast}>` unchanged.
- `frontend/src/features/leave/LeavePage.test.jsx` (new): mirrors `OvertimePage.test.jsx` — (a) blocks submit and shows `'วันที่เริ่มลาต้องไม่ก่อนวันนี้'` when start date is set in the past; (b) valid submit asserts `api.leave.create` is called with the exact existing payload shape (derived from the pre-migration code before any edits were made).
- `docs/agent-handoffs/21_rhf-forms-leave.md`: this handoff.

No `package.json`/`package-lock.json` changes — `react-hook-form`, `zod`, `@hookform/resolvers` were already installed from Phase 2a.

## Commands Run
```bash
git status
git checkout -b feat/rhf-forms-leave
npm test -- LeavePage.test.jsx
npm run lint
npm test
npm run build
```
Also used the Claude Code preview tool against the `frontend-mock` launch config (`VITE_USE_MOCKS=true`, port 5200) to browser-verify: HR demo login → Leave page → invalid past start date shows the FormField error and disables submit → valid start date auto-bumps the end date → valid submit succeeds, resets the form, and the leave-balance/quota cards update (pending day appears, remaining quota decreases) with zero console errors.

## Test / Build Results
- Targeted test: pass. `npm test -- LeavePage.test.jsx` → 2 tests passed.
- Full frontend tests: pass. `npm test` → 13 files / 67 tests passed (includes the 2 new Leave tests).
- Lint: pass. `npm run lint` → 0 errors, 9 pre-existing `react-hooks/exhaustive-deps` warnings in untouched files (as expected per task brief).
- Frontend build: pass. `npm run build` → succeeded (`dist/assets/index-BdYnRcte.css`, `dist/assets/index-D0yrqAdz.js`); pre-existing chunk-size warning only, unrelated to this change.
- Backend: not run; out of scope (frontend-only change).
- Browser smoke (mock API, desktop viewport): Leave page rendered with no console errors; RHF required/date-range errors displayed through `FormField`; end-date auto-bump verified; successful submit verified against the mock backend (balance cards updated, form reset to defaults).

## Decisions Made
- Followed the OvertimePage Phase 2a pattern precisely: `useWatch` for fields needed outside the form (here: `employeeId`, `startDate`, `leaveTypeCode`, since they drive the balances query, `balancesYear`, and the success-reset/invalidate calls), `getValues`/`setValue` for the imperative date-bump side effect, and `reset(defaultForm(...))` on successful submit.
- Added Thai messages to previously `required`-only fields (`leaveTypeCode`, `startDate`, `endDate`, `reason`) consistent with how Phase 2a treated the equivalent OT fields — the fields were always effectively required (native HTML `required`), so adding an explicit message is a validation-layer change, not a business-logic change.
- `employeeId` requirement stays conditional on `hasMultipleSubmitOptions`, exactly matching the old JSX branch that only rendered (and required) the employee `<select>` when there was more than one submit-eligible employee.
- Kept the `startDateInPast` check duplicated in both the Zod schema (`superRefine`, gates the resolver/submit) and as a `useWatch`-derived boolean (`startDateInPast`, drives the disabled `Button` and the live error message immediately on change) — same dual-check structure OvertimePage uses for its end-after-start rule.
- `attachmentName`/`attachmentUrl` schema fields are plain `z.string()` (no `.min()`), preserving their original optional/no-`required` behavior.

## Assumptions
- The visible required-field messages for `leaveTypeCode`/`startDate`/`endDate`/`reason` (new, since previously only native `required` with no text) are acceptable under the same rationale Phase 2a used — the task brief says to reproduce "the exact conditions under which each field is invalid," which these messages satisfy; the previously-existing `startDateInPast` message text was kept byte-for-byte.
- No other consumers import `LeavePage`'s internals (e.g. `defaultForm`, `updateForm`) — confirmed via search; `updateForm` was leave-page-local and safely removed.

## Known Risks
- `endDate`'s zod rule is "required" only — there is no min-date/after-start zod rule enforced beyond the HTML `min={formStartDate}` attribute, because the original code also had no JS-level enforcement of `endDate >= startDate` (only the `min` attribute and the auto-bump-on-startDate-change convenience). This preserves existing behavior exactly but means a user could still manually type an `endDate` before `startDate` in browsers that don't enforce the `min` attribute strictly — same risk as before this migration.
- Multi-employee submit path (employee `<select>` required via `superRefine`) is covered by the OT precedent but not by a dedicated Leave test in this PR (both new tests use the single-employee/self path, matching the OT test file's coverage level).

## Things Not Finished
- No commit or push was made.
- No other form-page migrations (`EmployeeFormModal`, `ChangePasswordModal`, `ChangeRequestModal`) — that's Phase 4, next.

## Recommended Next Agent
Claude Opus / human review for Phase 3, then an implementation agent for Phase 4.

## Exact Next Prompt
```
You are the single implementation agent for one branch on GL-R-ERP. Phases 0-3 of the Tailwind/ERP-tooling migration are merged (Phase 3 = LeavePage → react-hook-form + zod). This is Phase 4: migrate the remaining form modals to react-hook-form + zod, one PR each: `EmployeeFormModal`, `ChangePasswordModal` (auth), `ChangeRequestModal` (profile). Do all three as separate small branches/PRs, not one combined PR — pick one to start with (recommend `ChangePasswordModal` first, likely the smallest).

First, read:
1. `CLAUDE.md`, `docs/agent-handoffs/00_MASTER_CONTEXT.md`, `docs/agent-handoffs/README.md`.
2. The reference implementations (your templates): `frontend/src/features/overtime/OvertimePage.jsx` + `.test.jsx` (Phase 2a) and `frontend/src/features/leave/LeavePage.jsx` + `.test.jsx` (Phase 3, this handoff: `docs/agent-handoffs/21_rhf-forms-leave.md`).
3. The target modal component(s) and their current manual `useState`/validation/submit-payload logic.
4. `frontend/src/components/common/FormField.jsx`.

Then run `git status`, confirm a clean tree on up-to-date `main`, and branch off `main` with one focused branch per modal (e.g. `feat/rhf-forms-change-password`).

Hard constraints:
- Do NOT change business logic, validation rules, or submit payload shape/field names/transforms — only change HOW form state and validation are managed.
- Do not touch any other page/component; do not touch frozen sales pages; do not edit styles.css.
- Do not commit or push unless explicitly asked.
- Keep the diff minimal and focused; match surrounding code style.

Task per modal:
- Write a zod schema encoding the exact same validation currently enforced (required fields, password rules/confirmation match if applicable, any format rules), with the exact existing Thai error messages.
- Use `useForm({ resolver: zodResolver(schema) })`; wire `FormField` to RHF `register`/`errors` with `fieldErrorId`/`aria-invalid`/`aria-describedby`, and `noValidate` on the form, mirroring OvertimePage/LeavePage precisely.
- Preserve the submit handler's network call and exact payload shape; preserve reset-on-success/close-on-success behavior.
- Add a `<ModalName>.test.jsx` mirroring `OvertimePage.test.jsx`/`LeavePage.test.jsx`: an invalid case that blocks submit with the expected message, and a valid submit asserting the API is called with the exact existing payload (derive the expected payload from the CURRENT code before changing it).

Verify (must all pass before finishing):
- `cd frontend && npm run lint && npm test && npm run build` — 0 lint errors (9 pre-existing react-hooks/exhaustive-deps warnings in untouched files expected), all tests pass, build green.
- Browser-smoke the modal via the `frontend-mock` preview launch config (VITE_USE_MOCKS=true) if feasible: invalid state shows FormField error + disables submit; valid submit succeeds against the mock and closes/resets the modal.

Deliverable:
- Create `docs/agent-handoffs/22_rhf-forms-<modal-name>.md` from the template in the README (or continue numbering sequentially per PR); fill ALL sections including the exact next prompt for whichever modal(s) remain.
- Do not commit. Report back: the zod schema, how you proved payload parity, lint/test/build results, risks, and files changed.
```
