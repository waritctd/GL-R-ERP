# Agent Handoff

## Task
Implement roadmap item 1: `feat/payslip-pdf`. Add per-employee payroll payslip PDFs using the existing `PdfDocumentWriter` Sarabun-font PDF infrastructure, without adding dependencies or changing payroll calculations.

## Branch
`feat/payslip-pdf`

## Base Commit
`564f22dcc73f256dc6fc9c27c3ad478963664235`

## Current Commit
Stacked with Phase 2 on `feat/payslip-email` for the final sanity/merge task; see git history on `main` after merge.

## Agent / Model Used
Codex GPT-5

## Scope

### In Scope
- New payroll payslip PDF renderer using `PdfDocumentWriter`.
- HR/CEO endpoint for a saved payroll line PDF.
- Employee self-service endpoint that resolves by session `employeeId` only.
- Audit records and sensitive-access logs for both payslip endpoints.
- Frontend HR payroll row download button.
- Employee dashboard "My payslip" button.
- Minimal dashboard summary field to expose the employee's latest processed payroll period id for the self-service button.

### Out of Scope
- Payroll/tax/SSO calculation changes.
- New PDF/Maven/npm libraries.
- Sales/CRM changes.
- Push or PR creation.

## Files Changed
- `backend/src/main/java/th/co/glr/hr/payroll/PayslipRenderer.java`: new Sarabun/PDFBox payslip renderer with Thai labels, earnings, deductions, tax/SSO, and net pay sections.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollService.java`: added `payslipPdf` and `ownPayslipPdf`, line ownership checks, renderer calls, audit logging, and `AuditService.record` calls.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollController.java`: added `/api/payroll/{periodId}/lines/{lineId}/payslip.pdf` and `/api/payroll/{periodId}/payslip/me` PDF responses.
- `backend/src/main/java/th/co/glr/hr/dashboard/DashboardSummaryDto.java`: added `latestPayrollPeriodId`.
- `backend/src/main/java/th/co/glr/hr/dashboard/DashboardService.java`: populates latest payroll period id for users with an `employeeId`.
- `backend/src/main/java/th/co/glr/hr/dashboard/DashboardRepository.java`: added lookup for latest non-VOID payroll period containing the employee.
- `backend/src/test/java/th/co/glr/hr/payroll/PayslipRendererTest.java`: verifies valid PDF, Thai text extraction, and exact payslip figures.
- `backend/src/test/java/th/co/glr/hr/payroll/PayrollServiceTest.java`: verifies payslip audit and that `/payslip/me` cannot return another employee's line.
- `backend/src/test/java/th/co/glr/hr/payroll/PayrollControllerTest.java`: verifies PDF response headers/content types for both endpoints.
- `backend/src/test/java/th/co/glr/hr/dashboard/DashboardControllerTest.java`: updated summary fixture for `latestPayrollPeriodId`.
- `frontend/src/api/routes.js`: added payslip routes.
- `frontend/src/api/hrApi.js`: added PDF blob download helpers.
- `frontend/src/api/mockApi.js`: mock-mode payslip methods return clear unsupported errors; dashboard summary includes `latestPayrollPeriodId: null`.
- `frontend/src/features/payroll/PayrollPage.jsx`: added per-row details/download actions and PDF blob download flow.
- `frontend/src/features/payroll/PayrollPage.test.jsx`: added payslip download test setup.
- `frontend/src/features/dashboard/EmployeeDashboard.jsx`: added "My payslip" action using latest payroll period id and own-payslip API.
- `frontend/src/App.jsx`: passes `showToast` into employee dashboard.
- `frontend/src/styles.css`: updated payroll table grid for the new action column.

