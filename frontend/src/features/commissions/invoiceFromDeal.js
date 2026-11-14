import imageCompression from 'browser-image-compression';

// Shared by every caller of api.commissions.createFromDeal (CommissionPage's account flow and the finance deal
// page's RECORD_INVOICE form), so the form state, file handling and payload keys cannot drift between the two.

export function emptyInvoiceForm(grossAmount = '') {
  return {
    invoiceNumber: '',
    invoiceDate: new Date().toISOString().slice(0, 10),
    grossAmount,
    bankFees: '0',
    suspenseVat: '0',
    transportFee: '0',
    cutFee: '0',
    shortfall: '0',
    withholdingTax: '0',
    overpayment: '0',
    invoiceAttachment: null,
  };
}

function numberOrNull(value) {
  if (value === '' || value == null) return null;
  const n = Number(value);
  return Number.isNaN(n) ? null : n;
}

export async function prepareInvoiceAttachment(file) {
  if (!file) throw new Error('กรุณาแนบไฟล์ใบกำกับภาษี');
  if (!['application/pdf', 'image/jpeg', 'image/png'].includes(file.type)) {
    throw new Error('รองรับเฉพาะไฟล์ PDF, JPG หรือ PNG');
  }
  // imageCompression() returns a plain Blob, not a File -- it does not carry `file.name` through, so the
  // FormData part built from it defaults to the literal filename "blob" per the Fetch/FormData spec. Re-wrapping
  // in a File restores the original name.
  if (!file.type.startsWith('image/')) return file;
  return new File(
    [await imageCompression(file, {
      maxSizeMB: 2,
      maxWidthOrHeight: 1600,
      useWebWorker: true,
    })],
    file.name,
    { type: file.type },
  );
}

// Exactly the keys api.commissions.createFromDeal reads; nothing else may ride along.
export function toCreateFromDealPayload(ticketId, form, invoiceAttachment) {
  return {
    ticketId,
    invoiceNumber: form.invoiceNumber.trim(),
    invoiceDate: form.invoiceDate,
    grossAmount: numberOrNull(form.grossAmount),
    bankFees: numberOrNull(form.bankFees) ?? 0,
    suspenseVat: numberOrNull(form.suspenseVat) ?? 0,
    transportFee: numberOrNull(form.transportFee) ?? 0,
    cutFee: numberOrNull(form.cutFee) ?? 0,
    shortfall: numberOrNull(form.shortfall) ?? 0,
    withholdingTax: numberOrNull(form.withholdingTax) ?? 0,
    overpayment: numberOrNull(form.overpayment) ?? 0,
    invoiceAttachment,
  };
}
