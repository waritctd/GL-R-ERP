// Shared field/group metadata for the ล.ย.01 tax-allowance declaration (issue #387).
//
// The 21 declared controls below are exactly `TaxAllowanceDeclarationSubmitRequest`'s fields
// (backend/.../declaration/TaxAllowanceDeclarationDtos.java) minus `taxYear`/`effectiveMonth`/
// `documentReference` — same set `PayrollTaxAllowanceInput` carries. Keeping the key names
// identical end to end (mockApi.js's `taxAllowanceAllowancesFromBody` uses the same names) means a
// value read from `declaration.allowances` can be dropped straight into this form's defaults with
// no translation step.
//
// `capCategory` links a field to its row in `GET /api/payroll/tax-allowances/caps` — every cap
// number shown next to a field must come from that response, never a literal here (CLAUDE.md /
// issue #387 decision #1).

export const ALLOWANCE_MONEY_KEYS = [
  'spouseAllowance',
  'childAllowance',
  'parentCareAllowance',
  'disabledCareAllowance',
  'maternityAllowance',
  'lifeInsuranceAllowance',
  'healthInsuranceAllowance',
  'parentHealthInsuranceAllowance',
  'rmfAllowance',
  'ssfAllowance',
  'pensionInsuranceAllowance',
  'thaiEsgAllowance',
  'homeLoanInterestAllowance',
  'educationDonation',
  'generalDonation',
  'politicalDonation',
];

export const ALLOWANCE_COUNT_KEYS = [
  'childCount',
  'childCountDouble',
  'disabledCareCount',
  'parentCareCount',
];

export const ALLOWANCE_CHECKBOX_KEYS = ['disabilityCardHolder'];

export const ALL_ALLOWANCE_KEYS = [...ALLOWANCE_MONEY_KEYS, ...ALLOWANCE_COUNT_KEYS, ...ALLOWANCE_CHECKBOX_KEYS];

// Field order/grouping mirrors the issue's screen 1 layout exactly: ครอบครัว → ประกัน →
// การออมและการลงทุน → ที่อยู่อาศัย → เงินบริจาค.
export const TAX_ALLOWANCE_GROUPS = [
  {
    key: 'family',
    title: 'ครอบครัว',
    fields: [
      { key: 'spouseAllowance', label: 'คู่สมรส (ไม่มีเงินได้)', capCategory: 'spouse', kind: 'money' },
      { key: 'childAllowance', label: 'บุตร', capCategory: 'child', kind: 'money' },
      { key: 'childCount', label: 'จำนวนบุตร', capCategory: 'child', kind: 'count', unit: 'คน' },
      {
        key: 'childCountDouble',
        label: 'จำนวนบุตรที่ได้สิทธิ 2 เท่า',
        capCategory: 'child_double',
        kind: 'count',
        unit: 'คน',
        hint: 'บุตรคนที่ 2 เป็นต้นไปที่เกิดตั้งแต่ พ.ศ. 2561 ได้รับสิทธิเพิ่มอีกหัวละ 30,000 บาท',
      },
      { key: 'parentCareAllowance', label: 'อุปการะเลี้ยงดูบิดามารดา', capCategory: 'parent_care', kind: 'money' },
      { key: 'parentCareCount', label: 'จำนวนบิดามารดาที่อุปการะ', capCategory: 'parent_care', kind: 'count', unit: 'คน' },
      { key: 'disabledCareAllowance', label: 'อุปการะเลี้ยงดูคนพิการ/ทุพพลภาพ', capCategory: 'disabled_care', kind: 'money' },
      { key: 'disabledCareCount', label: 'จำนวนคนพิการ/ทุพพลภาพที่อุปการะ', capCategory: 'disabled_care', kind: 'count', unit: 'คน' },
      { key: 'maternityAllowance', label: 'ค่าฝากครรภ์และคลอดบุตร', capCategory: 'maternity', kind: 'money' },
      {
        key: 'disabilityCardHolder',
        label: 'ผู้พิการที่อุปการะมีบัตรประจำตัวคนพิการ',
        kind: 'checkbox',
        hint: 'มีบัตร: ยกเว้นเงินได้ได้ทุกช่วงอายุ · ไม่มีบัตร: ยกเว้นได้เฉพาะอายุ 65 ปีขึ้นไป',
      },
    ],
  },
  {
    key: 'insurance',
    title: 'ประกัน',
    groupCapId: 'life_health',
    fields: [
      { key: 'lifeInsuranceAllowance', label: 'ประกันชีวิต', capCategory: 'life_insurance', kind: 'money' },
      { key: 'healthInsuranceAllowance', label: 'ประกันสุขภาพ', capCategory: 'health_insurance', kind: 'money' },
      { key: 'parentHealthInsuranceAllowance', label: 'ประกันสุขภาพบิดามารดา', capCategory: 'parent_health_insurance', kind: 'money' },
    ],
  },
  {
    key: 'savings',
    title: 'การออมและการลงทุน',
    groupCapId: 'retirement',
    fields: [
      { key: 'rmfAllowance', label: 'กองทุนรวมเพื่อการเลี้ยงชีพ (RMF)', capCategory: 'rmf', kind: 'money' },
      { key: 'ssfAllowance', label: 'กองทุนรวมเพื่อการออม (SSF)', capCategory: 'ssf', kind: 'money' },
      { key: 'pensionInsuranceAllowance', label: 'ประกันชีวิตแบบบำนาญ', capCategory: 'pension', kind: 'money' },
      { key: 'thaiEsgAllowance', label: 'กองทุนรวมไทยเพื่อความยั่งยืน (Thai ESG)', capCategory: 'thai_esg', kind: 'money' },
    ],
  },
  {
    key: 'housing',
    title: 'ที่อยู่อาศัย',
    fields: [
      { key: 'homeLoanInterestAllowance', label: 'ดอกเบี้ยเงินกู้ยืมเพื่อที่อยู่อาศัย', capCategory: 'home_loan_interest', kind: 'money' },
    ],
  },
  {
    key: 'donation',
    title: 'เงินบริจาค',
    fields: [
      { key: 'educationDonation', label: 'เงินบริจาคสถานศึกษา/กีฬา (หักได้ 2 เท่า)', capCategory: 'education_donation', kind: 'money' },
      { key: 'generalDonation', label: 'เงินบริจาคทั่วไป', capCategory: 'general_donation', kind: 'money' },
      {
        key: 'politicalDonation',
        label: 'เงินบริจาคพรรคการเมือง',
        capCategory: 'political_donation',
        kind: 'money',
        hint: 'แยกวงเงินต่างหาก ไม่รวมกับเพดานเงินบริจาคด้านบน',
      },
    ],
  },
];

