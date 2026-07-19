-- Step 2: Factory quote revisions and Import-controlled costing submission.
-- Forward-only: extends the PricingRequest aggregate without writing legacy
-- sales.ticket_item pricing columns or deal stage/status fields.

CREATE SEQUENCE sales.factory_quote_code_seq START 1;
CREATE SEQUENCE sales.pricing_costing_code_seq START 1;

ALTER TABLE sales.pricing_request
    DROP CONSTRAINT chk_pricing_request_status;

ALTER TABLE sales.pricing_request
    ADD CONSTRAINT chk_pricing_request_status CHECK (status IN (
        'DRAFT',
        'SUBMITTED',
        'IMPORT_REVIEWING',
        'AWAITING_FACTORY_RESPONSE',
        'COSTING_IN_PROGRESS',
        'READY_FOR_CEO_REVIEW',
        'MORE_INFO_REQUIRED',
        'CANCELLED',
        'SUPERSEDED'
    ));

ALTER TABLE sales.pricing_request
    ADD COLUMN resume_status VARCHAR(30),
    ADD COLUMN root_pricing_request_id BIGINT REFERENCES sales.pricing_request(pricing_request_id) ON DELETE RESTRICT,
    ADD COLUMN superseded_at TIMESTAMPTZ,
    ADD COLUMN superseded_by_pricing_request_id BIGINT REFERENCES sales.pricing_request(pricing_request_id) ON DELETE SET NULL,
    ADD CONSTRAINT chk_pricing_request_resume_status CHECK (
        resume_status IS NULL OR resume_status IN ('IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS')
    );

ALTER TABLE sales.pricing_request_item
    ADD COLUMN price_list_version_id BIGINT,
    ADD COLUMN catalog_price_id BIGINT,
    ADD COLUMN catalog_base_price NUMERIC(14,4),
    ADD COLUMN catalog_currency VARCHAR(10),
    ADD COLUMN catalog_effective_date DATE,
    ADD COLUMN resolved_factory_id BIGINT,
    ADD COLUMN resolved_factory_name VARCHAR(255),
    ADD COLUMN catalog_product_code TEXT,
    ADD COLUMN catalog_brand VARCHAR(255),
    ADD COLUMN catalog_collection VARCHAR(255),
    ADD COLUMN catalog_model TEXT;

CREATE TABLE sales.factory_quote (
    factory_quote_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    quote_code VARCHAR(40) NOT NULL UNIQUE,
    pricing_request_id BIGINT NOT NULL REFERENCES sales.pricing_request(pricing_request_id) ON DELETE CASCADE,
    factory_id BIGINT,
    factory_name_snapshot VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL,
    email_to VARCHAR(255),
    email_subject TEXT,
    email_body TEXT,
    email_provider_message_id TEXT,
    email_sent_at TIMESTAMPTZ,
    sent_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    supplier_quote_ref TEXT,
    default_currency VARCHAR(10),
    payment_terms TEXT,
    lead_time_text TEXT,
    note TEXT,
    negotiation_note TEXT,
    requested_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ,
    root_factory_quote_id BIGINT REFERENCES sales.factory_quote(factory_quote_id) ON DELETE RESTRICT,
    parent_factory_quote_id BIGINT REFERENCES sales.factory_quote(factory_quote_id) ON DELETE RESTRICT,
    revision_no INTEGER NOT NULL DEFAULT 1,
    revision_reason TEXT,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    client_request_id UUID,
    created_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    cancelled_at TIMESTAMPTZ,
    cancel_reason TEXT,
    CONSTRAINT chk_factory_quote_status CHECK (status IN (
        'DRAFT',
        'REQUESTED',
        'RESPONSE_RECEIVED',
        'NEGOTIATING',
        'READY_FOR_COSTING',
        'NOT_AVAILABLE',
        'SUPERSEDED',
        'CANCELLED'
    )),
    CONSTRAINT chk_factory_quote_revision CHECK (revision_no >= 1),
    CONSTRAINT chk_factory_quote_current_terminal CHECK (
        is_current OR status IN ('SUPERSEDED', 'CANCELLED')
    )
);

CREATE UNIQUE INDEX uq_factory_quote_current_factory
    ON sales.factory_quote (pricing_request_id, factory_name_snapshot)
    WHERE is_current = TRUE AND status <> 'CANCELLED';

CREATE UNIQUE INDEX uq_factory_quote_chain_revision
    ON sales.factory_quote (COALESCE(root_factory_quote_id, factory_quote_id), revision_no);

