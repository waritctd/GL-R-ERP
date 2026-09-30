# Slice 2 — Flow A: quotation-first entry, recipient → stage, direct-quotation CTAs

**Status:** SPEC — backend half ready to implement after slice 1; frontend half is a
`hallmark redesign` under the locked `DESIGN.md` (system-managed project, diversification
inverted, every touched file stamped `designed-as-app`).
**Parent:** `INFORMATION_ARCHITECTURE.md` (D1–D7 approved 2026-09-30). Flow A + Flow B + CTA rules.
**Not here:** list/editor/panel linking (slice 1 frontend), numbering (slice 3).

---

## Part 1 — Backend (test-first, real Postgres)

| # | Change | Contract |
|---|---|---|
| S2-B1 | `UpsertDealQuotationRequest.recipientType` (DESIGNER / OWNER / BUYER; **required on create for `DEAL_DIRECT`**, 400 otherwise; editable while DRAFT via update; refused on a PRICING_REQUEST-origin row — its recipient comes from the คำขอราคา). Persisted to the existing `sales.quotation.recipient_type` (today hard-coded `"UNSPECIFIED"` at `DealQuotationRepository:576`). `recipientLabel` derived (Thai). | request field |
| S2-B2 | **Recipient → stage on create and on recipient change**, DEAL_DIRECT only: `TicketService.advanceStageForDirectQuotationRecipient(ticketId, recipientType, actor)` — public wrapper over the existing private `stageForQuotationRecipient` + `autoAdvanceStage` (forward-only, ACTIVE only, event `STAGE_CHANGED` note "อัตโนมัติจากใบเสนอราคา"). Called from `DealQuotationService.create` and from `update` when `recipientType` changed. **Never on a PRICING_REQUEST row** (that route advances at issue, unchanged). | behaviour |
| S2-B3 | **N6** — `DealQuotationService.create` on a ticket that already has a *live* DEAL_DIRECT quotation (DRAFT / PENDING_APPROVAL / APPROVED) → 409 whose body names the live quotation (`liveQuotationId`, `number`, `docStatus`) so the client can offer "แก้ไขฉบับนั้น" / "สร้างฉบับแก้ไข (-n)". Message: "ดีลนี้มีใบเสนอราคาตรงที่ใช้งานอยู่ ({number}) — แก้ไขฉบับนั้น หรือสร้างฉบับแก้ไขแทนการออกเลขใหม่". Reuses slice 1's `DirectQuotationLocks` predicate. | behaviour (409 + structured body) |
| S2-B4 | `TicketSummaryDto.liveDirectQuotation` — `{ id, number, docStatus, recipientType }` or null — filled in `TicketRepository.enrichSummary` for list rows **and** `findById` (one query per row, same shape as `hasRecordedCommission`; or a LEFT JOIN LATERAL if the list query is already joining — implementer's call, must cite). Picks the newest live DEAL_DIRECT row. This is what the CTA cascade keys on. | DTO field |
| S2-B5 | `confirm-order` (slice 1) unchanged. Backfilled quotation-first ghosts: NO data migration invents a stage (IA §7) — the rep sets ผู้รับ on the quotation and S2-B2 moves it. | — |
| — | No migration (all columns exist). Digests regenerated if any operation/field changes. | |

**Coverage list (write first, run red, then implement):**
- `DealQuotationRecipientStageIntegrationTest` (new): create DEAL_DIRECT with DESIGNER → deal at `QUOTE_DESIGN_SIDE`; OWNER → `QUOTE_OWNER`; BUYER → `QUOTE_BUYER`; create on a deal already at `NEGOTIATION` → stage **unchanged** (forward-only); update DRAFT recipient DESIGNER→OWNER moves S4→S5; BUYER→DESIGNER leaves S8; recipient change on PENDING_APPROVAL → 409; `recipientType` missing on a direct create → 400; `recipientType` sent on a PRICING_REQUEST-origin update → 409; **wrong-way-round**: an ON_HOLD deal is not advanced; a non-owner `sales` cannot create at all (403, pre-existing); `STAGE_CHANGED` event written with the auto note.
- N6: second `create` on a ticket with a live direct quotation → 409 with `liveQuotationId` = the live one; allowed again after that quotation is CANCELLED; allowed when the only prior quotation is SUPERSEDED.
- `TicketListLiveDirectQuotationIntegrationTest` (new): list row carries `liveDirectQuotation` for a deal with a DRAFT direct quotation; null for none; the **newest** live one when two exist (revision chain); cancelled ones ignored; `findById` carries the same.
- Mutation checks: (a) remove the `autoAdvanceStage` call → the three stage tests red; (b) drop the N6 check → its 409 test red; (c) return null from the enrichment → the list test red.

---

## Part 2 — Frontend: `hallmark redesign` under `DESIGN.md`

### Hallmark pre-flight (system-managed project)
`DESIGN.md` detected at project root — locked system: genre **modern-minimal**, app-page
macrostructure **Workbench** (existing stamp on `TicketDetailPage.jsx`), Sarabun-only type,
indigo = action, teal = live, flat desk, `--radius-md` everywhere, modal is a last resort,
Thai load-bearing verbs, ≥44 px touch targets. **No theme pick, no diversification** — consistency
wins. Every touched file gets the stamp
`/* Hallmark · genre: modern-minimal · macrostructure: Workbench · design-system: design.md · designed-as-app */`
(TicketDetailPage already carries it — append a conformance line). One combined
`.hallmark/log.json` entry, `"scope": "app"`.

### Files (stated before any edit — safety rail)
**Modify:** `features/quotations/DealCustomerCard.jsx` · `features/quotations/QuotationEditorPage.jsx`
· `features/quotations/quotationMeta.js` · `features/tickets/TicketDetailPage.jsx`
· `features/tickets/salesActions.js` · `features/tickets/workState.js`
· `features/dashboard/SalesOverview.jsx` · `features/tickets/DealHistoryPanel.jsx`
· `api/hrApi.js` (payload passthrough only) · `api/mockApi.js` (mirrors of S2-B1…B4) · `utils/format.js` (recipient label helper if none fits).
**Create:** `features/quotations/DealPicker.jsx` (the "เลือกดีลที่มีอยู่" combobox — additive, wired
through the existing `/quotations/new` route). **Delete:** none.

### A. `/quotations/new` — step 1 becomes "ดีล"
Panel title **ดีล** (was "ลูกค้าและโครงการ"). Top row: a two-option segmented choice using the
app's existing `aria-pressed` pill buttons (the same component voice as ช่องทางรับงาน — indigo
tint when selected, `--radius-md`, 38/44 px):

