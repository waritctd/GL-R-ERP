import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import imageCompression from 'browser-image-compression';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { FileUploadField } from '../../components/common/FileUploadField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { SkeletonCard } from '../../components/common/Skeleton.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { commissionStatusLabel as statusInfo, dealStageLabel, formatMoney, formatThaiDate } from '../../utils/format.js';
import { invoiceCalculation, monthlyTierBase, round2, tierBreakdown } from './commissionCalc.js';

const today = new Date().toISOString().slice(0, 10);
const thisMonth = new Date().toISOString().slice(0, 7);

const emptyCreateForm = {
  invoiceNumber: '',
  invoiceDate: today,
  grossAmount: '',
  bankFees: '0',
  suspenseVat: '0',
  transportFee: '0',
  cutFee: '0',
  shortfall: '0',
  withholdingTax: '0',
  overpayment: '0',
  invoiceAttachment: null,
};

const emptyDeductionDraft = {
  grossAmount: 0,
  bankFees: 0,
  suspenseVat: 0,
  transportFee: 0,
  cutFee: 0,
  shortfall: 0,
  withholdingTax: 0,
  overpayment: 0,
  weightMultiplier: 1,
  reason: '',
};

// Manual commission entries (feat/commission-manual-adjustments): ALL FOUR kinds are hand-typed
// for now — owner decision: manual across the UI until the CEO-confirmed auto-config lands to
// prefill suggestions for specific ones later (not implemented here, no auto-computation exists
// anywhere in this form). Mirrors backend/.../commission/CommissionKind.java's four constants.
const MANUAL_KIND_LABELS = {
  ADJUSTMENT: 'ปรับปรุง/รับช่วงงาน',
  MANAGER: 'ค่าคอมผู้จัดการ/ทีม',
  STOCK_BONUS: 'โบนัสขายสต็อก',
  INCENTIVE: 'Incentive ตามเป้า',
};
const MANUAL_KINDS = Object.keys(MANUAL_KIND_LABELS);

function isManualKind(kind) {
  return Object.prototype.hasOwnProperty.call(MANUAL_KIND_LABELS, kind);
}

const emptyManualForm = {
  salesRepId: '',
  kind: 'ADJUSTMENT',
  amount: '',
  payrollMonth: '',
  reason: '',
};

function kindLabel(kind) {
  if (kind === 'CLAWBACK') return 'คืน/ยกเลิก';
  if (isManualKind(kind)) return MANUAL_KIND_LABELS[kind];
  return 'ขาย';
}

function numberOrNull(value) {
  if (value === '' || value === null || value === undefined) return null;
  return Number(value);
}

/**
 * Waterfall calculation-detail view — gross -> each deduction (including WHT) -> actualReceived
 * -> ÷1.07 -> commissionableBase, plus the weight multiplier (when >1) and status/owner. This is
 * the "clear calc breakdown" the sales read-only view needs (Slice A3), and is reused for
 * manager/CEO's row expansion too since every value here is display-only (the fields are exactly
 * what CommissionCalculator.calculateInvoice consumed server-side, not re-derived).
 */
function CommissionCalcBreakdown({ record }) {
  const status = statusInfo(record.status);
  if (isManualKind(record.kind)) {
    const amount = Number(record.manualAmount || 0);
    return (
      <div className="grid gap-3 text-sm">
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
          <StatusBadge tone="info">{MANUAL_KIND_LABELS[record.kind] || record.kind}</StatusBadge>
        </div>
        <div className="grid gap-1.5 rounded-md border border-border bg-surface-subtle p-3">
          <div className="flex items-center justify-between gap-3 font-bold">
            <span>จำนวนเงิน (พิมพ์เอง — ไม่ผ่านการคำนวณอัตโนมัติ)</span>
            <code className="font-mono" style={{ color: amount < 0 ? 'var(--color-danger)' : undefined }}>
              {formatMoney(amount)}
            </code>
          </div>
          <div className="flex items-start justify-between gap-3">
            <span className="text-text-muted">เหตุผล</span>
            <span className="text-right">{record.manualReason || '-'}</span>
          </div>
        </div>
        <div className="grid grid-cols-2 gap-3 text-xs text-text-muted sm:grid-cols-4">
          <span>ผู้จัดการ: {record.managerApprovedAt ? `${record.managerApprovedByName || '-'} · ${formatThaiDate(record.managerApprovedAt)}` : '-'}</span>
          <span>CEO: {record.ceoApprovedAt ? `${record.ceoApprovedByName || '-'} · ${formatThaiDate(record.ceoApprovedAt)}` : '-'}</span>
        </div>
      </div>
    );
  }
  const invoice = record.invoiceDetails;
  const lines = [
    { label: 'ยอดใบกำกับ (Gross)', value: invoice.grossAmount, sign: '' },
    { label: 'ค่าธรรมเนียมธนาคาร', value: invoice.bankFees, sign: '-' },
    { label: 'ภาษีรอใช้สิทธิ (Suspense VAT)', value: invoice.suspenseVat, sign: '-' },
    { label: 'ค่าขนส่ง', value: invoice.transportFee, sign: '-' },
    { label: 'ค่าตัด', value: invoice.cutFee, sign: '-' },
    { label: 'รับเงินขาด', value: invoice.shortfall, sign: '-' },
    { label: 'หัก ณ ที่จ่าย (WHT)', value: invoice.withholdingTax, sign: '-' },
    { label: 'รับเงินเกิน', value: invoice.overpayment, sign: '+' },
  ];
  return (
    <div className="grid gap-3 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
        {record.weightMultiplier > 1 ? (
          <StatusBadge tone={record.weightMultiplier === 3 ? 'warning' : 'info'}>
            น้ำหนักฐานคอม {record.weightMultiplier} เท่า{record.weightMultiplier === 3 ? ' (ยังไม่ยืนยันนโยบาย)' : ''}
          </StatusBadge>
        ) : null}
        {record.dealAmountMismatch ? <StatusBadge tone="warning">ยอดต่างจากยอดที่เรียกเก็บ</StatusBadge> : null}
      </div>
      <div className="grid gap-1.5 rounded-md border border-border bg-surface-subtle p-3">
        {lines.map((line) => (
          <div key={line.label} className="flex items-center justify-between gap-3">
            <span className="text-text-muted">{line.label}</span>
            <code className="font-mono">{line.sign}{formatMoney(line.value)}</code>
          </div>
        ))}
        <div className="mt-1 flex items-center justify-between gap-3 border-t border-border pt-1.5 font-bold">
          <span>= ยอดรับจริง (Actual Received)</span>
          <code className="font-mono">{formatMoney(record.actualReceived)}</code>
        </div>
        <div className="flex items-center justify-between gap-3 text-text-muted">
          <span>÷ 1.07 (แยกภาษีมูลค่าเพิ่ม)</span>
          <span />
        </div>
        <div className="flex items-center justify-between gap-3 font-bold">
          <span>= ฐานค่าคอม (Commissionable Base)</span>
          <code className="font-mono">{formatMoney(record.commissionableBase)}</code>
        </div>
      </div>
      <div className="grid grid-cols-2 gap-3 text-xs text-text-muted sm:grid-cols-4">
        <span>Invoice: {invoice.invoiceNumber} · {formatThaiDate(invoice.invoiceDate)}</span>
        <span>ผู้จัดการ: {record.managerApprovedAt ? `${record.managerApprovedByName || '-'} · ${formatThaiDate(record.managerApprovedAt)}` : '-'}</span>
        <span>CEO: {record.ceoApprovedAt ? `${record.ceoApprovedByName || '-'} · ${formatThaiDate(record.ceoApprovedAt)}` : '-'}</span>
        <span>ไฟล์: {invoice.invoiceAttachmentFileName || '-'}</span>
      </div>
    </div>
  );
}

/**
 * Mobile record card for a commission record. `.commission-table` is
 * intentionally excluded from the CSS reflow-cards path (styles.css ~L1938)
 * because six columns of amounts/status can't reflow into readable stacked
 * rows — it just scrolls horizontally, and with 0 rows the bare table-head
 * (min-width: 900px at <=1040px, styles.css ~L1795) still overflows
 * `.content-scroll`. Supplying `mobileCard` here bypasses the grid/table-head
 * markup below 720px entirely (DataTable's `asCards` path), which removes
 * that overflow and shows only what's needed to identify the record.
 */
