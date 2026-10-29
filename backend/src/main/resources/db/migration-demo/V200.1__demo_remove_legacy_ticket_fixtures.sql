-- Removes the legacy demo ticket fixtures DEMO-TKT-02/03/04 (QA-26, owner ruling 2026-10-29: "remove demo tkt").
--
-- db/migration-demo is loaded by the demo profile -- including the hosted showcase, which runs prod,demo
-- (render.yaml), so this DOES run on that live database. It matches those three ticket codes ONLY and is a
-- no-op wherever they do not exist.
--
-- V21 seeded them with the legacy ticket.status values submitted / price_proposed / approved, which no queue
-- holds any more -- they sat in the deal list as stuck, unexplained deals. DEMO-TKT-01 and -05 stay: the
-- e2e-real smoke / route-coverage / commission specs depend on them.
--
-- ticket_item and ticket_event cascade from the ticket; notification and deal_activity do not, so they go
-- first. A fixture someone has since worked on in the showcase (it now owns a quotation, pricing request,
-- deposit notice, payment, ...) is left whole -- ticket AND its notification / activity rows -- rather than
-- failing the migration and blocking boot. One guard, applied to all three deletes.

CREATE TEMP TABLE demo_tkt_to_remove ON COMMIT DROP AS
SELECT t.ticket_id
  FROM sales.ticket t
 WHERE t.code IN ('DEMO-TKT-02', 'DEMO-TKT-03', 'DEMO-TKT-04')
   AND NOT EXISTS (SELECT 1 FROM sales.quotation x WHERE x.ticket_id = t.ticket_id)
   AND NOT EXISTS (SELECT 1 FROM sales.pricing_request x WHERE x.ticket_id = t.ticket_id)
   AND NOT EXISTS (SELECT 1 FROM sales.pricing_request_event x WHERE x.ticket_id = t.ticket_id)
   AND NOT EXISTS (SELECT 1 FROM sales.deposit_notice x WHERE x.ticket_id = t.ticket_id)
   AND NOT EXISTS (SELECT 1 FROM sales.payment_receipt x WHERE x.ticket_id = t.ticket_id)
   AND NOT EXISTS (SELECT 1 FROM sales.delivery_record x WHERE x.ticket_id = t.ticket_id)
   AND NOT EXISTS (SELECT 1 FROM sales.factory_purchase_order x WHERE x.ticket_id = t.ticket_id)
   AND NOT EXISTS (SELECT 1 FROM sales.billing_note_line x WHERE x.ticket_id = t.ticket_id)
   AND NOT EXISTS (SELECT 1 FROM sales.commission_record x WHERE x.source_ticket_id = t.ticket_id);

DELETE FROM sales.notification WHERE ticket_id IN (SELECT ticket_id FROM demo_tkt_to_remove);

DELETE FROM sales.deal_activity WHERE ticket_id IN (SELECT ticket_id FROM demo_tkt_to_remove);

DELETE FROM sales.ticket WHERE ticket_id IN (SELECT ticket_id FROM demo_tkt_to_remove);
