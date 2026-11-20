-- V238: per-employee read-everything grant for oversight people (QC, warehouse manager). A holder reads every deal, its
-- tabs, and the sales / finance / fulfilment pages, but edits nothing of other teams (no comment, activity, expected
-- payment, visit, pricing-request or money action). The grant counts for any role except import, which stays price-free.
-- Ships silently: no notification of any kind is raised by granting or using it.
--
-- Rollback: ALTER TABLE hr.employee DROP COLUMN can_read_all_sales;

ALTER TABLE hr.employee ADD COLUMN can_read_all_sales BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN hr.employee.can_read_all_sales IS
    'Per-employee grant: reads every deal and the sales, finance and fulfilment pages without any edit right (any role except import). Read live on every call, never cached on the session.';