/**
 * `canReview`/`onApprove`/`onReject` mirror the exact same `canReviewRecord`
 * check and `setApproveId`/`reject` handlers used by the desktop table's
 * action column. Without this, mobile users had no way to approve or reject
 * a commission at all — DataTable's `mobileCard` path renders only this
 * component and drops the desktop `actions` column entirely, which silently
 * broke the "CEO/manager can approve or reject" mobile task.
 */
function CommissionCard({ record, canReview, isCeoReview, saving, expanded, onToggleExpand, onApprove, onReject }) {
  const status = statusInfo(record.status);
  const manual = isManualKind(record.kind);
  return (
    <>
      <div className="flex min-w-0 items-start justify-between gap-3">
        <strong className="min-w-0 truncate text-sm font-extrabold text-text">
          {manual ? (MANUAL_KIND_LABELS[record.kind] || record.kind) : record.invoiceDetails.invoiceNumber}
        </strong>
        <span className="flex items-center gap-1.5">
          {manual ? <StatusBadge tone="info">Manual</StatusBadge> : null}
          <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
          <button
            type="button"
            className="icon-button"
            aria-expanded={expanded}
            title="ดูรายละเอียดการคำนวณ"
            aria-label="ดูรายละเอียดการคำนวณ"
            onClick={onToggleExpand}
          >
            <Icon name={expanded ? 'chevronUp' : 'chevronDown'} size={14} />
          </button>
        </span>
      </div>

      <span className="min-w-0 truncate text-xs text-text-muted">
        {record.salesRepName || record.salesRepId}
      </span>

      {manual ? (
        <span className="flex min-w-0 flex-col gap-0.5">
          <strong className="text-md font-extrabold text-text" style={{ color: Number(record.manualAmount || 0) < 0 ? 'var(--color-danger)' : undefined }}>
            {formatMoney(record.manualAmount)}
          </strong>
          <span className="truncate text-2xs text-text-muted">{record.manualReason}</span>
        </span>
      ) : (
        <span className="flex min-w-0 items-baseline gap-2">
          <strong className="text-md font-extrabold text-text">{formatMoney(record.actualReceived)}</strong>
          <span className="text-2xs text-text-muted">ฐาน {formatMoney(record.commissionableBase)}</span>
        </span>
      )}

      <div className="flex flex-wrap gap-1.5">
        {record.weightMultiplier > 1 ? (
          <StatusBadge tone={record.weightMultiplier === 3 ? 'warning' : 'info'}>{record.weightMultiplier}x</StatusBadge>
        ) : null}
        {record.dealAmountMismatch && (
          <StatusBadge tone="warning">ยอดต่างจากยอดที่เรียกเก็บ</StatusBadge>
        )}
      </div>

      {canReview && (
        <div className="mt-1 flex gap-2">
          <button
            type="button"
            className="secondary-button min-h-11 flex-1"
            style={{ color: 'var(--color-success)', borderColor: 'var(--color-success)' }}
            disabled={saving}
            onClick={(event) => { event.stopPropagation(); onApprove(); }}
          >
            <Icon name="check" size={14} />
            {isCeoReview ? 'CEO อนุมัติ' : 'ผู้จัดการอนุมัติ'}
          </button>
          <button
            type="button"
            className="secondary-button min-h-11 flex-1"
            style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger)' }}
            disabled={saving}
            onClick={(event) => { event.stopPropagation(); onReject(); }}
          >
            <Icon name="close" size={14} />
            ไม่อนุมัติ
          </button>
        </div>
      )}
    </>
  );
}

