import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaxAllowanceReviewPage } from './TaxAllowanceReviewPage.jsx';
import { api } from '../../api/index.js';
import { taxAllowanceStatusShortLabel } from './taxAllowanceStatus.js';

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

  describe('status chip labels (canonical map)', () => {
    it('sources every status chip label from taxAllowanceStatusShortLabel, not a page-local literal', async () => {
      renderPage({ user: hrUser, entry: '/tax-allowance-review?status=' });
      await screen.findByText('สมชาย ใจดี');
      // Read from the same function TaxAllowanceReviewPage's STATUS_CHIPS calls to build its
      // labels. If STATUS_CHIPS ever grows its own hardcoded literal again — the exact drift this
      // PR fixes, where APPROVED_UNAPPLIED and EXPIRED read differently here than on the badge —
      // a divergence makes this fail instead of passing on a lucky coincidence of matching text.
      for (const key of ['NONE', 'PENDING', 'APPROVED_UNAPPLIED', 'APPLIED', 'EXPIRED', 'REJECTED']) {
        expect(screen.getByRole('button', { name: taxAllowanceStatusShortLabel(key) })).not.toBeNull();
      }
    });

    it('keeps the chip SHORT label distinct from the badge LONG label where the two differ', async () => {
      const expiredDeclaration = {
        declarationId: 77,
        employeeId: 9,
        employeeCode: 'EMP009',
        employeeName: 'สมชาย ใจดี',
        status: 'EXPIRED',
        submittedAt: '2025-01-01T00:00:00.000Z',
        appliedAt: null,
        appliedEffectiveMonth: null,
        expiresOn: '2026-01-01',
        reviewerNote: null,
      };
      api.payroll.getTaxAllowanceDeclarations.mockResolvedValue({ items: [expiredDeclaration] });
      renderPage({ user: hrUser, entry: '/tax-allowance-review?status=' });

      // Long form (StatusBadge, on the row itself) keeps the full sentence.
      expect(await screen.findByText('หมดอายุ — ต้องยืนยันใหม่')).not.toBeNull();
      // Short form (the filter chip) is the compact noun alone — a distinct DOM node, not a
      // truncated rendering of the same text.
      const chip = screen.getByRole('button', { name: 'หมดอายุ' });
      expect(chip.textContent).not.toContain('ต้องยืนยันใหม่');
    });
  });

  describe('empty state honesty', () => {
    it('does not claim "no data" when a status filter matches zero rows out of a non-empty table', async () => {
      // Reachable today from any chip whose bucket happens to be empty for the year — the fixture
      // has one PENDING and one NONE (no-declaration) employee, and zero REJECTED ones.
      renderPage({ user: hrUser, entry: '/tax-allowance-review?status=REJECTED' });
      // The honest, filter-aware message appears...
      expect(await screen.findByText('ไม่พบพนักงานที่ตรงกับตัวกรองนี้')).not.toBeNull();
      // ...naming the real (pre-filter) headcount, not the zero the filter produced.
      expect(screen.getByText('ลองเลือก "ทั้งหมด" เพื่อดูพนักงานทั้งหมด 2 คน')).not.toBeNull();
      // ...and the old "there is no data at all" claim — false here, ~207 real employees exist in
      // production — does not appear anywhere (not even doubled into the sr-only live region).
      expect(screen.queryByText('ไม่มีข้อมูลพนักงานในปีนี้')).toBeNull();
    });

    it('keeps the original "no data at all" message when the table is genuinely empty (no filter active)', async () => {
      api.payroll.getTaxAllowanceDeclarations.mockResolvedValue({ items: [] });
      api.employees.list.mockResolvedValue({ employees: [] });
      renderPage({ user: hrUser }); // ทั้งหมด (no status filter) — this is not a filtered-empty case
      // The text is duplicated into DataTable's `aria-live` sr-only region alongside the visible
      // `<strong>` title (DataTable.jsx), so this asserts on the count rather than a single node.
      await waitFor(() => {
        expect(screen.getAllByText('ไม่มีข้อมูลพนักงานในปีนี้').length).toBeGreaterThan(0);
      });
      expect(screen.queryByText('ไม่พบพนักงานที่ตรงกับตัวกรองนี้')).toBeNull();
    });
  });

  describe('row menu — ยื่นแทนพนักงาน placement', () => {
    const soloEmployee = [{ id: 20, code: 'EMP020', nameTh: 'มานะ ตั้งใจ', active: true }];

    function declarationFor(status, extra = {}) {
      return {
        declarationId: 100,
        employeeId: 20,
        employeeCode: 'EMP020',
        employeeName: 'มานะ ตั้งใจ',
        status,
        submittedAt: '2026-01-01T00:00:00.000Z',
        appliedAt: null,
        appliedEffectiveMonth: null,
        expiresOn: null,
        reviewerNote: null,
        ...extra,
      };
    }

    // `?status=` (ทั้งหมด) throughout: these tests are about menu CONTENT for a given row status,
    // not which chip is selected — pinning ALL keeps the fixture's one row visible regardless of
    // which status `declarationFor` below is given.
    async function openRowMenu() {
      renderPage({ entry: '/tax-allowance-review?status=' });
      fireEvent.click(await screen.findByRole('button', { name: 'การดำเนินการสำหรับ มานะ ตั้งใจ' }));
      return screen.getAllByRole('menuitem');
    }

    it('drops ยื่นแทนพนักงาน from a PENDING row, but keeps อนุมัติ/ปฏิเสธ', async () => {
      api.payroll.getTaxAllowanceDeclarations.mockResolvedValue({ items: [declarationFor('PENDING')] });
      api.employees.list.mockResolvedValue({ employees: soloEmployee });
      const labels = (await openRowMenu()).map((item) => item.textContent);
      expect(labels.some((text) => text.includes('ยื่นแทนพนักงาน'))).toBe(false);
      expect(labels.some((text) => text.includes('อนุมัติ'))).toBe(true);
      expect(labels.some((text) => text.includes('ปฏิเสธ'))).toBe(true);
    });

    it('drops ยื่นแทนพนักงาน from an APPLIED row entirely — nothing is left to do', async () => {
      api.payroll.getTaxAllowanceDeclarations.mockResolvedValue({
        items: [declarationFor('APPROVED', { appliedAt: '2026-04-01T00:00:00.000Z', appliedEffectiveMonth: 4 })],
      });
      api.employees.list.mockResolvedValue({ employees: soloEmployee });
      renderPage({ entry: '/tax-allowance-review?status=' });
      await screen.findByText('มานะ ตั้งใจ');
      // No actions at all remain for an applied declaration — the overflow trigger itself does not
      // render (OverflowMenu returns null on an empty items array), not just a missing menu item.
      expect(screen.queryByRole('button', { name: 'การดำเนินการสำหรับ มานะ ตั้งใจ' })).toBeNull();
    });

    it('keeps ยื่นแทนพนักงาน as the only action on NONE — the only path in for staff who never log in', async () => {
      api.payroll.getTaxAllowanceDeclarations.mockResolvedValue({ items: [] });
      api.employees.list.mockResolvedValue({ employees: soloEmployee });
      const items = await openRowMenu();
      expect(items).toHaveLength(1);
      expect(items[0].textContent).toContain('ยื่นแทนพนักงาน');
    });

    it('keeps ยื่นแทนพนักงาน alongside ใช้กับเงินเดือน on APPROVED_UNAPPLIED', async () => {
      api.payroll.getTaxAllowanceDeclarations.mockResolvedValue({ items: [declarationFor('APPROVED')] });
      api.employees.list.mockResolvedValue({ employees: soloEmployee });
      const labels = (await openRowMenu()).map((item) => item.textContent);
      expect(labels.some((text) => text.includes('ยื่นแทนพนักงาน'))).toBe(true);
      expect(labels.some((text) => text.includes('ใช้กับเงินเดือน'))).toBe(true);
    });

    it('keeps ยื่นแทนพนักงาน alongside ยืนยันใหม่ on EXPIRED', async () => {
      api.payroll.getTaxAllowanceDeclarations.mockResolvedValue({ items: [declarationFor('EXPIRED')] });
      api.employees.list.mockResolvedValue({ employees: soloEmployee });
      const labels = (await openRowMenu()).map((item) => item.textContent);
      expect(labels.some((text) => text.includes('ยื่นแทนพนักงาน'))).toBe(true);
      expect(labels.some((text) => text.includes('ยืนยันใหม่'))).toBe(true);
    });

    it('keeps ยื่นแทนพนักงาน as the only action on REJECTED', async () => {
      api.payroll.getTaxAllowanceDeclarations.mockResolvedValue({ items: [declarationFor('REJECTED')] });
      api.employees.list.mockResolvedValue({ employees: soloEmployee });
      const items = await openRowMenu();
      expect(items).toHaveLength(1);
      expect(items[0].textContent).toContain('ยื่นแทนพนักงาน');
    });
  });
});
