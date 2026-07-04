-- Rename the deposit-notice tables/columns to honest names.
-- sales.document was deposit-notice-specific in everything but name (deposit_percent,
-- deposit_amount, vat_amount, total_payable; DEPOSIT_NOTICE-hardwired). Quotation and
-- invoice get their own tables (see docs/decisions/quotation-deposit-invoice-model.md).
-- Behavior-preserving: RENAME keeps data, FK constraint, and identity sequences intact.
--
-- Shared infra intentionally kept generic (not renamed): sales.document_sequence and
-- sales.document_note_template.

ALTER TABLE sales.document RENAME TO deposit_notice;
ALTER TABLE sales.deposit_notice RENAME COLUMN document_id TO deposit_notice_id;

ALTER TABLE sales.document_item RENAME TO deposit_notice_item;
ALTER TABLE sales.deposit_notice_item RENAME COLUMN document_id TO deposit_notice_id;

ALTER INDEX sales.idx_document_ticket RENAME TO idx_deposit_notice_ticket;
