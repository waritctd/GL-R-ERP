import { memo, useCallback, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { formatMoney, formatThaiDate, greetingName } from '../../utils/format.js';

/**
 * CEO exec-cockpit landing (plan: docs/agent-handoffs/valiant-watching-map.md
 * "6. CEO"). CEO keeps every existing sales surface + nav item unchanged —
 * this component only adds a role-shaped `/` landing that pulls the
 * cross-domain decisions only the CEO can make into one worklist, instead of
 * making the CEO hunt across คำขอราคา / รายการดีล / ค่าคอมมิชชัน / คำขอ / ลา.
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

function moneySum(items, pick) {
  return items.reduce((sum, item) => sum + (Number(pick(item)) || 0), 0);
}

function formatWorkDate(value) {
  if (!value) return '-';
  const date = new Date(`${value}T00:00:00+07:00`);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    dateStyle: 'medium',
    timeZone: 'Asia/Bangkok',
  }).format(date);
}

const MAX_ROWS = 3;

// Group header dot + the page the group's "ดูทั้งหมด" / "อีก N รายการ" open. Price takes the
// override colour so the CEO's own pricing decision stands out from the approvals below it.
const GROUP_META = {
  price: { label: 'ตั้งราคา', dot: 'bg-override', to: '/pricing-requests' },
  close: { label: 'ตรวจปิดงาน', dot: 'bg-success', to: '/tickets' },
  commission: {
    label: 'ค่าคอมมิชชัน',
    sub: 'ผู้จัดการอนุมัติแล้ว',
    dot: 'bg-primary',
    to: '/commissions?view=pending',
  },
  ot: { label: 'OT', dot: 'bg-warning', to: '/employee-requests?tab=ot' },
  leave: { label: 'ลา', dot: 'bg-text-muted', to: '/leave' },
};

/**
 * One row of a worklist group. Deliberately a plain row (not a whole-row
 * <button>) so the CTA can stay a real, separately-labelled button per domain
 * (ตั้งราคา / ตรวจปิดงาน / อนุมัติ) instead of one generic "ไปที่หน้า" click
 * target — mirrors the QueueCard pattern in PricingRequestQueuePage.jsx (a
 * nested interactive control needs its own element, never a parent <button>).
 */
function WorklistRow({ row, override, onOpen }) {
  return (
    <div data-testid={row.testId} className="flex items-center justify-between gap-3 px-5 py-2 mobile:px-3.5 mobile:py-2.5">
      <div className="min-w-0">
        <strong className="block truncate text-sm font-bold text-text">{row.entity}</strong>
        <span className="block truncate text-xs text-text-muted">{row.meta}</span>
      </div>
      <Button
        type="button"
        variant={override ? 'primary' : 'secondary'}
        size="sm"
        onClick={() => onOpen(row.to)}
        className={override ? '!border-override !bg-override mobile:min-h-[44px]' : 'mobile:min-h-[44px]'}
      >
        {row.ctaLabel}
      </Button>
    </div>
  );
}

/**
 * One worklist group. `rows` is null while its own query is loading: the group
 * keeps its testid and shows a small skeleton (aria-busy) instead of holding
 * the whole page back. `count` can exceed rows.length (the commission group is
 * ONE row standing in for several records). `failed` means its query errored
 * (after the client's own retry): the group says so instead of loading forever.
 */
const WorklistGroup = memo(function WorklistGroup({ groupKey, rows, count, failed, onOpen }) {
  const meta = GROUP_META[groupKey];
  const override = groupKey === 'price';
  if (!failed && rows !== null && count === 0) return null;
  const shown = rows?.slice(0, MAX_ROWS) ?? [];
  const more = (rows?.length ?? 0) - shown.length;
  // One root element for both states, so a node already handed out while loading stays the
  // live group once its rows land.
  return (
    <div
      data-testid={`worklist-group-${groupKey}`}
      aria-busy={rows === null && !failed ? 'true' : undefined}
      className={`border-b border-border last:border-b-0 ${override ? 'bg-override/5' : ''}`}
    >
      {failed ? (
        <div className="flex items-center justify-between gap-3 px-5 py-3 mobile:px-3.5">
          <span className="text-sm font-extrabold text-text">
            {meta.label} <small className="font-semibold text-danger">โหลดไม่สำเร็จ</small>
          </span>
          <Button type="button" variant="text" size="sm" onClick={() => onOpen(meta.to)} className="shrink-0 mobile:min-h-[44px]">
            ดูทั้งหมด →
          </Button>
        </div>
      ) : rows === null ? (
        <div className="grid gap-2 px-5 py-3 mobile:px-3.5" role="status" aria-label={`กำลังโหลด${meta.label}`}>
          <Skeleton width="30%" height={14} />
          <Skeleton height={36} />
        </div>
      ) : (
        <>
          <div className="flex items-center justify-between gap-3 px-5 pb-1 pt-3 mobile:px-3.5">
            <div className="flex min-w-0 items-center gap-2 text-sm font-extrabold text-text">
              <span aria-hidden="true" className={`size-2 shrink-0 rounded-full ${meta.dot}`} />
              <span>{meta.label}</span>
              <small className="shrink-0 font-semibold text-text-muted">{count} รายการ</small>
              {meta.sub ? <small className="truncate font-semibold text-text-muted">· {meta.sub}</small> : null}
            </div>
            <Button type="button" variant="text" size="sm" onClick={() => onOpen(meta.to)} className="shrink-0 mobile:min-h-[44px]">
              ดูทั้งหมด →
            </Button>
          </div>
          {shown.map((row) => (
            <WorklistRow key={row.testId} row={row} override={override} onOpen={onOpen} />
          ))}
          {more > 0 ? (
            <div className="px-5 pt-0.5 mobile:px-3.5">
              <Button type="button" variant="text" size="sm" onClick={() => onOpen(meta.to)} className="mobile:min-h-[44px]">
                อีก {more} รายการ
              </Button>
            </div>
          ) : null}
          <div className="pb-2" />
        </>
      )}
    </div>
  );
});

// One figure of the money band: <div><dt/><dd/><small/></div> (the shared test helper finds it
// via `dl > div`). Stacked cell on desktop, a label-left / value-right row on a phone.
function Figure({ label, value, caption, lead = false, danger = false }) {
  return (
    <div className="grid min-w-0 gap-0.5 mobile:grid-cols-[1fr_auto] mobile:items-baseline mobile:gap-x-3">
      <dt className="!m-0 !text-xs !font-bold !text-text-muted">{label}</dt>
      <dd
        className={`m-0 font-extrabold tabular-nums mobile:text-right ${lead ? 'text-[26px] mobile:text-xl' : 'text-xl'} ${danger ? '!text-danger' : '!text-text'}`}
      >
        {value}
      </dd>
      <small className="text-xs text-text-muted mobile:col-span-2">{caption}</small>
    </div>
  );
}

export function CeoOverview({ user, employee, dashboardSummary }) {
  const navigate = useNavigate();
  const onOpen = useCallback((to) => navigate(to), [navigate]);

  // Shares the same cache entries PricingRequestQueuePage
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

  // A source that is still loading is null: its group shows its own skeleton while the rest
  // of the page is already on screen (no all-or-nothing gate).
  const tickets = ticketsQuery.data ?? null;
  const pricingQueue = pricingQueueQuery.data ?? null;
  const commissions = commissionsQuery.data ?? null;
  const otManagerApproved = otManagerApprovedQuery.data ?? null;
  const otSubmitted = otSubmittedQuery.data ?? null;
  const leaveSubmitted = leaveSubmittedQuery.data ?? null;

  // ── ราคา: PricingRequest at READY_FOR_CEO_REVIEW / CEO_REVIEWING ──────────
  const priceApprovalRows = useMemo(() => pricingQueue?.filter((pr) => CEO_PRICE_STATUSES.includes(pr.status)), [pricingQueue]);

  // ── ปิดงาน: ฝ่ายบัญชี already confirmed close-ready, CEO has not verified ──
  // Mirrors TicketService.verifyClose's own precondition (closeConfirmedAt !=
  // null, deal still ACTIVE/not yet closed) — see the mock-parity comment on
  // tickets.list() in mockApi.js for why this field is safe to read here.
  const closeVerifyRows = useMemo(
    () => tickets?.filter((t) => (t.lifecycle ?? 'ACTIVE') === 'ACTIVE' && t.status !== 'closed' && t.closeConfirmedAt),
    [tickets],
  );

  // ── ค่าคอม: MANAGER_APPROVED, awaiting the CEO's own final step ───────────
  const commissionRows = useMemo(() => commissions?.filter((c) => c.status === 'MANAGER_APPROVED'), [commissions]);

  // ── OT: MANAGER_APPROVED (normal 2-step chain) PLUS SUBMITTED requests with
  // no manager stage at all — the employee's ฝ่าย has no ผู้จัดการ, or they are
  // one themselves. Keyed off the server's own `hasManagerApprover`, which is
  // the same expression OvertimeService.approve() routes on.
  //
  // This previously approximated the case as "reports_to points at the CEO",
  // which is wrong in BOTH directions now that approval is division-only: it
  // missed manager-less requests whose reports_to is someone else (the CEO
  // never saw work only they could clear), and it listed requests whose ฝ่าย
  // does have a ผู้จัดการ, where the CEO would just get a 403.
  const otDirectToCeo = useMemo(() => otSubmitted?.filter((r) => r.hasManagerApprover === false), [otSubmitted]);
  const otRows = useMemo(() => (otManagerApproved && otDirectToCeo ? [...otManagerApproved, ...otDirectToCeo] : null), [otManagerApproved, otDirectToCeo]);

  // ── ลา: SUBMITTED, all of it. This used to filter to `managerEmployeeId ===
  // user.employeeId` on the premise that canReviewLeave only ever granted hr, or the
  // employee's literal FK manager -- so a CEO-reviewable leave request was by definition one
  // whose manager FK was the CEO. CEO leave-approval reach (2026-09-01 owner ruling,
  // LeaveService.REVIEW_ALL_ROLES gaining "ceo") invalidates exactly that premise: the CEO can
  // now review EVERY ใบลา, not just requests whose FK manager happens to be them. leaveSubmitted
  // itself already comes back company-wide for this actor (api.leave.list is server-scoped by
  // VIEW_ALL_ROLES, which has included ceo since before this change), so every row here is one
  // this CEO may act on -- no further client-side filter needed. This component only ever renders
  // for user.role === 'ceo' (see App.jsx), so there is no other-role case to guard against. ─────
  const leaveRows = leaveSubmitted;

  // ── ผลบริษัทเดือนนี้: เงินรับแล้ว / ค้างรับ / ยอดคาดการณ์ come from /api/dashboard/summary (server
  // totals, the same figures /finance shows). Only เกินกำหนดชำระ is derived from the scoped
  // tickets.list() — amountOutstanding/overdue are the computed fields TicketDetailPage's money
  // views already trust (derivePaymentFields). ────
  const outstanding = dashboardSummary?.financeOutstanding;
  const forecast = dashboardSummary?.forecastValue;
  const cashReceived = dashboardSummary?.cashReceivedThisMonth;
  const overdueReceivables = useMemo(
    () =>
      tickets
        ? moneySum(
            tickets.filter((t) => t.overdue),
            (t) => t.amountOutstanding,
          )
        : null,
    [tickets],
  );
  const headcountActive = dashboardSummary?.headcount?.active ?? null;
  const lateToday = dashboardSummary?.attendance?.lateToday ?? null;

  // ── ยอดตามทีม/ฝ่าย: this month's cash received split per sales rep, served by the dashboard
  // summary (cashReceivedByRep; its rows add up to เงินรับแล้ว). Tickets carry no divisionId and
  // employees.list() is hr-only, so a division breakdown is not derivable for the CEO. No target/
  // quota field exists, so bars are sized relative to this month's top rep, never against a quota.
  const repBreakdown = useMemo(
    () =>
      [...(dashboardSummary?.cashReceivedByRep ?? [])]
        .map((row) => ({ name: row.repName, amount: Number(row.amount) || 0 }))
        .sort((a, b) => b.amount - a.amount),
    [dashboardSummary?.cashReceivedByRep],
  );
  const repBreakdownMax = repBreakdown.reduce((max, row) => Math.max(max, row.amount), 0);

  // ── the cross-domain worklist: one group per source, in money-blocking order. Each group is
  // null (loading) until its own query lands. ─────────────────────────────────────────────
  const priceGroup = useMemo(
    () =>
      priceApprovalRows?.map((pr) => ({
        testId: `worklist-row-price-${pr.id}`,
        entity: pr.customerName || pr.ticketCode || pr.requestCode,
        meta: [pr.requestCode, pr.ticketCode].filter(Boolean).join(' · '),
        ctaLabel: 'ตั้งราคา',
        to: `/pricing-requests/${pr.id}`,
      })) ?? null,
    [priceApprovalRows],
  );
  const closeGroup = useMemo(
    () =>
      closeVerifyRows?.map((t) => ({
        testId: `worklist-row-close-${t.id}`,
        entity: t.customerName || t.title || t.code,
        meta: [t.code, t.closeConfirmedByName ? `ยืนยันโดย ${t.closeConfirmedByName}` : null].filter(Boolean).join(' · '),
        ctaLabel: 'ตรวจปิดงาน',
        to: `/tickets/${t.id}`,
      })) ?? null,
    [closeVerifyRows],
  );
  // Commission is ONE row: every rep once (however many records wait) plus the kinds involved.
  const commissionGroup = useMemo(() => {
    if (!commissionRows) return null;
    if (commissionRows.length === 0) return [];
    const reps = [...new Set(commissionRows.map((c) => c.salesRepName || `พนักงาน #${c.salesRepId}`))];
    const kinds = [...new Set(commissionRows.map((c) => commissionKindLabel(c.kind)))];
    return [
      {
        testId: 'worklist-row-commission',
        entity: reps.join(', '),
        meta: kinds.join(' · '),
        ctaLabel: 'เปิดหน้าอนุมัติ',
        to: '/commissions?view=pending',
      },
    ];
  }, [commissionRows]);
  const otGroup = useMemo(
    () =>
      otRows?.map((r) => ({
        testId: `worklist-row-ot-${r.id}`,
        entity: r.employeeName || r.employeeCode || `พนักงาน #${r.employeeId}`,
        meta: formatWorkDate(r.workDate),
        ctaLabel: 'อนุมัติ',
        to: '/employee-requests?tab=ot',
      })) ?? null,
    [otRows],
  );
  const leaveGroup = useMemo(
    () =>
      leaveRows?.map((r) => ({
        testId: `worklist-row-leave-${r.id}`,
        entity: r.employeeName || r.employeeCode || `พนักงาน #${r.employeeId}`,
        meta: [r.leaveTypeNameTh, r.startDate && r.endDate ? `${formatWorkDate(r.startDate)} - ${formatWorkDate(r.endDate)}` : null]
          .filter(Boolean)
          .join(' · '),
        ctaLabel: 'อนุมัติ',
        to: '/leave',
      })) ?? null,
    [leaveRows],
  );

  const groups = [
    ['price', priceGroup, priceGroup?.length ?? 0, pricingQueueQuery.isError],
    ['close', closeGroup, closeGroup?.length ?? 0, ticketsQuery.isError],
    ['commission', commissionGroup, commissionRows?.length ?? 0, commissionsQuery.isError],
    ['ot', otGroup, otGroup?.length ?? 0, otManagerApprovedQuery.isError || otSubmittedQuery.isError],
    ['leave', leaveGroup, leaveGroup?.length ?? 0, leaveSubmittedQuery.isError],
  ];
  const total = groups.reduce((sum, [, , count]) => sum + count, 0);
  // A source is settled once it has rows or has failed; the total badge waits for all of them,
  // since a partial sum would read as the real figure.
  const settled = groups.map(([, rows, , failed]) => rows !== null || failed);
  const allLoaded = settled.every(Boolean);
  const allEmpty = groups.every(([, , count, failed]) => count === 0 && !failed);
  // Until the first source settles there is nothing to place a group around, so one panel-level
  // skeleton stands in; after that each group loads (and shows its own skeleton) independently.
  const anyLoaded = settled.some(Boolean);

  const today = formatThaiDate(new Date().toISOString());
  const greeting = `สวัสดี, ${greetingName(employee?.nickName || employee?.nameTh || user?.name)}`;
  const money = (value) => (value == null ? '—' : formatMoney(value));

  return (
    <PageStack>
      <PageHeader title="ภาพรวมผู้บริหาร" subtitle={`${greeting} · ${today}`} />

      <section className="rounded-md border border-border bg-surface p-5 mobile:p-3.5" aria-label="ผลบริษัทเดือนนี้">
        <dl className="m-0 grid grid-cols-[1.5fr_1fr_1fr_1fr] gap-x-6 nav-drawer:grid-cols-2 nav-drawer:gap-y-4 mobile:grid-cols-1 mobile:divide-y mobile:divide-border mobile:[&>div]:py-2.5">
          <Figure lead label="เงินรับแล้ว (เดือนนี้)" value={money(cashReceived)} caption="เงินที่บัญชียืนยันรับแล้ว" />
          <Figure label="ค้างรับ" value={money(outstanding)} caption="ยอดคงค้างรวม (หน้าการเงิน)" />
          <Figure
            danger
            label="เกินกำหนดชำระ"
            value={ticketsQuery.isError ? '—' : overdueReceivables == null ? <Skeleton width={96} height={22} className="mobile:ml-auto" /> : formatMoney(overdueReceivables)}
            caption="ดีลที่เลยกำหนดชำระ"
          />
          <Figure label="ยอดคาดการณ์" value={money(forecast)} caption="ดีลที่ยังไม่ได้รับคำสั่งซื้อ" />
        </dl>
      </section>

      <div className="grid items-start gap-[18px] grid-cols-[1.6fr_1fr] nav-drawer:grid-cols-1">
        <Panel
          flush
          title="รอคุณตัดสินใจ"
          actions={
            allLoaded ? (
              <span
                data-testid="worklist-total"
                aria-label={`รวม ${total} รายการ`}
                className="rounded-full bg-text px-2.5 py-px text-xs font-extrabold text-surface"
              >
                {total}
              </span>
            ) : null
          }
        >
          {!anyLoaded ? (
            <div className="grid gap-2 px-5 py-4 mobile:px-3.5" role="status" aria-busy="true" aria-label="กำลังโหลดรายการที่รอคุณตัดสินใจ">
              <Skeleton width="30%" height={14} />
              <Skeleton height={36} />
              <Skeleton height={36} />
            </div>
          ) : allLoaded && allEmpty ? (
            <div className="flex items-center gap-3 px-5 py-4 text-text-muted">
              <Icon name="check" size={18} />
              <span className="text-sm font-bold">ไม่มีรายการที่รอการตัดสินใจจากคุณตอนนี้</span>
            </div>
          ) : (
            groups.map(([key, rows, count, failed]) => (
              <WorklistGroup key={key} groupKey={key} rows={rows} count={count} failed={failed} onOpen={onOpen} />
            ))
          )}
        </Panel>

        <div className="grid gap-[18px]">
          <Panel title="เงินรับตามพนักงานขาย">
            {/* No target/quota field exists on any endpoint the CEO can reach
                (see the repBreakdown comment above) — bars are sized against
                this month's own top performer, and no % is shown, so this
                never implies a quota that does not exist. Grouped by sales
                rep, not division: tickets carry no divisionId and
                employees.list() is hr-only, so a real division breakdown is
                not derivable from any endpoint the CEO can call. */}
            {repBreakdown.length === 0 ? (
              <p className="m-0 text-sm text-text-muted">ยังไม่มีเงินรับเข้าเดือนนี้</p>
            ) : (
              <div className="flex flex-col gap-3">
                {repBreakdown.map((row, index) => (
                  <div key={`${row.name}-${index}`} data-testid="rep-row" className="grid gap-1">
                    <div className="flex items-baseline justify-between gap-3">
                      <span data-testid="rep-name" className="min-w-0 truncate text-sm font-bold text-text">
                        {row.name}
                      </span>
                      <span data-testid="rep-amount" className="shrink-0 text-sm tabular-nums text-text-muted">
                        {formatMoney(row.amount)}
                      </span>
                    </div>
                    <div className="h-2 w-full rounded-full bg-surface-subtle">
                      <div
                        className="h-2 rounded-full bg-primary"
                        style={{
                          width: `${repBreakdownMax > 0 ? Math.max(4, (row.amount / repBreakdownMax) * 100) : 0}%`,
                        }}
                      />
                    </div>
                  </div>
                ))}
              </div>
            )}
          </Panel>

          <Panel title="ทีมงานวันนี้">
            <dl className="m-0 grid grid-cols-2 gap-4">
              {[
                ['กำลังพล', headcountActive != null ? `${headcountActive} คน` : '-'],
                ['มาสายวันนี้', lateToday != null ? `${lateToday} คน` : '-'],
              ].map(([term, value]) => (
                <div key={term} className="grid gap-0.5">
                  <dt className="!m-0 !text-xs !font-bold !text-text-muted">{term}</dt>
                  <dd className="m-0 text-xl font-extrabold !text-text">{value}</dd>
                </div>
              ))}
            </dl>
          </Panel>
        </div>
      </div>
    </PageStack>
  );
}
