import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { bangkokToday } from './factoryContactMeta.js';

/**
 * CR-1 (GLA-167) R3 / R5 / R7 — "ติดต่อโรงงานแล้ว". A small dialog, not a confirm: import (or the
 * CEO) already contacted the factory outside the system and records WHEN (date, default today in
 * Asia/Bangkok, never later than today — the same zone FactoryQuoteService checks) and, optionally,
 * a note. Confirming moves the quote DRAFT -> REQUESTED and unlocks price entry; it is FINAL (no
 * undo, no edit), which the dialog says up front rather than leaving it as a surprise.
 */
export function FactoryContactDialog({ factoryName, pending, onCancel, onConfirm }) {
  const today = bangkokToday();
  const [contactedOn, setContactedOn] = useState(today);
  const [note, setNote] = useState('');
  // A cleared date input yields '' and a typed future date is refused by the server with a 400;
  // both are caught here so the button never invites a call that cannot succeed.
  const valid = Boolean(contactedOn) && contactedOn <= today;

  function submit() {
    if (!valid || pending) return;
    const trimmed = note.trim();
    onConfirm(trimmed ? { contactedOn, note: trimmed } : { contactedOn });
  }

  return (
    <Modal
      title="ติดต่อโรงงานแล้ว"
      subtitle={factoryName}
      onClose={onCancel}
      testId="factory-contact-dialog"
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onCancel}>ยกเลิก</Button>
          <Button type="button" variant="primary" disabled={!valid || pending} onClick={submit}>
            {pending ? 'กำลังบันทึก…' : 'ยืนยัน'}
          </Button>
        </>
      )}
    >
      <SafeForm className="grid gap-4" onSubmit={submit}>
        <FormField
          label="วันที่ติดต่อ"
          htmlFor="pcr-contact-date"
          error={contactedOn && contactedOn > today ? 'เลือกวันที่หลังวันนี้ไม่ได้' : undefined}
        >
          <input
            id="pcr-contact-date"
            type="date"
            max={today}
            value={contactedOn}
            onChange={(event) => setContactedOn(event.target.value)}
          />
        </FormField>
        <FormField label="หมายเหตุ (ไม่บังคับ)" htmlFor="pcr-contact-note" hint="เช่น ติดต่อใคร ผ่านช่องทางไหน">
          <textarea
            id="pcr-contact-note"
            className="min-h-20"
            maxLength={1000}
            value={note}
            onChange={(event) => setNote(event.target.value)}
          />
        </FormField>
        <p className="m-0 rounded-md border border-border-subtle bg-surface-subtle p-3 text-xs text-text-secondary">
          บันทึกแล้วแก้ไขหรือยกเลิกไม่ได้ — หลังจากนี้ฝ่ายนำเข้าจึงจะกรอกราคาจากโรงงานได้
        </p>
      </SafeForm>
    </Modal>
  );
}
