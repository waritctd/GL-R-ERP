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

/**
 * Canonical address formatter; do not re-add a page-local version.
 *
 * `currentAddress` is four separate fields (line1/district/province/postalCode),
 * and rendering only `line1` silently dropped the district, province and postcode.
 * Empty parts are filtered out because a newly created employee gets '' for
 * everything but `line1` (mockApi) — joining blindly would leave stray spaces.
 */
export function formatAddress(address) {
  if (!address) return '-';
  const parts = [address.line1, address.district, address.province, address.postalCode]
    .map((part) => (typeof part === 'string' ? part.trim() : part))
    .filter(Boolean);
  return parts.length > 0 ? parts.join(' ') : '-';
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

// Special-money (welfare) request status -> StatusBadge tone. Same status
// shape as overtime (SUBMITTED -> MANAGER_APPROVED -> APPROVED, or
// REJECTED/CANCELLED at either step) — see SpecialMoneyStatus.java. Canonical
// source; do not re-add a page-local `statusInfo`/map elsewhere.
export function specialMoneyStatusLabel(status) {
  const map = {
    SUBMITTED: { label: 'รอผู้จัดการ', tone: 'warning' },
    MANAGER_APPROVED: { label: 'รอ CEO', tone: 'info' },
    APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
    REJECTED: { label: 'ปฏิเสธ', tone: 'danger' },
    CANCELLED: { label: 'ยกเลิก', tone: 'neutral' },
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

export function paymentStageLabel(value) {
  const map = {
    NOT_REQUIRED: { label: 'ไม่ต้องชำระ', tone: 'neutral' },
    DEPOSIT_PENDING: { label: 'รอมัดจำ', tone: 'warning' },
    DEPOSIT_RECEIVED: { label: 'รับมัดจำแล้ว', tone: 'success' },
    PARTIALLY_PAID: { label: 'ชำระบางส่วน', tone: 'warning' },
    BALANCE_PENDING: { label: 'รอชำระส่วนที่เหลือ', tone: 'warning' },
    FULLY_PAID: { label: 'ชำระครบแล้ว', tone: 'success' },
  };
  return map[value] ?? { label: value || '-', tone: 'neutral' };
}

export function overdueBadgeLabel(overdue) {
  return overdue
    ? { label: 'เกินกำหนด', tone: 'danger' }
    : { label: 'ยังไม่เกินกำหนด', tone: 'neutral' };
}

export function fulfilmentStatusLabel(value) {
  const map = {
    IR_ISSUED: { label: 'ออก IR แล้ว', tone: 'info' },
    IR_SENT: { label: 'สั่งซื้อผู้ผลิตแล้ว', tone: 'info' },
    PICKED_UP: { label: 'รับจากผู้ผลิตแล้ว', tone: 'info' },
    SHIPPING: { label: 'สินค้าเดินทาง', tone: 'info' },
    CUSTOMS_CLEARANCE: { label: 'รอออกของ', tone: 'warning' },
    GOODS_RECEIVED: { label: 'สินค้าถึงโกดังแล้ว', tone: 'success' },
    FROM_STOCK: { label: 'สินค้าจากสต็อก', tone: 'success' },
    PARTIALLY_DELIVERED: { label: 'ส่งมอบบางส่วน', tone: 'warning' },
    FULLY_DELIVERED: { label: 'ส่งมอบครบแล้ว', tone: 'success' },
  };
  return map[value] ?? { label: value || '-', tone: 'neutral' };
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

// Bangkok is the business zone for every attendance/leave/overtime date. Deriving "today" from
// the browser's zone instead makes a late-evening session disagree with the server about which
// day it is — which matters most on the attendance page, whose primary control is a date stepper.
export function bangkokDateParts(date = new Date()) {
  return Object.fromEntries(
    new Intl.DateTimeFormat('en-US', {
      timeZone: 'Asia/Bangkok',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    })
      .formatToParts(date)
      .map((part) => [part.type, part.value]),
  );
}

export function bangkokTodayIso(date = new Date()) {
  const parts = bangkokDateParts(date);
  return `${parts.year}-${parts.month}-${parts.day}`;
}

export function bangkokMonthStartIso(date = new Date()) {
  const parts = bangkokDateParts(date);
  return `${parts.year}-${parts.month}-01`;
}

// Clock time only, pinned to Bangkok. Used by the attendance day table, where the date already
// labels the row and repeating it in every cell is noise.
export function formatBangkokTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Bangkok',
  }).format(date);
}

// Minutes -> "8:45". Hours are the unit people reason about for a working day; raw minutes
// ("525") force arithmetic at every glance.
export function formatDuration(minutes) {
  if (minutes === null || minutes === undefined || Number.isNaN(minutes)) return '-';
  const safe = Math.max(0, Math.round(minutes));
  return `${Math.floor(safe / 60)}:${String(safe % 60).padStart(2, '0')}`;
}

// Attendance day status -> StatusBadge tone. Canonical source; do not re-add a page-local map.
//
// NOTE: late/early-leave are REPORTING ONLY. Thai Labour Protection Act §76 forbids deducting
// wages as a penalty for lateness or absence, so these badges must never gain a "deduction"
// affordance. Tones are informational (warning), never punitive (danger).
export function attendanceStatusLabel(status) {
  const map = {
    PRESENT: { label: 'ปกติ', tone: 'success' },
    LATE: { label: 'มาสาย', tone: 'warning' },
    MISSING_CHECK_IN: { label: 'ขาดสแกนเข้า', tone: 'warning' },
    MISSING_CHECK_OUT: { label: 'ขาดสแกนออก', tone: 'warning' },
    NON_WORKDAY: { label: 'วันหยุด', tone: 'neutral' },
    NO_RECORD: { label: '-', tone: 'neutral' },
  };
  return map[status] ?? { label: status || '-', tone: 'neutral' };
}

// Per-day flags -> badges. A day can carry several (late in AND early out), so this returns a
// list. Minutes are baked into the label because "มาสาย" alone prompts "by how much?".
export function attendanceFlagLabels(day) {
  if (!day) return [];
  const labels = [];
  if (day.late_minutes > 0) {
    labels.push({ key: 'LATE', label: `สาย ${day.late_minutes} นาที`, tone: 'warning' });
  }
  if (day.early_leave_minutes > 0) {
    labels.push({
      key: 'EARLY_LEAVE',
      label: `ออกก่อน ${day.early_leave_minutes} นาที`,
      tone: 'warning',
    });
  }
  const flags = day.flags ?? [];
  if (flags.includes('MISSING_CHECK_IN')) {
    labels.push({ key: 'MISSING_CHECK_IN', label: 'ขาดสแกนเข้า', tone: 'warning' });
  }
  if (flags.includes('MISSING_CHECK_OUT')) {
    labels.push({ key: 'MISSING_CHECK_OUT', label: 'ขาดสแกนออก', tone: 'warning' });
  }
  if (day.overtime_minutes > 0) {
    labels.push({
      key: 'OVERTIME_APPROVED',
      label: `โอที ${formatDuration(day.overtime_minutes)}`,
      tone: 'info',
    });
  } else if (flags.includes('WORKED_LATE_UNAPPROVED')) {
    // Deliberately NOT called overtime: overtime pay requires approval, and promising it here
    // would set an expectation payroll will not meet.
    labels.push({ key: 'WORKED_LATE_UNAPPROVED', label: 'ออกช้า', tone: 'neutral' });
  }
  if (flags.includes('NON_WORKDAY')) {
    labels.push({ key: 'NON_WORKDAY', label: 'วันหยุด', tone: 'neutral' });
  }
  return labels;
}
