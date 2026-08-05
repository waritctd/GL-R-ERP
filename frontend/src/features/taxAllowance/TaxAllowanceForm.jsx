import { useEffect, useMemo } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { InfoTip } from '../../components/common/InfoTip.jsx';
import { FormGrid, formGridSpan2 } from '../../components/common/Layout.jsx';
import { QuotaBar } from '../../components/common/QuotaBar.jsx';
import { formatMoney } from '../../utils/format.js';
import {
  ALLOWANCE_COUNT_KEYS,
  ALLOWANCE_MONEY_KEYS,
  AUTO_GRANTED_ROWS,
  TAX_ALLOWANCE_GROUPS,
} from './taxAllowanceSchema.js';
import { capMapFrom, computeGroupUsage, fieldCapCaption, isCapUnavailableThisYear } from './taxAllowanceCaps.js';

const MONTH_OPTIONS = Array.from({ length: 12 }, (_, index) => index + 1);

function allowanceZodSchema() {
  const shape = {};
  for (const key of ALLOWANCE_MONEY_KEYS) {
    shape[key] = z.coerce.number({ invalid_type_error: 'กรุณาระบุตัวเลข' }).min(0, 'ต้องไม่ติดลบ');
  }
  for (const key of ALLOWANCE_COUNT_KEYS) {
    shape[key] = z.coerce.number({ invalid_type_error: 'กรุณาระบุตัวเลข' }).min(0, 'ต้องไม่ติดลบ').int('ต้องเป็นจำนวนเต็ม');
  }
  shape.disabilityCardHolder = z.boolean().optional().default(false);
  // The blank "มกราคม (ค่าเริ่มต้น)" <option> submits value="" (ล.ย.01 ข้อ 2.2: no override means
  // January). z.coerce.number() on "" coerces to 0, which then failed .min(1) on every submit with
  // the blank option selected — react-hook-form silently focused this field with no rendered error
  // (FormField never receives one here), so the whole form looked broken with no feedback at all.
  // Preprocess "" (and null/undefined) to null before the number check runs instead.
  shape.effectiveMonth = z.preprocess(
    (value) => (value === '' || value === null || value === undefined ? null : value),
    z.coerce.number().min(1).max(12).nullable().optional(),
  );
  shape.documentReference = z.string().optional();
  return z.object(shape);
}

const SCHEMA = allowanceZodSchema();

function MoneyField({ id, label, hint, error, disabled, register, required }) {
  return (
    <FormField label={label} htmlFor={id} hint={hint} error={error} required={required}>
      <span className="currency-input">
        <span className="currency-input-symbol" aria-hidden="true">฿</span>
        <input
          id={id}
          type="number"
          inputMode="decimal"
          min="0"
          step="0.01"
          placeholder="0.00"
          disabled={disabled}
          {...register}
        />
      </span>
    </FormField>
  );
}

function CountField({ id, label, unit, hint, error, disabled, register }) {
  return (
    <FormField label={unit ? `${label} (${unit})` : label} htmlFor={id} hint={hint} error={error}>
      <input id={id} type="number" inputMode="numeric" min="0" step="1" placeholder="0" disabled={disabled} {...register} />
    </FormField>
  );
}

// The bar itself now lives in components/common/QuotaBar.jsx -- welfare needs the same readout and
// this was the only accessible implementation in the app. Only the ล.ย.01-specific copy and this
// form's grid placement stay here; everything else is the shared component's job.
function GroupUsageBar({ group }) {
  const { label, total, cap } = group;
  return (
    <QuotaBar
      className={`${formGridSpan2}`}
      label={label}
      caption={`${label} ใช้วงเงินรวมกัน ${formatMoney(cap)}`}
      used={total}
      cap={cap}
      overMessage="ยอดที่ยื่นเกินวงเงินรวมของกลุ่มนี้ — ระบบจะยื่นตามยอดที่กรอกจริง แต่ตอนคำนวณภาษี HR จะเห็นยอดที่ตัดตามเพดานจริง"
    />
  );
}

/**
 * The ล.ย.01 declaration form — shared between the employee's own `/tax-allowance` screen and HR's
 * "ยื่นแทนพนักงาน" (on-behalf) mode on `/tax-allowance-review`. `readOnly` disables every control
 * without changing the DOM structure a screen reader would announce column-drift on.
 *
 * Deliberately does no Thai tax arithmetic: the group-consumption bars below only total what the
 * employee typed against the `groupCap` `/caps` returned (see taxAllowanceCaps.js), and the
 * submitted body carries whatever was declared verbatim — decision #1's "do not pre-clamp the
 * submitted value, the backend clamps".
 */
