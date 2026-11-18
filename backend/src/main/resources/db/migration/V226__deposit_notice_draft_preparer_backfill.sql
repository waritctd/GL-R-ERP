-- ผู้จัดทำ must be whoever made the document (sales manager ruling 2026-11-18). Until now no code wrote
-- preparer_name, so every deposit notice holds the V12 column default. Issued notices stay as printed;
-- a DRAFT still to be issued takes the deal's keyer (the person who works the deal's documents), else its owner.
UPDATE sales.deposit_notice d
   SET preparer_name = NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '')
  FROM sales.ticket t
  JOIN hr.employee e ON e.employee_id = COALESCE(t.keyed_by, t.created_by)
 WHERE t.ticket_id = d.ticket_id
   AND d.status = 'DRAFT'
   AND d.preparer_name = 'จินตนา หาญมนตรี'
   AND NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') IS NOT NULL;
