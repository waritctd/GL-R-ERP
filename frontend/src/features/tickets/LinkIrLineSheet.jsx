import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';

const itemName = (i) => [i.brand, i.model, i.size].filter(Boolean).join(' ') || `รายการ ${i.id}`;

/**
 * The stuck-deal exit: an ISSUED IR line that points at no deal line is given its deal line (import / CEO, any IR step).
 * Quantities, lots, receipts and history do not change; the server refuses another deal's line or another product's size.
 */
export function LinkIrLineSheet({ ticketId, line, onClose, onLinked, showToast }) {
  const [pick, setPick] = useState('');
  const detail = useQuery({ queryKey: ['linkLineDeal', ticketId], queryFn: () => api.tickets.get(ticketId).then((r) => r.ticket) });
  const items = detail.data?.items ?? [];
  const link = useMutation({
    mutationFn: () => api.storedImportRequests.linkLine(line.id, Number(pick)),
    onSuccess: () => { showToast?.('success', 'ผูกกับรายการของงานแล้ว'); onLinked?.(); onClose(); },
    onError: (e) => showToast?.('error', e?.message ?? 'ผูกไม่สำเร็จ'),
  });
  return (
    <Modal title="ผูกกับรายการของงาน" subtitle={`${line.code}${line.size ? ` ${line.size}` : ''} · ${Number(line.qty).toLocaleString('en-US')} ${line.unit ?? ''}`}
      onClose={onClose} placement="sheet-on-phone" testId="link-line-sheet"
      footer={<Button type="button" variant="primary" disabled={!pick || link.isPending} onClick={() => link.mutate()} data-testid="link-line-send">ผูก</Button>}>
      <div className="flex flex-col gap-3">
        {items.length === 0 && !detail.isLoading ? <p className="m-0 text-sm text-text-muted" data-testid="link-line-none">งานนี้ไม่มีรายการสินค้าให้ผูก</p> : (
          <label className="flex flex-col gap-1">
            <span className="text-xs font-bold text-text-secondary">รายการของงาน *</span>
            <select className="min-h-11 rounded-md border border-border-input px-2 text-base" value={pick} onChange={(e) => setPick(e.target.value)} data-testid="link-line-pick">
              <option value="">เลือกรายการ</option>
              {items.map((i) => <option key={i.id} value={i.id}>{itemName(i)} · {Number(i.qty).toLocaleString('en-US')}</option>)}
            </select>
          </label>
        )}
        <p className="m-0 text-xs text-text-muted">จำนวน ล็อต และการรับของเดิมไม่เปลี่ยน ผูกแล้วระบบเช็คการส่งมอบครบของงานให้ใหม่ทันที</p>
      </div>
    </Modal>
  );
}
