import { afterEach, describe, it, expect, vi } from 'vitest';
import { hasPermission, allowedRoute, canAccessPath, isDivisionManager, dealRedirectFor } from './permissions.js';

describe('hasPermission', () => {
  it('scopes price import to ceo/import only (#205)', () => {
    expect(hasPermission('ceo', 'canManagePriceImport')).toBe(true);
    expect(hasPermission('import', 'canManagePriceImport')).toBe(true);
    expect(hasPermission('sales', 'canManagePriceImport')).toBe(false);
    expect(hasPermission('sales_manager', 'canManagePriceImport')).toBe(false);
    expect(hasPermission('admin', 'canManagePriceImport')).toBe(false);
  });

  it('scopes the deal-pipeline BROWSER to sales/sales_manager/ceo, excluding account (Account role-scoped views)', () => {
    expect(hasPermission('sales', 'canViewDealPipeline')).toBe(true);
    expect(hasPermission('sales_manager', 'canViewDealPipeline')).toBe(true);
    expect(hasPermission('ceo', 'canViewDealPipeline')).toBe(true);
    expect(hasPermission('account', 'canViewDealPipeline')).toBe(false);
    expect(hasPermission('import', 'canViewDealPipeline')).toBe(false);
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

const ROLE_USERS = Object.fromEntries(
  ['employee', 'sales', 'sales_manager', 'import', 'account', 'hr', 'ceo', 'warehouse', 'qc']
    .map((role) => [role, { role, employeeId: 20 }]),
);

// Route-guard matrix: for each path, every listed role is asserted to be admitted or refused, so an
// allow and a deny are always pinned together (canAccessPath fails OPEN for a path no guard claims).
describe('canAccessPath role matrix', () => {
  it.each([
    ['/employees', ['hr'], ['employee']],
    ['/employees/5', ['hr'], ['employee']],
    ['/hr', ['hr'], ['employee']],
    ['/payroll', ['hr', 'ceo'], ['employee', 'sales', 'import', 'account']],
    ['/payroll/deduction-shortfalls', ['hr', 'ceo'], ['employee', 'sales', 'import', 'account']],
    ['/settings/leave-policy', ['hr', 'ceo'], ['employee', 'sales', 'import', 'account']],
    ['/fulfilment', ['import', 'ceo'], ['employee', 'sales', 'sales_manager', 'hr', 'account']],
    ['/import/deals/12', ['import', 'ceo'], ['sales', 'sales_manager', 'account', 'hr', 'employee', 'warehouse', 'qc']],
    ['/tickets', ['sales', 'sales_manager', 'ceo'], ['employee', 'account', 'import']],
    ['/tickets/12', ['sales', 'sales_manager', 'ceo', 'account'], ['import', 'employee']],
    ['/tickets/12/deposit', ['sales', 'account'], ['import']],
    ['/visits', ['sales', 'sales_manager', 'ceo'], ['hr', 'employee', 'import', 'account', 'warehouse', 'qc']],
    ['/finance', ['account', 'ceo'], ['sales', 'employee']],
    ['/finance/deals/501', ['account', 'ceo'], ['sales', 'employee']],
    ['/finance/receipts-import', ['account'], ['ceo', 'sales']],
    ['/commissions', ['sales'], ['employee']],
    ['/price-import', ['ceo', 'import'], ['sales', 'employee', 'hr']],
    ['/pricing-requests', ['import', 'ceo', 'sales_manager'], ['sales', 'employee']],
    ['/pricing-requests/42', ['sales', 'import'], ['employee']],
    ['/ceo-settings', ['ceo'], ['hr', 'employee', 'sales', 'import', 'account', 'sales_manager']],
    ['/quotations/new', ['sales', 'sales_manager'], ['ceo', 'import', 'account']],
    ['/quotations/12', ['ceo'], []],
    ['/leave', ['hr', 'ceo', 'employee'], []],
    ['/leave/new', ['employee', 'sales'], ['hr', 'ceo']],
  ])('%s', (path, allowed, denied) => {
    for (const role of allowed) expect(canAccessPath(path, ROLE_USERS[role]), `${role} -> ${path}`).toBe(true);
    for (const role of denied) expect(canAccessPath(path, ROLE_USERS[role]), `${role} -> ${path}`).toBe(false);
  });

  const linked = { role: 'employee', employeeId: 5 };
  const detached = { role: 'sales', employeeId: null };
  it.each([
    ['/employees', null, false],
    ['/', linked, true],
    ['/profile', linked, true],
    ['/overtime', linked, true],
    ['/leave', linked, true],
    ['/profile', detached, false],
    ['/overtime', detached, false],
    ['/leave', detached, false],
    ['/my-requests', { role: 'hr', employeeId: 10 }, true],
    ['/my-requests', detached, false],
  ])('self-service %s for %j is %s', (path, user, expected) => {
    expect(canAccessPath(path, user)).toBe(expected);
  });
});

describe('allowedRoute', () => {
  const hr = { role: 'hr', employeeId: 10 };
  const employee = { role: 'employee', employeeId: 5 };

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
});

describe('canAccessPath', () => {
  const hr = { role: 'hr', employeeId: 10 };
  const employee = { role: 'employee', employeeId: 5 };
  const sales = { role: 'sales', employeeId: 9 };
  const ceo = { role: 'ceo', employeeId: 1 };
  const importer = { role: 'import', employeeId: 2 };
  const account = { role: 'account', employeeId: 3 };

  // PayrollController: every GET, plus the non-persisting POST /preview and /preview/export/{kind},
  // is hasAnyRole('HR','CEO'), so the ROUTE only needs canViewPayroll (hr+ceo). Writes stay
  // canManagePayroll (hr-only) and are gated inside PayrollPage.jsx; ceo at '/payroll' is read-only
  // oversight.
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

  // The canCreateQuotation capability grant is OR'd into the '/quotations' guard: a grant holder's
  // ROLE need not be in ROLE_PERMISSIONS.canViewDealQuotations (qc is not).
  it('refuses /quotations to a qc user with no grant, admits one with the grant', () => {
    expect(canAccessPath('/quotations', { role: 'qc', employeeId: 20 })).toBe(false);
    expect(canAccessPath('/quotations/new', { role: 'qc', employeeId: 20 })).toBe(false);
    expect(canAccessPath('/quotations', { role: 'qc', employeeId: 20, canCreateQuotation: true })).toBe(true);
    expect(canAccessPath('/quotations/new', { role: 'qc', employeeId: 20, canCreateQuotation: true })).toBe(true);
  });
});

describe('isDivisionManager', () => {
  it('is true for role employee with the manager flag set', () => {
    expect(isDivisionManager({ role: 'employee', manager: true, employeeId: 10 })).toBe(true);
  });

  // Narrowed to role 'employee' on purpose: sales_manager/hr/ceo have their own dedicated landings (company mode
  // / a future sales-manager Overview), so a manager flag on those roles must not also route them here.
  it('is false for other roles even when the manager flag is set', () => {
    expect(isDivisionManager({ role: 'sales_manager', manager: true, employeeId: 11 })).toBe(false);
    expect(isDivisionManager({ role: 'hr', manager: true, employeeId: 12 })).toBe(false);
    expect(isDivisionManager({ role: 'ceo', manager: true, employeeId: 13 })).toBe(false);
  });
});

// Release lockdown (SELF_SERVICE_ONLY — app/features.js). Turning it back ON therefore needs vi.stubEnv +
// vi.resetModules + a dynamic import of a fresh permissions.js: the static `import` at the top of this file
// already evaluated with the flag off and can only ever prove the unlocked case.
describe('canAccessPath under the release lockdown', () => {
  const sales = { role: 'sales', employeeId: 41 };
  const salesManager = { role: 'sales_manager', employeeId: 42 };
  const importer = { role: 'import', employeeId: 43 };
  const account = { role: 'account', employeeId: 44 };
  const employee = { role: 'employee', employeeId: 45 };
  const divisionManager = { role: 'employee', employeeId: 46, manager: true };
  const hr = { role: 'hr', employeeId: 47 };
  const ceo = { role: 'ceo', employeeId: 48 };

  // Sales and sales_manager are exempt from the lock; import and account stay locked with a per-role
  // allowlist (tested below). Only these two roles see nothing but self-service.
  const LOCKED_ROLES = [employee, divisionManager];
  const SELF_SERVICE_ROLES = [importer, account, employee, divisionManager];

  // Paths the lock must REMOVE. Each is a page the role could otherwise reach, so the test fails if
  // the lock stops applying, not merely if a permission changes.
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
    '/visits',
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
    // มีอะไรใหม่: every employee must be able to open the release notice their bell row and email link to.
    '/whats-new',
    '/whats-new/2026-11-16',
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
    for (const user of SELF_SERVICE_ROLES) {
      for (const path of VISIBLE) {
        if (!guard(path, user)) refused.push(`${user.role}${user.manager ? '(manager)' : ''} -> ${path}`);
      }
    }
    expect(refused).toEqual([]);
  });

  // The sales stack opens for the sales roles; import gets ONLY งานนำเข้า and คิวขอราคา (plus its
  // per-deal drill-down), account ONLY การเงิน. Refusals first: what each role must still NOT reach.
  it('releases the sales stack to sales and sales_manager, nothing beyond their guards', async () => {
    const { canAccessPath: guard } = await locked();
    for (const user of [sales, salesManager]) {
      for (const path of ['/tickets', '/tickets/7', '/pricing-requests/9', '/quotations', '/quotations/3', '/catalog']) {
        expect(guard(path, user), `${user.role} -> ${path}`).toBe(true);
      }
      for (const path of ['/payroll', '/hr', '/employees', '/finance', '/tax-allowance-review', '/fulfilment']) {
        expect(guard(path, user), `${user.role} -> ${path}`).toBe(false);
      }
    }
    expect(guard('/commissions', salesManager)).toBe(true);
  });

  it('releases import only งานนำเข้า and คิวขอราคา', async () => {
    const { canAccessPath: guard } = await locked();
    for (const path of ['/fulfilment', '/pricing-requests', '/pricing-requests/9', '/import/deals/7', '/leave']) {
      expect(guard(path, importer), `import -> ${path}`).toBe(true);
    }
    for (const path of ['/tickets', '/tickets/7', '/finance', '/catalog', '/quotations', '/commissions', '/payroll', '/price-import', '/some-feature-added-later']) {
      expect(guard(path, importer), `import -> ${path}`).toBe(false);
    }
  });

  it('releases account only การเงิน', async () => {
    const { canAccessPath: guard } = await locked();
    for (const path of ['/finance', '/finance/billing-notes', '/leave']) {
      expect(guard(path, account), `account -> ${path}`).toBe(true);
    }
    for (const path of ['/tickets', '/tickets/7', '/tickets/7/deposit', '/fulfilment', '/pricing-requests', '/quotations', '/commissions', '/catalog', '/payroll', '/some-feature-added-later']) {
      expect(guard(path, account), `account -> ${path}`).toBe(false);
    }
  });

  // The lock flipped an unmatched path from allowed to refused, so a query string on an otherwise-visible path
  // became a way to lock a user out of their own page. /employee-requests?tab=ot is the live shape: it is where
  // the /overtime alias redirects and what EmployeeSelfService's OT card links to.
  it('matches on the pathname, ignoring a query string or hash', async () => {
    const { canAccessPath: guard } = await locked();
    expect(guard('/employee-requests?tab=ot', importer)).toBe(true);
    expect(guard('/employee-requests?tab=welfare', importer)).toBe(true);
    expect(guard('/leave?status=pending#top', importer)).toBe(true);
    // Still refused: the pathname is what is checked, so a query string is no way around the lock.
    expect(guard('/tickets?id=1', importer)).toBe(false);
    expect(guard('/payroll?month=2026-08', importer)).toBe(false);
    // The per-role release allowlist matches on the pathname the same way.
    expect(guard('/fulfilment?tab=pending', importer)).toBe(true);
    expect(guard('/finance?tab=deposits', account)).toBe(true);
  });

  // The allowlist's reason for existing: canAccessPath fails OPEN for any path no PATH_GUARDS entry claims, so a
  // route added after this lock shipped must be hidden by default rather than silently exposed.
  it('refuses a path no guard claims — the lock is an allowlist, not a blocklist', async () => {
    const { canAccessPath: guard } = await locked();
    expect(guard('/some-feature-added-later', employee)).toBe(false);
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
    // Pre-existing denials still hold — the lock exempts these roles, it does not widen them. /employees is
    // hr-only (EmployeeController is requireAnyRole("hr")), so CEO is refused with or without the lock.
    expect(guard('/employees', ceo)).toBe(false);
  });

  // /visits is handled like /tickets: a PATH_GUARDS entry, not a self-service path. The lock-exempt sales roles keep
  // it; every locked role loses it (and the guard would refuse them anyway).
  it('keeps /visits for the lock-exempt sales roles and ceo, and refuses it to every locked role', async () => {
    const { canAccessPath: guard } = await locked();
    for (const user of [sales, salesManager, ceo]) expect(guard('/visits', user), `${user.role} -> /visits`).toBe(true);
    for (const user of [importer, account, employee, divisionManager, hr]) {
      expect(guard('/visits', user), `${user.role} -> /visits`).toBe(false);
    }
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
    for (const role of ['import', 'account', 'employee', 'warehouse', 'qc']) {
      expect(isLocked({ role })).toBe(true);
    }
    // hr/ceo always; sales/sales_manager are exempt from the lock.
    for (const role of ['hr', 'ceo', 'sales', 'sales_manager']) {
      expect(isLocked({ role })).toBe(false);
    }
    // An unknown or absent role is locked, not exempt — fail closed.
    expect(isLocked({ role: undefined })).toBe(true);
    expect(isLocked(null)).toBe(true);
  });
});

describe('activity log access (admin capability)', () => {
  const user = (role, extra = {}) => ({ id: 216, employeeId: 216, role, ...extra });

  it('denies every role when the backend sends no admin field at all', () => {
    // The production image can be pinned AHEAD of this code, so /api/auth/me returns { user: {...} }
    // with NO admin key and userFromAuthResponse resolves Boolean(undefined) -> false. This asserts
    // the window between the frontend and backend deploys fails CLOSED.
    for (const role of ['sales', 'hr', 'ceo', 'employee', 'account', 'warehouse', 'qc']) {
      expect(canAccessPath('/activity-log', user(role, { admin: undefined }))).toBe(false);
    }
  });

  it('allows a flagged admin even though their derived role is sales', () => {
    expect(canAccessPath('/activity-log', user('sales', { admin: true }))).toBe(true);
  });

  // These two need the lockdown ON, so they re-import the module with the flag stubbed — vitest.config.js's
  // test.env sets VITE_SELF_SERVICE_ONLY=false for the rest of the suite, which is why the plain tests above
  // cannot assert lockdown behaviour.
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
      // The holder's role must not matter: a locked role (import; sales is exempt) still reaches the
      // page through the per-path exemption alone.
      const locked = await lockedCanAccessPath();
      expect(locked('/activity-log', user('import', { admin: true }))).toBe(true);
    });

    it('keeps /activity-log locked for a locked role without the admin flag', async () => {
      const locked = await lockedCanAccessPath();
      expect(locked('/activity-log', user('import', { admin: false }))).toBe(false);
    });

    it('unlocks NOTHING else for that admin', async () => {
      // The narrow-exemption check. Being admin must open /activity-log and nothing more; if this goes
      // green for /tickets, the exemption has been widened into a full lockdown bypass (which adding
      // `admin` to SELF_SERVICE_EXEMPT_ROLES would do).
      const locked = await lockedCanAccessPath();
      const admin = user('import', { admin: true });
      for (const path of ['/tickets', '/payroll', '/employees', '/commissions', '/catalog']) {
        expect(locked(path, admin)).toBe(false);
      }
    });
  });
});

