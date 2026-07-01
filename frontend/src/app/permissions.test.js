import { describe, it, expect } from 'vitest';
import { hasPermission, allowedRoute } from './permissions.js';

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
