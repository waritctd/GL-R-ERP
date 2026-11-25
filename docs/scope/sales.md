# Scope: Sales

From first contact with a customer to a confirmed order. The current flow is described in [`docs/sales-workflow.md`](../sales-workflow.md). Index: [index.md](index.md).

### 20. Customers and deal parties · existing
Customer records with Thai addresses, contacts, and the other parties on a deal such as designers. code in `hr/customer`, `hr/dealparty`, `hr/designer`, `hr/location`, `features/locations`

### 21. Deals and the stage pipeline · existing
A deal (shown as งาน) moves through fifteen stages with gates between them. It has an owner and a keyer, activities, attachments, and pause and cancel reasons. The stage rules live in the backend. code in `hr/ticket`, `features/tickets`

### 22. Price requests and pricing decisions · existing
Sales ask for a price, import adds the factory quote and costing, and the CEO decides in a pricing workspace, including the container rule and special prices. code in `hr/pricingrequest`, `hr/pricing`, `hr/pricingcosting`, `hr/pricingdecision`, `hr/factoryquote`, `features/pricingRequests`, `features/ceoSettings`

### 23. Quotations · existing
The quotation editor with revisions, approval, a chosen preparer, Thai or English output, and the customer document made from the company form. The money model is in [`docs/decisions/quotation-deposit-invoice-model.md`](../decisions/quotation-deposit-invoice-model.md). code in `hr/dealquotation`, `hr/customerquotation`, `features/quotations`

### 24. Order confirmation and deposit notice · existing
Confirming an order from an approved quotation, undoing that confirmation, and the deposit notice that copies the quotation lines. code in `hr/orderconfirmation`, `hr/deposit`, `features/deposits`, `features/documentChain`

### 25. Sales board, my work and visits · existing
The rep's own board of work, the team board for managers, and customer visits. code in `hr/salesboard`, `features/salesBoard`

### 26. Dashboards and sales analytics · existing
Landing pages for each role, the CEO analytics page, and the weekly analytics job. Metric definitions are in `analytics/metrics.yml`. code in `hr/dashboard`, `hr/salesanalytics`, `hr/analytics`, `features/dashboard`

## Slice 1: Add on orders

### 33. Add on orders · needs a decision
A customer orders more under the same project. The second order never replaces the first. Each order keeps its own quotation, payment and import line, and the lists still show one row per project with a count of orders.
**Done when:** a rep can start a second order inside a deal, the same item reuses its approved price with no second approval, orders are numbered 1, 2 and so on under the project number, commission is worked out per order, and the deal table and board show one row per project.
- [ ] Design it (spec): `/architect add on orders`

Three business questions are still open and belong in the spec: a quantity cap on instant approval, changing the deposit percent without approval, and who may open an add on order.

## Slice 2: Document generators

### 34. Document generators follow the quotation editor · needs a decision
Every screen that creates a customer document should look and work like the quotation editor, so staff learn one pattern.
**Done when:** the deposit notice and invoice screens use the same header, field grid, item table, totals block and save bar as the quotation editor, and what the screen shows matches the real PDF.
- [ ] Design it (spec): `/architect document generators follow the quotation editor`

Three calls are still open and belong in the spec: keep or drop the side preview, invoice as a page or a dialog, and whether lines can be edited or stay locked.

## Slice 3: Weekly report replacement

### 35. Weekly report replacement · in-progress
The portal writes the weekly sales report that reps now write by hand, so keeping deals current in the portal is all a rep has to do. Deal lists by date range and the receipts import already exist (features 21 and 32). Spec: [0003](../specs/0003-weekly-report-and-today.md)
**Done when:** each rep's weekly report is produced from portal data with an expected payment date per deal, late dates are shown as slips and not as errors, the received amount matches the accounting receipts for orders confirmed from 2026-11-16, and managers and the CEO have a team view of every rep's report and of slipped payments. No quiet-job measure (Ploy, 2026-11-26).
- [x] Design it (spec): `/architect weekly report replacement`
- [ ] Build it: `/develop weekly report replacement`
  - [ ] Rep weekly report (endpoint, money cutoff, dashboard switch)
  - [ ] Team weekly report
  - [ ] Rep งานวันนี้ redesign
  - [ ] Manager งานวันนี้ redesign
- [ ] Verify it: `/check verify weekly report replacement`
- [ ] Test it: `/test weekly report replacement`

## Slice 4: Import corrects factory lines

### 36. Import adds or moves a line on a contacted factory · in-progress
Import can add a missed line to a factory it already contacted, or move a line to another factory, without waiting for Sales to start a new round. Prices already entered stay, and the quote is confirmed again before it reaches the CEO. Spec: [0002](../specs/0002-import-add-move-quote-items.md)
**Done when:** a line with no factory can join a contacted factory, a line can move with a reason, costs use the right thickness and origin, and the CEO gate stays closed until every changed quote is confirmed.
- [x] Design it (spec): `/architect import adds or moves a line on a contacted factory`
- [ ] Build it: `/develop import adds or moves a line on a contacted factory`
  - [x] Add a missed line to a contacted factory (AC-1, AC-2, AC-3, AC-10, AC-11)
  - [ ] History, notices and the follow up email (AC-4, AC-9)
  - [ ] Move a line, including catalog lines (AC-5, AC-6, AC-7, AC-8, AC-9)
  - [ ] Mock API, API digests and revised tests (AC-1, AC-5, AC-10)
- [ ] Verify it: `/check verify import adds or moves a line on a contacted factory`
- [ ] Test it: `/test import adds or moves a line on a contacted factory`
