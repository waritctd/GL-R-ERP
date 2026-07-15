import { useEffect, useMemo, useState } from 'react';
import { api } from '../../api/index.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { DesktopOnlyNotice } from '../../components/common/DesktopOnlyNotice.jsx';
import { FileUploadField } from '../../components/common/FileUploadField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageStack, StatGrid } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { useIsMobile } from '../../hooks/useIsMobile.js';

const todayIso = () => new Date().toISOString().slice(0, 10);
const monthStartIso = () => {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), 1).toISOString().slice(0, 10);
};

// Reproduces `.filter-bar` for this page's native <form onSubmit> (Enter-to-submit
// needed); Layout.jsx's FilterBar only renders a <div> — same pattern established in
// OvertimePage/LeavePage (docs/agent-handoffs/29_tw-convert-overtime-leave.md).
const FILTER_BAR_CLASS =
  'flex flex-wrap gap-[10px] items-center bg-surface border border-border rounded-md p-[14px]';

/**
 * Mobile record card for a punch record. The desktop grid's 6 columns
 * (`reflow-cards` today stacks every column as its own labeled row, which is
 * usable but noisy for a scan-history list). This keeps only what identifies
 * the punch and lets someone spot a mismapped scan at a glance: who, when,
 * employee code, and where it came from.
 */
function AttendanceCard({ punch }) {
  return (
    <>
      <div className="flex min-w-0 items-start justify-between gap-3">
        <strong className="min-w-0 truncate text-sm font-extrabold text-text">
          {punch.employee_name || 'ยังไม่แมปพนักงาน'}
        </strong>
        <span className="shrink-0 text-right text-xs text-text-muted">
          {formatPunchDateTime(punch.punch_time)}
        </span>
      </div>

      <span className="min-w-0 truncate text-xs text-text-muted">
        {[punch.employee_code, punch.site_code || punch.device_name || punch.device_code]
          .filter(Boolean)
          .join(' · ')}
      </span>

      {punch.position_th ? (
        <span className="min-w-0 truncate text-2xs text-text-muted">{punch.position_th}</span>
      ) : null}
    </>
  );
}

