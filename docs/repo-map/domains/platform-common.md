---
title: Platform / Common
layer: Platform
schemas: [hr]
tags: [repo-map, domain/platform-common]
synced: develop@fe5138dd
---
# Platform / Common
> Shared infra: common utils, file attachments, and locations.

**Layer:** Platform  ·  **DB schemas:** `hr`

## Backend
### `th.co.glr.hr.common` — [common/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/common)
- `backend/src/main/java/th/co/glr/hr/common/ApiException.java`
- `backend/src/main/java/th/co/glr/hr/common/ApiExceptionHandler.java`
- `backend/src/main/java/th/co/glr/hr/common/ArGlrSequence.java`
- `backend/src/main/java/th/co/glr/hr/common/ChromiumPdfPrinter.java`
- `backend/src/main/java/th/co/glr/hr/common/ImageDecodability.java`
- `backend/src/main/java/th/co/glr/hr/common/LibreOfficePdfConverter.java`
- `backend/src/main/java/th/co/glr/hr/common/Page.java`
- `backend/src/main/java/th/co/glr/hr/common/PageRequest.java`
- `backend/src/main/java/th/co/glr/hr/common/PdfDocumentWriter.java`
- `backend/src/main/java/th/co/glr/hr/common/ThaiText.java`
- `backend/src/main/java/th/co/glr/hr/common/sheet/FontResolver.java`
- `backend/src/main/java/th/co/glr/hr/common/sheet/LibreOfficeMetrics.java`
- `backend/src/main/java/th/co/glr/hr/common/sheet/SheetHtmlRenderer.java`
- `backend/src/main/java/th/co/glr/hr/common/sheet/SheetPlan.java`
### `th.co.glr.hr.attachment` — [attachment/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/attachment)
- `backend/src/main/java/th/co/glr/hr/attachment/AttachmentController.java`
- `backend/src/main/java/th/co/glr/hr/attachment/AttachmentDto.java`
- `backend/src/main/java/th/co/glr/hr/attachment/AttachmentRepository.java`
- `backend/src/main/java/th/co/glr/hr/attachment/FileAttachmentBlobRepository.java`
- `backend/src/main/java/th/co/glr/hr/attachment/FileAttachmentDiskBackfillRunner.java`
- `backend/src/main/java/th/co/glr/hr/attachment/FileStorageService.java`
### `th.co.glr.hr.location` — [location/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/location)
- `backend/src/main/java/th/co/glr/hr/location/ThaiAddress.java`
- `backend/src/main/java/th/co/glr/hr/location/ThaiLocationController.java`
- `backend/src/main/java/th/co/glr/hr/location/ThaiLocationRepository.java`

## Frontend
### `features/locations` — [locations/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/locations)
- `frontend/src/features/locations/ThaiAddressFields.jsx`
- `frontend/src/features/locations/thaiAddress.js`

## DB
- Schemas: `hr`

## Related
[[auth-session|Auth & Session]]

## Gotchas
- common/ holds ApiException, PdfDocumentWriter, ThaiText, ArGlrSequence — reused everywhere.

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
