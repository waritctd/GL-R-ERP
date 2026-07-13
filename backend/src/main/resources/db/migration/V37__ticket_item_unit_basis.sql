-- Add unit_basis to ticket_item to track whether qty (PIECE) or qty_sqm (SQM) is the primary quantity
ALTER TABLE sales.ticket_item
    ADD COLUMN IF NOT EXISTS unit_basis VARCHAR(10) NOT NULL DEFAULT 'PIECE';

ALTER TABLE sales.ticket_item
    ADD CONSTRAINT chk_unit_basis CHECK (unit_basis IN ('PIECE', 'SQM'));

-- Add PRICE_REVISED event kind (used when import re-edits prices after price_proposed or approved)
ALTER TABLE sales.ticket_event DROP CONSTRAINT IF EXISTS chk_event_kind;
ALTER TABLE sales.ticket_event ADD CONSTRAINT chk_event_kind CHECK (kind IN (
    'CREATED','SUBMITTED','PICKED_UP','PRICE_PROPOSED',
    'APPROVED','REJECTED','QUOTATION_ISSUED',
    'COMMENTED','CLOSED','CANCELLED','EDITED',
    'DOCUMENT_ISSUED','REVISION_REQUESTED','PRICE_REVISED'
));
