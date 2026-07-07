# Agent Handoff

## Task
Phase 2a: introduce `react-hook-form` + `zod` as the frontend form system, piloted only on `OvertimePage`, while keeping the existing `FormField` wrapper and preserving the overtime submit payload/rules.

## Branch
`feat/rhf-forms-overtime-pilot`

## Base Commit
`2ca165f` (`main`, `origin/main`) — Phase 1b merged via #133

## Current Commit
Not committed.

## Agent / Model Used
Codex GPT-5

## Scope

### In Scope
- Add runtime dependencies: `react-hook-form`, `zod`, `@hookform/resolvers`.
- Replace the Overtime create-request form's manual `form` state / `updateForm` flow with `useForm` and `zodResolver`.
- Keep using `FormField` and `fieldErrorId` for labels, errors, and aria wiring.
- Preserve the existing OT submit payload shape and value conversion.
- Preserve the existing OT end-after-start rule and disabled-submit behavior.
- Add a focused Overtime form validation/payload test.

### Out of Scope
- No other form/page conversions.
- No frozen sales/CRM edits.
- No `styles.css` cleanup.
- No data-grid work.
- No business-logic changes.

## Files Changed
- `frontend/package.json`: added `react-hook-form`, `zod`, and `@hookform/resolvers` as runtime dependencies.
- `frontend/package-lock.json`: lockfile updates from `npm install react-hook-form zod @hookform/resolvers`; `zod` is now a direct runtime dependency.
- `frontend/src/features/overtime/OvertimePage.jsx`: replaced local create-form state with RHF; added a Zod schema for required fields and the existing end-after-start rule; kept `FormField`/`fieldErrorId`; preserved date-change start/end time carry-forward; preserved submit payload order/shape and disabled behavior.
- `frontend/src/features/overtime/OvertimePage.test.jsx`: added tests for invalid time-range blocking and valid payload parity.
- `docs/agent-handoffs/19_rhf-forms-overtime-pilot.md`: this handoff.

## Commands Run
```bash
sed -n '1,260p' CLAUDE.md
sed -n '1,320p' docs/agent-handoffs/00_MASTER_CONTEXT.md
sed -n '1,320p' docs/agent-handoffs/README.md
sed -n '1,360p' docs/agent-handoffs/18_tailwind-phase1b-button-sweep.md
sed -n '1,260p' frontend/src/components/common/FormField.jsx
sed -n '1,760p' frontend/src/features/overtime/OvertimePage.jsx
git status --short --branch
git log --oneline --decorate -5
git switch -c feat/rhf-forms-overtime-pilot
npm install react-hook-form zod @hookform/resolvers
rg -n "OvertimePage|overtime" frontend/src --glob '*.{test,spec}.{js,jsx,ts,tsx}'
rg -n "FormField|fieldErrorId|useForm|zod" frontend/src frontend/package.json
npm run lint
npm test -- OvertimePage.test.jsx
npm test
npm run build
VITE_USE_MOCKS=true npm run dev
```

Browser checks were run against `http://127.0.0.1:5174/overtime` with `VITE_USE_MOCKS=true` in the in-app browser.

## Test / Build Results
- Lint: pass. `npm run lint` completed with 0 errors and the expected 9 pre-existing warnings.
- Targeted test: pass. `npm test -- OvertimePage.test.jsx` completed with 2 tests passed.
- Full frontend tests: pass. `npm test` completed with 12 files / 63 tests passed.
- Frontend build: pass. `npm run build` completed successfully; output included `dist/assets/index-W351DF87.css` and `dist/assets/index-BRO_FJoV.js`.
- Backend tests: not run; out of scope.
- Browser desktop smoke: Overtime route rendered after mock HR login with no console errors. RHF required-field errors displayed through `FormField`.
- Browser mobile smoke: Overtime route rendered at 375px with default start/end values intact, submit button present, and no console errors.

## Decisions Made
- Kept submit disabled semantics aligned with the previous behavior: disabled while saving or when the planned end is not after planned start. Required empty fields are schema-validated on submit rather than disabling the button.
- Kept the end-after-start rule both in Zod and in RHF-watched field state. The watched values preserve the old immediate disabled/error behavior, while the resolver remains the submit gate.
- Used `noValidate` on the Overtime create form so RHF/Zod owns required-field validation and `FormField` owns the error display.
- Normalized `defaultForm().employeeId` to a string for DOM/RHF compatibility; submit still converts it with `Number(...)`, preserving the API payload.
- Preserved the old work-date behavior: changing `workDate` rewrites `plannedStartAt` and `plannedEndAt` to the new date while keeping their current time portions.
- Added tests instead of trying to inspect mock API internals from the browser, because the mock dev session is in-memory and full page reloads reset authentication.

## Assumptions
- The new visible required-field messages are acceptable because Phase 2a explicitly moves validation behind RHF/Zod and `FormField`; the previously custom Thai time-range message remains unchanged.
- Overtime create-form validation is the pilot pattern; remaining form migrations should be separate small PRs.

## Known Risks
- `useForm`'s resolver receives a schema memoized by whether the employee select is visible. The common HR path seeds an employee id, and tests cover the single-employee submit path; multi-employee required-select behavior should get extra attention during review/browser QA.
- Browser automation had limited fidelity with `datetime-local` controls via direct `fill`; the invalid time-range and payload parity are covered by Vitest.
- No other pages were converted, so mixed manual/RHF form patterns remain until Phase 3.

## Things Not Finished
- No commit or push was made.
- No `DataTable` migration.
- No additional form-page migrations.
- No frozen-page edits.

## Recommended Next Agent
Claude Opus review for Phase 2a, then Codex implementation for Phase 2b.

## Exact Next Prompt
```
You are the single implementation agent for one branch on GL-R-ERP. Phases 0–2a of the Tailwind/form migration are merged. This is Phase 2b: re-base `frontend/src/components/common/DataTable.jsx` internals on `@tanstack/react-table`.

First, read:
1. `CLAUDE.md`, `docs/agent-handoffs/00_MASTER_CONTEXT.md`, `docs/agent-handoffs/README.md`, and `docs/agent-handoffs/19_rhf-forms-overtime-pilot.md`.
2. `frontend/src/components/common/DataTable.jsx` and its tests.
Then run `git status`, confirm a clean tree on up-to-date `main`, and branch off `main` with one focused branch.

Task:
- Re-base `DataTable.jsx` internals on `@tanstack/react-table`.
- Preserve its current public props, rendered class names, empty/loading states, search behavior, sort behavior, pagination behavior, and mobile/card behavior.
- Update/add focused tests for sort/search/pagination parity if needed.

Hard constraints:
- No business-logic changes.
- Do not touch frozen sales/CRM pages except through the shared `DataTable` component behavior they already consume.
- Do not convert any more forms in this PR.
- Do not delete legacy CSS.
- Do not commit or push unless explicitly asked.

Verify:
- Run `cd frontend && npm run lint && npm test && npm run build`.
- Browser-check at least one HR `DataTable` consumer and one frozen-page/table consumer if available behind the local flag, at desktop and mobile widths.
- Fill every section of the new handoff, including commands, results, risks, and the exact next prompt.

For Phase 3 after Phase 2b is reviewed/merged: roll `react-hook-form` out to the remaining form pages one small PR each: `LeavePage`, `EmployeeFormModal`, `ChangePasswordModal`, and profile change-request forms.
```
