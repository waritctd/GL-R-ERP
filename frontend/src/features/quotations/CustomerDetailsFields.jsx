import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Modal } from '../../components/common/Modal.jsx';
import { Button } from '../../components/common/Button.jsx';
import { ThaiAddressFields, emptyThaiAddress, completeThaiAddress } from '../locations/ThaiAddressFields.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { QUOTATION_FIELD_IDS } from './quotationMeta.js';

const EDIT_FIELDS = ['name', 'taxId', 'phone', 'address'];

function editsFrom(customer) {
  return {
    name: customer?.name ?? '',
    taxId: customer?.taxId ?? '',
    phone: customer?.phone ?? '',
    address: customer?.address ?? '',
  };
}

/**
 * The SELECTED customer's ชื่อ / เลขที่ผู้เสียภาษี / โทร. / ที่อยู่, editable in place and saved back
 * to the customer master on blur (PUT /api/customers/{id}).
 *
 * History: F7 (owner, 2026-09-10) made tax id and phone editable inside DealCustomerCard. The owner
 * then asked (2026-09-11) for "a way for the sales to fill in the customer address" and for the
 * details to "autofill in later on if they get the same customer" — which is exactly what writing
 * to the customer MASTER (not the quotation) buys: the next quotation for that customer starts
 * filled. This component is those fields lifted OUT of DealCustomerCard, because the editor also
 * needs them on the `?ticket=` and existing-draft paths where that card is not rendered — one
 * component, not a pasted twin that a later fix would miss.
 *
 * `name` added (owner feedback, 2026-09-14 — "sometimes there's a typo in the name so they should
 * be able to correct it"): `PUT /api/customers/{id}` already accepted it (`UpdateCustomerRequest
 * .name`, `CustomerController#update`), it was just never surfaced here — unlike the other three,
 * the backend refuses a BLANK name (400 "กรุณาระบุชื่อลูกค้า", NOT NULL column), which `saveField`'s
 * existing generic error handling already covers correctly (restores the previous value, shows the
 * server's own message) with no special-casing needed.
 *
 * taxId/phone/address stay OPTIONAL (a customer without a tax id is still quotable — F7). The
 * document snapshots all four columns on every DRAFT save (DealQuotationService#update re-reads
 * the live customer), so the caller marks the quotation dirty after a save here; that next save is
 * what carries the correction onto the printed page.
 *
 * Contract: `customer` is the record (or a stand-in carrying `id`); `onChange(next)` receives the
 * record the SERVER says it stored (optimistic first, rolled back on refusal).
 */
