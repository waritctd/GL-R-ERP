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
  });

  it('defaults to the "ของฉัน" tab and hides "รอพิจารณา" for a plain employee', async () => {
    renderLeaveSurfacePage();

    const tabs = await screen.findAllByRole('tab');
    expect(tabs.map((tab) => tab.textContent)).toEqual(
      expect.arrayContaining([expect.stringContaining('ของฉัน'), expect.stringContaining('กฎการลา')]),
    );
    expect(tabs.some((tab) => tab.textContent.includes('รอพิจารณา'))).toBe(false);
    expect(screen.getByRole('tab', { name: /ของฉัน/ }).getAttribute('aria-selected')).toBe('true');
  });

  it('switching tabs writes ?tab= with replace (no new history entry)', async () => {
    renderLeaveSurfacePage();

    await screen.findAllByRole('tab');
    fireEvent.click(screen.getByRole('tab', { name: /กฎการลา/ }));

    await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/leave?tab=rules'));
    expect(screen.getByRole('tab', { name: /กฎการลา/ }).getAttribute('aria-selected')).toBe('true');
    // Placeholder panel, not real rule copy (Phase A1 deliberately adds none).
    expect(await screen.findByText('หน้ากฎการลากำลังจะมา')).not.toBeNull();
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
