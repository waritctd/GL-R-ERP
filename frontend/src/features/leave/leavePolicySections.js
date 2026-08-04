// Leave-surface IA rebuild, Phase A3: the §5 company-announcement ("วันเวลาทำงาน และการหยุดงาน",
// effective 1 ต.ค. 2567, supersedes 2561) as ordered, renderable metadata for RulesTab.jsx.
//
// THE HARD RULE this file follows throughout: a rule VALUE (a quota, a cap, a notice-day count, a
// service-month floor, a monthly tolerance, a carry-forward flag...) is NEVER hand-typed into a
// `body` string below. Every one of those lives on `LeaveTypeDto` (backend/.../leave/LeaveTypeDto.java)
// and is fetched at runtime via `api.leave.types()` — RulesTab.jsx renders the live value next to
// this file's static prose. Writing "ลาป่วยได้ 30 วัน" here would create a second source of truth
// that silently goes stale the day someone changes `hr.leave_type.annual_quota_days`; that is
// exactly the failure mode this codebase has been bitten by before (see CLAUDE.md's "Mock API
// contract" section, and the fixture/mirroring gotchas it documents). `body` below only ever
// describes rules that have NO backing DTO column at all (see `LEAVE_RULE_FIELD_LABELS` for the
// ones that do) — e.g. the wedding-leave day cap and the department-coverage/contiguous-leave/
// post-resignation gates are Java constants or pure relational checks, not `hr.leave_type` columns
// (see LeaveService's own `WEDDING_LEAVE_MAX_DAYS`/`RESIGNATION_GATED_LEAVE_TYPES`/
// `CONTIGUOUS_LEAVE_PAIR`/`MIN_DEPARTMENT_SIZE_FOR_COVERAGE_GATE` comments) — those numbers are
// reproduced here because there is nowhere else for them to come from, not because this file is
// exempt from the rule above.
//
// Section `id`s are the exact `sectionRef` strings LeaveRuleCode.java (backend/.../leave/) declares
// (e.g. `"5.3.3"`) — a later phase deep-links a rejected employee straight into the matching id here
// (RulesTab.jsx anchors each section on it). Where LeaveRuleCode has no code for a clause (§5.4
// MATERNITY, §5.5 MILITARY have no auto-reject gate of their own today — no LeaveRuleCode entry
// targets "5.4"/"5.5"), the id still follows the announcement's own §-numbering for consistency and
// future-proofing. `unpaid` (LEAVE_WITHOUT_PAY) is not one of the announcement's six numbered §5
// leave types — it is the payroll-facing label the backend gives to quota-exceeding days (see
// V85__leave_payroll_unpaid_deduction.sql) — so it deliberately does not claim a numeric id.
import { LEAVE_PURPOSE_OPTIONS } from './leaveRequestTable.jsx';

// §5.2's five NAMED purposes, read live off the same list the request form's "เหตุผลการลากิจ"
// select already uses (leaveRequestTable.jsx) — not retyped here, so this section can never drift
// from what the form actually offers. `OTHER` ("อื่นๆ") is the catch-all, not a sixth named purpose.
export const NAMED_PERSONAL_PURPOSES = LEAVE_PURPOSE_OPTIONS.filter((option) => option.value !== 'OTHER');

// The everyday/rare split RulesTab.jsx opens sections with. Duplicated from MyLeaveTab.jsx's own
// EVERYDAY_LEAVE_TYPE_CODES constant on purpose: MyLeaveTab.jsx is owned by this phase's Git Town
// parent (feat/leave-surface-ia-shell, PR #486) and is off-limits for edits here (see
// LeaveSurfacePage.jsx's TODO(A3) comment) — it has no exported constant this file could import
// instead. Keep the two lists in sync by hand if the everyday/rare split ever changes.
export const EVERYDAY_LEAVE_TYPE_CODES = new Set(['SICK', 'PERSONAL', 'VACATION']);

// Every LeaveTypeDto rule field that carries a policy meaning (excludes the three identity fields
// `code`/`nameTh`/`nameEn`) mapped to its short Thai `<dt>` label — RulesTab.jsx pairs each with a
// live-formatted `<dd>` value (see its own `formatRuleFieldValue`) inside a FieldList row. This is
// the "13th column" guard: adding a field to LeaveTypeDto without adding a label here is exactly
// the silent-omission failure this phase was asked to prevent — see leavePolicySections.test.js,
// which fails if any non-null field on a seeded leave type has no entry here.
//
// Where a field overlaps MyLeaveTab.jsx's own rare-type copy (minServiceMonths, oncePerEmployment,
// paidDaysCap), RulesTab.jsx's label+value pair reads as the SAME phrase MyLeaveTab.jsx uses (see
// its rareLeaveConditionText/rareBalanceSummary), just split across `<dt>`/`<dd>` instead of one
// inline sentence — e.g. "จ่ายค่าจ้างสูงสุด" + "45 วัน" is the same fact, same words, as MyLeaveTab's
// "จ่ายค่าจ้างสูงสุด 45 วัน". Two screens disagreeing about what a rule says is worse than one not
// showing it at all.
export const LEAVE_RULE_FIELD_LABELS = {
  annualQuotaDays: 'โควตาต่อปี',
  requiresAttachment: 'เอกสารประกอบการลา',
  paidDaysCap: 'จ่ายค่าจ้างสูงสุด',
  advanceNoticeDays: 'แจ้งล่วงหน้าอย่างน้อย',
  minServiceMonths: 'อายุงานอย่างน้อย',
  maxConsecutiveDays: 'ติดต่อกันได้ไม่เกิน',
  oncePerEmployment: 'เงื่อนไขการใช้สิทธิ์',
  dayCountBasis: 'วิธีนับวันลา',
  proratedFirstYear: 'การคำนวณโควตาปีแรก',
  firstYearMaxDays: 'โควตาปีแรกของการทำงาน',
  certificateFilingWindowDays: 'ยื่นใบรับรองแพทย์ภายใน',
  noCertificateMonthlyTolerance: 'ลาไม่มีใบรับรองแพทย์ได้',
  emergencyMonthlyAllowance: 'ลากิจฉุกเฉินได้ (โดยไม่หักเงิน)',
  carriesForward: 'การยกยอดวันลา',
};

