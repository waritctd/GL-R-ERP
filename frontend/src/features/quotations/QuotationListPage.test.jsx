import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationListPage } from './QuotationListPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      dealQuotations: {
        list: vi.fn(),
        counts: vi.fn(),
      },
    },
  };
});

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales' };
const ceoUser = { id: 8, name: 'ราม', role: 'ceo' };
const salesManagerUser = { id: 9, name: 'ผึ้ง', role: 'sales_manager' };

function row(overrides = {}) {
  return {
    id: 1,
    number: 'QD69-0001',
    customerName: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด',
    projectName: null,
    salesRepName: 'คุณสมหมาย ขายดี',
    grandTotal: 123456.78,
    docStatus: 'DRAFT',
    quotationDate: '2026-09-01',
    ...overrides,
  };
}

// Prints the current search string so a test can assert the tab actually travels in the URL
// (owner feedback F5: "driven by the URL (`?status=`)") rather than only in component state.
function LocationProbe() {
  const location = useLocation();
  return <p data-testid="location-search">{location.search}</p>;
}

function renderListPage(user, initialPath = '/quotations') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route
            path="/quotations"
            element={<><QuotationListPage user={user} /><LocationProbe /></>}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('QuotationListPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.dealQuotations.list.mockResolvedValue({ items: [row()] });
    // BARE, no `{ counts: ... }` envelope — DealQuotationController#counts returns the
    // DealQuotationCountsDto record itself (review finding MED-3). This fixture wrapped it while
    // the page still read `r?.counts ?? r`, so it passed on the tolerance rather than on the
    // contract; with the tolerance gone the fixture has to match the controller.
    api.dealQuotations.counts.mockResolvedValue({ all: 7, pendingApproval: 2, needsRework: 3, cancelled: 1, approved: 4 });
  });

  it('sales lands on ทั้งหมด (no approval queue of its own)', async () => {
    renderListPage(salesUser);

    expect(await screen.findByText('QD69-0001')).not.toBeNull();
    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({}));
  });

  it('sales_manager / ceo default to the รออนุมัติ queue', async () => {
    renderListPage(ceoUser);
    await screen.findByText('QD69-0001');
    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({ status: 'PENDING_APPROVAL' }));

    vi.clearAllMocks();
    api.dealQuotations.list.mockResolvedValue({ items: [row()] });
    api.dealQuotations.counts.mockResolvedValue({});
    renderListPage(salesManagerUser);
    await screen.findByText('QD69-0001');
    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({ status: 'PENDING_APPROVAL' }));
  });

  // F5: the five tabs the owner asked for, in order — ร่าง and ถูกแทนที่ are deliberately gone.
  it('renders exactly ทั้งหมด · รออนุมัติ · แก้ · ยกเลิก · อนุมัติแล้ว', async () => {
    renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    const labels = screen.getAllByRole('tab').map((tab) => tab.textContent.replace(/\d+$/, ''));
    expect(labels).toEqual(['ทั้งหมด', 'รออนุมัติ', 'แก้', 'ยกเลิก', 'อนุมัติแล้ว']);
    expect(screen.queryByRole('tab', { name: /ถูกแทนที่/ })).toBeNull();
  });

  it('shows each tab its own count from GET /deal-quotations/counts', async () => {
    renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    await waitFor(() => expect(api.dealQuotations.counts).toHaveBeenCalledTimes(1));
    const tabs = await screen.findAllByRole('tab');
    // Label + count, concatenated, in DEAL_QUOTATION_STATUS_TABS order.
    await waitFor(() => expect(tabs.map((t) => t.textContent)).toEqual([
      'ทั้งหมด7', 'รออนุมัติ2', 'แก้3', 'ยกเลิก1', 'อนุมัติแล้ว4',
    ]));
  });

  // Counts are advisory chrome. A failed counts call must not blank the list or print a 0 that
  // would read as "nothing here" — the tabs simply carry no number.
  it('still renders the tabs and the list when the counts request fails', async () => {
    api.dealQuotations.counts.mockRejectedValue(new Error('boom'));
    renderListPage(salesUser);

    expect(await screen.findByText('QD69-0001')).not.toBeNull();
    const tabs = await screen.findAllByRole('tab');
    expect(tabs.map((t) => t.textContent)).toEqual(['ทั้งหมด', 'รออนุมัติ', 'แก้', 'ยกเลิก', 'อนุมัติแล้ว']);
  });

  it('drives the แก้ tab off needsRework=true, not a docStatus, and writes it to the URL', async () => {
    renderListPage(salesUser);
    await screen.findByText('QD69-0001');

    fireEvent.click(screen.getByRole('tab', { name: /^แก้/ }));

    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({ needsRework: true }));
    await waitFor(() => expect(screen.getByTestId('location-search').textContent).toBe('?status=NEEDS_REWORK'));
  });

  it('opens the tab named by ?status= rather than the role default', async () => {
    renderListPage(ceoUser, '/quotations?status=CANCELLED');
    await screen.findByText('QD69-0001');

    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({ status: 'CANCELLED' }));
    expect(api.dealQuotations.list).not.toHaveBeenCalledWith({ status: 'PENDING_APPROVAL' });
    expect(screen.getByRole('tab', { name: /^ยกเลิก/ }).getAttribute('aria-selected')).toBe('true');
  });

  // A retired or mistyped key must fall back to this role's default tab, not render an empty list.
  it('falls back to the role default when ?status= names no tab', async () => {
    renderListPage(ceoUser, '/quotations?status=SUPERSEDED');
    await screen.findByText('QD69-0001');

    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({ status: 'PENDING_APPROVAL' }));
  });

  // Regression: an approver's default tab is รออนุมัติ, and "no ?status=" MEANS that default —
  // so ทั้งหมด has to write its own key. Clearing the param instead bounced the CEO straight back
  // to รออนุมัติ, making ทั้งหมด unreachable for the one role whose default is not it.
  it('lets an approver actually reach ทั้งหมด, writing status=all to the URL', async () => {
    renderListPage(ceoUser);
    await screen.findByText('QD69-0001');

    fireEvent.click(screen.getByRole('tab', { name: /^ทั้งหมด/ }));

    await waitFor(() => expect(api.dealQuotations.list).toHaveBeenCalledWith({}));
    expect(screen.getByTestId('location-search').textContent).toBe('?status=all');
    expect(screen.getByRole('tab', { name: /^ทั้งหมด/ }).getAttribute('aria-selected')).toBe('true');
  });

  it('renders the required columns: number, customer, sales rep, total, status, date', async () => {
    renderListPage(salesUser);

    await screen.findByText('QD69-0001');
    expect(screen.getByText('บริษัท แฟชั่นไอส์แลนด์ จำกัด')).not.toBeNull();
    expect(screen.getByText('คุณสมหมาย ขายดี')).not.toBeNull();
    // ร่าง is no longer a tab, so the row's own StatusBadge is the only place it appears.
    expect(screen.getByText('ร่าง')).not.toBeNull();
  });
});
