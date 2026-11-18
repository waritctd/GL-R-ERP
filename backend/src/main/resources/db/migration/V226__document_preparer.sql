-- ผู้จัดทำ is a selectable person (the deal's owner, keyer or a sales manager) defaulting to the deal's OWNER
-- (ruling 2026-11-18). Until now no code wrote deposit_notice.preparer_name, so every deposit notice holds the V12
-- column default.
ALTER TABLE sales.deposit_notice
    ADD COLUMN preparer_id BIGINT REFERENCES hr.employee(employee_id);

ALTER TABLE sales.remaining_invoice
    ADD COLUMN preparer_id   BIGINT REFERENCES hr.employee(employee_id),
    ADD COLUMN preparer_name VARCHAR(200);

-- DRAFT deposit notices still holding the V12 default -> the deal's owner. ISSUED ones stay as printed.
UPDATE sales.deposit_notice d
   SET preparer_id   = e.employee_id,
       preparer_name = NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '')
  FROM sales.ticket t
  JOIN hr.employee e ON e.employee_id = t.created_by
 WHERE t.ticket_id = d.ticket_id
   AND d.status = 'DRAFT'
   AND d.preparer_name = 'จินตนา หาญมนตรี'
   AND NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') IS NOT NULL;

-- DRAFT remaining invoices -> the deal's owner (the creator when the ticket has no owner).
UPDATE sales.remaining_invoice r
   SET preparer_id   = COALESCE(e.employee_id, r.created_by_id),
       preparer_name = COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), r.created_by_name)
  FROM sales.ticket t
  LEFT JOIN hr.employee e ON e.employee_id = t.created_by
 WHERE t.ticket_id = r.ticket_id
   AND r.status = 'DRAFT';

-- Every other remaining invoice keeps the name it printed until now: its deposit notice's preparer, else the
-- template's own line. No preparer_id (nobody was "chosen").
UPDATE sales.remaining_invoice r
   SET preparer_name = COALESCE(
         (SELECT d.preparer_name FROM sales.deposit_notice d WHERE d.deposit_notice_id = r.deposit_notice_id),
         'จินตนา หาญมนตรี')
 WHERE r.status <> 'DRAFT';

-- An already issued document names its preparer only by the name it printed. Where that name belongs to exactly ONE
-- employee (active or not), link it, so the document shows that person's signature image if one is uploaded.
WITH one_name AS (
    SELECT regexp_replace(TRIM(CONCAT_WS(' ', first_name_th, last_name_th)), '\s+', ' ', 'g') AS name,
           MIN(employee_id) AS employee_id
      FROM hr.employee
     WHERE NULLIF(TRIM(CONCAT_WS(' ', first_name_th, last_name_th)), '') IS NOT NULL
     GROUP BY 1
    HAVING COUNT(*) = 1
)
UPDATE sales.deposit_notice d
   SET preparer_id = n.employee_id
  FROM one_name n
 WHERE d.status <> 'DRAFT' AND d.preparer_id IS NULL
   AND regexp_replace(TRIM(d.preparer_name), '\s+', ' ', 'g') = n.name;

WITH one_name AS (
    SELECT regexp_replace(TRIM(CONCAT_WS(' ', first_name_th, last_name_th)), '\s+', ' ', 'g') AS name,
           MIN(employee_id) AS employee_id
      FROM hr.employee
     WHERE NULLIF(TRIM(CONCAT_WS(' ', first_name_th, last_name_th)), '') IS NOT NULL
     GROUP BY 1
    HAVING COUNT(*) = 1
)
UPDATE sales.remaining_invoice r
   SET preparer_id = n.employee_id
  FROM one_name n
 WHERE r.status <> 'DRAFT' AND r.preparer_id IS NULL
   AND regexp_replace(TRIM(r.preparer_name), '\s+', ' ', 'g') = n.name;
