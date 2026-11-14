import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';

// Leave-surface IA restructure, owner ruling: the "กฎการลา" TAB is gone and the §5 announcement is reached
// from a slim reference bar at the top of the leave page instead. Two surfaces that both mean "here is the
// source document this screen is governed by" should look identical.

// Bundled at build time (frontend/public/policy/, served as-is at this path -- same convention as
// SpecialMoneyPanel.jsx's POLICY_PDF_HREF). Exported so LeavePolicyBar.test.jsx can assert the file this
// points at ACTUALLY EXISTS in public/.
export const POLICY_PDF_HREF = '/policy/leave-policy-2567.pdf';
const POLICY_PDF_DOWNLOAD_NAME = 'วันเวลาทำงาน และการหยุดงาน_1_10_67.pdf';

// The 'absent' / 'unverified' / 'check-failed' states all render this exact link -- see the module comment's
// table.
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
  // An ERRORED query is 'check-failed', never the 'absent' default below. hrApi's probe never throws for an HTTP
  // reason (it maps every response to one of the four strings), but mockApi's does -- `requireSession()` raises
  // a 401 -- and an unexpected throw is always possible.
  const status = availabilityQuery.isError ? 'check-failed' : availabilityQuery.data;

  async function handleOpenUploaded() {
    setDownloadError('');
    try {
      const blob = await api.leave.downloadPolicyDocument();
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank', 'noopener');
      // Revoked on the next tick, not immediately: the newly opened tab needs the URL to still resolve when it
      // starts loading. Same 60s window, same reason, as LeavePolicyDocumentPage.jsx's identical pattern.
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (error) {
      // No showToast here by design -- this bar has no such prop and must not gain one just for this. A failed open
      // is rare (the probe already confirmed 'available' moments earlier) and small enough to explain inline, in the
      // same trailing slot the affordance itself sits in.
      setDownloadError(error?.message || 'เปิดเอกสารไม่สำเร็จ');
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2.5 rounded-md border border-border bg-surface px-3.5 py-2.5 text-sm">
      <Icon name="fileText" size={16} className="text-icon-muted" />
      <span className="min-w-0">
        กฎระเบียบพนักงาน เรื่องวันเวลาทำงานและการลาหยุดงาน (มีผล 1 ต.ค. 2567) — ข้อ 5 คือที่มาของกฎการลาทั้งหมดในหน้านี้
      </span>

      {/* One shared trailing slot for every state, positioned exactly like today's single was (ml-auto pushes it to
       * the far right; mobile:ml-0 drops that on narrow widths where the bar wraps instead). */}
      {availabilityQuery.isPending ? (
        // Deliberately NOT a link: a link rendered mid-probe can be clicked before the answer arrives, which is the
        // exact silent wrong-serve this whole change exists to prevent.
        <span className="ml-auto shrink-0 text-text-muted mobile:ml-0">กำลังตรวจสอบเอกสารล่าสุด…</span>
      ) : status === 'available' ? (
        <div className="ml-auto flex shrink-0 flex-wrap items-center justify-end gap-2 mobile:ml-0">
          {/* variant="text" is border-0 bg-transparent text-primary p-0 + Button's own !font-bold -- visually the same as
           * the bundled it replaces here. */}
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
          {/* role="status", not "alert": this is a page-load state the reader can act on in their own time, not an
           * interruption. Amber, not red -- the document IS available (the bundled copy renders regardless), it may just
           * be stale. */}
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
          {/* Mild by design -- no retry button. A non-PDF 200 or a rejected fetch proves nothing about whether a real
           * backend exists at all, so there is no more specific check to retry against; retrying the exact same probe
           * again is not a meaningfully different action from waiting for the next natural refetch. */}
          <span className="text-2xs text-text-muted">ตรวจสอบกับเซิร์ฟเวอร์ไม่ได้ — กำลังแสดงฉบับที่ติดมากับระบบ</span>
          <BundledPolicyLink />
        </div>
      ) : (
        // 'absent' (the state every environment is in today), and the safe default for anything unexpected: no warning
        // of any kind -- see the module comment's table for why a 404 is an ANSWER, not a failure.
        <div className="ml-auto shrink-0 mobile:ml-0">
          <BundledPolicyLink />
        </div>
      )}
    </div>
  );
}
