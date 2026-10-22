import { lazy } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';

const TicketDetailPage = lazy(() => import('./TicketDetailPage.jsx').then((module) => ({ default: module.TicketDetailPage })));

/**
 * Wraps a /tickets/:id/... sub-route (e.g. the deposit notice page): account is sent to the finance
 * deal page instead, everyone else gets the children. Reads the id from either route-param spelling.
 */
export function AccountFinanceRedirect({ user, children }) {
  const params = useParams();
  const id = params.id ?? params.ticketId;
  if (user?.role === 'account') return <Navigate to={`/finance/deals/${id}`} replace />;
  return children;
}

/**
 * /tickets/:id. H1 lockdown: account is refused on every /tickets deal route (403) and works a deal
 * through the finance view instead, so an account user landing here (a stale bookmark, an old
 * notification link) is sent to /finance/deals/:id BEFORE the ticket page mounts — TicketDetailPage's
 * first query never fires. `replace` keeps the refused URL out of the back-button history.
 * Presentation only; the server's 403 is the boundary.
 */
export function TicketDetailRoute({ user, showToast }) {
  const { id } = useParams();
  const navigate = useNavigate();
  if (user?.role === 'account') {
    return <Navigate to={`/finance/deals/${id}`} replace />;
  }
  return (
    <TicketDetailPage
      user={user}
      ticketId={id}
      // navigate(-1) (not a fixed '/tickets') so the list's status filter and
      // search text — now carried in the URL query string, see
      // TicketListPage.jsx — survive the round trip instead of resetting.
      onBack={() => navigate(-1)}
      showToast={showToast}
    />
  );
}
