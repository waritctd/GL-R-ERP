> ⚠️ **Snapshot, not live documentation.** Generated against `origin/main` @ `824f3270` (2026-08-12).
> As of 2026-08-18 main is **269 commits** further on, and this file is known to be stale in at least
> these places: `discountCeilingPct` was removed entirely (#821-era cleanup), `POST /pricing-decisions/{id}/recalculate`
> was deleted, `minimumSellingPrice` is now auto-populated at approval rather than typed, and the
> selling-price formula was rewired onto the CEO's V109 config (V152). Treat this as a map of the chain's
> *shape*, not as an authoritative field reference. See `docs/tools/` for the generator.

<!-- GENERATED from origin/main @ 824f3270 by docs/tools/gen-sales-pipeline-map.py.
     Regenerate rather than hand-editing. -->

# Sales Pipeline — Complete DTO Reference

Companion to [`sales-pipeline-forensics.md`](sales-pipeline-forensics.md) — that document maps how
the services integrate; this one is the field-level lookup.

Both are combined and searchable as one page: <https://claude.ai/code/artifact/39fdd144-f18f-4f36-a337-c7661ac2bd71>

**191 records / 1366 fields**, extracted from `origin/main` @ `824f3270`.
Types are shown as declared; `java.math.` / `java.time.` prefixes stripped.

| Package | Records |
|---|---:|
| `ticket` | 48 |
| `pricingrequest` | 15 |
| `factoryquote` | 12 |
| `pricingcosting` | 10 |
| `pricingdecision` | 14 |
| `customerquotation` | 14 |
| `orderconfirmation` | 3 |
| `deposit` | 8 |
| `procurement` | 9 |
| `commission` | 21 |
| `customer` | 6 |
| `pricing` | 19 |
| `catalog` | 10 |
| `factory` | 2 |
| **Total** | **191** |

---

## 1 · TicketService — the Deal

### Response DTOs — returned to the client

#### `DealActivityDto` — 8 fields
<sub>`ticket/DealActivityDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `ticketId` | `long` | — |
| `activityDate` | `LocalDate` | — |
| `kind` | `String` | — |
| `note` | `String` | — |
| `createdById` | `long` | — |
| `createdByName` | `String` | — |
| `createdAt` | `Instant` | — |

#### `DeliveryRecordDto` — 10 fields
<sub>`ticket/DeliveryRecordDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `deliveryId` | `long` | — |
| `ticketId` | `long` | — |
| `source` | `String` | — |
| `deliveredAt` | `Instant` | — |
| `deliveredById` | `long` | — |
| `deliveredByName` | `String` | — |
| `note` | `String` | — |
| `recipientName` | `String` | — |
| `createdAt` | `Instant` | — |
| `items` | `List<DeliveryRecordItemDto>` | — |

#### `DeliveryRecordItemDto` — 3 fields
<sub>`ticket/DeliveryRecordItemDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `deliveryItemId` | `long` | — |
| `itemId` | `long` | — |
| `qty` | `BigDecimal` | — |

#### `PaymentReceiptDto` — 12 fields
<sub>`ticket/PaymentReceiptDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `receiptId` | `long` | — |
| `ticketId` | `long` | — |
| `kind` | `String` | — |
| `amount` | `BigDecimal` | — |
| `currency` | `String` | — |
| `receivedAt` | `Instant` | — |
| `recordedById` | `long` | — |
| `recordedByName` | `String` | — |
| `note` | `String` | — |
| `depositNoticeId` | `Long` | — |
| `receiptRef` | `String` | — |
| `createdAt` | `Instant` | — |

#### `QuotationDto` — 24 fields
<sub>`ticket/QuotationDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `ticketId` | `long` | — |
| `number` | `String` | — |
| `issuedById` | `long` | — |
| `issuedByName` | `String` | — |
| `issuedAt` | `Instant` | — |
| `pdfPath` | `String` | — |
| `totalAmount` | `BigDecimal` | — |
| `currency` | `String` | — |
| `quotationVersion` | `int` | — |
| `docStatus` | `String` | — |
| `recipientType` | `String` | — |
| `recipientLabel` | `String` | — |
| `paymentTerms` | `String` | — |
| `leadTime` | `String` | — |
| `deliveryTerms` | `String` | — |
| `validityDate` | `LocalDate` | — |
| `sentAt` | `Instant` | — |
| `acceptedAt` | `Instant` | — |
| `rejectedAt` | `Instant` | — |
| `parentQuotationId` | `Long` | — |
| `offerDate` | `LocalDate` | — |
| `depositPercent` | `Integer` | — |
| `deliveryLeadDays` | `Integer` | — |

#### `Line` — 2 fields
<sub>`ticket/RecordDeliveryRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `itemId` | `Long` | `@NotNull` |
| `qty` | `BigDecimal` | `@NotNull @DecimalMin(value = "0.00", inclusive = false)` |

#### `Line` — 3 fields
<sub>`ticket/StockReservationRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `itemId` | `Long` | `@NotNull` |
| `qtyFromStock` | `BigDecimal` | `@NotNull @DecimalMin(value = "0.00")` |
| `note` | `String` | `@Size(max = 2000)` |

#### `TicketDto` — 5 fields
<sub>`ticket/TicketDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `summary` | `TicketSummaryDto` | — |
| `items` | `List<TicketItemDto>` | — |
| `events` | `List<TicketEventDto>` | — |
| `quotation` | `QuotationDto` | — |
| `quotations` | `List<QuotationDto>` | — |

#### `TicketEventDto` — 10 fields
<sub>`ticket/TicketEventDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `ticketId` | `long` | — |
| `actorId` | `long` | — |
| `actorName` | `String` | — |
| `kind` | `String` | — |
| `fromStatus` | `String` | — |
| `toStatus` | `String` | — |
| `message` | `String` | — |
| `createdAt` | `Instant` | — |
| `itemSnapshot` | `String` | — |

#### `TicketItemDto` — 33 fields
<sub>`ticket/TicketItemDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `ticketId` | `long` | — |
| `brand` | `String` | — |
| `model` | `String` | — |
| `color` | `String` | — |
| `texture` | `String` | — |
| `size` | `String` | — |
| `factory` | `String` | — |
| `qty` | `BigDecimal` | — |
| `qtySqm` | `BigDecimal` | — |
| `rawPrice` | `BigDecimal` | — |
| `rawCurrency` | `String` | — |
| `rawUnit` | `String` | — |
| `proposedPrice` | `BigDecimal` | — |
| `approvedPrice` | `BigDecimal` | — |
| `currency` | `String` | — |
| `sortOrder` | `int` | — |
| `calcedCost` | `BigDecimal` | — |
| `calcedPrice` | `BigDecimal` | — |
| `calcConfigVersion` | `Integer` | — |
| `unitBasis` | `String` | — |
| `manualPrice` | `BigDecimal` | — |
| `manualOverrideReason` | `String` | — |
| `qtyDelivered` | `BigDecimal` | — |
| `qtyFromStock` | `BigDecimal` | — |
| `stockNote` | `String` | — |
| `catalogPriceId` | `Long` | — |
| `catalogProductCode` | `String` | — |
| `source` | `String` | — |
| `catalogPrice` | `BigDecimal` | — |
| `catalogCurrency` | `String` | — |
| `catalogPriceUnit` | `String` | — |
| `sqmPerPiece` | `BigDecimal` | — |

#### `TicketActionState` — 5 fields
<sub>`ticket/TicketResponses.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `lifecycle` | `String` | — |
| `salesStage` | `String` | — |
| `paymentStatus` | `String` | — |
| `fulfillmentStatus` | `String` | — |
| `status` | `String` | — |

#### `TicketActionDto` — 5 fields
<sub>`ticket/TicketResponses.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `action` | `String` | — |
| `kind` | `String` | — |
| `label` | `String` | — |
| `targetStage` | `String` | — |
| `requiredFields` | `List<String>` | — |

#### `TicketSummaryDto` — 53 fields
<sub>`ticket/TicketSummaryDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `code` | `String` | — |
| `type` | `String` | — |
| `title` | `String` | — |
| `status` | `String` | — |
| `priority` | `String` | — |
| `createdById` | `long` | — |
| `createdByName` | `String` | — |
| `assignedToId` | `Long` | — |
| `assignedToName` | `String` | — |
| `customerName` | `String` | — |
| `customerId` | `Long` | — |
| `projectId` | `Long` | — |
| `projectName` | `String` | — |
| `contactId` | `Long` | — |
| `contactName` | `String` | — |
| `note` | `String` | — |
| `createdAt` | `Instant` | — |
| `updatedAt` | `Instant` | — |
| `closedAt` | `Instant` | — |
| `itemCount` | `int` | — |
| `hasEdits` | `boolean` | — |
| `paymentStatus` | `String` | — |
| `fulfillmentStatus` | `String` | — |
| `salesStage` | `String` | — |
| `lostReason` | `String` | — |
| `lostAt` | `Instant` | — |
| `stageUpdatedAt` | `Instant` | — |
| `lifecycle` | `String` | — |
| `tenderRequirement` | `String` | — |
| `depositPolicy` | `String` | — |
| `depositPolicyReason` | `String` | — |
| `entryChannel` | `String` | — |
| `billingDate` | `LocalDate` | — |
| `dueDate` | `LocalDate` | — |
| `creditTermDays` | `Integer` | — |
| `lastFollowUpAt` | `LocalDate` | — |
| `nextFollowUpAt` | `LocalDate` | — |
| `paymentStage` | `String` | — |
| `amountPayable` | `BigDecimal` | — |
| `amountPaid` | `BigDecimal` | — |
| `amountOutstanding` | `BigDecimal` | — |
| `overdue` | `boolean` | — |
| `closeConfirmedAt` | `Instant` | — |
| `closeConfirmedByName` | `String` | — |
| `invoiceOnFile` | `boolean` | — |
| `cancelReason` | `String` | — |
| `cancelledAt` | `Instant` | — |
| `winProbabilityOverride` | `Integer` | — |
| `designerName` | `String` | — |
| `ownerName` | `String` | — |
| `buyerName` | `String` | — |
| `stale` | `boolean` | — |

### Request DTOs — accepted from the client

#### `BillingRequest` — 5 fields
<sub>`ticket/BillingRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `billingDate` | `LocalDate` | — |
| `dueDate` | `LocalDate` | — |
| `creditTermDays` | `Integer` | — |
| `lastFollowUpAt` | `LocalDate` | — |
| `nextFollowUpAt` | `LocalDate` | — |

#### `CommentRequest` — 1 fields
<sub>`ticket/CommentRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `message` | `String` | `@NotBlank` |

#### `CompleteDeliveryRequest` — 2 fields
<sub>`ticket/CompleteDeliveryRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `note` | `String` | `@Size(max = 2000)` |
| `recipientName` | `String` | `@Size(max = 255)` |

#### `CreateTicketRequest` — 9 fields
<sub>`ticket/CreateTicketRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `title` | `String` | `@NotBlank` |
| `priority` | `String` | — |
| `customerName` | `String` | — |
| `customerId` | `Long` | — |
| `projectId` | `Long` | `@NotNull` |
| `contactId` | `Long` | — |
| `note` | `String` | — |
| `entryChannel` | `String` | — |
| `items` | `List< TicketItemRequest>` | `@Valid` |

#### `DealActivityRequest` — 3 fields
<sub>`ticket/DealActivityRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `activityDate` | `LocalDate` | `@NotNull` |
| `kind` | `String` | `@NotBlank` |
| `note` | `String` | `@Size(max = 2000)` |

#### `EditItemsRequest` — 2 fields
<sub>`ticket/EditItemsRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `items` | `List< TicketItemRequest>` | `@NotEmpty @Valid` |
| `note` | `String` | — |

#### `GenerateQuotationRequest` — 10 fields
<sub>`ticket/GenerateQuotationRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `recipientType` | `String` | `@NotBlank` |
| `recipientLabel` | `String` | `@Size(max = 255)` |
| `paymentTerms` | `String` | `@Size(max = 2000)` |
| `leadTime` | `String` | `@Size(max = 2000)` |
| `deliveryTerms` | `String` | `@Size(max = 2000)` |
| `validityDate` | `LocalDate` | — |
| `amendmentReason` | `String` | `@Size(max = 2000)` |
| `offerDate` | `LocalDate` | — |
| `depositPercent` | `Integer` | `@Min(1) @Max(100)` |
| `deliveryLeadDays` | `Integer` | `@Min(1) @Max(3650)` |

#### `OverridePriceRequest` — 2 fields
<sub>`ticket/OverridePriceRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `manualPrice` | `BigDecimal` | `@NotNull @Positive` |
| `reason` | `String` | — |

#### `ProposePriceRequest` — 2 fields
<sub>`ticket/ProposePriceRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `items` | `List< TicketItemRequest>` | `@NotEmpty @Valid` |
| `note` | `String` | — |

#### `RecordDeliveryRequest` — 4 fields
<sub>`ticket/RecordDeliveryRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `source` | `String` | `@NotBlank` |
| `note` | `String` | `@Size(max = 2000)` |
| `lines` | `List<Line>` | `@NotEmpty @Valid` |
| `recipientName` | `String` | `@Size(max = 255)` |

#### `RecordPaymentRequest` — 7 fields
<sub>`ticket/RecordPaymentRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `kind` | `String` | `@NotBlank` |
| `amount` | `BigDecimal` | `@DecimalMin(value = "0.00", inclusive = false)` |
| `receivedAt` | `Instant` | — |
| `note` | `String` | `@Size(max = 2000)` |
| `depositNoticeId` | `Long` | — |
| `receiptRef` | `String` | `@Size(max = 60)` |
| `allowOverpayment` | `Boolean` | — |

#### `RejectRequest` — 1 fields
<sub>`ticket/RejectRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `reason` | `String` | `@NotBlank` |

#### `SendFactoryEmailRequest` — 4 fields
<sub>`ticket/SendFactoryEmailRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `factory` | `String` | `@NotBlank` |
| `to` | `String` | `@NotBlank @Email` |
| `subject` | `String` | `@NotBlank` |
| `body` | `String` | `@NotBlank` |

#### `StockReservationRequest` — 1 fields
<sub>`ticket/StockReservationRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `lines` | `List<Line>` | `@NotEmpty @Valid` |

#### `UpdateStageRequest` — 2 fields
<sub>`ticket/TicketController.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `stage` | `String` | `@NotBlank` |
| `note` | `String` | `@Size(max = 2000)` |

#### `MarkLostRequest` — 2 fields
<sub>`ticket/TicketController.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `reason` | `String` | `@NotBlank` |
| `note` | `String` | `@Size(max = 2000)` |

#### `CancelRequest` — 2 fields
<sub>`ticket/TicketController.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `reason` | `String` | `@NotBlank` |
| `note` | `String` | `@Size(max = 2000)` |

#### `ReopenRequest` — 1 fields
<sub>`ticket/TicketController.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `note` | `String` | `@Size(max = 2000)` |

#### `NoteRequest` — 1 fields
<sub>`ticket/TicketController.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `note` | `String` | `@Size(max = 2000)` |

#### `PolicyValueRequest` — 1 fields
<sub>`ticket/TicketController.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `value` | `String` | `@NotBlank` |

#### `EntryChannelRequest` — 2 fields
<sub>`ticket/TicketController.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `value` | `String` | `@NotBlank` |
| `note` | `String` | `@Size(max = 2000)` |

#### `DepositPolicyRequest` — 2 fields
<sub>`ticket/TicketController.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `policy` | `String` | `@NotBlank` |
| `reason` | `String` | `@NotBlank @Size(max = 2000)` |

#### `TicketItemRequest` — 16 fields
<sub>`ticket/TicketItemRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `brand` | `String` | `@NotBlank` |
| `model` | `String` | `@NotBlank` |
| `color` | `String` | — |
| `texture` | `String` | — |
| `size` | `String` | `@NotBlank` |
| `factory` | `String` | — |
| `qty` | `BigDecimal` | — |
| `qtySqm` | `BigDecimal` | — |
| `unitBasis` | `String` | — |
| `rawPrice` | `BigDecimal` | — |
| `rawCurrency` | `String` | — |
| `rawUnit` | `String` | — |
| `proposedPrice` | `BigDecimal` | — |
| `currency` | `String` | — |
| `catalogPriceId` | `Long` | — |
| `catalogProductCode` | `String` | — |

#### `TrackingUpdateRequest` — 5 fields
<sub>`ticket/TrackingUpdateRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `winProbability` | `Integer` | `@Min(0) @Max(100)` |
| `designerName` | `String` | `@Size(max = 200)` |
| `ownerName` | `String` | `@Size(max = 200)` |
| `buyerName` | `String` | `@Size(max = 200)` |
| `nextFollowUpAt` | `LocalDate` | — |

### Response envelopes

#### `TicketListResponse` — 4 fields
<sub>`ticket/TicketResponses.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `tickets` | `List<TicketSummaryDto>` | — |
| `page` | `int` | — |
| `size` | `int` | — |
| `total` | `int` | — |

#### `TicketDetailResponse` — 1 fields
<sub>`ticket/TicketResponses.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `ticket` | `TicketDto` | — |

#### `QuotationResponse` — 1 fields
<sub>`ticket/TicketResponses.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `quotation` | `QuotationDto` | — |

#### `CalculatePricesResponse` — 2 fields
<sub>`ticket/TicketResponses.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `ticket` | `TicketDto` | — |
| `breakdown` | `List<PriceBreakdownItemDto>` | — |

#### `TicketActionsResponse` — 2 fields
<sub>`ticket/TicketResponses.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `currentState` | `TicketActionState` | — |
| `availableActions` | `List<TicketActionDto>` | — |

### Internal records — never cross the API boundary

#### `CellRec` — 6 fields
<sub>`ticket/QuotationRenderer.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `rowOff` | `int` | — |
| `col` | `int` | — |
| `style` | `CellStyle` | — |
| `type` | `CellType` | — |
| `s` | `String` | — |
| `d` | `double` | — |

#### `QuotationHeaderSnapshot` — 5 fields
<sub>`ticket/TicketRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `customerName` | `String` | — |
| `customerAddress` | `String` | — |
| `customerTaxId` | `String` | — |
| `customerPhone` | `String` | — |
| `projectName` | `String` | — |

#### `DepositNoticePaymentInfo` — 3 fields
<sub>`ticket/TicketRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `depositAmount` | `BigDecimal` | — |
| `totalPayable` | `BigDecimal` | — |

#### `ItemSnap` — 6 fields
<sub>`ticket/TicketService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `brand` | `String` | — |
| `model` | `String` | — |
| `qty` | `BigDecimal` | — |
| `rawPrice` | `BigDecimal` | — |
| `rawCurrency` | `String` | — |
| `rawUnit` | `String` | — |

#### `QuotationRenderContext` — 3 fields
<sub>`ticket/TicketService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `ticket` | `TicketDto` | — |
| `quotation` | `QuotationDto` | — |
| `customer` | `CustomerDto` | — |

#### `CalculatePricesResult` — 2 fields
<sub>`ticket/TicketService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `ticket` | `TicketDto` | — |
| `breakdown` | `List<PriceBreakdownItemDto>` | — |


---

## 2 · PricingRequestService

### Response DTOs — returned to the client

#### `PricingRequestSummaryDto` — 28 fields
<sub>`pricingrequest/PricingRequestDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `requestCode` | `String` | — |
| `ticketId` | `long` | — |
| `ticketCode` | `String` | — |
| `projectName` | `String` | — |
| `customerName` | `String` | — |
| `ticketCreatedById` | `long` | — |
| `recipientType` | `String` | — |
| `recipientContactId` | `Long` | — |
| `recipientLabel` | `String` | — |
| `status` | `String` | — |
| `requestedById` | `long` | — |
| `requestedByName` | `String` | — |
| `assignedImportId` | `Long` | — |
| `assignedImportName` | `String` | — |
| `requiredDate` | `LocalDate` | — |
| `customerTargetPrice` | `BigDecimal` | — |
| `targetCurrency` | `String` | — |
| `note` | `String` | — |
| `itemCount` | `int` | — |
| `revisionNo` | `int` | — |
| `parentPricingRequestId` | `Long` | — |
| `submittedAt` | `Instant` | — |
| `pickedUpAt` | `Instant` | — |
| `cancelledAt` | `Instant` | — |
| `createdAt` | `Instant` | — |
| `updatedAt` | `Instant` | — |
| `orderConfirmedAt` | `Instant` | — |

#### `PricingRequestItemDto` — 32 fields
<sub>`pricingrequest/PricingRequestDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `pricingRequestId` | `long` | — |
| `sourceTicketItemId` | `Long` | — |
| `productId` | `Long` | — |
| `variantId` | `Long` | — |
| `brand` | `String` | — |
| `model` | `String` | — |
| `productDescription` | `String` | — |
| `color` | `String` | — |
| `texture` | `String` | — |
| `size` | `String` | — |
| `factory` | `String` | — |
| `requestedQty` | `BigDecimal` | — |
| `requestedQtySqm` | `BigDecimal` | — |
| `requestedUnit` | `String` | — |
| `requestedUnitBasis` | `String` | — |
| `quantityType` | `String` | — |
| `targetDeliveryDate` | `LocalDate` | — |
| `deliveryLocation` | `String` | — |
| `specialRequirement` | `String` | — |
| `sortOrder` | `int` | — |
| `priceListVersionId` | `Long` | — |
| `catalogPriceId` | `Long` | — |
| `catalogBasePrice` | `BigDecimal` | — |
| `catalogCurrency` | `String` | — |
| `catalogEffectiveDate` | `LocalDate` | — |
| `resolvedFactoryId` | `Long` | — |
| `resolvedFactoryName` | `String` | — |
| `catalogProductCode` | `String` | — |
| `catalogBrand` | `String` | — |
| `catalogCollection` | `String` | — |
| `catalogModel` | `String` | — |

#### `PricingRequestEventDto` — 11 fields
<sub>`pricingrequest/PricingRequestDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `pricingRequestId` | `long` | — |
| `ticketId` | `long` | — |
| `actorId` | `Long` | — |
| `actorName` | `String` | — |
| `eventKind` | `String` | — |
| `fromStatus` | `String` | — |
| `toStatus` | `String` | — |
| `message` | `String` | — |
| `metadata` | `String` | — |
| `createdAt` | `Instant` | — |

#### `PricingRequestDetailDto` — 3 fields
<sub>`pricingrequest/PricingRequestDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `summary` | `PricingRequestSummaryDto` | — |
| `items` | `List<PricingRequestItemDto>` | — |
| `events` | `List<PricingRequestEventDto>` | — |

#### `PricingRequestAttachmentDto` — 8 fields
<sub>`pricingrequest/PricingRequestDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `pricingRequestId` | `long` | — |
| `fileName` | `String` | — |
| `mimeType` | `String` | — |
| `fileSize` | `Long` | — |
| `includeInFactoryEmail` | `boolean` | — |
| `uploadedBy` | `long` | — |
| `uploadedAt` | `Instant` | — |

### Request DTOs — accepted from the client

#### `CreatePricingRequestRequest` — 9 fields
<sub>`pricingrequest/PricingRequestRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `recipientType` | `String` | `@NotBlank` |
| `recipientContactId` | `Long` | — |
| `recipientLabel` | `String` | — |
| `requiredDate` | `LocalDate` | — |
| `customerTargetPrice` | `BigDecimal` | `@DecimalMin("0.00")` |
| `targetCurrency` | `String` | — |
| `note` | `String` | — |
| `clientRequestId` | `String` | — |
| `items` | `List< PricingRequestItemRequest>` | `@NotEmpty @Valid` |

#### `UpdatePricingRequestRequest` — 8 fields
<sub>`pricingrequest/PricingRequestRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `recipientType` | `String` | — |
| `recipientContactId` | `Long` | — |
| `recipientLabel` | `String` | — |
| `requiredDate` | `LocalDate` | — |
| `customerTargetPrice` | `BigDecimal` | `@DecimalMin("0.00")` |
| `targetCurrency` | `String` | — |
| `note` | `String` | — |
| `items` | `List< PricingRequestItemRequest>` | `@Valid` |

#### `PricingRequestItemRequest` — 18 fields
<sub>`pricingrequest/PricingRequestRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `sourceTicketItemId` | `Long` | — |
| `productId` | `Long` | — |
| `variantId` | `Long` | — |
| `brand` | `String` | — |
| `model` | `String` | — |
| `productDescription` | `String` | — |
| `color` | `String` | — |
| `texture` | `String` | — |
| `size` | `String` | — |
| `factory` | `String` | — |
| `requestedQty` | `BigDecimal` | `@NotNull @DecimalMin("0.0001")` |
| `requestedQtySqm` | `BigDecimal` | `@DecimalMin("0.0000")` |
| `requestedUnit` | `String` | `@NotBlank` |
| `requestedUnitBasis` | `String` | `@NotBlank` |
| `quantityType` | `String` | `@NotBlank` |
| `targetDeliveryDate` | `LocalDate` | — |
| `deliveryLocation` | `String` | — |
| `specialRequirement` | `String` | — |

#### `CancelPricingRequestRequest` — 1 fields
<sub>`pricingrequest/PricingRequestRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `reason` | `String` | `@NotBlank` |

#### `UpdatePricingRequestAttachmentRequest` — 1 fields
<sub>`pricingrequest/PricingRequestRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `includeInFactoryEmail` | `Boolean` | `@NotNull` |

#### `CustomerChangeRevisionRequest` — 10 fields
<sub>`pricingrequest/PricingRequestRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `revisionReason` | `String` | `@NotBlank` |
| `clientRequestId` | `String` | `@NotBlank` |
| `recipientType` | `String` | `@NotBlank` |
| `recipientContactId` | `Long` | — |
| `recipientLabel` | `String` | — |
| `requiredDate` | `LocalDate` | — |
| `customerTargetPrice` | `BigDecimal` | `@DecimalMin("0.00")` |
| `targetCurrency` | `String` | — |
| `note` | `String` | — |
| `items` | `List< PricingRequestItemRequest>` | `@NotEmpty @Valid` |

### Response envelopes

#### `PricingRequestDetailResponse` — 1 fields
<sub>`pricingrequest/PricingRequestResponses.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `pricingRequest` | `PricingRequestDetailDto` | — |

### Internal records — never cross the API boundary

#### `OrderConfirmationState` — 2 fields
<sub>`pricingrequest/PricingRequestRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `confirmed` | `boolean` | — |
| `clientRequestId` | `String` | — |

#### `PricingRequestEmailAttachmentFile` — 3 fields
<sub>`pricingrequest/PricingRequestRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `fileName` | `String` | — |
| `filePath` | `String` | — |
| `mimeType` | `String` | — |

#### `CancelOpenForTicketResult` — 2 fields
<sub>`pricingrequest/PricingRequestService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `cancelledCount` | `int` | — |
| `abandonedIds` | `List<Long>` | — |


---

## 3 · FactoryQuoteService

### Response DTOs — returned to the client

#### `FactoryQuoteDto` — 32 fields
<sub>`factoryquote/FactoryQuoteDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `quoteCode` | `String` | — |
| `pricingRequestId` | `long` | — |
| `factoryId` | `Long` | — |
| `factoryName` | `String` | — |
| `status` | `String` | — |
| `emailTo` | `String` | — |
| `emailSubject` | `String` | — |
| `emailBody` | `String` | — |
| `emailSentAt` | `Instant` | — |
| `sentBy` | `Long` | — |
| `supplierQuoteRef` | `String` | — |
| `defaultCurrency` | `String` | — |
| `paymentTerms` | `String` | — |
| `leadTimeText` | `String` | — |
| `note` | `String` | — |
| `negotiationNote` | `String` | — |
| `requestedAt` | `Instant` | — |
| `receivedAt` | `Instant` | — |
| `rootFactoryQuoteId` | `Long` | — |
| `parentFactoryQuoteId` | `Long` | — |
| `revisionNo` | `int` | — |
| `revisionReason` | `String` | — |
| `current` | `boolean` | — |
| `createdAt` | `Instant` | — |
| `updatedAt` | `Instant` | — |
| `items` | `List<FactoryQuoteItemDto>` | — |
| `attachments` | `List<FactoryQuoteAttachmentDto>` | — |
| `dispatchStatus` | `String` | — |
| `dispatchAttemptCount` | `int` | — |
| `dispatchFailureMessage` | `String` | — |
| `dispatchNextAttemptAt` | `Instant` | — |

#### `FactoryQuoteItemDto` — 19 fields
<sub>`factoryquote/FactoryQuoteDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `factoryQuoteId` | `long` | — |
| `pricingRequestItemId` | `long` | — |
| `catalogProductIdSnapshot` | `Long` | — |
| `supplierProductCode` | `String` | — |
| `supplierProductDescription` | `String` | — |
| `quotedQuantity` | `BigDecimal` | — |
| `quotedUnit` | `String` | — |
| `unitBasis` | `String` | — |
| `rawUnitPrice` | `BigDecimal` | — |
| `currency` | `String` | — |
| `minimumOrderQuantity` | `BigDecimal` | — |
| `sqmPerUnit` | `BigDecimal` | — |
| `piecesPerBox` | `BigDecimal` | — |
| `linearMPerUnit` | `BigDecimal` | — |
| `leadTimeText` | `String` | — |
| `availabilityNote` | `String` | — |
| `lineNote` | `String` | — |
| `sortOrder` | `int` | — |

#### `FactoryQuoteAttachmentDto` — 10 fields
<sub>`factoryquote/FactoryQuoteDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `factoryQuoteId` | `long` | — |
| `fileName` | `String` | — |
| `mimeType` | `String` | — |
| `fileSize` | `Long` | — |
| `uploadedBy` | `long` | — |
| `uploadedAt` | `Instant` | — |
| `deletedAt` | `Instant` | — |
| `deletedBy` | `Long` | — |
| `deleteReason` | `String` | — |

### Request DTOs — accepted from the client

#### `UpdateFactoryQuoteDraftRequest` — 4 fields
<sub>`factoryquote/FactoryQuoteRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `emailTo` | `String` | — |
| `emailSubject` | `String` | — |
| `emailBody` | `String` | — |
| `note` | `String` | — |

#### `SendFactoryQuoteRequest` — 4 fields
<sub>`factoryquote/FactoryQuoteRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `emailTo` | `String` | — |
| `emailSubject` | `String` | — |
| `emailBody` | `String` | — |
| `clientRequestId` | `String` | `@NotBlank` |

#### `ReceiveFactoryQuoteRequest` — 8 fields
<sub>`factoryquote/FactoryQuoteRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `supplierQuoteRef` | `String` | — |
| `defaultCurrency` | `String` | — |
| `paymentTerms` | `String` | — |
| `leadTimeText` | `String` | — |
| `revisionReason` | `String` | — |
| `negotiationNote` | `String` | — |
| `items` | `List< ReceiveFactoryQuoteItemRequest>` | `@NotEmpty @Valid` |
| `clientRequestId` | `String` | `@NotBlank` |

#### `ReceiveFactoryQuoteItemRequest` — 15 fields
<sub>`factoryquote/FactoryQuoteRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `pricingRequestItemId` | `Long` | `@NotNull` |
| `supplierProductCode` | `String` | — |
| `supplierProductDescription` | `String` | — |
| `quotedQuantity` | `BigDecimal` | `@NotNull @DecimalMin("0.0001")` |
| `quotedUnit` | `String` | `@NotBlank` |
| `unitBasis` | `String` | `@NotBlank` |
| `rawUnitPrice` | `BigDecimal` | `@NotNull @DecimalMin("0.0000")` |
| `currency` | `String` | `@NotBlank` |
| `minimumOrderQuantity` | `BigDecimal` | `@DecimalMin("0.0000")` |
| `sqmPerUnit` | `BigDecimal` | `@DecimalMin("0.000001")` |
| `piecesPerBox` | `BigDecimal` | `@DecimalMin("0.0000")` |
| `linearMPerUnit` | `BigDecimal` | `@DecimalMin("0.000001")` |
| `leadTimeText` | `String` | — |
| `availabilityNote` | `String` | — |
| `lineNote` | `String` | — |

#### `StartNegotiationRequest` — 1 fields
<sub>`factoryquote/FactoryQuoteRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `note` | `String` | `@NotBlank` |

#### `MarkNotAvailableRequest` — 1 fields
<sub>`factoryquote/FactoryQuoteRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `reason` | `String` | `@NotBlank` |

### Internal records — never cross the API boundary

#### `AttachmentFileLocation` — 2 fields
<sub>`factoryquote/FactoryQuoteRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `filePath` | `String` | — |
| `storageState` | `String` | — |

#### `FactoryQuoteEmailDispatchDto` — 18 fields
<sub>`factoryquote/FactoryQuoteRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `factoryQuoteId` | `long` | — |
| `clientRequestId` | `String` | — |
| `status` | `String` | — |
| `emailTo` | `String` | — |
| `emailSubject` | `String` | — |
| `emailBody` | `String` | — |
| `createdBy` | `Long` | — |
| `createdAt` | `Instant` | — |
| `sendingAt` | `Instant` | — |
| `sentAt` | `Instant` | — |
| `failedAt` | `Instant` | — |
| `failureMessage` | `String` | — |
| `attemptCount` | `int` | — |
| `nextAttemptAt` | `Instant` | — |
| `claimedAt` | `Instant` | — |
| `providerMessageId` | `String` | — |
| `finalizedAt` | `Instant` | — |

#### `FactoryQuoteResponseReceiptDto` — 5 fields
<sub>`factoryquote/FactoryQuoteRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `factoryQuoteId` | `long` | — |
| `createdBy` | `long` | — |
| `clientRequestId` | `String` | — |
| `createdAt` | `Instant` | — |


---

## 4 · PricingCostingService

### Response DTOs — returned to the client

#### `PricingCostingDto` — 16 fields
<sub>`pricingcosting/PricingCostingDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `costingCode` | `String` | — |
| `pricingRequestId` | `long` | — |
| `versionNo` | `int` | — |
| `status` | `String` | — |
| `stale` | `boolean` | — |
| `staleReason` | `String` | — |
| `note` | `String` | — |
| `createdBy` | `Long` | — |
| `createdAt` | `Instant` | — |
| `updatedAt` | `Instant` | — |
| `calculatedAt` | `Instant` | — |
| `submittedBy` | `Long` | — |
| `submittedAt` | `Instant` | — |
| `totalLandedCostThb` | `BigDecimal` | — |
| `items` | `List<PricingCostingItemDto>` | — |

#### `PricingCostingItemDto` — 37 fields
<sub>`pricingcosting/PricingCostingDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `pricingCostingId` | `long` | — |
| `pricingRequestItemId` | `long` | — |
| `factoryQuoteId` | `long` | — |
| `factoryQuoteItemId` | `long` | — |
| `factoryQuoteRevisionNo` | `int` | — |
| `factoryId` | `Long` | — |
| `factoryName` | `String` | — |
| `supplierQuoteRef` | `String` | — |
| `rawUnitPrice` | `BigDecimal` | — |
| `rawCurrency` | `String` | — |
| `rawUnit` | `String` | — |
| `unitBasis` | `String` | — |
| `requestedQuantity` | `BigDecimal` | — |
| `requestedUnit` | `String` | — |
| `requestedUnitBasis` | `String` | — |
| `normalizedQuantityPieces` | `BigDecimal` | — |
| `linearMPerUnit` | `BigDecimal` | — |
| `sqmPerUnit` | `BigDecimal` | — |
| `piecesPerBox` | `BigDecimal` | — |
| `fxRate` | `BigDecimal` | — |
| `fxSource` | `String` | — |
| `fxEffectiveDate` | `LocalDate` | — |
| `fxFetchedAt` | `Instant` | — |
| `calculationConfigId` | `long` | — |
| `calculationConfigVersion` | `int` | — |
| `goodsCostThb` | `BigDecimal` | — |
| `freightCostThb` | `BigDecimal` | — |
| `insuranceCostThb` | `BigDecimal` | — |
| `importDutyThb` | `BigDecimal` | — |
| `inlandTransportCostThb` | `BigDecimal` | — |
| `otherCostThb` | `BigDecimal` | — |
| `cifCostThb` | `BigDecimal` | — |
| `landedCostPerUnitThb` | `BigDecimal` | — |
| `totalLandedCostThb` | `BigDecimal` | — |
| `calculatedAt` | `Instant` | — |
| `calculationSnapshot` | `String` | — |

### Request DTOs — accepted from the client

#### `CreateCostingRequest` — 2 fields
<sub>`pricingcosting/PricingCostingRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `note` | `String` | — |
| `clientRequestId` | `String` | — |

#### `RecalculateCostingRequest` — 1 fields
<sub>`pricingcosting/PricingCostingRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `note` | `String` | — |

#### `SubmitCostingRequest` — 1 fields
<sub>`pricingcosting/PricingCostingRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `note` | `String` | — |

### Internal records — never cross the API boundary

#### `PricingCostingWriteItem` — 34 fields
<sub>`pricingcosting/PricingCostingRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `pricingRequestItemId` | `long` | — |
| `factoryQuoteId` | `long` | — |
| `factoryQuoteItemId` | `long` | — |
| `factoryQuoteRevisionNo` | `int` | — |
| `factoryId` | `Long` | — |
| `factoryName` | `String` | — |
| `supplierQuoteRef` | `String` | — |
| `rawUnitPrice` | `BigDecimal` | — |
| `rawCurrency` | `String` | — |
| `rawUnit` | `String` | — |
| `unitBasis` | `String` | — |
| `requestedQuantity` | `BigDecimal` | — |
| `requestedUnit` | `String` | — |
| `requestedUnitBasis` | `String` | — |
| `normalizedQuantityPieces` | `BigDecimal` | — |
| `linearMPerUnit` | `BigDecimal` | — |
| `sqmPerUnit` | `BigDecimal` | — |
| `piecesPerBox` | `BigDecimal` | — |
| `fxRate` | `BigDecimal` | — |
| `fxSource` | `String` | — |
| `fxEffectiveDate` | `LocalDate` | — |
| `fxFetchedAt` | `Instant` | — |
| `calculationConfigId` | `long` | — |
| `calculationConfigVersion` | `int` | — |
| `goodsCostThb` | `BigDecimal` | — |
| `freightCostThb` | `BigDecimal` | — |
| `insuranceCostThb` | `BigDecimal` | — |
| `importDutyThb` | `BigDecimal` | — |
| `inlandTransportCostThb` | `BigDecimal` | — |
| `otherCostThb` | `BigDecimal` | — |
| `cifCostThb` | `BigDecimal` | — |
| `landedCostPerUnitThb` | `BigDecimal` | — |
| `totalLandedCostThb` | `BigDecimal` | — |
| `calculationSnapshot` | `String` | — |

#### `CreateDraftResult` — 2 fields
<sub>`pricingcosting/PricingCostingRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `costingId` | `long` | — |
| `created` | `boolean` | — |

#### `ResolvedSource` — 3 fields
<sub>`pricingcosting/PricingCostingService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `requestItem` | `PricingRequestItemDto` | — |
| `quote` | `FactoryQuoteDto` | — |
| `quoteItem` | `FactoryQuoteItemDto` | — |

#### `FxSnapshot` — 4 fields
<sub>`pricingcosting/PricingCostingService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `rate` | `BigDecimal` | — |
| `source` | `String` | — |
| `effectiveDate` | `LocalDate` | — |
| `fetchedAt` | `Instant` | — |

#### `CalculationResult` — 2 fields
<sub>`pricingcosting/PricingCostingService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `items` | `List<PricingCostingWriteItem>` | — |
| `total` | `BigDecimal` | — |


---

## 5 · PricingDecisionService

### Response DTOs — returned to the client

#### `PricingDecisionDto` — 20 fields
<sub>`pricingdecision/PricingDecisionDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `decisionCode` | `String` | — |
| `pricingRequestId` | `long` | — |
| `pricingCostingId` | `long` | — |
| `decisionVersionNo` | `int` | — |
| `status` | `String` | — |
| `defaultMarginPct` | `BigDecimal` | — |
| `currency` | `String` | — |
| `fxRateUsed` | `BigDecimal` | — |
| `fxSource` | `String` | — |
| `fxEffectiveDate` | `LocalDate` | — |
| `ceoNote` | `String` | — |
| `returnReason` | `String` | — |
| `createdBy` | `Long` | — |
| `createdAt` | `Instant` | — |
| `updatedAt` | `Instant` | — |
| `approvedBy` | `Long` | — |
| `approvedAt` | `Instant` | — |
| `returnedAt` | `Instant` | — |
| `items` | `List<PricingDecisionItemDto>` | — |

#### `PricingDecisionItemDto` — 23 fields
<sub>`pricingdecision/PricingDecisionDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `pricingDecisionId` | `long` | — |
| `pricingRequestItemId` | `long` | — |
| `pricingCostingItemId` | `long` | — |
| `brand` | `String` | — |
| `model` | `String` | — |
| `productDescription` | `String` | — |
| `factoryName` | `String` | — |
| `requestedUnitBasis` | `String` | — |
| `requestedQuantity` | `BigDecimal` | — |
| `normalizedQuantityPieces` | `BigDecimal` | — |
| `frozenLandedCostPerPieceThb` | `BigDecimal` | — |
| `frozenLandedCostPerRequestedUnitThb` | `BigDecimal` | — |
| `currency` | `String` | — |
| `proposedMarginPct` | `BigDecimal` | — |
| `approvedMarginPct` | `BigDecimal` | — |
| `proposedSellingPricePerRequestedUnit` | `BigDecimal` | — |
| `approvedSellingPricePerRequestedUnit` | `BigDecimal` | — |
| `discountCeilingPct` | `BigDecimal` | — |
| `minimumSellingPricePerRequestedUnit` | `BigDecimal` | — |
| `decisionNote` | `String` | — |
| `createdAt` | `Instant` | — |
| `updatedAt` | `Instant` | — |

#### `PricingDecisionSalesViewDto` — 5 fields
<sub>`pricingdecision/PricingDecisionDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `pricingRequestId` | `long` | — |
| `pricingDecisionId` | `long` | — |
| `currency` | `String` | — |
| `approvedAt` | `Instant` | — |
| `items` | `List<PricingDecisionSalesItemDto>` | — |

#### `PricingDecisionSalesItemDto` — 10 fields
<sub>`pricingdecision/PricingDecisionDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `pricingRequestItemId` | `long` | — |
| `pricingDecisionItemId` | `long` | — |
| `brand` | `String` | — |
| `model` | `String` | — |
| `productDescription` | `String` | — |
| `requestedUnitBasis` | `String` | — |
| `requestedQuantity` | `BigDecimal` | — |
| `approvedSellingPricePerRequestedUnit` | `BigDecimal` | — |
| `discountCeilingPct` | `BigDecimal` | — |
| `minimumSellingPricePerRequestedUnit` | `BigDecimal` | — |

### Request DTOs — accepted from the client

#### `StartPricingDecisionRequest` — 4 fields
<sub>`pricingdecision/PricingDecisionRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `defaultMarginPct` | `BigDecimal` | — |
| `currency` | `String` | — |
| `ceoNote` | `String` | — |
| `clientRequestId` | `String` | — |

#### `UpdatePricingDecisionRequest` — 2 fields
<sub>`pricingdecision/PricingDecisionRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `ceoNote` | `String` | — |
| `items` | `List<UpdatePricingDecisionItemRequest>` | — |

#### `UpdatePricingDecisionItemRequest` — 5 fields
<sub>`pricingdecision/PricingDecisionRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `pricingDecisionItemId` | `long` | — |
| `marginPct` | `BigDecimal` | — |
| `discountCeilingPct` | `BigDecimal` | — |
| `minimumSellingPrice` | `BigDecimal` | — |
| `decisionNote` | `String` | — |

#### `RecalculatePricingDecisionRequest` — 1 fields
<sub>`pricingdecision/PricingDecisionRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `defaultMarginPct` | `BigDecimal` | — |

#### `ApprovePricingDecisionRequest` — 2 fields
<sub>`pricingdecision/PricingDecisionRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `ceoNote` | `String` | — |
| `clientRequestId` | `String` | — |

#### `ReturnPricingDecisionRequest` — 1 fields
<sub>`pricingdecision/PricingDecisionRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `returnReason` | `String` | — |

### Internal records — never cross the API boundary

#### `CreateDecisionResult` — 2 fields
<sub>`pricingdecision/PricingDecisionRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `decisionId` | `long` | — |
| `created` | `boolean` | — |

#### `WriteItem` — 10 fields
<sub>`pricingdecision/PricingDecisionRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `pricingRequestItemId` | `long` | — |
| `pricingCostingItemId` | `long` | — |
| `requestedUnitBasis` | `String` | — |
| `requestedQuantity` | `BigDecimal` | — |
| `normalizedQuantityPieces` | `BigDecimal` | — |
| `frozenLandedCostPerPieceThb` | `BigDecimal` | — |
| `frozenLandedCostPerRequestedUnitThb` | `BigDecimal` | — |
| `currency` | `String` | — |
| `proposedMarginPct` | `BigDecimal` | — |
| `proposedSellingPrice` | `BigDecimal` | — |

#### `ItemUpdate` — 6 fields
<sub>`pricingdecision/PricingDecisionRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `itemId` | `long` | — |
| `marginPct` | `BigDecimal` | — |
| `sellingPrice` | `BigDecimal` | — |
| `discountCeilingPct` | `BigDecimal` | — |
| `minimumSellingPrice` | `BigDecimal` | — |
| `decisionNote` | `String` | — |

#### `ApprovedItem` — 3 fields
<sub>`pricingdecision/PricingDecisionRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `itemId` | `long` | — |
| `approvedMarginPct` | `BigDecimal` | — |
| `approvedSellingPrice` | `BigDecimal` | — |


---

## 6 · CustomerQuotationService

### Response DTOs — returned to the client

#### `CustomerQuotationDto` — 30 fields
<sub>`customerquotation/CustomerQuotationDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `number` | `String` | — |
| `ticketId` | `long` | — |
| `pricingRequestId` | `long` | — |
| `pricingDecisionId` | `long` | — |
| `recipientType` | `String` | — |
| `recipientLabel` | `String` | — |
| `docStatus` | `String` | — |
| `quotationVersion` | `int` | — |
| `quotationRevisionNo` | `int` | — |
| `parentQuotationId` | `Long` | — |
| `issuedById` | `long` | — |
| `issuedByName` | `String` | — |
| `issuedAt` | `Instant` | — |
| `subtotalAmount` | `BigDecimal` | — |
| `vatAmount` | `BigDecimal` | — |
| `grandTotal` | `BigDecimal` | — |
| `currency` | `String` | — |
| `paymentTerms` | `String` | — |
| `leadTime` | `String` | — |
| `deliveryTerms` | `String` | — |
| `validityDate` | `LocalDate` | — |
| `customerNotes` | `String` | — |
| `sentAt` | `Instant` | — |
| `acceptedAt` | `Instant` | — |
| `rejectedAt` | `Instant` | — |
| `createdAt` | `Instant` | — |
| `outcomeNote` | `String` | — |
| `outcomeRecordedAt` | `Instant` | — |
| `items` | `List<CustomerQuotationItemDto>` | — |

#### `CustomerQuotationItemDto` — 15 fields
<sub>`customerquotation/CustomerQuotationDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `seq` | `int` | — |
| `pricingRequestItemId` | `long` | — |
| `pricingDecisionItemId` | `long` | — |
| `description` | `String` | — |
| `itemNotes` | `String` | — |
| `requestedUnitBasis` | `String` | — |
| `requestedQuantity` | `BigDecimal` | — |
| `approvedUnitPrice` | `BigDecimal` | — |
| `salesDiscount` | `BigDecimal` | — |
| `finalUnitPrice` | `BigDecimal` | — |
| `minimumSellingPricePerRequestedUnit` | `BigDecimal` | — |
| `lineSubtotal` | `BigDecimal` | — |
| `vat` | `BigDecimal` | — |
| `lineTotal` | `BigDecimal` | — |

### Request DTOs — accepted from the client

#### `CreateCustomerQuotationRequest` — 6 fields
<sub>`customerquotation/CustomerQuotationRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `paymentTerms` | `String` | `@Size(max = 2000)` |
| `leadTime` | `String` | `@Size(max = 2000)` |
| `deliveryTerms` | `String` | `@Size(max = 2000)` |
| `validityDate` | `LocalDate` | — |
| `customerNotes` | `String` | `@Size(max = 4000)` |
| `clientRequestId` | `String` | — |

#### `UpdateCustomerQuotationItemRequest` — 4 fields
<sub>`customerquotation/CustomerQuotationRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `quotationItemId` | `Long` | `@NotNull` |
| `description` | `String` | `@Size(max = 2000)` |
| `itemNotes` | `String` | `@Size(max = 2000)` |
| `salesDiscount` | `BigDecimal` | — |

#### `UpdateCustomerQuotationRequest` — 6 fields
<sub>`customerquotation/CustomerQuotationRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `paymentTerms` | `String` | `@Size(max = 2000)` |
| `leadTime` | `String` | `@Size(max = 2000)` |
| `deliveryTerms` | `String` | `@Size(max = 2000)` |
| `validityDate` | `LocalDate` | — |
| `customerNotes` | `String` | `@Size(max = 4000)` |
| `items` | `List< UpdateCustomerQuotationItemRequest>` | `@Valid` |

#### `IssueCustomerQuotationRequest` — 1 fields
<sub>`customerquotation/CustomerQuotationRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `clientRequestId` | `String` | — |

#### `CancelCustomerQuotationRequest` — 1 fields
<sub>`customerquotation/CustomerQuotationRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `reason` | `String` | `@Size(max = 2000)` |

#### `CreateRevisionRequest` — 2 fields
<sub>`customerquotation/CustomerQuotationRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `reason` | `String` | `@Size(max = 2000)` |
| `clientRequestId` | `String` | — |

#### `RecordQuotationOutcomeRequest` — 3 fields
<sub>`customerquotation/CustomerQuotationRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `outcome` | `String` | — |
| `customerNote` | `String` | `@Size(max = 4000)` |
| `clientRequestId` | `String` | — |

### Internal records — never cross the API boundary

#### `NewItem` — 13 fields
<sub>`customerquotation/CustomerQuotationRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `pricingRequestItemId` | `long` | — |
| `pricingDecisionItemId` | `long` | — |
| `description` | `String` | — |
| `requestedUnitBasis` | `String` | — |
| `requestedQuantity` | `BigDecimal` | — |
| `approvedUnitPrice` | `BigDecimal` | — |
| `salesDiscount` | `BigDecimal` | — |
| `finalUnitPrice` | `BigDecimal` | — |
| `lineSubtotal` | `BigDecimal` | — |
| `vat` | `BigDecimal` | — |
| `lineTotal` | `BigDecimal` | — |
| `brand` | `String` | — |
| `rawUnit` | `String` | — |

#### `InsertDraftParams` — 22 fields
<sub>`customerquotation/CustomerQuotationRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `ticketId` | `long` | — |
| `pricingRequestId` | `long` | — |
| `pricingDecisionId` | `long` | — |
| `recipientType` | `String` | — |
| `recipientLabel` | `String` | — |
| `actorId` | `long` | — |
| `clientRequestId` | `String` | — |
| `paymentTerms` | `String` | — |
| `leadTime` | `String` | — |
| `deliveryTerms` | `String` | — |
| `validityDate` | `LocalDate` | — |
| `customerNotes` | `String` | — |
| `parentQuotationId` | `Long` | — |
| `quotationRevisionNo` | `int` | — |
| `subtotal` | `BigDecimal` | — |
| `currency` | `String` | — |
| `customerName` | `String` | — |
| `customerAddress` | `String` | — |
| `customerTaxId` | `String` | — |
| `customerPhone` | `String` | — |
| `projectName` | `String` | — |
| `items` | `List<NewItem>` | — |

#### `ItemUpdate` — 8 fields
<sub>`customerquotation/CustomerQuotationRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `itemId` | `long` | — |
| `description` | `String` | — |
| `itemNotes` | `String` | — |
| `salesDiscount` | `BigDecimal` | — |
| `finalUnitPrice` | `BigDecimal` | — |
| `lineSubtotal` | `BigDecimal` | — |
| `vat` | `BigDecimal` | — |
| `lineTotal` | `BigDecimal` | — |

#### `ExpiredQuotationRow` — 4 fields
<sub>`customerquotation/CustomerQuotationRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `quotationId` | `long` | — |
| `pricingRequestId` | `long` | — |
| `ticketId` | `long` | — |
| `number` | `String` | — |

#### `RenderContext` — 3 fields
<sub>`customerquotation/CustomerQuotationService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `ticket` | `TicketDto` | — |
| `quotation` | `th.co.glr.hr.ticket.QuotationDto` | — |
| `customer` | `CustomerDto` | — |


---

## 7 · OrderConfirmationService

### Response DTOs — returned to the client

#### `OrderConfirmationResultDto` — 2 fields
<sub>`orderconfirmation/OrderConfirmationDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `ticket` | `TicketDto` | — |
| `pricingRequest` | `PricingRequestSummaryDto` | — |

### Request DTOs — accepted from the client

#### `ConfirmOrderRequest` — 1 fields
<sub>`orderconfirmation/OrderConfirmationRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `clientRequestId` | `String` | — |

#### `CreateDepositNoticeFromQuotationRequest` — 1 fields
<sub>`orderconfirmation/OrderConfirmationRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `depositPercent` | `BigDecimal` | `@DecimalMin("0") @DecimalMax("1")` |


---

## 8 · DepositNoticeService

### Response DTOs — returned to the client

#### `DepositNoticeDto` — 27 fields
<sub>`deposit/DepositNoticeDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `ticketId` | `long` | — |
| `docType` | `String` | — |
| `version` | `int` | — |
| `docNumber` | `String` | — |
| `issueDate` | `LocalDate` | — |
| `status` | `String` | — |
| `customerName` | `String` | — |
| `customerTaxId` | `String` | — |
| `customerAddress` | `String` | — |
| `projectName` | `String` | — |
| `reference` | `String` | — |
| `currency` | `String` | — |
| `depositPercent` | `BigDecimal` | — |
| `subtotal` | `BigDecimal` | — |
| `depositAmount` | `BigDecimal` | — |
| `vatPercent` | `BigDecimal` | — |
| `vatAmount` | `BigDecimal` | — |
| `totalPayable` | `BigDecimal` | — |
| `notes` | `List<String>` | — |
| `hasPdf` | `boolean` | — |
| `hasXlsx` | `boolean` | — |
| `issuedByName` | `String` | — |
| `preparerName` | `String` | — |
| `createdAt` | `OffsetDateTime` | — |
| `updatedAt` | `OffsetDateTime` | — |
| `items` | `List<DepositNoticeItemDto>` | — |

#### `DepositNoticeItemDto` — 9 fields
<sub>`deposit/DepositNoticeItemDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `seq` | `int` | — |
| `description` | `String` | — |
| `qty` | `BigDecimal` | — |
| `unit` | `String` | — |
| `unitPrice` | `BigDecimal` | — |
| `discountLabel` | `String` | — |
| `netUnitPrice` | `BigDecimal` | — |
| `amount` | `BigDecimal` | — |

#### `DocumentNoteTemplateDto` — 4 fields
<sub>`deposit/DocumentNoteTemplateDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `text` | `String` | — |
| `defaultSelected` | `boolean` | — |
| `sortOrder` | `int` | — |

#### `RemainingInvoiceDto` — 9 fields
<sub>`deposit/RemainingInvoiceDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `docNumber` | `String` | — |
| `issueDate` | `LocalDate` | — |
| `reference` | `String` | — |
| `customerName` | `String` | — |
| `customerAddress` | `String` | — |
| `customerTaxId` | `String` | — |
| `projectName` | `String` | — |
| `depositAmount` | `BigDecimal` | — |
| `items` | `List<RemainingInvoiceItemDto>` | — |

#### `RemainingInvoiceItemDto` — 5 fields
<sub>`deposit/RemainingInvoiceItemDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `seq` | `int` | — |
| `description` | `String` | — |
| `qty` | `BigDecimal` | — |
| `unit` | `String` | — |
| `unitPrice` | `BigDecimal` | — |

### Request DTOs — accepted from the client

#### `DepositNoticeDraftRequest` — 8 fields
<sub>`deposit/DepositNoticeDraftRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `customerName` | `String` | `@Size(max = 255)` |
| `customerTaxId` | `String` | `@Size(max = 255)` |
| `customerAddress` | `String` | `@Size(max = 255)` |
| `projectName` | `String` | `@Size(max = 255)` |
| `reference` | `String` | `@Size(max = 255)` |
| `depositPercent` | `BigDecimal` | `@DecimalMin("0") @DecimalMax("1")` |
| `notes` | `List<String>` | — |
| `items` | `List<DepositNoticeItemRequest>` | `@Valid` |

#### `DepositNoticeItemRequest` — 7 fields
<sub>`deposit/DepositNoticeItemRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `seq` | `int` | — |
| `description` | `String` | `@NotBlank` |
| `qty` | `BigDecimal` | `@NotNull @PositiveOrZero` |
| `unit` | `String` | `@Size(max = 32)` |
| `unitPrice` | `BigDecimal` | `@NotNull @PositiveOrZero` |
| `discountLabel` | `String` | — |
| `netUnitPrice` | `BigDecimal` | `@NotNull @PositiveOrZero` |

#### `RevisionRequest` — 2 fields
<sub>`deposit/RevisionRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `scope` | `RevisionScope` | `@NotNull` |
| `reason` | `String` | `@NotBlank` |


---

## 9 · ProcurementService

### Response DTOs — returned to the client

#### `FactoryPurchaseOrderDto` — 26 fields
<sub>`procurement/ProcurementDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `poNumber` | `String` | — |
| `pricingRequestId` | `long` | — |
| `pricingRequestCode` | `String` | — |
| `ticketId` | `long` | — |
| `ticketCode` | `String` | — |
| `factoryId` | `Long` | — |
| `factoryName` | `String` | — |
| `status` | `String` | — |
| `supplierProformaRef` | `String` | — |
| `supplierPaymentScheduleNote` | `String` | — |
| `currency` | `String` | — |
| `totalAmount` | `BigDecimal` | — |
| `etd` | `LocalDate` | — |
| `eta` | `LocalDate` | — |
| `containerRef` | `String` | — |
| `customsStatus` | `String` | — |
| `actualLandedCostThb` | `BigDecimal` | — |
| `cancelReason` | `String` | — |
| `createdBy` | `Long` | — |
| `createdByName` | `String` | — |
| `createdAt` | `Instant` | — |
| `updatedAt` | `Instant` | — |
| `receivedAt` | `Instant` | — |
| `cancelledAt` | `Instant` | — |
| `items` | `List<FactoryPurchaseOrderItemDto>` | — |

#### `FactoryPurchaseOrderItemDto` — 16 fields
<sub>`procurement/ProcurementDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `factoryPurchaseOrderId` | `long` | — |
| `pricingCostingItemId` | `long` | — |
| `pricingRequestItemId` | `long` | — |
| `brand` | `String` | — |
| `model` | `String` | — |
| `productDescription` | `String` | — |
| `quantity` | `BigDecimal` | — |
| `unitPrice` | `BigDecimal` | — |
| `currency` | `String` | — |
| `lineTotal` | `BigDecimal` | — |
| `estimatedLandedCostPerUnitThb` | `BigDecimal` | — |
| `estimatedTotalLandedCostThb` | `BigDecimal` | — |
| `qtyReceived` | `BigDecimal` | — |
| `qcNote` | `String` | — |
| `discrepancyQty` | `BigDecimal` | — |

### Request DTOs — accepted from the client

#### `CreateFactoryPurchaseOrdersRequest` — 1 fields
<sub>`procurement/ProcurementRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `clientRequestId` | `String` | — |

#### `RecordSupplierProformaRequest` — 2 fields
<sub>`procurement/ProcurementRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `supplierProformaRef` | `String` | `@NotBlank` |
| `supplierPaymentScheduleNote` | `String` | — |

#### `RecordShippingDetailRequest` — 4 fields
<sub>`procurement/ProcurementRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `containerRef` | `String` | — |
| `etd` | `LocalDate` | — |
| `eta` | `LocalDate` | — |
| `customsStatus` | `String` | — |

#### `RecordGoodsReceivedRequest` — 2 fields
<sub>`procurement/ProcurementRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `actualLandedCostThb` | `BigDecimal` | `@NotNull @DecimalMin("0")` |
| `items` | `List<ItemReceiptRequest>` | `@Valid` |

#### `ItemReceiptRequest` — 3 fields
<sub>`procurement/ProcurementRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `itemId` | `Long` | `@NotNull` |
| `qtyReceived` | `BigDecimal` | `@NotNull @DecimalMin("0")` |
| `qcNote` | `String` | — |

#### `CancelFactoryPurchaseOrderRequest` — 1 fields
<sub>`procurement/ProcurementRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `reason` | `String` | `@NotBlank` |

### Internal records — never cross the API boundary

#### `CostingItemForPo` — 7 fields
<sub>`procurement/ProcurementRepository.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `pricingCostingItemId` | `long` | — |
| `pricingRequestItemId` | `long` | — |
| `factoryId` | `Long` | — |
| `factoryName` | `String` | — |
| `rawUnitPrice` | `BigDecimal` | — |
| `rawCurrency` | `String` | — |
| `requestedQuantity` | `BigDecimal` | — |


---

## 10 · CommissionService

### Response DTOs — returned to the client

#### `CommissionRecord` — 32 fields
<sub>`commission/CommissionRecord.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `invoiceDetails` | `InvoiceDetails` | — |
| `sourceTicketId` | `Long` | — |
| `salesRepId` | `long` | — |
| `salesRepName` | `String` | — |
| `submittedById` | `long` | — |
| `kind` | `String` | — |
| `status` | `String` | — |
| `payrollMonth` | `LocalDate` | — |
| `actualReceived` | `BigDecimal` | — |
| `commissionableBase` | `BigDecimal` | — |
| `weightMultiplier` | `int` | — |
| `approvedById` | `Long` | — |
| `approvedAt` | `Instant` | — |
| `managerApprovedBy` | `Long` | — |
| `managerApprovedByName` | `String` | — |
| `managerApprovedAt` | `Instant` | — |
| `ceoApprovedBy` | `Long` | — |
| `ceoApprovedByName` | `String` | — |
| `ceoApprovedAt` | `Instant` | — |
| `rejectedById` | `Long` | — |
| `rejectedByName` | `String` | — |
| `rejectedAt` | `Instant` | — |
| `rejectionReason` | `String` | — |
| `cancellationOfId` | `Long` | — |
| `cancellationReason` | `String` | — |
| `createdAt` | `Instant` | — |
| `updatedAt` | `Instant` | — |
| `dealPayableAmountSnapshot` | `BigDecimal` | — |
| `dealAmountMismatch` | `boolean` | — |
| `manualAmount` | `BigDecimal` | — |
| `manualReason` | `String` | — |

#### `CommissionSimulationDto` — 7 fields
<sub>`commission/CommissionSimulationDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `payrollMonth` | `LocalDate` | — |
| `actualReceived` | `BigDecimal` | — |
| `commissionableBase` | `BigDecimal` | — |
| `existingMonthlyBase` | `BigDecimal` | — |
| `projectedMonthlyBase` | `BigDecimal` | — |
| `projectedMonthlyCommission` | `BigDecimal` | — |
| `incrementalCommission` | `BigDecimal` | — |

#### `IncentiveTierConfig` — 4 fields
<sub>`commission/IncentiveTierConfig.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `tierNumber` | `int` | — |
| `thresholdBase` | `BigDecimal` | — |
| `incentiveAmount` | `BigDecimal` | — |
| `effectiveFrom` | `LocalDate` | — |

#### `InvoiceCalculation` — 2 fields
<sub>`commission/InvoiceCalculation.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `actualReceived` | `BigDecimal` | — |
| `commissionableBase` | `BigDecimal` | — |

#### `InvoiceDetails` — 15 fields
<sub>`commission/InvoiceDetails.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `invoiceNumber` | `String` | — |
| `invoiceDate` | `LocalDate` | — |
| `grossAmount` | `BigDecimal` | — |
| `bankFees` | `BigDecimal` | — |
| `suspenseVat` | `BigDecimal` | — |
| `transportFee` | `BigDecimal` | — |
| `cutFee` | `BigDecimal` | — |
| `shortfall` | `BigDecimal` | — |
| `withholdingTax` | `BigDecimal` | — |
| `overpayment` | `BigDecimal` | — |
| `invoiceAttachmentId` | `Long` | — |
| `invoiceAttachmentFileName` | `String` | — |
| `createdAt` | `Instant` | — |
| `updatedAt` | `Instant` | — |

#### `PayrollCommissionSummaryDto` — 7 fields
<sub>`commission/PayrollCommissionSummaryDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `payrollMonth` | `LocalDate` | — |
| `status` | `String` | — |
| `totalCommissionableBase` | `BigDecimal` | — |
| `totalCommissionAmount` | `BigDecimal` | — |
| `totalIncentiveAmount` | `BigDecimal` | — |
| `totalStockBonusAmount` | `BigDecimal` | — |
| `salesReps` | `List<SalesRepCommissionSummaryDto>` | — |

#### `SalesRepCommissionSummaryDto` — 7 fields
<sub>`commission/SalesRepCommissionSummaryDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `salesRepId` | `long` | — |
| `salesRepName` | `String` | — |
| `commissionableBase` | `BigDecimal` | — |
| `commissionAmount` | `BigDecimal` | — |
| `manualAdjustmentAmount` | `BigDecimal` | — |
| `incentiveAmount` | `BigDecimal` | — |
| `stockBonusAmount` | `BigDecimal` | — |

#### `StockBonusConfig` — 4 fields
<sub>`commission/StockBonusConfig.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `enabled` | `boolean` | — |
| `effectiveFrom` | `LocalDate` | — |
| `blockAmount` | `BigDecimal` | — |
| `bonusPerBlock` | `BigDecimal` | — |

#### `TierConfig` — 5 fields
<sub>`commission/TierConfig.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `tierNumber` | `int` | — |
| `lowerBound` | `BigDecimal` | — |
| `upperBound` | `BigDecimal` | — |
| `ratePercent` | `BigDecimal` | — |
| `highRoller` | `boolean` | — |

### Request DTOs — accepted from the client

#### `CommissionSimulatorRequest` — 10 fields
<sub>`commission/CommissionSimulatorRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `salesRepId` | `Long` | — |
| `payrollMonth` | `LocalDate` | — |
| `grossAmount` | `BigDecimal` | `@NotNull @DecimalMin("0.00")` |
| `bankFees` | `BigDecimal` | `@DecimalMin("0.00")` |
| `suspenseVat` | `BigDecimal` | `@DecimalMin("0.00")` |
| `transportFee` | `BigDecimal` | `@DecimalMin("0.00")` |
| `cutFee` | `BigDecimal` | `@DecimalMin("0.00")` |
| `shortfall` | `BigDecimal` | `@DecimalMin("0.00")` |
| `withholdingTax` | `BigDecimal` | `@DecimalMin("0.00")` |
| `overpayment` | `BigDecimal` | `@DecimalMin("0.00")` |

#### `CreateClawbackRequest` — 1 fields
<sub>`commission/CreateClawbackRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `reason` | `String` | `@NotBlank` |

#### `ManualCommissionRequest` — 5 fields
<sub>`commission/ManualCommissionRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `salesRepId` | `Long` | `@NotNull` |
| `kind` | `String` | `@NotBlank` |
| `amount` | `BigDecimal` | `@NotNull` |
| `reason` | `String` | `@NotBlank` |
| `payrollMonth` | `LocalDate` | — |

#### `ReviewCommissionRequest` — 1 fields
<sub>`commission/ReviewCommissionRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `reviewerNote` | `String` | `@Size(max = 2000)` |

#### `SubmitCommissionRequest` — 12 fields
<sub>`commission/SubmitCommissionRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `sourceTicketId` | `Long` | — |
| `salesRepId` | `Long` | — |
| `invoiceNumber` | `String` | `@NotBlank` |
| `invoiceDate` | `LocalDate` | `@NotNull` |
| `grossAmount` | `BigDecimal` | `@NotNull @DecimalMin("0.00")` |
| `bankFees` | `BigDecimal` | `@DecimalMin("0.00")` |
| `suspenseVat` | `BigDecimal` | `@DecimalMin("0.00")` |
| `transportFee` | `BigDecimal` | `@DecimalMin("0.00")` |
| `cutFee` | `BigDecimal` | `@DecimalMin("0.00")` |
| `shortfall` | `BigDecimal` | `@DecimalMin("0.00")` |
| `withholdingTax` | `BigDecimal` | `@DecimalMin("0.00")` |
| `overpayment` | `BigDecimal` | `@DecimalMin("0.00")` |

#### `UpdateCommissionDeductionsRequest` — 10 fields
<sub>`commission/UpdateCommissionDeductionsRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `grossAmount` | `BigDecimal` | `@DecimalMin("0.00")` |
| `bankFees` | `BigDecimal` | `@DecimalMin("0.00")` |
| `suspenseVat` | `BigDecimal` | `@DecimalMin("0.00")` |
| `transportFee` | `BigDecimal` | `@DecimalMin("0.00")` |
| `cutFee` | `BigDecimal` | `@DecimalMin("0.00")` |
| `shortfall` | `BigDecimal` | `@DecimalMin("0.00")` |
| `withholdingTax` | `BigDecimal` | `@DecimalMin("0.00")` |
| `overpayment` | `BigDecimal` | `@DecimalMin("0.00")` |
| `weightMultiplier` | `Integer` | `@Min(1) @Max(3)` |
| `reason` | `String` | `@NotBlank` |

### Response envelopes

#### `CommissionListResponse` — 1 fields
<sub>`commission/CommissionResponses.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `commissions` | `List<CommissionRecord>` | — |

#### `CommissionDetailResponse` — 1 fields
<sub>`commission/CommissionResponses.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `commission` | `CommissionRecord` | — |

#### `CommissionSimulationResponse` — 1 fields
<sub>`commission/CommissionResponses.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `simulation` | `CommissionSimulationDto` | — |

#### `PayrollSummaryResponse` — 1 fields
<sub>`commission/CommissionResponses.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `summary` | `PayrollCommissionSummaryDto` | — |

### Internal records — never cross the API boundary

#### `DealLinkage` — 2 fields
<sub>`commission/CommissionService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `payableSnapshot` | `BigDecimal` | — |
| `mismatch` | `boolean` | — |

#### `RepPayrollCommission` — 7 fields
<sub>`commission/CommissionService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `salesRepId` | `long` | — |
| `salesRepName` | `String` | — |
| `tierCommissionableBase` | `BigDecimal` | — |
| `manualAdjustmentAmount` | `BigDecimal` | — |
| `incentiveAmount` | `BigDecimal` | — |
| `stockBonusAmount` | `BigDecimal` | — |
| `totalCommission` | `BigDecimal` | — |


---

## CustomerService

### Response DTOs — returned to the client

#### `ContactDto` — 7 fields
<sub>`customer/ContactDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `customerId` | `long` | — |
| `firstName` | `String` | — |
| `lastName` | `String` | — |
| `position` | `String` | — |
| `email` | `String` | — |
| `phone` | `String` | — |

#### `CustomerDto` — 6 fields
<sub>`customer/CustomerDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `name` | `String` | — |
| `taxId` | `String` | — |
| `address` | `String` | — |
| `branch` | `String` | — |
| `phone` | `String` | — |

#### `ProjectDto` — 3 fields
<sub>`customer/ProjectDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `customerId` | `long` | — |
| `name` | `String` | — |

### Request DTOs — accepted from the client

#### `CreateCustomerRequest` — 5 fields
<sub>`customer/CustomerController.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `name` | `String` | `@NotBlank @Size(max = 200)` |
| `taxId` | `String` | `@Size(max = 20)` |
| `address` | `String` | `@Size(max = 2000)` |
| `branch` | `String` | `@Size(max = 100)` |
| `phone` | `String` | `@Size(max = 50)` |

#### `CreateContactRequest` — 5 fields
<sub>`customer/CustomerController.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `firstName` | `String` | `@NotBlank @Size(max = 100)` |
| `lastName` | `String` | `@Size(max = 100)` |
| `position` | `String` | `@Size(max = 100)` |
| `email` | `String` | `@Email @Size(max = 200)` |
| `phone` | `String` | `@Size(max = 50)` |

#### `CreateProjectRequest` — 1 fields
<sub>`customer/CustomerController.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `name` | `String` | `@NotBlank @Size(max = 200)` |


---

## Pricing / FX / formula config

### Response DTOs — returned to the client

#### `DealEstimateMarkupDto` — 3 fields
<sub>`pricing/DealEstimateMarkupDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `multiplier` | `BigDecimal` | — |
| `updatedAt` | `Instant` | — |
| `updatedBy` | `Long` | — |

#### `FxRateDto` — 7 fields
<sub>`pricing/FxRateDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `currency` | `String` | — |
| `rateToThb` | `BigDecimal` | — |
| `effectiveDate` | `LocalDate` | — |
| `updatedAt` | `Instant` | — |
| `source` | `String` | — |
| `fetchedAt` | `Instant` | — |

#### `PriceBreakdownItemDto` — 19 fields
<sub>`pricing/PriceBreakdownItemDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `itemId` | `long` | — |
| `brand` | `String` | — |
| `model` | `String` | — |
| `factory` | `String` | — |
| `rawCurrency` | `String` | — |
| `fxRate` | `BigDecimal` | — |
| `sqmPerPiece` | `BigDecimal` | — |
| `goodsCostPerSqm` | `BigDecimal` | — |
| `freightPerSqm` | `BigDecimal` | — |
| `insurancePerSqm` | `BigDecimal` | — |
| `cifPerSqm` | `BigDecimal` | — |
| `importDutyPerSqm` | `BigDecimal` | — |
| `inlandPerSqm` | `BigDecimal` | — |
| `landedCostPerSqm` | `BigDecimal` | — |
| `marginPct` | `BigDecimal` | — |
| `sellPricePerSqm` | `BigDecimal` | — |
| `calcedCostPerPiece` | `BigDecimal` | — |
| `calcedPricePerPiece` | `BigDecimal` | — |
| `configVersion` | `int` | — |

#### `PriceCalcConfigDto` — 12 fields
<sub>`pricing/PriceCalcConfigDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `configId` | `long` | — |
| `version` | `int` | — |
| `country` | `String` | — |
| `freightPerSqm` | `BigDecimal` | — |
| `insurancePerSqm` | `BigDecimal` | — |
| `inlandFactoryToPortPerSqm` | `BigDecimal` | — |
| `inlandPortToWarehousePerSqm` | `BigDecimal` | — |
| `importDutyPct` | `BigDecimal` | — |
| `marginPct` | `BigDecimal` | — |
| `isCurrent` | `boolean` | — |
| `effectiveFrom` | `LocalDate` | — |
| `updatedAt` | `Instant` | — |

#### `PricingFormulaConfigDto` — 15 fields
<sub>`pricing/PricingFormulaConfigDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `formulaConfigId` | `long` | — |
| `version` | `int` | — |
| `insuranceValueFactor` | `BigDecimal` | — |
| `insuranceRate` | `BigDecimal` | — |
| `insuranceBuffer` | `BigDecimal` | — |
| `costBuffer` | `BigDecimal` | — |
| `sellingBuffer` | `BigDecimal` | — |
| `defaultMarginPct` | `BigDecimal` | — |
| `sellingPriceRoundUpTo` | `BigDecimal` | — |
| `isCurrent` | `boolean` | — |
| `effectiveFrom` | `LocalDate` | — |
| `updatedAt` | `Instant` | — |
| `freightRates` | `List<PricingFreightRateDto>` | — |
| `dutyRates` | `List<PricingDutyRateDto>` | — |
| `clearanceFees` | `List<PricingClearanceFeeDto>` | — |

#### `PricingFreightRateDto` — 7 fields
<sub>`pricing/PricingFormulaConfigDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `freightRateId` | `long` | — |
| `originCountry` | `String` | — |
| `thicknessMinMm` | `BigDecimal` | — |
| `thicknessMaxMm` | `BigDecimal` | — |
| `qtyMinSqm` | `BigDecimal` | — |
| `qtyMaxSqm` | `BigDecimal` | — |
| `amountThb` | `BigDecimal` | — |

#### `PricingDutyRateDto` — 4 fields
<sub>`pricing/PricingFormulaConfigDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `dutyRateId` | `long` | — |
| `productType` | `String` | — |
| `productLabel` | `String` | — |
| `dutyPct` | `BigDecimal` | — |

#### `PricingClearanceFeeDto` — 4 fields
<sub>`pricing/PricingFormulaConfigDtos.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `clearanceFeeId` | `long` | — |
| `qtyMinSqm` | `BigDecimal` | — |
| `qtyMaxSqm` | `BigDecimal` | — |
| `amountThb` | `BigDecimal` | — |

### Request DTOs — accepted from the client

#### `CreatePricingFormulaConfigRequest` — 11 fields
<sub>`pricing/PricingFormulaConfigRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `insuranceValueFactor` | `BigDecimal` | `@NotNull @DecimalMin(value = "0", inclusive = true)` |
| `insuranceRate` | `BigDecimal` | `@NotNull @DecimalMin(value = "0", inclusive = true)` |
| `insuranceBuffer` | `BigDecimal` | `@NotNull @DecimalMin(value = "0", inclusive = true)` |
| `costBuffer` | `BigDecimal` | `@NotNull @DecimalMin(value = "0", inclusive = true)` |
| `sellingBuffer` | `BigDecimal` | `@NotNull @DecimalMin(value = "0", inclusive = true)` |
| `defaultMarginPct` | `BigDecimal` | `@NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "1", inclusive = true)` |
| `sellingPriceRoundUpTo` | `BigDecimal` | `@NotNull @DecimalMin(value = "0", inclusive = false)` |
| `effectiveFrom` | `LocalDate` | — |
| `freightRates` | `List<FreightRateRequest>` | `@NotEmpty @Valid` |
| `dutyRates` | `List<DutyRateRequest>` | `@NotEmpty @Valid` |
| `clearanceFees` | `List<ClearanceFeeRequest>` | `@NotEmpty @Valid` |

#### `FreightRateRequest` — 6 fields
<sub>`pricing/PricingFormulaConfigRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `originCountry` | `String` | `@NotBlank` |
| `thicknessMinMm` | `BigDecimal` | `@NotNull @DecimalMin(value = "0", inclusive = true)` |
| `thicknessMaxMm` | `BigDecimal` | `@NotNull @DecimalMin(value = "0", inclusive = true)` |
| `qtyMinSqm` | `BigDecimal` | `@NotNull @DecimalMin(value = "0", inclusive = true)` |
| `qtyMaxSqm` | `BigDecimal` | — |
| `amountThb` | `BigDecimal` | `@NotNull @DecimalMin(value = "0", inclusive = true)` |