export function TaxAllowanceForm({
  caps = [],
  defaultValues,
  readOnly = false,
  submitting = false,
  submitLabel = 'ยื่นแบบแจ้ง',
  onSubmit,
  formId = 'tax-allowance-form',
  footer,
}) {
  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(SCHEMA),
    defaultValues,
  });

  useEffect(() => {
    reset(defaultValues);
  }, [defaultValues, reset]);

  const watchedValues = useWatch({ control });

  const capByCategory = useMemo(() => capMapFrom(caps), [caps]);
  const groupUsage = useMemo(() => computeGroupUsage(caps, watchedValues || {}), [caps, watchedValues]);
  const groupUsageById = useMemo(() => new Map(groupUsage.map((group) => [group.groupId, group])), [groupUsage]);

  function submit(values) {
    onSubmit?.(values);
  }

  return (
    <form id={formId} noValidate onSubmit={handleSubmit(submit)} className="grid gap-4">
      <div className="rounded-md border border-border-subtle bg-surface-subtle p-3">
        <p className="m-0 mb-2 text-xs font-extrabold uppercase tracking-wide text-text-muted">ได้รับอัตโนมัติ ไม่ต้องยื่น</p>
        <div className="grid gap-1.5">
          {AUTO_GRANTED_ROWS.map((row) => {
            const cap = row.capCategory ? capByCategory.get(row.capCategory) : null;
            return (
              <div key={row.key} className="flex items-center justify-between gap-3 text-sm">
                <span className="text-text-muted">{row.label}</span>
                <strong className="text-text">{cap ? formatMoney(cap.ownCap) : (row.note ?? '-')}</strong>
              </div>
            );
          })}
        </div>
      </div>

      <FormGrid>
        <FormField
          label="มีผลตั้งแต่งวดเดือน"
          htmlFor="ta-effective-month"
          hint="เว้นว่างไว้ = มีผลตั้งแต่เดือนมกราคม"
          error={errors.effectiveMonth?.message}
        >
          <select id="ta-effective-month" disabled={readOnly} {...register('effectiveMonth')}>
            <option value="">มกราคม (ค่าเริ่มต้น)</option>
            {MONTH_OPTIONS.map((month) => (
              <option key={month} value={month}>เดือน {month}</option>
            ))}
          </select>
        </FormField>
        <FormField label="เลขที่เอกสารอ้างอิง (ถ้ามี)" htmlFor="ta-document-reference">
          <input id="ta-document-reference" type="text" disabled={readOnly} placeholder="เช่น เลขที่ ล.ย.01 กระดาษ" {...register('documentReference')} />
        </FormField>
      </FormGrid>

      {TAX_ALLOWANCE_GROUPS.map((group) => {
        const barGroup = group.groupCapId ? groupUsageById.get(group.groupCapId) : null;
        return (
          <CollapsibleSection key={group.key} title={group.title} defaultOpen>
            <FormGrid>
              {group.fields.map((field) => {
                const cap = field.capCategory ? capByCategory.get(field.capCategory) : null;
                const unavailable = isCapUnavailableThisYear(cap);
                const fieldError = errors[field.key]?.message;
                if (field.kind === 'money') {
                  const hint = unavailable
                    ? 'ไม่สามารถหักลดหย่อนได้ตั้งแต่ปีภาษีนี้เป็นต้นไปตามกฎหมาย'
                    : [fieldCapCaption(cap), field.hint].filter(Boolean).join(' · ') || undefined;
                  return (
                    <MoneyField
                      key={field.key}
                      id={`ta-${field.key}`}
                      label={field.label}
                      hint={hint}
                      error={fieldError}
                      disabled={readOnly || unavailable}
                      register={register(field.key)}
                    />
                  );
                }
                if (field.kind === 'count') {
                  return (
                    <CountField
                      key={field.key}
                      id={`ta-${field.key}`}
                      label={field.label}
                      unit={field.unit}
                      hint={field.hint}
                      error={fieldError}
                      disabled={readOnly}
                      register={register(field.key)}
                    />
                  );
                }
                return (
                  <label key={field.key} htmlFor={`ta-${field.key}`} className={`${formGridSpan2} inline-flex items-center gap-2`}>
                    <input id={`ta-${field.key}`} type="checkbox" disabled={readOnly} {...register(field.key)} />
                    <span>{field.label}</span>
                    {field.hint ? <InfoTip label={field.label} text={field.hint} /> : null}
                  </label>
                );
              })}
              {barGroup ? <GroupUsageBar group={barGroup} /> : null}
            </FormGrid>
          </CollapsibleSection>
        );
      })}

      {footer ?? (readOnly ? null : (
        <div className="flex justify-end">
          <Button type="submit" variant="primary" loading={submitting} disabled={submitting}>
            {submitLabel}
          </Button>
        </div>
      ))}
    </form>
  );
}
