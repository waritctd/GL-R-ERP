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

// ---------------------------------------------------------------------------------------------
// Official Revenue Department (rd.go.th) sources cited by `lawRef` below.
//
// Every URL here was verified by hand (curl, HTTP 200) before this file was written. Do NOT add a
// new entry, and do NOT change what an existing entry's `label`/`what` claims, without re-verifying
// against the real page yourself — a broken or misdescribed link on a legal citation is the same
// "label asserts something the code doesn't do" defect class this repo has spent a session removing
// elsewhere. If a future task needs a source not listed here, verify it first (curl) and say so; do
// not guess a rd.go.th URL onto a tax form.
//
// What each source genuinely is, and the caveat that makes it easy to mislabel:
//
//  - 5937.html (`section47`) — ประมวลรัษฎากร มาตรา 47, the permanent statutory basis for the core
//    allowances (ส่วนตัว, คู่สมรส, บุตร, บิดามารดา, คนพิการ, ประกันชีวิต, ดอกเบี้ยเงินกู้, เงินบริจาค,
//    ประกันสังคม). CAVEAT: the statute's own text carries SUPERSEDED figures (e.g. life insurance
//    ฿10,000) — current limits come from later ministerial regulations. Never present this as the
//    source of a current number; `label` stays the bare section name, which claims nothing about
//    currency.
//  - 62777.html (`faq`) — "Clear Cut ประเด็นเด็ดเกร็ดลดหย่อน", RD's FAQ covering every allowance type
//    in one page. CAVEAT: not per-type — do not label it as specific to any one allowance. Used
//    below only as the fallback for fields with no more specific verified source.
//  - 65908.html (`yearSummary`) — RD's current-year summary, "ลดหย่อนภาษี ปี 2568". CAVEAT: content
//    is a JPG, and the URL itself is year-stamped and will move — label carries its year so a reader
//    can see that without clicking.
//  - 63475.html (`rmfSsf`) — RD page for RMF and SSF. CAVEAT: it is about *data submission* by asset
//    managers, not the deduction rules — label says "ข้อมูล", never "หลักเกณฑ์".
//  - 65911.html (`thaiEsg`) — RD page for Thai ESG. Same data-submission caveat as rmfSsf.
//  - fileadmin/.../loryor01_290362.pdf (`formPdf`) — the official ล.ย.01 form PDF, 2562/2019
//    revision. Not tied to any single field; listed in the references section as the primary paper
//    document this screen replaces.
//
// Deliberately NOT used: https://www.rd.go.th/60058.html. It looks tailored for the insurance
// group but is stamped ปีภาษี 2560 (2017) — stale figures behind a current tax form, exactly the
// defect class this feature exists to remove. Do not re-add it without independently verifying it
// carries current figures.
//
// Field → source mapping below follows the table above literally rather than re-deriving from
// general Thai tax knowledge: only the allowance types the table names as covered by มาตรา 47 point
// at `section47`. Fields not in that list (self/parent health insurance, pension life insurance,
// ค่าฝากครรภ์และคลอดบุตร) fall back to `faq`, per "where no genuinely specific source exists, point
// at the FAQ and label it as the general one" — not stretched onto มาตรา 47 or onto the RMF/SSF page
// on the strength of a thematic resemblance alone.
export const LAW_SOURCES = {
  section47: {
    url: 'https://www.rd.go.th/5937.html',
    label: 'ประมวลรัษฎากร มาตรา 47',
    what: 'ฐานกฎหมายถาวรของค่าลดหย่อนหลัก — ส่วนตัว คู่สมรส บุตร บิดามารดา คนพิการ ประกันชีวิต ดอกเบี้ยเงินกู้ยืมเพื่อที่อยู่อาศัย เงินบริจาค และประกันสังคม',
    vintage: 'ตัวบทกฎหมาย (ไม่ระบุปี)',
    caveat: 'ตัวเลขในตัวบทมาตรานี้ล้าสมัยแล้ว (เช่น ค่าเบี้ยประกันชีวิต 10,000 บาท) วงเงินที่ใช้จริงในปัจจุบันมาจากกฎกระทรวง/ประกาศฉบับหลัง ไม่ใช่ตัวเลขในหน้านี้ — อย่านำตัวเลขจากหน้านี้ไปอ้างอิงเป็นวงเงินปัจจุบัน',
  },
  faq: {
    url: 'https://www.rd.go.th/62777.html',
    label: 'Clear Cut ประเด็นเด็ดเกร็ดลดหย่อน',
    what: 'หน้าคำถามที่พบบ่อยของกรมสรรพากร ครอบคลุมค่าลดหย่อนภาษีทุกประเภทในหน้าเดียว',
    vintage: null,
    caveat: 'เป็นภาพรวมทุกประเภทรวมกัน ไม่ได้เจาะจงรายการใดรายการหนึ่งโดยเฉพาะ',
  },
  yearSummary: {
    url: 'https://www.rd.go.th/65908.html',
    label: 'สรุปลดหย่อนภาษี ปี 2568',
    what: 'สรุปค่าลดหย่อนภาษีเงินได้บุคคลธรรมดาประจำปีภาษี 2568 ของกรมสรรพากร (เนื้อหาเป็นไฟล์ภาพ)',
    vintage: 'ปีภาษี 2568',
    caveat: 'URL มีปี พ.ศ. ผูกอยู่ในลิงก์ และจะเปลี่ยนไปทุกปี — ลิงก์นี้อาจใช้ไม่ได้อีกต่อไปในปีภาษีถัดไป',
  },
  rmfSsf: {
    url: 'https://www.rd.go.th/63475.html',
    label: 'ข้อมูล RMF/SSF',
    what: 'หน้ากรมสรรพากรเกี่ยวกับการนำส่งข้อมูลการซื้อกองทุน RMF/SSF โดยบริษัทหลักทรัพย์/บริษัทจัดการกองทุน',
    vintage: null,
    caveat: 'เป็นหน้าเกี่ยวกับการนำส่งข้อมูลโดยผู้จัดการกองทุน ไม่ใช่หน้าอธิบายหลักเกณฑ์การลดหย่อนภาษีของ RMF/SSF โดยตรง',
  },
  thaiEsg: {
    url: 'https://www.rd.go.th/65911.html',
    label: 'ข้อมูลกองทุน Thai ESG',
    what: 'หน้ากรมสรรพากรเกี่ยวกับการนำส่งข้อมูลกองทุนรวมไทยเพื่อความยั่งยืน (Thai ESG)',
    vintage: null,
    caveat: 'เช่นเดียวกับหน้า RMF/SSF — เกี่ยวกับการนำส่งข้อมูล ไม่ใช่หลักเกณฑ์การลดหย่อนภาษีโดยตรง',
  },
  formPdf: {
    url: 'https://www.rd.go.th/fileadmin/tax_pdf/withhold/loryor01_290362.pdf',
    label: 'แบบ ล.ย.01 ฉบับ พ.ศ. 2562',
    what: 'แบบฟอร์ม ล.ย.01 (แจ้งรายการเพื่อการหักลดหย่อน) ต้นฉบับ PDF จากกรมสรรพากร ที่หน้าจอนี้แทนที่ในรูปแบบดิจิทัล',
    vintage: 'ฉบับ พ.ศ. 2562',
    caveat: null,
  },
};