#### `DutyRateRequest` — 3 fields
<sub>`pricing/PricingFormulaConfigRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `productType` | `String` | `@NotBlank` |
| `productLabel` | `String` | `@NotBlank` |
| `dutyPct` | `BigDecimal` | `@NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "1", inclusive = true)` |

#### `ClearanceFeeRequest` — 3 fields
<sub>`pricing/PricingFormulaConfigRequests.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `qtyMinSqm` | `BigDecimal` | `@NotNull @DecimalMin(value = "0", inclusive = true)` |
| `qtyMaxSqm` | `BigDecimal` | — |
| `amountThb` | `BigDecimal` | `@NotNull @DecimalMin(value = "0", inclusive = true)` |

#### `UpdateDealEstimateMarkupRequest` — 1 fields
<sub>`pricing/UpdateDealEstimateMarkupRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `multiplier` | `BigDecimal` | `@NotNull @DecimalMin(value = "0.001", inclusive = true) @DecimalMax(value = "999.999", inclusive = true)` |

#### `UpdatePriceCalcConfigRequest` — 8 fields
<sub>`pricing/UpdatePriceCalcConfigRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `country` | `String` | `@NotBlank` |
| `freightPerSqm` | `BigDecimal` | `@NotNull` |
| `insurancePerSqm` | `BigDecimal` | `@NotNull` |
| `inlandFactoryToPortPerSqm` | `BigDecimal` | `@NotNull` |
| `inlandPortToWarehousePerSqm` | `BigDecimal` | `@NotNull` |
| `importDutyPct` | `BigDecimal` | `@NotNull` |
| `marginPct` | `BigDecimal` | `@NotNull` |
| `effectiveFrom` | `LocalDate` | — |

