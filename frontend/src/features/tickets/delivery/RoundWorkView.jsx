/* Tokens only. What the warehouse (and an invoice issuer whose role has no deal page) sees at /tickets/:id (the bell links here): one order's delivery round and nothing of
 * the deal (no tabs, no money, no other data). A header, then the round panel with the review actions (phone M14, M15).
 */
import { useQuery } from '@tanstack/react-query';
import { Button } from '../../../components/common/Button.jsx';
import { Icon } from '../../../components/common/Icon.jsx';
import { DeliveryRoundsPanel } from './DeliveryRoundsPanel.jsx';
import { deliveryRoundsQueryOptions } from './deliveryRoundsQuery.js';

export function RoundWorkView({ user, ticketId, onBack }) {
  // Same key as the panel's own read, so this costs no second request.
  const { data } = useQuery(deliveryRoundsQueryOptions(ticketId));
  const order = data?.order;
  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-4" data-testid="round-work-view">
      <header className="flex items-center gap-2">
        <Button type="button" variant="icon" className="shrink-0 border-0" aria-label="กลับ" onClick={onBack}>
          <Icon name="chevronLeft" size={20} />
        </Button>
        <div className="flex min-w-0 flex-col">
          <h1 className="m-0 truncate text-lg">{order ? order.projectName || order.customerName || order.displayCode : 'รอบส่งของ'}</h1>
          {order ? <small className="text-xs text-text-muted">{[order.customerName, order.displayCode].filter(Boolean).join(' · ')}</small> : null}
        </div>
      </header>
      <DeliveryRoundsPanel ticketId={ticketId} warehouseUserId={user.role === 'warehouse' ? user.id : null} dealless={user.role !== 'warehouse'} pinInvoiceActions={false} />
    </div>
  );
}
