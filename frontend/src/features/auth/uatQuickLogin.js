// The 9 persona accounts seeded by db/migration-uat/V900, offered as one-click sign-in buttons on
// the UAT login screen. Unlike the mock quick-login (which posts a password-less { role } that the
// real backend rejects with a 403), these post real credentials to the real /api/auth/login.
//
// The shared password is only valid because db/migration-uat/V907 resets it and clears
// must_change_password. It is published in V900 and UAT_Accounts.md, so it is not a secret — but it
// does end up in the JS bundle, which is why every use of this module is gated on
// UAT_QUICK_LOGIN_ENABLED and only the uat branch's .env.production turns it on.
export const UAT_PASSWORD = 'Uat@2026';

export const uatAccounts = [
  { email: 'ceo@uat.glr', label: 'CEO', helper: 'อนุมัติใบขอราคา · ภาพรวมทั้งหมด', icon: 'shield' },
  { email: 'hr@uat.glr', label: 'HR', helper: 'พนักงานทั้งหมด · อนุมัติคำขอ', icon: 'badgeCheck' },
  { email: 'salesmgr@uat.glr', label: 'Sales Manager', helper: 'อนุมัติค่าคอม · แก้ไขค่าหัก', icon: 'badgeDollar' },
  { email: 'sales@uat.glr', label: 'Sales', helper: 'สร้างใบขอราคา · ออกใบเสนอราคา', icon: 'briefcase' },
  { email: 'import@uat.glr', label: 'Import', helper: 'รับเรื่อง · เสนอราคาสินค้า', icon: 'clipboard' },
  { email: 'divmgr@uat.glr', label: 'Division Manager', helper: 'ผู้จัดการฝ่ายคลังสินค้า · ทีมของฉัน', icon: 'userCog' },
  { email: 'employee@uat.glr', label: 'Employee', helper: 'โปรไฟล์ของฉัน · ส่งคำขอแก้ไข', icon: 'user' },
  { email: 'nulldiv@uat.glr', label: 'Employee (ไม่มีฝ่าย)', helper: 'ทดสอบ fallback เมื่อ division ว่าง', icon: 'user' },
  { email: 'admin@uat.glr', label: 'Admin', helper: 'ฝ่าย ADMIN · ได้สิทธิ์เท่า employee', icon: 'setting' },
];
