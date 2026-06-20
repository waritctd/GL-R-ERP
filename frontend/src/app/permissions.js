import { ROLE_PERMISSIONS } from '../api/routes.js';

export function hasPermission(role, key) {
  return ROLE_PERMISSIONS[key].includes(role);
}

export function allowedRoute(route, user) {
  if (!user) return 'dashboard';
  if (route === 'employees' && !hasPermission(user.role, 'canViewEmployees')) return 'dashboard';
  if (route === 'detail' && !hasPermission(user.role, 'canViewEmployees')) return 'dashboard';
  if (route === 'requests' && !hasPermission(user.role, 'canReviewProfileRequests')) return 'dashboard';
  if (route === 'users' && !hasPermission(user.role, 'canManageUsers')) return 'dashboard';
  if (route === 'myrequests' && user.role !== 'employee') return 'dashboard';
  return route;
}
