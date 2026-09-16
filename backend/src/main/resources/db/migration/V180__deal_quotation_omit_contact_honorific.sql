-- Item 2 (direct-deal quotation, owner ruling 2026-09-16) — "ไม่เติม “คุณ” หน้าชื่อผู้สั่งซื้อ".
--
-- Owner feedback: some deals record their ผู้สั่งซื้อ contact as a department/section name — "ฝ่าย
-- จัดซื้อ" — rather than a person, and DealQuotationRenderAdapter's existing #looksLikeOrganisation
-- heuristic (checked against a fixed list of Thai company markers: บริษัท/จำกัด/ห้างหุ้นส่วน/etc.)
-- does not, and cannot, catch every such case. Rather than growing that marker list indefinitely, a
-- rep gets a direct per-quotation override: tick it, and the attn line never prefixes "คุณ" onto
-- the contact name, whatever the heuristic would otherwise decide.
--
-- Per-QUOTATION, not per-contact or per-customer: the SAME contact snapshot can be a genuine person
-- on one deal's quotation and a department name typed into the same field on another, so the flag
-- lives on sales.quotation itself, exactly where deposit_percent/remainder_mode/etc. already do.
--
-- NOT NULL DEFAULT FALSE: every existing row (every quotation created before this feature) printed
-- with the honorific heuristic fully engaged, which is exactly what FALSE preserves — an additive,
-- backward-compatible column with no row needing a backfill value beyond the default.
--
-- Rollback: revert the application code (DealQuotationDtos/Requests/Service/Repository/
-- RenderAdapter and the frontend terms card), then
--     ALTER TABLE sales.quotation DROP COLUMN omit_contact_honorific;
-- No backfill concern either direction: the column carries no information any other column
-- duplicates, so dropping it loses only the flag itself, exactly what dropping was asked to do.

ALTER TABLE sales.quotation
    ADD COLUMN omit_contact_honorific BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN sales.quotation.omit_contact_honorific IS
    'Direct-deal quotation (origin = DEAL_DIRECT) only: when TRUE, DealQuotationRenderAdapter never prefixes "คุณ" onto the ผู้สั่งซื้อ contact name on the Thai document, for a deal whose contact is a department/section name rather than a person. Defaults FALSE (today''s existing honorific-detection behaviour) for every row, including every pre-V180 one. Not remembered in quotationPrefs -- always defaults unticked on a new quotation.';
