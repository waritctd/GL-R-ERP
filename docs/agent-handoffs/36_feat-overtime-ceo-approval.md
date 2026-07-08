# Agent Handoff

## Task
Implement `feat/overtime-ceo-approval`: overtime now flows employee submit -> manager approve -> CEO approve, with 3-day advance notice and notification/email calls on submit and approval stages.

**Update (review + fix pass):** Claude Opus reviewed the implementation (backend logic, tests, and a
live browser walkthrough via the `frontend-mock` preview server) and found that the CEO approval
stage had no reject path — the prompt asked only for "role-based approve buttons," so Codex correctly
built exactly that and explicitly logged the gap as out-of-scope (see the original "Out of Scope"
list below). On review this reads as a real functional gap, not an acceptable exclusion: a CEO
"approval gate" that can only say yes and never no isn't a gate, and once a manager approves, the
request would sit in `MANAGER_APPROVED` limbo forever with no way for the CEO to resolve it (the
only escape hatch was the original approving manager cancelling it — the CEO themselves has no
lever). Claude Sonnet added CEO-reject symmetrically with the existing manager-reject pattern (see
"Fix Applied in Review" below) and verified it live end-to-end before merge.

## Branch
feat/overtime-ceo-approval

## Base Commit
e30175ffb5d3ccac3bf130b09aa96eb1df89d9f3

