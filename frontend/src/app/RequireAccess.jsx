import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { AccessDeniedPage } from '../components/common/AccessDeniedPage.jsx';
import { canAccessPath, dealRedirectFor } from './permissions.js';

// Route guard: renders the nested route (`<Outlet/>`) when `user` may access the current path.
export function RequireAccess({ user }) {
  const location = useLocation();
  if (!canAccessPath(location.pathname, user)) {
    // Import and account are refused the whole-deal page; a stale deep-link goes to their own page
    // for the same deal (query string dropped — the whole-deal page's params mean nothing there).
    const dealPath = dealRedirectFor(location.pathname, user);
    if (dealPath) return <Navigate to={dealPath} replace />;
    return <AccessDeniedPage />;
  }
  return <Outlet />;
}
