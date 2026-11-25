import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';

/** The request fields of a เรียน / ออกในนาม choice (DepositNoticeDraftRequest / RemainingInvoiceDraftRequest); all null
 * keeps what the server stores. */
export const KEEP_NAMES = Object.freeze({ addresseeCustomerId: null, addresseeReset: null, payerCustomerId: null, payerReset: null });

/**
 * เรียน and ออกในนาม on a customer document (feedback #74, Ploy: "make everything choosable just put in the default value
 * first"). Each row shows the current value with its details and one "เปลี่ยน" button; the chooser under it searches the
 * customer list, offers the defaults, and takes a typed name (added to the list with optional email and phone). The
 * server prints list data, never typed text.
 *
 * `addressee` / `payer`: `{ name, detail, isDefault, pending }` as the page currently shows them.
 */
export function IssueNamesSection({ idPrefix, addressee, payer, onPickAddressee, onResetAddressee, onPickPayer, onResetPayer,
  showToast }) {
  return (
    <div className="col-span-full flex flex-col rounded-md border border-border-subtle" data-testid="customer-from-quotation">
      <NameRow
        id={`${idPrefix}-addressee`}
        label="เรียน"
        hint="ผู้รับเอกสาร (บริษัท / ลูกค้า)"
        value={addressee}
        emptyText="-"
        defaultTag="ตามใบเสนอราคา"
        shortcuts={addressee.isDefault ? [] : [{ key: 'DEFAULT', label: 'ใช้ลูกค้าตามใบเสนอราคา' }]}
        onPick={onPickAddressee}
        onShortcut={() => onResetAddressee()}
        showToast={showToast}
      />
      <NameRow
        id={`${idPrefix}-payer`}
        label="ออกในนาม"
        hint="ผู้ลงนาม / ผู้ชำระเงิน"
        value={payer}
        emptyText="บุคคลเดียวกับผู้รับเอกสาร"
        shortcuts={[
          { key: 'DEFAULT', label: 'ใช้ผู้ติดต่อตามใบเสนอราคา' },
          ...(payer.name ? [{ key: 'NONE', label: 'ไม่ระบุ (บุคคลเดียวกับผู้รับเอกสาร)' }] : []),
        ]}
        onPick={onPickPayer}
        onShortcut={onResetPayer}
        showToast={showToast}
        bordered
      />
    </div>
  );
}

function NameRow({ id, label, hint, value, emptyText, defaultTag, shortcuts, onPick, onShortcut, showToast, bordered = false }) {
  const [open, setOpen] = useState(false);
  const close = () => setOpen(false);
  return (
    <div className={`flex flex-col gap-3 p-3 ${bordered ? 'border-t border-border-subtle' : ''}`} data-testid={`${id}-picker`}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="m-0 text-xs font-bold text-text-muted">{label} <span className="font-normal">· {hint}</span></p>
          <p className="m-0 mt-0.5 break-words font-bold text-text" data-testid={`${id}-name`}>
            {value.name || <span className="font-normal text-text-muted">{emptyText}</span>}
          </p>
          {value.detail ? <p className="m-0 mt-0.5 whitespace-pre-line break-words text-xs text-text-muted">{value.detail}</p> : null}
          {value.pending || (value.isDefault && defaultTag) ? (
            <p className="m-0 mt-1 flex flex-wrap gap-1.5 text-2xs font-bold">
              {value.isDefault && defaultTag ? (
                <span className="rounded-full bg-surface-subtle px-2 py-0.5 text-text-muted">{defaultTag}</span>
              ) : null}
              {value.pending ? <span className="rounded-full bg-warning-bg-soft px-2 py-0.5 text-warning-dark">ยังไม่ได้บันทึก</span> : null}
            </p>
          ) : null}
        </div>
        <Button variant="secondary" className="shrink-0" data-testid={`${id}-change`} aria-expanded={open}
          onClick={() => setOpen((v) => !v)}>
          {open ? 'ปิด' : 'เปลี่ยน'}
        </Button>
      </div>
      {open ? (
        <Chooser id={id} shortcuts={shortcuts} showToast={showToast}
          onPick={(c) => { close(); onPick(c); }}
          onShortcut={(key) => { close(); onShortcut(key); }} />
      ) : null}
    </div>
  );
}

