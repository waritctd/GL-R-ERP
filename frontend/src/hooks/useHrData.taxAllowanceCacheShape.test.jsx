import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';

globalThis.React = React;

// Both consumers reach the same mocked module, which is the point — they must also agree on the
// shape that comes back through it.
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

/**
 * `useHrData` and `TaxAllowancePage` both register a query under
 * `queryKeys.taxAllowanceDeclarationsMe(year)`. React Query keeps ONE cache entry per key, so the
 * queryFn that resolves first decides the shape BOTH of them then read — and `useHrData` mounts at
 * the app shell, so on any visit to /tax-allowance it wins that race every time.
 *
 * Neither component's own test suite can see this: each mocks the API and renders alone, so each
 * only ever meets the shape it wrote itself. The collision only exists when both are mounted, which
 * is exactly what production always does and what no test did.
 *
 * It has already happened once in this file's neighbourhood — `useHrData.js`'s own
 * `taxAllowanceEvidenceQuery` carries a comment about a bare count cached under the attachments
 * key, which crashed `TaxAllowanceEvidencePanel` with "filter is not a function". Adding
 * `headerPrefill` to the `/declarations/me` envelope re-opened the same hole one query earlier:
 * the hook cached `.items` (an array) while the page had started reading `.items` off the envelope,
 * so the page saw `undefined`, concluded the year had never been filed, and offered an employee
 * with a live PENDING declaration a second filing that the backend refuses with 409.
 *
 * This test mounts the hook and the page together against one QueryClient — the production
 * arrangement — and asserts that BOTH still see the declaration once everything has settled.
 * Asserting on either one alone is vacuous; see the comment on the final assertion for why.
 */
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

  /**
   * Renders the shell hook's own view of the declaration into the DOM.
   *
   * A `renderHook` + `result.current` assertion is NOT enough here and was tried first: when the
   * hook throws mid-render, `result.current` keeps the last value that rendered successfully, so
   * the assertion reads a stale PENDING and passes while the shell is on fire. Mounting the hook as
   * a real component in the same tree as the page fixes that — a throw tears the tree down and the
   * probe's text is simply gone.
   */
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

    // ...and the SHELL still sees it too, after the page's own refetch has overwritten the entry.
    // This is the half that catches the bug. Asserting on the page alone is vacuous: both queries
    // refetch on mount, so the page's own queryFn lands last and the page looks right whatever the
    // hook cached. The damage lands on the other consumer — `selectCurrentDeclaration` opens with
    // `items.filter(...)`, so the hook handed the page's envelope throws "filter is not a function"
    // out of the APP SHELL, on every screen. Verified by mutation: reverting `useHrData` to cache a
    // bare array turns this assertion red and leaves the page assertions above green.
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
