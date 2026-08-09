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

// ---------------------------------------------------------------------------------------------
// THE BACKEND CONTRACT — unchanged by the ล.ย.01 restructure.
//
// These key lists are the wire shape `POST .../declarations/me` expects (see
// TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationSubmitRequest). They are deliberately NOT the
// same thing as "what the form shows": five of the money keys below are not on แบบ ล.ย.01 at all
// (ssf, thaiEsg, pensionInsurance, maternity, politicalDonation) and the form no longer collects
// them, but the columns still exist and the request still carries every component.
//
// ⚠️ CONSEQUENCE, accepted by the owner 2026-08-08: an amount the form does not collect submits as
// 0, and `apply` upserts all sixteen into employee_tax_allowance. An employee currently claiming
// SSF or Thai ESG loses it the first time they re-file. HR restores such a figure through the HR
// tax-allowance editor, the same route that now sets spouseAllowance (below).
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

/**
 * Fields the FORM never asks for, because แบบ ล.ย.01 does not print them — HR supplies them at
 * review through the existing HR tax-allowance editor.
 *
 * `spouseAllowance` is the interesting one: the form asks only for สถานภาพ (ข้อ 1) and whether the
 * spouse has income (ข้อ 2), never a baht figure. Turning that status into ฿60,000 is payroll math,
 * and the owner's ruling (2026-08-08) is that a human does it, exactly as on paper — the app
 * deliberately does NOT derive it. `disabilityCardHolder` is the same shape: a qualifying condition
 * of ข้อ 5 that the printed form has no box for.
 */
export const HR_SUPPLIED_KEYS = ['spouseAllowance', 'disabilityCardHolder'];

/** The nested `lorYor01` payload's own keys — everything on the form that is not an amount. */
export const LOR_YOR_01_DETAIL_KEYS = [
  'taxpayerId', 'firstNameTh', 'lastNameTh',
  'maritalState', 'spousalStatus', 'spouseHasIncome',
  'childrenTotal', 'childExtraAllowance',
  'ownFatherSupported', 'ownMotherSupported',
  'spouseFatherSupported', 'spouseMotherSupported', 'spouseParentCareAllowance',
  'ownFatherHealthInsured', 'ownMotherHealthInsured',
  'spouseFatherHealthInsured', 'spouseMotherHealthInsured',
  'providentFundAllowance', 'rmfSellerName', 'otherDonationNote',
];

/**
 * The eight ข้อ 4 / ข้อ 6 ticks. Split out because they must default to `false`, not `''` — they are
 * checkbox inputs validated as booleans, and seeding them with the empty string every other detail
 * key uses makes the whole form fail validation silently: `handleSubmit` simply never fires, with
 * no error rendered anywhere near the ข้อ the employee was filling in.
 */
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

/**
 * Attachment bucket for the signed, scanned form. Its own key rather than a file in the general
 * bucket (owner decision #3) so that "has the employee signed this?" is answerable without opening
 * files — the page gates submit on it and the backend refuses to approve without it.
 */
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

// ---------------------------------------------------------------------------------------------
// THE FORM — one section per ข้อ, in the government form's own order and wording.
//
// This replaced five invented categories (ครอบครัว / ประกัน / การออมและการลงทุน / ที่อยู่อาศัย /
// เงินบริจาค) that were grouped around modern Thai deductions rather than around the document the
// employee is actually filing. Owner ruling 2026-08-08: the page follows the paper.
//
// `title` quotes the form. Do NOT paraphrase it to read better — an employee cross-checking the
// screen against the PDF in front of them is the whole point of this structure.
//
// ⚠️ `hint` must never repeat the CAP figures the form prints in its own parentheticals. The 2562
// form carries superseded numbers (ข้อ 7 prints ฿10,000; the current life-insurance limit is
// ฿100,000), and this file's own header explains why an unsourced or stale Thai tax number must not
// appear here. Live figures come from GET /api/payroll/tax-allowances/caps via `capCategory`.
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
    // Printed on the form, never fillable: LTF stopped being deductible after tax year 2019, so
    // there is nothing an employee could truthfully declare and no column behind it (V137).
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
    // Derived, never typed: PayrollRepository#sumSocialSecurityForTaxYear sums what payroll actually
    // recorded. A form filed in January legitimately shows nothing here yet.
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

