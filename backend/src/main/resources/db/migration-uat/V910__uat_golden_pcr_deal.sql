-- =====================================================================
-- UAT BRANCH ONLY — golden, fully-worked end-to-end deal for the
-- pricing-request (PCR) redesign chain (V59-V84): ONE deal driven from
-- creation through PricingRequest -> FactoryQuote -> PricingCosting ->
-- PricingDecision -> CustomerQuotation -> OrderConfirmation ->
-- Deposit/Payment -> FactoryPurchaseOrder -> Delivery -> CLOSED_PAID ->
-- Commission, on the direct-factory-import sourcing path (exercises the
-- factory_purchase_order chain end to end).
--
-- WHY A NEW MIGRATION: V900-V909 seed the 14-stage deal-pipeline demo
-- (V50-V54) but never touch the PCR chain (V59-V84) at all — every
-- existing UAT ticket was seeded directly at its target sales_stage
-- with no pricing_request/factory_quote/pricing_costing/pricing_decision/
-- factory_purchase_order rows behind it. Testers exercising the PCR
-- redesign therefore have to hand-walk the whole chain themselves before
-- they can inspect the back half (quotation, deposit, procurement,
-- delivery, close, commission). This migration adds exactly one deal
-- that already has.
--
-- CODE SPACE: deliberately OUTSIDE both UAT-TKT-01..14 (V900-V909) and
-- the QN-2026-*/QN-UAT-00xx/DN-2026-*/UAT-INV-000x/UAT-RCPT-000xx-*
-- ranges already claimed by those migrations. Every business-key prefix
-- here uses a distinct 'UAT-...-GOLD-01' / '...-UAT-GOLD-01' shape so
-- FlywayMigrationTest.assertUatDealPipelineSeed's existing counts (which
-- filter ticket code LIKE 'UAT-TKT-%' and hardcode receipt_count=5/
-- delivery_count=3) are completely unaffected by this migration — see
-- that assertion in FlywayMigrationTest.java, which this file's own
-- ticket code ('UAT-GOLD-01') never matches.
--
-- IDEMPOTENT: every INSERT is guarded by WHERE NOT EXISTS on the row's
-- real unique key (documented per-section below), so a re-apply, or a
-- fresh DB where V900-V909 just ran, converges to the same state and can
-- never violate a unique index — including on the hosted UAT DB, which
-- may carry unrelated tester-made rows this migration cannot see.
-- =====================================================================

-- ---------------------------------------------------------------------
-- A. CUSTOMER + CONTACT + PROJECT.
--    Guard: customers.customer has no unique constraint besides PK, so
--    guard on exact name match (the only natural key available, same
--    convention V903/V905/V909 use).
-- ---------------------------------------------------------------------
INSERT INTO customers.customer (name, address, phone, branch)
SELECT 'บริษัท โกลเด้นเกท ดีเวลลอปเมนท์ จำกัด', '99 ถ.ริมแม่น้ำ กรุงเทพฯ', '081-999-8888', 'สำนักงานใหญ่'
WHERE NOT EXISTS (
    SELECT 1 FROM customers.customer c WHERE c.name = 'บริษัท โกลเด้นเกท ดีเวลลอปเมนท์ จำกัด'
);

INSERT INTO customers.contact (customer_id, first_name, last_name, position, email, phone)
SELECT cust.customer_id, 'สมชาย', 'โกลเด้นเกท', 'เจ้าของโครงการ (Owner)',
       'somchai@goldengate-dev.example', '081-999-8888'
FROM customers.customer cust
WHERE cust.name = 'บริษัท โกลเด้นเกท ดีเวลลอปเมนท์ จำกัด'
AND NOT EXISTS (
    SELECT 1 FROM customers.contact c
    WHERE c.customer_id = cust.customer_id AND c.first_name = 'สมชาย' AND c.last_name = 'โกลเด้นเกท'
);

INSERT INTO customers.project (customer_id, name)
SELECT cust.customer_id, 'โครงการคอนโดมิเนียม โกลเด้นเกท ริเวอร์ไซด์'
FROM customers.customer cust
WHERE cust.name = 'บริษัท โกลเด้นเกท ดีเวลลอปเมนท์ จำกัด'
AND NOT EXISTS (
    SELECT 1 FROM customers.project p
    WHERE p.customer_id = cust.customer_id AND p.name = 'โครงการคอนโดมิเนียม โกลเด้นเกท ริเวอร์ไซด์'
);

-- ---------------------------------------------------------------------
-- B. TICKET (the deal). code='UAT-GOLD-01' — outside the 'UAT-TKT-%'
--    pattern the existing seed assertion filters on. End state: closed /
--    CLOSED_PAID / COMPLETED / FULLY_PAID / FULLY_DELIVERED, three-party
--    close already signed off (V56: account confirms, CEO verifies).
--    Guard: sales.ticket.code is UNIQUE.
-- ---------------------------------------------------------------------
INSERT INTO sales.ticket (
    code, title, status, priority, created_by, assigned_to,
    customer_name, customer_id, project_id, contact_id, note,
    payment_status, fulfillment_status,
    sales_stage, lifecycle, tender_requirement, deposit_policy, entry_channel,
    billing_date, due_date, credit_term_days,
    close_confirmed_by, close_confirmed_at, closed_at,
    win_probability, owner_name
)
SELECT 'UAT-GOLD-01',
       'กระเบื้องนำเข้าโครงการคอนโดโกลเด้นเกท (Golden End-to-End PCR Deal)',
       'closed', 'NORMAL',
       creator.employee_id, assignee.employee_id,
       cust.name, cust.customer_id, proj.project_id, cont.contact_id,
       'Golden fixture: full PCR chain (pricing request -> factory quote -> costing -> '
       || 'CEO decision -> customer quotation -> order confirmation -> deposit/payment -> '
       || 'factory PO -> delivery -> close -> commission) driven to CLOSED_PAID for UAT walkthroughs.',
       'FULLY_PAID', 'FULLY_DELIVERED',
       'CLOSED_PAID', 'COMPLETED', 'NOT_REQUIRED', 'REQUIRED', 'OWNER_DIRECT',
       DATE '2026-06-17', DATE '2026-07-17', 30,
       account.employee_id, TIMESTAMPTZ '2026-07-12 15:00:00+07', TIMESTAMPTZ '2026-07-12 16:30:00+07',
       100, 'คุณสมชาย โกลเด้นเกท (Owner)'
