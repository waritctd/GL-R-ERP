import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { PayrollPage } from './PayrollPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    payroll: {
      current: vi.fn(),
      preview: vi.fn(),
      process: vi.fn(),
      exportFile: vi.fn(),
      exportPreviewFile: vi.fn(),
      downloadPayslip: vi.fn(),
      downloadPayslipsZip: vi.fn(),
      distributePayslips: vi.fn(),
      suggestedInputs: vi.fn(),
      // P0 fix (Opus review, 2026-07-30): the tax-treatment matrix section PayrollPage now renders
      // calls this on mount.
      getComponentTaxTreatments: vi.fn(),
      saveComponentTaxTreatments: vi.fn(),
    },
  },
}));

const zeroSpecialPays = Array.from({ length: 8 }, (_, index) => ({
  key: `specialPay${index + 1}`,
  label: `เงินพิเศษ ${index + 1}`,
  amount: 0,
}));

const payrollLine = {
  id: 55,
  employeeId: 1,
  employeeCode: 'GLR-001',
  employeeName: 'พนักงาน ทดสอบ',
  departmentName: 'HR',
  baseSalary: 30000,
  grossTaxableIncome: 30000,
  withholdingTax: 0,
  netPay: 29250,
  grossEarnings: 30000,
  specialPayTotal: 0,
  overtimePay: 0,
  commissionPay: 0,
  totalDeductions: 750,
  socialSecurity: 750,
  ssoWageBase: 15000,
  projectedAnnualIncome: 360000,
  taxAllowanceTotal: 100000,
  specialPays: zeroSpecialPays,
  unpaidLeaveDays: 0,
  studentLoanDeduction: 0,
  legalExecutionDeduction: 0,
  otherPostTaxDeductions: 0,
  // P1 fix (Opus review, 2026-07-30): bonusPay/otherOneOffPay/garnishmentType/
  // customerReturnAlreadyEarned had backend columns and no frontend field at all.
  bonusPay: 0,
  otherOneOffPay: 0,
  customerReturnDeduction: 0,
  garnishmentType: null,
  customerReturnAlreadyEarned: false,
};

function previewPeriod(overrides = {}) {
  return {
    id: null,
    payrollMonth: '2026-07-01',
    status: 'PREVIEW',
    lineCount: 1,
    totalGross: 30000,
    totalDeductions: 750,
    totalNet: 29250,
    totalSocialSecurity: 750,
    totalWithholdingTax: 0,
    lines: [payrollLine],
    ...overrides,
  };
}

function renderPayrollPage() {
  return render(<PayrollPage showToast={vi.fn()} />);
}

// Computed the same way PayrollPage.jsx's own module-level `thisMonth` const is (`new
// Date().toISOString().slice(0, 7)`) -- the page defaults its `month` state to this value, and
// `payload()` submits `` `${month}-01` ``. Used below to pin down that exact "-01" suffix (a
// mutation-testing survivor: changing it to "-02" left the suite green).
const thisMonth = new Date().toISOString().slice(0, 7);

