# Scope: Import and fulfilment

From factory prices to goods delivered to the customer. Index: [index.md](index.md).

### 27. Price catalogue and price import · existing
The price catalogue for nine factories, loaded from each factory's Excel price list with rules per factory. Only active price lists show in the sales picker. How the import works is in [`docs/Catalouge/`](../Catalouge/). code in `hr/catalog`, `hr/brand`, `hr/factory`, `features/catalog`

### 28. Import requests and procurement · existing
Import raises an import request for a confirmed order, issues its number, tracks revisions and lead time, and records the purchase. code in `hr/importrequest`, `hr/procurement`, `hr/importdeal`, `features/importDeal`, `features/importProgress`

### 29. Delivery and tax invoice before delivery · existing
In the deal's delivery tab, sales ask for a tax invoice, the warehouse reviews, the invoice issuer attaches it, and only then can the delivery be recorded. Deals from before the portal can start at this stage. code in `hr/deliveryrequest`, `features/tickets/delivery`

### 30. Shipment rounds and lots · in-progress
Import plans shipment rounds, splits an import request into lots, and moves each round forward. Sales see every lot in the deal's delivery tab and a list of what they ordered. Built on `develop` and waiting for release 2.4.0. Nothing is left to build, so this becomes `existing` once it is live. code in `hr/importrequest`, `features/fulfilment`, `features/myOrders`

## Slice 4: Import sheet

### 36. Import sheet backfill, import jobs and shared IR progress · in-progress
The team's Excel import sheet is loaded once into shipment rounds by a backfill run, import creates and edits their own งานนำเข้า in the portal, and every sales rep can read IR progress for the whole company with no prices and no factory documents. spec [0001](../specs/0001-import-sheet-load-shared-progress/index.md)
**Done when:** the current sheet is loaded on production, a second run of the same file changes nothing, jobs whose goods are in have moved forward with no notice sent, import can create and correct a งานนำเข้า on desktop and phone, and a sales rep sees every IR's progress under ทั้งบริษัท on ของที่ฉันสั่ง while still being refused on another rep's job.
- [x] Design it (spec): `/architect import sheet load`
- [x] Build it: `/develop import sheet load`
  - [x] Migration, parser and dry run report checked against the real sheet (AC-1 to AC-3, AC-10 to AC-12)
  - [x] Commit run: rounds, new IRs, adopt and fill in place, run log, IR counter, quiet job move, safe repeat (AC-4 to AC-9, AC-13 to AC-15)
  - [x] Import creates a งานนำเข้า from การขนส่ง (AC-16 to AC-18, AC-22, AC-23)
  - [x] Import edits a งานนำเข้า, its items and history; queue tags and two new dates on the round page (AC-19 to AC-21, AC-24, AC-25)
  - [x] Company wide list for sales with its real database permission test (AC-26 to AC-31)
- [ ] Verify it: `/check verify import sheet load`
- [ ] Test it: `/test import sheet load`

### 37. Link a loaded IR to a job · needs a decision
An IR loaded from the sheet with no job can be tied to a real job later, so it joins the job's pipeline, payments and delivery. from spec 0001
**Done when:** a rep or import can attach a loaded IR to a job, the job shows its incoming goods, and the rule of one issued IR per job and factory still holds.
- [ ] Design it (spec): `/architect link a no job IR to a job`
