import { afterEach, describe, it, expect, vi } from 'vitest';
import { hasPermission, allowedRoute, canAccessPath, isDivisionManager } from './permissions.js';

describe('hasPermission', () => {
  it('grants a permission listed for the role', () => {
    expect(hasPermission('hr', 'canViewEmployees')).toBe(true);
  });

  it('denies a permission not listed for the role', () => {
    expect(hasPermission('employee', 'canViewEmployees')).toBe(false);
  });

  it('returns false for an unknown permission key', () => {
    expect(hasPermission('hr', 'canDoSomethingImaginary')).toBe(false);
  });

  it('scopes price import to ceo/import only (#205)', () => {
    expect(hasPermission('ceo', 'canManagePriceImport')).toBe(true);
    expect(hasPermission('import', 'canManagePriceImport')).toBe(true);
    expect(hasPermission('sales', 'canManagePriceImport')).toBe(false);
    expect(hasPermission('sales_manager', 'canManagePriceImport')).toBe(false);
    expect(hasPermission('admin', 'canManagePriceImport')).toBe(false);
  });

  it('scopes catalog product writes to ceo/import only (#205)', () => {
    expect(hasPermission('ceo', 'canManageCatalogProducts')).toBe(true);
    expect(hasPermission('import', 'canManageCatalogProducts')).toBe(true);
    expect(hasPermission('sales', 'canManageCatalogProducts')).toBe(false);
    expect(hasPermission('employee', 'canManageCatalogProducts')).toBe(false);
  });

  it('scopes the deal-pipeline BROWSER to sales/sales_manager/ceo, excluding account (Account role-scoped views)', () => {
    expect(hasPermission('sales', 'canViewDealPipeline')).toBe(true);
    expect(hasPermission('sales_manager', 'canViewDealPipeline')).toBe(true);
    expect(hasPermission('ceo', 'canViewDealPipeline')).toBe(true);
    expect(hasPermission('account', 'canViewDealPipeline')).toBe(false);
    expect(hasPermission('import', 'canViewDealPipeline')).toBe(false);
  });

  // Role-scoped views (docs/role-scoped-views.md): the deal PIPELINE BROWSER
  // (canViewDealPipeline) is narrower than plain ticket-detail read
  // (canViewTickets) — import and account both keep detail-read but lose the
  // pipeline browser; sales/sales_manager/ceo keep both.
  it('keeps canViewTickets (detail-read) broader than canViewDealPipeline — import/account keep detail-read only', () => {
    expect(hasPermission('import', 'canViewTickets')).toBe(true);
    expect(hasPermission('sales', 'canViewTickets')).toBe(true);
    expect(hasPermission('account', 'canViewTickets')).toBe(true);
    expect(hasPermission('ceo', 'canViewTickets')).toBe(true);
    expect(hasPermission('sales_manager', 'canViewTickets')).toBe(true);
  });

  it('scopes sensitive employee data (salary, salary history, PDPA tab) to hr only (UX-09)', () => {
    expect(hasPermission('hr', 'canViewSensitiveEmployeeData')).toBe(true);
    expect(hasPermission('employee', 'canViewSensitiveEmployeeData')).toBe(false);
    expect(hasPermission('sales', 'canViewSensitiveEmployeeData')).toBe(false);
    expect(hasPermission('sales_manager', 'canViewSensitiveEmployeeData')).toBe(false);
    expect(hasPermission('ceo', 'canViewSensitiveEmployeeData')).toBe(false);
    expect(hasPermission('import', 'canViewSensitiveEmployeeData')).toBe(false);
    expect(hasPermission('account', 'canViewSensitiveEmployeeData')).toBe(false);
  });
});

