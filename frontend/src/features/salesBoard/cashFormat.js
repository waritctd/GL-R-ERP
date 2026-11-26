// Display helpers for expected payments and the เงินที่จะเข้า card (spec 2026-11-15 §7.3 item 3, §7.7). Labels and
// pre-fills only — every total is served by SalesBoardService#cashForMonth. Weeks are ISO weeks keyed by their
// Monday, on the Asia/Bangkok calendar date (ExpectedPaymentService refuses any other day).
import { addDaysIso } from '../../utils/format.js';
import { weekStartIso } from './visitWeek.js';

export const EXPECTED_KIND_LABEL = { DEPOSIT: 'มัดจำ', ON_DELIVERY: 'ส่งของ', BALANCE: 'ส่วนที่เหลือ' };
export const EXPECTED_KINDS = ['DEPOSIT', 'ON_DELIVERY', 'BALANCE'];
export const EXPECTED_STATUS_LABEL = { OPEN: 'รอ', RECEIVED: 'รับแล้ว', CANCELLED: 'ยกเลิก' };

const SHORT_MONTH = ['ม.ค.', 'ก.พ.', 'มี.ค.', 'เม.ย.', 'พ.ค.', 'มิ.ย.', 'ก.ค.', 'ส.ค.', 'ก.ย.', 'ต.ค.', 'พ.ย.', 'ธ.ค.'];
const ISO = /^(\d{4})-(\d{2})-(\d{2})$/;

const parts = (iso) => {
  const m = ISO.exec(iso ?? '');
  return m ? { year: Number(m[1]), month: Number(m[2]), day: Number(m[3]) } : null;
};

/** "16–20 พ.ย." (Mon–Fri of the week that starts on `mondayIso`); "30 พ.ย.–4 ธ.ค." across a month. */
export function weekRangeLabel(mondayIso) {
  const start = parts(mondayIso);
  if (!start) return '—';
  const end = parts(addDaysIso(mondayIso, 4));
  if (start.month === end.month) return `${start.day}–${end.day} ${SHORT_MONTH[end.month - 1]}`;
  return `${start.day} ${SHORT_MONTH[start.month - 1]}–${end.day} ${SHORT_MONTH[end.month - 1]}`;
}

/**
 * The weekly bar's label, "ถึง 13 พ.ย.": the week's Friday. A month's weeks are the ISO weeks whose Thursday is in it
 * (SalesBoardService#assemble), so the week of Mon 30 Nov is December's and reads "ถึง 4 ธ.ค.". The month's LAST week
 * (`isLast`) also holds the receipts of the days after its Friday, so it is labelled to the month's last day when that
 * is later.
 */
export function weekEndLabel(mondayIso, yearMonth, isLast = false) {
  const day = weekEndDay(mondayIso, yearMonth, isLast);
  return day === '—' ? day : `ถึง ${day}`;
}

/** weekEndLabel's date alone, "13 พ.ย." — the phone's "รอบถัดไป 13 พ.ย.". */
export function weekEndDay(mondayIso, yearMonth, isLast = false) {
  const friday = addDaysIso(mondayIso, 4);
  const [y, m] = (yearMonth ?? '').split('-').map(Number);
  let end = friday;
  if (isLast && y && m) {
    const monthEnd = `${yearMonth}-${String(new Date(Date.UTC(y, m, 0)).getUTCDate()).padStart(2, '0')}`;
    if (monthEnd > friday) end = monthEnd;
  }
  const p = parts(end);
  return p ? `${p.day} ${SHORT_MONTH[p.month - 1]}` : '—';
}

/** The Monday of the ISO week containing `iso`. */
export const snapToMonday = (iso) => weekStartIso(iso);

/** `count` Mondays starting with this week's. */
export function nextMondays(todayIso, count) {
  const monday = snapToMonday(todayIso);
  return Array.from({ length: count }, (_, i) => addDaysIso(monday, i * 7));
}

/**
 * The m-expected amount pre-fills: `preset` is a percent of `dealValue`, or `'rest'` = dealValue minus what is already
 * planned (never below 0). Rounded to satang. Null when the deal has no value yet.
 */
export function presetAmount({ preset, dealValue, planned = 0 }) {
  const value = Number(dealValue);
  if (dealValue == null || !Number.isFinite(value) || value <= 0) return null;
  // In whole satang, so 30% of ฿1,000.05 is ฿300.02 and not a float artefact.
  const valueSatang = Math.round(value * 100);
  const satang = preset === 'rest'
    ? Math.max(0, valueSatang - Math.round(Number(planned || 0) * 100))
    : Math.round((valueSatang * Number(preset)) / 100);
  return satang / 100;
}

/** "2026-11" → "พ.ย."; '' when it is not a YYYY-MM month. */
export function monthLabel(yearMonth) {
  const m = /^(\d{4})-(\d{2})$/.exec(yearMonth ?? '');
  return m ? SHORT_MONTH[Number(m[2]) - 1] ?? '' : '';
}


/** The deal's เงิน tab with the เงินที่คาดว่าจะเข้า add form already open (ExpectedPaymentsPanel reads `add`). */
export const ADD_EXPECTED_PARAM = 'expected';
export const addExpectedPaymentPath = (ticketId) => `/tickets/${ticketId}?tab=money&add=${ADD_EXPECTED_PARAM}`;
