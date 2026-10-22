# Design Brief: การเงิน tab redesign (GLA-129 step 4, part 2/3)

## What

Redesign the deal-page's การเงิน (money) tab on `TicketDetailPage.jsx` (`/tickets/:id?tab=money`) to add
a status strip and a document pipeline above the existing content, plus a remaining-invoice summary
card — without touching `DealMoneyTimeline` (the payment receipt log, explicitly frozen by owner
ruling) or rewriting `DealDepositPanel`.

## Who

- **Sales rep (owner of the deal):** primary user of the per-document action cards — this is where
  they actually do work (set deposit policy, issue a remaining invoice).
- **Account / CEO:** oversight — the status strip and pipeline give an at-a-glance read without
  opening every document.
- **Sales manager:** same visibility as sales, backup actor on some steps (per `DealDepositPanel`'s
  existing `StepRoleTag` pattern).
- Import/HR/employee never reach this tab at all (`ticketDetailTabs.js`'s own gate) — not a target
  audience for this redesign.

## Why

GLA-99's backend (deposit policy, stored remaining invoice, billing note) shipped over three merged
PRs with no UI to show it holistically — a sales rep today has no single place to see "where is this
deal's money at" beyond the raw receipt log. This redesign gives that at-a-glance view without
changing any of the underlying document workflows, which already exist and work
(`DealDepositPanel`, `RemainingInvoiceDialog`).

## Approved design (Ploy, 2026-09-19, `billing-note-gla99-design-rulings` memory)

Additive only, top to bottom in the money tab:
1. Status strip — วันวางบิล, ครบกำหนด, ติดตามครั้งถัดไป, deposit requested/paid.
2. Document pipeline — ใบแจ้งมัดจำ → ใบแจ้งหนี้ส่วนที่เหลือ → ใบวางบิล → รับชำระครบ, next step
   highlighted, role-aware actions/links.
3. `DealDepositPanel` — unchanged.
4. Remaining-invoice summary card — new entry point, opens the existing `RemainingInvoiceDialog`.
5. `DealMoneyTimeline` — unchanged, kept exactly as-is.

## Out of scope

- Any change to `DealMoneyTimeline`.
- The ใบวางบิล step's link target (`/finance`) actually working — that's branch 3. This branch
  renders the link inert-but-present for a role that would have access once branch 3 lands, and
  plain status text (no link) for a role that never will.
- VAT ledger storage change — status strip/pipeline show VAT-inclusive figures as a derived display
  only (GLA-107), the underlying ledger stays pre-VAT.
