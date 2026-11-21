import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { invalidateAfterRoundChange } from './useRoundUndo.jsx';
import { CompletePerInvoiceField, RoundFactField } from './RoundFactFields.jsx';
import { IMPORT_STEPS, importStepIndex } from '../importProgress/importSteps.js';
import { ALL_FACT_FIELDS, factLabel, roundTitle, STEP_DATE_KEY, TRANSPORT_MODES } from './shipmentRounds.js';

/** ข้อมูลรอบนี้ → แก้ไข: every shipment fact in one sheet. Saves exactly what is shown (a cleared field is cleared). */
export function RoundFactsModal({ round, onClose, showToast }) {
  const queryClient = useQueryClient();
  const [facts, setFacts] = useState(() => ({ transportMode: 'SEA', ...(round.facts ?? {}) }));
  // Real dates of the steps the round has passed (and is on): a correction, not a move.
  const passed = IMPORT_STEPS.filter((s) => importStepIndex(s.code) <= importStepIndex(round.importStep));
  const [dates, setDates] = useState(() => Object.fromEntries(passed.map((s) => [s.code, round[STEP_DATE_KEY[s.code]] ?? ''])));
  const complete = facts.completePerInvoice !== false;
  const save = useMutation({
    mutationFn: (payload) => api.shipmentRounds.updateFacts(round.id, payload),
    onSuccess: () => { invalidateAfterRoundChange(queryClient, round); showToast?.('success', 'บันทึกข้อมูลรอบแล้ว'); onClose(); },
    onError: (e) => showToast?.('error', e?.message ?? 'บันทึกไม่สำเร็จ'),
  });
  const noteMissing = !complete && !(facts.completeNote ?? '').trim();
  const set = (key) => (value) => setFacts((cur) => ({ ...cur, [key]: value }));
  function submit() {
    const changed = {};
    passed.forEach((s) => { if (dates[s.code] && dates[s.code] !== (round[STEP_DATE_KEY[s.code]] ?? '')) changed[s.code] = dates[s.code]; });
    save.mutate({
      facts: Object.fromEntries(Object.entries(facts).map(([k, v]) => [k, v === '' ? null : v])),
      stepDates: changed,
    });
  }
  return (
    <Modal
      title="แก้ไขข้อมูลรอบนี้"
      subtitle={roundTitle(round)}
      onClose={onClose}
      placement="sheet-on-phone"
      testId="round-facts-modal"
      footer={<Button type="button" variant="primary" disabled={save.isPending || noteMissing} data-testid="round-facts-save" onClick={submit}>บันทึก</Button>}
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <label htmlFor="facts-transportMode" className="text-xs font-bold text-text-secondary">ขนส่งทาง</label>
          <select id="facts-transportMode" className="min-h-11 w-full text-base" value={facts.transportMode} onChange={(e) => set('transportMode')(e.target.value)}>
            {TRANSPORT_MODES.map((m) => <option key={m.code} value={m.code}>{m.label}</option>)}
          </select>
        </div>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {ALL_FACT_FIELDS.map((f) => (
            <RoundFactField key={f.key} idPrefix="facts" field={{ ...f, label: factLabel(f, facts.transportMode) }} value={facts[f.key]} onChange={set(f.key)} />
          ))}
        </div>
        {round.importStep === 'RECEIVED' || facts.completePerInvoice != null ? (
          <CompletePerInvoiceField idPrefix="facts" complete={complete} note={facts.completeNote}
            onComplete={(v) => setFacts((cur) => ({ ...cur, completePerInvoice: v }))} onNote={set('completeNote')} />
        ) : null}
        <fieldset className="m-0 flex flex-col gap-3 border-0 p-0">
          <legend className="mb-1 p-0 text-xs font-bold text-text-secondary">แก้วันที่จริงของขั้นตอนที่ผ่านมาแล้ว (ไม่ย้ายขั้นตอน)</legend>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {passed.map((s) => (
              <RoundFactField key={s.code} idPrefix="step-date" field={{ key: s.code, label: s.label, type: 'date' }} value={dates[s.code]}
                onChange={(v) => setDates((cur) => ({ ...cur, [s.code]: v }))} />
            ))}
          </div>
        </fieldset>
      </div>
    </Modal>
  );
}
