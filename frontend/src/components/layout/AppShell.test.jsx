import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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

function renderShell(user, initialEntries = ['/']) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        <Routes>
          <Route path="/" element={<AppShell user={user} employee={null} onLogout={vi.fn()} pendingRequestCount={0} />}>
            <Route index element={<div>เนื้อหา</div>} />
            <Route path="*" element={<div>เนื้อหา</div>} />
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

// The 721–1040px band no longer has a persistent sidebar/rail at all (it was
// superseded by the same off-canvas drawer used at ≤720px — see
// styles.css's ≤1040px drawer block and AppShell's `nav-drawer:` hamburger
// gate, both driven by the same custom variant so they can't drift apart).
// Sidebar itself has no width-conditional JS branch — group headers,
// tooltips, and active-route highlighting are the same regardless of
// viewport, so these assertions hold at every width, not just "tablet".
describe('AppShell navigation (drawer content, no persistent tablet rail)', () => {
  it('keeps nav destinations accessible with native tooltip names', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' });

    const dealsLink = await screen.findByRole('link', { name: 'รายการดีล (Deal pipeline)' });
    expect(dealsLink.getAttribute('title')).toBe('รายการดีล (Deal pipeline)');
    expect(screen.getByRole('link', { name: 'GL&R ERP home' }).getAttribute('title')).toBe('GL&R ERP home');
    expect(screen.getByText('GL&R ERP')).toBeTruthy();
  });

  it('preserves the active route highlight', async () => {
    renderShell(
      { role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' },
      ['/tickets'],
    );

    const dealsLink = await screen.findByRole('link', { name: 'รายการดีล (Deal pipeline)' });
    expect(dealsLink.className).toContain('active');
  });

  // jsdom never evaluates media queries, so nothing else in this suite can
  // catch the hamburger's responsive gate reverting to the old ≤720px-only
  // boundary — this pins the className directly (same technique as
  // Layout.test.jsx's StatGrid 'max-[1040px]:grid-cols-2' assertion).
  // Mutation-checked: reverting the className to `max-[720px]:` (undoing the
  // whole point of this branch) turns this test red and nothing else in the
  // suite.
  it('gates the mobile nav trigger on the nav-drawer (≤1040px) breakpoint, not ≤720px', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' });

    const trigger = await screen.findByRole('button', { name: 'เปิดเมนูนำทาง' });
    expect(trigger.className).toContain('!hidden');
    expect(trigger.className).toContain('nav-drawer:!inline-flex');
    expect(trigger.className).toContain('nav-drawer:flex-[0_0_44px]');
  });

  // A nav group header is a real, clickable control (not hidden behind an
  // icon-only rail that no longer exists) — a toggle collapses/re-expands
  // its links, same behavior at every width including inside the drawer.
  it('lets a nav group toggle collapse and re-expand its links', async () => {
    renderShell({ role: 'ceo', employeeId: 1, name: 'ผู้บริหาร ทดสอบ', email: 'ceo@glr.co.th' });

    const groupToggle = await screen.findByRole('button', { name: /งานขาย/ });
    expect(screen.getByRole('link', { name: 'รายการดีล (Deal pipeline)' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'ตั้งค่าราคา (CEO price config)' })).toBeTruthy();

    fireEvent.click(groupToggle);
    expect(screen.queryByRole('link', { name: 'รายการดีล (Deal pipeline)' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'ตั้งค่าราคา (CEO price config)' })).toBeNull();

    fireEvent.click(groupToggle);
    expect(screen.getByRole('link', { name: 'รายการดีล (Deal pipeline)' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'ตั้งค่าราคา (CEO price config)' })).toBeTruthy();
  });

  it('restores focus to the drawer trigger after mobile navigation closes', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' });

    const trigger = screen.getByRole('button', { name: 'เปิดเมนูนำทาง' });
    trigger.focus();
    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole('button', { name: 'ปิดเมนู' }));

    await waitFor(() => expect(document.activeElement).toBe(trigger));
  });

  it('keeps notification and profile popovers mutually exclusive in the topbar', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' });

    const notificationTrigger = await screen.findByRole('button', { name: /การแจ้งเตือน/ });
    const userTrigger = screen.getByRole('button', { name: 'เมนูผู้ใช้' });

    fireEvent.click(notificationTrigger);
    expect(await screen.findByRole('dialog', { name: 'การแจ้งเตือน' })).toBeTruthy();

    fireEvent.click(userTrigger);
    expect(await screen.findByRole('menu', { name: 'เมนูผู้ใช้' })).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'การแจ้งเตือน' })).toBeNull());

    fireEvent.click(notificationTrigger);
    expect(await screen.findByRole('dialog', { name: 'การแจ้งเตือน' })).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('menu', { name: 'เมนูผู้ใช้' })).toBeNull());
  });

  it('gives the profile menu the same mobile viewport-safe positioning contract as header overlays', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' });

    fireEvent.click(await screen.findByRole('button', { name: 'เมนูผู้ใช้' }));
    const menu = await screen.findByRole('menu', { name: 'เมนูผู้ใช้' });

    expect(menu.className).toContain('max-[720px]:fixed');
    expect(menu.className).toContain('max-[720px]:left-4');
    expect(menu.className).toContain('max-[720px]:right-4');
  });

});

