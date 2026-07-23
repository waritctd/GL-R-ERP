import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel, StatGrid } from '../../components/common/Layout.jsx';
import { SkeletonCard } from '../../components/common/Skeleton.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney, formatThaiDate, bangkokMonthStartIso } from '../../utils/format.js';

/**
 * CEO exec-cockpit landing (plan: docs/agent-handoffs/valiant-watching-map.md
 * "6. CEO"). CEO keeps every existing sales surface + nav item unchanged —
 * this component only adds a role-shaped `/` landing that pulls the
 * cross-domain decisions only the CEO can make into one worklist, instead of
 * making the CEO hunt across ใบขอราคา / รายการดีล / ค่าคอมมิชชัน / คำขอ / ลา.
 *
 * Every row here deep-links to the real page that owns the mutation — this
 * component never calls an approve/verify/decide endpoint itself (see
 * docs/role-scoped-views.md "never duplicate a mutation").
 */

// Step 3 (CEO Selling Price Decision): a pricing request is CEO-actionable in
// either of these two statuses — see pricingRequestMeta.js's ALLOWED_TRANSITIONS.
// NOTE: mockApi's queue({status}) whitelist is missing 'CEO_REVIEWING' (a
// pre-existing gap noted in pricingRequestMeta.js), so this fetches the whole
// active queue and filters client-side rather than calling queue() per status.
const CEO_PRICE_STATUSES = ['READY_FOR_CEO_REVIEW', 'CEO_REVIEWING'];

// Mirrors CommissionPage.jsx's local `kindLabel` (not exported) — duplicated
// here in miniature since the worklist row only needs a short label, not the
// full calc-breakdown view.
const COMMISSION_KIND_LABELS = {
  SALE: 'ค่าคอมขาย',
  ADJUSTMENT: 'ปรับปรุง/รับช่วงงาน',
  MANAGER: 'ค่าคอมผู้จัดการ/ทีม',
  STOCK_BONUS: 'โบนัสขายสต็อก',
  INCENTIVE: 'Incentive ตามเป้า',
};

function commissionKindLabel(kind) {
  return COMMISSION_KIND_LABELS[kind] ?? 'ค่าคอม';
}

// Stable empty-array fallback: `data ?? []` would otherwise hand every
// downstream useMemo a fresh array identity on each render the query is
// still loading, defeating memoization (react-hooks/exhaustive-deps).
const EMPTY_LIST = [];

function moneySum(items, pick) {
  return items.reduce((sum, item) => sum + (Number(pick(item)) || 0), 0);
}

function formatWorkDate(value) {
  if (!value) return '-';
  const date = new Date(`${value}T00:00:00+07:00`);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', { dateStyle: 'medium', timeZone: 'Asia/Bangkok' }).format(date);
}

/**
 * One row of the "รออนุมัติจากคุณ" cross-domain worklist. Deliberately a plain
 * row (not a whole-row <button>) so the CTA can stay a real, separately-
 * labelled button per domain (ตั้งราคา / ตรวจปิดงาน / อนุมัติ) instead of one
 * generic "ไปที่หน้า" click target — mirrors the QueueCard pattern in
 * PricingRequestQueuePage.jsx (a nested interactive control needs its own
 * element, never a parent <button>).
 */
function WorklistRow({ row }) {
  return (
    <div
      data-testid={`worklist-row-${row.id}`}
      className="flex flex-wrap items-center justify-between gap-3 border-t border-border px-5 py-3 first:border-t-0"
    >
      <div className="flex min-w-0 flex-1 items-center gap-3">
        <StatusBadge tone={row.domainTone}>{row.domain}</StatusBadge>
        <div className="min-w-0">
          <strong className="block truncate text-sm font-bold text-text">{row.entity}</strong>
          <span className="block truncate text-xs text-text-muted">{row.meta}</span>
        </div>
      </div>
      <Button
        type="button"
        variant="secondary"
        size="sm"
        onClick={row.onCta}
        className={row.override ? '!border-override !text-override hover:!bg-override/10' : undefined}
      >
        {row.ctaLabel}
      </Button>
    </div>
  );
}

