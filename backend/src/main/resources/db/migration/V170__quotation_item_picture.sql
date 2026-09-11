-- Quotation item pictures (owner request GLA-75, 2026-09-10 -> built 2026-09-11).
--
-- Owner, 2026-09-10: "can you let it be able to attach image like the reference photo make sure
-- the sizing appropriate like the reference picture". Her three reference documents place a
-- picture in TWO different ways, so an item carries a placement as well as the bytes:
--   BELOW  -- a large picture under the item's description lines, bounded by the description
--             column's width (QN6900902-6's mosaic pattern, QN6900782-2's cut drawings);
--   BESIDE -- a small thumbnail at the right of the description cell, about two text rows tall
--             (QN6900971-4's tap / towel ring / shower set).
-- QuotationRenderer places both into the XLS, which is the one row plan the LibreOffice PDF and
-- the Chromium/HTML PDF both print.
--
-- MIGRATION NUMBERING: origin/develop tops out at V169 (quotation document language), so V170 is
-- the next free number ABOVE develop's max -- a lower number merged later would be silently
-- skipped on the prod deploy (validate-on-migrate is false there; see CLAUDE.md). No open branch
-- carries a V170 at the time of writing.
--
-- WHY A SEPARATE TABLE, not three columns on sales.quotation_item:
-- DealQuotationService#update is a FULL REPLACE of a draft's items (DELETE + INSERT, new
-- quotation_item_id every save). Bytes living on the item row would have to be read into memory
-- and re-written on every draft save. Instead the item holds a reference (picture_id) and the
-- bytes live once in sales.quotation_item_picture, IMMUTABLE after insert:
--   * a draft save re-points the new item rows at the same picture_id (the client round-trips each
--     item's `id`; see DealQuotationRequests.ItemInput#id) -- no bytes move;
--   * a revision copies its parent's items verbatim, picture_id included, so parent and child
--     SHARE the picture row. Replacing or removing the child's picture re-points or clears only the
--     child's item; the parent's approved document keeps printing what it was approved with;
--   * a picture row no item references any more is deleted by the service at the moment it is
--     orphaned (DealQuotationRepository#deletePictureIfUnreferenced). The FK below is RESTRICT
--     (the default), so that delete can never remove a picture something still prints.
--
-- Same storage shape as V165's hr.employee_signature (BYTEA + mime_type), which is the house
-- style for small images in this schema. The mime type is SNIFFED from the magic bytes on upload
-- (QuotationItemPictures#sniffMimeType), never the client's Content-Type -- the CHECK here is a
-- backstop, not the validation.

CREATE TABLE sales.quotation_item_picture (
    picture_id   BIGSERIAL PRIMARY KEY,
    mime_type    VARCHAR(40) NOT NULL,
    image        BYTEA NOT NULL,
    uploaded_by  BIGINT REFERENCES hr.employee(employee_id),
    uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_quotation_item_picture_mime CHECK (mime_type IN ('image/png', 'image/jpeg'))
);

ALTER TABLE sales.quotation_item
    ADD COLUMN picture_id        BIGINT REFERENCES sales.quotation_item_picture(picture_id),
    ADD COLUMN picture_placement VARCHAR(8);

-- A placement without a picture (or a picture without a placement) is meaningless and would make
-- the renderer guess; the two are set and cleared together by every write path.
ALTER TABLE sales.quotation_item
    ADD CONSTRAINT chk_quotation_item_picture_placement
        CHECK (picture_placement IS NULL OR picture_placement IN ('BELOW', 'BESIDE')),
    ADD CONSTRAINT chk_quotation_item_picture_pair
        CHECK ((picture_id IS NULL) = (picture_placement IS NULL));

-- The orphan check (is this picture still referenced by ANY item?) runs on every replace/remove
-- and every draft save that dropped a picture.
CREATE INDEX idx_quotation_item_picture_id ON sales.quotation_item(picture_id)
    WHERE picture_id IS NOT NULL;
