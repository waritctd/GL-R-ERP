// Shared field/group metadata for the ล.ย.01 tax-allowance declaration (issue #387). `capCategory` links a
// field to its row in `GET /api/payroll/tax-allowances/caps` — every cap number shown next to a field must
// come from that response, never a literal here (CLAUDE.md / issue #387 decision #1).

// Official Revenue Department (rd.go.th) sources cited by `lawRef` below. If a future task needs a source not
// listed here, verify it first (curl) and say so; do not guess a rd.go.th URL onto a tax form. CAVEAT: not
// per-type — do not label it as specific to any one allowance.
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

// Genuine exceptions to "every field has a lawRef" go here, keyed by field key, with a written reason —
// mirrors contract.test.js's KNOWN_GAPS/ARITY_EXEMPTIONS pattern.
export const LAW_REF_EXEMPTIONS = {};

// THE BACKEND CONTRACT — unchanged by the ล.ย.01 restructure. These key lists are the wire shape `POST
// .../declarations/me` expects (see TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationSubmitRequest).
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

const ALLOWANCE_COUNT_KEYS = [
  'childCount',
  'childCountDouble',
  'disabledCareCount',
  'parentCareCount',
];

const ALLOWANCE_CHECKBOX_KEYS = ['disabilityCardHolder'];

export const ALL_ALLOWANCE_KEYS = [...ALLOWANCE_MONEY_KEYS, ...ALLOWANCE_COUNT_KEYS, ...ALLOWANCE_CHECKBOX_KEYS];

/** Fields the FORM never asks for, because แบบ ล.ย.01 does not print them — HR supplies them at review through
 * the existing HR tax-allowance editor. `spouseAllowance` is the interesting one: the form asks only for
 * สถานภาพ (ข้อ 1) and whether the spouse has income (ข้อ 2), never a baht figure. */
export const HR_SUPPLIED_KEYS = ['spouseAllowance', 'disabilityCardHolder'];

/** The nested `lorYor01` payload's own keys — everything on the form that is not an amount. */
const LOR_YOR_01_DETAIL_KEYS = [
  'taxpayerId', 'firstNameTh', 'lastNameTh',
  'maritalState', 'spousalStatus', 'spouseHasIncome',
  'childrenTotal', 'childExtraAllowance',
  'ownFatherSupported', 'ownMotherSupported',
  'spouseFatherSupported', 'spouseMotherSupported', 'spouseParentCareAllowance',
  'ownFatherHealthInsured', 'ownMotherHealthInsured',
  'spouseFatherHealthInsured', 'spouseMotherHealthInsured',
  'providentFundAllowance', 'rmfSellerName', 'otherDonationNote',
];

/** The eight ข้อ 4 / ข้อ 6 ticks. */
export const LOR_YOR_01_TICK_KEYS = [
  'ownFatherSupported', 'ownMotherSupported', 'spouseFatherSupported', 'spouseMotherSupported',
  'ownFatherHealthInsured', 'ownMotherHealthInsured',
  'spouseFatherHealthInsured', 'spouseMotherHealthInsured',
];

export const LOR_YOR_01_ADDRESS_KEYS = [
  'building', 'roomNo', 'floor', 'village', 'houseNo', 'moo', 'soi', 'junction', 'road',
  'subDistrict', 'district', 'province', 'postalCode',
];

/** The general/uncategorised evidence bucket, kept for evidence that belongs to no single ข้อ. */
export const UNCATEGORIZED_EVIDENCE_KEY = '__uncategorized';

/** Attachment bucket for the signed, scanned form. Its own key rather than a file in the general bucket (owner
 * decision #3) so that "has the employee signed this?" is answerable without opening files — the page gates
 * submit on it and the backend refuses to approve without it. */
export const SIGNED_FORM_EVIDENCE_KEY = 'signed_form';

export const MARITAL_STATE_OPTIONS = [
  { value: 'SINGLE', label: 'โสด' },
  { value: 'WIDOWED', label: 'หม้าย' },
  { value: 'MARRIED', label: 'สมรส' },
  { value: 'DIED_DURING_YEAR', label: 'ตายระหว่างปีภาษี' },
];

export const SPOUSAL_STATUS_OPTIONS = [
  { value: 'MARRIED_WHOLE_YEAR', label: 'สมรสและอยู่ร่วมกันตลอดปีภาษี' },
  { value: 'MARRIED_DURING_YEAR', label: 'สมรสระหว่างปีภาษี' },
  { value: 'DIVORCED_DURING_YEAR', label: 'หย่าระหว่างปีภาษี' },
  { value: 'DIED_DURING_YEAR', label: 'ตายระหว่างปีภาษี' },
];

