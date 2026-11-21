import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketDetailRoute, AccountFinanceRedirect } from './TicketDetailRoute.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, api: { tickets: { get: vi.fn() } } };
});

vi.mock('./delivery/RoundWorkView.jsx', () => ({ RoundWorkView: ({ ticketId }) => <div>ROUND-WORK-VIEW {ticketId}</div> }));

// Stand-in for the real page: it reads the ticket on mount, exactly like TicketDetailPage's first query.
vi.mock('./TicketDetailPage.jsx', () => ({
  TicketDetailPage: ({ ticketId }) => {
    Promise.resolve(api.tickets.get(ticketId)).catch(() => {});
    return <div>TICKET-DETAIL-PAGE</div>;
  },
}));

function Where() {
  const location = useLocation();
  return <div data-testid="where">{location.pathname}</div>;
}

function renderAt(user) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter initialEntries={['/tickets/501']}>
      <Routes>
        <Route path="/tickets/:id" element={<React.Suspense fallback="loading"><TicketDetailRoute user={user} showToast={vi.fn()} /></React.Suspense>} />
        <Route path="/finance/deals/:id" element={<div>FINANCE-DEAL-PAGE</div>} />
      </Routes>
      <Where />
    </MemoryRouter>
    </QueryClientProvider>,
  );
}

function renderDeposit(user) {
  return render(
    <MemoryRouter initialEntries={['/tickets/501/deposit']}>
      <Routes>
        <Route path="/tickets/:ticketId/deposit" element={<AccountFinanceRedirect user={user}><div>DEPOSIT-PAGE</div></AccountFinanceRedirect>} />
        <Route path="/finance/deals/:id" element={<div>FINANCE-DEAL-PAGE</div>} />
      </Routes>
      <Where />
    </MemoryRouter>,
  );
}

describe('AccountFinanceRedirect (deposit sub-route)', () => {
  it('account is redirected from /tickets/:ticketId/deposit to /finance/deals/:ticketId', async () => {
    renderDeposit({ role: 'account' });
    expect(await screen.findByText('FINANCE-DEAL-PAGE')).not.toBeNull();
    expect(screen.getByTestId('where').textContent).toBe('/finance/deals/501');
  });
});

describe('TicketDetailRoute (H1 lockdown)', () => {
  beforeEach(() => vi.clearAllMocks());

  it('account is redirected to /finance/deals/:id and api.tickets.get is never called', async () => {
    renderAt({ role: 'account', name: 'บัญชี' });
    expect(await screen.findByText('FINANCE-DEAL-PAGE')).not.toBeNull();
    expect(screen.getByTestId('where').textContent).toBe('/finance/deals/501');
    expect(api.tickets.get).not.toHaveBeenCalled();
  });

  it('warehouse gets the round work view, never the deal page', async () => {
    renderAt({ role: 'warehouse', name: 'คลัง' });
    expect(await screen.findByText('ROUND-WORK-VIEW 501')).not.toBeNull();
    expect(screen.queryByText('TICKET-DETAIL-PAGE')).toBeNull();
    expect(api.tickets.get).not.toHaveBeenCalled();
  });

  it('an invoice issuer of a role with no deal page gets the round work view; a rep who is also an issuer keeps the deal page', async () => {
    renderAt({ role: 'qc', name: 'คิวซี', canIssueTaxInvoice: true });
    expect(await screen.findByText('ROUND-WORK-VIEW 501')).not.toBeNull();
    expect(api.tickets.get).not.toHaveBeenCalled();
  });

  // v2.3.0: a read-all holder reads the whole page even as warehouse or issuer; the same role without the flag keeps the round view.
  it('a flagged warehouse or qc issuer gets the full deal page, not the round work view', async () => {
    api.tickets.get.mockResolvedValue({ ticket: {} });
    for (const user of [{ role: 'warehouse', name: 'คลัง', readAllSales: true }, { role: 'qc', name: 'คิวซี', canIssueTaxInvoice: true, readAllSales: true }]) {
      renderAt(user);
      expect(await screen.findByText('TICKET-DETAIL-PAGE'), user.role).not.toBeNull();
      expect(screen.queryByText('ROUND-WORK-VIEW 501')).toBeNull();
      cleanup();
    }
  });

  it('a rep who also holds the issuer grant keeps the deal page, unless that deal refuses them: then the round work view', async () => {
    api.tickets.get.mockResolvedValue({ ticket: {} });
    renderAt({ role: 'sales', name: 'ขาย', canIssueTaxInvoice: true });
    expect(await screen.findByText('TICKET-DETAIL-PAGE')).not.toBeNull();
    cleanup();
    api.tickets.get.mockRejectedValue(Object.assign(new Error('x'), { status: 403 }));
    renderAt({ role: 'sales', name: 'ขาย', canIssueTaxInvoice: true });
    expect(await screen.findByText('ROUND-WORK-VIEW 501')).not.toBeNull();
  });
});
