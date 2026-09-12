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
--
-- ── Extended in place, 2026-09-12, owner ruling ("normalize it in the database so its the same in
-- unit. make the size cm and the thickness mm") ─────────────────────────────────────────────────
-- Adds thickness_unit_declared, the thickness twin of size_unit_declared: ImportEngine also used
-- to assume a bare (un-suffixed) third size-string value was ALWAYS already millimetres, and that
-- is false for the Chinese "2026 GENERAL EXPORT" list ("60X120X1.0" = a 9 mm tile, written in
-- CENTIMETRES -- confirmed by that workbook's own "2CM" tab name for the 20 mm slabs). "none" is a
-- legitimate declared value (Bode/Vives/Equipe genuinely carry no thickness data; the owner is
-- sending separate thickness files for them), not a placeholder for "not yet configured".
--
-- THIS FILE HAS NEVER BEEN APPLIED ANYWHERE -- production's Flyway is at max version 170, and V171
-- (like V172/V173) has not been merged or run. Extending it here is therefore NOT the "never edit
-- an already-applied migration" violation CLAUDE.md warns against -- there is nothing to roll back
-- in any real database, so this is edited in place rather than adding a V174 that would ALTER a
-- column added moments earlier in the same still-unreleased batch. Once this migration (or any
-- later one) has actually run somewhere, this rule stops applying and any further change here must
-- be a new forward-only migration instead.
--
-- Purely additive here too: one more NULLABLE, CHECK-constrained column on each table, same shape
-- as size_unit_declared. No backfill (same reasoning as above -- existing rows simply read NULL
-- until re-imported).

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
        )),
    ADD COLUMN thickness_unit_declared TEXT
        CHECK (thickness_unit_declared IN ('mm', 'cm', 'none'));

COMMENT ON COLUMN price_catalog.product_prices.size_unit_declared IS
    'The ImportProfile.size_unit ("mm"/"cm") in force when this row was parsed -- never guessed. '
    'NULL on any row imported before V171 (no backfill was performed; see V171''s own header).';

COMMENT ON COLUMN price_catalog.product_prices.sqm_provenance IS
    'How sqm_per_piece on this row was obtained, or why it is NULL. See ImportEngine''s '
    'PriceRow#sqmProvenance javadoc for the full decision table. NULL on any row imported before '
    'V171 (no backfill was performed; see V171''s own header).';

COMMENT ON COLUMN price_catalog.product_prices.thickness_unit_declared IS
    'The ImportProfile.thickness_unit ("mm"/"cm"/"none") in force when this row was parsed -- '
    'never guessed. "none" records a genuine absence of thickness data in the source (Bode/Vives/'
    'Equipe), not a missing declaration. NULL on any row imported before this column was added.';

ALTER TABLE price_catalog.product_price_staging
    ADD COLUMN size_unit_declared TEXT
        CHECK (size_unit_declared IN ('mm', 'cm')),
    ADD COLUMN sqm_provenance TEXT
        CHECK (sqm_provenance IN (
            'box_reconciled', 'box_only_no_dims', 'computed_from_dimensions',
            'linear_metre_not_area', 'unavailable', 'mismatch_quarantined'
        )),
    ADD COLUMN sqm_per_linear_m NUMERIC(10, 6),
    ADD COLUMN thickness_unit_declared TEXT
        CHECK (thickness_unit_declared IN ('mm', 'cm', 'none'));

COMMENT ON COLUMN price_catalog.product_price_staging.sqm_per_linear_m IS
    'Staging never got this column when V153 added it to product_prices. Carries the per-import '
    'per_linear_m coverage figure (see product_prices.sqm_per_linear_m''s own comment) from parse '
    'through to commit -- PriceImportService''s commit() INSERT...SELECT now includes it.';

-- Rollback: DROP the eight new columns (four on each table) added above. All are nullable with no
-- other object depending on them, so this is a plain, non-cascading drop:
--   ALTER TABLE price_catalog.product_prices
--       DROP COLUMN size_unit_declared, DROP COLUMN sqm_provenance, DROP COLUMN thickness_unit_declared;
--   ALTER TABLE price_catalog.product_price_staging
--       DROP COLUMN size_unit_declared, DROP COLUMN sqm_provenance, DROP COLUMN sqm_per_linear_m,
--       DROP COLUMN thickness_unit_declared;
-- (as a NEW forward-only migration -- never edit this file in place once applied.)
