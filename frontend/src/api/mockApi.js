import { createDemoDatabase } from '../data/demoData.js';

const db = createDemoDatabase();
let sessionUser = null;

// ── Mock in-memory document store ─────────────────────────────────────────────
const mockCustomers = [
  { id: 1, name: 'บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด',  taxId: '0105565012345', address: '123 ถนนสุขุมวิท แขวงคลองเตย กรุงเทพฯ 10110', branch: 'สำนักงานใหญ่', phone: '02-123-4567' },
  { id: 2, name: 'บริษัท ไทยแลนด์ ดีเวลลอปเมนท์ จำกัด', taxId: '0105556789012', address: '456 ถนนรัชดาภิเษก แขวงลาดยาว กรุงเทพฯ 10900',  branch: 'สำนักงานใหญ่', phone: '02-234-5678' },
  { id: 3, name: 'บริษัท พรีเมียม ดีไซน์ กรุ๊ป จำกัด',   taxId: '0105578901234', address: '789 ถนนพระราม 4 แขวงพระโขนง กรุงเทพฯ 10260',    branch: 'สำนักงานใหญ่', phone: '02-345-6789' },
  { id: 4, name: 'บริษัท เรืองแสง พร็อพเพอร์ตี้ จำกัด',  taxId: '0105591234567', address: '321 ถนนนวมินทร์ แขวงคลองกุ่ม กรุงเทพฯ 10240',  branch: 'สำนักงานใหญ่', phone: '02-456-7890' },
];

const mockNoteTemplates = [
  { id: 1, text: 'ราคารวมค่าขนส่งถึงชั้น 1 ของหน่วยงานในเขต กทม. แต่ไม่รวมค่าตัด/ติดตั้ง', defaultSelected: true, sortOrder: 1 },
  { id: 2, text: 'จ่ายเช็คในนาม บจก. จี แอล แอนด์ อาร์ฯ / โอนเข้า กสิกรไทย 003-1-15914-8 (กระแสรายวัน สาขาสุขุมวิท 33)', defaultSelected: true, sortOrder: 2 },
  { id: 3, text: 'กรณีโอนเงินส่ง Pay-in มาที่ e-mail : info@glr.co.th', defaultSelected: true, sortOrder: 3 },
];

const mockDocuments = []; // { id, ticketId, docType, version, docNumber, status, customerName, ... items:[], notes:[] }
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
  return safe;
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

function pushEvent(ticket, actor, kind, fromStatus, toStatus, message) {
  const nextId = Math.max(...db.tickets.flatMap((t) => t.events.map((e) => e.id)), 0) + 1;
  ticket.events.push({ id: nextId, ticketId: ticket.id, actorId: actor.id, actorName: actor.name, kind, fromStatus, toStatus, message, createdAt: new Date().toISOString() });
}

function addNotification(userId, ticketId, ticketCode, type, message) {
  const nextId = Math.max(...db.notifications.map((n) => n.id), 0) + 1;
  db.notifications.unshift({ id: nextId, userId, ticketId, ticketCode, type, message, read: false, createdAt: new Date().toISOString() });
}

