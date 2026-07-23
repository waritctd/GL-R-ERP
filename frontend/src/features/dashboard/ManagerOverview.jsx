import { useEffect, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Avatar } from '../../components/common/Avatar.jsx';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { Panel, PageStack, StatGrid } from '../../components/common/Layout.jsx';
import { SkeletonCard, SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney, formatThaiDate } from '../../utils/format.js';

// No server-side status filter exists on GET /api/commissions (mirrors
// CommissionController#list — payrollMonth is the only query param it
// reads); `status: 'SUBMITTED'` is passed anyway so a future backend filter
// works for free, and the result is still filtered client-side below,
// exactly like CommissionPage's own `totals.submitted` does.
const TEAM_COMMISSIONS_QUERY_KEY = ['commissions', 'list', 'submitted'];

function startOfMonth() {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), 1);
}

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

function dealAmount(ticket) {
  return Number(ticket.amountPayable ?? 0);
}

/**
 * Sales Manager role-scoped landing (team cockpit): pulse (team ยอด this
 * month / commissions awaiting the manager's own review / deals needing
 * attention / open team pipeline) + the ค่าคอมรออนุมัติ worklist (deep-links
 * into CommissionPage's existing approve flow — never duplicates the
 * approval mutation itself) + a "ดีลทีมที่ต้องดูแล" worklist + a right rail
 * (leaderboard, close-rate). See docs/role-scoped-views.md. Manager keeps
 * every existing nav item (รายการดีล / คิวใบขอราคา / ค่าคอมมิชชัน / แคตตาล็อก)
 * — this is only a new landing, nothing here removes a route or a permission.
 *
 * KNOWN DATA GAP (documented, not silently worked around — same convention
 * as AccountOverview's own note above its ticket query): `api.tickets.list({})`
 * currently returns every ticket for sales_manager, exactly as it does for
 * ceo — mockApi.js's `tickets.list` only narrows the response for
 * 'sales'/'import'/'account' (see the comment directly above that filter in
 * mockApi.js). This view treats the response as "team" scope per the
 * role-scoped-views plan, matching how TicketDashboard already consumes this
 * same endpoint for this role today; if/when the real
 * TicketRepository.appendRoleScope narrows sales_manager to their own
 * division/team, this component needs no change — only the row count moves.
 * No authorization was loosened or tightened by this branch (UI-only landing,
 * not a new authz surface).
 *
 * `api.dashboard.summary()` is deliberately NOT used here even though the
 * task brief lists it: for sales_manager, mockApi's `dashboardTickets()`
 * scope is 'none' (only 'import'/'ceo'/'sales' get a non-empty tickets
 * block), so its `tickets.*` counts are always zero for this role, and its
 * `pendingApprovals.commissions` count also includes MANAGER_APPROVED
 * records the manager cannot act on (only CEO can). Deriving every pulse
 * number directly from `api.tickets.list({})` / `api.commissions.list(...)`
 * below is strictly more correct than reading either of those summary
 * fields would be — flagged here rather than silently diverging from the
 * plan's data-source list.
 *
 * There is also no sales-target/quota field anywhere in the data model (mock
 * or Java DTO), so "ยอดทีมเดือนนี้" renders as a plain ฿ total with a
 * "ปิดแล้ว N ดีล" helper instead of the plan's "target progress bar" — no
 * target number exists to measure progress against, and inventing one would
 * be fabricating business data (CLAUDE.md). Flagged as a follow-up decision
 * for the plan owner, not fabricated here.
 */
