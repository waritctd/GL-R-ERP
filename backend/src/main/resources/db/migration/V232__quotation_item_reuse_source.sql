-- V232: add-on orders reuse items already approved elsewhere in the project. A reused line records which approved
-- quotation item it copies (source_quotation_item_id) so the server can check, at submit, that its price still equals
-- the approved one and approve the add-on quotation without a second approval. SET NULL: a deleted source (a draft
-- row replaced by a save) just turns the line back into an ordinary one that needs approval.
--
-- Also how a quotation was approved: approval_basis NULL = by a person; 'REUSED_APPROVED_PRICE' = by the add-on reuse rule,
-- with the source quotation number(s) in approval_source_numbers. Such a document prints no approver name or signature.
--
-- Rollback: ALTER TABLE sales.quotation DROP COLUMN approval_basis, DROP COLUMN approval_source_numbers;
--           ALTER TABLE sales.quotation_item DROP COLUMN source_quotation_item_id;

ALTER TABLE sales.quotation_item
    ADD COLUMN IF NOT EXISTS source_quotation_item_id BIGINT NULL
        REFERENCES sales.quotation_item (quotation_item_id) ON DELETE SET NULL;

ALTER TABLE sales.quotation
    ADD COLUMN IF NOT EXISTS approval_basis VARCHAR(32) NULL,
    ADD COLUMN IF NOT EXISTS approval_source_numbers TEXT NULL,
    ADD CONSTRAINT chk_quotation_approval_basis CHECK (approval_basis IS NULL OR approval_basis = 'REUSED_APPROVED_PRICE');
