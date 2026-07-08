# Agent Handoff

## Task
Implement `feat/leave-autoapprove-upload`: reusable file storage, leave single-file upload, auto-approve/reject rules, notification backbone wiring, and frontend upload/compression/law citation.

**Update (review + fix pass):** Claude Opus reviewed the implementation end-to-end (backend logic,
tests, and a live browser walkthrough via the `frontend-mock` preview server) and found one real,
pre-existing correctness bug that this PR's new "show remaining quota" feature exposed as active
data corruption. Claude Sonnet applied the fix on this same branch (see "Fix Applied in Review"
below) before merge. Everything else in the implementation — auto-approve/reject rules, file
upload, notification wiring, tests — was verified correct as built.

## Branch
feat/leave-autoapprove-upload

## Base Commit
d66d79abc98d0c5c4127dd8328ce5f42918658ec

## Current Commit
Not committed yet (fix pass applied on top of Codex's uncommitted work).

## Agent / Model Used
Codex GPT-5 (initial implementation) → Claude Opus 4.8 (review) → Claude Sonnet 5 (fix pass)

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

### Fix Applied in Review (Claude Sonnet, on top of the above)
- `frontend/src/features/leave/LeavePage.jsx`: **the `#leave-type-code` and `#leave-employee`
  `<select>` elements had no `value`/`defaultValue` prop** — they only spread `{...register(...)}`,
  which supplies `name`/`ref`/`onChange`/`onBlur` but not a value binding. For an uncontrolled
  `<select>`, the browser visually highlights whichever `<option>` is first in the list
  (alphabetical `leave_type_code` order → PERSONAL/"ลากิจ" is always first) **regardless of React
  Hook Form's actual tracked value** (`defaultForm`'s default is `'VACATION'`). This is a
  **pre-existing defect on `main`** (verified: identical markup exists on `main` before this
  branch) — but it was invisible before because nothing displayed a value tied to the mismatch. This
  PR's new "คงเหลือ X วัน จากสิทธิ์ Y วัน" quota hint is the first consumer of the watched
  `formLeaveTypeCode`, and it exposed the bug as **silent data corruption**: on page load the
  dropdown visibly shows "ลากิจ" selected, the quota hint (correctly, per the real RHF state) shows
  VACATION's numbers, and if the user submits without touching the dropdown, **the system files a
  VACATION request while the user believes they selected PERSONAL** — verified live via
  `frontend-mock`: fresh load → select shows "ลากิจ"/PERSONAL → submit without interaction → created
  record is "ลาพักร้อน"/VACATION. Same defect existed on the employee-selector used when a
  manager/HR files leave on behalf of a report (narrower blast radius, not separately reproduced,
  but identical missing-binding pattern).
  **Fix:** bind both selects to their already-watched values —
  `value={formLeaveTypeCode ?? ''}` / `value={formEmployeeId ?? ''}` — with an explicit `onChange`
  calling `setValue(..., { shouldValidate: true })`, making the visual selection and the submitted
  value the same value at all times. Verified via `frontend-mock`: dropdown now shows "ลาพักร้อน"
  matching the "4/6" hint on load; toggling to "ลากิจ" correctly shows "3/3"; submitting without
  touching the dropdown now files the type that's actually displayed; a full auto-approve run
  (PERSONAL, 12 days notice) correctly approved and decremented the right quota (3→2 remaining).

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

