-- V171: import-time provenance for the catalogue's dimensional data, and the columns the
-- ImportEngine reconciliation/quarantine pass writes.
--
-- Companion code change: ImportEngine deletes the "size < 300 mm => must be centimetres" magnitude
-- guess entirely. The unit is now a REQUIRED, explicit property of each ImportProfile
-- (size_unit: "mm" | "cm") -- never inferred from the numbers, never defaulted. A profile missing
-- it fails the whole import with a message naming the profile, rather than guessing.
--
-- Purely additive: two new NULLABLE columns on product_prices, mirrored on
-- product_price_staging (which also gains sqm_per_linear_m, added to product_prices back in V153
-- but never carried onto staging). No backfill, no rewrite of any existing row -- verified against
-- production that no existing row is currently mis-scaled by 10x, so there is nothing to repair.
-- Existing rows simply read NULL for both new columns until they are next re-imported.

ALTER TABLE price_catalog.product_prices
    ADD COLUMN size_unit_declared TEXT
        CHECK (size_unit_declared IN ('mm', 'cm')),
    ADD COLUMN sqm_provenance TEXT
        CHECK (sqm_provenance IN (
            'box_reconciled',           -- m2/box / pcs/box agreed with width x height (<=2% tolerance)
            'box_only_no_dims',         -- box figures used; no parsed width/height to check them against
            'computed_from_dimensions', -- no usable box figures; sqm_per_piece = width_mm * height_mm
            'linear_metre_not_area',    -- per_linear_m: box "sqm" column is LINEAR METRES, not area
                                        -- (see sqm_per_linear_m instead) -- sqm_per_piece is deliberately NULL
            'unavailable',              -- neither box figures nor parsed dimensions exist
            'mismatch_quarantined'      -- box figure and width x height disagreed beyond tolerance;
                                        -- the row was staged with import_error set and excluded from commit
        ));

COMMENT ON COLUMN price_catalog.product_prices.size_unit_declared IS
    'The ImportProfile.size_unit ("mm"/"cm") in force when this row was parsed -- never guessed. '
    'NULL on any row imported before V171 (no backfill was performed; see V171''s own header).';

COMMENT ON COLUMN price_catalog.product_prices.sqm_provenance IS
    'How sqm_per_piece on this row was obtained, or why it is NULL. See ImportEngine''s '
    'PriceRow#sqmProvenance javadoc for the full decision table. NULL on any row imported before '
    'V171 (no backfill was performed; see V171''s own header).';

ALTER TABLE price_catalog.product_price_staging
    ADD COLUMN size_unit_declared TEXT
        CHECK (size_unit_declared IN ('mm', 'cm')),
    ADD COLUMN sqm_provenance TEXT
        CHECK (sqm_provenance IN (
            'box_reconciled', 'box_only_no_dims', 'computed_from_dimensions',
            'linear_metre_not_area', 'unavailable', 'mismatch_quarantined'
        )),
    ADD COLUMN sqm_per_linear_m NUMERIC(10, 6);

COMMENT ON COLUMN price_catalog.product_price_staging.sqm_per_linear_m IS
    'Staging never got this column when V153 added it to product_prices. Carries the per-import '
    'per_linear_m coverage figure (see product_prices.sqm_per_linear_m''s own comment) from parse '
    'through to commit -- PriceImportService''s commit() INSERT...SELECT now includes it.';

-- Rollback: DROP the six new columns (three on each table) added above. All are nullable with no
-- other object depending on them, so this is a plain, non-cascading drop:
--   ALTER TABLE price_catalog.product_prices
--       DROP COLUMN size_unit_declared, DROP COLUMN sqm_provenance;
--   ALTER TABLE price_catalog.product_price_staging
--       DROP COLUMN size_unit_declared, DROP COLUMN sqm_provenance, DROP COLUMN sqm_per_linear_m;
-- (as a NEW forward-only migration -- never edit this file in place once applied.)
