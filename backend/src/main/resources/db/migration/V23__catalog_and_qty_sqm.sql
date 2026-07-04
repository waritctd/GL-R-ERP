-- R2: Product catalog + qty_sqm on ticket_item

-- Catalog: master product list for autocomplete & sqm-per-piece lookup
CREATE TABLE sales.catalog (
    catalog_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    brand         VARCHAR(200) NOT NULL,
    collection    VARCHAR(200),           -- model / รุ่น
    color         VARCHAR(100),
    surface       VARCHAR(100),           -- เนื้อผิว
    size          VARCHAR(80),
    factory       VARCHAR(200),
    sqm_per_piece NUMERIC(8,6),           -- ตร.ม. ต่อแผ่น (for pcs↔sqm conversion)
    notes         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_catalog_brand   ON sales.catalog(brand);
CREATE INDEX idx_catalog_factory ON sales.catalog(factory);

-- Demo seed (SCG / Cotto / Duragres representative products)
INSERT INTO sales.catalog (brand, collection, color, surface, size, factory, sqm_per_piece) VALUES
-- SCG Ceramics
('SCG', 'Elegance Series',  'ขาวนวล',     'ด้าน',     '60x60 ซม.',  'SCG Ceramics',     0.360000),
('SCG', 'Elegance Series',  'เทาอ่อน',    'ด้าน',     '60x60 ซม.',  'SCG Ceramics',     0.360000),
('SCG', 'Natura Collection','เบจธรรมชาติ','หยาบ',     '30x60 ซม.',  'SCG Ceramics',     0.180000),
('SCG', 'Natura Collection','น้ำตาลไม้',   'หยาบ',    '20x100 ซม.', 'SCG Ceramics',     0.200000),
('SCG', 'Crystal White',    'ขาวมุก',     'มัน',      '60x120 ซม.', 'SCG Ceramics',     0.720000),
-- Cotto
('Cotto','Metro Square',    'ขาว',        'ด้าน',     '30x30 ซม.',  'Cotto Industry',   0.090000),
('Cotto','Metro Square',    'ครีม',       'ด้าน',     '30x30 ซม.',  'Cotto Industry',   0.090000),
('Cotto','Stone Series',    'เทาเข้ม',    'หยาบ',     '60x60 ซม.',  'Cotto Industry',   0.360000),
('Cotto','Timber Line',     'น้ำตาลอ่อน', 'ลายไม้',  '20x120 ซม.', 'Cotto Industry',   0.240000),
-- Duragres
('Duragres','Granite Plus', 'เทากลาง',    'หยาบกึ่งมัน','60x60 ซม.','Duragres Thailand',0.360000),
('Duragres','Granite Plus', 'ดำ',         'หยาบกึ่งมัน','60x60 ซม.','Duragres Thailand',0.360000),
('Duragres','Porcelain Pro','ขาวเนียน',   'มัน',       '80x80 ซม.', 'Duragres Thailand',0.640000),
-- Panaria (Italian — used in R3/R4 for foreign factory demo)
('Panaria','Trilogy',       'Ivory',      'Lappato',   '60x120 cm',  'Panaria SpA',      0.720000),
('Panaria','Frame',         'Ash',        'Naturale',  '80x80 cm',   'Panaria SpA',      0.640000);

-- Add qty_sqm to ticket_item alongside existing qty (= pieces)
ALTER TABLE sales.ticket_item ADD COLUMN qty_sqm NUMERIC(12,4);
