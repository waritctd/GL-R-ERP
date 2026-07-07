# Sales document model: dedicated table per document type

**Status:** Accepted · **Date:** 2026-07-05 · **Scope:** `sales` schema, backend `deposit`/`ticket` packages, frontend document/quotation pages

## Context

The GL&R sales flow produces (or will produce) three distinct customer-facing
documents. They look superficially similar (line items, a customer block, a total) but
their money math and layout genuinely differ:

| | **Deposit notice** | **Quotation** | **Invoice** (future) |
|---|---|---|---|
| Purpose | Request a deposit once an order is confirmed | Offer prices before an order | Tax invoice on delivery / payment |
| Line total | `qty × unit price` | `net = price − discount`, then `net × qty` | line items + tax |
| VAT basis | on the **deposit amount** | on the **full subtotal** | on the full amount |
| Grand total | `deposit + VAT` | `subtotal + VAT` | full amount + terms |
| Deposit % | drives the total | only a **note condition**, not in the total | n/a |
| Number series | `GLRD` + Thai year + seq | `QN` + Thai year + seq | future `INV…` |
| Type-specific fields | preparer, customer snapshot | signatories block, validity days, offer date, import lead days | tax invoice no., due date |

(Quotation structure confirmed against the sales team's real form
`GL_R_ใบเสนอราคา_format.xls`, form control `F-SM-002 (03)`; see
`docs/V2/quotation-format-and-decisions.md`.)

The VAT-basis difference alone (deposit VAT on the deposit amount vs quotation VAT on the
full subtotal) means these cannot cleanly share one row shape.

### What existed before this decision

- `sales.document` (migration V17) was commented "generic — supports DEPOSIT_NOTICE now,
  QUOTATION/INVOICE later," but was **deposit-notice-specific in practice**: columns
  `deposit_percent` / `deposit_amount` / `vat_amount` / `total_payable`,
  `DocumentRepository` hardwiring `nextDocNumber("DEPOSIT_NOTICE", …)`, and
  `DepositNoticeRenderer` bound to `deposit_notice_template.xlsx`. Nothing switched on
  `doc_type` except the running-number sequence. The "generic engine" was never realized.
- `sales.quotation` (migration V6) was a separate, flat legacy table (number, total,
  currency, pdf path), later given versioning fields in V26/V27.

So the codebase already had two tables — one misleadingly named `document`.

## Decision

**One dedicated table per document type**, not a single generic engine:

- **Deposit notice** → rename `sales.document` → `sales.deposit_notice` (and
  `document_item` → `deposit_notice_item`). This is the honest name for what the table
  already is; the rename is behavior-preserving.
- **Quotation** → stays on `sales.quotation`; its real structure (per-row discount,
  VAT-on-subtotal, signatories, note parameters, versioning) is built out there.
- **Invoice** → not built yet. Reserved as a future `sales.invoice` with its own shape.

### Shared infrastructure stays generic (not renamed)

These are genuinely cross-type and are intentionally kept under the `document_` prefix:

- `sales.document_sequence` (`doc_type`, `year_th`, `last_seq`) — per-type running number
  generator; already used by deposit (`GLRD`) and ready for quotation (`QN`) / invoice.
- `sales.document_note_template` — boilerplate notes. **Currently deposit-only and has no
  `doc_type` column.** When quotation notes arrive it needs a `doc_type` column so each
  type gets its own note set (deferred — see below).

### Number series per type

`GLRD` + Thai-year + seq (deposit), `QN` + Thai-year + seq (quotation), future `INV…`.
All allocated via `sales.document_sequence`, keyed by `doc_type`.

## Consequences

- The persistence + backend + API + frontend rename `document → deposit_notice` lands as
  one atomic, behavior-preserving commit (migration `V28`, package
  `th.co.glr.hr.document` → `th.co.glr.hr.deposit`, API `/api/deposit-notices/*`, frontend
  `features/deposits/DepositNoticePage.jsx`).
- Building a new document type is now "add a table + repository + renderer + template,"
  reusing `document_sequence` and the customer/project snapshot pattern — no god-table to
  extend or regress.

## Explicitly out of scope (reserved, not done here)

- **Invoice** document implementation.
- **`doc_type` column on `document_note_template`** (needed before quotation notes).
- **`signatories` lookup table** (quotation signature block; `code` → `name`/`role`).

## Naming caveat

The commission module already contains `InvoiceDetails` / `InvoiceCalculation`
(`th.co.glr.hr.commission`) — these model the **customer's paid invoice used as a
commission basis**, *not* a generated document. A future invoice-document feature must use
a distinct name (e.g. `SalesInvoice` / `sales.invoice`) to avoid colliding with them.
