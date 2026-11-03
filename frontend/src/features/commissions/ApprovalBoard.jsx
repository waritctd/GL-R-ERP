import { useEffect, useMemo, useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import {
  commissionStatusLabel as statusInfo,
  formatMoney,
  formatThaiDate,
  formatThaiMonthYearFromMonthInputValue as shortMonth,
  formatThaiMonthYearLongFromMonthInputValue as longMonth,
} from '../../utils/format.js';
import { CommissionStatement } from './CommissionStatement.jsx';
import { InvoiceDetailPanel } from './InvoiceDetailPanel.jsx';
import { MANUAL_KIND_LABELS, baseContribution, countsTowardBase, describeCommissionWeight, isManualKind, kindLabel, monthOf } from './commissionRecord.js';

const STAGES = [
  { status: 'SUBMITTED', label: 'รอผู้จัดการ', dot: 'bg-warning-border' },
  { status: 'MANAGER_APPROVED', label: 'รอ CEO', dot: 'bg-info' },
  { status: 'APPROVED', label: 'อนุมัติแล้ว · พร้อมเข้า Payroll', dot: 'bg-success' },
];
const CHIPS = [
  { key: 'all', label: 'ทั้งหมด' },
  { key: 'SUBMITTED', label: 'รอผู้จัดการ' },
  { key: 'MANAGER_APPROVED', label: 'รอ CEO' },
  { key: 'APPROVED', label: 'อนุมัติแล้ว' },
  { key: 'REJECTED', label: 'ไม่อนุมัติ' },
];
// Whole literal (Tailwind JIT): expand, invoice, rep, base, status, approved-by, actions.
// Fixed tracks fit the 44px icon buttons (two side by side in actions) and the mismatch badge.
const ALL_RECORDS_GRID = 'grid-cols-[2.875rem_minmax(0,2.4fr)_minmax(0,1.1fr)_minmax(9.75rem,1fr)_minmax(0,0.9fr)_minmax(0,1.3fr)_6.25rem] nav-drawer:min-w-[960px]';
const COUNTED = STAGES.map((stage) => stage.status);

function shiftMonth(yearMonth, delta) {
  const [year, month] = yearMonth.split('-').map(Number);
  const index = year * 12 + (month - 1) + delta;
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`;
}
const byMonth = (a, b) => monthOf(a.commission).localeCompare(monthOf(b.commission));
const baseOf = (record) => (countsTowardBase(record) ? baseContribution(record) : 0);

function CycleHeader({ month, currentMonth, onMonth, records, stage, canCreateManual, onManual }) {
  const counts = COUNTED.map((status) => {
    const rows = records.filter((r) => r.status === status);
    const manualSum = rows.filter((r) => isManualKind(r.kind)).reduce((total, r) => total + Number(r.manualAmount || 0), 0);
    return { status, n: rows.length, sum: rows.reduce((total, r) => total + baseOf(r), 0), manualSum };
  });
  const total = counts.reduce((sum, c) => sum + c.n, 0);
  const done = counts[2].n;
  return (
    <section aria-label="รอบจ่ายค่าคอม" className="grid gap-3.5 rounded-md border border-border bg-surface p-4 mobile:p-3.5">
      <div className="flex flex-wrap items-center justify-between gap-x-5 gap-y-3">
        <div className="flex items-center gap-1.5">
          <Button type="button" variant="icon" aria-label="รอบก่อนหน้า" onClick={() => onMonth(shiftMonth(month, -1))}>
            <Icon name="chevronLeft" size={16} />
          </Button>
          <span className="grid min-w-0 px-1.5 leading-tight">
            <small className="text-sm font-bold text-text-muted">รอบจ่ายค่าคอม</small>
            <b className="text-2xl font-extrabold mobile:text-xl">
              {longMonth(month)}
              {month === currentMonth ? (
                <span className="ml-1.5 rounded-pill bg-primary-bg px-2 align-[3px] text-xs font-extrabold text-primary">รอบจ่ายถัดไป</span>
              ) : null}
            </b>
          </span>
          <Button type="button" variant="icon" aria-label="รอบถัดไป" onClick={() => onMonth(shiftMonth(month, 1))}>
            <Icon name="chevronRight" size={16} />
          </Button>
        </div>
        <span className="text-md text-text-muted">{`จากยอดรับเงิน ${shortMonth(shiftMonth(month, -1))} · จ่ายพร้อมเงินเดือน ${shortMonth(month)}`}</span>
        {canCreateManual ? (
          <Button type="button" variant="secondary" className="whitespace-nowrap mobile:min-h-11 mobile:w-full" onClick={onManual}>
            <Icon name="plus" size={14} />
            เพิ่มค่าคอมด้วยตนเอง
          </Button>
        ) : null}
      </div>
      <div className="grid gap-2">
        <div role="img" aria-label={`${done} จาก ${total} รายการอนุมัติแล้ว`} className="flex h-2.5 gap-0.5 overflow-hidden rounded-pill bg-surface-subtle">
          {counts.map((c, i) => (c.n ? <i key={c.status} className={cn('block h-full', STAGES[i].dot)} style={{ flex: c.n }} /> : null))}
        </div>
        <dl className="m-0 grid grid-cols-3 gap-2.5 mobile:grid-cols-1">
          {counts.map((c, i) => (
            <div
              key={c.status}
              data-testid={`cycle-box-${c.status}`}
              aria-current={c.status === stage ? 'true' : undefined}
              className={cn(
                'grid gap-px rounded-md border border-border px-2.5 py-2 mobile:grid-cols-[1fr_auto] mobile:items-center',
                c.status === stage && 'border-text shadow-[inset_0_0_0_1px_var(--color-text)]',
              )}
            >
              <dt className="flex items-center gap-1.5 text-sm text-text-muted">
                <i className={cn('inline-block h-2 w-2 rounded-sm', STAGES[i].dot)} />
                {STAGES[i].label}
              </dt>
              <dd className="m-0 text-lg font-extrabold tabular-nums">
                {`${c.n} รายการ`}
                <small className="ml-1 text-sm font-semibold text-text-muted">{formatMoney(c.sum)}</small>
                {c.manualSum !== 0 ? <small className="block text-sm font-semibold text-text-muted">{`+ กรอกเอง ${formatMoney(c.manualSum)}`}</small> : null}
              </dd>
            </div>
          ))}
        </dl>
      </div>
    </section>
  );
}

function CarryOver({ items, onGo }) {
  if (!items.length) return null;
  const target = monthOf(items[0].commission); // oldest first
  return (
    <div data-testid="carry-over-callout" className="flex flex-wrap items-center justify-between gap-2.5 rounded-md border border-warning-border bg-warning-bg-soft px-3.5 py-2.5 text-warning-dark">
      <span>
        <b>{`ค้างจากรอบก่อน: ${items.length} รายการ`}</b>
        {` · ${items.map((e) => `${e.commission.invoiceDetails?.invoiceNumber ?? `#${e.commission.id}`} (${e.commission.salesRepName || e.commission.salesRepId} · รอบ ${shortMonth(monthOf(e.commission))})`).join(', ')} ยังไม่ได้อนุมัติ`}
      </span>
      <Button type="button" variant="secondary" className="whitespace-nowrap" onClick={() => onGo(target)}>
        {`ไปที่รอบ ${shortMonth(target)}`}
        <Icon name="chevronRight" size={14} />
      </Button>
    </div>
  );
}

// Payroll month = invoice month + 1, so what was submitted THIS month sits in next month's round.
function UpcomingNote({ items, onGo }) {
  if (!items.length) return null;
  const target = monthOf(items[0].commission);
  const inTarget = items.filter((e) => monthOf(e.commission) === target).length;
  return (
    <div data-testid="upcoming-callout" className="flex flex-wrap items-center justify-between gap-2.5 rounded-md border border-info-border bg-info-bg px-3.5 py-2.5 text-info-dark">
      <span><b>{`รอบถัดไป ${shortMonth(target)}: ${inTarget} รายการรอคุณอนุมัติ`}</b></span>
      <Button type="button" variant="secondary" className="whitespace-nowrap" onClick={() => onGo(target)}>
        {`ไปที่รอบ ${shortMonth(target)}`}
        <Icon name="chevronRight" size={14} />
      </Button>
    </div>
  );
}

// Shared by the desktop columns and the phone card so the two views can't drift.
function ManualKindLabel({ record, className }) {
  return <span className={className}><strong>{MANUAL_KIND_LABELS[record.kind] || record.kind}</strong><StatusBadge tone="info">Manual</StatusBadge></span>;
}

// `blockClass` wraps each badge on its own line (desktop cell); without it they sit inline (phone card).
function SaleBadges({ record, blockClass }) {
  const badge = describeCommissionWeight(record);
  const wrap = (node) => (blockClass ? <span className={blockClass}>{node}</span> : node);
  return (
    <>
      {badge ? wrap(<StatusBadge tone="info">{badge.label}</StatusBadge>) : null}
      {record.dealAmountMismatch ? wrap(<StatusBadge tone="warning">ยอดต่างจากยอดที่เรียกเก็บ</StatusBadge>) : null}
    </>
  );
}

function Amount({ value, negativeRed = false, className }) {
  return <span className={cn('tabular-nums font-extrabold', negativeRed && Number(value) < 0 && 'text-danger', className)}>{formatMoney(value)}</span>;
}

function ApprovedByLines({ record, className }) {
  const who = (name, at) => (at ? `${name || '-'} · ${formatThaiDate(at)}` : '-');
  return (
    <span className={cn('grid text-text-muted', className)}>
      <span>{`ผู้จัดการ: ${who(record.managerApprovedByName, record.managerApprovedAt)}`}</span>
      <span>{`CEO: ${who(record.ceoApprovedByName, record.ceoApprovedAt)}`}</span>
    </span>
  );
}

function ExpandToggle({ record, open, onToggle, withLabel = false }) {
  const label = isManualKind(record.kind) ? (MANUAL_KIND_LABELS[record.kind] || record.kind) : record.invoiceDetails.invoiceNumber;
  return (
    <Button
      type="button"
      variant="icon"
      className={withLabel ? 'mobile:min-h-11 mobile:w-full mobile:gap-1.5 mobile:whitespace-nowrap mobile:px-3 mobile:text-sm mobile:font-bold' : undefined}
      aria-expanded={open}
      aria-label={`ดูรายละเอียด ${label}`}
      title="ดูรายละเอียดการคำนวณ"
      onClick={onToggle}
    >
      {/* the words show on mobile only; desktop keeps the compact chevron */}
      {withLabel ? <span className="hidden mobile:inline">ดูรายละเอียด</span> : null}
      <Icon name={open ? 'chevronUp' : 'chevronDown'} size={14} />
    </Button>
  );
}

function QueueRow({ entry, isCeo, open, onToggle, saving, onApprove, onReject, panel }) {
  const { commission: record } = entry;
  const manual = isManualKind(record.kind);
  const amount = Number(record.manualAmount || 0);
  const weight = Number(entry.effectiveWeight ?? 1);
  const sub = isCeo
    ? `ผู้จัดการอนุมัติ: ${record.managerApprovedByName || '-'} · ${formatThaiDate(record.managerApprovedAt)}`
    : [entry.ticketCode, entry.customerName].filter(Boolean).join(' · ');
  return (
    <>
      <div
        data-testid={`queue-row-${record.id}`}
        className={cn(
          'grid grid-cols-[28px_minmax(0,1.6fr)_minmax(0,1.2fr)_minmax(0,1fr)_auto] items-center gap-3.5 border-t border-border px-3.5 py-3 first:border-t-0',
          'mobile:grid-cols-[minmax(0,1fr)_auto] mobile:gap-x-2.5 mobile:gap-y-2',
          open && 'bg-surface-muted',
        )}
      >
        <span className="mobile:order-last mobile:col-span-full"><ExpandToggle record={record} open={open} onToggle={onToggle} withLabel /></span>
        <div className="min-w-0">
          <b className="block font-extrabold">{record.salesRepName || record.salesRepId}</b>
          <span className="block text-sm text-text-muted [overflow-wrap:anywhere]">
            {manual ? `${MANUAL_KIND_LABELS[record.kind] || record.kind} · ${record.manualReason || '-'}` : `${record.invoiceDetails?.invoiceNumber ?? ''} · ${sub}`}
          </span>
          {record.dealAmountMismatch ? <span className="mt-1 block"><StatusBadge tone="warning">ยอดต่างจากยอดที่เรียกเก็บ</StatusBadge></span> : null}
        </div>
        <div className="flex gap-4 mobile:col-span-full">
          {manual ? (
            <span className="grid">
              <small className="text-sm text-text-muted">จำนวนเงิน</small>
              <b className={cn('tabular-nums font-extrabold', amount < 0 && 'text-danger')}>{formatMoney(amount)}</b>
            </span>
          ) : (
            <span className="grid">
              <small className="text-sm text-text-muted">{weight !== 1 ? 'ฐานค่าคอม (หลังถ่วง)' : 'ฐานค่าคอม'}</small>
              <b className="tabular-nums font-extrabold">{formatMoney(entry.weightedCommissionableBase)}</b>
            </span>
          )}
          {!manual && weight !== 1 ? (
            <span className="grid">
              <small className="text-sm text-text-muted">น้ำหนัก</small>
              <b><StatusBadge tone="info">{`×${weight.toFixed(2)}`}</StatusBadge></b>
            </span>
          ) : null}
        </div>
        {manual ? <span /> : (
          <div className="grid mobile:col-start-2 mobile:row-start-1 mobile:text-right">
            <small className="text-sm text-text-muted">ค่าคอมโดยประมาณ</small>
            <b className="tabular-nums font-extrabold">{formatMoney(entry.estimatedCommission)}</b>
          </div>
        )}
        <div className="flex justify-end gap-2 mobile:col-span-full">
          <Button type="button" variant="secondary" className="border-success text-success hover:bg-success-bg mobile:min-h-11 mobile:flex-1" disabled={saving} onClick={onApprove}>
            <Icon name="check" size={14} />
            อนุมัติ
          </Button>
          <Button type="button" variant="secondary" className="border-danger text-danger hover:bg-danger-bg mobile:min-h-11 mobile:flex-1" disabled={saving} onClick={onReject}>
            <Icon name="close" size={14} />
            ไม่อนุมัติ
          </Button>
        </div>
      </div>
      {panel}
    </>
  );
}

function RepSummaryTable({ month, rows, summaries, pickedRepId, onPick }) {
  return (
    <section aria-labelledby="rep-summary-heading" className="grid min-w-0 gap-2.5">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-2">
        <h2 id="rep-summary-heading" className="m-0 text-lg font-extrabold text-text">{`สรุปรายคน · ${shortMonth(month)}`}</h2>
        <span className="text-sm text-text-muted">กดชื่อเพื่อดูใบสรุปค่าคอมแบบเดียวกับที่พนักงานเห็น</span>
      </div>
      <div className="overflow-x-auto rounded-md border border-border bg-surface">
        <table className="w-full border-collapse">
          <thead>
            <tr className="bg-surface-muted text-left text-sm text-text-muted">
              <th scope="col" className="whitespace-nowrap border-b border-border px-3 py-2.5 font-bold">พนักงานขาย</th>
              <th scope="col" className="whitespace-nowrap border-b border-border px-3 py-2.5 text-right font-bold">รายการ</th>
              <th scope="col" className="whitespace-nowrap border-b border-border px-3 py-2.5 text-right font-bold">ฐานค่าคอม (หลังถ่วง)</th>
              <th scope="col" className="whitespace-nowrap border-b border-border px-3 py-2.5 text-right font-bold mobile:hidden">ค่าคอมโดยประมาณ</th>
              <th scope="col" className="whitespace-nowrap border-b border-border px-3 py-2.5 font-bold mobile:hidden">ความคืบหน้า</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr><td colSpan={5} className="px-3 py-4 text-center text-sm text-text-muted">{`ยังไม่มีรายการในรอบ ${shortMonth(month)}`}</td></tr>
            ) : null}
            {rows.map((rep) => (
              <tr key={rep.id} onClick={() => onPick(rep.id)} className={cn('cursor-pointer border-b border-border last:border-b-0 hover:bg-surface-muted', String(pickedRepId) === String(rep.id) && 'bg-surface-muted')}>
                <td className="px-3 py-2.5">
                  <button type="button" className="border-0 bg-transparent p-0 text-left font-extrabold text-text" aria-pressed={String(pickedRepId) === String(rep.id)}>{rep.name}</button>
                </td>
                <td className="px-3 py-2.5 text-right tabular-nums">{rep.n}</td>
                <td className="px-3 py-2.5 text-right font-mono text-sm tabular-nums">{formatMoney(rep.base)}</td>
                <td className="px-3 py-2.5 text-right font-mono text-sm tabular-nums mobile:hidden">{summaries[rep.id] ? formatMoney(summaries[rep.id].totalCommission) : '-'}</td>
                <td className="px-3 py-2.5 mobile:hidden">
                  <span className="flex items-center gap-2">
                    <span className="flex h-1.5 w-[120px] gap-px overflow-hidden rounded-pill bg-surface-subtle">
                      {COUNTED.map((status, i) => (rep.by[status] ? <i key={status} className={cn('block h-full', STAGES[i].dot)} style={{ flex: rep.by[status] }} /> : null))}
                    </span>
                    <span className="text-sm text-text-muted">{`${rep.by.APPROVED}/${rep.n} อนุมัติแล้ว`}</span>
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/**
 * The sales_manager / ceo page body: payroll-round header + progress strip, carry-over callout, the
 * viewer's own "รอคุณอนุมัติ" queue, the per-rep table and every record of the round. All sections
 * follow `month`. Data and write-handlers come from CommissionPage.
 */
export function ApprovalBoard({
  user, month, currentMonth, onMonth, records, loading, pending, summaries, pickedRepId, onPickRep, saving,
  canCreateManual, onManual, manualForm, onApprove, onReject, onClawback, onEdit, editor, onAdjust, onEntryUpdate, onSaved,
}) {
  const isCeo = user.role === 'ceo';
  const stage = isCeo ? 'MANAGER_APPROVED' : 'SUBMITTED';
  const [queueOpen, setQueueOpen] = useState(null);
  const [tableOpen, setTableOpen] = useState(null);
  const [filter, setFilter] = useState('all');
  // Records with a weight save in flight. Lives here (always mounted) so the lock covers every open
  // panel of the record, its approve / reject buttons, and survives collapsing the panel.
  const [savingIds, setSavingIds] = useState(() => new Set());
  const setRecordSaving = (id, on) => setSavingIds((current) => {
    const next = new Set(current);
    if (on) next.add(id); else next.delete(id);
    return next;
  });
  // A new round starts clean: no stale filter, no open panels.
  useEffect(() => {
    setFilter('all');
    setQueueOpen(null);
    setTableOpen(null);
  }, [month]);

  const counted = useMemo(() => records.filter((r) => COUNTED.includes(r.status)), [records]);
  const entries = useMemo(() => pending ?? [], [pending]);
  // The manager's own manual entries are inserted MANAGER_APPROVED and pending-approval only carries
  // SALE records, so the CEO's queue also takes the round's manual entries from the month's list.
  const manualWaiting = isCeo
    ? records.filter((r) => isManualKind(r.kind) && r.status === 'MANAGER_APPROVED' && monthOf(r) === month).map((commission) => ({ commission }))
    : [];
  const queue = [...entries.filter((e) => monthOf(e.commission) === month), ...manualWaiting];
  const upcoming = entries.filter((e) => monthOf(e.commission) > month).sort(byMonth);
  const carry = entries.filter((e) => monthOf(e.commission) < month).sort(byMonth);
  const pendingById = useMemo(() => new Map(entries.map((e) => [e.commission.id, e])), [entries]);

  const reps = useMemo(() => {
    const byRep = new Map();
    counted.forEach((r) => {
      const rep = byRep.get(r.salesRepId) ?? { id: r.salesRepId, name: r.salesRepName || String(r.salesRepId), n: 0, base: 0, by: { SUBMITTED: 0, MANAGER_APPROVED: 0, APPROVED: 0 } };
      rep.n += 1;
      rep.base += baseOf(r);
      rep.by[r.status] += 1;
      byRep.set(r.salesRepId, rep);
    });
    return [...byRep.values()].sort((a, b) => b.base - a.base);
  }, [counted]);
  const pickedRep = reps.find((rep) => String(rep.id) === String(pickedRepId));

  const rejectedRows = records.filter((r) => r.status === 'REJECTED');
  const rows = filter === 'REJECTED' ? rejectedRows : counted.filter((r) => filter === 'all' || r.status === filter);
  const countOf = (key) => (key === 'all' ? counted.length : key === 'REJECTED' ? rejectedRows.length : counted.filter((r) => r.status === key).length);
  const canReview = (record) => record.status === stage;
  const stageWho = isCeo ? 'CEO ' : 'ผู้จัดการ';

  const panelFor = (record, open) => (open ? (
    <InvoiceDetailPanel
      record={record}
      entry={pendingById.get(record.id)}
      canEdit={canReview(record)}
      canAdjust={user.role === 'sales_manager' && record.status === 'SUBMITTED'}
      recordSaving={savingIds.has(record.id)}
      onSavingChange={(on) => setRecordSaving(record.id, on)}
      editor={editor(record)}
      onEdit={() => onEdit(record)}
      onAdjust={onAdjust}
      onEntryUpdate={onEntryUpdate}
      onSaved={onSaved}
    />
  ) : null);

  const columns = [
    {
      key: 'expand',
      header: '',
      render: (record) => <ExpandToggle record={record} open={tableOpen === record.id} onToggle={() => setTableOpen((cur) => (cur === record.id ? null : record.id))} />,
    },
    {
      key: 'invoiceNumber',
      header: 'ใบกำกับ',
      sortable: true,
      sortAccessor: (record) => (isManualKind(record.kind) ? kindLabel(record.kind) : record.invoiceDetails.invoiceNumber),
      searchAccessor: (record) => (isManualKind(record.kind) ? `${kindLabel(record.kind)} ${record.manualReason || ''}` : record.invoiceDetails.invoiceNumber),
      render: (record) => (isManualKind(record.kind) ? (
        <span>
          <ManualKindLabel record={record} className="flex items-center gap-1.5" />
          <small className="line-clamp-2 text-text-muted" title={record.manualReason}>{record.manualReason}</small>
        </span>
      ) : (
        <span>
          <strong>{record.invoiceDetails.invoiceNumber}</strong>
          {pendingById.get(record.id)?.ticketCode ? <small className="block text-text-muted">{pendingById.get(record.id).ticketCode}</small> : null}
        </span>
      )),
    },
    {
      key: 'salesRepName',
      header: 'พนักงานขาย',
      sortable: true,
      sortAccessor: (record) => record.salesRepName || record.salesRepId,
      searchAccessor: (record) => record.salesRepName || record.salesRepId,
      render: (record) => <span>{record.salesRepName || record.salesRepId}</span>,
    },
    {
      key: 'commissionableBase',
      header: 'ฐานค่าคอม',
      align: 'right',
      sortable: true,
      sortAccessor: (record) => baseOf(record),
      render: (record) => {
        if (isManualKind(record.kind)) return <Amount value={record.manualAmount} negativeRed />;
        return (
          <span>
            <Amount value={baseOf(record)} />
            <SaleBadges record={record} blockClass="mt-1 block" />
          </span>
        );
      },
    },
    {
      key: 'status',
      header: 'สถานะ',
      render: (record) => {
        const status = statusInfo(record.status);
        return <StatusBadge tone={status.tone}>{status.label}</StatusBadge>;
      },
    },
    {
      key: 'approvedBy',
      header: 'อนุมัติโดย',
      render: (record) => <ApprovedByLines record={record} className="text-sm" />,
    },
    {
      key: 'actions',
      header: '',
      render: (record) => (
        <span className="flex flex-wrap justify-end gap-1.5">
          {canReview(record) ? (
            <>
              <Button type="button" variant="icon" className="border-success text-success" title={`${stageWho}อนุมัติ`} aria-label={`${stageWho}อนุมัติ`} disabled={saving || savingIds.has(record.id)} onClick={() => onApprove(record.id)}>
                <Icon name="check" size={14} />
              </Button>
              <Button type="button" variant="icon" className="border-danger text-danger" title={`${stageWho}ปฏิเสธ`} aria-label={`${stageWho}ปฏิเสธ`} disabled={saving || savingIds.has(record.id)} onClick={() => onReject(record.id)}>
                <Icon name="close" size={14} />
              </Button>
            </>
          ) : null}
          {record.kind === 'SALE' && record.status === 'APPROVED' ? (
            <Button type="button" variant="icon" title="บันทึกหักคืน" aria-label="บันทึกหักคืน" disabled={saving} onClick={() => onClawback(record.id)}>
              <Icon name="close" size={14} />
            </Button>
          ) : null}
        </span>
      ),
    },
  ];

  const mobileCard = (record) => {
    const manual = isManualKind(record.kind);
    const amount = manual ? record.manualAmount : baseOf(record);
    const status = statusInfo(record.status);
    const ticketCode = pendingById.get(record.id)?.ticketCode;
    const busy = saving || savingIds.has(record.id);
    return (
      <div className="grid gap-2">
        <div className="flex items-baseline justify-between gap-3">
          <b className="min-w-0 font-extrabold [overflow-wrap:anywhere]">{record.salesRepName || record.salesRepId}</b>
          <Amount value={amount} negativeRed={manual} className="shrink-0" />
        </div>
        {manual ? (
          <div className="grid gap-1">
            <ManualKindLabel record={record} className="flex flex-wrap items-center gap-1.5" />
            <span className="line-clamp-2 text-sm text-text-muted" title={record.manualReason}>{record.manualReason}</span>
          </div>
        ) : (
          <div className="flex flex-wrap items-center gap-1.5 text-sm text-text-muted">
            <span>{[record.invoiceDetails.invoiceNumber, ticketCode].filter(Boolean).join(' · ')}</span>
            <SaleBadges record={record} />
          </div>
        )}
        <div className="flex items-center justify-between gap-3">
          <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
          {/* shrink-to-fit wrapper: ExpandToggle's mobile w-full would otherwise stretch across the card */}
          <span className="flex shrink-0 gap-2">
            {record.kind === 'SALE' && record.status === 'APPROVED' ? (
              <Button type="button" variant="secondary" className="min-h-11 whitespace-nowrap" aria-label="บันทึกหักคืน" disabled={saving} onClick={() => onClawback(record.id)}>
                หักคืน
              </Button>
            ) : null}
            <ExpandToggle record={record} open={tableOpen === record.id} onToggle={() => setTableOpen((cur) => (cur === record.id ? null : record.id))} withLabel />
          </span>
        </div>
        {record.managerApprovedAt || record.ceoApprovedAt ? <ApprovedByLines record={record} className="text-xs" /> : null}
        {canReview(record) ? (
          <div className="flex gap-2">
            <Button type="button" variant="secondary" className="min-h-11 flex-1 border-success text-success hover:bg-success-bg" disabled={busy} onClick={() => onApprove(record.id)}>
              <Icon name="check" size={14} />
              อนุมัติ
            </Button>
            <Button type="button" variant="secondary" className="min-h-11 flex-1 border-danger text-danger hover:bg-danger-bg" disabled={busy} onClick={() => onReject(record.id)}>
              <Icon name="close" size={14} />
              ไม่อนุมัติ
            </Button>
          </div>
        ) : null}
      </div>
    );
  };

  return (
    <>
      <CycleHeader month={month} currentMonth={currentMonth} onMonth={onMonth} records={counted} stage={stage} canCreateManual={canCreateManual} onManual={onManual} />
      {manualForm}
      <CarryOver items={carry} onGo={onMonth} />
      <UpcomingNote items={upcoming} onGo={onMonth} />

      <section aria-labelledby="approval-queue-heading" className="grid gap-2.5">
        <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-2">
          <h2 id="approval-queue-heading" className="m-0 flex items-baseline gap-2 text-lg font-extrabold text-text">
            รอคุณอนุมัติ{' '}
            <span className="rounded-pill bg-text px-2.5 text-md font-extrabold text-surface">{queue.length}</span>
          </h2>
          <span className="text-sm text-text-muted">
            {isCeo ? `อนุมัติแล้ว รายการจะเข้ารอบ Payroll ${shortMonth(month)}` : 'ผู้จัดการอนุมัติแล้ว รายการจะส่งต่อให้ CEO · ปรับน้ำหนัก ×2/×3 ได้เฉพาะสินค้าจากสต็อก'}
          </span>
        </div>
        <div className="overflow-hidden rounded-md border border-border bg-surface">
          {pending == null ? (
            <p className="m-0 p-4 text-sm text-text-muted">กำลังโหลดรายการรออนุมัติ…</p>
          ) : queue.length === 0 ? (
            <div className="flex items-center justify-center gap-2 p-4 text-center font-bold text-success">
              <Icon name="check" size={16} />
              {`ไม่มีรายการรอคุณอนุมัติในรอบ ${shortMonth(month)}`}
              {!isCeo && countOf('MANAGER_APPROVED') ? ` · ส่งต่อ CEO แล้ว ${countOf('MANAGER_APPROVED')} รายการ` : ''}
            </div>
          ) : queue.map((entry) => (
            <QueueRow
              key={entry.commission.id}
              entry={entry}
              isCeo={isCeo}
              open={queueOpen === entry.commission.id}
              onToggle={() => setQueueOpen((cur) => (cur === entry.commission.id ? null : entry.commission.id))}
              saving={saving || savingIds.has(entry.commission.id)}
              onApprove={() => onApprove(entry.commission.id)}
              onReject={() => onReject(entry.commission.id)}
              panel={panelFor(entry.commission, queueOpen === entry.commission.id)}
            />
          ))}
        </div>
      </section>

      <RepSummaryTable month={month} rows={reps} summaries={summaries} pickedRepId={pickedRepId} onPick={(id) => onPickRep(String(pickedRepId) === String(id) ? '' : id)} />
      {pickedRep && summaries[pickedRep.id] ? <CommissionStatement summary={summaries[pickedRep.id]} heading={`ค่าคอมของ ${pickedRep.name}`} /> : null}

      <section aria-labelledby="all-records-heading" className="grid min-w-0 gap-2.5">
        <h2 id="all-records-heading" className="m-0 text-lg font-extrabold text-text">รายการทั้งหมดในรอบนี้</h2>
        <div className="flex flex-wrap gap-1.5">
          {CHIPS.filter((chip) => chip.key !== 'REJECTED' || rejectedRows.length > 0).map((chip) => (
            <button
              key={chip.key}
              type="button"
              aria-pressed={filter === chip.key}
              onClick={() => setFilter(chip.key)}
              className={cn(
                'min-h-8 rounded-pill border border-border-muted bg-surface px-3 text-sm font-bold text-text-secondary pointer-coarse:min-h-11',
                filter === chip.key && 'border-text bg-text text-surface',
              )}
            >
              {`${chip.label} ${countOf(chip.key)}`}
            </button>
          ))}
        </div>
        <DataTable
          columns={columns}
          rows={rows}
          getRowKey={(record) => record.id}
          gridClassName={ALL_RECORDS_GRID}
          mobileCard={mobileCard}
          renderExpanded={(record) => panelFor(record, tableOpen === record.id)}
          pageSize={20}
          searchable
          searchPlaceholder="ค้นหาเลขที่ใบกำกับ / ชื่อ Sales"
          loading={loading}
          emptyState={{
            icon: 'badge',
            title: 'ยังไม่มีรายการค่าคอมในรอบนี้',
            description: 'ค่าคอมจากใบกำกับที่บันทึกเดือนนี้จะอยู่ในรอบเดือนถัดไป — ลองกด ‹ › เพื่อเลือกรอบ หรือรอฝ่ายบัญชีบันทึกใบกำกับ',
          }}
        />
      </section>
    </>
  );
}