export function CustomerDetailsFields({ customer, onChange, showToast, disabled = false }) {
  // Own local state rather than editing `customer` on every keystroke, so a half-typed value never
  // becomes what a save would snapshot. Re-seeded per FIELD whenever the underlying record changes
  // — including from a save's own response, which is what makes the update "verified" rather than
  // merely optimistic — but only for a field this component itself last seeded and the rep has not
  // since edited. `customer` is QuotationEditorPage's `summaryCustomer`, which can swap from the
  // quotation's frozen snapshot to the live customer-record query mid-session; without the
  // last-seeded check that resync silently overwrote whatever the rep had typed but not yet
  // blurred.
  const [edits, setEdits] = useState(() => editsFrom(customer));
  const [savingField, setSavingField] = useState(null);
  const queryClient = useQueryClient();
  const [addressEdit, setAddressEdit] = useState(null);
  // What this component itself last put into each field (the record's value, or a save's own
  // in-flight/rolled-back value) — the baseline a field is compared against to tell "still what we
  // seeded" (safe to re-seed) from "the rep typed something else" (leave it alone). `id` tracks
  // which customer that baseline belongs to.
  const lastSeededRef = useRef({ id: customer?.id, ...editsFrom(customer) });

  async function saveAddress() {
    if (!completeThaiAddress(addressEdit)) return;
    setSavingField('address');
    try {
      const res = await api.customers.update(customer.id, addressEdit);
      // The structured-address modal is the authoritative source for ที่อยู่ once used — apply its
      // result to the field directly (and mark it clean) rather than relying on the dirty check
      // above, since the free-text ที่อยู่ field may have been left dirty from typing before the
      // modal was ever opened.
      const nextAddress = res.customer?.address ?? '';
      setEdits((prev) => ({ ...prev, address: nextAddress }));
      lastSeededRef.current = { ...lastSeededRef.current, address: nextAddress };
      onChange(res.customer);
      queryClient.invalidateQueries({ queryKey: ['customers'] });
      setAddressEdit(null);
    } catch (error) {
      showToast?.('error', error.message || 'บันทึกที่อยู่ไม่สำเร็จ');
    } finally { setSavingField(null); }
  }

  useEffect(() => {
    // Read fields directly (not via `editsFrom(customer)`) so eslint's exhaustive-deps rule can
    // verify this against the deps array below field by field, same as the deps themselves.
    const record = {
      name: customer?.name ?? '', taxId: customer?.taxId ?? '', phone: customer?.phone ?? '',
      address: customer?.address ?? '',
    };
    // Snapshot the OLD baseline before reassigning the ref below. `setEdits`'s updater runs
    // asynchronously (whenever React processes the queued update) — by then `lastSeededRef.current`
    // has already been reassigned to the NEW record a few lines down, so reading `.current` from
    // inside the updater would compare against the wrong baseline. Closing over this snapshot
    // instead keeps the comparison correct regardless of when React actually calls the updater.
    const wasSeeded = lastSeededRef.current;
    const idChanged = customer?.id !== wasSeeded.id;
    setEdits((prev) => {
      if (idChanged) return record; // a different customer — the rep's dirty text belonged to the old one
      const merged = { ...prev };
      for (const field of EDIT_FIELDS) {
        if (prev[field] === wasSeeded[field]) merged[field] = record[field]; // clean — take the record
        // else: the rep has typed something else since it was seeded — keep their text
      }
      return merged;
    });
    lastSeededRef.current = { id: customer?.id, ...record };
  }, [customer?.id, customer?.name, customer?.taxId, customer?.phone, customer?.address]);

  /**
   * Persists ONE field on blur. Sends only that field, never the whole record: the endpoint has
   * PATCH semantics (an omitted key is left alone), so a one-field PUT cannot clobber a value some
   * other session changed meanwhile. A 403 — the deal-entry gate is real (DealEntryAccess) —
   * surfaces the backend's own Thai message and puts the previous value back, so the rep never
   * walks away believing a correction was saved.
   */
  async function saveField(field) {
    if (!customer?.id) return;
    const previous = customer[field] ?? '';
    // An address keeps its internal line breaks (it is printed as typed); only the ends are trimmed.
    const next = (edits[field] ?? '').trim();
    if (next === previous.trim()) return; // untouched (or re-typed identically) — no request at all
    const restore = customer;
    setSavingField(field);
    // Mark this field clean — both the ref AND the displayed text — at `next` (the value being
    // sent) BEFORE the optimistic onChange below. `next` is `edits[field]` trimmed, so a rep who
    // left whitespace at the ends needs the display normalised to match too, or it would never
    // again equal what the ref/record hold and would read as "still dirty" forever. Marking both
    // like this is what lets the record-resync effect above — which compares `edits[field]`
    // against this ref to tell a rep's own dirty edit from a value this component put there
    // itself — treat the optimistic update, and then the server's own response (even normalised
    // differently, e.g. a reformatted taxId), as safe to apply.
    lastSeededRef.current = { ...lastSeededRef.current, [field]: next };
    setEdits((prev) => ({ ...prev, [field]: next }));
    onChange({ ...customer, [field]: next || null });
    try {
      const res = await api.customers.update(customer.id, { [field]: next });
      onChange(res?.customer ?? { ...customer, [field]: next || null });
      // Any cached customer search / record still holds the stale row.
      queryClient.invalidateQueries({ queryKey: ['customers'] });
    } catch (error) {
      onChange(restore);
      setEdits((prev) => ({ ...prev, [field]: previous }));
      lastSeededRef.current = { ...lastSeededRef.current, [field]: previous };
      showToast?.('error', error.message || 'บันทึกข้อมูลลูกค้าไม่สำเร็จ');
    } finally {
      setSavingField(null);
    }
  }

  const hint = 'แก้ไขได้ บันทึกกลับไปที่ข้อมูลลูกค้าอัตโนมัติ ใช้ต่อได้ในใบเสนอราคาถัดไป';
  return (
    <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1" data-testid="customer-details">
      {/* Owner feedback (2026-09-14): "sometimes there's a typo in the name so they should be able
          to correct it" — a plain rename of the customer MASTER record (not a different customer,
          not a deal reassignment), same PUT the other three fields already use. 200 = Update
          CustomerRequest's @Size; the server 400s a blank ("กรุณาระบุชื่อลูกค้า") rather than
          letting a NOT NULL column violation surface. */}
      <div className="col-span-full">
        <FormField label="ชื่อลูกค้า" htmlFor={QUOTATION_FIELD_IDS.customerName} hint={hint}>
          <input
            id={QUOTATION_FIELD_IDS.customerName}
            value={edits.name}
            maxLength={200}
            disabled={disabled || savingField === 'name'}
            onChange={(e) => setEdits((prev) => ({ ...prev, name: e.target.value }))}
            onBlur={() => saveField('name')}
            onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }}
          />
        </FormField>
      </div>
      <FormField label="เลขที่ผู้เสียภาษี" htmlFor={QUOTATION_FIELD_IDS.customerTaxId}>
        <input
          id={QUOTATION_FIELD_IDS.customerTaxId}
          value={edits.taxId}
          placeholder="0105xxxxxxxxx"
          maxLength={20}
          disabled={disabled || savingField === 'taxId'}
          onChange={(e) => setEdits((prev) => ({ ...prev, taxId: e.target.value }))}
          onBlur={() => saveField('taxId')}
          onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }}
        />
      </FormField>
      <FormField label="โทร." htmlFor={QUOTATION_FIELD_IDS.customerPhone}>
        <input
          id={QUOTATION_FIELD_IDS.customerPhone}
          value={edits.phone}
          placeholder="02-xxx-xxxx"
          maxLength={50}
          disabled={disabled || savingField === 'phone'}
          onChange={(e) => setEdits((prev) => ({ ...prev, phone: e.target.value }))}
          onBlur={() => saveField('phone')}
          onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }}
        />
      </FormField>
      {/* Multi-line: the reference documents print a two-line Thai address. No Enter-to-blur here,
          because Enter is how a second line is typed. 2000 = CreateCustomerRequest's @Size. */}
      <div className="col-span-full">
        <FormField label="ที่อยู่" htmlFor={QUOTATION_FIELD_IDS.customerAddress}>
          <textarea
            id={QUOTATION_FIELD_IDS.customerAddress}
            rows={3}
            className="min-h-20"
            value={edits.address}
            placeholder="เลขที่ ถนน แขวง/ตำบล เขต/อำเภอ จังหวัด รหัสไปรษณีย์"
            maxLength={2000}
            disabled={disabled || savingField === 'address'}
            readOnly={Boolean(customer?.provinceCode)}
            onChange={(e) => setEdits((prev) => ({ ...prev, address: e.target.value }))}
            onBlur={() => saveField('address')}
          />
        </FormField>
        <Button variant="text" disabled={disabled || Boolean(savingField)} onClick={() => {
          const draft = emptyThaiAddress();
          Object.keys(draft).forEach((key) => { draft[key] = customer[key] ?? ''; });
          setAddressEdit(draft);
        }}>แก้ไขที่อยู่แบบแยกจังหวัด</Button>
        {addressEdit ? <Modal title="แก้ไขที่อยู่ลูกค้า" onClose={() => { if (!savingField) setAddressEdit(null); }}>
          {!customer.provinceCode && customer.address ? <p className="mb-3 whitespace-pre-wrap text-sm text-text-muted">ที่อยู่เดิม: {customer.address}</p> : null}
          <ThaiAddressFields value={addressEdit} onChange={(patch) => setAddressEdit((prev) => ({ ...prev, ...patch }))} disabled={Boolean(savingField)} />
          <div className="mt-4 flex flex-wrap justify-end gap-2">
            <Button disabled={!completeThaiAddress(addressEdit) || Boolean(savingField)} loading={savingField === 'address'} onClick={saveAddress}>บันทึกที่อยู่</Button>
            <Button variant="secondary" disabled={Boolean(savingField)} onClick={() => setAddressEdit(null)}>ยกเลิก</Button>
          </div>
        </Modal> : null}
      </div>
    </div>
  );
}
