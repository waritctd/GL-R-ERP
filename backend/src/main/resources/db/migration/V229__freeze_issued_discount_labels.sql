-- Documents issued before the ส่วนลด release print exactly as issued (Ploy 2026-11-19): freeze the text the old
-- renderer computed ("ลด X", X = unit_price - net_unit_price when net < unit, "%,.2f" US locale) into discount_label,
-- or NULL where it printed nothing. Runs after V228, which cleared legacy labels.

UPDATE sales.deposit_notice_item i
   SET discount_label = CASE
         WHEN i.unit_price IS NOT NULL AND i.net_unit_price IS NOT NULL AND i.net_unit_price < i.unit_price
         THEN 'ลด ' || to_char(i.unit_price - i.net_unit_price, 'FM999,999,999,990.00')
       END
  FROM sales.deposit_notice d
 WHERE d.deposit_notice_id = i.deposit_notice_id
   AND d.status <> 'DRAFT';

UPDATE sales.remaining_invoice_item i
   SET discount_label = CASE
         WHEN i.unit_price IS NOT NULL AND i.net_unit_price IS NOT NULL AND i.net_unit_price < i.unit_price
         THEN 'ลด ' || to_char(i.unit_price - i.net_unit_price, 'FM999,999,999,990.00')
       END
  FROM sales.remaining_invoice r
 WHERE r.id = i.remaining_invoice_id
   AND r.status <> 'DRAFT';
