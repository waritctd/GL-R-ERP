import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { formatMoney } from '../../utils/format.js';

// Small money-action forms for the finance deal page. Each one owns only its draft; the page owns the mutation
// and hands back `busy` (request pending) and `error` (the server's own message), so a failed request stays
// inside the dialog with the user's input intact.

const PAYMENT_KINDS = [
  { value: 'DEPOSIT', label: 'มัดจำ' },
  { value: 'BALANCE', label: 'ส่วนที่เหลือ' },
  { value: 'ADJUSTMENT', label: 'ปรับปรุงยอด/คืนเงิน' },
];

function ModalError({ error }) {
  if (!error) return null;
  return (
    <p role="alert" className="m-0 flex items-start gap-2 rounded-md border border-danger-border bg-danger-bg px-3 py-2 text-sm font-bold text-danger [overflow-wrap:anywhere]">
      <Icon name="triangleAlert" size={16} className="mt-0.5 shrink-0" />
      <span className="min-w-0">{error}</span>
    </p>
  );
}

function Footer({ onClose, busy, submitLabel, onSubmit, tone = 'primary' }) {
  return (
    <>
      <Button type="button" variant="secondary" className="whitespace-nowrap" onClick={onClose} disabled={busy}>ยกเลิก</Button>
      <Button type="button" variant={tone} className="whitespace-nowrap" loading={busy} onClick={onSubmit}>{submitLabel}</Button>
    </>
  );
}

// `prefill` is paymentPrefill(deal): { kind, amount, docLabel, basisHint } — the open notice/invoice the form starts
// from (design m-payment). Every field stays editable.
export function RecordPaymentModal({ prefill, expressUploadEnabled = false, busy, error, onSubmit, onClose }) {
  const [kind, setKind] = useState(prefill.kind);
  const [amount, setAmount] = useState(prefill.amount != null ? String(prefill.amount) : '');
  const [noteOpen, setNoteOpen] = useState(false);
  const [receivedAt, setReceivedAt] = useState('');
  const [receiptRef, setReceiptRef] = useState('');
  const [note, setNote] = useState('');
  const [allowOverpayment, setAllowOverpayment] = useState(false);
  const [amountError, setAmountError] = useState('');

  function submit() {
    if (busy) return;
    const value = Number(amount);
    if (!value || value <= 0) {
      setAmountError('กรุณากรอกยอดรับชำระ');
      return;
    }
    setAmountError('');
    onSubmit({
      kind,
      amount: value,
      receivedAt: receivedAt ? new Date(receivedAt).toISOString() : null,
      note: note.trim() || null,
      receiptRef: receiptRef.trim() || null,
      allowOverpayment,
    });
  }

  return (
    <Modal
      title="บันทึกรับชำระ"
      // Design m-payment: while the Express upload is on it is the normal path (spec 2026-11-15 §8) and this is the
      // fallback; with the upload off there is no Express path to fall back from, so the header says nothing of it.
      subtitle={expressUploadEnabled
        ? 'ใช้เมื่อรายการจาก Express จับคู่ไม่ได้ หรือรับเงินนอก Express — รายการนี้ย้อนกลับไม่ได้ ตรวจยอดก่อนบันทึก'
        : 'รายการนี้ย้อนกลับไม่ได้ ตรวจยอดก่อนบันทึก'}
      onClose={busy ? undefined : onClose}
      footer={<Footer onClose={onClose} busy={busy} submitLabel="บันทึกรับชำระ" onSubmit={submit} />}
    >
      <div className="grid gap-3.5">
        {prefill.docLabel ? (
          <p
            className="m-0 flex items-start gap-2 rounded-md bg-surface-muted px-3 py-2.5 text-sm font-bold text-text [overflow-wrap:anywhere]"
            data-testid="payment-prefill-doc"
          >
            <Icon name="fileText" size={16} className="mt-0.5 shrink-0 text-text-muted" />
            <span className="min-w-0">
              {prefill.docLabel}
              {prefill.amount != null ? ` · คงค้าง ${formatMoney(prefill.amount)}` : ''}
            </span>
          </p>
        ) : null}
        {prefill.whtHint ? (
          <p className="m-0 flex items-start gap-2 text-sm text-warning-dark" data-testid="payment-wht-hint">
            {prefill.whtHint}
          </p>
        ) : null}
        <FormField label="ประเภท" htmlFor="fin-pay-kind">
          <select id="fin-pay-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
            {PAYMENT_KINDS.map((k) => <option key={k.value} value={k.value}>{k.label}</option>)}
          </select>
        </FormField>
        <div className="grid grid-cols-[repeat(auto-fit,minmax(160px,1fr))] gap-3">
          <FormField
            label="จำนวนเงิน" htmlFor="fin-pay-amount" error={amountError}
            hint={prefill.amount != null && amount === String(prefill.amount) ? prefill.basisHint : undefined}
          >
            <input
              id="fin-pay-amount" type="number" inputMode="decimal" min="0" step="0.01"
              className="tabular-nums" value={amount}
              onChange={(e) => { setAmount(e.target.value); setAmountError(''); }}
            />
          </FormField>
          <FormField label="วันที่รับเงิน (ไม่บังคับ)" htmlFor="fin-pay-date" hint="เว้นว่าง = ใช้เวลาตอนบันทึก">
            <ThaiDateInput id="fin-pay-date" value={receivedAt} onChange={(e) => setReceivedAt(e.target.value)} />
          </FormField>
        </div>
        <FormField label="เลขอ้างอิง" htmlFor="fin-pay-ref">
          <input id="fin-pay-ref" value={receiptRef} maxLength={60} onChange={(e) => setReceiptRef(e.target.value)} />
        </FormField>
        {noteOpen ? (
          <FormField label="หมายเหตุ" htmlFor="fin-pay-note">
            <textarea id="fin-pay-note" rows={3} value={note} onChange={(e) => setNote(e.target.value)} />
          </FormField>
        ) : (
          <Button type="button" variant="text" className="justify-self-start" onClick={() => setNoteOpen(true)}>
            + หมายเหตุ
          </Button>
        )}
        <label className="flex min-h-11 items-center gap-2 text-sm">
          <input type="checkbox" checked={allowOverpayment} onChange={(e) => setAllowOverpayment(e.target.checked)} />
          ยืนยันรับชำระเกินยอด
        </label>
        <ModalError error={error} />
      </div>
    </Modal>
  );
}

