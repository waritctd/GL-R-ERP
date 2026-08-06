import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { FileUploadField } from '../../components/common/FileUploadField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  addDaysIso,
  attendanceFlagLabels,
  attendanceSourceLabel,
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
//
// `items-end`, not `items-center` — see MyLeaveTab.jsx's identical FILTER_BAR_CLASS
// for the full explanation. Only reachable for HR/manager (`!isSelfView`), so it
// never showed on the employee self-view, but it is the same defect and the same fix.
const FILTER_BAR_CLASS =
  'flex flex-wrap gap-[10px] items-end bg-surface border border-border rounded-md p-[14px]';

// Earlier than any punch the scanners have produced (the oldest on record is Nov 2020), so a
// backfill covers everything without needing to look up where history starts. The backend walks
// only the days that actually have punches, so an over-wide start costs nothing.
const BACKFILL_START = '2015-01-01';

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

// How far back the date stepper/picker may browse. Deliberately NOT full history -- backfill
// computes all of it, but presenting years of never-before-reviewed "late" days on day one would
// be a personnel problem rather than a feature. Product decision (2026-08): widened from
// "this month only" to a rolling 3-month window so a Bangkok-morning check on the 1st/2nd of a
// month can still reach last month's tail end.
const BROWSABLE_MONTHS_BACK = 3;

/**
 * The date window the page may show.
 */
function monthBounds() {
  return { start: bangkokMonthStartIso(undefined, BROWSABLE_MONTHS_BACK), today: bangkokTodayIso() };
}

