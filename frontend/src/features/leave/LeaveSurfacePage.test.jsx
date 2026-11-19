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
      // LeavePolicyBar.jsx (rendered unconditionally by this page) probes this on every render.
      // Without a mock the call falls through to `undefined()` and throws inside the query --
      // react-query swallows that into a silent error state, but a real mock is more honest.
      policyDocumentAvailable: vi.fn(),
      downloadPolicyDocument: vi.fn(),
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
    // The state every environment is in today (see LeavePolicyBar.jsx's own header comment) -- none of this file's
    // assertions are about the bar itself, so the neutral default is enough.
    api.leave.policyDocumentAvailable.mockResolvedValue('absent');
  });

  // "ลูกทีม" is the correctly-labelled, correctly-scoped home for the team-wide view -- see
  // leaveSurfaceTabs.js's hasTeamMembers and TeamLeaveTab.jsx's header comment.
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

  // Double-scroll containment: the request form's FileUploadField renders a real
  // `<input type="file">` visually hidden with Tailwind's `sr-only` (`position: absolute`). jsdom
  // does not compute layout, so this cannot assert scroll pixels; it asserts the structural
  // invariant the fix relies on: PageStack -- the single element between `.content-scroll` and
  // that input -- must establish a CSS positioning context (anything other than `position:
  // static`), or the input's containing block bubbles past `.content-scroll` to the document and
  // drags the whole page (including this sticky tab bar) into an outer scroll.
  // hr/ceo oversee leave but do not request it for themselves -- presentation only, the real rule
  // is LeaveService#resolveTargetEmployee's server-side 403. See leaveSurfaceTabs.js's
  // canSubmitOwnLeave/defaultLeaveSurfaceTabId.
  it('hr lands on "รอพิจารณา" by default and does not see the "ยื่นคำขอลา" CTA', async () => {
    const hr = { employeeId: 99, name: 'ฝ่ายบุคคล', role: 'hr', manager: false };
    renderLeaveSurfacePage(hr);

    const reviewTab = await screen.findByRole('tab', { name: /รอพิจารณา/ });
    expect(reviewTab.getAttribute('aria-selected')).toBe('true');
    expect(screen.queryByRole('button', { name: 'ยื่นคำขอลา' })).toBeNull();
  });

  it('a plain employee still sees the "ยื่นคำขอลา" CTA', async () => {
    renderLeaveSurfacePage();

    await screen.findAllByRole('tab');
    expect(screen.getByRole('button', { name: 'ยื่นคำขอลา' })).not.toBeNull();
  });
});
