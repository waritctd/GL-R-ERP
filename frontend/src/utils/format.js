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
