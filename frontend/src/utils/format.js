const thaiMonths = ['ม.ค.', 'ก.พ.', 'มี.ค.', 'เม.ย.', 'พ.ค.', 'มิ.ย.', 'ก.ค.', 'ส.ค.', 'ก.ย.', 'ต.ค.', 'พ.ย.', 'ธ.ค.'];

export function formatThaiDate(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return `${date.getDate()} ${thaiMonths[date.getMonth()]} ${date.getFullYear() + 543}`;
}

export function formatShortDate(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return `${String(date.getDate()).padStart(2, '0')}/${String(date.getMonth() + 1).padStart(2, '0')}/${date.getFullYear() + 543}`;
}

export function formatMoney(value) {
  if (value === null || value === undefined || value === '') return '-';
  return `฿${Number(value).toLocaleString('en-US')}`;
}

export function initialsFromName(name = '') {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0])
    .join('')
    .toUpperCase() || 'GL';
}

export function roleLabel(role) {
  const labels = {
    hr: 'HR',
    employee: 'EMPLOYEE',
    sales: 'SALES',
    sales_manager: 'SALES MANAGER',
    import: 'IMPORT',
    ceo: 'CEO',
    admin: 'ADMIN',
  };
  return labels[role] ?? role?.toUpperCase() ?? '-';
}

export function ticketStatusLabel(status) {
  const map = {
    draft:            { label: 'แบบร่าง',          tone: 'neutral' },
    submitted:        { label: 'รอรับเรื่องจากฝ่าย Import', tone: 'warning' },
    in_review:        { label: 'กำลังดำเนินการ',    tone: 'info' },
    price_proposed:   { label: 'รอการอนุมัติ',      tone: 'warning' },
    approved:         { label: 'อนุมัติแล้ว',       tone: 'success' },
    rejected:         { label: 'ตีกลับ',            tone: 'danger' },
    quotation_issued: { label: 'ออกใบเสนอราคาแล้ว', tone: 'success' },
    document_issued:  { label: 'ออกใบแจ้งยอดแล้ว',  tone: 'success' },
    closed:           { label: 'ปิดแล้ว',           tone: 'neutral' },
    cancelled:        { label: 'ยกเลิกแล้ว',        tone: 'danger' },
  };
  return map[status] ?? { label: status, tone: 'neutral' };
}

export function ticketPriorityLabel(priority) {
  const map = {
    LOW:    { label: 'ต่ำ',   tone: 'neutral' },
    NORMAL: { label: 'กลาง',  tone: 'warning' },
    HIGH:   { label: 'สูง',   tone: 'danger' },
  };
  return map[priority] ?? { label: priority, tone: 'neutral' };
}

export function requestStatus(status) {
  const map = {
    pending: { label: 'รออนุมัติ', tone: 'warning' },
    approved: { label: 'อนุมัติแล้ว', tone: 'success' },
    rejected: { label: 'ปฏิเสธแล้ว', tone: 'danger' },
  };
  return map[status] ?? { label: status, tone: 'neutral' };
}

// Commission approval status -> StatusBadge tone. Canonical source; do not
// re-add a page-local `statusInfo`/map for commission status elsewhere.
export function commissionStatusLabel(status) {
  const map = {
    SUBMITTED: { label: 'รอผู้จัดการ', tone: 'warning' },
    MANAGER_APPROVED: { label: 'รอ CEO', tone: 'info' },
    APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
    REJECTED: { label: 'ปฏิเสธแล้ว', tone: 'danger' },
    VOID: { label: 'ยกเลิก', tone: 'danger' },
  };
  return map[status] ?? { label: status, tone: 'neutral' };
}

