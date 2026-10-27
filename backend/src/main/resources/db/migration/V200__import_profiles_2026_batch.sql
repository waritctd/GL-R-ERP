-- V200: refresh catalogue import profiles for the 2026/2027 batch
-- (Cotto d'Este/CDE, LEA, Panaria, REFIN, Vives).
--
-- WHY: these 5 factories already carry import profiles seeded by V40 (Aug 2026), but (a) the
-- owner sent re-issued 2026/2027 price lists whose column headers changed, and (b) every one
-- of those seeds predates ImportProfile's now-REQUIRED size_unit/thickness_unit fields
-- (added by the 2026-09 catalogue-accuracy work), so ImportEngine#parse refuses them until
-- set. Each config below was validated header-by-header against the actual new workbook, and
-- every size_unit was cross-checked m2/box = (w x h) x pcs/box, never guessed. Replaces the
-- whole config per factory (not a partial patch) so the stored profile is complete and self-
-- consistent. Upsert on uq_profile_factory: a factory with no profile row (fresh env) gets one;
-- an existing one is overwritten. A factory name that does not exist updates nothing.
--
-- NOTE: the JSON below is dollar-quoted with the ANONYMOUS double-dollar tag (as V40 does), NOT
-- a named tag. Flyway runs placeholder replacement by default and does NOT skip NAMED-tag dollar
-- quotes: a named tag immediately followed by the JSON opening brace is then read as a Flyway
-- placeholder and the migration fails with "No value provided for placeholder". The anonymous
-- tag is skipped by the replacer, so it stays safe even when the JSON opens with a brace.

-- CDE: Cotto d'Este — columns unchanged from the 2026 list; adds the now-required size_unit/thickness_unit (mm, verified 150x600mm -> 0.09 x 2 = 0.18 m2/box).
INSERT INTO price_catalog.import_profiles (factory_id, config)
SELECT factory_id, $${
  "number_format": "eu",
  "size_unit": "mm",
  "thickness_unit": "mm",
  "sheets": [
    {
      "name": "LIST E1_E20 2026",
      "header_row": 1
    }
  ],
  "columns": {
    "product_code": "ITEM",
    "collection": "RANGE",
    "product_name": "DESCRIPTION",
    "color": "COLOR",
    "surface": "FINISH",
    "size_raw": "SIZE",
    "thickness_mm": "THICKNESS MM",
    "price": "PRICE",
    "currency": "DIVISA",
    "unit": "UOM",
    "pcs_per_box": "PCS_BOX",
    "sqm_per_box": "SQM_BOX",
    "kg_per_box": "KGS_BOX"
  }
}$$::jsonb
  FROM price_catalog.factories WHERE name = 'CDE'
ON CONFLICT ON CONSTRAINT uq_profile_factory
  DO UPDATE SET config = EXCLUDED.config, updated_at = now();

-- LEA: LEA — new headers: Thickness (was SPESSORE), Pieces/box (was PZ_SCA), 'm² per box' (was MQ_SCA), kg/box. size_unit mm (300x600mm -> 0.18 x 8 = 1.44).
INSERT INTO price_catalog.import_profiles (factory_id, config)
SELECT factory_id, $${
  "number_format": "eu",
  "size_unit": "mm",
  "thickness_unit": "mm",
  "sheets": [
    {
      "name": "LEA",
      "header_row": 1
    }
  ],
  "columns": {
    "product_code": "CODICE ART",
    "collection": "SERIE",
    "size_raw": "FORMATO",
    "color": "COLORE",
    "surface": "BORDO",
    "product_name": "DESCRIZIONE ART",
    "unit": "UMV",
    "thickness_mm": "Thickness",
    "price": "Prezzo Lordo 1°",
    "pcs_per_box": "Pieces/box",
    "sqm_per_box": "m² per box",
    "kg_per_box": "kg/box",
    "barcode": "BARCODE"
  }
}$$::jsonb
  FROM price_catalog.factories WHERE name = 'LEA'
ON CONFLICT ON CONSTRAINT uq_profile_factory
  DO UPDATE SET config = EXCLUDED.config, updated_at = now();

-- Panaria: Panaria — header still on row 2; Thickness (was SPESSORE), Pieces/box (was PZ_SCA); MQ_SCA/KG_SCA unchanged. size_unit mm (150x300mm -> 0.045 x 8 = 0.36).
INSERT INTO price_catalog.import_profiles (factory_id, config)
SELECT factory_id, $${
  "number_format": "eu",
  "size_unit": "mm",
  "thickness_unit": "mm",
  "sheets": [
    {
      "name": "PAN",
      "header_row": 2
    }
  ],
  "columns": {
    "product_code": "CODICE ART",
    "collection": "SERIE",
    "size_raw": "FORMATO",
    "color": "COLORE",
    "surface": "BORDO",
    "product_name": "DESCRIZIONE ART",
    "unit": "UM_VEN",
    "currency": "DIVISA",
    "thickness_mm": "Thickness",
    "price": "PRZ_1SCE_EST",
    "pcs_per_box": "Pieces/box",
    "sqm_per_box": "MQ_SCA",
    "kg_per_box": "KG_SCA"
  }
}$$::jsonb
  FROM price_catalog.factories WHERE name = 'Panaria'
ON CONFLICT ON CONSTRAINT uq_profile_factory
  DO UPDATE SET config = EXCLUDED.config, updated_at = now();

-- REFIN: REFIN — price column renamed to 'NEW PRICE 2027' (added to aliases); size_unit cm (header 'Size (cm)', 120x120cm -> 1.44 x 2 = 2.88); Trim-Tiles/accessories carry missing columns, handled by allow_missing_code + aliases exactly as before.
INSERT INTO price_catalog.import_profiles (factory_id, config)
SELECT factory_id, $${
  "number_format": "eu",
  "size_unit": "cm",
  "thickness_unit": "mm",
  "allow_missing_code": true,
  "sheets": [
    {
      "name": "Collections",
      "header_row": 1
    },
    {
      "name": "RELIEFS_recap",
      "header_row": 1
    },
    {
      "name": "Large-Slabs_recap",
      "header_row": 1
    },
    {
      "name": "OUT2.0_recap",
      "header_row": 1
    },
    {
      "name": "OUT2.0_accessories",
      "header_row": 1
    },
    {
      "name": "Trim-Tiles",
      "header_row": 1
    }
  ],
  "columns": {
    "product_code": "Code",
    "collection": "Collection",
    "product_name": "Item",
    "size_raw": "Size (cm)",
    "thickness_mm": "Thickness (mm)",
    "price": "NEW PRICE 2027",
    "unit": "Um",
    "pcs_per_box": "Pcs/Box",
    "sqm_per_box": "m²/Box",
    "kg_per_box": "Kg/Box"
  },
  "column_aliases": {
    "price": [
      "NEW PRICE 2027",
      "PRICE 2027",
      "PRICE 2026",
      "PRICE 2025"
    ],
    "product_code": [
      "Code",
      "CODE"
    ],
    "size_raw": [
      "Size (cm)",
      "SIZE (cm)"
    ],
    "product_name": [
      "Item",
      "ITEM"
    ],
    "collection": [
      "Collection",
      "COLLECTION"
    ],
    "unit": [
      "Um",
      "UM"
    ],
    "surface": [
      "SURFACE"
    ]
  }
}$$::jsonb
  FROM price_catalog.factories WHERE name = 'REFIN'
ON CONFLICT ON CONSTRAINT uq_profile_factory
  DO UPDATE SET config = EXCLUDED.config, updated_at = now();

-- Vives: Vives — price list re-issued with ENGLISH headers (Model/Name/Size/Pieces-Box/m²-Box/Weight-Box/EAN). Price is Price/Piece OR Price/m² (first_non_empty; cells are TEXT with comma decimals -> number_format eu). Size uses apostrophe decimal (15'8X31'6 = 15.8x31.6 cm), size_unit cm. No thickness in the main sheet (thickness_unit none); thickness joins from the SEPARATE workbook via Model<->Ref (value 'Thickness (mm)'). ~1964 of 4618 models match a sidecar Ref; the rest import thickness NULL, as the engine allows.
INSERT INTO price_catalog.import_profiles (factory_id, config)
SELECT factory_id, $${
  "number_format": "eu",
  "size_unit": "cm",
  "thickness_unit": "none",
  "size_format": "apostrophe_decimal",
  "sheets": [
    {
      "name": "Hoja1",
      "header_row": 1
    }
  ],
  "columns": {
    "product_code": "Model",
    "collection": "SERIE",
    "product_name": "Name",
    "size_raw": "Size",
    "unit": "Unit",
    "pcs_per_box": "Pieces/Box",
    "sqm_per_box": "m²/Box",
    "kg_per_box": "Weight/Box",
    "barcode": "EAN"
  },
  "price_column_rule": {
    "type": "first_non_empty",
    "map": {
      "Price/Piece": "per_piece",
      "Price/m²": "per_sqm"
    }
  },
  "thickness_sidecar": {
    "sheet": "Table1",
    "header_row": 1,
    "key_columns": [
      "Model"
    ],
    "sidecar_key_columns": [
      "Ref"
    ],
    "value_column": "Thickness (mm)"
  }
}$$::jsonb
  FROM price_catalog.factories WHERE name = 'Vives'
ON CONFLICT ON CONSTRAINT uq_profile_factory
  DO UPDATE SET config = EXCLUDED.config, updated_at = now();
