import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EmployeeDetailPage } from './EmployeeDetailPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      employees: { get: vi.fn(), resetPassword: vi.fn() },
      payroll: {
        getTaxAllowanceDeclarations: vi.fn(),
        getTaxAllowanceCaps: vi.fn(),
      },
    },
  };
});

const employee = {
  id: 9,
  code: 'EMP009',
  nameTh: 'สมชาย ใจดี',
  nameEn: 'Somchai Jaidee',
  nickName: 'ชาย',
  titleTh: 'นาย',
  genderTh: 'ชาย',
  birthDate: '1990-01-01',
  age: 36,
  nationality: 'ไทย',
  maritalStatus: 'โสด',
  email: 'somchai@example.com',
  phone: '0800000000',
  badge: 'B009',
  positionTh: 'พนักงาน',
  divisionTh: 'ฝ่ายขาย',
  departmentTh: 'ขายในประเทศ',
  statusTone: 'teal',
  statusTh: 'ทำงานอยู่',
  currentAddress: { line1: '1/1', district: 'บางรัก', province: 'กรุงเทพฯ', postalCode: '10500' },
  emergencyContact: { name: 'สมหญิง', relationship: 'พี่สาว', phone: '0810000000' },
  sensitive: {
    nationalId: '1234567890123',
    taxId: '1234567890123',
    socialSecurityNo: 'SSO-1',
    socialSecurityHospital: 'รพ.ทดสอบ',
    providentFundNo: 'PF-1',
  },
};

const declaration = {
  declarationId: 55,
  employeeId: 9,
  employeeCode: 'EMP009',
  employeeName: 'สมชาย ใจดี',
  status: 'APPROVED',
  submittedAt: '2026-03-01T00:00:00.000Z',
  appliedAt: '2026-04-01T00:00:00.000Z',
  appliedEffectiveMonth: 4,
  expiresOn: '2027-04-01',
  reviewerNote: null,
};

function renderDetail(user, { showToast = vi.fn() } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/employees/9']}>
        <Routes>
          <Route
            path="/employees/:id"
            element={<EmployeeDetailPage user={user} onUpdateEmployee={vi.fn()} showToast={showToast} />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('EmployeeDetailPage — ล.ย.01 section', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.employees.get.mockResolvedValue({ employee });
    api.payroll.getTaxAllowanceDeclarations.mockResolvedValue({ items: [declaration] });
    api.payroll.getTaxAllowanceCaps.mockResolvedValue({ caps: [] });
  });

  it('shows the declaration inside the sensitive tab for HR', async () => {
    renderDetail({ role: 'hr', employeeId: 1 });
    fireEvent.click(await screen.findByRole('button', { name: /ข้อมูลอ่อนไหว/ }));

    expect(await screen.findByText('ค่าลดหย่อนภาษี (ล.ย.01)')).not.toBeNull();
    // The status comes from the shared taxAllowanceStatus helper, so this asserts the drill-down
    // actually rendered rather than just the section heading.
    expect(await screen.findByText(/ใช้กับเงินเดือนแล้ว ตั้งแต่เดือน 4/)).not.toBeNull();
  });

  it('links onward to the HR register for the same employee and year', async () => {
    renderDetail({ role: 'hr', employeeId: 1 });
    fireEvent.click(await screen.findByRole('button', { name: /ข้อมูลอ่อนไหว/ }));

    const link = await screen.findByRole('link', { name: 'ดูในหน้าตรวจสอบของ HR' });
    expect(link.getAttribute('href')).toContain('q=EMP009');
    expect(link.getAttribute('href')).toContain(`year=${new Date().getFullYear()}`);
  });

  it('hides the whole sensitive tab — and with it ล.ย.01 — without canViewSensitiveEmployeeData', async () => {
    // Component-level gating only: /employees/:id is already hr-only at the route
    // (canViewEmployees), so this asserts the tab branch, not a route guard.
    renderDetail({ role: 'employee', employeeId: 9 });
    // The name also appears in the breadcrumb, so anchor on the hero heading specifically.
    await screen.findByRole('heading', { name: 'สมชาย ใจดี' });

    expect(screen.queryByRole('button', { name: /ข้อมูลอ่อนไหว/ })).toBeNull();
    expect(screen.queryByText('ค่าลดหย่อนภาษี (ล.ย.01)')).toBeNull();
    expect(api.payroll.getTaxAllowanceDeclarations).not.toHaveBeenCalled();
  });
});

