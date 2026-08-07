import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaxAllowanceReviewPage } from './TaxAllowanceReviewPage.jsx';
import { api } from '../../api/index.js';
import { payrollVerificationInfo, taxAllowanceStatusShortLabel } from './taxAllowanceStatus.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      payroll: {
        getTaxAllowanceCaps: vi.fn(),
        getTaxAllowanceDeclarations: vi.fn(),
        // "register shows what payroll actually uses" (2026-08) — the second source
        // TaxAllowanceReviewPage now joins in alongside getTaxAllowanceDeclarations above.
        getTaxAllowances: vi.fn(),
        listTaxAllowanceAttachments: vi.fn(),
        // F5 cache-invalidation coverage only — approve is the simplest of the five review
        // mutations to drive end to end (no reason/effective-month input needed).
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
    // Empty by default so every PRE-EXISTING test below (none of which is about the payroll join)
    // keeps its original behaviour: resolvePayrollAllowance([], ...) is always null, so
    // taxAllowanceStatusInfo's new branches never fire and every row falls through to the same
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

  // "Register shows what payroll actually uses" (2026-08): GET /api/payroll/tax-allowances
  // (hr.employee_tax_allowance) joined in by employeeId, alongside the declaration register above
  // (hr.tax_allowance_declaration) — see TaxAllowanceReviewPage.jsx's own header comment. Every
  // fixture here uses a DISTINCT declared/actual total (55,000 / 22,000 / 44,000 / 59,000 / 33,000)
  // so `getByText('฿…')` on a formatted total is unambiguous without needing to scope every
  // assertion — only one row in the whole suite can ever produce a given figure.
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
        // effectiveMonth 1: always qualifies under resolvePayrollAllowance regardless of which real
        // calendar month the suite happens to run in (see that function's own comment).
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

    // A declaration exists, but the payroll figure disagrees with it (44,000 declared vs 59,000
    // actually applied) — models a legacy bulk-PUT edit made after the declaration was applied.
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

    // A declaration exists AND the payroll figure agrees with it exactly (33,000 both sides) — the
    // normal, correctly-wired path. The control case proving the disagreement flag is not just
    // always-on.
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

    // F4 regression case: a declaration exists AND a payroll row exists, but that row is
    // EXPIRED_UNVERIFIED -- the two totals plainly differ (70,000 declared vs 85,000 stored-but-
    // expired), yet the disagreement flag must NOT fire anywhere: an expired row is excluded from
    // withholding entirely (PayrollRepository's WHERE clause), so comparing its stale figure against
    // the current declaration answers the wrong question. Before hasAllowanceDisagreement unified
    // the two surfaces, the summary badge already got this right but the expanded panel did not, and
    // nothing caught them disagreeing with EACH OTHER on this exact row.
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

    it('gives a no-declaration employee with a live payroll allowance its own badge, not ยังไม่ได้ยื่น', async () => {
      renderPage({ user: hrUser });
      const row = (await screen.findByText('มานี ไม่เคยยื่น')).closest('tr');
      expect(within(row).getByText('ใช้ค่าลดหย่อนเดิม — ยังไม่ได้ยืนยัน')).not.toBeNull();
      expect(within(row).queryByText('ยังไม่ได้ยื่น')).toBeNull();
    });

    it('keeps a genuinely-empty employee (no declaration, no payroll row) reading ยังไม่ได้ยื่น', async () => {
      renderPage({ user: hrUser });
      const row = (await screen.findByText('วิภา ไม่มีอะไรเลย')).closest('tr');
      expect(within(row).getByText('ยังไม่ได้ยื่น')).not.toBeNull();
    });

    it('distinguishes an EXPIRED_UNVERIFIED payroll row from both the applying and the empty state', async () => {
      renderPage({ user: hrUser });
      const row = (await screen.findByText('สมศักดิ์ หมดอายุ')).closest('tr');
      expect(within(row).getByText(/หมดอายุแล้ว ไม่ใช้กับเงินเดือน/)).not.toBeNull();
      expect(within(row).queryByText('ยังไม่ได้ยื่น')).toBeNull();
      expect(within(row).queryByText('ใช้ค่าลดหย่อนเดิม — ยังไม่ได้ยืนยัน')).toBeNull();
    });

    it('shows the payroll figure and verification state in the expanded row', async () => {
      renderPage({ user: hrUser });
      await screen.findByText('มานี ไม่เคยยื่น');
      fireEvent.click(screen.getByRole('button', { name: 'ดูรายละเอียดค่าลดหย่อนของ มานี ไม่เคยยื่น เทียบเพดาน' }));
      // F1 review remediation: relabelled from "ค่าลดหย่อนที่ระบบเงินเดือนใช้จริง" (what payroll
      // ACTUALLY USES) -- this is the RAW stored total, before PayrollCalculator's caps, so claiming
      // it is "actually used" overstates it. "บันทึกในระบบเงินเดือน" (recorded in the payroll
      // system) makes no claim about the post-clamp deducted amount.
      expect(await screen.findByText('ค่าลดหย่อนที่บันทึกในระบบเงินเดือน')).not.toBeNull();
      expect(screen.queryByText('ค่าลดหย่อนที่ระบบเงินเดือนใช้จริง')).toBeNull();
      expect(screen.getByText('฿55,000.00')).not.toBeNull();
      expect(screen.getByText(payrollVerificationInfo('GRANDFATHERED_UNVERIFIED').label)).not.toBeNull();
      // The relabelled panel must not claim the raw figure IS what payroll deducts, and must point
      // the reader to where the real, post-clamp number lives instead.
      expect(screen.getByText(/ยังไม่ผ่านการตัดเพดาน/)).not.toBeNull();
      expect(screen.getByText(/ค่าลดหย่อนรวม/)).not.toBeNull();
      // No declaration exists for this employee -- the expanded panel says so explicitly rather
      // than silently omitting the declaration grid.
      expect(screen.getByText(/ยังไม่มีแบบแจ้ง ล\.ย\.01 ผ่านระบบนี้/)).not.toBeNull();
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

    it('F4: never flags disagreement against an EXPIRED_UNVERIFIED payroll row, in the summary row OR the expanded panel', async () => {
      renderPage({ user: hrUser });
      const row = (await screen.findByText('ปรีชา ค่าเก่าหมดอายุ')).closest('tr');
      // Summary row: 70,000 declared vs 85,000 stored — a real difference — must not raise the flag.
      expect(within(row).queryByText('ยอดไม่ตรงกัน')).toBeNull();

      fireEvent.click(within(row).getByRole('button', { name: /ดูรายละเอียดค่าลดหย่อนของ ปรีชา ค่าเก่าหมดอายุ/ }));
      expect(await screen.findByText(/หมดอายุ — ไม่ใช้กับเงินเดือนแล้ว/)).not.toBeNull();
      // Expanded panel: same two totals, same non-disagreement — this is the exact case the two
      // surfaces used to answer DIFFERENTLY before hasAllowanceDisagreement unified them.
      expect(screen.queryByText(/ไม่ตรงกับยอดที่บันทึกในระบบเงินเดือน/)).toBeNull();
    });

    it('filters to the unverified-but-applying queue via the new chip', async () => {
      renderPage({ user: hrUser });
      await screen.findByText('มานี ไม่เคยยื่น');
      fireEvent.click(screen.getByRole('button', { name: taxAllowanceStatusShortLabel('GRANDFATHERED_APPLIED') }));
      expect(await screen.findByText('มานี ไม่เคยยื่น')).not.toBeNull();
      expect(screen.queryByText('สมศักดิ์ หมดอายุ')).toBeNull();
      expect(screen.queryByText('วิภา ไม่มีอะไรเลย')).toBeNull();
      expect(screen.queryByText('อารีย์ ยอดไม่ตรง')).toBeNull();
      expect(screen.queryByText('บุญมี ยอดตรงกัน')).toBeNull();
      expect(screen.queryByText('ปรีชา ค่าเก่าหมดอายุ')).toBeNull();
    });

    // F6 review remediation: GRANDFATHERED_EXPIRED had no filter chip while GRANDFATHERED_APPLIED
    // did -- HR needs to find people whose old allowance silently STOPPED applying at least as much
    // as the reverse. `expiredEmployee`/`expiredRow` above (no declaration, EXPIRED_UNVERIFIED) is
    // exactly the row this chip exists to surface.
    it('F6: filters to the lapsed-grandfathered queue via the new GRANDFATHERED_EXPIRED chip', async () => {
      renderPage({ user: hrUser });
      await screen.findByText('มานี ไม่เคยยื่น');
      fireEvent.click(screen.getByRole('button', { name: taxAllowanceStatusShortLabel('GRANDFATHERED_EXPIRED') }));
      expect(await screen.findByText('สมศักดิ์ หมดอายุ')).not.toBeNull();
      expect(screen.queryByText('มานี ไม่เคยยื่น')).toBeNull();
      expect(screen.queryByText('วิภา ไม่มีอะไรเลย')).toBeNull();
      expect(screen.queryByText('อารีย์ ยอดไม่ตรง')).toBeNull();
      expect(screen.queryByText('บุญมี ยอดตรงกัน')).toBeNull();
      expect(screen.queryByText('ปรีชา ค่าเก่าหมดอายุ')).toBeNull();
    });

    it('CEO: unaffected where it should be -- no new exposure, no employee enumeration', async () => {
      renderPage({ user: ceoUser });
      // CEO still sees the employees who HAVE a declaration, disagreement flag included (same
      // authorized read as the rest of this register -- PayrollController#getTaxAllowances is
      // hasAnyRole('HR','CEO'), identical to the declaration register's own gate).
      const disagreeingRow = (await screen.findByText('อารีย์ ยอดไม่ตรง')).closest('tr');
      expect(within(disagreeingRow).getByText('ยอดไม่ตรงกัน')).not.toBeNull();
      expect(screen.getByText('บุญมี ยอดตรงกัน')).not.toBeNull();
      // ...but never a no-declaration payroll-only row (cannot enumerate employees to synthesize
      // one), and never the new chip, which -- like NONE -- can only ever match such a row.
      expect(screen.queryByText('มานี ไม่เคยยื่น')).toBeNull();
      expect(screen.queryByText('สมศักดิ์ หมดอายุ')).toBeNull();
      expect(screen.queryByRole('button', { name: taxAllowanceStatusShortLabel('GRANDFATHERED_APPLIED') })).toBeNull();
      expect(api.employees.list).not.toHaveBeenCalled();
    });
  });

  // F5 review remediation: approving/applying/reverifying a declaration can write
  // hr.employee_tax_allowance (see taxAllowanceStatus.js's comment on
  // markTaxAllowanceVerified/expireTaxAllowanceVerification), which is exactly what
  // `payrollAllowancesQuery` reads -- but that query used to be invalidated by NONE of the five
  // review mutations, only the declaration register was. The panel kept showing pre-action data
  // until a manual reload. Approve is the simplest of the five to drive end to end here.
  describe('cache invalidation after a mutation (F5)', () => {
    it('invalidates the payroll-side allowance query, not just the declaration register, after approving', async () => {
      const invalidateSpy = vi.spyOn(QueryClient.prototype, 'invalidateQueries');
      api.payroll.approveTaxAllowanceDeclaration.mockResolvedValue({ ...pendingDeclaration, status: 'APPROVED' });
      renderPage({ user: hrUser, entry: '/tax-allowance-review?status=' });
      await screen.findByText('สมชาย ใจดี');
      const getTaxAllowancesCallsBeforeMutation = api.payroll.getTaxAllowances.mock.calls.length;

      fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการสำหรับ สมชาย ใจดี' }));
      const menuItems = screen.getAllByRole('menuitem');
      fireEvent.click(menuItems.find((item) => item.textContent.includes('อนุมัติ')));
      fireEvent.click(screen.getByRole('button', { name: 'ยืนยัน' }));

      await waitFor(() => expect(api.payroll.approveTaxAllowanceDeclaration).toHaveBeenCalled());
      // The precise fix: some invalidateQueries call names the taxAllowances namespace.
      await waitFor(() => {
        const invalidatedRoots = invalidateSpy.mock.calls.map(([arg]) => arg?.queryKey?.[0]);
        expect(invalidatedRoots).toContain('taxAllowances');
      });
      // The observable symptom the fix resolves: the panel actually refetches instead of sitting on
      // pre-action data until a manual reload.
      await waitFor(() => {
        expect(api.payroll.getTaxAllowances.mock.calls.length).toBeGreaterThan(getTaxAllowancesCallsBeforeMutation);
      });
      invalidateSpy.mockRestore();
    });
  });
});
