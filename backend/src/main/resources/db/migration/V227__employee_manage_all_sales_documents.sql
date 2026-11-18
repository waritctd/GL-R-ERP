-- Per-employee grant (Ploy ruling 2026-11-18): a holder acts as the OWNER of every deal's sales documents
-- (deal, quotations, deposit notice, remaining invoice) in addition to their own role. No data rows: set per person.
ALTER TABLE hr.employee ADD COLUMN IF NOT EXISTS can_manage_all_sales_documents BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN hr.employee.can_manage_all_sales_documents IS 'Acts as owner of every deal''s sales documents (deals, quotations, deposit notices, remaining invoices); commission, notifications and dashboards stay owner-only.';
