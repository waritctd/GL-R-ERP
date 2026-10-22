import React from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RequireAccess } from './RequireAccess.jsx';

globalThis.React = React;

function renderGuard({ initialPath, user }) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route element={<RequireAccess user={user} />}>
          <Route path="/employees" element={<div>รายชื่อพนักงาน</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe('RequireAccess', () => {
  it('renders an in-place access-denied page for denied routes', async () => {
    renderGuard({
      initialPath: '/employees',
      user: { role: 'employee', employeeId: 5 },
    });

    expect(await screen.findByRole('heading', { name: 'ไม่มีสิทธิ์เข้าถึงหน้านี้' })).not.toBeNull();
    expect(screen.getByText('/employees')).not.toBeNull();
    expect(screen.queryByText('รายชื่อพนักงาน')).toBeNull();
  });

  it('renders the nested route when the user has access', () => {
    renderGuard({
      initialPath: '/employees',
      user: { role: 'hr', employeeId: 1 },
    });

    expect(screen.getByText('รายชื่อพนักงาน')).not.toBeNull();
  });

  // Import no longer opens the whole-deal page (its GET 403s). A stale link to it — a backend
  // notification deep-link, a bookmark — lands on the import user's OWN page for that deal
  // rather than a dead end.
  function renderDealRoutes(user) {
    return render(
      <MemoryRouter initialEntries={['/tickets/12']}>
        <Routes>
          <Route element={<RequireAccess user={user} />}>
            <Route path="/tickets/:id" element={<div>หน้าดีลเต็ม</div>} />
            <Route path="/import/deals/:ticketId" element={<div>หน้าดีลฝ่ายนำเข้า</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
  }

  it('redirects import from /tickets/:id to /import/deals/:id instead of showing the whole deal', () => {
    renderDealRoutes({ role: 'import', employeeId: 2 });
    expect(screen.getByText('หน้าดีลฝ่ายนำเข้า')).not.toBeNull();
    expect(screen.queryByText('หน้าดีลเต็ม')).toBeNull();
  });

  it('still renders the whole deal for the CEO', () => {
    renderDealRoutes({ role: 'ceo', employeeId: 1 });
    expect(screen.getByText('หน้าดีลเต็ม')).not.toBeNull();
  });
});

// Production runs with the self-service lock ON, and under it canAccessPath refuses account every
// /tickets path — so TicketDetailRoute's own account -> /finance/deals/:id redirect never mounts.
// The deposit-notice notification sent to account links /tickets/:id; it must still land on the
// finance deal page. features.js reads the flag at module load and vitest.config.js pins it OFF,
// so this needs vi.stubEnv + vi.resetModules + a fresh dynamic import of the guard.
describe('RequireAccess under the self-service lock', () => {
  const account = { role: 'account', employeeId: 3 };

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  function Probe({ label }) {
    const { pathname, search } = useLocation();
    return <div>{`${label} ${pathname}${search}`}</div>;
  }

  async function renderLocked(initialPath, user) {
    vi.stubEnv('VITE_SELF_SERVICE_ONLY', 'true');
    vi.resetModules();
    const { RequireAccess: LockedRequireAccess } = await import('./RequireAccess.jsx');
    return render(
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route element={<LockedRequireAccess user={user} />}>
            <Route path="/tickets" element={<Probe label="pipeline" />} />
            <Route path="/tickets/:id" element={<Probe label="whole-deal" />} />
            <Route path="/tickets/:ticketId/deposit" element={<Probe label="deposit" />} />
            <Route path="/quotations/:id" element={<Probe label="quotation" />} />
            <Route path="/finance/deals/:id" element={<Probe label="finance-deal" />} />
            <Route path="/import/deals/:ticketId" element={<Probe label="import-deal" />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
  }

  const denied = () => screen.queryByRole('heading', { name: 'ไม่มีสิทธิ์เข้าถึงหน้านี้' });

  it('sends account from /tickets/:id to /finance/deals/:id instead of the access-denied page', async () => {
    await renderLocked('/tickets/123', account);
    expect(screen.getByText('finance-deal /finance/deals/123')).not.toBeNull();
    expect(denied()).toBeNull();
  });

  it('sends account from /tickets/:id/deposit to /finance/deals/:id', async () => {
    await renderLocked('/tickets/123/deposit', account);
    expect(screen.getByText('finance-deal /finance/deals/123')).not.toBeNull();
    expect(denied()).toBeNull();
  });

  // The query string is dropped, like import's redirect: FinanceDealPage reads no search params.
  it('still redirects account when the deal link carries a query string', async () => {
    await renderLocked('/tickets/123?from=notification', account);
    expect(screen.getByText('finance-deal /finance/deals/123')).not.toBeNull();
    expect(denied()).toBeNull();
  });

  it('still refuses account the deal pipeline list at /tickets', async () => {
    await renderLocked('/tickets', account);
    expect(denied()).not.toBeNull();
    expect(screen.queryByText(/^pipeline /)).toBeNull();
    expect(screen.queryByText(/^finance-deal /)).toBeNull();
  });

  it('still refuses account /quotations/:id', async () => {
    await renderLocked('/quotations/5', account);
    expect(denied()).not.toBeNull();
    expect(screen.queryByText(/^finance-deal /)).toBeNull();
  });

  it('still sends import from /tickets/:id to /import/deals/:id', async () => {
    await renderLocked('/tickets/123', { role: 'import', employeeId: 2 });
    expect(screen.getByText('import-deal /import/deals/123')).not.toBeNull();
  });

  it('does not redirect a sales rep (exempt from the lock) off the whole deal', async () => {
    await renderLocked('/tickets/123', { role: 'sales', employeeId: 9 });
    expect(screen.getByText('whole-deal /tickets/123')).not.toBeNull();
    expect(screen.queryByText(/^finance-deal /)).toBeNull();
  });
});
