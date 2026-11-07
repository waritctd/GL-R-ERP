import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { Panel, PageStack } from '../../components/common/Layout.jsx';
import { formatMoney, formatThaiDate, greetingName } from '../../utils/format.js';
import { accountMoneyBucket, awaitingGoodsForFinalPayment, nextAccountAction } from '../tickets/accountActions.js';
import { dedupeById, useAccountTickets } from '../finance/accountTickets.js';
import { moneyMilestoneOf } from '../finance/moneyMilestone.js';
import { Figure, WorklistGroup, WorklistTotal, failed, moneySum } from './dashboardParts.jsx';

// Worklist groups in money order, keyed by nextAccountAction's own key (also the /finance ?action= value).
// `field` is the ticket money the group sums and each row shows: the balance owed, the deposit notice
// total, or (close / invoice, where nothing is owed) the deal value.
const GROUPS = [
  { key: 'chaseOverdue', label: 'ติดตามชำระ', dot: 'bg-danger', tint: 'bg-danger-bg', field: 'amountOutstanding', sumLabel: 'ค้าง', cta: 'danger' },
  { key: 'confirmDeposit', label: 'ยืนยันรับมัดจำ', dot: 'bg-warning', field: 'depositDue', sumLabel: 'มัดจำ', cta: 'success' },
  { key: 'confirmFinalPayment', label: 'รับชำระส่วนที่เหลือ', dot: 'bg-accent', field: 'amountOutstanding', sumLabel: 'ค้าง', cta: 'success' },
  { key: 'confirmCloseReady', label: 'ยืนยันพร้อมปิดงาน', dot: 'bg-primary', field: 'amountPayable', sumLabel: 'มูลค่าดีล', cta: 'primary' },
  { key: 'recordInvoiceCommission', label: 'บันทึกใบกำกับ', dot: 'bg-info', field: 'amountPayable', sumLabel: 'มูลค่าดีล', cta: 'text' },
];

const money = (value) => (value == null ? '—' : formatMoney(value));
const dueTime = (t) => (t.dueDate ? new Date(t.dueDate).getTime() : Infinity);
const owed = (t) => Number(t.amountOutstanding ?? 0);

function rowMeta(key, t) {
  if (key === 'chaseOverdue') return `${t.code} · เกินกำหนด${t.dueDate ? ` · ครบ ${formatThaiDate(t.dueDate)}` : ''}`;
  if (key === 'confirmCloseReady') return `${t.code} · ชำระครบแล้ว`;
  if (key === 'recordInvoiceCommission') return `${t.code} · ปิดงานแล้ว · รอบันทึกใบกำกับ`;
  const step = moneyMilestoneOf(t.salesStage);
  if (step) return `${t.code} · ขั้น ${step.index} ${step.label}`;
  return `${t.code} · ยังไม่ได้รับคำสั่งซื้อ`;
}

/**
 * Account (ฝ่ายบัญชี) landing: a money band, then one worklist grouped by next action. Deals the
 * final-payment stage gate holds (owed, goods not yet delivered) sit in รอสินค้าเข้า with no button.
 * Only account reaches this page (App.jsx), so nextAccountAction is called without a viewer role.
 */