describe('allowedRoute', () => {
  const hr = { role: 'hr', employeeId: 10 };
  const employee = { role: 'employee', employeeId: 5 };
  const sales = { role: 'sales', employeeId: 9 };

  it('falls back to the dashboard when there is no user', () => {
    expect(allowedRoute('employees', null)).toBe('dashboard');
  });

  it('lets HR reach employee and request routes', () => {
    expect(allowedRoute('employees', hr)).toBe('employees');
    expect(allowedRoute('requests', hr)).toBe('requests');
    expect(allowedRoute('payroll', hr)).toBe('payroll');
  });

  // Split (issue #390): allowedRoute mirrors canAccessPath's canViewPayroll gate.
  it('lets CEO reach the payroll route too (read-only oversight, #390)', () => {
    const ceo = { role: 'ceo', employeeId: 1 };
    expect(allowedRoute('payroll', ceo)).toBe('payroll');
  });

  it('blocks an employee from HR-only routes', () => {
    expect(allowedRoute('employees', employee)).toBe('dashboard');
    expect(allowedRoute('requests', employee)).toBe('dashboard');
    expect(allowedRoute('payroll', employee)).toBe('dashboard');
  });

  it('scopes ticket routes to ticket roles', () => {
    expect(allowedRoute('tickets', sales)).toBe('tickets');
    expect(allowedRoute('tickets', employee)).toBe('dashboard');
  });

  it('allows self-service routes for any user linked to an employee', () => {
    expect(allowedRoute('profile', employee)).toBe('profile');
    // overtime is allowed for a linked employee even without the view-all permission
    expect(allowedRoute('overtime', employee)).toBe('overtime');
  });

  it('blocks self-service routes when the user is not linked to an employee', () => {
    const detached = { role: 'sales', employeeId: null };
    expect(allowedRoute('profile', detached)).toBe('dashboard');
    expect(allowedRoute('overtime', detached)).toBe('dashboard');
  });

  it('passes through a route the user is allowed to see', () => {
    expect(allowedRoute('dashboard', employee)).toBe('dashboard');
  });
});

