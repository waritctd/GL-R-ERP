import { useRef, useState } from 'react';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { fieldErrorId } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatMoney } from '../../utils/format.js';
import { convertToThb, formatThb } from './catalogPriceDisplay.js';
import {
  applyCatalogPick,
  applyDescriptiveFieldEdit,
  hasRealTileCount,
  CatalogAutocompleteField,
  ITEM_FIELD_META,
  ItemField,
  ItemFieldLabel,
  isRequiredItemField,
  missingQtyMessage,
  REQUIRED_ITEM_FIELD_LABELS,
  requiredItemFieldErrors,
  requiredQtyField,
  searchCatalog,
} from './ticketItemFields.jsx';

/**
 * "รายการสินค้า" — the deal's items table and its edit mode, moved verbatim out of TicketDetailPage.jsx (deal page
 * redesign, 2026-11-16: it now sits on the ใบเสนอราคา tab, under สินค้าในใบล่าสุด). The edit state and the
 * 'editItems.*' field errors are this component's own now; everything else is unchanged:
 *   - `canEditItems` is the page's can.editItems, passed through untouched (EDIT_ITEMS + editable status + sales
 *     role + owner + not a fromPricingChain fallback grid);
 *   - the price columns follow the same ROLE_PERMISSIONS reads as before;
 *   - saving goes through the page's shared action (`onAction(fn, successMsg)`, which toasts and re-reads the deal
 *     and resolves true on success); edit mode closes only when it succeeded, as before.
 */
