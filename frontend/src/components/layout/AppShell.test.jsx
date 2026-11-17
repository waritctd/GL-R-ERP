import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Link, MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

  it('shows import no procurement / factory-purchase-order nav item', async () => {
    renderShell({ role: 'import', employeeId: 2, name: 'นำเข้า ทดสอบ', email: 'import@test.local' });

    await screen.findByText('เนื้อหา');
    expect(screen.queryByText('จัดซื้อ & นำเข้า')).toBeNull();
    expect(screen.queryByText('ใบสั่งซื้อโรงงาน')).toBeNull();
    // งานนำเข้า is NOT that page coming back. The deleted one was a link list duplicating the dashboard worklist;
    // this one performs the four transitions. The retired LABEL must stay retired all the same.
    // Spec 2026-11-15 §7.11 (design a-fulfil): /fulfilment is labelled การขนส่ง; งานนำเข้า is import's landing title.
    expect(screen.getByText('การขนส่ง')).toBeTruthy();
  });

  // งานนำเข้า's audience is canActOnFulfilment {import, ceo} — deliberately NARROWER than its คิวขอราคา
  // neighbour, which also admits sales_manager. Wrong-way-round: the refusals are what matter, since a nav item
  // pointing at a page whose every button 403s is worse than no nav item.
  it('shows การขนส่ง (/fulfilment) to import and ceo only', async () => {
    for (const role of ['sales', 'sales_manager', 'account', 'hr', 'employee']) {
      const { unmount } = renderShell({ role, employeeId: 4, name: 'ทดสอบ', email: `${role}@test.local` });
      await screen.findByText('เนื้อหา');
      expect(screen.queryByText('การขนส่ง'), `${role} must not see การขนส่ง`).toBeNull();
      unmount();
    }

    renderShell({ role: 'ceo', employeeId: 1, name: 'ซีอีโอ ทดสอบ', email: 'ceo@test.local' });
    await screen.findByText('เนื้อหา');
    expect(screen.getByText('การขนส่ง')).toBeTruthy();
  });

  it('keeps งานทั้งหมด for sales', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' });

    await screen.findByText('เนื้อหา');
    expect(screen.getByText('งานทั้งหมด')).toBeTruthy();
  });

  it('keeps งานทั้งหมด for ceo/sales_manager (still on canViewDealPipeline)', async () => {
    renderShell({ role: 'ceo', employeeId: 1, name: 'CEO ทดสอบ', email: 'ceo@test.local' });
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

  it('shows นัดเข้าพบ to sales, sales_manager and ceo, directly after ใบเสนอราคา', async () => {
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

// The navigation is a launcher at every width: no persistent sidebar, no off-canvas drawer. A "เมนู" button in the
// topbar toggles a panel of every nav item in labelled sections.
const SALES_USER = { role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' };
const CEO_USER = { role: 'ceo', employeeId: 1, admin: true, name: 'ผู้บริหาร ทดสอบ', email: 'ceo@glr.co.th' };
const HR_USER = { role: 'hr', employeeId: 10, name: 'บุคคล ทดสอบ', email: 'hr@glr.co.th' };
const EMPLOYEE_USER = { role: 'employee', employeeId: 31, name: 'พนักงาน ทดสอบ', email: 'employee@glr.co.th' };
const MANAGER_USER = { role: 'employee', manager: true, employeeId: 30, name: 'ผู้จัดการ ทดสอบ', email: 'manager@glr.co.th' };

const SECTION_ORDER = ['หน้าหลัก', 'งานขาย', 'บุคคล', 'การเงิน', 'ทีมของฉัน', 'บุคคลของฉัน', 'ตั้งค่า'];

// Section labels present in the launcher, in document order.
function sectionLabels(nav) {
  return SECTION_ORDER
    .map((label) => ({ label, el: within(nav).queryByText(label) }))
    .filter(({ el }) => el)
    .sort((x, y) => (x.el.compareDocumentPosition(y.el) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1));
}

// href -> label of the section whose header most recently precedes that link in the launcher.
function sectionOfHref(nav, href) {
  const link = within(nav).getAllByRole('link').find((l) => l.getAttribute('href') === href);
  expect(link, `launcher link ${href}`).toBeTruthy();
  const preceding = sectionLabels(nav).filter(({ el }) => el.compareDocumentPosition(link) & Node.DOCUMENT_POSITION_FOLLOWING);
  return preceding.at(-1)?.label;
}

const hrefsIn = (nav) => within(nav).queryAllByRole('link').map((l) => l.getAttribute('href'));

describe('AppShell launcher — closed state and toggle', () => {
  it('has no persistent sidebar: no navigation landmark until the เมนู button is used', async () => {
    renderShell(SALES_USER, ['/'], { open: false });
    await screen.findByText('เนื้อหา');

    expect(screen.queryByRole('navigation')).toBeNull();
    expect(screen.queryByRole('link', { name: /งานทั้งหมด/ })).toBeNull();
  });

  // The button carries the brand mark + "เมนู" + a chevron; there is no separate brand/home link in the topbar.
  it('shows the เมนู button at every width, with the brand mark, and no separate home link in the topbar', async () => {
    renderShell(SALES_USER, ['/tickets'], { open: false });
    await screen.findByText('เนื้อหา');

    const button = launcherButton();
    expect(button.className).not.toContain('!hidden');
    expect(button.className).not.toContain('nav-drawer');
    const banner = screen.getByRole('banner');
    expect(within(banner).getByRole('button', { name: 'เมนู' })).toBe(button);
    expect(button.textContent).toContain('เมนู');
    expect(button.querySelector('[data-testid="brand-mark"]')).not.toBeNull();
    expect(within(banner).queryAllByRole('link')).toHaveLength(0);
    expect(banner.querySelector('a[href="/"]')).toBeNull();

    openLauncher();
    expect(within(launcherNav()).getByRole('link', { name: /แดชบอร์ด/ }).getAttribute('href')).toBe('/');
  });

  it('toggles the panel with aria-expanded / aria-controls pointing at the Primary navigation nav', async () => {
    renderShell(SALES_USER, ['/'], { open: false });
    await screen.findByText('เนื้อหา');

    const button = launcherButton();
    expect(button.getAttribute('aria-expanded')).toBe('false');

    const nav = openLauncher();
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(nav.tagName).toBe('NAV');
    expect(nav.id).toBeTruthy();
    expect(button.getAttribute('aria-controls')).toBe(nav.id);
    expect(within(nav).getByRole('link', { name: /งานทั้งหมด/ })).toBeTruthy();

    fireEvent.click(button);
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByRole('navigation', { name: 'Primary navigation' })).toBeNull();
  });

  it('closes when a link is chosen, even the current page\'s own link (no route change)', async () => {
    renderShell(SALES_USER, ['/']);
    const nav = launcherNav();

    fireEvent.click(within(nav).getByRole('link', { name: /แดชบอร์ด/ }));

    await waitFor(() => expect(screen.queryByRole('navigation', { name: 'Primary navigation' })).toBeNull());
    expect(launcherButton().getAttribute('aria-expanded')).toBe('false');
  });

  it('navigates and closes when another link is chosen', async () => {
    renderShell(SALES_USER, ['/']);

    fireEvent.click(within(launcherNav()).getByRole('link', { name: /งานทั้งหมด/ }));

    await waitFor(() => expect(screen.getByTestId('pathname').textContent).toBe('/tickets'));
    expect(screen.queryByRole('navigation', { name: 'Primary navigation' })).toBeNull();
  });

  it('closes on a route change that did not come from the launcher', async () => {
    renderShell(SALES_USER, ['/']);
    expect(launcherNav()).toBeTruthy();

    fireEvent.click(screen.getByRole('link', { name: 'ไปหน้าอื่น' }));

    await waitFor(() => expect(screen.getByTestId('pathname').textContent).toBe('/elsewhere'));
    expect(screen.queryByRole('navigation', { name: 'Primary navigation' })).toBeNull();
  });

  it('closes on Escape and returns focus to the เมนู button', async () => {
    renderShell(SALES_USER, ['/'], { open: false });
    const button = launcherButton();
    button.focus();
    fireEvent.click(button);
    launcherNav();

    fireEvent.keyDown(document, { key: 'Escape' });

    await waitFor(() => expect(screen.queryByRole('navigation', { name: 'Primary navigation' })).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(button));
  });
});

describe('AppShell launcher — pointer handling', () => {
  const launcherClosed = () => screen.queryByRole('navigation', { name: 'Primary navigation' }) === null;

  it('has no full-page backdrop button (it would swallow clicks on the other topbar controls)', async () => {
    renderShell(SALES_USER);
    expect(screen.queryByRole('button', { name: 'ปิดเมนู' })).toBeNull();
  });

  it('closes on a mousedown outside the panel and the button', async () => {
    renderShell(SALES_USER);
    fireEvent.mouseDown(screen.getByTestId('pathname'));
    await waitFor(() => expect(launcherClosed()).toBe(true));
  });

  it('stays open on a mousedown inside the panel', async () => {
    renderShell(SALES_USER);
    fireEvent.mouseDown(within(launcherNav()).getByRole('link', { name: /งานทั้งหมด/ }));
    expect(launcherClosed()).toBe(false);
  });

  it('closes, and does not reopen, when the เมนู button is pressed while open', async () => {
    renderShell(SALES_USER);
    const button = screen.getByRole('button', { name: 'เมนู' });
    fireEvent.mouseDown(button);
    expect(launcherClosed()).toBe(false);
    fireEvent.click(button);
    expect(launcherClosed()).toBe(true);
  });

  it('opens the notification bell in one click while closing the launcher', async () => {
    renderShell(SALES_USER);
    const bell = await screen.findByRole('button', { name: /การแจ้งเตือน/ });

    fireEvent.mouseDown(bell);
    fireEvent.click(bell);

    expect(await screen.findByRole('dialog', { name: 'การแจ้งเตือน' })).toBeTruthy();
    expect(launcherClosed()).toBe(true);
  });

  it('closes the launcher when a topbar popover opens', async () => {
    renderShell(SALES_USER);
    fireEvent.click(screen.getByRole('button', { name: 'เมนูผู้ใช้' }));
    await screen.findByRole('menu', { name: 'เมนูผู้ใช้' });
    expect(launcherClosed()).toBe(true);
  });
});

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
  it('lists the sections in order, and renders none that is empty', async () => {
    renderShell(CEO_USER);
    expect(sectionLabels(launcherNav()).map(({ label }) => label)).toEqual(
      ['หน้าหลัก', 'งานขาย', 'บุคคล', 'การเงิน', 'บุคคลของฉัน', 'ตั้งค่า'],
    );
  });

  it('shows ทีมของฉัน between การเงิน and บุคคลของฉัน for a division manager', async () => {
    renderShell(MANAGER_USER);
    const nav = launcherNav();
    expect(sectionLabels(nav).map(({ label }) => label)).toEqual(['หน้าหลัก', 'ทีมของฉัน', 'บุคคลของฉัน']);
    expect(sectionOfHref(nav, '/attendance')).toBe('ทีมของฉัน');
  });

  it('gives a plain employee only หน้าหลัก and บุคคลของฉัน — no งานขาย / บุคคล / การเงิน / ทีมของฉัน / ตั้งค่า', async () => {
    renderShell(EMPLOYEE_USER);
    const nav = launcherNav();
    expect(sectionLabels(nav).map(({ label }) => label)).toEqual(['หน้าหลัก', 'บุคคลของฉัน']);
    for (const absent of ['งานขาย', 'บุคคล', 'การเงิน', 'ทีมของฉัน', 'ตั้งค่า']) {
      expect(within(nav).queryByText(absent), absent).toBeNull();
    }
  });

  it('files every item under its section (ceo, admin)', async () => {
    renderShell(CEO_USER);
    const nav = launcherNav();
    const expected = {
      '/': 'หน้าหลัก',
      '/tickets': 'งานขาย',
      '/finance': 'การเงิน',
      '/payroll': 'บุคคล', // moved from การเงิน
      '/leave': 'บุคคลของฉัน',
      '/ceo-settings': 'ตั้งค่า', // moved from งานขาย
      '/price-import': 'ตั้งค่า', // moved from งานขาย
      '/settings/attendance-calendar': 'ตั้งค่า', // moved from บุคคล
      '/settings/leave-policy': 'ตั้งค่า', // moved from บุคคล
      '/activity-log': 'ตั้งค่า', // moved from หน้าหลัก
    };
    for (const [href, section] of Object.entries(expected)) {
      expect(sectionOfHref(nav, href), href).toBe(section);
    }
  });

  it('files hr\'s items under บุคคล and keeps the settings pages under ตั้งค่า', async () => {
    renderShell(HR_USER);
    const nav = launcherNav();
    for (const href of ['/hr', '/employees', '/payroll', '/tax-allowance-review']) {
      expect(sectionOfHref(nav, href), href).toBe('บุคคล');
    }
    expect(sectionOfHref(nav, '/settings/attendance-calendar')).toBe('ตั้งค่า');
    expect(sectionOfHref(nav, '/settings/leave-policy')).toBe('ตั้งค่า');
  });

  it('keeps the unread count in the คำขอแก้ไขข้อมูล link\'s accessible name, visually hidden from AT', async () => {
    renderShell(HR_USER, ['/'], { pendingRequestCount: 4 });
    const link = within(launcherNav()).getByRole('link', { name: /คำขอแก้ไขข้อมูล/ });

    expect(link.getAttribute('href')).toBe('/requests');
    expect(link.getAttribute('aria-label')).toContain('4 รายการใหม่');
    expect(within(link).getByText('4').getAttribute('aria-hidden')).toBe('true');
  });

  // Cannot-reach cases: these items left the launcher; their routes/guards are untouched (permissions.test.js).
  it('no longer lists the deduction pages, มีอะไรใหม่ or ค่าลดหย่อนภาษี (self) as launcher items', async () => {
    for (const user of [HR_USER, CEO_USER, EMPLOYEE_USER]) {
      const { unmount } = renderShell(user);
      const hrefs = hrefsIn(launcherNav());
      for (const gone of ['/payroll/deduction-shortfalls', '/payroll/deduction-consents', '/whats-new', '/tax-allowance']) {
        expect(hrefs, `${user.role} must not see ${gone}`).not.toContain(gone);
      }
      unmount();
    }
  });

  it('keeps ตรวจสอบค่าลดหย่อนภาษี (the HR register) — only the self-service item moved', async () => {
    renderShell(HR_USER);
    expect(hrefsIn(launcherNav())).toContain('/tax-allowance-review');
  });

  it('keeps every visibility rule: settings items stay hidden from roles that never saw them', async () => {
    renderShell(SALES_USER);
    const hrefs = hrefsIn(launcherNav());
    for (const href of ['/ceo-settings', '/price-import', '/settings/attendance-calendar', '/settings/leave-policy', '/activity-log', '/payroll']) {
      expect(hrefs, href).not.toContain(href);
    }
    expect(screen.queryByText('ตั้งค่า')).toBeNull();
  });

  it('shows บันทึกการใช้งาน only to an admin', async () => {
    renderShell(HR_USER);
    expect(hrefsIn(launcherNav())).not.toContain('/activity-log');
  });
});

describe('AppShell launcher — current page', () => {
  const currentHrefs = (nav) => within(nav).getAllByRole('link')
    .filter((l) => l.getAttribute('aria-current') === 'page')
    .map((l) => l.getAttribute('href'));

  it('marks the current page\'s link aria-current="page" and no other', async () => {
    renderShell(SALES_USER, ['/tickets']);
    expect(currentHrefs(launcherNav())).toEqual(['/tickets']);
  });

  it('marks the dashboard only on "/" (never as a prefix of every path)', async () => {
    renderShell(SALES_USER, ['/']);
    expect(currentHrefs(launcherNav())).toEqual(['/']);
  });

  it('keeps a parent lit on a detail page (prefix rule)', async () => {
    renderShell(HR_USER, ['/employees/123']);
    expect(currentHrefs(launcherNav())).toEqual(['/employees']);
  });

  it('honours `match` aliases: /overtime lights คำขอเงินพิเศษ', async () => {
    renderShell(EMPLOYEE_USER, ['/overtime']);
    expect(currentHrefs(launcherNav())).toEqual(['/employee-requests']);
  });

  // With the sibling deduction nav items gone, /payroll needs no `exact`: its sub-pages belong under it.
  it('lights เงินเดือน (and only it) on the payroll sub-pages that are no longer nav items', async () => {
    renderShell(HR_USER, ['/payroll/deduction-shortfalls']);
    expect(currentHrefs(launcherNav())).toEqual(['/payroll']);
  });
});

describe('AppShell topbar — มีอะไรใหม่ and ค่าลดหย่อนภาษี', () => {
  it('keeps มีอะไรใหม่ as a topbar control, not a launcher link', async () => {
    renderShell(EMPLOYEE_USER);
    expect(within(screen.getByRole('banner')).getByRole('button', { name: /มีอะไรใหม่/ })).toBeTruthy();
    expect(hrefsIn(launcherNav())).not.toContain('/whats-new');
  });

  it('puts ค่าลดหย่อนภาษี in the user menu for anyone with an employee record, and it navigates', async () => {
    renderShell(EMPLOYEE_USER, ['/'], { open: false });
    fireEvent.click(screen.getByRole('button', { name: 'เมนูผู้ใช้' }));

    fireEvent.click(await screen.findByRole('menuitem', { name: /ค่าลดหย่อนภาษี/ }));

    await waitFor(() => expect(screen.getByTestId('pathname').textContent).toBe('/tax-allowance'));
  });

  it.each([
    ['with an employee record', EMPLOYEE_USER],
    ['without one', { role: 'hr', name: 'ไม่มีพนักงาน', email: 'x@test.local' }],
  ])('offers มีอะไรใหม่ in the user menu %s, so /whats-new stays reachable', async (_label, user) => {
    renderShell(user, ['/'], { open: false });
    fireEvent.click(screen.getByRole('button', { name: 'เมนูผู้ใช้' }));

    fireEvent.click(await screen.findByRole('menuitem', { name: /มีอะไรใหม่/ }));

    await waitFor(() => expect(screen.getByTestId('pathname').textContent).toBe('/whats-new'));
  });

  it('does not offer ค่าลดหย่อนภาษี to a user with no employee record', async () => {
    renderShell({ role: 'hr', name: 'ไม่มีพนักงาน', email: 'x@test.local' }, ['/'], { open: false });
    fireEvent.click(screen.getByRole('button', { name: 'เมนูผู้ใช้' }));

    await screen.findByRole('menu', { name: 'เมนูผู้ใช้' });
    expect(screen.queryByRole('menuitem', { name: /ค่าลดหย่อนภาษี/ })).toBeNull();
  });
});

describe('AppShell navigation (topbar popovers)', () => {
  it('keeps notification and profile popovers mutually exclusive in the topbar', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' }, ['/'], { open: false });

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
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' }, ['/'], { open: false });

    fireEvent.click(await screen.findByRole('button', { name: 'เมนูผู้ใช้' }));
    const menu = await screen.findByRole('menu', { name: 'เมนูผู้ใช้' });

    expect(menu.className).toContain('mobile:fixed');
    expect(menu.className).toContain('mobile:left-4');
    expect(menu.className).toContain('mobile:right-4');
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

  it('ceo nav keeps งานทั้งหมด and ค่าคอมมิชชัน, and also gains งานการเงิน (canConfirmPayments fallback)', async () => {
    renderShell({ role: 'ceo', name: 'ผู้บริหาร ทดสอบ', email: 'ceo@glr.co.th', employeeId: 5 });
    expect(await screen.findByText('งานทั้งหมด')).not.toBeNull();
    expect(screen.getByText('ค่าคอมมิชชัน')).not.toBeNull();
    expect(screen.getByText('งานการเงิน')).not.toBeNull();
  });
});

// Skip link (WCAG 2.2 §2.4.1 Bypass Blocks, issue #392): must be the first focusable element in the shell —
// ahead of the sidebar's nav items — and activating it must move real DOM focus onto the <main> landmark, not
// just scroll to it.
describe('AppShell skip link (WCAG 2.2 §2.4.1 Bypass Blocks)', () => {
  it('renders as the first focusable element, before the เมนู button and the launcher links', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' });

    const skipLink = await screen.findByRole('link', { name: 'ข้ามไปยังเนื้อหาหลัก' });
    const firstNavLink = within(launcherNav()).getByRole('link', { name: /งานทั้งหมด/ });

    // DOCUMENT_POSITION_FOLLOWING (4) means the later node comes after skipLink.
    expect(skipLink.compareDocumentPosition(launcherButton()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(skipLink.compareDocumentPosition(firstNavLink) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('is visually hidden by default via sr-only, not display:none/visibility:hidden', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' }, ['/'], { open: false });

    const skipLink = await screen.findByRole('link', { name: 'ข้ามไปยังเนื้อหาหลัก' });
    expect(skipLink.className).toContain('sr-only');
    expect(skipLink.className).toContain('focus:not-sr-only');
  });

  it('moves real DOM focus onto <main id="main-content"> when activated, not just the viewport', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' }, ['/'], { open: false });

    const skipLink = await screen.findByRole('link', { name: 'ข้ามไปยังเนื้อหาหลัก' });
    fireEvent.click(skipLink);

    await waitFor(() => {
      expect(document.activeElement?.id).toBe('main-content');
      expect(document.activeElement?.tagName).toBe('MAIN');
    });
  });

  it('targets exactly one <main> landmark', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' }, ['/'], { open: false });
    await screen.findByText('เนื้อหา');

    expect(document.querySelectorAll('main')).toHaveLength(1);
    expect(document.getElementById('main-content')?.tagName).toBe('MAIN');
  });
});

// Landmark structure: the topbar <header> must be a SIBLING of <main>, never a descendant.
describe('AppShell landmark structure', () => {
  it('keeps the topbar <header> outside — and ahead of — the <main> landmark', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' }, ['/'], { open: false });
    await screen.findByText('เนื้อหา');

    const main = document.querySelector('main');
    const header = document.querySelector('header');
    expect(header).not.toBeNull();
    expect(main).not.toBeNull();
    expect(main.contains(header)).toBe(false);

    // A <header> is only exposed as the `banner` landmark while its landmark scope is <body>; the plain <div>
    // column wrapper it now sits in keeps that scope, where the old <main> parent did not.
    expect(screen.getByRole('banner')).toBe(header);

    // DOCUMENT_POSITION_FOLLOWING (4): main comes after the header, so the reading order is still banner → main.
    expect(header.compareDocumentPosition(main) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('excludes every topbar control from a getByRole("main")-scoped query', async () => {
    renderShell({ role: 'sales', employeeId: 9, name: 'ขาย ทดสอบ', email: 'sales@test.local' }, ['/'], { open: false });
    await screen.findByText('เนื้อหา');

    const main = screen.getByRole('main');

    // Wrong-way-round on purpose: asserting the chrome is ABSENT from main is the assertion that can actually
    // fail. "Page content is present" passed happily throughout the whole period the nesting was broken.
    expect(within(main).queryByRole('button', { name: 'เมนู' })).toBeNull();
    expect(within(main).queryByRole('button', { name: 'เมนูผู้ใช้' })).toBeNull();
    expect(within(main).queryByRole('button', { name: /การแจ้งเตือน/ })).toBeNull();
    expect(within(main).queryAllByRole('button')).toHaveLength(0);

    // ...and the same controls really are reachable from the document, so the assertions above cannot pass by the
    // buttons simply not having rendered.
    expect(launcherButton()).not.toBeNull();
    expect(screen.getByRole('button', { name: 'เมนูผู้ใช้' })).not.toBeNull();
    expect(screen.getByRole('button', { name: /การแจ้งเตือน/ })).not.toBeNull();
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

// The scroll container must be its own subtree's containing block, or a `position: absolute` descendant with
// no other positioned ancestor resolves to the *initial* containing block and leaks its static offset into
// `<html>`'s scrollable overflow — a second, outer scrollbar over a viewport-height shell.
describe('AppShell — <main> is the containing block for its own scrolled subtree', () => {
  it('renders <main> with both `relative` and `overflow-auto`, so absolutely-positioned page content cannot escape into document scroll', async () => {
    renderShell({ role: 'employee', name: 'พนักงาน ทดสอบ', email: 'employee@glr.co.th', employeeId: 31 }, ['/'], { open: false });
    await screen.findByText('เนื้อหา');

    const main = screen.getByRole('main');
    const classes = main.className.split(/\s+/);

    // Both halves matter and they are different ancestor walks: `overflow-auto` makes this the scroll container,
    // `relative` makes it the containing block. `overflow-auto` alone is the bug.
    expect(classes).toContain('relative');
    expect(classes).toContain('overflow-auto');

    // No competing positioning utility survived alongside it — `absolute`, `fixed` or `static` on this element
    // would either re-open the leak or break the shell's flex layout outright.
    expect(classes).not.toContain('static');
    expect(classes).not.toContain('absolute');
    expect(classes).not.toContain('fixed');
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

    expect(screen.getByText('ใบเสนอราคา')).toBeTruthy();
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
    expect(screen.queryByText('ใบเสนอราคา')).toBeNull();
    expect(screen.queryByText('งานทั้งหมด')).toBeNull();
    expect(screen.queryByText('นัดเข้าพบ')).toBeNull();
    expect(screen.queryByText('แคตตาล็อกสินค้า')).toBeNull();
    // import holds canManagePriceImport, so นำเข้าราคา is removed by the lock filter alone — and
    // '/price-import' must not ride on the '/pricing-requests' prefix.
    expect(screen.queryByText('นำเข้าราคา')).toBeNull();
    expect(screen.queryByText('งานการเงิน')).toBeNull();
    expect(screen.getByText('วันลา')).toBeTruthy();
  });

  it('leaves account งานการเงิน and nothing else from the sales group', async () => {
    await renderLockedShell({ role: 'account', employeeId: 43, name: 'บัญชี ทดสอบ', email: 'account@test.local' });
    await screen.findByText('เนื้อหา');
    expect(screen.getByText('งานการเงิน')).toBeTruthy();
    expect(screen.queryByText('คิวขอราคา')).toBeNull();
    expect(screen.queryByText('การขนส่ง')).toBeNull();
    expect(screen.queryByText('งานทั้งหมด')).toBeNull();
    expect(screen.queryByText('ใบเสนอราคา')).toBeNull();
    // account holds canViewCatalog; only the lock filter removes it.
    expect(screen.queryByText('แคตตาล็อกสินค้า')).toBeNull();
    expect(screen.queryByText('ค่าคอมมิชชัน')).toBeNull();
  });

  it('gives a can-create grantee ใบเสนอราคา even when their role is outside sales', async () => {
    await renderLockedShell({ role: 'qc', employeeId: 144, canCreateQuotation: true, name: 'คิวซี ทดสอบ', email: 'qc@test.local' });
    await screen.findByText('เนื้อหา');
    expect(screen.getByText('ใบเสนอราคา')).toBeTruthy();
    expect(screen.queryByText('งานทั้งหมด')).toBeNull();
    expect(screen.queryByText('แคตตาล็อกสินค้า')).toBeNull();
  });

  // A division manager loses the 'ทีมของฉัน' group but keeps the routes it pointed at: its three entries are
  // /employee-requests, /leave and /attendance, all of which the surviving 'self' group also lists. Approval
  // work therefore still has a way in.
  it('drops a division manager’s team group without stranding their approvals', async () => {
    await renderLockedShell({ role: 'employee', employeeId: 44, manager: true, name: 'หัวหน้า ทดสอบ', email: 'mgr@test.local' });
    await screen.findByText('เนื้อหา');

    expect(screen.queryByText('ทีมของฉัน')).toBeNull();
    expect(screen.queryByText('การอนุมัติวันลา')).toBeNull();
    expect(screen.getByText('วันลา')).toBeTruthy();
    expect(screen.getByText('คำขอเงินพิเศษ')).toBeTruthy();
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

  it('exempts ceo — payroll and the CEO price config survive', async () => {
    await renderLockedShell({ role: 'ceo', employeeId: 46, name: 'ผู้บริหาร ทดสอบ', email: 'ceo@test.local' });
    await screen.findByText('เนื้อหา');

    expect(screen.getByText('เงินเดือน')).toBeTruthy();
    expect(screen.getByText('ตั้งค่าราคา')).toBeTruthy();
  });

  // มีอะไรใหม่ (header control) and ค่าลดหย่อนภาษี (user menu) were ungrouped/'self' launcher links that survived the
  // lock by being ungrouped/self. Both moved out of the launcher; every locked role must still reach them.
  it('keeps มีอะไรใหม่ (topbar) and ค่าลดหย่อนภาษี (user menu) for every locked role, and neither is a launcher link', async () => {
    for (const user of [
      { role: 'employee', employeeId: 51 },
      { role: 'warehouse', employeeId: 52 },
      { role: 'account', employeeId: 53 },
      { role: 'import', employeeId: 54 },
      { role: 'employee', employeeId: 55, manager: true },
    ]) {
      const { unmount } = await renderLockedShell({ ...user, name: 'ทดสอบ', email: `${user.role}@test.local` });
      await screen.findByText('เนื้อหา');
      const hrefs = hrefsIn(launcherNav());
      expect(hrefs, user.role).not.toContain('/whats-new');
      expect(hrefs, user.role).not.toContain('/tax-allowance');
      expect(within(screen.getByRole('banner')).getByRole('button', { name: /มีอะไรใหม่/ }), user.role).toBeTruthy();
      expect(await userMenuItem(/ค่าลดหย่อนภาษี/), user.role).toBeTruthy();
      unmount();
    }
  });

  // /activity-log was ungrouped, which is what carried it through the lock filter. It now sits in ตั้งค่า, so the
  // filter must admit it explicitly for an admin (canAccessPath's adminActivityLog twin) and nobody else.
  it('still shows บันทึกการใช้งาน (under ตั้งค่า) to a locked admin, and not to a locked non-admin', async () => {
    const admin = await renderLockedShell({ role: 'employee', employeeId: 56, admin: true, name: 'แอดมิน', email: 'admin@test.local' });
    await screen.findByText('เนื้อหา');
    expect(sectionOfHref(launcherNav(), '/activity-log')).toBe('ตั้งค่า');
    // ...and the lock still removes every other settings item for that role.
    expect(hrefsIn(launcherNav())).not.toContain('/ceo-settings');
    admin.unmount();

    await renderLockedShell({ role: 'employee', employeeId: 57, name: 'พนักงาน', email: 'e@test.local' });
    await screen.findByText('เนื้อหา');
    expect(hrefsIn(launcherNav())).not.toContain('/activity-log');
    expect(within(launcherNav()).queryByText('ตั้งค่า')).toBeNull();
  });

  it('keeps the settings pages out of a locked sales rep\'s launcher', async () => {
    await renderLockedShell({ role: 'sales', employeeId: 41, name: 'ขาย ทดสอบ', email: 'sales@test.local' });
    await screen.findByText('เนื้อหา');
    expect(within(launcherNav()).queryByText('ตั้งค่า')).toBeNull();
  });

  it('changes nothing when the flag is off', async () => {
    await renderLockedShell({ role: 'sales', employeeId: 41, name: 'ขาย ทดสอบ', email: 'sales@test.local' }, 'false');
    await screen.findByText('เนื้อหา');

    expect(screen.getByText('งานทั้งหมด')).toBeTruthy();
    expect(screen.getByText('งานขาย')).toBeTruthy();
  });
});

// #H4: the canCreateQuotation capability grant (owner ruling 2026-09-09) OR'd into the "ใบเสนอราคา" nav item's
// visibility -- same OR the '/quotations' PATH_GUARDS entry applies, so the two never disagree about who sees
// the route at all.
describe('AppShell navigation — canCreateQuotation grant (#H4)', () => {
  it('hides ใบเสนอราคา from a qc user with no grant', async () => {
    renderShell({ role: 'qc', employeeId: 20, name: 'ภิญญดา ทดสอบ' });
    await screen.findByText('เนื้อหา');

    expect(screen.queryByText('ใบเสนอราคา')).toBeNull();
  });

  it('shows ใบเสนอราคา to a qc user WITH the grant, though qc is not in canViewDealQuotations', async () => {
    renderShell({ role: 'qc', employeeId: 20, name: 'ภิญญดา ทดสอบ', canCreateQuotation: true });
    await screen.findByText('เนื้อหา');

    expect(screen.getByText('ใบเสนอราคา')).toBeTruthy();
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