// Overtime approval status -> StatusBadge tone. Canonical source; do not
// re-add a page-local `statusInfo`/map for overtime status elsewhere.
export function overtimeStatusLabel(status) {
  const map = {
    SUBMITTED: { label: 'รอผู้จัดการ', tone: 'warning' },
    MANAGER_APPROVED: { label: 'รอ CEO', tone: 'info' },
    APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
    REJECTED: { label: 'ปฏิเสธแล้ว', tone: 'danger' },
    CANCELLED: { label: 'ยกเลิกแล้ว', tone: 'neutral' },
  };
  return map[status] ?? { label: status || '-', tone: 'neutral' };
}

// Leave request status -> StatusBadge tone. Canonical source; do not
// re-add a page-local `statusInfo`/map for leave status elsewhere.
export function leaveStatusLabel(status) {
  const map = {
    SUBMITTED: { label: 'รออนุมัติ', tone: 'warning' },
    APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
    REJECTED: { label: 'ปฏิเสธแล้ว', tone: 'danger' },
    CANCELLED: { label: 'ยกเลิกแล้ว', tone: 'neutral' },
    AUTO_REJECTED: { label: 'โควตาไม่พอ', tone: 'danger' },
  };
  return map[status] ?? { label: status || '-', tone: 'neutral' };
}

// Project sales-pipeline stage -> StatusBadge tone (V50). Canonical source; do
// not re-add a page-local map for project stage labels elsewhere. Stage order/
// gates/phases live in features/tickets/stageMeta.js.
export function dealStageLabel(stage) {
  const map = {
    LEAD_APPROACH:       { label: 'เข้าถึงเจ้าของ/ผู้ออกแบบโครงการ', tone: 'neutral' },
    PRESENTATION:        { label: 'นำเสนอสินค้า', tone: 'info' },
    SPEC_APPROVED:       { label: 'ผู้ออกแบบอนุมัติสเปค', tone: 'info' },
    QUOTE_DESIGN_SIDE:   { label: 'เสนอราคาผู้ออกแบบ/เจ้าของ', tone: 'info' },
    OWNER_SIGNOFF:       { label: 'เจ้าของอนุมัติสเปค', tone: 'success' },
    AWAITING_BUYER:      { label: 'รอผลประมูล / รอผู้ซื้อ', tone: 'warning' },
    QUOTE_BUYER:         { label: 'เสนอราคาผู้ซื้อ/ผู้รับเหมา', tone: 'info' },
    NEGOTIATION:         { label: 'เจรจา ติดตามใบสั่งซื้อ/มัดจำ', tone: 'warning' },
    ORDER_RECEIVED:      { label: 'ได้รับใบสั่งซื้อ', tone: 'success' },
    DEPOSIT_RECEIVED:    { label: 'ได้รับเงินมัดจำ', tone: 'success' },
    PROCUREMENT:         { label: 'จัดซื้อและนำเข้าสินค้า', tone: 'info' },
    DELIVERY_SCHEDULING: { label: 'นัดส่งสินค้า / นัดรับเงินส่วนที่เหลือ', tone: 'warning' },
    DELIVERED:           { label: 'ส่งมอบสินค้าครบถ้วน', tone: 'success' },
    CLOSED_PAID:         { label: 'ปิดงาน — รับเงินครบถ้วน', tone: 'success' },
  };
  return map[stage] ?? { label: stage || '-', tone: 'neutral' };
}

// Project lost reason -> StatusBadge tone (V50). Canonical source.
export function dealLostReasonLabel(reason) {
  const map = {
    PRODUCT_FIT:       'ไม่มีสินค้าที่ลูกค้าต้องการ',
    PRICE:             'ราคา',
    LEAD_TIME:         'ระยะเวลาส่งมอบ',
    PAYMENT_TERMS:     'เงื่อนไขการชำระเงิน',
    RELATIONSHIP:      'ความสัมพันธ์ / connection',
    PROJECT_ON_HOLD:   'โครงการหยุดก่อสร้างชั่วคราว',
    PROJECT_CANCELLED: 'โครงการหยุดก่อสร้างถาวร',
    ALREADY_PURCHASED: 'ลูกค้าสั่งซื้อกระเบื้องไปหมดแล้ว',
  };
  return { label: map[reason] ?? reason ?? '-', tone: 'danger' };
}