CREATE TABLE sales.factory_quote_item (
    factory_quote_item_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    factory_quote_id BIGINT NOT NULL REFERENCES sales.factory_quote(factory_quote_id) ON DELETE CASCADE,
    pricing_request_item_id BIGINT NOT NULL REFERENCES sales.pricing_request_item(pricing_request_item_id) ON DELETE RESTRICT,
    catalog_product_id_snapshot BIGINT,
    supplier_product_code TEXT,
    supplier_product_description TEXT,
    quoted_quantity NUMERIC(18,4) NOT NULL,
    quoted_unit VARCHAR(30) NOT NULL,
    unit_basis VARCHAR(30) NOT NULL,
    raw_unit_price NUMERIC(18,4),
    currency VARCHAR(10),
    minimum_order_quantity NUMERIC(18,4),
    sqm_per_unit NUMERIC(10,6),
    pieces_per_box NUMERIC(18,4),
    lead_time_text TEXT,
    availability_note TEXT,
    line_note TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT chk_factory_quote_item_price CHECK (raw_unit_price IS NULL OR raw_unit_price >= 0),
    CONSTRAINT uq_factory_quote_item_request_item UNIQUE (factory_quote_id, pricing_request_item_id)
);

CREATE TABLE sales.pricing_costing (
    pricing_costing_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    costing_code VARCHAR(40) NOT NULL UNIQUE,
    pricing_request_id BIGINT NOT NULL REFERENCES sales.pricing_request(pricing_request_id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    stale BOOLEAN NOT NULL DEFAULT FALSE,
    stale_reason TEXT,
    note TEXT,
    client_request_id UUID,
    created_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    calculated_at TIMESTAMPTZ,
    submitted_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    submitted_at TIMESTAMPTZ,
    total_landed_cost_thb NUMERIC(18,4),
    CONSTRAINT chk_pricing_costing_status CHECK (status IN ('DRAFT', 'CALCULATED', 'SUBMITTED', 'SUPERSEDED', 'CANCELLED')),
    CONSTRAINT chk_pricing_costing_version CHECK (version_no >= 1),
    CONSTRAINT uq_pricing_costing_version UNIQUE (pricing_request_id, version_no)
);

CREATE UNIQUE INDEX uq_pricing_costing_open_draft
    ON sales.pricing_costing (pricing_request_id)
    WHERE status IN ('DRAFT', 'CALCULATED');

CREATE TABLE sales.pricing_costing_item (
    pricing_costing_item_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pricing_costing_id BIGINT NOT NULL REFERENCES sales.pricing_costing(pricing_costing_id) ON DELETE CASCADE,
    pricing_request_item_id BIGINT NOT NULL REFERENCES sales.pricing_request_item(pricing_request_item_id) ON DELETE RESTRICT,
    factory_quote_id BIGINT NOT NULL REFERENCES sales.factory_quote(factory_quote_id) ON DELETE RESTRICT,
    factory_quote_item_id BIGINT NOT NULL REFERENCES sales.factory_quote_item(factory_quote_item_id) ON DELETE RESTRICT,
    factory_quote_revision_no INTEGER NOT NULL,
    factory_id BIGINT,
    factory_name VARCHAR(255) NOT NULL,
    supplier_quote_ref TEXT,
    raw_unit_price NUMERIC(18,4) NOT NULL,
    raw_currency VARCHAR(10) NOT NULL,
    raw_unit VARCHAR(30) NOT NULL,
    unit_basis VARCHAR(30) NOT NULL,
    requested_quantity NUMERIC(18,4) NOT NULL,
    requested_unit VARCHAR(30) NOT NULL,
    sqm_per_unit NUMERIC(10,6),
    pieces_per_box NUMERIC(18,4),
    fx_rate NUMERIC(12,6) NOT NULL,
    fx_source VARCHAR(30) NOT NULL,
    fx_effective_date DATE NOT NULL,
    fx_fetched_at TIMESTAMPTZ,
    calculation_config_id BIGINT NOT NULL,
    calculation_config_version INTEGER NOT NULL,
    goods_cost_thb NUMERIC(18,4) NOT NULL,
    freight_cost_thb NUMERIC(18,4) NOT NULL,
    insurance_cost_thb NUMERIC(18,4) NOT NULL,
    import_duty_thb NUMERIC(18,4) NOT NULL,
    inland_transport_cost_thb NUMERIC(18,4) NOT NULL,
    other_cost_thb NUMERIC(18,4) NOT NULL DEFAULT 0,
    cif_cost_thb NUMERIC(18,4) NOT NULL,
    landed_cost_per_unit_thb NUMERIC(18,4) NOT NULL,
    total_landed_cost_thb NUMERIC(18,4) NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL,
    calculation_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_factory_quote_request ON sales.factory_quote(pricing_request_id, factory_name_snapshot);
CREATE INDEX idx_factory_quote_root ON sales.factory_quote(root_factory_quote_id, revision_no);
CREATE INDEX idx_factory_quote_item_request ON sales.factory_quote_item(pricing_request_item_id);
CREATE INDEX idx_pricing_costing_request ON sales.pricing_costing(pricing_request_id, version_no);