export function CommissionPage({ user, showToast }) {
  const [searchParams] = useSearchParams();
  const [month, setMonth] = useState(thisMonth);
  const [records, setRecords] = useState([]);
  const [summary, setSummary] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  // account: "record invoice" / create-from-deal flow
  const [ticketIdInput, setTicketIdInput] = useState(searchParams.get('ticketId') || '');
  const [ticketLookupLoading, setTicketLookupLoading] = useState(false);
  const [ticketLookupError, setTicketLookupError] = useState('');
  const [loadedTicket, setLoadedTicket] = useState(null);
  const [eligibleTickets, setEligibleTickets] = useState([]);
  const [createForm, setCreateForm] = useState(emptyCreateForm);
  const [fileInputKey, setFileInputKey] = useState(0);
  const [recentlyCreated, setRecentlyCreated] = useState([]);

  // sales_manager/ceo: review-queue inline input edit
  const [editingId, setEditingId] = useState(null);
  const [deductionDraft, setDeductionDraft] = useState(emptyDeductionDraft);

  const [expandedId, setExpandedId] = useState(null);
  const [clawbackId, setClawbackId] = useState(null); // record id pending clawback reason, or null
  const [rejectId, setRejectId] = useState(null);
  const [approveId, setApproveId] = useState(null); // record id pending approve confirmation, or null

  // Manual commission entries (feat/commission-manual-adjustments): sales_manager/ceo only.
  const [showManualForm, setShowManualForm] = useState(false);
  const [manualForm, setManualForm] = useState(emptyManualForm);
  const [manualSaving, setManualSaving] = useState(false);
  const [repOptions, setRepOptions] = useState([]);

  // Sales is read-only (Slice A3, AUTHZ CHANGE): commission creation moved entirely to the
  // accountant's createFromDeal trigger. Mirrors ROLE_PERMISSIONS exactly — see api/routes.js
  // for the "why" comments tying each key back to a CommissionController/Service role gate.
  const canReview = ROLE_PERMISSIONS.canApproveCommissions.includes(user.role); // sales_manager, ceo
  const canListRecords = ROLE_PERMISSIONS.canListCommissionRecords.includes(user.role); // sales, sales_manager, ceo
  const canCreateFromDeal = ROLE_PERMISSIONS.canCreateCommissionFromDeal.includes(user.role); // account
  const canCreateManual = ROLE_PERMISSIONS.canCreateManualCommission.includes(user.role); // sales_manager, ceo
  const payrollOnly = ROLE_PERMISSIONS.canViewPayrollCommissions.includes(user.role); // hr
  const isSales = user.role === 'sales';

  async function load() {
    if (payrollOnly) {
      setLoading(true);
      try {
        const response = await api.commissions.payrollReady({ payrollMonth: month });
        setSummary(response.summary);
      } catch (error) {
        showToast('error', error.message || 'โหลดข้อมูลค่าคอมไม่สำเร็จ');
      } finally {
        setLoading(false);
      }
      return;
    }
    if (canListRecords) {
      setLoading(true);
      try {
        const response = await api.commissions.list({ payrollMonth: month });
        setRecords(response.commissions ?? []);
      } catch (error) {
        showToast('error', error.message || 'โหลดข้อมูลค่าคอมไม่สำเร็จ');
      } finally {
        setLoading(false);
      }
      return;
    }
    // account: GET /api/commissions has no route for this role on the real backend (see
    // routes.js's canListCommissionRecords comment) — there is nothing to load here.
    setLoading(false);
  }

  // `load` reads `user`-derived role flags that don't change while this page is mounted, so it
  // is intentionally omitted from the dependency array (re-running per render would be a no-op).
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { load(); }, [month]);

  // Best-effort convenience picker for account: the deals TicketRepository's account-role list
  // scoping surfaces are money-PENDING deals, so a CLOSED_PAID deal (already fully paid) often
  // will not appear here — see createFromDeal's NOTE above the mock implementation. Manual
  // ticket-id lookup (below) always works regardless; this list is a nice-to-have on top.
  useEffect(() => {
    if (!canCreateFromDeal) return;
    let cancelled = false;
    api.tickets.list({ salesStage: 'CLOSED_PAID' })
      .then((response) => { if (!cancelled) setEligibleTickets(response.tickets ?? []); })
      .catch(() => { if (!cancelled) setEligibleTickets([]); });
    return () => { cancelled = true; };
  }, [canCreateFromDeal]);

  // Deep-link support: a future "record invoice" call-to-action elsewhere (e.g. the ticket
  // detail page, once it grows one) can send account straight here via /commissions?ticketId=NN.
  useEffect(() => {
    if (!canCreateFromDeal) return;
    const paramTicketId = searchParams.get('ticketId');
    if (paramTicketId) lookupTicket(paramTicketId);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- run once on mount, driven by the URL.
  }, [canCreateFromDeal]);

  // Manual commission entry (feat/commission-manual-adjustments): sales_manager/ceo has no
  // /api/employees access (that's hr-only, see ROLE_PERMISSIONS.canViewEmployees), so the rep
  // picker is a best-effort convenience list derived from tickets sales_manager/ceo can already
  // see (canViewTickets), same pattern as AccountCreateFromDeal's eligibleTickets above. It will
  // not include every employee (e.g. a manager who owns no deals) — the numeric Employee ID
  // field in the form below is always the authoritative, primary path.
  useEffect(() => {
    if (!canCreateManual) return;
    let cancelled = false;
    api.tickets.list({}).then((response) => {
      if (cancelled) return;
      const seen = new Map();
      (response.tickets ?? []).forEach((ticket) => {
        if (ticket.createdById && !seen.has(ticket.createdById)) {
          seen.set(ticket.createdById, ticket.createdByName || `#${ticket.createdById}`);
        }
      });
      setRepOptions([...seen.entries()]
        .map(([id, name]) => ({ id, name }))
        .sort((a, b) => String(a.name).localeCompare(String(b.name), 'th')));
    }).catch(() => { if (!cancelled) setRepOptions([]); });
    return () => { cancelled = true; };
  }, [canCreateManual]);

  const totals = useMemo(() => {
    const base = records.reduce((sum, item) => sum + Number(item.commissionableBase || 0), 0);
    const approved = records.filter((item) => item.status === 'APPROVED').length;
    const submitted = records.filter((item) => item.status === 'SUBMITTED').length;
    const managerApproved = records.filter((item) => item.status === 'MANAGER_APPROVED').length;
    return { base, approved, submitted, managerApproved };
  }, [records]);

  // Sales-only monthly tier breakdown: informational mirror of CommissionCalculator, computed
  // client-side from exactly the records the sales rep can see (backend already scopes list() to
  // their own). Not authoritative — HR's payroll-ready run is — but gives the tier-by-tier
  // context the calc-detail waterfall alone can't (a single invoice's own base vs. the month's).
  const monthlyTierSummary = useMemo(() => {
    if (!isSales) return null;
    const weighted = records
      .filter((item) => !['VOID', 'REJECTED'].includes(item.status) && !isManualKind(item.kind))
      .reduce((sum, item) => sum + Number(item.actualReceived || 0) * Number(item.weightMultiplier || 1), 0);
    const base = monthlyTierBase(weighted);
    const tierResult = tierBreakdown(base);
    // Manual entries (feat/commission-manual-adjustments) never feed the tier calc — only an
    // APPROVED manual amount is added on top of the tier commission for the rep's own final
    // total, mirroring CommissionService#payrollReadySummary exactly (see the manualTotals map
    // there). A MANAGER_APPROVED manual entry (still awaiting CEO sign-off) does not count yet.
    const manualTotal = records
      .filter((item) => isManualKind(item.kind) && item.status === 'APPROVED')
      .reduce((sum, item) => sum + Number(item.manualAmount || 0), 0);
    return { base, ...tierResult, manualTotal, total: round2(tierResult.total + manualTotal) };
  }, [records, isSales]);

  function canManagerReview(record) {
    return record.status === 'SUBMITTED' && user.role === 'sales_manager';
  }

  function canCeoReview(record) {
    return record.status === 'MANAGER_APPROVED' && user.role === 'ceo';
  }

  function canReviewRecord(record) {
    return canManagerReview(record) || canCeoReview(record);
  }

  const commissionColumns = useMemo(() => {
    const columns = [
      {
        // A dedicated expand/collapse toggle column, not `onRowClick` — DataTable renders the
        // whole row as a <button> when `onRowClick` is set, and the actions column below already
        // renders several <button> icon-buttons, which would nest a button inside a button
        // (invalid HTML, breaks click semantics). This column stays a plain cell; only its own
        // button toggles the calc-detail panel.
        key: 'expand',
        header: '',
        render: (record) => (
          <button
            type="button"
            className="icon-button"
            aria-expanded={expandedId === record.id}
            title="ดูรายละเอียดการคำนวณ"
            aria-label="ดูรายละเอียดการคำนวณ"
            onClick={() => setExpandedId((current) => (current === record.id ? null : record.id))}
          >
            <Icon name={expandedId === record.id ? 'chevronUp' : 'chevronDown'} size={14} />
          </button>
        ),
      },
      {
        key: 'invoiceNumber',
        header: 'Invoice / รายการ',
        sortable: true,
        sortAccessor: (record) => (isManualKind(record.kind) ? kindLabel(record.kind) : record.invoiceDetails.invoiceNumber),
        searchAccessor: (record) => (isManualKind(record.kind)
          ? `${kindLabel(record.kind)} ${record.manualReason || ''}`
          : record.invoiceDetails.invoiceNumber),
        render: (record) => (isManualKind(record.kind) ? (
          <span>
            <span className="flex items-center gap-1.5">
              <strong>{MANUAL_KIND_LABELS[record.kind] || record.kind}</strong>
              <StatusBadge tone="info">Manual</StatusBadge>
            </span>
            <small style={{ color: '#64748b', display: 'block' }}>{record.manualReason}</small>
          </span>
        ) : (
          <span>
            <strong>{record.invoiceDetails.invoiceNumber}</strong>
            <small style={{ color: '#64748b', display: 'block' }}>{kindLabel(record.kind)} · {formatThaiDate(record.invoiceDetails.invoiceDate)}</small>
            <small style={{ color: '#64748b', display: 'block' }}>ไฟล์: {record.invoiceDetails.invoiceAttachmentFileName || '-'}</small>
          </span>
        )),
      },
      {
        key: 'salesRepName',
        header: 'Sales',
        sortable: true,
        sortAccessor: (record) => record.salesRepName || record.salesRepId,
        searchAccessor: (record) => record.salesRepName || record.salesRepId,
        render: (record) => <span>{record.salesRepName || record.salesRepId}</span>,
      },
      {
        key: 'actualReceived',
        header: 'ยอดรับจริง / จำนวนเงิน',
        align: 'right',
        sortable: true,
        sortAccessor: (record) => (isManualKind(record.kind) ? Number(record.manualAmount || 0) : Number(record.actualReceived || 0)),
        render: (record) => {
          if (isManualKind(record.kind)) {
            const amount = Number(record.manualAmount || 0);
            return <code style={{ color: amount < 0 ? 'var(--color-danger)' : undefined }}>{formatMoney(amount)}</code>;
          }
          return (
            <span>
              <code>{formatMoney(record.actualReceived)}</code>
              {record.weightMultiplier > 1 && (
                <span style={{ display: 'block', marginTop: 4 }}>
                  <StatusBadge tone={record.weightMultiplier === 3 ? 'warning' : 'info'}>น้ำหนัก {record.weightMultiplier} เท่า</StatusBadge>
                </span>
              )}
              {record.dealAmountMismatch && (
                // The whole point of the Step 9 cross-check flag: surfaced right next to the
                // amount so a reviewer sees it before approving, never buried elsewhere.
                <span style={{ display: 'block', marginTop: 4 }}>
                  <StatusBadge tone="warning">ยอดต่างจากยอดที่เรียกเก็บ</StatusBadge>
                </span>
              )}
            </span>
          );
        },
      },
      {
        key: 'commissionableBase',
        header: 'ฐานค่าคอม',
        align: 'right',
        sortable: true,
        // Manual entries never touch the tier calc (feat/commission-manual-adjustments) — sorted
        // last under this column since there is no base to compare.
        sortAccessor: (record) => (isManualKind(record.kind) ? -1 : Number(record.commissionableBase || 0)),
        render: (record) => (isManualKind(record.kind)
          ? <span className="text-xs text-text-muted">ไม่มีฐานคำนวณ</span>
          : <code>{formatMoney(record.commissionableBase)}</code>),
      },
      {
        key: 'status',
        header: 'สถานะ',
        render: (record) => {
          const status = statusInfo(record.status);
          return (
            <span>
              <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
              <small style={{ color: '#64748b', display: 'block', marginTop: 4 }}>
                ผู้จัดการ: {record.managerApprovedAt ? `${record.managerApprovedByName || '-'} · ${formatThaiDate(record.managerApprovedAt)}` : '-'}
              </small>
              <small style={{ color: '#64748b', display: 'block' }}>
                CEO: {record.ceoApprovedAt ? `${record.ceoApprovedByName || '-'} · ${formatThaiDate(record.ceoApprovedAt)}` : '-'}
              </small>
            </span>
          );
        },
      },
    ];

    // Sales is read-only (Slice A3): no actions column at all — approve/reject/edit are all
    // sales_manager/ceo capabilities, and there is nothing else for sales to do to a commission.
    if (!canReview) return columns;

    columns.push({
      key: 'actions',
      header: '',
      render: (record) => (
        <span style={{ display: 'flex', justifyContent: 'flex-end', gap: 6, flexWrap: 'wrap' }}>
          {!isManualKind(record.kind) && (
            // Deduction editing is an invoice-input concept (grossAmount, bankFees, ...) — a
            // manual entry has no invoiceDetails at all (feat/commission-manual-adjustments), so
            // there is nothing here for beginEdit to populate.
            <button
              type="button"
              className="icon-button"
              title="แก้ไขค่าหัก"
              aria-label="แก้ไขค่าหัก"
              onClick={(event) => { event.stopPropagation(); beginEdit(record); }}
            >
              <Icon name="pencil" size={14} />
            </button>
          )}
          {canReviewRecord(record) && (
            // Success-tinted (not just muted-gray) so approve reads as the
            // positive, serious action — matches DESIGN.md success token.
            <button
              type="button"
              className="icon-button"
              style={{ color: 'var(--color-success)', borderColor: 'var(--color-success)' }}
              title={canCeoReview(record) ? 'CEO อนุมัติ' : 'ผู้จัดการอนุมัติ'}
              aria-label={canCeoReview(record) ? 'CEO อนุมัติ' : 'ผู้จัดการอนุมัติ'}
              disabled={saving}
              onClick={(event) => { event.stopPropagation(); setApproveId(record.id); }}
            >
              <Icon name="check" size={14} />
            </button>
          )}
          {canReviewRecord(record) && (
            // Danger stays outlined per DESIGN.md ("danger is outlined, not
            // filled") — the icon-button is already an outline shape, so this
            // just tints the outline/icon danger-red to separate it from approve.
            <button
              type="button"
              className="icon-button"
              style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger)' }}
              title={canCeoReview(record) ? 'CEO ปฏิเสธ' : 'ผู้จัดการปฏิเสธ'}
              aria-label={canCeoReview(record) ? 'CEO ปฏิเสธ' : 'ผู้จัดการปฏิเสธ'}
              disabled={saving}
              onClick={(event) => { event.stopPropagation(); reject(record.id); }}
            >
              <Icon name="close" size={14} />
            </button>
          )}
          {record.kind === 'SALE' && record.status === 'APPROVED' && (
            <button
              type="button"
              className="icon-button"
              title="บันทึกหักคืน"
              aria-label="บันทึกหักคืน"
              disabled={saving}
              onClick={(event) => { event.stopPropagation(); clawback(record.id); }}
            >
              <Icon name="close" size={14} />
            </button>
          )}
        </span>
      ),
    });
    return columns;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canReview, saving, user.role, expandedId]);

  async function prepareInvoiceAttachment(file) {
    if (!file) throw new Error('กรุณาแนบไฟล์ใบกำกับภาษี');
    if (!['application/pdf', 'image/jpeg', 'image/png'].includes(file.type)) {
      throw new Error('รองรับเฉพาะไฟล์ PDF, JPG หรือ PNG');
    }
    if (!file.type.startsWith('image/')) return file;
    return imageCompression(file, {
      maxSizeMB: 2,
      maxWidthOrHeight: 1600,
      useWebWorker: true,
    });
  }

  function updateCreateForm(field, value) {
    setCreateForm((current) => ({ ...current, [field]: value }));
  }

  // Ticket-detail lookup is unaffected by the account-role list scoping (see the NOTE above
  // mockApi's createFromDeal) — this is the flow's real primary path, not a fallback.
  async function lookupTicket(rawTicketId) {
    const ticketId = Number(rawTicketId);
    if (!ticketId || Number.isNaN(ticketId)) {
      setTicketLookupError('กรุณาระบุเลขที่ Ticket ID ที่ถูกต้อง');
      setLoadedTicket(null);
      return;
    }
    setTicketLookupLoading(true);
    setTicketLookupError('');
    try {
      const response = await api.tickets.get(ticketId);
      const summary = response.ticket?.summary;
      if (!summary) {
        setLoadedTicket(null);
        setTicketLookupError('ไม่พบดีลนี้');
        return;
      }
      if (summary.salesStage !== 'CLOSED_PAID') {
        setLoadedTicket(null);
        setTicketLookupError(`ดีลนี้ยังไม่ถึงขั้นตอนปิดงาน/รับเงินครบ (สถานะปัจจุบัน: ${dealStageLabel(summary.salesStage)}) จึงยังบันทึกค่าคอมไม่ได้`);
        return;
      }
      setLoadedTicket(summary);
      updateCreateForm('grossAmount', summary.amountPayable != null ? String(summary.amountPayable) : '');
    } catch (error) {
      setLoadedTicket(null);
      setTicketLookupError(error.message || 'ไม่พบดีลนี้');
    } finally {
      setTicketLookupLoading(false);
    }
  }

  function selectEligibleTicket(ticketId) {
    setTicketIdInput(ticketId);
    if (ticketId) lookupTicket(ticketId);
    else setLoadedTicket(null);
  }

  async function submitFromDeal(event) {
    event.preventDefault();
    if (!loadedTicket) {
      showToast('error', 'กรุณาโหลดข้อมูลดีลที่ปิดงานแล้วก่อนบันทึก');
      return;
    }
    setSaving(true);
    try {
      const invoiceAttachment = await prepareInvoiceAttachment(createForm.invoiceAttachment);
      const response = await api.commissions.createFromDeal({
        ticketId: loadedTicket.id,
        invoiceNumber: createForm.invoiceNumber.trim(),
        invoiceDate: createForm.invoiceDate,
        grossAmount: numberOrNull(createForm.grossAmount),
        bankFees: numberOrNull(createForm.bankFees) ?? 0,
        suspenseVat: numberOrNull(createForm.suspenseVat) ?? 0,
        transportFee: numberOrNull(createForm.transportFee) ?? 0,
        cutFee: numberOrNull(createForm.cutFee) ?? 0,
        shortfall: numberOrNull(createForm.shortfall) ?? 0,
        withholdingTax: numberOrNull(createForm.withholdingTax) ?? 0,
        overpayment: numberOrNull(createForm.overpayment) ?? 0,
        invoiceAttachment,
      });
      setRecentlyCreated((current) => [response.commission, ...current]);
      setCreateForm(emptyCreateForm);
      setFileInputKey((key) => key + 1);
      setLoadedTicket(null);
      setTicketIdInput('');
      showToast('success', 'บันทึกใบกำกับและสร้างคำขอค่าคอมแล้ว');
    } catch (error) {
      showToast('error', error.message || 'บันทึกไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  function updateManualForm(field, value) {
    setManualForm((current) => ({ ...current, [field]: value }));
  }

  function openManualForm() {
    setManualForm({ ...emptyManualForm, payrollMonth: month });
    setShowManualForm(true);
  }

  // Manual commission entries (feat/commission-manual-adjustments): NO auto-computation anywhere
  // here — every amount is hand-typed by sales_manager/ceo, submitted verbatim to
  // POST /api/commissions/manual. (A future CEO-confirmed auto-config may prefill a suggested
  // amount for specific kinds, e.g. STOCK_BONUS — not implemented; owner decision is manual
  // across the UI for now.)
  async function submitManual(event) {
    event.preventDefault();
    if (!manualForm.salesRepId) {
      showToast('error', 'กรุณาระบุพนักงานขาย');
      return;
    }
    if (manualForm.amount === '' || Number.isNaN(Number(manualForm.amount))) {
      showToast('error', 'กรุณาระบุจำนวนเงิน');
      return;
    }
    if (manualForm.kind !== 'ADJUSTMENT' && Number(manualForm.amount) < 0) {
      showToast('error', 'จำนวนเงินสำหรับประเภทนี้ต้องไม่ติดลบ');
      return;
    }
    if (!manualForm.reason.trim()) {
      showToast('error', 'กรุณาระบุเหตุผล');
      return;
    }
    setManualSaving(true);
    try {
      await api.commissions.createManualCommission({
        salesRepId: Number(manualForm.salesRepId),
        kind: manualForm.kind,
        amount: Number(manualForm.amount),
        reason: manualForm.reason.trim(),
        payrollMonth: manualForm.payrollMonth ? `${manualForm.payrollMonth}-01` : undefined,
      });
      showToast('success', 'เพิ่มค่าคอมด้วยตนเองแล้ว');
      setManualForm(emptyManualForm);
      setShowManualForm(false);
      await load();
    } catch (error) {
      showToast('error', error.message || 'เพิ่มค่าคอมไม่สำเร็จ');
    } finally {
      setManualSaving(false);
    }
  }

  function beginEdit(record) {
    setEditingId(record.id);
    setDeductionDraft({
      grossAmount: Number(record.invoiceDetails.grossAmount || 0),
      bankFees: Number(record.invoiceDetails.bankFees || 0),
      suspenseVat: Number(record.invoiceDetails.suspenseVat || 0),
      transportFee: Number(record.invoiceDetails.transportFee || 0),
      cutFee: Number(record.invoiceDetails.cutFee || 0),
      shortfall: Number(record.invoiceDetails.shortfall || 0),
      withholdingTax: Number(record.invoiceDetails.withholdingTax || 0),
      overpayment: Number(record.invoiceDetails.overpayment || 0),
      weightMultiplier: Number(record.weightMultiplier || 1),
      reason: '',
    });
  }

  function updateDeductionDraft(field, value) {
    setDeductionDraft((current) => ({ ...current, [field]: value }));
  }

  // Live preview only — the server always recomputes the final commission from whatever it
  // persists; this never gets sent as-is, only the individual input fields do.
  const deductionPreview = useMemo(() => invoiceCalculation(deductionDraft), [deductionDraft]);

  async function saveDeductions(id) {
    if (!deductionDraft.reason.trim()) {
      showToast('error', 'กรุณาระบุเหตุผลในการแก้ไข');
      return;
    }
    setSaving(true);
    try {
      await api.commissions.updateDeductions(id, {
        grossAmount: deductionDraft.grossAmount,
        bankFees: deductionDraft.bankFees,
        suspenseVat: deductionDraft.suspenseVat,
        transportFee: deductionDraft.transportFee,
        cutFee: deductionDraft.cutFee,
        shortfall: deductionDraft.shortfall,
        withholdingTax: deductionDraft.withholdingTax,
        overpayment: deductionDraft.overpayment,
        weightMultiplier: deductionDraft.weightMultiplier,
        reason: deductionDraft.reason.trim(),
      });
      setEditingId(null);
      showToast('success', 'อัปเดตค่าคอมแล้ว');
      await load();
    } catch (error) {
      showToast('error', error.message || 'อัปเดตไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function confirmApprove() {
    setSaving(true);
    try {
      await api.commissions.approve(approveId);
      showToast('success', 'อนุมัติค่าคอมแล้ว');
      await load();
    } catch (error) {
      showToast('error', error.message || 'อนุมัติไม่สำเร็จ');
    } finally {
      setSaving(false);
      setApproveId(null);
    }
  }

  function reject(id) {
    setRejectId(id);
  }

  async function confirmReject(reviewerNote) {
    setSaving(true);
    try {
      await api.commissions.reject(rejectId, { reviewerNote });
      showToast('success', 'ปฏิเสธค่าคอมแล้ว');
      await load();
    } catch (error) {
      showToast('error', error.message || 'ปฏิเสธไม่สำเร็จ');
    } finally {
      setSaving(false);
      setRejectId(null);
    }
  }

  function clawback(id) {
    setClawbackId(id);
  }

  async function confirmClawback(reason) {
    setSaving(true);
    try {
      await api.commissions.clawback(clawbackId, { reason });
      showToast('success', 'บันทึกรายการหักคืนแล้ว');
      await load();
    } catch (error) {
      showToast('error', error.message || 'บันทึกหักคืนไม่สำเร็จ');
    } finally {
      setSaving(false);
      setClawbackId(null);
    }
  }

  return (
    <div className="page-stack">
      <PageHeader
        title="ค่าคอมมิชชัน"
        subtitle="Sales & Commission Management"
        actions={!canCreateFromDeal ? (
          <div className="flex flex-wrap items-center gap-3">
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: 8, fontSize: 13, fontWeight: 700 }}>
              รอบเดือน
              <input type="month" value={month} onChange={(event) => setMonth(event.target.value)} style={{ width: 150 }} />
            </label>
            {canCreateManual && (
              <button type="button" className="secondary-button" onClick={() => (showManualForm ? setShowManualForm(false) : openManualForm())}>
                <Icon name="plus" size={14} />
                เพิ่มค่าคอมด้วยตนเอง
              </button>
            )}
          </div>
        ) : undefined}
      />

      {payrollOnly ? (
        <PayrollSummary summary={summary} loading={loading} />
      ) : canCreateFromDeal ? (
        <AccountCreateFromDeal
          ticketIdInput={ticketIdInput}
          setTicketIdInput={setTicketIdInput}
          ticketLookupLoading={ticketLookupLoading}
          ticketLookupError={ticketLookupError}
          loadedTicket={loadedTicket}
          eligibleTickets={eligibleTickets}
          onLookup={() => lookupTicket(ticketIdInput)}
          onSelectEligible={selectEligibleTicket}
          createForm={createForm}
          updateCreateForm={updateCreateForm}
          fileInputKey={fileInputKey}
          onSubmit={submitFromDeal}
          saving={saving}
          recentlyCreated={recentlyCreated}
        />
      ) : (
        <>
          <div className="stat-grid">
            <StatCard icon="badge" label="ฐานค่าคอมเดือนนี้" value={formatMoney(totals.base)} helper="Commissionable base" tone="indigo" />
            <StatCard icon="check" label="อนุมัติแล้ว" value={totals.approved} helper="Approved records" tone="teal" />
            <StatCard icon="clock" label="รอผู้จัดการ" value={totals.submitted} helper="Submitted records" tone="amber" />
            <StatCard icon="clock" label="รอ CEO" value={totals.managerApproved} helper="Manager approved" tone="indigo" />
          </div>

          {canCreateManual && showManualForm && (
            <ManualCommissionForm
              form={manualForm}
              onChange={updateManualForm}
              repOptions={repOptions}
              onSubmit={submitManual}
              onCancel={() => setShowManualForm(false)}
              saving={manualSaving}
            />
          )}

          {monthlyTierSummary ? <MonthlyTierPanel summary={monthlyTierSummary} /> : null}

          <DataTable
            columns={commissionColumns}
            rows={records}
            getRowKey={(record) => record.id}
            gridClassName="commission-table"
            renderExpanded={(record) => (record.id === expandedId ? <CommissionCalcBreakdown record={record} /> : null)}
            mobileCard={(record) => (
              <CommissionCard
                record={record}
                canReview={canReview && canReviewRecord(record)}
                isCeoReview={canCeoReview(record)}
                saving={saving}
                expanded={expandedId === record.id}
                onToggleExpand={() => setExpandedId((current) => (current === record.id ? null : record.id))}
                onApprove={() => setApproveId(record.id)}
                onReject={() => reject(record.id)}
              />
            )}
            pageSize={20}
            searchable
            searchPlaceholder="ค้นหาเลขที่ใบกำกับ / ชื่อ Sales"
            loading={loading}
            emptyState={{
              icon: 'badge',
              title: 'ยังไม่มีรายการค่าคอม',
              description: 'เลือกรอบเดือนอื่นหรือรอฝ่ายบัญชีบันทึกใบกำกับ',
            }}
          />

          {editingId != null && (() => {
            const editingRecord = records.find((record) => record.id === editingId);
            if (!editingRecord) return null;
            return (
              <ManagerReviewEditPanel
                record={editingRecord}
                draft={deductionDraft}
                onChange={updateDeductionDraft}
                preview={deductionPreview}
                saving={saving}
                onSave={() => saveDeductions(editingRecord.id)}
                onCancel={() => setEditingId(null)}
              />
            );
          })()}
        </>
      )}

      <ConfirmDialog
        open={approveId != null}
        title="ยืนยันการอนุมัติค่าคอม"
        message={(() => {
          const record = records.find((item) => item.id === approveId);
          if (!record) return 'ยืนยันการอนุมัติค่าคอมมิชชันรายการนี้?';
          // Status-transition copy quoted from api.commissions.approve
          // (src/api/mockApi.js): SUBMITTED -> MANAGER_APPROVED when a
          // sales_manager approves; MANAGER_APPROVED -> APPROVED when the CEO
          // approves a manager-approved record.
          const nextStep = canCeoReview(record)
            ? 'สถานะจะเปลี่ยนเป็น "อนุมัติแล้ว" และพร้อมเข้ารอบ Payroll'
            : 'สถานะจะเปลี่ยนเป็น "รอ CEO" เพื่อรออนุมัติขั้นสุดท้าย';
          if (isManualKind(record.kind)) {
            return (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <p className="confirm-dialog-message" style={{ margin: 0 }}>
                  ตรวจสอบก่อนอนุมัติค่าคอมแบบ <strong>{MANUAL_KIND_LABELS[record.kind] || record.kind}</strong> ของ <strong>{record.salesRepName || record.salesRepId}</strong>
                </p>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 14, fontWeight: 700, borderTop: '1px solid #e6eaf0', paddingTop: 8 }}>
                  <span>จำนวนเงิน</span>
                  <code className="font-mono" style={{ color: Number(record.manualAmount || 0) < 0 ? 'var(--color-danger)' : undefined }}>
                    {formatMoney(record.manualAmount)}
                  </code>
                </div>
                <p style={{ margin: 0, fontSize: 12, color: '#64748b' }}>เหตุผล: {record.manualReason || '-'}</p>
                <p style={{ margin: 0, fontSize: 12, color: '#64748b' }}>{nextStep}</p>
              </div>
            );
          }
          return (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <p className="confirm-dialog-message" style={{ margin: 0 }}>
                ตรวจสอบยอดก่อนอนุมัติใบกำกับ <strong>{record.invoiceDetails.invoiceNumber}</strong> ของ <strong>{record.salesRepName || record.salesRepId}</strong>
              </p>
              {record.dealAmountMismatch && (
                // Step 9 cross-check: grossAmount diverged >5% from the linked deal's actual
                // payableAmount at submission time. Never blocked submission — surfaced here so
                // the approver sees it before signing off, which is the entire point of the flag.
                <StatusBadge tone="warning">ยอดต่างจากยอดที่เรียกเก็บ — โปรดตรวจสอบก่อนอนุมัติ</StatusBadge>
              )}
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, borderTop: '1px solid #e6eaf0', paddingTop: 8 }}>
                <span style={{ color: '#475569' }}>ยอดรับจริง</span>
                <code className="font-mono">{formatMoney(record.actualReceived)}</code>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 14, fontWeight: 700 }}>
                <span>ฐานค่าคอม</span>
                <code className="font-mono">{formatMoney(record.commissionableBase)}</code>
              </div>
              <p style={{ margin: 0, fontSize: 12, color: '#64748b' }}>{nextStep}</p>
            </div>
          );
        })()}
        confirmLabel="อนุมัติ"
        busy={saving}
        onCancel={() => setApproveId(null)}
        onConfirm={confirmApprove}
      />
      <ConfirmDialog
        open={clawbackId != null}
        tone="danger"
        title="บันทึกหักคืน"
        message="ยืนยันการยกเลิก/คืนเงินค่าคอมมิชชันรายการนี้?"
        requireReason
        reasonLabel="เหตุผลการยกเลิก/คืนเงิน"
        busy={saving}
        onCancel={() => setClawbackId(null)}
        onConfirm={confirmClawback}
      />
      <ConfirmDialog
        open={rejectId != null}
        tone="danger"
        title="ปฏิเสธค่าคอม"
        message="ยืนยันการปฏิเสธรายการค่าคอมนี้?"
        requireReason
        reasonLabel="เหตุผลการปฏิเสธ"
        busy={saving}
        onCancel={() => setRejectId(null)}
        onConfirm={confirmReject}
      />
    </div>
  );
}

