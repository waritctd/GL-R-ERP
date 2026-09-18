-- V184: per-factory import progress (S12–S17), price-free — there is NO purchase order.
--
-- The real import flow (owner ruling, 2026-09): Import generates an order-email template per
-- factory, sends it themselves outside the system, and records progress here. So this table is
-- deliberately price-free — no supplier price, proforma, or landed cost — which is why sales may
-- read it in full, unlike the dormant sales.factory_purchase_order module.
--
-- One row per (pricing_request, factory). The factory set for a deal is its distinct
-- factory_name_snapshot on sales.factory_quote (the same per-factory entity Import already emails);
-- rows are seeded from there when Import opens the tracker. ticket_id is denormalised for the
-- deal-detail panel and the cross-deal worklist.

CREATE TABLE sales.factory_import_progress (
    factory_import_progress_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pricing_request_id BIGINT NOT NULL REFERENCES sales.pricing_request(pricing_request_id) ON DELETE CASCADE,
    ticket_id          BIGINT NOT NULL REFERENCES sales.ticket(ticket_id) ON DELETE CASCADE,
    factory_name       VARCHAR(255) NOT NULL,
    -- Tracking starts at "สั่งซื้อผู้ผลิต" (S13 ORDERED): the IR (S12 IR_SENT) reaches Import
    -- before per-factory tracking begins, so IR_SENT is a valid earlier step but never the default.
    import_step        VARCHAR(30) NOT NULL DEFAULT 'ORDERED',
    import_step_at     TIMESTAMPTZ,
    eta                DATE,
    note               TEXT,
    updated_by         BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_factory_import_step CHECK (
        import_step IN ('IR_SENT', 'ORDERED', 'PICKED_UP', 'IN_TRANSIT', 'CUSTOMS_CLEARANCE', 'RECEIVED')),
    CONSTRAINT uq_factory_import_progress UNIQUE (pricing_request_id, factory_name)
);

CREATE INDEX idx_factory_import_progress_ticket ON sales.factory_import_progress(ticket_id);
