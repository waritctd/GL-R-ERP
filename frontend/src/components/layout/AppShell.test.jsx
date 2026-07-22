import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AppShell } from './AppShell.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      notifications: {
        list: vi.fn().mockResolvedValue({ notifications: [] }),
        markRead: vi.fn(),
      },
    },
  };
});

function renderShell(user) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route element={<AppShell user={user} employee={null} onLogout={vi.fn()} pendingRequestCount={0} />}>
            <Route index element={<div>เนื้อหา</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// Role-scoped views (Import build, docs/role-scoped-views.md): the sidebar's
// รายการดีล item is gated on canViewDealPipeline, not canViewTickets — import
// loses it, and the raw factory-PO nav item is replaced by the combined
// "จัดซื้อ & นำเข้า" item pointing at /procurement.
describe('AppShell navigation (role-scoped views)', () => {
  it('hides รายการดีล for import and shows the combined จัดซื้อ & นำเข้า item instead', async () => {
    renderShell({ role: 'import', employeeId: 2, name: 'นำเข้า ทดสอบ', email: 'import@test.local' });

    await screen.findByText('เนื้อหา');
    expect(screen.queryByText('รายการดีล')).toBeNull();
    expect(screen.getByText('จัดซื้อ & นำเข้า')).toBeTruthy();
  });

  it('keeps รายการดีล for sales', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' });

    await screen.findByText('เนื้อหา');
    expect(screen.getByText('รายการดีล')).toBeTruthy();
  });

  it('keeps รายการดีล for ceo/sales_manager (still on canViewDealPipeline)', async () => {
    renderShell({ role: 'ceo', employeeId: 1, name: 'CEO ทดสอบ', email: 'ceo@test.local' });
    await screen.findByText('เนื้อหา');
    expect(screen.getByText('รายการดีล')).toBeTruthy();
  });
});

// Account role-scoped views: nav must drop รายการดีล (pipeline browser) and
// ค่าคอมมิชชัน (its create-from-deal action folds into งานการเงิน), and gain
// งานการเงิน. sales is the control — unaffected by the split.
describe('AppShell nav — Account role-scoped views', () => {
  it('account nav has no รายการดีล, no ค่าคอมมิชชัน, and has งานการเงิน', async () => {
    renderShell({ role: 'account', name: 'บัญชี ทดสอบ', email: 'account@glr.co.th', employeeId: 3 });
    expect(await screen.findByText('งานการเงิน')).not.toBeNull();
    expect(screen.queryByText('รายการดีล')).toBeNull();
    expect(screen.queryByText('ค่าคอมมิชชัน')).toBeNull();
  });

  it('sales nav still has รายการดีล and ค่าคอมมิชชัน, but no งานการเงิน', async () => {
    renderShell({ role: 'sales', name: 'ขาย ทดสอบ', email: 'sales@glr.co.th', employeeId: 4 });
    expect(await screen.findByText('รายการดีล')).not.toBeNull();
    expect(screen.getByText('ค่าคอมมิชชัน')).not.toBeNull();
    expect(screen.queryByText('งานการเงิน')).toBeNull();
  });

  it('ceo nav keeps รายการดีล and ค่าคอมมิชชัน, and also gains งานการเงิน (canConfirmPayments fallback)', async () => {
    renderShell({ role: 'ceo', name: 'ผู้บริหาร ทดสอบ', email: 'ceo@glr.co.th', employeeId: 5 });
    expect(await screen.findByText('รายการดีล')).not.toBeNull();
    expect(screen.getByText('ค่าคอมมิชชัน')).not.toBeNull();
    expect(screen.getByText('งานการเงิน')).not.toBeNull();
  });
});
