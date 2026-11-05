import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

export function dedupeById(...lists) {
  const seen = new Map();
  lists.flat().forEach((ticket) => {
    if (ticket && !seen.has(ticket.id)) seen.set(ticket.id, ticket);
  });
  return [...seen.values()];
}

export function rowAmount(ticket) {
  const outstanding = Number(ticket.amountOutstanding ?? 0);
  return outstanding > 0 ? outstanding : Number(ticket.amountPayable ?? 0);
}

/** The account role's scoped ticket list plus CLOSED_PAID deals (outside that scope today), toasting on a load error. */
export function useAccountTickets(showToast) {
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

  return { scopedQuery, closedPaidQuery };
}
