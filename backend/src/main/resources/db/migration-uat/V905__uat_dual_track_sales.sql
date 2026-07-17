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
--   * No sales.catalog / customers.project / customers.contact rows are needed --
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
INSERT INTO customers.customer (name, address, phone, branch)
SELECT v.name, v.address, v.phone, 'สำนักงานใหญ่'
FROM (VALUES
    ('บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด', '15 ถ.บางนา-ตราด กรุงเทพฯ',  '081-234-5679'),
    ('หจก. อีสเทิร์นวิลล่า',                  '27 ถ.สุขาภิบาล 2 กรุงเทพฯ',  '081-234-5680')
) AS v(name, address, phone)
WHERE NOT EXISTS (SELECT 1 FROM customers.customer c WHERE c.name = v.name);

-- ---------------------------------------------------------------------
-- B. TICKETS (9 rows). Combined with V903's UAT-TKT-01..05, these cover
--    every final 14-stage deal-pipeline bucket exactly enough for UAT:
--    01 LEAD_APPROACH, 02 PRESENTATION, 03 SPEC_APPROVED,
--    05 QUOTE_DESIGN_SIDE, 09 OWNER_SIGNOFF, 10 AWAITING_BUYER,
--    06 QUOTE_BUYER, 11 NEGOTIATION, 12 ORDER_RECEIVED,
--    13 DEPOSIT_RECEIVED, 07 PROCUREMENT, 08 DELIVERY_SCHEDULING,
--    14 DELIVERED, 04 CLOSED_PAID.
-- ---------------------------------------------------------------------
INSERT INTO sales.ticket (
    code, title, status, priority, created_by, assigned_to, customer_name, customer_id, note,
    payment_status, fulfillment_status,
    sales_stage, lifecycle, tender_requirement, deposit_policy, entry_channel,
    billing_date, due_date, credit_term_days
)
SELECT v.code, v.title, v.status, v.priority,
       creator.employee_id, assignee.employee_id,
       cust.name, cust.customer_id, v.notes,
       v.payment_status, v.fulfillment_status,
       v.sales_stage, v.lifecycle, v.tender_requirement, v.deposit_policy, v.entry_channel,
       v.billing_date, v.due_date, v.credit_term_days
