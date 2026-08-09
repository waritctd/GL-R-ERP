import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LeaveSurfacePage } from './LeaveSurfacePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    leave: {
      employees: vi.fn(),
      types: vi.fn(),
      list: vi.fn(),
      balances: vi.fn(),
      contactDefaults: vi.fn(),
      create: vi.fn(),
      approve: vi.fn(),
      reject: vi.fn(),
      cancel: vi.fn(),
      reviewSummary: vi.fn(),
      // Phase A3: RulesTab (rendered by the "กฎการลา" tab, mounted below) probes this on load.
      policyDocumentAvailable: vi.fn(),
    },
  },
}));

const plainEmployee = { employeeId: 1, name: 'พนักงาน ทดสอบ', role: 'employee', manager: false };
const currentEmployee = { id: 1, nameTh: 'พนักงาน ทดสอบ' };

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}{location.search}</div>;
}

function renderLeaveSurfacePage(user = plainEmployee, initialEntries = ['/leave']) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        <LocationProbe />
        <LeaveSurfacePage user={user} currentEmployee={currentEmployee} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...utils, queryClient };
}

describe('LeaveSurfacePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.leave.employees.mockResolvedValue({
      employees: [{
        employeeId: 1, employeeName: 'พนักงาน ทดสอบ', employeeCode: 'GLR-001', self: true, directReport: false,
      }],
    });
    api.leave.types.mockResolvedValue({ leaveTypes: [{ code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation' }] });
    api.leave.list.mockResolvedValue({ requests: [] });
    api.leave.balances.mockResolvedValue({ balances: [] });
    api.leave.contactDefaults.mockResolvedValue({ contactDefaults: {} });
    api.leave.reviewSummary.mockResolvedValue({ pendingCount: 0, requests: [] });
    api.leave.policyDocumentAvailable.mockResolvedValue(false);
  });

  it('defaults to the "ของฉัน" tab and hides "รอพิจารณา"/"ลูกทีม" for a plain employee with no reports', async () => {
    renderLeaveSurfacePage();

    const tabs = await screen.findAllByRole('tab');
    // "กฎการลา" is gone as of 2026-08-10 -- the §5 announcement is now the LeavePolicyBar above
    // the tabs, so a plain employee with no reports has exactly ONE tab.
    expect(tabs.map((tab) => tab.textContent)).toEqual(
      expect.arrayContaining([expect.stringContaining('ของฉัน')]),
    );
    expect(tabs.some((tab) => tab.textContent.includes('กฎการลา'))).toBe(false);
    expect(tabs.some((tab) => tab.textContent.includes('รอพิจารณา'))).toBe(false);
    // The beforeEach's api.leave.employees() fixture is self-only (directReport: false) --
    // see leaveSurfaceTabs.test.js's hasTeamMembers coverage for the pure-function rule.
    expect(tabs.some((tab) => tab.textContent.includes('ลูกทีม'))).toBe(false);
    expect(screen.getByRole('tab', { name: /ของฉัน/ }).getAttribute('aria-selected')).toBe('true');
  });

  // 2026-08 bugfix: "ลูกทีม" is the correctly-labelled, correctly-scoped home for the team-wide
  // view that used to leak, mislabelled, into "ของฉัน" -- see leaveSurfaceTabs.js's hasTeamMembers
  // and TeamLeaveTab.jsx's own header comment.
  it('a manager with a direct report in api.leave.employees() sees and can open "ลูกทีม"', async () => {
    const manager = { employeeId: 5, name: 'หัวหน้างาน', role: 'employee', manager: true };
    api.leave.employees.mockResolvedValue({
      employees: [
        {
          employeeId: 5, employeeName: 'หัวหน้างาน', employeeCode: 'GLR-005', self: true, directReport: false,
        },
        {
          employeeId: 6, employeeName: 'ลูกทีม', employeeCode: 'GLR-006', self: false, directReport: true,
        },
      ],
    });
    api.leave.list.mockResolvedValue({
      requests: [{
        id: 803, employeeId: 6, employeeName: 'ลูกทีม', managerEmployeeId: 5, status: 'APPROVED',
        leaveTypeCode: 'VACATION', leaveTypeNameTh: 'ลาพักร้อน', startDate: '2026-08-10', endDate: '2026-08-10',
        totalDays: 1, quotaRemainingAfter: 5, reason: 'พักผ่อนของลูกทีม',
      }],
    });
    renderLeaveSurfacePage(manager);

    const teamTab = await screen.findByRole('tab', { name: /ลูกทีม/ });
    fireEvent.click(teamTab);

    await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/leave?tab=team'));
    expect(await screen.findByText('พักผ่อนของลูกทีม')).not.toBeNull();
  });

  it('"ลูกทีม" sits between "ของฉัน" and "รอพิจารณา" when both are visible', async () => {
    const manager = { employeeId: 5, name: 'หัวหน้างาน', role: 'employee', manager: true };
    api.leave.employees.mockResolvedValue({
      employees: [
        {
          employeeId: 5, employeeName: 'หัวหน้างาน', employeeCode: 'GLR-005', self: true, directReport: false,
        },
        {
          employeeId: 6, employeeName: 'ลูกทีม', employeeCode: 'GLR-006', self: false, directReport: true,
        },
      ],
    });
    api.leave.list.mockResolvedValue({
      requests: [{
        id: 804, employeeId: 6, employeeName: 'ลูกทีม', managerEmployeeId: 5, status: 'SUBMITTED',
        leaveTypeCode: 'VACATION', leaveTypeNameTh: 'ลาพักร้อน', startDate: '2026-08-10', endDate: '2026-08-10',
        totalDays: 1, quotaRemainingAfter: 5, reason: 'พักผ่อน',
      }],
    });
    renderLeaveSurfacePage(manager);

    // Both "ลูกทีม" and "รอพิจารณา" only appear once their own async signal queries resolve --
    // wait for the slower of the two before reading tab order, or this can race and see only
    // the two unconditionally-visible tabs ("ของฉัน"/"กฎการลา").
    await screen.findByRole('tab', { name: /รอพิจารณา/ });
    const tabs = await screen.findAllByRole('tab');
    const labels = tabs.map((tab) => tab.textContent);
    const meIndex = labels.findIndex((label) => label.includes('ของฉัน'));
    const teamIndex = labels.findIndex((label) => label.includes('ลูกทีม'));
    const reviewIndex = labels.findIndex((label) => label.includes('รอพิจารณา'));
    expect(meIndex).toBeLessThan(teamIndex);
    expect(teamIndex).toBeLessThan(reviewIndex);
  });

  // Retargeted 2026-08-10: this used to click "กฎการลา", which no longer exists. A plain employee
  // now has only one tab, so the ?tab= round-trip needs an actor with a second one -- a manager
  // with a direct report, switching to "รอพิจารณา".
  it('switching tabs writes ?tab= with replace (no new history entry)', async () => {
    const manager = { employeeId: 5, name: 'หัวหน้างาน', role: 'employee', manager: true };
    api.leave.employees.mockResolvedValue({
      employees: [
        {
          employeeId: 5, employeeName: 'หัวหน้างาน', employeeCode: 'GLR-005', self: true, directReport: false,
        },
        {
          employeeId: 6, employeeName: 'ลูกทีม', employeeCode: 'GLR-006', self: false, directReport: true,
        },
      ],
    });
    api.leave.list.mockResolvedValue({
      requests: [{
        id: 805, employeeId: 6, employeeName: 'ลูกทีม', managerEmployeeId: 5, status: 'SUBMITTED',
        leaveTypeCode: 'VACATION', leaveTypeNameTh: 'ลาพักร้อน', startDate: '2026-08-10', endDate: '2026-08-10',
        totalDays: 1, quotaRemainingAfter: 5, reason: 'พักผ่อนของลูกทีม',
      }],
    });
    renderLeaveSurfacePage(manager);

    const reviewTab = await screen.findByRole('tab', { name: /รอพิจารณา/ });
    fireEvent.click(reviewTab);

    await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/leave?tab=review'));
    expect(screen.getByRole('tab', { name: /รอพิจารณา/ }).getAttribute('aria-selected')).toBe('true');
  });

  it('a stale/unknown ?tab= falls back to "ของฉัน"', async () => {
    renderLeaveSurfacePage(plainEmployee, ['/leave?tab=review']);

    await screen.findAllByRole('tab');
    expect(screen.getByRole('tab', { name: /ของฉัน/ }).getAttribute('aria-selected')).toBe('true');
  });

  it('a non-HR manager with an actionable report sees and can open "รอพิจารณา"', async () => {
    const manager = { employeeId: 5, name: 'หัวหน้างาน', role: 'employee', manager: true };
    api.leave.list.mockResolvedValue({
      requests: [{
        id: 801, employeeId: 1, employeeName: 'ลูกทีม', managerEmployeeId: 5, status: 'SUBMITTED',
        leaveTypeCode: 'VACATION', leaveTypeNameTh: 'ลาพักร้อน', startDate: '2026-08-10', endDate: '2026-08-10',
        totalDays: 1, quotaRemainingAfter: 5, reason: 'พักผ่อน',
      }],
    });
    renderLeaveSurfacePage(manager);

    const reviewTab = await screen.findByRole('tab', { name: /รอพิจารณา/ });
    fireEvent.click(reviewTab);

    await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/leave?tab=review'));
    expect(await screen.findByText('พักผ่อน')).not.toBeNull();
  });

  // Regression test for the double-scroll containment bug: the 16-field request
  // form's FileUploadField renders a real `<input type="file">` visually hidden
  // with Tailwind's `sr-only` utility, which is `position: absolute`. jsdom does
  // not compute real layout/geometry, so this cannot assert scroll pixels the
  // way the manual browser verification did (documentElement.scrollHeight ===
  // clientHeight at five widths, with the absolute input's static position
  // pinned at html.scrollHeight-1 pre-fix). What it CAN assert is the structural
  // invariant the fix relies on: PageStack -- the single element between
  // `.content-scroll` and that input -- must establish a CSS positioning
  // context (anything other than `position: static`), or the input's containing
  // block bubbles past `.content-scroll` to the document and drags the whole
  // page (including this sticky tab bar) into an outer scroll with it.
  // Leave HR-submit gate (2026-08-03): hr/ceo oversee leave but do not request it for
  // themselves (owner ruling) -- presentation only, the real rule is
  // LeaveService#resolveTargetEmployee's server-side 403. See leaveSurfaceTabs.js's
  // canSubmitOwnLeave/defaultLeaveSurfaceTabId.
  it('hr lands on "รอพิจารณา" by default and does not see the "ยื่นคำขอลา" CTA', async () => {
    const hr = { employeeId: 99, name: 'ฝ่ายบุคคล', role: 'hr', manager: false };
    renderLeaveSurfacePage(hr);

    const reviewTab = await screen.findByRole('tab', { name: /รอพิจารณา/ });
    expect(reviewTab.getAttribute('aria-selected')).toBe('true');
    expect(screen.queryByRole('button', { name: 'ยื่นคำขอลา' })).toBeNull();
  });

  it('ceo lands on "รอพิจารณา" by default (once visible) and does not see the "ยื่นคำขอลา" CTA', async () => {
    const ceo = { employeeId: 2, name: 'ผู้บริหารระดับสูง', role: 'ceo', manager: false };
    // ceo is not in ROLE_PERMISSIONS.canReviewLeave (['hr'] only), so "review" is only visible
    // once an actionable row loads -- seed one under the ceo's own direct management.
    api.leave.list.mockResolvedValue({
      requests: [{
        id: 802, employeeId: 10, employeeName: 'ลูกทีมของ CEO', managerEmployeeId: 2, status: 'SUBMITTED',
        leaveTypeCode: 'VACATION', leaveTypeNameTh: 'ลาพักร้อน', startDate: '2026-08-10', endDate: '2026-08-10',
        totalDays: 1, quotaRemainingAfter: 5, reason: 'พักผ่อน',
      }],
    });
    renderLeaveSurfacePage(ceo);

    const reviewTab = await screen.findByRole('tab', { name: /รอพิจารณา/ });
    await waitFor(() => expect(reviewTab.getAttribute('aria-selected')).toBe('true'));
    expect(screen.queryByRole('button', { name: 'ยื่นคำขอลา' })).toBeNull();
  });

  it('a plain employee still sees the "ยื่นคำขอลา" CTA', async () => {
    renderLeaveSurfacePage();

    await screen.findAllByRole('tab');
    expect(screen.getByRole('button', { name: 'ยื่นคำขอลา' })).not.toBeNull();
  });

  // Regression: leaveSurfaceTabs.js's resolveTabLabel/resolveTabHelper exist to swap the "team"
  // tab's copy for hr/ceo (they see every employee, not a real org-chart team -- "ลูกทีม" is a
  // misleading label for them). That swap is only proven correct if something actually RENDERS
  // the resolved label -- a test that only calls resolveTabLabel() directly, without also
  // rendering LeaveSurfacePage and reading the tab's DOM text, would pass even if
  // LeaveSurfacePage.jsx were reverted to read the tab's static `label`/`helper` fields instead.
  it('an hr user with team members sees "พนักงานทั้งหมด", never the manager-facing "ลูกทีม" label', async () => {
    api.leave.employees.mockResolvedValue({
      employees: [
        { employeeId: 99, employeeName: 'ฝ่ายบุคคล', employeeCode: 'GLR-099', self: true, directReport: false },
        { employeeId: 1, employeeName: 'พนักงาน ทดสอบ', employeeCode: 'GLR-001', self: false, directReport: false },
      ],
    });
    const hr = { employeeId: 99, name: 'ฝ่ายบุคคล', role: 'hr', manager: false };
    renderLeaveSurfacePage(hr);

    const teamTab = await screen.findByRole('tab', { name: /พนักงานทั้งหมด/ });
    expect(screen.queryByRole('tab', { name: /^ลูกทีม$/ })).toBeNull();
    expect(teamTab).not.toBeNull();
  });

  it('the page root establishes a positioning context so the request form\'s absolutely-positioned file input stays contained by .content-scroll', async () => {
    const { container } = renderLeaveSurfacePage();
    await screen.findAllByRole('tab');

    const tablist = container.querySelector('[role="tablist"]');
    // PageStack is the only ancestor between the scroll container and the tab
    // bar / form that renders Tailwind's literal `grid` class (its signature
    // utility, per Layout.jsx's PageStack) -- walk up to it rather than
    // asserting on `container.firstChild`, which is brittle to unrelated
    // wrapper changes.
    const pageRoot = tablist.closest('.grid');
    expect(pageRoot).not.toBeNull();
    expect(pageRoot.classList.contains('relative')).toBe(true);
  });
});
