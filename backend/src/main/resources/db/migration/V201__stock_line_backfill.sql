-- V201: stock lines of deals whose order was confirmed BEFORE order confirmation began writing
-- ticket_item.qty_from_stock (walk 4, owner ruling 2026-10-29: a stock line is never ordered from a factory).
--
-- 1. A ticket line of a live (not closed / lost / cancelled) deal fed by an IN_THAILAND line of an order-confirmed
--    pricing request is covered in full (qty_from_stock = qty), unless it is already on an ISSUED import request (a factory order is out: left
--    alone) or a confirmed request also maps it as an import line (the data is ambiguous: left alone).
-- 2. A deal that is now all-stock (ACTIVE, no fulfilment yet, every line covered) with its deposit settled
--    (paid or later, or a no-deposit policy at CUSTOMER_CONFIRMED - TicketService#depositSettled) goes to
--    fulfillment_status FROM_STOCK and, when still earlier, to DELIVERY_SCHEDULING with a STAGE_CHANGED event.
-- Idempotent: step 1 only raises qty_from_stock, and step 2 only picks deals with no fulfilment status.

UPDATE sales.ticket_item ti
   SET qty_from_stock = ti.qty
 WHERE ti.qty_from_stock < ti.qty
   -- Closed/lost deals are history: raising qty_from_stock there would retroactively hand pending commission
   -- the x2/x3 stock share. ON_HOLD/DORMANT deals are temporary and still covered.
   AND EXISTS (SELECT 1 FROM sales.ticket tk
                WHERE tk.ticket_id = ti.ticket_id
                  AND tk.lifecycle NOT IN ('CLOSED_LOST', 'CANCELLED', 'COMPLETED'))
   AND EXISTS (SELECT 1
                 FROM sales.pricing_request_item pri
                 JOIN sales.pricing_request pr ON pr.pricing_request_id = pri.pricing_request_id
                WHERE pri.source_ticket_item_id = ti.item_id
                  AND pri.stock_source = 'IN_THAILAND'
                  AND pr.order_confirmed_at IS NOT NULL)
   AND NOT EXISTS (SELECT 1
                     FROM sales.pricing_request_item pri
                     JOIN sales.pricing_request pr ON pr.pricing_request_id = pri.pricing_request_id
                    WHERE pri.source_ticket_item_id = ti.item_id
                      AND pri.stock_source IS NULL
                      AND pr.order_confirmed_at IS NOT NULL)
   AND NOT EXISTS (SELECT 1
                     FROM sales.import_request_item iri
                     JOIN sales.import_request ir ON ir.import_request_id = iri.import_request_id
                    WHERE iri.ticket_item_id = ti.item_id
                      AND ir.status = 'ISSUED');

WITH stage_order AS (
    SELECT ARRAY['LEAD_APPROACH', 'PRESENTATION', 'SPEC_APPROVED', 'QUOTE_DESIGN_SIDE', 'QUOTE_OWNER',
                 'OWNER_SIGNOFF', 'AWAITING_BUYER', 'QUOTE_BUYER', 'NEGOTIATION', 'ORDER_RECEIVED',
                 'DEPOSIT_RECEIVED', 'PROCUREMENT', 'DELIVERY_SCHEDULING', 'DELIVERED', 'CLOSED_PAID']::text[] AS stages
),
routable AS (
    SELECT t.ticket_id, t.sales_stage AS old_stage, t.created_by,
           array_position(so.stages, t.sales_stage) < array_position(so.stages, 'DELIVERY_SCHEDULING') AS moves_stage
      FROM sales.ticket t, stage_order so
     WHERE t.lifecycle = 'ACTIVE'
       AND t.fulfillment_status IS NULL
       AND EXISTS (SELECT 1 FROM sales.ticket_item ti WHERE ti.ticket_id = t.ticket_id)
       AND NOT EXISTS (SELECT 1 FROM sales.ticket_item ti
                        WHERE ti.ticket_id = t.ticket_id AND ti.qty_from_stock < ti.qty)
       AND (t.payment_status IN ('DEPOSIT_PAID', 'AWAITING_FINAL_PAYMENT', 'FULLY_PAID')
            OR (t.deposit_policy IN ('NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER')
                AND t.payment_status = 'CUSTOMER_CONFIRMED'))
),
moved AS (
    UPDATE sales.ticket t
       SET fulfillment_status = 'FROM_STOCK',
           sales_stage = CASE WHEN r.moves_stage THEN 'DELIVERY_SCHEDULING' ELSE t.sales_stage END,
           stage_updated_at = CASE WHEN r.moves_stage THEN now() ELSE t.stage_updated_at END
      FROM routable r
     WHERE t.ticket_id = r.ticket_id
    RETURNING t.ticket_id
)
INSERT INTO sales.ticket_event (ticket_id, actor_id, actor_name, kind, from_status, to_status, message)
SELECT r.ticket_id, r.created_by, 'ระบบ (V201)', 'STAGE_CHANGED', r.old_stage, 'DELIVERY_SCHEDULING',
       'ย้ายอัตโนมัติ: ดีลสินค้าจากสต็อกทั้งหมด และรับมัดจำแล้ว'
  FROM routable r
  JOIN moved m ON m.ticket_id = r.ticket_id
 WHERE r.moves_stage;