## Current Commit
Not committed yet (fix pass applied on top of Codex's uncommitted work).

## Agent / Model Used
Codex GPT-5 (initial implementation) → Claude Opus 4.8 (review) → Claude Sonnet 5 (fix pass)

## Scope

### In Scope
- Add `OvertimeStatus.MANAGER_APPROVED`.
- Add Flyway V34 for manager/CEO approval columns and the expanded overtime status check.
- Split overtime approval into manager and CEO stages.
- Keep payroll inclusion limited to fully approved overtime (`status = 'APPROVED'`), without changing payroll/tax/commission math.
- Enforce 3-day overtime advance notice through `APP_OVERTIME_ADVANCE_NOTICE_DAYS` (default 3).
- Notify and email on overtime submit, manager approval, and CEO approval via `NotificationService.notify(...)`.
- Update OvertimePage to show manager and CEO approval stages with role-based approve buttons.
- Bind overtime form selects explicitly to watched React Hook Form values.

### Out of Scope
- Per-diem or generalized special-pay page.
- ~~CEO/manager reject redesign beyond the existing manager rejection path.~~ **Reopened in review —
  see "Fix Applied in Review" below.** A CEO reject path was added; the manager-reject *mechanism*
  itself (SUBMITTED-stage rejection) was not redesigned, only generalized to also dispatch to the
  new CEO-reject case, mirroring how `approve()` already dispatched by status.
- Payroll, tax, or commission calculation changes.

## Files Changed
- `backend/src/main/resources/db/migration/V34__overtime_ceo_approval.sql`: adds manager/CEO approval columns and allows `MANAGER_APPROVED`.
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeStatus.java`: adds `MANAGER_APPROVED`.
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeRequestDto.java`: exposes manager/CEO approval metadata.
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeRepository.java`: splits approval writes into `managerApprove` and `ceoApprove`, reads stage metadata, and resolves CEO approver employee IDs by the existing MD/MN division-code convention.
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeService.java`: adds 3-day advance notice, stage-specific approval transitions, AuditService records for real mutations, and NotificationService calls.
- `backend/src/main/java/th/co/glr/hr/config/AppProperties.java`: adds overtime advance-notice config.
- `backend/src/main/resources/application.yml`: adds `app.overtime.advance-notice-days`.
- `backend/src/test/java/th/co/glr/hr/overtime/OvertimeServiceTest.java`: covers submit notification, manager transition, CEO transition, 3-day rejection, and wrong-role rejection.
- `frontend/src/features/overtime/OvertimePage.jsx`: shows two approval stages, adds `MANAGER_APPROVED` status/filter/stat, role-based approve labels/buttons, controlled RHF selects, and future-friendly default date/range.

### Fix Applied in Review (Claude Sonnet, on top of the above)
- `backend/.../overtime/OvertimeRepository.java`: added `ceoReject(id, reviewedById, reviewerNote)` —
  same shape as `reject()` but requires `status = 'MANAGER_APPROVED'` (mirrors the
  `managerApprove`/`ceoApprove` split already in the file).
- `backend/.../overtime/OvertimeService.java`: generalized the public `reject(...)` to dispatch by
  current status, exactly like `approve()` already does — `SUBMITTED` → `managerReject` (existing
  behavior, unchanged), `MANAGER_APPROVED` → new `ceoReject` (requires `requireCeo`), anything else →
  409 "already reviewed". Also added `notifyRejected(...)` and wired it into both reject paths — the
  original `reject()` had **no notification call at all** (a pre-existing gap; the sibling
  `LeaveService.reject()` does notify), so this also closes that inconsistency for both manager- and
  CEO-level rejection.
- `backend/src/test/java/.../overtime/OvertimeServiceTest.java`: added
  `managerRejectionTransitionsSubmittedToRejected`, `ceoRejectionTransitionsManagerApprovedToRejected`,
  and `managerCannotCeoRejectManagerApprovedOvertime` (wrong-stage/wrong-role guard, mirroring the
  existing `managerCannotCeoApproveManagerApprovedOvertime` test).
- `frontend/src/features/overtime/OvertimePage.jsx`: removed the `canManagerApprove(request) ? (...) :
  null` guard that hid the reject button whenever `canCeoApprove(request)` was the true branch — the
  reject button now shows for both approval stages, matching the approve button's behavior.
- `frontend/src/api/mockApi.js`: **found during the fix, not in the original review** — the mock
  overtime `approve`/`reject`/`create`/`cancel` handlers were never updated for this branch at all;
  they still implemented the old single-stage SUBMITTED→APPROVED/REJECTED flow with no
  `MANAGER_APPROVED` concept. This meant local/demo mode (`frontend-mock`, including the CEO quick
  login) silently reverted to the pre-this-PR single-stage behavior — a real regression for anyone
  testing or demoing the feature without a live backend, and the reason the sibling
  `feat/leave-autoapprove-upload` branch's mock-parity precedent mattered here. Updated `approve()`/
  `reject()` to dispatch by current status with the same role checks as the backend
  (`canReviewLeave` for manager stage, `user.role === 'ceo'` for CEO stage), added
  `managerApprovedBy/At`/`ceoApprovedBy/At` fields to `create()`'s seed shape and `buildOvertimeRecord`'s
  name resolution, and added `MANAGER_APPROVED` to `cancel()`'s allowed-status list to match the
  backend's `OvertimeRepository.cancel(...)`.
- **Verified live end-to-end** via the `frontend-mock` preview server (logged in as CEO, who manages
  the SAL division's reports in the demo fixture): submitted an OT request for a report → manager-
  approved it (correctly computed 2h actual → 3h payable at the 1.5x multiplier, status → "รอ CEO")
  → **rejected it at the CEO stage** (previously impossible — the button was hidden and the API call
  would have 409'd even if forced) → confirmed the row correctly shows "ปฏิเสธแล้ว" with the reviewer
  note attached and the action buttons correctly gone (terminal state).

## Commands Run
```bash
sed -n '1,220p' docs/agent-handoffs/00_MASTER_CONTEXT.md
sed -n '1,260p' CLAUDE.md
sed -n '1,220p' ~/.claude/plans/1-quirky-stroustrup.md
git status --short --branch
git switch main
git pull --ff-only origin main
git switch -c feat/overtime-ceo-approval
ls backend/src/main/resources/db/migration | sort -V | tail -10
cd backend && ./mvnw -q -DskipTests compile
cd backend && ./mvnw -q -Dtest=OvertimeServiceTest test
cd frontend && npm test -- OvertimePage.test.jsx
cd frontend && npm run lint -- --quiet
cd backend && ./mvnw -B clean verify
cd frontend && npm run lint && npm test && npm run build

# --- review + fix pass (Claude Sonnet) ---
grep -n "reject\|Reject" backend/.../OvertimeService.java backend/.../OvertimeController.java backend/.../OvertimeRepository.java   # confirmed no CEO-reject path
grep -n "void.*[Rr]eject" backend/src/test/.../OvertimeServiceTest.java   # confirmed zero reject tests existed
grep -n "MANAGER_APPROVED\|overtime" frontend/src/api/mockApi.js   # confirmed mock never updated for the two-stage flow
cd backend && ./mvnw -q -B compile
./mvnw -B clean verify   # 298 tests (was 293 on main; +5 new: 3 reject tests here + prior branch's 2)
cd ../frontend && npm run lint && npm test -- --run && npm run build
# preview_start frontend-mock → logged in as CEO (manages SAL division in the demo fixture) →
# submitted OT for a SAL report → manager-approved (verified 2h->3h @1.5x calc, "รอ CEO") →
# CEO-rejected (verified previously-impossible transition now works, reviewer note attached,
# actions correctly gone at terminal REJECTED state)
```

## Test / Build Results
- Backend: `./mvnw -B clean verify` passed both before and after the fix. Docker/Testcontainers ran
  against Postgres 16, Flyway applied through V34, **298 tests passed** (295 + 3 new reject tests),
  Jacoco passed.
- Frontend: `npm run lint && npm test && npm run build` passed both before and after the fix. Lint
  has 9 pre-existing `react-hooks/exhaustive-deps` warnings and 0 errors (none in touched files).
  Tests passed: 17 files / 84 tests.
- Manual/live verification: full browser walkthrough via `frontend-mock`, confirming the two-stage
  flow renders and transitions correctly, and specifically that CEO-reject (the fix) works
  end-to-end — see "Fix Applied in Review" above for the exact steps.

## Decisions Made
- Kept the existing `/api/overtime/{id}/approve` endpoint; backend chooses manager vs CEO transition based on current request status and actor role.
- Manager approval still performs the attendance-backed payable-minute calculation, but now stores `MANAGER_APPROVED` instead of final `APPROVED`.
- CEO approval changes only the approval status/metadata; it does not recalculate overtime.
- Payroll repository already filters `ot.status = 'APPROVED'`, so no payroll math change was required.
- Overtime default form date and list range now look forward because same-day requests are invalid under the new 3-day rule.
- **(Added in review) `/api/overtime/{id}/reject` now also dispatches by status**, symmetric with
  `approve`: `SUBMITTED` → manager rejects, `MANAGER_APPROVED` → CEO rejects. Both paths now notify
  the employee (`OVERTIME_REJECTED`), closing a pre-existing gap where reject sent no notification at all.

## Known Notes
- CEO notification resolution uses the existing division convention from `NotificationRepository.notifyByRole("ceo", ...)`: active employees in divisions whose `source_code` starts with `MD` or `MN`.
- Dashboard pending-approval counts were not generalized in this branch; the task scope was overtime page and workflow.
- The mock's advance-notice validation (3-day minimum) was **not** added to `mockApi.js` — the mock
  has never replicated every backend validation rule 1:1, and this wasn't required to exercise the
  two-stage approval flow. Flagging as a minor, non-blocking parity gap, not a fix in this pass.
- Old seed record `id: 2` in `mockApi.js` (status `APPROVED`) predates the two-stage model and has no
  `managerApprovedBy`/`ceoApprovedBy` — renders fine (fields default to `null`/`-`), just cosmetically
  incomplete for that one legacy demo row.

## Things Not Finished
- Branch is not committed, pushed, or opened as a PR yet — do that next.

## Recommended Next Agent
Codex — implement the next round-1 branch: `feat/commission-invoice-dual-approval` (Prompt 4 from
`~/.claude/plans/1-quirky-stroustrup.md` Section H, reproduced below with lessons from this branch and
the prior leave branch folded in).

## Exact Next Prompt
```text
Repo GL-R-ERP. First read docs/agent-handoffs/00_MASTER_CONTEXT.md, CLAUDE.md, and Sections A/F/G of
~/.claude/plans/1-quirky-stroustrup.md. Run `git status`; branch off `main` (feat/overtime-ceo-approval
is now merged). Match existing patterns: hand-written JDBC repos, Flyway (check
`ls backend/src/main/resources/db/migration` for the next VNN), SessionContext auth, AuditService on
every real mutation, NotificationService.notify(...) for submit/approve/REJECT events (reject must
notify too — a real gap was found and fixed in feat/overtime-ceo-approval where reject sent no
notification at all). When a status has an approval gate with two outcomes, build BOTH outcomes
symmetrically at every stage — do not add an "approve" transition without a matching "reject"/"deny"
transition at the same stage; a gate that can only say yes isn't a gate (this exact gap — CEO could
approve but not reject — was found in review and fixed in feat/overtime-ceo-approval). When building
any <select> bound via react-hook-form's register(), always bind it to the watched value explicitly
(value={watchedValue ?? ''} + onChange calling setValue) — do not rely on bare {...register(...)} for
a select (found and fixed in feat/leave-autoapprove-upload). Whenever you touch a workflow's status
model, update frontend/src/api/mockApi.js to match — the mock is used for local dev and demos
(including CEO's own quick-login) and drifting out of sync silently reverts the feature to its old
behavior in mock mode (found and fixed in feat/overtime-ceo-approval, where the mock was never
updated for the two-stage flow at all). Frontend React + TanStack Query + react-hook-form/zod.
Smallest reviewable diff. Do NOT change payroll/tax/commission calculation math. Create
docs/agent-handoffs/37_feat-commission-invoice-dual-approval.md. Tests:
`cd backend && ./mvnw -B clean verify` and `cd frontend && npm run lint && npm test && npm run build`.
If this is UI-observable, verify live via the frontend-mock preview server (VITE_USE_MOCKS=true, port
5200) before calling it done. Open a PR and STOP — do not merge, do not start another branch.

TASK — feat/commission-invoice-dual-approval:
Two changes to commission, WITHOUT touching commission calculation/tier math:
- The tax invoice (ใบกำกับภาษี) becomes a REQUIRED file upload (reuse FileStorageService from
  feat/leave-autoapprove-upload; migration adds invoice_attachment_id) replacing the invoice-name
  text field.
- Approval requires manager AND CEO: status SUBMITTED -> MANAGER_APPROVED -> APPROVED. Split the
  current requireApprover into requireManager (-> MANAGER_APPROVED) and requireCeo (-> APPROVED).
  Notify+email (NotificationService) on submit and each stage — AND on rejection, at both stages
  (see the reject-must-notify and symmetric-approve/reject-gate notes above).
Frontend CommissionPage: invoice file upload + role-based two approve buttons (and matching reject
buttons at both stages). Acceptance: unit tests for the dual-approval sequence, invoice-file-required,
and reject at both stages.
```
