# Deal, project and party: one deal per project, one party per bidder

**Status:** Proposed · **Date:** 2026-11-06 · **Scope:** `sales.ticket`, `customers.project`,
`sales.quotation`, backend `ticket` / `dealquotation` / `dashboard` packages, frontend deal page,
quotation list and quotation editor

Prototype of the screens this changes: `docs/decisions/deal-party-model.prototype.html`
(open the file in a browser; static, example data only).

## Context

Sales often quote the same โครงการ to several ผู้รับเหมา who are bidding for it. Commercially that
is one opportunity with several bidders. In the system it is several deals, because:

- `sales.ticket` has exactly one `customer_id`, one `project_id` and one `contact_id` (V23).
- `customers.project.customer_id` is `NOT NULL`, so a project row belongs to one customer. The
  same site quoted to three contractors is three project rows with the same name.
- The direct-quotation path (V165, V193) mints a `quotation_only` container ticket per quotation,
  so each contractor's quotation arrives as its own ticket.

The pipeline design anticipated the business shape (`AWAITING_BUYER` "รอผลประมูล", `QUOTE_BUYER`,
the `BUYER` recipient labelled "ผู้ซื้อ / ผู้รับเหมา", and V192.3's note that two contractors
bidding on one project are two quotations) but never got a structure for it.

### Evidence (production, 2026-11-06)

Quotations not SUPERSEDED or CANCELLED, grouped by normalised project name:

| Metric | Count |
|---|---|
| Deals with a live quotation | 281 |
| Distinct project names | 212 |
| Project names quoted to more than one customer | 23 |
| Deals inside those projects | 74 |
| Of those, `quotation_only` container tickets | 72 |
| Sibling deals still `ACTIVE`, none marked lost | 74 |
| Multi-bidder projects handled by a single rep | 21 of 23 |

One project in that set has two contractors both at `ORDER_RECEIVED` (scope split between them),
and one at `LEAD_APPROACH` beside them. Two groups are the same customer typed twice (spelling
variants), and five groups have the same contractor on two separate tickets. Those are
customer-master and duplicate-deal hygiene, not bidding, and they are why linking must be a rep
decision with a suggestion, never automatic.

### What goes wrong today

- Pipeline value and the win-weighted forecast (`DashboardRepository`, `WinProbabilityDefaults`)
  sum the same opportunity once per bidder.
- Win rate counts one real win as one win plus N-1 losses.
- No `lost_reason` fits a losing bid ("another contractor was awarded"), so losing tickets stay
  `ACTIVE` forever. All 74 sibling deals are still active.
- The rep's deal list and the quotation list show what reads as duplicates.

## Decision

Separate the three things the ticket currently conflates.

| Concept | Meaning | Where it lives |
|---|---|---|
| **Project (โครงการ)** | The site. Independent of any customer. Optional owner and designer references. | `customers.project`, `customer_id` becomes nullable and is renamed in meaning to "owner customer" |
| **Deal** | The opportunity on a project, one per project per awarded scope. Carries stage, rep, forecast, and (after award) the bill-to customer. | `sales.ticket` unchanged in shape; `customer_id` means **awarded bill-to** |
| **Party (ผู้เกี่ยวข้องในดีล)** | One customer taking part in the deal in a role: DESIGNER, OWNER or BUYER. Each bidding contractor is one BUYER party. Quotations attach to a party. | new `sales.deal_party`; new `sales.quotation.party_id` |

This is the standard CRM shape (opportunity with account roles). It absorbs the existing
recipient concept: the DESIGNER / OWNER / BUYER chains `QuotationRecipient` already distinguishes
become parties, and the three free-text names on the deal tracking panel (V83 `designer_name`,
`owner_name`, `buyer_name`) are replaced by party rows.

### Lifecycle rules

1. **Before award** there is one deal per project. The rep adds a party per contractor and issues
   each its own quotation chain. Quotation numbering is unchanged (`QT-<year>-<seq>-<rev>` per
   document, `QuotationNumbering`).
2. **Pipeline counts a deal once.** Pipeline value and forecast use the deal's headline amount:
   the highest live quotation, or the one the rep flags as most likely (`deal_party.is_headline`).
3. **The rep records the bid outcome once, choosing one or more winners.** "บันทึกผลประมูล" on
   the deal lists every BUYER party with a checkbox. At least one must be ticked. One ticked
   winner is the common case; several are allowed because a project's scope is sometimes split
   between contractors (seen in production).
