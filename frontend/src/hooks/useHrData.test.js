import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

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
  const view = renderHook(() => useHrData({ user, showToast }));
  return { ...view, showToast };
}

describe('useHrData.loadData', () => {
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
    await act(async () => {
      await result.current.loadData(hrUser);
    });

    expect(api.employees.list).toHaveBeenCalledTimes(1);
    expect(result.current.employees).toEqual([{ id: 1 }, { id: 2 }]);
  });

  it('derives the list from the current employee when the user cannot view all employees', async () => {
    api.employees.get.mockResolvedValue({ employee: { id: 5 } });

    const { result } = setup(employeeUser);
    await act(async () => {
      await result.current.loadData(employeeUser);
    });

    expect(api.employees.list).not.toHaveBeenCalled();
    expect(api.employees.get).toHaveBeenCalledWith(5);
    expect(result.current.employees).toEqual([{ id: 5 }]);
    expect(result.current.currentEmployee).toEqual({ id: 5 });
  });

  it('tolerates a failed profile-requests load by falling back to an empty list', async () => {
    api.employees.list.mockResolvedValue({ employees: [] });
    api.profileRequests.list.mockRejectedValue(new Error('boom'));

    const { result } = setup(hrUser);
    await act(async () => {
      await result.current.loadData(hrUser);
    });

    expect(result.current.profileRequests).toEqual([]);
  });
});

describe('useHrData mutations', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Safe defaults so tests are order-independent; individual tests override as needed.
    api.employees.get.mockResolvedValue({ employee: { id: 10 } });
    api.employees.list.mockResolvedValue({ employees: [] });
    api.profileRequests.list.mockResolvedValue({ profileRequests: [] });
  });

  it('prepends a created employee and toasts success', async () => {
    api.employees.create.mockResolvedValue({ employee: { id: 9 } });

    const { result, showToast } = setup(hrUser);
    await act(async () => {
      await result.current.createEmployee({ nameTh: 'ทดสอบ' });
    });

    expect(result.current.employees).toEqual([{ id: 9 }]);
    expect(showToast).toHaveBeenCalledWith('success', expect.any(String));
  });

  it('replaces the reviewed request in place', async () => {
    api.employees.list.mockResolvedValue({ employees: [] });
    api.profileRequests.list.mockResolvedValue({ profileRequests: [{ id: 1, status: 'pending' }] });
    api.profileRequests.update.mockResolvedValue({ profileRequest: { id: 1, status: 'approved' } });

    const { result, showToast } = setup(hrUser);
    await act(async () => {
      await result.current.loadData(hrUser);
    });
    await act(async () => {
      await result.current.reviewProfileRequest(1, 'approved');
    });

    expect(api.profileRequests.update).toHaveBeenCalledWith(1, { status: 'approved' });
    expect(result.current.profileRequests).toEqual([{ id: 1, status: 'approved' }]);
    expect(showToast).toHaveBeenCalledWith('success', expect.any(String));
  });
});
