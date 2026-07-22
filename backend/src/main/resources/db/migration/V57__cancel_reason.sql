-- Structured cancel reason.
--
-- Marking a deal LOST has required a structured reason since V50 (lost_reason /
-- lost_at, the business's F1–F8 sheet). Cancelling recorded nothing at all — no
-- reason column, and the event message was hardcoded NULL — so a cancelled deal
-- carried zero explanation.
--
-- Cancelled is deliberately not folded into lost: lost is a competitive outcome
-- (we were beaten on price, spec, lead time), cancelled means the opportunity
-- itself disappeared. Merging them would poison win/loss reporting with deals
-- that were never winnable.

ALTER TABLE sales.ticket
    ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(40),
    ADD COLUMN IF NOT EXISTS cancelled_at  TIMESTAMPTZ;

ALTER TABLE sales.ticket DROP CONSTRAINT IF EXISTS chk_ticket_cancel_reason;
ALTER TABLE sales.ticket ADD CONSTRAINT chk_ticket_cancel_reason CHECK (
    cancel_reason IS NULL OR cancel_reason IN (
        'OWNER_CANCELLED','PROJECT_SUSPENDED','BUDGET_CANCELLED','OTHER'
    )
);

COMMENT ON COLUMN sales.ticket.cancel_reason IS
    'Why the opportunity went away. Distinct from lost_reason, which is why we lost it.';

-- Nullable with no backfill: deals cancelled before this migration have no
-- recorded reason and inventing one would be a fabrication. They read as NULL,
-- which is honest — "we do not know" — and is exactly what the UI shows.
