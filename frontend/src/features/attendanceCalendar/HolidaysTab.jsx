import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { OverflowMenu } from '../../components/common/OverflowMenu.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatShortDate } from '../../utils/format.js';
import { HolidayFormModal } from './HolidayFormModal.jsx';
import { parseCooldownSeconds, summarizeFetchOutcomes } from './holidayFetch.js';

const GRID = 'grid-cols-[minmax(0,0.9fr)_minmax(0,2.1fr)_minmax(0,1fr)_minmax(0,0.8fr)] nav-drawer:min-w-[620px] reflow-cards';

// Default cooldown fallback if a 429 message somehow doesn't carry a parseable seconds figure
// (message shape changed server-side) — matches BotHolidayFetchService.MANUAL_FETCH_COOLDOWN
// (10 minutes) exactly, so the countdown is still roughly honest even in that edge case.
const DEFAULT_COOLDOWN_SECONDS = 600;

function yearOptions(centerYear) {
  return [centerYear - 1, centerYear, centerYear + 1, centerYear + 2];
}

function isDuplicateDateError(error) {
  return error?.status === 409;
}

/**
 * วันหยุด tab: `GET /api/holidays?from&to` in a DataTable, plus create/edit/delete and the manual
 * BOT-fetch trigger. See HolidaysTab's siblings' own comments for the two tabs this one is grouped
 * with; this file owns every "the error state IS the feature" case called out in this branch's
 * brief — the 429 cooldown, the honest zero-row fetch report, the 409 duplicate-date offer, and the
 * BANK->COMPANY reclassification confirm.
 */
