// มีอะไรใหม่: v2.2.0 (21 พ.ย. 2569): หน้า "เอกสารขาย" (เดิมคือรายการใบเสนอราคา) ที่มี 3 แท็บ และการปรับปรุงขอออกใบกำกับ/ส่งของ.
// Same shape as v2.1.0.js (see v2.0.0.js's header for how entries and sections work). Who can do or see each thing:
//   - Edit from the เอกสารขาย page: the deal owner (DealDocumentLists shows "แก้ไขได้เฉพาะเจ้าของงาน" to everyone else);
//     a closed deal (ปิดงาน / พักงาน / เสียงาน / ยกเลิก) is read-only with the reason shown instead of แก้ไข.
//   - sales_manager, ceo, account open and download; account and ceo see issued documents only (no drafts). import is not a target.
//   - Ask / send a round: DeliveryRequestService#requireWrite (sales roles). The send-back reason is read in that sheet.
//   - Recording a delivery: warehouse (RecordDeliverySheet).
//   - Reused-price approver line: any quotation PDF, so every role that can open the quotations page.
//
// NO screenshots: every step is a tuple with alt null (image null), the same as v2.1.0.

const RELEASE_ID = 'v2.2.0';
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
    id: 'sales-documents',
    page: 'เอกสารขาย',
    menu: 'งานขาย › เอกสารขาย',
    path: '/quotations',
    kind: 'changed',
    title: 'เมนู "ใบเสนอราคา" เปลี่ยนชื่อเป็น "เอกสารขาย" มี 3 แท็บ และแก้ไขหรือดาวน์โหลดเอกสารได้จากแต่ละแถว',
    why: 'เห็นใบเสนอราคา ใบแจ้งมัดจำ และใบแจ้งหนี้ส่วนที่เหลือของงานไว้ที่เดียว และรู้ว่าแต่ละใบมาจากใบเสนอราคาและงานไหน',
    steps: [
      ['เปิด "เอกสารขาย" แล้วเลือกแท็บ "ใบเสนอราคา" "ใบแจ้งมัดจำ" หรือ "ใบแจ้งหนี้ส่วนที่เหลือ"', 'ใบแจ้งมัดจำและใบแจ้งหนี้แต่ละใบบอกว่ามาจากใบเสนอราคาใด ลูกค้าใด และงานใด ส่วนใบเสนอราคาแต่ละใบแสดงเอกสารที่ทำต่อจากใบนั้น', null],
      ['ใบแจ้งมัดจำ กด "แก้ไข" ที่แถว', 'เปิดใบแจ้งมัดจำในโหมด "แก้ไขฉบับปัจจุบัน" เลขที่เดิม ประวัติการแก้ไขยังอยู่', null],
      ['ใบแจ้งหนี้ส่วนที่เหลือ กด "แก้ไข" ที่แถว แล้วเลือก "แก้ไขฉบับปัจจุบัน" หรือ "ออกฉบับใหม่"', 'ฉบับใหม่ได้เลขต่อท้ายเป็น -2 -3 ตามลำดับ', null],
      ['กด "ดาวน์โหลด" แล้วติ๊กไฟล์ที่จะรวม', 'การ์ด "เอกสารต่อจากใบเสนอราคานี้" ในหน้าใบเสนอราคาก็มี "แก้ไข" และ "ดาวน์โหลด" เหมือนกัน', null],
      ['งานที่ปิดงาน พักงาน เสียงาน หรือยกเลิก แก้ไขไม่ได้', 'แถวแสดงเหตุผลแทนปุ่ม "แก้ไข" ยังดาวน์โหลดได้', null],
    ],
    help: 'ไม่เห็นปุ่ม "แก้ไข" แก้ไขได้เฉพาะเจ้าของงาน หรืองานนั้นปิดแล้ว',
  }),
  entry({
    id: 'sales-documents-view',
    page: 'เอกสารขาย',
    menu: 'งานขาย › เอกสารขาย',
    path: '/quotations',
    kind: 'changed',
    title: 'เมนู "ใบเสนอราคา" เปลี่ยนชื่อเป็น "เอกสารขาย" เปิดดูและดาวน์โหลดใบแจ้งมัดจำและใบแจ้งหนี้ได้ในหน้าเดียว',
    why: 'ตามเอกสารของงานได้ว่ามาจากใบเสนอราคาและลูกค้าใด โดยไม่ต้องเปิดทีละงาน',
    steps: [
      ['เปิด "เอกสารขาย" แล้วเลือกแท็บ "ใบเสนอราคา" "ใบแจ้งมัดจำ" หรือ "ใบแจ้งหนี้ส่วนที่เหลือ"', 'แต่ละใบบอกว่ามาจากใบเสนอราคา ลูกค้า และงานใด', null],
      ['กด "เปิดดู" หรือ "ดาวน์โหลด" ที่แถวที่ต้องการ', 'ดาวน์โหลดเลือกไฟล์ที่จะรวมได้ ข้อความ "แก้ไขได้เฉพาะเจ้าของงาน" บอกว่าแก้ไขต้องให้เจ้าของงานทำ', null],
      ['ฝ่ายบัญชีและ CEO เห็นเฉพาะเอกสารที่ออกเลขแล้ว', 'ไม่เห็นฉบับร่าง', null],
    ],
    help: '',
  }),
  entry({
    id: 'delivery-requests-v2-2',
    page: 'ขอออกใบกำกับ / ส่งของ',
    menu: 'งานขาย › เอกสารขาย · งานขาย › งานทั้งหมด › เลือกงาน › ส่งของ',
    path: '/tickets',
    kind: 'changed',
    title: 'เห็นเหตุผลที่คลังส่งกลับในหน้าต่างขอ และเริ่มขอได้จากหน้า "เอกสารขาย"',
    why: 'แก้คำขอที่ถูกส่งกลับได้ทันทีโดยไม่ต้องหาเหตุผลในที่อื่น',
    steps: [
      ['ถ้าคลังส่งกลับ เปิดหน้าต่าง "ขอออกใบกำกับ / ส่งของ"', 'เหตุผลที่คลังส่งกลับแสดงอยู่ในหน้าต่างนั้น', null],
      ['เริ่มคำขอใหม่ได้จากปุ่ม "ขอออกใบกำกับ / ส่งของ" ในหน้า "เอกสารขาย"', 'เลือกงานแล้วทำต่อในหน้าต่างขอเหมือนเดิม', null],
    ],
    help: '',
  }),
  entry({
    id: 'delivery-requests-warehouse-v2-2',
    page: 'หน้างาน (รอบส่ง)',
    menu: 'กระดิ่งแจ้งเตือน › เปิดงาน',
    path: '/tickets/1',
    kind: 'changed',
    title: 'บันทึกการส่งของ ถ่ายรูปใบส่งของหรือเลือกไฟล์ (รูปหรือ PDF) ได้',
    why: 'แนบใบส่งของที่เซ็นแล้วได้ทั้งจากกล้องและจากไฟล์ที่มีอยู่',
    steps: [
      ['ตอนบันทึกการส่งของ กดถ่ายรูปใบส่งของที่เซ็นแล้ว หรือกด "เลือกไฟล์ (รูปหรือ PDF)"', 'ขั้น "ส่งของ" บันทึกได้หลังออกใบกำกับ หรือบันทึกก่อนพร้อมหมายเหตุ', null],
    ],
    help: '',
  }),
  entry({
    id: 'reused-price-approver',
    page: 'ใบเสนอราคา (PDF)',
    menu: 'งานขาย › เอกสารขาย › เปิดใบเสนอราคา › PDF',
    path: '/quotations',
    kind: 'changed',
    title: 'ใบเสนอราคาที่ใช้ราคาเดิมที่อนุมัติแล้ว พิมพ์ชื่อผู้อนุมัติเดิม',
    why: 'ผู้อ่านเห็นว่าใครอนุมัติราคานี้ และอนุมัติตามใบเสนอราคาเลขใด',
    steps: [
      ['เปิด PDF ของใบเสนอราคาที่ใช้ราคาเดิม', 'ช่องผู้อนุมัติแสดงชื่อผู้อนุมัติเดิมพร้อมข้อความ "อนุมัติตามราคาเดิม" และเลขที่ใบเสนอราคาต้นทาง เช่น QT-2026-0101', null],
    ],
    help: '',
  }),
];

const release = {
  id: RELEASE_ID,
  date: '2026-11-21',
  dateLabel: 'v2.2.0 · 21 พ.ย. 2569',
  title: 'หน้า "เอกสารขาย" 3 แท็บ และปรับปรุงขอออกใบกำกับ / ส่งของ',
  entries,
  otherRolesNote: 'ฉบับนี้ไม่มีเรื่องเฉพาะแผนกนี้',
  sections: {
    sales: ['sales-documents', 'delivery-requests-v2-2', 'reused-price-approver'],
    sales_manager: ['sales-documents-view', 'reused-price-approver'],
    ceo: ['sales-documents-view', 'reused-price-approver'],
    account: ['sales-documents-view', 'reused-price-approver'],
    import: [],
    hr: [],
    warehouse: ['delivery-requests-warehouse-v2-2'],
    qc: [],
    employee: [],
  },
};

export default release;