// THE FORM — one section per ข้อ, in the government form's own order and wording. The 2562 form carries
// superseded numbers (ข้อ 7 prints ฿10,000; the current life-insurance limit is ฿100,000), and this file's own
// header explains why an unsourced or stale Thai tax number must not appear here.
export const LOR_YOR_01_SECTIONS = [
  {
    key: 'identity',
    no: null,
    title: 'ข้อมูลผู้มีเงินได้',
    subtitle: 'ชื่อ เลขประจำตัวผู้เสียภาษีอากร และที่อยู่ตามแบบ ล.ย.01',
    kind: 'identity',
    fields: [],
  },
  {
    key: 'status',
    no: '1–2',
    title: 'สถานภาพ และสถานะการมีเงินได้ของคู่สมรส',
    kind: 'status',
    fields: [],
  },
  {
    key: 'item3',
    no: '3',
    title: 'บุตร',
    fields: [
      { key: 'lorYor01.childrenTotal', label: 'จำนวนบุตรรวม', kind: 'count', unit: 'คน', max: 9, lawRef: LAW_SOURCES.section47 },
      { key: 'childCount', label: 'บุตร คนละ 30,000 บาท — มีสิทธินำมาหักลดหย่อนจำนวน', capCategory: 'child', kind: 'count', unit: 'คน', max: 9, lawRef: LAW_SOURCES.section47 },
      { key: 'childAllowance', label: 'จำนวนเงิน (บุตร คนละ 30,000 บาท)', capCategory: 'child', kind: 'money', lawRef: LAW_SOURCES.section47 },
      { key: 'childCountDouble', label: 'บุตรตั้งแต่คนที่สองที่เกิดในหรือหลัง พ.ศ. 2561 คนละ 60,000 บาท — จำนวน', capCategory: 'child_double', kind: 'count', unit: 'คน', max: 9, lawRef: LAW_SOURCES.section47 },
      { key: 'lorYor01.childExtraAllowance', label: 'จำนวนเงิน (บุตร คนละ 60,000 บาท)', capCategory: 'child_double', kind: 'money', lawRef: LAW_SOURCES.section47 },
    ],
  },
  {
    key: 'item4',
    no: '4',
    title: 'ค่าอุปการะเลี้ยงดูบิดามารดา',
    fields: [
      { key: 'lorYor01.ownFatherSupported', label: 'บิดา (ของผู้มีเงินได้)', kind: 'checkbox', lawRef: LAW_SOURCES.section47 },
      { key: 'lorYor01.ownMotherSupported', label: 'มารดา (ของผู้มีเงินได้)', kind: 'checkbox', lawRef: LAW_SOURCES.section47 },
      { key: 'parentCareAllowance', label: 'จำนวนเงิน (ของผู้มีเงินได้)', capCategory: 'parent_care', kind: 'money', lawRef: LAW_SOURCES.section47 },
      { key: 'lorYor01.spouseFatherSupported', label: 'บิดา (ของคู่สมรสที่ไม่มีเงินได้)', kind: 'checkbox', lawRef: LAW_SOURCES.section47 },
      { key: 'lorYor01.spouseMotherSupported', label: 'มารดา (ของคู่สมรสที่ไม่มีเงินได้)', kind: 'checkbox', lawRef: LAW_SOURCES.section47 },
      { key: 'lorYor01.spouseParentCareAllowance', label: 'จำนวนเงิน (ของคู่สมรสที่ไม่มีเงินได้)', capCategory: 'parent_care', kind: 'money', lawRef: LAW_SOURCES.section47 },
    ],
  },
  {
    key: 'item5',
    no: '5',
    title: 'ค่าอุปการะเลี้ยงดูคนพิการหรือคนทุพพลภาพ',
    fields: [
      { key: 'disabledCareCount', label: 'รวมทั้งสิ้น', capCategory: 'disabled_care', kind: 'count', unit: 'คน', max: 9, lawRef: LAW_SOURCES.section47 },
      { key: 'disabledCareAllowance', label: 'จำนวนเงิน', capCategory: 'disabled_care', kind: 'money', lawRef: LAW_SOURCES.section47 },
    ],
  },
  {
    key: 'item6',
    no: '6',
    title: 'เบี้ยประกันสุขภาพบิดามารดา',
    fields: [
      { key: 'lorYor01.ownFatherHealthInsured', label: 'บิดา (ของผู้มีเงินได้)', kind: 'checkbox', lawRef: LAW_SOURCES.faq },
      { key: 'lorYor01.ownMotherHealthInsured', label: 'มารดา (ของผู้มีเงินได้)', kind: 'checkbox', lawRef: LAW_SOURCES.faq },
      { key: 'lorYor01.spouseFatherHealthInsured', label: 'บิดา (ของคู่สมรสที่ไม่มีเงินได้)', kind: 'checkbox', lawRef: LAW_SOURCES.faq },
      { key: 'lorYor01.spouseMotherHealthInsured', label: 'มารดา (ของคู่สมรสที่ไม่มีเงินได้)', kind: 'checkbox', lawRef: LAW_SOURCES.faq },
      { key: 'parentHealthInsuranceAllowance', label: 'จำนวนเงินรวม', capCategory: 'parent_health_insurance', kind: 'money', lawRef: LAW_SOURCES.faq },
    ],
  },
  {
    key: 'item7',
    no: '7',
    title: 'เบี้ยประกันชีวิตที่จ่ายภายในปีภาษี',
    fields: [
      { key: 'lifeInsuranceAllowance', label: 'จำนวนเงิน', capCategory: 'life_insurance', kind: 'money', lawRef: LAW_SOURCES.section47 },
    ],
  },
  {
    key: 'item8',
    no: '8',
    title: 'เบี้ยประกันสุขภาพที่จ่ายภายในปีภาษี',
    fields: [
      { key: 'healthInsuranceAllowance', label: 'จำนวนเงิน', capCategory: 'health_insurance', kind: 'money', lawRef: LAW_SOURCES.faq },
    ],
  },
  {
    key: 'item9',
    no: '9',
    title: 'เงินสะสมที่จ่ายเข้ากองทุนสำรองเลี้ยงชีพ หรือกองทุนการออมแห่งชาติ หรือกองทุน กบข. หรือกองทุนสงเคราะห์ครูโรงเรียนเอกชน',
    fields: [
      {
        key: 'lorYor01.providentFundAllowance',
        label: 'จำนวนเงิน',
        capCategory: 'retirement',
        kind: 'money',
        hint: 'บริษัทไม่มีกองทุนสำรองเลี้ยงชีพ — ช่องนี้สำหรับผู้ที่เป็นสมาชิกกองทุนการออมแห่งชาติ (กอช.) ด้วยตนเอง',
        lawRef: LAW_SOURCES.faq,
      },
    ],
  },
  {
    key: 'item10',
    no: '10',
    title: 'ค่าซื้อหน่วยลงทุนในกองทุนรวมเพื่อการเลี้ยงชีพ (RMF)',
    fields: [
      { key: 'rmfAllowance', label: 'จำนวนเงิน', capCategory: 'rmf', kind: 'money', lawRef: LAW_SOURCES.rmfSsf },
      { key: 'lorYor01.rmfSellerName', label: 'ชื่อผู้ขายหน่วยลงทุน', kind: 'text', lawRef: LAW_SOURCES.rmfSsf },
    ],
  },
  {
    key: 'item11',
    no: '11',
    title: 'ค่าซื้อหน่วยลงทุนในกองทุนรวมหุ้นระยะยาว (LTF)',
    // Printed on the form, never fillable: LTF stopped being deductible after tax year 2019, so there is nothing
    // an employee could truthfully declare and no column behind it (V137).
    kind: 'retired',
    note: 'กองทุนรวมหุ้นระยะยาว (LTF) สิ้นสุดสิทธิลดหย่อนตั้งแต่ปีภาษี 2563 เป็นต้นไป จึงไม่สามารถกรอกได้ และจะเว้นว่างไว้ในแบบที่พิมพ์ออกมา',
    fields: [],
  },
  {
    key: 'item12',
    no: '12',
    title: 'ดอกเบี้ยเงินกู้ยืมเพื่อซื้อ เช่าซื้อ หรือสร้างอาคารที่อยู่อาศัย',
    fields: [
      { key: 'homeLoanInterestAllowance', label: 'จำนวนเงิน', capCategory: 'home_loan_interest', kind: 'money', lawRef: LAW_SOURCES.section47 },
    ],
  },
  {
    key: 'item13',
    no: '13',
    title: 'เงินสมทบกองทุนประกันสังคมภายในปีภาษี',
    // Derived, never typed: PayrollRepository#sumSocialSecurityForTaxYear sums what payroll actually recorded. A
    // form filed in January legitimately shows nothing here yet.
    kind: 'derived',
    note: 'คำนวณอัตโนมัติจากยอดเงินสมทบที่หักไว้จริงในปีภาษีนี้ ไม่ต้องกรอกเอง',
    fields: [],
  },
  {
    key: 'item14',
    no: '14',
    title: 'เงินบริจาคสนับสนุนการศึกษา',
    fields: [
      { key: 'educationDonation', label: 'จำนวนเงิน', capCategory: 'education_donation', kind: 'money', lawRef: LAW_SOURCES.section47 },
    ],
  },
  {
    key: 'item15',
    no: '15',
    title: 'เงินบริจาคอื่น ๆ',
    fields: [
      { key: 'lorYor01.otherDonationNote', label: 'ระบุ', kind: 'text', lawRef: LAW_SOURCES.section47 },
      { key: 'generalDonation', label: 'จำนวนเงิน', capCategory: 'general_donation', kind: 'money', lawRef: LAW_SOURCES.section47 },
    ],
  },
];

