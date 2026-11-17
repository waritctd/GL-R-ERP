import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { fieldErrorId } from '../../components/common/FormField.jsx';
import {
  applyCatalogPick,
  applyDescriptiveFieldEdit,
  CatalogAutocompleteField,
  ITEM_FIELD_META,
  ItemField,
  isRequiredItemField,
  missingQtyMessage,
  REQUIRED_ITEM_FIELD_LABELS,
  requiredItemFieldErrors,
  requiredQtyField,
  searchCatalog,
} from './ticketItemFields.jsx';

/**
 * Can this creator edit the deal's items AFTER creating it? Mirrors TicketService.editItems (backend
 * th.co.glr.hr.ticket.TicketService#editItems): `SALES_ROLES.contains(role) && isOwnerOrKeyer && status in {DRAFT, …}`,
 * with SALES_ROLES = {"sales"}. A deal's creator is always its owner or keyer and a new deal is always DRAFT, so for
 * the creator it reduces to the role. Pinned server-side by TicketServiceTest#editItems_rejectsSalesManagerRole.
 * Presentation only — the server decides; this just decides whether the create form must offer items itself.
 */
export function canEditDealItemsAfterCreate(user) {
  return user?.role === 'sales';
}

export const emptyCreateItem = () => ({
  brand: '', model: '', color: '', texture: '', size: '', factory: '',
  unitBasis: 'PIECE', qty: '', qtySqm: '', sqmPerPiece: null,
  source: 'custom', catalogPriceId: null, catalogProductCode: '', sourcedFromStock: false,
});

/** Every invalid field of every row, keyed `items.<i>.<field>` — the old create form's per-row rules
 * (requiredItemFieldErrors + the basis's quantity). */
export function validateCreateItems(items) {
  const errors = {};
  items.forEach((item, index) => {
    for (const [field, message] of Object.entries(requiredItemFieldErrors(item))) errors[`items.${index}.${field}`] = message;
    const qtyField = requiredQtyField(item.unitBasis);
    if (!item[qtyField] || Number(item[qtyField]) <= 0) {
      errors[`items.${index}.${qtyField}`] = missingQtyMessage(item.unitBasis, index + 1);
    }
  });
  return errors;
}

/** The request rows, exactly as the old accordion form's submit mapped them. */
export function createItemsPayload(items) {
  return items.map((item) => ({
    brand: item.brand.trim(),
    model: item.model.trim(),
    color: item.color.trim() || null,
    texture: item.texture.trim() || null,
    size: item.size.trim(),
    factory: (item.factory || item.brand).trim() || null,
    unitBasis: item.unitBasis || 'PIECE',
    qty: Number(item.qty) || 0,
    qtySqm: item.qtySqm !== '' && item.qtySqm != null ? Number(item.qtySqm) : null,
    catalogPriceId: item.catalogPriceId ?? null,
    catalogProductCode: item.catalogProductCode?.trim() || null,
    sourcedFromStock: Boolean(item.sourcedFromStock),
  }));
}

/**
 * รายการสินค้า for a creator who cannot add items later (canEditDealItemsAfterCreate is false): collapsed and
 * optional, built from the shared item fields (ticketItemFields.jsx) the deal page's items panel uses.
 */
