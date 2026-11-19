import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation, useNavigationType } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaxAllowancePage } from './TaxAllowancePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      payroll: {
        getTaxAllowanceCaps: vi.fn(),
        getMyTaxAllowanceDeclarations: vi.fn(),
        submitMyTaxAllowanceDeclaration: vi.fn(),
        renderMyTaxAllowanceForm: vi.fn(),
        withdrawMyTaxAllowanceDeclaration: vi.fn(),
        estimateMyTaxAllowanceDeclaration: vi.fn(),
        listTaxAllowanceAttachments: vi.fn(),
        downloadTaxAllowanceForm: vi.fn(),
        uploadTaxAllowanceAttachment: vi.fn(),
      },
    },
  };
});

const user = { role: 'employee', name: 'สมชาย ใจดี', employeeId: 9 };
const currentYear = new Date().getFullYear();

function declaration(overrides = {}) {
  return {
    declarationId: 55,
    employeeId: 9,
    status: 'PENDING',
    submittedAt: `${currentYear}-03-01T00:00:00.000Z`,
    appliedAt: null,
    appliedEffectiveMonth: null,
    effectiveMonth: null,
    expiresOn: null,
    reviewerNote: null,
    allowances: {},
    ...overrides,
  };
}

// Same idiom as TaxAllowanceReviewPage.test.jsx's `LocationProbe`: exposes the router's URL as
// text, plus `navigationType` (PUSH/REPLACE/POP via `useNavigationType`) to verify view changes
// push a history entry while year changes replace.
function LocationProbe() {
  const location = useLocation();
  const navigationType = useNavigationType();
  return (
    <div data-testid="location" data-nav-type={navigationType}>
      {`${location.pathname}${location.search}`}
    </div>
  );
}

