# Information Architecture: CR-1 — Pricing request, factory-contact flow

Linear: GLA-167 (CR-1). Status: **UX DRAFT, for Ploy's review. Nothing is built.**
Base: `origin/develop`. Page: `frontend/src/features/pricingRequests/PricingRequestDetailPage.jsx`.

## Ploy's rulings (2026-10-01)

| # | Ruling |
|---|---|
| R1 | Currency and หน่วยราคา come from the sales request and are **locked for import**. On the sales create form they are **auto-filled but sales can change them**. |
| R2 | Import may change the **lead time** (ระยะเวลานำเข้า). The change needs approval from the **owning rep or a sales manager**. It does **not block** the request: it can still go to the CEO, who sees a "pending" badge. |
| R3 | The flow is **per factory**, not per brand: **สร้างเมล** → modal with only **บันทึกร่าง / คัดลอกเมล** → a separate **ติดต่อโรงงานแล้ว** status button. That button unlocks price entry. |
| R4 | Existing quotes already marked "ส่งแล้ว" count as **ติดต่อโรงงานแล้ว**, and the sent date becomes the contacted date. |
| R5 | **Import and the CEO** can both mark a factory as contacted. |
| R6 | There is **one IR per factory**: a deal with three factories has three IRs. **Sales generates the IR** (owning rep or CEO), with the **สร้างใบ IR** button, once the quotation is approved. Whenever sales changes something afterwards (an approved lead-time change, a revised quotation), **sales regenerates the IR**. Import never creates one. |
| R7 | **No undo** of ติดต่อโรงงานแล้ว. |
| R8 | **Keep** the factory master's email field. It pre-fills ถึง in the สร้างเมล modal. |
| R9 | Import **receives the IR automatically**: no hand-off. Import downloads the PDF from the **/fulfilment** งานนำเข้า page. The IR is also listed on the deal **เอกสาร** tab and on the pricing-request factory card. |
| R10 | Lead-time changes use **option C**. The request is raised once per factory, and every line is pre-ticked with the new min/max. Import can untick a line or give it its own value. Sales approves or rejects the request **once, as a whole**. |

## Site map (no new routes)

- Pricing request detail `/pricing-requests/:id` — **changed** (factory section)
- Deal detail `/tickets/:id` → tab **เอกสาร** — **changed** (new ใบขอซื้อ section)
- Import deal page `/import/deals/:id` — unchanged; it already lists the IR for each factory
- Sales create modal (`PricingRequestCreateModal`) — **changed**: adds currency and price unit to each line
- Notification: a lead-time change request goes to the owning rep and to sales managers

## Content hierarchy: the factory section (import and CEO)

Section title: **ราคาจากโรงงาน**, with one card per factory.

1. **Card header.** Contents: factory name, country, number of lines, and a **status chip**. The chip reads ยังไม่ติดต่อ, then ติดต่อแล้ว 1 ต.ค., then ยืนยันราคาแล้ว. It comes first because it answers "what do I do next with this factory".
2. **Header actions.** Contents: **สร้างเมล** (secondary) and **ติดต่อโรงงานแล้ว** (primary, until pressed).
3. **Terms strip (read-only).** Contents: `EUR · ต่อ ตร.ม.`, with the hint "ตามคำขอของฝ่ายขาย" and a 🔒 icon. It sits above the grid because it tells import how to read the price column.
4. **Price grid.** One row per line: product, qty, **ราคาต่อหน่วย** (input), **ระยะเวลานำเข้า** (value plus ✎ ขอเปลี่ยน), and หมายเหตุราคา. Before the factory is contacted, the price inputs are disabled with the hint "กด ติดต่อโรงงานแล้ว ก่อนกรอกราคา".
5. **Footer.** Contents: ยืนยันราคาเสนอ, and the contacted note ("โทรคุณ Marco…").
6. **ใบขอซื้อ row.** This appears only once fulfilment stage 1 has started. Contents: `IR-2026-0012 · ฉบับร่าง · ดาวน์โหลด PDF`.

## User flows

### F1: Contact a factory and enter its price (import or CEO)
1. Request is in รับเรื่อง. Each card shows ยังไม่ติดต่อ, and its price inputs are locked.
2. *(optional)* **สร้างเมล** opens a modal with To (pre-filled from the factory master), Subject, Body, and attachments ticked.
   - The modal has two buttons: **บันทึกร่าง** and **คัดลอกเมล**. There is no ส่งแล้ว.
   - Closing the modal changes no status.
3. **ติดต่อโรงงานแล้ว** opens a small popover with a date (default today) and an optional note. Press ยืนยัน.
   - The chip becomes "ติดต่อแล้ว 1 ต.ค. · โดย ฝ้าย" and the price inputs unlock.
   - On the first factory, the request moves to เจรจาราคากับโรงงาน (AWAITING_FACTORY_RESPONSE, as today).
   - **No undo** (R7). The status is final once confirmed.
4. Import enters prices and presses **ยืนยันราคาเสนอ**. This works as today: when every factory is confirmed, the request goes to the CEO.