#### `UpsertFxRateRequest` — 2 fields
<sub>`pricing/UpsertFxRateRequest.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `rateToThb` | `BigDecimal` | `@NotNull @Positive` |
| `effectiveDate` | `LocalDate` | — |

### Response envelopes

#### `BotResponse` — 1 fields
<sub>`pricing/BotFxFetchService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `result` | `BotResult` | — |

### Internal records — never cross the API boundary

#### `BotResult` — 1 fields
<sub>`pricing/BotFxFetchService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `data` | `BotData` | — |

#### `BotData` — 1 fields
<sub>`pricing/BotFxFetchService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `dataDetail` | `List<BotDataDetail>` | `@JsonProperty("data_detail")` |

#### `BotDataDetail` — 2 fields
<sub>`pricing/BotFxFetchService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `currencyId` | `String` | `@JsonProperty("currency_id")` |
| `selling` | `String` | — |


---

## Catalog & price import

### Response DTOs — returned to the client

#### `CatalogDto` — 8 fields
<sub>`catalog/CatalogDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `catalogId` | `long` | — |
| `brand` | `String` | — |
| `collection` | `String` | — |
| `color` | `String` | — |
| `surface` | `String` | — |
| `size` | `String` | — |
| `factory` | `String` | — |
| `sqmPerPiece` | `BigDecimal` | — |