/** account role: pick/lookup a CLOSED_PAID deal, upload the tax invoice, submit. */
function AccountCreateFromDeal({
  ticketIdInput, setTicketIdInput, ticketLookupLoading, ticketLookupError, loadedTicket,
  eligibleTickets, onLookup, onSelectEligible, createForm, updateCreateForm, fileInputKey,
  onSubmit, saving, recentlyCreated,
}) {
  return (
    <>
      <section className="panel" style={{ padding: 0 }}>
        <div className="panel-header" style={{ padding: '14px 18px' }}>
          <h2>บันทึกใบกำกับภาษี / สร้างคำขอค่าคอมจากดีล</h2>
        </div>
        <div className="grid gap-4" style={{ padding: 18 }}>
          <div className="grid gap-2">
            <label htmlFor="commission-ticket-lookup" className="text-sm font-bold">
              เลขที่ Ticket ID ของดีลที่ปิดงาน/รับเงินครบแล้ว (CLOSED_PAID)
            </label>
            <div className="flex flex-wrap items-center gap-2">
              <input
                id="commission-ticket-lookup"
                type="number"
                min="1"
                value={ticketIdInput}
                onChange={(event) => setTicketIdInput(event.target.value)}
                placeholder="เช่น 42"
                style={{ maxWidth: 160 }}
              />
              <button type="button" className="secondary-button" disabled={ticketLookupLoading || !ticketIdInput} onClick={onLookup}>
                <Icon name="search" size={14} />
                {ticketLookupLoading ? 'กำลังโหลด...' : 'โหลดข้อมูลดีล'}
              </button>
            </div>
            {eligibleTickets.length > 0 ? (
              <label className="grid gap-1.5 text-sm">
                หรือเลือกจากดีลที่ปรากฏในรายการ
                <select value="" onChange={(event) => onSelectEligible(event.target.value)}>
                  <option value="">— เลือกดีล —</option>
                  {eligibleTickets.map((ticket) => (
                    <option key={ticket.id} value={ticket.id}>
                      {ticket.code} · {ticket.customerName || 'ไม่ระบุลูกค้า'}
                    </option>
                  ))}
                </select>
              </label>
            ) : (
              <p className="m-0 text-xs text-text-muted">
                ดีลที่ปิดงาน/รับเงินครบแล้วอาจไม่ปรากฏในรายการอัตโนมัติ (รายการนี้แสดงเฉพาะดีลที่ยังรอดำเนินการด้านการเงิน) — กรอกเลขที่ Ticket ID ด้วยตนเองด้านบนได้เสมอ
              </p>
            )}
            {ticketLookupError ? <StatusBadge tone="danger">{ticketLookupError}</StatusBadge> : null}
            {loadedTicket ? (
              <div className="rounded-md border border-border bg-surface-subtle p-3 text-sm">
                <strong>{loadedTicket.code}</strong> · {loadedTicket.customerName || 'ไม่ระบุลูกค้า'}
                <div className="mt-1 flex items-center justify-between">
                  <span className="text-text-muted">ยอดที่ต้องชำระของดีล</span>
                  <code className="font-mono">{formatMoney(loadedTicket.amountPayable)}</code>
                </div>
              </div>
            ) : null}
          </div>

          <form className="form-grid" onSubmit={onSubmit}>
            <label>
              Invoice Number *
              <input value={createForm.invoiceNumber} onChange={(event) => updateCreateForm('invoiceNumber', event.target.value)} required />
            </label>
            <label>
              Invoice Date *
              <input type="date" value={createForm.invoiceDate} onChange={(event) => updateCreateForm('invoiceDate', event.target.value)} required />
            </label>
            <div className="grid gap-[7px]">
              <label htmlFor="commission-invoice-file">Tax Invoice File *</label>
              <FileUploadField
                id="commission-invoice-file"
                key={fileInputKey}
                accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png"
                onChange={(event) => updateCreateForm('invoiceAttachment', event.target.files?.[0] || null)}
                required
                helperText="PDF, JPG หรือ PNG"
              />
            </div>
            <label>
              Gross Amount * <small className="text-text-muted">(ค่าเริ่มต้นจากยอดที่ต้องชำระของดีล แก้ไขได้)</small>
              <input type="number" min="0" step="0.01" value={createForm.grossAmount} onChange={(event) => updateCreateForm('grossAmount', event.target.value)} required />
            </label>
            <label>
              Bank Fees
              <input type="number" min="0" step="0.01" value={createForm.bankFees} onChange={(event) => updateCreateForm('bankFees', event.target.value)} />
            </label>
            <label>
              Suspense VAT
              <input type="number" min="0" step="0.01" value={createForm.suspenseVat} onChange={(event) => updateCreateForm('suspenseVat', event.target.value)} />
            </label>
            <label>
              ค่าขนส่ง
              <input type="number" min="0" step="0.01" value={createForm.transportFee} onChange={(event) => updateCreateForm('transportFee', event.target.value)} />
            </label>
            <label>
              ค่าตัด
              <input type="number" min="0" step="0.01" value={createForm.cutFee} onChange={(event) => updateCreateForm('cutFee', event.target.value)} />
            </label>
            <label>
              รับเงินขาด
              <input type="number" min="0" step="0.01" value={createForm.shortfall} onChange={(event) => updateCreateForm('shortfall', event.target.value)} />
            </label>
            <label>
              หัก ณ ที่จ่าย (WHT)
              <input type="number" min="0" step="0.01" value={createForm.withholdingTax} onChange={(event) => updateCreateForm('withholdingTax', event.target.value)} />
            </label>
            <label>
              รับเงินเกิน
              <input type="number" min="0" step="0.01" value={createForm.overpayment} onChange={(event) => updateCreateForm('overpayment', event.target.value)} />
            </label>

            <div className="span-2 flex flex-wrap justify-end gap-[10px] max-[720px]:flex-col-reverse">
              <button type="submit" className="primary-button max-[720px]:!min-h-11 max-[720px]:!w-full" disabled={saving || !loadedTicket}>
                <Icon name="check" size={14} />
                บันทึกและสร้างคำขอค่าคอม
              </button>
            </div>
          </form>
        </div>
      </section>

      {recentlyCreated.length > 0 ? (
        <section className="panel">
          <div className="panel-header">
            <h2>บันทึกล่าสุดในเซสชันนี้</h2>
          </div>
          <div className="grid gap-2">
            {recentlyCreated.map((record) => {
              const status = statusInfo(record.status);
              return (
                <div key={record.id} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border bg-surface-subtle p-3 text-sm">
                  <span>
                    <strong>{record.invoiceDetails.invoiceNumber}</strong>
                    <span className="ml-2 text-text-muted">{record.salesRepName || record.salesRepId}</span>
                  </span>
                  <span className="flex items-center gap-2">
                    <code className="font-mono">{formatMoney(record.commissionableBase)}</code>
                    <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                  </span>
                </div>
              );
            })}
          </div>
        </section>
      ) : null}
    </>
  );
}

