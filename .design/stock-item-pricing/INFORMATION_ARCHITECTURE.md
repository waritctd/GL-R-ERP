# Information Architecture: สินค้าจากสต็อก — stock lines on quotations and pricing requests

Status: **DRAFT v2 for owner review** · 2026-09-30 · branch `feat/pcr-stock-lines-backend` (off `origin/develop` @ `26304083`)

Ordered **backend → API → frontend**, which is also the build order.

v2 adds the owner's second stock case. Stock is now either **สต็อกในไทย** (already in Thailand) or **สต็อกกำลังเดินทาง** (in transit, which import has to update).

## 0. Owner rulings (2026-09-30)

| # | Ruling |
|---|--------|
| R1 | Each quotation line has one **source**: **สั่งนำเข้า** (default), **สต็อกในไทย**, or **สต็อกกำลังเดินทาง**. It is per line, never a quantity split. |
| R2 | **Direct quotation:** the source is recorded only. Sales types the price as today. |
| R3 | **Pricing request (คำขอราคา):** stock lines of either kind are **never sent to a factory**. The **CEO sets their price**. |
| R4 | **Only สต็อกในไทย lines:** the request goes straight to the CEO on submit. |
| R5 | **Any สั่งนำเข้า or สต็อกกำลังเดินทาง line:** the request goes to ฝ่ายนำเข้า and reaches the CEO **in one go**, once every import line is quoted **and** every in-transit line has an arrival date. |
| R6 | The CEO prices both stock kinds on the **existing** review screen. He types **ราคาตั้ง**, then the request's single price mode applies. An in-transit line also shows its arrival date. |
| R7 | On any stock line in a pricing request, **โรงงาน / ประเทศต้นทาง / ระยะเวลานำเข้า are hidden and not required**. |
| R8 | The source is **locked for sales after submit**; changing it takes a revision. **One exception (R13):** import may switch an in-transit line to สั่งนำเข้า. |
| R9 | The deal line's sales-entered **"ราคาขาย (สต็อก)" (V183) is dropped**. The deal line's stock marker stays, becomes the same three-way source, and seeds the pricing-request rows. |
| R10 | The **×2/×3 is set during the sales manager's existing commission approval**, at record level. The "ค่าคอมรออนุมัติ" email and her review panel list the deal's stock lines (both kinds). The email is sent when the accountant records the commission; that is fine. |
| R11 | Stock is **not shown on the customer PDF**, including lead time. It is internal only. |
| R12 | **In-transit, pricing stage:** import must enter a **วันที่คาดว่าจะถึง (ETA) per line** before the request can reach the CEO. |
| R13 | If the shipment can't cover the line, **import switches it to สั่งนำเข้า**. The line joins the normal factory-quote flow in the same request, and the rep is notified. |
| R14 | **In-transit, after the order:** it is tracked on the **same งานนำเข้า tracker (`/fulfilment`)**. The card **starts at ระหว่างขนส่ง**: ระหว่างขนส่ง → รอผ่านพิธีการศุลกากร → รับสินค้าเข้าคลัง. Import keeps each line's ETA updated there. |
| R15 | When import **changes an ETA** after the quotation, the **owning sales rep** is notified. |
| R16 | Once an in-transit card reaches **รับสินค้าเข้าคลัง**, its lines count as on-hand stock for delivery. |
| R17 | No cost or margin is shown for stock lines. The per-line ×2/×3 modal on `DealFulfilmentPanel` is **hidden** (UI only). |

## 1. Backend

### 1.1 Schema (forward-only; next free version is **V194**, confirm against both `db/migration` and `db/migration-demo` before authoring)

