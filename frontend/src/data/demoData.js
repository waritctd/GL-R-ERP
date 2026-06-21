export const divisions = [
  { id: 'SAL', th: 'ขายและการตลาด', en: 'Sales & Marketing' },
  { id: 'WHL', th: 'คลังสินค้าและจัดส่ง', en: 'Warehouse & Logistics' },
  { id: 'PRC', th: 'จัดซื้อ', en: 'Procurement' },
  { id: 'FIN', th: 'บัญชีและการเงิน', en: 'Accounting & Finance' },
  { id: 'HRD', th: 'ทรัพยากรบุคคล', en: 'Human Resources' },
  { id: 'IT', th: 'เทคโนโลยีสารสนเทศ', en: 'Information Technology' },
];

export const departments = {
  SAL: ['ขายปลีก', 'ขายโครงการ', 'การตลาด'],
  WHL: ['คลังสินค้า', 'ขนส่ง', 'ควบคุมสต็อก'],
  PRC: ['จัดซื้อในประเทศ', 'นำเข้า'],
  FIN: ['บัญชี', 'การเงิน'],
  HRD: ['สรรหาบุคลากร', 'ค่าตอบแทน'],
  IT: ['พัฒนาระบบ', 'ไอทีซัพพอร์ต'],
};

export const statuses = [
  { id: 'ACT', th: 'ทำงานปกติ', en: 'Active', tone: 'success' },
  { id: 'PRB', th: 'ทดลองงาน', en: 'Probation', tone: 'warning' },
  { id: 'RSG', th: 'ลาออก', en: 'Resigned', tone: 'danger' },
];

const positions = [
  { th: 'ผู้จัดการฝ่าย', en: 'Division Manager', level: 'M3', salary: 152000 },
  { th: 'ผู้จัดการแผนก', en: 'Department Manager', level: 'M2', salary: 92000 },
  { th: 'หัวหน้างาน', en: 'Supervisor', level: 'M1', salary: 56000 },
  { th: 'เจ้าหน้าที่อาวุโส', en: 'Senior Officer', level: 'S2', salary: 41000 },
  { th: 'เจ้าหน้าที่', en: 'Officer', level: 'O2', salary: 27500 },
  { th: 'พนักงานขาย', en: 'Sales Representative', level: 'O1', salary: 21000 },
  { th: 'พนักงานคลังสินค้า', en: 'Warehouse Officer', level: 'O1', salary: 17500 },
  { th: 'พนักงานขับรถ', en: 'Driver', level: 'O1', salary: 16500 },
];

const firstNames = [
  ['นาย', 'สมชาย', 'Somchai', 'ชาย', 'ชาย'],
  ['นางสาว', 'นภาพร', 'Napaporn', 'นก', 'หญิง'],
  ['นาย', 'วีรพงษ์', 'Weerapong', 'พงษ์', 'ชาย'],
  ['นางสาว', 'ปิยะนุช', 'Piyanuch', 'ปุ๊ก', 'หญิง'],
  ['นาย', 'กิตติศักดิ์', 'Kittisak', 'กิต', 'ชาย'],
  ['นางสาว', 'รัตนา', 'Rattana', 'นา', 'หญิง'],
  ['นาย', 'ชัยวัฒน์', 'Chaiwat', 'ชัย', 'ชาย'],
  ['นางสาว', 'จิราพร', 'Jiraporn', 'ฝน', 'หญิง'],
  ['นาย', 'ภาคภูมิ', 'Pakphum', 'ภูมิ', 'ชาย'],
  ['นางสาว', 'ชุติมา', 'Chutima', 'ตุ๊ก', 'หญิง'],
];

const lastNames = [
  ['ใจดี', 'Jaidee'],
  ['รุ่งเรือง', 'Rungrueang'],
  ['ศรีสุข', 'Srisuk'],
  ['วงศ์สวัสดิ์', 'Wongsawat'],
  ['แสงทอง', 'Saengthong'],
  ['บุญมี', 'Bunmee'],
  ['พงษ์พานิช', 'Pongpanich'],
  ['สุขสวัสดิ์', 'Suksawat'],
  ['ทองคำ', 'Thongkham'],
  ['เจริญสุข', 'Charoensuk'],
];