# --- review + fix pass (Claude Sonnet) ---
cd backend && ./mvnw -B clean verify   # 293 tests, re-verified independently
cd frontend && npm run lint && npm test -- --run && npm run build
# preview_start frontend-mock (VITE_USE_MOCKS=true, port 5200) → logged in as Employee,
# reproduced the select-binding bug live, applied the fix, reloaded, re-verified:
#  - dropdown visual selection now matches formLeaveTypeCode/formEmployeeId at all times
#  - submit-without-touching-dropdown now files the type actually shown
#  - full auto-approve path (PERSONAL, 12-day notice) approved correctly, quota 3->2
cd frontend && npm test -- --run && npm run lint && npm run build   # re-run after the fix
```

## Test / Build Results
- Frontend lint: pass with 9 existing `react-hooks/exhaustive-deps` warnings and 0 errors (before and after the fix).
- Frontend tests: pass, 17 files / 84 tests; focused `LeavePage.test.jsx` also passed (before and after the fix).
- Frontend build: pass (before and after the fix).
- Backend tests/build: pass, `./mvnw -B clean verify` ran with Docker/Testcontainers available; V33 applied successfully, 293 tests passed, Jacoco coverage check passed.
- Manual/live verification: full browser walkthrough via `frontend-mock` preview server, both before
  finding the bug (reproduced it) and after fixing it (confirmed correct end-to-end, including the
  auto-approve happy path).

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
- **None outstanding from the select-binding bug** — fixed and verified live (see above). Worth a
  follow-up sweep: grep the rest of the frontend for other `<select {...register(...)}>` without a
  `value`/`defaultValue` prop, since this exact pattern silently mis-submits whenever the
  alphabetically-first option differs from the real form default and the user doesn't interact with
  the dropdown before submitting. Not done here (out of this branch's scope) — flagging for a
  dedicated audit.

## Things Not Finished
- Branch is not committed, pushed, or opened as a PR yet — do that next.
- Suggested follow-up (not blocking): audit other uncontrolled `<select>` fields across the frontend
  for the same missing-value-binding pattern (see Known Risks).

## Recommended Next Agent
Claude Opus (self) will commit, push, open the PR, and merge after a final CI check — no further
Codex work needed on this branch. Next Codex work is Prompt 3 (`feat/overtime-ceo-approval`) once
this merges.

## Exact Next Prompt (for Codex, after this PR merges)
```text
Repo GL-R-ERP. First read docs/agent-handoffs/00_MASTER_CONTEXT.md, CLAUDE.md, and Sections A/F/G of
~/.claude/plans/1-quirky-stroustrup.md. Run `git status`; branch off `main` (feat/leave-autoapprove-upload
is now merged, so NotificationService, FileStorageService, and hr.notification are available). Match
existing patterns: hand-written JDBC repos, Flyway (check `ls backend/src/main/resources/db/migration`
for the next VNN), SessionContext auth, AuditService on every real mutation, NotificationService.notify
for submit/approve events. When building any <select> bound via react-hook-form's register(), ALWAYS
bind it to the watched value explicitly (value={watchedValue ?? ''} + onChange calling setValue) —
do NOT rely on bare {...register(...)} for a <select>, since an uncontrolled select silently
defaults to its first <option> regardless of the real form value (a real bug found and fixed in
feat/leave-autoapprove-upload). Frontend React + TanStack Query + react-hook-form/zod. Smallest
reviewable diff. Do NOT change payroll/tax/commission calculation math. Create
docs/agent-handoffs/36_feat-overtime-ceo-approval.md. Tests: `cd backend && ./mvnw -B clean verify`
and `cd frontend && npm run lint && npm test && npm run build`. Open a PR and STOP.

TASK — feat/overtime-ceo-approval:
Add a CEO approval step so overtime is employee submit -> manager approve -> CEO approve. Add
OvertimeStatus.MANAGER_APPROVED; migration adds ceo_approved_by/at (and manager-stage columns if
missing). OvertimeService: manager approve transitions SUBMITTED->MANAGER_APPROVED; CEO approve
transitions MANAGER_APPROVED->APPROVED; only fully-approved OT feeds payroll. Enforce a 3-day
advance-notice on submit. Notify + email (NotificationService.notify) on submit and at each approval
stage. Frontend OvertimePage: show the two approval stages and role-based approve buttons (manager vs
CEO). DO NOT build per-diem or generalize into a special-pay page — per-diem is deferred (CB-1). Keep
this overtime-only. Acceptance: unit tests for the 3 transitions and wrong-role rejection.
```
