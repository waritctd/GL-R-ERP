-- V241: close out an IR line that points at no deal line. An issued IR line whose deal line is missing holds the deal's delivery
-- (v2.4.2); import or the CEO either links it to a deal line or closes it out: goods that will not be on this deal. The row,
-- its lots and its receipts stay as the record. A closed-out line is not wanted any more (nothing of it is still to come), does
-- not count as unlinked goods and never counts as delivered. Reversible: reopening clears all three fields (refused once the
-- deal is fully delivered).
--
-- Rollback: CREATE OR REPLACE VIEW sales.import_item_wanted as V240 defined it; ALTER TABLE sales.import_request_item
-- DROP CONSTRAINT chk_import_item_closed_out, DROP COLUMN closed_out_reason, closed_out_by, closed_out_at;
-- DELETE FROM flyway_schema_history WHERE version = '241' (in the schema Flyway uses). No backfill.

ALTER TABLE sales.import_request_item
    ADD COLUMN closed_out_reason VARCHAR(255),
    ADD COLUMN closed_out_by     BIGINT REFERENCES hr.employee(employee_id),
    ADD COLUMN closed_out_at     TIMESTAMPTZ,
    ADD CONSTRAINT chk_import_item_closed_out CHECK (
        (closed_out_reason IS NULL AND closed_out_by IS NULL AND closed_out_at IS NULL)
        OR (closed_out_reason IS NOT NULL AND btrim(closed_out_reason) <> '' AND closed_out_by IS NOT NULL AND closed_out_at IS NOT NULL));

-- V240's view, with one change: a closed-out line wants only what is already on lots (nothing still to come).
CREATE OR REPLACE VIEW sales.import_item_wanted AS
SELECT x.import_request_item_id,
       CASE WHEN x.closed THEN x.qty - x.unplaced
            ELSE x.qty - LEAST(x.unplaced, GREATEST(x.nd - x.before_unplaced, 0)) END AS wanted_qty
  FROM (
    SELECT i.import_request_item_id, i.qty, COALESCE(ti.qty_not_delivered, 0) AS nd,
           GREATEST(i.qty - COALESCE(p.placed, 0), 0) AS unplaced,
           i.closed_out_at IS NOT NULL AS closed,
           COALESCE(SUM(GREATEST(i.qty - COALESCE(p.placed, 0), 0)) OVER (
               PARTITION BY i.ticket_item_id ORDER BY i.import_request_id, i.seq
               ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS before_unplaced
      FROM sales.import_request_item i
      JOIN sales.import_request ir ON ir.import_request_id = i.import_request_id AND ir.status = 'ISSUED'
      LEFT JOIN sales.ticket_item ti ON ti.item_id = i.ticket_item_id
      LEFT JOIN (SELECT import_request_item_id, SUM(qty) AS placed FROM sales.shipment_lot GROUP BY import_request_item_id) p
             ON p.import_request_item_id = i.import_request_item_id
  ) x;