| Change | Why |
|---|---|
| `sales.pricing_request_item.stock_source VARCHAR(20) NULL` with CHECK `IN ('IN_THAILAND','IN_TRANSIT')`. NULL means สั่งนำเข้า. | R1/R3. Copied through `replaceItems` and revisions. |
| `sales.pricing_request_item.expected_arrival_date DATE NULL`, plus `…_set_by_id` / `…_set_at`, with CHECK: allowed only when `stock_source = 'IN_TRANSIT'` | R12. Set by import. |
| `sales.quotation_item.stock_source` with the same CHECK | R1/R2. One column for both origins. The pipeline copies it from the PCR item. |
| `sales.ticket_item.stock_source` + `expected_arrival_date` | Carried at order confirmation. This is what the after-order tracker and the commission block read. V183's `sourced_from_stock` is kept in sync as `stock_source IS NOT NULL`, so existing readers keep working. |
| Relax V183's `chk_ticket_item_stock_sale_price` so a stock line no longer needs a price | R9. |
| `pricing_decision_item.pricing_costing_item_id` becomes NULLable, with a CHECK that it may be NULL **only** for a stock line | A stock line has no factory quote, so it has no costing row (V72:110, V61:155-161). |
| `sales.import_request.request_kind VARCHAR(20) NOT NULL DEFAULT 'FACTORY_ORDER'` with CHECK `IN ('FACTORY_ORDER','IN_TRANSIT_STOCK')`. For `IN_TRANSIT_STOCK`: no factory required, no email or PDF, and `import_step` starts at `IN_TRANSIT`. | R14. Reusing the tracker lets `/fulfilment` and `FactoryProgressBar` show it with little new UI. **To confirm in build:** reuse versus a lighter dedicated table; reuse is the recommendation. |

Stock lines are **not costed**, so `pricing_costing_item` is unchanged.

### 1.2 Pricing-request state machine (no new statuses, one new edge)

```
submit()
 ├─ only IN_THAILAND lines ─► SUBMITTED ─► READY_FOR_CEO_REVIEW           (existing edge; notify CEO, not import)
 └─ any import / IN_TRANSIT ► SUBMITTED ─► IMPORT_REVIEWING (รับเรื่อง)
        ├─ has import lines ───► AWAITING_FACTORY_RESPONSE ─► READY_FOR_CEO_REVIEW
        └─ no import lines ────────────────────────────────► READY_FOR_CEO_REVIEW   (NEW edge)
     Ready condition (the one predicate for every path):
        every import line resolvable (quote READY_FOR_COSTING)  AND  every IN_TRANSIT line has an ETA
     It is re-checked after markReadyForCosting AND after each ETA save, so whichever finishes last advances it.
```

Code that changes:
- `LandedCostCalculator.resolveSources` / `isFullyResolvable` skip stock lines. A new readiness predicate combines that check with "all ETAs set".
- `FactoryQuoteService.groupByFactory` / `generateDrafts` never include a stock line.
- `PricingRequestService.submit`:
  - Validation relaxes R7 for stock lines.
  - Routing follows the diagram above.
  - Notifications go to the CEO (all in-Thailand) or to import (otherwise).
- **New** `setInTransitArrival(prId, itemId, date)`:
  - Allowed for import and CEO while the request is IMPORT_REVIEWING, AWAITING_FACTORY_RESPONSE or READY_FOR_CEO_REVIEW.
  - Only on an IN_TRANSIT line.
- **New** `convertInTransitToImport(prId, itemId)` (R13):
  - Allowed for import only, while IMPORT_REVIEWING or AWAITING_FACTORY_RESPONSE.
  - Clears `stock_source` and the ETA. The line then needs a factory through the existing `setItemFactory`.
  - Notifies the owning rep.
  - A READY_FOR_CEO_REVIEW request is pulled back to AWAITING_FACTORY_RESPONSE, the same way a revised quote does today.
- `setItemFactory` refuses any stock line with 409.
- `return-to-import` is refused with 409 when the request has only in-Thailand lines.

### 1.3 CEO decision (`PricingDecisionService`)
- `startReview`:
  - Import lines are costed as today.
  - Stock lines (both kinds) get a decision item with no costing link, no cost and no `listUnitPrice`.
  - If there are no import lines, `landedCost.calculate` is skipped.
- `update`: the CEO's ราคาตั้ง on a stock line becomes its `listUnitPrice`, and the mode input then applies. **To confirm in build:** reuse `sellingPriceOverride` (the "ปรับราคาตั้งเอง" path) or add a dedicated field.
- `approve`:
  - The cost gate exempts stock lines.
  - A new 422 fires unless every stock line has ราคาตั้ง > 0.
