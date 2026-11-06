import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppShell } from './AppShell.jsx';

globalThis.React = React;

const { stop } = vi.hoisted(() => ({ stop: vi.fn() }));

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      notifications: { list: vi.fn().mockResolvedValue({ notifications: [] }), markRead: vi.fn() },
      viewAs: { candidates: vi.fn().mockResolvedValue([]), start: vi.fn(), stop },
    },
  };
});

const target = { role: 'hr', employeeId: 2, name: 'ทดสอบ เป้าหมาย', email: 'target@test.local' };

function renderShell(user) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<AppShell user={user} employee={null} onLogout={vi.fn()} pendingRequestCount={0} />}>
            <Route index element={<div>เนื้อหา</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  stop.mockReset();
});

describe('view-as banner', () => {
  it('names the viewed employee and offers the exit; exit stops the view then reloads at /', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    stop.mockResolvedValue({});
    renderShell({ ...target, canViewAs: true, viewAs: { actorId: 1, actorName: 'เจ้าของ' } });

    await screen.findByText('เนื้อหา');
    expect(screen.getByText(/กำลังดูในมุมมองของ ทดสอบ เป้าหมาย/)).toBeTruthy();
    expect(screen.getByText(/อ่านอย่างเดียว/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'ออกจากมุมมองนี้' }));

    await waitFor(() => expect(assign).toHaveBeenCalledWith('/'));
    expect(stop).toHaveBeenCalledTimes(1);
  });

  it('a failed stop does not reload into the view: shows the error and re-enables the button', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    stop.mockRejectedValue(new Error('หยุดไม่ได้'));
    renderShell({ ...target, canViewAs: true, viewAs: { actorId: 1, actorName: 'เจ้าของ' } });

    await screen.findByText('เนื้อหา');
    fireEvent.click(screen.getByRole('button', { name: 'ออกจากมุมมองนี้' }));

    expect((await screen.findByRole('alert')).textContent).toContain('หยุดไม่ได้');
    expect(assign).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'ออกจากมุมมองนี้' }).disabled).toBe(false);
  });

  it('shows no banner when not viewing', async () => {
    renderShell({ ...target, canViewAs: true, viewAs: null });

    await screen.findByText('เนื้อหา');
    expect(screen.queryByText(/กำลังดูในมุมมองของ/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'ออกจากมุมมองนี้' })).toBeNull();
  });
});

describe('view-as picker entry in the user menu', () => {
  async function openMenu(user) {
    renderShell(user);
    await screen.findByText('เนื้อหา');
    fireEvent.click(screen.getByRole('button', { name: 'เมนูผู้ใช้' }));
  }

  it('is offered when canViewAs and not already viewing', async () => {
    await openMenu({ ...target, canViewAs: true, viewAs: null });
    expect(screen.getByRole('menuitem', { name: /ดูในมุมมองพนักงาน/ })).toBeTruthy();
  });

  it('is hidden without canViewAs', async () => {
    await openMenu({ ...target, canViewAs: false, viewAs: null });
    expect(screen.queryByRole('menuitem', { name: /ดูในมุมมองพนักงาน/ })).toBeNull();
  });

  it('is hidden while already viewing', async () => {
    await openMenu({ ...target, canViewAs: true, viewAs: { actorId: 1, actorName: 'เจ้าของ' } });
    expect(screen.queryByRole('menuitem', { name: /ดูในมุมมองพนักงาน/ })).toBeNull();
  });
});
