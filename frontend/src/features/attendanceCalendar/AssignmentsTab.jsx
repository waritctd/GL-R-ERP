import { useState } from 'react';
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
import { formatShortDate } from '../../utils/format.js';
import { AssignmentFormModal } from './AssignmentFormModal.jsx';
import { todayIso } from './dateHelpers.js';
import { SCOPE_TYPES, scopeTypeLabel } from './scopeTypes.js';
import { confirmDialogMessageClass } from '../../components/common/ConfirmDialog.jsx';

const GRID = 'grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)_minmax(0,0.9fr)_minmax(0,0.9fr)_minmax(0,0.8fr)] max-[1040px]:min-w-[760px] reflow-cards';

/**
 * การมอบหมาย tab: list/create/end for hr.work_schedule_assignment.
 *
 * The create response (`WorkScheduleAssignmentDto`) never echoes which predecessor rows the write
 * auto-closed — that detail is written only into the audit log
 * (`WorkScheduleAssignmentAdminService#create`'s `auditService.record(..., Map.of("assignment",
 * created, "closedPredecessorAssignmentIds", closedPredecessorIds))`), not returned to this caller.
 * So this tab derives it itself: it snapshots the assignment list immediately before the POST, then
 * re-lists immediately after, and reports every id present in BOTH snapshots whose `effectiveTo`
 * changed. `WorkScheduleAssignmentRepository#closeOverlappingPredecessors` closes exactly the rows
 * for this same scope that were still live at the new `effectiveFrom` — nothing else can change an
 * existing row's `effectiveTo` in the same request — so this diff is an accurate read of what the
 * server actually did, not a guess at its business rule.
 */
