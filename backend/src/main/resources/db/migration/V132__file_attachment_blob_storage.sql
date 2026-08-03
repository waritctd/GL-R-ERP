-- Storage-durability fix (2026-08-03): hr.file_attachment rows across all four of its domains
-- (leave, commission-invoice evidence, tax_allowance_declaration, factory_quote) have only ever
-- stored a filesystem PATH. On this app's actual deployment targets that path is not durable:
--
--   * Render (UAT today, and the demo showcase) attaches no disk to the backend service, and
--     APP_UPLOADS_DIR is unset, so uploads fall back to ./uploads inside the container -- the
--     container's own EPHEMERAL filesystem (see backend/Dockerfile:65). Every redeploy loses
--     100% of previously uploaded bytes while the hr.file_attachment / hr.leave_request rows that
--     point at them survive untouched, silently becoming orphans.
--   * Real production will be ON-PREM (Render+Supabase is UAT only), and even there, local disk
--     is a single-instance trap: it does not survive a redeploy to a new container/host, and it
--     does not work at all the moment a second app instance is added, because only one instance
--     would ever see a given upload.
--
-- Why Postgres bytea instead of continuing to chase a disk/backup answer:
--   * ONE backup that is atomically consistent. With bytes on disk and metadata in Postgres, a
--     database-only restore produces rows asserting a medical certificate/tax-invoice/factory-quote
--     document exists with nothing behind it -- a compliance problem for HR evidence, not a mere
--     inconvenience. A single pg_dump/pg_restore now backs up both the fact and the bytes together.
--   * It is the only storage design that behaves IDENTICALLY on-prem and on Render, with no
--     per-environment mount, path, or backup runbook to keep in sync -- and it removes the
--     multi-instance trap local disk creates outright (Postgres is already the one shared thing
--     every app instance talks to).
--   * bytea is the right call for SMALL documents: the upload allowlist is PDF/JPEG/PNG, a few
--     hundred files a year across all four domains. It would NOT be the right call for large media
--     -- REVISIT THIS DECISION if the MIME allowlist ever widens (e.g. video, large scans, bulk
--     photo evidence).
--   * Self-hosted MinIO/S3 was considered and rejected: it is another service to run, secure,
--     patch, and back up on-prem, for a few hundred small files a year -- not proportionate.
--
-- hr.file_attachment_blob is a SIBLING table, not a new column on hr.file_attachment itself, so
-- that every existing listing/resolving query against hr.file_attachment (none of which today do
-- `SELECT *` -- verified before writing this migration) keeps not loading payloads; bytes are read
-- only on download, via a dedicated repository method, after the caller has already been
-- authorized.
CREATE TABLE hr.file_attachment_blob (
    attachment_id BIGINT PRIMARY KEY REFERENCES hr.file_attachment(attachment_id) ON DELETE CASCADE,
    content       BYTEA NOT NULL
);

COMMENT ON TABLE hr.file_attachment_blob IS
    'V132: byte payload for an hr.file_attachment row, kept in a sibling table so listing/resolving an attachment never loads its content -- bytes are read here only on download, after authorization.';

-- storage_state tracks where a given row's bytes actually live, across three states:
--   DATABASE    - bytes are in hr.file_attachment_blob (the target state for every row going
--                 forward: new uploads across all four domains write here directly).
--   DISK_LEGACY - a pre-migration row whose bytes may still be on disk. The one-shot Java backfill
--                 (FileAttachmentDiskBackfillRunner, runs in the app process because that is the
--                 only place the files might still exist -- Flyway/pg_read_binary_file cannot read
--                 the APP container's filesystem from the DATABASE server, a different machine
--                 entirely on Supabase) either migrates it to DATABASE or marks it MISSING.
--   MISSING     - the bytes are confirmed gone (an orphan). The owner's ruling: KEEP the row, never
--                 delete it -- deleting would destroy the audit fact that a certificate/invoice/
--                 quote was once filed, and would silently NULL hr.leave_request.attachment_id
--                 (ON DELETE SET NULL) as a side effect, erasing evidence that a leave request ever
--                 had one.
ALTER TABLE hr.file_attachment
    ADD COLUMN storage_state VARCHAR(20) NOT NULL DEFAULT 'DISK_LEGACY'
        CHECK (storage_state IN ('DATABASE', 'DISK_LEGACY', 'MISSING'));

COMMENT ON COLUMN hr.file_attachment.storage_state IS
    'V132: DATABASE = bytes in hr.file_attachment_blob; DISK_LEGACY = pre-migration row, bytes may still be on disk; MISSING = bytes confirmed gone, row kept for the audit trail.';
