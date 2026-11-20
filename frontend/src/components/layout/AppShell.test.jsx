import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Link, MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppShell } from './AppShell.jsx';
import { Launcher } from './Launcher.jsx';

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

// Content stand-in: a link out of the shell (for "a route change closes the launcher") and a pathname probe.
function PageStub() {
  const { pathname } = useLocation();
  return (
    <div>
      เนื้อหา
      <span data-testid="pathname">{pathname}</span>
      <Link to="/elsewhere">ไปหน้าอื่น</Link>
    </div>
  );
}

function shellTree(ShellComponent, user, initialEntries, pendingRequestCount = 0) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        <Routes>
          <Route path="/" element={<ShellComponent user={user} employee={null} onLogout={vi.fn()} pendingRequestCount={pendingRequestCount} />}>
            <Route index element={<PageStub />} />
            <Route path="*" element={<PageStub />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

const launcherButton = () => screen.getByRole('button', { name: 'เมนู' });
const launcherNav = () => screen.getByRole('navigation', { name: 'Primary navigation' });

function openLauncher() {
  fireEvent.click(launcherButton());
  return launcherNav();
}

// Nav items live in the launcher, which starts closed: every render opens it unless `open: false`.
function renderShell(user, initialEntries = ['/'], { open = true, pendingRequestCount = 0 } = {}) {
  const result = render(shellTree(AppShell, user, initialEntries, pendingRequestCount));
  if (open) openLauncher();
  return result;
}

// Role-scoped views (Import build, docs/role-scoped-views.md): the sidebar's งานทั้งหมด item is gated on
// canViewDealPipeline, not canViewTickets — import loses it and leads with its pricing queue instead.
describe('AppShell navigation (role-scoped views)', () => {
  it('hides งานทั้งหมด for import and leads with คิวขอราคา', async () => {
    renderShell({ role: 'import', employeeId: 2, name: 'นำเข้า ทดสอบ', email: 'import@test.local' });

    await screen.findByText('เนื้อหา');
    expect(screen.queryByText('งานทั้งหมด')).toBeNull();
    expect(screen.getByText('คิวขอราคา')).toBeTruthy();
  });

  // การขนส่ง's actors are canActOnFulfilment {import, ceo}; sales_manager and a read-all holder open it read only (v2.3.0).
  // Wrong-way-round: the refusals are what matter, since a nav item pointing at a page the guard refuses is worse than no item.
  it('shows การขนส่ง (/fulfilment) to import, ceo and the sales manager only', async () => {
    for (const role of ['sales', 'account', 'hr', 'employee']) {
      const { unmount } = renderShell({ role, employeeId: 4, name: 'ทดสอบ', email: `${role}@test.local` });
      await screen.findByText('เนื้อหา');
      expect(screen.queryByText('การขนส่ง'), `${role} must not see การขนส่ง`).toBeNull();
      unmount();
    }

    renderShell({ role: 'ceo', employeeId: 1, name: 'ซีอีโอ ทดสอบ', email: 'ceo@test.local' });
    await screen.findByText('เนื้อหา');
    expect(screen.getByText('การขนส่ง')).toBeTruthy();
  });

  // v2.3.0 read-all: the 7 oversight pages show for a sales manager and a flag holder on ANY role but import/account,
  // never the Express receipts or commissions; the same role without the flag keeps its menu.
  const READER_HREFS = ['/tickets', '/quotations', '/visits', '/catalog', '/pricing-requests', '/fulfilment', '/finance'];
  const OVERSIGHT_HREFS = [...READER_HREFS, '/finance/receipts-import', '/commissions'];
  it('shows the seven oversight pages to a flagged warehouse and qc, not receipts or commissions', async () => {
    for (const role of ['warehouse', 'qc']) {
      const { unmount } = renderShell({ role, employeeId: 4, name: 'ทดสอบ', email: `${role}@test.local`, readAllSales: true });
      await screen.findByText('เนื้อหา');
      const hrefs = hrefsIn(launcherNav());
      expect(hrefs.filter((h) => OVERSIGHT_HREFS.includes(h)).sort(), `${role} flagged`).toEqual([...READER_HREFS].sort());
      unmount();
    }
    const { unmount } = renderShell({ role: 'qc', employeeId: 4, name: 'ทดสอบ', email: 'qc@test.local' });
    await screen.findByText('เนื้อหา');
    expect(hrefsIn(launcherNav()).filter((h) => OVERSIGHT_HREFS.includes(h))).toEqual([]);
    unmount();
    renderShell({ role: 'import', employeeId: 4, name: 'ทดสอบ', email: 'import@test.local', readAllSales: true });
    await screen.findByText('เนื้อหา');
    expect(screen.queryByText('งานทั้งหมด')).toBeNull();
  });

  it('keeps งานทั้งหมด for sales', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' });

    await screen.findByText('เนื้อหา');
    expect(screen.getByText('งานทั้งหมด')).toBeTruthy();
  });

  // นัดเข้าพบ · Visits (design NAV: sales = mywork, board, quotes, VISITS, catalog, comm). Its audience is the visit
  // endpoints' — VisitService.VISIT_ROLES {sales, sales_manager, ceo}. Refusals first.
  it('shows นัดเข้าพบ to no role the visit endpoints refuse', async () => {
    for (const user of [
      { role: 'hr' }, { role: 'employee' }, { role: 'import' }, { role: 'account' }, { role: 'warehouse' },
      { role: 'qc' }, { role: 'employee', manager: true },
    ]) {
      const { unmount } = renderShell({ ...user, employeeId: 4, name: 'ทดสอบ', email: `${user.role}@test.local` });
      await screen.findByText('เนื้อหา');
      expect(screen.queryByRole('link', { name: /^นัดเข้าพบ/ }), `${user.role} must not see นัดเข้าพบ`).toBeNull();
      unmount();
    }
  });

  it('shows นัดเข้าพบ to sales, sales_manager and ceo, directly after เอกสารขาย', async () => {
    for (const role of ['sales', 'sales_manager', 'ceo']) {
      const { unmount } = renderShell({ role, employeeId: 4, name: 'ทดสอบ', email: `${role}@test.local` });
      await screen.findByText('เนื้อหา');
      const visits = screen.getByRole('link', { name: /^นัดเข้าพบ/ });
      expect(visits.getAttribute('href')).toBe('/visits');
      const names = within(visits.closest('nav')).getAllByRole('link').map((link) => link.getAttribute('href'));
      expect(names[names.indexOf('/visits') - 1], `${role}: the item before นัดเข้าพบ`).toBe('/quotations');
      unmount();
    }
  });
});

