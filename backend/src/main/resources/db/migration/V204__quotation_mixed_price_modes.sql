-- Mixed price modes on a direct-deal quotation.
--
-- Sales feedback #14 (แจ้งปัญหาการใช้งาน เสนอราคา): "อยากให้เลือกการคิดราคาแต่ละข้อไม่เหมือนกันได้ เช่น
-- บางข้อราคาพิเศษต่อตรม บางข้อลด% ตามสเต็ป". V168 decided the opposite on purpose — its header reads
-- "PER-QUOTATION, not per-item", because in all nine of the owner's sample documents every tile row
-- used the same mode — and the request was first declined ("one quotation = one discount format").
-- Owner ruling 2026-10-06 reverses that: the rep chooses between ONE mode for the whole document
-- (still the default, still what almost every document is) and a MIX.
--
-- The model, deliberately the smallest one that works:
--
--   * sales.quotation.price_mode gains a fourth value, 'MIXED'. It is a property of the DOCUMENT:
--     "this document has no single mode".
--   * sales.quotation_item.price_mode holds the mode of ONE tile row — NET / SPECIAL_SQM /
--     DIRECT_NET — and ONLY on a row of a MIXED document. On every other row it is NULL and the
--     document's own mode applies (WastageCalculator#rowPriceMode is the one rule, on the write
--     path, the read path, the printed ส่วนลด cell and the special-pricing gate alike).
--
-- So nothing changes for any row that exists today: every existing quotation_item gets NULL, every
-- existing quotation keeps its mode, and a single-mode document is priced, stored and printed by
-- exactly the code it always was. A row is never 'MIXED' itself — the item CHECK below does not
-- admit it — because a row is always priced in exactly one way.
--
-- PLAIN (สินค้า/บริการอื่น) and ADJUSTMENT (ส่วนลดพิเศษ) rows have no price mode on any document, mixed
-- or not, and stay NULL.
--
-- Not enforced in SQL, on purpose: "a MIXED document's tile rows must each state a mode" and "MIXED
-- is for Thai, direct-deal documents only" are rules about a whole document and its origin, which
-- a row-level CHECK cannot see. DealQuotationService refuses both at write time (buildItems,
-- requirePriceModeAllowed); DealQuotationMixedPriceModeIntegrationTest pins them.
--
-- Rollback note: this migration is additive and harmless to older code UNTIL a MIXED document is
-- saved. A backend build from before this change does not know the value — it would read such a
-- document's rows by rules for a mode it has never heard of. Do not roll the backend back past
-- this change once a MIXED quotation exists; correct forward instead.

ALTER TABLE sales.quotation
    DROP CONSTRAINT chk_quotation_price_mode;

ALTER TABLE sales.quotation
    ADD CONSTRAINT chk_quotation_price_mode
        CHECK (price_mode IN ('NET', 'SPECIAL_SQM', 'DIRECT_NET', 'MIXED'));

ALTER TABLE sales.quotation_item
    ADD COLUMN price_mode VARCHAR(16);

ALTER TABLE sales.quotation_item
    ADD CONSTRAINT chk_quotation_item_price_mode
        CHECK (price_mode IN ('NET', 'SPECIAL_SQM', 'DIRECT_NET'));

COMMENT ON COLUMN sales.quotation.price_mode IS
    'How the document''s TILE rows are priced: NET (list price - discount %), SPECIAL_SQM (special price per sqm), DIRECT_NET (net price typed directly), or MIXED (V204: each tile row states its own in quotation_item.price_mode). NULL reads as NET.';

COMMENT ON COLUMN sales.quotation_item.price_mode IS
    'V204 (owner ruling 2026-10-06): this TILE row''s own price mode - NET / SPECIAL_SQM / DIRECT_NET - on a document whose quotation.price_mode is MIXED. NULL on every row of a single-mode document (the document''s mode applies) and on every PLAIN / ADJUSTMENT row.';
