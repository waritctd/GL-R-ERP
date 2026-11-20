const TECHNICAL_MESSAGE_MAP = [
  {
    test: (message) => /user is not linked to an employee/i.test(message),
    message: 'ยังเชื่อมบัญชีนี้กับข้อมูลพนักงานไม่ได้',
    description: 'ติดต่อฝ่ายบุคคลเพื่อเชื่อมบัญชีกับข้อมูลพนักงาน แล้วลองใหม่อีกครั้ง',
  },
];

function textFrom(value) {
  if (!value) return '';
  if (typeof value === 'string') return value;
  if (typeof value.message === 'string') return value.message;
  return '';
}

function technicalMessageMatch(value) {
  const message = textFrom(value);
  if (!message) return null;
  return TECHNICAL_MESSAGE_MAP.find((entry) => entry.test(message)) ?? null;
}

export function toUserErrorMessage(error, fallback = 'ดำเนินการไม่สำเร็จ') {
  const mapped = technicalMessageMatch(error);
  if (mapped) return mapped.message;
  return fallback;
}

export function toUserErrorDescription(error, fallback = '') {
  const mapped = technicalMessageMatch(error);
  if (mapped) return mapped.description;
  return fallback;
}

export function sanitizeToastMessage(kind, message) {
  if (kind !== 'error') return message;
  const mapped = technicalMessageMatch(message);
  if (!mapped) return message;
  console.error('Suppressed technical error toast:', textFrom(message));
  return mapped.message;
}

const BUNDLE_PART_LABEL = { po: 'PO', attachment: 'ไฟล์แนบ', quotation: 'ใบเสนอราคา' };

/** The warning for a PDF download whose bundle left out a PO / the quotation (hrApi copies the server's
 * X-Document-Bundle-Skipped header onto the blob as `bundleSkipped`); null when nothing was skipped. */
function bundleSkippedMessage(blob) {
  const parts = String(blob?.bundleSkipped ?? '').split(',').map((p) => BUNDLE_PART_LABEL[p.trim()]).filter(Boolean);
  return parts.length ? `แนบ ${parts.join(' และ ')} ไม่ครบ กรุณาตรวจสอบไฟล์ก่อนส่งลูกค้า` : null;
}

/** Toasts the bundleSkippedMessage warning after a PDF download; a no-op when nothing was skipped. */
export function warnIfBundleSkipped(blob, showToast) {
  const message = [bundleSkippedMessage(blob), blob?.bundleLarge ? 'ไฟล์รวมใหญ่เกิน 15 MB อาจส่งทางอีเมลไม่ได้' : null]
    .filter(Boolean).join(' · ');
  if (message) showToast?.('warning', message);
}
