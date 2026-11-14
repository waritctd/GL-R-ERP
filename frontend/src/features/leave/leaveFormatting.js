// Leave-surface IA rebuild, Phase A1: date/day-count formatting shared by MyLeaveTab.jsx and
// ReviewQueueTab.jsx -- lifted verbatim out of the pre-A1 LeavePage.jsx so both tabs render dates/day-counts
// identically instead of each growing its own slightly-different copy.
import { bangkokTodayIso } from '../../utils/format.js';

export const todayIso = () => bangkokTodayIso();

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

// Day counts read as วัน / ชั่วโมง / นาที, never as a decimal (2026-08-31, owner ask) ────────── Every day
// figure on the leave surface is a NUMERIC(5,2) day count whose fractional part is real, not a rounding
// artefact.
export const LEAVE_HOURS_PER_DAY = 8;
const MINUTES_PER_LEAVE_DAY = LEAVE_HOURS_PER_DAY * 60;

// The reconstructed minutes are snapped to 5 because the stored 2dp day count cannot carry a finer signal:
// +/-0.005 of a day is +/-2.4 minutes, so a minute-exact readout prints quantisation noise as though it were
// data.
const DISPLAY_MINUTE_STEP = 5;

const dayCountFormat = new Intl.NumberFormat('th-TH', { maximumFractionDigits: 0 });

// A duration is longer than the decimal it replaces, so where it lands in a narrow table track it WILL wrap --
// the question is only whether it wraps somewhere that still reads. Gluing at the token level lets the phrase
// reflow to as many lines as it needs while never splitting a figure from its unit.
const NBSP = '\u00A0';

/** A stored day count as a human duration: "6 วัน 3 ชั่วโมง", "3 ชั่วโมง", "30 นาที", "7 วัน". Only the
 * fractional cases move. `null`/`undefined`/`''` keep their old "0 วัน" reading -- `formatDaysOrDash` below is
 * what distinguishes "not known" from "genuinely zero", and it still does. */
export function formatDays(value) {
  const days = Number(value);
  const totalMinutes = Number.isFinite(days)
    ? Math.round((Math.abs(days) * MINUTES_PER_LEAVE_DAY) / DISPLAY_MINUTE_STEP) * DISPLAY_MINUTE_STEP
    : 0;
  if (totalMinutes === 0) return `${dayCountFormat.format(0)}${NBSP}วัน`;

  const wholeDays = Math.floor(totalMinutes / MINUTES_PER_LEAVE_DAY);
  const restMinutes = totalMinutes % MINUTES_PER_LEAVE_DAY;
  const parts = [];
  if (wholeDays > 0) parts.push(`${dayCountFormat.format(wholeDays)}${NBSP}วัน`);
  if (restMinutes >= 60) parts.push(`${Math.floor(restMinutes / 60)}${NBSP}ชั่วโมง`);
  if (restMinutes % 60 > 0) parts.push(`${restMinutes % 60}${NBSP}นาที`);
  return `${days < 0 ? '-' : ''}${parts.join(' ')}`;
}

// Used by the expanded-row detail for paidDays/unpaidDays/quotaRemainingBefore -- fields the mock does not
// always populate (paidDays/unpaidDays are a real LeaveRequestDto field this mock does not compute; see
// mockApi.js's leave.create() comment on the paid/unpaid quota-split gap).
export function formatDaysOrDash(value) {
  return value == null ? '-' : formatDays(value);
}
