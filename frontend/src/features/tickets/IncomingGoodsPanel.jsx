import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { IMPORT_STEPS, importStepIndex } from '../importProgress/importSteps.js';
import { formatQty, STEP_DATE_KEY, stepLabel, stepTone } from '../fulfilment/shipmentRounds.js';

// Whole literal, one track per column (6, ND3): product, lot, quantity, status, expected, the open button.
const INCOMING_GRID = 'grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)_minmax(0,0.9fr)_minmax(0,1fr)_minmax(0,1.2fr)_minmax(0,0.6fr)]';

const lotWhen = (lot) => (lot.step === 'RECEIVED'
  ? `ถึง ${formatThaiDate(lot.receivedOn ?? lot.arrivedOn)}`
  : lot.expectedOn ? `คาดว่าถึง ${formatThaiDate(lot.expectedOn)}` : 'ยังไม่มีกำหนด');
const lotNote = (lot) => (lot.lotCount > 1 ? `ล็อต ${lot.lotNo} จาก ${lot.lotCount}` : 'ครบทั้งใบ');
const lotQtyText = (lot) => (lot.receivedQty != null && Number(lot.receivedQty) !== Number(lot.qty)
  ? `${formatQty(lot.receivedQty)} จาก ${formatQty(lot.qty)} ${lot.unit ?? ''}`
  : `${formatQty(lot.qty)} ${lot.unit ?? ''}`).trim();

// Short arrival: what is missing of a received lot (null when it arrived in full or has not arrived).
const lotShortfall = (lot) => (lot.receivedQty != null && Number(lot.receivedQty) < Number(lot.qty)
  ? `ขาด ${formatQty(Number(lot.qty) - Number(lot.receivedQty))} ${lot.unit ?? ''}`.trim() : null);

/** N06: one lot, read-only: its steps with dates, the vessel, the customer's wanted date, and a message to import. */
export function LotSheet({ ticketId, ir, lot, wantedBy, canMessage, showToast, onClose }) {
  const [message, setMessage] = useState('');
  const [writing, setWriting] = useState(false);
  const send = useMutation({
    // The existing deal comment: import reads the deal's thread on its own deal page.
    mutationFn: () => api.tickets.comment(ticketId, { message: `ถึงฝ่ายนำเข้า · ${ir.docNumber} · ${lot.product} (${lotNote(lot)}): ${message.trim()}` }),
    onSuccess: () => { showToast?.('success', 'ส่งข้อความถึงฝ่ายนำเข้าแล้ว'); setWriting(false); setMessage(''); },
    onError: (e) => showToast?.('error', e?.message ?? 'ส่งข้อความไม่สำเร็จ'),
  });
  const current = importStepIndex(lot.step);
  return (
    <Modal title="รายละเอียดล็อต" subtitle={`${ir.docNumber} · ${lot.product}`} onClose={onClose} placement="sheet-on-phone" testId="incoming-lot-sheet">
      <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <StatusBadge tone={stepTone(lot.step)}>{stepLabel(lot.step)}</StatusBadge>
          <span>{lotNote(lot)} · {lotQtyText(lot)}</span>
          {lotShortfall(lot) ? <b className="text-danger-dark" data-testid="lot-shortfall">{lotShortfall(lot)}</b> : null}
        </div>
        <ol className="m-0 flex list-none flex-col p-0" aria-label="ขั้นตอนของล็อต">
          {IMPORT_STEPS.map((s, i) => (
            <li key={s.code} data-step={s.code} className="flex min-h-11 items-center justify-between gap-3 border-t border-border-subtle py-2 first:border-t-0">
              <b className={i <= current ? 'text-sm' : 'text-sm text-text-muted'}>{i < current ? '✓ ' : ''}{s.label}</b>
              <span className="text-xs text-text-muted">{lot[STEP_DATE_KEY[s.code]] ? formatThaiDate(lot[STEP_DATE_KEY[s.code]]) : i > current ? 'ยังไม่ถึง' : '-'}</span>
            </li>
          ))}
        </ol>
        <div className="flex flex-col text-sm">
          {[['ชื่อเรือ / เที่ยวบิน / รถ', lot.vessel], ['ท่าเรือ', lot.port], ['ออกจากต้นทาง', lot.etdActualOn && formatThaiDate(lot.etdActualOn)],
            ['คาดว่าถึงไทย', lot.etaOn && formatThaiDate(lot.etaOn)], ['ลูกค้าต้องการของ', wantedBy]].filter(([, v]) => v).map(([k, v]) => (
            <div key={k} className="flex justify-between gap-3 border-t border-border-subtle py-1.5 first:border-t-0"><span className="text-text-muted">{k}</span><b className="text-right">{v}</b></div>
          ))}
        </div>
        {canMessage ? (writing ? (
          <div className="flex flex-col gap-2">
            <label htmlFor="incoming-message" className="text-xs font-bold text-text-secondary">ข้อความถึงฝ่ายนำเข้า</label>
            <textarea id="incoming-message" className="min-h-24 w-full text-base" value={message} onChange={(e) => setMessage(e.target.value)} />
            <Button type="button" variant="primary" disabled={send.isPending || !message.trim()} onClick={() => send.mutate()} data-testid="incoming-message-send">ส่งข้อความ</Button>
          </div>
        ) : (
          <Button type="button" variant="secondary" onClick={() => setWriting(true)} data-testid="incoming-message-open">ส่งข้อความถึงฝ่ายนำเข้า</Button>
        )) : null}
      </div>
    </Modal>
  );
}

