import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AccountFinancePage } from './AccountFinancePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
    },
  };
});

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, useNavigate: () => mockNavigate };
});

const accountUser = { role: 'account', name: 'บัญชี ทดสอบ', employeeId: 3 };

const depositTicket = {
  id: 201, code: 'PR-2026-0201', title: 'โครงการดี', customerName: 'บริษัท ดี จำกัด',
  status: 'quotation_issued', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'NEGOTIATION', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 80000, amountPaid: 0, amountOutstanding: 80000,
  overdue: false, dueDate: null, updatedAt: '2026-07-10T00:00:00.000Z',
};

const finalPaymentTicket = {
  id: 202, code: 'PR-2026-0202', title: 'โครงการอี', customerName: 'บริษัท อี จำกัด',
  status: 'quotation_issued', paymentStatus: 'AWAITING_FINAL_PAYMENT', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'DELIVERY_SCHEDULING', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 120000, amountPaid: 60000, amountOutstanding: 60000,
  overdue: false, dueDate: null, updatedAt: '2026-07-11T00:00:00.000Z',
};

const closedPaidTicket = {
  id: 203, code: 'PR-2026-0203', title: 'โครงการเอฟ', customerName: 'บริษัท เอฟ จำกัด',
  status: 'quotation_issued', paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED',
  lifecycle: 'ACTIVE', salesStage: 'CLOSED_PAID', depositPolicy: 'REQUIRED',
  closeConfirmedAt: '2026-07-01T00:00:00.000Z', invoiceOnFile: true,
  amountPayable: 90000, amountPaid: 90000, amountOutstanding: 0,
  overdue: false, dueDate: null, updatedAt: '2026-07-01T00:00:00.000Z',
};

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/finance']}>
        <AccountFinancePage user={accountUser} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AccountFinancePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockImplementation((params = {}) => {
      if (params.salesStage === 'CLOSED_PAID') return Promise.resolve({ tickets: [closedPaidTicket] });
      return Promise.resolve({ tickets: [depositTicket, finalPaymentTicket] });
    });
  });

  it('renders one worklist row per money-lifecycle stage', async () => {
    renderPage();
    expect(await screen.findByText('บริษัท ดี จำกัด')).not.toBeNull();
    expect(screen.getByText('บริษัท อี จำกัด')).not.toBeNull();
    expect(screen.getByText('บริษัท เอฟ จำกัด')).not.toBeNull();
  });

  it('a deposit-pending row deep-links to /tickets/:id', async () => {
    renderPage();
    const dealName = await screen.findByText('บริษัท ดี จำกัด');
    // Scope to the row: the CTA label also appears as a filter chip above the
    // table, and (for the commission step) matches the row's own plain-text
    // status column too — the <button> inside this specific row is the target.
    const row = dealName.closest('[role="row"]');
    fireEvent.click(within(row).getByRole('button'));
    expect(mockNavigate).toHaveBeenCalledWith('/tickets/201');
  });

  it('the commission row deep-links to the create-from-deal flow (/commissions?ticketId=NN)', async () => {
    renderPage();
    const dealName = await screen.findByText('บริษัท เอฟ จำกัด');
    const row = dealName.closest('[role="row"]');
    fireEvent.click(within(row).getByRole('button'));
    expect(mockNavigate).toHaveBeenCalledWith('/commissions?ticketId=203');
  });

  it('filters the worklist by stage via the filter chips', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    fireEvent.click(screen.getByRole('button', { name: /รอชำระส่วนที่เหลือ/ }));
    expect(screen.queryByText('บริษัท ดี จำกัด')).toBeNull();
    expect(screen.getByText('บริษัท อี จำกัด')).not.toBeNull();
  });
});