export const LEAVE_POLICY_SECTIONS = [
  {
    id: '5.1',
    title: '5.1 ลาป่วย',
    types: ['SICK'],
    // fields owned by THIS section's FieldList — see RulesTab.jsx's fieldsForSection. Ownership is
    // 1:1 across the whole list below so no runtime value is ever rendered twice.
    fields: [
      'annualQuotaDays', 'requiresAttachment', 'advanceNoticeDays', 'minServiceMonths',
      'maxConsecutiveDays', 'certificateFilingWindowDays', 'noCertificateMonthlyTolerance',
    ],
    body: 'ลาป่วยตามที่ป่วยจริง แจ้งหัวหน้างานทราบโดยเร็วที่สุด กรณีมีใบรับรองแพทย์ ต้องยื่นภายในระยะเวลาที่กำหนดด้านล่างนับจากวันแรกที่ลา '
      + 'กรณีไม่มีใบรับรองแพทย์ จะได้รับการผ่อนผันให้ลาป่วยได้ในจำนวนครั้งต่อเดือนตามที่ระบุด้านล่างเท่านั้น '
      + 'เกินกว่านั้นต้องมีใบรับรองแพทย์แนบประกอบทุกครั้ง',
  },
  {
    id: '5.2',
    title: '5.2 ลากิจ',
    types: ['PERSONAL'],
    fields: [
      'annualQuotaDays', 'requiresAttachment', 'advanceNoticeDays', 'minServiceMonths',
      'proratedFirstYear', 'firstYearMaxDays', 'emergencyMonthlyAllowance',
    ],
    // NAMED_PERSONAL_PURPOSES itself is rendered separately by RulesTab.jsx (a live list, not
    // prose) — this body only carries the parts with no backing data at all: the wedding-leave
    // day cap (LeaveService#WEDDING_LEAVE_MAX_DAYS, a Java constant, not an hr.leave_type column —
    // see this file's header comment) and the emergency-filing PROCEDURE (its "how many times a
    // month" number is a real column, rendered from LEAVE_RULE_FIELD_LABELS.emergencyMonthlyAllowance
    // instead of repeated here).
    body: 'ลากิจธุระจำเป็นสำหรับเหตุผลเฉพาะที่บริษัทกำหนดไว้ ดังรายการด้านล่าง พิธีสมรส (ของตนเองหรือบุตร) ลาได้ไม่เกิน 3 วัน '
      + 'กรณีมีเหตุฉุกเฉินจนไม่สามารถแจ้งล่วงหน้าได้ทัน ให้แจ้งหัวหน้างานก่อนเริ่มเวลาทำงานแล้วยื่นใบลาในวันแรกที่กลับมาทำงาน '
      + 'ถือเป็นการลากิจฉุกเฉินโดยไม่หักเงิน ภายในจำนวนครั้งต่อเดือนตามที่ระบุด้านล่าง',
  },
  {
    id: '5.3',
    title: '5.3 ลาพักร้อน',
    types: ['VACATION'],
    fields: ['annualQuotaDays', 'requiresAttachment', 'advanceNoticeDays', 'minServiceMonths', 'proratedFirstYear'],
    body: 'ลาพักร้อนประจำปี กรณีอายุงานยังไม่ถึงหนึ่งปีให้ลาได้ตามสัดส่วนอายุงาน ต้องแจ้งล่วงหน้าตามระยะเวลาที่กำหนดด้านล่าง '
      + 'เพื่อให้หัวหน้างานจัดคนทำงานทดแทนได้ทัน',
  },
  {
    id: '5.3.2',
    title: '5.3.2 ต้องมีคนทำงานในแผนก',
    // Type-agnostic — LeaveService#departmentCoverageRejectionNote applies "to every leave type"
    // (its own comment's wording), not only VACATION/PERSONAL. Grouped under §5.3's numbering
    // because that is where the announcement places it, not because it is vacation-specific.
    types: [],
    fields: [],
    body: 'จะไม่อนุมัติคำขอลาที่ทำให้ไม่มีใครในแผนกเดียวกันมาทำงานเลยในวันใดวันหนึ่ง กฎนี้ใช้กับการลาทุกประเภท '
      + 'และยกเว้นให้กับแผนกที่มีพนักงานประจำการจำนวนน้อย ซึ่งกฎนี้จะบล็อกการลาแทบทุกครั้งหากบังคับใช้อย่างเคร่งครัด',
  },
  {
    id: '5.3.3',
    title: '5.3.3 ลาติดกันไม่ได้',
    types: ['VACATION', 'PERSONAL'],
    fields: [],
    body: 'ลากิจและลาพักร้อนใช้ต่อเนื่องกันไม่ได้ — ยื่นลาพักร้อนติดกับลากิจ (ก่อนหรือหลังก็ตาม) จะไม่ได้รับอนุมัติ '
      + 'ต้องเว้นวันทำงานอย่างน้อยหนึ่งวันคั่นระหว่างสองประเภทนี้',
  },
  {
    id: '5.3.4',
    title: '5.3.4 ลาไม่ได้หลังยื่นใบลาออก',
    types: ['VACATION', 'PERSONAL'],
    fields: [],
    body: 'เมื่อยื่นใบลาออกแล้ว จะไม่สามารถยื่นคำขอลาพักร้อนหรือลากิจใหม่ได้อีก จนกว่าจะพ้นสภาพพนักงาน',
  },
  {
    id: '5.3.5',
    title: '5.3.5 ยกยอดวันลาพักร้อน',
    types: ['VACATION'],
    fields: ['carriesForward'],
    body: 'วันลาพักร้อนที่เหลือใช้ไม่หมดในปีนี้ สามารถยกไปใช้ต่อในปีถัดไปได้ตามเงื่อนไขด้านล่าง หากยังใช้ไม่หมดในปีที่ยกไป '
      + 'ส่วนที่เหลือจะหมดอายุไป ไม่สะสมข้ามปีต่อเนื่องไปเรื่อยๆ',
  },
  {
    id: '5.4',
    title: '5.4 ลาคลอดบุตร',
    types: ['MATERNITY'],
    fields: ['annualQuotaDays', 'requiresAttachment', 'paidDaysCap', 'advanceNoticeDays', 'minServiceMonths', 'dayCountBasis'],
    body: 'ลาคลอดบุตร นับรวมวันลาเพื่อตรวจครรภ์ก่อนคลอดด้วย จ่ายค่าจ้างเต็มจำนวนไม่เกินจำนวนวันที่ระบุด้านล่าง '
      + 'ส่วนที่เกินจากนั้นเป็นการลาที่ไม่ได้รับค่าจ้าง',
  },
  {
    id: '5.5',
    title: '5.5 ลารับราชการทหาร',
    types: ['MILITARY'],
    // annualQuotaDays deliberately excluded — see RulesTab.jsx's own comment on why MILITARY's
    // 366 is a sentinel ("no annual ceiling") and must never render as a quota figure.
    fields: ['requiresAttachment', 'paidDaysCap', 'advanceNoticeDays', 'minServiceMonths'],
    body: 'ลาเพื่อเข้ารับการตรวจเลือก เข้ารับการฝึกวิชาทหาร หรือทดสอบความพร้อมตามหมายเรียกของทางราชการ '
      + 'ไม่ใช่การสมัครใจเข้าเป็นทหาร ต้องแนบหมายเรียกหรือเอกสารจากทางราชการประกอบการลา จ่ายค่าจ้างเต็มจำนวนไม่เกินจำนวนวันที่ระบุด้านล่างต่อปี',
  },
  {
    id: '5.6',
    title: '5.6 ลาอุปสมบท',
    types: ['ORDINATION'],
    fields: ['annualQuotaDays', 'requiresAttachment', 'paidDaysCap', 'advanceNoticeDays', 'minServiceMonths', 'oncePerEmployment'],
    body: 'ลาอุปสมบท จ่ายค่าจ้างเต็มจำนวนไม่เกินจำนวนวันที่ระบุด้านล่าง ส่วนที่เหลือของโควตาเป็นการลาที่ไม่ได้รับค่าจ้าง',
  },
  {
    id: 'unpaid',
    title: 'ลาไม่รับค่าจ้าง',
    types: ['LEAVE_WITHOUT_PAY'],
    fields: ['requiresAttachment', 'advanceNoticeDays', 'minServiceMonths'],
    body: 'ไม่ใช่ประเภทการลาที่ยื่นขอโดยตรง แต่เป็นส่วนของวันลาที่เกินสิทธิ์ที่จ่ายค่าจ้างของประเภทการลาอื่น '
      + '(เช่น ส่วนที่เกินสิทธิ์จ่ายค่าจ้างของลาคลอดบุตรหรือลาอุปสมบท — ดู "จ่ายค่าจ้างสูงสุด" ในหมวดนั้นๆ) '
      + 'ซึ่งจะถูกหักเป็นวันลาไม่รับค่าจ้างโดยอัตโนมัติ',
  },
];
