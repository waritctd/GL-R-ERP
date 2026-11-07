-- V204: deal party model — one deal per project, one party per bidder.
--
-- Decision record: docs/decisions/deal-party-model.md (read it first). The short form: sales
-- often quotes one โครงการ to several ผู้รับเหมา bidding for it. Today each bid is its own
-- sales.ticket because a ticket has one customer_id and customers.project.customer_id is NOT
-- NULL, so the pipeline counts one opportunity once per bidder, win rate counts one win plus N-1
-- losses, and losing bids have no outcome to record (production 2026-11-06: 23 projects quoted
-- to more than one customer, 74 sibling tickets, every one still ACTIVE).
--
-- This migration is ADDITIVE and IDEMPOTENT (every statement guarded) so that
-- DealPartyBackfillIntegrationTest can execute this file verbatim against an already-migrated
-- schema, the same technique V175 / V193 use.
--
-- MIGRATION NUMBERING: V204. Verified free on origin/develop, origin/main and every remote branch
-- (`git ls-tree` over each) immediately before writing this file; both top out at V203.
--
-- What changes:
--   1. customers.project.customer_id becomes NULLABLE. A project is the SITE, not a customer's
--      record. The column now means "owner of the site when known". Existing rows keep their
--      value. name_key (generated) is the normalised name the editor prompt and the backlog
--      reconcile match on.
--   2. sales.deal_party: one row per customer taking part in a deal in a role (DESIGNER / OWNER /
--      BUYER). Each bidding contractor is one BUYER party. Statuses:
--        ACTIVE         in the deal, nothing quoted yet (or a non-bidding role)
--        QUOTED         has at least one live quotation
--        NEGOTIATING    rep-set, pre-outcome
--        AWARDED        the deal's bill-to: ticket.customer_id / contact_id were copied from it
--        AWARDED_SPLIT  also won, but the scope was split: forked to its own child ticket
--                       (split_ticket_id) where it is the AWARDED party
--        NOT_AWARDED    lost the bid; not_awarded_reason says why
--        WITHDRAWN      pulled out before the outcome
--      is_headline marks the one party whose quotation is the deal's headline amount for the
--      pipeline / forecast (at most one per ticket).
--   3. sales.quotation.party_id: which party a quotation is addressed to. Sibling bids on one
--      ticket snapshot THEIR party's customer, not the ticket's.
--   4. sales.ticket.parent_ticket_id: set on a child ticket forked by a split outcome.
--   5. chk_event_kind widened (full re-declaration, as every migration since V39 does) with the
--      four kinds the party flow writes.
--   6. Backfill: one party per existing ticket from the columns it already carries; every
--      existing quotation attached to its ticket's party.
--
-- sales.ticket.customer_id stays NOT NULL: under this model it means the AWARDED bill-to, and a
-- pre-outcome deal created from the party flow sets it to the first party's customer as a
-- placeholder that bid-outcome overwrites. Relaxing it is a separate slice once every read of
-- the column is audited.
--
-- Rollback (reverse order): drop the four event kinds from the constraint (re-declare it as
-- V193 did), DROP COLUMN sales.ticket.parent_ticket_id, DROP COLUMN sales.quotation.party_id,
-- DROP TABLE sales.deal_party, DROP INDEX idx_project_name_key, DROP COLUMN
-- customers.project.name_key, ALTER COLUMN customers.project.customer_id SET NOT NULL (fails
-- if a NULL-owner project was created meanwhile; set those first).

-- ── 1. project is the site ───────────────────────────────────────────────────────────────────
ALTER TABLE customers.project ALTER COLUMN customer_id DROP NOT NULL;

ALTER TABLE customers.project
    ADD COLUMN IF NOT EXISTS name_key TEXT
        GENERATED ALWAYS AS (lower(regexp_replace(btrim(name), '\s+', ' ', 'g'))) STORED;

CREATE INDEX IF NOT EXISTS idx_project_name_key ON customers.project(name_key);

COMMENT ON COLUMN customers.project.customer_id IS
    'Owner of the site (เจ้าของโครงการ) when known. NULL is allowed since V204: a project is the site, not one customer''s record. Bidding contractors are sales.deal_party rows on the deal, not owners of the project.';
