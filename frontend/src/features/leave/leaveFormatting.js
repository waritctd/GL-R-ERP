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

// ── Day counts read as วัน / ชั่วโมง / นาที, never as a decimal (2026-08-31, owner ask) ──────────
//
// Every day figure on the leave surface is a NUMERIC(5,2) day count whose fractional part is real,
// not a rounding artefact. Sub-day leave divides clock-minutes by an eight-hour workday
// (LeaveService's `STANDARD_WORKDAY_MINUTES = 8 * 60`, HALF_UP to 2dp, capped at 1.00), and a
// remaining balance is `quota - approved - pending`, so those eighths accumulate into figures like
// 6.37. Rows read "0.38 วัน · เหลือ 6.37 วัน" -- arithmetic nobody does in their head to answer
// "how much have I actually got left". They now read "3 ชั่วโมง · เหลือ 6 วัน 3 ชั่วโมง".
//
// The eight-hour divisor is LeaveService's own, NOT a new rule invented here: this module only
// re-expresses a stored day count in the unit it was computed from. Nothing recomputes a day count,
// and the value sent to and received from the API is unchanged in every direction.
export const LEAVE_HOURS_PER_DAY = 8;
const MINUTES_PER_LEAVE_DAY = LEAVE_HOURS_PER_DAY * 60;

// The reconstructed minutes are snapped to 5 because the stored 2dp day count cannot carry a finer
// signal: +/-0.005 of a day is +/-2.4 minutes, so a minute-exact readout prints quantisation noise
// as though it were data -- a flat three-hour leave stores as 0.38 and reconstructs to 182.4
// minutes, which minute-exact would render as "3 ชั่วโมง 2 นาที".
//
// 5 is derived, not chosen by feel: at that step EVERY five-minute leave a requester can enter
// (all 96 of them, 0:05 through 8:00 -- which subsumes every quarter- and half-hour) survives the
// round trip minutes -> 2dp day -> minutes exactly. leaveFormatting.test.js pins the whole set, so
// a change to either the step or the workday length fails loudly instead of drifting silently.
const DISPLAY_MINUTE_STEP = 5;

const dayCountFormat = new Intl.NumberFormat('th-TH', { maximumFractionDigits: 0 });

// A duration is longer than the decimal it replaces, so where it lands in a narrow table track it
// WILL wrap -- the question is only whether it wraps somewhere that still reads. Each number is
// glued to its own unit with a no-break space, and the units are joined by an ordinary one, so the
// break opportunities are exactly between units: "6 วัน / 3 ชั่วโมง" over two lines is still true,
// while "6 / วัน 3 ชั่วโมง" is not.
//
// This is deliberately NOT `white-space: nowrap` on the whole phrase, which was the first attempt
// and measured worse: the longest string here ("28 วัน 6 ชั่วโมง 15 นาที") is wider than the
// leave table's day column at every viewport, so an unbreakable phrase stopped wrapping and
// started being CLIPPED by the `.data-row` cell's `overflow: clip` instead -- trading a bad wrap
// for silently missing text. Gluing at the token level lets the phrase reflow to as many lines as
// it needs while never splitting a figure from its unit.
const NBSP = '\u00A0';

/**
 * A stored day count as a human duration: "6 วัน 3 ชั่วโมง", "3 ชั่วโมง", "30 นาที", "7 วัน".
 *
 * Zero-valued units are dropped rather than padded ("7 วัน", never "7 วัน 0 ชั่วโมง"), so a whole
 * day count -- which is what every quota ceiling and the large majority of requests are -- renders
 * exactly as it did before this change. Only the fractional cases move.
 *
 * Non-finite input (a `NaN`, an unparseable string) collapses to "0 วัน" rather than reaching the
 * DOM as "NaN วัน", which the previous `Number(value || 0)` did emit for a non-numeric string.
 * `null`/`undefined`/`''` keep their old "0 วัน" reading -- `formatDaysOrDash` below is what
 * distinguishes "not known" from "genuinely zero", and it still does.
 *
 * A negative balance (over-quota) keeps its sign in front of the whole phrase: "-4 ชั่วโมง".
 */
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

// Used by the expanded-row detail for paidDays/unpaidDays/quotaRemainingBefore -- fields the
// mock does not always populate (paidDays/unpaidDays are a real LeaveRequestDto field this mock
// does not compute; see mockApi.js's leave.create() comment on the paid/unpaid quota-split gap).
// `formatDays` alone would render a bare `undefined` as "0 วัน", which is a lie -- "not known in
// this mode" and "genuinely zero" are different facts. This keeps them visually distinct.
export function formatDaysOrDash(value) {
  return value == null ? '-' : formatDays(value);
}
