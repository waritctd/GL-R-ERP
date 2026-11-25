// Visit week helpers (spec 2026-11-15 §7.6), shared by the screens and mockApi so the mock's "this week" cannot drift
// from the UI's. Weeks are ISO weeks starting Monday on the Asia/Bangkok date (VisitService.weekStart).
import { addDaysIso } from '../../utils/format.js';

const SHORT_DAY = ['จ.', 'อ.', 'พ.', 'พฤ.', 'ศ.', 'ส.', 'อา.'];

/** 0 = Monday … 6 = Sunday, for an ISO 'YYYY-MM-DD' calendar date. */
export function isoWeekdayIndex(iso) {
  return (new Date(`${iso}T00:00:00Z`).getUTCDay() + 6) % 7;
}

/** The Monday of the ISO week containing `iso`. */
export function weekStartIso(iso) {
  return addDaysIso(iso, -isoWeekdayIndex(iso));
}

/** Mon–Sun of the week containing `dayIso`: [{ iso, short: 'จ.', dayOfMonth: 10, isToday }]. `todayIso` defaults
 * to `dayIso`; pass it when showing another week so no column is marked today. */
export function weekDays(dayIso, todayIso = dayIso) {
  const monday = weekStartIso(dayIso);
  return SHORT_DAY.map((short, i) => {
    const iso = addDaysIso(monday, i);
    return { iso, short, dayOfMonth: Number(iso.slice(8, 10)), isToday: iso === todayIso };
  });
}

/** The short Thai weekday of an ISO date: 'พฤ.'. */
export function shortWeekday(iso) {
  return SHORT_DAY[isoWeekdayIndex(iso)];
}

/**
 * `done` = DONE visits; `planned` = every visit booked that was not cancelled (done ones included), so
 * `${done} จาก ${planned}` reads as "3 of 5 visits made". Same meaning as RepRow.visitsDone / visitsPlanned.
 */
export function visitCounts(visits) {
  const live = (visits ?? []).filter((v) => v.status !== 'CANCELLED');
  return { done: live.filter((v) => v.status === 'DONE').length, planned: live.length };
}

/** "10:00" from the server's LocalTime ("10:00" or "10:00:00"); '' when unset. */
export function timeLabel(plannedTime) {
  return plannedTime ? String(plannedTime).slice(0, 5) : '';
}

const SHORT_MONTH = ['ม.ค.', 'ก.พ.', 'มี.ค.', 'เม.ย.', 'พ.ค.', 'มิ.ย.', 'ก.ค.', 'ส.ค.', 'ก.ย.', 'ต.ค.', 'พ.ย.', 'ธ.ค.'];

/** "ศ. 20 พ.ย." for one ISO date; with `todayIso`, a date outside today's year gets its พ.ศ. year: "อ. 5 ม.ค. 2570". */
export function shortDayLabel(iso, todayIso = null) {
  const label = `${shortWeekday(iso)} ${Number(iso.slice(8, 10))} ${SHORT_MONTH[Number(iso.slice(5, 7)) - 1]}`;
  return todayIso && iso.slice(0, 4) !== todayIso.slice(0, 4) ? `${label} ${Number(iso.slice(0, 4)) + 543}` : label;
}

/** "9–15 พ.ย." for the week starting `mondayIso`; "30 พ.ย.–6 ธ.ค." across a month end. With `todayIso`, a week
 * that is not wholly in today's year gets the พ.ศ. year: "4–10 ม.ค. 2570". */
export function weekRangeLabel(mondayIso, todayIso = null) {
  const sunday = addDaysIso(mondayIso, 6);
  const [startDay, startMonth] = [Number(mondayIso.slice(8, 10)), Number(mondayIso.slice(5, 7))];
  const [endDay, endMonth] = [Number(sunday.slice(8, 10)), Number(sunday.slice(5, 7))];
  const range = startMonth === endMonth
    ? `${startDay}–${endDay} ${SHORT_MONTH[endMonth - 1]}`
    : `${startDay} ${SHORT_MONTH[startMonth - 1]}–${endDay} ${SHORT_MONTH[endMonth - 1]}`;
  const thisYear = todayIso?.slice(0, 4);
  const otherYear = thisYear && (mondayIso.slice(0, 4) !== thisYear || sunday.slice(0, 4) !== thisYear);
  return otherYear ? `${range} ${Number(sunday.slice(0, 4)) + 543}` : range;
}

