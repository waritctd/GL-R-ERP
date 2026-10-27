---
title: GL-R-ERP Repo Map
tags: [repo-map, index]
synced: develop@fe5138dd
---
# GL-R-ERP Repo Map
> A domain-level map of the repo: each note is one **vertical slice** (backend ↔ frontend ↔ DB) so you can find where a feature lives — and how features relate — without grepping the whole tree.

**How to use**
- Open `docs/repo-map/` as an **Obsidian vault**; the `[[links]]` between notes drive the **Graph view** — that graph *is* the map.
- Start here, open the domain you're touching, follow **Related** to its neighbours.
- **Agents:** read this INDEX first, then the one relevant domain note, instead of scanning the tree. Treat each note's **Gotchas** as pointers — `CLAUDE.md` is authoritative.

_Synced to `develop@fe5138dd`. Re-run `python3 docs/repo-map/_tools/generate.py` after big merges to refresh the file listings._

## Sales / CRM
- [[sales-deal-ticket|Sales Deal & Ticket]] — The deal aggregate (1 ticket = 1 deal) and its sales-stage pipeline / actions.
- [[pricing-flow|Pricing Flow]] — Pricing request → factory quote → landed cost → CEO selling-price decision.
- [[catalog-price-import|Catalog & Price Import]] — The product price catalogue (import-managed, version-controlled) + brand reference.
- [[quotation|Quotation]] — Customer-facing quotation generation, revisions, and the rendered PDF/XLSX.
- [[deposit-billing-finance|Deposit · Billing · Finance]] — Deposit notices, billing notes, tax invoices, payments, and sales commission.
- [[import-fulfilment|Import / Fulfilment]] — Per-factory import requests, procurement steps, and delivery fulfilment.
- [[customer|Customer]] — Customers, projects, contacts, designers, and Thai-address handling.

## HR
- [[employee|Employee & Self-service]] — Employee master, profile, and self-service edit/requests (incl. tax allowance).
- [[attendance|Attendance]] — Punch clock, attendance records, corrections, and the work calendar.
- [[leave|Leave]] — Leave requests, balances, policy rules, and approval.
- [[overtime|Overtime]] — Overtime filing and the two-step (manager → CEO) approval chain.
- [[payroll|Payroll]] — Payroll runs, deductions, consents, tax, and the payroll draft etag.

## Platform
- [[auth-session|Auth & Session]] — Login, session (JDBC-backed), CSRF, password reset, and the security filter chain.
- [[notification-mail|Notification & Mail]] — In-app notifications and outbound email (quotations, resets, alerts).
- [[activity-audit|Activity & Audit]] — The activity log (request filter + recorder) and the audit trail.
- [[dashboard|Dashboard]] — Cross-domain rollups and the landing dashboards.
- [[platform-common|Platform / Common]] — Shared infra: common utils, file attachments, and locations.
