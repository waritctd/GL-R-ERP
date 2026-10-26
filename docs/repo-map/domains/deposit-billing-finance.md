---
title: Deposit · Billing · Finance
layer: Sales / CRM
schemas: [sales, hr]
tags: [repo-map, domain/deposit-billing-finance]
synced: develop@fe5138dd
---
# Deposit · Billing · Finance
> Deposit notices, billing notes, tax invoices, payments, and sales commission.

**Layer:** Sales / CRM  ·  **DB schemas:** `sales, hr`

## Backend
### `th.co.glr.hr.deposit` — [deposit/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/deposit)
- `backend/src/main/java/th/co/glr/hr/deposit/DepositNoticeController.java`
- `backend/src/main/java/th/co/glr/hr/deposit/DepositNoticeDraftRequest.java`
- `backend/src/main/java/th/co/glr/hr/deposit/DepositNoticeDto.java`
- `backend/src/main/java/th/co/glr/hr/deposit/DepositNoticeItemDto.java`
- `backend/src/main/java/th/co/glr/hr/deposit/DepositNoticeItemRequest.java`
- `backend/src/main/java/th/co/glr/hr/deposit/DepositNoticeRenderer.java`
- `backend/src/main/java/th/co/glr/hr/deposit/DepositNoticeRepository.java`
- `backend/src/main/java/th/co/glr/hr/deposit/DepositNoticeService.java`
- `backend/src/main/java/th/co/glr/hr/deposit/DocumentNoteTemplateDto.java`
- `backend/src/main/java/th/co/glr/hr/deposit/RemainingInvoiceController.java`
- `backend/src/main/java/th/co/glr/hr/deposit/RemainingInvoiceDocumentDto.java`
- `backend/src/main/java/th/co/glr/hr/deposit/RemainingInvoiceDocumentItemDto.java`
- `backend/src/main/java/th/co/glr/hr/deposit/RemainingInvoiceDraftRequest.java`
- `backend/src/main/java/th/co/glr/hr/deposit/RemainingInvoiceDto.java`
- `backend/src/main/java/th/co/glr/hr/deposit/RemainingInvoiceItemDto.java`
- `backend/src/main/java/th/co/glr/hr/deposit/RemainingInvoiceOptionsDto.java`
- `backend/src/main/java/th/co/glr/hr/deposit/RemainingInvoiceRenderer.java`
- `backend/src/main/java/th/co/glr/hr/deposit/RemainingInvoiceRepository.java`
- `backend/src/main/java/th/co/glr/hr/deposit/RemainingInvoiceService.java`
- `backend/src/main/java/th/co/glr/hr/deposit/RevisionRequest.java`
- `backend/src/main/java/th/co/glr/hr/deposit/RevisionScope.java`
### `th.co.glr.hr.billing` — [billing/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/billing)
- `backend/src/main/java/th/co/glr/hr/billing/BillingNoteCancelRequest.java`
- `backend/src/main/java/th/co/glr/hr/billing/BillingNoteCandidateDto.java`
- `backend/src/main/java/th/co/glr/hr/billing/BillingNoteCandidatesDto.java`
- `backend/src/main/java/th/co/glr/hr/billing/BillingNoteController.java`
- `backend/src/main/java/th/co/glr/hr/billing/BillingNoteDocumentDto.java`
- `backend/src/main/java/th/co/glr/hr/billing/BillingNoteDraftRequest.java`
- `backend/src/main/java/th/co/glr/hr/billing/BillingNoteDto.java`
- `backend/src/main/java/th/co/glr/hr/billing/BillingNoteLineDto.java`
- `backend/src/main/java/th/co/glr/hr/billing/BillingNoteLineRenderDto.java`
- `backend/src/main/java/th/co/glr/hr/billing/BillingNoteLineSelection.java`
- `backend/src/main/java/th/co/glr/hr/billing/BillingNoteMarkReceivedRequest.java`
- `backend/src/main/java/th/co/glr/hr/billing/BillingNoteRenderer.java`
- `backend/src/main/java/th/co/glr/hr/billing/BillingNoteRepository.java`
- `backend/src/main/java/th/co/glr/hr/billing/BillingNoteService.java`
### `th.co.glr.hr.finance` — [finance/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/finance)
- `backend/src/main/java/th/co/glr/hr/finance/FinanceDealController.java`
- `backend/src/main/java/th/co/glr/hr/finance/FinanceDealDto.java`
- `backend/src/main/java/th/co/glr/hr/finance/FinanceDealRepository.java`
- `backend/src/main/java/th/co/glr/hr/finance/FinanceDealService.java`
### `th.co.glr.hr.specialmoney` — [specialmoney/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/specialmoney)
- `backend/src/main/java/th/co/glr/hr/specialmoney/CapRule.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/ClaimWindow.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/EligibilityRule.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/EmployeeEligibilitySnapshot.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/PolicyAmounts.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/PolicyDecision.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/ReviewSpecialMoneyRequest.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyApprovalPreviewDto.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyAttachmentDto.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyBucket.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyController.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyEmployeeOption.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyFilter.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyPolicyEvaluator.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyRepository.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyRequestDto.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyResponses.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyService.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyStatus.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyType.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyTypeOption.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyUsageDto.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SubmitSpecialMoneyHttpRequest.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/SubmitSpecialMoneyRequest.java`
- `backend/src/main/java/th/co/glr/hr/specialmoney/UsageSnapshot.java`
### `th.co.glr.hr.commission` — [commission/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/commission)
- `backend/src/main/java/th/co/glr/hr/commission/CommissionAttachmentRepository.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionCalculator.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionController.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionKind.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionMonthlySummaryDto.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionRecord.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionRepOptionDto.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionRepository.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionResponses.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionService.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionSimulationDto.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionSimulatorRequest.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionStatus.java`
- `backend/src/main/java/th/co/glr/hr/commission/CommissionTierRowDto.java`
- `backend/src/main/java/th/co/glr/hr/commission/CreateClawbackRequest.java`
- `backend/src/main/java/th/co/glr/hr/commission/IncentiveTierConfig.java`
- `backend/src/main/java/th/co/glr/hr/commission/InvoiceCalculation.java`
- `backend/src/main/java/th/co/glr/hr/commission/InvoiceDetails.java`
- `backend/src/main/java/th/co/glr/hr/commission/ManualCommissionRequest.java`
- `backend/src/main/java/th/co/glr/hr/commission/PayrollCommissionSummaryDto.java`
- `backend/src/main/java/th/co/glr/hr/commission/PendingCommissionDto.java`
- `backend/src/main/java/th/co/glr/hr/commission/PendingCommissionItemDto.java`
- `backend/src/main/java/th/co/glr/hr/commission/QuotationDisplayNameEligibility.java`
- `backend/src/main/java/th/co/glr/hr/commission/ReviewCommissionRequest.java`
- `backend/src/main/java/th/co/glr/hr/commission/SalesRepCommissionSummaryDto.java`
- `backend/src/main/java/th/co/glr/hr/commission/StockBonusConfig.java`
- `backend/src/main/java/th/co/glr/hr/commission/SubmitCommissionRequest.java`
- `backend/src/main/java/th/co/glr/hr/commission/TeamOverrideConfig.java`
- `backend/src/main/java/th/co/glr/hr/commission/TeamOverrideRecipient.java`
- `backend/src/main/java/th/co/glr/hr/commission/TierConfig.java`
- `backend/src/main/java/th/co/glr/hr/commission/UpdateCommissionDeductionsRequest.java`

