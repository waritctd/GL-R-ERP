import { ROLE_PERMISSIONS } from '../api/routes.js';
import { SALES_ENABLED, SELF_SERVICE_ONLY } from './features.js';
import { canCreateDealQuotationStandalone } from '../features/quotations/quotationMeta.js';
import { isOversightReader } from '../features/tickets/dealOwnership.js';

export { isOversightReader };

export function hasPermission(role, key) {
  return ROLE_PERMISSIONS[key]?.includes(role) ?? false;
}

export function defaultRouteFor() {
  // Everyone lands on the shared employee dashboard; role dashboards live in their own tabs.
  return 'dashboard';
}

/** A "division manager" is role `employee` with the manager flag set — the non-sales manager of their own
 * division (DivisionAccessPolicy.isManager: position contains "ผู้จัดการ", assistant managers included). */
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
  // 'tickets' is the pipeline BROWSER (canViewDealPipeline: sales/sales_manager/ceo only);
  // 'ticket-detail' is the whole-deal DETAIL read (canViewWholeDeal: keeps account; import has its own page).
  if (route === 'tickets' && !hasPermission(user.role, 'canViewDealPipeline')) return fallback;
  if (route === 'ticket-detail' && !hasPermission(user.role, 'canViewWholeDeal')) return fallback;
  if (route === 'finance' && !hasPermission(user.role, 'canConfirmPayments')) return fallback;
  if (route === 'commissions' && !hasPermission(user.role, 'canViewCommissions')) return fallback;
  // The route only needs read access (canViewPayroll, hr+ceo); mutating controls inside
  // PayrollPage.jsx gate on canManagePayroll (hr-only).
  if (route === 'payroll' && !hasPermission(user.role, 'canViewPayroll')) return fallback;
  if (route === 'overtime' && !user.employeeId && !hasPermission(user.role, 'canViewAllOvertime')) return fallback;
  if (route === 'leave' && !user.employeeId && !hasPermission(user.role, 'canViewAllLeave')) return fallback;
  if (route === 'profile' && !user.employeeId) return fallback;
  return route;
}