4. **One deal per paying customer after the outcome.** The first (or rep-chosen) winner is the
   *primary*: its customer and contact become the ticket's `customer_id` / `contact_id` (the
   bill-to every downstream step reads: deposit notice, invoices, commission), its party becomes
   `AWARDED`, and the deal advances to `ORDER_RECEIVED` through the existing
   `promoteToDeal` / `confirmOrder` path. Every additional winner becomes `AWARDED_SPLIT`: the
   service forks a child ticket (`DL-<year>-<seq>-A`, `-B`, …) on the same project with that
   party as its sole `AWARDED` party, moves that party's quotation chain to the child, and
   advances the child to `ORDER_RECEIVED` the same way. Parties not ticked become `NOT_AWARDED`
   with the reason the rep picked, or stay `QUOTED` if the rep chose "ยังรอผล" (the primary deal
   then still accepts a later outcome for them). Pre-outcome: one opportunity per project.
   Post-outcome: one contract per paying customer, all under one project, counted as one win.
5. **Outcomes live at the right level.** "Lost to another contractor" is a party outcome
   (`deal_party.status = NOT_AWARDED`, with its own reason). "Project cancelled / on hold" stays a
   deal outcome (`sales.ticket.lost_reason`). Win rate is per deal; bid conversion is per party.
6. **One rep owns the deal.** Other reps who bring a contractor are collaborators on that party
   (`deal_party.added_by`), and `DealEntryAccess` admits them to the deal through the party.

### Schema sketch (forward-only `Vnnn`, numbers assigned at build time)

```sql
-- Slice 1: project becomes customer-independent
ALTER TABLE customers.project ALTER COLUMN customer_id DROP NOT NULL;
ALTER TABLE customers.project
    ADD COLUMN designer_customer_id BIGINT REFERENCES customers.customer(customer_id),
    ADD COLUMN merged_into_project_id BIGINT REFERENCES customers.project(project_id),
    ADD COLUMN name_key TEXT GENERATED ALWAYS AS
        (lower(regexp_replace(name, '\s+', ' ', 'g'))) STORED;
CREATE INDEX idx_project_name_key ON customers.project(name_key)
    WHERE merged_into_project_id IS NULL;
COMMENT ON COLUMN customers.project.customer_id IS
    'Owner of the site (เจ้าของโครงการ) when known. NULL is allowed: a project is the site, not a customer''s record.';

-- Slice 2: parties
CREATE TABLE sales.deal_party (
    party_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id     BIGINT NOT NULL REFERENCES sales.ticket(ticket_id) ON DELETE CASCADE,
    customer_id   BIGINT NOT NULL REFERENCES customers.customer(customer_id),
    contact_id    BIGINT REFERENCES customers.contact(contact_id) ON DELETE SET NULL,
    role          VARCHAR(10) NOT NULL CHECK (role IN ('DESIGNER','OWNER','BUYER')),
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN (
                      'ACTIVE',        -- in the deal, nothing quoted yet (or a non-bidding role)
                      'QUOTED',        -- has at least one live quotation
                      'NEGOTIATING',
                      'AWARDED',       -- became the ticket's bill-to
                      'AWARDED_SPLIT', -- awarded part of the scope; forked to child ticket
                      'NOT_AWARDED',
                      'WITHDRAWN')),
    not_awarded_reason VARCHAR(30) CHECK (not_awarded_reason IN (
                      'OTHER_BIDDER','PRICE','LEAD_TIME','PAYMENT_TERMS','RELATIONSHIP','PRODUCT_FIT')),
    is_headline   BOOLEAN NOT NULL DEFAULT FALSE,
    split_ticket_id BIGINT REFERENCES sales.ticket(ticket_id),
    added_by      BIGINT REFERENCES hr.employee(employee_id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_deal_party UNIQUE (ticket_id, customer_id, role),
    CONSTRAINT chk_deal_party_not_awarded_pair
        CHECK ((status = 'NOT_AWARDED') = (not_awarded_reason IS NOT NULL))
);
CREATE INDEX idx_deal_party_ticket ON sales.deal_party(ticket_id);
CREATE INDEX idx_deal_party_customer ON sales.deal_party(customer_id);
CREATE UNIQUE INDEX uq_deal_party_headline ON sales.deal_party(ticket_id) WHERE is_headline;

ALTER TABLE sales.quotation ADD COLUMN party_id BIGINT REFERENCES sales.deal_party(party_id);
CREATE INDEX idx_quotation_party ON sales.quotation(party_id);

-- Deal-level outcome keeps its list; the bid-level outcome is on the party, so no new
-- sales.ticket.lost_reason value is needed.
```

