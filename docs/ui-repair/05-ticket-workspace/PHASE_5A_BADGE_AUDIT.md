# Phase 5A Badge Audit

Date: 2026-07-25

Scope: `/tickets/:id` ticket workspace and child workspace panels:

- `TicketDetailPage.jsx`
- `DealStateHeader.jsx`
- `DealStagePanel.jsx`
- `DealTrackingPanel.jsx`
- `DealQuotationPanel.jsx`
- `DealDepositPanel.jsx`
- `DealFulfilmentPanel.jsx`
- `PricingRequestPanel.jsx`

This audit covers both shared `StatusBadge` usage and badge-like hand-rolled pills such as
`rounded-full` step chips and role tags.

## Badge Rule

A badge represents a short state.

Do not use badges for:

- Long explanations.
- Ordinary metadata.
- Labels already present in a heading.
- Every step in a sequence.
- Decorative emphasis.
- Counts or raw facts that are better as text.

Ticket workspace rules:

- One primary stage badge in the persistent header.
- One work-state badge or alert.
- Local record badges only when the sub-record has its own state.
- Do not repeat `กำลังดำเนินการ` at multiple levels.
- Long waiting messages belong in `InlineAlert` or plain text, not pill badges.
- Use text plus icon where colour alone is insufficient.

## Approved Badge Homes

| Home | Allowed Badge | Notes |
|---|---|---|
| Persistent command header | Current stage badge | The only primary stage badge. |
| Persistent command header | Work-state badge or alert | If the state needs explanation, use `InlineAlert` or text next to the badge. |
| Pricing row | Pricing-request status | The request is an independent sub-record. |
| Quotation row | Quotation document status | Keep separate from customer outcome. |
| Deposit/process row | Deposit policy, deposit document, or deposit payment state | Short row-local state only. |
| Factory PO row | Factory PO status | The PO is an independent external record. |
| Activity filters, if added | Selected filter count or active filter label only where interactive | Prefer segmented controls/text; do not badge every event type. |

Everything else defaults to plain text, description-list value, inline alert, ordered-step row,
or table/list cell.

## Badge Inventory