describe('canAccessPath', () => {
  const hr = { role: 'hr', employeeId: 10 };
  const employee = { role: 'employee', employeeId: 5 };
  const sales = { role: 'sales', employeeId: 9 };
  const ceo = { role: 'ceo', employeeId: 1 };
  const importer = { role: 'import', employeeId: 2 };
  const account = { role: 'account', employeeId: 3 };

  it('denies every path when there is no user', () => {
    expect(canAccessPath('/employees', null)).toBe(false);
    expect(canAccessPath('/', null)).toBe(false);
  });

  it('lets HR reach employee, request, and payroll paths', () => {
    expect(canAccessPath('/employees', hr)).toBe(true);
    expect(canAccessPath('/employees/5', hr)).toBe(true);
    expect(canAccessPath('/requests', hr)).toBe(true);
    expect(canAccessPath('/payroll', hr)).toBe(true);
    expect(canAccessPath('/hr', hr)).toBe(true);
  });

  it('blocks an employee from HR-only paths', () => {
    expect(canAccessPath('/employees', employee)).toBe(false);
    expect(canAccessPath('/employees/5', employee)).toBe(false);
    expect(canAccessPath('/requests', employee)).toBe(false);
    expect(canAccessPath('/payroll', employee)).toBe(false);
    expect(canAccessPath('/hr', employee)).toBe(false);
  });

  // ── Nested /payroll paths: the fail-open case ─────────────────────────────
  // canAccessPath returns TRUE for any path no guard claims (`if (!guard) return true`), and the
  // `/payroll` guard is an EXACT match — so a nested payroll route without its own PATH_GUARDS
  // entry is reachable by every authenticated role, including `employee` and `sales`. That is not
  // hypothetical: it is what PR #768 found when it added the shortfall ledger.
  //
  // Written wrong-way-round on purpose (CLAUDE.md): the assertions that matter are the refusals.
  // Deleting either PATH_GUARDS entry makes this test fail — verified by mutation check, not
  // assumed.
  //
  // FRONTEND ROUTING ONLY. This proves which roles the SPA will route; it is not evidence about the
  // server. The backend enforces the same split independently —
  // @PreAuthorize("hasAnyRole('HR','CEO')") on the GET, hasRole('HR') on the PUT — and that is
  // where the real gate lives.
  it('refuses every non-payroll role on the nested payroll paths', () => {
    for (const path of ['/payroll/deduction-consents', '/payroll/deduction-shortfalls']) {
      expect(canAccessPath(path, employee)).toBe(false);
      expect(canAccessPath(path, sales)).toBe(false);
      expect(canAccessPath(path, importer)).toBe(false);
      expect(canAccessPath(path, account)).toBe(false);
    }
  });

  it('lets HR and CEO reach the nested payroll paths, mirroring canViewPayroll', () => {
    for (const path of ['/payroll/deduction-consents', '/payroll/deduction-shortfalls']) {
      expect(canAccessPath(path, hr)).toBe(true);
      expect(canAccessPath(path, ceo)).toBe(true);
    }
  });

  // Same fail-open shape one directory over: `/settings/attendance-calendar` is an EXACT match, so
  // a second `/settings/*` route with no PATH_GUARDS entry of its own would be reachable by every
  // authenticated role. Mirrors LeaveController#uploadPolicyDocument's requireAnyRole(hr, ceo).
  //
  // FRONTEND ROUTING ONLY — the server enforces its own gate, and that is not tested here.
  it('refuses everyone but hr/ceo on the leave-policy upload settings route', () => {
    expect(canAccessPath('/settings/leave-policy', employee)).toBe(false);
    expect(canAccessPath('/settings/leave-policy', sales)).toBe(false);
    expect(canAccessPath('/settings/leave-policy', importer)).toBe(false);
    expect(canAccessPath('/settings/leave-policy', account)).toBe(false);
  });

  it('lets hr and ceo reach the leave-policy upload settings route', () => {
    expect(canAccessPath('/settings/leave-policy', hr)).toBe(true);
    expect(canAccessPath('/settings/leave-policy', ceo)).toBe(true);
  });

  // ── /fulfilment (งานนำเข้า) — the same fail-open shape a third time ────────
  // A brand-new path with no PATH_GUARDS entry is reachable by EVERY authenticated
  // role, so these refusals are the assertions that matter. Written wrong-way-round
  // per CLAUDE.md: deleting the guard entry makes the first test fail.
  //
  // FRONTEND ROUTING ONLY. This proves which roles the SPA will route to the page;
  // it is NOT evidence about the server. The real gate is
  // TicketService.FULFILMENT_ROLES {import, ceo} on the four endpoints the page
  // calls, unchanged by this branch and NOT verified here.
  it('refuses every role but import/ceo on the fulfilment workspace', () => {
    expect(canAccessPath('/fulfilment', employee)).toBe(false);
    expect(canAccessPath('/fulfilment', sales)).toBe(false);
    expect(canAccessPath('/fulfilment', hr)).toBe(false);
    expect(canAccessPath('/fulfilment', account)).toBe(false);
    // Narrower than its nav neighbour /pricing-requests, which DOES admit
    // sales_manager — a sales_manager here would find every button 403s.
    expect(canAccessPath('/fulfilment', { role: 'sales_manager', employeeId: 8 })).toBe(false);
    expect(canAccessPath('/pricing-requests', { role: 'sales_manager', employeeId: 8 })).toBe(true);
  });

  it('lets import and ceo reach the fulfilment workspace, mirroring FULFILMENT_ROLES', () => {
    expect(canAccessPath('/fulfilment', importer)).toBe(true);
    expect(canAccessPath('/fulfilment', ceo)).toBe(true);
  });

  it('scopes ticket paths to ticket roles', () => {
    expect(canAccessPath('/tickets', sales)).toBe(true);
    expect(canAccessPath('/tickets/12', sales)).toBe(true);
    expect(canAccessPath('/tickets/12/deposit', sales)).toBe(true);
    expect(canAccessPath('/tickets', employee)).toBe(false);
  });

  // Account role-scoped views: the pipeline BROWSER (the `/tickets` list) is
  // narrower than ticket-DETAIL read (`/tickets/:id`) — account keeps the
  // latter (its worklist deep-links straight to a single deal) but loses the
  // former (its own งานการเงิน worklist replaces it).
  it('splits the deal-pipeline browser from ticket-detail read for account', () => {
    expect(canAccessPath('/tickets', account)).toBe(false);
    expect(canAccessPath('/tickets/12', account)).toBe(true);
    expect(canAccessPath('/tickets/12/deposit', account)).toBe(true);
    // sales/sales_manager/ceo are unaffected by the split — they still see everything.
    expect(canAccessPath('/tickets', ceo)).toBe(true);
  });

  it('scopes /finance to canConfirmPayments (account/ceo)', () => {
    expect(canAccessPath('/finance', account)).toBe(true);
    expect(canAccessPath('/finance', ceo)).toBe(true);
    expect(canAccessPath('/finance', sales)).toBe(false);
    expect(canAccessPath('/finance', employee)).toBe(false);
  });

  it('allows self-service paths for any user linked to an employee', () => {
    expect(canAccessPath('/profile', employee)).toBe(true);
    // overtime/leave are allowed for a linked employee even without the view-all permission
    expect(canAccessPath('/overtime', employee)).toBe(true);
    expect(canAccessPath('/leave', employee)).toBe(true);
  });

  it('blocks self-service paths when the user is not linked to an employee', () => {
    const detached = { role: 'sales', employeeId: null };
    expect(canAccessPath('/profile', detached)).toBe(false);
    expect(canAccessPath('/overtime', detached)).toBe(false);
    expect(canAccessPath('/leave', detached)).toBe(false);
  });

  // /my-requests is now an alias redirecting to /profile, so it must gate
  // identically — anyone with an employee record, not just submitters. A
  // stricter guard would 403 an HR user following an old notification link.
  it('gates my-requests exactly like its /profile redirect target', () => {
    expect(canAccessPath('/my-requests', employee)).toBe(true);
    expect(canAccessPath('/my-requests', hr)).toBe(true);
    expect(canAccessPath('/profile', hr)).toBe(true);
    expect(canAccessPath('/my-requests', { role: 'sales', employeeId: null })).toBe(false);
  });

  // Split (issue #390): PayrollController mirrors -- every GET, plus the non-persisting POST
  // /preview and /preview/export/{kind}, is hasAnyRole('HR','CEO'), so the ROUTE only needs
  // canViewPayroll (hr+ceo). Every write stays canManagePayroll (hr-only) and is gated inside
  // PayrollPage.jsx itself, not at the route level -- ceo reaching '/payroll' here is read-only
  // oversight, not the manage capability.
  it('lets CEO reach /payroll for read-only oversight, same route HR uses to manage it (#390)', () => {
    expect(canAccessPath('/payroll', hr)).toBe(true);
    expect(canAccessPath('/payroll', ceo)).toBe(true);
    expect(hasPermission('ceo', 'canViewPayroll')).toBe(true);
    expect(hasPermission('ceo', 'canManagePayroll')).toBe(false);
    expect(hasPermission('hr', 'canManagePayroll')).toBe(true);
    // Every other role stays blocked from the route entirely, same as before the split.
    expect(canAccessPath('/payroll', sales)).toBe(false);
    expect(canAccessPath('/payroll', employee)).toBe(false);
    expect(canAccessPath('/payroll', importer)).toBe(false);
    expect(canAccessPath('/payroll', account)).toBe(false);
  });

  it('scopes commissions to commission-viewing roles', () => {
    expect(canAccessPath('/commissions', sales)).toBe(true);
    expect(canAccessPath('/commissions', employee)).toBe(false);
  });

  it('scopes price-import to ceo/import only (#205)', () => {
    expect(canAccessPath('/price-import', ceo)).toBe(true);
    expect(canAccessPath('/price-import', importer)).toBe(true);
    expect(canAccessPath('/price-import', sales)).toBe(false);
    expect(canAccessPath('/price-import', employee)).toBe(false);
    expect(canAccessPath('/price-import', hr)).toBe(false);
  });

  it('allows unguarded and unknown paths for any authenticated user', () => {
    // `/` and `/attendance` are intentionally open to every authenticated user
    // (UX-20 tracks whether that should change) — not covered by this fix.
    expect(canAccessPath('/', employee)).toBe(true);
    expect(canAccessPath('/attendance', employee)).toBe(true);
  });

  // COMMIT 6 review-remediation regression test: the two rules used to be one
  // combined `p === '/pricing-requests' || p.startsWith('/pricing-requests/')`
  // rule with `... || u.role === 'sales'`, which let sales reach the bare
  // queue too (App.test.jsx's route-guard test caught this). The queue is
  // Import's work list; sales only needs the per-request detail sub-path
  // (PICKED_UP/MORE_INFO_REQUIRED notifications link there — this guard only
  // decides the URL shape, not per-request ownership, which the backend's
  // requireViewable enforces).
  it('scopes the bare pricing-requests queue to canViewPricingRequestQueue roles, never sales', () => {
    expect(canAccessPath('/pricing-requests', importer)).toBe(true);
    expect(canAccessPath('/pricing-requests', ceo)).toBe(true);
    expect(canAccessPath('/pricing-requests', { role: 'sales_manager', employeeId: 4 })).toBe(true);
    expect(canAccessPath('/pricing-requests', sales)).toBe(false);
    expect(canAccessPath('/pricing-requests', employee)).toBe(false);
  });

  it('lets sales reach an individual pricing-request detail sub-path even though the queue is closed to them', () => {
    expect(canAccessPath('/pricing-requests/42', sales)).toBe(true);
    expect(canAccessPath('/pricing-requests/42', importer)).toBe(true);
    expect(canAccessPath('/pricing-requests/42', employee)).toBe(false);
  });

  it('gates ceo-settings to the ceo role, matching the sidebar nav condition (UX-19)', () => {
    expect(canAccessPath('/ceo-settings', ceo)).toBe(true);
    expect(canAccessPath('/ceo-settings', hr)).toBe(false);
    expect(canAccessPath('/ceo-settings', employee)).toBe(false);
    expect(canAccessPath('/ceo-settings', sales)).toBe(false);
    expect(canAccessPath('/ceo-settings', importer)).toBe(false);
    expect(canAccessPath('/ceo-settings', { role: 'account', employeeId: 3 })).toBe(false);
    expect(canAccessPath('/ceo-settings', { role: 'sales_manager', employeeId: 4 })).toBe(false);
  });

  // Role-scoped views (Import build): import is blocked from the pipeline
  // BROWSER (/tickets exact) but keeps ticket-detail read (/tickets/:id).
  it('blocks import from the deal-pipeline browser but allows ticket detail', () => {
    expect(canAccessPath('/tickets', importer)).toBe(false);
    expect(canAccessPath('/tickets/12', importer)).toBe(true);
    expect(canAccessPath('/tickets/12/deposit', importer)).toBe(true);
  });

  // /procurement and /factory-purchase-orders(/:id) were removed 2026-08-11
  // (owner ruling — see AppShell.test.jsx). Their PATH_GUARDS entries went with
  // them, so canAccessPath now falls through to its unguarded default (`true`)
  // for every role — including sales/account, which the deleted guard refused.
  // That is not a widening: App.jsx has no route at either path any more, so
  // both fall to the `path="*"` catch-all and redirect to '/'. This asserts the
  // guard is really GONE rather than silently left behind pointing at nothing.
  it('leaves no guard behind for the removed procurement routes', () => {
    for (const path of ['/procurement', '/factory-purchase-orders', '/factory-purchase-orders/1']) {
      for (const user of [importer, ceo, sales, { role: 'account', employeeId: 3 }]) {
        expect(canAccessPath(path, user)).toBe(true);
      }
    }
  });

  it('keeps sales/sales_manager/ceo on the deal-pipeline browser', () => {
    expect(canAccessPath('/tickets', sales)).toBe(true);
    expect(canAccessPath('/tickets', ceo)).toBe(true);
    expect(canAccessPath('/tickets', { role: 'sales_manager', employeeId: 4 })).toBe(true);
  });
});

