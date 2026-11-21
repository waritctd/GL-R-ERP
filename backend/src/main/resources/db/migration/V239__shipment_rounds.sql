-- V239: shipment rounds (รอบขนส่ง) and lots (ล็อต). A round is one factory shipment: it carries the import step, the real
-- date of every step it has passed, and the shipment facts (proforma/invoice no., vessel, ETD/ETA, customs, GR number).
-- A lot is a quantity of one import-request line riding on one round. Slice 1 only lays the model down: every ISSUED
-- import request gets its own round (source MIGRATED) with one full-quantity lot per line, and sales.import_request keeps
-- import_step, which every reader still uses. The proforma/invoice number rule is applied by the service to PORTAL
-- rounds only, so it is deliberately NOT a constraint: MIGRATED and BACKFILL rounds are exempt for good.
--
-- Rollback: DROP TABLE sales.shipment_lot; DROP TABLE sales.shipment_round; sales.import_request is untouched.

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
    proforma_no          VARCHAR(100),
    invoice_no           VARCHAR(100),
    proforma_attachment_id BIGINT REFERENCES sales.attachment(attachment_id) ON DELETE SET NULL,
    invoice_attachment_id  BIGINT REFERENCES sales.attachment(attachment_id) ON DELETE SET NULL,
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

CREATE TABLE sales.shipment_lot (
    shipment_lot_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shipment_round_id       BIGINT NOT NULL REFERENCES sales.shipment_round(shipment_round_id) ON DELETE CASCADE,
    import_request_id       BIGINT NOT NULL REFERENCES sales.import_request(import_request_id) ON DELETE CASCADE,
    import_request_item_id  BIGINT NOT NULL REFERENCES sales.import_request_item(import_request_item_id) ON DELETE CASCADE,
    qty                     NUMERIC(12,2) NOT NULL CHECK (qty > 0),
    CONSTRAINT uq_shipment_lot_round_item UNIQUE (shipment_round_id, import_request_item_id)
);
CREATE INDEX ix_shipment_lot_import_request ON sales.shipment_lot(import_request_id);
CREATE INDEX ix_shipment_lot_item ON sales.shipment_lot(import_request_item_id);

COMMENT ON TABLE sales.shipment_round IS 'One factory shipment: import step, real step dates and shipment facts. Its step is the step of every lot on it.';
COMMENT ON TABLE sales.shipment_lot IS 'A quantity of one import-request line riding on one shipment round.';
COMMENT ON COLUMN sales.shipment_round.source IS 'PORTAL = made in the portal (number rule applies); MIGRATED = one per ISSUED IR at V239; BACKFILL = loaded from the import sheet. The last two are exempt from the proforma/invoice number rule.';

-- One round per ISSUED import request that has lines, at its current step; the date of that step is the step's own stamp.
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
