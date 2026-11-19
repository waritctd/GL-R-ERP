/* Tokens only. "ออเดอร์ในโครงการนี้" (phone design M05): the project's orders with value and what is still owed, the
 * project total, and the สั่งเพิ่ม entry. Also the ใบเสนอราคา tab's per-order quotation lists (M06).
 */
import { useQueries } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  dealLifecycleLabel, dealStageLabel, formatMoney, formatThaiDate, fulfilmentStatusLabel, quotationStatusLabel,
} from '../../utils/format.js';
import { dealQuotationStatusLabel } from '../quotations/quotationMeta.js';
import { findStage, useStageCatalog } from './stageCatalog.js';

const ROW = 'flex min-h-14 items-start gap-3 px-4 py-2.5 text-text no-underline';

function stageText(catalog, code) {
  const no = findStage(catalog, code)?.no;
  const label = dealStageLabel(code).label;
  return no != null ? `${no}. ${label}` : label;
}

function MoneyCell({ value, owed }) {
  if (value == null) return null;
  return (
    <span className="shrink-0 text-right text-[0.9375rem] font-bold tabular-nums">
      {formatMoney(value)}
      <small className="block text-xs font-normal text-text-muted">มูลค่าก่อน VAT</small>
      <small className="block text-xs font-normal text-text-muted">ค้างรับ (รวม VAT) {formatMoney(owed)}</small>
    </span>
  );
}

function OrderRow({ order, current }) {
  const { catalog } = useStageCatalog();
  const lifecycle = order.lifecycle && order.lifecycle !== 'ACTIVE' ? dealLifecycleLabel(order.lifecycle) : null;
  const delivery = order.fulfillmentStatus ? fulfilmentStatusLabel(order.fulfillmentStatus).label : 'ยังไม่เริ่มส่งของ';
  const body = (
    <>
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <b className="text-base">ออเดอร์ {order.orderNo}</b>
        <small className="text-xs text-text-muted">
          <code className="tabular-nums">{order.displayCode}</code>
          {order.orderNo === 1 ? ' · ออเดอร์แรก' : ' · ออเดอร์เพิ่ม'}
        </small>
        <span className="my-1 flex flex-wrap gap-1.5">
          <StatusBadge tone="info">{stageText(catalog, order.salesStage)}</StatusBadge>
          {lifecycle ? <StatusBadge tone={lifecycle.tone}>{lifecycle.label}</StatusBadge> : null}
        </span>
        <small className="text-xs text-text-muted">{delivery}</small>
      </span>
      <MoneyCell value={order.valueExVat} owed={order.outstanding} />
    </>
  );
  return current ? (
    <li aria-current="page" className={ROW} data-testid={`project-order-${order.orderNo}`}>{body}</li>
  ) : (
    <li>
      <Link to={`/tickets/${order.ticketId}?tab=overview`} className={ROW} data-testid={`project-order-${order.orderNo}`}>{body}</Link>
    </li>
  );
}

/**
 * `ordersQuery`: useProjectOrders' result. `canAddOn` + `onAddOn`: whether the viewer may open one, and the opener.
 * Shown when the project has more than one order or the viewer may open an add-on (its error and skeleton show for
 * every viewer who can read the project); with a single order only the button shows.
 */
export function ProjectOrdersCard({ ordersQuery, currentId, canAddOn, onAddOn }) {
  const orders = ordersQuery.data?.orders ?? [];
  const multiple = orders.length > 1;
  const addOn = canAddOn ? (
    <Button type="button" variant="secondary" className="w-full" data-testid="project-orders-add-on" onClick={onAddOn}>
      <Icon name="plus" size={16} />
      สั่งเพิ่ม (ออเดอร์ใหม่)
    </Button>
  ) : null;
  if (ordersQuery.isError) {
    return (
      <section className="flex flex-col gap-2" data-testid="project-orders-error">
        <p role="alert" className="m-0 text-sm text-danger">โหลดรายการออเดอร์ไม่สำเร็จ</p>
        <Button type="button" variant="secondary" className="w-full sm:w-auto" onClick={() => ordersQuery.refetch()}>ลองใหม่</Button>
        {addOn}
      </section>
    );
  }
  if (ordersQuery.isLoading) {
    return <div className="h-40 animate-pulse rounded-md bg-surface-subtle" data-testid="project-orders-skeleton" aria-busy="true" />;
  }
  if (!multiple) return addOn ? <div data-testid="project-orders-single">{addOn}</div> : null;
  const totals = ordersQuery.data?.totals;
  return (
    <section className="flex flex-col gap-2" data-testid="project-orders-card">
      <h2 className="m-0 flex flex-wrap items-baseline justify-between gap-x-2 px-1 text-[0.8125rem] font-bold text-text-secondary">
        ออเดอร์ในโครงการนี้
        <span className="font-normal text-text-muted">แยกใบเสนอราคา เงิน และนำเข้า</span>
      </h2>
      <ul className="m-0 list-none divide-y divide-border-subtle overflow-hidden rounded-md border border-border bg-surface p-0">
        {orders.map((order) => <OrderRow key={order.ticketId} order={order} current={order.ticketId === Number(currentId)} />)}
        {totals?.valueExVat != null ? (
          <li className="flex min-h-14 items-center gap-3 bg-surface-subtle px-4 py-2.5" data-testid="project-orders-total">
            <span className="flex min-w-0 flex-1 flex-col">
              <b className="text-base">รวมทั้งโครงการ</b>
            </span>
            <MoneyCell value={totals.valueExVat} owed={totals.outstanding} />
          </li>
        ) : null}
      </ul>
      {addOn}
    </section>
  );
}

