import { describe, it, expect } from 'vitest';
import { hasPermission, canAccessPath } from './permissions.js';

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
});

describe('canAccessPath', () => {
  const hr = { role: 'hr', employeeId: 10 };
  const employee = { role: 'employee', employeeId: 5 };
  const sales = { role: 'sales', employeeId: 9 };

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

  it('gates my-requests to roles that submit profile requests', () => {
    expect(canAccessPath('/my-requests', employee)).toBe(true);
    expect(canAccessPath('/my-requests', hr)).toBe(false);
  });

  it('scopes commissions to commission-viewing roles', () => {
    expect(canAccessPath('/commissions', sales)).toBe(true);
    expect(canAccessPath('/commissions', employee)).toBe(false);
  });

  it('allows unguarded and unknown paths for any authenticated user', () => {
    // `/` and `/attendance` are open to everyone; `/ceo-settings` is nav-gated
    // only, so canAccessPath lets it through.
    expect(canAccessPath('/', employee)).toBe(true);
    expect(canAccessPath('/attendance', employee)).toBe(true);
    expect(canAccessPath('/ceo-settings', employee)).toBe(true);
  });
});
