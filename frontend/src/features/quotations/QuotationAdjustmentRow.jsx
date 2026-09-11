import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { formatQuotationMoney } from './quotationMeta.js';

const KIND_OPTIONS = [
  { code: 'PERCENT', label: 'เป็น %' },
  { code: 'AMOUNT', label: 'เป็นจำนวนเงิน' },
];

/**
 * A ส่วนลดพิเศษ row — quotation v3 S3 (owner feedback pass 3, 2026-09-11). The rep types a percent
 * and (optionally) a date; the amount and the description are DERIVED and shown, never typed:
 * "ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569", −฿38,198.21 on her QN6900704-2. A flat baht
 * amount is the alternative for the case that is not a percentage.
 *
 * `amount` / `description` are what to SHOW, and the parent decides where they come from: the
 * server's own saved figures when the editor is clean, or a labelled estimate while it holds
 * unsaved edits (`estimated` — see quotationMeta#estimateAdjustmentAmount for why the live preview
 * endpoint cannot produce this figure). Every adjustment prints LAST on the document, so the parent
 * renders these after all ตำแหน่งติดตั้ง groups rather than inside one.
 */
export function QuotationAdjustmentRow({
  adjustment, index, readOnly, errors = {}, onChange, onRemove,
  amount = null, description = '', estimated = true, currency = 'THB',
}) {
  const flat = adjustment.adjustmentKind === 'AMOUNT';
  return (
    <li className="grid gap-3 rounded-md border border-dashed border-border p-3.5" data-line-type="ADJUSTMENT">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <span className="inline-flex min-w-0 items-center gap-2">
          <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-surface-subtle text-2xs font-extrabold text-text-muted">
            {index + 1}
          </span>
          <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">ส่วนลดพิเศษ</span>
        </span>
        {!readOnly ? (
          <Button variant="icon" size="sm" className="mobile:min-h-[44px] mobile:w-11" onClick={onRemove} title="ลบส่วนลดพิเศษ" aria-label="ลบส่วนลดพิเศษ">
            <Icon name="close" size={16} />
          </Button>
        ) : null}
      </div>

      <div className="grid grid-cols-3 gap-3 mobile:grid-cols-1">
        <FormField label="คิดส่วนลด">
          <div className="flex flex-wrap gap-2" role="group" aria-label="คิดส่วนลด">
            {KIND_OPTIONS.map((opt) => (
              <button
                key={opt.code}
                type="button"
                disabled={readOnly}
                aria-pressed={adjustment.adjustmentKind === opt.code}
                className={`min-h-[38px] mobile:min-h-[44px] rounded-md border px-3 text-xs font-bold ${
                  adjustment.adjustmentKind === opt.code ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'
                }`}
                onClick={() => onChange({ adjustmentKind: opt.code })}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </FormField>
        {flat ? (
          <FormField label={`จำนวนเงินส่วนลด (${currency === 'USD' ? 'USD' : 'บาท'})`} htmlFor={`adj-amount-${index}`} required error={errors.adjustmentAmount}>
            <input
              id={`adj-amount-${index}`} type="number" step="0.01" min="0" disabled={readOnly}
              value={adjustment.adjustmentAmount ?? ''}
              onChange={(e) => onChange({ adjustmentAmount: e.target.value === '' ? '' : Number(e.target.value) })}
            />
          </FormField>
        ) : (
          <FormField label="ส่วนลด %" htmlFor={`adj-pct-${index}`} hint="ของยอดรวมรายการข้างบนทั้งหมด" required error={errors.adjustmentPct}>
            <input
              id={`adj-pct-${index}`} type="number" step="0.001" min="0" max="100" disabled={readOnly}
              value={adjustment.adjustmentPct ?? ''}
              onChange={(e) => onChange({ adjustmentPct: e.target.value === '' ? '' : Number(e.target.value) })}
            />
          </FormField>
        )}
        <FormField label="สั่งซื้อภายในวันที่" htmlFor={`adj-deadline-${index}`} hint="ไม่บังคับ · ตั้งต้นเป็นวันสิ้นสุดยืนราคา">
          <input
            id={`adj-deadline-${index}`} type="date" disabled={readOnly}
            value={adjustment.adjustmentDeadline ?? ''}
            onChange={(e) => onChange({ adjustmentDeadline: e.target.value })}
          />
        </FormField>
      </div>

      {flat ? (
        <FormField label="ข้อความ (ไม่บังคับ)" htmlFor={`adj-desc-${index}`} hint="เว้นว่าง = ใช้ข้อความมาตรฐาน">
          <input
            id={`adj-desc-${index}`} disabled={readOnly} maxLength={2000}
            value={adjustment.description ?? ''}
            onChange={(e) => onChange({ description: e.target.value })}
          />
        </FormField>
      ) : null}

      {/* What the document will print for this row — derived, never typed. */}
      <div className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-surface-subtle px-3 py-2.5">
        <p className="m-0 min-w-0 flex-1 text-xs text-text" data-testid={`adjustment-description-${index}`}>
          {description}
        </p>
        <span className="inline-flex items-baseline gap-1.5">
          {estimated && amount != null ? <span className="text-2xs font-bold text-warning">โดยประมาณ</span> : null}
          <span className="tabular-nums text-md font-extrabold text-danger" data-testid={`adjustment-amount-${index}`}>
            {amount != null ? formatQuotationMoney(-amount, currency) : '-'}
          </span>
        </span>
      </div>
    </li>
  );
}
