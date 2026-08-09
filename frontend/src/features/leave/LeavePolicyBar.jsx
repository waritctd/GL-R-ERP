import { Icon } from '../../components/common/Icon.jsx';

// Leave-surface IA restructure (2026-08-10), owner ruling: the "กฎการลา" TAB is gone and the
// §5 announcement is reached from a slim reference bar at the top of the leave page instead --
// deliberately the same shape as SpecialMoneyPanel.jsx's welfare reference bar ("ระเบียบสวัสดิการ
// (PDF)"), which the owner pointed at as the pattern to copy. Two surfaces that both mean "here is
// the source document this screen is governed by" should look identical.
//
// What went with the tab: the in-app §5 clause breakdown (RulesTab.jsx + leavePolicySections.js,
// ~8 CollapsibleSections of quota / notice / attachment rules per leave type). Dropped on the
// owner's explicit call -- the PDF is the authoritative text and is one click away here.
//
// ── THE DOCUMENT IS NOW BUNDLED, NOT FETCHED (2026-08-11, owner ruling) ──────
// This used to probe `GET /api/leave/policy-document` (HEAD for availability, then a blob
// download) and render one of three states, because no file had ever been uploaded and the
// honest answer was "ask HR". The announcement now ships with the app, at
// frontend/public/policy/ -- served as-is at the href below, the same way
// SpecialMoneyPanel.jsx already ships ระเบียบสวัสดิการ. So availability is no longer a question
// with a real "no": the file is in the bundle or the build is broken, and there is nothing left
// to probe, fail, or explain. The three states collapse to a link.
//
// ⚠️ Two consequences worth knowing before "restoring" the API version:
//
//  1. `api.leave.policyDocumentAvailable` / `downloadPolicyDocument` are now called by NOTHING.
//     They stay in hrApi.js and mockApi.js because contract.test.js pins that surface against the
//     real LeaveController, which still exposes the endpoint -- an unused client method is not a
//     dead endpoint. If HR uploading a replacement ever becomes a real workflow, that endpoint is
//     where it goes, and this bar would need to prefer it over the bundled copy.
//  2. A bundled file is the ONLY option that works on the Vercel/Render demo, which has no
//     backend at all. An API-served PDF is permanently unavailable there, which is exactly the
//     state this replaces.
//
// The bundled PDF is the signed original: "กฎระเบียบข้อบังคับของพนักงาน บริษัท จี แอล แอนด์ อาร์
// แทปส์ แอนด์ ไทลส์ จำกัด เรื่องวันเวลาทำงาน และ การลาหยุดงาน", effective 1 ตุลาคม 2567. Its
// clause 5 is what every leave rule in this app traces back to, and the figures match
// mockApi.js's db.leaveTypes / the V116-V125 migrations as seeded: 5.1 ลาป่วย 30 วัน,
// 5.2 ลากิจ 7 วัน (and its five named purposes are leaveRequestTable.jsx's LEAVE_PURPOSE_OPTIONS),
// 5.3 ลาพักร้อน 6 วัน + carryover, 5.4 ลาคลอด 98 วัน/จ่าย 45, 5.5 ลาทหาร จ่ายไม่เกิน 60 วัน
// (the reason MILITARY's annualQuotaDays is a 366 sentinel, not a policy number), 5.6 ลาอุปสมบท
// ครั้งเดียวตลอดอายุงาน. Do NOT treat this file as a spec to re-derive that logic from -- it is
// reference material for the reader; the rules already live in the migrations.

// Bundled at build time (frontend/public/policy/, served as-is at this path -- same convention as
// SpecialMoneyPanel.jsx's POLICY_PDF_HREF). The download filename mirrors the source document's
// own name rather than the URL slug, so a saved copy is recognisable regardless of what the asset
// happens to be named on disk.
// Exported so LeavePolicyBar.test.jsx can assert the file this points at ACTUALLY EXISTS in
// public/. That is the one failure this component can have and jsdom cannot see: a rename or a
// missing asset renders a perfectly good-looking link that 404s on click, with nothing else in the
// suite to notice.
export const POLICY_PDF_HREF = '/policy/leave-policy-2567.pdf';
const POLICY_PDF_DOWNLOAD_NAME = 'วันเวลาทำงาน และการหยุดงาน_1_10_67.pdf';

export function LeavePolicyBar() {
  return (
    <div className="flex flex-wrap items-center gap-2.5 rounded-md border border-border bg-surface px-3.5 py-2.5 text-sm">
      <Icon name="fileText" size={16} className="text-icon-muted" />
      <span className="min-w-0">
        กฎระเบียบพนักงาน เรื่องวันเวลาทำงานและการลาหยุดงาน (มีผล 1 ต.ค. 2567) — ข้อ 5 คือที่มาของกฎการลาทั้งหมดในหน้านี้
      </span>
      <a
        href={POLICY_PDF_HREF}
        target="_blank"
        rel="noopener noreferrer"
        download={POLICY_PDF_DOWNLOAD_NAME}
        className="ml-auto inline-flex shrink-0 items-center gap-1.5 font-bold text-primary mobile:ml-0"
      >
        <Icon name="fileText" size={15} />
        ประกาศวันลา (PDF)
      </a>
    </div>
  );
}
