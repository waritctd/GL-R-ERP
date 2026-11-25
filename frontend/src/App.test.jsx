import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App, DepositNoticeRoute } from './App.jsx';
import { getLastPage } from './features/whatsNew/lastPage.js';

// Some deeper components (e.g. AppShell.jsx) rely on the classic JSX runtime's implicit global `React`, per
// the same pattern used by features/dashboard/EmployeeDashboard.test.jsx.
globalThis.React = React;

vi.mock('./api/index.js', () => ({
  api: {
    auth: {
      me: vi.fn(),
      login: vi.fn(),
      logout: vi.fn(),
      changePassword: vi.fn(),
    },
    // AppShell always mounts NotificationBell, which fires this query on mount for any authenticated route —
    // needed once the route-guard tests below render a real logged-in user through <App/>, not just the logged-out
    // login screen the tests above exercise.
    notifications: {
      list: vi.fn().mockResolvedValue({ notifications: [] }),
      markRead: vi.fn().mockResolvedValue({}),
    },
    // PricingRequestQueuePage's only query, and (unfiltered) SalesOverview's own worklist source — both the
    // /pricing-requests guard test and the sales-redirect-lands-on-SalesOverview test below need this.
    pricingRequests: {
      queue: vi.fn().mockResolvedValue({ items: [] }),
    },
    // SalesOverview (งานวันนี้) is what a sales user lands on at '/': GET /api/sales/my-work plus the stage
    // catalog MarkLostModal reads.
    salesBoard: {
      myWork: vi.fn().mockResolvedValue({ followUp: [], board: [], quietDays: 14 }),
    },
    meta: {
      dealStages: vi.fn().mockResolvedValue({ stages: [], phases: [], lostReasons: [], cancelReasons: [], routes: {} }),
    },
    tickets: {
      list: vi.fn().mockResolvedValue({ tickets: [] }),
    },
    commissions: {
      list: vi.fn().mockResolvedValue({ commissions: [] }),
    },
    // DivisionManagerOverview's queries, needed for the '/' route-branch test below (a division manager lands
    // there instead of EmployeeDashboard). EmployeeSelfService (plain-employee landing) also reads
    // attendance.daily.
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
    documentChain: { get: vi.fn() },
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

describe('DepositNoticeRoute', () => {
  it('a failed chain read shows an error with a retry, never a silently read-only page', async () => {
    api.documentChain.get.mockRejectedValueOnce(new Error('boom')).mockReturnValue(new Promise(() => {}));
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={['/tickets/18/deposit']}>
          <Routes><Route path="/tickets/:ticketId/deposit" element={<DepositNoticeRoute user={{ id: 6, role: 'sales' }} showToast={vi.fn()} />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(await screen.findByText('โหลดสิทธิ์การแก้ไขไม่สำเร็จ')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'ลองใหม่' }));
    await waitFor(() => expect(api.documentChain.get).toHaveBeenCalledTimes(2));
  });
});

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
    // client.js's ApiError always carries a `.status` — this is the real shape App.jsx branches on, not an
    // invented field.
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

// Commit 6 added the '/pricing-requests' PATH_GUARDS entry gated on
// ROLE_PERMISSIONS.canViewPricingRequestQueue.
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

    // RequireAccess now renders AccessDeniedPage in place of the guarded route instead of <Navigate to="/"
    // replace/> — the user must NOT land on SalesOverview, and the refused path must be named in the copy, proving
    // the guard rendered in place rather than navigating away from /pricing-requests.
    expect(await screen.findByRole('heading', { name: 'ไม่มีสิทธิ์เข้าถึงหน้านี้' })).toBeTruthy();
    expect(screen.getByText('/pricing-requests')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'คิวขอราคา' })).toBeNull();
    expect(screen.queryByRole('heading', { name: `สวัสดี คุณ${salesUser.name}` })).toBeNull();
  });
});

// isDivisionManager (app/permissions.js) branches the '/' route to DivisionManagerOverview instead of the
// generic EmployeeDashboard for role `employee` + the manager flag.
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

// Release lockdown (SELF_SERVICE_ONLY — app/features.js): a locked role lands on the self-service view at '/' (import and account keep their own dashboard),
// and every non-self-service, non-released route answers with the access-denied page.
describe('App / release lockdown (SELF_SERVICE_ONLY)', () => {
  const salesUser = { employeeId: 41, name: 'พนักงานขาย ทดสอบ', role: 'sales', email: 'sales@glr.co' };
  // import stays locked after the sales release (owner ruling 2026-10-22); sales does not.
  const importUser = { employeeId: 42, name: 'ฝ่ายนำเข้า ทดสอบ', role: 'import', email: 'import@glr.co' };
  const accountUser = { employeeId: 43, name: 'ฝ่ายบัญชี ทดสอบ', role: 'account', email: 'account@glr.co' };

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

  // Owner ruling 2026-11-17: import and account stay locked (paths) but land on their own dashboards.
  it('lands a locked import user on the import dashboard, not self-service', async () => {
    await renderLockedAppAt('/', importUser);

    expect(await screen.findByRole('heading', { name: 'งานนำเข้า' })).toBeTruthy();
    expect(screen.queryByText(/เวลาทำงานและคำขอของคุณ/)).toBeNull();
  });

  it('lands a locked account user on the finance dashboard, not self-service', async () => {
    await renderLockedAppAt('/', accountUser);

    expect(await screen.findByRole('heading', { name: 'ภาพรวมการเงิน' })).toBeTruthy();
    expect(screen.queryByText(/เวลาทำงานและคำขอของคุณ/)).toBeNull();
  });

  // Sales release (owner ruling 2026-10-22): a sales rep is exempt and gets SalesOverview back.
  it('lands a sales rep on SalesOverview — sales is exempt from the lock', async () => {
    await renderLockedAppAt('/', salesUser);

    expect(await screen.findByRole('heading', { name: 'งานวันนี้' })).toBeTruthy();
    expect(screen.queryByText(/เวลาทำงานและคำขอของคุณ/)).toBeNull();
  });

  it('refuses a locked role the deal pipeline at /tickets', async () => {
    await renderLockedAppAt('/tickets', importUser);

    expect(await screen.findByRole('heading', { name: 'ไม่มีสิทธิ์เข้าถึงหน้านี้' })).toBeTruthy();
  });
});

describe('App / last page memory for the feedback form', () => {
  it('forgets the previous page on logout, so the next user in the same tab starts blank', async () => {
    const { api: mocked } = await import('./api/index.js');
    mocked.auth.me.mockResolvedValue({ user: { employeeId: 41, name: 'พนักงานขาย ทดสอบ', role: 'sales', email: 'sales@glr.co' } });
    mocked.auth.logout.mockResolvedValue({});
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={['/leave']}><App /></MemoryRouter>
      </QueryClientProvider>,
    );
    await waitFor(() => expect(getLastPage()).toBe('/leave'));

    fireEvent.click(await screen.findByRole('button', { name: 'เมนูผู้ใช้' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: /ออกจากระบบ/ }));

    await waitFor(() => expect(getLastPage()).toBeNull());
  });
});