/**
 * sales_manager/ceo manual commission entry (feat/commission-manual-adjustments): every amount
 * here is hand-typed and submitted verbatim — there is NO auto-computation anywhere in this
 * form. A future CEO-confirmed auto-config may prefill a suggested amount for specific kinds
 * (e.g. STOCK_BONUS) later; owner decision for now is manual across the UI, so this form stays a
 * plain number input with no formula behind it.
 *
 * Created by sales_manager lands MANAGER_APPROVED (needs CEO sign-off); created by ceo lands
 * APPROVED immediately — see CommissionService#createManualCommission. Either way it then flows
 * through the exact same approve/reject controls as a SALE commission (no parallel UI here).
 */
function ManualCommissionForm({ form, onChange, repOptions, onSubmit, onCancel, saving }) {
  const isAdjustment = form.kind === 'ADJUSTMENT';
  return (
    <section className="panel" style={{ padding: 0 }}>
      <div className="panel-header" style={{ padding: '14px 18px' }}>
        <h2>เพิ่มค่าคอมด้วยตนเอง</h2>
      </div>
      <form className="form-grid" style={{ padding: 18 }} onSubmit={onSubmit}>
        <label>
          ประเภท *
          <select value={form.kind} onChange={(event) => onChange('kind', event.target.value)}>
            {MANUAL_KINDS.map((kind) => (
              <option key={kind} value={kind}>{MANUAL_KIND_LABELS[kind]}</option>
            ))}
          </select>
        </label>
        <label>
          รหัสพนักงาน (Employee ID) ของพนักงานขาย *
          <input
            type="number"
            min="1"
            value={form.salesRepId}
            onChange={(event) => onChange('salesRepId', event.target.value)}
            placeholder="เช่น 42"
            required
          />
        </label>
        {repOptions.length > 0 && (
          <label className="span-2">
            หรือเลือกจากรายชื่อ (รายการนี้มาจากดีลที่มีอยู่ อาจไม่ครบทุกคน)
            <select value="" onChange={(event) => { if (event.target.value) onChange('salesRepId', event.target.value); }}>
              <option value="">— เลือกพนักงานขาย —</option>
              {repOptions.map((rep) => (
                <option key={rep.id} value={rep.id}>{rep.name}</option>
              ))}
            </select>
          </label>
        )}
        <label>
          จำนวนเงิน (บาท) *
          <input
            type="number"
            step="0.01"
            min={isAdjustment ? undefined : 0}
            value={form.amount}
            onChange={(event) => onChange('amount', event.target.value)}
            required
          />
          <small className="text-text-muted">
            {isAdjustment
              ? 'ใส่ค่าลบเพื่อหักออกจากค่าคอมของพนักงาน (เช่น -2000)'
              : 'ต้องไม่ติดลบ'}
          </small>
        </label>
        <label>
          รอบเดือน
          <input type="month" value={form.payrollMonth} onChange={(event) => onChange('payrollMonth', event.target.value)} />
        </label>
        <label className="span-2">
          เหตุผล *
          <textarea
            rows={2}
            value={form.reason}
            onChange={(event) => onChange('reason', event.target.value)}
            required
          />
        </label>

        <div className="span-2 flex flex-wrap justify-end gap-[10px] max-[720px]:flex-col-reverse">
          <button type="button" className="secondary-button max-[720px]:!min-h-11 max-[720px]:!w-full" disabled={saving} onClick={onCancel}>
            ยกเลิก
          </button>
          <button type="submit" className="primary-button max-[720px]:!min-h-11 max-[720px]:!w-full" disabled={saving}>
            <Icon name="check" size={14} />
            บันทึกค่าคอม
          </button>
        </div>
      </form>
    </section>
  );
}

