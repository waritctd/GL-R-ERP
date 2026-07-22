import { useEffect, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { Panel, PageStack } from '../../components/common/Layout.jsx';
import { SkeletonCard, SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { formatMoney, formatThaiDate, ticketStatusLabel } from '../../utils/format.js';
import { accountMoneyBucket, nextAccountAction } from '../tickets/accountActions.js';

// Money-pulse bucket meta — order matches the plan's five-across layout:
// overdue leads (most urgent), then the two payment-confirmation steps, then
// close-ready, then the folded commission step. `amountField` picks which
// ticket money field the bucket sums: outstanding balance for the three
// pending-money buckets, deal value (amountPayable) for the two "money is
// already secured, just needs admin follow-through" buckets where
// amountOutstanding is 0 by definition.
const MONEY_BUCKETS = [
  { key: 'overdue', label: 'เกินกำหนด', icon: 'triangleAlert', tone: 'rose', amountField: 'amountOutstanding' },
  { key: 'depositPending', label: 'รอรับมัดจำ', icon: 'clock', tone: 'amber', amountField: 'amountOutstanding' },
  { key: 'finalPaymentPending', label: 'รอชำระส่วนที่เหลือ', icon: 'badgeDollar', tone: 'teal', amountField: 'amountOutstanding', live: true },
  { key: 'closeReady', label: 'รอปิดงาน', icon: 'check', tone: 'indigo', amountField: 'amountPayable' },
  { key: 'commissionPending', label: 'ออกค่าคอม', icon: 'fileText', tone: 'blue', amountField: 'amountPayable' },
];

const CTA_VARIANT = {
  confirmDeposit: 'success',
  confirmFinalPayment: 'success',
  chaseOverdue: 'danger',
  confirmCloseReady: 'primary',
  recordInvoiceCommission: 'text',
};

function dedupeById(...lists) {
  const seen = new Map();
  lists.flat().forEach((ticket) => {
    if (ticket && !seen.has(ticket.id)) seen.set(ticket.id, ticket);
  });
  return [...seen.values()];
}

function rowAmount(ticket) {
  const outstanding = Number(ticket.amountOutstanding ?? 0);
  return outstanding > 0 ? outstanding : Number(ticket.amountPayable ?? 0);
}

function startOfMonth() {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), 1);
}

/**
 * Account (ฝ่ายบัญชี) role-scoped landing: money pulse + "สิ่งที่ต้องทำ"
 * worklist, mirrors ImportOverview's conveyor-pulse pattern but keyed on
 * money buckets instead of pipeline stages. See docs/role-scoped-views.md.
 *
 * KNOWN DATA GAP (documented, not silently worked around — see
 * accountActions.js's own doc comment and CommissionPage.jsx's identical
 * note above its own eligibleTickets fetch): the account role's server-side
 * ticket-list scope (TicketRepository.appendRoleScope / mockApi's
 * accountListScopeIncludes) only ever returns deals with a payment action
 * pending (DEPOSIT_NOTICE_ISSUED/AWAITING_FINAL_PAYMENT) or an overdue
 * balance — never a close-ready or CLOSED_PAID deal (both have
 * amountOutstanding = 0 by definition, so neither condition is ever true).
 * That scope was a deliberate, reviewed authz decision (handoff 100), not a
 * bug this branch introduces, and per CLAUDE.md this task must never loosen
 * it. Consequence: "รอปิดงาน"/"ออกค่าคอม" below will read 0 under the
 * current backend scope even when such deals exist — the salesStage=
 * CLOSED_PAID query is kept anyway (mirrors CommissionPage's own
 * "eligibleTickets" picker) so this becomes correct for free if that scope
 * is ever widened. Flagged in the branch's handoff as a follow-up decision
 * for the plan owner, not fixed here.
 */