#### `ProductPriceDto` — 14 fields
<sub>`catalog/ProductPriceDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `priceId` | `long` | — |
| `factoryId` | `long` | — |
| `factoryName` | `String` | — |
| `productCode` | `String` | — |
| `grade` | `String` | — |
| `collection` | `String` | — |
| `productName` | `String` | — |
| `color` | `String` | — |
| `surface` | `String` | — |
| `sizeRaw` | `String` | — |
| `price` | `BigDecimal` | — |
| `currency` | `String` | — |
| `priceUnit` | `String` | — |
| `sqmPerPiece` | `BigDecimal` | — |

#### `ProductPriceInput` — 11 fields
<sub>`catalog/ProductPriceInput.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `factoryId` | `Long` | — |
| `productCode` | `String` | — |
| `grade` | `String` | — |
| `collection` | `String` | — |
| `productName` | `String` | — |
| `color` | `String` | — |
| `surface` | `String` | — |
| `sizeRaw` | `String` | — |
| `price` | `BigDecimal` | — |
| `currency` | `String` | — |
| `priceUnit` | `String` | — |

#### `ImportResult` — 2 fields
<sub>`catalog/ImportResult.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `rows` | `List<PriceRow>` | — |
| `errors` | `List<String>` | — |

#### `PriceRow` — 22 fields
<sub>`catalog/PriceRow.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `factoryId` | `long` | — |
| `productCode` | `String` | — |
| `grade` | `String` | — |
| `collection` | `String` | — |
| `productName` | `String` | — |
| `color` | `String` | — |
| `surface` | `String` | — |
| `sizeRaw` | `String` | — |
| `widthMm` | `BigDecimal` | — |
| `heightMm` | `BigDecimal` | — |
| `thicknessMm` | `BigDecimal` | — |
| `price` | `BigDecimal` | — |
| `currency` | `String` | — |
| `priceUnit` | `String` | — |
| `sqmPerPiece` | `BigDecimal` | — |
| `pcsPerBox` | `BigDecimal` | — |
| `sqmPerBox` | `BigDecimal` | — |
| `kgPerBox` | `BigDecimal` | — |
| `priceVariants` | `Map<String, String>` | — |
| `attributes` | `Map<String, String>` | — |
| `sourceSheet` | `String` | — |
| `sourceRow` | `int` | — |