FROM customers.customer cust
JOIN customers.project proj ON proj.customer_id = cust.customer_id
    AND proj.name = 'โครงการคอนโดมิเนียม โกลเด้นเกท ริเวอร์ไซด์'
JOIN customers.contact cont ON cont.customer_id = cust.customer_id
    AND cont.first_name = 'สมชาย' AND cont.last_name = 'โกลเด้นเกท'
JOIN hr.employee creator ON creator.employee_code = 'GLR-0005'
JOIN hr.employee assignee ON assignee.employee_code = 'GLR-0004'
JOIN hr.employee account ON account.employee_code = 'GLR-0013'
WHERE cust.name = 'บริษัท โกลเด้นเกท ดีเวลลอปเมนท์ จำกัด'
AND NOT EXISTS (SELECT 1 FROM sales.ticket t WHERE t.code = 'UAT-GOLD-01');

-- ---------------------------------------------------------------------
-- C. TICKET ITEMS (2 lines). Guard: (ticket_id, model) not exists.
-- ---------------------------------------------------------------------
INSERT INTO sales.ticket_item (
    ticket_id, model, brand, texture, size, color, qty, unit, unit_basis,
    approved_price, currency, sort_order
)
SELECT t.ticket_id, v.model, v.brand, v.texture, v.size, v.color, v.qty, v.unit, 'PIECE',
       v.approved_price, 'THB', v.sort_order
FROM (VALUES
    ('GOLD-6060-MB', 'กระเบื้องพอร์ซเลนนำเข้า', 'มัน',  '60x60', 'หินอ่อน',   500::numeric, 'แผ่น', 737.13::numeric, 0::smallint),
    ('GOLD-3060-WD', 'กระเบื้องลายไม้นำเข้า',   'ด้าน', '30x60', 'สีไม้เข้ม', 300::numeric, 'แผ่น', 590.78::numeric, 1::smallint)
) AS v(model, brand, texture, size, color, qty, unit, approved_price, sort_order)
JOIN sales.ticket t ON t.code = 'UAT-GOLD-01'
WHERE NOT EXISTS (
    SELECT 1 FROM sales.ticket_item ti WHERE ti.ticket_id = t.ticket_id AND ti.model = v.model
);

