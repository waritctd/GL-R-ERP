CREATE TABLE hr.file_attachment (
    attachment_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    domain        VARCHAR(40) NOT NULL,
    owner_id      BIGINT NOT NULL,
    file_name     VARCHAR(255) NOT NULL,
    file_path     VARCHAR(1000) NOT NULL,
    mime_type     VARCHAR(100),
    file_size     BIGINT,
    uploaded_by   BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    uploaded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_file_attachment_domain_nonblank CHECK (btrim(domain) <> '')
);

ALTER TABLE hr.leave_request
    ADD COLUMN attachment_id BIGINT REFERENCES hr.file_attachment(attachment_id) ON DELETE SET NULL;

CREATE INDEX idx_file_attachment_domain_owner
    ON hr.file_attachment(domain, owner_id);

CREATE INDEX idx_leave_request_attachment
    ON hr.leave_request(attachment_id);
