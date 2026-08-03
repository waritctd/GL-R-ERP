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
//
// EXTENDED (welfare page IA redesign, 2026-08) with TYPE_GROUPS, RULE_CARD and
// DETAIL_KEYS -- the same approximation warning above applies to every string
// in RULE_CARD below: each is either one of this file's own numeric
// constants (already an approximation of hr.special_money_policy) or a
// verbatim paraphrase of a comment/branch actually present in
// SpecialMoneyPolicyEvaluator.java, never an invented figure. The
// PREPROBATION_KIT_* rates below are read straight from the same V66 seed
// row set the rest of this file's constants already cite (see the comment on
// PREPROBATION_KIT_TSHIRT_RATE) -- an earlier version of this file left them
// out on the mistaken assumption they were not derivable client-side.
import { isExcludedProvince } from './thaiProvinces.js';

export const MEDICAL_ANNUAL_CAP = 3000;
export const AID_FIXED_CAP = 5000;
export const UNIFORM_ANNUAL_CAP = 1300;
export const UNIFORM_ANNUAL_MAX_PIECES = 4;
export const UNIFORM_ANNUAL_SHIRT_RATE = 300;
export const UNIFORM_ANNUAL_TROUSER_RATE = 350;

// §2.1.3 pre-probation kit — seeded in V66__special_money_request_schema.sql:164-171
// (hr.special_money_policy, same 2018-06-08 row set every other constant above cites). สายรัดหลัง
// is conditional on the request's own `needsBackSupport` detail flag (evaluateUniformPreprobationKit
// only adds it "กรณีของเดิมที่มีไม่เพียงพอ"), so it is broken out as its own rate/qty pair rather than
// folded into a single fixed total.
export const PREPROBATION_KIT_TSHIRT_RATE = 220;
export const PREPROBATION_KIT_TSHIRT_QTY = 3;
export const PREPROBATION_KIT_TROUSER_RATE = 300;
export const PREPROBATION_KIT_TROUSER_QTY = 3;
export const PREPROBATION_KIT_SHOES_RATE = 400;
export const PREPROBATION_KIT_SHOES_QTY = 1;
export const PREPROBATION_KIT_BELT_RATE = 700;
export const PREPROBATION_KIT_BELT_QTY = 1;
export const PREPROBATION_KIT_BASE_TOTAL = PREPROBATION_KIT_TSHIRT_RATE * PREPROBATION_KIT_TSHIRT_QTY
  + PREPROBATION_KIT_TROUSER_RATE * PREPROBATION_KIT_TROUSER_QTY
  + PREPROBATION_KIT_SHOES_RATE * PREPROBATION_KIT_SHOES_QTY;
export const PREPROBATION_KIT_TOTAL_WITH_BELT = PREPROBATION_KIT_BASE_TOTAL
  + PREPROBATION_KIT_BELT_RATE * PREPROBATION_KIT_BELT_QTY;

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