// URL-path guards for the router, ported from the `allowedRoute` conditions above.
// `canAccessPath` returns true when `user` may see `path`; unguarded and unknown paths
// (`/`, `/attendance`) return true, the route table / `*` fallback handles those.
// Kept as a named helper so the lockdown exemption below and the guard above can never drift
// apart — the two must agree on exactly which paths this covers.
// Whoever works a delivery round without reading the deal opens /tickets/:id (and only that, no sub-path): the warehouse,
// and the invoice issuer (a per-person grant on ANY role). The bell links there.
function isRoundWorkPath(path, user) {
  const works = hasPermission(user?.role, 'canWorkDeliveryRounds') || Boolean(user?.canIssueTaxInvoice);
  return works && /^\/tickets\/\d+\/?$/.test(path.split(/[?#]/)[0]);
}

function isActivityLogPath(path) {
  return path === '/activity-log' || path.startsWith('/activity-log/');
}

const PATH_GUARDS = [
  // Admin capability, NOT a role — `admin` is orthogonal to roleFor's output and no role grants it (see V157 and
  // AuthResponse). This only decides whether the route renders; the server re-reads hr.employee.is_admin on
  // every call, so forging `admin` on the client gains nothing.
  { test: (p) => isActivityLogPath(p), can: (u) => Boolean(u.admin) },
  // Server hint (ceo/hr/team head); PeopleInsightsService re-decides the scope on every call.
  { test: (p) => p === '/people-insights', can: (u) => Boolean(u.canViewPeopleInsights) },
  { test: (p) => p === '/hr', can: (u) => hasPermission(u.role, 'canViewEmployees') },
  // The bare `/tickets` list is the deal-PIPELINE BROWSER (canViewDealPipeline: sales/sales_manager/ceo);
  // `/tickets/:id` detail is canViewWholeDeal (keeps account; import has its own /import/deals/:id page).
  // See docs/role-scoped-views.md.
  { test: (p) => p === '/employees' || p.startsWith('/employees/'), can: (u) => hasPermission(u.role, 'canViewEmployees') },
  { test: (p) => p === '/requests', can: (u) => hasPermission(u.role, 'canReviewProfileRequests') },
  // `/my-requests` is an alias that redirects to `/profile`, so it must gate identically:
  // a stricter guard would 403 an HR user following an old notification link.
  { test: (p) => p === '/my-requests' || p === '/profile', can: (u) => !!u.employeeId },
  // List = pipeline browser; a single ticket's DETAIL page is canViewWholeDeal.
  { test: (p) => p === '/tickets', can: (u) => hasPermission(u.role, 'canViewDealPipeline') || isOversightReader(u) },
  // Whole-deal page (and its sub-paths, e.g. /tickets/:id/deposit). Import is REFUSED here — the backend 403s it
  // the whole-deal read and deposit notices — and gets its own per-deal page below.
  // The warehouse and the invoice issuer get exactly /tickets/:id (RoundWorkView: one round and its files), no sub-path.
  { test: (p) => p.startsWith('/tickets/'), can: (u, p) => hasPermission(u.role, 'canViewWholeDeal') || isOversightReader(u) || isRoundWorkPath(p, u) },
  // นัดเข้าพบ: exactly the roles the visit endpoints serve (VisitService.VISIT_ROLES via canViewVisits). Exact match —
  // the page has no sub-paths. Like /tickets it is NOT on the self-service allowlist: the lock-exempt sales roles keep
  // it, every locked role loses it.
  { test: (p) => p === '/visits', can: (u) => hasPermission(u.role, 'canViewVisits') || isOversightReader(u) },
  // Import's own per-deal page (GET /api/import/deals/{id}: import + ceo). MANDATORY entry: canAccessPath fails
  // OPEN for a path no guard claims, so without this line every authenticated role would reach
  // /import/deals/:id.
  { test: (p) => p === '/import/deals' || p.startsWith('/import/deals/'), can: (u) => hasPermission(u.role, 'canViewImportDeal') || isOversightReader(u) },
  { test: (p) => p === '/catalog', can: (u) => hasPermission(u.role, 'canViewCatalog') || isOversightReader(u) },
  // Direct deal quotation. Exact + prefix (the editor's /quotations/new and /quotations/:id both
  // fall under this). Mirrors ROLE_PERMISSIONS.canViewDealQuotations OR'd with the per-employee
  // canCreateQuotation grant: a grant-holder's role need not be in that list (e.g. `qc`).
  // Per-row "own deals only" scoping for `sales` is enforced server-side, not by this guard.
  // The create page gets the editor's own create gate (sales, sales_manager, or the grant), not
  // the wider view audience below: ceo/import/account reach the list and detail pages only.
  // Pathname only, so `/quotations/new?ticket=6` is held to it too.
  { test: (p) => p.split(/[?#]/)[0] === '/quotations/new', can: (u) => canCreateDealQuotationStandalone(u) },
  { test: (p) => p === '/quotations' || p.startsWith('/quotations/'), can: (u) => hasPermission(u.role, 'canViewDealQuotations') || Boolean(u.canCreateQuotation) || isOversightReader(u) },
  { test: (p) => p === '/commissions', can: (u) => hasPermission(u.role, 'canViewCommissions') },
  // Account's money-lifecycle worklist (งานการเงิน): mirrors ROLE_PERMISSIONS.canConfirmPayments
  // (account/ceo). The prefix arm covers /finance/deals/:id: the exact match alone would leave it
  // unclaimed, and canAccessPath fails OPEN for an unclaimed path.
  // Deliberately NOT OR'd with isBillingNoteReleaseUser: AccountFinancePage.jsx has no internal
  // tab scoping, so opening this guard for a can_issue_billing_note grant holder would hand a
  // plain `employee` the WHOLE finance worklist, not just ใบวางบิล. Add the OR together with
  // the page's own tab-level gate.
  // The Express receipts upload is account-ONLY: ReceiptImportService refuses the CEO, so the page must not be
  // reachable by URL for them. Listed before the /finance prefix rule (first match wins).
  { test: (p) => p.split(/[?#]/)[0] === '/finance/receipts-import', can: (u) => u.role === 'account' },
  { test: (p) => p === '/finance' || p.startsWith('/finance/'), can: (u) => hasPermission(u.role, 'canConfirmPayments') || isOversightReader(u) },
  // Mirrors PayrollController exactly: every GET plus the non-persisting POST /preview and
  // /preview/export/{kind} are hasAnyRole('HR','CEO'); every write stays hr-only and is gated
  // inside PayrollPage.jsx (canManagePayroll), not at the route level.
  { test: (p) => p === '/payroll', can: (u) => hasPermission(u.role, 'canViewPayroll') },
  // Garnishment shortfall ledger. Needs its own entry because the guard above is an EXACT match and
  // canAccessPath fails OPEN for a path no guard claims — a nested path would otherwise be reachable by any
  // authenticated role.
  { test: (p) => p === '/payroll/deduction-shortfalls', can: (u) => hasPermission(u.role, 'canViewPayroll') },
  // Written-consent register. Needs its own entry for the same reason as the sibling above:
  // `/payroll` is an EXACT match and canAccessPath fails OPEN for a path no guard claims.
  // canViewPayroll (hr + ceo) mirrors the GET's @PreAuthorize("hasAnyRole('HR','CEO')") and
  // DeductionWrittenConsentService.VIEW_ROLES. The narrower WRITE gate (hasRole('HR'), EDIT_ROLES)
  // is enforced inside the page on canManagePayroll: a route guard cannot express it, since CEO
  // must reach the page read-only.
  { test: (p) => p === '/payroll/deduction-consents', can: (u) => hasPermission(u.role, 'canViewPayroll') },
  // ล.ย.01 tax-allowance declaration. `/tax-allowance` is the
  // employee's own declaration form — same "must have an employeeId" shape as
  // `/profile` above, and just as important to guard explicitly: unknown paths
  // fail OPEN in canAccessPath (the `if (!guard) return true` below), so omitting
  // this entry would let any logged-in user without an employeeId reach it.
  { test: (p) => p === '/tax-allowance', can: (u) => !!u.employeeId },
  // `/tax-allowance-review` is HR/CEO's register — mirrors TaxAllowanceDeclarationService's GET /declarations
  // gate (hasAnyRole('HR','CEO')).
  { test: (p) => p === '/tax-allowance-review', can: (u) => hasPermission(u.role, 'canViewTaxAllowanceRegister') },
  // /employee-requests hosts both the overtime and welfare/special-money tabs (RequestsPage.jsx), so it is
  // visible to anyone either sub-page would be visible to. /overtime stays guarded identically since it's a
  // same-page redirect alias (App.jsx), not a separate view.
  {
    test: (p) => p === '/employee-requests' || p === '/overtime',
    can: (u) => !!u.employeeId || hasPermission(u.role, 'canViewAllOvertime') || hasPermission(u.role, 'canViewAllSpecialMoney'),
  },
  { test: (p) => p === '/leave', can: (u) => !!u.employeeId || hasPermission(u.role, 'canViewAllLeave') },
  // /leave/new: the same audience as /leave, minus hr/ceo: they do not file leave
  // (LeaveService SELF_SUBMIT_BLOCKED_ROLES), so the composer is refused outright.
  // Inlined rather than importing canSubmitOwnLeave: leaveSurfaceTabs.js imports this module.
  { test: (p) => p === '/leave/new', can: (u) => (!!u.employeeId || hasPermission(u.role, 'canViewAllLeave')) && u.role !== 'hr' && u.role !== 'ceo' },
  { test: (p) => p === '/price-import', can: (u) => hasPermission(u.role, 'canManagePriceImport') },
  // The bare queue (`/pricing-requests`) is Import's work list: canViewPricingRequestQueue
  // only (import/ceo/sales_manager), never sales. Detail sub-paths are a separate rule: a sales
  // rep needs to reach their OWN request's `/pricing-requests/:id` (PICKED_UP/MORE_INFO_REQUIRED
  // notifications link there), but the backend's requireViewable ownership check is what
  // enforces per-request access; this guard only decides whether the role belongs on the URL shape.
  { test: (p) => p === '/pricing-requests', can: (u) => hasPermission(u.role, 'canViewPricingRequestQueue') || isOversightReader(u) },
  { test: (p) => p.startsWith('/pricing-requests/'), can: (u) => hasPermission(u.role, 'canViewPricingRequestQueue') || u.role === 'sales' || isOversightReader(u) },
  // งานนำเข้า — Import's cross-deal fulfilment workspace. Frontend gating only — the four endpoints enforce the
  // real gate and are unchanged. Mirrors ROLE_PERMISSIONS.canActOnFulfilment, itself a mirror of
  // TicketService.FULFILMENT_ROLES {import, ceo}.
  // ของที่ฉันสั่ง: MyIncomingService serves sales, sales_manager, ceo and read-all holders (OversightReader).
  { test: (p) => p === '/my-orders', can: (u) => hasPermission(u.role, 'canViewMyOrders') || isOversightReader(u) || Boolean(u.canCreateQuotation) || Boolean(u.canSeeShowroomOrders) },
  { test: (p) => p === '/fulfilment' || p.startsWith('/fulfilment/'), can: (u) => hasPermission(u.role, 'canActOnFulfilment') || isOversightReader(u) },
  // Matches the launcher's nav condition exactly (AppShell.jsx: `role === 'ceo'`).
  { test: (p) => p === '/ceo-settings', can: (u) => u.role === 'ceo' },
  // Attendance calendar admin (hr.holiday / hr.work_schedule / hr.work_schedule_assignment CRUD).
  // Mirrors the three controllers' requireAnyRole(user, "hr", "ceo") exactly. Frontend gating
  // only: see ROLE_PERMISSIONS.canManageAttendanceCalendar in routes.js for the same caveat.
  { test: (p) => p === '/settings/attendance-calendar', can: (u) => hasPermission(u.role, 'canManageAttendanceCalendar') },
  // §5 announcement PDF upload (LeaveController#uploadPolicyDocument's requireAnyRole(user, "hr",
  // "ceo")). Its own entry is required: canAccessPath fails OPEN for any path no guard claims,
  // and `/settings/attendance-calendar` above is an EXACT match, so a second `/settings/*` route
  // without its own line would be reachable by every authenticated role. Frontend gating only: see
  // ROLE_PERMISSIONS.canManageLeavePolicyDocument for the same caveat.
  { test: (p) => p === '/settings/leave-policy', can: (u) => hasPermission(u.role, 'canManageLeavePolicyDocument') },
];

// Release lockdown (SELF_SERVICE_ONLY, app/features.js). HR and CEO keep the
// full portal: HR runs payroll and works the ล.ย.01 / profile-request
// registers, and CEO reads them.
//
// Sales and sales_manager are exempt too: they get back exactly what PATH_GUARDS already grants
// them (the sales stack; never payroll or the HR registers). import and account stay
// locked but get a per-role allowlist (RELEASED_PATHS_BY_ROLE below): import
// sees งานนำเข้า and คิวขอราคา only, account sees การเงิน only. Every other
// role (warehouse, qc, employee, and a division manager: role `employee` +
// manager flag) is confined to self-service.
//
// A division manager keeps their approval work despite being locked: leave and
// OT approval live INSIDE /leave and /employee-requests, both self-service
// paths, not on the DivisionManagerOverview landing this hides.
const SELF_SERVICE_EXEMPT_ROLES = ['hr', 'ceo', 'sales', 'sales_manager'];

// Per-role release allowlist for roles that stay locked. Same prefix rule as
// SELF_SERVICE_PATHS (the trailing slash is required, so `/finance` does not
// admit `/finance-archive`). `/import/deals` is the queue's own per-deal
// drill-down (dealRedirectFor), not a wider surface.
const RELEASED_PATHS_BY_ROLE = {
  import: ['/fulfilment', '/pricing-requests', '/import/deals'],
  account: ['/finance'],
};

export function isReleasedPath(path, user) {
  const pathname = path.split(/[?#]/)[0];
  return (RELEASED_PATHS_BY_ROLE[user?.role] ?? [])
    .some((allowed) => pathname === allowed || pathname.startsWith(`${allowed}/`));
}

export function isSelfServiceLocked(user) {
  if (!SELF_SERVICE_ONLY) return false;
  return !SELF_SERVICE_EXEMPT_ROLES.includes(user?.role);
}

// Locked roles that still get their own dashboard at `/` (owner ruling 2026-11-17): import lands on
// ImportOverview, account on AccountOverview. They stay locked; RELEASED_PATHS_BY_ROLE still bounds
// where they can go. Only while SALES_ENABLED, since those dashboards are sales screens.
const OWN_LANDING_LOCKED_ROLES = ['import', 'account'];

/** Whether `/` is the self-service landing for `user`: locked, and not a role that keeps its own dashboard.
 * The landing only; path access is isSelfServiceLocked + canAccessPath. */
export function landsOnSelfService(user) {
  return isSelfServiceLocked(user) && !(SALES_ENABLED && OWN_LANDING_LOCKED_ROLES.includes(user?.role));
}

// The lockdown is an ALLOWLIST, not a blocklist, and that direction is the whole point. The prefix test
// requires the trailing slash, so `/tax-allowance` does NOT admit `/tax-allowance-review` — HR's register,
// which must stay hidden.
const SELF_SERVICE_PATHS = [
  '/profile',
  // Alias that redirects to /profile (App.jsx) — must be allowed identically or an old notification deep-link
  // dies at the access-denied page.
  '/my-requests',
  '/attendance',
  '/leave',
  '/employee-requests',
  // Same reasoning as /my-requests: /overtime is a redirect alias for /employee-requests?tab=ot, and
  // OvertimeService hardcodes it in notification links.
  '/overtime',
  '/tax-allowance',
  // มีอะไรใหม่ release notice (/whats-new and /whats-new/:releaseId): every employee's bell row and email link here.
  '/whats-new',
];

export function isSelfServicePath(path) {
  // Match on the pathname alone. Callers pass bare paths, but `/employee-requests?tab=ot` (the
  // shape the /overtime alias and EmployeeSelfService's OT card use) would otherwise be refused
  // and send a user to the access-denied page for their own OT tab. This can only admit a path
  // whose pathname is already on the list, so it cannot widen the lock.
  const pathname = path.split(/[?#]/)[0];
  if (pathname === '/') return true;
  return SELF_SERVICE_PATHS.some((allowed) => pathname === allowed || pathname.startsWith(`${allowed}/`));
}

// Quotation release: the direct-deal QUOTATION pages open through the lock for
// the people who create and approve quotations, and ONLY those pages; /tickets, /customers,
// /catalog, /pricing-requests and the rest of the sales stack stay locked. The audience mirrors
// DealQuotationService: EDIT_ROLES (sales, sales_manager), APPROVE_ROLES (sales_manager, plus ceo,
// who is exempt from the lock anyway), and the per-employee can_create_quotation grant (which
// covers a grantee whose role is none of these, e.g. qc). The service's VIEW-only roles (import,
// account) are deliberately NOT included: they read quotations, they do not create them.
// Per-path like the activity-log exemption below, never by exempting roles, which would unlock
// the whole sales stack as a side effect. Frontend gating only; the backend enforces the rest.
const QUOTATION_RELEASE_ROLES = ['sales', 'sales_manager'];

export function isQuotationReleaseUser(user) {
  return QUOTATION_RELEASE_ROLES.includes(user?.role) || Boolean(user?.canCreateQuotation);
}

function isQuotationPath(path) {
  // Pathname only, and the prefix needs its slash: `/quotations-archive` must not ride along.
  const pathname = path.split(/[?#]/)[0];
  return pathname === '/quotations' || pathname.startsWith('/quotations/');
}

// Billing-note release predicate: identifies a holder of the per-employee can_issue_billing_note
// grant (BillingNoteService.hasWriteGrant), which, like canCreateQuotation above, can land on a
// role none of the other guards would admit (e.g. a plain `employee`). Grant-driven, not
// role-driven, same shape as isQuotationReleaseUser, and deliberately NOT account/ceo here:
// `account` is NOT exempt from the self-service lockdown (only hr/ceo are,
// SELF_SERVICE_EXEMPT_ROLES above), so a caller wiring this predicate into a lockdown exemption
// must never let it un-hide a path for a plain account role while locked.
//
// NOT YET WIRED into PATH_GUARDS, the lockdown bypass below, or AppShell's nav: AccountFinancePage.jsx
// has no internal tab-level scoping, so opening /finance's route guard for a grant holder would
// expose the WHOLE finance worklist, not just ใบวางบิล. Wire all three together with that tab gate.
export function isBillingNoteReleaseUser(user) {
  return Boolean(user?.canIssueBillingNote);
}

// Role -> its own per-deal page, and whether `/tickets/:id/<sub>` (the deposit page) follows.
const DEAL_REDIRECTS = {
  // `/tickets/:id/deposit` has no import equivalent and stays refused.
  import: { prefix: '/import/deals', subPaths: false },
  // Account works the deposit through the finance deal page. Under the self-service lock this is
  // the ONLY way account's deal links land: RequireAccess refuses /tickets/* before
  // TicketDetailRoute's own account redirect can mount (DepositNoticeService notifies account
  // with a /tickets/:id link).
  account: { prefix: '/finance/deals', subPaths: true },
};

/**
 * Where a user refused a whole-deal URL (`/tickets/:id`) should go instead: their role's own
 * per-deal page. Backend notification deep-links and old bookmarks still point at `/tickets/:id`,
 * and a dead-end refusal there helps nobody. `/tickets` (the pipeline browser) never redirects.
 * Returns null when no redirect applies. Routing convenience only; the backend is the authority.
 */
export function dealRedirectFor(path, user) {
  const target = DEAL_REDIRECTS[user?.role];
  if (!target) return null;
  const match = /^\/tickets\/(\d+)(\/[^/]+)?\/?$/.exec(path.split(/[?#]/)[0]);
  if (!match || (match[2] && !target.subPaths)) return null;
  return `${target.prefix}/${match[1]}`;
}

// The read pages an oversight reader (sales_manager, or a read-all grant holder) opens: the 7 nav pages, the deal page, and the
// drill-downs they link to. The lock exemption below and AppShell's nav filter share it, so the menu and the guard cannot drift.
// receipts-import (account only) and commissions are never in it.
const READER_PATH_PREFIXES = ['/tickets', '/quotations', '/catalog', '/visits', '/my-orders', '/pricing-requests', '/fulfilment', '/import/deals', '/finance'];

export function isReaderPath(path) {
  const pathname = path.split(/[?#]/)[0];
  if (pathname === '/finance/receipts-import') return false;
  return READER_PATH_PREFIXES.some((prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`));
}

// /my-orders for a plain employee holding the no-project-order or showroom grant, which the page's own guard admits.
export function isMyOrdersGrantUser(user) {
  return Boolean(user?.canCreateQuotation || user?.canSeeShowroomOrders);
}

// Per-path lock exemptions: each opens exactly one path family for the holder of one capability, never a role.
// (A team head is not hr/ceo, so the lock would otherwise hide their one page.)
const LOCK_EXEMPTIONS = [
  { test: isActivityLogPath, can: (u) => Boolean(u.admin) },
  { test: isQuotationPath, can: isQuotationReleaseUser },
  // The warehouse and the invoice issuer stay locked to self-service except for the one page their bell notices open.
  { test: (p) => p.startsWith('/tickets/'), can: (u, p) => isRoundWorkPath(p, u) },
  { test: (p) => p === '/people-insights', can: (u) => Boolean(u.canViewPeopleInsights) },
  // v2.3.0 read-all grant: exactly the oversight reader's read pages, one capability, never a role. Not /finance/receipts-import (account only), not /commissions.
  { test: isReaderPath, can: (u) => isOversightReader(u) },
  // The grants that open ของที่ฉันสั่ง (no-project orders, showroom orders): this one path, no reader pages.
  { test: (p) => p.split(/[?#]/)[0] === '/my-orders', can: isMyOrdersGrantUser },
];

export function canAccessPath(path, user) {
  if (!user) return false;
  // Checked BEFORE the guards below, not after: the lockdown has to deny paths the user's role would otherwise
  // pass (a sales rep on /tickets) as well as paths no guard claims at all. Narrow exemption, deliberate.
  if (isSelfServiceLocked(user) && !isSelfServicePath(path)
    && !LOCK_EXEMPTIONS.some((entry) => entry.test(path) && entry.can(user, path))
    && !isReleasedPath(path, user)) return false;
  const guard = PATH_GUARDS.find((entry) => entry.test(path));
  if (!guard) return true;
  return guard.can(user, path);
}