const HR_USER = { role: 'hr', employeeId: 10, name: 'บุคคล ทดสอบ', email: 'hr@glr.co.th' };

const hrefsIn = (nav) => within(nav).queryAllByRole('link').map((l) => l.getAttribute('href'));

describe('Launcher guard', () => {
  it('throws, naming the path and group, when an item has a group with no section', () => {
    const items = [{ path: '/mystery', label: 'ลึกลับ', helper: 'Mystery', icon: 'clipboard', group: 'nope', show: true }];
    vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => render(<MemoryRouter><Launcher id="x" items={items} onNavigate={() => {}} /></MemoryRouter>))
      .toThrow(/\/mystery.*nope/);
    vi.restoreAllMocks();
  });
});

describe('AppShell launcher — sections and placement', () => {
  it('shows บันทึกการใช้งาน only to an admin', async () => {
    const { unmount } = renderShell(HR_USER);
    expect(hrefsIn(launcherNav())).not.toContain('/activity-log');
    unmount();

    renderShell({ ...HR_USER, admin: true });
    expect(hrefsIn(launcherNav())).toContain('/activity-log');
  });
});

// Account role-scoped views (finalized design, owner-confirmed 2026-07-24): nav drops BOTH งานทั้งหมด (pipeline
// browser) and ค่าคอมมิชชัน (the invoice+commission step is folded into งานการเงิน as the last money-lifecycle
// stage), and gains งานการเงิน. sales is the control — unaffected by the split.
describe('AppShell nav — Account role-scoped views', () => {
  it('account nav has no งานทั้งหมด, no ค่าคอมมิชชัน, and has งานการเงิน', async () => {
    renderShell({ role: 'account', name: 'บัญชี ทดสอบ', email: 'account@glr.co.th', employeeId: 3 });
    expect(await screen.findByText('งานการเงิน')).not.toBeNull();
    expect(screen.queryByText('งานทั้งหมด')).toBeNull();
    expect(screen.queryByText('ค่าคอมมิชชัน')).toBeNull();
  });

  it('sales nav still has งานทั้งหมด and ค่าคอมมิชชัน, but no งานการเงิน', async () => {
    renderShell({ role: 'sales', name: 'ขาย ทดสอบ', email: 'sales@glr.co.th', employeeId: 4 });
    expect(await screen.findByText('งานทั้งหมด')).not.toBeNull();
    expect(screen.getByText('ค่าคอมมิชชัน')).not.toBeNull();
    expect(screen.queryByText('งานการเงิน')).toBeNull();
  });
});