| Component | Current Badge / Pill | Purpose | Problem | Decision | Replacement |
|---|---|---|---|---|---|
| `DealStateHeader` | Lifecycle `StatusBadge` (`กำลังดำเนินการ`, hold/lost/etc.) | Lifecycle state | Repeats at body/stage level and can duplicate work-state. `กำลังดำเนินการ` is low-value in active deals. | REMOVE DUPLICATION | Show lifecycle as plain compact metadata for active deals. Use alert/terminal treatment only for hold, lost, cancelled, completed. |
| `DealStateHeader` | Terminal work-state `StatusBadge` next to lifecycle | Terminal state | Can create two adjacent lifecycle/work-state pills. | REMOVE DUPLICATION | Keep one terminal state treatment: either work-state badge/alert or lifecycle terminal text, not both. |
| `DealStateHeader` | Current stage `StatusBadge` | Current stage | Legitimate primary stage state. | KEEP AS BADGE | Keep as the single primary stage badge in the header. |
| `DealStateHeader` | Work-state `StatusBadge` | Viewer-specific current work state | Legitimate if short; explanation already appears below. | KEEP AS BADGE OR INLINE ALERT | Keep one work-state badge for short labels. Use `InlineAlert` when blocker/returned/waiting reason is long. |
| `DealStateHeader` | Blocker/deadline detail values | Current blocker/freshness | Currently plain description values; must not become badges. | KEEP AS TEXT | Keep in compact description/metadata strip with icon/text where needed. |
| `DealStagePanel` | `PricingRequestSummaryStrip` substep chips | Pricing journey status | Uses a chip for every step in a sequence. | CONVERT TO STEP ROW/TEXT | Overview gets one compact pricing row. Full sequence stays behind `ดูขั้นตอนทั้งหมด` or Pricing tab detail. |
| `DealStagePanel` | `StatusBadge` `เฟส X` | Phase label | Phase is context, not state; stage badge already lives in header. | CONVERT TO TEXT | Render as `เฟส X` plain metadata next to current stage. |
| `DealStagePanel` | Gate label pill (`การเข้าถึงโครงการ`, etc.) | Stage-group metadata | Ordinary metadata styled as a pill. | CONVERT TO TEXT | Render as muted stage context text. |
| `DealStagePanel` | Next-stage rounded pill | Immediate next stage | Decorative emphasis and duplicated stage context. | CONVERT TO ROW | Render previous/current/next context as plain text row. |
| `DealStagePanel` | Payment status `StatusBadge` | Payment substate | Duplicates Money tab and header blocker when overdue. | REMOVE DUPLICATION | Overview workflow summary gets one payment row; Money tab owns detailed payment state. |
| `DealStagePanel` | Overdue `StatusBadge` | Blocking payment freshness | Duplicates header work-state/blocker. | CONVERT TO INLINE ALERT | Header owns global overdue/blocker. Overview may link to Money as text/alert. |
| `DealStagePanel` | Deposit policy `StatusBadge` | Deposit policy | Local state, but shown inside pipeline as another chip. | MOVE TO SUMMARY ROW | Overview workflow summary or Money/Fulfilment row owns it. Use badge only in owning row if short. |
| `DealStagePanel` | Import `SubstepChips` | Fulfilment sequence | Chip for every import step. | CONVERT TO STEP ROW/TEXT | Fulfilment tab owns ordered step list with one current emphasis. Overview gets one summary row. |
| `DealStagePanel` | Delivery progress `StatusBadge` (`0 / 200`) | Quantity progress | Numeric progress is not a state. | CONVERT TO TEXT | Use progress text/bar or table/list value. |
| `DealStagePanel` | Completed-deal success block | Terminal state | Not a badge, but acts like large status emphasis. | CONVERT TO INLINE ALERT | Use terminal inline alert or header terminal state; avoid repeated success surfaces. |
| `PricingRequestPanel` | Pricing request status `StatusBadge` | Independent request state | Legitimate local record status. | KEEP AS BADGE | Keep one status badge on the request row. Do not repeat same state in expanded body. |
| `PricingRequestPanel` | Quantity type `StatusBadge` | Item quantity type | Ordinary item metadata, not state. | CONVERT TO TEXT | Render as plain table/list cell. |
| `PricingRequestPanel` | Request action row buttons | Actions | Not badges; ensure they do not imitate status pills. | KEEP AS ACTIONS | Keep row-local secondary actions; no status colouring. |
| `DealQuotationPanel` | Pricing request status badge in panel heading | Parent request state | Duplicates Pricing tab/request row and header metadata. | REMOVE DUPLICATION | Show as plain context text or link to Pricing Request detail. |
| `DealQuotationPanel` | Current quotation `docStatus` badge | Quotation document state | Legitimate local document state, but currently raw enum. | KEEP AS BADGE | Keep one Thai-labelled document-status badge per row/document. |
| `DealQuotationPanel` | Customer outcome state | Customer accepted/rejected decision | Distinct from document state; may be short state. | KEEP SEPARATE | Use separate short badge or decision row; never merge with doc status. |
| `DealDepositPanel` | `StepRoleTag` owner pill | Responsible role | Role metadata, not state. | CONVERT TO TEXT | Render as plain owner text in the process row. |
| `DealDepositPanel` | Step number circle | Ordered step marker | Not a state badge. | KEEP AS STEP MARKER | Keep as step marker if it supports ordered process. |
| `DealDepositPanel` | Deposit policy `StatusBadge` | Deposit policy state | Legitimate row-local state if short. | KEEP AS LOCAL BADGE | Keep one badge on deposit policy row; reason stays plain text/alert. |
| `DealDepositPanel` | Deposit notice status `StatusBadge` | Deposit document state | Legitimate document row state. | KEEP AS LOCAL BADGE | Keep one badge on the notice row. |
| `DealDepositPanel` | `รับมัดจำแล้ว` `StatusBadge` | Deposit payment completion | Legitimate completed row state, but may duplicate Money if both visible. | KEEP LOCAL OR TEXT | Keep only inside owning deposit/payment row; Overview should summarize once. |
| `DealFulfilmentPanel` | `StepRoleTag` owner pill | Responsible role | Role metadata, not state. | CONVERT TO TEXT | Render as plain owner text. |
| `DealFulfilmentPanel` | Fulfilment status `StatusBadge` in import step | Current fulfilment state | Legitimate inside Fulfilment tab, but duplicates substep chips. | KEEP ONE LOCAL BADGE | Keep one current-state label for the track; remove chip wall duplication. |
| `DealFulfilmentPanel` | Fulfilment `SubstepChips` | Import/procurement sequence | Chip for every step. | CONVERT TO ORDERED STEP LIST | Use ordered step rows with one current emphasis. |
| `DealFulfilmentPanel` | Factory PO status `StatusBadge` | Independent external record state | Legitimate local record status. | KEEP AS BADGE | Keep one status badge per PO row. |
| `DealTrackingPanel` | Readiness `StatusBadge` (`พร้อมเลื่อนสถานะ` / `ยังไม่พร้อม`) | Stage-advance gate | Long enough to need explanation; currently also has an inline warning below. | CONVERT TO INLINE ALERT OR TEXT | Use inline alert when not ready; use plain ready text or no badge when ready. |
| `DealTrackingPanel` | `override` `StatusBadge` | Win-probability source metadata | Metadata, not state; English label. | CONVERT TO TEXT | Render Thai plain text such as `ปรับเอง`. |
| `DealTrackingPanel` | Activity kind `StatusBadge` | Activity type | Type metadata, not state. | CONVERT TO TEXT | Render as timeline type text/icon. Do not badge every activity event. |
| `TicketDetailPage` Money panel | Payment stage `StatusBadge` | Payment state | Legitimate in Money tab but duplicates header blocker/work state if overdue. | KEEP ONE LOCAL STATUS | Move into compact financial summary row; do not also show a heading badge if the row already states it. |
| `TicketDetailPage` Money panel | Overdue `StatusBadge` | Blocking freshness | Duplicates header. | CONVERT TO INLINE ALERT | Header owns global blocker. Money tab may show a compact overdue alert/details row. |
| `TicketDetailPage` legacy quotation rows | `Rev` inline styled label | Revision number | Metadata, not state. | CONVERT TO TEXT | Use a `Revision` table/list cell. |
| `TicketDetailPage` legacy quotation rows | Quotation status `StatusBadge` | Document state | Legitimate local document state. | KEEP AS BADGE | Keep one Thai status badge per quotation row. |
| `TicketDetailPage` `SectionPeek` rounded inset | Role-hidden section notice | Permission/projection hint | Not a badge, but can look like another status surface. | CONVERT TO INLINE ALERT | Use compact role-scope text/alert only when needed. |

