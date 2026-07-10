import { createDemoDatabase } from '../data/demoData.js';

const db = createDemoDatabase();
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
  { id: 1, currency: 'CNY', rateToThb: 4.85,  effectiveDate: '2026-07-01', updatedAt: new Date().toISOString() },
  { id: 2, currency: 'EUR', rateToThb: 38.50, effectiveDate: '2026-07-01', updatedAt: new Date().toISOString() },
  { id: 3, currency: 'GBP', rateToThb: 44.80, effectiveDate: '2026-07-01', updatedAt: new Date().toISOString() },
  { id: 4, currency: 'JPY', rateToThb: 0.24,  effectiveDate: '2026-07-01', updatedAt: new Date().toISOString() },
  { id: 5, currency: 'THB', rateToThb: 1.00,  effectiveDate: '2026-07-01', updatedAt: new Date().toISOString() },
  { id: 6, currency: 'USD', rateToThb: 35.20, effectiveDate: '2026-07-01', updatedAt: new Date().toISOString() },
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

const mockNoteTemplates = [
  { id: 1, text: 'ราคารวมค่าขนส่งถึงชั้น 1 ของหน่วยงานในเขต กทม. แต่ไม่รวมค่าตัด/ติดตั้ง', defaultSelected: true, sortOrder: 1 },
  { id: 2, text: 'จ่ายเช็คในนาม บจก. จี แอล แอนด์ อาร์ฯ / โอนเข้า กสิกรไทย 003-1-15914-8 (กระแสรายวัน สาขาสุขุมวิท 33)', defaultSelected: true, sortOrder: 2 },
  { id: 3, text: 'กรณีโอนเงินส่ง Pay-in มาที่ e-mail : info@glr.co.th', defaultSelected: true, sortOrder: 3 },
];

const mockDepositNotices = []; // used by both depositNotices and documents API groups
let mockDocSeq = 1;
let mockDocNumberSeq = 1;

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

function findTicketRaw(id) {
  const ticket = db.tickets.find((t) => t.id === id);
  if (!ticket) fail('Ticket not found', 404);
  return ticket;
}

function verifyStatus(ticket, expected) {
  if (ticket.status !== expected) fail(`Expected status '${expected}' but ticket is '${ticket.status}'`, 409);
}

function buildMockQuotationCsv(ticketId, quotationId) {
  const ticket = findTicketRaw(Number(ticketId));
  const quotation = (ticket.quotations ?? []).find((q) => q.id === Number(quotationId));
  if (!quotation) fail('Quotation not found', 404);
  const rows = [
    ['ใบเสนอราคา', quotation.number],
    ['ลูกค้า', ticket.customerName ?? ''],
    ['วันที่', quotation.issuedAt],
    [],
    ['รายละเอียด', 'จำนวน', 'ราคา/หน่วย', 'เป็นเงิน'],
    ...ticket.items
      .filter((it) => it.approvedPrice != null)
      .map((it) => [`${it.brand ?? ''} ${it.model ?? ''}`.trim(), it.qty, it.approvedPrice, it.approvedPrice * it.qty]),
    [],
    ['รวมเป็นเงิน', '', '', quotation.totalAmount],
  ];
  return rows.map((r) => r.join(',')).join('\n');
}

function pushEvent(ticket, actor, kind, fromStatus, toStatus, message, itemSnapshot = null) {
  const nextId = Math.max(...db.tickets.flatMap((t) => t.events.map((e) => e.id)), 0) + 1;
  ticket.events.push({ id: nextId, ticketId: ticket.id, actorId: actor.id, actorName: actor.name, kind, fromStatus, toStatus, message, createdAt: new Date().toISOString(), itemSnapshot });
}

function addNotification(userId, ticketId, ticketCode, type, message) {
  const nextId = Math.max(...db.notifications.map((n) => n.id), 0) + 1;
  db.notifications.unshift({ id: nextId, userId, ticketId, ticketCode, type, message, read: false, createdAt: new Date().toISOString() });
}

function buildTicketDetail(ticket) {
  const project = ticket.projectId ? mockProjects.find((p) => p.id === ticket.projectId) : null;
  const contact = ticket.contactId ? mockContacts.find((c) => c.id === ticket.contactId) : null;
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
    || user?.role === 'supervisor'
    || user?.role === 'sales_manager'
    || employee?.positionTh === 'ผู้จัดการฝ่าย'
  );
}

