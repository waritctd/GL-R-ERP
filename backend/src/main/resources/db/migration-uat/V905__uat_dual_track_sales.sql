-- =====================================================================
-- UAT BRANCH ONLY: seeds fixtures for the post-quotation dual-track sales
-- flow (payment_status / fulfillment_status, V37-V39 on main, forward-
-- ported to uat per docs/agent-handoffs/40_uat-merge-main-sales-flow.md).
--
-- CRITICAL: this is a NEW migration. Never edit the already-applied
-- V900-V904 (breaks Flyway checksum on the live, already-seeded UAT DB).
-- V905 > V904 so it applies in natural version order; `uat`'s
-- application-uat.yml also sets spring.flyway.out-of-order=true so this
-- (and V37-V39 from main) apply cleanly on top of an already-V904'd
-- database regardless of ordering relative to main's own migrations.
--
-- Test cases supported: TKT-10 (confirm customer), TKT-11 (issue deposit
-- notice / payment track), TKT-12 (confirm deposit paid), TKT-13 (issue
-- import request / fulfillment track), TKT-14 (IR-sent -> shipping ->
-- goods-received, incl. the auto AWAITING_FINAL_PAYMENT flip),
-- TKT-15 (confirm final payment -> FULLY_PAID), TKT-16 (download the
-- remaining invoice for a quotation_issued ticket), TKT-17 (CEO manual
-- price override on an item, requires status=price_proposed per
-- TicketService.overrideItemPrice -- NOT exercised by the entry-point/
-- mid-flow fixtures below, which are quotation_issued+; a price_proposed
-- fixture item carries manual_price/manual_override_reason pre-set
-- instead, to exercise the *columns* without violating the state
-- precondition), TKT-18 (unit_basis=SQM item round-trip).
--
-- FK resolution: every row resolves customer/ticket/employee references
-- by BUSINESS KEY via subquery (customer name / ticket code /
-- employee_code) -- no hardcoded generated IDs, following V903's
-- convention exactly.
--
-- Schema notes (checked against V6, V16, V17, V24, V27, V28, V29, V37,
-- V38, V39 before writing this file):
--   * sales.ticket.status CHECK (chk_ticket_status, V6 as extended by
--     V17) allows 'quotation_issued' verbatim -- used for all 3 tickets
--     below (V39's payment_status/fulfillment_status columns are
--     orthogonal, nullable, NOT covered by any CHECK constraint, so the
--     exact state-machine string values from TicketService are used
--     directly: CUSTOMER_CONFIRMED, DEPOSIT_NOTICE_ISSUED, DEPOSIT_PAID,
--     AWAITING_FINAL_PAYMENT, FULLY_PAID / IR_ISSUED, IR_SENT, SHIPPING,
--     GOODS_RECEIVED).
--   * sales.ticket_item (V6, renamed by V8/V9, extended by V22/V24/V37/
--     V38) columns used here: brand/model/color/texture/size/qty/unit/
--     proposed_price/approved_price/currency (V6/V8/V9/V22), qty_sqm
--     (V24), unit_basis (V37, CHECK IN ('PIECE','SQM'), NOT NULL DEFAULT
--     'PIECE'), manual_price/manual_override_reason (V38, both nullable,
--     no CHECK). factory left NULL as V903 does (no factory column in
--     source data for these fixtures either).
--   * sales.quotation (V6, extended by V27/V28): number (UNIQUE),
--     issued_by (NOT NULL FK), doc_status (V27, DRAFT/ISSUED/SUPERSEDED
--     convention), quotation_version (V28, default 1). All 3 fixture
--     tickets get an ISSUED quotation row (required to reach
--     quotation_issued in the first place).
--   * sales.deposit_notice (V17, renamed by V29) columns used: ticket_id,
--     doc_type='DEPOSIT_NOTICE', version, doc_number, issue_date, status
--     ('ISSUED'), customer_name, deposit_amount, issued_by_id,
--     issued_by_name -- same subset V903 already seeds for UAT-TKT-04.
--     Mid-flow A's deposit notice models the state after
--     DepositNoticeService.issue() (status ISSUED, doc_number assigned).
--   * No sales.catalog / sales.project / sales.contact rows are needed --
--     ticket_item has no catalog_id FK (per V903's note) and
--     project_id/contact_id are nullable optional FKs on sales.ticket
--     (V23), left NULL here exactly as V903 leaves them for its own rows.
--
-- Determinism: no random(). Every row is a literal VALUES list keyed on
-- customer name / ticket_code / employee_code. WHERE NOT EXISTS guards
-- make re-apply against an already-seeded database a no-op.
-- =====================================================================

SET search_path = sales, hr, public;

-- ---------------------------------------------------------------------
-- A. CUSTOMERS (2 new, distinct from V16's 4 samples and V903's 8 rows).
-- ---------------------------------------------------------------------
INSERT INTO sales.customer (name, address, phone, branch)
SELECT v.name, v.address, v.phone, 'สำนักงานใหญ่'
FROM (VALUES
    ('บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด', '15 ถ.บางนา-ตราด กรุงเทพฯ',  '081-234-5679'),
    ('หจก. อีสเทิร์นวิลล่า',                  '27 ถ.สุขาภิบาล 2 กรุงเทพฯ',  '081-234-5680')
) AS v(name, address, phone)
WHERE NOT EXISTS (SELECT 1 FROM sales.customer c WHERE c.name = v.name);

-- ---------------------------------------------------------------------
-- B. TICKETS (3 new: entry-point, mid-flow A, mid-flow B). All reach
--    status='quotation_issued'; payment_status/fulfillment_status set
--    per fixture below (both NULL for the entry-point ticket).
-- ---------------------------------------------------------------------
INSERT INTO sales.ticket (
    code, title, status, priority, created_by, assigned_to, customer_name, customer_id, note,
    payment_status, fulfillment_status
)
SELECT v.code, v.title, v.status, v.priority,
       creator.employee_id, assignee.employee_id,
       cust.name, cust.customer_id, v.notes,
       v.payment_status, v.fulfillment_status
FROM (VALUES
    ('UAT-TKT-06', 'กระเบื้องปูพื้นโครงการดวงตะวัน F (entry-point)',   'quotation_issued', 'NORMAL',
        'GLR-0005', 'GLR-0004', 'บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด',
        'Entry-point: quotation just issued, both dual-track fields still NULL -- walk the full flow from here',
        NULL::varchar, NULL::varchar),
    ('UAT-TKT-07', 'กระเบื้องผนังโครงการอีสเทิร์นวิลล่า G (mid-flow A)', 'quotation_issued', 'NORMAL',
        'GLR-0006', 'GLR-0004', 'หจก. อีสเทิร์นวิลล่า',
        'Mid-flow A: deposit paid, import request issued -- realistic in-progress state',
        'DEPOSIT_PAID', 'IR_ISSUED'),
    ('UAT-TKT-08', 'กระเบื้องสระว่ายน้ำโครงการดวงตะวัน H (mid-flow B)',  'quotation_issued', 'NORMAL',
        'GLR-0005', 'GLR-0004', 'บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด',
        'Mid-flow B: goods received, awaiting final payment -- ready for final-payment/close',
        'AWAITING_FINAL_PAYMENT', 'GOODS_RECEIVED')
) AS v(code, title, status, priority, created_by_code, assigned_to_code, customer_name, notes,
       payment_status, fulfillment_status)
JOIN hr.employee creator ON creator.employee_code = v.created_by_code
LEFT JOIN hr.employee assignee ON assignee.employee_code = v.assigned_to_code
JOIN sales.customer cust ON cust.name = v.customer_name
WHERE NOT EXISTS (SELECT 1 FROM sales.ticket t WHERE t.code = v.code);

-- ---------------------------------------------------------------------
-- C. TICKET ITEMS (5 rows across the 3 tickets). Every item carries
--    approved_price (required for quotation_issued tickets, per
--    TicketService.generateQuotation's total calc). UAT-TKT-06 gets one
--    unit_basis='SQM' item (with qty_sqm) and one item with
--    manual_price/manual_override_reason pre-set (TKT-17's column
--    coverage, since overrideItemPrice() itself requires
--    status=price_proposed and can't be exercised live on a
--    quotation_issued fixture).
-- ---------------------------------------------------------------------
INSERT INTO sales.ticket_item (
    ticket_id, model, brand, texture, size, color, qty, qty_sqm, unit, unit_basis,
    proposed_price, approved_price, manual_price, manual_override_reason
)
SELECT t.ticket_id, v.model, v.brand, v.texture, v.size, v.color, v.qty, v.qty_sqm, v.unit, v.unit_basis,
       v.proposed_price, v.approved_price, v.manual_price, v.manual_override_reason
FROM (VALUES
    -- UAT-TKT-06 (entry-point): one PIECE item, one SQM item with a manual override.
    ('UAT-TKT-06', 'GRT-6060-BG', 'กระเบื้องแกรนิตโต้', 'ด้าน', '60x60', 'เบจ',
        180::numeric, NULL::numeric,      'แผ่น',    'PIECE',
        1300::numeric, 1250::numeric, NULL::numeric, NULL::text),
    -- qty is NOT NULL at the DB level (V6) even for SQM-basis items; the real
    -- frontend (TicketCreateModal.jsx) always sends `Number(item.qty) || 0` and
    -- never a literal null, even when unit_basis='SQM' and qty_sqm is the
    -- primary quantity -- 0 here mirrors that real-app convention exactly.
    ('UAT-TKT-06', 'POOL-2525-BL', 'กระเบื้องสระว่ายน้ำ', 'ด้าน', '25x25', 'ฟ้า',
        0::numeric,    45.5::numeric,     'ตร.ม.',   'SQM',
        900::numeric,  880::numeric,  850::numeric,  'CEO override: ลดราคาให้ลูกค้ารายใหญ่ตามข้อตกลงพิเศษ'),
    -- UAT-TKT-07 (mid-flow A): single PIECE item.
    ('UAT-TKT-07', 'WALL-3060-GY', 'กระเบื้องผนัง', 'มัน', '30x60', 'เทา',
        220::numeric, NULL::numeric,      'แผ่น',    'PIECE',
        780::numeric,  750::numeric,  NULL::numeric, NULL::text),
    -- UAT-TKT-08 (mid-flow B): two PIECE items.
    ('UAT-TKT-08', 'POOL-2525-WH', 'กระเบื้องสระว่ายน้ำ', 'ด้าน', '25x25', 'ขาว',
        160::numeric, NULL::numeric,      'แผ่น',    'PIECE',
        850::numeric,  820::numeric,  NULL::numeric, NULL::text),
    ('UAT-TKT-08', 'GRT-8080-BG', 'กระเบื้องแกรนิตโต้', 'มัน', '80x80', 'เบจ',
        90::numeric,  NULL::numeric,      'แผ่น',    'PIECE',
        1450::numeric, 1400::numeric, NULL::numeric, NULL::text)
) AS v(ticket_code, model, brand, texture, size, color, qty, qty_sqm, unit, unit_basis,
       proposed_price, approved_price, manual_price, manual_override_reason)
JOIN sales.ticket t ON t.code = v.ticket_code
WHERE NOT EXISTS (
    SELECT 1 FROM sales.ticket_item ti
    WHERE ti.ticket_id = t.ticket_id AND ti.model = v.model AND ti.texture = v.texture AND ti.size = v.size
);

-- ---------------------------------------------------------------------
-- D. QUOTATIONS (1 ISSUED quotation per ticket -- required for a ticket
--    to actually be in status='quotation_issued').
-- ---------------------------------------------------------------------
INSERT INTO sales.quotation (ticket_id, number, issued_by, doc_status, quotation_version, total_amount)
SELECT t.ticket_id, v.number, issuer.employee_id, 'ISSUED', 1, v.total_amount
FROM (VALUES
    ('UAT-TKT-06', 'QN-2026-00010', 'GLR-0004', 305150.00::numeric),  -- 180*1250 + 45.5*880 (approved_price)
    ('UAT-TKT-07', 'QN-2026-00011', 'GLR-0004', 165000.00::numeric),  -- 220*750
    ('UAT-TKT-08', 'QN-2026-00012', 'GLR-0004', 257200.00::numeric)   -- 160*820 + 90*1400
) AS v(ticket_code, number, issuer_code, total_amount)
JOIN sales.ticket t ON t.code = v.ticket_code
JOIN hr.employee issuer ON issuer.employee_code = v.issuer_code
WHERE NOT EXISTS (SELECT 1 FROM sales.quotation q WHERE q.number = v.number);

-- ---------------------------------------------------------------------
-- E. DEPOSIT NOTICE (mid-flow A only, UAT-TKT-07): an ISSUED deposit
--    notice matching its payment_status=DEPOSIT_PAID.
-- ---------------------------------------------------------------------
INSERT INTO sales.deposit_notice (
    ticket_id, doc_type, version, doc_number, issue_date, status,
    customer_name, deposit_amount, issued_by_id, issued_by_name
)
SELECT t.ticket_id, 'DEPOSIT_NOTICE', 1, 'DN-2026-00005', DATE '2026-07-01', 'ISSUED',
       cust.name, 82500.00::numeric, issuer.employee_id,
       TRIM(CONCAT_WS(' ', issuer.first_name_th, issuer.last_name_th))
FROM sales.ticket t
JOIN sales.customer cust ON cust.name = 'หจก. อีสเทิร์นวิลล่า'
JOIN hr.employee issuer ON issuer.employee_code = 'GLR-0004'
WHERE t.code = 'UAT-TKT-07'
AND NOT EXISTS (
    SELECT 1 FROM sales.deposit_notice dn
    WHERE dn.ticket_id = t.ticket_id AND dn.doc_number = 'DN-2026-00005'
);