/** ใบเสนอราคา tab (M06) on a multi-order project: each order's quotations under an "ออเดอร์ n <code>" heading, once.
 * The current order renders `currentContent` (the page's own quotation section, deal-direct and legacy); the others
 * list deal-direct quotations plus their ticket-native (legacy) ones. */
export function ProjectQuotationsByOrder({ orders, currentId, currentContent }) {
  const results = useQueries({
    queries: orders.flatMap((order) => (order.ticketId === currentId ? [] : [
      {
        queryKey: queryKeys.dealQuotationsByTicket(order.ticketId),
        queryFn: () => api.dealQuotations.listForTicket(order.ticketId).then((r) => r.items ?? []),
      },
      {
        queryKey: queryKeys.ticketDetail(order.ticketId),
        queryFn: () => api.tickets.get(order.ticketId).then((r) => r.ticket),
      },
    ])),
  });
  return (
    <div className="flex flex-col gap-4" data-testid="project-quotations-by-order">
      {orders.map((order, i) => {
        const heading = (
          <h2 className="m-0 flex items-baseline justify-between gap-2 px-1 text-[0.8125rem] font-bold text-text-secondary">
            ออเดอร์ {order.orderNo}
            <span className="font-normal text-text-muted"><code className="tabular-nums">{order.displayCode}</code></span>
          </h2>
        );
        if (order.ticketId === currentId) {
          return (
            <section key={order.ticketId} className="flex flex-col gap-2" data-testid={`order-quotations-${order.orderNo}`}>
              {heading}
              {currentContent}
            </section>
          );
        }
        const at = orders.slice(0, i).filter((o) => o.ticketId !== currentId).length * 2;
        const [direct, detail] = [results[at], results[at + 1]];
        const loading = direct?.isLoading || detail?.isLoading;
        const failed = direct?.isError || detail?.isError;
        const seen = new Set((direct?.data ?? []).map((q) => q.number));
        const rows = [
          ...(direct?.data ?? []).map((q) => ({ ...q, href: `/quotations/${q.id}` })),
          ...(detail?.data?.quotations ?? []).filter((q) => !seen.has(q.number)).map((q) => ({ ...q, href: null })),
        ];
        return (
          <section key={order.ticketId} className="flex flex-col gap-2" data-testid={`order-quotations-${order.orderNo}`}>
            {heading}
            {loading ? <div className="h-16 animate-pulse rounded-md bg-surface-subtle" /> : null}
            {failed ? <p role="alert" className="m-0 px-1 text-sm text-danger">โหลดใบเสนอราคาไม่สำเร็จ</p> : null}
            {!loading && !failed && rows.length === 0 ? (
              <p className="m-0 px-1 text-sm text-text-muted">ยังไม่มีใบเสนอราคา</p>
            ) : null}
            {rows.length > 0 ? (
              <ul className="m-0 list-none divide-y divide-border-subtle overflow-hidden rounded-md border border-border bg-surface p-0">
                {rows.map((q) => {
                  const status = q.href ? dealQuotationStatusLabel(q.docStatus) : quotationStatusLabel(q.docStatus);
                  const body = (
                    <>
                      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                        <b className="text-[0.9375rem] tabular-nums">{q.number}</b>
                        {q.issuedAt || q.createdAt ? <small className="text-xs text-text-muted">{formatThaiDate(q.issuedAt ?? q.createdAt)}</small> : null}
                        <span className="mt-1"><StatusBadge tone={status.tone}>{status.label}</StatusBadge></span>
                      </span>
                      {q.totalAmount != null ? <span className="shrink-0 text-[0.9375rem] font-bold tabular-nums">{formatMoney(q.totalAmount)}</span> : null}
                    </>
                  );
                  return (
                    <li key={`${q.href ? 'd' : 'l'}-${q.id}`}>
                      {q.href
                        ? <Link to={q.href} className={ROW} data-testid={`order-quotation-${q.id}`}>{body}</Link>
                        : <div className={ROW} data-testid={`order-quotation-legacy-${q.id}`}>{body}</div>}
                    </li>
                  );
                })}
              </ul>
            ) : null}
          </section>
        );
      })}
    </div>
  );
}

/** เงิน → รวมโครงการ: the three project-wide tiles from the same orders response (live orders only). */
export function ProjectTotalsTiles({ totals }) {
  if (totals?.valueExVat == null) return null;
  const tiles = [
    ['มูลค่าก่อน VAT', totals.valueExVat, 'text-text'],
    ['รับแล้ว (รวม VAT)', totals.received, 'text-success-dark'],
    ['ค้างรับ (รวม VAT)', totals.outstanding, 'text-text'],
  ];
  return (
    <section className="flex flex-col gap-2" data-testid="project-totals">
      <h2 className="m-0 px-1 text-[0.8125rem] font-bold text-text-secondary">รวมทั้งโครงการ</h2>
      <div className="grid grid-cols-1 gap-3 rounded-md border border-border bg-surface p-4 sm:grid-cols-3">
        {tiles.map(([label, value, tone]) => (
          <div key={label} className="flex items-baseline justify-between gap-3 sm:flex-col sm:items-start sm:gap-1">
            <span className="text-sm text-text-muted">{label}</span>
            <b className={`text-lg tabular-nums ${tone}`}>{formatMoney(value)}</b>
          </div>
        ))}
      </div>
      <p className="m-0 px-1 text-xs text-text-muted">ไม่นับออเดอร์ที่เสียงานหรือยกเลิกแล้ว</p>
    </section>
  );
}
