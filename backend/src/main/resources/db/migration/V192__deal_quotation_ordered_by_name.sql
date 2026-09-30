-- Owner-directed reversal of F2 (2026-09-10, hardened 2026-09-15): the ผู้สั่งซื้อ (buyer)
-- signature slot on a deal quotation must NEVER auto-fill from the contact/customer name any
-- more. It defaults to the dotted signature line, and prints ONLY what a sales rep types here
-- by hand.
--
-- sales.quotation.ordered_by_name: nullable free-text manual override for the ผู้สั่งซื้อ
-- signature-slot name. NULL (the default, and every pre-V192 row) keeps the dotted placeholder;
-- a non-blank value is what DealQuotationRenderAdapter#orderedByName now prints there instead —
-- see that method's own Javadoc for the reversal. Does NOT affect the "เรียน ..." greeting line
-- at the top of the document, which still reads contactName/customerName untouched.

ALTER TABLE sales.quotation
    ADD COLUMN ordered_by_name VARCHAR(255);

COMMENT ON COLUMN sales.quotation.ordered_by_name IS
    'Owner-directed reversal of F2 (2026-09-10/09-15): manual, optional buyer name typed by the sales rep for the printed ผู้สั่งซื้อ signature slot. NULL prints the dotted placeholder; never auto-filled from contact_name/customer_name any more.';
