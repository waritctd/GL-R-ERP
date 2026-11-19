import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { HrOverview } from './HrOverview.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      payroll: {
        current: vi.fn().mockResolvedValue({ period: null }),
        getTaxAllowanceDeclarations: vi.fn().mockResolvedValue({
          items: [
            { declarationId: 1, employeeId: 9, status: 'PENDING' },
            { declarationId: 2, employeeId: 10, status: 'PENDING' },
            { declarationId: 3, employeeId: 11, status: 'APPROVED' },
          ],
        }),
      },
    },
  };
});

const employee = { nameTh: 'ฝ่ายบุคคล ทดสอบ', positionTh: 'เจ้าหน้าที่บุคคล', divisionTh: 'HR-ฝ่ายบุคคล' };
const employees = [
  { id: 1, active: true, divisionId: '17', divisionTh: 'HR-ฝ่ายบุคคล' },
  { id: 2, active: true, divisionId: '9', divisionTh: 'SA-ฝ่ายขาย' },
];

const dashboardSummary = {
  headcount: {
    scope: 'all',
    total: 28,
    active: 26,
    byDivision: [
      { divisionId: '17', divisionCode: 'HR', divisionName: 'HR-ฝ่ายบุคคล', total: 4, active: 4 },
      { divisionId: '9', divisionCode: 'SA', divisionName: 'SA-ฝ่ายขาย', total: 12, active: 11 },
    ],
  },
  pendingApprovals: { total: 6, profileRequests: 3, overtime: 2, leave: 1, commissions: 0 },
  attendance: { todayPresent: 20, lateToday: 2, missingCheckout: 1 },
  notifications: { unread: 5 },
};

function renderOverview(summary = dashboardSummary, queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
})) {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <HrOverview employee={employee} employees={employees} dashboardSummary={summary} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('HrOverview', () => {
  it('shows payroll period status with a CTA to /payroll', () => {
    renderOverview();
    expect(screen.getByText('รอบเงินเดือน')).not.toBeNull();
    expect(screen.getByText('ยังไม่เริ่มรอบเงินเดือนเดือนนี้')).not.toBeNull();
    expect(screen.getByText('ไปที่หน้าเงินเดือน').closest('button')).not.toBeNull();
  });

  it('renders leave/OT as monitoring-only, with no approve button anywhere on the page', () => {
    renderOverview();
    const monitorPanel = screen.getByText('ภาพรวมลา / OT (ดูอย่างเดียว)').closest('section');
    expect(within(monitorPanel).getByText('อนุมัติโดยหัวหน้าฝ่าย/CEO — ฝ่ายบุคคลติดตามสถานะเท่านั้น')).not.toBeNull();
    expect(within(monitorPanel).getByText('คำขอลารออนุมัติ')).not.toBeNull();
    expect(within(monitorPanel).getByText('OT รออนุมัติ')).not.toBeNull();

    // No button anywhere on the page is an approval action (e.g. "อนุมัติ") — HR approves nothing here; it only
    // monitors leave/OT. This deliberately does not match "รออนุมัติ" ("awaiting approval") labels, which are
    // informational counts, not approve buttons.
    const approveButtons = screen.queryAllByRole('button')
      .filter((button) => /^อนุมัติ/.test(button.textContent.trim()));
    expect(approveButtons).toHaveLength(0);
  });
});