function buildTicketDetail(ticket) {
  return { summary: { id: ticket.id, code: ticket.code, type: ticket.type, title: ticket.title, status: ticket.status, priority: ticket.priority, createdById: ticket.createdById, createdByName: ticket.createdByName, assignedToId: ticket.assignedToId, assignedToName: ticket.assignedToName, customerName: ticket.customerName, note: ticket.note, createdAt: ticket.createdAt, updatedAt: ticket.updatedAt, closedAt: ticket.closedAt, itemCount: ticket.items.length, hasEdits: ticket.hasEdits ?? false }, items: ticket.items, events: ticket.events, quotation: ticket.quotation };
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
      return delay({ ticket: { summary: { id: ticket.id, code: ticket.code, type: ticket.type, title: ticket.title, status: ticket.status, priority: ticket.priority, createdById: ticket.createdById, createdByName: ticket.createdByName, assignedToId: ticket.assignedToId, assignedToName: ticket.assignedToName, customerName: ticket.customerName, note: ticket.note, createdAt: ticket.createdAt, updatedAt: ticket.updatedAt, closedAt: ticket.closedAt, itemCount: ticket.items.length }, items: ticket.items, events: ticket.events, quotation: ticket.quotation } });
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
        note: payload.note || null,
        createdAt: now.slice(0, 10), updatedAt: now.slice(0, 10), closedAt: null,
        items: (payload.items || []).map((item, i) => ({
          id: nextId * 100 + i, ticketId: nextId,
          brand: item.brand, model: item.model,
          color: item.color, texture: item.texture, size: item.size,
          qty: item.qty,
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
      pushEvent(ticket, user, 'PRICE_PROPOSED', 'in_review', 'price_proposed', payload.note || null);
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
        qty: item.qty,
        proposedPrice: item.proposedPrice ?? ticket.items[i]?.proposedPrice ?? null,
        id: ticket.items[i]?.id ?? ticket.id * 100 + i,
        ticketId: ticket.id, sortOrder: i,
      }));
      ticket.hasEdits = true;
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'EDITED', st, st, payload.note || null);
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
      verifyStatus(ticket, 'approved');
      if (ticket.createdById !== user.id && user.role !== 'admin') fail('Forbidden', 403);
      const total = ticket.items.reduce((sum, item) => sum + (item.approvedPrice || 0) * item.qty, 0);
      const nextQNum = db.tickets.filter((t) => t.quotation).length + 1;
      ticket.quotation = { id: nextQNum, ticketId: ticket.id, number: `QT-2026-${String(nextQNum).padStart(4, '0')}`, issuedById: user.id, issuedByName: user.name, issuedAt: new Date().toISOString(), pdfPath: null, totalAmount: total, currency: 'THB' };
      ticket.status = 'quotation_issued';
      ticket.updatedAt = new Date().toISOString().slice(0, 10);
      pushEvent(ticket, user, 'QUOTATION_ISSUED', 'approved', 'quotation_issued', null);
      return delay({ ticket: buildTicketDetail(ticket) });
    },

    async close(id) {
      const user = requireSession();
      return delay(doTransition(Number(id), 'quotation_issued', 'closed', 'CLOSED', user, null));
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
      // delegate to documents.createDraft (defined below — works at call time)
      return api.documents.createDraft(ticketId, payload);
    },

    async listDocs(ticketId) {
      return api.documents.listByTicket(ticketId);
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

  dashboard: {
    async summary() {
      const user = requireSession();
      if (!['sales', 'import', 'ceo', 'admin'].includes(user.role)) fail('Forbidden', 403);
      const list = user.role === 'sales'
        ? db.tickets.filter((t) => t.createdById === user.id)
        : db.tickets;
      const now = new Date();
      const monthStart = new Date(now.getFullYear(), now.getMonth(), 1).toISOString().slice(0, 10);
      const threeDaysAgo = new Date(now - 3 * 86400000).toISOString().slice(0, 10);
      return delay({
        summary: {
          totalOpen: list.filter((t) => !['closed', 'cancelled'].includes(t.status)).length,
          submitted: list.filter((t) => t.status === 'submitted').length,
          inReview: list.filter((t) => t.status === 'in_review').length,
          priceProposed: list.filter((t) => t.status === 'price_proposed').length,
          approved: list.filter((t) => t.status === 'approved').length,
          quotationIssued: list.filter((t) => t.status === 'quotation_issued').length,
          closedThisMonth: list.filter((t) => t.status === 'closed' && t.closedAt >= monthStart).length,
          cancelledThisMonth: list.filter((t) => t.status === 'cancelled' && t.updatedAt >= monthStart).length,
          overdueOver3Days: list.filter((t) => !['closed', 'cancelled', 'draft'].includes(t.status) && t.createdAt < threeDaysAgo).length,
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

  customers: {
    async search(q) {
      requireSession();
      const lower = (q ?? '').toLowerCase();
      const results = lower
        ? mockCustomers.filter((c) => c.name.toLowerCase().includes(lower) || (c.taxId ?? '').includes(lower))
        : mockCustomers;
      return delay({ customers: results });
    },
  },

  documents: {
    async noteTemplates() {
      requireSession();
      return delay({ templates: mockNoteTemplates });
    },

    async createDraft(ticketId, payload) {
      const user = requireSession();
      const ticket = findTicketRaw(Number(ticketId));
      if (!['approved', 'document_issued'].includes(ticket.status)) fail('Ticket must be approved', 409);

      // Auto-build items from approved ticket items
      const items = payload.items?.length ? payload.items : ticket.items
        .filter((it) => it.approvedPrice != null)
        .map((it, idx) => {
          const desc = [it.brand, it.model, it.color, it.texture, it.size].filter(Boolean).join(' ');
          return { seq: idx + 1, description: desc, qty: Number(it.qty), unit: 'แผ่น', unitPrice: Number(it.approvedPrice), discountLabel: null, netUnitPrice: Number(it.approvedPrice) };
        });

      const notes = payload.notes ?? mockNoteTemplates.filter((t) => t.defaultSelected).map((t) => t.text);
      const nextVer = mockDocuments.filter((d) => d.ticketId === Number(ticketId)).length + 1;

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
      mockDocuments.push(doc);
      return delay({ document: structuredClone(doc) });
    },

    async listByTicket(ticketId) {
      requireSession();
      return delay({ documents: structuredClone(mockDocuments.filter((d) => d.ticketId === Number(ticketId))) });
    },

    async get(docId) {
      requireSession();
      const doc = mockDocuments.find((d) => d.id === Number(docId));
      if (!doc) fail('Document not found', 404);
      return delay({ document: structuredClone(doc) });
    },

    async update(docId, payload) {
      requireSession();
      const doc = mockDocuments.find((d) => d.id === Number(docId));
      if (!doc) fail('Document not found', 404);
      if (doc.status !== 'DRAFT') fail('Document is not draft', 409);
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
      return delay({ document: structuredClone(doc) });
    },

    async preview(docId) {
      requireSession();
      const doc = mockDocuments.find((d) => d.id === Number(docId));
      if (!doc) fail('Document not found', 404);
      // Return HTML string directly (not wrapped in JSON)
      return mockPreviewHtml(buildMockDoc(doc));
    },

    async issue(docId) {
      const user = requireSession();
      const doc = mockDocuments.find((d) => d.id === Number(docId));
      if (!doc) fail('Document not found', 404);
      if (doc.status !== 'DRAFT') fail('Not a draft', 409);
      const ticket = findTicketRaw(doc.ticketId);

      // Supersede previous issued docs
      mockDocuments.forEach((d) => { if (d.ticketId === doc.ticketId && d.id !== doc.id && d.status === 'ISSUED') d.status = 'SUPERSEDED'; });

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

      return delay({ document: structuredClone(doc) });
    },

    async downloadXlsx(docId) {
      requireSession();
      const doc = mockDocuments.find((d) => d.id === Number(docId));
      if (!doc) fail('Document not found', 404);
      const html = mockPreviewHtml(buildMockDoc(doc));
      return new Blob([html], { type: 'text/html' });
    },
  },

};
