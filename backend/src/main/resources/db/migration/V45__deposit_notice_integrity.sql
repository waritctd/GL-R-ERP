-- Guard deposit notice numbering/version assumptions that the services rely on.
CREATE UNIQUE INDEX IF NOT EXISTS ux_deposit_notice_ticket_version
    ON sales.deposit_notice(ticket_id, version);

CREATE UNIQUE INDEX IF NOT EXISTS ux_deposit_notice_doc_number
    ON sales.deposit_notice(doc_number)
    WHERE doc_number IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_attachment_quotation
    ON sales.attachment(quotation_id);
