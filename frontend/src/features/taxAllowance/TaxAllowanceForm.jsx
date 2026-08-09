import { useEffect, useMemo } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { FormGrid } from '../../components/common/Layout.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { formatMoney } from '../../utils/format.js';
import {
  AUTO_GRANTED_ROWS,
  LAW_SOURCES,
  LOR_YOR_01_ADDRESS_KEYS,
  LOR_YOR_01_SECTIONS,
  MARITAL_STATE_OPTIONS,
  SPOUSAL_STATUS_OPTIONS,
  declaredAllowanceTotalFromValues,
  sectionDeclaredTotal,
  sectionHasValue,
} from './taxAllowanceSchema.js';
import { capMapFrom, fieldCapCaption } from './taxAllowanceCaps.js';
import { TaxAllowanceEvidencePanel } from './TaxAllowanceEvidencePanel.jsx';

const MONTH_OPTIONS = Array.from({ length: 12 }, (_, index) => index + 1);

/** The form's own labels for the 13 address slots, in the order it prints them. */
const ADDRESS_LABELS = {
  building: 'อาคาร',
  roomNo: 'ห้องเลขที่',
  floor: 'ชั้นที่',
  village: 'หมู่บ้าน',
  houseNo: 'เลขที่',
  moo: 'หมู่ที่',
  soi: 'ตรอก/ซอย',
  junction: 'แยก',
  road: 'ถนน',
  subDistrict: 'ตำบล/แขวง',
  district: 'อำเภอ/เขต',
  province: 'จังหวัด',
  postalCode: 'รหัสไปรษณีย์',
};

const money = () => z.coerce.number({ invalid_type_error: 'กรุณาระบุตัวเลข' }).min(0, 'ต้องไม่ติดลบ');
const count = (max) => money().int('ต้องเป็นจำนวนเต็ม').max(max, `กรอกได้ไม่เกิน ${max}`);

/**
 * Validation mirrors the CONSTRAINTS THE PRINTED FORM IMPOSES, not looser ones of our own.
 *
 * The count boxes accept a single character in the template's own AcroForm, so 0–9 is the document's
 * limit and it is the limit here — a two-digit count could never be printed in the box it belongs
 * to. Same reasoning for the 13-digit tax ID and the 5-digit postcode: those are comb fields with
 * exactly that many printed cells.
 */
function lorYor01Schema() {
  const shape = {};
  for (const section of LOR_YOR_01_SECTIONS) {
    for (const field of section.fields) {
      if (field.key.startsWith('lorYor01.')) continue;
      shape[field.key] = field.kind === 'count' ? count(field.max ?? 9) : money();
    }
  }
  return z.object({
    ...shape,
    effectiveMonth: z.preprocess(
      (value) => (value === '' || value == null ? null : Number(value)),
      z.number().int().min(1).max(12).nullable().optional(),
    ),
    documentReference: z.string().optional(),
    lorYor01: z.object({
      taxpayerId: z.string().regex(/^$|^\d{13}$/, 'เลขประจำตัวผู้เสียภาษีอากรต้องมี 13 หลัก').optional(),
      firstNameTh: z.string().optional(),
      lastNameTh: z.string().optional(),
      address: z.object({
        ...Object.fromEntries(LOR_YOR_01_ADDRESS_KEYS.map((key) => [key, z.string().optional()])),
        postalCode: z.string().regex(/^$|^\d{5}$/, 'รหัสไปรษณีย์ต้องมี 5 หลัก').optional(),
      }).partial(),
      maritalState: z.string().optional(),
      spousalStatus: z.string().optional(),
      spouseHasIncome: z.union([z.string(), z.boolean()]).optional(),
      childrenTotal: count(9).optional(),
      childExtraAllowance: money(),
      spouseParentCareAllowance: money(),
      providentFundAllowance: money(),
      rmfSellerName: z.string().optional(),
      otherDonationNote: z.string().optional(),
      ownFatherSupported: z.boolean().optional(),
      ownMotherSupported: z.boolean().optional(),
      spouseFatherSupported: z.boolean().optional(),
      spouseMotherSupported: z.boolean().optional(),
      ownFatherHealthInsured: z.boolean().optional(),
      ownMotherHealthInsured: z.boolean().optional(),
      spouseFatherHealthInsured: z.boolean().optional(),
      spouseMotherHealthInsured: z.boolean().optional(),
    }),
  });
}

