import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { ThaiAddressFields, emptyThaiAddress, completeThaiAddress } from '../locations/ThaiAddressFields.jsx';

/**
 * ออกในนาม (feedback #74): a customer document defaults to its quotation's customer (#65); the user may issue it in
 * another name picked from the customer list, or add a new name to that list. The server prints the chosen customer's
 * list data, never typed text. Used by the deposit notice and the remaining invoice.
 *
 * `payerId` is the unsaved choice, `savedPayerId` the stored one (the hint to press save shows while they differ).
 */
export function IssueInNamePicker({ quotationNumber, payerId, payerName, savedPayerId, onPick, onClear, showToast, idPrefix }) {
  const [picking, setPicking] = useState(false);
  const [search, setSearch] = useState('');
  const [newName, setNewName] = useState(null);
  const typed = search.trim();
  const resultsQuery = useQuery({
    queryKey: queryKeys.customersSearch(typed),
    queryFn: () => api.customers.search(typed).then((r) => r.customers ?? []),
    enabled: picking && typed !== '',
  });
  const results = typed ? resultsQuery.data ?? [] : [];

  function pick(customer) {
    setPicking(false);
    setSearch('');
    setNewName(null);
    onPick(customer);
  }

  function startNewName() {
    setNewName(typed);
    setSearch('');
  }

  const unsaved = (payerId ?? null) !== (savedPayerId ?? null);
  return (
    <div className="col-span-full flex flex-col gap-2" data-testid="customer-from-quotation">
      <div className="flex flex-wrap items-center gap-2">
        <p className="m-0 text-sm text-text-muted">
          {payerId == null
            ? `ชื่อ เลขผู้เสียภาษี และที่อยู่ดึงจากใบเสนอราคา ${quotationNumber ?? ''} (แก้ไม่ได้)`
            : `ออกในนาม ${payerName ?? ''} · อ้างอิงใบเสนอราคา ${quotationNumber ?? ''}${unsaved ? ' (กดบันทึกเพื่อใช้)' : ''}`}
        </p>
        {payerId == null ? (
          <Button variant="secondary" data-testid="pick-payer"
            onClick={() => { setPicking((v) => !v); setNewName(null); setSearch(''); }}>
            {picking ? 'ยกเลิก' : 'ออกในนาม'}
          </Button>
        ) : (
          <Button variant="secondary" data-testid="clear-payer" onClick={() => { setPicking(false); onClear(); }}>
            ใช้ลูกค้าในใบเสนอราคา
          </Button>
        )}
      </div>
      {payerId == null && unsaved ? (
        <p className="m-0 text-sm text-warning-dark">กดบันทึกเพื่อกลับไปใช้ลูกค้าในใบเสนอราคา</p>
      ) : null}
      {picking && newName == null ? (
        <div className="relative">
          <FormField label="ค้นหาลูกค้า (master)" htmlFor={`${idPrefix}-payer-search`}>
            <input id={`${idPrefix}-payer-search`} value={search} autoComplete="off"
              onChange={(e) => setSearch(e.target.value)} placeholder="ชื่อบริษัท หรือ เลขภาษี…" />
          </FormField>
          {typed ? (
            <div className="absolute left-0 right-0 top-full z-50 max-h-[220px] overflow-y-auto rounded-[6px] border border-border-subtle bg-surface shadow-[0_4px_12px_rgba(0,0,0,0.1)]">
              {results.map((c) => (
                <button key={c.id} type="button" onClick={() => pick(c)}
                  className="w-full cursor-pointer border-0 border-b border-surface-subtle bg-transparent px-3 py-2 text-left text-sm text-text">
                  <strong className="block">{c.name}</strong>
                  <span className="text-2xs text-text-muted">{c.taxId} · {c.address}</span>
                </button>
              ))}
              <button type="button" data-testid="new-payer" onClick={startNewName}
                className="w-full cursor-pointer border-0 bg-transparent px-3 py-2 text-left text-sm font-bold text-primary">
                {`+ เพิ่มชื่อใหม่ "${typed}"`}
              </button>
            </div>
          ) : (
            <Button variant="text" className="mt-2" data-testid="new-payer" onClick={startNewName}>+ เพิ่มชื่อใหม่</Button>
          )}
        </div>
      ) : null}
      {newName != null ? (
        <NewNameForm idPrefix={idPrefix} initialName={newName} showToast={showToast}
          onCreated={pick} onCancel={() => setNewName(null)} />
      ) : null}
    </div>
  );
}

/** A name not yet in the customer list: added through POST /api/customers (the deal-entry gate), then picked. */
function NewNameForm({ idPrefix, initialName, onCreated, onCancel, showToast }) {
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
    <div className="grid gap-3 rounded-md border border-border-subtle p-3" data-testid="new-payer-form">
      <FormField label="ชื่อ (บริษัท หรือ บุคคล)" htmlFor={`${idPrefix}-new-payer-name`} required>
        <input id={`${idPrefix}-new-payer-name`} value={value.name} disabled={saving} maxLength={255}
          onChange={(e) => setValue((v) => ({ ...v, name: e.target.value }))} />
      </FormField>
      <FormField label="เลขประจำตัวผู้เสียภาษี" htmlFor={`${idPrefix}-new-payer-tax`}>
        <input id={`${idPrefix}-new-payer-tax`} value={value.taxId} disabled={saving} inputMode="numeric" maxLength={13}
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
