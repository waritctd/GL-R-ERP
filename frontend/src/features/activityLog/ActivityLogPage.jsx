import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { DataTable } from '../../components/common/DataTable.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { Tabs } from '../../components/common/Tabs.jsx';
import { actionLabel, actionTone } from './actionLabels.js';

/**
 * Admin-only view of what everyone has been doing in the portal.
 *
 * <p>Three tabs, because there are genuinely three different questions and one table cannot answer
 * all of them:
 *
 * - **สรุป** — who was in the portal at all, and when. Sourced from hr.activity_log, which the
 *   request filter writes for every /api/ call, so it sees people who only browsed.
 * - **การดำเนินการ** — who requested what and who approved what. Sourced from hr.audit_log, which
 *   is semantic: one row per business action, with a readable name.
 * - **คำขอทั้งหมด** — the raw request stream, for when the other two do not explain something.
 *
 * The gate is server-side (ActivityLogService.requireAdmin re-reads hr.employee.is_admin on every
 * call); `user.admin` here only decides whether the route renders, and a client that forges it
 * still gets 403 from all three endpoints.
 */

// Tabs.jsx's contract is `items: [{ id, label, ... }]`, not `value` — see PR for the incident
// this fixed. Exported so ActivityLogPage.test.jsx can drive its tab-switch test off the real
// array instead of a hardcoded label list.
export const TABS = [
  { id: 'summary', label: 'สรุป' },
  { id: 'actions', label: 'การดำเนินการ' },
  { id: 'requests', label: 'คำขอทั้งหมด' },
  { id: 'system', label: 'ระบบและข้อผิดพลาด' },
];

const TONE_CLASS = {
  positive: 'text-emerald-700 dark:text-emerald-400',
  negative: 'text-rose-700 dark:text-rose-400',
  muted: 'text-text-muted',
  neutral: 'text-text',
};

function todayInBangkok() {
  // The backend windows on Asia/Bangkok, so the default the page sends must agree — a browser in
  // another timezone would otherwise ask for the wrong day and get a confusingly empty table.
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bangkok' }).format(new Date());
}

// `!value` alone is not enough: a non-empty value that doesn't parse to a real date (a malformed
// timestamp, or an OffsetDateTime serialized wrong by a misconfigured ObjectMapper — this repo has
// hit exactly that before, see hand-wired-controller-it-objectmapper-instant) leaves `new Date(...)`
// an Invalid Date. Intl.DateTimeFormat#format then throws RangeError synchronously, during render,
// inside a DataTable column — not inside a promise, so react-query's isError never sees it. It
// propagates straight to the route-level ErrorBoundary, which replaces the ENTIRE page for every
// tab. Same failure class as the Tabs.jsx id/value mismatch fixed on
// fix/activity-log-page-load-failure; this closes the other trigger for it.
function isValidDate(date) {
  return !Number.isNaN(date.getTime());
}

// Exported (only) so ActivityLogPage.test.jsx can unit-test the invalid-date guard directly,
// independent of which tab renders it — formatTime backs summary's firstSeen/lastSeen,
// formatDateTime backs every other tab's `at` column.
export function formatTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (!isValidDate(date)) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    timeZone: 'Asia/Bangkok', hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).format(date);
}

export function formatDateTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (!isValidDate(date)) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    timeZone: 'Asia/Bangkok', day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit',
  }).format(date);
}

function personLabel(row) {
  const name = row.name || row.actorName;
  const code = row.employeeCode || row.actorEmployeeCode;
  if (name && code) return `${name} (${code})`;
  return name || code || row.email || row.actorEmail || 'ไม่ทราบผู้ใช้';
}

