SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- LEAVE: §5 announcement PDF storage (rules-tab link, LeaveController#policyDocument /
-- #uploadPolicyDocument).
-- ---------------------------------------------------------------------
-- SCHEMA ONLY. No seed data, no document bytes. Two facts drove this:
--   1. This repo is PUBLIC on GitHub. A Flyway migration is committed to git, so embedding the
--      announcement's bytes here (e.g. base64) would publish an internal HR document to the open
--      internet -- the exact disclosure problem that already ruled out frontend/public/ for this
--      file. The PDF reaches the database only through POST /api/leave/policy-document (HR/CEO
--      only), never through a migration.
--   2. render.yaml declares no `disk:` block, so the container filesystem is recreated on every
--      deploy -- a filesystem path would lose the file on the next deploy, and real production is
--      now confirmed on-prem while UAT stays on Render/Supabase, so the storage mechanism has to
--      behave identically in both without a per-environment mount/backup runbook. Postgres (which
--      is already backed up) is the one place that works the same everywhere.
--
-- Bytes live in their own table (leave_policy_document_blob), separate from metadata
-- (leave_policy_document): listing/resolving "the current document" (LeavePolicyDocumentRepository
-- #findCurrent) never has to load the payload -- only #findContent, called once per actual
-- download, touches leave_policy_document_blob.
--
-- effective_from + retained superseded rows (never overwritten, never deleted) rather than one
-- mutable row: the 1 ต.ค. 2567 announcement itself superseded a 2561 one, and a rejection raised
-- under an older ruleset should eventually be able to cite the document that actually applied on
-- that date, not whatever is uploaded today (that citation feature is not built in this phase --
-- this schema just does not foreclose it). "Current" is resolved as the row with the latest
-- effective_from that is <= CURRENT_DATE, not an is_current flag -- a flag can drift out of sync
-- across concurrent/out-of-order uploads, while "latest effective date not in the future" is always
-- a pure function of the stored rows and needs no extra bookkeeping to stay correct.
--
-- Deliberately reuses none of hr.file_attachment's shape: that table's file_path is NOT NULL and
-- names a filesystem location, which this feature has none of (fact 2 above) -- widening that
-- constraint or faking a path would be worse than a small dedicated table.
CREATE TABLE hr.leave_policy_document (
    document_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_name       VARCHAR(255) NOT NULL,
    mime_type       VARCHAR(100) NOT NULL,
    file_size       BIGINT NOT NULL,
    effective_from  DATE NOT NULL,
    uploaded_by     BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_leave_policy_document_file_size_positive CHECK (file_size > 0)
);

-- DESC: #findCurrent's ORDER BY effective_from DESC is the only access pattern this index needs to
-- serve.
CREATE INDEX idx_leave_policy_document_effective_from
    ON hr.leave_policy_document(effective_from DESC);

CREATE TABLE hr.leave_policy_document_blob (
    document_id BIGINT PRIMARY KEY REFERENCES hr.leave_policy_document(document_id) ON DELETE CASCADE,
    content     BYTEA NOT NULL
);

COMMENT ON TABLE hr.leave_policy_document IS
    'Metadata for each uploaded version of the §5 leave-rules announcement PDF. Never seeded by a '
    'migration (repo is public) -- rows only ever come from POST /api/leave/policy-document '
    '(HR/CEO). "Current" = latest effective_from <= CURRENT_DATE; see idx_leave_policy_document_'
    'effective_from and LeavePolicyDocumentRepository#findCurrent.';
COMMENT ON TABLE hr.leave_policy_document_blob IS
    'One row per hr.leave_policy_document row, holding its bytes. Split from the metadata table so '
    'a metadata-only read (list/resolve-current) never loads a PDF into memory -- only '
    'LeavePolicyDocumentRepository#findContent, called on an actual download, does.';