// Genuine exceptions to "every field has a lawRef" go here, keyed by field key, with a written
// reason — mirrors contract.test.js's KNOWN_GAPS/ARITY_EXEMPTIONS pattern (CLAUDE.md "Mock API
// contract": "genuine exceptions go in that file's KNOWN_GAPS / ARITY_EXEMPTIONS with a written
// reason, not a silent skip"). Empty today: every field below maps to a real LAW_SOURCES entry,
// falling back to `faq` where no more specific verified page exists — see the mapping comment
// above LAW_SOURCES for which fields that applies to and why.
export const LAW_REF_EXEMPTIONS = {};

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

// Shared between TaxAllowancePage.jsx (staged-evidence bucket key) and TaxAllowanceForm.jsx
// (reading that bucket for step 1's general/uncategorized evidence list) -- one definition so the
// two can't silently drift apart (review #tax-allowance-sections).
export const UNCATEGORIZED_EVIDENCE_KEY = '__uncategorized';

// Field order/grouping mirrors the issue's screen 1 layout exactly: ครอบครัว → ประกัน →
// การออมและการลงทุน → ที่อยู่อาศัย → เงินบริจาค.
export const TAX_ALLOWANCE_GROUPS = [
  {
    key: 'family',
    title: 'ครอบครัว',
    fields: [
      { key: 'spouseAllowance', label: 'คู่สมรส (ไม่มีเงินได้)', capCategory: 'spouse', kind: 'money', lawRef: LAW_SOURCES.section47 },
      { key: 'childAllowance', label: 'บุตร', capCategory: 'child', kind: 'money', lawRef: LAW_SOURCES.section47 },
      { key: 'childCount', label: 'จำนวนบุตร', capCategory: 'child', kind: 'count', unit: 'คน', lawRef: LAW_SOURCES.section47 },
      {
        key: 'childCountDouble',
        label: 'จำนวนบุตรที่ได้สิทธิ 2 เท่า',
        capCategory: 'child_double',
        kind: 'count',
        unit: 'คน',
        hint: 'บุตรคนที่ 2 เป็นต้นไปที่เกิดตั้งแต่ พ.ศ. 2561 ได้รับสิทธิเพิ่มอีกหัวละ 30,000 บาท',
        lawRef: LAW_SOURCES.section47,
      },
      { key: 'parentCareAllowance', label: 'อุปการะเลี้ยงดูบิดามารดา', capCategory: 'parent_care', kind: 'money', lawRef: LAW_SOURCES.section47 },
      { key: 'parentCareCount', label: 'จำนวนบิดามารดาที่อุปการะ', capCategory: 'parent_care', kind: 'count', unit: 'คน', lawRef: LAW_SOURCES.section47 },
      { key: 'disabledCareAllowance', label: 'อุปการะเลี้ยงดูคนพิการ/ทุพพลภาพ', capCategory: 'disabled_care', kind: 'money', lawRef: LAW_SOURCES.section47 },
      { key: 'disabledCareCount', label: 'จำนวนคนพิการ/ทุพพลภาพที่อุปการะ', capCategory: 'disabled_care', kind: 'count', unit: 'คน', lawRef: LAW_SOURCES.section47 },
      // ค่าฝากครรภ์และคลอดบุตร is not in ม.47's own named list (task research) -- FAQ, not section47.
      { key: 'maternityAllowance', label: 'ค่าฝากครรภ์และคลอดบุตร', capCategory: 'maternity', kind: 'money', lawRef: LAW_SOURCES.faq },
      {
        key: 'disabilityCardHolder',
        label: 'ผู้พิการที่อุปการะมีบัตรประจำตัวคนพิการ',
        kind: 'checkbox',
        hint: 'มีบัตร: ยกเว้นเงินได้ได้ทุกช่วงอายุ · ไม่มีบัตร: ยกเว้นได้เฉพาะอายุ 65 ปีขึ้นไป',
        // A qualifying condition of the SAME "คนพิการ" line item above, not a separate allowance --
        // shares that field's source rather than inventing a more specific one.
        lawRef: LAW_SOURCES.section47,
      },
    ],
  },
  {
    key: 'insurance',
    title: 'ประกัน',
    groupCapId: 'life_health',
    fields: [
      { key: 'lifeInsuranceAllowance', label: 'ประกันชีวิต', capCategory: 'life_insurance', kind: 'money', lawRef: LAW_SOURCES.section47 },
      // Self/parent health insurance are NOT named in ม.47's own list (task research) -- FAQ, not
      // section47, even though they sit in the same "ประกัน" group as life insurance above.
      { key: 'healthInsuranceAllowance', label: 'ประกันสุขภาพ', capCategory: 'health_insurance', kind: 'money', lawRef: LAW_SOURCES.faq },
      { key: 'parentHealthInsuranceAllowance', label: 'ประกันสุขภาพบิดามารดา', capCategory: 'parent_health_insurance', kind: 'money', lawRef: LAW_SOURCES.faq },
    ],
  },
  {
    key: 'savings',
    title: 'การออมและการลงทุน',
    groupCapId: 'retirement',
    fields: [
      { key: 'rmfAllowance', label: 'กองทุนรวมเพื่อการเลี้ยงชีพ (RMF)', capCategory: 'rmf', kind: 'money', lawRef: LAW_SOURCES.rmfSsf },
      { key: 'ssfAllowance', label: 'กองทุนรวมเพื่อการออม (SSF)', capCategory: 'ssf', kind: 'money', lawRef: LAW_SOURCES.rmfSsf },
      // Pension life insurance has no dedicated verified source (unlike RMF/SSF and Thai ESG, which
      // "get their own" per the task) -- FAQ fallback, not stretched onto section47 or rmfSsf.
      { key: 'pensionInsuranceAllowance', label: 'ประกันชีวิตแบบบำนาญ', capCategory: 'pension', kind: 'money', lawRef: LAW_SOURCES.faq },
      { key: 'thaiEsgAllowance', label: 'กองทุนรวมไทยเพื่อความยั่งยืน (Thai ESG)', capCategory: 'thai_esg', kind: 'money', lawRef: LAW_SOURCES.thaiEsg },
    ],
  },
  {
    key: 'housing',
    title: 'ที่อยู่อาศัย',
    fields: [
      { key: 'homeLoanInterestAllowance', label: 'ดอกเบี้ยเงินกู้ยืมเพื่อที่อยู่อาศัย', capCategory: 'home_loan_interest', kind: 'money', lawRef: LAW_SOURCES.section47 },
    ],
  },
  {
    key: 'donation',
    title: 'เงินบริจาค',
    fields: [
      { key: 'educationDonation', label: 'เงินบริจาคสถานศึกษา/กีฬา (หักได้ 2 เท่า)', capCategory: 'education_donation', kind: 'money', lawRef: LAW_SOURCES.section47 },
      { key: 'generalDonation', label: 'เงินบริจาคทั่วไป', capCategory: 'general_donation', kind: 'money', lawRef: LAW_SOURCES.section47 },
      {
        key: 'politicalDonation',
        label: 'เงินบริจาคพรรคการเมือง',
        capCategory: 'political_donation',
        kind: 'money',
        hint: 'แยกวงเงินต่างหาก ไม่รวมกับเพดานเงินบริจาคด้านบน',
        lawRef: LAW_SOURCES.section47,
      },
    ],
  },
];

