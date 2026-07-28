-- Remove the sample/demo fixtures that the CORE migrations seed into every environment.
--
-- The 2026-07-24 purge assumed all demo data came from db/migration-demo, and PR #306 stopped the
-- prod profile from loading that folder. That was only half the story: four migrations in
-- db/migration -- the folder EVERY environment runs, real production included -- carry INSERTs of
-- invented data, each with a comment that says so outright:
--
--   V16 "Sample customers for demo"                 -> 4 companies with made-up tax IDs
--   V23 "Demo contacts" / "Demo projects"           -> 5 contacts, 5 projects
--   V24 "Demo seed (SCG / Cotto / Duragres ...)"    -> 14 catalog tiles
--   V25 (unlabelled, same batch)                    -> 4 factory_config rows
--
-- Real production was cleaned by hand on 2026-07-28, but a rebuilt production database would
-- migrate straight back into the same 32 rows. This migration is what makes the cleanup stick.
--
-- FORWARD-ONLY. The INSERTs stay where they are: editing an applied migration changes its checksum
-- and breaks validation on every deployed database (see the V31/V32 collisions this repo has
-- already been bitten by). Undoing them here is the only safe direction.
--
-- Written to be correct in three different states:
--
--   * real production, already cleaned by hand -- every statement matches nothing, zero rows;
--   * a fresh production database -- the seed is present and unreferenced, so all of it goes;
--   * the demo showcase (SPRING_PROFILES_ACTIVE=prod,demo) -- V21 seeds DEMO-TKT-* tickets against
--     these customers and V32 links them by FK, so the NOT EXISTS guards below deliberately SPARE
--     any row the demo still points at, and db/migration-demo/V91.1 puts the rest back.
--
-- Rows are matched on the natural keys the seed wrote (tax_id, email, name), never on surrogate
-- ids. A real customer created later must not be deleted because it happens to occupy id 1.

-- No inbound foreign keys reference either of these two tables, so they need no guard.
DELETE FROM sales.factory_config
 WHERE (factory_name, email) IN (
    ('SCG Ceramics',      'sales@scg.co.th'),
    ('Cotto Industry',    'orders@cotto.co.th'),
    ('Duragres Thailand', 'sales@duragres.co.th'),
    ('Panaria SpA',       'export@panaria.it')
 );

DELETE FROM sales.catalog
 WHERE (brand, collection, color, size, factory) IN (
    ('SCG',      'Elegance Series',   'ขาวนวล',      '60x60 ซม.',  'SCG Ceramics'),
    ('SCG',      'Elegance Series',   'เทาอ่อน',     '60x60 ซม.',  'SCG Ceramics'),
    ('SCG',      'Natura Collection', 'เบจธรรมชาติ', '30x60 ซม.',  'SCG Ceramics'),
    ('SCG',      'Natura Collection', 'น้ำตาลไม้',    '20x100 ซม.', 'SCG Ceramics'),
    ('SCG',      'Crystal White',     'ขาวมุก',      '60x120 ซม.', 'SCG Ceramics'),
    ('Cotto',    'Metro Square',      'ขาว',         '30x30 ซม.',  'Cotto Industry'),
    ('Cotto',    'Metro Square',      'ครีม',        '30x30 ซม.',  'Cotto Industry'),
    ('Cotto',    'Stone Series',      'เทาเข้ม',     '60x60 ซม.',  'Cotto Industry'),
    ('Cotto',    'Timber Line',       'น้ำตาลอ่อน',  '20x120 ซม.', 'Cotto Industry'),
    ('Duragres', 'Granite Plus',      'เทากลาง',     '60x60 ซม.',  'Duragres Thailand'),
    ('Duragres', 'Granite Plus',      'ดำ',          '60x60 ซม.',  'Duragres Thailand'),
    ('Duragres', 'Porcelain Pro',     'ขาวเนียน',    '80x80 ซม.',  'Duragres Thailand'),
    ('Panaria',  'Trilogy',           'Ivory',       '60x120 cm',  'Panaria SpA'),
    ('Panaria',  'Frame',             'Ash',         '80x80 cm',   'Panaria SpA')
 );

-- Contacts before projects before customers: each is a parent of the next, and a surviving child
-- must be able to hold its parent back rather than trip an FK error mid-migration.
DELETE FROM customers.contact c
 WHERE c.email IN (
        'wipa@kaona.co.th',
        'thanaphon@kaona.co.th',
        'preecha@tld.co.th',
        'supaporn@pdg.co.th',
        'kamol@rp.co.th'
       )
   AND NOT EXISTS (SELECT 1 FROM sales.ticket t WHERE t.contact_id = c.contact_id)
   AND NOT EXISTS (
        SELECT 1 FROM sales.pricing_request p WHERE p.recipient_contact_id = c.contact_id);

DELETE FROM customers.project p
 WHERE p.name IN (
        'โครงการ Central Ladprao ชั้น B1',
        'โครงการ The Mall Bangkapi',
        'โครงการ Asoke Tower ชั้น 12-15',
        'โครงการ PDG HQ Renovation',
        'โครงการ Rueangchat Condo Phase 2'
       )
   AND NOT EXISTS (SELECT 1 FROM sales.ticket t WHERE t.project_id = p.project_id);

DELETE FROM customers.customer c
 WHERE c.tax_id IN (
        '0105565012345',
        '0105556789012',
        '0105578901234',
        '0105591234567'
       )
   AND NOT EXISTS (SELECT 1 FROM sales.ticket t WHERE t.customer_id = c.customer_id)
   -- A contact or project spared by the guards above keeps its customer alive too.
   AND NOT EXISTS (SELECT 1 FROM customers.contact x WHERE x.customer_id = c.customer_id)
   AND NOT EXISTS (SELECT 1 FROM customers.project x WHERE x.customer_id = c.customer_id);
