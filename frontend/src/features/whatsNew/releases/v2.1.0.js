// มีอะไรใหม่: v2.1.0 (20 พ.ย. 2569): ขอออกใบกำกับ/ส่งของ (รอบส่ง) ในแท็บ "ส่งของ" และงานที่สั่งซื้อก่อนใช้พอร์ทัล. Same shape as
// v2.0.0.js (see its header for how entries and sections work). Who can do or see each thing follows the Java rules:
//   - Ask / send a round: DeliveryRequestService#requireWrite (owner, keyer, sales manager, all-sales-docs grant; sales roles).
//   - Warehouse and the tax-invoice issuer (a per-person grant on any role) open exactly /tickets/:id (RoundWorkView); the
//     issuer has no role of its own, so it has no section here: its steps are told to the people who ask and to account.
//   - The pre-portal deposit and its date: TicketService#becomePrePortal (sales, sales_manager, ceo may start a deal at สั่งซื้อแล้ว).
//
// NO screenshots: none could be taken for this release, so every step is a tuple with alt null (image null), the same as
// the v2.0.0 steps the mock app cannot show. Add pictures under /whats-new-img/v2.1.0/<entry id>-<step>.png and give each
// step its alt to turn them on.

const RELEASE_ID = 'v2.1.0';
const shot = (entryId, n) => `/whats-new-img/${RELEASE_ID}/${entryId}-${n}.png`;

/** A step tuple is [text, detail, alt]; an alt of null means the step has NO screenshot (image null). */
function entry({ id, steps, ...rest }) {
  return {
    id,
    ...rest,
    steps: steps.map(([text, detail, alt], index) => ({
      text, detail, alt, image: alt === null ? null : shot(id, index + 1),
    })),
  };
}

