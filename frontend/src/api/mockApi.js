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
  UNIT_BASIS_OPTIONS as PRICING_REQUEST_UNIT_BASIS_OPTIONS,
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
db.specialMoneyRequests = db.specialMoneyRequests || [];
if (db.specialMoneyRequests.length === 0) {
  const now = new Date().toISOString();
  db.specialMoneyRequests = [
    {
      id: 1,
      employeeId: db.employees[8].id,
      requestType: 'MEDICAL',
      eventDate: '2026-06-20',
      eventEndDate: null,
      receiptDate: '2026-06-20',
      quantity: 1,
      requestedAmount: 1200,
      approvedAmount: null,
      payrollBucket: 'NON_TAXABLE',
      policyVersion: 1,
      reason: 'ค่ารักษาพยาบาลตรวจสุขภาพประจำปี',
      detail: {},
      status: 'SUBMITTED',
      payrollMonth: null,
      capOverrideReason: null,
      requestedById: db.employees[8].id,
      requestedAt: now,
      managerApprovedBy: null,
      managerApprovedAt: null,
      ceoApprovedBy: null,
      ceoApprovedAt: null,
      reviewedById: null,
      reviewedAt: null,
      reviewerNote: null,
      cancelledAt: null,
      createdAt: now,
      updatedAt: now,
    },
    {
      id: 2,
      employeeId: db.employees[12].id,
      requestType: 'TRAVEL_PER_DIEM',
      eventDate: '2026-07-10',
      eventEndDate: '2026-07-12',
      receiptDate: null,
      quantity: 3,
      requestedAmount: 1200,
      approvedAmount: null,
      payrollBucket: 'PER_DIEM',
      policyVersion: 1,
      reason: 'เดินทางส่งของลูกค้าต่างจังหวัด',
      detail: { destination: 'DOMESTIC', province: 'เชียงใหม่', role: 'driver' },
      status: 'MANAGER_APPROVED',
      payrollMonth: null,
      capOverrideReason: null,
      requestedById: db.employees[12].id,
      requestedAt: now,
      managerApprovedBy: db.employees[20].id,
      managerApprovedAt: now,
      ceoApprovedBy: null,
      ceoApprovedAt: null,
      reviewedById: db.employees[20].id,
      reviewedAt: now,
      reviewerNote: null,
      cancelledAt: null,
      createdAt: now,
      updatedAt: now,
    },
    {
      id: 3,
      employeeId: db.employees[3].id,
      requestType: 'AID_WEDDING',
      eventDate: '2026-05-15',
      eventEndDate: null,
      receiptDate: null,
      quantity: 1,
      requestedAmount: 5000,
      approvedAmount: 5000,
      payrollBucket: 'AID',
      policyVersion: 1,
      reason: 'เงินช่วยเหลืองานแต่งงาน',
      detail: {},
      status: 'APPROVED',
      payrollMonth: '2026-06-01',
      capOverrideReason: null,
      requestedById: db.employees[3].id,
      requestedAt: now,
      managerApprovedBy: db.employees[20].id,
      managerApprovedAt: now,
      ceoApprovedBy: db.employees[0].id,
      ceoApprovedAt: now,
      reviewedById: db.employees[0].id,
      reviewedAt: now,
      reviewerNote: null,
      cancelledAt: null,
      createdAt: now,
      updatedAt: now,
    },
    {
      id: 4,
      employeeId: db.employees[8].id,
      requestType: 'UNIFORM_ANNUAL',
      eventDate: '2026-06-05',
      eventEndDate: null,
      receiptDate: '2026-05-28',
      quantity: 2,
      requestedAmount: 4000,
      approvedAmount: null,
      payrollBucket: 'NON_TAXABLE',
      policyVersion: 1,
      reason: 'ชุดฟอร์มประจำปี ตัดใหม่จำนวน 2 ชิ้น',
      detail: { uniformMode: 'SELF_BUY', shirtCount: '3', trouserCount: '2' },
      status: 'REJECTED',
      payrollMonth: null,
      capOverrideReason: null,
      requestedById: db.employees[8].id,
      requestedAt: now,
      managerApprovedBy: null,
      managerApprovedAt: null,
      ceoApprovedBy: null,
      ceoApprovedAt: null,
      reviewedById: db.employees[20].id,
      reviewedAt: now,
      reviewerNote: 'จำนวนชิ้นเกินเพดานต่อปี กรุณาส่งคำขอใหม่ให้ตรงเพดาน',
      cancelledAt: null,
      createdAt: now,
      updatedAt: now,
    },
    {
      id: 5,
      employeeId: db.employees[12].id,
      requestType: 'TRAINING',
      eventDate: '2026-06-01',
      eventEndDate: null,
      receiptDate: null,
      quantity: 1,
      requestedAmount: 2500,
      approvedAmount: null,
      payrollBucket: 'NON_TAXABLE',
      policyVersion: 1,
      reason: 'อบรมหลักสูตรความปลอดภัยในการขับขี่',
      detail: {},
      status: 'CANCELLED',
      payrollMonth: null,
      capOverrideReason: null,
      requestedById: db.employees[12].id,
      requestedAt: now,
      managerApprovedBy: null,
      managerApprovedAt: null,
      ceoApprovedBy: null,
      ceoApprovedAt: null,
      reviewedById: null,
      reviewedAt: null,
      reviewerNote: null,
      cancelledAt: now,
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
const mockFactoryQuotes = [];
const mockPricingCostings = [];
const mockFactoryQuoteResponseReceipts = [];
// clientRequestId -> quoteId, for sendFactoryQuote()'s idempotency replay (mirrors
// sales.factory_quote_email_dispatch's unique (created_by, client_request_id) index).
const mockFactoryQuoteDispatchClientRequests = [];
let mockFactoryQuoteSeq = 1;
let mockFactoryQuoteItemSeq = 1;
let mockFactoryQuoteAttachmentSeq = 1;
let mockPricingRequestAttachmentSeq = 1;
let mockPricingCostingSeq = 1;
// Step 3: CEO Selling Price Decision (sales.pricing_decision / pricing_decision_item).
const mockPricingDecisions = [];
let mockPricingDecisionSeq = 1;
let mockPricingDecisionItemSeq = 1;
// Step 4: Customer Quotation Generation and Issuance — extends sales.quotation/quotation_item
// (owner's decision: ONE quotation aggregate, not a parallel table). Kept as its own flat mock
// array (like mockPricingDecisions) rather than nested inside a ticket's `.quotations`, since a
// Step 4 quotation is keyed by pricingRequestId first — the legacy ticket-item-driven
// `ticket.quotations` array is untouched by this section.
const mockCustomerQuotations = [];
let mockCustomerQuotationSeq = 1;
let mockCustomerQuotationItemSeq = 1;
// Step 7: Factory Purchase Order and Import Execution (sales.factory_purchase_order /
// factory_purchase_order_item). Mirrors ProcurementRepository/ProcurementService.
const mockFactoryPurchaseOrders = [];
let mockFactoryPurchaseOrderSeq = 1;
let mockFactoryPurchaseOrderItemSeq = 1;
const PRICING_REQUEST_VIEWER_ROLES = ['sales', 'import', 'ceo', 'sales_manager'];
const PRICING_REQUEST_RECIPIENT_VALUES = PRICING_REQUEST_RECIPIENT_OPTIONS.map((o) => o.code);
const PRICING_REQUEST_QUANTITY_TYPE_VALUES = PRICING_REQUEST_QUANTITY_TYPE_OPTIONS.map((o) => o.code);
// Mirrors th.co.glr.hr.pricingrequest.UnitBasis.VALUES (financial-integrity review Finding B).
const UNIT_BASIS_VALUES = PRICING_REQUEST_UNIT_BASIS_OPTIONS.map((o) => o.code);

function nextPricingRequestCode() {
  return `PCR-2026-${String(mockPricingRequestSeq).padStart(4, '0')}`;
}

function findPricingRequestRaw(id) {
  const pr = mockPricingRequests.find((p) => p.id === Number(id));
  if (!pr) fail('Pricing request not found', 404);
  return pr;
}

// Step 7: Factory Purchase Order and Import Execution — mirrors ProcurementRepository.findById /
// ProcurementRepository.withItems (joins request/ticket codes + item display fields for read).
function findFactoryPurchaseOrderRaw(id) {
  const po = mockFactoryPurchaseOrders.find((p) => p.id === Number(id));
  if (!po) fail('Factory purchase order not found', 404);
  return po;
}

function buildFactoryPurchaseOrderView(po) {
  const pr = mockPricingRequests.find((p) => p.id === po.pricingRequestId);
  const ticket = db.tickets.find((t) => t.id === po.ticketId);
  const creator = db.users.find((u) => u.id === po.createdBy);
  return {
    ...po,
    pricingRequestCode: pr?.requestCode ?? null,
    ticketCode: ticket?.code ?? null,
    createdByName: creator?.name ?? po.createdByName ?? null,
  };
}

// Mock stand-in for FactoryQuoteEmailDispatchWorker: send() only enqueues (dispatchStatus:
// 'PENDING'); this simulates the background worker claiming it (-> 'SENDING') and finalizing it
// (-> 'SENT', quote -> REQUESTED, pricing request status transition, FACTORY_EMAIL_SENT event) a
// short delay later, so PricingRequestDetailPage's polling has something real to observe.
function scheduleMockFactoryQuoteDispatch(quote, actor) {
  quote.dispatchStatus = 'SENDING';
  quote.dispatchAttemptCount = 1;
  setTimeout(() => {
    const current = mockFactoryQuotes.find((q) => q.id === quote.id);
    // Guard against a quote that moved on (e.g. was cancelled) while "in flight" — the closest
    // mock equivalent of the real worker's guarded, idempotent finalize.
    if (!current || current.status !== 'DRAFT' || current.dispatchStatus !== 'SENDING') return;
    current.status = 'REQUESTED';
    current.emailSentAt = new Date().toISOString();
    current.requestedAt = current.emailSentAt;
    current.sentBy = actor.id;
    current.updatedAt = current.emailSentAt;
    current.dispatchStatus = 'SENT';
    const pr = findPricingRequestRaw(current.pricingRequestId);
    const fromStatus = pr.status;
    if (['IMPORT_REVIEWING', 'COSTING_IN_PROGRESS'].includes(pr.status)) pr.status = 'AWAITING_FACTORY_RESPONSE';
    pushPricingRequestEvent(pr, actor, 'FACTORY_EMAIL_SENT', fromStatus, pr.status);
  }, 700);
}

// Step 3, design correction 3 ("freeze factory mutations from CEO_REVIEWING"): mirrors
// FactoryQuoteService's RESPONSE_STATUSES/MUTABLE_STATUSES/DRAFT_STATUSES all deliberately
// excluding CEO_REVIEWING (the CEO owns the request from here until approve/return). Called at
// the top of every factory-quote mutation this branch touches.
function mockRequireNotCeoReviewing(pr) {
  if (pr.status === 'CEO_REVIEWING') {
    fail('Pricing request is under CEO review — factory quote mutations are frozen', 409);
  }
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

function round2(n) {
  return Math.round((Number(n) + Number.EPSILON) * 100) / 100;
}

// Step 6: mirrors OrderConfirmationService's own private unitLabel(), used when building a
// deposit-notice item from a customer-quotation item's requestedUnitBasis.
function mockUnitBasisLabel(unitBasis) {
  switch (unitBasis) {
    case 'PER_SQM': return 'ตร.ม.';
    case 'PER_PIECE': return 'แผ่น';
    case 'PER_BOX': return 'กล่อง';
    case 'PER_LINEAR_M': return 'เมตร';
    default: return unitBasis ?? 'หน่วย';
  }
}

/**
 * Step 8: mirrors OrderConfirmationService.reconcileTicketItems — sales.ticket_item.qty is set
 * once at ticket creation and never touched by the PricingRequest chain, so it can drift from
 * what the chain actually settled on (both on a first submission and further after a
 * cost-affecting customer-change revision, which is reachable even from QUOTATION_ACCEPTED — see
 * that Java method's own Javadoc for the full account). Called once from confirmOrder, before any
 * delivery machinery becomes reachable, exactly like the real bridge.
 */
function reconcileTicketItemsFromPricingRequest(ticket, pr, user) {
  let anyChange = false;
  for (const item of pr.items ?? []) {
    const newQty = Number(item.requestedQty);
    if (item.sourceTicketItemId != null) {
      const ticketItem = ticket.items.find((ti) => ti.id === item.sourceTicketItemId);
      if (!ticketItem) continue;
      const qtyDelivered = Number(ticketItem.qtyDelivered ?? 0);
      const qtyFromStock = Number(ticketItem.qtyFromStock ?? 0);
      if (newQty < qtyDelivered || newQty < qtyFromStock) {
        // Mirrors the DB's chk_ticket_item_qty_delivered/chk_ticket_item_qty_from_stock CHECK
        // constraints (V54) that back the real reconcileItemQty call — a downward reconciliation
        // that would drop qty below an already-recorded delivered/reserved amount is refused, not
        // silently applied.
        fail(`ไม่สามารถปรับจำนวนสินค้า (item ${item.sourceTicketItemId}) ให้ตรงกับใบขอราคาที่ยืนยันคำสั่งซื้อได้ `
          + 'เนื่องจากมีการส่งมอบหรือจองสต็อกไปแล้วเกินจำนวนใหม่', 409);
      }
      if (Number(ticketItem.qty) !== newQty) {
        ticketItem.qty = newQty;
        ticketItem.qtySqm = item.requestedQtySqm ?? null;
        anyChange = true;
      }
    } else {
      // A wholly new line added by a customer-change revision — no original ticket_item to
      // reconcile against, so one is created now.
      const nextId = Math.max(0, ...ticket.items.map((ti) => ti.id)) + 1;
      ticket.items.push({
        id: nextId, ticketId: ticket.id,
        brand: item.brand || item.model || item.productDescription || 'รายการใหม่จากใบขอราคา',
        model: item.model ?? null, color: item.color ?? null, texture: item.texture ?? null,
        size: item.size ?? null, factory: item.factory ?? null,
        qty: newQty, qtySqm: item.requestedQtySqm ?? null,
        proposedPrice: null, approvedPrice: null,
        currency: 'THB', sortOrder: ticket.items.length,
        qtyDelivered: 0, qtyFromStock: 0,
        unitBasis: item.requestedUnitBasis === 'PER_SQM' ? 'SQM' : 'PIECE',
      });
      anyChange = true;
    }
  }
  // Bug fix (found on review, not deferred): close out any ticket_item this pricing-request
  // CHAIN used to reference but the currently-accepted revision dropped entirely — mirrors
  // TicketRepository.closeOutDroppedChainItems. Without this, a stale ticket_item with
  // qty > qtyDelivered permanently blocks completeDelivery's "no open quantities" check, since
  // nobody can ever deliver units of a product the customer no longer ordered.
  //
  // Walk to the root of pr's own revision chain (mirrors root_pricing_request_id), scoped to
  // THIS chain only — a ticket can carry independent pricing requests for other recipients
  // (designer/owner/buyer), which must never be touched just because they're absent here.
  let root = pr;
  while (root.parentPricingRequestId != null) {
    const parent = mockPricingRequests.find((p) => p.id === root.parentPricingRequestId);
    if (!parent) break;
    root = parent;
  }
  const chain = mockPricingRequests.filter((p) => {
    let walk = p;
    while (walk) {
      if (walk.id === root.id) return true;
      walk = walk.parentPricingRequestId != null
        ? mockPricingRequests.find((x) => x.id === walk.parentPricingRequestId)
        : null;
    }
    return false;
  });
  const everReferencedIds = new Set(
    chain.flatMap((p) => (p.items ?? []).map((i) => i.sourceTicketItemId)).filter((id) => id != null));
  const currentIds = new Set((pr.items ?? []).map((i) => i.sourceTicketItemId).filter((id) => id != null));
  for (const droppedId of everReferencedIds) {
    if (currentIds.has(droppedId)) continue;
    const ticketItem = ticket.items.find((ti) => ti.id === droppedId);
    if (!ticketItem) continue;
    const qtyDelivered = Number(ticketItem.qtyDelivered ?? 0);
    if (Number(ticketItem.qty) !== qtyDelivered) {
      ticketItem.qty = qtyDelivered;
      anyChange = true;
    }
  }
  if (anyChange) {
    pushPricingRequestEvent(pr, user, 'TICKET_ITEMS_RECONCILED', null, null,
      'ปรับจำนวนสินค้าในรายการดีลให้ตรงกับใบขอราคาที่ยืนยันคำสั่งซื้อแล้ว');
  }
}

/** Builds a fresh Step 4 DRAFT quotation, snapshotting prices from the current APPROVED
 * pricing_decision — mirrors CustomerQuotationService.create/createRevision's item-building
 * (buildItem). `priorDiscounts` (keyed by pricingRequestItemId) is empty for a first-ever
 * create and carries forward each line's discount for a revision. */
function buildMockCustomerQuotationDraft(pr, decision, ticket, user, payload, parentQuotationId, revisionNo, priorDiscounts) {
  const customer = ticket?.customerId ? mockCustomers.find((c) => c.id === ticket.customerId) : null;
  const project = ticket?.projectId ? mockProjects.find((p) => p.id === ticket.projectId) : null;
  const id = mockCustomerQuotationSeq++;
  const items = decision.items
    .filter((di) => di.approvedSellingPricePerRequestedUnit != null)
    .map((di, idx) => {
      const discount = Number(priorDiscounts?.[di.pricingRequestItemId] ?? 0);
      const approvedUnitPrice = Number(di.approvedSellingPricePerRequestedUnit);
      const finalUnitPrice = approvedUnitPrice - discount;
      const lineSubtotal = round2(finalUnitPrice * Number(di.requestedQuantity));
      const vat = round2(lineSubtotal * 0.07);
      return {
        id: mockCustomerQuotationItemSeq++,
        seq: idx + 1,
        pricingRequestItemId: di.pricingRequestItemId,
        pricingDecisionItemId: di.id,
        description: di.productDescription || [di.brand, di.model].filter(Boolean).join(' '),
        itemNotes: null,
        requestedUnitBasis: di.requestedUnitBasis,
        requestedQuantity: Number(di.requestedQuantity),
        approvedUnitPrice,
        salesDiscount: discount,
        finalUnitPrice,
        minimumSellingPricePerRequestedUnit: di.minimumSellingPricePerRequestedUnit ?? null,
        lineSubtotal,
        vat,
        lineTotal: round2(lineSubtotal + vat),
      };
    });
  const quotation = {
    id,
    number: `QT-2026-${String(id).padStart(4, '0')}`,
    ticketId: pr.ticketId,
    pricingRequestId: pr.id,
    pricingDecisionId: decision.id,
    recipientType: pr.recipientType,
    recipientLabel: pr.recipientLabel ?? null,
    docStatus: 'DRAFT',
    quotationVersion: revisionNo,
    quotationRevisionNo: revisionNo,
    parentQuotationId: parentQuotationId ?? null,
    issuedById: user.id,
    issuedByName: user.name,
    issuedAt: null,
    subtotalAmount: 0,
    vatAmount: 0,
    grandTotal: 0,
    currency: decision.currency || 'THB',
    paymentTerms: payload.paymentTerms || null,
    leadTime: payload.leadTime || null,
    deliveryTerms: payload.deliveryTerms || null,
    validityDate: payload.validityDate || null,
    customerNotes: payload.customerNotes || null,
    sentAt: null,
    acceptedAt: null,
    rejectedAt: null,
    createdAt: new Date().toISOString(),
    clientRequestId: payload.clientRequestId ?? null,
    issueClientRequestId: null,
    customerName: ticket?.customerName ?? (customer ? customer.name : null),
    customerAddress: customer ? customer.address : null,
    customerTaxId: customer ? customer.taxId : null,
    customerPhone: customer ? customer.phone : null,
    projectName: project ? project.name : null,
    items,
  };
  recalcMockCustomerQuotationTotals(quotation);
  return quotation;
}

function recalcMockCustomerQuotationTotals(quotation) {
  quotation.subtotalAmount = round2(quotation.items.reduce((sum, it) => sum + it.lineSubtotal, 0));
  quotation.vatAmount = round2(quotation.items.reduce((sum, it) => sum + it.vat, 0));
  quotation.grandTotal = round2(quotation.items.reduce((sum, it) => sum + it.lineTotal, 0));
}

// Read access: sales/sales_manager/ceo/import, sales scoped to their own deal. account is
// deliberately excluded end-to-end (task brief: "account role: no quotation editing", and
// Step 3's own precedent excludes account from every raw-pricing-adjacent view on this chain).
function mockCustomerQuotationViewAccess(id) {
  const user = hasRole('sales', 'sales_manager', 'ceo', 'import');
  const quotation = mockCustomerQuotations.find((q) => q.id === Number(id));
  if (!quotation) fail('Customer quotation not found', 404);
  if (user.role === 'sales') {
    const pr = findPricingRequestRaw(quotation.pricingRequestId);
    const ticket = db.tickets.find((t) => t.id === pr.ticketId);
    if (ticket?.createdById !== user.id) fail('Forbidden', 403);
  }
  return quotation;
}

// Write access: sales, ticket owner only — ceo/import/sales_manager are read-only everywhere
// on this aggregate (caller must already have called hasRole('sales') for `user`).
function mockCustomerQuotationEditAccess(id, user) {
  const quotation = mockCustomerQuotations.find((q) => q.id === Number(id));
  if (!quotation) fail('Customer quotation not found', 404);
  const pr = findPricingRequestRaw(quotation.pricingRequestId);
  const ticket = db.tickets.find((t) => t.id === pr.ticketId);
  if (ticket?.createdById !== user.id) fail('Forbidden', 403);
  return quotation;
}

// Demo-mode file preview for a Step 4 quotation — mirrors buildMockQuotationXlsx/
// buildMockQuotationHtml's own placeholder pattern (same styling/colors, same "real file comes
// from the server template" banner) rather than inventing a second preview style.
function buildMockCustomerQuotationDocument(quotation, format) {
  if (format === 'xlsx') {
    const lines = [
      `ใบเสนอราคา  เลขที่ ${quotation.number ?? ''} (revision ${quotation.quotationRevisionNo})`,
      `วันที่: ${mockThaiDate(quotation.issuedAt ? new Date(quotation.issuedAt) : new Date())}`,
      `ลูกค้า: ${quotation.customerName ?? ''}`,
      ...(quotation.projectName ? [`Project: ${quotation.projectName}`] : []),
      '',
      ...quotation.items.map((it, i) => `${i + 1}. ${it.description} — ${it.requestedQuantity} × ${it.finalUnitPrice}`),
    ];
    return Promise.resolve(mockDocPlaceholderBlob(lines));
  }
  const fmtNum = (n) => Number(n).toLocaleString('th-TH', { minimumFractionDigits: 2 });
  const rowsHtml = quotation.items.map((it, i) =>
    `<tr><td>${i + 1}</td><td>${it.description}</td><td style="text-align:right">${it.requestedQuantity}</td><td style="text-align:right">${fmtNum(it.finalUnitPrice)}</td><td style="text-align:right">${fmtNum(it.lineSubtotal)}</td></tr>`
  ).join('');
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
<div class="meta">เลขที่: <strong>${quotation.number}</strong> revision ${quotation.quotationRevisionNo} &nbsp;|&nbsp; ลูกค้า: <strong>${quotation.customerName ?? ''}</strong></div>
<table><thead><tr><th>#</th><th>รายละเอียด</th><th>จำนวน</th><th>ราคา/หน่วย</th><th>เป็นเงิน (บาท)</th></tr></thead>
<tbody>${rowsHtml}</tbody>
<tfoot><tr><td colspan="4" style="text-align:right;font-weight:700">รวมเป็นเงิน</td><td style="text-align:right;font-weight:700">${fmtNum(quotation.subtotalAmount)}</td></tr></tfoot></table>
<div class="total">VAT 7%: ${fmtNum(quotation.vatAmount)} บาท &nbsp;|&nbsp; รวมทั้งสิ้น: ${fmtNum(quotation.grandTotal)} บาท</div></body></html>`;
  return Promise.resolve(new Blob([html], { type: 'text/html;charset=utf-8' }));
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
    revisionNo: pr.revisionNo ?? 1,
    parentPricingRequestId: pr.parentPricingRequestId ?? null,
    submittedAt: pr.submittedAt ?? null,
    pickedUpAt: pr.pickedUpAt ?? null,
    cancelledAt: pr.cancelledAt ?? null,
    createdAt: pr.createdAt,
    updatedAt: pr.updatedAt,
    // Step 6: non-null once pricingRequests.confirmOrder has bridged this (terminal,
    // QUOTATION_ACCEPTED) request into the legacy ticket payment/deposit pipeline.
    orderConfirmedAt: pr.orderConfirmedAt ?? null,
  };
}

function buildPricingRequestDetail(pr) {
  return { summary: buildPricingRequestSummary(pr), items: pr.items, events: pr.events };
}

function requirePricingRequestViewable(id, user) {
  if (!PRICING_REQUEST_VIEWER_ROLES.includes(user.role)) fail('Forbidden', 403);
  const pr = findPricingRequestRaw(id);
  const ticket = db.tickets.find((t) => t.id === pr.ticketId);
  const draftOversight = user.role === 'ceo' || user.role === 'sales_manager';
  if (pr.status === 'DRAFT' && !draftOversight && ticket?.createdById !== user.id) {
    fail('Pricing request not found', 404);
  }
  if (user.role === 'sales' && ticket?.createdById !== user.id) fail('Forbidden', 403);
  return pr;
}

function requirePricingRequestDealActive(ticket) {
  if ((ticket?.lifecycle ?? 'ACTIVE') !== 'ACTIVE') {
    fail(`ดีลไม่ได้อยู่ในสถานะ ACTIVE (${ticket?.lifecycle}) จึงสร้าง/แก้ไขคำขอราคาไม่ได้`, 409);
  }
}

// Mirrors PricingRequestRepository.normalizeCurrency: trim + uppercase,
// blank collapses to null. Applied on both insert and update so the mock
// never stores a raw/mixed-case currency the real column wouldn't have.
function normalizePricingRequestCurrency(targetCurrency) {
  if (targetCurrency == null || targetCurrency.trim() === '') return null;
  return targetCurrency.trim().toUpperCase();
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
    // Mirrors PricingRequestItemRequest's @NotBlank requestedUnitBasis (V68,
    // financial-integrity review Finding B) — the machine-readable basis
    // PricingCostingService now normalizes the requested quantity against.
    if (!item.requestedUnitBasis?.trim()) {
      fail('requestedUnitBasis must not be blank', 400);
    }
    if (!item.quantityType?.trim()) {
      fail('quantityType must not be blank', 400);
    }
    // Mirrors PricingRequestService.validateItems: an item must actually name
    // a product somehow — a link to an existing deal line, a catalog
    // product, a model name, or a dedicated product description. Brand alone
    // is deliberately NOT sufficient (a brand with no model does not
    // identify a product), so Import never receives a request for a line
    // nobody can actually source.
    const identified = item.sourceTicketItemId != null || item.productId != null
      || Boolean(item.model?.trim()) || Boolean(item.productDescription?.trim());
    if (!identified) {
      fail(`รายการที่ ${index + 1}: ต้องระบุสินค้าที่ต้องการเสนอราคา (เลือกจากรายการในดีล หรือระบุรุ่น/รายละเอียด)`, 400);
    }
  });
}

// Mirrors PricingRequestRepository.snapshotCatalogSelections: for each item whose productId
// matches an ACTIVE catalog price, populates the catalog snapshot fields the same way the real
// UPDATE...FROM does — including catalog_brand = COALESCE(pri.brand, pp.grade) and
// factory = COALESCE(NULLIF(BTRIM(pri.factory), ''), f.name). An item whose productId does not
// resolve (null productId, or no ACTIVE price_list_version for that product's factory) is left
// untouched — submitPricingRequestCatalogGate below is what rejects those.
function snapshotPricingRequestCatalogSelections(pr) {
  for (const item of pr.items) {
    if (item.productId == null) continue;
    const product = mockProductPrices.find((p) => p.priceId === item.productId);
    if (!product) continue;
    const activeVersion = mockPriceImportVersions.find(
      (v) => v.factoryId === product.factoryId && v.status === 'ACTIVE');
    if (!activeVersion) continue;
    const factory = mockPriceImportFactories.find((f) => f.factoryId === product.factoryId);
    item.priceListVersionId = activeVersion.versionId;
    item.catalogPriceId = product.priceId;
    item.catalogBasePrice = product.price;
    item.catalogCurrency = product.currency;
    item.catalogEffectiveDate = activeVersion.createdAt ?? null;
    item.resolvedFactoryId = product.factoryId;
    item.resolvedFactoryName = factory?.name ?? product.factoryName ?? null;
    item.catalogProductCode = product.productCode ?? null;
    item.catalogBrand = item.brand?.trim() ? item.brand : (product.grade ?? null);
    item.catalogCollection = product.collection ?? null;
    item.catalogModel = product.productName ?? null;
    item.factory = item.factory?.trim() ? item.factory : (factory?.name ?? product.factoryName ?? item.factory);
  }
}

// Mirrors PricingRequestService.submit's Finding A gate (financial-integrity review, commit
// 3): the catalog is now MANDATORY — every item must have a fully-resolved catalog snapshot
// (run snapshotPricingRequestCatalogSelections first) or submit() 422s naming every failing
// 1-based line number in one message, same "รายการที่ N" style as the identity check above.
function submitPricingRequestCatalogGate(pr) {
  const missingLines = [];
  pr.items.forEach((item, index) => {
    if (item.catalogPriceId == null || item.priceListVersionId == null
        || item.catalogBasePrice == null || item.catalogCurrency == null
        || item.resolvedFactoryId == null || item.resolvedFactoryName == null) {
      missingLines.push(index + 1);
    }
  });
  if (missingLines.length > 0) {
    fail(`รายการที่ ${missingLines.join(', ')}: ต้องเลือกสินค้าจาก Price Catalog ที่ active ก่อนส่งคำขอราคา (ไม่พบข้อมูลราคา/โรงงานจาก catalog)`, 422);
  }
}

// Mirrors PricingCostingService.requireFactor/missingFactor (financial-integrity review
// Finding B): 422 naming both the item and the missing conversion factor, rather than letting
// a null factor silently propagate into a wrong number (or a NaN).
function mockRequireConversionFactor(value, pricingRequestItemId, factorName) {
  const numeric = Number(value);
  if (value == null || !(numeric > 0)) {
    fail(`Pricing request item ${pricingRequestItemId} is missing the ${factorName} conversion factor needed to normalize its price/quantity`, 422);
  }
  return numeric;
}

// Mirrors PricingCostingService.pricePerPiece: converts a raw factory-quoted price to a
// per-PIECE figure using the QUOTE's own unit basis.
function mockPricePerPiece(rawPrice, quotedUnitBasis, factors, pricingRequestItemId) {
  switch (quotedUnitBasis) {
    case 'PER_PIECE': return Number(rawPrice);
    case 'PER_BOX': return Number(rawPrice) / mockRequireConversionFactor(factors.piecesPerBox, pricingRequestItemId, 'piecesPerBox');
    case 'PER_SQM': return Number(rawPrice) * mockRequireConversionFactor(factors.sqmPerUnit, pricingRequestItemId, 'sqmPerUnit');
    case 'PER_LINEAR_M': return Number(rawPrice) * mockRequireConversionFactor(factors.linearMPerUnit, pricingRequestItemId, 'linearMPerUnit');
    default: fail(`Unsupported factory quote unit basis '${quotedUnitBasis}'`, 422); return null;
  }
}

// Mirrors PricingCostingService.quantityToPieces: converts a requested quantity to a PIECE
// count using the REQUEST's own unit basis — independent of the quote's own basis above.
function mockQuantityToPieces(requestedQty, requestedUnitBasis, factors, pricingRequestItemId) {
  switch (requestedUnitBasis) {
    case 'PER_PIECE': return Number(requestedQty);
    case 'PER_BOX': return Number(requestedQty) * mockRequireConversionFactor(factors.piecesPerBox, pricingRequestItemId, 'piecesPerBox');
    case 'PER_SQM': return Number(requestedQty) / mockRequireConversionFactor(factors.sqmPerUnit, pricingRequestItemId, 'sqmPerUnit');
    case 'PER_LINEAR_M': return Number(requestedQty) / mockRequireConversionFactor(factors.linearMPerUnit, pricingRequestItemId, 'linearMPerUnit');
    default: fail(`Unsupported requested unit basis '${requestedUnitBasis}'`, 422); return null;
  }
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
    // Step 8: who on the customer's side received/confirmed this delivery — optional, mirrors
    // TicketRepository.insertDeliveryRecord's new recipientName column (V78).
    recipientName: payload.recipientName ?? null,
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

// Step 9 cross-check threshold: flag (never block) when the hand-typed grossAmount diverges from
// the linked deal's actual payableAmount by more than this fraction. Mirrors
// CommissionService.MISMATCH_THRESHOLD.
const COMMISSION_MISMATCH_THRESHOLD = 0.05;

function commissionDealMismatch(grossAmount, payable) {
  const gross = Number(grossAmount) || 0;
  if (!payable) return gross > 0;
  return Math.abs(gross - payable) / Math.abs(payable) > COMMISSION_MISMATCH_THRESHOLD;
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
    dealPayableAmountSnapshot: null,
    dealAmountMismatch: false,
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

/**
 * Who this caller may see, mirroring AttendanceService.resolveScope. A non-manager asking for
 * someone else is a 403; a manager is silently narrowed to their division instead.
 */
function mockAttendanceScope(user, params = {}) {
  if (['hr', 'ceo'].includes(user.role)) {
    const requested = params.employeeId ? Number(params.employeeId) : null;
    // divisionId is a convenience filter for roles that already see everything — never a grant.
    // Compared as a string: the demo dataset keys divisions by code ('HRD'), while the real schema
    // uses an integer id. Number() would turn 'HRD' into NaN and silently disable the filter.
    const requestedDivision = params.divisionId ? String(params.divisionId) : null;
    let employees = db.employees;
    if (requestedDivision) {
      employees = employees.filter((employee) => String(employee.divisionId) === requestedDivision);
    }
    if (requested) {
      employees = employees.filter((employee) => employee.id === requested);
    }
    return { employees };
  }
  if (!user.employeeId) fail('User is not linked to an employee', 400);
  if (dashboardManager(user) && dashboardDivisionId(user)) {
    const division = db.employees.filter(
      (employee) => employee.divisionId === dashboardDivisionId(user),
    );
    const requested = params.employeeId ? Number(params.employeeId) : null;
    // Both predicates AND, so an out-of-division id matches nothing rather than leaking.
    return { employees: requested ? division.filter((e) => e.id === requested) : division };
  }
  if (params.employeeId && Number(params.employeeId) !== user.employeeId) fail('Forbidden', 403);
  const self = employeeForUser(user);
  return { employees: self ? [self] : [] };
}

const MOCK_UNMAPPED_BADGES = [
  {
    badge_code: '8801',
    punch_count: 12,
    first_seen: '2026-07-02T08:11:00+07:00',
    last_seen: '2026-07-17T17:48:00+07:00',
    site_code: 'SHOWROOM',
  },
  {
    badge_code: '9042',
    punch_count: 3,
    first_seen: '2026-07-09T08:31:00+07:00',
    last_seen: '2026-07-11T17:22:00+07:00',
    site_code: 'WAREHOUSE',
  },
];

function isoDate(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

function bangkokStamp(date, hour, minute) {
  return `${isoDate(date)}T${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}:00+07:00`;
}

/**
 * The eight shapes the day table has to render, cycled deterministically by (employee, day) so a
 * given date always looks the same and every badge is reachable without hunting.
 */
const MOCK_DAY_SHAPES = [
  { kind: 'present', inAt: [8, 24], outAt: [17, 35] },
  { kind: 'late', inAt: [8, 47], outAt: [17, 32], late: 17, midAt: [[12, 3], [13, 10], [15, 30]] },
  { kind: 'early', inAt: [8, 28], outAt: [16, 20], early: 70 },
  { kind: 'missingOut', inAt: [8, 31], outAt: null },
  { kind: 'overtime', inAt: [8, 20], outAt: [19, 0], overtime: 90, midAt: [[12, 15]] },
  { kind: 'present', inAt: [8, 12], outAt: [17, 40] },
  { kind: 'workedLate', inAt: [8, 26], outAt: [18, 40] },
  { kind: 'missingIn', inAt: null, outAt: [17, 25] },
  { kind: 'none' },
];

/**
 * Keyed on the employee's own id, never their position in the array. The drill-down fetches punches
 * for a single employee, so a position-based key would pick a different shape there than the day
 * list used and the expanded scans would contradict the row they came from.
 */
function mockDayShape(employee, date) {
  return MOCK_DAY_SHAPES[(date.getDate() + Number(employee.id)) % MOCK_DAY_SHAPES.length];
}

function mockAttendanceDays(employees, from, to) {
  const days = [];
  for (let cursor = new Date(to); cursor >= from; cursor.setDate(cursor.getDate() - 1)) {
    const date = new Date(cursor);
    const weekend = date.getDay() === 0 || date.getDay() === 6;
    employees.forEach((employee) => {
      const shape = weekend ? { kind: 'nonWorkday' } : mockDayShape(employee, date);
      days.push(mockAttendanceDay(employee, date, shape, weekend));
    });
  }
  return days;
}

function mockAttendanceDay(employee, date, shape, weekend) {
  const base = {
    employee_id: employee.id,
    employee_code: employee.code,
    employee_name: employee.nameTh,
    nick_name: employee.nickname ?? null,
    position_th: employee.positionTh ?? null,
    work_date: isoDate(date),
    is_workday: !weekend,
    check_in: null,
    check_out: null,
    total_minutes: null,
    late_minutes: 0,
    early_leave_minutes: 0,
    overtime_minutes: 0,
    punch_count: 0,
    site_code: null,
    status: 'NO_RECORD',
    flags: [],
    is_manual_override: false,
    notes: null,
  };

  if (shape.kind === 'none') return base;
  if (shape.kind === 'nonWorkday') {
    return { ...base, status: 'NON_WORKDAY', flags: ['NON_WORKDAY'] };
  }

  const checkIn = shape.inAt ? bangkokStamp(date, shape.inAt[0], shape.inAt[1]) : null;
  const checkOut = shape.outAt ? bangkokStamp(date, shape.outAt[0], shape.outAt[1]) : null;
  const flags = [];
  let status = 'PRESENT';

  if (!checkIn) {
    status = 'MISSING_CHECK_IN';
    flags.push('MISSING_CHECK_IN');
  } else if (!checkOut) {
    status = 'MISSING_CHECK_OUT';
    flags.push('MISSING_CHECK_OUT');
  }
  if (shape.late) {
    status = 'LATE';
    flags.push('LATE');
  }
  if (shape.early) flags.push('EARLY_LEAVE');
  if (shape.overtime) flags.push('OVERTIME_APPROVED');
  if (shape.kind === 'workedLate') flags.push('WORKED_LATE_UNAPPROVED');

  const totalMinutes = checkIn && checkOut
    ? (shape.outAt[0] * 60 + shape.outAt[1]) - (shape.inAt[0] * 60 + shape.inAt[1])
    : null;

  return {
    ...base,
    check_in: checkIn,
    check_out: checkOut,
    total_minutes: totalMinutes,
    late_minutes: shape.late ?? 0,
    early_leave_minutes: shape.early ?? 0,
    overtime_minutes: shape.overtime ?? 0,
    // Counts every scan, not just the two that became check-in/out — that difference is exactly
    // what the day row's "N ครั้ง" affordance surfaces.
    punch_count: (checkIn ? 1 : 0) + (checkOut ? 1 : 0) + (shape.midAt?.length ?? 0),
    site_code: 'SHOWROOM',
    status,
    flags,
  };
}

/** The raw scans behind one day, for the drill-down. */
function mockAttendancePunches(employees, params) {
  const workDate = params.from || params.to;
  if (!workDate || employees.length === 0) return [];
  const date = new Date(workDate);
  const weekend = date.getDay() === 0 || date.getDay() === 6;
  if (weekend) return [];

  return employees.flatMap((employee) => {
    const shape = mockDayShape(employee, date);
    if (shape.kind === 'none' || shape.kind === 'nonWorkday') return [];
    // Chronological, so the consumer sees the same first/last the backend derived by MIN/MAX.
    const stamps = [shape.inAt, ...(shape.midAt ?? []), shape.outAt].filter(Boolean);
    return stamps.map((stamp, position) => ({
      punch_id: employee.id * 1000 + date.getDate() * 10 + position,
      employee_id: employee.id,
      employee_code: employee.code,
      employee_name: employee.nameTh,
      nick_name: employee.nickname ?? null,
      position_th: employee.positionTh ?? null,
      punch_time: bangkokStamp(date, stamp[0], stamp[1]),
      work_date: isoDate(date),
      site_code: 'SHOWROOM',
      device_code: 'SHOWROOM_SC700',
      device_name: 'เครื่องสแกนโชว์รูม',
    }));
  });
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

// Mirrors OvertimeService.validateRetroactiveWindow(). Advance notice was removed, so same-day and
// backdated requests are accepted — bounded by how far back they reach and by a reason that
// explains the delay.
//
// KNOWN GAP: the Java service also refuses a work date whose payroll month is already PROCESSED
// (OvertimeService.requirePayrollMonthOpen). There is no payroll_period collection in this mock, so
// that rule is absent here. The mock is therefore more permissive than prod on that one case — the
// dangerous direction, so do not read a successful mock submit as proof the backend would accept it.
const OT_RETROACTIVE_WINDOW_DAYS = 60;
const OT_BACKDATED_REASON_MIN_LENGTH = 20;

function validateOvertimeRetroactiveWindow(payload) {
  const workDate = payload.workDate;
  if (!workDate) return;
  const today = new Date().toISOString().slice(0, 10);
  if (workDate >= today) return;
  const earliest = new Date(Date.now() - OT_RETROACTIVE_WINDOW_DAYS * 86400000)
    .toISOString().slice(0, 10);
  if (workDate < earliest) {
    fail(`Overtime can only be filed up to ${OT_RETROACTIVE_WINDOW_DAYS} days after the work date`, 400);
  }
  const reason = (payload.reason || '').trim();
  if (reason.length < OT_BACKDATED_REASON_MIN_LENGTH) {
    fail('A retroactive overtime request must explain clearly why it is late', 400);
  }
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

// Mirrors SpecialMoneyType.java (name -> label/bucket/evidence). Kept as a plain
// object here (not derived from db) since GET /api/special-money/types is a
// static catalog, same as the Java controller's `Arrays.stream(SpecialMoneyType.values())`.
const SPECIAL_MONEY_TYPES = [
  { requestType: 'UNIFORM_ANNUAL', thaiLabel: 'ชุดฟอร์มประจำปี', payrollBucket: 'NON_TAXABLE', evidenceRequired: true },
  { requestType: 'UNIFORM_NEW_STAFF', thaiLabel: 'ชุดฟอร์มพนักงานใหม่', payrollBucket: 'NON_TAXABLE', evidenceRequired: true },
  { requestType: 'UNIFORM_PREPROBATION_KIT', thaiLabel: 'ชุดพนักงานก่อนผ่านทดลองงาน', payrollBucket: 'NON_TAXABLE', evidenceRequired: true },
  { requestType: 'TRAVEL_PER_DIEM', thaiLabel: 'เบี้ยเลี้ยงเดินทาง', payrollBucket: 'PER_DIEM', evidenceRequired: false },
  { requestType: 'TRAVEL_LODGING', thaiLabel: 'ค่าที่พัก', payrollBucket: 'PER_DIEM', evidenceRequired: true },
  { requestType: 'MEDICAL', thaiLabel: 'ค่ารักษาพยาบาล', payrollBucket: 'NON_TAXABLE', evidenceRequired: true },
  { requestType: 'AID_WEDDING', thaiLabel: 'เงินช่วยเหลืองานแต่งงาน', payrollBucket: 'AID', evidenceRequired: true },
  { requestType: 'AID_ORDINATION', thaiLabel: 'เงินช่วยเหลืองานบวช', payrollBucket: 'AID', evidenceRequired: true },
  { requestType: 'AID_CHILDBIRTH', thaiLabel: 'เงินช่วยเหลือคลอดบุตร', payrollBucket: 'AID', evidenceRequired: true },
  { requestType: 'AID_FUNERAL', thaiLabel: 'เงินช่วยเหลืองานศพ', payrollBucket: 'AID', evidenceRequired: true },
  { requestType: 'TRAINING', thaiLabel: 'สนับสนุนการฝึกอบรม', payrollBucket: 'NON_TAXABLE', evidenceRequired: true },
  { requestType: 'OTHER', thaiLabel: 'อื่นๆ', payrollBucket: 'AID', evidenceRequired: true },
];

function specialMoneyType(requestType) {
  return SPECIAL_MONEY_TYPES.find((item) => item.requestType === requestType) || null;
}

// Mirrors SpecialMoneyService.managesEmployee(): direct report (stored FK) OR
// division manager (position-derived user.manager() sharing the employee's
// division, excluding self) -- NO hr/admin bypass here either, same shape as
// canReviewOvertime and deliberately not shared with it (see that function's
// comment: these gates encode distinct Java classes and must not be merged).
function canReviewSpecialMoney(user, employeeId) {
  if (!user.employeeId || employeeId === user.employeeId) return false;
  const employee = findEmployee(employeeId);
  const directReport = managerIdForEmployee(employee) === user.employeeId;
  const divisionManager = dashboardManager(user)
    && dashboardDivisionId(user) != null
    && dashboardDivisionId(user) === employee.divisionId
    && employeeId !== user.employeeId;
  return directReport || divisionManager;
}

function canViewAllSpecialMoney(user) {
  return ['hr', 'ceo'].includes(user.role);
}

function canAccessSpecialMoneyEmployee(user, employeeId) {
  return canViewAllSpecialMoney(user)
    || employeeId === user.employeeId
    || canReviewSpecialMoney(user, employeeId);
}

function buildSpecialMoneyRecord(record) {
  const employee = db.employees.find((item) => item.id === record.employeeId);
  const managerEmployeeId = managerIdForEmployee(employee);
  const manager = managerEmployeeId ? db.employees.find((item) => item.id === managerEmployeeId) : null;
  const requestedBy = record.requestedById ? db.employees.find((item) => item.id === record.requestedById) : null;
  const managerApprover = record.managerApprovedBy ? db.employees.find((item) => item.id === record.managerApprovedBy) : null;
  const ceoApprover = record.ceoApprovedBy ? db.employees.find((item) => item.id === record.ceoApprovedBy) : null;
  const reviewer = record.reviewedById ? db.employees.find((item) => item.id === record.reviewedById) : null;
  return {
    ...structuredClone(record),
    employeeCode: employee?.code || null,
    employeeName: employee?.nameTh || null,
    managerEmployeeId,
    managerName: manager?.nameTh || null,
    requestedByName: requestedBy?.nameTh || null,
    managerApprovedByName: managerApprover?.nameTh || null,
    ceoApprovedByName: ceoApprover?.nameTh || null,
    reviewedByName: reviewer?.nameTh || null,
  };
}

function specialMoneyPayrollMonth() {
  // Mirrors SpecialMoneyService#ceoApprove's 25th-of-month cutoff
  // (app.special-money.payroll-cutoff-day, default 25): approved on/before the
  // 25th lands in the current month, after rolls to next month. The mock has
  // no payroll-period table to roll further past, so it stops there.
  const now = new Date();
  const cutoffDay = 25;
  const target = now.getDate() <= cutoffDay
    ? new Date(now.getFullYear(), now.getMonth(), 1)
    : new Date(now.getFullYear(), now.getMonth() + 1, 1);
  return target.toISOString().slice(0, 10);
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

// Demo credentials for each role — mirrors V21__demo_seed_accounts.sql
const DEMO_ROLE_EMAIL = {
  sales:         'demo.sales@demo.invalid',
  hr:            'demo.hr@demo.invalid',
  ceo:           'demo.ceo@demo.invalid',
  import:        'demo.import@demo.invalid',
  sales_manager: 'demo.salesmanager@demo.invalid',
  employee:      'demo.employee@demo.invalid',
  account:       'demo.import@demo.invalid', // no dedicated account demo
};

// Try a real backend fetch (credentials included) and return the Blob, or null on failure.
async function tryBackendBlob(url) {
  try {
    const res = await fetch(url, { credentials: 'include' });
    if (res.ok) return res.blob();
  } catch { /* backend offline or not authed */ }
  return null;
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

      // Fire-and-forget: also create a real backend session so document downloads
      // (quotation XLS/PDF, deposit notice, remaining invoice) return real files.
      const demoEmail = DEMO_ROLE_EMAIL[requestedRole ?? user.role];
      if (demoEmail) {
        fetch('/api/auth/login', {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ email: demoEmail, password: 'Demo@2026' }),
        }).catch(() => {});
      }

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
      // Step 9 addition: additive filter on the deal pipeline stage (e.g. CLOSED_PAID) — mirrors
      // TicketController's new `salesStage` query param, used by the commission "Linked Deal" picker.
      if (params.salesStage) list = list.filter((t) => t.salesStage === params.salesStage);
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
        recipientName: payload.recipientName ?? null,
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
      const blob = await tryBackendBlob(`/api/tickets/${ticketId}/quotations/${quotationId}/file?format=xlsx`);
      return blob ?? buildMockQuotationXlsx(ticketId, quotationId);
    },

    async downloadQuotationPdf(ticketId, quotationId) {
      const blob = await tryBackendBlob(`/api/tickets/${ticketId}/quotations/${quotationId}/file?format=pdf`);
      return blob ?? buildMockQuotationHtml(ticketId, quotationId);
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
      const blob = await tryBackendBlob(`/api/tickets/${id}/remaining-invoice/file`);
      return blob ?? buildMockRemainingInvoiceXlsx(Number(id));
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
      validateOvertimeRetroactiveWindow(payload);
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

  // Mirrors SpecialMoneyController + SpecialMoneyService (specialmoney/) --
  // same manager/CEO gate shapes as overtime (see canReviewSpecialMoney's
  // comment for why it is not shared with canReviewOvertime), but cancel is
  // stricter: only the employee or the person who filed on their behalf, and
  // only while still SUBMITTED (no manager-cancel across every active status
  // the way overtime allows). This mock does NOT reimplement the full policy
  // cap/eligibility engine (SpecialMoneyPolicyEvaluator) -- it approximates
  // authorization and status transitions faithfully, but `create` accepts
  // whatever requestedAmount the caller sends without recomputing/clamping it
  // against the policy caps. That enforcement is Java-only; never treat a mock
  // "successful" submit as proof the real cap logic was exercised.
  specialMoney: {
    async employees() {
      const user = requireSession();
      const includeAll = canViewAllSpecialMoney(user);
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

    async types() {
      requireSession();
      return delay({ types: SPECIAL_MONEY_TYPES });
    },

    async usage(params = {}) {
      const user = requireSession();
      const employeeId = params.employeeId ? Number(params.employeeId) : null;
      if (!employeeId) fail('employeeId is required', 400);
      if (!canAccessSpecialMoneyEmployee(user, employeeId)) fail('Forbidden', 403);
      const year = params.year ? Number(params.year) : new Date().getFullYear();
      const approvedAmountThisYearByType = {};
      const approvedCountLifetimeByType = {};
      db.specialMoneyRequests
        .filter((item) => item.employeeId === employeeId && item.status === 'APPROVED')
        .forEach((item) => {
          approvedCountLifetimeByType[item.requestType] = (approvedCountLifetimeByType[item.requestType] || 0) + 1;
          if (new Date(item.eventDate).getFullYear() === year) {
            approvedAmountThisYearByType[item.requestType] =
              (approvedAmountThisYearByType[item.requestType] || 0) + Number(item.approvedAmount || 0);
          }
        });
      return delay({
        usage: { employeeId, year, approvedAmountThisYearByType, approvedCountLifetimeByType },
      });
    },

    async list(params = {}) {
      const user = requireSession();
      let list = db.specialMoneyRequests;
      const includeAll = canViewAllSpecialMoney(user);
      if (!includeAll) {
        if (params.employeeId && !canAccessSpecialMoneyEmployee(user, Number(params.employeeId))) fail('Forbidden', 403);
        list = list.filter((item) => item.employeeId === user.employeeId || canReviewSpecialMoney(user, item.employeeId));
      }
      if (params.employeeId) list = list.filter((item) => item.employeeId === Number(params.employeeId));
      if (params.status) list = list.filter((item) => item.status === params.status);
      if (params.type) list = list.filter((item) => item.requestType === params.type);
      if (params.from) list = list.filter((item) => item.eventDate >= params.from);
      if (params.to) list = list.filter((item) => item.eventDate <= params.to);
      return delay({ requests: list.map(buildSpecialMoneyRecord) });
    },

    async create(payload) {
      const user = requireSession();
      const actorEmployeeId = user.employeeId;
      if (!actorEmployeeId) fail('User is not linked to an employee', 400);
      const employeeId = payload.employeeId ? Number(payload.employeeId) : actorEmployeeId;
      // Filing on another employee's behalf is manager-only, not HR -- mirrors
      // SpecialMoneyService.resolveTargetEmployee(), which has no hr/admin
      // bypass ("Employees can only submit their own special-money requests").
      if (employeeId !== actorEmployeeId && !canReviewSpecialMoney(user, employeeId)) fail('Forbidden', 403);
      findEmployee(employeeId);
      const type = specialMoneyType(payload.requestType);
      if (!type) fail('Invalid special money request type', 400);
      if (!payload.eventDate) fail('eventDate is required', 400);
      if (!payload.requestedAmount || Number(payload.requestedAmount) <= 0) fail('requestedAmount must be positive', 400);
      if (!payload.reason || !payload.reason.trim()) fail('reason is required', 400);

      const id = Math.max(0, ...db.specialMoneyRequests.map((item) => item.id)) + 1;
      const now = new Date().toISOString();
      const request = {
        id,
        employeeId,
        requestType: payload.requestType,
        eventDate: payload.eventDate,
        eventEndDate: payload.eventEndDate || null,
        receiptDate: payload.receiptDate || null,
        quantity: payload.quantity ?? 1,
        requestedAmount: Number(payload.requestedAmount),
        approvedAmount: null,
        payrollBucket: type.payrollBucket,
        policyVersion: 1,
        reason: payload.reason.trim(),
        detail: payload.detail || {},
        status: 'SUBMITTED',
        payrollMonth: null,
        capOverrideReason: null,
        requestedById: actorEmployeeId,
        requestedAt: now,
        managerApprovedBy: null,
        managerApprovedAt: null,
        ceoApprovedBy: null,
        ceoApprovedAt: null,
        reviewedById: null,
        reviewedAt: null,
        reviewerNote: null,
        cancelledAt: null,
        createdAt: now,
        updatedAt: now,
      };
      db.specialMoneyRequests.unshift(request);
      return delay({ request: buildSpecialMoneyRecord(request) });
    },

    async approve(id, payload = {}) {
      const user = requireSession();
      const request = db.specialMoneyRequests.find((item) => item.id === Number(id));
      if (!request) fail('Special money request not found', 404);
      const now = new Date().toISOString();
      if (request.status === 'SUBMITTED') {
        if (!canReviewSpecialMoney(user, request.employeeId)) fail('Forbidden', 403);
        request.status = 'MANAGER_APPROVED';
        request.managerApprovedBy = user.employeeId;
        request.managerApprovedAt = now;
        request.reviewedById = user.employeeId;
        request.reviewedAt = now;
        request.reviewerNote = payload.reviewerNote || null;
        request.updatedAt = now;
        return delay({ request: buildSpecialMoneyRecord(request) });
      }
      if (request.status === 'MANAGER_APPROVED') {
        if (user.role !== 'ceo') fail('Only the CEO can approve manager-approved special-money requests', 403);
        request.status = 'APPROVED';
        request.approvedAmount = payload.approvedAmount != null ? Number(payload.approvedAmount) : request.requestedAmount;
        request.capOverrideReason = payload.capOverrideReason || null;
        request.payrollMonth = specialMoneyPayrollMonth();
        request.ceoApprovedBy = user.employeeId;
        request.ceoApprovedAt = now;
        request.reviewedById = user.employeeId;
        request.reviewedAt = now;
        request.reviewerNote = payload.reviewerNote || request.reviewerNote;
        request.updatedAt = now;
        return delay({ request: buildSpecialMoneyRecord(request) });
      }
      fail('Special money request has already been reviewed', 409);
    },

    async reject(id, payload = {}) {
      const user = requireSession();
      const request = db.specialMoneyRequests.find((item) => item.id === Number(id));
      if (!request) fail('Special money request not found', 404);
      const now = new Date().toISOString();
      if (request.status === 'SUBMITTED') {
        if (!canReviewSpecialMoney(user, request.employeeId)) fail('Forbidden', 403);
        request.status = 'REJECTED';
        request.reviewedById = user.employeeId;
        request.reviewedAt = now;
        request.reviewerNote = payload.reviewerNote || null;
        request.updatedAt = now;
        return delay({ request: buildSpecialMoneyRecord(request) });
      }
      if (request.status === 'MANAGER_APPROVED') {
        if (user.role !== 'ceo') fail('Only the CEO can approve manager-approved special-money requests', 403);
        request.status = 'REJECTED';
        request.reviewedById = user.employeeId;
        request.reviewedAt = now;
        request.reviewerNote = payload.reviewerNote || null;
        request.updatedAt = now;
        return delay({ request: buildSpecialMoneyRecord(request) });
      }
      fail('Special money request has already been reviewed', 409);
    },

    // Stricter than overtime.cancel: only the employee themselves or whoever
    // filed on their behalf (requestedById), and only while still SUBMITTED --
    // mirrors SpecialMoneyService.cancel(), which has no manager-cancel path
    // for MANAGER_APPROVED/APPROVED the way OvertimeService does.
    async cancel(id, payload = {}) {
      const user = requireSession();
      const request = db.specialMoneyRequests.find((item) => item.id === Number(id));
      if (!request) fail('Special money request not found', 404);
      const isEmployee = request.employeeId === user.employeeId;
      const isRequester = request.requestedById != null && request.requestedById === user.employeeId;
      if (!isEmployee && !isRequester) fail('Forbidden', 403);
      if (request.status !== 'SUBMITTED') fail('Only submitted special money requests can be cancelled', 409);
      const now = new Date().toISOString();
      request.status = 'CANCELLED';
      request.cancelledAt = now;
      request.reviewerNote = payload.reviewerNote || request.reviewerNote;
      request.updatedAt = now;
      return delay({ request: buildSpecialMoneyRecord(request) });
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
      // Step 9 gate + cross-check — mirrors CommissionService#resolveDealLinkage exactly. Unlinked
      // (sourceTicketId absent) commissions are unaffected: snapshot stays null, mismatch false.
      let dealPayableAmountSnapshot = null;
      let dealAmountMismatch = false;
      if (payload.sourceTicketId != null) {
        const linkedTicket = db.tickets.find((t) => t.id === Number(payload.sourceTicketId));
        if (!linkedTicket) fail('Ticket not found', 404);
        if (linkedTicket.salesStage !== 'CLOSED_PAID') {
          fail('Deal has not reached final payment (CLOSED_PAID); commission cannot be submitted yet.', 422);
        }
        dealPayableAmountSnapshot = payableAmount(linkedTicket);
        dealAmountMismatch = commissionDealMismatch(payload.grossAmount, dealPayableAmountSnapshot);
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
        dealPayableAmountSnapshot,
        dealAmountMismatch,
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
    // C1/C2 reconciliation additions (2026-07-21): same "view broader than edit" split as the rest
    // of this namespace (GET is hr/ceo, PUT is hr-only). Like preview/process above, these carry
    // real payroll numbers (tax allowances, YTD income), so mock mode surfaces a clear
    // "not supported" error on writes rather than fabricating figures; GET returns an empty list so
    // the UI's empty state renders correctly.
    async getTaxAllowances() {
      hasRole('hr', 'ceo');
      return delay({ taxYear: new Date().getFullYear(), items: [] });
    },
    async saveTaxAllowances() {
      hasRole('hr');
      throw new Error('บันทึกค่าลดหย่อนภาษีไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
    async getYtdSeed() {
      hasRole('hr', 'ceo');
      return delay({ taxYear: new Date().getFullYear(), items: [] });
    },
    async saveYtdSeed() {
      hasRole('hr');
      throw new Error('บันทึกยอดสะสมต้นปีไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
    },
  },

  // Mirrors AttendanceController + AttendanceService (attendance/).
  //
  // `daily` and `list` have no top-level role gate — AttendanceService.resolveScope
  // scopes by role instead (hr/ceo see all; a ฝ่าย manager — dashboardManager() —
  // is scoped to their division with no 403; everyone else is 403'd for requesting
  // another employeeId). `unmapped`, `devices`, `importDat` and `backfillCards` are
  // hr/ceo-only at the controller (AttendanceController.java).
  //
  // Days are generated rather than stored so the fixture never goes stale, and the
  // pattern deliberately produces every flag the UI renders — on time, late, early
  // out, missing each scan, approved OT, unapproved late stay, non-workday and
  // no-record — so the whole status vocabulary is reachable in mock mode.
  //
  // AUTHZ IS NOT AUTHORITATIVE HERE: verify permission behaviour against the Java
  // service, never against this file.
  attendance: {
    async daily(params = {}) {
      const user = requireSession();
      const scope = mockAttendanceScope(user, params);
      const to = params.to ? new Date(params.to) : new Date();
      const from = params.from ? new Date(params.from) : new Date(to.getFullYear(), to.getMonth(), 1);
      return delay({ days: mockAttendanceDays(scope.employees, from, to) });
    },
    // Backs the per-day drill-down: the raw scans behind one day's first/last.
    async list(params = {}) {
      const user = requireSession();
      const scope = mockAttendanceScope(user, params);
      return delay({ punches: mockAttendancePunches(scope.employees, params) });
    },
    async unmapped() {
      hasRole('hr', 'ceo');
      return delay({ badges: MOCK_UNMAPPED_BADGES });
    },
    async employees() {
      const user = requireSession();
      return delay({
        employees: mockAttendanceScope(user, {}).employees.map((employee) => ({
          employee_id: employee.id,
          employee_code: employee.code,
          employee_name: employee.nameTh,
          nick_name: employee.nickname ?? null,
          department_name: employee.departmentTh ?? null,
          division_id: employee.divisionId ?? null,
          division_name: employee.divisionTh ?? null,
        })),
      });
    },
    async recalculate() {
      hasRole('hr', 'ceo');
      // The mock generates days on the fly, so there is nothing to roll up — report zero rather
      // than pretending work happened. The real endpoint returns the count actually written.
      return delay({ recalculatedDays: 0 });
    },
    async backfillCards() {
      hasRole('hr', 'ceo');
      throw new Error('แก้ไขการแมปบัตรไม่รองรับในโหมดทดลองใช้งาน (mock mode)');
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
      const backendBlob = await tryBackendBlob(`/api/deposit-notices/${docId}/file?format=xlsx`);
      if (backendBlob) return backendBlob;
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
      const blob = await tryBackendBlob(`/api/deposit-notices/${docId}/file?format=pdf`);
      if (blob) return blob;
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
      const draftOversight = user.role === 'ceo' || user.role === 'sales_manager';
      const items = mockPricingRequests
        .filter((pr) => pr.ticketId === Number(ticketId))
        .filter((pr) => pr.status !== 'DRAFT' || draftOversight || ticket.createdById === user.id)
        .map(buildPricingRequestSummary);
      return delay({ items });
    },

    async queue(params = {}) {
      const user = requireSession();
      // Mirrors PricingRequestService.list: same viewer roles as a single
      // request, plus sales is scoped to only its own created tickets.
      if (!PRICING_REQUEST_VIEWER_ROLES.includes(user.role)) fail('Forbidden', 403);
      if (params.status && ![
        'DRAFT',
        'SUBMITTED',
        'IMPORT_REVIEWING',
        'AWAITING_FACTORY_RESPONSE',
        'COSTING_IN_PROGRESS',
        'READY_FOR_CEO_REVIEW',
        'MORE_INFO_REQUIRED',
        'CANCELLED',
        'SUPERSEDED',
      ].includes(params.status)) {
        fail(`Unknown status '${params.status}'`, 400);
      }
      let list = mockPricingRequests;
      if (user.role === 'sales') {
        list = list.filter((pr) => db.tickets.find((t) => t.id === pr.ticketId)?.createdById === user.id);
      }
      // Mirrors PricingRequestService.list's draft-privacy clause: a DRAFT is the
      // owning rep's private scratchpad, visible only to them plus ceo/sales_manager
      // oversight. import must not see it in the queue even though it sees
      // every other status. Without this the mock is MORE permissive than the Java
      // service, which is the direction that only surfaces in production (#199).
      const draftOversight = user.role === 'ceo' || user.role === 'sales_manager';
      list = list.filter((pr) => pr.status !== 'DRAFT'
        || draftOversight
        || db.tickets.find((t) => t.id === pr.ticketId)?.createdById === user.id);
      if (params.status) {
        list = list.filter((pr) => pr.status === params.status);
      } else {
        // Mirrors the same method's default-queue branch: dead rows do not pollute it.
        list = list.filter((pr) => pr.status !== 'CANCELLED');
      }
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
      if (!payload.clientRequestId || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(payload.clientRequestId)) {
        fail('clientRequestId must be a UUID', 400);
      }
      const existing = mockPricingRequests.find((pr) => (
        pr.requestedById === user.id && pr.clientRequestId === payload.clientRequestId
      ));
      if (existing) {
        if (existing.ticketId !== Number(ticketId)) fail('clientRequestId has already been used for a different ticket', 409);
        return delay({ pricingRequest: buildPricingRequestDetail(existing) });
      }
      if (!PRICING_REQUEST_RECIPIENT_VALUES.includes(payload.recipientType)) {
        fail(`Unknown recipient type '${payload.recipientType}'`, 400);
      }
      if (!payload.items?.length) fail('items must not be empty', 400);
      requirePricingRequestItemFieldsValid(payload.items);
      for (const item of payload.items) {
        if (!PRICING_REQUEST_QUANTITY_TYPE_VALUES.includes(item.quantityType)) {
          fail(`Unknown quantity type '${item.quantityType}'`, 400);
        }
        // Mirrors PricingRequestService.validateItems's UnitBasis.isValid check.
        if (!UNIT_BASIS_VALUES.includes(item.requestedUnitBasis)) {
          fail(`Unknown requestedUnitBasis '${item.requestedUnitBasis}'`, 400);
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
        productDescription: item.productDescription ?? null,
        color: item.color ?? null,
        texture: item.texture ?? null,
        size: item.size ?? null,
        factory: item.factory ?? null,
        requestedQty: item.requestedQty,
        requestedQtySqm: item.requestedQtySqm ?? null,
        requestedUnit: item.requestedUnit,
        requestedUnitBasis: item.requestedUnitBasis,
        quantityType: item.quantityType,
        targetDeliveryDate: item.targetDeliveryDate ?? null,
        deliveryLocation: item.deliveryLocation ?? null,
        specialRequirement: item.specialRequirement ?? null,
        sortOrder: i,
        // Catalog snapshot (Finding A, financial-integrity review commit 3) — populated only
        // by submit()'s snapshotCatalogSelections mirror below, never at create/update time,
        // matching PricingRequestRepository.create/updateDraft/snapshotCatalogSelections.
        priceListVersionId: null,
        catalogPriceId: null,
        catalogBasePrice: null,
        catalogCurrency: null,
        catalogEffectiveDate: null,
        resolvedFactoryId: null,
        resolvedFactoryName: null,
        catalogProductCode: null,
        catalogBrand: null,
        catalogCollection: null,
        catalogModel: null,
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
        targetCurrency: normalizePricingRequestCurrency(payload.targetCurrency),
        note: payload.note ?? null,
        clientRequestId: payload.clientRequestId,
        // DB column is `revision_no INTEGER NOT NULL DEFAULT 1` with
        // `chk_pricing_request_revision CHECK (revision_no >= 1)` (V58) — a
        // mock row starting at 0 would violate that constraint in production.
        revisionNo: 1, parentPricingRequestId: null,
        submittedAt: null, pickedUpAt: null, cancelledAt: null,
        createdAt: now, updatedAt: now,
        items, events: [],
        // Sales-level supporting attachments (V69, review remediation COMMIT 4) — distinct from
        // a factory quote's own `attachments` array above.
        attachments: [],
      };
      // Deliberately no notification, no ticket status change — a draft is the
      // rep's private scratchpad until submit() (mirrors createDraft's Javadoc).
      pushPricingRequestEvent(pr, user, 'PRICING_REQUEST_CREATED', null, 'DRAFT');
      mockPricingRequests.push(pr);
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },

    async update(id, payload) {
      // Mirrors PricingRequestService.updateDraft: owner sales, DRAFT only.
      // Review-remediation plan Fix 2 made this a FULL-REPLACEMENT PUT (in
      // sync with PricingRequestRepository.updateDraft dropping its COALESCE)
      // — every editable scalar field is validated and applied unconditionally
      // against the payload exactly as sent, never merged with the existing
      // row first. This mock used to distinguish `undefined` (field omitted,
      // "leave unchanged") from `null` (field explicitly cleared) for these
      // columns, which was actually MORE permissive than the real
      // UpdatePricingRequestRequest Java record ever was: Jackson deserializes
      // a missing JSON key the same way it deserializes an explicit `null` —
      // there is no wire-level way to distinguish the two on the real
      // backend — so "omitted" and "null" must collapse to the same outcome
      // here too. recipientType is additionally required (recipient_type is
      // NOT NULL in the real schema), validated unconditionally like create().
      const user = hasRole('sales');
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (ticket?.createdById !== user.id) fail('Forbidden', 403);
      if (pr.status !== 'DRAFT') fail(`Expected status 'DRAFT' but pricing request is '${pr.status}'`, 409);
      if (!payload.recipientType?.trim()) fail('recipientType must not be blank', 400);
      if (!PRICING_REQUEST_RECIPIENT_VALUES.includes(payload.recipientType)) {
        fail(`Unknown recipient type '${payload.recipientType}'`, 400);
      }
      if (payload.recipientContactId == null && !payload.recipientLabel?.trim()) {
        fail('ต้องระบุผู้รับคำขอราคา (recipientContactId หรือ recipientLabel)', 400);
      }
      if (payload.items != null) {
        requirePricingRequestItemFieldsValid(payload.items);
        for (const item of payload.items) {
          if (!PRICING_REQUEST_QUANTITY_TYPE_VALUES.includes(item.quantityType)) {
            fail(`Unknown quantity type '${item.quantityType}'`, 400);
          }
          if (!UNIT_BASIS_VALUES.includes(item.requestedUnitBasis)) {
            fail(`Unknown requestedUnitBasis '${item.requestedUnitBasis}'`, 400);
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

      pr.recipientType = payload.recipientType;
      pr.recipientContactId = payload.recipientContactId ?? null;
      pr.recipientLabel = payload.recipientLabel ?? null;
      pr.requiredDate = payload.requiredDate ?? null;
      pr.customerTargetPrice = payload.customerTargetPrice ?? null;
      pr.targetCurrency = normalizePricingRequestCurrency(payload.targetCurrency);
      pr.note = payload.note ?? null;
      if (payload.items != null) {
        pr.items = payload.items.map((item, i) => ({
          id: mockPricingRequestItemSeq++,
          pricingRequestId: pr.id,
          sourceTicketItemId: item.sourceTicketItemId ?? null,
          productId: item.productId ?? null,
          variantId: item.variantId ?? null,
          brand: item.brand ?? null,
          model: item.model ?? null,
          productDescription: item.productDescription ?? null,
          color: item.color ?? null,
          texture: item.texture ?? null,
          size: item.size ?? null,
          factory: item.factory ?? null,
          requestedQty: item.requestedQty,
          requestedQtySqm: item.requestedQtySqm ?? null,
          requestedUnit: item.requestedUnit,
          requestedUnitBasis: item.requestedUnitBasis,
          quantityType: item.quantityType,
          targetDeliveryDate: item.targetDeliveryDate ?? null,
          deliveryLocation: item.deliveryLocation ?? null,
          specialRequirement: item.specialRequirement ?? null,
          sortOrder: i,
          // See create()'s identical block above for why these start null.
          priceListVersionId: null,
          catalogPriceId: null,
          catalogBasePrice: null,
          catalogCurrency: null,
          catalogEffectiveDate: null,
          resolvedFactoryId: null,
          resolvedFactoryName: null,
          catalogProductCode: null,
          catalogBrand: null,
          catalogCollection: null,
          catalogModel: null,
        }));
      }
      pr.updatedAt = new Date().toISOString();
      pushPricingRequestEvent(pr, user, 'PRICING_REQUEST_UPDATED', 'DRAFT', 'DRAFT');
      return delay({ pricingRequest: buildPricingRequestDetail(pr) });
    },

    async generateFactoryEmailDrafts(id) {
      const user = hasRole('import');
      const pr = findPricingRequestRaw(id);
      if (!['IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS'].includes(pr.status)) {
        fail('Pricing request must be under Import review before factory quote drafts can be generated', 409);
      }
      const byFactory = new Map();
      for (const item of pr.items) {
        if (!item.factory) fail(`Pricing request item ${item.id} has no resolved factory`, 422);
        byFactory.set(item.factory, [...(byFactory.get(item.factory) ?? []), item]);
      }
      for (const [factoryName, items] of byFactory) {
        const exists = mockFactoryQuotes.some((q) => q.pricingRequestId === pr.id && q.factoryName === factoryName && q.current);
        if (exists) continue;
        const quoteId = mockFactoryQuoteSeq++;
        mockFactoryQuotes.push({
          id: quoteId,
          quoteCode: `FQ-2026-${String(quoteId).padStart(4, '0')}`,
          pricingRequestId: pr.id,
          factoryId: null,
          factoryName,
          status: 'DRAFT',
          emailTo: null,
          emailSubject: `Pricing request ${pr.requestCode}`,
          emailBody: items.map((item) => `${item.brand ?? ''} ${item.model ?? item.productDescription ?? ''}`).join('\n'),
          emailSentAt: null,
          sentBy: null,
          supplierQuoteRef: null,
          defaultCurrency: 'THB',
          paymentTerms: null,
          leadTimeText: null,
          note: null,
          negotiationNote: null,
          requestedAt: null,
          receivedAt: null,
          rootFactoryQuoteId: quoteId,
          parentFactoryQuoteId: null,
          revisionNo: 1,
          revisionReason: null,
          current: true,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
          attachments: [],
          // Mirrors FactoryQuoteDto's dispatchStatus/dispatchAttemptCount/dispatchFailureMessage/
          // dispatchNextAttemptAt: the outbox worker's state for this quote's most recent send.
          // Null until sendFactoryQuote() enqueues one.
          dispatchStatus: null,
          dispatchAttemptCount: 0,
          dispatchFailureMessage: null,
          dispatchNextAttemptAt: null,
          items: items.map((item, i) => ({
            id: mockFactoryQuoteItemSeq++,
            factoryQuoteId: quoteId,
            pricingRequestItemId: item.id,
            quotedQuantity: item.requestedQty,
            quotedUnit: item.requestedUnit,
            unitBasis: item.requestedUnit,
            rawUnitPrice: null,
            currency: null,
            sortOrder: i,
          })),
        });
      }
      pushPricingRequestEvent(pr, user, 'FACTORY_EMAIL_READY', pr.status, pr.status);
      return delay({ items: mockFactoryQuotes.filter((q) => q.pricingRequestId === pr.id) });
    },

    async listFactoryQuotes(id) {
      hasRole('import', 'ceo');
      findPricingRequestRaw(id);
      return delay({ items: mockFactoryQuotes.filter((q) => q.pricingRequestId === Number(id)) });
    },

    async getFactoryQuote(id) {
      hasRole('import', 'ceo');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('Factory quote not found', 404);
      return delay({ factoryQuote: quote });
    },

    async updateFactoryQuote(id, payload) {
      hasRole('import');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('Factory quote not found', 404);
      if (quote.status !== 'DRAFT') fail('Only draft factory quote emails can be edited', 409);
      Object.assign(quote, {
        emailTo: payload.emailTo ?? quote.emailTo,
        emailSubject: payload.emailSubject ?? quote.emailSubject,
        emailBody: payload.emailBody ?? quote.emailBody,
        note: payload.note ?? quote.note,
        updatedAt: new Date().toISOString(),
      });
      return delay({ factoryQuote: quote });
    },

    // Mirrors FactoryQuoteService.send(): enqueue-only. The actual "send + finalize" (quote ->
    // REQUESTED, pricing request status transition, FACTORY_EMAIL_SENT event) happens out-of-band
    // a moment later via scheduleMockFactoryQuoteDispatch(), the mock stand-in for
    // FactoryQuoteEmailDispatchWorker, so the frontend can exercise the same
    // pending/sending/sent-with-a-delay UX it will see against the real backend.
    async sendFactoryQuote(id, payload = {}) {
      const user = hasRole('import');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('Factory quote not found', 404);
      if (!payload.clientRequestId) fail('clientRequestId must be a UUID', 400);
      if (quote.status === 'REQUESTED') return delay({ factoryQuote: quote });
      if (quote.status !== 'DRAFT') fail('Only draft factory quote emails can be sent', 409);
      const existingForClient = mockFactoryQuoteDispatchClientRequests.find(
        (d) => d.clientRequestId === payload.clientRequestId
      );
      if (existingForClient) {
        if (existingForClient.quoteId !== quote.id) {
          fail('clientRequestId has already been used for another factory quote', 409);
        }
        return delay({ factoryQuote: quote });
      }
      if (quote.dispatchStatus && ['PENDING', 'SENDING', 'SENT'].includes(quote.dispatchStatus)) {
        return delay({ factoryQuote: quote });
      }
      quote.emailTo = payload.emailTo ?? quote.emailTo;
      quote.emailSubject = payload.emailSubject ?? quote.emailSubject;
      quote.emailBody = payload.emailBody ?? quote.emailBody;
      quote.updatedAt = new Date().toISOString();
      mockFactoryQuoteDispatchClientRequests.push({ clientRequestId: payload.clientRequestId, quoteId: quote.id });
      quote.dispatchStatus = 'PENDING';
      quote.dispatchAttemptCount = 0;
      quote.dispatchFailureMessage = null;
      quote.dispatchNextAttemptAt = null;
      scheduleMockFactoryQuoteDispatch(quote, user);
      return delay({ factoryQuote: quote });
    },

    async receiveFactoryQuote(id, payload) {
      const user = hasRole('import');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('Factory quote not found', 404);
      if (!payload.clientRequestId) fail('clientRequestId must be a UUID', 400);
      // Idempotency replay: a lost-response retry (same actor + clientRequestId)
      // must not be treated as a new commercial revision. Look this up BEFORE any
      // mutation and short-circuit with the quote the original call landed on.
      const chainId = (q) => q.rootFactoryQuoteId ?? q.id;
      const existingReceipt = mockFactoryQuoteResponseReceipts.find(
        (r) => r.createdBy === user.id && r.clientRequestId === payload.clientRequestId
      );
      if (existingReceipt) {
        const receiptQuote = mockFactoryQuotes.find((q) => q.id === existingReceipt.factoryQuoteId);
        if (!receiptQuote) fail('Factory quote not found', 404);
        // Compare the QUOTE CHAIN, not the pricing request: a pricing request has one quote
        // per factory, so reusing a clientRequestId against a DIFFERENT factory's quote in the
        // same pricing request must 409, not silently return the wrong factory's quote.
        if (chainId(receiptQuote) !== chainId(quote)) {
          fail('clientRequestId has already been used for another factory quote', 409);
        }
        return delay({ factoryQuote: receiptQuote });
      }
      if (!quote.current) fail('Only the current factory quote revision can receive a response', 409);
      const pr = findPricingRequestRaw(quote.pricingRequestId);
      mockRequireNotCeoReviewing(pr);
      const applyResponse = (target) => {
        target.status = 'RESPONSE_RECEIVED';
        target.supplierQuoteRef = payload.supplierQuoteRef ?? null;
        target.defaultCurrency = payload.defaultCurrency ?? 'THB';
        target.paymentTerms = payload.paymentTerms ?? null;
        target.leadTimeText = payload.leadTimeText ?? null;
        target.revisionReason = payload.revisionReason ?? null;
        target.negotiationNote = payload.negotiationNote ?? null;
        target.receivedAt = new Date().toISOString();
        target.updatedAt = target.receivedAt;
        target.items = payload.items.map((item, i) => ({
          id: mockFactoryQuoteItemSeq++,
          factoryQuoteId: target.id,
          pricingRequestItemId: item.pricingRequestItemId,
          quotedQuantity: item.quotedQuantity,
          quotedUnit: item.quotedUnit,
          unitBasis: item.unitBasis,
          rawUnitPrice: item.rawUnitPrice,
          currency: item.currency,
          minimumOrderQuantity: item.minimumOrderQuantity ?? null,
          sqmPerUnit: item.sqmPerUnit ?? null,
          piecesPerBox: item.piecesPerBox ?? null,
          // Mirrors FactoryQuoteItemDto.linearMPerUnit (V68, financial-integrity review
          // Finding B) — the PER_LINEAR_M conversion factor, same role as sqmPerUnit/
          // piecesPerBox above for PER_SQM/PER_BOX.
          linearMPerUnit: item.linearMPerUnit ?? null,
          sortOrder: i,
        }));
      };
      if (['DRAFT', 'REQUESTED'].includes(quote.status)) {
        applyResponse(quote);
        // First/partial factory response only confirms the request is awaiting
        // (or still awaiting) factory replies. COSTING_IN_PROGRESS is entered only
        // by createCosting(), once every request item's factory has a current
        // READY_FOR_COSTING quote — see PricingCostingService.resolveSources.
        if (pr.status === 'IMPORT_REVIEWING') pr.status = 'AWAITING_FACTORY_RESPONSE';
        pushPricingRequestEvent(pr, user, 'FACTORY_RESPONSE_RECEIVED', pr.status, pr.status);
        mockFactoryQuoteResponseReceipts.push({ factoryQuoteId: quote.id, createdBy: user.id, clientRequestId: payload.clientRequestId });
        return delay({ factoryQuote: quote });
      }
      if (!['RESPONSE_RECEIVED', 'NEGOTIATING', 'READY_FOR_COSTING'].includes(quote.status)) {
        fail(`Factory quote cannot receive a response in status ${quote.status}`, 409);
      }
      quote.status = 'SUPERSEDED';
      quote.current = false;
      const revision = { ...quote, id: mockFactoryQuoteSeq++, quoteCode: `FQ-2026-${String(mockFactoryQuoteSeq).padStart(4, '0')}`, status: 'RESPONSE_RECEIVED', parentFactoryQuoteId: quote.id, revisionNo: quote.revisionNo + 1, current: true, createdAt: new Date().toISOString() };
      applyResponse(revision);
      mockFactoryQuotes.push(revision);
      for (const costing of mockPricingCostings.filter((c) => c.pricingRequestId === pr.id && ['DRAFT', 'CALCULATED'].includes(c.status))) {
        costing.stale = true;
        costing.staleReason = 'Factory quote revision changed';
      }
      pushPricingRequestEvent(pr, user, 'FACTORY_RESPONSE_REVISED', pr.status, pr.status);
      mockFactoryQuoteResponseReceipts.push({ factoryQuoteId: revision.id, createdBy: user.id, clientRequestId: payload.clientRequestId });
      return delay({ factoryQuote: revision });
    },

    async startFactoryNegotiation(id, payload) {
      const user = hasRole('import');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('Factory quote not found', 404);
      mockRequireNotCeoReviewing(findPricingRequestRaw(quote.pricingRequestId));
      if (quote.status !== 'RESPONSE_RECEIVED' || !quote.current) fail('Only a current received response can enter negotiation', 409);
      quote.status = 'NEGOTIATING';
      quote.negotiationNote = payload.note;
      quote.updatedAt = new Date().toISOString();
      pushPricingRequestEvent(findPricingRequestRaw(quote.pricingRequestId), user, 'FACTORY_NEGOTIATION_STARTED', null, null, payload.note);
      return delay({ factoryQuote: quote });
    },

    async markFactoryQuoteReady(id) {
      const user = hasRole('import');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('Factory quote not found', 404);
      mockRequireNotCeoReviewing(findPricingRequestRaw(quote.pricingRequestId));
      if (!['RESPONSE_RECEIVED', 'NEGOTIATING'].includes(quote.status) || !quote.current) fail('Current response must have raw prices before it can be marked ready', 409);
      quote.status = 'READY_FOR_COSTING';
      quote.updatedAt = new Date().toISOString();
      pushPricingRequestEvent(findPricingRequestRaw(quote.pricingRequestId), user, 'FACTORY_RESPONSE_READY_FOR_COSTING', null, null);
      return delay({ factoryQuote: quote });
    },

    async markFactoryQuoteNotAvailable(id, payload) {
      const user = hasRole('import');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('Factory quote not found', 404);
      mockRequireNotCeoReviewing(findPricingRequestRaw(quote.pricingRequestId));
      quote.status = 'NOT_AVAILABLE';
      quote.note = payload.reason;
      pushPricingRequestEvent(findPricingRequestRaw(quote.pricingRequestId), user, 'FACTORY_NOT_AVAILABLE', null, null, payload.reason);
      return delay({ factoryQuote: quote });
    },

    async uploadFactoryQuoteAttachment(id, file) {
      hasRole('import');
      const quote = mockFactoryQuotes.find((q) => q.id === Number(id));
      if (!quote) fail('Factory quote not found', 404);
      mockRequireNotCeoReviewing(findPricingRequestRaw(quote.pricingRequestId));
      const attachment = {
        id: mockFactoryQuoteAttachmentSeq++,
        factoryQuoteId: quote.id,
        fileName: file?.name ?? 'attachment',
        mimeType: file?.type ?? null,
        fileSize: file?.size ?? null,
        uploadedBy: sessionUser.id,
        uploadedAt: new Date().toISOString(),
      };
      quote.attachments = [attachment, ...(quote.attachments ?? [])];
      return delay({ attachment });
    },

    factoryQuoteAttachmentUrl(id) {
      return `#mock-factory-quote-attachment-${id}`;
    },

    async deleteFactoryQuoteAttachment(id) {
      hasRole('import');
      for (const quote of mockFactoryQuotes) {
        quote.attachments = (quote.attachments ?? []).filter((attachment) => attachment.id !== Number(id));
      }
      return delay({ ok: true });
    },

    async createCosting(id, payload = {}) {
      const user = hasRole('import');
      const pr = findPricingRequestRaw(id);
      // Mirrors PricingCostingService.COSTING_CREATE_STATUSES (Step 3, design corrections 3+4):
      // READY_FOR_CEO_REVIEW/CEO_REVIEWING are deliberately excluded — a submitted costing is
      // frozen until the CEO explicitly returns the request (-> COSTING_REVISION_REQUIRED).
      if (!['IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS', 'COSTING_REVISION_REQUIRED'].includes(pr.status)) {
        fail('Pricing request is not ready for costing', 409);
      }
      const readyFactories = new Set(mockFactoryQuotes.filter((q) => q.pricingRequestId === pr.id && q.current && q.status === 'READY_FOR_COSTING').map((q) => q.factoryName));
      for (const item of pr.items) if (!readyFactories.has(item.factory)) fail(`Factory quote for ${item.factory} is not ready for costing`, 422);
      const existing = mockPricingCostings.find((c) => c.pricingRequestId === pr.id && ['DRAFT', 'CALCULATED'].includes(c.status));
      if (existing) return delay({ costing: existing });
      const costing = { id: mockPricingCostingSeq++, costingCode: `PCO-2026-${String(mockPricingCostingSeq).padStart(4, '0')}`, pricingRequestId: pr.id, versionNo: mockPricingCostingSeq, status: 'DRAFT', stale: false, staleReason: null, note: payload.note ?? null, createdBy: user.id, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), calculatedAt: null, submittedBy: null, submittedAt: null, totalLandedCostThb: null, items: [] };
      mockPricingCostings.push(costing);
      pr.status = 'COSTING_IN_PROGRESS';
      pushPricingRequestEvent(pr, user, 'PRICING_COSTING_STARTED', null, 'COSTING_IN_PROGRESS');
      return delay({ costing });
    },

    async listCostings(id) {
      hasRole('import', 'ceo');
      return delay({ items: mockPricingCostings.filter((c) => c.pricingRequestId === Number(id)) });
    },

    async getCosting(id) {
      hasRole('import', 'ceo');
      const costing = mockPricingCostings.find((c) => c.id === Number(id));
      if (!costing) fail('Costing not found', 404);
      return delay({ costing });
    },

    async recalculateCosting(id, payload = {}) {
      const user = hasRole('import');
      const costing = mockPricingCostings.find((c) => c.id === Number(id));
      if (!costing) fail('Costing not found', 404);
      if (costing.status === 'SUBMITTED') fail('Submitted costing is immutable', 409);
      const pr = findPricingRequestRaw(costing.pricingRequestId);
      costing.items = pr.items.map((item) => {
        const quote = mockFactoryQuotes.find((q) => q.pricingRequestId === pr.id && q.factoryName === item.factory && q.current && q.status === 'READY_FOR_COSTING');
        const quoteItem = quote?.items.find((qi) => qi.pricingRequestItemId === item.id);
        if (!quote || !quoteItem) fail(`Factory quote for ${item.factory} is not ready for costing`, 422);
        // Finding B (financial-integrity review, commit 3): normalize BOTH the quoted price
        // and the requested quantity onto a common basis (physical pieces) before multiplying
        // — see mockPricePerPiece/mockQuantityToPieces above, mirroring
        // PricingCostingService.calculate(). The pre-fix mock multiplied raw price by the raw
        // (un-normalized) requestedQty, the same bug the real backend had.
        const factors = { sqmPerUnit: quoteItem.sqmPerUnit, piecesPerBox: quoteItem.piecesPerBox, linearMPerUnit: quoteItem.linearMPerUnit };
        const raw = Number(quoteItem.rawUnitPrice ?? 0);
        const pricePerPiece = mockPricePerPiece(raw, quoteItem.unitBasis, factors, item.id);
        const normalizedQuantityPieces = mockQuantityToPieces(item.requestedQty, item.requestedUnitBasis, factors, item.id);
        return {
          pricingRequestItemId: item.id,
          factoryQuoteId: quote.id,
          factoryQuoteItemId: quoteItem.id,
          factoryQuoteRevisionNo: quote.revisionNo,
          factoryName: quote.factoryName,
          rawUnitPrice: raw,
          rawCurrency: quoteItem.currency,
          rawUnit: quoteItem.quotedUnit,
          unitBasis: quoteItem.unitBasis,
          requestedQuantity: item.requestedQty,
          requestedUnit: item.requestedUnit,
          requestedUnitBasis: item.requestedUnitBasis,
          normalizedQuantityPieces,
          sqmPerUnit: quoteItem.sqmPerUnit ?? null,
          piecesPerBox: quoteItem.piecesPerBox ?? null,
          linearMPerUnit: quoteItem.linearMPerUnit ?? null,
          goodsCostThb: pricePerPiece,
          landedCostPerUnitThb: pricePerPiece,
          totalLandedCostThb: pricePerPiece * normalizedQuantityPieces,
        };
      });
      costing.totalLandedCostThb = costing.items.reduce((sum, item) => sum + item.totalLandedCostThb, 0);
      costing.status = 'CALCULATED';
      costing.stale = false;
      costing.staleReason = null;
      costing.note = payload.note ?? costing.note;
      costing.calculatedAt = new Date().toISOString();
      pushPricingRequestEvent(pr, user, 'PRICING_COSTING_CALCULATED', null, null);
      return delay({ costing });
    },

    async submitCosting(id, payload = {}) {
      const user = hasRole('import');
      const costing = mockPricingCostings.find((c) => c.id === Number(id));
      if (!costing) fail('Costing not found', 404);
      if (costing.stale) fail('Costing is stale and must be recalculated before submit', 409);
      if (costing.status !== 'CALCULATED') fail('Only a calculated costing can be submitted', 409);
      const pr = findPricingRequestRaw(costing.pricingRequestId);
      costing.status = 'SUBMITTED';
      costing.submittedBy = user.id;
      costing.submittedAt = new Date().toISOString();
      costing.note = payload.note ?? costing.note;
      pr.status = 'READY_FOR_CEO_REVIEW';
      pushPricingRequestEvent(pr, user, 'PRICING_COSTING_SUBMITTED', 'COSTING_IN_PROGRESS', 'READY_FOR_CEO_REVIEW');
      return delay({ costing });
    },

    // ── Step 3: CEO Selling Price Decision — mirrors PricingDecisionController +
    // PricingDecisionService (pricingdecision/). Authorization is NOT authoritative (CLAUDE.md);
    // verify role/scope behavior against the real Java service.
    //
    // Simplifications vs the real backend (documented, not silent): FX is always THB (rate 1) —
    // the same simplification the costing mock above already makes (no fxRate/fxSource fields on
    // a mock costing item, no BOT validation); selling price is computed in THB only.

    async startPricingDecision(id, payload = {}) {
      const user = hasRole('ceo');
      const pr = findPricingRequestRaw(id);
      if (pr.status !== 'READY_FOR_CEO_REVIEW') fail('Pricing request is not ready for CEO review', 409);
      const openDraft = mockPricingDecisions.find((d) => d.pricingRequestId === pr.id && d.status === 'DRAFT');
      if (openDraft) return delay({ decision: openDraft });
      const submittedCostings = mockPricingCostings.filter((c) => c.pricingRequestId === pr.id && c.status === 'SUBMITTED');
      const costing = submittedCostings[submittedCostings.length - 1];
      if (!costing) fail('Pricing request has no submitted costing', 409);
      const currency = (payload.currency || pr.targetCurrency || 'THB').toUpperCase();
      const defaultMarginPct = payload.defaultMarginPct ?? null;
      const decisionVersionNo = mockPricingDecisions.filter((d) => d.pricingRequestId === pr.id).length + 1;
      const decision = {
        id: mockPricingDecisionSeq++,
        decisionCode: `PCD-2026-${String(mockPricingDecisionSeq).padStart(4, '0')}`,
        pricingRequestId: pr.id,
        pricingCostingId: costing.id,
        decisionVersionNo,
        status: 'DRAFT',
        defaultMarginPct,
        currency,
        fxRateUsed: 1,
        fxSource: 'THB',
        fxEffectiveDate: new Date().toISOString().slice(0, 10),
        ceoNote: payload.ceoNote ?? null,
        returnReason: null,
        approveClientRequestId: null,
        createdBy: user.id,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        approvedBy: null,
        approvedAt: null,
        returnedAt: null,
        items: costing.items.map((costingItem) => {
          const prItem = pr.items.find((i) => i.id === costingItem.pricingRequestItemId);
          const frozenPerPiece = Number(costingItem.landedCostPerUnitThb ?? 0);
          const requestedQuantity = Number(costingItem.requestedQuantity ?? prItem?.requestedQty ?? 0);
          const frozenPerRequestedUnit = requestedQuantity > 0
            ? Number(costingItem.totalLandedCostThb ?? 0) / requestedQuantity : frozenPerPiece;
          const proposedSellingPricePerRequestedUnit = defaultMarginPct != null
            ? frozenPerRequestedUnit * (1 + Number(defaultMarginPct)) : null;
          return {
            id: mockPricingDecisionItemSeq++,
            pricingDecisionId: decision?.id,
            pricingRequestItemId: costingItem.pricingRequestItemId,
            pricingCostingItemId: costingItem.pricingRequestItemId,
            brand: prItem?.brand ?? null,
            model: prItem?.model ?? null,
            productDescription: prItem?.productDescription ?? null,
            factoryName: costingItem.factoryName ?? null,
            requestedUnitBasis: costingItem.requestedUnitBasis,
            requestedQuantity,
            normalizedQuantityPieces: costingItem.normalizedQuantityPieces,
            frozenLandedCostPerPieceThb: frozenPerPiece,
            frozenLandedCostPerRequestedUnitThb: frozenPerRequestedUnit,
            currency,
            proposedMarginPct: defaultMarginPct,
            approvedMarginPct: null,
            proposedSellingPricePerRequestedUnit,
            approvedSellingPricePerRequestedUnit: null,
            discountCeilingPct: null,
            minimumSellingPricePerRequestedUnit: null,
            decisionNote: null,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
          };
        }),
      };
      // Fix up the self-reference now that decision.id is known (items were built before push).
      decision.items.forEach((item) => { item.pricingDecisionId = decision.id; });
      mockPricingDecisions.push(decision);
      pr.status = 'CEO_REVIEWING';
      pushPricingRequestEvent(pr, user, 'PRICING_DECISION_STARTED', 'READY_FOR_CEO_REVIEW', 'CEO_REVIEWING');
      return delay({ decision });
    },

    async listPricingDecisions(id) {
      hasRole('import', 'ceo');
      return delay({ items: mockPricingDecisions.filter((d) => d.pricingRequestId === Number(id)) });
    },

    async getPricingDecisionSalesView(id) {
      const user = hasRole('sales', 'sales_manager', 'ceo', 'import');
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (user.role === 'sales' && ticket?.createdById !== user.id) fail('Forbidden', 403);
      const decision = mockPricingDecisions.find((d) => d.pricingRequestId === pr.id && d.status === 'APPROVED');
      if (!decision) fail('No approved pricing decision yet', 404);
      // Design correction 2 ("never leak cost to Sales"): a fresh object literal per item with
      // ONLY these fields — never a spread of the raw (cost/margin-bearing) decision item.
      return delay({
        decision: {
          pricingRequestId: pr.id,
          pricingDecisionId: decision.id,
          currency: decision.currency,
          approvedAt: decision.approvedAt,
          items: decision.items.map((item) => ({
            pricingRequestItemId: item.pricingRequestItemId,
            brand: item.brand,
            model: item.model,
            productDescription: item.productDescription,
            requestedUnitBasis: item.requestedUnitBasis,
            requestedQuantity: item.requestedQuantity,
            approvedSellingPricePerRequestedUnit: item.approvedSellingPricePerRequestedUnit,
            discountCeilingPct: item.discountCeilingPct,
            minimumSellingPricePerRequestedUnit: item.minimumSellingPricePerRequestedUnit,
          })),
        },
      });
    },

    async getPricingDecision(id) {
      hasRole('import', 'ceo');
      const decision = mockPricingDecisions.find((d) => d.id === Number(id));
      if (!decision) fail('Pricing decision not found', 404);
      return delay({ decision });
    },

    async updatePricingDecision(id, payload) {
      hasRole('ceo');
      const decision = mockPricingDecisions.find((d) => d.id === Number(id));
      if (!decision) fail('Pricing decision not found', 404);
      if (decision.status !== 'DRAFT') fail('Decision is not open for editing', 409);
      if (payload.ceoNote != null) decision.ceoNote = payload.ceoNote;
      for (const itemPayload of payload.items ?? []) {
        const item = decision.items.find((i) => i.id === Number(itemPayload.pricingDecisionItemId));
        if (!item) fail(`Item ${itemPayload.pricingDecisionItemId} does not belong to this decision`, 400);
        if (itemPayload.marginPct != null) {
          item.proposedMarginPct = itemPayload.marginPct;
          item.proposedSellingPricePerRequestedUnit =
            item.frozenLandedCostPerRequestedUnitThb * (1 + Number(itemPayload.marginPct));
        }
        if (itemPayload.discountCeilingPct != null) item.discountCeilingPct = itemPayload.discountCeilingPct;
        if (itemPayload.minimumSellingPrice != null) item.minimumSellingPricePerRequestedUnit = itemPayload.minimumSellingPrice;
        if (itemPayload.decisionNote != null) item.decisionNote = itemPayload.decisionNote;
        item.updatedAt = new Date().toISOString();
      }
      decision.updatedAt = new Date().toISOString();
      return delay({ decision });
    },

    async recalculatePricingDecision(id, payload = {}) {
      hasRole('ceo');
      const decision = mockPricingDecisions.find((d) => d.id === Number(id));
      if (!decision) fail('Pricing decision not found', 404);
      if (decision.status !== 'DRAFT') fail('Decision is not open for editing', 409);
      if (payload.defaultMarginPct != null) decision.defaultMarginPct = payload.defaultMarginPct;
      for (const item of decision.items) {
        const margin = payload.defaultMarginPct != null ? payload.defaultMarginPct : item.proposedMarginPct;
        if (margin == null) continue;
        item.proposedMarginPct = margin;
        item.proposedSellingPricePerRequestedUnit = item.frozenLandedCostPerRequestedUnitThb * (1 + Number(margin));
        item.updatedAt = new Date().toISOString();
      }
      decision.updatedAt = new Date().toISOString();
      return delay({ decision });
    },

    async approvePricingDecision(id, payload = {}) {
      const user = hasRole('ceo');
      const decision = mockPricingDecisions.find((d) => d.id === Number(id));
      if (!decision) fail('Pricing decision not found', 404);
      if (payload.clientRequestId && decision.approveClientRequestId === payload.clientRequestId
          && decision.status === 'APPROVED') {
        return delay({ decision });
      }
      if (decision.status !== 'DRAFT') fail('Decision is not open for approval', 409);
      const pr = findPricingRequestRaw(decision.pricingRequestId);
      if (pr.status !== 'CEO_REVIEWING') fail('Pricing request is not under CEO review', 409);
      const missingMargin = decision.items.filter((i) => i.proposedMarginPct == null).map((i) => i.id);
      const missingMinimum = decision.items.filter((i) => i.minimumSellingPricePerRequestedUnit == null).map((i) => i.id);
      if (missingMargin.length || missingMinimum.length) {
        fail(`Every item needs a margin and a minimum selling price before approval — missing margin: [${missingMargin}], missing minimum selling price: [${missingMinimum}]`, 422);
      }
      // Never trust a stored/client-supplied selling price — recompute from frozen cost + margin.
      for (const item of decision.items) {
        item.approvedMarginPct = item.proposedMarginPct;
        item.approvedSellingPricePerRequestedUnit =
          item.frozenLandedCostPerRequestedUnitThb * (1 + Number(item.proposedMarginPct));
        item.updatedAt = new Date().toISOString();
      }
      decision.status = 'APPROVED';
      decision.approvedBy = user.id;
      decision.approvedAt = new Date().toISOString();
      decision.approveClientRequestId = payload.clientRequestId ?? null;
      if (payload.ceoNote != null) decision.ceoNote = payload.ceoNote;
      decision.updatedAt = new Date().toISOString();
      pr.status = 'APPROVED_FOR_QUOTATION';
      pushPricingRequestEvent(pr, user, 'PRICING_DECISION_APPROVED', 'CEO_REVIEWING', 'APPROVED_FOR_QUOTATION');
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      addNotification(pr.requestedById, pr.ticketId, ticket?.code, 'PRICING_DECISION_APPROVED',
        `ใบขอราคา ${pr.requestCode} ได้รับอนุมัติราคาขายแล้ว`);
      return delay({ decision });
    },

    async returnPricingDecisionToImport(id, payload) {
      const user = hasRole('ceo');
      if (!payload?.returnReason?.trim()) fail('returnReason is required', 400);
      const decision = mockPricingDecisions.find((d) => d.id === Number(id));
      if (!decision) fail('Pricing decision not found', 404);
      if (decision.status !== 'DRAFT') fail('Decision is not open for editing', 409);
      const pr = findPricingRequestRaw(decision.pricingRequestId);
      if (pr.status !== 'CEO_REVIEWING') fail('Pricing request is not under CEO review', 409);
      decision.status = 'RETURNED';
      decision.returnReason = payload.returnReason;
      decision.returnedAt = new Date().toISOString();
      decision.updatedAt = new Date().toISOString();
      pr.status = 'COSTING_REVISION_REQUIRED';
      pushPricingRequestEvent(pr, user, 'PRICING_DECISION_RETURNED', 'CEO_REVIEWING', 'COSTING_REVISION_REQUIRED', payload.returnReason);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (pr.assignedImportId != null) {
        addNotification(pr.assignedImportId, pr.ticketId, ticket?.code, 'PRICING_DECISION_RETURNED',
          `ใบขอราคา ${pr.requestCode} ถูก CEO ตีกลับให้แก้ไขต้นทุน`);
      }
      return delay({ decision });
    },

    // ── Step 4: Customer Quotation Generation and Issuance — mirrors
    // CustomerQuotationController + CustomerQuotationService (customerquotation/).
    // Authorization is NOT authoritative (CLAUDE.md); verify against the real Java service.
    // Simplification vs the real backend (documented, not silent): FX/currency mirrors Step 3's
    // own mock simplification — THB only, rate 1 (no fxRate/fxSource fields anywhere on this
    // aggregate either, same as the pricing-decision mock above).

    async createCustomerQuotation(id, payload = {}) {
      const user = hasRole('sales');
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (ticket?.createdById !== user.id) fail('Forbidden', 403);
      if (payload.clientRequestId) {
        const replay = mockCustomerQuotations.find(
          (q) => q.issuedById === user.id && q.clientRequestId === payload.clientRequestId);
        if (replay) return delay({ quotation: replay });
      }
      if (pr.status !== 'APPROVED_FOR_QUOTATION') {
        fail(`ใบขอราคาต้องอยู่ในสถานะ 'อนุมัติราคาขายแล้ว' ก่อนจึงจะออกใบเสนอราคาลูกค้าได้ (ปัจจุบัน: ${pr.status})`, 409);
      }
      const decision = mockPricingDecisions.find((d) => d.pricingRequestId === pr.id && d.status === 'APPROVED');
      if (!decision) fail('ยังไม่มีราคาขายที่ CEO อนุมัติสำหรับใบขอราคานี้', 409);
      const quotation = buildMockCustomerQuotationDraft(pr, decision, ticket, user, payload, null, 1, {});
      mockCustomerQuotations.push(quotation);
      pushPricingRequestEvent(pr, user, 'CUSTOMER_QUOTATION_CREATED', pr.status, pr.status, 'สร้างร่างใบเสนอราคาลูกค้า');
      return delay({ quotation });
    },

    async listCustomerQuotations(id) {
      const user = hasRole('sales', 'sales_manager', 'ceo', 'import');
      const pr = findPricingRequestRaw(id);
      if (user.role === 'sales') {
        const ticket = db.tickets.find((t) => t.id === pr.ticketId);
        if (ticket?.createdById !== user.id) fail('Forbidden', 403);
      }
      return delay({
        items: mockCustomerQuotations
          .filter((q) => q.pricingRequestId === pr.id)
          .sort((a, b) => a.quotationRevisionNo - b.quotationRevisionNo),
      });
    },

    async getCustomerQuotation(id) {
      const quotation = mockCustomerQuotationViewAccess(id);
      return delay({ quotation });
    },

    async updateCustomerQuotation(id, payload = {}) {
      const user = hasRole('sales');
      const quotation = mockCustomerQuotationEditAccess(id, user);
      if (!['DRAFT', 'READY_TO_ISSUE'].includes(quotation.docStatus)) {
        fail(`แก้ไขได้เฉพาะใบเสนอราคาที่ยังเป็นร่างเท่านั้น (ปัจจุบัน: ${quotation.docStatus})`, 409);
      }
      if (payload.paymentTerms != null) quotation.paymentTerms = payload.paymentTerms;
      if (payload.leadTime != null) quotation.leadTime = payload.leadTime;
      if (payload.deliveryTerms != null) quotation.deliveryTerms = payload.deliveryTerms;
      if (payload.validityDate != null) quotation.validityDate = payload.validityDate;
      if (payload.customerNotes != null) quotation.customerNotes = payload.customerNotes;
      for (const itemPayload of payload.items ?? []) {
        const item = quotation.items.find((i) => i.id === Number(itemPayload.quotationItemId));
        if (!item) fail(`รายการ ${itemPayload.quotationItemId} ไม่ได้อยู่ในใบเสนอราคานี้`, 400);
        const discount = itemPayload.salesDiscount != null ? Number(itemPayload.salesDiscount) : (item.salesDiscount ?? 0);
        if (discount < 0) fail('ส่วนลดต้องไม่ติดลบ', 400);
        const finalUnitPrice = item.approvedUnitPrice - discount;
        if (finalUnitPrice < 0) fail('ส่วนลดต้องไม่เกินราคาที่อนุมัติ', 400);
        // Discount Policy B (owner's decision): never below the CEO-approved minimum — hard
        // 422, no auto-escalation.
        if (item.minimumSellingPricePerRequestedUnit != null && finalUnitPrice < item.minimumSellingPricePerRequestedUnit) {
          fail(`ราคาหลังหักส่วนลดของรายการ ${itemPayload.quotationItemId} ต่ำกว่าราคาขั้นต่ำที่ CEO อนุมัติ`, 422);
        }
        if (itemPayload.description != null) item.description = itemPayload.description;
        if (itemPayload.itemNotes != null) item.itemNotes = itemPayload.itemNotes;
        item.salesDiscount = discount;
        item.finalUnitPrice = finalUnitPrice;
        item.lineSubtotal = round2(finalUnitPrice * item.requestedQuantity);
        item.vat = round2(item.lineSubtotal * 0.07);
        item.lineTotal = round2(item.lineSubtotal + item.vat);
      }
      recalcMockCustomerQuotationTotals(quotation);
      pushPricingRequestEvent(findPricingRequestRaw(quotation.pricingRequestId), user, 'CUSTOMER_QUOTATION_UPDATED',
        null, null, `แก้ไขใบเสนอราคาลูกค้า ${quotation.number}`);
      return delay({ quotation });
    },

    async previewCustomerQuotation(id) {
      // Rule 12: pure read, zero writes — same object the last create/update already computed.
      const quotation = mockCustomerQuotationViewAccess(id);
      return delay({ quotation });
    },

    async issueCustomerQuotation(id, payload = {}) {
      const user = hasRole('sales');
      const preview = mockCustomerQuotationEditAccess(id, user);
      if (payload.clientRequestId) {
        const replay = mockCustomerQuotations.find(
          (q) => q.issuedById === user.id && q.issueClientRequestId === payload.clientRequestId);
        if (replay) {
          if (replay.id !== preview.id) fail('clientRequestId ถูกใช้ไปแล้วกับใบเสนอราคาอื่น', 409);
          return delay({ quotation: replay });
        }
      }
      if (!['DRAFT', 'READY_TO_ISSUE'].includes(preview.docStatus)) {
        if (preview.docStatus === 'ISSUED') fail('ใบเสนอราคานี้ออกไปแล้ว', 409);
        fail(`ใบเสนอราคาไม่ได้อยู่ในสถานะที่ออกได้ (${preview.docStatus})`, 409);
      }
      for (const item of preview.items) {
        if (item.minimumSellingPricePerRequestedUnit != null && item.finalUnitPrice < item.minimumSellingPricePerRequestedUnit) {
          fail(`ไม่สามารถออกใบเสนอราคาได้ — รายการ ${item.id} ต่ำกว่าราคาขั้นต่ำ`, 422);
        }
      }
      preview.docStatus = 'ISSUED';
      preview.issuedAt = new Date().toISOString();
      preview.issueClientRequestId = payload.clientRequestId ?? null;

      const pr = findPricingRequestRaw(preview.pricingRequestId);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      // Only the FIRST issue moves the pricing request — a revision's re-issue is a no-op
      // transition (mirrors PricingRequestStatus.QUOTATION_ISSUED being terminal).
      if (pr.status === 'APPROVED_FOR_QUOTATION') {
        pr.status = 'QUOTATION_ISSUED';
      }
      // Rule 7: reuse the EXACT SAME stage-advance the legacy quotation() mock action already
      // performs — not a second path.
      if (['DESIGNER', 'OWNER'].includes(preview.recipientType)) autoAdvanceStage(ticket, 'QUOTE_DESIGN_SIDE', user);
      if (preview.recipientType === 'BUYER') autoAdvanceStage(ticket, 'QUOTE_BUYER', user);
      pushEvent(ticket, user, 'QUOTATION_ISSUED', null, null,
        `ออกใบเสนอราคาลูกค้า ${preview.number} (revision ${preview.quotationRevisionNo})`);
      pushPricingRequestEvent(pr, user, 'CUSTOMER_QUOTATION_ISSUED', null, null, `ออกใบเสนอราคาลูกค้า ${preview.number}`);
      // "Customer notification" — no customer user account exists in this system to notify
      // in-app, so (matching the real service) the closest equivalent is a CEO-visibility
      // notification. Documented, not silently substituted.
      const ceoUsers = db.users.filter((u) => u.role === 'ceo');
      ceoUsers.forEach((ceo) => addNotification(ceo.id, pr.ticketId, ticket?.code, 'CUSTOMER_QUOTATION_ISSUED',
        `ใบเสนอราคาลูกค้า ${preview.number} ถูกออกแล้ว`));
      return delay({ quotation: preview });
    },

    async cancelCustomerQuotation(id, payload = {}) {
      const user = hasRole('sales');
      const quotation = mockCustomerQuotationEditAccess(id, user);
      if (!['DRAFT', 'READY_TO_ISSUE'].includes(quotation.docStatus)) {
        fail('ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงยกเลิกไม่ได้', 409);
      }
      quotation.docStatus = 'CANCELLED';
      pushPricingRequestEvent(findPricingRequestRaw(quotation.pricingRequestId), user, 'CUSTOMER_QUOTATION_CANCELLED',
        null, null, `ยกเลิกร่างใบเสนอราคาลูกค้า ${quotation.number}${payload.reason ? ` — ${payload.reason}` : ''}`);
      return delay({ quotation });
    },

    async createCustomerQuotationRevision(id, payload = {}) {
      const user = hasRole('sales');
      const source = mockCustomerQuotationEditAccess(id, user);
      // Step 5 (design correction 3): a commercial-only correction is reachable from ISSUED OR
      // REVISION_REQUESTED (recordOutcome writes ISSUED -> REVISION_REQUESTED immediately, before
      // Sales picks commercial-only vs cost-affecting) — nothing else.
      if (!['ISSUED', 'REVISION_REQUESTED'].includes(source.docStatus)) {
        fail('แก้ไข revision ใหม่ได้จากใบเสนอราคาที่ออกแล้วเท่านั้น', 409);
      }
      if (payload.clientRequestId) {
        const replay = mockCustomerQuotations.find(
          (q) => q.issuedById === user.id && q.clientRequestId === payload.clientRequestId);
        if (replay) return delay({ quotation: replay });
      }
      const pr = findPricingRequestRaw(source.pricingRequestId);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      const decision = mockPricingDecisions.find((d) => d.pricingRequestId === pr.id && d.status === 'APPROVED');
      if (!decision) fail('ยังไม่มีราคาขายที่ CEO อนุมัติ', 409);
      // Preserve each prior line's discount by pricingRequestItemId, same as the real service.
      const priorDiscounts = {};
      source.items.forEach((item) => { priorDiscounts[item.pricingRequestItemId] = item.salesDiscount; });
      const revision = buildMockCustomerQuotationDraft(pr, decision, ticket, user, {
        paymentTerms: source.paymentTerms, leadTime: source.leadTime, deliveryTerms: source.deliveryTerms,
        validityDate: source.validityDate, customerNotes: source.customerNotes,
        clientRequestId: payload.clientRequestId,
      }, source.id, source.quotationRevisionNo + 1, priorDiscounts);
      mockCustomerQuotations.push(revision);
      source.docStatus = 'SUPERSEDED';
      pushPricingRequestEvent(pr, user, 'CUSTOMER_QUOTATION_REVISED', null, null,
        `สร้างใบเสนอราคาลูกค้า revision ${revision.quotationRevisionNo}${payload.reason ? ` — ${payload.reason}` : ''}`);
      return delay({ quotation: revision });
    },

    // ── Step 5: Customer Decision and Commercial Revisions — mirrors
    // CustomerQuotationController.recordOutcome + CustomerQuotationService.recordOutcome.
    // Authorization is NOT authoritative (CLAUDE.md); verify against the real Java service.

    async recordCustomerQuotationOutcome(id, payload = {}) {
      const user = hasRole('sales');
      const quotation = mockCustomerQuotationEditAccess(id, user);
      if (payload.outcome === 'EXPIRED') {
        fail('EXPIRED ไม่สามารถบันทึกผ่าน API นี้ได้ — ระบบตั้งเป็นอัตโนมัติเท่านั้น', 400);
      }
      if (!['ACCEPTED', 'REJECTED', 'REVISION_REQUESTED'].includes(payload.outcome)) {
        fail('outcome ไม่ถูกต้อง', 400);
      }
      if (payload.clientRequestId) {
        const replay = mockCustomerQuotations.find(
          (q) => q.issuedById === user.id && q.outcomeClientRequestId === payload.clientRequestId);
        if (replay) return delay({ quotation: replay });
      }
      if (quotation.docStatus !== 'ISSUED') {
        fail(`บันทึกผลได้เฉพาะใบเสนอราคาที่ออกแล้วเท่านั้น (ปัจจุบัน: ${quotation.docStatus})`, 409);
      }
      const now = new Date().toISOString();
      quotation.docStatus = payload.outcome;
      quotation.outcomeNote = payload.customerNote ?? null;
      quotation.outcomeRecordedAt = now;
      quotation.outcomeClientRequestId = payload.clientRequestId ?? null;
      if (payload.outcome === 'ACCEPTED') quotation.acceptedAt = now;
      if (payload.outcome === 'REJECTED') quotation.rejectedAt = now;

      const pr = findPricingRequestRaw(quotation.pricingRequestId);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      const eventKind = payload.outcome === 'ACCEPTED' ? 'CUSTOMER_QUOTATION_ACCEPTED'
        : payload.outcome === 'REJECTED' ? 'CUSTOMER_QUOTATION_REJECTED' : 'CUSTOMER_QUOTATION_REVISION_REQUESTED';
      const label = payload.outcome === 'ACCEPTED' ? 'ลูกค้ายอมรับแล้ว'
        : payload.outcome === 'REJECTED' ? 'ถูกลูกค้าปฏิเสธ' : 'ลูกค้าขอแก้ไข';
      pushPricingRequestEvent(pr, user, eventKind, null, null,
        `บันทึกผลใบเสนอราคาลูกค้า ${quotation.number}: ${payload.outcome}${payload.customerNote ? ` — ${payload.customerNote}` : ''}`);
      const ceoUsers = db.users.filter((u) => u.role === 'ceo');
      ceoUsers.forEach((ceo) => addNotification(ceo.id, pr.ticketId, ticket?.code, eventKind,
        `ใบเสนอราคาลูกค้า ${quotation.number} ${label}`));

      // Design correction 2: ACCEPTED is the ONE forward exit from QUOTATION_ISSUED.
      // REJECTED/REVISION_REQUESTED/EXPIRED deliberately never change pr.status.
      if (payload.outcome === 'ACCEPTED' && pr.status === 'QUOTATION_ISSUED') {
        pr.status = 'QUOTATION_ACCEPTED';
      }
      return delay({ quotation });
    },

    // ── Step 6: Deposit, Payment, and Order Confirmation — mirrors
    // OrderConfirmationController/OrderConfirmationService. Authorization is NOT authoritative
    // (CLAUDE.md); verify against the real Java service.

    async confirmOrder(id, payload = {}) {
      const user = hasRole('sales');
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      // Owner-only, mirrors OrderConfirmationService.requireOwner (ticket owner, not merely the
      // pricing request's own requestedById).
      if (ticket?.createdById !== user.id) fail('Forbidden', 403);

      if (pr.orderConfirmedAt) {
        if (payload.clientRequestId && payload.clientRequestId === pr.orderConfirmClientRequestId) {
          return delay({ result: { ticket: buildTicketDetail(ticket), pricingRequest: buildPricingRequestSummary(pr) } });
        }
        fail('คำสั่งซื้อนี้ได้รับการยืนยันไปแล้ว', 409);
      }
      if (pr.status !== 'QUOTATION_ACCEPTED') {
        fail(`ยืนยันคำสั่งซื้อได้เฉพาะใบขอราคาที่ลูกค้ายอมรับใบเสนอราคาแล้วเท่านั้น (ปัจจุบัน: ${pr.status})`, 409);
      }

      const now = new Date().toISOString();
      pr.orderConfirmedAt = now;
      pr.orderConfirmedBy = user.id;
      pr.orderConfirmClientRequestId = payload.clientRequestId ?? null;

      // The one deliberate bridge write (mirrors TicketRepository
      // .markQuotationIssuedForOrderConfirmation): guarded FROM 'draft' only.
      if (ticket.status === 'draft') {
        const fromStatus = ticket.status;
        ticket.status = 'quotation_issued';
        ticket.updatedAt = now;
        pushEvent(ticket, user, 'ORDER_CONFIRMED_FROM_QUOTATION', fromStatus, 'quotation_issued',
          `ยืนยันคำสั่งซื้อจากใบเสนอราคาลูกค้าที่ยอมรับแล้ว (ใบขอราคา ${pr.requestCode})`);
      }

      // Step 8: reconcile ticket_item.qty to what THIS pricing request actually settled on,
      // before any delivery machinery becomes reachable — mirrors OrderConfirmationService
      // .reconcileTicketItems, called at the same point in the real bridge.
      reconcileTicketItemsFromPricingRequest(ticket, pr, user);

      // Bug fix (found on review, not deferred): confirmOrder runs once PER ACCEPTED PRICING
      // REQUEST — reconciliation above must happen for every accepted revision, not just the
      // deal's first one — but confirmCustomer is a ONE-TIME, ticket-level action whose guard
      // correctly refuses a second call once payment has progressed. Calling it unconditionally
      // meant confirming a later revision (deposit already paid, stock already reserved) always
      // threw, rolling back this whole transaction INCLUDING the reconciliation that just ran.
      // Only run the confirmCustomer mirror when the ticket hasn't been customer-confirmed yet.
      if (ticket.paymentStatus == null) {
        ticket.paymentStatus = 'CUSTOMER_CONFIRMED';
        ticket.updatedAt = now;
        pushEvent(ticket, user, 'CUSTOMER_CONFIRMED', ticket.status, ticket.status, null);
        autoAdvanceStage(ticket, 'ORDER_RECEIVED', user);
      }

      pushPricingRequestEvent(pr, user, 'ORDER_CONFIRMED', pr.status, pr.status, 'ยืนยันคำสั่งซื้อแล้ว');
      const ceoUsers = db.users.filter((u) => u.role === 'ceo');
      ceoUsers.forEach((ceo) => addNotification(ceo.id, pr.ticketId, ticket?.code, 'ORDER_CONFIRMED',
        `ใบขอราคา ${pr.requestCode} ยืนยันคำสั่งซื้อแล้ว`));

      return delay({ result: { ticket: buildTicketDetail(ticket), pricingRequest: buildPricingRequestSummary(pr) } });
    },

    async createDepositNoticeFromQuotation(id, payload = {}) {
      const user = hasRole('sales');
      const pr = findPricingRequestRaw(id);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (ticket?.createdById !== user.id) fail('Forbidden', 403);

      const accepted = mockCustomerQuotations.find(
        (q) => q.pricingRequestId === pr.id && q.docStatus === 'ACCEPTED');
      if (!accepted) fail('ยังไม่มีใบเสนอราคาที่ลูกค้ายอมรับสำหรับใบขอราคานี้', 409);

      // Mirrors OrderConfirmationService.createDepositNoticeFromQuotation: items/amounts trace
      // to the quotation, never to any sales.ticket_item row.
      const items = accepted.items.map((item, idx) => ({
        seq: item.seq ?? idx + 1,
        description: item.description?.trim() || 'รายการสินค้า',
        qty: item.requestedQuantity,
        unit: mockUnitBasisLabel(item.requestedUnitBasis),
        unitPrice: item.approvedUnitPrice,
        discountLabel: item.salesDiscount > 0 ? `ส่วนลด ${item.salesDiscount} ต่อหน่วย` : null,
        netUnitPrice: item.finalUnitPrice,
      }));

      // Requires quotation_issued (one of DepositNoticeService.requireApprovedTicket's three
      // accepted statuses) — mirrors what confirmOrder above already left in place; calling this
      // BEFORE confirmOrder fails here exactly like the real backend's requireApprovedTicket.
      requireActive(ticket);
      if (!['approved', 'quotation_issued', 'document_issued'].includes(ticket.status)) {
        fail('Deposit notice can only be created for approved tickets', 409);
      }

      const notes = mockNoteTemplates.filter((t) => t.defaultSelected).map((t) => t.text);
      const nextVer = mockDepositNotices.filter((d) => d.ticketId === pr.ticketId).length + 1;
      const doc = buildMockDoc({
        id: mockDocSeq++, ticketId: pr.ticketId, docType: 'DEPOSIT_NOTICE',
        version: nextVer, docNumber: null, issueDate: null, status: 'DRAFT',
        customerName: ticket.customerName ?? '', customerTaxId: '', customerAddress: '',
        projectName: '', reference: accepted.number,
        depositPercent: payload.depositPercent ?? 0.5, vatPercent: 0.07,
        notes, items, issuedByName: null, preparerName: 'จินตนา หาญมนตรี',
        hasPdf: false, hasXlsx: false,
        createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
      });
      mockDepositNotices.push(doc);

      pushPricingRequestEvent(pr, user, 'DEPOSIT_NOTICE_DRAFTED_FROM_QUOTATION', pr.status, pr.status,
        `สร้างร่างใบแจ้งยอดเงินรับมัดจำจากใบเสนอราคา ${accepted.number}`);
      return delay({ depositNotice: structuredClone(doc) });
    },

    async downloadCustomerQuotationPdf(id) {
      const quotation = mockCustomerQuotationViewAccess(id);
      return buildMockCustomerQuotationDocument(quotation, 'pdf');
    },

    async downloadCustomerQuotationXlsx(id) {
      const quotation = mockCustomerQuotationViewAccess(id);
      return buildMockCustomerQuotationDocument(quotation, 'xlsx');
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
      // Finding A (financial-integrity review, commit 3): snapshot the catalog selection for
      // every catalog-backed item, then reject the submit unless EVERY item resolved — mirrors
      // PricingRequestService.submit calling snapshotCatalogSelections then re-checking.
      snapshotPricingRequestCatalogSelections(pr);
      submitPricingRequestCatalogGate(pr);
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

    async createCustomerChangeRevision(id, payload) {
      const user = hasRole('sales');
      const parent = requirePricingRequestViewable(id, user);
      const ticket = db.tickets.find((t) => t.id === parent.ticketId);
      if (ticket?.createdById !== user.id) fail('Forbidden', 403);
      if (['DRAFT', 'CANCELLED', 'SUPERSEDED'].includes(parent.status)) {
        fail('Customer-change revisions can only be created from an active submitted pricing request', 409);
      }
      if (!payload.revisionReason?.trim()) fail('revisionReason must not be blank', 400);
      if (!payload.clientRequestId) fail('clientRequestId must be a UUID', 400);
      if (!payload.items?.length) fail('items must not be empty', 400);
      requirePricingRequestItemFieldsValid(payload.items ?? []);
      const parentStatus = parent.status;
      const revisionId = mockPricingRequestSeq++;
      parent.status = 'SUPERSEDED';
      parent.updatedAt = new Date().toISOString();
      const now = new Date().toISOString();
      const revision = {
        ...parent,
        id: revisionId,
        requestCode: nextPricingRequestCode(),
        status: 'DRAFT',
        revisionNo: (parent.revisionNo ?? 1) + 1,
        parentPricingRequestId: parent.id,
        clientRequestId: payload.clientRequestId,
        recipientType: payload.recipientType,
        recipientContactId: payload.recipientContactId ?? null,
        recipientLabel: payload.recipientLabel ?? null,
        requiredDate: payload.requiredDate ?? null,
        customerTargetPrice: payload.customerTargetPrice ?? null,
        targetCurrency: normalizePricingRequestCurrency(payload.targetCurrency),
        note: payload.note ?? null,
        submittedAt: null,
        pickedUpAt: null,
        cancelledAt: null,
        createdAt: now,
        updatedAt: now,
        items: (payload.items ?? []).map((item, i) => ({
          id: mockPricingRequestItemSeq++,
          pricingRequestId: revisionId,
          ...item,
          sortOrder: i,
        })),
        events: [],
        // A revision starts with no attachments of its own — the `...parent` spread above must
        // NOT carry the parent's attachments array forward.
        attachments: [],
      };
      mockPricingRequests.push(revision);
      // Step 5 (design correction 1, "the cascade gap"): also supersede any DRAFT/APPROVED
      // pricing_decision and any non-terminal quotation left over from Steps 3/4 — without this,
      // both stayed silently readable as current after the parent pricing request superseded.
      mockPricingDecisions
        .filter((d) => d.pricingRequestId === parent.id && ['DRAFT', 'APPROVED'].includes(d.status))
        .forEach((d) => { d.status = 'SUPERSEDED'; });
      mockCustomerQuotations
        .filter((q) => q.pricingRequestId === parent.id
          && ['ISSUED', 'READY_TO_ISSUE', 'SENT', 'REVISION_REQUESTED'].includes(q.docStatus))
        .forEach((q) => { q.docStatus = 'SUPERSEDED'; });
      pushPricingRequestEvent(parent, user, 'PRICING_REQUEST_REVISED', parentStatus, 'SUPERSEDED', payload.revisionReason);
      pushPricingRequestEvent(revision, user, 'PRICING_REQUEST_CREATED', null, 'DRAFT', payload.revisionReason);
      return delay({ pricingRequest: buildPricingRequestDetail(revision) });
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

    // ── Pricing Request attachments (V69, review remediation COMMIT 4) ──────
    // Sales-level supporting attachments on the Pricing Request itself — distinct from the raw
    // factory-quote attachments above (uploadFactoryQuoteAttachment etc.), which stay
    // Import/CEO-only. Mirrors PricingRequestService's new uploadAttachment/listAttachments/
    // attachmentFilePath/deleteAttachment/setAttachmentIncludeInFactoryEmail.

    async uploadAttachment(id, file) {
      // Mirrors PricingRequestService.uploadAttachment: owner sales only, DRAFT/MORE_INFO_REQUIRED
      // only. requirePricingRequestViewable already 404s a non-owner on a DRAFT (draft privacy)
      // and 403s a non-owner once the request is visible-but-not-owned — see that helper.
      const user = hasRole('sales');
      const pr = requirePricingRequestViewable(id, user);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);
      if (ticket?.createdById !== user.id) fail('Forbidden', 403);
      if (!['DRAFT', 'MORE_INFO_REQUIRED'].includes(pr.status)) {
        fail('Pricing request attachments can only be uploaded while DRAFT or MORE_INFO_REQUIRED', 409);
      }
      requirePricingRequestDealActive(ticket);
      const attachment = {
        id: mockPricingRequestAttachmentSeq++,
        pricingRequestId: pr.id,
        fileName: file?.name ?? 'attachment',
        mimeType: file?.type ?? null,
        fileSize: file?.size ?? null,
        includeInFactoryEmail: false,
        uploadedBy: user.id,
        uploadedAt: new Date().toISOString(),
      };
      pr.attachments = [attachment, ...(pr.attachments ?? [])];
      pushPricingRequestEvent(pr, user, 'PRICING_REQUEST_UPDATED', pr.status, pr.status, `Attachment uploaded: ${attachment.fileName}`);
      return delay({ attachment });
    },

    async listAttachments(id) {
      const user = requireSession();
      const pr = requirePricingRequestViewable(id, user);
      return delay({ items: pr.attachments ?? [] });
    },

    attachmentUrl(id) {
      return `#mock-pricing-request-attachment-${id}`;
    },

    async deleteAttachment(id) {
      // Mirrors PricingRequestService.deleteAttachment: owner sales only,
      // DRAFT/MORE_INFO_REQUIRED only.
      const user = hasRole('sales');
      const owningPr = mockPricingRequests.find((p) => (p.attachments ?? []).some((a) => a.id === Number(id)));
      if (!owningPr) fail('Pricing request attachment not found', 404);
      const ticket = db.tickets.find((t) => t.id === owningPr.ticketId);
      if (ticket?.createdById !== user.id) fail('Forbidden', 403);
      if (!['DRAFT', 'MORE_INFO_REQUIRED'].includes(owningPr.status)) {
        fail('Pricing request attachments can only be deleted while DRAFT or MORE_INFO_REQUIRED', 409);
      }
      owningPr.attachments = (owningPr.attachments ?? []).filter((a) => a.id !== Number(id));
      return delay({ ok: true });
    },

    async setAttachmentIncludeInFactoryEmail(id, includeInFactoryEmail) {
      // Mirrors PricingRequestService.setAttachmentIncludeInFactoryEmail: import only. No extra
      // status gate — requirePricingRequestViewable already makes a DRAFT request invisible to
      // import entirely, so import can only ever reach an attachment on an already-submitted
      // request.
      const user = hasRole('import');
      const owningPr = mockPricingRequests.find((p) => (p.attachments ?? []).some((a) => a.id === Number(id)));
      if (!owningPr) fail('Pricing request attachment not found', 404);
      requirePricingRequestViewable(owningPr.id, user);
      const attachment = owningPr.attachments.find((a) => a.id === Number(id));
      attachment.includeInFactoryEmail = Boolean(includeInFactoryEmail);
      return delay({ attachment });
    },
  },

  // ── Step 7: Factory Purchase Order and Import Execution — mirrors ProcurementController +
  // ProcurementService (procurement/). Authorization is NOT authoritative (CLAUDE.md); verify
  // role behavior against the real Java service. Import/CEO only, matching the real service's
  // RAW_PO_ROLES — sales/sales_manager cannot reach any method here at all.
  //
  // Source of truth, mirroring the real backend exactly: a PO's items are sourced from the
  // pricing request's currently-APPROVED mockPricingDecisions entry's own pricingCostingId, never
  // re-picked/re-priced at PO time. Simplification vs the real backend (documented, not silent):
  // like every mock costing/decision item above, pricingCostingItemId is stood in by
  // pricingRequestItemId (1:1 within one costing, and the costing is immutable once its decision
  // is approved) rather than a separate synthetic id.
  procurement: {
    async create(pricingRequestId, payload = {}) {
      const user = hasRole('import', 'ceo');
      const pr = findPricingRequestRaw(pricingRequestId);
      const ticket = db.tickets.find((t) => t.id === pr.ticketId);

      if (payload.clientRequestId) {
        const replay = mockFactoryPurchaseOrders.filter(
          (po) => po.createdBy === user.id && po.clientRequestId === payload.clientRequestId);
        if (replay.length > 0) return delay({ factoryPurchaseOrders: replay.map(buildFactoryPurchaseOrderView) });
      }

      if (pr.status !== 'QUOTATION_ACCEPTED') {
        fail(`สร้างใบสั่งซื้อโรงงานได้เฉพาะใบขอราคาที่ลูกค้ายอมรับใบเสนอราคาแล้วเท่านั้น (ปัจจุบัน: ${pr.status})`, 409);
      }
      if (ticket?.salesStage !== 'PROCUREMENT') {
        fail(`สร้างใบสั่งซื้อโรงงานได้หลังจากออกคำขอนำเข้า (Import Request) แล้วเท่านั้น (สถานะดีลปัจจุบัน: ${ticket?.salesStage})`, 409);
      }

      const decision = mockPricingDecisions.find((d) => d.pricingRequestId === pr.id && d.status === 'APPROVED');
      if (!decision) fail('ไม่พบราคาต้นทุนที่ได้รับอนุมัติสำหรับใบขอราคานี้', 409);
      const costing = mockPricingCostings.find((c) => c.id === decision.pricingCostingId);
      if (!costing || costing.items.length === 0) fail('ไม่พบรายการต้นทุนสำหรับสร้างใบสั่งซื้อโรงงาน', 409);

      const byFactory = new Map();
      for (const item of costing.items) {
        if (!byFactory.has(item.factoryName)) byFactory.set(item.factoryName, []);
        byFactory.get(item.factoryName).push(item);
      }

      const created = [];
      for (const [factoryName, items] of byFactory) {
        let po = mockFactoryPurchaseOrders.find(
          (p) => p.pricingRequestId === pr.id && p.factoryName === factoryName);
        if (!po) {
          po = {
            id: mockFactoryPurchaseOrderSeq++,
            poNumber: `FPO-2026-${String(mockFactoryPurchaseOrderSeq).padStart(4, '0')}`,
            pricingRequestId: pr.id,
            ticketId: pr.ticketId,
            factoryName,
            status: 'OPEN',
            supplierProformaRef: null,
            supplierPaymentScheduleNote: null,
            currency: items[0].rawCurrency,
            totalAmount: items.reduce((sum, item) => sum + Number(item.rawUnitPrice) * Number(item.requestedQuantity), 0),
            etd: null, eta: null, containerRef: null, customsStatus: null,
            actualLandedCostThb: null, cancelReason: null,
            clientRequestId: payload.clientRequestId ?? null,
            createdBy: user.id, createdByName: user.name,
            createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
            receivedAt: null, cancelledAt: null,
            items: items.map((item) => ({
              id: mockFactoryPurchaseOrderItemSeq++,
              pricingCostingItemId: item.pricingRequestItemId,
              pricingRequestItemId: item.pricingRequestItemId,
              quantity: item.requestedQuantity,
              unitPrice: item.rawUnitPrice,
              currency: item.rawCurrency,
              lineTotal: Number(item.rawUnitPrice) * Number(item.requestedQuantity),
              estimatedLandedCostPerUnitThb: item.landedCostPerUnitThb,
              estimatedTotalLandedCostThb: item.totalLandedCostThb,
            })),
          };
          mockFactoryPurchaseOrders.push(po);
          pushPricingRequestEvent(pr, user, 'FACTORY_PO_CREATED', pr.status, pr.status,
            `สร้างใบสั่งซื้อโรงงาน ${factoryName} (${po.poNumber})`);
        }
        created.push(po);
      }
      const ceoUsers = db.users.filter((u) => u.role === 'ceo');
      ceoUsers.forEach((ceo) => addNotification(ceo.id, pr.ticketId, ticket?.code, 'FACTORY_PO_CREATED',
        `สร้างใบสั่งซื้อโรงงาน ${created.length} ฉบับสำหรับใบขอราคา ${pr.requestCode}`));
      return delay({ factoryPurchaseOrders: created.map(buildFactoryPurchaseOrderView) });
    },

    async listForPricingRequest(pricingRequestId) {
      hasRole('import', 'ceo');
      const items = mockFactoryPurchaseOrders
        .filter((po) => po.pricingRequestId === Number(pricingRequestId))
        .map(buildFactoryPurchaseOrderView);
      return delay({ factoryPurchaseOrders: items });
    },

    async list(status) {
      hasRole('import', 'ceo');
      const items = mockFactoryPurchaseOrders
        .filter((po) => !status || po.status === status)
        .map(buildFactoryPurchaseOrderView);
      return delay({ factoryPurchaseOrders: items });
    },

    async get(id) {
      hasRole('import', 'ceo');
      const po = findFactoryPurchaseOrderRaw(id);
      return delay({ factoryPurchaseOrder: buildFactoryPurchaseOrderView(po) });
    },

    async recordSupplierProforma(id, payload) {
      const user = hasRole('import', 'ceo');
      const po = findFactoryPurchaseOrderRaw(id);
      if (['RECEIVED', 'CANCELLED'].includes(po.status)) fail('ใบสั่งซื้อนี้ปิดแล้ว ไม่สามารถแก้ไขได้', 409);
      po.supplierProformaRef = payload.supplierProformaRef;
      po.supplierPaymentScheduleNote = payload.supplierPaymentScheduleNote ?? null;
      po.updatedAt = new Date().toISOString();
      const pr = findPricingRequestRaw(po.pricingRequestId);
      pushPricingRequestEvent(pr, user, 'FACTORY_PO_PROFORMA_RECORDED', null, null,
        `บันทึกใบแจ้งหนี้ล่วงหน้า (Proforma) ${payload.supplierProformaRef} (${po.poNumber})`);
      return delay({ factoryPurchaseOrder: buildFactoryPurchaseOrderView(po) });
    },

    async recordShippingDetail(id, payload = {}) {
      const user = hasRole('import', 'ceo');
      const po = findFactoryPurchaseOrderRaw(id);
      if (['RECEIVED', 'CANCELLED'].includes(po.status)) fail('ใบสั่งซื้อนี้ปิดแล้ว ไม่สามารถแก้ไขได้', 409);
      if (payload.containerRef != null) po.containerRef = payload.containerRef;
      if (payload.etd != null) po.etd = payload.etd;
      if (payload.eta != null) po.eta = payload.eta;
      if (payload.customsStatus != null) po.customsStatus = payload.customsStatus;
      if (po.status === 'OPEN') po.status = 'SHIPPING';
      po.updatedAt = new Date().toISOString();
      const pr = findPricingRequestRaw(po.pricingRequestId);
      pushPricingRequestEvent(pr, user, 'FACTORY_PO_SHIPPING_RECORDED', null, null,
        `บันทึกรายละเอียดการขนส่ง (${po.poNumber})`);
      return delay({ factoryPurchaseOrder: buildFactoryPurchaseOrderView(po) });
    },

    async recordGoodsReceived(id, payload) {
      // Step 8: mirrors ProcurementService.recordGoodsReceived — items is OPTIONAL (null/absent
      // defaults every line to its own ordered quantity, zero discrepancy, backward-compatible
      // with the unmodified Step 7 caller); an explicitly-supplied list (even empty) must cover
      // EVERY line on the PO or the call is rejected.
      const user = hasRole('import', 'ceo');
      const po = findFactoryPurchaseOrderRaw(id);
      if (['RECEIVED', 'CANCELLED'].includes(po.status)) fail('ใบสั่งซื้อนี้ปิดแล้ว ไม่สามารถบันทึกรับสินค้าได้อีก', 409);
      const items = payload.items ?? null;
      if (items === null) {
        po.items.forEach((item) => {
          item.qtyReceived = item.quantity;
          item.qcNote = null;
          item.discrepancyQty = 0;
        });
      } else {
        const validIds = new Set(po.items.map((item) => item.id));
        const requestedIds = new Set(items.map((line) => Number(line.itemId)));
        const sameSize = requestedIds.size === validIds.size;
        const covers = sameSize && [...requestedIds].every((itemId) => validIds.has(itemId));
        if (!covers) fail('ต้องระบุจำนวนรับสินค้าให้ครบทุกรายการในใบสั่งซื้อนี้ และรายการที่ระบุต้องอยู่ในใบสั่งซื้อนี้เท่านั้น', 400);
        for (const line of items) {
          const item = po.items.find((it) => it.id === Number(line.itemId));
          item.qtyReceived = moneyValue(line.qtyReceived);
          item.qcNote = line.qcNote ?? null;
          item.discrepancyQty = moneyValue(item.qtyReceived - Number(item.quantity ?? 0));
        }
      }
      po.actualLandedCostThb = payload.actualLandedCostThb;
      po.status = 'RECEIVED';
      po.receivedAt = new Date().toISOString();
      po.updatedAt = po.receivedAt;
      const pr = findPricingRequestRaw(po.pricingRequestId);
      pushPricingRequestEvent(pr, user, 'FACTORY_PO_GOODS_RECEIVED', null, null,
        `รับสินค้าแล้ว ต้นทุนนำเข้าจริง ${payload.actualLandedCostThb} (${po.poNumber})`);
      return delay({ factoryPurchaseOrder: buildFactoryPurchaseOrderView(po) });
    },

    async cancel(id, payload) {
      const user = hasRole('import', 'ceo');
      const po = findFactoryPurchaseOrderRaw(id);
      if (['RECEIVED', 'CANCELLED'].includes(po.status)) fail('ใบสั่งซื้อนี้ปิดแล้ว ไม่สามารถยกเลิกได้', 409);
      po.status = 'CANCELLED';
      po.cancelReason = payload.reason;
      po.cancelledAt = new Date().toISOString();
      po.updatedAt = po.cancelledAt;
      const pr = findPricingRequestRaw(po.pricingRequestId);
      pushPricingRequestEvent(pr, user, 'FACTORY_PO_CANCELLED', null, null,
        `ยกเลิกใบสั่งซื้อโรงงาน: ${payload.reason} (${po.poNumber})`);
      return delay({ factoryPurchaseOrder: buildFactoryPurchaseOrderView(po) });
    },
  },

};
