import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App.jsx';

// Some deeper components (e.g. AppShell.jsx) rely on the classic JSX runtime's
// implicit global `React`, per the same pattern used by
// features/dashboard/EmployeeDashboard.test.jsx. The tests above never
// mounted AppShell (they only ever reach LoginPage), so this wasn't needed
// until the route-guard tests below render a fully authenticated <App/>.
globalThis.React = React;

vi.mock('./api/index.js', () => ({
  api: {
    auth: {
      me: vi.fn(),
      login: vi.fn(),
      logout: vi.fn(),
      changePassword: vi.fn(),
    },
    // AppShell always mounts NotificationBell, which fires this query on
    // mount for any authenticated route — needed once the route-guard tests
    // below render a real logged-in user through <App/>, not just the
    // logged-out login screen the tests above exercise.
    notifications: {
      list: vi.fn().mockResolvedValue({ notifications: [] }),
      markRead: vi.fn().mockResolvedValue({}),
    },
    // PricingRequestQueuePage's only query, and (unfiltered) SalesOverview's
    // own worklist source — both the /pricing-requests guard test and the
    // sales-redirect-lands-on-SalesOverview test below need this.
    pricingRequests: {
      queue: vi.fn().mockResolvedValue({ items: [] }),
    },
    // SalesOverview (role-scoped views, Sales branch) is what a sales user
    // now lands on at '/' — its own two remaining queries, needed by the
    // redirect test below.
    tickets: {
      list: vi.fn().mockResolvedValue({ tickets: [] }),
    },
    commissions: {
      list: vi.fn().mockResolvedValue({ commissions: [] }),
    },
    // DivisionManagerOverview's queries, needed for the '/' route-branch test
    // below (a division manager lands there instead of EmployeeDashboard).
    // EmployeeSelfService (plain-employee landing) also reads attendance.daily.
    overtime: {
      list: vi.fn().mockResolvedValue({ requests: [] }),
    },
    leave: {
      list: vi.fn().mockResolvedValue({ requests: [] }),
      balances: vi.fn().mockResolvedValue({ balances: [] }),
    },
    attendance: {
      daily: vi.fn().mockResolvedValue({ days: [] }),
    },
  },
  ROLE_PERMISSIONS: {
    canUseEmployeeExperience: ['employee'],
    canViewEmployees: ['hr'],
    canManageEmployees: ['hr'],
    canReviewProfileRequests: ['hr'],
    canViewCommissions: ['sales', 'sales_manager', 'ceo', 'hr', 'account'],
    canListCommissionRecords: ['sales', 'sales_manager', 'ceo'],
    canCreateCommissionFromDeal: ['account'],
    canApproveCommissions: ['sales_manager', 'ceo'],
    canViewPayrollCommissions: ['hr'],
  },
}));

vi.mock('./hooks/useHrData.js', () => ({
  useHrData: () => ({
    currentEmployee: null,
    employees: [],
    profileRequests: [],
    dashboardSummary: null,
    resetData: vi.fn(),
    createEmployee: vi.fn(),
    updateEmployee: vi.fn(),
    createProfileRequest: vi.fn(),
    reviewProfileRequest: vi.fn(),
    reviewingProfileRequest: false,
  }),
}));

import { api } from './api/index.js';

describe('App auth restore', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('does not render the login form while session restore is pending', () => {
    api.auth.me.mockReturnValue(new Promise(() => {}));

    render(
      <MemoryRouter initialEntries={['/employees']}>
        <App />
      </MemoryRouter>,
    );

    expect(screen.getByRole('status')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'เข้าสู่ระบบ' })).toBeNull();
  });

  it('renders the login form after session restore confirms no user', async () => {
    api.auth.me.mockRejectedValue(new Error('Not authenticated'));

    render(
      <MemoryRouter initialEntries={['/employees']}>
        <App />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'เข้าสู่ระบบ' })).toBeTruthy();
    });
  });
});

