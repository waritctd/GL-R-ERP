-- V152: give every catalogue row a per-sqm price basis and a resolvable thickness, then expose the
-- one surface the pricing engine will read.
--
-- The CEO formula works entirely in square metres: UC = TC / Q, and both the freight and clearance
-- bands key off Q in sqm. The catalogue does not -- it stores whatever unit each factory quotes in
-- (per_sqm, per_piece, per_box, per_linear_m), and 41.9% of rows have no thickness at all because
-- four of the nine source workbooks simply do not carry the column.
--
-- Measured before this migration: 55.6% of 22,455 rows could resolve a freight band AND a sqm
-- basis. This migration is what moves that number.

-- ── 1. Thickness defaults, CEO-maintained ────────────────────────────────────
-- Vives (4,617 rows), Padana (2,435), Equipe (2,122) and Bode (209) have no thickness column in
-- the source at all, so no parser change can recover it. 244 (factory, collection) pairs cover
-- every missing row -- comparable to the 39 freight rows the CEO already maintains.
CREATE TABLE price_catalog.collection_thickness_default (
    default_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    factory_id   BIGINT NOT NULL REFERENCES price_catalog.factories(factory_id),
    collection   TEXT,                    -- NULL = whole-factory fallback
    size_norm    TEXT,                    -- NULL = whole-collection (the normal case)
    thickness_mm NUMERIC(8,2) NOT NULL CHECK (thickness_mm > 0),
    note         TEXT,
    updated_by   BIGINT REFERENCES hr.employee(employee_id),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_collection_thickness UNIQUE NULLS NOT DISTINCT
        (factory_id, collection, size_norm)
);

COMMENT ON TABLE price_catalog.collection_thickness_default IS
    'CEO-maintained thickness fallbacks. Resolution is most-specific-first: the row''s own '
    'thickness_mm, then (factory, collection, size_norm), then (factory, collection), then '
    '(factory). Deliberately NO global default -- a guessed thickness picks a freight band that '
    'can differ by 50,000 THB per shipment, so refusing to price is the correct failure.';

CREATE INDEX idx_collection_thickness_lookup
    ON price_catalog.collection_thickness_default(factory_id, collection);

-- ── 2. Normalised size key ───────────────────────────────────────────────────
-- Also closes the case-duplicate defect: '120X278' and '120x278' were different uq_price keys, so
-- 34 REFIN rows exist twice for the same product.
ALTER TABLE price_catalog.product_prices
    ADD COLUMN size_norm TEXT GENERATED ALWAYS AS
        (upper(regexp_replace(btrim(coalesce(size_raw, '')), '\s+', '', 'g'))) STORED;

CREATE INDEX idx_pp_size_norm ON price_catalog.product_prices(factory_id, size_norm);

-- ── 3. Linear-metre → sqm conversion ─────────────────────────────────────────
-- A trim piece sold by the metre has a face of profile_height x length, so one linear metre covers
-- profile_height square metres. The profile is the SHORTER parsed dimension: for a 7x60 cm
-- battiscopa the 7 cm is the profile and the 60 cm is the length, already accounted for by "per
-- linear metre". Measured across the 453 per-metre rows, this resolves to 55-100 mm throughout --
-- exactly the range skirting and bullnose profiles occupy.
ALTER TABLE price_catalog.product_prices
    ADD COLUMN sqm_per_linear_m NUMERIC(10,6);

UPDATE price_catalog.product_prices
   SET sqm_per_linear_m = round(least(width_mm, height_mm) / 1000.0, 6)
 WHERE price_unit = 'per_linear_m'
   AND width_mm IS NOT NULL AND height_mm IS NOT NULL
   AND least(width_mm, height_mm) > 0;

COMMENT ON COLUMN price_catalog.product_prices.sqm_per_linear_m IS
    'Square metres covered by one linear metre = profile height = min(width_mm, height_mm)/1000. '
    'NOTE: for these rows the source sqm_per_box column holds LINEAR METRES, not m2 -- CITY '
    'battiscopa reports 6.0 for a box that is really 0.42 m2, a 14.3x overstatement. Multiply '
    'sqm_per_box by this factor before summing into a shipment total.';

-- ── 4. sqm_per_piece: two-step derivation ────────────────────────────────────
-- Step 1 -- from the box figures, where the source supplies them.
UPDATE price_catalog.product_prices
   SET sqm_per_piece = round(sqm_per_box / pcs_per_box, 6)
 WHERE sqm_per_piece IS NULL
   AND coalesce(pcs_per_box, 0) > 0
   AND coalesce(sqm_per_box, 0) > 0;

-- Step 2 -- from the parsed geometry. Load-bearing and easy to miss: 132 per_piece rows have
-- NEITHER sqm_per_piece NOR sqm_per_box, so step 1 cannot reach them; step 2 recovers 112.
UPDATE price_catalog.product_prices
   SET sqm_per_piece = round(width_mm * height_mm / 1000000.0, 6)
 WHERE sqm_per_piece IS NULL
   AND price_unit IN ('per_piece', 'per_box')
   AND width_mm IS NOT NULL AND height_mm IS NOT NULL
   AND width_mm > 0 AND height_mm > 0;

-- ── 5. The canonical price the formula consumes ──────────────────────────────
-- GENERATED, so it can never drift from its inputs. price_unit 'unknown' yields NULL by design:
-- 164 CDE rows carry UOM 'M' (linear metre) that the importer did not map, and inventing a sqm
-- price for them would be worse than refusing. Reclassifying those is a separate, stated change.
ALTER TABLE price_catalog.product_prices
    ADD COLUMN price_per_sqm NUMERIC(14,6) GENERATED ALWAYS AS (
        CASE price_unit
            WHEN 'per_sqm'      THEN price
            WHEN 'per_piece'    THEN price / NULLIF(sqm_per_piece,    0)
            WHEN 'per_box'      THEN price / NULLIF(sqm_per_box,      0)
            WHEN 'per_linear_m' THEN price / NULLIF(sqm_per_linear_m, 0)
            ELSE NULL
        END
    ) STORED;

-- ── 6. The engine's only surface ─────────────────────────────────────────────
-- pricing_status is computed HERE rather than stored on the row: it depends on the thickness
-- lookup, so a stored copy would go stale the moment the CEO adds a default. (The design doc had
-- it as a column; this is a deliberate improvement on that.)
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