FROM (VALUES
    ('UAT-TKT-06', 'กระเบื้องปูพื้นโครงการดวงตะวัน F (entry-point)',   'quotation_issued', 'NORMAL',
        'GLR-0005', 'GLR-0004', 'บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด',
        'Quote-buyer stage: quotation just issued, both dual-track fields still NULL -- walk the full flow from here',
        NULL::varchar, NULL::varchar,
        'QUOTE_BUYER', 'ACTIVE', 'UNKNOWN', 'REQUIRED', 'DESIGNER_LED',
        NULL::date, NULL::date, NULL::integer),
    ('UAT-TKT-07', 'กระเบื้องผนังโครงการอีสเทิร์นวิลล่า G (mid-flow A)', 'quotation_issued', 'NORMAL',
        'GLR-0006', 'GLR-0004', 'หจก. อีสเทิร์นวิลล่า',
        'Procurement stage: deposit paid, import request issued -- realistic in-progress state',
        'DEPOSIT_PAID', 'IR_ISSUED',
        'PROCUREMENT', 'ACTIVE', 'UNKNOWN', 'REQUIRED', 'DESIGNER_LED',
        NULL::date, NULL::date, NULL::integer),
    ('UAT-TKT-08', 'กระเบื้องสระว่ายน้ำโครงการดวงตะวัน H (mid-flow B)',  'quotation_issued', 'NORMAL',
        'GLR-0005', 'GLR-0004', 'บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด',
        'Delivery-scheduling stage: deposit paid and goods received, with one partial delivery record',
        'AWAITING_FINAL_PAYMENT', 'PARTIALLY_DELIVERED',
        'DELIVERY_SCHEDULING', 'ACTIVE', 'UNKNOWN', 'REQUIRED', 'DESIGNER_LED',
        NULL::date, NULL::date, NULL::integer),
    ('UAT-TKT-09', 'กระเบื้องตกแต่งล็อบบี้โครงการ Owner Signoff I',      'quotation_issued', 'NORMAL',
        'GLR-0006', 'GLR-0004', 'บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด',
        'Owner-signoff stage: owner quotation sent and waiting for approval',
        NULL::varchar, NULL::varchar,
        'OWNER_SIGNOFF', 'ACTIVE', 'UNKNOWN', 'REQUIRED', 'DESIGNER_LED',
        NULL::date, NULL::date, NULL::integer),
    ('UAT-TKT-10', 'กระเบื้องอาคารสำนักงาน Awaiting Buyer J',            'quotation_issued', 'HIGH',
        'GLR-0005', 'GLR-0004', 'หจก. อีสเทิร์นวิลล่า',
        'Awaiting-buyer stage and credit-customer overdue fixture',
        NULL::varchar, NULL::varchar,
        'AWAITING_BUYER', 'ACTIVE', 'UNKNOWN', 'CREDIT_CUSTOMER', 'OWNER_DIRECT',
        DATE '2026-06-01', DATE '2026-06-30', 29),
    ('UAT-TKT-11', 'กระเบื้องห้องน้ำโครงการ Negotiation K',              'quotation_issued', 'NORMAL',
        'GLR-0006', 'GLR-0004', 'บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด',
        'Negotiation stage: buyer quotation sent, commercial terms still open',
        NULL::varchar, NULL::varchar,
        'NEGOTIATION', 'ACTIVE', 'UNKNOWN', 'REQUIRED', 'BUYER_DIRECT',
        NULL::date, NULL::date, NULL::integer),
    ('UAT-TKT-12', 'กระเบื้องคาเฟ่โครงการ Order Received L',             'quotation_issued', 'NORMAL',
        'GLR-0005', 'GLR-0004', 'หจก. อีสเทิร์นวิลล่า',
        'Order-received stage: buyer accepted quotation, payment not yet started',
        'CUSTOMER_CONFIRMED', NULL::varchar,
        'ORDER_RECEIVED', 'ACTIVE', 'UNKNOWN', 'REQUIRED', 'BUYER_DIRECT',
        NULL::date, NULL::date, NULL::integer),
    ('UAT-TKT-13', 'กระเบื้องโรงแรมโครงการ Deposit Received M',          'quotation_issued', 'NORMAL',
        'GLR-0006', 'GLR-0004', 'บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด',
        'Deposit-received stage: receipt ledger has deposit but no fulfilment yet',
        'DEPOSIT_PAID', NULL::varchar,
        'DEPOSIT_RECEIVED', 'ACTIVE', 'UNKNOWN', 'REQUIRED', 'DESIGNER_LED',
        NULL::date, NULL::date, NULL::integer),
    ('UAT-TKT-14', 'กระเบื้องคอนโดโครงการ Delivered N',                  'quotation_issued', 'NORMAL',
        'GLR-0005', 'GLR-0004', 'หจก. อีสเทิร์นวิลล่า',
        'Delivered stage: all lines delivered, final balance still outstanding',
        'AWAITING_FINAL_PAYMENT', 'FULLY_DELIVERED',
        'DELIVERED', 'ACTIVE', 'UNKNOWN', 'REQUIRED', 'DESIGNER_LED',
        NULL::date, NULL::date, NULL::integer)
) AS v(code, title, status, priority, created_by_code, assigned_to_code, customer_name, notes,
       payment_status, fulfillment_status,
       sales_stage, lifecycle, tender_requirement, deposit_policy, entry_channel,
       billing_date, due_date, credit_term_days)
JOIN hr.employee creator ON creator.employee_code = v.created_by_code
LEFT JOIN hr.employee assignee ON assignee.employee_code = v.assigned_to_code
JOIN customers.customer cust ON cust.name = v.customer_name
WHERE NOT EXISTS (SELECT 1 FROM sales.ticket t WHERE t.code = v.code);

