-- Move price catalog tables from sales → price_catalog schema.
-- PostgreSQL maintains FK constraints by OID, so all relationships remain valid after the move.
-- DROP IF EXISTS handles local dev environments where price_catalog tables may already exist
-- from a previous experimental catalog Flyway run.

CREATE SCHEMA IF NOT EXISTS price_catalog;

DROP TABLE IF EXISTS price_catalog.product_price_staging;
DROP TABLE IF EXISTS price_catalog.product_prices;
DROP TABLE IF EXISTS price_catalog.price_list_versions;
DROP TABLE IF EXISTS price_catalog.import_profiles;
DROP TABLE IF EXISTS price_catalog.factories;
DROP TABLE IF EXISTS price_catalog.flyway_catalog_history;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='sales' AND table_name='factories') THEN
    ALTER TABLE sales.factories SET SCHEMA price_catalog;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='sales' AND table_name='import_profiles') THEN
    ALTER TABLE sales.import_profiles SET SCHEMA price_catalog;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='sales' AND table_name='price_list_versions') THEN
    ALTER TABLE sales.price_list_versions SET SCHEMA price_catalog;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='sales' AND table_name='product_prices') THEN
    ALTER TABLE sales.product_prices SET SCHEMA price_catalog;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='sales' AND table_name='product_price_staging') THEN
    ALTER TABLE sales.product_price_staging SET SCHEMA price_catalog;
  END IF;
END $$;
