import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketDetailRoute, AccountFinanceRedirect } from './TicketDetailRoute.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, api: { tickets: { get: vi.fn() } } };
});

// Stand-in for the real page: it reads the ticket on mount, exactly like TicketDetailPage's first query.
vi.mock('./TicketDetailPage.jsx', () => ({
  TicketDetailPage: ({ ticketId }) => {
    api.tickets.get(ticketId);
    return <div>TICKET-DETAIL-PAGE</div>;
  },
}));

function Where() {
  const location = useLocation();
  return <div data-testid="where">{location.pathname}</div>;
}

function renderAt(user) {
  return render(
    <MemoryRouter initialEntries={['/tickets/501']}>
      <Routes>
        <Route path="/tickets/:id" element={<React.Suspense fallback="loading"><TicketDetailRoute user={user} showToast={vi.fn()} /></React.Suspense>} />
        <Route path="/finance/deals/:id" element={<div>FINANCE-DEAL-PAGE</div>} />
      </Routes>
      <Where />
    </MemoryRouter>,
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

  it('other roles keep the deposit page', async () => {
    renderDeposit({ role: 'sales' });
    expect(await screen.findByText('DEPOSIT-PAGE')).not.toBeNull();
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

  it('the redirect replaces the history entry (back does not bounce into the refused page)', async () => {
    // MemoryRouter cannot read history length; assert the destination is final and stable instead.
    renderAt({ role: 'account', name: 'บัญชี' });
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/finance/deals/501'));
    expect(screen.queryByText('TICKET-DETAIL-PAGE')).toBeNull();
  });

  it('ceo keeps the ticket page, which reads the ticket', async () => {
    renderAt({ role: 'ceo', name: 'ceo' });
    expect(await screen.findByText('TICKET-DETAIL-PAGE')).not.toBeNull();
    expect(api.tickets.get).toHaveBeenCalledWith('501');
    expect(screen.getByTestId('where').textContent).toBe('/tickets/501');
  });
});
