// Mock backend for VITE_USE_MOCKS=true — the default verification surface for the
// `frontend-mock` launch config that devs, QA and coding agents drive.
//
// THE CONTRACT (see CLAUDE.md "Mock API contract", issue #201):
//   - Endpoints and DTO shapes ARE meant to be a faithful stand-in for the Spring
//     backend. `contract.test.js` enforces the method surface against hrApi.js.
//   - Authorization is NOT authoritative. The gates below approximate the Java
//     services and are known to diverge in places. Never read a permission rule
//     off this file — verify it against the Java service. A mock more permissive
//     than production is the dangerous direction: you only find out in prod.
//
// Each namespace below names the Java class it mirrors. Keep those pointers
// accurate when editing — they are how the next reader finds the source of truth.

import { createDemoDatabase } from '../data/demoData.js';
// Deal pipeline (V50): stage order + write gates shared with the pages so the
// mock's monotonic/gate behavior can't drift from the UI's; the authoritative
// rules live in TicketService (backend ticket/).
import {
  canMarkLost as dealCanMarkLost,
  canSetStage as dealCanSetStage,
  CANCEL_REASONS as DEAL_CANCEL_REASONS,
  isRoutineBackwardMove,
  LOST_REASONS as DEAL_LOST_REASONS,
  stageIndex as dealStageIndex,
} from '../features/tickets/stageMeta.js';
// PricingRequest (commit 6): status transition table + option lists shared
// with the UI so the mock's gates can't drift from PricingRequestService's —
// the authoritative rules live in the backend pricingrequest/ package.
import {
  canTransition as pricingRequestCanTransition,
  QUANTITY_TYPE_OPTIONS as PRICING_REQUEST_QUANTITY_TYPE_OPTIONS,
  RECIPIENT_OPTIONS as PRICING_REQUEST_RECIPIENT_OPTIONS,
} from '../features/pricingRequests/pricingRequestMeta.js';

const db = createDemoDatabase();

function normalizeQuotation(q, ticket, index = 0) {
  return {
    ...q,
    ticketId: q.ticketId ?? ticket.id,
    quotationVersion: q.quotationVersion ?? index + 1,
    docStatus: q.docStatus ?? 'ISSUED',
    recipientType: q.recipientType ?? 'UNSPECIFIED',
    recipientLabel: q.recipientLabel ?? null,
    paymentTerms: q.paymentTerms ?? null,
    leadTime: q.leadTime ?? null,
    deliveryTerms: q.deliveryTerms ?? null,
    validityDate: q.validityDate ?? null,
    sentAt: q.sentAt ?? null,
    acceptedAt: q.acceptedAt ?? null,
    rejectedAt: q.rejectedAt ?? null,
    parentQuotationId: q.parentQuotationId ?? null,
  };
}

