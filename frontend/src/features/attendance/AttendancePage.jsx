import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { DesktopOnlyNotice } from '../../components/common/DesktopOnlyNotice.jsx';
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

export function AttendancePage({ user, employees, showToast }) {
  const isMobile = useIsMobile();
  const queryClient = useQueryClient();
  // HR/executives see everyone; ฝ่าย managers get the same view scoped to their division
  // (the backend limits the results to the manager's division).
  const canViewAll = hasPermission(user.role, 'canViewAllAttendance') || Boolean(user.manager);
  const canImport = hasPermission(user.role, 'canImportAttendance');
  const initialFilters = {
    from: monthStartIso(),
    to: todayIso(),
    employeeId: '',
    limit: 500,
  };
  const [filters, setFilters] = useState(initialFilters);
  const [appliedFilters, setAppliedFilters] = useState(initialFilters);
  const [selectedFile, setSelectedFile] = useState(null);
  const [importDeviceCode, setImportDeviceCode] = useState('');

  const sortedEmployees = useMemo(
    () => [...employees].sort((a, b) => (a.nameTh || '').localeCompare(b.nameTh || '', 'th')),
    [employees],
  );

  const punchesQuery = useQuery({
    queryKey: queryKeys.attendancePunches(appliedFilters),
    queryFn: () => api.attendance.list({
      from: appliedFilters.from,
      to: appliedFilters.to,
      limit: appliedFilters.limit,
      ...(canViewAll && appliedFilters.employeeId ? { employeeId: appliedFilters.employeeId } : {}),
    }).then((response) => response.punches || []),
  });
  const punches = useMemo(() => punchesQuery.data ?? [], [punchesQuery.data]);
  const loading = punchesQuery.isLoading || punchesQuery.isFetching;

  useEffect(() => {
    if (punchesQuery.error) showToast('error', punchesQuery.error.message || 'โหลดข้อมูลเวลาทำงานไม่สำเร็จ');
  }, [punchesQuery.error, showToast]);

  // Load the registered scanners so HR/C-level can attribute an import to the right location.
  const devicesQuery = useQuery({
    queryKey: queryKeys.attendanceDevices(),
    queryFn: () => api.attendance.devices().then((response) => response.devices || []),
    enabled: canImport,
  });
  const devices = useMemo(() => devicesQuery.data ?? [], [devicesQuery.data]);

  // Default the import device once the list lands, without overriding a user pick.
  useEffect(() => {
    if (devicesQuery.data && !importDeviceCode) {
      setImportDeviceCode(devicesQuery.data[0]?.device_code || '');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- mirrors the old effect's "seed once" intent
  }, [devicesQuery.data]);

  const uniqueEmployees = useMemo(() => new Set(punches.map((punch) => punch.employee_id).filter(Boolean)).size, [punches]);
  const unresolvedCount = punches.filter((punch) => !punch.employee_id).length;
  // Multiple scanners/locations feed this table; summarise how many distinct sites appear in the results.
  const sourceSummary = useMemo(() => {
    const sites = new Set(punches.map((punch) => punch.site_code).filter(Boolean));
    if (sites.size === 0) return { value: '-', helper: 'ยังไม่มีข้อมูล' };
    if (sites.size === 1) return { value: [...sites][0], helper: 'สถานที่เดียว' };
    return { value: `${sites.size} สถานที่`, helper: 'หลายเครื่องสแกน' };
  }, [punches]);

  function updateFilter(field, value) {
    setFilters((current) => ({ ...current, [field]: value }));
  }

  function submitFilters(event) {
    event.preventDefault();
    setAppliedFilters(filters);
  }

  const importMutation = useMutation({
    mutationFn: (vars) => api.attendance.importDat(vars.payload),
    onSuccess: (response, vars) => {
      showToast('success', response.status === 'duplicate_file' ? 'ไฟล์นี้เคยนำเข้าแล้ว' : 'นำเข้าไฟล์เวลาเรียบร้อย');
      const nextFilters = vars.detectedRange
        ? { ...filters, from: vars.detectedRange.from, to: vars.detectedRange.to, employeeId: '' }
        : filters;
      setFilters(nextFilters);
      setAppliedFilters(nextFilters);
      queryClient.invalidateQueries({ queryKey: queryKeys.attendancePunches(nextFilters) });
    },
    onError: (error) => showToast('error', error.message || 'นำเข้าไฟล์ไม่สำเร็จ'),
  });
  const lastImport = importMutation.data ?? null;

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
    let content;
    try {
      content = await selectedFile.text();
    } catch (error) {
      showToast('error', error.message || 'นำเข้าไฟล์ไม่สำเร็จ');
      return;
    }
    importMutation.mutate({
      payload: {
        site_code: device.site_code,
        device_code: device.device_code,
        file_name: selectedFile.name,
        content,
      },
      detectedRange: dateRangeFromDatContent(content),
    });
  }

  return (
    <PageStack>
      {isMobile && <DesktopOnlyNotice />}
      <PageHeader
        title="เวลาทำงาน"
        subtitle={canViewAll ? 'ตรวจสอบประวัติการสแกนของพนักงานทุกคน' : 'ตรวจสอบประวัติการสแกนของคุณ'}
        actions={(
          <Button variant="secondary" onClick={() => punchesQuery.refetch()} disabled={loading}>
            <Icon name="refresh" />
            รีเฟรช
          </Button>
        )}
      />

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
          <input type="file" accept=".dat,text/plain" onChange={(event) => setSelectedFile(event.target.files?.[0] || null)} />
          <Button type="submit" variant="success" className="max-[720px]:w-full" disabled={importMutation.isPending || !selectedFile || !importDeviceCode}>
            <Icon name="plus" />
            {importMutation.isPending ? 'กำลังนำเข้า' : 'นำเข้า'}
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
