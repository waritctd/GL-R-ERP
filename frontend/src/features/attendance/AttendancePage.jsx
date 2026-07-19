import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../../api/index.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { FileUploadField } from '../../components/common/FileUploadField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  attendanceFlagLabels,
  attendanceStatusLabel,
  bangkokMonthStartIso,
  bangkokTodayIso,
  formatBangkokTime,
  formatDuration,
  formatShortDate,
} from '../../utils/format.js';

// Reproduces `.filter-bar` for this page's native <form onSubmit> (Enter-to-submit
// needed); Layout.jsx's FilterBar only renders a <div> — same pattern established in
// OvertimePage/LeavePage (docs/agent-handoffs/29_tw-convert-overtime-leave.md).
const FILTER_BAR_CLASS =
  'flex flex-wrap gap-[10px] items-center bg-surface border border-border rounded-md p-[14px]';

/**
 * Which view of attendance this user gets. Mirrors `dashboardMode()` in EmployeeDashboard so the
 * two surfaces agree on who counts as a manager, and mirrors the backend's own three-way scope
 * (hr/ceo = everyone, ฝ่าย manager = their division, otherwise = self).
 */
function attendanceMode(user) {
  if (hasPermission(user.role, 'canViewAllAttendance')) return 'company';
  if (user?.manager) return 'manager';
  return 'employee';
}

/**
 * The date window the page may show.
 *
 * Deliberately capped at the current month. Backfill computes all of history, but presenting years
 * of never-before-reviewed "late" days on day one would be a personnel problem rather than a
 * feature; older months are a later UI change, not a recompute.
 */
function monthBounds() {
  return { start: bangkokMonthStartIso(), today: bangkokTodayIso() };
}