/**
 * sales_manager/ceo review-queue edit panel: every input field (not the final amount) is
 * editable, each save requires a reason, and the recomputed actualReceived/commissionableBase
 * previews live as the reviewer types — the final commission itself is never hand-entered.
 */
function ManagerReviewEditPanel({ record, draft, onChange, preview, saving, onSave, onCancel }) {
  const fields = [
    ['grossAmount', 'Gross Amount'],
    ['bankFees', 'Bank Fees'],
    ['suspenseVat', 'Suspense VAT'],
    ['transportFee', 'ค่าขนส่ง'],
    ['cutFee', 'ค่าตัด'],
    ['shortfall', 'รับเงินขาด'],
    ['withholdingTax', 'หัก ณ ที่จ่าย (WHT)'],
    ['overpayment', 'รับเงินเกิน'],
  ];
  return (
    <section className="commission-row-wrap panel">
      <div className="panel-header">
        <h2>แก้ไขข้อมูลใบกำกับ {record.invoiceDetails.invoiceNumber}</h2>
      </div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        {fields.map(([key, label]) => (
          <label key={key} className="text-xs font-bold">
            {label}
            <input
              type="number"
              min="0"
              step="0.01"
              value={draft[key]}
              onChange={(event) => onChange(key, Number(event.target.value))}
              className="mt-1"
            />
          </label>
        ))}
        <label className="text-xs font-bold">
          น้ำหนักฐานคอม (Weight Multiplier)
          <select
            value={draft.weightMultiplier}
            onChange={(event) => onChange('weightMultiplier', Number(event.target.value))}
            className="mt-1"
          >
            <option value={1}>1 เท่า (ปกติ)</option>
            <option value={2}>2 เท่า (ยืนยันนโยบายแล้ว)</option>
            <option value={3}>3 เท่า (ยังไม่ยืนยันนโยบาย)</option>
          </select>
        </label>
      </div>
      {draft.weightMultiplier === 3 ? (
        <p className="mt-2">
          <StatusBadge tone="warning">น้ำหนัก 3 เท่ายังไม่ได้รับการยืนยันเป็นนโยบาย — ใช้เฉพาะเมื่อจำเป็นและมีการตรวจสอบแล้วเท่านั้น</StatusBadge>
        </p>
      ) : null}

      <div className="mt-3 grid grid-cols-2 gap-3 rounded-md border border-border bg-surface-subtle p-3 text-sm sm:grid-cols-4">
        <span>
          <small className="block text-text-muted">ยอดรับจริง (คำนวณใหม่)</small>
          <code className="font-mono">{formatMoney(preview.actualReceived)}</code>
        </span>
        <span>
          <small className="block text-text-muted">ฐานค่าคอม (คำนวณใหม่)</small>
          <code className="font-mono">{formatMoney(preview.commissionableBase)}</code>
        </span>
      </div>

      <label className="mt-3 block text-xs font-bold">
        เหตุผลในการแก้ไข *
        <textarea
          rows={2}
          value={draft.reason}
          onChange={(event) => onChange('reason', event.target.value)}
          className="mt-1 w-full"
          required
        />
      </label>

      <div className="mt-3 flex gap-2">
        <button type="button" className="primary-button" disabled={saving || !draft.reason.trim()} onClick={onSave}>บันทึก</button>
        <button type="button" className="secondary-button" disabled={saving} onClick={onCancel}>ยกเลิก</button>
      </div>
    </section>
  );
}

