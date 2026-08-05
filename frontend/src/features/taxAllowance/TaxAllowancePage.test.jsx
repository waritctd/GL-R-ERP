import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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
        withdrawMyTaxAllowanceDeclaration: vi.fn(),
        estimateMyTaxAllowanceDeclaration: vi.fn(),
        listTaxAllowanceAttachments: vi.fn(),
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

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <TaxAllowancePage user={user} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
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
    it('offers withdrawal while pending, explaining why the form is locked', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [declaration()] });
      renderPage();

      expect(await screen.findByRole('button', { name: 'ยกเลิกการยื่น' })).not.toBeNull();
      expect(screen.getByText(/แก้ไขไม่ได้ระหว่างรอ HR ตรวจสอบ/)).not.toBeNull();
      // Editing stays unavailable — withdrawal is the exit, not a direct edit.
      expect(screen.queryByRole('button', { name: 'แก้ไข / ยื่นฉบับใหม่' })).toBeNull();
    });

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

  describe('tax-year history', () => {
    it('refetches when a past year is chosen', async () => {
      renderPage();
      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear);
      });

      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });
      await waitFor(() => {
        expect(api.payroll.getMyTaxAllowanceDeclarations).toHaveBeenCalledWith(currentYear - 1);
      });
    });

    it('is read-only for a past year even when nothing was ever filed', async () => {
      renderPage();
      await screen.findByRole('heading', { name: 'แบบแจ้งรายการเพื่อการหักลดหย่อน' });

      fireEvent.change(screen.getByLabelText('ปีภาษี'), { target: { value: String(currentYear - 1) } });

      expect(await screen.findByText(new RegExp(`ยื่นหรือแก้ไขได้เฉพาะปีภาษี ${currentYear}`))).not.toBeNull();
      expect(screen.queryByRole('button', { name: 'แก้ไข / ยื่นฉบับใหม่' })).toBeNull();
    });
  });

  // The user's stated complaint (#tax-allowance-sections): "I couldn't attach a PDF while first
  // filling in the form" -- there was no declarationId yet for a brand-new declaration to attach
  // evidence to. This proves the fix end to end: a file picked mid-fill-in shows immediately
  // (staged, client-side) WITHOUT hitting the server, and is genuinely uploaded — tagged to the
  // section it was picked under — only once the declaration is actually submitted and a real
  // declarationId exists.
  describe('attaching evidence while first filling in a NOT-YET-SUBMITTED declaration', () => {
    it('stages a PDF picked during step 2, then uploads it against the real declarationId once submit succeeds', async () => {
      api.payroll.getMyTaxAllowanceDeclarations.mockResolvedValue({ items: [] }); // status NONE
      api.payroll.submitMyTaxAllowanceDeclaration.mockResolvedValue({ declarationId: 77, employeeId: 9, status: 'PENDING' });
      api.payroll.uploadTaxAllowanceAttachment.mockResolvedValue({ attachment: { attachmentId: 1 } });

      renderPage();

      fireEvent.click(await screen.findByText('ครอบครัว'));

      const file = new File(['x'], 'cert.pdf', { type: 'application/pdf' });
      const input = document.querySelector('input[type="file"]');
      fireEvent.change(input, { target: { files: [file] } });

      // Attached immediately, during fill-in -- no round trip needed to SEE it.
      expect(await screen.findByText('cert.pdf')).not.toBeNull();
      expect(screen.getByText('รอส่ง')).not.toBeNull();
      // But genuinely not sent yet -- there is no declarationId for a brand-new declaration.
      expect(api.payroll.uploadTaxAllowanceAttachment).not.toHaveBeenCalled();

      fireEvent.click(screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' }));

      await waitFor(() => expect(api.payroll.submitMyTaxAllowanceDeclaration).toHaveBeenCalled());
      // Flushed against the REAL declarationId the submit just returned, tagged with the section
      // it was picked under while filling in -- proves this is not "attach after a first submit",
      // it is the SAME pick from mid-fill-in finally reaching the server.
      await waitFor(() => expect(api.payroll.uploadTaxAllowanceAttachment).toHaveBeenCalledWith(77, file, 'family'));
    });
  });
});
