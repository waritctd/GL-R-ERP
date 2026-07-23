# Agent Handoff

## Task
Implement the three real payroll text-file generators payroll must produce, behind an HR dropdown:
1. **KBank PCT** — K Cash Connect Plus salary-transfer file.
2. **PND1 (ภ.ง.ด.1)** — Revenue Department monthly withholding-tax file.
3. **SSO สปส.1-10** — Social Security Office contribution file.

None existed before; the only export was a fake `GLR_PAYROLL|…` file whose frontend path wrapped the
body in a **UTF-8** blob — which would corrupt the CP874 Thai bytes these formats require.

## Branch
`feat/payroll-statutory-export-files`

## Base Commit
`56aaa02` (origin/main at branch creation)

## Current Commit
Not committed yet (working tree; commit/push on request).

## Agent / Model Used
Claude Opus 4.8

## Scope

### In Scope
- New CP874/fixed-width + pipe-delimited generators; a new `GET /api/payroll/{periodId}/export/{kind}`
  endpoint (kind ∈ kbank|pnd1|sso) returning raw bytes; HR dropdown UI; employer config; tests.
- **Contract change (stated, not a side effect):** removed `GET /api/payroll/{periodId}/bank-export`
  (`PayrollService.bankExport`, the fake `GLR_PAYROLL` format) and replaced it with the new export
  endpoint. Frontend `bankExport` → `exportFile`.

### Out of Scope
- No payroll/tax/SSO **math** changed — generators only render already-computed `payroll_line` values.
- PND1 owner dual-income-type split (see Known Risks).

## Files Changed
Backend (main):
- `payroll/export/Cp874.java` — byte-level CP874 (x-windows-874) padding/satang/CRLF helper.
- `payroll/export/PayrollExportRow.java` — export DTO (computed amounts + PII + bank + address).
- `payroll/export/KBankPctExporter.java` / `Pnd1Exporter.java` / `SsoExporter.java` — the 3 formatters.
- `payroll/export/PayrollExportKind.java` / `PayrollExportFile.java` — kind enum + result record.
- `payroll/PayrollService.java` — `export(kind, periodId, effectiveDate, actor)`; removed `bankExport`;
  injects the 3 exporters + `AppProperties`; audits per-kind PII fields.
- `payroll/PayrollController.java` — `GET /{periodId}/export/{kind}?effectiveDate=`; removed bank-export.
- `payroll/PayrollRepository.java` — `findExportRows(periodId)` joining `payroll_line` → employee/title/
  `hr_restricted.employee_pii`/bank/current-address.
- `config/AppProperties.java` — `app.payroll.employer.*` (company name/tax id, KBank debit acct + batch
  ref, SSO employer acct/branch/rate, establishment name, `defaultTransferDay=26`).
- `resources/application.yml` (env-overridable blanks) + `application-demo.yml` (sample values).

Frontend:
- `api/routes.js` `payroll.export(...)`, `api/hrApi.js` `exportFile(...)` (binary blob fetch),
  `api/mockApi.js` `exportFile` mirror + header, `features/payroll/PayrollPage.jsx` dropdown
  (KBank/ภ.ง.ด.1/สปส.1-10) + pay-date input (default 26th) + download button (replaces "Bank file").

Tests: `payroll/export/{Cp874Test,KBankPctExporterGoldenTest,Pnd1ExporterGoldenTest,SsoExporterTest}`,
golden resources `test/resources/payroll-export/{PCTNew2906,Pnd1}.golden.txt`, updated
`PayrollServiceTest`/`PayrollControllerTest`/`PayrollRepositoryIntegrationTest`/
`SecurityAuthorizationIntegrationTest`/`RetroactiveOvertimeReachesPayrollIntegrationTest`,
`frontend PayrollPage.test.jsx`.

## Commands Run
```bash
# frontend (all green)
cd frontend && npm run lint && npx vitest run && npm run build   # 433 tests pass; build ok

# backend — DB-free unit tests
cd backend && ./mvnw -o test -Dtest='PayrollServiceTest,PayrollControllerTest,Cp874Test,KBankPctExporterGoldenTest,Pnd1ExporterGoldenTest,SsoExporterTest'  # 36 pass

# backend integration — each class on its own fresh local Postgres DB (Docker down)
TEST_DB_URL=jdbc:postgresql://localhost:5432/<throwaway>?user=$USER ./mvnw -o test -DforkCount=1 -Dtest='<class>'
#   PayrollRepositoryIntegrationTest 6, RetroactiveOvertimeReachesPayrollIntegrationTest 9,
#   SecurityAuthorizationIntegrationTest 13 — all pass
```