// Division Manager (non-sales) role-scoped views: a role==='employee' user with the manager flag gains the
// "ทีมของฉัน" nav group (isDivisionManager, see permissions.js) — a plain employee (no manager flag) never
// sees it.
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

// Release lockdown (SELF_SERVICE_ONLY — app/features.js): the sidebar keeps only the ungrouped แดชบอร์ด plus
// the 'self' group for every role except HR and CEO.
describe('AppShell navigation under the release lockdown', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  async function renderLockedShell(user, flag = 'true') {
    vi.stubEnv('VITE_SELF_SERVICE_ONLY', flag);
    vi.resetModules();
    const { AppShell: FreshAppShell } = await import('./AppShell.jsx');
    const result = render(shellTree(FreshAppShell, user, ['/']));
    openLauncher();
    return result;
  }

  async function userMenuItem(name) {
    fireEvent.click(screen.getByRole('button', { name: 'เมนูผู้ใช้' }));
    await screen.findByRole('menu', { name: 'เมนูผู้ใช้' });
    return screen.queryByRole('menuitem', { name });
  }

  // Asserted wrong-way-round: the items and the group headers must be GONE.
  // A test that only checked the self-service items are present would pass
  // just as happily with the whole sales menu still sitting beside them.
  // Sales release (owner ruling, Ploy 2026-10-22): sales gets its whole group back; what it never
  // had (การเงิน, งานนำเข้า) must still be absent.
  it('gives a sales rep the sales group back, and nothing from finance or import', async () => {
    await renderLockedShell({ role: 'sales', employeeId: 41, name: 'ขาย ทดสอบ', email: 'sales@test.local' });
    await screen.findByText('เนื้อหา');

    expect(screen.getByText('เอกสารขาย')).toBeTruthy();
    expect(screen.getByText('งานทั้งหมด')).toBeTruthy();
    expect(screen.getByText('นัดเข้าพบ')).toBeTruthy();
    expect(screen.getByText('แคตตาล็อกสินค้า')).toBeTruthy();
    expect(screen.queryByText('งานการเงิน')).toBeNull();
    expect(screen.queryByText('การขนส่ง')).toBeNull();

    expect(screen.getByText('บุคคลของฉัน')).toBeTruthy();
    expect(screen.getByText('แดชบอร์ด')).toBeTruthy();
    expect(screen.getByText('เวลาทำงาน')).toBeTruthy();
    expect(screen.getByText('วันลา')).toBeTruthy();
    expect(screen.getByText('คำขอเงินพิเศษ')).toBeTruthy();
    // ค่าลดหย่อนภาษี left the launcher for the user menu; the lock must not take it away.
    expect(within(launcherNav()).queryByText('ค่าลดหย่อนภาษี')).toBeNull();
    expect(await userMenuItem(/ค่าลดหย่อนภาษี/)).toBeTruthy();
  });

  // Owner ruling 2026-10-22: import sees ONLY งานนำเข้า and คิวขอราคา; account ONLY งานการเงิน.
  // Asserted wrong-way-round: every other sales-group item must be gone.
  it('leaves import การขนส่ง + คิวขอราคา and account งานการเงิน, nothing else from the sales group', async () => {
    await renderLockedShell({ role: 'import', employeeId: 42, name: 'นำเข้า ทดสอบ', email: 'import@test.local' });
    await screen.findByText('เนื้อหา');
    expect(screen.getByText('คิวขอราคา')).toBeTruthy();
    expect(screen.getByText('การขนส่ง')).toBeTruthy();
    expect(screen.queryByText('เอกสารขาย')).toBeNull();
    expect(screen.queryByText('งานทั้งหมด')).toBeNull();
    expect(screen.queryByText('นัดเข้าพบ')).toBeNull();
    expect(screen.queryByText('แคตตาล็อกสินค้า')).toBeNull();
    // import holds canManagePriceImport, so นำเข้าราคา is removed by the lock filter alone — and
    // '/price-import' must not ride on the '/pricing-requests' prefix.
    expect(screen.queryByText('นำเข้าราคา')).toBeNull();
    expect(screen.queryByText('งานการเงิน')).toBeNull();
    expect(screen.getByText('วันลา')).toBeTruthy();
  });

  it('exempts hr — the HR and payroll groups survive', async () => {
    await renderLockedShell({ role: 'hr', employeeId: 45, name: 'บุคคล ทดสอบ', email: 'hr@test.local' });
    await screen.findByText('เนื้อหา');

    expect(screen.getByText('พนักงานทั้งหมด')).toBeTruthy();
    expect(screen.getByText('เงินเดือน')).toBeTruthy();
    expect(screen.getByText('ตรวจสอบค่าลดหย่อนภาษี')).toBeTruthy();
    // The settings section survives for hr (exempt from the lock).
    expect(within(launcherNav()).getByText('ตั้งค่า')).toBeTruthy();
    expect(hrefsIn(launcherNav())).toEqual(expect.arrayContaining(['/settings/attendance-calendar', '/settings/leave-policy']));
  });

  // The lock filter and canAccessPath share isReaderPath: a locked qc/warehouse WITH the flag keeps exactly the seven
  // pages, one WITHOUT it stays confined (no nav link to a refused page, no reachable page without a link).
  it('keeps the seven oversight pages for a flagged qc under the lock, and none without the flag', async () => {
    const { unmount } = await renderLockedShell({ role: 'qc', employeeId: 7, name: 'คิวซี ทดสอบ', email: 'qc@test.local', readAllSales: true });
    await screen.findByText('เนื้อหา');
    const hrefs = hrefsIn(launcherNav());
    expect(hrefs).toEqual(expect.arrayContaining(['/tickets', '/quotations', '/visits', '/catalog', '/pricing-requests', '/fulfilment', '/finance']));
    expect(hrefs).not.toContain('/finance/receipts-import');
    expect(hrefs).not.toContain('/commissions');
    unmount();
    await renderLockedShell({ role: 'warehouse', employeeId: 8, name: 'คลัง ทดสอบ', email: 'wh@test.local' });
    await screen.findByText('เนื้อหา');
    expect(screen.queryByText('งานทั้งหมด')).toBeNull();
    expect(screen.queryByText('งานการเงิน')).toBeNull();
  });

  it('changes nothing when the flag is off', async () => {
    await renderLockedShell({ role: 'sales', employeeId: 41, name: 'ขาย ทดสอบ', email: 'sales@test.local' }, 'false');
    await screen.findByText('เนื้อหา');

    expect(screen.getByText('งานทั้งหมด')).toBeTruthy();
    expect(screen.getByText('งานขาย')).toBeTruthy();
  });
});

