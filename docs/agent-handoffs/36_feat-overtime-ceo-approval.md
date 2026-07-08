# Agent Handoff

## Task
Implement `feat/overtime-ceo-approval`: overtime now flows employee submit -> manager approve -> CEO approve, with 3-day advance notice and notification/email calls on submit and approval stages.

## Branch
feat/overtime-ceo-approval

## Base Commit
e30175ffb5d3ccac3bf130b09aa96eb1df89d9f3

## Current Commit
Not committed yet.

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
- CEO/manager reject redesign beyond the existing manager rejection path.
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
```

## Test / Build Results
- Backend: `./mvnw -B clean verify` passed. Docker/Testcontainers ran against Postgres 16, Flyway applied through V34, 295 tests passed, Jacoco passed.
- Frontend: `npm run lint && npm test && npm run build` passed. Lint has 9 pre-existing `react-hooks/exhaustive-deps` warnings and 0 errors. Tests passed: 17 files / 84 tests.

## Decisions Made
- Kept the existing `/api/overtime/{id}/approve` endpoint; backend chooses manager vs CEO transition based on current request status and actor role.
- Manager approval still performs the attendance-backed payable-minute calculation, but now stores `MANAGER_APPROVED` instead of final `APPROVED`.
- CEO approval changes only the approval status/metadata; it does not recalculate overtime.
- Payroll repository already filters `ot.status = 'APPROVED'`, so no payroll math change was required.
- Overtime default form date and list range now look forward because same-day requests are invalid under the new 3-day rule.

## Known Notes
- CEO notification resolution uses the existing division convention from `NotificationRepository.notifyByRole("ceo", ...)`: active employees in divisions whose `source_code` starts with `MD` or `MN`.
- Dashboard pending-approval counts were not generalized in this branch; the task scope was overtime page and workflow.
