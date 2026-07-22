import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
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
    // PricingRequestQueuePage's only query, needed for the /pricing-requests
    // guard test below.
    pricingRequests: {
      queue: vi.fn().mockResolvedValue({ items: [] }),
    },
  },
  ROLE_PERMISSIONS: {
    canUseEmployeeExperience: ['employee'],
    canSubmitProfileRequests: ['employee'],
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

  it('redirects a role without canViewPricingRequestQueue (sales) to the dashboard instead', async () => {
    renderAppAt('/pricing-requests', salesUser);

    // RequireAccess denies the path and <Navigate to="/" replace /> lands on
    // EmployeeDashboard, whose PageHeader greets the logged-in user by name.
    expect(await screen.findByRole('heading', { name: `สวัสดี, คุณ${salesUser.name}` })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'คิวขอราคา' })).toBeNull();
  });
});
