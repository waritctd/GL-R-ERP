-- V205: reconcile the sibling-bid backlog under the V204 deal party model.
--
-- Decision record: docs/decisions/deal-party-model.md, slice 4. Before V204, every quotation a rep wrote
-- for a different ผู้รับเหมา on the same โครงการ minted its own quotation-only container ticket, so one
-- opportunity sat in the pipeline once per bidder. This migration folds each such group into ONE deal with
-- one BUYER party per contractor, the way the editor now records new bids.
--
-- WHICH GROUPS (strict on purpose; anything else is left for a person to decide):
--   * same deal owner (sales.ticket.created_by) and same project name (customers.project.name_key, V204);
--   * at least two different customers in the group;
--   * the same keyed_by (V202) on every ticket, and the same deposit_policy and tender_requirement: the survivor
--     keeps its own, so a group that disagrees would silently change the others' owner/keyer or policy;
--   * EVERY ticket in the group, among those not already cancelled, is a pure container:
--       quotation_only = TRUE, status 'draft', lifecycle 'ACTIVE', at least one live quotation, and NOTHING else
--       hanging off it: no ticket_item, pricing_request, deposit_notice, import_request, payment_receipt,
--       attachment, remaining_invoice, commission_record (source_ticket_id), delivery_record,
--       factory_purchase_order or billing_note_line.
--   A group with even one ordinary deal or confirmed order in it is skipped whole: which deal should survive,
--   and whether the confirmed contractors were split awards, is a business call, not a migration's.
--   Production on 2026-11-07: 23 multi-customer groups, 21 qualify; the 2 left out are "the base patong sai 3"
--   (two orders already confirmed) and "บ้านพวงทอง" (has an ordinary pipeline deal).
--
-- WHAT HAPPENS TO A GROUP:
--   * survivor = the group's lowest ticket_id (the first bid); it keeps its code, project, owner and bill-to
--     placeholder, and moves to the most advanced sales_stage in the group;
--   * each other ticket's parties move onto the survivor; a customer that already holds that role there is
--     merged into the existing party (its quotations repointed), so uq_deal_party holds;
--   * every quotation moves to the survivor (ticket_id) and keeps its own number, status and party;
--   * the emptied container is cancelled (status 'cancelled', lifecycle 'CANCELLED', cancel_reason 'OTHER')
--     with a DEAL_MERGED_INTO event naming the survivor; its own events and activities stay where they are;
--   * the survivor gets one DEAL_PARTY_ADDED event per contractor that joined it.
--
-- IDEMPOTENT: a merged container is cancelled, so a second run finds no group (a group needs two
-- non-cancelled tickets). DealPartyMergeMigrationIntegrationTest runs this file twice.
--
-- MIGRATION NUMBERING: V205, directly above V204 (same branch). Verified free on origin/develop, origin/main
-- and every remote branch immediately before writing.
--
-- Rollback: there is no automatic one (merging is lossy only in the container's status). To undo one group:
-- move the quotations back by party (sales.quotation.party_id -> the party's original customer), move the
-- parties back, and restore each container with status 'draft', lifecycle 'ACTIVE', cancel_reason NULL,
-- cancelled_at NULL. The DEAL_MERGED_INTO event on each container names its survivor.

CREATE TEMP TABLE v205_member ON COMMIT DROP AS
WITH t AS (
    SELECT t.ticket_id, t.created_by, t.customer_id, p.name_key, t.keyed_by, t.deposit_policy, t.tender_requirement,
           (t.quotation_only AND t.status = 'draft' AND t.lifecycle = 'ACTIVE'
            AND EXISTS (SELECT 1 FROM sales.quotation q WHERE q.ticket_id = t.ticket_id
                          AND q.doc_status NOT IN ('SUPERSEDED', 'CANCELLED'))
            AND NOT EXISTS (SELECT 1 FROM sales.ticket_item x WHERE x.ticket_id = t.ticket_id)
            AND NOT EXISTS (SELECT 1 FROM sales.pricing_request x WHERE x.ticket_id = t.ticket_id)
            AND NOT EXISTS (SELECT 1 FROM sales.deposit_notice x WHERE x.ticket_id = t.ticket_id)
            AND NOT EXISTS (SELECT 1 FROM sales.import_request x WHERE x.ticket_id = t.ticket_id)
            AND NOT EXISTS (SELECT 1 FROM sales.payment_receipt x WHERE x.ticket_id = t.ticket_id)
            AND NOT EXISTS (SELECT 1 FROM sales.attachment x WHERE x.ticket_id = t.ticket_id)
            AND NOT EXISTS (SELECT 1 FROM sales.remaining_invoice x WHERE x.ticket_id = t.ticket_id)
            AND NOT EXISTS (SELECT 1 FROM sales.commission_record x WHERE x.source_ticket_id = t.ticket_id)
            AND NOT EXISTS (SELECT 1 FROM sales.delivery_record x WHERE x.ticket_id = t.ticket_id)
            AND NOT EXISTS (SELECT 1 FROM sales.factory_purchase_order x WHERE x.ticket_id = t.ticket_id)
            AND NOT EXISTS (SELECT 1 FROM sales.billing_note_line x WHERE x.ticket_id = t.ticket_id)
           ) AS container
      FROM sales.ticket t
      JOIN customers.project p ON p.project_id = t.project_id
     WHERE t.status <> 'cancelled'
),
grp AS (
    SELECT created_by, name_key, MIN(ticket_id) AS survivor_id
      FROM t
     GROUP BY created_by, name_key
    HAVING COUNT(*) >= 2
       AND COUNT(DISTINCT customer_id) >= 2
       AND COUNT(DISTINCT COALESCE(keyed_by, created_by)) = 1
       AND COUNT(DISTINCT deposit_policy) = 1
       AND COUNT(DISTINCT tender_requirement) = 1
       AND bool_and(container)
)
SELECT t.ticket_id, g.survivor_id
  FROM t JOIN grp g ON g.created_by = t.created_by AND g.name_key = t.name_key;

-- A party of a merged ticket whose (customer, role) already exists on the survivor folds into that party.
CREATE TEMP TABLE v205_party_map ON COMMIT DROP AS
SELECT src.party_id AS from_party, dst.party_id AS to_party
  FROM v205_member m
  JOIN sales.deal_party src ON src.ticket_id = m.ticket_id
  JOIN sales.deal_party dst ON dst.ticket_id = m.survivor_id
                           AND dst.customer_id = src.customer_id AND dst.role = src.role
 WHERE m.ticket_id <> m.survivor_id;

UPDATE sales.quotation q
   SET party_id = pm.to_party
  FROM v205_party_map pm
 WHERE q.party_id = pm.from_party;

DELETE FROM sales.deal_party p USING v205_party_map pm WHERE p.party_id = pm.from_party;

-- Two merged tickets for the same customer (and role) that the survivor does NOT have: keep the lowest party,
-- fold the others into it, before moving, so the move cannot collide on uq_deal_party.
CREATE TEMP TABLE v205_dup_map ON COMMIT DROP AS
SELECT p.party_id AS from_party,
       MIN(p.party_id) OVER (PARTITION BY m.survivor_id, p.customer_id, p.role) AS to_party
  FROM v205_member m
  JOIN sales.deal_party p ON p.ticket_id = m.ticket_id
 WHERE m.ticket_id <> m.survivor_id;

UPDATE sales.quotation q
   SET party_id = dm.to_party
  FROM v205_dup_map dm
 WHERE q.party_id = dm.from_party AND dm.from_party <> dm.to_party;

DELETE FROM sales.deal_party p USING v205_dup_map dm
 WHERE p.party_id = dm.from_party AND dm.from_party <> dm.to_party;

-- The joining parties: one event each on the survivor, written before they move (the name comes from the row).
INSERT INTO sales.ticket_event (ticket_id, actor_id, actor_name, kind, message)
SELECT m.survivor_id, s.created_by, 'ระบบ (รวมดีลโครงการเดียวกัน)', 'DEAL_PARTY_ADDED',
       'รวมจากดีล ' || src.code || ': เพิ่มผู้รับเหมา ' || c.name
  FROM v205_member m
  JOIN sales.ticket src ON src.ticket_id = m.ticket_id
  JOIN sales.ticket s ON s.ticket_id = m.survivor_id
  JOIN sales.deal_party p ON p.ticket_id = m.ticket_id
  JOIN customers.customer c ON c.customer_id = p.customer_id
 WHERE m.ticket_id <> m.survivor_id;

UPDATE sales.deal_party p
   SET ticket_id = m.survivor_id, is_headline = FALSE, updated_at = now()
  FROM v205_member m
 WHERE p.ticket_id = m.ticket_id AND m.ticket_id <> m.survivor_id;

-- quotation_version is numbered per (ticket, recipient_type) (ux_quotation_ticket_recipient_version), so moved
-- rows are renumbered above the survivor's current maximum, in their original creation order. The printed
-- number (sales.quotation.number) is untouched.
UPDATE sales.quotation q
   SET ticket_id = r.survivor_id,
       quotation_version = r.base_version + r.seq,
       updated_at = now()
  FROM (
    SELECT q2.quotation_id, m.survivor_id,
           ROW_NUMBER() OVER (PARTITION BY m.survivor_id, q2.recipient_type ORDER BY q2.quotation_id) AS seq,
           COALESCE((SELECT MAX(s.quotation_version) FROM sales.quotation s
                      WHERE s.ticket_id = m.survivor_id AND s.recipient_type = q2.recipient_type), 0) AS base_version
      FROM sales.quotation q2
      JOIN v205_member m ON m.ticket_id = q2.ticket_id
     WHERE m.ticket_id <> m.survivor_id) r
 WHERE q.quotation_id = r.quotation_id;

-- Every party now on a survivor that has a live quotation is QUOTED (a merged ACTIVE row would otherwise stay
-- ACTIVE with a document).
UPDATE sales.deal_party p
   SET status = 'QUOTED', updated_at = now()
 WHERE p.status = 'ACTIVE'
   AND p.ticket_id IN (SELECT survivor_id FROM v205_member)
   AND EXISTS (SELECT 1 FROM sales.quotation q WHERE q.party_id = p.party_id
                 AND q.doc_status NOT IN ('SUPERSEDED', 'CANCELLED'));

-- The survivor takes the most advanced stage any bid in the group had reached (all are pre-order here).
UPDATE sales.ticket s
   SET sales_stage = best.stage, stage_updated_at = now(), updated_at = now()
  FROM (
    SELECT m.survivor_id,
           (array_agg(t.sales_stage ORDER BY array_position(ARRAY[
               'LEAD_APPROACH','PRESENTATION','SPEC_APPROVED','QUOTE_DESIGN_SIDE','QUOTE_OWNER','OWNER_SIGNOFF',
               'AWAITING_BUYER','QUOTE_BUYER','NEGOTIATION']::text[], t.sales_stage::text) DESC NULLS LAST))[1] AS stage
      FROM v205_member m JOIN sales.ticket t ON t.ticket_id = m.ticket_id
     GROUP BY m.survivor_id) best
 WHERE s.ticket_id = best.survivor_id AND s.sales_stage IS DISTINCT FROM best.stage;

INSERT INTO sales.ticket_event (ticket_id, actor_id, actor_name, kind, from_status, to_status, message)
SELECT m.ticket_id, t.created_by, 'ระบบ (รวมดีลโครงการเดียวกัน)', 'DEAL_MERGED_INTO', 'draft', 'cancelled',
       'รวมเข้าดีล ' || s.code || ' (โครงการเดียวกัน ผู้รับเหมาหลายราย) ใบเสนอราคาย้ายไปที่ดีลนั้นแล้ว'
  FROM v205_member m
  JOIN sales.ticket t ON t.ticket_id = m.ticket_id
  JOIN sales.ticket s ON s.ticket_id = m.survivor_id
 WHERE m.ticket_id <> m.survivor_id;

UPDATE sales.ticket t
   SET status = 'cancelled', lifecycle = 'CANCELLED', cancel_reason = 'OTHER', cancelled_at = now(), updated_at = now()
  FROM v205_member m
 WHERE t.ticket_id = m.ticket_id AND m.ticket_id <> m.survivor_id;
