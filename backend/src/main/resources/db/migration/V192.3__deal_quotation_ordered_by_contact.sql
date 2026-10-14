-- Sales feedback (แจ้งปัญหาการใช้งาน เสนอราคา), owner ruling recorded 2026-10-11: "ผู้ติดต่อ should also
-- have an Email and Phone this is for sales record only (it should show in the details of the
-- quotation but doesn't need to be render in the actual pdf)".
--
-- ผู้ติดต่อ is the editor's name for sales.quotation.ordered_by_name (V192) — the free text a rep
-- types, which prints in the ผู้สั่งซื้อ signature slot. These two columns sit beside it and hold how
-- to reach that person. They are a SALES RECORD and nothing else:
--
--   * Never printed. DealQuotationRenderAdapter, QuotationRenderModel and QuotationRenderer do not
--     read them, so no quotation document — Thai or English, XLS or PDF — changes because of this
--     migration. DealQuotationOrderedByContactRenderTest and
--     DealQuotationOrderedByContactIntegrationTest pin that.
--   * NOT the contact snapshot. contact_id / contact_name / contact_phone / contact_email (V167)
--     are the customer-master contact, and the header line the document prints still reads those.
--     Nothing here copies to or from them.
--
-- Per-QUOTATION, like ordered_by_name: two contractors bidding on one project are two quotations
-- with two different people to call. A revision / reorder copy inherits both columns, exactly as it
-- inherits ordered_by_name.
--
-- NULLABLE, no default, no backfill: NULL means "nobody typed one", which is true of every existing
-- row and of every row a path that knows nothing of these columns writes (the legacy
-- customerquotation/ engine, DealQuotationService#createFromPricingRequest).
--
-- Widths are the limits DealQuotationService enforces before it writes (400, not a SQL error):
-- phone is free text up to 50 characters — an extension or several numbers is normal
-- ("02-123-4567 ต่อ 12, 081-234-5678") — the same width as contact_phone; email is up to 254, the
-- longest address that can be delivered to.
--
-- Rollback: revert the application code (DealQuotationRequests / DealQuotationDtos /
-- DealQuotationRepository / DealQuotationService, the mock, and the editor's two inputs), then
--     ALTER TABLE sales.quotation DROP COLUMN ordered_by_phone, DROP COLUMN ordered_by_email;
-- Nothing else reads the columns and nothing is derived from them, so dropping them loses only the
-- typed phone numbers and addresses.

ALTER TABLE sales.quotation
    ADD COLUMN ordered_by_phone VARCHAR(50),
    ADD COLUMN ordered_by_email VARCHAR(254);

COMMENT ON COLUMN sales.quotation.ordered_by_phone IS
    'Sales record only, NEVER printed: phone of the person named in ordered_by_name (the editor''s ผู้ติดต่อ field), typed by the sales rep. Free text up to 50 characters. NULL when none was typed. Not the customer-master contact snapshot (contact_phone).';

COMMENT ON COLUMN sales.quotation.ordered_by_email IS
    'Sales record only, NEVER printed: email of the person named in ordered_by_name (the editor''s ผู้ติดต่อ field), typed by the sales rep. NULL when none was typed. Not the customer-master contact snapshot (contact_email).';
