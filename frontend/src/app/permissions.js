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
  if (route === 'profile' && !user.employeeId) return fallback;
  return route;
}
