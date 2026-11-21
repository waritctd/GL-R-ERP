import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { roundTitle, stepLabel } from './shipmentRounds.js';
import { StepUndoSnackbar } from './StepUndoSnackbar.jsx';

// The server's 30 s window (ShipmentRoundService / ImportRequestService.STEP_UNDO_WINDOW_SECONDS); the response's own
// undoUntil closes the snackbar, this is only the fallback.
const FALLBACK_WINDOW_MS = 30_000;

/** Every cache a round advance (or its undo) can move: the queue, the round, and each deal whose IR step changed. */
export function invalidateAfterRoundChange(queryClient, round) {
  queryClient.invalidateQueries({ queryKey: queryKeys.shipmentRounds() });
  if (round?.id != null) queryClient.invalidateQueries({ queryKey: queryKeys.shipmentRound(round.id) });
  (round?.lots ?? []).forEach((lot) => {
    queryClient.invalidateQueries({ queryKey: queryKeys.storedImportRequests(lot.ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.importDeal(lot.ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(lot.ticketId) });
  });
  queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
  queryClient.invalidateQueries({ queryKey: queryKeys.dashboardSummary() });
  queryClient.invalidateQueries({ queryKey: queryKeys.notifications() });
}

/** The 30-second undo of a round advance. `offer(round)` after a successful advance shows the snackbar; `snackbar`
 * is the element to render. */
export function useRoundUndo({ showToast }) {
  const queryClient = useQueryClient();
  const [undo, setUndo] = useState(null);

  useEffect(() => {
    if (!undo) return undefined;
    const until = undo.undoUntil ? Date.parse(undo.undoUntil) : NaN;
    const ms = Number.isFinite(until) ? Math.max(0, until - Date.now()) : FALLBACK_WINDOW_MS;
    const timer = setTimeout(() => setUndo((cur) => (cur?.key === undo.key ? null : cur)), ms);
    return () => clearTimeout(timer);
  }, [undo]);

  const undoMutation = useMutation({
    mutationFn: ({ round }) => api.shipmentRounds.undo(round.id, { expectedStep: round.importStep }),
    onSuccess: (_d, { round }) => {
      invalidateAfterRoundChange(queryClient, round);
      setUndo(null);
      showToast?.('success', 'เลิกทำการเลื่อนขั้นตอนแล้ว');
    },
    onError: (e) => {
      setUndo(null);
      showToast?.('error', e?.message ?? 'เลิกทำไม่สำเร็จ');
    },
  });

  return {
    offer: (round) => {
      // RECEIVED is never undoable (it may have rolled the deal up): a plain confirmation, no snackbar.
      if (round.importStep === 'RECEIVED') {
        showToast?.('success', 'อัปเดตสถานะรอบขนส่งแล้ว');
        return;
      }
      setUndo({
      round, key: `${round.id}:${round.importStep}:${Date.now()}`, undoUntil: round.undoUntil ?? null,
      label: stepLabel(round.importStep), title: roundTitle(round),
      });
    },
    snackbar: undo ? (
      <StepUndoSnackbar undo={undo} busy={undoMutation.isPending} onUndo={() => undoMutation.mutate({ round: undo.round })} />
    ) : null,
  };
}
