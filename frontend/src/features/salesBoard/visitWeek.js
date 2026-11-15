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

/** Mon–Sun of the week containing `todayIso`: [{ iso, short: 'จ.', dayOfMonth: 10, isToday }]. */
export function weekDays(todayIso) {
  const monday = weekStartIso(todayIso);
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

export const VISIT_STATUS_LABEL = { DONE: 'ไปแล้ว', PLANNED: 'นัดไว้', CANCELLED: 'ยกเลิก' };
