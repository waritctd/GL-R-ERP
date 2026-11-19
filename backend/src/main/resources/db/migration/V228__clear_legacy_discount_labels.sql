-- The ส่วนลด cell now prints the stored discount_label as-is (the quotation's own cell text: พิเศษ / Net / N%).
-- Until now the renderer ignored the column, so rows still carry the old authored forms, which would print as long
-- clipped text: the notice's "ส่วนลด <amount> ต่อหน่วย" (also folded "ส่วนลดพิเศษ ... ต่อหน่วย"), and the remaining
-- invoice's short "ลด <amount>". Clear exactly those shapes; old documents print an empty cell.
UPDATE sales.deposit_notice_item
   SET discount_label = NULL
 WHERE discount_label LIKE 'ส่วนลด%ต่อหน่วย';

UPDATE sales.remaining_invoice_item
   SET discount_label = NULL
 WHERE discount_label LIKE 'ส่วนลด%ต่อหน่วย'
    OR discount_label ~ '^ลด [0-9]';