## Frontend
### `features/deposits` — [deposits/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/deposits)
- `frontend/src/features/deposits/DepositNoticePage.jsx`
### `features/finance` — [finance/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/finance)
- `frontend/src/features/finance/AccountFinancePage.jsx`
- `frontend/src/features/finance/FinanceActionModals.jsx`
- `frontend/src/features/finance/FinanceDealPage.jsx`
- `frontend/src/features/finance/FinanceMilestoneSections.jsx`
- `frontend/src/features/finance/dealHref.js`
- `frontend/src/features/finance/financeDealActions.js`
- `frontend/src/features/finance/moneyMilestone.js`
### `features/commissions` — [commissions/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/commissions)
- `frontend/src/features/commissions/CommissionPage.jsx`
- `frontend/src/features/commissions/CommissionStatement.jsx`
- `frontend/src/features/commissions/InvoiceFromDealForm.jsx`
- `frontend/src/features/commissions/ReceiptChain.jsx`
- `frontend/src/features/commissions/ReceiptLedger.jsx`
- `frontend/src/features/commissions/WeightSegmented.jsx`
- `frontend/src/features/commissions/WeightingDesk.jsx`
- `frontend/src/features/commissions/commissionRecord.js`
- `frontend/src/features/commissions/invoiceFromDeal.js`
### `features/specialmoney` — [specialmoney/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/specialmoney)
- `frontend/src/features/specialmoney/ApproveSpecialMoneyDialog.jsx`
- `frontend/src/features/specialmoney/AttachmentList.jsx`
- `frontend/src/features/specialmoney/EntitlementPanel.jsx`
- `frontend/src/features/specialmoney/RuleCard.jsx`
- `frontend/src/features/specialmoney/SpecialMoneyPanel.jsx`
- `frontend/src/features/specialmoney/TypePicker.jsx`
- `frontend/src/features/specialmoney/specialMoneyRules.js`
- `frontend/src/features/specialmoney/thaiProvinces.js`

## DB
- Schemas: `sales, hr`
- Key migrations: `V72 pricing_decision`; `V188 remaining invoice document`; `V189 billing note document`

## Related
[[sales-deal-ticket|Sales Deal & Ticket]] · [[quotation|Quotation]]

## Gotchas
- DepositNoticeService.issue gates on ticket.status='quotation_issued' AND paymentStatus='CUSTOMER_CONFIRMED' — else 409.
- Commission math is UNTOUCHABLE without explicit request (CLAUDE.md scope rule).
- PDF/XLSX render is a 'not supported in mock mode' stub — documents only truly render on the real backend.

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