export function AccountOverview({ user, employee, dashboardSummary, showToast }) {
  const navigate = useNavigate();
  const { scopedQuery, closedPaidQuery } = useAccountTickets(showToast);

  const allTickets = useMemo(
    () => dedupeById(scopedQuery.data ?? [], closedPaidQuery.data ?? []),
    [scopedQuery.data, closedPaidQuery.data],
  );
  // A group waits on its own source: everything but the CLOSED_PAID-only invoice step needs just the scoped list.
  const sources = (key) => (key === 'recordInvoiceCommission' ? [scopedQuery, closedPaidQuery] : [scopedQuery]);

  const byAction = useMemo(() => {
    const map = Object.fromEntries(GROUPS.map((g) => [g.key, []]));
    allTickets.forEach((ticket) => {
      const action = nextAccountAction(ticket, user?.role);
      if (action) map[action.key].push(ticket);
    });
    // Earliest due first, then the largest balance.
    Object.values(map).forEach((list) => list.sort((a, b) => dueTime(a) - dueTime(b) || owed(b) - owed(a)));
    return map;
  }, [allTickets, user?.role]);

  const held = useMemo(() => allTickets.filter(awaitingGoodsForFinalPayment), [allTickets]);

  const groups = GROUPS.map((g) => {
    const qs = sources(g.key);
    const isFailed = qs.some(failed);
    const loaded = qs.every((q) => q.data !== undefined);
    const list = byAction[g.key];
    return { ...g, isFailed, list: loaded ? list : null, count: loaded ? list.length : 0 };
  });
  const settled = groups.every((g) => g.list !== null || g.isFailed);
  const total = groups.reduce((sum, g) => sum + g.count, 0);
  const allEmpty = groups.every((g) => g.count === 0 && !g.isFailed);

  // Band: the two summary figures arrive with the page; the deal-derived ones wait for the scoped list.
  const dealsReady = scopedQuery.data !== undefined;
  const overdueTickets = dealsReady ? allTickets.filter((t) => accountMoneyBucket(t) === 'overdue') : null;
  const depositTickets = dealsReady ? allTickets.filter((t) => accountMoneyBucket(t) === 'depositPending') : null;
  const earliestDue = overdueTickets?.length ? Math.min(...overdueTickets.map(dueTime)) : Infinity;
  // One definition per money figure: the server totals, same as the CEO and sales-manager pages.
  const received = dashboardSummary?.cashReceivedThisMonth ?? null;
  const overdue = dashboardSummary?.overdueOutstanding ?? null;

  const firstName = employee?.nickName || employee?.nameTh || user?.name || '';
  const today = formatThaiDate(new Date().toISOString());
  const onOpen = (to) => navigate(to);

  return (
    <PageStack>
      <PageHeader title="ภาพรวมการเงิน" subtitle={`สวัสดี, ${greetingName(firstName)} · ${today}`} />

      <section className="rounded-md border border-border bg-surface p-5 mobile:p-3.5" aria-label="การเงินเดือนนี้">
        <dl className="m-0 grid grid-cols-[1.5fr_1fr_1fr_1fr] gap-x-6 nav-drawer:grid-cols-2 nav-drawer:gap-y-4 mobile:grid-cols-1 mobile:divide-y mobile:divide-border mobile:[&>div]:py-2.5">
          <Figure lead label="ค้างรับทั้งหมด" value={money(dashboardSummary?.financeOutstanding)} caption="ยอดคงค้างรวม (หน้าการเงิน)" />
          <Figure
            danger
            label="เกินกำหนดชำระ"
            value={money(overdue)}
            caption={overdue == null || !overdueTickets ? '' : overdueTickets.length > 0
              ? `${overdueTickets.length} ดีล${earliestDue === Infinity ? '' : ` · เก่าสุดครบ ${formatThaiDate(earliestDue)}`}`
              : 'ไม่มีดีลเกินกำหนด'}
          />
          <Figure
            label="รอรับมัดจำ"
            value={money(depositTickets && moneySum(depositTickets, (t) => t.depositDue))}
            caption={depositTickets ? `ใบแจ้งมัดจำที่ออกแล้ว ${depositTickets.length} ใบ` : ''}
          />
          <Figure
            label="รับแล้ว (เดือนนี้)"
            value={money(received)}
            caption={received == null ? '' : received > 0 ? 'เงินที่บัญชียืนยันรับแล้ว' : 'ยังไม่มีรับเข้าเดือนนี้'}
          />
        </dl>
      </section>

      <div className="grid items-start gap-[18px] grid-cols-[1.6fr_1fr] nav-drawer:grid-cols-1">
        <Panel flush title="สิ่งที่ต้องทำ" actions={settled ? <WorklistTotal total={total} /> : null}>
          {settled && allEmpty ? (
            <div className="flex items-center gap-3 p-5 text-text-muted">
              <Icon name="check" size={18} />
              <span className="text-sm font-bold">ไม่มีรายการที่ต้องดำเนินการตอนนี้</span>
            </div>
          ) : groups.map((g) => (
            <WorklistGroup
              key={g.key}
              groupKey={g.key}
              label={g.label}
              dot={g.dot}
              tint={g.tint}
              count={g.count}
              failed={g.isFailed}
              to={`/finance?action=${g.key}`}
              onOpen={onOpen}
              summary={`${g.count} ดีล · ${g.sumLabel} ${formatMoney(moneySum(g.list ?? [], (t) => t[g.field]))}`}
              rows={g.list?.map((t) => ({
                testId: `worklist-row-${g.key}-${t.id}`,
                entity: t.customerName || t.title,
                meta: rowMeta(g.key, t),
                amount: formatMoney(Number(t[g.field] ?? 0)),
                ctaLabel: nextAccountAction(t, user?.role).label,
                ctaVariant: g.cta,
                to: nextAccountAction(t, user?.role).to,
              })) ?? null}
            />
          ))}
        </Panel>

        {dealsReady && held.length > 0 ? (
          <Panel
            flush
            data-testid="awaiting-goods-panel"
            title="รอสินค้าเข้า"
            actions={<span className="text-xs font-semibold text-text-muted">{held.length} ดีล · {formatMoney(moneySum(held, owed))}</span>}
          >
            {held.map((t) => (
              <div key={t.id} data-testid={`awaiting-goods-row-${t.id}`} className="flex items-center justify-between gap-3 border-b border-border px-5 py-2 mobile:px-3.5">
                <div className="min-w-0">
                  <strong className="block truncate text-sm font-bold text-text">{t.customerName || t.title}</strong>
                  <span className="block truncate text-xs text-text-muted">{rowMeta('hold', t)}</span>
                </div>
                <span className="shrink-0 text-sm font-bold tabular-nums text-text">{money(t.amountOutstanding)}</span>
              </div>
            ))}
            <p className="m-0 flex items-start gap-2 px-5 py-3 text-xs text-text-muted mobile:px-3.5">
              <Icon name="lock" size={14} className="mt-0.5 shrink-0" />
              <span>รับชำระส่วนที่เหลือได้เมื่อดีลถึงขั้น 4 ส่งมอบ</span>
            </p>
          </Panel>
        ) : null}
      </div>
    </PageStack>
  );
}
