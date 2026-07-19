import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { QUANTITY_TYPE_OPTIONS, RECIPIENT_OPTIONS } from './pricingRequestMeta.js';

function emptyItemFromTicketItem(ticketItem) {
  return {
    sourceTicketItemId: ticketItem?.id ?? null,
    brand: ticketItem?.brand ?? '',
    model: ticketItem?.model ?? '',
    color: ticketItem?.color ?? '',
    texture: ticketItem?.texture ?? '',
    size: ticketItem?.size ?? '',
    factory: ticketItem?.factory ?? '',
    requestedQty: ticketItem?.qty ?? 1,
    requestedUnit: 'แผ่น',
    quantityType: 'ESTIMATE',
    targetDeliveryDate: '',
    deliveryLocation: '',
    specialRequirement: '',
  };
}

/**
 * Create/submit modal for a new PricingRequest (commit 6). No attachments —
 * explicitly cut from scope (see handoff). Item rows seed from the deal's
 * existing ticket_item rows (carrying sourceTicketItemId so Import can trace
 * a request line back to the original product line), but every field stays
 * editable — PricingRequestItemRequest carries its own independent
 * descriptive copy, not a read-only mirror of ticket_item.
 *
 * recipientContactId (a real customer-contact picker) is not wired up yet —
 * recipientLabel (free text) is the only way to identify a recipient today.
 * PricingRequestService.validateRecipientIdentifiable accepts either.
 */
export function PricingRequestCreateModal({ ticketItems = [], onClose, onCreated, createFn, submitFn }) {
  const [recipientType, setRecipientType] = useState('DESIGNER');
  const [recipientLabel, setRecipientLabel] = useState('');
  const [requiredDate, setRequiredDate] = useState('');
  const [customerTargetPrice, setCustomerTargetPrice] = useState('');
  const [targetCurrency, setTargetCurrency] = useState('THB');
  const [note, setNote] = useState('');
  const [items, setItems] = useState(() => (
    ticketItems.length ? ticketItems.map(emptyItemFromTicketItem) : [emptyItemFromTicketItem(null)]
  ));
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  function updateItem(index, field, value) {
    setItems((cur) => cur.map((item, i) => (i === index ? { ...item, [field]: value } : item)));
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

  function buildPayload() {
    return {
      recipientType,
      recipientLabel: recipientLabel.trim(),
      requiredDate: requiredDate || null,
      customerTargetPrice: customerTargetPrice !== '' ? Number(customerTargetPrice) : null,
      targetCurrency: targetCurrency.trim() || null,
      note: note.trim() || null,
      items: items.map((item) => ({
        sourceTicketItemId: item.sourceTicketItemId ?? null,
        brand: item.brand?.trim() || null,
        model: item.model?.trim() || null,
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
  }

  async function handleSaveDraft() {
    const validationError = validate();
    if (validationError) { setError(validationError); return; }
    setError('');
    setSaving(true);
    try {
      await createFn(buildPayload());
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
    setError('');
    setSaving(true);
    try {
      const created = await createFn(buildPayload());
      const id = created?.pricingRequest?.summary?.id;
      if (id != null) await submitFn(id);
      onCreated();
    } catch (err) {
      setError(err.message || 'ส่งใบขอราคาไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal
      title="สร้างใบขอราคา"
      subtitle="ส่งรายการสินค้าให้ฝ่ายนำเข้าเสนอราคา — ไม่รองรับไฟล์แนบในขั้นนี้"
      onClose={onClose}
      footer={(
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
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                  <label className="col-span-2 flex flex-col gap-1 text-xs sm:col-span-1">
                    ยี่ห้อ
                    <input value={item.brand} onChange={(e) => updateItem(index, 'brand', e.target.value)} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    รุ่น
                    <input value={item.model} onChange={(e) => updateItem(index, 'model', e.target.value)} />
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

        {error ? <div className="form-error" role="alert">{error}</div> : null}
      </div>
    </Modal>
  );
}
