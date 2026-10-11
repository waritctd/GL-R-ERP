-- Sales feedback #5 (แจ้งปัญหาการใช้งาน เสนอราคา, 2026-10-06) — "ไม่แสดงชื่อโครงการในใบเสนอราคา".
--
-- Sales asked for โครงการ to stop being required. The ruling was the opposite half of that: the
-- project stays REQUIRED on the deal ("กรณียังไม่ทราบชัดเจน เป็นชื่อกลางๆได้ เช่น บ้านเอกมัย ซอย 5,
-- โรงแรม@เชียงใหม่") and the rep chooses, per document, whether it PRINTS ("สร้างทางเลือก แสดง
-- หรือไม่แสดง"). So a rep who had to type a placeholder name is not forced to send that
-- placeholder to the customer.
--
-- Per-QUOTATION, like omit_contact_honorific (V180) and ordered_by_name (V192): the same deal can
-- issue one document that names the project and another that does not, so the choice lives on
-- sales.quotation itself. The project NAME is untouched — sales.quotation.project_name still holds
-- it, and the editor, the เอกสาร register and the deal all still read it. This column decides one
-- thing only: whether DealQuotationRenderAdapter hands that name to the renderer (the
-- "Project : …" line on the Thai F-SM-002 and English F-SM-008 documents alike).
--
-- NOT NULL DEFAULT FALSE: every existing row printed its project name, which is exactly what
-- FALSE preserves — additive and backward compatible, no backfill beyond the default. It is also
-- what keeps every INSERT that does not name this column correct with no code change: the legacy
-- customerquotation/ engine, DealQuotationService#createFromPricingRequest, and any path added
-- later all write a document that prints its project until someone ticks the box.
--
-- Rollback: revert the application code (DealQuotationDtos / DealQuotationRequests /
-- DealQuotationRepository / DealQuotationService / DealQuotationRenderAdapter, the mock, and the
-- editor's tick box), then
--     ALTER TABLE sales.quotation DROP COLUMN hide_project_name;
-- Nothing else reads the column and it duplicates no other, so dropping it loses only the flag —
-- every document goes back to printing its project name, as before this migration.

ALTER TABLE sales.quotation
    ADD COLUMN hide_project_name BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN sales.quotation.hide_project_name IS
    'Deal quotation (origin DEAL_DIRECT or PRICING_REQUEST): when TRUE, DealQuotationRenderAdapter gives the renderer no project name, so the printed document carries no "Project : ..." line. project_name itself is kept and still shown in the editor, the register and on the deal. Defaults FALSE (print it) for every row, including every pre-V200 one. A revision / reorder clone inherits it; it is not remembered in quotationPrefs, so a brand-new quotation always starts unticked.';
