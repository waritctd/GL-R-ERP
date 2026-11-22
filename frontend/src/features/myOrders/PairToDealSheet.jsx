import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { formatQty } from '../fulfilment/shipmentRounds.js';

const sameName = (a, b) => {
  const x = String(a ?? '').trim().toLowerCase();
  const y = String(b ?? '').trim().toLowerCase();
  return x.length >= 3 && y.length >= 3 && (x === y || x.includes(y) || y.includes(x));
};
const itemName = (i) => [i.brand, i.model, i.size].filter(Boolean).join(' ') || i.code || `รายการ ${i.id}`;

/**
 * Track now, pair later: a PROJECT order (the project known, no deal yet) is tied to its real deal, each order line to the
 * deal line it is for. Only live deals of the same project are offered. The server refuses anything else. There is no
 * undo here, so the sheet says so before the button.
 */
export function PairToDealSheet({ row, onClose, showToast }) {
  const queryClient = useQueryClient();
  const [ticketId, setTicketId] = useState('');
  const [picks, setPicks] = useState({});
  const deals = useQuery({ queryKey: ['pairDeals'], queryFn: () => api.tickets.list().then((r) => r.tickets ?? []) });
  const order = useQuery({ queryKey: ['pairOrder', row.importRequestId], queryFn: () => api.storedImportRequests.get(row.importRequestId).then((r) => r.importRequest) });
  const detail = useQuery({ queryKey: ['pairDeal', ticketId], enabled: Boolean(ticketId), queryFn: () => api.tickets.get(ticketId).then((r) => r.ticket) });
  const candidates = useMemo(() => (deals.data ?? []).filter((t) => (t.lifecycle ?? 'ACTIVE') === 'ACTIVE'
    && (sameName(row.who, t.projectName) || sameName(row.who, t.customerName))), [deals.data, row.who]);
  const lines = order.data?.items ?? [];
  const dealItems = detail.data?.items ?? [];
  const chosen = lines.map((l) => Number(picks[l.id] ?? 0));
  const complete = lines.length > 0 && chosen.every((v) => v > 0) && new Set(chosen).size === chosen.length;
  const pair = useMutation({
    mutationFn: () => api.noProjectOrders.pairToDeal(row.importRequestId, { ticketId: Number(ticketId), lines: lines.map((l) => ({ importRequestItemId: l.id, ticketItemId: Number(picks[l.id]) })) }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.myIncoming() });
      queryClient.invalidateQueries({ queryKey: queryKeys.shipmentRounds() });
      showToast?.('success', 'ผูกกับงานแล้ว');
      onClose();
    },
    onError: (e) => showToast?.('error', e?.message ?? 'ผูกกับงานไม่สำเร็จ'),
  });
  const footer = (
    <Button type="button" variant="primary" disabled={!ticketId || !complete || pair.isPending} onClick={() => pair.mutate()} data-testid="pair-send">
      ผูกกับงาน
    </Button>
  );
  return (
    <Modal title="ผูกกับงาน" subtitle={`${row.docNumber} · ${row.who}`} onClose={onClose} placement="sheet-on-phone" testId="pair-sheet" footer={footer}>
      <div className="flex flex-col gap-3">
        {deals.isLoading ? <p className="m-0 text-sm text-text-muted">กำลังโหลดงาน…</p> : candidates.length === 0 ? (
          <p className="m-0 text-sm text-text-muted" data-testid="pair-none">ไม่พบงานที่ยังเปิดอยู่ของโครงการ “{row.who}” — สร้างงานก่อน แล้วกลับมาผูก</p>
        ) : (
          <label className="flex flex-col gap-1">
            <span className="text-xs font-bold text-text-secondary">งาน *</span>
            <select className="min-h-11 rounded-md border border-border-input px-2 text-base" value={ticketId} onChange={(e) => { setTicketId(e.target.value); setPicks({}); }} data-testid="pair-deal">
              <option value="">เลือกงาน</option>
              {candidates.map((t) => <option key={t.id} value={t.id}>{t.code} · {t.projectName || t.customerName}</option>)}
            </select>
          </label>
        )}
        {ticketId && lines.map((l) => (
          <label key={l.id} className="flex flex-col gap-1">
            <span className="text-xs font-bold text-text-secondary">{itemName(l)} · {formatQty(l.qty)} {l.unit ?? ''} คือรายการไหนของงาน *</span>
            <select className="min-h-11 rounded-md border border-border-input px-2 text-base" value={picks[l.id] ?? ''} onChange={(e) => setPicks((cur) => ({ ...cur, [l.id]: e.target.value }))} data-testid={`pair-line-${l.id}`}>
              <option value="">เลือกรายการ</option>
              {dealItems.map((i) => <option key={i.id} value={i.id}>{itemName(i)} · {formatQty(i.qty)}</option>)}
            </select>
          </label>
        ))}
        <p className="m-0 text-xs text-text-muted">ล็อต รอบ และการรับของเดิมยังอยู่ครบ ผูกแล้วย้ายกลับในระบบไม่ได้ ถ้าผูกผิดให้แจ้งผู้ดูแลระบบ</p>
      </div>
    </Modal>
  );
}
