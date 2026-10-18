import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { FormField } from '../../components/common/FormField.jsx';

const CONTACT_EDIT_FIELDS = ['phone', 'email'];

function contactEditsFrom(contact) {
  return { phone: contact?.phone ?? '', email: contact?.email ?? '' };
}

export function contactDisplayName(contact) {
  if (!contact) return '';
  return `${contact.firstName ?? ''} ${contact.lastName ?? ''}`.trim();
}

function emptyNewContact() {
  return { firstName: '', lastName: '', phone: '', email: '' };
}

/**
 * ผู้ติดต่อ picker — one of the customer's contact records (`customers.contact`), picked per
 * document; the picked name prints as ผู้สั่งซื้อ on the quotation (owner rulings 2026-10-18,
 * .design/quotation-contact/DESIGN.md). Optional everywhere — no required marker, no gate, phone
 * and email optional too — and the one `hint` is the only explanatory text on the field.
 *
 * Closed, it is a select-style button; a picked contact shows as a card (name, เบอร์/อีเมล editable
 * in place, เปลี่ยน / ล้าง). Open, the list's FIRST row is "+ เพิ่มผู้ติดต่อใหม่" (the add-contact
 * modal) so the rep never scrolls to add, then a client-side search over name / phone / email, then
 * one row per contact with its phone · email.
 *
 * Contract with the parent: `value` is a contact OBJECT (or null) and the parent owns it, same
 * controlled-field shape QuotationItemRow uses. `value` may be a partial stand-in — `{ id,
 * firstName }` seeded from a frozen `contactId`/`contactName` snapshot — so a selected contact
 * that is not (yet) in the loaded option list is injected as its own option rather than reading
 * as "nobody chosen" for a request that in fact has one.
 */
