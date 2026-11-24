import { useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { ThaiAddressFields, emptyThaiAddress, completeThaiAddress } from '../locations/ThaiAddressFields.jsx';

/** ออกในนาม a name not yet in the customer list (feedback #74): adds it to the list (POST /api/customers, the deal-entry
 * gate) and hands the new customer back, so the notice still prints master data. */
export function NewPayerForm({ initialName = '', onCreated, onCancel, showToast }) {
  const [value, setValue] = useState(() => ({ name: initialName, taxId: '', ...emptyThaiAddress() }));
  const [saving, setSaving] = useState(false);
  const ready = value.name.trim() && completeThaiAddress(value);

  async function save() {
    setSaving(true);
    try {
      const res = await api.customers.create({
        name: value.name.trim(),
        taxId: value.taxId.trim() || null,
        addressLine: value.addressLine.trim(),
        provinceCode: value.provinceCode,
        districtCode: value.districtCode,
        subdistrictCode: value.subdistrictCode,
        postalCode: value.postalCode || null,
      });
      onCreated(res.customer);
    } catch (error) {
      showToast?.('error', error.message || 'เพิ่มชื่อใหม่ไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="col-span-full grid gap-3 rounded-md border border-border-subtle p-3" data-testid="new-payer-form">
      <FormField label="ชื่อ (บริษัท หรือ บุคคล)" htmlFor="new-payer-name" required>
        <input id="new-payer-name" value={value.name} disabled={saving} maxLength={255}
          onChange={(e) => setValue((v) => ({ ...v, name: e.target.value }))} />
      </FormField>
      <FormField label="เลขประจำตัวผู้เสียภาษี" htmlFor="new-payer-tax">
        <input id="new-payer-tax" value={value.taxId} disabled={saving} inputMode="numeric" maxLength={13}
          onChange={(e) => setValue((v) => ({ ...v, taxId: e.target.value }))} placeholder="0000000000000" />
      </FormField>
      <ThaiAddressFields value={value} onChange={(patch) => setValue((v) => ({ ...v, ...patch }))} disabled={saving} />
      <div className="flex flex-wrap justify-end gap-2">
        <Button variant="secondary" disabled={saving} onClick={onCancel}>ยกเลิก</Button>
        <Button variant="primary" loading={saving} disabled={!ready || saving} onClick={save}>ใช้ชื่อนี้</Button>
      </div>
    </div>
  );
}
