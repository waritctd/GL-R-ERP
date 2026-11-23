import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { bangkokTodayIso } from '../../utils/format.js';
import { cn } from '../../utils/cn.js';
import { PURPOSE_LABELS } from '../fulfilment/shipmentRounds.js';

const blankLine = () => ({ key: Math.random().toString(36).slice(2), code: '', size: '', qty: '', unit: 'แผ่น' });

/**
 * The ONE form of สั่งของไม่มีโครงการ, shared by the phone sheet (N07) and the desktop side panel (ND4): `body` is the fields,
 * `footer` the filled send button with "ยังขาด: …". Nothing here knows which of the two hosts it.
 *
 * N07 สั่งของไม่มีโครงการ: an order for a sample, the showroom or stock, with no deal. EVERY field is required (purpose, who it
 * is for / where it is used, factory, at least one line with a quantity and unit, wanted-by date): the send button stays
 * disabled and says what is still missing. No approval: import acts on it straight away. A bottom sheet on a phone, a centred
 * dialog from 640px.
 */
export function useNewOrderForm({ onDone, showToast, wide = false, enabled = true }) {
  const queryClient = useQueryClient();
  const [purpose, setPurpose] = useState('');
  const [note, setNote] = useState('');
  const [factoryId, setFactoryId] = useState('');
  const [lines, setLines] = useState(() => [blankLine()]);
  const [wantedBy, setWantedBy] = useState('');
  const factories = useQuery({ queryKey: queryKeys.factoryNames(), queryFn: () => api.factoryConfigs.names().then((r) => r.factories ?? []), enabled });

  const setLine = (key, patch) => setLines((cur) => cur.map((l) => (l.key === key ? { ...l, ...patch } : l)));
  const lineOk = (l) => l.code.trim() && Number(l.qty) > 0 && l.unit.trim();
  const missing = [
    !purpose && 'สั่งเพื่ออะไร',
    !note.trim() && 'ให้ใคร / ใช้ที่ไหน',
    !factoryId && 'โรงงาน',
    !lines.some(lineOk) || lines.some((l) => (l.code.trim() || l.qty) && !lineOk(l)) ? 'สินค้าและจำนวน' : null,
    (!wantedBy || wantedBy < bangkokTodayIso()) && 'ต้องการภายใน',
  ].filter(Boolean);

  const send = useMutation({
    mutationFn: () => api.noProjectOrders.create({
      purpose, purposeNote: note.trim(), factoryId: Number(factoryId), wantedBy,
      lines: lines.filter(lineOk).map((l) => ({ code: l.code.trim(), size: l.size.trim() || null, qty: Number(l.qty), unit: l.unit.trim() })),
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.myIncoming() });
      queryClient.invalidateQueries({ queryKey: queryKeys.shipmentRounds() });
      showToast?.('success', 'ส่งคำขอสั่งของแล้ว ฝ่ายนำเข้าได้รับแจ้งเตือนแล้ว');
      setPurpose(''); setNote(''); setFactoryId(''); setLines([blankLine()]); setWantedBy('');
      onDone?.();
    },
    onError: (e) => showToast?.('error', e?.message ?? 'ส่งคำขอไม่สำเร็จ'),
  });

  const footer = (
    <div className={cn('flex w-full flex-col items-stretch gap-1', !wide && 'sm:items-end')}>
          <Button type="button" variant="primary" className={cn('w-full', !wide && 'sm:w-auto')} disabled={missing.length > 0 || send.isPending} onClick={() => send.mutate()} data-testid="new-order-send">
            ส่งคำขอสั่งของ
          </Button>
          {missing.length > 0 ? <small className={cn('text-center text-text-muted', !wide && 'sm:text-right')} data-testid="new-order-missing">ยังขาด: {missing.join(' · ')}</small> : null}
        </div>
  );
  // a fieldset disables every field and line control while the order is on its way: an edit now would be wiped by the reset
  const body = (
      <fieldset disabled={send.isPending} className="m-0 flex min-w-0 flex-col gap-4 border-0 p-0">
        <div className="flex flex-col gap-1">
          <span className="text-xs font-bold text-text-secondary" id="order-purpose">สั่งเพื่ออะไร *</span>
          <div className="flex flex-wrap gap-2" role="group" aria-labelledby="order-purpose">
            {Object.entries(PURPOSE_LABELS).filter(([code]) => code !== 'PROJECT').map(([code, label]) => ( // PROJECT rows exist but cannot be created yet
              <button key={code} type="button" aria-pressed={purpose === code} onClick={() => setPurpose(code)} data-testid={`order-purpose-${code}`}
                className={cn('inline-flex min-h-11 items-center rounded-full border px-4 text-sm font-bold',
                  purpose === code ? 'border-primary bg-primary/10 text-primary' : 'border-border-input bg-surface text-text-secondary')}>
                {label}
              </button>
            ))}
          </div>
        </div>
        <div className="flex flex-col gap-1">
          <label htmlFor="order-note" className="text-xs font-bold text-text-secondary">{purpose === 'PROJECT' ? 'ชื่อโครงการ' : 'ให้ใคร / ใช้ที่ไหน'} *</label>
          <input id="order-note" type="text" maxLength={200} className="min-h-11 w-full text-base" value={note} onChange={(e) => setNote(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <label htmlFor="order-factory" className="text-xs font-bold text-text-secondary">โรงงาน *</label>
          <select id="order-factory" className="min-h-11 w-full text-base" value={factoryId} onChange={(e) => setFactoryId(e.target.value)}>
            <option value="">เลือกโรงงาน</option>
            {(factories.data ?? []).map((f) => <option key={f.id} value={f.id}>{f.factoryName}</option>)}
          </select>
        </div>
        <div className="flex flex-col gap-3">
          <span className="text-xs font-bold text-text-secondary">สินค้าและจำนวน *</span>
          {lines.map((l, i) => (
            <div key={l.key} className="flex flex-col gap-2 rounded-md border border-border p-3" data-testid="order-line">
              <div className="flex flex-col gap-1">
                <label htmlFor={`order-code-${l.key}`} className="text-xs font-bold text-text-secondary">รหัส / ชื่อสินค้า</label>
                <input id={`order-code-${l.key}`} type="text" maxLength={255} className="min-h-11 w-full text-base" value={l.code} onChange={(e) => setLine(l.key, { code: e.target.value })} />
              </div>
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
                <div className="flex flex-col gap-1">
                  <label htmlFor={`order-size-${l.key}`} className="text-xs font-bold text-text-secondary">ขนาด (ถ้ามี)</label>
                  <input id={`order-size-${l.key}`} type="text" maxLength={80} className="min-h-11 w-full text-base" value={l.size} onChange={(e) => setLine(l.key, { size: e.target.value })} />
                </div>
                <div className="flex flex-col gap-1">
                  <label htmlFor={`order-qty-${l.key}`} className="text-xs font-bold text-text-secondary">จำนวน</label>
                  <input id={`order-qty-${l.key}`} type="number" inputMode="decimal" min="0" step="any" className="min-h-11 w-full text-base" value={l.qty} onChange={(e) => setLine(l.key, { qty: e.target.value })} />
                </div>
                <div className="flex flex-col gap-1">
                  <label htmlFor={`order-unit-${l.key}`} className="text-xs font-bold text-text-secondary">หน่วย</label>
                  <input id={`order-unit-${l.key}`} type="text" maxLength={30} className="min-h-11 w-full text-base" value={l.unit} onChange={(e) => setLine(l.key, { unit: e.target.value })} />
                </div>
              </div>
              {lines.length > 1 ? <Button type="button" size="sm" variant="text" className="self-end" onClick={() => setLines((cur) => cur.filter((x) => x.key !== l.key))}>ลบรายการที่ {i + 1}</Button> : null}
            </div>
          ))}
          <Button type="button" variant="secondary" className="w-full sm:w-auto" onClick={() => setLines((cur) => [...cur, blankLine()])} data-testid="order-add-line">+ เพิ่มรายการ</Button>
        </div>
        <div className="flex flex-col gap-1">
          <label htmlFor="order-wanted" className="text-xs font-bold text-text-secondary">ต้องการภายใน *</label>
          <ThaiDateInput id="order-wanted" className="min-h-11 text-base" min={bangkokTodayIso()} value={wantedBy} onChange={(e) => setWantedBy(e.target.value)} />
        </div>
        <p className="m-0 text-xs text-text-muted">กรอกให้ครบทุกช่องจึงส่งได้ · ไม่ต้องขออนุมัติ ไม่มีใบเสนอราคา ไม่มีขั้นตอนเงิน · ฝ่ายนำเข้ารวมไปกับรอบขนส่งถัดไปของโรงงานนี้</p>
      </fieldset>
  );
  return { body, footer };
}

/** Phone / narrow screens: a bottom sheet, a centred dialog from 640px. */
export function NewOrderSheet({ form: { body, footer }, onClose }) {
  return (
    <Modal title="สั่งของไม่มีโครงการ" onClose={onClose} placement="sheet-on-phone" testId="new-order-sheet" footer={footer}>
      {body}
    </Modal>
  );
}

/** Desktop (ND4): the same form as a side panel beside the list, not a modal. */
export function NewOrderPanel({ form: { body, footer } }) {
  return (
    <section className="flex flex-col gap-4 rounded-md border border-border bg-surface p-4 min-[1041px]:sticky min-[1041px]:top-4" aria-label="สั่งของไม่มีโครงการ" data-testid="new-order-panel">
      <h2 className="m-0 text-base font-bold">สั่งของไม่มีโครงการ</h2>
      {body}
      <div className="flex flex-col items-stretch gap-1 border-t border-border-subtle pt-3">{footer}</div>
    </section>
  );
}