export function dealLifecycleLabel(value) {
  const map = {
    ACTIVE:      { label: 'กำลังดำเนินการ', tone: 'success' },
    ON_HOLD:     { label: 'พักไว้ชั่วคราว', tone: 'warning' },
    DORMANT:     { label: 'พักยาว (dormant)', tone: 'neutral' },
    CLOSED_LOST: { label: 'เสียงาน', tone: 'danger' },
    CANCELLED:   { label: 'ยกเลิก', tone: 'danger' },
    COMPLETED:   { label: 'เสร็จสมบูรณ์', tone: 'success' },
  };
  return map[value] ?? { label: value || '-', tone: 'neutral' };
}

export function tenderRequirementLabel(value) {
  const map = {
    REQUIRED: 'ต้องประมูล',
    NOT_REQUIRED: 'ไม่ต้องประมูล',
    UNKNOWN: 'ยังไม่ทราบ',
  };
  return { label: map[value] ?? value ?? '-', tone: value === 'REQUIRED' ? 'warning' : 'neutral' };
}

export function depositPolicyLabel(value) {
  const map = {
    REQUIRED: 'ต้องเก็บมัดจำ',
    NOT_REQUIRED: 'ไม่เก็บมัดจำ',
    WAIVED: 'ยกเว้นมัดจำ',
    CREDIT_CUSTOMER: 'ลูกค้าเครดิต',
  };
  const tone = value === 'REQUIRED' ? 'neutral' : value === 'CREDIT_CUSTOMER' ? 'info' : 'warning';
  return { label: map[value] ?? value ?? '-', tone };
}

export function entryChannelLabel(value) {
  const map = {
    DESIGNER_LED: 'ผู้ออกแบบนำดีล',
    OWNER_DIRECT: 'เจ้าของติดต่อโดยตรง',
    BUYER_DIRECT: 'ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง',
  };
  return { label: map[value] ?? value ?? '-', tone: 'neutral' };
}

export function quotationRecipientLabel(value) {
  const map = {
    DESIGNER: 'ผู้ออกแบบ',
    OWNER: 'เจ้าของ',
    BUYER: 'ผู้ซื้อ-ผู้รับเหมา',
    UNSPECIFIED: 'ไม่ระบุ',
  };
  return { label: map[value] ?? value ?? '-', tone: 'neutral' };
}

export function quotationStatusLabel(value) {
  const map = {
    DRAFT: { label: 'แบบร่าง', tone: 'neutral' },
    ISSUED: { label: 'ออกแล้ว', tone: 'success' },
    SENT: { label: 'ส่งแล้ว', tone: 'info' },
    ACCEPTED: { label: 'รับแล้ว', tone: 'success' },
    REJECTED: { label: 'ปฏิเสธ', tone: 'danger' },
    EXPIRED: { label: 'หมดอายุ', tone: 'warning' },
    CANCELLED: { label: 'ยกเลิก', tone: 'danger' },
    SUPERSEDED: { label: 'ถูกแทนที่', tone: 'neutral' },
  };
  return map[value] ?? { label: value || '-', tone: 'neutral' };
}

// Payroll run status -> StatusBadge tone. Canonical source; do not
// re-add a page-local `statusInfo`/map for payroll status elsewhere.
export function payrollStatusLabel(status) {
  const map = {
    PREVIEW: { label: 'ตัวอย่าง', tone: 'info' },
    OPEN: { label: 'เปิดรอบ', tone: 'warning' },
    PROCESSED: { label: 'ประมวลผลแล้ว', tone: 'success' },
    CLOSED: { label: 'ปิดรอบ', tone: 'neutral' },
    VOID: { label: 'ยกเลิก', tone: 'danger' },
  };
  return map[status] ?? { label: status || '-', tone: 'neutral' };
}
