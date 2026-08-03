import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaxAllowanceReviewPage } from './TaxAllowanceReviewPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      payroll: {
        getTaxAllowanceCaps: vi.fn(),
        getTaxAllowanceDeclarations: vi.fn(),
        listTaxAllowanceAttachments: vi.fn(),
      },
      employees: { list: vi.fn() },
    },
  };
});

const hrUser = { role: 'hr', name: 'บุคคล ทดสอบ', employeeId: 1 };
// CEO holds canViewTaxAllowanceRegister but NOT canViewEmployees — the whole point of these tests.
const ceoUser = { role: 'ceo', name: 'ซีอีโอ ทดสอบ', employeeId: 2 };

const pendingDeclaration = {
  declarationId: 55,
  employeeId: 9,
  employeeCode: 'EMP009',
  employeeName: 'สมชาย ใจดี',
  status: 'PENDING',
  submittedAt: '2026-03-01T00:00:00.000Z',
  appliedAt: null,
  appliedEffectiveMonth: null,
  expiresOn: null,
  reviewerNote: null,
};

// An employee with no declaration at all — HR must see them as ยังไม่ได้ยื่น, CEO must not see
// them at all (CEO cannot enumerate employees).
const employees = [
  { id: 9, code: 'EMP009', nameTh: 'สมชาย ใจดี', active: true },
  { id: 10, code: 'EMP010', nameTh: 'สมหญิง มีสุข', active: true },
];

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{`${location.pathname}${location.search}`}</div>;
}

function renderPage({ user = hrUser, entry = '/tax-allowance-review' } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <TaxAllowanceReviewPage user={user} showToast={vi.fn()} />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TaxAllowanceReviewPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.payroll.getTaxAllowanceCaps.mockResolvedValue({ caps: [] });
    api.payroll.getTaxAllowanceDeclarations.mockResolvedValue({ items: [pendingDeclaration] });
    api.payroll.listTaxAllowanceAttachments.mockResolvedValue({ items: [] });
    api.employees.list.mockResolvedValue({ employees });
  });

  describe('HR', () => {
    it('offers the ยังไม่ได้ยื่น chip and synthesizes a row for an employee with no declaration', async () => {
      renderPage({ user: hrUser });
      expect(await screen.findByRole('button', { name: 'ยังไม่ได้ยื่น' })).not.toBeNull();
      // Both employees are listed — the one who filed and the one who did not.
      expect(await screen.findByText('สมชาย ใจดี')).not.toBeNull();
      expect(screen.getByText('สมหญิง มีสุข')).not.toBeNull();
    });
  });

  describe('CEO (no canViewEmployees)', () => {
    it('drops the ยังไม่ได้ยื่น chip, which without the employee list could only match zero rows', async () => {
      renderPage({ user: ceoUser });
      await screen.findByText('สมชาย ใจดี');
      expect(screen.queryByRole('button', { name: 'ยังไม่ได้ยื่น' })).toBeNull();
      // The real statuses stay available.
      expect(screen.getByRole('button', { name: 'รอ HR ตรวจสอบ' })).not.toBeNull();
    });

    it('says the table lists filed declarations only, instead of claiming to cover every employee', async () => {
      renderPage({ user: ceoUser });
      await screen.findByText('สมชาย ใจดี');
      expect(screen.queryByText(/ของพนักงานทุกคน/)).toBeNull();
      expect(screen.getByText(/พนักงานที่ยังไม่ได้ยื่นจะไม่ปรากฏในตารางนี้/)).not.toBeNull();
    });

    it('never enumerates employees', async () => {
      renderPage({ user: ceoUser });
      await screen.findByText('สมชาย ใจดี');
      expect(api.employees.list).not.toHaveBeenCalled();
      // Only the employee who actually filed is present.
      expect(screen.queryByText('สมหญิง มีสุข')).toBeNull();
    });

    it('falls back to ทั้งหมด on a ?status=NONE deep-link rather than pinning an empty table', async () => {
      renderPage({ user: ceoUser, entry: '/tax-allowance-review?status=NONE' });
      // The row is still shown: the unreachable filter was ignored, not applied.
      expect(await screen.findByText('สมชาย ใจดี')).not.toBeNull();
      expect(screen.getByRole('button', { name: 'ทั้งหมด' }).getAttribute('aria-pressed')).toBe('true');
    });
  });

  describe('URL state', () => {
    it('reads the tax year from ?year= so a prior-year drill-down lands on the right table', async () => {
      renderPage({ entry: '/tax-allowance-review?year=2024' });
      await waitFor(() => {
        expect(api.payroll.getTaxAllowanceDeclarations).toHaveBeenCalledWith({ year: 2024 });
      });
      expect(screen.getByLabelText('ปีภาษี').value).toBe('2024');
    });

    it('ignores an out-of-range ?year= instead of showing a blank select', async () => {
      renderPage({ entry: '/tax-allowance-review?year=1999' });
      const currentYear = new Date().getFullYear();
      await waitFor(() => {
        expect(api.payroll.getTaxAllowanceDeclarations).toHaveBeenCalledWith({ year: currentYear });
      });
      expect(screen.getByLabelText('ปีภาษี').value).toBe(String(currentYear));
    });

    it('writes the status filter to the URL', async () => {
      renderPage();
      await screen.findByText('สมชาย ใจดี');
      fireEvent.click(screen.getByRole('button', { name: 'รอ HR ตรวจสอบ' }));
      await waitFor(() => {
        expect(screen.getByTestId('location').textContent).toContain('status=PENDING');
      });
    });

    it('keeps the chosen year when the search box is typed into', async () => {
      renderPage({ entry: '/tax-allowance-review?year=2024' });
      await screen.findByText('สมชาย ใจดี');
      fireEvent.change(screen.getByPlaceholderText('ค้นหาพนักงาน…'), { target: { value: 'EMP009' } });
      await waitFor(() => {
        const location = screen.getByTestId('location').textContent;
        expect(location).toContain('q=EMP009');
        // The regression this guards: the old handler replaced the whole query string.
        expect(location).toContain('year=2024');
      });
    });
  });
});
