-- V206: finish the sibling-bid reconcile that V205 left behind (owner request 2026-11-07: "why aren't these two
-- S26 HOTEL deals combined?").
--
-- V205 was deliberately narrow: same deal owner (created_by), same keyer, and at least two DIFFERENT customers.
-- Production on 2026-11-07 still held 14 projects whose open deals were all pure quotation-only containers and
-- all quoted by ONE rep, but which V205 skipped because the deal rows disagreed on bookkeeping:
--   * the keyer differs (an admin typed one deal in, a different admin typed the next);
--   * the owner differs: a deal typed in by an admin before V202 carries the admin as created_by while the rep is
--     named on the quotation (V203 only re-owned deals with no keyer), e.g. PR-2026-0269 / DL-2026-0341;
--   * the same customer was quoted twice on the project (two containers, one customer).
--
-- WHICH GROUPS (still strict; a business call stays a person's):
--   * same project (customers.project.name_key), every non-cancelled deal on it a pure container, exactly as in
--     V205 (quotation_only, draft, ACTIVE, a live quotation, nothing else hanging off it);
--   * at least two such deals, the same deposit_policy and tender_requirement on all;
--   * ONE rep across the whole group: every live quotation names the same rep (sales_rep_display_id, else
--     sales_rep_id). A project quoted by several different reps ("showroom @myanmar") is several reps'
--     opportunities, not one, and is left alone, as is test data under mixed reps;
--   * that rep OWNS at least one deal in the group; the lowest such ticket_id is the survivor, so the deal's
--     owner stays the rep who quoted it. The survivor keeps its own keyer: another admin who keyed one of the
--     merged deals no longer sees it as theirs (their access to the rep's other deals is unchanged).
--   A project with an ordinary deal or confirmed order in it is still skipped whole (V205's reasoning).
--
-- WHAT HAPPENS TO A GROUP: identical to V205 (parties and quotations move onto the survivor, the emptied
-- containers are cancelled with a DEAL_MERGED_INTO event, the survivor gets DEAL_PARTY_ADDED events). A customer
-- quoted twice ends as ONE party holding both quotations.
--
-- IDEMPOTENT: merged containers are cancelled, so a second run finds no group.
-- Rollback: as V205's (move the quotations back by party, restore each container to draft/ACTIVE).

CREATE TEMP TABLE v206_member ON COMMIT DROP AS
WITH t AS (
    SELECT t.ticket_id, t.created_by, t.customer_id, p.name_key, t.keyed_by, t.deposit_policy, t.tender_requirement,
           r.rep_id, r.rep_count,
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
      -- The rep each quotation names; a deal with live quotations from two reps has rep_count 2 and blocks its group.
      CROSS JOIN LATERAL (
          SELECT MIN(COALESCE(q.sales_rep_display_id, q.sales_rep_id)) AS rep_id,
                 COUNT(DISTINCT COALESCE(q.sales_rep_display_id, q.sales_rep_id)) AS rep_count
            FROM sales.quotation q
           WHERE q.ticket_id = t.ticket_id AND q.doc_status NOT IN ('SUPERSEDED', 'CANCELLED')) r
     WHERE t.status <> 'cancelled'
),
grp AS (
    SELECT name_key,
           -- The owning-rep deal with the lowest id survives; NULL when the rep owns none (group skipped below).
           MIN(ticket_id) FILTER (WHERE created_by = rep_id) AS survivor_id
      FROM t
     GROUP BY name_key
    HAVING COUNT(*) >= 2
       AND COUNT(DISTINCT deposit_policy) = 1
       AND COUNT(DISTINCT tender_requirement) = 1
       AND bool_and(container)
       AND bool_and(rep_count = 1)
       AND COUNT(DISTINCT rep_id) = 1
       AND MIN(ticket_id) FILTER (WHERE created_by = rep_id) IS NOT NULL
)
SELECT t.ticket_id, g.survivor_id
  FROM t JOIN grp g ON g.name_key = t.name_key;

-- A party of a merged ticket whose (customer, role) already exists on the survivor folds into that party.
CREATE TEMP TABLE v206_party_map ON COMMIT DROP AS
SELECT src.party_id AS from_party, dst.party_id AS to_party
  FROM v206_member m
  JOIN sales.deal_party src ON src.ticket_id = m.ticket_id
  JOIN sales.deal_party dst ON dst.ticket_id = m.survivor_id
                           AND dst.customer_id = src.customer_id AND dst.role = src.role
 WHERE m.ticket_id <> m.survivor_id;

UPDATE sales.quotation q
   SET party_id = pm.to_party
  FROM v206_party_map pm
 WHERE q.party_id = pm.from_party;

DELETE FROM sales.deal_party p USING v206_party_map pm WHERE p.party_id = pm.from_party;

-- Two merged tickets for the same customer (and role) that the survivor does NOT have: keep the lowest party,
-- fold the others into it, before moving, so the move cannot collide on uq_deal_party.
CREATE TEMP TABLE v206_dup_map ON COMMIT DROP AS
SELECT p.party_id AS from_party,
       MIN(p.party_id) OVER (PARTITION BY m.survivor_id, p.customer_id, p.role) AS to_party
  FROM v206_member m
  JOIN sales.deal_party p ON p.ticket_id = m.ticket_id
 WHERE m.ticket_id <> m.survivor_id;

UPDATE sales.quotation q
   SET party_id = dm.to_party
  FROM v206_dup_map dm
 WHERE q.party_id = dm.from_party AND dm.from_party <> dm.to_party;

DELETE FROM sales.deal_party p USING v206_dup_map dm
 WHERE p.party_id = dm.from_party AND dm.from_party <> dm.to_party;

-- The joining parties: one event each on the survivor, written before they move (the name comes from the row).
INSERT INTO sales.ticket_event (ticket_id, actor_id, actor_name, kind, message)
SELECT m.survivor_id, s.created_by, 'ระบบ (รวมดีลโครงการเดียวกัน)', 'DEAL_PARTY_ADDED',
       'รวมจากดีล ' || src.code || ': เพิ่มผู้รับเหมา ' || c.name
  FROM v206_member m
  JOIN sales.ticket src ON src.ticket_id = m.ticket_id
  JOIN sales.ticket s ON s.ticket_id = m.survivor_id
  JOIN sales.deal_party p ON p.ticket_id = m.ticket_id
  JOIN customers.customer c ON c.customer_id = p.customer_id
 WHERE m.ticket_id <> m.survivor_id;

UPDATE sales.deal_party p
   SET ticket_id = m.survivor_id, is_headline = FALSE, updated_at = now()
  FROM v206_member m
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
      JOIN v206_member m ON m.ticket_id = q2.ticket_id
     WHERE m.ticket_id <> m.survivor_id) r
 WHERE q.quotation_id = r.quotation_id;

-- Every party now on a survivor that has a live quotation is QUOTED (a merged ACTIVE row would otherwise stay
-- ACTIVE with a document).
UPDATE sales.deal_party p
   SET status = 'QUOTED', updated_at = now()
 WHERE p.status = 'ACTIVE'
   AND p.ticket_id IN (SELECT survivor_id FROM v206_member)
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
      FROM v206_member m JOIN sales.ticket t ON t.ticket_id = m.ticket_id
     GROUP BY m.survivor_id) best
 WHERE s.ticket_id = best.survivor_id AND s.sales_stage IS DISTINCT FROM best.stage;

INSERT INTO sales.ticket_event (ticket_id, actor_id, actor_name, kind, from_status, to_status, message)
SELECT m.ticket_id, t.created_by, 'ระบบ (รวมดีลโครงการเดียวกัน)', 'DEAL_MERGED_INTO', 'draft', 'cancelled',
       'รวมเข้าดีล ' || s.code || ' (โครงการเดียวกัน ผู้รับเหมาหลายราย) ใบเสนอราคาย้ายไปที่ดีลนั้นแล้ว'
  FROM v206_member m
  JOIN sales.ticket t ON t.ticket_id = m.ticket_id
  JOIN sales.ticket s ON s.ticket_id = m.survivor_id
 WHERE m.ticket_id <> m.survivor_id;

UPDATE sales.ticket t
   SET status = 'cancelled', lifecycle = 'CANCELLED', cancel_reason = 'OTHER', cancelled_at = now(), updated_at = now()
  FROM v206_member m
 WHERE t.ticket_id = m.ticket_id AND m.ticket_id <> m.survivor_id;
