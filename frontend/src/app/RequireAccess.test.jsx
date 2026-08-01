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
});
