import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { leadTimeRangeText } from './factoryContactMeta.js';

/**
 * CR-1 (GLA-167) R2 / R10 — what the OWNING sales rep (or a sales manager) sees when import has
 * asked to change a factory's ระยะเวลานำเข้า: ONE banner per pending request, listing the whole
 * request (factory, reason, every line old -> new), decided once, as a whole.
 *
 *   อนุมัติ        — the new values become the lines' lead times.
 *   ไม่อนุมัติ     — the old values stay; a reason is REQUIRED (import sees it).
 *
 * Both send the `version` this banner loaded (`expectedVersion`): if import edited the request in
 * the meantime the server answers 409 and the page says so and refetches (handled by the caller).
 * Who may see this at all (owning rep / sales_manager — NOT the CEO, B-R3) is decided by the caller.
 */
export function LeadTimeChangeBanner({ change, factoryName, lineNames, pending, onApprove, onReject }) {
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const reasonId = `pcr-lt-reject-reason-${change.id}`;

  return (
    <section
      className="grid gap-3 rounded-md border border-warning-border bg-warning-bg-soft p-4"
      data-testid="pcr-lt-banner"
      aria-label={`คำขอเปลี่ยนระยะเวลานำเข้า ${factoryName}`}
    >
      <div className="flex flex-wrap items-center gap-2">
        <Icon name="clock" size={16} className="shrink-0 text-warning-dark" />
        <strong className="text-sm text-text">ฝ่ายนำเข้าขอเปลี่ยนระยะเวลานำเข้า</strong>
        <StatusBadge tone="warning">รอฝ่ายขายอนุมัติ</StatusBadge>
      </div>
      <p className="m-0 text-sm text-text">
        โรงงาน <strong>{factoryName}</strong>
      </p>
      <p className="m-0 text-sm text-text-secondary">
        <span className="font-bold text-text">เหตุผล:</span> {change.reason}
      </p>
      <ul className="m-0 grid list-none gap-1 p-0 text-sm text-text">
        {change.lines.map((line) => (
          <li key={line.pricingRequestItemId} className="flex flex-wrap items-baseline gap-x-2">
            <span className="min-w-0 truncate font-bold">{lineNames.get(line.pricingRequestItemId) ?? `รายการ #${line.pricingRequestItemId}`}</span>
            <span className="tabular-nums">
              {`${leadTimeRangeText(line.oldMinDays, line.oldMaxDays)} → ${leadTimeRangeText(line.newMinDays, line.newMaxDays)} วัน`}
            </span>
          </li>
        ))}
      </ul>

      {rejecting ? (
        <div className="grid gap-2">
          <FormField label="เหตุผลที่ไม่อนุมัติ" htmlFor={reasonId} hint="ฝ่ายนำเข้าจะเห็นเหตุผลนี้">
            <textarea id={reasonId} className="min-h-16" maxLength={1000} value={reason}
              onChange={(event) => setReason(event.target.value)} />
          </FormField>
          <div className="flex flex-wrap justify-end gap-2">
            <Button type="button" variant="secondary" disabled={pending} onClick={() => { setRejecting(false); setReason(''); }}>
              ยกเลิก
            </Button>
            <Button type="button" variant="danger" disabled={pending || !reason.trim()}
              onClick={() => onReject(change, reason.trim())}>
              ยืนยันไม่อนุมัติ
            </Button>
          </div>
        </div>
      ) : (
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="danger" disabled={pending} onClick={() => setRejecting(true)}>
            ไม่อนุมัติ
          </Button>
          <Button type="button" variant="primary" disabled={pending} onClick={() => onApprove(change)}>
            อนุมัติ
          </Button>
        </div>
      )}
    </section>
  );
}
