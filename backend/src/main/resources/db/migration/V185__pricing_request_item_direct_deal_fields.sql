-- Pricing Request item form -> direct-deal item form parity (Phase 1 of the sales-flow redesign).
--
-- Owner ruling (Ploy): the คำขอราคา (PricingRequest / PCR) item form Sales fills must become the
-- SAME form as the direct-deal quotation item form (DEAL_DIRECT, V165/V176/V182 —
-- th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput), MINUS the price/discount inputs.
-- CEO pricing (unit price, discount, วิธีกรอกราคา) is a LATER phase and is deliberately not added
-- here — see the "excluded" list at the bottom of this header.
--
-- MIGRATION NUMBERING: V184 is reserved by an unmerged sibling branch; V185 is this worktree's own
-- next free number, verified by `ls backend/src/main/resources/db/migration` immediately before
-- writing this file (tops out at V183__ticket_item_stock_sale_price.sql). Re-check before merge if
-- time has passed or other worktrees have advanced (this repo's own standing numbering caveat).
--
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- What is reused vs. added
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- sales.pricing_request_item ALREADY has brand/model/color/texture/size (V59) and a `product_id`
-- that V68 repointed at price_catalog.product_prices.price_id — the EXACT same target as
-- sales.quotation_item.catalog_price_id. So the direct-deal form's catalogPriceId/model/brand/
-- color/texture/sizeText map onto product_id/model/brand/color/texture/size UNCHANGED — no new
-- column for any of those.
--
-- `catalog_product_code` (V61) is a CATALOG-SNAPSHOT column, written by
-- PricingRequestRepository#snapshotCatalogSelections at submit() time, from price_catalog data —
-- it is NOT what Sales types. The direct-deal form's sales-typed productCode is therefore a
-- genuinely different thing and gets its own new `product_code` column below, exactly the same
-- distinction sales.quotation_item already draws between its own sales-typed `product_code`
-- (V165) and its catalog-snapshot `catalog_product_code` (V61, shared ancestor migration).
--
-- โรงงาน (factory): the sales-entered factory field is REMOVED from the sales form ("เปลี่ยน
-- โรงงานเป็นยี่ห้อ" — ยี่ห้อ/brand is what Sales uses now). The existing `factory` column and
-- Import's SetItemFactoryRequest gap-fill (PricingRequestRepository#fillItemFactory) are UNTOUCHED
-- — Sales simply stops writing to it; snapshotCatalogSelections still backfills it from the
-- catalog's own factory name exactly as before, and Import can still fill a blank one.
--
-- รายละเอียดสินค้า (product_description): the direct-deal form has no such field. The existing
-- `product_description` column is KEPT (still read by PricingRequestService#isProductIdentified's
-- identity fallback), but the new sales form maps its own หมายเหตุรายการ-equivalent notes input
-- onto it instead of a dedicated "รายละเอียดสินค้า" box — see PricingRequestCreateModal.jsx.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- New columns — types mirror sales.quotation_item's DEAL_DIRECT columns EXACTLY (V165/V176/V182),
-- including their CHECK posture: like quotation_item, these enum-shaped columns (quantity_mode,
-- wastage_mode) carry NO database CHECK constraint — WastageCalculator and
-- PricingRequestService#requireItemFieldsComplete validate them in Java, the same division of
-- labour DealQuotationService already uses for the identical columns on quotation_item.
-- ─────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE sales.pricing_request_item
    ADD COLUMN product_code          TEXT,
    ADD COLUMN thickness_mm          NUMERIC(6,2),
    ADD COLUMN sqm_per_piece         NUMERIC(10,6),
    ADD COLUMN quantity_mode         VARCHAR(10),
    ADD COLUMN area_sqm              NUMERIC(12,2),
    ADD COLUMN pieces_input          INTEGER,
    ADD COLUMN wastage_mode          VARCHAR(10),
    ADD COLUMN wastage_value         NUMERIC(10,2),
    ADD COLUMN pieces_per_box        SMALLINT,
    ADD COLUMN sqm_per_box           NUMERIC(10,6),
    ADD COLUMN pieces_before_wastage INTEGER,
    ADD COLUMN pieces_after_wastage  INTEGER,
    ADD COLUMN boxes                 INTEGER,
    ADD COLUMN origin_country        VARCHAR(40),
    ADD COLUMN lead_time_min_days    SMALLINT,
    ADD COLUMN lead_time_max_days    SMALLINT;

-- round_to_full_box mirrors quotation_item's own V182 column verbatim, including its NOT NULL
-- DEFAULT TRUE — every existing (legacy, pre-this-migration) row reads TRUE, which is the only
-- behaviour that has ever existed for a pricing-request item (piece counts were never box-rounded
-- at all before this change; TRUE is also WastageCalculator.Input's own "round up to a full box"
-- default), so no existing row's meaning changes.
ALTER TABLE sales.pricing_request_item
    ADD COLUMN round_to_full_box BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN sales.pricing_request_item.product_code IS
    'Sales-typed product code (direct-deal-form parity, mirrors sales.quotation_item.product_code, V165). Distinct from catalog_product_code (V61), which is the CATALOG SNAPSHOT written at submit() time, not what Sales types.';
COMMENT ON COLUMN sales.pricing_request_item.thickness_mm IS
    'ความหนา (มม.) as typed by Sales -- required by PricingRequestService#requireItemFieldsComplete for every item created/updated from V185 onward. NULL on every pre-V185 row (legacy items keep displaying with --).';
COMMENT ON COLUMN sales.pricing_request_item.sqm_per_piece IS
    'ตร.ม./แผ่น -- mirrors sales.quotation_item.sqm_per_piece (V165) exactly, including precision. Required going forward; feeds requested_qty_sqm derivation (WastageCalculator#sqmQuantityFromPieces).';
COMMENT ON COLUMN sales.pricing_request_item.quantity_mode IS
    'AREA | PIECES -- which of area_sqm/pieces_input Sales entered. Mirrors sales.quotation_item.quantity_mode (V165); validated in Java (WastageCalculator), not by a DB CHECK, matching that column''s own posture.';
COMMENT ON COLUMN sales.pricing_request_item.wastage_mode IS
    'PERCENT | PIECES | NONE -- mirrors sales.quotation_item.wastage_mode (V165). Feeds the requested_qty derivation the same way it feeds a direct-deal line''s printed quantity.';
COMMENT ON COLUMN sales.pricing_request_item.pieces_per_box IS
    'แผ่น/กล่อง -- mirrors sales.quotation_item.pieces_per_box (V165). Required going forward (owner ruling: every direct-deal-required field is required here too).';
COMMENT ON COLUMN sales.pricing_request_item.sqm_per_box IS
    'ตร.ม./กล่อง (supplier-stated box area) -- mirrors sales.quotation_item.sqm_per_box (V176). Optional, same as on the direct-deal form; not used by this phase''s requested_qty_sqm derivation (that always derives from sqm_per_piece x pieces, never from box area).';
COMMENT ON COLUMN sales.pricing_request_item.pieces_before_wastage IS
    'Server-computed (WastageCalculator#calculate), audit/display only -- the piece count before wastage/box rounding. Never client-supplied; overwritten on every create/update of the owning item.';
COMMENT ON COLUMN sales.pricing_request_item.pieces_after_wastage IS
    'Server-computed (WastageCalculator#calculate) -- the piece count after wastage, before box rounding. Never client-supplied.';
COMMENT ON COLUMN sales.pricing_request_item.boxes IS
    'Server-computed (WastageCalculator#calculate) -- full box count, or NULL when pieces_per_box is not set. Never client-supplied.';
COMMENT ON COLUMN sales.pricing_request_item.origin_country IS
    'ประเทศต้นทาง -- mirrors sales.quotation_item.origin_country (V165). Optional, exactly as on the direct-deal form.';
COMMENT ON COLUMN sales.pricing_request_item.lead_time_min_days IS
    'ระยะเวลานำเข้า (วัน), minimum -- mirrors sales.quotation_item.lead_time_min_days (V165). Optional here (unlike the direct-deal quotation, a PricingRequest has no submit-time "every tile needs a lead time" gate in this phase).';
COMMENT ON COLUMN sales.pricing_request_item.lead_time_max_days IS
    'ระยะเวลานำเข้า (วัน), maximum -- mirrors sales.quotation_item.lead_time_max_days (V165). Optional, see lead_time_min_days.';
COMMENT ON COLUMN sales.pricing_request_item.round_to_full_box IS
    'ขายแผ่นไม่เต็มกล่อง (inverted) -- mirrors sales.quotation_item.round_to_full_box (V182) exactly, including the NOT NULL DEFAULT TRUE (every existing row keeps rounding up, today''s only behaviour). false sells the wastage-adjusted piece count unrounded.';

-- requested_qty / requested_qty_sqm / requested_unit / requested_unit_basis are UNCHANGED columns
-- (V59/V68) -- for an item created/updated from this migration onward, PricingRequestService now
-- DERIVES them server-side from the columns above via WastageCalculator, the same math the
-- direct-deal quotation uses (requested_unit_basis is always PER_PIECE, requested_unit is always
-- the "แผ่น" piece label, requested_qty is the post-wastage/post-box-rounding piece count). Import
-- (SetItemFactoryRequest, FactoryQuoteRepository seeding) and PricingCostingService/
-- LandedCostCalculator keep reading exactly these four columns and need no code change -- see the
-- PR body for the read-path verification. Legacy (pre-V185) rows keep whatever value they already
-- had in these columns; this migration does not touch existing data.

-- =============================================================================================
-- GLA-125 (owner ruling 2026-09-18, "Yes, all of it") — added to this SAME migration because V185
-- is still unmerged and unapplied (no separate Vnnn needed; this is exactly the case the
-- forward-only-migration rule's own escape hatch is for: editing an migration nobody has applied
-- yet is not "editing an already-applied migration").
-- =============================================================================================

-- Item 2: อื่นๆ + typed country. ORIGIN_COUNTRY_OPTIONS (quotationMeta.js) already uses the
-- literal string "อื่นๆ" as its own sentinel CODE (not a 'ZZ' ISO-style code — that convention
-- belongs to a DIFFERENT list, price_catalog.factories.country, which this item-level field has
-- never used). This column is the typed name Sales enters when origin_country = 'อื่นๆ'; required
-- in Java exactly then, ignored/cleared otherwise (PricingRequestService#resolveItem).
ALTER TABLE sales.pricing_request_item
    ADD COLUMN origin_country_other TEXT;

ALTER TABLE sales.pricing_request_item
    ADD CONSTRAINT chk_pricing_request_item_origin_country_other_not_blank CHECK (
        origin_country_other IS NULL OR btrim(origin_country_other) <> ''
    );

COMMENT ON COLUMN sales.pricing_request_item.origin_country_other IS
    'Typed ประเทศต้นทาง name, only meaningful when origin_country = ''อื่นๆ'' (GLA-125). Required by PricingRequestService#requireItemFieldsComplete exactly then; NULL/ignored for every other origin_country value and for every legacy (pre-GLA-125) row.';

-- Item 4: header terms on the PCR itself, mirroring sales.quotation's own DEAL_DIRECT columns
-- (V165 dept_code/unit_code/credit_days/validity_days, V179 printed_by/sales_rep_display_id, V180
-- omit_contact_honorific) so Sales can fill the same header Sales would eventually fill on the
-- quotation. NOT wired onto customerquotation/ yet -- carrying these onto the actual quotation is
-- Phase 3, per the owner ruling. Reused, NOT duplicated here: recipient_contact_id (V59, already
-- "ผู้สั่งซื้อ"), note (V59, already "หมายเหตุเพิ่มเติม"/customer_notes) and unit_code doubles as
-- the ผู้ออกแบบ picker's target exactly as it already does on sales.quotation (V173's own
-- Javadoc: "a designer pick... writes the same free-text D.Co. field", never a dedicated
-- designer_id column) -- so no separate designer column is added either. ช่องทางรับงาน is NOT
-- duplicated onto pricing_request at all: it is sales.ticket.entry_channel (V51/V144), already set
-- at deal-creation time and immutable from a PCR — the create form only ever DISPLAYS it read-only.
--
-- payment_term_mode is a SIMPLER two-way choice than quotation's own deposit_percent/
-- remainder_mode/full_payment_term trio (V165/V181): a PricingRequest has no price yet, so
-- "deposit percentage" cannot exist here at all. CREDIT pairs with credit_days (required exactly
-- then, enforced in Java, matching the enum-shaped-column-no-DB-CHECK posture every other
-- Java-validated column on this table already uses); ON_DELIVERY carries no day count.
ALTER TABLE sales.pricing_request
    ADD COLUMN payment_term_mode        VARCHAR(20),
    ADD COLUMN credit_days              SMALLINT,
    ADD COLUMN validity_days            SMALLINT,
    ADD COLUMN printed_by_display_id    BIGINT NULL REFERENCES hr.employee(employee_id),
    ADD COLUMN sales_rep_display_id     BIGINT NULL REFERENCES hr.employee(employee_id),
    ADD COLUMN dept_code                VARCHAR(20),
    ADD COLUMN unit_code                VARCHAR(20),
    ADD COLUMN omit_contact_honorific   BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN sales.pricing_request.payment_term_mode IS
    'CREDIT | ON_DELIVERY (GLA-125) -- validated in Java (PricingRequestService), not a DB CHECK, matching quantity_mode/wastage_mode''s own posture on pricing_request_item. NULL on every pre-GLA-125 row and on any row Sales has not filled a payment term for yet.';
COMMENT ON COLUMN sales.pricing_request.credit_days IS
    'เครดิต N วัน -- meaningful only when payment_term_mode = CREDIT (required exactly then, forced NULL otherwise by PricingRequestService, mirroring how DealQuotationService forces remainder_mode/credit_days back to NULL once deposit_percent resolves to 0). Mirrors sales.quotation.credit_days (V165) in type.';
COMMENT ON COLUMN sales.pricing_request.validity_days IS
    'ยืนราคา N วัน -- mirrors sales.quotation.validity_days (V165). A simple day-count only (unlike quotation''s later DATE-mode option, V178) -- optional here, exactly as on the direct-deal document today.';
COMMENT ON COLUMN sales.pricing_request.printed_by_display_id IS
    'Print-only override, mirrors sales.quotation.printed_by_display_id (V179) exactly, including the same semantics: NULL means "print requested_by''s own name", the only behaviour that ever existed before this column.';
COMMENT ON COLUMN sales.pricing_request.sales_rep_display_id IS
    'Print-only override, mirrors sales.quotation.sales_rep_display_id (V179) exactly. NULL means "print requested_by''s own name".';
COMMENT ON COLUMN sales.pricing_request.dept_code IS
    'ฝ่าย -- mirrors sales.quotation.dept_code (V165). Free text, exactly as on the direct-deal document.';
COMMENT ON COLUMN sales.pricing_request.unit_code IS
    'หน่วยงาน, AND the ผู้ออกแบบ picker''s target field (mirrors sales.quotation.unit_code, V165/V173 -- a designer pick is a frontend convenience that writes this SAME free-text column, never a separate designer_id; the designer''s own name is confidential and never reaches this column, only the CODE does).';
COMMENT ON COLUMN sales.pricing_request.omit_contact_honorific IS
    'ไม่เติม "คุณ" หน้าชื่อผู้สั่งซื้อ -- mirrors sales.quotation.omit_contact_honorific (V180) exactly, including the NOT NULL DEFAULT FALSE (every existing row, including every pre-GLA-125 one, keeps today''s honorific-detection behaviour).';
