---
title: Customer
layer: Sales / CRM
schemas: [customers]
tags: [repo-map, domain/customer]
synced: develop@fe5138dd
---
# Customer
> Customers, projects, contacts, designers, and Thai-address handling.

**Layer:** Sales / CRM  ·  **DB schemas:** `customers`

## Backend
### `th.co.glr.hr.customer` — [customer/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/customer)
- `backend/src/main/java/th/co/glr/hr/customer/ContactDto.java`
- `backend/src/main/java/th/co/glr/hr/customer/ContactRepository.java`
- `backend/src/main/java/th/co/glr/hr/customer/CustomerController.java`
- `backend/src/main/java/th/co/glr/hr/customer/CustomerDto.java`
- `backend/src/main/java/th/co/glr/hr/customer/CustomerRepository.java`
- `backend/src/main/java/th/co/glr/hr/customer/CustomerService.java`
- `backend/src/main/java/th/co/glr/hr/customer/ProjectDto.java`
- `backend/src/main/java/th/co/glr/hr/customer/ProjectRepository.java`
### `th.co.glr.hr.designer` — [designer/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/designer)
- `backend/src/main/java/th/co/glr/hr/designer/DesignerController.java`
- `backend/src/main/java/th/co/glr/hr/designer/DesignerDto.java`
- `backend/src/main/java/th/co/glr/hr/designer/DesignerRepository.java`

## Frontend
_(no dedicated frontend feature — UI lives inside a related domain)_

## DB
- Schemas: `customers`
- Key migrations: `V32 demo ticket customers (demo)`

## Related
[[sales-deal-ticket|Sales Deal & Ticket]] · [[quotation|Quotation]]

## Gotchas
- Customer UI lives inside tickets/quotations features rather than a standalone feature dir.

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
