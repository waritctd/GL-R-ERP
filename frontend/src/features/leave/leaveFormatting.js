// Leave-surface IA rebuild, Phase A1: date/day-count formatting shared by MyLeaveTab.jsx
// (calendar list, own-request table) and ReviewQueueTab.jsx (review table) -- lifted
// verbatim out of the pre-A1 LeavePage.jsx (same function bodies) so both tabs render
// dates/day-counts identically instead of each growing its own slightly-different copy.

export function bangkokDateParts(date = new Date()) {
  return Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Bangkok',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date).map((part) => [part.type, part.value]));
}

export const todayIso = () => {
  const parts = bangkokDateParts();
  return `${parts.year}-${parts.month}-${parts.day}`;
};

export const monthStartIso = () => {
  const parts = bangkokDateParts();
  return `${parts.year}-${parts.month}-01`;
};

export function yearFrom(dateString) {
  return Number((dateString || todayIso()).slice(0, 4));
}

export function formatDate(value) {
  if (!value) return '-';
  const date = new Date(`${value}T00:00:00+07:00`);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    dateStyle: 'medium',
    timeZone: 'Asia/Bangkok',
  }).format(date);
}

export function formatDateTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    dateStyle: 'medium',
    timeStyle: 'short',
    hour12: false,
    timeZone: 'Asia/Bangkok',
  }).format(date);
}

export function formatDateRange(start, end) {
  return start === end ? formatDate(start) : `${formatDate(start)} - ${formatDate(end)}`;
}

export function formatDays(value) {
  const days = Number(value || 0);
  return `${new Intl.NumberFormat('th-TH', { maximumFractionDigits: 2 }).format(days)} วัน`;
}

// Used by the expanded-row detail for paidDays/unpaidDays/quotaRemainingBefore -- fields the
// mock does not always populate (paidDays/unpaidDays are a real LeaveRequestDto field this mock
// does not compute; see mockApi.js's leave.create() comment on the paid/unpaid quota-split gap).
// `formatDays` alone would render a bare `undefined` as "0 วัน", which is a lie -- "not known in
// this mode" and "genuinely zero" are different facts. This keeps them visually distinct.
export function formatDaysOrDash(value) {
  return value == null ? '-' : formatDays(value);
}
