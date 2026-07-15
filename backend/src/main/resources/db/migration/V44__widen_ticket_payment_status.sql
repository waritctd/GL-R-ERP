-- Bug fix: sales.ticket.payment_status was created VARCHAR(20) back in V6
-- (sales/CRM initial schema, alongside the never-used delivery_status).
-- V39 ("dual-track post-quotation status") intended to widen payment_status
-- to VARCHAR(40) so it could hold the new state-machine values, but used
-- `ADD COLUMN IF NOT EXISTS payment_status VARCHAR(40)` -- which is a no-op
-- when the column already exists, silently leaving it at VARCHAR(20).
--
-- TicketService's dual-track payment states include values longer than 20
-- chars (DEPOSIT_NOTICE_ISSUED = 21, AWAITING_FINAL_PAYMENT = 22), so any
-- ticket reaching those states via issueDepositNotice()/markGoodsReceived()
-- hits "value too long for type character varying(20)" today. Discovered
-- while seeding V905 UAT fixtures for the dual-track flow; this is a real
-- backend bug, not a seed-data issue, so it is fixed here as a proper
-- shared migration (not folded into the UAT-only seed).
--
-- fulfillment_status is a brand-new column (no pre-existing narrower
-- definition), so it already came in at VARCHAR(40) correctly from V39 --
-- included here only for a matching explicit-width statement, not because
-- it was broken.
ALTER TABLE sales.ticket
    ALTER COLUMN payment_status TYPE VARCHAR(40);

-- delivery_status (V6) is unused by any dual-track code path (fulfillment_status
-- is the real column now) but is widened too for consistency/headroom, since it
-- shares the same narrow-VARCHAR(20) origin and no code path should ever hit a
-- silent truncation error on it either.
ALTER TABLE sales.ticket
    ALTER COLUMN delivery_status TYPE VARCHAR(40);
