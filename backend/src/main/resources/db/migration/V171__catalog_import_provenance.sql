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
--
-- ── Extended in place AGAIN, 2026-09-12, owner-supplied thickness sources for the three "none"
-- factories (Bode/Vives/Equipe) ────────────────────────────────────────────────────────────────
-- Adds thickness_provenance + thickness_note, the thickness twin of sqm_provenance's shape: HOW a
-- row's thickness_mm was obtained, or why it is NULL, now that all three sources declared "none"
-- above actually have owner-supplied thickness data (or a ruling to use one anyway):
--   * Equipe  -- a genuine per-row thickness column, appended to the same price-list rows. Its
--     thicknessUnit moves from "none" to "mm" (the data is now real and self-describing --
--     "Thickness (mm)"); 36 rows hold a RANGE ("9.5–19.5", EN DASH) instead of a single number --
--     owner ruling "เก็บค่าน้อยสุด (9.5)" -- the MINIMUM is stored in thickness_mm and the original
--     range text is preserved in thickness_note, never discarded.
--   * Vives   -- thickness lives in a SEPARATE sidecar workbook, joined on a declared composite key
--     (CODIGO, MODELO). Only 890 of 4,617 keys carry a value; the rest import thickness_mm = NULL
--     (a genuine, owner-confirmed absence -- "not published" or "not found on current catalogue" --
--     never defaulted).
--   * Bode    -- still has NO usable per-row thickness anywhere. Owner ruling ("ตั้งค่าตามที่ได้ไปก่อน
--     เดี๋ยวเซลแก้เองถ้าผิด") sets a PROFILE-LEVEL default of 9mm. A defaulted value MUST be
--     distinguishable from a stated one -- that is the entire reason thickness_provenance exists
--     rather than just widening thickness_unit_declared -- so a sales rep correcting the quotation
--     can tell which rows are real and which are the profile's guess.
--
-- thickness_provenance is deliberately populated for EVERY freshly-imported row (like
-- sqm_provenance), including a genuine, recorded absence ('absent') -- never left NULL except on
-- rows imported before this column existed. thickness_note is free text (no CHECK), mirroring
-- quarantine_reason/import_error's role elsewhere in this same file: it carries Equipe's per-row
-- verification status text, an Equipe range's original wording, a Vives sidecar's "why blank"
-- reason, or the Bode default's own explanation -- never structured, always just kept.
--
-- THIS FILE STILL HAS NEVER BEEN APPLIED ANYWHERE -- see the header above; nothing here has run in
-- any real database, so extending V171 a second time is not the "never edit an applied migration"
-- violation either, for the identical reason already given once in this file. Once any version in
-- this still-unreleased batch actually runs somewhere, this must become a new forward-only
-- migration instead -- do not repeat this pattern after that point.
--
-- Purely additive again: thickness_provenance (CHECK-constrained, same shape as thickness_unit_
-- declared/sqm_provenance) and thickness_note (free TEXT) on both tables. No backfill -- existing
-- rows read NULL for both until re-imported.

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
        CHECK (thickness_unit_declared IN ('mm', 'cm', 'none')),
    ADD COLUMN thickness_provenance TEXT
        CHECK (thickness_provenance IN (
            'stated',           -- a real thickness value existed in the source for this row (a
                                 -- dedicated column, a self-describing "9MM" token, a size-embedded
                                 -- bare 3rd value, or an Equipe range collapsed to its minimum --
                                 -- see thickness_note for the original range text when it applies)
            'sidecar_resolved', -- resolved by joining a separate thickness workbook on a declared
                                 -- composite key (Vives: CODIGO+MODELO) -- see ImportProfile#
                                 -- thicknessSidecar
            'profile_default',  -- no per-row thickness existed anywhere; ImportProfile#
                                 -- defaultThicknessMm was applied (Bode: 9mm) -- MUST be
                                 -- distinguishable from 'stated' so a sales rep can find and correct
                                 -- it on the quotation
            'absent'            -- no thickness value exists for this row from any source (no
                                 -- column/embedded value, no sidecar hit, no profile default) --
                                 -- thickness_mm is NULL, and that is a recorded, deliberate absence
        )),
    ADD COLUMN thickness_note TEXT;

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

COMMENT ON COLUMN price_catalog.product_prices.thickness_provenance IS
    'How thickness_mm on this row was obtained, or why it is NULL. See ImportEngine''s '
    'PriceRow#thicknessProvenance javadoc for the full decision table. NULL on any row imported '
    'before this column was added (no backfill was performed).';

COMMENT ON COLUMN price_catalog.product_prices.thickness_note IS
    'Free-text detail accompanying thickness_provenance: Equipe''s per-row verification status '
    '("Verified / matched" / "Best-effort / verify" / "Verified, source conflict") and, for a row '
    'whose source held a RANGE, the original range text (the numeric thickness_mm is always the '
    'range''s minimum, per owner ruling); a Vives sidecar''s stated reason for a blank thickness '
    '("Not published..." / "Not found..."); or the Bode profile-default''s own explanation. Never '
    'structured -- read thickness_provenance for that.';

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
        CHECK (thickness_unit_declared IN ('mm', 'cm', 'none')),
    ADD COLUMN thickness_provenance TEXT
        CHECK (thickness_provenance IN (
            'stated', 'sidecar_resolved', 'profile_default', 'absent'
        )),
    ADD COLUMN thickness_note TEXT;

COMMENT ON COLUMN price_catalog.product_price_staging.sqm_per_linear_m IS
    'Staging never got this column when V153 added it to product_prices. Carries the per-import '
    'per_linear_m coverage figure (see product_prices.sqm_per_linear_m''s own comment) from parse '
    'through to commit -- PriceImportService''s commit() INSERT...SELECT now includes it.';

-- Rollback: DROP the ten new columns (five on each table) added above. All are nullable with no
-- other object depending on them, so this is a plain, non-cascading drop:
--   ALTER TABLE price_catalog.product_prices
--       DROP COLUMN size_unit_declared, DROP COLUMN sqm_provenance, DROP COLUMN thickness_unit_declared,
--       DROP COLUMN thickness_provenance, DROP COLUMN thickness_note;
--   ALTER TABLE price_catalog.product_price_staging
--       DROP COLUMN size_unit_declared, DROP COLUMN sqm_provenance, DROP COLUMN sqm_per_linear_m,
--       DROP COLUMN thickness_unit_declared, DROP COLUMN thickness_provenance, DROP COLUMN thickness_note;
-- (as a NEW forward-only migration -- never edit this file in place once applied.)
