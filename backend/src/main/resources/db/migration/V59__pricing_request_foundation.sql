-- =============================================================
-- Sales Module: Pricing Request foundation
-- Introduces the PricingRequest aggregate: 1 Ticket (deal) = 0..N
-- Pricing Requests. This migration adds schema only; no existing
-- sales.ticket / sales.ticket_event behaviour changes.
-- =============================================================

-- Auto-increment sequence for human-readable codes (e.g. PCR-2026-0001)
CREATE SEQUENCE sales.pricing_request_code_seq START 1;

-- Core pricing request
CREATE TABLE sales.pricing_request (
    pricing_request_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_code             VARCHAR(30)  NOT NULL UNIQUE,
    ticket_id                BIGINT NOT NULL REFERENCES sales.ticket(ticket_id) ON DELETE RESTRICT,
    recipient_type            VARCHAR(20)  NOT NULL,
    recipient_contact_id      BIGINT REFERENCES customers.contact(contact_id) ON DELETE SET NULL,
    recipient_label           VARCHAR(255),
    status                    VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
    requested_by              BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE RESTRICT,
    assigned_import_id        BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    required_date             DATE,
    customer_target_price     NUMERIC(18,2),
    -- VARCHAR(10), not (3), to match the existing sales.ticket_item.currency column.
    target_currency           VARCHAR(10),
    note                      TEXT,
    parent_pricing_request_id BIGINT REFERENCES sales.pricing_request(pricing_request_id) ON DELETE RESTRICT,
    revision_no               INTEGER NOT NULL DEFAULT 1,
    revision_reason           TEXT,
    submitted_at              TIMESTAMPTZ,
    picked_up_at               TIMESTAMPTZ,
    cancelled_at              TIMESTAMPTZ,
    cancelled_by              BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_pricing_request_recipient_type CHECK (recipient_type IN ('DESIGNER', 'OWNER', 'BUYER')),
    CONSTRAINT chk_pricing_request_status CHECK (status IN (
        'DRAFT', 'SUBMITTED', 'IMPORT_REVIEWING', 'MORE_INFO_REQUIRED', 'CANCELLED'
    )),
    CONSTRAINT chk_pricing_request_revision CHECK (revision_no >= 1),
    CONSTRAINT chk_pricing_request_target_price CHECK (customer_target_price IS NULL OR customer_target_price >= 0),
    -- You cannot be picked up before you were submitted; backstops a bad
    -- round-trip UPDATE (e.g. a partial-field save that clears submitted_at
    -- but leaves picked_up_at set) from commit 2 onward.
    CONSTRAINT chk_pricing_request_pickup_order CHECK (picked_up_at IS NULL OR submitted_at IS NOT NULL),
    CONSTRAINT chk_pricing_request_cancelled_pair CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL),
    CONSTRAINT chk_pricing_request_parent_not_self CHECK (
        parent_pricing_request_id IS NULL OR parent_pricing_request_id <> pricing_request_id
    )
);

-- Line items requested within a pricing request
CREATE TABLE sales.pricing_request_item (
    pricing_request_item_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pricing_request_id      BIGINT NOT NULL REFERENCES sales.pricing_request(pricing_request_id) ON DELETE CASCADE,
    source_ticket_item_id    BIGINT REFERENCES sales.ticket_item(item_id) ON DELETE SET NULL,
    product_id               BIGINT REFERENCES sales.catalog(catalog_id) ON DELETE SET NULL,
    -- No product-variant table exists in this schema yet. variant_id is an
    -- unconstrained forward-compatibility placeholder (unlike product_id,
    -- which has a real referent in sales.catalog) until one is introduced.
    variant_id               BIGINT,
    brand                    VARCHAR(255),
    model                    VARCHAR(255),
    color                    VARCHAR(255),
    texture                  VARCHAR(255),
    size                     VARCHAR(255),
    factory                  VARCHAR(255),
    requested_qty            NUMERIC(18,4) NOT NULL,
    requested_qty_sqm        NUMERIC(18,4),
    requested_unit           VARCHAR(30) NOT NULL,
    quantity_type            VARCHAR(20) NOT NULL,
    target_delivery_date     DATE,
    delivery_location        TEXT,
    special_requirement      TEXT,
    sort_order               INTEGER NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_pricing_request_item_qty CHECK (requested_qty > 0),
    CONSTRAINT chk_pricing_request_item_qty_sqm CHECK (requested_qty_sqm IS NULL OR requested_qty_sqm >= 0),
    CONSTRAINT chk_pricing_request_item_quantity_type CHECK (quantity_type IN ('REFERENCE', 'ESTIMATE', 'CONFIRMED'))
);

-- Audit / timeline events for a pricing request
CREATE TABLE sales.pricing_request_event (
    pricing_request_event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pricing_request_id       BIGINT NOT NULL REFERENCES sales.pricing_request(pricing_request_id) ON DELETE CASCADE,
    ticket_id                 BIGINT NOT NULL REFERENCES sales.ticket(ticket_id) ON DELETE RESTRICT,
    -- Unlike sales.ticket_event.actor_id (NOT NULL), this is nullable with
    -- ON DELETE SET NULL so events survive employee deletion; readers fall
    -- back to the denormalised actor_name below. This divergence from
    -- ticket_event is deliberate — do not "fix" it into NOT NULL.
    actor_id                  BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    actor_name                VARCHAR(255),
    event_kind                VARCHAR(50) NOT NULL,
    from_status               VARCHAR(40),
    to_status                 VARCHAR(40),
    message                   TEXT,
    metadata                  JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes
CREATE INDEX idx_pricing_request_ticket            ON sales.pricing_request(ticket_id);
CREATE INDEX idx_pricing_request_status            ON sales.pricing_request(status);
CREATE INDEX idx_pricing_request_assigned_import    ON sales.pricing_request(assigned_import_id, status);
CREATE INDEX idx_pricing_request_requested_by       ON sales.pricing_request(requested_by, created_at DESC);
CREATE INDEX idx_pricing_request_required_date      ON sales.pricing_request(required_date) WHERE status <> 'CANCELLED';
CREATE INDEX idx_pricing_request_item_request       ON sales.pricing_request_item(pricing_request_id, sort_order);
CREATE INDEX idx_pricing_request_event_request      ON sales.pricing_request_event(pricing_request_id, created_at, pricing_request_event_id);
CREATE UNIQUE INDEX uq_pricing_request_parent_revision ON sales.pricing_request(parent_pricing_request_id, revision_no)
    WHERE parent_pricing_request_id IS NOT NULL;
