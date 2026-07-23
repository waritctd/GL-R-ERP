import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatThaiDate, fulfilmentStatusLabel } from '../../utils/format.js';
import { nextFulfilmentActionCode, nextImportAction } from '../tickets/importActions.js';

/**
 * Import's landing view (role-scoped-views plan, docs/role-scoped-views.md —
 * "1. Import"). Replaces the generic EmployeeDashboard/TicketDashboard for
 * the import role: Import's job is procurement -> delivery on the deals
 * already in its worklist, not browsing the whole deal pipeline (which stays
 * gated behind canViewDealPipeline — see AppShell.jsx/permissions.js).
 *
 * Data is derived CLIENT-SIDE from the same two calls TicketDashboard already
 * makes (api.tickets.list + api.pricingRequests.queue) — no dashboard summary
 * endpoint, no backend change. Both queries are already role-scoped server
 * (mock) side (import only ever sees its own worklist slice — see
 * importListScopeIncludes / PRICING_REQUEST_VIEWER_ROLES in mockApi.js), so
 * every count/row below is already the correct audience; this component only
 * buckets and sorts what it's given.
 */

// Pricing-request statuses still "in flight" toward a price — used only for
// the ตั้งราคา conveyor-pulse COUNT (an awareness gauge across every pricing
// request touching Import's deals, not just the ones Import can act on right
// now). DRAFT never reaches here (mockApi's draft-privacy filter keeps it out
// of the queue for import); QUOTATION_ACCEPTED/QUOTATION_ISSUED/SUPERSEDED/
// CANCELLED are excluded because pricing is resolved (or dead) by then.
const PRICING_IN_FLIGHT_STATUSES = [
  'SUBMITTED', 'IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS',
  'READY_FOR_CEO_REVIEW', 'CEO_REVIEWING', 'COSTING_REVISION_REQUIRED', 'MORE_INFO_REQUIRED',
];

// fulfillmentStatus values that mean "physically in transit" — the same set
// both the "ขนส่ง" conveyor bucket and the "กำลังขนส่ง" tracker use.
const IN_TRANSIT_STATUSES = ['IR_SENT', 'SHIPPING', 'CUSTOMS_CLEARANCE'];

// The five mutually-exclusive fulfilment-stage buckets a deal can sit in
// (pricing pickup is a 6th, computed separately since it comes from the
// PricingRequest side, not fulfillmentStatus). Order matters: it is the
// conveyor's left-to-right order and the worklist's tie-break sort.
const STAGE_BUCKETS = [
  { key: 'pricing', label: 'ตั้งราคา', tone: 'amber' },
  { key: 'procurement', label: 'จัดซื้อ/IR', tone: 'blue' },
  { key: 'shipping', label: 'ขนส่ง', tone: 'blue' },
  { key: 'goodsReceived', label: 'รับเข้าคลัง', tone: 'teal' },
  { key: 'delivery', label: 'ส่งมอบ', tone: 'indigo' },
];

const FULFILMENT_ACTION_TO_BUCKET = {
  issueImportRequest: 'procurement',
  markIrSent: 'procurement',
  markShipping: 'shipping',
  markGoodsReceived: 'goodsReceived',
  recordDelivery: 'delivery',
};

/** Which conveyor bucket a worklist row belongs to (see nextImportAction). */
function bucketOfAction(action) {
  if (!action) return null;
  if (action.code === 'pickupPricingRequest') return 'pricing';
  return FULFILMENT_ACTION_TO_BUCKET[action.code] ?? null;
}

function PulseTile({ label, count, tone, active, onClick }) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        'flex min-h-[64px] min-w-[92px] flex-1 flex-col items-start gap-1 rounded-lg border px-3 py-2.5 text-left transition-colors',
        active ? 'border-primary bg-primary/10' : 'border-border bg-surface hover:bg-surface-hover',
      )}
    >
      <span
        className={cn(
          'text-2xl font-extrabold leading-none',
          tone === 'teal' ? 'text-accent' : tone === 'danger' ? 'text-danger' : 'text-text',
        )}
      >
        {count}
      </span>
      <span className="text-2xs font-bold uppercase tracking-wide text-text-muted">{label}</span>
    </button>
  );
}

