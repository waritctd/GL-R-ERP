import { useNavigate } from 'react-router-dom';
import { Button } from '../../components/common/Button.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney, formatShortDate } from '../../utils/format.js';

/**
 * Profile summary panel (issue #387 screen 4) — status + totals only, never the full 21-field
 * form. `summary` is computed and passed down from App.jsx/useHrData.js, the same "fetch once at
 * the top, pass as a prop" pattern `profileRequests` already uses on this page — not fetched
 * inside this component.
 */
export function TaxAllowanceSummaryPanel({ summary }) {
  const navigate = useNavigate();
  const statusInfo = summary?.statusInfo ?? { label: 'ยังไม่ได้ยื่น', tone: 'neutral' };

  return (
    <Panel
      title="ค่าลดหย่อนภาษี (แบบ ล.ย.01)"
      actions={<StatusBadge tone={statusInfo.tone}>{statusInfo.label}</StatusBadge>}
    >
      <div className="grid grid-cols-3 gap-3 max-[720px]:grid-cols-1">
        <div>
          <small className="block text-text-muted">ค่าลดหย่อนที่ประกาศ</small>
          <strong className="text-lg">{summary?.declaredTotal ? formatMoney(summary.declaredTotal) : '-'}</strong>
        </div>
        <div>
          <small className="block text-text-muted">หลักฐานที่แนบ</small>
          <strong className="text-lg">{summary?.evidenceCount ?? 0} ไฟล์</strong>
        </div>
        <div>
          <small className="block text-text-muted">หมดอายุ</small>
          <strong className="text-lg">{summary?.expiresOn ? formatShortDate(summary.expiresOn) : '-'}</strong>
        </div>
      </div>
      <div className="mt-4 flex justify-end">
        <Button type="button" variant="secondary" onClick={() => navigate('/tax-allowance')}>
          ดู / ยื่นแบบแจ้ง ล.ย.01
        </Button>
      </div>
    </Panel>
  );
}
