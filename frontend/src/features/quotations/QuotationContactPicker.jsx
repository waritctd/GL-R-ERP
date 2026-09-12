import { useEffect, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';

export function contactDisplayName(contact) {
  if (!contact) return '';
  return `${contact.firstName ?? ''} ${contact.lastName ?? ''}`.trim();
}

function emptyNewContact() {
  return { firstName: '', lastName: '', phone: '', email: '' };
}

/**
 * ผู้สั่งซื้อ picker — owner feedback F2, 2026-09-10 ("change ผู้ติดต่อ -> ผู้สั่งซื้อ, make
 * mandatory and use that name to auto fill in the name for signature in the quotation pdf").
 *
 * ONE component, deliberately, because the editor needs this control in THREE different places:
 * inside DealCustomerCard on the inline-create path, and inside the read-only deal summary on both
 * the `?ticket=` and the existing-DRAFT paths. The previous version of this markup lived only
 * inside DealCustomerCard; copying it into the summary panel would have created exactly the
 * inline-copy divergence this repo has been bitten by before (a fix to the component never reaches
 * its pasted twins). The required-ness, the Thai copy and the inline-add fields are therefore
 * defined once, here.
 *
 * Contract with the parent: `value` is a contact OBJECT (or null) and the parent owns it, same
 * controlled-field shape DealCustomerCard/QuotationItemRow already use. `value` may be a partial
 * stand-in — `{ id, firstName }` seeded from a DealQuotationDto's frozen `contactId`/`contactName`
 * snapshot — so a selected contact that is not (yet) in the loaded option list is injected as its
 * own option rather than falling back to a blank select, which would read as "nobody chosen" for
 * a quotation that in fact has one.
 */
export function QuotationContactPicker({
  customerId, customerName, value, onChange, error, showToast, disabled = false, idPrefix = 'deal-contact', onResolve,
}) {
  const [options, setOptions] = useState([]);
  const [loading, setLoading] = useState(false);
  const [showNew, setShowNew] = useState(false);
  const [newContact, setNewContact] = useState(emptyNewContact());
  const [saving, setSaving] = useState(false);
  // "ใช้ชื่อเดียวกับลูกค้า" (owner testing feedback, 2026-09-11: "when the customer and the
  // purchaser are the same person, let the rep reuse the customer name instead of retyping it").
  //
  // Decided behaviour, documented here because there is no spec doc for it:
  //  - Checking the box copies `customerName` into ชื่อ and blanks นามสกุล (the customer record
  //    is a single name string; splitting it into first/last would be a guess this component has
  //    no basis for), and disables ชื่อ while checked so the field can't silently drift out of
  //    sync with a checkbox that claims "same as".
  //  - While checked, ชื่อ stays LIVE-bound to `customerName` (the effect below re-copies it) --
  //    covers the rep switching to a different selected customer without first unchecking.
  //  - Unchecking hands ชื่อ back for manual editing and freezes it at whatever it last held; it
  //    does NOT clear the field. Editing by hand therefore means "uncheck first" -- there's no
  //    silent-divergence state where the box reads checked but the text no longer matches.
  //  - HIDDEN entirely when there is no customer name yet to copy -- a checkbox offering to
  //    reuse a name that does not exist would be a dead control, not a disabled one.
  const [sameAsCustomer, setSameAsCustomer] = useState(false);
  useEffect(() => {
    if (!sameAsCustomer) return;
    setNewContact((prev) => ({ ...prev, firstName: customerName ?? '', lastName: '' }));
  }, [sameAsCustomer, customerName]);

  useEffect(() => {
    if (!customerId) {
      setOptions([]);
      return undefined;
    }
    let cancelled = false;
    setLoading(true);
    api.customers.contacts(customerId)
      .then((res) => { if (!cancelled) setOptions(res.contacts ?? []); })
      .catch(() => { if (!cancelled) setOptions([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [customerId]);

  // The selected contact, guaranteed present in the rendered options (see the class doc). Compared
  // as strings because a seeded id can arrive as a number from the DTO and as a string from the
  // <select>'s own value.
  const selectedId = value?.id ?? null;
  const hasSelectedOption = selectedId != null && options.some((c) => String(c.id) === String(selectedId));
  const renderedOptions = hasSelectedOption || selectedId == null ? options : [value, ...options];
  const selected = selectedId == null ? null : (options.find((c) => String(c.id) === String(selectedId)) ?? value);

  // A stand-in seeded from a NAME only (the ?ticket= path seeds `{ id, firstName }` off the
  // ticket) does not know the ผู้สั่งซื้อ's โทร./อีเมล. Once the real option arrives, hand it up
  // through `onResolve` — deliberately NOT `onChange`, which the parent treats as the rep's own
  // edit (it marks the quotation dirty). `phone === undefined` is the "unknown" marker; a known
  // empty value is null/'' and is left alone.
  useEffect(() => {
    if (!onResolve || selectedId == null || value?.phone !== undefined) return;
    const match = options.find((c) => String(c.id) === String(selectedId));
    if (match) onResolve(match);
  }, [options, selectedId, value, onResolve]);

  async function handleCreate() {
    if (!customerId || !newContact.firstName.trim()) return;
    setSaving(true);
    try {
      const res = await api.customers.createContact(customerId, {
        firstName: newContact.firstName.trim(),
        lastName: newContact.lastName.trim() || null,
        phone: newContact.phone.trim() || null,
        email: newContact.email.trim() || null,
      });
      setOptions((prev) => [...prev, res.contact]);
      onChange(res.contact);
      setNewContact(emptyNewContact());
      setSameAsCustomer(false);
      setShowNew(false);
    } catch (err) {
      showToast?.('error', err.message || 'เพิ่มผู้สั่งซื้อใหม่ไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <FormField label="ผู้สั่งซื้อ" htmlFor={idPrefix} required error={error} hint="ชื่อนี้จะพิมพ์ในช่องลงนามของใบเสนอราคา">
        <select
          id={idPrefix}
          disabled={disabled || !customerId}
          value={selectedId ?? ''}
          onChange={(e) => {
            if (e.target.value === '__new__') { setShowNew(true); return; }
            const found = renderedOptions.find((c) => String(c.id) === e.target.value) ?? null;
            onChange(found);
          }}
        >
          <option value="">{customerId ? (loading ? 'กำลังโหลด…' : '- เลือกผู้สั่งซื้อ -') : 'เลือกลูกค้าก่อน'}</option>
          {renderedOptions.map((c) => (
            <option key={c.id} value={c.id}>{contactDisplayName(c)}</option>
          ))}
          {customerId && !disabled ? <option value="__new__">+ เพิ่มผู้สั่งซื้อใหม่</option> : null}
        </select>
        {/* The ผู้สั่งซื้อ's own โทร./อีเมล — prefilled from the contact record the moment one is
            picked (a repeat customer's contacts arrive with them), and shown so a gap is visible
            before the document is sent. The English form prints the email ("E :").
            Read-only on purpose: there is NO endpoint to update an existing contact
            (CustomerController has POST /{id}/contacts but no PUT), so offering an edit box here
            would be a lie — a new ผู้สั่งซื้อ can carry corrected details via "+ เพิ่มผู้สั่งซื้อใหม่". */}
        {selected ? (
          <span className="block text-2xs text-text-muted" data-testid={`${idPrefix}-details`}>
            {selected.phone === undefined && selected.email === undefined
              ? 'กำลังโหลดเบอร์โทร/อีเมล…'
              : (
                <>
                  <span className={selected.phone ? '' : 'text-warning'}>โทร. {selected.phone || 'ยังไม่มี'}</span>
                  {' · '}
                  <span className={selected.email ? '' : 'text-warning'}>อีเมล {selected.email || 'ยังไม่มี'}</span>
                </>
              )}
          </span>
        ) : null}
      </FormField>

      {showNew && customerId ? (
        <div className="col-span-full mt-1 flex flex-col gap-2 rounded-md border border-info-border bg-info-row-active p-3">
          <p className="m-0 text-xs font-bold text-info">เพิ่มผู้สั่งซื้อใหม่</p>
          {customerName ? (
            <label className="m-0 flex items-center gap-1.5 text-2xs">
              <input
                type="checkbox"
                checked={sameAsCustomer}
                onChange={(e) => setSameAsCustomer(e.target.checked)}
              />
              ใช้ชื่อเดียวกับลูกค้า ({customerName})
            </label>
          ) : null}
          <div className="grid grid-cols-4 gap-2 tablet:grid-cols-2 mobile:grid-cols-1">
            <label className="m-0">
              <span className="text-2xs">ชื่อ *</span>
              <input
                aria-label="ชื่อผู้สั่งซื้อ"
                value={newContact.firstName}
                disabled={sameAsCustomer}
                onChange={(e) => setNewContact((p) => ({ ...p, firstName: e.target.value }))}
              />
            </label>
            <label className="m-0">
              <span className="text-2xs">นามสกุล</span>
              <input
                aria-label="นามสกุลผู้สั่งซื้อ"
                value={newContact.lastName}
                onChange={(e) => setNewContact((p) => ({ ...p, lastName: e.target.value }))}
              />
            </label>
            <label className="m-0">
              <span className="text-2xs">โทรศัพท์</span>
              <input
                aria-label="โทรศัพท์ผู้สั่งซื้อ"
                value={newContact.phone}
                onChange={(e) => setNewContact((p) => ({ ...p, phone: e.target.value }))}
              />
            </label>
            <label className="m-0">
              <span className="text-2xs">อีเมล</span>
              <input
                type="email"
                aria-label="อีเมลผู้สั่งซื้อ"
                value={newContact.email}
                onChange={(e) => setNewContact((p) => ({ ...p, email: e.target.value }))}
              />
            </label>
          </div>
          <div className="mt-1 flex gap-2">
            <Button variant="primary" size="sm" loading={saving} disabled={!newContact.firstName.trim() || saving} onClick={handleCreate}>
              เพิ่มผู้สั่งซื้อ
            </Button>
            <Button variant="secondary" size="sm" onClick={() => { setShowNew(false); setNewContact(emptyNewContact()); setSameAsCustomer(false); }}>
              ยกเลิก
            </Button>
          </div>
        </div>
      ) : null}
    </>
  );
}
