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

/** Shared "which of the next few days are company holidays" panel -- reads `GET /api/leave/calendar-context`,
 * the ONLY holiday read every employee may call: `/api/holidays` is HR/CEO-only admin CRUD. Do not add
 * anything here that only makes sense for one caller. */
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
        // A two-column GRID, not a flex row, and each name clamped to one line. The full record is one hover away via
        // `title` and is never lost -- and HR keeps the unabridged value in the admin surface
        // (`attendanceCalendar/HolidaysTab.jsx`), which is the role that actually needs it.
        <ul className="m-0 grid list-none gap-1.5 p-0">
          {holidays.map((holiday) => (
            <li
              key={holiday.holidayDate}
              className="grid grid-cols-[7.5rem_1fr] items-baseline gap-x-3 rounded-md border border-border bg-surface px-3 py-2 mobile:grid-cols-1 mobile:items-start mobile:gap-y-0.5"
            >
              <strong className="text-sm tabular-nums text-text">{formatHolidayDate(holiday.holidayDate)}</strong>
              {/* Clamped by LINES, not by `truncate`. Losing 8% is worse than losing 71%, not better, because it looks
               * complete. */}
              <span
                className="overflow-hidden text-xs text-text-muted [display:-webkit-box] [-webkit-box-orient:vertical] [-webkit-line-clamp:2] mobile:[-webkit-line-clamp:3]"
                title={holiday.nameTh}
              >
                {holiday.nameTh}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
