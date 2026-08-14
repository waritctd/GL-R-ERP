import { describe, it, expect } from 'vitest';
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
