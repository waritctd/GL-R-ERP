import { Outlet, useLocation } from 'react-router-dom';
import { AccessDeniedPage } from '../components/common/AccessDeniedPage.jsx';
import { canAccessPath } from './permissions.js';

// Route guard: renders the nested route (`<Outlet/>`) when `user` may access
// the current path. Otherwise renders an in-place "insufficient privileges"
// view (issue #391) instead of the old silent `<Navigate to="/" replace/>`
// — a denied deep link now stays on its own URL and explains the refusal,
// rather than dropping the user on the dashboard with no indication anything
// was refused.
//
// This component only ever runs for a LOGGED-IN user who lacks the
// permission for the current path: App.jsx returns <LoginPage/> and never
// renders the <Routes> tree at all while `!user` (see App.jsx's early
// `if (!user)` return above where these routes are declared), so there is no
// "not authenticated" case reaching this guard to preserve — that redirect
// (to the login screen) happens entirely outside RequireAccess and is
// unchanged by this fix.
export function RequireAccess({ user }) {
  const location = useLocation();
  if (!canAccessPath(location.pathname, user)) {
    return <AccessDeniedPage />;
  }
  return <Outlet />;
}
