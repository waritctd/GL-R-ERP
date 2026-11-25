import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { ThaiAddressFields, emptyThaiAddress, completeThaiAddress } from '../locations/ThaiAddressFields.jsx';

/** The request fields of a เรียน choice (DepositNoticeDraftRequest / RemainingInvoiceDraftRequest); both null keeps what
 * the server stores. */
export const KEEP_NAMES = Object.freeze({ addresseeCustomerId: null, addresseeReset: null });

/**
 * เรียน on a deposit notice or remaining invoice (feedback #74, Ploy: "select the same customer for quotation (default)
 * or create an entirely new customer"). Shows the customer the document is addressed to with its details and one
 * "เปลี่ยน" button; the chooser under it searches the customer list, offers the quotation's customer, and adds a new
 * customer (name required, the rest optional). The server prints that customer's list data.
 *
 * `addressee`: `{ name, detail, isDefault, pending }` as the page currently shows it.
 */
export function AddresseeSection({ idPrefix, addressee, onPick, onReset, showToast }) {
  const id = `${idPrefix}-addressee`;
  const [open, setOpen] = useState(false);
  const close = () => setOpen(false);
  return (
    <div className="col-span-full flex flex-col gap-3 rounded-md border border-border-subtle p-3" data-testid="customer-from-quotation">
      <div className="flex items-start justify-between gap-3" data-testid={`${id}-picker`}>
        <div className="min-w-0">
          <p className="m-0 text-xs font-bold text-text-muted">เรียน <span className="font-normal">· ลูกค้าที่ออกเอกสารให้</span></p>
          <p className="m-0 mt-0.5 break-words font-bold text-text" data-testid={`${id}-name`}>{addressee.name || '-'}</p>
          {addressee.detail ? (
            <p className="m-0 mt-0.5 whitespace-pre-line break-words text-xs text-text-muted">{addressee.detail}</p>
          ) : null}
          {addressee.pending || addressee.isDefault ? (
            <p className="m-0 mt-1 flex flex-wrap gap-1.5 text-2xs font-bold">
              {addressee.isDefault ? <span className="rounded-full bg-surface-subtle px-2 py-0.5 text-text-muted">ตามใบเสนอราคา</span> : null}
              {addressee.pending ? <span className="rounded-full bg-warning-bg-soft px-2 py-0.5 text-warning-dark">ยังไม่ได้บันทึก</span> : null}
            </p>
          ) : null}
        </div>
        <Button variant="secondary" className="shrink-0" data-testid={`${id}-change`} aria-expanded={open}
          onClick={() => setOpen((v) => !v)}>
          {open ? 'ปิด' : 'เปลี่ยน'}
        </Button>
      </div>
      {open ? (
        <Chooser id={id} showToast={showToast} showReset={!addressee.isDefault}
          onPick={(c) => { close(); onPick(c); }}
          onReset={() => { close(); onReset(); }} />
      ) : null}
    </div>
  );
}

const ITEM = 'border-t border-border-subtle first:border-t-0';
const OPTION = 'flex min-h-11 w-full cursor-pointer flex-col justify-center border-0 bg-transparent px-3 py-2 text-left text-sm text-text hover:bg-surface-hover focus-visible:bg-surface-hover';

/** Search the customer list or add a new customer; the quotation's customer sits on top. Inline (not a floating menu),
 * so nothing on the page is covered on a phone. */
function Chooser({ id, showReset, onPick, onReset, showToast }) {
  const [search, setSearch] = useState('');
  const [newName, setNewName] = useState(null);
  const typed = search.trim();
  const resultsQuery = useQuery({
    queryKey: queryKeys.customersSearch(typed),
    queryFn: () => api.customers.search(typed).then((r) => r.customers ?? []),
    enabled: typed !== '',
  });
  const results = typed ? resultsQuery.data ?? [] : [];
  // opened on purpose by "เปลี่ยน": the user types next
  const inputRef = useRef(null);
  useEffect(() => { if (newName == null) inputRef.current?.focus(); }, [newName]);

  if (newName != null) {
    return <NewCustomerForm id={id} initialName={newName} showToast={showToast} onCreated={onPick} onCancel={() => setNewName(null)} />;
  }
  return (
    <div className="flex flex-col gap-2 rounded-md bg-surface-muted p-3">
      <FormField label="ค้นหาลูกค้า หรือเพิ่มลูกค้าใหม่" htmlFor={`${id}-search`}>
        <input id={`${id}-search`} ref={inputRef} value={search} autoComplete="off"
          onChange={(e) => setSearch(e.target.value)} placeholder="ชื่อ หรือเลขประจำตัวผู้เสียภาษี" />
      </FormField>
      <ul className="m-0 list-none overflow-hidden rounded-md border border-border-subtle bg-surface p-0">
        {showReset ? (
          <li className={ITEM}>
            <button type="button" className={OPTION} data-testid={`${id}-reset-DEFAULT`} onClick={onReset}>
              <span className="font-bold text-primary">ใช้ลูกค้าตามใบเสนอราคา</span>
            </button>
          </li>
        ) : null}
        {results.map((c) => (
          <li key={c.id} className={ITEM}>
            <button type="button" className={OPTION} onClick={() => onPick(c)}>
              <strong className="break-words">{c.name}</strong>
              {[c.taxId, c.phone, c.address].some(Boolean) ? (
                <span className="break-words text-2xs text-text-muted">{[c.taxId, c.phone, c.address].filter(Boolean).join(' · ')}</span>
              ) : null}
            </button>
          </li>
        ))}
        {typed && resultsQuery.isFetching && results.length === 0 ? (
          <li className={`${ITEM} px-3 py-2 text-xs text-text-muted`}>กำลังค้นหา…</li>
        ) : null}
        <li className={ITEM}>
          <button type="button" className={OPTION} data-testid={`${id}-new`} onClick={() => setNewName(typed)}>
            <span className="break-words font-bold text-primary">{typed ? `เพิ่มลูกค้าใหม่ “${typed}”` : '+ เพิ่มลูกค้าใหม่'}</span>
            <span className="text-2xs text-text-muted">กรอกชื่อ ส่วนเลขภาษี ที่อยู่ เบอร์โทร อีเมล ใส่เมื่อมี</span>
          </button>
        </li>
      </ul>
    </div>
  );
}