`sales.ticket.customer_id` stays `NOT NULL` for now. A pre-award deal created from the party
flow sets it to the first party's customer as a placeholder and `award` overwrites it; a later
slice may relax it once every read of the column is audited.

### Backfill (Slice 2, idempotent)

```sql
-- One party per existing ticket, from the columns it already has. Role from the ticket's
-- latest live quotation recipient; UNSPECIFIED / none falls back to BUYER (every direct
-- quotation is addressed to a buyer or owner, and OWNER is recoverable by hand).
INSERT INTO sales.deal_party (ticket_id, customer_id, contact_id, role, status, is_headline, added_by)
SELECT t.ticket_id, t.customer_id, t.contact_id,
       CASE q.recipient_type WHEN 'DESIGNER' THEN 'DESIGNER' WHEN 'OWNER' THEN 'OWNER' ELSE 'BUYER' END,
       CASE
         WHEN t.sales_stage IN ('ORDER_RECEIVED','DEPOSIT_RECEIVED','PROCUREMENT',
                                'DELIVERY_SCHEDULING','DELIVERED','CLOSED_PAID') THEN 'AWARDED'
         WHEN q.quotation_id IS NOT NULL THEN 'QUOTED'
         ELSE 'ACTIVE'
       END,
       TRUE, t.created_by
  FROM sales.ticket t
  LEFT JOIN LATERAL (
       SELECT quotation_id, recipient_type FROM sales.quotation
        WHERE ticket_id = t.ticket_id AND doc_status NOT IN ('SUPERSEDED','CANCELLED')
        ORDER BY created_at DESC LIMIT 1) q ON TRUE
 WHERE t.customer_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sales.deal_party p WHERE p.ticket_id = t.ticket_id);

UPDATE sales.quotation q
   SET party_id = p.party_id
  FROM sales.deal_party p
 WHERE p.ticket_id = q.ticket_id AND q.party_id IS NULL;
```

### Detecting the backlog (Slice 4 seed)

The query the sales manager's merge list is built from. Same shape as the one used for the
evidence above; it proposes, a person confirms.

```sql
WITH q AS (
  SELECT DISTINCT q.ticket_id, q.sales_rep_id,
         lower(regexp_replace(COALESCE(NULLIF(TRIM(q.project_name),''), p.name),'\s+',' ','g')) AS project_key,
         t.customer_id, t.quotation_only, t.sales_stage
    FROM sales.quotation q
    JOIN sales.ticket t ON t.ticket_id = q.ticket_id
    LEFT JOIN customers.project p ON p.project_id = t.project_id
   WHERE q.doc_status NOT IN ('SUPERSEDED','CANCELLED')
)
SELECT project_key, sales_rep_id,
       array_agg(ticket_id ORDER BY ticket_id) AS tickets,
       COUNT(DISTINCT customer_id) AS bidders,
       bool_and(quotation_only) AS all_quotation_only
  FROM q
 WHERE project_key IS NOT NULL
 GROUP BY project_key, sales_rep_id
HAVING COUNT(DISTINCT customer_id) > 1
 ORDER BY bidders DESC;
```

### API (new, `dealparty` package)

| Method | Path | Body / result |
|---|---|---|
| GET | `/api/deals/{ticketId}/parties` | parties with role, status, customer, contact, latest quotation number and amount, `isHeadline` |
| POST | `/api/deals/{ticketId}/parties` | `{ customerId, role, contactId? }` → the party; 409 if that customer already holds that role |
| PATCH | `/api/deals/{ticketId}/parties/{partyId}` | `{ status?, contactId?, isHeadline? }` for pre-outcome edits (NEGOTIATING, WITHDRAWN, headline) |
| POST | `/api/deals/{ticketId}/bid-outcome` | `{ awardedPartyIds: [≥1], primaryPartyId, othersOutcome: 'NOT_AWARDED' \| 'KEEP_WAITING', notAwardedReason? }` → `{ primaryTicketId, childTicketIds[] }` |
| GET | `/api/deals/open-for-project?name=&repId=` | the rep's open pre-outcome deal(s) whose project `name_key` matches, for the editor prompt |