/**
 * ค้างจากสัปดาห์ก่อน looks back 31 days before this Monday — the ranged endpoint's cap (VisitService.MAX_RANGE_DAYS).
 * An older unticked visit is still reachable with ‹ สัปดาห์ก่อน. `{ from, to }` for GET /api/sales/visits.
 */
export const CARRY_OVER_DAYS = 31;
export function carryOverRange(thisMondayIso) {
  return { from: addDaysIso(thisMondayIso, -CARRY_OVER_DAYS), to: addDaysIso(thisMondayIso, -1) };
}

/** How far the phone's นัดถัดไป looks ahead: today to today + 7 (8 days inclusive, inside the 31-day cap). */
export const NEXT_VISIT_DAYS = 7;

/** A PLANNED visit whose day has passed without a tick (review M4). */
export function isOverdue(visit, todayIso) {
  return visit.status === 'PLANNED' && visit.plannedDate < todayIso;
}

/** "HH:mm" on the Bangkok clock. */
export function bangkokNowHm(date = new Date()) {
  return new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Bangkok', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(date);
}

/** A PLANNED visit that needs a result now: an earlier day, or today at a set time that has passed. Mirrors WeeklyReportService. */
export function visitIsDue(visit, todayIso, nowHm = bangkokNowHm()) {
  if (visit.status !== 'PLANNED') return false;
  if (visit.plannedDate < todayIso) return true;
  return visit.plannedDate === todayIso && Boolean(visit.plannedTime) && timeLabel(visit.plannedTime) <= nowHm;
}

/** DONE / PLANNED / CANCELLED, or OVERDUE for a PLANNED visit dated before today. */
export function displayStatus(visit, todayIso) {
  return isOverdue(visit, todayIso) ? 'OVERDUE' : visit.status;
}

const LONG_DAY = ['จันทร์', 'อังคาร', 'พุธ', 'พฤหัสบดี', 'ศุกร์', 'เสาร์', 'อาทิตย์'];

/** "พฤหัสบดี 12 พ.ย." — the phone's วันนี้ของฉัน date line. */
export function dayLineLabel(iso) {
  return `${LONG_DAY[isoWeekdayIndex(iso)]} ${Number(iso.slice(8, 10))} ${SHORT_MONTH[Number(iso.slice(5, 7)) - 1]}`;
}

/** VisitRepository's order: date, time (unset last), id. */
const byPlan = (a, b) => a.plannedDate.localeCompare(b.plannedDate)
  || (a.plannedTime == null) - (b.plannedTime == null)
  || String(a.plannedTime ?? '').localeCompare(String(b.plannedTime ?? ''))
  || a.id - b.id;

/**
 * The phone's นัดถัดไป: the first visit still PLANNED for today (a timed one before an untimed one — a visit whose
 * time has passed but is not ticked yet is still the one to log); when today has none left, the first PLANNED visit
 * later in the given visits, up to `lastIso` inclusive when given. Overdue ones are never "next". Same date and time:
 * the lower id first (VisitRepository's tie-break). `{ visit, isToday }`, or null.
 */
export function nextVisit(visits, todayIso, lastIso = null) {
  const ahead = (visits ?? []).filter((v) => v.status === 'PLANNED' && v.plannedDate >= todayIso
    && (!lastIso || v.plannedDate <= lastIso)).sort(byPlan);
  return ahead.length ? { visit: ahead[0], isToday: ahead[0].plannedDate === todayIso } : null;
}

export const VISIT_STATUS_LABEL ={ DONE: 'ไปแล้ว', PLANNED: 'นัดไว้', CANCELLED: 'ยกเลิก', OVERDUE: 'เลยกำหนด' };
