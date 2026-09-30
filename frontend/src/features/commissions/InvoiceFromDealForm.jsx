import { Button } from '../../components/common/Button.jsx';
import { FileUploadField } from '../../components/common/FileUploadField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { FormGrid, formGridSpan2 } from '../../components/common/Layout.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';

const DEDUCTIONS = [
  ['bankFees', 'ค่าธรรมเนียมธนาคาร'],
  ['suspenseVat', 'ภาษีพัก (Suspense VAT)'],
  ['transportFee', 'ค่าขนส่ง'],
  ['cutFee', 'ค่าตัด'],
  ['shortfall', 'รับเงินขาด'],
  ['withholdingTax', 'หัก ณ ที่จ่าย'],
  ['overpayment', 'รับเงินเกิน'],
];

/**
 * The tax-invoice fields for POST /api/commissions/from-deal, shared by CommissionPage's account
 * flow and the finance deal page. Controlled: the caller owns `form` (see emptyInvoiceForm) and the
 * submit handler. `fileInputKey` remounts the file field to clear it after a successful save.
 */
export function InvoiceFromDealForm({
  form, onChange, onSubmit, saving = false, disabled = false, fileInputKey = 0,
  fileInputId = 'invoice-file', submitLabel, onCancel,
}) {
  const field = (name) => ({ value: form[name], onChange: (event) => onChange(name, event.target.value) });
  return (
    // allowSubmitterlessSubmit: the file input carries a native `required`, and jsdom does not run
    // real constraint validation on it after a synthetic change, so the tests dispatch a
    // submitter-less submit straight on the form. INERT in a real browser: the submit button below
    // is always rendered (only `disabled` varies), so `submitter` is never null for a genuine
    // keypress. See the original note in SafeForm.jsx's header.
    <SafeForm onSubmit={onSubmit} allowSubmitterlessSubmit>
      <FormGrid>
        <label>
          เลขที่ใบกำกับ *
          <input {...field('invoiceNumber')} required />
        </label>
        <label>
          วันที่ใบกำกับ *
          <input type="date" {...field('invoiceDate')} required />
        </label>
        <div className="grid gap-[7px]">
          <label htmlFor={fileInputId}>ไฟล์ใบกำกับภาษี *</label>
          <FileUploadField
            id={fileInputId}
            key={fileInputKey}
            accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png"
            onChange={(event) => onChange('invoiceAttachment', event.target.files?.[0] || null)}
            required
            helperText="PDF, JPG หรือ PNG"
          />
        </div>
        <label>
          ยอดรวม (ก่อน VAT) * <small className="text-text-muted">(ค่าเริ่มต้นจากยอดก่อน VAT ของดีล แก้ไขได้)</small>
          <input type="number" min="0" step="0.01" {...field('grossAmount')} required />
        </label>
        {DEDUCTIONS.map(([name, label]) => (
          <label key={name}>
            {label}
            <input type="number" min="0" step="0.01" {...field(name)} />
          </label>
        ))}
        <div className={`${formGridSpan2} flex flex-wrap justify-end gap-[10px] mobile:flex-col-reverse`}>
          {onCancel ? (
            <Button type="button" variant="secondary" className="pointer-coarse:min-h-11 mobile:!w-full" onClick={onCancel} disabled={saving}>ยกเลิก</Button>
          ) : null}
          <Button type="submit" variant="primary" className="pointer-coarse:min-h-11 mobile:!min-h-11 mobile:!w-full" disabled={saving || disabled}>
            <Icon name="check" size={14} />
            {submitLabel}
          </Button>
        </div>
      </FormGrid>
    </SafeForm>
  );
}