describe('App login errors (UX-06)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.auth.me.mockRejectedValue(new Error('Not authenticated'));
  });

  async function renderLoginForm() {
    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'เข้าสู่ระบบ' })).toBeTruthy();
    });
    fireEvent.change(screen.getByLabelText('อีเมล'), { target: { value: 'someone@glr.co' } });
    fireEvent.change(screen.getByLabelText('รหัสผ่าน'), { target: { value: 'wrong-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'เข้าสู่ระบบ' }));
  }

  it('shows Thai bad-credentials copy for a 401, not the raw server message', async () => {
    // client.js's ApiError always carries a `.status` — this is the real shape
    // App.jsx branches on, not an invented field.
    const unauthorized = new Error('Invalid email or password');
    unauthorized.status = 401;
    api.auth.login.mockRejectedValue(unauthorized);

    await renderLoginForm();

    await waitFor(() => {
      expect(screen.getByText('อีเมลหรือรหัสผ่านไม่ถูกต้อง')).toBeTruthy();
    });
    expect(screen.queryByText('Invalid email or password')).toBeNull();
    expect(screen.queryByText(/invalid/i)).toBeNull();
  });

  it('shows a distinct generic Thai message when the failure has no HTTP status', async () => {
    api.auth.login.mockRejectedValue(new TypeError('Failed to fetch'));

    await renderLoginForm();

    await waitFor(() => {
      expect(screen.getByText('เข้าสู่ระบบไม่สำเร็จ กรุณาลองใหม่อีกครั้ง')).toBeTruthy();
    });
  });
});

// Commit 6 added the '/pricing-requests' PATH_GUARDS entry (app/permissions.js)
// gated on ROLE_PERMISSIONS.canViewPricingRequestQueue (import/ceo/sales_manager
// in the real routes.js — permissions.js reads that module directly, not the
// api/index.js mock above, so these are the real role gates, not stand-ins).
describe('App route guard for /pricing-requests (commit 6)', () => {
  const importUser = { employeeId: 20, name: 'ฝ่ายนำเข้า ทดสอบ', role: 'import', email: 'import@glr.co' };
  const salesUser = { employeeId: 21, name: 'พนักงานขาย ทดสอบ', role: 'sales', email: 'sales@glr.co' };

  beforeEach(() => {
    vi.clearAllMocks();
    api.notifications.list.mockResolvedValue({ notifications: [] });
    api.pricingRequests.queue.mockResolvedValue({ items: [] });
    api.tickets.list.mockResolvedValue({ tickets: [] });
    api.commissions.list.mockResolvedValue({ commissions: [] });
  });

  function renderAppAt(path, user) {
    api.auth.me.mockResolvedValue({ user });
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[path]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>,
    );
  }

  it('lets a role with canViewPricingRequestQueue (import) reach /pricing-requests', async () => {
    renderAppAt('/pricing-requests', importUser);

    expect(await screen.findByRole('heading', { name: 'คิวขอราคา' })).toBeTruthy();
  });

  it('shows an in-place access-denied view for a role without canViewPricingRequestQueue (sales), instead of redirecting to the dashboard (#391)', async () => {
    renderAppAt('/pricing-requests', salesUser);

    // RequireAccess now renders AccessDeniedPage in place of the guarded
    // route instead of <Navigate to="/" replace/> — the user must NOT land
    // on SalesOverview (the '/' landing the old redirect used to produce),
    // and the refused path must be named in the copy, proving the guard
    // rendered in place rather than navigating away from /pricing-requests.
    expect(await screen.findByRole('heading', { name: 'ไม่มีสิทธิ์เข้าถึงหน้านี้' })).toBeTruthy();
    expect(screen.getByText('/pricing-requests')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'คิวขอราคา' })).toBeNull();
    expect(screen.queryByRole('heading', { name: `สวัสดี คุณ${salesUser.name}` })).toBeNull();
  });
});

// isDivisionManager (app/permissions.js) branches the '/' route to
// DivisionManagerOverview instead of the generic EmployeeDashboard for role
// `employee` + the manager flag. Confirms the actual App.jsx wiring, not just
// the isDivisionManager predicate (unit-tested separately in permissions.test.js)
// or DivisionManagerOverview's own rendering (DivisionManagerOverview.test.jsx).
describe('App / route branches division managers to DivisionManagerOverview', () => {
  const managerUser = { employeeId: 30, name: 'ผู้จัดการ ทดสอบ', role: 'employee', manager: true, email: 'manager@glr.co' };
  const plainEmployeeUser = { employeeId: 31, name: 'พนักงาน ทดสอบ', role: 'employee', manager: false, email: 'employee@glr.co' };

  beforeEach(() => {
    vi.clearAllMocks();
    api.notifications.list.mockResolvedValue({ notifications: [] });
    api.overtime.list.mockResolvedValue({ requests: [] });
    api.leave.list.mockResolvedValue({ requests: [] });
    api.attendance.daily.mockResolvedValue({ days: [] });
  });

  function renderAppAt(path, user) {
    api.auth.me.mockResolvedValue({ user });
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[path]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>,
    );
  }

  it('lands a division manager (role employee + manager flag) on DivisionManagerOverview at /', async () => {
    renderAppAt('/', managerUser);
    expect(await screen.findByText(/ภาพรวมทีม/)).toBeTruthy();
  });

  it('lands a plain employee (no manager flag) on EmployeeSelfService at / (not EmployeeDashboard, not DivisionManagerOverview)', async () => {
    renderAppAt('/', plainEmployeeUser);
    expect(await screen.findByRole('heading', { name: `สวัสดี คุณ${plainEmployeeUser.name}` })).toBeTruthy();
    expect(screen.queryByText(/ภาพรวมทีม/)).toBeNull();
  });
});

