-- V239: shipment rounds (รอบขนส่ง) and lots (ล็อต). A round is one factory shipment: it carries the import step, the real
-- date of every step it has passed, and the shipment facts (vessel, ETD/ETA, customs, GR number).
-- A lot is a quantity of one import-request line riding on one round. Slice 1 only lays the model down: every ISSUED
-- import request gets its own round (source MIGRATED) with one full-quantity lot per line, and sales.import_request keeps
-- import_step, which every reader still uses. The proforma/invoice number rule (RECEIVED needs one of each) is applied by the service to PORTAL
-- rounds only, so it is deliberately NOT a constraint: MIGRATED and BACKFILL rounds are exempt for good.
--
-- It also lets an import request exist WITHOUT a deal (a sample, showroom or stock order): ticket_id becomes nullable, and
-- such a row must carry its purpose, requester, who/where note and wanted-by date. hr.employee.can_see_showroom_orders lets
-- chosen people read every SHOWROOM order (a grant by flag, never by name).
--
-- Rollback, in this order:
--   1. DROP TABLE sales.shipment_lot; DROP TABLE sales.shipment_round_document; DROP TABLE sales.shipment_round;
--   2. delete every no-project order (they have no deal to go back to; withdrawn ones included), items first:
--      DELETE FROM sales.import_request_item WHERE import_request_id IN (SELECT import_request_id FROM sales.import_request WHERE ticket_id IS NULL);
--      DELETE FROM sales.import_request WHERE ticket_id IS NULL;
--   3. ALTER TABLE sales.import_request DROP CONSTRAINT chk_import_request_project_or_order,
--      DROP CONSTRAINT chk_import_request_order_purpose, DROP COLUMN wanted_by, DROP COLUMN purpose_note,
--      DROP COLUMN requester_id, DROP COLUMN order_purpose, ALTER COLUMN ticket_id SET NOT NULL;
--   4. ALTER TABLE hr.employee DROP COLUMN can_see_showroom_orders;
--   5. DELETE FROM hr.flyway_schema_history WHERE version = '239';  (so Flyway applies it again, not 'missing')