### F2: Import changes lead time for a factory (R2, R10 = option C)
1. On the factory card, **✎ ขอเปลี่ยนระยะเวลานำเข้า** opens a panel.
   - At the top: new min/max days, plus a required reason.
   - Below: the factory's lines, each **pre-ticked** with the new value. Import can untick a line or type its own min/max.
2. Import submits. The ticked lines show `75–90 → 120–150 วัน · รอฝ่ายขายอนุมัติ` in amber.
   - There can be only **one pending request per factory**. Import can edit it or withdraw it while it is pending.
3. The rep and sales managers get a notification. The request page shows them one banner listing the whole request, with [อนุมัติ] and [ไม่อนุมัติ (ต้องระบุเหตุผล)].
   - **อนุมัติ:** the new values become the lines' lead times, and the old values are kept in the history.
   - **ไม่อนุมัติ:** the old values stay, and import sees the reason.
4. Nothing is blocked. The CEO can review and approve the price while a request is pending, and sees an amber badge.
5. *(Assumed defaults, to confirm)*
   - Until approval, the quotation uses the **old** value.
   - A request that is approved after the quotation is issued updates the IR only. It does not revise the issued quotation.
   - A **shorter** lead time follows the same approval flow.

### F3: IR per factory (R6, R9)
1. The CEO approves the price, the quotation is issued and accepted, and the deal reaches fulfilment stage 1.
2. The **owning rep or CEO** presses **สร้างใบ IR** (deal page). One IR is created per factory; the PDF downloads straight away.
3. Import sees each IR at once: the /fulfilment งานนำเข้า card gets a **ดาวน์โหลด PDF** action, and the pricing-request factory card shows `IR-2026-0012 · ฉบับร่าง · PDF` (read-only for import).
4. When sales later approves a lead-time change or revises the quotation, sales presses **สร้างใบ IR** again. That makes a new revision, and import downloads the new PDF.
5. The **/fulfilment** งานนำเข้า card shows the same IR (it already reads import_request rows).
6. The deal page's **เอกสาร** tab gets a 5th section, **ใบขอซื้อ (รายโรงงาน)**, with one row per IR: number, factory, status, and PDF.

### F4: Sales creates the request (R1)
- Each line gets **สกุลเงิน** and **หน่วยราคา**.
  - For a catalogue line, both are auto-filled from the catalogue or factory.
  - For a hand-typed line, they are auto-filled from the chosen factory's default currency/unit, or left blank and required.
- Sales can change them until submit. After submit they are locked for everyone except through a revision.

## Naming

| Concept | UI label | Notes |
|---|---|---|
| Mark factory contacted | **ติดต่อโรงงานแล้ว** | Replaces ส่งแล้ว everywhere |
| Generate email | **สร้างเมล** | Replaces สร้างร่างอีเมล / ร่างอีเมล / ดูอีเมล |
| Modal buttons | **บันทึกร่าง**, **คัดลอกเมล** | Only these two |
| Lead time | **ระยะเวลานำเข้า** | Same as today's read-only label |
| Pending change | **รอฝ่ายขายอนุมัติ** | |
| Purchase request | **ใบขอซื้อ** | Same as the deal page |

## What this changes underneath (stated, per CLAUDE.md)

- **Schema (forward-only Vnnn):**
  - `factory_quote` gets `contacted_at`, `contacted_note` and `contacted_by`. Existing REQUESTED+ rows are backfilled from their sent date (R4).
  - `pricing_request_item` gets currency and price unit.
  - A new lead-time change-request table (pending/approved/rejected, with reason).
- **API:** new endpoints for contact and uncontact, and for the lead-time change request with approve/reject. `send` is retired or aliased. Currency/unit writes on the factory quote are refused.
- **Authz:** contact is allowed for import and the CEO. Lead-time approval is allowed for the owning rep and sales managers. IR creation stays with the rep and CEO (no change); the IR download opens to import on /fulfilment and the pricing-request page. Each of these needs a real-DB integration test.
- **Frontend:** the factory section of `PricingRequestDetailPage`, `FactoryEmailDraftModal`, the create modal, and `DealDocumentRegister` (the IR section).

## Open questions

- None. Rulings closed 2026-10-01. Branch off `develop` (ruled 2026-10-01 after seeing develop is 105 commits ahead of main on these files); migrations start at **V196** (V195 is claimed by stock-lines slice 2).

## Backend rulings (Ploy, 2026-10-01)

- **B-R1:** the CEO may also `generateDrafts` and `updateDraft` (the email draft), not only mark contacted. This widens authz.
- **B-R2:** the backend refuses `receive` (price entry) on a quote that has not been marked contacted, returning 409. Today it is accepted.
- **B-R3:** the CEO may NOT approve or reject lead-time changes. Only the owning rep and sales managers can.
- **B-R4:** the IR takes its lead time from the deal's **new sales quotation**, not straight from the pricing-request lines. The chain is:
  1. The lead-time change is approved.
  2. The pricing-request lines are updated.
  3. Sales issues a new quotation.
  4. Sales creates or revises the IR.

  Only step 4 picks up the value. An approved change without a new quotation does not change the IR.