// Release lockdown (SELF_SERVICE_ONLY — app/features.js): everyone except HR
// and CEO lands on the self-service view at '/', and every non-self-service
// route answers with the access-denied page. features.js reads import.meta.env
// at module-load time and vitest.config.js pins the flag OFF for the suite, so
// exercising the locked case needs vi.stubEnv + vi.resetModules + a dynamic
// import of a fresh App/api pair — the static `import { App }` at the top of
// this file already evaluated unlocked.
describe('App / release lockdown (SELF_SERVICE_ONLY)', () => {
  const salesUser = { employeeId: 41, name: 'พนักงานขาย ทดสอบ', role: 'sales', email: 'sales@glr.co' };
  const salesUserNoEmployeeId = { employeeId: null, name: 'ผู้ใช้ระบบ ทดสอบ', role: 'sales', email: 'noemp@glr.co' };
  const hrUser = { employeeId: 40, name: 'ฝ่ายบุคคล ทดสอบ', role: 'hr', email: 'hr@glr.co' };

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  async function renderLockedAppAt(path, user, flag = 'true') {
    vi.stubEnv('VITE_SELF_SERVICE_ONLY', flag);
    vi.resetModules();
    const { api: freshApi } = await import('./api/index.js');
    freshApi.auth.me.mockResolvedValue({ user });

    const { App: FreshApp } = await import('./App.jsx');
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[path]}>
          <FreshApp />
        </MemoryRouter>
      </QueryClientProvider>,
    );
  }

  it('lands a sales rep on EmployeeSelfService, not SalesOverview', async () => {
    await renderLockedAppAt('/', salesUser);

    // EmployeeSelfService's greeting has NO comma; EmployeeDashboard's does
    // ("สวัสดี, คุณ…"). "ดีลของฉัน" is SalesOverview's own subtitle.
    expect(await screen.findByRole('heading', { name: `สวัสดี คุณ${salesUser.name}` })).toBeTruthy();
    expect(screen.getByText(/เวลาทำงานและคำขอของคุณ/)).toBeTruthy();
    expect(screen.queryByText(/ดีลของฉัน/)).toBeNull();
  });

  it('falls back to EmployeeDashboard for a locked user with no employee row', async () => {
    await renderLockedAppAt('/', salesUserNoEmployeeId);

    expect(await screen.findByRole('heading', { name: `สวัสดี, คุณ${salesUserNoEmployeeId.name}` })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: `สวัสดี คุณ${salesUserNoEmployeeId.name}` })).toBeNull();
  });

  it('refuses a locked role the deal pipeline at /tickets', async () => {
    await renderLockedAppAt('/tickets', salesUser);

    expect(await screen.findByRole('heading', { name: 'ไม่มีสิทธิ์เข้าถึงหน้านี้' })).toBeTruthy();
  });

  // /commissions, not /pricing-requests: canViewPricingRequestQueue is
  // ['import','ceo','sales_manager'] (routes.js), so a sales rep is refused that
  // path with or without the lock and the assertion would prove nothing about
  // the lock. canViewCommissions DOES include sales, so this one can only pass
  // because the lock refused it.
  it('refuses a locked role a page its own role permits — /commissions', async () => {
    await renderLockedAppAt('/commissions', salesUser);

    expect(await screen.findByRole('heading', { name: 'ไม่มีสิทธิ์เข้าถึงหน้านี้' })).toBeTruthy();
  });

  it('still lets a locked role reach its own leave page', async () => {
    await renderLockedAppAt('/leave', salesUser);

    expect(screen.queryByRole('heading', { name: 'ไม่มีสิทธิ์เข้าถึงหน้านี้' })).toBeNull();
  });

  it('exempts hr — HrOverview still renders at /', async () => {
    await renderLockedAppAt('/', hrUser);

    expect(await screen.findByRole('heading', { name: 'สวัสดี ฝ่ายบุคคล' })).toBeTruthy();
  });

  it('changes nothing when the flag is off — a sales rep still lands on SalesOverview', async () => {
    await renderLockedAppAt('/', salesUser, 'false');

    expect(await screen.findByText(/ดีลของฉัน/)).toBeTruthy();
    expect(screen.queryByText(/เวลาทำงานและคำขอของคุณ/)).toBeNull();
  });
});