`bid-outcome` is a single transaction: parties updated, bill-to copied, child tickets forked,
quotations repointed, one `BID_OUTCOME_RECORDED` ticket event on the primary and a `CREATED`
event citing the parent on each child. It refuses (409) when the deal already has an `AWARDED`
party; a correction goes through the existing `REOPENED` path, which resets parties to `QUOTED`
and leaves forked children to be cancelled by hand (they may already carry documents).

`DealQuotationService#create` gains an optional `partyId`; when present the quotation snapshots
that party's customer and contact instead of the ticket's, so sibling bids on one ticket print
the right company. A ticket with more than one BUYER party and no `AWARDED` party is shown under
its project name, not its placeholder customer.

## Delivery: four slices, each shippable alone

1. **Customer and project hygiene.** Customer merge tool; tax ID unique where present; project
   dedupe by `name_key` with `merged_into_project_id`; `customer_id` nullable on the project.
   Prerequisite because party identity depends on customer identity.
2. **Additive schema and backfill.** `deal_party`, `quotation.party_id`, the backfill above. No
   read changes yet. Tracking panel's three free-text names are shown read-only from parties.
3. **Change the entry point.** In the quotation editor and the deal form, picking a project that
   already has an open deal for this rep attaches to it and adds a party instead of minting a
   new `quotation_only` ticket. This is the single change that stops the problem growing.
   `promoteToDeal` is called by `bid-outcome`. The outcome dialog ships here.
4. **Reconcile the backlog.** A merge action for the 23 existing groups: a `quotation_only`
   ticket carries nothing but its quotation, so merging moves the quotation and party onto the
   surviving deal and cancels the empty container (`DEAL_MERGED_INTO` event naming the survivor).
   Shipped as migration V205 with a strict rule; see Implementation status.

Dashboard and forecast switch to per-deal counting in slice 2 (they already sum per ticket; the
change is that siblings become one ticket). Commission is invoice-based and keyed by ticket, so it
is unchanged.

## Implementation status (2026-11-07)

Built on branch `claude/keen-cannon-m416h7`:

| Slice | State | Where |
|---|---|---|
| 1. Project is the site | Done: `customer_id` nullable, generated `name_key`. Customer merge tool and tax-ID uniqueness are **not** built. | V204 |
| 2. Parties, `quotation.party_id`, backfill | Done. Every new deal gets its customer as first BUYER party. | V204, `TicketRepository#create` |
| 3. Entry point and outcome | Done: per-party quotation (own customer snapshot, number family, live-quotation lock, approval sweep), `bid-outcome` with one or more winners, open-deal lookup for the editor. | `dealparty/`, `DealQuotationService` |
| 4. Backlog | Done as an automatic, strict migration instead of a per-group confirmation, because the owner asked for the existing deals to be corrected. Only groups made entirely of pure quotation-only containers merge. | V205 |

V205 against production data on 2026-11-07 (grouped by deal owner and project row name):
21 of 23 multi-customer groups merge. Left for a person to decide: "the base patong sai 3"
(two orders already confirmed for different contractors, plus open bids) and "บ้านพวงทอง"
(contains an ordinary pipeline deal). Same-company duplicates typed as two customers become two
parties on one deal; merging the customer rows is still a manual step.

Behaviour decided while building, beyond the rules above:

- A quotation created without a party attaches to the deal's own customer's party, preferring
  the quotation's recipient role, else the deal's sole party.
- Approval supersedes other live quotations of the same party only, so each bidder keeps one
  live offer.
- After the winner's order is confirmed, a bidder recorded as "ยังรอผล" may still submit and
  approve, and a later outcome forks it to a child deal; there is never a second primary.

## Non-goals

- Restructuring `sales.ticket.customer_id`. It is read across fulfilment and finance; under this
  model it keeps one clear meaning (awarded bill-to) and the party table carries the pre-award
  ambiguity.
- Changing quotation numbering, the PDF, or issued documents.
- Changing the 15-stage pipeline or `DealStage.ORDER`.
- Automatic linking by project name. The system proposes; a rep or sales manager confirms.

## Calls made here (Ploy to confirm or strike)

1. Bid-level loss lives on the party, not as a new `sales.ticket.lost_reason`.
2. Several winners are chosen in one outcome dialog; each extra winner forks a child ticket rather than allowing two bill-tos on one ticket.
3. One deal owner; a second rep is a collaborator through their party.
4. The three free-text tracking names are replaced by parties, not kept beside them.
5. Backfill maps `UNSPECIFIED` recipient to `BUYER`.