/** sales-only: informational tier-by-tier breakdown of the currently selected payroll month. */
function MonthlyTierPanel({ summary }) {
  const [open, setOpen] = useState(false);
  return (
    <section className="panel">
      <div className="panel-header">
        <h2>ขั้นบันไดค่าคอมเดือนนี้ (ประมาณการ)</h2>
        <button type="button" className="icon-button" onClick={() => setOpen((current) => !current)} aria-expanded={open} title={open ? 'ย่อ' : 'ขยาย'}>
          <Icon name={open ? 'chevronUp' : 'chevronDown'} size={16} />
        </button>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <span>
          <small className="block text-text-muted">ฐานค่าคอมรวมเดือนนี้ (น้ำหนักรวมแล้ว)</small>
          <code className="font-mono text-base font-bold">{formatMoney(summary.base)}</code>
        </span>
        <span className="text-right">
          <small className="block text-text-muted">ค่าคอมประมาณการ</small>
          <code className="font-mono text-base font-bold">{formatMoney(summary.total)}</code>
        </span>
      </div>
      {summary.manualTotal ? (
        // Manual entries (feat/commission-manual-adjustments) never feed the tier calc above —
        // this is the rep's own APPROVED manual amount, already folded into "ค่าคอมประมาณการ"
        // on top of the tier commission, same as CommissionService#payrollReadySummary.
        <div className="mt-2 flex items-center justify-between gap-3 text-sm">
          <span className="text-text-muted">ค่าคอมปรับปรุง/โบนัสที่อนุมัติแล้ว (นอกขั้นบันได)</span>
          <code className="font-mono" style={{ color: summary.manualTotal < 0 ? 'var(--color-danger)' : undefined }}>
            {formatMoney(summary.manualTotal)}
          </code>
        </div>
      ) : null}
      {summary.belowFloor ? (
        <p className="mt-2">
          <StatusBadge tone="neutral">ฐานเดือนนี้ต่ำกว่า 50,000 บาท — ยังไม่มีค่าคอมตามเกณฑ์นโยบาย</StatusBadge>
        </p>
      ) : null}
      {open ? (
        <div className="mt-3 overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="text-text-muted">
                <th className="pb-1 pr-3 font-semibold">ขั้น</th>
                <th className="pb-1 pr-3 font-semibold">ช่วง (บาท)</th>
                <th className="pb-1 pr-3 font-semibold text-right">อัตรา</th>
                <th className="pb-1 font-semibold text-right">ค่าคอมในขั้นนี้</th>
              </tr>
            </thead>
            <tbody>
              {summary.rows.map((row) => (
                <tr key={row.tierNumber} className="border-t border-border">
                  <td className="py-1 pr-3">{row.tierNumber}</td>
                  <td className="py-1 pr-3">
                    {formatMoney(row.lowerBound)} – {row.upperBound == null ? 'ขึ้นไป' : formatMoney(row.upperBound)}
                  </td>
                  <td className="py-1 pr-3 text-right">{row.ratePercent.toFixed(2)}%</td>
                  <td className="py-1 text-right"><code className="font-mono">{formatMoney(row.commission)}</code></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </section>
  );
}

function PayrollSummary({ summary, loading }) {
  if (loading) {
    return (
      <div className="stat-grid" aria-busy="true" aria-label="กำลังโหลดสรุปค่าคอมมิชชัน Payroll">
        <SkeletonCard lines={2} />
        <SkeletonCard lines={2} />
        <SkeletonCard lines={2} />
      </div>
    );
  }
  if (!summary) {
    return <EmptyState icon="badge" title="ยังไม่มีข้อมูลค่าคอม" description="เลือกรอบเดือนอื่นเพื่อตรวจสอบ" />;
  }
  return (
    <>
      <div className="stat-grid">
        <StatCard icon="badge" label="ฐานค่าคอมรวม" value={formatMoney(summary.totalCommissionableBase)} helper="Payroll-ready base" tone="indigo" />
        <StatCard icon="check" label="ค่าคอมที่ต้องจ่าย" value={formatMoney(summary.totalCommissionAmount)} helper={summary.status} tone="teal" />
        <StatCard icon="users" label="จำนวน Sales" value={summary.salesReps?.length ?? 0} helper="Sales reps" tone="blue" />
      </div>
      <section className="table-panel">
        <div className="commission-payroll-table table-head">
          <span>Sales Rep</span>
          <span>ฐานค่าคอม</span>
          <span>ค่าคอม</span>
        </div>
        {(summary.salesReps ?? []).length === 0 ? (
          <EmptyState icon="badge" title="ไม่มีรายการอนุมัติ" description="ยังไม่มีค่าคอมที่พร้อมเข้า Payroll ในเดือนนี้" />
        ) : summary.salesReps.map((rep) => (
          <div key={rep.salesRepId} className="commission-payroll-table data-row">
            <strong>{rep.salesRepName || rep.salesRepId}</strong>
            <code>{formatMoney(rep.commissionableBase)}</code>
            <code>{formatMoney(rep.commissionAmount)}</code>
          </div>
        ))}
      </section>
    </>
  );
}
