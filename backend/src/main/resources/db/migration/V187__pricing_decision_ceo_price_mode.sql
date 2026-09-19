-- Phase 2 of the sales pricing-flow redesign: CEO pricing method (owner rulings, 2026-09-18/19).
--
-- MIGRATION NUMBERING: this branch (feat/ceo-pricing-discount-mode) is stacked on
-- feat/pcr-item-form-direct-deal (Phase 1, V185, merged as PR #1005 @ 9c4917d4). V186 is reserved
-- by an unmerged sibling (PR #1004); V184 is reserved by another unmerged branch. V187 is this
-- worktree's own next free number, verified by `ls backend/src/main/resources/db/migration`
-- immediately before writing this file (tops out at V185). Re-check before merge if time has
-- passed or other worktrees have advanced.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- What this adds
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Phase 1 (V185) gave a new-form PricingRequest item the SAME shape as a direct-deal quotation
-- item, minus price/discount. Phase 2 gives the CEO's own pricing decision the SAME price-entry
-- shape the direct-deal quotation editor already uses (WastageCalculator.PRICE_MODE_NET /
-- SPECIAL_SQM / DIRECT_NET, th.co.glr.hr.dealquotation package) instead of the margin-only formula
-- path pricing_decision has used since V72/V109 -- chosen ONCE per decision (price_mode, header
-- level, mirrors sales.quotation.price_mode, V168), with per-item inputs mirroring
-- sales.quotation_item's own DEAL_DIRECT columns (V49/V165/V168) exactly.
--
-- This is additive and backward compatible: price_mode stays NULL for every decision created
-- before this migration (a "legacy" decision) and for one started against a pricing-request item
-- that is not the new (Phase 1) PER_PIECE/sqm_per_piece shape -- PricingDecisionService keeps
-- driving those through the untouched margin/"ปรับราคาเอง" formula path (V109/V150). Nothing here
-- changes column meaning or removes a column; the margin/override columns on pricing_decision(_item)
-- are read exactly as before for a legacy decision.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Header: which pricing method this decision uses (once per decision, like sales.quotation)
-- ─────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE sales.pricing_decision
    ADD COLUMN price_mode VARCHAR(16);

-- Same three codes, same CHECK shape as chk_quotation_price_mode (V168) -- a CHECK, not an enum
-- type, for the same reason: NULL passes (every legacy decision, and every new decision before
-- the CEO has picked a mode), and the constraint stays widenable by a later DROP + re-ADD.
ALTER TABLE sales.pricing_decision
    ADD CONSTRAINT chk_pricing_decision_price_mode
    CHECK (price_mode IN ('NET', 'SPECIAL_SQM', 'DIRECT_NET'));

COMMENT ON COLUMN sales.pricing_decision.price_mode IS
    'CEO pricing method for this decision, chosen ONCE (owner ruling 2026-09-18/19) -- NET = ''ราคาตั้ง - ส่วนลด %'', SPECIAL_SQM = ''ราคาพิเศษ บาท/ตร.ม.'' (VAT-inclusive), DIRECT_NET = ''ราคาสุทธิต่อแผ่น''. NULL means either a legacy decision (predates this migration) or a new decision the CEO has not yet picked a mode for -- PricingDecisionService drives the margin/''ปรับราคาเอง'' formula path for either case, unchanged. Never set for a decision whose pricing-request items are not the Phase-1 (V185) PER_PIECE + sqm_per_piece shape -- see PricingDecisionService''s own new-form eligibility check.';

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Items: CEO price-mode inputs, PER REQUESTED UNIT (== per piece/แผ่น for a Phase-1 item) --
-- column types mirror sales.quotation_item's own equivalents exactly (V49 unit_price, V168
-- discount_pct/special_price_sqm; direct_net_price and net_unit_price are new here because
-- quotation_item stores its own DIRECT_NET value and its net straight into unit_price/
-- final_unit_price -- pricing_decision_item instead keeps the CEO's raw typed inputs and the
-- derived net as separate columns, alongside (never replacing) proposed/approved/manual
-- selling-price columns V72/V150 already defined, so this phase adds nothing that could make an
-- existing column's meaning ambiguous).
-- ─────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE sales.pricing_decision_item
    ADD COLUMN list_unit_price    NUMERIC(14,2),
    ADD COLUMN discount_pct       NUMERIC(5,2),
    ADD COLUMN special_price_sqm  NUMERIC(12,2),
    ADD COLUMN direct_net_price   NUMERIC(14,2),
    ADD COLUMN net_unit_price     NUMERIC(14,2);

-- Range CHECKs only for what a single-table CHECK can express without knowing the parent
-- decision's price_mode -- "the right field(s) are set for the CHOSEN mode" is cross-table
-- (pricing_decision.price_mode) and is validated in PricingDecisionService instead, the same
-- division of labour V185's own header already draws for quantity_mode/wastage_mode.
ALTER TABLE sales.pricing_decision_item
    ADD CONSTRAINT chk_pricing_decision_item_discount_pct
        CHECK (discount_pct IS NULL OR (discount_pct >= 0 AND discount_pct <= 100)),
    ADD CONSTRAINT chk_pricing_decision_item_list_unit_price_nonneg
        CHECK (list_unit_price IS NULL OR list_unit_price >= 0),
    ADD CONSTRAINT chk_pricing_decision_item_special_price_sqm_nonneg
        CHECK (special_price_sqm IS NULL OR special_price_sqm >= 0),
    ADD CONSTRAINT chk_pricing_decision_item_direct_net_price_nonneg
        CHECK (direct_net_price IS NULL OR direct_net_price >= 0),
    ADD CONSTRAINT chk_pricing_decision_item_net_unit_price_nonneg
        CHECK (net_unit_price IS NULL OR net_unit_price >= 0);

COMMENT ON COLUMN sales.pricing_decision_item.list_unit_price IS
    'ราคา/หน่วย (ราคาตั้งต่อแผ่น), used as price_mode = NET''s starting price. Owner correction 2026-09-19, superseding this column''s original description here: this is NEVER a CEO-typed value -- it is ALWAYS auto-calculated and server-maintained, money2(proposed_selling_price_per_requested_unit), refreshed by PricingDecisionService#deriveListAndNet every time that formula reference is recomputed (startReview, overrideItemCost, recalculateCost, overrideItemProductType, and a legacy marginPct edit) -- never frozen once and left stale. Under price_mode = NET, an active "ปรับราคาเอง" override (manual_selling_price_per_requested_unit) REPLACES this as the effective list price the discount applies to (ruling B); this column itself is untouched by that override. Populated for a LEGACY decision too (mirrors proposed_selling_price_per_requested_unit unconditionally, regardless of price_mode), even though the legacy margin/"ปรับราคาเอง" path never reads it -- NOT NULL-for-legacy as this comment previously (and wrongly) said. CEO-only -- stripped to NULL for every other role, see PricingDecisionService''s stripping.';
COMMENT ON COLUMN sales.pricing_decision_item.discount_pct IS
    'ส่วนลด % -- CEO-typed under price_mode = NET, paired with list_unit_price. Mirrors sales.quotation_item.discount_pct (V165) including its 0-100 range. CEO-only.';
COMMENT ON COLUMN sales.pricing_decision_item.special_price_sqm IS
    'ราคาพิเศษ บาท/ตร.ม. รวม VAT -- CEO-typed under price_mode = SPECIAL_SQM. Requires the item''s pricing_request_item.sqm_per_piece (V185) to derive a net -- WastageCalculator#netPerPieceFromSpecialSqm, exactly as the direct-deal quotation editor uses it. Mirrors sales.quotation_item.special_price_sqm (V168). CEO-only.';
COMMENT ON COLUMN sales.pricing_decision_item.direct_net_price IS
    'ราคาสุทธิต่อแผ่น -- CEO-typed under price_mode = DIRECT_NET, the per-piece net price stated directly with no derivation. CEO-only.';
COMMENT ON COLUMN sales.pricing_decision_item.net_unit_price IS
    'Server-derived net price per requested unit (per แผ่น) for whichever price_mode is active -- NEVER client-supplied, always recomputed from list_unit_price+discount_pct / special_price_sqm / direct_net_price via WastageCalculator, the same arithmetic core the direct-deal quotation editor uses (PricingDecisionService never reimplements it). On approve() of a decision with a non-NULL price_mode, this value freezes into BOTH approved_selling_price_per_requested_unit and minimum_selling_price_per_requested_unit (owner ruling: the CEO''s own discount is pre-approved and never trips the V155 per-line discount-approval gate downstream). CEO-only. NULL for a legacy decision or before the CEO has entered a price for this item.';
