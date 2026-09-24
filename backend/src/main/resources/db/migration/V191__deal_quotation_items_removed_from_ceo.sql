-- GLA-123 slice S1, Opus review M4(c) (2026-09-20): tracks how many CEO-linked lines have been
-- dropped from a PRICING_REQUEST-origin quotation, so the editor can show a header-level marker
-- ("รายการที่ CEO อนุมัติถูกลบออก N รายการ") the same way per-line priceChangedFromCeo already
-- flags a PRICE change.
--
-- MIGRATION NUMBERING: renumbered V190 -> V191 on rebase (2026-09-23). V188
-- (remaining-invoice document) and V189 (billing-note document, re-declares chk_event_kind) both
-- merged to develop while this branch was open; an UNRELATED self-service forgot-password PR
-- (#1024) then also merged and independently claimed V190 (password_reset_token) before this
-- branch's own V190 could land, producing a genuine filename/version collision. V191 was verified
-- free across every remote branch by two independent sessions before this rename.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- What this adds
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- sales.quotation.items_removed_from_ceo_count: a plain counter, NOT a boolean — "N รายการ" in
-- the marker text needs the count, not just a yes/no. Defaults 0 (every DEAL_DIRECT row and every
-- pre-existing PRICING_REQUEST row alike; the concept is meaningless for DEAL_DIRECT, which has no
-- CEO-linked lines to drop, so it simply never moves off 0 there).
--
-- Written by DealQuotationRepository#incrementItemsRemovedFromCeo, called from
-- DealQuotationService#update (a linked-line drop: +1 per line) and #restoreRemovedItem (a
-- "คืนรายการ" restore: -1 per line, floored at 0 so a value can never go negative even if a race
-- or a bug over-decrements).

ALTER TABLE sales.quotation
    ADD COLUMN items_removed_from_ceo_count INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_quotation_items_removed_from_ceo_count_non_negative
        CHECK (items_removed_from_ceo_count >= 0);

COMMENT ON COLUMN sales.quotation.items_removed_from_ceo_count IS
    'GLA-123 slice S1 M4(c): count of CEO-linked (pricing_decision_item_id-carrying) lines dropped from a PRICING_REQUEST-origin quotation since creation. Always 0 for DEAL_DIRECT/legacy rows.';
