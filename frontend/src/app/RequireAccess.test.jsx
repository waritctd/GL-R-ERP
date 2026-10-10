import React from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
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
