CREATE TABLE sales.factory_quote_email_dispatch (
    factory_quote_email_dispatch_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    factory_quote_id BIGINT NOT NULL REFERENCES sales.factory_quote(factory_quote_id) ON DELETE CASCADE,
    client_request_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    email_to VARCHAR(255) NOT NULL,
    email_subject TEXT,
    email_body TEXT,
    created_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sending_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    failure_message TEXT,
    CONSTRAINT chk_factory_quote_email_dispatch_status CHECK (
        status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')
    )
);

CREATE UNIQUE INDEX uq_factory_quote_email_dispatch_creator_client
    ON sales.factory_quote_email_dispatch (created_by, client_request_id)
    WHERE client_request_id IS NOT NULL;

CREATE UNIQUE INDEX uq_factory_quote_email_dispatch_active_quote
    ON sales.factory_quote_email_dispatch (factory_quote_id)
    WHERE status IN ('PENDING', 'SENDING', 'SENT');

CREATE INDEX idx_factory_quote_email_dispatch_quote
    ON sales.factory_quote_email_dispatch (factory_quote_id, status);

CREATE INDEX idx_file_attachment_factory_quote
    ON hr.file_attachment(domain, owner_id, uploaded_at DESC)
    WHERE domain = 'factory_quote';
