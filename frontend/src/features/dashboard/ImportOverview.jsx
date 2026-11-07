import { useMemo } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate, fulfilmentStatusLabel, greetingName, pricingRequestStatusLabel } from '../../utils/format.js';
import { pricingRequestRecipientLabel } from '../pricingRequests/pricingRequestMeta.js';
import { nextFulfilmentActionCode, nextImportAction } from '../tickets/importActions.js';
import { Figure, WorklistGroup, WorklistTotal, failed } from './dashboardParts.jsx';

/**
 * Import's landing: a count band, then one worklist (new requests to claim, my requests with the
 * factory, fulfilment steps), with shipments and the requests waiting on the CEO on the right.
 *
 * Data comes from the two calls this page always made (api.tickets.list + api.pricingRequests.queue),
 * both already role-scoped server side; no new endpoint. "Mine" follows the queue page's งานของฉัน tab:
 * assigned to me and still in an import-side status (the retired COSTING_IN_PROGRESS included, for the
 * deploy-before-migration window), so the band and /pricing-requests agree.
 */

const MY_WORK_STATUSES = ['IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS'];
const CEO_STATUSES = ['READY_FOR_CEO_REVIEW', 'CEO_REVIEWING'];

// fulfillmentStatus values that mean "physically in transit".
//
// CUSTOMS_CLEARANCE used to sit third here and was removed as dead (issue #749): nothing has ever
// written it into sales.ticket.fulfillment_status. Do not re-add it for symmetry with the factory PO
// statuses; those describe the factory PO's own sequence, not this column.
const IN_TRANSIT_STATUSES = ['IR_SENT', 'SHIPPING'];

// Fulfilment steps the page counts, in conveyor order (also the worklist tie-break). Delivery
// (recordDelivery) is Sales' prompt now (owner ruling 2026-08-17): it has no entry here and no row.
const FULFILMENT_ACTION_TO_BUCKET = {
  issueImportRequest: 'procurement',
  markIrSent: 'procurement',
  markShipping: 'shipping',
  markGoodsReceived: 'goodsReceived',
};
const BUCKET_ORDER = ['procurement', 'shipping', 'goodsReceived'];

const byOldest = (a, b) => String(a.submittedAt ?? '').localeCompare(String(b.submittedAt ?? '')) || a.id - b.id;
const pcrName = (pr) => pr.customerName || pr.ticketCode || pr.requestCode;
const count = (n, unit) => `${n} ${unit}`;

