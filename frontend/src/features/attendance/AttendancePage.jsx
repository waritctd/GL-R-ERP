import { useEffect, useMemo, useState } from 'react';
import { api } from '../../api/index.js';
import { hasPermission } from '../../app/permissions.js';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';

const todayIso = () => new Date().toISOString().slice(0, 10);
const monthStartIso = () => {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), 1).toISOString().slice(0, 10);
};

export function AttendancePage({ user, employees, showToast }) {
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
    <div className="page-stack">
      <PageHeader
        title="เวลาทำงาน"
        subtitle={canViewAll ? 'ตรวจสอบประวัติการสแกนของพนักงานทุกคน' : 'ตรวจสอบประวัติการสแกนของคุณ'}
        actions={(
          <button type="button" className="secondary-button" onClick={() => loadPunches(filters)} disabled={loading}>
            <Icon name="refresh" />
            รีเฟรช
          </button>
        )}
      />

      <section className="stat-grid">
        <StatCard label="รายการในช่วงที่เลือก" value={punches.length} helper="Punch records" icon="calendar" tone="indigo" />
        <StatCard label="พนักงานที่พบ" value={uniqueEmployees} helper={canViewAll ? 'จากผลลัพธ์ปัจจุบัน' : 'บัญชีของคุณ'} icon="users" tone="teal" />
        <StatCard label="ยังไม่แมปพนักงาน" value={unresolvedCount} helper="badge_card_no ไม่ตรง" icon="badge" tone="amber" />
        <StatCard label="แหล่งข้อมูล" value={sourceSummary.value} helper={sourceSummary.helper} icon="clock" tone="rose" />
      </section>

      <form className="filter-bar" onSubmit={submitFilters}>
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
        <button type="submit" className="primary-button" disabled={loading}>
          <Icon name="search" />
          ค้นหา
        </button>
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
          <button type="submit" className="success-button" disabled={importing || !selectedFile || !importDeviceCode}>
            <Icon name="plus" />
            {importing ? 'กำลังนำเข้า' : 'นำเข้า'}
          </button>
          {lastImport ? (
            <span className="attendance-import-result">
              {lastImport.status}: เพิ่ม {lastImport.inserted_punch_count} · ข้าม {lastImport.skipped_punch_count} · ผิดพลาด {lastImport.error_count}
            </span>
          ) : null}
        </form>
      ) : null}

      <section className="table-panel">
        <div className="attendance-table table-head">
          <span>เวลา</span>
          <span>พนักงาน</span>
          <span>ชื่อเล่น</span>
          <span>ตำแหน่ง</span>
          <span>รหัสบัตร</span>
          <span>ไซต์ / อุปกรณ์</span>
        </div>
        {loading ? (
          <EmptyState icon="clock" title="กำลังโหลดข้อมูล" />
        ) : punches.length === 0 ? (
          <EmptyState icon="calendar" title="ไม่พบข้อมูลเวลา" description="ลองเปลี่ยนช่วงวันที่หรือนำเข้าไฟล์ .dat" />
        ) : punches.map((punch) => (
          <div className="attendance-table table-row" key={punch.punch_id}>
            <span>
              <strong>{formatPunchDateTime(punch.punch_time)}</strong>
              <small>{punch.work_date}</small>
            </span>
            <span>
              <strong>{punch.employee_name || 'ยังไม่แมปพนักงาน'}</strong>
              <small>{punch.employee_code || '-'}</small>
            </span>
            <span>{punch.nick_name || '-'}</span>
            <span>{punch.position_th || '-'}</span>
            <code>{cardNoLabel(punch)}</code>
            <span>
              <strong>{punch.site_code}</strong>
              <small>{punch.device_name || punch.device_code || '-'}</small>
            </span>
          </div>
        ))}
      </section>
    </div>
  );
}

// The รหัสบัตร column is the card number. A PIN/fingerprint punch scans the employee code,
// not a card, so never surface that here — show the employee's registered card instead.
function cardNoLabel(punch) {
  if (punch.badge_card_no && punch.badge_card_no !== punch.employee_code) {
    return punch.badge_card_no;
  }
  // Unmapped rows have no employee on file — show the raw scanned serial so HR can
  // still identify the unknown card. (The employee-code field stays blank for these.)
  if (!punch.employee_id) {
    return punch.badge_code || '-';
  }
  return '-';
}

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
