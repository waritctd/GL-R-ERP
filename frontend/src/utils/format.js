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
    import: 'IMPORT',
    ceo: 'CEO',
  };
  return labels[role] ?? role?.toUpperCase() ?? '-';
}

export function ticketStatusLabel(status) {
  const map = {
    draft:            { label: 'แบบร่าง',          tone: 'neutral' },
    submitted:        { label: 'รอรับเรื่องจากฝ่าย Import', tone: 'warning' },
    in_review:        { label: 'กำลังดำเนินการ',    tone: 'info' },
    price_proposed:   { label: 'รอการอนุมัติ',      tone: 'warning' },
    approved:         { label: 'อนุมัติแล้ว',        tone: 'success' },
    rejected:         { label: 'ตีกลับ',             tone: 'danger' },
    document_issued:  { label: 'ออกใบแจ้งยอดแล้ว',  tone: 'success' },
    closed:           { label: 'ปิดแล้ว',            tone: 'neutral' },
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
