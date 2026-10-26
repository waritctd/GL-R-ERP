---
title: Sales Deal & Ticket
layer: Sales / CRM
schemas: [sales]
tags: [repo-map, domain/sales-deal-ticket]
synced: develop@fe5138dd
---
# Sales Deal & Ticket
> The deal aggregate (1 ticket = 1 deal) and its sales-stage pipeline / actions.

**Layer:** Sales / CRM  ·  **DB schemas:** `sales`

## Backend
### `th.co.glr.hr.ticket` — [ticket/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/ticket)
- `backend/src/main/java/th/co/glr/hr/ticket/AttachType.java`
- `backend/src/main/java/th/co/glr/hr/ticket/BillingRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/ChangeCustomerRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/CommentRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/CompleteDeliveryRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/CreateTicketRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/DealActivityDto.java`
- `backend/src/main/java/th/co/glr/hr/ticket/DealActivityKind.java`
- `backend/src/main/java/th/co/glr/hr/ticket/DealActivityRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/DealCancelReason.java`
- `backend/src/main/java/th/co/glr/hr/ticket/DealLifecycle.java`
- `backend/src/main/java/th/co/glr/hr/ticket/DealLostReason.java`
- `backend/src/main/java/th/co/glr/hr/ticket/DealRoute.java`
- `backend/src/main/java/th/co/glr/hr/ticket/DealStage.java`
- `backend/src/main/java/th/co/glr/hr/ticket/DealStageMetaController.java`
- `backend/src/main/java/th/co/glr/hr/ticket/DeliveryRecordDto.java`
- `backend/src/main/java/th/co/glr/hr/ticket/DeliveryRecordItemDto.java`
- `backend/src/main/java/th/co/glr/hr/ticket/DepositPolicy.java`
- `backend/src/main/java/th/co/glr/hr/ticket/DirectQuotationLocks.java`
- `backend/src/main/java/th/co/glr/hr/ticket/EditItemsRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/EntryChannel.java`
- `backend/src/main/java/th/co/glr/hr/ticket/FulfilmentStatus.java`
- `backend/src/main/java/th/co/glr/hr/ticket/GenerateQuotationRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/ItemWeightMultiplierRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/LiveDirectQuotationDto.java`
- `backend/src/main/java/th/co/glr/hr/ticket/MoneyMilestone.java`
- `backend/src/main/java/th/co/glr/hr/ticket/OverridePriceRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/PaymentReceiptDto.java`
- `backend/src/main/java/th/co/glr/hr/ticket/PaymentStage.java`
- `backend/src/main/java/th/co/glr/hr/ticket/PaymentTrack.java`
- `backend/src/main/java/th/co/glr/hr/ticket/Priority.java`
- `backend/src/main/java/th/co/glr/hr/ticket/ProposePriceRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/QuotationDto.java`
- `backend/src/main/java/th/co/glr/hr/ticket/QuotationNumbering.java`
- `backend/src/main/java/th/co/glr/hr/ticket/QuotationRecipient.java`
- `backend/src/main/java/th/co/glr/hr/ticket/QuotationRenderModel.java`
- `backend/src/main/java/th/co/glr/hr/ticket/QuotationRenderer.java`
- `backend/src/main/java/th/co/glr/hr/ticket/QuotationStatus.java`
- `backend/src/main/java/th/co/glr/hr/ticket/RecordDeliveryRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/RecordPaymentRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/RejectRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/RelatedDocumentType.java`
- `backend/src/main/java/th/co/glr/hr/ticket/StockReservationRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/TenderRequirement.java`
- `backend/src/main/java/th/co/glr/hr/ticket/TicketAccessPolicy.java`
- `backend/src/main/java/th/co/glr/hr/ticket/TicketController.java`
- `backend/src/main/java/th/co/glr/hr/ticket/TicketDto.java`
- `backend/src/main/java/th/co/glr/hr/ticket/TicketEventDto.java`
- `backend/src/main/java/th/co/glr/hr/ticket/TicketEventKind.java`
- `backend/src/main/java/th/co/glr/hr/ticket/TicketItemDto.java`
- `backend/src/main/java/th/co/glr/hr/ticket/TicketItemRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/TicketRepository.java`
- `backend/src/main/java/th/co/glr/hr/ticket/TicketResponses.java`
- `backend/src/main/java/th/co/glr/hr/ticket/TicketService.java`
- `backend/src/main/java/th/co/glr/hr/ticket/TicketStatus.java`
- `backend/src/main/java/th/co/glr/hr/ticket/TicketSummaryDto.java`
- `backend/src/main/java/th/co/glr/hr/ticket/TrackingUpdateRequest.java`
- `backend/src/main/java/th/co/glr/hr/ticket/WinProbabilityDefaults.java`
### `th.co.glr.hr.orderconfirmation` — [orderconfirmation/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/orderconfirmation)
- `backend/src/main/java/th/co/glr/hr/orderconfirmation/OrderConfirmationController.java`
- `backend/src/main/java/th/co/glr/hr/orderconfirmation/OrderConfirmationDtos.java`
- `backend/src/main/java/th/co/glr/hr/orderconfirmation/OrderConfirmationRequests.java`
- `backend/src/main/java/th/co/glr/hr/orderconfirmation/OrderConfirmationService.java`
### `th.co.glr.hr.approval` — [approval/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/approval)
- `backend/src/main/java/th/co/glr/hr/approval/PendingApproverSql.java`