const avatarPalette = [
  ['#e0e7ff', '#4338ca'],
  ['#ccfbf1', '#0f766e'],
  ['#fef3c7', '#b45309'],
  ['#ffe4e6', '#be123c'],
  ['#e0f2fe', '#0369a1'],
  ['#dcfce7', '#15803d'],
];

function iso(year, month, day) {
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

function thirteenDigits(seed) {
  let text = '';
  for (let index = 0; index < 13; index += 1) text += (seed * 7 + index * 13 + 3) % 10;
  return `${text.slice(0, 1)}-${text.slice(1, 5)}-${text.slice(5, 10)}-${text.slice(10, 12)}-${text.slice(12)}`;
}

export function createDemoDatabase() {
  const divAssign = [0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 5, 5, 5, 0, 1, 3, 4, 5];
  const divisionManagers = {};
  const employees = divAssign.map((divisionIndex, index) => {
    const first = firstNames[index % firstNames.length];
    const last = lastNames[(index * 5 + 2) % lastNames.length];
    const division = divisions[divisionIndex];
    const department = departments[division.id][index % departments[division.id].length];
    const isFirstInDivision = divisionManagers[division.id] === undefined;
    const position = isFirstInDivision
      ? positions[0]
      : division.id === 'WHL'
        ? positions[[2, 6, 6, 7, 6, 7][index % 6]]
        : division.id === 'SAL'
          ? positions[[2, 5, 5, 4, 3][index % 5]]
          : positions[[2, 3, 4, 4, 3][index % 5]];

    if (isFirstInDivision) divisionManagers[division.id] = index + 1;

    const hireYear = 2012 + ((index * 7) % 12);
    const dobYear = 1978 + ((index * 13) % 24);
    const status = index === 3 || index === 17 || index === 28
      ? statuses[1]
      : index === 24 || index === 29
        ? statuses[2]
        : statuses[0];
    const salary = Math.max(15000, Math.round((position.salary + (((index * 1300) % 9000) - 2500)) / 500) * 500);
    const [avatarBg, avatarFg] = avatarPalette[index % avatarPalette.length];
    const code = `GLR-${1001 + index}`;
    const nameTh = `${first[0]}${first[1]} ${last[0]}`;
    const nameEn = `${first[2]} ${last[1]}`;
    const email = `${first[2].toLowerCase()}.${last[1][0].toLowerCase()}@glr.co.th`;
    const phone = `08${1 + (index % 9)}-${String(123 + index * 3).padStart(3, '0')}-${String(4500 + index * 37).padStart(4, '0')}`;
    const lowPosition = positions[Math.min(positions.indexOf(position) + 1, positions.length - 1)];

    return {
      id: index + 1,
      code,
      badge: `BC-${20250000 + index * 137}`,
      nameTh,
      nameEn,
      nickName: first[3],
      initials: `${first[2][0]}${last[1][0]}`.toUpperCase(),
      avatarBg,
      avatarFg,
      titleTh: first[0],
      genderTh: first[4],
      birthDate: iso(dobYear, (index * 7) % 12 + 1, (index * 11) % 27 + 1),
      age: 2026 - dobYear,
      nationality: 'ไทย',
      maritalStatus: index % 3 === 0 ? 'สมรส' : 'โสด',
      email,
      phone,
      divisionId: division.id,
      divisionTh: division.th,
      divisionEn: division.en,
      departmentTh: department,
      positionTh: position.th,
      positionEn: position.en,
      level: position.level,
      locationTh: ['สำนักงานใหญ่ กรุงเทพฯ', 'คลังสินค้าบางนา', 'สาขานนทบุรี', 'สาขาเชียงใหม่'][index % 4],
      statusId: status.id,
      statusTh: status.th,
      statusTone: status.tone,
      active: status.id !== 'RSG',
      payType: 'รายเดือน',
      salary,
      hireDate: iso(hireYear, (index * 5) % 12 + 1, (index * 3) % 27 + 1),
      confirmationDate: iso(hireYear, Math.min((index * 5) % 12 + 5, 12), (index * 3) % 27 + 1),
      reportsTo: null,
      bank: ['ธนาคารกสิกรไทย', 'ธนาคารไทยพาณิชย์', 'ธนาคารกรุงเทพ', 'ธนาคารกรุงไทย'][index % 4],
      bankAccount: `${String(index * 13).padStart(3, '0')}-${index % 10}-${String(12345 + index * 97).padStart(5, '0')}`,
      currentAddress: {
        line1: `${10 + index}/${(index % 9) + 1} ซอย ${(index % 12) + 1} ถ.สุขุมวิท`,
        district: ['บางนา', 'คลองเตย', 'ห้วยขวาง', 'จตุจักร'][index % 4],
        province: ['กรุงเทพมหานคร', 'นนทบุรี', 'สมุทรปราการ', 'เชียงใหม่'][index % 4],
        postalCode: `10${String(index * 111).padStart(3, '0')}`,
      },
      emergencyContact: {
        name: ['คุณมานพ', 'คุณสุดา', 'คุณวิรัช', 'คุณนงนุช'][index % 4],
        relationship: ['คู่สมรส', 'บิดา', 'มารดา', 'พี่สาว'][index % 4],
        phone: `09${index % 9}-${String(200 + index).padStart(3, '0')}-${String(7800 + index * 11).padStart(4, '0')}`,
      },
      education: [
        {
          level: 'ปริญญาตรี',
          major: ['บริหารธุรกิจ', 'การบัญชี', 'การตลาด', 'วิทยาการคอมพิวเตอร์'][index % 4],
          institution: ['มหาวิทยาลัยเกษตรศาสตร์', 'มหาวิทยาลัยกรุงเทพ', 'มหาวิทยาลัยธรรมศาสตร์'][index % 3],
          yearStart: dobYear + 19,
          yearEnd: dobYear + 23,
        },
      ],
      assignments: [
        {
          from: iso(hireYear, 1, 1),
          to: iso(hireYear + 2, 1, 1),
          title: lowPosition.th,
          division: division.th,
          department,
          current: false,
        },
        {
          from: iso(hireYear + 2, 1, 1),
          to: null,
          title: position.th,
          division: division.th,
          department,
          current: true,
        },
      ],
      salaryHistory: [
        { date: iso(hireYear, 1, 1), oldSalary: 0, newSalary: Math.round(salary * 0.7), note: 'เริ่มงาน' },
        { date: iso(hireYear + 2, 1, 1), oldSalary: Math.round(salary * 0.7), newSalary: Math.round(salary * 0.86), note: 'ปรับประจำปี' },
        { date: iso(2025, 1, 1), oldSalary: Math.round(salary * 0.86), newSalary: salary, note: 'ปรับตามผลประเมิน' },
      ],
      sensitive: {
        nationalId: thirteenDigits(index + 1),
        taxId: thirteenDigits(index + 50).replaceAll('-', ''),
        socialSecurityNo: thirteenDigits(index + 90).replaceAll('-', ''),
        socialSecurityHospital: ['โรงพยาบาลกรุงเทพ', 'โรงพยาบาลบางนา 1', 'โรงพยาบาลพระราม 9'][index % 3],
        providentFundNo: `PVD-${20000 + index * 7}`,
      },
    };
  });

  employees.forEach((employee) => {
    employee.reportsTo = employee.positionTh === 'ผู้จัดการฝ่าย'
      ? 'คุณวิชัย ธนาคาร · กรรมการผู้จัดการ'
      : `${employees[divisionManagers[employee.divisionId] - 1].nameTh} · ผู้จัดการฝ่าย`;
  });

  const users = [
    { id: 1, email: 'admin@glr.co.th', password: 'demo1234', name: 'ระบบผู้ดูแล', role: 'admin', employeeId: employees[20].id, active: true, createdAt: iso(2025, 1, 5) },
    { id: 2, email: 'hr@glr.co.th', password: 'demo1234', name: employees[20].nameTh, role: 'hr', employeeId: employees[20].id, active: true, createdAt: iso(2025, 1, 5) },
    { id: 3, email: 'director@glr.co.th', password: 'demo1234', name: 'คุณวิชัย ธนาคาร', role: 'director', employeeId: employees[0].id, active: true, createdAt: iso(2025, 1, 5) },
    { id: 4, email: 'employee@glr.co.th', password: 'demo1234', name: employees[8].nameTh, role: 'employee', employeeId: employees[8].id, active: true, createdAt: iso(2025, 2, 11) },
    { id: 5, email: 'supervisor@glr.co.th', password: 'demo1234', name: employees[2].nameTh, role: 'supervisor', employeeId: employees[2].id, active: true, createdAt: iso(2025, 2, 11) },
    // Sales module users
    { id: 6, email: 'sales@glr.co.th', password: 'demo1234', name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null, active: true, createdAt: iso(2025, 6, 1) },
    { id: 7, email: 'import@glr.co.th', password: 'demo1234', name: 'คุณนำเข้า พานิช', role: 'import', employeeId: null, active: true, createdAt: iso(2025, 6, 1) },
    { id: 8, email: 'ceo@glr.co.th', password: 'demo1234', name: 'คุณวิชัย ธนาคาร', role: 'ceo', employeeId: employees[0].id, active: true, createdAt: iso(2025, 6, 1) },
  ];

  const tickets = [
    {
      id: 1, code: 'PR-2026-0001', type: 'PRICE_REQUEST',
      title: 'ขอราคาสินค้ากลุ่มเสื้อ Summer Collection',
      status: 'approved', priority: 'HIGH',
      createdById: 6, createdByName: 'คุณสมหมาย ขายดี',
      assignedToId: 7, assignedToName: 'คุณนำเข้า พานิช',
      customerName: 'ห้างสรรพสินค้า Central',
      note: 'ต้องการราคาพิเศษ Summer 2026',
      createdAt: iso(2026, 6, 14), updatedAt: iso(2026, 6, 18), closedAt: null,
      items: [
        { id: 1, ticketId: 1, productCode: null, productName: 'เสื้อยืดคอกลม', size: 'M', color: 'ขาว', qty: 200, unit: 'ตัว', proposedPrice: 185, approvedPrice: 185, currency: 'THB', sortOrder: 0 },
        { id: 2, ticketId: 1, productCode: null, productName: 'เสื้อยืดคอกลม', size: 'L', color: 'ดำ', qty: 150, unit: 'ตัว', proposedPrice: 185, approvedPrice: 185, currency: 'THB', sortOrder: 1 },
        { id: 3, ticketId: 1, productCode: null, productName: 'กางเกงขาสั้น', size: 'L', color: 'กากี', qty: 100, unit: 'ตัว', proposedPrice: 320, approvedPrice: 320, currency: 'THB', sortOrder: 2 },
      ],
      events: [
        { id: 1, ticketId: 1, actorId: 6, actorName: 'คุณสมหมาย ขายดี', kind: 'CREATED', fromStatus: null, toStatus: 'draft', message: null, createdAt: iso(2026, 6, 14) + 'T09:00:00Z' },
        { id: 2, ticketId: 1, actorId: 6, actorName: 'คุณสมหมาย ขายดี', kind: 'SUBMITTED', fromStatus: 'draft', toStatus: 'submitted', message: null, createdAt: iso(2026, 6, 14) + 'T09:30:00Z' },
        { id: 3, ticketId: 1, actorId: 7, actorName: 'คุณนำเข้า พานิช', kind: 'PICKED_UP', fromStatus: 'submitted', toStatus: 'in_review', message: null, createdAt: iso(2026, 6, 15) + 'T08:00:00Z' },
        { id: 4, ticketId: 1, actorId: 7, actorName: 'คุณนำเข้า พานิช', kind: 'PRICE_PROPOSED', fromStatus: 'in_review', toStatus: 'price_proposed', message: 'ตรวจสอบราคาจากซัพพลายเออร์แล้ว', createdAt: iso(2026, 6, 17) + 'T14:00:00Z' },
        { id: 5, ticketId: 1, actorId: 8, actorName: 'คุณวิชัย ธนาคาร', kind: 'APPROVED', fromStatus: 'price_proposed', toStatus: 'approved', message: null, createdAt: iso(2026, 6, 18) + 'T10:00:00Z' },
      ],
      quotation: null,
    },
    {
      id: 2, code: 'PR-2026-0002', type: 'PRICE_REQUEST',
      title: 'ผ้าพิมพ์ลายสำหรับโปรเจกต์ ABC Corp',
      status: 'price_proposed', priority: 'NORMAL',
      createdById: 6, createdByName: 'คุณสมหมาย ขายดี',
      assignedToId: 7, assignedToName: 'คุณนำเข้า พานิช',
      customerName: 'ABC Corporation', note: null,
      createdAt: iso(2026, 6, 16), updatedAt: iso(2026, 6, 18), closedAt: null,
      items: [
        { id: 4, ticketId: 2, productCode: null, productName: 'ผ้าพิมพ์ลายดอก', size: '2 เมตร', color: null, qty: 500, unit: 'หลา', proposedPrice: 95, approvedPrice: null, currency: 'THB', sortOrder: 0 },
      ],
      events: [
        { id: 6, ticketId: 2, actorId: 6, actorName: 'คุณสมหมาย ขายดี', kind: 'CREATED', fromStatus: null, toStatus: 'draft', message: null, createdAt: iso(2026, 6, 16) + 'T10:00:00Z' },
        { id: 7, ticketId: 2, actorId: 6, actorName: 'คุณสมหมาย ขายดี', kind: 'SUBMITTED', fromStatus: 'draft', toStatus: 'submitted', message: null, createdAt: iso(2026, 6, 16) + 'T11:00:00Z' },
        { id: 8, ticketId: 2, actorId: 7, actorName: 'คุณนำเข้า พานิช', kind: 'PICKED_UP', fromStatus: 'submitted', toStatus: 'in_review', message: null, createdAt: iso(2026, 6, 17) + 'T08:00:00Z' },
        { id: 9, ticketId: 2, actorId: 7, actorName: 'คุณนำเข้า พานิช', kind: 'PRICE_PROPOSED', fromStatus: 'in_review', toStatus: 'price_proposed', message: null, createdAt: iso(2026, 6, 18) + 'T14:00:00Z' },
      ],
      quotation: null,
    },
    {
      id: 3, code: 'PR-2026-0003', type: 'PRICE_REQUEST',
      title: 'ชุดยูนิฟอร์มพนักงาน XYZ Co.',
      status: 'in_review', priority: 'HIGH',
      createdById: 6, createdByName: 'คุณสมหมาย ขายดี',
      assignedToId: 7, assignedToName: 'คุณนำเข้า พานิช',
      customerName: 'XYZ Co., Ltd.', note: null,
      createdAt: iso(2026, 6, 17), updatedAt: iso(2026, 6, 18), closedAt: null,
      items: [
        { id: 5, ticketId: 3, productCode: null, productName: 'เสื้อเชิ้ตยูนิฟอร์ม', size: 'M', color: 'ขาว', qty: 50, unit: 'ตัว', proposedPrice: null, approvedPrice: null, currency: 'THB', sortOrder: 0 },
        { id: 6, ticketId: 3, productCode: null, productName: 'กางเกงสแลค', size: '32', color: 'กรมท่า', qty: 50, unit: 'ตัว', proposedPrice: null, approvedPrice: null, currency: 'THB', sortOrder: 1 },
      ],
      events: [
        { id: 10, ticketId: 3, actorId: 6, actorName: 'คุณสมหมาย ขายดี', kind: 'CREATED', fromStatus: null, toStatus: 'draft', message: null, createdAt: iso(2026, 6, 17) + 'T09:00:00Z' },
        { id: 11, ticketId: 3, actorId: 6, actorName: 'คุณสมหมาย ขายดี', kind: 'SUBMITTED', fromStatus: 'draft', toStatus: 'submitted', message: null, createdAt: iso(2026, 6, 17) + 'T11:00:00Z' },
        { id: 12, ticketId: 3, actorId: 7, actorName: 'คุณนำเข้า พานิช', kind: 'PICKED_UP', fromStatus: 'submitted', toStatus: 'in_review', message: null, createdAt: iso(2026, 6, 18) + 'T08:30:00Z' },
      ],
      quotation: null,
    },
    {
      id: 4, code: 'PR-2026-0004', type: 'PRICE_REQUEST',
      title: 'กระเป๋าผ้านำเข้าจากจีน',
      status: 'submitted', priority: 'NORMAL',
      createdById: 6, createdByName: 'คุณสมหมาย ขายดี',
      assignedToId: null, assignedToName: null,
      customerName: 'ร้านค้าปลีก กรุงเทพ',
      note: 'ต้องการทราบราคานำเข้า MOQ',
      createdAt: iso(2026, 6, 18), updatedAt: iso(2026, 6, 18), closedAt: null,
      items: [
        { id: 7, ticketId: 4, productCode: null, productName: 'กระเป๋าผ้า Tote Bag', size: null, color: null, qty: 1000, unit: 'ใบ', proposedPrice: null, approvedPrice: null, currency: 'THB', sortOrder: 0 },
      ],
      events: [
        { id: 13, ticketId: 4, actorId: 6, actorName: 'คุณสมหมาย ขายดี', kind: 'CREATED', fromStatus: null, toStatus: 'draft', message: null, createdAt: iso(2026, 6, 18) + 'T07:00:00Z' },
        { id: 14, ticketId: 4, actorId: 6, actorName: 'คุณสมหมาย ขายดี', kind: 'SUBMITTED', fromStatus: 'draft', toStatus: 'submitted', message: null, createdAt: iso(2026, 6, 18) + 'T07:30:00Z' },
      ],
      quotation: null,
    },
    {
      id: 5, code: 'PR-2026-0005', type: 'PRICE_REQUEST',
      title: 'หมวกโปโลสำหรับ Event',
      status: 'draft', priority: 'LOW',
      createdById: 6, createdByName: 'คุณสมหมาย ขายดี',
      assignedToId: null, assignedToName: null,
      customerName: 'Event Organizer Co.', note: null,
      createdAt: iso(2026, 6, 19), updatedAt: iso(2026, 6, 19), closedAt: null,
      items: [
        { id: 8, ticketId: 5, productCode: null, productName: 'หมวกโปโลปัก Logo', size: 'Free Size', color: null, qty: 300, unit: 'ใบ', proposedPrice: null, approvedPrice: null, currency: 'THB', sortOrder: 0 },
      ],
      events: [
        { id: 15, ticketId: 5, actorId: 6, actorName: 'คุณสมหมาย ขายดี', kind: 'CREATED', fromStatus: null, toStatus: 'draft', message: null, createdAt: iso(2026, 6, 19) + 'T06:00:00Z' },
      ],
      quotation: null,
    },
    {
      id: 6, code: 'PR-2026-0006', type: 'PRICE_REQUEST',
      title: 'ผ้าไหมพรม Grade A สำหรับส่งออก EU',
      status: 'quotation_issued', priority: 'HIGH',
      createdById: 6, createdByName: 'คุณสมหมาย ขายดี',
      assignedToId: 7, assignedToName: 'คุณนำเข้า พานิช',
      customerName: 'EU Trading Co.', note: null,
      createdAt: iso(2026, 6, 9), updatedAt: iso(2026, 6, 16), closedAt: null,
      items: [
        { id: 9, ticketId: 6, productCode: null, productName: 'ผ้าไหมพรม Grade A', size: null, color: null, qty: 200, unit: 'หลา', proposedPrice: 1200, approvedPrice: 1200, currency: 'THB', sortOrder: 0 },
      ],
      events: [
        { id: 16, ticketId: 6, actorId: 6, actorName: 'คุณสมหมาย ขายดี', kind: 'CREATED', fromStatus: null, toStatus: 'draft', message: null, createdAt: iso(2026, 6, 9) + 'T09:00:00Z' },
        { id: 17, ticketId: 6, actorId: 6, actorName: 'คุณสมหมาย ขายดี', kind: 'SUBMITTED', fromStatus: 'draft', toStatus: 'submitted', message: null, createdAt: iso(2026, 6, 9) + 'T10:00:00Z' },
        { id: 18, ticketId: 6, actorId: 7, actorName: 'คุณนำเข้า พานิช', kind: 'PICKED_UP', fromStatus: 'submitted', toStatus: 'in_review', message: null, createdAt: iso(2026, 6, 10) + 'T08:00:00Z' },
        { id: 19, ticketId: 6, actorId: 7, actorName: 'คุณนำเข้า พานิช', kind: 'PRICE_PROPOSED', fromStatus: 'in_review', toStatus: 'price_proposed', message: null, createdAt: iso(2026, 6, 12) + 'T15:00:00Z' },
        { id: 20, ticketId: 6, actorId: 8, actorName: 'คุณวิชัย ธนาคาร', kind: 'APPROVED', fromStatus: 'price_proposed', toStatus: 'approved', message: null, createdAt: iso(2026, 6, 14) + 'T11:00:00Z' },
        { id: 21, ticketId: 6, actorId: 6, actorName: 'คุณสมหมาย ขายดี', kind: 'QUOTATION_ISSUED', fromStatus: 'approved', toStatus: 'quotation_issued', message: null, createdAt: iso(2026, 6, 16) + 'T09:00:00Z' },
      ],
      quotation: { id: 1, ticketId: 6, number: 'QT-2026-0001', issuedById: 6, issuedByName: 'คุณสมหมาย ขายดี', issuedAt: iso(2026, 6, 16) + 'T09:00:00Z', pdfPath: null, totalAmount: 240000, currency: 'THB' },
    },
  ];

  const notifications = [
    { id: 1, userId: 8, ticketId: 2, ticketCode: 'PR-2026-0002', type: 'PRICE_PROPOSED', message: 'Ticket PR-2026-0002 มีราคาเสนอรอการอนุมัติ', read: false, createdAt: iso(2026, 6, 18) + 'T14:00:00Z' },
    { id: 2, userId: 6, ticketId: 1, ticketCode: 'PR-2026-0001', type: 'APPROVED', message: 'Ticket PR-2026-0001 ได้รับการอนุมัติราคาแล้ว — กด Generate ใบเสนอราคาได้เลย', read: false, createdAt: iso(2026, 6, 18) + 'T10:00:00Z' },
    { id: 3, userId: 7, ticketId: 4, ticketCode: 'PR-2026-0004', type: 'SUBMITTED', message: 'Ticket PR-2026-0004 รอการรับเรื่อง', read: false, createdAt: iso(2026, 6, 18) + 'T07:30:00Z' },
    { id: 4, userId: 7, ticketId: 3, ticketCode: 'PR-2026-0003', type: 'PICKED_UP', message: 'Ticket PR-2026-0003 ถูกมอบหมายให้คุณแล้ว', read: true, createdAt: iso(2026, 6, 18) + 'T08:30:00Z' },
  ];

  const profileRequests = [
    { id: 101, employeeId: employees[8].id, fieldKey: 'phone', fieldLabel: 'เบอร์โทรศัพท์', oldValue: employees[8].phone, newValue: '089-555-2210', requestedBy: employees[8].nameTh, requestedAt: iso(2026, 6, 9), status: 'pending' },
    { id: 102, employeeId: employees[12].id, fieldKey: 'email', fieldLabel: 'อีเมล', oldValue: employees[12].email, newValue: 'w.new@glr.co.th', requestedBy: employees[12].nameTh, requestedAt: iso(2026, 6, 8), status: 'pending' },
    { id: 103, employeeId: employees[5].id, fieldKey: 'address', fieldLabel: 'ที่อยู่ปัจจุบัน', oldValue: employees[5].currentAddress.line1, newValue: '88/12 ซอยอ่อนนุช 17 เขตสวนหลวง กทม. 10250', requestedBy: employees[5].nameTh, requestedAt: iso(2026, 6, 7), status: 'pending' },
    { id: 104, employeeId: employees[19].id, fieldKey: 'emergency', fieldLabel: 'ผู้ติดต่อฉุกเฉิน', oldValue: `${employees[19].emergencyContact.name} · ${employees[19].emergencyContact.phone}`, newValue: 'คุณมานพ แสงทอง · 086-441-9920', requestedBy: employees[19].nameTh, requestedAt: iso(2026, 6, 5), status: 'pending' },
    { id: 105, employeeId: employees[2].id, fieldKey: 'phone', fieldLabel: 'เบอร์โทรศัพท์', oldValue: employees[2].phone, newValue: '081-902-3344', requestedBy: employees[2].nameTh, requestedAt: iso(2026, 6, 2), status: 'approved' },
  ];

  return { employees, users, profileRequests, tickets, notifications };
}
