// Pure helpers for the delivery-round screens (DeliveryRoundsPanel, DeliveryRequestSheet).
import { useState } from 'react';
import { formatThaiDate } from '../../../utils/format.js';

/** Opens a file in a new tab. The tab is opened in the tap (a phone blocks a window opened after an await), then pointed at
 * the blob. Resolves 'ok', 'blocked' (the browser refused the tab) or 'failed' (the fetch failed: the blank tab is closed). */
export async function openBlobInTab(getBlob) {
  const tab = window.open('', '_blank');
  if (!tab) return 'blocked';
  try {
    const blob = await getBlob();
    const url = URL.createObjectURL(blob.type ? blob : new Blob([blob], { type: 'application/pdf' }));
    tab.location.href = url;
    setTimeout(() => URL.revokeObjectURL(url), 60000);
    return 'ok';
  } catch {
    tab.close();
    return 'failed';
  }
}

export const OPEN_FILE_FAILED = 'เปิดไฟล์ไม่สำเร็จ ลองใหม่อีกครั้ง';

/** onClick for an attached-file link: show it in a tab. Only when the browser blocked the tab does it fall back to the plain
 * download; a failed fetch (file gone, session expired) stays on the page and calls `onFail(message)`. */
export const openAttachedFile = (url, getBlob, onFail = () => {}) => async (event) => {
  event.preventDefault();
  const result = await openBlobInTab(getBlob);
  if (result === 'blocked') window.location.assign(url);
  else if (result === 'failed') onFail(OPEN_FILE_FAILED);
};

/** `[handler, error]`: `handler(url, getBlob)` is a link's onClick; `error` is the message to show beside the list. */
export function useFileOpener() {
  const [error, setError] = useState(null);
  const handler = (url, getBlob) => (event) => { setError(null); return openAttachedFile(url, getBlob, setError)(event); };
  return [handler, error];
}

export const ROUND_STEPS = [
  { key: 'request', label: 'ขอออกใบกำกับภาษี', hint: 'ฝ่ายขายส่งเอกสารให้คลัง' },
  { key: 'review', label: 'คลังตรวจเอกสาร', hint: null },
  { key: 'invoice', label: 'ออกใบกำกับภาษี', hint: null },
  { key: 'deliver', label: 'ส่งของ', hint: 'บันทึกได้เมื่อมีใบกำกับภาษีของรอบนี้' },
];

// How many of the four steps are finished, per status. A DRAFT or RETURNED round is still step 1 (the rep's).
const STEPS_DONE = { DRAFT: 0, RETURNED: 0, SENT: 1, FORWARDED: 2, INVOICED: 3 };

/** 'done' | 'now' | 'todo' for step `index` (0-based) of a round with `status`. */
export function stepState(status, index) {
  const done = STEPS_DONE[status] ?? 0;
  if (index < done) return 'done';
  return index === done ? 'now' : 'todo';
}

/** The covered quantity of a round, summed over its lines, with the unit of the first line ("300 แผ่น"). */
export function roundQtyText(round) {
  const lines = round?.lines ?? [];
  if (lines.length === 0) return '';
  const total = lines.reduce((sum, l) => sum + Number(l.coveredQty || 0), 0);
  return `${total.toLocaleString('en-US', { maximumFractionDigits: 2 })} ${lines[0].unit ?? ''}`.trim();
}

/** "19 พ.ย. 2569 10:42" in Bangkok time, or '' without a value. */
export function thaiDateTime(value) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const time = date.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Bangkok' });
  const day = new Date(date.toLocaleString('en-US', { timeZone: 'Asia/Bangkok' }));
  return `${formatThaiDate(day)} ${time}`;
}

export function fileSizeText(bytes) {
  const n = Number(bytes);
  if (!Number.isFinite(n) || n <= 0) return '';
  return n < 1024 * 1024 ? `${Math.max(1, Math.round(n / 1024))} KB` : `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

/** The one-line caption under a round file: who attached it, and its size. */
export function fileCaption(file) {
  const source = file.kind === 'SYSTEM_DOC' ? 'จากระบบ · PDF'
    : file.kind === 'INVOICE' ? 'ใบกำกับภาษี'
      : file.kind === 'DELIVERY_PROOF' ? 'หลักฐานการส่ง'
      : file.uploaderSide === 'WAREHOUSE' ? 'คลังแนบ' : file.uploaderSide === 'SALES' ? 'ฝ่ายขายแนบ' : null;
  return [source, file.kind === 'SYSTEM_DOC' ? null : fileSizeText(file.fileSize)].filter(Boolean).join(' · ');
}

/** What the warehouse can say is missing when it sends a round back (the server's DeliveryRequestService.RETURN_REASONS). */
export const RETURN_REASONS = [
  { code: 'ADDRESS_TAX_ID', label: 'ที่อยู่ออกใบกำกับ / เลขผู้เสียภาษี' },
  { code: 'PAYMENT_PROOF', label: 'หลักฐานการชำระเงิน' },
  { code: 'DELIVERY_DATE_PLACE', label: 'วันและสถานที่ส่ง' },
];

/** The reason a round was sent back, as one line: the ticked labels, then the warehouse's own words. */
export function returnReasonText(round) {
  const labels = (round?.returnReasonCodes ?? []).map((code) => RETURN_REASONS.find((r) => r.code === code)?.label ?? code);
  return [...labels, round?.returnReasonText].filter(Boolean).join(' · ');
}

export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
export const MAX_FILES_PER_ROUND = 30;

/**
 * Whether a deposit notice / remaining invoice may go with a round (the server's DeliveryRequestService#requireOfQuotation).
 * `refNumber`: the quotation number the document names (a notice's reference, the invoice's quotation), may be blank or a
 * PO number. One naming an approved quotation belongs to that one; any other is attachable only when the order has
 * exactly one approved quotation. Returns `{ ok, named }` (`named`: the approved quotation it names, or null).
 */
export function docBelongsToRound(refNumber, approvedQuotations, chosenQuotationId) {
  const list = approvedQuotations ?? [];
  // No chosen quotation yet: the server takes the only approved one.
  const chosen = chosenQuotationId ?? (list.length === 1 ? list[0].id : null);
  const named = refNumber ? list.find((q) => q.number === refNumber) ?? null : null;
  const ok = named ? chosen != null && named.id === Number(chosen) : list.length === 1;
  return { ok, named };
}

export const DOC_AMBIGUOUS_TEXT = 'ไม่ระบุใบเสนอราคา ออเดอร์นี้มีหลายใบ จึงไม่แนบในรอบนี้';