// /people-insights follows the server's canViewPeopleInsights hint (ceo/hr/team head), and a team head is not hr/ceo, so
// the self-service lockdown must let this one path through for the flag holder and nothing else.
describe('people insights access (canViewPeopleInsights)', () => {
  const user = (role, extra = {}) => ({ id: 9, employeeId: 9, role, ...extra });

  it('opens the page only for a flagged user', () => {
    expect(canAccessPath('/people-insights', user('employee', { canViewPeopleInsights: true }))).toBe(true);
    expect(canAccessPath('/people-insights', user('employee', { canViewPeopleInsights: false }))).toBe(false);
    expect(canAccessPath('/people-insights', user('hr'))).toBe(false); // no flag, no page, whatever the role
  });

  describe('under the self-service lockdown', () => {
    afterEach(() => {
      vi.unstubAllEnvs();
      vi.resetModules();
    });

    it('lets a flagged team head through to this page and unlocks nothing else', async () => {
      vi.stubEnv('VITE_SELF_SERVICE_ONLY', 'true');
      vi.resetModules();
      const { canAccessPath: locked } = await import('./permissions.js');
      const head = user('employee', { canViewPeopleInsights: true });
      expect(locked('/people-insights', head)).toBe(true);
      expect(locked('/payroll', head)).toBe(false);
      expect(locked('/people-insights', user('employee'))).toBe(false);
    });
  });
});