const ITEM = 'border-t border-border-subtle first:border-t-0';
const OPTION = 'flex min-h-11 w-full cursor-pointer flex-col justify-center border-0 bg-transparent px-3 py-2 text-left text-sm text-text hover:bg-surface-hover focus-visible:bg-surface-hover';

/** Search the customer list or type a new name; the defaults sit on top as shortcuts. Inline (not a floating menu), so
 * nothing on the page is covered on a phone. */
function Chooser({ id, shortcuts, onPick, onShortcut, showToast }) {
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
    return <NewNameForm id={id} initialName={newName} showToast={showToast} onCreated={onPick} onCancel={() => setNewName(null)} />;
  }
  return (
    <div className="flex flex-col gap-2 rounded-md bg-surface-muted p-3">
      <FormField label="ค้นหาจากรายชื่อลูกค้า หรือพิมพ์ชื่อใหม่" htmlFor={`${id}-search`}>
        <input id={`${id}-search`} ref={inputRef} value={search} autoComplete="off"
          onChange={(e) => setSearch(e.target.value)} placeholder="ชื่อ หรือเลขประจำตัวผู้เสียภาษี" />
      </FormField>
      <ul className="m-0 list-none overflow-hidden rounded-md border border-border-subtle bg-surface p-0">
        {shortcuts.map((s) => (
          <li key={s.key} className={ITEM}>
            <button type="button" className={OPTION} data-testid={`${id}-reset-${s.key}`} onClick={() => onShortcut(s.key)}>
              <span className="font-bold text-primary">{s.label}</span>
            </button>
          </li>
        ))}
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
        {typed ? (
          <li className={ITEM}>
            <button type="button" className={OPTION} data-testid={`${id}-new`} onClick={() => setNewName(typed)}>
              <span className="break-words font-bold text-primary">{`ใช้ชื่อใหม่ “${typed}”`}</span>
              <span className="text-2xs text-text-muted">เพิ่มเป็นรายชื่อใหม่ ใส่อีเมลหรือเบอร์โทรได้ถ้ามี</span>
            </button>
          </li>
        ) : (
          <li className={`${ITEM} px-3 py-2 text-xs text-text-muted`}>พิมพ์ชื่อเพื่อค้นหา ถ้าไม่พบ เลือก “ใช้ชื่อใหม่” ได้ทันที</li>
        )}
      </ul>
    </div>
  );
}

/** A name not yet in the customer list, added in a hurry: the name only, email and phone optional (Ploy). Saved through
 * POST /api/customers (the deal-entry gate; the server checks the email and keeps it on a contact), then picked. */
function NewNameForm({ id, initialName, onCreated, onCancel, showToast }) {
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
    <div className="grid gap-3 rounded-md bg-surface-muted p-3" data-testid={`${id}-new-form`}>
      <p className="m-0 text-xs text-text-muted">เพิ่มเป็นรายชื่อใหม่ ใช้ซ้ำได้ในเอกสารครั้งต่อไป</p>
      <FormField label="ชื่อ (บริษัทหรือบุคคล)" htmlFor={`${id}-new-name`} required>
        <input id={`${id}-new-name`} value={value.name} disabled={saving} maxLength={200}
          onChange={(e) => setValue((v) => ({ ...v, name: e.target.value }))} />
      </FormField>
      <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
        <FormField label="อีเมล (ไม่บังคับ)" htmlFor={`${id}-new-email`}>
          <input id={`${id}-new-email`} type="email" inputMode="email" autoComplete="off" value={value.email}
            disabled={saving} maxLength={200} onChange={(e) => setValue((v) => ({ ...v, email: e.target.value }))} />
        </FormField>
        <FormField label="เบอร์โทร (ไม่บังคับ)" htmlFor={`${id}-new-phone`}>
          <input id={`${id}-new-phone`} type="tel" inputMode="tel" autoComplete="off" value={value.phone}
            disabled={saving} maxLength={50} onChange={(e) => setValue((v) => ({ ...v, phone: e.target.value }))} />
        </FormField>
      </div>
      <div className="flex flex-wrap justify-end gap-2">
        <Button variant="secondary" disabled={saving} onClick={onCancel}>ย้อนกลับ</Button>
        <Button variant="primary" loading={saving} disabled={!ready || saving} onClick={save}>ใช้ชื่อนี้</Button>
      </div>
    </div>
  );
}