describe('PayrollPage adjustment inputs', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    URL.createObjectURL = vi.fn(() => 'blob:payslip');
    URL.revokeObjectURL = vi.fn();
    api.payroll.current.mockResolvedValue({ period: previewPeriod() });
    api.payroll.preview.mockResolvedValue({ period: previewPeriod() });
    api.payroll.downloadPayslip.mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }));
    api.payroll.exportFile.mockResolvedValue(new Blob(['HPCT'], { type: 'application/octet-stream' }));
    api.payroll.distributePayslips.mockResolvedValue({ periodId: 7, totalLines: 1, alreadySent: 0, queued: 1 });
    api.payroll.suggestedInputs.mockResolvedValue({ payrollMonth: '2026-07-01', suggestions: [] });
    api.payroll.getComponentTaxTreatments.mockResolvedValue({ taxYear: 2026, items: [] });
    api.payroll.saveComponentTaxTreatments.mockResolvedValue({ taxYear: 2026, items: [] });
  });

  it('uses Excel-based UAT defaults and shows a Baht prefix on money fields', async () => {
    render(<PayrollPage showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    const gprs = screen.getByLabelText(/พิเศษ 6 \(ค่า GPRS\)/);
    const allowance = screen.getByLabelText(/พิเศษ 3 \(เบี้ยเลี้ยงประจำ\)/);

    expect(costOfLiving.value).toBe('500');
    expect(gprs.value).toBe('500');
    expect(allowance.value).toBe('');
    expect(costOfLiving.parentElement.querySelector('.currency-input-symbol').textContent).toBe('฿');
  });

  it('allows clearing zero/default amounts and sends them as zeroes', async () => {
    render(<PayrollPage showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    const gprs = screen.getByLabelText(/พิเศษ 6 \(ค่า GPRS\)/);

    fireEvent.change(costOfLiving, { target: { value: '' } });
    fireEvent.change(gprs, { target: { value: '' } });

    expect(costOfLiving.value).toBe('');
    expect(gprs.value).toBe('');

    fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

    await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
    expect(api.payroll.preview.mock.calls[0][0].inputs).toEqual([]);
    // Mutation-testing survivor: `payload().payrollMonth: \`${month}-01\`` had no assertion pinning
    // down the literal "-01" -- changing it to "-02" left the whole suite green. Assert the exact
    // value, not just a shape match.
    expect(api.payroll.preview.mock.calls[0][0].payrollMonth).toBe(`${thisMonth}-01`);
    expect(screen.getByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/).value).toBe('');
  });

  it('includes an input value of exactly 1 -- the boundary of hasPayrollInput\'s `> 0` check', async () => {
    // Mutation-testing survivor: `parsePayrollNumber(input[key]) > 0` could be weakened to `> 1`
    // without failing anything, because no existing test isolated a value of exactly 1 -- it would
    // silently drop a real HR-entered "1" (e.g. 1 special-pay baht, 1 unpaid-leave day) from the
    // submitted payload. Must clear the two UAT-default fields (specialPay1/5, both 500) first --
    // otherwise they mask the mutation by satisfying `.some(...)` on their own regardless of what
    // the field under test is set to.
    renderPayrollPage();

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    const gprs = screen.getByLabelText(/พิเศษ 6 \(ค่า GPRS\)/);
    const allowance = screen.getByLabelText(/พิเศษ 3 \(เบี้ยเลี้ยงประจำ\)/);
    fireEvent.change(costOfLiving, { target: { value: '' } });
    fireEvent.change(gprs, { target: { value: '' } });
    fireEvent.change(allowance, { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

    await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
    const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
    expect(submitted).toBeDefined();
    // เบี้ยเลี้ยงประจำ is slot 3 after the realignment to the accountant's numbering.
    expect(submitted.specialPay3).toBe(1);
  });

  it('downloads a saved payslip for the selected payroll line', async () => {
    const processedPeriod = previewPeriod({ id: 7, status: 'PROCESSED' });
    api.payroll.current.mockResolvedValue({ period: processedPeriod });

    renderPayrollPage();

    expect(screen.queryByRole('columnheader', { name: /เอกสาร/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /Download payslip/i })).toBeNull();

    // Exact match, not just anchored-start: upstream's bulk "ดาวน์โหลดสลิปเงินเดือนทั้งหมด" button
    // (feat/payroll-detail-xlsx-export) also starts with "ดาวน์โหลดสลิป", so a merely-anchored regex
    // matches both this single-payslip button and the bulk one, ambiguously.
    fireEvent.click(await screen.findByRole('button', { name: /^ดาวน์โหลดสลิป$/i }));

    await waitFor(() => expect(api.payroll.downloadPayslip).toHaveBeenCalledWith(7, 55));
  });

  it('right-aligns money, shows satang consistently, and reconciles all visible lines without stranding employee 26', async () => {
    const lines = Array.from({ length: 26 }, (_, index) => ({
      ...payrollLine,
      id: 100 + index,
      employeeId: index + 1,
      employeeCode: `GLR-${String(index + 1).padStart(3, '0')}`,
      employeeName: `พนักงาน ${index + 1}`,
    }));
    api.payroll.current.mockResolvedValue({
      period: previewPeriod({
        lineCount: 26,
        totalGross: 780000,
        totalDeductions: 19500,
        totalNet: 760500,
        totalSocialSecurity: 19500,
        lines,
      }),
    });

    const { container } = renderPayrollPage();

    expect(await screen.findByText('พนักงาน 26')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Download payslip/i })).toBeNull();
    expect(screen.queryByText(/หน้า 1 \//)).toBeNull();
    expect(screen.queryByRole('columnheader', { name: /เงินพิเศษ/i })).toBeNull();
    expect(screen.queryByRole('columnheader', { name: /OT \/ Commission/i })).toBeNull();

    const firstGrossCell = container.querySelector('tbody td[data-label="รายได้"]');
    expect(firstGrossCell.className).toContain('text-right');
    expect(firstGrossCell.className).toContain('payroll-money-cell');
    expect(firstGrossCell.textContent).toBe('฿30,000.00');
    expect(container.querySelector('tbody td[data-label="เงินพิเศษ"]')).toBeNull();
    expect(container.querySelector('tbody td[data-label="OT / Commission"]')).toBeNull();

    const grossHeader = container.querySelector('thead th.payroll-money-cell');
    expect(grossHeader.className).toContain('text-right');

    const totalRow = container.querySelector('tfoot .payroll-total-row');
    expect(totalRow).toBeTruthy();
    expect(totalRow.textContent).toContain('รวมทั้งงวด');
    expect(totalRow.textContent).toContain('26 คน');
    expect(totalRow.textContent).toContain('฿780,000.00');
    expect(totalRow.textContent).toContain('฿760,500.00');
  });

  it('selects a payroll line from the whole row and opens the detail drawer contract', async () => {
    const lines = [
      { ...payrollLine, employeeId: 1, employeeName: 'พนักงาน ก' },
      { ...payrollLine, id: 56, employeeId: 2, employeeCode: 'GLR-002', employeeName: 'พนักงาน ข' },
    ];
    api.payroll.current.mockResolvedValue({
      period: previewPeriod({
        lineCount: 2,
        totalGross: 60000,
        totalDeductions: 1500,
        totalNet: 58500,
        totalSocialSecurity: 1500,
        lines,
      }),
    });

    const { container } = renderPayrollPage();

    const secondRow = (await screen.findByText('พนักงาน ข')).closest('tr');
    expect(secondRow.getAttribute('role')).toBe('row');
    expect(secondRow.getAttribute('tabindex')).toBe('0');
    // Item 4d fix (Opus review, 2026-07-31): `aria-current`, not `aria-selected` -- see
    // DataTable.jsx's own comment on the render for why (`aria-selected` is only valid ARIA inside a
    // `grid`/`treegrid`, and this is a plain table). The visible "เลือกอยู่" badge checked below is
    // unaffected and remains the primary cue.
    expect(secondRow.getAttribute('aria-current')).toBe('false');

    fireEvent.click(secondRow);

    expect(secondRow.getAttribute('aria-current')).toBe('true');
    expect(secondRow.className).toContain('active');
    expect(within(secondRow).getByText('เลือกอยู่')).toBeTruthy();
    expect(container.querySelector('.payroll-detail-panel').className).toContain('is-open');
    expect(container.querySelector('.payroll-detail-panel h2').textContent).toBe('พนักงาน ข');
  });

  // Defect 1 regression guard (Opus review, 2026-07-31): the selected row's `เลือกอยู่` badge used to
  // paint on top of the employee name because `min-w-0` on the name block only allows the flex item
  // to SHRINK -- it does nothing to the text once shrunk, so an overlong name simply overflowed its
  // own box and rendered underneath the badge (a later sibling, so it paints on top). jsdom has no
  // layout engine, so this can't assert the actual pixel overlap is gone -- it pins down the classes
  // that prevent it: `<strong>`/`<small>` must each be `block truncate` so they clip with an ellipsis
  // at their own width, on BOTH the selected row (where the badge is competing for space) and an
  // unselected one (so the fix isn't accidentally conditioned on `selected`).
  it('truncates the employee name/code independently instead of letting them overflow under the เลือกอยู่ badge', async () => {
    const lines = [
      { ...payrollLine, employeeId: 1, employeeName: 'พนักงานที่มีชื่อยาวมากเกินกว่าจะแสดงในคอลัมน์นี้ได้ทั้งหมด' },
      { ...payrollLine, id: 56, employeeId: 2, employeeCode: 'GLR-002', employeeName: 'พนักงาน ข' },
    ];
    api.payroll.current.mockResolvedValue({
      period: previewPeriod({
        lineCount: 2,
        totalGross: 60000,
        totalDeductions: 1500,
        totalNet: 58500,
        totalSocialSecurity: 1500,
        lines,
      }),
    });

    const { container } = renderPayrollPage();

    // Not `screen.findByText(lines[0].employeeName)`: the first line is auto-selected on first
    // render, so its name is ALSO already showing in the always-mounted detail panel's own
    // heading -- an ambiguous match. Grab the rows directly by their DataTable `.data-row` class
    // (see the `openDetailPanel` helper above for the same reasoning) instead.
    await screen.findByRole('button', { name: /คำนวณตัวอย่าง/i });
    const [firstRow, secondRow] = container.querySelectorAll('tr.data-row');
    fireEvent.click(firstRow);
    expect(within(firstRow).getByText('เลือกอยู่')).toBeTruthy();

    const selectedNameBlock = within(firstRow).getByText(lines[0].employeeName);
    expect(selectedNameBlock.tagName).toBe('STRONG');
    expect(selectedNameBlock.className.split(' ')).toEqual(expect.arrayContaining(['block', 'truncate']));
    const selectedCodeBlock = firstRow.querySelector('small');
    expect(selectedCodeBlock.className.split(' ')).toEqual(expect.arrayContaining(['block', 'truncate']));

    // Unselected row: same classes must be present unconditionally, not just when the badge shows up.
    const unselectedNameBlock = within(secondRow).getByText(lines[1].employeeName);
    expect(unselectedNameBlock.className.split(' ')).toEqual(expect.arrayContaining(['block', 'truncate']));

    expect(container.querySelectorAll('.data-row')).toHaveLength(2);
  });

  // B1/B2 fix (Opus review, 2026-07-31): the detail panel has exactly two presentations --
  // >=1440px is a persistent side panel (no dialog semantics, since the rest of the page beside it
  // is not hidden), <1440px is a true overlay dialog (focus trap, Escape, role="dialog"). These
  // tests mock `window.matchMedia` for the `(min-width: 1440px)` query PayrollPage.jsx's
  // `useMediaQuery` reads (see useIsMobile.js) to pin down each mode independently.
  //
  // Item 1 fix (2026-07-31): raised from 1280px -- real-browser measurement showed the side-by-side
  // track didn't actually fit (money columns hidden behind a scrollbar) until ~1400px; see
  // index.css's `payroll-wide` custom-variant comment for the full measurement.
  describe('detail panel: side panel vs. overlay dialog', () => {
    afterEach(() => {
      delete window.matchMedia;
    });

    function mockPanelViewport(isDesktopWidth) {
      window.matchMedia = vi.fn((query) => ({
        matches: query === '(min-width: 1440px)' ? isDesktopWidth : false,
        media: query,
        addEventListener: () => {},
        removeEventListener: () => {},
      }));
    }

    // Not `findByText(payrollLine.employeeName)`: with a single line, that name is ALSO the
    // default `selectedLine` shown in the (always-mounted, just CSS-hidden until opened) detail
    // panel's own heading from the very first render -- an ambiguous match before any click even
    // happens, and the employee code has the same problem. Wait on the always-unique Preview
    // button instead (proof the period finished loading), then grab the row via `.data-row` --
    // DataTable's own class for a genuine data row (see DataTable.jsx) -- and click it directly.
    async function openDetailPanel(container) {
      await screen.findByRole('button', { name: /คำนวณตัวอย่าง/i });
      const row = container.querySelector('tr.data-row');
      fireEvent.click(row);
    }

    it('is a persistent side panel with no dialog semantics at >=1440px', async () => {
      mockPanelViewport(true);
      const { container } = renderPayrollPage();
      await openDetailPanel(container);

      const panel = container.querySelector('.payroll-detail-panel');
      expect(panel.className).toContain('is-open');
      expect(panel.getAttribute('role')).toBeNull();
      expect(panel.getAttribute('aria-modal')).toBeNull();
      expect(panel.getAttribute('aria-labelledby')).toBeNull();
    });

    it('is a labelled dialog overlay below 1440px', async () => {
      mockPanelViewport(false);
      const { container } = renderPayrollPage();
      await openDetailPanel(container);

      const panel = container.querySelector('.payroll-detail-panel');
      expect(panel.getAttribute('role')).toBe('dialog');
      expect(panel.getAttribute('aria-modal')).toBe('true');
      const labelledBy = panel.getAttribute('aria-labelledby');
      expect(labelledBy).toBeTruthy();
      expect(document.getElementById(labelledBy).textContent).toBe(payrollLine.employeeName);
    });

    // Regression guard: the dismiss control used to be hidden by a `.payroll-detail-close`
    // `display` toggle in styles.css, which never applied -- Button.jsx's own `inline-flex`
    // utility sits in `layer(utilities)` and always beats `layer(legacy)`. So the button rendered
    // at every width, including the >=1440px side panel where clicking it is inert (that panel's
    // visibility is not gated by `detailOpen`). Asserting on presence/absence in the DOM, not on
    // computed `display`: jsdom applies no stylesheets, so a CSS-only fix would pass this test
    // while still being dead in the browser.
    it('renders the close button only in the overlay presentation', async () => {
      mockPanelViewport(false);
      const overlay = renderPayrollPage();
      await openDetailPanel(overlay.container);
      expect(overlay.container.querySelector('.payroll-detail-close')).not.toBeNull();
      overlay.unmount();

      mockPanelViewport(true);
      const sidePanel = renderPayrollPage();
      await openDetailPanel(sidePanel.container);
      expect(sidePanel.container.querySelector('.payroll-detail-panel')).not.toBeNull();
      expect(sidePanel.container.querySelector('.payroll-detail-close')).toBeNull();
    });

    it('closes the overlay on Escape', async () => {
      mockPanelViewport(false);
      const { container } = renderPayrollPage();
      await openDetailPanel(container);
      expect(container.querySelector('.payroll-detail-panel').getAttribute('role')).toBe('dialog');

      fireEvent.keyDown(document, { key: 'Escape' });

      const panel = container.querySelector('.payroll-detail-panel');
      expect(panel.className).not.toContain('is-open');
      // Closed also means "no longer a dialog" -- role/aria-modal are gated on detailOpen too.
      expect(panel.getAttribute('role')).toBeNull();
    });

    it('traps Tab inside the overlay panel, wrapping last back to first', async () => {
      mockPanelViewport(false);
      const { container } = renderPayrollPage();
      await openDetailPanel(container);

      const panel = container.querySelector('.payroll-detail-panel');
      const focusable = panel.querySelectorAll(
        'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])',
      );
      expect(focusable.length).toBeGreaterThan(1);
      const first = focusable[0];
      const last = focusable[focusable.length - 1];

      // Opening the overlay moves focus into it immediately (useDialogFocus's initial-focus step).
      expect(document.activeElement).toBe(first);

      last.focus();
      fireEvent.keyDown(document, { key: 'Tab' });
      expect(document.activeElement).toBe(first);
    });

    it('does not trap focus or steal it for the >=1440px persistent side panel', async () => {
      mockPanelViewport(true);
      const { container } = renderPayrollPage();
      const previewButton = await screen.findByRole('button', { name: /คำนวณตัวอย่าง/i });
      previewButton.focus();
      expect(document.activeElement).toBe(previewButton);

      fireEvent.click(container.querySelector('tr.data-row'));

      // A side panel beside the table must never steal focus from what the user was doing --
      // unlike the overlay case above, where opening moves focus into the panel immediately.
      expect(document.activeElement).toBe(previewButton);
    });
  });

  it('ties footer sums to the hero totals exactly, including satang', async () => {
    const lines = [
      {
        ...payrollLine,
        id: 101,
        employeeId: 1,
        employeeName: 'พนักงาน ก',
        grossEarnings: 100.10,
        totalDeductions: 0.05,
        netPay: 100.05,
      },
      {
        ...payrollLine,
        id: 102,
        employeeId: 2,
        employeeCode: 'GLR-002',
        employeeName: 'พนักงาน ข',
        grossEarnings: 200.20,
        totalDeductions: 0.10,
        netPay: 200.10,
      },
    ];
    api.payroll.current.mockResolvedValue({
      period: previewPeriod({
        lineCount: 2,
        totalGross: 300.30,
        totalDeductions: 0.15,
        totalNet: 300.15,
        totalSocialSecurity: 0.15,
        lines,
      }),
    });

    const { container } = renderPayrollPage();

    expect(await screen.findByText('พนักงาน ข')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();

    const totalRow = container.querySelector('tfoot .payroll-total-row');
    expect(totalRow.textContent).toContain('฿300.30');
    expect(totalRow.textContent).toContain('฿0.15');
    expect(totalRow.textContent).toContain('฿300.15');
    expect(totalRow.textContent).toContain('ตรงกับรายได้รวม');
    expect(totalRow.textContent).toContain('ตรงกับเงินหักรวม');
    expect(totalRow.textContent).toContain('ตรงกับยอดโอนสุทธิ');

    const mobileSummary = container.querySelector('.payroll-mobile-summary-row');
    expect(mobileSummary.textContent).toContain('รายได้');
    expect(mobileSummary.textContent).toContain('หัก');
    expect(mobileSummary.textContent).toContain('สุทธิ');
    expect(mobileSummary.textContent).toContain('฿300.30');
  });

  it('raises a visible reconciliation alert when a line sum does not match the hero total', async () => {
    const lines = [
      {
        ...payrollLine,
        grossEarnings: 300.30,
        totalDeductions: 0.15,
        netPay: 300.15,
      },
    ];
    api.payroll.current.mockResolvedValue({
      period: previewPeriod({
        totalGross: 300.31,
        totalDeductions: 0.15,
        totalNet: 300.15,
        totalSocialSecurity: 0.15,
        lines,
      }),
    });

    const { container } = renderPayrollPage();

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('ยอดรวมไม่ตรงกับสรุปด้านบน');
    expect(alert.textContent).toContain('รายได้');
    expect(alert.textContent).toContain('฿300.30');
    expect(alert.textContent).toContain('฿300.31');

    const totalRow = container.querySelector('tfoot .payroll-total-row');
    expect(totalRow.textContent).toContain('ไม่ตรงกับรายได้รวม: ฿300.31');
  });

  it('starts payslip email distribution for a processed payroll period', async () => {
    api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: 7, status: 'PROCESSED' }) });

    renderPayrollPage();

    fireEvent.click(await screen.findByRole('button', { name: /ส่งอีเมลสลิปเงินเดือน/i }));

    await waitFor(() => expect(api.payroll.distributePayslips).toHaveBeenCalledWith(7));
  });

  it('Refresh recomputes a processed month live (Preview), never committing', async () => {
    // A month that was already Processed loads from its saved snapshot (api.payroll.current) — which
    // freezes commission/OT/etc. from when it ran. Clicking รีเฟรช must pull the latest via a live
    // recompute (api.payroll.preview) and must NOT process/commit the month.
    api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: 7, status: 'PROCESSED' }) });

    renderPayrollPage();

    const refreshButton = await screen.findByRole('button', { name: /รีเฟรช/ });
    expect(api.payroll.preview).not.toHaveBeenCalled();

    fireEvent.click(refreshButton);

    await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
    expect(api.payroll.process).not.toHaveBeenCalled();
  });

  // Leave -> payroll unpaid-day deduction (2026-07-23). The unpaidLeaveDays field lives inside the
  // "รายการหักรายบุคคล" CollapsibleSection, which defaults to collapsed -- and CollapsibleSection
  // unmounts its body entirely (not CSS-hidden) while collapsed, so every test here must expand it
  // before the field exists in the DOM.
  async function expandUnpaidLeaveSection() {
    fireEvent.click(await screen.findByRole('button', { name: /รายการหักรายบุคคล/ }));
    // The `selector: 'input'` filter is needed because the field's InfoTip button carries the same
    // accessible name ("วันลาไม่รับค่าจ้าง") as an aria-label -- without it, getByLabelText matches
    // both the input and the InfoTip trigger and throws "Found multiple elements".
    return screen.findByLabelText(/วันลาไม่รับค่าจ้าง/, { selector: 'input' });
  }

  describe('leave-derived unpaidLeaveDays suggestion', () => {
    it('pre-fills unpaidLeaveDays from the leave-derived suggestion on a fresh PREVIEW run', async () => {
      api.payroll.suggestedInputs.mockResolvedValue({
        payrollMonth: '2026-07-01',
        suggestions: [{ employeeId: 1, unpaidLeaveDays: 1.5, pendingUnpaidLeaveCorrectionDays: 0 }],
      });

      renderPayrollPage();

      const unpaidLeaveDays = await expandUnpaidLeaveSection();
      expect(unpaidLeaveDays.value).toBe('1.5');
    });

    it('lets HR override the pre-filled unpaidLeaveDays suggestion before submitting', async () => {
      api.payroll.suggestedInputs.mockResolvedValue({
        payrollMonth: '2026-07-01',
        suggestions: [{ employeeId: 1, unpaidLeaveDays: 1.5, pendingUnpaidLeaveCorrectionDays: 0 }],
      });

      renderPayrollPage();

      const unpaidLeaveDays = await expandUnpaidLeaveSection();
      expect(unpaidLeaveDays.value).toBe('1.5');

      fireEvent.change(unpaidLeaveDays, { target: { value: '2' } });
      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submittedInput = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submittedInput.unpaidLeaveDays).toBe(2);
    });

    it('a real (already-persisted) line value on a PROCESSED period wins over any suggestion', async () => {
      api.payroll.current.mockResolvedValue({
        period: previewPeriod({ id: 7, status: 'PROCESSED', lines: [{ ...payrollLine, unpaidLeaveDays: 3 }] }),
      });
      api.payroll.suggestedInputs.mockResolvedValue({
        payrollMonth: '2026-07-01',
        suggestions: [{ employeeId: 1, unpaidLeaveDays: 1.5, pendingUnpaidLeaveCorrectionDays: 0 }],
      });

      renderPayrollPage();

      const unpaidLeaveDays = await expandUnpaidLeaveSection();
      expect(unpaidLeaveDays.value).toBe('3');
      // PROCESSED periods never fetch suggestions at all (see load()'s guard).
      expect(api.payroll.suggestedInputs).not.toHaveBeenCalled();
    });

    it('shows a hint for an unresolved cancel-after-close correction credit, without changing the field value', async () => {
      api.payroll.suggestedInputs.mockResolvedValue({
        payrollMonth: '2026-07-01',
        suggestions: [{ employeeId: 1, unpaidLeaveDays: 0, pendingUnpaidLeaveCorrectionDays: 1 }],
      });

      renderPayrollPage();

      const unpaidLeaveDays = await expandUnpaidLeaveSection();
      expect(unpaidLeaveDays.value).toBe('');
      // findByText throws (failing the test) if the hint isn't present -- no jest-dom matchers are
      // set up in this project's vitest config, so there's nothing to chain here.
      await screen.findByText(/เครดิตวันลาไม่รับค่าจ้างค้างคืน/);
    });

    // Cancel-after-close reversal, AUTO-REFUND (2026-07-23): the backend now applies the correction
    // itself (PayrollService#preview/#process) rather than only surfacing a "please adjust manually"
    // suggestion -- these two fields (leaveRefundDays/leaveDeductionRefund) live on the CALCULATED
    // line the API returns, not on the suggestion or the HR-editable adjustment form.
    it('shows the auto-applied refund on a line that already includes one, and drops the stale manual-entry hint', async () => {
      api.payroll.current.mockResolvedValue({
        period: previewPeriod({
          lines: [{ ...payrollLine, leaveRefundDays: 1, leaveDeductionRefund: 1000 }],
        }),
      });
      api.payroll.suggestedInputs.mockResolvedValue({
        payrollMonth: '2026-07-01',
        suggestions: [{ employeeId: 1, unpaidLeaveDays: 0, pendingUnpaidLeaveCorrectionDays: 1 }],
      });

      renderPayrollPage();
      await expandUnpaidLeaveSection();

      // The new auto-applied hint appears...
      await screen.findByText(/ระบบคืนเครดิตวันลาไม่รับค่าจ้างค้างคืน 1 วัน/);
      // ...and the old "please adjust manually, not automatic yet" wording is gone -- that claim is no
      // longer true and would risk HR double-entering the credit into unpaidLeaveDays by hand.
      expect(screen.queryByText(/กรุณาปรับตัวเลขด้านบนด้วยตนเอง/)).toBeNull();
      // The breakdown panel also shows the refund amount as its own line.
      await screen.findByText(/คืนเครดิตวันลาไม่รับค่าจ้าง \(1 วัน\)/);
    });

    it('does not show any refund hint when there is no refund on the line and no pending correction', async () => {
      renderPayrollPage();
      await expandUnpaidLeaveSection();

      expect(screen.queryByText(/เครดิตวันลาไม่รับค่าจ้างค้างคืน/)).toBeNull();
      expect(screen.queryByText(/คืนเครดิตวันลาไม่รับค่าจ้าง \(/)).toBeNull();
    });
  });

  describe('per-run withholding-tax override (V88)', () => {
    async function openOverrideInput() {
      fireEvent.click(await screen.findByRole('button', { name: /รายการหักรายบุคคล/ }));
      // The field's InfoTip trigger shares the label's accessible name, so scope to the input.
      return screen.findByLabelText(/ภาษีหัก ณ ที่จ่าย \(กำหนดเอง\)/, { selector: 'input' });
    }

    it('submits a typed per-run override amount', async () => {
      renderPayrollPage();
      const override = await openOverrideInput();

      fireEvent.change(override, { target: { value: '250' } });
      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted.withholdingTaxOverride).toBe(250);
    });

    it('submits an override of 0 (withhold nothing) rather than dropping the input', async () => {
      renderPayrollPage();
      const override = await openOverrideInput();

      fireEvent.change(override, { target: { value: '0' } });
      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted).toBeDefined();
      expect(submitted.withholdingTaxOverride).toBe(0);
    });

    it('sends a blank override as null (compute/standing applies)', async () => {
      renderPayrollPage();
      const override = await openOverrideInput();
      expect(override.value).toBe('');

      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      // The line is still submitted (it carries the UAT default special pays), but the untyped
      // override is null -- never 0 -- so the server computes/uses the standing value.
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted).toBeDefined();
      expect(submitted.withholdingTaxOverride).toBeNull();
    });

    it('pre-fills the per-run override carried from suggested-inputs, incl. a carried 0', async () => {
      api.payroll.suggestedInputs.mockResolvedValue({
        payrollMonth: '2026-07-01',
        suggestions: [{ employeeId: 1, withholdingTaxOverride: 0 }],
      });

      renderPayrollPage();
      const override = await openOverrideInput();
      // A carried 0 is a real per-run override and must pre-fill as "0", not blank.
      expect(override.value).toBe('0');
    });
  });

  // F2 (Opus review, 2026-07-30): HR could type a per-diem amount with no basis selector at all --
  // Preview always succeeded (it never writes a row), then Process 500'd on V97's
  // chk_payroll_line_per_diem_basis_present CHECK. Fixed by adding the missing amount inputs AND the
  // basis selector (shown only once an amount is entered), plus a server-side 400 as a second line of
  // defence (PayrollService#calculateLine).
  describe('per-diem basis selector (V97 / F2)', () => {
    async function openPerDiemSection() {
      fireEvent.click(await screen.findByRole('button', { name: /ค่าอาหาร \/ เบี้ยเลี้ยง/ }));
      return screen.findByLabelText(/เบี้ยเลี้ยง — ส่วนเกิน \(เสียภาษี\)/, { selector: 'input' });
    }

    it('hides the basis selector until a per-diem amount is entered', async () => {
      renderPayrollPage();
      await openPerDiemSection();

      expect(screen.queryByLabelText(/ฐานเบี้ยเลี้ยง \(มาตรา 42\)/)).toBeNull();
    });

    it('shows the basis selector once a taxable per-diem amount is entered, and hides it again when cleared', async () => {
      renderPayrollPage();
      const taxable = await openPerDiemSection();

      fireEvent.change(taxable, { target: { value: '300' } });
      const basis = await screen.findByLabelText(/ฐานเบี้ยเลี้ยง \(มาตรา 42\)/, { selector: 'select' });
      expect(basis).toBeTruthy();

      fireEvent.change(taxable, { target: { value: '' } });
      await waitFor(() => expect(screen.queryByLabelText(/ฐานเบี้ยเลี้ยง \(มาตรา 42\)/)).toBeNull());
    });

    it('shows the basis selector for the exempt amount too, not only the taxable one', async () => {
      renderPayrollPage();
      fireEvent.click(await screen.findByRole('button', { name: /ค่าอาหาร \/ เบี้ยเลี้ยง/ }));
      const exempt = await screen.findByLabelText(/เบี้ยเลี้ยง — ส่วนที่ยกเว้นภาษี \(ม\.42\)/, { selector: 'input' });

      fireEvent.change(exempt, { target: { value: '700' } });
      expect(await screen.findByLabelText(/ฐานเบี้ยเลี้ยง \(มาตรา 42\)/, { selector: 'select' })).toBeTruthy();
    });

    it('submits the chosen per-diem basis verbatim, matching the backend PerDiemBasis enum', async () => {
      renderPayrollPage();
      const taxable = await openPerDiemSection();
      fireEvent.change(taxable, { target: { value: '300' } });
      const basis = await screen.findByLabelText(/ฐานเบี้ยเลี้ยง \(มาตรา 42\)/, { selector: 'select' });
      fireEvent.change(basis, { target: { value: 'REIMBURSED_S42_1' } });

      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted.perDiemTaxable).toBe(300);
      expect(submitted.perDiemBasis).toBe('REIMBURSED_S42_1');
    });

    it('sends perDiemBasis as null, never a blank string, when no per-diem amount is entered', async () => {
      renderPayrollPage();
      await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted).toBeDefined();
      expect(submitted.perDiemBasis).toBeNull();
    });

    // The backend now rejects this before the INSERT (see PayrollService#calculateLine's F2 fix) with
    // a 400 naming the employee and the missing basis, instead of the DB CHECK constraint turning it
    // into a bare 500. This proves the page surfaces that rejection cleanly rather than swallowing it.
    it('surfaces the backend rejection cleanly when Process is attempted without a chosen basis', async () => {
      const showToast = vi.fn();
      api.payroll.process.mockRejectedValue(new Error(
        'พนักงาน GLR-001 พนักงาน ทดสอบ มีการจ่ายเบี้ยเลี้ยง (เบี้ยเลี้ยง ตจว/ตปท) แต่ไม่ได้ระบุฐานตามมาตรา 42'
        + ' (เหมาจ่ายตามอัตราราชการ มาตรา 42(2) หรือจ่ายจริงตามหน้าที่ มาตรา 42(1)) กรุณาเลือกฐานก่อนประมวลผลเงินเดือน',
      ));
      render(<PayrollPage showToast={showToast} />);
      const taxable = await openPerDiemSection();
      fireEvent.change(taxable, { target: { value: '300' } });

      fireEvent.click(screen.getByRole('button', { name: /ประมวลผลเงินเดือน/i }));
      fireEvent.click(await screen.findByRole('button', { name: /ยืนยันประมวลผล/i }));

      await waitFor(() => expect(api.payroll.process).toHaveBeenCalledTimes(1));
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', expect.stringContaining('กรุณาเลือกฐานก่อนประมวลผลเงินเดือน')));
    });
  });

  it('generates the selected statutory export file with the chosen pay date', async () => {
    api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: 7, status: 'PROCESSED' }) });

    renderPayrollPage();

    // Pick PND1 from the dropdown, then download.
    const kindSelect = await screen.findByLabelText('ประเภทไฟล์ที่จะสร้าง');
    fireEvent.change(kindSelect, { target: { value: 'pnd1' } });
    fireEvent.click(screen.getByRole('button', { name: /ดาวน์โหลดไฟล์/ }));

    // Pay date defaults to the 26th of the current payroll month (kept month-agnostic here).
    await waitFor(() => expect(api.payroll.exportFile)
      .toHaveBeenCalledWith(7, 'pnd1', expect.stringMatching(/^\d{4}-\d{2}-26$/)));
  });

  // The detailed payroll xlsx export -- reachable from the same dropdown/button as the three
  // statutory kinds, per PayrollController#export's slug ∈ {kbank,pnd1,sso,payroll-detail}.
  it('reaches the detailed payroll xlsx export from the same dropdown and downloads it as .xlsx', async () => {
    api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: 7, status: 'PROCESSED' }) });
    api.payroll.exportFile.mockResolvedValue(new Blob(['PK'], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    }));
    const showToast = vi.fn();

    render(<PayrollPage showToast={showToast} />);

    const kindSelect = await screen.findByLabelText('ประเภทไฟล์ที่จะสร้าง');
    // getByRole throws (failing the test) if the option isn't present -- no jest-dom matchers here.
    within(kindSelect).getByRole('option', { name: /รายละเอียดเงินเดือนรายเดือน/ });

    fireEvent.change(kindSelect, { target: { value: 'payroll-detail' } });
    fireEvent.click(screen.getByRole('button', { name: /ดาวน์โหลดไฟล์/ }));

    await waitFor(() => expect(api.payroll.exportFile)
      .toHaveBeenCalledWith(7, 'payroll-detail', expect.stringMatching(/^\d{4}-\d{2}-26$/)));
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', expect.stringContaining('รายละเอียดเงินเดือนรายเดือน')));
  });

  // Owner requirement (2026-07-30): "July 2026 is live and still unprocessed... HR's whole reason
  // to want this file is to review the month BEFORE committing it." Unlike KBank/PND1/SSO, the
  // detail export button must work with NO persisted period (id === null) -- it POSTs the same
  // payrollMonth/inputs payload Preview/Process already send, so the workbook always matches
  // whatever the on-screen preview shows for those inputs.
  it('downloads the detail xlsx for an unprocessed preview (period.id === null) via the preview-export endpoint', async () => {
    api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: null, status: 'PREVIEW', lineCount: 1 }) });
    api.payroll.exportPreviewFile.mockResolvedValue(new Blob(['PK'], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    }));
    const showToast = vi.fn();

    render(<PayrollPage showToast={showToast} />);

    const kindSelect = await screen.findByLabelText('ประเภทไฟล์ที่จะสร้าง');
    expect(kindSelect.disabled).toBe(false); // reachable even though period.id is null
    fireEvent.change(kindSelect, { target: { value: 'payroll-detail' } });
    const downloadButton = screen.getByRole('button', { name: /ดาวน์โหลดไฟล์/ });
    expect(downloadButton.disabled).toBe(false);
    fireEvent.click(downloadButton);

    await waitFor(() => expect(api.payroll.exportPreviewFile).toHaveBeenCalledTimes(1));
    const [payloadArg, kindArg] = api.payroll.exportPreviewFile.mock.calls[0];
    expect(kindArg).toBe('payroll-detail');
    expect(payloadArg.payrollMonth).toBe(`${thisMonth}-01`);
    expect(api.payroll.exportFile).not.toHaveBeenCalled();
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', expect.stringContaining('รายละเอียดเงินเดือนรายเดือน')));
  });

  it('keeps the three statutory export kinds gated on a real period id even though payroll-detail is not', async () => {
    api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: null, status: 'PREVIEW', lineCount: 1 }) });

    render(<PayrollPage showToast={vi.fn()} />);

    const kindSelect = await screen.findByLabelText('ประเภทไฟล์ที่จะสร้าง');
    fireEvent.change(kindSelect, { target: { value: 'kbank' } });

    expect(screen.getByRole('button', { name: /ดาวน์โหลดไฟล์/ }).disabled).toBe(true);
  });

  // Bulk payslip ZIP (owner requirement, 2026-07-30): "hr should be able to bulk download payslip
  // before emailing to all employee for recheck". Sits next to "ส่งอีเมลสลิปเงินเดือน" so the
  // review-then-send order is obvious, and -- unlike the detail xlsx export -- only works for a
  // genuinely PROCESSED period.
  describe('Bulk payslip ZIP download', () => {
    it('renders next to the email button and hits the payslips.zip endpoint for a processed period', async () => {
      api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: 7, status: 'PROCESSED' }) });
      api.payroll.downloadPayslipsZip.mockResolvedValue(new Blob(['PK'], { type: 'application/zip' }));
      const showToast = vi.fn();

      render(<PayrollPage showToast={showToast} />);

      const zipButton = await screen.findByRole('button', { name: /ดาวน์โหลดสลิปเงินเดือนทั้งหมด/ });
      expect(zipButton.disabled).toBe(false);
      fireEvent.click(zipButton);

      await waitFor(() => expect(api.payroll.downloadPayslipsZip).toHaveBeenCalledWith(7));
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', expect.stringContaining('ดาวน์โหลดสลิปเงินเดือนทั้งหมด')));
    });

    it('is disabled for an unprocessed period (no periodId yet)', async () => {
      api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: null, status: 'PREVIEW' }) });

      render(<PayrollPage showToast={vi.fn()} />);

      const zipButton = await screen.findByRole('button', { name: /ดาวน์โหลดสลิปเงินเดือนทั้งหมด/ });
      expect(zipButton.disabled).toBe(true);
    });

    it('is disabled for a VOID period even though it has a real periodId', async () => {
      api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: 1, status: 'VOID' }) });

      render(<PayrollPage showToast={vi.fn()} />);

      const zipButton = await screen.findByRole('button', { name: /ดาวน์โหลดสลิปเงินเดือนทั้งหมด/ });
      expect(zipButton.disabled).toBe(true);
    });
  });

  // F1: PayrollService#process (backend) doesn't need a period id -- it recomputes from
  // findActiveEmployees() and commits regardless. Opening the confirm dialog and processing while
  // the header reads "พนักงาน 0 คน" would silently zero out every HR-entered value and commit an
  // irreversible PROCESSED status. See the `emptyPeriod` comment in PayrollPage.jsx.
  describe('Process Payroll guard against an empty period (F1)', () => {
    it('disables Process Payroll when the loaded period has zero lines (lineCount: 0)', async () => {
      api.payroll.current.mockResolvedValue({ period: previewPeriod({ lineCount: 0, lines: [] }) });

      renderPayrollPage();

      const processButton = await screen.findByRole('button', { name: /ประมวลผลเงินเดือน/i });
      await waitFor(() => expect(processButton.disabled).toBe(true));
    });

    // Regression guard against "simplifying" the check to `!period?.id`: PayrollService#preview
    // returns id=null for every month that has never been processed, so id===null does NOT imply
    // an empty period -- it's the normal shape of a fresh, never-run month with real lines to
    // process. Guarding on id instead of lineCount would make first-time processing impossible.
    it('enables Process Payroll for a fresh (never-processed) period with lineCount > 0 and id === null', async () => {
      api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: null, lineCount: 1 }) });

      renderPayrollPage();

      const processButton = await screen.findByRole('button', { name: /ประมวลผลเงินเดือน/i });
      await waitFor(() => expect(processButton.disabled).toBe(false));
    });

    it('shows an accessible block reason when disabled for an empty period, not the mobile-only message', async () => {
      api.payroll.current.mockResolvedValue({ period: previewPeriod({ lineCount: 0, lines: [] }) });

      renderPayrollPage();

      const reason = await screen.findByText(/ยังไม่มีพนักงานในรอบเงินเดือนนี้/);
      expect(reason.getAttribute('role')).toBe('note');
      const processButton = screen.getByRole('button', { name: /ประมวลผลเงินเดือน/i });
      expect(processButton.getAttribute('aria-describedby')).toBe(reason.id);
    });

    it('keeps Preview visually primary and demotes Process into its own status region', async () => {
      renderPayrollPage();

      const previewButton = await screen.findByRole('button', { name: /คำนวณตัวอย่าง/i });
      const processButton = screen.getByRole('button', { name: /ประมวลผลเงินเดือน/i });
      const processRegion = processButton.closest('.payroll-process-region');

      expect(previewButton.className).toContain('bg-primary');
      expect(processButton.className).toContain('text-danger');
      expect(processButton.className).not.toContain('bg-primary');
      expect(processRegion).toBeTruthy();
      expect(processRegion.textContent).toContain('ปิดรอบเงินเดือน');
      expect(processRegion.textContent).toContain('ตัวอย่าง');
      expect(processRegion.textContent).toContain('1 คน');
    });

    it('names employee count and the OT/no-unprocess consequence in the Process confirmation', async () => {
      api.payroll.current.mockResolvedValue({ period: previewPeriod({ lineCount: 1 }) });

      renderPayrollPage();

      fireEvent.click(await screen.findByRole('button', { name: /ประมวลผลเงินเดือน/i }));

      const dialog = await screen.findByRole('dialog', { name: /ประมวลผลเงินเดือน/i });
      expect(dialog.textContent).toContain('พนักงาน 1 คน');
      expect(dialog.textContent).toContain('การอนุมัติ OT ของเดือนนี้จะปิดทันที');
      expect(dialog.textContent).toContain('ไม่มีทางยกเลิกการประมวลผล');
    });
  });

  // P1 fix (Opus review, 2026-07-30): bonusPay/otherOneOffPay/garnishmentType/
  // customerReturnAlreadyEarned existed end-to-end on the backend with no input anywhere on this page.
  describe('one-off pay, garnishment type, and customer-return-earned flag (P1)', () => {
    it('submits bonusPay and otherOneOffPay from the "เงินก้อนพิเศษ" section', async () => {
      renderPayrollPage();
      fireEvent.click(await screen.findByRole('button', { name: /เงินก้อนพิเศษ \(จ่ายครั้งเดียว\)/ }));
      const bonus = await screen.findByLabelText(/เงินโบนัส/, { selector: 'input' });
      const oneOff = await screen.findByLabelText(/เงินก้อนอื่นๆ \(ครั้งเดียว\)/, { selector: 'input' });

      fireEvent.change(bonus, { target: { value: '20000' } });
      fireEvent.change(oneOff, { target: { value: '1500' } });
      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted.bonusPay).toBe(20000);
      expect(submitted.otherOneOffPay).toBe(1500);
    });

    async function openIndividualDeductionsSection() {
      fireEvent.click(await screen.findByRole('button', { name: /รายการหักรายบุคคล/ }));
      return screen.findByLabelText(/หักอายัดกรมบังคับคดี/, { selector: 'input' });
    }

    it('hides the garnishment-type selector until an amount is entered under หักอายัดกรมบังคับคดี', async () => {
      renderPayrollPage();
      await openIndividualDeductionsSection();

      expect(screen.queryByLabelText(/ประเภทเงินที่ถูกอายัด/)).toBeNull();
    });

    it('shows the garnishment-type selector once an amount is entered, and submits it verbatim', async () => {
      renderPayrollPage();
      const legalExecution = await openIndividualDeductionsSection();

      fireEvent.change(legalExecution, { target: { value: '5000' } });
      const garnishmentType = await screen.findByLabelText(/ประเภทเงินที่ถูกอายัด/, { selector: 'select' });
      fireEvent.change(garnishmentType, { target: { value: 'OVERTIME_OR_DILIGENCE' } });

      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted.legalExecutionDeduction).toBe(5000);
      expect(submitted.garnishmentType).toBe('OVERTIME_OR_DILIGENCE');
    });

    it('sends garnishmentType as null, never a blank string, when no garnishment amount is entered', async () => {
      renderPayrollPage();
      await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted).toBeDefined();
      expect(submitted.garnishmentType).toBeNull();
    });

    it('hides the customer-return-earned checkbox until a return amount is entered, defaults to false', async () => {
      renderPayrollPage();
      fireEvent.click(await screen.findByRole('button', { name: /รายการหักก่อนภาษี/, expanded: false }));
      const customerReturn = await screen.findByLabelText(/หักลูกค้าคืนสินค้า/, { selector: 'input' });

      expect(screen.queryByLabelText(/คอมมิชชันนี้รับไปแล้ว/)).toBeNull();

      fireEvent.change(customerReturn, { target: { value: '3000' } });
      const alreadyEarned = await screen.findByLabelText(/คอมมิชชันนี้รับไปแล้ว/, { selector: 'input' });
      expect(alreadyEarned.checked).toBe(false);

      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));
      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted.customerReturnDeduction).toBe(3000);
      expect(submitted.customerReturnAlreadyEarned).toBe(false);
    });

    it('submits customerReturnAlreadyEarned = true once HR ticks the box', async () => {
      renderPayrollPage();
      fireEvent.click(await screen.findByRole('button', { name: /รายการหักก่อนภาษี/, expanded: false }));
      const customerReturn = await screen.findByLabelText(/หักลูกค้าคืนสินค้า/, { selector: 'input' });
      fireEvent.change(customerReturn, { target: { value: '3000' } });
      const alreadyEarned = await screen.findByLabelText(/คอมมิชชันนี้รับไปแล้ว/, { selector: 'input' });

      fireEvent.click(alreadyEarned);
      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted.customerReturnAlreadyEarned).toBe(true);
    });

    // D1 fix (fourth reachability audit, 2026-07-30): a processed line's customerReturnDeduction is
    // the POST-TAX bookkeeping figure -- 0 in the unearned path, where the amount was instead netted
    // pre-tax out of commission. Hydrating the form from THAT field (the pre-fix bug) silently wiped
    // both the amount and the checkbox (which only renders once the field is > 0) on every reload.
    // customerReturnRequested always carries what HR actually typed, regardless of the earned flag.
    it('hydrates the entered amount (and keeps the checkbox visible) from customerReturnRequested on reload, not the zeroed post-tax figure', async () => {
      api.payroll.current.mockResolvedValue({
        period: previewPeriod({
          id: 7,
          status: 'PROCESSED',
          lines: [{
            ...payrollLine,
            customerReturnDeduction: 0, // the unearned path's post-tax bookkeeping figure -- always 0
            customerReturnRequested: 3000, // what HR actually typed
            customerReturnAlreadyEarned: false,
          }],
        }),
      });

      renderPayrollPage();
      fireEvent.click(await screen.findByRole('button', { name: /รายการหักก่อนภาษี/, expanded: false }));
      const customerReturn = await screen.findByLabelText(/หักลูกค้าคืนสินค้า/, { selector: 'input' });

      expect(customerReturn.value).toBe('3000');
      // The checkbox's render gate is `> 0` on this same field -- if hydration had read the zeroed
      // customerReturnDeduction instead, findByLabelText below would reject (no such element) rather
      // than resolve.
      const alreadyEarned = await screen.findByLabelText(/คอมมิชชันนี้รับไปแล้ว/, { selector: 'input' });
      expect(alreadyEarned.checked).toBe(false);
    });
  });

  // P0 fix (Opus review, 2026-07-30): the withholding-tax classification matrix screen. Without a
  // real screen, HR had no way to satisfy PayrollCalculator#calculateClassified's classification
  // gate for any component beyond the V100 backfill defaults.
  describe('withholding-tax classification matrix (P0)', () => {
    it('loads the matrix on mount and shows an unclassified count per employee', async () => {
      api.payroll.getComponentTaxTreatments.mockResolvedValue({
        taxYear: 2026,
        items: [{ employeeId: 1, employeeCode: 'GLR-001', employeeName: 'พนักงาน ทดสอบ', byComponent: {} }],
      });
      renderPayrollPage();

      fireEvent.click(await screen.findByRole('button', { name: /การจัดประเภทภาษีหัก ณ ที่จ่าย/ }));
      await waitFor(() => expect(api.payroll.getComponentTaxTreatments).toHaveBeenCalledWith(2026));
      expect(await screen.findByRole('button', { name: /พนักงาน ทดสอบ \(GLR-001\)/ })).toBeTruthy();
    });

    it('saves an edited classification and reflects the server response', async () => {
      api.payroll.getComponentTaxTreatments.mockResolvedValue({
        taxYear: 2026,
        items: [{ employeeId: 1, employeeCode: 'GLR-001', employeeName: 'พนักงาน ทดสอบ', byComponent: {} }],
      });
      api.payroll.saveComponentTaxTreatments.mockResolvedValue({
        taxYear: 2026,
        items: [{
          employeeId: 1, employeeCode: 'GLR-001', employeeName: 'พนักงาน ทดสอบ',
          byComponent: { SPECIAL_PAY_1: 'REGULAR_REPROJECT' },
        }],
      });
      renderPayrollPage();

      fireEvent.click(await screen.findByRole('button', { name: /การจัดประเภทภาษีหัก ณ ที่จ่าย/ }));
      fireEvent.click(await screen.findByRole('button', { name: /พนักงาน ทดสอบ \(GLR-001\)/ }));
      const specialPay1 = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/, { selector: 'select' });

      fireEvent.change(specialPay1, { target: { value: 'REGULAR_REPROJECT' } });
      fireEvent.click(screen.getByRole('button', { name: /บันทึกการจัดประเภท/ }));

      await waitFor(() => expect(api.payroll.saveComponentTaxTreatments).toHaveBeenCalledWith(2026, [
        { employeeId: 1, component: 'SPECIAL_PAY_1', taxTreatment: 'REGULAR_REPROJECT' },
      ]));
    });

    // Sixth Opus review, 2026-07-30: `byComponent` now carries the EFFECTIVE classification
    // (server-synthesized defaults merged in), so every cell renders non-blank and the section badge
    // reads "จัดประเภทครบแล้ว" even when nobody has chosen anything. The per-cell
    // "ค่าเริ่มต้นของระบบ" hint, keyed off `explicitlyClassifiedComponents`, is the ONLY thing left
    // that tells HR "the system is defaulting this" from "someone chose this" — the branch's own
    // stated back-loading-risk mitigation. It shipped with no test; this is it, written wrong-way-round
    // (the explicitly-classified cell must NOT carry the hint).
    it('flags a synthesized default but not a cell HR actually classified', async () => {
      api.payroll.getComponentTaxTreatments.mockResolvedValue({
        taxYear: 2026,
        items: [{
          employeeId: 1,
          employeeCode: 'GLR-001',
          employeeName: 'พนักงาน ทดสอบ',
          byComponent: {
            SPECIAL_PAY_1: 'EXTRA_CUMULATIVE_ACTUAL',
            SPECIAL_PAY_2: 'REGULAR_REPROJECT',
          },
          explicitlyClassifiedComponents: ['SPECIAL_PAY_2'],
        }],
      });
      renderPayrollPage();

      fireEvent.click(await screen.findByRole('button', { name: /การจัดประเภทภาษีหัก ณ ที่จ่าย/ }));
      fireEvent.click(await screen.findByRole('button', { name: /พนักงาน ทดสอบ \(GLR-001\)/ }));

      const defaulted = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/, { selector: 'select' });
      const chosenByHr = await screen.findByLabelText(/พิเศษ 2 \(ค่าเช่าบ้าน\)/, { selector: 'select' });

      expect(defaulted.value).toBe('EXTRA_CUMULATIVE_ACTUAL');
      expect(chosenByHr.value).toBe('REGULAR_REPROJECT');
      expect(defaulted.closest('label').textContent).toContain('ค่าเริ่มต้นของระบบ');
      expect(chosenByHr.closest('label').textContent).not.toContain('ค่าเริ่มต้นของระบบ');
    });
  });
});