// Account role-scoped views (finalized design, owner-confirmed 2026-07-24): nav
// drops BOTH รายการดีล (pipeline browser) and ค่าคอมมิชชัน (the invoice+commission
// step is folded into งานการเงิน as the last money-lifecycle stage), and gains
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

// Skip link (WCAG 2.2 §2.4.1 Bypass Blocks, issue #392): must be the first
// focusable element in the shell — ahead of the sidebar's nav items — and
// activating it must move real DOM focus onto the <main> landmark, not just
// scroll to it. A bare `href="#main-content"` moving the viewport without
// moving focus is the most common way this pattern silently fails.
describe('AppShell skip link (WCAG 2.2 §2.4.1 Bypass Blocks)', () => {
  it('renders as the first focusable element, before the sidebar nav', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' });

    const skipLink = await screen.findByRole('link', { name: 'ข้ามไปยังเนื้อหาหลัก' });
    const firstNavLink = await screen.findByRole('link', { name: 'รายการดีล (Deal pipeline)' });

    // DOCUMENT_POSITION_FOLLOWING (4) means firstNavLink comes after skipLink.
    expect(skipLink.compareDocumentPosition(firstNavLink) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('is visually hidden by default via sr-only, not display:none/visibility:hidden', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' });

    const skipLink = await screen.findByRole('link', { name: 'ข้ามไปยังเนื้อหาหลัก' });
    expect(skipLink.className).toContain('sr-only');
    expect(skipLink.className).toContain('focus:not-sr-only');
  });

  it('moves real DOM focus onto <main id="main-content"> when activated, not just the viewport', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' });

    const skipLink = await screen.findByRole('link', { name: 'ข้ามไปยังเนื้อหาหลัก' });
    fireEvent.click(skipLink);

    await waitFor(() => {
      expect(document.activeElement?.id).toBe('main-content');
      expect(document.activeElement?.tagName).toBe('MAIN');
    });
  });

  it('targets exactly one <main> landmark', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' });
    await screen.findByText('เนื้อหา');

    expect(document.querySelectorAll('main')).toHaveLength(1);
    expect(document.getElementById('main-content')?.tagName).toBe('MAIN');
  });
});

// Division Manager (non-sales) role-scoped views: a role==='employee' user with
// the manager flag gains the "ทีมของฉัน" nav group (isDivisionManager, see
// permissions.js) — a plain employee (no manager flag) never sees it.
describe('AppShell nav — Division Manager (non-sales) role-scoped views', () => {
  it('shows the ทีมของฉัน group for a division manager (role employee + manager flag)', async () => {
    renderShell({ role: 'employee', manager: true, name: 'ผู้จัดการ ทดสอบ', email: 'manager@glr.co.th', employeeId: 30 });
    expect(await screen.findByText('การอนุมัติ OT')).not.toBeNull();
    expect(screen.getByText('การอนุมัติวันลา')).not.toBeNull();
    expect(screen.getByText('ทีมในฝ่าย')).not.toBeNull();
  });

  it('hides the ทีมของฉัน group for a plain employee (no manager flag)', async () => {
    renderShell({ role: 'employee', manager: false, name: 'พนักงาน ทดสอบ', email: 'employee@glr.co.th', employeeId: 31 });
    await screen.findByText('เนื้อหา');
    expect(screen.queryByText('การอนุมัติ OT')).toBeNull();
    expect(screen.queryByText('การอนุมัติวันลา')).toBeNull();
    expect(screen.queryByText('ทีมในฝ่าย')).toBeNull();
  });
});
