# 37 feat/commission-invoice-dual-approval

## Branch

`feat/commission-invoice-dual-approval`

## Update (review pass — no fixes needed)

Claude Opus reviewed this branch end-to-end (backend logic, repository SQL, tests, and a live
browser walkthrough) and found **nothing requiring a fix** — a first for this review series. Every
lesson from the prior two cycles was correctly applied without prompting a second time:
- Reject was built symmetrically with approve at both stages from the start (`managerReject`/
  `ceoReject`, mirroring `managerApprove`/`ceoApprove`), including notifications on both reject paths.
- `frontend/src/api/mockApi.js` was kept in sync with the new status model and dual approve/reject
  dispatch (unlike the overtime branch, which needed this fixed in review).
- `CommissionPage.jsx` doesn't use react-hook-form `<select>` at all (plain controlled inputs), so
  the select-binding bug class from the leave branch doesn't apply here.
- Dashboard pending-approval counts were proactively generalized to include both `SUBMITTED` and
  `MANAGER_APPROVED` (the overtime branch had left this as an explicit out-of-scope note).

**Live verification (Claude Opus, via `frontend-mock` on a temporary `VITE_ENABLE_SALES=true` launch
config — reverted after testing, not part of this diff):** logged in as Sales → filled the commission
form → attached a real file via a `DataTransfer`-constructed `File` object (not just checked the input
attributes) → submitted (correctly required the file) → logged in as Sales Manager → manager-approved
(stat moved 1 "รอผู้จัดการ" → 0, "รอ CEO" → 1) → logged in as CEO → **CEO-rejected** (the same
transition class fixed in `feat/overtime-ceo-approval`) → confirmed terminal `REJECTED` state with the
rejection reason attached, the invoice file name (`tax-invoice.pdf`) visible in the row, and no
approve/reject actions remaining. This completes the file-submission test that Codex's own handoff
below noted its tooling couldn't perform (no `File`/`DataTransfer` in its browser sandbox).

Minor, non-blocking observations (not fixed, noted for awareness):
- No test exercises "CEO cannot skip ahead and manager-approve a SUBMITTED record" (the inverse of
  the existing `salesManagerCannotCeoApproveManagerApprovedCommission` test). The code is correct —
  `requireManager` only accepts `sales_manager` — just untested from this direction.
- `CommissionPage`'s "แก้ไขค่าหัก" (edit deductions) button still renders for `REJECTED`/`VOID`
  records; the backend correctly 409s if clicked (`updateDeductions` guards both statuses), so this
  is a dead click, not a data-integrity issue. Pre-existing pattern, not introduced by this branch.
- `CommissionAttachmentRepository.findFilePathById` is unused (no download endpoint yet) — same
  accepted pattern as the leave branch's "no attachment download UI in this branch."

## Context read

- `docs/agent-handoffs/00_MASTER_CONTEXT.md`
- `CLAUDE.md`
- `docs/agent-handoffs/36_feat-overtime-ceo-approval.md`
- Sections A/F/G of `~/.claude/plans/1-quirky-stroustrup.md`

## Implemented

- Added Flyway `V35__commission_invoice_dual_approval.sql`.
- Added `sales.invoice_details.invoice_attachment_id` referencing `hr.file_attachment`.
- Added commission manager/CEO approval metadata and rejection metadata.
- Added `MANAGER_APPROVED` and `REJECTED` commission statuses.
- Required commission tax invoice upload on submit, stored through `FileStorageService` under `commission-invoice/{invoiceId}`.
- Added commission attachment metadata persistence in `hr.file_attachment`.
- Split approval flow:
  - Sales/sales manager/CEO submit with invoice file.
  - Sales manager: `SUBMITTED -> MANAGER_APPROVED`.
  - CEO: `MANAGER_APPROVED -> APPROVED`.
  - Sales manager and CEO can reject at their respective stages.
- Kept payroll-ready commission logic on `APPROVED` only.
- Updated active/recalculation paths to exclude `VOID` and `REJECTED`.
- Added audit entries for submit, manager approve, CEO approve, manager reject, CEO reject, deduction update, and clawback.
- Added `NotificationService.notify(...)` calls on submit, each approval stage, and both rejection stages.
- Updated frontend commission page with required invoice file upload, image compression, manager/CEO stage labels, role-based approve buttons, and matching reject buttons.
- Kept `frontend/src/api/mockApi.js` in sync with status changes and rejection flow.

