import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import {
  PLAIN_ROW_PRESETS, PLAIN_UNIT_OPTIONS, UNLABELLED_LOCATION_TEXT, formatQuotationMoney,
} from './quotationMeta.js';

/**
 * A PLAIN row of the ใบเสนอราคา editor — quotation v3 S2 (owner feedback pass 3, 2026-09-11):
 * freight, consumables, a cut service, sanitary ware. Description, quantity, unit, unit price and
 * NONE of the tile machinery (no wastage, no ตร.ม./แผ่น, no catalog link, no composed description)
 * — DealQuotationService#buildPlainItem computes `amount = quantity × net price` and nothing else.
 *
 * Least typing: หน่วย is a controlled list (PLAIN_UNIT_OPTIONS — the units the owner's documents
 * actually print, the current document language's first), and a blank row offers one-click presets
 * for the lines her documents repeat (ค่าขนส่ง / ค่าบริการตัดกระเบื้อง / Transportation Charges).
 *
 * Same parent contract as QuotationItemRow: it renders what it is given and reports field patches
 * upward; the live amount arrives on `item.lineAmount` from the parent's debounced calculate-line.
 */
export function QuotationPlainItemRow({
  item, index, readOnly, errors = {}, onChange, onRemove,
  groupId = null, locationGroups = [], onMove, documentLanguage = 'TH', currency = 'THB',
}) {
  const moveTargets = locationGroups.filter((group) => group.groupId !== groupId);
  const primaryUnits = PLAIN_UNIT_OPTIONS[documentLanguage] ?? PLAIN_UNIT_OPTIONS.TH;
  const otherUnits = documentLanguage === 'EN' ? PLAIN_UNIT_OPTIONS.TH : PLAIN_UNIT_OPTIONS.EN;
  // A stored unit outside the list (a row saved by another client, or before the list changed)
  // is kept as an extra option rather than silently blanked — blanking it would change the
  // printed document on the next save without the rep having touched the field.
  const knownUnit = !item.unit || [...primaryUnits, ...otherUnits].includes(item.unit);
  const presets = PLAIN_ROW_PRESETS[documentLanguage] ?? [];
  const blank = !item.description && !item.unit;

  return (
    <li className="grid gap-3 rounded-md border border-border p-3.5" data-line-type="PLAIN">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <span className="inline-flex min-w-0 items-center gap-2">
          <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-surface-subtle text-2xs font-extrabold text-text-muted">
            {index + 1}
          </span>
          <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">สินค้า/บริการอื่น</span>
        </span>
        {!readOnly ? (
          <div className="flex min-w-0 flex-1 flex-wrap items-center justify-end gap-1.5">
            {moveTargets.length ? (
              <>
                <label htmlFor={`plain-move-${index}`} className="sr-only">ย้ายไปตำแหน่ง</label>
                {/* Same width discipline as QuotationItemRow's move select, for the same measured
                    reason: an option carries a user-typed label, so the control must be allowed to
                    shrink (`min-w-0`) or it pushes the page wide on a phone. */}
                <select
                  id={`plain-move-${index}`}
                  className="h-8 mobile:h-11 w-full min-w-0 py-0 text-2xs sm:w-auto sm:min-w-[9.5rem] sm:max-w-[13rem]"
                  value=""
                  onChange={(e) => { if (e.target.value) onMove?.(e.target.value); }}
                >
                  <option value="">ย้ายไปตำแหน่ง…</option>
                  {moveTargets.map((group) => (
                    <option key={group.groupId} value={group.groupId}>{group.label || UNLABELLED_LOCATION_TEXT}</option>
                  ))}
                </select>
              </>
            ) : null}
            <Button variant="icon" size="sm" className="mobile:min-h-[44px] mobile:w-11" onClick={onRemove} title="ลบรายการ" aria-label="ลบรายการ">
              <Icon name="close" size={16} />
            </Button>
          </div>
        ) : null}
      </div>

      {!readOnly && blank && presets.length ? (
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">ใช้บ่อย</span>
          {presets.map((preset) => (
            <button
              key={preset.label}
              type="button"
              className="inline-flex min-h-7 mobile:min-h-[44px] items-center rounded-full border border-border bg-surface-subtle px-2.5 text-2xs font-bold text-text-muted hover:border-primary hover:text-primary"
              onClick={() => onChange({
                description: preset.description,
                unit: preset.unit,
                ...(preset.quantity != null ? { quantity: preset.quantity } : {}),
              })}
            >
              {preset.label}
            </button>
          ))}
        </div>
      ) : null}

      <FormField label="รายละเอียด" htmlFor={`plain-desc-${index}`} required error={errors.description}>
        <input
          id={`plain-desc-${index}`}
          disabled={readOnly}
          maxLength={2000}
          value={item.description ?? ''}
          placeholder={documentLanguage === 'EN' ? 'e.g. Mapei Adhesive (20kg/Bag)' : 'เช่น ค่าขนส่ง, กาวซีเมนต์ Mapei (20kg/ถุง)'}
          onChange={(e) => onChange({ description: e.target.value })}
        />
      </FormField>

      <div className="grid grid-cols-4 gap-3 mobile:grid-cols-2">
        <FormField label="จำนวน" htmlFor={`plain-qty-${index}`} required error={errors.quantity}>
          <input
            id={`plain-qty-${index}`} type="number" step="0.01" min="0" disabled={readOnly}
            value={item.quantity ?? ''}
            onChange={(e) => onChange({ quantity: e.target.value === '' ? '' : Number(e.target.value) })}
          />
        </FormField>
        <FormField label="หน่วย" htmlFor={`plain-unit-${index}`} required error={errors.unit}>
          <select id={`plain-unit-${index}`} disabled={readOnly} value={item.unit ?? ''} onChange={(e) => onChange({ unit: e.target.value })}>
            <option value="">-</option>
            {!knownUnit ? <option value={item.unit}>{item.unit}</option> : null}
            {primaryUnits.map((unit) => <option key={unit} value={unit}>{unit}</option>)}
            <optgroup label={documentLanguage === 'EN' ? 'ภาษาไทย' : 'English'}>
              {otherUnits.map((unit) => <option key={unit} value={unit}>{unit}</option>)}
            </optgroup>
          </select>
        </FormField>
        <FormField label="ราคา/หน่วย" htmlFor={`plain-price-${index}`} required error={errors.unitPrice}>
          <input
            id={`plain-price-${index}`} type="number" step="0.01" min="0" disabled={readOnly}
            value={item.unitPrice ?? ''}
            onChange={(e) => onChange({ unitPrice: e.target.value === '' ? '' : Number(e.target.value) })}
          />
        </FormField>
        <FormField label="ส่วนลด %" htmlFor={`plain-disc-${index}`} hint="ปกติ 0 (พิมพ์ Net)">
          <input
            id={`plain-disc-${index}`} type="number" step="0.01" min="0" disabled={readOnly}
            value={item.discountPct ?? 0}
            onChange={(e) => onChange({ discountPct: e.target.value === '' ? 0 : Number(e.target.value) })}
          />
        </FormField>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-surface-subtle px-3 py-2.5">
        <p className="m-0 min-w-0 flex-1 text-xs text-text-muted">
          {item.calcPending ? 'กำลังคำนวณ...' : 'จำนวน × ราคา/หน่วย'}
        </p>
        <span className="tabular-nums text-md font-extrabold text-text">{formatQuotationMoney(item.lineAmount, currency)}</span>
      </div>
    </li>
  );
}
