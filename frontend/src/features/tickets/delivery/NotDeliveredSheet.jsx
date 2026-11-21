/* "ไม่ได้รับของ": the sales manager or CEO marks a quantity of a deal line the factory never sent, with a reason. The quantity
 * defaults to the most the server allows (what has not arrived and is not on its way); no amount changes. Phone first. */
import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { api } from '../../../api/index.js';
import { Button } from '../../../components/common/Button.jsx';
import { Modal } from '../../../components/common/Modal.jsx';

const num = (n) => Number(n).toLocaleString('en-US', { maximumFractionDigits: 2 });

export function NotDeliveredSheet({ ticketId, item, onClose, onDone }) {
  const [typedQty, setTypedQty] = useState(null);
  const [reason, setReason] = useState('');
  const [error, setError] = useState(null);
  const allowedQuery = useQuery({
    queryKey: ['tickets', 'notDeliveredAllowed', ticketId, item.id],
    queryFn: () => api.tickets.notDeliveredAllowed(ticketId, item.id),
    retry: false,
  });
  const allowed = allowedQuery.data ? Number(allowedQuery.data.allowed) : null;
  const qty = typedQty ?? (allowed != null ? String(allowed) : '');
  const name = [item.brand, item.model, item.size].filter(Boolean).join(' ') || `รายการ ${item.id}`;
  const save = useMutation({
    mutationFn: () => api.tickets.markNotDelivered(ticketId, item.id, { qty: Number(qty), reason: reason.trim() }),
    onSuccess: () => onDone?.(),
    onError: (e) => setError(e?.message || 'บันทึกไม่สำเร็จ'),
  });
  const problem = allowedQuery.isError ? (allowedQuery.error?.message || 'บันทึกว่าไม่ได้รับของไม่ได้')
    : allowed == null ? 'กำลังตรวจสอบจำนวนที่บันทึกได้…'
      : allowed <= 0 ? 'ไม่มีจำนวนที่บันทึกได้ (ของถึงโกดังหรืออยู่ระหว่างขนส่งแล้ว)'
        : !(Number(qty) > 0) ? 'ระบุจำนวน'
          : Number(qty) > allowed ? `บันทึกได้ไม่เกิน ${num(allowed)}`
            : !reason.trim() ? 'ระบุเหตุผล' : null;
  return (
    <Modal
      title="ไม่ได้รับของ"
      subtitle={name}
      onClose={save.isPending ? () => {} : onClose}
      placement="sheet-on-phone"
      testId="not-delivered-sheet"
      footer={(
        <div className="flex w-full flex-col gap-1">
          {error ? <p role="alert" className="m-0 text-center text-sm text-danger" data-testid="not-delivered-error">{error}</p> : null}
          {problem ? <p role="status" className="m-0 text-center text-sm text-text-secondary" data-testid="not-delivered-blocked">{problem}</p> : null}
          <Button type="button" variant="primary" className="w-full" disabled={Boolean(problem) || save.isPending} data-testid="not-delivered-confirm"
            onClick={() => { setError(null); save.mutate(); }}>
            {save.isPending ? 'กำลังบันทึก…' : 'ยืนยัน ไม่ได้รับของ'}
          </Button>
          <Button type="button" variant="text" className="min-h-12 w-full" disabled={save.isPending} onClick={onClose}>ยกเลิก</Button>
        </div>
      )}
    >
      <div className="flex flex-col gap-4">
        <p className="m-0 text-sm text-text-secondary">
          บันทึกว่าโรงงานไม่ส่งสินค้าส่วนนี้ ดีลจะปิดได้โดยไม่ต้องส่งส่วนนี้ ยอดเงินไม่เปลี่ยนอัตโนมัติ ฝ่ายบัญชีจัดการคืนเงินหรือเครดิตตามปกติ
        </p>
        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          จำนวนที่ไม่ได้รับ{allowed != null ? ` (ไม่เกิน ${num(allowed)})` : ''}
          <input type="text" inputMode="decimal" autoComplete="off" value={qty} data-testid="not-delivered-qty"
            className="min-h-12 rounded-md border border-border-input bg-surface px-3 text-base font-bold tabular-nums"
            onChange={(e) => setTypedQty(e.target.value.replace(/[^\d.]/g, ''))} />
        </label>
        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          เหตุผล *
          <textarea rows={2} maxLength={255} value={reason} data-testid="not-delivered-reason"
            className="min-h-16 w-full rounded-md border border-border-input bg-surface px-3 py-2 text-base font-normal"
            onChange={(e) => setReason(e.target.value)} />
        </label>
      </div>
    </Modal>
  );
}
