-- =====================================================================
-- DEMO BRANCH ONLY (demo/all-roles-showcase): seed one login per role
-- (employee / hr / sales / sales_manager / import / ceo) plus
-- sample tickets, leave, overtime, and commission records so every role
-- has something to click through in every module.
--
-- Safety:
--   * All demo rows use the 'DEMO-' employee_code / ticket-code prefix so
--     they are trivially identifiable and removable (see rollback notes).
--   * Demo employees deliberately carry NO current_salary, and
--     PayrollRepository.findActiveEmployees() additionally excludes
--     employee_code LIKE 'DEMO-%' outright, so these rows can never be
--     swept into a real payroll batch / KBank export / payslip run.
--   * Shared password for every demo account: Demo@2026
--     (BCrypt hash below verified against Spring's BCryptPasswordEncoder.)
--   * Roles resolve from the employee's division via
--     DivisionAccessPolicy.roleFor (md->ceo, hr->hr, pcim->import,
--     sa->sales/sales_manager; everything else -> employee). There is NO
--     'admin' role (ApplicationRoles.java). This file once seeded a
--     demo.admin persona in a fake ADMIN division, but roleFor never
--     special-cased it (it silently logged in as a plain employee), so
--     V46 deleted it from live demo DBs and the seed rows were dropped
--     from this file (#206). Edit-in-place is safe here: the hosted demo
--     runs the prod profile with validate-on-migrate=false.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Reference data: ensure the divisions this feature depends on exist.
-- ON CONFLICT DO NOTHING means these are no-ops against the real
-- production database (which already has MD/HR/PCIM/SA) and only
-- backfill placeholder rows on a fresh/CI database.
-- ---------------------------------------------------------------------
INSERT INTO hr.division (source_code, name_th) VALUES
    ('MD',   'MD-ผู้บริหารระดับสูง'),
    ('HR',   'HR-บุคคล'),
    ('PCIM', 'PCIM-จัดซื้อต่างประเทศ'),
    ('SA',   'SA-ฝ่ายขาย')
ON CONFLICT (source_code) DO NOTHING;

INSERT INTO hr.position (source_code, name_th) VALUES
    ('DEMO-STAFF', 'พนักงาน (สาธิต)'),
    ('DEMO-MGR',   'ผู้จัดการฝ่ายขาย (สาธิต)')
ON CONFLICT (source_code) DO NOTHING;

-- ---------------------------------------------------------------------
-- One demo employee per role. Password for all: Demo@2026
-- ---------------------------------------------------------------------
INSERT INTO hr.employee (
    employee_code, first_name_th, last_name_th, email,
    division_id, position_id, password_hash, must_change_password, is_active
)
SELECT v.employee_code, v.first_name_th, v.last_name_th, v.email,
       d.division_id, p.position_id,
       '$2y$10$dgX94V4KgoGZzoiJPGiA9.Xa3M1GLBph8x1yOyTPqr8c9DPF4Fkt2',
       FALSE, TRUE
FROM (VALUES
    ('DEMO-EMP01', '[DEMO]', 'พนักงานทั่วไป',      'demo.employee@demo.invalid',      NULL,    'DEMO-STAFF'),
    ('DEMO-HR01',  '[DEMO]', 'ฝ่ายบุคคล',           'demo.hr@demo.invalid',            'HR',    'DEMO-STAFF'),
    ('DEMO-SLS01', '[DEMO]', 'ฝ่ายขาย',             'demo.sales@demo.invalid',         'SA',    'DEMO-STAFF'),
    ('DEMO-MGR01', '[DEMO]', 'ผู้จัดการฝ่ายขาย',    'demo.salesmanager@demo.invalid',  'SA',    'DEMO-MGR'),
    ('DEMO-IMP01', '[DEMO]', 'ฝ่ายจัดซื้อ',         'demo.import@demo.invalid',        'PCIM',  'DEMO-STAFF'),
    ('DEMO-CEO01', '[DEMO]', 'ผู้บริหาร',           'demo.ceo@demo.invalid',           'MD',    'DEMO-STAFF')
) AS v(employee_code, first_name_th, last_name_th, email, division_code, position_code)
LEFT JOIN hr.division d ON d.source_code = v.division_code
LEFT JOIN hr.position p ON p.source_code = v.position_code
ON CONFLICT (employee_code) DO NOTHING;

-- ---------------------------------------------------------------------
-- Sample tickets across the sales workflow (draft -> ... -> closed),
-- reusing the sample customers seeded in V16.
-- ---------------------------------------------------------------------
INSERT INTO sales.ticket (code, title, status, created_by, assigned_to, customer_name)
SELECT v.code, v.title, v.status,
       (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-SLS01'),
       (SELECT employee_id FROM hr.employee WHERE employee_code = v.assigned_to_code),
       (SELECT name FROM sales.customer ORDER BY customer_id LIMIT 1 OFFSET v.customer_offset)
FROM (VALUES
    ('DEMO-TKT-01', 'กระเบื้องปูพื้นโครงการ A (Demo)', 'draft',            NULL,          0),
    ('DEMO-TKT-02', 'กระเบื้องปูผนังโครงการ B (Demo)', 'submitted',        'DEMO-IMP01',  1),
    ('DEMO-TKT-03', 'กระเบื้องสระว่ายน้ำโครงการ C (Demo)', 'price_proposed', 'DEMO-IMP01', 2),
    ('DEMO-TKT-04', 'กระเบื้องคอนโดโครงการ D (Demo)',  'approved',         'DEMO-IMP01',  3),
    ('DEMO-TKT-05', 'กระเบื้องบ้านเดี่ยวโครงการ E (Demo)', 'closed',        'DEMO-IMP01',  0)
) AS v(code, title, status, assigned_to_code, customer_offset)
ON CONFLICT (code) DO NOTHING;

UPDATE sales.ticket SET closed_at = now() - INTERVAL '5 days'
 WHERE code = 'DEMO-TKT-05' AND closed_at IS NULL;

INSERT INTO sales.ticket_item (ticket_id, model, brand, texture, size, color, qty, unit, proposed_price, approved_price)
SELECT t.ticket_id, v.model, v.brand, v.texture, v.size, v.color, v.qty, 'แผ่น', v.proposed_price, v.approved_price
FROM sales.ticket t
JOIN (VALUES
    ('DEMO-TKT-01', 'GRT-6060-WH', 'กระเบื้องแกรนิตโต้ (Demo)', 'ด้าน', '60x60', 'ขาว', 120, NULL::numeric, NULL::numeric),
    ('DEMO-TKT-02', 'GRT-3060-GY', 'กระเบื้องผนัง (Demo)',     'มัน',  '30x60', 'เทา', 80,  NULL::numeric, NULL::numeric),
    ('DEMO-TKT-03', 'POOL-2525-BL','กระเบื้องสระว่ายน้ำ (Demo)','ด้าน', '25x25','ฟ้า', 300, 850::numeric,  NULL::numeric),
    ('DEMO-TKT-04', 'GRT-8080-BG', 'กระเบื้องแกรนิตโต้ (Demo)', 'มัน',  '80x80', 'เบจ', 150, 1450::numeric, 1350::numeric),
    ('DEMO-TKT-05', 'GRT-6060-WH', 'กระเบื้องแกรนิตโต้ (Demo)', 'ด้าน', '60x60', 'ขาว', 200, 980::numeric,  920::numeric)
) AS v(code, model, brand, texture, size, color, qty, proposed_price, approved_price)
  ON v.code = t.code
WHERE NOT EXISTS (SELECT 1 FROM sales.ticket_item i WHERE i.ticket_id = t.ticket_id);

INSERT INTO sales.ticket_event (ticket_id, actor_id, actor_name, kind, from_status, to_status, message)
SELECT t.ticket_id,
       (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-SLS01'),
       '[DEMO] ฝ่ายขาย', 'CREATED', NULL, 'draft', 'สร้าง ticket สาธิต'
FROM sales.ticket t
WHERE t.code LIKE 'DEMO-TKT-%'
  AND NOT EXISTS (SELECT 1 FROM sales.ticket_event e WHERE e.ticket_id = t.ticket_id);

INSERT INTO sales.notification (employee_id, ticket_id, type, message)
SELECT (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-IMP01'),
       (SELECT ticket_id FROM sales.ticket WHERE code = 'DEMO-TKT-02'),
       'SUBMITTED', 'Ticket DEMO-TKT-02 รอการรับเรื่อง (Demo)'
WHERE NOT EXISTS (SELECT 1 FROM sales.notification WHERE message = 'Ticket DEMO-TKT-02 รอการรับเรื่อง (Demo)');

INSERT INTO sales.notification (employee_id, ticket_id, type, message)
SELECT (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-CEO01'),
       (SELECT ticket_id FROM sales.ticket WHERE code = 'DEMO-TKT-04'),
       'PRICE_PROPOSED', 'Ticket DEMO-TKT-04 รออนุมัติราคา (Demo)'
WHERE NOT EXISTS (SELECT 1 FROM sales.notification WHERE message = 'Ticket DEMO-TKT-04 รออนุมัติราคา (Demo)');

-- ---------------------------------------------------------------------
-- Leave: one pending request (for HR/CEO to review) + one historical
-- approval, both against the real leave-quota engine.
-- ---------------------------------------------------------------------
INSERT INTO hr.leave_request (
    employee_id, leave_type_code, start_date, end_date, total_days, quota_year,
    reason, status, quota_remaining_before, quota_remaining_after, requested_by_id
)
SELECT (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-EMP01'),
       'SICK', CURRENT_DATE + 5, CURRENT_DATE + 7, 3,
       EXTRACT(YEAR FROM CURRENT_DATE)::smallint,
       'ไข้หวัดใหญ่ (Demo)', 'SUBMITTED', 30, 27,
       (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-EMP01')
WHERE NOT EXISTS (
    SELECT 1 FROM hr.leave_request lr
    JOIN hr.employee e ON e.employee_id = lr.employee_id
    WHERE e.employee_code = 'DEMO-EMP01' AND lr.reason = 'ไข้หวัดใหญ่ (Demo)'
);

INSERT INTO hr.leave_request (
    employee_id, leave_type_code, start_date, end_date, total_days, quota_year,
    reason, status, quota_remaining_before, quota_remaining_after,
    requested_by_id, reviewed_by_id, reviewed_at
)
SELECT (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-SLS01'),
       'VACATION', CURRENT_DATE - 10, CURRENT_DATE - 10, 1,
       EXTRACT(YEAR FROM CURRENT_DATE - 10)::smallint,
       'พักผ่อนประจำปี (Demo)', 'APPROVED', 6, 5,
       (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-SLS01'),
       (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-HR01'),
       now() - INTERVAL '9 days'
WHERE NOT EXISTS (
    SELECT 1 FROM hr.leave_request lr
    JOIN hr.employee e ON e.employee_id = lr.employee_id
    WHERE e.employee_code = 'DEMO-SLS01' AND lr.reason = 'พักผ่อนประจำปี (Demo)'
);

-- ---------------------------------------------------------------------
-- Overtime: one pending request + one historical approval.
-- ---------------------------------------------------------------------
INSERT INTO hr.overtime_request (
    employee_id, work_date, planned_start_at, planned_end_at, planned_minutes,
    reason, status, payroll_month, requested_by_id
)
SELECT (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-EMP01'),
       CURRENT_DATE - 2,
       (CURRENT_DATE - 2 + TIME '18:00'), (CURRENT_DATE - 2 + TIME '20:00'), 120,
       'เร่งปิดงานสต็อกสิ้นเดือน (Demo)', 'SUBMITTED',
       date_trunc('month', CURRENT_DATE)::date,
       (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-EMP01')
WHERE NOT EXISTS (
    SELECT 1 FROM hr.overtime_request ot
    JOIN hr.employee e ON e.employee_id = ot.employee_id
    WHERE e.employee_code = 'DEMO-EMP01' AND ot.reason = 'เร่งปิดงานสต็อกสิ้นเดือน (Demo)'
);

INSERT INTO hr.overtime_request (
    employee_id, work_date, planned_start_at, planned_end_at, planned_minutes,
    reason, status, actual_start_at, actual_end_at, actual_minutes, payable_minutes,
    payroll_month, requested_by_id, reviewed_by_id, reviewed_at
)
SELECT (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-SLS01'),
       CURRENT_DATE - 5,
       (CURRENT_DATE - 5 + TIME '18:00'), (CURRENT_DATE - 5 + TIME '21:00'), 180,
       'ปิดยอดขายปลายเดือน (Demo)', 'APPROVED',
       (CURRENT_DATE - 5 + TIME '18:00'), (CURRENT_DATE - 5 + TIME '21:00'), 180, 180,
       date_trunc('month', CURRENT_DATE)::date,
       (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-SLS01'),
       (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-HR01'),
       now() - INTERVAL '4 days'
WHERE NOT EXISTS (
    SELECT 1 FROM hr.overtime_request ot
    JOIN hr.employee e ON e.employee_id = ot.employee_id
    WHERE e.employee_code = 'DEMO-SLS01' AND ot.reason = 'ปิดยอดขายปลายเดือน (Demo)'
);

-- ---------------------------------------------------------------------
-- Commission: one submitted (pending sales-manager approval) + one
-- already-approved historical record, tied to the closed demo ticket.
-- ---------------------------------------------------------------------
INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount)
VALUES
    ('DEMO-INV-0001', CURRENT_DATE - 3,  350000.00),
    ('DEMO-INV-0002', CURRENT_DATE - 20, 180000.00)
ON CONFLICT (invoice_number) DO NOTHING;

INSERT INTO sales.commission_record (
    invoice_id, source_ticket_id, sales_rep_id, submitted_by_id,
    status, payroll_month, actual_received, commissionable_base
)
SELECT (SELECT invoice_id FROM sales.invoice_details WHERE invoice_number = 'DEMO-INV-0001'),
       (SELECT ticket_id FROM sales.ticket WHERE code = 'DEMO-TKT-05'),
       (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-SLS01'),
       (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-SLS01'),
       'SUBMITTED', date_trunc('month', CURRENT_DATE)::date, 350000.00, 350000.00
WHERE NOT EXISTS (
    SELECT 1 FROM sales.commission_record
    WHERE invoice_id = (SELECT invoice_id FROM sales.invoice_details WHERE invoice_number = 'DEMO-INV-0001')
);

INSERT INTO sales.commission_record (
    invoice_id, sales_rep_id, submitted_by_id,
    status, payroll_month, actual_received, commissionable_base,
    approved_by_id, approved_at
)
SELECT (SELECT invoice_id FROM sales.invoice_details WHERE invoice_number = 'DEMO-INV-0002'),
       (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-SLS01'),
       (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-SLS01'),
       'APPROVED', date_trunc('month', CURRENT_DATE - 20)::date, 180000.00, 180000.00,
       (SELECT employee_id FROM hr.employee WHERE employee_code = 'DEMO-MGR01'),
       now() - INTERVAL '2 days'
WHERE NOT EXISTS (
    SELECT 1 FROM sales.commission_record
    WHERE invoice_id = (SELECT invoice_id FROM sales.invoice_details WHERE invoice_number = 'DEMO-INV-0002')
);

-- ---------------------------------------------------------------------
-- Attendance: a few raw punches so the Attendance page has something to
-- show for the demo employee and demo sales rep.
-- ---------------------------------------------------------------------
INSERT INTO hr.attendance_punch (site_code, employee_id, badge_code, punch_time, work_date, punch_source, ingest_method)
SELECT 'SHOWROOM', e.employee_id, e.employee_code, p.punch_time, p.punch_time::date, 'MANUAL', 'MANUAL_ENTRY'
FROM hr.employee e
JOIN (VALUES
    ('DEMO-EMP01', (CURRENT_DATE - 1 + TIME '08:55')::timestamptz),
    ('DEMO-EMP01', (CURRENT_DATE - 1 + TIME '18:05')::timestamptz),
    ('DEMO-EMP01', (CURRENT_DATE     + TIME '09:10')::timestamptz),
    ('DEMO-SLS01', (CURRENT_DATE - 1 + TIME '08:50')::timestamptz),
    ('DEMO-SLS01', (CURRENT_DATE - 1 + TIME '18:20')::timestamptz),
    ('DEMO-SLS01', (CURRENT_DATE     + TIME '08:58')::timestamptz)
) AS p(employee_code, punch_time) ON p.employee_code = e.employee_code
WHERE NOT EXISTS (
    SELECT 1 FROM hr.attendance_punch ap
    WHERE ap.badge_code = e.employee_code AND ap.punch_time = p.punch_time
);
