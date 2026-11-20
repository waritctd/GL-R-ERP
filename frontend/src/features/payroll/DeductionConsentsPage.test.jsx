import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DeductionConsentsPage } from './DeductionConsentsPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      payroll: { getDeductionConsents: vi.fn(), upsertDeductionConsent: vi.fn() },
      employees: { list: vi.fn() },
    },
  };
});

const HR = { role: 'hr', employeeId: 10 };
const CEO = { role: 'ceo', employeeId: 1 };

// Field-for-field with DeductionWrittenConsentDto. Only the four CONSENT_APPLICABLE_KINDS can appear — V107's
// CHECK constraint rejects the rest at the database.
const rows = [
  {
    id: 1,
    employeeId: 3,
    employeeCode: 'GLR-1003',
    employeeName: 'สมชาย ใจดี',
    deductionKind: 'WARNING_LETTER',
    consentOnFile: true,
    consentDocumentReference: 'CONSENT-2569-0012',
    consentDate: '2026-05-04',
    notes: 'เก็บต้นฉบับไว้ที่แฟ้มบุคคล ชั้น 3',
    recordedById: 9,
    recordedAt: '2026-05-05T03:00:00Z',
    updatedById: 9,
    updatedAt: '2026-05-05T03:00:00Z',
  },
  {
    id: 2,
    employeeId: 20,
    employeeCode: 'GLR-1020',
    employeeName: 'มานี รักงาน',
    deductionKind: 'OTHER_POST_TAX',
    consentOnFile: false,
    consentDocumentReference: null,
    consentDate: null,
    notes: 'ส่งแบบฟอร์มให้พนักงานแล้ว รอเซ็นกลับ',
    recordedById: 9,
    recordedAt: '2026-06-01T03:00:00Z',
    updatedById: 9,
    updatedAt: '2026-06-02T03:00:00Z',
  },
];

function renderPage({ entry = '/payroll/deduction-consents', user, showToast } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <DeductionConsentsPage user={user} showToast={showToast} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('DeductionConsentsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.payroll.getDeductionConsents.mockResolvedValue({ items: rows });
    api.payroll.upsertDeductionConsent.mockResolvedValue({ items: [rows[0]] });
    api.employees.list.mockResolvedValue({
      employees: [
        { id: 3, code: 'GLR-1003', nameTh: 'สมชาย ใจดี', active: true },
        { id: 20, code: 'GLR-1020', nameTh: 'มานี รักงาน', active: true },
        { id: 44, code: 'GLR-1044', nameTh: 'อดีต พนักงาน', active: false },
      ],
    });
  });

  // The write half: hr only ───────────────────────────────────────────────
  // DeductionWrittenConsentService.EDIT_ROLES is Set.of("hr"), strictly narrower than VIEW_ROLES (hr + ceo).
  // FRONTEND GATING ONLY.
  async function openDialog(name) {
    fireEvent.click(screen.getByRole('button', { name }));
    return within(await screen.findByRole('dialog'));
  }

  describe('write affordances', () => {
    it('offers HR both the record action and a per-row edit', async () => {
      renderPage({ user: HR });
      await screen.findByText('สมชาย ใจดี');

      expect(screen.getByRole('button', { name: 'บันทึกหนังสือยินยอม' })).not.toBeNull();
      expect(screen.getByRole('button', { name: /แก้ไขบันทึกหนังสือยินยอมของ สมชาย ใจดี/ })).not.toBeNull();
    });

    it('offers the CEO no write control at all, on the page or on any row', async () => {
      renderPage({ user: CEO });
      await screen.findByText('สมชาย ใจดี');

      expect(screen.queryByRole('button', { name: 'บันทึกหนังสือยินยอม' })).toBeNull();
      expect(screen.queryByRole('button', { name: /แก้ไขบันทึกหนังสือยินยอม/ })).toBeNull();
      // And no employee lookup is even attempted — employees.list is HR-only server-side too.
      expect(api.employees.list).not.toHaveBeenCalled();
    });

    it('sends a recorded consent to the server as the DTO shape', async () => {
      renderPage({ user: HR });
      await screen.findByText('สมชาย ใจดี');

      const dialog = await openDialog('บันทึกหนังสือยินยอม');

      fireEvent.change(dialog.getByRole('combobox', { name: 'พนักงาน' }), { target: { value: '20' } });
      fireEvent.change(dialog.getByRole('combobox', { name: 'ประเภทการหัก' }), { target: { value: 'CUSTOMER_RETURN' } });
      fireEvent.click(dialog.getByRole('checkbox'));
      fireEvent.change(dialog.getByRole('textbox', { name: 'เลขที่เอกสาร' }), { target: { value: ' CONSENT-2569-0099 ' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

      await waitFor(() => expect(api.payroll.upsertDeductionConsent).toHaveBeenCalledWith({
        employeeId: 20,
        deductionKind: 'CUSTOMER_RETURN',
        consentOnFile: true,
        // Trimmed, and null (not '') when blank — the columns are nullable TEXT.
        consentDocumentReference: 'CONSENT-2569-0099',
        consentDate: null,
        notes: null,
      }));
    });
  });
});
