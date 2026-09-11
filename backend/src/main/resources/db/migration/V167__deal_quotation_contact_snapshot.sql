-- Quotation v2 -- owner feedback pass 1, F2: "change from ผู้ติดต่อ -> ผู้สั่งซื้อ, make mandatory,
-- and use that name to auto fill in the name for signature in the quotation pdf" (Ploy, 2026-09-10
-- evening, after the demo).
--
-- MIGRATION NUMBERING: origin/develop tops out at V166 (leave partial-day span), so V167 is the
-- next free number ABOVE develop's max -- a gap would let a later, lower-numbered migration be
-- silently skipped on the prod deploy (validate-on-migrate is false there; see CLAUDE.md).
--
-- The deal's contact (sales.ticket.contact_id -> customers.contact) is the ผู้สั่งซื้อ -- the
-- person the quotation is addressed to ("เรียน คุณ{contact} / {company}") AND whose name is
-- pre-printed under the ผู้สั่งซื้อ signature slot. DealQuotationRepository used to LEFT JOIN the
-- ticket's contact at read time, so the printed name followed whatever the ticket pointed at
-- TODAY; an approved, emailed document could then re-render with a different name after the deal's
-- contact was changed. These four columns are a FROZEN SNAPSHOT written by DealQuotationService at
-- create/update (a DRAFT being re-saved re-snapshots from the chosen contact; nothing else ever
-- rewrites them, and a revision copies its parent's snapshot verbatim). Every read now comes from
-- the snapshot only -- the ticket-contact join is gone from the repository.
--
-- contact_id is a soft reference (no FK): the snapshot must outlive the contact row -- an approved
-- quotation must still print the name it was approved with after the contact is deleted -- and
-- customers.contact cascades on customer delete (V23). Widths mirror customers.contact
-- (first_name 100 + ' ' + last_name 100 -> 201, rounded to 255; phone 50; email 200).
ALTER TABLE sales.quotation
    ADD COLUMN contact_id    BIGINT,
    ADD COLUMN contact_name  VARCHAR(255),
    ADD COLUMN contact_phone VARCHAR(50),
    ADD COLUMN contact_email VARCHAR(200);

-- Backfill every existing direct-deal row from the ticket's contact as it stands NOW -- the same
-- value the old read-time join would have produced for it -- so the demo/UAT rows keep their
-- เรียน line and gain a ผู้สั่งซื้อ name instead of going blank. Rows whose ticket has no contact
-- stay NULL: DealQuotationService refuses to submit them (400 "กรุณาระบุผู้สั่งซื้อ") until a
-- contact is chosen on the next save. Idempotent (only NULL contact_id rows are touched).
UPDATE sales.quotation q
   SET contact_id    = ct.contact_id,
       contact_name  = NULLIF(TRIM(CONCAT_WS(' ', ct.first_name, ct.last_name)), ''),
       contact_phone = ct.phone,
       contact_email = ct.email
  FROM sales.ticket t
  JOIN customers.contact ct ON ct.contact_id = t.contact_id
 WHERE t.ticket_id = q.ticket_id
   AND q.origin = 'DEAL_DIRECT'
   AND q.contact_id IS NULL;