- The decision item DTO carries `stockSource` + `expectedArrivalDate`, read-only.

### 1.4 Quotation creation and order confirmation
- Pipeline quotation copies `stock_source` to `quotation_item`. Direct quotation: `ItemInput.stockSource` (new overload constructor).
- Order confirmation (both `confirmOrderFromDirectQuotation` and `OrderConfirmationService`) copies `stock_source` + ETA to `ticket_item`.
- When the order has **any IN_TRANSIT line**, it creates one `import_request` of kind `IN_TRANSIT_STOCK` per deal, at step `IN_TRANSIT`, listing those lines.
  - **Direct quotation:** its in-transit lines carry no ETA yet (no import step happened), so the card starts **without** ETA and import must fill it. Its "ขาด ETA" state is a visible worklist item.

### 1.5 After-order tracking (R14–R16)
- The existing `advanceStep` on the in-transit card follows ระหว่างขนส่ง → รอผ่านพิธีการศุลกากร → รับสินค้าเข้าคลัง. Forward-only, as the existing rule is.
- **New** per-line ETA update on the card (import, CEO). Each change notifies the **owning rep** (R15) with a new notification type and a `TICKET_EVENT_TITLES` entry (no event-kind migration needed).
- At **รับสินค้าเข้าคลัง**, the lines count as on-hand for the deal's delivery readiness (R16). **To confirm in build:** how this meets `reserveStock` / `fulfillment_status`. The rule is that an in-transit line never blocks delivery of lines already in Thailand, which matches the existing mixed stock + import sequencing.

### 1.6 Commission (R10). No change to commission maths.
- The commission detail DTO gains read-only `stockLines[]` (source, brand, model, qty, amount), read from the deal's `ticket_item.stock_source`.
- `notifySubmitted` appends the stock-line list to the sales manager's "มีคำขอค่าคอมรออนุมัติ" email.

### 1.7 Authorization
- New write paths:
  - `setInTransitArrival`: import and CEO.
  - `convertInTransitToImport`: import only.
  - The in-transit card's ETA update: import and CEO.
- Each gets a real-DB integration test with wrong-way cases (a sales rep and the sales manager cannot set an ETA or convert a line) plus a mutation check, per CLAUDE.md.
- Existing CEO-only decision gates are unchanged.

## 2. API

Two new endpoints; everything else is fields or behaviour on existing ones. `hrApi.js` and `mockApi.js` are mirrored, `contract.test.js` must stay green, and **both API digests are regenerated** for the new endpoints.

| Endpoint | Change |
|---|---|
| PCR create / update / revision | Item gains `stockSource`. It is refused on a non-DRAFT update. |
| PCR GET (detail, per-ticket, queue) | Item gains `stockSource`, `expectedArrivalDate`. The summary gains `stockItemCount`, `inTransitMissingEtaCount`, `allInThailand`. |
| `POST /api/pricing-requests/{id}/submit` | Routing per §1.2. |
| **NEW** `PUT /api/pricing-requests/{id}/items/{itemId}/expected-arrival` `{ date }` | R12. |
| **NEW** `POST /api/pricing-requests/{id}/items/{itemId}/convert-to-import` | R13. |
| `PUT …/items/{itemId}/factory` | 409 on a stock line. |
| `POST …/pricing-decisions` (startReview), `PUT /api/pricing-decisions/{id}`, `POST …/approve` | Per §1.3. New 422 "รายการจากสต็อกต้องมีราคาตั้ง". |
| `POST …/return-to-import` | 409 when the request has only in-Thailand lines. |
| `/api/deal-quotations` create/update | Item gains `stockSource`. |
| Import-request / fulfilment list + detail | Gains `requestKind` and per-line `expectedArrivalDate`. `advanceStep` is unchanged. |
| **NEW (the one after-order endpoint)** `PUT /api/import-requests/{id}/lines/{lineId}/expected-arrival` | R14/R15. **To confirm:** fold into an existing update endpoint if one fits. |
| `GET /api/commissions/{id}` | Gains `stockLines[]`. |
| `PUT /api/tickets/{id}` (deal line edit) | `stockSource` replaces the V183 boolean + price pair on input. |

