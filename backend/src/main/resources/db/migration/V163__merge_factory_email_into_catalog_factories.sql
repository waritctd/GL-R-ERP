-- V163: merge the two unrelated "factory" tables that have only ever been joined by free-text name.
--
-- price_catalog.factories is the REAL 9 factories (Bode, CDE, CITY, Equipe, LEA, Padana, Panaria,
-- REFIN, Vives) -- the master-data table the catalog import/pricing engine keys everything off of
-- (factory_id BIGSERIAL PK, name TEXT UNIQUE NOT NULL, country CHAR(2) NOT NULL REFERENCES
-- price_catalog.country, default_currency CHAR(3) NOT NULL DEFAULT 'EUR' -- see V40, V42, V151).
--
-- sales.factory_config (V25) is a SEPARATE table -- the RFQ email directory -- linked to the real
-- factories only by matching sales.factory_config.factory_name = price_catalog.factories.name as
-- free text, with no foreign key between them at all. In production this join has ALWAYS matched
-- ZERO rows: sales.factory_config holds only the 4 DEMO fixtures re-seeded by
-- db/migration-demo/V91.1 (Cotto Industry, Duragres Thailand, Panaria SpA, SCG Ceramics), and none
-- of those names is one of the 9 real factories. FactoryQuoteService.generateDrafts resolves its
-- RFQ email recipient via factoryConfigs.findByName(factoryName) against this table, so emailTo
-- has been NULL for every factory-quote draft ever generated in production, and no factory RFQ has
-- ever actually been sendable there -- sales.factory_quote and sales.factory_quote_email_dispatch
-- are both 0 rows in prod. That 0%-name-match join is the defect this migration fixes.
--
-- Fix (owner decision): fold factory_config's email/unit columns onto price_catalog.factories --
-- the correct, singular master-data table for "a factory" -- and drop factory_config outright. The
-- copy below moves email/unit across ONLY where the names already match, which is zero rows in
-- production BY DESIGN: the 4 existing factory_config rows are demo fixtures, not real supplier
-- contacts, and are deliberately DISCARDED rather than migrated.
--
-- factory_config.notes is deliberately NOT carried across (review remediation): nothing on this
-- branch reads, writes, or exposes a notes field anywhere downstream -- FactoryConfigRepository's
-- SELECT_COLUMNS omits it and FactoryConfigDto has no such component -- so adding the column here
-- would be schema nothing can reach. If a real use for it turns up later, add it in its own
-- migration alongside the code that actually uses it.
--
-- Ordering on a FRESH database: db/migration-demo/V91.1 (version "91.1") inserts those 4 demo rows
-- into sales.factory_config, and this migration (version 163) runs strictly after it and drops the
-- table outright. Flyway applies db/migration and db/migration-demo as one ascending version
-- sequence (render.yaml wires migration-demo in via SPRING_FLYWAY_LOCATIONS), so 91.1 < 163 means
-- the demo insert always runs first and is then always dropped -- safe on a brand-new database, not
-- only on prod's already-migrated history.
--
-- sales.factory_quote_email_dispatch is DELIBERATELY NOT dropped here, even though this same change
-- makes factory RFQ email manual-only (FactoryQuoteService no longer enqueues or sends through it).
-- It is 0 rows in prod and genuinely unused from this point on, but dropping a table is a more
-- destructive, less reversible step than leaving an unused one behind, and this migration's job is
-- the factory-master-data merge -- not a dispatch-machinery cleanup. Drop it in a follow-up
-- migration once the manual-send change has been live for a while.

ALTER TABLE price_catalog.factories
    ADD COLUMN email VARCHAR(200),
    ADD COLUMN unit  VARCHAR(30) NOT NULL DEFAULT 'piece';

COMMENT ON COLUMN price_catalog.factories.email IS
    'RFQ contact email for this factory, folded in from sales.factory_config (V25, dropped by '
    'this migration). Optional -- factory RFQ email is manual-only (FactoryQuoteService.send just '
    'records that a human sent it), so a blank recipient is allowed and does not block a draft.';

COMMENT ON COLUMN price_catalog.factories.unit IS
    'Default quoting unit (piece, sqm or box) for this factory RFQ, folded in from '
    'sales.factory_config (V25, dropped by this migration).';

-- In production this UPDATE moves ZERO rows -- see header. Kept as a real join (not a no-op) so a
-- non-production database that happens to carry matching names does not silently lose data.
UPDATE price_catalog.factories f
   SET email = fc.email,
       unit  = fc.unit
  FROM sales.factory_config fc
 WHERE fc.factory_name = f.name;

DROP TABLE sales.factory_config;
