-- =============================================================
-- Sales Module: Ticket / Price-Request schema
-- Schema: sales (separate from hr)
-- All user references point to hr.employee(employee_id)
-- because hr.app_user was removed in V5.
-- =============================================================

CREATE SCHEMA IF NOT EXISTS sales;

-- Auto-increment sequences for human-readable codes
CREATE SEQUENCE IF NOT EXISTS sales.ticket_code_seq    START 1;
CREATE SEQUENCE IF NOT EXISTS sales.quotation_code_seq START 1;

-- Core ticket
CREATE TABLE sales.ticket (
    ticket_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            VARCHAR(20)  NOT NULL UNIQUE,
    type            VARCHAR(20)  NOT NULL DEFAULT 'PRICE_REQUEST',
    title           VARCHAR(255) NOT NULL,
    status          VARCHAR(30)  NOT NULL DEFAULT 'draft',
    priority        VARCHAR(10)  NOT NULL DEFAULT 'NORMAL',
    created_by      BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    assigned_to     BIGINT       REFERENCES hr.employee(employee_id),
    customer_name   VARCHAR(255),
    note            TEXT,
    payment_status  VARCHAR(20),
    delivery_status VARCHAR(20),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    CONSTRAINT chk_ticket_status CHECK (status IN (
        'draft','submitted','in_review','price_proposed',
        'approved','rejected','quotation_issued','closed','cancelled'
    )),
    CONSTRAINT chk_ticket_priority CHECK (priority IN ('LOW','NORMAL','HIGH'))
);

-- Line items (1 ticket → many items)
CREATE TABLE sales.ticket_item (
    item_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id       BIGINT NOT NULL REFERENCES sales.ticket(ticket_id) ON DELETE CASCADE,
    product_code    VARCHAR(80),
    product_name    VARCHAR(255) NOT NULL,
    size            VARCHAR(80),
    color           VARCHAR(80),
    qty             NUMERIC(12,2) NOT NULL,
    unit            VARCHAR(30),
    proposed_price  NUMERIC(14,4),
    approved_price  NUMERIC(14,4),
    currency        VARCHAR(10) NOT NULL DEFAULT 'THB',
    sort_order      SMALLINT NOT NULL DEFAULT 0
);

-- Audit / timeline events
CREATE TABLE sales.ticket_event (
    event_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id       BIGINT NOT NULL REFERENCES sales.ticket(ticket_id) ON DELETE CASCADE,
    actor_id        BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    actor_name      VARCHAR(200),
    kind            VARCHAR(30) NOT NULL,
    from_status     VARCHAR(30),
    to_status       VARCHAR(30),
    message         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_event_kind CHECK (kind IN (
        'CREATED','SUBMITTED','PICKED_UP','PRICE_PROPOSED',
        'APPROVED','REJECTED','QUOTATION_ISSUED',
        'COMMENTED','CLOSED','CANCELLED'
    ))
);

-- Issued quotation
CREATE TABLE sales.quotation (
    quotation_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id       BIGINT NOT NULL REFERENCES sales.ticket(ticket_id),
    number          VARCHAR(30) NOT NULL UNIQUE,
    issued_by       BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    pdf_path        VARCHAR(500),
    total_amount    NUMERIC(14,2),
    currency        VARCHAR(10) NOT NULL DEFAULT 'THB'
);

-- In-app notifications
CREATE TABLE sales.notification (
    notification_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id     BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    ticket_id       BIGINT REFERENCES sales.ticket(ticket_id),
    type            VARCHAR(30) NOT NULL,
    message         TEXT NOT NULL,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes
CREATE INDEX idx_ticket_status      ON sales.ticket(status);
CREATE INDEX idx_ticket_created_by  ON sales.ticket(created_by);
CREATE INDEX idx_ticket_assigned_to ON sales.ticket(assigned_to);
CREATE INDEX idx_ticket_created_at  ON sales.ticket(created_at DESC);
CREATE INDEX idx_ticket_item        ON sales.ticket_item(ticket_id);
CREATE INDEX idx_ticket_event       ON sales.ticket_event(ticket_id, created_at);
CREATE INDEX idx_quotation_ticket   ON sales.quotation(ticket_id);
CREATE INDEX idx_notification_emp   ON sales.notification(employee_id, is_read, created_at DESC);
