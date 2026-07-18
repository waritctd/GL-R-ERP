import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App.jsx';

vi.mock('./api/index.js', () => ({
  api: {
    auth: {
      me: vi.fn(),
      login: vi.fn(),
      logout: vi.fn(),
      changePassword: vi.fn(),
    },
  },
  ROLE_PERMISSIONS: {
    canUseEmployeeExperience: ['employee'],
    canSubmitProfileRequests: ['employee'],
    canViewEmployees: ['hr'],
    canManageEmployees: ['hr'],
    canReviewProfileRequests: ['hr'],
    canSubmitCommissions: ['sales', 'sales_manager', 'ceo'],
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