// #H4: the canCreateQuotation capability grant (owner ruling 2026-09-09) OR'd into the "เอกสารขาย" nav item's
// visibility -- same OR the '/quotations' PATH_GUARDS entry applies, so the two never disagree about who sees
// the route at all.
describe('AppShell navigation — canCreateQuotation grant (#H4)', () => {
  it('hides เอกสารขาย from a qc user with no grant', async () => {
    renderShell({ role: 'qc', employeeId: 20, name: 'ภิญญดา ทดสอบ' });
    await screen.findByText('เนื้อหา');

    expect(screen.queryByText('เอกสารขาย')).toBeNull();
  });

  it('shows เอกสารขาย to a qc user WITH the grant, though qc is not in canViewDealQuotations', async () => {
    renderShell({ role: 'qc', employeeId: 20, name: 'ภิญญดา ทดสอบ', canCreateQuotation: true });
    await screen.findByText('เนื้อหา');

    expect(screen.getByText('เอกสารขาย')).toBeTruthy();
  });
});

describe('AppShell navigation: วิเคราะห์การลาและเวลาเข้างาน', () => {
  const base = { employeeId: 2, name: 'ทดสอบ', email: 'x@test.local' };

  it('shows the item only when the user carries canViewPeopleInsights', async () => {
    const { unmount } = renderShell({ ...base, role: 'employee', canViewPeopleInsights: true });
    await screen.findByText('เนื้อหา');
    expect(screen.getAllByText('วิเคราะห์การลาและเวลาเข้างาน').length).toBeGreaterThan(0);
    unmount();

    renderShell({ ...base, role: 'hr', canViewPeopleInsights: false });
    await screen.findByText('เนื้อหา');
    expect(screen.queryByText('วิเคราะห์การลาและเวลาเข้างาน')).toBeNull();
  });
});
