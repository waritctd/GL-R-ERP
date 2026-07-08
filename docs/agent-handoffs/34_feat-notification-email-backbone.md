# Agent Handoff

## Task
Add the HR notification + email backbone: async mail support, generic `hr.notification`, `NotificationService.notify(...)`, `/api/notifications` read APIs on HR notifications, and focused tests proving in-app insert + email attempt.

## Branch
feat/notification-email-backbone

## Base Commit
ffd2a6d9da31258abff5580ac3dc9b139e7749c3

## Current Commit
Not committed yet.

## Agent / Model Used
Codex GPT-5

## Scope

### In Scope
- Add `@EnableAsync`.
- Add Flyway `V32__hr_notification_schema.sql`.
- Add generic HR notification service/repository/email sender.
- Retarget `/api/notifications` GET/PATCH and dashboard notification summary to `hr.notification`.
- Preserve existing `sales.notification` write helpers used by frozen sales code.
- Add focused backend tests and a frontend notification query key.

### Out of Scope
- Wiring leave, overtime, commission, or other workflows into the new backbone.
- Changing payroll, tax, commission, quotation, or pricing math.
- Migrating or rewriting the existing ticket-bound `sales.notification` table.

## Files Changed
- `backend/src/main/java/th/co/glr/hr/HrBackendApplication.java`: enabled Spring async execution.
- `backend/src/main/resources/db/migration/V32__hr_notification_schema.sql`: created generic `hr.notification` table and employee/read index.
- `backend/src/main/java/th/co/glr/hr/notification/NotificationDto.java`: added generic title/link fields.
- `backend/src/main/java/th/co/glr/hr/notification/NotificationRepository.java`: moved list/mark-read/insert/email lookup to `hr.notification`; kept sales notification helper writes intact.
- `backend/src/main/java/th/co/glr/hr/notification/NotificationService.java`: added transactional notify/list/mark-read orchestration with audit and after-commit async email scheduling.
- `backend/src/main/java/th/co/glr/hr/notification/NotificationEmailService.java`: added async JavaMailSender sender that catches/logs failures.
- `backend/src/main/java/th/co/glr/hr/notification/NotificationController.java`: routed GET/PATCH through `NotificationService` and SessionContext.
- `backend/src/main/java/th/co/glr/hr/dashboard/DashboardRepository.java`: counted HR notifications for dashboard summary.
- `backend/src/test/java/th/co/glr/hr/notification/NotificationControllerTest.java`: updated controller tests for service-backed endpoint.
- `backend/src/test/java/th/co/glr/hr/notification/NotificationServiceTest.java`: added acceptance test for in-app insert + synchronous-test email attempt, plus mark-read not-found behavior.
- `backend/src/test/java/th/co/glr/hr/dashboard/DashboardRepositoryIntegrationTest.java`: updated notification fixture to `hr.notification`.
- `frontend/src/api/queryKeys.js`: added a `notifications` key for the shared API layer.

## Commands Run
```bash
sed -n '1,220p' docs/agent-handoffs/00_MASTER_CONTEXT.md
sed -n '1,240p' CLAUDE.md
git status --short --branch
ls backend/src/main/resources/db/migration
sed -n '37,309p' ~/.claude/plans/1-quirky-stroustrup.md
git switch -c feat/notification-email-backbone
rg -n "class .*Notification|notification|/api/notifications|NotificationService|sales.notification" backend/src frontend/src
rg -n "class FactoryEmailService|JavaMailSender|send\(" backend/src/main/java backend/src/test/java
rg -n "class SessionContext|record SessionContext|AuditService|@Enable|SpringBootApplication|@SpringBootApplication" backend/src/main/java backend/src/test/java
cd backend && ./mvnw -B -Dtest=NotificationServiceTest,NotificationControllerTest test
git diff --check
cd backend && ./mvnw -B clean verify
cd frontend && npm run lint && npm test && npm run build
```

## Test / Build Results
- Frontend lint: pass with 9 pre-existing `react-hooks/exhaustive-deps` warnings and 0 errors.
- Frontend tests: pass, 17 files / 84 tests.
- Frontend build: pass.
- Backend tests/build: pass, `./mvnw -B clean verify` ran with Docker/Testcontainers available; V32 applied successfully, 288 tests passed, Jacoco coverage check passed.

## Decisions Made
- `hr.notification` is generic and not ticket-bound; it stores `type`, `title`, `message`, optional `link`, read state, and timestamp.
- Existing `sales.notification` helper methods remain in `NotificationRepository` so current sales ticket/deposit code continues writing to the legacy table.
- Email sends are scheduled after transaction commit when a transaction is active, and mail failures are caught/logged in the async sender so they cannot poison caller transactions.
- Mark-read mutations are audited through `AuditService`; system-created notifications are audited with a null actor.

## Assumptions
- The general `/api/notifications` API should now read/write `hr.notification`; legacy `sales.notification` remains available for existing sales code until a future migration/wiring branch.
- No new frontend UI behavior is required in B0 beyond keeping the existing API route contract and adding the query key.

## Known Risks
- Existing ticket-bound sales notifications still write to `sales.notification`; they are not surfaced by the new HR-backed `/api/notifications` list until a future migration or dual-write decision.
- Integration tests passed locally via Docker/Testcontainers; no DB skip occurred.

## Things Not Finished
- Branch has not yet been committed, pushed, or opened as a PR.

## Recommended Next Agent
Claude Opus review.

## Exact Next Prompt
```text
Review PR for feat/notification-email-backbone. Check that the HR notification + email backbone stays generic, keeps sales.notification behavior intact, uses SessionContext/AuditService/JavaMailSender patterns correctly, does not wire leave/OT/commission yet, and preserves payroll/tax/commission math. Pay special attention to whether legacy sales notifications should remain hidden from the new HR-backed /api/notifications endpoint or need an explicit migration/dual-write decision.
```