```
[ ◉ เลือกดีลที่มีอยู่ ]  [ ○ สร้างดีลใหม่ ]
```

- **เลือกดีลที่มีอยู่** → `DealPicker`: one combobox (`role="combobox"` + `listbox`, keyboard
  pattern copied from the โครงการ typeahead in `DealCustomerCard`) over `api.tickets.list()` rows
  (already server-scoped: sales = own deals). Filters client-side on `code · customerName ·
  projectName`. Each option: `code` (mono) · customer (bold) · project (muted) · stage
  `StatusBadge`. Picking one sets `?ticket=<id>` on the URL (`setSearchParams`) so the page
  becomes the existing `?ticket=` path — no second code path. If that deal has a
  `liveDirectQuotation`, show an **inline info notice** (not a modal): "ดีลนี้มีใบเสนอราคาตรงที่ใช้
  งานอยู่ — {number} · {status}" with `[เปิดใบเสนอราคา]` and, when APPROVED, `[สร้างฉบับแก้ไข]`;
  the create button is disabled with that reason (DESIGN.md §14: a disabled action explains why).
- **สร้างดีลใหม่** → today's card (customer typeahead · project typeahead · ช่องทางรับงาน), unchanged.
- **ผู้รับใบเสนอราคา*** (both branches, below the deal choice, above ช่องทางรับงาน): three pill
  radios — `ผู้ออกแบบ` · `เจ้าของโครงการ` · `ผู้ซื้อ / ผู้รับเหมา` — required; validation error in the
  same `FormField` error slot. Helper line under it (13 px, `--color-text-muted`):
  "ดีลจะอยู่ที่ขั้น {stage label}" updating live with the choice (e.g. *ดีลจะอยู่ที่ขั้น เสนอราคา
  ผู้ออกแบบ*). On the `?ticket=` path the radio is **preselected from the deal's stage** when it is
  S4/S5/S8 and left empty otherwise. Order of options = S-order (S4, S5, S8).
- Copy is Thai-first; no S-codes in the UI (the stage label is the text; DESIGN.md §18).

### B. Editor header strip (minimal, needed to make the stage effect visible)
Directly under `PageHeader`, one flat row (no card-in-card — it sits on the page, not in a Panel):
`ดีล <code mono> · <customerName> · <StatusBadge: stage label> · [เปิดดีล]` (text button, indigo).
Hidden while inline-creating (no deal yet). Uses `quotation.ticketCode` / `dealStage` (slice 1
DTO) or the `?ticket=` summary. `เปิดดีล` hidden when the role cannot read tickets.

