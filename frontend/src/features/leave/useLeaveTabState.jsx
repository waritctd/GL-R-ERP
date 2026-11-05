import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';

/** Draft vs applied filter state for LeaveFilterBar; `initialFilters` is the "nothing typed" shape. */
export function useLeaveFilters(initialFilters) {
  const [filters, setFilters] = useState(initialFilters);
  const [appliedFilters, setAppliedFilters] = useState(initialFilters);
  const hasCustomFilters = JSON.stringify(appliedFilters) !== JSON.stringify(initialFilters);

  function updateFilter(field, value) {
    setFilters((current) => ({ ...current, [field]: value }));
  }

  function clearFilters() {
    setFilters(initialFilters);
    setAppliedFilters(initialFilters);
  }

  function submitFilters(event) {
    event.preventDefault();
    setAppliedFilters(filters);
  }

  return { filters, appliedFilters, hasCustomFilters, updateFilter, clearFilters, submitFilters };
}

/** Cancel-a-request flow: call `requestCancel(id)` and render `cancelDialog`. */
export function useCancelLeave(showToast) {
  const queryClient = useQueryClient();
  const [confirmState, setConfirmState] = useState(null);

  const cancelMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.leave.cancel(id, { reviewerNote: reviewerNote?.trim() || null }).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'ยกเลิกคำขอลาแล้ว');
      setConfirmState(null);
      // Invalidate the ['leave'] PREFIX: every other filter combination would otherwise stay stale.
      queryClient.invalidateQueries({ queryKey: ['leave'] });
      queryClient.invalidateQueries({ queryKey: ['payroll'] });
    },
    onError: (error) => showToast('error', error.message || 'ยกเลิกวันลาไม่สำเร็จ'),
  });

  const cancelDialog = (
    <ConfirmDialog
      open={confirmState?.kind === 'cancel'}
      title="ยกเลิกคำขอลา"
      message='ยืนยันการยกเลิกคำขอลานี้? สถานะจะเปลี่ยนเป็น "ยกเลิกแล้ว" ถาวร'
      confirmLabel="ยกเลิกคำขอ"
      tone="danger"
      busy={cancelMutation.isPending}
      requireReason
      optionalReason
      reasonLabel="หมายเหตุการยกเลิก (ถ้ามี)"
      onConfirm={(reason) => cancelMutation.mutate({ id: confirmState.id, reviewerNote: reason })}
      onCancel={() => setConfirmState(null)}
    />
  );

  return { requestCancel: (id) => setConfirmState({ kind: 'cancel', id }), cancelDialog };
}

/** The three counts both tabs' stat rows start from. */
export function summarizeLeaveRequests(requests) {
  const submitted = requests.filter((request) => request.status === 'SUBMITTED').length;
  const approved = requests.filter((request) => request.status === 'APPROVED');
  const approvedDays = approved.reduce((sum, request) => sum + Number(request.totalDays || 0), 0);
  return { submitted, approved: approved.length, approvedDays };
}