const entries = [
  entry({
    id: 'delivery-requests',
    page: 'หน้างาน',
    menu: 'งานขาย › งานทั้งหมด › เลือกงาน › ส่งของ',
    path: '/tickets',
    kind: 'new',
    title: 'ขอออกใบกำกับและส่งของเป็นรอบ ในแท็บ "ส่งของ" หรือจากรายการใบเสนอราคา',
    why: 'คลังเห็นว่าต้องส่งอะไร รอบไหน ฝ่ายออกใบกำกับแนบใบกำกับให้ในที่เดียว และเงินที่รับบันทึกตามใบกำกับ ไม่ต้องตามกันทางแชต',
    steps: [
      ['เปิดงาน ไปที่แท็บ "ส่งของ" แล้วกด "ขอออกใบกำกับ / ส่งของ" หรือกดปุ่มเดียวกันที่แถวใบเสนอราคาในรายการใบเสนอราคา', 'เลือกใบเสนอราคาของรอบนี้ ติ๊กเอกสารและไฟล์ที่จะส่ง ใส่หมายเหตุถึงคลังได้', null],
      ['กด "ส่งให้คลัง"', 'ถ้าส่งไม่ได้ ปุ่มบอกเหตุผลอยู่ใต้ปุ่ม คลังตรวจรอบ แล้วส่งต่อให้ผู้ออกใบกำกับ หรือส่งกลับพร้อมเหตุผลให้คุณแก้ไข', null],
      ['เมื่อผู้ออกใบกำกับแนบใบกำกับและยืนยัน ระบบนับเป็นเงินที่รับแล้ว', 'ยอดที่บันทึกไม่เกินมูลค่างาน ถ้ายังไม่ได้ส่งของ ผู้ออกใบกำกับยกเลิกใบกำกับพร้อมเหตุผลได้', null],
      ['คลังส่งของหลังมีใบกำกับ หรือส่งก่อนพร้อมหมายเหตุ', 'รอบที่ส่งแล้วขึ้นในแท็บ "ส่งของ" ของงาน', null],
      ['ดูไฟล์ของแต่ละรอบที่แท็บ "ไฟล์"', 'ไฟล์จัดตามออเดอร์และรอบส่ง ไฟล์ของรอบที่ส่งต่อแล้วแก้ไม่ได้', null],
    ],
    help: 'ไม่เห็นปุ่มขอออกใบกำกับ งานอาจไม่ใช่ของคุณ หรือยังไม่มีใบเสนอราคาที่อนุมัติ',
  }),
  entry({
    id: 'delivery-requests-team',
    page: 'หน้างาน',
    menu: 'งานขาย › งานทั้งหมด › เลือกงาน › ส่งของ',
    path: '/tickets',
    kind: 'new',
    title: 'แท็บ "ส่งของ" แสดงรอบส่ง สถานะ และใบกำกับของแต่ละรอบ',
    why: 'เห็นว่าทีมขอไปถึงขั้นไหน ใครรออะไร โดยไม่ต้องถาม',
    steps: [
      ['เปิดงาน ไปที่แท็บ "ส่งของ"', 'แต่ละรอบแสดงสถานะ ใบเสนอราคาที่อ้างอิง ใบกำกับที่แนบ และการส่งของ', null],
      ['ดูไฟล์ของรอบที่แท็บ "ไฟล์"', 'ไฟล์จัดตามออเดอร์และรอบส่ง', null],
    ],
    help: '',
  }),
  entry({
    id: 'delivery-requests-warehouse',
    page: 'หน้างาน (รอบส่ง)',
    menu: 'กระดิ่งแจ้งเตือน › เปิดงาน',
    path: '/tickets/1',
    kind: 'new',
    title: 'คลังรับรอบส่งที่ฝ่ายขายขอ ตรวจ ส่งต่อ หรือส่งกลับ และบันทึกการส่งของ',
    why: 'รู้ว่าต้องส่งอะไรให้ใคร รอบไหน และใบกำกับพร้อมหรือยัง',
    steps: [
      ['กดแจ้งเตือนรอบส่งใหม่ เพื่อเปิดรอบนั้น', 'หน้านี้แสดงเฉพาะรอบและไฟล์ของรอบ ไม่แสดงราคา', null],
      ['ตรวจรายการและไฟล์ แล้วกด "ส่งต่อ" ให้ผู้ออกใบกำกับ หรือกด "ส่งกลับ" พร้อมเหตุผลให้ฝ่ายขายแก้', 'ฝ่ายขายได้รับแจ้งเมื่อส่งกลับ', null],
      ['เมื่อมีใบกำกับแล้วบันทึกการส่งของ หรือส่งก่อนใบกำกับโดยใส่หมายเหตุ', 'ผู้ที่เกี่ยวข้องได้รับแจ้งเมื่อบันทึก', null],
    ],
    help: '',
  }),
  entry({
    id: 'delivery-requests-finance',
    page: 'หน้างานการเงิน',
    menu: 'การเงิน › งานการเงิน › เปิดงาน',
    path: '/finance',
    kind: 'changed',
    title: 'ใบกำกับที่ผู้ออกใบกำกับยืนยันถูกบันทึกเป็นเงินที่รับแล้ว',
    why: 'ยอดรับชำระตรงกับใบกำกับ ไม่ต้องคีย์ซ้ำ และปรับยอดได้ถ้ามีข้อผิดพลาด',
    steps: [
      ['เปิดงานจากหน้า "งานการเงิน" แล้วดูรายการรับเงิน', 'รายการที่มาจากใบกำกับแสดงเลขที่ใบกำกับ', null],
      ['ถ้ายอดไม่ถูก ใช้ "ปรับยอด" ตามเดิม', 'ใบกำกับที่ยังไม่ส่งของ ผู้ออกใบกำกับยกเลิกได้ ยอดที่บันทึกก็ถูกถอนตาม', null],
    ],
    help: '',
  }),
  entry({
    id: 'pre-portal-orders',
    page: 'สร้างงาน / ขอออกใบกำกับ',
    menu: 'งานขาย › สร้างงาน › เริ่มที่ "สั่งซื้อแล้ว"',
    path: '/tickets',
    kind: 'new',
    title: 'งานที่ลูกค้าสั่งซื้อก่อนใช้พอร์ทัล เริ่มที่ "สั่งซื้อแล้ว" พร้อมมัดจำที่รับแล้วและวันที่รับ',
    why: 'งานเก่าเข้าระบบได้โดยไม่ต้องย้อนทำขั้นตอนเดิม และเงินมัดจำนับในเดือนของวันที่ได้รับจริง',
    steps: [
      ['ตอนสร้างงาน เลือก "เริ่มที่" เป็น "สั่งซื้อแล้ว"', 'ใช้กับงานที่ลูกค้าสั่งไปแล้วก่อนมีพอร์ทัล', null],
      ['ใส่ "มัดจำที่รับแล้วก่อนใช้ระบบ" และ "วันที่ได้รับมัดจำ"', 'ใส่ 0 ถ้ายังไม่ได้รับ ถ้ามากกว่า 0 ต้องใส่วันที่ และเป็นวันในอนาคตไม่ได้ วันที่เป็นปี พ.ศ.', null],
      ['งานเก่าที่ขอออกใบกำกับครั้งแรก ใส่มัดจำและวันที่ในหน้าต่างขอเช่นกัน', 'ถ้าใส่ผิด ฝ่ายขายยกเลิกการยืนยันคำสั่งซื้อได้ตราบที่ยังไม่มีเงินอื่นหรือรายการ Express มาผูก ไม่เช่นนั้นให้ฝ่ายบัญชีปรับยอด', null],
    ],
    help: 'ปุ่มส่งไม่ทำงาน ดูเหตุผลใต้ปุ่ม มักเป็นยังไม่ใส่วันที่ได้รับมัดจำ',
  }),
];

const release = {
  id: RELEASE_ID,
  date: '2026-11-20',
  dateLabel: 'v2.1.0 · 20 พ.ย. 2569',
  title: 'ขอออกใบกำกับและส่งของเป็นรอบ ใบกำกับนับเป็นเงินที่รับ และงานที่สั่งซื้อก่อนใช้พอร์ทัล',
  entries,
  otherRolesNote: 'ฉบับนี้ไม่มีเรื่องเฉพาะแผนกนี้ ผู้ที่ได้รับสิทธิ์ออกใบกำกับดูขั้นตอนของตนในแจ้งเตือนรอบส่ง',
  sections: {
    sales: ['delivery-requests', 'pre-portal-orders'],
    sales_manager: ['delivery-requests-team', 'pre-portal-orders'],
    ceo: ['delivery-requests-team', 'pre-portal-orders'],
    account: ['delivery-requests-finance'],
    import: [],
    hr: [],
    warehouse: ['delivery-requests-warehouse'],
    qc: [],
    employee: [],
  },
};

export default release;
