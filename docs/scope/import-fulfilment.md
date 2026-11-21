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
