/* Tokens only. "ส่งกลับฝ่ายขาย" (phone design M15): the warehouse says what is missing and the round goes back to the
 * rep, who sees the reason, fixes it and sends it again. At least one chip or some words are required. */
import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../../api/index.js';
import { queryKeys } from '../../../api/queryKeys.js';
import { Button } from '../../../components/common/Button.jsx';
import { Icon } from '../../../components/common/Icon.jsx';
import { Modal } from '../../../components/common/Modal.jsx';
import { cn } from '../../../utils/cn.js';
import { RETURN_REASONS } from './deliveryRound.js';

/** `order`: the rounds response's order ref; `round`: the SENT round being sent back. */
export function SendBackSheet({ ticketId, order, round, onClose }) {
  const queryClient = useQueryClient();
  const [codes, setCodes] = useState([]);
  const [detail, setDetail] = useState('');
  const [error, setError] = useState(null);
  const empty = codes.length === 0 && !detail.trim();

  const sendBack = useMutation({
    mutationFn: () => api.deliveryRequests.sendBack(round.id, { reasonCodes: codes, detail: detail.trim() || null }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.deliveryRequests(ticketId) });
      queryClient.invalidateQueries({ queryKey: ['notifications'] });
      onClose();
    },
    onError: (e) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.deliveryRequests(ticketId) });
      // 409: someone already acted on the round. Close and let the screen say so, as it no longer offers this.
      if (e?.status === 409) onClose(e.message);
      else setError(e?.message || 'ส่งกลับไม่สำเร็จ');
    },
  });
  const toggle = (code) => setCodes((prev) => (prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code]));
  const orderLabel = order.orderNo > 1 || order.parentCode ? `ออเดอร์ ${order.orderNo} · ` : '';

  return (
    <Modal
      title="ส่งกลับฝ่ายขาย"
      // Modal calls onClose with the click event; our onClose(notice) takes a message, so never pass the event on.
      onClose={sendBack.isPending ? () => {} : () => onClose()}
      placement="sheet-on-phone"
      testId="send-back-sheet"
      footer={(
        <div className="flex w-full flex-col gap-2">
          {empty ? <p role="status" className="m-0 text-center text-sm text-text-secondary" data-testid="send-back-blocked">เลือกสิ่งที่ขาด หรือเขียนรายละเอียดอย่างน้อยหนึ่งอย่าง</p> : null}
          <Button
            type="button"
            variant="primary"
            className="w-full"
            disabled={empty || sendBack.isPending}
            data-testid="send-back-submit"
            onClick={() => { setError(null); sendBack.mutate(); }}
          >
            {sendBack.isPending ? 'กำลังส่ง…' : 'ส่งกลับฝ่ายขาย'}
          </Button>
        </div>
      )}
    >
      <div className="flex flex-col gap-5">
        <p className="m-0 text-base text-text-secondary">
          {orderLabel}รอบส่งที่ {round.roundNo} · เรื่องจะกลับไปที่{order.ownerName || 'ฝ่ายขาย'} พร้อมเหตุผลนี้
        </p>
        {error ? <p role="alert" className="m-0 text-sm text-danger" data-testid="send-back-error">{error}</p> : null}
        <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
          <legend className="mb-2 p-0 text-[0.8125rem] font-bold text-text-secondary">ขาดอะไร</legend>
          {RETURN_REASONS.map((reason) => {
            const on = codes.includes(reason.code);
            return (
              <label
                key={reason.code}
                className={cn(
                  'flex min-h-14 cursor-pointer items-center gap-3 rounded-md border-[1.5px] px-3 py-2',
                  on ? 'border-primary bg-primary-bg' : 'border-border-input bg-surface',
                )}
              >
                <input type="checkbox" className="sr-only" checked={on} onChange={() => toggle(reason.code)} />
                <span
                  aria-hidden="true"
                  className={cn('grid size-6 shrink-0 place-items-center rounded-sm border-[1.5px]', on ? 'border-primary bg-primary text-surface' : 'border-border-input bg-surface')}
                >
                  {on ? <Icon name="check" size={16} /> : null}
                </span>
                <b className="text-[0.9375rem]">{reason.label}</b>
              </label>
            );
          })}
        </fieldset>
        <label className="flex flex-col gap-1.5">
          <span className="text-[0.8125rem] font-bold text-text-secondary">รายละเอียด</span>
          <textarea
            rows={3}
            value={detail}
            onChange={(e) => setDetail(e.target.value)}
            className="w-full rounded-md border-[1.5px] border-border-input bg-surface px-3 py-2 text-base font-normal text-text"
            data-testid="send-back-detail"
          />
        </label>
      </div>
    </Modal>
  );
}
