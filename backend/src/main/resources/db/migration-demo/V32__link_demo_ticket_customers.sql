-- The original V21 demo seed only set sales.ticket.customer_name (free text),
-- not customer_id, even though the customer rows it references already exist
-- in sales.customer (seeded by V16). Without the FK, quotation/deposit-notice
-- generation has no customer row to pull tax ID / address from, so the
-- auto-fill feature looks broken in the demo/UAT environment even though it
-- works correctly for tickets created against a real customer record.
UPDATE sales.ticket t
   SET customer_id = c.customer_id
  FROM sales.customer c
 WHERE t.code LIKE 'DEMO-TKT-%'
   AND t.customer_id IS NULL
   AND t.customer_name = c.name;
