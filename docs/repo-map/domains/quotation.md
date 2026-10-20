---
title: Quotation
layer: Sales / CRM
schemas: [sales, customers]
tags: [repo-map, domain/quotation]
synced: develop@ef9b17dc
---
# Quotation
> Customer-facing quotation generation, revisions, and the rendered PDF/XLSX.

**Layer:** Sales / CRM  ·  **DB schemas:** `sales, customers`

## Backend
### `th.co.glr.hr.dealquotation` — [dealquotation/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/dealquotation)
- `backend/src/main/java/th/co/glr/hr/dealquotation/DealQuotationAttachmentController.java`
- `backend/src/main/java/th/co/glr/hr/dealquotation/DealQuotationAttachmentRepository.java`
- `backend/src/main/java/th/co/glr/hr/dealquotation/DealQuotationAttachmentService.java`
- `backend/src/main/java/th/co/glr/hr/dealquotation/DealQuotationController.java`
- `backend/src/main/java/th/co/glr/hr/dealquotation/DealQuotationDtos.java`
- `backend/src/main/java/th/co/glr/hr/dealquotation/DealQuotationLines.java`
- `backend/src/main/java/th/co/glr/hr/dealquotation/DealQuotationRenderAdapter.java`
- `backend/src/main/java/th/co/glr/hr/dealquotation/DealQuotationRepository.java`
- `backend/src/main/java/th/co/glr/hr/dealquotation/DealQuotationRequests.java`
- `backend/src/main/java/th/co/glr/hr/dealquotation/DealQuotationService.java`
- `backend/src/main/java/th/co/glr/hr/dealquotation/EmployeeSignatureController.java`
- `backend/src/main/java/th/co/glr/hr/dealquotation/EmployeeSignatureRepository.java`
- `backend/src/main/java/th/co/glr/hr/dealquotation/EmployeeSignatureService.java`
- `backend/src/main/java/th/co/glr/hr/dealquotation/QuotationHtmlDocument.java`
- `backend/src/main/java/th/co/glr/hr/dealquotation/QuotationItemPictures.java`
- `backend/src/main/java/th/co/glr/hr/dealquotation/WastageCalculator.java`
### `th.co.glr.hr.customerquotation` — [customerquotation/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/customerquotation)
- `backend/src/main/java/th/co/glr/hr/customerquotation/CustomerQuotationController.java`
- `backend/src/main/java/th/co/glr/hr/customerquotation/CustomerQuotationDtos.java`
- `backend/src/main/java/th/co/glr/hr/customerquotation/CustomerQuotationRepository.java`
- `backend/src/main/java/th/co/glr/hr/customerquotation/CustomerQuotationRequests.java`
- `backend/src/main/java/th/co/glr/hr/customerquotation/CustomerQuotationService.java`
- `backend/src/main/java/th/co/glr/hr/customerquotation/DiscountApprovalController.java`
- `backend/src/main/java/th/co/glr/hr/customerquotation/DiscountApprovalDtos.java`
- `backend/src/main/java/th/co/glr/hr/customerquotation/DiscountApprovalRepository.java`
- `backend/src/main/java/th/co/glr/hr/customerquotation/DiscountApprovalRequests.java`
- `backend/src/main/java/th/co/glr/hr/customerquotation/DiscountApprovalService.java`
- `backend/src/main/java/th/co/glr/hr/customerquotation/QuotationExpiryWorker.java`

## Frontend
### `features/quotations` — [quotations/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/quotations)
- `frontend/src/features/quotations/ChangeCustomerModal.jsx`
- `frontend/src/features/quotations/CustomerDetailsFields.jsx`
- `frontend/src/features/quotations/DealCustomerCard.jsx`
- `frontend/src/features/quotations/DealPicker.jsx`
- `frontend/src/features/quotations/DesignerPicker.jsx`
- `frontend/src/features/quotations/QuotationAdjustmentRow.jsx`
- `frontend/src/features/quotations/QuotationAttachmentsPanel.jsx`
- `frontend/src/features/quotations/QuotationChecklist.jsx`
- `frontend/src/features/quotations/QuotationContactPicker.jsx`
- `frontend/src/features/quotations/QuotationDealFields.jsx`
- `frontend/src/features/quotations/QuotationDocumentView.jsx`
- `frontend/src/features/quotations/QuotationEditorPage.jsx`
- `frontend/src/features/quotations/QuotationItemRow.jsx`
- `frontend/src/features/quotations/QuotationListPage.jsx`
- `frontend/src/features/quotations/QuotationPlainItemRow.jsx`
- `frontend/src/features/quotations/quotationCopy.js`
- `frontend/src/features/quotations/quotationMeta.js`
- `frontend/src/features/quotations/quotationPrefs.js`

## DB
- Schemas: `sales, customers`
- Key migrations: `V165 deal_quotation_direct`; `V176 quotation_item sqm_per_box`; `V192.x per-sqm / mixed price modes`

## Related
[[pricing-flow|Pricing Flow]] · [[sales-deal-ticket|Sales Deal & Ticket]] · [[customer|Customer]] · [[deposit-billing-finance|Deposit · Billing · Finance]]

## Gotchas
- QuotationRenderer needs the licensed Thai fonts (backend/fonts/, gitignored) — a Git build embeds substitute fonts.
- Quotation carries its OWN frozen price snapshot, independent of the live pricing decision.

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
