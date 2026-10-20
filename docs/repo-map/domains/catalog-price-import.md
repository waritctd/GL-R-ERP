---
title: Catalog & Price Import
layer: Sales / CRM
schemas: [price_catalog]
tags: [repo-map, domain/catalog-price-import]
synced: develop@ef9b17dc
---
# Catalog & Price Import
> The product price catalogue (import-managed, version-controlled) + brand reference.

**Layer:** Sales / CRM  ·  **DB schemas:** `price_catalog`

## Backend
### `th.co.glr.hr.catalog` — [catalog/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/catalog)
- `backend/src/main/java/th/co/glr/hr/catalog/CatalogController.java`
- `backend/src/main/java/th/co/glr/hr/catalog/CatalogDto.java`
- `backend/src/main/java/th/co/glr/hr/catalog/CatalogRepository.java`
- `backend/src/main/java/th/co/glr/hr/catalog/ProductPriceDto.java`
- `backend/src/main/java/th/co/glr/hr/catalog/ProductPriceInput.java`
- `backend/src/main/java/th/co/glr/hr/catalog/ThicknessDefaultController.java`
- `backend/src/main/java/th/co/glr/hr/catalog/ThicknessDefaultDtos.java`
- `backend/src/main/java/th/co/glr/hr/catalog/ThicknessDefaultRepository.java`
- `backend/src/main/java/th/co/glr/hr/catalog/ThicknessDefaultRequests.java`
- `backend/src/main/java/th/co/glr/hr/catalog/importer/ImportEngine.java`
- `backend/src/main/java/th/co/glr/hr/catalog/importer/ImportProfile.java`
- `backend/src/main/java/th/co/glr/hr/catalog/importer/ImportResult.java`
- `backend/src/main/java/th/co/glr/hr/catalog/importer/PriceImportController.java`
- `backend/src/main/java/th/co/glr/hr/catalog/importer/PriceImportService.java`
- `backend/src/main/java/th/co/glr/hr/catalog/importer/PriceRow.java`
### `th.co.glr.hr.brand` — [brand/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/brand)
- `backend/src/main/java/th/co/glr/hr/brand/BrandAssets.java`
- `backend/src/main/java/th/co/glr/hr/brand/BrandController.java`

## Frontend
### `features/catalog` — [catalog/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/catalog)
- `frontend/src/features/catalog/CatalogSearchPage.jsx`
- `frontend/src/features/catalog/PriceImportPage.jsx`
- `frontend/src/features/catalog/ProductFormModal.jsx`

## DB
- Schemas: `price_catalog`
- Key migrations: `V153 active-version filter`; `V171 import provenance`; `V172 size norm`; `V194 priceable basis`

## Related
[[pricing-flow|Pricing Flow]] · [[quotation|Quotation]]

## Gotchas
- Catalogue reads filter price_list_versions.status = 'ACTIVE' (V153); non-active rows are invisible to pricing.
- price_catalog.product_prices is the price list; sales.catalog is a SEPARATE brand reference — different ids.
- product_prices.price_per_sqm is a GENERATED column; ~41% of real rows have NULL thickness_mm.

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
