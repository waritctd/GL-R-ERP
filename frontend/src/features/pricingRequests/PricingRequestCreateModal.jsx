import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { QUANTITY_TYPE_OPTIONS, RECIPIENT_OPTIONS } from './pricingRequestMeta.js';

// TicketItemDto's unitBasis is a code ('PIECE' | 'SQM'), never a display unit —
// mirrors the label mapping TicketDetailPage already uses for the same field
// (unitBasis === 'SQM' ? 'ตร.ม.' : 'แผ่น').
function unitLabelForTicketItem(ticketItem) {
  return ticketItem?.unitBasis === 'SQM' ? 'ตร.ม.' : 'แผ่น';
}

// Mirrors TicketDetailPage's own qtyDisplay logic: an SQM-basis line quotes
// its sqm quantity, a PIECE-basis line quotes its piece count.
function quantityForTicketItem(ticketItem) {
  if (ticketItem?.unitBasis === 'SQM' && ticketItem?.qtySqm != null) return ticketItem.qtySqm;
  return ticketItem?.qty ?? 1;
}

function emptyItemFromTicketItem(ticketItem) {
  return {
    sourceTicketItemId: ticketItem?.id ?? null,
    productId: null,
    brand: ticketItem?.brand ?? '',
    model: ticketItem?.model ?? '',
    productDescription: '',
    color: ticketItem?.color ?? '',
    texture: ticketItem?.texture ?? '',
    size: ticketItem?.size ?? '',
    factory: ticketItem?.factory ?? '',
    requestedQty: quantityForTicketItem(ticketItem),
    requestedUnit: unitLabelForTicketItem(ticketItem),
    quantityType: 'ESTIMATE',
    targetDeliveryDate: '',
    deliveryLocation: '',
    specialRequirement: '',
  };
}

// Edit mode (Fix 2 of the review-remediation plan) seeds its rows from a
// persisted PricingRequestItemDto instead of a ticket_item — same target
// shape as emptyItemFromTicketItem, but every field already has a value
// (including productId, which this modal never sets itself — no catalog
// picker yet, see the class doc below — but must round-trip unchanged if an
// item already carries one, or editing would silently erase that identity).
function itemFromExisting(item) {
  return {
    sourceTicketItemId: item?.sourceTicketItemId ?? null,
    productId: item?.productId ?? null,
    brand: item?.brand ?? '',
    model: item?.model ?? '',
    productDescription: item?.productDescription ?? '',
    color: item?.color ?? '',
    texture: item?.texture ?? '',
    size: item?.size ?? '',
    factory: item?.factory ?? '',
    requestedQty: item?.requestedQty ?? 1,
    requestedUnit: item?.requestedUnit ?? '',
    quantityType: item?.quantityType ?? 'ESTIMATE',
    targetDeliveryDate: item?.targetDeliveryDate ?? '',
    deliveryLocation: item?.deliveryLocation ?? '',
    specialRequirement: item?.specialRequirement ?? '',
  };
}

/**
 * Create/submit modal for a new PricingRequest (commit 6), extended in the
 * review-remediation plan (Fix 2) to double as the edit modal for an existing
 * DRAFT: pass `mode="edit"` + `initialValue` (that request's summary + items,
 * as returned by `api.pricingRequests.get`) and `updateFn` instead of
 * `createFn`/`submitFn`. One component, not a forked near-duplicate — create
 * and edit differ only in how the form seeds its initial state and what the
 * footer buttons do; every field, validation, and row layout below is shared.
 *
 * No attachments — explicitly cut from scope (see handoff). Item rows seed
 * from the deal's existing ticket_item rows in create mode (carrying
 * sourceTicketItemId so Import can trace a request line back to the original
 * product line) or from the request's own persisted items in edit mode, but
 * every field stays editable either way — PricingRequestItemRequest carries
 * its own independent descriptive copy, not a read-only mirror of ticket_item.
 *
 * recipientContactId (a real customer-contact picker) is not wired up yet —
 * recipientLabel (free text) is the only way to identify a recipient today.
 * PricingRequestService.validateRecipientIdentifiable accepts either.
 */