export function AccountOverview({ user, employee, showToast }) {
  const navigate = useNavigate();
  const isMobile = useIsMobile();

  const scopedQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((r) => r.tickets ?? []),
  });
  const closedPaidQuery = useQuery({
    queryKey: queryKeys.ticketListBySalesStage('CLOSED_PAID'),
    queryFn: () => api.tickets.list({ salesStage: 'CLOSED_PAID' }).then((r) => r.tickets ?? []),
  });

  useEffect(() => {
    const error = scopedQuery.error || closedPaidQuery.error;
    if (error) showToast?.('error', error.message || 'โหลดข้อมูลไม่สำเร็จ');
  }, [scopedQuery.error, closedPaidQuery.error, showToast]);

  const loading = scopedQuery.isLoading || closedPaidQuery.isLoading;

  const allTickets = useMemo(
    () => dedupeById(scopedQuery.data ?? [], closedPaidQuery.data ?? []),
    [scopedQuery.data, closedPaidQuery.data],
  );

  const buckets = useMemo(() => {
    const counts = Object.fromEntries(MONEY_BUCKETS.map((b) => [b.key, { count: 0, amount: 0 }]));
    allTickets.forEach((ticket) => {
      const key = accountMoneyBucket(ticket);
      if (!key || !counts[key]) return;
      const meta = MONEY_BUCKETS.find((b) => b.key === key);
      counts[key].count += 1;
      counts[key].amount += Number(ticket[meta.amountField] ?? 0);
    });
    return counts;
  }, [allTickets]);

  const worklist = useMemo(() => {
    const rows = allTickets
      .map((ticket) => ({ ticket, action: nextAccountAction(ticket) }))
      .filter((row) => row.action != null);
    // Overdue-first, then earliest due date, then largest balance.
    return rows.sort((a, b) => {
      if (a.action.urgent !== b.action.urgent) return a.action.urgent ? -1 : 1;
      const dueA = a.ticket.dueDate ? new Date(a.ticket.dueDate).getTime() : Infinity;
      const dueB = b.ticket.dueDate ? new Date(b.ticket.dueDate).getTime() : Infinity;
      if (dueA !== dueB) return dueA - dueB;
      return rowAmount(b.ticket) - rowAmount(a.ticket);
    });
  }, [allTickets]);

  const monthSummary = useMemo(() => {
    const monthStart = startOfMonth();
    let receivedThisMonth = 0;
    let outstandingTotal = 0;
    let overdueTotal = 0;
    allTickets.forEach((ticket) => {
      const outstanding = Number(ticket.amountOutstanding ?? 0);
      outstandingTotal += outstanding;
      if (ticket.overdue) overdueTotal += outstanding;
      // Best-effort "received this month": amountPaid on deals last updated
      // within the current calendar month — the closest signal available
      // without a per-ticket payment-receipt fetch for every visible deal.
      if (ticket.updatedAt && new Date(ticket.updatedAt) >= monthStart) {
        receivedThisMonth += Number(ticket.amountPaid ?? 0);
      }
    });
    return { receivedThisMonth, outstandingTotal, overdueTotal };
  }, [allTickets]);

  const firstName = employee?.nickName || employee?.nameTh || user?.name || '';
  const today = formatThaiDate(new Date().toISOString());

  return (
    <PageStack>
      <PageHeader
        title={`สวัสดี คุณ${firstName}`}
        subtitle={`ภาพรวมการเงิน · ${today} — เงินที่ต้องรับ / ยืนยัน / ปิดงาน`}
      />

      {loading ? (
        <div aria-busy="true" aria-label="กำลังโหลดข้อมูลการเงิน">
          <div className="grid grid-cols-5 gap-3 max-[1040px]:grid-cols-3 max-[720px]:grid-cols-2">
            {MONEY_BUCKETS.map((b) => <SkeletonCard key={b.key} lines={1} />)}
          </div>
          <section className="panel" style={{ marginTop: 14 }}>
            <SkeletonText lines={5} />
          </section>
        </div>
      ) : (
        <>
          {/* Money pulse — desktop: 5-across cards; mobile: horizontal-scroll chips. */}
          {isMobile ? (
            <div className="flex gap-2.5 overflow-x-auto pb-1 -mx-1 px-1">
              {MONEY_BUCKETS.map((b) => {
                const bucket = buckets[b.key];
                return (
                  <div
                    key={b.key}
                    className={`flex shrink-0 flex-col gap-1 rounded-lg border border-border bg-surface px-3.5 py-2.5 min-w-[132px] ${b.tone === 'rose' && bucket.count > 0 ? 'border-danger-border' : ''}`}
                  >
                    <span className="flex items-center gap-1.5 text-2xs font-bold text-text-muted">
                      <Icon name={b.icon} size={13} />
                      {b.label}
                      {/* DESIGN.md "Rationed Teal Rule": teal marks what is live — here,
                          the one bucket in active money-motion right now. */}
                      {b.live ? <span className="h-1.5 w-1.5 rounded-full bg-accent" aria-hidden="true" /> : null}
                    </span>
                    <strong className="text-lg font-extrabold tabular-nums text-text">{formatMoney(bucket.amount)}</strong>
                    <span className="text-2xs text-text-muted">{bucket.count} ดีล</span>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="grid grid-cols-5 gap-3 max-[1040px]:grid-cols-3">
              {MONEY_BUCKETS.map((b) => {
                const bucket = buckets[b.key];
                return (
                  <div key={b.key} className={`stat-card ${b.tone === 'rose' && bucket.count > 0 ? '!border-danger-border' : ''}`}>
                    <div className={`stat-icon stat-${b.tone}`}>
                      <Icon name={b.icon} size={21} />
                    </div>
                    <div className="stat-value tabular-nums">{formatMoney(bucket.amount)}</div>
                    <div className="stat-label flex items-center gap-1.5">
                      {b.label}
                      {/* DESIGN.md "Rationed Teal Rule": teal marks what is live — here,
                          the one bucket in active money-motion right now. */}
                      {b.live ? <span className="h-1.5 w-1.5 rounded-full bg-accent" aria-hidden="true" /> : null}
                    </div>
                    <div className="stat-helper">{bucket.count} ดีล</div>
                  </div>
                );
              })}
            </div>
          )}

          <div className="grid gap-[18px] items-start grid-cols-[1.6fr_1fr] max-[1040px]:grid-cols-1">
            {/* "สิ่งที่ต้องทำ" money worklist — overdue-first. */}
            <Panel title="สิ่งที่ต้องทำ" className="!p-0 overflow-hidden">
              {worklist.length === 0 ? (
                <div className="flex items-center gap-3 p-5 text-text-muted">
                  <Icon name="check" size={18} />
                  <span className="text-sm font-bold">ไม่มีรายการที่ต้องดำเนินการตอนนี้</span>
                </div>
              ) : (
                <div className="flex flex-col">
                  {worklist.map(({ ticket, action }) => {
                    const st = ticketStatusLabel(ticket.status);
                    return (
                      <div
                        key={ticket.id}
                        className="flex items-center justify-between gap-3 border-t border-border px-5 py-3 first:border-t-0 max-[720px]:flex-col max-[720px]:items-stretch max-[720px]:gap-2.5 max-[720px]:p-4"
                      >
                        <div className="flex min-w-0 flex-1 items-center gap-3 max-[720px]:flex-col max-[720px]:items-start max-[720px]:gap-1.5">
                          <span className={`h-2 w-2 shrink-0 rounded-full ${action.urgent ? 'bg-danger' : 'bg-info'}`} aria-hidden="true" />
                          <div className="min-w-0 flex-1">
                            <div className="flex flex-wrap items-center gap-2">
                              <strong className="min-w-0 truncate text-sm font-extrabold text-text">
                                {ticket.customerName || ticket.title}
                              </strong>
                              <StatusBadge tone={st.tone}>{st.label}</StatusBadge>
                            </div>
                            <code className="block text-2xs text-text-muted">{ticket.code}</code>
                          </div>
                        </div>
                        <div className="flex shrink-0 items-center gap-3 max-[720px]:w-full max-[720px]:justify-between">
                          <strong className="tabular-nums text-sm text-text">{formatMoney(rowAmount(ticket))}</strong>
                          <Button
                            type="button"
                            variant={CTA_VARIANT[action.key] ?? 'secondary'}
                            className="max-[720px]:flex-1 max-[720px]:min-h-11"
                            onClick={() => navigate(action.to)}
                          >
                            {action.label}
                          </Button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </Panel>

            {/* Right rail — month money summary + คิวของฉัน link. */}
            <div className="grid gap-[18px]">
              <Panel title="สรุปเดือนนี้">
                <dl className="m-0 grid gap-2.5">
                  {[
                    ['รับแล้ว (เดือนนี้)', monthSummary.receivedThisMonth, 'text-success'],
                    ['ค้างรับ', monthSummary.outstandingTotal, 'text-text'],
                    ['เกินกำหนด', monthSummary.overdueTotal, 'text-danger'],
                  ].map(([label, value, tone]) => (
                    <div key={label} className="flex items-baseline justify-between gap-3">
                      <dt className="!text-xs !text-text-muted">{label}</dt>
                      <dd className={`m-0 tabular-nums !text-md !font-extrabold ${tone}`}>{formatMoney(value)}</dd>
                    </div>
                  ))}
                </dl>
              </Panel>

              <button
                type="button"
                onClick={() => navigate('/finance')}
                className="bg-surface border border-border rounded-md shadow-sm p-4 w-full text-left cursor-pointer flex items-center justify-between gap-3 transition-colors hover:border-primary/50 hover:bg-surface-hover focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)]"
              >
                <span className="flex items-center gap-3">
                  <span className="stat-icon !mb-0 stat-indigo">
                    <Icon name="badgeDollar" size={19} />
                  </span>
                  <span>
                    <span className="block !text-sm !font-bold !text-text">คิวของฉัน</span>
                    <span className="block !text-xs !text-text-muted">งานการเงินทั้งหมด</span>
                  </span>
                </span>
                <Icon name="chevronRight" size={16} className="text-text-faint" />
              </button>
            </div>
          </div>
        </>
      )}
    </PageStack>
  );
}