function renderPage({ entry = '/tax-allowance', showToast = vi.fn() } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <TaxAllowancePage user={user} showToast={showToast} />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Waits for an evidence picker to actually mount before firing at it. `last` picks the sign-off panel's picker
 * when more than one ข้อ is open. */
async function findPicker({ last = false } = {}) {
  return waitFor(() => {
    const pickers = document.querySelectorAll('input[type="file"]');
    expect(pickers.length).toBeGreaterThan(0);
    return last ? pickers[pickers.length - 1] : pickers[0];
  });
}

describe('TaxAllowancePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.payroll.getTaxAllowanceCaps.mockResolvedValue({ caps: [] });
    api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] });
    api.payroll.listTaxAllowanceAttachments.mockResolvedValue({ items: [] });
    api.payroll.withdrawMyTaxAllowanceDeclaration.mockResolvedValue(undefined);
  });

  describe('withdrawal — the way out of PENDING', () => {
    it('withdraws the pending declaration after confirmation', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [declaration()] });
      renderPage();

      fireEvent.click(await screen.findByRole('button', { name: 'ยกเลิกการยื่น' }));
      // The confirm dialog repeats the label; the last match is the dialog's confirm button.
      const confirmButtons = await screen.findAllByRole('button', { name: 'ยกเลิกการยื่น' });
      fireEvent.click(confirmButtons[confirmButtons.length - 1]);

      await waitFor(() => {
        expect(api.payroll.withdrawMyTaxAllowanceDeclaration).toHaveBeenCalledWith(55);
      });
    });

    it('does not offer withdrawal once approved', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({
        items: [declaration({ status: 'APPROVED', appliedAt: `${currentYear}-04-01T00:00:00Z`, appliedEffectiveMonth: 4 })],
      });
      renderPage();

      await screen.findByText(/ใช้กับเงินเดือนแล้ว/);
      expect(screen.queryByRole('button', { name: 'ยกเลิกการยื่น' })).toBeNull();
    });
  });

  // ---------------------------------------------------------------------------------------------
  // Revising an ALREADY-CONFIRMED declaration, and getting a copy of it.
  // `TaxAllowanceDeclarationService#submitOwn` refuses only on `existsPending` and `#approve`
  // calls `supersedeApproved` first, so the server accepts a revision; `GET
  // /declarations/{id}/form.pdf` / `hrApi.downloadTaxAllowanceForm` let a filed declaration be
  // downloaded.
  // ---------------------------------------------------------------------------------------------
  describe('an already-confirmed declaration can be revised and downloaded', () => {
    const applied = (overrides = {}) => declaration({
      status: 'APPROVED',
      appliedAt: `${currentYear}-01-20T00:00:00.000Z`,
      appliedEffectiveMonth: 1,
      allowances: { lifeInsuranceAllowance: 40000 },
      ...overrides,
    });

    it('offers a revise action on an APPLIED declaration, where there used to be none', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [applied()] });
      renderPage();

      expect(await screen.findByText(/ใช้กับเงินเดือนแล้ว/)).not.toBeNull();
      expect(await screen.findByRole('button', { name: 'ยื่นฉบับแก้ไข' })).not.toBeNull();
      // Its own words, not the rejected/expired button's — this replaces a declaration in force.
      expect(screen.queryByRole('button', { name: 'แก้ไข / ยื่นฉบับใหม่' })).toBeNull();
    });

    it('a past tax year is still read-only — revising applies to the current year only', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [applied()] });
      renderPage({ entry: `/tax-allowance?year=${currentYear - 1}` });

      expect(await screen.findByText(new RegExp(`ยื่นหรือแก้ไขได้เฉพาะปีภาษี ${currentYear}`))).not.toBeNull();
      expect(screen.queryByRole('button', { name: 'ยื่นฉบับแก้ไข' })).toBeNull();
    });
  });

  // -------------------------------------------------------------------------------------------
  // Confirm before filing. Once the signed scan is staged the submit button is enabled, and
  // HTML's implicit submission hands Enter in any text field a real `event.submitter`, so both of
  // SafeForm's guards pass and Enter would file the declaration outright. The guard is not
  // Enter-specific: the dialog sits between the submit EVENT and the mutation, so every trigger
  // (Enter, click, anything added later) goes through the same gate.
  //
  // ⚠️ jsdom cannot perform implicit submission, so nothing here exercises the Enter trigger; it
  // proves a submit event does not reach the API on its own. The Enter path is covered in
  // frontend/e2e-real/loryor01-form.spec.js.
  // -------------------------------------------------------------------------------------------
  describe('filing asks for confirmation first', () => {
    async function fillToSubmittable() {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] }); // status NONE
      renderPage();
      fireEvent.click(await screen.findByRole('button', { name: /ตรวจทาน ลงนาม และยื่น/ }));
      const signed = new File(['y'], 'signed.pdf', { type: 'application/pdf' });
      fireEvent.change(await findPicker({ last: true }), { target: { files: [signed] } });
      await screen.findByText('signed.pdf');
      const submit = screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' });
      await waitFor(() => expect(submit.disabled).toBe(false));
      return submit;
    }

    it('confirming files exactly the declaration the form was holding', async () => {
      api.payroll.submitMyTaxAllowanceDeclaration.mockResolvedValue({
        declarationId: 77, employeeId: 9, status: 'PENDING',
      });
      const submit = await fillToSubmittable();

      // A value typed before the dialog opened must survive into what is actually filed. The gate holds the
      // submitted values, and handing the mutation a stale or empty snapshot is the obvious way to get this refactor
      // subtly wrong.
      fireEvent.click(screen.getByRole('button', { name: /ข้อ 7/ }));
      fireEvent.change(await screen.findByLabelText('จำนวนเงิน'), { target: { value: '4321' } });

      fireEvent.click(submit);
      const dialog = await screen.findByRole('dialog');
      fireEvent.click(within(dialog).getByRole('button', { name: 'ยื่นแบบแจ้ง' }));

      await waitFor(() => expect(api.payroll.submitMyTaxAllowanceDeclaration).toHaveBeenCalledTimes(1));
      expect(api.payroll.submitMyTaxAllowanceDeclaration.mock.calls[0][0]).toMatchObject({
        taxYear: currentYear,
        lifeInsuranceAllowance: 4321,
      });
      // The /me endpoint never carries an employeeId — the server resolves the caller from the session. Asserted
      // here because this change moved the body-building call site.
      expect(api.payroll.submitMyTaxAllowanceDeclaration.mock.calls[0][0].employeeId).toBeUndefined();
    });
  });
});