function MoneyInput({ id, disabled, register, name }) {
  return (
    <div className="relative">
      <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-text-muted">฿</span>
      <input id={id} type="number" inputMode="decimal" min="0" step="0.01" disabled={disabled}
        className="pl-7" {...register(name)} />
    </div>
  );
}

/**
 * The employee-facing แบบ ล.ย.01, structured as the government prints it: one collapsible per ข้อ,
 * in the form's order, quoting the form's wording.
 *
 * <p>This replaced a hub-and-spoke of `?view=` URLs across five invented categories. The categories
 * were grouped around modern Thai deductions rather than around the document being filed, so an
 * employee holding the PDF could not follow along. Owner ruling 2026-08-08: the page follows the
 * paper, and disclosure happens in place rather than by navigating away.
 *
 * <p><b>Why collapsing is safe.</b> `CollapsibleSection` unmounts its children, and these are
 * `register`ed inputs — which works only because react-hook-form defaults `shouldUnregister: false`,
 * so a collapsed ข้อ keeps its value. That is a library default this form depends on, so
 * `TaxAllowanceForm.test.jsx` pins it rather than trusting it.
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
  onDirtyChange,
  /** Renders the filled PDF from the current (unsaved) values so the employee can print and sign. */
  onGeneratePdf,
  generatingPdf = false,
  /**
   * Owner decision #3: the signed scan is required before submit. The PAGE owns that state because
   * it owns the staged-evidence buckets; this component only gates on it.
   */
  signedFormAttached = false,
  evidenceMode,
  evidenceDeclarationId = null,
  stagedEvidenceBySection = {},
  onStageEvidence,
  onUnstageEvidence,
  showToast,
}) {
  const schema = useMemo(() => lorYor01Schema(), []);
  const { register, handleSubmit, reset, control, formState: { errors, isDirty } } = useForm({
    resolver: zodResolver(schema),
    defaultValues,
  });

  useEffect(() => { reset(defaultValues); }, [defaultValues, reset]);
  useEffect(() => { onDirtyChange?.(isDirty); }, [isDirty, onDirtyChange]);

  const watchedValues = useWatch({ control }) ?? {};
  const capMap = useMemo(() => capMapFrom(caps), [caps]);

  function submit(values) {
    onSubmit?.(values);
  }

  const renderField = (field) => {
    const id = `ta-${field.key.replace(/\./g, '-')}`;
    const cap = field.capCategory ? capMap.get(field.capCategory) : null;
    const caption = cap ? fieldCapCaption(cap) : null;
    const error = field.key.includes('.')
      ? errors.lorYor01?.[field.key.split('.')[1]]?.message
      : errors[field.key]?.message;

    if (field.kind === 'checkbox') {
      return (
        <label key={field.key} className="flex items-center gap-2 text-sm text-text">
          <input id={id} type="checkbox" disabled={readOnly} {...register(field.key)} />
          {field.label}
        </label>
      );
    }
    return (
      <FormField key={field.key} label={field.label} htmlFor={id}
        hint={field.hint || caption || undefined} error={error}>
        {field.kind === 'money' ? (
          <MoneyInput id={id} disabled={readOnly} register={register} name={field.key} />
        ) : field.kind === 'count' ? (
          <input id={id} type="number" inputMode="numeric" min="0" max={field.max ?? 9} step="1"
            disabled={readOnly} {...register(field.key)} />
        ) : (
          <input id={id} type="text" disabled={readOnly} {...register(field.key)} />
        )}
      </FormField>
    );
  };

  const identityFields = (
    <div className="grid gap-4">
      <FormGrid>
        <FormField label="เลขประจำตัวผู้เสียภาษีอากร" htmlFor="ta-taxpayer-id"
          hint="13 หลัก" error={errors.lorYor01?.taxpayerId?.message}>
          <input id="ta-taxpayer-id" type="text" inputMode="numeric" maxLength={13}
            disabled={readOnly} {...register('lorYor01.taxpayerId')} />
        </FormField>
        <FormField label="ผู้มีเงินได้ชื่อ" htmlFor="ta-first-name">
          <input id="ta-first-name" type="text" disabled={readOnly} {...register('lorYor01.firstNameTh')} />
        </FormField>
        <FormField label="ชื่อสกุล" htmlFor="ta-last-name">
          <input id="ta-last-name" type="text" disabled={readOnly} {...register('lorYor01.lastNameTh')} />
        </FormField>
      </FormGrid>
      <p className="m-0 text-2xs font-extrabold uppercase tracking-wide text-text-muted">ที่อยู่</p>
      <FormGrid>
        {LOR_YOR_01_ADDRESS_KEYS.map((key) => (
          <FormField key={key} label={ADDRESS_LABELS[key]} htmlFor={`ta-addr-${key}`}
            error={key === 'postalCode' ? errors.lorYor01?.address?.postalCode?.message : undefined}>
            <input id={`ta-addr-${key}`} type="text" disabled={readOnly}
              maxLength={key === 'postalCode' ? 5 : undefined}
              {...register(`lorYor01.address.${key}`)} />
          </FormField>
        ))}
      </FormGrid>
      <p className="m-0 text-xs text-text-muted">
        ข้อมูลนี้กรอกให้อัตโนมัติจากทะเบียนพนักงาน แก้ไขได้ และจะบันทึกกลับเข้าทะเบียนก็ต่อเมื่อฝ่ายบุคคลอนุมัติแล้วเท่านั้น
      </p>
    </div>
  );

  const radioGroup = (name, options, legend) => (
    <fieldset className="grid gap-2 border-0 p-0">
      <legend className="mb-1 text-xs font-extrabold uppercase tracking-wide text-text-muted">{legend}</legend>
      <div className="grid gap-2 sm:grid-cols-2">
        {options.map((option) => (
          <label key={option.value} className="flex items-center gap-2 text-sm text-text">
            <input type="radio" value={option.value} disabled={readOnly} {...register(name)} />
            {option.label}
          </label>
        ))}
      </div>
    </fieldset>
  );

  const statusFields = (
    <div className="grid gap-4">
      {radioGroup('lorYor01.maritalState', MARITAL_STATE_OPTIONS, 'ข้อ 1 · สถานภาพ')}
      {radioGroup('lorYor01.spousalStatus', SPOUSAL_STATUS_OPTIONS, 'ข้อ 1 · สถานภาพการสมรส')}
      {radioGroup('lorYor01.spouseHasIncome', [
        { value: 'true', label: 'มีเงินได้' },
        { value: 'false', label: 'ไม่มีเงินได้' },
      ], 'ข้อ 2 · สถานะการมีเงินได้ของคู่สมรส')}
      <p className="m-0 text-xs text-text-muted">
        แบบ ล.ย.01 ถามเฉพาะสถานภาพ ไม่มีช่องจำนวนเงินค่าลดหย่อนคู่สมรส — ฝ่ายบุคคลจะเป็นผู้กำหนดจำนวนเงินตอนตรวจสอบ
      </p>
    </div>
  );

  const sectionBody = (section) => {
    if (section.kind === 'identity') return identityFields;
    if (section.kind === 'status') return statusFields;
    if (section.fields.length === 0) {
      return <p className="m-0 text-sm text-text-muted">{section.note}</p>;
    }
    return (
      <div className="grid gap-4">
        <FormGrid>{section.fields.filter((f) => f.kind !== 'checkbox').map(renderField)}</FormGrid>
        {section.fields.some((f) => f.kind === 'checkbox') ? (
          <div className="grid gap-2 sm:grid-cols-2">
            {section.fields.filter((f) => f.kind === 'checkbox').map(renderField)}
          </div>
        ) : null}
      </div>
    );
  };

  /** The right-hand state on a collapsed row: subtotal, file count, or "ไม่ได้ประกาศ". */
  const sectionState = (section) => {
    if (section.kind === 'identity' || section.kind === 'status') return null;
    if (section.fields.length === 0) return <span className="text-xs text-text-muted">ไม่ต้องกรอก</span>;
    const total = sectionDeclaredTotal(section, watchedValues);
    if (total > 0) return <strong className="font-mono text-sm text-text">{formatMoney(total)}</strong>;
    if (sectionHasValue(section, watchedValues)) return <span className="text-xs text-text-muted">กรอกแล้ว</span>;
    return <span className="text-xs text-text-muted">ไม่ได้ประกาศ</span>;
  };

  const declarationFields = (
    <FormGrid>
      <FormField label="มีผลตั้งแต่งวดเดือน" htmlFor="ta-effective-month"
        hint="เว้นว่างไว้ = มีผลตั้งแต่เดือนมกราคม" error={errors.effectiveMonth?.message}>
        <select id="ta-effective-month" disabled={readOnly} {...register('effectiveMonth')}>
          <option value="">มกราคม (ค่าเริ่มต้น)</option>
          {MONTH_OPTIONS.map((month) => <option key={month} value={month}>เดือน {month}</option>)}
        </select>
      </FormField>
      <FormField label="เลขที่เอกสารอ้างอิง (ถ้ามี)" htmlFor="ta-document-reference">
        <input id="ta-document-reference" type="text" disabled={readOnly}
          placeholder="เช่น เลขที่ ล.ย.01 กระดาษ" {...register('documentReference')} />
      </FormField>
    </FormGrid>
  );

  const sections = LOR_YOR_01_SECTIONS.map((section) => (
    <CollapsibleSection
      key={section.key}
      id={`ta-section-${section.key}`}
      title={section.no ? `ข้อ ${section.no} · ${section.title}` : section.title}
      subtitle={section.subtitle}
      defaultOpen={!sectioned || section.kind === 'identity'}
      headerRight={sectionState(section)}
    >
      {/* No per-ข้อ หลักฐานแสดงสิทธิ panel. Owner ruling 2026-08-09: the ERP is not the system of
          record for the supporting documents (receipts, certificates, บัตรคนพิการ) — HR handles
          those on paper, as the form's own หมายเหตุ (2) contemplates. The ONE attachment this page
          still takes is the signed ล.ย.01 itself, in the sign-off panel below; that is not
          evidence, and the server-side approve guard depends on it. */}
      <div className="grid gap-4">{sectionBody(section)}</div>
    </CollapsibleSection>
  ));

  const autoGranted = (
    <div className="grid gap-1 rounded-md border border-border-subtle bg-surface-subtle p-3">
      <p className="m-0 text-2xs font-extrabold uppercase tracking-wide text-text-muted">หักให้อัตโนมัติ</p>
      {AUTO_GRANTED_ROWS.map((row) => (
        <p key={row.key} className="m-0 text-xs text-text-muted">
          {row.label}{row.note ? ` — ${row.note}` : ''}
        </p>
      ))}
    </div>
  );

  const lawReferences = (
    <div className="grid gap-3 rounded-md border border-border-subtle p-3">
      <p className="m-0 text-2xs font-extrabold uppercase tracking-wide text-text-muted">แหล่งอ้างอิงทางกฎหมาย</p>
      {Object.values(LAW_SOURCES).map((source) => (
        <div key={source.url} className="grid gap-0.5">
          <a href={source.url} target="_blank" rel="noopener noreferrer"
            className="inline-flex w-fit items-center gap-1 text-xs font-bold text-primary hover:underline">
            {source.label} — กรมสรรพากร
            <Icon name="externalLink" size={11} />
            <span className="sr-only"> (เปิดในแท็บใหม่)</span>
          </a>
          {source.vintage ? <p className="m-0 text-2xs font-bold text-text-muted">{source.vintage}</p> : null}
          <p className="m-0 text-2xs text-text-muted">
            {source.what}{source.caveat ? ` — ${source.caveat}` : ''}
          </p>
        </div>
      ))}
    </div>
  );

  const declaredTotal = formatMoney(declaredAllowanceTotalFromValues(watchedValues));

  /**
   * CRITICAL: every control now lives in ONE form with a visible submit button, so HTML's implicit
   * submission fires a real submit on Enter in any text field — and there are many. The previous
   * version of this file carried a long comment about the same hazard; the hazard did not go away
   * with the wizard, it got broader. `canSubmit` narrows submission to the state where submitting is
   * actually meaningful: the signed form is attached (owner decision #3). SafeForm's own submitter
   * guard then applies ON TOP, because `canSubmit` is a RESTRICTION and never a permission.
   *
   * jsdom does not implement implicit submission, so a green unit suite says nothing here — the
   * real-browser check is what covers it.
   */
  const canSubmit = !readOnly && signedFormAttached;

  const signOffPanel = readOnly ? null : (
    <CollapsibleSection id="ta-section-sign" title="ตรวจทาน ลงนาม และยื่น" defaultOpen={false}
      headerRight={signedFormAttached
        ? <span className="text-xs font-bold text-success">แนบแบบที่ลงนามแล้ว</span>
        : <span className="text-xs text-text-muted">ยังไม่ได้แนบ</span>}>
      <div className="grid gap-4">
        <ol className="m-0 grid list-decimal gap-1 pl-5 text-sm text-text-muted">
          <li>สร้างไฟล์ PDF แบบ ล.ย.01 จากข้อมูลที่กรอกไว้</li>
          <li>พิมพ์ออกมาและลงลายมือชื่อในช่อง “ลงชื่อ...ผู้มีเงินได้”</li>
          <li>สแกนหรือถ่ายรูปแบบที่ลงนามแล้ว และแนบกลับที่ด้านล่าง</li>
          <li>กด “{submitLabel}” เพื่อส่งให้ฝ่ายบุคคลตรวจสอบ</li>
        </ol>
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-border-subtle bg-surface-subtle p-3">
          <span className="text-sm font-bold text-text">รวมค่าลดหย่อนที่ประกาศ</span>
          <strong className="font-mono text-base text-text">{declaredTotal}</strong>
        </div>
        <div>
          <Button type="button" variant="secondary" loading={generatingPdf} disabled={generatingPdf}
            onClick={() => onGeneratePdf?.(watchedValues)}>
            สร้างไฟล์ PDF แบบ ล.ย.01
          </Button>
        </div>
        {evidenceMode ? (
          <TaxAllowanceEvidencePanel
            mode={evidenceMode}
            declarationId={evidenceDeclarationId}
            sectionKey="signed_form"
            showUncategorized={false}
            staged={stagedEvidenceBySection?.signed_form ?? []}
            onStageFile={onStageEvidence ? (file) => onStageEvidence('signed_form', file) : undefined}
            onUnstageFile={onUnstageEvidence ? (tempId) => onUnstageEvidence('signed_form', tempId) : undefined}
            showToast={showToast}
            title="แบบ ล.ย.01 ที่ลงนามแล้ว (บังคับ)"
            emptyLabel="ต้องแนบแบบที่ลงนามแล้วก่อนจึงจะยื่นได้"
          />
        ) : null}
        {!signedFormAttached ? (
          <p className="m-0 text-xs text-text-muted">ยังยื่นไม่ได้ — ต้องแนบแบบ ล.ย.01 ที่ลงนามแล้วก่อน</p>
        ) : null}
      </div>
    </CollapsibleSection>
  );

  const submitFooter = footer ?? (readOnly ? null : (
    <div className="flex justify-end">
      <Button type="submit" variant="primary" loading={submitting} disabled={submitting || !canSubmit}>
        {submitLabel}
      </Button>
    </div>
  ));

  return (
    <SafeForm id={formId} noValidate onSubmit={handleSubmit(submit)} canSubmit={canSubmit}
      className="grid gap-4">
      <div className="grid gap-2">
        <p className="m-0 text-xs font-extrabold uppercase tracking-wide text-text-muted">ข้อมูลแบบแจ้ง</p>
        {declarationFields}
      </div>
      {autoGranted}
      <div className="grid gap-2">{sections}</div>
      {signOffPanel}
      {submitFooter}
      {lawReferences}
    </SafeForm>
  );
}
