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

/** "9–15 พ.ย." for the week starting `mondayIso`; "30 พ.ย.–6 ธ.ค." across a month end. */
export function weekRangeLabel(mondayIso) {
  const sunday = addDaysIso(mondayIso, 6);
  const [startDay, startMonth] = [Number(mondayIso.slice(8, 10)), Number(mondayIso.slice(5, 7))];
  const [endDay, endMonth] = [Number(sunday.slice(8, 10)), Number(sunday.slice(5, 7))];
  return startMonth === endMonth
    ? `${startDay}–${endDay} ${SHORT_MONTH[endMonth - 1]}`
    : `${startDay} ${SHORT_MONTH[startMonth - 1]}–${endDay} ${SHORT_MONTH[endMonth - 1]}`;
}

/** A PLANNED visit whose day has passed without a tick (review M4). */
export function isOverdue(visit, todayIso) {
  return visit.status === 'PLANNED' && visit.plannedDate < todayIso;
}

/** DONE / PLANNED / CANCELLED, or OVERDUE for a PLANNED visit dated before today. */
export function displayStatus(visit, todayIso) {
  return isOverdue(visit, todayIso) ? 'OVERDUE' : visit.status;
}

export const VISIT_STATUS_LABEL = { DONE: 'ไปแล้ว', PLANNED: 'นัดไว้', CANCELLED: 'ยกเลิก', OVERDUE: 'เลยกำหนด' };
