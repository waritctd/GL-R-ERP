-- Put the sample fixtures back, for demo environments only.
--
-- V91 (db/migration) deletes the customers, contacts, projects, catalog and factory_config rows that
-- V16/V23/V24/V25 seed, because those migrations run on REAL production and invented data has no
-- business being there. The Render showcase, however, is exactly the environment those fixtures were
-- written for: it browses the catalog, opens the DEMO-TKT-* deals and generates quotations that
-- auto-fill a customer's tax ID and address.
--
-- This file lives in db/migration-demo, which only the demo profile loads (application-demo.yml,
-- plus SPRING_FLYWAY_LOCATIONS in render.yaml), so real production never sees it. Numbered V91.1 so
-- Flyway orders it immediately after V91 -- version ordering is global across all configured
-- locations, not per folder, which is what makes a minor version the right tool here (same
-- convention as the existing V11.1 / V11.2).
--
-- Every INSERT is an anti-join on the seed's natural key, so this is idempotent and it does not
-- duplicate the rows V91's NOT EXISTS guards deliberately spared.

INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
SELECT v.factory_name, v.email, v.currency, v.unit, v.country
  FROM (VALUES
        ('SCG Ceramics',      'sales@scg.co.th',      'THB', 'piece', 'Thailand'),
        ('Cotto Industry',    'orders@cotto.co.th',   'THB', 'piece', 'Thailand'),
        ('Duragres Thailand', 'sales@duragres.co.th', 'THB', 'piece', 'Thailand'),
        ('Panaria SpA',       'export@panaria.it',    'EUR', 'sqm',   'Italy')
       ) AS v(factory_name, email, currency, unit, country)
 WHERE NOT EXISTS (
        SELECT 1 FROM sales.factory_config f WHERE f.factory_name = v.factory_name);

INSERT INTO sales.catalog (brand, collection, color, surface, size, factory, sqm_per_piece)
SELECT v.brand, v.collection, v.color, v.surface, v.size, v.factory, v.sqm_per_piece::numeric
  FROM (VALUES
        ('SCG',      'Elegance Series',   'ขาวนวล',      'ด้าน',        '60x60 ซม.',  'SCG Ceramics',      0.360000),
        ('SCG',      'Elegance Series',   'เทาอ่อน',     'ด้าน',        '60x60 ซม.',  'SCG Ceramics',      0.360000),
        ('SCG',      'Natura Collection', 'เบจธรรมชาติ', 'หยาบ',        '30x60 ซม.',  'SCG Ceramics',      0.180000),
        ('SCG',      'Natura Collection', 'น้ำตาลไม้',    'หยาบ',        '20x100 ซม.', 'SCG Ceramics',      0.200000),
        ('SCG',      'Crystal White',     'ขาวมุก',      'มัน',         '60x120 ซม.', 'SCG Ceramics',      0.720000),
        ('Cotto',    'Metro Square',      'ขาว',         'ด้าน',        '30x30 ซม.',  'Cotto Industry',    0.090000),
        ('Cotto',    'Metro Square',      'ครีม',        'ด้าน',        '30x30 ซม.',  'Cotto Industry',    0.090000),
        ('Cotto',    'Stone Series',      'เทาเข้ม',     'หยาบ',        '60x60 ซม.',  'Cotto Industry',    0.360000),
        ('Cotto',    'Timber Line',       'น้ำตาลอ่อน',  'ลายไม้',      '20x120 ซม.', 'Cotto Industry',    0.240000),
        ('Duragres', 'Granite Plus',      'เทากลาง',     'หยาบกึ่งมัน', '60x60 ซม.',  'Duragres Thailand', 0.360000),
        ('Duragres', 'Granite Plus',      'ดำ',          'หยาบกึ่งมัน', '60x60 ซม.',  'Duragres Thailand', 0.360000),
        ('Duragres', 'Porcelain Pro',     'ขาวเนียน',    'มัน',         '80x80 ซม.',  'Duragres Thailand', 0.640000),
        ('Panaria',  'Trilogy',           'Ivory',       'Lappato',     '60x120 cm',  'Panaria SpA',       0.720000),
        ('Panaria',  'Frame',             'Ash',         'Naturale',    '80x80 cm',   'Panaria SpA',       0.640000)
       ) AS v(brand, collection, color, surface, size, factory, sqm_per_piece)
 WHERE NOT EXISTS (
        SELECT 1
          FROM sales.catalog c
         WHERE c.brand = v.brand
           AND c.collection = v.collection
           AND c.color = v.color
           AND c.size = v.size);

