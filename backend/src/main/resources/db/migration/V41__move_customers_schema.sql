-- Move customer-related tables from sales to a dedicated customers schema.
-- PostgreSQL maintains FK constraints by table OID, so all existing FKs
-- (contact→customer, project→customer, ticket→customer/contact/project)
-- remain valid after the move without dropping and re-creating them.

CREATE SCHEMA IF NOT EXISTS customers;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='sales' AND table_name='customer') THEN
    ALTER TABLE sales.customer SET SCHEMA customers;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='sales' AND table_name='contact') THEN
    ALTER TABLE sales.contact SET SCHEMA customers;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='sales' AND table_name='project') THEN
    ALTER TABLE sales.project SET SCHEMA customers;
  END IF;
END $$;