-- ---------------------------------------------------------------------
-- C. TICKET ITEMS (11 rows across the 9 tickets). Every item carries
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
    -- UAT-TKT-08 (delivery scheduling): two PIECE items; delivery records below
    -- mark the first line complete and the second line partial.
    ('UAT-TKT-08', 'POOL-2525-WH', 'กระเบื้องสระว่ายน้ำ', 'ด้าน', '25x25', 'ขาว',
        160::numeric, NULL::numeric,      'แผ่น',    'PIECE',
        850::numeric,  820::numeric,  NULL::numeric, NULL::text),
    ('UAT-TKT-08', 'GRT-8080-BG', 'กระเบื้องแกรนิตโต้', 'มัน', '80x80', 'เบจ',
        90::numeric,  NULL::numeric,      'แผ่น',    'PIECE',
        1450::numeric, 1400::numeric, NULL::numeric, NULL::text),
    ('UAT-TKT-09', 'DECOR-2040-IV', 'กระเบื้องตกแต่ง', 'ด้าน', '20x40', 'งาช้าง',
        100::numeric, NULL::numeric,      'แผ่น',    'PIECE',
        1050::numeric, 990::numeric,  NULL::numeric, NULL::text),
    ('UAT-TKT-10', 'OFFICE-6060-GR', 'กระเบื้องอาคารสำนักงาน', 'ด้าน', '60x60', 'เทา',
        120::numeric, NULL::numeric,      'แผ่น',    'PIECE',
        1050::numeric, 1000::numeric, NULL::numeric, NULL::text),
    ('UAT-TKT-11', 'BATH-3060-WH', 'กระเบื้องห้องน้ำ', 'มัน', '30x60', 'ขาว',
        75::numeric,  NULL::numeric,      'แผ่น',    'PIECE',
        1600::numeric, 1500::numeric, NULL::numeric, NULL::text),
    ('UAT-TKT-12', 'CAFE-3030-GN', 'กระเบื้องคาเฟ่', 'ด้าน', '30x30', 'เขียว',
        140::numeric, NULL::numeric,      'แผ่น',    'PIECE',
        950::numeric,  900::numeric,  NULL::numeric, NULL::text),
    ('UAT-TKT-13', 'HOTEL-3060-BE', 'กระเบื้องโรงแรม', 'มัน', '30x60', 'เบจ',
        200::numeric, NULL::numeric,      'แผ่น',    'PIECE',
        800::numeric,  750::numeric,  NULL::numeric, NULL::text),
    ('UAT-TKT-14', 'CONDO-6060-ST', 'กระเบื้องคอนโด', 'ด้าน', '60x60', 'หิน',
        200::numeric, NULL::numeric,      'แผ่น',    'PIECE',
        1000::numeric, 950::numeric,  NULL::numeric, NULL::text)
) AS v(ticket_code, model, brand, texture, size, color, qty, qty_sqm, unit, unit_basis,
       proposed_price, approved_price, manual_price, manual_override_reason)
JOIN sales.ticket t ON t.code = v.ticket_code
WHERE NOT EXISTS (
    SELECT 1 FROM sales.ticket_item ti
    WHERE ti.ticket_id = t.ticket_id AND ti.model = v.model AND ti.texture = v.texture AND ti.size = v.size
);

-- ---------------------------------------------------------------------
-- D. QUOTATIONS (1 recipient-scoped quotation per ticket). Statuses are
--    valid under V52's chk_quotation_doc_status and mirror each stage's
--    business position (sent/accepted/issued).
-- ---------------------------------------------------------------------
INSERT INTO sales.quotation (
    ticket_id, number, issued_by, doc_status, quotation_version, total_amount,
    recipient_type, sent_at, accepted_at
)
SELECT t.ticket_id, v.number, issuer.employee_id, v.doc_status, 1, v.total_amount,
       v.recipient_type, v.sent_at, v.accepted_at
FROM (VALUES
    ('UAT-TKT-06', 'QN-2026-00010', 'GLR-0004', 'ISSUED',   'BUYER',    265040.00::numeric, NULL::timestamptz, NULL::timestamptz),
    ('UAT-TKT-07', 'QN-2026-00011', 'GLR-0004', 'ACCEPTED', 'BUYER',    165000.00::numeric, TIMESTAMPTZ '2026-07-01 09:00:00+07', TIMESTAMPTZ '2026-07-01 16:00:00+07'),
    ('UAT-TKT-08', 'QN-2026-00012', 'GLR-0004', 'ACCEPTED', 'BUYER',    257200.00::numeric, TIMESTAMPTZ '2026-07-02 09:00:00+07', TIMESTAMPTZ '2026-07-02 16:00:00+07'),
    ('UAT-TKT-09', 'QN-2026-00013', 'GLR-0004', 'SENT',     'OWNER',     99000.00::numeric, TIMESTAMPTZ '2026-07-03 10:00:00+07', NULL::timestamptz),
    ('UAT-TKT-10', 'QN-2026-00014', 'GLR-0004', 'ACCEPTED', 'DESIGNER', 120000.00::numeric, TIMESTAMPTZ '2026-06-01 10:00:00+07', TIMESTAMPTZ '2026-06-02 10:00:00+07'),
    ('UAT-TKT-11', 'QN-2026-00015', 'GLR-0004', 'SENT',     'BUYER',    112500.00::numeric, TIMESTAMPTZ '2026-07-05 10:00:00+07', NULL::timestamptz),
    ('UAT-TKT-12', 'QN-2026-00016', 'GLR-0004', 'ACCEPTED', 'BUYER',    126000.00::numeric, TIMESTAMPTZ '2026-07-06 10:00:00+07', TIMESTAMPTZ '2026-07-06 15:00:00+07'),
    ('UAT-TKT-13', 'QN-2026-00017', 'GLR-0004', 'ACCEPTED', 'BUYER',    150000.00::numeric, TIMESTAMPTZ '2026-07-07 10:00:00+07', TIMESTAMPTZ '2026-07-07 15:00:00+07'),
    ('UAT-TKT-14', 'QN-2026-00018', 'GLR-0004', 'ACCEPTED', 'BUYER',    190000.00::numeric, TIMESTAMPTZ '2026-07-08 10:00:00+07', TIMESTAMPTZ '2026-07-08 15:00:00+07')
) AS v(ticket_code, number, issuer_code, doc_status, recipient_type, total_amount, sent_at, accepted_at)
JOIN sales.ticket t ON t.code = v.ticket_code
JOIN hr.employee issuer ON issuer.employee_code = v.issuer_code
WHERE NOT EXISTS (SELECT 1 FROM sales.quotation q WHERE q.number = v.number);

