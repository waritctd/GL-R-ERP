-- Normalize ETL-imported manager position titles that bake the ฝ่าย (division) into the name.
--
-- Background: managers are detected by DivisionAccessPolicy.isManager(), which flags any position
-- whose (whitespace-stripped) name contains "ผู้จัดการ". The imported catalog carried the ฝ่าย inside
-- the title, e.g. "ผู้จัดการฝ่ายขาย", "ผู้จัดการฝ่ายผลิต". A manager's ฝ่าย/scope is already derived from
-- their division assignment (hr.employee.division_id), so the division does not belong in the title.
-- This collapses those titles to a single canonical value "ผู้จัดการ".
--
-- Assistant managers are kept as a DISTINCT canonical position "ผู้ช่วยผู้จัดการ" (see
-- ผู้ช่วยผู้จัดการฝ่ายผลิต). They are intentionally NOT merged into full managers.
--
-- Executives are left untouched: "กรรมการผู้จัดการ" (Managing Director) is matched by
-- DivisionAccessPolicy.isExecutive() via "กรรมการ" and must keep that title, so its role stays "ceo".
-- The predicates below only match titles that START WITH "ผู้จัดการ"/"ผู้ช่วยผู้จัดการ", so
-- "กรรมการผู้จัดการ" (starts with "กรรมการ") is never rewritten.
--
-- NO employee's division_id is changed here, so the division-derived scope/role is preserved.
-- The migration is idempotent and set-based: re-running it is a no-op. Retired baked catalog rows are
-- deactivated (is_active = FALSE), not deleted, so this stays reversible and loses no source_code
-- provenance. Dated assignment history in hr.employee_assignment is preserved — only the position_id
-- each row points to is re-pointed; effective_from/effective_to/is_current are untouched.

-- 0. Resync the IDENTITY sequence. The catalog was ETL-imported with explicit position_id values, so
--    the GENERATED-ALWAYS identity sequence is behind MAX(position_id) and a plain INSERT would collide
--    on the primary key. Fast-forward it so the canonical rows below get fresh ids. Idempotent.
SELECT setval(pg_get_serial_sequence('hr.position', 'position_id'),
              (SELECT GREATEST(MAX(position_id), 1) FROM hr.position), TRUE);

-- 1. Ensure the two canonical catalog entries exist (insert only if missing → idempotent).
INSERT INTO hr.position (source_code, name_th, name_en, is_active)
SELECT 'MGR', 'ผู้จัดการ', 'Manager', TRUE
WHERE NOT EXISTS (SELECT 1 FROM hr.position WHERE name_th = 'ผู้จัดการ');

INSERT INTO hr.position (source_code, name_th, name_en, is_active)
SELECT 'AMGR', 'ผู้ช่วยผู้จัดการ', 'Assistant Manager', TRUE
WHERE NOT EXISTS (SELECT 1 FROM hr.position WHERE name_th = 'ผู้ช่วยผู้จัดการ');

-- 2. Re-point full ฝ่าย managers (e.g. ผู้จัดการฝ่ายขาย, ผู้จัดการฝ่ายผลิต) to canonical "ผู้จัดการ".
--    The imported catalog can carry more than one row already named exactly 'ผู้จัดการ' (duplicate
--    ETL batches), so "the" canonical row is picked deterministically (lowest position_id) instead of
--    assuming uniqueness — otherwise the subqueries below would fail with "more than one row returned".
--    Current employment snapshot:
UPDATE hr.employee e
SET position_id = (SELECT MIN(position_id) FROM hr.position WHERE name_th = 'ผู้จัดการ')
WHERE e.position_id IN (
    SELECT position_id FROM hr.position
    WHERE regexp_replace(name_th, '\s', '', 'g') LIKE 'ผู้จัดการ%'
      AND name_th <> 'ผู้จัดการ'
);
--    Dated assignment history (keeps effective_from/effective_to/is_current):
UPDATE hr.employee_assignment a
SET position_id = (SELECT MIN(position_id) FROM hr.position WHERE name_th = 'ผู้จัดการ')
WHERE a.position_id IN (
    SELECT position_id FROM hr.position
    WHERE regexp_replace(name_th, '\s', '', 'g') LIKE 'ผู้จัดการ%'
      AND name_th <> 'ผู้จัดการ'
);