export function CreateDealItems({ items, onChange, errors = {}, onClearError }) {
  const [open, setOpen] = useState(items.length > 0);
  const [catalogResults, setCatalogResults] = useState([]);
  const [catalogFocus, setCatalogFocus] = useState(null); // { index, field }

  function update(index, field, value) {
    onClearError?.(`items.${index}.${field}`);
    onChange(items.map((item, i) => {
      if (i !== index) return item;
      let next = applyDescriptiveFieldEdit(item, field, value);
      if (field === 'qty' && item.sqmPerPiece) next = { ...next, qtySqm: value ? (Number(value) * item.sqmPerPiece).toFixed(3) : '' };
      if (field === 'qtySqm' && item.sqmPerPiece) next = { ...next, qty: value ? Math.ceil(Number(value) / item.sqmPerPiece) : '' };
      return next;
    }));
  }

  function onCatalogInput(index, field, value) {
    update(index, field, value);
    setCatalogFocus({ index, field });
    searchCatalog(value, setCatalogResults);
  }

  function pick(index, cat) {
    onChange(items.map((item, i) => (i === index ? applyCatalogPick(item, cat) : item)));
    Object.keys(REQUIRED_ITEM_FIELD_LABELS).forEach((field) => onClearError?.(`items.${index}.${field}`));
    setCatalogResults([]);
    setCatalogFocus(null);
  }

  return (
    <div className="border-t border-border-subtle pt-1">
      <Button
        type="button"
        variant="text"
        className="min-h-11 gap-1.5 px-0 text-sm mobile:min-h-11"
        aria-expanded={open}
        aria-controls="deal-create-items"
        onClick={() => setOpen((v) => !v)}
      >
        <Icon name={open ? 'chevronDown' : 'chevronRight'} size={14} />
        {`รายการสินค้า (ไม่บังคับ)${items.length ? ` · ${items.length} รายการ` : ''}`}
      </Button>
      {open ? (
        <div id="deal-create-items" className="mt-2 grid gap-3">
          <p className="m-0 text-xs text-text-muted">เพิ่มได้ตอนนี้ — หลังสร้างแล้ว รายการสินค้าแก้ได้เฉพาะพนักงานขายเจ้าของงาน</p>
          {items.map((item, index) => {
            const basis = item.unitBasis || 'PIECE';
            const qtyError = errors[`items.${index}.${requiredQtyField(basis)}`];
            return (
              <div key={index} className="grid gap-3 rounded-lg border border-border bg-surface p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-sm font-extrabold text-text">{`รายการที่ ${index + 1}`}</span>
                  <Button variant="icon" aria-label={`ลบรายการที่ ${index + 1}`} className="text-danger" onClick={() => onChange(items.filter((_, i) => i !== index))}>
                    <Icon name="close" size={14} />
                  </Button>
                </div>
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  {['brand', 'model'].map((field) => (
                    <CatalogAutocompleteField
                      key={field}
                      id={`create-item-${index}-${field}`}
                      label={ITEM_FIELD_META[field].label}
                      required={isRequiredItemField(field)}
                      value={item[field]}
                      placeholder={ITEM_FIELD_META[field].placeholder}
                      onInput={(value) => onCatalogInput(index, field, value)}
                      onFocusSearch={() => { setCatalogFocus({ index, field }); if (item[field]) searchCatalog(item[field], setCatalogResults); }}
                      onBlur={() => setTimeout(() => setCatalogFocus(null), 180)}
                      expanded={catalogFocus?.index === index && catalogFocus?.field === field}
                      results={catalogResults}
                      onPick={(cat) => pick(index, cat)}
                      error={errors[`items.${index}.${field}`]}
                    />
                  ))}
                  {['size', 'color', 'texture'].map((field) => (
                    <ItemField
                      key={field}
                      id={`create-item-${index}-${field}`}
                      label={ITEM_FIELD_META[field].label}
                      hint={ITEM_FIELD_META[field].hint}
                      required={isRequiredItemField(field)}
                      error={errors[`items.${index}.${field}`]}
                    >
                      <input
                        id={`create-item-${index}-${field}`}
                        className="min-h-11"
                        value={item[field]}
                        placeholder={ITEM_FIELD_META[field].placeholder}
                        onChange={(e) => update(index, field, e.target.value)}
                        aria-invalid={errors[`items.${index}.${field}`] ? true : undefined}
                        aria-describedby={errors[`items.${index}.${field}`] ? fieldErrorId(`create-item-${index}-${field}`) : undefined}
                      />
                    </ItemField>
                  ))}
                  <ItemField id={`create-item-${index}-qty`} label={basis === 'SQM' ? 'พื้นที่ (ตร.ม.)' : 'จำนวน (แผ่น)'} required error={qtyError}>
                    <input
                      id={`create-item-${index}-qty`}
                      type="number"
                      min="0"
                      step={basis === 'SQM' ? '0.001' : '1'}
                      className="min-h-11"
                      value={basis === 'SQM' ? item.qtySqm : item.qty}
                      onChange={(e) => update(index, basis === 'SQM' ? 'qtySqm' : 'qty', e.target.value)}
                      aria-invalid={qtyError ? true : undefined}
                    />
                  </ItemField>
                </div>
                <div className="flex flex-wrap items-center gap-4">
                  <div role="radiogroup" aria-label={`หน่วยที่ใช้สั่ง รายการที่ ${index + 1}`} className="flex gap-4">
                    {[{ value: 'PIECE', label: 'แผ่น' }, { value: 'SQM', label: 'ตร.ม.' }].map((opt) => (
                      <label key={opt.value} className="m-0 flex min-h-11 cursor-pointer items-center gap-1.5 text-sm">
                        <input type="radio" name={`create-unit-${index}`} checked={basis === opt.value} onChange={() => update(index, 'unitBasis', opt.value)} />
                        {opt.label}
                      </label>
                    ))}
                  </div>
                  <label className="m-0 flex min-h-11 cursor-pointer items-center gap-2 text-sm">
                    <input type="checkbox" checked={Boolean(item.sourcedFromStock)} onChange={(e) => update(index, 'sourcedFromStock', e.target.checked)} />
                    จากสต็อก
                  </label>
                </div>
              </div>
            );
          })}
          <Button type="button" variant="secondary" onClick={() => onChange([...items, emptyCreateItem()])}>
            <Icon name="plus" size={14} /> เพิ่มรายการสินค้า
          </Button>
        </div>
      ) : null}
    </div>
  );
}
