import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';

globalThis.React = React;

// Both consumers reach the same mocked module, which is the point — they must also agree on the shape that
// comes back through it.
vi.mock('../api/index.js', () => ({
  api: {
    employees: { get: vi.fn(), list: vi.fn(), create: vi.fn(), update: vi.fn() },
    profileRequests: { list: vi.fn(), create: vi.fn(), update: vi.fn() },
    dashboard: {},
    payroll: {
      getMyTaxAllowanceDeclarations: vi.fn(),
      getTaxAllowanceCaps: vi.fn(),
      listTaxAllowanceAttachments: vi.fn(),
      submitMyTaxAllowanceDeclaration: vi.fn(),
      withdrawMyTaxAllowanceDeclaration: vi.fn(),
      estimateMyTaxAllowanceDeclaration: vi.fn(),
      uploadTaxAllowanceAttachment: vi.fn(),
    },
  },
}));

import { api } from '../api/index.js';
import { queryKeys } from '../api/queryKeys.js';
import { useHrData } from './useHrData.js';
import { TaxAllowancePage } from '../features/taxAllowance/TaxAllowancePage.jsx';

/** `useHrData` and `TaxAllowancePage` both register a query under `queryKeys.taxAllowanceDeclarationsMe(year)`.
 * Neither component's own test suite can see this: each mocks the API and renders alone, so each only ever
 * meets the shape it wrote itself. */
const currentYear = new Date().getFullYear();
const user = { role: 'employee', name: 'สมชาย ใจดี', employeeId: 5 };

const envelope = {
  taxYear: currentYear,
  items: [{
    declarationId: 55,
    employeeId: 5,
    status: 'PENDING',
    submittedAt: `${currentYear}-03-01T00:00:00.000Z`,
    appliedAt: null,
    appliedEffectiveMonth: null,
    effectiveMonth: null,
    expiresOn: null,
    reviewerNote: null,
    allowances: {},
  }],
  headerPrefill: {
    taxpayerId: '1103700000011',
    firstNameTh: 'สมชาย',
    lastNameTh: 'ใจดี',
    maritalState: 'SINGLE',
    address: {
      building: null, roomNo: null, floor: null, village: null, houseNo: '123/45', moo: null,
      soi: null, junction: null, road: null, subDistrict: null, district: null,
      province: null, postalCode: null,
    },
  },
};

describe('taxAllowanceDeclarationsMe — one cache key, two consumers', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.employees.get.mockResolvedValue({ employee: { id: 5 } });
    api.employees.list.mockResolvedValue({ employees: [] });
    api.profileRequests.list.mockResolvedValue({ profileRequests: [] });
    api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue(envelope);
    api.payroll.getTaxAllowanceCaps.mockResolvedValue({ caps: [] });
    api.payroll.listTaxAllowanceAttachments.mockResolvedValue({ items: [] });
  });

  /** Renders the shell hook's own view of the declaration into the DOM. Mounting the hook as a real component in
   * the same tree as the page fixes that — a throw tears the tree down and the probe's text is simply gone. */
  function ShellProbe() {
    const { taxAllowanceSummary } = useHrData({ user, showToast: vi.fn() });
    return <div data-testid="shell-status">{taxAllowanceSummary.statusInfo.key}</div>;
  }

  it('the shell hook and the page agree on the shape they share under one key', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });

    // The production arrangement: the shell hook and the page mounted together, one client.
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/tax-allowance']}>
          <ShellProbe />
          <TaxAllowancePage user={user} showToast={vi.fn()} />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    // The PAGE sees the declaration: a live PENDING one, so withdrawal is the offered action.
    expect(await screen.findByRole('button', { name: 'ยกเลิกการยื่น' })).not.toBeNull();
    expect(screen.queryByText('ยังไม่ได้ยื่น')).toBeNull();

    // ...and the SHELL still sees it too, after the page's own refetch has overwritten the entry. This is the half
    // that catches the bug. Asserting on the page alone is vacuous: both queries refetch on mount, so the page's
    // own queryFn lands last and the page looks right whatever the hook cached.
    await waitFor(() => {
      expect(screen.getByTestId('shell-status').textContent).toBe('PENDING');
    });

    // One key, one entry, one shape.
    expect(queryClient.getQueryCache().findAll({ queryKey: ['taxAllowanceDeclarations', 'me'] }))
      .toHaveLength(1);
    const cached = queryClient.getQueryData(queryKeys.taxAllowanceDeclarationsMe(currentYear));
    expect(Array.isArray(cached), 'a bare array is the pre-fix shape that broke the shell').toBe(false);
    expect(cached.items).toHaveLength(1);
    expect(cached.headerPrefill).toBeTruthy();
  });
});
