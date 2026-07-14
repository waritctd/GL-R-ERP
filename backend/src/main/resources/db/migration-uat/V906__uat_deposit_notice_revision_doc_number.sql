-- Keep V903 immutable for Flyway checksum validation. This forward-only
-- correction gives the seeded UAT revision its own document number so later
-- deposit_notice integrity constraints can enforce globally unique doc_number.

SET search_path = sales, hr, public;

UPDATE sales.deposit_notice dn
SET doc_number = 'QN-2026-00003'
FROM sales.ticket t
WHERE dn.ticket_id = t.ticket_id
  AND t.code = 'UAT-TKT-04'
  AND dn.version = 2
  AND dn.doc_number = 'QN-2026-00001'
  AND NOT EXISTS (
      SELECT 1
      FROM sales.deposit_notice existing
      WHERE existing.doc_number = 'QN-2026-00003'
  );
