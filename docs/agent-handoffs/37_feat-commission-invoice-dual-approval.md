# 37 feat/commission-invoice-dual-approval

## Branch

`feat/commission-invoice-dual-approval`

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

## Next reviewer prompt

Review PR for `feat/commission-invoice-dual-approval`. Focus on:

- V35 schema compatibility with existing commission data.
- Role transition enforcement for manager vs CEO approval/rejection.
- Notification recipient selection for sales managers and CEO users.
- Invoice upload persistence path and metadata linkage.
- Frontend stage controls and mock API parity.
