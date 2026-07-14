-- =====================================================================
-- UAT BRANCH ONLY: materializes the authored UAT sales/CRM dataset from
-- `ERP Documentation/UAT Deliverables/UAT_Test_Data.xlsx`
-- (Customers, Tickets, TicketItems, Documents, Commissions, Notifications
-- sheets, exported as scratch/*.csv for this migration). Applies only
-- under the `uat` Spring profile alongside V900 (employees) / V901
-- (attendance) / V902 (leave/OT). Landing state so testers open the app
-- onto realistic mid-flow tickets/commissions instead of an empty sales
-- module.
--
-- Test cases supported: TKT-01..TKT-05 (draft / submitted / price_proposed
-- / closed / in_review-after-rejection ticket lifecycle states), DOC-01
-- (issued deposit notice), DOC-02/03/04 (draft quotation preview/edit/
-- issue flow), DOC-05 (deposit-notice revision: same doc_number, version
-- 1 retained + version 2 bump), COM-01 (approved commission, full
-- manager+CEO dual-approval chain per V35), COM-02 (submitted commission
-- pending manager approval), COM-03 (second tier-band approved example),
-- COM-04 (clawback reversing an approved commission), NOTIF-01..NOTIF-04
-- (ticket-bound notifications, incl. the NOTIF-03 cross-user read-denial
-- fixture: notification owned by GLR-0007, must NOT be mark-readable by
-- any other user).
--
-- FK resolution: every row below resolves customer/ticket/employee
-- references by BUSINESS KEY via subquery (customer name, ticket code,
-- employee_code) -- no hardcoded generated IDs anywhere in this file.
--
-- Schema notes / simplifications (see docs/decisions/quotation-deposit-
-- invoice-model.md and the V6/V16/V17/V29/V35 migrations for the full
-- DDL this was checked against):
--   * sales.customer (V16) has NO customer_code business-key column --
--     only customer_id/name/tax_id/address/branch/phone, and no UNIQUE
--     constraint on name. The CSV's CUST-00N codes are therefore a
--     CSV-authoring convenience only; this migration resolves customers
--     by exact `name` match (all 8 authored names are distinct from each
--     other and from V16's 4 sample customers, so this is unambiguous
--     without adding a column to the frozen sales schema).
--   * sales.ticket.status CHECK (chk_ticket_status, from V6 as extended
--     by V17) allows: 'draft','submitted','in_review','price_proposed',
--     'approved','rejected','quotation_issued','closed','cancelled',
--     'document_issued'. The CSV's draft/submitted/price_proposed/closed/
--     in_review tokens are all valid AS-IS (lowercase, verbatim) --
--     confirmed directly against the CHECK constraint text, no mapping
--     needed. ticket.code is free-form VARCHAR(20) UNIQUE (no format
--     CHECK), so the CSV's UAT-TKT-0N codes are used verbatim instead of
--     the app's runtime PR-YYYY-NNNN generator format, to keep UAT rows
--     obviously identifiable. Both customer_name (legacy snapshot column)
--     and customer_id (V23 FK) are populated for realism.
--   * sales.ticket_item (V6, renamed by V8/V9, extended by V22/V24) final
--     columns are model/brand/texture/size/color/qty/unit/proposed_price/
--     approved_price/factory/qty_sqm -- a 1:1 match for the CSV's
--     columns; no sales.catalog join needed (ticket_item has no
--     catalog_id FK, factory is free text and left NULL here since the
--     CSV doesn't carry a factory column).
--   * sales.deposit_notice / sales.quotation (V17, renamed by V29;
--     extended by V27/V28) have NO status CHECK constraint -- status /
--     doc_status are free-text VARCHAR with DRAFT/ISSUED/SUPERSEDED as
--     the documented convention (V17 top comment). CSV status tokens
--     issued/draft are upper-cased to ISSUED/DRAFT to match that
--     convention. version (deposit_notice) / quotation_version
--     (quotation) is the real revision counter; the CSV's revision_no
--     1->2 for QN-2026-00001 is modeled as two deposit_notice rows with
--     version 1 and version 2. The CSV's note that both rows share the
--     same doc_number ("original retained") does NOT match the real
--     schema: main's V41__deposit_notice_integrity.sql enforces
--     doc_number to be globally unique (ux_deposit_notice_doc_number),
--     matching DepositNoticeRepository's real issue() behavior, which
--     always assigns a brand-new sequential doc_number to each issued
--     revision. So version 2 here is given the next unused doc_number
--     (QN-2026-00003) instead of reusing QN-2026-00001 -- confirmed via
--     a migration-replay run (docs/agent-handoffs/44_nft-migration-replay.md
--     on main) that the CSV-literal same-doc_number modeling violates
--     that constraint. BOTH rows still keep the CSV's literal status
--     ISSUED (not reinterpreting version 1 as SUPERSEDED) -- the version
--     column alone is enough to distinguish revision history for DOC-05;
--     only the doc_number collision itself was a real bug, not the
--     status choice. sales.quotation.issued_by is NOT NULL
--     even for a draft; the ticket's assigned rep is used since the CSV
--     gives no separate issuer for the draft quotation. quotation.number
--     reuses the CSV's doc_number (QN-2026-00002) as the required UNIQUE
--     `number` column.
--   * sales.invoice_details / sales.commission_record (V12, extended by
--     V35 dual-approval columns): the app NEVER stores a commission
--     amount -- CommissionCalculator.progressiveCommission() always
--     recomputes it live from commissionable_base against sales.tier_config
--     (a monthly, per-rep, progressive/cumulative calculation, plus a
--     1.07 VAT divisor when derived from bank_fees/suspense_vat/etc). To
--     honor "use the CSV's commission_amount verbatim, don't invent
--     business-logic math" for a column that structurally doesn't exist,
--     this migration sets bank_fees/suspense_vat/transport_fee/cut_fee/
--     shortfall = 0 and commissionable_base = actual_received =
--     gross_amount verbatim, which was verified to reproduce the CSV's
--     stated commission_amount EXACTLY under the app's own flat-tier-rate
--     math (gross_amount * tier rate, each row being the sole commission
--     for its rep in its payroll_month): 240000*0.25%=600 (INV-0001),
--     620000*0.75%=4650 (INV-0002), 310000*0.50%=1550 (INV-0003) -- no
--     commission math was invented, only the zero-deduction/no-VAT-
--     divisor simplification documented here. submitted_by_id has no
--     CSV source column; the sales rep is used as their own submitter
--     (self-submitted), the closest faithful reading. UAT-INV-0001's
--     APPROVED row is given the full V35 dual-approval chain (manager_
--     approved_by = the ticket's sales manager persona GLR-0007, then
--     ceo_approved_by = GLR-0001), with approved_by_id/approved_at also
--     set to the CEO per CommissionRepository.ceoApprove()'s real SQL.
--     UAT-INV-0003 is likewise fully dual-approved. UAT-INV-0002 is left
--     status SUBMITTED (no approvals yet) per the CSV note "Pending
--     sales_manager approval". The CSV's CLAWBACK row (UAT-INV-0001-CB)
--     is modeled exactly like CommissionRepository.createClawback(): it
--     reuses UAT-INV-0001's SAME invoice_details row (not a new invoice),
--     kind='CLAWBACK', status='APPROVED' (clawbacks are auto-approved in
--     the real flow), negative actual_received/commissionable_base, and
--     cancellation_of_id pointing back at the original commission_record.
--     Note: Commissions.csv assigns UAT-INV-0003 to sales_rep_emp_code
--     GLR-0010, who is flagged has_commission=N in the authored
--     Employees.csv -- a discrepancy between the two authored sheets.
--     Commissions.csv (the more specific sheet for this table) is
--     followed verbatim per the "seed the CSV, don't fix data" mandate;
--     not silently corrected to a different rep.
--   * Ticket-bound notifications live in sales.notification (V6:
--     employee_id/ticket_id/type/message/is_read), NOT hr.notification
--     (V36, which is HR-only and has no ticket_id column at all) -- the
--     authored rows are explicitly ticket-bound (owner + ticket_code +
--     type SUBMITTED/PRICE_PROPOSED/APPROVED/REJECTED), confirming
--     sales.notification is the correct target. notification_id is
--     GENERATED ALWAYS AS IDENTITY; the CSV's notification_id 1-4 are
--     just row labels for cross-referencing within the CSV, not literal
--     PKs to preserve, so they are NOT forced via OVERRIDING SYSTEM
--     VALUE -- rows are resolved for any future reference by
--     (owner employee_code, ticket_code, type) instead.
--
-- Determinism: no random(). Every row is a literal VALUES list keyed on
-- customer name / ticket_code / employee_code straight from the CSVs.
-- Re-running this migration is a no-op in practice (Flyway applies each
-- versioned migration exactly once); NOT EXISTS / ON CONFLICT guards
-- below additionally make manual re-apply against an already-seeded
-- database safe.
-- =====================================================================

SET search_path = sales, hr, public;

-- ---------------------------------------------------------------------
-- A. CUSTOMERS (8 rows, Customers.csv). No customer_code column exists
--    (see note above) -- resolved elsewhere in this file by exact name.
-- ---------------------------------------------------------------------
INSERT INTO sales.customer (name, address, phone, branch)
SELECT v.name, v.address, v.phone, 'สำนักงานใหญ่'
FROM (VALUES
    ('บริษัท สยามคอนสตรัคชั่น จำกัด',       '123 ถ.สุขุมวิท กรุงเทพฯ',              '081-234-5671'),
    ('หจก. บ้านสวยกรุ๊ป',                   '45 ถ.รัชดาภิเษก กรุงเทพฯ',             '081-234-5672'),
    ('บริษัท พูลวิลล่า จำกัด',              '78 ถ.บางนา-ตราด สมุทรปราการ',          '081-234-5673'),
    ('ร้านกระเบื้องรุ่งโรจน์',               '12 ถ.เพชรเกษม กรุงเทพฯ',               '081-234-5674'),
    ('บริษัท เมโทรพร็อพเพอร์ตี้ จำกัด',      '99 ถ.พระราม 9 กรุงเทพฯ',               '081-234-5675'),
    ('หจก. ช่างไทยการช่าง',                 '33 ถ.ลาดพร้าว กรุงเทพฯ',               '081-234-5676'),
    ('บริษัท แกรนด์วิลเลจ จำกัด',            '56 ถ.เกษตร-นวมินทร์ กรุงเทพฯ',          '081-234-5677'),
    ('บริษัท ไทยรีสอร์ท แอนด์ สปา จำกัด',    '8 หาดจอมเทียน ชลบุรี',                 '081-234-5678')
) AS v(name, address, phone)
WHERE NOT EXISTS (SELECT 1 FROM sales.customer c WHERE c.name = v.name);
-- name/address/phone map directly to sales.customer columns. contact_name/
-- email from the CSV are NOT modeled here: sales.customer has no such
-- columns -- those live on sales.contact (V23), which is a separate
-- customer_id-keyed table not populated by this migration, to keep this
-- seed to the columns the CSV actually authors against sales.customer
-- itself. The notes column likewise has no target field on sales.customer
-- and is intentionally dropped, matching the "closest faithful subset"
-- guidance rather than inventing a notes column.

-- ---------------------------------------------------------------------
-- B. TICKETS (5 rows, Tickets.csv). Status tokens used verbatim (see
--    top-of-file note); customer resolved by name; created_by/
--    assigned_to resolved by employee_code (assigned_to nullable, blank
--    for the still-draft UAT-TKT-01).
-- ---------------------------------------------------------------------
INSERT INTO sales.ticket (
    code, title, status, priority, created_by, assigned_to, customer_name, customer_id, note
)
SELECT v.code, v.title, v.status, v.priority,
       creator.employee_id, assignee.employee_id,
       cust.name, cust.customer_id, v.notes
FROM (VALUES
    ('UAT-TKT-01', 'กระเบื้องปูพื้นโครงการคอนโด A',                 'draft',          'NORMAL',
        'GLR-0005', NULL,        'บริษัท สยามคอนสตรัคชั่น จำกัด',      'Draft ticket, not yet submitted'),
    ('UAT-TKT-02', 'กระเบื้องปูผนังโครงการบ้านจัดสรร B',             'submitted',      'NORMAL',
        'GLR-0005', 'GLR-0004',  'หจก. บ้านสวยกรุ๊ป',                 'Submitted, awaiting pickup'),
    ('UAT-TKT-03', 'กระเบื้องสระว่ายน้ำโครงการรีสอร์ท C',            'price_proposed', 'HIGH',
        'GLR-0006', 'GLR-0004',  'บริษัท ไทยรีสอร์ท แอนด์ สปา จำกัด', 'Mid-flow: price proposed, awaiting sales_manager/ceo approval'),
    ('UAT-TKT-04', 'กระเบื้องปูพื้นโครงการคอนโด D',                  'closed',         'NORMAL',
        'GLR-0005', 'GLR-0004',  'บริษัท เมโทรพร็อพเพอร์ตี้ จำกัด',   'Closed happy-path ticket, feeds a document + commission'),
    ('UAT-TKT-05', 'กระเบื้องบ้านเดี่ยวโครงการ E (rejection path)',  'in_review',      'LOW',
        'GLR-0006', 'GLR-0004',  'บริษัท แกรนด์วิลเลจ จำกัด',         'Price was proposed then rejected by sales_manager, sent back to in_review for re-pricing')
) AS v(code, title, status, priority, created_by_code, assigned_to_code, customer_name, notes)
JOIN hr.employee creator ON creator.employee_code = v.created_by_code
LEFT JOIN hr.employee assignee ON assignee.employee_code = v.assigned_to_code
JOIN sales.customer cust ON cust.name = v.customer_name
WHERE NOT EXISTS (SELECT 1 FROM sales.ticket t WHERE t.code = v.code);

-- ---------------------------------------------------------------------
-- C. TICKET ITEMS (7 rows, TicketItems.csv). Resolved to their parent
--    ticket by code. proposed_price/approved_price left NULL where the
--    CSV leaves them blank (draft / not-yet-priced items).
-- ---------------------------------------------------------------------
INSERT INTO sales.ticket_item (
    ticket_id, model, brand, texture, size, color, qty, unit, proposed_price, approved_price
)
SELECT t.ticket_id, v.model, v.brand, v.texture, v.size, v.color, v.qty, v.unit, v.proposed_price, v.approved_price
FROM (VALUES
    ('UAT-TKT-01', 'GRT-6060-WH',   'กระเบื้องแกรนิตโต้', 'ด้าน', '60x60', 'ขาว', 120::numeric, 'แผ่น', NULL::numeric,      NULL::numeric),
    ('UAT-TKT-01', 'GRT-3030-WH',   'กระเบื้องแกรนิตโต้', 'ด้าน', '30x30', 'ขาว', 60::numeric,  'แผ่น', NULL::numeric,      NULL::numeric),
    ('UAT-TKT-02', 'WALL-3060-GY',  'กระเบื้องผนัง',       'มัน',  '30x60', 'เทา', 80::numeric,  'แผ่น', NULL::numeric,      NULL::numeric),
    ('UAT-TKT-03', 'POOL-2525-BL',  'กระเบื้องสระว่ายน้ำ', 'ด้าน', '25x25', 'ฟ้า', 300::numeric, 'แผ่น', 850::numeric,       NULL::numeric),
    ('UAT-TKT-03', 'POOL-2525-WH',  'กระเบื้องสระว่ายน้ำ', 'ด้าน', '25x25', 'ขาว', 100::numeric, 'แผ่น', 850::numeric,       NULL::numeric),
    ('UAT-TKT-04', 'GRT-8080-BG',   'กระเบื้องแกรนิตโต้', 'มัน',  '80x80', 'เบจ', 150::numeric, 'แผ่น', 1450::numeric,      1350::numeric),
    ('UAT-TKT-05', 'GRT-6060-WH',   'กระเบื้องแกรนิตโต้', 'ด้าน', '60x60', 'ขาว', 200::numeric, 'แผ่น', 980::numeric,       NULL::numeric)
) AS v(ticket_code, model, brand, texture, size, color, qty, unit, proposed_price, approved_price)
JOIN sales.ticket t ON t.code = v.ticket_code
WHERE NOT EXISTS (
    SELECT 1 FROM sales.ticket_item ti
    WHERE ti.ticket_id = t.ticket_id AND ti.model = v.model AND ti.texture = v.texture AND ti.size = v.size
);

-- ---------------------------------------------------------------------
-- D. DOCUMENTS (Documents.csv): 2 deposit_notice rows (version 1 -> 2,
--    the DOC-05 revision fixture) + 1 draft quotation (DOC-02/03/04
--    preview/edit/issue fixture). Version 2 gets its own doc_number
--    (QN-2026-00003) rather than the CSV-literal QN-2026-00001 reused
--    from version 1 -- see the doc_number note in the file header.
-- ---------------------------------------------------------------------
INSERT INTO sales.deposit_notice (
    ticket_id, doc_type, version, doc_number, issue_date, status,
    customer_name, issued_by_id, issued_by_name
)
SELECT t.ticket_id, 'DEPOSIT_NOTICE', v.version, v.doc_number, v.issue_date, v.status,
       cust.name, issuer.employee_id,
       TRIM(CONCAT_WS(' ', issuer.first_name_th, issuer.last_name_th))
FROM (VALUES
    ('UAT-TKT-04', 1, 'QN-2026-00001', DATE '2026-06-20', 'ISSUED', 'Original issued document (XLSX)'),
    ('UAT-TKT-04', 2, 'QN-2026-00003', DATE '2026-06-25', 'ISSUED', 'Revision bumping revision_no; original retained; own doc_number (main V41 uniqueness)')
) AS v(ticket_code, version, doc_number, issue_date, status, notes)
JOIN sales.ticket t ON t.code = v.ticket_code
JOIN sales.customer cust ON cust.name = 'บริษัท เมโทรพร็อพเพอร์ตี้ จำกัด'
JOIN hr.employee issuer ON issuer.employee_code = 'GLR-0004'
WHERE NOT EXISTS (
    SELECT 1 FROM sales.deposit_notice dn
    WHERE dn.ticket_id = t.ticket_id AND dn.doc_number = v.doc_number AND dn.version = v.version
);

-- Draft quotation for UAT-TKT-03 (no issue_date in the CSV -> issued_at
-- keeps its schema default of now(); doc_status left DRAFT).
INSERT INTO sales.quotation (ticket_id, number, issued_by, doc_status, quotation_version)
SELECT t.ticket_id, 'QN-2026-00002', issuer.employee_id, 'DRAFT', 1
FROM sales.ticket t
JOIN hr.employee issuer ON issuer.employee_code = 'GLR-0004'
WHERE t.code = 'UAT-TKT-03'
AND NOT EXISTS (SELECT 1 FROM sales.quotation q WHERE q.number = 'QN-2026-00002');

-- ---------------------------------------------------------------------
-- E. COMMISSIONS (Commissions.csv): 3 invoice_details rows (one per
--    distinct invoice_number, the CLAWBACK reuses UAT-INV-0001's row) +
--    4 commission_record rows (APPROVED w/ full V35 dual-approval chain,
--    SUBMITTED pending, second APPROVED tier example, CLAWBACK reversal).
--    gross_amount / commissionable_base / actual_received mapping and
--    the dual-approval columns are explained in the top-of-file note.
-- ---------------------------------------------------------------------
INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount)
SELECT v.invoice_number, v.invoice_date, v.gross_amount
FROM (VALUES
    ('UAT-INV-0001', DATE '2026-06-28', 240000::numeric),
    ('UAT-INV-0002', DATE '2026-07-03', 620000::numeric),
    ('UAT-INV-0003', DATE '2026-05-30', 310000::numeric)
) AS v(invoice_number, invoice_date, gross_amount)
WHERE NOT EXISTS (SELECT 1 FROM sales.invoice_details i WHERE i.invoice_number = v.invoice_number);

-- UAT-INV-0001: APPROVED, full manager+CEO chain (GLR-0007 -> GLR-0001).
INSERT INTO sales.commission_record (
    invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
    payroll_month, actual_received, commissionable_base,
    manager_approved_by, manager_approved_at, ceo_approved_by, ceo_approved_at,
    approved_by_id, approved_at
)
SELECT inv.invoice_id, t.ticket_id, rep.employee_id, rep.employee_id, 'SALE', 'APPROVED',
       DATE '2026-06-01', 240000::numeric, 240000::numeric,
       mgr.employee_id, TIMESTAMPTZ '2026-06-29 10:00:00+07',
       ceo.employee_id, TIMESTAMPTZ '2026-06-29 15:00:00+07',
       ceo.employee_id, TIMESTAMPTZ '2026-06-29 15:00:00+07'
FROM sales.invoice_details inv
JOIN sales.ticket t ON t.code = 'UAT-TKT-04'
JOIN hr.employee rep ON rep.employee_code = 'GLR-0005'
JOIN hr.employee mgr ON mgr.employee_code = 'GLR-0007'
JOIN hr.employee ceo ON ceo.employee_code = 'GLR-0001'
WHERE inv.invoice_number = 'UAT-INV-0001'
AND NOT EXISTS (
    SELECT 1 FROM sales.commission_record cr
    WHERE cr.invoice_id = inv.invoice_id AND cr.sales_rep_id = rep.employee_id AND cr.kind = 'SALE'
);

-- UAT-INV-0002: SUBMITTED, pending sales_manager approval (no approvals set).
INSERT INTO sales.commission_record (
    invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
    payroll_month, actual_received, commissionable_base
)
SELECT inv.invoice_id, t.ticket_id, rep.employee_id, rep.employee_id, 'SALE', 'SUBMITTED',
       DATE '2026-07-01', 620000::numeric, 620000::numeric
FROM sales.invoice_details inv
JOIN sales.ticket t ON t.code = 'UAT-TKT-04'
JOIN hr.employee rep ON rep.employee_code = 'GLR-0006'
WHERE inv.invoice_number = 'UAT-INV-0002'
AND NOT EXISTS (
    SELECT 1 FROM sales.commission_record cr
    WHERE cr.invoice_id = inv.invoice_id AND cr.sales_rep_id = rep.employee_id AND cr.kind = 'SALE'
);

-- UAT-INV-0003: APPROVED, second tier-band example, full chain
-- (GLR-0007 -> GLR-0001). NOTE: Commissions.csv assigns this to
-- GLR-0010, who Employees.csv flags has_commission=N -- seeded verbatim
-- per the top-of-file discrepancy note, not silently reassigned.
INSERT INTO sales.commission_record (
    invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
    payroll_month, actual_received, commissionable_base,
    manager_approved_by, manager_approved_at, ceo_approved_by, ceo_approved_at,
    approved_by_id, approved_at
)
SELECT inv.invoice_id, t.ticket_id, rep.employee_id, rep.employee_id, 'SALE', 'APPROVED',
       DATE '2026-05-01', 310000::numeric, 310000::numeric,
       mgr.employee_id, TIMESTAMPTZ '2026-05-29 10:00:00+07',
       ceo.employee_id, TIMESTAMPTZ '2026-05-29 15:00:00+07',
       ceo.employee_id, TIMESTAMPTZ '2026-05-29 15:00:00+07'
FROM sales.invoice_details inv
JOIN sales.ticket t ON t.code = 'UAT-TKT-04'
JOIN hr.employee rep ON rep.employee_code = 'GLR-0010'
JOIN hr.employee mgr ON mgr.employee_code = 'GLR-0007'
JOIN hr.employee ceo ON ceo.employee_code = 'GLR-0001'
WHERE inv.invoice_number = 'UAT-INV-0003'
AND NOT EXISTS (
    SELECT 1 FROM sales.commission_record cr
    WHERE cr.invoice_id = inv.invoice_id AND cr.sales_rep_id = rep.employee_id AND cr.kind = 'SALE'
);

-- UAT-INV-0001-CB: CLAWBACK reversing UAT-INV-0001, mirroring
-- CommissionRepository.createClawback() exactly -- same invoice_id as
-- the original, status always APPROVED, negative amounts,
-- cancellation_of_id pointing back at the original commission_record.
INSERT INTO sales.commission_record (
    invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
    payroll_month, actual_received, commissionable_base,
    cancellation_of_id, cancellation_reason,
    approved_by_id, approved_at
)
SELECT orig.invoice_id, orig.source_ticket_id, orig.sales_rep_id, ceo.employee_id, 'CLAWBACK', 'APPROVED',
       DATE '2026-07-01', -240000::numeric, -240000::numeric,
       orig.commission_id, 'Clawback: invoice UAT-INV-0001 cancelled, offsets future payroll',
       ceo.employee_id, TIMESTAMPTZ '2026-07-02 09:00:00+07'
FROM sales.commission_record orig
JOIN sales.invoice_details inv ON inv.invoice_id = orig.invoice_id
JOIN hr.employee ceo ON ceo.employee_code = 'GLR-0001'
WHERE inv.invoice_number = 'UAT-INV-0001' AND orig.kind = 'SALE'
AND NOT EXISTS (
    SELECT 1 FROM sales.commission_record cb
    WHERE cb.cancellation_of_id = orig.commission_id AND cb.kind = 'CLAWBACK'
);

-- ---------------------------------------------------------------------
-- F. NOTIFICATIONS (Notifications.csv): ticket-bound, seeded into
--    sales.notification (see top-of-file note on why not hr.notification).
-- ---------------------------------------------------------------------
INSERT INTO sales.notification (employee_id, ticket_id, type, message, is_read)
SELECT owner.employee_id, t.ticket_id, v.type, v.message, v.is_read
FROM (VALUES
    ('GLR-0004', 'UAT-TKT-02', 'SUBMITTED',      'Ticket UAT-TKT-02 รอการรับเรื่อง',                    FALSE),
    ('GLR-0007', 'UAT-TKT-03', 'PRICE_PROPOSED', 'Ticket UAT-TKT-03 รออนุมัติราคา',                      FALSE),
    ('GLR-0005', 'UAT-TKT-04', 'APPROVED',       'Ticket UAT-TKT-04 ราคาผ่านการอนุมัติแล้ว',             TRUE),
    ('GLR-0006', 'UAT-TKT-05', 'REJECTED',       'Ticket UAT-TKT-05 ราคาถูกตีกลับ กรุณาเสนอราคาใหม่',    FALSE)
) AS v(owner_code, ticket_code, type, message, is_read)
JOIN hr.employee owner ON owner.employee_code = v.owner_code
JOIN sales.ticket t ON t.code = v.ticket_code
WHERE NOT EXISTS (
    SELECT 1 FROM sales.notification n
    WHERE n.employee_id = owner.employee_id AND n.ticket_id = t.ticket_id AND n.type = v.type
);