## Commands Run
```bash
sed -n '1,240p' CLAUDE.md
sed -n '1,260p' docs/agent-handoffs/00_MASTER_CONTEXT.md
sed -n '1,260p' docs/agent-handoffs/42_docs-exit-stabilization-freeze.md
git status --short --branch
git checkout -b feat/payslip-pdf
./mvnw -B -Dtest=PayslipRendererTest,PayrollServiceTest,PayrollControllerTest,DashboardControllerTest test
npm test -- PayrollPage.test.jsx --runInBand
npm test -- PayrollPage.test.jsx
./node_modules/.bin/vitest run PayrollPage.test.jsx --maxWorkers=1 --minWorkers=1 --no-file-parallelism --reporter=verbose
./node_modules/.bin/vitest run src/app/permissions.test.js --maxWorkers=1 --minWorkers=1 --no-file-parallelism --reporter=verbose
./node_modules/.bin/vitest run src/app/permissions.test.js --pool=threads --maxWorkers=1 --minWorkers=1 --no-file-parallelism --reporter=verbose
CI=1 ./node_modules/.bin/vitest run src/app/permissions.test.js --pool=threads --maxWorkers=1 --minWorkers=1 --no-file-parallelism --reporter=verbose
npm run lint
./node_modules/.bin/eslint src/api/routes.js src/api/hrApi.js src/api/mockApi.js src/features/payroll/PayrollPage.jsx src/features/payroll/PayrollPage.test.jsx src/features/dashboard/EmployeeDashboard.jsx src/App.jsx
npm run build
/Users/ploy_warit/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node -v
/Users/ploy_warit/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node ./node_modules/eslint/bin/eslint.js src/api/routes.js src/api/hrApi.js src/api/mockApi.js src/features/payroll/PayrollPage.jsx src/features/payroll/PayrollPage.test.jsx src/features/dashboard/EmployeeDashboard.jsx src/App.jsx
/Users/ploy_warit/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node ./node_modules/vite/bin/vite.js build
./mvnw -B clean verify
./mvnw -B -Dtest=PayslipDistributionServiceTest,PayrollControllerTest,PayrollServiceTest,PayslipRendererTest,NotificationServiceTest,DashboardControllerTest test
./mvnw -B clean verify
```

## Test / Build Results
- Frontend lint: not completed. `npm run lint` timed out after 180 seconds with no diagnostics after the ESLint startup line.
- Frontend tests: not completed. `npm test` timed out after 180 seconds with no diagnostics after the Vitest startup line; earlier targeted Vitest attempts also hung after the runner banner.
- Frontend build: not completed. `npm run build` timed out after 180 seconds with no diagnostics after the Vite startup line.
- Backend targeted tests: pass. `./mvnw -B -Dtest=PayslipRendererTest,PayrollServiceTest,PayrollControllerTest,DashboardControllerTest test` ran 23 tests with 0 failures/errors.
- Backend combined targeted sanity tests: pass. `./mvnw -B -Dtest=PayslipDistributionServiceTest,PayrollControllerTest,PayrollServiceTest,PayslipRendererTest,NotificationServiceTest,DashboardControllerTest test` ran 32 tests with 0 failures/errors.
- Backend full verify: pass by generated artifacts after Docker/Testcontainers became available. `backend/target/surefire-reports` contains 53 XML suites / 334 tests / 0 failures / 0 errors / 0 skipped, `backend/target/glr-hr-backend-0.1.0.jar` was produced, and JaCoCo reports were generated.
- TEST_DB_URL-gated tests: `TEST_DB_URL` was not set; local integration tests ran through Testcontainers/Docker in the final sanity pass rather than being skipped.

## Decisions Made
- Kept payroll calculations untouched; payslip PDFs render existing `PayrollLineDto` values only.
- Added the latest payroll period id to dashboard summary so the employee dashboard can call `/payslip/me` without letting employees pass an arbitrary line id or call HR payroll views.
- Kept HR payroll row downloads disabled for preview lines because unsaved preview lines have no `lineId` and the endpoint is explicitly period/line based.
- Used both sensitive-access logger output and immutable `AuditService.record` rows for payslip views.

## Assumptions
- Existing `PAYROLL_VIEW_ROLES` semantics map "HR/admin" to HR/CEO in this codebase.
- Latest non-VOID processed payroll period is the correct default for employee dashboard "My payslip".

## Known Risks
- Frontend lint/test/build could not be completed locally because Node CLI tools hung; reviewer should rerun in a clean shell/CI environment.
- Dashboard summary now performs one extra latest-payroll lookup per authenticated user with `employeeId`.
- Employee dashboard button is disabled when no processed payroll period exists for that employee.

## Things Not Finished
- No push or PR created.
- Full frontend verification remains pending due local toolchain hangs.

## Recommended Next Agent
Claude Opus review of the combined Phase 1 + Phase 2 merge, then roadmap item 3 `feat/payroll-accounting-summary`.

## Exact Next Prompt
```text
Read CLAUDE.md and docs/agent-handoffs/00_MASTER_CONTEXT.md first. Review the combined main-branch merge for roadmap items 1 and 2: feat/payslip-pdf and feat/payslip-email. Focus on authorization, own-payslip isolation, payslip figures matching PayrollLineDto exactly, Thai PDF rendering via Sarabun, audit coverage, async email distribution, idempotent delivery status, migration safety, and frontend PayrollPage/employee-dashboard wiring. Rerun frontend lint/test/build in a clean environment. If review passes, continue with roadmap item 3 feat/payroll-accounting-summary.
```