export function AssignmentsTab({ showToast }) {
  const queryClient = useQueryClient();

  const assignmentsQuery = useQuery({
    queryKey: queryKeys.workScheduleAssignments(),
    queryFn: () => api.workScheduleAssignments.list().then((response) => response.assignments || []),
  });
  const rows = assignmentsQuery.data ?? [];

  const schedulesQuery = useQuery({
    queryKey: queryKeys.workSchedules(),
    queryFn: () => api.workSchedules.list().then((response) => response.schedules || []),
  });

  // --- create ---
  const [createOpen, setCreateOpen] = useState(false);
  const [createError, setCreateError] = useState('');
  const [lastAutoClosed, setLastAutoClosed] = useState(null); // { count, ids } | null — dismissible

  const createMutation = useMutation({
    mutationFn: async (payload) => {
      const beforeRows = queryClient.getQueryData(queryKeys.workScheduleAssignments())
        ?? (await api.workScheduleAssignments.list().then((response) => response.assignments || []));
      const created = await api.workScheduleAssignments.create(payload);
      const afterRows = await api.workScheduleAssignments.list().then((response) => response.assignments || []);
      const beforeById = new Map(beforeRows.map((row) => [row.assignmentId, row]));
      const closedPredecessors = afterRows.filter((row) => {
        if (row.assignmentId === created.assignmentId) return false;
        const prior = beforeById.get(row.assignmentId);
        return !!prior && String(prior.effectiveTo ?? '') !== String(row.effectiveTo ?? '');
      });
      return { created, afterRows, closedPredecessors };
    },
    onSuccess: ({ afterRows, closedPredecessors }) => {
      queryClient.setQueryData(queryKeys.workScheduleAssignments(), afterRows);
      setCreateOpen(false);
      setCreateError('');
      if (closedPredecessors.length > 0) {
        // Never silent: a user who just ended three assignments they didn't explicitly ask to end
        // needs to see that plainly, not infer it later from a gap in the timeline.
        setLastAutoClosed({
          count: closedPredecessors.length,
          ids: closedPredecessors.map((row) => row.assignmentId),
        });
        showToast?.('success', `สร้างรายการมอบหมายสำเร็จ และปิดการมอบหมายเดิมโดยอัตโนมัติ ${closedPredecessors.length} รายการ`);
      } else {
        setLastAutoClosed(null);
        showToast?.('success', 'สร้างรายการมอบหมายสำเร็จ');
      }
    },
    onError: (error) => {
      // Both WorkScheduleAssignmentAdminService#create failure modes this tab must explain land
      // here: 400 back-dating rejection, 409 conflicting successor. Either way the modal stays
      // open with the exact server message so the dates are fixable in place.
      setCreateError(error?.message || 'สร้างรายการมอบหมายไม่สำเร็จ');
    },
  });

  // --- end ---
  const [endTarget, setEndTarget] = useState(null);
  const [endDate, setEndDate] = useState(todayIso());
  const [endError, setEndError] = useState('');

  function openEnd(row) {
    setEndTarget(row);
    setEndDate(todayIso());
    setEndError('');
  }

  const endMutation = useMutation({
    mutationFn: ({ id, effectiveTo }) => api.workScheduleAssignments.end(id, { effectiveTo }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.workScheduleAssignments() });
      showToast?.('success', 'สิ้นสุดการมอบหมายแล้ว');
      setEndTarget(null);
      setEndError('');
    },
    onError: (error) => {
      // Back-dating the END is rejected the same way as create — keep the dialog open with the
      // server's own message rather than closing on failure.
      setEndError(error?.message || 'สิ้นสุดการมอบหมายไม่สำเร็จ');
    },
  });

  const columns = [
    {
      key: 'scope',
      header: 'ขอบเขต',
      sortable: true,
      sortAccessor: (row) => `${row.scopeType} ${row.scopeId}`,
      render: (row) => (
        <span>
          <strong className="block">{scopeTypeLabel(row.scopeType)}</strong>
          <small className="text-text-muted">รหัส {row.scopeId}</small>
        </span>
      ),
    },
    {
      key: 'schedule',
      header: 'ตารางเวลาทำงาน',
      render: (row) => <span className="font-mono font-bold">{row.scheduleCode}</span>,
    },
    {
      key: 'effectiveFrom',
      header: 'เริ่มมีผล',
      sortable: true,
      sortAccessor: (row) => row.effectiveFrom,
      render: (row) => <span className="font-mono">{formatShortDate(row.effectiveFrom)}</span>,
    },
    {
      key: 'effectiveTo',
      header: 'สิ้นสุด',
      render: (row) => (row.effectiveTo
        ? <span className="font-mono">{formatShortDate(row.effectiveTo)}</span>
        : <span className="text-text-muted">— (ยังมีผล)</span>),
    },
    {
      key: 'actions',
      header: 'การดำเนินการ',
      render: (row) => (
        <OverflowMenu
          label={`การดำเนินการสำหรับการมอบหมายรหัส ${row.assignmentId}`}
          items={[
            { key: 'end', label: 'สิ้นสุดการมอบหมาย', icon: 'close', tone: 'danger', onSelect: () => openEnd(row) },
          ]}
        />
      ),
    },
  ];

  return (
    <div className="grid gap-4">
      <StatePanel
        state="denied"
        compact
        icon="info"
        title="ลำดับความสำคัญเมื่อขอบเขตซ้อนกัน"
        description={`${SCOPE_TYPES.map((entry) => entry.label).join(' > ')} — เมื่อพนักงานคนหนึ่งเข้าเงื่อนไขได้หลายรายการพร้อมกัน (เช่น ทั้งรายคนและทั้งแผนก) ระบบจะใช้รายการที่แคบที่สุดเสมอ นี่คือคำตอบเมื่อพบว่า "ทำไมพนักงานคนนี้ถึงใช้ตารางเวลาทำงานผิด"`}
      />

      {lastAutoClosed ? (
        <StatePanel
          state="denied"
          compact
          icon="triangleAlert"
          title={`ปิดการมอบหมายเดิม ${lastAutoClosed.count} รายการโดยอัตโนมัติ`}
          description={`รหัสรายการที่ถูกปิด: ${lastAutoClosed.ids.join(', ')} — ระบบปิดรายการเดิมที่ยังมีผลอยู่สำหรับขอบเขตเดียวกัน (สิ้นสุดวันก่อนวันเริ่มมีผลของรายการใหม่) เพื่อไม่ให้ซ้อนทับกัน`}
          action={(
            <button type="button" className="secondary-button" onClick={() => setLastAutoClosed(null)}>
              รับทราบ
            </button>
          )}
        />
      ) : null}

      <div className="flex justify-end">
        <Button type="button" onClick={() => { setCreateError(''); setCreateOpen(true); }}>
          <Icon name="plus" />
          มอบหมายตารางเวลาทำงาน
        </Button>
      </div>

      <DataTable
        columns={columns}
        rows={rows}
        getRowKey={(row) => row.assignmentId}
        gridClassName={GRID}
        loading={assignmentsQuery.isLoading}
        error={assignmentsQuery.error}
        onRetry={() => assignmentsQuery.refetch()}
        emptyState={{ icon: 'calendar', title: 'ยังไม่มีการมอบหมายตารางเวลาทำงาน' }}
      />

      {createOpen ? (
        <AssignmentFormModal
          schedules={schedulesQuery.data ?? []}
          busy={createMutation.isPending}
          formError={createError}
          onClose={() => { if (!createMutation.isPending) setCreateOpen(false); }}
          onSubmit={(values) => createMutation.mutate(values)}
        />
      ) : null}

      <ConfirmDialog
        open={!!endTarget}
        title="สิ้นสุดการมอบหมายตารางเวลาทำงาน"
        tone="danger"
        confirmLabel="สิ้นสุด"
        busy={endMutation.isPending}
        onConfirm={() => endMutation.mutate({ id: endTarget.assignmentId, effectiveTo: endDate })}
        onCancel={() => { setEndTarget(null); setEndError(''); }}
        message={endTarget ? (
          <div className="grid gap-3">
            <p className={confirmDialogMessageClass}>
              สิ้นสุดการมอบหมาย {scopeTypeLabel(endTarget.scopeType)} รหัส {endTarget.scopeId} ({endTarget.scheduleCode})
              ที่เริ่มมีผลตั้งแต่ {formatShortDate(endTarget.effectiveFrom)}
            </p>
            <FormField label="วันที่มีผลสิ้นสุด (รวมวันนี้ — inclusive)" htmlFor="end-assignment-date">
              <input
                id="end-assignment-date"
                type="date"
                min={todayIso()}
                value={endDate}
                onChange={(event) => setEndDate(event.target.value)}
              />
            </FormField>
            {endError ? <p role="alert" className="m-0 text-xs font-bold text-danger">{endError}</p> : null}
          </div>
        ) : ''}
      />
    </div>
  );
}
