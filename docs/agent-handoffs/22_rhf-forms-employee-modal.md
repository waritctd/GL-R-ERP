# Agent Handoff

## Task
Phase 3 (continued): migrate `EmployeeFormModal` (used for both create-employee and edit-employee) from manual `useState`/imperative `onChange`/native-`required` validation to `react-hook-form` + `zod`, following the pattern already merged for `OvertimePage` and `LeavePage`. Business logic (validation rules, submit payload shape/transforms) must stay byte-for-byte identical.

## Branch
`feat/rhf-forms-employee-modal`

## Base Commit
`2ef5f2f347af4dbd550c7aae22cfdd0509fc2080` (`main`, includes PR #136 — LeavePage RHF migration)

## Current Commit
See the commit created at the end of this session (not amended after; check `git log -1`).

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- Replace `EmployeeFormModal`'s local `[form, setForm]` state and `update(field, value)` helper with `useForm({ resolver: zodResolver(schema), defaultValues, mode: 'onChange', reValidateMode: 'onChange' })`.
- Write a `zod` schema reproducing the exact prior validation: `nameTh` required, `email` required + must look like an email (previously enforced only by the browser via `type="email" required`, silently, no visible message), `phone` required. All other fields stay optional (no `.min()`).
- Keep using `FormField`/`fieldErrorId` for every field (the previous version used bare `<label>` elements with no `FormField`, no inline error text, and no `aria-invalid`/`aria-describedby` — this is now aligned with the OvertimePage/LeavePage pattern per the task brief).
- Preserve create-vs-edit mode: title/subtitle switch (`employee ? 'แก้ไขข้อมูลพนักงาน' : 'เพิ่มพนักงาน'` / `employee.code` vs `'Employee database'`), and all `defaultValues` seeded from an existing `employee` object exactly as before (including nested reads: `employee?.currentAddress?.line1`, `employee?.emergencyContact?.name`, `employee?.emergencyContact?.phone`).
- Preserve the `divisionOptions` derivation (merge of known `divisions` + options derived from the `employees` list + a fallback entry if the current employee's division isn't in either), the `departmentOptions` derivation filtered by the currently selected division, and the exact submit payload: `{ ...formValues, divisionTh: <label of matched division>, salary: Number(salary || 0) }` — every original field name is preserved untouched in the spread.
- Add `frontend/src/features/employees/EmployeeFormModal.test.jsx`: required-fields-empty case, invalid-email-format case, valid create-submit payload-parity case, and an edit-mode defaults-seeded + submit-preserves-values case.

### Out of Scope
- No other form/page conversions (`ChangePasswordModal`, `ChangeRequestModal` remain — see Exact Next Prompt).
- No frozen sales/CRM edits.
- No `styles.css` changes.
- No changes to `EmployeeListPage.jsx` / `EmployeeDetailPage.jsx` (callers) — their `onSubmit`/`onCreateEmployee`/`onUpdateEmployee` wiring is untouched; `EmployeeFormModal` never called the API directly (it only calls the `onSubmit` prop), so no network-call code exists inside this file to preserve/migrate beyond the payload shape.
- No `package.json` changes — `react-hook-form`, `zod`, `@hookform/resolvers` were already installed.

## Files Changed
- `frontend/src/features/employees/EmployeeFormModal.jsx`:
  - Added imports: `zodResolver`, `useForm`/`useWatch` from `react-hook-form`, `z` from `zod`; added `FormField`/`fieldErrorId`.
  - Added `employeeFormSchema` (module-level, no dynamic conditions needed — unlike Leave/Overtime, no field here has a conditional-required rule): `nameTh: z.string().min(1, 'กรุณาระบุชื่อ-นามสกุล')`, `email: z.string().min(1, 'กรุณาระบุอีเมล').pipe(z.email('รูปแบบอีเมลไม่ถูกต้อง'))`, `phone: z.string().min(1, 'กรุณาระบุเบอร์โทร')`, and every other field as a bare `z.string()` (`nameEn`, `nickName`, `divisionId`, `departmentTh`, `positionTh`, `level`, `statusId`, `hireDate`, `locationTh`, `address`, `emergencyName`, `emergencyPhone`) or `z.union([z.string(), z.number()])` for `salary` (the input is `type="number"` but RHF's default `register` keeps values as strings; the union avoids coercion surprises while `Number(values.salary || 0)` still runs at submit time exactly as before).
  - Removed the local `[form, setForm]` state, the `update(field, value)` handler, and the imperative `submit(event)` handler; replaced with `useForm(...)` (`register`, `handleSubmit`, `control`, `formState.errors`) and a `submit(values)` function passed to `handleSubmit`.
  - `divisionOptions` derivation (`useMemo`) is unchanged logic, now depends on `employee`/`employees` only (no longer reads `form.divisionId`, since division-select is no longer local state).
  - `departmentOptions` now derives from `useWatch({ control, name: 'divisionId' })` (`formDivisionId`) instead of `form.divisionId` — same filtering logic (`!formDivisionId || item.divisionId === formDivisionId`). Used `useWatch` (not the bare `watch()` accessor) to stay compatible with the React Compiler, matching the `react-hooks/incompatible-library` guidance already followed in `LeavePage`/`OvertimePage` (an initial `watch()`-based draft produced that lint warning; switched to `useWatch` and the warning disappeared).
  - `submit(values)` builds the exact same payload as before: finds the matching `division` from `divisionOptions`, then `onSubmit({ ...values, divisionTh: division?.label, salary: Number(values.salary || 0) })`. Every original field name (`nameTh`, `nameEn`, `nickName`, `email`, `phone`, `divisionId`, `departmentTh`, `positionTh`, `level`, `salary`, `statusId`, `hireDate`, `locationTh`, `address`, `emergencyName`, `emergencyPhone`) is present via the spread, unchanged.
  - Every field in the JSX now uses `FormField` + `register(...)` + (for the three validated fields) `aria-invalid`/`aria-describedby` wired to `fieldErrorId`, and the `<form>` has `noValidate`, mirroring `OvertimePage`/`LeavePage` exactly. The `datalist` for `departmentTh` autocomplete is preserved (`list="department-options"` on the registered input).
  - No `useEffect`, no `reset()` call was added: the original component had no reset/success-effect either (it just unmounts via `onClose` after the parent's `onSubmit` promise resolves, per `EmployeeListPage.submitCreate`/`EmployeeDetailPage.submitEdit` — both close the modal by flipping `creating`/`editing` state after `await`ing the parent mutation, unchanged since this file was not touched).
- `frontend/src/features/employees/EmployeeFormModal.test.jsx` (new): four tests —
  1. blocks submit and shows all three required-field messages when `nameTh`/`email`/`phone` are empty;
  2. blocks submit and shows the email-format message for an invalid (non-empty) email;
  3. valid create-mode submit asserts `onSubmit` is called with the exact payload shape (derived from the pre-migration code, using default division `'10'` / `'AC-ฝ่ายบัญชี'` since `divisionOptions[0]` is the first entry of the module-level `divisions` array and `employees=[]`);
  4. edit-mode: seeds all `defaultValues` from a fixture `employee` object (including nested `currentAddress`/`emergencyContact`), asserts the inputs display the seeded values, and asserts an unmodified submit reproduces the same values in the payload (with `divisionTh` recomputed from the matched division, `salary` coerced through `Number(...)`). Uses a far-future `hireDate` (`2099-01-15`) to avoid any date-dependent flakiness (`hireDate` has no zod rule, but the fixture avoids look-alike-today values as a matter of test hygiene).
- `docs/agent-handoffs/22_rhf-forms-employee-modal.md`: this handoff.

## Commands Run
```bash
git status
git checkout -b feat/rhf-forms-employee-modal
npm ci                      # node_modules were not present in this fresh worktree
npm run lint
npm test -- --run
npm run build
```

## Test / Build Results
- Lint: pass. `npm run lint` → 0 errors, 9 pre-existing `react-hooks/exhaustive-deps` warnings in untouched files (as expected per task brief; no new warnings — an initial `watch()`-based draft did trigger a 10th, `react-hooks/incompatible-library`, warning, resolved by switching to `useWatch`).
- Full frontend tests: pass. `npm test -- --run` → 14 files / 71 tests passed (includes the 4 new `EmployeeFormModal` tests; up from 67 tests in the Phase 3 Leave handoff, since 4 new tests were added and 0 were removed).
- Frontend build: pass. `npm run build` → succeeded; only the pre-existing >500 kB chunk-size warning, unrelated to this change.
- Backend: not run; out of scope (frontend-only change).
- Browser smoke: not run this session (no `frontend-mock` preview server was started); recommend the next reviewer do a quick manual pass (`Create employee` / `Edit employee` flows) before merge, though behavior is covered by the four automated tests plus close reading of the diff.

## Decisions Made
- Used `z.string().min(1, ...).pipe(z.email(...))` for `email` rather than a plain `.email()` chain — this repo's installed `zod` is v4 (`^4.4.3`), where the classic `z.string().email()` chain method still works but the modern top-level `z.email()` format-validator is the documented replacement; piping keeps the "required" and "format" checks as two distinct, independently-worded issues (matching how Leave/Overtime separate "required" vs. rule-specific messages).
- Added visible Thai messages for `nameTh`/`email`/`phone` (previously enforced only by native browser `required`/`type="email"` validation, which produces no visible in-page text and was fully bypassed by RHF/Zod's `noValidate` submit flow) — this mirrors the same category of change Phase 3 (Leave) made for `leaveTypeCode`/`startDate`/`endDate`/`reason`, justified there and here as "reproducing the same validation gate," not a business-logic change, since these fields were always effectively mandatory.
- Did NOT add a Thai message/required rule to any field that was optional before (`nameEn`, `nickName`, `divisionId`, `departmentTh`, `positionTh`, `level`, `salary`, `statusId`, `hireDate`, `locationTh`, `address`, `emergencyName`, `emergencyPhone`) — all remain unconstrained `z.string()`/`z.union([z.string(), z.number()])`, exactly matching their prior lack of a `required` attribute.
- `salary` schema is `z.union([z.string(), z.number()])` (not `z.coerce.number()`) so that RHF's raw string value round-trips through the resolver without the resolver itself performing the `Number()` coercion — the coercion still happens exactly once, at the same place as before (`Number(values.salary || 0)` inside the submit handler), preserving the original transform's behavior (`''` → `0`, `'0'` → `0`, etc.) rather than moving it into validation.
- No `superRefine`/conditional-required logic was needed (unlike Leave/Overtime's `employeeId`) — `EmployeeFormModal` has no field whose required-ness depends on other form/query state, so a plain `z.object({...})` schema is sufficient and is defined at module scope (not `useMemo`'d per-render) since it never changes.

## Assumptions
- `EmployeeListPage.jsx` and `EmployeeDetailPage.jsx` (the two callers) do not read any internal state/export of `EmployeeFormModal` beyond the `employee`/`employees`/`onClose`/`onSubmit` props — confirmed via `grep`; neither file was touched.
- The task brief's instruction to "reproduce the same Thai messages already shown" is read, per the Phase 3 Leave precedent, as "reproduce the same validation *gates*" for fields that were previously silently required via native HTML attributes with no visible message — new Thai text was authored in the same style as the existing Leave/Overtime messages (`กรุณาระบุ...` / `กรุณาเลือก...`) since no prior visible text existed to copy verbatim for `nameTh`/`email`/`phone`.
- No `frontend-mock` browser smoke test was run this session; the four Vitest cases (required-empty, invalid-email, valid-create-payload, valid-edit-payload) are treated as sufficient automated coverage given the component's small, self-contained surface (no network calls, no async queries inside the modal itself).

## Known Risks
- No visible required/format message existed before this change for `nameTh`/`email`/`phone` — end users will now see new Thai validation text they didn't see previously. This is a UX-visible change bundled into an infra migration, consistent with how Phase 3 (Leave) handled the same category of field, but flagging it explicitly since three fields are affected here (vs. the Leave page's already-partial coverage).
- `salary`'s zod type (`z.union([z.string(), z.number()])`) accepts any string, including non-numeric ones; `Number('abc' || 0)` → `NaN` would have flowed into the payload identically both before and after this change (the original code had no numeric-format validation on `salary` either), so this is a pre-existing gap, not a regression — noted for visibility only.
- The four new tests cover required/format/create/edit-payload parity but do not cover the `departmentTh` datalist-filter-by-division interaction or the "existing employee's division not in the known/derived list" fallback-merge branch of `divisionOptions` — both are unchanged logic (verbatim from the pre-migration `useMemo`), so risk is limited to whatever pre-existed.

## Things Not Finished
- No commit was made until this handoff was written (see Commands Run / final commit step below — committed and pushed after this file was completed, per the task instructions).
- Remaining Phase 4 targets: `ChangePasswordModal` (auth) and `ChangeRequestModal` (profile) — not started.
- No browser/manual smoke test via the `frontend-mock` preview config was performed this session.

## Recommended Next Agent
Claude Opus / human review for this branch, then an implementation agent for the next remaining RHF form (`ChangePasswordModal` recommended first — likely the smallest).

## Exact Next Prompt
```
You are the single implementation agent for one branch on GL-R-ERP. Phases 0-3 of the RHF/Tailwind form migration are merged, plus EmployeeFormModal (this handoff: docs/agent-handoffs/22_rhf-forms-employee-modal.md). Continue the RHF rollout: migrate `ChangePasswordModal` (auth) to react-hook-form + zod. (After that, `ChangeRequestModal` (profile) is the last remaining manual form — do it as a separate branch/PR.)

First, read:
1. `CLAUDE.md`, `docs/agent-handoffs/00_MASTER_CONTEXT.md`, `docs/agent-handoffs/README.md`.
2. The reference implementations (your templates): `frontend/src/features/overtime/OvertimePage.jsx` + `.test.jsx`, `frontend/src/features/leave/LeavePage.jsx` + `.test.jsx`, and `frontend/src/features/employees/EmployeeFormModal.jsx` + `.test.jsx` (this handoff).
3. The target `ChangePasswordModal` component — study its current manual `useState`/validation (password rules, confirmation match) and its submit payload/network call.
4. `frontend/src/components/common/FormField.jsx` and `Modal.jsx`.

Then run `git status`, confirm a clean tree on up-to-date `main`, and branch off `main` as `feat/rhf-forms-change-password`.

Hard constraints:
- `react-hook-form`, `zod`, `@hookform/resolvers` are already installed — do not modify `package.json`.
- Do NOT change business logic, password rules, or submit payload shape/field names/transforms — only change HOW form state and validation are managed.
- Do not touch any other file; do not touch frozen sales pages; do not edit `styles.css`.
- Keep the diff minimal and focused; match surrounding code style.

Task:
- Write a zod schema encoding the exact same validation currently enforced (required fields, password length/format rules, confirm-password match via `superRefine` if applicable), with the exact existing Thai error messages where visible text already exists, or new Thai messages in the same style (`กรุณาระบุ...`) for fields that were previously only silently `required`.
- Use `useForm({ resolver: zodResolver(schema), mode: 'onChange', reValidateMode: 'onChange' })`; wire `FormField` to RHF `register`/`formState.errors` with `fieldErrorId`/`aria-invalid`/`aria-describedby`, and `noValidate` on the form.
- Preserve the submit handler's network call and exact payload shape; preserve reset-on-success/close-on-close behavior exactly as today.
- Add `ChangePasswordModal.test.jsx` mirroring `EmployeeFormModal.test.jsx`: an invalid case (e.g. mismatched confirmation, or a rule violation) that blocks submit and shows the expected message; a valid submit asserting the API is called with the exact existing payload (derive the expected payload from the CURRENT code before changing it).

Verify (must all pass before finishing):
- `cd frontend && npm run lint && npm test && npm run build` — 0 lint errors (9 pre-existing react-hooks/exhaustive-deps warnings in untouched files expected), all tests pass, build green.

Finish:
- Create `docs/agent-handoffs/23_rhf-forms-change-password.md` from the template in the README, filling every section; "Exact Next Prompt" continues to `ChangeRequestModal`.
- Commit (`feat(frontend): migrate ChangePasswordModal to react-hook-form + zod (Phase 3)`, ending with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`) and push the branch to origin. Do not open a PR, do not merge.
- Report back: the zod schema, how you proved payload parity, lint/test/build results, files changed, risks.
```