function WorklistRow({ ticket, action, navigate }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border-subtle px-4 py-3 first:border-t-0 max-[720px]:flex-col max-[720px]:items-stretch">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <strong className="min-w-0 truncate text-sm font-extrabold text-text">
            {ticket.customerName || ticket.title}
          </strong>
          {ticket.overdue ? <StatusBadge tone="danger">เกินกำหนด</StatusBadge> : null}
        </div>
        <span className="block truncate text-2xs text-text-muted">
          <code>{ticket.code}</code>
          {ticket.fulfillmentStatus ? ` · ${fulfilmentStatusLabel(ticket.fulfillmentStatus).label}` : ''}
          {ticket.dueDate ? ` · กำหนด ${formatThaiDate(ticket.dueDate)}` : ''}
        </span>
      </div>
      <Button type="button" size="sm" onClick={() => navigate(action.to)} className="shrink-0">
        {action.label}
      </Button>
    </div>
  );
}

export function ImportOverview({ user, employee }) {
  const navigate = useNavigate();
  const [activeBucket, setActiveBucket] = useState(null);

  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((r) => r?.tickets ?? []),
  });
  const pricingQueueQuery = useQuery({
    queryKey: queryKeys.pricingRequestQueue({ activeOnly: true }),
    queryFn: () => api.pricingRequests.queue({ activeOnly: true }).then((r) => r?.items ?? []),
  });

  const tickets = useMemo(() => ticketsQuery.data ?? [], [ticketsQuery.data]);
  const pricingRequests = useMemo(() => pricingQueueQuery.data ?? [], [pricingQueueQuery.data]);
  const loading = ticketsQuery.isLoading || pricingQueueQuery.isLoading;

  const prByTicket = useMemo(() => {
    const map = new Map();
    pricingRequests.forEach((pr) => {
      const list = map.get(pr.ticketId) ?? [];
      list.push(pr);
      map.set(pr.ticketId, list);
    });
    return map;
  }, [pricingRequests]);

  // One row per in-scope deal with something for Import to do right now —
  // nextImportAction is the SAME helper DealFulfilmentPanel's `can.*` gates
  // derive from, so this worklist can never disagree with the panel that
  // actually performs the mutation.
  const worklistRows = useMemo(() => {
    const rows = tickets
      .map((ticket) => ({ ticket, action: nextImportAction(ticket, prByTicket.get(ticket.id) ?? []) }))
      .filter((row) => row.action);
    const stageOrder = (bucketKey) => {
      if (bucketKey === 'pricing') return 0;
      const idx = STAGE_BUCKETS.findIndex((b) => b.key === bucketKey);
      return idx < 0 ? STAGE_BUCKETS.length : idx;
    };
    return rows.sort((a, b) => {
      if (Boolean(a.ticket.overdue) !== Boolean(b.ticket.overdue)) return a.ticket.overdue ? -1 : 1;
      const stageDiff = stageOrder(bucketOfAction(a.action)) - stageOrder(bucketOfAction(b.action));
      if (stageDiff !== 0) return stageDiff;
      return new Date(a.ticket.updatedAt || 0) - new Date(b.ticket.updatedAt || 0);
    });
  }, [tickets, prByTicket]);

  const bucketCounts = useMemo(() => {
    const counts = { overdue: 0, pricing: 0, procurement: 0, shipping: 0, goodsReceived: 0, delivery: 0 };
    counts.overdue = tickets.filter((t) => t.overdue).length;
    counts.pricing = pricingRequests.filter((pr) => PRICING_IN_FLIGHT_STATUSES.includes(pr.status)).length;
    tickets.forEach((ticket) => {
      const code = nextFulfilmentActionCode(ticket);
      const bucket = FULFILMENT_ACTION_TO_BUCKET[code];
      if (bucket) counts[bucket] += 1;
    });
    return counts;
  }, [tickets, pricingRequests]);

  const inTransit = useMemo(
    () => tickets.filter((t) => IN_TRANSIT_STATUSES.includes(t.fulfillmentStatus)),
    [tickets],
  );

  const filteredRows = useMemo(() => {
    if (!activeBucket) return worklistRows;
    if (activeBucket === 'overdue') return worklistRows.filter((row) => row.ticket.overdue);
    return worklistRows.filter((row) => bucketOfAction(row.action) === activeBucket);
  }, [worklistRows, activeBucket]);

  function toggleBucket(key) {
    setActiveBucket((current) => (current === key ? null : key));
  }

  // "คิวของฉัน" — the two workspaces the worklist above feeds into: the
  // pricing-request queue (pickup happens there) and the combined
  // procurement/fulfilment page (everything past pickup happens there).
  const pricingQueueCount = pricingRequests.filter((pr) => pr.status === 'SUBMITTED').length;
  const procurementQueueCount = tickets.filter((t) => nextFulfilmentActionCode(t) != null).length;

  const greeting = `สวัสดี, คุณ${employee?.nickName || employee?.nameTh || user?.name || ''}`;

  return (
    <PageStack>
      <PageHeader title={greeting} subtitle="ภาพรวมงานนำเข้า — จัดซื้อ ขนส่ง และส่งมอบ" />

      <Panel title="สถานะงานทั้งหมด" className="!p-4">
        <div className="flex flex-wrap gap-2 max-[520px]:flex-nowrap max-[520px]:overflow-x-auto max-[520px]:pb-1">
          <PulseTile
            label="เกินกำหนด"
            count={bucketCounts.overdue}
            tone="danger"
            active={activeBucket === 'overdue'}
            onClick={() => toggleBucket('overdue')}
          />
          {STAGE_BUCKETS.map((bucket) => (
            <PulseTile
              key={bucket.key}
              label={bucket.label}
              count={bucketCounts[bucket.key]}
              tone={bucket.tone}
              active={activeBucket === bucket.key}
              onClick={() => toggleBucket(bucket.key)}
            />
          ))}
        </div>
      </Panel>

      <Panel
        title="สิ่งที่ต้องทำ"
        actions={activeBucket ? (
          <Button type="button" variant="text" onClick={() => setActiveBucket(null)}>ล้างตัวกรอง</Button>
        ) : null}
        className="!p-0 overflow-hidden"
      >
        {loading ? (
          <p className="px-4 py-6 text-sm text-text-muted">กำลังโหลด...</p>
        ) : filteredRows.length === 0 ? (
          <EmptyState
            icon="check"
            title="ไม่มีงานที่ต้องดำเนินการตอนนี้"
            description={activeBucket ? 'ลองล้างตัวกรองเพื่อดูงานอื่น' : 'งานของฝ่ายนำเข้าทั้งหมดดำเนินการครบแล้ว'}
          />
        ) : (
          <div className="flex flex-col">
            {filteredRows.map(({ ticket, action }) => (
              <WorklistRow key={ticket.id} ticket={ticket} action={action} navigate={navigate} />
            ))}
          </div>
        )}
      </Panel>

      <div className="grid grid-cols-2 gap-[14px] max-[720px]:grid-cols-1">
        <Panel title="คิวของฉัน" className="!p-0 overflow-hidden">
          <div className="flex flex-col">
            <button
              type="button"
              onClick={() => navigate('/pricing-requests')}
              className="flex items-center justify-between gap-3 border-t border-border-subtle px-4 py-3 text-left first:border-t-0 hover:bg-surface-hover"
            >
              <span className="flex items-center gap-2 text-sm font-bold text-text">
                <Icon name="clipboard" size={16} className="text-text-muted" />
                คิวใบขอราคา
              </span>
              <span className="flex items-center gap-2">
                <StatusBadge tone={pricingQueueCount > 0 ? 'warning' : 'neutral'}>{pricingQueueCount}</StatusBadge>
                <Icon name="chevronRight" size={16} className="text-text-faint" />
              </span>
            </button>
            <button
              type="button"
              onClick={() => navigate('/procurement')}
              className="flex items-center justify-between gap-3 border-t border-border-subtle px-4 py-3 text-left hover:bg-surface-hover"
            >
              <span className="flex items-center gap-2 text-sm font-bold text-text">
                <Icon name="fileText" size={16} className="text-text-muted" />
                จัดซื้อ & นำเข้า
              </span>
              <span className="flex items-center gap-2">
                <StatusBadge tone={procurementQueueCount > 0 ? 'info' : 'neutral'}>{procurementQueueCount}</StatusBadge>
                <Icon name="chevronRight" size={16} className="text-text-faint" />
              </span>
            </button>
          </div>
        </Panel>

        <Panel title="กำลังขนส่ง" className="!p-0 overflow-hidden">
          {inTransit.length === 0 ? (
            <p className="px-4 py-6 text-sm text-text-muted">ไม่มีสินค้าระหว่างขนส่งตอนนี้</p>
          ) : (
            <div className="flex flex-col">
              {inTransit.map((ticket) => {
                const status = fulfilmentStatusLabel(ticket.fulfillmentStatus);
                return (
                  <button
                    key={ticket.id}
                    type="button"
                    onClick={() => navigate(`/tickets/${ticket.id}`)}
                    className="flex items-center justify-between gap-3 border-t border-border-subtle px-4 py-3 text-left first:border-t-0 hover:bg-surface-hover"
                  >
                    <span className="min-w-0">
                      <strong className="block truncate text-sm text-text">{ticket.customerName || ticket.title}</strong>
                      <code className="block truncate text-2xs text-text-muted">{ticket.code}</code>
                    </span>
                    <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                  </button>
                );
              })}
            </div>
          )}
        </Panel>
      </div>
    </PageStack>
  );
}
