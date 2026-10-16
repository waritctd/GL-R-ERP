---
title: Import / Fulfilment
layer: Sales / CRM
schemas: [sales]
tags: [repo-map, domain/import-fulfilment]
synced: develop@ef9b17dc
---
# Import / Fulfilment
> Per-factory import requests, procurement steps, and delivery fulfilment.

**Layer:** Sales / CRM  ·  **DB schemas:** `sales`

## Backend
### `th.co.glr.hr.importdeal` — [importdeal/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/importdeal)
- `backend/src/main/java/th/co/glr/hr/importdeal/ImportDealController.java`
- `backend/src/main/java/th/co/glr/hr/importdeal/ImportDealDtos.java`
- `backend/src/main/java/th/co/glr/hr/importdeal/ImportDealService.java`
### `th.co.glr.hr.importrequest` — [importrequest/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/importrequest)
- `backend/src/main/java/th/co/glr/hr/importrequest/ImportRequestController.java`
- `backend/src/main/java/th/co/glr/hr/importrequest/ImportRequestDtos.java`
- `backend/src/main/java/th/co/glr/hr/importrequest/ImportRequestFormAssembler.java`
- `backend/src/main/java/th/co/glr/hr/importrequest/ImportRequestFormData.java`
- `backend/src/main/java/th/co/glr/hr/importrequest/ImportRequestLinePrefill.java`
- `backend/src/main/java/th/co/glr/hr/importrequest/ImportRequestQueryRepository.java`
- `backend/src/main/java/th/co/glr/hr/importrequest/ImportRequestRenderer.java`
- `backend/src/main/java/th/co/glr/hr/importrequest/ImportRequestRepository.java`
- `backend/src/main/java/th/co/glr/hr/importrequest/ImportRequestRequests.java`
- `backend/src/main/java/th/co/glr/hr/importrequest/ImportRequestService.java`
- `backend/src/main/java/th/co/glr/hr/importrequest/ImportRequestStatus.java`
- `backend/src/main/java/th/co/glr/hr/importrequest/ImportRequestStep.java`
- `backend/src/main/java/th/co/glr/hr/importrequest/LeadTimeDefaults.java`
### `th.co.glr.hr.procurement` — [procurement/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/procurement)
- `backend/src/main/java/th/co/glr/hr/procurement/FactoryPurchaseOrderStatus.java`
- `backend/src/main/java/th/co/glr/hr/procurement/ProcurementDtos.java`
- `backend/src/main/java/th/co/glr/hr/procurement/ProcurementRepository.java`
- `backend/src/main/java/th/co/glr/hr/procurement/ProcurementRequests.java`
- `backend/src/main/java/th/co/glr/hr/procurement/ProcurementService.java`
### `th.co.glr.hr.factory` — [factory/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/factory)
- `backend/src/main/java/th/co/glr/hr/factory/FactoryConfigController.java`
- `backend/src/main/java/th/co/glr/hr/factory/FactoryConfigDto.java`
- `backend/src/main/java/th/co/glr/hr/factory/FactoryConfigRepository.java`

## Frontend
### `features/fulfilment` — [fulfilment/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/fulfilment)
- `frontend/src/features/fulfilment/ImportFulfilmentPage.jsx`
### `features/importDeal` — [importDeal/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/importDeal)
- `frontend/src/features/importDeal/ImportDealPage.jsx`
### `features/importProgress` — [importProgress/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/importProgress)
- `frontend/src/features/importProgress/FactoryProgressBar.jsx`
- `frontend/src/features/importProgress/ImportStatusStrip.jsx`
- `frontend/src/features/importProgress/importSteps.js`

## DB
- Schemas: `sales`
- Key migrations: `V77 factory_purchase_order`; `V184 per-factory import_request (sales.import_request)`

## Related
[[sales-deal-ticket|Sales Deal & Ticket]] · [[pricing-flow|Pricing Flow]]

## Gotchas
- TWO parallel import systems: legacy deal-level (fulfillmentStatus IR_ISSUED) vs per-factory V184 (sales.import_request).
- A deal is in the import scope only when salesStage >= PROCUREMENT AND fulfillmentStatus != null.
- An item must be linked to a factory before it enters the import lane.

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
