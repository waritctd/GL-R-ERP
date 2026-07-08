# Agent Handoff

## Task
Implement `feat/leave-autoapprove-upload`: reusable file storage, leave single-file upload, auto-approve/reject rules, notification backbone wiring, and frontend upload/compression/law citation.

## Branch
feat/leave-autoapprove-upload

## Base Commit
d66d79abc98d0c5c4127dd8328ce5f42918658ec

## Current Commit
Not committed yet.

## Agent / Model Used
Codex GPT-5

## Scope

### In Scope
- Extract reusable filesystem storage from ticket attachment upload.
- Add Flyway V33 for generic HR file metadata and `hr.leave_request.attachment_id`.
- Replace leave text attachment fields in app code with a single uploaded file.
- Accept leave attachment via multipart submit.
- Auto-approve eligible leave and auto-reject over-quota, short-notice, and sick-without-certificate submissions.
- Notify via `NotificationService.notify(...)` for leave submit/approve/reject events.
- Add client-side image compression with `browser-image-compression`.
- Add focused acceptance tests for the requested leave submit outcomes.

### Out of Scope
- Payroll/tax/commission math changes.
- Manager rejection after auto-approval.
- Sick certificate threshold variants; all sick leave requires a certificate in this branch.
- Commission invoice upload or other future users of `FileStorageService`.

## Files Changed
- `backend/src/main/resources/db/migration/V33__leave_attachment_autoapprove.sql`: adds `hr.file_attachment`, `hr.leave_request.attachment_id`, and indexes.
- `backend/src/main/java/th/co/glr/hr/attachment/FileStorageService.java`: reusable `{uploads-dir}/{domain}/{id}` file writer with optional MIME allowlist.
- `backend/src/main/java/th/co/glr/hr/attachment/AttachmentController.java`: ticket uploads now use `FileStorageService`.
- `backend/src/main/java/th/co/glr/hr/config/AppProperties.java`: adds leave advance-notice config.
- `backend/src/main/resources/application.yml`: adds `APP_LEAVE_ADVANCE_NOTICE_DAYS`, default 7.
- `backend/src/main/java/th/co/glr/hr/leave/LeaveAttachmentDto.java`: metadata DTO for HR file attachments.
- `backend/src/main/java/th/co/glr/hr/leave/LeaveAttachmentRepository.java`: JDBC repository for `hr.file_attachment`.
- `backend/src/main/java/th/co/glr/hr/leave/LeaveController.java`: adds multipart leave submit and JSON consumes guard.
- `backend/src/main/java/th/co/glr/hr/leave/LeaveRepository.java`: stops writing/reading `attachment_name`/`attachment_url`; writes/reads `attachment_id` and file name.
- `backend/src/main/java/th/co/glr/hr/leave/LeaveRequestDto.java`: exposes `attachmentId` and `attachmentFileName`.
- `backend/src/main/java/th/co/glr/hr/leave/LeaveService.java`: auto-approval/rejection rules, attachment save, and NotificationService calls.
- `backend/src/main/java/th/co/glr/hr/leave/SubmitLeaveRequest.java`: removes old text attachment fields.
- `backend/src/test/java/th/co/glr/hr/attachment/AttachmentControllerTest.java`: updated constructor for extracted storage service.
- `backend/src/test/java/th/co/glr/hr/leave/LeaveServiceTest.java`: adds/updates tests for auto-approve, over-quota reject, short-notice reject, and sick-without-certificate reject.
- `frontend/package.json`, `frontend/package-lock.json`: add `browser-image-compression`.
- `frontend/src/api/hrApi.js`: supports multipart leave create when `attachmentFile` is present.
- `frontend/src/api/mockApi.js`: mirrors new leave attachment field names and auto-approval behavior in mock mode.
- `frontend/src/features/leave/LeavePage.jsx`: replaces text attachment fields with file input, compresses images, shows selected quota, and adds labor-law citation.
- `frontend/src/features/leave/LeavePage.test.jsx`: updates expected submit payload.

## Commands Run
```bash
sed -n '1,220p' docs/agent-handoffs/00_MASTER_CONTEXT.md
sed -n '1,240p' CLAUDE.md
sed -n '37,309p' ~/.claude/plans/1-quirky-stroustrup.md
git status --short --branch
ls backend/src/main/resources/db/migration
git pull --ff-only origin main
git switch -c feat/leave-autoapprove-upload
rg -n "attachmentName|attachmentUrl|LeaveRequestDto|leave_request|requiresAttachment|AUTO_REJECTED" backend/src frontend/src -g "*.*"
cd backend && ./mvnw -B -Dtest=LeaveServiceTest,AttachmentControllerTest test
cd frontend && npm install browser-image-compression
cd frontend && npm test -- LeavePage.test.jsx
git diff --check
cd backend && ./mvnw -B clean verify
cd frontend && npm run lint && npm test && npm run build
```

## Test / Build Results
- Frontend lint: pass with 9 existing `react-hooks/exhaustive-deps` warnings and 0 errors.
- Frontend tests: pass, 17 files / 84 tests; focused `LeavePage.test.jsx` also passed.
- Frontend build: pass.
- Backend tests/build: pass, `./mvnw -B clean verify` ran with Docker/Testcontainers available; V33 applied successfully, 293 tests passed, Jacoco coverage check passed.

## Decisions Made
- Leave upload is accepted on submit, not via a separate post-create endpoint, so sick certificate validation can happen in the same submit transaction.
- `hr.file_attachment` is generic HR metadata for leave now and later HR-side reuse; ticket attachment metadata remains in `sales.attachment`.
- V33 does not drop the old leave text attachment columns, but application code no longer writes or reads them.
- Employee email notifications use `sendEmail=true`; manager auto-approval notifications are in-app only (`sendEmail=false`).
- Leave advance notice defaults to 7 days and is configurable through `APP_LEAVE_ADVANCE_NOTICE_DAYS`.

## Assumptions
- The frontend may submit multipart for leave even when no file is selected; the backend handles an absent `attachment` part.
- Existing approved leave cancellation behavior is left unchanged; this branch only avoids adding manager rejection of auto-approved leave.

## Known Risks
- Old `attachment_name` / `attachment_url` data remains in the database but is no longer surfaced by the app.
- There is no dedicated leave attachment download UI in this branch; the list displays the uploaded file name.
- The direct `fetch` multipart path mirrors existing attachment upload behavior and does not use `VITE_API_BASE_URL`.

## Things Not Finished
- Branch is not committed, pushed, or opened as a PR yet.

## Recommended Next Agent
Claude Opus review.

## Exact Next Prompt
```text
Review PR for feat/leave-autoapprove-upload. Focus on whether the leave auto-approval/rejection rules match the CEO-signed scope, FileStorageService is reusable without disturbing ticket attachments, NotificationService.notify is used appropriately without adding notification audit rows, and no payroll/tax/commission math changed. Pay special attention to the decision to keep old attachment_name/attachment_url columns unused rather than dropping them, and to the lack of a leave attachment download UI in this branch.
```
