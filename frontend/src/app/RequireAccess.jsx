import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { canAccessPath } from './permissions.js';

// Route guard: renders the nested route (`<Outlet/>`) when `user` may access
// the current path, otherwise redirects to `/` — reproducing the old
// `allowedRoute` fallback-to-dashboard behavior.
export function RequireAccess({ user }) {
  const location = useLocation();
  if (!canAccessPath(location.pathname, user)) {
    return <Navigate to="/" replace />;
  }
  return <Outlet />;
}
