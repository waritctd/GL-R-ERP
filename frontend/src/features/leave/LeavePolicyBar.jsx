import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
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
// ── HISTORY: BUNDLED, NOT FETCHED (2026-08-11 owner ruling) — REVERSED (2026-08-14) ──────────
// This used to probe `GET /api/leave/policy-document` (HEAD for availability, then a blob
// download), then went bundled-only on 2026-08-11: no file had ever been uploaded (nothing to
// prefer), and the announcement shipped with the app instead, at frontend/public/policy/ -- served
// as-is, the same way SpecialMoneyPanel.jsx already ships ระเบียบสวัสดิการ. The ruling reasoned that
// a bundled file was the ONLY option that worked on "the Vercel/Render demo, which has no backend
// at all" -- that premise was already stale when written (both `vercel.json` files rewrite
// `/api/:path*` to the real Render-hosted Spring backend, and `render.yaml` deploys one there; the
// backend-less path only matters for a plain static host with no `/api` proxy configured, which is
// not what this demo actually is), and would have made the demo land on the 404 row of the table
// below regardless -- not "permanently unavailable".
//
// The precondition the 2026-08-11 comment explicitly named for reversing itself -- "If HR
// uploading a replacement ever becomes a real workflow ... this bar would need to prefer it over
// the bundled copy" -- was met by issue #774, which shipped LeavePolicyDocumentPage.jsx
// (/settings/leave-policy). Per owner ruling on 2026-08-14, this bar now probes again, and PREFERS
// a confirmed-current uploaded document over the bundled fallback. `api.leave.policyDocumentAvailable`
// / `downloadPolicyDocument` were never deleted across that whole detour -- they stayed in hrApi.js
// and mockApi.js because contract.test.js pins that surface against the real LeaveController -- so
// nothing had to be re-added to make this reversal possible, only rewired.
//
// ── THE DESIGN THIS REVERSAL SETTLED ON ───────────────────────────────────────────────────────
// `fetch` resolves if and only if an HTTP response actually came back -- that is what separates
// "the server answered" from "nothing answered". But the DOCUMENT ITSELF is served in EVERY case: a
// leave policy is reference material, so withholding it is the worse failure than serving a
// possibly-stale copy with a clear warning. The four states below differ only in the STRENGTH OF
// THE WARNING next to the document, never in whether the document is offered:
//
//   'available'     2xx + content-type: application/pdf  -- a current uploaded document exists.
//                    Renders it (not the bundled copy). No warning.
//   'absent'         404 -- the server ANSWERED "nothing stored", which CONFIRMS the bundled copy
//                    IS current. Renders the bundled copy. No warning of any kind -- this is the
//                    state every environment is in today and must look unchanged.
//   'unverified'     2xx but not a PDF (a static host's SPA catch-all serving index.html), or
//                    `fetch` REJECTED outright -- learned nothing about whether a real backend
//                    exists behind this path. Renders the bundled copy + a mild note. No retry
//                    (there is nothing more specific to retry against).
//   'check-failed'   any other status (500/401/403/502/...) -- POSITIVE evidence a backend exists
//                    and refused to answer; the bundled copy might already be superseded. Renders
//                    the bundled copy + a STRONG warning + a retry button.
//
// See hrApi.js's `policyDocumentAvailable` for exactly how a raw HTTP response maps to these four
// strings, including why a 500 warns harder than a rejection. 401 gets NO special case here or
// there -- this app has no global 401 handler to defer to (the only `status === 401` handling
// anywhere is the login form itself), and inventing a second, competing re-auth path inside this
// reference bar would be worse than letting it land in the same generic non-404 bucket as every
// other refusal.
//
// Mock mode (`VITE_USE_MOCKS=true`) makes no HTTP request at all, so mockApi.js can only ever
// answer 'available' or 'absent' -- see that file's own comment on this method.
//
// The bundled PDF is the signed original: "กฎระเบียบข้อบังคับของพนักงาน บริษัท จี แอล แอนด์ อาร์
// แทปส์ แอนด์ ไทลส์ จำกัด เรื่องวันเวลาทำงาน และ การลาหยุดงาน", effective 1 ตุลาคม 2567. Its
// clause 5 is what every leave rule in this app traces back to, and the figures match
// mockApi.js's db.leaveTypes / the V116-V125 migrations as seeded: 5.1 ลาป่วย 30 วัน,
// 5.2 ลากิจ 7 วัน (and its five named purposes are leaveRequestTable.jsx's LEAVE_PURPOSE_OPTIONS),
// 5.3 ลาพักร้อน 6 วัน + carryover, 5.4 ลาคลอด 98 วัน/จ่าย 45, 5.5 ลาทหาร จ่ายไม่เกิน 60 วัน
// (the reason MILITARY's annualQuotaDays is a 366 sentinel, not a policy number), 5.6 ลาอุปสมบท
// ครั้งเดียวตลอดอายุงาน. Do NOT treat this file as a spec to re-derive that logic from -- it is
// reference material for the reader; the rules already live in the migrations. That paragraph is
// true of whichever copy is showing (bundled or uploaded): the announcement text itself did not
// change, only where the bytes may come from.

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

// The 'absent' / 'unverified' / 'check-failed' states all render this exact link -- see the
// module comment's table. Shared as one definition so the three states cannot silently drift from
// each other (the property the test file pins: 'check-failed' and 'absent' must render an
// IDENTICAL link, differing only in whatever warning sits alongside it).
function BundledPolicyLink() {
  return (
    <a
      href={POLICY_PDF_HREF}
      target="_blank"
      rel="noopener noreferrer"
      download={POLICY_PDF_DOWNLOAD_NAME}
      className="inline-flex shrink-0 items-center gap-1.5 font-bold text-primary"
    >
      <Icon name="fileText" size={15} />
      ประกาศวันลา (PDF)
    </a>
  );
}

