import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Avatar } from '../../components/common/Avatar.jsx';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney, formatThaiDate, greetingName } from '../../utils/format.js';
import { Figure, failed, moneySum } from './dashboardParts.jsx';
import { defaultRange } from '../tickets/movementRange.js';
import { TeamMovementPanel } from './TeamMovementPanel.jsx';

// A deal is "won" once it reaches the terminal pipeline stage, "lost" once
// its lifecycle/legacy status marks it lost or cancelled, else "open" —
// mirrors the same three-way split features/tickets/salesViewScope.js's
// `dealInScope`/`closed` already draws for the identical distinction, kept
// separate here only because this view needs won vs lost as two counts
// (close-rate) rather than one combined "closed" boolean.
function isDealWon(ticket) {
  return ticket.salesStage === 'CLOSED_PAID';
}
function isDealLost(ticket) {
  return ticket.lifecycle === 'CLOSED_LOST' || ticket.lifecycle === 'CANCELLED' || ticket.status === 'cancelled';
}
function isDealOpen(ticket) {
  return !isDealWon(ticket) && !isDealLost(ticket) && ticket.status !== 'closed';
}

/**
 * Sales Manager role-scoped landing (team cockpit), top to bottom: the money
 * band (เงินรับแล้ว / ค้างรับ / เกินกำหนดชำระ / ยอดคาดการณ์, same figures as the
 * CEO landing), then two columns. Left: ค่าคอมรอคุณตรวจ (deep-links into
 * CommissionPage's approve flow, never duplicates the approval mutation) and
 * ดีลที่ต้องดูแล (reason chips, overdue -> follow-up due -> stale). Right: the
 * same deals counted per rep, and อัตราปิดงาน. Nothing is gated on both queries:
 * the band renders from the summary at once, and each panel loads (or says it
 * failed) on its own. See docs/role-scoped-views.md. Manager keeps every
 * existing nav item; this is only a landing, nothing removes a route or permission.
 *
 * KNOWN DATA GAP (documented, not silently worked around): `api.tickets.list({})`
 * currently returns every ticket for sales_manager, exactly as it does for ceo
 * (mockApi's `tickets.list` only narrows for 'sales'/'import'/'account'). This
 * view treats the response as "team" scope, matching TicketDashboard; if the
 * real TicketRepository.appendRoleScope narrows sales_manager to their team,
 * this component needs no change. No authorization is loosened or tightened.
 *
 * All four money figures come from `dashboardSummary` (server totals; rows are capped,
 * so they are never summed here). The commission queue is GET /commissions/pending-approval,
 * the same list /commissions?view=pending shows the manager: SALE records only (manual
 * kinds are inserted MANAGER_APPROVED, see ApprovalBoard), each entry carrying its deal code.
 * The summary's `pendingApprovals.commissions` is not used: it also counts MANAGER_APPROVED
 * records only the CEO can act on. There is no
 * sales-target/quota field anywhere in the data model, so nothing here shows
 * progress against a target.
 */

const MAX_COMMISSIONS = 5;
const MAX_DEALS = 5;

// Order is the sort order (overdue -> follow-up due -> stale); `bar` colours the per-rep stack.
const REASONS = [
  { key: 'overdue', label: 'เกินกำหนดชำระ', short: 'เกินกำหนด', tone: 'danger', bar: 'bg-danger' },
  { key: 'followUp', label: 'ถึงกำหนดติดตาม', short: 'ถึงกำหนดติดตาม', tone: 'warning', bar: 'bg-warning' },
  { key: 'stale', label: 'ไม่มีความเคลื่อนไหว 7 วัน', short: 'ไม่เคลื่อนไหว', tone: 'neutral', bar: 'bg-border-strong' },
];

// Same precedence the list always used: overdue, then stale, then a past follow-up date.
function dealReason(ticket, now) {
  if (ticket.overdue) return 'overdue';
  if (ticket.stale) return 'stale';
  if (ticket.nextFollowUpAt && new Date(ticket.nextFollowUpAt) < now) return 'followUp';
  return null;
}

const timeOf = (t) => (t.stageUpdatedAt ? new Date(t.stageUpdatedAt).getTime() : 0);

const PANEL_ROW = 'px-5 py-3 mobile:px-3.5';

function PanelSkeleton({ label }) {
  return (
    <div className="grid gap-2 px-5 py-4 mobile:px-3.5" role="status" aria-busy="true" aria-label={label}>
      <Skeleton height={36} />
      <Skeleton height={36} />
    </div>
  );
}

