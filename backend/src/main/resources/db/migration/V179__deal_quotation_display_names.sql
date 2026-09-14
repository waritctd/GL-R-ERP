-- Direct-deal quotation (V165) — print-only name override for ผู้พิมพ์ / พนักงานขาย.
--
-- Owner feedback #4, as clarified mid-discussion (2026-09-14): "กรณีที่ admin ช่วยทำใบเสนอราคาแทนเซลล์
-- อยากให้แสดงชื่อผู้พิมพ์เป็นชื่อแอดมิน ส่วนชื่อพนักงานขายเป็นชื่อเซลล์" — when an admin fills in a
-- quotation on behalf of a sales rep, ผู้พิมพ์ should show the admin's name and พนักงานขาย should show
-- the rep's name. Owner's own summary: "they should be able to select who to show for ผู้พิมพ์ and
-- พนักงานขาย" — PRINT-ONLY name selection, explicitly NOT a change to deal ownership, edit/view
-- access, or commission attribution. The existing created_by / sales_rep_id columns (deal ownership,
-- access scoping via DealQuotationService#requireEditAccess, commission attribution) are UNTOUCHED
-- by this migration and by the application code built on top of it.
--
-- Additive and backward-compatible: both columns are nullable with NO backfill and NO default —
-- NULL already means "use the real name", i.e. today's behaviour, for every existing row and for
-- the whole legacy customer-quotation path (which never sets or reads either column).
--
-- Who may appear as an option (enforced in the application, not by a CHECK constraint here — see
-- DealQuotationRepository#isEligibleQuotationDisplayName / #findEligibleQuotationDisplayNameOptions):
-- the union of (a) active employees in the sales division (DivisionAccessPolicy.SALES_DIVISION_CODE)
-- and (b) any active employee holding the hr.employee.can_create_quotation grant. Who may SET these
-- two fields on a quotation: no new gate — the existing DealQuotationService#requireEditAccess rule
-- (sales on their own deal, sales_manager on any deal, a can_create_quotation grant holder on any
-- deal) already covers it.
--
-- Rollback: revert the application code (DealQuotationRequests/Dtos/Service/Repository/
-- RenderAdapter/Controller and the frontend), then
--     ALTER TABLE sales.quotation DROP CONSTRAINT sales_quotation_printed_by_display_id_fkey;
--     ALTER TABLE sales.quotation DROP CONSTRAINT sales_quotation_sales_rep_display_id_fkey;
--     ALTER TABLE sales.quotation DROP COLUMN printed_by_display_id;
--     ALTER TABLE sales.quotation DROP COLUMN sales_rep_display_id;
-- No data migration is needed in either direction: both columns are pure print overrides with no
-- other column or computation depending on their value.

ALTER TABLE sales.quotation
    ADD COLUMN printed_by_display_id  BIGINT NULL REFERENCES hr.employee(employee_id),
    ADD COLUMN sales_rep_display_id   BIGINT NULL REFERENCES hr.employee(employee_id);

COMMENT ON COLUMN sales.quotation.printed_by_display_id IS
    'Print-only override (owner feedback #4, 2026-09-14): when set, the ผู้พิมพ์ signature slot prints THIS employee''s name instead of created_by''s. Does not change who created/owns the document. NULL (default) prints created_by, exactly as before this column existed.';

COMMENT ON COLUMN sales.quotation.sales_rep_display_id IS
    'Print-only override (owner feedback #4, 2026-09-14): when set, the พนักงานขาย signature slot AND the header "Sales/{name} T.{phone}" line print THIS employee''s name+phone instead of sales_rep_id''s. Does not change who owns the deal or who earns commission on it. NULL (default) prints sales_rep_id, exactly as before this column existed.';
