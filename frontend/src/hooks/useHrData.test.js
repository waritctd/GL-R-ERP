import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
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

const hrUser = { role: 'hr', employeeId: 10 };
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

  it('loads the full employee list for a user who can view employees', async () => {
    api.employees.get.mockResolvedValue({ employee: { id: 10 } });
    api.employees.list.mockResolvedValue({ employees: [{ id: 1 }, { id: 2 }] });

    const { result } = setup(hrUser);

    await waitFor(() => {
      expect(result.current.employees).toEqual([{ id: 1 }, { id: 2 }]);
    });
    expect(api.employees.list).toHaveBeenCalledTimes(1);
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

  it('tolerates a failed profile-requests load by falling back to an empty list', async () => {
    api.employees.list.mockResolvedValue({ employees: [] });
    api.profileRequests.list.mockRejectedValue(new Error('boom'));

    const { result } = setup(hrUser);

    await waitFor(() => {
      expect(api.profileRequests.list).toHaveBeenCalled();
    });
    expect(result.current.profileRequests).toEqual([]);
  });
});

describe('useHrData mutations', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.employees.get.mockResolvedValue({ employee: { id: 10 } });
    api.employees.list.mockResolvedValue({ employees: [] });
    api.profileRequests.list.mockResolvedValue({ profileRequests: [] });
  });

  it('creates an employee, refetches the list, and toasts success', async () => {
    api.employees.create.mockResolvedValue({ employee: { id: 9 } });

    const { result, showToast } = setup(hrUser);

    // Wait for the initial list query to settle before mutating.
    await waitFor(() => expect(api.employees.list).toHaveBeenCalledTimes(1));

    // The refetch after invalidation should include the created employee.
    api.employees.list.mockResolvedValue({ employees: [{ id: 9 }] });

    await act(async () => {
      await result.current.createEmployee({ nameTh: 'ทดสอบ' });
    });

    expect(api.employees.create).toHaveBeenCalledWith({ nameTh: 'ทดสอบ' });
    expect(showToast).toHaveBeenCalledWith('success', expect.any(String));
    await waitFor(() => {
      expect(api.employees.list).toHaveBeenCalledTimes(2);
      expect(result.current.employees).toEqual([{ id: 9 }]);
    });
  });

  it('reviews a profile request, refetches the list, and toasts success', async () => {
    api.profileRequests.list.mockResolvedValue({ profileRequests: [{ id: 1, status: 'pending' }] });
    api.profileRequests.update.mockResolvedValue({ profileRequest: { id: 1, status: 'approved' } });

    const { result, showToast } = setup(hrUser);

    await waitFor(() => {
      expect(result.current.profileRequests).toEqual([{ id: 1, status: 'pending' }]);
    });

    api.profileRequests.list.mockResolvedValue({ profileRequests: [{ id: 1, status: 'approved' }] });

    await act(async () => {
      await result.current.reviewProfileRequest(1, 'approved');
    });

    expect(api.profileRequests.update).toHaveBeenCalledWith(1, { status: 'approved' });
    expect(showToast).toHaveBeenCalledWith('success', expect.any(String));
    await waitFor(() => {
      expect(result.current.profileRequests).toEqual([{ id: 1, status: 'approved' }]);
    });
  });
});