## 3. Frontend

### 3.1 Site map (no new pages)
- ดีล `/tickets/:id`
  - tab สินค้าและราคา: deal lines (source selector) + คำขอราคา panel
  - tab จัดซื้อ-ส่งมอบ: import progress, now including the in-transit card
- คำขอราคา `/pricing-requests/:id`: import view + CEO review
- คิวคำขอราคา `/pricing-requests`
- งานนำเข้า `/fulfilment`: import's tracker, now including in-transit cards
- ใบเสนอราคา `/quotations/:id`
- ค่าคอมมิชชั่น `/commissions`; หน้าแรก `/` (ManagerOverview)

### 3.2 Line source selector (one shared control)
A compact three-way segmented control in the line row header: **สั่งนำเข้า · สต็อกในไทย · สต็อกกำลังเดินทาง**. The default is สั่งนำเข้า.

The same control is used on deal lines, pricing-request rows and direct-quotation rows. After submit it is read-only and shows as a badge. Badge tones:
- สต็อกในไทย: success tone.
- สต็อกกำลังเดินทาง: info tone, and it shows "คาดถึง <date>" when an ETA exists.

### 3.3 Flows

**F1: Sales creates a pricing request**
1. Rows are seeded from the deal lines, including their source.
2. Choosing either stock source collapses โรงงาน / ประเทศต้นทาง / ระยะเวลานำเข้า. A one-line hint appears:
   - สต็อกในไทย: *"ไม่ส่งฝ่ายนำเข้า — CEO กำหนดราคาเอง"*
   - สต็อกกำลังเดินทาง: *"ฝ่ายนำเข้าจะยืนยันวันที่คาดว่าจะถึง — CEO กำหนดราคาเอง"*
3. The footer button follows the sources:
   - Only in-Thailand lines: **"ส่งให้ CEO กำหนดราคา"**.
   - Otherwise: **"ส่งให้ฝ่ายนำเข้า"**, with a summary: *"สต็อกในไทย N · สต็อกกำลังเดินทาง M (ฝ่ายนำเข้ายืนยันวันถึง) · สั่งนำเข้า K"*.
4. After submit the source is read-only. To change it, the rep uses สร้างรอบแก้ไข.

**F2: ฝ่ายนำเข้า works the request** (detail page; import lines as today)
1. **Group "สต็อกกำลังเดินทาง — ยืนยันวันที่คาดว่าจะถึง (M รายการ)"**. Each line has:
   - a required date input **"วันที่คาดว่าจะถึง"** with a save button;
   - a secondary action **"เปลี่ยนเป็นสั่งนำเข้า"**, with a confirm step: *"สต็อกระหว่างทางไม่พอ — รายการนี้จะเข้าขั้นตอนขอราคาโรงงาน และแจ้งฝ่ายขาย"*. After confirming, the line moves into the import group and shows `ImportFactoryPicker`.
2. **Group "สต็อกในไทย (ไม่ต้องดำเนินการ)"**: compact and read-only.
3. A readiness line at the top: *"พร้อมส่ง CEO เมื่อ: ราคาโรงงานครบ (x/y) · วันถึงครบ (a/b)"*.
4. An in-transit-only request has no factory-quote grid. Once the last ETA is saved, it goes to the CEO automatically.
5. On the queue, requests with missing ETAs get a **"รอวันถึง N"** chip.

**F3: CEO prices it**
1. The stock card shows a source badge and no cost block. It has a required **"ราคาตั้ง (บาท/แผ่น)"** input, then the same mode input as every other card.
2. An in-transit card also shows **"คาดถึง <date>"**.
3. The approve bar's blocker lists: missing cost (import lines) and *"รายการจากสต็อกต้องกรอกราคาตั้ง (N)"*.
4. "ตีกลับให้ฝ่ายนำเข้าแก้ไข" is hidden when every line is in Thailand.

**F4: Quotation from the pipeline.** Lines print normally, with no stock mark and no automatic lead time (R11). The editor shows an internal source chip.

