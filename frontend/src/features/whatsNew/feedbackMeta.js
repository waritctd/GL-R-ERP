// แจ้งปัญหา / ขอฟีเจอร์: codes mirror FeedbackService (AREAS, impacts, statuses).
export const FEEDBACK_AREAS = [
  ['DEALS', 'งานทั้งหมด (ฝ่ายขาย)'], ['QUOTATIONS', 'ใบเสนอราคา'], ['VISITS', 'นัดเข้าพบ'], ['CATALOG', 'แคตตาล็อกสินค้า'],
  ['COMMISSION', 'ค่าคอมมิชชัน'], ['ATTENDANCE', 'เวลาทำงาน'], ['SPECIAL_MONEY', 'คำขอเงินพิเศษ'], ['LEAVE', 'วันลา'],
  ['TAX_ALLOWANCE', 'ค่าลดหย่อนภาษี'], ['PAYROLL', 'เงินเดือน'], ['MENU', 'เมนู / หน้าหลัก'], ['LOGIN', 'เข้าสู่ระบบ'],
  ['OTHER', 'อื่น ๆ / ไม่แน่ใจ'],
];

export const FEEDBACK_KINDS = {
  BUG: {
    title: 'แจ้งปัญหา', desc: 'มีอะไรใช้ไม่ได้ หรือแสดงผิด', q1: 'เกิดอะไรขึ้น',
    hint: 'เล่าตามลำดับ: ทำอะไรอยู่ กดอะไร แล้วเห็นอะไร', q2: 'ควรเป็นอย่างไร', impactLabel: 'กระทบงานแค่ไหน',
    impacts: [['BLOCKED', 'ทำงานต่อไม่ได้เลย'], ['WORKAROUND', 'ทำได้ แต่ต้องอ้อมหรือช้าลง'], ['LOW', 'ไม่รีบ แค่อยากให้รู้']],
  },
  FEATURE: {
    title: 'ขอฟีเจอร์ใหม่', desc: 'อยากให้ระบบทำอะไรเพิ่มได้', q1: 'อยากให้ทำอะไรได้',
    hint: 'เล่าเป็นงานจริง เช่น "อยากกรองงานตามเดือนที่ปิด"', q2: 'ตอนนี้ทำอย่างไรอยู่', impactLabel: 'จะได้ใช้บ่อยแค่ไหน',
    impacts: [['DAILY', 'ต้องใช้ทุกวัน'], ['SOMETIMES', 'ใช้เป็นครั้งคราว'], ['NICE', 'มีก็ดี']],
  },
};

export const PROGRESS_STEPS = [
  ['ACCEPTED', 'รับเรื่อง'], ['IN_PROGRESS', 'กำลังทำ'], ['TESTING', 'ทดสอบ'], ['RELEASED', 'ใช้งานได้แล้ว'],
];

/** Reporter-facing status label and badge tone. */
export function statusView(status) {
  if (status === 'PENDING') return { label: 'รอพิจารณา', tone: 'warning' };
  if (status === 'DENIED') return { label: 'ไม่รับ', tone: 'danger' };
  if (status === 'RELEASED') return { label: 'เสร็จแล้ว', tone: 'success' };
  return { label: 'รับเรื่องแล้ว', tone: 'indigo' };
}

// Longest prefix first is not needed: prefixes are disjoint.
const AREA_BY_PREFIX = [
  ['/quotations', 'QUOTATIONS'], ['/tickets', 'DEALS'], ['/deals', 'DEALS'], ['/visits', 'VISITS'],
  ['/catalog', 'CATALOG'], ['/commission', 'COMMISSION'], ['/attendance', 'ATTENDANCE'],
  ['/special-money', 'SPECIAL_MONEY'], ['/leave', 'LEAVE'], ['/tax-allowance', 'TAX_ALLOWANCE'],
  ['/payroll', 'PAYROLL'], ['/dashboard', 'MENU'], ['/login', 'LOGIN'],
];

export function areaForPath(path) {
  if (!path) return '';
  const pathname = path.split(/[?#]/)[0];
  const hit = AREA_BY_PREFIX.find(([prefix]) => pathname === prefix || pathname.startsWith(`${prefix}/`));
  return hit ? hit[1] : '';
}