export function AttendancePage({ user, showToast }) {
  const mode = attendanceMode(user);
  const isSelfView = mode === 'employee';
  const canImport = hasPermission(user.role, 'canImportAttendance');
  const canSeeUnmapped = hasPermission(user.role, 'canViewAllAttendance');
  // CEO's 08:30 stand-up / WFH roster. Same audience as canViewAllAttendance (hr/ceo), so this is
  // always reachable in 'company' mode — the roster it needs (employeeOptions) is already loaded
  // below.
  const canMarkPresent = hasPermission(user.role, 'canMarkAttendance');
  // Deliberately not memoized: `monthBounds()` is two Intl.DateTimeFormat calls, cheap enough to
  // recompute on every render, and memoizing it with an empty dep array froze "today" at mount --
  // a tab left open past Bangkok midnight would keep clamping the stepper/picker to the previous
  // day's bounds.
  const { start: monthStart, today } = monthBounds();

  // Employees read their own month at a glance; everyone else answers "who is late today", so
  // they get a single day plus a stepper.
  const [selectedDate, setSelectedDate] = useState(today);
  const [employeeId, setEmployeeId] = useState('');
  // HR/CEO only. A ฝ่าย manager is already pinned to their own division server-side, so offering
  // them this control would imply a reach they don't have.
  const [divisionId, setDivisionId] = useState('');
  const [employeeOptions, setEmployeeOptions] = useState([]);
  const [unmapped, setUnmapped] = useState([]);
  const [unmappedOpen, setUnmappedOpen] = useState(false);
  const [expandedKey, setExpandedKey] = useState(null);
  const [punchesByKey, setPunchesByKey] = useState({});

  const [importOpen, setImportOpen] = useState(false);
  const [importing, setImporting] = useState(false);
  const [recalcOpen, setRecalcOpen] = useState(false);
  const [recalculating, setRecalculating] = useState(false);
  const [markPresentOpen, setMarkPresentOpen] = useState(false);
  const [markingPresent, setMarkingPresent] = useState(false);
  const [selectedFile, setSelectedFile] = useState(null);
  const [lastImport, setLastImport] = useState(null);
  const [devices, setDevices] = useState([]);
  const [importDeviceCode, setImportDeviceCode] = useState('');

  // The self-view fetch is deliberately NOT widened along with the picker's 3-month browsable
  // window: it has no date control to reach further back with (the whole filter bar is gated on
  // !isSelfView below), and AttendanceDailyService.MAX_RANGE_DAYS caps any query at <92 days --
  // a 3-month-back `from` combined with a late-month `today` can exceed that and 400. Kept at the
  // current month, matching both the copy below ("...ในเดือนนี้") and the API's hard cap.
  const range = isSelfView
    ? { from: bangkokMonthStartIso(), to: today }
    : { from: selectedDate, to: selectedDate };

  // Issue #422 B2: onto react-query so this screen participates in the shared cache (an OT
  // approval or a same-day recalculation elsewhere can now reach it via invalidation, and it gets
  // a 60s poll + window-focus refetch, same as PayrollPage below) instead of the plain
  // useState+useEffect loader that made it a dead end for both.
  const daysQuery = useQuery({
    queryKey: queryKeys.attendanceDailyScoped(range.from, range.to, employeeId, divisionId),
    queryFn: () => api.attendance.daily({
      from: range.from,
      to: range.to,
      ...(employeeId ? { employeeId } : {}),
      ...(divisionId ? { divisionId } : {}),
    }).then((response) => response.days || []),
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });
  const days = daysQuery.data ?? [];
  // `isLoading` (true only for the FIRST fetch of a given scope, i.e. no cached data yet) drives
  // the table's loading skeleton AND (adversarial-review fix, P2) the รีเฟรช button's disabled
  // state below -- `isFetching` (true for ANY fetch in flight, including the silent 60s poll) was
  // used for the button originally, but that made it flicker disabled on every background poll
  // with no user action involved. This does let a rapid multi-click on รีเฟรช fan out that many
  // real `GET /attendance/daily` requests (measured: 3 clicks -> 4 calls total, incl. the mount
  // fetch) -- TanStack Query v5's `refetch()` does NOT dedupe against other in-flight fetches for
  // the same key by default (`cancelRefetch: true`), and this `queryFn` does not wire the
  // `signal` argument through to `api.attendance.daily` to make cancellation possible either.
  // Left as-is: the outcome is harmless (React Query still serialises the resulting STATE updates
  // -- last response to resolve wins, same table either way), and wiring `signal` through would
  // touch the shared `apiRequest` helper (and its mock counterpart) for every endpoint, not just
  // this one. Worth doing if this page ever talks to a slow/rate-limited backend.
  const loading = daysQuery.isLoading;

  // Adversarial-review fix (P0): same infinite-loop hazard as PayrollPage's identical effect --
  // `showToast` (useToast()/App.jsx) is a plain, re-created-per-render function; with it in this
  // effect's deps, calling it re-renders App, which hands a new `showToast` identity back down,
  // which re-fires this effect for as long as `daysQuery.error` stays non-null. A ref holds the
  // latest callback without being a dependency, and the effect keys on the error's MESSAGE (a
  // primitive) instead of the Error object or the callback.
  const showToastRef = useRef(showToast);
  useEffect(() => {
    showToastRef.current = showToast;
  });
  const daysQueryErrorMessage = daysQuery.error?.message;
  useEffect(() => {
    if (daysQueryErrorMessage) showToastRef.current('error', daysQueryErrorMessage || 'โหลดข้อมูลเวลาทำงานไม่สำเร็จ');
  }, [daysQueryErrorMessage]);

  // A day's expanded punch-detail panel belongs to the scope the user is currently looking at
  // (date/employee/division) -- reset only when THAT changes, not on every background poll of the
  // same scope, or an open panel would silently collapse under someone reading it every 60s.
  useEffect(() => {
    setExpandedKey(null);
  }, [range.from, range.to, employeeId, divisionId]);

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

  const rowKey = useCallback((day) => `${day.employee_id}:${day.work_date}`, []);

  const toggleExpanded = useCallback(async (day) => {
    // A day with no scans has nothing to reveal; opening an empty panel and firing a request for it
    // is worse than not responding to the click.
    if (!day.punch_count) return;
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
      // The API returns punches oldest-first, which is the contract PunchDetail relies on to label
      // index 0 as เข้า and the last index as ออก (see AttendanceRepository.findPunches).
      setPunchesByKey((current) => ({ ...current, [key]: response.punches || [] }));
    } catch (error) {
      showToast('error', error.message || 'โหลดรายการสแกนไม่สำเร็จ');
    }
  }, [expandedKey, punchesByKey, rowKey, showToast]);

  function stepDay(deltaDays) {
    // Pure YYYY-MM-DD calendar arithmetic -- NOT `new Date(...).toISOString()`. That round trip
    // reads the UTC calendar day off a Bangkok-midnight instant, which is always one day behind
    // the Bangkok date intended (Bangkok midnight is 17:00 UTC the previous day), netting a 2-day
    // back-step and a stuck forward-step. See addDaysIso's comment in utils/format.js.
    const iso = addDaysIso(selectedDate, deltaDays);
    // Clamp to the browsable window in both directions — see monthBounds().
    if (iso < monthStart || iso > today) return;
    setSelectedDate(iso);
  }

  /**
   * Rolls existing punches up into daily rows.
   *
   * Needed because the roll-up only runs forward — on live punches and on import — so a database
   * with historical scans shows nothing until this is run once. Recalculation is idempotent and
   * skips rows HR has manually overridden, so it is safe to re-run after any .dat import or badge
   * mapping fix.
   *
   * The range starts well before the oldest expected punch rather than being derived from the UI's
   * current month, which is deliberately narrow.
   */
  async function runBackfill() {
    setRecalculating(true);
    try {
      const response = await api.attendance.recalculate({
        fromDate: BACKFILL_START,
        toDate: today,
      });
      const written = response?.recalculatedDays ?? 0;
      showToast('success', `คำนวณข้อมูลย้อนหลังเรียบร้อย — ${written.toLocaleString()} รายการ`);
      setRecalcOpen(false);
      await daysQuery.refetch();
    } catch (error) {
      showToast('error', error.message || 'คำนวณข้อมูลย้อนหลังไม่สำเร็จ');
    } finally {
      setRecalculating(false);
    }
  }

  /**
   * The CEO/HR stand-up roster: marks everyone checked present for the chosen date with no punches
   * (WFH). Resubmitting for the same date reconciles the roster — anyone left off is un-marked —
   * so this is idempotent and safe to run again if someone was ticked off by mistake.
   */
  async function submitMarkPresent({ date, employeeIds, notes }) {
    setMarkingPresent(true);
    try {
      const response = await api.attendance.markPresent({
        work_date: date,
        employee_ids: employeeIds,
        notes,
      });
      const marked = response?.marked_count ?? 0;
      const cleared = response?.cleared_count ?? 0;
      showToast(
        'success',
        `บันทึกเรียบร้อย — เข้างาน ${marked.toLocaleString()} คน`
          + (cleared ? ` · เอาออก ${cleared.toLocaleString()} คน` : ''),
      );
      setMarkPresentOpen(false);
      await daysQuery.refetch();
    } catch (error) {
      showToast('error', error.message || 'บันทึกไม่สำเร็จ');
    } finally {
      setMarkingPresent(false);
    }
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
      await daysQuery.refetch();
    } catch (error) {
      showToast('error', error.message || 'นำเข้าไฟล์ไม่สำเร็จ');
    } finally {
      setImporting(false);
    }
  }

  const columns = useMemo(() => {
    const baseColumns = isSelfView ? selfColumns : teamColumns;
    return [
      ...baseColumns,
      {
        key: 'scanDetail',
        header: 'รายการสแกน',
        render: (day) => {
          if (!day.punch_count) {
            return <span className="text-xs text-text-muted">-</span>;
          }
          const expanded = expandedKey === rowKey(day);
          const labelSubject = isSelfView
            ? formatShortDate(day.work_date)
            : `${day.employee_name || 'พนักงาน'} ${formatShortDate(day.work_date)}`;
          return (
            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={() => toggleExpanded(day)}
              aria-expanded={expanded}
              aria-label={`${expanded ? 'ซ่อน' : 'ดู'}รายการสแกน ${labelSubject}`}
            >
              {expanded ? 'ซ่อน' : 'ดู'}
            </Button>
          );
        },
      },
    ];
  }, [expandedKey, isSelfView, rowKey, toggleExpanded]);

  // Derived from the employees the caller may already see, so the filter can never offer a ฝ่าย
  // whose people they cannot read. Sorted by name; unnamed divisions are dropped rather than
  // rendered as a blank option.
  const divisionOptions = useMemo(() => {
    const byId = new Map();
    employeeOptions.forEach((option) => {
      if (option.division_id && option.division_name && !byId.has(option.division_id)) {
        byId.set(option.division_id, option.division_name);
      }
    });
    return [...byId.entries()]
      .map(([id, name]) => ({ id, name }))
      .sort((a, b) => a.name.localeCompare(b.name, 'th'));
  }, [employeeOptions]);

  // A manager already sees exactly one division, so the control would be a no-op for them.
  const showDivisionFilter = canSeeUnmapped && divisionOptions.length > 1;

  const employeePickerOptions = useMemo(
    () => (divisionId
      ? employeeOptions.filter((option) => String(option.division_id) === String(divisionId))
      : employeeOptions),
    [employeeOptions, divisionId],
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
          <>
            {canMarkPresent ? (
              <Button
                variant="primary"
                onClick={() => setMarkPresentOpen(true)}
                disabled={employeeOptions.length === 0}
              >
                <Icon name="plus" />
                ทำเครื่องหมายเข้างาน
              </Button>
            ) : null}
            <Button variant="secondary" onClick={() => daysQuery.refetch()} disabled={daysQuery.isLoading}>
              <Icon name="refresh" />
              รีเฟรช
            </Button>
          </>
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
          {importOpen ? (
            <div className="flex flex-wrap items-center gap-3 border-t border-border p-[14px]">
              <div className="min-w-0 grow">
                <strong className="block text-sm font-bold text-text">คำนวณข้อมูลย้อนหลัง</strong>
                <small className="text-xs text-text-muted">
                  สร้างสรุปเวลาเข้า-ออกรายวันจากรายการสแกนทั้งหมดที่มีอยู่
                  — ใช้เมื่อเปิดใช้งานครั้งแรก หรือหลังแก้ไขการแมปบัตร
                </small>
              </div>
              <Button
                variant="secondary"
                onClick={() => setRecalcOpen(true)}
                disabled={recalculating}
                className="max-[720px]:min-h-11 max-[720px]:w-full"
              >
                <Icon name="refresh" />
                {recalculating ? 'กำลังคำนวณ' : 'คำนวณข้อมูลย้อนหลัง'}
              </Button>
            </div>
          ) : null}
        </div>
      ) : null}

      <ConfirmDialog
        open={recalcOpen}
        title="คำนวณข้อมูลย้อนหลัง"
        message={
          'ระบบจะอ่านรายการสแกนทั้งหมดที่มีอยู่ แล้วสร้างสรุปเวลาเข้า-ออกรายวันใหม่ '
          + 'รายการที่ HR แก้ไขด้วยตนเองไว้จะไม่ถูกเขียนทับ และสามารถสั่งซ้ำได้'
        }
        confirmLabel="เริ่มคำนวณ"
        busy={recalculating}
        onConfirm={runBackfill}
        onCancel={() => setRecalcOpen(false)}
      />

      {markPresentOpen ? (
        <MarkPresentModal
          employees={employeeOptions}
          defaultDate={selectedDate}
          minDate={monthStart}
          maxDate={today}
          submitting={markingPresent}
          onClose={() => setMarkPresentOpen(false)}
          onSubmit={submitMarkPresent}
        />
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
          {showDivisionFilter ? (
            <label>
              ฝ่าย
              <select
                value={divisionId}
                onChange={(event) => {
                  setDivisionId(event.target.value);
                  // The chosen person may not belong to the new ฝ่าย; clearing avoids a filter pair
                  // that silently matches nothing.
                  setEmployeeId('');
                }}
              >
                <option value="">ทุกฝ่าย</option>
                {divisionOptions.map((division) => (
                  <option key={division.id} value={division.id}>{division.name}</option>
                ))}
              </select>
            </label>
          ) : null}
          {employeePickerOptions.length > 1 ? (
            <label>
              พนักงาน
              <select value={employeeId} onChange={(event) => setEmployeeId(event.target.value)}>
                <option value="">ทุกคน</option>
                {employeePickerOptions.map((option) => (
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
            ? 'grid-cols-[0.75fr_0.45fr_0.45fr_0.35fr_0.8fr_1.55fr_minmax(78px,0.7fr)] reflow-cards'
            : 'grid-cols-[1.2fr_0.45fr_0.45fr_0.35fr_0.8fr_1.55fr_minmax(78px,0.7fr)] max-[900px]:min-w-[840px] reflow-cards'
        }
        mobileCard={(day) => (
          <AttendanceDayCard
            day={day}
            isSelfView={isSelfView}
            expanded={expandedKey === rowKey(day)}
            onToggle={() => toggleExpanded(day)}
          />
        )}
        pageSize={isSelfView ? 31 : 50}
        searchable={!isSelfView}
        searchPlaceholder="ค้นหาพนักงาน / รหัส / ชื่อเล่น"
        loading={loading}
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

/**
 * The CEO/HR stand-up (WFH) roster picker. Everyone is pre-checked by default — most stand-up days
 * nobody is absent, so the caller just unchecks the no-shows instead of hunting for who attended —
 * and resubmitting for a date reconciles the roster server-side (see
 * `AttendanceDailyService#setWfhRoster`), so unchecking someone here and saving again removes an
 * earlier mark.
 */
function MarkPresentModal({ employees, defaultDate, minDate, maxDate, submitting, onClose, onSubmit }) {
  const [date, setDate] = useState(defaultDate);
  const [selected, setSelected] = useState(() => new Set(employees.map((employee) => employee.employee_id)));
  const [notes, setNotes] = useState('');

  function changeDate(nextDate) {
    setDate(nextDate);
    setSelected(new Set(employees.map((employee) => employee.employee_id)));
  }

  function toggle(employeeId) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(employeeId)) {
        next.delete(employeeId);
      } else {
        next.add(employeeId);
      }
      return next;
    });
  }

  function toggleAll() {
    setSelected((current) => (
      current.size === employees.length
        ? new Set()
        : new Set(employees.map((employee) => employee.employee_id))
    ));
  }

  return (
    <Modal
      title="ทำเครื่องหมายเข้างาน"
      subtitle="สำหรับ WFH / ประชุมยืนตอนเช้า — ทุกคนถูกเลือกไว้แล้ว เอาเครื่องหมายออกเฉพาะคนที่ไม่มา"
      onClose={submitting ? undefined : onClose}
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose} disabled={submitting}>
            ยกเลิก
          </Button>
          <Button
            type="button"
            variant="primary"
            disabled={submitting}
            onClick={() => onSubmit({ date, employeeIds: [...selected], notes: notes.trim() || undefined })}
          >
            {submitting ? 'กำลังบันทึก…' : `บันทึก (${selected.size} คน)`}
          </Button>
        </>
      )}
    >
      <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
        วันที่
        <input
          type="date"
          value={date}
          min={minDate}
          max={maxDate}
          onChange={(event) => changeDate(event.target.value || defaultDate)}
        />
      </label>

      <div className="mt-4 flex items-center justify-between gap-2">
        <span className="text-sm font-bold text-text-secondary">
          รายชื่อพนักงาน ({selected.size}/{employees.length} คน)
        </span>
        <button type="button" className="text-xs font-bold text-primary" onClick={toggleAll}>
          {selected.size === employees.length ? 'ไม่เลือกทั้งหมด' : 'เลือกทั้งหมด'}
        </button>
      </div>

      <ul className="mt-2 max-h-80 overflow-y-auto rounded-md border border-border">
        {employees.length === 0 ? (
          <li className="px-3 py-2 text-xs text-text-muted">ไม่พบรายชื่อพนักงาน</li>
        ) : employees.map((employee) => {
          const checked = selected.has(employee.employee_id);
          return (
            <li key={employee.employee_id} className="border-b border-border last:border-b-0">
              <label className="flex cursor-pointer items-center gap-3 px-3 py-2 text-sm">
                <input
                  type="checkbox"
                  className="h-4 w-4 shrink-0"
                  checked={checked}
                  onChange={() => toggle(employee.employee_id)}
                />
                <span className="min-w-0 flex-1 truncate">
                  {employee.employee_name}
                  <span className="ml-1.5 text-xs text-text-muted">
                    {[employee.nick_name, employee.employee_code].filter(Boolean).join(' · ')}
                  </span>
                </span>
              </label>
            </li>
          );
        })}
      </ul>

      <label className="mt-4 flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
        หมายเหตุ (ถ้ามี)
        <textarea
          className="min-h-16"
          value={notes}
          placeholder="เช่น ประชุมยืน 08:30"
          onChange={(event) => setNotes(event.target.value)}
        />
      </label>
    </Modal>
  );
}