export function RevokeCloseModal({ busy, error, onSubmit, onClose }) {
  const [note, setNote] = useState('');
  return (
    <Modal
      title="ยกเลิกการยืนยันปิดงาน"
      subtitle="ดีลจะกลับไปรอการยืนยันปิดงานอีกครั้ง"
      onClose={busy ? undefined : onClose}
      footer={(
        <Footer
          onClose={onClose} busy={busy} tone="danger" submitLabel="ยกเลิกการยืนยันปิดงาน"
          onSubmit={() => { if (!busy) onSubmit({ note: note.trim() || null }); }}
        />
      )}
    >
      <div className="grid gap-3.5">
        <FormField label="หมายเหตุ (ไม่บังคับ)" htmlFor="fin-revoke-note">
          <textarea id="fin-revoke-note" rows={3} value={note} onChange={(e) => setNote(e.target.value)} />
        </FormField>
        <ModalError error={error} />
      </div>
    </Modal>
  );
}

export function StageModal({ options, busy, error, onSubmit, onClose }) {
  const [stage, setStage] = useState(options[0]?.value ?? '');
  const [note, setNote] = useState('');
  return (
    <Modal
      title="แก้ไขสถานะ"
      onClose={busy ? undefined : onClose}
      footer={(
        <Footer
          onClose={onClose} busy={busy} submitLabel="บันทึกสถานะ"
          onSubmit={() => {
            if (busy || !stage) return;
            onSubmit({ stage, ...(note.trim() ? { note: note.trim() } : {}) });
          }}
        />
      )}
    >
      <div className="grid gap-3.5">
        <FormField label="สถานะที่ต้องการ" htmlFor="fin-stage-select">
          <select id="fin-stage-select" value={stage} onChange={(e) => setStage(e.target.value)}>
            {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </FormField>
        <FormField label="หมายเหตุ (ไม่บังคับ)" htmlFor="fin-stage-note">
          <textarea id="fin-stage-note" rows={3} value={note} onChange={(e) => setNote(e.target.value)} />
        </FormField>
        <ModalError error={error} />
      </div>
    </Modal>
  );
}