export function ManagerOverview({ user, employee, showToast }) {
  const navigate = useNavigate();

  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((r) => r.tickets ?? []),
  });
  const commissionsQuery = useQuery({
    queryKey: TEAM_COMMISSIONS_QUERY_KEY,
    queryFn: () => api.commissions.list({ status: 'SUBMITTED' }).then((r) => r.commissions ?? []),
  });

  useEffect(() => {
    const error = ticketsQuery.error || commissionsQuery.error;
    if (error) showToast?.('error', error.message || 'โหลดข้อมูลไม่สำเร็จ');
  }, [ticketsQuery.error, commissionsQuery.error, showToast]);

  const loading = ticketsQuery.isLoading || commissionsQuery.isLoading;
  // Memoized (not a plain `?? []` fallback) so every useMemo below that
  // depends on `tickets` doesn't re-derive on every render just because a
  // fresh `[]` fallback is a new reference each time.
  const tickets = useMemo(() => ticketsQuery.data ?? [], [ticketsQuery.data]);
  // Defensive client-side filter — see the TEAM_COMMISSIONS_QUERY_KEY comment
  // above: the mock (and possibly the real endpoint) does not filter by
  // status server-side.
  const pendingCommissions = useMemo(
    () => (commissionsQuery.data ?? []).filter((record) => record.status === 'SUBMITTED'),
    [commissionsQuery.data],
  );

  const ticketsById = useMemo(() => new Map(tickets.map((t) => [t.id, t])), [tickets]);

  const monthStart = useMemo(() => startOfMonth(), []);
  const closedThisMonth = useMemo(
    () => tickets.filter((t) => isDealWon(t) && t.stageUpdatedAt && new Date(t.stageUpdatedAt) >= monthStart),
    [tickets, monthStart],
  );
  const teamMonthValue = useMemo(
    () => closedThisMonth.reduce((sum, t) => sum + dealAmount(t), 0),
    [closedThisMonth],
  );

  const openTickets = useMemo(() => tickets.filter(isDealOpen), [tickets]);
  const openPipelineValue = useMemo(
    () => openTickets.reduce((sum, t) => sum + dealAmount(t), 0),
    [openTickets],
  );

  // "ดีลต้องดูแล": an open deal with no activity in 7 days (`ticket.stale`,
  // mirrors TicketRepository.enrichSummary's own staleness window — see
  // features/tickets/dealTrackingMeta.js's STALE_ACTIVITY_DAYS), a payment
  // overdue (`ticket.overdue`), or a follow-up date already in the past.
  const attentionTickets = useMemo(() => {
    // `new Date()` (not `Date.now()`, which the react-hooks purity rule
    // flags as a well-known impure call inside render) — same pattern this
    // file's own `startOfMonth()` already uses above.
    const now = new Date();
    return openTickets
      .filter((t) => t.stale || t.overdue || (t.nextFollowUpAt && new Date(t.nextFollowUpAt) < now))
      .sort((a, b) => {
        // Payment slipping outranks an unattended lead.
        if (Boolean(a.overdue) !== Boolean(b.overdue)) return a.overdue ? -1 : 1;
        const aTime = a.stageUpdatedAt ? new Date(a.stageUpdatedAt).getTime() : 0;
        const bTime = b.stageUpdatedAt ? new Date(b.stageUpdatedAt).getTime() : 0;
        return aTime - bTime; // longest-untouched first
      });
  }, [openTickets]);

  const wonCount = useMemo(() => tickets.filter(isDealWon).length, [tickets]);
  const lostCount = useMemo(() => tickets.filter(isDealLost).length, [tickets]);
  const closeRate = wonCount + lostCount > 0 ? (wonCount / (wonCount + lostCount)) * 100 : null;

  const leaderboard = useMemo(() => {
    const byRep = new Map();
    closedThisMonth.forEach((t) => {
      const key = t.createdById ?? t.createdByName ?? 'unknown';
      const current = byRep.get(key) || { id: key, name: t.createdByName || 'ไม่ระบุ', amount: 0, count: 0 };
      current.amount += dealAmount(t);
      current.count += 1;
      byRep.set(key, current);
    });
    return [...byRep.values()].sort((a, b) => b.amount - a.amount).slice(0, 5);
  }, [closedThisMonth]);
  const leaderboardMax = leaderboard[0]?.amount || 0;

  const firstName = employee?.nickName || employee?.nameTh || user?.name || '';
  const today = formatThaiDate(new Date().toISOString());

  return (
    <PageStack>
      <PageHeader
        title={`สวัสดี คุณ${firstName}`}
        subtitle={`ภาพรวมทีมขาย · ${today} — ผลงานทีม · ค่าคอมที่รอคุณตรวจ · ดีลที่ต้องดูแล`}
        actions={<Button type="button" variant="text" onClick={() => navigate('/tickets')}>ดูดีลทีม →</Button>}
      />

      {loading ? (
        <div aria-busy="true" aria-label="กำลังโหลดข้อมูลทีมขาย">
          <StatGrid>
            <SkeletonCard lines={1} />
            <SkeletonCard lines={1} />
            <SkeletonCard lines={1} />
            <SkeletonCard lines={1} />
          </StatGrid>
          <Panel style={{ marginTop: 14 }}>
            <SkeletonText lines={5} />
          </Panel>
        </div>
      ) : (
        <>
          {/* Team pulse. */}
          <StatGrid>
            <StatCard
              icon="badgeDollar"
              label="ยอดทีมเดือนนี้"
              value={formatMoney(teamMonthValue)}
              helper={`ปิดแล้ว ${closedThisMonth.length} ดีล`}
              tone="teal"
            />
            <StatCard
              icon="clock"
              label="ค่าคอมรออนุมัติ"
              value={pendingCommissions.length}
              helper="รอคุณตรวจ"
              tone={pendingCommissions.length > 0 ? 'amber' : 'indigo'}
              onClick={pendingCommissions.length > 0 ? () => navigate('/commissions') : undefined}
            />
            <StatCard
              icon="triangleAlert"
              label="ดีลต้องดูแล"
              value={attentionTickets.length}
              helper="ค้าง/เกินติดตาม"
              tone={attentionTickets.length > 0 ? 'rose' : 'indigo'}
              onClick={() => navigate('/tickets')}
            />
            <StatCard
              icon="briefcase"
              label="pipeline ทีม"
              value={formatMoney(openPipelineValue)}
              helper={`${openTickets.length} ดีลเปิดอยู่`}
              tone="blue"
              onClick={() => navigate('/tickets')}
            />
          </StatGrid>

          <div className="grid gap-[18px] items-start grid-cols-[1.6fr_1fr] max-[1040px]:grid-cols-1">
            <div className="grid gap-[18px]">
              {/* Centerpiece: ค่าคอมรออนุมัติ worklist — deep-links to
                  CommissionPage's own manager-approve flow, never duplicates
                  the approval mutation here. */}
              <Panel title="ค่าคอมรออนุมัติ" className="!p-0 overflow-hidden">
                {pendingCommissions.length === 0 ? (
                  <div className="flex items-center gap-3 p-5 text-text-muted">
                    <Icon name="check" size={18} />
                    <span className="text-sm font-bold">ไม่มีค่าคอมรออนุมัติตอนนี้</span>
                  </div>
                ) : (
                  <div className="flex flex-col">
                    {pendingCommissions.map((record) => {
                      const linkedTicket = record.sourceTicketId ? ticketsById.get(record.sourceTicketId) : null;
                      const ref = [record.invoiceDetails?.invoiceNumber, linkedTicket?.code].filter(Boolean).join(' · ');
                      return (
                        <div
                          key={record.id}
                          className="flex items-center justify-between gap-3 border-t border-border px-5 py-3 first:border-t-0 max-[720px]:flex-col max-[720px]:items-stretch max-[720px]:gap-2.5 max-[720px]:p-4"
                        >
                          <div className="flex min-w-0 flex-1 items-center gap-3">
                            <Avatar name={record.salesRepName} size="sm" />
                            <div className="min-w-0 flex-1">
                              <strong className="block truncate text-sm font-extrabold text-text">
                                {record.salesRepName || `#${record.salesRepId}`}
                              </strong>
                              <code className="block truncate text-2xs text-text-muted">{ref || '-'}</code>
                            </div>
                          </div>
                          <div className="flex shrink-0 items-center gap-3 max-[720px]:w-full max-[720px]:justify-between">
                            <span className="text-right">
                              <strong className="block tabular-nums text-sm text-text">{formatMoney(record.commissionableBase)}</strong>
                              <span className="block text-2xs text-text-muted">ฐานค่าคอม</span>
                            </span>
                            <Button
                              type="button"
                              variant="success"
                              className="max-[720px]:flex-1 max-[720px]:min-h-11"
                              onClick={() => navigate('/commissions')}
                            >
                              ตรวจ · อนุมัติ
                            </Button>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </Panel>

              {/* ดีลทีมที่ต้องดูแล — stalled / overdue-follow-up open deals. */}
              <Panel title="ดีลทีมที่ต้องดูแล" className="!p-0 overflow-hidden">
                {attentionTickets.length === 0 ? (
                  <div className="flex items-center gap-3 p-5 text-text-muted">
                    <Icon name="check" size={18} />
                    <span className="text-sm font-bold">ไม่มีดีลที่ต้องติดตามตอนนี้</span>
                  </div>
                ) : (
                  <div className="flex flex-col">
                    {attentionTickets.map((ticket) => {
                      const reason = ticket.overdue
                        ? 'เกินกำหนดชำระ'
                        : ticket.stale
                          ? 'ไม่มีความเคลื่อนไหว 7 วัน'
                          : 'ถึงกำหนดติดตาม';
                      return (
                        <button
                          type="button"
                          key={ticket.id}
                          onClick={() => navigate(`/tickets/${ticket.id}`)}
                          className="flex cursor-pointer items-center justify-between gap-3 border-t border-border px-5 py-3 text-left first:border-t-0 hover:bg-surface-hover max-[720px]:flex-col max-[720px]:items-stretch max-[720px]:gap-2 max-[720px]:p-4"
                        >
                          <div className="min-w-0 flex-1">
                            <div className="flex flex-wrap items-center gap-2">
                              <strong className="min-w-0 truncate text-sm font-extrabold text-text">
                                {ticket.customerName || ticket.title}
                              </strong>
                              <StatusBadge tone={ticket.overdue ? 'danger' : 'warning'}>{reason}</StatusBadge>
                            </div>
                            <code className="block text-2xs text-text-muted">{ticket.code} · {ticket.createdByName || '-'}</code>
                          </div>
                          <Icon name="chevronRight" size={16} className="shrink-0 text-text-faint" />
                        </button>
                      );
                    })}
                  </div>
                )}
              </Panel>
            </div>

            {/* Right rail — team leaderboard + close rate. */}
            <div className="grid gap-[18px]">
              <Panel title="อันดับทีม (เดือนนี้)">
                {leaderboard.length === 0 ? (
                  <p className="m-0 text-sm text-text-muted">ยังไม่มีดีลปิดในเดือนนี้</p>
                ) : (
                  <div className="grid gap-3">
                    {leaderboard.map((rep, index) => (
                      <div key={rep.id} className="grid gap-1">
                        <div className="flex items-center justify-between gap-2 text-sm">
                          <span className="min-w-0 truncate font-bold text-text">{index + 1}. {rep.name}</span>
                          <strong className="tabular-nums text-text">{formatMoney(rep.amount)}</strong>
                        </div>
                        {/* Teal fill — DESIGN.md's "Rationed Teal Rule" reserves teal
                            for progress-bar fills exactly like this one. */}
                        <div className="h-1.5 w-full overflow-hidden rounded-full bg-surface-subtle">
                          <div
                            className="h-full rounded-full bg-accent"
                            style={{ width: `${leaderboardMax > 0 ? Math.max(4, (rep.amount / leaderboardMax) * 100) : 0}%` }}
                          />
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </Panel>

              <Panel title="อัตราปิดงาน">
                <div className="text-center">
                  <strong className="block text-3xl font-extrabold tabular-nums text-text">
                    {closeRate == null ? '—' : `${closeRate.toFixed(0)}%`}
                  </strong>
                  <span className="text-xs text-text-muted">{wonCount} ปิดสำเร็จ / {lostCount} เสีย</span>
                </div>
              </Panel>
            </div>
          </div>
        </>
      )}
    </PageStack>
  );
}