-- ---------------------------------------------------------------------
-- E. DEPOSIT NOTICES: issued notices for the payment-flow fixtures.
-- ---------------------------------------------------------------------
INSERT INTO sales.deposit_notice (
    ticket_id, doc_type, version, doc_number, issue_date, status,
    customer_name, deposit_amount, total_payable, issued_by_id, issued_by_name
)
SELECT t.ticket_id, 'DEPOSIT_NOTICE', 1, v.doc_number, v.issue_date, 'ISSUED',
       cust.name, v.deposit_amount, v.total_payable, issuer.employee_id,
       TRIM(CONCAT_WS(' ', issuer.first_name_th, issuer.last_name_th))
FROM (VALUES
    ('UAT-TKT-07', 'หจก. อีสเทิร์นวิลล่า',                  'DN-2026-00005', DATE '2026-07-01', 82500.00::numeric, 165000.00::numeric),
    ('UAT-TKT-08', 'บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด', 'DN-2026-00008', DATE '2026-07-02', 128600.00::numeric, 257200.00::numeric),
    ('UAT-TKT-13', 'บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด', 'DN-2026-00006', DATE '2026-07-07', 75000.00::numeric, 150000.00::numeric),
    ('UAT-TKT-14', 'หจก. อีสเทิร์นวิลล่า',                  'DN-2026-00007', DATE '2026-07-08', 95000.00::numeric, 190000.00::numeric)
) AS v(ticket_code, customer_name, doc_number, issue_date, deposit_amount, total_payable)
JOIN sales.ticket t ON t.code = v.ticket_code
JOIN customers.customer cust ON cust.name = v.customer_name
JOIN hr.employee issuer ON issuer.employee_code = 'GLR-0004'
WHERE NOT EXISTS (
    SELECT 1 FROM sales.deposit_notice dn
    WHERE dn.ticket_id = t.ticket_id AND dn.doc_number = v.doc_number
);

-- ---------------------------------------------------------------------
-- F. PAYMENT RECEIPTS: the UI derives paid/outstanding/payment_stage from
--    sales.payment_receipt, so every paid fixture has a matching ledger row.
--    UAT-TKT-10 intentionally has no receipt and an overdue credit due date.
-- ---------------------------------------------------------------------
INSERT INTO sales.payment_receipt (
    ticket_id, kind, amount, currency, received_at, recorded_by,
    note, deposit_notice_id, receipt_ref
)
SELECT t.ticket_id, v.kind, v.amount, 'THB', v.received_at, recorder.employee_id,
       v.note, dn.deposit_notice_id, v.receipt_ref
FROM (VALUES
    ('UAT-TKT-04', 'BALANCE', 202500.00::numeric, TIMESTAMPTZ '2026-06-28 15:00:00+07',
        'GLR-0013', NULL::varchar,     'UAT-RCPT-00004-B', 'Full balance received for closed-paid UAT fixture'),
    ('UAT-TKT-07', 'DEPOSIT',  82500.00::numeric, TIMESTAMPTZ '2026-07-01 17:00:00+07',
        'GLR-0013', 'DN-2026-00005',   'UAT-RCPT-00007-D', 'Deposit received'),
    ('UAT-TKT-08', 'DEPOSIT', 128600.00::numeric, TIMESTAMPTZ '2026-07-02 17:00:00+07',
        'GLR-0013', 'DN-2026-00008',   'UAT-RCPT-00008-D', 'Deposit received before partial delivery'),
    ('UAT-TKT-13', 'DEPOSIT',  75000.00::numeric, TIMESTAMPTZ '2026-07-07 17:00:00+07',
        'GLR-0013', 'DN-2026-00006',   'UAT-RCPT-00013-D', 'Deposit received'),
    ('UAT-TKT-14', 'DEPOSIT',  95000.00::numeric, TIMESTAMPTZ '2026-07-08 17:00:00+07',
        'GLR-0013', 'DN-2026-00007',   'UAT-RCPT-00014-D', 'Deposit received; final balance still outstanding')
) AS v(ticket_code, kind, amount, received_at, recorder_code, doc_number, receipt_ref, note)
JOIN sales.ticket t ON t.code = v.ticket_code
JOIN hr.employee recorder ON recorder.employee_code = v.recorder_code
LEFT JOIN sales.deposit_notice dn ON dn.ticket_id = t.ticket_id AND dn.doc_number = v.doc_number
WHERE NOT EXISTS (
    SELECT 1 FROM sales.payment_receipt existing
    WHERE existing.ticket_id = t.ticket_id AND existing.receipt_ref = v.receipt_ref
);