/**
 * The raw scans behind a day — what settles "the system says 17:32, why?".
 *
 * <p>Someone may badge in and out several times a day (lunch, moving between sites). The first and
 * last scan are what became the เข้า/ออก columns, so they are labelled as such; everything between
 * is marked ระหว่างวัน. Without the labels this is just a list of times that silently repeats the
 * two values already shown on the row.
 */
export function PunchDetail({ punches }) {
  if (!punches) {
    return <span className="text-xs text-text-muted">กำลังโหลดรายการสแกน…</span>;
  }
  if (punches.length === 0) {
    return <span className="text-xs text-text-muted">ไม่มีรายการสแกนในวันนี้</span>;
  }
  return (
    <ul className="grid gap-1">
      {punches.map((punch, index) => {
        const role = punchRole(index, punches.length);
        return (
          <li key={punch.punch_id} className="flex flex-wrap items-baseline gap-2 text-xs text-text-muted">
            <span className={`w-[68px] shrink-0 ${role.muted ? 'text-text-muted' : 'text-text-secondary'}`}>
              {role.label}
            </span>
            <strong className="text-text">{formatBangkokTime(punch.punch_time)}</strong>
            <span>{punch.site_code || '-'}</span>
            <span>{punch.device_name || punch.device_code || '-'}</span>
          </li>
        );
      })}
    </ul>
  );
}

