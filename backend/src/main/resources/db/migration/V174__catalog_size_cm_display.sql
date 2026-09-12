-- V174: a clean, ready-to-display size in CENTIMETRES, derived from the canonical millimetres.
--
-- ── Why ──────────────────────────────────────────────────────────────────────────────────────
-- Every surface that shows a size to a human shows it in CENTIMETRES -- the quotation editor's
-- field is literally labelled "ขนาด (ซม.)" with the hint "เช่น 60x120", and the printed
-- ใบเสนอราคา prints cm too. Until now there was no cm value anywhere in the database, so each
-- caller re-derived one, and the quotation editor did not derive it at all: it displayed
-- `size_raw` verbatim.
--
-- `size_raw` is the factory's own string in the factory's OWN declared unit, kept for provenance
-- (V171). It is the wrong thing to display, in two different ways, both reported by the owner:
--   1. The four mm-declared sources (Panaria, LEA, CDE, Bode) wrote millimetres, so a 20x20 cm
--      tile displayed as "200x200" in a field labelled ซม. -- Panaria APGEBK15, whose own product
--      name says "30*20X20", showed 200x200 (owner, 2026-09-13).
--   2. Equipe's profile takes `size_from: product_name`, so its size_raw IS the product name:
--      VEN027994 displayed "1,2X20 JOLLY COCO W" (owner, 2026-09-13).
-- Measured on production: 18,353 of 24,486 rows with dimensions display differently once the
-- value is derived from the catalogue's own millimetres instead of from size_raw.
--
-- ── What this adds ───────────────────────────────────────────────────────────────────────────
-- size_cm: GENERATED ALWAYS ... STORED, exactly like size_norm (V172) and for the same reason --
-- a derived value that can never drift from the dimensions it is derived from, and that covers
-- every existing row the moment the column exists, with no backfill to forget and no importer
-- change needed. width_mm/height_mm are ALWAYS millimetres (V171/V172 guarantee this; re-verified
-- across all ten brands in production, every one mm-consistent with no magnitude outliers), so
-- this is a pure /10 with NO unit inference -- which is what the owner's "Do not infer anything,
-- proceed based on the catalogue" ruling requires.
--
-- Form: '<trim_scale(width_mm/10)>x<trim_scale(height_mm/10)>' -- lowercase 'x', matching the
-- field's own "เช่น 60x120" hint, and trailing zeros trimmed by trim_scale so 600x1200 mm reads
-- '60x120' rather than '60.00x120.00'. A genuine fraction survives: Vives 364x337 mm -> '36.4x33.7'.
-- NULL when either dimension is absent (~50 rows) -- callers fall back to size_raw there, because
-- a dirty string a rep can correct beats an empty required field.
--
-- Deliberately a SEPARATE column from size_norm rather than a reformat of it: size_norm is a JOIN
-- KEY (collection_thickness_default matches on it, and v_priceable_product's LATERAL uses it) and
-- must stay canonical millimetres. This one is for display only and is never joined on.
--
-- ── Rollback plan ────────────────────────────────────────────────────────────────────────────
-- Never edit this file once applied. To revert, author a NEW forward-only migration running:
--   ALTER TABLE price_catalog.product_prices DROP COLUMN size_cm;
-- Nothing joins on it and no view selects it at the time of writing, so a drop is self-contained.
-- Adding a STORED generated column rewrites the table (~24,500 rows), same cost as V172.

ALTER TABLE price_catalog.product_prices
    ADD COLUMN size_cm TEXT GENERATED ALWAYS AS (
        CASE
            WHEN width_mm IS NOT NULL AND height_mm IS NOT NULL
                 AND width_mm > 0 AND height_mm > 0
            THEN trim_scale(width_mm / 10)::text || 'x' || trim_scale(height_mm / 10)::text
            ELSE NULL
        END
    ) STORED;

COMMENT ON COLUMN price_catalog.product_prices.size_cm IS
    'DISPLAY size in centimetres, derived from width_mm/height_mm: '
    '''<trim_scale(width_mm/10)>x<trim_scale(height_mm/10)>'', e.g. ''60x120'', ''1.2x20''. '
    'Use THIS for anything shown to a human; never size_raw, which is the factory''s own string '
    'in the factory''s own unit and for some sources (Equipe) is the product name. NULL when the '
    'row carries no dimensions. Display only -- size_norm remains the canonical mm join key.';