// Deal pipeline backfill — mirrors V50__deal_sales_pipeline.sql's UPDATE so the
// demo deals always land exactly where the real migration would put them.
for (const t of db.tickets) {
  if (t.salesStage) continue;
  if (t.paymentStatus === 'FULLY_PAID' || t.status === 'closed') t.salesStage = 'CLOSED_PAID';
  else if (t.fulfillmentStatus != null) t.salesStage = 'PROCUREMENT';
  else if (['DEPOSIT_PAID', 'AWAITING_FINAL_PAYMENT'].includes(t.paymentStatus)) t.salesStage = 'DEPOSIT_RECEIVED';
  else if (['CUSTOMER_CONFIRMED', 'DEPOSIT_NOTICE_ISSUED'].includes(t.paymentStatus)) t.salesStage = 'ORDER_RECEIVED';
  else if (['quotation_issued', 'document_issued'].includes(t.status)) t.salesStage = 'QUOTE_BUYER';
  else t.salesStage = 'QUOTE_DESIGN_SIDE';
  t.lostReason = t.lostReason ?? null;
  t.lostAt = t.lostAt ?? null;
  t.stageUpdatedAt = t.stageUpdatedAt ?? t.updatedAt;
  t.lifecycle = t.lifecycle ?? (t.lostReason ? 'CLOSED_LOST'
    : t.status === 'cancelled' ? 'CANCELLED'
      : t.status === 'closed' ? 'COMPLETED'
        : 'ACTIVE');
  t.tenderRequirement = t.tenderRequirement ?? 'UNKNOWN';
  t.depositPolicy = t.depositPolicy ?? 'REQUIRED';
  t.depositPolicyReason = t.depositPolicyReason ?? null;
  t.entryChannel = t.entryChannel ?? 'DESIGNER_LED';
  const existingQuotations = t.quotations ?? (t.quotation ? [t.quotation] : []);
  t.quotations = existingQuotations.map((q, index) => normalizeQuotation(q, t, index));
  if (t.id === 6 && t.quotations.length === 1) {
    t.quotations[0] = { ...t.quotations[0], recipientType: 'DESIGNER', recipientLabel: 'Premium Design Group' };
    t.quotations.unshift(normalizeQuotation({
      ...t.quotations[0],
      id: 101,
      number: 'QT-2026-0101',
      issuedAt: '2026-06-17T09:30:00Z',
      recipientType: 'OWNER',
      recipientLabel: t.customerName,
      quotationVersion: 1,
      docStatus: 'SENT',
      sentAt: '2026-06-17T10:00:00Z',
      parentQuotationId: null,
    }, t, 0));
  }
  t.quotation = t.quotations[0] ?? null;
  t.items = (t.items ?? []).map((item) => ({
    ...item,
    qtyDelivered: item.qtyDelivered ?? 0,
    qtyFromStock: item.qtyFromStock ?? 0,
    stockNote: item.stockNote ?? null,
  }));
}
db.paymentReceipts = db.paymentReceipts || [
  { receiptId: 1, ticketId: 12, kind: 'DEPOSIT', amount: 65000, currency: 'THB', receivedAt: '2026-06-18T08:00:00Z', recordedById: 11, recordedByName: 'คุณบัญชี การเงิน', note: 'รับมัดจำจากใบแจ้งยอด', depositNoticeId: null, receiptRef: 'MOCK-12-DEP', createdAt: '2026-06-18T08:00:00Z' },
  { receiptId: 2, ticketId: 13, kind: 'DEPOSIT', amount: 66250, currency: 'THB', receivedAt: '2026-06-02T08:00:00Z', recordedById: 11, recordedByName: 'คุณบัญชี การเงิน', note: 'รับมัดจำ', depositNoticeId: null, receiptRef: 'MOCK-13-DEP', createdAt: '2026-06-02T08:00:00Z' },
  { receiptId: 3, ticketId: 14, kind: 'DEPOSIT', amount: 312000, currency: 'THB', receivedAt: '2026-05-20T08:00:00Z', recordedById: 11, recordedByName: 'คุณบัญชี การเงิน', note: 'รับมัดจำ', depositNoticeId: null, receiptRef: 'MOCK-14-DEP', createdAt: '2026-05-20T08:00:00Z' },
  { receiptId: 4, ticketId: 14, kind: 'BALANCE', amount: 312000, currency: 'THB', receivedAt: '2026-07-05T08:00:00Z', recordedById: 11, recordedByName: 'คุณบัญชี การเงิน', note: 'รับชำระส่วนที่เหลือ', depositNoticeId: null, receiptRef: 'MOCK-14-BAL', createdAt: '2026-07-05T08:00:00Z' },
];
const partialDeliveryDemo = db.tickets.find((t) => t.id === 13);
if (partialDeliveryDemo?.items?.[0]) {
  partialDeliveryDemo.items[0].qtyDelivered = 200;
  partialDeliveryDemo.fulfillmentStatus = 'PARTIALLY_DELIVERED';
}
const onHoldDemoTicket = db.tickets.find((t) => t.id === 15);
if (onHoldDemoTicket) {
  onHoldDemoTicket.lifecycle = 'ON_HOLD';
}
const dormantDemoTicket = db.tickets.find((t) => t.id === 11);
if (dormantDemoTicket) {
  dormantDemoTicket.lifecycle = 'DORMANT';
}
db.deliveryRecords = db.deliveryRecords || [
  {
    deliveryId: 1,
    ticketId: 13,
    source: 'WAREHOUSE',
    deliveredAt: '2026-07-08T08:00:00Z',
    deliveredById: 2,
    deliveredByName: 'คุณนำเข้า ต่างประเทศ',
    note: 'ส่งมอบบางส่วน',
    createdAt: '2026-07-08T08:00:00Z',
    items: [{ deliveryItemId: 1, itemId: 16, qty: 200 }],
  },
];
const creditDemoTicket = db.tickets.find((t) => t.id === 6);
if (creditDemoTicket) {
  creditDemoTicket.depositPolicy = 'CREDIT_CUSTOMER';
  creditDemoTicket.depositPolicyReason = creditDemoTicket.depositPolicyReason ?? 'ลูกค้าเครดิตตามข้อตกลง';
  creditDemoTicket.billingDate = creditDemoTicket.billingDate ?? '2026-06-20';
  creditDemoTicket.dueDate = creditDemoTicket.dueDate ?? '2026-07-01';
  creditDemoTicket.creditTermDays = creditDemoTicket.creditTermDays ?? 30;
  creditDemoTicket.nextFollowUpAt = creditDemoTicket.nextFollowUpAt ?? '2026-07-18';
  creditDemoTicket.fulfillmentStatus = creditDemoTicket.fulfillmentStatus ?? 'FROM_STOCK';
  creditDemoTicket.salesStage = 'PROCUREMENT';
  if (creditDemoTicket.items?.[0]) {
    creditDemoTicket.items[0].qtyFromStock = creditDemoTicket.items[0].qty;
    creditDemoTicket.items[0].stockNote = 'พร้อมส่งจากสต็อก';
  }
}
db.commissions = db.commissions || [];
db.leaveTypes = db.leaveTypes || [
  { code: 'PERSONAL', nameTh: 'ลากิจ', nameEn: 'Personal leave', annualQuotaDays: 3, requiresAttachment: false },
  { code: 'SICK', nameTh: 'ลาป่วย', nameEn: 'Sick leave', annualQuotaDays: 30, requiresAttachment: true },
  { code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation leave', annualQuotaDays: 6, requiresAttachment: false },
];
db.leaveRequests = db.leaveRequests || [];
if (db.leaveRequests.length === 0) {
  const now = new Date().toISOString();
  db.leaveRequests = [
    {
      id: 1,
      employeeId: db.employees[8].id,
      leaveTypeCode: 'VACATION',
      startDate: '2026-07-13',
      endDate: '2026-07-14',
      totalDays: 2,
      quotaYear: 2026,
      reason: 'Family trip',
      attachmentId: null,
      attachmentFileName: null,
      status: 'APPROVED',
      quotaRemainingBefore: 6,
      quotaRemainingAfter: 4,
      systemNote: null,
      requestedById: db.employees[8].id,
      requestedByName: db.employees[8].nameTh,
      requestedAt: now,
      reviewedById: null,
      reviewedByName: null,
      reviewedAt: null,
      reviewerNote: null,
      cancelledAt: null,
      createdAt: now,
      updatedAt: now,
    },
    {
      id: 2,
      employeeId: db.employees[12].id,
      leaveTypeCode: 'SICK',
      startDate: '2026-06-18',
      endDate: '2026-06-18',
      totalDays: 1,
      quotaYear: 2026,
      reason: 'Medical appointment',
      attachmentId: 2,
      attachmentFileName: 'medical-certificate.pdf',
      status: 'APPROVED',
      quotaRemainingBefore: 30,
      quotaRemainingAfter: 29,
      systemNote: null,
      requestedById: db.employees[12].id,
      requestedByName: db.employees[12].nameTh,
      requestedAt: now,
      reviewedById: db.employees[20].id,
      reviewedByName: db.employees[20].nameTh,
      reviewedAt: now,
      reviewerNote: null,
      cancelledAt: null,
      createdAt: now,
      updatedAt: now,
    },
  ];
}
db.overtimeRequests = db.overtimeRequests || [];
if (db.overtimeRequests.length === 0) {
  const now = new Date().toISOString();
  db.overtimeRequests = [
    {
      id: 1,
      employeeId: db.employees[8].id,
      workDate: '2026-07-04',
      plannedStartAt: '2026-07-04T18:00:00+07:00',
      plannedEndAt: '2026-07-04T20:00:00+07:00',
      plannedMinutes: 120,
      dayType: 'WORKDAY',
      reason: 'ปิดยอดสิ้นเดือน',
      status: 'SUBMITTED',
      actualMinutes: null,
      payableMinutes: null,
      calculationNote: null,
      requestedById: db.employees[8].id,
      requestedByName: db.employees[8].nameTh,
      requestedAt: now,
      reviewedById: null,
      reviewedByName: null,
      reviewedAt: null,
      reviewerNote: null,
      cancelledAt: null,
      createdAt: now,
      updatedAt: now,
    },
    {
      id: 2,
      employeeId: db.employees[12].id,
      workDate: '2026-06-28',
      plannedStartAt: '2026-06-28T09:00:00+07:00',
      plannedEndAt: '2026-06-28T13:00:00+07:00',
      plannedMinutes: 240,
      dayType: 'HOLIDAY',
      reason: 'งานติดตั้งนอกสถานที่',
      status: 'APPROVED',
      actualMinutes: 240,
      payableMinutes: 720,
      calculationNote: null,
      requestedById: db.employees[12].id,
      requestedByName: db.employees[12].nameTh,
      requestedAt: now,
      reviewedById: db.employees[20].id,
      reviewedByName: db.employees[20].nameTh,
      reviewedAt: now,
      reviewerNote: null,
      cancelledAt: null,
      createdAt: now,
      updatedAt: now,
    },
  ];
}
let sessionUser = null;

// ── Mock in-memory document store ─────────────────────────────────────────────
const mockCustomers = [
  { id: 1, name: 'บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด',  taxId: '0105565012345', address: '123 ถนนสุขุมวิท แขวงคลองเตย กรุงเทพฯ 10110', branch: 'สำนักงานใหญ่', phone: '02-123-4567' },
  { id: 2, name: 'บริษัท ไทยแลนด์ ดีเวลลอปเมนท์ จำกัด', taxId: '0105556789012', address: '456 ถนนรัชดาภิเษก แขวงลาดยาว กรุงเทพฯ 10900',  branch: 'สำนักงานใหญ่', phone: '02-234-5678' },
  { id: 3, name: 'บริษัท พรีเมียม ดีไซน์ กรุ๊ป จำกัด',   taxId: '0105578901234', address: '789 ถนนพระราม 4 แขวงพระโขนง กรุงเทพฯ 10260',    branch: 'สำนักงานใหญ่', phone: '02-345-6789' },
  { id: 4, name: 'บริษัท เรืองแสง พร็อพเพอร์ตี้ จำกัด',  taxId: '0105591234567', address: '321 ถนนนวมินทร์ แขวงคลองกุ่ม กรุงเทพฯ 10240',  branch: 'สำนักงานใหญ่', phone: '02-456-7890' },
];
let mockCustomerSeq = mockCustomers.length + 1;

const mockContacts = [
  { id: 1, customerId: 1, firstName: 'วิภา',   lastName: 'สมิทธ์',   position: 'ผู้จัดการโครงการ', email: 'wipa@kaona.co.th',     phone: '081-111-2222' },
  { id: 2, customerId: 1, firstName: 'ธนพล',   lastName: 'อภิชัย',   position: 'วิศวกรโยธา',       email: 'thanaphon@kaona.co.th', phone: '082-333-4444' },
  { id: 3, customerId: 2, firstName: 'ปรีชา',  lastName: 'วงศ์สกุล', position: 'จัดซื้อ',          email: 'preecha@tld.co.th',     phone: '083-555-6666' },
  { id: 4, customerId: 3, firstName: 'สุภาพร', lastName: 'ทองดี',    position: 'ผู้อำนวยการ',       email: 'supaporn@pdg.co.th',    phone: '084-777-8888' },
  { id: 5, customerId: 4, firstName: 'กมล',    lastName: 'เรืองศรี', position: 'ผู้จัดการ',         email: 'kamol@rp.co.th',        phone: '085-999-0000' },
];
let mockContactSeq = mockContacts.length + 1;

const mockProjects = [
  { id: 1, customerId: 1, name: 'โครงการ Central Ladprao ชั้น B1' },
  { id: 2, customerId: 1, name: 'โครงการ The Mall Bangkapi' },
  { id: 3, customerId: 2, name: 'โครงการ Asoke Tower ชั้น 12-15' },
  { id: 4, customerId: 3, name: 'โครงการ PDG HQ Renovation' },
  { id: 5, customerId: 4, name: 'โครงการ Rueangchat Condo Phase 2' },
];
let mockProjectSeq = mockProjects.length + 1;

// R4: FX rates + price calc configs
const mockFxRates = [
  { id: 1, currency: 'CNY', rateToThb: 4.85,  effectiveDate: '2026-07-01', updatedAt: new Date().toISOString(), source: 'BOT',    fetchedAt: new Date().toISOString() },
  { id: 2, currency: 'EUR', rateToThb: 38.50, effectiveDate: '2026-07-01', updatedAt: new Date().toISOString(), source: 'BOT',    fetchedAt: new Date().toISOString() },
  { id: 3, currency: 'GBP', rateToThb: 44.80, effectiveDate: '2026-07-10', updatedAt: new Date().toISOString(), source: 'BOT',    fetchedAt: new Date().toISOString() },
  { id: 4, currency: 'JPY', rateToThb: 0.24,  effectiveDate: '2026-07-01', updatedAt: new Date().toISOString(), source: 'BOT',    fetchedAt: new Date().toISOString() },
  { id: 5, currency: 'THB', rateToThb: 1.00,  effectiveDate: '2026-07-01', updatedAt: new Date().toISOString(), source: 'MANUAL', fetchedAt: null },
  { id: 6, currency: 'USD', rateToThb: 33.6264, effectiveDate: '2026-07-10', updatedAt: new Date().toISOString(), source: 'BOT',    fetchedAt: new Date().toISOString() },
];

const mockPriceCalcConfigs = [
  {
    configId: 1, version: 1, country: 'Italy',
    freightPerSqm: 120, insurancePerSqm: 15,
    inlandFactoryToPortPerSqm: 30, inlandPortToWarehousePerSqm: 50,
    importDutyPct: 0.05, marginPct: 0.25,
    isCurrent: true, effectiveFrom: '2026-01-01', updatedAt: new Date().toISOString(),
  },
  {
    configId: 2, version: 1, country: 'Thailand',
    freightPerSqm: 0, insurancePerSqm: 0,
    inlandFactoryToPortPerSqm: 0, inlandPortToWarehousePerSqm: 50,
    importDutyPct: 0, marginPct: 0.20,
    isCurrent: true, effectiveFrom: '2026-01-01', updatedAt: new Date().toISOString(),
  },
];
let mockPriceConfigSeq = mockPriceCalcConfigs.length + 1;

// R5: Attachments
const mockAttachments = [];
let mockAttachSeq = 1;

const mockFactoryConfigs = [
  { id: 1, factoryName: 'SCG Ceramics',      email: 'sales@scg.co.th',         currency: 'THB', unit: 'piece', country: 'Thailand' },
  { id: 2, factoryName: 'Cotto Industry',    email: 'orders@cotto.co.th',       currency: 'THB', unit: 'piece', country: 'Thailand' },
  { id: 3, factoryName: 'Duragres Thailand', email: 'sales@duragres.co.th',     currency: 'THB', unit: 'piece', country: 'Thailand' },
  { id: 4, factoryName: 'Panaria SpA',       email: 'export@panaria.it',        currency: 'EUR', unit: 'sqm',   country: 'Italy' },
];

const mockCatalog = [
  { id: 1,  brand: 'SCG',      collection: 'Elegance Series',   color: 'ขาวนวล',      surface: 'ด้าน',         size: '60x60 ซม.',  factory: 'SCG Ceramics',      sqmPerPiece: 0.36 },
  { id: 2,  brand: 'SCG',      collection: 'Elegance Series',   color: 'เทาอ่อน',     surface: 'ด้าน',         size: '60x60 ซม.',  factory: 'SCG Ceramics',      sqmPerPiece: 0.36 },
  { id: 3,  brand: 'SCG',      collection: 'Natura Collection', color: 'เบจธรรมชาติ', surface: 'หยาบ',         size: '30x60 ซม.',  factory: 'SCG Ceramics',      sqmPerPiece: 0.18 },
  { id: 4,  brand: 'SCG',      collection: 'Natura Collection', color: 'น้ำตาลไม้',   surface: 'หยาบ',         size: '20x100 ซม.', factory: 'SCG Ceramics',      sqmPerPiece: 0.20 },
  { id: 5,  brand: 'SCG',      collection: 'Crystal White',     color: 'ขาวมุก',      surface: 'มัน',          size: '60x120 ซม.', factory: 'SCG Ceramics',      sqmPerPiece: 0.72 },
  { id: 6,  brand: 'Cotto',    collection: 'Metro Square',      color: 'ขาว',         surface: 'ด้าน',         size: '30x30 ซม.',  factory: 'Cotto Industry',    sqmPerPiece: 0.09 },
  { id: 7,  brand: 'Cotto',    collection: 'Metro Square',      color: 'ครีม',        surface: 'ด้าน',         size: '30x30 ซม.',  factory: 'Cotto Industry',    sqmPerPiece: 0.09 },
  { id: 8,  brand: 'Cotto',    collection: 'Stone Series',      color: 'เทาเข้ม',     surface: 'หยาบ',         size: '60x60 ซม.',  factory: 'Cotto Industry',    sqmPerPiece: 0.36 },
  { id: 9,  brand: 'Cotto',    collection: 'Timber Line',       color: 'น้ำตาลอ่อน', surface: 'ลายไม้',       size: '20x120 ซม.', factory: 'Cotto Industry',    sqmPerPiece: 0.24 },
  { id: 10, brand: 'Duragres', collection: 'Granite Plus',      color: 'เทากลาง',     surface: 'หยาบกึ่งมัน', size: '60x60 ซม.',  factory: 'Duragres Thailand', sqmPerPiece: 0.36 },
  { id: 11, brand: 'Duragres', collection: 'Granite Plus',      color: 'ดำ',          surface: 'หยาบกึ่งมัน', size: '60x60 ซม.',  factory: 'Duragres Thailand', sqmPerPiece: 0.36 },
  { id: 12, brand: 'Duragres', collection: 'Porcelain Pro',     color: 'ขาวเนียน',    surface: 'มัน',          size: '80x80 ซม.',  factory: 'Duragres Thailand', sqmPerPiece: 0.64 },
  { id: 13, brand: 'Panaria',  collection: 'Trilogy',           color: 'Ivory',       surface: 'Lappato',      size: '60x120 cm',  factory: 'Panaria SpA',       sqmPerPiece: 0.72 },
  { id: 14, brand: 'Panaria',  collection: 'Frame',             color: 'Ash',         surface: 'Naturale',     size: '80x80 cm',   factory: 'Panaria SpA',       sqmPerPiece: 0.64 },
];

let mockPriceImportFactorySeq = 10;
const mockPriceImportFactories = [
  { factoryId: 1, name: 'Panaria SpA',    country: 'Italy',   numberFormat: 'eu' },
  { factoryId: 2, name: 'REFIN',          country: 'Italy',   numberFormat: 'eu' },
  { factoryId: 3, name: 'Equipe',         country: 'Spain',   numberFormat: 'eu' },
  { factoryId: 4, name: 'Vives',          country: 'Spain',   numberFormat: 'eu' },
  { factoryId: 5, name: 'Bode',           country: 'Germany', numberFormat: 'us' },
  { factoryId: 6, name: 'CDE',            country: 'Italy',   numberFormat: 'eu' },
  { factoryId: 7, name: 'Padana Marmi',   country: 'Italy',   numberFormat: 'eu' },
  { factoryId: 8, name: 'LEA',            country: 'Italy',   numberFormat: 'eu' },
  { factoryId: 9, name: 'CITY Ceramica',  country: 'Italy',   numberFormat: 'eu' },
];

// Two separate ID spaces, deliberately. priceImport.upload() used to mint version
// IDs from mockProductPriceSeq — the *product price* counter — which collides once
// catalog.addProduct/priceImport.uploadAndCommit also consume it for real price rows.
let mockProductPriceSeq = 100;
let mockPriceVersionSeq = 100;
const mockProductPrices = [
  { priceId: 1, factoryId: 1, factoryName: 'Panaria SpA',  productCode: 'PAN-T600-IVO', grade: null,  collection: 'Trilogy',      productName: 'Ivory Lappato',    color: 'Ivory',   surface: 'Lappato',   sizeRaw: '60x120', price: 43.00, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: 0.72 },
  { priceId: 2, factoryId: 1, factoryName: 'Panaria SpA',  productCode: 'PAN-T600-GRY', grade: null,  collection: 'Trilogy',      productName: 'Grigio Naturale',  color: 'Grigio',  surface: 'Naturale',  sizeRaw: '60x120', price: 43.00, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: 0.72 },
  { priceId: 3, factoryId: 1, factoryName: 'Panaria SpA',  productCode: 'PAN-F800-ASH', grade: null,  collection: 'Frame',        productName: 'Ash',              color: 'Ash',     surface: 'Naturale',  sizeRaw: '80x80',  price: 38.50, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: 0.64 },
  { priceId: 4, factoryId: 2, factoryName: 'REFIN',        productCode: null,           grade: null,  collection: 'Terraço',      productName: 'L-Trim',           color: null,      surface: null,        sizeRaw: '10x60',  price: 38.00, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: null },
  { priceId: 5, factoryId: 2, factoryName: 'REFIN',        productCode: null,           grade: null,  collection: 'Terraço',      productName: 'Corner',           color: null,      surface: null,        sizeRaw: '10x10',  price: 55.00, currency: 'EUR', priceUnit: 'per_piece', sqmPerPiece: null },
  { priceId: 6, factoryId: 2, factoryName: 'REFIN',        productCode: 'RF-BAL-6060',  grade: null,  collection: 'Balneo',       productName: 'Floor Tile',       color: 'White',   surface: 'Lappato',   sizeRaw: '60x60',  price: 42.00, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: 0.36 },
  { priceId: 7, factoryId: 3, factoryName: 'Equipe',       productCode: 'EQ-001',       grade: null,  collection: 'Stromboli',    productName: '1.2X20 Jolly Ash', color: 'Ash',     surface: 'Mate',      sizeRaw: '1.2x20', price: 25.50, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: null },
  { priceId: 8, factoryId: 4, factoryName: 'Vives',        productCode: 'VV-001',       grade: null,  collection: 'Masia',        productName: 'Tile A',           color: 'Beige',   surface: 'Mate',      sizeRaw: "15'8X31'6", price: 5.50, currency: 'EUR', priceUnit: 'per_piece', sqmPerPiece: 0.05 },
  { priceId: 9, factoryId: 5, factoryName: 'Bode',         productCode: 'BVLE10426KGA', grade: null,  collection: 'Limestone',    productName: null,               color: null,      surface: 'Honed',     sizeRaw: '600x600',price: 23.50, currency: 'USD', priceUnit: 'per_sqm',   sqmPerPiece: 0.36 },
  { priceId: 10,factoryId: 7, factoryName: 'Padana Marmi', productCode: '0400012',      grade: 'A01', collection: 'Stone',        productName: null,               color: 'Beige',   surface: 'Lucidato',  sizeRaw: '60x120', price: 43.00, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: 0.72 },
  { priceId: 11,factoryId: 7, factoryName: 'Padana Marmi', productCode: '0400012',      grade: 'A02', collection: 'Stone',        productName: null,               color: 'Beige',   surface: 'Lucidato',  sizeRaw: '60x120', price: 21.50, currency: 'EUR', priceUnit: 'per_sqm',   sqmPerPiece: 0.72 },
];

const mockPriceImportVersions = [
  { versionId: 1, factoryId: 1, label: 'Price List 2026 Q1', status: 'ACTIVE',   createdAt: '2026-01-10T09:00:00Z', uploadedByName: 'Admin' },
  { versionId: 2, factoryId: 2, label: 'REFIN 2026',         status: 'ACTIVE',   createdAt: '2026-02-01T11:00:00Z', uploadedByName: 'Admin' },
  { versionId: 3, factoryId: 5, label: 'Bode USD 2026',      status: 'ARCHIVED', createdAt: '2025-12-01T08:00:00Z', uploadedByName: 'Admin' },
];

const mockNoteTemplates = [
  { id: 1, text: 'ราคารวมค่าขนส่งถึงชั้น 1 ของหน่วยงานในเขต กทม. แต่ไม่รวมค่าตัด/ติดตั้ง', defaultSelected: true, sortOrder: 1 },
  { id: 2, text: 'จ่ายเช็คในนาม บจก. จี แอล แอนด์ อาร์ฯ / โอนเข้า กสิกรไทย 003-1-15914-8 (กระแสรายวัน สาขาสุขุมวิท 33)', defaultSelected: true, sortOrder: 2 },
  { id: 3, text: 'กรณีโอนเงินส่ง Pay-in มาที่ e-mail : info@glr.co.th', defaultSelected: true, sortOrder: 3 },
];

const mockDepositNotices = []; // used by both depositNotices and documents API groups
let mockDocSeq = 1;
let mockDocNumberSeq = 1;

// PricingRequest (commit 6): one deal may have several pricing requests (one
// per recipient / re-quote round). Stored as full detail records (summary
// fields + items + its own event log) so buildPricingRequestDetail never has
// to join across a second array.
const mockPricingRequests = [];
let mockPricingRequestSeq = 1;
let mockPricingRequestItemSeq = 1;
let mockPricingRequestEventSeq = 1;
const PRICING_REQUEST_VIEWER_ROLES = ['sales', 'import', 'ceo', 'account', 'sales_manager'];
const PRICING_REQUEST_RECIPIENT_VALUES = PRICING_REQUEST_RECIPIENT_OPTIONS.map((o) => o.code);
const PRICING_REQUEST_QUANTITY_TYPE_VALUES = PRICING_REQUEST_QUANTITY_TYPE_OPTIONS.map((o) => o.code);

function nextPricingRequestCode() {
  return `PCR-2026-${String(mockPricingRequestSeq).padStart(4, '0')}`;
}

function findPricingRequestRaw(id) {
  const pr = mockPricingRequests.find((p) => p.id === Number(id));
  if (!pr) fail('Pricing request not found', 404);
  return pr;
}

function pushPricingRequestEvent(pr, actor, eventKind, fromStatus, toStatus, message = null, metadata = null) {
  pr.events.push({
    id: mockPricingRequestEventSeq++,
    pricingRequestId: pr.id,
    ticketId: pr.ticketId,
    actorId: actor.id,
    actorName: actor.name,
    eventKind,
    fromStatus,
    toStatus,
    message,
    metadata,
    createdAt: new Date().toISOString(),
  });
}

// Mirrors PricingRequestService.detail()'s join: the ticket a request belongs
// to is looked up fresh every time (never cached on the request record), same
// as PricingRequestSummaryDto's ticketCode/projectName/customerName/
// ticketCreatedById fields.
function buildPricingRequestSummary(pr) {
  const ticket = db.tickets.find((t) => t.id === pr.ticketId);
  return {
    id: pr.id,
    requestCode: pr.requestCode,
    ticketId: pr.ticketId,
    ticketCode: ticket?.code ?? null,
    projectName: ticket?.projectId ? (mockProjects.find((p) => p.id === ticket.projectId)?.name ?? null) : null,
    customerName: ticket?.customerName ?? null,
    ticketCreatedById: ticket?.createdById ?? null,
    recipientType: pr.recipientType,
    recipientContactId: pr.recipientContactId ?? null,
    recipientLabel: pr.recipientLabel ?? null,
    status: pr.status,
    requestedById: pr.requestedById,
    requestedByName: pr.requestedByName,
    assignedImportId: pr.assignedImportId ?? null,
    assignedImportName: pr.assignedImportName ?? null,
    requiredDate: pr.requiredDate ?? null,
    customerTargetPrice: pr.customerTargetPrice ?? null,
    targetCurrency: pr.targetCurrency ?? null,
    note: pr.note ?? null,
    itemCount: pr.items.length,
    revisionNo: pr.revisionNo ?? 0,
    parentPricingRequestId: pr.parentPricingRequestId ?? null,
    submittedAt: pr.submittedAt ?? null,
    pickedUpAt: pr.pickedUpAt ?? null,
    cancelledAt: pr.cancelledAt ?? null,
    createdAt: pr.createdAt,
    updatedAt: pr.updatedAt,
  };
}

function buildPricingRequestDetail(pr) {
  return { summary: buildPricingRequestSummary(pr), items: pr.items, events: pr.events };
}

function requirePricingRequestViewable(id, user) {
  if (!PRICING_REQUEST_VIEWER_ROLES.includes(user.role)) fail('Forbidden', 403);
  const pr = findPricingRequestRaw(id);
  const ticket = db.tickets.find((t) => t.id === pr.ticketId);
  if (user.role === 'sales' && ticket?.createdById !== user.id) fail('Forbidden', 403);
  return pr;
}

function requirePricingRequestDealActive(ticket) {
  if ((ticket?.lifecycle ?? 'ACTIVE') !== 'ACTIVE') {
    fail(`ดีลไม่ได้อยู่ในสถานะ ACTIVE (${ticket?.lifecycle}) จึงสร้าง/แก้ไขคำขอราคาไม่ได้`, 409);
  }
}

// Mirrors PricingRequestRequests.PricingRequestItemRequest's Bean Validation
// annotations, which run BEFORE PricingRequestService even sees the request
// (@NotNull @DecimalMin("0.0001") requestedQty, @NotBlank requestedUnit,
// @NotBlank quantityType — quantityType's enum-membership is checked
// separately by the callers of this helper). A mock that skips this is the
// dangerous direction (issue #199): it would accept a blank unit / zero qty
// that the real backend 400s on.
function requirePricingRequestItemFieldsValid(items) {
  items.forEach((item, index) => {
    if (item.requestedQty == null || !(Number(item.requestedQty) >= 0.0001)) {
      fail('requestedQty must be at least 0.0001', 400);
    }
    if (!item.requestedUnit?.trim()) {
      fail('requestedUnit must not be blank', 400);
    }
    if (!item.quantityType?.trim()) {
      fail('quantityType must not be blank', 400);
    }
    // Mirrors PricingRequestService.validateItems: an item must actually name
    // a product somehow — a link to an existing deal line, a catalog
    // product, a model name, or a free-text special requirement. Brand alone
    // is deliberately NOT sufficient (a brand with no model does not
    // identify a product), so Import never receives a request for a line
    // nobody can actually source.
    const identified = item.sourceTicketItemId != null || item.productId != null
      || Boolean(item.model?.trim()) || Boolean(item.specialRequirement?.trim());
    if (!identified) {
      fail(`รายการที่ ${index + 1}: ต้องระบุสินค้าที่ต้องการเสนอราคา (เลือกจากรายการในดีล หรือระบุรุ่น/รายละเอียด)`, 400);
    }
  });
}

function buildMockDoc(doc) {
  const items = doc.items ?? [];
  const depositPct = doc.depositPercent ?? 0.5;
  const subtotal = items.reduce((s, it) => s + (Number(it.netUnitPrice) || 0) * (Number(it.qty) || 0), 0);
  const deposit  = Math.round(subtotal * depositPct * 100) / 100;
  const vat      = Math.round(deposit * 0.07 * 100) / 100;
  const total    = Math.round((deposit + vat) * 100) / 100;
  return { ...doc, subtotal, depositAmount: deposit, vatAmount: vat, totalPayable: total };
}

function mockPreviewHtml(doc) {
  const money = (v) => v == null ? '—' : Number(v).toLocaleString('th-TH', { minimumFractionDigits: 2 });
  const depositPct = Math.round((doc.depositPercent ?? 0.5) * 100);
  let rows = (doc.items ?? []).map((it, i) =>
    `<tr><td>${i+1}</td><td>${it.description??''}</td><td style="text-align:right">${it.qty}</td><td>${it.unit??'แผ่น'}</td><td style="text-align:right">${money(it.unitPrice)}</td><td style="text-align:right">${money(it.netUnitPrice??it.unitPrice)}</td><td style="text-align:right">${money((it.netUnitPrice??it.unitPrice)*it.qty)}</td></tr>`
  ).join('');
  return `<!DOCTYPE html><html><head><meta charset="UTF-8"><style>body{font-family:sans-serif;padding:20px;font-size:13px}table{width:100%;border-collapse:collapse;margin:12px 0}th{background:#1e3a5f;color:#fff;padding:6px 8px;text-align:left}td{padding:5px 8px;border-bottom:1px solid #eee}.sum{text-align:right;width:300px;float:right;margin-top:12px}.sum td{padding:4px 8px}</style></head><body>
<div style="display:flex;justify-content:space-between;border-bottom:2px solid #1e3a5f;padding-bottom:12px;margin-bottom:16px">
  <div><strong>บริษัท จี แอล แอนด์ อาร์ จำกัด</strong><br><small>เลขภาษี 0105542026329</small></div>
  <div style="text-align:right"><strong style="font-size:16px">ใบแจ้งยอด / เงินรับมัดจำ</strong><br><code>${doc.docNumber??'DRAFT'}</code></div>
</div>
<div>เรียน: <strong>${doc.customerName??''}</strong></div>
<div style="color:#666;font-size:12px">${doc.customerAddress??''}</div>
${doc.projectName ? `<div>โครงการ: ${doc.projectName}</div>` : ''}
<table><thead><tr><th>ลำดับ</th><th>รายละเอียด</th><th>จำนวน</th><th>หน่วย</th><th>ราคา/หน่วย</th><th>ราคาสุทธิ</th><th>เป็นเงิน</th></tr></thead><tbody>${rows}</tbody></table>
<table class="sum"><tr><td>รวมเป็นเงิน</td><td style="text-align:right">${money(doc.subtotal)} บาท</td></tr>
<tr><td>มัดจำ ${depositPct}%</td><td style="text-align:right">${money(doc.depositAmount)} บาท</td></tr>
<tr><td>VAT 7% (จากมัดจำ)</td><td style="text-align:right">${money(doc.vatAmount)} บาท</td></tr>
<tr style="font-weight:bold;border-top:2px solid #1e3a5f"><td>รวมต้องชำระ</td><td style="text-align:right">${money(doc.totalPayable)} บาท</td></tr></table>
<div style="clear:both"></div>
${doc.notes?.length ? `<div style="margin-top:20px;font-size:12px"><strong>หมายเหตุ:</strong><ol>${doc.notes.map(n=>`<li>${n}</li>`).join('')}</ol></div>` : ''}
<div style="margin-top:30px;font-size:12px;color:#666">ผู้จัดทำ: จินตนา หาญมนตรี</div>
</body></html>`;
}

function delay(value) {
  return new Promise((resolve) => {
    window.setTimeout(() => resolve(structuredClone(value)), 140);
  });
}

function fail(message, status = 400) {
  const error = new Error(message);
  error.status = status;
  throw error;
}

function publicUser(user) {
  if (!user) return null;
  const { password, ...safe } = user;
  const employee = employeeForUser(user);
  return {
    ...safe,
    divisionId: safe.divisionId ?? employee?.divisionId ?? null,
    manager: safe.manager ?? dashboardManager(user),
  };
}

function requireSession() {
  if (!sessionUser) fail('Not authenticated', 401);
  return sessionUser;
}

function hasRole(...roles) {
  const user = requireSession();
  if (!roles.includes(user.role)) fail('Forbidden', 403);
  return user;
}

// --- ticket helpers ---

// Mirrors TicketService.requireViewAccess: viewer role required, sales reps
// only see their own tickets. Used by every read/render path. sales_manager is
// read+comment-only oversight — never add it to a write-action role list.
function requireTicketViewer(id) {
  const user = hasRole('sales', 'import', 'ceo', 'account', 'sales_manager');
  const ticket = findTicketRaw(Number(id));
  if (user.role === 'sales' && ticket.createdById !== user.id) fail('Forbidden', 403);
  return { user, ticket };
}

function findTicketRaw(id) {
  const ticket = db.tickets.find((t) => t.id === id);
  if (!ticket) fail('Ticket not found', 404);
  return ticket;
}

function verifyStatus(ticket, expected) {
  if (ticket.status !== expected) fail(`Expected status '${expected}' but ticket is '${ticket.status}'`, 409);
}

function requireActive(ticket) {
  if ((ticket.lifecycle ?? 'ACTIVE') !== 'ACTIVE') {
    fail(`ดีลไม่ได้อยู่ในสถานะ ACTIVE (${ticket.lifecycle}) จึงแก้ไขขั้นตอนนี้ไม่ได้`, 409);
  }
}

function depositBypassesNotice(ticket) {
  return ['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER'].includes(ticket.depositPolicy);
}

function moneyValue(value) {
  return Math.round((Number(value) || 0) * 100) / 100;
}

function payableAmount(ticket) {
  const quotations = ticket.quotations ?? (ticket.quotation ? [ticket.quotation] : []);
  const recipientRank = (recipient) => recipient === 'BUYER' ? 0 : recipient === 'OWNER' ? 1 : 2;
  const pickQuotation = (statuses) => [...quotations]
    .filter((q) => statuses.includes(q.docStatus ?? 'ISSUED'))
    .sort((a, b) => {
      const rank = recipientRank(a.recipientType) - recipientRank(b.recipientType);
      if (rank !== 0) return rank;
      return new Date(b.acceptedAt ?? b.issuedAt ?? 0) - new Date(a.acceptedAt ?? a.issuedAt ?? 0)
        || Number(b.id ?? 0) - Number(a.id ?? 0);
    })[0];
  const accepted = pickQuotation(['ACCEPTED']);
  if (accepted?.totalAmount != null) return moneyValue(accepted.totalAmount);
  const issued = pickQuotation(['ISSUED', 'SENT']);
  if (issued?.totalAmount != null) return moneyValue(issued.totalAmount);
  const notice = [...mockDepositNotices]
    .filter((d) => d.ticketId === ticket.id && d.status === 'ISSUED')
    .sort((a, b) => Number(b.version ?? 0) - Number(a.version ?? 0) || Number(b.id ?? 0) - Number(a.id ?? 0))[0];
  if (notice?.totalPayable != null) return moneyValue(notice.totalPayable);
  return moneyValue((ticket.items ?? []).reduce((sum, item) =>
    sum + (Number(item.approvedPrice) || 0) * (Number(item.qty) || 0), 0));
}

function receiptsForTicket(ticketId) {
  return (db.paymentReceipts ?? [])
    .filter((r) => r.ticketId === Number(ticketId))
    .sort((a, b) => new Date(a.receivedAt) - new Date(b.receivedAt) || a.receiptId - b.receiptId);
}

function sumPaid(ticketId) {
  return moneyValue(receiptsForTicket(ticketId).reduce((sum, receipt) =>
    sum + (receipt.kind === 'ADJUSTMENT' ? -Number(receipt.amount) : Number(receipt.amount)), 0));
}

function derivePaymentFields(ticket) {
  const payable = payableAmount(ticket);
  const paid = sumPaid(ticket.id);
  const outstanding = moneyValue(Math.max(0, payable - paid));
  const hasBalance = receiptsForTicket(ticket.id).some((receipt) => receipt.kind === 'BALANCE');
  let paymentStage = 'NOT_REQUIRED';
  if (payable > 0 && paid >= payable) paymentStage = 'FULLY_PAID';
  else if (payable > 0 && paid > 0) paymentStage = hasBalance ? 'PARTIALLY_PAID' : 'DEPOSIT_RECEIVED';
  else if (payable > 0 && !depositBypassesNotice(ticket)
      && ['CUSTOMER_CONFIRMED', 'DEPOSIT_NOTICE_ISSUED'].includes(ticket.paymentStatus)) paymentStage = 'DEPOSIT_PENDING';
  else if (payable > 0 && outstanding > 0) paymentStage = 'BALANCE_PENDING';
  const overdue = Boolean(ticket.dueDate && new Date(`${ticket.dueDate}T00:00:00`) < new Date() && outstanding > 0);
  return {
    billingDate: ticket.billingDate ?? null,
    dueDate: ticket.dueDate ?? null,
    creditTermDays: ticket.creditTermDays ?? null,
    lastFollowUpAt: ticket.lastFollowUpAt ?? null,
    nextFollowUpAt: ticket.nextFollowUpAt ?? null,
    paymentStage,
    amountPayable: payable,
    amountPaid: paid,
    amountOutstanding: outstanding,
    overdue,
  };
}

function latestIssuedDepositNotice(ticketId) {
  return [...mockDepositNotices]
    .filter((d) => d.ticketId === Number(ticketId) && d.status === 'ISSUED')
    .sort((a, b) => Number(b.version ?? 0) - Number(a.version ?? 0) || Number(b.id ?? 0) - Number(a.id ?? 0))[0] ?? null;
}

function reconcilePaymentStatus(ticket, user) {
  const payable = payableAmount(ticket);
  const paid = sumPaid(ticket.id);
  if (payable > 0 && paid >= payable) {
    if (ticket.paymentStatus !== 'FULLY_PAID') {
      ticket.paymentStatus = 'FULLY_PAID';
      pushEvent(ticket, user, 'FULLY_PAID', ticket.status, ticket.status, null);
      maybeAdvanceClosedPaid(ticket, user);
    }
    return;
  }
  if (paid <= 0 || ticket.paymentStatus === 'FULLY_PAID') return;
  const eligible = ticket.paymentStatus == null
    || ticket.paymentStatus === 'CUSTOMER_CONFIRMED'
    || ticket.paymentStatus === 'DEPOSIT_NOTICE_ISSUED'
    || depositBypassesNotice(ticket);
  if (eligible && ticket.paymentStatus !== 'DEPOSIT_PAID') {
    ticket.paymentStatus = 'DEPOSIT_PAID';
    pushEvent(ticket, user, 'DEPOSIT_PAID', ticket.status, ticket.status, null);
    autoAdvanceStage(ticket, 'DEPOSIT_RECEIVED', user);
    if (ticket.fulfillmentStatus === 'GOODS_RECEIVED') {
      ticket.paymentStatus = 'AWAITING_FINAL_PAYMENT';
      pushEvent(ticket, user, 'AWAITING_FINAL_PAYMENT', ticket.status, ticket.status, null);
    }
  }
}

function deliveryRecordsForTicket(ticketId) {
  return (db.deliveryRecords ?? [])
    .filter((record) => record.ticketId === Number(ticketId))
    .sort((a, b) => new Date(a.deliveredAt) - new Date(b.deliveredAt) || a.deliveryId - b.deliveryId)
    .map((record) => structuredClone(record));
}

// Mirrors TicketService.deliveryGateComplete. Previously this also accepted
// GOODS_RECEIVED with no delivery records; that concession was justified as a
// legacy allowance but legacy tickets close via the DOCUMENT_ISSUED branch and
// never reach this predicate, so it only ever loosened modern dual-track deals —
// letting a fully-paid deal close with the goods still in GLR's own warehouse.
function deliveryComplete(status) {
  return status === 'FULLY_DELIVERED';
}

// Attachments live in their own store (mockAttachments), not on the ticket —
// mirrors sales.attachment being its own table.
function hasInvoiceAttachment(ticket) {
  return mockAttachments.some((a) => a.ticketId === ticket.id && a.attachType === 'INVOICE');
}

// Mirrors TicketService.requireClosePrerequisites. Legacy document_issued deals
// predate the delivery and invoice tracks, so those two are waived for them —
// requiring either would strand old data permanently.
function requireClosePrerequisites(ticket) {
  const legacyOk = ticket.status === 'document_issued'
    && (ticket.paymentStatus == null || ticket.paymentStatus === 'FULLY_PAID');
  const dualTrackOk = ticket.status === 'quotation_issued'
    && ticket.paymentStatus === 'FULLY_PAID'
    && deliveryComplete(ticket.fulfillmentStatus);
  if (!legacyOk && !dualTrackOk) {
    fail('ปิดงานไม่ได้: ต้องรับเงินครบและส่งมอบสินค้าครบก่อน', 409);
  }
  if (derivePaymentFields(ticket).amountOutstanding > 0) {
    fail('ปิดงานไม่ได้: ยังมียอดค้างชำระ', 409);
  }
  if (dualTrackOk && !hasInvoiceAttachment(ticket)) {
    fail('ปิดงานไม่ได้: ยังไม่ได้แนบใบกำกับภาษี (ฝ่ายบัญชีต้องอัปโหลดก่อน)', 409);
  }
}

// Mirrors TicketService.maybeAdvanceClosedPaid: CLOSED_PAID (S20) requires BOTH
// gates — payment fully paid AND goods actually delivered (FULLY_DELIVERED).
// Now the same rule the manual close uses; the two agree on "delivered".
function maybeAdvanceClosedPaid(ticket, user) {
  if (ticket.paymentStatus === 'FULLY_PAID' && ticket.fulfillmentStatus === 'FULLY_DELIVERED') {
    autoAdvanceStage(ticket, 'CLOSED_PAID', user);
  }
}

function hasRemainingDelivery(ticket) {
  return (ticket.items ?? []).some((item) => Number(item.qtyDelivered ?? 0) < Number(item.qty ?? 0));
}

// Goods reaching the warehouse is a permanent fact (the GOODS_RECEIVED event), not the
// mutable fulfillmentStatus — so a stock-first partial delivery can't lock out the
// warehouse remainder (mirrors TicketService.warehouseDeliveryAvailable — Case 8 fix).
function hasReceivedGoods(ticketId) {
  const ticket = db.tickets.find((t) => t.id === Number(ticketId));
  return (ticket?.events ?? []).some((ev) => ev.kind === 'GOODS_RECEIVED');
}

function warehouseAvailableFor(ticket) {
  return ticket.fulfillmentStatus === 'GOODS_RECEIVED' || hasReceivedGoods(ticket.id);
}

function deliveryAvailable(ticket) {
  const stockAvailable = (ticket.items ?? []).some((item) => Number(item.qtyFromStock ?? 0) > Number(item.qtyDelivered ?? 0));
  return ticket.fulfillmentStatus === 'FROM_STOCK' || stockAvailable || warehouseAvailableFor(ticket);
}

function reserveStockForTicket(ticket, user, payload = {}) {
  const lines = payload.lines ?? [];
  if (!lines.length) fail('ต้องระบุรายการสินค้า', 400);
  let total = 0;
  for (const line of lines) {
    const item = ticket.items.find((it) => it.id === Number(line.itemId));
    if (!item) fail('Item not found in this ticket', 404);
    const qty = moneyValue(line.qtyFromStock);
    if (qty < 0 || qty > Number(item.qty || 0)) fail('จำนวนสินค้าจากสต็อกต้องไม่เกินจำนวนที่สั่ง', 400);
    item.qtyFromStock = qty;
    item.stockNote = line.note ?? null;
    total += qty;
  }
  pushEvent(ticket, user, 'STOCK_RESERVED', ticket.status, ticket.status, `qty_from_stock=${total}`);
  const allCovered = (ticket.items ?? []).length > 0
    && ticket.items.every((item) => Number(item.qtyFromStock ?? 0) >= Number(item.qty ?? 0));
  if (allCovered && (ticket.fulfillmentStatus == null || ticket.fulfillmentStatus === 'FROM_STOCK')) {
    ticket.fulfillmentStatus = 'FROM_STOCK';
    // Full stock coverage has no import journey — goods are ready now, so advance
    // straight to DELIVERY_SCHEDULING (S18) rather than PROCUREMENT.
    autoAdvanceStage(ticket, 'DELIVERY_SCHEDULING', user);
  }
}

function recordDeliveryForTicket(ticket, user, payload = {}, completing = false) {
  const source = String(payload.source ?? '').trim().toUpperCase();
  if (!['WAREHOUSE', 'STOCK'].includes(source)) fail('source ต้องเป็น WAREHOUSE หรือ STOCK', 400);
  const lines = payload.lines ?? [];
  if (!lines.length) fail('ต้องระบุรายการส่งสินค้า', 400);
  const combined = new Map();
  for (const line of lines) {
    const item = ticket.items.find((it) => it.id === Number(line.itemId));
    if (!item) fail('Item not found in this ticket', 404);
    const qty = moneyValue(line.qty);
    if (qty <= 0) fail('จำนวนส่งมอบต้องมากกว่า 0', 400);
    combined.set(item.id, moneyValue((combined.get(item.id) ?? 0) + qty));
  }
  for (const [itemId, qty] of combined.entries()) {
    const item = ticket.items.find((it) => it.id === itemId);
    const newDelivered = moneyValue(Number(item.qtyDelivered ?? 0) + qty);
    if (newDelivered > Number(item.qty ?? 0)) fail('จำนวนส่งมอบเกินจำนวนที่สั่ง', 409);
    if (source === 'STOCK' && newDelivered > Number(item.qtyFromStock ?? 0)) {
      fail('ส่งจากสต็อกได้ไม่เกินจำนวนที่ประกาศว่าพร้อมจากสต็อก', 409);
    }
  }
  if (source === 'WAREHOUSE' && !warehouseAvailableFor(ticket)) {
    fail('ต้องรับสินค้าเข้าโกดังก่อนส่งจาก WAREHOUSE', 409);
  }
  const now = new Date().toISOString();
  const nextId = Math.max(0, ...(db.deliveryRecords ?? []).map((record) => record.deliveryId)) + 1;
  let itemSeq = Math.max(0, ...(db.deliveryRecords ?? []).flatMap((record) => record.items ?? []).map((item) => item.deliveryItemId)) + 1;
  const recordItems = [];
  for (const [itemId, qty] of combined.entries()) {
    const item = ticket.items.find((it) => it.id === itemId);
    item.qtyDelivered = moneyValue(Number(item.qtyDelivered ?? 0) + qty);
    recordItems.push({ deliveryItemId: itemSeq++, itemId, qty });
  }
  db.deliveryRecords.push({
    deliveryId: nextId,
    ticketId: ticket.id,
    source,
    deliveredAt: now,
    deliveredById: user.id,
    deliveredByName: user.name,
    note: payload.note ?? null,
    createdAt: now,
    items: recordItems,
  });
  const message = recordItems.map((line) => {
    const item = ticket.items.find((it) => it.id === line.itemId);
    return `${line.itemId}: ${Number(item.qtyDelivered).toLocaleString('en-US')}/${Number(item.qty).toLocaleString('en-US')}`;
  }).join(', ');
  pushEvent(ticket, user, 'DELIVERY_RECORDED', ticket.status, ticket.status, message);
  const full = ticket.items.every((item) => Number(item.qtyDelivered ?? 0) >= Number(item.qty ?? 0));
  if (full) {
    ticket.fulfillmentStatus = 'FULLY_DELIVERED';
    pushEvent(ticket, user, 'DELIVERY_COMPLETED', ticket.status, ticket.status, completing ? 'ส่งมอบครบ' : message);
    autoAdvanceStage(ticket, 'DELIVERED', user);
    // Second CLOSED_PAID gate: a deal paid in full before delivery closes exactly
    // when delivery completes.
    maybeAdvanceClosedPaid(ticket, user);
  } else {
    ticket.fulfillmentStatus = 'PARTIALLY_DELIVERED';
  }
}

function recordPaymentForTicket(ticket, user, payload) {
  const kind = String(payload.kind ?? '').trim().toUpperCase();
  if (!['DEPOSIT', 'BALANCE', 'ADJUSTMENT'].includes(kind)) fail(`Unknown payment kind '${payload.kind}'`, 400);
  const amount = moneyValue(payload.amount);
  if (amount <= 0) fail('ยอดรับชำระต้องมากกว่า 0', 400);
  const payable = payableAmount(ticket);
  const paid = sumPaid(ticket.id);
  const signed = kind === 'ADJUSTMENT' ? -amount : amount;
  const newPaid = moneyValue(paid + signed);
  if (newPaid < 0) fail('ยอดรับชำระสุทธิห้ามติดลบ', 400);
  const note = (payload.note ?? '').trim() || null;
  if (newPaid > payable && !payload.allowOverpayment) fail('ยอดรับชำระเกินยอดที่ต้องชำระ กรุณายืนยัน overpayment พร้อมเหตุผล', 400);
  if (newPaid > payable && !note) fail('การรับชำระเกินยอดต้องระบุเหตุผล', 400);
  const receiptRef = (payload.receiptRef ?? '').trim() || null;
  if (receiptRef && (db.paymentReceipts ?? []).some((r) => r.ticketId === ticket.id && r.receiptRef === receiptRef)) {
    fail('เลขอ้างอิงรับชำระซ้ำ', 409);
  }
  const nextId = Math.max(0, ...(db.paymentReceipts ?? []).map((r) => r.receiptId)) + 1;
  const now = new Date().toISOString();
  db.paymentReceipts.push({
    receiptId: nextId,
    ticketId: ticket.id,
    kind,
    amount,
    currency: 'THB',
    receivedAt: payload.receivedAt || now,
    recordedById: user.id,
    recordedByName: user.name,
    note,
    depositNoticeId: payload.depositNoticeId ?? null,
    receiptRef,
    createdAt: now,
  });
  pushEvent(ticket, user, 'PAYMENT_RECORDED', ticket.status, ticket.status,
    `kind=${kind}, amount=${amount}, paid=${newPaid}, payable=${payable}${note ? ` — ${note}` : ''}`);
  reconcilePaymentStatus(ticket, user);
  ticket.updatedAt = now.slice(0, 10);
}

// ── Thai date helper (mirrors QuotationRenderer.java thaiDate) ───────────────
const MOCK_THAI_MONTHS = ['มกราคม','กุมภาพันธ์','มีนาคม','เมษายน','พฤษภาคม','มิถุนายน','กรกฎาคม','สิงหาคม','กันยายน','ตุลาคม','พฤศจิกายน','ธันวาคม'];
function mockThaiDate(d) {
  if (!d) return '';
  const date = d instanceof Date ? d : new Date(d);
  return `${date.getDate()} ${MOCK_THAI_MONTHS[date.getMonth()]} ${date.getFullYear() + 543}`;
}

function mockItemDesc(it) {
  return [it.brand, it.model, it.color, it.texture, it.size].filter(Boolean).join(' ');
}

// Demo-mode placeholder blob. The real xlsx is rendered server-side by Apache POI
// (QuotationRenderer/RemainingInvoiceRenderer/DepositNoticeRenderer) and streamed to the
// client; mock mode only needs to return a valid Blob so download callers stay happy without
// pulling in the SheetJS (`xlsx`) dependency, which carries an unpatched high-severity advisory.
function mockDocPlaceholderBlob(lines) {
  const body = ['⚠ Demo Mode — ไฟล์จริงสร้างจาก template บน server (Apache POI)', '', ...lines].join('\n');
  return new Blob([body], { type: 'text/plain;charset=utf-8' });
}

// ── Quotation XLSX (demo placeholder) — real file from QuotationRenderer.java ───
// Mirrors TicketService.loadQuotationContext (V49): if this quotation has a snapshot
// (items + customer/project header frozen at issue time), render from that — never from
// today's live ticket data. Older mock quotations (created before this change, or a
// freshly-loaded page that never re-ran quotation()) have no `items` array and fall back
// to live data, matching the backend's legacy-quotation fallback.
async function buildMockQuotationXlsx(ticketId, quotationId) {
  const ticket = findTicketRaw(Number(ticketId));
  const quotation = (ticket.quotations ?? []).find((q) => q.id === Number(quotationId));
  if (!quotation) fail('Quotation not found', 404);

  const hasSnapshot = Array.isArray(quotation.items) && quotation.items.length > 0;
  const issueDate = quotation.issuedAt ? new Date(quotation.issuedAt) : new Date();
  const priceItems = hasSnapshot ? quotation.items : ticket.items.filter((it) => it.approvedPrice != null);
  const customerName = hasSnapshot ? (quotation.customerName ?? '') : (ticket.customerName ?? '');
  const projectName = hasSnapshot ? quotation.projectName : ticket.projectName;
  const lines = [
    `ใบเสนอราคา  เลขที่ ${quotation.number ?? ''}`,
    `วันที่: ${mockThaiDate(issueDate)}`,
    `ลูกค้า: ${customerName}`,
    ...(projectName ? [`Project: ${projectName}`] : []),
    '',
    ...priceItems.map((it, i) => {
      const qty = Number(it.qty) || 0;
      const price = Number(it.approvedPrice) || 0;
      return `${i + 1}. ${mockItemDesc(it)} — ${qty} ${it.rawUnit ?? 'แผ่น'} × ${price}`;
    }),
  ];
  return mockDocPlaceholderBlob(lines);
}

// ── Quotation HTML preview — shown when "PDF" is clicked in demo mode ────────
// Same snapshot-first / live-data-fallback rule as buildMockQuotationXlsx above.
function buildMockQuotationHtml(ticketId, quotationId) {
  const ticket = findTicketRaw(Number(ticketId));
  const quotation = (ticket.quotations ?? []).find((q) => q.id === Number(quotationId));
  if (!quotation) fail('Quotation not found', 404);
  const hasSnapshot = Array.isArray(quotation.items) && quotation.items.length > 0;
  const priceItems = hasSnapshot ? quotation.items : ticket.items.filter((it) => it.approvedPrice != null);
  const customerName = hasSnapshot ? (quotation.customerName ?? '') : (ticket.customerName ?? '');
  const fmtNum = (n) => Number(n).toLocaleString('th-TH', { minimumFractionDigits: 2 });
  const rowsHtml = priceItems.map((it, i) => {
    const amt = Number(it.approvedPrice) * Number(it.qty);
    return `<tr><td>${i+1}</td><td>${mockItemDesc(it)}</td><td style="text-align:right">${Number(it.qty).toLocaleString('th-TH')}</td><td>${it.rawUnit ?? 'แผ่น'}</td><td style="text-align:right">${fmtNum(it.approvedPrice)}</td><td style="text-align:right">${fmtNum(amt)}</td></tr>`;
  }).join('');
  const total = fmtNum(quotation.totalAmount ?? 0);
  const html = `<!DOCTYPE html><html lang="th"><head><meta charset="utf-8"/><title>ใบเสนอราคา ${quotation.number}</title>
<style>body{font-family:sans-serif;padding:40px;color:#1e293b;max-width:900px;margin:auto}
h2{margin:0 0 4px}.meta{color:#64748b;font-size:13px;margin-bottom:24px}
table{width:100%;border-collapse:collapse;margin-top:16px}
th{background:#f1f5f9;border:1px solid #cbd5e1;padding:8px 10px;text-align:left;font-size:13px}
td{border:1px solid #e2e8f0;padding:8px 10px;font-size:13px}
.banner{background:#fef3c7;border:1px solid #f59e0b;border-radius:6px;padding:10px 14px;margin-bottom:20px;font-size:13px;color:#92400e}
.total{font-weight:700;font-size:15px;text-align:right;margin-top:16px}</style></head>
<body><div class="banner">⚠ Demo Mode — PDF จริงสร้างจาก template บน server</div>
<h2>ใบเสนอราคา</h2>
<div class="meta">เลขที่: <strong>${quotation.number}</strong> &nbsp;|&nbsp; ลูกค้า: <strong>${customerName}</strong> &nbsp;|&nbsp; วันที่: ${mockThaiDate(new Date(quotation.issuedAt))}</div>
<table><thead><tr><th>#</th><th>รายละเอียด</th><th>จำนวน</th><th>หน่วย</th><th>ราคา/หน่วย</th><th>เป็นเงิน (บาท)</th></tr></thead>
<tbody>${rowsHtml}</tbody>
<tfoot><tr><td colspan="5" style="text-align:right;font-weight:700">รวมเป็นเงิน</td><td style="text-align:right;font-weight:700">${total}</td></tr></tfoot></table>
<div class="total">ยอดรวมทั้งสิ้น: ${total} บาท</div></body></html>`;
  return new Blob([html], { type: 'text/html;charset=utf-8' });
}

// ── Remaining invoice XLSX (demo placeholder) — real file from RemainingInvoiceRenderer.java ─
async function buildMockRemainingInvoiceXlsx(ticketId) {
  const ticket = findTicketRaw(Number(ticketId));
  if (!ticket) fail('Ticket not found', 404);

  const today = new Date();
  const thaiYear2 = String(today.getFullYear() + 543).slice(-2);
  const docNumber = `GLR${thaiYear2}${String(ticketId).padStart(3, '0')}`;
  const firstQ = (ticket.quotations ?? [])[0];
  const priceItems = ticket.items.filter((it) => it.approvedPrice != null);
  const lines = [
    `ใบแจ้งหนี้ส่วนที่เหลือ  เลขที่ ${docNumber}`,
    `วันที่: ${mockThaiDate(today)}`,
    `ลูกค้า: ${ticket.customerName ?? ''}`,
    ...(firstQ ? [`อ้างอิง: ${firstQ.number}`] : []),
    ...(ticket.projectName ? [`Project: ${ticket.projectName}`] : []),
    '',
    ...priceItems.map((it, i) => {
      const qty = Number(it.qty) || 0;
      return `${i + 1}. ${mockItemDesc(it)} — ${qty} ${it.rawUnit ?? 'แผ่น'} × ${Number(it.approvedPrice) || 0}`;
    }),
    `หัก  มัดจำ${firstQ ? '  ' + firstQ.number : ''}`,
  ];
  return mockDocPlaceholderBlob(lines);
}

function pushEvent(ticket, actor, kind, fromStatus, toStatus, message, itemSnapshot = null) {
  const nextId = Math.max(...db.tickets.flatMap((t) => t.events.map((e) => e.id)), 0) + 1;
  ticket.events.push({ id: nextId, ticketId: ticket.id, actorId: actor.id, actorName: actor.name, kind, fromStatus, toStatus, message, createdAt: new Date().toISOString(), itemSnapshot });
}

function addNotification(userId, ticketId, ticketCode, type, message) {
  const nextId = Math.max(...db.notifications.map((n) => n.id), 0) + 1;
  db.notifications.unshift({ id: nextId, userId, ticketId, ticketCode, type, message, read: false, createdAt: new Date().toISOString() });
}

// Deal pipeline (V50): mirrors TicketService.autoAdvanceStage — monotonic
// forward-only, no-op while lost. Called from the 4 milestone transitions.
function autoAdvanceStage(ticket, targetStage, user) {
  // ACTIVE is the whole test — since V57 lost_reason SURVIVES a reopen, so keying
  // on it would silently disable auto-advance on every reopened deal.
  if ((ticket.lifecycle ?? 'ACTIVE') !== 'ACTIVE') return;
  if (dealStageIndex(targetStage) <= dealStageIndex(ticket.salesStage)) return;
  const fromStage = ticket.salesStage;
  ticket.salesStage = targetStage;
  ticket.stageUpdatedAt = new Date().toISOString();
  pushEvent(ticket, user, 'STAGE_CHANGED', fromStage, targetStage, 'อัตโนมัติจากขั้นตอนของดีล');
}

function markQuotationStatus(id, quotationId, targetStatus, eventKind, payload = {}) {
  const user = requireSession();
  const ticket = findTicketRaw(Number(id));
  requireActive(ticket);
  const owner = user.role === 'sales' && ticket.createdById === user.id;
  if (!owner && user.role !== 'ceo') fail('Forbidden', 403);
  const quotation = (ticket.quotations ?? []).find((q) => q.id === Number(quotationId));
  if (!quotation) fail('Quotation not found', 404);
  const legal = targetStatus === 'SENT'
    ? ['ISSUED', 'SENT'].includes(quotation.docStatus)
    : ['ISSUED', 'SENT'].includes(quotation.docStatus);
  if (!legal) fail(`Cannot mark quotation ${targetStatus} from ${quotation.docStatus}`, 409);
  quotation.docStatus = targetStatus;
  const now = new Date().toISOString();
  if (targetStatus === 'SENT') quotation.sentAt = now;
  if (targetStatus === 'ACCEPTED') quotation.acceptedAt = now;
  if (targetStatus === 'REJECTED') quotation.rejectedAt = now;
  ticket.updatedAt = now.slice(0, 10);
  pushEvent(ticket, user, eventKind, ticket.status, ticket.status,
    `${quotation.number} (${quotation.recipientType})${(payload.note || '').trim() ? ` — ${payload.note.trim()}` : ''}`);
  return delay({ ticket: buildTicketDetail(ticket) });
}

function buildTicketDetail(ticket) {
  const project = ticket.projectId ? mockProjects.find((p) => p.id === ticket.projectId) : null;
  const contact = ticket.contactId ? mockContacts.find((c) => c.id === ticket.contactId) : null;
  const paymentFields = derivePaymentFields(ticket);
  return {
    summary: {
      id: ticket.id, code: ticket.code, type: ticket.type, title: ticket.title,
      status: ticket.status, priority: ticket.priority,
      createdById: ticket.createdById, createdByName: ticket.createdByName,
      assignedToId: ticket.assignedToId, assignedToName: ticket.assignedToName,
      customerName: ticket.customerName,
      customerId: ticket.customerId ?? null,
      projectId: ticket.projectId ?? null,
      projectName: project?.name ?? null,
      contactId: ticket.contactId ?? null,
      contactName: contact ? `${contact.firstName} ${contact.lastName ?? ''}`.trim() : null,
      note: ticket.note,
      createdAt: ticket.createdAt, updatedAt: ticket.updatedAt, closedAt: ticket.closedAt,
      itemCount: ticket.items.length, hasEdits: ticket.hasEdits ?? false,
      paymentStatus: ticket.paymentStatus ?? null,
      fulfillmentStatus: ticket.fulfillmentStatus ?? null,
      salesStage: ticket.salesStage, lostReason: ticket.lostReason ?? null,
      reopenedAt: ticket.reopenedAt ?? null, reopenCount: ticket.reopenCount ?? 0,
      lostAt: ticket.lostAt ?? null, stageUpdatedAt: ticket.stageUpdatedAt ?? ticket.updatedAt,
      lifecycle: ticket.lifecycle ?? 'ACTIVE',
      tenderRequirement: ticket.tenderRequirement ?? 'UNKNOWN',
      depositPolicy: ticket.depositPolicy ?? 'REQUIRED',
      depositPolicyReason: ticket.depositPolicyReason ?? null,
      entryChannel: ticket.entryChannel ?? 'DESIGNER_LED',
      cancelReason: ticket.cancelReason ?? null,
      cancelledAt: ticket.cancelledAt ?? null,
      closeConfirmedAt: ticket.closeConfirmedAt ?? null,
      closeConfirmedByName: ticket.closeConfirmedByName ?? null,
      invoiceOnFile: hasInvoiceAttachment(ticket),
      ...paymentFields,
    },
    items: ticket.items, events: ticket.events,
    quotation: ticket.quotations ? ticket.quotations[0] ?? null : ticket.quotation ?? null,
    quotations: ticket.quotations ?? (ticket.quotation ? [ticket.quotation] : []),
  };
}

function commissionMonth(value) {
  return (value || new Date().toISOString()).slice(0, 7);
}

function invoiceCalculation(payload) {
  const actualReceived = Number(payload.grossAmount || 0)
    - Number(payload.bankFees || 0)
    - Number(payload.suspenseVat || 0)
    - Number(payload.transportFee || 0)
    - Number(payload.cutFee || 0)
    - Number(payload.shortfall || 0);
  return {
    actualReceived: Number(actualReceived.toFixed(2)),
    commissionableBase: Number((actualReceived / 1.07).toFixed(2)),
  };
}

function progressiveCommission(baseValue) {
  const base = Math.max(0, Number(baseValue || 0));
  let total = 0;
  for (let tier = 1; tier <= 12; tier++) {
    const lower = (tier - 1) * 250000;
    const upper = tier * 250000;
    const block = Math.max(0, Math.min(base, upper) - lower);
    total += block * ((tier * 0.25) / 100);
  }
  if (base > 3000000) total += (base - 3000000) * 0.075;
  return Number(total.toFixed(2));
}

function buildCommissionRecord(record) {
  return structuredClone({
    managerApprovedBy: null,
    managerApprovedByName: null,
    managerApprovedAt: null,
    ceoApprovedBy: null,
    ceoApprovedByName: null,
    ceoApprovedAt: null,
    rejectedById: null,
    rejectedByName: null,
    rejectedAt: null,
    rejectionReason: null,
    ...record,
    invoiceDetails: {
      invoiceAttachmentId: null,
      invoiceAttachmentFileName: null,
      ...record.invoiceDetails,
    },
  });
}

function employeeForUser(user) {
  return user?.employeeId ? db.employees.find((employee) => employee.id === user.employeeId) : null;
}

function dashboardManager(user) {
  const employee = employeeForUser(user);
  return Boolean(
    user?.manager
    || user?.role === 'sales_manager'
    || employee?.positionTh === 'ผู้จัดการฝ่าย'
  );
}

function dashboardDivisionId(user) {
  return user?.divisionId ?? employeeForUser(user)?.divisionId ?? null;
}

function dashboardEmployeeScope(user) {
  const employee = employeeForUser(user);
  if (['hr', 'ceo'].includes(user.role)) return { label: 'all', employees: db.employees };
  if (dashboardManager(user) && dashboardDivisionId(user)) {
    return {
      label: 'division',
      employees: db.employees.filter((item) => item.divisionId === dashboardDivisionId(user)),
    };
  }
  return { label: employee ? 'self' : 'none', employees: employee ? [employee] : [] };
}

function dashboardHeadcount(user) {
  const company = ['hr', 'ceo'].includes(user.role);
  const manager = dashboardManager(user);
  const divisionId = dashboardDivisionId(user);
  const employees = company
    ? db.employees
    : manager && divisionId
      ? db.employees.filter((employee) => employee.divisionId === divisionId)
      : [];
  if (employees.length === 0) return { scope: 'none', active: null, inactive: null, total: null, byDivision: [] };

  const byDivision = [...employees.reduce((groups, employee) => {
    const key = employee.divisionId || 'unknown';
    const current = groups.get(key) || {
      divisionId: employee.divisionId ?? null,
      divisionCode: employee.divisionId ?? null,
      divisionName: employee.divisionTh || 'ไม่ระบุฝ่าย',
      active: 0,
      inactive: 0,
      total: 0,
    };
    if (employee.active) current.active += 1;
    else current.inactive += 1;
    current.total += 1;
    groups.set(key, current);
    return groups;
  }, new Map()).values()];

  return {
    scope: company ? 'all' : 'division',
    active: employees.filter((employee) => employee.active).length,
    inactive: employees.filter((employee) => !employee.active).length,
    total: employees.length,
    byDivision,
  };
}

function dashboardTickets(user) {
  const allVisible = ['import', 'ceo'].includes(user.role);
  const ownVisible = user.role === 'sales';
  const list = allVisible
    ? db.tickets
    : ownVisible
      ? db.tickets.filter((ticket) => ticket.createdById === user.id || (user.employeeId && ticket.createdById === user.employeeId))
      : [];
  const now = new Date();
  const monthStart = new Date(now.getFullYear(), now.getMonth(), 1).toISOString().slice(0, 10);
  const threeDaysAgo = new Date(now - 3 * 86400000).toISOString().slice(0, 10);
  return {
    scope: allVisible ? 'all' : ownVisible ? 'self' : 'none',
    draft: list.filter((ticket) => ticket.status === 'draft').length,
    submitted: list.filter((ticket) => ticket.status === 'submitted').length,
    inReview: list.filter((ticket) => ticket.status === 'in_review').length,
    priceProposed: list.filter((ticket) => ticket.status === 'price_proposed').length,
    approved: list.filter((ticket) => ticket.status === 'approved').length,
    quotationIssued: list.filter((ticket) => ticket.status === 'quotation_issued').length,
    documentIssued: list.filter((ticket) => ticket.status === 'document_issued').length,
    closed: list.filter((ticket) => ticket.status === 'closed').length,
    cancelled: list.filter((ticket) => ticket.status === 'cancelled').length,
    total: list.length,
    totalOpen: list.filter((ticket) => !['closed', 'cancelled'].includes(ticket.status)).length,
    closedThisMonth: list.filter((ticket) => ticket.status === 'closed' && ticket.closedAt >= monthStart).length,
    cancelledThisMonth: list.filter((ticket) => ticket.status === 'cancelled' && ticket.updatedAt >= monthStart).length,
    overdueOver3Days: list.filter((ticket) => !['closed', 'cancelled', 'draft'].includes(ticket.status) && ticket.createdAt < threeDaysAgo).length,
    onHold: list.filter((ticket) => ticket.lifecycle === 'ON_HOLD').length,
    dormant: list.filter((ticket) => ticket.lifecycle === 'DORMANT').length,
    paymentOverdue: list.filter((ticket) => derivePaymentFields(ticket).overdue).length,
    partiallyDelivered: list.filter((ticket) => ticket.fulfillmentStatus === 'PARTIALLY_DELIVERED').length,
  };
}

function dashboardPending(user, ticketSummary) {
  const employeeScope = dashboardEmployeeScope(user);
  const employeeIds = new Set(employeeScope.employees.map((employee) => employee.id));
  const employeeSelf = employeeScope.label === 'self';
  const manager = employeeScope.label === 'division';
  const isHr = user.role === 'hr';
  const profileRequests = isHr || employeeSelf
    ? db.profileRequests.filter((request) => employeeIds.has(request.employeeId) && request.status === 'pending').length
    : 0;
  const leave = isHr || manager || employeeSelf
    ? db.leaveRequests.filter((request) => employeeIds.has(request.employeeId) && request.status === 'SUBMITTED').length
    : 0;
  const commissions = ['sales_manager', 'ceo'].includes(user.role)
    ? db.commissions.filter((record) => ['SUBMITTED', 'MANAGER_APPROVED'].includes(record.status)).length
    : user.role === 'sales'
      ? db.commissions.filter((record) => record.salesRepId === user.id && record.status === 'SUBMITTED').length
      : 0;
  const tickets = ['sales', 'import', 'ceo'].includes(user.role)
    ? ticketSummary.submitted + ticketSummary.inReview + ticketSummary.priceProposed
    : 0;
  return {
    scope: employeeScope.label,
    profileRequests,
    overtime: 0,
    leave,
    commissions,
    tickets,
    total: profileRequests + leave + commissions + tickets,
  };
}

function dashboardAttendance(user) {
  if (['hr', 'ceo'].includes(user.role)) {
    return { scope: 'all', todayPresent: 0, lateToday: 0, missingCheckout: 0, punchCountToday: 0, monthlyAttendanceDays: 0 };
  }
  if (dashboardManager(user) && dashboardDivisionId(user)) {
    return { scope: 'division', todayPresent: 0, lateToday: 0, missingCheckout: 0, punchCountToday: 0, monthlyAttendanceDays: 0 };
  }
  return { scope: 'self', monthlyAttendanceDays: 0, todayStatus: 'NO_RECORD', firstIn: null, lastOut: null, lateMinutesToday: 0 };
}

function dashboardNotifications(user) {
  const ids = new Set([user.id, user.employeeId].filter(Boolean));
  const list = db.notifications.filter((notification) => ids.has(notification.userId));
  return {
    unread: list.filter((notification) => !notification.read).length,
    read: list.filter((notification) => notification.read).length,
    total: list.length,
  };
}

// Reads the stored reports-to link — mirrors hr.employee.reports_to_employee_id
// (the self-FK), not a live org-chart scan. Division managers carry managerId:
// null in the seed (no row above them), same as the real NULL-FK state.
function managerIdForEmployee(employee) {
  return employee?.managerId ?? null;
}

// Mirrors LeaveService.canReviewEmployee()/isDirectManager() — hr bypass, else
// stored-FK match on an *active* target employee. No division fallback: unlike
// overtime, a ฝ่าย manager cannot review a colleague's leave just by sharing a
// division.
function canReviewLeave(user, employeeId) {
  if (user.role === 'hr') return true;
  const employee = findEmployee(employeeId);
  return Boolean(employee?.active && user.employeeId
    && managerIdForEmployee(employee) === user.employeeId);
}

// Mirrors OvertimeService.managesEmployee() — direct report (stored FK) OR
// division manager (position-derived user.manager() sharing the employee's
// division, excluding self). Deliberately has NO hr/admin bypass (HR may review
// leave but never overtime) and NO active() check (Java has none here either).
// Overtime must not reuse canReviewLeave() — that was the #199 bug, where the
// mock let HR approve OT while the real backend returns 403.
//
// These two gates look similar but encode genuinely different Java models
// (active-check + no division term vs. no active-check + division term). Do
// NOT merge them "for DRY" — that reintroduces exactly the #199 bug class.
function canReviewOvertime(user, employeeId) {
  if (!user.employeeId || employeeId === user.employeeId) return false;
  const employee = findEmployee(employeeId);
  const directReport = managerIdForEmployee(employee) === user.employeeId;
  const divisionManager = dashboardManager(user)
    && dashboardDivisionId(user) != null
    && dashboardDivisionId(user) === employee.divisionId
    && employeeId !== user.employeeId;
  return directReport || divisionManager;
}

function leaveTypeByCode(code) {
  const type = db.leaveTypes.find((item) => item.code === String(code || '').toUpperCase());
  if (!type) fail('Invalid leave type', 400);
  return type;
}

function workingDaysBetween(startDate, endDate) {
  const start = new Date(`${startDate}T00:00:00`);
  const end = new Date(`${endDate}T00:00:00`);
  if (end < start) fail('Leave end date must be on or after start date', 400);
  if (startDate.slice(0, 4) !== endDate.slice(0, 4)) fail('Leave requests cannot span quota years', 400);
  let days = 0;
  const cursor = new Date(start);
  while (cursor <= end) {
    const day = cursor.getDay();
    if (day !== 0 && day !== 6) days += 1;
    cursor.setDate(cursor.getDate() + 1);
  }
  if (days <= 0) fail('Leave range must include at least one weekday', 400);
  return days;
}

function leaveUsedDays(employeeId, leaveTypeCode, quotaYear, statuses) {
  return db.leaveRequests
    .filter((request) => request.employeeId === employeeId
      && request.leaveTypeCode === leaveTypeCode
      && request.quotaYear === quotaYear
      && statuses.includes(request.status))
    .reduce((sum, request) => sum + Number(request.totalDays || 0), 0);
}

function leaveBalance(employeeId, type, quotaYear) {
  const approvedDays = leaveUsedDays(employeeId, type.code, quotaYear, ['APPROVED']);
  const pendingDays = leaveUsedDays(employeeId, type.code, quotaYear, ['SUBMITTED']);
  return {
    leaveTypeCode: type.code,
    leaveTypeNameTh: type.nameTh,
    leaveTypeNameEn: type.nameEn,
    annualQuotaDays: type.annualQuotaDays,
    approvedDays,
    pendingDays,
    remainingDays: Math.max(0, Number(type.annualQuotaDays || 0) - approvedDays - pendingDays),
    requiresAttachment: type.requiresAttachment,
  };
}

function buildLeaveRecord(record) {
  const employee = db.employees.find((item) => item.id === record.employeeId);
  const managerEmployeeId = managerIdForEmployee(employee);
  const manager = managerEmployeeId ? db.employees.find((item) => item.id === managerEmployeeId) : null;
  const leaveType = leaveTypeByCode(record.leaveTypeCode);
  return {
    ...structuredClone(record),
    employeeCode: employee?.code || null,
    employeeName: employee?.nameTh || null,
    managerEmployeeId,
    managerName: manager?.nameTh || null,
    leaveTypeNameTh: leaveType.nameTh,
    leaveTypeNameEn: leaveType.nameEn,
  };
}

function overtimeMinutesBetween(startAt, endAt) {
  const start = new Date(startAt);
  const end = new Date(endAt);
  const diff = Math.round((end.getTime() - start.getTime()) / 60000);
  if (Number.isNaN(diff) || diff <= 0) fail('Overtime end time must be after start time', 400);
  return diff;
}

function buildOvertimeRecord(record) {
  const employee = db.employees.find((item) => item.id === record.employeeId);
  const managerEmployeeId = managerIdForEmployee(employee);
  const manager = managerEmployeeId ? db.employees.find((item) => item.id === managerEmployeeId) : null;
  const managerApprover = record.managerApprovedBy ? db.employees.find((item) => item.id === record.managerApprovedBy) : null;
  const ceoApprover = record.ceoApprovedBy ? db.employees.find((item) => item.id === record.ceoApprovedBy) : null;
  return {
    ...structuredClone(record),
    employeeCode: employee?.code || null,
    employeeName: employee?.nameTh || null,
    managerEmployeeId,
    managerName: manager?.nameTh || null,
    managerApprovedByName: managerApprover?.nameTh || null,
    ceoApprovedByName: ceoApprover?.nameTh || null,
  };
}

function employeeWithRequestMeta(employee) {
  return {
    ...employee,
    pendingRequestCount: db.profileRequests.filter((request) => request.employeeId === employee.id && request.status === 'pending').length,
  };
}

function findEmployee(id) {
  const employee = db.employees.find((item) => item.id === Number(id));
  if (!employee) fail('Employee not found', 404);
  return employee;
}

function applyApprovedProfileRequest(request) {
  const employee = findEmployee(request.employeeId);
  if (request.fieldKey === 'phone') employee.phone = request.newValue;
  if (request.fieldKey === 'email') employee.email = request.newValue;
  if (request.fieldKey === 'address') employee.currentAddress = { ...employee.currentAddress, line1: request.newValue };
  if (request.fieldKey === 'emergency') {
    const [name, phone] = request.newValue.split('·').map((part) => part.trim());
    employee.emergencyContact = { ...employee.emergencyContact, name: name || request.newValue, phone: phone || employee.emergencyContact.phone };
  }
}

function createEmployeeRecord(payload) {
  const id = Math.max(...db.employees.map((employee) => employee.id)) + 1;
  const division = payload.divisionId || 'SAL';
  const department = payload.departmentTh || 'ขายปลีก';
  const statusId = payload.statusId || 'ACT';
  const statusMap = {
    ACT: ['ทำงานปกติ', 'success', true],
    PRB: ['ทดลองงาน', 'warning', true],
    RSG: ['ลาออก', 'danger', false],
  };
  const [statusTh, statusTone, active] = statusMap[statusId] ?? statusMap.ACT;

  return {
    id,
    code: payload.code || `GLR-${1000 + id}`,
    badge: payload.badge || `BC-${Date.now().toString().slice(-8)}`,
    nameTh: payload.nameTh,
    nameEn: payload.nameEn || payload.nameTh,
    nickName: payload.nickName || '',
    initials: payload.nameEn ? payload.nameEn.split(' ').map((part) => part[0]).join('').slice(0, 2).toUpperCase() : 'GL',
    avatarBg: '#e0e7ff',
    avatarFg: '#4338ca',
    titleTh: payload.titleTh || 'นาย',
    genderTh: payload.genderTh || 'ไม่ระบุ',
    birthDate: payload.birthDate || '1995-01-01',
    age: 31,
    nationality: 'ไทย',
    maritalStatus: 'โสด',
    email: payload.email,
    phone: payload.phone,
    divisionId: division,
    divisionTh: payload.divisionTh || division,
    divisionEn: '',
    departmentTh: department,
    positionTh: payload.positionTh || 'เจ้าหน้าที่',
    positionEn: '',
    level: payload.level || 'O2',
    locationTh: payload.locationTh || 'สำนักงานใหญ่ กรุงเทพฯ',
    statusId,
    statusTh,
    statusTone,
    active,
    payType: 'รายเดือน',
    salary: Number(payload.salary || 0),
    hireDate: payload.hireDate || new Date().toISOString().slice(0, 10),
    confirmationDate: payload.confirmationDate || null,
    reportsTo: payload.reportsTo || '-',
    bank: '',
    bankAccount: '',
    currentAddress: { line1: payload.address || '-', district: '', province: '', postalCode: '' },
    emergencyContact: { name: payload.emergencyName || '-', relationship: payload.emergencyRelationship || '-', phone: payload.emergencyPhone || '-' },
    education: [],
    assignments: [],
    salaryHistory: [],
    sensitive: {},
  };
}

// --- price import helpers ---

// Mirrors the version transition in PriceImportService.commit(): the committed
// version becomes ACTIVE and the factory's previous ACTIVE version is ARCHIVED —
// kept for history, never deleted. Shared by priceImport.commit and
// priceImport.uploadAndCommit so the two cannot drift apart.
// Returns the number of versions archived.
function activateVersion(versionId) {
  const version = mockPriceImportVersions.find((item) => item.versionId === versionId);
  if (!version) return 0;
  const superseded = mockPriceImportVersions
    .filter((item) => item.factoryId === version.factoryId && item.status === 'ACTIVE' && item.versionId !== versionId);
  superseded.forEach((item) => { item.status = 'ARCHIVED'; });
  version.status = 'ACTIVE';
  return superseded.length;
}

function factoryNameFor(factoryId) {
  return mockPriceImportFactories.find((item) => item.factoryId === factoryId)?.name ?? null;
}

export const api = {
  // Mirrors AuthController + AuthService (auth/).
  auth: {
    async login(payload) {
      const email = payload?.email?.trim().toLowerCase();
      const requestedRole = payload?.role;
      const user = requestedRole
        ? db.users.find((item) => item.role === requestedRole && item.active)
        : db.users.find((item) => item.email.toLowerCase() === email && item.active);

      // Collapsed to one message (matches AuthService.INVALID_CREDENTIALS): must not
      // reveal whether the email exists, only whether the credential pair is valid.
      if (!user) fail('Invalid email or password', 401);
      if (!requestedRole && payload?.password && payload.password !== user.password) fail('Invalid email or password', 401);

      sessionUser = user;
      return delay({ user: publicUser(user) });
    },
    async logout() {
      sessionUser = null;
      return delay({ ok: true });
    },
    async me() {
      return delay({ user: publicUser(requireSession()) });
    },
    async changePassword(payload) {
      const user = requireSession();
      if (!payload?.currentPassword || payload.currentPassword !== user.password) {
        fail('Current password is incorrect', 401);
      }
      if (!payload?.newPassword || payload.newPassword.length < 8) {
        fail('New password must be at least 8 characters', 400);
      }
      if (payload.newPassword === user.password) {
        fail('New password must differ from the current password', 400);
      }
      user.password = payload.newPassword;
      user.mustChangePassword = false;
      return delay({ user: publicUser(user) });
    },
  },
  // Mirrors EmployeeController + EmployeeService (employee/).
  employees: {
    async list(params = {}) {
      hasRole('hr');
      let employees = db.employees.map(employeeWithRequestMeta);
      if (params.search) {
        const query = params.search.toLowerCase();
        employees = employees.filter((employee) => (
          employee.nameTh.includes(params.search)
          || employee.nameEn.toLowerCase().includes(query)
          || employee.code.toLowerCase().includes(query)
          || employee.nickName.includes(params.search)
        ));
      }
      if (params.divisionId) employees = employees.filter((employee) => employee.divisionId === params.divisionId);
      if (params.departmentTh) employees = employees.filter((employee) => employee.departmentTh === params.departmentTh);
      if (params.statusId) employees = employees.filter((employee) => employee.statusId === params.statusId);
      if (params.active === 'true') employees = employees.filter((employee) => employee.active);
      if (params.active === 'false') employees = employees.filter((employee) => !employee.active);
      return delay({ employees });
    },
    async create(payload) {
      hasRole('hr');
      const employee = createEmployeeRecord(payload);
      db.employees.unshift(employee);
      return delay({ employee });
    },
    async get(id) {
      const user = requireSession();
      const employee = findEmployee(id);
      // Mirrors EmployeeService.get(): hr, or the employee viewing themselves — no other role.
      if (user.role !== 'hr' && user.employeeId !== employee.id) fail('Forbidden', 403);
      return delay({ employee: employeeWithRequestMeta(employee) });
    },
    async update(id, payload) {
      hasRole('hr');
      const employee = findEmployee(id);
      Object.assign(employee, payload);
      if (payload.statusId) {
        const status = { ACT: ['ทำงานปกติ', 'success', true], PRB: ['ทดลองงาน', 'warning', true], RSG: ['ลาออก', 'danger', false] }[payload.statusId];
        if (status) {
          employee.statusTh = status[0];
          employee.statusTone = status[1];
          employee.active = status[2];
        }
      }
      return delay({ employee: employeeWithRequestMeta(employee) });
    },
  },
  // Mirrors ProfileRequestController + ProfileRequestService (profile/).
  profileRequests: {
    async list() {
      const user = requireSession();
      const rows = user.role === 'hr'
        ? db.profileRequests
        : db.profileRequests.filter((request) => request.employeeId === user.employeeId);
      const profileRequests = rows.map((request) => ({
        ...request,
        employee: db.employees.find((employee) => employee.id === request.employeeId),
      }));
      return delay({ profileRequests });
    },
    async create(payload) {
      const user = hasRole('employee');
      const employee = findEmployee(user.employeeId);
      const request = {
        id: Math.max(...db.profileRequests.map((item) => item.id)) + 1,
        employeeId: employee.id,
        fieldKey: payload.fieldKey,
        fieldLabel: payload.fieldLabel,
        oldValue: payload.oldValue,
        newValue: payload.newValue,
        requestedBy: employee.nameTh,
        requestedAt: new Date().toISOString().slice(0, 10),
        status: 'pending',
      };
      db.profileRequests.unshift(request);
      return delay({ profileRequest: { ...request, employee } });
    },
    async update(id, payload) {
      hasRole('hr');
      const request = db.profileRequests.find((item) => item.id === Number(id));
      if (!request) fail('Profile request not found', 404);
      request.status = payload.status;
      request.reviewedAt = new Date().toISOString().slice(0, 10);
      if (payload.reviewerNote !== undefined) request.reviewerNote = payload.reviewerNote;
      if (request.status === 'approved') applyApprovedProfileRequest(request);
      return delay({ profileRequest: { ...request, employee: findEmployee(request.employeeId) } });
    },
  },
  // Mirrors TicketController + TicketService (ticket/).
  tickets: {
    async list(params = {}) {
      const user = requireSession();
      // sales_manager: read+comment oversight only — kept here (not routed through
      // requireTicketViewer) to match the existing inline-array pattern; must move
      // in lockstep with requireTicketViewer/get() and TicketService.VIEWER_ROLES.
      if (!['sales', 'import', 'ceo', 'account', 'sales_manager'].includes(user.role)) fail('Forbidden', 403);
      let list = structuredClone(db.tickets);
      if (user.role === 'sales') list = list.filter((t) => t.createdById === user.id);
      if (params.status) list = list.filter((t) => t.status === params.status);
      const tickets = list.map((t) => ({
        id: t.id, code: t.code, type: t.type, title: t.title,
        status: t.status, priority: t.priority,
        createdById: t.createdById, createdByName: t.createdByName,
        assignedToId: t.assignedToId, assignedToName: t.assignedToName,
        customerName: t.customerName, note: t.note,
        projectId: t.projectId ?? null,
        projectName: t.projectId ? (mockProjects.find((p) => p.id === t.projectId)?.name ?? null) : null,
        createdAt: t.createdAt, updatedAt: t.updatedAt, closedAt: t.closedAt,
        itemCount: t.items.length,
        paymentStatus: t.paymentStatus ?? null,
        fulfillmentStatus: t.fulfillmentStatus ?? null,
        salesStage: t.salesStage, lostReason: t.lostReason ?? null,
        lostAt: t.lostAt ?? null, stageUpdatedAt: t.stageUpdatedAt ?? t.updatedAt,
        lifecycle: t.lifecycle ?? 'ACTIVE',
        tenderRequirement: t.tenderRequirement ?? 'UNKNOWN',
        depositPolicy: t.depositPolicy ?? 'REQUIRED',
        depositPolicyReason: t.depositPolicyReason ?? null,
        entryChannel: t.entryChannel ?? 'DESIGNER_LED',
        ...derivePaymentFields(t),
      }));
      return delay({ tickets });
    },

    async get(id) {
      const user = requireSession();
      if (!['sales', 'import', 'ceo', 'account', 'sales_manager'].includes(user.role)) fail('Forbidden', 403);
      const ticket = structuredClone(db.tickets.find((t) => t.id === Number(id)));
      if (!ticket) fail('Ticket not found', 404);
      if (user.role === 'sales' && ticket.createdById !== user.id) fail('Forbidden', 403);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async listPayments(id) {
      requireTicketViewer(id);
      return delay({ items: receiptsForTicket(id) });
    },

    async listDeliveries(id) {
      requireTicketViewer(id);
      return delay({ items: deliveryRecordsForTicket(id) });
    },

    async recordPayment(id, payload) {
      const user = hasRole('account', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      recordPaymentForTicket(ticket, user, payload ?? {});
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async reserveStock(id, payload) {
      const user = hasRole('import', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      reserveStockForTicket(ticket, user, payload ?? {});
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async recordDelivery(id, payload) {
      const user = hasRole('import', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      recordDeliveryForTicket(ticket, user, payload ?? {});
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async completeDelivery(id, payload = {}) {
      const user = hasRole('import', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      const remaining = (ticket.items ?? [])
        .map((item) => ({ itemId: item.id, qty: moneyValue(Number(item.qty ?? 0) - Number(item.qtyDelivered ?? 0)) }))
        .filter((line) => line.qty > 0);
      if (!remaining.length) fail('ไม่มีจำนวนค้างส่ง', 409);
      const allRemainingCoveredByStock = (ticket.items ?? []).every((item) => {
        const remainingQty = moneyValue(Number(item.qty ?? 0) - Number(item.qtyDelivered ?? 0));
        if (remainingQty <= 0) return true;
        return moneyValue(Number(item.qtyDelivered ?? 0) + remainingQty) <= Number(item.qtyFromStock ?? 0);
      });
      recordDeliveryForTicket(ticket, user, {
        source: allRemainingCoveredByStock ? 'STOCK' : 'WAREHOUSE',
        note: payload.note ?? null,
        lines: remaining,
      }, true);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async setBilling(id, payload) {
      const user = hasRole('account', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      ticket.billingDate = payload.billingDate ?? null;
      ticket.dueDate = payload.dueDate ?? null;
      ticket.creditTermDays = payload.creditTermDays ?? null;
      ticket.lastFollowUpAt = payload.lastFollowUpAt ?? null;
      ticket.nextFollowUpAt = payload.nextFollowUpAt ?? null;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'BILLING_UPDATED', ticket.status, ticket.status,
        `billing_date=${ticket.billingDate}, due_date=${ticket.dueDate}`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async actions(id) {
      const { user, ticket } = requireTicketViewer(id);
      const active = (ticket.lifecycle ?? 'ACTIVE') === 'ACTIVE';
      const availableActions = [];
      const add = (action, kind, label, extra = {}) => availableActions.push({ action, kind, label, ...extra });
      const owner = user.role === 'sales' && ticket.createdById === user.id;
      const dealOwner = owner || user.role === 'sales_manager' || user.role === 'ceo';
      const canIssueIr = ticket.status === 'quotation_issued'
        && ticket.fulfillmentStatus == null
        && (['DEPOSIT_NOTICE_ISSUED', 'DEPOSIT_PAID'].includes(ticket.paymentStatus)
          || (depositBypassesNotice(ticket) && (ticket.paymentStatus == null || ticket.paymentStatus === 'CUSTOMER_CONFIRMED')));

      if (active) {
        // Ticket-level SUBMIT is retired (commit 5/6, superseded by the
        // PricingRequest aggregate) — never advertised, so the UI never shows
        // a button that would 409 on click.
        if (user.role === 'import' && ticket.status === 'submitted') add('PICKUP', 'operational', 'รับเรื่อง');
        if (user.role === 'import' && ['in_review', 'price_proposed', 'approved'].includes(ticket.status)) add('PROPOSE_PRICE', 'operational', 'เสนอราคา', { requiredFields: ['items'] });
        if (user.role === 'ceo' && ticket.status === 'price_proposed') {
          add('CALCULATE_PRICES', 'operational', 'คำนวณราคา');
          add('OVERRIDE_ITEM_PRICE', 'operational', 'แก้ไขราคาด้วยตนเอง', { requiredFields: ['itemId', 'manualPrice'] });
          add('APPROVE', 'operational', 'อนุมัติราคา');
          add('REJECT', 'operational', 'ตีกลับราคา', { requiredFields: ['reason'] });
        }
        if (owner && ['approved', 'quotation_issued'].includes(ticket.status)) add('GENERATE_QUOTATION', 'doc', 'ออกใบเสนอราคา', { requiredFields: ['recipientType'] });
        const quotationManager = owner || user.role === 'ceo';
        const legalHeads = (ticket.quotations ?? []).filter((q) => ['ISSUED', 'SENT'].includes(q.docStatus));
        if (quotationManager && legalHeads.some((q) => q.docStatus === 'ISSUED')) {
          add('MARK_QUOTATION_SENT', 'doc', 'บันทึกว่าส่งใบเสนอราคาแล้ว', { requiredFields: ['quotationId'] });
        }
        if (quotationManager && legalHeads.length > 0) {
          add('MARK_QUOTATION_ACCEPTED', 'doc', 'บันทึกลูกค้ารับใบเสนอราคา', { requiredFields: ['quotationId'] });
          add('MARK_QUOTATION_REJECTED', 'doc', 'บันทึกลูกค้าปฏิเสธใบเสนอราคา', { requiredFields: ['quotationId'] });
        }
        if (owner && ticket.status === 'quotation_issued' && (ticket.paymentStatus == null || ticket.paymentStatus === 'CUSTOMER_CONFIRMED')) add('CONFIRM_CUSTOMER', 'payment', 'ลูกค้ายืนยัน');
        if (owner && ticket.status === 'quotation_issued' && ticket.paymentStatus === 'CUSTOMER_CONFIRMED' && !depositBypassesNotice(ticket)) add('ISSUE_DEPOSIT_NOTICE', 'doc', 'ออกใบแจ้งมัดจำ');
        if (['account', 'ceo'].includes(user.role) && ticket.paymentStatus === 'DEPOSIT_NOTICE_ISSUED') add('DEPOSIT_PAID', 'payment', 'รับมัดจำ');
        const paymentFields = derivePaymentFields(ticket);
        if (['account', 'ceo'].includes(user.role) && paymentFields.amountPayable > 0 && paymentFields.paymentStage !== 'FULLY_PAID') {
          add('RECORD_PAYMENT', 'payment', 'บันทึกรับชำระเงิน', { requiredFields: ['kind', 'amount'] });
        }
        if (['account', 'ceo'].includes(user.role)) add('SET_BILLING', 'payment', 'ตั้งค่าการวางบิล', { requiredFields: ['dueDate'] });
        if (['import', 'ceo'].includes(user.role) && canIssueIr) add('ISSUE_IMPORT_REQUEST', 'fulfillment', 'ออก IR');
        if (['import', 'ceo'].includes(user.role) && ticket.fulfillmentStatus === 'IR_ISSUED') add('IR_SENT', 'fulfillment', 'ส่ง IR');
        if (['import', 'ceo'].includes(user.role) && ticket.fulfillmentStatus === 'IR_SENT') add('SHIPPING', 'fulfillment', 'สินค้าเดินทาง');
        if (['import', 'ceo'].includes(user.role) && ticket.fulfillmentStatus === 'SHIPPING') add('GOODS_RECEIVED', 'fulfillment', 'รับสินค้า');
        if (['import', 'ceo'].includes(user.role) && (ticket.items ?? []).length > 0 && hasRemainingDelivery(ticket)
            && ticket.fulfillmentStatus !== 'FULLY_DELIVERED') {
          add('RESERVE_STOCK', 'fulfillment', 'จองสินค้าจากสต็อก', { requiredFields: ['lines'] });
        }
        if (['import', 'ceo'].includes(user.role) && hasRemainingDelivery(ticket) && deliveryAvailable(ticket)) {
          add('RECORD_PARTIAL_DELIVERY', 'fulfillment', 'บันทึกการส่งสินค้า', { requiredFields: ['source', 'lines'] });
          add('COMPLETE_DELIVERY', 'fulfillment', 'ส่งมอบครบ');
        }
        const finalPaymentAllowed = ['AWAITING_FINAL_PAYMENT', 'DEPOSIT_PAID'].includes(ticket.paymentStatus)
          || (depositBypassesNotice(ticket) && (ticket.paymentStatus == null || ticket.paymentStatus === 'CUSTOMER_CONFIRMED'));
        if (['account', 'ceo'].includes(user.role) && finalPaymentAllowed) add('FINAL_PAYMENT', 'payment', 'รับเงินครบ');
        // Three-party close: account confirms, CEO verifies. Sales is not involved.
        let closeReady = true;
        try { requireClosePrerequisites(ticket); } catch { closeReady = false; }
        if (user.role === 'account' && !ticket.closeConfirmedAt && closeReady) {
          add('CONFIRM_CLOSE', 'operational', 'ยืนยันพร้อมปิดงาน');
        }
        if (['account', 'ceo'].includes(user.role) && ticket.closeConfirmedAt) {
          add('REVOKE_CLOSE_CONFIRM', 'operational', 'ยกเลิกการยืนยันปิดงาน');
        }
        if (user.role === 'ceo' && ticket.closeConfirmedAt && closeReady) {
          add('VERIFY_CLOSE', 'operational', 'ตรวจสอบและปิดงาน');
        }
        if (ticket.createdById === user.id && !['closed', 'cancelled'].includes(ticket.status)) add('CANCEL', 'operational', 'ยกเลิก');
        if (owner && ['draft', 'submitted', 'in_review', 'price_proposed'].includes(ticket.status)) add('EDIT_ITEMS', 'operational', 'แก้ไขรายการ');
        for (const stage of ['LEAD_APPROACH','PRESENTATION','SPEC_APPROVED','QUOTE_DESIGN_SIDE','OWNER_SIGNOFF','AWAITING_BUYER','QUOTE_BUYER','NEGOTIATION','ORDER_RECEIVED','DEPOSIT_RECEIVED','PROCUREMENT','DELIVERY_SCHEDULING','DELIVERED','CLOSED_PAID']) {
          if (stage !== ticket.salesStage && dealCanSetStage(user, ticket, stage)) add('ADVANCE_STAGE', 'stage', 'เลื่อนสถานะ', { targetStage: stage });
        }
        if (availableActions.some((a) => a.kind === 'stage')) add('UPDATE_STAGE', 'stage', 'แก้ไขสถานะ', { requiredFields: ['stage'] });
        if (dealOwner) {
          add('MARK_LOST', 'lifecycle', 'เสียงาน', { requiredFields: ['reason'] });
          add('PLACE_ON_HOLD', 'lifecycle', 'พักดีลไว้');
          add('MARK_DORMANT', 'lifecycle', 'พัก dormant');
          add('SET_TENDER_REQUIREMENT', 'policy', 'ตั้งค่าสถานะประมูล', { requiredFields: ['value'] });
          add('SET_ENTRY_CHANNEL', 'policy', 'ตั้งค่า entry channel', { requiredFields: ['value'] });
        }
        if (['account', 'ceo'].includes(user.role)) add('WAIVE_DEPOSIT', 'policy', 'นโยบายมัดจำ', { requiredFields: ['policy', 'reason'] });
      } else if (['ON_HOLD', 'DORMANT'].includes(ticket.lifecycle) && dealOwner) {
        add('RESUME', 'lifecycle', 'ดำเนินการต่อ');
        if (ticket.lifecycle === 'ON_HOLD') add('MARK_DORMANT', 'lifecycle', 'พัก dormant');
      } else if (ticket.lifecycle === 'CLOSED_LOST' && dealOwner) {
        add('REOPEN', 'lifecycle', 'เปิดดีลใหม่');
      }
      return delay({
        currentState: {
          lifecycle: ticket.lifecycle ?? 'ACTIVE',
          salesStage: ticket.salesStage,
          paymentStatus: ticket.paymentStatus ?? null,
          fulfillmentStatus: ticket.fulfillmentStatus ?? null,
          status: ticket.status,
        },
        availableActions,
      });
    },

    async create(payload) {
      const user = hasRole('sales');
      // Mirrors TicketService.create (V50): every new deal belongs to a โครงการ.
      if (payload.projectId == null) fail('ต้องเลือกโครงการก่อนสร้างดีล', 400);
      const nextId = Math.max(...db.tickets.map((t) => t.id)) + 1;
      const code = `PR-2026-${String(nextId).padStart(4, '0')}`;
      const now = new Date().toISOString();
      // Every deal begins as a DRAFT at the lead stage, regardless of whether
      // products were attached at creation time — pricing no longer starts at
      // ticket creation (commit 5). Items attached here are preliminary deal
      // products only; nothing reaches Import (no notification, no status
      // change) until a PricingRequest is created and submitted separately.
      const ticket = {
        id: nextId, code, type: 'PRICE_REQUEST',
        title: payload.title, status: 'draft',
        priority: payload.priority || 'NORMAL',
        createdById: user.id, createdByName: user.name,
        assignedToId: null, assignedToName: null,
        customerName: payload.customerName || null,
        customerId: payload.customerId ?? null,
        projectId: payload.projectId ?? null,
        contactId: payload.contactId ?? null,
        note: payload.note || null,
        salesStage: 'LEAD_APPROACH', lostReason: null, lostAt: null, stageUpdatedAt: now,
        lifecycle: 'ACTIVE',
        tenderRequirement: 'UNKNOWN',
        depositPolicy: 'REQUIRED',
        depositPolicyReason: null,
        entryChannel: payload.entryChannel || 'DESIGNER_LED',
        createdAt: now.slice(0, 10), updatedAt: now.slice(0, 10), closedAt: null,
        items: (payload.items || []).map((item, i) => ({
          id: nextId * 100 + i, ticketId: nextId,
          brand: item.brand, model: item.model,
          color: item.color, texture: item.texture, size: item.size,
          factory: item.factory || null,
          qty: item.qty, qtySqm: item.qtySqm ?? null,
          proposedPrice: null, approvedPrice: null,
          currency: item.currency || 'THB', sortOrder: i,
        })),
        events: [{ id: nextId * 1000, ticketId: nextId, actorId: user.id, actorName: user.name, kind: 'CREATED', fromStatus: null, toStatus: 'draft', message: null, createdAt: now }],
        quotation: null,
      };
      db.tickets.unshift(ticket);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    /**
     * Deprecated: ticket-level price-request submission has been replaced by
     * the PricingRequest aggregate (commit 5/6). 409s unconditionally, same as
     * TicketService.submit — create a pricing request via
     * api.pricingRequests.create + .submit instead.
     */
    async submit() {
      // Mirrors TicketService.submit exactly: 409s unconditionally, regardless
      // of status, role, or ownership — there is no more role-specific denial.
      requireSession();
      fail('การส่งขอราคาย้ายไปอยู่ที่ใบขอราคา (PCR) แล้ว — กรุณาสร้างใบขอราคาจากหน้าดีลแทน', 409);
    },

    async pickup(id) {
      const user = hasRole('import');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      verifyStatus(ticket, 'submitted');
      ticket.status = 'in_review';
      ticket.assignedToId = user.id;
      ticket.assignedToName = user.name;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'PICKED_UP', 'submitted', 'in_review', null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async proposePrice(id, payload) {
      const user = hasRole('import');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      verifyStatus(ticket, 'in_review');
      ticket.items = (payload.items || []).map((item, i) => ({
        ...ticket.items[i], ...item,
        id: ticket.items[i]?.id ?? ticket.id * 100 + i,
        ticketId: ticket.id, sortOrder: i,
      }));
      ticket.status = 'price_proposed';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      const snap = JSON.stringify((payload.items || []).map((it) => ({
        brand: it.brand, model: it.model, qty: it.qty,
        rawPrice: it.rawPrice, rawCurrency: it.rawCurrency, rawUnit: it.rawUnit,
      })));
      pushEvent(ticket, user, 'PRICE_PROPOSED', 'in_review', 'price_proposed', payload.note || null, snap);
      addNotification(8, ticket.id, ticket.code, 'PRICE_PROPOSED', `Ticket ${ticket.code} มีราคาเสนอรอการอนุมัติ`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async editItems(id, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      const st = ticket.status;
      const isOwner = user.id === ticket.createdById;
      // 'draft' included since V50: a lightweight lead-stage deal gets its product
      // items here before submit().
      const salesCanEdit = user.role === 'sales' && isOwner
        && ['draft', 'submitted', 'in_review', 'price_proposed'].includes(st);
      const importCanEdit = user.role === 'import'
        && ['in_review', 'price_proposed'].includes(st);
      if (!salesCanEdit && !importCanEdit) fail('ไม่มีสิทธิ์แก้ไขรายการสินค้าในสถานะนี้', 403);
      // Mirrors TicketService.editItems: sales/import editing descriptive fields must
      // never silently overwrite import's proposed price or CEO's approved/manual price —
      // pricing fields always come from the existing item at this position, never the
      // request (2026-07-16 pricing-integrity audit, finding #4). Only proposePrice is
      // allowed to replace proposedPrice wholesale.
      ticket.items = (payload.items || []).map((item, i) => ({
        ...ticket.items[i],
        brand: item.brand, model: item.model,
        color: item.color, texture: item.texture, size: item.size,
        factory: item.factory ?? ticket.items[i]?.factory ?? null,
        qty: item.qty, qtySqm: item.qtySqm ?? ticket.items[i]?.qtySqm ?? null,
        rawPrice: item.rawPrice ?? ticket.items[i]?.rawPrice ?? null,
        rawCurrency: item.rawCurrency ?? ticket.items[i]?.rawCurrency ?? null,
        rawUnit: item.rawUnit ?? ticket.items[i]?.rawUnit ?? null,
        proposedPrice: ticket.items[i]?.proposedPrice ?? null,
        id: ticket.items[i]?.id ?? ticket.id * 100 + i,
        ticketId: ticket.id, sortOrder: i,
      }));
      ticket.hasEdits = true;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'EDITED', st, st, payload.note || null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async calculatePrices(id) {
      hasRole('ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      verifyStatus(ticket, 'price_proposed');

      const fcMap = {};
      mockFactoryConfigs.forEach((fc) => { fcMap[fc.factoryName] = fc; });
      const fxMap = {};
      mockFxRates.forEach((fx) => { fxMap[fx.currency] = fx.rateToThb; });
      const cfgMap = {};
      mockPriceCalcConfigs.filter((c) => c.isCurrent).forEach((c) => { cfgMap[c.country] = c; });

      ticket.items = ticket.items.map((item) => {
        if (item.rawPrice == null) return item;
        const fc = fcMap[item.factory] ?? { country: 'Thailand' };
        const cfg = cfgMap[fc.country] ?? cfgMap['Thailand'] ?? { freightPerSqm: 0, insurancePerSqm: 0, inlandFactoryToPortPerSqm: 0, inlandPortToWarehousePerSqm: 50, importDutyPct: 0, marginPct: 0.2, version: 1 };
        // Mirrors PriceCalcService.resolveFxRate: THB never needs a lookup; any other
        // currency with no fx_rates row must fail loudly, not silently cost at 1:1 THB
        // (2026-07-16 pricing-integrity audit, finding #1).
        const rawCurrency = item.rawCurrency ?? 'THB';
        let fxRate;
        if (rawCurrency === 'THB') {
          fxRate = 1;
        } else if (fxMap[rawCurrency] != null) {
          fxRate = fxMap[rawCurrency];
        } else {
          fail(`ไม่พบอัตราแลกเปลี่ยนสำหรับสกุลเงิน ${rawCurrency} — กรุณาตั้งค่าใน CEO Settings`, 422);
        }

        const sqmPerPiece = (item.qtySqm && item.qty && item.qty > 0) ? item.qtySqm / item.qty : 1;

        let goodsCostPerSqm;
        if (item.rawUnit === 'sqm') {
          goodsCostPerSqm = item.rawPrice * fxRate;
        } else {
          goodsCostPerSqm = sqmPerPiece > 0 ? (item.rawPrice * fxRate / sqmPerPiece) : item.rawPrice * fxRate;
        }

        const cifPerSqm = goodsCostPerSqm + cfg.freightPerSqm + cfg.insurancePerSqm;
        const dutyPerSqm = cifPerSqm * cfg.importDutyPct;
        const landedPerSqm = cifPerSqm + dutyPerSqm + cfg.inlandFactoryToPortPerSqm + cfg.inlandPortToWarehousePerSqm;
        const sellPerSqm = landedPerSqm * (1 + cfg.marginPct);

        const calcedCost  = Math.round(landedPerSqm * sqmPerPiece * 10000) / 10000;
        const calcedPrice = Math.round(sellPerSqm  * sqmPerPiece * 100)   / 100;

        item._breakdown = {
          itemId: item.id,
          brand: item.brand, model: item.model, factory: item.factory,
          rawCurrency: item.rawCurrency, fxRate, sqmPerPiece,
          goodsCostPerSqm: Math.round(goodsCostPerSqm * 10000) / 10000,
          freightPerSqm: cfg.freightPerSqm, insurancePerSqm: cfg.insurancePerSqm,
          cifPerSqm: Math.round(cifPerSqm * 10000) / 10000,
          importDutyPerSqm: Math.round(dutyPerSqm * 10000) / 10000,
          inlandPerSqm: Math.round((cfg.inlandFactoryToPortPerSqm + cfg.inlandPortToWarehousePerSqm) * 10000) / 10000,
          landedCostPerSqm: Math.round(landedPerSqm * 10000) / 10000,
          marginPct: cfg.marginPct,
          sellPricePerSqm: Math.round(sellPerSqm * 10000) / 10000,
          calcedCostPerPiece: calcedCost,
          calcedPricePerPiece: calcedPrice,
          configVersion: cfg.version,
        };
        // Mirrors PriceCalcService.calculateForTicket: a CEO manual price override must
        // survive recalculation — calcedCost/calcedPrice always reflect the fresh
        // calculation, but proposedPrice stays pinned to the override (2026-07-16
        // pricing-integrity audit, finding #2).
        const proposedPrice = item.manualPrice != null ? item.manualPrice : calcedPrice;
        return { ...item, calcedCost, calcedPrice, calcConfigVersion: cfg.version, proposedPrice };
      });

      const breakdown = ticket.items.filter((it) => it._breakdown).map((it) => {
        const b = it._breakdown;
        delete it._breakdown;
        return b;
      });

      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      return delay({ ticket: buildTicketDetail(ticket), breakdown });
    },

    async overrideItemPrice(ticketId, itemId, payload) {
      const user = hasRole('ceo');
      const ticket = findTicketRaw(Number(ticketId));
      requireActive(ticket);
      verifyStatus(ticket, 'price_proposed');
      const item = ticket.items.find((it) => it.id === Number(itemId));
      if (!item) throw new Error('Item not found');
      item.manualPrice = payload.manualPrice;
      item.manualOverrideReason = payload.reason ?? null;
      item.proposedPrice = payload.manualPrice;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      // Mirrors TicketService.overrideItemPrice: audit trail for CEO manual price
      // overrides (2026-07-16 pricing-integrity audit, finding #3 — previously logged
      // no ticket event at all).
      const note = `Item #${itemId}: ราคา manual override = ${payload.manualPrice}`
        + (payload.reason ? ` — เหตุผล: ${payload.reason}` : '');
      pushEvent(ticket, user, 'PRICE_OVERRIDDEN', ticket.status, ticket.status, note);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async approve(id) {
      const user = hasRole('ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      verifyStatus(ticket, 'price_proposed');
      ticket.items = ticket.items.map((item) => ({ ...item, approvedPrice: item.proposedPrice }));
      ticket.hasEdits = false;
      ticket.status = 'approved';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'APPROVED', 'price_proposed', 'approved', null);
      addNotification(ticket.createdById, ticket.id, ticket.code, 'APPROVED', `Ticket ${ticket.code} ได้รับการอนุมัติราคาแล้ว — กด Generate ใบเสนอราคาได้เลย`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async reject(id, payload) {
      const user = hasRole('ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      verifyStatus(ticket, 'price_proposed');
      ticket.status = 'in_review';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'REJECTED', 'price_proposed', 'in_review', payload.reason);
      addNotification(ticket.assignedToId || 7, ticket.id, ticket.code, 'REJECTED', `Ticket ${ticket.code} ถูกตีกลับ — กรุณาแก้ไขราคาเสนอ`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async quotation(id, payload = {}) {
      const user = hasRole('sales');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (ticket.status !== 'approved' && ticket.status !== 'quotation_issued') {
        fail(`Expected status 'approved' or 'quotation_issued' but ticket is '${ticket.status}'`, 409);
      }
      const fromStatus = ticket.status;
      if (ticket.createdById !== user.id) fail('Forbidden', 403);
      const recipientType = (payload.recipientType || '').trim();
      if (!['DESIGNER', 'OWNER', 'BUYER'].includes(recipientType)) fail(`Unknown quotation recipient '${recipientType}'`, 400);
      if (!ticket.quotations) ticket.quotations = ticket.quotation ? [normalizeQuotation(ticket.quotation, ticket, 0)] : [];
      const chain = ticket.quotations.filter((q) => q.recipientType === recipientType);
      const acceptedInChain = chain.some((q) => q.docStatus === 'ACCEPTED');
      if ((acceptedInChain || ticket.paymentStatus != null) && !(payload.amendmentReason || '').trim()) {
        fail('ต้องระบุเหตุผลการแก้ไขใบเสนอราคาหลังลูกค้ายืนยันหรือมีใบที่ accepted แล้ว', 400);
      }
      const total = ticket.items.reduce((sum, item) => sum + (item.approvedPrice || 0) * item.qty, 0);

      // Supersede only the selected recipient chain; accepted/rejected/cancelled rows stay as history.
      ticket.quotations.forEach((q) => {
        if (q.recipientType === recipientType && !['SUPERSEDED', 'ACCEPTED', 'REJECTED', 'CANCELLED'].includes(q.docStatus)) {
          q.docStatus = 'SUPERSEDED';
        }
      });

      const nextVersion = Math.max(0, ...chain.map((q) => q.quotationVersion || 0)) + 1;
      const allQuotationIds = db.tickets.flatMap((t) => t.quotations ?? (t.quotation ? [t.quotation] : [])).map((q) => q.id || 0);
      const nextQNum = Math.max(0, ...allQuotationIds) + 1;
      const parent = chain.slice().sort((a, b) => (b.quotationVersion || 0) - (a.quotationVersion || 0))[0] ?? null;

      // Freeze this quotation at issue time (mirrors TicketService.generateQuotation +
      // V49's quotation_item/customer-header columns): deep-copy the priced items and the
      // customer/project header NOW, so a later ticket edit or customer-record change can
      // never alter this quotation's downloaded content. customerName/address/taxId/phone
      // come from the linked customer record (mockCustomers), not the ticket's own
      // (possibly stale) customerName field — same source-of-truth choice as the backend.
      const customer = ticket.customerId ? mockCustomers.find((c) => c.id === ticket.customerId) : null;
      const project = ticket.projectId ? mockProjects.find((p) => p.id === ticket.projectId) : null;
      const priceItems = ticket.items.filter((it) => it.approvedPrice != null);
      const newQuotation = {
        id: nextQNum, ticketId: ticket.id,
        number: `QT-2026-${String(nextQNum).padStart(4, '0')}`,
        issuedById: user.id, issuedByName: user.name,
        issuedAt: new Date().toISOString(), pdfPath: null,
        totalAmount: total, currency: 'THB',
        quotationVersion: nextVersion, docStatus: 'ISSUED',
        recipientType,
        recipientLabel: payload.recipientLabel || null,
        paymentTerms: payload.paymentTerms || null,
        leadTime: payload.leadTime || null,
        deliveryTerms: payload.deliveryTerms || null,
        validityDate: payload.validityDate || null,
        sentAt: null,
        acceptedAt: null,
        rejectedAt: null,
        parentQuotationId: parent ? parent.id : null,
        // Snapshot (V49) — undefined/empty on any quotation object built before this change.
        items: priceItems.map((it) => ({ ...it })),
        // Fidelity rule (mirrors TicketService.generateQuotation): freeze the TICKET's
        // display name — that's what the renderer prints — master name only as fallback.
        customerName: ticket.customerName ?? (customer ? customer.name : null),
        customerAddress: customer ? customer.address : null,
        customerTaxId: customer ? customer.taxId : null,
        customerPhone: customer ? customer.phone : null,
        projectName: project ? project.name : null,
      };
      ticket.quotations.unshift(newQuotation); // newest first
      ticket.quotation = newQuotation; // backward compat

      ticket.status = 'quotation_issued';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      const message = `recipient_type=${recipientType}, version=${nextVersion}`
        + ((payload.amendmentReason || '').trim() ? ` — amendment: ${payload.amendmentReason.trim()}` : '');
      pushEvent(ticket, user, 'QUOTATION_ISSUED', fromStatus, 'quotation_issued', message);
      if (['DESIGNER', 'OWNER'].includes(recipientType)) autoAdvanceStage(ticket, 'QUOTE_DESIGN_SIDE', user);
      if (recipientType === 'BUYER') autoAdvanceStage(ticket, 'QUOTE_BUYER', user);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async markQuotationSent(id, quotationId, payload = {}) {
      return markQuotationStatus(id, quotationId, 'SENT', 'QUOTATION_SENT', payload);
    },

    async markQuotationAccepted(id, quotationId, payload = {}) {
      return markQuotationStatus(id, quotationId, 'ACCEPTED', 'QUOTATION_ACCEPTED', payload);
    },

    async markQuotationRejected(id, quotationId, payload = {}) {
      return markQuotationStatus(id, quotationId, 'REJECTED', 'QUOTATION_REJECTED', payload);
    },

    async downloadQuotationXlsx(ticketId, quotationId) {
      return buildMockQuotationXlsx(ticketId, quotationId);
    },

    async downloadQuotationPdf(ticketId, quotationId) {
      return buildMockQuotationHtml(ticketId, quotationId);
    },

    // Three-party close (V55). Mirrors TicketService.confirmCloseReady /
    // revokeCloseConfirmation / verifyClose. Sales is not part of the sequence.
    async confirmCloseReady(id) {
      const user = requireSession();
      hasRole('account'); // NOT ceo — the CEO signs the second half
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (ticket.closeConfirmedAt) fail('ยืนยันปิดงานไปแล้ว — รอ CEO ตรวจสอบ', 409);
      requireClosePrerequisites(ticket);
      ticket.closeConfirmedAt = new Date().toISOString();
      ticket.closeConfirmedByName = user.name;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'CLOSE_CONFIRMED', ticket.status, ticket.status,
        'ฝ่ายบัญชียืนยันพร้อมปิดงาน — รอ CEO ตรวจสอบ');
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async revokeCloseConfirmation(id, payload = {}) {
      const user = requireSession();
      hasRole('account', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (!ticket.closeConfirmedAt) fail('ดีลนี้ยังไม่ได้ยืนยันปิดงาน', 409);
      ticket.closeConfirmedAt = null;
      ticket.closeConfirmedByName = null;
      pushEvent(ticket, user, 'CLOSE_CONFIRM_REVOKED', ticket.status, ticket.status,
        (payload.note || '').trim() || null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async verifyClose(id) {
      const user = requireSession();
      hasRole('ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (!ticket.closeConfirmedAt) fail('ปิดงานไม่ได้: ต้องให้ฝ่ายบัญชียืนยันก่อน', 409);
      // Re-checked here too: the CEO verifies, never overrides.
      requireClosePrerequisites(ticket);
      const prev = ticket.status;
      ticket.status = 'closed';
      ticket.closedAt = new Date().toISOString().slice(0, 10);
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'CLOSED', prev, 'closed', 'CEO ตรวจสอบและปิดงาน');
      ticket.lifecycle = 'COMPLETED';
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    // Mirrors TicketService.cancel. The reason is mandatory (V56) — a cancelled
    // deal used to carry no explanation at all, unlike its CLOSED_LOST sibling.
    async cancel(id, payload = {}) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!DEAL_CANCEL_REASONS.some((r) => r.code === payload.reason)) {
        fail(`Unknown cancel reason '${payload.reason}'`, 400);
      }
      if (ticket.status === 'closed' || ticket.status === 'cancelled') fail('Cannot cancel', 409);
      // Ownership gate — the Java service has always had this; the mock did not,
      // which made it MORE permissive than production (the dangerous direction).
      if (ticket.createdById !== user.id) fail('Forbidden', 403);
      const prev = ticket.status;
      ticket.status = 'cancelled';
      ticket.lifecycle = 'CANCELLED';
      ticket.cancelReason = payload.reason;
      ticket.cancelledAt = new Date().toISOString();
      ticket.closedAt = new Date().toISOString().slice(0, 10);
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      const note = (payload.note || '').trim();
      pushEvent(ticket, user, 'CANCELLED', prev, 'cancelled',
        note ? `ยกเลิกดีล (${payload.reason}) — ${note}` : `ยกเลิกดีล (${payload.reason})`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async comment(id, payload) {
      // Mirrors TicketService.comment: same read gate as get() — commenting
      // returns the full ticket.
      const { user, ticket } = requireTicketViewer(id);
      pushEvent(ticket, user, 'COMMENTED', null, null, payload.message);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async createDocDraft(ticketId, payload) {
      // delegate to depositNotices.createDraft (defined below — works at call time)
      return api.depositNotices.createDraft(ticketId, payload);
    },

    async listDocs(ticketId) {
      return api.depositNotices.listByTicket(ticketId);
    },

    async revision(id, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      // Phase 1 lifecycle gate (mirrors DepositNoticeService.requestRevision).
      requireActive(ticket);
      if (!['approved', 'document_issued'].includes(ticket.status)) fail('ไม่สามารถขอแก้ไขในสถานะนี้', 409);

      const toStatus = {
        QTY_OR_NOTE:  'approved',
        PRICE_CHANGE: 'price_proposed',
        NEW_ITEM:     'in_review',
      }[payload.scope] ?? ticket.status;

      ticket.status = toStatus;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'REVISION_REQUESTED', ticket.status, toStatus, `[${payload.scope}] ${payload.reason}`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    // ── Dual-track post-quotation (ข้อ 13) ──────────────────────────────────

    async downloadRemainingInvoice(id) {
      // Mirrors DepositNoticeService.getRemainingInvoiceXlsx: read gate first.
      const { ticket } = requireTicketViewer(id);
      if (ticket.status !== 'quotation_issued') fail('Expected quotation_issued', 409);
      return buildMockRemainingInvoiceXlsx(Number(id));
    },

    async confirmCustomer(id) {
      const user = hasRole('sales');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      // Mirrors TicketService.confirmCustomer: owner-only.
      if (ticket.createdById !== user.id) fail('Forbidden', 403);
      if (ticket.status !== 'quotation_issued') fail('Expected quotation_issued', 409);
      // Never downgrade the payment track (mirrors TicketService.confirmCustomer).
      if (ticket.paymentStatus != null && ticket.paymentStatus !== 'CUSTOMER_CONFIRMED') {
        fail('Payment track already past CUSTOMER_CONFIRMED', 409);
      }
      ticket.paymentStatus = 'CUSTOMER_CONFIRMED';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'CUSTOMER_CONFIRMED', ticket.status, ticket.status, null);
      autoAdvanceStage(ticket, 'ORDER_RECEIVED', user);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    // (issueDepositNotice removed — depositNotices.issue is now the single action
    //  that advances the payment track to DEPOSIT_NOTICE_ISSUED.)

    async confirmDepositPaid(id) {
      // Money receipts are confirmed by ฝ่ายบัญชี (CEO fallback) — mirrors
      // TicketService.ACCOUNT_ROLES.
      const user = hasRole('account', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (ticket.paymentStatus !== 'DEPOSIT_NOTICE_ISSUED') fail('Expected paymentStatus=DEPOSIT_NOTICE_ISSUED', 409);
      const notice = latestIssuedDepositNotice(ticket.id);
      const amount = notice?.depositAmount ?? moneyValue(payableAmount(ticket) * 0.5);
      if (amount <= 0) fail('ไม่พบยอดมัดจำสำหรับบันทึกรับชำระ', 409);
      recordPaymentForTicket(ticket, user, {
        kind: 'DEPOSIT',
        amount,
        note: 'ยืนยันรับมัดจำ',
        depositNoticeId: notice?.id ?? null,
      });
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async issueImportRequest(id) {
      const user = hasRole('import');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      // DEPOSIT_PAID also qualifies — the deposit is often confirmed before the IR
      // (mirrors TicketService.issueImportRequest).
      const waivedReady = depositBypassesNotice(ticket)
        && (ticket.paymentStatus == null || ticket.paymentStatus === 'CUSTOMER_CONFIRMED');
      if (ticket.status !== 'quotation_issued'
          || (!['DEPOSIT_NOTICE_ISSUED', 'DEPOSIT_PAID'].includes(ticket.paymentStatus) && !waivedReady)) {
        fail('Requires quotation_issued + paymentStatus=DEPOSIT_NOTICE_ISSUED or DEPOSIT_PAID', 409);
      }
      if (ticket.fulfillmentStatus != null) fail('Import request already issued', 409);
      ticket.fulfillmentStatus = 'IR_ISSUED';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'IR_ISSUED', ticket.status, ticket.status, null);
      autoAdvanceStage(ticket, 'PROCUREMENT', user);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async markIrSent(id) {
      const user = hasRole('import');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (ticket.fulfillmentStatus !== 'IR_ISSUED') fail('Expected fulfillmentStatus=IR_ISSUED', 409);
      ticket.fulfillmentStatus = 'IR_SENT';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'IR_SENT', ticket.status, ticket.status, null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async markShipping(id) {
      const user = hasRole('import');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (ticket.fulfillmentStatus !== 'IR_SENT') fail('Expected fulfillmentStatus=IR_SENT', 409);
      ticket.fulfillmentStatus = 'SHIPPING';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'SHIPPING', ticket.status, ticket.status, null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async markGoodsReceived(id) {
      const user = hasRole('import');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (ticket.fulfillmentStatus !== 'SHIPPING') fail('Expected fulfillmentStatus=SHIPPING', 409);
      ticket.fulfillmentStatus = 'GOODS_RECEIVED';
      if (ticket.paymentStatus === 'DEPOSIT_PAID') {
        ticket.paymentStatus = 'AWAITING_FINAL_PAYMENT';
        pushEvent(ticket, user, 'AWAITING_FINAL_PAYMENT', ticket.status, ticket.status, null);
      }
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'GOODS_RECEIVED', ticket.status, ticket.status, null);
      // Goods are at the warehouse (S17) — advance to DELIVERY_SCHEDULING (S18) so
      // the "schedule delivery / collect balance" step is reached before DELIVERED.
      autoAdvanceStage(ticket, 'DELIVERY_SCHEDULING', user);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async confirmFinalPayment(id) {
      // Mirrors TicketService.ACCOUNT_ROLES (ฝ่ายบัญชี + CEO fallback).
      const user = hasRole('account', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      const allowed = ['AWAITING_FINAL_PAYMENT', 'DEPOSIT_PAID'].includes(ticket.paymentStatus)
        || (depositBypassesNotice(ticket) && (ticket.paymentStatus == null || ticket.paymentStatus === 'CUSTOMER_CONFIRMED'));
      if (!allowed) fail('Expected paymentStatus=DEPOSIT_PAID/AWAITING_FINAL_PAYMENT or a waived deposit policy', 409);
      const outstanding = moneyValue(payableAmount(ticket) - sumPaid(ticket.id));
      if (outstanding <= 0) {
        if (ticket.paymentStatus !== 'FULLY_PAID') {
          ticket.paymentStatus = 'FULLY_PAID';
          ticket.updatedAt = new Date().toISOString().slice(0, 10);
          pushEvent(ticket, user, 'FULLY_PAID', ticket.status, ticket.status, null);
          maybeAdvanceClosedPaid(ticket, user);
        }
      } else {
        recordPaymentForTicket(ticket, user, {
          kind: 'BALANCE',
          amount: outstanding,
          note: 'ยืนยันชำระส่วนที่เหลือ',
        });
      }
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    // ── Deal pipeline (V50): mirrors TicketService.updateStage/markLost/reopenDeal.
    // Gates come from stageMeta (shared with the pages); authz here approximates
    // the Java service and is NOT authoritative (CLAUDE.md).

    async updateStage(id, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (dealStageIndex(payload.stage) < 0) fail(`Unknown stage '${payload.stage}'`, 400);
      if (!dealCanSetStage(user, ticket, payload.stage)) fail('Forbidden', 403);
      // Lifecycle, not lostReason: a reopened deal is ACTIVE and keeps its reason (V57).
      if (ticket.lifecycle === 'CLOSED_LOST') fail('ดีลถูกทำเครื่องหมายเสียงานแล้ว — เปิดดีลใหม่ก่อนแก้ไขสถานะ', 409);
      if (ticket.salesStage === payload.stage) fail(`Deal is already in stage ${payload.stage}`, 409);
      const backward = dealStageIndex(payload.stage) < dealStageIndex(ticket.salesStage)
        && !isRoutineBackwardMove(ticket.salesStage, payload.stage);
      const skipForward = dealStageIndex(payload.stage) - dealStageIndex(ticket.salesStage) > 1;
      if (backward && !(payload.note || '').trim()) fail('การย้อนสถานะกลับต้องระบุเหตุผล', 400);
      if (skipForward && !(payload.note || '').trim()) fail('การข้ามขั้นตอนต้องระบุเหตุผล', 400);
      const fromStage = ticket.salesStage;
      ticket.salesStage = payload.stage;
      ticket.stageUpdatedAt = new Date().toISOString();
      pushEvent(ticket, user, 'STAGE_CHANGED', fromStage, payload.stage, (payload.note || '').trim() || null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async markLost(id, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (!DEAL_LOST_REASONS.some((r) => r.code === payload.reason)) fail(`Unknown lost reason '${payload.reason}'`, 400);
      if (!dealCanMarkLost(user, ticket)) fail('Forbidden', 403);
      if (ticket.lifecycle === 'CLOSED_LOST') fail('Deal is already marked lost', 409);
      ticket.lostReason = payload.reason;
      ticket.lostAt = new Date().toISOString();
      ticket.lifecycle = 'CLOSED_LOST';
      ticket.stageUpdatedAt = ticket.lostAt;
      // Stage untouched by design: reopening resumes exactly where the deal was.
      pushEvent(ticket, user, 'MARKED_LOST', ticket.salesStage, ticket.salesStage,
        `เสียงาน (${payload.reason})${(payload.note || '').trim() ? ` — ${payload.note.trim()}` : ''}`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async reopen(id, payload = {}) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!dealCanMarkLost(user, ticket)) fail('Forbidden', 403);
      if (ticket.lifecycle !== 'CLOSED_LOST' || ticket.lostReason == null) fail('Deal is not marked lost', 409);
      // lostReason/lostAt deliberately PRESERVED (V57): erasing them left the row
      // indistinguishable from one never lost, so "why did we lose this before we
      // reopened it" needed parsing Thai free text out of an event message.
      ticket.lifecycle = 'ACTIVE';
      ticket.reopenedAt = new Date().toISOString();
      ticket.reopenCount = (ticket.reopenCount ?? 0) + 1;
      ticket.stageUpdatedAt = new Date().toISOString();
      pushEvent(ticket, user, 'REOPENED', ticket.salesStage, ticket.salesStage, (payload.note || '').trim() || null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async hold(id, payload = {}) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!dealCanMarkLost(user, ticket)) fail('Forbidden', 403);
      requireActive(ticket);
      ticket.lifecycle = 'ON_HOLD';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'ON_HOLD', ticket.salesStage, ticket.salesStage, (payload.note || '').trim() || null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async dormant(id, payload = {}) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!dealCanMarkLost(user, ticket)) fail('Forbidden', 403);
      if (!['ACTIVE', 'ON_HOLD'].includes(ticket.lifecycle ?? 'ACTIVE')) fail('พัก dormant ได้เฉพาะดีลที่ active หรือ on hold', 409);
      ticket.lifecycle = 'DORMANT';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'DORMANT', ticket.salesStage, ticket.salesStage, (payload.note || '').trim() || null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async resume(id, payload = {}) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!dealCanMarkLost(user, ticket)) fail('Forbidden', 403);
      if (!['ON_HOLD', 'DORMANT'].includes(ticket.lifecycle)) fail('ดำเนินการต่อได้เฉพาะดีลที่พักไว้', 409);
      ticket.lifecycle = 'ACTIVE';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'RESUMED', ticket.salesStage, ticket.salesStage, (payload.note || '').trim() || null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async setTenderRequirement(id, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!dealCanMarkLost(user, ticket)) fail('Forbidden', 403);
      requireActive(ticket);
      if (!['REQUIRED', 'NOT_REQUIRED', 'UNKNOWN'].includes(payload.value)) fail(`Unknown tender requirement '${payload.value}'`, 400);
      ticket.tenderRequirement = payload.value;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'POLICY_CHANGED', ticket.salesStage, ticket.salesStage, `tender_requirement → ${payload.value}`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async setEntryChannel(id, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (!dealCanMarkLost(user, ticket)) fail('Forbidden', 403);
      requireActive(ticket);
      if (!['DESIGNER_LED', 'OWNER_DIRECT', 'BUYER_DIRECT'].includes(payload.value)) fail(`Unknown entry channel '${payload.value}'`, 400);
      if (ticket.entryChannel && ticket.entryChannel !== 'DESIGNER_LED' && ticket.entryChannel !== payload.value && !(payload.note || '').trim()) {
        fail('การเปลี่ยน entry channel ต้องระบุเหตุผล', 400);
      }
      ticket.entryChannel = payload.value;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'POLICY_CHANGED', ticket.salesStage, ticket.salesStage,
        `entry_channel → ${payload.value}${(payload.note || '').trim() ? ` — ${payload.note.trim()}` : ''}`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async setDepositPolicy(id, payload) {
      const user = hasRole('account', 'ceo');
      const ticket = findTicketRaw(Number(id));
      requireActive(ticket);
      if (!['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER'].includes(payload.policy)) fail(`Unknown deposit waiver policy '${payload.policy}'`, 400);
      if (!(payload.reason || '').trim()) fail('ต้องระบุเหตุผลนโยบายมัดจำ', 400);
      ticket.depositPolicy = payload.policy;
      ticket.depositPolicyReason = payload.reason.trim();
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'POLICY_CHANGED', ticket.salesStage, ticket.salesStage,
        `deposit_policy → ${payload.policy} — ${payload.reason.trim()}`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },
  },

  // Mirrors LeaveController + LeaveService (leave/).
  leave: {
    // Mirrors LeaveRepository.findEmployeeOptions() — scope is self + stored-FK
    // reports only (reports_to_employee_id). Deliberately NO division term:
    // unlike overtime, a ฝ่าย manager's leave dropdown does not widen to their
    // whole division.
    async employees() {
      const user = requireSession();
      const includeAll = ['hr', 'ceo'].includes(user.role);
      const rows = db.employees
        .filter((employee) => employee.active)
        .filter((employee) => includeAll || employee.id === user.employeeId || managerIdForEmployee(employee) === user.employeeId)
        .map((employee) => ({
          employeeId: employee.id,
          employeeCode: employee.code,
          employeeName: employee.nameTh,
          departmentName: employee.departmentTh,
          self: employee.id === user.employeeId,
          directReport: managerIdForEmployee(employee) === user.employeeId,
        }));
      return delay({ employees: rows });
    },

    async types() {
      requireSession();
      return delay({ leaveTypes: db.leaveTypes });
    },

    async balances(params = {}) {
      const user = requireSession();
      const employeeId = params.employeeId ? Number(params.employeeId) : user.employeeId;
      if (!employeeId) fail('User is not linked to an employee', 400);
      if (!['hr', 'ceo'].includes(user.role) && employeeId !== user.employeeId && !canReviewLeave(user, employeeId)) fail('Forbidden', 403);
      findEmployee(employeeId);
      const year = Number(params.year || new Date().getFullYear());
      return delay({ balances: db.leaveTypes.map((type) => leaveBalance(employeeId, type, year)) });
    },

    async list(params = {}) {
      const user = requireSession();
      let list = db.leaveRequests;
      const includeAll = ['hr', 'ceo'].includes(user.role);
      if (!includeAll) list = list.filter((item) => item.employeeId === user.employeeId || canReviewLeave(user, item.employeeId));
      if (params.employeeId) list = list.filter((item) => item.employeeId === Number(params.employeeId));
      if (params.status) list = list.filter((item) => item.status === params.status);
      if (params.from) list = list.filter((item) => item.endDate >= params.from);
      if (params.to) list = list.filter((item) => item.startDate <= params.to);
      return delay({ requests: list.map(buildLeaveRecord) });
    },

    async create(payload) {
      const user = requireSession();
      const employeeId = payload.employeeId ? Number(payload.employeeId) : user.employeeId;
      if (!employeeId) fail('User is not linked to an employee', 400);
      if (employeeId !== user.employeeId && !canReviewLeave(user, employeeId)) fail('Forbidden', 403);
      const employee = findEmployee(employeeId);
      const leaveType = leaveTypeByCode(payload.leaveTypeCode);
      const totalDays = workingDaysBetween(payload.startDate, payload.endDate);
      const quotaYear = Number(payload.startDate.slice(0, 4));
      const used = leaveUsedDays(employeeId, leaveType.code, quotaYear, ['SUBMITTED', 'APPROVED']);
      const remainingBefore = Math.max(0, leaveType.annualQuotaDays - used);
      const quotaAvailable = remainingBefore >= totalDays;
      const today = new Date().toISOString().slice(0, 10);
      const sevenDaysAhead = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
      const hasAttachment = Boolean(payload.attachmentFile);
      let systemNote = null;
      if (!quotaAvailable) {
        systemNote = `โควตาคงเหลือ ${remainingBefore} วัน ไม่พอสำหรับคำขอ ${totalDays} วัน กรุณาติดต่อ HR เพื่อปรับโควตาหรือดำเนินการลาไม่รับค่าจ้าง`;
      } else if (leaveType.code === 'SICK' && !hasAttachment) {
        systemNote = 'ลาป่วยต้องแนบใบรับรองแพทย์ กรุณาแนบเอกสารหรือติดต่อ HR';
      } else if (leaveType.code !== 'SICK' && payload.startDate < sevenDaysAhead && payload.startDate >= today) {
        systemNote = 'ต้องยื่นคำขอลาล่วงหน้าอย่างน้อย 7 วัน กรุณาติดต่อหัวหน้าหรือ HR หากเป็นเหตุเร่งด่วน';
      }
      const status = systemNote ? 'AUTO_REJECTED' : 'APPROVED';
      const remainingAfter = status === 'APPROVED' ? remainingBefore - totalDays : remainingBefore;
      const id = Math.max(0, ...db.leaveRequests.map((item) => item.id)) + 1;
      const now = new Date().toISOString();
      const request = {
        id,
        employeeId,
        leaveTypeCode: leaveType.code,
        startDate: payload.startDate,
        endDate: payload.endDate,
        totalDays,
        quotaYear,
        reason: payload.reason,
        attachmentId: hasAttachment ? id : null,
        attachmentFileName: payload.attachmentFile?.name || null,
        status,
        quotaRemainingBefore: remainingBefore,
        quotaRemainingAfter: remainingAfter,
        systemNote,
        requestedById: user.employeeId,
        requestedByName: user.name,
        requestedAt: now,
        reviewedById: null,
        reviewedByName: null,
        reviewedAt: null,
        reviewerNote: null,
        cancelledAt: null,
        createdAt: now,
        updatedAt: now,
      };
      request.employeeCode = employee.code;
      request.employeeName = employee.nameTh;
      db.leaveRequests.unshift(request);
      return delay({ request: buildLeaveRecord(request) });
    },

    async approve(id, payload = {}) {
      const user = requireSession();
      const request = db.leaveRequests.find((item) => item.id === Number(id));
      if (!request) fail('Leave request not found', 404);
      if (!canReviewLeave(user, request.employeeId)) fail('Forbidden', 403);
      if (request.status !== 'SUBMITTED') fail('Leave request has already been reviewed', 409);
      const now = new Date().toISOString();
      request.status = 'APPROVED';
      request.reviewedById = user.employeeId;
      request.reviewedByName = user.name;
      request.reviewedAt = now;
      request.reviewerNote = payload.reviewerNote || null;
      request.updatedAt = now;
      return delay({ request: buildLeaveRecord(request) });
    },

    async reject(id, payload = {}) {
      const user = requireSession();
      const request = db.leaveRequests.find((item) => item.id === Number(id));
      if (!request) fail('Leave request not found', 404);
      if (!canReviewLeave(user, request.employeeId)) fail('Forbidden', 403);
      if (request.status !== 'SUBMITTED') fail('Leave request has already been reviewed', 409);
      const now = new Date().toISOString();
      request.status = 'REJECTED';
      request.reviewedById = user.employeeId;
      request.reviewedByName = user.name;
      request.reviewedAt = now;
      request.reviewerNote = payload.reviewerNote || null;
      request.updatedAt = now;
      return delay({ request: buildLeaveRecord(request) });
    },

    async cancel(id, payload = {}) {
      const user = requireSession();
      const request = db.leaveRequests.find((item) => item.id === Number(id));
      if (!request) fail('Leave request not found', 404);
      const approver = canReviewLeave(user, request.employeeId);
      if (!approver && request.employeeId !== user.employeeId) fail('Forbidden', 403);
      if (!approver && request.status !== 'SUBMITTED') fail('Only submitted leave requests can be cancelled by employees', 409);
      if (!['SUBMITTED', 'APPROVED'].includes(request.status)) fail('Only active leave requests can be cancelled', 409);
      const now = new Date().toISOString();
      request.status = 'CANCELLED';
      request.cancelledAt = now;
      request.reviewerNote = payload.reviewerNote || request.reviewerNote;
      request.updatedAt = now;
      return delay({ request: buildLeaveRecord(request) });
    },
  },

  // Mirrors OvertimeController + OvertimeService (overtime/) — see
  // requireManager()/managesEmployee() for the review gate, and
  // submit() -> resolveTargetEmployee() for filing on another employee's behalf.
  // Neither has an hr/admin bypass; use canReviewOvertime(), never canReviewLeave().
  overtime: {
    // Mirrors OvertimeRepository.findEmployeeOptions() — unlike leave, scope AND
    // directReport both add a division term (self + FK reports + same-division,
    // when the actor is a position-derived division manager): a ฝ่าย manager's OT
    // dropdown flags their whole division as ลูกทีม, matching prod.
    async employees() {
      const user = requireSession();
      const includeAll = ['hr', 'ceo'].includes(user.role);
      const isManager = dashboardManager(user);
      const managerDivisionId = isManager ? dashboardDivisionId(user) : null;
      const rows = db.employees
        .filter((employee) => employee.active)
        .filter((employee) => includeAll
          || employee.id === user.employeeId
          || managerIdForEmployee(employee) === user.employeeId
          || (managerDivisionId != null && employee.divisionId === managerDivisionId))
        .map((employee) => {
          const self = employee.id === user.employeeId;
          const directReport = managerIdForEmployee(employee) === user.employeeId
            || (managerDivisionId != null && employee.divisionId === managerDivisionId && !self);
          return {
            employeeId: employee.id,
            employeeCode: employee.code,
            employeeName: employee.nameTh,
            departmentName: employee.departmentTh,
            self,
            directReport,
          };
        });
      return delay({ employees: rows });
    },

    async list(params = {}) {
      const user = requireSession();
      let list = db.overtimeRequests;
      const includeAll = ['hr', 'ceo'].includes(user.role);
      if (!includeAll) list = list.filter((item) => item.employeeId === user.employeeId || canReviewOvertime(user, item.employeeId));
      if (params.employeeId) list = list.filter((item) => item.employeeId === Number(params.employeeId));
      if (params.status) list = list.filter((item) => item.status === params.status);
      if (params.from) list = list.filter((item) => item.workDate >= params.from);
      if (params.to) list = list.filter((item) => item.workDate <= params.to);
      return delay({ requests: list.map(buildOvertimeRecord) });
    },

    async create(payload) {
      const user = requireSession();
      const employeeId = payload.employeeId ? Number(payload.employeeId) : user.employeeId;
      if (!employeeId) fail('User is not linked to an employee', 400);
      // Filing OT on another employee's behalf is manager-only, not HR. Verified
      // against OvertimeService.submit() → resolveTargetEmployee(), which calls the
      // same managesEmployee() helper as requireManager() and has no hr/admin bypass
      // ("Employees can only request their own overtime").
      if (employeeId !== user.employeeId && !canReviewOvertime(user, employeeId)) fail('Forbidden', 403);
      findEmployee(employeeId);
      const plannedMinutes = overtimeMinutesBetween(payload.plannedStartAt, payload.plannedEndAt);
      const id = Math.max(0, ...db.overtimeRequests.map((item) => item.id)) + 1;
      const now = new Date().toISOString();
      const request = {
        id,
        employeeId,
        workDate: payload.workDate,
        plannedStartAt: payload.plannedStartAt,
        plannedEndAt: payload.plannedEndAt,
        plannedMinutes,
        dayType: payload.dayType || 'WORKDAY',
        reason: payload.reason,
        status: 'SUBMITTED',
        actualMinutes: null,
        payableMinutes: null,
        calculationNote: null,
        requestedById: user.employeeId,
        requestedByName: user.name,
        requestedAt: now,
        managerApprovedBy: null,
        managerApprovedAt: null,
        ceoApprovedBy: null,
        ceoApprovedAt: null,
        reviewedById: null,
        reviewedByName: null,
        reviewedAt: null,
        reviewerNote: null,
        cancelledAt: null,
        createdAt: now,
        updatedAt: now,
      };
      db.overtimeRequests.unshift(request);
      return delay({ request: buildOvertimeRecord(request) });
    },

    async approve(id, payload = {}) {
      const user = requireSession();
      const request = db.overtimeRequests.find((item) => item.id === Number(id));
      if (!request) fail('Overtime request not found', 404);
      const now = new Date().toISOString();
      if (request.status === 'SUBMITTED') {
        if (!canReviewOvertime(user, request.employeeId)) fail('Forbidden', 403);
        const multiplier = request.dayType === 'HOLIDAY' ? 3 : 1.5;
        request.status = 'MANAGER_APPROVED';
        request.actualMinutes = request.actualMinutes ?? request.plannedMinutes;
        request.payableMinutes = Math.round(request.actualMinutes * multiplier);
        request.managerApprovedBy = user.employeeId;
        request.managerApprovedAt = now;
        request.reviewedById = user.employeeId;
        request.reviewedByName = user.name;
        request.reviewedAt = now;
        request.reviewerNote = payload.reviewerNote || null;
        request.updatedAt = now;
        return delay({ request: buildOvertimeRecord(request) });
      }
      if (request.status === 'MANAGER_APPROVED') {
        if (user.role !== 'ceo') fail('Only the CEO can approve manager-approved overtime', 403);
        request.status = 'APPROVED';
        request.ceoApprovedBy = user.employeeId;
        request.ceoApprovedAt = now;
        request.reviewedById = user.employeeId;
        request.reviewedByName = user.name;
        request.reviewedAt = now;
        request.reviewerNote = payload.reviewerNote || request.reviewerNote;
        request.updatedAt = now;
        return delay({ request: buildOvertimeRecord(request) });
      }
      fail('Overtime request has already been reviewed', 409);
    },

    async reject(id, payload = {}) {
      const user = requireSession();
      const request = db.overtimeRequests.find((item) => item.id === Number(id));
      if (!request) fail('Overtime request not found', 404);
      const now = new Date().toISOString();
      if (request.status === 'SUBMITTED') {
        if (!canReviewOvertime(user, request.employeeId)) fail('Forbidden', 403);
        request.status = 'REJECTED';
        request.reviewedById = user.employeeId;
        request.reviewedByName = user.name;
        request.reviewedAt = now;
        request.reviewerNote = payload.reviewerNote || null;
        request.updatedAt = now;
        return delay({ request: buildOvertimeRecord(request) });
      }
      if (request.status === 'MANAGER_APPROVED') {
        if (user.role !== 'ceo') fail('Only the CEO can approve manager-approved overtime', 403);
        request.status = 'REJECTED';
        request.reviewedById = user.employeeId;
        request.reviewedByName = user.name;
        request.reviewedAt = now;
        request.reviewerNote = payload.reviewerNote || null;
        request.updatedAt = now;
        return delay({ request: buildOvertimeRecord(request) });
      }
      fail('Overtime request has already been reviewed', 409);
    },

    async cancel(id, payload = {}) {
      const user = requireSession();
      const request = db.overtimeRequests.find((item) => item.id === Number(id));
      if (!request) fail('Overtime request not found', 404);
      const approver = canReviewOvertime(user, request.employeeId);
      if (!approver && request.employeeId !== user.employeeId) fail('Forbidden', 403);
      if (!approver && request.status !== 'SUBMITTED') fail('Only submitted overtime requests can be cancelled by employees', 409);
      if (!['SUBMITTED', 'MANAGER_APPROVED', 'APPROVED'].includes(request.status)) fail('Only active overtime requests can be cancelled', 409);
      const now = new Date().toISOString();
      request.status = 'CANCELLED';
      request.cancelledAt = now;
      request.reviewerNote = payload.reviewerNote || request.reviewerNote;
      request.updatedAt = now;
      return delay({ request: buildOvertimeRecord(request) });
    },
  },

  // Mirrors CommissionController + CommissionService (commission/).
  commissions: {
    async list(params = {}) {
      const user = requireSession();
      if (!['sales', 'sales_manager', 'ceo'].includes(user.role)) fail('Forbidden', 403);
      let list = db.commissions;
      if (user.role === 'sales') list = list.filter((item) => item.salesRepId === user.id);
      if (params.payrollMonth) list = list.filter((item) => commissionMonth(item.payrollMonth) === params.payrollMonth.slice(0, 7));
      return delay({ commissions: list.map(buildCommissionRecord) });
    },

    async create(payload) {
      const user = hasRole('sales', 'sales_manager', 'ceo');
      if (user.role === 'sales' && (Number(payload.transportFee || 0) > 0 || Number(payload.cutFee || 0) > 0 || Number(payload.shortfall || 0) > 0)) {
        fail('Sales cannot edit deduction fields', 403);
      }
      if (!payload.invoiceAttachment) fail('Tax invoice file is required', 400);
      if (db.commissions.some((item) => item.invoiceDetails.invoiceNumber === payload.invoiceNumber)) {
        fail('Invoice number already exists', 409);
      }
      const id = Math.max(0, ...db.commissions.map((item) => item.id)) + 1;
      const salesRepId = user.role === 'sales' ? user.id : Number(payload.salesRepId || user.id);
      const calc = invoiceCalculation({
        ...payload,
        transportFee: user.role === 'sales' ? 0 : payload.transportFee,
        cutFee: user.role === 'sales' ? 0 : payload.cutFee,
        shortfall: user.role === 'sales' ? 0 : payload.shortfall,
      });
      const record = {
        id,
        sourceTicketId: payload.sourceTicketId ?? null,
        salesRepId,
        salesRepName: db.users.find((item) => item.id === salesRepId)?.name || user.name,
        submittedById: user.id,
        kind: 'SALE',
        status: 'SUBMITTED',
        payrollMonth: `${commissionMonth(payload.invoiceDate)}-01`,
        actualReceived: calc.actualReceived,
        commissionableBase: calc.commissionableBase,
        approvedById: null,
        approvedAt: null,
        managerApprovedBy: null,
        managerApprovedByName: null,
        managerApprovedAt: null,
        ceoApprovedBy: null,
        ceoApprovedByName: null,
        ceoApprovedAt: null,
        rejectedById: null,
        rejectedByName: null,
        rejectedAt: null,
        rejectionReason: null,
        cancellationOfId: null,
        cancellationReason: null,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        invoiceDetails: {
          id,
          invoiceNumber: payload.invoiceNumber,
          invoiceDate: payload.invoiceDate,
          grossAmount: Number(payload.grossAmount || 0),
          bankFees: Number(payload.bankFees || 0),
          suspenseVat: Number(payload.suspenseVat || 0),
          transportFee: user.role === 'sales' ? 0 : Number(payload.transportFee || 0),
          cutFee: user.role === 'sales' ? 0 : Number(payload.cutFee || 0),
          shortfall: user.role === 'sales' ? 0 : Number(payload.shortfall || 0),
          invoiceAttachmentId: id,
          invoiceAttachmentFileName: payload.invoiceAttachment?.name || 'tax-invoice.pdf',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      };
      db.commissions.unshift(record);
      return delay({ commission: buildCommissionRecord(record) });
    },

    async updateDeductions(id, payload) {
      hasRole('sales_manager', 'ceo');
      const record = db.commissions.find((item) => item.id === Number(id));
      if (!record) fail('Commission record not found', 404);
      Object.assign(record.invoiceDetails, {
        transportFee: Number(payload.transportFee ?? record.invoiceDetails.transportFee),
        cutFee: Number(payload.cutFee ?? record.invoiceDetails.cutFee),
        shortfall: Number(payload.shortfall ?? record.invoiceDetails.shortfall),
        updatedAt: new Date().toISOString(),
      });
      const calc = invoiceCalculation(record.invoiceDetails);
      db.commissions
        .filter((item) => item.invoiceDetails.id === record.invoiceDetails.id && !['VOID', 'REJECTED'].includes(item.status))
        .forEach((item) => {
          item.actualReceived = item.kind === 'CLAWBACK' ? -Math.abs(calc.actualReceived) : calc.actualReceived;
          item.commissionableBase = item.kind === 'CLAWBACK' ? -Math.abs(calc.commissionableBase) : calc.commissionableBase;
          item.updatedAt = new Date().toISOString();
        });
      return delay({ commission: buildCommissionRecord(record) });
    },

    async approve(id) {
      const user = hasRole('sales_manager', 'ceo');
      const record = db.commissions.find((item) => item.id === Number(id));
      if (!record) fail('Commission record not found', 404);
      const now = new Date().toISOString();
      if (record.status === 'SUBMITTED') {
        if (user.role !== 'sales_manager') fail('Only a sales manager can review submitted commissions', 403);
        record.status = 'MANAGER_APPROVED';
        record.managerApprovedBy = user.employeeId || user.id;
        record.managerApprovedByName = user.name;
        record.managerApprovedAt = now;
        record.approvedById = user.id;
        record.approvedAt = now;
        record.updatedAt = now;
        return delay({ commission: buildCommissionRecord(record) });
      }
      if (record.status === 'MANAGER_APPROVED') {
        if (user.role !== 'ceo') fail('Only the CEO can review manager-approved commissions', 403);
        record.status = 'APPROVED';
        record.ceoApprovedBy = user.employeeId || user.id;
        record.ceoApprovedByName = user.name;
        record.ceoApprovedAt = now;
        record.approvedById = user.id;
        record.approvedAt = now;
        record.updatedAt = now;
        return delay({ commission: buildCommissionRecord(record) });
      }
      fail('Commission record has already been reviewed', 409);
    },

    async reject(id, payload = {}) {
      const user = hasRole('sales_manager', 'ceo');
      const record = db.commissions.find((item) => item.id === Number(id));
      if (!record) fail('Commission record not found', 404);
      const now = new Date().toISOString();
      if (record.status === 'SUBMITTED') {
        if (user.role !== 'sales_manager') fail('Only a sales manager can review submitted commissions', 403);
      } else if (record.status === 'MANAGER_APPROVED') {
        if (user.role !== 'ceo') fail('Only the CEO can review manager-approved commissions', 403);
      } else {
        fail('Commission record has already been reviewed', 409);
      }
      record.status = 'REJECTED';
      record.rejectedById = user.employeeId || user.id;
      record.rejectedByName = user.name;
      record.rejectedAt = now;
      record.rejectionReason = payload.reviewerNote || null;
      record.approvedById = user.id;
      record.approvedAt = now;
      record.updatedAt = now;
      return delay({ commission: buildCommissionRecord(record) });
    },

    async clawback(id, payload) {
      const user = hasRole('sales_manager', 'ceo');
      const original = db.commissions.find((item) => item.id === Number(id));
      if (!original) fail('Commission record not found', 404);
      if (original.kind !== 'SALE' || original.status !== 'APPROVED') fail('Only approved sale commissions can be clawed back', 409);
      if (db.commissions.some((item) => item.cancellationOfId === original.id && item.status !== 'VOID')) fail('This commission already has an active clawback', 409);
      const nextId = Math.max(0, ...db.commissions.map((item) => item.id)) + 1;
      const record = {
        ...structuredClone(original),
        id: nextId,
        kind: 'CLAWBACK',
        status: 'APPROVED',
        payrollMonth: `${commissionMonth(new Date().toISOString())}-01`,
        actualReceived: -Math.abs(original.actualReceived),
        commissionableBase: -Math.abs(original.commissionableBase),
        submittedById: user.id,
        approvedById: user.id,
        approvedAt: new Date().toISOString(),
        cancellationOfId: original.id,
        cancellationReason: payload.reason,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      db.commissions.unshift(record);
      return delay({ commission: buildCommissionRecord(record) });
    },

    async simulate(payload) {
      const user = requireSession();
      if (!['sales', 'sales_manager', 'ceo'].includes(user.role)) fail('Forbidden', 403);
      if (user.role === 'sales' && (Number(payload.transportFee || 0) > 0 || Number(payload.cutFee || 0) > 0 || Number(payload.shortfall || 0) > 0)) {
        fail('Sales cannot edit deduction fields', 403);
      }
      const salesRepId = user.role === 'sales' ? user.id : Number(payload.salesRepId || user.id);
      const month = commissionMonth(payload.payrollMonth || new Date().toISOString());
      const calc = invoiceCalculation({
        ...payload,
        transportFee: user.role === 'sales' ? 0 : payload.transportFee,
        cutFee: user.role === 'sales' ? 0 : payload.cutFee,
        shortfall: user.role === 'sales' ? 0 : payload.shortfall,
      });
      const existingMonthlyBase = db.commissions
        .filter((item) => item.salesRepId === salesRepId
          && commissionMonth(item.payrollMonth) === month
          && !['VOID', 'REJECTED'].includes(item.status))
        .reduce((sum, item) => sum + Number(item.commissionableBase || 0), 0);
      const projectedMonthlyBase = existingMonthlyBase + calc.commissionableBase;
      const projectedMonthlyCommission = progressiveCommission(projectedMonthlyBase);
      const incrementalCommission = projectedMonthlyCommission - progressiveCommission(existingMonthlyBase);
      return delay({
        simulation: {
          payrollMonth: `${month}-01`,
          actualReceived: calc.actualReceived,
          commissionableBase: calc.commissionableBase,
          existingMonthlyBase,
          projectedMonthlyBase,
          projectedMonthlyCommission,
          incrementalCommission: Number(incrementalCommission.toFixed(2)),
        },
      });
    },

    async payrollReady(params = {}) {
      hasRole('hr');
      const month = commissionMonth(params.payrollMonth || new Date().toISOString());
      const approved = db.commissions.filter((item) => item.status === 'APPROVED' && commissionMonth(item.payrollMonth) === month);
      const reps = new Map();
      approved.forEach((item) => {
        const current = reps.get(item.salesRepId) || { salesRepId: item.salesRepId, salesRepName: item.salesRepName, commissionableBase: 0 };
        current.commissionableBase += Number(item.commissionableBase || 0);
        reps.set(item.salesRepId, current);
      });
      const salesReps = [...reps.values()].map((rep) => ({
        ...rep,
        commissionableBase: Math.max(0, Number(rep.commissionableBase.toFixed(2))),
        commissionAmount: progressiveCommission(rep.commissionableBase),
      }));
      return delay({
        summary: {
          payrollMonth: `${month}-01`,
          status: 'PAYROLL_READY',
          totalCommissionableBase: salesReps.reduce((sum, item) => sum + item.commissionableBase, 0),
          totalCommissionAmount: salesReps.reduce((sum, item) => sum + item.commissionAmount, 0),
          salesReps,
        },
      });
    },
  },

  // No seeded payroll-period data yet — `current` returns an empty period so
  // PayrollPage degrades to its built-in empty state; the mutating actions
  // (preview/process/bankExport) are explicit user-triggered calculations that
  // would require reproducing real payroll/tax logic to fake convincingly, so
  // they surface a clear "not supported in mock mode" error instead of
  // fabricating financial figures (real backend implementation is in hrApi.js).
  // Mirrors PayrollController + PayrollService (payroll/): view/export/payslip
  // reads are hr/ceo; process + distributePayslips are hr-only; downloadOwnPayslip
  // stays open to any authenticated user (Java is isAuthenticated()) so the
  // employee dashboard's "My payslip" button keeps working.
  payroll: {
    async current() {
      hasRole('hr', 'ceo');
      return delay({ period: null });
    },
    async preview() {
      hasRole('hr', 'ceo');
      throw new Error('คำนวณเงินเดือนไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async process() {
      hasRole('hr');
      throw new Error('ประมวลผลเงินเดือนไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async bankExport() {
      hasRole('hr', 'ceo');
      throw new Error('ดาวน์โหลดไฟล์โอนเงินไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async downloadPayslip() {
      hasRole('hr', 'ceo');
      throw new Error('ดาวน์โหลดสลิปเงินเดือนไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async downloadOwnPayslip() {
      requireSession();
      throw new Error('ดาวน์โหลดสลิปเงินเดือนไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async distributePayslips() {
      hasRole('hr');
      throw new Error('ส่งอีเมลสลิปเงินเดือนไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
  },

  // No seeded punch/device data yet — these return empty results so HR-core
  // pages degrade to their built-in empty state instead of crashing on the
  // missing namespace (real backend implementation is in hrApi.js).
  // Mirrors AttendanceController + AttendanceService (attendance/): `list` has
  // no top-level role gate — AttendanceService.listPunches scopes by role
  // instead (hr/ceo see all; a ฝ่าย manager — dashboardManager() — is scoped to
  // their division with no 403; everyone else is 403'd for requesting another
  // employeeId, matching AttendanceService.java:111-121). `devices` and
  // `importDat` are both hr/ceo-only at the controller
  // (AttendanceController.java:47-62).
  attendance: {
    async list(params = {}) {
      const user = requireSession();
      if (!['hr', 'ceo'].includes(user.role)) {
        if (!user.employeeId) fail('User is not linked to an employee', 400);
        if (!(dashboardManager(user) && dashboardDivisionId(user))) {
          if (params.employeeId && Number(params.employeeId) !== user.employeeId) fail('Forbidden', 403);
        }
      }
      return delay({ punches: [] });
    },
    async devices() {
      hasRole('hr', 'ceo');
      return delay({ devices: [] });
    },
    async importDat() {
      hasRole('hr', 'ceo');
      throw new Error('นำเข้าข้อมูลจากเครื่องสแกนไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
  },

  // Mirrors DashboardController + DashboardService (dashboard/).
  dashboard: {
    async summary() {
      const user = requireSession();
      const tickets = dashboardTickets(user);
      const pendingApprovals = dashboardPending(user, tickets);
      const notifications = dashboardNotifications(user);
      return delay({
        summary: {
          role: user.role,
          employeeId: user.employeeId ?? null,
          divisionId: dashboardDivisionId(user),
          manager: dashboardManager(user),
          generatedAt: new Date().toISOString(),
          headcount: dashboardHeadcount(user),
          pendingApprovals,
          attendance: dashboardAttendance(user),
          latestPayrollPeriodId: null,
          tickets,
          notifications,
          totalOpen: tickets.totalOpen,
          submitted: tickets.submitted,
          inReview: tickets.inReview,
          priceProposed: tickets.priceProposed,
          approved: tickets.approved,
          quotationIssued: tickets.quotationIssued,
          closedThisMonth: tickets.closedThisMonth,
          cancelledThisMonth: tickets.cancelledThisMonth,
          overdueOver3Days: tickets.overdueOver3Days,
          onHold: tickets.onHold,
          dormant: tickets.dormant,
          paymentOverdue: tickets.paymentOverdue,
          partiallyDelivered: tickets.partiallyDelivered,
        },
      });
    },
  },

  // Mirrors NotificationController + NotificationService (notification/).
  notifications: {
    async list() {
      const user = requireSession();
      const items = db.notifications
        .filter((n) => n.userId === user.id)
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
        .slice(0, 50);
      return delay({ notifications: structuredClone(items) });
    },
    async markRead(id) {
      requireSession();
      const n = db.notifications.find((item) => item.id === Number(id));
      if (n) n.read = true;
      return delay({ ok: true });
    },
  },

  // Mirrors CatalogController (catalog/) — product CRUD delegates to
  // PriceImportService.addProductManual()/updateProduct()/deleteProduct().
  catalog: {
    async search(q) {
      requireSession();
      const lower = (q ?? '').toLowerCase();
      const results = lower
        ? mockCatalog.filter((c) =>
            c.brand.toLowerCase().includes(lower) ||
            c.collection.toLowerCase().includes(lower) ||
            c.color.toLowerCase().includes(lower) ||
            (c.factory ?? '').toLowerCase().includes(lower))
        : mockCatalog.slice(0, 30);
      return delay({ items: results });
    },
    async prices(q, factoryId) {
      requireSession();
      const lower = (q ?? '').toLowerCase();
      const fid = factoryId ? Number(factoryId) : null;
      let results = mockProductPrices.filter((p) => {
        if (fid && p.factoryId !== fid) return false;
        if (!lower) return true;
        return (
          (p.productCode   ?? '').toLowerCase().includes(lower) ||
          (p.collection    ?? '').toLowerCase().includes(lower) ||
          (p.productName   ?? '').toLowerCase().includes(lower) ||
          (p.color         ?? '').toLowerCase().includes(lower) ||
          (p.surface       ?? '').toLowerCase().includes(lower) ||
          (p.factoryName   ?? '').toLowerCase().includes(lower)
        );
      });
      return delay({ items: results.slice(0, 50) });
    },
    // addProduct/updateProduct/deleteProduct are ceo/import only (#205), mirroring
    // CatalogController.requireCatalogEditor. search/prices above stay requireSession()
    // only — catalog browsing is open to any logged-in user.
    async addProduct(input = {}) {
      hasRole('ceo', 'import');
      if (input.factoryId == null) fail('factoryId จำเป็น', 400);
      if (input.price == null) fail('price จำเป็น', 400);
      const fid = Number(input.factoryId);
      const product = {
        priceId: mockProductPriceSeq++,
        factoryId: fid,
        factoryName: factoryNameFor(fid),
        productCode: input.productCode ?? null,
        grade: input.grade ?? null,
        collection: input.collection ?? null,
        productName: input.productName ?? null,
        color: input.color ?? null,
        surface: input.surface ?? null,
        sizeRaw: input.sizeRaw ?? null,
        price: Number(input.price),
        currency: input.currency ?? 'EUR',
        priceUnit: input.priceUnit ?? 'per_sqm',
        sqmPerPiece: null,
      };
      mockProductPrices.push(product);
      return delay({ priceId: product.priceId, status: 'added' });
    },
    async updateProduct(priceId, input = {}) {
      hasRole('ceo', 'import');
      if (input.price == null) fail('price จำเป็น', 400);
      const pid = Number(priceId);
      const product = mockProductPrices.find((p) => p.priceId === pid);
      if (!product) fail(`ไม่พบสินค้า price_id=${pid}`, 404);
      // factoryId is deliberately not reassignable — PriceImportService.updateProduct
      // does not touch it either.
      Object.assign(product, {
        productCode: input.productCode ?? null,
        grade: input.grade ?? null,
        collection: input.collection ?? null,
        productName: input.productName ?? null,
        color: input.color ?? null,
        surface: input.surface ?? null,
        sizeRaw: input.sizeRaw ?? null,
        price: Number(input.price),
        currency: input.currency ?? null,
        priceUnit: input.priceUnit ?? null,
      });
      return delay({ status: 'updated' });
    },
    async deleteProduct(priceId) {
      hasRole('ceo', 'import');
      const pid = Number(priceId);
      const index = mockProductPrices.findIndex((p) => p.priceId === pid);
      if (index === -1) fail(`ไม่พบสินค้า price_id=${pid}`, 404);
      mockProductPrices.splice(index, 1);
      return delay({ status: 'deleted' });
    },
  },

  // Mirrors FactoryConfigController + FactoryEmailService (factory/).
  factoryConfigs: {
    async list() {
      requireSession();
      return delay({ factories: mockFactoryConfigs });
    },
    async sendEmail(ticketId, payload) {
      // Mirrors TicketService.assertFactoryEmailAllowed: import role + real ticket.
      hasRole('import');
      findTicketRaw(Number(ticketId));
      console.log(`[mock] Factory email sent | ticket=${ticketId} factory=${payload.factory} to=${payload.to}`);
      return delay({ status: 'sent' });
    },
  },

  // Mirrors FxRateController + BotFxFetchService (pricing/).
  fxRates: {
    async list() {
      requireSession();
      return delay({ fxRates: structuredClone(mockFxRates) });
    },
    async upsert(currency, payload) {
      hasRole('ceo');
      const existing = mockFxRates.find((r) => r.currency === currency.toUpperCase());
      if (existing) {
        existing.rateToThb = payload.rateToThb;
        existing.effectiveDate = payload.effectiveDate ?? new Date().toISOString().slice(0, 10);
        existing.updatedAt = new Date().toISOString();
        existing.source = 'MANUAL';
        existing.fetchedAt = null;
        return delay({ fxRate: structuredClone(existing) });
      }
      const newRate = {
        id: mockFxRates.length + 1, currency: currency.toUpperCase(),
        rateToThb: payload.rateToThb,
        effectiveDate: payload.effectiveDate ?? new Date().toISOString().slice(0, 10),
        updatedAt: new Date().toISOString(),
        source: 'MANUAL', fetchedAt: null,
      };
      mockFxRates.push(newRate);
      return delay({ fxRate: structuredClone(newRate) });
    },
  },

  // Mirrors PriceCalcConfigController + PriceCalcService (pricing/).
  priceCalcConfigs: {
    async list() {
      requireSession();
      return delay({ configs: structuredClone(mockPriceCalcConfigs.filter((c) => c.isCurrent)) });
    },
    async update(payload) {
      hasRole('ceo');
      mockPriceCalcConfigs
        .filter((c) => c.country === payload.country && c.isCurrent)
        .forEach((c) => { c.isCurrent = false; });
      const maxVer = Math.max(0, ...mockPriceCalcConfigs.filter((c) => c.country === payload.country).map((c) => c.version));
      const newCfg = {
        configId: mockPriceConfigSeq++, version: maxVer + 1, country: payload.country,
        freightPerSqm: Number(payload.freightPerSqm),
        insurancePerSqm: Number(payload.insurancePerSqm),
        inlandFactoryToPortPerSqm: Number(payload.inlandFactoryToPortPerSqm),
        inlandPortToWarehousePerSqm: Number(payload.inlandPortToWarehousePerSqm),
        importDutyPct: Number(payload.importDutyPct),
        marginPct: Number(payload.marginPct),
        isCurrent: true,
        effectiveFrom: payload.effectiveFrom ?? new Date().toISOString().slice(0, 10),
        updatedAt: new Date().toISOString(),
      };
      mockPriceCalcConfigs.push(newCfg);
      return delay({ config: structuredClone(newCfg) });
    },
  },

  // Mirrors AttachmentController + FileStorageService (attachment/).
  attachments: {
    async list(ticketId) {
      requireSession();
      return delay({ attachments: structuredClone(mockAttachments.filter((a) => a.ticketId === Number(ticketId))) });
    },
    async upload(ticketId, file, attachType) {
      const user = requireSession();
      const attachment = {
        id: mockAttachSeq++, ticketId: Number(ticketId), quotationId: null,
        fileName: file?.name ?? 'file.pdf',
        attachType: (attachType ?? 'OTHER').toUpperCase(),
        mimeType: file?.type ?? 'application/pdf',
        fileSize: file?.size ?? 0,
        uploadedBy: user.id,
        uploadedAt: new Date().toISOString(),
      };
      mockAttachments.push(attachment);
      return delay({ attachment: structuredClone(attachment) });
    },
    fileUrl: (id) => `#mock-file-${id}`,
    async delete(id) {
      requireSession();
      const idx = mockAttachments.findIndex((a) => a.id === Number(id));
      if (idx >= 0) mockAttachments.splice(idx, 1);
      return delay({ ok: true });
    },
  },

  // Mirrors CustomerController (customer/).
  customers: {
    async create(payload) {
      hasRole('sales'); // deal-entry flow; mirrors CustomerController's requireAnyRole('sales')
      const customer = { id: mockCustomerSeq++, name: payload.name, taxId: payload.taxId || null, address: payload.address || null, branch: payload.branch || 'สำนักงานใหญ่', phone: payload.phone || null };
      mockCustomers.push(customer);
      return delay({ customer });
    },
    async search(q) {
      requireSession();
      const lower = (q ?? '').toLowerCase();
      const results = lower
        ? mockCustomers.filter((c) => c.name.toLowerCase().includes(lower) || (c.taxId ?? '').includes(lower))
        : mockCustomers;
      return delay({ customers: results });
    },
    async contacts(customerId) {
      requireSession();
      return delay({ contacts: mockContacts.filter((c) => c.customerId === Number(customerId)) });
    },
    async createContact(customerId, payload) {
      hasRole('sales'); // mirrors CustomerController's requireAnyRole('sales')
      const contact = { id: mockContactSeq++, customerId: Number(customerId), ...payload };
      mockContacts.push(contact);
      return delay({ contact });
    },
    async projects(customerId) {
      requireSession();
      return delay({ projects: mockProjects.filter((p) => p.customerId === Number(customerId)) });
    },
    async createProject(customerId, payload) {
      hasRole('sales'); // mirrors CustomerController's requireAnyRole('sales')
      const project = { id: mockProjectSeq++, customerId: Number(customerId), name: payload.name };
      mockProjects.push(project);
      return delay({ project });
    },
  },

  // Mirrors DepositNoticeController + DepositNoticeService (deposit/).
  depositNotices: {
    async noteTemplates() {
      requireSession();
      return delay({ templates: mockNoteTemplates });
    },

    async createDraft(ticketId, payload) {
      requireSession();
      const ticket = findTicketRaw(Number(ticketId));
      // Phase 1 lifecycle gate (mirrors DepositNoticeService.createDraft).
      requireActive(ticket);
      if (!['approved', 'quotation_issued', 'document_issued'].includes(ticket.status)) fail('Ticket must be approved', 409);

      // Auto-build items from approved ticket items
      const items = payload.items?.length ? payload.items : ticket.items
        .filter((it) => it.approvedPrice != null)
        .map((it, idx) => {
          const desc = [it.brand, it.model, it.color, it.texture, it.size].filter(Boolean).join(' ');
          return { seq: idx + 1, description: desc, qty: Number(it.qty), unit: 'แผ่น', unitPrice: Number(it.approvedPrice), discountLabel: null, netUnitPrice: Number(it.approvedPrice) };
        });

      const notes = payload.notes ?? mockNoteTemplates.filter((t) => t.defaultSelected).map((t) => t.text);
      const nextVer = mockDepositNotices.filter((d) => d.ticketId === Number(ticketId)).length + 1;

      const doc = buildMockDoc({
        id: mockDocSeq++, ticketId: Number(ticketId), docType: 'DEPOSIT_NOTICE',
        version: nextVer, docNumber: null, issueDate: null, status: 'DRAFT',
        customerName: payload.customerName ?? ticket.customerName ?? '',
        customerTaxId: payload.customerTaxId ?? '', customerAddress: payload.customerAddress ?? '',
        projectName: payload.projectName ?? '', reference: payload.reference ?? '',
        depositPercent: payload.depositPercent ?? 0.5, vatPercent: 0.07,
        notes, items, issuedByName: null, preparerName: 'จินตนา หาญมนตรี',
        hasPdf: false, hasXlsx: false,
        createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
      });
      mockDepositNotices.push(doc);
      return delay({ depositNotice: structuredClone(doc) });
    },

    async listByTicket(ticketId) {
      // Mirrors DepositNoticeService.listByTicket: viewer role + sales owner scoping.
      requireTicketViewer(ticketId);
      return delay({ depositNotices: structuredClone(mockDepositNotices.filter((d) => d.ticketId === Number(ticketId))) });
    },

    async get(docId) {
      const doc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!doc) fail('Deposit notice not found', 404);
      // Mirrors DepositNoticeService.getById: read gate on the owning ticket.
      requireTicketViewer(doc.ticketId);
      return delay({ depositNotice: structuredClone(doc) });
    },

    async update(docId, payload) {
      requireSession();
      const doc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!doc) fail('Deposit notice not found', 404);
      if (doc.status !== 'DRAFT') fail('Deposit notice is not draft', 409);
      Object.assign(doc, {
        customerName:    payload.customerName    ?? doc.customerName,
        customerTaxId:   payload.customerTaxId   ?? doc.customerTaxId,
        customerAddress: payload.customerAddress ?? doc.customerAddress,
        projectName:     payload.projectName     ?? doc.projectName,
        reference:       payload.reference       ?? doc.reference,
        depositPercent:  payload.depositPercent  ?? doc.depositPercent,
        notes:           payload.notes           ?? doc.notes,
        items:           payload.items?.length ? payload.items : doc.items,
        updatedAt:       new Date().toISOString(),
      });
      const updated = buildMockDoc(doc);
      Object.assign(doc, updated);
      return delay({ depositNotice: structuredClone(doc) });
    },

    async preview(docId) {
      const doc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!doc) fail('Deposit notice not found', 404);
      // Mirrors DepositNoticeService.preview: read gate on the owning ticket.
      requireTicketViewer(doc.ticketId);
      // Return HTML string directly (not wrapped in JSON)
      return mockPreviewHtml(buildMockDoc(doc));
    },

    async issue(docId) {
      const user = requireSession();
      const doc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!doc) fail('Deposit notice not found', 404);
      if (doc.status !== 'DRAFT') fail('Not a draft', 409);
      const ticket = findTicketRaw(doc.ticketId);

      // Mirrors DepositNoticeService.issue: the document IS the payment-track step.
      // Requires a customer-confirmed quotation; advances paymentStatus and leaves
      // the main status at quotation_issued (no more document_issued flip).
      if (ticket.status !== 'quotation_issued' || ticket.paymentStatus !== 'CUSTOMER_CONFIRMED') {
        fail('Deposit notice requires quotation_issued + paymentStatus=CUSTOMER_CONFIRMED', 409);
      }
      // Phase 1 lifecycle gate (mirrors DepositNoticeService.requireActiveLifecycle).
      requireActive(ticket);

      // Supersede previous issued docs
      mockDepositNotices.forEach((d) => { if (d.ticketId === doc.ticketId && d.id !== doc.id && d.status === 'ISSUED') d.status = 'SUPERSEDED'; });

      const thaiYear = new Date().getFullYear() + 543;
      doc.docNumber = `GLRD${String(thaiYear).slice(-2)}${String(mockDocNumberSeq++).padStart(3,'0')}`;
      doc.issueDate = new Date().toISOString().slice(0, 10);
      doc.status = 'ISSUED';
      doc.issuedByName = user.name;
      doc.updatedAt = new Date().toISOString();

      ticket.paymentStatus = 'DEPOSIT_NOTICE_ISSUED';
      ticket.updatedAt = doc.updatedAt;
      pushEvent(ticket, user, 'DEPOSIT_NOTICE_ISSUED', ticket.status, ticket.status, `เอกสาร ${doc.docNumber} ออกแล้ว`);

      return delay({ depositNotice: structuredClone(doc) });
    },

    async downloadXlsx(docId) {
      const rawDoc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!rawDoc) fail('Deposit notice not found', 404);
      // Mirrors DepositNoticeService.getXlsx: read gate on the owning ticket.
      requireTicketViewer(rawDoc.ticketId);
      const doc = buildMockDoc(rawDoc);

      // Demo placeholder — real xlsx from DepositNoticeRenderer.java (server, Apache POI)
      const items = (doc.items ?? []).map((it, i) => {
        const net = Number(it.netUnitPrice ?? it.unitPrice) || 0;
        const qty = Number(it.qty) || 0;
        return `${i + 1}. ${it.description ?? ''} — ${qty} ${it.unit ?? 'แผ่น'} × ${Number(it.unitPrice) || 0} = ${net * qty}`;
      });
      const lines = [
        `ใบแจ้งยอดมัดจำ  เลขที่ ${doc.docNumber ?? 'DRAFT'}`,
        `วันที่: ${mockThaiDate(doc.issueDate ? new Date(doc.issueDate) : new Date())}`,
        `เรียน ${doc.customerName ?? ''}`,
        ...(doc.reference ? [`อ้างอิง: ${doc.reference}`] : []),
        ...(doc.projectName ? [`Project: ${doc.projectName}`] : []),
        '',
        ...items,
        '',
        `ยอดก่อนภาษี: ${doc.subtotal ?? 0}`,
        `มัดจำ ${((doc.depositPercent ?? 0.5) * 100)}%: ${doc.depositAmount ?? 0}`,
        `ภาษี 7%: ${doc.vatAmount ?? 0}`,
        `ยอดชำระ: ${doc.totalPayable ?? 0}`,
      ];
      return mockDocPlaceholderBlob(lines);
    },

    async downloadPdf(docId) {
      const rawDoc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!rawDoc) fail('Deposit notice not found', 404);
      // Mirrors DepositNoticeService.getPdf: read gate on the owning ticket.
      requireTicketViewer(rawDoc.ticketId);
      const html = mockPreviewHtml(buildMockDoc(rawDoc));
      return new Blob([html], { type: 'text/html;charset=utf-8' });
    },
  },

  // Mirrors PriceImportController + PriceImportService (catalog/importer/).
  priceImport: {
    async factories() {
      hasRole('ceo', 'import');
      return delay(mockPriceImportFactories);
    },
    async createFactory(name, country, defaultCurrency) {
      // #205: PriceImportController now gates every endpoint (including reads) to
      // ceo/import via requireImporter(session) — mirror that here.
      hasRole('ceo', 'import');
      if (!name || !String(name).trim()) fail('ชื่อโรงงานห้ามว่าง', 400);
      const factory = {
        factoryId: mockPriceImportFactorySeq++,
        name: String(name).trim(),
        country: country && String(country).trim() ? String(country).trim().toUpperCase() : null,
        defaultCurrency: defaultCurrency && String(defaultCurrency).trim()
          ? String(defaultCurrency).trim().toUpperCase()
          : 'EUR',
        numberFormat: 'eu',
      };
      mockPriceImportFactories.push(factory);
      return delay(factory);
    },
    async versions(factoryId) {
      hasRole('ceo', 'import');
      const fid = Number(factoryId);
      return delay(mockPriceImportVersions.filter((v) => v.factoryId === fid));
    },
    // Mirrors PriceImportService.uploadAndCommit — parse → stage → validate → commit
    // in one shot, returning UploadCommitResult(versionId, parsedRows, committedRows,
    // retainedRows, errorCount, errors). Gated ceo/import (#205), matching
    // PriceImportController.uploadAndCommit's requireImporter(session) gate.
    //
    // The mock cannot parse a real .xlsx in the browser, so the parsed batch is
    // fabricated — the same simplification upload() already makes with its fixed
    // parsedRows: 12. retainedRows counts the factory's pre-existing products, which
    // is the closest honest stand-in for commit()'s incremental merge (old products
    // not matched by the new file are carried forward, not dropped).
    async uploadAndCommit(factoryId, file, label) {
      hasRole('ceo', 'import');
      const fid = Number(factoryId);
      if (!mockPriceImportFactories.some((f) => f.factoryId === fid)) {
        fail(`ไม่พบ import profile สำหรับ factory id=${fid}`, 404);
      }

      const retainedRows = mockProductPrices.filter((p) => p.factoryId === fid).length;

      const versionId = mockPriceVersionSeq++;
      mockPriceImportVersions.push({
        versionId,
        factoryId: fid,
        label: label || file?.name || `Version ${versionId}`,
        status: 'DRAFT',
        createdAt: new Date().toISOString(),
        uploadedByName: 'Admin',
      });

      const factoryName = factoryNameFor(fid);
      const parsed = [
        { productCode: 'IMP-60120-WHT', collection: 'Imported Series', productName: 'White Lappato', color: 'White', surface: 'Lappato',  sizeRaw: '60x120', price: 41.50, sqmPerPiece: 0.72 },
        { productCode: 'IMP-60120-GRY', collection: 'Imported Series', productName: 'Grey Naturale', color: 'Grey',  surface: 'Naturale', sizeRaw: '60x120', price: 39.90, sqmPerPiece: 0.72 },
        { productCode: 'IMP-8080-BEI',  collection: 'Imported Series', productName: 'Beige Matt',    color: 'Beige', surface: 'Matt',     sizeRaw: '80x80',  price: 36.00, sqmPerPiece: 0.64 },
      ];
      parsed.forEach((row) => {
        mockProductPrices.push({
          priceId: mockProductPriceSeq++,
          factoryId: fid,
          factoryName,
          grade: null,
          currency: 'EUR',
          priceUnit: 'per_sqm',
          ...row,
        });
      });

      activateVersion(versionId);

      return delay({
        versionId,
        parsedRows: parsed.length,
        committedRows: parsed.length,
        retainedRows,
        errorCount: 0,
        errors: [],
      });
    },
    // Field names below mirror PriceImportService.UploadReport/StagingReport/CommitResult
    // exactly (#206) — the mock previously invented stagedRows/parseErrors,
    // totalRows/errorRows/newRows/changedRows/removedRows/unchangedRows, and
    // inserted/updated/archived, none of which exist on the real DTOs.
    async upload(factoryId, file, label) {
      hasRole('ceo', 'import');
      const fid = Number(factoryId);
      const versionId = mockPriceVersionSeq++;
      mockPriceImportVersions.push({
        versionId, factoryId: fid,
        label: label || file?.name || `Version ${versionId}`,
        status: 'DRAFT',
        createdAt: new Date().toISOString(),
        uploadedByName: 'Admin',
      });
      return delay({
        versionId,
        sessionId: crypto.randomUUID(),
        parsedRows: 12,
        errorCount: 0,
        errors: [],
      });
    },
    async validate(versionId) {
      hasRole('ceo', 'import');
      return delay({ status: 'validated', versionId: Number(versionId) });
    },
    async staging(versionId) {
      hasRole('ceo', 'import');
      return delay({
        versionId: Number(versionId),
        totalStaged: 12,
        validCount: 11,
        invalidCount: 1,
        newProducts: 10,
        removedProducts: 1,
        priceChanged: 2,
        prevVersionId: null,
        sampleErrors: [],
      });
    },
    async commit(versionId) {
      hasRole('ceo', 'import');
      const vid = Number(versionId);
      // Mirrors PriceImportService.commit()'s requireDraft() — re-committing an
      // already-ACTIVE/ARCHIVED version is a 409, not a silent re-activation.
      const version = mockPriceImportVersions.find((item) => item.versionId === vid);
      if (version && version.status !== 'DRAFT') {
        fail(`version ${vid} สถานะ ${version.status} (ต้อง DRAFT)`, 409);
      }
      const versionsArchived = activateVersion(vid);
      return delay({ versionId: vid, committed: 10, retained: 2, versionsArchived });
    },
    async getProfile(factoryId) {
      hasRole('ceo', 'import');
      void factoryId;
      return delay(JSON.stringify({ number_format: 'eu', sheets: [{ name: 'Sheet1', header_row: 1 }], columns: {} }));
    },
    async updateProfile(factoryId, json) {
      hasRole('ceo', 'import');
      void json;
      return delay({ status: 'updated', factoryId: Number(factoryId) });
    },
  },

  // Mirrors PricingRequestController + PricingRequestService (pricingrequest/).
  pricingRequests: {
    async listForTicket(ticketId) {
      const user = requireSession();
      if (!PRICING_REQUEST_VIEWER_ROLES.includes(user.role)) fail('Forbidden', 403);
      const ticket = db.tickets.find((t) => t.id === Number(ticketId));
      if (!ticket) fail('Ticket not found', 404);
      // Mirrors PricingRequestService.listForTicket: sales may only see requests
      // on tickets they created.
      if (user.role === 'sales' && ticket.createdById !== user.id) fail('Forbidden', 403);
      const items = mockPricingRequests
        .filter((pr) => pr.ticketId === Number(ticketId))
        .map(buildPricingRequestSummary);
      return delay({ items });
    },

    async queue(params = {}) {
      const user = requireSession();
      // Mirrors PricingRequestService.list: same viewer roles as a single
      // request, plus sales is scoped to only its own created tickets.
      if (!PRICING_REQUEST_VIEWER_ROLES.includes(user.role)) fail('Forbidden', 403);
      if (params.status && !['DRAFT', 'SUBMITTED', 'IMPORT_REVIEWING', 'MORE_INFO_REQUIRED', 'CANCELLED'].includes(params.status)) {
        fail(`Unknown status '${params.status}'`, 400);
      }
      let list = mockPricingRequests;
      if (user.role === 'sales') {
        list = list.filter((pr) => db.tickets.find((t) => t.id === pr.ticketId)?.createdById === user.id);
      }
      if (params.status) list = list.filter((pr) => pr.status === params.status);
      if (params.assignedImportId) list = list.filter((pr) => pr.assignedImportId === Number(params.assignedImportId));
      const activeOnly = params.activeOnly === undefined || params.activeOnly === true || params.activeOnly === 'true';
      if (activeOnly) {
        list = list.filter((pr) => (db.tickets.find((t) => t.id === pr.ticketId)?.lifecycle ?? 'ACTIVE') === 'ACTIVE');
      }
      return delay({ items: list.map(buildPricingRequestSummary) });
    },

    async get(id) {
      const user = requireSession();
      const pr = requirePricingRequestViewable(id, user);
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },

    async create(ticketId, payload) {
      // Mirrors PricingRequestService.createDraft: sales (deal owner), deal
      // must be ACTIVE, and every field is validated BEFORE persisting.
      const user = hasRole('sales');
      const ticket = db.tickets.find((t) => t.id === Number(ticketId));
      if (!ticket) fail('Ticket not found', 404);
      requirePricingRequestDealActive(ticket);
      if (ticket.createdById !== user.id) fail('Forbidden', 403);
      if (!PRICING_REQUEST_RECIPIENT_VALUES.includes(payload.recipientType)) {
        fail(`Unknown recipient type '${payload.recipientType}'`, 400);
      }
      if (!payload.items?.length) fail('items must not be empty', 400);
      requirePricingRequestItemFieldsValid(payload.items);
      for (const item of payload.items) {
        if (!PRICING_REQUEST_QUANTITY_TYPE_VALUES.includes(item.quantityType)) {
          fail(`Unknown quantity type '${item.quantityType}'`, 400);
        }
      }
      if (payload.targetCurrency && payload.targetCurrency.trim().length !== 3) {
        fail('targetCurrency must be a 3-letter currency code', 400);
      }
      if (payload.recipientContactId == null && !payload.recipientLabel?.trim()) {
        fail('ต้องระบุผู้รับคำขอราคา (recipientContactId หรือ recipientLabel)', 400);
      }
      const validSourceItemIds = new Set((ticket.items ?? []).map((i) => i.id));
      for (const item of payload.items) {
        if (item.sourceTicketItemId != null && !validSourceItemIds.has(item.sourceTicketItemId)) {
          fail(`sourceTicketItemId ${item.sourceTicketItemId} does not belong to ticket ${ticketId}`, 400);
        }
      }

      const now = new Date().toISOString();
      const id = mockPricingRequestSeq++;
      const requestCode = nextPricingRequestCode();
      const items = payload.items.map((item, i) => ({
        id: mockPricingRequestItemSeq++,
        pricingRequestId: id,
        sourceTicketItemId: item.sourceTicketItemId ?? null,
        productId: item.productId ?? null,
        variantId: item.variantId ?? null,
        brand: item.brand ?? null,
        model: item.model ?? null,
        color: item.color ?? null,
        texture: item.texture ?? null,
        size: item.size ?? null,
        factory: item.factory ?? null,
        requestedQty: item.requestedQty,
        requestedQtySqm: item.requestedQtySqm ?? null,
        requestedUnit: item.requestedUnit,
        quantityType: item.quantityType,
        targetDeliveryDate: item.targetDeliveryDate ?? null,
        deliveryLocation: item.deliveryLocation ?? null,
        specialRequirement: item.specialRequirement ?? null,
        sortOrder: i,
      }));
      const pr = {
        id, requestCode, ticketId: Number(ticketId),
        recipientType: payload.recipientType,
        recipientContactId: payload.recipientContactId ?? null,
        recipientLabel: payload.recipientLabel ?? null,
        status: 'DRAFT',
        requestedById: user.id, requestedByName: user.name,
        assignedImportId: null, assignedImportName: null,
        requiredDate: payload.requiredDate ?? null,
        customerTargetPrice: payload.customerTargetPrice ?? null,
        targetCurrency: payload.targetCurrency ?? null,
        note: payload.note ?? null,
        revisionNo: 0, parentPricingRequestId: null,
        submittedAt: null, pickedUpAt: null, cancelledAt: null,
        createdAt: now, updatedAt: now,
        items, events: [],
      };
      // Deliberately no notification, no ticket status change — a draft is the
      // rep's private scratchpad until submit() (mirrors createDraft's Javadoc).
      pushPricingRequestEvent(pr, user, 'PRICING_REQUEST_CREATED', null, 'DRAFT');
      mockPricingRequests.push(pr);
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },

    async update(id, payload) {
      // Mirrors PricingRequestService.updateDraft: owner sales, DRAFT only.
      const user = hasRole('sales');
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (ticket?.createdById !== user.id) fail('Forbidden', 403);
      if (pr.status !== 'DRAFT') fail(`Expected status 'DRAFT' but pricing request is '${pr.status}'`, 409);
      if (payload.recipientType != null && !PRICING_REQUEST_RECIPIENT_VALUES.includes(payload.recipientType)) {
        fail(`Unknown recipient type '${payload.recipientType}'`, 400);
      }
      if (payload.items != null) {
        requirePricingRequestItemFieldsValid(payload.items);
        for (const item of payload.items) {
          if (!PRICING_REQUEST_QUANTITY_TYPE_VALUES.includes(item.quantityType)) {
            fail(`Unknown quantity type '${item.quantityType}'`, 400);
          }
        }
        const validSourceItemIds = new Set((ticket?.items ?? []).map((i) => i.id));
        for (const item of payload.items) {
          if (item.sourceTicketItemId != null && !validSourceItemIds.has(item.sourceTicketItemId)) {
            fail(`sourceTicketItemId ${item.sourceTicketItemId} does not belong to ticket ${pr.ticketId}`, 400);
          }
        }
      }
      if (payload.targetCurrency != null && payload.targetCurrency.trim().length !== 3) {
        fail('targetCurrency must be a 3-letter currency code', 400);
      }
      const effectiveContactId = payload.recipientContactId !== undefined ? payload.recipientContactId : pr.recipientContactId;
      const effectiveLabel = payload.recipientLabel !== undefined ? payload.recipientLabel : pr.recipientLabel;
      if ((payload.recipientContactId !== undefined || payload.recipientLabel !== undefined)
          && effectiveContactId == null && !effectiveLabel?.trim()) {
        fail('ต้องระบุผู้รับคำขอราคา (recipientContactId หรือ recipientLabel)', 400);
      }

      if (payload.recipientType !== undefined) pr.recipientType = payload.recipientType;
      if (payload.recipientContactId !== undefined) pr.recipientContactId = payload.recipientContactId;
      if (payload.recipientLabel !== undefined) pr.recipientLabel = payload.recipientLabel;
      if (payload.requiredDate !== undefined) pr.requiredDate = payload.requiredDate;
      if (payload.customerTargetPrice !== undefined) pr.customerTargetPrice = payload.customerTargetPrice;
      if (payload.targetCurrency !== undefined) pr.targetCurrency = payload.targetCurrency;
      if (payload.note !== undefined) pr.note = payload.note;
      if (payload.items != null) {
        pr.items = payload.items.map((item, i) => ({
          id: mockPricingRequestItemSeq++,
          pricingRequestId: pr.id,
          sourceTicketItemId: item.sourceTicketItemId ?? null,
          productId: item.productId ?? null,
          variantId: item.variantId ?? null,
          brand: item.brand ?? null,
          model: item.model ?? null,
          color: item.color ?? null,
          texture: item.texture ?? null,
          size: item.size ?? null,
          factory: item.factory ?? null,
          requestedQty: item.requestedQty,
          requestedQtySqm: item.requestedQtySqm ?? null,
          requestedUnit: item.requestedUnit,
          quantityType: item.quantityType,
          targetDeliveryDate: item.targetDeliveryDate ?? null,
          deliveryLocation: item.deliveryLocation ?? null,
          specialRequirement: item.specialRequirement ?? null,
          sortOrder: i,
        }));
      }
      pr.updatedAt = new Date().toISOString();
      pushPricingRequestEvent(pr, user, 'PRICING_REQUEST_UPDATED', 'DRAFT', 'DRAFT');
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },

    async submit(id) {
      // Mirrors PricingRequestService.submit: owner sales, DRAFT only, deal
      // ACTIVE, >=1 item, recipient identifiable, requiredDate not in the past,
      // no duplicate sourceTicketItemId across lines.
      const user = hasRole('sales');
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (ticket?.createdById !== user.id) fail('Forbidden', 403);
      if (pr.status !== 'DRAFT') fail(`Expected status 'DRAFT' but pricing request is '${pr.status}'`, 409);
      requirePricingRequestDealActive(ticket);
      if (pr.items.length === 0) fail('ต้องมีรายการสินค้าอย่างน้อย 1 รายการก่อนส่งคำขอราคา', 400);
      if (pr.recipientContactId == null && !pr.recipientLabel?.trim()) fail('ต้องระบุผู้รับคำขอราคา', 400);
      if (pr.requiredDate && new Date(pr.requiredDate) < new Date(new Date().toDateString())) {
        fail('วันที่ต้องการต้องไม่ใช่วันที่ผ่านมาแล้ว', 400);
      }
      // Re-check item identity against the PERSISTED items, not just at
      // create()/update() time — a draft saved before this rule existed (or
      // one whose items were never touched again) must still be blocked here.
      requirePricingRequestItemFieldsValid(pr.items);
      const seenSourceItemIds = new Set();
      for (const item of pr.items) {
        if (item.sourceTicketItemId != null) {
          if (seenSourceItemIds.has(item.sourceTicketItemId)) fail('มีรายการอ้างอิงสินค้าเดิมซ้ำกัน', 400);
          seenSourceItemIds.add(item.sourceTicketItemId);
        }
      }

      const now = new Date().toISOString();
      pr.status = 'SUBMITTED';
      pr.submittedAt = now;
      pr.updatedAt = now;
      pushPricingRequestEvent(pr, user, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED');
      // NotificationRepository.notifyByRole('import', ...) has no single mock
      // equivalent (no per-role broadcast helper here) — hardcoded to the demo
      // import user (id 7), same convention as the existing ceo hardcode
      // (id 8) elsewhere in this file for PRICE_PROPOSED.
      addNotification(7, pr.ticketId, ticket?.code, 'PRICING_REQUEST_SUBMITTED', `ใบขอราคา ${pr.requestCode} รอการรับเรื่อง`);
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },

    async pickup(id) {
      // Mirrors PricingRequestService.pickup: any import user, SUBMITTED only.
      // Assigns the PRICING REQUEST only — never sales.ticket.assigned_to (two
      // pricing requests on the same deal may go to two different Import users).
      const user = hasRole('import');
      const pr = findPricingRequestRaw(id);
      if (pr.status !== 'SUBMITTED') fail('Only a submitted pricing request can be picked up', 409);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      requirePricingRequestDealActive(ticket);
      const now = new Date().toISOString();
      pr.status = 'IMPORT_REVIEWING';
      pr.assignedImportId = user.id;
      pr.assignedImportName = user.name;
      pr.pickedUpAt = now;
      pr.updatedAt = now;
      pushPricingRequestEvent(pr, user, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING');
      addNotification(pr.requestedById, pr.ticketId, ticket?.code, 'PRICING_REQUEST_PICKED_UP', `ใบขอราคา ${pr.requestCode} ถูกรับเรื่องแล้ว`);
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },

    async requestInformation(id, payload) {
      // Mirrors PricingRequestService.requestInformation: only the ASSIGNED
      // import user, IMPORT_REVIEWING only.
      const user = hasRole('import');
      const pr = findPricingRequestRaw(id);
      if (pr.assignedImportId == null || pr.assignedImportId !== user.id) fail('Forbidden', 403);
      if (pr.status !== 'IMPORT_REVIEWING') fail(`Expected status 'IMPORT_REVIEWING' but pricing request is '${pr.status}'`, 409);
      // Mirrors RequestMoreInformationRequest's @NotBlank message.
      if (!payload.message?.trim()) fail('message must not be blank', 400);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      const now = new Date().toISOString();
      pr.status = 'MORE_INFO_REQUIRED';
      pr.updatedAt = now;
      pushPricingRequestEvent(
        pr, user, 'MORE_INFO_REQUESTED', 'IMPORT_REVIEWING', 'MORE_INFO_REQUIRED',
        payload.message, payload.dueDate ? JSON.stringify({ dueDate: payload.dueDate }) : null,
      );
      addNotification(pr.requestedById, pr.ticketId, ticket?.code, 'MORE_INFO_REQUIRED', `ใบขอราคา ${pr.requestCode} ต้องการข้อมูลเพิ่มเติม`);
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },

    async respondInformation(id, payload) {
      // Mirrors PricingRequestService.respondInformation: owner sales,
      // MORE_INFO_REQUIRED only. Goes back to IMPORT_REVIEWING, not SUBMITTED —
      // Import already owns this request; assignedImportId/pickedUpAt survive.
      const user = hasRole('sales');
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (ticket?.createdById !== user.id) fail('Forbidden', 403);
      if (pr.status !== 'MORE_INFO_REQUIRED') fail(`Expected status 'MORE_INFO_REQUIRED' but pricing request is '${pr.status}'`, 409);
      const now = new Date().toISOString();
      pr.status = 'IMPORT_REVIEWING';
      pr.updatedAt = now;
      pushPricingRequestEvent(pr, user, 'MORE_INFO_RESPONDED', 'MORE_INFO_REQUIRED', 'IMPORT_REVIEWING', payload.response);
      if (pr.assignedImportId != null) {
        addNotification(pr.assignedImportId, pr.ticketId, ticket?.code, 'MORE_INFO_RESPONDED', `ใบขอราคา ${pr.requestCode} ได้รับข้อมูลเพิ่มเติมแล้ว`);
      }
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },

    async cancel(id, payload) {
      // Mirrors PricingRequestService.cancel: owner sales OR ceo (an explicit
      // override — unlike TicketService.cancel, which has none), any status the
      // transition table allows into CANCELLED.
      const user = requireSession();
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      const isOwnerOrCeo = user.role === 'ceo' || ticket?.createdById === user.id;
      if (!isOwnerOrCeo) fail('Forbidden', 403);
      if (!pricingRequestCanTransition(pr.status, 'CANCELLED')) {
        fail(`Cannot cancel pricing request in status '${pr.status}'`, 409);
      }
      const now = new Date().toISOString();
      const fromStatus = pr.status;
      pr.status = 'CANCELLED';
      pr.cancelledAt = now;
      pr.updatedAt = now;
      pushPricingRequestEvent(pr, user, 'PRICING_REQUEST_CANCELLED', fromStatus, 'CANCELLED', payload.reason, JSON.stringify({ reason: payload.reason }));
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },
  },

};
