import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Icon } from '../../components/common/Icon.jsx';
import { downloadBlob } from '../../utils/download.js';

// Leave-surface IA restructure (2026-08-10), owner ruling: the "กฎการลา" TAB is gone and the
// §5 announcement is reached from a slim reference bar at the top of the leave page instead --
// deliberately the same shape as SpecialMoneyPanel.jsx's welfare reference bar ("ระเบียบสวัสดิการ
// (PDF)"), which the owner pointed at as the pattern to copy. Two surfaces that both mean "here is
// the source document this screen is governed by" should look identical.
//
// What went with the tab: the in-app §5 clause breakdown (RulesTab.jsx + leavePolicySections.js,
// ~8 CollapsibleSections of quota / notice / attachment rules per leave type). Dropped on the
// owner's explicit call -- the PDF is the authoritative text and is one click away here.
// `git show <this commit>^:frontend/src/features/leave/RulesTab.jsx` brings it back.
//
// ── The three states are not decoration ──
// `available` is a real question with a real "no": the file is uploaded by HR and may simply not
// be there. Rendering a download control that then 404s is worse than saying so up front, which is
// why the unavailable branch replaces the trigger rather than disabling it. "We could not ask"
// (isError, e.g. offline) collapses into the same branch on purpose -- a user who cannot reach the
// endpoint has no more use for a live link than one where the document was never uploaded.

export function LeavePolicyBar() {
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState(null);

  const availableQuery = useQuery({
    queryKey: queryKeys.leavePolicyDocumentAvailable(),
    queryFn: () => api.leave.policyDocumentAvailable(),
  });

  async function handleDownload() {
    setDownloading(true);
    setError(null);
    try {
      const blob = await api.leave.downloadPolicyDocument();
      downloadBlob(blob, 'glr-leave-policy-announcement', 'pdf');
    } catch (err) {
      setError(err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloading(false);
    }
  }

  // Nothing at all while the availability check is in flight: this is a one-line reference bar
  // above the tabs, and flashing a skeleton band there shifts every panel below it on first paint.
  if (availableQuery.isPending) return null;

  const available = availableQuery.isSuccess && availableQuery.data === true;

  return (
    <div className="flex flex-wrap items-center gap-2.5 rounded-md border border-border bg-surface px-3.5 py-2.5 text-sm">
      <Icon name="fileText" size={16} className="text-icon-muted" />
      <span className="min-w-0">
        {available
          ? 'ประกาศ §5 เรื่องวันลา — เอกสารต้นฉบับที่ใช้ตั้งกฎการลาทั้งหมด'
          : 'ยังไม่มีไฟล์ประกาศ §5 ฉบับเต็ม — กรุณาติดต่อฝ่ายบุคคลหากต้องการเอกสารต้นฉบับ'}
      </span>
      {available ? (
        // A button, not an `<a href>` as the welfare bar uses: this PDF is not a static file in
        // public/ -- it is an authenticated API blob (GET /api/leave/policy-document), so it has to
        // go through the api client. Styled to read as the same affordance regardless.
        <button
          type="button"
          onClick={handleDownload}
          disabled={downloading}
          className="ml-auto inline-flex shrink-0 items-center gap-1.5 font-bold text-primary disabled:opacity-60 mobile:ml-0"
        >
          <Icon name="fileText" size={15} />
          {downloading ? 'กำลังดาวน์โหลด…' : 'ประกาศวันลา (PDF)'}
        </button>
      ) : null}
      {error ? <span className="w-full text-sm text-danger">{error}</span> : null}
    </div>
  );
}
