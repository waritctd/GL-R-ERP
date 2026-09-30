/* Hallmark · component: invoice form · genre: modern-minimal · theme: project system (Sarabun + indigo, owner-locked) · redesign
 * states: default · hover · focus-visible · active · disabled · loading · error · success
 * Hallmark · pre-emit critique: P4 H4 E4 S4 R4 V3 (component scope: no macrostructure; two labelled groups, document fields then amounts/deductions)
 */
import { useId } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { FileUploadField } from '../../components/common/FileUploadField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { FormGrid, formGridSpan2 } from '../../components/common/Layout.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { cn } from '../../utils/cn.js';

const DEDUCTIONS = [
  ['bankFees', 'ค่าธรรมเนียมธนาคาร'],
  ['suspenseVat', 'ภาษีพัก (Suspense VAT)'],
  ['transportFee', 'ค่าขนส่ง'],
  ['cutFee', 'ค่าตัด'],
  ['shortfall', 'รับเงินขาด'],
  ['withholdingTax', 'หัก ณ ที่จ่าย'],
  ['overpayment', 'รับเงินเกิน'],
];

// Control states the global input reset does not draw: hover, disabled and invalid. Default and
// :focus come from index.css's `@layer base` (border + indigo focus ring), so they are not repeated.
const CONTROL_STATES = [
  'hover:border-border-muted',
  'disabled:cursor-not-allowed disabled:bg-surface-subtle disabled:text-text-muted',
  'aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-bg',
  'pointer-coarse:min-h-11',
].join(' ');

function Group({ legend, hint, divided = false, children }) {
  const titleId = useId();
  return (
    <div role="group" aria-labelledby={titleId} className={cn('grid min-w-0 gap-3', divided && 'border-t border-border pt-6')}>
      <div className="grid gap-0.5">
        <h3 id={titleId} className="m-0 text-md font-extrabold text-text">{legend}</h3>
        {hint ? <p className="m-0 text-sm text-text-muted">{hint}</p> : null}
      </div>
      {children}
    </div>
  );
}

function FieldError({ id, message }) {
  if (!message) return null;
  return (
    <span id={id} role="alert" className="flex items-start gap-1.5 text-sm font-bold text-danger">
      <Icon name="triangleAlert" size={14} className="mt-0.5 shrink-0" />
      <span>{message}</span>
    </span>
  );
}

/**
 * The tax-invoice fields for POST /api/commissions/from-deal, shared by CommissionPage's account
 * flow and the finance deal page. Controlled: the caller owns `form` (see emptyInvoiceForm) and the
 * submit handler. `fileInputKey` remounts the file field to clear it after a successful save.
 *
 * Two groups: the document (เลขที่ / วันที่ / ไฟล์), then the amounts and deductions.
 * Optional presentation props: `error` (form-level message), `success` (form-level message) and
 * `fieldErrors` ({ fieldName: message }) mark controls invalid; callers that pass none see the
 * form exactly as before. `saving` disables every control and shows the button's loading state.
 */