CREATE TABLE sales.shipment_round (
    shipment_round_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    factory_id           BIGINT REFERENCES price_catalog.factories(factory_id),
    factory_name         VARCHAR(255),
    label                VARCHAR(100),
    source               VARCHAR(10) NOT NULL
        CONSTRAINT chk_shipment_round_source CHECK (source IN ('PORTAL','MIGRATED','BACKFILL')),
    import_step          VARCHAR(20) NOT NULL
        CONSTRAINT chk_shipment_round_step CHECK (import_step IN (
            'CONTACTED', 'ORDERED', 'PICKED_UP', 'IN_TRANSIT', 'AWAITING_CUSTOMS', 'RECEIVED')),
    contacted_on         DATE,
    ordered_on           DATE,
    picked_up_on         DATE,
    in_transit_on        DATE,
    awaiting_customs_on  DATE,
    received_on          DATE,
    step_by_id           BIGINT,
    step_by_name         VARCHAR(200),
    step_note            VARCHAR(500),
    step_undo_from       VARCHAR(20),
    step_undo_by_id      BIGINT,
    step_advanced_at     TIMESTAMPTZ,
    -- Names the latest advance: an undo must quote it, so a delayed retry of an earlier undo cannot hit a later advance.
    step_advance_id      UUID,
    -- The row's step, dates, by/note and facts as they were before the latest advance, so the 30-second undo restores
    -- exactly what that advance wrote.
    step_undo_snapshot   JSONB,
    transport_mode       VARCHAR(5) NOT NULL DEFAULT 'SEA'
        CONSTRAINT chk_shipment_round_transport CHECK (transport_mode IN ('SEA','AIR','LAND')),
    forwarder            VARCHAR(200),
    container_nos        VARCHAR(500),
    voyage               VARCHAR(100),
    note                 VARCHAR(1000),
    vessel               VARCHAR(200),
    goods_ready_on       DATE,
    etd_planned_on       DATE,
    etd_actual_on        DATE,
    eta_on               DATE,
    port                 VARCHAR(100),
    arrived_on           DATE,
    customs_released_on  DATE,
    gr_number            VARCHAR(100),
    complete_per_invoice BOOLEAN,
    complete_note        VARCHAR(500),
    migrated_from_import_request_id BIGINT,
    created_by_id        BIGINT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Documents of a round: proforma, commercial invoice, packing list, bill of lading, certificate of origin, import entry,
-- other. MANY per round and kind (one shipment can carry several invoices or POs), and the same proforma number may sit on
-- several rounds, so numbers are not unique. A row needs a number or a file; the file is optional. Not sales.attachment
-- (that table belongs to one deal). Readable by import and the CEO only: a factory invoice shows cost prices and other
-- deals' lines.
CREATE TABLE sales.shipment_round_document (
    shipment_round_document_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shipment_round_id          BIGINT NOT NULL REFERENCES sales.shipment_round(shipment_round_id) ON DELETE CASCADE,
    kind                       VARCHAR(20) NOT NULL
        CONSTRAINT chk_shipment_round_document_kind CHECK (kind IN (
            'PROFORMA','INVOICE','PACKING_LIST','BILL_OF_LADING','CERT_ORIGIN','IMPORT_ENTRY','OTHER')),
    doc_no                     VARCHAR(100),
    doc_date                   DATE,
    note                       VARCHAR(255),
    file_name                  VARCHAR(500),
    file_path                  VARCHAR(1000),
    file_size                  BIGINT,
    mime_type                  VARCHAR(255),
    uploaded_by                BIGINT NOT NULL,
    -- Typed on the latest advance: its 30-second undo removes it with the advance.
    created_by_advance         BOOLEAN NOT NULL DEFAULT false,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_shipment_round_document_number_or_file CHECK (doc_no IS NOT NULL OR file_path IS NOT NULL)
);
CREATE INDEX ix_shipment_round_document_round ON sales.shipment_round_document(shipment_round_id);

CREATE TABLE sales.shipment_lot (
    shipment_lot_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shipment_round_id       BIGINT NOT NULL REFERENCES sales.shipment_round(shipment_round_id) ON DELETE CASCADE,
    import_request_id       BIGINT NOT NULL REFERENCES sales.import_request(import_request_id) ON DELETE CASCADE,
    import_request_item_id  BIGINT NOT NULL REFERENCES sales.import_request_item(import_request_item_id) ON DELETE CASCADE,
    qty                     NUMERIC(12,2) NOT NULL CHECK (qty > 0),
    -- NULL = the lot arrived in full. Set when goods arrive short (used by a later slice).
    received_qty            NUMERIC(12,2) CHECK (received_qty >= 0),
    CONSTRAINT uq_shipment_lot_round_item UNIQUE (shipment_round_id, import_request_item_id)
);
CREATE INDEX ix_shipment_lot_import_request ON sales.shipment_lot(import_request_id);
CREATE INDEX ix_shipment_lot_item ON sales.shipment_lot(import_request_item_id);

COMMENT ON TABLE sales.shipment_round IS 'One factory shipment: import step, real step dates and shipment facts. Its step is the step of every lot on it.';
COMMENT ON TABLE sales.shipment_lot IS 'A quantity of one import-request line riding on one shipment round.';
COMMENT ON COLUMN sales.shipment_round.source IS 'PORTAL = made in the portal (number rule applies); MIGRATED = one per ISSUED IR at V239; BACKFILL = loaded from the import sheet. The last two are exempt from the proforma/invoice number rule.';

-- One round per ISSUED import request that has lines, at its current step; the date of that step is the step's own stamp.
-- EVERY such IR gets its round, deals that are over included (CLOSED_LOST, CANCELLED, COMPLETED): reopenDeal can make a
-- CLOSED_LOST deal ACTIVE again, and an IR with no round would then be re-planned and lose its step and date. The service
-- hides a round whose lots are all on finished deals; it reappears when the deal does.
INSERT INTO sales.shipment_round (
    factory_id, factory_name, source, import_step,
    contacted_on, ordered_on, picked_up_on, in_transit_on, awaiting_customs_on, received_on,
    step_by_id, step_by_name, step_note, migrated_from_import_request_id)
SELECT r.factory_id, r.factory_name, 'MIGRATED', r.import_step,
       CASE WHEN r.import_step = 'CONTACTED'       THEN r.import_step_at END,
       CASE WHEN r.import_step = 'ORDERED'         THEN r.import_step_at END,
       CASE WHEN r.import_step = 'PICKED_UP'       THEN r.import_step_at END,
       CASE WHEN r.import_step = 'IN_TRANSIT'      THEN r.import_step_at END,
       CASE WHEN r.import_step = 'AWAITING_CUSTOMS' THEN r.import_step_at END,
       CASE WHEN r.import_step = 'RECEIVED'        THEN r.import_step_at END,
       r.import_step_by_id, r.import_step_by_name, r.import_step_note, r.import_request_id
  FROM sales.import_request r
 WHERE r.status = 'ISSUED'
   AND EXISTS (SELECT 1 FROM sales.import_request_item i WHERE i.import_request_id = r.import_request_id);

INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty)
SELECT s.shipment_round_id, i.import_request_id, i.import_request_item_id, i.qty
  FROM sales.shipment_round s
  JOIN sales.import_request_item i ON i.import_request_id = s.migrated_from_import_request_id;

-- Orders with no project (สั่งของไม่มีโครงการ): an import request owned by a requester and a purpose instead of a deal.
ALTER TABLE sales.import_request ALTER COLUMN ticket_id DROP NOT NULL;
ALTER TABLE sales.import_request
    ADD COLUMN order_purpose VARCHAR(10),
    ADD COLUMN requester_id  BIGINT REFERENCES hr.employee(employee_id),
    ADD COLUMN purpose_note  VARCHAR(255),
    ADD COLUMN wanted_by     DATE,
    ADD CONSTRAINT chk_import_request_order_purpose CHECK (order_purpose IS NULL OR order_purpose IN ('SAMPLE','SHOWROOM','STOCK')),
    ADD CONSTRAINT chk_import_request_project_or_order CHECK (
        ticket_id IS NOT NULL
        OR (order_purpose IS NOT NULL AND requester_id IS NOT NULL AND purpose_note IS NOT NULL AND wanted_by IS NOT NULL));
COMMENT ON COLUMN sales.import_request.order_purpose IS 'SAMPLE / SHOWROOM / STOCK for an order with no project (ticket_id NULL); NULL on a deal IR.';
COMMENT ON COLUMN sales.import_request.requester_id IS 'Who asked for a no-project order; they see it on ของที่ฉันสั่ง and get the lot bells.';

ALTER TABLE hr.employee ADD COLUMN can_see_showroom_orders BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN hr.employee.can_see_showroom_orders IS 'Reads every SHOWROOM no-project order (the showroom team), read only.';
