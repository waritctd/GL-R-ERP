-- V164: expose two more raw catalogue columns through v_priceable_product for a NEW read-only
-- consumer -- the "prefill everything" pass's estimators (SPEC-PREFILL.md) -- neither of which is
-- a pricing input LandedCostCalculator consumes.
--
-- Postgres only allows CREATE OR REPLACE VIEW to APPEND columns, never reorder or remove them, so
-- both new columns go at the very end. Nothing about the view's existing contract changes: same
-- FROM/WHERE, same ACTIVE-version filter, same pricing_status logic -- V153's own comment ("the
-- pricing engine reads ONLY this") still holds; this migration only adds two columns nothing in
-- the engine reads.
--
-- kg_per_box / pcs_per_box are box-level facts from the source workbook (see V40's import_profiles
-- seed -- KG_SCA/PZ_SCA and friends), never priced-affecting on their own:
--   * PricingRequestThicknessSuggestionService (ladder A, rung 4 -- box weight) divides
--     kg_per_box by the ALREADY-CORRECTED true_sqm_per_box (defined two columns up in the SELECT
--     list) and a per-factory density to imply a thickness -- see ThicknessEstimator for the
--     density constants and why Equipe is suppressed and Vives is unvalidated.
--   * The frontend's "พื้นที่ต่อ 1 หน่วย" prefill (ladder B, rung 2 -- box ratio) divides
--     true_sqm_per_box by pcs_per_box, the SAME derivation V153 step 4 already ran once, at
--     migration time, to backfill sqm_per_piece for rows that existed then. Exposing the raw
--     inputs live covers a row that backfill never reached (added after V153, or by hand through
--     ProductFormModal, with sqm_per_piece still null).
CREATE OR REPLACE VIEW price_catalog.v_priceable_product AS
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
       END AS pricing_status,
       -- New in V164 (appended -- see this migration's header for why these two must go last).
       -- Deliberately RAW, unlike true_sqm_per_box above: pcs_per_box needs no per-linear-m
       -- correction (a piece count is a piece count regardless of price basis), and kg_per_box is
       -- always read alongside the ALREADY-corrected true_sqm_per_box, so no consumer ever divides
       -- it by the mislabelled-as-sqm raw figure.
       p.kg_per_box, p.pcs_per_box
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
    'The pricing engine reads ONLY this for pricing. kg_per_box/pcs_per_box (V164) are the one '
    'exception -- box-level facts read only by the thickness/sqm-per-unit PREFILL estimators '
    '(PricingRequestThicknessSuggestionService on the backend, deriveSqmPerPiece''s ladder on the '
    'frontend), never by LandedCostCalculator. Filters to the ACTIVE price-list version -- nothing '
    'else in the codebase enforces that, and a stale DRAFT version has been observed lingering.';
