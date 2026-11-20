-- The last attachment choice a sales user made when downloading a deposit notice / remaining invoice PDF: which deal
-- attachments (in order) go into the bundle, and whether the referenced quotation does. No bytes are stored; a deleted
-- attachment drops out of the selection (ON DELETE CASCADE). Nothing is frozen at issue.
CREATE TABLE sales.document_bundle_pref (
    document_kind     TEXT    NOT NULL CHECK (document_kind IN ('DEPOSIT_NOTICE', 'REMAINING_INVOICE')),
    document_id       BIGINT  NOT NULL,
    include_quotation BOOLEAN NOT NULL,
    PRIMARY KEY (document_kind, document_id)
);

CREATE TABLE sales.document_attachment_selection (
    document_kind TEXT   NOT NULL CHECK (document_kind IN ('DEPOSIT_NOTICE', 'REMAINING_INVOICE')),
    document_id   BIGINT NOT NULL,
    attachment_id BIGINT NOT NULL REFERENCES sales.attachment(attachment_id) ON DELETE CASCADE,
    seq           INT    NOT NULL,
    PRIMARY KEY (document_kind, document_id, attachment_id)
);
