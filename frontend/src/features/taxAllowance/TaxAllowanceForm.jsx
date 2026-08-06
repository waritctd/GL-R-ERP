import { useEffect, useMemo, useRef, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { InfoTip } from '../../components/common/InfoTip.jsx';
import { FormGrid, formGridSpan2 } from '../../components/common/Layout.jsx';
import { QuotaBar } from '../../components/common/QuotaBar.jsx';
import { formatMoney } from '../../utils/format.js';
import {
  ALLOWANCE_COUNT_KEYS,
  ALLOWANCE_MONEY_KEYS,
  AUTO_GRANTED_ROWS,
  TAX_ALLOWANCE_GROUPS,
  groupHasValue,
  UNCATEGORIZED_EVIDENCE_KEY,
} from './taxAllowanceSchema.js';
import { capMapFrom, computeGroupUsage, fieldCapCaption, isCapUnavailableThisYear } from './taxAllowanceCaps.js';
import { TaxAllowanceEvidencePanel } from './TaxAllowanceEvidencePanel.jsx';

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

function StepHeading({ innerRef, step, total, title }) {
  return (
    <h3 ref={innerRef} tabIndex={-1} className="m-0 text-base font-bold text-text focus-visible:outline-none">
      <span className="mr-2 text-text-muted">ขั้นตอนที่ {step}/{total}</span>
      {title}
    </h3>
  );
}

// Step 1's section-choice button. Shows the real "has anything been typed in yet" verdict
// (`groupHasValue`) rather than a guess, so a returning user can see which sections they already
// started without opening each one.
function GroupChoice({ group, filled, selected, onSelect }) {
  return (
    <button
      type="button"
      onClick={() => onSelect(group.key)}
      aria-current={selected ? 'true' : undefined}
      className={`flex w-full items-center justify-between gap-3 rounded-md border-[1.5px] px-4 py-3 text-left transition-colors ${
        selected ? 'border-primary bg-info-bg-alt' : 'border-border-input bg-surface hover:border-primary-hover'
      }`}
    >
      <span className="grid gap-0.5">
        <span className="text-sm font-bold text-text">{group.title}</span>
        <span className="text-xs text-text-muted">{filled ? 'กรอกแล้ว' : 'ยังไม่ได้กรอก'}</span>
      </span>
      {filled ? (
        <Icon name="check" size={16} className="shrink-0 text-primary" />
      ) : (
        <Icon name="chevronRight" size={16} className="shrink-0 text-text-muted" />
      )}
    </button>
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
 *
 * Progressive disclosure (#tax-allowance-sections): `sectioned` (default true) renders a 2-step
 * "choose a section, then fill only that section" flow modelled on LeaveRequestPage.jsx's step
 * wizard — step 1 lists the five TAX_ALLOWANCE_GROUPS as choices, step 2 shows only the chosen
 * group's fields plus its evidence panel. The single `react-hook-form` instance below is never
 * remounted or reset between steps, so values already typed into another section survive switching
 * away and back — see `TaxAllowanceForm.test.jsx`'s "values survive step navigation" case for the
 * explicit A→B→back-to-A proof.
 *
 * `sectioned={false}` keeps the ORIGINAL "every group expanded via CollapsibleSection" layout,
 * unchanged — TaxAllowanceReviewPage's `OnBehalfModal` opts into this: it is a small modal for a
 * one-off HR-on-behalf entry (no evidence, no returning-user "which section did I start" need), and
 * turning it into a step wizard too would be an unrequested UX change to a flow this task was never
 * asked to touch.
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
  sectioned = true,
  // Evidence panel (only rendered when sectioned && evidenceMode is provided — TaxAllowanceReviewPage's
  // OnBehalfModal passes none of these, so it never renders one; see the component javadoc above).
  evidenceMode, // 'direct' | 'staging' | 'readonly' | undefined
  evidenceDeclarationId = null,
  stagedEvidenceBySection = {},
  onStageEvidence,
  onUnstageEvidence,
  showToast,
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

  const [step, setStep] = useState(1);
  const [activeGroupKey, setActiveGroupKey] = useState(null);
  const step1HeadingRef = useRef(null);
  const step2HeadingRef = useRef(null);

  useEffect(() => {
    reset(defaultValues);
    // A new declaration/year loaded underneath this form -- return to the section picker rather
    // than silently staying on whatever group happened to be open for the PREVIOUS declaration.
    setStep(1);
    setActiveGroupKey(null);
  }, [defaultValues, reset]);

  const watchedValues = useWatch({ control });

  const capByCategory = useMemo(() => capMapFrom(caps), [caps]);
  const groupUsage = useMemo(() => computeGroupUsage(caps, watchedValues || {}), [caps, watchedValues]);
  const groupUsageById = useMemo(() => new Map(groupUsage.map((group) => [group.groupId, group])), [groupUsage]);

  useEffect(() => {
    const ref = step === 1 ? step1HeadingRef : step2HeadingRef;
    ref.current?.focus();
  }, [step]);

  const activeGroup = useMemo(
    () => TAX_ALLOWANCE_GROUPS.find((group) => group.key === activeGroupKey) ?? null,
    [activeGroupKey],
  );

  function openGroup(key) {
    setActiveGroupKey(key);
    setStep(2);
  }

  function backToSections() {
    setStep(1);
  }

  function submit(values) {
    onSubmit?.(values);
  }

  function renderGroupFields(group) {
    const barGroup = group.groupCapId ? groupUsageById.get(group.groupCapId) : null;
    return (
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
    );
  }

  const autoGrantedInfo = (
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
  );

  const declarationFields = (
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
  );

  const submitFooter = footer ?? (readOnly ? null : (
    <div className="flex justify-end">
      <Button type="submit" variant="primary" loading={submitting} disabled={submitting}>
        {submitLabel}
      </Button>
    </div>
  ));

  if (!sectioned) {
    return (
      <form id={formId} noValidate onSubmit={handleSubmit(submit)} className="grid gap-4">
        {autoGrantedInfo}
        {declarationFields}
        {TAX_ALLOWANCE_GROUPS.map((group) => (
          <CollapsibleSection key={group.key} title={group.title} defaultOpen>
            {renderGroupFields(group)}
          </CollapsibleSection>
        ))}
        {submitFooter}
      </form>
    );
  }

  return (
    <form id={formId} noValidate onSubmit={handleSubmit(submit)} className="grid gap-4">
      {step === 1 ? (
        <div className="grid gap-4">
          <StepHeading innerRef={step1HeadingRef} step={1} total={2} title="เลือกหมวดที่ต้องการกรอก" />
          {autoGrantedInfo}
          {declarationFields}
          <div className="grid gap-2">
            {TAX_ALLOWANCE_GROUPS.map((group) => (
              <GroupChoice
                key={group.key}
                group={group}
                filled={groupHasValue(group, watchedValues)}
                selected={group.key === activeGroupKey}
                onSelect={openGroup}
              />
            ))}
          </div>
          {evidenceMode ? (
            <TaxAllowanceEvidencePanel
              mode={evidenceMode}
              declarationId={evidenceDeclarationId}
              sectionKey={null}
              showUncategorized
              staged={stagedEvidenceBySection?.[UNCATEGORIZED_EVIDENCE_KEY] ?? []}
              onStageFile={onStageEvidence ? (file) => onStageEvidence(null, file) : undefined}
              onUnstageFile={onUnstageEvidence ? (tempId) => onUnstageEvidence(null, tempId) : undefined}
              showToast={showToast}
              title="หลักฐานทั่วไป (ไม่ได้ระบุหมวด)"
              emptyLabel="ยังไม่มีไฟล์แนบทั่วไป — แนบไฟล์เฉพาะหมวดได้หลังเลือกหมวดด้านบน"
            />
          ) : null}
          {submitFooter}
        </div>
      ) : null}

      {step === 2 && activeGroup ? (
        <div className="grid gap-4">
          <div className="flex items-center justify-between gap-3">
            <StepHeading innerRef={step2HeadingRef} step={2} total={2} title={activeGroup.title} />
            <Button type="button" variant="secondary" size="sm" onClick={backToSections}>
              <Icon name="chevronLeft" size={14} />
              กลับไปเลือกหมวด
            </Button>
          </div>
          {renderGroupFields(activeGroup)}
          {evidenceMode ? (
            <TaxAllowanceEvidencePanel
              mode={evidenceMode}
              declarationId={evidenceDeclarationId}
              sectionKey={activeGroup.key}
              showUncategorized={false}
              staged={stagedEvidenceBySection?.[activeGroup.key] ?? []}
              onStageFile={onStageEvidence ? (file) => onStageEvidence(activeGroup.key, file) : undefined}
              onUnstageFile={onUnstageEvidence ? (tempId) => onUnstageEvidence(activeGroup.key, tempId) : undefined}
              showToast={showToast}
              title={`หลักฐานแสดงสิทธิ — ${activeGroup.title}`}
            />
          ) : null}
          <div className="flex justify-between gap-[10px]">
            <Button type="button" variant="secondary" onClick={backToSections}>ย้อนกลับ</Button>
            {submitFooter}
          </div>
        </div>
      ) : null}
    </form>
  );
}