/** Rows granted automatically — never declared, never editable. */
export const AUTO_GRANTED_ROWS = [
  { key: 'personal', label: 'ส่วนตัว', capCategory: 'personal', lawRef: LAW_SOURCES.section47 },
];

/** Every field/row this schema expects to carry a `lawRef`, flattened to one list — the single source of truth
 * for taxAllowanceSchema.test.js, so a field added to a section is automatically covered with no second list
 * to remember. */
export function allLawReferencedEntries() {
  return [...LOR_YOR_01_SECTIONS.flatMap((section) => section.fields), ...AUTO_GRANTED_ROWS];
}

/** Reads a possibly-nested form key ("lorYor01.rmfSellerName" or "childAllowance"). */
export function readFieldValue(values, key) {
  if (!values) return undefined;
  if (!key.includes('.')) return values[key];
  const [outer, inner] = key.split('.');
  return values[outer]?.[inner];
}

/** Whether the employee has entered anything for this ข้อ yet — drives the collapsed row's state. */
export function sectionHasValue(section, values) {
  return section.fields.some((field) => {
    const value = readFieldValue(values, field.key);
    if (field.kind === 'checkbox') return Boolean(value);
    if (field.kind === 'text') return Boolean(value && String(value).trim());
    return Number(value || 0) > 0;
  });
}

