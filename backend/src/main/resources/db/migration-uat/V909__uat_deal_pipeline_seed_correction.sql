-- =====================================================================
-- UAT BRANCH ONLY — forward-only correction that upgrades the UAT sales
-- seed (V903 tickets 01-05 + V905 tickets 06-08) to the deal-pipeline
-- model (V50-V54, merged from main), and adds tickets 09-14 so every one
-- of the 14 sales stages is represented.
--
-- WHY A NEW MIGRATION (not an edit to V903/V905):
--   The hosted gl-r-erp-uat DB already applied V903/V905 with their
--   ORIGINAL (pre-pipeline) checksums. Editing them in place would change
--   the checksum and fail Flyway validate() on the live DB (application-uat
--   does NOT disable validate-on-migrate). So V903/V905 are left byte-for-
--   byte as first deployed, and ALL pipeline enrichment lives here in
--   V909 (> V908, applies in natural order; out-of-order=true also covers
--   the V50-V54 interleave with the V900+ range).
--
-- IDEMPOTENT: every INSERT is WHERE NOT EXISTS on a business key and every
--   UPDATE is keyed by ticket code / quotation number, so a re-apply (or a
--   fresh DB where V903/V905 just ran) is safe and converges to the same
--   state. sales.payment_receipt (V53) and sales.delivery_record (V54) did
--   not exist when V903/V905 were written, so the original seed created
--   none — no duplication risk for those.
--
-- END STATE (asserted by FlywayMigrationTest.assertUatDealPipelineSeed):
--   14 distinct sales_stage across UAT-TKT-%, 5 payment receipts,
--   3 delivery records (04/08/14), UAT-TKT-10 = CREDIT_CUSTOMER overdue,
--   UAT-TKT-08 = partial delivery, UAT-TKT-04 = closed-paid/completed.
-- =====================================================================

SET search_path = sales, hr, public;

-- ---------------------------------------------------------------------
-- A. CUSTOMERS for tickets 06-14 (created by the original V905 too; the
--    guard makes this a no-op there and keeps V909 self-contained).
-- ---------------------------------------------------------------------
INSERT INTO customers.customer (name, address, phone, branch)
SELECT v.name, v.address, v.phone, 'สำนักงานใหญ่'
FROM (VALUES
    ('บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด', '15 ถ.บางนา-ตราด กรุงเทพฯ', '081-234-5679'),
    ('หจก. อีสเทิร์นวิลล่า',                  '27 ถ.สุขาภิบาล 2 กรุงเทพฯ', '081-234-5680')
) AS v(name, address, phone)
WHERE NOT EXISTS (SELECT 1 FROM customers.customer c WHERE c.name = v.name);

