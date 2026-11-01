import { PRE_CEO_GATE_STATUSES } from './pricingRequestMeta.js';

// Pure helpers behind the import pricing view-model (PricingRequestDetailPage#buildImportModel):
// no React, no queries, no gates — just how a request item is described and what "ขั้นต่อไป" says.

export function formatOrDash(value, suffix = '') {
  return value == null || value === '' ? '—' : `${value}${suffix}`;
}

/** Quantities shown with up to 2 decimals, no trailing zeros (7.2, 5, 7.25). */
export function formatQty(value) {
  return Number(Number(value).toFixed(2)).toLocaleString('en-US');
}

export function qtyOf(item) {
  return `${item.requestedQty ?? '—'} ${item.requestedUnit ?? ''}`.trim();
}

const OTHER_COUNTRY = 'อื่นๆ';
const countryText = (item) => (item?.originCountry === OTHER_COUNTRY
  ? `${OTHER_COUNTRY} (${formatOrDash(item.originCountryOther)})`
  : item?.originCountry);

export const specFor = (item) => [
  ['สี', formatOrDash(item?.color)],
  ['ผิว', formatOrDash(item?.texture)],
  ['ขนาด', formatOrDash(item?.size)],
  ['ความหนา', formatOrDash(item?.thicknessMm, ' มม.')],
  ['ขายแผ่นไม่เต็มกล่อง', item?.piecesPerBox != null ? (item.roundToFullBox === false ? 'ใช่' : 'ไม่ใช่') : '—'],
  ['โรงงาน (ที่ฝ่ายขายกรอก)', item?.brand?.trim() || '—'],
  ['ประเทศต้นทาง', formatOrDash(countryText(item) || null)],
];

export const productCodeOf = (item) => item?.productCode?.trim() || item?.catalogProductCode || '—';
export const variantOf = (item) => [item?.size, item?.color, item?.texture].filter(Boolean).join(' · ');
export const qtyDetailOf = (item) => [
  item?.requestedQtySqm != null ? `${formatQty(item.requestedQtySqm)} ตร.ม.` : null,
  item?.boxes != null ? `${item.boxes} กล่อง` : null,
].filter(Boolean).join(' · ');
export const originOf = (items) => items.map(countryText).find(Boolean) ?? null;
export const metaOf = (items, count) => [originOf(items), `${count} รายการ`].filter(Boolean).join(' · ');

/**
 * The ขั้นต่อไป line of the progress strip. First match wins.
 * `cards` need only isContacted / isConfirmed / isDeclined / name.
 */
export function nextStep({ status, cards, missingCount, firstUnassignedName, canSetItemFactory, hasGateNotice }) {
  const pending = cards.filter((c) => !c.isConfirmed);
  const firstDeclined = cards.find((c) => c.isDeclined);
  const firstNotContacted = cards.find((c) => !c.isContacted);
  let next;
  let sub;
  if (status === 'CANCELLED') {
    next = 'คำขอนี้ถูกยกเลิกแล้ว';
    sub = '';
  } else if (status === 'SUPERSEDED') {
    next = 'คำขอนี้ถูกแทนด้วยรอบใหม่';
    sub = '';
  } else if (missingCount > 0 && canSetItemFactory) {
    next = `ระบุโรงงานให้ "${firstUnassignedName}" ก่อน`;
    sub = 'รายการนี้ยังไม่มีโรงงาน จึงขอราคาไม่ได้ และคำขอจะยังไม่ไปถึง CEO';
  } else if (missingCount > 0) {
    // no picker is rendered in this status, so do not point at one
    next = `ยังไม่ได้ระบุโรงงาน ${missingCount} รายการ — ฝ่ายนำเข้าระบุไม่ได้ในขั้นนี้`;
    sub = '';
  } else if (firstDeclined) {
    next = `${firstDeclined.name} แจ้งไม่พร้อมเสนอราคา`;
    sub = hasGateNotice ? 'ดูรายละเอียดด้านล่าง' : 'คำขอจะยังไม่ส่งถึง CEO จนกว่าโรงงานนี้จะพร้อมเสนอราคา';
  } else if (cards.length === 0) {
    next = 'ไม่มีรายการที่ต้องขอราคาจากโรงงาน';
    sub = '';
  } else if (firstNotContacted) {
    next = `ติดต่อ ${firstNotContacted.name}`;
  } else if (pending.length > 0) {
    next = `กรอกราคาและยืนยัน ${pending[0].name}`;
  } else if (PRE_CEO_GATE_STATUSES.includes(status)) {
    // every factory is confirmed, but the server moves the request on more than that (see ceoGateNotice)
    next = 'ยืนยันราคาครบแล้ว แต่ยังไม่ส่งถึง CEO';
    sub = hasGateNotice ? 'ดูรายละเอียดด้านล่าง' : 'ระบบจะส่งถึง CEO เมื่อครบเงื่อนไข';
  } else {
    next = 'ส่งถึง CEO แล้ว';
    sub = 'ยืนยันราคาครบทุกโรงงาน คำขอนี้ไปอยู่ที่ "รอ CEO พิจารณาราคา"';
  }
  if (sub === undefined) {
    sub = pending.length === 1
      ? 'ยืนยันโรงงานนี้แล้วคำขอนี้จะส่งถึง CEO อัตโนมัติ'
      : `ยังเหลือ ${pending.length} โรงงาน · คำขอจะส่งถึง CEO อัตโนมัติเมื่อยืนยันครบทุกโรงงาน`;
  }
  return { next, sub };
}
