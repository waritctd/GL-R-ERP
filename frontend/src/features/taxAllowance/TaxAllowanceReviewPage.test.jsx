import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, within } from '@testing-library/react';
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
        // "register shows what payroll actually uses" (2026-08) — the second source TaxAllowanceReviewPage now joins
        // in alongside getTaxAllowanceDeclarations above.
        getTaxAllowances: vi.fn(),
        listTaxAllowanceAttachments: vi.fn(),
        // F5 cache-invalidation coverage only — approve is the simplest of the five review mutations to drive end to
        // end (no reason/effective-month input needed).
        approveTaxAllowanceDeclaration: vi.fn(),
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

// An employee with no declaration at all — HR must see them as ยังไม่ได้ยื่น, CEO must not see them at all
// (CEO cannot enumerate employees).
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
    // Empty by default so every PRE-EXISTING test below keeps its original behaviour: resolvePayrollAllowance is
    // always null, so taxAllowanceStatusInfo's new branches never fire and every row falls through to the same
    // declaration-only logic as before this feature existed.
    api.payroll.getTaxAllowances.mockResolvedValue({ items: [] });
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

    // `?status=` (ทั้งหมด) throughout: these tests are about menu CONTENT for a given row status, not which chip
    // is selected — pinning ALL keeps the fixture's one row visible regardless of which status `declarationFor`
    // below is given.
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

    it('keeps ยื่นแทนพนักงาน as the only action on NONE — the only path in for staff who never log in', async () => {
      api.payroll.getTaxAllowanceDeclarations.mockResolvedValue({ items: [] });
      api.employees.list.mockResolvedValue({ employees: soloEmployee });
      const items = await openRowMenu();
      expect(items).toHaveLength(1);
      expect(items[0].textContent).toContain('ยื่นแทนพนักงาน');
    });
  });

  // "Register shows what payroll actually uses" (2026-08): GET /api/payroll/tax-allowances
  // (hr.employee_tax_allowance) joined in by employeeId, alongside the declaration register above
  // (hr.tax_allowance_declaration) — see TaxAllowanceReviewPage.jsx's own header comment.
  describe('payroll-side allowance (hr.employee_tax_allowance, a second table)', () => {
    function blankAllowances(overrides = {}) {
      return {
        spouseAllowance: 0, childAllowance: 0, parentCareAllowance: 0, disabledCareAllowance: 0,
        maternityAllowance: 0, lifeInsuranceAllowance: 0, healthInsuranceAllowance: 0,
        parentHealthInsuranceAllowance: 0, rmfAllowance: 0, ssfAllowance: 0,
        pensionInsuranceAllowance: 0, thaiEsgAllowance: 0, homeLoanInterestAllowance: 0,
        educationDonation: 0, generalDonation: 0, politicalDonation: 0,
        childCount: 0, childCountDouble: 0, disabledCareCount: 0,
        disabilityCardHolder: false, parentCareCount: 0,
        ...overrides,
      };
    }
    function payrollAllowanceRow({ allowances, ...overrides }) {
      return {
        employeeId: null, employeeCode: null, employeeName: null,
        // effectiveMonth 1: always qualifies under resolvePayrollAllowance regardless of which real calendar month the
        // suite happens to run in (see that function's own comment).
        effectiveMonth: 1, documentReference: null, updatedAt: '2026-01-05T00:00:00.000Z',
        verificationStatus: 'VERIFIED', verifiedById: null, verifiedAt: null, verificationDeadline: null,
        allowances: blankAllowances(allowances),
        ...overrides,
      };
    }

    // No declaration, ever — the central "42 rows from personreduce.csv" case.
    const grandfatheredEmployee = { id: 30, code: 'EMP030', nameTh: 'มานี ไม่เคยยื่น', active: true };
    const grandfatheredRow = payrollAllowanceRow({
      employeeId: 30, employeeCode: 'EMP030', employeeName: 'มานี ไม่เคยยื่น',
      allowances: { spouseAllowance: 55000 },
      verificationStatus: 'GRANDFATHERED_UNVERIFIED',
      verificationDeadline: '2026-12-31',
    });

    // No declaration, and the grace period has lapsed — excluded from payroll, but not "nothing".
    const expiredEmployee = { id: 31, code: 'EMP031', nameTh: 'สมศักดิ์ หมดอายุ', active: true };
    const expiredRow = payrollAllowanceRow({
      employeeId: 31, employeeCode: 'EMP031', employeeName: 'สมศักดิ์ หมดอายุ',
      allowances: { lifeInsuranceAllowance: 22000 },
      verificationStatus: 'EXPIRED_UNVERIFIED',
      verificationDeadline: '2020-01-01',
    });

    // No declaration AND no payroll row at all — the control case for "genuinely nothing".
    const emptyEmployee = { id: 32, code: 'EMP032', nameTh: 'วิภา ไม่มีอะไรเลย', active: true };

    // A declaration exists, but the payroll figure disagrees with it (44,000 declared vs 59,000 actually applied)
    // — models a legacy bulk-PUT edit made after the declaration was applied.
    const disagreementEmployee = { id: 40, code: 'EMP040', nameTh: 'อารีย์ ยอดไม่ตรง', active: true };
    const disagreementDeclaration = {
      declarationId: 90, employeeId: 40, employeeCode: 'EMP040', employeeName: 'อารีย์ ยอดไม่ตรง',
      status: 'APPROVED', submittedAt: '2026-02-01T00:00:00.000Z',
      appliedAt: '2026-02-05T00:00:00.000Z', appliedEffectiveMonth: 2,
      expiresOn: '2026-12-31', reviewerNote: null,
      allowances: blankAllowances({ spouseAllowance: 44000 }),
    };
    const disagreementRow = payrollAllowanceRow({
      employeeId: 40, employeeCode: 'EMP040', employeeName: 'อารีย์ ยอดไม่ตรง',
      allowances: { spouseAllowance: 44000, lifeInsuranceAllowance: 15000 },
      verificationStatus: 'GRANDFATHERED_UNVERIFIED',
    });

    // A declaration exists AND the payroll figure agrees with it exactly (33,000 both sides) — the normal,
    // correctly-wired path. The control case proving the disagreement flag is not just always-on.
    const agreementEmployee = { id: 41, code: 'EMP041', nameTh: 'บุญมี ยอดตรงกัน', active: true };
    const agreementDeclaration = {
      declarationId: 91, employeeId: 41, employeeCode: 'EMP041', employeeName: 'บุญมี ยอดตรงกัน',
      status: 'APPROVED', submittedAt: '2026-02-01T00:00:00.000Z',
      appliedAt: '2026-02-05T00:00:00.000Z', appliedEffectiveMonth: 2,
      expiresOn: '2026-12-31', reviewerNote: null,
      allowances: blankAllowances({ spouseAllowance: 33000 }),
    };
    const agreementRow = payrollAllowanceRow({
      employeeId: 41, employeeCode: 'EMP041', employeeName: 'บุญมี ยอดตรงกัน',
      allowances: { spouseAllowance: 33000 },
      verificationStatus: 'VERIFIED',
    });

    // F4 regression case: a declaration exists AND a payroll row exists, but that row is EXPIRED_UNVERIFIED.
    const expiredDisagreesEmployee = { id: 42, code: 'EMP042', nameTh: 'ปรีชา ค่าเก่าหมดอายุ', active: true };
    const expiredDisagreesDeclaration = {
      declarationId: 92, employeeId: 42, employeeCode: 'EMP042', employeeName: 'ปรีชา ค่าเก่าหมดอายุ',
      status: 'APPROVED', submittedAt: '2026-02-01T00:00:00.000Z',
      appliedAt: '2026-02-05T00:00:00.000Z', appliedEffectiveMonth: 2,
      expiresOn: '2026-12-31', reviewerNote: null,
      allowances: blankAllowances({ spouseAllowance: 70000 }),
    };
    const expiredDisagreesRow = payrollAllowanceRow({
      employeeId: 42, employeeCode: 'EMP042', employeeName: 'ปรีชา ค่าเก่าหมดอายุ',
      allowances: { spouseAllowance: 60000, lifeInsuranceAllowance: 25000 }, // sums to 85,000
      verificationStatus: 'EXPIRED_UNVERIFIED',
      verificationDeadline: '2020-01-01',
    });

    const joinEmployees = [
      grandfatheredEmployee, expiredEmployee, emptyEmployee, disagreementEmployee, agreementEmployee,
      expiredDisagreesEmployee,
    ];
    const joinDeclarations = [disagreementDeclaration, agreementDeclaration, expiredDisagreesDeclaration];
    const joinPayrollAllowances = [grandfatheredRow, expiredRow, disagreementRow, agreementRow, expiredDisagreesRow];

    beforeEach(() => {
      api.employees.list.mockResolvedValue({ employees: joinEmployees });
      api.payroll.getTaxAllowanceDeclarations.mockResolvedValue({ items: joinDeclarations });
      api.payroll.getTaxAllowances.mockResolvedValue({ items: joinPayrollAllowances });
    });

    it('flags a disagreement between the declared and actually-applied totals, only where they differ', async () => {
      renderPage({ user: hrUser });
      const disagreeingRow = (await screen.findByText('อารีย์ ยอดไม่ตรง')).closest('tr');
      expect(within(disagreeingRow).getByText('ยอดไม่ตรงกัน')).not.toBeNull();

      const agreeingRow = screen.getByText('บุญมี ยอดตรงกัน').closest('tr');
      expect(within(agreeingRow).queryByText('ยอดไม่ตรงกัน')).toBeNull();

      // The no-declaration rows have nothing to disagree WITH, so the flag never applies there.
      const grandfatheredRowEl = screen.getByText('มานี ไม่เคยยื่น').closest('tr');
      expect(within(grandfatheredRowEl).queryByText('ยอดไม่ตรงกัน')).toBeNull();
    });

    it('CEO: unaffected where it should be -- no new exposure, no employee enumeration', async () => {
      renderPage({ user: ceoUser });
      // CEO still sees the employees who HAVE a declaration, disagreement flag included (same authorized read as the
      // rest of this register -- PayrollController#getTaxAllowances is hasAnyRole('HR','CEO'), identical to the
      // declaration register's own gate).
      const disagreeingRow = (await screen.findByText('อารีย์ ยอดไม่ตรง')).closest('tr');
      expect(within(disagreeingRow).getByText('ยอดไม่ตรงกัน')).not.toBeNull();
      expect(screen.getByText('บุญมี ยอดตรงกัน')).not.toBeNull();
      // ...but never a no-declaration payroll-only row (cannot enumerate employees to synthesize one), and never the
      // new chip, which -- like NONE -- can only ever match such a row.
      expect(screen.queryByText('มานี ไม่เคยยื่น')).toBeNull();
      expect(screen.queryByText('สมศักดิ์ หมดอายุ')).toBeNull();
      expect(screen.queryByRole('button', { name: taxAllowanceStatusShortLabel('GRANDFATHERED_APPLIED') })).toBeNull();
      expect(api.employees.list).not.toHaveBeenCalled();
    });
  });
});
