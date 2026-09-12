-- V172: make size_norm a canonical MILLIMETRE key instead of a normalised copy of raw text.
--
-- ── Why ──────────────────────────────────────────────────────────────────────────────────────
-- V153 defined size_norm as upper(regexp_replace(btrim(coalesce(size_raw,'')), '\s+','','g')) --
-- text normalisation only. It carries no unit information, so it silently assumes every row in a
-- join shares one unit convention. Two real defects follow directly from that:
--   1. Thickness gets glued onto the key: size_raw 'odd'60x120 9MM' -> size_norm '60X1209MM'.
--      Measured on production: 2,915 rows have a size_norm with a trailing thickness token baked
--      in (this is what ImportEngine's cleaning pass (V171 companion) now strips from size_raw's
--      PARSED width/height -- size_norm just never benefited from that parse).
--   2. The SAME physical tile from two documents with different declared units produced two
--      DIFFERENT keys: Padana '60X120' (cm) vs LEA '300x600' (mm) is one 60x120cm tile in both
--      cases, but yielded size_norm '60X120' and '300X600' -- opposite of what a join key for "is
--      this the same size" should do.
--
-- size_norm is a JOIN KEY: price_catalog.collection_thickness_default matches on
-- (factory_id, collection, size_norm), and price_catalog.v_priceable_product's thickness-default
-- LATERAL JOIN uses it. Measured on production before this migration: collection_thickness_default
-- has 37 rows total and ALL 37 have size_norm IS NULL (collection-level -- "the normal case" per
-- V153's own comment). ZERO live rows exercise the size_norm-VALUE branch of that join today, so
-- recanonicalising the column changes no live join result; it fixes a key that would silently
-- misbehave the day someone adds the first size-level override, and fixes the 2,915 already-wrong
-- values now.
--
-- ── What changes ─────────────────────────────────────────────────────────────────────────────
-- New canonical form: '<trim_scale(width_mm)>X<trim_scale(height_mm)>' -- e.g. width_mm=600.00,
-- height_mm=1200.00 -> '600X1200'. Both a Padana '60X120' (cm, -> width_mm/height_mm 600/1200) and
-- a LEA '300x600' (mm, -> width_mm/height_mm 300/600 -- a DIFFERENT physical tile, note, so a
-- different key is correct there) now key off the NORMALISED millimetre dimensions rather than raw
-- text, so two documents describing the SAME physical tile in different declared units produce the
-- SAME key. Thickness is excluded entirely -- it has thickness_mm of its own; gluing it into the
-- size key was exactly the V153-era bug.
--
-- Rows with no usable width_mm/height_mm (58 historically, per docs/price-catalog-reconciliation-
-- 2026-08-17.md: 27 LEA + 8 Padana + 4 Panaria + 19 REFIN) fall back to the OLD raw-text
-- normalisation. This is a deliberate, safe degradation, not data loss: those rows have no
-- millimetre dimensions to canonicalise FROM, so the alternative to "reuse the old, unit-blind
-- form" would be a NULL key -- worse for the (collection-level, size_norm IS NULL) join this
-- column mostly serves, since it would make every such row indistinguishable from an intentional
-- NULL under `t.size_norm IS NULL OR t.size_norm = p.size_norm`... actually harmless either way
-- because that clause treats t.size_norm IS NULL as "matches everything" regardless of p.size_norm,
-- but a real, stable (if imperfect) string is friendlier for any future size_norm-VALUE override
-- than a NULL would be.
--
-- Owner-verified: NOT ONE of the existing width_mm/height_mm values is being touched, rewritten, or
-- "corrected" by this migration -- production has 1,503 rows where the parsed geometry disagrees
-- with box-derived m2/piece, and the owner confirmed none of that 1,503 is explained by a x10/\10
-- scaling error. size_raw is untouched, verbatim, as always -- it is the provenance record of what
-- the source document actually said.
--
-- ── Mechanics: this REWRITES THE WHOLE TABLE ────────────────────────────────────────────────
-- Postgres has no ALTER COLUMN ... generated-expression form -- changing a GENERATED ALWAYS AS
-- expression requires dropping and re-adding the column, which recomputes it for every row
-- (~24,000+ rows at last count). price_catalog.v_priceable_product SELECTs p.size_norm directly, so
-- it depends on the column and must be dropped and recreated around the change (its body is
-- otherwise byte-for-byte identical to V153's -- no other logic in it changes). The dependent index
-- is dropped and recreated too. None of this touches product_price_staging, which has no size_norm
-- column of its own.
--
-- ── Rollback plan ────────────────────────────────────────────────────────────────────────────
-- Never edit this file once applied. To revert, author a NEW forward-only migration that runs, in
-- order (this also rewrites the whole table, for the same reason as above):
--
--   DROP VIEW price_catalog.v_priceable_product;
--   DROP INDEX price_catalog.idx_pp_size_norm;
--   ALTER TABLE price_catalog.product_prices DROP COLUMN size_norm;
--   ALTER TABLE price_catalog.product_prices
--       ADD COLUMN size_norm TEXT GENERATED ALWAYS AS
--           (upper(regexp_replace(btrim(coalesce(size_raw, '')), '\s+', '', 'g'))) STORED;
--   CREATE INDEX idx_pp_size_norm ON price_catalog.product_prices(factory_id, size_norm);
--   CREATE VIEW price_catalog.v_priceable_product AS
--   -- ... paste V153's CREATE VIEW body verbatim (unchanged since V153) ...
--
-- and re-apply this migration's own COMMENT ON statements' inverse (drop them; they are advisory).

DROP VIEW price_catalog.v_priceable_product;
DROP INDEX price_catalog.idx_pp_size_norm;

ALTER TABLE price_catalog.product_prices DROP COLUMN size_norm;

ALTER TABLE price_catalog.product_prices
    ADD COLUMN size_norm TEXT GENERATED ALWAYS AS (
        CASE
            WHEN width_mm IS NOT NULL AND height_mm IS NOT NULL
                 AND width_mm > 0 AND height_mm > 0
            THEN trim_scale(width_mm)::text || 'X' || trim_scale(height_mm)::text
            ELSE upper(regexp_replace(btrim(coalesce(size_raw, '')), '\s+', '', 'g'))
        END
    ) STORED;

COMMENT ON COLUMN price_catalog.product_prices.size_norm IS
    'V172: canonical join key derived from the NORMALISED millimetre dimensions -- '
    '''<trim_scale(width_mm)>X<trim_scale(height_mm)>'', e.g. ''600X1200'' -- so the same physical '
    'tile keys identically regardless of which document''s declared unit (mm/cm) it was imported '
    'under. Falls back to the V153 raw-text form (upper, whitespace-stripped size_raw) only when '
    'width_mm/height_mm are unavailable (58 rows historically). Thickness is NEVER part of this key '
    '-- see thickness_mm.';

CREATE INDEX idx_pp_size_norm ON price_catalog.product_prices(factory_id, size_norm);

-- Recreated verbatim from V153 -- the only change forced by the DROP above is that it exists again.
CREATE VIEW price_catalog.v_priceable_product AS
SELECT p.price_id,
       p.factory_id,
       f.name        AS factory,
       f.country     AS origin_country_code,
       p.version_id,
       p.product_code, p.grade, p.collection, p.product_name,
       p.size_raw, p.size_norm,
       COALESCE(p.thickness_mm, d.thickness_mm) AS thickness_mm,
       (p.thickness_mm IS NULL AND d.thickness_mm IS NOT NULL) AS thickness_is_default,
       'TILE'::VARCHAR(32) AS product_type,      -- tiles-only scope, owner ruling 2026-08-17
       p.currency, p.price AS source_price, p.price_unit,
       p.price_per_sqm,
       p.sqm_per_piece, p.sqm_per_linear_m,
       -- Corrects the mislabelled box quantity for per-metre rows (see column comment above).
       CASE WHEN p.price_unit = 'per_linear_m' AND p.sqm_per_linear_m IS NOT NULL
            THEN p.sqm_per_box * p.sqm_per_linear_m
            ELSE p.sqm_per_box END AS true_sqm_per_box,
       CASE
           WHEN p.price_per_sqm IS NULL THEN 'NO_SQM_BASIS'
           WHEN COALESCE(p.thickness_mm, d.thickness_mm) IS NULL THEN 'NO_THICKNESS'
           WHEN COALESCE(p.thickness_mm, d.thickness_mm) < 3
             OR COALESCE(p.thickness_mm, d.thickness_mm) >= 21 THEN 'THICKNESS_OUT_OF_BAND'
           ELSE 'PRICEABLE'
       END AS pricing_status
  FROM price_catalog.product_prices p
  JOIN price_catalog.factories           f ON f.factory_id = p.factory_id
  JOIN price_catalog.price_list_versions v ON v.version_id = p.version_id
  LEFT JOIN LATERAL (
      SELECT t.thickness_mm
        FROM price_catalog.collection_thickness_default t
       WHERE t.factory_id = p.factory_id
         AND (t.collection IS NULL OR t.collection = p.collection)
         AND (t.size_norm  IS NULL OR t.size_norm  = p.size_norm)
       ORDER BY (t.size_norm IS NOT NULL) DESC, (t.collection IS NOT NULL) DESC
       LIMIT 1
  ) d ON TRUE
 WHERE v.status = 'ACTIVE';

COMMENT ON VIEW price_catalog.v_priceable_product IS
    'The pricing engine reads ONLY this. Filters to the ACTIVE price-list version -- nothing else '
    'in the codebase enforces that, and a stale DRAFT version has been observed lingering.';