describe('EmployeeDetailPage — ตั้งรหัสผ่านชั่วคราว (issue #744)', () => {
  const TEMP_PASSWORD = 'Kbn7RtWq3xZmDp';

  beforeEach(() => {
    vi.clearAllMocks();
    api.employees.get.mockResolvedValue({ employee });
    api.payroll.getTaxAllowanceDeclarations.mockResolvedValue({ items: [declaration] });
    api.payroll.getTaxAllowanceCaps.mockResolvedValue({ caps: [] });
    api.employees.resetPassword.mockResolvedValue({ temporaryPassword: TEMP_PASSWORD });
  });

  // The overflow item is role="menuitem" (OverflowMenu), while ConfirmDialog's confirm control is a
  // real button carrying the same Thai label — so the roles are what keep the two apart here.
  async function openResetMenu() {
    fireEvent.click(await screen.findByRole('button', { name: /การดำเนินการเพิ่มเติม/ }));
    return screen.findByRole('menuitem', { name: 'ตั้งรหัสผ่านชั่วคราว' });
  }

  // The control is HR-only client-side; EmployeeController#resetPassword is the enforcing gate.
  // Written wrong-way-round: the case that matters is that a non-HR viewer is NOT offered it.
  it.each(['employee', 'ceo', 'sales'])('does not offer the control to role=%s', async (role) => {
    renderDetail({ role, employeeId: 9 });
    await screen.findByRole('heading', { name: 'สมชาย ใจดี' });

    expect(screen.queryByRole('button', { name: /การดำเนินการเพิ่มเติม/ })).toBeNull();
    expect(screen.queryByText('ตั้งรหัสผ่านชั่วคราว')).toBeNull();
    expect(api.employees.resetPassword).not.toHaveBeenCalled();
  });

  it('asks for confirmation before firing the request', async () => {
    renderDetail({ role: 'hr', employeeId: 1 });
    fireEvent.click(await openResetMenu());

    // Confirm step is up, and nothing has been sent yet — the reset is destructive.
    expect(await screen.findByText(/รหัสผ่านเดิมจะใช้ไม่ได้ทันที/)).not.toBeNull();
    expect(api.employees.resetPassword).not.toHaveBeenCalled();
    expect(screen.queryByTestId('temporary-password-value')).toBeNull();
  });

  it('shows the temporary password once confirmed, with the one-time warning', async () => {
    renderDetail({ role: 'hr', employeeId: 1 });
    fireEvent.click(await openResetMenu());
    fireEvent.click(await screen.findByRole('button', { name: 'ตั้งรหัสผ่านชั่วคราว' }));

    const value = await screen.findByTestId('temporary-password-value');
    expect(value.textContent).toBe(TEMP_PASSWORD);
    expect(api.employees.resetPassword).toHaveBeenCalledWith(9);
    expect(screen.getByText(/ระบบจะแสดงรหัสผ่านนี้เพียงครั้งเดียว/)).not.toBeNull();
    expect(screen.getByText(/บังคับให้ตั้งรหัสผ่านใหม่/)).not.toBeNull();
  });

  it('copies the password to the clipboard', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

    renderDetail({ role: 'hr', employeeId: 1 });
    fireEvent.click(await openResetMenu());
    fireEvent.click(await screen.findByRole('button', { name: 'ตั้งรหัสผ่านชั่วคราว' }));
    await screen.findByTestId('temporary-password-value');
    fireEvent.click(screen.getByRole('button', { name: /คัดลอก/ }));

    expect(writeText).toHaveBeenCalledWith(TEMP_PASSWORD);
    expect(await screen.findByRole('button', { name: /คัดลอกแล้ว/ })).not.toBeNull();
  });

  it('keeps the password out of the toast', async () => {
    const showToast = vi.fn();
    renderDetail({ role: 'hr', employeeId: 1 }, { showToast });
    fireEvent.click(await openResetMenu());
    fireEvent.click(await screen.findByRole('button', { name: 'ตั้งรหัสผ่านชั่วคราว' }));
    await screen.findByTestId('temporary-password-value');

    expect(showToast).toHaveBeenCalled();
    // Toast text is retained and re-rendered by the app shell, well outside this dialog's life.
    for (const [, message] of showToast.mock.calls) {
      expect(message).not.toContain(TEMP_PASSWORD);
    }
  });

  it('retains nothing after the dialog closes, and re-opens on the confirm step', async () => {
    renderDetail({ role: 'hr', employeeId: 1 });
    fireEvent.click(await openResetMenu());
    fireEvent.click(await screen.findByRole('button', { name: 'ตั้งรหัสผ่านชั่วคราว' }));
    await screen.findByTestId('temporary-password-value');

    fireEvent.click(screen.getByRole('button', { name: 'เสร็จสิ้น' }));

    await waitFor(() => expect(screen.queryByTestId('temporary-password-value')).toBeNull());
    // The value must be gone from the document entirely, not merely hidden.
    expect(document.body.textContent).not.toContain(TEMP_PASSWORD);

    // Re-opening starts at the confirm step again rather than replaying the old value.
    fireEvent.click(await openResetMenu());
    expect(await screen.findByText(/รหัสผ่านเดิมจะใช้ไม่ได้ทันที/)).not.toBeNull();
    expect(screen.queryByTestId('temporary-password-value')).toBeNull();
    expect(document.body.textContent).not.toContain(TEMP_PASSWORD);
  });

  it('surfaces a failed reset without closing or revealing anything', async () => {
    api.employees.resetPassword.mockRejectedValue(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'));

    renderDetail({ role: 'hr', employeeId: 1 });
    fireEvent.click(await openResetMenu());
    fireEvent.click(await screen.findByRole('button', { name: 'ตั้งรหัสผ่านชั่วคราว' }));

    expect(await screen.findByText('ไม่มีสิทธิ์เข้าถึงรายการนี้')).not.toBeNull();
    expect(screen.queryByTestId('temporary-password-value')).toBeNull();
  });
});
