---
title: Pricing Flow
layer: Sales / CRM
schemas: [sales, price_catalog]
tags: [repo-map, domain/pricing-flow]
synced: develop@ef9b17dc
---
# Pricing Flow
> Pricing request → factory quote → landed cost → CEO selling-price decision.

**Layer:** Sales / CRM  ·  **DB schemas:** `sales, price_catalog`

## Backend
### `th.co.glr.hr.pricingrequest` — [pricingrequest/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/pricingrequest)
- `backend/src/main/java/th/co/glr/hr/pricingrequest/LeadTimeChangeController.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/LeadTimeChangeDtos.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/LeadTimeChangeRepository.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/LeadTimeChangeRequests.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/LeadTimeChangeService.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/LeadTimeChangeStatus.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestController.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestDtos.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestEventKind.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestRecipient.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestRepository.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestRequests.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestResponses.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestService.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestStatus.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/QuantityType.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/UnitBasis.java`
- `backend/src/main/java/th/co/glr/hr/pricingrequest/UnitBasisMetaController.java`
### `th.co.glr.hr.pricingcosting` — [pricingcosting/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/pricingcosting)
- `backend/src/main/java/th/co/glr/hr/pricingcosting/LandedCostCalculator.java`
- `backend/src/main/java/th/co/glr/hr/pricingcosting/PricingCostingController.java`
- `backend/src/main/java/th/co/glr/hr/pricingcosting/PricingCostingDtos.java`
- `backend/src/main/java/th/co/glr/hr/pricingcosting/PricingCostingRepository.java`
- `backend/src/main/java/th/co/glr/hr/pricingcosting/PricingCostingRequests.java`
- `backend/src/main/java/th/co/glr/hr/pricingcosting/PricingCostingService.java`
- `backend/src/main/java/th/co/glr/hr/pricingcosting/PricingFormulaEngine.java`
### `th.co.glr.hr.pricingdecision` — [pricingdecision/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/pricingdecision)
- `backend/src/main/java/th/co/glr/hr/pricingdecision/PricingDecisionController.java`
- `backend/src/main/java/th/co/glr/hr/pricingdecision/PricingDecisionDtos.java`
- `backend/src/main/java/th/co/glr/hr/pricingdecision/PricingDecisionRepository.java`
- `backend/src/main/java/th/co/glr/hr/pricingdecision/PricingDecisionRequests.java`
- `backend/src/main/java/th/co/glr/hr/pricingdecision/PricingDecisionService.java`
- `backend/src/main/java/th/co/glr/hr/pricingdecision/PricingDecisionStatus.java`
### `th.co.glr.hr.pricing` — [pricing/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/pricing)
- `backend/src/main/java/th/co/glr/hr/pricing/BotFxFetchService.java`
- `backend/src/main/java/th/co/glr/hr/pricing/FxRateController.java`
- `backend/src/main/java/th/co/glr/hr/pricing/FxRateDto.java`
- `backend/src/main/java/th/co/glr/hr/pricing/FxRateRepository.java`
- `backend/src/main/java/th/co/glr/hr/pricing/FxResolver.java`
- `backend/src/main/java/th/co/glr/hr/pricing/PriceCalcConfigController.java`
- `backend/src/main/java/th/co/glr/hr/pricing/PriceCalcConfigDto.java`
- `backend/src/main/java/th/co/glr/hr/pricing/PriceCalcConfigRepository.java`
- `backend/src/main/java/th/co/glr/hr/pricing/PricingFormulaConfigController.java`
- `backend/src/main/java/th/co/glr/hr/pricing/PricingFormulaConfigDtos.java`
- `backend/src/main/java/th/co/glr/hr/pricing/PricingFormulaConfigRepository.java`
- `backend/src/main/java/th/co/glr/hr/pricing/PricingFormulaConfigRequests.java`
- `backend/src/main/java/th/co/glr/hr/pricing/UpdatePriceCalcConfigRequest.java`
- `backend/src/main/java/th/co/glr/hr/pricing/UpsertFxRateRequest.java`
### `th.co.glr.hr.factoryquote` — [factoryquote/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/factoryquote)
- `backend/src/main/java/th/co/glr/hr/factoryquote/FactoryQuoteCarryForward.java`
- `backend/src/main/java/th/co/glr/hr/factoryquote/FactoryQuoteController.java`
- `backend/src/main/java/th/co/glr/hr/factoryquote/FactoryQuoteDtos.java`
- `backend/src/main/java/th/co/glr/hr/factoryquote/FactoryQuoteRepository.java`
- `backend/src/main/java/th/co/glr/hr/factoryquote/FactoryQuoteRequests.java`
- `backend/src/main/java/th/co/glr/hr/factoryquote/FactoryQuoteService.java`
- `backend/src/main/java/th/co/glr/hr/factoryquote/FactoryQuoteStatus.java`

## Frontend
### `features/pricingRequests` — [pricingRequests/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/pricingRequests)
- `frontend/src/features/pricingRequests/FactoryContactDialog.jsx`
- `frontend/src/features/pricingRequests/LeadTimeChangeBanner.jsx`
- `frontend/src/features/pricingRequests/LeadTimeChangeDialog.jsx`
- `frontend/src/features/pricingRequests/PricingRequestCreateModal.jsx`
- `frontend/src/features/pricingRequests/PricingRequestDetailPage.jsx`
- `frontend/src/features/pricingRequests/PricingRequestPanel.jsx`
- `frontend/src/features/pricingRequests/PricingRequestQueuePage.jsx`
- `frontend/src/features/pricingRequests/factoryContactMeta.js`
- `frontend/src/features/pricingRequests/pricingRequestMeta.js`
- `frontend/src/features/pricingRequests/quotedPriceThb.js`
- `frontend/src/features/pricingRequests/unitBasisCatalog.js`
### `features/ceoSettings` — [ceoSettings/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/ceoSettings)
- `frontend/src/features/ceoSettings/CeoSettingsPage.jsx`

## DB
- Schemas: `sales, price_catalog`
- Key migrations: `V61 factory_quote + costing foundation`; `V109 formula engine (landed cost)`; `V141 CEO owns costing`; `V156 uncostable items`; `V185 direct-deal item fields`; `V187 CEO price mode`

## Related
[[catalog-price-import|Catalog & Price Import]] · [[sales-deal-ticket|Sales Deal & Ticket]] · [[quotation|Quotation]] · [[import-fulfilment|Import / Fulfilment]]

## Gotchas
- LandedCostCalculator reads thickness/origin from the CATALOG LINK only (+ Sales-entered thickness fallback, 2026-10) — see CLAUDE.md.
- A line with no resolvable thickness/origin/sqm is set ASIDE as 'uncostable' (V156), not a hard 422.
- Mock reimplements SOME pricing math but NOT freight — do not trust mock for pricing. See CLAUDE.md 'Mock API contract'.
- ceoSettings = FX rates + pricing formula config (BOT fetch + manual entry).

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
