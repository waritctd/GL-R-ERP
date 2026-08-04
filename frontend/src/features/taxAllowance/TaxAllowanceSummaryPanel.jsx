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
 *
 * <p>The panel used to be passive: one fixed CTA ("ดู / ยื่นแบบแจ้ง ล.ย.01") regardless of state,
 * and no sign of WHY a declaration was rejected or that an approved one had expired. Since nothing
 * in the workflow notifies the employee, this panel is one of only two places a rejection or an
 * expiry can be discovered at all — so the state that needs action now says so here, rather than
 * making the employee open the form to find out.
 */

// The action the employee can actually take next, per state. Mirrors TaxAllowancePage's own
// EDITABLE_STATUS_KEYS: only these three states accept a new submission — PENDING and both
// APPROVED variants are read-only there, so promising "ยื่นฉบับใหม่" would be a dead link.
const ACTIONABLE_KEYS = new Set(['NONE', 'REJECTED', 'EXPIRED']);

function ctaLabel(statusKey) {
  if (statusKey === 'NONE') return 'ยื่นแบบแจ้ง ล.ย.01';
  if (ACTIONABLE_KEYS.has(statusKey)) return 'ยื่นฉบับใหม่';
  return 'ดูแบบแจ้ง ล.ย.01';
}

export function TaxAllowanceSummaryPanel({ summary }) {
  const navigate = useNavigate();
  const statusInfo = summary?.statusInfo ?? { label: 'ยังไม่ได้ยื่น', tone: 'neutral', key: 'NONE' };
  const statusKey = statusInfo.key ?? 'NONE';
  const needsAction = ACTIONABLE_KEYS.has(statusKey) && statusKey !== 'NONE';

  return (
    <Panel
      title="ค่าลดหย่อนภาษี (แบบ ล.ย.01)"
      actions={<StatusBadge tone={statusInfo.tone}>{statusInfo.label}</StatusBadge>}
    >
      {/* A single inline line, not a nested card — this panel already sits among three others on
          /profile, and a boxed alert inside a box is exactly the density this page does not need. */}
      {needsAction ? (
        <p className="m-0 mb-3 border-l-2 border-danger-border pl-3 text-sm font-bold text-danger">
          {statusKey === 'REJECTED'
            ? `ถูกปฏิเสธ${statusInfo.note ? ` — ${statusInfo.note}` : ''} · ยื่นฉบับใหม่ได้เลย`
            : 'แบบแจ้งหมดอายุแล้ว — ต้องยื่นฉบับใหม่เพื่อให้ยังได้รับสิทธิลดหย่อน'}
        </p>
      ) : null}

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
          <strong className={`text-lg ${statusKey === 'EXPIRED' ? 'text-danger' : ''}`}>
            {summary?.expiresOn ? formatShortDate(summary.expiresOn) : '-'}
          </strong>
        </div>
      </div>
      {/* Full-width CTA below 720px: a right-aligned secondary button is a small target on a
          phone, and this is the panel's only action. */}
      <div className="mt-4 flex justify-end max-[720px]:block">
        <Button
          type="button"
          variant="secondary"
          className="max-[720px]:w-full"
          onClick={() => navigate('/tax-allowance')}
        >
          {ctaLabel(statusKey)}
        </Button>
      </div>
    </Panel>
  );
}
