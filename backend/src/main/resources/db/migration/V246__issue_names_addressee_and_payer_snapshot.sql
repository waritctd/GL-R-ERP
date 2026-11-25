-- Feedback #74, Ploy: "เรียน can be the master ลูกค้า … the person that is ออกในนาม can be a different person",
-- "store it as a seperate column" and "make everything choosable just put in the default value first".
-- On a deposit notice and a remaining invoice:
--   เรียน      = the customer_* header. Defaults to the referenced quotation's party customer (#65); addressee_customer_id
--                holds a different customer-list row the user picked (NULL = the quotation's customer).
--   ออกในนาม  = the person the document is issued to, printed as its own line under เรียน: payer_name / payer_phone /
--                payer_email, a snapshot. Defaults to the quotation's contact (ผู้ติดต่อ); payer_customer_id is set when
--                picked from (or quick-added to) the customer list. payer_name NULL = the same as เรียน.
-- Backfill: under V244/V245 (v2.7.0) a payer REPLACED the stored header. Those rows get the payer as the new snapshot
-- and their header back from the quotation's customer (else the deal's), under CustomerPrintRule: the branch prints only
-- with a company tax id (13 digits starting 1-8 is a person).
-- Rollback: ALTER TABLE sales.deposit_notice / sales.remaining_invoice DROP COLUMN addressee_customer_id, payer_name,
-- payer_phone, payer_email (the backfilled headers stay restored); DELETE FROM flyway_schema_history WHERE version = '246'.

ALTER TABLE sales.deposit_notice
    ADD COLUMN addressee_customer_id BIGINT REFERENCES customers.customer(customer_id),
    ADD COLUMN payer_name  VARCHAR(255),
    ADD COLUMN payer_phone VARCHAR(50),
    ADD COLUMN payer_email VARCHAR(200);
ALTER TABLE sales.remaining_invoice
    ADD COLUMN addressee_customer_id BIGINT REFERENCES customers.customer(customer_id),
    ADD COLUMN payer_name  VARCHAR(255),
    ADD COLUMN payer_phone VARCHAR(50),
    ADD COLUMN payer_email VARCHAR(200);

COMMENT ON COLUMN sales.deposit_notice.addressee_customer_id IS 'เรียน picked from the customer list instead of the quotation''s party customer (feedback #74); NULL = the quotation''s customer.';
COMMENT ON COLUMN sales.deposit_notice.payer_name IS 'ออกในนาม (feedback #74): the person the notice is issued to, printed under เรียน; NULL = the same as เรียน. Snapshot with payer_phone / payer_email.';
COMMENT ON COLUMN sales.deposit_notice.payer_customer_id IS 'The customer-list row the ออกในนาม snapshot was taken from (feedback #74); NULL when it is the quotation''s contact or none.';
COMMENT ON COLUMN sales.remaining_invoice.addressee_customer_id IS 'เรียน picked from the customer list instead of the quotation''s party customer (feedback #74); NULL = the quotation''s customer.';
COMMENT ON COLUMN sales.remaining_invoice.payer_name IS 'ออกในนาม (feedback #74): the person the invoice is issued to, printed under เรียน; NULL = the same as เรียน. Snapshot with payer_phone / payer_email.';
COMMENT ON COLUMN sales.remaining_invoice.payer_customer_id IS 'The customer-list row the ออกในนาม snapshot was taken from (feedback #74); NULL when it is the quotation''s contact or none.';

-- the payer's snapshot: name and phone from the customer row, email from its first contact that has one
UPDATE sales.deposit_notice d
   SET payer_name = pc.name, payer_phone = pc.phone,
       payer_email = (SELECT ct.email FROM customers.contact ct
                       WHERE ct.customer_id = pc.customer_id AND NULLIF(TRIM(ct.email), '') IS NOT NULL
                       ORDER BY ct.contact_id LIMIT 1)
  FROM customers.customer pc
 WHERE pc.customer_id = d.payer_customer_id;
UPDATE sales.remaining_invoice r
   SET payer_name = pc.name, payer_phone = pc.phone,
       payer_email = (SELECT ct.email FROM customers.contact ct
                       WHERE ct.customer_id = pc.customer_id AND NULLIF(TRIM(ct.email), '') IS NOT NULL
                       ORDER BY ct.contact_id LIMIT 1)
  FROM customers.customer pc
 WHERE pc.customer_id = r.payer_customer_id;

-- the header back to the quotation's party customer (else the deal's customer)
WITH h AS (
    SELECT d.deposit_notice_id AS id, c.name, NULLIF(TRIM(c.tax_id), '') AS tax_id, NULLIF(TRIM(c.address), '') AS address,
           CASE WHEN NULLIF(TRIM(c.tax_id), '') IS NOT NULL AND NULLIF(TRIM(c.branch), '') IS NOT NULL
                     AND regexp_replace(c.tax_id, '[\s-]', '', 'g') !~ '^[1-8][0-9]{12}$'
                THEN TRIM(c.branch) END AS branch
      FROM sales.deposit_notice d
      JOIN sales.ticket t ON t.ticket_id = d.ticket_id
      JOIN customers.customer c ON c.customer_id = COALESCE((
           SELECT dp.customer_id FROM sales.quotation q JOIN sales.deal_party dp ON dp.party_id = q.party_id
            WHERE q.ticket_id = d.ticket_id AND q.number = d.reference AND q.origin IS NOT NULL
            ORDER BY q.quotation_id DESC LIMIT 1), t.customer_id)
     WHERE d.payer_customer_id IS NOT NULL)
UPDATE sales.deposit_notice d
   SET customer_name = h.name, customer_tax_id = h.tax_id,
       customer_address = NULLIF(CONCAT_WS(' ', h.address, h.branch), '')
  FROM h WHERE h.id = d.deposit_notice_id;

WITH h AS (
    SELECT r.id, c.name, NULLIF(TRIM(c.tax_id), '') AS tax_id, NULLIF(TRIM(c.address), '') AS address,
           CASE WHEN NULLIF(TRIM(c.tax_id), '') IS NOT NULL AND NULLIF(TRIM(c.branch), '') IS NOT NULL
                     AND regexp_replace(c.tax_id, '[\s-]', '', 'g') !~ '^[1-8][0-9]{12}$'
                THEN TRIM(c.branch) END AS branch
      FROM sales.remaining_invoice r
      JOIN sales.ticket t ON t.ticket_id = r.ticket_id
      JOIN customers.customer c ON c.customer_id = COALESCE((
           SELECT dp.customer_id FROM sales.quotation q JOIN sales.deal_party dp ON dp.party_id = q.party_id
            WHERE q.ticket_id = r.ticket_id AND q.origin IS NOT NULL
              AND (q.quotation_id = r.customer_quotation_id OR (r.customer_quotation_id IS NULL AND q.number = r.reference))
            ORDER BY q.quotation_id DESC LIMIT 1), t.customer_id)
     WHERE r.payer_customer_id IS NOT NULL)
UPDATE sales.remaining_invoice r
   SET customer_name = h.name, customer_tax_id = h.tax_id, customer_branch = h.branch, customer_address = h.address
  FROM h WHERE h.id = r.id;