// Mirrors PricingRequestService.validateItems (Part 1 of the review-remediation
// plan): an item must actually name a product somehow — a link back to a deal
// line, a catalog product, a model name, or a dedicated product description.
// Brand alone is deliberately NOT enough (a brand with no model does not
// identify a product). This modal has no productId field yet (no catalog
// picker — see the class doc below), so in practice this reduces to
// sourceTicketItemId / model / productDescription, but the productId check is
// kept here to stay byte-for-byte in sync with the backend predicate.
function itemIdentityValid(item) {
  return Boolean(item.sourceTicketItemId) || Boolean(item.productId)
    || Boolean(item.model?.trim()) || Boolean(item.productDescription?.trim());
}

function generateClientRequestId() {
  return globalThis.crypto?.randomUUID?.()
    ?? '00000000-0000-4000-8000-' + String(Date.now()).slice(-12).padStart(12, '0');
}

export function PricingRequestCreateModal({
  ticketItems = [], onClose, onCreated, createFn, submitFn,
  mode = 'create', initialValue = null, updateFn,
}) {
  const isEdit = mode === 'edit';
  const initialSummary = initialValue?.summary ?? null;
  const [recipientType, setRecipientType] = useState(() => initialSummary?.recipientType ?? 'DESIGNER');
  const [recipientLabel, setRecipientLabel] = useState(() => initialSummary?.recipientLabel ?? '');
  const [requiredDate, setRequiredDate] = useState(() => initialSummary?.requiredDate ?? '');
  const [customerTargetPrice, setCustomerTargetPrice] = useState(() => (
    initialSummary?.customerTargetPrice != null ? String(initialSummary.customerTargetPrice) : ''
  ));
  const [targetCurrency, setTargetCurrency] = useState(() => initialSummary?.targetCurrency ?? 'THB');
  const [note, setNote] = useState(() => initialSummary?.note ?? '');
  const [clientRequestId] = useState(() => generateClientRequestId());
  const [items, setItems] = useState(() => {
    if (isEdit) {
      return initialValue?.items?.length ? initialValue.items.map(itemFromExisting) : [emptyItemFromTicketItem(null)];
    }
    return ticketItems.length ? ticketItems.map(emptyItemFromTicketItem) : [emptyItemFromTicketItem(null)];
  });
  const [error, setError] = useState('');
  // Informational (non-error) banner — currently only used by the create-mode
  // duplicate-draft guard below, to tell the user a retry is reusing the
  // draft it already created rather than making a second one.
  const [info, setInfo] = useState('');
  // Per-row identity errors (keyed by item index), separate from the
  // form-level `error` banner above — unlike the other validation rules,
  // this one is attached to the specific line that is missing an identity so
  // the user knows which row to fix, not just that "a" row is wrong.
  const [itemErrors, setItemErrors] = useState({});
  const [saving, setSaving] = useState(false);
  // Fix 1 (review-remediation plan): once createFn has succeeded once, its
  // resulting id is held here so a retry (create succeeded, then submitFn
  // failed — or the user switches from "submit" to "save draft" after that
  // failure) reuses the same draft instead of calling createFn again and
  // orphaning a duplicate. Always null in edit mode (there is no create step).
  const [createdId, setCreatedId] = useState(null);

  function updateItem(index, field, value) {
    setItems((cur) => cur.map((item, i) => (i === index ? { ...item, [field]: value } : item)));
    if (['sourceTicketItemId', 'productId', 'model', 'productDescription'].includes(field)) {
      setItemErrors((cur) => {
        if (!(index in cur)) return cur;
        const next = { ...cur };
        delete next[index];
        return next;
      });
    }
  }

  function addItem() {
    setItems((cur) => [...cur, emptyItemFromTicketItem(null)]);
  }

  function removeItem(index) {
    setItems((cur) => cur.filter((_, i) => i !== index));
  }

  function validate() {
    if (!recipientLabel.trim()) return 'กรุณาระบุผู้รับคำขอราคา';
    if (items.length === 0) return 'ต้องมีรายการสินค้าอย่างน้อย 1 รายการ';
    for (const item of items) {
      if (!Number(item.requestedQty) || Number(item.requestedQty) <= 0) return 'กรุณากรอกจำนวนของทุกรายการให้ถูกต้อง';
      if (!item.requestedUnit?.trim()) return 'กรุณากรอกหน่วยของทุกรายการ';
    }
    return '';
  }

  // Separate from validate() above: this rule reports per-row, not as a
  // single form-level message (see itemErrors' declaration). Returns whether
  // every row is valid, and updates itemErrors as a side effect.
  function validateItemIdentities() {
    const next = {};
    items.forEach((item, index) => {
      if (!itemIdentityValid(item)) {
        next[index] = 'ต้องระบุสินค้าที่ต้องการเสนอราคา (เลือกจากรายการในดีล หรือระบุรุ่น/รายละเอียด)';
      }
    });
    setItemErrors(next);
    return Object.keys(next).length === 0;
  }

  function buildPayload({ includeClientRequestId = false } = {}) {
    const payload = {
      recipientType,
      recipientLabel: recipientLabel.trim(),
      requiredDate: requiredDate || null,
      customerTargetPrice: customerTargetPrice !== '' ? Number(customerTargetPrice) : null,
      targetCurrency: targetCurrency.trim() || null,
      note: note.trim() || null,
      items: items.map((item) => ({
        sourceTicketItemId: item.sourceTicketItemId ?? null,
        productId: item.productId ?? null,
        brand: item.brand?.trim() || null,
        model: item.model?.trim() || null,
        productDescription: item.productDescription?.trim() || null,
        color: item.color?.trim() || null,
        texture: item.texture?.trim() || null,
        size: item.size?.trim() || null,
        factory: item.factory?.trim() || null,
        requestedQty: Number(item.requestedQty),
        requestedUnit: item.requestedUnit.trim(),
        quantityType: item.quantityType,
        targetDeliveryDate: item.targetDeliveryDate || null,
        deliveryLocation: item.deliveryLocation?.trim() || null,
        specialRequirement: item.specialRequirement?.trim() || null,
      })),
    };
    if (includeClientRequestId) payload.clientRequestId = clientRequestId;
    return payload;
  }

  // Fix 1 (review-remediation plan): both create-mode actions below share the
  // same duplicate-draft guard. If createFn already succeeded once (createdId
  // is set) — either because a prior "ส่งให้ Import" attempt created the
  // draft and then failed at the submit step, or the user clicked "บันทึกร่าง"
  // again after that — neither handler calls createFn a second time. Instead
  // they push the CURRENT form state onto the existing draft via updateFn
  // (update-then-submit/save, not "reset createdId and create fresh"): the
  // user may well have edited the form to fix whatever made the first attempt
  // fail (e.g. a validation error), and discarding createdId would silently
  // resurrect the create-twice bug this fix exists to close. onCreated() still
  // fires either way so the caller's normal close+refresh flow is unchanged.
  async function handleSaveDraft() {
    const validationError = validate();
    if (validationError) { setError(validationError); return; }
    if (!validateItemIdentities()) return;
    setError('');
    setInfo(createdId != null ? 'ร่างถูกสร้างแล้ว กำลังบันทึกการเปลี่ยนแปลง' : '');
    setSaving(true);
    try {
      if (createdId != null) {
        if (updateFn) await updateFn(createdId, buildPayload());
      } else {
        const created = await createFn(buildPayload({ includeClientRequestId: true }));
        const id = created?.pricingRequest?.summary?.id;
        if (id != null) setCreatedId(id);
      }
      onCreated();
    } catch (err) {
      setError(err.message || 'สร้างใบขอราคาไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function handleSubmitToImport() {
    const validationError = validate();
    if (validationError) { setError(validationError); return; }
    if (!validateItemIdentities()) return;
    setError('');
    setInfo(createdId != null ? 'ร่างถูกสร้างแล้ว กำลังส่งให้ Import' : '');
    setSaving(true);
    try {
      let id = createdId;
      if (id != null) {
        if (updateFn) await updateFn(id, buildPayload());
      } else {
        const created = await createFn(buildPayload({ includeClientRequestId: true }));
        id = created?.pricingRequest?.summary?.id;
        if (id != null) setCreatedId(id);
      }
      if (id != null) await submitFn(id);
      onCreated();
    } catch (err) {
      setError(err.message || 'ส่งใบขอราคาไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  // Edit mode (Fix 2): a straight update of the persisted DRAFT — no create
  // step, no submit step, just PUT the full editable representation.
  async function handleUpdate() {
    const validationError = validate();
    if (validationError) { setError(validationError); return; }
    if (!validateItemIdentities()) return;
    setError('');
    setSaving(true);
    try {
      await updateFn(initialSummary.id, buildPayload());
      onCreated();
    } catch (err) {
      setError(err.message || 'บันทึกการแก้ไขไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal
      title={isEdit ? 'แก้ไขร่างใบขอราคา' : 'สร้างใบขอราคา'}
      subtitle={isEdit
        ? 'แก้ไขรายละเอียดใบขอราคานี้ก่อนส่งให้ Import — บันทึกแล้วยังคงเป็นร่างจนกว่าจะกดส่ง'
        : 'ส่งรายการสินค้าให้ฝ่ายนำเข้าเสนอราคา — ไม่รองรับไฟล์แนบในขั้นนี้'}
      onClose={onClose}
      footer={isEdit ? (
        <>
          <button type="button" className="secondary-button" onClick={onClose} disabled={saving}>ยกเลิก</button>
          <button type="button" className="primary-button" disabled={saving} onClick={handleUpdate}>
            <Icon name="check" size={14} />
            {saving ? 'กำลังบันทึก...' : 'บันทึกการแก้ไข'}
          </button>
        </>
      ) : (
        <>
          <button type="button" className="secondary-button" onClick={onClose} disabled={saving}>ยกเลิก</button>
          <button type="button" className="secondary-button" disabled={saving} onClick={handleSaveDraft}>
            {saving ? 'กำลังบันทึก...' : 'บันทึกร่าง'}
          </button>
          <button type="button" className="primary-button" disabled={saving} onClick={handleSubmitToImport}>
            <Icon name="check" size={14} />
            {saving ? 'กำลังส่ง...' : 'ส่งให้ Import'}
          </button>
        </>
      )}
    >
      <div className="flex flex-col gap-4">
        <div>
          <span className="mb-1.5 block text-sm font-bold text-text-secondary">ผู้รับคำขอราคา *</span>
          <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="ผู้รับคำขอราคา">
            {RECIPIENT_OPTIONS.map((option) => {
              const selected = recipientType === option.code;
              return (
                <button
                  key={option.code}
                  type="button"
                  role="radio"
                  aria-checked={selected}
                  className={`rounded-full border px-3 py-1.5 text-xs font-bold ${
                    selected ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface text-text-muted'
                  }`}
                  onClick={() => setRecipientType(option.code)}
                >
                  {option.label}
                </button>
              );
            })}
          </div>
        </div>

        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          ชื่อผู้รับ / บริษัท *
          <input value={recipientLabel} onChange={(e) => setRecipientLabel(e.target.value)} placeholder="เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ" />
        </label>

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
            วันที่ต้องการราคา
            <input type="date" value={requiredDate} onChange={(e) => setRequiredDate(e.target.value)} />
          </label>
          <div className="flex gap-2">
            <label className="flex flex-1 flex-col gap-1.5 text-sm font-bold text-text-secondary">
              ราคาเป้าหมายของลูกค้า
              <input type="number" min="0" step="0.01" value={customerTargetPrice} onChange={(e) => setCustomerTargetPrice(e.target.value)} placeholder="ไม่บังคับ" />
            </label>
            <label className="flex w-24 flex-col gap-1.5 text-sm font-bold text-text-secondary">
              สกุลเงิน
              <input value={targetCurrency} onChange={(e) => setTargetCurrency(e.target.value.toUpperCase())} maxLength={3} />
            </label>
          </div>
        </div>

        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          หมายเหตุถึง Import
          <textarea className="min-h-16" value={note} onChange={(e) => setNote(e.target.value)} placeholder="ข้อมูลเพิ่มเติมสำหรับฝ่ายนำเข้า (ถ้ามี)" />
        </label>

        <div>
          <div className="mb-2 flex items-center justify-between">
            <span className="text-sm font-bold text-text-secondary">รายการสินค้า *</span>
            <button type="button" className="secondary-button" style={{ fontSize: 12 }} onClick={addItem}>
              <Icon name="plus" size={13} /> เพิ่มรายการ
            </button>
          </div>
          <div className="flex flex-col gap-2">
            {items.map((item, index) => (
              <div key={index} className="flex flex-col gap-2 rounded-lg border border-border bg-surface-subtle p-3">
                <div className="flex items-center justify-between">
                  <span className="text-2xs font-bold text-text-muted">
                    รายการที่ {index + 1}
                    {item.sourceTicketItemId ? ` · อ้างอิงจากรายการสินค้าในดีล #${item.sourceTicketItemId}` : ''}
                  </span>
                  {items.length > 1 ? (
                    <button type="button" className="icon-button" aria-label={`ลบรายการที่ ${index + 1}`} onClick={() => removeItem(index)}>
                      <Icon name="close" size={13} />
                    </button>
                  ) : null}
                </div>
                {itemErrors[index] ? (
                  <p role="alert" className="text-2xs font-bold text-danger-dark">{itemErrors[index]}</p>
                ) : null}
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                  <label className="col-span-2 flex flex-col gap-1 text-xs sm:col-span-1">
                    ยี่ห้อ
                    <input value={item.brand} onChange={(e) => updateItem(index, 'brand', e.target.value)} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    รุ่น
                    <input value={item.model} onChange={(e) => updateItem(index, 'model', e.target.value)} />
                  </label>
                  <label className="col-span-2 flex flex-col gap-1 text-xs sm:col-span-3">
                    รายละเอียดสินค้า
                    <input value={item.productDescription} onChange={(e) => updateItem(index, 'productDescription', e.target.value)} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    สี
                    <input value={item.color} onChange={(e) => updateItem(index, 'color', e.target.value)} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    เนื้อผิว
                    <input value={item.texture} onChange={(e) => updateItem(index, 'texture', e.target.value)} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    ขนาด
                    <input value={item.size} onChange={(e) => updateItem(index, 'size', e.target.value)} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    โรงงาน
                    <input value={item.factory} onChange={(e) => updateItem(index, 'factory', e.target.value)} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    จำนวน *
                    <input type="number" min="0.0001" step="0.0001" value={item.requestedQty} onChange={(e) => updateItem(index, 'requestedQty', e.target.value)} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    หน่วย *
                    <input value={item.requestedUnit} onChange={(e) => updateItem(index, 'requestedUnit', e.target.value)} placeholder="แผ่น / ตร.ม." />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    ลักษณะจำนวน *
                    <select value={item.quantityType} onChange={(e) => updateItem(index, 'quantityType', e.target.value)}>
                      {QUANTITY_TYPE_OPTIONS.map((option) => (
                        <option key={option.code} value={option.code}>{option.label}</option>
                      ))}
                    </select>
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    วันที่ต้องการส่งมอบ
                    <input type="date" value={item.targetDeliveryDate} onChange={(e) => updateItem(index, 'targetDeliveryDate', e.target.value)} />
                  </label>
                  <label className="col-span-2 flex flex-col gap-1 text-xs sm:col-span-1">
                    สถานที่ส่งมอบ
                    <input value={item.deliveryLocation} onChange={(e) => updateItem(index, 'deliveryLocation', e.target.value)} />
                  </label>
                  <label className="col-span-2 flex flex-col gap-1 text-xs sm:col-span-3">
                    ข้อกำหนดพิเศษ
                    <input value={item.specialRequirement} onChange={(e) => updateItem(index, 'specialRequirement', e.target.value)} />
                  </label>
                </div>
              </div>
            ))}
          </div>
        </div>

        {info ? <div className="text-xs font-bold text-info" role="status">{info}</div> : null}
        {error ? <div className="form-error" role="alert">{error}</div> : null}
      </div>
    </Modal>
  );
}
