-- V17: extend ticket status enum to include document_issued
-- (V12 already created the document tables and revision_no column)

ALTER TABLE sales.ticket DROP CONSTRAINT IF EXISTS chk_ticket_status;
ALTER TABLE sales.ticket ADD CONSTRAINT chk_ticket_status CHECK (status IN (
    'draft','submitted','in_review','price_proposed',
    'approved','rejected','quotation_issued','closed','cancelled',
    'document_issued'
));
