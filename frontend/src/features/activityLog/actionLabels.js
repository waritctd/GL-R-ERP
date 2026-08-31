// Thai labels for hr.audit_log action codes.
//
// The raw codes are readable to a developer and to nobody else, and this page exists so a
// non-developer can answer "who requested what, who approved what". Anything missing falls back to
// the raw code rather than to a blank — a new action added on the backend must degrade to
// something you can still search for, not disappear.

const ACTION_LABELS = {
  // Auth
  LOGIN: 'เข้าสู่ระบบ',
  CHANGE_PASSWORD: 'เปลี่ยนรหัสผ่าน',
  RESET_EMPLOYEE_PASSWORD: 'รีเซ็ตรหัสผ่านพนักงาน',

  // Leave
  SUBMIT_LEAVE_REQUEST: 'ยื่นใบลา',
  APPROVE_LEAVE_REQUEST: 'อนุมัติใบลา',
  REJECT_LEAVE_REQUEST: 'ไม่อนุมัติใบลา',
  CANCEL_LEAVE_REQUEST: 'ยกเลิกใบลา',

  // Overtime
  SUBMIT_OVERTIME_REQUEST: 'ยื่นขอโอที',
  MANAGER_APPROVE_OVERTIME_REQUEST: 'ผู้จัดการอนุมัติโอที',
  CEO_APPROVE_OVERTIME_REQUEST: 'CEO อนุมัติโอที',
  CEO_REJECT_OVERTIME_REQUEST: 'CEO ไม่อนุมัติโอที',
  CEO_DIRECT_APPROVE_OVERTIME_REQUEST: 'CEO อนุมัติโอทีโดยตรง',
  CEO_DIRECT_REJECT_OVERTIME_REQUEST: 'CEO ไม่อนุมัติโอทีโดยตรง',
  REJECT_OVERTIME_REQUEST: 'ไม่อนุมัติโอที',
  CANCEL_OVERTIME_REQUEST: 'ยกเลิกโอที',
  MANUAL_INSERT_APPROVED_OVERTIME: 'บันทึกโอทีย้อนหลัง',

  // Welfare / special money
  SUBMIT_SPECIAL_MONEY_REQUEST: 'ยื่นขอสวัสดิการ',
  CEO_APPROVE_SPECIAL_MONEY_REQUEST: 'CEO อนุมัติสวัสดิการ',
  CEO_APPROVE_LEGACY_MANAGER_APPROVED_SPECIAL_MONEY_REQUEST: 'CEO อนุมัติสวัสดิการ (เคสเดิม)',
  CEO_REJECT_SPECIAL_MONEY_REQUEST: 'CEO ไม่อนุมัติสวัสดิการ',
  CANCEL_SPECIAL_MONEY_REQUEST: 'ยกเลิกคำขอสวัสดิการ',

  // Tax allowance (ล.ย.01)
  SUBMIT_TAX_ALLOWANCE_DECLARATION: 'ยื่น ล.ย.01',
  APPROVE_TAX_ALLOWANCE_DECLARATION: 'อนุมัติ ล.ย.01',
  REJECT_TAX_ALLOWANCE_DECLARATION: 'ไม่อนุมัติ ล.ย.01',
  REVERIFY_TAX_ALLOWANCE_DECLARATION: 'ตรวจสอบ ล.ย.01 ซ้ำ',
  WITHDRAW_TAX_ALLOWANCE_DECLARATION: 'ถอน ล.ย.01',
  APPLY_TAX_ALLOWANCE_DECLARATION: 'นำ ล.ย.01 ไปใช้',
  CREATE_TAX_ALLOWANCE_DECLARATION_ON_BEHALF: 'สร้าง ล.ย.01 แทนพนักงาน',
  UPLOAD_TAX_ALLOWANCE_ATTACHMENT: 'แนบเอกสาร ล.ย.01',
  DELETE_TAX_ALLOWANCE_ATTACHMENT: 'ลบเอกสาร ล.ย.01',
  VIEW_OWN_TAX_ALLOWANCE_DECLARATIONS: 'เปิดดู ล.ย.01 ของตนเอง',
  UPSERT_TAX_ALLOWANCES: 'แก้ไขค่าลดหย่อน',
  WRITE_BACK_TAX_ALLOWANCE_HEADER: 'บันทึกหัวเอกสาร ล.ย.01',

  // Attendance correction
  SUBMIT_ATTENDANCE_CORRECTION_REQUEST: 'ยื่นขอแก้ไขเวลาทำงาน',
  APPROVE_ATTENDANCE_CORRECTION_REQUEST: 'อนุมัติแก้ไขเวลาทำงาน',
  REJECT_ATTENDANCE_CORRECTION_REQUEST: 'ไม่อนุมัติแก้ไขเวลาทำงาน',
  CANCEL_ATTENDANCE_CORRECTION_REQUEST: 'ยกเลิกคำขอแก้ไขเวลาทำงาน',

  // Profile
  SUBMIT_PROFILE_REQUEST: 'ยื่นขอแก้ไขข้อมูลส่วนตัว',
  APPROVE_PROFILE_REQUEST: 'อนุมัติแก้ไขข้อมูลส่วนตัว',
  REJECT_PROFILE_REQUEST: 'ไม่อนุมัติแก้ไขข้อมูลส่วนตัว',

  // Employee master
  CREATE_EMPLOYEE: 'เพิ่มพนักงาน',
  UPDATE_EMPLOYEE: 'แก้ไขข้อมูลพนักงาน',

  // Payroll
  PROCESS_PAYROLL: 'ประมวลผลเงินเดือน',
  DISTRIBUTE_PAYSLIPS: 'ส่งสลิปเงินเดือน',
  BULK_DOWNLOAD_PAYSLIPS: 'ดาวน์โหลดสลิปทั้งงวด',
  VIEW_PAYSLIP_PDF: 'เปิดดูสลิปพนักงาน',
  VIEW_OWN_PAYSLIP_PDF: 'เปิดดูสลิปของตนเอง',

  // Commission
  SUBMIT_COMMISSION: 'ยื่นค่าคอมมิชชั่น',
  MANAGER_APPROVE_COMMISSION: 'ผู้จัดการอนุมัติค่าคอม',
  CEO_APPROVE_COMMISSION: 'CEO อนุมัติค่าคอม',
  CEO_REJECT_COMMISSION: 'CEO ไม่อนุมัติค่าคอม',
  REJECT_COMMISSION: 'ไม่อนุมัติค่าคอม',
};

export function actionLabel(action) {
  return ACTION_LABELS[action] || action || '-';
}

/** Approvals/rejections read differently from submissions, so the page can tint them. */
export function actionTone(action) {
  if (!action) return 'neutral';
  if (/(^|_)(APPROVE|CEO_APPROVE|MANAGER_APPROVE)/.test(action)) return 'positive';
  if (/(^|_)REJECT/.test(action)) return 'negative';
  if (/(^|_)CANCEL|WITHDRAW/.test(action)) return 'muted';
  return 'neutral';
}

export const ACTION_LABEL_MAP = ACTION_LABELS;