## Test / Build Results
- Frontend: **pass** — lint 0 errors (pre-existing `load` dep warning), 433 vitest tests, build ok.
- Backend unit: **pass** — 36 (formatters/golden/service/controller).
- Backend integration (**ran** on real local Postgres, per-class isolated DBs): **pass** —
  Repository 6, Overtime 9, Security authz 13.
- Full `mvnw clean verify` in ONE run was **not completed**: the external-`TEST_DB_URL` path
  clean+migrates per class and (a) exceeds 10 min and (b) races Flyway reset when `@SpringBootTest`
  and repository ITs share one physical DB (each class passes alone; CI's Testcontainers golden-template
  path avoids this). Whole tree **compiles**. Re-run full suite via CI (Testcontainers) or Docker.

## Authz Evidence
Verified against the real Java service on real Postgres (`SecurityAuthorizationIntegrationTest`,
`@SpringBootTest` + real SecurityFilterChain + `PayrollRepositoryIntegrationTest`):
- employee & sales sessions → **403** on `/export/{kbank,pnd1,sso}` (wrong-way-round);
  unauthenticated → **401**; HR & CEO pass authz (404 on a missing period).
- `findExportRows` returns the period's rows joined to `hr_restricted.employee_pii`, scoped to the
  period (a separate month's rows do not leak), null tax_id stays null.
- **Mutation check:** removing both guards (controller `@PreAuthorize` → `isAuthenticated()` **and**
  service `requireRole`) turned the employee-403 test red (got 200); reverted to a clean diff.

The endpoint reads PDPA-restricted PII, gated to the existing HR/CEO payroll-view roles; every call is
audited via `auditPayrollAccess("EXPORT_PAYROLL_<KIND>", …)` naming the exact fields.

## Decisions Made
- KBank + PND1 are pinned **byte-for-byte** to the user's golden sample files (golden-file tests).
- Employer constants live in `app.payroll.employer.*` config (user's choice), not a table/UI.
- One pay-date field (default the **26th**, HR-overridable) drives the KBank effective date and the
  PND1/SSO pay date — extended slightly beyond "KBank-only" so PND1/SSO also get the right pay date.
- Files returned as `application/octet-stream`; frontend fetches a binary blob (never UTF-8 text).

## Assumptions
- `x-windows-874` is the correct codec (verified: it round-trips both golden files byte-identically).
- KBank detail leaves tax-id/personal-id/address blank, matching the golden file (no PII in bank file).

## Known Risks
- **SSO bytes UNVALIDATED against a real filing.** No golden sample existed; conventions (amounts as
  satang, rate `percent×100`, wage = capped `sso_wage_base`, title code blank, Gregorian period year)
  are isolated in `SsoExporter` and flagged in its Javadoc. **User must validate the SSO file on the
  SSO e-service before real use.**
- **PND1 owner split deferred:** the ERP has one withholding figure per employee, so PND1 emits one
  line/employee at income type 1; the sample's owner type-1+type-4 split is not reproduced.
- **Employer config must be filled per environment.** `sso-employer-account` is a placeholder
  (`0000000000`) in demo; real KBank debit account / SSO account must be set before production use.

## Things Not Finished
- Not committed/pushed (awaiting user say-so).
- Full-suite `mvnw clean verify` in one shot (blocked by local Docker down + external-DB Flyway race;
  run on CI).
- Browser screenshot of the dropdown skipped (mock port 5200 was held by another session; the dropdown
  is covered by `PayrollPage.test.jsx`).

## Recommended Next Agent
Claude Opus review (verify golden byte-equality + authz evidence), then user validates the SSO file on
the SSO e-service and provides the real SSO employer account/branch for config.

## Exact Next Prompt
```
Review branch feat/payroll-statutory-export-files (payroll KBank/PND1/SSO export files). Confirm:
(1) the KBank + PND1 golden-file tests actually assert byte-equality against the real sample files;
(2) the CP874 encoding path is used end-to-end (octet-stream, binary blob on the frontend);
(3) the export authz integration test + mutation check are sound and PII access is audited.
Then help the user set the real app.payroll.employer.* values and validate the SSO สปส.1-10 output
on the SSO e-service. Run the backend suite via CI/Testcontainers (the local external-DB path races
Flyway reset across @SpringBootTest + repository ITs).
```
