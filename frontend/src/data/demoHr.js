// Demo seed data for HR self-service requests (leave / overtime / special money /
// profile-change requests) and notifications — state-matrix coverage for local
// VITE_USE_MOCKS=true development (see chore/mock-demo-seed-state-matrix).
//
// These used to live as two competing sources: a handful of rows returned by
// createDemoDatabase() itself, PLUS a set of `if (db.X.length === 0) {...}`-style
// blocks in mockApi.js. For leaveRequests/overtimeRequests those blocks were dead
// code (createDemoDatabase() already returned a non-empty array — see that file's
// git history). specialMoneyRequests was the exception (review fix, 2026-08-02):
// createDemoDatabase() never returned it, so that block was LIVE and was the sole
// source of the 5 special-money rows it seeded. This module is now the single
// source for all three; the 5 special-money rows below are those same rows,
// preserved verbatim.
//
// Field shapes here are taken from the handlers that actually read them
// (create/approve/reject/cancel in mockApi.js), not the old demoData.js rows —
// the old rows were missing fields (attachmentId, systemNote, reviewerNote,
// createdAt/updatedAt, the two-stage OT approval fields) that a real UI panel
// would otherwise render as `undefined`.

function iso(year, month, day) {
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

// ─────────────────────────────────────────────────────────────────────────────
// Leave requests — mirrors LeaveRequestService. Statuses actually reachable via
// approve()/reject()/cancel(): SUBMITTED, APPROVED, AUTO_REJECTED, REJECTED,
// CANCELLED. Leave types: PERSONAL (7 days/yr), SICK (30, requires attachment),
// VACATION (6). Quota fields (quotaRemainingBefore/After) are informational only
// — leaveBalance() derives the live remaining count from totalDays/status on the
// fly, so these just need to look consistent, not be load-bearing.
// ─────────────────────────────────────────────────────────────────────────────
export function buildDemoLeaveRequests(employees) {
  const now = new Date().toISOString();
  const at = (offsetDays) => {
    const d = new Date('2026-08-02T00:00:00Z');
    d.setUTCDate(d.getUTCDate() + offsetDays);
    return d.toISOString();
  };
  const row = (overrides) => ({
    attachmentId: null,
    attachmentFileName: null,
    quotaRemainingBefore: null,
    quotaRemainingAfter: null,
    systemNote: null,
    reviewedById: null,
    reviewedByName: null,
    reviewedAt: null,
    reviewerNote: null,
    cancelledAt: null,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  });

  const hrReviewer = { reviewedById: employees[20].id, reviewedByName: employees[20].nameTh };

  return [
    // SUBMITTED — pending HR review, spread across divisions/types
    row({
      id: 1, employeeId: employees[8].id, leaveTypeCode: 'VACATION',
      startDate: iso(2026, 8, 10), endDate: iso(2026, 8, 12), totalDays: 3, quotaYear: 2026,
      reason: 'พักผ่อนกับครอบครัวช่วงกลางเดือน', status: 'SUBMITTED',
      quotaRemainingBefore: 6, quotaRemainingAfter: 3,
      requestedById: employees[8].id, requestedByName: employees[8].nameTh, requestedAt: at(-13),
    }),
    row({
      id: 2, employeeId: employees[12].id, leaveTypeCode: 'SICK',
      startDate: iso(2026, 7, 29), endDate: iso(2026, 7, 29), totalDays: 1, quotaYear: 2026,
      reason: 'ไข้หวัดใหญ่ ตรวจที่คลินิกใกล้บ้าน', status: 'SUBMITTED',
      attachmentId: 201, attachmentFileName: 'clinic-note-2026-07-29.pdf',
      quotaRemainingBefore: 29, quotaRemainingAfter: 28,
      requestedById: employees[12].id, requestedByName: employees[12].nameTh, requestedAt: at(-4),
    }),
    row({
      id: 3, employeeId: employees[19].id, leaveTypeCode: 'PERSONAL',
      startDate: iso(2026, 8, 1), endDate: iso(2026, 8, 1), totalDays: 1, quotaYear: 2026,
      reason: 'ทำธุระที่เขต', status: 'SUBMITTED',
      quotaRemainingBefore: 7, quotaRemainingAfter: 6,
      requestedById: employees[19].id, requestedByName: employees[19].nameTh, requestedAt: at(-2),
    }),

    // APPROVED (incl. one quota-exhausted case)
    row({
      id: 4, employeeId: employees[18].id, leaveTypeCode: 'VACATION',
      startDate: iso(2026, 7, 10), endDate: iso(2026, 7, 11), totalDays: 2, quotaYear: 2026,
      reason: 'ธุระครอบครัวต่างจังหวัด', status: 'APPROVED',
      quotaRemainingBefore: 6, quotaRemainingAfter: 4,
      requestedById: employees[18].id, requestedByName: employees[18].nameTh, requestedAt: at(-25),
      ...hrReviewer, reviewedAt: at(-24),
    }),
    row({
      id: 5, employeeId: employees[2].id, leaveTypeCode: 'SICK',
      startDate: iso(2026, 7, 15), endDate: iso(2026, 7, 15), totalDays: 1, quotaYear: 2026,
      reason: 'ปวดท้อง ไปหาหมอ', status: 'APPROVED',
      attachmentId: 202, attachmentFileName: 'sick-note-2026-07-15.pdf',
      quotaRemainingBefore: 30, quotaRemainingAfter: 29,
      requestedById: employees[2].id, requestedByName: employees[2].nameTh, requestedAt: at(-19),
      ...hrReviewer, reviewedAt: at(-18),
    }),
    row({
      // Quota-exhausted case: uses the full 7-day PERSONAL annual quota in one request.
      id: 6, employeeId: employees[15].id, leaveTypeCode: 'PERSONAL',
      startDate: iso(2026, 6, 1), endDate: iso(2026, 6, 9), totalDays: 7, quotaYear: 2026,
      reason: 'ลากิจสะสมทั้งปี ดูแลบิดาป่วย', status: 'APPROVED',
      quotaRemainingBefore: 7, quotaRemainingAfter: 0,
      requestedById: employees[15].id, requestedByName: employees[15].nameTh, requestedAt: at(-63),
      ...hrReviewer, reviewedAt: at(-62),
    }),

    // AUTO_REJECTED — the create() handler's own synchronous rule (quota unavailable /
    // SICK with no attachment), so systemNote must be non-null and reviewedBy* stays null.
    row({
      id: 7, employeeId: employees[23].id, leaveTypeCode: 'VACATION',
      startDate: iso(2026, 8, 1), endDate: iso(2026, 8, 8), totalDays: 8, quotaYear: 2026,
      reason: 'ทริปครอบครัวต่างประเทศ', status: 'AUTO_REJECTED',
      systemNote: 'โควตาวันลาคงเหลือไม่เพียงพอ (ระบบปฏิเสธอัตโนมัติ)',
      quotaRemainingBefore: 6, quotaRemainingAfter: 6,
      requestedById: employees[23].id, requestedByName: employees[23].nameTh, requestedAt: at(-15),
    }),
    row({
      id: 8, employeeId: employees[27].id, leaveTypeCode: 'SICK',
      startDate: iso(2026, 7, 5), endDate: iso(2026, 7, 5), totalDays: 1, quotaYear: 2026,
      reason: 'ปวดหัว', status: 'AUTO_REJECTED',
      systemNote: 'ลาป่วยต้องแนบใบรับรองแพทย์ (ระบบปฏิเสธอัตโนมัติ)',
      quotaRemainingBefore: 30, quotaRemainingAfter: 30,
      requestedById: employees[27].id, requestedByName: employees[27].nameTh, requestedAt: at(-28),
    }),

    // REJECTED (by reviewer, not the system)
    row({
      id: 9, employeeId: employees[0].id, leaveTypeCode: 'VACATION',
      startDate: iso(2026, 8, 15), endDate: iso(2026, 8, 17), totalDays: 3, quotaYear: 2026,
      reason: 'พักผ่อนช่วงกลางเดือน', status: 'REJECTED',
      quotaRemainingBefore: 6, quotaRemainingAfter: 6,
      requestedById: employees[0].id, requestedByName: employees[0].nameTh, requestedAt: at(-10),
      ...hrReviewer, reviewedAt: at(-9), reviewerNote: 'ช่วงเวลาดังกล่าวมีงานสำคัญของฝ่าย ขอให้เลื่อนวันลา',
    }),
    row({
      id: 10, employeeId: employees[29].id, leaveTypeCode: 'PERSONAL',
      startDate: iso(2026, 7, 25), endDate: iso(2026, 7, 25), totalDays: 1, quotaYear: 2026,
      reason: 'ธุระส่วนตัว', status: 'REJECTED',
      quotaRemainingBefore: 7, quotaRemainingAfter: 7,
      requestedById: employees[29].id, requestedByName: employees[29].nameTh, requestedAt: at(-8),
      ...hrReviewer, reviewedAt: at(-7), reviewerNote: 'เอกสารประกอบไม่ครบถ้วน กรุณายื่นใหม่',
    }),

    // CANCELLED — one cancelled while still pending, one cancelled after approval.
    row({
      id: 11, employeeId: employees[8].id, leaveTypeCode: 'SICK',
      startDate: iso(2026, 7, 1), endDate: iso(2026, 7, 1), totalDays: 1, quotaYear: 2026,
      reason: 'ไม่สบาย', status: 'CANCELLED',
      quotaRemainingBefore: 30, quotaRemainingAfter: 29,
      requestedById: employees[8].id, requestedByName: employees[8].nameTh, requestedAt: at(-32),
      cancelledAt: at(-31),
    }),
    row({
      id: 12, employeeId: employees[12].id, leaveTypeCode: 'VACATION',
      startDate: iso(2026, 6, 20), endDate: iso(2026, 6, 21), totalDays: 2, quotaYear: 2026,
      reason: 'ทริปเพื่อน ยกเลิกกะทันหัน', status: 'CANCELLED',
      quotaRemainingBefore: 6, quotaRemainingAfter: 4,
      requestedById: employees[12].id, requestedByName: employees[12].nameTh, requestedAt: at(-45),
      ...hrReviewer, reviewedAt: at(-44), cancelledAt: at(-40),
    }),
  ];
}

// ─────────────────────────────────────────────────────────────────────────────
// Overtime requests — mirrors OvertimeRequestService. Two-stage approval:
// SUBMITTED -> MANAGER_APPROVED -> APPROVED (CEO), or REJECTED at either stage;
// CANCELLED while SUBMITTED/MANAGER_APPROVED/APPROVED. dayType WORKDAY (x1.5) /
// HOLIDAY (x3). Two backdated rows: one inside the 60-day retroactive window
// (OT_RETROACTIVE_WINDOW_DAYS), one representing an older historical entry
// outside it (imported by some other means — create() itself would reject it,
// but a pre-existing row is a legitimate thing for the UI to display).
// ─────────────────────────────────────────────────────────────────────────────
export function buildDemoOvertimeRequests(employees) {
  const now = new Date().toISOString();
  const at = (offsetDays) => {
    const d = new Date('2026-08-02T00:00:00Z');
    d.setUTCDate(d.getUTCDate() + offsetDays);
    return d.toISOString();
  };
  const row = (overrides) => ({
    plannedStartAt: null,
    plannedEndAt: null,
    actualMinutes: null,
    payableMinutes: null,
    calculationNote: null,
    managerApprovedBy: null,
    managerApprovedByName: null,
    managerApprovedAt: null,
    ceoApprovedBy: null,
    ceoApprovedByName: null,
    ceoApprovedAt: null,
    reviewedById: null,
    reviewedByName: null,
    reviewedAt: null,
    reviewerNote: null,
    cancelledAt: null,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  });

  const whlManager = { id: employees[5].id, name: employees[5].nameTh };
  const salManager = { id: employees[0].id, name: employees[0].nameTh };
  const finManager = { id: employees[15].id, name: employees[15].nameTh };
  const ceo = { id: employees[0].id, name: employees[0].nameTh };

  return [
    // SUBMITTED
    row({
      id: 1, employeeId: employees[8].id, workDate: iso(2026, 8, 5),
      plannedStartAt: '2026-08-05T18:00:00+07:00', plannedEndAt: '2026-08-05T20:00:00+07:00',
      plannedMinutes: 120, dayType: 'WORKDAY', reason: 'เร่งปิดยอดต้นเดือน', status: 'SUBMITTED',
      requestedById: employees[8].id, requestedByName: employees[8].nameTh, requestedAt: at(-13),
    }),
    row({
      id: 2, employeeId: employees[12].id, workDate: iso(2026, 8, 3),
      plannedStartAt: '2026-08-03T09:00:00+07:00', plannedEndAt: '2026-08-03T13:00:00+07:00',
      plannedMinutes: 240, dayType: 'HOLIDAY', reason: 'ตรวจนับสต็อกวันหยุด', status: 'SUBMITTED',
      requestedById: employees[12].id, requestedByName: employees[12].nameTh, requestedAt: at(-11),
    }),
    row({
      // Backdated, inside the 60-day retroactive window — reason >= 20 chars.
      id: 3, employeeId: employees[5].id, workDate: iso(2026, 6, 10),
      plannedStartAt: '2026-06-10T18:00:00+07:00', plannedEndAt: '2026-06-10T20:30:00+07:00',
      plannedMinutes: 150, dayType: 'WORKDAY',
      reason: 'เข้าช่วยแก้ไขปัญหาระบบคลังสินค้าเร่งด่วนช่วงปลายเดือน', status: 'SUBMITTED',
      requestedById: employees[5].id, requestedByName: employees[5].nameTh, requestedAt: at(-53),
    }),

    // MANAGER_APPROVED (stage 1 only, no CEO action yet)
    row({
      id: 4, employeeId: employees[2].id, workDate: iso(2026, 7, 20),
      plannedStartAt: '2026-07-20T17:00:00+07:00', plannedEndAt: '2026-07-20T18:30:00+07:00',
      plannedMinutes: 90, dayType: 'WORKDAY', reason: 'ปิดงานเอกสารลูกค้าด่วน', status: 'MANAGER_APPROVED',
      requestedById: employees[2].id, requestedByName: employees[2].nameTh, requestedAt: at(-13),
      managerApprovedBy: salManager.id, managerApprovedByName: salManager.name, managerApprovedAt: at(-12),
    }),
    row({
      id: 5, employeeId: employees[15].id, workDate: iso(2026, 7, 18),
      plannedStartAt: '2026-07-18T09:00:00+07:00', plannedEndAt: '2026-07-18T12:00:00+07:00',
      plannedMinutes: 180, dayType: 'HOLIDAY', reason: 'ปิดงบประจำเดือนวันหยุด', status: 'MANAGER_APPROVED',
      requestedById: employees[15].id, requestedByName: employees[15].nameTh, requestedAt: at(-15),
      managerApprovedBy: finManager.id, managerApprovedByName: finManager.name, managerApprovedAt: at(-14),
    }),

    // APPROVED (both stages complete, actual/payable minutes computed)
    row({
      id: 6, employeeId: employees[19].id, workDate: iso(2026, 7, 10),
      plannedStartAt: '2026-07-10T18:00:00+07:00', plannedEndAt: '2026-07-10T20:00:00+07:00',
      plannedMinutes: 120, actualMinutes: 125, payableMinutes: 188, dayType: 'WORKDAY',
      reason: 'ปิดยอดกลางเดือน', status: 'APPROVED',
      requestedById: employees[19].id, requestedByName: employees[19].nameTh, requestedAt: at(-23),
      managerApprovedBy: finManager.id, managerApprovedByName: finManager.name, managerApprovedAt: at(-22),
      ceoApprovedBy: ceo.id, ceoApprovedByName: ceo.name, ceoApprovedAt: at(-21),
    }),
    row({
      id: 7, employeeId: employees[23].id, workDate: iso(2026, 7, 5),
      plannedStartAt: '2026-07-05T09:00:00+07:00', plannedEndAt: '2026-07-05T12:00:00+07:00',
      plannedMinutes: 180, actualMinutes: 180, payableMinutes: 540, dayType: 'HOLIDAY',
      reason: 'ซ่อมบำรุงเซิร์ฟเวอร์ประจำเดือน', status: 'APPROVED',
      requestedById: employees[23].id, requestedByName: employees[23].nameTh, requestedAt: at(-28),
      managerApprovedBy: employees[22].id, managerApprovedByName: employees[22].nameTh, managerApprovedAt: at(-27),
      ceoApprovedBy: ceo.id, ceoApprovedByName: ceo.name, ceoApprovedAt: at(-26),
    }),
    row({
      // Historical entry outside the 60-day retroactive window — a legitimate
      // pre-existing row (e.g. imported), even though a fresh create() call
      // dated this far back would now be rejected.
      id: 8, employeeId: employees[29].id, workDate: iso(2026, 5, 15),
      plannedStartAt: '2026-05-15T08:00:00+07:00', plannedEndAt: '2026-05-15T11:20:00+07:00',
      plannedMinutes: 200, actualMinutes: 200, payableMinutes: 600, dayType: 'HOLIDAY',
      reason: 'ปฏิบัติงานเก็บข้อมูลสต็อกประจำปีที่คลังสินค้าใหญ่', status: 'APPROVED',
      requestedById: employees[29].id, requestedByName: employees[29].nameTh, requestedAt: at(-79),
      managerApprovedBy: employees[22].id, managerApprovedByName: employees[22].nameTh, managerApprovedAt: at(-78),
      ceoApprovedBy: ceo.id, ceoApprovedByName: ceo.name, ceoApprovedAt: at(-77),
    }),

    // REJECTED (one at each stage)
    row({
      id: 9, employeeId: employees[0].id, workDate: iso(2026, 7, 25),
      plannedStartAt: '2026-07-25T17:00:00+07:00', plannedEndAt: '2026-07-25T18:00:00+07:00',
      plannedMinutes: 60, dayType: 'WORKDAY', reason: 'งานเอกสารค้าง', status: 'REJECTED',
      requestedById: employees[0].id, requestedByName: employees[0].nameTh, requestedAt: at(-8),
      reviewedById: whlManager.id, reviewedByName: whlManager.name, reviewedAt: at(-7),
      reviewerNote: 'ไม่มีความจำเป็นต้องทำล่วงเวลา',
    }),
    row({
      id: 10, employeeId: employees[27].id, workDate: iso(2026, 7, 22),
      plannedStartAt: '2026-07-22T09:00:00+07:00', plannedEndAt: '2026-07-22T11:00:00+07:00',
      plannedMinutes: 120, dayType: 'HOLIDAY', reason: 'ตรวจสอบยอดบัญชี', status: 'REJECTED',
      requestedById: employees[27].id, requestedByName: employees[27].nameTh, requestedAt: at(-10),
      managerApprovedBy: finManager.id, managerApprovedByName: finManager.name, managerApprovedAt: at(-9),
      reviewedById: ceo.id, reviewedByName: ceo.name, reviewedAt: at(-8),
      reviewerNote: 'เกินงบประมาณ OT ของแผนกในเดือนนี้',
    }),

    // CANCELLED (one while pending, one after full approval)
    row({
      id: 11, employeeId: employees[12].id, workDate: iso(2026, 7, 15),
      plannedStartAt: '2026-07-15T18:00:00+07:00', plannedEndAt: '2026-07-15T19:30:00+07:00',
      plannedMinutes: 90, dayType: 'WORKDAY', reason: 'ช่วยปิดงานลูกค้า', status: 'CANCELLED',
      requestedById: employees[12].id, requestedByName: employees[12].nameTh, requestedAt: at(-18),
      cancelledAt: at(-17),
    }),
    row({
      id: 12, employeeId: employees[8].id, workDate: iso(2026, 7, 8),
      plannedStartAt: '2026-07-08T08:00:00+07:00', plannedEndAt: '2026-07-08T13:00:00+07:00',
      plannedMinutes: 300, actualMinutes: 300, payableMinutes: 900, dayType: 'HOLIDAY',
      reason: 'ช่วยขนย้ายสต็อกวันหยุด', status: 'CANCELLED',
      requestedById: employees[8].id, requestedByName: employees[8].nameTh, requestedAt: at(-25),
      managerApprovedBy: whlManager.id, managerApprovedByName: whlManager.name, managerApprovedAt: at(-24),
      ceoApprovedBy: ceo.id, ceoApprovedByName: ceo.name, ceoApprovedAt: at(-23),
      cancelledAt: at(-20),
    }),
  ];
}

// ─────────────────────────────────────────────────────────────────────────────
// Special money requests — mirrors SpecialMoneyService / SPECIAL_MONEY_TYPES
// (12 kinds). Keeps the 5 rows already covering MEDICAL/TRAVEL_PER_DIEM/
// AID_WEDDING/UNIFORM_ANNUAL/TRAINING, and adds the remaining 7 kinds so every
// SPECIAL_MONEY_TYPES entry has at least one row.
// ─────────────────────────────────────────────────────────────────────────────
export function buildDemoSpecialMoneyRequests(employees) {
  const now = new Date().toISOString();
  const row = (overrides) => ({
    eventEndDate: null,
    receiptDate: null,
    approvedAmount: null,
    detail: {},
    payrollMonth: null,
    capOverrideReason: null,
    managerApprovedBy: null,
    managerApprovedAt: null,
    ceoApprovedBy: null,
    ceoApprovedAt: null,
    reviewedById: null,
    reviewedAt: null,
    reviewerNote: null,
    cancelledAt: null,
    createdAt: now,
    updatedAt: now,
    policyVersion: 1,
    ...overrides,
  });

  return [
    row({
      id: 1, employeeId: employees[8].id, requestType: 'MEDICAL',
      eventDate: '2026-06-20', receiptDate: '2026-06-20', quantity: 1,
      requestedAmount: 1200, payrollBucket: 'NON_TAXABLE',
      reason: 'ค่ารักษาพยาบาลตรวจสุขภาพประจำปี', status: 'SUBMITTED',
      requestedById: employees[8].id, requestedAt: now,
    }),
    row({
      id: 2, employeeId: employees[12].id, requestType: 'TRAVEL_PER_DIEM',
      eventDate: '2026-07-10', eventEndDate: '2026-07-12', quantity: 3,
      requestedAmount: 1200, payrollBucket: 'PER_DIEM',
      reason: 'เดินทางส่งของลูกค้าต่างจังหวัด',
      detail: { destination: 'DOMESTIC', province: 'เชียงใหม่', role: 'driver' },
      status: 'MANAGER_APPROVED',
      requestedById: employees[12].id, requestedAt: now,
      managerApprovedBy: employees[20].id, managerApprovedAt: now,
      reviewedById: employees[20].id, reviewedAt: now,
    }),
    row({
      id: 3, employeeId: employees[3].id, requestType: 'AID_WEDDING',
      eventDate: '2026-05-15', quantity: 1,
      requestedAmount: 5000, approvedAmount: 5000, payrollBucket: 'AID',
      reason: 'เงินช่วยเหลืองานแต่งงาน', status: 'APPROVED', payrollMonth: '2026-06-01',
      requestedById: employees[3].id, requestedAt: now,
      managerApprovedBy: employees[20].id, managerApprovedAt: now,
      ceoApprovedBy: employees[0].id, ceoApprovedAt: now,
      reviewedById: employees[0].id, reviewedAt: now,
    }),
    row({
      id: 4, employeeId: employees[8].id, requestType: 'UNIFORM_ANNUAL',
      eventDate: '2026-06-05', receiptDate: '2026-05-28', quantity: 2,
      requestedAmount: 4000, payrollBucket: 'NON_TAXABLE',
      reason: 'ชุดฟอร์มประจำปี ตัดใหม่จำนวน 2 ชิ้น',
      detail: { uniformMode: 'SELF_BUY', shirtCount: '3', trouserCount: '2' },
      status: 'REJECTED',
      requestedById: employees[8].id, requestedAt: now,
      reviewedById: employees[20].id, reviewedAt: now,
      reviewerNote: 'จำนวนชิ้นเกินเพดานต่อปี กรุณาส่งคำขอใหม่ให้ตรงเพดาน',
    }),
    row({
      id: 5, employeeId: employees[12].id, requestType: 'TRAINING',
      eventDate: '2026-06-01', quantity: 1,
      requestedAmount: 2500, payrollBucket: 'NON_TAXABLE',
      reason: 'อบรมหลักสูตรความปลอดภัยในการขับขี่', status: 'CANCELLED',
      requestedById: employees[12].id, requestedAt: now, cancelledAt: now,
    }),
    // ── Remaining SPECIAL_MONEY_TYPES kinds ──
    row({
      id: 6, employeeId: employees[27].id, requestType: 'UNIFORM_NEW_STAFF',
      eventDate: '2026-07-01', receiptDate: '2026-06-28', quantity: 1,
      requestedAmount: 1800, payrollBucket: 'NON_TAXABLE',
      reason: 'ชุดฟอร์มพนักงานใหม่', status: 'SUBMITTED',
      requestedById: employees[27].id, requestedAt: now,
    }),
    row({
      id: 7, employeeId: employees[29].id, requestType: 'UNIFORM_PREPROBATION_KIT',
      eventDate: '2026-07-15', receiptDate: '2026-07-14', quantity: 1,
      requestedAmount: 1200, payrollBucket: 'NON_TAXABLE',
      reason: 'ชุดพนักงานก่อนผ่านทดลองงาน', status: 'MANAGER_APPROVED',
      requestedById: employees[29].id, requestedAt: now,
      managerApprovedBy: employees[22].id, managerApprovedAt: now,
      reviewedById: employees[22].id, reviewedAt: now,
    }),
    row({
      id: 8, employeeId: employees[19].id, requestType: 'TRAVEL_LODGING',
      eventDate: '2026-07-20', eventEndDate: '2026-07-21', receiptDate: '2026-07-21', quantity: 1,
      requestedAmount: 1500, approvedAmount: 1500, payrollBucket: 'PER_DIEM',
      reason: 'ค่าที่พักออกตรวจสอบสาขาต่างจังหวัด', status: 'APPROVED', payrollMonth: '2026-07-01',
      requestedById: employees[19].id, requestedAt: now,
      managerApprovedBy: employees[15].id, managerApprovedAt: now,
      ceoApprovedBy: employees[0].id, ceoApprovedAt: now,
      reviewedById: employees[0].id, reviewedAt: now,
    }),
    row({
      id: 9, employeeId: employees[5].id, requestType: 'AID_ORDINATION',
      eventDate: '2026-06-10', quantity: 1,
      requestedAmount: 3000, payrollBucket: 'AID',
      reason: 'เงินช่วยเหลืองานบวช', status: 'APPROVED', payrollMonth: '2026-06-01',
      approvedAmount: 3000,
      requestedById: employees[5].id, requestedAt: now,
      managerApprovedBy: employees[5].id, managerApprovedAt: now,
      ceoApprovedBy: employees[0].id, ceoApprovedAt: now,
      reviewedById: employees[0].id, reviewedAt: now,
    }),
    row({
      id: 10, employeeId: employees[16].id, requestType: 'AID_CHILDBIRTH',
      eventDate: '2026-07-02', quantity: 1,
      requestedAmount: 3000, payrollBucket: 'AID',
      reason: 'เงินช่วยเหลือคลอดบุตร', status: 'SUBMITTED',
      requestedById: employees[16].id, requestedAt: now,
    }),
    row({
      id: 11, employeeId: employees[24].id, requestType: 'AID_FUNERAL',
      eventDate: '2026-05-20', quantity: 1,
      requestedAmount: 5000, payrollBucket: 'AID',
      reason: 'เงินช่วยเหลืองานศพบิดา', status: 'REJECTED',
      requestedById: employees[24].id, requestedAt: now,
      reviewedById: employees[22].id, reviewedAt: now,
      reviewerNote: 'เอกสารหลักฐานไม่ครบ กรุณาแนบใบมรณบัตร',
    }),
    row({
      id: 12, employeeId: employees[2].id, requestType: 'OTHER',
      eventDate: '2026-07-18', quantity: 1,
      requestedAmount: 800, payrollBucket: 'AID',
      reason: 'ค่าเดินทางกรณีพิเศษนอกนโยบายมาตรฐาน', status: 'MANAGER_APPROVED',
      requestedById: employees[2].id, requestedAt: now,
      managerApprovedBy: employees[0].id, managerApprovedAt: now,
      reviewedById: employees[0].id, reviewedAt: now,
    }),
  ];
}

// ─────────────────────────────────────────────────────────────────────────────
// Profile-change requests — mirrors ProfileRequestService. Only 'pending' and
// 'approved' are literal-checked in code (approved triggers the employee-field
// apply side effect); 'rejected' is exercised by mockApi.profileRequests.test.js
// and is a legitimate free-form status value the UI renders.
// ─────────────────────────────────────────────────────────────────────────────
export function buildDemoProfileRequests(employees) {
  return [
    { id: 101, employeeId: employees[8].id, fieldKey: 'phone', fieldLabel: 'เบอร์โทรศัพท์', oldValue: employees[8].phone, newValue: '089-555-2210', requestedBy: employees[8].nameTh, requestedAt: '2026-06-09', status: 'pending' },
    { id: 102, employeeId: employees[12].id, fieldKey: 'email', fieldLabel: 'อีเมล', oldValue: employees[12].email, newValue: 'w.new@glr.co.th', requestedBy: employees[12].nameTh, requestedAt: '2026-06-08', status: 'pending' },
    { id: 103, employeeId: employees[5].id, fieldKey: 'address', fieldLabel: 'ที่อยู่ปัจจุบัน', oldValue: employees[5].currentAddress.line1, newValue: '88/12 ซอยอ่อนนุช 17 เขตสวนหลวง กทม. 10250', requestedBy: employees[5].nameTh, requestedAt: '2026-06-07', status: 'pending' },
    { id: 104, employeeId: employees[19].id, fieldKey: 'emergency', fieldLabel: 'ผู้ติดต่อฉุกเฉิน', oldValue: `${employees[19].emergencyContact.name} · ${employees[19].emergencyContact.phone}`, newValue: 'คุณมานพ แสงทอง · 086-441-9920', requestedBy: employees[19].nameTh, requestedAt: '2026-06-05', status: 'pending' },
    { id: 105, employeeId: employees[2].id, fieldKey: 'phone', fieldLabel: 'เบอร์โทรศัพท์', oldValue: employees[2].phone, newValue: '081-902-3344', requestedBy: employees[2].nameTh, requestedAt: '2026-06-02', status: 'approved' },
    { id: 106, employeeId: employees[23].id, fieldKey: 'email', fieldLabel: 'อีเมล', oldValue: employees[23].email, newValue: 'it.new@glr.co.th', requestedBy: employees[23].nameTh, requestedAt: '2026-07-14', status: 'pending' },
    { id: 107, employeeId: employees[27].id, fieldKey: 'address', fieldLabel: 'ที่อยู่ปัจจุบัน', oldValue: employees[27].currentAddress.line1, newValue: '25/4 ถ.รัชดาภิเษก แขวงดินแดง กทม. 10400', requestedBy: employees[27].nameTh, requestedAt: '2026-07-12', status: 'pending' },
    { id: 108, employeeId: employees[20].id, fieldKey: 'phone', fieldLabel: 'เบอร์โทรศัพท์', oldValue: employees[20].phone, newValue: '082-000-1122', requestedBy: employees[20].nameTh, requestedAt: '2026-06-25', status: 'rejected', reviewerNote: 'ข้อมูลไม่ตรงกับที่แจ้งไว้ กรุณายืนยันใหม่' },
  ];
}

// ─────────────────────────────────────────────────────────────────────────────
// Notifications — free-form `type`; addNotification() literals seen in the
// ticket/pricing-request flow, plus the original 4 legacy seed rows.
// userId is a db.users[].id (login/account id), NOT an employeeId.
// ─────────────────────────────────────────────────────────────────────────────
export function buildDemoNotifications() {
  return [
    { id: 1, userId: 8, ticketId: 2, ticketCode: 'PR-2026-0002', type: 'PRICE_PROPOSED', message: 'Ticket PR-2026-0002 มีราคาเสนอรอการอนุมัติ', read: false, createdAt: '2026-06-18T14:00:00Z' },
    { id: 2, userId: 6, ticketId: 1, ticketCode: 'PR-2026-0001', type: 'APPROVED', message: 'Ticket PR-2026-0001 ได้รับการอนุมัติราคาแล้ว — กด Generate ใบเสนอราคาได้เลย', read: false, createdAt: '2026-06-18T10:00:00Z' },
    { id: 3, userId: 7, ticketId: 4, ticketCode: 'PR-2026-0004', type: 'SUBMITTED', message: 'Ticket PR-2026-0004 รอการรับเรื่อง', read: false, createdAt: '2026-06-18T07:30:00Z' },
    { id: 4, userId: 7, ticketId: 3, ticketCode: 'PR-2026-0003', type: 'PICKED_UP', message: 'Ticket PR-2026-0003 ถูกมอบหมายให้คุณแล้ว', read: true, createdAt: '2026-06-18T08:30:00Z' },
    { id: 5, userId: 8, ticketId: 4, ticketCode: 'PR-2026-0004', type: 'PRICING_DECISION_APPROVED', message: 'คำขอราคา PCR-2026-0008 ได้รับการอนุมัติราคาขายแล้ว', read: false, createdAt: '2026-07-20T11:00:00Z' },
    // D6 fix (review pass): was 'QT-2026-0010' — only QT-2026-0001..0008 ever get created
    // (demoData.js's 7 legacy inline ticket.quotation rows + demoSales.js's own quotationSeq,
    // which tops out at 8 on ticket 18). Ticket 5's real ISSUED quotation is QT-2026-0002 —
    // see demoSales.js's PR10 (ticketId: 5), whose second makeQuotation() call is the ISSUED
    // revision of the REJECTED/SUPERSEDED q10Old.
    { id: 6, userId: 6, ticketId: 5, ticketCode: 'PR-2026-0005', type: 'CUSTOMER_QUOTATION_ISSUED', message: 'ใบเสนอราคา QT-2026-0002 ถูกออกแล้ว', read: true, createdAt: '2026-07-21T09:00:00Z' },
    { id: 7, userId: 7, ticketId: 7, ticketCode: 'PR-2026-0007', type: 'ORDER_CONFIRMED', message: 'ลูกค้ายืนยันคำสั่งซื้อของดีล PR-2026-0007 แล้ว', read: false, createdAt: '2026-07-25T13:00:00Z' },
    { id: 8, userId: 12, ticketId: 16, ticketCode: 'PR-2026-0016', type: 'PRICING_REQUEST_SUBMITTED', message: 'คำขอราคาของคุณถูกส่งเข้าคิวฝ่ายนำเข้าแล้ว', read: false, createdAt: '2026-07-28T10:00:00Z' },
  ];
}
