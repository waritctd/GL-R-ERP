-- Q4: Document versioning + revision flow

-- Ticket gains revision counter and document_issued status support
ALTER TABLE sales.ticket ADD COLUMN revision_no INT NOT NULL DEFAULT 1;

-- Per-type-per-year document number sequence (atomic increment)
CREATE TABLE sales.document_sequence (
    doc_type  VARCHAR(30) NOT NULL,
    year_th   INT         NOT NULL,
    last_seq  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (doc_type, year_th)
);

-- Main document table (generic — supports DEPOSIT_NOTICE now, QUOTATION/INVOICE later)
CREATE TABLE sales.document (
    document_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id        BIGINT NOT NULL REFERENCES sales.ticket(ticket_id),
    doc_type         VARCHAR(30) NOT NULL DEFAULT 'DEPOSIT_NOTICE',
    version          INT NOT NULL DEFAULT 1,
    doc_number       VARCHAR(30),
    issue_date       DATE,
    status           VARCHAR(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT | ISSUED | SUPERSEDED
    -- Customer snapshot (frozen at issue time)
    customer_name    VARCHAR(200),
    customer_tax_id  VARCHAR(20),
    customer_address TEXT,
    -- Content fields
    project_name     VARCHAR(200),
    reference        VARCHAR(200),
    currency         VARCHAR(3)  NOT NULL DEFAULT 'THB',
    deposit_percent  NUMERIC(5,4) NOT NULL DEFAULT 0.5,
    subtotal         NUMERIC(15,2),
    deposit_amount   NUMERIC(15,2),
    vat_percent      NUMERIC(5,4) NOT NULL DEFAULT 0.07,
    vat_amount       NUMERIC(15,2),
    total_payable    NUMERIC(15,2),
    notes            TEXT[]      NOT NULL DEFAULT '{}',
    -- Rendered file paths (set after render, null until then)
    pdf_path         VARCHAR(500),
    xlsx_path        VARCHAR(500),
    -- Audit
    issued_by_id     BIGINT,
    issued_by_name   VARCHAR(200),
    preparer_name    VARCHAR(200) NOT NULL DEFAULT 'จินตนา หาญมนตรี',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_document_ticket ON sales.document(ticket_id);

-- Snapshot of items at issue time (immutable after issue)
CREATE TABLE sales.document_item (
    item_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id    BIGINT NOT NULL REFERENCES sales.document(document_id),
    seq            INT    NOT NULL,
    description    TEXT   NOT NULL,
    qty            NUMERIC(10,2) NOT NULL,
    unit           VARCHAR(50) NOT NULL DEFAULT 'แผ่น',
    unit_price     NUMERIC(15,2) NOT NULL,
    discount_label VARCHAR(100),
    net_unit_price NUMERIC(15,2) NOT NULL,
    amount         NUMERIC(15,2) NOT NULL
);

-- Extend event kind constraint
ALTER TABLE sales.ticket_event DROP CONSTRAINT IF EXISTS chk_event_kind;
ALTER TABLE sales.ticket_event ADD CONSTRAINT chk_event_kind CHECK (kind IN (
    'CREATED','SUBMITTED','PICKED_UP','PRICE_PROPOSED',
    'APPROVED','REJECTED','QUOTATION_ISSUED',
    'COMMENTED','CLOSED','CANCELLED','EDITED',
    'DOCUMENT_ISSUED','REVISION_REQUESTED'
));
