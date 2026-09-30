import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { cn } from '../../utils/cn.js';
import { commissionStatusLabel as statusInfo, formatMoney, formatThaiDate } from '../../utils/format.js';
import { ReceiptChain } from './ReceiptChain.jsx';
import {
  MANUAL_KIND_LABELS,
  baseContribution,
  formatWeight,
  isManualKind,
  kindLabel,
  recordWeight,
} from './commissionRecord.js';

const dateOf = (record) => formatThaiDate(record.invoiceDetails?.invoiceDate ?? record.createdAt);

function ExpandButton({ expanded, onToggle }) {
  return (
    <Button
      type="button"
      variant="icon"
      aria-expanded={expanded}
      title="ดูรายละเอียดการคำนวณ"
      aria-label="ดูรายละเอียดการคำนวณ"
      onClick={onToggle}
    >
      <Icon name={expanded ? 'chevronUp' : 'chevronDown'} size={14} />
    </Button>
  );
}

// The identity cell: invoice number for a receipt, the kind (and reason) for a manual entry.
function Identity({ record }) {
  if (isManualKind(record.kind)) {
    return (
      <span className="grid min-w-0 gap-0.5">
        <strong className="text-md text-text [overflow-wrap:anywhere]">{MANUAL_KIND_LABELS[record.kind] || record.kind}</strong>
        <span className="text-sm text-text-muted [overflow-wrap:anywhere]">{`${formatMoney(record.manualAmount)}${record.manualReason ? ` — ${record.manualReason}` : ''}`}</span>
      </span>
    );
  }
  return (
    <span className="grid min-w-0 gap-0.5">
      <strong className="text-md text-text [overflow-wrap:anywhere]">{record.invoiceDetails?.invoiceNumber}</strong>
      <span className="text-sm text-text-muted">{kindLabel(record.kind)}</span>
    </span>
  );
}

function WeightChip({ record }) {
  const weight = recordWeight(record);
  if (isManualKind(record.kind) || weight === 1) return null;
  return <StatusBadge tone="info">{formatWeight(weight)}</StatusBadge>;
}

/**
 * The rep's receipts for the month, one row each: date · number · status · ex-VAT amount · weight
 * chip (only when it is not ×1) · what it puts into the base. There is deliberately NO per-receipt
 * commission in baht: the ladder is marginal over the month's total, so a per-sale figure would be
 * invented — the note under the heading says so. Desktop is a table; on a phone each receipt becomes
 * a card (identity · status · the two facts · expand) rather than a squeezed grid.
 */