-- ---------------------------------------------------------------------
-- G. DELIVERY RECORDS: Phase 4 derives delivery from per-line quantities
--    plus auditable delivery records, not fulfillment_status alone.
-- ---------------------------------------------------------------------
WITH delivery_seed(ticket_code, source, delivered_by_code, delivered_at, note) AS (
    VALUES
        ('UAT-TKT-04', 'WAREHOUSE', 'GLR-0008', TIMESTAMPTZ '2026-06-27 10:00:00+07',
            'Closed-paid fixture delivered in full'),
        ('UAT-TKT-08', 'WAREHOUSE', 'GLR-0008', TIMESTAMPTZ '2026-07-10 10:00:00+07',
            'Partial delivery fixture: first line full, second line partial'),
        ('UAT-TKT-14', 'WAREHOUSE', 'GLR-0008', TIMESTAMPTZ '2026-07-12 10:00:00+07',
            'Delivered-stage fixture delivered in full')
),
created_delivery AS (
    INSERT INTO sales.delivery_record (ticket_id, source, delivered_by, delivered_at, note)
    SELECT t.ticket_id, ds.source, delivered_by.employee_id, ds.delivered_at, ds.note
    FROM delivery_seed ds
    JOIN sales.ticket t ON t.code = ds.ticket_code
    JOIN hr.employee delivered_by ON delivered_by.employee_code = ds.delivered_by_code
    WHERE NOT EXISTS (
        SELECT 1 FROM sales.delivery_record existing
        WHERE existing.ticket_id = t.ticket_id AND existing.note = ds.note
    )
    RETURNING delivery_id, ticket_id, note
),
all_delivery AS (
    SELECT delivery_id, ticket_id, note FROM created_delivery
    UNION ALL
    SELECT dr.delivery_id, dr.ticket_id, dr.note
    FROM sales.delivery_record dr
    JOIN delivery_seed ds ON ds.note = dr.note
    JOIN sales.ticket t ON t.ticket_id = dr.ticket_id AND t.code = ds.ticket_code
),
delivery_lines(ticket_code, model, qty) AS (
    VALUES
        ('UAT-TKT-04', 'GRT-8080-BG', 150::numeric),
        ('UAT-TKT-08', 'POOL-2525-WH', 160::numeric),
        ('UAT-TKT-08', 'GRT-8080-BG', 45::numeric),
        ('UAT-TKT-14', 'CONDO-6060-ST', 200::numeric)
)
INSERT INTO sales.delivery_record_item (delivery_id, item_id, qty)
SELECT ad.delivery_id, ti.item_id, dl.qty
FROM delivery_lines dl
JOIN sales.ticket t ON t.code = dl.ticket_code
JOIN sales.ticket_item ti ON ti.ticket_id = t.ticket_id AND ti.model = dl.model
JOIN all_delivery ad ON ad.ticket_id = t.ticket_id
WHERE NOT EXISTS (
    SELECT 1 FROM sales.delivery_record_item existing
    WHERE existing.delivery_id = ad.delivery_id AND existing.item_id = ti.item_id
);

UPDATE sales.ticket_item ti
SET qty_delivered = v.qty_delivered
FROM (VALUES
    ('UAT-TKT-04', 'GRT-8080-BG', 150::numeric),
    ('UAT-TKT-08', 'POOL-2525-WH', 160::numeric),
    ('UAT-TKT-08', 'GRT-8080-BG', 45::numeric),
    ('UAT-TKT-14', 'CONDO-6060-ST', 200::numeric)
) AS v(ticket_code, model, qty_delivered)
JOIN sales.ticket t ON t.code = v.ticket_code
WHERE ti.ticket_id = t.ticket_id
  AND ti.model = v.model
  AND ti.qty_delivered <> v.qty_delivered;
