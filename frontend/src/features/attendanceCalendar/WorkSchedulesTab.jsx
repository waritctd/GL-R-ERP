import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { DataTable } from '../../components/common/DataTable.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';

const GRID = 'grid-cols-[minmax(0,0.8fr)_minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,0.7fr)_minmax(0,1.6fr)_minmax(0,1.1fr)] nav-drawer:min-w-[860px] reflow-cards';

// ISO day-of-week (1=Mon..7=Sun) -> Thai short label, matching hr.work_schedule_day.day_of_week
// directly (WorkScheduleSummaryDto's own javadoc: "no translation needed either side").
const WEEKDAY_LABELS = ['', 'จ', 'อ', 'พ', 'พฤ', 'ศ', 'ส', 'อา'];

function formatWorkdays(workdays) {
  if (!Array.isArray(workdays) || workdays.length === 0) return '-';
  return [...workdays].sort((a, b) => a - b).map((day) => WEEKDAY_LABELS[day] ?? day).join(' ');
}

function formatTime(value) {
  if (!value) return '-';
  // LocalTime serializes as "HH:mm:ss" or "HH:mm" — either way the first 5 chars are "HH:mm".
  return String(value).slice(0, 5);
}

const COLUMNS = [
  { key: 'code', header: 'รหัส', sortable: true, render: (row) => <span className="font-mono font-bold">{row.code}</span> },
  { key: 'nameTh', header: 'ชื่อตารางเวลาทำงาน', sortable: true, render: (row) => row.nameTh },
  {
    key: 'hours',
    header: 'เวลาเข้า–ออก',
    render: (row) => <span className="font-mono">{formatTime(row.workStart)}–{formatTime(row.workEnd)}</span>,
  },
  { key: 'grace', header: 'ผ่อนผัน (นาที)', align: 'right', render: (row) => row.graceMinutes },
  { key: 'workdays', header: 'วันทำงาน', render: (row) => formatWorkdays(row.workdays) },
  {
    key: 'requiresCheckOut',
    header: 'ต้องสแกนออก',
    render: (row) => (row.requiresCheckOut ? (
      <span>ต้อง</span>
    ) : (
      // §4 of the governing work-hours announcement lets ฝ่ายขาย scan in only — SALES_5D is the
      // one schedule seeded with requiresCheckOut=false, which is why sales staff never get
      // flagged MISSING_CHECK_OUT for a lone check-in. Worth stating here, not just in the Java
      // javadoc, since this is exactly the kind of "why does this schedule behave differently"
      // question an HR admin would otherwise have no way to answer from this screen alone.
      <span className="text-text-muted" title="เฉพาะตารางนี้: อนุญาตให้ทาบบัตรเข้างานอย่างเดียวได้ (ตามประกาศเวลาทำงานฯ ข้อ 4) — จะไม่ถูกตั้งสถานะ MISSING_CHECK_OUT">
        ไม่ต้อง (ฝ่ายขาย)
      </span>
    )),
  },
];

/**
 * Read-only catalogue of hr.work_schedule — see WorkScheduleController's own javadoc: "creating new
 * schedules is out of scope for this branch; assigning the existing seeded ones ... is the need."
 * This tab exists so the การมอบหมาย tab's schedule picker is legible, and so the requiresCheckOut
 * exception (SALES_5D) is visible somewhere instead of only living in a Java comment.
 */
export function WorkSchedulesTab() {
  const schedulesQuery = useQuery({
    queryKey: queryKeys.workSchedules(),
    queryFn: () => api.workSchedules.list().then((response) => response.schedules || []),
  });

  return (
    <div className="grid gap-3">
      <StatePanel
        state="denied"
        compact
        icon="info"
        title="สร้างตารางเวลาทำงานใหม่ยังไม่รองรับในหน้านี้"
        description="เพิ่ม/แก้ไขตารางเวลาทำงานเองต้องทำผ่านฝั่งพัฒนาระบบ — หน้านี้ใช้สำหรับดูตารางที่มีอยู่และมอบหมายให้พนักงาน/แผนก/ฝ่ายในแท็บ “การมอบหมาย” เท่านั้น"
      />
      <DataTable
        columns={COLUMNS}
        rows={schedulesQuery.data ?? []}
        getRowKey={(row) => row.workScheduleId}
        gridClassName={GRID}
        loading={schedulesQuery.isLoading}
        error={schedulesQuery.error}
        onRetry={() => schedulesQuery.refetch()}
        emptyState={{
          icon: 'calendar',
          title: 'ยังไม่มีตารางเวลาทำงานในระบบ',
        }}
      />
    </div>
  );
}