### C. Recipient on an existing DRAFT (`:id` path)
Same three pill radios inside **ข้อมูลลูกค้าและผู้ขาย**, editable only while DRAFT and only for
`DEAL_DIRECT`; a PRICING_REQUEST row shows the value read-only with "จากคำขอราคา". Changing it
saves through `update` (server re-applies the forward-only stage).

### D. Deal page — Flow B route choice (inline, not a dialog)
DESIGN.md §16: a modal is a last resort. So the ใบเสนอราคา panel header gets **two sibling
buttons** instead of a dialog:
`[สร้างคำขอราคา]` (primary — the existing action) and `[ใบเสนอราคาตรง]` (secondary), with one
helper line under the panel title: "ผ่านคำขอราคา = import → โรงงาน → CEO กำหนดราคา · ใบเสนอราคาตรง =
กรอกราคาเอง แล้วส่ง ผจก./CEO อนุมัติ". `ใบเสนอราคาตรง` links to `/quotations/new?ticket=:id`
(recipient preselected from stage). It is **hidden while a live direct quotation exists** (the
panel row for that quotation carries `[เปิด]` instead — N6 by construction). Gate:
`canCreateDealQuotation(user, deal)`.

### E. Sticky CTA + worklist — three new cascade buckets (`salesActions.js`)
Placed **before** CREATE_PCR (a deal with a live direct quotation is being priced by hand; it must
never be told to open a คำขอราคา):

| Key | When (`deal.liveDirectQuotation.docStatus`) | Label | Rank |
|---|---|---|---|
| `SUBMIT_DIRECT_QUOTATION` | `DRAFT` | ส่งขออนุมัติใบเสนอราคา | 2 (with ISSUE_QUOTATION) |
| `AWAIT_DIRECT_APPROVAL` | `PENDING_APPROVAL` | รออนุมัติใบเสนอราคา | 5 (waiting) |
| `CONFIRM_ORDER_DIRECT` | `APPROVED` | ยืนยันคำสั่งซื้อ | 1 (with CONFIRM_ORDER) |

`workState.js`: SUBMIT → navigate to `/quotations/:id`; CONFIRM_ORDER_DIRECT → the existing
confirm dialog (same copy as the editor's, calling `dealQuotations.confirmOrder`); AWAIT is a
**waiting** state for `sales` → rendered as the header's `bannerText` ("รอ ผจก.ขาย/CEO อนุมัติ
ใบเสนอราคา {number}"), not a button; for `sales_manager`/`ceo` the same bucket resolves to the
action `อนุมัติใบเสนอราคา` → `/quotations/:id`. `SalesOverview` badge tones: SUBMIT / CONFIRM =
warning (mine to act), AWAIT = neutral (waiting), per DESIGN.md §15.

### F. History label
`DEAL_PROMOTED_FROM_QUOTATION` → `ยืนยันคำสั่งซื้อจากใบเสนอราคา`; `STAGE_CHANGED` with the auto
note renders as today.

### Slop / conformance checks before hand-back
Pre-emit critique scored (P/H/E/S/R/V ≥ 3); no new colours or fonts (tokens only); no
card-in-card; no side-stripe; radio pills ≥44 px on mobile; every control has `:focus-visible`;
Thai on every load-bearing verb; 320/375/414/768 px pass with no horizontal scroll; `mobileCard`
untouched (no new tables). Screenshots: desktop 1366 + mobile 390 of `/quotations/new` (both
branches), the editor with the header strip, the deal page ใบเสนอราคา panel, and the sticky CTA
in each of the three states.

### Tests (write first)
`DealPicker.test.jsx` (new: filters, keyboard, pick → `?ticket=`, live-quotation notice + disabled
create with reason), `DealCustomerCard.test.jsx` (segmented choice, recipient required, helper
line), `QuotationEditorPage.test.jsx` (recipient sent on create/update; preselect from stage;
header strip; read-only on PRICING_REQUEST), `salesActions.test.js` (three buckets, order vs
CREATE_PCR, ranks), `workState.test.js` (banner vs action per role), `SalesOverview.test.jsx`
(tones), `TicketDetailPage.test.jsx` (two-button header, hidden while live quotation, link carries
`?ticket=`), `DealHistoryPanel.test.jsx` (label), `api/contract.test.js` + `serverContract.test.js`
+ regenerated `ui-reachable.json` if any new hrApi method appears (none expected — `tickets.list`
and `dealQuotations.*` already exist).