/** Sum of just this ข้อ's money fields — the subtotal on its collapsed row. */
export function sectionDeclaredTotal(section, values) {
  return section.fields
    .filter((field) => field.kind === 'money')
    .reduce((sum, field) => sum + Number(readFieldValue(values, field.key) || 0), 0);
}

/** The header slots `GET /declarations/me`'s `headerPrefill` can seed — owner decision #4's read half. Only
 * identity is knowable in advance. */
const LOR_YOR_01_PREFILLABLE_DETAIL_KEYS = [
  'taxpayerId', 'firstNameTh', 'lastNameTh', 'maritalState',
];

/** Empty string, whitespace and null are all "the employee has not answered this". */
const isBlank = (value) => value == null || String(value).trim() === '';

/** ONE rule, applied to every prefillable slot: what the employee already put there wins. Letting the master
 * win would silently revert a correction the employee is in the middle of making — the exact opposite of
 * decision #4, where corrections flow employee → master on approval, never back. */
const seeded = (declared, offered) => (isBlank(declared) ? (offered ?? '') : declared);

/** Selects affected frontend tests, including readers outside the import graph. */
export function defaultAllowanceValues(declaration, headerPrefill = null) {
  const source = declaration?.allowances || {};
  const detail = declaration?.lorYor01 || {};
  const prefill = headerPrefill || {};
  const values = {};
  for (const key of ALLOWANCE_MONEY_KEYS) values[key] = Number(source[key] || 0);
  for (const key of ALLOWANCE_COUNT_KEYS) values[key] = Number(source[key] || 0);
  values.disabilityCardHolder = Boolean(source.disabilityCardHolder);
  values.documentReference = declaration?.documentReference || '';
  values.lorYor01 = {};
  for (const key of LOR_YOR_01_DETAIL_KEYS) {
    if (LOR_YOR_01_TICK_KEYS.includes(key)) {
      values.lorYor01[key] = Boolean(detail[key]);
    } else if (LOR_YOR_01_PREFILLABLE_DETAIL_KEYS.includes(key)) {
      values.lorYor01[key] = seeded(detail[key], prefill[key]);
    } else {
      values.lorYor01[key] = detail[key] ?? '';
    }
  }
  values.lorYor01.address = {};
  for (const key of LOR_YOR_01_ADDRESS_KEYS) {
    values.lorYor01.address[key] = seeded(detail.address?.[key], prefill.address?.[key]);
  }
  return values;
}

