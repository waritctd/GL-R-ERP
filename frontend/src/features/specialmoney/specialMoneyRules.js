// Client-side APPROXIMATION of the welfare policy figures seeded in
// V66__special_money_request_schema.sql (hr.special_money_policy, "2018-06-08
// welfare policy figures") and the exclusion/eligibility rules in
// SpecialMoneyPolicyEvaluator.java.
//
// This exists ONLY to give the submitter fast, inline feedback (live amount,
// "over the cap" warnings) before they submit — it is not a second source of
// truth. The server re-evaluates every request against the live
// hr.special_money_policy table and hr.special_money_excluded_province, and
// its answer is the one that counts; nothing here is ever trusted for the
// actual submitted amount validation. If the effective-dated policy amounts
// change in the database, this file will silently drift and only the
// server-side response is authoritative — that is an accepted tradeoff for a
// fast-feedback-only slice (see backend/.../SpecialMoneyController.java,
// which has no endpoint that exposes these numbers to the frontend).
import { isExcludedProvince } from './thaiProvinces.js';

export const MEDICAL_ANNUAL_CAP = 3000;
export const AID_FIXED_CAP = 5000;
export const UNIFORM_ANNUAL_CAP = 1300;
export const UNIFORM_ANNUAL_MAX_PIECES = 4;
export const UNIFORM_ANNUAL_SHIRT_RATE = 300;
export const UNIFORM_ANNUAL_TROUSER_RATE = 350;

export const PER_DIEM_RATE_DRIVER = 400;
export const PER_DIEM_RATE_LOADER = 200;

export const AID_TYPES = ['AID_WEDDING', 'AID_ORDINATION', 'AID_CHILDBIRTH', 'AID_FUNERAL'];
export const UNIFORM_TYPES = ['UNIFORM_ANNUAL', 'UNIFORM_NEW_STAFF', 'UNIFORM_PREPROBATION_KIT'];
export const ONCE_PER_LIFETIME_TYPES = ['AID_WEDDING', 'AID_ORDINATION'];

/** Group label for the <optgroup> the type <select> renders. */
export function typeCategory(requestType) {
  if (requestType === 'TRAVEL_PER_DIEM' || requestType === 'TRAVEL_LODGING') return 'เดินทาง';
  if (requestType === 'MEDICAL') return 'ค่ารักษาพยาบาล';
  if (AID_TYPES.includes(requestType)) return 'เงินช่วยเหลือ';
  if (UNIFORM_TYPES.includes(requestType)) return 'เครื่องแต่งกาย';
  return 'อื่นๆ';
}

/** "คิดภาษี" / "ไม่คิดภาษี" chip, derived from the type's payrollBucket (GET /types). */
export function taxChip(payrollBucket) {
  // PER_DIEM and AID are taxable; NON_TAXABLE is not (per the slice-3 brief).
  const taxable = payrollBucket === 'PER_DIEM' || payrollBucket === 'AID';
  return taxable
    ? { label: 'คิดภาษี', tone: 'warning' }
    : { label: 'ไม่คิดภาษี', tone: 'success' };
}

/**
 * Fast client-side amount estimate + working text + warnings, per request type.
 * Returns { amount, working, warnings: string[] }. Never throws — an
 * incomplete form just yields amount 0 with no warnings.
 */
export function estimateAmount(requestType, form, usage) {
  switch (requestType) {
    case 'TRAVEL_PER_DIEM':
      return estimatePerDiem(form);
    case 'TRAVEL_LODGING':
      return { amount: 0, working: 'ระบุจำนวนเงินค่าที่พักตามใบเสร็จจริง (ไม่มีเพดานตายตัว)', warnings: [] };
    case 'MEDICAL':
      return estimateMedical(form, usage);
    case 'AID_WEDDING':
    case 'AID_ORDINATION':
    case 'AID_CHILDBIRTH':
    case 'AID_FUNERAL':
      return estimateAid(requestType, usage);
    case 'UNIFORM_ANNUAL':
      return estimateUniformAnnual(form);
    case 'UNIFORM_NEW_STAFF':
    case 'UNIFORM_PREPROBATION_KIT':
      return { amount: 0, working: 'ระบุจำนวนเงินตามชุดที่เบิกจริง', warnings: [] };
    case 'TRAINING':
    case 'OTHER':
    default:
      return { amount: 0, working: '', warnings: [] };
  }
}

function estimatePerDiem(form) {
  const warnings = [];
  const days = daysBetween(form.eventDate, form.eventEndDate);
  const province = form.province || '';
  if (province && isExcludedProvince(province)) {
    warnings.push(`${province} อยู่ในรายชื่อจังหวัดที่ถือเป็นการเดินทางในพื้นที่ (local commuting) — เบี้ยเลี้ยงเป็น ฿0`);
    return { amount: 0, working: `${days} วัน × ฿0 (จังหวัดยกเว้น)`, warnings };
  }
  const rate = form.role === 'loader' ? PER_DIEM_RATE_LOADER : form.role === 'driver' ? PER_DIEM_RATE_DRIVER : 0;
  if (!form.role) warnings.push('เลือกบทบาท (คนขับ/ผู้ช่วย) เพื่อคำนวณอัตราเบี้ยเลี้ยง');
  const amount = rate * days;
  return { amount, working: `${days} วัน × ฿${rate.toLocaleString('th-TH')}`, warnings };
}

