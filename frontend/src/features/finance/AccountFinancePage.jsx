/* Hallmark · genre: modern-minimal · macrostructure: Stat-Led · tone: utilitarian · theme: project system (Sarabun + indigo, owner-locked) · redesign */
/* Hallmark · pre-emit critique: P4 H4 E4 S4 R4 V4 (lead figure is the real outstanding total of the loaded rows and always paired with words; worklist keeps the subgrid columns) */
import { useEffect, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { FilterBar, FilterField, PageStack } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { TabPanel, Tabs } from '../../components/common/Tabs.jsx';
import { cn } from '../../utils/cn.js';
import { dealStageLabel, formatMoney, formatThaiDate } from '../../utils/format.js';
import { nextAccountAction } from '../tickets/accountActions.js';
import { findStage, useStageCatalog } from '../tickets/stageCatalog.js';
import { MONEY_TRACK, moneyMilestoneOf } from './moneyMilestone.js';

// The next-action filter. Keys are exactly nextAccountAction()'s own `action.key`, so filtering
// never drifts from what a row's action column shows.
const ACTION_FILTERS = [
  { key: 'chaseOverdue', label: 'ติดตามชำระ (เกินกำหนด)' },
  { key: 'confirmDeposit', label: 'ยืนยันรับมัดจำ' },
  { key: 'confirmFinalPayment', label: 'รับชำระส่วนที่เหลือ' },
  { key: 'confirmCloseReady', label: 'ยืนยันพร้อมปิดงาน' },
  { key: 'recordInvoiceCommission', label: 'บันทึกใบกำกับ' },
];

const money = (value) => (value == null ? '—' : formatMoney(value));

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

function Bone({ className }) {
  return <span aria-hidden="true" className={cn('block rounded-md bg-surface-muted motion-safe:animate-pulse', className)} />;
}

function DealRow({ row, catalog }) {
  const { ticket, action, milestone } = row;
  const stage = findStage(catalog, ticket.salesStage);
  const stageCode = stage?.businessCode;
  const chip = milestone
    ? `${milestone.index} · ${milestone.label}${stageCode ? ` · ${stageCode}` : ''}`
    : `${dealStageLabel(ticket.salesStage).label}${stageCode ? ` · ${stageCode}` : ''}`;
  return (
    <li className={cn('col-span-full grid grid-cols-subgrid border-b border-border last:border-b-0', action?.urgent && 'shadow-[inset_3px_0_0_var(--color-danger)]')}>
      <Link
        to={`/finance/deals/${ticket.id}`}
        className="col-span-full grid grid-cols-subgrid items-center gap-y-1.5 px-5 py-3 text-text no-underline hover:bg-surface-hover active:bg-surface-muted pointer-coarse:min-h-[44px] @max-[60rem]:px-4 @max-[60rem]:py-3.5"
      >
        <span className="grid min-w-0 gap-0.5 @max-[60rem]:col-start-1 @max-[60rem]:row-start-1">
          <strong className="min-w-0 text-md font-bold text-text [overflow-wrap:anywhere]">{ticket.customerName || ticket.title}</strong>
          <span className="min-w-0 text-sm text-text-muted [overflow-wrap:anywhere]">
            {[ticket.code, ticket.projectName].filter(Boolean).join(' · ')}
          </span>
        </span>
        <span className="contents @max-[60rem]:col-span-2 @max-[60rem]:col-start-1 @max-[60rem]:row-start-2 @max-[60rem]:flex @max-[60rem]:flex-wrap @max-[60rem]:items-center @max-[60rem]:gap-x-3 @max-[60rem]:gap-y-1">
          <span className="order-2 min-w-0 text-sm text-text-secondary [overflow-wrap:anywhere]">{chip}</span>
          <span className={cn('order-3 min-w-0 text-sm font-bold [overflow-wrap:anywhere]', action?.urgent ? 'text-danger' : 'text-text', !action && '@max-[60rem]:hidden')}>
            {action ? action.label : <span className="font-normal text-text-muted">—</span>}
          </span>
          <span className={cn('order-5 min-w-0 text-right text-sm @max-[60rem]:text-left', !ticket.overdue && !ticket.paymentDueDate && '@max-[60rem]:hidden')}>
            {ticket.overdue ? (
              <span className="inline-flex flex-wrap items-center justify-end gap-x-1 font-bold text-danger">
                <Icon name="triangleAlert" size={13} />
                <span>เกินกำหนดชำระ</span>
                {ticket.paymentDueDate ? <span className="font-medium tabular-nums">{formatThaiDate(ticket.paymentDueDate)}</span> : null}
              </span>
            ) : ticket.paymentDueDate ? (
              <span className="tabular-nums text-text-muted">{formatThaiDate(ticket.paymentDueDate)}</span>
            ) : (
              <span className="text-text-muted">—</span>
            )}
          </span>
        </span>
        <span className="order-4 whitespace-nowrap text-right text-md font-bold tabular-nums text-text @max-[60rem]:col-start-2 @max-[60rem]:row-start-1">
          {money(ticket.amountOutstanding)}
        </span>
      </Link>
    </li>
  );
}

/**
 * งานการเงิน — the page IS the worklist. Default view "ต้องดำเนินการ" lists deals where
 * `nextAccountAction` (the single source of "what does ฝ่ายบัญชี do next", shared with the
 * Overview) says there is something to do; "ทั้งหมด" adds every other row the account list returns,
 * including closed-paid history. Each row is one link to /finance/deals/:id.
 *
 * Same data-scope note as before: which deals appear is decided server-side (account's list scope);
 * this page filters and orders what it is given.
 */
export function AccountFinancePage({ user, showToast }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const view = searchParams.get('view') === 'all' ? 'all' : 'todo';
  const milestoneFilter = searchParams.get('milestone') ?? '';
  // `?action=` is the param; the old `?stage=` (the filter's former name) is still read so existing links work.
  const actionFilter = searchParams.get('action') ?? searchParams.get('stage') ?? '';
  const { catalog } = useStageCatalog();

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
  const loadError = scopedQuery.error || closedPaidQuery.error;

  const allRows = useMemo(() => {
    const tickets = dedupeById(scopedQuery.data ?? [], closedPaidQuery.data ?? []);
    return tickets
      // GLA-118: pass the viewer's role so the CEO (who reaches this page too) is never offered
      // confirmDeposit / confirmFinalPayment — see nextAccountAction's `viewerRole` Javadoc.
      .map((ticket) => ({
        ticket,
        action: nextAccountAction(ticket, user?.role),
        milestone: moneyMilestoneOf(ticket.salesStage),
      }))
      .sort((a, b) => {
        const ua = a.action?.urgent === true;
        const ub = b.action?.urgent === true;
        if (ua !== ub) return ua ? -1 : 1;
        const dueA = a.ticket.paymentDueDate ? new Date(a.ticket.paymentDueDate).getTime() : Infinity;
        const dueB = b.ticket.paymentDueDate ? new Date(b.ticket.paymentDueDate).getTime() : Infinity;
        if (dueA !== dueB) return dueA - dueB;
        return rowAmount(b.ticket) - rowAmount(a.ticket);
      });
  }, [scopedQuery.data, closedPaidQuery.data, user?.role]);

  const actionable = useMemo(() => allRows.filter((r) => r.action != null), [allRows]);

  // The summary always describes the actionable set, whichever view is showing.
  const stats = useMemo(() => {
    const total = actionable.reduce((sum, r) => sum + Number(r.ticket.amountOutstanding ?? 0), 0);
    const overdueCount = actionable.filter((r) => r.ticket.overdue).length;
    const dueTimes = actionable
      .map((r) => (r.ticket.paymentDueDate ? new Date(r.ticket.paymentDueDate).getTime() : null))
      .filter((t) => t != null && !Number.isNaN(t));
    const nextDue = dueTimes.length ? new Date(Math.min(...dueTimes)).toISOString() : null;
    return { total, overdueCount, nextDue };
  }, [actionable]);
  const summary = `ต้องดำเนินการ ${actionable.length} ดีล · คงค้างรวม ${actionable.length === 0 ? '—' : formatMoney(stats.total)}`;

  const rows = useMemo(() => {
    const base = view === 'all' ? allRows : actionable;
    return base.filter((r) => (
      (!milestoneFilter || String(r.milestone?.index ?? '') === milestoneFilter)
      && (!actionFilter || r.action?.key === actionFilter)
    ));
  }, [view, allRows, actionable, milestoneFilter, actionFilter]);

  const filtered = Boolean(milestoneFilter || actionFilter);

  function setParam(name, value) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      if (name === 'action') next.delete('stage');
      if (value) next.set(name, value); else next.delete(name);
      return next;
    });
  }

  function clearFilters() {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.delete('milestone');
      next.delete('action');
      next.delete('stage');
      return next;
    });
  }

  return (
    <PageStack>
      <PageHeader title="งานการเงิน" />

      {loading ? null : (
        <section aria-label="สรุปงานการเงิน" className="grid min-w-0 grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)] items-end gap-x-8 gap-y-4 border-b border-border pb-5 nav-drawer:grid-cols-[minmax(0,1fr)]">
          {/* The screen-reader sentence; the visible block below shows the same figures as a lead stat. */}
          <p className="sr-only">{summary}</p>
          <div className="grid min-w-0 gap-1">
            <p className="m-0 text-sm font-bold text-text-secondary">ยอดคงค้างของดีลที่ต้องดำเนินการ</p>
            <p className="m-0 text-4xl font-extrabold leading-tight tabular-nums text-text [overflow-wrap:anywhere] mobile:text-3xl">
              {actionable.length === 0 ? '—' : formatMoney(stats.total)}
            </p>
          </div>
          <dl className="m-0 grid grid-cols-3 gap-x-4 gap-y-2 mobile:grid-cols-1">
            <div className="grid min-w-0 gap-0.5">
              <dt className="text-sm text-text-muted">ดีลที่รอดำเนินการ</dt>
              <dd className="m-0 text-lg font-extrabold tabular-nums text-text">{`${actionable.length} ดีล`}</dd>
            </div>
            <div className="grid min-w-0 gap-0.5">
              <dt className="text-sm text-text-muted">ในนั้นเกินกำหนด</dt>
              <dd className={cn('m-0 text-lg font-extrabold tabular-nums', stats.overdueCount > 0 ? 'text-danger' : 'text-text')}>{`${stats.overdueCount} ดีล`}</dd>
            </div>
            <div className="grid min-w-0 gap-0.5">
              <dt className="text-sm text-text-muted">ครบกำหนดใกล้สุด</dt>
              <dd className="m-0 text-lg font-extrabold tabular-nums text-text">{stats.nextDue ? formatThaiDate(stats.nextDue) : '—'}</dd>
            </div>
          </dl>
        </section>
      )}

      <Tabs
        items={[
          { id: 'todo', label: 'ต้องดำเนินการ', badge: loading ? null : actionable.length, badgeLabel: `${actionable.length} ดีล` },
          { id: 'all', label: 'ทั้งหมด', badge: loading ? null : allRows.length, badgeLabel: `${allRows.length} ดีล` },
        ]}
        value={view}
        onChange={(v) => setParam('view', v === 'all' ? 'all' : '')}
        ariaLabel="มุมมองรายการ"
        idPrefix="fin-view"
      />

      <TabPanel id={view} idPrefix="fin-view" active>
      <FilterBar>
        <FilterField label="ขั้นการเงิน" htmlFor="fin-filter-milestone" className="basis-[200px]">
          <select id="fin-filter-milestone" value={milestoneFilter} onChange={(e) => setParam('milestone', e.target.value)}>
            <option value="">ทุกขั้น</option>
            {MONEY_TRACK.map((m) => <option key={m.key} value={String(m.index)}>{`${m.index} ${m.label}`}</option>)}
          </select>
        </FilterField>
        <FilterField label="ขั้นตอนที่ต้องทำ" htmlFor="fin-filter-action" className="basis-[220px]">
          <select id="fin-filter-action" value={actionFilter} onChange={(e) => setParam('action', e.target.value)}>
            <option value="">ทุกขั้นตอน</option>
            {ACTION_FILTERS.map((a) => <option key={a.key} value={a.key}>{a.label}</option>)}
          </select>
        </FilterField>
      </FilterBar>

      {loading ? (
        <div aria-busy="true" aria-label="กำลังโหลดงานการเงิน" className="grid">
          {[0, 1, 2, 3, 4].map((i) => (
            <div key={i} className="grid gap-2 border-b border-border px-5 py-3.5">
              <Bone className="h-4 w-2/5" />
              <Bone className="h-3 w-3/5" />
            </div>
          ))}
        </div>
      ) : loadError ? (
        <div className="grid max-w-[520px] justify-items-start gap-3 py-6">
          <p role="alert" className="m-0 flex items-start gap-2 text-sm font-bold text-danger [overflow-wrap:anywhere]">
            <Icon name="triangleAlert" size={16} className="mt-0.5 shrink-0" />
            <span>{loadError.message || 'โหลดงานการเงินไม่สำเร็จ'}</span>
          </p>
          <Button
            type="button" variant="secondary" className="whitespace-nowrap"
            onClick={() => { scopedQuery.refetch(); closedPaidQuery.refetch(); }}
          >
            ลองอีกครั้ง
          </Button>
        </div>
      ) : rows.length === 0 ? (
        <div className="grid justify-items-center gap-3">
          <EmptyState
            icon="badgeDollar"
            title={filtered ? (milestoneFilter ? 'ไม่มีดีลในขั้นนี้' : 'ไม่มีดีลที่ตรงกับตัวกรอง') : 'ไม่มีดีลที่ต้องดำเนินการตอนนี้'}
            description={filtered
              ? 'ตัวกรองที่เลือกไม่ตรงกับดีลใดในมุมมองนี้'
              : view === 'all' ? 'ยังไม่มีดีลในขอบเขตงานการเงินของคุณ' : 'ดูดีลที่เสร็จแล้วได้ที่มุมมอง "ทั้งหมด"'}
          />
          {filtered ? <Button type="button" variant="secondary" className="whitespace-nowrap" onClick={clearFilters}>ล้างตัวกรอง</Button> : null}
        </div>
      ) : (
        <div className="@container overflow-hidden rounded-md border border-border bg-surface">
          {/* Column tracks are declared ONCE here; the header and every row are subgrids of them, so
              each column (and the money figures' right edge) lines up down the whole list. */}
          <ul className="m-0 grid list-none grid-cols-[minmax(0,2.2fr)_minmax(0,1.8fr)_minmax(0,1.3fr)_minmax(7rem,auto)_minmax(7rem,auto)] gap-x-4 p-0 @max-[60rem]:grid-cols-[minmax(0,1fr)_auto]">
            <li
              aria-hidden="true"
              className="col-span-full grid grid-cols-subgrid items-center border-b border-border bg-surface-muted px-5 py-3 text-2xs font-extrabold uppercase text-text-muted @max-[60rem]:hidden"
            >
              <span>ดีล</span>
              <span>ขั้นการเงิน</span>
              <span>ขั้นตอนที่ต้องทำ</span>
              <span className="text-right">คงค้าง</span>
              <span className="text-right">ครบกำหนด</span>
            </li>
            {rows.map((row) => <DealRow key={row.ticket.id} row={row} catalog={catalog} />)}
          </ul>
        </div>
      )}
      </TabPanel>
    </PageStack>
  );
}