export function AttendancePage({ user, showToast }) {
  const mode = attendanceMode(user);
  const isSelfView = mode === 'employee';
  const canImport = hasPermission(user.role, 'canImportAttendance');
  const canSeeUnmapped = hasPermission(user.role, 'canViewAllAttendance');
  const { start: monthStart, today } = useMemo(() => monthBounds(), []);

  // Employees read their own month at a glance; everyone else answers "who is late today", so
  // they get a single day plus a stepper.
  const [selectedDate, setSelectedDate] = useState(today);
  const [employeeId, setEmployeeId] = useState('');
  const [days, setDays] = useState([]);
  const [loading, setLoading] = useState(false);
  const [employeeOptions, setEmployeeOptions] = useState([]);
  const [unmapped, setUnmapped] = useState([]);
  const [unmappedOpen, setUnmappedOpen] = useState(false);
  const [expandedKey, setExpandedKey] = useState(null);
  const [punchesByKey, setPunchesByKey] = useState({});

  const [importOpen, setImportOpen] = useState(false);
  const [importing, setImporting] = useState(false);
  const [selectedFile, setSelectedFile] = useState(null);
  const [lastImport, setLastImport] = useState(null);
  const [devices, setDevices] = useState([]);
  const [importDeviceCode, setImportDeviceCode] = useState('');

  const range = isSelfView
    ? { from: monthStart, to: today }
    : { from: selectedDate, to: selectedDate };

  const loadDays = useCallback(async () => {
    setLoading(true);
    try {
      const response = await api.attendance.daily({
        from: range.from,
        to: range.to,
        ...(employeeId ? { employeeId } : {}),
      });
      setDays(response.days || []);
      setExpandedKey(null);
    } catch (error) {
      showToast('error', error.message || 'โหลดข้อมูลเวลาทำงานไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }, [range.from, range.to, employeeId, showToast]);

  useEffect(() => {
    loadDays();
  }, [loadDays]);

  // A ฝ่าย manager's picker cannot come from the shared `employees` prop — that list is HR-only,
  // so it holds exactly one entry for them. This endpoint returns their actual team.
  useEffect(() => {
    if (isSelfView) return undefined;
    let cancelled = false;
    api.attendance.employees()
      .then((response) => {
        if (!cancelled) setEmployeeOptions(response.employees || []);
      })
      .catch(() => {
        if (!cancelled) setEmployeeOptions([]);
      });
    return () => {
      cancelled = true;
    };
  }, [isSelfView]);

  // Unmapped scans can never appear in a day view (they have no employee), so surface them
  // separately or someone's attendance goes missing with no signal at all.
  useEffect(() => {
    if (!canSeeUnmapped) return undefined;
    let cancelled = false;
    api.attendance.unmapped({ from: range.from, to: range.to })
      .then((response) => {
        if (!cancelled) setUnmapped(response.badges || []);
      })
      .catch(() => {
        if (!cancelled) setUnmapped([]);
      });
    return () => {
      cancelled = true;
    };
  }, [canSeeUnmapped, range.from, range.to]);

  useEffect(() => {
    if (!canImport) return undefined;
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

  const rowKey = (day) => `${day.employee_id}:${day.work_date}`;

  async function toggleExpanded(day) {
    const key = rowKey(day);
    if (expandedKey === key) {
      setExpandedKey(null);
      return;
    }
    setExpandedKey(key);
    if (punchesByKey[key]) return;
    try {
      const response = await api.attendance.list({
        from: day.work_date,
        to: day.work_date,
        employeeId: day.employee_id,
      });
      setPunchesByKey((current) => ({ ...current, [key]: response.punches || [] }));
    } catch (error) {
      showToast('error', error.message || 'โหลดรายการสแกนไม่สำเร็จ');
    }
  }

  function stepDay(deltaDays) {
    const next = new Date(`${selectedDate}T00:00:00+07:00`);
    next.setDate(next.getDate() + deltaDays);
    const iso = next.toISOString().slice(0, 10);
    // Clamp to the current month in both directions — see monthBounds().
    if (iso < monthStart || iso > today) return;
    setSelectedDate(iso);
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
      const response = await api.attendance.importDat({
        site_code: device.site_code,
        device_code: device.device_code,
        file_name: selectedFile.name,
        content,
      });
      setLastImport(response);
      showToast(
        'success',
        response.status === 'duplicate_file' ? 'ไฟล์นี้เคยนำเข้าแล้ว' : 'นำเข้าไฟล์เวลาเรียบร้อย',
      );
      await loadDays();
    } catch (error) {
      showToast('error', error.message || 'นำเข้าไฟล์ไม่สำเร็จ');
    } finally {
      setImporting(false);
    }
  }

  const columns = useMemo(
    () => (isSelfView ? selfColumns : teamColumns),
    [isSelfView],
  );

  const unmappedTotal = unmapped.reduce((sum, badge) => sum + (badge.punch_count || 0), 0);

  return (
    <PageStack>
      <PageHeader
        title="เวลาทำงาน"
        subtitle={
          isSelfView
            ? 'เวลาเข้า-ออกงานของคุณในเดือนนี้'
            : 'เวลาเข้า-ออกงานรายวัน'
        }
        actions={(
          <Button variant="secondary" onClick={loadDays} disabled={loading}>
            <Icon name="refresh" />
            รีเฟรช
          </Button>
        )}
      />

      {canSeeUnmapped && unmapped.length > 0 ? (
        <div className="rounded-md border border-warning-border bg-warning-bg p-[14px]">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <span className="text-sm text-text">
              <strong>มี {unmappedTotal} รายการสแกนที่ยังไม่ระบุพนักงาน</strong>
              {' '}
              จาก {unmapped.length} บัตร — เวลาทำงานเหล่านี้จะยังไม่ปรากฏในตาราง
            </span>
            <Button variant="secondary" onClick={() => setUnmappedOpen((open) => !open)}>
              {unmappedOpen ? 'ซ่อน' : 'ตรวจสอบและแก้ไข'}
            </Button>
          </div>
          {unmappedOpen ? (
            <ul className="mt-3 grid gap-2">
              {unmapped.map((badge) => (
                <li
                  key={badge.badge_code}
                  className="flex flex-wrap items-center justify-between gap-2 rounded-sm border border-border bg-surface px-3 py-2 text-xs"
                >
                  <code className="font-bold">{badge.badge_code}</code>
                  <span className="text-text-muted">
                    {badge.punch_count} ครั้ง · {formatShortDate(badge.first_seen)}
                    {' – '}
                    {formatShortDate(badge.last_seen)} · {badge.site_code || '-'}
                  </span>
                </li>
              ))}
              <li className="text-2xs text-text-muted">
                แก้ไขได้โดยตั้งค่ารหัสบัตร (badge_card_no) ของพนักงานให้ตรงกับรหัสข้างต้น
                ที่หน้าข้อมูลพนักงาน
              </li>
            </ul>
          ) : null}
        </div>
      ) : null}

      {canImport ? (
        <div className="rounded-md border border-border bg-surface">
          <button
            type="button"
            className="flex w-full items-center justify-between gap-3 px-[14px] py-3 text-left"
            onClick={() => setImportOpen((open) => !open)}
            aria-expanded={importOpen}
          >
            <span className="text-sm font-bold text-text">นำเข้าไฟล์ Attendance (.dat)</span>
            <span className="text-xs text-text-muted">
              {importOpen ? 'ซ่อน' : 'เฉพาะ HR / ผู้บริหาร'}
            </span>
          </button>
          {importOpen ? (
            <form className="attendance-import-panel border-t border-border" onSubmit={importFile}>
              <label className="attendance-import-device">
                เครื่องสแกน / สถานที่
                <select
                  value={importDeviceCode}
                  onChange={(event) => setImportDeviceCode(event.target.value)}
                >
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
              <Button
                type="submit"
                variant="success"
                className="max-[720px]:min-h-11 max-[720px]:w-full"
                disabled={importing || !selectedFile || !importDeviceCode}
              >
                <Icon name="plus" />
                {importing ? 'กำลังนำเข้า' : 'นำเข้า'}
              </Button>
              {lastImport ? (
                <span className="attendance-import-result">
                  {lastImport.status}: เพิ่ม {lastImport.inserted_punch_count} · ข้าม{' '}
                  {lastImport.skipped_punch_count} · ผิดพลาด {lastImport.error_count}
                </span>
              ) : null}
            </form>
          ) : null}
        </div>
      ) : null}

      {!isSelfView ? (
        <div className={FILTER_BAR_CLASS}>
          <div className="flex items-center gap-2">
            <Button
              variant="secondary"
              onClick={() => stepDay(-1)}
              disabled={loading || selectedDate <= monthStart}
              aria-label="วันก่อนหน้า"
            >
              <Icon name="chevronLeft" />
            </Button>
            <label className="m-0">
              วันที่
              <input
                type="date"
                value={selectedDate}
                min={monthStart}
                max={today}
                onChange={(event) => setSelectedDate(event.target.value || today)}
              />
            </label>
            <Button
              variant="secondary"
              onClick={() => stepDay(1)}
              disabled={loading || selectedDate >= today}
              aria-label="วันถัดไป"
            >
              <Icon name="chevronRight" />
            </Button>
          </div>
          {employeeOptions.length > 1 ? (
            <label>
              พนักงาน
              <select value={employeeId} onChange={(event) => setEmployeeId(event.target.value)}>
                <option value="">ทุกคน</option>
                {employeeOptions.map((option) => (
                  <option key={option.employee_id} value={option.employee_id}>
                    {option.employee_name} · {option.employee_code}
                  </option>
                ))}
              </select>
            </label>
          ) : null}
        </div>
      ) : null}

      <DataTable
        columns={columns}
        rows={days}
        getRowKey={rowKey}
        // Status is the reason this page exists, so it gets the widest share and the time columns
        // are held narrow. Without this the status column was pushed off-screen at tablet width,
        // which hid every late/early/OT badge on the page.
        gridClassName={
          isSelfView
            ? 'grid-cols-[0.7fr_0.45fr_0.45fr_0.35fr_2.05fr] reflow-cards'
            : 'grid-cols-[1.3fr_0.45fr_0.45fr_0.35fr_2.05fr] max-[900px]:min-w-[720px] reflow-cards'
        }
        mobileCard={(day) => <AttendanceDayCard day={day} isSelfView={isSelfView} />}
        pageSize={isSelfView ? 31 : 50}
        searchable={!isSelfView}
        searchPlaceholder="ค้นหาพนักงาน / รหัส / ชื่อเล่น"
        loading={loading}
        onRowClick={toggleExpanded}
        renderExpanded={(day) =>
          expandedKey === rowKey(day) ? (
            <PunchDetail punches={punchesByKey[rowKey(day)]} />
          ) : null
        }
        emptyState={{
          icon: 'calendar',
          title: 'ไม่พบข้อมูลเวลา',
          description: isSelfView
            ? 'ยังไม่มีการสแกนในเดือนนี้'
            : 'ลองเปลี่ยนวันที่ หรือนำเข้าไฟล์ .dat',
        }}
      />
    </PageStack>
  );
}

/** The raw scans behind a day — what settles "the system says 17:32, why?". */
function PunchDetail({ punches }) {
  if (!punches) {
    return <span className="text-xs text-text-muted">กำลังโหลดรายการสแกน…</span>;
  }
  if (punches.length === 0) {
    return <span className="text-xs text-text-muted">ไม่มีรายการสแกนในวันนี้</span>;
  }
  return (
    <ul className="grid gap-1">
      {punches.map((punch) => (
        <li key={punch.punch_id} className="flex flex-wrap gap-2 text-xs text-text-muted">
          <strong className="text-text">{formatBangkokTime(punch.punch_time)}</strong>
          <span>{punch.site_code || '-'}</span>
          <span>{punch.device_name || punch.device_code || '-'}</span>
        </li>
      ))}
    </ul>
  );
}

function StatusCell({ day }) {
  const flags = attendanceFlagLabels(day);
  if (flags.length === 0) {
    const status = attendanceStatusLabel(day.status);
    if (day.status === 'NO_RECORD') return <span className="text-text-muted">-</span>;
    return <StatusBadge tone={status.tone}>{status.label}</StatusBadge>;
  }
  return (
    <span className="flex flex-wrap gap-1">
      {flags.map((flag) => (
        <StatusBadge key={flag.key} tone={flag.tone}>{flag.label}</StatusBadge>
      ))}
    </span>
  );
}

function AttendanceDayCard({ day, isSelfView }) {
  // A day with no scans should read as one quiet line, not as a card full of dashes — on a phone
  // every empty day costs a full card, and "รวม - ชม." plus a bare "-" is three ways of saying
  // "nothing happened".
  const hasTimes = Boolean(day.check_in || day.check_out);
  return (
    <>
      <div className="flex min-w-0 items-start justify-between gap-3">
        <strong className="min-w-0 truncate text-sm font-extrabold text-text">
          {isSelfView ? formatShortDate(day.work_date) : day.employee_name || '-'}
        </strong>
        {hasTimes ? (
          <span className="shrink-0 text-right text-xs text-text-muted">
            {formatBangkokTime(day.check_in)} – {formatBangkokTime(day.check_out)}
          </span>
        ) : null}
      </div>
      {!isSelfView ? (
        <span className="min-w-0 truncate text-xs text-text-muted">
          {[day.employee_code, formatShortDate(day.work_date)].filter(Boolean).join(' · ')}
        </span>
      ) : null}
      {day.total_minutes != null ? (
        <span className="text-xs text-text-muted">รวม {formatDuration(day.total_minutes)} ชม.</span>
      ) : null}
      {day.status === 'NO_RECORD' && !hasTimes ? (
        <span className="text-xs text-text-muted">ไม่มีข้อมูล</span>
      ) : (
        <StatusCell day={day} />
      )}
    </>
  );
}

const statusColumn = {
  key: 'status',
  header: 'สถานะ',
  render: (day) => <StatusCell day={day} />,
};

const timeColumns = [
  {
    key: 'check_in',
    header: 'เข้า',
    sortable: true,
    sortAccessor: (day) => day.check_in || '',
    render: (day) => <strong>{formatBangkokTime(day.check_in)}</strong>,
  },
  {
    key: 'check_out',
    header: 'ออก',
    sortable: true,
    sortAccessor: (day) => day.check_out || '',
    render: (day) => <strong>{formatBangkokTime(day.check_out)}</strong>,
  },
  {
    key: 'total_minutes',
    header: 'ชม.',
    sortable: true,
    sortAccessor: (day) => day.total_minutes ?? -1,
    render: (day) => <span>{formatDuration(day.total_minutes)}</span>,
  },
];

const selfColumns = [
  {
    key: 'work_date',
    header: 'วันที่',
    sortable: true,
    sortAccessor: (day) => day.work_date,
    render: (day) => <strong>{formatShortDate(day.work_date)}</strong>,
  },
  ...timeColumns,
  statusColumn,
];

const teamColumns = [
  {
    key: 'employee_name',
    header: 'พนักงาน',
    sortable: true,
    sortAccessor: (day) => day.employee_name || '',
    searchAccessor: (day) =>
      [day.employee_name, day.nick_name, day.employee_code, day.position_th]
        .filter(Boolean)
        .join(' '),
    render: (day) => (
      <span>
        <strong>{day.employee_name || '-'}</strong>
        <small>{[day.nick_name, day.employee_code].filter(Boolean).join(' · ') || '-'}</small>
      </span>
    ),
  },
  ...timeColumns,
  statusColumn,
];
