-- Give UAT its own customer master, catalog and factory config.
--
-- V91 (db/migration) removes the sample customers/contacts/projects/catalog/factory_config that
-- V16/V23/V24/V25 seeded into every environment, because invented data has no business reaching real
-- production. On the hosted UAT database those four V16 customers were, as it happens, the ONLY rows
-- in customers.customer -- and nothing referenced them (all 13 DEAL-UAT-* tickets carry a free-text
-- customer_name with customer_id/project_id/contact_id NULL, and none of the 4 pricing_requests
-- carries a recipient_contact_id). So every guard in V91 passes and UAT is left with an empty
-- customer master: no customer picker, no project or contact picker, and no tax ID / address for
-- quotation auto-fill to pull.
--
-- Nothing errors -- the deals do not use those FKs -- but UAT stops being able to exercise the
-- flows it exists to exercise. This file restores that capability with data that is unmistakably
-- UAT's own, and runs after V91 on both the existing database and any future rebuild.
--
-- Two deliberate choices:
--
--   * Customer names mirror the 13 DEAL-UAT-* tickets' customer_name values, so a tester can pick
--     the customer that matches the deal they are looking at. Ticket FKs are deliberately NOT
--     back-filled here -- that is deal data, and rewriting it is a separate decision.
--   * factory_config carries @uat.glr email addresses, NEVER a real supplier's. That column is the
--     destination for factory-quote dispatch (see the V64/V67 outbox worker): a real address here
--     means a UAT test run can email an actual factory. The factory NAMES mirror
--     price_catalog.factories so the pricing chain lines up with the 9 real price lists already
--     loaded on UAT; only the contact details are synthetic.
--
-- Tax IDs use an obviously-synthetic 0999-prefix block so no UAT row can ever be mistaken for a
-- real customer record. Every INSERT is guarded, so this is idempotent and safe to re-run.

INSERT INTO customers.customer (name, tax_id, address, branch, phone)
SELECT v.name, v.tax_id, v.address, v.branch, v.phone
  FROM (VALUES
        ('บริษัท สยามคอนสตรัคชั่น จำกัด',      '0999000000001', '1 ถนนทดสอบ แขวงยูเอที เขตยูเอที กรุงเทพฯ 10110', 'สำนักงานใหญ่', '02-900-0001'),
        ('โครงการ เดอะ ริเวอร์ เรสซิเดนซ์',    '0999000000002', '2 ถนนทดสอบ แขวงยูเอที เขตยูเอที กรุงเทพฯ 10110', 'สำนักงานใหญ่', '02-900-0002'),
        ('หจก. ไทยเดคคอร์',                    '0999000000003', '3 ถนนทดสอบ แขวงยูเอที เขตยูเอที กรุงเทพฯ 10110', 'สำนักงานใหญ่', '02-900-0003'),
        ('บริษัท เกรทวอลล์ อินทีเรีย',         '0999000000004', '4 ถนนทดสอบ แขวงยูเอที เขตยูเอที กรุงเทพฯ 10110', 'สำนักงานใหญ่', '02-900-0004'),
        ('โรงแรม บ้านริมทะเล',                 '0999000000005', '5 ถนนทดสอบ แขวงยูเอที เขตยูเอที กรุงเทพฯ 10110', 'สำนักงานใหญ่', '02-900-0005'),
        ('บริษัท ลักซ์ชัวรี่ ลิฟวิ่ง',          '0999000000006', '6 ถนนทดสอบ แขวงยูเอที เขตยูเอที กรุงเทพฯ 10110', 'สำนักงานใหญ่', '02-900-0006'),
        ('โครงการ สกาย ทาวเวอร์',              '0999000000007', '7 ถนนทดสอบ แขวงยูเอที เขตยูเอที กรุงเทพฯ 10110', 'สำนักงานใหญ่', '02-900-0007'),
        ('บริษัท มารีน่า เบย์',                 '0999000000008', '8 ถนนทดสอบ แขวงยูเอที เขตยูเอที กรุงเทพฯ 10110', 'สำนักงานใหญ่', '02-900-0008'),
        ('หจก. บ้านสวยการช่าง',                '0999000000009', '9 ถนนทดสอบ แขวงยูเอที เขตยูเอที กรุงเทพฯ 10110', 'สำนักงานใหญ่', '02-900-0009'),
        ('บริษัท ซันไชน์ พร็อพเพอร์ตี้',        '0999000000010', '10 ถนนทดสอบ แขวงยูเอที เขตยูเอที กรุงเทพฯ 10110', 'สำนักงานใหญ่', '02-900-0010'),
        ('โครงการ เดอะ การ์เด้น วิลล่า',        '0999000000011', '11 ถนนทดสอบ แขวงยูเอที เขตยูเอที กรุงเทพฯ 10110', 'สำนักงานใหญ่', '02-900-0011'),
        ('บริษัท เมโทร ดีไซน์',                 '0999000000012', '12 ถนนทดสอบ แขวงยูเอที เขตยูเอที กรุงเทพฯ 10110', 'สำนักงานใหญ่', '02-900-0012'),
        ('หจก. บิ๊กโฮม เฟอร์นิเจอร์',           '0999000000013', '13 ถนนทดสอบ แขวงยูเอที เขตยูเอที กรุงเทพฯ 10110', 'สำนักงานใหญ่', '02-900-0013')
       ) AS v(name, tax_id, address, branch, phone)
 WHERE NOT EXISTS (SELECT 1 FROM customers.customer c WHERE c.tax_id = v.tax_id);

