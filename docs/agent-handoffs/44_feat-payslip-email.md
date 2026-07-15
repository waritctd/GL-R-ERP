# Agent Handoff

## Task
Implement roadmap item 2: `feat/payslip-email`, dependent on `feat/payslip-pdf`. Add HR-triggered async email distribution of generated payslip PDFs, with idempotent per-employee delivery status logging.

## Branch
`feat/payslip-email`

## Base Commit
`564f22dcc73f256dc6fc9c27c3ad478963664235`

## Current Commit
Stacked with Phase 1 on `feat/payslip-email` for the final sanity/merge task; see git history on `main` after merge.

## Agent / Model Used
Codex GPT-5

## Scope

### In Scope
- Reuse Phase 1 `PayslipRenderer` to render PDF attachments.
- Add `NotificationEmailService.sendWithAttachment(...)` using existing `JavaMailSender` + `MimeMessageHelper`.
- Add `PayslipDistributionService` with async per-line send loop.
- Add persistent idempotency/status table for payslip email delivery.
- Add HR-only `POST /api/payroll/{periodId}/distribute`.
- Audit the distribution endpoint request.
- Add PayrollPage "Email payslips" button and API wiring.

### Out of Scope
- New mail provider abstraction on this branch; `uat` was checked and has `Mailer`, but this branch/main uses `JavaMailSender`.
- Payroll/tax/SSO calculation changes.
- Sales/CRM changes.
- Push or PR creation.

## Files Changed
- `backend/src/main/java/th/co/glr/hr/notification/NotificationEmailService.java`: added `sendWithAttachment(to, subject, body, filename, bytes)` with `MimeMessageHelper` and `ByteArrayResource`.
- `backend/src/main/java/th/co/glr/hr/payroll/PayslipDistributionService.java`: new HR-only queue/audit method plus `@Async` send loop that skips sent lines, renders payslips, emails attachments, and records sent/failed status.
- `backend/src/main/java/th/co/glr/hr/payroll/PayslipDistributionResponse.java`: response DTO for accepted distribution requests.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollController.java`: injected distribution service and added `POST /api/payroll/{periodId}/distribute`.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollRepository.java`: added employee email lookup and delivery status methods (`findSentPayslipLineIds`, pending/sent/failed markers).
- `backend/src/main/resources/db/migration/V43__payroll_payslip_email_delivery.sql`: new delivery status table with unique `(period_id, line_id)`.
- `backend/src/test/java/th/co/glr/hr/notification/NotificationServiceTest.java`: covers MIME attachment sending.
- `backend/src/test/java/th/co/glr/hr/payroll/PayslipDistributionServiceTest.java`: covers queue counts/audit, skip sent, successful send, missing email failure, and non-HR rejection.
- `backend/src/test/java/th/co/glr/hr/payroll/PayrollControllerTest.java`: covers distribute endpoint response and async trigger.
- `frontend/src/api/routes.js`: added payroll distribute route.
- `frontend/src/api/hrApi.js`: added `api.payroll.distributePayslips`.
- `frontend/src/api/mockApi.js`: mock-mode distribution returns clear unsupported error.
- `frontend/src/features/payroll/PayrollPage.jsx`: added "Email payslips" button and mutation/toast.
- `frontend/src/features/payroll/PayrollPage.test.jsx`: added distribute API mock and click assertion.

Phase 1 files are also present in this stacked working tree; see `docs/agent-handoffs/43_feat-payslip-pdf.md`.

## Commands Run
```bash
sed -n '1,240p' CLAUDE.md
sed -n '1,280p' docs/agent-handoffs/00_MASTER_CONTEXT.md
sed -n '1,260p' docs/agent-handoffs/43_feat-payslip-pdf.md
git status --short --branch
git checkout -b feat/payslip-email
rg -n "NotificationEmailService|JavaMailSender|SimpleMailMessage|MimeMessageHelper|Mailer|SmtpMailer|ResendMailer|@Async|EnableAsync|Async" backend/src/main/java backend/src/test/java
git show uat:backend/src/main/java/th/co/glr/hr/notification/NotificationEmailService.java
git ls-tree -r --name-only uat backend/src/main/java | rg 'Mailer|mail|Email|notification'
sed -n '1,260p' backend/src/main/java/th/co/glr/hr/notification/NotificationEmailService.java
sed -n '1,220p' backend/src/main/java/th/co/glr/hr/audit/AuditLogRepository.java
sed -n '1,180p' backend/src/main/java/th/co/glr/hr/notification/NotificationService.java
sed -n '1,180p' backend/src/main/java/th/co/glr/hr/HrBackendApplication.java
sed -n '1,180p' backend/src/main/java/th/co/glr/hr/notification/NotificationRepository.java
sed -n '1,220p' backend/src/test/java/th/co/glr/hr/notification/NotificationServiceTest.java
sed -n '70,115p' backend/src/main/resources/application.yml
./mvnw -B -Dtest=PayslipDistributionServiceTest,PayrollControllerTest,PayrollServiceTest,PayslipRendererTest,NotificationServiceTest test
npm run lint
npm test
npm run build
./mvnw -B clean verify
./mvnw -B -Dtest=PayslipDistributionServiceTest,PayrollControllerTest,PayrollServiceTest,PayslipRendererTest,NotificationServiceTest,DashboardControllerTest test
./mvnw -B clean verify
```