// Rows granted automatically — never declared, never editable (decision #1 / issue #387).
// `capCategory: null` (sso) means there is no `/caps` row for it at all; it is shown as a plain
// label with no figure, since inventing one here would be exactly the "hardcoded Thai tax number"
// the issue forbids.
export const AUTO_GRANTED_ROWS = [
  { key: 'personal', label: 'ส่วนตัว', capCategory: 'personal', lawRef: LAW_SOURCES.section47 },
  { key: 'sso', label: 'ประกันสังคม (SSO)', capCategory: null, note: 'หักตามฐานเงินเดือนและอัตราที่กฎหมายกำหนดโดยอัตโนมัติ', lawRef: LAW_SOURCES.section47 },
];

/**
 * Every field/row this schema expects to carry a `lawRef` — every TAX_ALLOWANCE_GROUPS field plus
 * every AUTO_GRANTED_ROWS row, flattened to one list. Single source of truth for
 * taxAllowanceSchema.test.js's "every field has a lawRef, or a written LAW_REF_EXEMPTIONS reason"
 * check, so a field added to a group is automatically covered by that test with no second list to
 * remember to update.
 */
export function allLawReferencedEntries() {
  return [...TAX_ALLOWANCE_GROUPS.flatMap((group) => group.fields), ...AUTO_GRANTED_ROWS];
}