COMMENT ON COLUMN customers.project.name_key IS
    'Generated: lower-cased, whitespace-collapsed name. The quotation editor''s "this project already has your deal" prompt and the V205 backlog reconcile match on it.';

-- ── 2. parties ───────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sales.deal_party (
    party_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id           BIGINT NOT NULL REFERENCES sales.ticket(ticket_id) ON DELETE CASCADE,
    customer_id         BIGINT NOT NULL REFERENCES customers.customer(customer_id),
    contact_id          BIGINT REFERENCES customers.contact(contact_id) ON DELETE SET NULL,
    role                VARCHAR(10) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    not_awarded_reason  VARCHAR(30),
    is_headline         BOOLEAN NOT NULL DEFAULT FALSE,
    split_ticket_id     BIGINT REFERENCES sales.ticket(ticket_id) ON DELETE SET NULL,
    added_by            BIGINT REFERENCES hr.employee(employee_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_deal_party UNIQUE (ticket_id, customer_id, role),
    CONSTRAINT chk_deal_party_role CHECK (role IN ('DESIGNER','OWNER','BUYER')),
    CONSTRAINT chk_deal_party_status CHECK (status IN (
        'ACTIVE','QUOTED','NEGOTIATING','AWARDED','AWARDED_SPLIT','NOT_AWARDED','WITHDRAWN')),
    CONSTRAINT chk_deal_party_not_awarded_reason CHECK (not_awarded_reason IS NULL OR not_awarded_reason IN (
        'OTHER_BIDDER','PRICE','LEAD_TIME','PAYMENT_TERMS','RELATIONSHIP','PRODUCT_FIT')),
    CONSTRAINT chk_deal_party_not_awarded_pair
        CHECK ((status = 'NOT_AWARDED') = (not_awarded_reason IS NOT NULL)),
    CONSTRAINT chk_deal_party_split_pair
        CHECK ((status = 'AWARDED_SPLIT') = (split_ticket_id IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_deal_party_ticket   ON sales.deal_party(ticket_id);
CREATE INDEX IF NOT EXISTS idx_deal_party_customer ON sales.deal_party(customer_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_deal_party_headline ON sales.deal_party(ticket_id) WHERE is_headline;

COMMENT ON TABLE sales.deal_party IS
    'One customer taking part in a deal in one role. Each ผู้รับเหมา bidding on the project is a BUYER party with its own quotation chain and its own outcome. See docs/decisions/deal-party-model.md.';

-- ── 3. quotation → party ─────────────────────────────────────────────────────────────────────
ALTER TABLE sales.quotation
    ADD COLUMN IF NOT EXISTS party_id BIGINT REFERENCES sales.deal_party(party_id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_quotation_party ON sales.quotation(party_id);

COMMENT ON COLUMN sales.quotation.party_id IS
    'The deal party this document is addressed to. Its customer / contact snapshot columns are taken from this party at write time, not from the ticket, so sibling bids on one ticket print the right company. NULL only for rows older than V204 whose ticket had no customer.';

-- ── 4. child tickets from a split outcome ───────────────────────────────────────────────────
ALTER TABLE sales.ticket
    ADD COLUMN IF NOT EXISTS parent_ticket_id BIGINT REFERENCES sales.ticket(ticket_id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_ticket_parent ON sales.ticket(parent_ticket_id) WHERE parent_ticket_id IS NOT NULL;

COMMENT ON COLUMN sales.ticket.parent_ticket_id IS
    'Set on a ticket forked by a bid outcome with more than one winner: the primary deal it was split from. The child carries its own bill-to (invoices, deposit, commission are per customer); pipeline reporting counts the project''s win once through the parent.';

-- ── 5. event kinds ───────────────────────────────────────────────────────────────────────────
ALTER TABLE sales.ticket_event DROP CONSTRAINT IF EXISTS chk_event_kind;
ALTER TABLE sales.ticket_event ADD CONSTRAINT chk_event_kind CHECK (kind IN (
    'CREATED','SUBMITTED','PICKED_UP','PRICE_PROPOSED','APPROVED','REJECTED',
    'QUOTATION_ISSUED','COMMENTED','CLOSED','CANCELLED','EDITED',
    'DOCUMENT_ISSUED','REVISION_REQUESTED','PRICE_REVISED',
    'CUSTOMER_CONFIRMED','DEPOSIT_NOTICE_ISSUED','DEPOSIT_PAID',
    'IR_ISSUED','IR_SENT','SHIPPING','GOODS_RECEIVED',
    'AWAITING_FINAL_PAYMENT','FULLY_PAID','PRICE_OVERRIDDEN',
    'STAGE_CHANGED','MARKED_LOST','REOPENED',
    'ON_HOLD','DORMANT','RESUMED','POLICY_CHANGED',
    'QUOTATION_SENT','QUOTATION_ACCEPTED','QUOTATION_REJECTED',
    'PAYMENT_RECORDED','BILLING_UPDATED',
    'STOCK_RESERVED','DELIVERY_RECORDED','DELIVERY_COMPLETED',
    'CLOSE_CONFIRMED','CLOSE_CONFIRM_REVOKED',
    'ORDER_CONFIRMED_FROM_QUOTATION',
    'DEAL_QUOTATION_REORDERED',
    'DEAL_QUOTATION_SUPERSEDED',
    'IMPORT_STEP_ADVANCED',
    'IMPORT_REQUEST_EMAIL_SENT',
    'DEAL_PROMOTED_FROM_QUOTATION',
    'DEAL_PARTY_ADDED',
    'BID_OUTCOME_RECORDED',
    'DEAL_SPLIT_FROM_PARENT',
    'DEAL_MERGED_INTO'
));

-- ── 6. backfill ──────────────────────────────────────────────────────────────────────────────
-- One party per existing ticket, from the columns it already has. The role comes from the
-- ticket's latest live quotation recipient_type (V52); UNSPECIFIED or no quotation falls back to
-- BUYER — every direct quotation is addressed to a buyer or an owner, and OWNER is recoverable
-- by hand. Status: AWARDED once the ticket is at or past ORDER_RECEIVED (its customer_id IS the
-- bill-to already), QUOTED when a live quotation exists, else ACTIVE. The single party is the
-- headline. Tickets without a customer_id (legacy pre-V23 rows) get no party and their
-- quotations keep party_id NULL. Guarded by NOT EXISTS so a re-run inserts nothing.
INSERT INTO sales.deal_party (ticket_id, customer_id, contact_id, role, status, is_headline, added_by, created_at)
SELECT t.ticket_id,
       t.customer_id,
       t.contact_id,
       CASE q.recipient_type WHEN 'DESIGNER' THEN 'DESIGNER' WHEN 'OWNER' THEN 'OWNER' ELSE 'BUYER' END,
       CASE
         WHEN t.sales_stage IN ('ORDER_RECEIVED','DEPOSIT_RECEIVED','PROCUREMENT',
                                'DELIVERY_SCHEDULING','DELIVERED','CLOSED_PAID') THEN 'AWARDED'
         WHEN q.quotation_id IS NOT NULL THEN 'QUOTED'
         ELSE 'ACTIVE'
       END,
       TRUE,
       t.created_by,
       t.created_at
  FROM sales.ticket t
  LEFT JOIN LATERAL (
       SELECT quotation_id, recipient_type
         FROM sales.quotation
        WHERE ticket_id = t.ticket_id
          AND doc_status NOT IN ('SUPERSEDED','CANCELLED')
        ORDER BY created_at DESC, quotation_id DESC
        LIMIT 1) q ON TRUE
 WHERE t.customer_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sales.deal_party p WHERE p.ticket_id = t.ticket_id);

-- Every quotation on a ticket that now has exactly one party belongs to that party. (A ticket
-- can only have several parties AFTER this migration, through the party flow, which sets
-- party_id itself.)
UPDATE sales.quotation q
   SET party_id = p.party_id
  FROM sales.deal_party p
 WHERE p.ticket_id = q.ticket_id
   AND q.party_id IS NULL
   AND (SELECT COUNT(*) FROM sales.deal_party p2 WHERE p2.ticket_id = q.ticket_id) = 1;