export function emptyAllowanceValues() {
  return defaultAllowanceValues(null);
}

/** Sum of every declared baht amount — display only, never an input to the estimate call. */
export function declaredAllowanceTotal(declaration) {
  const source = declaration?.allowances;
  if (!source) return 0;
  return ALLOWANCE_MONEY_KEYS.reduce((sum, key) => sum + Number(source[key] || 0), 0);
}

/** Same figure from a live react-hook-form `values`, while the employee is still typing. */
export function declaredAllowanceTotalFromValues(values) {
  if (!values) return 0;
  const amounts = ALLOWANCE_MONEY_KEYS.reduce((sum, key) => sum + Number(values[key] || 0), 0);
  const extra = Number(values?.lorYor01?.childExtraAllowance || 0)
    + Number(values?.lorYor01?.spouseParentCareAllowance || 0)
    + Number(values?.lorYor01?.providentFundAllowance || 0);
  return amounts + extra;
}

const blank = (value) => {
  const trimmed = typeof value === 'string' ? value.trim() : value;
  return trimmed === '' || trimmed === undefined ? null : trimmed;
};

/** Builds the exact body `POST .../declarations/me` expects — never includes `employeeId`. The five off-form
 * amounts submit as 0 by construction: they are in ALLOWANCE_MONEY_KEYS (the wire shape) but have no control,
 * so `values` never carries them. */
export function buildAllowanceSubmitBody(values, { taxYear, documentReference }) {
  const body = {
    taxYear,
    documentReference: documentReference?.trim() || values.documentReference?.trim() || null,
  };
  for (const key of ALLOWANCE_MONEY_KEYS) body[key] = Number(values[key] || 0);
  for (const key of ALLOWANCE_COUNT_KEYS) body[key] = Number(values[key] || 0);
  body.disabilityCardHolder = Boolean(values.disabilityCardHolder);

  const detail = values.lorYor01 || {};
  body.lorYor01 = {
    taxpayerId: blank(detail.taxpayerId),
    firstNameTh: blank(detail.firstNameTh),
    lastNameTh: blank(detail.lastNameTh),
    address: LOR_YOR_01_ADDRESS_KEYS.reduce((acc, key) => {
      acc[key] = blank(detail.address?.[key]);
      return acc;
    }, {}),
    maritalState: blank(detail.maritalState),
    spousalStatus: blank(detail.spousalStatus),
    // A tri-state on the wire: null means neither ข้อ 2 box was ticked, which is different from "ticked
    // ไม่มีเงินได้". The radio group stores '' / 'true' / 'false' as strings.
    spouseHasIncome: detail.spouseHasIncome === '' || detail.spouseHasIncome == null
      ? null
      : detail.spouseHasIncome === true || detail.spouseHasIncome === 'true',
    childrenTotal: Number(detail.childrenTotal || 0) || null,
    childExtraAllowance: Number(detail.childExtraAllowance || 0),
    ownFatherSupported: Boolean(detail.ownFatherSupported),
    ownMotherSupported: Boolean(detail.ownMotherSupported),
    spouseFatherSupported: Boolean(detail.spouseFatherSupported),
    spouseMotherSupported: Boolean(detail.spouseMotherSupported),
    spouseParentCareAllowance: Number(detail.spouseParentCareAllowance || 0),
    ownFatherHealthInsured: Boolean(detail.ownFatherHealthInsured),
    ownMotherHealthInsured: Boolean(detail.ownMotherHealthInsured),
    spouseFatherHealthInsured: Boolean(detail.spouseFatherHealthInsured),
    spouseMotherHealthInsured: Boolean(detail.spouseMotherHealthInsured),
    providentFundAllowance: Number(detail.providentFundAllowance || 0),
    rmfSellerName: blank(detail.rmfSellerName),
    otherDonationNote: blank(detail.otherDonationNote),
  };
  return body;
}
