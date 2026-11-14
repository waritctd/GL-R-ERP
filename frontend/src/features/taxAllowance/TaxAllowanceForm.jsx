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

/** Validation mirrors the CONSTRAINTS THE PRINTED FORM IMPOSES, not looser ones of our own. The count boxes
 * accept a single character in the template's own AcroForm, so 0–9 is the document's limit and it is the limit
 * here — a two-digit count could never be printed in the box it belongs to. */
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

/** The employee-facing แบบ ล.ย.01, structured as the government prints it: one collapsible per ข้อ, in the
 * form's order, quoting the form's wording. This replaced a hub-and-spoke of `?view=` URLs across five
 * invented categories. */
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
  /** Fetches the PDF of the declaration ALREADY FILED, from the server. Undefined when this tax year has no saved
   * declaration to fetch. Distinct from `onGeneratePdf`, which renders today's draft. */
  onDownloadFiledForm,
  downloadingFiledForm = false,
  /** Owner decision #3: the signed scan is required before submit. The PAGE owns that state because it owns the
   * staged-evidence buckets; this component only gates on it. */
  signedFormAttached = false,
  evidenceMode,
  evidenceDeclarationId = null,
  stagedEvidenceBySection = {},
  onStageEvidence,
  onUnstageEvidence,
  showToast,
}) {
  const schema = useMemo(() => lorYor01Schema(), []);
  /** `mode: 'onTouched'` — validate a field the first time it is left, then on every change. The per-field error
   * wiring already existed (`FormField error=…`); only the trigger was missing. */
  const { register, handleSubmit, reset, control, trigger, formState: { errors, isDirty } } = useForm({
    resolver: zodResolver(schema),
    mode: 'onTouched',
    defaultValues,
  });

  useEffect(() => { reset(defaultValues); }, [defaultValues, reset]);
  useEffect(() => { onDirtyChange?.(isDirty); }, [isDirty, onDirtyChange]);

  const watchedValues = useWatch({ control }) ?? {};
  const capMap = useMemo(() => capMapFrom(caps), [caps]);

  function submit(values) {
    onSubmit?.(values);
  }

  /** The ล.ย.01 the employee is about to print and SIGN is rendered from whatever is typed right now, so it gets
   * the same validation the submit path gets — otherwise a malformed tax ID or postcode is discovered only after
   * a signature is on the paper. */
  async function generatePdf() {
    if (!(await trigger())) {
      showToast?.('error', 'ยังสร้างไฟล์ไม่ได้ — มีช่องที่กรอกไม่ถูกต้อง กรุณาตรวจสอบและแก้ไขก่อน');
      return;
    }
    onGeneratePdf?.(watchedValues);
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

  // No "มีผลตั้งแต่งวดเดือน" control: a ล.ย.01 is a whole-tax-year declaration (owner ruling 2026-08-31) and
  // HR's approval is what makes it live, so there is no month for the employee to choose.
  const declarationFields = (
    <FormGrid>
      <FormField label="เลขที่เอกสารอ้างอิง (ถ้ามี)" htmlFor="ta-document-reference">
        <input id="ta-document-reference" type="text" disabled={readOnly}
          placeholder="เช่น เลขที่ ล.ย.01 กระดาษ" {...register('documentReference')} />
      </FormField>
    </FormGrid>
  );

  const sections = LOR_YOR_01_SECTIONS.map((section) => (
    <CollapsibleSection
      /* ข้อมูลผู้มีเงินได้ opens only while the form is EDITABLE, where it is genuinely step 1 — sixteen
       * mostly-blank header slots the employee has to check before anything else. */
      key={section.kind === 'identity' ? `${section.key}-${readOnly ? 'ro' : 'rw'}` : section.key}
      id={`ta-section-${section.key}`}
      title={section.no ? `ข้อ ${section.no} · ${section.title}` : section.title}
      subtitle={section.subtitle}
      defaultOpen={!sectioned || (section.kind === 'identity' && !readOnly)}
      headerRight={sectionState(section)}
    >
      <div className="grid gap-4">
        {sectionBody(section)}
        {evidenceMode && section.fields.length > 0 ? (
          <TaxAllowanceEvidencePanel
            mode={evidenceMode}
            declarationId={evidenceDeclarationId}
            sectionKey={section.key}
            showUncategorized={false}
            staged={stagedEvidenceBySection?.[section.key] ?? []}
            onStageFile={onStageEvidence ? (file) => onStageEvidence(section.key, file) : undefined}
            onUnstageFile={onUnstageEvidence ? (tempId) => onUnstageEvidence(section.key, tempId) : undefined}
            showToast={showToast}
            title={`หลักฐานแสดงสิทธิ — ข้อ ${section.no}`}
          />
        ) : null}
      </div>
    </CollapsibleSection>
  ));

  /** One line, not a card. The figure comes from the caps endpoint like every other number on this page; it is
   * never hardcoded here. */
  const autoGranted = (
    <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1 rounded-md border border-border-subtle bg-surface-subtle px-3 py-2">
      <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">หักให้อัตโนมัติ</span>
      {AUTO_GRANTED_ROWS.map((row) => {
        const caption = row.capCategory ? fieldCapCaption(capMap.get(row.capCategory)) : null;
        return (
          <span key={row.key} className="text-xs text-text-muted">
            {row.label}{caption ? ` · ${caption}` : ''}{row.note ? ` — ${row.note}` : ''}
          </span>
        );
      })}
    </div>
  );

  /** Reference material, not part of filling the form — so it is COLLAPSED by default. It is genuinely useful
   * (the caveats say which rd.go.th numbers are stale), so it stays on the page — behind the same disclosure
   * every other section on this screen already uses, rather than as a wall of text. */
  const lawSources = Object.values(LAW_SOURCES);
  const lawReferences = (
    <CollapsibleSection
      id="ta-section-law-sources"
      title="แหล่งอ้างอิงทางกฎหมาย"
      subtitle="ลิงก์ไปหน้าต้นทางของกรมสรรพากร พร้อมข้อควรระวังของแต่ละแหล่ง"
      defaultOpen={false}
      headerRight={<span className="text-xs text-text-muted">{lawSources.length} แหล่ง</span>}
    >
      <div className="grid gap-3">
        {lawSources.map((source) => (
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
    </CollapsibleSection>
  );

  const declaredTotal = formatMoney(declaredAllowanceTotalFromValues(watchedValues));

  /** CRITICAL: every control now lives in ONE form with a visible submit button, so HTML's implicit submission
   * fires a real submit on Enter in any text field — and there are many. SafeForm's own submitter guard then
   * applies ON TOP, because `canSubmit` is a RESTRICTION and never a permission. */
  const canSubmit = !readOnly && signedFormAttached;

  /** Displayed in both the read-only view and the sign-off panel — one markup, one figure. */
  const declaredTotalRow = (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-border-subtle bg-surface-subtle p-3">
      <span className="text-sm font-bold text-text">รวมค่าลดหย่อนที่ประกาศ</span>
      <strong className="font-mono text-base text-text">{declaredTotal}</strong>
    </div>
  );

  /** What a filed declaration offers: the figure that was declared, and a copy of the document. The download is
   * the point. It applies at ANY saved status, not just an approved one: a PENDING filing is equally theirs. */
  const filedFormBlock = (
    <div className="grid gap-3">
      {declaredTotalRow}
      {onDownloadFiledForm ? (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <Button type="button" variant="secondary" loading={downloadingFiledForm}
            disabled={downloadingFiledForm} onClick={onDownloadFiledForm}>
            ดาวน์โหลดแบบ ล.ย.01 ที่ยื่นไว้
          </Button>
          <p className="m-0 text-xs text-text-muted">ไฟล์ PDF ของแบบแจ้งฉบับที่ยื่นไว้ สำหรับเก็บไว้เป็นหลักฐาน</p>
        </div>
      ) : null}
    </div>
  );

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
        {declaredTotalRow}
        <div>
          <Button type="button" variant="secondary" loading={generatingPdf} disabled={generatingPdf}
            onClick={generatePdf}>
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
      </div>
    </CollapsibleSection>
  );

  /** Why the disabled submit button states its own reason. The same sentence used to sit a third time at the
   * bottom of that section, next to the evidence panel's own "ต้องแนบแบบที่ลงนามแล้วก่อนจึงจะยื่นได้" empty
   * state; that copy is gone and this is the one place it lives now. */
  const submitBlockedReason = !readOnly && !signedFormAttached
    ? 'ยังยื่นไม่ได้ — เปิดหัวข้อ “ตรวจทาน ลงนาม และยื่น” เพื่อแนบแบบ ล.ย.01 ที่ลงนามแล้ว'
    : null;

  const submitFooter = footer ?? (readOnly ? null : (
    <div className="flex flex-wrap items-center justify-end gap-x-3 gap-y-2">
      {submitBlockedReason ? (
        <p className="m-0 mr-auto text-xs text-text-muted">{submitBlockedReason}</p>
      ) : null}
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
      {/* Read-only loses the sign-off panel, and with it the only place the grand total and the PDF button appeared —
       * so a filed declaration showed fifteen per-ข้อ subtotals, no total, and no way to get the document back. Both
       * live here instead, in the slot the panel would occupy. */}
      {readOnly ? filedFormBlock : signOffPanel}
      {submitFooter}
      {lawReferences}
    </SafeForm>
  );
}
