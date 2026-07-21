import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RequestsPage } from './RequestsPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    overtime: {
      employees: vi.fn(),
      list: vi.fn(),
      create: vi.fn(),
      approve: vi.fn(),
      reject: vi.fn(),
      cancel: vi.fn(),
    },
    specialMoney: {
      employees: vi.fn(),
      types: vi.fn(),
      list: vi.fn(),
      usage: vi.fn(),
      create: vi.fn(),
      approve: vi.fn(),
      reject: vi.fn(),
      cancel: vi.fn(),
    },
  },
}));

const user = {
  employeeId: 1,
  name: 'พนักงาน ทดสอบ',
  role: 'employee',
  manager: false,
};

const currentEmployee = {
  id: 1,
  nameTh: 'พนักงาน ทดสอบ',
};

function renderRequestsPage(initialEntry = '/employee-requests') {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <RequestsPage user={user} currentEmployee={currentEmployee} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('RequestsPage tab bar', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.overtime.employees.mockResolvedValue({ employees: [] });
    api.overtime.list.mockResolvedValue({ requests: [] });
    api.specialMoney.employees.mockResolvedValue({ employees: [] });
    api.specialMoney.types.mockResolvedValue({ types: [] });
    api.specialMoney.list.mockResolvedValue({ requests: [] });
    api.specialMoney.usage.mockResolvedValue({
      usage: { employeeId: 1, year: 2026, approvedAmountThisYearByType: {}, approvedCountLifetimeByType: {} },
    });
  });

  it('renders both tabs and defaults to the overtime tab', async () => {
    renderRequestsPage('/employee-requests');

    const tabs = screen.getAllByRole('tab');
    expect(tabs).toHaveLength(2);
    expect(screen.getByRole('tab', { name: 'ล่วงเวลา' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: 'สวัสดิการ / เงินพิเศษ' }).getAttribute('aria-selected')).toBe('false');
    // The OT panel deliberately has no page heading of its own — RequestsPage owns the only one,
    // otherwise the tab renders two stacked headers. Identify the panel by its form instead.
    expect(screen.getAllByRole('heading', { name: 'คำขอ' })).toHaveLength(1);
    expect(await screen.findByLabelText(/วันที่ทำ OT/)).not.toBeNull();
  });

  it('selects the welfare panel when ?tab=welfare is in the query string', async () => {
    renderRequestsPage('/employee-requests?tab=welfare');

    expect(screen.getByRole('tab', { name: 'สวัสดิการ / เงินพิเศษ' }).getAttribute('aria-selected')).toBe('true');
    expect(await screen.findByRole('heading', { name: 'ยื่นคำขอเงินสวัสดิการ' })).not.toBeNull();
  });
});