-- ---------------------------------------------------------------------
-- D. PRICING REQUEST. recipient_type=OWNER. Terminal status
--    QUOTATION_ACCEPTED (Step 5's terminal state) with the Step 6 bridge
--    (order_confirmed_at/by) already fired. Guard: request_code UNIQUE.
-- ---------------------------------------------------------------------
INSERT INTO sales.pricing_request (
    request_code, ticket_id, recipient_type, recipient_contact_id, recipient_label,
    status, requested_by, assigned_import_id, required_date,
    target_currency, note, revision_no,
    submitted_at, picked_up_at,
    order_confirmed_at, order_confirmed_by
)
SELECT 'PCR-UAT-GOLD-01', t.ticket_id, 'OWNER', cont.contact_id, 'คุณสมชาย โกลเด้นเกท (Owner)',
       'QUOTATION_ACCEPTED', rep.employee_id, imp.employee_id, DATE '2026-08-15',
       'THB', 'Owner-direct request: 2 porcelain tile lines for the Golden Gate Riverside condo project, direct factory import.', 1,
       TIMESTAMPTZ '2026-06-02 09:00:00+07', TIMESTAMPTZ '2026-06-03 10:00:00+07',
       TIMESTAMPTZ '2026-06-16 16:00:00+07', rep.employee_id
FROM sales.ticket t
JOIN customers.contact cont ON cont.first_name = 'สมชาย' AND cont.last_name = 'โกลเด้นเกท'
JOIN hr.employee rep ON rep.employee_code = 'GLR-0005'
JOIN hr.employee imp ON imp.employee_code = 'GLR-0004'
WHERE t.code = 'UAT-GOLD-01'
AND NOT EXISTS (SELECT 1 FROM sales.pricing_request pr WHERE pr.request_code = 'PCR-UAT-GOLD-01');

-- ---------------------------------------------------------------------
-- E. PRICING REQUEST ITEMS (2). source_ticket_item_id links back to the
--    ticket items so every downstream table can be joined by ticket-item
--    model. Guard: (pricing_request_id, source_ticket_item_id) not exists.
-- ---------------------------------------------------------------------
INSERT INTO sales.pricing_request_item (
    pricing_request_id, source_ticket_item_id, brand, model, color, texture, size, factory,
    requested_qty, requested_unit, quantity_type, requested_unit_basis, sort_order
)
SELECT pr.pricing_request_id, ti.item_id, ti.brand, ti.model, ti.color, ti.texture, ti.size,
       'Golden Ceramic Factory (Import)',
       ti.qty, ti.unit, 'CONFIRMED', 'PER_PIECE', ti.sort_order
FROM sales.ticket t
JOIN sales.ticket_item ti ON ti.ticket_id = t.ticket_id
JOIN sales.pricing_request pr ON pr.ticket_id = t.ticket_id AND pr.request_code = 'PCR-UAT-GOLD-01'
WHERE t.code = 'UAT-GOLD-01'
AND NOT EXISTS (
    SELECT 1 FROM sales.pricing_request_item pri
    WHERE pri.pricing_request_id = pr.pricing_request_id AND pri.source_ticket_item_id = ti.item_id
);

-- ---------------------------------------------------------------------
-- F. PRICING REQUEST EVENTS — narrative timeline (event_kind is free
--    text, no CHECK constraint, so this cannot violate a schema
--    invariant). Guard: (pricing_request_id, event_kind) not exists —
--    each kind below is used at most once for this request.
-- ---------------------------------------------------------------------
INSERT INTO sales.pricing_request_event (
    pricing_request_id, ticket_id, actor_id, actor_name, event_kind,
    from_status, to_status, message, created_at
)
SELECT pr.pricing_request_id, t.ticket_id, actor.employee_id,
       TRIM(CONCAT_WS(' ', actor.first_name_th, actor.last_name_th)), v.event_kind,
       v.from_status, v.to_status, v.message, v.created_at
FROM (VALUES
    ('SUBMITTED',              'GLR-0005', 'DRAFT',                     'SUBMITTED',            'Owner request submitted for import review.',                 TIMESTAMPTZ '2026-06-02 09:00:00+07'),
    ('PICKED_UP',              'GLR-0004', 'SUBMITTED',                 'IMPORT_REVIEWING',     'Import picked up the request.',                              TIMESTAMPTZ '2026-06-03 10:00:00+07'),
    ('FACTORY_QUOTE_RECEIVED', 'GLR-0004', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS',  'Factory quote received from Golden Ceramic Factory.',        TIMESTAMPTZ '2026-06-10 11:00:00+07'),
    ('COSTING_SUBMITTED',      'GLR-0004', 'COSTING_IN_PROGRESS',       'READY_FOR_CEO_REVIEW', 'Landed-cost costing submitted for CEO review.',              TIMESTAMPTZ '2026-06-12 14:00:00+07'),
    ('CEO_APPROVED',           'GLR-0001', 'CEO_REVIEWING',             'APPROVED_FOR_QUOTATION', 'CEO approved selling price at 35% margin.',                TIMESTAMPTZ '2026-06-13 15:00:00+07'),
    ('QUOTATION_ISSUED',       'GLR-0005', 'APPROVED_FOR_QUOTATION',    'QUOTATION_ISSUED',     'Customer quotation QN-UAT-GOLD-01 issued and sent to owner.', TIMESTAMPTZ '2026-06-14 09:30:00+07'),
    ('QUOTATION_ACCEPTED',     'GLR-0005', 'QUOTATION_ISSUED',          'QUOTATION_ACCEPTED',   'Owner accepted the quotation.',                              TIMESTAMPTZ '2026-06-16 13:00:00+07'),
    ('ORDER_CONFIRMED',        'GLR-0005', 'QUOTATION_ACCEPTED',        'QUOTATION_ACCEPTED',   'Order confirmed; bridged into the legacy deposit/fulfilment pipeline.', TIMESTAMPTZ '2026-06-16 16:00:00+07')
) AS v(event_kind, actor_code, from_status, to_status, message, created_at)
JOIN hr.employee actor ON actor.employee_code = v.actor_code
JOIN sales.ticket t ON t.code = 'UAT-GOLD-01'
JOIN sales.pricing_request pr ON pr.ticket_id = t.ticket_id AND pr.request_code = 'PCR-UAT-GOLD-01'
WHERE NOT EXISTS (
    SELECT 1 FROM sales.pricing_request_event pre
    WHERE pre.pricing_request_id = pr.pricing_request_id AND pre.event_kind = v.event_kind
);

-- ---------------------------------------------------------------------
-- G. FACTORY QUOTE. One factory, one current revision, locked in for
--    costing. Guard: quote_code UNIQUE.
-- ---------------------------------------------------------------------
INSERT INTO sales.factory_quote (
    quote_code, pricing_request_id, factory_name_snapshot, status,
    default_currency, payment_terms, lead_time_text,
    requested_at, received_at, revision_no, is_current, created_by
)
SELECT 'FQ-UAT-GOLD-01', pr.pricing_request_id, 'Golden Ceramic Factory (Import)', 'READY_FOR_COSTING',
       'USD', '30% deposit with PO, 70% before shipment', '35 days ex-factory',
       TIMESTAMPTZ '2026-06-04 09:00:00+07', TIMESTAMPTZ '2026-06-10 11:00:00+07', 1, TRUE, imp.employee_id
FROM sales.ticket t
JOIN sales.pricing_request pr ON pr.ticket_id = t.ticket_id AND pr.request_code = 'PCR-UAT-GOLD-01'
JOIN hr.employee imp ON imp.employee_code = 'GLR-0004'
WHERE t.code = 'UAT-GOLD-01'
AND NOT EXISTS (SELECT 1 FROM sales.factory_quote fq WHERE fq.quote_code = 'FQ-UAT-GOLD-01');

-- ---------------------------------------------------------------------
-- H. FACTORY QUOTE ITEMS (2). Guard: uq_factory_quote_item_request_item
--    (factory_quote_id, pricing_request_item_id).
-- ---------------------------------------------------------------------
INSERT INTO sales.factory_quote_item (
    factory_quote_id, pricing_request_item_id, supplier_product_code,
    quoted_quantity, quoted_unit, unit_basis, raw_unit_price, currency, sort_order
)
SELECT fq.factory_quote_id, pri.pricing_request_item_id, v.supplier_code,
       ti.qty, ti.unit, 'PER_PIECE', v.raw_unit_price, 'USD', v.sort_order
FROM (VALUES
    ('GOLD-6060-MB', 'GCF-MB6060', 12.50::numeric, 0::int),
    ('GOLD-3060-WD', 'GCF-WD3060', 9.80::numeric,  1::int)
) AS v(model, supplier_code, raw_unit_price, sort_order)
JOIN sales.ticket t ON t.code = 'UAT-GOLD-01'
JOIN sales.ticket_item ti ON ti.ticket_id = t.ticket_id AND ti.model = v.model
JOIN sales.pricing_request pr ON pr.ticket_id = t.ticket_id AND pr.request_code = 'PCR-UAT-GOLD-01'
JOIN sales.pricing_request_item pri ON pri.pricing_request_id = pr.pricing_request_id AND pri.source_ticket_item_id = ti.item_id
JOIN sales.factory_quote fq ON fq.pricing_request_id = pr.pricing_request_id AND fq.quote_code = 'FQ-UAT-GOLD-01'
WHERE NOT EXISTS (
    SELECT 1 FROM sales.factory_quote_item fqi
    WHERE fqi.factory_quote_id = fq.factory_quote_id AND fqi.pricing_request_item_id = pri.pricing_request_item_id
);

-- ---------------------------------------------------------------------
-- I. PRICING COSTING (version 1, SUBMITTED). Guard: costing_code UNIQUE.
-- ---------------------------------------------------------------------
INSERT INTO sales.pricing_costing (
    costing_code, pricing_request_id, version_no, status,
    created_by, calculated_at, submitted_by, submitted_at, total_landed_cost_thb
)
SELECT 'PC-UAT-GOLD-01', pr.pricing_request_id, 1, 'SUBMITTED',
       imp.employee_id, TIMESTAMPTZ '2026-06-11 10:00:00+07', imp.employee_id, TIMESTAMPTZ '2026-06-12 14:00:00+07',
       404178.5000
FROM sales.ticket t
JOIN sales.pricing_request pr ON pr.ticket_id = t.ticket_id AND pr.request_code = 'PCR-UAT-GOLD-01'
JOIN hr.employee imp ON imp.employee_code = 'GLR-0004'
WHERE t.code = 'UAT-GOLD-01'
AND NOT EXISTS (SELECT 1 FROM sales.pricing_costing pc WHERE pc.costing_code = 'PC-UAT-GOLD-01');

-- ---------------------------------------------------------------------
-- J. PRICING COSTING ITEMS (2). fx_rate 36.50 THB/USD, 10% import duty,
--    landed cost fully worked per line. Guard: uq_pricing_costing_item_
--    request_item (pricing_costing_id, pricing_request_item_id).
-- ---------------------------------------------------------------------
INSERT INTO sales.pricing_costing_item (
    pricing_costing_id, pricing_request_item_id, factory_quote_id, factory_quote_item_id,
    factory_quote_revision_no, factory_name, supplier_quote_ref,
    raw_unit_price, raw_currency, raw_unit, unit_basis,
    requested_quantity, requested_unit,
    fx_rate, fx_source, fx_effective_date,
    calculation_config_id, calculation_config_version,
    goods_cost_thb, freight_cost_thb, insurance_cost_thb, import_duty_thb,
    inland_transport_cost_thb, other_cost_thb, cif_cost_thb,
    landed_cost_per_unit_thb, total_landed_cost_thb, calculated_at,
    requested_unit_basis, normalized_quantity_pieces
)
SELECT pc.pricing_costing_id, pri.pricing_request_item_id, fq.factory_quote_id, fqi.factory_quote_item_id,
       1, 'Golden Ceramic Factory (Import)', v.supplier_code,
       v.raw_unit_price, 'USD', 'แผ่น', 'PER_PIECE',
       ti.qty, ti.unit,
       36.50, 'BOT_REFERENCE', DATE '2026-06-11',
       1, 1,
       v.goods_cost_thb, v.freight_cost_thb, v.insurance_cost_thb, v.import_duty_thb,
       v.inland_cost_thb, 0, v.cif_cost_thb,
       v.landed_cost_per_unit_thb, v.total_landed_cost_thb, TIMESTAMPTZ '2026-06-11 10:00:00+07',
       'PER_PIECE', ti.qty
FROM (VALUES
    ('GOLD-6060-MB', 'GCF-MB6060', 12.50::numeric,
        228125.0000::numeric, 15000.0000::numeric, 2000.0000::numeric, 22812.5000::numeric,
        5000.0000::numeric, 245125.0000::numeric, 545.8750::numeric, 272937.5000::numeric),
    ('GOLD-3060-WD', 'GCF-WD3060', 9.80::numeric,
        107310.0000::numeric, 9000.0000::numeric, 1200.0000::numeric, 10731.0000::numeric,
        3000.0000::numeric, 117510.0000::numeric, 437.4700::numeric, 131241.0000::numeric)
) AS v(model, supplier_code, raw_unit_price,
       goods_cost_thb, freight_cost_thb, insurance_cost_thb, import_duty_thb,
       inland_cost_thb, cif_cost_thb, landed_cost_per_unit_thb, total_landed_cost_thb)
JOIN sales.ticket t ON t.code = 'UAT-GOLD-01'
JOIN sales.ticket_item ti ON ti.ticket_id = t.ticket_id AND ti.model = v.model
JOIN sales.pricing_request pr ON pr.ticket_id = t.ticket_id AND pr.request_code = 'PCR-UAT-GOLD-01'
JOIN sales.pricing_request_item pri ON pri.pricing_request_id = pr.pricing_request_id AND pri.source_ticket_item_id = ti.item_id
JOIN sales.factory_quote fq ON fq.pricing_request_id = pr.pricing_request_id AND fq.quote_code = 'FQ-UAT-GOLD-01'
JOIN sales.factory_quote_item fqi ON fqi.factory_quote_id = fq.factory_quote_id AND fqi.pricing_request_item_id = pri.pricing_request_item_id
JOIN sales.pricing_costing pc ON pc.pricing_request_id = pr.pricing_request_id AND pc.costing_code = 'PC-UAT-GOLD-01'
WHERE NOT EXISTS (
    SELECT 1 FROM sales.pricing_costing_item pci
    WHERE pci.pricing_costing_id = pc.pricing_costing_id AND pci.pricing_request_item_id = pri.pricing_request_item_id
);

-- ---------------------------------------------------------------------
-- K. PRICING DECISION (version 1, APPROVED by CEO, 35% margin).
--    Guard: decision_code UNIQUE.
-- ---------------------------------------------------------------------
INSERT INTO sales.pricing_decision (
    decision_code, pricing_request_id, pricing_costing_id, decision_version_no, status,
    default_margin_pct, currency, fx_rate_used, fx_source, fx_effective_date,
    ceo_note, created_by, approved_by, approved_at
)
SELECT 'PD-UAT-GOLD-01', pr.pricing_request_id, pc.pricing_costing_id, 1, 'APPROVED',
       0.35, 'THB', 1, 'THB', DATE '2026-06-13',
       'Approved at standard 35% margin for a repeat owner-direct import deal.',
       ceo.employee_id, ceo.employee_id, TIMESTAMPTZ '2026-06-13 15:00:00+07'
FROM sales.ticket t
JOIN sales.pricing_request pr ON pr.ticket_id = t.ticket_id AND pr.request_code = 'PCR-UAT-GOLD-01'
JOIN sales.pricing_costing pc ON pc.pricing_request_id = pr.pricing_request_id AND pc.costing_code = 'PC-UAT-GOLD-01'
JOIN hr.employee ceo ON ceo.employee_code = 'GLR-0001'
WHERE t.code = 'UAT-GOLD-01'
AND NOT EXISTS (SELECT 1 FROM sales.pricing_decision pd WHERE pd.decision_code = 'PD-UAT-GOLD-01');

-- ---------------------------------------------------------------------
-- L. PRICING DECISION ITEMS (2). Selling price = landed cost * 1.35;
--    minimum = landed cost * 1.15. Guard: uq_pricing_decision_item_
--    request_item (pricing_decision_id, pricing_request_item_id).
-- ---------------------------------------------------------------------
INSERT INTO sales.pricing_decision_item (
    pricing_decision_id, pricing_request_item_id, pricing_costing_item_id,
    requested_unit_basis, requested_quantity, normalized_quantity_pieces,
    frozen_landed_cost_per_piece_thb, frozen_landed_cost_per_requested_unit_thb,
    currency, proposed_margin_pct, approved_margin_pct,
    proposed_selling_price_per_requested_unit, approved_selling_price_per_requested_unit,
    discount_ceiling_pct, minimum_selling_price_per_requested_unit
)
SELECT pd.pricing_decision_id, pri.pricing_request_item_id, pci.pricing_costing_item_id,
       'PER_PIECE', ti.qty, ti.qty,
       v.landed_cost_per_unit_thb, v.landed_cost_per_unit_thb,
       'THB', 0.35, 0.35,
       v.selling_price, v.selling_price,
       0.05, v.min_selling_price
FROM (VALUES
    ('GOLD-6060-MB', 545.8750::numeric, 737.1313::numeric, 627.7563::numeric),
    ('GOLD-3060-WD', 437.4700::numeric, 590.7845::numeric, 503.0905::numeric)
) AS v(model, landed_cost_per_unit_thb, selling_price, min_selling_price)
JOIN sales.ticket t ON t.code = 'UAT-GOLD-01'
JOIN sales.ticket_item ti ON ti.ticket_id = t.ticket_id AND ti.model = v.model
JOIN sales.pricing_request pr ON pr.ticket_id = t.ticket_id AND pr.request_code = 'PCR-UAT-GOLD-01'
JOIN sales.pricing_request_item pri ON pri.pricing_request_id = pr.pricing_request_id AND pri.source_ticket_item_id = ti.item_id
JOIN sales.pricing_costing pc ON pc.pricing_request_id = pr.pricing_request_id AND pc.costing_code = 'PC-UAT-GOLD-01'
JOIN sales.pricing_costing_item pci ON pci.pricing_costing_id = pc.pricing_costing_id AND pci.pricing_request_item_id = pri.pricing_request_item_id
JOIN sales.pricing_decision pd ON pd.pricing_request_id = pr.pricing_request_id AND pd.decision_code = 'PD-UAT-GOLD-01'
WHERE NOT EXISTS (
    SELECT 1 FROM sales.pricing_decision_item pdi
    WHERE pdi.pricing_decision_id = pd.pricing_decision_id AND pdi.pricing_request_item_id = pri.pricing_request_item_id
);

-- ---------------------------------------------------------------------
-- M. CUSTOMER QUOTATION (Step 4 extends sales.quotation in place — no
--    separate customer_quotation table, per V74's owner decision).
--    Guard: number is GLOBALLY UNIQUE ('QN-UAT-GOLD-01', distinct from
--    both QN-2026-NNNNN app-generated numbers and V909's QN-UAT-00xx
--    range) AND ux_quotation_ticket_recipient_version(ticket_id,
--    recipient_type, quotation_version).
-- ---------------------------------------------------------------------
INSERT INTO sales.quotation (
    ticket_id, number, issued_by, issued_at, doc_status, quotation_version, total_amount, currency,
    recipient_type, recipient_label, payment_terms, lead_time, delivery_terms, validity_date,
    sent_at, accepted_at, offer_date, delivery_days,
    pricing_request_id, pricing_decision_id, quotation_revision_no,
    customer_notes, outcome_note, outcome_recorded_by, outcome_recorded_at,
    customer_name, customer_address, project_name, deposit_pct
)
SELECT t.ticket_id, 'QN-UAT-GOLD-01', rep.employee_id, TIMESTAMPTZ '2026-06-14 09:30:00+07',
       'ACCEPTED', 1, 584007.07, 'THB',
       'OWNER', 'คุณสมชาย โกลเด้นเกท (Owner)',
       'มัดจำ 50% ก่อนสั่งผลิต ส่วนที่เหลือชำระก่อนส่งมอบ', '60 วันหลังยืนยันคำสั่งซื้อ',
       'ส่งมอบถึงหน้างานโครงการ', DATE '2026-07-14',
       TIMESTAMPTZ '2026-06-14 09:30:00+07', TIMESTAMPTZ '2026-06-16 13:00:00+07', DATE '2026-06-14', 60,
       pr.pricing_request_id, pd.pricing_decision_id, 1,
       'ราคานี้เป็นราคานำเข้าตรงจากโรงงาน รวมค่าขนส่งและภาษีนำเข้าแล้ว',
       'Owner accepted by phone, confirmed via LINE the same day.', rep.employee_id, TIMESTAMPTZ '2026-06-16 13:00:00+07',
       cust.name, cust.address, proj.name, 0.5
FROM sales.ticket t
JOIN customers.customer cust ON cust.name = 'บริษัท โกลเด้นเกท ดีเวลลอปเมนท์ จำกัด'
JOIN customers.project proj ON proj.customer_id = cust.customer_id
    AND proj.name = 'โครงการคอนโดมิเนียม โกลเด้นเกท ริเวอร์ไซด์'
JOIN sales.pricing_request pr ON pr.ticket_id = t.ticket_id AND pr.request_code = 'PCR-UAT-GOLD-01'
JOIN sales.pricing_decision pd ON pd.pricing_request_id = pr.pricing_request_id AND pd.decision_code = 'PD-UAT-GOLD-01'
JOIN hr.employee rep ON rep.employee_code = 'GLR-0005'
WHERE t.code = 'UAT-GOLD-01'
AND NOT EXISTS (SELECT 1 FROM sales.quotation q WHERE q.number = 'QN-UAT-GOLD-01')
AND NOT EXISTS (
    SELECT 1 FROM sales.quotation q2
    WHERE q2.ticket_id = t.ticket_id AND q2.recipient_type = 'OWNER' AND q2.quotation_version = 1
);

-- ---------------------------------------------------------------------
-- N. QUOTATION ITEMS (2) — legacy rendering columns + Step 4 business
--    columns on the same row (per V74). Guard: (quotation_id, seq) not
--    exists (no formal unique constraint, but seq is a natural per-quote
--    key by house convention).
-- ---------------------------------------------------------------------
INSERT INTO sales.quotation_item (
    quotation_id, seq, brand, model, color, texture, size, qty, unit_basis, raw_unit, unit_price, amount,
    pricing_request_item_id, pricing_decision_item_id,
    requested_unit_basis, requested_quantity, approved_unit_price, sales_discount, final_unit_price,
    line_subtotal, vat, line_total, description
)
SELECT q.quotation_id, v.seq, ti.brand, ti.model, ti.color, ti.texture, ti.size, ti.qty, 'PIECE', ti.unit,
       v.unit_price, v.amount,
       pri.pricing_request_item_id, pdi.pricing_decision_item_id,
       'PER_PIECE', ti.qty, v.approved_unit_price, 0, v.approved_unit_price,
       v.line_subtotal, v.vat, v.line_total, v.description
FROM (VALUES
    ('GOLD-6060-MB', 1, 737.13::numeric, 368565.00::numeric, 737.1313::numeric,
        368565.65::numeric, 25799.60::numeric, 394365.25::numeric,
        'กระเบื้องพอร์ซเลนนำเข้า 60x60 ผิวมัน สีหินอ่อน'),
    ('GOLD-3060-WD', 2, 590.78::numeric, 177234.00::numeric, 590.7845::numeric,
        177235.35::numeric, 12406.47::numeric, 189641.82::numeric,
        'กระเบื้องลายไม้นำเข้า 30x60 ผิวด้าน สีไม้เข้ม')
) AS v(model, seq, unit_price, amount, approved_unit_price, line_subtotal, vat, line_total, description)
JOIN sales.ticket t ON t.code = 'UAT-GOLD-01'
JOIN sales.ticket_item ti ON ti.ticket_id = t.ticket_id AND ti.model = v.model
JOIN sales.pricing_request pr ON pr.ticket_id = t.ticket_id AND pr.request_code = 'PCR-UAT-GOLD-01'
JOIN sales.pricing_request_item pri ON pri.pricing_request_id = pr.pricing_request_id AND pri.source_ticket_item_id = ti.item_id
JOIN sales.pricing_decision pd ON pd.pricing_request_id = pr.pricing_request_id AND pd.decision_code = 'PD-UAT-GOLD-01'
JOIN sales.pricing_decision_item pdi ON pdi.pricing_decision_id = pd.pricing_decision_id AND pdi.pricing_request_item_id = pri.pricing_request_item_id
JOIN sales.quotation q ON q.ticket_id = t.ticket_id AND q.number = 'QN-UAT-GOLD-01'
WHERE NOT EXISTS (
    SELECT 1 FROM sales.quotation_item qi WHERE qi.quotation_id = q.quotation_id AND qi.seq = v.seq
);

-- ---------------------------------------------------------------------
-- O. DEPOSIT NOTICE. Guard BOTH unique keys: ux_deposit_notice_ticket_
--    version(ticket_id, version) AND the GLOBAL ux_deposit_notice_doc_
--    number(doc_number) — same defensive pattern V909 uses, in case the
--    hosted UAT DB already carries a tester-issued version-1 notice on
--    this ticket or the doc_number collides elsewhere.
-- ---------------------------------------------------------------------
INSERT INTO sales.deposit_notice (
    ticket_id, doc_type, version, doc_number, issue_date, status,
    customer_name, customer_address, project_name, reference,
    currency, deposit_percent, subtotal, deposit_amount, vat_percent, vat_amount, total_payable,
    issued_by_id, issued_by_name
)
SELECT t.ticket_id, 'DEPOSIT_NOTICE', 1, 'DN-UAT-GOLD-01', DATE '2026-06-17', 'ISSUED',
       cust.name, cust.address, proj.name, 'QN-UAT-GOLD-01',
       'THB', 0.5, 545801.00, 292003.54, 0.07, 38206.07, 584007.07,
       imp.employee_id, TRIM(CONCAT_WS(' ', imp.first_name_th, imp.last_name_th))
FROM sales.ticket t
JOIN customers.customer cust ON cust.name = 'บริษัท โกลเด้นเกท ดีเวลลอปเมนท์ จำกัด'
JOIN customers.project proj ON proj.customer_id = cust.customer_id
    AND proj.name = 'โครงการคอนโดมิเนียม โกลเด้นเกท ริเวอร์ไซด์'
JOIN hr.employee imp ON imp.employee_code = 'GLR-0004'
WHERE t.code = 'UAT-GOLD-01'
AND NOT EXISTS (SELECT 1 FROM sales.deposit_notice dn WHERE dn.ticket_id = t.ticket_id AND dn.version = 1)
AND NOT EXISTS (SELECT 1 FROM sales.deposit_notice dn2 WHERE dn2.doc_number = 'DN-UAT-GOLD-01');

-- ---------------------------------------------------------------------
-- P. PAYMENT RECEIPTS (2: DEPOSIT + BALANCE, summing to the full
--    584,007.07 payable — drives payment_status=FULLY_PAID). Guard:
--    ux_payment_receipt_ref(ticket_id, receipt_ref).
-- ---------------------------------------------------------------------
INSERT INTO sales.payment_receipt (
    ticket_id, kind, amount, currency, received_at, recorded_by, note, deposit_notice_id, receipt_ref
)
SELECT t.ticket_id, v.kind, v.amount, 'THB', v.received_at, account.employee_id, v.note,
       CASE WHEN v.link_deposit_notice THEN dn.deposit_notice_id ELSE NULL END,
       v.receipt_ref
FROM (VALUES
    ('DEPOSIT', 292003.54::numeric, TIMESTAMPTZ '2026-06-18 15:00:00+07', 'Deposit received (50%) for golden fixture', 'UAT-RCPT-GOLD-01-D', TRUE),
    ('BALANCE', 292003.53::numeric, TIMESTAMPTZ '2026-07-11 16:00:00+07', 'Final balance received after full delivery', 'UAT-RCPT-GOLD-01-B', FALSE)
) AS v(kind, amount, received_at, note, receipt_ref, link_deposit_notice)
JOIN hr.employee account ON account.employee_code = 'GLR-0013'
JOIN sales.ticket t ON t.code = 'UAT-GOLD-01'
LEFT JOIN sales.deposit_notice dn ON dn.ticket_id = t.ticket_id AND dn.doc_number = 'DN-UAT-GOLD-01'
WHERE NOT EXISTS (
    SELECT 1 FROM sales.payment_receipt existing
    WHERE existing.ticket_id = t.ticket_id AND existing.receipt_ref = v.receipt_ref
);

-- ---------------------------------------------------------------------
-- Q. FACTORY PURCHASE ORDER (RECEIVED — full procurement chain closed
--    out). Guard: po_number UNIQUE and uq_factory_po_request_factory
--    (pricing_request_id, factory_name).
-- ---------------------------------------------------------------------
INSERT INTO sales.factory_purchase_order (
    po_number, pricing_request_id, ticket_id, factory_name, status,
    supplier_proforma_ref, currency, total_amount, etd, eta, container_ref, customs_status,
    actual_landed_cost_thb, created_by, received_at
)
SELECT 'PO-UAT-GOLD-01', pr.pricing_request_id, t.ticket_id, 'Golden Ceramic Factory (Import)', 'RECEIVED',
       'PI-GCF-2026-0614', 'USD', 9190.0000, DATE '2026-06-20', DATE '2026-07-05', 'TCLU-GOLD-0001', 'CLEARED',
       404178.5000, imp.employee_id, TIMESTAMPTZ '2026-07-06 10:00:00+07'
FROM sales.ticket t
JOIN sales.pricing_request pr ON pr.ticket_id = t.ticket_id AND pr.request_code = 'PCR-UAT-GOLD-01'
JOIN hr.employee imp ON imp.employee_code = 'GLR-0004'
WHERE t.code = 'UAT-GOLD-01'
AND NOT EXISTS (SELECT 1 FROM sales.factory_purchase_order po WHERE po.po_number = 'PO-UAT-GOLD-01');

-- ---------------------------------------------------------------------
-- R. FACTORY PURCHASE ORDER ITEMS (2), fully received with QC notes.
--    Guard: uq_factory_po_item_costing_item(pricing_costing_item_id) —
--    a costing item can be procured on at most one PO ever.
-- ---------------------------------------------------------------------
INSERT INTO sales.factory_purchase_order_item (
    factory_purchase_order_id, pricing_costing_item_id, pricing_request_item_id,
    quantity, unit_price, currency, line_total, qty_received, qc_note
)
SELECT po.factory_purchase_order_id, pci.pricing_costing_item_id, pri.pricing_request_item_id,
       ti.qty, v.unit_price, 'USD', v.line_total, ti.qty, 'ตรวจนับครบตามจำนวน ไม่มีความเสียหาย'
FROM (VALUES
    ('GOLD-6060-MB', 12.50::numeric, 6250.0000::numeric),
    ('GOLD-3060-WD', 9.80::numeric,  2940.0000::numeric)
) AS v(model, unit_price, line_total)
JOIN sales.ticket t ON t.code = 'UAT-GOLD-01'
JOIN sales.ticket_item ti ON ti.ticket_id = t.ticket_id AND ti.model = v.model
JOIN sales.pricing_request pr ON pr.ticket_id = t.ticket_id AND pr.request_code = 'PCR-UAT-GOLD-01'
JOIN sales.pricing_request_item pri ON pri.pricing_request_id = pr.pricing_request_id AND pri.source_ticket_item_id = ti.item_id
JOIN sales.pricing_costing pc ON pc.pricing_request_id = pr.pricing_request_id AND pc.costing_code = 'PC-UAT-GOLD-01'
JOIN sales.pricing_costing_item pci ON pci.pricing_costing_id = pc.pricing_costing_id AND pci.pricing_request_item_id = pri.pricing_request_item_id
JOIN sales.factory_purchase_order po ON po.pricing_request_id = pr.pricing_request_id AND po.po_number = 'PO-UAT-GOLD-01'
WHERE NOT EXISTS (
    SELECT 1 FROM sales.factory_purchase_order_item poi WHERE poi.pricing_costing_item_id = pci.pricing_costing_item_id
);

-- ---------------------------------------------------------------------
-- S. DELIVERY RECORD + ITEMS — full delivery (drives fulfillment_status
--    = FULLY_DELIVERED). Guard on delivery_record: (ticket_id, note) not
--    exists (mirrors V909's own guard shape for this table, which has no
--    formal unique constraint). Guard on delivery_record_item:
--    (delivery_id, item_id) not exists.
-- ---------------------------------------------------------------------
INSERT INTO sales.delivery_record (ticket_id, source, delivered_by, delivered_at, note, recipient_name)
SELECT t.ticket_id, 'WAREHOUSE', wh.employee_id, TIMESTAMPTZ '2026-07-10 10:00:00+07',
       'Golden fixture: full delivery of both lines to the Golden Gate Riverside site', 'คุณสมชาย โกลเด้นเกท (Owner)'
FROM sales.ticket t
JOIN hr.employee wh ON wh.employee_code = 'GLR-0008'
WHERE t.code = 'UAT-GOLD-01'
AND NOT EXISTS (
    SELECT 1 FROM sales.delivery_record dr
    WHERE dr.ticket_id = t.ticket_id
      AND dr.note = 'Golden fixture: full delivery of both lines to the Golden Gate Riverside site'
);

INSERT INTO sales.delivery_record_item (delivery_id, item_id, qty)
SELECT dr.delivery_id, ti.item_id, ti.qty
FROM sales.ticket t
JOIN sales.ticket_item ti ON ti.ticket_id = t.ticket_id
JOIN sales.delivery_record dr ON dr.ticket_id = t.ticket_id
    AND dr.note = 'Golden fixture: full delivery of both lines to the Golden Gate Riverside site'
WHERE t.code = 'UAT-GOLD-01'
AND NOT EXISTS (
    SELECT 1 FROM sales.delivery_record_item existing
    WHERE existing.delivery_id = dr.delivery_id AND existing.item_id = ti.item_id
);

-- ---------------------------------------------------------------------
-- T. Backfill ticket_item.qty_delivered to fully delivered (matches the
--    delivery_record_item rows above; this table has no unique guard to
--    check, so the UPDATE is naturally idempotent — WHERE clause only
--    touches rows not already at the target value).
-- ---------------------------------------------------------------------
UPDATE sales.ticket_item ti
SET qty_delivered = ti.qty
FROM sales.ticket t
WHERE ti.ticket_id = t.ticket_id AND t.code = 'UAT-GOLD-01' AND ti.qty_delivered <> ti.qty;

-- ---------------------------------------------------------------------
-- U. DEAL ACTIVITY — a short, plausible follow-up trail. Guard:
--    (ticket_id, activity_date, kind) not exists.
-- ---------------------------------------------------------------------
INSERT INTO sales.deal_activity (ticket_id, activity_date, kind, note, created_by_id)
SELECT t.ticket_id, v.activity_date, v.kind, v.note, rep.employee_id
FROM (VALUES
    (DATE '2026-06-01', 'MEETING',            'Kickoff meeting with owner to review project scope and tile requirements.'),
    (DATE '2026-06-05', 'SITE_VISIT',         'Site visit to confirm delivery access for the riverside condo project.'),
    (DATE '2026-06-14', 'QUOTATION_FOLLOWUP', 'Followed up by phone after sending QN-UAT-GOLD-01.')
) AS v(activity_date, kind, note)
JOIN sales.ticket t ON t.code = 'UAT-GOLD-01'
JOIN hr.employee rep ON rep.employee_code = 'GLR-0005'
WHERE NOT EXISTS (
    SELECT 1 FROM sales.deal_activity da
    WHERE da.ticket_id = t.ticket_id AND da.activity_date = v.activity_date AND da.kind = v.kind
);

-- ---------------------------------------------------------------------
-- V. COMMISSION (invoice + APPROVED SALE commission, full V35
--    dual-approval chain, tied to the deal via source_ticket_id and V79's
--    deal_payable_amount_snapshot). Guard: invoice_number UNIQUE;
--    commission_record guarded by (invoice_id, sales_rep_id, kind) not
--    exists, mirroring V903's own guard shape exactly.
-- ---------------------------------------------------------------------
INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount)
SELECT 'UAT-INV-GOLD-01', DATE '2026-07-11', 584007.07
WHERE NOT EXISTS (SELECT 1 FROM sales.invoice_details i WHERE i.invoice_number = 'UAT-INV-GOLD-01');

INSERT INTO sales.commission_record (
    invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
    payroll_month, actual_received, commissionable_base,
    manager_approved_by, manager_approved_at, ceo_approved_by, ceo_approved_at,
    approved_by_id, approved_at,
    deal_payable_amount_snapshot, deal_amount_mismatch, weight_multiplier
)
SELECT inv.invoice_id, t.ticket_id, rep.employee_id, rep.employee_id, 'SALE', 'APPROVED',
       DATE '2026-07-01', 584007.07, 584007.07,
       mgr.employee_id, TIMESTAMPTZ '2026-07-12 09:00:00+07',
       ceo.employee_id, TIMESTAMPTZ '2026-07-13 10:00:00+07',
       ceo.employee_id, TIMESTAMPTZ '2026-07-13 10:00:00+07',
       584007.07, FALSE, 1
FROM sales.invoice_details inv
JOIN sales.ticket t ON t.code = 'UAT-GOLD-01'
JOIN hr.employee rep ON rep.employee_code = 'GLR-0005'
JOIN hr.employee mgr ON mgr.employee_code = 'GLR-0007'
JOIN hr.employee ceo ON ceo.employee_code = 'GLR-0001'
WHERE inv.invoice_number = 'UAT-INV-GOLD-01'
AND NOT EXISTS (
    SELECT 1 FROM sales.commission_record cr
    WHERE cr.invoice_id = inv.invoice_id AND cr.sales_rep_id = rep.employee_id AND cr.kind = 'SALE'
);