## Duplication Hotspots

- `กำลังดำเนินการ` appears as lifecycle context while the header also shows work-state and the
  stage panel repeats operational progress. Active lifecycle should be plain metadata, not a
  prominent badge.
- Payment state appears in the header blocker/work-state, stage panel subchips, Money tab heading
  and financial content. Phase 5 keeps one global blocker in the header and one local payment row
  in Money.
- Fulfilment state appears in the pipeline subchips and Fulfilment tab substep chips. Phase 5
  keeps one Overview summary row and one Fulfilment ordered track.
- Pricing request state appears in Pricing request rows, quotation panel heading and sometimes
  header metadata. Phase 5 keeps one request-row badge and uses text/link elsewhere.
- Activity type badges create a noisy timeline. Phase 5 uses text or icons for event type and
  reserves badges for state.

## Implementation Acceptance

Implementation is not complete until:

- The persistent header has one primary stage badge and one work-state badge/alert at most.
- `กำลังดำเนินการ` is not repeated as a high-emphasis badge at multiple levels.
- Long blocker/waiting/returned explanations render as `InlineAlert` or plain text, not badges.
- Role labels render as plain text, not pills.
- Sequence steps render as ordered rows with one current-step emphasis, not a chip for every step.
- Numeric progress (`0 / 200`, counts, money values) renders as text/table values, not badges.
- Independent sub-record states retain one local badge: pricing request, quotation document,
  deposit document/payment row and Factory PO.
- Customer quotation document status and customer outcome stay separate.
- Every badge remains text-labelled; icons are additive only.
- Badge use is verified in the five Phase 5 viewports and all role projections.
