---
title: Payroll
layer: HR
schemas: [hr, hr_restricted]
tags: [repo-map, domain/payroll]
synced: develop@fe5138dd
---
# Payroll
> Payroll runs, deductions, consents, tax, and the payroll draft etag.

**Layer:** HR  ·  **DB schemas:** `hr, hr_restricted`

## Backend
### `th.co.glr.hr.payroll` — [payroll/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/payroll)
- `backend/src/main/java/th/co/glr/hr/payroll/DeductionCapGroup.java`
- `backend/src/main/java/th/co/glr/hr/payroll/DeductionRuleClass.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollAllowanceEstimateResult.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollCalculation.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollCalculationInput.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollCalculator.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollCarryForwardDtos.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollClassificationDtos.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollClassifiedCalculationDtos.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollComponent.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollController.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollDeductionClassification.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollDeductionKind.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollDeductionOrderingPolicy.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollDraftETag.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollEmployeeInputRequest.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollEmployeeSnapshot.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollGarnishmentType.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollInputDraftDtos.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollLineDto.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollPeriodDto.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollReconciliationDtos.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollRepository.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollResponses.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollService.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollSpecialPayDto.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollTaxAllowanceInput.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollTaxTreatment.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollYearToDate.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayslipDistributionResponse.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayslipDistributionService.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PayslipRenderer.java`
- `backend/src/main/java/th/co/glr/hr/payroll/PerDiemBasis.java`
- `backend/src/main/java/th/co/glr/hr/payroll/ProcessPayrollRequest.java`
- `backend/src/main/java/th/co/glr/hr/payroll/declaration/TaxAllowanceAttachmentController.java`
- `backend/src/main/java/th/co/glr/hr/payroll/declaration/TaxAllowanceCapCatalog.java`
- `backend/src/main/java/th/co/glr/hr/payroll/declaration/TaxAllowanceDeclarationController.java`
- `backend/src/main/java/th/co/glr/hr/payroll/declaration/TaxAllowanceDeclarationDtos.java`
- `backend/src/main/java/th/co/glr/hr/payroll/declaration/TaxAllowanceDeclarationRepository.java`
- `backend/src/main/java/th/co/glr/hr/payroll/declaration/TaxAllowanceDeclarationService.java`
- `backend/src/main/java/th/co/glr/hr/payroll/declaration/TaxAllowanceDeclarationStatus.java`
- `backend/src/main/java/th/co/glr/hr/payroll/declaration/TaxAllowanceExpiryWorker.java`
- `backend/src/main/java/th/co/glr/hr/payroll/declaration/loryor01/LorYor01FormAssembler.java`
- `backend/src/main/java/th/co/glr/hr/payroll/declaration/loryor01/LorYor01FormData.java`
- `backend/src/main/java/th/co/glr/hr/payroll/declaration/loryor01/LorYor01Renderer.java`
- `backend/src/main/java/th/co/glr/hr/payroll/export/Cp874.java`
- `backend/src/main/java/th/co/glr/hr/payroll/export/KBankPctExporter.java`
- `backend/src/main/java/th/co/glr/hr/payroll/export/PayrollDetailExporter.java`
- `backend/src/main/java/th/co/glr/hr/payroll/export/PayrollDetailIdentityDto.java`
- `backend/src/main/java/th/co/glr/hr/payroll/export/PayrollExportFile.java`
- `backend/src/main/java/th/co/glr/hr/payroll/export/PayrollExportKind.java`
- `backend/src/main/java/th/co/glr/hr/payroll/export/PayrollExportRow.java`
- `backend/src/main/java/th/co/glr/hr/payroll/export/Pnd1Exporter.java`
- `backend/src/main/java/th/co/glr/hr/payroll/export/SsoExporter.java`
- `backend/src/main/java/th/co/glr/hr/payroll/obligation/DeductionObligationController.java`
- `backend/src/main/java/th/co/glr/hr/payroll/obligation/DeductionObligationDtos.java`
- `backend/src/main/java/th/co/glr/hr/payroll/obligation/DeductionObligationKind.java`
- `backend/src/main/java/th/co/glr/hr/payroll/obligation/DeductionObligationRepository.java`
- `backend/src/main/java/th/co/glr/hr/payroll/obligation/DeductionObligationService.java`
- `backend/src/main/java/th/co/glr/hr/payroll/obligation/DeductionObligationStatus.java`
- `backend/src/main/java/th/co/glr/hr/payroll/obligation/DeductionWrittenConsentController.java`
- `backend/src/main/java/th/co/glr/hr/payroll/obligation/DeductionWrittenConsentDtos.java`
- `backend/src/main/java/th/co/glr/hr/payroll/obligation/DeductionWrittenConsentRepository.java`
- `backend/src/main/java/th/co/glr/hr/payroll/obligation/DeductionWrittenConsentService.java`
- `backend/src/main/java/th/co/glr/hr/payroll/obligation/PayrollDeductionShortfallController.java`
- `backend/src/main/java/th/co/glr/hr/payroll/obligation/PayrollDeductionShortfallDtos.java`
- `backend/src/main/java/th/co/glr/hr/payroll/obligation/PayrollDeductionShortfallRepository.java`
- `backend/src/main/java/th/co/glr/hr/payroll/obligation/PayrollDeductionShortfallService.java`

## Frontend
### `features/payroll` — [payroll/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/payroll)
- `frontend/src/features/payroll/DeductionConsentFormModal.jsx`
- `frontend/src/features/payroll/DeductionConsentsPage.jsx`
- `frontend/src/features/payroll/DeductionShortfallsPage.jsx`
- `frontend/src/features/payroll/PayrollPage.jsx`

## DB
- Schemas: `hr, hr_restricted`

## Related
[[employee|Employee & Self-service]] · [[attendance|Attendance]] · [[overtime|Overtime]] · [[leave|Leave]]

## Gotchas
- Payroll/tax math is UNTOUCHABLE without explicit request (CLAUDE.md).
- computeDraftEtag is mirrored in the mock — a mock-driven test can't validate the algorithm, only plumbing.

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
