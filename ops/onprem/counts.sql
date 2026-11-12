-- Exact row count of every base table in the app schemas, one "schema.table|count" per line.
-- Run with psql -At against Supabase and the restored on-prem DB, then diff the two outputs.
-- ORDER BY uses the C collation so both servers sort identically regardless of their locale.
SELECT format('%s.%s|%s', table_schema, table_name,
         (xpath('/row/c/text()',
                query_to_xml(format('SELECT count(*) AS c FROM %I.%I', table_schema, table_name),
                             false, true, '')))[1]::text)
FROM information_schema.tables
WHERE table_type = 'BASE TABLE'
  AND table_schema IN ('hr', 'hr_restricted', 'sales', 'customers', 'price_catalog')
ORDER BY table_schema::text COLLATE "C", table_name::text COLLATE "C";