// Under the lock, the quotation pages, and nothing else from the sales stack, open for the people
// who create/approve quotations.
describe('quotation release through the self-service lockdown', () => {
  const user = (role, extra = {}) => ({ id: 60, employeeId: 60, role, ...extra });
  const QUOTATION_PATHS = ['/quotations', '/quotations/new', '/quotations/new?ticket=6', '/quotations/12'];
  const OTHER_SALES_PATHS = ['/tickets', '/tickets/6', '/customers', '/catalog', '/pricing-requests',
    '/deposits', '/fulfilment', '/commissions', '/ceo-settings', '/quotations-archive'];

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  async function locked() {
    vi.stubEnv('VITE_SELF_SERVICE_ONLY', 'true');
    vi.resetModules();
    return import('./permissions.js');
  }

  it('opens the quotation pages to sales, sales_manager and a can-create grantee', async () => {
    const { canAccessPath: can } = await locked();
    for (const u of [user('sales'), user('sales_manager'), user('qc', { canCreateQuotation: true })]) {
      for (const path of QUOTATION_PATHS) {
        expect(can(path, u), `${u.role} ${path}`).toBe(true);
      }
    }
  });

  // The exemption must not leak the rest of the sales stack to a grantee outside the sales roles
  // (sales/sales_manager are exempt from the lock).
  it('keeps every other sales page locked for a grantee outside the sales roles', async () => {
    const { canAccessPath: can } = await locked();
    for (const u of [user('qc', { canCreateQuotation: true })]) {
      for (const path of OTHER_SALES_PATHS) {
        expect(can(path, u), `${u.role} ${path}`).toBe(false);
      }
    }
  });

  it('keeps the quotation pages locked for roles that do not create quotations', async () => {
    const { canAccessPath: can } = await locked();
    for (const role of ['import', 'account', 'employee', 'warehouse', 'qc']) {
      for (const path of QUOTATION_PATHS) {
        expect(can(path, user(role)), `${role} ${path}`).toBe(false);
      }
    }
    expect(can('/quotations', user('employee', { manager: true }))).toBe(false);
  });
});

