import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EmployeeDetailPage } from './EmployeeDetailPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      employees: { get: vi.fn() },
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

function renderDetail(user) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/employees/9']}>
        <Routes>
          <Route
            path="/employees/:id"
            element={<EmployeeDetailPage user={user} onUpdateEmployee={vi.fn()} />}
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