export function InvoiceFromDealForm({
  form, onChange, onSubmit, saving = false, disabled = false, fileInputKey = 0,
  fileInputId = 'invoice-file', submitLabel, onCancel, error, success, fieldErrors = {},
}) {
  const field = (name) => ({
    value: form[name],
    onChange: (event) => onChange(name, event.target.value),
    'aria-invalid': fieldErrors[name] ? 'true' : undefined,
    'aria-describedby': fieldErrors[name] ? `${fileInputId}-${name}-error` : undefined,
    className: CONTROL_STATES,
  });
  const errorFor = (name) => <FieldError id={`${fileInputId}-${name}-error`} message={fieldErrors[name]} />;
  return (
    // allowSubmitterlessSubmit: the file input carries a native `required`, and jsdom does not run
    // real constraint validation on it after a synthetic change, so the tests dispatch a
    // submitter-less submit straight on the form. INERT in a real browser: the submit button below
    // is always rendered (only `disabled` varies), so `submitter` is never null for a genuine
    // keypress. See the original note in SafeForm.jsx's header.
    <SafeForm onSubmit={onSubmit} allowSubmitterlessSubmit>
      <div className="grid gap-6">
        <fieldset disabled={saving} className="m-0 grid min-w-0 gap-6 border-0 p-0">
          <Group legend="เอกสารใบกำกับภาษี" hint="เลขที่ วันที่ และไฟล์ที่แนบ">
            <FormGrid>
              <label>
                เลขที่ใบกำกับ *
                <input {...field('invoiceNumber')} required />
                {errorFor('invoiceNumber')}
              </label>
              <label>
                วันที่ใบกำกับ *
                <input type="date" {...field('invoiceDate')} required />
                {errorFor('invoiceDate')}
              </label>
              <div className={cn('grid gap-[7px]', formGridSpan2)}>
                <label htmlFor={fileInputId}>ไฟล์ใบกำกับภาษี *</label>
                <FileUploadField
                  id={fileInputId}
                  key={fileInputKey}
                  accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png"
                  onChange={(event) => onChange('invoiceAttachment', event.target.files?.[0] || null)}
                  required
                  helperText="PDF, JPG หรือ PNG"
                />
                {errorFor('invoiceAttachment')}
              </div>
            </FormGrid>
          </Group>

          <Group divided legend="ยอดเงินและรายการหัก" hint="ยอดรวมก่อน VAT ตามด้วยรายการหักหรือปรับปรุง เว้นว่างได้ถ้าไม่มี">
            <FormGrid>
              <label className={formGridSpan2}>
                ยอดรวม (ก่อน VAT) * <small className="text-text-muted">(ค่าเริ่มต้นจากยอดก่อน VAT ของดีล แก้ไขได้)</small>
                <input type="number" min="0" step="0.01" inputMode="decimal" {...field('grossAmount')} required />
                {errorFor('grossAmount')}
              </label>
              {DEDUCTIONS.map(([name, label]) => (
                <label key={name}>
                  {label}
                  <input type="number" min="0" step="0.01" inputMode="decimal" {...field(name)} />
                  {errorFor(name)}
                </label>
              ))}
            </FormGrid>
          </Group>
        </fieldset>

        {error ? (
          <p role="alert" className="m-0 flex items-start gap-2 rounded-md border border-danger-border bg-danger-bg px-3 py-2.5 text-sm font-bold text-danger [overflow-wrap:anywhere]">
            <Icon name="triangleAlert" size={16} className="mt-0.5 shrink-0" />
            <span>{error}</span>
          </p>
        ) : null}
        {success ? (
          <p role="status" className="m-0 flex items-start gap-2 rounded-md border border-success-border bg-success-bg px-3 py-2.5 text-sm font-bold text-success-dark [overflow-wrap:anywhere]">
            <Icon name="check" size={16} className="mt-0.5 shrink-0" />
            <span>{success}</span>
          </p>
        ) : null}

        <div className="flex flex-wrap justify-end gap-2.5 border-t border-border pt-4 mobile:flex-col-reverse">
          {onCancel ? (
            <Button
              type="button" variant="secondary"
              className="whitespace-nowrap hover:border-border-muted active:bg-surface-subtle pointer-coarse:min-h-11 mobile:!w-full"
              onClick={onCancel} disabled={saving}
            >
              ยกเลิก
            </Button>
          ) : null}
          <Button
            type="submit" variant="primary"
            className="whitespace-nowrap hover:bg-primary-hover active:brightness-95 pointer-coarse:min-h-11 mobile:!min-h-11 mobile:!w-full"
            disabled={disabled} loading={saving}
          >
            <Icon name="check" size={14} />
            {submitLabel}
          </Button>
        </div>
      </div>
    </SafeForm>
  );
}