export function ImportOverview({ user, employee }) {
  const navigate = useNavigate();
  const onOpen = (to) => navigate(to);

  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((r) => r?.tickets ?? []),
  });
  const pricingQueueQuery = useQuery({
    queryKey: queryKeys.pricingRequestQueue({ activeOnly: true }),
    queryFn: () => api.pricingRequests.queue({ activeOnly: true }).then((r) => r?.items ?? []),
  });
  const ticketsReady = ticketsQuery.data !== undefined;
  const queueReady = pricingQueueQuery.data !== undefined;
  const tickets = useMemo(() => ticketsQuery.data ?? [], [ticketsQuery.data]);
  const pricingRequests = useMemo(() => pricingQueueQuery.data ?? [], [pricingQueueQuery.data]);

  // W3-02: shipments are PER FACTORY (one stored ใบขอซื้อ row each), so the ขนส่ง count and the
  // กำลังขนส่ง list read the rows exactly as /fulfilment does — same candidate filter, same query key.
  const candidateIds = useMemo(() => tickets
    .filter((t) => t.fulfillmentStatus != null
      && t.fulfillmentStatus !== 'FROM_STOCK'
      && t.fulfillmentStatus !== 'FULLY_DELIVERED')
    .map((t) => t.id), [tickets]);
  const rowsQueries = useQueries({
    queries: candidateIds.map((ticketId) => ({
      queryKey: queryKeys.storedImportRequests(ticketId),
      queryFn: () => api.storedImportRequests.listForTicket(ticketId).then((r) => r?.importRequests ?? []),
    })),
  });
  const inTransitShipments = useMemo(() => {
    const byTicket = new Map();
    candidateIds.forEach((id, i) => {
      const n = (rowsQueries[i]?.data ?? [])
        .filter((r) => r.status === 'ISSUED' && r.importStep === 'IN_TRANSIT').length;
      if (n > 0) byTicket.set(id, n);
    });
    return byTicket;
  }, [candidateIds, rowsQueries]);

  const pickup = useMemo(() => pricingRequests.filter((pr) => pr.status === 'SUBMITTED').sort(byOldest), [pricingRequests]);
  const mine = useMemo(() => pricingRequests
    .filter((pr) => MY_WORK_STATUSES.includes(pr.status) && pr.assignedImportId != null && Number(pr.assignedImportId) === Number(user?.id))
    .sort(byOldest), [pricingRequests, user?.id]);
  const withCeo = useMemo(() => pricingRequests.filter((pr) => CEO_STATUSES.includes(pr.status)), [pricingRequests]);

  // Fulfilment rows only: nextImportAction gets no pricing requests, so its pick-up branch never fires
  // (those requests are the first two groups). Same helper DealFulfilmentPanel's `can.*` derive from.
  const importRows = useMemo(() => tickets
    .map((ticket) => ({ ticket, action: nextImportAction(ticket, []) }))
    .filter((row) => row.action)
    .sort((a, b) => {
      if (Boolean(a.ticket.overdue) !== Boolean(b.ticket.overdue)) return a.ticket.overdue ? -1 : 1;
      const stage = (row) => BUCKET_ORDER.indexOf(FULFILMENT_ACTION_TO_BUCKET[row.action.code]);
      return stage(a) - stage(b) || new Date(a.ticket.updatedAt || 0) - new Date(b.ticket.updatedAt || 0);
    }), [tickets]);

  const fulfilment = useMemo(() => {
    const counts = { procurement: 0, shipping: 0, goodsReceived: 0 };
    tickets.forEach((ticket) => {
      const bucket = FULFILMENT_ACTION_TO_BUCKET[nextFulfilmentActionCode(ticket)];
      if (bucket) counts[bucket] += 1;
    });
    inTransitShipments.forEach((n) => { counts.shipping += n; });
    return counts;
  }, [tickets, inTransitShipments]);

  const inTransit = useMemo(
    () => tickets.filter((t) => IN_TRANSIT_STATUSES.includes(t.fulfillmentStatus) || inTransitShipments.has(t.id)),
    [tickets, inTransitShipments],
  );

  const queueFailed = failed(pricingQueueQuery);
  const groups = [
    {
      key: 'pickup', label: 'รับเรื่องใหม่', dot: 'bg-warning', to: '/pricing-requests',
      list: queueReady ? pickup : null, isFailed: queueFailed,
      toRow: (pr) => ({
        testId: `worklist-row-pickup-${pr.id}`,
        entity: pcrName(pr),
        meta: [pr.requestCode, pr.ticketCode, pricingRequestRecipientLabel(pr.recipientType)].filter(Boolean).join(' · '),
        ctaLabel: 'รับเรื่อง', ctaVariant: 'primary', to: `/pricing-requests/${pr.id}`,
      }),
    },
    {
      key: 'mine', label: 'ขอราคากับโรงงาน', dot: 'bg-primary', to: '/pricing-requests',
      list: queueReady ? mine : null, isFailed: queueFailed,
      toRow: (pr) => ({
        testId: `worklist-row-mine-${pr.id}`,
        entity: pcrName(pr),
        meta: `${pr.requestCode} · ${pricingRequestStatusLabel(pr.status).label}`,
        ctaLabel: 'เปิดคำขอ', to: `/pricing-requests/${pr.id}`,
      }),
    },
    {
      key: 'import', label: 'นำเข้า', dot: 'bg-accent', to: '/fulfilment',
      list: ticketsReady ? importRows : null, isFailed: failed(ticketsQuery),
      toRow: ({ ticket, action }) => ({
        testId: `worklist-row-import-${ticket.id}`,
        entity: ticket.customerName || ticket.title,
        meta: `${ticket.code}${ticket.fulfillmentStatus ? ` · ${fulfilmentStatusLabel(ticket.fulfillmentStatus).label}` : ''}${ticket.dueDate ? ` · กำหนด ${formatThaiDate(ticket.dueDate)}` : ''}`,
        ctaLabel: action.label, to: action.to,
      }),
    },
  ].map((g) => ({ ...g, n: g.list?.length ?? 0 }));
  const settled = groups.every((g) => g.list !== null || g.isFailed);
  const total = groups.reduce((sum, g) => sum + g.n, 0);
  const allEmpty = groups.every((g) => g.n === 0 && !g.isFailed);

  const fulfilmentTotal = fulfilment.procurement + fulfilment.shipping + fulfilment.goodsReceived;
  const mineReviewing = mine.filter((pr) => pr.status === 'IMPORT_REVIEWING').length;
  const pricingFigure = (n) => (queueReady ? count(n, 'คำขอ') : '—');

  const today = formatThaiDate(new Date().toISOString());
  const greeting = `สวัสดี, ${greetingName(employee?.nickName || employee?.nameTh || user?.name)}`;

  return (
    <PageStack>
      <PageHeader title="ภาพรวมงานนำเข้า" subtitle={`${greeting} · ${today}`} />

      <section className="rounded-md border border-border bg-surface p-5 mobile:p-3.5" aria-label="งานนำเข้าตอนนี้">
        <dl className="m-0 grid grid-cols-[1.5fr_1fr_1fr_1fr] gap-x-6 nav-drawer:grid-cols-2 nav-drawer:gap-y-4 mobile:grid-cols-1 mobile:divide-y mobile:divide-border mobile:[&>div]:py-2.5">
          <Figure lead label="รอรับเรื่อง" value={pricingFigure(pickup.length)} caption="ยังไม่มีใครรับ" />
          <Figure
            label="กำลังขอราคา (ของฉัน)"
            value={pricingFigure(mine.length)}
            caption={`รับเรื่อง ${mineReviewing} · เจรจากับโรงงาน ${mine.length - mineReviewing}`}
          />
          <Figure label="รอ CEO ตั้งราคา" value={pricingFigure(withCeo.length)} caption="ส่งต่อแล้ว ไม่ต้องทำอะไร" />
          <Figure
            label="นำเข้า / ขนส่ง"
            value={ticketsReady ? count(fulfilmentTotal, 'ดีล') : '—'}
            caption={`ออกใบขอซื้อ ${fulfilment.procurement} · ขนส่ง ${fulfilment.shipping} · ถึงคลัง ${fulfilment.goodsReceived}`}
          />
        </dl>
      </section>

      <div className="grid items-start gap-[18px] grid-cols-[1.6fr_1fr] nav-drawer:grid-cols-1">
        <Panel flush title="สิ่งที่ต้องทำ" actions={settled ? <WorklistTotal total={total} /> : null}>
          {settled && allEmpty ? (
            <div className="flex items-center gap-3 p-5 text-text-muted">
              <Icon name="check" size={18} />
              <span className="text-sm font-bold">ไม่มีงานที่ต้องดำเนินการตอนนี้</span>
            </div>
          ) : groups.map((g) => (
            <WorklistGroup
              key={g.key}
              groupKey={g.key}
              label={g.label}
              dot={g.dot}
              count={g.n}
              failed={g.isFailed}
              to={g.to}
              onOpen={onOpen}
              summary={count(g.n, 'รายการ')}
              rows={g.list?.map(g.toRow) ?? null}
            />
          ))}
        </Panel>

        <div className="grid gap-[18px]">
          {ticketsReady ? (
            <Panel
              flush
              data-testid="in-transit-panel"
              title="กำลังขนส่ง"
              actions={<span className="text-xs font-semibold text-text-muted">{inTransit.length}</span>}
            >
              {inTransit.length === 0 ? (
                <p className="m-0 flex items-center gap-2 px-5 py-3 text-sm text-text-muted mobile:px-3.5">
                  <Icon name="truck" size={16} />
                  ไม่มีสินค้าระหว่างขนส่งตอนนี้
                </p>
              ) : inTransit.map((ticket) => {
                const status = fulfilmentStatusLabel(ticket.fulfillmentStatus);
                return (
                  <button
                    key={ticket.id}
                    type="button"
                    onClick={() => navigate(`/import/deals/${ticket.id}`)}
                    className="flex w-full items-center justify-between gap-3 border-0 border-t border-border-subtle bg-transparent px-5 py-3 text-left text-text first:border-t-0 hover:bg-surface-hover mobile:min-h-[44px] mobile:px-3.5"
                  >
                    <span className="min-w-0">
                      <strong className="block truncate text-sm text-text">{ticket.customerName || ticket.title}</strong>
                      <code className="block truncate text-2xs text-text-muted">{ticket.code}</code>
                    </span>
                    <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                  </button>
                );
              })}
            </Panel>
          ) : null}

          {queueReady && withCeo.length > 0 ? (
            <Panel
              flush
              data-testid="ceo-waiting-panel"
              title="รอ CEO ตั้งราคา"
              actions={<span className="text-xs font-semibold text-text-muted">{withCeo.length}</span>}
            >
              {withCeo.map((pr) => (
                <div key={pr.id} data-testid={`ceo-waiting-row-${pr.id}`} className="flex items-center justify-between gap-3 border-t border-border-subtle px-5 py-2 first:border-t-0 mobile:px-3.5">
                  <div className="min-w-0">
                    <strong className="block truncate text-sm font-bold text-text">{pcrName(pr)}</strong>
                    <span className="block truncate text-xs text-text-muted">{[pr.requestCode, pr.ticketCode].filter(Boolean).join(' · ')}</span>
                  </div>
                  <StatusBadge tone="info">รอ CEO</StatusBadge>
                </div>
              ))}
            </Panel>
          ) : null}
        </div>
      </div>
    </PageStack>
  );
}
