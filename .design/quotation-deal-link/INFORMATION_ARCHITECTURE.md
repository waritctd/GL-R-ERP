# Information Architecture: ใบเสนอราคา ↔ ดีล — one linked system

**Status:** PROPOSAL — awaiting owner approval (Ploy)
**Date:** 2026-09-30
**Scope:** how a quotation and a deal are linked in both directions, which pipeline stage a
quotation puts its deal on, what each surface shows of the other, and the quotation running
number.
**Supersedes in part:** GLA-136 / PR #1083 (merged to `develop` `dfcd1d72` earlier today), which
hid a quotation-first deal from the pipeline until "promoted". Under this design the deal is
visible from the moment the quotation exists; §7 says exactly what of #1083 survives.
**Out of scope:** pricing math, commission, permissions (every gate stays as it is), the
pricing-request chain itself (import → factory → CEO is untouched), payroll/HR.

---

## 1. The problem with today's IA

Two quotation routes write the **same table** (`sales.quotation`, discriminated by `origin`) and
mint from the **same running number** (`sales.quotation_code_seq`), but the screens treat them
as two products:

| Surface | Shows direct (`DEAL_DIRECT`) | Shows pipeline (`PRICING_REQUEST`) | Links to the deal |
|---|---|---|---|
| `/quotations` list | yes | **no** (`WHERE q.origin = 'DEAL_DIRECT'`, `DealQuotationRepository:1409`) | **no** — no deal column, no link |
| `/quotations/:id` editor | yes | yes | **no** (only after "promote", #1083) |
| Deal page → เอกสาร | yes | yes | — |
| Deal stage | **never moves** for a direct quotation | S4/S5/S8 on issue (`stageForQuotationRecipient`) | — |
| Direct editor | has **no ผู้รับ (recipient)** field — rows are stored `UNSPECIFIED` | recipient comes from the คำขอราคา | |

So a rep who starts from `/quotations` gets a quotation with no visible deal behind it, and a
rep who starts from the deal gets a quotation that does not exist on the quotation page. Each
half is right; the two halves do not know about each other.

## 2. Principles

1. **One deal, one place, every document.** Every quotation — either origin, or legacy — is
   reachable from its deal's เอกสาร tab, and every deal is one click from any of its quotations.
2. **The recipient decides the stage.** The existing mapping `ผู้ออกแบบ → S4 เสนอราคาผู้ออกแบบ`,
   `เจ้าของโครงการ → S5 เสนอราคาเจ้าของโครงการ`, `ผู้ซื้อ/ผู้รับเหมา → S8 เสนอราคาผู้ซื้อ/ผู้รับเหมา`
   (`TicketService#stageForQuotationRecipient`) applies to **both** routes. Stage moves are
   monotonic (`autoAdvanceStage`: forward only, ACTIVE deals only) — a quotation can never pull a
   deal backwards.
3. **One running number, no second implementation.** `QT-<year>-<seq>` with `-n` revision
   suffix, minted by one helper, consumed by exactly two engines, with a test that proves it.
4. **Nothing hidden.** A deal that exists is in the pipeline. The concept "quotation-only ghost"
   (#1083) goes away; the column it added becomes provenance, not visibility.

## 3. Site map (what changes, in place — no new routes)

- `/quotations` — list, **all origins** (tabs unchanged: ทั้งหมด / รออนุมัติ / อนุมัติแล้ว / แก้ / ยกเลิก)
  - new column **ดีล** (deal code + customer) → `/tickets/:id`
  - new origin chip per row: `ตรง` · `จากคำขอราคา` · `เดิม` (legacy, read-only)
  - new filter: ที่มา (origin)
- `/quotations/new` — create; **step 1 becomes "ดีล"** (see flow A)
- `/quotations/:id` — editor; new header strip: `ดีล DL-2026-0042 · ลูกค้า · ขั้น S4 เสนอราคาผู้ออกแบบ · [เปิดดีล]`
- `/tickets/:id` → เอกสาร — **one** "ใบเสนอราคา" panel for all origins (today: three panels —
  `DealDirectQuotationPanel`, `DealQuotationPanel`, `DealLegacyQuotations`), with the create
  action restored (see flow B)
- `/tickets` (ดีล list) — quotation-first deals are **listed** (reverses #1083's exclusion)

## 4. User flows

### Flow A — quotation first (`/quotations` → สร้าง)

```
/quotations ─ สร้าง ─► step 1  ดีล
                       ├─ ◉ เลือกดีลที่มีอยู่   [search: ลูกค้า / โครงการ / รหัสดีล]
                       └─ ◉ สร้างดีลใหม่        ลูกค้า* · โครงการ* · ช่องทาง
                       ผู้รับใบเสนอราคา*  ( ) ผู้ออกแบบ  ( ) เจ้าของโครงการ  ( ) ผู้ซื้อ/ผู้รับเหมา
                                │
                       [ถัดไป] ─► deal row created (or reused) — stage := stageFor(recipient), forward-only
                                │  number minted: QT-2026-0021-1
                                ▼
                     step 2  รายการ + เงื่อนไข   (today's editor, unchanged)
                                ▼
                     ส่งขออนุมัติ ─► ผึ้ง/CEO อนุมัติ ─► APPROVED
                                ▼
                     [ยืนยันคำสั่งซื้อ]  (was "สร้างดีลจากใบเสนอราคา")  ─►  S10 ได้รับใบสั่งซื้อ
                                ▼
                     ใบแจ้งรับมัดจำ → นำเข้า/สต็อก → ส่งมอบ → ปิดงาน   (unchanged, ticket-keyed)
```

Decision points inside the flow:
- **Recipient is required** for a direct quotation (today it is silently `UNSPECIFIED`). It is
  the one field that decides the stage, and the PDF already has the เรียน line for it.
- **The deal is visible immediately**, at the recipient's stage, with the sticky CTA reflecting
  the quotation: `DRAFT → "ส่งขออนุมัติใบเสนอราคา"`, `PENDING_APPROVAL → "รออนุมัติ (ผึ้ง/CEO)"`,
  `APPROVED → "ยืนยันคำสั่งซื้อ"`. These are new buckets in `salesActions.js`'s cascade, placed
  between CREATE_PCR and ISSUE_QUOTATION — a deal with a live direct quotation never gets
  "สร้างคำขอราคา" as its CTA.
- **Changing the recipient on a DRAFT** re-applies the mapping, forward only (S4 → S5 moves; S8 →
  S4 does not).
- **ยืนยันคำสั่งซื้อ** is #1083's promote endpoint, renamed and re-gated: it no longer requires
  `quotation_only`; it requires an APPROVED direct quotation on a `draft`-status ACTIVE deal, and
  performs the same bridge write (`draft → quotation_issued`, items from the quotation lines,
  `CUSTOMER_CONFIRMED`, S10). R10 stands: the quotation document itself gets no ACCEPTED status;
  the **deal** records the order (ticket event `DEAL_PROMOTED_FROM_QUOTATION` → relabelled
  `ยืนยันคำสั่งซื้อจากใบเสนอราคา`).

### Flow B — deal first (`/tickets/:id` → เอกสาร → สร้างใบเสนอราคา)

```
เอกสาร ─ สร้างใบเสนอราคา ─► choose route
            ├─ ◉ ผ่านคำขอราคา (import → โรงงาน → CEO กำหนดราคา)   → existing "สร้างคำขอราคา" flow;
            │        the quotation is created from the CEO decision, prefilled, and appears BOTH here
            │        and on /quotations (origin จากคำขอราคา)
            └─ ◉ กรอกราคาเอง (ใบเสนอราคาตรง)                          → /quotations/new?ticket=:id
                     ผู้รับใบเสนอราคา* preselected from the deal's current stage when it is S4/S5/S8,
                     otherwise asked; stage advances forward-only exactly as in flow A
```

The route choice is explicit so a pipeline deal is never priced by hand *by accident* — this
keeps this morning's "the two stay separate" intent at the level that matters (who sets the
price), while linking the documents.

### Flow C — from either side, find the other

- Quotation list row / editor header → **เปิดดีล**.
- Deal เอกสาร panel row → **เปิดใบเสนอราคา** (`/quotations/:id` for both engines; a legacy
  `origin IS NULL` row opens its existing PDF/legacy view — read-only, no editor exists for it).
- Deal timeline already carries `DEAL_QUOTATION_SUBMITTED/APPROVED/REJECTED/…` events — no change.

## 5. Content hierarchy

### `/quotations` (list)
1. Number + status chip + **origin chip** — what document, what state, which route
2. Customer / project + **ดีล** (code, links to the deal) — whose deal
3. Rep, amount, date — as today
4. Filters: status tab (today), rep (today), **ที่มา** (new)

### `/quotations/:id` (editor) — header strip, above the form
1. `ดีล DL-…` + customer + **stage chip** (e.g. `S4 เสนอราคาผู้ออกแบบ`) + `[เปิดดีล]`
2. Number, status, approver line — as today
3. Actions — as today, with `สร้างดีลจากใบเสนอราคา` renamed **ยืนยันคำสั่งซื้อ** (APPROVED direct only)

### `/tickets/:id` → เอกสาร → ใบเสนอราคา (one panel)
1. Live quotation(s) first (DRAFT / PENDING_APPROVAL / APPROVED / ISSUED), each: number, origin
   chip, status, amount, approver, `[เปิด]` `[PDF]` `[Excel]`
2. Superseded / cancelled / rejected below, collapsed
3. Panel action: **สร้างใบเสนอราคา** → flow B's route choice

## 6. The running number (ว่ามันรันถูก) — rules and what changes

**Today (verified in source and in two local DBs):**
- One Postgres sequence `sales.quotation_code_seq`, format `QT-<Year.now()>-<%04d>`; **three**
  consumers: `DealQuotationRepository`, `CustomerQuotationRepository`, and a legacy
  `TicketRepository#nextQuotationCode` (behind the deprecated, routeless `generateQuotation`).
- Suffix: first issue is `-1`, revisions `-2, -3…` on the same base (owner rulings 09-11 and
  09-18); **สั่งเหมือนเดิม (reorder) shares the base too** (owner ruling 09-10: "-n = derived from an
  earlier one — revision OR reorder"). Legacy bare numbers are left alone.
- **The counter never resets per year.** Only the year prefix changes; 2027 would start at
  `QT-2027-0021`.
- **The number is minted at DRAFT creation** (`nextval` inside `create`). A create that fails
  after `nextval`, or a rolled-back transaction, burns a number. Both local DBs already show gaps
  (`missing = 18, 19`; sequence at 19/20 with 17/18 bases used).
- A cancelled draft keeps its number (correct — it appears in the ยกเลิก tab, so the number is
  accounted for).

**Proposed rules (the invariant the code and a test must state):**

| # | Rule | Change needed |
|---|---|---|
| N1 | One running number across **both** engines and **both** entry points; direct and pipeline quotations interleave in one series | none — already true; add `QuotationNumberingIntegrationTest` that creates one of each and asserts consecutive bases |
| N2 | Exactly **one** minting helper; the legacy third consumer is deleted | remove `TicketRepository#nextQuotationCode` (+ dead `generateQuotation` path it serves) |
| N3 | Per-year reset: `QT-2027-0001` on 1 Jan 2027, no renumbering of 2026 | **decision D5** — reuse the existing `sales.document_sequence` (doc_type + year), seeded for 2026 from the current sequence value; effective at year rollover |
| N4 | Minting moment stays **at creation** (a draft has a number the rep can quote on the phone); a cancelled draft keeps it; gaps only from failed creates | none, but add a `ตรวจสอบเลขรัน` line to the CEO console: highest number, count issued, and the list of unaccounted numbers — so "runs correctly" is checkable, not assumed |
| N5 | Revision `-n` and reorder-shares-base rules unchanged | none |
| N6 | A quotation created via flow B (`?ticket=`) for a deal that already has a live direct quotation is a **revision** of it, not a second base | enforce in `create`: if a live DEAL_DIRECT quotation exists on the ticket → offer "แก้ไข (ฉบับ -n)" instead of a new base |

## 7. What happens to GLA-136 / PR #1083

| #1083 piece | Fate |
|---|---|
| V193 `sales.ticket.quotation_only` | **kept as provenance** (rename in a later cleanup: "created from a quotation"); **no longer hides** the deal — `PIPELINE_ONLY` / `PIPELINE_TICKETS_ONLY` filters removed |
| 409 refusals on stage/items/entry-channel/tender/pricing-request for a quotation-only ticket | **stage / entry-channel / tender refusals removed** (the deal is a real deal); **items refusal kept while a live direct quotation exists** (the lines live on the quotation — B5's read-only fallback already shows them on the deal); **pricing-request refusal kept** (a deal with a live direct quotation is priced by hand; a คำขอราคา on top would create the mixed-origin case nobody has ever produced) |
| `POST /deal-quotations/{id}/promote-to-deal` | **kept, renamed** `…/confirm-order` (old path kept as an alias for one release), precondition `quotation_only` dropped |
| Deal-page banner "ยังไม่เข้า pipeline" | removed |
| Removed deal-page create link | **restored**, as flow B's two-route choice |
| Backfilled ghost deals on prod (38+) | they become visible pipeline deals at S1 with recipient `UNSPECIFIED`; the rep sets ผู้รับ on the quotation and the stage follows (forward-only) — no data migration invents a stage |

## 8. Naming conventions

| Concept | Label in UI | Notes |
|---|---|---|
| Direct quotation | ใบเสนอราคาตรง / chip `ตรง` | sales-priced, ผึ้ง/CEO approve the document |
| Pipeline quotation | ใบเสนอราคาจากคำขอราคา / chip `จากคำขอราคา` | CEO-priced via คำขอราคา |
| Legacy (origin NULL) | chip `เดิม` | read-only |
| Recipient | ผู้รับใบเสนอราคา: ผู้ออกแบบ / เจ้าของโครงการ / ผู้ซื้อ/ผู้รับเหมา | same three as pricingRequestMeta.js; UNSPECIFIED never offered |
| Customer ordered (direct) | ยืนยันคำสั่งซื้อ | replaces "สร้างดีลจากใบเสนอราคา" |
| Deal link | เปิดดีล | everywhere, one verb |

## 9. Component reuse map

| Component | Used on | Change |
|---|---|---|
| `QuotationEditorPage` step 1 | `/quotations/new` | becomes the "ดีล" step: pick-or-create + recipient |
| `DealDirectQuotationPanel` + `DealQuotationPanel` + `DealLegacyQuotations` | deal เอกสาร | merge into one `DealQuotationsPanel` (all origins) |
| `salesActions.js` cascade | deal page sticky CTA, ดีล list | three new buckets for a live direct quotation |
| `stageForQuotationRecipient` + `autoAdvanceStage` | `TicketService` | reused verbatim by direct create / recipient change |
| `QuotationNumbering` | both engines | unchanged; becomes the only minter (N2) |
| `DealStateHeader` stage chip | editor header strip | reused for the deal's current stage |

## 10. URL strategy

No new routes. `/quotations/new?ticket=:id` keeps working and is the flow-B entry. `/quotations`
gains `?origin=` (`direct|pricing_request|legacy`) alongside the existing status/rep params.

## 11. Decisions requested from the owner

| # | Question | Recommendation |
|---|---|---|
| D1 | When does the recipient move the stage — at quotation **creation** or at **approval**? | **Creation.** "I'm quoting the designer" *is* S4 for the sales team; the PR route advances at issue because the CEO round-trip sits in between, which direct has no equivalent of. |
| D2 | Deal-page create restored with the explicit **two-route choice** (ผ่านคำขอราคา / กรอกราคาเอง)? | **Yes.** Keeps "who sets the price" separate while the documents are linked. |
| D3 | Is `ยืนยันคำสั่งซื้อ` on a direct quotation the rep's action (as today's promote), with no customer-signature capture? | **Yes** — R10 stands; the signed PDF/PO can be attached on the deal's เอกสาร tab as today. |
| D4 | Legacy (`origin IS NULL`) quotations on `/quotations`: show read-only, or leave them deal-page-only? | **Show, read-only** — "everything linked" includes history. |
| D5 | Running number resets per year (`QT-2027-0001`)? | **Yes**, via the existing `sales.document_sequence`; 2026 numbers untouched. |
| D6 | The `ตรวจสอบเลขรัน` console line (N4)? | **Yes** — cheap, and it is how "รันถูก" gets verified after every deploy. |
| D7 | Second direct quotation on a deal that already has a live one = revision, not new base (N6)? | **Yes**. |

## 12. Not in this design

- No change to who may create/edit/approve quotations or deals (`requireEditAccess`,
  `DealEntryAccess`, `SALES_ROLES` all as today).
- No change to the คำขอราคา chain, the CEO pricing screen, factory RFQ, deposit/IR/delivery/close.
- No renumbering of any existing quotation.
- The `qc`-role grantee landing (flagged in #1083) is folded into flow A's editor header (`เปิดดีล`
  is hidden when the role cannot read tickets) rather than a redirect.