### Internal records — never cross the API boundary

#### `FillDown` — 2 fields
<sub>`catalog/ImportEngine.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `colName` | `String` | — |
| `fieldTarget` | `String` | — |

#### `StagingReport` — 9 fields
<sub>`catalog/PriceImportService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `versionId` | `long` | — |
| `totalStaged` | `int` | — |
| `validCount` | `int` | — |
| `invalidCount` | `int` | — |
| `newProducts` | `int` | — |
| `removedProducts` | `int` | — |
| `priceChanged` | `int` | — |
| `prevVersionId` | `Long` | — |
| `sampleErrors` | `List<String>` | — |

#### `CommitResult` — 4 fields
<sub>`catalog/PriceImportService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `versionId` | `long` | — |
| `committed` | `int` | — |
| `retained` | `int` | — |
| `versionsArchived` | `int` | — |

#### `UploadReport` — 5 fields
<sub>`catalog/PriceImportService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `versionId` | `long` | — |
| `sessionId` | `UUID` | — |
| `parsedRows` | `int` | — |
| `errorCount` | `int` | — |
| `errors` | `List<String>` | — |

#### `UploadCommitResult` — 6 fields
<sub>`catalog/PriceImportService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `versionId` | `long` | — |
| `parsedRows` | `int` | — |
| `committedRows` | `int` | — |
| `retainedRows` | `int` | — |
| `errorCount` | `int` | — |
| `errors` | `List<String>` | — |


---

## Factory config

### Response DTOs — returned to the client

#### `FactoryConfigDto` — 6 fields
<sub>`factory/FactoryConfigDto.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `id` | `long` | — |
| `factoryName` | `String` | — |
| `email` | `String` | — |
| `currency` | `String` | — |
| `unit` | `String` | — |
| `country` | `String` | — |

### Internal records — never cross the API boundary

#### `EmailAttachment` — 3 fields
<sub>`factory/FactoryEmailService.java`</sub>

| Field | Type | Constraints |
|---|---|---|
| `fileName` | `String` | — |
| `filePath` | `String` | — |
| `mimeType` | `String` | — |

