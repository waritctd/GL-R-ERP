-- C1: Catalog import schema
-- factories + import_profiles + price_list_versions + product_prices + staging
-- Seed: 9 โรงงาน พร้อม profile JSON จาก docs/Catalouge/factory_profiles.json

-- ── 1. factories ─────────────────────────────────────────────────────────────
CREATE TABLE sales.factories (
    factory_id      BIGSERIAL PRIMARY KEY,
    name            TEXT UNIQUE NOT NULL,          -- Panaria, Padana, Vives …
    country         CHAR(2),                       -- ISO 3166-1 alpha-2: IT, ES, CN
    default_currency CHAR(3) NOT NULL DEFAULT 'EUR'
);

-- ── 2. import_profiles ───────────────────────────────────────────────────────
-- config JSONB stores sheet/column mapping; แก้ผ่าน GUI ได้ ไม่ต้อง deploy ใหม่
CREATE TABLE sales.import_profiles (
    profile_id      BIGSERIAL PRIMARY KEY,
    factory_id      BIGINT NOT NULL REFERENCES sales.factories(factory_id),
    config          JSONB NOT NULL,
    updated_by      BIGINT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_profile_factory UNIQUE (factory_id)   -- 1 active profile per factory
);

-- ── 3. price_list_versions ───────────────────────────────────────────────────
CREATE TABLE sales.price_list_versions (
    version_id      BIGSERIAL PRIMARY KEY,
    factory_id      BIGINT NOT NULL REFERENCES sales.factories(factory_id),
    label           TEXT,                          -- "Euro 2026"
    source_file     TEXT,
    effective_from  DATE,
    status          TEXT NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),
    uploaded_by     BIGINT,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_count       INT,
    error_count     INT
);

CREATE INDEX idx_plv_factory ON sales.price_list_versions(factory_id, status);

-- ── 4. product_prices (ตารางหลักที่ Sales ใช้) ───────────────────────────────
CREATE TABLE sales.product_prices (
    price_id        BIGSERIAL PRIMARY KEY,
    factory_id      BIGINT NOT NULL REFERENCES sales.factories(factory_id),
    version_id      BIGINT NOT NULL REFERENCES sales.price_list_versions(version_id),
    product_code    TEXT,                          -- NULL รองรับ REFIN Trim-Tiles
    grade           TEXT,                          -- Padana Scelta: A01 / A02
    collection      TEXT,
    product_name    TEXT,
    color           TEXT,
    surface         TEXT,
    size_raw        TEXT,                          -- เก็บค่าเดิมไว้เสมอ (audit)
    width_mm        NUMERIC,
    height_mm       NUMERIC,
    thickness_mm    NUMERIC,
    price           NUMERIC(14,4) NOT NULL,        -- ❗ NUMERIC ห้าม float
    currency        CHAR(3) NOT NULL,
    price_unit      TEXT NOT NULL                  -- per_sqm | per_piece | per_box | per_linear_m
                    CHECK (price_unit IN ('per_sqm','per_piece','per_box','per_linear_m','unknown')),
    sqm_per_piece   NUMERIC(10,6),                 -- คำนวณตอน import (sqm_per_box / pcs_per_box)
    pcs_per_box     NUMERIC,
    sqm_per_box     NUMERIC,
    kg_per_box      NUMERIC,
    price_variants  JSONB,                         -- Equipe: {pallet, picking, sueltas}
    attributes      JSONB,                         -- barcode, intrastat, etc.
    source_sheet    TEXT,
    source_row      INT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- unique key: version + code + grade + size + surface (NULLs treated as distinct)
    CONSTRAINT uq_price UNIQUE NULLS NOT DISTINCT
        (version_id, product_code, grade, size_raw, surface)
);

CREATE INDEX idx_pp_active  ON sales.product_prices(factory_id, version_id);
CREATE INDEX idx_pp_code    ON sales.product_prices(product_code);
CREATE INDEX idx_pp_search  ON sales.product_prices
    USING GIN (to_tsvector('simple',
        coalesce(collection,'') || ' ' ||
        coalesce(product_name,'') || ' ' ||
        coalesce(color,'') || ' ' ||
        coalesce(size_raw,'')
    ));

-- ── 5. staging table (ตรวจก่อน commit) ─────────────────────────────────────
-- ใช้ DEFAULTS เท่านั้น ไม่ copy UNIQUE/FK เพื่อให้แถวซ้ำในไฟล์ต้นฉาก insert ได้
-- (duplicate จะถูก detect และ mark ด้วย import_error ใน validate step)
CREATE TABLE sales.product_price_staging (
    LIKE sales.product_prices INCLUDING DEFAULTS
);
-- check constraints ยังต้องการ (price_unit enum)
ALTER TABLE sales.product_price_staging
    ADD CONSTRAINT stg_price_unit_check
        CHECK (price_unit IN ('per_sqm','per_piece','per_box','per_linear_m','unknown'));

-- session และ error columns
ALTER TABLE sales.product_price_staging
    ADD COLUMN import_session_id UUID,
    ADD COLUMN import_error      TEXT;           -- null = ผ่าน, มีข้อความ = error