export function CeoOverview({ user, employee, dashboardSummary }) {
  const navigate = useNavigate();
  const monthStart = bangkokMonthStartIso();

  // Shares the same cache entries TicketDashboard/PricingRequestQueuePage
  // already populate for a CEO session — one company-wide fetch each, no
  // per-row detail calls (see the tickets.list() mock-parity note below for
  // why that matters for the close-verify worklist).
  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((r) => (Array.isArray(r) ? r : (r?.tickets ?? []))),
  });
  const pricingQueueQuery = useQuery({
    queryKey: queryKeys.pricingRequestQueue({ activeOnly: true }),
    queryFn: () => api.pricingRequests.queue({ activeOnly: true }).then((r) => r.items ?? []),
  });
  // CommissionController#list only supports payrollMonth (no status param —
  // see CommissionPage.jsx), so this fetches everything and filters
  // client-side for MANAGER_APPROVED (the CEO's own review step).
  const commissionsQuery = useQuery({
    queryKey: queryKeys.commissionsList(),
    queryFn: () => api.commissions.list({}).then((r) => r.commissions ?? []),
  });
  // Two bounded, status-filtered fetches (both params are real/optional on
  // OvertimeController) instead of one unbounded all-history fetch.
  const otManagerApprovedQuery = useQuery({
    queryKey: queryKeys.overtimeRequests({ status: 'MANAGER_APPROVED' }),
    queryFn: () => api.overtime.list({ status: 'MANAGER_APPROVED' }).then((r) => r.requests ?? []),
  });
  const otSubmittedQuery = useQuery({
    queryKey: queryKeys.overtimeRequests({ status: 'SUBMITTED' }),
    queryFn: () => api.overtime.list({ status: 'SUBMITTED' }).then((r) => r.requests ?? []),
  });
  const leaveSubmittedQuery = useQuery({
    queryKey: queryKeys.leaveRequests({ status: 'SUBMITTED' }),
    queryFn: () => api.leave.list({ status: 'SUBMITTED' }).then((r) => r.requests ?? []),
  });

  const tickets = ticketsQuery.data ?? EMPTY_LIST;
  const pricingQueue = pricingQueueQuery.data ?? EMPTY_LIST;
  const commissions = commissionsQuery.data ?? EMPTY_LIST;
  const otManagerApproved = otManagerApprovedQuery.data ?? EMPTY_LIST;
  const otSubmitted = otSubmittedQuery.data ?? EMPTY_LIST;
  const leaveSubmitted = leaveSubmittedQuery.data ?? EMPTY_LIST;

  // ── ราคา: PricingRequest at READY_FOR_CEO_REVIEW / CEO_REVIEWING ──────────
  const priceApprovalRows = useMemo(
    () => pricingQueue.filter((pr) => CEO_PRICE_STATUSES.includes(pr.status)),
    [pricingQueue],
  );

  // ── ปิดงาน: ฝ่ายบัญชี already confirmed close-ready, CEO has not verified ──
  // Mirrors TicketService.verifyClose's own precondition (closeConfirmedAt !=
  // null, deal still ACTIVE/not yet closed) — see the mock-parity comment on
  // tickets.list() in mockApi.js for why this field is safe to read here.
  const closeVerifyRows = useMemo(
    () => tickets.filter((t) => (t.lifecycle ?? 'ACTIVE') === 'ACTIVE' && t.status !== 'closed' && t.closeConfirmedAt),
    [tickets],
  );

  // ── ค่าคอม: MANAGER_APPROVED, awaiting the CEO's own final step ───────────
  const commissionRows = useMemo(
    () => commissions.filter((c) => c.status === 'MANAGER_APPROVED'),
    [commissions],
  );

  // ── OT: MANAGER_APPROVED (normal 2-step chain) PLUS SUBMITTED requests
  // whose employee has no manager in between (managerEmployeeId points
  // straight at the CEO's own employeeId) — mirrors OvertimeService's
  // managesEmployee()/canReviewOvertime's directReport branch, the same FK
  // the reconciled approval model calls "manager-less division goes straight
  // to CEO". Real code-supported case, not an invented bypass — see
  // docs/role-scoped-views.md "CEO" section for the full derivation.
  const otDirectToCeo = useMemo(
    () => otSubmitted.filter((r) => r.managerEmployeeId != null && Number(r.managerEmployeeId) === Number(user?.employeeId)),
    [otSubmitted, user?.employeeId],
  );
  const otRows = useMemo(() => [...otManagerApproved, ...otDirectToCeo], [otManagerApproved, otDirectToCeo]);

  // ── ลา: SUBMITTED, same manager-less-division FK case (LeaveService has no
  // MANAGER_APPROVED step at all — canReviewLeave only ever grants hr, or the
  // employee's literal FK manager, so a CEO-reviewable leave request IS by
  // definition one whose manager FK is the CEO). ──────────────────────────
  const leaveRows = useMemo(
    () => leaveSubmitted.filter((r) => r.managerEmployeeId != null && Number(r.managerEmployeeId) === Number(user?.employeeId)),
    [leaveSubmitted, user?.employeeId],
  );

  // ── ผลบริษัทเดือนนี้: no ฿ figure exists on /api/dashboard/summary (it only
  // returns ticket-status counts), so every ฿ number here is derived from the
  // scoped tickets.list() the CEO already gets in full — amountPayable/
  // amountPaid/amountOutstanding/overdue are the same computed fields
  // TicketDetailPage's money views already trust (derivePaymentFields). ────
  const closedThisMonthTickets = useMemo(
    () => tickets.filter((t) => t.status === 'closed' && t.closedAt && t.closedAt >= monthStart),
    [tickets, monthStart],
  );
  const salesClosedThisMonth = useMemo(
    () => moneySum(closedThisMonthTickets, (t) => t.amountPaid ?? t.amountPayable),
    [closedThisMonthTickets],
  );
  const companyPipelineValue = useMemo(
    () => moneySum(tickets.filter((t) => (t.lifecycle ?? 'ACTIVE') === 'ACTIVE' && t.status !== 'closed'), (t) => t.amountPayable),
    [tickets],
  );
  const overdueReceivables = useMemo(
    () => moneySum(tickets.filter((t) => t.overdue), (t) => t.amountOutstanding),
    [tickets],
  );
  const closedThisMonthCount = dashboardSummary?.tickets?.closedThisMonth ?? closedThisMonthTickets.length;
  const headcountActive = dashboardSummary?.headcount?.active ?? null;
  const lateToday = dashboardSummary?.attendance?.lateToday ?? null;

  // ── ยอดตามทีม/ฝ่าย: tickets carry no divisionId (and employees.list() is
  // hr-only — CEO has no server route to a division breakdown here), so this
  // groups by the deal's owning sales rep (createdByName) instead, the
  // closest real grouping the CEO's existing endpoints expose. No target/
  // quota field exists anywhere in this data, so bars are sized relative to
  // this month's top performer, never against an invented quota — see
  // docs/role-scoped-views.md "CEO" section for this stated gap.
  const repBreakdown = useMemo(() => {
    const byRep = new Map();
    for (const ticket of closedThisMonthTickets) {
      const key = ticket.createdByName || 'ไม่ระบุผู้ขาย';
      const amount = Number(ticket.amountPaid ?? ticket.amountPayable) || 0;
      byRep.set(key, (byRep.get(key) ?? 0) + amount);
    }
    const rows = [...byRep.entries()].map(([name, amount]) => ({ name, amount }));
    rows.sort((a, b) => b.amount - a.amount);
    return rows.slice(0, 8);
  }, [closedThisMonthTickets]);
  const repBreakdownMax = repBreakdown.reduce((max, row) => Math.max(max, row.amount), 0);

  // ── the cross-domain worklist, in the exec-pulse's own order ─────────────
  const worklist = useMemo(() => ([
    ...priceApprovalRows.map((pr) => ({
      id: `price-${pr.id}`,
      domain: 'ราคา',
      domainTone: 'info',
      entity: pr.customerName || pr.ticketCode || pr.requestCode,
      meta: [pr.requestCode, pr.ticketCode].filter(Boolean).join(' · '),
      ctaLabel: 'ตั้งราคา',
      override: true,
      onCta: () => navigate(`/pricing-requests/${pr.id}`),
    })),
    ...closeVerifyRows.map((t) => ({
      id: `close-${t.id}`,
      domain: 'ปิดงาน',
      domainTone: 'teal',
      entity: t.customerName || t.title || t.code,
      meta: [t.code, t.closeConfirmedByName ? `ยืนยันโดย ${t.closeConfirmedByName}` : null].filter(Boolean).join(' · '),
      ctaLabel: 'ตรวจปิดงาน',
      onCta: () => navigate(`/tickets/${t.id}`),
    })),
    ...commissionRows.map((c) => ({
      id: `commission-${c.id}`,
      domain: 'ค่าคอม',
      domainTone: 'indigo',
      entity: c.salesRepName || `พนักงาน #${c.salesRepId}`,
      meta: commissionKindLabel(c.kind),
      ctaLabel: 'อนุมัติ',
      onCta: () => navigate('/commissions'),
    })),
    ...otRows.map((r) => ({
      id: `ot-${r.id}`,
      domain: 'OT',
      domainTone: 'warning',
      entity: r.employeeName || r.employeeCode || `พนักงาน #${r.employeeId}`,
      meta: formatWorkDate(r.workDate),
      ctaLabel: 'อนุมัติ',
      onCta: () => navigate('/employee-requests?tab=ot'),
    })),
    ...leaveRows.map((r) => ({
      id: `leave-${r.id}`,
      domain: 'ลา',
      domainTone: 'neutral',
      entity: r.employeeName || r.employeeCode || `พนักงาน #${r.employeeId}`,
      meta: [r.leaveTypeNameTh, r.startDate && r.endDate ? `${formatWorkDate(r.startDate)} - ${formatWorkDate(r.endDate)}` : null].filter(Boolean).join(' · '),
      ctaLabel: 'อนุมัติ',
      onCta: () => navigate('/leave'),
    })),
  ]), [priceApprovalRows, closeVerifyRows, commissionRows, otRows, leaveRows, navigate]);

  const otLeavePendingCount = otRows.length + leaveRows.length;

  const primaryLoading = ticketsQuery.isLoading || pricingQueueQuery.isLoading || commissionsQuery.isLoading
    || otManagerApprovedQuery.isLoading || otSubmittedQuery.isLoading || leaveSubmittedQuery.isLoading;

  const today = formatThaiDate(new Date().toISOString());
  const greeting = `สวัสดี, คุณ${employee?.nickName || employee?.nameTh || user?.name || ''}`;

  return (
    <PageStack>
      <PageHeader
        title="ภาพรวมผู้บริหาร"
        subtitle={`${greeting} · ${today} · การตัดสินใจที่รอคุณ · ผลบริษัทเดือนนี้`}
      />

      {primaryLoading ? (
        <div aria-busy="true" aria-label="กำลังโหลดภาพรวมผู้บริหาร">
          <StatGrid>
            <SkeletonCard lines={1} />
            <SkeletonCard lines={1} />
            <SkeletonCard lines={1} />
            <SkeletonCard lines={1} />
            <SkeletonCard lines={1} />
          </StatGrid>
        </div>
      ) : (
        <>
          <StatGrid>
            <StatCard
              icon="badgeDollar"
              label="อนุมัติราคา"
              value={priceApprovalRows.length}
              helper="Pricing requests"
              tone="rose"
              onClick={priceApprovalRows.length > 0 ? () => navigate('/pricing-requests') : undefined}
            />
            <StatCard
              icon="clipboard"
              label="ตรวจปิดงาน"
              value={closeVerifyRows.length}
              helper="Close verifications"
              tone="amber"
              onClick={closeVerifyRows.length > 0 ? () => navigate('/tickets') : undefined}
            />
            <StatCard
              icon="badge"
              label="ค่าคอมรออนุมัติ"
              value={commissionRows.length}
              helper="Manager approved"
              tone="indigo"
              onClick={commissionRows.length > 0 ? () => navigate('/commissions') : undefined}
            />
            <StatCard
              icon="clock"
              label="OT·ลา รออนุมัติ"
              value={otLeavePendingCount}
              helper="Final approvals"
              tone="blue"
              onClick={otLeavePendingCount > 0
                ? () => navigate(otRows.length >= leaveRows.length ? '/employee-requests?tab=ot' : '/leave')
                : undefined}
            />
            <StatCard
              icon="badgeDollar"
              label="ยอดขายเดือนนี้"
              value={formatMoney(salesClosedThisMonth)}
              helper="Closed this month"
              tone="teal"
            />
          </StatGrid>

          <Panel title="รออนุมัติจากคุณ" className="!p-0 overflow-hidden">
            {worklist.length === 0 ? (
              <div className="flex items-center gap-3 px-5 py-4 text-text-muted">
                <Icon name="check" size={18} />
                <span className="text-sm font-bold">ไม่มีรายการที่รอการตัดสินใจจากคุณตอนนี้</span>
              </div>
            ) : (
              <div className="flex flex-col">
                {worklist.map((row) => <WorklistRow key={row.id} row={row} />)}
              </div>
            )}
          </Panel>

          <div className="grid gap-[18px] items-start grid-cols-[1.15fr_0.85fr] max-[1040px]:grid-cols-1">
            <Panel title="ยอดตามทีม/ฝ่าย">
              {/* No target/quota field exists on any endpoint the CEO can reach
                  (see the repBreakdown comment above) — bars are sized against
                  this month's own top performer, and no % is shown, so this
                  never implies a quota that does not exist. Grouped by sales
                  rep, not division: tickets carry no divisionId and
                  employees.list() is hr-only, so a real division breakdown is
                  not derivable from any endpoint the CEO can call. */}
              {repBreakdown.length === 0 ? (
                <p className="m-0 text-sm text-text-muted">ยังไม่มีดีลที่ปิดในเดือนนี้</p>
              ) : (
                <div className="flex flex-col gap-3">
                  {repBreakdown.map((row) => (
                    <div key={row.name} className="grid gap-1">
                      <div className="flex items-baseline justify-between gap-3">
                        <span className="min-w-0 truncate text-sm font-bold text-text">{row.name}</span>
                        <code className="shrink-0 font-mono text-xs text-text-muted">{formatMoney(row.amount)}</code>
                      </div>
                      <div className="h-2 w-full rounded-full bg-surface-subtle">
                        <div
                          className="h-2 rounded-full bg-primary"
                          style={{ width: `${repBreakdownMax > 0 ? Math.max(4, (row.amount / repBreakdownMax) * 100) : 0}%` }}
                        />
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </Panel>

            <Panel title="ผลบริษัทเดือนนี้">
              <dl className="m-0 grid gap-3">
                {[
                  ['ยอดขายปิดแล้ว', formatMoney(salesClosedThisMonth)],
                  ['ปิดงานแล้ว', `${closedThisMonthCount} ดีล`],
                  ['Pipeline บริษัท', formatMoney(companyPipelineValue)],
                  ['ลูกหนี้ค้าง/เกินกำหนด', formatMoney(overdueReceivables)],
                  ['กำลังพล', headcountActive != null ? `${headcountActive} คน` : '-'],
                  ['มาสายวันนี้', lateToday != null ? `${lateToday} คน` : '-'],
                ].map(([term, value]) => (
                  <div key={term} className="flex items-baseline justify-between gap-4 border-b border-surface-subtle pb-3 last:border-b-0 last:pb-0">
                    <dt className="!text-xs !text-text-muted">{term}</dt>
                    <dd className="m-0 !text-sm !font-bold !text-text text-right">{value}</dd>
                  </div>
                ))}
              </dl>
            </Panel>
          </div>
        </>
      )}
    </PageStack>
  );
}