## Test / Build Results
- Backend targeted tests: pass. `./mvnw -B -Dtest=PayslipDistributionServiceTest,PayrollControllerTest,PayrollServiceTest,PayslipRendererTest,NotificationServiceTest test` ran 30 tests with 0 failures/errors.
- Backend combined targeted sanity tests: pass. `./mvnw -B -Dtest=PayslipDistributionServiceTest,PayrollControllerTest,PayrollServiceTest,PayslipRendererTest,NotificationServiceTest,DashboardControllerTest test` ran 32 tests with 0 failures/errors.
- Backend full verify: pass by generated artifacts after the migration was renamed to `V43`. `backend/target/surefire-reports` contains 53 XML suites / 334 tests / 0 failures / 0 errors / 0 skipped, `backend/target/glr-hr-backend-0.1.0.jar` was produced, and JaCoCo reports were generated.
- Frontend lint: not completed. `npm run lint` timed out after 180 seconds with no diagnostics after the ESLint startup line.
- Frontend tests: not completed. `npm test` timed out after 180 seconds with no diagnostics after the Vitest startup line.
- Frontend build: not completed. `npm run build` timed out after 180 seconds with no diagnostics after the Vite startup line.
- TEST_DB_URL-gated tests: `TEST_DB_URL` was not set; local integration tests ran through Testcontainers/Docker in the final sanity pass rather than being skipped.

## Decisions Made
- Used main-branch `JavaMailSender` directly instead of the `uat` `Mailer` abstraction because this branch does not contain `th.co.glr.hr.mail.*`.
- Persisted delivery status in `hr.payroll_payslip_email_delivery` for idempotency. Successful lines are skipped on repeated distribution requests; failed/missing-email lines can be retried.
- Added a stale `PENDING` retry window in `markPayslipEmailPending`: fresh pending rows block duplicate async workers, while rows older than 15 minutes can be reclaimed.
- Made the controller return `202 Accepted` with counts, then trigger the async service method from the controller so Spring's `@Async` proxy is actually used.
- Kept the endpoint HR-only (`hasRole('HR')`), stricter than Phase 1 HR/CEO payslip PDF viewing.
- Missing employee email is logged as `FAILED` per employee without rendering or attempting to send.

## Assumptions
- Employee email should come from `hr.employee.email`; `PayrollLineDto` has all payslip data, but not recipient email.
- A repeated distribute click should not resend rows with `SENT` status.
- Existing `spring-boot-starter-mail` is sufficient; no new Maven dependency is needed.

## Required Render Environment Variables
Set these for SMTP on Render:
```text
SPRING_MAIL_HOST=<smtp host>
SPRING_MAIL_PORT=<smtp port, usually 587>
SPRING_MAIL_USERNAME=<smtp username / from address>
SPRING_MAIL_PASSWORD=<smtp password or app password>
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true
```

Optional, only if your provider needs it:
```text
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_TRUST=<smtp host>
```

`NotificationEmailService` currently uses `spring.mail.username` as the From address.

## Known Risks
- Frontend lint/test/build could not complete locally due Node CLI hangs; rerun in CI or a clean shell.
- The delivery table migration is `V43`; it was renamed from `V42` after full backend verify found a demo-profile `V42` collision.

## Things Not Finished
- No push or PR created.
- Frontend verification remains pending due local toolchain hangs.

## Recommended Next Agent
Claude Opus review for the combined Phase 1 + Phase 2 merge, then roadmap item 3 `feat/payroll-accounting-summary`.

## Exact Next Prompt
```text
Read CLAUDE.md and docs/agent-handoffs/00_MASTER_CONTEXT.md first. Review the combined main-branch merge for roadmap items 1 and 2: feat/payslip-pdf and feat/payslip-email. Focus on authorization, own-payslip isolation, async behavior, idempotent delivery status, attachment email correctness, audit coverage, migration safety, and frontend PayrollPage/employee-dashboard wiring. Rerun frontend lint/test/build in a clean environment. Do not implement roadmap item 3 until review passes.
```
