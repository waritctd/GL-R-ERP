import { lazy } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Navigate, useLocation, useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission, isOversightReader } from '../../app/permissions.js';

const RoundWorkView = lazy(() => import('./delivery/RoundWorkView.jsx').then((module) => ({ default: module.RoundWorkView })));
const TicketDetailPage = lazy(() => import('./TicketDetailPage.jsx').then((module) => ({ default: module.TicketDetailPage })));

/** Wraps a /tickets/:id/... sub-route (e.g. the deposit notice page): account is sent to the finance deal page
 * instead, everyone else gets the children. Reads the id from either route-param spelling. */
export function AccountFinanceRedirect({ user, children }) {
  const params = useParams();
  const id = params.id ?? params.ticketId;
  if (user?.role === 'account') return <Navigate to={`/finance/deals/${id}`} replace />;
  return children;
}

/** Redirect account users to the finance deal view before ticket queries mount; server authorization remains
 * authoritative. */
export function TicketDetailRoute({ user, showToast }) {
  const { id } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  // A grant holder whose role has the deal page but who may not open THIS deal (403) falls back to the round work view
  // instead of a dead end. Same key as the page's own read, so the page does not read the deal twice.
  const holderOfDealRole = Boolean(user?.canIssueTaxInvoice) && hasPermission(user.role, 'canViewWholeDeal') && user.role !== 'account';
  const dealProbe = useQuery({
    queryKey: queryKeys.ticketDetail(id),
    queryFn: () => api.tickets.get(id).then((r) => r.ticket),
    enabled: holderOfDealRole,
    retry: false,
  });
  const dealRefused = holderOfDealRole && dealProbe.isError && dealProbe.error?.status === 403;
  if (user?.role === 'account') {
    return <Navigate to={`/finance/deals/${id}`} replace />;
  }
  // The warehouse never reads the deal, nor does an invoice issuer whose role has no deal page (the grant is per person,
  // any role): each works one delivery round (RoundWorkView), the bell notice's target. A rep or manager who also holds
  // the grant keeps the deal page (its ship tab does the same work); account lands on the finance page above.
  // An oversight reader (sales manager or read-all grant holder) reads the whole page, even as warehouse or issuer.
  if (!isOversightReader(user) && (user?.role === 'warehouse' || dealRefused || (user?.canIssueTaxInvoice && !hasPermission(user.role, 'canViewWholeDeal')))) {
    return <RoundWorkView user={user} ticketId={id} onBack={() => (location.key === 'default' ? navigate('/', { replace: true }) : navigate(-1))} />;
  }
  return (
    <TicketDetailPage
      // Keyed by the deal: moving from one deal to another mounts a fresh page, so NO per-deal client state (an
      // unsaved item edit, the open tab's panels, dialogs and their drafts, the timeline filter) can cross from deal A
      // to deal B — or come back half-restored on A.
      key={id}
      user={user}
      ticketId={id}
      // navigate(-1) (not a fixed '/tickets') so the list's status filter and search text — now carried in the URL
      // query string, see TicketListPage.jsx — survive the round trip instead of resetting.
      onBack={() => navigate(-1)}
      showToast={showToast}
    />
  );
}
