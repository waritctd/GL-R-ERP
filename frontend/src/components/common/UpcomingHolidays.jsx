import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { EmptyState } from './EmptyState.jsx';
import { Panel } from './Layout.jsx';

function formatHolidayDate(value) {
  if (!value) return '-';
  const date = new Date(`${value}T00:00:00+07:00`);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    dateStyle: 'medium',
    timeZone: 'Asia/Bangkok',
  }).format(date);
}

/**
 * Shared "which of the next few days are company holidays" panel -- reads
 * `GET /api/leave/calendar-context` (`LeaveController#calendarContext` / `api.leave.calendarContext`),
 * the ONLY holiday read every employee (not just HR/CEO) may call: `/api/holidays`
 * (`HolidayController`) is HR/CEO-only admin CRUD. `holidays` here is therefore
 * `{ holidayDate, nameTh }` only -- no `source` (BANK/COMPANY is an admin-CRUD distinction,
 * matching `LeaveCalendarHolidayDto`'s own narrower shape, not `HolidayDto`'s).
 *
 * Feature-agnostic by design: `OvertimePanel.jsx` (#ot-holiday-visibility, PR 2) is the first
 * caller; the leave-request composer's own follow-up PR reuses this instead of growing a second
 * copy of the same read. Do not add anything here that only makes sense for one caller.
 *
 * Renders a genuinely distinct state for every outcome -- loading, a real network/HTTP error, and
 * a range that truly has zero holidays all resolve to their OWN visible `EmptyState`, never a
 * blank panel or a spinner nothing ever replaces. That distinction is the entire point of the PR
 * this panel ships in: an employee who sees nothing here cannot tell "the calendar has not loaded
 * yet" from "there really are no holidays coming up" -- the exact ambiguity
 * `OvertimeService#validateDayTypeClaim` (backend, PR 1) exists to stop an OT claim from silently
 * riding on. See that method's decision table for the "loaded-but-empty" vs "never loaded"
 * distinction this panel's copy deliberately echoes.
 */
export function UpcomingHolidays({ from, to, title = 'วันหยุดที่จะถึง', limit = 8 }) {
  const enabled = Boolean(from && to);
  const query = useQuery({
    queryKey: queryKeys.leaveCalendarContext(from, to),
    queryFn: () => api.leave.calendarContext({ from, to }).then((response) => response.calendarContext),
    enabled,
  });

  const holidays = [...(query.data?.holidays ?? [])]
    .sort((a, b) => (a.holidayDate < b.holidayDate ? -1 : a.holidayDate > b.holidayDate ? 1 : 0))
    .slice(0, limit);

  return (
    <Panel title={title}>
      {!enabled || query.isLoading ? (
        <EmptyState icon="calendar" title="กำลังโหลดข้อมูลวันหยุด..." />
      ) : query.isError ? (
        <EmptyState
          icon="triangleAlert"
          title="โหลดข้อมูลวันหยุดไม่สำเร็จ"
          description={query.error?.message || 'ลองรีเฟรชหน้านี้อีกครั้ง'}
        />
      ) : holidays.length === 0 ? (
        <EmptyState icon="calendar" title="ยังไม่มีข้อมูลวันหยุด" description="ไม่มีวันหยุดบริษัทในช่วงที่แสดงนี้" />
      ) : (
        // A two-column GRID, not a flex row, and each name clamped to one line. Both are about the
        // same fact: `hr.holiday.name_th` carries the OFFICIAL Bank of Thailand record, not a label.
        // Production names average 35 characters and reach 149 -- e.g. 12 Aug is
        // "วันเฉลิมพระชนมพรรษาสมเด็จพระนางเจ้าสิริกิติ์ พระบรมราชินีนาถ พระบรมราชชนนีพันปีหลวง และวันแม่แห่งชาติ".
        //
        // Under the previous `flex flex-wrap`, measured at 360px: a 101-char name wrapped onto its
        // own line starting at x=50 while short names stayed inline at x=132, and row heights ran
        // 44px / 71px / 127px. The column read as ragged and the panel dwarfed the form it was
        // meant to support. A fixed date column plus `truncate` makes every row exactly one line
        // and every name start at the same x, whatever the calendar happens to contain.
        //
        // The full record is one hover away via `title` and is never lost -- and HR keeps the
        // unabridged value in the admin surface (`attendanceCalendar/HolidaysTab.jsx`), which is
        // the role that actually needs it. An employee picking an OT date needs to know THAT the
        // day is a holiday, not to read a royal title.
        //
        // ── ...BUT THE `title` FALLBACK DOES NOT EXIST ON A PHONE (2026-08-10) ──────────────
        // The paragraph above is still right at tablet and desktop, where the name column is wide
        // and a hover recovers the rest. It does not hold at ≤720px, and that is where this panel
        // is read most. Measured at 390px: the name column is 158px against a 551px name, so 71%
        // of it is hidden -- the visible stub is "วันเฉลิมพระชนมพรรษาสมเด็จ...", which is the
        // GENERIC opening of every royal-birthday holiday. Two different holidays render as the
        // same string, so the row stops answering "which one?" -- and `title` is a hover
        // affordance, so on touch there is no way to recover it at all. The one-line rule was
        // trading legibility for tidiness against a fallback that only desktop users have.
        //
        // At ≤720px the row therefore stacks: date on its own line, name beneath it across the
        // full card width. Every name still starts at the same x (that was the real complaint
        // about the old `flex flex-wrap` -- names starting at x=50 or x=132 depending on length),
        // and `line-clamp-3` keeps even the 149-char worst case from running away, while showing
        // roughly six times more of it than `truncate` did. Above 720px nothing changes.
        <ul className="m-0 grid list-none gap-1.5 p-0">
          {holidays.map((holiday) => (
            <li
              key={holiday.holidayDate}
              className="grid grid-cols-[7.5rem_1fr] items-baseline gap-x-3 rounded-md border border-border bg-surface px-3 py-2 mobile:grid-cols-1 mobile:items-start mobile:gap-y-0.5"
            >
              <strong className="text-sm tabular-nums text-text">{formatHolidayDate(holiday.holidayDate)}</strong>
              <span className="truncate text-xs text-text-muted mobile:overflow-visible mobile:whitespace-normal mobile:[display:-webkit-box] mobile:[-webkit-box-orient:vertical] mobile:[-webkit-line-clamp:3]" title={holiday.nameTh}>
                {holiday.nameTh}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