export function LeavePolicyBar() {
  const [downloadError, setDownloadError] = useState('');

  const availabilityQuery = useQuery({
    queryKey: queryKeys.leavePolicyDocumentAvailable(),
    queryFn: () => api.leave.policyDocumentAvailable(),
  });
  // An ERRORED query is 'check-failed', never the 'absent' default below. hrApi's probe never throws
  // for an HTTP reason (it maps every response to one of the four strings), but mockApi's does --
  // `requireSession()` raises a 401 -- and an unexpected throw is always possible. The fall-through
  // default at the bottom of this component is "bundled copy, NO warning at all", which would
  // present an answer we never received as a confirmed-current one. That is the exact silent
  // wrong-serve this whole change exists to prevent, so a thrown probe gets the STRONGEST warning,
  // not the weakest.
  const status = availabilityQuery.isError ? 'check-failed' : availabilityQuery.data;

  async function handleOpenUploaded() {
    setDownloadError('');
    try {
      const blob = await api.leave.downloadPolicyDocument();
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank', 'noopener');
      // Revoked on the next tick, not immediately: the newly opened tab needs the URL to still
      // resolve when it starts loading. Same 60s window, same reason, as
      // LeavePolicyDocumentPage.jsx's identical pattern.
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (error) {
      // No showToast here by design -- this bar has no such prop and must not gain one just for
      // this. A failed open is rare (the probe already confirmed 'available' moments earlier) and
      // small enough to explain inline, in the same trailing slot the affordance itself sits in.
      setDownloadError(error?.message || 'เปิดเอกสารไม่สำเร็จ');
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2.5 rounded-md border border-border bg-surface px-3.5 py-2.5 text-sm">
      <Icon name="fileText" size={16} className="text-icon-muted" />
      <span className="min-w-0">
        กฎระเบียบพนักงาน เรื่องวันเวลาทำงานและการลาหยุดงาน (มีผล 1 ต.ค. 2567) — ข้อ 5 คือที่มาของกฎการลาทั้งหมดในหน้านี้
      </span>

      {/* One shared trailing slot for every state, positioned exactly like today's single <a> was
          (ml-auto pushes it to the far right; mobile:ml-0 drops that on narrow widths where the
          bar wraps instead). Kept on the wrapper rather than repeated on each inner element so the
          multi-element states ('unverified': note + link; 'check-failed': warning + retry + link)
          lay out as one right-aligned cluster instead of fighting each other's auto margins. */}
      {availabilityQuery.isPending ? (
        // Deliberately NOT a link: a link rendered mid-probe can be clicked before the answer
        // arrives, which is the exact silent wrong-serve this whole change exists to prevent.
        <span className="ml-auto shrink-0 text-text-muted mobile:ml-0">กำลังตรวจสอบเอกสารล่าสุด…</span>
      ) : status === 'available' ? (
        <div className="ml-auto flex shrink-0 flex-wrap items-center justify-end gap-2 mobile:ml-0">
          {/* variant="text" is border-0 bg-transparent text-primary p-0 + Button's own !font-bold --
              visually the same as the bundled <a> it replaces here. */}
          <Button type="button" variant="text" onClick={handleOpenUploaded}>
            <Icon name="fileText" size={15} />
            ประกาศวันลา (PDF)
          </Button>
          {downloadError ? (
            <span role="alert" className="text-2xs font-bold text-danger">{downloadError}</span>
          ) : null}
        </div>
      ) : status === 'check-failed' ? (
        <div className="ml-auto flex shrink-0 flex-wrap items-center justify-end gap-2 mobile:ml-0">
          {/* role="status", not "alert": this is a page-load state the reader can act on in their
              own time, not an interruption. Amber, not red -- the document IS available (the
              bundled copy renders regardless), it may just be stale. */}
          <span
            role="status"
            className="inline-flex items-center gap-1.5 rounded-full border border-warning-border bg-warning-bg-soft px-2.5 py-1 text-2xs font-bold text-warning"
          >
            <Icon name="triangleAlert" size={13} className="shrink-0" />
            ตรวจสอบฉบับล่าสุดจากระบบไม่สำเร็จ — ไฟล์ที่แสดงอาจไม่ใช่ฉบับปัจจุบัน
          </span>
          <Button type="button" variant="secondary" size="sm" onClick={() => availabilityQuery.refetch()}>
            ลองใหม่
          </Button>
          <BundledPolicyLink />
        </div>
      ) : status === 'unverified' ? (
        <div className="ml-auto flex shrink-0 flex-wrap items-center justify-end gap-2 mobile:ml-0">
          {/* Mild by design -- no retry button. A non-PDF 200 or a rejected fetch proves nothing
              about whether a real backend exists at all, so there is no more specific check to
              retry against; retrying the exact same probe again is not a meaningfully different
              action from waiting for the next natural refetch. */}
          <span className="text-2xs text-text-muted">ตรวจสอบกับเซิร์ฟเวอร์ไม่ได้ — กำลังแสดงฉบับที่ติดมากับระบบ</span>
          <BundledPolicyLink />
        </div>
      ) : (
        // 'absent' (the state every environment is in today), and the safe default for anything
        // unexpected: no warning of any kind -- see the module comment's table for why a 404 is an
        // ANSWER, not a failure.
        <div className="ml-auto shrink-0 mobile:ml-0">
          <BundledPolicyLink />
        </div>
      )}
    </div>
  );
}
