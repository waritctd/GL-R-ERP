import { render, screen, waitFor } from '@testing-library/react';
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
