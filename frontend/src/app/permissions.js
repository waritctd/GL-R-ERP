import { ROLE_PERMISSIONS } from '../api/routes.js';

export function hasPermission(role, key) {
  return ROLE_PERMISSIONS[key]?.includes(role) ?? false;
}

export function defaultRouteFor() {
  // Everyone now lands on the shared employee dashboard. Role-specific
  // dashboards (HR overview, ticket overview) live in their own tabs.
  return 'dashboard';
}

export function allowedRoute(route, user) {
  if (!user) return 'dashboard';
  const fallback = defaultRouteFor(user);
  if (route === 'hr-dashboard' && !hasPermission(user.role, 'canViewEmployees')) return fallback;
  if (route === 'ticket-dashboard' && !hasPermission(user.role, 'canViewTickets')) return fallback;
  if (route === 'employees' && !hasPermission(user.role, 'canViewEmployees')) return fallback;
  if (route === 'detail' && !hasPermission(user.role, 'canViewEmployees')) return fallback;
  if (route === 'requests' && !hasPermission(user.role, 'canReviewProfileRequests')) return fallback;
  if (route === 'myrequests' && !hasPermission(user.role, 'canSubmitProfileRequests')) return fallback;
  if ((route === 'tickets' || route === 'ticket-detail') && !hasPermission(user.role, 'canViewTickets')) return fallback;
  if (route === 'commissions' && !hasPermission(user.role, 'canViewCommissions')) return fallback;
  if (route === 'payroll' && !hasPermission(user.role, 'canManagePayroll')) return fallback;
  if (route === 'overtime' && !user.employeeId && !hasPermission(user.role, 'canViewAllOvertime')) return fallback;
  if (route === 'leave' && !user.employeeId && !hasPermission(user.role, 'canViewAllLeave')) return fallback;
  if (route === 'profile' && !user.employeeId) return fallback;
  return route;
}

// URL-path guards for the router (branch 5). These port the `allowedRoute`
// conditions above to path predicates so `permissions.js` stays the single
// source of truth. `canAccessPath` returns true when `user` may see `path`.
// Unguarded paths (`/`, `/attendance`) and unknown paths return true — the
// route table / `*` fallback handles those.
const PATH_GUARDS = [
  { test: (p) => p === '/hr', can: (u) => hasPermission(u.role, 'canViewEmployees') },
  { test: (p) => p === '/ticket-overview', can: (u) => hasPermission(u.role, 'canViewTickets') },
  { test: (p) => p === '/employees' || p.startsWith('/employees/'), can: (u) => hasPermission(u.role, 'canViewEmployees') },
  { test: (p) => p === '/requests', can: (u) => hasPermission(u.role, 'canReviewProfileRequests') },
  { test: (p) => p === '/my-requests', can: (u) => hasPermission(u.role, 'canSubmitProfileRequests') },
  { test: (p) => p === '/tickets' || p.startsWith('/tickets/'), can: (u) => hasPermission(u.role, 'canViewTickets') },
  { test: (p) => p === '/commissions', can: (u) => hasPermission(u.role, 'canViewCommissions') },
  { test: (p) => p === '/payroll', can: (u) => hasPermission(u.role, 'canManagePayroll') },
  { test: (p) => p === '/overtime', can: (u) => !!u.employeeId || hasPermission(u.role, 'canViewAllOvertime') },
  { test: (p) => p === '/leave', can: (u) => !!u.employeeId || hasPermission(u.role, 'canViewAllLeave') },
  { test: (p) => p === '/profile', can: (u) => !!u.employeeId },
  { test: (p) => p === '/price-import', can: (u) => hasPermission(u.role, 'canManagePriceImport') },
];

export function canAccessPath(path, user) {
  if (!user) return false;
  const guard = PATH_GUARDS.find((entry) => entry.test(path));
  if (!guard) return true;
  return guard.can(user);
}
