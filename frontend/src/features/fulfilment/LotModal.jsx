import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { importStepIndex } from '../importProgress/importSteps.js';
import { formatQty, lotProduct, lotWho, roundTitle, stepLabel } from './shipmentRounds.js';
import { invalidateAfterRoundChange } from './useRoundUndo.jsx';

/** One lot of a round that has not left: change its quantity, move it to another round of the same factory, or take it
 * off (the quantity goes back to "รอจัดรอบ"). `targets` are the other rounds of this factory that have not left. */
export function LotModal({ round, lot, targets, onClose, showToast }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [qty, setQty] = useState(String(lot.qty));
  const [to, setTo] = useState(targets[0]?.id ?? '');
  const finish = (data, message, targetRoundId) => {
    // The emptied source round was deleted: drop its query (a refetch would 404) before refreshing the rest.
    if (data?.round == null) queryClient.removeQueries({ queryKey: queryKeys.shipmentRound(round.id) });
    invalidateAfterRoundChange(queryClient, round);
    if (targetRoundId != null) queryClient.invalidateQueries({ queryKey: queryKeys.shipmentRound(targetRoundId) });
    showToast?.('success', message);
    onClose();
    if (data?.round == null) navigate('/fulfilment');
  };
  const fail = (e) => showToast?.('error', e?.message ?? 'บันทึกไม่สำเร็จ');
  const change = useMutation({
    mutationFn: () => api.shipmentRounds.changeLot(round.id, lot.lotId, { qty: Number(qty) }),
    onSuccess: (d) => finish(d, 'แก้จำนวนแล้ว'),
    onError: fail,
  });
  const move = useMutation({
    mutationFn: () => api.shipmentRounds.moveLot(round.id, lot.lotId, { toRoundId: Number(to) }),
    onSuccess: (d) => finish(d, 'ย้ายล็อตแล้ว', Number(to)),
    onError: fail,
  });
  const remove = useMutation({
    mutationFn: () => api.shipmentRounds.removeLot(round.id, lot.lotId),
    onSuccess: (d) => finish(d, 'เอาออกจากรอบแล้ว ส่วนนี้กลับไปรอจัดรอบ'),
    onError: fail,
  });
  const busy = change.isPending || move.isPending || remove.isPending;
  const qtyOk = Number(qty) > 0 && Number.isFinite(Number(qty));
  const target = targets.find((t) => t.id === Number(to));
  const moveRewinds = target && importStepIndex(target.importStep) < importStepIndex(round.importStep);
  return (
    <Modal title="จัดการล็อต" subtitle={`${lotWho(lot)} · ${lot.docNumber}`} onClose={onClose} placement="sheet-on-phone" testId="lot-modal">
      <div className="flex flex-col gap-5">
        <p className="m-0 text-sm text-text-muted">{lotProduct(lot)} · สั่ง {formatQty(lot.itemQty)} {lot.unit ?? ''}</p>
        <div className="flex flex-col gap-2">
          <label htmlFor="lot-qty" className="text-xs font-bold text-text-secondary">จำนวนที่ไปรอบนี้ ({lot.unit ?? 'หน่วย'})</label>
          <input id="lot-qty" type="number" inputMode="decimal" min="0" step="any" className="min-h-11 w-full text-base" value={qty} onChange={(e) => setQty(e.target.value)} />
          <Button type="button" variant="secondary" disabled={busy || !qtyOk || Number(qty) === Number(lot.qty)} onClick={() => change.mutate()} data-testid="lot-save-qty">บันทึกจำนวน</Button>
        </div>
        {targets.length > 0 ? (
          <div className="flex flex-col gap-2">
            <label htmlFor="lot-move" className="text-xs font-bold text-text-secondary">ย้ายไปรอบอื่นของโรงงานนี้ (เช่น ตกเรือ / ตกตู้)</label>
            <select id="lot-move" className="min-h-11 w-full text-base" value={to} onChange={(e) => setTo(e.target.value)}>
              {targets.map((t) => <option key={t.id} value={t.id}>{roundTitle(t)} · #{t.id} · {stepLabel(t.importStep)}</option>)}
            </select>
            {moveRewinds ? <small className="text-text-muted">รอบปลายทางอยู่ขั้น “{stepLabel(target.importStep)}” ใบขอซื้อนี้จะย้อนไปขั้นนั้น</small> : null}
            <Button type="button" variant="secondary" disabled={busy || !to} onClick={() => move.mutate()} data-testid="lot-move-go">ย้ายล็อต</Button>
          </div>
        ) : null}
        <div className="flex flex-col gap-2">
          <Button type="button" variant="secondary" disabled={busy} onClick={() => remove.mutate()} data-testid="lot-remove">เอาออกจากรอบนี้</Button>
          <small className="text-text-muted">ส่วนที่เอาออกกลับไปอยู่ที่ รอจัดรอบ</small>
        </div>
      </div>
    </Modal>
  );
}