// Rows granted automatically — never declared, never editable (decision #1 / issue #387).
// `capCategory: null` (sso) means there is no `/caps` row for it at all; it is shown as a plain
// label with no figure, since inventing one here would be exactly the "hardcoded Thai tax number"
// the issue forbids.
export const AUTO_GRANTED_ROWS = [
  { key: 'personal', label: 'ส่วนตัว', capCategory: 'personal' },
  { key: 'sso', label: 'ประกันสังคม (SSO)', capCategory: null, note: 'หักตามฐานเงินเดือนและอัตราที่กฎหมายกำหนดโดยอัตโนมัติ' },
];

export function defaultAllowanceValues(declaration) {
  const source = declaration?.allowances || {};
  const values = {};
  for (const key of ALLOWANCE_MONEY_KEYS) values[key] = Number(source[key] || 0);
  for (const key of ALLOWANCE_COUNT_KEYS) values[key] = Number(source[key] || 0);
  values.disabilityCardHolder = Boolean(source.disabilityCardHolder);
  values.documentReference = declaration?.documentReference || '';
  return values;
}

export function emptyAllowanceValues() {
  return defaultAllowanceValues(null);
}

/**
 * Builds the exact body `POST .../declarations/me` (or `/on-behalf`, plus `employeeId` added by
 * the caller) expects — never includes `employeeId` here, per the self-service contract.
 */
/** Sum of every declared baht amount — display-only (a total-so-far figure the employee/HR typed
 * in, not a capped or tax-adjusted figure). Never used as an input to the estimate call. */
export function declaredAllowanceTotal(declaration) {
  const source = declaration?.allowances;
  if (!source) return 0;
  return ALLOWANCE_MONEY_KEYS.reduce((sum, key) => sum + Number(source[key] || 0), 0);
}

export function buildAllowanceSubmitBody(values, { taxYear, effectiveMonth, documentReference }) {
  const body = {
    taxYear,
    effectiveMonth: effectiveMonth || null,
    documentReference: documentReference?.trim() || values.documentReference?.trim() || null,
  };
  for (const key of ALLOWANCE_MONEY_KEYS) body[key] = Number(values[key] || 0);
  for (const key of ALLOWANCE_COUNT_KEYS) body[key] = Number(values[key] || 0);
  body.disabilityCardHolder = Boolean(values.disabilityCardHolder);
  return body;
}
