import { useCallback, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { FilterBar, PageStack, Panel } from '../../components/common/Layout.jsx';
import { SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatThaiDate } from '../../utils/format.js';
import { FactoryProgressBar } from '../importProgress/FactoryProgressBar.jsx';
import { IMPORT_STEPS } from '../importProgress/importSteps.js';

/**
 * งานนำเข้า — Import's cross-deal fulfilment worklist, REDESIGNED for per-factory
 * tracking (revision 2026-09-19, see .design/import-fulfilment/INFORMATION_ARCHITECTURE.md §11).
 *
 * The original page (2026-08-17) bucketed each DEAL into one of four deal-level
 * `fulfillment_status` transitions and performed them in place. Per-factory import
 * tracking (S12–S17, sales.factory_import_progress — the no-PO model) broke that
 * premise: a deal is no longer at ONE import status, it has N factories each at
 * their own step. On the old page a tracked deal sat mislabelled in "ออกคำขอนำเข้า"
 * (its deal-level status stays null in the no-PO flow) and then VANISHED the moment
 * the all-received rollup set GOODS_RECEIVED. Both were reported as bugs.
 *
 * The core principle is unchanged — this is "the room", not "a door": Import advances
 * work here without opening N deal pages. Only the UNIT changed, from a deal at one of
 * four statuses to a FACTORY SHIPMENT at one of six steps. Each shipment renders as the
 * same {@link FactoryProgressBar} used at the bottom of the deal page, so the two
 * surfaces read identically; import/ceo advance a step (and generate the order email)
 * in place. The old four deal-level transitions are gone from this page — they are the
 * abandoned pre-per-factory mechanism, hidden on the deal page too.
 *
 * A deal stays here while ANY factory is < RECEIVED. When every factory is received the
 * deal rolls up to GOODS_RECEIVED (server-side) and drops into a collapsed
 * "รับครบแล้ว · รอส่งมอบ" group at the foot rather than disappearing — so the handoff to
 * Sales's delivery step reads as a handoff, never as a lost row.
 *
 * Delivery itself is still NOT here (owner ruling): it is Sales's, on the deal's
 * จัดซื้อ-ส่งมอบ tab. See the footer note.
 */

// The rollup chip counts factories that reached the warehouse (S17 RECEIVED) — the
// step that actually completes the deal and hands it to Sales.
function receivedCount(rows) {
  return rows.filter((r) => r.importStep === 'RECEIVED').length;
}

export function ImportFulfilmentPage({ user, showToast }) {
  const queryClient = useQueryClient();
  // Nav-gated to import/ceo; anyone here may advance. A read-only viewer (should one
  // ever reach it) still gets the bars, just without the controls.
  const editable = user?.role === 'import' || user?.role === 'ceo';
  const [stepFilter, setStepFilter] = useState('ALL');
  const [search, setSearch] = useState('');
  const [orderEmail, setOrderEmail] = useState(null); // { factoryName, subject, body } | null

  // The per-factory rows across every deal Import owns — the SAME cross-deal source
  // (importProgress.listAll) the dashboard's กำลังขนส่ง awareness reads, so the two
  // never diverge. Rows are already role-scoped server-side.
  const rowsQuery = useQuery({
    queryKey: queryKeys.importProgressAll(),
    queryFn: () => api.importProgress.listAll().then((r) => r?.items ?? []),
  });
  // Joined only for the deal facts a progress row does not carry — customer, project,
  // due date, overdue. Same call + key the dashboard/ImportOverview use, one cache entry.
  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((r) => r?.tickets ?? []),
  });

  // The SAME invalidation set DealFulfilmentPanel/FactoryImportProgressPanel fire, plus
  // the cross-deal list this page reads: a deal advanced here must not render its old
  // step on /tickets/:id (or vice versa) until a reload, and the all-received rollup
  // moves the deal between this page's active/done groups only once the list refetches.
  const invalidateAfterAdvance = useCallback((ticketId) => {
    queryClient.invalidateQueries({ queryKey: queryKeys.importProgressAll() });
    queryClient.invalidateQueries({ queryKey: queryKeys.importProgressForTicket(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
    queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
    queryClient.invalidateQueries({ queryKey: queryKeys.dashboardSummary() });
    queryClient.invalidateQueries({ queryKey: queryKeys.notifications() });
  }, [queryClient]);

  const advance = useMutation({
    mutationFn: ({ rowId, targetStep }) => api.importProgress.advanceStep(rowId, { targetStep }),
    onSuccess: (_data, { ticketId, completesDeal }) => {
      invalidateAfterAdvance(ticketId);
      // Name the handoff when this advance is the one that finishes the deal — the row
      // is about to leave the active list, and "done, and it moved on" beats a silent jump.
      showToast?.('success', completesDeal
        ? 'รับครบทุกโรงงาน — ส่งต่อฝ่ายขายเพื่อส่งมอบ'
        : 'อัปเดตสถานะรายโรงงานแล้ว');
    },
    onError: (e) => showToast?.('error', e?.message ?? 'อัปเดตไม่สำเร็จ'),
  });

  const generateEmail = useMutation({
    mutationFn: (row) => api.importProgress.generateOrderEmail(row.pricingRequestId, { factoryName: row.factoryName }),
    onSuccess: (res) => setOrderEmail(res?.template ?? null),
    onError: (e) => showToast?.('error', e?.message ?? 'สร้างอีเมลไม่สำเร็จ'),
  });

  const copyBody = async () => {
    try {
      await navigator.clipboard.writeText(orderEmail?.body ?? '');
      showToast?.('success', 'คัดลอกข้อความอีเมลแล้ว');
    } catch {
      showToast?.('error', 'คัดลอกไม่สำเร็จ — เลือกข้อความแล้วคัดลอกเอง');
    }
  };

  const ticketsById = useMemo(() => {
    const map = new Map();
    (ticketsQuery.data ?? []).forEach((t) => map.set(t.id, t));
    return map;
  }, [ticketsQuery.data]);

  // Group the flat rows into one entry per deal, joining the deal facts.
  const deals = useMemo(() => {
    const byTicket = new Map();
    (rowsQuery.data ?? []).forEach((row) => {
      if (!byTicket.has(row.ticketId)) byTicket.set(row.ticketId, []);
      byTicket.get(row.ticketId).push(row);
    });
    return [...byTicket.entries()].map(([ticketId, factoryRows]) => {
      const ticket = ticketsById.get(ticketId);
      const rows = [...factoryRows].sort((a, b) => a.factoryName.localeCompare(b.factoryName, 'th'));
      const received = receivedCount(rows);
      return {
        ticketId,
        ticketCode: factoryRows[0].ticketCode,
        customerName: ticket?.customerName ?? null,
        projectName: ticket?.projectName ?? null,
        title: ticket?.title ?? null,
        dueDate: ticket?.dueDate ?? null,
        overdue: Boolean(ticket?.overdue),
        rows,
        received,
        total: rows.length,
        allReceived: rows.length > 0 && received === rows.length,
      };
    });
  }, [rowsQuery.data, ticketsById]);

  const activeDeals = useMemo(() => deals.filter((d) => !d.allReceived), [deals]);
  const doneDeals = useMemo(() => deals.filter((d) => d.allReceived), [deals]);

  // Chip counts are a readout of SHIPMENTS (factory rows) per step across active deals —
  // "5 โรงงานกำลังเดินทาง" answered without a click. ALL is the active shipment total.
  const stepCounts = useMemo(() => {
    const counts = { ALL: 0 };
    IMPORT_STEPS.forEach((s) => { counts[s.code] = 0; });
    activeDeals.forEach((deal) => deal.rows.forEach((row) => {
      counts.ALL += 1;
      if (counts[row.importStep] != null) counts[row.importStep] += 1;
    }));
    return counts;
  }, [activeDeals]);

  const visibleActive = useMemo(() => {
    const term = search.trim().toLowerCase();
    return activeDeals
      .filter((d) => !term || [d.customerName, d.ticketCode, d.projectName, d.title]
        .some((f) => (f ?? '').toLowerCase().includes(term)))
      .filter((d) => stepFilter === 'ALL' || d.rows.some((r) => r.importStep === stepFilter))
      .sort((a, b) => {
        if (a.overdue !== b.overdue) return a.overdue ? -1 : 1;
        return (a.customerName ?? a.ticketCode ?? '').localeCompare(b.customerName ?? b.ticketCode ?? '', 'th');
      });
  }, [activeDeals, stepFilter, search]);

  const isLoading = rowsQuery.isLoading || ticketsQuery.isLoading;
  const nothingAtAll = !isLoading && deals.length === 0;

  const filterOptions = [{ key: 'ALL', label: 'ทั้งหมด' },
    ...IMPORT_STEPS.map((s) => ({ key: s.code, label: s.label }))];

  return (
    <PageStack>
      <PageHeader
        title="งานนำเข้า"
        subtitle="ติดตามและเลื่อนสถานะนำเข้ารายโรงงาน — สั่งซื้อ → ขนส่งรับของ → กำลังเดินทาง → ถึงไทย → ถึงโกดัง"
        actions={(
          <Button
            type="button"
            variant="icon"
            onClick={() => {
              queryClient.invalidateQueries({ queryKey: queryKeys.importProgressAll() });
              queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
            }}
            title="รีเฟรช"
            aria-label="รีเฟรช"
          >
            <Icon name="refresh" />
          </Button>
        )}
      />

      <FilterBar>
        <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">ขั้นตอน</span>
        {filterOptions.map((option) => {
          const active = stepFilter === option.key;
          return (
            <button
              key={option.key}
              type="button"
              aria-pressed={active}
              className={cn(
                'inline-flex min-h-8 items-center gap-1.5 rounded-full border px-3 text-xs font-bold',
                active ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface hover:bg-surface-hover',
              )}
              onClick={() => setStepFilter(option.key)}
              data-testid={`step-chip-${option.key}`}
            >
              {option.label}
              <span className="tabular-nums opacity-70">{stepCounts[option.key] ?? 0}</span>
            </button>
          );
        })}
        <label className="ml-auto flex items-center gap-2 mobile:ml-0 mobile:w-full">
          <span className="sr-only">ค้นหาดีล</span>
          <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="ค้นหาลูกค้า / เลขที่ดีล"
            className="min-h-9 w-52 rounded-md border border-border-input px-3 text-sm mobile:w-full"
            data-testid="fulfilment-search"
          />
        </label>
      </FilterBar>

      {isLoading ? (
        <Panel aria-busy="true" aria-label="กำลังโหลดงานนำเข้า">
          <SkeletonText lines={6} />
        </Panel>
      ) : nothingAtAll ? (
        <EmptyState
          icon="check"
          title="ยังไม่มีงานนำเข้ารายโรงงาน"
          description="เมื่อฝ่ายนำเข้าเปิดการติดตามนำเข้าของดีล แต่ละโรงงานจะขึ้นที่นี่ให้เลื่อนสถานะ"
        />
      ) : visibleActive.length === 0 && doneDeals.length === 0 ? (
        <EmptyState
          icon="check"
          title="ไม่มีงานนำเข้าในขั้นตอนนี้"
          description={'ลองล้างคำค้นหรือเลือกขั้นตอน "ทั้งหมด"'}
        />
      ) : (
        <div className="flex flex-col gap-3">
          {visibleActive.map((deal) => (
            <section
              key={deal.ticketId}
              className="rounded-xl border border-border bg-surface-subtle p-3"
              data-testid="fulfilment-deal"
              data-ticket-id={deal.ticketId}
            >
              <div className="mb-2 flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <strong className="min-w-0 truncate text-sm font-extrabold text-text">
                      {deal.customerName || deal.title || deal.ticketCode}
                    </strong>
                    {deal.overdue ? <StatusBadge tone="danger">เกินกำหนด</StatusBadge> : null}
                  </div>
                  <span className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-2xs text-text-muted">
                    <Link to={`/tickets/${deal.ticketId}`} className="text-info underline">
                      <code>{deal.ticketCode}</code>
                    </Link>
                    {deal.projectName ? <span className="truncate">{deal.projectName}</span> : null}
                    {deal.dueDate ? <span>กำหนด {formatThaiDate(deal.dueDate)}</span> : null}
                  </span>
                </div>
                <span className="shrink-0 rounded-full bg-info-bg px-2.5 py-0.5 text-2xs font-bold text-info">
                  ถึงโกดัง {deal.received}/{deal.total} โรงงาน
                </span>
              </div>

              <div className="flex flex-col gap-2">
                {deal.rows.map((row) => {
                  const dim = stepFilter !== 'ALL' && row.importStep !== stepFilter;
                  return (
                    <div key={row.id} className={cn('transition-opacity', dim && 'opacity-40')}>
                      <FactoryProgressBar
                        row={row}
                        editable={editable}
                        advancing={advance.isPending}
                        onAdvance={(r, targetStep) => advance.mutate({
                          rowId: r.id,
                          targetStep,
                          ticketId: deal.ticketId,
                          // The completing move: every OTHER factory is already received.
                          completesDeal: targetStep === 'RECEIVED'
                            && deal.rows.every((x) => x.id === r.id || x.importStep === 'RECEIVED'),
                        })}
                        onGenerateEmail={editable ? (r) => generateEmail.mutate(r) : undefined}
                      />
                    </div>
                  );
                })}
              </div>
            </section>
          ))}

          {visibleActive.length === 0 ? (
            <p className="rounded-lg border border-dashed border-border px-4 py-6 text-center text-xs text-text-muted">
              ไม่มีงานนำเข้าที่ตรงกับตัวกรองนี้ — ดูดีลที่รับครบแล้วด้านล่าง
            </p>
          ) : null}

          {doneDeals.length > 0 ? (
            <details className="rounded-xl border border-border bg-surface" data-testid="fulfilment-done">
              <summary className="cursor-pointer list-none px-4 py-3 text-xs font-bold text-text-muted marker:hidden">
                <span className="inline-flex items-center gap-2">
                  <Icon name="check" size={14} className="text-success" />
                  รับครบแล้ว · รอฝ่ายขายส่งมอบ ({doneDeals.length})
                </span>
              </summary>
              <div className="border-t border-border-subtle">
                {doneDeals.map((deal) => (
                  <div
                    key={deal.ticketId}
                    className="flex flex-wrap items-center justify-between gap-2 border-t border-border-subtle px-4 py-2.5 first:border-t-0"
                  >
                    <span className="flex flex-wrap items-center gap-x-2 text-xs">
                      <strong className="text-text">{deal.customerName || deal.title || deal.ticketCode}</strong>
                      <Link to={`/tickets/${deal.ticketId}`} className="text-info underline">
                        <code>{deal.ticketCode}</code>
                      </Link>
                    </span>
                    <span className="text-2xs font-bold text-success-dark">
                      ครบทุกโรงงาน ({deal.total}) → ส่งต่อส่งมอบ
                    </span>
                  </div>
                ))}
              </div>
            </details>
          ) : null}
        </div>
      )}

      <p className="text-2xs text-text-muted">
        การส่งมอบสินค้าให้ลูกค้าไม่ได้อยู่ในหน้านี้ — บันทึกที่แท็บ จัดซื้อ-ส่งมอบ ของดีลนั้น
      </p>

      {orderEmail ? (
        <Modal
          title="อีเมลสั่งซื้อ (ร่าง)"
          subtitle={orderEmail.factoryName}
          onClose={() => setOrderEmail(null)}
          testId="order-email-modal"
          footer={(
            <>
              <Button type="button" variant="secondary" onClick={() => setOrderEmail(null)}>ปิด</Button>
              <Button type="button" variant="primary" onClick={copyBody}>คัดลอกข้อความ</Button>
            </>
          )}
        >
          <div className="grid gap-2">
            <ol className="m-0 list-decimal pl-5 text-xs text-text-muted">
              <li>คัดลอกข้อความด้านล่าง</li>
              <li>วางในอีเมล/ช่องทางที่ใช้ส่งโรงงานเอง แล้วส่ง</li>
              <li>กลับมากดเลื่อนสถานะรายโรงงาน</li>
            </ol>
            <div className="text-xs font-bold text-text-muted">หัวข้อ: {orderEmail.subject}</div>
            <textarea
              className="form-input min-h-64 font-mono text-xs"
              readOnly
              value={orderEmail.body}
            />
          </div>
        </Modal>
      ) : null}
    </PageStack>
  );
}