export function AttendancePage({ user, employees, showToast }) {
  const isMobile = useIsMobile();
  // HR/executives see everyone; ฝ่าย managers get the same view scoped to their division
  // (the backend limits the results to the manager's division).
  const canViewAll = hasPermission(user.role, 'canViewAllAttendance') || Boolean(user.manager);
  const canImport = hasPermission(user.role, 'canImportAttendance');
  const [filters, setFilters] = useState({
    from: monthStartIso(),
    to: todayIso(),
    employeeId: '',
    limit: 500,
  });
  const [punches, setPunches] = useState([]);
  const [loading, setLoading] = useState(false);
  const [importing, setImporting] = useState(false);
  const [selectedFile, setSelectedFile] = useState(null);
  const [lastImport, setLastImport] = useState(null);
  const [devices, setDevices] = useState([]);
  const [importDeviceCode, setImportDeviceCode] = useState('');

  const sortedEmployees = useMemo(
    () => [...employees].sort((a, b) => (a.nameTh || '').localeCompare(b.nameTh || '', 'th')),
    [employees],
  );
  const uniqueEmployees = useMemo(() => new Set(punches.map((punch) => punch.employee_id).filter(Boolean)).size, [punches]);
  const unresolvedCount = punches.filter((punch) => !punch.employee_id).length;
  // Multiple scanners/locations feed this table; summarise how many distinct sites appear in the results.
  const sourceSummary = useMemo(() => {
    const sites = new Set(punches.map((punch) => punch.site_code).filter(Boolean));
    if (sites.size === 0) return { value: '-', helper: 'ยังไม่มีข้อมูล' };
    if (sites.size === 1) return { value: [...sites][0], helper: 'สถานที่เดียว' };
    return { value: `${sites.size} สถานที่`, helper: 'หลายเครื่องสแกน' };
  }, [punches]);

  async function loadPunches(nextFilters = filters) {
    setLoading(true);
    try {
      const params = {
        from: nextFilters.from,
        to: nextFilters.to,
        limit: nextFilters.limit,
        ...(canViewAll && nextFilters.employeeId ? { employeeId: nextFilters.employeeId } : {}),
      };
      const response = await api.attendance.list(params);
      setPunches(response.punches || []);
    } catch (error) {
      showToast('error', error.message || 'โหลดข้อมูลเวลาทำงานไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    loadPunches();
  }, []);

  // Load the registered scanners so HR/C-level can attribute an import to the right location.
  useEffect(() => {
    if (!canImport) return;
    let cancelled = false;
    api.attendance.devices()
      .then((response) => {
        if (cancelled) return;
        const list = response.devices || [];
        setDevices(list);
        setImportDeviceCode((current) => current || list[0]?.device_code || '');
      })
      .catch(() => {
        if (!cancelled) setDevices([]);
      });
    return () => {
      cancelled = true;
    };
  }, [canImport]);

  function updateFilter(field, value) {
    setFilters((current) => ({ ...current, [field]: value }));
  }

  async function submitFilters(event) {
    event.preventDefault();
    await loadPunches(filters);
  }

  async function importFile(event) {
    event.preventDefault();
    if (!selectedFile) {
      showToast('error', 'เลือกไฟล์ .dat ก่อนนำเข้า');
      return;
    }
    const device = devices.find((item) => item.device_code === importDeviceCode);
    if (!device) {
      showToast('error', 'เลือกเครื่องสแกน/สถานที่ก่อนนำเข้า');
      return;
    }
    setImporting(true);
    try {
      const content = await selectedFile.text();
      const detectedRange = dateRangeFromDatContent(content);
      const response = await api.attendance.importDat({
        site_code: device.site_code,
        device_code: device.device_code,
        file_name: selectedFile.name,
        content,
      });
      setLastImport(response);
      showToast('success', response.status === 'duplicate_file' ? 'ไฟล์นี้เคยนำเข้าแล้ว' : 'นำเข้าไฟล์เวลาเรียบร้อย');

      const nextFilters = detectedRange
        ? { ...filters, from: detectedRange.from, to: detectedRange.to, employeeId: '' }
        : filters;
      setFilters(nextFilters);
      await loadPunches(nextFilters);
    } catch (error) {
      showToast('error', error.message || 'นำเข้าไฟล์ไม่สำเร็จ');
    } finally {
      setImporting(false);
    }
  }

  return (
    <PageStack>
      <PageHeader
        title="เวลาทำงาน"
        subtitle={canViewAll ? 'ตรวจสอบประวัติการสแกนของพนักงานทุกคน' : 'ตรวจสอบประวัติการสแกนของคุณ'}
        actions={(
          <Button variant="secondary" onClick={() => loadPunches(filters)} disabled={loading}>
            <Icon name="refresh" />
            รีเฟรช
          </Button>
        )}
      />
      {isMobile && <DesktopOnlyNotice />}

      <StatGrid>
        <StatCard label="รายการในช่วงที่เลือก" value={punches.length} helper="Punch records" icon="calendar" tone="indigo" />
        <StatCard label="พนักงานที่พบ" value={uniqueEmployees} helper={canViewAll ? 'จากผลลัพธ์ปัจจุบัน' : 'บัญชีของคุณ'} icon="users" tone="teal" />
        <StatCard label="ยังไม่แมปพนักงาน" value={unresolvedCount} helper="badge_card_no ไม่ตรง" icon="badge" tone="amber" />
        <StatCard label="แหล่งข้อมูล" value={sourceSummary.value} helper={sourceSummary.helper} icon="clock" tone="rose" />
      </StatGrid>

      <form className={FILTER_BAR_CLASS} onSubmit={submitFilters}>
        <label>
          จากวันที่
          <input type="date" value={filters.from} onChange={(event) => updateFilter('from', event.target.value)} />
        </label>
        <label>
          ถึงวันที่
          <input type="date" value={filters.to} onChange={(event) => updateFilter('to', event.target.value)} />
        </label>
        {canViewAll ? (
          <label>
            พนักงาน
            <select value={filters.employeeId} onChange={(event) => updateFilter('employeeId', event.target.value)}>
              <option value="">ทุกคน</option>
              {sortedEmployees.map((employee) => (
                <option key={employee.id} value={employee.id}>{employee.nameTh} · {employee.code}</option>
              ))}
            </select>
          </label>
        ) : null}
        <label>
          จำนวน
          <select value={filters.limit} onChange={(event) => updateFilter('limit', Number(event.target.value))}>
            <option value={100}>100</option>
            <option value={500}>500</option>
            <option value={1000}>1,000</option>
            <option value={2000}>2,000</option>
          </select>
        </label>
        <Button type="submit" disabled={loading}>
          <Icon name="search" />
          ค้นหา
        </Button>
      </form>

      {canImport ? (
        <form className="attendance-import-panel" onSubmit={importFile}>
          <div>
            <strong>นำเข้าไฟล์ Attendance (.dat)</strong>
            <small>เฉพาะ HR / ผู้บริหาร</small>
          </div>
          <label className="attendance-import-device">
            เครื่องสแกน / สถานที่
            <select value={importDeviceCode} onChange={(event) => setImportDeviceCode(event.target.value)}>
              {devices.length === 0 ? (
                <option value="">ไม่พบเครื่องสแกน</option>
              ) : (
                devices.map((device) => (
                  <option key={device.device_code} value={device.device_code}>
                    {device.site_name} · {device.device_name}
                  </option>
                ))
              )}
            </select>
          </label>
          <FileUploadField
            id="attendance-import-file"
            accept=".dat,text/plain"
            onChange={(event) => setSelectedFile(event.target.files?.[0] || null)}
            helperText="ไฟล์ .dat"
          />
          <Button type="submit" variant="success" className="max-[720px]:min-h-11 max-[720px]:w-full" disabled={importing || !selectedFile || !importDeviceCode}>
            <Icon name="plus" />
            {importing ? 'กำลังนำเข้า' : 'นำเข้า'}
          </Button>
          {lastImport ? (
            <span className="attendance-import-result">
              {lastImport.status}: เพิ่ม {lastImport.inserted_punch_count} · ข้าม {lastImport.skipped_punch_count} · ผิดพลาด {lastImport.error_count}
            </span>
          ) : null}
        </form>
      ) : null}

      <DataTable
        columns={attendanceColumns}
        rows={punches}
        getRowKey={(punch) => punch.punch_id}
        gridClassName="grid-cols-[1.35fr_1.5fr_0.8fr_1.2fr_0.8fr_1.15fr] max-[1040px]:min-w-[900px] reflow-cards"
        mobileCard={(punch) => <AttendanceCard punch={punch} />}
        pageSize={50}
        searchable
        searchPlaceholder="ค้นหาพนักงาน / รหัส / ชื่อเล่น"
        loading={loading}
        emptyState={{
          icon: 'calendar',
          title: 'ไม่พบข้อมูลเวลา',
          description: 'ลองเปลี่ยนช่วงวันที่หรือนำเข้าไฟล์ .dat',
        }}
      />
    </PageStack>
  );
}

const attendanceColumns = [
  {
    key: 'punch_time',
    header: 'เวลา',
    sortable: true,
    sortAccessor: (punch) => punch.punch_time,
    render: (punch) => (
      <span>
        <strong>{formatPunchDateTime(punch.punch_time)}</strong>
        <small>{punch.work_date}</small>
      </span>
    ),
  },
  {
    key: 'employee_name',
    header: 'พนักงาน',
    sortable: true,
    sortAccessor: (punch) => punch.employee_name || 'ยังไม่แมปพนักงาน',
    searchAccessor: (punch) => punch.employee_name || '',
    render: (punch) => (
      <span>
        <strong>{punch.employee_name || 'ยังไม่แมปพนักงาน'}</strong>
      </span>
    ),
  },
  {
    key: 'nick_name',
    header: 'ชื่อเล่น',
    searchAccessor: (punch) => punch.nick_name || '',
    render: (punch) => punch.nick_name || '-',
  },
  {
    key: 'position_th',
    header: 'ตำแหน่ง',
    render: (punch) => punch.position_th || '-',
  },
  {
    key: 'employee_code',
    header: 'รหัสพนักงาน',
    searchAccessor: (punch) => punch.employee_code || '',
    render: (punch) => <code>{punch.employee_code || '-'}</code>,
  },
  {
    key: 'site',
    header: 'ไซต์ / อุปกรณ์',
    render: (punch) => (
      <span>
        <strong>{punch.site_code}</strong>
        <small>{punch.device_name || punch.device_code || '-'}</small>
      </span>
    ),
  },
];

function formatPunchDateTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    dateStyle: 'medium',
    timeStyle: 'medium',
    hour12: false,
  }).format(date);
}

function dateRangeFromDatContent(content) {
  const dates = content
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => line.split('\t')[1]?.slice(0, 10))
    .filter((value) => /^\d{4}-\d{2}-\d{2}$/.test(value))
    .sort();
  if (dates.length === 0) return null;
  return { from: dates[0], to: dates[dates.length - 1] };
}
