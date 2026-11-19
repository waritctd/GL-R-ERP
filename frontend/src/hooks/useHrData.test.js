import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

// Mock the API layer so the hook is exercised in isolation.
vi.mock('../api/index.js', () => ({
  api: {
    employees: { get: vi.fn(), list: vi.fn(), create: vi.fn(), update: vi.fn() },
    profileRequests: { list: vi.fn(), create: vi.fn(), update: vi.fn() },
  },
}));

import { api } from '../api/index.js';
import { useHrData } from './useHrData.js';

const employeeUser = { role: 'employee', employeeId: 5 };

function setup(user) {
  const showToast = vi.fn();
  // Fresh client per test with retries off so failures surface immediately.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }) => createElement(QueryClientProvider, { client: queryClient }, children);
  const view = renderHook(() => useHrData({ user, showToast }), { wrapper });
  return { ...view, showToast, queryClient };
}

describe('useHrData reads', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Safe defaults so tests are order-independent; individual tests override as needed.
    api.employees.get.mockResolvedValue({ employee: { id: 10 } });
    api.employees.list.mockResolvedValue({ employees: [] });
    api.profileRequests.list.mockResolvedValue({ profileRequests: [] });
  });

  it('derives the list from the current employee when the user cannot view all employees', async () => {
    api.employees.get.mockResolvedValue({ employee: { id: 5 } });

    const { result } = setup(employeeUser);

    await waitFor(() => {
      expect(result.current.currentEmployee).toEqual({ id: 5 });
    });
    expect(result.current.employees).toEqual([{ id: 5 }]);
    expect(api.employees.list).not.toHaveBeenCalled();
    expect(api.employees.get).toHaveBeenCalledWith(5);
  });
});