CREATE INDEX idx_stg_version ON sales.product_price_staging(version_id);
CREATE INDEX idx_stg_session ON sales.product_price_staging(import_session_id);

-- ── 6. Seed: factories ───────────────────────────────────────────────────────
INSERT INTO sales.factories (name, country, default_currency) VALUES
    ('Panaria', 'IT', 'EUR'),
    ('LEA',     'IT', 'EUR'),
    ('Padana',  'IT', 'EUR'),
    ('CDE',     'IT', 'EUR'),
    ('CITY',    'IT', 'EUR'),
    ('REFIN',   'IT', 'EUR'),
    ('Equipe',  'ES', 'EUR'),
    ('Vives',   'ES', 'EUR'),
    ('Bode',    'CN', 'USD');

-- ── 7. Seed: import_profiles (จาก factory_profiles.json) ────────────────────

INSERT INTO sales.import_profiles (factory_id, config)
SELECT f.factory_id, p.config
FROM sales.factories f
JOIN (VALUES

  ('Panaria', $${
    "number_format": "eu",
    "sheets": [{"name": "PAN", "header_row": 2}],
    "columns": {
      "product_code": "CODICE ART",
      "collection":   "SERIE",
      "product_name": "DESCRIZIONE ART",
      "color":        "COLORE",
      "surface":      "BORDO",
      "size_raw":     "FORMATO",
      "thickness_mm": "SPESSORE",
      "price":        "PRZ_1SCE_EST",
      "currency":     "DIVISA",
      "unit":         "UM_VEN",
      "pcs_per_box":  "PZ_SCA",
      "sqm_per_box":  "MQ_SCA",
      "kg_per_box":   "KG_SCA",
      "barcode":      "BARCODE"
    },
    "notes": "header อยู่แถว 2 (แถว 1 เป็น title). มี DATA PRZ = วันที่ราคา"
  }$$::jsonb),

  ('LEA', $${
    "number_format": "eu",
    "sheets": [{"name": "LEA", "header_row": 1}],
    "columns": {
      "product_code": "CODICE ART",
      "collection":   "SERIE",
      "product_name": "DESCRIZIONE ART",
      "color":        "COLORE",
      "surface":      "BORDO",
      "size_raw":     "FORMATO",
      "thickness_mm": "SPESSORE",
      "price":        "Prezzo Lordo 1°",
      "unit":         "UMV",
      "pcs_per_box":  "PZ_SCA",
      "sqm_per_box":  "MQ_SCA",
      "kg_per_box":   "KG_SCA",
      "barcode":      "BARCODE"
    },
    "defaults": {"currency": "EUR"},
    "notes": "ไม่มีคอลัมน์สกุลเงิน -> ใช้ default EUR"
  }$$::jsonb),

  ('Padana', $${
    "number_format": "eu",
    "sheets": [{"name": "Sheet1", "header_row": 1}],
    "columns": {
      "product_code": "Articolo",
      "grade":        "Scelta",
      "collection":   "Descrizione Serie",
      "product_name": "Descrizione Articolo",
      "size_raw":     "Formato",
      "thickness_mm": "Spessore",
      "price":        "Prezzo",
      "unit":         "Unità",
      "pcs_per_box":  "PZ/SC",
      "sqm_per_box":  "MQ/SC",
      "kg_per_box":   "KG/SC",
      "barcode":      "EAN"
    },
    "defaults": {"currency": "EUR"},
    "notes": "Articolo ซ้ำ — ต้องใช้ (Articolo+Scelta) เป็น key. A01=เกรด1 A02=เกรด2 ราคาต่างกัน. stylesheet เสีย -> re-save ด้วย LibreOffice ก่อน"
  }$$::jsonb),

  ('CDE', $${
    "number_format": "eu",
    "sheets": [{"name": "LIST E1_E20 2026", "header_row": 1}],
    "columns": {
      "product_code": "ITEM ",
      "collection":   "RANGE",
      "product_name": "DESCRIPTION",
      "color":        "COLOR",
      "surface":      "FINISH ",
      "size_raw":     "SIZE",
      "thickness_mm": "THICKNESS MM",
      "price":        "PRICE ",
      "currency":     "DIVISA",
      "unit":         "UOM",
      "pcs_per_box":  "PCS_BOX",
      "sqm_per_box":  "SQM_BOX",
      "kg_per_box":   "KGS_BOX",
      "barcode":      "BARCODE"
    },
    "notes": "ชื่อคอลัมน์มีช่องว่างท้าย (ITEM , PRICE , FINISH ) -> engine ต้อง trim ตอน match header"
  }$$::jsonb),

  ('CITY', $${
    "number_format": "eu",
    "sheets": [
      {"name": "Contract Solutions", "header_row": 1},
      {"name": "Outdoor Solutions",  "header_row": 1}
    ],
    "columns": {
      "product_code": "Code",
      "collection":   "Collection",
      "product_name": "Item",
      "size_raw":     "Size (cm)",
      "thickness_mm": "Thick (mm)",
      "price":        "PRICE 2026",
      "unit":         "Um",
      "pcs_per_box":  "Pcs/Box",
      "sqm_per_box":  "m²/Box",
      "kg_per_box":   "Kg/Box"
    },
    "defaults": {"currency": "EUR"},
    "notes": "2 ชีต ใช้ mapping เดียวกันได้"
  }$$::jsonb),

  ('REFIN', $${
    "number_format": "eu",
    "sheets": [
      {"name": "Collections",        "header_row": 1},
      {"name": "RELIEFS_recap",      "header_row": 1},
      {"name": "Large-Slabs_recap",  "header_row": 1},
      {"name": "OUT2.0_recap",       "header_row": 1},
      {"name": "OUT2.0_accessories", "header_row": 1},
      {"name": "Trim-Tiles",         "header_row": 1},
      {"name": "Balneo-Project",     "header_row": 1}
    ],
    "columns": {
      "product_code": "Code",
      "collection":   "Collection",
      "product_name": "Item",
      "size_raw":     "Size (cm)",
      "thickness_mm": "Thickness (mm)",
      "price":        "PRICE 2025",
      "unit":         "Um",
      "pcs_per_box":  "Pcs/Box",
      "sqm_per_box":  "m²/Box",
      "kg_per_box":   "Kg/Box"
    },
    "defaults": {"currency": "EUR"},
    "column_aliases": {
      "product_code": ["Code", "CODE"],
      "collection":   ["Collection", "COLLECTION"],
      "product_name": ["Item", "ITEM"],
      "size_raw":     ["Size (cm)", "SIZE (cm)"],
      "surface":      ["SURFACE"],
      "price":        ["PRICE 2025", "PRICE 2025 ", "PRICE 2026"],
      "unit":         ["Um", "UM"]
    },
    "allow_missing_code": true,
    "fill_down_per_sheet": {
      "Balneo-Project": ["COLLECTION", "ITEM", "SIZE (cm)"]
    },
    "notes": "7 ชีต หัวตารางไม่เหมือนกัน. Trim-Tiles ไม่มีคอลัมน์ Code -> surrogate key. Balneo-Project มี merged cells -> fill-down เฉพาะชีตนี้. หัวเขียน PRICE 2025 แต่เป็นไฟล์ 2026 -> อย่า parse ปีจากชื่อคอลัมน์"
  }$$::jsonb),

  ('Equipe', $${
    "number_format": "eu",
    "sheets": [{"name": "EXTRACOMUNITARIOS", "header_row": 1}],
    "columns": {
      "product_code": "Artículo",
      "collection":   "Colección",
      "product_name": "Descripción",
      "unit":         "Unidad",
      "pcs_per_box":  "PZ / CJ",
      "sqm_per_box":  "M2 / CJ",
      "kg_per_box":   "KG / CJ",
      "barcode":      "EAN13"
    },
    "defaults": {"currency": "EUR"},
    "price_column_rule": {
      "type":         "choose",
      "options":      ["Precio Pallet", "Precio Picking", "Precio Sueltas"],
      "selected":     "Precio Pallet",
      "keep_all_as":  "price_variants"
    },
    "size_from": "product_name",
    "notes": "ราคา 3 คอลัมน์ CEO เลือกได้ เก็บที่เหลือใน price_variants. ขนาดต้อง parse จาก Descripción"
  }$$::jsonb),

  ('Vives', $${
    "number_format": "eu",
    "sheets": [{"name": "Hoja1", "header_row": 1}],
    "columns": {
      "product_code": "MODELO",
      "collection":   "SERIE",
      "product_name": "NOMBRE",
      "size_raw":     "FORMATO",
      "pcs_per_box":  "PIEZCAJA",
      "sqm_per_box":  "METROCAJ",
      "kg_per_box":   "PESOCAJA",
      "barcode":      "EAN"
    },
    "defaults": {"currency": "EUR"},
    "price_column_rule": {
      "type": "first_non_empty",
      "map":  {"PREPIEZA": "per_piece", "PREMETRO": "per_sqm"}
    },
    "size_format": "apostrophe_decimal",
    "notes": "ราคา PREPIEZA|PREMETRO มีอันใดอันหนึ่ง -> หน่วยตามคอลัมน์ที่มีค่า อย่าใช้ UMEDIDA. ขนาด ' = ทศนิยม: 15'8X31'6 = 15.8x31.6 cm"
  }$$::jsonb),

  ('Bode', $${
    "number_format": "us",
    "sheets": [{"name": "工作表1", "header_row": 11}],
    "columns": {
      "collection":   "series",
      "product_code": "code",
      "size_raw":     "size",
      "surface":      "finish",
      "price":        "USD/M2, FOB WITHOUT ORC/THC"
    },
    "defaults": {"unit": "per_sqm", "currency": "USD"},
    "fill_down":    ["series"],
    "split_column": {"code": ","},
    "notes": "Quotation sheet ไม่ใช่ price list มาตรฐาน. header แถว 11. series merged -> fill-down. 1 เซลล์ code มี 2 รหัสคั่น comma -> แตกเป็นหลายแถว. ราคา USD/M2 FOB"
  }$$::jsonb)

) AS p(factory_name, config) ON f.name = p.factory_name;