-- ---------------------------------------------------------------------
-- B. NEW TICKETS 09-14 (with full pipeline fields). Combined with the
--    enriched 01-08 below, the 14 tickets cover all 14 stages exactly.
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
    ('UAT-TKT-09', 'กระเบื้องตกแต่งล็อบบี้โครงการ Owner Signoff I', 'quotation_issued', 'NORMAL',
        'GLR-0006', 'GLR-0004', 'บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด',
        'Owner-signoff stage: owner quotation sent and waiting for approval',
        NULL::varchar, NULL::varchar,
        'OWNER_SIGNOFF', 'ACTIVE', 'UNKNOWN', 'REQUIRED', 'DESIGNER_LED',
        NULL::date, NULL::date, NULL::integer),
    ('UAT-TKT-10', 'กระเบื้องอาคารสำนักงาน Awaiting Buyer J', 'quotation_issued', 'HIGH',
        'GLR-0005', 'GLR-0004', 'หจก. อีสเทิร์นวิลล่า',
        'Awaiting-buyer stage and credit-customer overdue fixture',
        NULL::varchar, NULL::varchar,
        'AWAITING_BUYER', 'ACTIVE', 'UNKNOWN', 'CREDIT_CUSTOMER', 'OWNER_DIRECT',
        DATE '2026-06-01', DATE '2026-06-30', 29),
    ('UAT-TKT-11', 'กระเบื้องห้องน้ำโครงการ Negotiation K', 'quotation_issued', 'NORMAL',
        'GLR-0006', 'GLR-0004', 'บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด',
        'Negotiation stage: buyer quotation sent, commercial terms still open',
        NULL::varchar, NULL::varchar,
        'NEGOTIATION', 'ACTIVE', 'UNKNOWN', 'REQUIRED', 'BUYER_DIRECT',
        NULL::date, NULL::date, NULL::integer),
    ('UAT-TKT-12', 'กระเบื้องคาเฟ่โครงการ Order Received L', 'quotation_issued', 'NORMAL',
        'GLR-0005', 'GLR-0004', 'หจก. อีสเทิร์นวิลล่า',
        'Order-received stage: buyer accepted quotation, payment not yet started',
        'CUSTOMER_CONFIRMED', NULL::varchar,
        'ORDER_RECEIVED', 'ACTIVE', 'UNKNOWN', 'REQUIRED', 'BUYER_DIRECT',
        NULL::date, NULL::date, NULL::integer),
    ('UAT-TKT-13', 'กระเบื้องโรงแรมโครงการ Deposit Received M', 'quotation_issued', 'NORMAL',
        'GLR-0006', 'GLR-0004', 'บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด',
        'Deposit-received stage: receipt ledger has deposit but no fulfilment yet',
        'DEPOSIT_PAID', NULL::varchar,
        'DEPOSIT_RECEIVED', 'ACTIVE', 'UNKNOWN', 'REQUIRED', 'DESIGNER_LED',
        NULL::date, NULL::date, NULL::integer),
    ('UAT-TKT-14', 'กระเบื้องคอนโดโครงการ Delivered N', 'quotation_issued', 'NORMAL',
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
-- C. ENRICH tickets 01-08 (created pre-pipeline by V903/V905) with their
--    deal-pipeline fields. Keyed by code; status is left untouched.
-- ---------------------------------------------------------------------
UPDATE sales.ticket t
SET sales_stage        = v.sales_stage,
    lifecycle          = v.lifecycle,
    tender_requirement = v.tender_requirement,
    deposit_policy     = v.deposit_policy,
    entry_channel      = v.entry_channel,
    payment_status     = v.payment_status,
    fulfillment_status = v.fulfillment_status
FROM (VALUES
    ('UAT-TKT-01', 'LEAD_APPROACH',       'ACTIVE',    'UNKNOWN', 'REQUIRED', 'DESIGNER_LED', NULL::varchar, NULL::varchar),
    ('UAT-TKT-02', 'PRESENTATION',        'ACTIVE',    'UNKNOWN', 'REQUIRED', 'DESIGNER_LED', NULL::varchar, NULL::varchar),
    ('UAT-TKT-03', 'SPEC_APPROVED',       'ACTIVE',    'UNKNOWN', 'REQUIRED', 'DESIGNER_LED', NULL::varchar, NULL::varchar),
    ('UAT-TKT-04', 'CLOSED_PAID',         'COMPLETED', 'UNKNOWN', 'REQUIRED', 'DESIGNER_LED', 'FULLY_PAID',             'FULLY_DELIVERED'),
    ('UAT-TKT-05', 'QUOTE_DESIGN_SIDE',   'ACTIVE',    'UNKNOWN', 'REQUIRED', 'DESIGNER_LED', NULL::varchar, NULL::varchar),
    ('UAT-TKT-06', 'QUOTE_BUYER',         'ACTIVE',    'UNKNOWN', 'REQUIRED', 'DESIGNER_LED', NULL::varchar, NULL::varchar),
    ('UAT-TKT-07', 'PROCUREMENT',         'ACTIVE',    'UNKNOWN', 'REQUIRED', 'DESIGNER_LED', 'DEPOSIT_PAID',           'IR_ISSUED'),
    ('UAT-TKT-08', 'DELIVERY_SCHEDULING', 'ACTIVE',    'UNKNOWN', 'REQUIRED', 'DESIGNER_LED', 'AWAITING_FINAL_PAYMENT', 'PARTIALLY_DELIVERED')
) AS v(code, sales_stage, lifecycle, tender_requirement, deposit_policy, entry_channel,
       payment_status, fulfillment_status)
WHERE t.code = v.code;

-- ---------------------------------------------------------------------
-- D. TICKET ITEMS for the new tickets 09-14 (01-08 items already seeded).
-- ---------------------------------------------------------------------
INSERT INTO sales.ticket_item (
    ticket_id, model, brand, texture, size, color, qty, unit, unit_basis,
    proposed_price, approved_price
)
SELECT t.ticket_id, v.model, v.brand, v.texture, v.size, v.color, v.qty, v.unit, 'PIECE',
       v.proposed_price, v.approved_price
FROM (VALUES
    ('UAT-TKT-09', 'DECOR-2040-IV',  'กระเบื้องตกแต่ง',        'ด้าน', '20x40', 'งาช้าง', 100::numeric, 'แผ่น', 1050::numeric, 990::numeric),
    ('UAT-TKT-10', 'OFFICE-6060-GR', 'กระเบื้องอาคารสำนักงาน', 'ด้าน', '60x60', 'เทา',    120::numeric, 'แผ่น', 1050::numeric, 1000::numeric),
    ('UAT-TKT-11', 'BATH-3060-WH',   'กระเบื้องห้องน้ำ',        'มัน',  '30x60', 'ขาว',    75::numeric,  'แผ่น', 1600::numeric, 1500::numeric),
    ('UAT-TKT-12', 'CAFE-3030-GN',   'กระเบื้องคาเฟ่',          'ด้าน', '30x30', 'เขียว',  140::numeric, 'แผ่น', 950::numeric,  900::numeric),
    ('UAT-TKT-13', 'HOTEL-3060-BE',  'กระเบื้องโรงแรม',         'มัน',  '30x60', 'เบจ',    200::numeric, 'แผ่น', 800::numeric,  750::numeric),
    ('UAT-TKT-14', 'CONDO-6060-ST',  'กระเบื้องคอนโด',          'ด้าน', '60x60', 'หิน',    200::numeric, 'แผ่น', 1000::numeric, 950::numeric)
) AS v(ticket_code, model, brand, texture, size, color, qty, unit, proposed_price, approved_price)
JOIN sales.ticket t ON t.code = v.ticket_code
WHERE NOT EXISTS (
    SELECT 1 FROM sales.ticket_item ti
    WHERE ti.ticket_id = t.ticket_id AND ti.model = v.model AND ti.texture = v.texture AND ti.size = v.size
);

-- ---------------------------------------------------------------------
-- E. QUOTATIONS. New recipient-scoped quotations for 09-14; enrich the
--    existing 06-08 quotations (V905 issued them without recipient_type).
-- ---------------------------------------------------------------------
INSERT INTO sales.quotation (
    ticket_id, number, issued_by, doc_status, quotation_version, total_amount,
    recipient_type, sent_at, accepted_at
)
SELECT t.ticket_id, v.number, issuer.employee_id, v.doc_status, 1, v.total_amount,
       v.recipient_type, v.sent_at, v.accepted_at
FROM (VALUES
    -- Distinct 'QN-UAT-*' prefix so these never collide with app-generated
    -- QN-2026-NNNNN numbers a tester may have issued past the seed's 00012.
    ('UAT-TKT-09', 'QN-UAT-0013', 'GLR-0004', 'SENT',     'OWNER',     99000.00::numeric,  TIMESTAMPTZ '2026-07-03 10:00:00+07', NULL::timestamptz),
    ('UAT-TKT-10', 'QN-UAT-0014', 'GLR-0004', 'ACCEPTED', 'DESIGNER', 120000.00::numeric,  TIMESTAMPTZ '2026-06-01 10:00:00+07', TIMESTAMPTZ '2026-06-02 10:00:00+07'),
    ('UAT-TKT-11', 'QN-UAT-0015', 'GLR-0004', 'SENT',     'BUYER',    112500.00::numeric,  TIMESTAMPTZ '2026-07-05 10:00:00+07', NULL::timestamptz),
    ('UAT-TKT-12', 'QN-UAT-0016', 'GLR-0004', 'ACCEPTED', 'BUYER',    126000.00::numeric,  TIMESTAMPTZ '2026-07-06 10:00:00+07', TIMESTAMPTZ '2026-07-06 15:00:00+07'),
    ('UAT-TKT-13', 'QN-UAT-0017', 'GLR-0004', 'ACCEPTED', 'BUYER',    150000.00::numeric,  TIMESTAMPTZ '2026-07-07 10:00:00+07', TIMESTAMPTZ '2026-07-07 15:00:00+07'),
    ('UAT-TKT-14', 'QN-UAT-0018', 'GLR-0004', 'ACCEPTED', 'BUYER',    190000.00::numeric,  TIMESTAMPTZ '2026-07-08 10:00:00+07', TIMESTAMPTZ '2026-07-08 15:00:00+07')
) AS v(ticket_code, number, issuer_code, doc_status, recipient_type, total_amount, sent_at, accepted_at)
JOIN sales.ticket t ON t.code = v.ticket_code
JOIN hr.employee issuer ON issuer.employee_code = v.issuer_code
WHERE NOT EXISTS (SELECT 1 FROM sales.quotation q WHERE q.number = v.number);

UPDATE sales.quotation q
SET recipient_type = v.recipient_type,
    doc_status     = v.doc_status,
    sent_at        = v.sent_at,
    accepted_at    = v.accepted_at
FROM (VALUES
    ('QN-2026-00010', 'BUYER', 'ISSUED',   NULL::timestamptz,                    NULL::timestamptz),
    ('QN-2026-00011', 'BUYER', 'ACCEPTED', TIMESTAMPTZ '2026-07-01 09:00:00+07', TIMESTAMPTZ '2026-07-01 16:00:00+07'),
    ('QN-2026-00012', 'BUYER', 'ACCEPTED', TIMESTAMPTZ '2026-07-02 09:00:00+07', TIMESTAMPTZ '2026-07-02 16:00:00+07')
) AS v(number, recipient_type, doc_status, sent_at, accepted_at)
WHERE q.number = v.number;

-- ---------------------------------------------------------------------
-- F. DEPOSIT NOTICES for the new payment-flow fixtures (08/13/14). TKT-07's
--    DN-2026-00005 already exists from V905; its payable derives from the
--    quotation, so no update is needed here.
-- ---------------------------------------------------------------------
INSERT INTO sales.deposit_notice (
    ticket_id, doc_type, version, doc_number, issue_date, status,
    customer_name, deposit_amount, total_payable, issued_by_id, issued_by_name
)
SELECT t.ticket_id, 'DEPOSIT_NOTICE', 1, v.doc_number, v.issue_date, 'ISSUED',
       cust.name, v.deposit_amount, v.total_payable, issuer.employee_id,
       TRIM(CONCAT_WS(' ', issuer.first_name_th, issuer.last_name_th))
FROM (VALUES
    ('UAT-TKT-08', 'บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด', 'DN-2026-00008', DATE '2026-07-02', 128600.00::numeric, 257200.00::numeric),
    ('UAT-TKT-13', 'บริษัท ดวงตะวัน พร็อพเพอร์ตี้ จำกัด', 'DN-2026-00006', DATE '2026-07-07', 75000.00::numeric,  150000.00::numeric),
    ('UAT-TKT-14', 'หจก. อีสเทิร์นวิลล่า',                  'DN-2026-00007', DATE '2026-07-08', 95000.00::numeric,  190000.00::numeric)
) AS v(ticket_code, customer_name, doc_number, issue_date, deposit_amount, total_payable)
JOIN sales.ticket t ON t.code = v.ticket_code
JOIN customers.customer cust ON cust.name = v.customer_name
JOIN hr.employee issuer ON issuer.employee_code = 'GLR-0004'
-- Guard on BOTH unique keys: ux_deposit_notice_ticket_version (ticket_id, version)
-- and the GLOBAL ux_deposit_notice_doc_number (doc_number). The hosted UAT DB may
-- already carry a tester-issued version-1 notice on these tickets, or the doc_number
-- elsewhere; skip in either case so the migration never violates a unique index.
WHERE NOT EXISTS (
    SELECT 1 FROM sales.deposit_notice dn
    WHERE dn.ticket_id = t.ticket_id AND dn.version = 1
)
AND NOT EXISTS (
    SELECT 1 FROM sales.deposit_notice dn2 WHERE dn2.doc_number = v.doc_number
);

-- ---------------------------------------------------------------------
-- G. PAYMENT RECEIPTS (5): the pipeline derives paid/outstanding from the
--    ledger. UAT-TKT-10 intentionally has NO receipt (overdue credit).
-- ---------------------------------------------------------------------
INSERT INTO sales.payment_receipt (
    ticket_id, kind, amount, currency, received_at, recorded_by,
    note, deposit_notice_id, receipt_ref
)
SELECT t.ticket_id, v.kind, v.amount, 'THB', v.received_at, recorder.employee_id,
       v.note, dn.deposit_notice_id, v.receipt_ref
FROM (VALUES
    ('UAT-TKT-04', 'BALANCE', 202500.00::numeric, TIMESTAMPTZ '2026-06-28 15:00:00+07', 'GLR-0013', NULL::varchar,   'UAT-RCPT-00004-B', 'Full balance received for closed-paid UAT fixture'),
    ('UAT-TKT-07', 'DEPOSIT',  82500.00::numeric, TIMESTAMPTZ '2026-07-01 17:00:00+07', 'GLR-0013', 'DN-2026-00005', 'UAT-RCPT-00007-D', 'Deposit received'),
    ('UAT-TKT-08', 'DEPOSIT', 128600.00::numeric, TIMESTAMPTZ '2026-07-02 17:00:00+07', 'GLR-0013', 'DN-2026-00008', 'UAT-RCPT-00008-D', 'Deposit received before partial delivery'),
    ('UAT-TKT-13', 'DEPOSIT',  75000.00::numeric, TIMESTAMPTZ '2026-07-07 17:00:00+07', 'GLR-0013', 'DN-2026-00006', 'UAT-RCPT-00013-D', 'Deposit received'),
    ('UAT-TKT-14', 'DEPOSIT',  95000.00::numeric, TIMESTAMPTZ '2026-07-08 17:00:00+07', 'GLR-0013', 'DN-2026-00007', 'UAT-RCPT-00014-D', 'Deposit received; final balance still outstanding')
) AS v(ticket_code, kind, amount, received_at, recorder_code, doc_number, receipt_ref, note)
JOIN sales.ticket t ON t.code = v.ticket_code
JOIN hr.employee recorder ON recorder.employee_code = v.recorder_code
LEFT JOIN sales.deposit_notice dn ON dn.ticket_id = t.ticket_id AND dn.doc_number = v.doc_number
WHERE NOT EXISTS (
    SELECT 1 FROM sales.payment_receipt existing
    WHERE existing.ticket_id = t.ticket_id AND existing.receipt_ref = v.receipt_ref
);

-- ---------------------------------------------------------------------
-- H. DELIVERY RECORDS (3: 04 full, 08 partial, 14 full) + per-line
--    quantities. Drives PARTIALLY_DELIVERED/FULLY_DELIVERED derivation.
-- ---------------------------------------------------------------------
WITH delivery_seed(ticket_code, source, delivered_by_code, delivered_at, note) AS (
    VALUES
        ('UAT-TKT-04', 'WAREHOUSE', 'GLR-0008', TIMESTAMPTZ '2026-06-27 10:00:00+07', 'Closed-paid fixture delivered in full'),
        ('UAT-TKT-08', 'WAREHOUSE', 'GLR-0008', TIMESTAMPTZ '2026-07-10 10:00:00+07', 'Partial delivery fixture: first line full, second line partial'),
        ('UAT-TKT-14', 'WAREHOUSE', 'GLR-0008', TIMESTAMPTZ '2026-07-12 10:00:00+07', 'Delivered-stage fixture delivered in full')
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
        ('UAT-TKT-04', 'GRT-8080-BG',   150::numeric),
        ('UAT-TKT-08', 'POOL-2525-WH',  160::numeric),
        ('UAT-TKT-08', 'GRT-8080-BG',   45::numeric),
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
    ('UAT-TKT-04', 'GRT-8080-BG',   150::numeric),
    ('UAT-TKT-08', 'POOL-2525-WH',  160::numeric),
    ('UAT-TKT-08', 'GRT-8080-BG',   45::numeric),
    ('UAT-TKT-14', 'CONDO-6060-ST', 200::numeric)
) AS v(ticket_code, model, qty_delivered)
JOIN sales.ticket t ON t.code = v.ticket_code
WHERE ti.ticket_id = t.ticket_id
  AND ti.model = v.model
  AND ti.qty_delivered <> v.qty_delivered;
