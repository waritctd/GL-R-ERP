-- Step 7 of the sales pricing-flow redesign: Factory Purchase Order and Import Execution.
--
-- There is no existing procurement/purchase-order module in this codebase (unlike Step 6, which
-- bridged into a substantial existing deposit/payment pipeline) -- sales.ticket's own
-- fulfillment_status (IR_ISSUED -> IR_SENT -> SHIPPING -> CUSTOMS_CLEARANCE -> GOODS_RECEIVED,
-- th.co.glr.hr.ticket.FulfilmentStatus) is a bare sequence of string flags with no PO record, no
-- supplier detail, no ETA/ETD, no customs tracking, no landed-cost record, and no linkage to which
-- factory-quote revision was actually used. This migration genuinely builds that record -- it is
-- not bridging into something that already exists.
--
-- The PO's items are sourced from the EXACT chain the customer's selling price was already built
-- from and paid against: sales.pricing_decision (status = APPROVED) -> pricing_costing_id ->
-- sales.pricing_costing_item -> factory_quote_item -> factory_quote (revision) -> factory_config.
-- Import does not re-pick a factory or a price at PO time -- see
-- th.co.glr.hr.procurement.ProcurementService's own Javadoc for the read path that enforces this.

CREATE SEQUENCE sales.factory_purchase_order_no_seq START 1;

CREATE TABLE sales.factory_purchase_order (
    factory_purchase_order_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    po_number VARCHAR(40) NOT NULL UNIQUE,
    pricing_request_id BIGINT NOT NULL REFERENCES sales.pricing_request(pricing_request_id) ON DELETE RESTRICT,
    ticket_id BIGINT NOT NULL REFERENCES sales.ticket(ticket_id) ON DELETE RESTRICT,
    -- Snapshot only (mirrors sales.pricing_costing_item.factory_id/factory_name -- see V61, which
    -- itself carries no FK to factory_config for the same reason: factory_config rows can be
    -- renamed/removed after the fact, and this PO must keep reading what was true when it was
    -- raised, not whatever factory_config says today).
    factory_id BIGINT,
    factory_name VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    supplier_proforma_ref TEXT,
    supplier_payment_schedule_note TEXT,
    currency VARCHAR(10) NOT NULL,
    total_amount NUMERIC(18,4) NOT NULL DEFAULT 0,
    etd DATE,
    eta DATE,
    container_ref TEXT,
    customs_status VARCHAR(60),
    -- The REAL landed cost once goods actually clear customs, recorded on receipt -- distinct
    -- from sales.pricing_costing_item.total_landed_cost_thb's ESTIMATE from Step 2. Both stay
    -- visible (this table adds a new column rather than overwriting the estimate) for later
    -- variance reporting -- the task's own explicit instruction: "do not conflate the two."
    actual_landed_cost_thb NUMERIC(18,4),
    cancel_reason TEXT,
    -- Idempotency/audit trail for the creation batch that produced this row (ProcurementService
    -- .createPurchaseOrders creates one PO per distinct factory per call) -- mirrors every prior
    -- step's own client_request_id column. The actual idempotency guarantee for a retried batch
    -- is the natural key below (uq_factory_po_request_factory): a retry simply finds the existing
    -- row per factory and skips it. This column is corroborating evidence, not the sole guard.
    client_request_id UUID,
    created_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    received_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    CONSTRAINT chk_factory_po_status CHECK (status IN ('OPEN', 'SHIPPING', 'RECEIVED', 'CANCELLED')),
    -- One PO per factory per pricing request -- mirrors Step 2's own per-factory quote grouping
    -- (sales.factory_quote's own uq_factory_quote_current_per_factory precedent): a pricing
    -- request with 2 factories gets 2 POs, never 2 POs for the same factory on the same request.
    CONSTRAINT uq_factory_po_request_factory UNIQUE (pricing_request_id, factory_name)
);

CREATE INDEX idx_factory_po_pricing_request ON sales.factory_purchase_order(pricing_request_id);
CREATE INDEX idx_factory_po_ticket ON sales.factory_purchase_order(ticket_id);
CREATE INDEX idx_factory_po_status ON sales.factory_purchase_order(status);

-- NOT unique, unlike every prior step's own per-creator client_request_id index (e.g.
-- uq_pricing_decision_creator_client_request): ProcurementService.createPurchaseOrders is a
-- BATCH action that mints one PO PER FACTORY under a single client_request_id, so the same key
-- legitimately labels multiple rows in one call. The actual idempotency guarantee for a retried
-- batch is the natural key below (uq_factory_po_request_factory) instead -- a retry simply finds
-- the existing row per factory and skips it (see ProcurementRepository.findOpenIdByFactory). This
-- index exists only so ProcurementRepository.findByClientRequestId's replay lookup is not a
-- sequential scan.
CREATE INDEX idx_factory_po_creator_client_request
    ON sales.factory_purchase_order (created_by, client_request_id)
    WHERE client_request_id IS NOT NULL;

CREATE TABLE sales.factory_purchase_order_item (
    factory_purchase_order_item_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    factory_purchase_order_id BIGINT NOT NULL REFERENCES sales.factory_purchase_order(factory_purchase_order_id) ON DELETE CASCADE,
    -- The structured record of what was ALREADY decided and paid for by the customer -- traces
    -- back through pricing_costing_item -> factory_quote_item -> factory_quote (revision), never
    -- a fresh factory/price pick at PO time (see this file's own header comment).
    pricing_costing_item_id BIGINT NOT NULL REFERENCES sales.pricing_costing_item(pricing_costing_item_id) ON DELETE RESTRICT,
    pricing_request_item_id BIGINT NOT NULL REFERENCES sales.pricing_request_item(pricing_request_item_id) ON DELETE RESTRICT,
    -- Frozen at PO creation time, copied verbatim from the costing item -- never recomputed if
    -- costing is later touched (it can't be, post-SUBMITTED, per Step 3's immutability rules; see
    -- PricingRequestStatus.CEO_REVIEWING's own Javadoc).
    quantity NUMERIC(18,4) NOT NULL,
    unit_price NUMERIC(18,4) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    line_total NUMERIC(18,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A costing item can be procured on at most ONE purchase order, ever -- the no-double-
    -- procurement guard, and also what makes ProcurementRepository.insertItems' cross-tenant
    -- guard (WHERE pc.pricing_request_id = :pricingRequestId, in the Java, not here) safe to
    -- rely on: a costing item already consumed by PO A can never be silently re-attached to PO B.
    CONSTRAINT uq_factory_po_item_costing_item UNIQUE (pricing_costing_item_id)
);

CREATE INDEX idx_factory_po_item_po ON sales.factory_purchase_order_item(factory_purchase_order_id);