export function DealItemsPanel({
  ticketId, role, items, st, isOwner, canEditItems, fromPricingChain = false, fxRatesByCurrency = { THB: 1 },
  actionLoading = false, onAction,
}) {
  const can = { editItems: canEditItems };

  // Edit-items mode
  const [editMode, setEditMode] = useState(false);
  const [editDraft, setEditDraft] = useState([]);
  const [editNote, setEditNote] = useState('');
  // Catalog autocomplete for the ยี่ห้อ/รุ่น fields in edit mode — same shape as TicketCreateModal's
  // catalogResults/catalogFocus (`{ index, field }`).
  const [editCatalogResults, setEditCatalogResults] = useState([]);
  const [editCatalogFocus, setEditCatalogFocus] = useState(null);

  // Per-row, per-field required-field errors ('editItems.<field>.<rowIndex>'), and the DOM node behind each key so a
  // failed save can scroll/focus the first invalid control.
  const [fieldErrors, setFieldErrors] = useState({});
  const fieldRefs = useRef({});
  function clearFieldError(key) {
    setFieldErrors((prev) => {
      if (!(key in prev)) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  }
  function setFieldErrorsForPrefix(prefix, errors) {
    setFieldErrors((prev) => {
      const kept = Object.fromEntries(Object.entries(prev).filter(([k]) => !k.startsWith(prefix)));
      return { ...kept, ...errors };
    });
  }
  function focusFirstInvalid(key) {
    const node = fieldRefs.current[key];
    if (!node) return;
    if (typeof node.scrollIntoView === 'function') node.scrollIntoView({ behavior: 'smooth', block: 'center' });
    node.focus();
  }

  // Real keystroke: mutates the row AND searches. Kept separate from onEditCatalogFieldFocus below: re-focusing an
  // already-filled ยี่ห้อ/รุ่น box (no keystroke) through this mutating path would re-run the brand→factory lockstep
  // and catalog-link CLEAR, so tabbing into and out of a field on a server-loaded row wiped
  // catalogPriceId/catalogProductCode before save (data loss).
  function onEditCatalogInput(index, field, value) {
    setEditDraft((d) => d.map((r, i) => (i === index ? applyDescriptiveFieldEdit(r, field, value) : r)));
    clearFieldError(`editItems.${field}.${index}`);
    setEditCatalogFocus({ index, field });
    searchCatalog(value, setEditCatalogResults);
  }
  // Focus-only: search-only, no row mutation.
  function onEditCatalogFieldFocus(index, field, value) {
    setEditCatalogFocus({ index, field });
    if (value) searchCatalog(value, setEditCatalogResults);
  }
  function applyEditCatalogPick(index, cat) {
    setEditDraft((d) => d.map((r, i) => (i === index ? applyCatalogPick(r, cat) : r)));
    Object.keys(REQUIRED_ITEM_FIELD_LABELS).forEach((f) => clearFieldError(`editItems.${f}.${index}`));
    setEditCatalogResults([]);
    setEditCatalogFocus(null);
  }

  // The save: the page's shared action; a success closes edit mode (the page used to do this in resetActionDrafts).
  async function saveItems(fn, successMsg) {
    const ok = await onAction(fn, successMsg);
    if (ok) {
      setEditMode(false);
      setEditDraft([]);
      setEditNote('');
      setFieldErrorsForPrefix('editItems.', {});
    }
  }

  const showProposed = ROLE_PERMISSIONS.canProposePrices.includes(role) || ROLE_PERMISSIONS.canApproveReject.includes(role);
  // ข้อ 10.1: Import sees only rawPrice + proposedPrice — NOT approvedPrice or CEO-set prices
  const showApproved = ROLE_PERMISSIONS.canApproveReject.includes(role) || ROLE_PERMISSIONS.canCreateTickets.includes(role);
  const showCalcBreakdown = ROLE_PERMISSIONS.canApproveReject.includes(role) && items.some((it) => it.calcedCost != null);
  // There is no standalone ราคาตั้ง column: three fixed columns (ยี่ห้อ/รุ่น · สี/เนื้อผิว · จำนวน) are followed by
  // whichever price columns the role may see.
  const itemsGridCols = showCalcBreakdown
    ? 'minmax(0,1.4fr) minmax(0,1fr) minmax(0,0.5fr) minmax(0,0.9fr) minmax(0,0.9fr) minmax(0,0.9fr)'
    : showProposed
      ? 'minmax(0,1.8fr) minmax(0,1.2fr) minmax(0,0.6fr) minmax(0,1.1fr) minmax(0,1.1fr)'
      : 'minmax(0,1.8fr) minmax(0,1.2fr) minmax(0,0.6fr) minmax(0,1.1fr)';

  return (
    <>
      {/* flush + title/actions picks up Panel's standard bordered-header inset (20px/16px,
          border-border-subtle), the same small shift as the CommissionPage port. */}
      <Panel
        flush
        title={`รายการสินค้า (${editMode ? editDraft.length : items.length} รายการ)`}
        actions={can.editItems && !editMode ? (
          <Button type="button" variant="secondary" disabled={actionLoading}
            onClick={() => {
              setEditDraft(items.map((item) => ({ ...item })));
              setEditNote('');
              setEditMode(true);
              setFieldErrorsForPrefix('editItems.', {});
            }}>
            <Icon name="pencil" size={14} />
            แก้ไขรายการสินค้า
          </Button>
        ) : null}
      >
        {editMode ? (
          <div className="p-[14px_18px]">
            {editDraft.map((item, index) => (
              <div key={index} className="mb-2.5 rounded-md border border-border-subtle bg-surface-muted p-[12px_14px]">
                <div className="mb-2 flex items-center justify-between">
                  <span className="text-xs font-bold text-text-muted">รายการที่ {index + 1}</span>
                  {editDraft.length > 1 && (
                    <Button type="button" variant="icon" className="text-danger" aria-label={`ลบรายการที่ ${index + 1}`}
                      onClick={() => {
                        setEditDraft((d) => d.filter((_, i) => i !== index));
                        // Removing a row shifts every later row's index down by one: reindex
                        // their qty errors so an error never ends up pinned to the wrong row.
                        setFieldErrors((prev) => {
                          let changed = false;
                          const next = {};
                          Object.entries(prev).forEach(([k, v]) => {
                            // Any editItems.<field>.<rowIndex> key (brand/model/size/qty), so a
                            // required-field error never ends up pinned to the wrong row after
                            // a delete.
                            const m = k.match(/^editItems\.([a-zA-Z]+)\.(\d+)$/);
                            if (!m) { next[k] = v; return; }
                            const [, field, idxStr] = m;
                            const idx = Number(idxStr);
                            if (idx === index) { changed = true; return; }
                            if (idx > index) { next[`editItems.${field}.${idx - 1}`] = v; changed = true; return; }
                            next[k] = v;
                          });
                          return changed ? next : prev;
                        });
                      }}>
                      <Icon name="close" size={14} />
                    </Button>
                  )}
                </div>
                <div className="grid grid-cols-2 gap-2">
                  {/* Fields/labels/placeholders and the catalog autocomplete on ยี่ห้อ/รุ่น come
                      from ticketItemFields.jsx, shared with TicketCreateModal.jsx. ยี่ห้อ and
                      โรงงาน are one field, same as create (see applyDescriptiveFieldEdit's
                      brand branch). */}
                  <CatalogAutocompleteField
                    id={`edit-item-${index}-brand`}
                    label={ITEM_FIELD_META.brand.label}
                    placeholder={ITEM_FIELD_META.brand.placeholder}
                    required={isRequiredItemField('brand')}
                    value={item.brand || ''}
                    onInput={(value) => onEditCatalogInput(index, 'brand', value)}
                    onFocusSearch={() => onEditCatalogFieldFocus(index, 'brand', item.brand)}
                    onBlur={() => setTimeout(() => setEditCatalogFocus(null), 180)}
                    expanded={editCatalogFocus?.index === index && editCatalogFocus?.field === 'brand'}
                    results={editCatalogResults}
                    onPick={(cat) => applyEditCatalogPick(index, cat)}
                    error={fieldErrors[`editItems.brand.${index}`]}
                    inputRef={(el) => { fieldRefs.current[`editItems.brand.${index}`] = el; }}
                  />
                  <CatalogAutocompleteField
                    id={`edit-item-${index}-model`}
                    label={ITEM_FIELD_META.model.label}
                    placeholder={ITEM_FIELD_META.model.placeholder}
                    required={isRequiredItemField('model')}
                    value={item.model || ''}
                    onInput={(value) => onEditCatalogInput(index, 'model', value)}
                    onFocusSearch={() => onEditCatalogFieldFocus(index, 'model', item.model)}
                    onBlur={() => setTimeout(() => setEditCatalogFocus(null), 180)}
                    expanded={editCatalogFocus?.index === index && editCatalogFocus?.field === 'model'}
                    results={editCatalogResults}
                    onPick={(cat) => applyEditCatalogPick(index, cat)}
                    error={fieldErrors[`editItems.model.${index}`]}
                    inputRef={(el) => { fieldRefs.current[`editItems.model.${index}`] = el; }}
                  />
                  <ItemField
                    id={`edit-item-${index}-size`}
                    label={ITEM_FIELD_META.size.label}
                    required={isRequiredItemField('size')}
                    error={fieldErrors[`editItems.size.${index}`]}
                  >
                    <input
                      id={`edit-item-${index}-size`}
                      ref={(el) => { fieldRefs.current[`editItems.size.${index}`] = el; }}
                      value={item.size || ''}
                      placeholder={ITEM_FIELD_META.size.placeholder}
                      aria-required="true"
                      aria-invalid={fieldErrors[`editItems.size.${index}`] ? true : undefined}
                      aria-describedby={fieldErrors[`editItems.size.${index}`] ? fieldErrorId(`edit-item-${index}-size`) : undefined}
                      onChange={(e) => {
                        setEditDraft((d) => d.map((r, i) => (i === index ? applyDescriptiveFieldEdit(r, 'size', e.target.value) : r)));
                        clearFieldError(`editItems.size.${index}`);
                      }}
                    />
                  </ItemField>
                  <ItemField id={`edit-item-${index}-color`} label={ITEM_FIELD_META.color.label} hint={ITEM_FIELD_META.color.hint}>
                    <input
                      id={`edit-item-${index}-color`}
                      value={item.color || ''}
                      placeholder={ITEM_FIELD_META.color.placeholder}
                      onChange={(e) => setEditDraft((d) => d.map((r, i) => (i === index ? applyDescriptiveFieldEdit(r, 'color', e.target.value) : r)))}
                    />
                  </ItemField>
                  <ItemField id={`edit-item-${index}-texture`} label={ITEM_FIELD_META.texture.label} hint={ITEM_FIELD_META.texture.hint}>
                    <input
                      id={`edit-item-${index}-texture`}
                      value={item.texture || ''}
                      placeholder={ITEM_FIELD_META.texture.placeholder}
                      onChange={(e) => setEditDraft((d) => d.map((r, i) => (i === index ? applyDescriptiveFieldEdit(r, 'texture', e.target.value) : r)))}
                    />
                  </ItemField>
                  {/* Unit basis toggle */}
                  <div className="col-span-full m-0">
                    <span className="mb-1 block text-xs">หน่วยที่ใช้สั่ง</span>
                    <div className="flex flex-wrap items-center gap-4">
                      {[{ value: 'PIECE', label: 'แผ่น' }, { value: 'SQM', label: 'ตร.ม.' }].map((opt) => (
                        <label key={opt.value} className="flex cursor-pointer items-center gap-1.5 text-sm">
                          <input type="radio" name={`editUnitBasis-${index}`} value={opt.value}
                            checked={(item.unitBasis || 'PIECE') === opt.value}
                            onChange={() => setEditDraft((d) => d.map((r, ri) => {
                              if (ri !== index) return r;
                              const u = { ...r, unitBasis: opt.value };
                              if (r.sqmPerPiece) {
                                if (opt.value === 'SQM' && r.qty) u.qtySqm = (Number(r.qty) * r.sqmPerPiece).toFixed(3);
                                if (opt.value === 'PIECE' && r.qtySqm) u.qty = Math.ceil(Number(r.qtySqm) / r.sqmPerPiece);
                              }
                              return u;
                            }))}
                            className="h-4 w-4 cursor-pointer accent-info-dot" />
                          <strong>{opt.label}</strong>
                        </label>
                      ))}
                      {item.sqmPerPiece && (
                        <span className="text-2xs text-text-muted">· 1 แผ่น = {item.sqmPerPiece} ตร.ม.</span>
                      )}
                    </div>
                  </div>

                  {/* Qty inputs */}
                  {(item.unitBasis || 'PIECE') === 'PIECE' ? (
                    <>
                      <label className="m-0 text-xs" htmlFor={`edit-item-qty-${index}`}>
                        <ItemFieldLabel label="จำนวน (แผ่น)" required={requiredQtyField(item.unitBasis) === 'qty'} />
                        <input type="number" value={item.qty ?? ''} step="1"
                          id={`edit-item-qty-${index}`}
                          ref={(el) => { fieldRefs.current[`editItems.qty.${index}`] = el; }}
                          onChange={(e) => {
                            setEditDraft((d) => d.map((r, ri) => {
                              if (ri !== index) return r;
                              const u = { ...r, qty: e.target.value };
                              if (r.sqmPerPiece && e.target.value) u.qtySqm = (Number(e.target.value) * r.sqmPerPiece).toFixed(3);
                              return u;
                            }));
                            clearFieldError(`editItems.qty.${index}`);
                          }}
                          aria-required={requiredQtyField(item.unitBasis) === 'qty' ? 'true' : undefined}
                          aria-invalid={fieldErrors[`editItems.qty.${index}`] ? true : undefined}
                          aria-describedby={fieldErrors[`editItems.qty.${index}`] ? fieldErrorId(`edit-item-qty-${index}`) : undefined}
                        />
                        {fieldErrors[`editItems.qty.${index}`] ? (
                          <p id={fieldErrorId(`edit-item-qty-${index}`)} role="alert" className="mx-0 mb-0 mt-1 text-2xs font-bold text-danger">
                            {fieldErrors[`editItems.qty.${index}`]}
                          </p>
                        ) : null}
                      </label>
                      <div className="m-0">
                        <span className="mb-1 block text-xs">พื้นที่รวม (ตร.ม.)</span>
                        <div className={cn('rounded-md border border-border-subtle bg-surface-muted px-2.5 py-[7px] text-sm', item.qtySqm ? 'text-icon-muted' : 'text-text-muted')}>
                          {item.qtySqm ? `${Number(item.qtySqm).toFixed(3)} ตร.ม.` : '—'}
                        </div>
                      </div>
                    </>
                  ) : (
                    <>
                      <label className="m-0 text-xs" htmlFor={`edit-item-qtysqm-${index}`}>
                        <ItemFieldLabel label="พื้นที่ (ตร.ม.)" required={requiredQtyField(item.unitBasis) === 'qtySqm'} />
                        {/* Governs qty when this row is in SQM mode (qty is derived from qtySqm × sqmPerPiece below) — so the per-row
                         * qty error, if any, attaches here rather than to a non-editable derived display. */}
                        <input type="number" value={item.qtySqm ?? ''} min="0" step="0.001"
                          id={`edit-item-qtysqm-${index}`}
                          ref={(el) => { fieldRefs.current[`editItems.qty.${index}`] = el; }}
                          onChange={(e) => {
                            setEditDraft((d) => d.map((r, ri) => {
                              if (ri !== index) return r;
                              const u = { ...r, qtySqm: e.target.value };
                              if (r.sqmPerPiece && e.target.value) u.qty = Math.ceil(Number(e.target.value) / r.sqmPerPiece);
                              return u;
                            }));
                            clearFieldError(`editItems.qty.${index}`);
                          }}
                          aria-required={requiredQtyField(item.unitBasis) === 'qtySqm' ? 'true' : undefined}
                          aria-invalid={fieldErrors[`editItems.qty.${index}`] ? true : undefined}
                          aria-describedby={fieldErrors[`editItems.qty.${index}`] ? fieldErrorId(`edit-item-qtysqm-${index}`) : undefined}
                        />
                        {fieldErrors[`editItems.qty.${index}`] ? (
                          <p id={fieldErrorId(`edit-item-qtysqm-${index}`)} role="alert" className="mx-0 mb-0 mt-1 text-2xs font-bold text-danger">
                            {fieldErrors[`editItems.qty.${index}`]}
                          </p>
                        ) : null}
                      </label>
                      <div className="m-0">
                        <span className="mb-1 block text-xs">จำนวน (แผ่น)</span>
                        <div className={cn('rounded-md border border-border-subtle bg-surface-muted px-2.5 py-[7px] text-sm', hasRealTileCount(item) ? 'text-icon-muted' : 'text-text-muted')}>
                          {hasRealTileCount(item) ? `${item.qty} แผ่น` : '—'}
                        </div>
                      </div>
                    </>
                  )}
                  {ROLE_PERMISSIONS.canProposePrices.includes(role) && (
                    <label className="col-span-full m-0">
                      <span className="text-xs">ราคาที่เสนอ (บาท)</span>
                      <input type="number" min="0" step="0.01"
                        value={item.proposedPrice ?? ''}
                        placeholder="ราคา/หน่วย"
                        onChange={(e) => setEditDraft((d) => d.map((r, i) => i === index ? { ...r, proposedPrice: e.target.value === '' ? null : Number(e.target.value) } : r))} />
                    </label>
                  )}
                  {/* "จากสต็อก" is only a flag (walk 4): the price is the CEO-approved one, so no price input. */}
                  <div className="col-span-full flex flex-col gap-1.5 rounded-md border border-border-input bg-surface-muted px-3 py-2.5">
                    <label className="m-0 flex cursor-pointer items-center gap-2 text-sm">
                      <input
                        type="checkbox"
                        className="h-4 w-4 cursor-pointer accent-info-dot"
                        checked={Boolean(item.sourcedFromStock)}
                        onChange={(e) => {
                          const checked = e.target.checked;
                          setEditDraft((d) => d.map((r, i) => i === index ? { ...r, sourcedFromStock: checked } : r));
                        }} />
                      <strong>จากสต็อก</strong>
                    </label>
                    <p className="m-0 pl-6 text-2xs font-semibold text-text-muted">
                      สินค้าที่มีอยู่ในคลัง — ราคาขายมาจากการอนุมัติของ CEO
                    </p>
                  </div>
                </div>
              </div>
            ))}
            <Button type="button" variant="secondary"
              onClick={() => setEditDraft((d) => [...d, {
                brand: '', model: '', color: '', texture: '', size: '', qty: 1, proposedPrice: null,
                sourcedFromStock: false,
              }])}
              className="mb-3">
              <Icon name="plus" size={14} /> เพิ่มรายการ
            </Button>
            <label className="mb-2.5 block text-sm">
              หมายเหตุการแก้ไข
              <input value={editNote} onChange={(e) => setEditNote(e.target.value)} placeholder="ระบุสาเหตุที่แก้ไข (ถ้ามี)" className="mt-1" />
            </label>
            <div className="flex gap-2">
              <Button type="button" variant="primary" disabled={actionLoading}
                onClick={() => {
                  // Per-row required-field errors (brand, model, size, then qty/qtySqm), in the
                  // order focusFirstInvalid walks to land on the first invalid control
                  // top-to-bottom.
                  const rowErrors = {};
                  const order = [];
                  editDraft.forEach((item, i) => {
                    const itemErrors = requiredItemFieldErrors(item);
                    for (const field of ['brand', 'model', 'size']) {
                      if (itemErrors[field]) {
                        const key = `editItems.${field}.${i}`;
                        rowErrors[key] = itemErrors[field];
                        order.push(key);
                      }
                    }
                    // The qty rule matches create's basis-aware requiredQtyField. Always
                    // checking item.qty could deadlock the SQM branch: for a catalog row with NO
                    // ตร.ม./แผ่น factor, "จำนวน (แผ่น)" is a read-only derived display in SQM
                    // mode, so a rep who filled พื้นที่ (ตร.ม.) could never satisfy a check on a
                    // field they can't edit. Safe to align: create's zod schema never validates
                    // qty for SQM basis either, and the backend accepts that shape
                    // (TicketItemRequest's qty/qtySqm carry no @NotNull/@Positive,
                    // TicketService#mergeEditedItemsPreservingPricing passes both through, and
                    // sales.ticket_item.qty is NOT NULL with no CHECK > 0). Both payloads
                    // already coerce to `qty: Number(item.qty) || 0`.
                    const qtyField = requiredQtyField(item.unitBasis);
                    const qtyValue = item[qtyField];
                    if (!qtyValue || Number(qtyValue) <= 0) {
                      const key = `editItems.qty.${i}`;
                      rowErrors[key] = missingQtyMessage(item.unitBasis, i + 1);
                      order.push(key);
                    }
                  });
                  if (order.length > 0) {
                    setFieldErrorsForPrefix('editItems.', rowErrors);
                    focusFirstInvalid(order[0]);
                    return;
                  }
                  setFieldErrorsForPrefix('editItems.', {});
                  saveItems(() => api.tickets.editItems(ticketId, {
                    items: editDraft.map((item) => ({
                      brand: item.brand, model: item.model, color: item.color,
                      texture: item.texture, size: item.size,
                      factory: item.factory || null,
                      unitBasis: item.unitBasis || 'PIECE',
                      qty: Number(item.qty) || 0,
                      qtySqm: item.qtySqm != null && item.qtySqm !== '' ? Number(item.qtySqm) : null,
                      proposedPrice: item.proposedPrice != null && item.proposedPrice !== '' ? Number(item.proposedPrice) : null,
                      currency: item.currency ?? 'THB',
                      // Round-trips the deal-creation catalog link: editDraft rows are seeded
                      // from the fetched `items` (which carry it), and the row inputs above
                      // clear it on any descriptive hand-edit.
                      catalogPriceId: item.catalogPriceId ?? null,
                      catalogProductCode: item.catalogProductCode?.trim() || null,
                      // Sent unconditionally (true and false) so un-flagging a stock line reaches the
                      // backend as false, not as an absent field the merge reads as "inherit prior".
                      sourcedFromStock: Boolean(item.sourcedFromStock),
                    })),
                    note: editNote.trim() || null,
                  }), 'บันทึกการแก้ไขแล้ว');
                }}>
                บันทึกการแก้ไข
              </Button>
              <Button type="button" variant="secondary" disabled={actionLoading}
                onClick={() => { setEditMode(false); setEditDraft([]); setEditNote(''); setFieldErrorsForPrefix('editItems.', {}); }}>
                ยกเลิก
              </Button>
            </div>
          </div>
        ) : (
          <>
            {fromPricingChain ? (
              <div className="p-[14px_18px_0]">
                <div className="flex items-start gap-2 rounded-md border border-info-border bg-info-bg px-3 py-2.5 text-xs text-info-dark" data-testid="items-pricing-chain-banner">
                  <Icon name="info" size={15} className="mt-0.5 shrink-0" />
                  <span>รายการจากใบเสนอราคา — จะยืนยันเป็นรายการจริงเมื่อยืนยันคำสั่งซื้อ (แก้ไขได้ที่คำขอราคา/ใบเสนอราคา)</span>
                </div>
              </div>
            ) : null}
            <div className="ticket-items-table table-head" style={{ gridTemplateColumns: itemsGridCols }}>
              <span>ยี่ห้อ / รุ่น</span>
              <span>สี / เนื้อผิว</span>
              <span>จำนวน</span>
              {showCalcBreakdown ? (
                <>
                  <span>ราคาโรงงาน</span>
                  <span>ต้นทุน (THB/ชิ้น)</span>
                  <span>ราคาขาย (THB/ชิ้น)</span>
                </>
              ) : showProposed ? (
                <>
                  <span>ราคาที่เสนอ</span>
                  <span>ราคาที่อนุมัติ</span>
                </>
              ) : (
                <span>ราคาที่อนุมัติ</span>
              )}
            </div>
            {items.length === 0 ? (
              <EmptyState title="ไม่มีรายการสินค้า" />
            ) : items.map((item, i) => (
              <div key={item.id ?? i} className="ticket-items-table data-row" style={{ gridTemplateColumns: itemsGridCols }}>
                <span data-label="ยี่ห้อ / รุ่น">
                  <strong>{item.brand}</strong>
                  {item.model && <small className="text-text-muted">{item.model}</small>}
                  {item.factory && <small className="text-2xs text-text-muted">{item.factory}</small>}
                  {/* A saved จากสต็อก line is flagged here; its price is the CEO-approved one (walk 4). */}
                  {item.sourcedFromStock ? (
                    <span className="mt-0.5 block w-fit"><StatusBadge tone="warning">จากสต็อก</StatusBadge></span>
                  ) : null}
                </span>
                <span data-label="สี / เนื้อผิว" className="flex flex-col gap-0.5">
                  {item.color && <span>{item.color}</span>}
                  {item.texture && <small className="text-text-muted">{item.texture}</small>}
                  {item.size && <small className="text-text-muted">{item.size}</small>}
                </span>
                <span data-label="จำนวน">
                  {item.unitBasis === 'SQM'
                    ? <>{item.qtySqm != null ? `${Number(item.qtySqm).toFixed(2)} ตร.ม.` : '—'}{hasRealTileCount(item) && <small className="block text-text-muted">{item.qty} แผ่น</small>}</>
                    : <>{item.qty} แผ่น{item.qtySqm != null && <small className="block text-text-muted">{Number(item.qtySqm).toFixed(2)} ตร.ม.</small>}</>
                  }
                </span>
                {showCalcBreakdown ? (
                  <>
                    <span data-label="ราคาโรงงาน" className="text-xs">
                      {item.rawPrice != null
                        ? (() => {
                          // Original currency is the figure of record; the baht line underneath is a straight FX conversion (no markup)
                          // and is omitted entirely when no rate exists for that currency, rather than implying a 1:1 rate.
                          const thb = convertToThb(item.rawPrice, item.rawCurrency, fxRatesByCurrency);
                          const unit = item.rawUnit === 'sqm' ? 'ตร.ม.' : 'แผ่น';
                          return (
                            <>
                              <strong>{formatThb(item.rawPrice)}</strong>
                              <small className="text-text-muted"> {item.rawCurrency}/{unit}</small>
                              {thb != null && item.rawCurrency !== 'THB' ? (
                                <small className="block text-text-muted">≈ {formatThb(thb)} บาท/{unit}</small>
                              ) : null}
                            </>
                          );
                        })()
                        : <span className="text-text-muted">-</span>}
                      {item.calcConfigVersion && <small className="block text-2xs text-text-muted">สูตรราคาเวอร์ชัน {item.calcConfigVersion}</small>}
                    </span>
                    <code data-label="ต้นทุน (THB/ชิ้น)" className="text-info">{item.calcedCost != null ? formatMoney(item.calcedCost) : '—'}</code>
                    <span data-label="ราคาขาย (THB/ชิ้น)">
                      <code className={cn('font-bold', item.manualPrice != null ? 'text-override' : 'text-success')}>
                        {item.manualPrice != null ? formatMoney(item.manualPrice) : item.calcedPrice != null ? formatMoney(item.calcedPrice) : '—'}
                      </code>
                      {/* A read-only readout of whatever the 3 stranded legacy tickets already
                          carry (the ticket-native CEO manual override is retired). */}
                      {item.manualPrice != null && <small className="block text-2xs text-override">override</small>}
                    </span>
                  </>
                ) : (
                  <>
                    {showProposed && <code data-label="ราคาที่เสนอ">{formatMoney(item.proposedPrice)}</code>}
                    {showApproved && (
                      <span data-label="ราคาที่อนุมัติ">
                        <code>{formatMoney(item.approvedPrice)}</code>
                      </span>
                    )}
                  </>
                )}
              </div>
            ))}
          </>
        )}

        {/* The empty-draft hint travels with can.editItems into the items panel. */}
        {st === 'draft' && isOwner && items.length === 0 && (
          <div className="p-[0_18px_16px]">
            <span className="rounded-md border border-border bg-surface-subtle px-3 py-2 text-xs text-text-muted">
              งานนี้ยังไม่มีรายการสินค้า — กด “แก้ไขรายการสินค้า” เพื่อเพิ่มก่อนส่งขอราคา
            </span>
          </div>
        )}
      </Panel>
    </>
  );
}
