import { useMemo, useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { isValidLeadTimeRange, leadTimeWithUnit } from './factoryContactMeta.js';

/**
 * CR-1 (GLA-167) R2 / R10 (option C) — import asks to change ระยะเวลานำเข้า for ONE factory.
 *
 * One request per factory: a new min/max and a required reason at the top, then every import
 * (non-stock) line of that factory PRE-TICKED with that value. Import can untick a line or type
 * that line's own min/max; the owning rep / a sales manager later approves or rejects the request
 * once, as a whole. `lines` are the factory's pricing-request lines (current values shown as the
 * "old" range); `existing` is the PENDING change being edited, if any (then the dialog seeds from
 * it and saves through update instead of create).
 */
export function LeadTimeChangeDialog({ factoryName, lines, existing = null, pending, onCancel, onSubmit }) {
  const seeded = useMemo(() => {
    const byItem = new Map((existing?.lines ?? []).map((line) => [line.pricingRequestItemId, line]));
    return Object.fromEntries(lines.map((line) => {
      const prior = byItem.get(line.item.id);
      return [line.item.id, {
        ticked: existing ? Boolean(prior) : true,
        min: prior ? String(prior.newMinDays) : '',
        max: prior ? String(prior.newMaxDays) : '',
      }];
    }));
  }, [lines, existing]);
  const firstSeed = existing?.lines?.[0];
  const [bulkMin, setBulkMin] = useState(firstSeed ? String(firstSeed.newMinDays) : '');
  const [bulkMax, setBulkMax] = useState(firstSeed ? String(firstSeed.newMaxDays) : '');
  const [reason, setReason] = useState(existing?.reason ?? '');
  const [rows, setRows] = useState(seeded);

  function changeBulk(field, value) {
    if (field === 'min') setBulkMin(value); else setBulkMax(value);
    // The bulk value is what every TICKED line is pre-filled with; a line's own override is typed
    // after it (typing the bulk value again resets the ticked lines to it).
    setRows((current) => Object.fromEntries(Object.entries(current).map(([id, row]) => [
      id, row.ticked ? { ...row, [field]: value } : row,
    ])));
  }

  function patchRow(itemId, patch) {
    setRows((current) => ({ ...current, [itemId]: { ...current[itemId], ...patch } }));
  }

  function toggleRow(itemId) {
    setRows((current) => {
      const row = current[itemId];
      // Re-ticking a line gives it the bulk value again rather than leaving it blank.
      return { ...current, [itemId]: row.ticked
        ? { ...row, ticked: false }
        : { ticked: true, min: bulkMin, max: bulkMax } };
    });
  }

  const ticked = lines.filter((line) => rows[line.item.id]?.ticked);
  const valid = reason.trim().length > 0 && ticked.length > 0
    && ticked.every((line) => isValidLeadTimeRange(rows[line.item.id].min, rows[line.item.id].max));

  function submit() {
    if (!valid || pending) return;
    onSubmit({
      reason: reason.trim(),
      lines: ticked.map((line) => ({
        pricingRequestItemId: line.item.id,
        newMinDays: Number(rows[line.item.id].min),
        newMaxDays: Number(rows[line.item.id].max),
      })),
    });
  }

  return (
    <Modal
      title="ขอเปลี่ยนระยะเวลานำเข้า"
      subtitle={factoryName}
      onClose={onCancel}
      testId="lead-time-change-dialog"
      size="md"
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onCancel}>ยกเลิก</Button>
          <Button type="button" variant="primary" disabled={!valid || pending} onClick={submit}>
            {pending ? 'กำลังบันทึก…' : existing ? 'บันทึกการแก้ไข' : 'ส่งคำขอ'}
          </Button>
        </>
      )}
    >
      <SafeForm className="grid gap-4" onSubmit={submit}>
        <div className="grid grid-cols-[repeat(2,minmax(0,1fr))] gap-3 sm:max-w-sm">
          <FormField label="ต่ำสุด (วัน)" htmlFor="pcr-lt-bulk-min">
            <input id="pcr-lt-bulk-min" type="number" min="1" max="3650" inputMode="numeric"
              value={bulkMin} onChange={(event) => changeBulk('min', event.target.value)} />
          </FormField>
          <FormField label="สูงสุด (วัน)" htmlFor="pcr-lt-bulk-max">
            <input id="pcr-lt-bulk-max" type="number" min="1" max="3650" inputMode="numeric"
              value={bulkMax} onChange={(event) => changeBulk('max', event.target.value)} />
          </FormField>
        </div>
        <FormField label="เหตุผล" htmlFor="pcr-lt-reason" hint="ฝ่ายขายจะเห็นเหตุผลนี้ตอนอนุมัติหรือไม่อนุมัติ">
          <textarea id="pcr-lt-reason" className="min-h-20" maxLength={1000}
            value={reason} onChange={(event) => setReason(event.target.value)} />
        </FormField>

        <div className="grid gap-2" data-testid="lead-time-change-lines">
          <p className="m-0 text-xs font-bold text-text-secondary">
            รายการของโรงงานนี้ — ติ๊กรายการที่ต้องการเปลี่ยน หรือกรอกค่าเฉพาะรายการ
          </p>
          {lines.map(({ item, name, current }) => {
            const row = rows[item.id];
            return (
              <div key={item.id}
                className="grid grid-cols-[minmax(0,1fr)] items-center gap-x-3 gap-y-1.5 rounded-md border border-border-subtle p-3 sm:grid-cols-[minmax(0,1fr)_auto]">
                <label className="flex min-w-0 items-start gap-2 text-sm text-text">
                  <input type="checkbox" className="mt-1 shrink-0" checked={row.ticked}
                    aria-label={`เลือกรายการ ${name}`} onChange={() => toggleRow(item.id)} />
                  <span className="min-w-0">
                    <span className="block truncate font-bold">{name}</span>
                    <span className="block text-xs text-text-muted">ปัจจุบัน {leadTimeWithUnit(current.min, current.max)}</span>
                  </span>
                </label>
                <div className="flex items-center gap-1.5 pl-6 sm:pl-0">
                  <input className="w-20" type="number" min="1" max="3650" inputMode="numeric"
                    disabled={!row.ticked} value={row.min}
                    aria-label={`ต่ำสุด ${name}`}
                    onChange={(event) => patchRow(item.id, { min: event.target.value })} />
                  <span className="text-xs text-text-muted" aria-hidden="true">–</span>
                  <input className="w-20" type="number" min="1" max="3650" inputMode="numeric"
                    disabled={!row.ticked} value={row.max}
                    aria-label={`สูงสุด ${name}`}
                    onChange={(event) => patchRow(item.id, { max: event.target.value })} />
                  <span className="text-xs text-text-muted">วัน</span>
                </div>
              </div>
            );
          })}
        </div>
      </SafeForm>
    </Modal>
  );
}
