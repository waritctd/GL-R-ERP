-- D7: Add source tracking to fx_rates (BOT auto-fetch vs MANUAL CEO entry)
ALTER TABLE sales.fx_rates
    ADD COLUMN IF NOT EXISTS source     VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS fetched_at TIMESTAMPTZ;

-- D10: CEO manual price override per ticket item
ALTER TABLE sales.ticket_item
    ADD COLUMN IF NOT EXISTS manual_price           NUMERIC(14,4),
    ADD COLUMN IF NOT EXISTS manual_override_reason TEXT;