-- Contacts on a handful of them: enough to exercise the recipient picker and the OWNER/DESIGNER
-- pricing-request paths without pretending every customer has a full contact book.
INSERT INTO customers.contact (customer_id, first_name, last_name, position, email, phone)
SELECT c.customer_id, v.first_name, v.last_name, v.position, v.email, v.phone
  FROM (VALUES
        ('0999000000001', 'ทดสอบ',  'จัดซื้อ',     'ฝ่ายจัดซื้อ',              'uat.purchasing@uat.glr', '081-900-0001'),
        ('0999000000002', 'ทดสอบ',  'เจ้าของงาน',  'เจ้าของโครงการ (Owner)',   'uat.owner@uat.glr',      '081-900-0002'),
        ('0999000000004', 'ทดสอบ',  'ออกแบบ',      'ผู้ออกแบบ (Designer)',     'uat.designer@uat.glr',   '081-900-0004'),
        ('0999000000007', 'ทดสอบ',  'ผู้จัดการ',    'ผู้จัดการโครงการ',         'uat.pm@uat.glr',         '081-900-0007')
       ) AS v(tax_id, first_name, last_name, position, email, phone)
  JOIN customers.customer c ON c.tax_id = v.tax_id
 WHERE NOT EXISTS (SELECT 1 FROM customers.contact x WHERE x.email = v.email);

INSERT INTO customers.project (customer_id, name)
SELECT c.customer_id, v.name
  FROM (VALUES
        ('0999000000001', 'โครงการทดสอบ UAT เฟส 1'),
        ('0999000000002', 'โครงการทดสอบ UAT ริเวอร์ไซด์'),
        ('0999000000004', 'โครงการทดสอบ UAT อินทีเรีย'),
        ('0999000000007', 'โครงการทดสอบ UAT สกายทาวเวอร์')
       ) AS v(tax_id, name)
  JOIN customers.customer c ON c.tax_id = v.tax_id
 WHERE NOT EXISTS (SELECT 1 FROM customers.project x WHERE x.name = v.name);

-- Factory names mirror price_catalog.factories (the 9 real price lists loaded on UAT) so the
-- pricing chain resolves; contact emails are @uat.glr so a UAT dispatch can never reach a real
-- supplier.
INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
SELECT v.factory_name, v.email, v.currency, v.unit, v.country
  FROM (VALUES
        ('Panaria', 'uat.panaria@uat.glr', 'EUR', 'sqm', 'Italy'),
        ('LEA',     'uat.lea@uat.glr',     'EUR', 'sqm', 'Italy'),
        ('Padana',  'uat.padana@uat.glr',  'EUR', 'sqm', 'Italy'),
        ('CDE',     'uat.cde@uat.glr',     'EUR', 'sqm', 'Italy'),
        ('CITY',    'uat.city@uat.glr',    'EUR', 'sqm', 'Italy'),
        ('REFIN',   'uat.refin@uat.glr',   'EUR', 'sqm', 'Italy'),
        ('Equipe',  'uat.equipe@uat.glr',  'EUR', 'sqm', 'Spain'),
        ('Vives',   'uat.vives@uat.glr',   'EUR', 'sqm', 'Spain'),
        ('Bode',    'uat.bode@uat.glr',    'USD', 'sqm', 'China')
       ) AS v(factory_name, email, currency, unit, country)
 WHERE NOT EXISTS (
        SELECT 1 FROM sales.factory_config f WHERE f.factory_name = v.factory_name);

INSERT INTO sales.catalog (brand, collection, color, surface, size, factory, sqm_per_piece)
SELECT v.brand, v.collection, v.color, v.surface, v.size, v.factory, v.sqm_per_piece::numeric
  FROM (VALUES
        ('Panaria', 'UAT Trilogy',   'Ivory',  'Lappato',  '60x120 cm', 'Panaria', 0.720000),
        ('Panaria', 'UAT Frame',     'Ash',    'Naturale', '80x80 cm',  'Panaria', 0.640000),
        ('LEA',     'UAT Slimtech',  'Grey',   'Natural',  '60x60 cm',  'LEA',     0.360000),
        ('Padana',  'UAT Cover',     'White',  'Matt',     '60x60 cm',  'Padana',  0.360000),
        ('Equipe',  'UAT Metro',     'Cream',  'Glossy',   '7.5x15 cm', 'Equipe',  0.011250),
        ('Bode',    'UAT Porcelain', 'Beige',  'Polished', '80x80 cm',  'Bode',    0.640000)
       ) AS v(brand, collection, color, surface, size, factory, sqm_per_piece)
 WHERE NOT EXISTS (
        SELECT 1
          FROM sales.catalog c
         WHERE c.brand = v.brand
           AND c.collection = v.collection
           AND c.color = v.color
           AND c.size = v.size);
