# Information Architecture: การเงิน tab redesign (GLA-129 step 4, part 2/3)

## Site Map / Navigation Model / URL Strategy

Unchanged. Same route (`/tickets/:id?tab=money`), same tab, no new sub-routes or query params. This
is a content-structure restructuring within an existing tab, not a new page.

## Content Hierarchy

### การเงิน tab (`money` TabPanel, `TicketDetailPage.jsx`)

1. **Status strip** (new, `DealMoneyStatusStrip`) — the fastest possible read of "is this deal's
   billing on track," first because it answers the question a returning user asks before anything
   else: has this moved since I last looked.
2. **Document pipeline** (new, `DealDocumentPipeline`) — the four-stage sequence with the next
   action highlighted. Comes second because it's the map that tells the user *which* of the cards
   below to look at.
3. **`DealDepositPanel`** (existing, unchanged) — the first two pipeline stages' actual controls
   (deposit policy, deposit notice, deposit payment). Directly below the pipeline that points at it.
4. **Remaining-invoice summary card** (new) — the third stage's control. Same reasoning: sits where
   the pipeline points, right after the deposit panel since it's the next stage in sequence.
5. **`DealMoneyTimeline`** (existing, unchanged) — the payment receipt log. Last, because it's a
   historical record a user consults for detail/verification, not the primary "what do I do next"
   surface the four items above serve. (Note: this reorders `DealMoneyTimeline` from its CURRENT
   position — today it renders first, above `DealDepositPanel`. Moving it to last is part of this
   redesign's own reordering, not an oversight; "keep การชำระเงิน timeline as-is" in the owner
   ruling means keep the *component* untouched, not keep its position.)

## Naming Conventions

| Concept | Label in UI | Notes |
|---|---|---|
| Deposit notice | ใบแจ้งมัดจำ | Existing term, `DealDepositPanel`'s own step 2 label is "ใบแจ้งยอดมัดจำ" — pipeline uses the shorter form per the GLA-129 ticket text; both refer to the same document. |
| Remaining invoice | ใบแจ้งหนี้ส่วนที่เหลือ | Existing term, `RemainingInvoiceDialog`'s own header. |
| Billing note | ใบวางบิล | Existing term, GLA-99/GLA-129's own name throughout. |
| Final settlement | รับชำระครบ | New pipeline-only label — no existing document/component uses this exact string; it's the pipeline's terminal state, not a document. |
| Payment history panel | การชำระเงิน | Existing `DealMoneyTimeline` panel title, unchanged. |
| Billing date | วันวางบิล | New, status strip only. |
| Due date | ครบกำหนด | New, status strip only. |
| Next follow-up | ติดตามครั้งถัดไป | New, status strip only. |

## Component Reuse Map

| Component | Used on | Behavior differences |
|---|---|---|
| `StatChip` shape (from `DealStateHeader.jsx`) | `DealMoneyStatusStrip` (new) | Pattern copied, not imported — `StatChip` is module-private to `DealStateHeader.jsx`. Same `label`/`value`/`tone` shape, same `<dl>` grid convention. |
| Step-card shape (from `DealDepositPanel.jsx`: `StepNumber`, `StepRoleTag`, bordered card) | `DealDocumentPipeline` (new) | Pattern copied, not imported — both are module-private. `DealDocumentPipeline` has exactly 4 fixed steps (no accordion/collapse, unlike `DealStageStepper`). |
| `RemainingInvoiceDialog` | Remaining-invoice summary card (new) | Reused via the SAME external-boolean-state pattern already used elsewhere in `TicketDetailPage.jsx` (`remainingInvoiceDialogOpen`) — no changes to the dialog itself, just a second entry point into it. |
| `sections.payment` / `sections.depositNotice` (`salesViewScope.js`) | `DealMoneyStatusStrip`, `DealDocumentPipeline` | Reused as the existing gating vocabulary — no new section flag added. The ใบวางบิล step's link-vs-text-only behavior is a per-user check (`account`/`ceo`/`user.canIssueBillingNote`), not a new section flag, since it's about ONE step's affordance, not the whole tab's visibility. |
| `DealDepositPanel`, `DealMoneyTimeline` | Money tab | Unchanged components, only their position/surrounding content changes. |

## Content Growth Plan

Unchanged growth patterns: the pipeline's per-step status recomputes from live data on every render
(no accumulation of its own); `DealMoneyTimeline` remains the unbounded append-only log, already
handling growth via its existing chronological list.