## Tests

- `cd backend && ./mvnw -B clean verify`
  - PASS
  - 303 tests run, 0 failures, 0 errors, 0 skipped.
  - Testcontainers/Flyway DB path ran; migrations applied through V35.
- `cd frontend && npm run lint && npm test && npm run build`
  - PASS
  - Lint exited 0 with existing React hook dependency warnings.
  - Vitest: 17 files, 84 tests passed.
  - Vite build passed.

## Live UI smoke

- Ran frontend mock dev server with sales routes enabled:
  - `VITE_USE_MOCKS=true VITE_ENABLE_SALES=true npm run dev -- --host 127.0.0.1 --port 5200 --strictPort`
- Verified `/commissions` through the SPA after mock Sales login.
- Confirmed the commission page renders the required tax invoice file control:
  - `input[type=file]` count: 1
  - `required`: present
  - `accept`: `application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png`
- Note: the in-app browser automation surface does not expose a supported file-picker or `setInputFiles` API, and its page sandbox lacks `File`/`DataTransfer`, so the live smoke could not complete an actual browser file submit without adding tooling or test-only app hooks. Backend unit tests cover invoice-file-required and approval/rejection state transitions.

## Files changed

- `backend/src/main/resources/db/migration/V35__commission_invoice_dual_approval.sql`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionAttachmentRepository.java`
- `backend/src/main/java/th/co/glr/hr/commission/ReviewCommissionRequest.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionController.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionRecord.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionRepository.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionService.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionStatus.java`
- `backend/src/main/java/th/co/glr/hr/commission/InvoiceDetails.java`
- `backend/src/main/java/th/co/glr/hr/dashboard/DashboardRepository.java`
- `backend/src/test/java/th/co/glr/hr/commission/CommissionServiceTest.java`
- `frontend/src/api/hrApi.js`
- `frontend/src/api/mockApi.js`
- `frontend/src/api/routes.js`
- `frontend/src/features/commissions/CommissionPage.jsx`

## Guardrails carried

- No payroll/tax/commission calculation or tier math changes.
- Reject exists alongside approve at both manager and CEO stages.
- `mockApi.js` reflects the new status model.
- No React Hook Form select changes were needed in `CommissionPage`.

## Next reviewer prompt (superseded — branch reviewed clean and merged)

~~Review PR for `feat/commission-invoice-dual-approval`. Focus on: V35 schema compatibility...~~
Done — see "Update (review pass — no fixes needed)" above. Merged to `main`.

## Recommended Next Agent

Codex — implement the last round-1 branch: `feat/payroll-refresh-button` (Prompt 5 from
`~/.claude/plans/1-quirky-stroustrup.md` Section H).

## Exact Next Prompt

```text
Repo GL-R-ERP. First read docs/agent-handoffs/00_MASTER_CONTEXT.md, CLAUDE.md, and Sections A/F/G of
~/.claude/plans/1-quirky-stroustrup.md. Run `git status`; branch off `main` (feat/commission-invoice-
dual-approval is now merged). Match existing patterns: TanStack Query for server state (PayrollPage
already uses it), SessionContext auth if any new endpoint is needed, AuditService on real mutations.
This branch is a good stopping point for round 1 -- if it's small and low-risk, it's fine to be a
lighter review than the last three branches. Frontend React + TanStack Query. Smallest reviewable
diff. Do NOT change any payroll/tax/commission calculation math -- this branch only adds a manual
refresh trigger, nothing computational. Create docs/agent-handoffs/38_feat-payroll-refresh-button.md.
Tests: `cd backend && ./mvnw -B clean verify` (only if a backend endpoint is added) and
`cd frontend && npm run lint && npm test && npm run build`. If a live check is useful, verify via the
frontend-mock preview server before calling it done. Open a PR and STOP -- do not merge.

TASK — feat/payroll-refresh-button:
Add a manual "Refresh / re-pull latest" button to the payroll processing page (PayrollPage) that
invalidates and refetches the payroll TanStack Query so HR can force-pull the latest attendance/leave/
OT/commission before processing. Frontend-only if a plain refetch suffices; add a re-pull endpoint
only if needed. Do NOT change any payroll math. Acceptance: clicking the button refetches; existing
tests pass.
```
