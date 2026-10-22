-- Idempotent twin of V192.1, V192.2, V192.3 and V192.5 (quotation sales feedback, shipped to
-- production from `main` on 2026-10-12 while `develop` was already at V199).
--
-- Why a twin: those four files reached `develop` only on 2026-10-14, numbered BELOW migrations
-- that develop-based databases had already applied. A fresh database runs them in order and this
-- file then changes nothing. A database that was already past V192 when they arrived, and that
-- does not validate on migrate (the prod profile), SKIPS them silently and would be left without
-- the columns the code now reads. This file gives such a database the same end state. Production
-- applied the originals, so there it is a no-op as well.
--
-- Every statement is safe to run on a database in either state. The originals are not edited.

-- V192.1
ALTER TABLE sales.quotation
    ADD COLUMN IF NOT EXISTS hide_project_name BOOLEAN NOT NULL DEFAULT FALSE;

-- V192.2
ALTER TABLE sales.quotation_item
    ADD COLUMN IF NOT EXISTS list_price_sqm NUMERIC(12,2),
    ADD COLUMN IF NOT EXISTS direct_net_price_sqm NUMERIC(12,2);

ALTER TABLE sales.quotation_item
    DROP CONSTRAINT IF EXISTS chk_quotation_item_list_price_sqm_positive,
    DROP CONSTRAINT IF EXISTS chk_quotation_item_direct_net_price_sqm_positive;
ALTER TABLE sales.quotation_item
    ADD CONSTRAINT chk_quotation_item_list_price_sqm_positive
        CHECK (list_price_sqm IS NULL OR list_price_sqm > 0),
    ADD CONSTRAINT chk_quotation_item_direct_net_price_sqm_positive
        CHECK (direct_net_price_sqm IS NULL OR direct_net_price_sqm > 0);

-- V192.3
ALTER TABLE sales.quotation
    ADD COLUMN IF NOT EXISTS ordered_by_phone VARCHAR(50),
    ADD COLUMN IF NOT EXISTS ordered_by_email VARCHAR(254);

-- V192.5
ALTER TABLE sales.quotation
    DROP CONSTRAINT IF EXISTS chk_quotation_price_mode;
ALTER TABLE sales.quotation
    ADD CONSTRAINT chk_quotation_price_mode
        CHECK (price_mode IN ('NET', 'SPECIAL_SQM', 'DIRECT_NET', 'MIXED'));

ALTER TABLE sales.quotation_item
    ADD COLUMN IF NOT EXISTS price_mode VARCHAR(16);
ALTER TABLE sales.quotation_item
    DROP CONSTRAINT IF EXISTS chk_quotation_item_price_mode;
ALTER TABLE sales.quotation_item
    ADD CONSTRAINT chk_quotation_item_price_mode
        CHECK (price_mode IN ('NET', 'SPECIAL_SQM', 'DIRECT_NET'));

-- Column comments. V192.3 said the two ผู้ติดต่อ columns are "NEVER printed"; the owner reversed
-- that on 2026-10-13, and an applied migration is never edited, so the correction lives here.
COMMENT ON COLUMN sales.quotation.hide_project_name IS
    'Deal quotation: when TRUE the printed document carries no "Project : ..." line. project_name itself is kept and still shown in the editor, the register and on the deal. Defaults FALSE (print it).';
COMMENT ON COLUMN sales.quotation_item.list_price_sqm IS
    'Deal quotation TILE row, Thai document: the list price (ราคาตั้ง) as the rep typed it, baht per sqm INCLUDING VAT. unit_price is the per-piece, ex-VAT figure derived from it and is what prints. NULL = the list price was stated per piece. Never printed, never summed.';
COMMENT ON COLUMN sales.quotation_item.direct_net_price_sqm IS
    'Deal quotation TILE row in DIRECT_NET price mode, Thai document: the net price (ราคาสุทธิ) as the rep typed it, baht per sqm INCLUDING VAT. final_unit_price is the per-piece, ex-VAT figure derived from it. NULL in every other mode and whenever the net was stated per piece. Never printed, never summed.';
COMMENT ON COLUMN sales.quotation.ordered_by_phone IS
    'Phone of the person named in ordered_by_name (the editor''s ผู้ติดต่อ field), typed by the sales rep. Free text up to 50 characters. PRINTED on the customer address line of the quotation when not blank (owner ruling 2026-10-13); NULL prints nothing, with no fallback to the customer master or to contact_phone.';
COMMENT ON COLUMN sales.quotation.ordered_by_email IS
    'Email of the person named in ordered_by_name (the editor''s ผู้ติดต่อ field), typed by the sales rep. PRINTED on the customer address line of the quotation when not blank (owner ruling 2026-10-13); NULL prints nothing, with no fallback to contact_email.';
COMMENT ON COLUMN sales.quotation.price_mode IS
    'How the document''s TILE rows are priced: NET (list price - discount %), SPECIAL_SQM (special price per sqm), DIRECT_NET (net price typed directly), or MIXED (each tile row states its own in quotation_item.price_mode). NULL reads as NET.';
COMMENT ON COLUMN sales.quotation_item.price_mode IS
    'This TILE row''s own price mode - NET / SPECIAL_SQM / DIRECT_NET - on a document whose quotation.price_mode is MIXED. NULL on every row of a single-mode document and on every PLAIN / ADJUSTMENT row.';