function Failed() {
  return <p className="m-0 px-5 py-4 text-sm font-bold text-danger mobile:px-3.5">โหลดไม่สำเร็จ</p>;
}

function Empty({ children }) {
  return (
    <div className="flex items-center gap-3 px-5 py-4 text-text-muted mobile:px-3.5">
      <Icon name="check" size={18} />
      <span className="text-sm font-bold">{children}</span>
    </div>
  );
}

function CountPill({ count, unit = 'รายการ' }) {
  return (
    <span className="rounded-full bg-text px-2.5 py-px text-xs font-extrabold text-surface">
      <span aria-hidden="true">{count}</span>
      <span className="sr-only">{count} {unit}</span>
    </span>
  );
}

export function ManagerOverview({ user, employee, dashboardSummary }) {
  const navigate = useNavigate();
  const [reasonFilter, setReasonFilter] = useState('all');

  // The team-movement panel reads the same list with the default 7-day range (server flags per row).
  const teamRange = useMemo(() => defaultRange(), []);
  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketListByRange(teamRange.from, teamRange.to),
    queryFn: () => api.tickets.list({ movementFrom: teamRange.from, movementTo: teamRange.to }).then((r) => r.tickets ?? []),
  });
  const commissionsQuery = useQuery({
    queryKey: queryKeys.commissionsPendingApproval(),
    queryFn: () => api.commissions.pendingApproval().then((r) => r.commissions ?? []),
  });

  // null while loading: each panel shows its own skeleton, the rest of the page is already up.
  const tickets = ticketsQuery.data ?? null;
  const pendingCommissions = commissionsQuery.data ?? null;

  const outstanding = dashboardSummary?.financeOutstanding;
  const forecast = dashboardSummary?.forecastValue;
  const cashReceived = dashboardSummary?.cashReceivedThisMonth;
  const overdue = dashboardSummary?.overdueOutstanding;

  // "ดีลที่ต้องดูแล": open deals with a reason, grouped overdue -> follow-up due -> stale,
  // longest-untouched first inside each group.
  const attention = useMemo(() => {
    if (!tickets) return null;
    const now = new Date(); // not Date.now(): the purity rule flags it inside render
    return tickets
      .filter(isDealOpen)
      .map((ticket) => ({ ticket, reason: dealReason(ticket, now) }))
      .filter((row) => row.reason)
      .sort((a, b) => REASONS.findIndex((r) => r.key === a.reason) - REASONS.findIndex((r) => r.key === b.reason)
        || timeOf(a.ticket) - timeOf(b.ticket));
  }, [tickets]);

  const reasonCounts = useMemo(
    () => Object.fromEntries(REASONS.map((r) => [r.key, attention?.filter((row) => row.reason === r.key).length ?? 0])),
    [attention],
  );
  // A chosen reason whose deals are all gone (paid, followed up) falls back to ทั้งหมด rather than
  // stranding the list on an empty filter.
  const activeFilter = reasonFilter !== 'all' && reasonCounts[reasonFilter] > 0 ? reasonFilter : 'all';
  const visibleDeals = attention?.filter((row) => activeFilter === 'all' || row.reason === activeFilter) ?? [];

  const repRows = useMemo(() => {
    const byRep = new Map();
    (attention ?? []).forEach(({ ticket, reason }) => {
      const name = ticket.createdByName || '-';
      const entry = byRep.get(name) ?? { name, total: 0, overdue: 0, followUp: 0, stale: 0 };
      entry[reason] += 1;
      entry.total += 1;
      byRep.set(name, entry);
    });
    return [...byRep.values()].sort((a, b) => b.total - a.total);
  }, [attention]);
  const repMax = repRows[0]?.total ?? 0;

  const wonCount = tickets?.filter(isDealWon).length ?? 0;
  const lostCount = tickets?.filter(isDealLost).length ?? 0;
  const closeRate = wonCount + lostCount > 0 ? (wonCount / (wonCount + lostCount)) * 100 : null;

  const today = formatThaiDate(new Date().toISOString());
  const greeting = `สวัสดี, ${greetingName(employee?.nickName || employee?.nameTh || user?.name)}`;
  const money = (value) => (value == null ? '—' : formatMoney(value));
  const commissionsShown = pendingCommissions?.slice(0, MAX_COMMISSIONS) ?? [];
  const dealsShown = visibleDeals.slice(0, MAX_DEALS);

  return (
    <PageStack>
      <PageHeader
        title="ภาพรวมทีมขาย"
        subtitle={`${greeting} · ${today}`}
        actions={<Button type="button" variant="secondary" onClick={() => navigate('/tickets')}>ดูดีลทีม</Button>}
      />

      <section className="rounded-md border border-border bg-surface p-5 mobile:p-3.5" aria-label="ผลทีมเดือนนี้">
        <dl className="m-0 grid grid-cols-[1.5fr_1fr_1fr_1fr] gap-x-6 nav-drawer:grid-cols-2 nav-drawer:gap-y-4 mobile:grid-cols-1 mobile:divide-y mobile:divide-border mobile:[&>div]:py-2.5">
          <Figure lead label="เงินรับแล้ว (เดือนนี้)" value={money(cashReceived)} caption="เงินที่บัญชียืนยันรับแล้ว" />
          <Figure label="ค้างรับ" value={money(outstanding)} caption="ยอดคงค้างรวม (หน้าการเงิน)" />
          <Figure
            danger
            label="เกินกำหนดชำระ"
            value={money(overdue)}
            caption="ดีลทีมที่เลยกำหนดชำระ"
          />
          <Figure label="ยอดคาดการณ์" value={money(forecast)} caption="ดีลที่ยังไม่ได้รับคำสั่งซื้อ" />
        </dl>
      </section>

      <TeamMovementPanel deals={tickets} range={teamRange} />

      <div className="grid items-start gap-[18px] grid-cols-[1.6fr_1fr] nav-drawer:grid-cols-1">
        <div className="grid gap-[18px]">
          <Panel flush title="ค่าคอมรอคุณตรวจ" actions={pendingCommissions ? <CountPill count={pendingCommissions.length} /> : null}>
            {failed(commissionsQuery) ? (
              <Failed />
            ) : pendingCommissions === null ? (
              <PanelSkeleton label="กำลังโหลดค่าคอมรอคุณตรวจ" />
            ) : pendingCommissions.length === 0 ? (
              <Empty>ไม่มีค่าคอมรออนุมัติตอนนี้</Empty>
            ) : (
              <>
                {commissionsShown.map(({ commission: record, ticketCode }) => {
                  const ref = [record.invoiceDetails?.invoiceNumber, ticketCode].filter(Boolean).join(' · ');
                  return (
                    <div key={record.id} data-testid={`commission-row-${record.id}`} className={`flex items-center justify-between gap-3 border-t border-border first:border-t-0 ${PANEL_ROW}`}>
                      <div className="flex min-w-0 flex-1 items-center gap-3">
                        <Avatar name={record.salesRepName} size="sm" />
                        <div className="min-w-0 flex-1">
                          <strong className="block truncate text-sm font-extrabold text-text">{record.salesRepName || `#${record.salesRepId}`}</strong>
                          <code className="block truncate text-2xs text-text-muted">{ref || '-'}</code>
                        </div>
                      </div>
                      <span className="shrink-0 text-right">
                        <strong className="block tabular-nums text-sm text-text">{formatMoney(record.commissionableBase)}</strong>
                        <span className="block text-2xs text-text-muted">ฐานค่าคอม</span>
                      </span>
                    </div>
                  );
                })}
                <div className={`flex flex-wrap items-center justify-between gap-3 border-t border-border ${PANEL_ROW}`}>
                  <span className="text-sm text-text-muted">
                    {pendingCommissions.length > MAX_COMMISSIONS ? `อีก ${pendingCommissions.length - MAX_COMMISSIONS} รายการ · ` : ''}
                    รวมฐาน <strong className="tabular-nums text-text">{formatMoney(moneySum(pendingCommissions, (e) => e.commission.commissionableBase))}</strong>
                  </span>
                  <Button type="button" variant="success" onClick={() => navigate('/commissions?view=pending')} className="mobile:min-h-[44px]">
                    เปิดหน้าอนุมัติ
                  </Button>
                </div>
              </>
            )}
          </Panel>

          <Panel flush title="ดีลที่ต้องดูแล" actions={attention ? <CountPill count={attention.length} unit="ดีล" /> : null}>
            {failed(ticketsQuery) ? (
              <Failed />
            ) : attention === null ? (
              <PanelSkeleton label="กำลังโหลดดีลที่ต้องดูแล" />
            ) : attention.length === 0 ? (
              <Empty>ไม่มีดีลที่ต้องติดตามตอนนี้</Empty>
            ) : (
              <>
                <div role="group" aria-label="กรองตามเหตุผล" className={`flex flex-wrap gap-2 ${PANEL_ROW}`}>
                  {[{ key: 'all', label: 'ทั้งหมด', count: attention.length }, ...REASONS.map((r) => ({ ...r, count: reasonCounts[r.key] })).filter((chip) => chip.count > 0)].map((chip) => (
                    <Button
                      key={chip.key}
                      type="button"
                      size="sm"
                      variant={activeFilter === chip.key ? 'primary' : 'secondary'}
                      aria-pressed={activeFilter === chip.key}
                      onClick={() => setReasonFilter(chip.key)}
                      className="mobile:min-h-[44px]"
                    >
                      {chip.label} {chip.count}
                    </Button>
                  ))}
                </div>
                {dealsShown.map(({ ticket, reason }) => {
                  const meta = REASONS.find((r) => r.key === reason);
                  return (
                    <button
                      type="button"
                      key={ticket.id}
                      data-testid={`deal-row-${ticket.id}`}
                      onClick={() => navigate(`/tickets/${ticket.id}`)}
                      className={`flex w-full cursor-pointer items-center justify-between gap-3 border-0 border-t border-border bg-transparent text-left text-text hover:bg-surface-hover mobile:min-h-[44px] ${PANEL_ROW}`}
                    >
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <strong className="min-w-0 truncate text-sm font-extrabold text-text">{ticket.customerName || ticket.title}</strong>
                          <StatusBadge tone={meta.tone}>{meta.label}</StatusBadge>
                        </div>
                        <code className="block text-2xs text-text-muted">{ticket.code} · {ticket.createdByName || '-'}</code>
                      </div>
                      <Icon name="chevronRight" size={16} className="shrink-0 text-text-faint" />
                    </button>
                  );
                })}
                <div className={`flex flex-wrap items-center justify-between gap-3 border-t border-border ${PANEL_ROW}`}>
                  <span className="text-sm text-text-muted">แสดง {dealsShown.length} จาก {visibleDeals.length}</span>
                  {visibleDeals.length > MAX_DEALS ? (
                    <Button type="button" variant="text" size="sm" onClick={() => navigate('/tickets')} className="mobile:min-h-[44px]">
                      ดูอีก {visibleDeals.length - MAX_DEALS} ดีล
                    </Button>
                  ) : null}
                </div>
              </>
            )}
          </Panel>
        </div>

        <div className="grid gap-[18px]">
          <Panel title="ดีลที่ต้องดูแล ตามพนักงาน">
            {failed(ticketsQuery) ? (
              <Failed />
            ) : attention === null ? (
              <PanelSkeleton label="กำลังโหลดดีลตามพนักงาน" />
            ) : repRows.length === 0 ? (
              <p className="m-0 text-sm text-text-muted">ยังไม่มีดีลที่ต้องดูแล</p>
            ) : (
              <div className="flex flex-col gap-3">
                {repRows.map((rep) => (
                  <div key={rep.name} data-testid="attention-rep" className="grid gap-1">
                    <div className="flex items-baseline justify-between gap-3">
                      <span className="min-w-0 truncate text-sm font-bold text-text">{rep.name}</span>
                      <span className="shrink-0 text-sm tabular-nums text-text-muted">{rep.total} ดีล</span>
                    </div>
                    {/* Sized against the top rep, never a quota (none exists). */}
                    <div className="flex h-2 overflow-hidden rounded-full bg-surface-subtle" style={{ width: `${(rep.total / repMax) * 100}%` }}>
                      {REASONS.map((r) => (rep[r.key] ? <div key={r.key} className={r.bar} style={{ flex: rep[r.key] }} /> : null))}
                    </div>
                  </div>
                ))}
                <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-muted">
                  {REASONS.map((r) => (
                    <span key={r.key} className="inline-flex items-center gap-1.5">
                      <span aria-hidden="true" className={`size-2 rounded-full ${r.bar}`} />
                      {r.short}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </Panel>

          <Panel title="อัตราปิดงาน">
            {failed(ticketsQuery) ? (
              <Failed />
            ) : tickets === null ? (
              <Skeleton height={28} />
            ) : (
              <p className="m-0 flex flex-wrap items-baseline gap-x-3 text-sm text-text-muted">
                <strong className="text-2xl font-extrabold tabular-nums text-text">{closeRate == null ? '—' : `${closeRate.toFixed(0)}%`}</strong>
                {closeRate == null ? null : <span>ปิดสำเร็จ {wonCount} · เสีย {lostCount}</span>}
              </p>
            )}
          </Panel>
        </div>
      </div>
    </PageStack>
  );
}