function dashboardDivisionId(user) {
  return user?.divisionId ?? employeeForUser(user)?.divisionId ?? null;
}

function dashboardEmployeeScope(user) {
  const employee = employeeForUser(user);
  if (['hr', 'admin', 'ceo'].includes(user.role)) return { label: 'all', employees: db.employees };
  if (dashboardManager(user) && dashboardDivisionId(user)) {
    return {
      label: 'division',
      employees: db.employees.filter((item) => item.divisionId === dashboardDivisionId(user)),
    };
  }
  return { label: employee ? 'self' : 'none', employees: employee ? [employee] : [] };
}

function dashboardHeadcount(user) {
  const company = ['hr', 'ceo', 'admin'].includes(user.role);
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
  const allVisible = ['import', 'ceo', 'admin'].includes(user.role);
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
  };
}

function dashboardPending(user, ticketSummary) {
  const employeeScope = dashboardEmployeeScope(user);
  const employeeIds = new Set(employeeScope.employees.map((employee) => employee.id));
  const employeeSelf = employeeScope.label === 'self';
  const manager = employeeScope.label === 'division';
  const hrOrAdmin = ['hr', 'admin'].includes(user.role);
  const profileRequests = hrOrAdmin || employeeSelf
    ? db.profileRequests.filter((request) => employeeIds.has(request.employeeId) && request.status === 'pending').length
    : 0;
  const leave = hrOrAdmin || manager || employeeSelf
    ? db.leaveRequests.filter((request) => employeeIds.has(request.employeeId) && request.status === 'SUBMITTED').length
    : 0;
  const commissions = ['sales_manager', 'ceo', 'admin'].includes(user.role)
    ? db.commissions.filter((record) => ['SUBMITTED', 'MANAGER_APPROVED'].includes(record.status)).length
    : user.role === 'sales'
      ? db.commissions.filter((record) => record.salesRepId === user.id && record.status === 'SUBMITTED').length
      : 0;
  const tickets = ['sales', 'import', 'ceo', 'admin'].includes(user.role)
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
  if (['hr', 'ceo', 'admin'].includes(user.role)) {
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

function managerIdForEmployee(employee) {
  if (!employee || employee.positionTh === 'ผู้จัดการฝ่าย') return null;
  return db.employees.find((item) => item.divisionId === employee.divisionId && item.positionTh === 'ผู้จัดการฝ่าย')?.id ?? null;
}

function canReviewLeave(user, employeeId) {
  if (['hr', 'admin'].includes(user.role)) return true;
  const employee = findEmployee(employeeId);
  return user.employeeId && managerIdForEmployee(employee) === user.employeeId;
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

function doTransition(id, fromStatus, toStatus, kind, actor, message) {
  const ticket = findTicketRaw(id);
  verifyStatus(ticket, fromStatus);
  ticket.status = toStatus;
  ticket.updatedAt = new Date().toISOString().slice(0, 10);
  if (toStatus === 'closed' || toStatus === 'cancelled') ticket.closedAt = ticket.updatedAt;
  pushEvent(ticket, actor, kind, fromStatus, toStatus, message);
  return { ticket: buildTicketDetail(ticket) };
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

export const api = {
  auth: {
    async login(payload) {
      const email = payload?.email?.trim().toLowerCase();
      const requestedRole = payload?.role;
      const user = requestedRole
        ? db.users.find((item) => item.role === requestedRole && item.active)
        : db.users.find((item) => item.email.toLowerCase() === email && item.active);

      if (!user) fail('Invalid email or inactive user', 401);
      if (!requestedRole && payload?.password && payload.password !== user.password) fail('Invalid password', 401);

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
  employees: {
    async list(params = {}) {
      hasRole('hr', 'director', 'admin');
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
      hasRole('hr', 'admin');
      const employee = createEmployeeRecord(payload);
      db.employees.unshift(employee);
      return delay({ employee });
    },
    async get(id) {
      const user = requireSession();
      const employee = findEmployee(id);
      if (user.role === 'employee' && user.employeeId !== employee.id) fail('Forbidden', 403);
      if (user.role === 'supervisor' && user.employeeId !== employee.id) fail('Forbidden', 403);
      return delay({ employee: employeeWithRequestMeta(employee) });
    },
    async update(id, payload) {
      hasRole('hr', 'admin');
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
  profileRequests: {
    async list() {
      const user = requireSession();
      const rows = user.role === 'hr' || user.role === 'admin'
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
      if (request.status === 'approved') applyApprovedProfileRequest(request);
      return delay({ profileRequest: { ...request, employee: findEmployee(request.employeeId) } });
    },
  },
  users: {
    async list() {
      hasRole('admin');
      return delay({ users: db.users.map(publicUser) });
    },
    async create(payload) {
      hasRole('admin');
      const employee = payload.employeeId ? findEmployee(payload.employeeId) : null;
      const user = {
        id: Math.max(...db.users.map((item) => item.id)) + 1,
        email: payload.email,
        password: payload.password || 'demo1234',
        name: employee?.nameTh || payload.name,
        role: payload.role || 'employee',
        employeeId: employee?.id ?? payload.employeeId,
        active: true,
        createdAt: new Date().toISOString().slice(0, 10),
      };
      db.users.unshift(user);
      return delay({ user: publicUser(user) });
    },
    async update(id, payload) {
      hasRole('admin');
      const user = db.users.find((item) => item.id === Number(id));
      if (!user) fail('User not found', 404);
      Object.assign(user, payload);
      return delay({ user: publicUser(user) });
    },
  },

  tickets: {
    async list(params = {}) {
      const user = requireSession();
      if (!['sales', 'import', 'ceo', 'admin'].includes(user.role)) fail('Forbidden', 403);
      let list = structuredClone(db.tickets);
      if (user.role === 'sales') list = list.filter((t) => t.createdById === user.id);
      if (params.status) list = list.filter((t) => t.status === params.status);
      const tickets = list.map((t) => ({
        id: t.id, code: t.code, type: t.type, title: t.title,
        status: t.status, priority: t.priority,
        createdById: t.createdById, createdByName: t.createdByName,
        assignedToId: t.assignedToId, assignedToName: t.assignedToName,
        customerName: t.customerName, note: t.note,
        createdAt: t.createdAt, updatedAt: t.updatedAt, closedAt: t.closedAt,
        itemCount: t.items.length,
      }));
      return delay({ tickets });
    },

    async get(id) {
      const user = requireSession();
      if (!['sales', 'import', 'ceo', 'admin'].includes(user.role)) fail('Forbidden', 403);
      const ticket = structuredClone(db.tickets.find((t) => t.id === Number(id)));
      if (!ticket) fail('Ticket not found', 404);
      if (user.role === 'sales' && ticket.createdById !== user.id) fail('Forbidden', 403);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async create(payload) {
      const user = hasRole('sales', 'admin');
      const nextId = Math.max(...db.tickets.map((t) => t.id)) + 1;
      const code = `PR-2026-${String(nextId).padStart(4, '0')}`;
      const now = new Date().toISOString();
      const ticket = {
        id: nextId, code, type: 'PRICE_REQUEST',
        title: payload.title, status: 'submitted',
        priority: payload.priority || 'NORMAL',
        createdById: user.id, createdByName: user.name,
        assignedToId: null, assignedToName: null,
        customerName: payload.customerName || null,
        customerId: payload.customerId ?? null,
        projectId: payload.projectId ?? null,
        contactId: payload.contactId ?? null,
        note: payload.note || null,
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
        events: [{ id: nextId * 1000, ticketId: nextId, actorId: user.id, actorName: user.name, kind: 'SUBMITTED', fromStatus: null, toStatus: 'submitted', message: null, createdAt: now }],
        quotation: null,
      };
      db.tickets.unshift(ticket);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async submit(id) {
      const user = hasRole('sales', 'admin');
      return delay(doTransition(Number(id), 'draft', 'submitted', 'SUBMITTED', user, null));
    },

    async pickup(id) {
      const user = hasRole('import', 'admin');
      const ticket = findTicketRaw(Number(id));
      verifyStatus(ticket, 'submitted');
      ticket.status = 'in_review';
      ticket.assignedToId = user.id;
      ticket.assignedToName = user.name;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'PICKED_UP', 'submitted', 'in_review', null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async proposePrice(id, payload) {
      const user = hasRole('import', 'admin');
      const ticket = findTicketRaw(Number(id));
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
      const st = ticket.status;
      const isOwner = user.id === ticket.createdById;
      const salesCanEdit = ['sales', 'admin'].includes(user.role) && isOwner
        && ['submitted', 'in_review', 'price_proposed'].includes(st);
      const importCanEdit = ['import', 'admin'].includes(user.role)
        && ['in_review', 'price_proposed'].includes(st);
      if (!salesCanEdit && !importCanEdit) fail('ไม่มีสิทธิ์แก้ไขรายการสินค้าในสถานะนี้', 403);
      ticket.items = (payload.items || []).map((item, i) => ({
        ...ticket.items[i],
        brand: item.brand, model: item.model,
        color: item.color, texture: item.texture, size: item.size,
        factory: item.factory ?? ticket.items[i]?.factory ?? null,
        qty: item.qty, qtySqm: item.qtySqm ?? ticket.items[i]?.qtySqm ?? null,
        rawPrice: item.rawPrice ?? ticket.items[i]?.rawPrice ?? null,
        rawCurrency: item.rawCurrency ?? ticket.items[i]?.rawCurrency ?? null,
        rawUnit: item.rawUnit ?? ticket.items[i]?.rawUnit ?? null,
        proposedPrice: item.proposedPrice ?? ticket.items[i]?.proposedPrice ?? null,
        id: ticket.items[i]?.id ?? ticket.id * 100 + i,
        ticketId: ticket.id, sortOrder: i,
      }));
      ticket.hasEdits = true;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'EDITED', st, st, payload.note || null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async calculatePrices(id) {
      hasRole('ceo', 'admin');
      const ticket = findTicketRaw(Number(id));
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
        const fxRate = fxMap[item.rawCurrency ?? 'THB'] ?? 1;

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

        return { ...item, calcedCost, calcedPrice, calcConfigVersion: cfg.version, proposedPrice: calcedPrice };
      });

      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async approve(id) {
      const user = hasRole('ceo', 'admin');
      const ticket = findTicketRaw(Number(id));
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
      const user = hasRole('ceo', 'admin');
      const ticket = findTicketRaw(Number(id));
      verifyStatus(ticket, 'price_proposed');
      ticket.status = 'in_review';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'REJECTED', 'price_proposed', 'in_review', payload.reason);
      addNotification(ticket.assignedToId || 7, ticket.id, ticket.code, 'REJECTED', `Ticket ${ticket.code} ถูกตีกลับ — กรุณาแก้ไขราคาเสนอ`);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async quotation(id) {
      const user = hasRole('sales', 'admin');
      const ticket = findTicketRaw(Number(id));
      if (ticket.status !== 'approved' && ticket.status !== 'quotation_issued') {
        fail(`Expected status 'approved' or 'quotation_issued' but ticket is '${ticket.status}'`, 409);
      }
      const fromStatus = ticket.status;
      if (ticket.createdById !== user.id && user.role !== 'admin') fail('Forbidden', 403);
      const total = ticket.items.reduce((sum, item) => sum + (item.approvedPrice || 0) * item.qty, 0);

      // init quotations array if not present
      if (!ticket.quotations) ticket.quotations = ticket.quotation ? [{ ...ticket.quotation, quotationVersion: 1, docStatus: 'SUPERSEDED' }] : [];

      // supersede existing non-superseded quotations
      ticket.quotations.forEach((q) => { if (q.docStatus !== 'SUPERSEDED') q.docStatus = 'SUPERSEDED'; });

      const nextVersion = ticket.quotations.length + 1;
      const nextQNum = db.tickets.flatMap((t) => t.quotations ?? (t.quotation ? [t.quotation] : [])).length + 1;
      const newQuotation = {
        id: nextQNum, ticketId: ticket.id,
        number: `QT-2026-${String(nextQNum).padStart(4, '0')}`,
        issuedById: user.id, issuedByName: user.name,
        issuedAt: new Date().toISOString(), pdfPath: null,
        totalAmount: total, currency: 'THB',
        quotationVersion: nextVersion, docStatus: 'DRAFT',
      };
      ticket.quotations.unshift(newQuotation); // newest first
      ticket.quotation = newQuotation; // backward compat

      ticket.status = 'quotation_issued';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'QUOTATION_ISSUED', fromStatus, 'quotation_issued', null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async downloadQuotationXlsx(ticketId, quotationId) {
      const csv = buildMockQuotationCsv(ticketId, quotationId);
      return new Blob([csv], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
    },

    async downloadQuotationPdf(ticketId, quotationId) {
      const csv = buildMockQuotationCsv(ticketId, quotationId);
      return new Blob([csv], { type: 'application/pdf' });
    },

    async close(id) {
      const user = requireSession();
      return delay(doTransition(Number(id), 'document_issued', 'closed', 'CLOSED', user, null));
    },

    async cancel(id) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
      if (ticket.status === 'closed' || ticket.status === 'cancelled') fail('Cannot cancel', 409);
      const prev = ticket.status;
      ticket.status = 'cancelled';
      ticket.closedAt = new Date().toISOString().slice(0, 10);
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'CANCELLED', prev, 'cancelled', null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async comment(id, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(id));
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
  },

  leave: {
    async employees() {
      const user = requireSession();
      const includeAll = ['hr', 'ceo', 'admin'].includes(user.role);
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
      if (!['hr', 'ceo', 'admin'].includes(user.role) && employeeId !== user.employeeId && !canReviewLeave(user, employeeId)) fail('Forbidden', 403);
      findEmployee(employeeId);
      const year = Number(params.year || new Date().getFullYear());
      return delay({ balances: db.leaveTypes.map((type) => leaveBalance(employeeId, type, year)) });
    },

    async list(params = {}) {
      const user = requireSession();
      let list = db.leaveRequests;
      const includeAll = ['hr', 'ceo', 'admin'].includes(user.role);
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

  overtime: {
    async employees() {
      const user = requireSession();
      const includeAll = ['hr', 'ceo', 'admin'].includes(user.role);
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

    async list(params = {}) {
      const user = requireSession();
      let list = db.overtimeRequests;
      const includeAll = ['hr', 'ceo', 'admin'].includes(user.role);
      if (!includeAll) list = list.filter((item) => item.employeeId === user.employeeId || canReviewLeave(user, item.employeeId));
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
      if (employeeId !== user.employeeId && !canReviewLeave(user, employeeId)) fail('Forbidden', 403);
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
        if (!canReviewLeave(user, request.employeeId)) fail('Forbidden', 403);
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
        if (!canReviewLeave(user, request.employeeId)) fail('Forbidden', 403);
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
      const approver = canReviewLeave(user, request.employeeId);
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

  commissions: {
    async list(params = {}) {
      const user = requireSession();
      if (!['sales', 'sales_manager', 'ceo', 'admin'].includes(user.role)) fail('Forbidden', 403);
      let list = db.commissions;
      if (user.role === 'sales') list = list.filter((item) => item.salesRepId === user.id);
      if (params.payrollMonth) list = list.filter((item) => commissionMonth(item.payrollMonth) === params.payrollMonth.slice(0, 7));
      return delay({ commissions: list.map(buildCommissionRecord) });
    },

    async create(payload) {
      const user = hasRole('sales', 'sales_manager', 'ceo', 'admin');
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
      hasRole('sales_manager', 'ceo', 'admin');
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
      const user = hasRole('sales_manager', 'ceo', 'admin');
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
      const user = hasRole('sales_manager', 'ceo', 'admin');
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
      const user = hasRole('sales_manager', 'ceo', 'admin');
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
      if (!['sales', 'sales_manager', 'ceo', 'admin'].includes(user.role)) fail('Forbidden', 403);
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
      hasRole('hr', 'admin');
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
        },
      });
    },
  },

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
  },

  factoryConfigs: {
    async list() {
      requireSession();
      return delay({ factories: mockFactoryConfigs });
    },
    async sendEmail(ticketId, payload) {
      requireSession();
      console.log(`[mock] Factory email sent | ticket=${ticketId} factory=${payload.factory} to=${payload.to}`);
      return delay({ status: 'sent' });
    },
  },

  fxRates: {
    async list() {
      requireSession();
      return delay({ fxRates: structuredClone(mockFxRates) });
    },
    async upsert(currency, payload) {
      hasRole('ceo', 'admin');
      const existing = mockFxRates.find((r) => r.currency === currency.toUpperCase());
      if (existing) {
        existing.rateToThb = payload.rateToThb;
        existing.effectiveDate = payload.effectiveDate ?? new Date().toISOString().slice(0, 10);
        existing.updatedAt = new Date().toISOString();
        return delay({ fxRate: structuredClone(existing) });
      }
      const newRate = {
        id: mockFxRates.length + 1, currency: currency.toUpperCase(),
        rateToThb: payload.rateToThb,
        effectiveDate: payload.effectiveDate ?? new Date().toISOString().slice(0, 10),
        updatedAt: new Date().toISOString(),
      };
      mockFxRates.push(newRate);
      return delay({ fxRate: structuredClone(newRate) });
    },
  },

  priceCalcConfigs: {
    async list() {
      requireSession();
      return delay({ configs: structuredClone(mockPriceCalcConfigs.filter((c) => c.isCurrent)) });
    },
    async update(payload) {
      hasRole('ceo', 'admin');
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

  customers: {
    async create(payload) {
      requireSession();
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
      requireSession();
      const contact = { id: mockContactSeq++, customerId: Number(customerId), ...payload };
      mockContacts.push(contact);
      return delay({ contact });
    },
    async projects(customerId) {
      requireSession();
      return delay({ projects: mockProjects.filter((p) => p.customerId === Number(customerId)) });
    },
    async createProject(customerId, payload) {
      requireSession();
      const project = { id: mockProjectSeq++, customerId: Number(customerId), name: payload.name };
      mockProjects.push(project);
      return delay({ project });
    },
  },

  depositNotices: {
    async noteTemplates() {
      requireSession();
      return delay({ templates: mockNoteTemplates });
    },

    async createDraft(ticketId, payload) {
      requireSession();
      const ticket = findTicketRaw(Number(ticketId));
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
      requireSession();
      return delay({ depositNotices: structuredClone(mockDepositNotices.filter((d) => d.ticketId === Number(ticketId))) });
    },

    async get(docId) {
      requireSession();
      const doc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!doc) fail('Deposit notice not found', 404);
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
      requireSession();
      const doc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!doc) fail('Deposit notice not found', 404);
      // Return HTML string directly (not wrapped in JSON)
      return mockPreviewHtml(buildMockDoc(doc));
    },

    async issue(docId) {
      const user = requireSession();
      const doc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!doc) fail('Deposit notice not found', 404);
      if (doc.status !== 'DRAFT') fail('Not a draft', 409);
      const ticket = findTicketRaw(doc.ticketId);

      // Supersede previous issued docs
      mockDepositNotices.forEach((d) => { if (d.ticketId === doc.ticketId && d.id !== doc.id && d.status === 'ISSUED') d.status = 'SUPERSEDED'; });

      const thaiYear = new Date().getFullYear() + 543;
      doc.docNumber = `GLRD${String(thaiYear).slice(-2)}${String(mockDocNumberSeq++).padStart(3,'0')}`;
      doc.issueDate = new Date().toISOString().slice(0, 10);
      doc.status = 'ISSUED';
      doc.issuedByName = user.name;
      doc.updatedAt = new Date().toISOString();

      // Transition ticket to document_issued
      ticket.status = 'document_issued';
      ticket.updatedAt = doc.updatedAt;
      pushEvent(ticket, user, 'DOCUMENT_ISSUED', 'approved', 'document_issued', `เอกสาร ${doc.docNumber} ออกแล้ว`);

      return delay({ depositNotice: structuredClone(doc) });
    },

    async downloadXlsx(docId) {
      requireSession();
      const doc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!doc) fail('Deposit notice not found', 404);
      const html = mockPreviewHtml(buildMockDoc(doc));
      return new Blob([html], { type: 'text/html' });
    },

    async downloadPdf(docId) {
      requireSession();
      const doc = mockDepositNotices.find((d) => d.id === Number(docId));
      if (!doc) fail('Deposit notice not found', 404);
      const html = mockPreviewHtml(buildMockDoc(doc));
      return new Blob([html], { type: 'application/pdf' });
    },
  },

};
