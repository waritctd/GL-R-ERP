import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api } from '../../api/index.js';
import { FormField } from '../../components/common/FormField.jsx';
import { QUOTATION_FIELD_IDS } from './quotationMeta.js';

function editsFrom(customer) {
  return {
    taxId: customer?.taxId ?? '',
    phone: customer?.phone ?? '',
    address: customer?.address ?? '',
  };
}

/**
 * The SELECTED customer's เลขที่ผู้เสียภาษี / โทร. / ที่อยู่, editable in place and saved back to the
 * customer master on blur (PUT /api/customers/{id}).
 *
 * History: F7 (owner, 2026-09-10) made tax id and phone editable inside DealCustomerCard. The owner
 * then asked (2026-09-11) for "a way for the sales to fill in the customer address" and for the
 * details to "autofill in later on if they get the same customer" — which is exactly what writing
 * to the customer MASTER (not the quotation) buys: the next quotation for that customer starts
 * filled. This component is those fields lifted OUT of DealCustomerCard, because the editor also
 * needs them on the `?ticket=` and existing-draft paths where that card is not rendered — one
 * component, not a pasted twin that a later fix would miss.
 *
 * All three stay OPTIONAL (a customer without a tax id is still quotable — F7). The document
 * snapshots these columns on every DRAFT save (DealQuotationService#update re-reads the live
 * customer), so the caller marks the quotation dirty after a save here; that next save is what
 * carries the correction onto the printed page.
 *
 * Contract: `customer` is the record (or a stand-in carrying `id`); `onChange(next)` receives the
 * record the SERVER says it stored (optimistic first, rolled back on refusal).
 */
export function CustomerDetailsFields({ customer, onChange, showToast, disabled = false }) {
  // Own local state rather than editing `customer` on every keystroke, so a half-typed value never
  // becomes what a save would snapshot. Re-seeded whenever the underlying record changes —
  // including from a save's own response, which is what makes the update "verified" rather than
  // merely optimistic.
  const [edits, setEdits] = useState(() => editsFrom(customer));
  const [savingField, setSavingField] = useState(null);
  const queryClient = useQueryClient();
  useEffect(() => {
    setEdits({ taxId: customer?.taxId ?? '', phone: customer?.phone ?? '', address: customer?.address ?? '' });
  }, [customer?.id, customer?.taxId, customer?.phone, customer?.address]);

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
    onChange({ ...customer, [field]: next || null });
    try {
      const res = await api.customers.update(customer.id, { [field]: next });
      onChange(res?.customer ?? { ...customer, [field]: next || null });
      // Any cached customer search / record still holds the stale row.
      queryClient.invalidateQueries({ queryKey: ['customers'] });
    } catch (error) {
      onChange(restore);
      setEdits((prev) => ({ ...prev, [field]: previous }));
      showToast?.('error', error.message || 'บันทึกข้อมูลลูกค้าไม่สำเร็จ');
    } finally {
      setSavingField(null);
    }
  }

  const hint = 'แก้ไขได้ บันทึกกลับไปที่ข้อมูลลูกค้าอัตโนมัติ ใช้ต่อได้ในใบเสนอราคาถัดไป';
  return (
    <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1" data-testid="customer-details">
      <FormField label="เลขที่ผู้เสียภาษี" htmlFor={QUOTATION_FIELD_IDS.customerTaxId} hint={hint}>
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
            onChange={(e) => setEdits((prev) => ({ ...prev, address: e.target.value }))}
            onBlur={() => saveField('address')}
          />
        </FormField>
      </div>
    </div>
  );
}