function estimateMedical(form, usage) {
  const warnings = [];
  const requested = Number(form.requestedAmount || 0);
  const usedThisYear = Number(usage?.medicalUsedThisYear || 0);
  const remaining = Math.max(0, MEDICAL_ANNUAL_CAP - usedThisYear);
  let amount = requested;
  if (requested > remaining) {
    warnings.push(`เกินวงเงินคงเหลือปีนี้ (เหลือ ฿${remaining.toLocaleString('th-TH')}) — ระบบจะตัดยอดเบิกที่ ฿${remaining.toLocaleString('th-TH')}`);
    amount = remaining;
  }
  return { amount, working: `เพดานปีละ ฿${MEDICAL_ANNUAL_CAP.toLocaleString('th-TH')} · ใช้ไปแล้ว ฿${usedThisYear.toLocaleString('th-TH')}`, warnings };
}

function estimateAid(requestType, usage) {
  const warnings = [];
  if (ONCE_PER_LIFETIME_TYPES.includes(requestType) && (usage?.lifetimeCountByType?.[requestType] || 0) >= 1) {
    warnings.push('สิทธินี้เบิกได้ครั้งเดียวตลอดอายุงาน และคุณเคยเบิกไปแล้ว');
  }
  return { amount: AID_FIXED_CAP, working: `เงินช่วยเหลือคงที่ ฿${AID_FIXED_CAP.toLocaleString('th-TH')}`, warnings };
}

function estimateUniformAnnual(form) {
  const warnings = [];
  const shirtCount = Number(form.shirtCount || 0);
  const trouserCount = Number(form.trouserCount || 0);
  const totalPieces = shirtCount + trouserCount;
  if (totalPieces > UNIFORM_ANNUAL_MAX_PIECES) {
    warnings.push(`เกินจำนวนชิ้นสูงสุด ${UNIFORM_ANNUAL_MAX_PIECES} ชิ้น/ปี`);
  }
  const cappedShirts = Math.min(shirtCount, UNIFORM_ANNUAL_MAX_PIECES);
  const cappedTrousers = Math.min(trouserCount, Math.max(0, UNIFORM_ANNUAL_MAX_PIECES - cappedShirts));
  const amount = Math.min(
    UNIFORM_ANNUAL_CAP,
    cappedShirts * UNIFORM_ANNUAL_SHIRT_RATE + cappedTrousers * UNIFORM_ANNUAL_TROUSER_RATE,
  );
  return {
    amount,
    working: `เสื้อ ${shirtCount} × ฿${UNIFORM_ANNUAL_SHIRT_RATE} + กางเกง ${trouserCount} × ฿${UNIFORM_ANNUAL_TROUSER_RATE}`,
    warnings,
  };
}

function daysBetween(start, end) {
  if (!start) return 1;
  if (!end) return 1;
  const startDate = new Date(`${start}T00:00:00`);
  const endDate = new Date(`${end}T00:00:00`);
  const diff = Math.round((endDate.getTime() - startDate.getTime()) / 86400000) + 1;
  return diff >= 1 ? diff : 1;
}

/**
 * The 25th-of-month payroll cutoff (APP_SPECIAL_MONEY_PAYROLL_CUTOFF_DAY,
 * default 25 — see application.yml / AppProperties.SpecialMoney). This is an
 * informational client-side estimate of which payroll period a request would
 * land in if approved today; the server assigns the real payrollMonth at CEO
 * approval time (SpecialMoneyService#ceoApprove), rolling forward past any
 * already-processed month.
 */
export function payrollCutoffInfo(today = new Date(), cutoffDay = 25) {
  const day = today.getDate();
  const daysRemaining = day <= cutoffDay ? cutoffDay - day : monthLength(today) - day + cutoffDay;
  const targetMonth = new Date(today.getFullYear(), today.getMonth() + (day <= cutoffDay ? 0 : 1), 1);
  return { daysRemaining, targetMonth };
}

function monthLength(date) {
  return new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate();
}

const THAI_MONTHS = [
  'ม.ค.', 'ก.พ.', 'มี.ค.', 'เม.ย.', 'พ.ค.', 'มิ.ย.',
  'ก.ค.', 'ส.ค.', 'ก.ย.', 'ต.ค.', 'พ.ย.', 'ธ.ค.',
];

export function formatThaiMonthYear(date) {
  const buddhistYear = date.getFullYear() + 543;
  return `${THAI_MONTHS[date.getMonth()]} ${String(buddhistYear).slice(-2)}`;
}
