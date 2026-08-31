import { ROLE_PERMISSIONS } from '../api/routes.js';
import { SELF_SERVICE_ONLY } from './features.js';

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
  if (route === 'employees' && !hasPermission(user.role, 'canViewEmployees')) return fallback;
  if (route === 'detail' && !hasPermission(user.role, 'canViewEmployees')) return fallback;
  if (route === 'requests' && !hasPermission(user.role, 'canReviewProfileRequests')) return fallback;
  // Role-scoped views: 'tickets' is the pipeline BROWSER (canViewDealPipeline
  // — sales/sales_manager/ceo only), 'ticket-detail' stays the broader
  // ticket-DETAIL read (canViewTickets — keeps import/account).
  if (route === 'tickets' && !hasPermission(user.role, 'canViewDealPipeline')) return fallback;
  if (route === 'ticket-detail' && !hasPermission(user.role, 'canViewTickets')) return fallback;
  if (route === 'finance' && !hasPermission(user.role, 'canConfirmPayments')) return fallback;
  if (route === 'commissions' && !hasPermission(user.role, 'canViewCommissions')) return fallback;
  // Split (issue #390): the route itself only needs read access (canViewPayroll, hr+ceo) --
  // mutating controls inside PayrollPage.jsx gate individually on canManagePayroll (hr-only).
  if (route === 'payroll' && !hasPermission(user.role, 'canViewPayroll')) return fallback;
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
  // Role-scoped views: the bare `/tickets` list is the deal-PIPELINE BROWSER
  // (canViewDealPipeline — sales/sales_manager/ceo only); `/tickets/:id`
  // detail stays on the broader canViewTickets (keeps import/account, whose
  // Overview/worklist rows deep-link straight to a single deal). See
  // docs/role-scoped-views.md.
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
  // Split (issue #390): mirrors PayrollController exactly -- every GET plus the non-persisting
  // POST /preview and /preview/export/{kind} are hasAnyRole('HR','CEO'); every write stays
  // hr-only and is gated inside PayrollPage.jsx (canManagePayroll), not at the route level.
  { test: (p) => p === '/payroll', can: (u) => hasPermission(u.role, 'canViewPayroll') },
  // Garnishment shortfall ledger. Needs its own entry because the guard above is an EXACT match
  // and canAccessPath fails OPEN for a path no guard claims — a nested path would otherwise be
  // reachable by any authenticated role. canViewPayroll (hr + ceo) mirrors the endpoint's
  // @PreAuthorize("hasAnyRole('HR','CEO')") and PayrollDeductionShortfallService's VIEW_ROLES.
  { test: (p) => p === '/payroll/deduction-shortfalls', can: (u) => hasPermission(u.role, 'canViewPayroll') },
  // Written-consent register (issue #376's other read surface). Needs its own entry for exactly the
  // same reason as the sibling above — `/payroll` is an EXACT match and canAccessPath fails OPEN
  // for a path no guard claims, so without this line any authenticated role reaches it.
  // canViewPayroll (hr + ceo) mirrors the GET's @PreAuthorize("hasAnyRole('HR','CEO')") and
  // DeductionWrittenConsentService.VIEW_ROLES. The narrower WRITE gate (hasRole('HR'), EDIT_ROLES)
  // is enforced inside the page on canManagePayroll — a route guard cannot express it, since CEO
  // must reach the page read-only.
  { test: (p) => p === '/payroll/deduction-consents', can: (u) => hasPermission(u.role, 'canViewPayroll') },
  // ล.ย.01 tax-allowance declaration (issue #387). `/tax-allowance` is the
  // employee's own declaration form — same "must have an employeeId" shape as
  // `/profile` above, and just as important to guard explicitly: unknown paths
  // fail OPEN in canAccessPath (the `if (!guard) return true` below), so omitting
  // this entry would let any logged-in user without an employeeId reach it.
  { test: (p) => p === '/tax-allowance', can: (u) => !!u.employeeId },
  // `/tax-allowance-review` is HR/CEO's register — mirrors
  // TaxAllowanceDeclarationService's GET /declarations gate (hasAnyRole('HR','CEO')).
  { test: (p) => p === '/tax-allowance-review', can: (u) => hasPermission(u.role, 'canViewTaxAllowanceRegister') },
  // /employee-requests hosts both the overtime and welfare/special-money tabs
  // (RequestsPage.jsx), so it is visible to anyone either sub-page would be
  // visible to. /overtime stays guarded identically since it's a same-page
  // redirect alias (App.jsx), not a separate view.
  {
    test: (p) => p === '/employee-requests' || p === '/overtime',
    can: (u) => !!u.employeeId || hasPermission(u.role, 'canViewAllOvertime') || hasPermission(u.role, 'canViewAllSpecialMoney'),
  },
  // /leave/new (Phase A2, #485): the composer files a request for the SAME "self or an
  // employeeId-bearing acting employee" audience as /leave itself -- same guard.
  { test: (p) => p === '/leave' || p === '/leave/new', can: (u) => !!u.employeeId || hasPermission(u.role, 'canViewAllLeave') },
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
  // งานนำเข้า — Import's cross-deal fulfilment workspace. This entry is MANDATORY,
  // not decorative: canAccessPath fails OPEN for any path no guard claims (the
  // `if (!guard) return true` below), so without this line every authenticated
  // role reaches /fulfilment. Mirrors ROLE_PERMISSIONS.canActOnFulfilment, itself
  // a mirror of TicketService.FULFILMENT_ROLES {import, ceo}. Frontend gating
  // only — the four endpoints enforce the real gate and are unchanged.
  { test: (p) => p === '/fulfilment', can: (u) => hasPermission(u.role, 'canActOnFulfilment') },
  // Matches the sidebar's nav condition exactly (AppShell.jsx: `role === 'ceo'`).
  { test: (p) => p === '/ceo-settings', can: (u) => u.role === 'ceo' },
  // Attendance calendar admin (hr.holiday / hr.work_schedule / hr.work_schedule_assignment CRUD —
  // PR #480's API, this branch's UI). Mirrors the three controllers' requireAnyRole(user, "hr",
  // "ceo") exactly. This is frontend gating only — see ROLE_PERMISSIONS.canManageAttendanceCalendar
  // in routes.js for the same caveat.
  { test: (p) => p === '/settings/attendance-calendar', can: (u) => hasPermission(u.role, 'canManageAttendanceCalendar') },
  // §5 announcement PDF upload (LeaveController#uploadPolicyDocument's requireAnyRole(user, "hr",
  // "ceo") — PR #494's write API, this branch's UI). Its own entry is required, not optional:
  // canAccessPath fails OPEN for any path no guard claims, and `/settings/attendance-calendar`
  // above is an EXACT match, so a second `/settings/*` route without its own line would be
  // reachable by every authenticated role. Frontend gating only — see
  // ROLE_PERMISSIONS.canManageLeavePolicyDocument for the same caveat.
  { test: (p) => p === '/settings/leave-policy', can: (u) => hasPermission(u.role, 'canManageLeavePolicyDocument') },
];