export function ActivityLogPage() {
  const [tab, setTab] = useState('summary');
  const [from, setFrom] = useState(todayInBangkok);
  const [to, setTo] = useState(todayInBangkok);

  const range = useMemo(() => ({ from, to }), [from, to]);

  const summaryQuery = useQuery({
    queryKey: ['activityLog', 'summary', range],
    queryFn: () => api.activityLog.summary(range),
    enabled: tab === 'summary',
  });

  const auditQuery = useQuery({
    queryKey: ['activityLog', 'audit', range],
    queryFn: () => api.activityLog.audit(range),
    enabled: tab === 'actions',
  });

  const requestsQuery = useQuery({
    queryKey: ['activityLog', 'requests', range],
    queryFn: () => api.activityLog.list(range),
    enabled: tab === 'requests',
  });

  const systemQuery = useQuery({
    queryKey: ['activityLog', 'system', range],
    queryFn: () => api.activityLog.events(range),
    enabled: tab === 'system',
  });

  const summaryColumns = useMemo(() => [
    { key: 'person', header: 'พนักงาน', render: (row) => personLabel(row) },
    {
      key: 'requestCount',
      header: 'จำนวนการใช้งาน',
      align: 'right',
      render: (row) => row.requestCount?.toLocaleString('th-TH') ?? '-',
    },
    { key: 'firstSeen', header: 'เข้าใช้ครั้งแรก', render: (row) => formatTime(row.firstSeen) },
    { key: 'lastSeen', header: 'ล่าสุด', render: (row) => formatTime(row.lastSeen) },
  ], []);

  const auditColumns = useMemo(() => [
    { key: 'at', header: 'เวลา', render: (row) => formatDateTime(row.at) },
    { key: 'actor', header: 'ผู้ดำเนินการ', render: (row) => personLabel(row) },
    {
      key: 'action',
      header: 'การดำเนินการ',
      render: (row) => (
        <span className={TONE_CLASS[actionTone(row.action)] || TONE_CLASS.neutral}>
          {actionLabel(row.action)}
        </span>
      ),
    },
    {
      key: 'subject',
      header: 'เกี่ยวกับ',
      render: (row) => row.subjectName || (row.entityId ? `${row.entity} #${row.entityId}` : '-'),
    },
  ], []);

  const requestColumns = useMemo(() => [
    { key: 'at', header: 'เวลา', render: (row) => formatDateTime(row.at) },
    { key: 'person', header: 'พนักงาน', render: (row) => personLabel(row) },
    { key: 'method', header: 'วิธี', render: (row) => row.method },
    { key: 'path', header: 'ปลายทาง', render: (row) => <code className="text-xs">{row.path}</code> },
    {
      key: 'status',
      header: 'ผล',
      align: 'right',
      render: (row) => (
        <span className={row.status >= 400 ? TONE_CLASS.negative : TONE_CLASS.muted}>{row.status}</span>
      ),
    },
  ], []);

  const systemColumns = useMemo(() => [
    { key: 'at', header: 'เวลา', render: (row) => formatDateTime(row.at) },
    {
      key: 'kind',
      header: 'ประเภท',
      render: (row) => (row.kind === 'JOB' ? 'งานเบื้องหลัง' : 'ข้อความระบบ'),
    },
    {
      key: 'level',
      header: 'ระดับ',
      render: (row) => (
        <span className={row.level === 'ERROR' ? TONE_CLASS.negative : row.level === 'WARN' ? 'text-amber-700 dark:text-amber-400' : TONE_CLASS.muted}>
          {row.level}
        </span>
      ),
    },
    { key: 'logger', header: 'ที่มา', render: (row) => <code className="text-xs">{row.logger || '-'}</code> },
    {
      key: 'message',
      header: 'รายละเอียด',
      render: (row) => (
        <div className="flex flex-col gap-0.5">
          <span>{row.message}</span>
          {row.exceptionType && (
            <span className="text-xs text-text-muted">
              {row.exceptionType}{row.exceptionMessage ? `: ${row.exceptionMessage}` : ''}
            </span>
          )}
          {/* One frame by design — see V159. Never a full trace on a web page. */}
          {row.firstFrame && <code className="text-[11px] text-text-faint">{row.firstFrame}</code>}
        </div>
      ),
    },
    {
      key: 'durationMs',
      header: 'ใช้เวลา',
      align: 'right',
      render: (row) => (row.durationMs == null ? '-' : `${row.durationMs.toLocaleString('th-TH')} ms`),
    },
  ], []);

  // Lookup rather than chained ternaries: at four tabs the ternary chain stopped being readable,
  // and a missing branch would have silently rendered the wrong tab's columns.
  const QUERIES = { summary: summaryQuery, actions: auditQuery, requests: requestsQuery, system: systemQuery };
  const COLUMNS = { summary: summaryColumns, actions: auditColumns, requests: requestColumns, system: systemColumns };
  const EMPTY = {
    summary: 'ไม่มีการใช้งานในช่วงเวลานี้',
    actions: 'ไม่มีการดำเนินการในช่วงเวลานี้',
    requests: 'ไม่มีการใช้งานในช่วงเวลานี้',
    system: 'ไม่มีข้อผิดพลาดหรืองานเบื้องหลังในช่วงเวลานี้',
  };

  const active = QUERIES[tab];
  const rows = active.data ?? [];
  const columns = COLUMNS[tab];
  const emptyMessage = EMPTY[tab];

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="บันทึกการใช้งานระบบ"
        subtitle="ดูว่าใครเข้าใช้ระบบ และใครยื่นหรืออนุมัติอะไรบ้าง"
      />

      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-text-muted">ตั้งแต่วันที่</span>
          <input
            type="date"
            value={from}
            max={to}
            onChange={(event) => setFrom(event.target.value)}
            className="rounded-md border border-border bg-surface px-3 py-2"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-text-muted">ถึงวันที่</span>
          <input
            type="date"
            value={to}
            min={from}
            onChange={(event) => setTo(event.target.value)}
            className="rounded-md border border-border bg-surface px-3 py-2"
          />
        </label>
      </div>

      <Tabs items={TABS} value={tab} onChange={setTab} ariaLabel="มุมมองบันทึกการใช้งาน" idPrefix="activity-log" />

      <DataTable
        columns={columns}
        rows={rows}
        getRowKey={(row) => row.id ?? `emp-${row.employeeId}`}
        loading={active.isLoading}
        error={active.isError}
        onRetry={active.refetch}
        pageSize={25}
        stickyHeader
        emptyState={<EmptyState title={emptyMessage} />}
      />
    </div>
  );
}

export default ActivityLogPage;