// Mode-aware (evaluateUniformAnnual): SELF_BUY prices from the per-piece shirt/trouser rates
// (unchanged math, the only mode this used to support); TAILORED is a flat amount up to the cap
// -- the evaluator's TAILORED branch returns `request.requestedAmount()` clamped at the cap, it
// does not compute anything from piece counts, even though the piece-count CHECK itself still
// applies in both modes (see the caller's own warning below).
function estimateUniformAnnual(form) {
  const warnings = [];
  const shirtCount = Number(form.shirtCount || 0);
  const trouserCount = Number(form.trouserCount || 0);
  const totalPieces = shirtCount + trouserCount;
  if (totalPieces > UNIFORM_ANNUAL_MAX_PIECES) {
    warnings.push(`เกินจำนวนชิ้นสูงสุด ${UNIFORM_ANNUAL_MAX_PIECES} ชิ้น/ปี`);
  }

  if (form.uniformMode === 'TAILORED') {
    const requested = Number(form.requestedAmount || 0);
    const amount = Math.min(requested, UNIFORM_ANNUAL_CAP);
    if (requested > UNIFORM_ANNUAL_CAP) {
      warnings.push(`เกินเพดานตัดชุดกับร้านที่บริษัทกำหนด (สูงสุด ฿${UNIFORM_ANNUAL_CAP.toLocaleString('th-TH')}) — ระบบจะตัดยอดเบิกที่เพดาน`);
    }
    return { amount, working: `เพดานตัดชุด ฿${UNIFORM_ANNUAL_CAP.toLocaleString('th-TH')} ต่อปี`, warnings };
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

/**
 * Evidence document expected per type. Moved here (was a SpecialMoneyPanel.jsx-local
 * function) so RuleCard.jsx and SpecialMoneyPanel.jsx share one copy — both need it, and a
 * second copy is exactly how the panel's own "หลักฐานประกอบ" hint and the rule card's
 * "หลักฐานที่ต้องแนบ" row could drift apart.
 */
export function evidenceLabel(requestType) {
  const map = {
    MEDICAL: 'ใบเสร็จรับเงินค่ารักษาพยาบาล',
    TRAVEL_LODGING: 'ใบเสร็จค่าที่พัก',
    AID_WEDDING: 'บัตรเชิญ / ทะเบียนสมรส',
    AID_ORDINATION: 'ภาพถ่ายงานบวช',
    AID_CHILDBIRTH: 'สูติบัตรบุตร',
    AID_FUNERAL: 'ใบมรณบัตร / ภาพถ่ายงานศพ',
    UNIFORM_ANNUAL: 'ใบเสร็จรับเงินชุดฟอร์ม',
    UNIFORM_NEW_STAFF: 'ใบเสร็จรับเงินชุดฟอร์ม',
    UNIFORM_PREPROBATION_KIT: 'ใบเสร็จรับเงินชุดฟอร์ม',
    TRAINING: 'ใบสุทธิ / ใบเสร็จค่าอบรม',
    OTHER: 'เอกสารประกอบการเบิก',
  };
  return map[requestType] || 'เอกสารหลักฐาน';
}

function money(value) {
  return `฿${Number(value || 0).toLocaleString('th-TH')}`;
}

/**
 * Fixed, rule-derived type grouping (plan §"Type grouping"): ordered by how often the money
 * actually moves, not alphabetically or by backend enum order, so twelve types never compete
 * with equal visual weight. Group 1 is open by default in TypePicker.jsx; the rest disclose on
 * demand. Every SpecialMoneyType the backend defines must appear in exactly one group — see
 * SpecialMoneyPanel.test.jsx's "grouping puts common types first" test, which asserts this
 * table's shape directly rather than re-deriving it.
 */
export const TYPE_GROUPS = [
  { key: 'FREQUENT', label: 'เบิกได้บ่อย', types: ['TRAVEL_PER_DIEM', 'TRAVEL_LODGING', 'MEDICAL'] },
  { key: 'LIFE_EVENT', label: 'เหตุการณ์สำคัญ', types: ['AID_WEDDING', 'AID_ORDINATION', 'AID_CHILDBIRTH', 'AID_FUNERAL'] },
  { key: 'UNIFORM', label: 'เครื่องแต่งกาย (ตามรอบปี)', types: UNIFORM_TYPES },
  { key: 'OTHER', label: 'อื่นๆ', types: ['TRAINING', 'OTHER'] },
];

/**
 * `detail` keys each type actually reads server-side (SpecialMoneyPolicyEvaluator
 * #detailValue), for documentation and for pinning the string-typing contract in tests --
 * `detail` is `Map<String,String>` on the wire, so a numeric/boolean value here must already be
 * a string by the time it reaches `create()`'s payload (see SpecialMoneyPanel.jsx's
 * `detailFor()`, and the evaluator's own `parseIntOrZero`/`Boolean.parseBoolean` callers).
 */
export const DETAIL_KEYS = {
  TRAVEL_PER_DIEM: ['destination', 'province', 'role'],
  TRAVEL_LODGING: ['destination', 'province'],
  UNIFORM_ANNUAL: ['uniformMode', 'shirtCount', 'trouserCount'],
  UNIFORM_NEW_STAFF: ['shirtCount', 'trouserCount'],
  // evaluateUniformPreprobationKit reads ONLY needsBackSupport -- it does not read
  // shirtCount/trouserCount at all (the kit's piece counts are fixed by policy, not chosen by the
  // requester). This used to list shirtCount/trouserCount (a path this type never reads) and omit
  // needsBackSupport (the only key it does read), so the belt could never actually be requested.
  UNIFORM_PREPROBATION_KIT: ['needsBackSupport'],
  AID_FUNERAL: ['relation'],
};

/**
 * The rule card table (plan §"The rule card"): one entry per SpecialMoneyType, restating only
 * what SpecialMoneyPolicyEvaluator.java actually enforces for that type, so RuleCard.jsx can
 * render "only that type's rules" instead of the employee reading all twelve. `eligibility` is
 * populated ONLY where the evaluator restricts who may claim beyond the standard "passed
 * probation" gate every type gets (evaluateStandardProbationEligibility) -- i.e. TRAVEL_PER_DIEM
 * (driver/loader role), AID_FUNERAL (relation), and UNIFORM_PREPROBATION_KIT
 * (evaluatePreprobationKitEligibility's department/position gate) -- matching the plan's "only
 * where restrictive" instruction.
 */
export const RULE_CARD = {
  TRAVEL_PER_DIEM: {
    cap: `${money(PER_DIEM_RATE_DRIVER)}/วัน (คนขับ) หรือ ${money(PER_DIEM_RATE_LOADER)}/วัน (ผู้ช่วย/ยกของ) — จังหวัดในรายการยกเว้น (ถือเป็นการเดินทางในพื้นที่) จ่าย ฿0`,
    deadline: null,
    frequency: 'ไม่จำกัดจำนวนครั้ง (คำนวณต่อทริป)',
    eligibility: 'ต้องระบุบทบาท: คนขับ หรือ ผู้ช่วย/ยกของ เท่านั้น',
  },
  TRAVEL_LODGING: {
    cap: 'ไม่มีเพดานตายตัว — เบิกตามจำนวนในใบเสร็จค่าที่พักจริง',
    deadline: null,
    frequency: 'ไม่จำกัดจำนวนครั้ง',
    eligibility: null,
  },
  MEDICAL: {
    cap: `${money(MEDICAL_ANNUAL_CAP)} ต่อปี (รวมทุกครั้งที่เบิกในปีนั้น)`,
    deadline: 'ใบเสร็จต้องลงวันที่ไม่เกิน 1 เดือนก่อนวันที่ยื่นคำขอ',
    frequency: 'เบิกได้หลายครั้ง จนกว่าจะครบวงเงินรวมต่อปี',
    eligibility: null,
  },
  AID_WEDDING: {
    cap: `${money(AID_FIXED_CAP)} (จำนวนคงที่)`,
    deadline: 'ไม่มีกำหนดเวลาเบิกหลังวันงาน',
    frequency: 'เบิกได้ครั้งเดียวตลอดการเป็นพนักงาน',
    eligibility: null,
  },
  AID_ORDINATION: {
    cap: `${money(AID_FIXED_CAP)} (จำนวนคงที่)`,
    deadline: 'ไม่มีกำหนดเวลาเบิกหลังวันงาน',
    frequency: 'เบิกได้ครั้งเดียวตลอดการเป็นพนักงาน',
    eligibility: null,
  },
  AID_CHILDBIRTH: {
    cap: `${money(AID_FIXED_CAP)} (จำนวนคงที่)`,
    deadline: 'ไม่มีกำหนดเวลาเบิกหลังวันคลอด',
    frequency: 'ไม่จำกัดจำนวนครั้ง (ต่อการคลอดแต่ละครั้ง)',
    eligibility: null,
  },
  AID_FUNERAL: {
    cap: `${money(AID_FIXED_CAP)} (จำนวนคงที่)`,
    deadline: 'ไม่มีกำหนดเวลาเบิกหลังงานศพ',
    frequency: 'ไม่จำกัดจำนวนครั้ง',
    eligibility: 'เฉพาะกรณีบิดา/มารดา คู่สมรส หรือบุตรของพนักงานเท่านั้น',
  },
  UNIFORM_ANNUAL: {
    cap: `${money(UNIFORM_ANNUAL_CAP)} (ตัดชุดกับร้านที่บริษัทกำหนด) หรือ เสื้อ ${money(UNIFORM_ANNUAL_SHIRT_RATE)}/ชิ้น + กางเกง ${money(UNIFORM_ANNUAL_TROUSER_RATE)}/ชิ้น (ซื้อเอง) — รวมไม่เกิน ${UNIFORM_ANNUAL_MAX_PIECES} ชิ้น`,
    deadline: 'ยื่นคำขอภายในเดือนมิถุนายน — กรณีซื้อเอง ใบเสร็จต้องลงวันที่เดือนพฤษภาคม',
    frequency: 'ปีละ 1 ครั้ง',
    eligibility: null,
  },
  // No baht figure exists anywhere for this type (see evaluateUniformNewStaff's javadoc); the
  // "3 ชุด (6 ชิ้น)" figure is quoted verbatim from that comment, not invented here.
  UNIFORM_NEW_STAFF: {
    cap: 'ตามนโยบาย: 3 ชุด (6 ชิ้น) สำหรับปีแรกที่เข้าทำงาน — ไม่มีวงเงินบาทตายตัว',
    deadline: 'เฉพาะภายในปีแรกนับจากวันเริ่มงานเท่านั้น',
    frequency: 'เบิกได้ครั้งเดียวตลอดการเป็นพนักงาน',
    eligibility: null,
  },
  // PREPROBATION_KIT_* rates are the V66 seed (see the constant declarations above) -- an earlier
  // version of this table claimed no client constant existed for these and described the cap only
  // in words; that was wrong, the same seed row set every other figure in this file already cites
  // does carry them.
  UNIFORM_PREPROBATION_KIT: {
    cap: `รวม ${money(PREPROBATION_KIT_BASE_TOTAL)} (เสื้อยืด ${money(PREPROBATION_KIT_TSHIRT_RATE)}×${PREPROBATION_KIT_TSHIRT_QTY} · กางเกง `
      + `${money(PREPROBATION_KIT_TROUSER_RATE)}×${PREPROBATION_KIT_TROUSER_QTY} · รองเท้า ${money(PREPROBATION_KIT_SHOES_RATE)}×${PREPROBATION_KIT_SHOES_QTY}) `
      + `— บวกสายรัดหลัง ${money(PREPROBATION_KIT_BELT_RATE)} เป็นรวม ${money(PREPROBATION_KIT_TOTAL_WITH_BELT)} เฉพาะกรณีของเดิมไม่เพียงพอ`,
    deadline: null,
    frequency: 'ระบบไม่ได้จำกัดจำนวนครั้งไว้',
    eligibility: 'เฉพาะพนักงานขับรถ / ติดรถส่งของ / ฝ่ายโมเสค / สนับสนุนฝ่ายขาย ที่ทำงานมาแล้วอย่างน้อย 7 วัน '
      + '— §2.1.3 กำหนดว่าโมเสคและสนับสนุนฝ่ายขายมีสิทธิ์เฉพาะกรณี "ที่ต้องออกไปพบลูกค้ากับฝ่ายขาย" เท่านั้น '
      + '(CEO เป็นผู้พิจารณาตอนอนุมัติ ระบบไม่ได้บังคับเงื่อนไขนี้) · พนักงานติดรถส่งของและฝ่ายโมเสคยังไม่มีรหัสแผนก/'
      + 'ตำแหน่งในข้อมูล HR จึงยังเบิกผ่านระบบไม่ได้จนกว่า HR จะตั้งค่าเพิ่ม',
  },
  TRAINING: {
    cap: 'ไม่มีเพดานตายตัว — พิจารณาจากดุลยพินิจของ CEO',
    deadline: null,
    frequency: 'ไม่จำกัดจำนวนครั้ง',
    eligibility: null,
  },
  OTHER: {
    cap: 'ไม่มีเพดานตายตัว — พิจารณาจากดุลยพินิจของ CEO',
    deadline: null,
    frequency: 'ไม่จำกัดจำนวนครั้ง',
    eligibility: null,
  },
};

export function ruleCardFor(requestType) {
  return RULE_CARD[requestType] || null;
}
