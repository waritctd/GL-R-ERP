import { ROLE_PERMISSIONS } from '../api/routes.js';

export function hasPermission(role, key) {
  return ROLE_PERMISSIONS[key]?.includes(role) ?? false;
}

export function defaultRouteFor() {
  // Everyone now lands on the shared employee dashboard. Role-specific
  // dashboards (HR overview, ticket overview) live in their own tabs.
  return 'dashboard';
}

/**
 * A "division manager" is role `employee` with the manager flag set — the
 * non-sales manager of their own division (DivisionAccessPolicy.isManager:
 * position contains "ผู้จัดการ", assistant managers included). This is the
 * exact `user?.manager` predicate EmployeeDashboard.dashboardMode's 'manager'
 * branch uses, narrowed to `role === 'employee'` so sales_manager/hr/ceo
 * (who have their own dedicated landings/company mode) aren't swept in.
 * Single source of truth for App.jsx's `/` route branch, AppShell.jsx's nav
 * grouping, and DivisionManagerOverview's own detection.
 */
export function isDivisionManager(user) {
  return user?.role === 'employee' && !!user?.manager;
}

export function allowedRoute(route, user) {
  if (!user) return 'dashboard';
  const fallback = defaultRouteFor(user);
  if (route === 'hr-dashboard' && !hasPermission(user.role, 'canViewEmployees')) return fallback;
  // /ticket-overview is the pipeline's own ภาพรวม tab — gated with the
  // pipeline browser, not plain ticket-detail read.
  if (route === 'ticket-dashboard' && !hasPermission(user.role, 'canViewDealPipeline')) return fallback;
  if (route === 'employees' && !hasPermission(user.role, 'canViewEmployees')) return fallback;
  if (route === 'detail' && !hasPermission(user.role, 'canViewEmployees')) return fallback;
  if (route === 'requests' && !hasPermission(user.role, 'canReviewProfileRequests')) return fallback;
  if (route === 'myrequests' && !hasPermission(user.role, 'canSubmitProfileRequests')) return fallback;
  // Role-scoped views: 'tickets'/'ticket-dashboard' are the pipeline BROWSER
  // (canViewDealPipeline — sales/sales_manager/ceo only), 'ticket-detail'
  // stays the broader ticket-DETAIL read (canViewTickets — keeps
  // import/account).
  if (route === 'tickets' && !hasPermission(user.role, 'canViewDealPipeline')) return fallback;
  if (route === 'ticket-detail' && !hasPermission(user.role, 'canViewTickets')) return fallback;
  if (route === 'finance' && !hasPermission(user.role, 'canConfirmPayments')) return fallback;
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
  // Role-scoped views: `/ticket-overview` and the bare `/tickets` list are the
  // deal-PIPELINE BROWSER (canViewDealPipeline — sales/sales_manager/ceo
  // only); `/tickets/:id` detail stays on the broader canViewTickets (keeps
  // import/account, whose Overview/worklist rows deep-link straight to a
  // single deal). See docs/role-scoped-views.md.
  { test: (p) => p === '/ticket-overview', can: (u) => hasPermission(u.role, 'canViewDealPipeline') },
  { test: (p) => p === '/employees' || p.startsWith('/employees/'), can: (u) => hasPermission(u.role, 'canViewEmployees') },
  { test: (p) => p === '/requests', can: (u) => hasPermission(u.role, 'canReviewProfileRequests') },
  // `/my-requests` is now an alias that redirects to `/profile`, so it has to
  // gate identically — a stricter guard would 403 an HR user following an old
  // notification link to a page they are allowed to see.
  { test: (p) => p === '/my-requests' || p === '/profile', can: (u) => !!u.employeeId },
  // Split (role-scoped views program): the deal-pipeline LIST is the
  // pipeline browser (canViewDealPipeline — sales/sales_manager/ceo only); a
  // single ticket's DETAIL page stays canViewTickets (import/account keep
  // detail-read access even though they no longer browse the full list).
  { test: (p) => p === '/tickets', can: (u) => hasPermission(u.role, 'canViewDealPipeline') },
  { test: (p) => p.startsWith('/tickets/'), can: (u) => hasPermission(u.role, 'canViewTickets') },
  { test: (p) => p === '/catalog', can: (u) => hasPermission(u.role, 'canViewCatalog') },
  { test: (p) => p === '/commissions', can: (u) => hasPermission(u.role, 'canViewCommissions') },
  // Account's money-lifecycle worklist (งานการเงิน) — mirrors ROLE_PERMISSIONS
  // .canConfirmPayments exactly (account/ceo), same audience as the ticket
  // confirmDepositPaid/confirmFinalPayment/confirmCloseReady actions this
  // page's rows drive.
  { test: (p) => p === '/finance', can: (u) => hasPermission(u.role, 'canConfirmPayments') },
  { test: (p) => p === '/payroll', can: (u) => hasPermission(u.role, 'canManagePayroll') },
  // /employee-requests hosts both the overtime and welfare/special-money tabs
  // (RequestsPage.jsx), so it is visible to anyone either sub-page would be
  // visible to. /overtime stays guarded identically since it's a same-page
  // redirect alias (App.jsx), not a separate view.
  {
    test: (p) => p === '/employee-requests' || p === '/overtime',
    can: (u) => !!u.employeeId || hasPermission(u.role, 'canViewAllOvertime') || hasPermission(u.role, 'canViewAllSpecialMoney'),
  },
  { test: (p) => p === '/leave', can: (u) => !!u.employeeId || hasPermission(u.role, 'canViewAllLeave') },
  { test: (p) => p === '/price-import', can: (u) => hasPermission(u.role, 'canManagePriceImport') },
  // The bare queue (`/pricing-requests`) is Import's work list — canViewPricingRequestQueue
  // only (import/ceo/sales_manager), never sales. Detail sub-paths are a separate rule: a sales
  // rep needs to reach their OWN request's `/pricing-requests/:id` (PICKED_UP/MORE_INFO_REQUIRED
  // notifications link there — NotificationRepository.notifyEmployeeForPricingRequest), but the
  // backend's requireViewable ownership check is what actually enforces per-request access; this
  // guard only decides whether the role belongs on the URL shape at all. Regression-fixed by
  // COMMIT 6 review remediation: a single combined rule previously let sales reach the queue too.
  { test: (p) => p === '/pricing-requests', can: (u) => hasPermission(u.role, 'canViewPricingRequestQueue') },
  { test: (p) => p.startsWith('/pricing-requests/'), can: (u) => hasPermission(u.role, 'canViewPricingRequestQueue') || u.role === 'sales' },
  // Matches the sidebar's nav condition exactly (AppShell.jsx: `role === 'ceo'`).
  { test: (p) => p === '/ceo-settings', can: (u) => u.role === 'ceo' },
  // Step 7: Factory Purchase Order and Import Execution — Import/CEO only, mirrors
  // ProcurementService.RAW_PO_ROLES and AppShell.jsx's own nav condition.
  { test: (p) => p === '/factory-purchase-orders' || p.startsWith('/factory-purchase-orders/'), can: (u) => hasPermission(u.role, 'canManageProcurement') },
  // Role-scoped views (Import build): the combined "จัดซื้อ & นำเข้า" page
  // (ProcurementFulfilmentPage) — same audience as the raw PO list above,
  // since it embeds ProcurementListPage as its second section.
  { test: (p) => p === '/procurement', can: (u) => hasPermission(u.role, 'canManageProcurement') },
];

export function canAccessPath(path, user) {
  if (!user) return false;
  const guard = PATH_GUARDS.find((entry) => entry.test(path));
  if (!guard) return true;
  return guard.can(user);
}
