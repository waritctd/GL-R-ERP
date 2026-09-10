-- Quotation v3 (owner feedback pass 3, 2026-09-11) — line types + price entry modes.
--
-- MIGRATION NUMBERING: origin/develop tops out at V167 (deal quotation contact snapshot), so V168
-- is the next free number ABOVE develop's max. A LOWER number merged after a higher one is
-- SILENTLY SKIPPED on the prod deploy (validate-on-migrate is false there — see CLAUDE.md), so
-- never renumber this downward and never renumber it at all once it is on develop.
--
-- THIS IS A DELIBERATE SCHEMA + CONTRACT CHANGE, not a side effect of UI work. It is authorised
-- under CLAUDE.md's "Sales flow redesign — business logic IS changing" relaxation, which is scoped
-- to the sales pricing/deal workflow. Payroll/tax/SSO/commission math is untouched by this file.
--
-- WHAT THE OWNER ASKED FOR (2026-09-11, from nine of her own real ใบเสนอราคา):
--
--   S1. Three price ENTRY modes for tile rows. Today the rep types a list price and a discount
--       percent; her documents also show two other habits — a ราคาพิเศษ quoted in บาท per ตร.ม.
--       INCLUDING VAT, and a straight per-piece net price. The mode is PER-QUOTATION, not
--       per-item: in every one of the nine samples, every tile row in a document uses the same
--       mode, so one selector is the least typing. Hence `price_mode` lands on sales.quotation.
--   S2. PLAIN rows — description/quantity/unit/unit price and none of the tile machinery. Freight
--       ("Transportation Charges from China to Male Port" — 1 JOB), Mapei consumables (Bags,
--       Barrels), the กระเบื้องตัด cut service, sanitary-ware ชุด rows.
--   S3. ADJUSTMENT rows — the ส่วนลดพิเศษ line. Her QN6900704-2 prints จำนวน −1, no unit, a
--       POSITIVE ราคา/คงเหลือ and a NEGATIVE เป็นเงิน, and the figure is a percentage of the rows
--       above it (149,767.20 + 96,012.60 + 528,269.76 + 499,224.00 = 1,273,273.56, × 3% =
--       38,198.21, exactly as printed).
--
-- ── sales.quotation.price_mode ────────────────────────────────────────────────────────────────
-- NULL is read as 'NET' by th.co.glr.hr.dealquotation.DealQuotationService, so every pre-V168 row
-- keeps today's behaviour with no rewrite. The backfill below is hygiene for rows this feature
-- owns, not a correctness requirement — but it makes the column honest to anyone reading the table
-- directly rather than leaving "NULL means NET" as tribal knowledge.
ALTER TABLE sales.quotation ADD COLUMN price_mode VARCHAR(16);

-- A CHECK, not an enum type: this table's own convention (chk_quotation_doc_status, V52/V74/V165)
-- and it stays widenable by a later DROP + re-ADD. NULL passes a CHECK in Postgres, which is what
-- keeps every legacy row (and every Step-4 / pricing-chain row, which never sets this) legal.
ALTER TABLE sales.quotation ADD CONSTRAINT chk_quotation_price_mode
    CHECK (price_mode IN ('NET', 'SPECIAL_SQM', 'DIRECT_NET'));

-- ── sales.quotation_item.line_type ────────────────────────────────────────────────────────────
-- NULL is read as 'TILE'. Same reasoning as price_mode: no rewrite of existing rows is required
-- for them to keep behaving exactly as they do today.
ALTER TABLE sales.quotation_item ADD COLUMN line_type VARCHAR(16);

ALTER TABLE sales.quotation_item ADD CONSTRAINT chk_quotation_item_line_type
    CHECK (line_type IN ('TILE', 'PLAIN', 'ADJUSTMENT'));

-- ── SPECIAL_SQM: the rep's ราคาพิเศษ, in บาท per ตร.ม., INCLUDING VAT ──────────────────────────
-- Stored because it is NOT recoverable from what we already keep. final_unit_price holds the
-- computed per-piece net, and getting back from that to the ราคาพิเศษ would mean inverting two
-- HALF_UP roundings — which is not a function. It is also printed verbatim on the item's sub-line
-- ("(ราคาพิเศษ 1,350 บาท/ตรม ราคารวมภาษีมูลค่าเพิ่ม)"), so the document itself needs it back.
--
-- Deliberately NOT stored, by contrast: DIRECT_NET's rep-typed net price. That one IS exactly
-- final_unit_price — no rounding sits between the input and the stored value — so a second column
-- would be a duplicate that could drift out of step with the first.
ALTER TABLE sales.quotation_item ADD COLUMN special_price_sqm NUMERIC(12,2);

-- ── ADJUSTMENT: the rep types a percent and a deadline; the system derives everything else ─────
-- Both nullable because an adjustment may instead be a flat baht amount (the owner's "also accept
-- a flat amount" case), in which case the figure lives in unit_price/amount like any other row and
-- these two stay NULL.
--
-- NUMERIC(6,3) rather than the (5,2) used by discount_pct: an adjustment percent is applied to a
-- whole-document base that can run to seven figures, where a third decimal place is worth ~1 baht.
-- discount_pct is per-piece and does not have that leverage.
ALTER TABLE sales.quotation_item ADD COLUMN adjustment_pct NUMERIC(6,3);

-- The "สั่งซื้อภายใน" date the composed description prints (31/07/2569 in QN6900704-2). A DATE, not
-- text: it is a real calendar date the rep picks, and the Thai Buddhist-era formatting is a
-- PRESENTATION concern done at render time (DealQuotationLines#adjustmentDescription), never
-- stored pre-formatted. Storing the rendered string would make the year un-queryable and would
-- freeze the format.
ALTER TABLE sales.quotation_item ADD COLUMN adjustment_deadline DATE;

-- ── Backfill: this feature's own rows only ────────────────────────────────────────────────────
-- Scoped to origin = 'DEAL_DIRECT' (V165's tag) so it can never touch a legacy ticket-item row or
-- a Step-4 / pricing-chain row — neither of which this code reads, and both of which would be
-- mislabelled by a blanket UPDATE. The IS NULL guards make these TWO UPDATE statements inert on
-- replay -- and only them. The five ADD COLUMNs above carry no IF NOT EXISTS and would ERROR if
-- this file ran a second time, so "inert on replay" is a statement about the backfill, never about
-- the migration as a whole. Under Flyway a migration never replays, so this is precision about what
-- the SQL does, not a deploy risk; it is spelled out because the loose phrasing was read the other
-- way in review.
UPDATE sales.quotation
   SET price_mode = 'NET'
 WHERE origin = 'DEAL_DIRECT'
   AND price_mode IS NULL;

UPDATE sales.quotation_item qi
   SET line_type = 'TILE'
  FROM sales.quotation q
 WHERE q.quotation_id = qi.quotation_id
   AND q.origin = 'DEAL_DIRECT'
   AND qi.line_type IS NULL;
