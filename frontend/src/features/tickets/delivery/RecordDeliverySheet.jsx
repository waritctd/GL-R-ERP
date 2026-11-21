/* Tokens only. "บันทึกการส่งสินค้า" (phone design M18): record goods delivered against ONE invoiced round, by sales or any
 * warehouse staff. Takes only round-scoped data (no deal read, no prices), so the warehouse can use it. Each line is capped
 * at what the round's tax invoice still covers; the server enforces the same (and the tax-invoice rule). A photo of the
 * signed delivery note may go with it: it is attached to the round once the delivery is saved. */
import { useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { api } from '../../../api/index.js';
import { Button } from '../../../components/common/Button.jsx';
import { Icon } from '../../../components/common/Icon.jsx';
import { Modal } from '../../../components/common/Modal.jsx';
import { ThaiDateInput } from '../../../components/common/ThaiDateInput.jsx';
import { bangkokTodayIso, formatThaiDate } from '../../../utils/format.js';
import { deliveryDateBounds } from '../deliveryDate.js';
import { MAX_UPLOAD_BYTES } from './deliveryRound.js';

const SOURCE_LABELS = { WAREHOUSE: 'คลังสินค้า', STOCK: 'สต็อก' };
const num = (n) => Number(n).toLocaleString('en-US', { maximumFractionDigits: 2 });
const remainingOf = (line) => Math.max(0, Math.round((Number(line.coveredQty) - Number(line.deliveredQty || 0)) * 100) / 100);

/** What the dialog sends. A past day only; today leaves it to the server's clock, as the deal page always did. */
export function deliveryPayload({ lines, qty, deliveredOn, recipient, source }) {
  return {
    ...(source ? { source } : {}),
    lines: lines.map((l) => ({ itemId: l.itemId, qty: Number(qty[l.itemId] || 0) })).filter((l) => l.qty > 0),
    ...(deliveredOn && deliveredOn !== bangkokTodayIso() ? { deliveredOn } : {}),
    ...(recipient.trim() ? { recipientName: recipient.trim() } : {}),
  };
}

/** The deal's items as the sheet's lines, for a delivery made before any tax invoice (no round). */
const itemLines = (items) => items.map((i) => ({
  itemId: i.id, description: [i.brand, i.model, i.size].filter(Boolean).join(' ') || `รายการ ${i.id}`, unit: 'แผ่น',
  orderedQty: Number(i.qty || 0), markedQty: Number(i.qtyNotDelivered || 0),
  coveredQty: Number(i.qty || 0) - Number(i.qtyNotDelivered || 0), deliveredQty: Number(i.qtyDelivered || 0), // less what was marked not delivered
}));

/** `round`: an INVOICED round the viewer may record against. `events`: the deal's events when the viewer reads the deal
 * (it sets the earliest day to the order-confirmation day, and the viewer picks the goods' source: คลังสินค้า or สต็อก, as
 * the deal page always offered); the warehouse has none, gets the 7-day window and the server's source.
 * No `round` = no confirmed tax invoice (Ploy ruling 2026-11-20): sales records from the deal's `items` through the deal route,
 * and the warehouse through `uninvoicedRound` (a sent round it can see; the delivery is linked to it). Either must give a
 * note/reference (required, max 500) that goes on the record, the history and the audit bells. */
export function RecordDeliverySheet({ round = null, order, events = null, ticketId = null, items = [], fulfilmentStatus = null, uninvoicedRound = null, onClose, onDone }) {
  const pickerRef = useRef(null);
  const cameraRef = useRef(null);
  const noInvoice = round == null;
  const [noteText, setNoteText] = useState('');
  const lines = (round ? round.lines : uninvoicedRound ? uninvoicedRound.lines : itemLines(items)).filter((l) => remainingOf(l) > 0);
  // what the user typed; a line not typed in shows its cap (below), so the default follows the warehouse allowance once it loads
  const [typed, setTyped] = useState({});
  const [deliveredOn, setDeliveredOn] = useState(bangkokTodayIso());
  const [recipient, setRecipient] = useState('');
  // Only a viewer who reads the deal picks the source; it starts from what the deal is (from stock, else the warehouse).
  const canPickSource = events != null;
  // A deal whose lines all fit what is declared ready from stock starts on สต็อก (the one-click complete uses the same rule).
  const stockFits = lines.length > 0 && lines.every((l) => {
    const item = items.find((i) => i.id === l.itemId);
    return item != null && Number(item.qtyFromStock || 0) - Number(item.qtyDelivered || 0) >= remainingOf(l);
  });
  const [source, setSource] = useState(fulfilmentStatus === 'FROM_STOCK' || stockFits ? 'STOCK' : 'WAREHOUSE');
  // The warehouse's remaining allowance per line (the server's one shared cap rule): the default and the limit up front.
  const allowanceQuery = useQuery({
    queryKey: ['tickets', 'deliveryAllowance', ticketId],
    queryFn: () => api.tickets.deliveryAllowance(ticketId),
    enabled: Boolean(ticketId) && source === 'WAREHOUSE',
    retry: false,
    staleTime: 0,
    gcTime: 0, // every open reads the limit afresh: a delivery or an arrival since the last open changes it
  });
  const allow = source === 'WAREHOUSE' && allowanceQuery.data
    ? Object.fromEntries((allowanceQuery.data.lines ?? []).map((l) => [l.itemId, Number(l.remaining)])) : null;
  const capOf = (line) => (allow && allow[line.itemId] != null ? Math.min(remainingOf(line), allow[line.itemId]) : remainingOf(line));
  const qty = Object.fromEntries(lines.map((l) => [l.itemId, typed[l.itemId] ?? String(capOf(l))]));
  const [proof, setProof] = useState(null);
  const [error, setError] = useState(null);
  // After the delivery is saved but the photo was not: the delivery stands, only the photo can be retried.
  const [proofFailed, setProofFailed] = useState(null);
  const today = bangkokTodayIso();
  const { min, max, confirmedOn } = deliveryDateBounds(today, events);

  const total = lines.reduce((sum, l) => sum + Math.min(Number(qty[l.itemId] || 0), capOf(l)), 0);
  const unit = lines[0]?.unit ?? 'แผ่น';
  const overLine = lines.find((l) => Number(qty[l.itemId] || 0) > capOf(l));
  // The warehouse limit is not known yet (or could not be read): saving would only fail after submit, so it waits
  const allowanceWait = source === 'WAREHOUSE' && Boolean(ticketId) && !allowanceQuery.data;
  const problem = allowanceWait ? (allowanceQuery.isError ? 'โหลดจำนวนที่ส่งได้ไม่สำเร็จ ลองอีกครั้ง' : 'กำลังโหลดจำนวนที่ส่งได้…')
    : noInvoice && !noteText.trim() ? 'ระบุหมายเหตุหรือเลขอ้างอิงก่อนบันทึก'
    : noInvoice && noteText.trim().length > 500 ? 'หมายเหตุยาวเกิน 500 ตัวอักษร' : total <= 0 ? 'ระบุจำนวนที่ส่งอย่างน้อย 1 รายการ'
    : overLine ? (capOf(overLine) < remainingOf(overLine)
      ? `${overLine.description}: เกินจำนวนที่ถึงโกดังแล้ว (ส่งได้ไม่เกิน ${num(capOf(overLine))})`
      : `${overLine.description}: เกิน${noInvoice ? 'จำนวนที่สั่ง' : 'ที่ใบกำกับครอบคลุม'} (เหลือ ${num(remainingOf(overLine))})`) : null;

  const set = (line, value) => setTyped((prev) => ({ ...prev, [line.itemId]: value }));
  const step = (line, delta) => set(line, String(Math.min(capOf(line), Math.max(0, Number(qty[line.itemId] || 0) + delta))));

  const uploadProof = async () => {
    try {
      await api.deliveryRequests.addFile(round.id, proof);
      onDone?.();
    } catch (e) {
      setProofFailed(e?.message || 'แนบรูปไม่สำเร็จ');
    }
  };

  const save = useMutation({
    mutationFn: () => {
      const payload = deliveryPayload({ lines, qty, deliveredOn, recipient, source: canPickSource ? source : null });
      // The warehouse reads no deal: it records through a round it can see (one not yet invoiced), with the same note.
      if (noInvoice && uninvoicedRound) return api.deliveryRequests.recordDelivery(uninvoicedRound.id, { ...payload, noInvoiceNote: noteText.trim() });
      return noInvoice
        ? api.tickets.recordDelivery(ticketId, { ...payload, source: canPickSource ? source : fulfilmentStatus === 'FROM_STOCK' ? 'STOCK' : 'WAREHOUSE', noInvoiceNote: noteText.trim() })
        : api.deliveryRequests.recordDelivery(round.id, payload);
    },
    onSuccess: async () => {
      if (proof) {
        await uploadProof();
      } else {
        onDone?.();
      }
    },
    onError: (e) => setError(e?.message || 'บันทึกการส่งไม่สำเร็จ'),
  });

  if (proofFailed) {
    return (
      <Modal
        title="บันทึกการส่งสินค้าแล้ว"
        onClose={() => onDone?.()}
        placement="sheet-on-phone"
        testId="record-delivery-sheet"
        footer={(
          <div className="flex w-full flex-col gap-1">
            <Button type="button" variant="primary" className="w-full" data-testid="proof-retry" onClick={() => { setProofFailed(null); uploadProof(); }}>แนบรูปอีกครั้ง</Button>
            <Button type="button" variant="text" className="min-h-12 w-full" onClick={() => onDone?.()}>เสร็จ ไม่แนบรูป</Button>
          </div>
        )}
      >
        <p role="alert" className="m-0 text-base text-danger" data-testid="proof-error">บันทึกการส่งแล้ว แต่แนบรูปไม่สำเร็จ: {proofFailed}</p>
      </Modal>
    );
  }

  return (
    <Modal
      title="บันทึกการส่งสินค้า"
      onClose={save.isPending ? () => {} : onClose}
      placement="sheet-on-phone"
      testId="record-delivery-sheet"
      footer={(
        <div className="flex w-full flex-col gap-1">
          {error ? <p role="alert" className="m-0 text-center text-sm text-danger" data-testid="record-delivery-error">{error}</p> : null}
          {problem ? <p role="status" className="m-0 text-center text-sm text-text-secondary" data-testid="record-delivery-blocked">{problem}</p> : null}
          {allowanceWait && allowanceQuery.isError ? <Button type="button" variant="secondary" className="w-full" data-testid="record-allowance-retry" onClick={() => allowanceQuery.refetch()}>ลองอีกครั้ง</Button> : null}
          <Button type="button" variant="primary" className="w-full" disabled={Boolean(problem) || save.isPending} data-testid="record-delivery-submit"
            onClick={() => { setError(null); save.mutate(); }}>
            {save.isPending ? 'กำลังบันทึก…' : overLine ? `ส่งได้ไม่เกิน ${num(capOf(overLine))} ${overLine.unit ?? unit}` : `บันทึกส่ง ${num(total)} ${unit}`}
          </Button>
          <Button type="button" variant="text" className="min-h-12 w-full" disabled={save.isPending} onClick={onClose}>ยกเลิก</Button>
        </div>
      )}
    >
      <div className="flex flex-col gap-4">
        <p className="m-0 text-sm text-text-secondary" data-testid="record-delivery-ref">
          {noInvoice ? `ออเดอร์ ${order?.orderNo ?? 1}${uninvoicedRound ? ` · รอบส่งที่ ${uninvoicedRound.roundNo}` : ''} · ยังไม่มีใบกำกับภาษี` : (
            <>ออเดอร์ {order?.orderNo ?? 1} · รอบส่งที่ {round.roundNo} · ใบกำกับ <span className="font-mono">{round.invoice?.number ?? '-'}</span></>
          )}
        </p>
        {noInvoice ? (
          <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
            หมายเหตุ / เลขอ้างอิง (ยังไม่มีใบกำกับ)
            <textarea
              required
              maxLength={500}
              rows={2}
              className="min-h-16 w-full rounded-md border border-border-input bg-surface px-3 py-2 text-base font-normal"
              placeholder="เช่น เลขที่ใบสั่งซื้อ หรือเหตุผลที่ส่งก่อน"
              value={noteText}
              data-testid="record-no-invoice-note"
              onChange={(e) => setNoteText(e.target.value)}
            />
          </label>
        ) : null}
        {lines.map((line) => (
          <div key={line.itemId} className="flex flex-col gap-2 rounded-md border border-border bg-surface px-4 py-3" data-testid={`record-line-${line.itemId}`}>
            <span className="flex flex-col">
              <b className="text-base">{line.description}</b>
              <small className="text-xs text-text-muted">
                {noInvoice ? `สั่ง ${num(line.orderedQty ?? line.coveredQty)}${line.markedQty > 0 ? ` · ไม่ได้รับของ ${num(line.markedQty)}` : ''} ${line.unit}` : `ใบกำกับครอบคลุม ${num(line.coveredQty)} ${line.unit}`}{Number(line.deliveredQty) > 0 ? ` · ส่งแล้ว ${num(line.deliveredQty)}` : ''}
              </small>
              {allow ? <small className="text-xs font-bold text-text-secondary" data-testid={`record-limit-${line.itemId}`}>ส่งได้ไม่เกิน {num(capOf(line))} {line.unit}</small> : null}
            </span>
            <div className="flex items-center gap-2">
              <Button type="button" variant="secondary" className="min-h-12 w-12 shrink-0 p-0" aria-label="ลดจำนวน" onClick={() => step(line, -1)}>
                <Icon name="minus" size={18} />
              </Button>
              <input
                type="text"
                inputMode="decimal"
                autoComplete="off"
                aria-label={`จำนวนที่ส่ง ${line.description}`}
                className="min-h-12 min-w-0 flex-1 rounded-md border border-border-input bg-surface px-3 text-center text-base font-bold tabular-nums"
                value={qty[line.itemId] ?? ''}
                data-testid={`record-qty-${line.itemId}`}
                onChange={(e) => set(line, e.target.value.replace(/[^\d.]/g, ''))}
              />
              <Button type="button" variant="secondary" className="min-h-12 w-12 shrink-0 p-0" aria-label="เพิ่มจำนวน" onClick={() => step(line, 1)}>
                <Icon name="plus" size={18} />
              </Button>
              <span className="min-w-9 text-sm text-text-secondary">{line.unit}</span>
            </div>
          </div>
        ))}
        {canPickSource ? (
          <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
            แหล่งสินค้า
            <select value={source} className="min-h-12 text-base font-normal" data-testid="record-source" onChange={(e) => setSource(e.target.value)}>
              {Object.entries(SOURCE_LABELS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
            </select>
          </label>
        ) : null}
        <div className="flex flex-col gap-1.5">
          <label htmlFor="record-delivered-on" className="text-sm font-bold text-text-secondary">วันที่ส่ง</label>
          <ThaiDateInput id="record-delivered-on" value={deliveredOn} min={min} max={max}
            onChange={(e) => setDeliveredOn(e.target.value || today)} className="min-h-12 w-full text-base" />
          <p className="m-0 text-xs text-text-muted">
            {confirmedOn ? `เลือกได้ตั้งแต่วันยืนยันคำสั่งซื้อ ${formatThaiDate(`${confirmedOn}T12:00:00+07:00`)}` : 'ย้อนหลังได้ไม่เกิน 7 วัน'}
          </p>
        </div>
        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          <span>ผู้รับของ <span className="font-normal text-text-muted">ไม่บังคับ</span></span>
          <input type="text" maxLength={255} autoComplete="off" placeholder="ชื่อผู้รับหน้างาน" value={recipient} data-testid="record-recipient"
            className="min-h-12 w-full rounded-md border border-border-input bg-surface px-3 text-base font-normal" onChange={(e) => setRecipient(e.target.value)} />
        </label>
        {noInvoice ? null : (
        <div className="flex flex-col gap-1.5">
          <span className="text-sm font-bold text-text-secondary">หลักฐานการส่ง <span className="font-normal text-text-muted">ไม่บังคับ</span></span>
          {proof ? (
            <div className="flex min-h-12 items-center gap-2 rounded-md border border-border bg-surface px-3" data-testid="record-proof-chosen">
              <Icon name="image" size={18} />
              <span className="min-w-0 flex-1 truncate text-sm">{proof.name}</span>
              <Button type="button" variant="text" className="min-h-12" onClick={() => setProof(null)}>เอาออก</Button>
            </div>
          ) : (
            <>
              <Button type="button" variant="secondary" className="min-h-12 w-full gap-2 border-dashed" data-testid="record-proof-pick" onClick={() => cameraRef.current?.click()}>
                <Icon name="camera" size={18} />
                ถ่ายรูปใบส่งของที่เซ็นแล้ว
              </Button>
              <Button type="button" variant="text" className="min-h-12 w-full" data-testid="record-proof-file" onClick={() => pickerRef.current?.click()}>
                เลือกไฟล์ (รูปหรือ PDF)
              </Button>
            </>
          )}
          {proof && proof.size > MAX_UPLOAD_BYTES ? <p role="alert" className="m-0 text-sm text-danger">ไฟล์มีขนาดใหญ่เกินไป (สูงสุด 10 MB)</p> : null}
          <input ref={cameraRef} type="file" accept="image/*" capture="environment" className="sr-only" tabIndex={-1} aria-hidden="true"
            data-testid="record-proof-camera" onChange={(e) => { setProof(e.target.files?.[0] ?? null); e.target.value = ''; }} />
          <input ref={pickerRef} type="file" accept="image/*,.pdf" className="sr-only" tabIndex={-1} aria-hidden="true"
            data-testid="record-proof-input" onChange={(e) => { setProof(e.target.files?.[0] ?? null); e.target.value = ''; }} />
        </div>
        )}
      </div>
    </Modal>
  );
}