export function HolidaysTab({ showToast }) {
  const queryClient = useQueryClient();
  const currentYear = new Date().getFullYear();
  const [year, setYear] = useState(currentYear);
  const from = `${year}-01-01`;
  const to = `${year}-12-31`;

  const holidaysQuery = useQuery({
    queryKey: queryKeys.holidays(from, to),
    queryFn: () => api.holidays.list({ from, to }).then((response) => response.holidays || []),
  });
  const rows = holidaysQuery.data ?? [];

  function invalidateHolidays() {
    // Every year's list is its own cache entry (queryKeys.holidays(from,to)); invalidate the whole
    // ['holidays', ...] prefix so switching the year selector afterwards never shows stale data.
    queryClient.invalidateQueries({ queryKey: ['holidays'] });
  }

  // --- create / edit ---
  const [formTarget, setFormTarget] = useState(null); // null | { mode: 'create' } | { mode: 'edit', row }
  const [formError, setFormError] = useState('');
  const [reclassifyConfirm, setReclassifyConfirm] = useState(null); // { row, nameTh } | null

  const createMutation = useMutation({
    mutationFn: (payload) => api.holidays.create(payload),
    onSuccess: () => {
      invalidateHolidays();
      showToast?.('success', 'เพิ่มวันหยุดแล้ว');
      setFormTarget(null);
      setFormError('');
    },
    onError: (error) => {
      // 409: the exact date already has a row. HolidayAdminService's own message already says
      // "กรุณาแก้ไขรายการเดิม" — surfaced verbatim, inline, field-shaped (not a toast), plus a
      // shortcut into edit mode for that row when it's already in the loaded list.
      setFormError(error.message || 'เพิ่มวันหยุดไม่สำเร็จ');
    },
  });

  const updateMutation = useMutation({
    mutationFn: ({ date, nameTh }) => api.holidays.update(date, { nameTh }),
    onSuccess: () => {
      invalidateHolidays();
      showToast?.('success', 'บันทึกการแก้ไขวันหยุดแล้ว');
      setFormTarget(null);
      setFormError('');
      setReclassifyConfirm(null);
    },
    onError: (error) => {
      setFormError(error.message || 'แก้ไขวันหยุดไม่สำเร็จ');
      setReclassifyConfirm(null);
    },
  });

  function handleFormSubmit(values) {
    setFormError('');
    if (formTarget?.mode === 'create') {
      createMutation.mutate(values);
      return;
    }
    const row = formTarget?.row;
    if (!row) return;
    if (row.source === 'BANK') {
      // PUT /api/holidays/{date} always forces source='COMPANY' — HolidayController's own javadoc
      // is explicit that this is a real, permanent consequence of editing a BANK row (the next BOT
      // fetch will never touch this date again). Confirm before firing the mutation rather than
      // just doing it.
      setReclassifyConfirm({ date: row.holidayDate, nameTh: values.nameTh });
      return;
    }
    updateMutation.mutate({ date: row.holidayDate, nameTh: values.nameTh });
  }

  function openCreate() {
    setFormError('');
    setFormTarget({ mode: 'create' });
  }
  function openEdit(row) {
    setFormError('');
    setFormTarget({ mode: 'edit', row });
  }
  function closeForm() {
    if (createMutation.isPending || updateMutation.isPending) return;
    setFormTarget(null);
    setFormError('');
  }

  // --- delete ---
  const [deleteTarget, setDeleteTarget] = useState(null);
  const deleteMutation = useMutation({
    mutationFn: (date) => api.holidays.remove(date),
    onSuccess: () => {
      invalidateHolidays();
      showToast?.('success', 'ลบวันหยุดแล้ว');
      setDeleteTarget(null);
    },
    onError: (error) => {
      showToast?.('error', error.message || 'ลบวันหยุดไม่สำเร็จ');
      setDeleteTarget(null);
    },
  });

  // --- BOT fetch: cooldown (429), and the honest zero-row report ---
  const [cooldownUntil, setCooldownUntil] = useState(null);
  const [cooldownMessage, setCooldownMessage] = useState('');
  const [nowTick, setNowTick] = useState(() => Date.now());
  const [fetchResult, setFetchResult] = useState(null); // summarizeFetchOutcomes() result, or null

  useEffect(() => {
    if (!cooldownUntil) return undefined;
    const timer = window.setInterval(() => setNowTick(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [cooldownUntil]);

  const cooldownSecondsLeft = cooldownUntil ? Math.max(0, Math.ceil((cooldownUntil - nowTick) / 1000)) : 0;
  useEffect(() => {
    if (cooldownUntil && cooldownSecondsLeft <= 0) setCooldownUntil(null);
  }, [cooldownSecondsLeft, cooldownUntil]);

  const fetchMutation = useMutation({
    mutationFn: () => api.holidays.fetch(),
    onSuccess: (data) => {
      const summary = summarizeFetchOutcomes(data?.outcomes);
      setFetchResult(summary);
      if (summary.anySuccess) {
        invalidateHolidays();
        showToast?.('success', 'ดึงข้อมูลวันหยุดจากธนาคารแห่งประเทศไทยสำเร็จ — ดูรายละเอียดด้านล่าง');
      } else {
        // The exact production failure mode this branch's brief names: HTTP 200, but nothing was
        // actually imported (BOT token unset, or BOT has nothing published yet). Never a "success"
        // toast for this — an info nudge pointing at the honest, persistent detail panel below.
        showToast?.('info', 'ดึงข้อมูลจาก ธปท. เสร็จแล้ว แต่ไม่มีวันหยุดใหม่ที่นำเข้าได้ — ดูรายละเอียดด้านล่าง');
      }
    },
    onError: (error) => {
      if (error?.status === 429) {
        const seconds = parseCooldownSeconds(error.message) ?? DEFAULT_COOLDOWN_SECONDS;
        const now = Date.now();
        setCooldownMessage(error.message || '');
        setCooldownUntil(now + seconds * 1000);
        // nowTick's initial value is only set once, at mount -- by the time this error lands it
        // can be arbitrarily stale (however long the request took), which would inflate the very
        // first countdown render (cooldownUntil - a stale nowTick > the true remaining ms). Reset
        // it here so the first render is accurate instead of waiting a tick for the interval below
        // to correct it.
        setNowTick(now);
        return;
      }
      showToast?.('error', error?.message || 'ดึงข้อมูลวันหยุดจากธนาคารแห่งประเทศไทยไม่สำเร็จ');
    },
  });

  const columns = [
    {
      key: 'holidayDate',
      header: 'วันที่',
      sortable: true,
      sortAccessor: (row) => row.holidayDate,
      render: (row) => <span className="font-mono">{formatShortDate(row.holidayDate)}</span>,
    },
    { key: 'nameTh', header: 'ชื่อวันหยุด', sortable: true, searchAccessor: (row) => row.nameTh, render: (row) => row.nameTh },
    {
      key: 'source',
      header: 'แหล่งที่มา',
      render: (row) => (
        <StatusBadge tone={row.source === 'BANK' ? 'info' : 'neutral'}>
          {row.source === 'BANK' ? 'ธปท. (BANK)' : 'บริษัท (COMPANY)'}
        </StatusBadge>
      ),
    },
    {
      key: 'actions',
      header: 'การดำเนินการ',
      render: (row) => (
        <OverflowMenu
          label={`การดำเนินการสำหรับวันหยุด ${row.holidayDate}`}
          items={[
            { key: 'edit', label: 'แก้ไข', icon: 'pencil', onSelect: () => openEdit(row) },
            { key: 'delete', label: 'ลบ', icon: 'close', tone: 'danger', onSelect: () => setDeleteTarget(row) },
          ]}
        />
      ),
    },
  ];

  const conflictRow = isDuplicateDateError(createMutation.error) && formTarget?.mode === 'create'
    ? rows.find((row) => row.holidayDate === formTarget.pendingDate)
    : null;

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <FormField label="ปี" htmlFor="holiday-year">
          <select id="holiday-year" value={year} onChange={(event) => setYear(Number(event.target.value))}>
            {yearOptions(currentYear).map((option) => (
              <option key={option} value={option}>{option + 543}</option>
            ))}
          </select>
        </FormField>
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            variant="secondary"
            onClick={() => fetchMutation.mutate()}
            disabled={fetchMutation.isPending || cooldownSecondsLeft > 0}
          >
            <Icon name="refresh" />
            {fetchMutation.isPending ? 'กำลังดึงข้อมูล...' : 'ดึงข้อมูลวันหยุดจาก ธปท.'}
          </Button>
          <Button type="button" onClick={openCreate}>
            <Icon name="plus" />
            เพิ่มวันหยุด
          </Button>
        </div>
      </div>

      {cooldownSecondsLeft > 0 ? (
        <StatePanel
          state="denied"
          icon="clock"
          compact
          title={`รอสักครู่ก่อนลองดึงข้อมูลจาก ธปท. อีกครั้ง (เหลืออีกประมาณ ${cooldownSecondsLeft} วินาที)`}
          description={cooldownMessage}
        />
      ) : null}

      {fetchResult ? (
        <StatePanel
          state={fetchResult.anySuccess ? 'empty' : 'denied'}
          icon={fetchResult.anySuccess ? 'check' : 'triangleAlert'}
          compact
          title={fetchResult.anySuccess ? 'ผลการดึงข้อมูลจาก ธปท.' : 'ดึงข้อมูลจาก ธปท. แล้ว แต่ไม่มีวันหยุดใหม่'}
        >
          <ul className="m-0 grid list-none gap-1 p-0 text-left text-xs">
            {fetchResult.rows.length === 0 ? (
              <li>ไม่มีผลลัพธ์จากการดึงข้อมูล (ไม่มีปีใดถูกเรียกเลย) — ตรวจสอบการตั้งค่า BOT_HOLIDAY_API_TOKEN</li>
            ) : fetchResult.rows.map((row) => (
              <li key={row.year}>
                ปี {row.year + 543}:{' '}
                {row.ok
                  ? `นำเข้า ${row.holidayCount} วันหยุด`
                  : 'ไม่มีวันหยุดใหม่ (ยังไม่ได้ตั้งค่า BOT token หรือ ธปท. ยังไม่ประกาศปีนี้)'}
              </li>
            ))}
          </ul>
        </StatePanel>
      ) : null}

      <DataTable
        columns={columns}
        rows={rows}
        getRowKey={(row) => row.holidayDate}
        gridClassName={GRID}
        searchable
        searchPlaceholder="ค้นหาชื่อวันหยุด..."
        loading={holidaysQuery.isLoading}
        error={holidaysQuery.error}
        onRetry={() => holidaysQuery.refetch()}
        emptyState={{ icon: 'calendar', title: `ยังไม่มีข้อมูลวันหยุดของปี ${year + 543}` }}
      />

      {formTarget ? (
        <HolidayFormModal
          // Keyed on mode+row-date so switching from "create" to "edit" (the 409 conflict's
          // "แก้ไขรายการเดิม" shortcut) forces a remount -- HolidayFormModal's nameTh/holidayDate
          // are plain useState seeded from initialNameTh/initialDate, which only run on first
          // mount; without a key change here the modal would keep showing the just-typed
          // duplicate name instead of the existing row's real one. Deliberately NOT keyed on
          // `pendingDate`: that field only exists to remember which date a 409 was about (for the
          // conflictRow lookup below), and it changes on every submit attempt -- keying on it would
          // remount the form (silently wiping the name the user just typed) on every failed submit,
          // not only the one 409 case this key exists to handle.
          key={formTarget.mode === 'edit' ? `edit:${formTarget.row.holidayDate}` : 'create'}
          mode={formTarget.mode}
          initialDate={formTarget.mode === 'edit' ? formTarget.row.holidayDate : undefined}
          initialNameTh={formTarget.mode === 'edit' ? formTarget.row.nameTh : ''}
          source={formTarget.mode === 'edit' ? formTarget.row.source : undefined}
          busy={createMutation.isPending || updateMutation.isPending}
          formError={formError}
          onUseExisting={conflictRow ? () => {
            setFormTarget({ mode: 'edit', row: conflictRow });
            setFormError('');
          } : null}
          onClose={closeForm}
          onSubmit={(values) => {
            if (formTarget.mode === 'create') {
              setFormTarget({ ...formTarget, pendingDate: values.holidayDate });
            }
            handleFormSubmit(values);
          }}
        />
      ) : null}

      <ConfirmDialog
        open={!!reclassifyConfirm}
        title="แปลงวันหยุดจาก ธปท. เป็นวันหยุดบริษัท"
        tone="danger"
        confirmLabel="ยืนยันแก้ไข"
        busy={updateMutation.isPending}
        message={reclassifyConfirm ? (
          <>
            วันที่ {formatShortDate(reclassifyConfirm.date)} ปัจจุบันมาจากข้อมูลธนาคารแห่งประเทศไทย (BANK)
            การแก้ไขชื่อนี้จะเปลี่ยนแหล่งที่มาเป็น &ldquo;วันหยุดบริษัท (COMPANY)&rdquo; อย่างถาวร —
            การดึงข้อมูลจาก ธปท. ครั้งต่อไปจะไม่เขียนทับหรืออัปเดตวันที่นี้อีก
          </>
        ) : ''}
        onConfirm={() => updateMutation.mutate({ date: reclassifyConfirm.date, nameTh: reclassifyConfirm.nameTh })}
        onCancel={() => setReclassifyConfirm(null)}
      />

      <ConfirmDialog
        open={!!deleteTarget}
        title="ลบวันหยุด"
        tone="danger"
        confirmLabel="ลบ"
        busy={deleteMutation.isPending}
        message={deleteTarget ? `ลบวันหยุด "${deleteTarget.nameTh}" (${formatShortDate(deleteTarget.holidayDate)}) ใช่หรือไม่ — จะมีผลต่อการนับวันทำงาน/วันลา และสถานะการเข้างานของวันนี้ทันที` : ''}
        onConfirm={() => deleteMutation.mutate(deleteTarget.holidayDate)}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}