// Release lockdown (SELF_SERVICE_ONLY, app/features.js). HR and CEO are the
// only roles that keep the full portal: HR runs payroll and works the ล.ย.01 /
// profile-request registers, and CEO reads them. Every other role — sales,
// sales_manager, import, account, warehouse, qc, employee, and a division
// manager (role `employee` + manager flag) — is confined to self-service.
//
// A division manager keeps their approval work despite being locked: leave and
// OT approval live INSIDE /leave and /employee-requests, both self-service
// paths, not on the DivisionManagerOverview landing this hides.
const SELF_SERVICE_EXEMPT_ROLES = ['hr', 'ceo'];

export function isSelfServiceLocked(user) {
  if (!SELF_SERVICE_ONLY) return false;
  return !SELF_SERVICE_EXEMPT_ROLES.includes(user?.role);
}

// The lockdown is an ALLOWLIST, not a blocklist, and that direction is the
// whole point. canAccessPath below fails OPEN for any path no PATH_GUARDS
// entry claims (`if (!guard) return true`), so a blocklist would silently
// admit every route added after this one was written. Listing what stays
// visible instead makes a new route locked-by-default — the safe direction.
//
// Entries match exactly or as a path prefix (`/leave` covers `/leave/new`).
// The prefix test requires the trailing slash, so `/tax-allowance` does NOT
// admit `/tax-allowance-review` — HR's register, which must stay hidden.
const SELF_SERVICE_PATHS = [
  '/profile',
  // Alias that redirects to /profile (App.jsx) — must be allowed identically
  // or an old notification deep-link dies at the access-denied page.
  '/my-requests',
  '/attendance',
  '/leave',
  '/employee-requests',
  // Same reasoning as /my-requests: /overtime is a redirect alias for
  // /employee-requests?tab=ot, and OvertimeService hardcodes it in notification
  // links.
  '/overtime',
  '/tax-allowance',
];

export function isSelfServicePath(path) {
  // Match on the pathname alone. Every caller today passes a bare path
  // (RequireAccess passes location.pathname; EmployeeDashboard's quickActions
  // list holds bare paths), but the lock changed the cost of getting this
  // wrong: an unmatched path used to fall through to PATH_GUARDS and be
  // ALLOWED, and now it is REFUSED. `/employee-requests?tab=ot` — the exact
  // shape the /overtime alias and EmployeeSelfService's OT card use — would
  // otherwise send a user to the access-denied page for their own OT tab.
  // This can only ever admit a path whose pathname is already on the list, so
  // it cannot widen the lock.
  const pathname = path.split(/[?#]/)[0];
  if (pathname === '/') return true;
  return SELF_SERVICE_PATHS.some((allowed) => pathname === allowed || pathname.startsWith(`${allowed}/`));
}

export function canAccessPath(path, user) {
  if (!user) return false;
  // Checked BEFORE the guards below, not after: the lockdown has to deny paths
  // the user's role would otherwise pass (a sales rep on /tickets) as well as
  // paths no guard claims at all.
  if (isSelfServiceLocked(user) && !isSelfServicePath(path)) return false;
  const guard = PATH_GUARDS.find((entry) => entry.test(path));
  if (!guard) return true;
  return guard.can(user);
}
