-- GLA-123 slice S1, Opus review M4(c) (2026-09-20): tracks how many CEO-linked lines have been
-- dropped from a PRICING_REQUEST-origin quotation, so the editor can show a header-level marker
-- ("รายการที่ CEO อนุมัติถูกลบออก N รายการ") the same way per-line priceChangedFromCeo already
-- flags a PRICE change.
--
-- MIGRATION NUMBERING: per the lead session (2026-09-20), V188 and V189 are claimed by another
-- unmerged session (that session's V189 re-declares chk_event_kind) — do NOT use either. This
-- worktree's own next free number is V190, verified against
-- `ls backend/src/main/resources/db/migration` immediately before writing this file (tops out at
-- V187 in this worktree). Re-check before merge if other worktrees have advanced further.
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
