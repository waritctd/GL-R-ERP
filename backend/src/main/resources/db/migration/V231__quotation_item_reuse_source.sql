-- V231: add-on orders reuse items already approved elsewhere in the project. A reused line records which approved
-- quotation item it copies (source_quotation_item_id) so the server can check, at submit, that its price still equals
-- the approved one and approve the add-on quotation without a second approval. SET NULL: a deleted source (a draft
-- row replaced by a save) just turns the line back into an ordinary one that needs approval.
--
-- Rollback: ALTER TABLE sales.quotation_item DROP COLUMN source_quotation_item_id;

ALTER TABLE sales.quotation_item
    ADD COLUMN IF NOT EXISTS source_quotation_item_id BIGINT NULL
        REFERENCES sales.quotation_item (quotation_item_id) ON DELETE SET NULL;
