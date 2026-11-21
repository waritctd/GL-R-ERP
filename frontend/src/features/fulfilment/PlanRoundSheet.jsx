import { useMemo, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { cn } from '../../utils/cn.js';
import { formatQty, lotProduct, TRANSPORT_MODES, waitingFactories } from './shipmentRounds.js';
import { invalidateAfterRoundChange } from './useRoundUndo.jsx';

/**
 * N04 จัดรอบขนส่ง: pick a factory (only factories with waiting IRs), tick its waiting IRs and type, per line, the quantity
 * going (default: all that remains; less than that = a split, the rest stays waiting). With `round`, the same picker adds
 * IRs to that round (its factory only). A bottom sheet on a phone, a centred dialog from 640px.
 */
export function PlanRoundSheet({ waiting, round, onClose, onDone, showToast }) {
  const queryClient = useQueryClient();
  const factories = useMemo(() => waitingFactories(waiting), [waiting]);
  const [factoryId, setFactoryId] = useState(round?.factoryId ?? factories[0]?.factoryId ?? null);
  const [label, setLabel] = useState('');
  const [mode, setMode] = useState('SEA');
  const [picked, setPicked] = useState({}); // irId -> { itemId: qty string }
  const factory = factories.find((f) => f.factoryId === factoryId);
  const irs = factory?.irs ?? [];

  const toggle = (ir) => setPicked((cur) => {
    if (cur[ir.importRequestId]) {
      const { [ir.importRequestId]: _gone, ...rest } = cur;
      return rest;
    }
    return { ...cur, [ir.importRequestId]: Object.fromEntries(ir.lines.map((l) => [l.itemId, String(l.remainingQty)])) };
  });
  const setQty = (irId, itemId, value) => setPicked((cur) => ({ ...cur, [irId]: { ...cur[irId], [itemId]: value } }));

  // A line goes when it has a positive quantity; more than remains is an error shown under the field.
  const problems = {};
  const lots = [];
  irs.filter((ir) => picked[ir.importRequestId]).forEach((ir) => {
    ir.lines.forEach((l) => {
      const raw = (picked[ir.importRequestId][l.itemId] ?? '').trim();
      if (raw === '' || Number(raw) === 0) return;
      const n = Number(raw);
      if (!Number.isFinite(n) || n < 0) problems[l.itemId] = 'จำนวนไม่ถูกต้อง';
      else if (n > Number(l.remainingQty)) problems[l.itemId] = `เกินที่เหลือ (${formatQty(l.remainingQty)})`;
      else lots.push({ importRequestId: ir.importRequestId, itemId: l.itemId, qty: n });
    });
  });
  const irCount = new Set(lots.map((l) => l.importRequestId)).size;
  const invalid = lots.length === 0 || Object.keys(problems).length > 0;

  const save = useMutation({
    mutationFn: () => (round
      ? api.shipmentRounds.addLots(round.id, { lots })
      : api.shipmentRounds.createRound({ factoryId, label: label.trim() || null, transportMode: mode, lots })),
    onSuccess: (data) => {
      invalidateAfterRoundChange(queryClient, data.round);
      showToast?.('success', round ? 'เพิ่มใบขอซื้อเข้ารอบแล้ว' : 'สร้างรอบขนส่งแล้ว');
      onDone(data.round);
    },
    onError: (e) => showToast?.('error', e?.message ?? 'บันทึกไม่สำเร็จ'),
  });

  return (
    <Modal
      title={round ? 'เพิ่มใบขอซื้อเข้ารอบนี้' : 'จัดรอบขนส่ง'}
      subtitle={round ? round.factoryName : undefined}
      onClose={onClose}
      placement="sheet-on-phone"
      testId="plan-round-sheet"
      footer={(
        <Button type="button" variant="primary" className="w-full sm:w-auto" disabled={invalid || save.isPending} data-testid="plan-round-submit" onClick={() => save.mutate()}>
          {round ? `เพิ่มเข้ารอบ · ${irCount} ใบขอซื้อ` : `สร้างรอบขนส่ง · ${irCount} ใบขอซื้อ`}
        </Button>
      )}
    >
      <div className="flex flex-col gap-4">
        {irs.length === 0 && !round ? <p className="m-0 text-sm text-text-muted">ไม่มีใบขอซื้อที่รอจัดรอบ</p> : null}
        {!round && factories.length > 0 ? (
          <div className="flex flex-col gap-1">
            <label htmlFor="plan-factory" className="text-xs font-bold text-text-secondary">โรงงาน</label>
            <select id="plan-factory" className="min-h-11 w-full text-base" value={factoryId ?? ''}
              onChange={(e) => { setFactoryId(Number(e.target.value)); setPicked({}); }}>
              {factories.map((f) => <option key={f.factoryId} value={f.factoryId}>{f.factoryName}</option>)}
            </select>
          </div>
        ) : null}
        {!round ? (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div className="flex flex-col gap-1">
              <label htmlFor="plan-label" className="text-xs font-bold text-text-secondary">ชื่อรอบ (ถ้ามี เช่น รอบ พ.ย.)</label>
              <input id="plan-label" type="text" maxLength={100} className="min-h-11 w-full text-base" value={label} onChange={(e) => setLabel(e.target.value)} />
            </div>
            <div className="flex flex-col gap-1">
              <label htmlFor="plan-mode" className="text-xs font-bold text-text-secondary">ขนส่งทาง</label>
              <select id="plan-mode" className="min-h-11 w-full text-base" value={mode} onChange={(e) => setMode(e.target.value)}>
                {TRANSPORT_MODES.map((m) => <option key={m.code} value={m.code}>{m.label}</option>)}
              </select>
            </div>
          </div>
        ) : null}
        {irs.length > 0 ? (
          <div className="flex flex-col gap-2">
            <p className="m-0 text-xs font-bold text-text-secondary">ของที่ไปรอบนี้ <span className="font-normal text-text-muted">เลือกใบขอซื้อ แล้วใส่จำนวนที่ไปจริง</span></p>
            {irs.map((ir) => {
              const on = Boolean(picked[ir.importRequestId]);
              return (
                <div key={ir.importRequestId} className={cn('rounded-md border bg-surface', on ? 'border-primary' : 'border-border')} data-testid={`plan-ir-${ir.importRequestId}`}>
                  <div className="flex min-h-11 items-start gap-3 p-3">
                    <input id={`plan-pick-${ir.importRequestId}`} type="checkbox" className="mt-1 size-5" checked={on} onChange={() => toggle(ir)} />
                    <div className="flex min-w-0 flex-col">
                      <label htmlFor={`plan-pick-${ir.importRequestId}`} className="cursor-pointer text-sm font-bold">{ir.projectName || ir.customerName || ir.ticketCode}</label>
                      <small className="text-text-muted"><code>{ir.docNumber}</code> · {ir.lines.length} รายการ</small>
                    </div>
                  </div>
                  {on ? (
                    <div className="flex flex-col gap-3 px-3 pb-3 sm:pl-11">
                      {ir.lines.map((l) => {
                        const id = `plan-qty-${l.itemId}`;
                        const going = Number(picked[ir.importRequestId][l.itemId] || 0);
                        return (
                          <div key={l.itemId} className="flex flex-col gap-1">
                            <label htmlFor={id} className="text-xs font-bold text-text-secondary">
                              {lotProduct(l)} · ไปรอบนี้ ({l.unit ?? 'หน่วย'}) <span className="font-normal text-text-muted">เหลือ {formatQty(l.remainingQty)}</span>
                            </label>
                            <input id={id} type="number" inputMode="decimal" min="0" step="any" className="min-h-11 w-full text-base"
                              value={picked[ir.importRequestId][l.itemId] ?? ''} onChange={(e) => setQty(ir.importRequestId, l.itemId, e.target.value)} />
                            <small className={problems[l.itemId] ? 'text-danger-dark' : 'text-text-muted'}>
                              {problems[l.itemId] ?? (going > 0 && going < Number(l.remainingQty)
                                ? `เหลือ ${formatQty(Number(l.remainingQty) - going)} ไปรอบถัดไป` : going > 0 ? 'ครบทั้งใบ' : 'ไม่ไปรอบนี้')}
                            </small>
                          </div>
                        );
                      })}
                    </div>
                  ) : null}
                </div>
              );
            })}
          </div>
        ) : null}
        <p className="m-0 rounded-md bg-surface-subtle px-3 py-2 text-xs text-text-muted">ใส่จำนวนน้อยกว่าที่สั่ง = แบ่งล็อต ส่วนที่เหลือกลับไปอยู่ที่ รอจัดรอบ</p>
      </div>
    </Modal>
  );
}
