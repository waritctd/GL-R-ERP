/* Tokens only. The order chips at the top of the เงิน / ส่งของ / ไฟล์ tab bodies (phone designs M09, M10, M19). Each
 * order IS its own deal page, so a chip is a plain link to that deal (same tab): no per-order state in one page.
 * An optional all-orders chip ("รวมโครงการ" on เงิน) links to `allHref` and is the "on" chip when `allActive`.
 */
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { cn } from '../../utils/cn.js';

/** The project's orders (GET /api/tickets/{id}/orders); shared by the overview box, the tab chips and the quote tab. */
export function useProjectOrders(ticketId, enabled = true) {
  return useQuery({
    queryKey: queryKeys.ticketOrders(ticketId),
    queryFn: () => api.tickets.orders(ticketId),
    enabled: Boolean(ticketId) && enabled,
  });
}

const CHIP = 'inline-flex min-h-11 items-center whitespace-nowrap rounded-full border-[1.5px] px-3.5 text-[0.9375rem] font-semibold no-underline';
const CHIP_OFF = 'border-border-input bg-surface text-text-secondary';
const CHIP_ON = 'border-primary bg-primary-bg text-primary';

/**
 * `orders`: the response's orders; renders nothing for a single order. `hrefFor(order)`: where that order's chip goes.
 * `allLabel` + `allHref`: the optional all-orders chip. `currentId` is the deal on screen.
 */
export function OrderSwitcher({ orders, currentId, hrefFor, allLabel = null, allHref = null, allActive = false }) {
  if (!orders || orders.length < 2) return null;
  return (
    <nav aria-label="เลือกออเดอร์" data-testid="order-switcher" className="flex flex-wrap gap-2">
      {orders.map((order) => {
        const on = !allActive && order.ticketId === Number(currentId);
        return (
          <Link
            key={order.ticketId}
            to={hrefFor(order)}
            aria-current={on ? 'page' : undefined}
            data-testid={`order-chip-${order.orderNo}`}
            className={cn(CHIP, on ? CHIP_ON : CHIP_OFF)}
          >
            ออเดอร์ {order.orderNo}
          </Link>
        );
      })}
      {allLabel && allHref ? (
        <Link
          to={allHref}
          aria-current={allActive ? 'page' : undefined}
          data-testid="order-chip-all"
          className={cn(CHIP, allActive ? CHIP_ON : CHIP_OFF)}
        >
          {allLabel}
        </Link>
      ) : null}
    </nav>
  );
}