/** A customer not yet in the list (feedback #74: "create an entirely new customer"): the name is required and every
 * other detail optional, so a rep in a hurry is never stuck. Saved through POST /api/customers (the deal-entry gate; the
 * server checks the email and keeps it on a contact), then picked. An address, when started, needs its province,
 * district and sub-district, so it prints whole. */
function NewCustomerForm({ id, initialName, onCreated, onCancel, showToast }) {
  const [value, setValue] = useState(() => ({ name: initialName, taxId: '', phone: '', email: '', ...emptyThaiAddress() }));
  const [saving, setSaving] = useState(false);
  const addressStarted = Boolean(value.addressLine.trim() || value.provinceCode || value.districtCode || value.subdistrictCode);
  const addressReady = !addressStarted || completeThaiAddress(value);
  const ready = value.name.trim() !== '' && addressReady;

  async function save() {
    setSaving(true);
    try {
      const res = await api.customers.create({
        name: value.name.trim(),
        taxId: value.taxId.trim() || null,
        phone: value.phone.trim() || null,
        email: value.email.trim() || null,
        ...(addressStarted ? {
          addressLine: value.addressLine.trim(),
          provinceCode: value.provinceCode,
          districtCode: value.districtCode,
          subdistrictCode: value.subdistrictCode,
          postalCode: value.postalCode || null,
        } : {}),
      });
      onCreated(res.customer);
    } catch (error) {
      showToast?.('error', error.message || 'เพิ่มลูกค้าใหม่ไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  const set = (key) => (e) => setValue((v) => ({ ...v, [key]: e.target.value }));
  return (
    <div className="grid gap-3 rounded-md bg-surface-muted p-3" data-testid={`${id}-new-form`}>
      <p className="m-0 text-xs text-text-muted">ลูกค้าใหม่จะอยู่ในรายชื่อลูกค้า ใช้ซ้ำได้ในเอกสารครั้งต่อไป</p>
      <FormField label="ชื่อบริษัท หรือชื่อบุคคล" htmlFor={`${id}-new-name`} required>
        <input id={`${id}-new-name`} value={value.name} disabled={saving} maxLength={200} onChange={set('name')} />
      </FormField>
      <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
        <FormField label="เลขประจำตัวผู้เสียภาษี (ไม่บังคับ)" htmlFor={`${id}-new-tax`}>
          <input id={`${id}-new-tax`} value={value.taxId} disabled={saving} inputMode="numeric" maxLength={13}
            onChange={set('taxId')} placeholder="0000000000000" />
        </FormField>
        <FormField label="เบอร์โทร (ไม่บังคับ)" htmlFor={`${id}-new-phone`}>
          <input id={`${id}-new-phone`} type="tel" inputMode="tel" autoComplete="off" value={value.phone}
            disabled={saving} maxLength={50} onChange={set('phone')} />
        </FormField>
        <div className="col-span-full">
          <FormField label="อีเมล (ไม่บังคับ)" htmlFor={`${id}-new-email`}>
            <input id={`${id}-new-email`} type="email" inputMode="email" autoComplete="off" value={value.email}
              disabled={saving} maxLength={200} onChange={set('email')} />
          </FormField>
        </div>
      </div>
      <div className="grid gap-2">
        <p className="m-0 text-xs text-text-muted">ที่อยู่ไม่บังคับ ถ้าใส่ ให้เลือกจังหวัด อำเภอ/เขต และตำบล/แขวงให้ครบ</p>
        <ThaiAddressFields value={value} onChange={(patch) => setValue((v) => ({ ...v, ...patch }))} disabled={saving} />
        {!addressReady ? <p className="m-0 text-xs font-bold text-warning-dark">เลือกจังหวัด อำเภอ/เขต และตำบล/แขวงให้ครบ หรือเว้นที่อยู่ว่างไว้</p> : null}
      </div>
      <div className="flex flex-wrap justify-end gap-2">
        <Button variant="secondary" disabled={saving} onClick={onCancel}>ย้อนกลับ</Button>
        <Button variant="primary" loading={saving} disabled={!ready || saving} onClick={save}>ใช้ลูกค้านี้</Button>
      </div>
    </div>
  );
}
