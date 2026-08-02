// Cap-presentation helpers for the ล.ย.01 declaration UI (issue #387).
//
// Every number surfaced here is read straight off `GET /api/payroll/tax-allowances/caps`
// (`TaxAllowanceCapEntry` — backend/.../declaration/TaxAllowanceDeclarationDtos.java). Nothing in
// this file invents a threshold: nulls stay nulls, and the only arithmetic performed is summing the
// EMPLOYEE'S OWN declared baht amounts for a shared-ceiling progress bar (decision #1's "shared
// groups get a group-level consumption bar, because a shared ceiling is invisible otherwise") —
// never Thai withholding-tax math, which stays server-side in PayrollCalculator/PayrollService.
import { formatMoney } from '../../utils/format.js';

export function capMapFrom(caps = []) {
  return new Map(caps.map((cap) => [cap.category, cap]));
}

/** Thai caption for a single field's own cap, built entirely from the cap row's own fields. */
export function fieldCapCaption(cap) {
  if (!cap) return null;
  const { kind, ownCap, groupCap, maxTotal, incomeRate, multiplier } = cap;
  const rate = incomeRate != null ? `${(Number(incomeRate) * 100).toLocaleString('th-TH')}%` : null;
  switch (kind) {
    case 'FLAT':
      return ownCap != null ? `สูงสุด ${formatMoney(ownCap)}` : null;
    case 'PER_HEAD': {
      let caption = ownCap != null ? `หัวละ ${formatMoney(ownCap)}` : null;
      if (maxTotal != null) caption = `${caption ?? ''} รวมไม่เกิน ${formatMoney(maxTotal)}`.trim();
      return caption;
    }
    case 'SHARED_GROUP':
      return `สูงสุด ${formatMoney(ownCap)} ต่อรายการ (รวมกลุ่มไม่เกิน ${formatMoney(groupCap)})`;
    case 'PERCENT_OF_INCOME': {
      const multiplierNote = multiplier != null && Number(multiplier) > 1 ? ` (หักได้ ${multiplier} เท่าของยอดที่จ่ายจริง)` : '';
      if (ownCap != null) {
        return `ไม่เกิน ${formatMoney(ownCap)} หรือ ${rate} ของเงินได้ แล้วแต่จำนวนใดต่ำกว่า${multiplierNote}`;
      }
      return rate ? `ไม่เกิน ${rate} ของเงินได้หลังหักค่าลดหย่อนอื่น${multiplierNote}` : null;
    }
    default:
      return null;
  }
}

/** SSF (and, if the catalogue ever adds one, any other percent-of-income row) with ownCap=0 means
 * "not deductible this tax year" — mirrors TaxAllowanceCapCatalog's SSF_FIRST_NON_DEDUCTIBLE_TAX_YEAR
 * (2025+): the backend returns ownCap 0 / incomeRate 0 rather than a separate boolean flag, so that
 * IS the signal, not a client-side year comparison (which would be exactly the "reimplement the
 * rule" mistake CLAUDE.md warns against).
 */
export function isCapUnavailableThisYear(cap) {
  if (!cap || cap.kind !== 'PERCENT_OF_INCOME') return false;
  return Number(cap.ownCap ?? 0) === 0 && Number(cap.incomeRate ?? 0) === 0;
}

export const GROUP_LABELS = {
  life_health: 'ประกันชีวิต + ประกันสุขภาพ',
  retirement: 'RMF + SSF + บำนาญ',
  donation: 'เงินบริจาคสถานศึกษา + เงินบริจาคทั่วไป',
};

// Consumption order for the shared retirement ceiling — decision #1's "consumed RMF -> SSF ->
// pension, in that order" — used only to order the stacked bar segments, not to compute an
// authoritative capped figure (the backend does that at submit time).
const GROUP_FIELD_ORDER = {
  life_health: [
    { field: 'lifeInsuranceAllowance', category: 'life_insurance' },
    { field: 'healthInsuranceAllowance', category: 'health_insurance' },
  ],
  retirement: [
    { field: 'rmfAllowance', category: 'rmf' },
    { field: 'ssfAllowance', category: 'ssf' },
    { field: 'pensionInsuranceAllowance', category: 'pension' },
  ],
};

/**
 * Declared-total vs shared groupCap, for the consumption bars on the ประกัน/การออมฯ sections.
 * Returns `{ groupId, label, total, cap, segments }[]`. `cap` is whatever `groupCap` the catalogue
 * returned (never a literal here); `total` is the plain sum of what the employee typed into the
 * fields belonging to that group.
 */
export function computeGroupUsage(caps, values) {
  const byCategory = capMapFrom(caps);
  return Object.entries(GROUP_FIELD_ORDER)
    .map(([groupId, members]) => {
      const segments = members.map(({ field, category }) => ({
        field,
        category,
        amount: Number(values?.[field] || 0),
      }));
      const total = segments.reduce((sum, segment) => sum + segment.amount, 0);
      const cap = members
        .map(({ category }) => byCategory.get(category)?.groupCap)
        .find((value) => value != null) ?? null;
      return { groupId, label: GROUP_LABELS[groupId] ?? groupId, total, cap, segments };
    })
    .filter((group) => group.cap != null);
}
