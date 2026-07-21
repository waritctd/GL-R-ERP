import { describe, it, expect } from 'vitest';
import { hasPermission, allowedRoute, canAccessPath } from './permissions.js';

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

  it('scopes ticket paths to ticket roles', () => {
    expect(canAccessPath('/tickets', sales)).toBe(true);
    expect(canAccessPath('/tickets/12', sales)).toBe(true);
    expect(canAccessPath('/tickets/12/deposit', sales)).toBe(true);
    expect(canAccessPath('/ticket-overview', sales)).toBe(true);
    expect(canAccessPath('/tickets', employee)).toBe(false);
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
});