export function ReceiptLedger({ records, loading }) {
  const isMobile = useIsMobile();
  const [expandedId, setExpandedId] = useState(null);
  const toggle = (id) => setExpandedId((current) => (current === id ? null : id));

  return (
    <section aria-labelledby="receipt-ledger-heading" className="grid min-w-0 gap-3">
      <div className="grid gap-1">
        <h2 id="receipt-ledger-heading" className="m-0 text-lg font-extrabold text-text">ที่มาของแต่ละรายการ</h2>
        <p data-testid="no-per-sale-note" className="m-0 max-w-[72ch] text-sm text-text-muted">
          ค่าคอมคิดแบบขั้นบันไดจากยอดรวมทั้งเดือน จึงไม่แสดงเป็นบาทรายใบ — แต่ละใบแสดงเฉพาะยอดที่เข้าฐาน
        </p>
        <p data-testid="receipt-rounding-note" className="m-0 max-w-[72ch] text-sm text-text-muted">
          ตัวเลขรายใบถูกปัดเศษ ผลรวมของ “ที่เข้าฐาน” จึงอาจต่างจากฐานคิดค่าคอมอยู่ไม่กี่สตางค์
        </p>
      </div>

      {loading && records.length === 0 ? (
        <p className="m-0 text-sm text-text-muted" aria-busy="true">กำลังโหลดรายการ…</p>
      ) : records.length === 0 ? (
        <EmptyState
          icon="badge"
          title="ยังไม่มีรายการค่าคอม"
          description="ค่าคอมจากใบกำกับที่บันทึกเดือนนี้จะอยู่ในรอบเดือนถัดไป — ลองกด “รอบถัดไป” หรือรอฝ่ายบัญชีบันทึกใบกำกับ"
        />
      ) : isMobile ? (
        <ul className="m-0 grid list-none gap-3 p-0">
          {records.map((record) => {
            const status = statusInfo(record.status);
            const manual = isManualKind(record.kind);
            return (
              <li key={record.id} data-testid={`receipt-row-${record.id}`} className="grid min-w-0 gap-3 rounded-md border border-border bg-surface p-3">
                <div className="flex min-w-0 items-start justify-between gap-3">
                  <Identity record={record} />
                  <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                </div>
                {manual ? (
                  <p className="m-0 text-sm text-text-muted">{dateOf(record)} · ไม่เข้าฐาน</p>
                ) : (
                  <dl className="m-0 grid grid-cols-2 gap-x-3 gap-y-1 text-sm">
                    <dt className="text-text-muted">ยอดไม่รวม VAT</dt>
                    <dd className="m-0 text-right font-bold tabular-nums [overflow-wrap:anywhere]">{formatMoney(record.commissionableBase)}</dd>
                    <dt className="text-text-muted">ที่เข้าฐาน</dt>
                    <dd className="m-0 text-right font-bold tabular-nums [overflow-wrap:anywhere]">{formatMoney(baseContribution(record))}</dd>
                    <dt className="text-text-muted">วันที่</dt>
                    <dd className="m-0 text-right">{dateOf(record)}</dd>
                  </dl>
                )}
                <div className="flex items-center justify-between gap-2">
                  <WeightChip record={record} />
                  <span className="ml-auto"><ExpandButton expanded={expandedId === record.id} onToggle={() => toggle(record.id)} /></span>
                </div>
                {expandedId === record.id ? <ReceiptChain record={record} compact /> : null}
              </li>
            );
          })}
        </ul>
      ) : (
        <div className="min-w-0 overflow-x-auto rounded-md border border-border bg-surface">
          <table className="w-full min-w-[44rem] border-collapse text-left text-md">
            <thead>
              <tr className="bg-surface-muted text-2xs font-extrabold uppercase tracking-[0.04em] text-text-muted">
                <th scope="col" className="w-14 px-3 py-2.5"><span className="sr-only">ขยาย</span></th>
                <th scope="col" className="px-3 py-2.5">วันที่</th>
                <th scope="col" className="px-3 py-2.5">เลขที่</th>
                <th scope="col" className="px-3 py-2.5">สถานะ</th>
                <th scope="col" className="px-3 py-2.5 text-right">ยอดไม่รวม VAT</th>
                <th scope="col" className="px-3 py-2.5">น้ำหนัก</th>
                <th scope="col" className="px-3 py-2.5 text-right">ที่เข้าฐาน</th>
              </tr>
            </thead>
            {records.map((record) => {
              const status = statusInfo(record.status);
              const manual = isManualKind(record.kind);
              const expanded = expandedId === record.id;
              return (
                <tbody key={record.id} className="border-t border-border-subtle">
                  <tr data-testid={`receipt-row-${record.id}`}>
                    <td className="px-3 py-2"><ExpandButton expanded={expanded} onToggle={() => toggle(record.id)} /></td>
                    <td className="whitespace-nowrap px-3 py-2 text-text-secondary">{dateOf(record)}</td>
                    <td className="min-w-0 px-3 py-2"><Identity record={record} /></td>
                    <td className="px-3 py-2"><StatusBadge tone={status.tone}>{status.label}</StatusBadge></td>
                    <td className={cn('px-3 py-2 text-right tabular-nums', manual && 'text-text-muted')}>{manual ? '—' : formatMoney(record.commissionableBase)}</td>
                    <td className="px-3 py-2"><WeightChip record={record} /></td>
                    <td className={cn('px-3 py-2 text-right font-bold tabular-nums', manual && 'font-normal text-text-muted')}>
                      {manual ? 'ไม่เข้าฐาน' : formatMoney(baseContribution(record))}
                    </td>
                  </tr>
                  {expanded ? (
                    <tr>
                      <td colSpan={7} className="px-3 pb-3"><ReceiptChain record={record} compact /></td>
                    </tr>
                  ) : null}
                </tbody>
              );
            })}
          </table>
        </div>
      )}
    </section>
  );
}