export const QuotationContactPicker = forwardRef(function QuotationContactPicker({
  customerId, customerName, value, onChange, error, showToast, disabled = false, idPrefix = 'deal-contact', onResolve,
  // Item 2 (V180, "ไม่เติม “คุณ” หน้าชื่อผู้สั่งซื้อ", owner ruling 2026-09-16) — optional so a
  // caller with nothing to bind it to simply omits both and gets no extra UI. Both current callers
  // pass their own value through: QuotationEditorPage (its `terms.omitContactHonorific`, on the
  // inline-create and ?ticket= paths alike) and PricingRequestCreateModal.
  omitContactHonorific = false, onChangeOmitContactHonorific,
  label = 'ผู้ติดต่อ', hint = 'ชื่อจะพิมพ์เป็นผู้สั่งซื้อบนใบเสนอราคา',
}, ref) {
  const [options, setOptions] = useState([]);
  const [loading, setLoading] = useState(false);
  const [showNew, setShowNew] = useState(false);
  const [newContact, setNewContact] = useState(emptyNewContact());
  const [saving, setSaving] = useState(false);
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const triggerRef = useRef(null); // whichever button carries `id={idPrefix}`: the closed select or the card's เปลี่ยน
  const searchRef = useRef(null);
  const listRef = useRef(null);
  const firstNameRef = useRef(null);
  // Set before a pick / Escape / ล้าง / closing the add modal so focus lands back on the trigger
  // after the re-render, which may have swapped the closed select for the card (or back).
  const restoreFocusRef = useRef(false);
  useEffect(() => { if (open) searchRef.current?.focus(); }, [open]);
  useEffect(() => {
    if (!restoreFocusRef.current) return;
    restoreFocusRef.current = false;
    triggerRef.current?.focus();
  });
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
  // as strings because a seeded id can arrive as a number from the DTO and as a string elsewhere.
  const selectedId = value?.id ?? null;
  const hasSelectedOption = selectedId != null && options.some((c) => String(c.id) === String(selectedId));
  const renderedOptions = hasSelectedOption || selectedId == null ? options : [value, ...options];
  const selected = selectedId == null ? null : (options.find((c) => String(c.id) === String(selectedId)) ?? value);
  const needle = query.trim().toLocaleLowerCase('th');
  const filtered = renderedOptions.filter((c) => (
    `${contactDisplayName(c)} ${c.phone ?? ''} ${c.email ?? ''}`.toLocaleLowerCase('th').includes(needle)
  ));

  // A stand-in seeded from a NAME only (the PCR modal seeds `{ id, firstName }` off the deal's
  // contact; the quotation editor no longer seeds anything) does not know the contact's โทร./อีเมล. Once the real option arrives, hand it up
  // through `onResolve` — deliberately NOT `onChange`, which the parent treats as the rep's own
  // edit (it marks the quotation dirty). `phone === undefined` is the "unknown" marker; a known
  // empty value is null/'' and is left alone.
  useEffect(() => {
    if (!onResolve || selectedId == null || value?.phone !== undefined) return;
    const match = options.find((c) => String(c.id) === String(selectedId));
    if (match) onResolve(match);
  }, [options, selectedId, value, onResolve]);

  // ── edit-in-place: โทร./อีเมล of the SELECTED contact (gap fix, prod QT-2026-0041-1) ──────
  // A contact created without an e-mail used to be stuck that way — there was no update
  // endpoint. Mirrors CustomerDetailsFields' own save-on-blur pattern: local field state, seeded
  // from the selected contact and re-seeded only for a field this component itself last put
  // there (never clobbering text the rep is mid-typing), saved on blur with PATCH semantics
  // (only the changed field is sent), optimistic + rollback + toast on refusal.
  const [contactEdits, setContactEdits] = useState(() => contactEditsFrom(selected));
  const [savingContactField, setSavingContactField] = useState(null);
  const lastSeededContactRef = useRef({ id: selected?.id ?? null, ...contactEditsFrom(selected) });
  // Bug fix (owner re-report 2026-09-16, "แก้หรือเพิ่ม Email ผู้สั่งซื้อภายหลังไม่ได้"): the ONE
  // in-flight `saveContactField` call, if any -- exposed via `flushPendingContactSave` below so the
  // editor's ส่งขออนุมัติ pre-save can AWAIT it before reading `contact`/building its own PUT. Without
  // this, a rep who blurs อีเมล then immediately confirms submit could have the quotation's own
  // pre-save PUT (which re-reads the LIVE contact row server-side) land before this field's PUT
  // commits, freezing the OLD email into an approved document.
  const pendingContactSaveRef = useRef(null);
  useImperativeHandle(ref, () => ({
    flushPendingContactSave: () => pendingContactSaveRef.current ?? Promise.resolve(),
  }), []);

  useEffect(() => {
    // Still resolving (see the onResolve effect above) — โทร./อีเมล are unknown, not editable yet.
    if (!selected || selected.phone === undefined || selected.email === undefined) return;
    const record = contactEditsFrom(selected);
    const wasSeeded = lastSeededContactRef.current;
    const idChanged = selected.id !== wasSeeded.id;
    setContactEdits((prev) => {
      if (idChanged) return record; // a different contact — the rep's dirty text belonged to the old one
      const merged = { ...prev };
      for (const field of CONTACT_EDIT_FIELDS) {
        if (prev[field] === wasSeeded[field]) merged[field] = record[field]; // clean — take the record
        // else: the rep has typed something else since it was seeded — keep their text
      }
      return merged;
    });
    lastSeededContactRef.current = { id: selected.id, ...record };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected?.id, selected?.phone, selected?.email]);

  /** Persists ONE field on blur, sending only that field (PATCH semantics — an omitted key is
   * left alone). Updates the local options list too, so the picker's own list reflects the edit
   * without a refetch, and hands the result up through `onChange` — the SAME callback a newly
   * created contact uses — so the parent marks the quotation dirty and the next draft save
   * re-snapshots it (DealQuotationService#resolveContact re-reads the live contact by id on
   * every save; see CustomerController#updateContact's own Javadoc). */
  function saveContactField(field) {
    if (!selected?.id) return;
    // Bug fix (owner re-report 2026-09-16): this used to return here just as silently as the
    // `!selected?.id` case above -- no request, no toast, the typed value simply reverting to
    // whatever it was on the next re-render with nothing telling the rep why. Happens for a
    // quotation-grant holder who cannot load the ticket (`contactCustomerId` stays null even
    // though the quotation's own frozen snapshot already seeded a `selected` contact with
    // phone/email). Toast instead, and — unlike the catch block below — do NOT revert
    // `contactEdits`: there is nothing to roll back to (no request was ever sent), so reverting
    // here would silently discard what the rep just typed for no reason they could see.
    if (!customerId) {
      showToast?.('error', 'แก้ไขข้อมูลผู้ติดต่อไม่ได้ — ไม่พบข้อมูลลูกค้าของดีลนี้');
      return;
    }
    const previous = (selected[field] ?? '').toString();
    const next = (contactEdits[field] ?? '').trim();
    if (next === previous.trim()) return; // untouched — no request at all
    const restore = selected;
    setSavingContactField(field);
    lastSeededContactRef.current = { ...lastSeededContactRef.current, [field]: next };
    setContactEdits((prev) => ({ ...prev, [field]: next }));
    const optimistic = { ...selected, [field]: next || null };
    setOptions((prev) => prev.map((c) => (String(c.id) === String(selected.id) ? { ...c, [field]: next || null } : c)));
    onChange(optimistic);
    // Tracked so the editor's submit pre-save can await this exact request — see
    // `flushPendingContactSave` above.
    const promise = (async () => {
      try {
        const res = await api.customers.updateContact(customerId, selected.id, { [field]: next });
        const updated = res?.contact ?? optimistic;
        setOptions((prev) => prev.map((c) => (String(c.id) === String(selected.id) ? updated : c)));
        onChange(updated);
      } catch (err) {
        setOptions((prev) => prev.map((c) => (String(c.id) === String(selected.id) ? restore : c)));
        setContactEdits((prev) => ({ ...prev, [field]: previous }));
        lastSeededContactRef.current = { ...lastSeededContactRef.current, [field]: previous };
        onChange(restore);
        showToast?.('error', err.message || 'บันทึกข้อมูลผู้ติดต่อไม่สำเร็จ');
      } finally {
        setSavingContactField(null);
        if (pendingContactSaveRef.current === promise) pendingContactSaveRef.current = null;
      }
    })();
    pendingContactSaveRef.current = promise;
  }

  function closeList({ restoreFocus = false } = {}) {
    restoreFocusRef.current = restoreFocus;
    setOpen(false);
    setQuery('');
  }

  function pick(contact) {
    onChange(contact);
    closeList({ restoreFocus: true });
  }

  function closeNewContact() {
    if (saving) return;
    restoreFocusRef.current = true;
    setShowNew(false);
    setNewContact(emptyNewContact());
    setSameAsCustomer(false);
  }

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
      restoreFocusRef.current = true;
      setNewContact(emptyNewContact());
      setSameAsCustomer(false);
      setShowNew(false);
    } catch (err) {
      showToast?.('error', err.message || 'เพิ่มผู้ติดต่อใหม่ไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  const locked = disabled || !customerId;
  // FormField wires aria-describedby only onto a DIRECT child whose id matches; the trigger sits
  // inside the wrapper below (and inside the card once picked), so it is wired by hand.
  const triggerAria = {
    'aria-haspopup': 'listbox',
    'aria-expanded': open,
    'aria-controls': open ? `${idPrefix}-list` : undefined,
    'aria-describedby': [hint && `${idPrefix}-hint`, error && `${idPrefix}-error`].filter(Boolean).join(' ') || undefined,
    'aria-invalid': error ? true : undefined,
  };
  const toggleList = () => (open ? closeList({ restoreFocus: true }) : setOpen(true));
  // While the list is open, a press on the trigger keeps focus in the search box, so the click
  // toggles it shut. Otherwise Safari and Firefox (which do not focus a pressed button) blur the
  // search box with no relatedTarget, the wrapper closes the list, and the click re-opens it.
  const keepFocusWhileOpen = (e) => { if (open) e.preventDefault(); };

  return (
    <>
      <FormField label={label} htmlFor={idPrefix} error={error} hint={hint}>
        {/* eslint-disable-next-line jsx-a11y/no-static-element-interactions -- not a control: only catches focus leaving, and Escape bubbling up from, the real controls inside */}
        <div
          onBlur={(e) => { if (open && !e.currentTarget.contains(e.relatedTarget)) closeList(); }}
          onKeyDown={(e) => {
            // Stops here so an enclosing Modal's document-level Escape does not close it too.
            if (e.key === 'Escape' && open) { e.stopPropagation(); closeList({ restoreFocus: true }); }
          }}
        >
          {selected ? (
            <div className="rounded-md border border-border bg-surface-muted px-3.5 py-3">
              <div className="flex items-start justify-between gap-3">
                <span className="min-w-0 break-words text-base font-bold text-text">{contactDisplayName(selected)}</span>
                <span className="flex shrink-0 gap-3">
                  <Button
                    ref={triggerRef} id={idPrefix} variant="text" size="sm" className="mobile:min-h-[44px]"
                    aria-label="เปลี่ยนผู้ติดต่อ" {...triggerAria} disabled={locked}
                    onMouseDown={keepFocusWhileOpen} onClick={toggleList}
                  >
                    เปลี่ยน
                  </Button>
                  <Button
                    variant="text" size="sm" className="mobile:min-h-[44px]" aria-label="ล้างผู้ติดต่อ" disabled={locked}
                    onClick={() => { closeList({ restoreFocus: true }); onChange(null); }}
                  >
                    ล้าง
                  </Button>
                </span>
              </div>
              {/* The ผู้ติดต่อ's own เบอร์/อีเมล — prefilled from the contact record, editable in
                  place (gap fix, prod QT-2026-0041-1: a contact created without an e-mail could
                  never gain one) and saved back to PUT /api/customers/{customerId}/contacts/
                  {contactId} on blur — same pattern as CustomerDetailsFields' ที่อยู่/โทร. fields.
                  A missing one is muted info, never a warning (owner ruling 2026-10-18). */}
              {selected.phone === undefined && selected.email === undefined ? (
                <span className="mt-1.5 block text-2xs text-text-muted" data-testid={`${idPrefix}-details`}>
                  กำลังโหลดเบอร์โทร/อีเมล…
                </span>
              ) : (
                <div className="mt-2 grid grid-cols-2 gap-2 mobile:grid-cols-1" data-testid={`${idPrefix}-details`}>
                  <label className="m-0">
                    <span className="text-2xs text-text-muted">เบอร์</span>
                    <input
                      aria-label="แก้ไขเบอร์ผู้ติดต่อ"
                      className="text-2xs"
                      placeholder="ไม่มีเบอร์"
                      value={contactEdits.phone}
                      maxLength={50}
                      disabled={disabled || savingContactField === 'phone'}
                      onChange={(e) => setContactEdits((prev) => ({ ...prev, phone: e.target.value }))}
                      onBlur={() => saveContactField('phone')}
                      onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }}
                    />
                  </label>
                  <label className="m-0">
                    <span className="text-2xs text-text-muted">อีเมล</span>
                    <input
                      type="email"
                      aria-label="แก้ไขอีเมลผู้ติดต่อ"
                      className="text-2xs"
                      placeholder="ไม่มีอีเมล"
                      value={contactEdits.email}
                      maxLength={200}
                      disabled={disabled || savingContactField === 'email'}
                      onChange={(e) => setContactEdits((prev) => ({ ...prev, email: e.target.value }))}
                      onBlur={() => saveContactField('email')}
                      onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }}
                    />
                  </label>
                </div>
              )}
            </div>
          ) : (
            <button
              ref={triggerRef}
              id={idPrefix}
              type="button"
              className="flex h-10 w-full items-center justify-between gap-2 rounded-md border-[1.5px] border-border-input bg-surface px-3 text-left text-base text-text-muted mobile:h-11"
              {...triggerAria}
              disabled={locked}
              onMouseDown={keepFocusWhileOpen}
              onClick={toggleList}
            >
              <span className="truncate">{customerId ? (loading ? 'กำลังโหลด…' : '- เลือกผู้ติดต่อ -') : 'เลือกลูกค้าก่อน'}</span>
              <Icon name="chevronDown" size={16} className="shrink-0" />
            </button>
          )}
          {open ? (
            // In the page's flow rather than floating, so a modal's scrolling body can never clip it.
            // A press anywhere in the panel — a row, the padding round the search box, ไม่พบผู้ติดต่อ —
            // cancels its mousedown so focus stays in the search box and the wrapper's blur-to-close
            // never fires: Safari and Firefox do not focus a pressed button or box, so without this
            // the blur arrives with no relatedTarget and reads as focus leaving. Two exceptions: the
            // search box itself (a press must still place the caret) and the listbox element, whose
            // only own press is its scrollbar — a cancelled mousedown can stop a scrollbar drag.
            // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- not a control: only keeps focus where it is
            <div
              className="mt-1.5 overflow-hidden rounded-md border border-border bg-surface shadow-[var(--shadow-lg-heavy)]"
              onMouseDown={(e) => { if (e.target !== searchRef.current && e.target !== listRef.current) e.preventDefault(); }}
            >
              <button
                type="button"
                data-testid="contact-add-first"
                className="flex min-h-11 w-full cursor-pointer items-center gap-2.5 border-0 border-b border-border-subtle bg-primary-bg px-3.5 text-left text-sm font-bold text-primary"
                onClick={() => { closeList(); setShowNew(true); }}
              >
                <span className="grid h-6 w-6 shrink-0 place-items-center rounded-pill bg-primary text-surface" aria-hidden="true">
                  <Icon name="plus" size={14} />
                </span>
                เพิ่มผู้ติดต่อใหม่
              </button>
              <div className="border-b border-border-subtle p-2.5">
                <input
                  ref={searchRef}
                  type="search"
                  autoComplete="off"
                  aria-label="ค้นหาชื่อ เบอร์ หรืออีเมล"
                  placeholder="ค้นหาชื่อ เบอร์ หรืออีเมล"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') e.preventDefault(); }}
                />
              </div>
              <ul ref={listRef} id={`${idPrefix}-list`} role="listbox" aria-label="รายชื่อผู้ติดต่อ" className="m-0 max-h-64 list-none overflow-auto p-0">
                {loading ? <li role="presentation" className="px-3.5 py-2.5 text-sm text-text-muted">กำลังโหลด…</li> : null}
                {!loading && filtered.length === 0 ? (
                  <li role="presentation" className="px-3.5 py-2.5 text-sm text-text-muted">ไม่พบผู้ติดต่อ</li>
                ) : null}
                {filtered.map((c) => {
                  const isSelected = String(c.id) === String(selectedId);
                  return (
                    <li key={c.id} role="presentation" className="border-b border-border-subtle last:border-b-0">
                      <button
                        type="button"
                        role="option"
                        aria-selected={isSelected}
                        className={`grid w-full cursor-pointer gap-0.5 border-0 px-3.5 py-2.5 text-left hover:bg-surface-hover ${isSelected ? 'bg-surface-hover' : 'bg-surface'}`}
                        onClick={() => pick(c)}
                      >
                        <span className="text-sm font-bold text-text">{contactDisplayName(c)}</span>
                        <span className="text-xs text-text-secondary">
                          <span className={c.phone ? undefined : 'text-text-faint'}>{c.phone || 'ไม่มีเบอร์'}</span>
                          {' · '}
                          <span className={c.email ? undefined : 'text-text-faint'}>{c.email || 'ไม่มีอีเมล'}</span>
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            </div>
          ) : null}
        </div>
      </FormField>
      {/* Item 2 (V180, "ไม่เติม “คุณ” หน้าชื่อผู้สั่งซื้อ", owner ruling 2026-09-16) — directly under
          the picker, per the owner's own placement. No sub-line: the field's hint is the only
          explanatory text (owner ruling 2026-10-18). Whole row is the tappable label
          (not just the checkbox square) and grows to the mobile touch-target floor, same
          `mobile:min-h-[44px]` convention QuotationEditorPage's own terms-card toggle buttons use. */}
      {onChangeOmitContactHonorific ? (
        <label
          htmlFor={`${idPrefix}-omit-honorific`}
          className="mt-2 flex min-h-[38px] cursor-pointer items-start gap-2 rounded-md px-1 py-1 mobile:min-h-[44px] mobile:py-2"
        >
          <input
            id={`${idPrefix}-omit-honorific`}
            type="checkbox"
            className="mt-0.5 h-4 w-4 shrink-0"
            checked={!!omitContactHonorific}
            disabled={disabled}
            onChange={(e) => onChangeOmitContactHonorific(e.target.checked)}
          />
          <span className="text-xs">ไม่เติม “คุณ” หน้าชื่อผู้ติดต่อ</span>
        </label>
      ) : null}

      {showNew && customerId ? (
        <Modal
          title="เพิ่มผู้ติดต่อใหม่"
          subtitle={customerName ? `ลูกค้า: ${customerName}` : undefined}
          onClose={closeNewContact}
          initialFocusRef={firstNameRef}
          footer={(
            <>
              <Button variant="secondary" disabled={saving} onClick={closeNewContact}>ยกเลิก</Button>
              <Button variant="primary" loading={saving} disabled={!newContact.firstName.trim()} onClick={handleCreate}>
                เพิ่มและเลือกคนนี้
              </Button>
            </>
          )}
        >
          <div className="grid grid-cols-2 gap-3.5 mobile:grid-cols-1">
            <FormField label="ชื่อ" htmlFor={`${idPrefix}-new-first-name`} required>
              <input
                id={`${idPrefix}-new-first-name`}
                ref={firstNameRef}
                value={newContact.firstName}
                disabled={sameAsCustomer}
                onChange={(e) => setNewContact((p) => ({ ...p, firstName: e.target.value }))}
              />
            </FormField>
            <FormField label="นามสกุล" htmlFor={`${idPrefix}-new-last-name`}>
              <input
                id={`${idPrefix}-new-last-name`}
                value={newContact.lastName}
                onChange={(e) => setNewContact((p) => ({ ...p, lastName: e.target.value }))}
              />
            </FormField>
            <FormField label="เบอร์โทร" htmlFor={`${idPrefix}-new-phone`}>
              <input
                id={`${idPrefix}-new-phone`}
                type="tel"
                value={newContact.phone}
                onChange={(e) => setNewContact((p) => ({ ...p, phone: e.target.value }))}
              />
            </FormField>
            <FormField label="อีเมล" htmlFor={`${idPrefix}-new-email`}>
              <input
                id={`${idPrefix}-new-email`}
                type="email"
                value={newContact.email}
                onChange={(e) => setNewContact((p) => ({ ...p, email: e.target.value }))}
              />
            </FormField>
          </div>
          {customerName ? (
            <label className="mt-3.5 flex cursor-pointer items-center gap-2.5 text-sm font-medium text-text">
              <input
                type="checkbox"
                checked={sameAsCustomer}
                onChange={(e) => setSameAsCustomer(e.target.checked)}
              />
              ใช้ชื่อเดียวกับลูกค้า
            </label>
          ) : null}
        </Modal>
      ) : null}
    </>
  );
});
