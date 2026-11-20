/* Tokens only. Cancelling a confirmed tax invoice (before any delivery): destructive, so it is never the primary action
 * and sits behind this sheet with a required reason. The server removes the money this invoice recorded and returns the
 * round to the issuer, who can attach the right file again. */
import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../../api/index.js';
import { queryKeys } from '../../../api/queryKeys.js';
import { Button } from '../../../components/common/Button.jsx';
import { Modal } from '../../../components/common/Modal.jsx';
import { formatMoney } from '../../../utils/format.js';

/** `round`: an INVOICED round the viewer may void (viewer.canVoid). */
export function VoidInvoiceSheet({ round, ticketId, onClose }) {
  const queryClient = useQueryClient();
  const [reason, setReason] = useState('');
  const [error, setError] = useState(null);
  const recorded = round.invoice?.recordedAmount;
  const mutation = useMutation({
    mutationFn: () => api.deliveryRequests.voidInvoice(round.id, { reason: reason.trim() }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.deliveryRequests(ticketId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketPayments(ticketId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
      queryClient.invalidateQueries({ queryKey: ['notifications'] });
      onClose();
    },
    onError: (e) => {
      setError(e?.message || 'ยกเลิกไม่สำเร็จ');
      queryClient.invalidateQueries({ queryKey: queryKeys.deliveryRequests(ticketId) });
    },
  });
  const empty = !reason.trim();
  return (
    <Modal
      title="ยกเลิกใบกำกับนี้"
      onClose={mutation.isPending ? () => {} : onClose}
      placement="sheet-on-phone"
      testId="void-invoice-sheet"
      footer={(
        <div className="flex w-full flex-col gap-1">
          {error ? <p role="alert" className="m-0 text-center text-sm text-danger" data-testid="void-error">{error}</p> : null}
          {empty ? <p role="status" className="m-0 text-center text-sm text-text-secondary" data-testid="void-blocked">ระบุเหตุผลที่ยกเลิก</p> : null}
          <Button type="button" variant="danger" className="w-full" disabled={empty || mutation.isPending} data-testid="void-go" onClick={() => { setError(null); mutation.mutate(); }}>
            {mutation.isPending ? 'กำลังยกเลิก…' : 'ยกเลิกใบกำกับ'}
          </Button>
          <Button type="button" variant="text" className="min-h-12 w-full" disabled={mutation.isPending} onClick={onClose}>ไม่ยกเลิก</Button>
        </div>
      )}
    >
      <div className="flex flex-col gap-3">
        <p className="m-0 text-base">
          ใบกำกับ <b className="font-mono">{round.invoice?.number}</b>
          {recorded != null && Number(recorded) > 0 ? ` จะถูกยกเลิก และเงิน ${formatMoney(recorded)} ที่บันทึกรับจากใบกำกับนี้จะถูกลบออก` : ' จะถูกยกเลิก'}
          {' '}รอบส่งนี้จะกลับไปรอผู้ออกใบกำกับแนบไฟล์ใหม่
        </p>
        <label className="flex flex-col gap-1 text-sm font-bold text-text-secondary">
          เหตุผล
          <textarea
            className="min-h-24 w-full rounded-md border border-border-input bg-surface px-3 py-2 text-base font-normal"
            value={reason}
            maxLength={300}
            data-testid="void-reason"
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
      </div>
    </Modal>
  );
}
