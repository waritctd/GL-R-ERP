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
    admin: 'ADMIN',
    director: 'DIRECTOR',
    hr: 'HR',
    supervisor: 'SUPERVISOR',
    employee: 'EMPLOYEE',
  };
  return labels[role] ?? role?.toUpperCase() ?? '-';
}

export function requestStatus(status) {
  const map = {
    pending: { label: 'รออนุมัติ', tone: 'warning' },
    approved: { label: 'อนุมัติแล้ว', tone: 'success' },
    rejected: { label: 'ปฏิเสธแล้ว', tone: 'danger' },
  };
  return map[status] ?? { label: status, tone: 'neutral' };
}
