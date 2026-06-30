-- =============================================================
-- Sales Commission Management
-- Stores invoice metadata, commission records, and tier config.
-- =============================================================

CREATE TABLE sales.invoice_details (
    invoice_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_number VARCHAR(80) NOT NULL UNIQUE,
    invoice_date   DATE NOT NULL,
    gross_amount   NUMERIC(14,2) NOT NULL,
    bank_fees      NUMERIC(14,2) NOT NULL DEFAULT 0,
    suspense_vat   NUMERIC(14,2) NOT NULL DEFAULT 0,
    transport_fee  NUMERIC(14,2) NOT NULL DEFAULT 0,
    cut_fee        NUMERIC(14,2) NOT NULL DEFAULT 0,
    shortfall      NUMERIC(14,2) NOT NULL DEFAULT 0,
    metadata       JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_invoice_amounts_nonnegative CHECK (
        gross_amount >= 0
        AND bank_fees >= 0
        AND suspense_vat >= 0
        AND transport_fee >= 0
        AND cut_fee >= 0
        AND shortfall >= 0
    )
);

CREATE TABLE sales.commission_record (
    commission_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_id          BIGINT NOT NULL REFERENCES sales.invoice_details(invoice_id),
    source_ticket_id    BIGINT REFERENCES sales.ticket(ticket_id),
    sales_rep_id        BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    submitted_by_id     BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    kind                VARCHAR(20) NOT NULL DEFAULT 'SALE',
    status              VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    payroll_month       DATE NOT NULL,
    actual_received     NUMERIC(14,2) NOT NULL,
    commissionable_base NUMERIC(14,2) NOT NULL,
    approved_by_id      BIGINT REFERENCES hr.employee(employee_id),
    approved_at         TIMESTAMPTZ,
    cancellation_of_id  BIGINT REFERENCES sales.commission_record(commission_id),
    cancellation_reason TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_commission_kind CHECK (kind IN ('SALE','CLAWBACK')),
    CONSTRAINT chk_commission_status CHECK (status IN ('SUBMITTED','APPROVED','VOID')),
    CONSTRAINT chk_commission_payroll_month_first CHECK (payroll_month = date_trunc('month', payroll_month)::date)
);

CREATE TABLE sales.tier_config (
    tier_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tier_number    SMALLINT NOT NULL UNIQUE,
    lower_bound    NUMERIC(14,2) NOT NULL,
    upper_bound    NUMERIC(14,2),
    rate_percent   NUMERIC(7,4) NOT NULL,
    is_high_roller BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_tier_bounds CHECK (upper_bound IS NULL OR upper_bound > lower_bound),
    CONSTRAINT chk_tier_rate CHECK (rate_percent >= 0)
);

INSERT INTO sales.tier_config (tier_number, lower_bound, upper_bound, rate_percent, is_high_roller)
VALUES
    (1, 0.00,       250000.00,  0.2500, FALSE),
    (2, 250000.00,  500000.00,  0.5000, FALSE),
    (3, 500000.00,  750000.00,  0.7500, FALSE),
    (4, 750000.00,  1000000.00, 1.0000, FALSE),
    (5, 1000000.00, 1250000.00, 1.2500, FALSE),
    (6, 1250000.00, 1500000.00, 1.5000, FALSE),
    (7, 1500000.00, 1750000.00, 1.7500, FALSE),
    (8, 1750000.00, 2000000.00, 2.0000, FALSE),
    (9, 2000000.00, 2250000.00, 2.2500, FALSE),
    (10, 2250000.00, 2500000.00, 2.5000, FALSE),
    (11, 2500000.00, 2750000.00, 2.7500, FALSE),
    (12, 2750000.00, 3000000.00, 3.0000, FALSE),
    (13, 3000000.00, NULL,      7.5000, TRUE);

CREATE INDEX idx_invoice_date ON sales.invoice_details(invoice_date DESC);
CREATE INDEX idx_commission_sales_rep ON sales.commission_record(sales_rep_id, payroll_month DESC);
CREATE INDEX idx_commission_payroll ON sales.commission_record(payroll_month, status);
CREATE INDEX idx_commission_ticket ON sales.commission_record(source_ticket_id);
CREATE INDEX idx_commission_invoice ON sales.commission_record(invoice_id);
