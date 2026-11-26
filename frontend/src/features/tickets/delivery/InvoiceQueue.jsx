import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../../api/index.js';
import { queryKeys } from '../../../api/queryKeys.js';
import { Icon } from '../../../components/common/Icon.jsx';
import { Panel } from '../../../components/common/Layout.jsx';
import { formatThaiDate } from '../../../utils/format.js';
import { thaiDateTime } from './deliveryRound.js';

/** The invoice issuer's queue on งานวันนี้: rounds the warehouse has forwarded. Hidden when there is nothing to do. */
export function InvoiceQueue() {
  const query = useQuery({
    queryKey: queryKeys.invoiceQueue(),
    queryFn: () => api.deliveryRequests.awaitingInvoice().then((r) => r.items ?? []),
    refetchOnMount: 'always',
  });
  const items = query.data ?? [];
  if (!query.isError && items.length === 0) return null;
  return (
    <Panel title="รอคุณออกใบกำกับ" actions={query.isError ? null : <span className="text-xs text-text-muted">{items.length} รายการ · เรียงตามวันที่ต้องการให้ส่ง</span>}>
      {query.isError ? <p className="m-0 text-sm font-bold text-danger">โหลดไม่สำเร็จ</p> : (
        <ul className="m-0 grid list-none divide-y divide-border p-0" data-testid="invoice-queue">
          {items.map((item) => (
            <li key={item.roundId}>
              <Link to={`/tickets/${item.ticketId}?view=issue`} className="flex min-h-[56px] items-center gap-3 py-2.5 text-text no-underline">
                <span className="min-w-0 flex-1">
                  <b className="block [overflow-wrap:anywhere]">{item.buyerName || item.projectName || item.displayCode}</b>
                  <small className="block text-sm text-text-muted">
                    {[item.displayCode, `รอบส่งที่ ${item.roundNo}`, item.ownerName ? `ฝ่ายขาย ${item.ownerName}` : null].filter(Boolean).join(' · ')}
                  </small>
                  <small className="block text-sm text-text-muted">
                    <b className="text-text">{item.requestedDate ? `ต้องการให้ส่ง ${formatThaiDate(item.requestedDate)}` : 'ไม่ระบุวันส่ง'}</b>
                    {item.forwardedAt ? ` · คลังส่งต่อ ${thaiDateTime(item.forwardedAt)}` : ''}
                  </small>
                </span>
                <Icon name="chevronRight" size={18} />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
