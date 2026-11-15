-- V209: the rep's expected close quarter for a deal (spec 2026-11-15 §7.3 board card, §7.9 pipeline import).
-- Additive, nullable, no backfill: the reps' workbooks carry "Q1/70"-style values that the import (slice 4) maps.
-- Format is fixed to 'YYYY-Qn' (Gregorian year) so it sorts and compares as text. Written by
-- PUT /api/tickets/{id}/tracking (expectedQuarter), read by GET /api/sales/my-work.
ALTER TABLE sales.ticket ADD COLUMN IF NOT EXISTS expected_quarter TEXT;

ALTER TABLE sales.ticket DROP CONSTRAINT IF EXISTS chk_ticket_expected_quarter;
ALTER TABLE sales.ticket ADD CONSTRAINT chk_ticket_expected_quarter
    CHECK (expected_quarter IS NULL OR expected_quarter ~ '^\d{4}-Q[1-4]$');

COMMENT ON COLUMN sales.ticket.expected_quarter IS
    'Rep-set expected close quarter, YYYY-Qn (e.g. 2026-Q4). NULL = not set.';