/**
 * N05 / ND3 ของกำลังมา — the deal's incoming goods on its ส่งของ tab: one block per IR with a received / ordered meter, one row
 * per lot (status and expected date: the round's ETA, else the IR's expected arrival) and a รอจัดรอบ row for quantity on
 * no round. An IR on one whole round shows one row, ครบทั้งใบ. Read-only; no price, no factory document. Renders nothing
 * for a deal with no issued IR.
 */
export function IncomingGoodsPanel({ ticketId, canMessage = false, showToast }) {
  const [open, setOpen] = useState(null); // { ir, lot }
  const query = useQuery({
    queryKey: queryKeys.ticketIncoming(ticketId),
    queryFn: () => api.tickets.incoming(ticketId),
    enabled: !!ticketId,
    retry: false,
  });
  const data = query.data;
  if (!data || data.irs.length === 0) return null;

  const columns = (ir) => [
    { key: 'product', header: 'สินค้า', render: (r) => <b className="text-sm">{r.product}</b> },
    { key: 'lot', header: 'ล็อต', render: (r) => <span className="text-sm">{r.waiting ? 'ส่วนที่ยังไม่จัดรอบ' : lotNote(r)}</span> },
    { key: 'qty', header: 'จำนวน', render: (r) => (
      <span className="flex flex-col tabular-nums">
        <b>{r.waiting ? `${formatQty(r.remainingQty)} ${r.unit ?? ''}` : lotQtyText(r)}</b>
        {!r.waiting && lotShortfall(r) ? <small className="text-danger-dark">{lotShortfall(r)}</small> : null}
      </span>
    ) },
    { key: 'status', header: 'สถานะ', render: (r) => (r.waiting ? <StatusBadge tone="warning">รอจัดรอบ</StatusBadge> : <StatusBadge tone={stepTone(r.step)}>{stepLabel(r.step)}</StatusBadge>) },
    { key: 'when', header: 'กำหนด', render: (r) => (r.waiting ? <span className="text-xs text-text-muted">ยังไม่มีกำหนด</span> : <span className="text-sm">{lotWhen(r)}</span>) },
    { key: 'open', header: '', render: (r) => (r.waiting ? null : <Button type="button" size="sm" variant="secondary" onClick={() => setOpen({ ir, lot: r })} data-testid={`incoming-open-${r.lotId}`}>ดู</Button>) },
  ];
  const renderCard = (ir, r) => (
    <div className="flex items-start justify-between gap-3 rounded-md border border-border bg-surface p-3" data-testid={r.waiting ? 'incoming-waiting' : 'incoming-lot'}>
      <span className="flex min-w-0 flex-col gap-0.5">
        <b className="text-sm">{r.product}</b>
        <small className="text-text-muted">{r.waiting ? 'รอจัดรอบ' : `${lotNote(r)} · ${stepLabel(r.step)}`}</small>
        <small className="text-text-muted">{r.waiting ? 'ยังไม่มีกำหนด' : lotWhen(r)}</small>
      </span>
      <span className="flex shrink-0 flex-col items-end gap-1 text-right">
        <b className="tabular-nums">{r.waiting ? `${formatQty(r.remainingQty)} ${r.unit ?? ''}` : lotQtyText(r)}</b>
        {!r.waiting && lotShortfall(r) ? <small className="text-danger-dark">{lotShortfall(r)}</small> : null}
        {r.waiting ? null : <Button type="button" size="sm" variant="secondary" onClick={() => setOpen({ ir, lot: r })} data-testid={`incoming-open-${r.lotId}`}>ดู</Button>}
      </span>
    </div>
  );

  return (
    <Panel title="ของกำลังมา" data-testid="incoming-panel">
      <p className="m-0 mb-3 text-sm text-text-muted" data-testid="incoming-total">
        {data.totalUnit ? <>ถึงโกดังแล้ว <b className="text-text">{formatQty(data.totalReceived)} / {formatQty(data.totalOrdered)}</b> {data.totalUnit} · </> : null}
        ฝ่ายนำเข้าเป็นผู้อัปเดต
      </p>
      <div className="flex flex-col gap-4">
        {data.irs.map((ir) => {
          const rows = [...ir.lots, ...ir.waiting.map((w, i) => ({ ...w, waiting: true, lotId: `w${i}` }))];
          const pct = ir.ordered ? Math.min(100, Math.round((Number(ir.received) / Number(ir.ordered)) * 100)) : null;
          return (
            <section key={ir.importRequestId} className="flex flex-col gap-2" data-testid={`incoming-ir-${ir.importRequestId}`}>
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <b className="text-sm">{ir.factoryName} · <code>{ir.docNumber}</code></b>
                {ir.ordered != null ? <span className="text-xs text-text-muted">ถึงโกดัง {formatQty(ir.received)} / {formatQty(ir.ordered)} {ir.unit ?? ''}</span> : null}
              </div>
              {pct != null ? (
                <div className="h-2 overflow-hidden rounded-full bg-surface-subtle" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} aria-label={`ถึงโกดัง ${pct}%`}>
                  <div className="h-full bg-success" style={{ width: `${pct}%` }} />
                </div>
              ) : null}
              <DataTable columns={columns(ir)} rows={rows} getRowKey={(r) => r.lotId} gridClassName={INCOMING_GRID} mobileCard={(r) => renderCard(ir, r)}
                emptyState={{ icon: 'check', title: 'ยังไม่มีล็อต' }} />
            </section>
          );
        })}
      </div>
      {open ? <LotSheet ticketId={ticketId} ir={open.ir} lot={open.lot} wantedBy={data.wantedBy} canMessage={canMessage} showToast={showToast} onClose={() => setOpen(null)} /> : null}
    </Panel>
  );
}
