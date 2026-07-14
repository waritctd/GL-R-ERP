import { ROLE_PERMISSIONS } from '../api/routes.js';

export function hasPermission(role, key) {
  return ROLE_PERMISSIONS[key]?.includes(role) ?? false;
}

// URL-path guards for the router. `canAccessPath` returns true when `user`
// may see `path`. Unguarded paths (`/`, `/attendance`) and unknown paths
// return true — the route table / `*` fallback handles those.
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
];

export function canAccessPath(path, user) {
  if (!user) return false;
  const guard = PATH_GUARDS.find((entry) => entry.test(path));
  if (!guard) return true;
  return guard.can(user);
}