/**
 * Whether the employee has entered anything for this GROUP's own fields yet — feeds the "กรอกแล้ว"
 * indicator on TaxAllowanceForm's step-1 section picker (progressive disclosure, #tax-allowance-
 * sections), so a returning user can see at a glance which of the five sections they have already
 * started.
 *
 * Deliberately its own helper, not a reuse of `computeGroupUsage` (taxAllowanceCaps.js):
 * `computeGroupUsage` answers "how much of a SHARED CEILING has been consumed" and only has an
 * opinion on the two groups that share one (life_health/retirement -- `groupCapId` on `insurance`/
 * `savings` below); it says nothing about `family`, `housing`, or `donation`, and is keyed by a
 * different id namespace (`groupId`, not `TAX_ALLOWANCE_GROUPS[].key`). This only asks "is any
 * field in this group non-default", for all five groups uniformly.
 */
export function groupHasValue(group, values) {
  if (!values) return false;
  return group.fields.some((field) => {
    const value = values[field.key];
    return field.kind === 'checkbox' ? Boolean(value) : Number(value || 0) > 0;
  });
}

/**
 * Sum of just this GROUP's `kind: 'money'` fields, read from live `values` — the hub row and
 * review-recap subtotal for one section (#tax-allowance-ia-hub-review). Sibling of `groupHasValue`
 * above, not a replacement for it: `groupHasValue` also counts a nonzero COUNT field or a checked
 * checkbox as "something is here" (used to decide whether to show a subtotal at all, vs the
 * `ไม่ได้ประกาศ` fallback), while this only totals money — a group whose only entry is e.g.
 * `childCount` with no corresponding `childAllowance` yet would have `groupHasValue` true and this
 * return 0, which is the honest answer to "how many baht has this group declared so far", not a bug.
 */
export function groupDeclaredTotal(group, values) {
  if (!values) return 0;
  return group.fields
    .filter((field) => field.kind === 'money')
    .reduce((sum, field) => sum + Number(values[field.key] || 0), 0);
}

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

/**
 * Same "sum of every declared baht amount" figure as `declaredAllowanceTotal` above, but reads a
 * live react-hook-form `values` object instead of a submitted `declaration` — the hub and review
 * views (#tax-allowance-ia-hub-review) need this total WHILE the employee is still filling the form
 * in, before there is any `declaration.allowances` to sum. Deliberately not a call to
 * `declaredAllowanceTotal({ allowances: values })`: that would happen to work today because the two
 * key sets line up, but it would keep silently working if they ever drifted apart instead of failing
 * loudly, and it reads backwards at the call site (building a fake declaration just to satisfy a
 * shape). Two helpers, two sources (`declaration` vs live `values`) — same reasoning as
 * `groupHasValue`/`groupDeclaredTotal` above for why neither is a call-through to the other.
 */
export function declaredAllowanceTotalFromValues(values) {
  if (!values) return 0;
  return ALLOWANCE_MONEY_KEYS.reduce((sum, key) => sum + Number(values[key] || 0), 0);
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