INSERT INTO customers.customer (name, tax_id, address, branch, phone)
SELECT v.name, v.tax_id, v.address, v.branch, v.phone
  FROM (VALUES
        ('บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด',  '0105565012345', '123 ถนนสุขุมวิท แขวงคลองเตย เขตคลองเตย กรุงเทพฯ 10110', 'สำนักงานใหญ่', '02-123-4567'),
        ('บริษัท ไทยแลนด์ ดีเวลลอปเมนท์ จำกัด', '0105556789012', '456 ถนนรัชดาภิเษก แขวงลาดยาว เขตจตุจักร กรุงเทพฯ 10900',  'สำนักงานใหญ่', '02-234-5678'),
        ('บริษัท พรีเมียม ดีไซน์ กรุ๊ป จำกัด',   '0105578901234', '789 ถนนพระราม 4 แขวงพระโขนง เขตคลองเตย กรุงเทพฯ 10260',  'สำนักงานใหญ่', '02-345-6789'),
        ('บริษัท เรืองแสง พร็อพเพอร์ตี้ จำกัด',  '0105591234567', '321 ถนนนวมินทร์ แขวงคลองกุ่ม เขตบึงกุ่ม กรุงเทพฯ 10240', 'สำนักงานใหญ่', '02-456-7890')
       ) AS v(name, tax_id, address, branch, phone)
 WHERE NOT EXISTS (SELECT 1 FROM customers.customer c WHERE c.tax_id = v.tax_id);

-- Children re-attach by their parent's tax ID, not by a hard-coded id: a customer V91 deleted and
-- this file re-inserted comes back with a fresh identity value.
INSERT INTO customers.contact (customer_id, first_name, last_name, position, email, phone)
SELECT c.customer_id, v.first_name, v.last_name, v.position, v.email, v.phone
  FROM (VALUES
        ('0105565012345', 'วิภา',   'สมิทธ์',   'ผู้จัดการโครงการ', 'wipa@kaona.co.th',      '081-111-2222'),
        ('0105565012345', 'ธนพล',   'อภิชัย',   'วิศวกรโยธา',       'thanaphon@kaona.co.th', '082-333-4444'),
        ('0105556789012', 'ปรีชา',  'วงศ์สกุล', 'จัดซื้อ',          'preecha@tld.co.th',     '083-555-6666'),
        ('0105578901234', 'สุภาพร', 'ทองดี',    'ผู้อำนวยการ',      'supaporn@pdg.co.th',    '084-777-8888'),
        ('0105591234567', 'กมล',    'เรืองศรี', 'ผู้จัดการ',        'kamol@rp.co.th',        '085-999-0000')
       ) AS v(tax_id, first_name, last_name, position, email, phone)
  JOIN customers.customer c ON c.tax_id = v.tax_id
 WHERE NOT EXISTS (SELECT 1 FROM customers.contact x WHERE x.email = v.email);

INSERT INTO customers.project (customer_id, name)
SELECT c.customer_id, v.name
  FROM (VALUES
        ('0105565012345', 'โครงการ Central Ladprao ชั้น B1'),
        ('0105565012345', 'โครงการ The Mall Bangkapi'),
        ('0105556789012', 'โครงการ Asoke Tower ชั้น 12-15'),
        ('0105578901234', 'โครงการ PDG HQ Renovation'),
        ('0105591234567', 'โครงการ Rueangchat Condo Phase 2')
       ) AS v(tax_id, name)
  JOIN customers.customer c ON c.tax_id = v.tax_id
 WHERE NOT EXISTS (SELECT 1 FROM customers.project x WHERE x.name = v.name);
