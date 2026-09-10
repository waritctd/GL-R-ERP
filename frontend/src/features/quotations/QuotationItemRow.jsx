import { useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { formatMoney } from '../../utils/format.js';
import {
  ORIGIN_COUNTRY_OPTIONS, QUANTITY_MODE_OPTIONS, WASTAGE_PERCENT_PRESETS, defaultLeadTimeForOrigin,
} from './quotationMeta.js';

/**
 * One item row of the ใบเสนอราคา editor -- catalog typeahead (autofills, everything stays
 * editable after), quantity mode toggle, wastage segmented control, and the live calculation
 * line the parent keeps up to date via a 300ms-debounced `calculate-line` call (see
 * QuotationEditorPage.jsx's `updateItem`; this component only renders what it is given and
 * reports plain field patches upward).
 *
 * `errors` (optional, defaults to `{}`) is `validateQuotationItem(item)`'s own return value
 * (quotationMeta.js), computed by the parent and passed down already gated on whether this row
 * should show its inline hints yet -- QuotationEditorPage only passes a non-empty object once the
 * row has been touched (not on first paint of a freshly-added blank row), so this component never
 * decides that itself. `error={errors.field}` on a FormField renders the red hint + wires
 * aria-invalid; `required` marks the label with `*`, matching the rest of the app.
 */
export function QuotationItemRow({ item, index, readOnly, errors = {}, onChange, onRemove }) {
  const [catalogResults, setCatalogResults] = useState([]);
  const [catalogOpen, setCatalogOpen] = useState(false);
  // #L4: "กำหนดเอง" opens the custom input -- UI-only state, never written onto `item` itself.
  // The old version jammed `wastageValue: 12` on click (an arbitrary number nobody chose, whose
  // only purpose was to fail the presets check below so the custom input would render). Resets
  // whenever a preset or the +N mode is picked, so it never lingers after the user picks a
  // normal option.
  const [wastageCustomOpen, setWastageCustomOpen] = useState(false);
  // #L2: PER-ROW debounce timer (one ref per mounted row), not a module-level singleton -- the
  // old `_catalogTimer` was shared across every row on the page, so typing in one row's รุ่น
  // field cancelled (and could starve) whatever another row's typeahead had queued.
  const catalogTimer = useRef(null);

  function patch(fields) {
    onChange(fields);
  }

  function debouncedCatalogSearch(q, cb) {
    clearTimeout(catalogTimer.current);
    catalogTimer.current = setTimeout(() => cb(q), 280);
  }

  function onCatalogQuery(value) {
    // #M9: clears ONLY catalogPriceId, not productCode -- a typed รุ่น value invalidates the
    // catalog LINK (this is no longer "the row picked from the dropdown"), but productCode is
    // its own free-text field the user may have typed or edited independently; wiping it just
    // because รุ่น changed threw away data the user never asked to clear.
    patch({ model: value, catalogPriceId: null });
    setCatalogOpen(true);
    debouncedCatalogSearch(value, async (q) => {
      if (!q?.trim()) { setCatalogResults([]); return; }
      try {
        const res = await api.catalog.prices(q, undefined, 20);
        setCatalogResults(res.items ?? []);
      } catch { /* ignore -- typeahead only */ }
    });
  }

  function pickCatalog(cat) {
    // thicknessMm/pcsPerBox/sqmPerBox are additive fields the backend slice is adding to
    // ProductPriceDto alongside this feature -- optional until it lands, per the plan. Autofills
    // brand/รุ่น/สี/ผิว/ขนาด/ความหนา/ตร.ม./แผ่น/แผ่น-กล่อง/รหัสสินค้า -- everything the DTO knows
    // (owner ruling 2026-09-10, "autofill as much as possible") -- reusing the exact mapping
    // PricingRequestCreateModal.jsx:189-207's deriveItemFromCatalogProduct already uses (collection
    // -> productName -> productCode for รุ่น, factoryName for ยี่ห้อ, color/surface/sizeRaw
    // straight across) so the two surfaces never disagree about what a catalog row means.
    //
    // Deliberately does NOT touch `unitPrice` -- `cat.price` is the FACTORY PURCHASE price
    // (price_catalog.product_prices.price), not a selling price; prefilling ราคา/หน่วย from it
    // used to leak that purchase price into the quotation as if it were what to charge the
    // customer (bug, caught in review) -- omitted from the patch entirely so unitPrice is left
    // exactly as the rep already had it.
    patch({
      catalogPriceId: cat.priceId ?? null,
      productCode: cat.productCode ?? null,
      brand: cat.factoryName ?? cat.factory ?? cat.brand ?? item.brand,
      model: cat.collection ?? cat.productName ?? cat.productCode ?? item.model,
      color: cat.color ?? '',
      texture: cat.surface ?? '',
      sizeText: cat.sizeRaw ?? cat.size ?? '',
      thicknessMm: cat.thicknessMm ?? item.thicknessMm ?? null,
      sqmPerPiece: cat.sqmPerPiece ?? item.sqmPerPiece ?? null,
      piecesPerBox: cat.pcsPerBox ?? item.piecesPerBox ?? null,
    });
    setCatalogResults([]);
    setCatalogOpen(false);
  }

  function onOriginChange(value) {
    const defaults = defaultLeadTimeForOrigin(value);
    patch({ originCountry: value, ...defaults });
  }

  return (
    <li className="grid gap-3 rounded-md border border-border p-3.5">
      <div className="flex items-start justify-between gap-3">
        <span className="mt-1.5 inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-surface-subtle text-2xs font-extrabold text-text-muted">
          {index + 1}
        </span>
        {!readOnly ? (
          <Button variant="icon" size="sm" onClick={onRemove} title="ลบรายการ" aria-label="ลบรายการ">
            <Icon name="close" size={16} />
          </Button>
        ) : null}
      </div>

      <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
        {/* Optional by owner ruling 2026-09-10 — a blank label prints no heading row on the
            document (the backend groups only non-blank labels), so a rep who does not need
            it types nothing. */}
        <FormField label="ตำแหน่งติดตั้ง (ไม่บังคับ)" htmlFor={`loc-${index}`} hint="เช่น ชั้น 1 - โซน A · เว้นว่างได้">
          <input
            id={`loc-${index}`}
            list="quotation-location-labels"
            disabled={readOnly}
            value={item.locationLabel ?? ''}
            onChange={(e) => patch({ locationLabel: e.target.value })}
          />
        </FormField>

        <div className="relative">
          <FormField label="รุ่น / ค้นหาแคตตาล็อก" htmlFor={`model-${index}`} required error={errors.model}>
            <input
              id={`model-${index}`}
              disabled={readOnly}
              value={item.model ?? ''}
              autoComplete="off"
              onChange={(e) => onCatalogQuery(e.target.value)}
              onFocus={() => setCatalogOpen(catalogResults.length > 0)}
              onBlur={() => setTimeout(() => setCatalogOpen(false), 150)}
            />
          </FormField>
          {catalogOpen && catalogResults.length > 0 ? (
            <ul className="absolute z-10 mt-1 max-h-64 w-full overflow-auto rounded-md border border-border bg-surface shadow-[var(--shadow-lg-heavy)]">
              {catalogResults.map((cat) => (
                <li key={cat.priceId ?? `${cat.productCode}-${cat.collection}`}>
                  <button
                    type="button"
                    className="block w-full px-3 py-2 text-left text-xs hover:bg-surface-hover"
                    onMouseDown={(e) => { e.preventDefault(); pickCatalog(cat); }}
                  >
                    <strong>{cat.collection ?? cat.productName ?? cat.productCode}</strong>
                    <span className="block text-2xs text-text-muted">
                      {(cat.factoryName ?? cat.factory ?? '-')} · {cat.color ?? '-'} · {cat.sizeRaw ?? cat.size ?? '-'}
                      {cat.price != null ? ` · ${formatMoney(cat.price)}` : ''}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      </div>

      {/* Provenance badge (owner ruling 2026-09-10): reuses PricingRequestCreateModal.jsx's own
          "Catalog #..." badge pattern (text-info, small, non-blocking) so a row that came from
          the catalog says so -- every field it filled stays editable, this is informational, not
          a lock. Cleared automatically the instant รุ่น is retyped (onCatalogQuery nulls
          catalogPriceId, #M9), so it can never lie about a row that no longer matches what was
          picked. */}
      {item.catalogPriceId ? (
        <p role="status" className="m-0 flex items-center gap-1 text-2xs font-bold text-info">
          <Icon name="badgeCheck" size={12} />
          จาก catalog{item.productCode ? ` · ${item.productCode}` : ''}
        </p>
      ) : null}

      <div className="grid grid-cols-4 gap-3 mobile:grid-cols-2">
        <FormField label="ยี่ห้อ" htmlFor={`brand-${index}`}>
          <input id={`brand-${index}`} disabled={readOnly} value={item.brand ?? ''} onChange={(e) => patch({ brand: e.target.value })} />
        </FormField>
        <FormField label="สี" htmlFor={`color-${index}`} required error={errors.color}>
          <input id={`color-${index}`} disabled={readOnly} value={item.color ?? ''} onChange={(e) => patch({ color: e.target.value })} />
        </FormField>
        <FormField label="ผิว" htmlFor={`texture-${index}`} required error={errors.texture}>
          <input id={`texture-${index}`} disabled={readOnly} value={item.texture ?? ''} onChange={(e) => patch({ texture: e.target.value })} />
        </FormField>
        <FormField label="ขนาด (ซม.)" htmlFor={`size-${index}`} hint="เช่น 60x120" required error={errors.sizeText}>
          <input id={`size-${index}`} disabled={readOnly} value={item.sizeText ?? ''} onChange={(e) => patch({ sizeText: e.target.value })} />
        </FormField>
        <FormField label="ความหนา (มม.)" htmlFor={`thickness-${index}`} required error={errors.thicknessMm}>
          <input
            id={`thickness-${index}`} type="number" step="0.1" disabled={readOnly}
            value={item.thicknessMm ?? ''}
            onChange={(e) => patch({ thicknessMm: e.target.value === '' ? null : Number(e.target.value) })}
          />
        </FormField>
        <FormField label="ตร.ม./แผ่น" htmlFor={`sqm-${index}`} required error={errors.sqmPerPiece}>
          <input
            id={`sqm-${index}`} type="number" step="0.0001" disabled={readOnly}
            value={item.sqmPerPiece ?? ''}
            onChange={(e) => patch({ sqmPerPiece: e.target.value === '' ? null : Number(e.target.value) })}
          />
        </FormField>
        <FormField label="แผ่น/กล่อง" htmlFor={`ppb-${index}`} required error={errors.piecesPerBox}>
          <input
            id={`ppb-${index}`} type="number" disabled={readOnly}
            value={item.piecesPerBox ?? ''}
            onChange={(e) => patch({ piecesPerBox: e.target.value === '' ? null : Number(e.target.value) })}
          />
        </FormField>
        <FormField label="รหัสสินค้า" htmlFor={`code-${index}`}>
          <input id={`code-${index}`} disabled={readOnly} value={item.productCode ?? ''} onChange={(e) => patch({ productCode: e.target.value })} />
        </FormField>
      </div>

      <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
        <FormField label="จำนวน" htmlFor={`qty-${index}`} required error={item.quantityMode === 'PIECES' ? errors.piecesInput : errors.areaSqm}>
          <div className="flex gap-2">
            <div className="inline-flex overflow-hidden rounded-md border border-border-input">
              {QUANTITY_MODE_OPTIONS.map((opt) => (
                <button
                  key={opt.code}
                  type="button"
                  disabled={readOnly}
                  aria-pressed={item.quantityMode === opt.code}
                  className={`min-h-[38px] px-3 text-xs font-bold ${item.quantityMode === opt.code ? 'bg-primary text-surface' : 'bg-surface text-icon-muted'}`}
                  onClick={() => patch({ quantityMode: opt.code })}
                >
                  {opt.label}
                </button>
              ))}
            </div>
            <input
              id={`qty-${index}`}
              type="number"
              disabled={readOnly}
              className="flex-1"
              value={(item.quantityMode === 'PIECES' ? item.piecesInput : item.areaSqm) ?? ''}
              onChange={(e) => {
                const value = e.target.value === '' ? '' : Number(e.target.value);
                patch(item.quantityMode === 'PIECES' ? { piecesInput: value } : { areaSqm: value });
              }}
            />
          </div>
        </FormField>

        <FormField label="เผื่อ (wastage)" htmlFor={`waste-${index}`}>
          <div className="flex flex-wrap gap-2">
            {WASTAGE_PERCENT_PRESETS.map((pct) => (
              <button
                key={pct}
                type="button"
                disabled={readOnly}
                aria-pressed={item.wastageMode === 'PERCENT' && !wastageCustomOpen && Number(item.wastageValue) === pct}
                className={`min-h-[38px] rounded-md border px-3 text-xs font-bold ${
                  item.wastageMode === 'PERCENT' && !wastageCustomOpen && Number(item.wastageValue) === pct
                    ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'
                }`}
                onClick={() => { setWastageCustomOpen(false); patch({ wastageMode: 'PERCENT', wastageValue: pct }); }}
              >
                {pct}%
              </button>
            ))}
            <button
              type="button"
              disabled={readOnly}
              aria-pressed={item.wastageMode === 'PIECES'}
              className={`min-h-[38px] rounded-md border px-3 text-xs font-bold ${
                item.wastageMode === 'PIECES' ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'
              }`}
              onClick={() => { setWastageCustomOpen(false); patch({ wastageMode: 'PIECES', wastageValue: item.wastageMode === 'PIECES' ? item.wastageValue : 0 }); }}
            >
              +N แผ่น
            </button>
            {(item.wastageMode === 'PERCENT' && (wastageCustomOpen || !WASTAGE_PERCENT_PRESETS.includes(Number(item.wastageValue))))
            || item.wastageMode === 'PIECES' ? (
              <input
                id={`waste-${index}`}
                type="number"
                disabled={readOnly}
                className="w-20"
                value={item.wastageValue ?? ''}
                onChange={(e) => patch({ wastageValue: e.target.value === '' ? 0 : Number(e.target.value) })}
              />
            ) : null}
            {item.wastageMode === 'PERCENT' ? (
              <button
                type="button"
                disabled={readOnly}
                aria-pressed={wastageCustomOpen || !WASTAGE_PERCENT_PRESETS.includes(Number(item.wastageValue))}
                className="min-h-[38px] rounded-md border border-border bg-surface px-3 text-xs font-bold text-text-muted"
                onClick={() => setWastageCustomOpen(true)}
              >
                กำหนดเอง
              </button>
            ) : null}
          </div>
        </FormField>
      </div>

      <div className="grid grid-cols-4 gap-3 mobile:grid-cols-2">
        <FormField label="ราคา/หน่วย" htmlFor={`price-${index}`} required error={errors.unitPrice}>
          <input
            id={`price-${index}`} type="number" step="0.01" disabled={readOnly}
            value={item.unitPrice ?? ''}
            onChange={(e) => patch({ unitPrice: e.target.value === '' ? '' : Number(e.target.value) })}
          />
        </FormField>
        <FormField label="ส่วนลด %" htmlFor={`disc-${index}`}>
          <input
            id={`disc-${index}`} type="number" step="0.01" disabled={readOnly}
            value={item.discountPct ?? 0}
            onChange={(e) => patch({ discountPct: e.target.value === '' ? 0 : Number(e.target.value) })}
          />
        </FormField>
        <FormField label="ประเทศต้นทาง" htmlFor={`origin-${index}`}>
          <select id={`origin-${index}`} disabled={readOnly} value={item.originCountry ?? ''} onChange={(e) => onOriginChange(e.target.value)}>
            <option value="">-</option>
            {ORIGIN_COUNTRY_OPTIONS.map((opt) => <option key={opt.code} value={opt.code}>{opt.label}</option>)}
          </select>
        </FormField>
        <FormField label="ระยะเวลานำเข้า (วัน)" htmlFor={`lead-${index}`}>
          <div className="flex items-center gap-1.5">
            <input
              id={`lead-${index}`} type="number" disabled={readOnly} className="w-16"
              value={item.leadTimeMinDays ?? ''}
              onChange={(e) => patch({ leadTimeMinDays: e.target.value === '' ? null : Number(e.target.value) })}
            />
            <span className="text-text-muted">–</span>
            {/* #L1: its own id + label -- FormField only wires the ONE control whose id matches
                its own htmlFor (the min input above), so this one had neither, which is invisible
                to a screen reader. sr-only rather than a second visible "ระยะเวลานำเข้า (วัน)" --
                the range reads as one field visually, min/max just need their own names. */}
            <label htmlFor={`lead-max-${index}`} className="sr-only">ถึง (วัน)</label>
            <input
              id={`lead-max-${index}`} type="number" disabled={readOnly} className="w-16"
              value={item.leadTimeMaxDays ?? ''}
              onChange={(e) => patch({ leadTimeMaxDays: e.target.value === '' ? null : Number(e.target.value) })}
            />
          </div>
        </FormField>
      </div>

      <FormField label="หมายเหตุรายการ" htmlFor={`notes-${index}`}>
        <input id={`notes-${index}`} disabled={readOnly} value={item.itemNotes ?? ''} onChange={(e) => patch({ itemNotes: e.target.value })} />
      </FormField>

      {/* Live calculation line -- from calculate-line, debounced by the parent. */}
      <div className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-surface-subtle px-3 py-2.5">
        <p className="m-0 min-w-0 flex-1 text-xs text-text-muted">
          {item.calcPending ? 'กำลังคำนวณ...' : (item.calculationLine || 'กรอกจำนวนและเผื่อเพื่อคำนวณ')}
        </p>
        <span className="tabular-nums text-md font-extrabold text-text">{formatMoney(item.lineAmount)}</span>
      </div>
    </li>
  );
}

export function itemInputFromRow(item) {
  return {
    locationLabel: item.locationLabel || null,
    catalogPriceId: item.catalogPriceId ?? null,
    productCode: item.productCode || null,
    brand: item.brand || null,
    model: item.model || null,
    color: item.color || null,
    texture: item.texture || null,
    sizeText: item.sizeText || null,
    thicknessMm: item.thicknessMm ?? null,
    sqmPerPiece: item.sqmPerPiece ?? null,
    quantityMode: item.quantityMode ?? 'AREA',
    areaSqm: item.areaSqm === '' ? null : item.areaSqm,
    piecesInput: item.piecesInput === '' ? null : item.piecesInput,
    wastageMode: item.wastageMode ?? 'NONE',
    wastageValue: item.wastageValue ?? 0,
    piecesPerBox: item.piecesPerBox === '' ? null : item.piecesPerBox,
    unitPrice: item.unitPrice === '' ? null : item.unitPrice,
    discountPct: item.discountPct ?? 0,
    originCountry: item.originCountry || null,
    leadTimeMinDays: item.leadTimeMinDays ?? null,
    leadTimeMaxDays: item.leadTimeMaxDays ?? null,
    itemNotes: item.itemNotes || null,
  };
}

export function emptyQuotationItem() {
  return {
    clientId: crypto.randomUUID?.() ?? `tmp-${Date.now()}-${Math.random()}`,
    locationLabel: '', catalogPriceId: null, productCode: '',
    brand: '', model: '', color: '', texture: '', sizeText: '', thicknessMm: null, sqmPerPiece: null,
    quantityMode: 'AREA', areaSqm: '', piecesInput: '',
    wastageMode: 'PERCENT', wastageValue: 0, piecesPerBox: '',
    unitPrice: '', discountPct: 0,
    originCountry: '', leadTimeMinDays: null, leadTimeMaxDays: null,
    itemNotes: '',
    piecesPerSqm: null, piecesBeforeWastage: null, piecesAfterWastage: null, piecesFinal: null, boxes: null,
    netUnitPrice: null, lineAmount: null, descriptionLine: '', sizeLine: '', calculationLine: '',
    calcPending: false,
  };
}