// An import or account user landing on a whole-deal URL (a backend notification deep-link, an old
// bookmark) is sent to their own page for that deal instead of a dead-end refusal.
describe('dealRedirectFor', () => {
  const importer = { role: 'import', employeeId: 2 };
  const account = { role: 'account', employeeId: 3 };

  it('maps /tickets/:id to /import/deals/:id for import only', () => {
    expect(dealRedirectFor('/tickets/12', importer)).toBe('/import/deals/12');
    expect(dealRedirectFor('/tickets/12/', importer)).toBe('/import/deals/12');
  });

  it('does not redirect other roles, other paths, or sub-paths', () => {
    expect(dealRedirectFor('/tickets/12', { role: 'ceo', employeeId: 1 })).toBeNull();
    expect(dealRedirectFor('/tickets/12', { role: 'sales', employeeId: 9 })).toBeNull();
    expect(dealRedirectFor('/tickets', importer)).toBeNull();
    expect(dealRedirectFor('/tickets/12/deposit', importer)).toBeNull();
    expect(dealRedirectFor('/tickets/abc', importer)).toBeNull();
    expect(dealRedirectFor('/fulfilment', importer)).toBeNull();
    expect(dealRedirectFor('/tickets/12', null)).toBeNull();
  });

  // Account's deposit-notice notification (DepositNoticeService -> notifyByRole('account')) links
  // /tickets/:id; account works a deal on /finance/deals/:id. Unlike import, the sub-paths
  // (/tickets/:id/deposit) have an account equivalent, so they redirect too.
  it('maps /tickets/:id and its sub-paths to /finance/deals/:id for account', () => {
    expect(dealRedirectFor('/tickets/12', account)).toBe('/finance/deals/12');
    expect(dealRedirectFor('/tickets/12/', account)).toBe('/finance/deals/12');
    expect(dealRedirectFor('/tickets/12/deposit', account)).toBe('/finance/deals/12');
    expect(dealRedirectFor('/tickets/12?from=bell', account)).toBe('/finance/deals/12');
  });
});