describe('isDivisionManager', () => {
  it('is true for role employee with the manager flag set', () => {
    expect(isDivisionManager({ role: 'employee', manager: true, employeeId: 10 })).toBe(true);
  });

  it('is false for a plain employee (no manager flag)', () => {
    expect(isDivisionManager({ role: 'employee', manager: false, employeeId: 10 })).toBe(false);
    expect(isDivisionManager({ role: 'employee', employeeId: 10 })).toBe(false);
  });

  // Narrowed to role 'employee' on purpose: sales_manager/hr/ceo have their own
  // dedicated landings (company mode / a future sales-manager Overview), so a
  // manager flag on those roles must not also route them here.
  it('is false for other roles even when the manager flag is set', () => {
    expect(isDivisionManager({ role: 'sales_manager', manager: true, employeeId: 11 })).toBe(false);
    expect(isDivisionManager({ role: 'hr', manager: true, employeeId: 12 })).toBe(false);
    expect(isDivisionManager({ role: 'ceo', manager: true, employeeId: 13 })).toBe(false);
  });

  it('is false for no user', () => {
    expect(isDivisionManager(null)).toBe(false);
    expect(isDivisionManager(undefined)).toBe(false);
  });
});

// Release lockdown (SELF_SERVICE_ONLY — app/features.js). features.js reads
// import.meta.env once, at module-load time, and vitest.config.js pins the flag
// OFF for the whole suite so the ~1300 tests above keep exercising the full
// product. Turning it back ON therefore needs vi.stubEnv + vi.resetModules + a
// dynamic import of a fresh permissions.js: the static `import` at the top of
// this file already evaluated with the flag off and can only ever prove the
// unlocked case.
describe('canAccessPath under the release lockdown', () => {
  const sales = { role: 'sales', employeeId: 41 };
  const salesManager = { role: 'sales_manager', employeeId: 42 };
  const importer = { role: 'import', employeeId: 43 };
  const account = { role: 'account', employeeId: 44 };
  const employee = { role: 'employee', employeeId: 45 };
  const divisionManager = { role: 'employee', employeeId: 46, manager: true };
  const hr = { role: 'hr', employeeId: 47 };
  const ceo = { role: 'ceo', employeeId: 48 };

  const LOCKED_ROLES = [sales, salesManager, importer, account, employee, divisionManager];

  // Paths the lock must REMOVE. Asserted wrong-way-round on purpose: each of
  // these is a page the role could otherwise reach, so the test fails if the
  // lock stops applying rather than merely if a permission changes.
  const HIDDEN = [
    '/hr',
    '/employees',
    '/employees/12',
    '/requests',
    '/tax-allowance-review',
    '/payroll',
    '/payroll/deduction-shortfalls',
    '/payroll/deduction-consents',
    '/settings/attendance-calendar',
    '/settings/leave-policy',
    '/tickets',
    '/tickets/7',
    '/tickets/7/deposit',
    '/pricing-requests',
    '/pricing-requests/9',
    '/fulfilment',
    '/commissions',
    '/finance',
    '/catalog',
    '/price-import',
    '/ceo-settings',
  ];

  const VISIBLE = [
    '/',
    '/profile',
    '/my-requests',
    '/attendance',
    '/leave',
    '/leave/new',
    '/employee-requests',
    '/overtime',
    '/tax-allowance',
  ];

  async function locked() {
    vi.stubEnv('VITE_SELF_SERVICE_ONLY', 'true');
    vi.resetModules();
    return import('./permissions.js');
  }

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it('refuses every non-self-service path for every locked role', async () => {
    const { canAccessPath: guard } = await locked();
    const reachable = [];
    for (const user of LOCKED_ROLES) {
      for (const path of HIDDEN) {
        if (guard(path, user)) reachable.push(`${user.role}${user.manager ? '(manager)' : ''} -> ${path}`);
      }
    }
    expect(reachable).toEqual([]);
  });

  it('keeps every self-service path reachable for every locked role', async () => {
    const { canAccessPath: guard } = await locked();
    const refused = [];
    for (const user of LOCKED_ROLES) {
      for (const path of VISIBLE) {
        if (!guard(path, user)) refused.push(`${user.role}${user.manager ? '(manager)' : ''} -> ${path}`);
      }
    }
    expect(refused).toEqual([]);
  });

  // The prefix trap this allowlist is built to avoid: HR's ล.ย.01 REGISTER
  // shares a leading string with the employee's own declaration form. A
  // `startsWith('/tax-allowance')` test would have admitted it.
  it('does not let /tax-allowance admit HR’s /tax-allowance-review', async () => {
    const { canAccessPath: guard } = await locked();
    expect(guard('/tax-allowance', sales)).toBe(true);
    expect(guard('/tax-allowance-review', sales)).toBe(false);
  });

  // The lock flipped an unmatched path from allowed to refused, so a query
  // string on an otherwise-visible path became a way to lock a user out of
  // their own page. /employee-requests?tab=ot is the live shape: it is where
  // the /overtime alias redirects and what EmployeeSelfService's OT card links
  // to.
  it('matches on the pathname, ignoring a query string or hash', async () => {
    const { canAccessPath: guard } = await locked();
    expect(guard('/employee-requests?tab=ot', sales)).toBe(true);
    expect(guard('/employee-requests?tab=welfare', sales)).toBe(true);
    expect(guard('/leave?status=pending#top', sales)).toBe(true);
    // Still refused — the pathname is what is checked, so a query string is no
    // way around the lock.
    expect(guard('/tickets?id=1', sales)).toBe(false);
    expect(guard('/payroll?month=2026-08', sales)).toBe(false);
  });

  // The allowlist's reason for existing: canAccessPath fails OPEN for any path
  // no PATH_GUARDS entry claims, so a route added after this lock shipped must
  // be hidden by default rather than silently exposed.
  it('refuses a path no guard claims — the lock is an allowlist, not a blocklist', async () => {
    const { canAccessPath: guard } = await locked();
    expect(guard('/some-feature-added-later', sales)).toBe(false);
    expect(guard('/some-feature-added-later', hr)).toBe(true);
  });

  it('exempts hr and ceo — their existing access is untouched', async () => {
    const { canAccessPath: guard } = await locked();
    expect(guard('/payroll', hr)).toBe(true);
    expect(guard('/employees', hr)).toBe(true);
    expect(guard('/tax-allowance-review', hr)).toBe(true);
    expect(guard('/settings/leave-policy', hr)).toBe(true);
    expect(guard('/payroll', ceo)).toBe(true);
    expect(guard('/ceo-settings', ceo)).toBe(true);
    expect(guard('/tickets', ceo)).toBe(true);
    // Pre-existing denials still hold — the lock exempts these roles, it does
    // not widen them. /employees is hr-only (EmployeeController is
    // requireAnyRole("hr")), so CEO is refused with or without the lock.
    expect(guard('/employees', ceo)).toBe(false);
  });

  // The lock narrows; it must never widen. A locked user with no linked
  // employee row still fails the pre-existing `!!u.employeeId` guards.
  it('does not widen a self-service path past its own guard', async () => {
    const { canAccessPath: guard } = await locked();
    const noEmployeeRow = { role: 'sales', employeeId: null };
    expect(guard('/profile', noEmployeeRow)).toBe(false);
    expect(guard('/tax-allowance', noEmployeeRow)).toBe(false);
  });

  it('changes nothing when the flag is off', async () => {
    vi.stubEnv('VITE_SELF_SERVICE_ONLY', 'false');
    vi.resetModules();
    const { canAccessPath: guard } = await import('./permissions.js');
    expect(guard('/tickets', sales)).toBe(true);
    expect(guard('/catalog', sales)).toBe(true);
    expect(guard('/finance', account)).toBe(true);
  });
});

