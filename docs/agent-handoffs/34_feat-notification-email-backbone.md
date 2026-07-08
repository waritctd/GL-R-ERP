# Agent Handoff

## Task
Add the HR notification + email backbone: async mail support, generic `hr.notification`, `NotificationService.notify(...)`, `/api/notifications` read APIs on HR notifications, and focused tests proving in-app insert + email attempt.

**Update (review + fix pass):** Claude Opus reviewed the initial implementation and found the
"Known Risk" below (ticket notifications writing to `sales.notification`, which nothing reads
anymore) was a real regression, not an acceptable gap — it silently breaks the notification bell
and dashboard unread-count for every sales/import/CEO user on every ticket event. Claude Sonnet
applied the fix on this same branch (see "Fixes Applied in Review" below) before merge.

## Branch
feat/notification-email-backbone

## Base Commit
ffd2a6d9da31258abff5580ac3dc9b139e7749c3

## Current Commit
Not committed yet (fix pass applied on top of Codex's uncommitted work).

## Agent / Model Used
Codex GPT-5 (initial implementation) → Claude Opus 4.8 (review) → Claude Sonnet 5 (fix pass)

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

### Fixes Applied in Review (Claude Sonnet, on top of the above)
- `backend/.../notification/NotificationRepository.java`: **retargeted `notifyEmployee`/`notifyByRole`
  from `sales.notification` to `hr.notification`** (the regression below), with a
  `TICKET_EVENT_TITLES` map to derive a human title from the short machine `type` code, and
  `link = "/tickets/{ticketId}"` so click-through still works. This makes `hr.notification` the
  single unified store — nothing writes to `sales.notification` anymore.
- `backend/.../notification/NotificationService.java`: **removed the `AuditService` dependency and
  its two call sites** (`notify()` and `markRead()`). Notifications are a side effect of an
  already-audited business action; auditing them too (a) doubles audit-log volume once leave/OT/
  commission wire in, incl. one row per notification on "mark all read" fan-out, and (b)
  `AuditService.record()` intentionally participates in the caller's transaction and rolls it back
  on failure — a guarantee that belongs to the business mutation, not a best-effort notification.
- `backend/src/test/java/.../notification/NotificationServiceTest.java`: updated for the
  `AuditService`-free constructor; replaced the audit-verification test with a
  `markReadSucceedsWhenRepositoryUpdatesOwnedNotification` positive-path test.
- `backend/src/test/java/.../notification/NotificationRepositoryIntegrationTest.java` (new):
  Testcontainers-backed test proving `notifyEmployee`/`notifyByRole` land in `hr.notification` with
  the correct title/link, respect the `notifyByRole` division + active-employee filter, and that
  `sales.notification` stays empty (locks in the fix so it can't silently regress again).
- `frontend/src/components/common/NotificationBell.jsx` +
  `frontend/src/components/layout/AppShell.jsx`: **found during the fix, not in the original
  review** — the bell's click-through (`onOpenTicket(item.ticketId)`) depended on the now-always-null
  `ticketId` field from `hr.notification` rows, so clicking any ticket notification would have
  silently done nothing post-merge. Generalized to `onNavigate(item.link)` / `if (item.link)
  onNavigate(item.link)`, using the `link` column that already existed for exactly this purpose.
  The `ticketCode` badge in the bell row is no longer shown (it's already embedded in the message
  text, e.g. "Ticket PR-2026-0099 ..."), so no information is lost.

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

# --- review + fix pass (Claude Sonnet) ---
grep -rn "notifyEmployee|notifyByRole" backend/src/main/java   # found the sales.notification split
grep -rn "sales.notification" backend/src/main/java
grep -rn "onOpenTicket" frontend/src                            # found the bell click-through gap
cd backend && ./mvnw -q -B compile
./mvnw -q -B test -Dtest='NotificationServiceTest,NotificationControllerTest'
./mvnw -q -B test -Dtest='...,NotificationRepositoryIntegrationTest,DashboardRepositoryIntegrationTest'
./mvnw -B clean verify
cd ../frontend && npm run lint && npm test -- --run && npm run build
```

## Test / Build Results
- Frontend lint: pass with 9 pre-existing `react-hooks/exhaustive-deps` warnings and 0 errors (none in touched files).
- Frontend tests: pass, 17 files / 84 tests.
- Frontend build: pass.
- Backend tests/build: pass, `./mvnw -B clean verify` ran with Docker/Testcontainers available; V32 applied successfully, **291 tests passed** (288 + 3 new: 2 in `NotificationRepositoryIntegrationTest`, 1 replacing the removed audit-verification test), Jacoco coverage check passed.

## Decisions Made
- `hr.notification` is generic and not ticket-bound; it stores `type`, `title`, `message`, optional `link`, read state, and timestamp.
- **(Revised in review) `sales.notification` is no longer written to at all.** `notifyEmployee`/
  `notifyByRole` now insert into `hr.notification` with a derived title and a `/tickets/{id}` link,
  making `hr.notification` the single source of truth for both HR and sales-stack notifications.
  The `sales.notification` table itself is left in place (unused, harmless) — dropping it is a
  separate, later cleanup, not needed for this fix.
- **(Revised in review) Notification creation and mark-read are NOT audited.** They are side
  effects of an already-audited business action; `AuditService` involvement was removed to avoid
  transactional coupling and audit-log noise (see "Fixes Applied in Review" above).
- Email sends are scheduled after transaction commit when a transaction is active, and mail failures are caught/logged in the async sender so they cannot poison caller transactions.

## Assumptions
- The general `/api/notifications` API reads/writes `hr.notification` exclusively; no dual-write or
  migration-of-old-rows was needed since `sales.notification` had no reader depending on historical
  rows surviving (dashboard/bell only ever showed recent unread items).
- No new frontend UI behavior is required in B0 beyond keeping the existing API route contract,
  adding the query key, and the minimal `onNavigate`/`link` generalization needed to avoid a
  regression in existing click-through behavior.

## Known Risks
- None outstanding from this branch. (The `sales.notification` split-brain and the bell
  click-through dependency on `ticketId` were both found and fixed in the review pass — see above.)
- Integration tests passed locally via Docker/Testcontainers; no DB skip occurred.

## Things Not Finished
- Branch has not yet been committed, pushed, or opened as a PR — do that next.
- `sales.notification` table itself was left in the schema (now unused) — fine to leave; a future
  cleanup migration could drop it once confirmed nothing else reads it.

## Recommended Next Agent
Codex — implement the next round-1 branch: `feat/leave-autoapprove-upload` (see the standalone
prompt in `~/.claude/plans/1-quirky-stroustrup.md` Section H, Prompt 2, reproduced below).

## Exact Next Prompt
```text
Repo GL-R-ERP. First read docs/agent-handoffs/00_MASTER_CONTEXT.md, CLAUDE.md, and Sections A/F/G of
~/.claude/plans/1-quirky-stroustrup.md. Run `git status`; branch off `main` (feat/notification-email-backbone
is now merged, so hr.notification / NotificationService / @EnableAsync are available). Match existing
patterns: hand-written JDBC repos (NamedParameterJdbcTemplate), Flyway (next migration = run
`ls backend/src/main/resources/db/migration` and use the next VNN, currently V33), SessionContext auth,
AuditService on every real mutation (NOT on notifications — NotificationService intentionally has no
AuditService dependency), JavaMailSender via NotificationService.notify(...) for all
submit/approve/reject events. Frontend React + TanStack Query + react-hook-form/zod (api/routes.js,
api/hrApi.js, api/queryKeys.js). Smallest reviewable diff. Do NOT change payroll/tax/commission
calculation math. Create docs/agent-handoffs/35_feat-leave-autoapprove-upload.md. Tests:
`cd backend && ./mvnw -B clean verify` (note if Testcontainers/DB skipped) and
`cd frontend && npm run lint && npm test && npm run build`. Open a PR and STOP — do not merge, do not
start another branch.

TASK — feat/leave-autoapprove-upload:
(a) Extract a reusable FileStorageService from AttachmentController (filesystem under
app.uploads-dir/{domain}/{id}) so leave and (later) commission can reuse it.
(b) Leave changes:
- Attachment becomes a single FILE UPLOAD: migration adds attachment_id to hr.leave_request; stop
  using attachment_name/attachment_url; add multipart upload (POST /api/leave/{id}/attachment or accept
  the file on submit); content-type allowlist pdf/jpg/png.
- LeaveService.submit: if within quota AND submitted >=7 days ahead (EXCEPT leave_type SICK) AND
  (SICK => a certificate file is attached) -> status APPROVED automatically, call
  NotificationService.notify(...) to notify the manager (in-app) and email the employee their
  remaining quota; otherwise AUTO_REJECTED with a clear reason + who to contact (also via
  NotificationService). Advance-notice days = a config property.
- Frontend LeavePage: replace the name+URL inputs with one file upload; compress images client-side
  with browser-image-compression (add the dep); show remaining quota; add a footer citing the leave law
  (Thai Labor Protection Act B.E. 2541 — link to the Ministry of Labour page, see plan Section F item 1).
SOLID SCOPE ONLY: sick certificate required for ALL sick leave; manager is notify-only (no reject-after-
auto-approve). Do NOT build "manager can reject an auto-approved leave" (CB-3) or a ">=3-day cert
threshold" (CB-4) — those are parked in the CEO Clarification Backlog pending sign-off. Acceptance:
unit tests for auto-approve, over-quota reject, <7-day reject, and sick-without-certificate reject.
```