## Frontend
### `features/tickets` — [tickets/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/tickets)
- `frontend/src/features/tickets/CancelDealModal.jsx`
- `frontend/src/features/tickets/DealAttachmentsPanel.jsx`
- `frontend/src/features/tickets/DealDepositPanel.jsx`
- `frontend/src/features/tickets/DealDocumentPipeline.jsx`
- `frontend/src/features/tickets/DealDocumentRegister.jsx`
- `frontend/src/features/tickets/DealFulfilmentPanel.jsx`
- `frontend/src/features/tickets/DealHistoryPanel.jsx`
- `frontend/src/features/tickets/DealLegacyQuotations.jsx`
- `frontend/src/features/tickets/DealMetaFields.jsx`
- `frontend/src/features/tickets/DealMoneyStatusStrip.jsx`
- `frontend/src/features/tickets/DealMoneyTimeline.jsx`
- `frontend/src/features/tickets/DealQuotationPanel.jsx`
- `frontend/src/features/tickets/DealRemainingInvoiceCard.jsx`
- `frontend/src/features/tickets/DealStagePanel.jsx`
- `frontend/src/features/tickets/DealStageStepper.jsx`
- `frontend/src/features/tickets/DealStateHeader.jsx`
- `frontend/src/features/tickets/DealTrackingPanel.jsx`
- `frontend/src/features/tickets/EntryChannelFix.jsx`
- `frontend/src/features/tickets/ImportRequestFactoryCard.jsx`
- `frontend/src/features/tickets/MarkLostModal.jsx`
- `frontend/src/features/tickets/RemainingInvoiceDialog.jsx`
- `frontend/src/features/tickets/TicketCreateModal.jsx`
- `frontend/src/features/tickets/TicketDetailPage.jsx`
- `frontend/src/features/tickets/TicketDetailRoute.jsx`
- `frontend/src/features/tickets/TicketListPage.jsx`
- `frontend/src/features/tickets/UpdateStageModal.jsx`
- `frontend/src/features/tickets/accountActions.js`
- `frontend/src/features/tickets/catalogPriceDisplay.js`
- `frontend/src/features/tickets/dealDocumentVersions.js`
- `frontend/src/features/tickets/dealDrafts.js`
- `frontend/src/features/tickets/dealMoneyStatus.js`
- `frontend/src/features/tickets/dealTrackingMeta.js`
- `frontend/src/features/tickets/importActions.js`
- `frontend/src/features/tickets/remainingInvoiceReadiness.js`
- `frontend/src/features/tickets/salesActions.js`
- `frontend/src/features/tickets/salesViewScope.js`
- `frontend/src/features/tickets/stageCatalog.js`
- `frontend/src/features/tickets/stageMeta.js`
- `frontend/src/features/tickets/ticketDetailTabs.js`
- `frontend/src/features/tickets/ticketItemFields.jsx`
- `frontend/src/features/tickets/workState.js`
### `features/sales` — [sales/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/sales)
- `frontend/src/features/sales/SalesTabs.jsx`

## DB
- Schemas: `sales`
- Key migrations: `V59 pricing_request_item`; `V83 deal tracking + activity`

## Related
[[pricing-flow|Pricing Flow]] · [[quotation|Quotation]] · [[customer|Customer]] · [[deposit-billing-finance|Deposit · Billing · Finance]] · [[import-fulfilment|Import / Fulfilment]]

## Gotchas
- 15-stage pipeline (DealStage); S12-S17 = per-factory procurement, S18/S19 = delivery.
- salesActions.js is the single source of which action shows at each stage.
- ticket.status vs paymentStatus vs fulfillmentStatus are THREE independent axes.

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