**F5: Direct quotation.** Same selector, record only. Nothing hides and the price is unchanged. An in-transit line here has no ETA until the order is confirmed (§1.4).

**F6: Deal line.** The V183 "จากสต็อก" checkbox and "ราคาขาย (สต็อก)" field are replaced by the source selector. Hint: *"ราคาสต็อกกำหนดโดย CEO ในคำขอราคา"*.

**F7: After the order: in-transit tracking** (R14–R16)
- **`/fulfilment` (งานนำเข้า):**
  - The deal's in-transit card appears next to factory cards, labelled **"สต็อกกำลังเดินทาง"** instead of a factory name. It has no email or PDF actions.
  - `FactoryProgressBar` shows only the last three steps.
  - Each line row has an editable **"คาดถึง"** date. Cards with a missing ETA sort first with a **"ยังไม่มีวันถึง"** warning.
- **Deal → จัดซื้อ-ส่งมอบ:** the same card, read-only for sales, showing step and per-line ETA.
- **Rep notification on an ETA change:** *"ดีล {code}: วันที่คาดว่าสินค้าจากสต็อกกำลังเดินทางจะถึงเปลี่ยนเป็น {date} ({brand model})"*, linking to the deal.

**F8: Sales manager commission approval** (R10)
- The stock-line list appears in her email.
- A **"มีสินค้าจากสต็อก"** chip appears on the ManagerOverview worklist row.
- A **"รายการจากสต็อกในดีลนี้"** block (source badge · brand model · qty · amount) sits above the existing weight select, with the hint *"น้ำหนัก 2 หรือ 3 เท่ามีผลกับค่าคอมของทั้งดีล"*.
- The per-line ×2/×3 button on `DealFulfilmentPanel` is removed (R17).

### 3.4 Naming conventions

| Concept | Label | Notes |
|---|---|---|
| Line source (import) | **สั่งนำเข้า** | The default. |
| On-hand stock | **สต็อกในไทย** | Replaces the bare "จากสต็อก" on inputs. "จากสต็อก" remains only as the umbrella heading ("รายการจากสต็อก…"). |
| In-transit stock | **สต็อกกำลังเดินทาง** | |
| ETA | **วันที่คาดว่าจะถึง** in inputs; **คาดถึง** in compact badges | "คาดถึง" matches the existing `/fulfilment` wording. |
| Import's switch | **เปลี่ยนเป็นสั่งนำเข้า** | |
| All-in-Thailand submit | **ส่งให้ CEO กำหนดราคา** | |
| Stock line's CEO price | **ราคาตั้ง** | |

### 3.5 Component reuse

| Component | Used on | Difference |
|---|---|---|
| `LineSourceSelector` (new) | deal line, PCR row, direct-QT row | In the PCR row it hides import fields; elsewhere it is record-only. |
| `StockSourceBadge` (new) | PCR panel/detail, CEO card, QT editor, commission block, fulfilment | Shows ETA when in transit. |
| `FactoryProgressBar` (existing) | fulfilment + deal tab | A `steps` prop limits the in-transit card to its last three steps. |
| CEO new-form item card | CEO review | Stock variant with no cost block and a ราคาตั้ง input. |

## 4. Build slices (each test-first, Sonnet implements, Opus reviews; backend before API before frontend)
1. **Backend: pricing-request stock path.**
   - V194 source + ETA columns and the decision-item relax.
   - Routing, including the new IMPORT_REVIEWING → READY_FOR_CEO_REVIEW edge.
   - Readiness predicate, ETA and convert endpoints, factory-quote skip.
   - CEO startReview / update / approve.
   - Quotation copy.
2. **Backend: direct-quotation source, order copy, V183 relax, commission `stockLines` + email.**
3. **Backend: after-order in-transit tracking.** `import_request.request_kind`, creation at order confirmation, per-line ETA update + rep notification, received → on-hand.
4. **API surface:** `hrApi`/`mockApi` mirror, contract test, both digests.
5. **Frontend F1–F3, F6.**
6. **Frontend F4, F5, F7, F8.**