/**
 * Which of the day's scans a punch is. A single scan is deliberately left unlabelled — the day row
 * already says which side is missing, and calling a lone punch "เข้า" would assert a direction the
 * data does not carry.
 */
export function punchRole(index, total) {
  if (total === 1) return { label: '', muted: true };
  if (index === 0) return { label: 'เข้า', muted: false };
  if (index === total - 1) return { label: 'ออก', muted: false };
  return { label: 'ระหว่างวัน', muted: true };
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

function AttendanceDayCard({ day, isSelfView, expanded, onToggle }) {
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
        <span className="flex flex-wrap items-center gap-1.5">
          <StatusCell day={day} />
          <MidDayPunchChip day={day} />
          {attendanceSourceLabel(day) ? (
            <span className="text-2xs text-text-muted">· {attendanceSourceLabel(day)}</span>
          ) : null}
        </span>
      )}
      {day.punch_count ? (
        <Button
          type="button"
          variant="secondary"
          size="sm"
          className="mt-1 w-full"
          onClick={onToggle}
          aria-expanded={expanded}
          aria-label={`${expanded ? 'ซ่อน' : 'ดู'}รายการสแกน ${
            isSelfView ? formatShortDate(day.work_date) : `${day.employee_name || 'พนักงาน'} ${formatShortDate(day.work_date)}`
          }`}
        >
          {expanded ? 'ซ่อนรายการสแกน' : 'ดูรายการสแกน'}
        </Button>
      ) : null}
    </>
  );
}

