import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';

/** The request fields of a เรียน / ออกในนาม choice (DepositNoticeDraftRequest / RemainingInvoiceDraftRequest); all null
 * keeps what the server stores. */
export const KEEP_NAMES = Object.freeze({ addresseeCustomerId: null, addresseeReset: null, payerCustomerId: null, payerReset: null });

/** เรียน as a picker: the quotation's customer by default (#65). `onPick` gets the customer-list row. */
export function AddresseePicker({ name, isDefault, unsaved, onPick, onReset, showToast, idPrefix }) {
  return (
    <NamePicker label="เรียน" idPrefix={`${idPrefix}-addressee`} name={name} unsaved={unsaved} showToast={showToast}
      note={isDefault ? 'ลูกค้าในใบเสนอราคา' : null} onPick={onPick}
      resets={isDefault ? [] : [{ key: 'DEFAULT', label: 'ใช้ลูกค้าในใบเสนอราคา' }]} onReset={onReset} />
  );
}

/** ออกในนาม as a picker: the person the document is issued to, printed on its own line under เรียน; the quotation's
 * contact by default, none means the same as เรียน. */
export function PayerPicker({ name, unsaved, onPick, onReset, showToast, idPrefix }) {
  const resets = [{ key: 'DEFAULT', label: 'ใช้ผู้ติดต่อในใบเสนอราคา' }];
  if (name) resets.push({ key: 'NONE', label: 'คนเดียวกับเรียน' });
  return (
    <NamePicker label="ออกในนาม" idPrefix={`${idPrefix}-payer`} name={name} unsaved={unsaved} showToast={showToast}
      note={name ? null : 'คนเดียวกับเรียน'} onPick={onPick} resets={resets} onReset={onReset} />
  );
}

/**
 * One choosable name on a customer document (feedback #74, Ploy: "make everything choosable just put in the default
 * value first"): shows the current value, lets the user pick another from the customer list or add a new name to it
 * (name only, email and phone optional), or go back to a default. The server prints list data, never typed text.
 */
function NamePicker({ label, idPrefix, name, note, unsaved, resets, onPick, onReset, showToast }) {
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
  const open = picking || newName != null;

  function close() {
    setPicking(false);
    setSearch('');
    setNewName(null);
  }

  function pick(customer) {
    close();
    onPick(customer);
  }

  function startNewName() {
    setNewName(typed);
    setSearch('');
  }

  return (
    <div className="col-span-full flex flex-col gap-2" data-testid={`${idPrefix}-picker`}>
      <div className="flex flex-wrap items-center gap-2">
        <p className="m-0 min-w-0 text-sm text-text">
          {label} <strong data-testid={`${idPrefix}-name`}>{name || '-'}</strong>
          {note ? <span className="text-text-muted"> ({note})</span> : null}
          {unsaved ? <span className="text-warning-dark"> · กดบันทึกเพื่อใช้</span> : null}
        </p>
        <Button variant="secondary" data-testid={`${idPrefix}-change`} onClick={() => (open ? close() : setPicking(true))}>
          {open ? 'ยกเลิก' : 'เปลี่ยน'}
        </Button>
        {!open ? resets.map((r) => (
          <Button key={r.key} variant="text" data-testid={`${idPrefix}-reset-${r.key}`} onClick={() => onReset(r.key)}>
            {r.label}
          </Button>
        )) : null}
      </div>
      {picking && newName == null ? (
        <div className="relative">
          <FormField label="ค้นหาลูกค้า (master)" htmlFor={`${idPrefix}-search`}>
            <input id={`${idPrefix}-search`} value={search} autoComplete="off"
              onChange={(e) => setSearch(e.target.value)} placeholder="ชื่อ หรือ เลขภาษี…" />
          </FormField>
          {typed ? (
            <div className="absolute left-0 right-0 top-full z-50 max-h-[220px] overflow-y-auto rounded-[6px] border border-border-subtle bg-surface shadow-[0_4px_12px_rgba(0,0,0,0.1)]">
              {results.map((c) => (
                <button key={c.id} type="button" onClick={() => pick(c)}
                  className="w-full cursor-pointer border-0 border-b border-surface-subtle bg-transparent px-3 py-2 text-left text-sm text-text">
                  <strong className="block">{c.name}</strong>
                  <span className="text-2xs text-text-muted">{[c.taxId, c.phone, c.address].filter(Boolean).join(' · ')}</span>
                </button>
              ))}
              <button type="button" data-testid={`${idPrefix}-new`} onClick={startNewName}
                className="w-full cursor-pointer border-0 bg-transparent px-3 py-2 text-left text-sm font-bold text-primary">
                {`+ เพิ่มชื่อใหม่ "${typed}"`}
              </button>
            </div>
          ) : (
            <Button variant="text" className="mt-2" data-testid={`${idPrefix}-new`} onClick={startNewName}>+ เพิ่มชื่อใหม่</Button>
          )}
        </div>
      ) : null}
      {newName != null ? (
        <NewNameForm idPrefix={idPrefix} initialName={newName} showToast={showToast} onCreated={pick} onCancel={close} />
      ) : null}
    </div>
  );
}

/** A name not yet in the customer list, added in a hurry: the name only, email and phone optional (Ploy). Saved through
 * POST /api/customers (the deal-entry gate; the server checks the email and keeps it on a contact), then picked. */
function NewNameForm({ idPrefix, initialName, onCreated, onCancel, showToast }) {
  const [value, setValue] = useState(() => ({ name: initialName, email: '', phone: '' }));
  const [saving, setSaving] = useState(false);
  const ready = value.name.trim() !== '';

  async function save() {
    setSaving(true);
    try {
      const res = await api.customers.create({
        name: value.name.trim(),
        email: value.email.trim() || null,
        phone: value.phone.trim() || null,
      });
      onCreated(res.customer);
    } catch (error) {
      showToast?.('error', error.message || 'เพิ่มชื่อใหม่ไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="grid gap-3 rounded-md border border-border-subtle p-3" data-testid={`${idPrefix}-new-form`}>
      <FormField label="ชื่อ (บริษัท หรือ บุคคล)" htmlFor={`${idPrefix}-new-name`} required>
        <input id={`${idPrefix}-new-name`} value={value.name} disabled={saving} maxLength={200}
          onChange={(e) => setValue((v) => ({ ...v, name: e.target.value }))} />
      </FormField>
      <FormField label="อีเมล (ถ้ามี)" htmlFor={`${idPrefix}-new-email`}>
        <input id={`${idPrefix}-new-email`} type="email" inputMode="email" autoComplete="off" value={value.email}
          disabled={saving} maxLength={200} onChange={(e) => setValue((v) => ({ ...v, email: e.target.value }))} />
      </FormField>
      <FormField label="เบอร์โทร (ถ้ามี)" htmlFor={`${idPrefix}-new-phone`}>
        <input id={`${idPrefix}-new-phone`} type="tel" inputMode="tel" autoComplete="off" value={value.phone}
          disabled={saving} maxLength={50} onChange={(e) => setValue((v) => ({ ...v, phone: e.target.value }))} />
      </FormField>
      <div className="flex flex-wrap justify-end gap-2">
        <Button variant="secondary" disabled={saving} onClick={onCancel}>ยกเลิก</Button>
        <Button variant="primary" loading={saving} disabled={!ready || saving} onClick={save}>ใช้ชื่อนี้</Button>
      </div>
    </div>
  );
}