-- 3. Re-point assistant managers (e.g. ผู้ช่วยผู้จัดการฝ่ายผลิต) to canonical "ผู้ช่วยผู้จัดการ".
UPDATE hr.employee e
SET position_id = (SELECT MIN(position_id) FROM hr.position WHERE name_th = 'ผู้ช่วยผู้จัดการ')
WHERE e.position_id IN (
    SELECT position_id FROM hr.position
    WHERE regexp_replace(name_th, '\s', '', 'g') LIKE 'ผู้ช่วยผู้จัดการ%'
      AND name_th <> 'ผู้ช่วยผู้จัดการ'
);
UPDATE hr.employee_assignment a
SET position_id = (SELECT MIN(position_id) FROM hr.position WHERE name_th = 'ผู้ช่วยผู้จัดการ')
WHERE a.position_id IN (
    SELECT position_id FROM hr.position
    WHERE regexp_replace(name_th, '\s', '', 'g') LIKE 'ผู้ช่วยผู้จัดการ%'
      AND name_th <> 'ผู้ช่วยผู้จัดการ'
);

-- 3b. If duplicate exact-'ผู้จัดการ'/'ผู้ช่วยผู้จัดการ' rows exist, re-point anything still referencing
--     a non-canonical duplicate (i.e. not the MIN(position_id) row) onto the canonical row too.
UPDATE hr.employee e
SET position_id = (SELECT MIN(position_id) FROM hr.position WHERE name_th = 'ผู้จัดการ')
WHERE e.position_id IN (SELECT position_id FROM hr.position WHERE name_th = 'ผู้จัดการ')
  AND e.position_id <> (SELECT MIN(position_id) FROM hr.position WHERE name_th = 'ผู้จัดการ');
UPDATE hr.employee_assignment a
SET position_id = (SELECT MIN(position_id) FROM hr.position WHERE name_th = 'ผู้จัดการ')
WHERE a.position_id IN (SELECT position_id FROM hr.position WHERE name_th = 'ผู้จัดการ')
  AND a.position_id <> (SELECT MIN(position_id) FROM hr.position WHERE name_th = 'ผู้จัดการ');
UPDATE hr.employee e
SET position_id = (SELECT MIN(position_id) FROM hr.position WHERE name_th = 'ผู้ช่วยผู้จัดการ')
WHERE e.position_id IN (SELECT position_id FROM hr.position WHERE name_th = 'ผู้ช่วยผู้จัดการ')
  AND e.position_id <> (SELECT MIN(position_id) FROM hr.position WHERE name_th = 'ผู้ช่วยผู้จัดการ');
UPDATE hr.employee_assignment a
SET position_id = (SELECT MIN(position_id) FROM hr.position WHERE name_th = 'ผู้ช่วยผู้จัดการ')
WHERE a.position_id IN (SELECT position_id FROM hr.position WHERE name_th = 'ผู้ช่วยผู้จัดการ')
  AND a.position_id <> (SELECT MIN(position_id) FROM hr.position WHERE name_th = 'ผู้ช่วยผู้จัดการ');

-- 4. Retire the now-orphaned baked catalog rows (non-destructive; keeps source_code provenance),
--    plus any duplicate exact-canonical rows left empty by step 3b.
UPDATE hr.position
SET is_active = FALSE
WHERE (regexp_replace(name_th, '\s', '', 'g') LIKE 'ผู้จัดการ%'      AND name_th <> 'ผู้จัดการ')
   OR (regexp_replace(name_th, '\s', '', 'g') LIKE 'ผู้ช่วยผู้จัดการ%' AND name_th <> 'ผู้ช่วยผู้จัดการ')
   OR (name_th = 'ผู้จัดการ'        AND position_id <> (SELECT MIN(position_id) FROM hr.position WHERE name_th = 'ผู้จัดการ'))
   OR (name_th = 'ผู้ช่วยผู้จัดการ'  AND position_id <> (SELECT MIN(position_id) FROM hr.position WHERE name_th = 'ผู้ช่วยผู้จัดการ'));
