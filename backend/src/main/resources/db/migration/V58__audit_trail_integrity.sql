-- Audit-trail integrity: two places where history was unrecoverable.
--
-- 1. Reopening a lost deal ran `lost_reason = NULL, lost_at = NULL`, so the row
--    became indistinguishable from one that was never lost. The reason survived
--    only as Thai free text inside an event message ("เสียงาน (PRICE)"), which
--    makes "why was this lost before we reopened it" a parsing exercise rather
--    than a query. Reason-code analytics on reopened deals was impractical.
--
--    Fix: keep lost_reason readable after reopen and stamp reopened_at /
--    reopen_count. There is deliberately NO 'REOPENED' lifecycle value — a
--    reopened deal genuinely IS active, and adding a parallel state would fork
--    every lifecycle check. The marker columns answer the question instead.
--
-- 2. ticket_event recorded WHAT changed but never WHICH DOCUMENT it produced.
--    Correlating a status change to its quotation / receipt / delivery meant
--    matching timestamps or parsing free text.

ALTER TABLE sales.ticket
    ADD COLUMN IF NOT EXISTS reopened_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reopen_count  INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN sales.ticket.reopen_count IS
    'Times this deal was reopened after being marked lost. lost_reason survives a reopen, so '
    'a live deal with reopen_count > 0 still shows why it was previously lost.';

-- Polymorphic on purpose: the target lives in one of several tables
-- (sales.quotation, sales.payment_receipt, sales.delivery_record,
-- sales.deposit_notice), so a real FK would need one nullable column per table.
-- The type column is CHECK-constrained instead; there is no referential
-- integrity here and callers must not assume the row still exists.
ALTER TABLE sales.ticket_event
    ADD COLUMN IF NOT EXISTS related_document_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS related_document_id   BIGINT;

ALTER TABLE sales.ticket_event DROP CONSTRAINT IF EXISTS chk_event_related_document;
ALTER TABLE sales.ticket_event ADD CONSTRAINT chk_event_related_document CHECK (
    (related_document_type IS NULL AND related_document_id IS NULL)
    OR (related_document_type IS NOT NULL AND related_document_id IS NOT NULL
        AND related_document_type IN (
            'QUOTATION','DEPOSIT_NOTICE','PAYMENT_RECEIPT','DELIVERY_RECORD'
        ))
);

CREATE INDEX IF NOT EXISTS ix_ticket_event_related_document
    ON sales.ticket_event(related_document_type, related_document_id)
    WHERE related_document_type IS NOT NULL;

-- No backfill on either change. Historical events keep NULL linkage rather than
-- a guess reconstructed from timestamps, and deals reopened before this
-- migration have already lost their reason — it is gone, and writing a
-- plausible-looking value would be inventing history.