/**
 * Every field/row this schema expects to carry a `lawRef`, flattened to one list — the single
 * source of truth for taxAllowanceSchema.test.js, so a field added to a section is automatically
 * covered with no second list to remember.
 */
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

/**
 * The header slots `GET /declarations/me`'s `headerPrefill` can seed — owner decision #4's read
 * half. Deliberately NOT the whole detail block: ข้อ 2 (`spousalStatus`, `spouseHasIncome`) is a
 * statement about the tax year that no HR record can answer, and every ticked box and baht figure
 * below it is the employee's own declaration. Only identity is knowable in advance.
 *
 * `maritalState` is here because the master genuinely holds it; the backend maps
 * `hr.employee.marital_status` into ข้อ 1's own vocabulary so this stays a straight slot-for-slot
 * copy (see `TaxAllowanceDeclarationService#maritalStateFromMaster`).
 */
export const LOR_YOR_01_PREFILLABLE_DETAIL_KEYS = [
  'taxpayerId', 'firstNameTh', 'lastNameTh', 'maritalState',
];

/** Empty string, whitespace and null are all "the employee has not answered this". */
const isBlank = (value) => value == null || String(value).trim() === '';

/**
 * ONE rule, applied to every prefillable slot: what the employee already put there wins.
 *
 * A declaration being re-prepared after rejection carries the header the employee typed and
 * possibly corrected; the master carries whatever HR last confirmed. Letting the master win would
 * silently revert a correction the employee is in the middle of making — the exact opposite of
 * decision #4, where corrections flow employee → master on approval, never back.
 */
const seeded = (declared, offered) => (isBlank(declared) ? (offered ?? '') : declared);

/**
 * @param declaration   the declaration being restored, or null for a fresh form
 * @param headerPrefill `GET /declarations/me`'s `headerPrefill`, or null to seed nothing. The PAGE
 *                      decides whether to pass it — it must not reach a read-only or past-year
 *                      view, where the current master address would be shown against a historical
 *                      filing that never contained it. See `TaxAllowancePage`'s `canStartEdit` gate.
 */
export function defaultAllowanceValues(declaration, headerPrefill = null) {
  const source = declaration?.allowances || {};
  const detail = declaration?.lorYor01 || {};
  const prefill = headerPrefill || {};
  const values = {};
  for (const key of ALLOWANCE_MONEY_KEYS) values[key] = Number(source[key] || 0);
  for (const key of ALLOWANCE_COUNT_KEYS) values[key] = Number(source[key] || 0);
  values.disabilityCardHolder = Boolean(source.disabilityCardHolder);
  values.documentReference = declaration?.documentReference || '';
  // `effectiveMonth` IS restored here, unlike the pre-restructure defaults, which silently reverted
  // a re-prepared declaration to January while restoring documentReference right beside it.
  values.effectiveMonth = declaration?.effectiveMonth ?? '';
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

/**
 * Builds the exact body `POST .../declarations/me` expects — never includes `employeeId`.
 *
 * The five off-form amounts submit as 0 by construction: they are in ALLOWANCE_MONEY_KEYS (the wire
 * shape) but have no control, so `values` never carries them. That is the accepted consequence of
 * "strictly the 15 items" — see this file's contract note above.
 */
export function buildAllowanceSubmitBody(values, { taxYear, effectiveMonth, documentReference }) {
  const body = {
    taxYear,
    effectiveMonth: effectiveMonth || null,
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
    // A tri-state on the wire: null means neither ข้อ 2 box was ticked, which is different from
    // "ticked ไม่มีเงินได้". The radio group stores '' / 'true' / 'false' as strings.
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
