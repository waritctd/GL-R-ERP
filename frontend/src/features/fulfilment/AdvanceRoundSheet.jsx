import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { bangkokTodayIso } from '../../utils/format.js';
import { importStepMeta, stepsAhead } from '../importProgress/importSteps.js';
import { invalidateAfterRoundChange } from './useRoundUndo.jsx';
import { CompletePerInvoiceField, LotReceiptFields, receiptsValid, RoundFactField } from './RoundFactFields.jsx';
import {
  docKindLabel, factLabel, hasNumberedDoc, roundIrCount, roundTitle, STEP_DOC_KIND, STEP_FACT_FIELDS, stepPillText, stepTone,
} from './shipmentRounds.js';

const isReceivedTarget = (t) => t === 'RECEIVED';

/** N03: move a round forward. A bottom sheet on a phone, a centred dialog from 640px up (Modal's sheet-on-phone). Asks
 * ONLY the chosen step's facts; every IR on the round follows. `onAdvanced(round)` gets the updated round. */
export function AdvanceRoundSheet({ round, onClose, onAdvanced, showToast }) {
  const queryClient = useQueryClient();
  const ahead = stepsAhead(round.importStep);
  const [target, setTarget] = useState(ahead[0]?.code);
  const [eventDate, setEventDate] = useState(bangkokTodayIso());
  const [facts, setFacts] = useState({});
  const [complete, setComplete] = useState(true);
  const [completeNote, setCompleteNote] = useState('');
  const [docNos, setDocNos] = useState({});
  // Short arrival: each lot's received quantity, shown once ของเข้าครบ is unticked (default = the lot's quantity).
  const [received, setReceived] = useState(() => Object.fromEntries((round.lots ?? []).map((l) => [l.lotId, String(l.qty)])));
  const shortArrival = isReceivedTarget(target) && !complete;
  const mode = round.facts?.transportMode;
  const fields = STEP_FACT_FIELDS[target] ?? [];
  const isReceived = target === 'RECEIVED';
  const noteMissing = isReceived && !complete && !completeNote.trim();
  const receiptsBad = shortArrival && !receiptsValid(round.lots ?? [], received);
  // Document numbers: an optional one for the step's own document (ORDERED proforma, IN_TRANSIT invoice, no warning); on
  // the way to ถึงโกดัง a proforma and an invoice number, each asked only while the round has none. A portal round
  // cannot be received without them; a migrated or backfilled one never needs them.
  const docKinds = isReceived
    ? ['PROFORMA', 'INVOICE'].filter((k) => !hasNumberedDoc(round, k))
    : (STEP_DOC_KIND[target] ? [STEP_DOC_KIND[target]] : []);
  const numbersRequired = isReceived && round.source === 'PORTAL';
  const numbersMissing = numbersRequired && docKinds.some((k) => !(docNos[k] ?? '').trim());

  const advance = useMutation({
    mutationFn: (payload) => api.shipmentRounds.advance(round.id, payload),
    onSuccess: (data) => {
      invalidateAfterRoundChange(queryClient, data.round);
      onAdvanced(data.round);
    },
    onError: (e) => showToast?.('error', e?.message ?? 'เลื่อนขั้นตอนไม่สำเร็จ'),
  });

  function submit() {
    const sent = {};
    fields.forEach((f) => { if (facts[f.key]) sent[f.key] = facts[f.key]; });
    if (isReceived) {
      sent.completePerInvoice = complete;
      if (!complete) sent.completeNote = completeNote.trim();
    }
    const lotReceipts = shortArrival ? (round.lots ?? []).map((l) => ({ lotId: l.lotId, qty: Number(received[l.lotId]) })) : undefined;
    const documents = docKinds.filter((k) => (docNos[k] ?? '').trim()).map((k) => ({ kind: k, docNo: docNos[k].trim() }));
    advance.mutate({ targetStep: target, eventDate: eventDate || undefined, expectedStep: round.importStep, facts: sent, documents, lotReceipts });
  }

  const label = importStepMeta(target)?.label ?? '';
  return (
    <Modal
      title="เลื่อนขั้นตอน"
      subtitle={roundTitle(round)}
      onClose={onClose}
      placement="sheet-on-phone"
      testId="advance-round-sheet"
      footer={(
        <Button type="button" variant="primary" className="w-full sm:w-auto" disabled={advance.isPending || noteMissing || numbersMissing || receiptsBad || !target}
          onClick={submit} data-testid="advance-round-submit">
          บันทึก {label}
        </Button>
      )}
    >
      <div className="flex flex-col gap-4">
        <p className="m-0 flex flex-wrap items-center gap-2 text-sm text-text-muted">
          {roundIrCount(round)} ใบขอซื้อ · ตอนนี้ <StatusBadge tone={stepTone(round.importStep)}>{stepPillText(round.importStep)}</StatusBadge>
        </p>
        <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
          <legend className="mb-1 p-0 text-xs font-bold text-text-secondary">เลื่อนไปที่</legend>
          {ahead.map((s) => (
            <label key={s.code} className="flex min-h-11 cursor-pointer items-center gap-3 rounded-md border border-border-input px-3">
              <input type="radio" name="target-step" checked={target === s.code} onChange={() => setTarget(s.code)} />
              <b className="text-sm">{s.s} {s.label}</b>
            </label>
          ))}
        </fieldset>
        <div className="flex flex-col gap-1">
          <label htmlFor="advance-event-date" className="text-xs font-bold text-text-secondary">
            {isReceived ? 'วันที่ของเข้าโกดัง' : 'วันที่ของขั้นตอนนี้'}
          </label>
          <ThaiDateInput id="advance-event-date" className="min-h-11 text-base" max={bangkokTodayIso()} value={eventDate}
            onChange={(e) => setEventDate(e.target.value)} />
        </div>
        {fields.length > 0 ? (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {fields.map((f) => (
              <RoundFactField key={f.key} idPrefix="advance" field={{ ...f, label: factLabel(f, mode) }} value={facts[f.key]}
                onChange={(v) => setFacts((cur) => ({ ...cur, [f.key]: v }))} />
            ))}
          </div>
        ) : null}
        {docKinds.length > 0 ? (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {docKinds.map((k) => (
              <RoundFactField key={k} idPrefix="advance-doc"
                field={{ key: k, type: 'text', label: `เลขที่ ${docKindLabel(k)}${numbersRequired ? ' *' : ' (ถ้ามี)'}` }}
                value={docNos[k]} onChange={(v) => setDocNos((cur) => ({ ...cur, [k]: v }))} />
            ))}
          </div>
        ) : null}
        {isReceived ? (
          <CompletePerInvoiceField idPrefix="advance" complete={complete} note={completeNote} onComplete={setComplete} onNote={setCompleteNote} />
        ) : null}
        {shortArrival ? (
          <LotReceiptFields idPrefix="advance" lots={round.lots ?? []} values={received} onChange={(id, v) => setReceived((cur) => ({ ...cur, [id]: v }))} />
        ) : null}
        <p className="m-0 rounded-md bg-surface-subtle px-3 py-2 text-xs text-text-muted">
          ถามเฉพาะข้อมูลของขั้นที่เลือก · ใบขอซื้อ {roundIrCount(round)} ใบในรอบนี้ขยับพร้อมกัน
        </p>
      </div>
    </Modal>
  );
}