describe('isSelfServiceLocked', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it('locks every role except hr and ceo when the flag is on', async () => {
    vi.stubEnv('VITE_SELF_SERVICE_ONLY', 'true');
    vi.resetModules();
    const { isSelfServiceLocked: isLocked } = await import('./permissions.js');
    for (const role of ['sales', 'sales_manager', 'import', 'account', 'employee', 'warehouse', 'qc']) {
      expect(isLocked({ role })).toBe(true);
    }
    expect(isLocked({ role: 'hr' })).toBe(false);
    expect(isLocked({ role: 'ceo' })).toBe(false);
    // An unknown or absent role is locked, not exempt — fail closed.
    expect(isLocked({ role: undefined })).toBe(true);
    expect(isLocked(null)).toBe(true);
  });

  it('locks nobody when the flag is off', async () => {
    vi.stubEnv('VITE_SELF_SERVICE_ONLY', 'false');
    vi.resetModules();
    const { isSelfServiceLocked: isLocked } = await import('./permissions.js');
    expect(isLocked({ role: 'sales' })).toBe(false);
    expect(isLocked(null)).toBe(false);
  });
});

describe('activity log access (admin capability)', () => {
  const user = (role, extra = {}) => ({ id: 216, employeeId: 216, role, ...extra });

  it('denies every role when the backend sends no admin field at all', () => {
    // The production image is pinned AHEAD of this code, so /api/auth/me returns
    // { user: {...} } with NO admin key, and App.jsx's userFromAuthResponse resolves
    // Boolean(undefined) -> false. This asserts the window between the frontend deploy and
    // the backend deploy fails CLOSED. The 2026-08-17 /fulfilment incident was this same
    // frontend-ships-alone asymmetry failing OPEN instead.
    for (const role of ['sales', 'hr', 'ceo', 'employee', 'account', 'warehouse', 'qc']) {
      expect(canAccessPath('/activity-log', user(role, { admin: undefined }))).toBe(false);
    }
  });

  it('denies non-admins once the backend does send the field', () => {
    for (const role of ['sales', 'hr', 'ceo', 'employee', 'sales_manager']) {
      expect(canAccessPath('/activity-log', user(role, { admin: false }))).toBe(false);
    }
  });

  it('denies a caller whose role merely claims to be admin', () => {
    // No role grants this; only the flag does. Guards against the gate drifting into a role
    // check, which is what the backend mutation check covers on the other side.
    expect(canAccessPath('/activity-log', user('admin', { admin: false }))).toBe(false);
  });

  it('allows a flagged admin even though their derived role is sales', () => {
    expect(canAccessPath('/activity-log', user('sales', { admin: true }))).toBe(true);
  });

  // These two need the lockdown ON, so they re-import the module with the flag stubbed —
  // vitest.config.js's test.env sets VITE_SELF_SERVICE_ONLY=false for the rest of the suite,
  // which is why the plain tests above cannot assert lockdown behaviour.
  describe('under the self-service lockdown', () => {
    afterEach(() => {
      vi.unstubAllEnvs();
      vi.resetModules();
    });

    async function lockedCanAccessPath() {
      vi.stubEnv('VITE_SELF_SERVICE_ONLY', 'true');
      vi.resetModules();
      const module = await import('./permissions.js');
      return module.canAccessPath;
    }

    it('opens /activity-log to a flagged admin whose role is not exempt', async () => {
      // วริศรา is role `sales`, which is NOT in SELF_SERVICE_EXEMPT_ROLES, so without the
      // per-path exemption the lockdown would hide this page from its only holder.
      const locked = await lockedCanAccessPath();
      expect(locked('/activity-log', user('sales', { admin: true }))).toBe(true);
    });

    it('unlocks NOTHING else for that admin', async () => {
      // The narrow-exemption check. Being admin must open /activity-log and nothing more; if
      // this ever goes green for /tickets, the exemption has been widened into a full
      // lockdown bypass — which is exactly what adding `admin` to SELF_SERVICE_EXEMPT_ROLES
      // would have done, and why it was not done that way.
      const locked = await lockedCanAccessPath();
      const admin = user('sales', { admin: true });
      for (const path of ['/tickets', '/payroll', '/employees', '/commissions', '/catalog']) {
        expect(locked(path, admin)).toBe(false);
      }
    });
  });
});