/**
 * Marks days that hold more than the two scans already shown in the เข้า/ออก columns, so the ones
 * worth opening are findable without clicking rows at random. An ordinary in/out day stays clean —
 * which is what gives the chip its meaning.
 */
function MidDayPunchChip({ day }) {
  if (!day.punch_count || day.punch_count <= 2) return null;
  return (
    <span className="inline-flex items-center gap-1 rounded-sm bg-surface-subtle px-1.5 py-0.5 text-2xs text-text-muted">
      <Icon name="chevronDown" size={11} />
      {day.punch_count} ครั้ง
    </span>
  );
}

const statusColumn = {
  key: 'status',
  header: 'สถานะ',
  render: (day) => (
    <span className="flex flex-wrap items-center gap-1.5">
      <StatusCell day={day} />
      <MidDayPunchChip day={day} />
    </span>
  ),
};

// Where the day's attendance came from: the scanner's site (Showroom / Warehouse) for punched days,
// or WFH for a CEO/HR "marked present" day with no scans. Days with no record show "-".
const sourceColumn = {
  key: 'source',
  header: 'สถานที่',
  sortable: true,
  sortAccessor: (day) => attendanceSourceLabel(day) || '',
  render: (day) => {
    const source = attendanceSourceLabel(day);
    return source
      ? <span className="text-text-secondary">{source}</span>
      : <span className="text-text-muted">-</span>;
  },
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
  sourceColumn,
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
  sourceColumn,
  statusColumn,
];
